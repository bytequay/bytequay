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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.domain.BranchGuard.Health;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
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
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.RebaseOutcome;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.bytequay.app.service.threads.AutomationCoordinator;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Keeps enabled task branches caught up with their base branch. */
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
    private final PullRequestService pullRequests;
    private final ObjectMapper mapper;
    private final RemoteDevelopmentStageService remoteStages;
    private final CodeGraphUpdateCoordinator codeGraph;
    private final TaskPhaseMachine phaseMachine;

    @Autowired
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
            PullRequestService pullRequests,
            ObjectMapper mapper,
            RemoteDevelopmentStageService remoteStages,
            CodeGraphUpdateCoordinator codeGraph,
            TaskPhaseMachine phaseMachine)
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
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.remoteStages = requireNonNull(remoteStages, "remoteStages is null");
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

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
            PullRequestService pullRequests,
            ObjectMapper mapper,
            RemoteDevelopmentStageService remoteStages,
            TaskPhaseMachine phaseMachine)
    {
        this(guards, taskStore, threadStore, scheduler, turnStore, git, checks,
                agentRuns, notifications, pullRequests, mapper, remoteStages,
                CodeGraphUpdateCoordinator.disabled(), phaseMachine);
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

    /** Visible for tests: run one guard's check. Skips busy threads. */
    void checkOne(BranchGuard snapshot)
    {
        TaskPhaseMachine.withTaskLock(snapshot.taskId(), () -> {
            guards.findByTask(snapshot.taskId()).ifPresent(this::checkOneLocked);
            return null;
        });
    }

    private void checkOneLocked(BranchGuard guard)
    {
        Task task = taskStore.findTaskById(guard.taskId()).orElse(null);
        if (isStopped(guard, task)
                || task.worktreePath() == null || task.worktreePath().isBlank()
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
            Boolean checksGreen = probeChecksGreen(task);
            if (mergeBase.isPresent() && mergeBase.get().equals(baseTip.get())) {
                Health health = new Health(0, true, checksGreen);
                guards.save(guard.withState(BranchGuard.STATE_HEALTHY)
                        .withHealth(health).withLastRun(null, Instant.now()));
                return;
            }
            Integer behindBy = git.commitCountUniqueTo(worktree, baseRef, "HEAD");
            RebaseOutcome outcome = git.rebasePreview(worktree, "HEAD", baseRef);
            Health health = new Health(behindBy, outcome == RebaseOutcome.CLEAN, checksGreen);
            driveDrift(task, thread, guard.withHealth(health), worktree, baseRef, outcome);
        }
        catch (Exception e) {
            log.warn("branch guard git operation failed for task {}: {}", task.id(), e.getMessage());
            parkNeedsAttention(task, guard, null, "guard_error: " + e.getMessage());
        }
    }

    /** Mirrors cached CI health for the chip. It never starts CI repair. */
    private Boolean probeChecksGreen(Task task)
    {
        if (task.linkedPrRef() == null) {
            return null;
        }
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return null;
        }
        try {
            PullRequestDetail detail = pullRequests.getPullRequestDetail(
                    ref.get().repoFullName(), ref.get().number());
            if (detail == null) {
                return null;
            }
            return !AutomationCoordinator.aggregateChecks(
                    AutomationCoordinator.toCheckRunStates(detail.checkRuns())).isFailing();
        }
        catch (RuntimeException e) {
            log.warn("branch guard: reading cached CI state failed for task {}: {}", task.id(), e.getMessage());
            return null;
        }
    }

    private void driveDrift(
            Task task, Thread thread, BranchGuard guard, Path worktree, String baseRef, RebaseOutcome outcome)
            throws Exception
    {
        StageInstance remoteStage = remoteStages.ensureOpen(task.id());
        AgentRun run = agentRuns.openInStage(
                task.id(), AgentRun.KIND_BRANCH_GUARD, AgentRun.SOURCE_SCHEDULED,
                remoteStage.id().toString(), /* budget */ null);
        if (outcome != RebaseOutcome.CLEAN) {
            askAgentToResolve(task, thread, guard, run, baseRef,
                    "main drifted and would conflict on rebase");
            return;
        }
        try {
            git.rebase(worktree, baseRef);
            codeGraph.rebuildSync(worktree, "branch-guard-rebase");
        }
        catch (RuntimeException e) {
            codeGraph.rebuildSync(worktree, "branch-guard-rebase-failed");
            askAgentToResolve(task, thread, guard, run, baseRef, "rebase failed: " + e.getMessage());
            return;
        }
        finishClean(task, guard, run, worktree, baseRef);
    }

    /** Runs checks and pushes a caught-up branch. */
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
        boolean pushed = TaskPhaseMachine.withTaskLock(task.id(), () -> {
            BranchGuard currentGuard = guards.findByTask(task.id()).orElse(null);
            Task currentTask = taskStore.findTaskById(task.id()).orElse(null);
            if (isStopped(currentGuard, currentTask)) {
                agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "guard_stopped_before_push");
                return false;
            }
            try {
                // A rebase rewrites commit SHAs, so the push must be force-with-lease
                // even though this is a plain drift-catchup, not a rewritten fix.
                git.pushForceWithLease(worktree);
            }
            catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
                throw new IllegalStateException("branch guard push interrupted", e);
            }
            catch (IOException e) {
                throw new IllegalStateException("branch guard push failed", e);
            }
            agentRuns.transition(run.id(), AgentRun.STATUS_SUCCEEDED, "rebased_and_pushed");
            guards.save(currentGuard.withHealth(guard.health())
                    .withState(BranchGuard.STATE_HEALTHY)
                    .withLastRun(run.id(), Instant.now()));
            return true;
        });
        if (pushed) {
            log.info("branch guard rebased + pushed task {} onto {}", task.id(), baseRef);
        }
    }

    /** Starts one bounded agent turn when the mechanical rebase cannot finish. */
    private void askAgentToResolve(
            Task task, Thread thread, BranchGuard guard, AgentRun run, String baseRef, String reason)
    {
        guard = guard.withState(BranchGuard.STATE_CONFLICTED).withLastRun(run.id(), Instant.now());
        guards.save(guard);
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
                    thread, prompt, task.id(), run.stageId(),
                    TurnInitiator.unattended("branch-guard-fix"), run.id());
            guards.save(guard.withState(BranchGuard.STATE_FIXING).withLastRun(run.id(), Instant.now()));
        }
        catch (RuntimeException e) {
            agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "enqueue_failed");
            parkNeedsAttention(task, guard, run.id(), reason);
        }
    }

    /** Verifies the fix turn, then either pushes or parks for a human. */
    @EventListener
    public void onFixTurnFinished(TaskTurnFinishedEvent event)
    {
        TaskPhaseMachine.withTaskLock(event.taskId(), () -> {
            onFixTurnFinishedLocked(event);
            return null;
        });
    }

    private void onFixTurnFinishedLocked(TaskTurnFinishedEvent event)
    {
        BranchGuard guard = guards.findByTask(event.taskId()).orElse(null);
        if (guard == null || !BranchGuard.STATE_FIXING.equals(guard.state()) || guard.lastRunId() == null) {
            return;
        }
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || !matchesRunTurn(guard.lastRunId(), turn)) {
            return;
        }
        Task task = taskStore.findTaskById(event.taskId()).orElse(null);
        if (isStopped(guard, task)
                || task.worktreePath() == null || task.baseBranch() == null) {
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

    private boolean matchesRunTurn(String runId, ThreadTurn turn)
    {
        if (runId == null || turn == null) {
            return false;
        }
        String agentRunId = turn.agentRunId();
        if (agentRunId != null && !agentRunId.isBlank()) {
            return runId.equals(agentRunId);
        }
        if (!"branch-guard-fix".equals(turn.initiator().source())) {
            return false;
        }
        return turn.stageId() != null
                && agentRuns.findById(runId)
                        .map(run -> turn.stageId().equals(run.stageId()))
                        .orElse(false);
    }

    private void parkNeedsAttention(Task task, BranchGuard guard, String runId, String reason)
    {
        TaskPhaseMachine.withTaskLock(task.id(), () -> {
            Task currentTask = taskStore.findTaskById(task.id()).orElse(null);
            if (currentTask == null || currentTask.phase() == TaskPhase.COMPLETED
                    || isTerminal(currentTask.status())) {
                return null;
            }
            if (currentTask.phase() == TaskPhase.NEEDS_ATTENTION) {
                // Repair a legacy/partial row whose phase was parked without
                // the matching runtime status.
                phaseMachine.observe(
                        task.id(), TaskPhase.NEEDS_ATTENTION, "branch_guard_needs_attention");
            }
            else {
                phaseMachine.transition(
                        task.id(), TaskPhase.NEEDS_ATTENTION,
                        "branch_guard_needs_attention", Actor.AGENT);
            }
            BranchGuard current = guards.findByTask(task.id()).orElse(guard);
            guards.save(current.withState(BranchGuard.STATE_NEEDS_ATTENTION).withLastRun(runId, Instant.now()));
            return null;
        });
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

    private static boolean isTerminal(TaskStatus status)
    {
        return switch (status) {
            case COMPLETED, REMOTE_CLOSED, ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    private static boolean isStopped(BranchGuard guard, Task task)
    {
        if (guard == null || !guard.enabled()
                || BranchGuard.STATE_NEEDS_ATTENTION.equals(guard.state())
                || task == null
                || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED
                || isTerminal(task.status())) {
            return true;
        }
        return switch (task.status()) {
            case AWAITING, AWAITING_REVIEW, NEEDS_ATTENTION, PAUSED -> true;
            default -> false;
        };
    }
}
