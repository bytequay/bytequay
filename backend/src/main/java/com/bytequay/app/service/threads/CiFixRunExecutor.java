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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * The CI-fixing loop — lifted from {@link AutomationCoordinator} unchanged
 * (plan-rail-runs.md R7): watch checks (detection stays in AC, which calls
 * in here), read the failing log, fix, commit, re-run, budgeted iterations.
 * Only the bookkeeping moved — attempts/iterations live on an {@link
 * AgentRun} row and the turn runs inside Remote Development, instead of an
 * in-memory map + a phase-driven {@code CI_FIXING_STAGE} side effect.
 *
 * <p>{@link #autoFixTriggered} and {@link #ciFixCooldown} stay in-memory,
 * non-durable maps exactly as they were on {@code AutomationCoordinator} —
 * they're process-lifetime dedup/pacing guards, not state the run row is
 * meant to carry.
 */
@Component
public class CiFixRunExecutor
{
    private static final Logger log = LoggerFactory.getLogger(CiFixRunExecutor.class);

    /** Total autonomous CI-fix attempts for a shipped task before we
     *  hand it to the user. Attempt 1 is the cheap re-run (flaky guard);
     *  attempts 2–5 are agent fix turns. After this the loop escalates
     *  to a NEEDS_ATTENTION notification per the post-ship design. */
    static final int MAX_CI_FIX_ATTEMPTS = 5;

    /** Cap the CI-fix error summary stamped on the run's headline so a
     *  giant log dump can't bloat the row. */
    private static final int CI_ERROR_MESSAGE_CAP = 500;

    /** After triggering a re-run or an agent fix turn, give CI this long
     *  to actually re-run before the next sweep acts again. Without it,
     *  detail-cache lag (the cached run still reads "failure" for a sweep
     *  or two after we re-ran) would make us skip the cheap re-run and
     *  spend an agent turn prematurely. */
    private static final Duration CI_FIX_COOLDOWN = Duration.ofMinutes(4);

    private final WorktreeLeaseService leaseService;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final WorkspaceStore workspaceStore;
    private final NotificationService notificationService;
    private final ThreadTurnScheduler scheduler;
    private final PullRequestService pullRequests;
    private final PRService localPrs;
    private final GitRunner git;
    private final ObjectMapper mapper;
    private final ThreadTurnStore turnStore;
    private final AgentRunService agentRuns;
    private final RemoteDevelopmentStageService remoteStages;
    private final TaskPhaseMachine phaseMachine;

    /** Tracks tasks that already had an auto-fix turn queued during this
     *  process's lifetime — the dashboard/opt-in path's dedup, moved as-is
     *  from AutomationCoordinator (not run-row state; see class doc). */
    private final Set<String> autoFixTriggered = ConcurrentHashMap.newKeySet();

    /** Per-task cooldown gate: the earliest instant the next CI-fix sweep
     *  may act on this task again. Moved as-is from AutomationCoordinator. */
    private final ConcurrentHashMap<String, Instant> ciFixCooldown = new ConcurrentHashMap<>();

    public CiFixRunExecutor(
            WorktreeLeaseService leaseService,
            TaskStore taskStore,
            ThreadStore threadStore,
            WorkspaceStore workspaceStore,
            NotificationService notificationService,
            ThreadTurnScheduler scheduler,
            PullRequestService pullRequests,
            PRService localPrs,
            GitRunner git,
            ObjectMapper mapper,
            ThreadTurnStore turnStore,
            AgentRunService agentRuns,
            RemoteDevelopmentStageService remoteStages,
            TaskPhaseMachine phaseMachine)
    {
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.notificationService = requireNonNull(notificationService, "notificationService is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.localPrs = requireNonNull(localPrs, "localPrs is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.remoteStages = requireNonNull(remoteStages, "remoteStages is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
    }

    /**
     * After an autonomous CI-fix agent turn finishes, push its commit
     * for it. The agent fixes + commits but cannot push — raw {@code git push}
     * is blocked (it must publish through the app, never raw git). Force-with-
     * lease so a branch the agent rebased onto its base still lands; the lease
     * still refuses if the remote moved unexpectedly. The push runs off-thread
     * so the turn-completion path isn't blocked on the network.
     */
    @EventListener
    public void autoPushAfterCiFix(TaskTurnFinishedEvent event)
    {
        if (event.failed()) {
            return;
        }
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.initiator() == null
                || !("ci-fix-shipped".equals(turn.initiator().source())
                || "auto-fix-ci-fail".equals(turn.initiator().source()))) {
            return;
        }
        boolean shippedEpisode = "ci-fix-shipped".equals(turn.initiator().source());
        java.lang.Thread.startVirtualThread(
                () -> pushCiFix(event.taskId(), turn.agentRunId(), shippedEpisode, event.codeChanged()));
    }

    private void pushCiFix(
            String taskId, String agentRunId, boolean shippedEpisode, boolean codeChanged)
    {
        TaskPhaseMachine.withTaskLock(taskId, () -> {
            pushCiFixLocked(taskId, agentRunId, shippedEpisode, codeChanged);
            return null;
        });
    }

    /** Re-read every durable guard after the async hand-off and while holding
     *  the task lifecycle lock. A completion event may have been queued just
     *  before the task was parked, closed, or merged. */
    private void pushCiFixLocked(
            String taskId, String agentRunId, boolean shippedEpisode, boolean codeChanged)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        AgentRun run = agentRunId == null ? null : agentRuns.findById(agentRunId).orElse(null);
        if (task == null
                || isPushBlocked(task)
                || task.worktreePath() == null
                || task.worktreePath().isBlank()
                || run == null
                || !AgentRun.KIND_CI_FIX.equals(run.kind())
                || !taskId.equals(run.taskId())
                // Shipped CI episodes stay RUNNING until live CI turns green;
                // dashboard fixes complete with their one scheduler turn.
                // This also rejects delayed shipped completion events after
                // green has already terminalised the episode.
                || !(shippedEpisode
                ? AgentRun.STATUS_RUNNING.equals(run.status())
                : AgentRun.STATUS_SUCCEEDED.equals(run.status()))) {
            return;
        }
        Path worktree = Path.of(task.worktreePath());
        try {
            boolean dirty = git.hasUncommittedChanges(worktree);
            if (!codeChanged && !dirty) {
                agentRuns.updateHeadline(run.id(), "No code changes; retry CI manually");
                if (shippedEpisode) {
                    parkTask(task, "ci_fix_no_changes");
                    agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "no_code_changes");
                }
                log.info("CI-fix turn made no code changes for task {}; no push requested", task.id());
                return;
            }
            // Belt-and-braces: if the agent forgot to commit, checkpoint its
            // edits (minus app-managed hook files) so the fix isn't lost.
            if (dirty) {
                git.stageAll(worktree, List.of(WorktreeService.HOOK_DIR_REL));
                git.commit(worktree, "ByteQuay: CI-fix changes");
            }
            git.pushForceWithLease(worktree);
            log.info("Auto-pushed CI-fix for task {} ({})", task.id(), task.linkedPrRef());
        }
        catch (IOException e) {
            log.warn("Auto-push after CI-fix for task {} failed: {}", task.id(), e.getMessage());
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            log.warn("Auto-push after CI-fix for task {} interrupted", task.id());
        }
    }

    /**
     * Decides what to do with a candidate task whose linked PR is
     * failing CI. Four outcomes:
     *
     *   * the repo hasn't opted in to auto-fix → human-only, no
     *     headless run. The NEEDS_ATTENTION row already lights up the
     *     bell; the user clicks in and handles it themselves.
     *   * the repo IS opted in BUT the worktree is currently leased
     *     (the human is live-editing the same branch) → defer, log
     *     "would auto-fix later." The model doc explicitly forbids
     *     barging in on a worktree the human is using.
     *   * the repo is opted in AND the worktree is free AND the
     *     owning thread is IDLE → enqueue a headless turn through the
     *     agent scheduler with a CI-fail prompt. The CLI lane cap, the
     *     worktree lease, the permission gate are all the scheduler's
     *     concern; the coordinator just supplies the prompt.
     *   * any other thread state (RUNNING, AWAITING, terminal, …) →
     *     defer to the next sweep so we don't interrupt the user or
     *     re-animate a finished thread.
     */
    void tryAutoFix(Task task, String repoFullName, List<String> failingChecks,
            List<PrCheckRunState> failingRuns)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        Optional<Thread> threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty()) {
            log.warn("auto-fix skipped: owning thread {} not found for task {}",
                    task.threadId(), task.id());
            return;
        }
        Thread thread = threadOpt.get();
        String workspaceId = thread.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            log.warn("auto-fix skipped: thread {} has no workspace (task {})",
                    thread.id(), task.id());
            return;
        }
        Optional<WorkspaceRepo> ws = workspaceStore.findRepo(workspaceId, repoFullName);
        if (ws.isEmpty() || !ws.get().autoFixEnabled()) {
            log.debug("auto-fix not enabled for {} in workspace {} (task {}); NEEDS_ATTENTION-only",
                    repoFullName, workspaceId, task.id());
            return;
        }
        if (leaseService.isHeldByAnotherTask(task.worktreePath(), task.id())) {
            log.info("auto-fix deferred: worktree {} is held (task {}, PR #{})",
                    task.worktreePath(), task.id(), task.linkedPrNumber());
            return;
        }
        // Already queued during this process's lifetime → skip. Each
        // 60-sec sweep would otherwise queue a fresh turn for as long
        // as CI stays red.
        if (!autoFixTriggered.add(task.id())) {
            log.debug("auto-fix already queued earlier for task {}; skipping", task.id());
            return;
        }
        if (thread.status() != ThreadStatus.IDLE) {
            // Not strictly an error — RUNNING means the user is
            // mid-turn; AWAITING means a permission card is up;
            // terminals are off-limits. In every case the next sweep
            // re-evaluates, so undo the dedup so we can retry.
            autoFixTriggered.remove(task.id());
            log.info("auto-fix deferred: thread {} is {} (task {}, PR #{})",
                    thread.id(), thread.status(), task.id(), task.linkedPrNumber());
            return;
        }
        StageInstance remoteStage = remoteStages.ensureOpen(task.id());
        AgentRun run = agentRuns.openInStage(
                task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                remoteStage.id().toString(), MAX_CI_FIX_ATTEMPTS);
        String prompt = buildAutoFixPrompt(task, repoFullName, failingChecks);
        try {
            // Bind the task id + the run's own stage so the turn runs on the
            // task's (worktree-leased) agent and its messages land in
            // stage_messages — never the read-only trunk planner, never the
            // thread slice.
            String turnId = scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), remoteStage.id().toString(),
                    TurnInitiator.unattended("auto-fix-ci-fail"), run.id());
            agentRuns.recordIteration(run.id(), headlineFor(failingRuns));
            log.info("auto-fix queued: task {} on {} (worktree {}, PR #{}) → turn {}",
                    task.id(), repoFullName, task.worktreePath(),
                    task.linkedPrNumber(), turnId);
        }
        catch (RuntimeException e) {
            autoFixTriggered.remove(task.id());
            log.warn("auto-fix enqueue failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    /** Composes the agent's first prompt for an auto-fix turn. Names
     *  the failing checks so the CLI can grep logs by check name
     *  rather than re-discovering them, and lists the repo + PR so
     *  the agent can attach gh / API calls correctly. */
    private static String buildAutoFixPrompt(
            Task task, String repoFullName, List<String> failingChecks)
    {
        StringBuilder out = new StringBuilder();
        out.append("CI is failing on ").append(repoFullName)
                .append(" PR #").append(task.linkedPrNumber()).append(".\n");
        if (failingChecks != null && !failingChecks.isEmpty()) {
            out.append("Failing checks:\n");
            for (String name : failingChecks) {
                out.append("  - ").append(name).append('\n');
            }
        }
        out.append('\n')
                .append("Investigate the failure(s) from the CI logs in this worktree, ")
                .append("propose a fix on the existing branch, and run the local checks ")
                .append("(`mvn verify` for the backend, `npx tsc --noEmit` + `npm test` ")
                .append("for the frontend), then commit the fix on the existing branch. ")
                .append("Do not push or comment on the PR yourself. This repo's CI auto-fix ")
                .append("setting is the user's standing authorization: ByteQuay pushes the ")
                .append("commit with force-with-lease after this turn finishes, then CI reruns. ")
                .append("Commit and stop; do not open a separate review gate.");
        return out.toString();
    }

    /**
     * Drives one step of the autonomous post-ship CI-fix loop for a shipped
     * task whose linked PR is failing CI, off the PR's live state (fetched
     * directly by {@code owner/repo#n} — the same call the PR panel and the
     * lifecycle driver use, so it sees the real check-run state whether or
     * not the dashboard sync has cached the PR). The sequence, paced by the
     * 60-second sweep and {@link #CI_FIX_COOLDOWN}:
     *
     * <ol>
     *   <li>iteration 0 → <b>re-run</b> the failed checks in place — the
     *       cheapest way to clear a flaky/transient failure before
     *       spending an agent;</li>
     *   <li>iterations 1–4 → spawn an <b>agent fix turn</b> that fixes and
     *       pushes when it can; a no-change result parks for an explicit
     *       user retry instead of silently burning another iteration;</li>
     *   <li>at the cap → <b>escalate</b> to a NEEDS_ATTENTION row, fail the
     *       run, and stop acting (the user takes over).</li>
     * </ol>
     *
     * Returns true only when this step wrote a user-facing notification.
     */
    boolean driveShippedCiFix(Task task, String repoFullName, AutomationCoordinator.CiAggregate ci)
    {
        Instant now = Instant.now();
        Instant eligibleAt = ciFixCooldown.get(task.id());
        if (eligibleAt != null && now.isBefore(eligibleAt)) {
            // Last action's CI run hasn't had time to report back yet.
            return false;
        }
        StageInstance remoteStage = remoteStages.ensureOpen(task.id());
        AgentRun run = agentRuns.openInStage(
                task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                remoteStage.id().toString(), MAX_CI_FIX_ATTEMPTS);
        if (run.iterations() >= attemptLimit(run)) {
            return escalateShippedCiFix(run, task, repoFullName, ci);
        }
        if (run.iterations() == 0) {
            rerunShippedCi(run, task, repoFullName, now);
            return false;
        }
        enqueueShippedCiFixTurn(run, task, repoFullName, ci.failingNames(), ci.failingRuns(), now);
        return false;
    }

    /** A live shipped ci_fix run whose CI has gone green closes out as
     *  succeeded — nothing in the pre-split code ever explicitly closed the
     *  loop (the in-memory attempt counter just went stale); the run row
     *  needs an explicit terminal status so the rail/episode fold can show
     *  it done instead of stuck "running" forever. */
    void closeIfGreen(Task task)
    {
        agentRuns.findByTask(task.id(), AgentRun.KIND_CI_FIX, null).stream()
                .filter(AgentRun::isLive)
                .findFirst()
                .ifPresent(run -> {
                    agentRuns.updateHeadline(run.id(), "CI passed after " + run.iterations()
                            + (run.iterations() == 1 ? " attempt" : " attempts"));
                    agentRuns.transition(run.id(), AgentRun.STATUS_SUCCEEDED, "checks_green");
                });
    }

    /** Re-runs the failed checks on the PR's head commit (the cheap flaky
     *  guard, iteration 0). Resolves the head SHA from the PR itself via the
     *  number overload, NOT the local worktree — a reaped or missing worktree
     *  must not dead-end the loop (it used to skip every sweep on "could not
     *  resolve HEAD"). Bumps the iteration count and arms the cooldown. */
    private void rerunShippedCi(AgentRun run, Task task, String repoFullName, Instant now)
    {
        if (task.linkedPrNumber() == null) {
            return;
        }
        try {
            int n = pullRequests.rerunFailedChecks(repoFullName, task.linkedPrNumber());
            recordRerun(task, n);
            agentRuns.recordIteration(run.id(), n == 1
                    ? "Re-ran 1 failed CI workflow"
                    : "Re-ran " + n + " failed CI workflows");
            ciFixCooldown.put(task.id(), now.plus(CI_FIX_COOLDOWN));
            log.info("CI re-run requested for shipped task {} on {} PR #{}: {} run(s)",
                    task.id(), repoFullName, task.linkedPrNumber(), n);
        }
        catch (RuntimeException e) {
            log.warn("CI re-run failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    private void recordRerun(Task task, int workflowCount)
    {
        try {
            PR pr = localPrs.findByTask(task.id()).orElse(null);
            if (pr == null) {
                return;
            }
            List<PRCommit> commits = localPrs.commits(pr.id());
            String headSha = commits.isEmpty() ? null : commits.get(commits.size() - 1).sha();
            boolean userTriggered = taskStore.listPhaseEvents(task.id()).stream()
                    .max((left, right) -> left.transitionedAt().compareTo(right.transitionedAt()))
                    .map(event -> "user_retried_ci".equals(event.reason()))
                    .orElse(false);
            localPrs.recordRemoteCiRerun(
                    pr.id(), userTriggered ? "user" : "automatic", headSha, workflowCount);
        }
        catch (RuntimeException e) {
            log.warn("recording CI rerun timeline event for task {} failed: {}",
                    task.id(), e.getMessage());
        }
    }

    /** Spawns an agent fix turn for a shipped task whose re-run didn't
     *  clear CI. Same worktree-free / thread-IDLE gating as the dashboard
     *  auto-fix, but always-on (no per-repo opt-in) since the task entered
     *  the loop explicitly on ship. Bumps the iteration count only when a
     *  turn was actually queued; a deferral retries on the next sweep. */
    private void enqueueShippedCiFixTurn(
            AgentRun run, Task task, String repoFullName, List<String> failingChecks,
            List<PrCheckRunState> failingRuns, Instant now)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        if (leaseService.isHeldByAnotherTask(task.worktreePath(), task.id())) {
            log.info("shipped CI-fix deferred: worktree {} held (task {}, PR #{})",
                    task.worktreePath(), task.id(), task.linkedPrNumber());
            return;
        }
        Optional<Thread> threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty()) {
            log.warn("shipped CI-fix skipped: thread {} not found (task {})",
                    task.threadId(), task.id());
            return;
        }
        Thread thread = threadOpt.get();
        if (thread.status() != ThreadStatus.IDLE) {
            log.info("shipped CI-fix deferred: thread {} is {} (task {}, PR #{})",
                    thread.id(), thread.status(), task.id(), task.linkedPrNumber());
            return;
        }
        String prompt = buildShippedCiFixPrompt(
                task, repoFullName, failingChecks, priorStageContext(task, repoFullName));
        try {
            // Bind the task id + the run's own stage: a shipped task is
            // IN_REVIEW (empty active-task projection) so the no-id enqueue
            // would misroute to the read-only trunk; the explicit stage keeps
            // the fix's messages in stage_messages instead of the thread slice.
            String turnId = scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), run.stageId(),
                    TurnInitiator.unattended("ci-fix-shipped"), run.id());
            AgentRun updated = agentRuns.recordIteration(run.id(), headlineFor(failingRuns));
            ciFixCooldown.put(task.id(), now.plus(CI_FIX_COOLDOWN));
            log.info("shipped CI-fix queued: task {} on {} PR #{} → turn {} (iteration {})",
                    task.id(), repoFullName, task.linkedPrNumber(), turnId, updated.iterations());
        }
        catch (RuntimeException e) {
            log.warn("shipped CI-fix enqueue failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    /** Hands a stubbornly-red shipped PR to the user after the autonomous
     *  budget is spent. Reuses the NEEDS_ATTENTION row + UNREAD dedup so a
     *  PR that stays red doesn't re-notify every sweep. Fails the run. */
    private boolean escalateShippedCiFix(
            AgentRun run, Task task, String repoFullName, AutomationCoordinator.CiAggregate ci)
    {
        parkTask(task, "ci_fix_attempts_exhausted");
        agentRuns.transition(run.id(), AgentRun.STATUS_FAILED, "attempts_exhausted");
        if (hasOpenNotificationForTask(task.id())) {
            return false;
        }
        try {
            String payloadJson = mapper.writeValueAsString(new AutomationCoordinator.CiFailingPayload(
                    repoFullName, task.linkedPrNumber(), ci.failingNames(), ci.total()));
            notificationService.notifyNeedsAttention(task.threadId(), task.id(), payloadJson);
            log.info("shipped CI-fix gave up after {} attempts on {} PR #{} (task {}); escalated",
                    attemptLimit(run), repoFullName, task.linkedPrNumber(), task.id());
            return true;
        }
        catch (JsonProcessingException e) {
            log.warn("Failed to write CI-fail escalation payload for task {}: {}",
                    task.id(), e.getMessage());
            return false;
        }
    }

    private static int attemptLimit(AgentRun run)
    {
        return run.budget() == null ? MAX_CI_FIX_ATTEMPTS : run.budget();
    }

    /** Persist both task axes before returning control to the periodic scan.
     *  Otherwise the failed run is no longer live, so the next scan opens a
     *  fresh iteration-0 run and silently restarts the exhausted loop. */
    private void parkTask(Task task, String reason)
    {
        Task current = taskStore.findTaskById(task.id()).orElse(task);
        if (isTerminal(current)) {
            return;
        }
        if (current.phase() != TaskPhase.NEEDS_ATTENTION) {
            phaseMachine.transition(
                    current.id(), TaskPhase.NEEDS_ATTENTION, reason, Actor.AGENT);
        }
        if (current.status() != TaskStatus.NEEDS_ATTENTION) {
            taskStore.saveTask(current.withStatus(TaskStatus.NEEDS_ATTENTION));
        }
    }

    private static boolean isTerminal(Task task)
    {
        if (task.phase() == TaskPhase.COMPLETED) {
            return true;
        }
        return switch (task.status()) {
            case COMPLETED, REMOTE_CLOSED, ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    private static boolean isPushBlocked(Task task)
    {
        if (task.phase() == TaskPhase.NEEDS_ATTENTION || task.phase() == TaskPhase.COMPLETED) {
            return true;
        }
        return switch (task.status()) {
            case AWAITING, AWAITING_REVIEW, NEEDS_ATTENTION, PAUSED,
                    COMPLETED, REMOTE_CLOSED, ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    /**
     * Context a fresh CI-fix stage agent gets seeded with, since it no longer
     * resumes the Development session: the task's plan/agenda and the
     * Development summary (the drafted PR description). Assembled from existing
     * rows — no extra agent turn. Empty when neither is available.
     */
    private String priorStageContext(Task task, String repoFullName)
    {
        StringBuilder ctx = new StringBuilder();
        if (task.agendaJson() != null && !task.agendaJson().isBlank()) {
            ctx.append("Plan / agenda for this task:\n").append(task.agendaJson()).append("\n\n");
        }
        if (task.linkedPrNumber() != null) {
            try {
                PullRequestDetail pr = pullRequests.getPullRequestDetail(
                        repoFullName, task.linkedPrNumber());
                if (pr != null && pr.body() != null && !pr.body().isBlank()) {
                    ctx.append("What this PR set out to do (its description, written when the ")
                            .append("development work shipped):\n").append(pr.body()).append("\n\n");
                }
            }
            catch (RuntimeException e) {
                log.debug("prior-stage seed: PR detail fetch failed for task {}: {}",
                        task.id(), e.getMessage());
            }
        }
        return ctx.toString();
    }

    /** The agent's first prompt for an autonomous shipped CI-fix turn. The
     *  agent first attributes the failure. Deterministic failures inherited
     *  from the base are fixed as the first PR commit; branch-caused failures
     *  are fixed at the tip. Flakes and infrastructure failures only re-run. */
    private static String buildShippedCiFixPrompt(
            Task task, String repoFullName, List<String> failingChecks, String priorContext)
    {
        String baseBranch = task.baseBranch() == null || task.baseBranch().isBlank()
                ? "the PR base branch"
                : "the PR base branch `" + task.baseBranch() + "`";
        StringBuilder out = new StringBuilder();
        if (priorContext != null && !priorContext.isBlank()) {
            out.append("## Context from prior stages\n")
                    .append("You are a fresh agent for the CI-fixing stage — you did NOT do the ")
                    .append("development work, so here is what came before:\n\n")
                    .append(priorContext)
                    .append("---\n\n");
        }
        out.append("CI is failing on the shipped PR ").append(repoFullName)
                .append(" #").append(task.linkedPrNumber()).append(".\n");
        if (failingChecks != null && !failingChecks.isEmpty()) {
            out.append("Failing checks:\n");
            for (String name : failingChecks) {
                out.append("  - ").append(name).append('\n');
            }
        }
        out.append('\n')
                .append("First decide whether these failures are caused by this branch's ")
                .append("changes. If the failure is flaky or caused by infrastructure/network ")
                .append("trouble, do not change code: say so briefly and stop so the system ")
                .append("can re-run the checks.\n")
                .append("If a deterministic failure reproduces on ").append(baseBranch)
                .append(" without this PR's commits, fix it too. Preserve every original PR ")
                .append("commit and its order, but rewrite the branch so the base-CI repair is ")
                .append("the first commit after the merge base, followed by all original PR ")
                .append("commits. Resolve any replay conflicts and test the final combined ")
                .append("history before stopping.\n")
                .append("If they ARE caused by this branch, fix them on the current branch, ")
                .append("run the local checks to confirm green (`mvn verify` for the backend, ")
                .append("`npx tsc --noEmit` + `npm test` for the frontend), then commit your fix ")
                .append("with a normal `git commit`. Do NOT run `git push` yourself — direct "
                        + "pushes are blocked. ByteQuay pushes your commit for you (with "
                        + "--force-with-lease, so rebasing onto the base first is fine) and CI "
                        + "re-runs automatically. This is an autonomous CI-fix turn: commit and "
                        + "stop, do not wait for review.");
        return out.toString();
    }

    private boolean hasOpenNotificationForTask(String taskId)
    {
        // Treat any UNREAD NEEDS_ATTENTION notification on this task as
        // "already told the user, don't pile on" — mirrors
        // AutomationCoordinator.hasOpenNotificationForTask exactly.
        for (Notification n : notificationService.listUnread()) {
            if (n.kind() == NotificationKind.NEEDS_ATTENTION
                    && taskId.equals(n.taskId())) {
                return true;
            }
        }
        return false;
    }

    /** Build the run headline from the first failing check — its name +
     *  error summary (capped). Null when there's nothing failing to
     *  attribute (mirrors the pre-split iteration-event enrichment). */
    private static String headlineFor(List<PrCheckRunState> failingRuns)
    {
        if (failingRuns == null || failingRuns.isEmpty()) {
            return null;
        }
        PrCheckRunState first = failingRuns.get(0);
        String name = first.name() == null ? "(unnamed)" : first.name();
        String error = first.outputSummary() != null && !first.outputSummary().isBlank()
                ? first.outputSummary()
                : (first.outputTitle() == null ? "" : first.outputTitle());
        if (error.length() > CI_ERROR_MESSAGE_CAP) {
            error = error.substring(0, CI_ERROR_MESSAGE_CAP);
        }
        return error.isBlank() ? name : name + ": " + error;
    }
}
