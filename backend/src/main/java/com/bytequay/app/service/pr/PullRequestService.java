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
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.service.CredentialService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
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
            "head_ref_force_pushed");

    private final PullRequestRepository gitHub;
    private final PullRequestStore store;
    private final PrDetailStore detailStore;
    private final PrViewStateStore viewStateStore;
    private final AppSettingsStore settingsStore;
    private final CredentialService credentialService;
    private final Executor executor;
    private final Executor ioExecutor;

    public PullRequestService(
            PullRequestRepository gitHub,
            PullRequestStore store,
            PrDetailStore detailStore,
            PrViewStateStore viewStateStore,
            AppSettingsStore settingsStore,
            CredentialService credentialService,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor,
            @Qualifier(com.bytequay.app.config.AsyncConfig.IO_EXECUTOR) Executor ioExecutor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.store = requireNonNull(store, "store is null");
        this.detailStore = requireNonNull(detailStore, "detailStore is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.settingsStore = requireNonNull(settingsStore, "settingsStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
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
                    return existing == null || !existing.equals(pr.updatedAt());
                })
                .map(pr -> CompletableFuture.runAsync(() -> syncDetailQuietly(pat, pr, currentLogin), executor))
                .collect(toImmutableList());

        CompletableFuture.allOf(detailFutures.toArray(CompletableFuture[]::new)).join();
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
        Optional<Long> prId = store.findIdByRepoAndNumber(repo, number);
        if (prId.isPresent()) {
            Optional<StoredPrDetail> stored = detailStore.find(prId.get());
            if (stored.isPresent()) {
                return assemblePullRequestDetail(repo, number, stored.get());
            }
        }

        // Cache miss — fetch live, store for next time
        PullRequestRef ref = parseRef(repo, number);
        StoredPrDetail fetched = fetchDetailFromGitHub(pat, ref);
        prId.ifPresent(id -> detailStore.save(id, fetched));
        return assemblePullRequestDetail(repo, number, fetched);
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
        return gitHub.fetchCommitDiffFiles(pat, parseRef(repo, number), sha);
    }

    /**
     * Fetches a file's full content at a specific commit, returned as a
     * list of lines (1-based by index in the caller's view). Backs the
     * "expand collapsed code" affordance in the diff viewer.
     */
    public List<String> getFileBlobLines(String pat, String repo, String path, String sha)
    {
        return gitHub.fetchFileBlobLines(pat, parseRepoRef(repo), path, sha);
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
        }
    }

    /**
     * Replies to an existing per-line review thread on the PR. {@code rootCommentId}
     * is the GitHub id of the thread root (root or any reply works on
     * GitHub's side, but we always pass the root for clarity).
     */
    public void replyToReviewThread(String pat, String repo, int number, long rootCommentId, String body)
    {
        requireNotBlank(body, "reply body must not be blank");
        gitHub.replyToReviewComment(pat, parseRef(repo, number), rootCommentId, body);
    }

    /**
     * Toggles a review thread's resolved state via GraphQL. The
     * frontend identifies the thread by its REST root comment id; we
     * look up the GraphQL node id from the cached detail (populated by
     * the GraphQL fetch on the previous PR-detail load) before firing
     * the mutation. Throws 404 when the thread isn't in the cache or
     * its node id hasn't been written yet.
     */
    public void setReviewThreadResolved(String pat, long prId, long rootCommentId, boolean resolved)
    {
        String nodeId = detailStore.find(prId)
                .map(d -> d.reviewComments().stream()
                        .filter(m -> m.githubId() == rootCommentId && m.graphqlNodeId() != null)
                        .findFirst()
                        .map(PrReviewThreadMessage::graphqlNodeId)
                        .orElse(null))
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
        // The new resolved flag is reflected on the next PR-detail
        // fetch (the GraphQL fetcher runs on every fetchPullRequestDetail
        // call). The frontend optimistically toggles its local copy
        // immediately so the user doesn't see a flicker.
    }

    private static final java.util.Set<String> ALLOWED_REACTION_CONTENT = java.util.Set.of(
            "+1", "-1", "laugh", "confused", "heart", "hooray", "rocket", "eyes");

    /**
     * Adds an emoji reaction to a per-line review comment. {@code content}
     * is GitHub's reaction-content string ("+1", "heart", "rocket", …).
     * Idempotent on GitHub — re-adding the same reaction returns 200 OK
     * with the existing reaction id.
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
        com.bytequay.app.domain.RepoRef ref = parseRepoRef(repo);
        gitHub.addReviewCommentReaction(pat, ref.owner(), ref.repo(), commentId, content);
        // The reaction lands on GitHub immediately. The local DB count
        // updates on the next pulls/comments sync; the frontend
        // optimistically bumps its in-memory tally so the user sees the
        // new chip without waiting.
    }

    /**
     * Adds an emoji reaction to a top-level issue / PR comment (the
     * "commented" timeline events). Same content-allowlist + path
     * shape as the review-comment variant — only the GitHub URL
     * differs (issues/comments vs pulls/comments).
     */
    public void addIssueCommentReaction(String pat, String repo, long commentId, String content)
    {
        if (!ALLOWED_REACTION_CONTENT.contains(content)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "reaction content must be one of " + ALLOWED_REACTION_CONTENT);
        }
        com.bytequay.app.domain.RepoRef ref = parseRepoRef(repo);
        gitHub.addIssueCommentReaction(pat, ref.owner(), ref.repo(), commentId, content);
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
        invalidateCachedDetail(repo, number);
    }

    /** Removes one user from the PR's requested reviewers. */
    public void removeRequestedReviewer(String pat, String repo, int number, String reviewer)
    {
        requireNotBlank(reviewer, "reviewer must not be blank");
        gitHub.removeRequestedReviewers(
                pat,
                parseRef(repo, number),
                new RequestReviewersCommand(ImmutableList.of(reviewer.trim()), ImmutableList.of()));
        invalidateCachedDetail(repo, number);
    }

    /**
     * GitHub's suggested reviewers for one PR — the same chips github.com
     * surfaces in its conversation-page reviewers picker. GraphQL-only;
     * empty list on auth/network failure since this is a non-essential
     * affordance and shouldn't block the rest of the reviewers panel.
     */
    public List<com.bytequay.app.domain.SuggestedReviewer> getSuggestedReviewers(String pat, String repo, int number)
    {
        return gitHub.fetchSuggestedReviewers(pat, parseRef(repo, number));
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
        invalidateCachedDetail(repo, number);
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
        invalidateCachedDetail(repo, number);
    }

    /**
     * Submits an approval review for the given pull request on GitHub and records a local reviewed state.
     */
    public void approvePullRequest(String pat, String repo, int number, long prId)
    {
        gitHub.createReview(pat, parseRef(repo, number), CreateReviewCommand.approve(""));
        viewStateStore.markReviewed(prId, HandledAction.APPROVED);
    }

    /**
     * Merges the given pull request on GitHub and records a local reviewed state.
     */
    public MergeResult mergePullRequest(String pat, String repo, int number, long prId)
    {
        // Default to rebase — user preference. Merge-commit/squash can be added
        // as explicit strategies later if needed.
        MergeResult result = gitHub.mergePullRequest(pat, parseRef(repo, number), MergePullRequestCommand.rebase());
        viewStateStore.markReviewed(prId, HandledAction.MERGED);
        return result;
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<PullRequest> fetchRelevant(String pat)
    {
        CompletableFuture<List<PullRequest>> authoredFuture = CompletableFuture.supplyAsync(
                () -> gitHub.searchPullRequests(pat, "is:pr is:open author:@me"), executor);
        CompletableFuture<List<PullRequest>> reviewFuture = CompletableFuture.supplyAsync(
                () -> gitHub.searchPullRequests(pat, "is:pr is:open review-requested:@me"), executor);

        List<PullRequest> authored = join(authoredFuture);
        List<PullRequest> reviewRequested = join(reviewFuture);

        LinkedHashMap<String, PullRequest> merged = Maps.newLinkedHashMap();
        if (authored != null) {
            for (PullRequest pr : authored) {
                merged.put(pr.repo() + "#" + pr.number(), withOrigin(pr, AUTHORED));
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
                    rolledUpReviewerVerdicts(detail.reviews()));
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
        return side == null || side.isBlank() ? RIGHT : side.toUpperCase();
    }

    private static String normalizeOptionalSide(String side, String defaultSide)
    {
        return side == null || side.isBlank() ? defaultSide : side.toUpperCase();
    }

    private void invalidateCachedDetail(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(id -> detailStore.deleteByPrIds(ImmutableSet.of(id)));
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
    private <T> CompletableFuture<T> timed(String name, PullRequestRef ref, java.util.function.Supplier<T> task)
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
        Set<Integer> numbers = extractClosingReferences(body);
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

    private static final Pattern CLOSING_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+#(\\d+)");

    /**
     * Pulls the issue numbers from "closes #N" / "fixes #N" / "resolves #N"
     * style references in a PR body. Returns an empty set when {@code body}
     * is null/blank. Linkified URLs (#123 inside a code block, etc.) are
     * not specially handled — over-matching here is benign because the
     * resolve step silently drops 404s.
     */
    static Set<Integer> extractClosingReferences(String body)
    {
        if (body == null || body.isBlank()) {
            return ImmutableSet.of();
        }
        Set<Integer> out = Sets.newLinkedHashSet();
        Matcher m = CLOSING_KEYWORD_PATTERN.matcher(body);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group(2)));
            }
            catch (NumberFormatException ignored) {
                // Pattern guarantees digits, so this is unreachable in practice.
            }
        }
        return ImmutableSet.copyOf(out);
    }

    private PullRequestDetail assemblePullRequestDetail(String repo, int number, StoredPrDetail stored)
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
                aggregateCiStatus(stored.checkRuns()),
                stored.files(),
                toActivityItems(stored.timeline()),
                toCheckRuns(stored.checkRuns()),
                groupReviewThreads(stored.reviewComments()),
                stored.linkedIssues() != null ? stored.linkedIssues() : ImmutableList.of());
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
                    root.startSide()));
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

    static List<PullRequestDetail.CheckRun> toCheckRuns(List<PrCheckRunState> checkRuns)
    {
        // Deduplicate by name: GitHub's check-runs endpoint returns one row
        // per attempt, so re-runs and matrix retries can produce dozens of
        // entries with the same name. Keep the first (most recent) per name
        // and group anonymous (null/blank-name) ones under a synthetic key
        // so they survive the dedupe but still collapse identical rows.
        Map<String, PrCheckRunState> latestByName = Maps.newLinkedHashMap();
        for (int i = 0; i < checkRuns.size(); i++) {
            PrCheckRunState c = checkRuns.get(i);
            String key = c.name() == null || c.name().isBlank() ? "__anonymous__" + i : c.name();
            latestByName.putIfAbsent(key, c);
        }
        return latestByName.values().stream()
                .map(c -> new PullRequestDetail.CheckRun(c.name(), c.status(), c.conclusion(), c.htmlUrl()))
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
        if (checkRuns.isEmpty()) {
            return PullRequestDetail.CiStatus.NONE;
        }
        boolean anyFailed = checkRuns.stream()
                .anyMatch(c -> "failure".equals(c.conclusion()) || "cancelled".equals(c.conclusion()));
        if (anyFailed) {
            return PullRequestDetail.CiStatus.FAILING;
        }
        boolean anyPending = checkRuns.stream()
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
            out.put(r.login(), r.state());
        }
        return ImmutableMap.copyOf(out);
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
                pr.reviewerVerdicts());
    }

    private static <T> T join(CompletableFuture<T> future)
    {
        return future.join();
    }
}
