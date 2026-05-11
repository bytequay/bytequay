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
import com.bytequay.app.domain.HandledAction;
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
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.RepoListCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String RIGHT = "RIGHT";

    private static final Set<String> INTERESTING_EVENTS = ImmutableSet.of(
            "committed", "reviewed", "review_requested", "commented", "merged", "closed", "reopened",
            "head_ref_force_pushed", "added_to_merge_queue", "removed_from_merge_queue");

    private final PullRequestRepository gitHub;
    private final PullRequestStore store;
    private final PrDetailStore detailStore;
    private final PrViewStateStore viewStateStore;
    private final AppSettingsStore settingsStore;
    private final CredentialService credentialService;
    private final GitHubResponseCache responseCache;
    private final PullRequestDetailInvalidator detailInvalidator;
    private final RepoListCache repoListCache;
    private final Executor executor;
    private final Executor ioExecutor;
    /** prId → last ETag returned by GitHub for {@code GET /pulls/{n}}.
     *  Populated by {@link #refreshPullRequestDetail}'s probe path
     *  and consulted on the next probe to short-circuit unchanged
     *  PRs (304 → no rate-limit cost). In-memory only — a backend
     *  restart just means the next probe pays for one full fetch. */
    private final ConcurrentMap<Long, String> detailEtags = new ConcurrentHashMap<>();

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
            @Qualifier(APPLICATION_EXECUTOR) Executor executor,
            @Qualifier(IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.store = requireNonNull(store, "store is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.responseCache = requireNonNull(responseCache, "responseCache is null");
        this.detailInvalidator = requireNonNull(detailInvalidator, "detailInvalidator is null");
        this.repoListCache = requireNonNull(repoListCache, "repoListCache is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
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
     * Fetches the PR list from GitHub, persists it, then refreshes detail for any PR whose
     * {@code updatedAt} changed (or is new). Stale detail for removed PRs is cleaned up.
     * All detail fetches run in parallel.
     */
    public void syncFromGitHub(String pat)
    {
        Map<Long, Instant> existingUpdatedAt = store.findUpdatedAtMap();
        // Rows whose V26 enrichment fields are still null get a forced
        // detail sync below regardless of `updatedAt`. Catches legacy
        // rows whose `updatedAt` hasn't moved since the kanban
        // categorization started reading reviewer_verdicts; without
        // this backfill those rows render as "Opened" forever.
        Set<Long> missingEnrichment = store.findIdsMissingEnrichment();

        // Pull the current user's login once per sync so we can reconcile
        // `handledAction` against reviews submitted outside the app (e.g. via
        // the embedded github.com window or a separate browser tab).
        String currentLogin = resolveCurrentLogin(pat);

        List<PullRequest> fresh = fetchRelevant(pat);
        store.replaceAll(fresh);

        Set<Long> freshIds = fresh.stream()
                .map(PullRequest::id)
                .collect(toImmutableSet());
        Set<Long> removedIds = existingUpdatedAt.keySet().stream()
                .filter(id -> !freshIds.contains(id))
                .collect(toImmutableSet());
        if (!removedIds.isEmpty()) {
            detailStore.deleteByPrIds(removedIds);
        }

        List<CompletableFuture<Void>> detailFutures = fresh.stream()
                .filter(pr -> {
                    Instant existing = existingUpdatedAt.get(pr.id());
                    if (existing == null || !existing.equals(pr.updatedAt())) {
                        return true;
                    }
                    return missingEnrichment.contains(pr.id());
                })
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
    public PullRequestDetail getPullRequestDetail(String pat, String repo, int number)
    {
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
                return assemblePullRequestDetail(repo, number, stored.get(), viewerCanWrite);
            }
        }

        // Cache miss — fetch live, store for next time
        StoredPrDetail fetched = fetchDetailFromGitHub(pat, ref);
        prId.ifPresent(id -> {
            detailStore.save(id, fetched);
            // Propagate the aggregate CI status onto the PR row so the
            // kanban categorizer (prBuckets.ts) picks up a fresh
            // FAILING state without waiting for the next bulk sync.
            // The detail blob has it; the row didn't until this line.
            store.updateCiStatus(id, PrAttention.aggregateCiStatus(fetched));
        });
        return assemblePullRequestDetail(repo, number, fetched, viewerCanWrite);
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
    public PullRequestDetail refreshPullRequestDetail(String pat, String repo, int number)
    {
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        if (prId.isPresent()) {
            try {
                PullRequestRef ref = parseRef(repo, number);
                String cachedEtag = detailEtags.get(prId.get());
                PullRequestRepository.ProbeResult probe =
                        gitHub.probeChangedSinceEtag(pat, ref, cachedEtag);
                // Always capture the latest ETag — including the
                // first-ever probe (no cached ETag → 200 response,
                // body discarded, ETag captured for the next call).
                if (probe.newEtag() != null) {
                    detailEtags.put(prId.get(), probe.newEtag());
                }
                if (cachedEtag != null && !probe.changed()) {
                    // 304: nothing's changed since we last fetched.
                    // Skip the multi-call refetch and serve cached.
                    log.debug("ETag probe 304 for {}#{} — serving cached", repo, number);
                    return getPullRequestDetail(pat, repo, number);
                }
            }
            catch (Exception e) {
                // Probe is best-effort. Fall through to the full
                // refetch — never gate correctness on the probe.
                log.debug("ETag probe failed for {}#{}: {}", repo, number, e.getMessage());
            }
        }
        invalidatePullRequestDetail(repo, number);
        return getPullRequestDetail(pat, repo, number);
    }

    /**
     * Toggles a PR between draft and ready-for-review. Drops the cached
     * detail so the next fetch reflects the new state and the timeline
     * picks up the synthetic "ready for review" / "marked as draft"
     * event GitHub emits.
     */
    public void setPullRequestDraft(String pat, String repo, int number, boolean draft)
    {
        gitHub.setPullRequestDraft(pat, parseRef(repo, number), draft);
        invalidatePullRequestDetail(repo, number);
        repoListCache.invalidatePulls(parseRepoRef(repo));
    }

    /**
     * Returns the raw log text for a single Actions check-run, capped at
     * a sensible size so a 50MB job log doesn't crater the renderer.
     * Empty string when GitHub doesn't expose a log for this check
     * (external CI, expired log, missing PAT scope) — the frontend
     * shows a "log unavailable" hint in that case.
     */
    public String getCheckRunLog(String pat, String repo, long checkRunId)
    {
        PullRequestRef refOrNull = parseRef(repo, 1); // PR number not needed for the call
        return gitHub.fetchCheckRunLog(pat, RepoRef.of(refOrNull.owner(), refOrNull.repo()), checkRunId)
                .map(PullRequestService::trimLogToTail)
                .orElse("");
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
    public PrCiSnapshot getPullRequestCiSnapshot(String pat, String repo, int number)
    {
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
        PullRequestDetail.CiStatus aggregate = aggregateCiStatus(runs);
        // Propagate the freshly-computed aggregate onto the PR row so
        // a click on the merge bar's ↻ refresh also re-routes the
        // kanban card (categorizeMyPr / categorizeToReview both read
        // ciStatus from the row, not the detail blob).
        store.findIdByRepoAndNumber(repo, number).ifPresent(id ->
                store.updateCiStatus(id, aggregate));
        return new PrCiSnapshot(
                aggregate,
                toCheckRuns(runs),
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
     * Fetches the list of files changed in a pull request along with their
     * unified-diff patches. Always served fresh from GitHub — patches are not
     * cached (too large + rarely re-read).
     */
    public List<DiffFile> getPullRequestDiffFiles(String pat, String repo, int number)
    {
        return gitHub.fetchPrDiffFiles(pat, parseRef(repo, number));
    }

    /**
     * Fetches the commits in a pull request, oldest first. Also served fresh —
     * commit metadata is small and the sync job doesn't currently retain it.
     */
    public List<PullRequestCommit> getPullRequestCommits(String pat, String repo, int number)
    {
        return gitHub.fetchPrCommits(pat, parseRef(repo, number));
    }

    /**
     * Returns the diff scoped to a single commit — same DiffFile shape as
     * {@link #getPullRequestDiffFiles}, but only the changes that this one
     * sha introduced. Backs the "select a commit" affordance in the diff
     * viewer for PRs with many commits.
     */
    public List<DiffFile> getCommitDiffFiles(String pat, String repo, int number, String sha)
    {
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
    public List<String> getFileBlobLines(String pat, String repo, String path, String sha)
    {
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
    public void commentOnPullRequest(String pat, String repo, int number, long prId, String body, boolean close)
    {
        PullRequestRef ref = parseRef(repo, number);
        if (body != null && !body.isBlank()) {
            gitHub.createIssueComment(pat, ref, body);
        }
        if (close) {
            gitHub.updatePullRequest(pat, ref, UpdatePullRequestCommand.close());
            viewStateStore.markReviewed(prId, HandledAction.DISMISSED);
            invalidatePullRequestDetail(repo, number);
            repoListCache.invalidatePulls(parseRepoRef(repo));
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
    public void replyToReviewThread(String pat, String repo, int number, long rootCommentId, String body)
    {
        requireNotBlank(body, "reply body must not be blank");
        PrReviewThreadMessage created = gitHub.replyToReviewComment(pat, parseRef(repo, number), rootCommentId, body);
        store.findIdByRepoAndNumber(repo, number).ifPresent(prId ->
                detailStore.find(prId).ifPresent(cached ->
                        detailStore.save(prId, withReviewThreadReplyAppended(cached, created))));
    }

    /**
     * Returns a new {@link StoredPrDetail} with {@code reply} appended to
     * {@code reviewComments}. No-op de-dup beyond the GitHub id check the
     * SQLite save layer already performs — the caller is expected to pass
     * a fresh reply.
     */
    private static StoredPrDetail withReviewThreadReplyAppended(StoredPrDetail detail, PrReviewThreadMessage reply)
    {
        List<PrReviewThreadMessage> patched = ImmutableList.<PrReviewThreadMessage>builder()
                .addAll(detail.reviewComments())
                .add(reply)
                .build();
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues());
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
    public void editIssueComment(String pat, String repo, long commentId, String body)
    {
        requireNotBlank(body, "comment body must not be blank");
        RepoRef ref = parseRepoRef(repo);
        gitHub.editIssueComment(pat, ref.owner(), ref.repo(), commentId, body);
        detailStore.findPrIdByIssueCommentId(commentId).ifPresent(prId ->
                detailStore.find(prId).ifPresent(cached ->
                        detailStore.save(prId, withTimelineCommentBody(cached, commentId, body))));
    }

    /**
     * Returns a new {@link StoredPrDetail} with the body of the
     * {@code commented} timeline event identified by {@code commentId}
     * replaced. Other rows pass through unchanged.
     */
    private static StoredPrDetail withTimelineCommentBody(StoredPrDetail detail, long commentId, String body)
    {
        List<PrTimelineEvent> patched = detail.timeline().stream()
                .map(e -> e.githubId() != null && e.githubId() == commentId && "commented".equals(e.event())
                        ? new PrTimelineEvent(
                                e.githubId(), e.event(), e.actor(), e.state(), e.timestamp(), body,
                                e.beforeSha(), e.afterSha(), e.requestedReviewer(), e.reviewId(),
                                e.authorAssociation(), e.reactions())
                        : e)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), patched,
                detail.checkRuns(), detail.reviewComments(), detail.linkedIssues());
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
    public void editReviewComment(String pat, String repo, long commentId, String body)
    {
        requireNotBlank(body, "comment body must not be blank");
        RepoRef ref = parseRepoRef(repo);
        gitHub.editReviewComment(pat, ref.owner(), ref.repo(), commentId, body);
        detailStore.findPrIdByReviewCommentId(commentId).ifPresent(prId ->
                detailStore.find(prId).ifPresent(cached ->
                        detailStore.save(prId, withReviewCommentBody(cached, commentId, body))));
    }

    /**
     * Returns a new {@link StoredPrDetail} with the body of the
     * review-thread message identified by {@code commentId} replaced.
     * Other rows pass through unchanged.
     */
    private static StoredPrDetail withReviewCommentBody(StoredPrDetail detail, long commentId, String body)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .map(m -> m.githubId() == commentId
                        ? new PrReviewThreadMessage(
                                m.githubId(), m.inReplyTo(), m.reviewId(), m.author(), body,
                                m.filePath(), m.lineNumber(), m.side(), m.diffHunk(), m.commitId(),
                                m.createdAt(), m.reactions(), m.outdated(), m.startLine(), m.startSide(),
                                m.originalLine(), m.originalStartLine(), m.authorAssociation(),
                                m.graphqlNodeId(), m.resolved())
                        : m)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues());
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
    public void setReviewThreadResolved(String pat, long prId, long rootCommentId, boolean resolved)
    {
        StoredPrDetail cached = detailStore.find(prId).orElse(null);
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
        detailStore.save(prId, withReviewThreadResolved(cached, rootCommentId, resolved));
    }

    /**
     * Returns a new {@link StoredPrDetail} with the {@code resolved} flag
     * on the thread root identified by {@code rootCommentId} replaced.
     * Other rows pass through unchanged.
     */
    private static StoredPrDetail withReviewThreadResolved(StoredPrDetail detail, long rootCommentId, boolean resolved)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .map(m -> m.githubId() == rootCommentId
                        ? new PrReviewThreadMessage(
                                m.githubId(), m.inReplyTo(), m.reviewId(), m.author(), m.body(),
                                m.filePath(), m.lineNumber(), m.side(), m.diffHunk(), m.commitId(),
                                m.createdAt(), m.reactions(), m.outdated(), m.startLine(), m.startSide(),
                                m.originalLine(), m.originalStartLine(), m.authorAssociation(),
                                m.graphqlNodeId(), resolved)
                        : m)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues());
    }

    private static final Set<String> ALLOWED_REACTION_CONTENT = Set.of(
            "+1", "-1", "laugh", "confused", "heart", "hooray", "rocket", "eyes");

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
    public void addReviewCommentReaction(String pat, String repo, long commentId, String content)
    {
        if (!ALLOWED_REACTION_CONTENT.contains(content)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "reaction content must be one of " + ALLOWED_REACTION_CONTENT);
        }
        // The reactions endpoint targets a repo + comment id; PR number
        // isn't part of the URL. parseRepoRef avoids parseRef's
        // number-must-be-positive invariant.
        RepoRef ref = parseRepoRef(repo);
        gitHub.addReviewCommentReaction(pat, ref.owner(), ref.repo(), commentId, content);
        detailStore.findPrIdByReviewCommentId(commentId).ifPresent(prId ->
                detailStore.find(prId).ifPresent(cached ->
                        detailStore.save(prId, withReviewCommentReaction(cached, commentId, content))));
    }

    /**
     * Returns a new {@link StoredPrDetail} with the matching review-thread
     * message's reaction tally for {@code content} bumped by one. Other
     * rows pass through unchanged.
     */
    private static StoredPrDetail withReviewCommentReaction(StoredPrDetail detail, long commentId, String content)
    {
        List<PrReviewThreadMessage> patched = detail.reviewComments().stream()
                .map(m -> m.githubId() == commentId
                        ? new PrReviewThreadMessage(
                                m.githubId(), m.inReplyTo(), m.reviewId(), m.author(), m.body(),
                                m.filePath(), m.lineNumber(), m.side(), m.diffHunk(), m.commitId(),
                                m.createdAt(), bumpReaction(m.reactions(), content), m.outdated(),
                                m.startLine(), m.startSide(), m.originalLine(), m.originalStartLine(),
                                m.authorAssociation(), m.graphqlNodeId(), m.resolved())
                        : m)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), detail.timeline(),
                detail.checkRuns(), patched, detail.linkedIssues());
    }

    /**
     * Returns a copy of {@code reactions} (or {@link Reactions#EMPTY} if
     * null) with the count for {@code content} incremented by one. Any
     * unrecognised content returns the input unchanged — the
     * controller-side allowlist guarantees we never get there in
     * practice, but we don't want a typo to corrupt a valid count.
     */
    private static Reactions bumpReaction(Reactions reactions, String content)
    {
        Reactions base = reactions == null ? Reactions.EMPTY : reactions;
        return switch (content) {
            case "+1" -> new Reactions(base.plusOne() + 1, base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "-1" -> new Reactions(base.plusOne(), base.minusOne() + 1, base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "laugh" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh() + 1, base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "hooray" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray() + 1,
                    base.confused(), base.heart(), base.rocket(), base.eyes());
            case "confused" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused() + 1, base.heart(), base.rocket(), base.eyes());
            case "heart" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart() + 1, base.rocket(), base.eyes());
            case "rocket" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket() + 1, base.eyes());
            case "eyes" -> new Reactions(base.plusOne(), base.minusOne(), base.laugh(), base.hooray(),
                    base.confused(), base.heart(), base.rocket(), base.eyes() + 1);
            default -> base;
        };
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
    public void addIssueCommentReaction(String pat, String repo, long commentId, String content)
    {
        if (!ALLOWED_REACTION_CONTENT.contains(content)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "reaction content must be one of " + ALLOWED_REACTION_CONTENT);
        }
        RepoRef ref = parseRepoRef(repo);
        gitHub.addIssueCommentReaction(pat, ref.owner(), ref.repo(), commentId, content);
        detailStore.findPrIdByIssueCommentId(commentId).ifPresent(prId ->
                detailStore.find(prId).ifPresent(cached ->
                        detailStore.save(prId, withTimelineCommentReaction(cached, commentId, content))));
    }

    /**
     * Returns a new {@link StoredPrDetail} with the reaction tally on
     * the {@code commented} timeline event identified by
     * {@code commentId} bumped by one for {@code content}. Other rows
     * pass through unchanged.
     */
    private static StoredPrDetail withTimelineCommentReaction(StoredPrDetail detail, long commentId, String content)
    {
        List<PrTimelineEvent> patched = detail.timeline().stream()
                .map(e -> e.githubId() != null && e.githubId() == commentId && "commented".equals(e.event())
                        ? new PrTimelineEvent(
                                e.githubId(), e.event(), e.actor(), e.state(), e.timestamp(), e.body(),
                                e.beforeSha(), e.afterSha(), e.requestedReviewer(), e.reviewId(),
                                e.authorAssociation(), bumpReaction(e.reactions(), content))
                        : e)
                .collect(toImmutableList());
        return new StoredPrDetail(
                detail.raw(), detail.reviews(), detail.files(), patched,
                detail.checkRuns(), detail.reviewComments(), detail.linkedIssues());
    }

    /**
     * Adds one user to the PR's requested reviewers. Drops the cached
     * detail so the next fetch reflects the updated reviewer set + the
     * synthetic review_requested timeline event GitHub emits.
     */
    public void addRequestedReviewer(String pat, String repo, int number, String reviewer)
    {
        requireNotBlank(reviewer, "reviewer must not be blank");
        gitHub.requestReviewers(
                pat,
                parseRef(repo, number),
                new RequestReviewersCommand(ImmutableList.of(reviewer.trim()), ImmutableList.of()));
        invalidatePullRequestDetail(repo, number);
        repoListCache.invalidatePulls(parseRepoRef(repo));
    }

    /** Removes one user from the PR's requested reviewers. */
    public void removeRequestedReviewer(String pat, String repo, int number, String reviewer)
    {
        requireNotBlank(reviewer, "reviewer must not be blank");
        gitHub.removeRequestedReviewers(
                pat,
                parseRef(repo, number),
                new RequestReviewersCommand(ImmutableList.of(reviewer.trim()), ImmutableList.of()));
        invalidatePullRequestDetail(repo, number);
        repoListCache.invalidatePulls(parseRepoRef(repo));
    }

    /**
     * GitHub's suggested reviewers for one PR — the same chips github.com
     * surfaces in its conversation-page reviewers picker. GraphQL-only;
     * empty list on auth/network failure since this is a non-essential
     * affordance and shouldn't block the rest of the reviewers panel.
     */
    public List<SuggestedReviewer> getSuggestedReviewers(String pat, String repo, int number)
    {
        PullRequestRef ref = parseRef(repo, number);
        return responseCache.getSuggestedReviewers(
                pat,
                ref,
                () -> gitHub.fetchSuggestedReviewers(pat, ref));
    }

    /**
     * Posts a single per-line review comment on a diff line, mirroring
     * GitHub's "Add single comment" action. {@code commitId} should be the
     * PR head SHA at the time the user clicked the line — caller resolves it
     * client-side. Drops the cached detail so the next detail fetch picks
     * up the new thread.
     */
    public void createInlineReviewComment(
            String pat,
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
        requireNotBlank(body, "comment body must not be blank");
        requireNotBlank(path, "path must not be blank");
        requireNotBlank(commitId, "commitId must not be blank");

        String resolvedSide = normalizeSide(side);
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
            resolvedStartSide = normalizeOptionalSide(startSide, resolvedSide);
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
    public void updatePullRequestBody(String pat, String repo, int number, String body)
    {
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
    public void approvePullRequest(String pat, String repo, int number, long prId)
    {
        gitHub.createReview(pat, parseRef(repo, number), CreateReviewCommand.approve(""));
        viewStateStore.markReviewed(prId, HandledAction.APPROVED);
        // Drop the cached detail so the next /prs/detail call re-pulls the
        // timeline and the new "reviewed APPROVED" event shows up in the
        // conversation immediately. Without this the user waits for the
        // next background sync (~2 min) to see their own approval land.
        invalidatePullRequestDetail(repo, number);
        repoListCache.invalidatePulls(parseRepoRef(repo));
    }

    /**
     * Merges the given pull request on GitHub using the requested strategy
     * and records a local reviewed state. {@code strategy} is one of
     * {@code "rebase"}, {@code "squash"}, or {@code "merge"}; null /
     * unknown values fall back to {@code "rebase"} (the historical
     * default).
     */
    public MergeResult mergePullRequest(String pat, String repo, int number, long prId, String strategy)
    {
        MergePullRequestCommand command = strategyCommand(strategy);
        MergeResult result = gitHub.mergePullRequest(pat, parseRef(repo, number), command);
        viewStateStore.markReviewed(prId, HandledAction.MERGED);
        // Drop the cached detail so the next /prs/detail call re-pulls the
        // timeline and the new "merged" / "closed" events surface
        // immediately, instead of waiting for the next background sync
        // (~2 min) to refresh the cached StoredPrDetail.
        invalidatePullRequestDetail(repo, number);
        repoListCache.invalidatePulls(parseRepoRef(repo));
        return result;
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
    public PullRequestHistoryPage searchAuthoredHistory(String pat, int page, int perPage)
    {
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
                if ("CHANGES_REQUESTED".equals(v)) {
                    return "CHANGES_REQUESTED";
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
                () -> gitHub.searchPullRequests(pat, "is:pr is:open author:@me"), executor);
        CompletableFuture<List<PullRequest>> reviewFuture = CompletableFuture.supplyAsync(
                () -> gitHub.searchPullRequests(pat, "is:pr is:open review-requested:@me"), executor);
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
        return ImmutableList.copyOf(merged.values());
    }

    private void syncDetailQuietly(String pat, PullRequest pr, String currentLogin)
    {
        try {
            PullRequestRef ref = PullRequestRef.of(pr.repo().split("/")[0], pr.repo().split("/")[1], pr.number());
            // Repo-scoped fetch: prefer a per-repo PAT if one is configured.
            // Existing detail in the store ⇒ this is an incremental refresh
            // (fetchDetailFromGitHub sees the watermark and uses `since=`);
            // route through saveIncremental so old timeline/thread rows
            // survive and only the new ones get appended. First-time syncs
            // hit the regular wholesale save() path.
            boolean incremental = detailStore.findSyncedAt(pr.repo(), pr.number()).isPresent();
            StoredPrDetail detail = fetchDetailFromGitHub(patForRepo(pat, pr.repo()), ref);
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
                    latestPushAt(detail.timeline()),
                    rolledUpReviewerVerdicts(detail.reviews()),
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
            if ("APPROVED".equals(r.state())) {
                derived = HandledAction.APPROVED;
            }
            else if ("CHANGES_REQUESTED".equals(r.state())) {
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

    private static String normalizeSide(String side)
    {
        return side == null || side.isBlank() ? RIGHT : side.toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptionalSide(String side, String defaultSide)
    {
        return side == null || side.isBlank() ? defaultSide : side.toUpperCase(Locale.ROOT);
    }

    private void invalidatePullRequestDetail(String repo, int number)
    {
        detailInvalidator.invalidate(repo, number);
    }

    private StoredPrDetail fetchDetailFromGitHub(String pat, PullRequestRef ref)
    {
        // The sub-fetches run on `ioExecutor` (virtual threads) — NOT
        // on `executor`. The previous code used the same bounded
        // applicationExecutor for both the parent (sync job per-PR
        // task) and its 6 children (timeline, reviews, files, …),
        // which deadlocked when 4 parents filled the pool and their
        // own children couldn't acquire a thread to run. Virtual
        // threads have no fixed pool size, so the fan-out is safe.
        long t0 = System.nanoTime();
        log.info("fetchDetailFromGitHub start: {}#{}", ref.owner() + "/" + ref.repo(), ref.number());

        // Watermark for incremental sync — the timestamp of our last
        // successful detail fetch. Endpoints that support `since=`
        // (timeline, issue-comments, pulls/comments) only return rows
        // updated after this point, so a quiet PR settles for an empty
        // single-page response per cycle. New PRs (no prior detail)
        // get a null watermark and the original full-fetch path.
        // 30-second safety margin guards against GitHub indexing a
        // freshly-created comment just after our previous wall-clock read.
        String repoFull = ref.owner() + "/" + ref.repo();
        Instant watermark = detailStore.findSyncedAt(repoFull, ref.number())
                .map(t -> t.minusSeconds(30))
                .orElse(null);
        if (watermark != null) {
            log.info("fetchDetailFromGitHub incremental: {}#{} since={}", repoFull, ref.number(), watermark);
        }

        CompletableFuture<PrRawDetail> detailFuture =
                timed("fetchPrDetail", ref, () -> gitHub.fetchPrDetail(pat, ref));
        CompletableFuture<List<PrReviewState>> reviewsFuture =
                timed("fetchPrReviews", ref, () -> gitHub.fetchPrReviews(pat, ref));
        CompletableFuture<List<PullRequestDetail.ChangedFile>> filesFuture =
                timed("fetchPrFiles", ref, () -> gitHub.fetchPrFiles(pat, ref));
        CompletableFuture<List<PrTimelineEvent>> timelineFuture =
                timed("fetchPrTimeline", ref, () -> gitHub.fetchPrTimeline(pat, ref, watermark));
        CompletableFuture<List<PrReviewThreadMessage>> reviewCommentsFuture =
                timed("fetchPrReviewComments", ref, () -> gitHub.fetchPrReviewComments(pat, ref, watermark));
        CompletableFuture<List<PrTimelineEvent>> issueCommentsFuture =
                timed("fetchPrIssueComments", ref, () -> gitHub.fetchPrIssueComments(pat, ref, watermark));
        // GraphQL fetch — review-thread resolution state. REST doesn't
        // expose it. Best-effort: if the GraphQL call fails (rate limit,
        // permission, etc.) we still return the REST data without the
        // resolved flag. Per-thread metadata is joined back via the
        // root comment's databaseId.
        CompletableFuture<List<PullRequestRepository.ReviewThreadMeta>> threadResolutionFuture =
                timed("fetchReviewThreadResolution", ref,
                        () -> {
                            try {
                                return gitHub.fetchReviewThreadResolution(pat, ref);
                            }
                            catch (RuntimeException e) {
                                log.warn("GraphQL review-thread resolution fetch failed: {}", e.getMessage());
                                return ImmutableList.<PullRequestRepository.ReviewThreadMeta>of();
                            }
                        });

        PrRawDetail raw = join(detailFuture);
        List<PrReviewState> reviews = join(reviewsFuture);

        CompletableFuture<List<PrCheckRunState>> checkRunsFuture = raw != null && raw.headSha() != null
                ? timed("fetchPrCheckRuns", ref, () -> gitHub.fetchPrCheckRuns(pat, ref.owner(), ref.repo(), raw.headSha()))
                : CompletableFuture.completedFuture(ImmutableList.of());

        List<PrCheckRunState> checkRuns = join(checkRunsFuture);
        List<PullRequestDetail.ChangedFile> files = join(filesFuture);
        List<PrTimelineEvent> timeline = join(timelineFuture);
        List<PrReviewThreadMessage> reviewComments = join(reviewCommentsFuture);
        List<PrTimelineEvent> issueComments = join(issueCommentsFuture);
        List<PullRequestRepository.ReviewThreadMeta> threadResolution = join(threadResolutionFuture);
        // Stitch the GraphQL metadata onto the REST messages. Only the
        // thread root (inReplyTo == null) carries graphqlNodeId +
        // resolved; replies stay null on those fields. The lookup is
        // O(N) by databaseId == githubId.
        if (reviewComments != null && threadResolution != null && !threadResolution.isEmpty()) {
            Map<Long, PullRequestRepository.ReviewThreadMeta> metaByRootId = new HashMap<>();
            for (PullRequestRepository.ReviewThreadMeta m : threadResolution) {
                metaByRootId.put(m.rootCommentDatabaseId(), m);
            }
            reviewComments = reviewComments.stream()
                    .map(m -> {
                        if (m.inReplyTo() != null) {
                            return m;
                        }
                        PullRequestRepository.ReviewThreadMeta meta = metaByRootId.get(m.githubId());
                        if (meta == null) {
                            return m;
                        }
                        return new PrReviewThreadMessage(
                                m.githubId(), m.inReplyTo(), m.reviewId(), m.author(),
                                m.body(), m.filePath(), m.lineNumber(), m.side(),
                                m.diffHunk(), m.commitId(), m.createdAt(), m.reactions(),
                                m.outdated(), m.startLine(), m.startSide(),
                                m.originalLine(), m.originalStartLine(),
                                m.authorAssociation(),
                                meta.graphqlNodeId(),
                                meta.resolved());
                    })
                    .collect(toImmutableList());
        }

        if (raw == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Empty response from GitHub PR detail");
        }
        List<PrTimelineEvent> mergedTimeline = mergeIssueComments(
                timeline != null ? timeline : ImmutableList.of(),
                issueComments != null ? issueComments : ImmutableList.of());
        long tLinkedStart = System.nanoTime();
        List<PullRequestDetail.LinkedIssue> linkedIssues = resolveLinkedIssues(pat, ref, raw.body());
        log.info("resolveLinkedIssues({}#{}) {} issues in {}ms",
                ref.owner() + "/" + ref.repo(), ref.number(),
                linkedIssues.size(), (System.nanoTime() - tLinkedStart) / 1_000_000);

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        log.info("fetchDetailFromGitHub done: {}#{} in {}ms — timeline={} threadMsgs={} files={} checks={} issueComments={}",
                ref.owner() + "/" + ref.repo(), ref.number(), elapsedMs,
                timeline != null ? timeline.size() : 0,
                reviewComments != null ? reviewComments.size() : 0,
                files != null ? files.size() : 0,
                checkRuns != null ? checkRuns.size() : 0,
                issueComments != null ? issueComments.size() : 0);

        return new StoredPrDetail(
                raw,
                reviews != null ? reviews : ImmutableList.of(),
                files != null ? files : ImmutableList.of(),
                mergedTimeline,
                checkRuns != null ? checkRuns : ImmutableList.of(),
                reviewComments != null ? reviewComments : ImmutableList.of(),
                linkedIssues);
    }

    /**
     * Wraps a fan-out fetch with start/elapsed logs so a stuck endpoint
     * makes the slow sub-fetch obvious in the backend log. Submitted on
     * {@link AsyncConfig#IO_EXECUTOR} (virtual threads) so parents and
     * children don't share a fixed pool — see {@link #fetchDetailFromGitHub}
     * for the deadlock background.
     */
    private <T> CompletableFuture<T> timed(String name, PullRequestRef ref, Supplier<T> task)
    {
        return CompletableFuture.supplyAsync(() -> {
            long t = System.nanoTime();
            try {
                T result = task.get();
                long ms = (System.nanoTime() - t) / 1_000_000;
                if (ms > 500) {
                    log.info("{}({}#{}) ok in {}ms", name, ref.owner() + "/" + ref.repo(), ref.number(), ms);
                }
                else {
                    log.debug("{}({}#{}) ok in {}ms", name, ref.owner() + "/" + ref.repo(), ref.number(), ms);
                }
                return result;
            }
            catch (RuntimeException e) {
                long ms = (System.nanoTime() - t) / 1_000_000;
                log.warn("{}({}#{}) failed in {}ms: {}", name, ref.owner() + "/" + ref.repo(), ref.number(), ms, e.toString());
                throw e;
            }
        }, ioExecutor);
    }

    /**
     * GitHub's /issues/timeline endpoint sometimes returns {@code commented}
     * events without their {@code body} text — particularly on PRs where the
     * caller has visibility through the repo's PR list but not through the
     * issues feed. Fetching {@code /issues/{n}/comments} directly always
     * returns the body, so we use those rows as the source of truth for
     * comment text and drop {@code commented} entries from the timeline that
     * we have a richer match for. Match key is {@code (actor, createdAt)} —
     * good enough since GitHub doesn't allow two comments by the same user
     * at the same instant.
     */
    static List<PrTimelineEvent> mergeIssueComments(
            List<PrTimelineEvent> timeline, List<PrTimelineEvent> issueComments)
    {
        if (issueComments.isEmpty()) {
            return timeline;
        }
        Set<String> issueCommentKeys = issueComments.stream()
                .map(PullRequestService::commentKey)
                .filter(Objects::nonNull)
                .collect(toImmutableSet());
        List<PrTimelineEvent> out = Lists.newArrayList();
        for (PrTimelineEvent e : timeline) {
            if ("commented".equals(e.event()) && issueCommentKeys.contains(commentKey(e))) {
                // Drop — replaced by the issue-comments version below.
                continue;
            }
            out.add(e);
        }
        out.addAll(issueComments);
        return ImmutableList.copyOf(out);
    }

    private static String commentKey(PrTimelineEvent e)
    {
        if (e == null || e.actor() == null || e.timestamp() == null) {
            return null;
        }
        return e.actor() + "@" + e.timestamp();
    }

    /**
     * Scans the PR body for closing keywords (closes/fixes/resolves #N,
     * case-insensitive), then fetches each referenced issue's metadata in
     * parallel. Cross-repo refs ({@code closes owner/repo#N}) are not yet
     * matched — same-repo only — see Phase 2.5 GraphQL follow-up.
     */
    private List<PullRequestDetail.LinkedIssue> resolveLinkedIssues(String pat, PullRequestRef ref, String body)
    {
        Set<Integer> numbers = extractClosingReferences(body, ref.owner(), ref.repo());
        if (numbers.isEmpty()) {
            return ImmutableList.of();
        }
        RepoRef repoRef = new RepoRef(ref.owner(), ref.repo());
        List<CompletableFuture<Optional<PullRequestDetail.LinkedIssue>>> futures = numbers.stream()
                .sorted()
                .map(n -> CompletableFuture.supplyAsync(() -> gitHub.fetchIssue(pat, repoRef, n), ioExecutor))
                .toList();
        List<PullRequestDetail.LinkedIssue> resolved = Lists.newArrayList();
        for (CompletableFuture<Optional<PullRequestDetail.LinkedIssue>> f : futures) {
            Optional<PullRequestDetail.LinkedIssue> v = join(f);
            if (v != null && v.isPresent()) {
                resolved.add(v.get());
            }
        }
        return ImmutableList.copyOf(resolved);
    }

    // Either form is allowed after a closing keyword:
    //   #1234                                    (group 2 = number)
    //   https://github.com/owner/repo/issues/N   (groups 3/4/5 = owner/repo/number)
    // The URL form is filtered down to same-repo refs in extractClosingReferences;
    // cross-repo URLs are skipped — see Phase 2.5 GraphQL follow-up.
    private static final Pattern CLOSING_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+"
                    + "(?:#(\\d+)|https?://github\\.com/([^/\\s]+)/([^/\\s]+)/issues/(\\d+))");

    /**
     * Pulls the issue numbers from "closes #N" / "fixes #N" / "resolves #N"
     * style references in a PR body — accepts both the bare {@code #N} form
     * and the full {@code https://github.com/owner/repo/issues/N} URL form.
     * The URL form is only kept when the URL points at the PR's own repo
     * (cross-repo links are skipped to avoid fetching the wrong issue id
     * from the PR's repo). Returns an empty set when {@code body} is
     * null/blank.
     */
    static Set<Integer> extractClosingReferences(String body, String prOwner, String prRepo)
    {
        if (body == null || body.isBlank()) {
            return ImmutableSet.of();
        }
        Set<Integer> out = Sets.newLinkedHashSet();
        Matcher m = CLOSING_KEYWORD_PATTERN.matcher(body);
        while (m.find()) {
            try {
                String hashNumber = m.group(2);
                if (hashNumber != null) {
                    out.add(Integer.parseInt(hashNumber));
                    continue;
                }
                String urlOwner = m.group(3);
                String urlRepo = m.group(4);
                String urlNumber = m.group(5);
                if (urlNumber != null && urlOwner != null && urlRepo != null
                        && urlOwner.equalsIgnoreCase(prOwner)
                        && urlRepo.equalsIgnoreCase(prRepo)) {
                    out.add(Integer.parseInt(urlNumber));
                }
            }
            catch (NumberFormatException ignored) {
                // Pattern guarantees digits, so this is unreachable in practice.
            }
        }
        return ImmutableSet.copyOf(out);
    }

    private PullRequestDetail assemblePullRequestDetail(String repo, int number, StoredPrDetail stored, boolean viewerCanWrite)
    {
        PrRawDetail raw = stored.raw();
        return new PullRequestDetail(
                repo,
                number,
                raw.body(),
                raw.labels(),
                raw.draft(),
                raw.mergeable(),
                raw.mergeableState(),
                raw.additions(),
                raw.deletions(),
                raw.changedFiles(),
                countApprovals(stored.reviews()),
                countChangesRequested(stored.reviews()),
                raw.requestedReviewerCount(),
                raw.requestedReviewers() != null ? raw.requestedReviewers() : ImmutableList.of(),
                aggregateCiStatus(stored.checkRuns()),
                stored.files(),
                toActivityItems(stored.timeline()),
                toCheckRuns(stored.checkRuns()),
                groupReviewThreads(stored.reviewComments()),
                stored.linkedIssues() != null ? stored.linkedIssues() : ImmutableList.of(),
                viewerCanWrite,
                raw.headRef(),
                raw.headRepo(),
                raw.baseRef(),
                raw.baseRepo());
    }

    /**
     * Groups a flat list of GitHub per-line review comments into threads.
     * Each top-level comment ({@code inReplyTo == null}) seeds a thread;
     * replies attach to the root identified by {@code inReplyTo}. Messages
     * within a thread sort by createdAt ascending (oldest reply first, the
     * way GitHub renders them); threads themselves sort by their root's
     * createdAt descending so newest discussions surface at the top.
     */
    static List<PullRequestDetail.ReviewThread> groupReviewThreads(List<PrReviewThreadMessage> flat)
    {
        if (flat == null || flat.isEmpty()) {
            return ImmutableList.of();
        }
        // First pass: index roots and gather replies under their root id.
        LinkedHashMap<Long, PrReviewThreadMessage> rootById = Maps.newLinkedHashMap();
        Map<Long, List<PrReviewThreadMessage>> repliesByRoot = Maps.newHashMap();
        for (PrReviewThreadMessage m : flat) {
            if (m.inReplyTo() == null) {
                rootById.put(m.githubId(), m);
                repliesByRoot.computeIfAbsent(m.githubId(), k -> Lists.newArrayList());
            }
        }
        for (PrReviewThreadMessage m : flat) {
            if (m.inReplyTo() != null) {
                repliesByRoot.computeIfAbsent(m.inReplyTo(), k -> Lists.newArrayList()).add(m);
            }
        }
        // Second pass: assemble threads.
        List<PullRequestDetail.ReviewThread> threads = Lists.newArrayList();
        for (PrReviewThreadMessage root : rootById.values()) {
            List<PrReviewThreadMessage> replies = repliesByRoot.getOrDefault(root.githubId(), ImmutableList.of()).stream()
                    .sorted((a, b) -> {
                        Instant ax = a.createdAt() != null ? a.createdAt() : Instant.EPOCH;
                        Instant bx = b.createdAt() != null ? b.createdAt() : Instant.EPOCH;
                        return ax.compareTo(bx);
                    })
                    .toList();
            List<PullRequestDetail.ReviewMessage> messages = Lists.newArrayList();
            messages.add(new PullRequestDetail.ReviewMessage(
                    root.githubId(), root.author(), root.body(), root.createdAt(),
                    root.reactions() != null ? root.reactions() : Reactions.EMPTY,
                    root.reviewId(),
                    root.authorAssociation()));
            for (PrReviewThreadMessage r : replies) {
                messages.add(new PullRequestDetail.ReviewMessage(
                        r.githubId(), r.author(), r.body(), r.createdAt(),
                        r.reactions() != null ? r.reactions() : Reactions.EMPTY,
                        r.reviewId(),
                        r.authorAssociation()));
            }
            threads.add(new PullRequestDetail.ReviewThread(
                    root.githubId(),
                    root.filePath(),
                    root.lineNumber(),
                    root.side(),
                    root.diffHunk(),
                    ImmutableList.copyOf(messages),
                    // resolved: now sourced from the thread root row
                    // (V31). Stays null when the GraphQL fetcher hasn't
                    // run for this thread yet — the UI treats null as
                    // "unknown" and hides the resolved pill.
                    root.resolved(),
                    root.outdated(),
                    root.startLine(),
                    root.startSide(),
                    root.originalLine(),
                    root.originalStartLine()));
        }
        // Newest threads first.
        threads.sort((a, b) -> {
            PrReviewThreadMessage ra = rootById.get(a.rootGithubId());
            PrReviewThreadMessage rb = rootById.get(b.rootGithubId());
            Instant ax = ra != null && ra.createdAt() != null ? ra.createdAt() : Instant.EPOCH;
            Instant bx = rb != null && rb.createdAt() != null ? rb.createdAt() : Instant.EPOCH;
            return bx.compareTo(ax);
        });
        return ImmutableList.copyOf(threads);
    }

    /**
     * Keeps one row per check name — the first occurrence wins, which
     * matches GitHub's "most recent attempt" ordering for re-runs and
     * matrix retries. Both {@link #aggregateCiStatus} and
     * {@link #toCheckRuns} need to see this view, otherwise an earlier
     * failed attempt can mark a since-fixed check as FAILING in the
     * aggregate while the displayed list shows it as passing.
     */
    static List<PrCheckRunState> dedupeCheckRunsByName(List<PrCheckRunState> checkRuns)
    {
        Map<String, PrCheckRunState> latestByName = Maps.newLinkedHashMap();
        for (int i = 0; i < checkRuns.size(); i++) {
            PrCheckRunState c = checkRuns.get(i);
            String key = c.name() == null || c.name().isBlank() ? "__anonymous__" + i : c.name();
            latestByName.putIfAbsent(key, c);
        }
        return ImmutableList.copyOf(latestByName.values());
    }

    static List<PullRequestDetail.CheckRun> toCheckRuns(List<PrCheckRunState> checkRuns)
    {
        return dedupeCheckRunsByName(checkRuns).stream()
                .map(c -> new PullRequestDetail.CheckRun(
                        c.githubId(),
                        c.name(),
                        c.status(),
                        c.conclusion(),
                        c.htmlUrl(),
                        c.outputTitle(),
                        c.outputSummary()))
                .collect(toImmutableList());
    }

    static int countApprovals(List<PrReviewState> reviews)
    {
        return (int) reviews.stream().filter(r -> "APPROVED".equals(r.state())).count();
    }

    static int countChangesRequested(List<PrReviewState> reviews)
    {
        return (int) reviews.stream().filter(r -> "CHANGES_REQUESTED".equals(r.state())).count();
    }

    static PullRequestDetail.CiStatus aggregateCiStatus(List<PrCheckRunState> checkRuns)
    {
        // Aggregate over the deduped (latest-per-name) view so a check
        // that failed in attempt 1 but passed in a re-run doesn't keep
        // the whole PR in FAILING state. Without this, Trino-sized PRs
        // with frequent re-runs end up showing "CI failing — 101 of
        // 101 passing", with the merge button disabled even though
        // every visible check is green.
        List<PrCheckRunState> latest = dedupeCheckRunsByName(checkRuns);
        if (latest.isEmpty()) {
            return PullRequestDetail.CiStatus.NONE;
        }
        boolean anyFailed = latest.stream()
                .anyMatch(c -> "failure".equals(c.conclusion()) || "cancelled".equals(c.conclusion()));
        if (anyFailed) {
            return PullRequestDetail.CiStatus.FAILING;
        }
        boolean anyPending = latest.stream()
                .anyMatch(c -> "in_progress".equals(c.status()) || "queued".equals(c.status()));
        if (anyPending) {
            return PullRequestDetail.CiStatus.PENDING;
        }
        return PullRequestDetail.CiStatus.PASSING;
    }

    static List<PullRequestDetail.ActivityItem> toActivityItems(List<PrTimelineEvent> timeline)
    {
        // Returns the full conversation feed sorted newest-first. We
        // already paginated through every page on the way in, so capping
        // the surfaced slice here would just throw away work we already
        // did and leave the user with a partial view on long-lived PRs
        // (300+ events isn't unusual on big repos). Null timestamps sort
        // to the bottom — they're typically structural events that
        // don't need precise ordering.
        return timeline.stream()
                .filter(e -> INTERESTING_EVENTS.contains(e.event()))
                .sorted((a, b) -> {
                    Instant at = a.timestamp() != null ? a.timestamp() : Instant.EPOCH;
                    Instant bt = b.timestamp() != null ? b.timestamp() : Instant.EPOCH;
                    return bt.compareTo(at);
                })
                .map(e -> new PullRequestDetail.ActivityItem(
                        e.actor(),
                        e.event(),
                        e.timestamp(),
                        e.body(),
                        // The /timeline endpoint returns review state lowercase
                        // ("approved") while /pulls/{n}/reviews returns it
                        // uppercase ("APPROVED"). Normalize here so the UI can
                        // compare against canonical uppercase values regardless
                        // of which path populated the cache.
                        e.state() != null ? e.state().toUpperCase(Locale.ROOT) : null,
                        e.beforeSha(),
                        e.afterSha(),
                        e.requestedReviewer(),
                        e.reviewId(),
                        e.authorAssociation(),
                        e.githubId(),
                        e.reactions() != null ? e.reactions() : Reactions.EMPTY))
                .collect(toImmutableList());
    }

    /**
     * Most recent {@code committed} timestamp from the cached timeline,
     * or null if the PR has no committed events yet. Drives the "last
     * push Xd ago" card meta on the redesigned kanban.
     */
    private static Instant latestPushAt(List<PrTimelineEvent> timeline)
    {
        if (timeline == null) {
            return null;
        }
        Instant latest = null;
        for (PrTimelineEvent e : timeline) {
            if (!"committed".equals(e.event()) || e.timestamp() == null) {
                continue;
            }
            if (latest == null || e.timestamp().isAfter(latest)) {
                latest = e.timestamp();
            }
        }
        return latest;
    }

    /**
     * Reduces a per-event review list (each entry = one review submission)
     * to the latest verdict per reviewer. Last writer wins, so a reviewer
     * who left COMMENTED then later APPROVED ends up as APPROVED — same
     * behaviour as github.com's reviewer-status badges.
     */
    private static Map<String, String> rolledUpReviewerVerdicts(List<PrReviewState> reviews)
    {
        if (reviews == null || reviews.isEmpty()) {
            return ImmutableMap.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (PrReviewState r : reviews) {
            if (r.login() == null || r.state() == null) {
                continue;
            }
            String state = r.state();
            // GitHub's verdict semantics: APPROVED / CHANGES_REQUESTED /
            // DISMISSED reviews "stick" — a reviewer who requested
            // changes and then later left a casual COMMENTED follow-up
            // is still considered to be requesting changes. Only let
            // sticky states overwrite a previous sticky verdict; let
            // any state seed an empty slot. Without this filter a
            // single COMMENTED review wipes out the CHANGES_REQUESTED
            // signal the kanban relies on for its "Needs changes"
            // column.
            if (out.containsKey(r.login()) && !isStickyVerdict(state)) {
                continue;
            }
            out.put(r.login(), state);
        }
        return ImmutableMap.copyOf(out);
    }

    private static boolean isStickyVerdict(String state)
    {
        return "APPROVED".equals(state)
                || "CHANGES_REQUESTED".equals(state)
                || "DISMISSED".equals(state);
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
