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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.stage.IterationService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * The home for the headless automation surface — CI-fail subscribing,
 * jump-in lease transfer, parked-state writers (see
 * {@code docs/mockups/workspace-thread-task-design.md} "Automation
 * and system-initiated tasks").
 *
 * <p>v1 ships two periodic sweeps:
 * <ul>
 *   <li>{@link #reapStaleLeases} — release lease rows whose holder
 *       process is gone, so a crashed subprocess doesn't permanently
 *       block the next agent on that worktree.</li>
 *   <li>{@link #scanForFailingCi} — for every task carrying a
 *       {@code linked_pr_number}, look up the PR's cached check-run
 *       state; when the aggregate is failing, emit one
 *       {@link NotificationKind#NEEDS_ATTENTION} row so the bell
 *       lights up. De-dups against open UNREAD notifications on the
 *       same task so a stubbornly-red PR doesn't spam the user.</li>
 * </ul>
 *
 * Auto-fix-on-CI-fail (actually run a headless agent in the free
 * worktree) and jump-in lease transfer land on top of this in
 * follow-up commits.
 */
@Component
public class AutomationCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(AutomationCoordinator.class);

    /** Lease rows whose lifetime exceeds this clock-wall window are
     *  considered candidates for the reaper even when the holder pid
     *  still maps to a live process — the surrounding agent should
     *  have released by now. Six hours covers a long debugging
     *  session with plenty of slack. */
    private static final long MAX_LEASE_AGE_MS = 6L * 60 * 60 * 1000;

    /** Page size cap on the CI-fail scan. A user with hundreds of
     *  linked-PR tasks would otherwise hit the GitHub-detail cache
     *  hard on each sweep. */
    private static final int CI_SCAN_LIMIT = 200;

    /** Total autonomous CI-fix attempts for a shipped task before we
     *  hand it to the user. Attempt 1 is the cheap re-run (flaky guard);
     *  attempts 2–3 are agent fix turns. After this the loop escalates
     *  to a NEEDS_ATTENTION notification per the post-ship design. */
    private static final int MAX_CI_FIX_ATTEMPTS = 3;

    /** Cap the CI-fix error summary stamped on the iteration event so a
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
    private final WatchedRepoStore watchedRepoStore;
    private final PullRequestStore pullRequestStore;
    private final PrDetailStore prDetailStore;
    private final NotificationService notificationService;
    private final WorkspaceStore workspaceStore;
    private final ThreadTurnScheduler scheduler;
    private final PullRequestService pullRequests;
    private final GitRunner git;
    private final ObjectMapper mapper;
    private final IterationService iterationService;

    /** Tracks tasks that already had an auto-fix turn queued during
     *  this process's lifetime. Without it the 60-second CI sweep
     *  would re-trigger on every tick as long as CI stays red, piling
     *  up duplicate turns. Not durable — a restart resets the dedup,
     *  which is fine: the operator's hands are on the system at that
     *  point and can stop or re-disable the flag. Removed on
     *  enqueue-failure so the next sweep retries. */
    private final Set<String> autoFixTriggered = ConcurrentHashMap.newKeySet();

    /** Per-task autonomous CI-fix attempt counter for shipped tasks on
     *  the post-ship loop, capped at {@link #MAX_CI_FIX_ATTEMPTS}. Like
     *  {@link #autoFixTriggered} this is intentionally non-durable — a
     *  restart resets the budget, which is fine: the operator is present
     *  and the user can always take over a stuck PR. */
    private final ConcurrentHashMap<String, Integer> ciFixAttempts = new ConcurrentHashMap<>();

    /** Per-task cooldown gate: the earliest instant the next CI-fix sweep
     *  may act on this task again, set after each re-run / agent turn so
     *  the in-flight CI has time to report back. Non-durable, same as
     *  {@link #ciFixAttempts}. */
    private final ConcurrentHashMap<String, Instant> ciFixCooldown = new ConcurrentHashMap<>();

    public AutomationCoordinator(
            WorktreeLeaseService leaseService,
            TaskStore taskStore,
            ThreadStore threadStore,
            WatchedRepoStore watchedRepoStore,
            PullRequestStore pullRequestStore,
            PrDetailStore prDetailStore,
            NotificationService notificationService,
            WorkspaceStore workspaceStore,
            ThreadTurnScheduler scheduler,
            PullRequestService pullRequests,
            GitRunner git,
            ObjectMapper mapper,
            IterationService iterationService)
    {
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.prDetailStore = requireNonNull(prDetailStore, "prDetailStore is null");
        this.notificationService = requireNonNull(notificationService, "notificationService is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.git = requireNonNull(git, "git is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.iterationService = requireNonNull(iterationService, "iterationService is null");
    }

    /**
     * Periodic sweep. Releases any lease row whose holder is gone or
     * whose age exceeds the soft cap. Runs every minute under the
     * same {@code bytequay.scheduling.enabled} gate as the other
     * scheduled jobs so tests don't get surprise reapings.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reapStaleLeases()
    {
        Instant now = Instant.now();
        int reaped = 0;
        List<WorktreeLease> all = leaseService.listAll();
        for (WorktreeLease lease : all) {
            if (shouldReap(lease, now)) {
                log.info("Reaping stale lease on {} (task {}, pid {}, acquired {})",
                        lease.worktreePath(), lease.taskId(),
                        lease.holderPid(), lease.acquiredAt());
                leaseService.release(lease.worktreePath());
                reaped++;
            }
        }
        if (reaped > 0) {
            log.info("Released {} stale worktree lease(s)", reaped);
        }
    }

    /**
     * Walks tasks with a {@code linked_pr_number}, looks each PR up
     * in the local detail cache, and emits one NEEDS_ATTENTION row
     * the first time its aggregate check-run state turns failing.
     * Idempotent across runs — an open UNREAD notification on the
     * same task is treated as "already told the user."
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void scanForFailingCi()
    {
        List<Task> candidates = taskStore.listWithLinkedPr(CI_SCAN_LIMIT);
        int emitted = 0;
        for (Task task : candidates) {
            if (task.linkedPrNumber() == null || task.workingDir() == null) {
                continue;
            }
            try {
                if (maybeEmitCiFail(task)) {
                    emitted++;
                }
            }
            catch (RuntimeException e) {
                log.warn("CI-fail check failed for task {}: {}", task.id(), e.getMessage());
            }
        }
        if (emitted > 0) {
            log.info("Emitted {} NEEDS_ATTENTION notification(s) for failing CI", emitted);
        }
    }

    /** Returns true when this scan actually wrote a new notification. */
    private boolean maybeEmitCiFail(Task task)
    {
        // A shipped task drives its own autonomous CI-fix sequence — re-run →
        // agent fix → escalate — off the PR's LIVE state, fetched straight from
        // GitHub by ref exactly like the lifecycle driver and the PR panel.
        // This is deliberately independent of the dashboard sync's cached
        // pull_request row, which a freshly-opened (or fork) PR may not carry
        // yet — without the live fetch the loop would never see the failure
        // even though the panel (which also fetches live) shows CI red.
        if (task.linkedPrRef() != null) {
            return driveShippedCiFixFromLiveDetail(task);
        }
        // A plain linked-PR task (not shipped through our flow) is, by
        // definition, a dashboard PR — read its cached detail and keep the
        // conservative behaviour: one NEEDS_ATTENTION row plus an opt-in turn.
        Optional<WatchedRepo> repo = findRepoForWorkingDir(task.workingDir());
        if (repo.isEmpty()) {
            return false;
        }
        String repoFullName = repo.get().owner() + "/" + repo.get().repo();
        Optional<Long> prId = pullRequestStore.findIdByRepoAndNumber(
                repoFullName, task.linkedPrNumber());
        if (prId.isEmpty()) {
            return false;
        }
        Optional<StoredPrDetail> detail = prDetailStore.find(prId.get());
        if (detail.isEmpty()) {
            return false;
        }
        CiAggregate ci = aggregateChecks(detail.get().checkRuns());
        if (!ci.isFailing()) {
            return false;
        }
        if (hasOpenNotificationForTask(task.id())) {
            return false;
        }
        try {
            String payloadJson = mapper.writeValueAsString(new CiFailingPayload(
                    repoFullName, task.linkedPrNumber(), ci.failingNames(), ci.total()));
            notificationService.notifyNeedsAttention(task.threadId(), task.id(), payloadJson);
            log.info("CI failing on {} PR #{} (task {}); emitted NEEDS_ATTENTION",
                    repoFullName, task.linkedPrNumber(), task.id());
            // Best-effort follow-up: when auto-fix is opt-in for this
            // repo AND the worktree is free, enqueue a headless turn
            // through the agent scheduler. Off by default per
            // CLAUDE.md — the bell-lights-up path above happens
            // regardless of the flag.
            try {
                tryAutoFix(task, repoFullName, ci.failingNames(), ci.failingRuns());
            }
            catch (RuntimeException e) {
                log.warn("auto-fix candidate check failed for task {}: {}",
                        task.id(), e.getMessage());
            }
            return true;
        }
        catch (JsonProcessingException e) {
            log.warn("Failed to write CI-fail payload for task {}: {}", task.id(), e.getMessage());
            return false;
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
    private void tryAutoFix(Task task, String repoFullName, List<String> failingChecks,
            List<PrCheckRunState> failingRuns)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        Optional<WorkspaceRepo> ws = workspaceStore.findRepo(
                WorkspaceService.DEFAULT_WORKSPACE_ID, repoFullName);
        if (ws.isEmpty() || !ws.get().autoFixEnabled()) {
            log.debug("auto-fix not enabled for {} (task {}); NEEDS_ATTENTION-only",
                    repoFullName, task.id());
            return;
        }
        if (leaseService.isHeld(task.worktreePath())) {
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
        Optional<Thread> threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty()) {
            autoFixTriggered.remove(task.id());
            log.warn("auto-fix skipped: owning thread {} not found for task {}",
                    task.threadId(), task.id());
            return;
        }
        Thread thread = threadOpt.get();
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
        String prompt = buildAutoFixPrompt(task, repoFullName, failingChecks);
        try {
            // Bind the task id so the turn runs on the task's (worktree-leased)
            // agent, never the read-only trunk planner — task/stage work must
            // never fall back to the trunk.
            String turnId = scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), TurnInitiator.unattended("auto-fix-ci-fail"));
            iterationService.begin(task.id(), turnId, IterationService.TRIGGER_RED_CI,
                    ciFixContext(failingRuns));
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
                .append("for the frontend) before requesting review. Do not push or comment ")
                .append("on the PR yourself — call `request_review` when you have a candidate ")
                .append("fix and the user will publish.");
        return out.toString();
    }

    /**
     * Drives one step of the autonomous post-ship CI-fix loop for a
     * shipped task whose linked PR is failing CI. The sequence, paced by
     * the 60-second sweep and the {@link #CI_FIX_COOLDOWN}:
     *
     * <ol>
     *   <li>attempt 0 → <b>re-run</b> the failed checks in place — the
     *       cheapest way to clear a flaky/transient failure before
     *       spending an agent;</li>
     *   <li>attempts 1–2 → spawn an <b>agent fix turn</b> that decides
     *       whether the failure is ours, fixes + pushes if so, else stops
     *       so the next sweep re-runs;</li>
     *   <li>at the cap → <b>escalate</b> to a NEEDS_ATTENTION row and stop
     *       acting (the user takes over).</li>
     * </ol>
     *
     * Returns true only when this step wrote a user-facing notification.
     */
    /**
     * Run the shipped CI-fix loop off the PR's live state. Fetches the linked
     * PR directly by {@code owner/repo#n} — the same call the PR panel and the
     * lifecycle driver use — so it sees the real check-run state whether or not
     * the dashboard sync has cached the PR. Without this the loop read an empty
     * cache for a freshly-opened PR and never started, leaving the CI-fixing
     * stage open with nothing driving it.
     */
    private boolean driveShippedCiFixFromLiveDetail(Task task)
    {
        String ref = task.linkedPrRef();
        int hash = ref.lastIndexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            return false;
        }
        String repoFullName = ref.substring(0, hash);
        int number;
        try {
            number = Integer.parseInt(ref.substring(hash + 1).trim());
        }
        catch (NumberFormatException e) {
            return false;
        }
        PullRequestDetail detail;
        try {
            detail = pullRequests.getPullRequestDetail(repoFullName, number);
        }
        catch (RuntimeException e) {
            log.warn("shipped CI-fix: live fetch of {} failed (task {}): {}",
                    ref, task.id(), e.getMessage());
            return false;
        }
        CiAggregate ci = aggregateChecks(toCheckRunStates(detail.checkRuns()));
        if (!ci.isFailing()) {
            return false;
        }
        return driveShippedCiFix(task, repoFullName, ci);
    }

    /** Adapt the live detail's check runs to the aggregator's input shape —
     *  the two records carry identical fields. */
    private static List<PrCheckRunState> toCheckRunStates(List<PullRequestDetail.CheckRun> runs)
    {
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }
        List<PrCheckRunState> out = new ArrayList<>(runs.size());
        for (PullRequestDetail.CheckRun r : runs) {
            out.add(new PrCheckRunState(
                    r.githubId(), r.name(), r.status(), r.conclusion(),
                    r.htmlUrl(), r.outputTitle(), r.outputSummary()));
        }
        return out;
    }

    private boolean driveShippedCiFix(Task task, String repoFullName, CiAggregate ci)
    {
        Instant now = Instant.now();
        Instant eligibleAt = ciFixCooldown.get(task.id());
        if (eligibleAt != null && now.isBefore(eligibleAt)) {
            // Last action's CI run hasn't had time to report back yet.
            return false;
        }
        int attempts = ciFixAttempts.getOrDefault(task.id(), 0);
        if (attempts >= MAX_CI_FIX_ATTEMPTS) {
            return escalateShippedCiFix(task, repoFullName, ci);
        }
        if (attempts == 0) {
            rerunShippedCi(task, repoFullName, now);
            return false;
        }
        enqueueShippedCiFixTurn(task, repoFullName, ci.failingNames(), ci.failingRuns(), now);
        return false;
    }

    /** Re-runs the failed checks on the task's pushed head commit (the
     *  cheap flaky guard, attempt 0). The head SHA comes from the kept
     *  worktree — a shipped task keeps its worktree precisely so the loop
     *  can act on it. Bumps the attempt counter and arms the cooldown. */
    private void rerunShippedCi(Task task, String repoFullName, Instant now)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        String headSha;
        try {
            headSha = git.headSha(Path.of(task.worktreePath()));
        }
        catch (IOException e) {
            log.warn("CI re-run skipped: could not resolve HEAD for task {} ({}): {}",
                    task.id(), task.worktreePath(), e.getMessage());
            return;
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            log.warn("CI re-run interrupted resolving HEAD for task {}", task.id());
            return;
        }
        try {
            int n = pullRequests.rerunFailedChecks(repoFullName, headSha);
            ciFixAttempts.put(task.id(), 1);
            ciFixCooldown.put(task.id(), now.plus(CI_FIX_COOLDOWN));
            log.info("CI re-run requested for shipped task {} on {} PR #{} (head {}): {} run(s)",
                    task.id(), repoFullName, task.linkedPrNumber(), headSha, n);
        }
        catch (RuntimeException e) {
            log.warn("CI re-run failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    /** Spawns an agent fix turn for a shipped task whose re-run didn't
     *  clear CI. Same worktree-free / thread-IDLE gating as the dashboard
     *  auto-fix, but always-on (no per-repo opt-in) since the task entered
     *  the loop explicitly on ship. Bumps the attempt counter only when a
     *  turn was actually queued; a deferral retries on the next sweep. */
    private void enqueueShippedCiFixTurn(
            Task task, String repoFullName, List<String> failingChecks,
            List<PrCheckRunState> failingRuns, Instant now)
    {
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            return;
        }
        if (leaseService.isHeld(task.worktreePath())) {
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
        String prompt = buildShippedCiFixPrompt(task, repoFullName, failingChecks);
        try {
            // Bind the task id: a shipped task is IN_REVIEW, so the active-task
            // projection is empty and the no-id enqueue would stamp task_id =
            // null and route this autonomous fix to the read-only trunk agent.
            // Task/stage work must always run on the task's own agent.
            String turnId = scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), TurnInitiator.unattended("ci-fix-shipped"));
            iterationService.begin(task.id(), turnId, IterationService.TRIGGER_RED_CI,
                    ciFixContext(failingRuns));
            int attempt = ciFixAttempts.merge(task.id(), 1, Integer::sum);
            ciFixCooldown.put(task.id(), now.plus(CI_FIX_COOLDOWN));
            log.info("shipped CI-fix queued: task {} on {} PR #{} → turn {} (attempt {})",
                    task.id(), repoFullName, task.linkedPrNumber(), turnId, attempt);
        }
        catch (RuntimeException e) {
            log.warn("shipped CI-fix enqueue failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    /** Hands a stubbornly-red shipped PR to the user after the autonomous
     *  budget is spent. Reuses the NEEDS_ATTENTION row + UNREAD dedup so a
     *  PR that stays red doesn't re-notify every sweep. */
    private boolean escalateShippedCiFix(Task task, String repoFullName, CiAggregate ci)
    {
        if (hasOpenNotificationForTask(task.id())) {
            return false;
        }
        try {
            String payloadJson = mapper.writeValueAsString(new CiFailingPayload(
                    repoFullName, task.linkedPrNumber(), ci.failingNames(), ci.total()));
            notificationService.notifyNeedsAttention(task.threadId(), task.id(), payloadJson);
            log.info("shipped CI-fix gave up after {} attempts on {} PR #{} (task {}); escalated",
                    MAX_CI_FIX_ATTEMPTS, repoFullName, task.linkedPrNumber(), task.id());
            return true;
        }
        catch (JsonProcessingException e) {
            log.warn("Failed to write CI-fail escalation payload for task {}: {}",
                    task.id(), e.getMessage());
            return false;
        }
    }

    /** The agent's first prompt for an autonomous shipped CI-fix turn.
     *  Unlike the dashboard auto-fix prompt, this turn pushes its own fix
     *  (the post-ship loop is autonomous on CI): the agent first decides
     *  whether the failure is ours, and only changes + pushes code when it
     *  is — otherwise it stops and the system re-runs. */
    private static String buildShippedCiFixPrompt(
            Task task, String repoFullName, List<String> failingChecks)
    {
        StringBuilder out = new StringBuilder();
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
                .append("changes. If they are NOT — a flaky test, an infra/network blip, or ")
                .append("an unrelated breakage already on the base branch — do not change ")
                .append("any code: say so briefly and stop, and the system will re-run the ")
                .append("checks.\n")
                .append("If they ARE caused by this branch, fix them on the current branch, ")
                .append("run the local checks to confirm green (`mvn verify` for the backend, ")
                .append("`npx tsc --noEmit` + `npm test` for the frontend), then commit and ")
                .append("`git push` the fix so CI re-runs. This is an autonomous CI-fix turn: ")
                .append("push directly, do not wait for review.");
        return out.toString();
    }

    /** Visible for tests: seed the in-memory CI-fix attempt counter so a
     *  test can drive the agent-fix and escalation branches directly. */
    void seedCiFixAttemptsForTest(String taskId, int attempts)
    {
        ciFixAttempts.put(taskId, attempts);
    }

    private Optional<WatchedRepo> findRepoForWorkingDir(String workingDir)
    {
        Path needle = Path.of(workingDir);
        for (WatchedRepo r : watchedRepoStore.findAll()) {
            if (r.localClonePath() != null
                    && !r.localClonePath().isBlank()
                    && Path.of(r.localClonePath()).equals(needle)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    private boolean hasOpenNotificationForTask(String taskId)
    {
        // Treat any UNREAD NEEDS_ATTENTION notification on this task
        // as "already told the user, don't pile on." A read-but-not-
        // dismissed dedup window would be sturdier, but UNREAD-only
        // keeps the rule simple while still preventing every tick
        // from emitting a fresh row.
        for (Notification n : notificationService.listUnread()) {
            if (n.kind() == NotificationKind.NEEDS_ATTENTION
                    && taskId.equals(n.taskId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides whether a lease row is stale. A lease counts as stale
     * when ANY of the following holds:
     *   * its soft expiry has passed,
     *   * its holder pid is non-null and no live OS process matches,
     *   * the lease has no holder pid AND its acquired_at is older
     *     than {@link #MAX_LEASE_AGE_MS}.
     *
     * The pid-less age rule catches LOGIC_LOOP holders that have no
     * pid to check against. The age rule does <em>not</em> apply to
     * leases held by a live pid — the registry holds the worktree
     * lease for the lifetime of the human's attachment to the
     * thread, which is allowed to outlive any subprocess. Reaping a
     * live attachment just because it's been open for six hours
     * would let an auto-fix run barge in on a thread the human is
     * still working in.
     */
    static boolean shouldReap(WorktreeLease lease, Instant now)
    {
        if (lease.expiresAt() != null && now.isAfter(lease.expiresAt())) {
            return true;
        }
        if (lease.holderPid() != null) {
            Optional<ProcessHandle> handle = ProcessHandle.of(lease.holderPid());
            return handle.isEmpty() || !handle.get().isAlive();
        }
        long ageMs = now.toEpochMilli() - lease.acquiredAt().toEpochMilli();
        return ageMs > MAX_LEASE_AGE_MS;
    }

    /** Aggregated check-run state for a PR. "Failing" mirrors the
     *  GitHub PR-merge button logic: ANY check whose conclusion is
     *  failure / timed_out / cancelled / action_required counts as
     *  failing; in-progress checks don't tip the scale either way. */
    static CiAggregate aggregateChecks(List<PrCheckRunState> checks)
    {
        if (checks == null || checks.isEmpty()) {
            return new CiAggregate(false, List.of(), List.of(), 0);
        }
        List<String> failingNames = new ArrayList<>();
        List<PrCheckRunState> failingRuns = new ArrayList<>();
        for (PrCheckRunState c : checks) {
            String conclusion = c.conclusion() == null ? "" : c.conclusion().toLowerCase(Locale.ROOT);
            if (conclusion.equals("failure")
                    || conclusion.equals("timed_out")
                    || conclusion.equals("cancelled")
                    || conclusion.equals("action_required")) {
                failingNames.add(c.name() == null ? "(unnamed)" : c.name());
                failingRuns.add(c);
            }
        }
        return new CiAggregate(
                !failingNames.isEmpty(), List.copyOf(failingNames), List.copyOf(failingRuns), checks.size());
    }

    record CiAggregate(boolean isFailing, List<String> failingNames,
            List<PrCheckRunState> failingRuns, int total) {}

    /** Build the CI-fix detail for the iteration event from the first failing
     *  check — its name, error summary (capped), and Actions run URL. Null
     *  when there's nothing failing to attribute. */
    private static IterationService.CiFixContext ciFixContext(List<PrCheckRunState> failingRuns)
    {
        if (failingRuns == null || failingRuns.isEmpty()) {
            return null;
        }
        PrCheckRunState first = failingRuns.get(0);
        String error = first.outputSummary() != null && !first.outputSummary().isBlank()
                ? first.outputSummary()
                : (first.outputTitle() == null ? "" : first.outputTitle());
        if (error.length() > CI_ERROR_MESSAGE_CAP) {
            error = error.substring(0, CI_ERROR_MESSAGE_CAP);
        }
        return new IterationService.CiFixContext(
                first.name() == null ? "(unnamed)" : first.name(), error, first.htmlUrl());
    }

    /** Wire shape for the NEEDS_ATTENTION payload emitted when a task's
     *  linked PR is failing CI. {@code reason} is a fixed discriminator
     *  for this notification kind so the dashboard can route on it. */
    private record CiFailingPayload(
            String repoFullName,
            Integer prNumber,
            List<String> failingChecks,
            int totalChecks)
    {
        @JsonProperty("reason") public String reason() { return "CI failing"; }
    }
}
