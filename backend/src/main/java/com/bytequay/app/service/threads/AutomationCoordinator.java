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
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The home for the headless automation surface's DETECTION half — CI-fail
 * subscribing (this class) plus lease reaping. The autonomous CI-fixing
 * LOOP (re-run → agent fix → escalate, budgeted) was split out into {@link
 * CiFixRunExecutor} (plan-rail-runs.md R7): this class only decides
 * <em>whether</em> a task's linked PR is failing CI and, if so, hands off —
 * it never enqueues a fix turn or pushes a commit itself.
 *
 * <p>v1 ships two periodic sweeps:
 * <ul>
 *   <li>{@link #reapStaleLeases} — release lease rows whose holder
 *       process is gone, so a crashed subprocess doesn't permanently
 *       block the next agent on that worktree.</li>
 *   <li>{@link #scanForFailingCi} — for every task carrying a
 *       {@code linked_pr_number}, look up the PR's check-run state; when
 *       the aggregate is failing, emit one {@link
 *       NotificationKind#NEEDS_ATTENTION} row so the bell lights up, and
 *       hand off to {@link CiFixRunExecutor} for the autonomous loop.
 *       De-dups against open UNREAD notifications on the same task so a
 *       stubbornly-red PR doesn't spam the user.</li>
 * </ul>
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
    private final WatchedRepoStore watchedRepoStore;
    private final PullRequestStore pullRequestStore;
    private final PrDetailStore prDetailStore;
    private final NotificationService notificationService;
    private final PullRequestService pullRequests;
    private final ObjectMapper mapper;
    private final CiFixRunExecutor ciFixRunExecutor;
    private final ThreadStore threadStore;
    private final WorktreeService worktreeService;

    public AutomationCoordinator(
            WorktreeLeaseService leaseService,
            TaskStore taskStore,
            WatchedRepoStore watchedRepoStore,
            PullRequestStore pullRequestStore,
            PrDetailStore prDetailStore,
            NotificationService notificationService,
            PullRequestService pullRequests,
            ObjectMapper mapper,
            CiFixRunExecutor ciFixRunExecutor,
            ThreadStore threadStore,
            WorktreeService worktreeService)
    {
        this.leaseService = requireNonNull(leaseService, "leaseService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.pullRequestStore = requireNonNull(pullRequestStore, "pullRequestStore is null");
        this.prDetailStore = requireNonNull(prDetailStore, "prDetailStore is null");
        this.notificationService = requireNonNull(notificationService, "notificationService is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.ciFixRunExecutor = requireNonNull(ciFixRunExecutor, "ciFixRunExecutor is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
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

    /** Planning worktrees whose trunk went quiet for this long are
     *  removed; the turn-start sync rebuilds one on demand from the
     *  persisted SHA, so this only trades disk for a one-off checkout
     *  + re-index when an old trunk wakes up. */
    private static final long IDLE_PLANNING_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Periodic disk-hygiene sweep over the per-thread planning worktrees
     * ({@code <clone>/.worktrees/_planning/<threadId>}): removes the
     * checkout when its thread no longer exists (orphan) or hasn't been
     * touched for {@link #IDLE_PLANNING_AGE_MS}. Safe against a
     * concurrent turn start — {@code removePlanningWorktree} holds the
     * same per-clone lock as the turn's sync.
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 300_000)
    public void reapIdlePlanningWorktrees()
    {
        Instant cutoff = Instant.now().minusMillis(IDLE_PLANNING_AGE_MS);
        for (WatchedRepo repo : watchedRepoStore.findAll()) {
            String clonePath = repo.localClonePath();
            if (clonePath == null || clonePath.isBlank()) {
                continue;
            }
            Path planningRoot = Path.of(clonePath)
                    .resolve(WorktreeService.WORKTREE_ROOT_REL)
                    .resolve(WorktreeService.PLANNING_WORKTREE_REL);
            if (!Files.isDirectory(planningRoot)) {
                continue;
            }
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(planningRoot)) {
                for (Path dir : dirs) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    String threadId = dir.getFileName().toString();
                    boolean reapable = threadStore.findThreadById(threadId)
                            .map(thread -> thread.updatedAt() != null
                                    && thread.updatedAt().isBefore(cutoff))
                            .orElse(true);
                    if (reapable) {
                        log.info("Reaping planning worktree {} (idle or orphaned)", dir);
                        worktreeService.removePlanningWorktree(Path.of(clonePath), threadId);
                    }
                }
            }
            catch (IOException e) {
                log.warn("Planning-worktree sweep failed for {}: {}", clonePath, e.getMessage());
            }
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
            if (taskStore.isV2Task(task.id())
                    || isParkedOrTerminal(task)
                    || task.linkedPrNumber() == null
                    || task.workingDir() == null) {
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

    /** A parked or finished task is durable stop-state, not a candidate for
     *  another CI run. Check both axes so a partially persisted legacy row
     *  still fails closed. */
    private static boolean isParkedOrTerminal(Task task)
    {
        if (task.phase() == TaskPhase.NEEDS_ATTENTION || task.phase() == TaskPhase.COMPLETED) {
            return true;
        }
        return switch (task.status()) {
            case AWAITING_REVIEW, NEEDS_ATTENTION, PAUSED,
                    COMPLETED, REMOTE_CLOSED, ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
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
        // Only ring the bell on the user's OWN PRs. A red-CI notification on a
        // PR the user merely reviews is noise — the reviewer can't fix someone
        // else's CI (mirrors PrAttention's mine-gating and its "would be noise"
        // comment). The shipped-PR autonomous fix path above is separate and
        // doesn't emit a bell, so it's unaffected.
        if (pullRequestStore.findById(prId.get())
                .map(pr -> pr.origin() != PullRequest.Origin.AUTHORED)
                .orElse(true)) {
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
                ciFixRunExecutor.tryAutoFix(task, repoFullName, ci.failingNames(), ci.failingRuns());
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
     * Run the shipped CI-fix detection off the PR's live state. Fetches the
     * linked PR directly by {@code owner/repo#n} — the same call the PR
     * panel and the lifecycle driver use — so it sees the real check-run
     * state whether or not the dashboard sync has cached the PR. Without
     * this the loop read an empty cache for a freshly-opened PR and never
     * started. Green checks close out any live run instead of driving it —
     * detection's job either way, hand-off is the executor's.
     */
    private boolean driveShippedCiFixFromLiveDetail(Task task)
    {
        Optional<PullRequestRef> parsed = PullRequestRef.parse(task.linkedPrRef());
        if (parsed.isEmpty()) {
            return false;
        }
        String repoFullName = parsed.get().repoRef().fullName();
        int number = parsed.get().number();
        String ref = parsed.get().fullName();
        PullRequestDetail detail;
        try {
            // Force a live read (conditional GET, maxAge=0) — NOT
            // getPullRequestDetail, which serves the SQLite snapshot and would
            // let the loop act on stale CI. The loop must see the freshest
            // pass/fail to decide whether to re-run, fix, or stand down.
            detail = pullRequests.refreshPullRequestDetail(repoFullName, number);
        }
        catch (RuntimeException e) {
            log.warn("shipped CI-fix: live fetch of {} failed (task {}): {}",
                    ref, task.id(), e.getMessage());
            return false;
        }
        if (detail.ciStatus() == PullRequestDetail.CiStatus.PASSING) {
            ciFixRunExecutor.closeIfGreen(task);
            return false;
        }
        // Queued/in-progress/absent checks are not green. Leave the live run
        // open and wait for the next poll instead of recording a false
        // success and preventing the eventual failure from being addressed.
        if (detail.ciStatus() != PullRequestDetail.CiStatus.FAILING) {
            return false;
        }
        // A single failed job makes the PR aggregate FAILING even while sibling
        // jobs are still running. GitHub cannot re-run failed jobs until their
        // workflow has completed, so wait for the whole check set to settle.
        if (detail.checkRuns() != null && detail.checkRuns().stream()
                .anyMatch(run -> !"completed".equals(run.status()))) {
            return false;
        }
        CiAggregate ci = aggregateChecks(toCheckRunStates(detail.checkRuns()));
        return ciFixRunExecutor.driveShippedCiFix(task, repoFullName, ci);
    }

    /** Adapt live detail check runs to the aggregator's input shape. */
    public static List<PrCheckRunState> toCheckRunStates(List<PullRequestDetail.CheckRun> runs)
    {
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }
        return runs.stream()
                .map(r -> new PrCheckRunState(
                        r.githubId(), r.name(), r.status(), r.conclusion(),
                        r.htmlUrl(), r.outputTitle(), r.outputSummary()))
                .toList();
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
    public static CiAggregate aggregateChecks(List<PrCheckRunState> checks)
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

    public record CiAggregate(boolean isFailing, List<String> failingNames,
            List<PrCheckRunState> failingRuns, int total) {}

    /** Wire shape for the NEEDS_ATTENTION payload emitted when a task's
     *  linked PR is failing CI. {@code reason} is a fixed discriminator
     *  for this notification kind so the dashboard can route on it.
     *  Package-visible (not private): {@link CiFixRunExecutor}'s escalation
     *  path emits the identical payload shape. */
    record CiFailingPayload(
            String repoFullName,
            Integer prNumber,
            List<String> failingChecks,
            int totalChecks)
    {
        @JsonProperty("reason") public String reason() { return "CI failing"; }
    }
}
