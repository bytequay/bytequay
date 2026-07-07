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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRDashboardEntry;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ActivityItem;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Materialises a task's local PR from its real git state so the PR view has
 * something to show without waiting for an agent to call the {@code record_pr_*}
 * tools. Idempotent: creates the row on first sight, then appends any branch
 * commits it hasn't recorded yet, and flips {@code local-drafted → local-open}
 * once the task's phase says development is done and it's awaiting review/push.
 *
 * <p>ponytail: read-side sync from git (git log on each PR-bundle fetch) rather
 * than event-sourced from the agent. Cheap for one task; the agent-driven path
 * ({@code record_pr_*}) can supersede it once stage prompts drive those tools.
 */
@Service
public class PRSyncService
{
    private static final Logger log = LoggerFactory.getLogger(PRSyncService.class);
    private static final int COMMIT_LIMIT = 200;
    private static final String DEFAULT_BASE = "main";

    /** Passive-sync calls (e.g. a PR-bundle fetch on pane load) probe GitHub
     *  at most this often — matches {@link PullRequestService}'s own
     *  detail-page polling maxAge. An explicit user-triggered refresh
     *  ({@code POST /api/prs/{id}/sync}) passes {@code 0} to always probe. */
    private static final int DEFAULT_MAX_AGE_SECONDS = 20;

    /** Phases at which dev is finished and the PR is awaiting the user's review. */
    private static final Set<TaskPhase> READY_FOR_REVIEW = ImmutableSet.of(
            TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH, TaskPhase.ADDRESSING_LOCAL_COMMENTS);

    private final PRService prService;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final BrainReviewService brainReview;
    private final PullRequestService pullRequests;
    private final PRPublishService prPublish;

    public PRSyncService(
            PRService prService, TaskStore taskStore, GitRunner git, BrainReviewService brainReview,
            PullRequestService pullRequests, PRPublishService prPublish)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.prPublish = requireNonNull(prPublish, "prPublish is null");
    }

    /** Ensure the task's local PR exists and reflects the branch's commits
     *  and (once pushed) the remote PR's comments/reviews. Returns empty
     *  when the task has no branch yet (nothing to show). */
    public Optional<PR> syncFromTask(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null || task.branchName() == null || task.branchName().isBlank()) {
            return Optional.empty();
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? DEFAULT_BASE : task.baseBranch();
        String title = task.name() != null && !task.name().isBlank() ? task.name() : task.branchName();
        PR pr = prService.createForTask(taskId, task.branchName(), base, title, "");
        return syncPR(pr.id(), DEFAULT_MAX_AGE_SECONDS);
    }

    /** Canonical id-based refresh for either origin — the {@code POST
     *  /api/prs/{id}/sync} entry point and the target of a future dashboard
     *  {@code syncList}. Task-origin PRs also pick up their branch's local
     *  commits; both origins pick up the remote PR's comments/reviews once
     *  a {@code remotePrNumber} exists. Returns empty only when the PR
     *  itself doesn't exist. */
    public Optional<PR> syncPR(String prId)
    {
        return syncPR(prId, DEFAULT_MAX_AGE_SECONDS);
    }

    /**
     * @param maxAgeSeconds forwarded to {@link PullRequestService#refreshPullRequestDetail}
     *  — {@code 0} always probes GitHub, otherwise a probe within the last
     *  {@code maxAgeSeconds} is skipped (see {@link #DEFAULT_MAX_AGE_SECONDS}).
     */
    public Optional<PR> syncPR(String prId, int maxAgeSeconds)
    {
        PR pr = prService.findById(prId).orElse(null);
        if (pr == null) {
            return Optional.empty();
        }
        if (PR.ORIGIN_EXTERNAL.equals(pr.origin())) {
            if (pr.repo() != null && pr.remotePrNumber() != null) {
                syncRemoteTimeline(pr, pr.repo(), maxAgeSeconds);
            }
            prService.markSynced(prId, Instant.now());
            return prService.findById(prId);
        }

        Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
        if (task == null) {
            return Optional.of(pr);
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? DEFAULT_BASE : task.baseBranch();
        syncCommits(pr, task, base);
        maybeFlipToOpen(pr.id(), task);
        pr = healIfAlreadyPushedRemotely(pr, task);
        PR healed = pr;
        if (healed.remotePrNumber() != null) {
            resolveGitRemoteSlug(task).ifPresent(
                    repo -> syncRemoteTimeline(healed, repo.owner() + "/" + repo.repo(), maxAgeSeconds));
        }
        prService.markSynced(healed.id(), Instant.now());
        return prService.findById(healed.id());
    }

    /**
     * Resolver for an external PR (the dashboard/details-page entry point):
     * finds the already-synced-in row for this (repo, number), or fetches
     * just enough from GitHub to create one, then hands off to {@link
     * #syncPR} for the rest (timeline, status). Empty only when GitHub has
     * no such PR (a bad link, or the caller lacks access).
     */
    public Optional<PR> syncExternalPR(String repo, int number)
    {
        Optional<PR> existing = prService.findByRepoAndNumber(repo, number);
        if (existing.isPresent()) {
            return syncPR(existing.get().id(), DEFAULT_MAX_AGE_SECONDS);
        }
        PullRequest light;
        PullRequestDetail detail;
        try {
            light = pullRequests.lookupPullRequest(repo, number);
            detail = pullRequests.getPullRequestDetail(repo, number);
        }
        catch (RuntimeException e) {
            log.info("looking up external PR {}#{} failed: {}", repo, number, e.getMessage());
            return Optional.empty();
        }
        String status = deriveExternalStatus(light.mergedAt() != null, light.state(), light.draft());
        PR created = prService.createExternal(
                repo, number, light.htmlUrl(), actorLabel(light.author()),
                detail.headRef() != null ? detail.headRef() : "unknown",
                detail.baseRef() != null ? detail.baseRef() : DEFAULT_BASE,
                light.title(), detail.body(), status, light.createdAt(), light.mergedAt(), light.closedAt());
        return syncPR(created.id(), DEFAULT_MAX_AGE_SECONDS);
    }

    /** PRs handled within this window stay on the dashboard's Handled tab
     *  even after they fall out of the relevant-PR search — mirrors the
     *  legacy dashboard sync's retention rule exactly. */
    private static final int HANDLED_RETENTION_DAYS = 30;

    /**
     * The dashboard sweep (design doc U3): finds every PR the relevant-PR
     * search surfaces (open authored / review-requested / reviewed-by, plus
     * recently-closed-authored), upserts each into the unified {@code pr}
     * table via the same idempotent find-or-create {@link #syncExternalPR}
     * uses, refreshes list-level fields every call, and — for a PR whose
     * GitHub {@code updatedAt} moved or that still looks under-enriched —
     * runs a detail pass for the richer dashboard fields (CI, mergeable,
     * reviewer verdicts, attention reason). Finally clears {@code
     * watch_reason} for any previously-watched PR that fell out of the
     * search (unless it's within the handled-retention window) and runs
     * the snooze auto-wake check.
     *
     * <p>ponytail: no in-memory recheck-interval throttle for under-enriched
     * PRs yet (legacy has one, {@code BACKFILL_RECHECK_INTERVAL}) — add one
     * if a real deployment shows this hammering the GitHub API.
     */
    public void syncList()
    {
        List<PullRequest> fresh = pullRequests.searchRelevantForDashboard();
        String currentLogin = pullRequests.resolveCurrentDashboardLogin();
        Instant now = Instant.now();

        Set<String> freshKeys = new HashSet<>();
        for (PullRequest ghPr : fresh) {
            freshKeys.add(ghPr.repo() + "#" + ghPr.number());
            PR pr = prService.findByRepoAndNumber(ghPr.repo(), ghPr.number())
                    .orElseGet(() -> prService.createExternal(
                            ghPr.repo(), ghPr.number(), ghPr.htmlUrl(), actorLabel(ghPr.author()),
                            ghPr.headRef() != null ? ghPr.headRef() : "unknown", DEFAULT_BASE,
                            ghPr.title(), "",
                            deriveExternalStatus(ghPr.mergedAt() != null, ghPr.state(), ghPr.draft()),
                            ghPr.createdAt(), ghPr.mergedAt(), ghPr.closedAt()));
            PR.PRSyncSnapshot baseline = pr.githubSync();
            boolean needsDetail = baseline == null || baseline.ciStatus() == null
                    || !ghPr.updatedAt().equals(baseline.ghUpdatedAt());
            PR.PRSyncSnapshot listLevel = new PR.PRSyncSnapshot(
                    ghPr.origin(), ghPr.updatedAt(),
                    ghPr.labels() == null ? List.of() : ghPr.labels(),
                    ghPr.labelColors() == null ? Map.of() : ghPr.labelColors(),
                    ghPr.draft(),
                    baseline == null ? null : baseline.ciStatus(),
                    baseline == null ? 0 : baseline.additions(),
                    baseline == null ? 0 : baseline.deletions(),
                    baseline == null ? 0 : baseline.commentCount(),
                    baseline == null ? null : baseline.attentionReason(),
                    baseline == null ? null : baseline.mergeable(),
                    baseline == null ? null : baseline.mergeableState(),
                    baseline == null ? null : baseline.headPushedAt(),
                    baseline == null ? Map.of() : baseline.reviewerVerdicts(),
                    baseline == null ? List.of() : baseline.requestedReviewers());
            PR updated = prService.updateSyncSnapshot(pr.id(), listLevel);
            if (needsDetail) {
                syncDashboardDetail(updated, currentLogin, now);
            }
        }

        for (PRDashboardEntry entry : prService.dashboardEntries()) {
            String key = entry.pr().repo() + "#" + entry.pr().remotePrNumber();
            if (freshKeys.contains(key)) {
                continue;
            }
            Instant reviewedAt = entry.triage().reviewedAt();
            boolean withinRetention = reviewedAt != null
                    && Duration.between(reviewedAt, now).toDays() < HANDLED_RETENTION_DAYS;
            if (!withinRetention) {
                prService.setWatchReason(entry.pr().id(), null);
            }
        }

        runDashboardAutoWake(now);
    }

    /** Detail pass for the dashboard's richer fields — CI status, mergeable,
     *  reviewer verdicts, attention reason, comment count. Runs {@link
     *  #syncPR} first for the timeline/status side effects every other
     *  surface relies on, then re-derives the snapshot fields from the same
     *  detail shape {@code syncPR} itself fetches (re-fetched here since
     *  that internal detail object isn't otherwise exposed — cheap, since
     *  {@link PullRequestService#refreshPullRequestDetail} is ETag-cached). */
    private void syncDashboardDetail(PR pr, String currentLogin, Instant now)
    {
        syncPR(pr.id(), 0);
        PullRequestDetail detail;
        try {
            detail = pullRequests.refreshPullRequestDetail(pr.repo(), pr.remotePrNumber(), 0);
        }
        catch (RuntimeException e) {
            log.info("dashboard detail sync for PR {} failed: {}", pr.id(), e.getMessage());
            return;
        }
        // Branch backfill, commit/check sync, and the diff/CI snapshot
        // refresh all already happened above inside syncPR's own
        // syncRemoteTimeline call — this method only adds the dashboard-only
        // fields (attention reason, reviewer verdicts, comment count) that
        // need this current user's login and viewed-at marker.
        PR current = prService.findById(pr.id()).orElse(pr);
        PR.PRSyncSnapshot baseline = current.githubSync();
        if (baseline == null) {
            return;
        }
        Map<String, String> reviewerVerdicts = rolledUpReviewerVerdicts(detail.recentActivity());
        int commentCount = countComments(detail.recentActivity());
        AttentionReason attentionReason = promoteReason(
                baseline, detail, currentLogin, triageViewedAt(current.id()), now);
        prService.updateSyncSnapshot(current.id(), new PR.PRSyncSnapshot(
                baseline.watchReason(), baseline.ghUpdatedAt(), baseline.labels(), baseline.labelColors(),
                baseline.draft(), detail.ciStatus(), detail.additions(), detail.deletions(), commentCount,
                attentionReason, detail.mergeable(), detail.mergeableState(), baseline.headPushedAt(),
                reviewerVerdicts, detail.requestedReviewers() == null ? List.of() : detail.requestedReviewers()));
    }

    private Instant triageViewedAt(String prId)
    {
        return prService.triage(prId).viewedAt();
    }

    /** Latest review state per actor, from the "reviewed" activity items —
     *  mirrors the legacy dashboard's per-reviewer verdict rollup. */
    private static Map<String, String> rolledUpReviewerVerdicts(List<ActivityItem> activity)
    {
        if (activity == null) {
            return Map.of();
        }
        Map<String, String> verdicts = new HashMap<>();
        for (ActivityItem item : activity) {
            if (!"reviewed".equals(item.eventType()) || item.actor() == null || item.state() == null) {
                continue;
            }
            verdicts.put(item.actor(), item.state());
        }
        return Map.copyOf(verdicts);
    }

    private static int countComments(List<ActivityItem> activity)
    {
        if (activity == null) {
            return 0;
        }
        int count = 0;
        for (ActivityItem item : activity) {
            if ("commented".equals(item.eventType())) {
                count++;
            }
        }
        return count;
    }

    /** Adapted from {@code PrAttention.promoteReason} for the unified
     *  {@link PullRequestDetail}/{@link ActivityItem} shapes rather than the
     *  legacy {@code StoredPrDetail}/{@code PrTimelineEvent} — same v1 rules
     *  and precedence order (design doc §6.5). */
    private static AttentionReason promoteReason(
            PR.PRSyncSnapshot snap, PullRequestDetail detail, String currentLogin, Instant viewedAt, Instant now)
    {
        boolean mine = snap.watchReason() == PullRequest.Origin.AUTHORED;
        if (detail.ciStatus() == PullRequestDetail.CiStatus.FAILING) {
            return AttentionReason.CI_FAILING;
        }
        if (mine && Boolean.FALSE.equals(detail.mergeable()) && "dirty".equalsIgnoreCase(detail.mergeableState())) {
            return AttentionReason.MERGE_CONFLICT;
        }
        List<ActivityItem> activity = detail.recentActivity();
        if (hasUnseenMention(activity, currentLogin, viewedAt)) {
            return AttentionReason.MENTIONED;
        }
        if (mine && hasUnseenActivity(activity, currentLogin, viewedAt)) {
            return AttentionReason.NEW_COMMENT;
        }
        if (snap.labels() != null && snap.labels().stream()
                .anyMatch(l -> l != null && l.toLowerCase(Locale.ROOT).contains("block"))) {
            return AttentionReason.BLOCKING;
        }
        if (snap.ghUpdatedAt() != null && Duration.between(snap.ghUpdatedAt(), now).toDays() >= 7) {
            return AttentionReason.STALE;
        }
        return mine ? AttentionReason.MINE : null;
    }

    private static boolean hasUnseenActivity(List<ActivityItem> activity, String currentLogin, Instant viewedAt)
    {
        if (activity == null) {
            return false;
        }
        for (ActivityItem item : activity) {
            String type = item.eventType();
            if (!"commented".equals(type) && !"reviewed".equals(type)) {
                continue;
            }
            if (currentLogin != null && currentLogin.equalsIgnoreCase(item.actor())) {
                continue;
            }
            if (viewedAt != null && item.timestamp() != null && !item.timestamp().isAfter(viewedAt)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean hasUnseenMention(List<ActivityItem> activity, String currentLogin, Instant viewedAt)
    {
        if (currentLogin == null || currentLogin.isBlank() || activity == null) {
            return false;
        }
        Pattern mention = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_-])@" + Pattern.quote(currentLogin) + "(?![A-Za-z0-9_-])");
        for (ActivityItem item : activity) {
            if (item.body() == null || item.body().isBlank()) {
                continue;
            }
            if (item.actor() != null && currentLogin.equalsIgnoreCase(item.actor())) {
                continue;
            }
            if (viewedAt != null && item.timestamp() != null && !item.timestamp().isAfter(viewedAt)) {
                continue;
            }
            if (mention.matcher(item.body()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Wakes a snoozed dashboard PR when its timer elapsed, or when an
     *  urgent signal flips on: CI now failing, a reviewer requested changes,
     *  or a merge conflict appeared (authored PRs only). Cheap — an
     *  in-memory pass over rows already loaded for this sync tick. */
    private void runDashboardAutoWake(Instant now)
    {
        for (PRDashboardEntry entry : prService.dashboardEntries()) {
            Instant snoozedUntil = entry.triage().snoozedUntil();
            if (snoozedUntil == null) {
                continue;
            }
            PR.PRSyncSnapshot snap = entry.pr().githubSync();
            String reason = null;
            if (!snoozedUntil.isAfter(now)) {
                reason = "TIME_ELAPSED";
            }
            else if (snap != null && snap.ciStatus() == PullRequestDetail.CiStatus.FAILING) {
                reason = "CI_FAILING";
            }
            else if (snap != null && snap.watchReason() == PullRequest.Origin.AUTHORED
                    && Boolean.FALSE.equals(snap.mergeable()) && "dirty".equalsIgnoreCase(snap.mergeableState())) {
                reason = "MERGE_CONFLICT";
            }
            else if (snap != null && snap.reviewerVerdicts() != null
                    && snap.reviewerVerdicts().containsValue("CHANGES_REQUESTED")) {
                reason = "CHANGES_REQUESTED";
            }
            if (reason != null) {
                prService.autoWake(entry.pr().id(), reason);
            }
        }
    }

    private Optional<RepoRef> resolveGitRemoteSlug(Task task)
    {
        try {
            return git.remoteSlug(Path.of(task.workingDir()), "origin");
        }
        catch (IOException e) {
            log.info("resolving origin remote for task {} failed: {}", task.id(), e.getMessage());
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Self-heals a row stuck at {@code local-drafted}/{@code local-open}
     *  when the task's PR is already open remotely — the same recovery
     *  {@link PRPublishService#onPushedElsewhere} performs for a push
     *  resolved via a gate, applied here too since a task pushed before that
     *  sync existed (or through a path that missed it) would otherwise never
     *  catch up. Runs on every PR-bundle fetch, so it's a one-time fix per
     *  task — once flipped, the status guard in {@code onPushedElsewhere}
     *  makes every later call a no-op. */
    private PR healIfAlreadyPushedRemotely(PR pr, Task task)
    {
        if (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status()) && !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return pr;
        }
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return pr;
        }
        prPublish.onPushedElsewhere(new PrPushedEvent(
                task.id(), ref.get().number(),
                "https://github.com/" + ref.get().owner() + "/" + ref.get().repo() + "/pull/" + ref.get().number()));
        return prService.findById(pr.id()).orElse(pr);
    }

    /** Mirror the remote PR's comments and reviews onto the unified timeline
     *  — the caller has already confirmed a {@code remotePrNumber} exists.
     *  Best-effort: a GitHub hiccup here must never break the PR view, so
     *  failures just log. Goes through {@link PullRequestService#refreshPullRequestDetail}
     *  rather than a raw fetch, so a repeat sync within {@code maxAgeSeconds}
     *  (or an unchanged ETag) skips the network round-trip. */
    private void syncRemoteTimeline(PR pr, String repoSlug, int maxAgeSeconds)
    {
        PullRequestDetail detail;
        try {
            detail = pullRequests.refreshPullRequestDetail(repoSlug, pr.remotePrNumber(), maxAgeSeconds);
        }
        catch (RuntimeException e) {
            log.info("fetching remote PR detail for PR {} failed: {}", pr.id(), e.getMessage());
            return;
        }
        if (PR.ORIGIN_EXTERNAL.equals(pr.origin())) {
            reconcileExternalStatus(pr, detail);
            // The dashboard sweep's initial createExternal has no better guess
            // than "unknown"/the default base — GitHub's search API never
            // returns head.ref (GitHubClient.toPullRequest). Backfill the real
            // names here too, since a PR opened directly (never touched by a
            // dashboard tick first) reaches this path, not syncDashboardDetail.
            if (detail.headRef() != null || detail.baseRef() != null) {
                prService.updateBranches(pr.id(), detail.headRef(), detail.baseRef());
            }
            // Same story as the branch backfill above: the dashboard sweep's
            // initial createExternal has no PR body to hand over (ghPr is the
            // lightweight search result), so it creates the row with an empty
            // description. GitHub is authoritative for an external PR's
            // description (nothing in ByteQuay ever edits one), so backfill
            // it from the already-fetched detail on every sync.
            if (detail.body() != null) {
                prService.updateDetails(pr.id(), null, detail.body());
            }
            syncExternalCommits(pr);
            syncExternalChecks(pr, detail);
            refreshDiffAndCiSnapshot(pr, detail);
        }
        if (detail.recentActivity() == null) {
            return;
        }
        for (ActivityItem item : detail.recentActivity()) {
            if (item.githubId() == null) {
                continue;
            }
            if ("commented".equals(item.eventType())) {
                syncIssueComment(pr, item);
            }
            else if ("reviewed".equals(item.eventType())) {
                syncReview(pr, item);
            }
        }
    }

    private void syncIssueComment(PR pr, ActivityItem item)
    {
        if (prService.hasRemoteEvent(pr.id(), item.githubId())) {
            return;
        }
        try {
            prService.addRemoteComment(
                    pr.id(), actorLabel(item.actor()), item.body() == null ? "" : item.body(),
                    item.timestamp() == null ? Instant.now() : item.timestamp(), item.githubId());
        }
        catch (RuntimeException e) {
            log.warn("syncing remote comment {} onto local PR {} failed: {}", item.githubId(), pr.id(),
                    e.getMessage());
        }
    }

    private void syncReview(PR pr, ActivityItem item)
    {
        if (prService.hasRemoteEvent(pr.id(), item.githubId())) {
            return;
        }
        try {
            prService.recordRemoteReview(
                    pr.id(), actorLabel(item.actor()), item.state(), item.body(),
                    item.timestamp() == null ? Instant.now() : item.timestamp(), item.githubId());
        }
        catch (RuntimeException e) {
            log.warn("syncing remote review {} onto local PR {} failed: {}", item.githubId(), pr.id(),
                    e.getMessage());
        }
    }

    private static String actorLabel(String githubLogin)
    {
        return githubLogin == null || githubLogin.isBlank() ? "unknown" : "@" + githubLogin;
    }

    /** External PRs have no push/merge gate inside ByteQuay — GitHub itself
     *  drives their status, so a repeat sync must catch up whenever it
     *  drifts (opened as draft then marked ready, merged, or closed). */
    private void reconcileExternalStatus(PR pr, PullRequestDetail detail)
    {
        String derived = deriveExternalStatus(detail.merged(), detail.state(), detail.draft());
        if (!derived.equals(pr.status()) && pr.canTransitionTo(derived)) {
            prService.transition(pr.id(), derived, PRTimelineEntry.ACTOR_AGENT);
        }
    }

    private static String deriveExternalStatus(boolean merged, String state, boolean draft)
    {
        if (merged) {
            return PR.STATUS_MERGED;
        }
        if ("closed".equalsIgnoreCase(state)) {
            return PR.STATUS_CLOSED;
        }
        return draft ? PR.STATUS_REMOTE_DRAFTED : PR.STATUS_REMOTE_OPEN;
    }

    /** Mirrors GitHub's own commit list onto {@code pr_commit} for an
     *  external-origin PR — the header's commit count otherwise reads as 0
     *  forever, since nothing else ever populates that table for a PR whose
     *  commits were never made locally. Deduped by sha, same pattern as
     *  {@link #syncCommits}'s local-git version. */
    private void syncExternalCommits(PR pr)
    {
        List<PullRequestCommit> commits;
        try {
            commits = pullRequests.getPullRequestCommits(pr.repo(), pr.remotePrNumber());
        }
        catch (RuntimeException e) {
            log.info("fetching commits for external PR {} failed: {}", pr.id(), e.getMessage());
            return;
        }
        Set<String> known = new HashSet<>();
        for (PRCommit c : prService.commits(pr.id())) {
            known.add(c.sha());
        }
        for (PullRequestCommit c : commits) {
            if (known.contains(c.sha())) {
                continue;
            }
            prService.recordSyncedCommit(pr.id(), c.sha(), c.message(), c.authoredAt(), actorLabel(c.authorLogin()));
        }
    }

    /** Mirrors GitHub's check runs onto {@code pr_check} for an
     *  external-origin PR — same "otherwise always reads 0" gap as {@link
     *  #syncExternalCommits}. A run with no {@code githubId} (a legacy
     *  cached row) is skipped: there's nothing stable to dedupe it by. */
    private void syncExternalChecks(PR pr, PullRequestDetail detail)
    {
        if (detail.checkRuns() == null) {
            return;
        }
        for (PullRequestDetail.CheckRun run : detail.checkRuns()) {
            if (run.githubId() == null) {
                continue;
            }
            prService.recordSyncedCheck(
                    pr.id(), String.valueOf(run.githubId()), run.name(),
                    mapCheckStatus(run.status(), run.conclusion()), null, null);
        }
    }

    /** GitHub's check-run {@code status}/{@code conclusion} pair onto the
     *  app's own 5-value vocabulary. "Failing" mirrors GitHub's own PR-merge
     *  button semantics (see {@code AutomationCoordinator.aggregateChecks}). */
    private static String mapCheckStatus(String status, String conclusion)
    {
        if (!"completed".equals(status)) {
            return "queued".equals(status) ? PRCheck.STATUS_PENDING : PRCheck.STATUS_RUNNING;
        }
        if (conclusion == null) {
            return PRCheck.STATUS_NEUTRAL;
        }
        return switch (conclusion) {
            case "success" -> PRCheck.STATUS_PASSED;
            case "failure", "timed_out", "cancelled", "action_required", "startup_failure" -> PRCheck.STATUS_FAILED;
            default -> PRCheck.STATUS_NEUTRAL;
        };
    }

    /** Keeps the diff totals and CI/mergeable state {@code syncRemoteTimeline}
     *  already has on hand (from the same {@code detail} fetch) current on
     *  every external-PR sync — not just the throttled dashboard detail pass.
     *  The header sums this PR-level total rather than {@code pr_commit} rows,
     *  since GitHub's commit-list API has no per-commit stats (see {@link
     *  #syncExternalCommits}). Dashboard-only fields (watch reason, labels,
     *  attention reason, reviewer verdicts, comment count) are left exactly as
     *  {@code syncList}'s last pass set them — computing those needs the
     *  current user's login and viewed-at marker, which only {@code
     *  syncDashboardDetail} has to hand. */
    private void refreshDiffAndCiSnapshot(PR pr, PullRequestDetail detail)
    {
        PR.PRSyncSnapshot baseline = pr.githubSync();
        PR.PRSyncSnapshot next = new PR.PRSyncSnapshot(
                baseline == null ? null : baseline.watchReason(),
                baseline == null ? null : baseline.ghUpdatedAt(),
                baseline == null ? List.of() : baseline.labels(),
                baseline == null ? Map.of() : baseline.labelColors(),
                detail.draft(),
                detail.ciStatus(),
                detail.additions(),
                detail.deletions(),
                baseline == null ? 0 : baseline.commentCount(),
                baseline == null ? null : baseline.attentionReason(),
                detail.mergeable(),
                detail.mergeableState(),
                baseline == null ? null : baseline.headPushedAt(),
                baseline == null ? Map.of() : baseline.reviewerVerdicts(),
                detail.requestedReviewers() == null ? List.of() : detail.requestedReviewers());
        prService.updateSyncSnapshot(pr.id(), next);
    }

    private void syncCommits(PR pr, Task task, String base)
    {
        String cwd = task.worktreePath() != null && !task.worktreePath().isBlank()
                ? task.worktreePath() : task.workingDir();
        if (cwd == null || cwd.isBlank()) {
            return;
        }
        Set<String> known = new HashSet<>();
        for (PRCommit c : prService.commits(pr.id())) {
            known.add(c.sha());
        }
        try {
            Path dir = Path.of(cwd);
            // Resolve the real fork point rather than trusting the configured
            // base name verbatim — a stale local base ref (never fast-forwarded
            // while origin/<base> moved on, e.g. because another parallel
            // worktree merged work upstream) would otherwise sweep in commits
            // that already landed upstream as if they belonged to this branch
            // (see GitRunner.resolveCommitBase).
            String resolvedBase = git.resolveCommitBase(dir, base);
            List<GitRunner.CommitEntry> ahead = resolvedBase == null
                    ? List.of() : git.listCommitsAhead(dir, resolvedBase, COMMIT_LIMIT);
            // git log is newest-first; record oldest-first so the timeline reads
            // in the order the commits were authored.
            for (int i = ahead.size() - 1; i >= 0; i--) {
                GitRunner.CommitEntry c = ahead.get(i);
                if (known.contains(c.shortSha())) {
                    continue;
                }
                int[] delta = commitDelta(dir, c.sha());
                prService.recordCommit(
                        pr.id(), c.shortSha(), c.subject(), delta[0], delta[1], PRTimelineEntry.ACTOR_AGENT);
            }
        }
        catch (IOException e) {
            log.info("syncing commits for local PR {} failed: {}", pr.id(), e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("syncing commits for local PR {} interrupted", pr.id());
        }
    }

    /** Summed additions/deletions for one commit ({@code [add, del]}); zeros on
     *  any git failure so a stat hiccup never blocks recording the commit. */
    private int[] commitDelta(Path dir, String sha)
    {
        try {
            int add = 0;
            int del = 0;
            for (GitRunner.CommitFileChange f : git.commitFiles(dir, sha)) {
                add += f.additions();
                del += f.deletions();
            }
            return new int[] {add, del};
        }
        catch (IOException e) {
            return new int[] {0, 0};
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new int[] {0, 0};
        }
    }

    private void maybeFlipToOpen(String prId, Task task)
    {
        if (task.phase() == null || !READY_FOR_REVIEW.contains(task.phase())) {
            return;
        }
        PR pr = prService.findById(prId).orElse(null);
        if (pr != null && pr.canTransitionTo(PR.STATUS_LOCAL_OPEN)) {
            brainReview.reviewBeforeLocalOpen(prId, PRTimelineEntry.ACTOR_AGENT);
        }
    }
}
