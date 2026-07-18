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

import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.DiffSide;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.GithubReviewState;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.RepoMetadataCacheStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.RepoListCache;
import com.bytequay.app.service.credentials.PatResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
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

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.app.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRepoRef;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestService
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestService.class);
    // How many V53-backfill PRs to re-fetch per sync tick. A bounded
    // batch keeps the rate-limit cost predictable — once the table is
    // backfilled the query returns 0 and this loop is free.
    private static final int REVIEW_TIMESTAMP_BACKFILL_BATCH = 25;

    // Minimum gap between forced detail re-fetches of an *unchanged* PR
    // that still looks under-enriched (empty reviewer verdicts, null
    // review timestamps). The enrichment re-check exists to catch
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
    private static final Duration REPO_METADATA_TTL = Duration.ofDays(7);

    // PR-search pagination for the dashboard's relevant-PR fetch. 100 is
    // GitHub's max page size; 6 pages caps the per-query cost at 600 PRs
    // (GitHub's search ceiling is 1000) while still covering reviewers
    // with far more than the old single 50-item page exposed.
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_PAGES = 6;

    private final PullRequestRepository gitHub;
    private final PullRequestStore store;
    private final PrDetailStore detailStore;
    private final PrViewStateStore viewStateStore;
    private final AppSettingsStore settingsStore;
    private final CredentialService credentialService;
    private final GitHubResponseCache responseCache;
    private final PullRequestDetailInvalidator detailInvalidator;
    private final RepoListCache repoListCache;
    private final RepoMetadataCacheStore repoMetadataCache;
    private final Executor executor;
    private final PullRequestDetailFetcher detailFetcher;
    private final PatResolver patResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskStore taskStore;
    private final CollaboratorPermissionService collaboratorPermissions;
    /** prId → last ETag + the timestamp it was returned by GitHub.
     *  Populated by {@link #refreshPullRequestDetail}'s probe path
     *  and consulted on the next probe to short-circuit unchanged
     *  PRs (304 → no rate-limit cost). The {@code lastProbedAt}
     *  field powers the {@code maxAgeSeconds} short-circuit — when
     *  a caller (e.g. the detail-page 10s polling tick) probes more
     *  often than necessary, we serve cached without even hitting
     *  GitHub's {@code If-None-Match} endpoint. In-memory only —
     *  a backend restart just means the next probe pays for one
     *  full fetch. */
    private final ConcurrentMap<Long, EtagEntry> detailEtags = new ConcurrentHashMap<>();
    /** prId → the last time we forced a backfill detail-sync for an
     *  unchanged-but-under-enriched PR. Gates the enrichment / review-
     *  timestamp re-check to {@link #BACKFILL_RECHECK_INTERVAL} so a
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

    /** Snapshot of "last ETag and when we got it" for one PR. */
    private record EtagEntry(String etag, Instant lastProbedAt) {}

    public PullRequestService(
            PullRequestRepository gitHub,
            PullRequestStore store,
            PrDetailStore detailStore,
            PrViewStateStore viewStateStore,
            AppSettingsStore settingsStore,
            CredentialService credentialService,
            GitHubResponseCache responseCache,
            PullRequestDetailInvalidator detailInvalidator,
            RepoListCache repoListCache,
            RepoMetadataCacheStore repoMetadataCache,
            PatResolver patResolver,
            ApplicationEventPublisher eventPublisher,
            TaskStore taskStore,
            CollaboratorPermissionService collaboratorPermissions,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher is null");
        this.store = requireNonNull(store, "store is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.responseCache = requireNonNull(responseCache, "responseCache is null");
        this.detailInvalidator = requireNonNull(detailInvalidator, "detailInvalidator is null");
        this.repoListCache = requireNonNull(repoListCache, "repoListCache is null");
        this.repoMetadataCache = requireNonNull(repoMetadataCache, "repoMetadataCache is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.collaboratorPermissions = requireNonNull(collaboratorPermissions, "collaboratorPermissions is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.detailFetcher = new PullRequestDetailFetcher(gitHub, detailStore, requireNonNull(ioExecutor, "ioExecutor is null"));
    }

    /**
     * Returns the PAT to use for repo-scoped GitHub calls about {@code repo}:
     * a per-repo (REPO, repo) credential when present, otherwise falls back
     * to the account-level PAT passed in.
     */
    private String patForRepo(String accountPat, String repo)
    {
        return credentialService.getSecret(CredentialType.REPO, repo)
                .filter(s -> !s.isBlank())
                .orElse(accountPat);
    }

    /**
     * Returns all pull requests from the local database, sorted by the user's configured order.
     */
    public List<PullRequest> listPullRequests()
    {
        return PullRequestOrder.fromKey(settingsStore.get(AppSettingsStore.Key.PR_SORT_ORDER).orElse(""))
                .sort(store.findAll());
    }

    /**
     * Fetches a single PR straight from GitHub by repo + number,
     * bypassing the cached dashboard list. Backs the assign-review
     * dialog's on-demand lookup so the user can target any PR they can
     * see on GitHub — even one that never enters the dashboard's
     * relevant-PR set (e.g. requested via a team, or in an unwatched
     * repo). Propagates GitHub's 404 when no such PR exists.
     */
    public PullRequest lookupPullRequest(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        return gitHub.getPullRequest(pat, parseRef(repo, number));
    }

    /**
     * Fetches the PR list from GitHub, persists it, then refreshes detail for any PR whose
     * {@code updatedAt} changed (or is new). Stale detail for removed PRs is cleaned up.
     * All detail fetches run in parallel.
     */
    public void syncFromGitHub()
    {
        String pat = patResolver.resolve();
        Map<Long, Instant> existingUpdatedAt = store.findUpdatedAtMap();
        // Rows whose V26 enrichment fields are still null get a forced
        // detail sync below regardless of `updatedAt`. Catches legacy
        // rows whose `updatedAt` hasn't moved since the kanban
        // categorization started reading reviewer_verdicts; without
        // this backfill those rows render as "Opened" forever.
        Set<Long> missingEnrichment = store.findIdsMissingEnrichment();
        // Same idea for the V53 review-timestamp backfill: pull a
        // capped batch of PRs whose cached reviews still have null
        // submitted_at and queue them alongside missing-enrichment so
        // the analytics page's time-bucketed cards (daily bars,
        // heatmap, response time) light up without waiting for the PR
        // itself to be touched again on GitHub.
        Set<Long> missingReviewTimestamps = ImmutableSet.copyOf(
                detailStore.findPrIdsMissingReviewTimestamps(REVIEW_TIMESTAMP_BACKFILL_BATCH));

        // Pull the current user's login once per sync so we can reconcile
        // `handledAction` against reviews submitted outside the app (e.g. via
        // the embedded github.com window or a separate browser tab).
        String currentLogin = resolveCurrentLogin(pat);

        List<PullRequest> fresh = fetchRelevant(pat);
        store.replaceAll(fresh);
        linkPrsToTasks(fresh);

        Set<Long> freshIds = fresh.stream()
                .map(PullRequest::id)
                .collect(toImmutableSet());
        Set<Long> removedIds = existingUpdatedAt.keySet().stream()
                .filter(id -> !freshIds.contains(id))
                .collect(toImmutableSet());
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
            boolean underEnriched = missingEnrichment.contains(pr.id())
                    || missingReviewTimestamps.contains(pr.id());
            if (underEnriched && backfillDue(pr.id(), now)) {
                lastBackfillAttempt.put(pr.id(), now);
                toSync.add(pr);
            }
        }
        List<CompletableFuture<Void>> detailFutures = toSync.stream()
                .map(pr -> CompletableFuture.runAsync(() -> syncDetailQuietly(pat, pr, currentLogin), executor))
                .collect(toImmutableList());

        CompletableFuture.allOf(detailFutures.toArray(CompletableFuture[]::new)).join();

        // After detail-sync has refreshed reviewerVerdicts / mergeable /
        // ciStatus / etc., walk the snoozed PRs and wake any whose timer
        // has elapsed or whose urgent signals have flipped on. Cheap —
        // an in-memory pass over the local store.
        try {
            runAutoWakeCheck();
        }
        catch (Exception e) {
            log.warn("Snooze auto-wake check failed: {}", e.getMessage());
        }
    }

    /** The dashboard's relevant-PR sweep (open authored + review-requested +
     *  reviewed-by + recently-closed-authored), for the unified {@code
     *  PRSyncService.syncList} to upsert into the {@code pr} table. Pure
     *  GitHub search — no storage side effects, same as every other caller
     *  of {@link #fetchRelevant}. */
    public List<PullRequest> searchRelevantForDashboard()
    {
        return fetchRelevant(patResolver.resolve());
    }

    /** The authenticated GitHub login, for the unified dashboard sync's
     *  attention-reason / mention detection — same resolution {@link
     *  #syncFromGitHub} uses for its own reconciliation pass. */
    public String resolveCurrentDashboardLogin()
    {
        return resolveCurrentLogin(patResolver.resolve());
    }

    /** True when an under-enriched PR is due for a forced backfill sync:
     *  either we've never re-checked it or the last attempt is older than
     *  {@link #BACKFILL_RECHECK_INTERVAL}. */
    private boolean backfillDue(long prId, Instant now)
    {
        Instant last = lastBackfillAttempt.get(prId);
        return last == null || last.isBefore(now.minus(BACKFILL_RECHECK_INTERVAL));
    }

    /**
     * Auto-link synced PRs back to their originating task by head branch,
     * so a PR opened outside the app's {@code open_pr} flow (e.g. manually
     * on GitHub) still attaches to its task, and a linked task's PR state
     * stays current (open → merged) on every sync. Bounded to {@code dev/}
     * head refs — the prefix worktree task branches carry — so a busy
     * repo's sync doesn't issue a task lookup per unrelated PR.
     */
    private void linkPrsToTasks(List<PullRequest> prs)
    {
        for (PullRequest pr : prs) {
            String head = pr.headRef();
            if (head == null || !head.startsWith("dev/")) {
                continue;
            }
            Task task = taskStore.findTaskByBranch(head).orElse(null);
            if (task == null) {
                continue;
            }
            String state = prStateFor(pr);
            // The sync runs often; skip the write when nothing changed.
            if (Integer.valueOf(pr.number()).equals(task.prNumber())
                    && state.equals(task.prState())) {
                continue;
            }
            try {
                taskStore.linkPullRequest(task.id(), pr.number(), state);
                taskStore.linkTaskToPr(task.id(), pr.repo() + "#" + pr.number());
            }
            catch (RuntimeException e) {
                log.warn("auto-linking PR #{} to task {} failed: {}",
                        pr.number(), task.id(), e.getMessage());
            }
        }
    }

    /** Derive the task-facing PR state from a synced PR. */
    private static String prStateFor(PullRequest pr)
    {
        if (pr.mergedAt() != null) {
            return "merged";
        }
        if (pr.draft()) {
            return "draft";
        }
        if ("closed".equalsIgnoreCase(pr.state())) {
            return "closed";
        }
        return "open";
    }

    private String resolveCurrentLogin(String pat)
    {
        try {
            return gitHub.fetchUserProfile(pat).login();
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
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        RepoRef repoRef = RepoRef.of(ref.owner(), ref.repo());
        boolean viewerCanWrite = responseCache.getViewerCanWrite(
                pat,
                repoRef,
                () -> gitHub.fetchViewerCanWrite(pat, repoRef));

        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        if (prId.isPresent()) {
            Optional<StoredPrDetail> stored = detailStore.find(prId.get());
            if (stored.isPresent()) {
                log.info("[cache-diag] getPullRequestDetail {}#{}: SQLite snapshot HIT — returning cached body",
                        repo, number);
                int writeApprovals = collaboratorPermissions.countWriteApprovals(pat, repoRef, stored.get().reviews());
                return PullRequestDetailMapper.toPullRequestDetail(
                        repo, number, stored.get(), viewerCanWrite, writeApprovals);
            }
            log.info("[cache-diag] getPullRequestDetail {}#{}: SQLite snapshot MISS — calling fetchDetailFromGitHub",
                    repo, number);
        }
        else {
            log.info("[cache-diag] getPullRequestDetail {}#{}: no prId in store — calling fetchDetailFromGitHub",
                    repo, number);
        }

        // Cache miss — fetch live, store for next time
        StoredPrDetail fetched = detailFetcher.fetch(pat, ref);
        prId.ifPresent(id -> {
            detailStore.save(id, fetched);
            // Propagate the aggregate CI status onto the PR row so the
            // kanban categorizer (prBuckets.ts) picks up a fresh
            // FAILING state without waiting for the next bulk sync.
            // The detail blob has it; the row didn't until this line.
            store.updateCiStatus(id, PrAttention.aggregateCiStatus(fetched));
        });
        int writeApprovals = collaboratorPermissions.countWriteApprovals(pat, repoRef, fetched.reviews());
        return PullRequestDetailMapper.toPullRequestDetail(repo, number, fetched, viewerCanWrite, writeApprovals);
    }

    /**
     * Refreshes one PR's detail. Tries a cheap conditional GET first
     * ({@code If-None-Match} on the cached ETag); when GitHub answers
     * 304 we skip the full multi-call refetch and return the cached
     * snapshot — that 304 doesn't count against the rate limit, so
     * the navigate-back-to-PR flow is essentially free for quiet PRs.
     *
     * <p>On a miss (no prior ETag, 200, or any probe error) we fall
     * back to the original invalidate-then-refetch path so the caller
     * always gets the freshest data we can produce.
     */
    public PullRequestDetail refreshPullRequestDetail(String repo, int number)
    {
        return refreshPullRequestDetail(repo, number, 0);
    }

    /**
     * Same as {@link #refreshPullRequestDetail(String, String, int)},
     * with a {@code maxAgeSeconds} short-circuit. When we've already
     * probed GitHub for this PR within the last {@code maxAgeSeconds}
     * the response is served from the local detail store with no
     * network call at all — neither full refetch nor ETag probe.
     *
     * <p>Powers the detail-page 10s polling tick (frontend ticks every
     * 10s, passes {@code maxAgeSeconds=20}, so concurrent tabs probe
     * GitHub at most once per 20s on average — every other tick hits
     * the fast path). The manual ↻ refresh button still passes
     * {@code 0} so it always probes.
     */
    public PullRequestDetail refreshPullRequestDetail(String repo, int number, int maxAgeSeconds)
    {
        String pat = patResolver.resolve(repo);
        log.info("[cache-diag] refreshPullRequestDetail entry: {}#{} maxAge={}s", repo, number, maxAgeSeconds);
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        if (prId.isPresent()) {
            // Fast path: a recent probe already established that the
            // cached snapshot is current. Skip even the ETag round-trip.
            if (maxAgeSeconds > 0) {
                EtagEntry entry = detailEtags.get(prId.get());
                if (entry != null
                        && entry.lastProbedAt() != null
                        && entry.lastProbedAt().isAfter(Instant.now().minusSeconds(maxAgeSeconds))) {
                    log.info("[cache-diag] maxAge FAST-PATH fired for {}#{}: lastProbed {}s ago (<= {}s)",
                            repo, number,
                            Instant.now().getEpochSecond() - entry.lastProbedAt().getEpochSecond(),
                            maxAgeSeconds);
                    return getPullRequestDetail(repo, number);
                }
                else {
                    log.info("[cache-diag] maxAge fast-path SKIPPED for {}#{}: entry={} lastProbedAt={}",
                            repo, number,
                            entry == null ? "NULL" : "present",
                            entry == null ? "n/a" : (entry.lastProbedAt() == null ? "null"
                                    : (Instant.now().getEpochSecond() - entry.lastProbedAt().getEpochSecond()) + "s ago"));
                }
            }
            try {
                PullRequestRef ref = parseRef(repo, number);
                EtagEntry cachedEntry = detailEtags.get(prId.get());
                String cachedEtag = cachedEntry != null ? cachedEntry.etag() : null;
                PullRequestRepository.ProbeResult probe =
                        gitHub.probeChangedSinceEtag(pat, ref, cachedEtag);
                Instant now = Instant.now();
                // Always capture the latest ETag — including the
                // first-ever probe (no cached ETag → 200 response,
                // body discarded, ETag captured for the next call).
                if (probe.newEtag() != null) {
                    detailEtags.put(prId.get(), new EtagEntry(probe.newEtag(), now));
                }
                else if (cachedEntry != null) {
                    // GitHub didn't issue a new ETag (rare — happens on
                    // some 304 responses). Bump the probe timestamp
                    // anyway so the maxAge short-circuit sees the
                    // probe just happened.
                    detailEtags.put(prId.get(), new EtagEntry(cachedEntry.etag(), now));
                }
                if (cachedEtag != null && !probe.changed()) {
                    // 304: nothing's changed since we last fetched.
                    // Skip the multi-call refetch and serve cached.
                    log.info("[cache-diag] ETag probe 304 for {}#{} — serving cached (no refetch)", repo, number);
                    return getPullRequestDetail(repo, number);
                }
                log.info("[cache-diag] ETag probe for {}#{}: hadCachedEtag={} changed={} → invalidate + refetch",
                        repo, number, cachedEtag != null, probe.changed());
            }
            catch (Exception e) {
                // Probe is best-effort. Fall through to the full
                // refetch — never gate correctness on the probe.
                log.info("[cache-diag] ETag probe failed for {}#{}: {} → invalidate + refetch",
                        repo, number, e.getMessage());
            }
        }
        invalidatePullRequestDetail(repo, number);
        return getPullRequestDetail(repo, number);
    }

    /**
     * Returns conversation (issue) comments created on the PR after
     * {@code since}, mapped to the same activity-item shape the detail
     * timeline uses. Backs the detail page's lightweight comments-delta
     * poll: it runs on a tighter cadence than the full-detail refresh and
     * touches only the issue-comments endpoint, so a reviewer's new comment
     * surfaces quickly without paying for the multi-call detail refetch.
     *
     * <p>{@code since} is GitHub's inclusive lower bound, so a boundary
     * comment can come back again; the caller dedups by comment id.
     */
    public List<PullRequestDetail.ActivityItem> fetchNewComments(String repo, int number, Instant since)
    {
        requireNonNull(since, "since is null");
        String pat = patResolver.resolve(repo);
        List<PrTimelineEvent> comments = gitHub.fetchPrIssueComments(pat, parseRef(repo, number), since);
        return PullRequestDetailMapper.toActivityItems(comments);
    }

    /**
     * Toggles a PR between draft and ready-for-review. Drops the cached
     * detail so the next fetch reflects the new state and the timeline
     * picks up the synthetic "ready for review" / "marked as draft"
     * event GitHub emits.
     */
    public void setPullRequestDraft(String repo, int number, boolean draft)
    {
        String pat = patResolver.resolve(repo);
        gitHub.setPullRequestDraft(pat, parseRef(repo, number), draft);
        invalidatePullRequestCaches(repo, number);
    }

    /** The result of a title edit, returned to the caller so the UI can
     *  reflect the change before the next background sync lands. */
    public record PrTitleUpdate(int number, String title, Instant updatedAt) {}

    /**
     * Renames a PR on GitHub, then busts the cached detail so the next
     * {@link PullRequestDetailFetcher} poll surfaces the new title. The
     * title must be non-blank, ≤256 chars, and actually different from the
     * cached one; a PR that isn't in our cache 404s. A GitHub permission
     * failure (401/403) maps to 403, any other GitHub error to 502.
     */
    public PrTitleUpdate updatePullRequestTitle(String repo, int number, String newTitle)
    {
        String trimmed = newTitle == null ? "" : newTitle.strip();
        if (trimmed.isEmpty() || trimmed.length() > 256) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Title must be between 1 and 256 characters");
        }
        PullRequest cached = store.findIdByRepoAndNumber(repo, number)
                .flatMap(store::findById)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "No such pull request: " + repo + "#" + number));
        if (trimmed.equals(cached.title())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "Title is unchanged");
        }
        String pat = patResolver.resolve(repo);
        try {
            gitHub.updatePullRequest(pat, parseRef(repo, number),
                    new UpdatePullRequestCommand(Optional.of(trimmed),
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }
        catch (ResponseStatusException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(403),
                        "You don't have permission to rename this pull request", e);
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "GitHub rejected the title update", e);
        }
        invalidatePullRequestCaches(repo, number);
        return new PrTitleUpdate(number, trimmed, Instant.now());
    }

    /**
     * Re-runs the failed CI jobs on {@code headSha} for {@code repo}
     * (GitHub's "re-run failed jobs"). Used by the post-ship loop to
     * shake out a transient/flaky failure before spending an agent
     * turn. Returns how many workflow runs were re-triggered — 0 when
     * nothing on the head had failed.
     */
    public int rerunFailedChecks(String repo, String headSha)
    {
        String pat = patResolver.resolve(repo);
        return gitHub.rerunFailedChecks(pat, parseRepoRef(repo), headSha);
    }

    /**
     * Returns the raw log text for a single Actions check-run, capped at
     * a sensible size so a 50MB job log doesn't crater the renderer.
     * Empty string when GitHub doesn't expose a log for this check
     * (external CI, expired log, missing PAT scope) — the frontend
     * shows a "log unavailable" hint in that case.
     */
    public String getCheckRunLog(String repo, long checkRunId)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef refOrNull = parseRef(repo, 1); // PR number not needed for the call
        return gitHub.fetchCheckRunLog(pat, RepoRef.of(refOrNull.owner(), refOrNull.repo()), checkRunId)
                .map(PullRequestService::trimLogToTail)
                .orElse("");
    }

    /**
     * Re-runs the failed CI jobs on the PR's head commit — GitHub's
     * built-in flaky-failure fix. Returns how many workflow runs were
     * re-triggered (0 when nothing on the head had failed). One PR fetch
     * resolves the head SHA the Actions API keys off.
     */
    public int rerunFailedChecks(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        PrRawDetail raw = gitHub.fetchPrDetail(pat, ref);
        String headSha = raw == null ? null : raw.headSha();
        return gitHub.rerunFailedChecks(pat, RepoRef.of(ref.owner(), ref.repo()), headSha);
    }

    /** Logs from CI runs are appended chronologically; the *end* of the
     *  log is almost always the failure context. Cap at 200 KB tail —
     *  large enough for a deep stack trace, small enough to ship over
     *  IPC and render in a {@code <pre>} without choking the UI. */
    private static String trimLogToTail(String full)
    {
        final int cap = 200_000;
        if (full.length() <= cap) {
            return full;
        }
        int start = full.length() - cap;
        // Don't slice mid-line — start at the next newline so the first
        // visible line isn't a half-message.
        int newline = full.indexOf('\n', start);
        if (newline >= 0 && newline - start < 1024) {
            start = newline + 1;
        }
        return "… (log truncated; showing last " + (full.length() - start) + " bytes)\n" + full.substring(start);
    }

    /**
     * Lightweight CI snapshot for the focus-driven polling on the PR detail
     * page. Skips the full timeline + threads orchestration; just refetches
     * the head SHA's check runs and the per-PAT write permission. The merge
     * button on the detail page reads both from this response so it can
     * react to a CI flip without waiting for the next full-detail load.
     */
    public PrCiSnapshot getPullRequestCiSnapshot(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        PrRawDetail raw = gitHub.fetchPrDetail(pat, ref);
        List<PrCheckRunState> runs = raw != null && raw.headSha() != null
                ? gitHub.fetchPrCheckRuns(pat, ref.owner(), ref.repo(), raw.headSha())
                : ImmutableList.of();
        RepoRef repoRef = RepoRef.of(ref.owner(), ref.repo());
        boolean viewerCanWrite = responseCache.getViewerCanWrite(
                pat,
                repoRef,
                () -> gitHub.fetchViewerCanWrite(pat, repoRef));
        PullRequestDetail.CiStatus aggregate = PullRequestDetailMapper.aggregateCiStatus(runs);
        // Propagate the freshly-computed aggregate onto the PR row so
        // a click on the merge bar's ↻ refresh also re-routes the
        // kanban card (categorizeMyPr / categorizeToReview both read
        // ciStatus from the row, not the detail blob).
        store.findIdByRepoAndNumber(repo, number).ifPresent(id -> {
            store.updateCiStatus(id, aggregate);
            // Also patch the cached detail blob's check runs. The detail-page
            // poll serves that snapshot, and aggregateCiStatus recomputes the
            // pill from it — so a stale blob (e.g. right after a re-run, while
            // the 60s sync hasn't re-fetched) would otherwise overwrite this
            // fresh PENDING with the old FAILING on the next tick.
            detailStore.find(id).ifPresent(d -> detailStore.save(id, new StoredPrDetail(
                    d.raw(), d.reviews(), d.files(), d.timeline(), runs,
                    d.reviewComments(), d.linkedIssues(), d.mergeQueueState(), d.mergeQueueEnabled())));
        });
        return new PrCiSnapshot(
                aggregate,
                PullRequestDetailMapper.toCheckRuns(runs),
                viewerCanWrite);
    }

    /**
     * Records that the user opened this PR in the app. Idempotent.
     */
    public void markViewed(long prId)
    {
        viewStateStore.markViewed(prId);
    }

    /**
     * Id-namespace-safe variant of {@link #markViewed(long)}: the local
     * store keys by GitHub's search-issue ids, while rows fetched via the
     * REST pulls endpoints carry pull-request ids — the same PR under two
     * different numbers. Resolving by repo + number sidesteps the mismatch.
     * No-op when the PR isn't in the local store (nothing tracks it).
     */
    public void markViewed(String repo, int number)
    {
        store.findAll().stream()
                .filter(pr -> pr.repo().equals(repo) && pr.number() == number)
                .findFirst()
                .ifPresent(pr -> viewStateStore.markViewed(pr.id()));
    }

    /**
     * Fetches the list of files changed in a pull request along with their
     * unified-diff patches. Always served fresh from GitHub — patches are not
     * cached (too large + rarely re-read).
     */
    public List<DiffFile> getPullRequestDiffFiles(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        return gitHub.fetchPrDiffFiles(pat, parseRef(repo, number));
    }

    /**
     * Fetches the commits in a pull request, oldest first. Also served fresh —
     * commit metadata is small and the sync job doesn't currently retain it.
     */
    public List<PullRequestCommit> getPullRequestCommits(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        return gitHub.fetchPrCommits(pat, parseRef(repo, number));
    }

    /**
     * Returns the diff scoped to a single commit — same DiffFile shape as
     * {@link #getPullRequestDiffFiles}, but only the changes that this one
     * sha introduced. Backs the "select a commit" affordance in the diff
     * viewer for PRs with many commits.
     */
    public List<DiffFile> getCommitDiffFiles(String repo, int number, String sha)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        return responseCache.getCommitDiffFiles(
                pat,
                ref,
                sha,
                () -> gitHub.fetchCommitDiffFiles(pat, ref, sha));
    }

    /**
     * Fetches a file's full content at a specific commit, returned as a
     * list of lines (1-based by index in the caller's view). Backs the
     * "expand collapsed code" affordance in the diff viewer.
     */
    public List<String> getFileBlobLines(String repo, String path, String sha)
    {
        String pat = patResolver.resolve(repo);
        RepoRef repoRef = parseRepoRef(repo);
        return responseCache.getFileBlobLines(
                pat,
                repoRef,
                path,
                sha,
                () -> gitHub.fetchFileBlobLines(pat, repoRef, path, sha));
    }

    /**
     * Posts a general-purpose issue comment on the PR, optionally closing the
     * PR afterwards (matches github.com's "Comment" / "Close with comment"
     * buttons). Empty body + {@code close=true} just closes.
     */
    public void commentOnPullRequest(String repo, int number, long prId, String body, boolean close)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        if (body != null && !body.isBlank()) {
            PrTimelineEvent created = gitHub.createIssueComment(pat, ref, body);
            // Splice the just-posted comment into the cached snapshot so the
            // next /prs/detail read (including the 10s poll's maxAge fast
            // path) shows it immediately rather than waiting for the cache
            // to age out. When closing we drop the whole cache below, so
            // there's no point patching it first.
            if (!close && created != null) {
                patchCachedDetail(store.findIdByRepoAndNumber(repo, number),
                        cached -> PullRequestDetailPatcher.withTimelineCommentAppended(cached, created));
            }
        }
        if (close) {
            gitHub.updatePullRequest(pat, ref, UpdatePullRequestCommand.close());
            viewStateStore.markReviewed(prId, HandledAction.DISMISSED);
            invalidatePullRequestCaches(repo, number);
        }
    }

    /**
     * Replies to an existing per-line review thread on the PR. {@code rootCommentId}
     * is the GitHub id of the thread root (root or any reply works on
     * GitHub's side, but we always pass the root for clarity).
     *
     * <p>After GitHub accepts the reply we append the returned message to
     * the cached PR detail so the next {@code /prs/detail} read shows the
     * reply (with its real GitHub id) without waiting for a background
     * sync.
     */
    public void replyToReviewThread(String repo, int number, long rootCommentId, String body)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(body, "reply body must not be blank");
        PrReviewThreadMessage created = gitHub.replyToReviewComment(pat, parseRef(repo, number), rootCommentId, body);
        patchCachedDetail(store.findIdByRepoAndNumber(repo, number),
                cached -> PullRequestDetailPatcher.withReviewThreadReplyAppended(cached, created));
    }

    /**
     * Updates the body of a top-level issue / PR comment owned by the
     * authenticated user. GitHub returns 403 for comments authored by
     * someone else; the frontend already gates the affordance on the
     * author check, so a 403 here is purely a defensive backstop.
     *
     * <p>After GitHub accepts the edit we patch the cached detail in
     * place so the next {@code /prs/detail} read shows the new body
     * immediately.
     */
    public void editIssueComment(String repo, long commentId, String body)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(body, "comment body must not be blank");
        RepoRef ref = parseRepoRef(repo);
        gitHub.editIssueComment(pat, ref.owner(), ref.repo(), commentId, body);
        patchCachedDetail(detailStore.findPrIdByIssueCommentId(commentId),
                cached -> PullRequestDetailPatcher.withTimelineCommentBody(cached, commentId, body));
    }

    /**
     * Updates the body of a per-line review comment owned by the
     * authenticated user. Same author-gating story as
     * {@link #editIssueComment(String, String, long, String)}.
     *
     * <p>After GitHub accepts the edit we patch the cached detail in
     * place so the next {@code /prs/detail} read shows the new body
     * immediately.
     */
    public void editReviewComment(String repo, long commentId, String body)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(body, "comment body must not be blank");
        RepoRef ref = parseRepoRef(repo);
        gitHub.editReviewComment(pat, ref.owner(), ref.repo(), commentId, body);
        patchCachedDetail(detailStore.findPrIdByReviewCommentId(commentId),
                cached -> PullRequestDetailPatcher.withReviewCommentBody(cached, commentId, body));
    }

    /**
     * Deletes a top-level issue / PR comment. GitHub permits this when
     * the authenticated user owns the comment or holds write access on
     * the repo; the frontend gates the affordance the same way, so a 403
     * here is a defensive backstop.
     *
     * <p>After GitHub accepts the delete we drop the comment from the
     * cached detail so the next {@code /prs/detail} read no longer shows
     * it.
     */
    public void deleteIssueComment(String repo, long commentId)
    {
        String pat = patResolver.resolve(repo);
        RepoRef ref = parseRepoRef(repo);
        gitHub.deleteIssueComment(pat, ref.owner(), ref.repo(), commentId);
        patchCachedDetail(detailStore.findPrIdByIssueCommentId(commentId),
                cached -> PullRequestDetailPatcher.withTimelineCommentRemoved(cached, commentId));
    }

    /**
     * Deletes a per-line review comment. Same permission story and cache
     * handling as {@link #deleteIssueComment(String, long)}.
     */
    public void deleteReviewComment(String repo, long commentId)
    {
        String pat = patResolver.resolve(repo);
        RepoRef ref = parseRepoRef(repo);
        gitHub.deleteReviewComment(pat, ref.owner(), ref.repo(), commentId);
        patchCachedDetail(detailStore.findPrIdByReviewCommentId(commentId),
                cached -> PullRequestDetailPatcher.withReviewCommentRemoved(cached, commentId));
    }

    /**
     * Toggles a review thread's resolved state via GraphQL. The
     * frontend identifies the thread by its REST root comment id; we
     * look up the GraphQL node id from the cached detail (populated by
     * the GraphQL fetch on the previous PR-detail load) before firing
     * the mutation. Throws 404 when the thread isn't in the cache or
     * its node id hasn't been written yet.
     *
     * <p>After GitHub accepts the mutation we patch the cached detail
     * in place so the next {@code /prs/detail} read returns the new
     * resolved flag immediately, instead of the previous "wait for the
     * 30s TTL or the next background sync" behaviour that made
     * unresolve clicks feel like they hadn't taken.
     */
    public void setReviewThreadResolved(String repo, long prId, long rootCommentId, boolean resolved)
    {
        String pat = patResolver.resolve(repo);
        // Prefer the client-supplied prId, but fall back to resolving it from
        // the thread's own root comment id. Unified-PR surfaces don't carry
        // the legacy pull_requests id the client-passed prId assumes, so
        // without this the cache lookup 404s for any PR opened outside the
        // legacy dashboard path.
        long detailPrId = detailStore.find(prId).isPresent()
                ? prId
                : detailStore.findPrIdByReviewCommentId(rootCommentId).orElse(prId);
        StoredPrDetail cached = detailStore.find(detailPrId).orElse(null);
        String nodeId = cached == null ? null : cached.reviewComments().stream()
                .filter(m -> m.githubId() == rootCommentId && m.graphqlNodeId() != null)
                .findFirst()
                .map(PrReviewThreadMessage::graphqlNodeId)
                .orElse(null);
        if (nodeId == null) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404),
                    "review thread " + rootCommentId + " has no GraphQL node id yet — refresh the PR detail and try again");
        }
        if (resolved) {
            gitHub.resolveReviewThread(pat, nodeId);
        }
        else {
            gitHub.unresolveReviewThread(pat, nodeId);
        }
        detailStore.save(detailPrId, PullRequestDetailPatcher.withReviewThreadResolved(cached, rootCommentId, resolved));
    }

    /** Adds an emoji reaction to the PR description. */
    public void addPullRequestReaction(String repo, int number, String content)
    {
        String pat = patResolver.resolve(repo);
        requireAllowedReactionContent(content);
        gitHub.addPullRequestReaction(pat, parseRef(repo, number), content);
    }

    /**
     * Adds an emoji reaction to a per-line review comment. {@code content}
     * is GitHub's reaction-content string ("+1", "heart", "rocket", …).
     * Idempotent on GitHub — re-adding the same reaction returns 200 OK
     * with the existing reaction id.
     *
     * <p>After GitHub accepts the reaction we bump the matching
     * reaction count on the cached review-thread message so the next
     * {@code /prs/detail} read shows the new chip without waiting for
     * the next background sync. Re-clicks bump the cache count again
     * even though GitHub stays at +1; the next full sync reconciles.
     */
    public void addReviewCommentReaction(String repo, long commentId, String content)
    {
        String pat = patResolver.resolve(repo);
        requireAllowedReactionContent(content);
        // The reactions endpoint targets a repo + comment id; PR number
        // isn't part of the URL. parseRepoRef avoids parseRef's
        // number-must-be-positive invariant.
        RepoRef ref = parseRepoRef(repo);
        gitHub.addReviewCommentReaction(pat, ref.owner(), ref.repo(), commentId, content);
        patchCachedDetail(detailStore.findPrIdByReviewCommentId(commentId),
                cached -> PullRequestDetailPatcher.withReviewCommentReaction(cached, commentId, content));
    }

    /**
     * Adds an emoji reaction to a top-level issue / PR comment (the
     * "commented" timeline events). Same content-allowlist + path
     * shape as the review-comment variant — only the GitHub URL
     * differs (issues/comments vs pulls/comments).
     *
     * <p>After GitHub accepts the reaction we bump the matching tally
     * on the cached timeline event in place, mirroring the
     * review-comment patch path so the next {@code /prs/detail} read
     * shows the new chip without waiting for a background sync.
     */
    public void addIssueCommentReaction(String repo, long commentId, String content)
    {
        String pat = patResolver.resolve(repo);
        requireAllowedReactionContent(content);
        RepoRef ref = parseRepoRef(repo);
        gitHub.addIssueCommentReaction(pat, ref.owner(), ref.repo(), commentId, content);
        patchCachedDetail(detailStore.findPrIdByIssueCommentId(commentId),
                cached -> PullRequestDetailPatcher.withTimelineCommentReaction(cached, commentId, content));
    }

    /**
     * Adds one user to the PR's requested reviewers. Drops the cached
     * detail so the next fetch reflects the updated reviewer set + the
     * synthetic review_requested timeline event GitHub emits.
     */
    public void addRequestedReviewer(String repo, int number, String reviewer)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(reviewer, "reviewer must not be blank");
        gitHub.requestReviewers(
                pat,
                parseRef(repo, number),
                new RequestReviewersCommand(ImmutableList.of(reviewer.trim()), ImmutableList.of()));
        invalidatePullRequestCaches(repo, number);
    }

    /** Removes one user from the PR's requested reviewers. */
    public void removeRequestedReviewer(String repo, int number, String reviewer)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(reviewer, "reviewer must not be blank");
        gitHub.removeRequestedReviewers(
                pat,
                parseRef(repo, number),
                new RequestReviewersCommand(ImmutableList.of(reviewer.trim()), ImmutableList.of()));
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * GitHub's suggested reviewers for one PR — the same chips github.com
     * surfaces in its conversation-page reviewers picker. GraphQL-only;
     * empty list on auth/network failure since this is a non-essential
     * affordance and shouldn't block the rest of the reviewers panel.
     */
    public List<SuggestedReviewer> getSuggestedReviewers(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        return responseCache.getSuggestedReviewers(
                pat,
                ref,
                () -> gitHub.fetchSuggestedReviewers(pat, ref));
    }

    public record MetadataChoices(
            List<GitHubUserMatch> users,
            List<IssueDetail.Label> labels,
            List<String> assignees,
            List<String> selectedLabels) {}

    /** Current selections plus the repository choices used by the three metadata pickers. */
    public MetadataChoices getMetadataChoices(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        IssueDetail issue = gitHub.fetchIssueDetail(pat, ref.repoRef(), number);
        Optional<RepoMetadataCacheStore.Snapshot> cached = repoMetadataCache.find(ref.repoFullName());
        RepoMetadataCacheStore.Snapshot choices = cached
                .filter(snapshot -> !snapshot.fetchedAt().isBefore(Instant.now().minus(REPO_METADATA_TTL)))
                .orElseGet(() -> refreshMetadataChoices(pat, ref, cached));
        return new MetadataChoices(
                choices.users(),
                choices.labels(),
                issue.assignees().stream().map(IssueDetail.Assignee::login).toList(),
                issue.labels().stream().map(IssueDetail.Label::name).toList());
    }

    private RepoMetadataCacheStore.Snapshot refreshMetadataChoices(
            String pat,
            PullRequestRef ref,
            Optional<RepoMetadataCacheStore.Snapshot> stale)
    {
        List<GitHubUserMatch> users = gitHub.fetchAssignableUsers(pat, ref.repoRef());
        List<IssueDetail.Label> labels = gitHub.fetchRepoLabels(pat, ref.repoRef());
        if (users.isEmpty() && stale.isPresent()) {
            // ponytail: the shared paginator represents an upstream failure as
            // an empty list; preserve the last useful DB snapshot rather than
            // replacing it with a transient outage.
            return stale.get();
        }
        if (labels.isEmpty() && stale.isPresent() && !stale.get().labels().isEmpty()) {
            labels = stale.get().labels();
        }
        Instant fetchedAt = Instant.now();
        repoMetadataCache.save(ref.repoFullName(), users, labels, fetchedAt);
        return new RepoMetadataCacheStore.Snapshot(users, labels, fetchedAt);
    }

    public void setPullRequestAssignee(String repo, int number, String login, boolean selected)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(login, "assignee must not be blank");
        gitHub.setPullRequestAssignee(pat, parseRef(repo, number), login.trim(), selected);
        invalidatePullRequestCaches(repo, number);
    }

    public void setPullRequestLabel(String repo, int number, String label, boolean selected)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(label, "label must not be blank");
        gitHub.setPullRequestLabel(pat, parseRef(repo, number), label.trim(), selected);
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Posts a single per-line review comment on a diff line, mirroring
     * GitHub's "Add single comment" action. {@code commitId} should be the
     * PR head SHA at the time the user clicked the line — caller resolves it
     * client-side. Drops the cached detail so the next detail fetch picks
     * up the new thread.
     */
    public void createInlineReviewComment(
            String repo,
            int number,
            String body,
            String path,
            int line,
            String side,
            String commitId,
            Integer startLine,
            String startSide)
    {
        String pat = patResolver.resolve(repo);
        requireNotBlank(body, "comment body must not be blank");
        requireNotBlank(path, "path must not be blank");
        requireNotBlank(commitId, "commitId must not be blank");

        String resolvedSide = DiffSide.normalize(side);
        // Multi-line range: validate startLine sits before line on the
        // same side. We accept null startSide and default it to the end
        // side, matching GitHub's UX.
        Integer resolvedStartLine = null;
        String resolvedStartSide = null;
        if (startLine != null && startLine != line) {
            if (startLine > line) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                        "startLine must be ≤ line for a multi-line comment");
            }
            resolvedStartLine = startLine;
            resolvedStartSide = DiffSide.normalizeOptional(startSide, resolvedSide);
        }
        gitHub.createInlineReviewComment(pat, parseRef(repo, number),
                body, path, line, resolvedSide, commitId,
                resolvedStartLine, resolvedStartSide);
        invalidatePullRequestDetail(repo, number);
    }

    /**
     * Updates the PR's description (body) on GitHub. GitHub only lets the PR
     * author edit this; attempts from anyone else come back as 422 / 403
     * which the client surfaces as an error.
     */
    public void updatePullRequestBody(String repo, int number, String body)
    {
        String pat = patResolver.resolve(repo);
        gitHub.updatePullRequest(
                pat,
                parseRef(repo, number),
                new UpdatePullRequestCommand(
                        Optional.empty(),
                        Optional.ofNullable(body),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        // Drop the cached detail so the next `GET /prs/detail` refetches and
        // reflects the new body. Simpler and less error-prone than patching
        // the cache in place.
        invalidatePullRequestDetail(repo, number);
    }

    /**
     * Submits an approval review for the given pull request on GitHub and records a local reviewed state.
     */
    public void approvePullRequest(String repo, int number, long prId)
    {
        submitApproval(repo, number);
        viewStateStore.markReviewed(prId, HandledAction.APPROVED);
    }

    /** GitHub-only half of {@link #approvePullRequest} — the unified
     *  dashboard's {@code POST /api/prs/{id}/approve} calls this directly
     *  and records its own triage state via {@code PRService.markHandled}
     *  instead of the legacy {@code pr_view_state} write. */
    public void submitApproval(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        gitHub.createReview(pat, parseRef(repo, number), CreateReviewCommand.approve(""));
        // Drop the cached detail so the next /prs/detail call re-pulls the
        // timeline and the new "reviewed APPROVED" event shows up in the
        // conversation immediately. Without this the user waits for the
        // next background sync (~2 min) to see their own approval land.
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Merges the given pull request on GitHub using the requested strategy
     * and records a local reviewed state. {@code strategy} is one of
     * {@code "rebase"}, {@code "squash"}, or {@code "merge"}; null /
     * unknown values fall back to {@code "rebase"} (the historical
     * default).
     *
     * <p>Dispatches based on whether the target branch has merge queue
     * enabled (one GraphQL probe per call):
     * <ul>
     *   <li>Queue absent → REST {@code PUT .../merge} as before.</li>
     *   <li>Queue present → GraphQL {@code enqueuePullRequest}. The
     *       caller's strategy is ignored — the queue's configured method
     *       wins. The PR isn't merged yet, so we don't mark it reviewed
     *       (a queue entry can still fail required checks and bounce);
     *       the next background sync will pick up the actual merge when
     *       it lands.</li>
     * </ul>
     *
     * <p>If the probe itself fails (network blip, GraphQL outage) we
     * fall through to the direct REST merge — that's the historical
     * behavior and worst-case the user just gets the underlying error
     * back instead of an unrelated probe-failure error.
     */
    public MergeResult mergePullRequest(String repo, int number, long prId, String strategy)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        Optional<PullRequestRepository.MergeQueueProbe> probe;
        try {
            probe = gitHub.probeMergeQueue(pat, ref);
        }
        catch (Exception e) {
            log.debug("Merge queue probe failed for {}#{}, falling back to direct merge: {}",
                    repo, number, e.getMessage());
            probe = Optional.empty();
        }
        if (probe.isPresent()) {
            MergeResult queued = gitHub.enqueuePullRequest(pat, probe.get().pullRequestNodeId());
            // Drop the cached detail so the next /prs/detail call reflects
            // the new "queued" state without waiting for the background
            // sync. Don't markReviewed — the merge hasn't actually happened.
            invalidatePullRequestCaches(repo, number);
            return queued;
        }
        MergePullRequestCommand command = strategyCommand(strategy);
        MergeResult result;
        try {
            result = gitHub.mergePullRequest(pat, ref, command);
        }
        catch (ResponseStatusException e) {
            // Rulesets can require the merge queue without exposing it to the
            // probe above (GraphQL's pullRequest.mergeQueue is null for
            // ruleset-driven queues). GitHub then 405s the direct merge —
            // recover by enqueueing instead.
            if (requiresMergeQueue(e)) {
                Optional<String> nodeId = gitHub.pullRequestNodeId(pat, ref);
                if (nodeId.isPresent()) {
                    MergeResult queued = gitHub.enqueuePullRequest(pat, nodeId.get());
                    invalidatePullRequestCaches(repo, number);
                    return queued;
                }
            }
            throw e;
        }
        viewStateStore.markReviewed(prId, HandledAction.MERGED);
        // The PR actually landed (not just queued) — let a shipped task
        // that owns this PR advance from IN_REVIEW to COMPLETED.
        eventPublisher.publishEvent(new PullRequestMergedEvent(repo, number));
        // Drop the cached detail so the next /prs/detail call re-pulls the
        // timeline and the new "merged" / "closed" events surface
        // immediately, instead of waiting for the next background sync
        // (~2 min) to refresh the cached StoredPrDetail.
        invalidatePullRequestCaches(repo, number);
        return result;
    }

    /**
     * Add a PR to its repo's merge queue without ever attempting a direct
     * merge — used for the automatic re-enqueue after a merge-queue bounce
     * (the user's merge consent already stands). Resolves the PR's GraphQL
     * node id via the queue probe, falling back to a plain node-id lookup for
     * ruleset-driven queues. Returns true when the enqueue call succeeded.
     */
    public boolean enqueueForMerge(String repo, int number)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        Optional<PullRequestRepository.MergeQueueProbe> probe;
        try {
            probe = gitHub.probeMergeQueue(pat, ref);
        }
        catch (RuntimeException e) {
            probe = Optional.empty();
        }
        String nodeId = probe.map(PullRequestRepository.MergeQueueProbe::pullRequestNodeId)
                .orElseGet(() -> gitHub.pullRequestNodeId(pat, ref).orElse(null));
        if (nodeId == null) {
            return false;
        }
        try {
            gitHub.enqueuePullRequest(pat, nodeId);
            invalidatePullRequestCaches(repo, number);
            return true;
        }
        catch (RuntimeException e) {
            log.warn("auto re-enqueue of {}#{} failed: {}", repo, number, e.getMessage());
            return false;
        }
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

    /**
     * Enables auto-merge for the PR — GitHub will merge it automatically once
     * required checks pass and approvals are in place. Mirrors github.com's
     * "Merge when ready" button. Goes through GraphQL; REST has no
     * equivalent. The detail cache is dropped so the next /prs/detail call
     * reflects the new state instead of waiting for the background sync.
     */
    public void enableAutoMerge(String repo, int number, long prId, String strategy)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        gitHub.enableAutoMerge(pat, ref, autoMergeGraphqlEnum(strategy));
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Cancels a previously-enabled auto-merge. Idempotent on GitHub's side
     * — a no-op when auto-merge isn't enabled. Detail cache is invalidated
     * for the same reason as {@link #enableAutoMerge}.
     */
    public void disableAutoMerge(String repo, int number, long prId)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        gitHub.disableAutoMerge(pat, ref);
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Removes the PR from its repo's merge queue. Mirrors github.com's
     * "Remove from queue" button. Cache is invalidated so the next
     * detail fetch reflects the PR's new state (mergeQueueState cleared,
     * mergeable_state typically flipping back to "blocked").
     */
    public void dequeuePullRequest(String repo, int number, long prId)
    {
        String pat = patResolver.resolve(repo);
        PullRequestRef ref = parseRef(repo, number);
        gitHub.dequeuePullRequest(pat, ref);
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Maps the wire-level "rebase" / "squash" / "merge" strategy strings
     * (used by /prs/merge for parity with the dropdown) onto GraphQL's
     * PullRequestMergeMethod enum values (REBASE / SQUASH / MERGE).
     */
    private static String autoMergeGraphqlEnum(String strategy)
    {
        if (strategy == null) {
            return "REBASE";
        }
        return switch (strategy.toLowerCase(Locale.ROOT)) {
            case "squash" -> "SQUASH";
            case "merge" -> "MERGE";
            default -> "REBASE";
        };
    }

    /**
     * Marks a PR as handled with the given action, without calling any GitHub API.
     * Used by the hover "Handled" button on the card.
     */
    public void markHandled(long prId, HandledAction action)
    {
        viewStateStore.markReviewed(prId, action);
    }

    /**
     * Clears the local reviewed timestamp so the PR returns to the Inbox.
     */
    public void reopen(long prId)
    {
        viewStateStore.reopen(prId);
    }

    /**
     * Park a PR until {@code until}. The PR is hidden from the Inbox /
     * kanban / sidebar lists until that time, until the user explicitly
     * wakes it, or until the auto-wake check trips an urgent condition
     * (CI failing, changes requested, merge conflict).
     */
    public void snooze(long prId, Instant until)
    {
        if (until == null || !until.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Snooze target must be in the future.");
        }
        viewStateStore.snooze(prId, until);
    }

    /** User-initiated wake. No alert banner. */
    public void unsnooze(long prId)
    {
        viewStateStore.unsnooze(prId, null);
    }

    /** Drop the wake-reason flag once the user has seen the just-woke alert. */
    public void clearSnoozeWakeReason(long prId)
    {
        viewStateStore.clearWakeReason(prId);
    }

    /**
     * Live GitHub search for closed (merged + closed-without-merge) PRs the
     * user authored. Powers the "View full merge history" page — the local
     * sync only persists the last 7 days of closed PRs so a real history
     * view has to hit GitHub directly. Sorted server-side by closed date
     * descending.
     *
     * @param page    1-based page index
     * @param perPage per page count (clamped to [1, 100] by the client)
     */
    public PullRequestHistoryPage searchAuthoredHistory(int page, int perPage)
    {
        String pat = patResolver.resolve();
        return gitHub.searchPullRequestsPaged(
                pat, "is:pr is:closed author:@me sort:closed-desc", page, perPage);
    }

    /**
     * Walk every PR with a non-null {@code snoozedUntil} and decide
     * whether to wake it. The four wake conditions:
     *
     *   1. Time elapsed.
     *   2. CI started failing after the snooze was set.
     *   3. A reviewer requested changes after the snooze was set.
     *   4. A merge conflict appeared after the snooze was set.
     *
     * Each wake records a reason so the frontend can surface a
     * just-woke alert. Called from {@link #syncFromGitHub} after the
     * detail-sync passes have updated the rows we need to inspect.
     */
    private void runAutoWakeCheck()
    {
        Instant now = Instant.now();
        Map<Long, PrViewState> states = viewStateStore.findAll();
        for (Map.Entry<Long, PrViewState> entry : states.entrySet()) {
            PrViewState state = entry.getValue();
            if (state.snoozedUntil() == null) {
                continue;
            }
            if (!state.snoozedUntil().isAfter(now)) {
                viewStateStore.unsnooze(state.prId(), "TIME");
                continue;
            }
            // Pull the PR row to inspect its current urgent signals.
            // Skip if the PR is no longer in the local store (was
            // unwatched / merged-and-removed since the snooze landed).
            Optional<PullRequest> prOpt = store.findById(state.prId());
            if (prOpt.isEmpty()) {
                continue;
            }
            PullRequest pr = prOpt.get();
            String reason = autoWakeReason(pr, state);
            if (reason != null) {
                viewStateStore.unsnooze(state.prId(), reason);
            }
        }
    }

    /**
     * Returns the wake reason if the PR has tripped an auto-wake
     * condition since the snooze was set, or null if no condition has
     * fired. Order matters: CI > CHANGES_REQUESTED > MERGE_CONFLICT
     * (most-blocking first, matches the focus-band picking order).
     */
    private static String autoWakeReason(PullRequest pr, PrViewState state)
    {
        // CI started failing while snoozed.
        if (pr.ciStatus() == PullRequestDetail.CiStatus.FAILING) {
            return "CI_FAILING";
        }
        // A reviewer requested changes after the snooze landed. We
        // can't tell *when* the verdict was set without an extra
        // timestamp, so we treat any current CHANGES_REQUESTED as a
        // wake — false positives are rare (the user just snoozed; if
        // the verdict was already there they'd have seen it). Cheap
        // pragmatic check until we wire per-verdict timestamps.
        Map<String, String> verdicts = pr.reviewerVerdicts();
        if (verdicts != null) {
            for (String v : verdicts.values()) {
                if (GithubReviewState.CHANGES_REQUESTED.equals(v)) {
                    return GithubReviewState.CHANGES_REQUESTED;
                }
            }
        }
        // Merge conflict — only flag if the snooze was set BEFORE the
        // PR's last update (otherwise the user snoozed it knowing
        // about the conflict).
        boolean conflict = Boolean.FALSE.equals(pr.mergeable())
                || "MERGE_CONFLICT".equals(String.valueOf(pr.attentionReason()));
        if (conflict
                && state.snoozedAt() != null
                && pr.updatedAt() != null
                && pr.updatedAt().isAfter(state.snoozedAt())) {
            return "MERGE_CONFLICT";
        }
        return null;
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
        // Recently-closed authored PRs feed the kanban's "Recently merged"
        // column. Without this the moment a PR is merged on GitHub the
        // is:open search drops it, store.replaceAll(...) deletes the row,
        // and the column stays empty. 7-day window matches
        // categorizeMyPr's recently_merged horizon exactly so we don't
        // pull rows the kanban would discard anyway.
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString();
        CompletableFuture<List<PullRequest>> recentlyClosedFuture = CompletableFuture.supplyAsync(
                () -> gitHub.searchPullRequests(pat,
                        "is:pr is:closed author:@me closed:>=" + sinceDate),
                executor);

        List<PullRequest> authored = join(authoredFuture);
        List<PullRequest> reviewRequested = join(reviewFuture);
        List<PullRequest> reviewedBy = join(reviewedByFuture);
        List<PullRequest> recentlyClosed = join(recentlyClosedFuture);

        LinkedHashMap<String, PullRequest> merged = Maps.newLinkedHashMap();
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
        return ImmutableList.copyOf(merged.values());
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
        ImmutableList.Builder<PullRequest> all = ImmutableList.builder();
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
        return all.build();
    }

    private void syncDetailQuietly(String pat, PullRequest pr, String currentLogin)
    {
        try {
            PullRequestRef ref = parseRef(pr.repo(), pr.number());
            // Repo-scoped fetch: prefer a per-repo PAT if one is configured.
            // Existing detail in the store ⇒ this is an incremental refresh
            // (the detail fetcher sees the watermark and uses `since=`);
            // route through saveIncremental so old timeline/thread rows
            // survive and only the new ones get appended. First-time syncs
            // hit the regular wholesale save() path.
            boolean incremental = detailStore.findSyncedAt(pr.repo(), pr.number()).isPresent();
            StoredPrDetail detail = detailFetcher.fetch(patForRepo(pat, pr.repo()), ref);
            if (incremental) {
                detailStore.saveIncremental(pr.id(), detail);
            }
            else {
                detailStore.save(pr.id(), detail);
            }
            // One viewState lookup feeds both reconcile (handledAction) and
            // the MENTIONED rule (viewedAt anchors which mentions are "new").
            PrViewState viewState = viewStateStore.findAll().get(pr.id());
            reconcileHandledActionFromReviews(pr.id(), detail.reviews(), currentLogin, viewState);
            Instant viewedAt = viewState != null ? viewState.viewedAt() : null;
            // Refresh the list-level enrichment columns so cards can render
            // ciStatus / diff / comment count / attention banner / kanban
            // signals without re-loading the full detail blob.
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
        detailInvalidator.invalidate(repo, number);
    }

    private static void requireAllowedReactionContent(String content)
    {
        if (!PullRequestDetailPatcher.ALLOWED_REACTION_CONTENT.contains(content)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "reaction content must be one of " + PullRequestDetailPatcher.ALLOWED_REACTION_CONTENT);
        }
    }

    private void invalidatePullRequestCaches(String repo, int number)
    {
        invalidatePullRequestDetail(repo, number);
        repoListCache.invalidatePulls(parseRepoRef(repo));
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
                pr.snoozedUntil(),
                pr.snoozeWakeReason(),
                pr.headRef());
    }

    private static <T> T join(CompletableFuture<T> future)
    {
        return future.join();
    }
}
