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
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final WorktreeLeaseService leaseService;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final WatchedRepoStore watchedRepoStore;
    private final PullRequestStore pullRequestStore;
    private final PrDetailStore prDetailStore;
    private final NotificationService notificationService;
    private final WorkspaceStore workspaceStore;
    private final ThreadTurnScheduler scheduler;
    private final ObjectMapper mapper;

    /** Tracks tasks that already had an auto-fix turn queued during
     *  this process's lifetime. Without it the 60-second CI sweep
     *  would re-trigger on every tick as long as CI stays red, piling
     *  up duplicate turns. Not durable — a restart resets the dedup,
     *  which is fine: the operator's hands are on the system at that
     *  point and can stop or re-disable the flag. Removed on
     *  enqueue-failure so the next sweep retries. */
    private final Set<String> autoFixTriggered = ConcurrentHashMap.newKeySet();

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
            ObjectMapper mapper)
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
        this.mapper = requireNonNull(mapper, "mapper is null");
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
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("repoFullName", repoFullName);
            payload.put("prNumber", task.linkedPrNumber());
            payload.put("failingChecks", ci.failingNames());
            payload.put("totalChecks", ci.total());
            payload.put("reason", "CI failing");
            String payloadJson = mapper.writeValueAsString(payload);
            notificationService.notifyNeedsAttention(task.threadId(), task.id(), payloadJson);
            log.info("CI failing on {} PR #{} (task {}); emitted NEEDS_ATTENTION",
                    repoFullName, task.linkedPrNumber(), task.id());
            // Best-effort follow-up: when auto-fix is opt-in for this
            // repo AND the worktree is free, enqueue a headless turn
            // through the agent scheduler. Off by default per
            // CLAUDE.md — the bell-lights-up path above happens
            // regardless of the flag.
            try {
                tryAutoFix(task, repoFullName, ci.failingNames());
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
    private void tryAutoFix(Task task, String repoFullName, List<String> failingChecks)
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
            String turnId = scheduler.enqueueTurn(thread, prompt);
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
     *   * its acquired_at is older than {@link #MAX_LEASE_AGE_MS}.
     *
     * The third rule catches LOGIC_LOOP holders that have no pid to
     * check against — for those, "too old" is the only signal.
     */
    static boolean shouldReap(WorktreeLease lease, Instant now)
    {
        if (lease.expiresAt() != null && now.isAfter(lease.expiresAt())) {
            return true;
        }
        if (lease.holderPid() != null) {
            Optional<ProcessHandle> handle = ProcessHandle.of(lease.holderPid());
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
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
            return new CiAggregate(false, List.of(), 0);
        }
        List<String> failingNames = new ArrayList<>();
        for (PrCheckRunState c : checks) {
            String conclusion = c.conclusion() == null ? "" : c.conclusion().toLowerCase(Locale.ROOT);
            if (conclusion.equals("failure")
                    || conclusion.equals("timed_out")
                    || conclusion.equals("cancelled")
                    || conclusion.equals("action_required")) {
                failingNames.add(c.name() == null ? "(unnamed)" : c.name());
            }
        }
        return new CiAggregate(!failingNames.isEmpty(), List.copyOf(failingNames), checks.size());
    }

    record CiAggregate(boolean isFailing, List<String> failingNames, int total) {}
}
