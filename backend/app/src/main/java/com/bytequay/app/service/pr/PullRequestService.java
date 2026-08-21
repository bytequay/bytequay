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
package com.bytequay.app.service.pr;

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubMergeClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.GithubReviewState;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestService
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestService.class);
    // Minimum gap between forced detail re-fetches of an *unchanged* PR
    // that still looks under-enriched. The enrichment re-check exists to catch
    // reviewer verdicts that flip without bumping the PR's updatedAt, so
    // we can't drop it entirely — but re-fetching every ~60s sync tick
    // for every review-less PR is what exhausts the GitHub rate limit.
    // Throttling each such PR to one backfill attempt per this interval
    // keeps the safety net while cutting the steady-state cost ~15x.
    private static final Duration BACKFILL_RECHECK_INTERVAL = Duration.ofMinutes(15);

    // How often the notifications-feed backstop actually re-polls GitHub. The
    // dashboard sweep runs every ~60s, but the four search queries are the
    // primary source; notifications only catch what search silently drops, so
    // polling them (plus the per-PR fetch each surfaced ref costs) every sweep
    // is wasteful. Between polls the last resolved set is re-served so those
    // PRs stay in the sweep's result set and don't flicker off the dashboard.
    private static final Duration NOTIFICATION_POLL_INTERVAL = Duration.ofMinutes(5);

    // PR-search pagination for the dashboard's relevant-PR fetch. 100 is
    // GitHub's max page size; 6 pages caps the per-query cost at 600 PRs
    // (GitHub's search ceiling is 1000) while still covering reviewers
    // with far more than the old single 50-item page exposed.
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_PAGES = 6;

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubPullRequestWriteClient pullRequestWrites;
    private final GitHubMergeClient merges;
    private final GitHubAccountClient accounts;
    private final PullRequestStore store;
    private final PrDetailStore detailStore;
    private final PrViewStateStore viewStateStore;
    private final Executor executor;
    private final PullRequestDetailFetcher detailFetcher;
    private final CredentialService credentialService;
    /** prId → the last time we forced a detail-sync for an
     *  unchanged-but-under-enriched PR. Gates the enrichment / review-
     *  re-check to {@link #BACKFILL_RECHECK_INTERVAL} so a
     *  review-less PR isn't re-fetched on every sync tick. In-memory
     *  only — a restart just re-checks each PR once. */
    private final ConcurrentMap<Long, Instant> lastBackfillAttempt = new ConcurrentHashMap<>();
    /** Last resolved notification-backstop PRs and when we last polled the
     *  notifications feed. Re-served on every sweep inside {@link
     *  #NOTIFICATION_POLL_INTERVAL} so the search-invisible PRs they surface
     *  stay put; refreshed only past that interval. In-memory only — a restart
     *  just forces one poll on the next sweep. */
    private volatile List<PullRequest> cachedNotificationPrs = List.of();
    private volatile Instant lastNotificationPoll = Instant.EPOCH;

    @Autowired
    public PullRequestService(
            GitHubPullRequestReadClient gitHub,
            GitHubPullRequestWriteClient pullRequestWrites,
            GitHubMergeClient merges,
            GitHubActionsClient actions,
            GitHubAccountClient accounts,
            PullRequestStore store,
            PrDetailStore detailStore,
            PrViewStateStore viewStateStore,
            CredentialService credentialService,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.merges = requireNonNull(merges, "merges is null");
        requireNonNull(actions, "actions is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.store = requireNonNull(store, "store is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.detailFetcher = new PullRequestDetailFetcher(
                gitHub,
                actions,
                detailStore,
                requireNonNull(ioExecutor, "ioExecutor is null"));
    }

    public List<PullRequest> listPullRequests()
    {
        return store.findAll();
    }

    public List<RepoUser> listRepositoryUsers(String repo)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return gitHub.fetchAssignableUsers(pat, RepoRef.of(ref.owner(), ref.repo()));
    }

    public boolean hasMergeQueue(String repo, String baseRef)
    {
        requireNotBlank(baseRef, "baseRef");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return merges.hasMergeQueue(pat, RepoRef.of(ref.owner(), ref.repo()), baseRef);
    }

    /**
     * Fetches the PR list from GitHub, persists it, then refreshes detail for any PR whose
     * {@code updatedAt} changed (or is new). Stale detail for removed PRs is cleaned up.
     * All detail fetches run in parallel.
     */
    public void syncFromGitHub()
    {
        String pat = credentialService.resolveGitHubToken();
        Map<Long, Instant> existingUpdatedAt = store.findUpdatedAtMap();
        // Rows whose enrichment fields are still null get a forced detail
        // sync regardless of updatedAt.
        Set<Long> missingEnrichment = store.findIdsMissingEnrichment();
        // Pull the current user's login once per sync so we can reconcile
        // `handledAction` against reviews submitted outside the app (e.g. via
        // the embedded github.com window or a separate browser tab).
        String currentLogin = resolveCurrentLogin(pat);

        List<PullRequest> fresh = fetchRelevant(pat);
        store.replaceAll(fresh);

        Set<Long> freshIds = fresh.stream()
                .map(PullRequest::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> removedIds = existingUpdatedAt.keySet().stream()
                .filter(id -> !freshIds.contains(id))
                .collect(Collectors.toUnmodifiableSet());
        if (!removedIds.isEmpty()) {
            detailStore.deleteByPrIds(removedIds);
            removedIds.forEach(lastBackfillAttempt::remove);
        }

        // A PR earns a detail sync if its updatedAt moved (definitely
        // changed), or — to backfill rows GitHub didn't bump — if it
        // still looks under-enriched AND we haven't re-checked it within
        // BACKFILL_RECHECK_INTERVAL. The interval gate is what keeps a
        // review-less PR from being re-fetched on every ~60s tick.
        Instant now = Instant.now();
        List<PullRequest> toSync = new ArrayList<>();
        for (PullRequest pr : fresh) {
            Instant existing = existingUpdatedAt.get(pr.id());
            if (existing == null || !existing.equals(pr.updatedAt())) {
                toSync.add(pr);
                continue;
            }
            boolean underEnriched = missingEnrichment.contains(pr.id());
            if (underEnriched && backfillDue(pr.id(), now)) {
                lastBackfillAttempt.put(pr.id(), now);
                toSync.add(pr);
            }
        }
        List<CompletableFuture<Void>> detailFutures = toSync.stream()
                .map(pr -> CompletableFuture.runAsync(() -> syncDetailQuietly(pat, pr, currentLogin), executor))
                .toList();

        CompletableFuture.allOf(detailFutures.toArray(CompletableFuture[]::new)).join();
    }

    /** True when an under-enriched PR is due for a forced backfill sync:
     *  either we've never re-checked it or the last attempt is older than
     *  {@link #BACKFILL_RECHECK_INTERVAL}. */
    private boolean backfillDue(long prId, Instant now)
    {
        Instant last = lastBackfillAttempt.get(prId);
        return last == null || last.isBefore(now.minus(BACKFILL_RECHECK_INTERVAL));
    }

    private String resolveCurrentLogin(String pat)
    {
        try {
            return accounts.fetchUserProfile(pat).login();
        }
        catch (Exception e) {
            log.warn("Could not resolve current user for sync reconciliation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the full detail for a single PR. Reads from the local cache if available;
     * falls back to a live GitHub fetch (and stores the result) if the cache is cold.
     */
    public PullRequestDetail getPullRequestDetail(String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        RepoRef repoRef = RepoRef.of(ref.owner(), ref.repo());
        boolean viewerCanWrite = accounts.fetchViewerCanWrite(pat, repoRef);

        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        if (prId.isPresent()) {
            Optional<StoredPrDetail> stored = detailStore.find(prId.get());
            if (stored.isPresent()) {
                log.debug("[cache-diag] getPullRequestDetail {}#{}: SQLite snapshot HIT — returning cached body", repo, number);
                updateListState(prId.get(), stored.get());
                return PullRequestDetailMapper.toPullRequestDetail(repo, number, stored.get(), viewerCanWrite);
            }
            log.debug("[cache-diag] getPullRequestDetail {}#{}: SQLite snapshot MISS — calling fetchDetailFromGitHub", repo, number);
        }
        else {
            log.debug("[cache-diag] getPullRequestDetail {}#{}: no prId in store — calling fetchDetailFromGitHub", repo, number);
        }

        // Cache miss — fetch live, store for next time
        StoredPrDetail fetched = detailFetcher.fetch(pat, ref);
        prId.ifPresent(id -> {
            detailStore.save(id, fetched);
            // Propagate the aggregate CI status onto the PR row so Home and
            // the PR list update without waiting for the next bulk sync.
            store.updateCiStatus(id, PrAttention.aggregateCiStatus(fetched));
            updateListState(id, fetched);
        });
        return PullRequestDetailMapper.toPullRequestDetail(repo, number, fetched, viewerCanWrite);
    }

    public PullRequestDetail refreshPullRequestDetail(String repo, int number)
    {
        invalidatePullRequestDetail(repo, number);
        return getPullRequestDetail(repo, number);
    }

    /**
     * Fetches the list of files changed in a pull request along with their
     * unified-diff patches. Always served fresh from GitHub — patches are not
     * cached (too large + rarely re-read).
     */
    public List<DiffFile> getPullRequestDiffFiles(String repo, int number)
    {
        String pat = credentialService.resolveGitHubToken();
        return gitHub.fetchPrDiffFiles(pat, parseRef(repo, number));
    }

    public void commentOnPullRequest(String repo, int number, String body)
    {
        requireNotBlank(body, "comment body must not be blank");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        PrTimelineEvent created = pullRequestWrites.createIssueComment(pat, ref, body);
        if (created != null) {
            patchCachedDetail(store.findIdByRepoAndNumber(repo, number), cached -> {
                List<PrTimelineEvent> timeline = new ArrayList<>(cached.timeline());
                timeline.removeIf(event -> event.githubId() != null
                        && event.githubId().equals(created.githubId())
                        && "commented".equals(event.event()));
                timeline.add(created);
                return new StoredPrDetail(
                        cached.raw(),
                        cached.reviews(),
                        List.copyOf(timeline),
                        cached.checkRuns(),
                        cached.unavailableSections());
            });
        }
    }

    public void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        requireNotBlank(body, "reply body must not be blank");
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.replyToReviewComment(pat, parseRef(repo, number), commentId, body);
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Submits an approval review for the given pull request on GitHub and records a local reviewed state.
     */
    public void approvePullRequest(String repo, int number, long prId)
    {
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.approve(pat, parseRef(repo, number));
        viewStateStore.markReviewed(prId, HandledAction.APPROVED);
        invalidatePullRequestCaches(repo, number);
    }

    public void submitReview(String repo, int number, long prId, String event, String body)
    {
        requireNotBlank(event, "review event must not be blank");
        String normalizedEvent = event.toUpperCase(Locale.ROOT);
        if (!Set.of("COMMENT", "APPROVE", "REQUEST_CHANGES").contains(normalizedEvent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported review event");
        }
        String reviewBody = body == null ? "" : body.trim();
        if (!"APPROVE".equals(normalizedEvent)) {
            requireNotBlank(reviewBody, "review body must not be blank");
        }

        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.submitReview(pat, parseRef(repo, number), normalizedEvent, reviewBody);
        if ("APPROVE".equals(normalizedEvent)) {
            viewStateStore.markReviewed(prId, HandledAction.APPROVED);
        }
        else if ("REQUEST_CHANGES".equals(normalizedEvent)) {
            viewStateStore.markReviewed(prId, HandledAction.CHANGES_REQUESTED);
        }
        invalidatePullRequestCaches(repo, number);
    }
    /** Merges directly, falling back to GitHub's merge queue when required. */
    public MergeResult mergePullRequest(String repo, int number, long prId, String strategy)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        if ("queue".equalsIgnoreCase(strategy)) {
            return enqueuePullRequest(pat, ref, repo, number);
        }
        MergePullRequestCommand command = strategyCommand(strategy);
        MergeResult result;
        try {
            result = merges.mergePullRequest(pat, ref, command);
        }
        catch (ResponseStatusException e) {
            if (requiresMergeQueue(e)) {
                return enqueuePullRequest(pat, ref, repo, number);
            }
            throw e;
        }
        viewStateStore.markReviewed(prId, HandledAction.MERGED);
        // Drop the cached detail so the next /prs/detail call re-pulls the
        // timeline and the new "merged" / "closed" events surface
        // immediately, instead of waiting for the next background sync
        // (~2 min) to refresh the cached StoredPrDetail.
        invalidatePullRequestCaches(repo, number);
        return result;
    }

    private MergeResult enqueuePullRequest(String pat, PullRequestRef ref, String repo, int number)
    {
        String nodeId = merges.pullRequestNodeId(pat, ref)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned no pull request node ID"));
        MergeResult result = merges.enqueuePullRequest(pat, nodeId);
        invalidatePullRequestCaches(repo, number);
        return result;
    }

    /** True when a direct-merge rejection is GitHub requiring the change to
     *  go through the merge queue (HTTP 405 with a queue message). */
    private static boolean requiresMergeQueue(ResponseStatusException e)
    {
        return e.getStatusCode().value() == 405
                && e.getReason() != null
                && e.getReason().toLowerCase(Locale.ROOT).contains("merge queue");
    }

    private static MergePullRequestCommand strategyCommand(String strategy)
    {
        if (strategy == null) {
            return MergePullRequestCommand.rebase();
        }
        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "squash" -> MergePullRequestCommand.squash();
            case "merge" -> MergePullRequestCommand.mergeCommit();
            default -> MergePullRequestCommand.rebase();
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<PullRequest> fetchRelevant(String pat)
    {
        CompletableFuture<List<PullRequest>> authoredFuture = CompletableFuture.supplyAsync(
                () -> searchAllPages(pat, "is:pr is:open author:@me"), executor);
        // user-review-requested (not review-requested) so a review asked of
        // a TEAM the user belongs to is included, not just direct requests —
        // GitHub files team requests under the team, so review-requested:@me
        // misses them and the PR never reaches the board.
        CompletableFuture<List<PullRequest>> reviewFuture = CompletableFuture.supplyAsync(
                () -> searchAllPages(pat, "is:pr is:open user-review-requested:@me"), executor);
        // Open PRs I've already reviewed. GitHub drops a PR from
        // `review-requested:@me` the moment a verdict is submitted, so
        // without this an already-reviewed PR vanishes from the list
        // even though it's still open and re-reviewable — which is what
        // the assign-review dialog wants to offer. These carry the
        // REVIEW_REQUESTED origin too, but the awaiting-me filter still
        // hides them from the dashboard because they have a verdict.
        CompletableFuture<List<PullRequest>> reviewedByFuture = CompletableFuture.supplyAsync(
                () -> searchAllPages(pat, "is:pr is:open reviewed-by:@me"), executor);
        // Keep a short history of recently closed authored PRs for the Done tab.
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString();
        CompletableFuture<List<PullRequest>> recentlyClosedFuture = CompletableFuture.supplyAsync(
                () -> gitHub.searchPullRequests(pat,
                        "is:pr is:closed author:@me closed:>=" + sinceDate),
                executor);

        List<PullRequest> authored = join(authoredFuture);
        List<PullRequest> reviewRequested = join(reviewFuture);
        List<PullRequest> reviewedBy = join(reviewedByFuture);
        List<PullRequest> recentlyClosed = join(recentlyClosedFuture);

        LinkedHashMap<String, PullRequest> merged = new LinkedHashMap<>();
        if (authored != null) {
            for (PullRequest pr : authored) {
                merged.put(pr.repo() + "#" + pr.number(), withOrigin(pr, AUTHORED));
            }
        }
        if (recentlyClosed != null) {
            for (PullRequest pr : recentlyClosed) {
                merged.putIfAbsent(pr.repo() + "#" + pr.number(), withOrigin(pr, AUTHORED));
            }
        }
        if (reviewRequested != null) {
            for (PullRequest pr : reviewRequested) {
                String key = pr.repo() + "#" + pr.number();
                merged.putIfAbsent(key, withOrigin(pr, REVIEW_REQUESTED));
            }
        }
        if (reviewedBy != null) {
            for (PullRequest pr : reviewedBy) {
                String key = pr.repo() + "#" + pr.number();
                merged.putIfAbsent(key, withOrigin(pr, REVIEW_REQUESTED));
            }
        }
        // 5th source: the notifications feed (review requests + direct
        // @-mentions). GitHub's search index can silently drop a review request
        // (observed: a team review request on an enterprise repo never surfaced
        // in user-review-requested:@me), but the notifications pipeline — the
        // one that sends the email — always has it. Pull each PR search missed
        // via a direct read, stamped REVIEW_REQUESTED, deduped by repo#number.
        // ponytail: single notifications page (≤50, freshest first) covers
        // "jump to top on a new request/mention"; a PR reaches the board this
        // way only while its notification is still returned — the search sweep
        // stays the durable source. Also: a mention on the user's OWN PR that
        // author:@me search somehow dropped would be mislabelled REVIEW_REQUESTED
        // here (author:@me is reliable, so vanishingly rare). Add paging /
        // author-check / requested-reviewer re-verify only if these bite.
        for (PullRequest pr : notificationBackstopPrs(pat, merged.keySet())) {
            merged.putIfAbsent(pr.repo() + "#" + pr.number(), withOrigin(pr, REVIEW_REQUESTED));
        }
        return List.copyOf(merged.values());
    }

    /**
     * The open PRs surfaced by the notifications backstop. Re-polls the
     * notifications feed (and re-fetches each surfaced PR) at most once per
     * {@link #NOTIFICATION_POLL_INTERVAL}; every sweep in between re-serves the
     * last resolved set so those PRs stay in the dashboard result and don't
     * flicker. Best-effort throughout — a notifications-endpoint failure or a
     * transient per-PR 5xx must never regress the search-based sweep, so it
     * keeps whatever it resolved and retries on the next poll.
     */
    private List<PullRequest> notificationBackstopPrs(String pat, Set<String> alreadyPresent)
    {
        if (Duration.between(lastNotificationPoll, Instant.now()).compareTo(NOTIFICATION_POLL_INTERVAL) < 0) {
            return cachedNotificationPrs;
        }
        lastNotificationPoll = Instant.now();
        List<PullRequestRef> refs;
        try {
            refs = gitHub.fetchAttentionPrRefs(pat);
        }
        catch (RuntimeException e) {
            log.info("attention notifications fetch failed: {}", e.getMessage());
            return cachedNotificationPrs;
        }
        List<PullRequest> resolved = new ArrayList<>();
        for (PullRequestRef ref : refs) {
            // Skip anything the search sweep already surfaced — no point spending
            // a per-PR fetch on a PR we already have.
            if (alreadyPresent.contains(ref.repoFullName() + "#" + ref.number())) {
                continue;
            }
            try {
                PullRequest pr = gitHub.getPullRequest(pat, ref);
                // Notifications (all=true) still list review requests whose PR
                // has since closed/merged; the review-requested search is
                // is:open, so match it — a closed PR must never enter "To review".
                if ("open".equals(pr.state())) {
                    resolved.add(pr);
                }
            }
            catch (RuntimeException e) {
                log.info("notification PR {} fetch failed: {}", ref.fullName(), e.getMessage());
            }
        }
        cachedNotificationPrs = List.copyOf(resolved);
        return cachedNotificationPrs;
    }

    /**
     * Runs a GitHub PR search to exhaustion (up to {@link #MAX_SEARCH_PAGES}
     * pages of {@link #SEARCH_PAGE_SIZE}), instead of the single 50-item
     * page the dashboard used to read. A heavy reviewer can sit on more
     * than 50 open review requests; the old single page silently dropped
     * the tail, so those PRs never appeared anywhere — including the
     * assign-review dialog's number search.
     */
    private List<PullRequest> searchAllPages(String pat, String query)
    {
        List<PullRequest> all = new ArrayList<>();
        for (int page = 1; page <= MAX_SEARCH_PAGES; page++) {
            // sort=updated desc so the freshest PRs land on the first pages —
            // GitHub's default best-match ordering can push the newest past
            // the MAX_SEARCH_PAGES cap and out of the synced set entirely.
            PullRequestHistoryPage result =
                    gitHub.searchPullRequestsPaged(pat, query, page, SEARCH_PAGE_SIZE, "updated", "desc");
            all.addAll(result.items());
            if (!result.hasMore()) {
                break;
            }
        }
        return List.copyOf(all);
    }

    private void syncDetailQuietly(String pat, PullRequest pr, String currentLogin)
    {
        try {
            PullRequestRef ref = parseRef(pr.repo(), pr.number());
            StoredPrDetail detail = detailFetcher.fetch(pat, ref);
            // A full replacement keeps mutable review-thread resolution and
            // edited comment payloads accurate. Detail syncs only run for a
            // changed PR (or a throttled backfill), so this remains bounded.
            detailStore.save(pr.id(), detail);
            updateListState(pr.id(), detail);
            // One viewState lookup feeds both reconcile (handledAction) and
            // the MENTIONED rule (viewedAt anchors which mentions are "new").
            PrViewState viewState = viewStateStore.findAll().get(pr.id());
            reconcileHandledActionFromReviews(pr.id(), detail.reviews(), currentLogin, viewState);
            Instant viewedAt = viewState != null ? viewState.viewedAt() : null;
            // Refresh list-level values without reloading the detail blob.
            store.updateEnrichment(
                    pr.id(),
                    PrAttention.aggregateCiStatus(detail),
                    detail.raw() != null ? detail.raw().additions() : 0,
                    detail.raw() != null ? detail.raw().deletions() : 0,
                    PrAttention.countComments(detail),
                    PrAttention.promoteReason(pr, detail, currentLogin, viewedAt, Instant.now()),
                    detail.raw() != null ? detail.raw().mergeable() : null,
                    detail.raw() != null ? detail.raw().mergeableState() : null,
                    PullRequestDetailMapper.latestPushAt(detail.timeline()),
                    PullRequestDetailMapper.rolledUpReviewerVerdicts(detail.reviews()),
                    detail.raw() != null ? detail.raw().headRef() : null);
        }
        catch (Exception e) {
            log.warn("Failed to sync detail for PR {}/{}: {}", pr.repo(), pr.number(), e.getMessage());
        }
    }

    private void updateListState(long prId, StoredPrDetail detail)
    {
        if (detail.raw() != null && detail.raw().state() != null) {
            store.updateState(
                    prId,
                    detail.raw().state(),
                    detail.raw().updatedAt(),
                    detail.raw().mergedAt());
        }
    }

    /**
     * If the user has submitted a review on this PR outside the app (via the
     * embedded github.com window, a browser tab, or the GitHub mobile app),
     * mirror that state into {@code pr_view_state.handled_action} so the PR
     * shows up correctly in the Handled bucket. Never overrides an
     * explicitly-set handled action — the user's in-app choice wins.
     */
    private void reconcileHandledActionFromReviews(long prId, List<PrReviewState> reviews, String currentLogin, PrViewState existing)
    {
        HandledAction derived = deriveHandledActionFromReviews(reviews, currentLogin);
        if (derived == null) {
            return;
        }
        if (existing != null && existing.handledAction() != null) {
            return;
        }
        viewStateStore.markReviewed(prId, derived);
    }

    /**
     * Pure-logic version of the review→handled-action mapping, exposed
     * package-private for unit tests. Returns the action implied by the
     * current user's latest review, or {@code null} if no conclusive
     * APPROVED / CHANGES_REQUESTED review exists. Drive-by COMMENTED reviews
     * and standalone DISMISSED entries are intentionally ignored — GitHub
     * surfaces them in the review list but they don't mean the user has
     * "handled" the PR.
     */
    static HandledAction deriveHandledActionFromReviews(List<PrReviewState> reviews, String currentLogin)
    {
        if (currentLogin == null || reviews == null || reviews.isEmpty()) {
            return null;
        }
        HandledAction derived = null;
        for (PrReviewState r : reviews) {
            if (r == null || !currentLogin.equalsIgnoreCase(r.login())) {
                continue;
            }
            if (GithubReviewState.APPROVED.equals(r.state())) {
                derived = HandledAction.APPROVED;
            }
            else if (GithubReviewState.CHANGES_REQUESTED.equals(r.state())) {
                derived = HandledAction.CHANGES_REQUESTED;
            }
            else if ("DISMISSED".equals(r.state())) {
                // A later DISMISSED resets the derived state — if nothing
                // else follows, the user effectively has no stance.
                derived = null;
            }
        }
        return derived;
    }

    private void invalidatePullRequestDetail(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(id -> detailStore.deleteByPrIds(Set.of(id)));
    }

    private void invalidatePullRequestCaches(String repo, int number)
    {
        invalidatePullRequestDetail(repo, number);
    }

    private void patchCachedDetail(Optional<Long> prId, UnaryOperator<StoredPrDetail> patcher)
    {
        requireNonNull(patcher, "patcher is null");
        prId.ifPresent(id -> detailStore.find(id)
                .map(patcher)
                .ifPresent(patched -> detailStore.save(id, patched)));
    }

    private static PullRequest withOrigin(PullRequest pr, PullRequest.Origin origin)
    {
        return new PullRequest(
                pr.id(),
                pr.repo(),
                pr.number(),
                pr.title(),
                pr.author(),
                pr.htmlUrl(),
                pr.createdAt(),
                pr.updatedAt(),
                origin,
                pr.labels(),
                pr.labelColors(),
                pr.draft(),
                pr.viewedAt(),
                pr.reviewedAt(),
                pr.handledAction(),
                pr.requestedReviewers(),
                pr.ciStatus(),
                pr.additions(),
                pr.deletions(),
                pr.commentCount(),
                pr.attentionReason(),
                pr.state(),
                pr.closedAt(),
                pr.mergedAt(),
                pr.mergeable(),
                pr.mergeableState(),
                pr.headPushedAt(),
                pr.reviewerVerdicts(),
                pr.headRef());
    }

    private static <T> T join(CompletableFuture<T> future)
    {
        return future.join();
    }
}
