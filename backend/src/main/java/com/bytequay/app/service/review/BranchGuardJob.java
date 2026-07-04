/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.service.review;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.BranchGuardStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationCheck;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.RebaseOutcome;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The branch guard's scheduled tick (plan-rail-runs.md R18): for every
 * enabled {@link BranchGuard} on an idle thread, fetch the task's base
 * branch, and if it drifted ahead, rebase, run the registered {@link
 * ValidationCheck}s, and push — pre-authorized the same way an armed
 * {@code ci_fix} run's per-iteration push is (R9).
 *
 * <p>A dry-run conflict preview or a real rebase failure hands off to a
 * single bounded agent turn to resolve it (the agent edits/commits/
 * continues the rebase; this job verifies the result and does the
 * checks+push afterward, the same split {@code CiFixRunExecutor} uses).
 * If the thread isn't idle, or the agent's one attempt doesn't leave the
 * branch caught up, the guard parks at {@code needs_attention} and
 * notifies — there's still no unbounded retry loop or check-fixing here.
 * A check failure after a clean rebase also parks directly (unchanged) —
 * that's shipped-CI-fix territory, not this job's.
 *
 * <p>One sweep == the "nightly" schedule for every guard (the only
 * schedule value v1 supports); {@code lastCheckedAt} is observability,
 * not a per-guard due-check gate.
 */
@Component
public class BranchGuardJob
{
    private static final Logger log = LoggerFactory.getLogger(BranchGuardJob.class);

    private static final long NIGHTLY_MS = 24L * 60 * 60 * 1000;

    private final BranchGuardStore guards;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final ThreadTurnStore turnStore;
    private final GitRunner git;
    private final List<ValidationCheck> checks;
    private final AgentRunService agentRuns;
    private final NotificationService notifications;
    private final ObjectMapper mapper;

    public BranchGuardJob(
            BranchGuardStore guards,
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            GitRunner git,
            List<ValidationCheck> checks,
            AgentRunService agentRuns,
            NotificationService notifications,
            ObjectMapper mapper)
    {
        this.guards = requireNonNull(guards, "guards is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.git = requireNonNull(git, "git is null");
        this.checks = requireNonNull(checks, "checks is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Scheduled(fixedDelay = NIGHTLY_MS, initialDelay = NIGHTLY_MS)
    public void tick()
    {
        for (BranchGuard guard : guards.findEnabled()) {
            try {
                checkOne(guard);
            }
            catch (RuntimeException e) {
                log.warn("branch guard tick for task {} failed: {}", guard.taskId(), e.getMessage());
            }
        }
    }

    /** Visible for the unit test: run one guard's check. A no-op (retried
     *  next sweep) if the task's thread isn't idle — the mechanical rebase
     *  below mutates the same worktree a live turn might be mid-edit in. */
    void checkOne(BranchGuard guard)
    {
        Task task = taskStore.findTaskById(guard.taskId()).orElse(null);
        if (task == null || task.worktreePath() == null || task.worktreePath().isBlank()
                || task.baseBranch() == null) {
            return;
        }
        Thread thread = threadStore.findThreadById(task.threadId()).orElse(null);
        if (thread == null || thread.status() != ThreadStatus.IDLE) {
            log.info("branch guard: thread not idle for task {}; retrying next sweep", task.id());
            return;
        }
        Path worktree = Path.of(task.worktreePath());
        String baseRef = "origin/" + task.baseBranch();
        try {
            git.fetch(worktree);
            Optional<String> baseTip = git.resolveCommitSha(worktree, baseRef);
            if (baseTip.isEmpty()) {
                return; // base ref unresolvable — nothing to compare against yet.
            }
            Optional<String> mergeBase = git.mergeBase(worktree, "HEAD", baseRef);
            if (mergeBase.isPresent() && mergeBase.get().equals(baseTip.get())) {
                guards.save(guard.withState(BranchGuard.STATE_IN_SYNC).withLastRun(null, Instant.now()));
                return;
            }
            driveDrift(task, thread, guard, worktree, baseRef);
        }
        catch (Exception e) {
            log.warn("branch guard git operation failed for task {}: {}", task.id(), e.getMessage());
            parkNeedsAttention(task, guard, null, "guard_error: " + e.getMessage());
        }
    }

    private void driveDrift(Task task, Thread thread, BranchGuard guard, Path worktree, String baseRef)
            throws Exception
    {
        AgentRun run = agentRuns.open(
                task.id(), AgentRun.KIND_BRANCH_GUARD, AgentRun.SOURCE_SCHEDULED,
                /* parentStageId */ null, StageType.BRANCH_GUARD_STAGE, /* budget */ null);
        RebaseOutcome outcome = git.rebasePreview(worktree, "HEAD", baseRef);
        if (outcome != RebaseOutcome.CLEAN) {
            askAgentToResolve(task, thread, guard, run, baseRef,
                    "main drifted and would conflict on rebase");
            return;
        }
        try {
            git.rebase(worktree, baseRef);
        }
        catch (RuntimeException e) {
            askAgentToResolve(task, thread, guard, run, baseRef, "rebase failed: " + e.getMessage());
            return;
        }
        finishClean(task, guard, run, worktree, baseRef);
    }

    /** Runs checks + pushes a mechanically-clean rebase (or, after a fix
     *  turn, one the agent left caught up). Shared by both entry points so
     *  the check/push rule is identical either way. */
    private void finishClean(Task task, BranchGuard guard, AgentRun run, Path worktree, String baseRef)
            throws Exception
    {
        List<ValidationFailure> failures = checks.stream()
                .flatMap(c -> c.run(task.id(), worktree).stream())
                .toList();
        if (!failures.isEmpty()) {
            agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "checks_failed_after_rebase");
            parkNeedsAttention(task, guard, run.id(), "local checks failed after rebasing onto " + baseRef);
            return;
        }
        // A rebase rewrites commit SHAs, so the push must be force-with-lease
        // even though this is a plain drift-catchup, not a rewritten fix.
        git.pushForceWithLease(worktree);
        agentRuns.transition(run.id(), AgentRun.STATUS_SUCCEEDED, "rebased_and_pushed");
        guards.save(guard.withState(BranchGuard.STATE_IN_SYNC).withLastRun(run.id(), Instant.now()));
        log.info("branch guard rebased + pushed task {} onto {}", task.id(), baseRef);
    }

    /** One bounded agent turn to resolve a conflict the mechanical path
     *  can't: the agent edits/commits/continues the rebase with its normal
     *  shell access; {@link #onFixTurnFinished} verifies the result and
     *  runs checks+push itself (mirrors how {@code CiFixRunExecutor}
     *  splits "agent edits" from "executor pushes"). Parks immediately,
     *  same as before, if the thread isn't idle after all (a race) or the
     *  turn can't be enqueued. */
    private void askAgentToResolve(
            Task task, Thread thread, BranchGuard guard, AgentRun run, String baseRef, String reason)
    {
        if (thread.status() != ThreadStatus.IDLE) {
            agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "unresolvable_conflict");
            parkNeedsAttention(task, guard, run.id(), reason);
            return;
        }
        String prompt = "The nightly branch guard found " + baseRef + " has drifted ahead of this "
                + "task's branch, and " + reason + ". Resolve it: run `git rebase " + baseRef + "` "
                + "(or continue one already in progress), fix any conflicts, and commit. Do NOT push "
                + "— stop once the rebase is clean and committed; pushing happens automatically once "
                + "you're done.";
        try {
            scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), run.stageId(), TurnInitiator.unattended("branch-guard-fix"));
            guards.save(guard.withState(BranchGuard.STATE_FIXING).withLastRun(run.id(), Instant.now()));
        }
        catch (RuntimeException e) {
            agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "enqueue_failed");
            parkNeedsAttention(task, guard, run.id(), reason);
        }
    }

    /** The fix turn finishing means the agent is done trying (or ran out of
     *  turn) — re-check whether the branch actually caught up with base. If
     *  so, finish exactly like the mechanical path (checks + push); if not,
     *  park for the human, leaving whatever state the agent left so they
     *  can pick up where it stopped rather than silently aborting it. */
    @EventListener
    public void onFixTurnFinished(TaskTurnFinishedEvent event)
    {
        BranchGuard guard = guards.findByTask(event.taskId()).orElse(null);
        if (guard == null || !BranchGuard.STATE_FIXING.equals(guard.state()) || guard.lastRunId() == null) {
            return;
        }
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.stageId() == null || !isRunsBackingStage(guard.lastRunId(), turn.stageId())) {
            return;
        }
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (task == null || task.worktreePath() == null || task.baseBranch() == null) {
            return;
        }
        Path worktree = Path.of(task.worktreePath());
        String baseRef = "origin/" + task.baseBranch();
        try {
            if (git.hasUncommittedChanges(worktree)) {
                parkNeedsAttention(task, guard, guard.lastRunId(),
                        "agent left the rebase unfinished (uncommitted/unmerged changes remain)");
                return;
            }
            Optional<String> baseTip = git.resolveCommitSha(worktree, baseRef);
            Optional<String> mergeBase = git.mergeBase(worktree, "HEAD", baseRef);
            if (baseTip.isEmpty() || mergeBase.isEmpty() || !mergeBase.get().equals(baseTip.get())) {
                parkNeedsAttention(task, guard, guard.lastRunId(),
                        "agent attempted to resolve the conflict but the branch is still behind " + baseRef);
                return;
            }
            AgentRun run = agentRuns.findById(guard.lastRunId()).orElseThrow();
            finishClean(task, guard, run, worktree, baseRef);
        }
        catch (Exception e) {
            log.warn("branch guard fix-turn verification failed for task {}: {}", task.id(), e.getMessage());
            parkNeedsAttention(task, guard, guard.lastRunId(), "guard_error: " + e.getMessage());
        }
    }

    private boolean isRunsBackingStage(String runId, String stageId)
    {
        return agentRuns.findById(runId).map(AgentRun::stageId).map(stageId::equals).orElse(false);
    }

    private void parkNeedsAttention(Task task, BranchGuard guard, String runId, String reason)
    {
        guards.save(guard.withState(BranchGuard.STATE_NEEDS_ATTENTION).withLastRun(runId, Instant.now()));
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", "branch_guard_needs_attention");
            payload.put("detail", reason);
            notifications.notifyNeedsAttention(task.threadId(), task.id(), mapper.writeValueAsString(payload));
        }
        catch (Exception e) {
            log.warn("branch guard needs-attention notify failed for task {}: {}", task.id(), e.getMessage());
        }
    }
}
