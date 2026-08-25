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
import com.bytequay.app.repository.github.GitHubMergeClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.AttentionPrRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.GithubReviewState;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.core.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.domain.PullRequest.Origin.REVIEW_REQUESTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestService
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestService.class);
    private static final Duration STATUS_REFRESH_INTERVAL = Duration.ofMinutes(6);
    private static final Duration ACTIVE_PULL_REQUEST_WINDOW = Duration.ofHours(6);
    private static final int CLOSED_RETENTION_DAYS = 30;

    // PR-search pagination for the dashboard's relevant-PR fetch. 100 is
    // GitHub's max page size; 6 pages caps the per-query cost at 600 PRs
    // (GitHub's search ceiling is 1000) while still covering reviewers
    // with far more than the old single 50-item page exposed.
    private static final int SEARCH_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_PAGES = 6;

    private final GitHubPullRequestReadClient gitHub;
    private final GitHubPullRequestWriteClient pullRequestWrites;
    private final GitHubRepoClient repositories;
    private final GitHubMergeClient merges;
    private final GitHubAccountClient accounts;
    private final PullRequestStore store;
    private final PullRequestSnapshotService snapshots;
    private final PrViewStateStore viewStateStore;
    private final Executor executor;
    private final CredentialService credentialService;
    @Autowired
    public PullRequestService(
            GitHubPullRequestReadClient gitHub,
            GitHubPullRequestWriteClient pullRequestWrites,
            GitHubRepoClient repositories,
            GitHubMergeClient merges,
            GitHubAccountClient accounts,
            PullRequestStore store,
            PullRequestSnapshotService snapshots,
            PrViewStateStore viewStateStore,
            CredentialService credentialService,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.store = requireNonNull(store, "store is null");
        this.snapshots = requireNonNull(snapshots, "snapshots is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    public PullRequestPage listPullRequests(int page, int pageSize)
    {
        return listPullRequests(page, pageSize, null);
    }

    public PullRequestPage listPullRequests(int page, int pageSize, String search)
    {
        checkPage(page, pageSize);
        return store.findPage(page, pageSize, validateSearch(search));
    }

    /** Returns the existing local PR rows restricted to one repository. */
    public PullRequestPage listPullRequests(RepoRef repository, int page, int pageSize)
    {
        return listPullRequests(repository, page, pageSize, null);
    }

    public PullRequestPage listPullRequests(RepoRef repository, int page, int pageSize, String search)
    {
        checkPage(page, pageSize);
        return store.findRepositoryPage(
                requireNonNull(repository, "repository is null").fullName(), page, pageSize, validateSearch(search));
    }

    private static String validateSearch(String search)
    {
        String value = search == null ? "" : search.trim();
        if (value.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "search must not exceed 200 characters");
        }
        return value;
    }

    /**
     * Refreshes the local workspace projection for one monitored repository.
     *
     * <p>The refresh discovers pull requests regardless of state, preserves rows omitted from
     * GitHub's eventually-consistent search, and refreshes detail by pull-request identity only
     * for new or changed rows. The caller resolves whether a workspace monitors its selected
     * repository or an upstream repository.</p>
     */
    public void syncRepository(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        String currentLogin = credential.profile().login();
        String syncMarker = java.util.UUID.randomUUID().toString();
        String qualifier = " repo:" + repository.fullName();
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
        syncRepositoryQuery(
                pat, repository, currentLogin, syncMarker, "is:pr updated:>=" + sinceDate + qualifier, false);
        syncRepositoryQuery(
                pat, repository, currentLogin, syncMarker, "is:pr is:open" + qualifier, false);
        syncRepositoryQuery(
                pat,
                repository,
                currentLogin,
                syncMarker,
                "is:pr is:open user-review-requested:@me" + qualifier,
                true);
        reconcileRepositorySearchMisses(pat, repository, currentLogin, syncMarker);
        deleteDetails(store.completeRepositorySync(
                repository.fullName(), Instant.now().minus(CLOSED_RETENTION_DAYS, ChronoUnit.DAYS)));
    }

    public List<RepoUser> listRepositoryUsers(String repo)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return gitHub.fetchAssignableUsers(pat, RepoRef.of(ref.owner(), ref.repo()));
    }

    public List<RepositoryLabel> listRepositoryLabels(String repo)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return repositories.fetchLabels(pat, RepoRef.of(ref.owner(), ref.repo()));
    }

    public boolean hasMergeQueue(String repo, String baseRef)
    {
        requireNotBlank(baseRef, "baseRef");
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, 1);
        return merges.hasMergeQueue(pat, RepoRef.of(ref.owner(), ref.repo()), baseRef);
    }

    public void rerunFailedChecks(String repo, int number)
    {
        snapshots.rerunFailedChecks(parseRef(repo, number));
    }

    /**
     * Fetches the PR list from GitHub, persists it, then refreshes detail for any PR whose
     * {@code updatedAt} changed (or is new). Stale detail for removed PRs is cleaned up.
     * All detail fetches run in parallel.
     */
    public void syncFromGitHub()
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        String currentLogin = credential.profile().login();
        String syncMarker = java.util.UUID.randomUUID().toString();
        Set<String> seen = new HashSet<>();
        syncDashboardQuery(pat, currentLogin, syncMarker, seen, "is:pr is:open author:@me", AUTHORED);
        String sinceDate = LocalDate.now(ZoneOffset.UTC).minusDays(CLOSED_RETENTION_DAYS).toString();
        syncDashboardQuery(
                pat,
                currentLogin,
                syncMarker,
                seen,
                "is:pr is:closed author:@me closed:>=" + sinceDate,
                AUTHORED);
        syncDashboardQuery(
                pat, currentLogin, syncMarker, seen, "is:pr is:open user-review-requested:@me", REVIEW_REQUESTED);
        syncDashboardQuery(
                pat, currentLogin, syncMarker, seen, "is:pr is:open reviewed-by:@me", OTHER);
        syncNotificationPullRequests(pat, currentLogin, syncMarker, seen);
        deleteDetails(store.completeDashboardSync(syncMarker));
    }

    private void syncDetails(String pat, String currentLogin, List<PullRequest> pullRequests)
    {
        Map<String, Boolean> viewerCanWriteByRepo = new HashMap<>();
        for (PullRequest pullRequest : pullRequests) {
            viewerCanWriteByRepo.computeIfAbsent(
                    pullRequest.repo(), repo -> resolveViewerCanWrite(pat, repo));
        }
        List<CompletableFuture<Void>> details = pullRequests.stream()
                .map(pullRequest -> CompletableFuture.runAsync(
                        () -> syncDetailQuietly(
                                pat,
                                pullRequest,
                                currentLogin,
                                viewerCanWriteByRepo.get(pullRequest.repo())),
                        executor))
                .collect(toImmutableList());
        CompletableFuture.allOf(details.toArray(CompletableFuture[]::new)).join();
    }

    private void syncStatuses(String pat, String currentLogin, List<PullRequest> pullRequests)
    {
        List<CompletableFuture<Void>> statuses = pullRequests.stream()
                .map(pullRequest -> CompletableFuture.runAsync(
                        () -> syncStatusQuietly(pat, pullRequest, currentLogin), executor))
                .collect(toImmutableList());
        CompletableFuture.allOf(statuses.toArray(CompletableFuture[]::new)).join();
    }

    /** Returns persisted detail, fetching and caching it on a local miss. */
    public PullRequestDetail getPullRequestDetail(String repo, int number)
    {
        parseRef(repo, number);
        Optional<PullRequestDetail> stored = findPullRequestDetail(repo, number);
        if (stored.isPresent()) {
            return stored.orElseThrow();
        }
        return refreshPullRequestDetailFromGitHub(repo, number).detail();
    }

    public PullRequestDetail getPullRequestDetail(long prId)
    {
        PullRequestRef ref = storedPullRequestRef(prId);
        return getPullRequestDetail(ref.repoFullName(), ref.number());
    }

    DetailRefresh refreshPullRequestDetailFromGitHub(String repo, int number)
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        String pat = credential.token();
        PullRequestRef ref = parseRef(repo, number);
        Optional<Long> storedId = store.findIdByRepoAndNumber(repo, number);
        Optional<String> etag = storedId.flatMap(snapshots::findDetailEtag);
        PullRequestFetch fetched = gitHub.getPullRequest(pat, ref, etag);
        if (fetched.pullRequest().isEmpty()) {
            StoredPrDetail detail = snapshots.refreshStatus(pat, ref, storedId.orElseThrow(), fetched);
            return new DetailRefresh(false, PullRequestDetailMapper.toPullRequestDetail(
                    ref.repoFullName(), ref.number(), detail, detail.viewerCanWrite()));
        }
        return new DetailRefresh(true, storeFullRefresh(credential, ref, storedId, fetched));
    }

    PullRequestDetail refreshAllPullRequestDetailFromGitHub(String repo, int number)
    {
        GitHubCredential credential = credentialService.resolveGitHubCredential();
        PullRequestRef ref = parseRef(repo, number);
        Optional<Long> storedId = store.findIdByRepoAndNumber(repo, number);
        PullRequestFetch fetched = gitHub.getPullRequest(credential.token(), ref, Optional.empty());
        return storeFullRefresh(credential, ref, storedId, fetched);
    }

    private PullRequestDetail storeFullRefresh(
            GitHubCredential credential,
            PullRequestRef ref,
            Optional<Long> storedId,
            PullRequestFetch fetched)
    {
        String pat = credential.token();
        String currentLogin = credential.profile().login();
        PullRequest fetchedPullRequest = workspaceOrigin(fetched.pullRequest().orElseThrow(), currentLogin);
        PullRequest pullRequest = storedId
                .map(fetchedPullRequest::withId)
                .orElse(fetchedPullRequest);
        store.saveObserved(pullRequest);

        StoredPrDetail detail = snapshots.refreshDetail(
                pat,
                ref,
                pullRequest.id(),
                accounts.fetchViewerCanWrite(pat, RepoRef.of(ref.owner(), ref.repo())));
        storeDetail(pullRequest, detail, currentLogin);
        syncDiffQuietly(pat, pullRequest);
        snapshots.updateDetailEtag(pullRequest.id(), fetched.etag());
        return PullRequestDetailMapper.toPullRequestDetail(
                ref.repoFullName(), ref.number(), detail, detail.viewerCanWrite());
    }

    DetailRefresh refreshPullRequestDetailFromGitHub(long prId)
    {
        PullRequestRef ref = storedPullRequestRef(prId);
        return refreshPullRequestDetailFromGitHub(ref.repoFullName(), ref.number());
    }

    PullRequestDetail refreshAllPullRequestDetailFromGitHub(long prId)
    {
        PullRequestRef ref = storedPullRequestRef(prId);
        return refreshAllPullRequestDetailFromGitHub(ref.repoFullName(), ref.number());
    }

    /** Returns locally persisted diff files without contacting GitHub. */
    public List<DiffFile> getPullRequestDiffFiles(String repo, int number)
    {
        parseRef(repo, number);
        return snapshots.getDiffFiles(storedPullRequestId(repo, number), null, null);
    }

    public List<DiffFile> getPullRequestDiffFiles(
            String repo, int number, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getDiffFiles(
                storedPullRequestId(repo, number), baseSha, headSha);
    }

    public List<DiffCommit> getPullRequestDiffCommits(String repo, int number)
    {
        parseRef(repo, number);
        return snapshots.getDiffCommits(storedPullRequestId(repo, number));
    }

    public DiffFileContent getPullRequestDiffFileContent(String repo, int number, String path)
    {
        parseRef(repo, number);
        return snapshots.getFileContent(
                storedPullRequestId(repo, number), path, null, null);
    }

    public DiffFileContent getPullRequestDiffFileContent(
            String repo, int number, String path, String baseSha, String headSha)
    {
        parseRef(repo, number);
        return snapshots.getFileContent(
                storedPullRequestId(repo, number), path, baseSha, headSha);
    }

    void refreshPullRequestDiffFromGitHub(
            String repo, int number, String baseSha, String headSha)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        snapshots.refreshDiff(
                pat, ref, storedPullRequestId(repo, number), baseSha, headSha);
    }

    void refreshPullRequestDiffFileContentFromGitHub(
            String repo,
            int number,
            String path,
            String baseSha,
            String headSha)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        snapshots.refreshFileContent(
                pat, ref, storedPullRequestId(repo, number), path, baseSha, headSha);
    }

    public void createReviewComment(String repo, int number, InlineReviewComment comment)
    {
        validateReviewComment(comment, true);
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.createReviewComment(pat, parseRef(repo, number), comment);
        invalidatePullRequestCaches(repo, number);
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
                        ImmutableList.copyOf(timeline),
                        cached.checkRuns(),
                        cached.unavailableSections(),
                        cached.viewerCanWrite());
            });
        }
    }

    public void closePullRequest(String repo, int number)
    {
        pullRequestWrites.closePullRequest(
                credentialService.resolveGitHubToken(), parseRef(repo, number));
        invalidatePullRequestCaches(repo, number);
    }

    public void updateMetadata(String repo, int number, List<String> assignees, List<String> labels)
    {
        pullRequestWrites.updateIssueMetadata(
                credentialService.resolveGitHubToken(),
                parseRef(repo, number),
                normalizeNames(assignees, "assignees"),
                normalizeNames(labels, "labels"));
        invalidatePullRequestCaches(repo, number);
    }

    public void updateReviewerRequest(String repo, int number, String login, boolean requested)
    {
        requireNotBlank(login, "reviewer login must not be blank");
        pullRequestWrites.updateReviewerRequest(
                credentialService.resolveGitHubToken(), parseRef(repo, number), login.trim(), requested);
        invalidatePullRequestCaches(repo, number);
    }

    private static List<String> normalizeNames(List<String> values, String name)
    {
        requireNonNull(values, name + " is null");
        return values.stream()
                .map(value -> {
                    requireNotBlank(value, name + " must not contain blanks");
                    return value.trim();
                })
                .distinct()
                .collect(toImmutableList());
    }

    public void replyToReviewComment(String repo, int number, long commentId, String body)
    {
        requireNotBlank(body, "reply body must not be blank");
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.replyToReviewComment(pat, parseRef(repo, number), commentId, body);
        invalidatePullRequestCaches(repo, number);
    }

    public void setReviewThreadResolved(String repo, int number, long rootCommentId, boolean resolved)
    {
        String pat = credentialService.resolveGitHubToken();
        PullRequestRef ref = parseRef(repo, number);
        String threadId = gitHub.findReviewThreadId(pat, ref, rootCommentId);
        pullRequestWrites.setReviewThreadResolved(pat, threadId, resolved);
        invalidatePullRequestCaches(repo, number);
    }

    /**
     * Submits an approval review for the given pull request on GitHub and records a local reviewed state.
     */
    public void approvePullRequest(String repo, int number, long prId)
    {
        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.approve(pat, parseRef(repo, number));
        viewStateStore.markReviewed(prId, HandledAction.APPROVED, currentHeadSha(prId));
        invalidatePullRequestCaches(repo, number);
    }

    public void submitReview(String repo, int number, long prId, String event, String body)
    {
        requireNotBlank(event, "review event must not be blank");
        String normalizedEvent = event.toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("COMMENT", "APPROVE", "REQUEST_CHANGES").contains(normalizedEvent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported review event");
        }
        String reviewBody = body == null ? "" : body.trim();
        if (!"APPROVE".equals(normalizedEvent)) {
            requireNotBlank(reviewBody, "review body must not be blank");
        }

        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.submitReview(pat, parseRef(repo, number), normalizedEvent, reviewBody);
        markReviewed(prId, normalizedEvent);
        invalidatePullRequestCaches(repo, number);
    }

    public void submitReview(
            String repo,
            int number,
            long prId,
            String event,
            String body,
            List<InlineReviewComment> comments)
    {
        requireNotBlank(event, "review event must not be blank");
        String normalizedEvent = event.toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("COMMENT", "APPROVE", "REQUEST_CHANGES").contains(normalizedEvent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported review event");
        }
        List<InlineReviewComment> reviewComments = comments == null ? ImmutableList.of() : comments;
        reviewComments.forEach(comment -> validateReviewComment(comment, false));
        String reviewBody = body == null ? "" : body.trim();
        if (!"APPROVE".equals(normalizedEvent) && reviewBody.isBlank() && reviewComments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review body or inline comment must not be blank");
        }

        String pat = credentialService.resolveGitHubToken();
        pullRequestWrites.submitReview(
                pat, parseRef(repo, number), normalizedEvent, reviewBody, reviewComments);
        markReviewed(prId, normalizedEvent);
        invalidatePullRequestCaches(repo, number);
    }

    private void markReviewed(long prId, String event)
    {
        HandledAction action = switch (event) {
            case "APPROVE" -> HandledAction.APPROVED;
            case "REQUEST_CHANGES" -> HandledAction.CHANGES_REQUESTED;
            default -> HandledAction.COMMENTED;
        };
        viewStateStore.markReviewed(prId, action, currentHeadSha(prId));
    }

    private String currentHeadSha(long prId)
    {
        return snapshots.findHeadSha(prId).orElse(null);
    }

    private static void validateReviewComment(InlineReviewComment comment, boolean requireCommit)
    {
        if (comment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment must not be null");
        }
        requireNotBlank(comment.path(), "review comment path must not be blank");
        requireNotBlank(comment.body(), "review comment body must not be blank");
        requireNotBlank(comment.side(), "review comment side must not be blank");
        String side = comment.side().toUpperCase(Locale.ROOT);
        if (!ImmutableSet.of("LEFT", "RIGHT").contains(side)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment side must be LEFT or RIGHT");
        }
        if (comment.line() < 1 || comment.startLine() != null && (comment.startLine() < 1 || comment.startLine() > comment.line())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid review comment line range");
        }
        if (comment.startLine() != null && !side.equalsIgnoreCase(comment.startSide())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "review comment range must stay on one side");
        }
        if (requireCommit) {
            requireNotBlank(comment.commitId(), "review comment commit must not be blank");
        }
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
        viewStateStore.markReviewed(prId, HandledAction.MERGED, currentHeadSha(prId));
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

    private void syncDashboardQuery(
            String pat,
            String currentLogin,
            String syncMarker,
            Set<String> seen,
            String query,
            PullRequest.Origin origin)
    {
        for (int page = 1; page <= MAX_SEARCH_PAGES; page++) {
            PullRequestHistoryPage result =
                    gitHub.searchPullRequestsPaged(pat, query, page, SEARCH_PAGE_SIZE, "updated", "desc");
            List<PullRequest> fresh = result.items().stream()
                    .filter(pullRequest -> seen.add(pullRequest.repo() + "#" + pullRequest.number()))
                    .map(pullRequest -> withOrigin(pullRequest, origin))
                    .collect(toImmutableList());
            syncDashboardPage(pat, currentLogin, syncMarker, fresh);
            if (!result.hasMore()) {
                break;
            }
        }
    }

    private void syncDashboardPage(
            String pat,
            String currentLogin,
            String syncMarker,
            List<PullRequest> pullRequests)
    {
        if (pullRequests.isEmpty()) {
            return;
        }
        pullRequests = pullRequests.stream()
                .map(this::preserveStoredId)
                .collect(toImmutableList());
        Set<Long> ids = pullRequests.stream().map(PullRequest::id).collect(toImmutableSet());
        Map<Long, Instant> previous = store.findUpdatedAtByIds(ids);
        store.saveDashboardSyncPage(pullRequests, syncMarker);
        syncPageRefreshes(pat, currentLogin, pullRequests, previous);
    }

    private void syncRepositoryQuery(
            String pat,
            RepoRef repository,
            String currentLogin,
            String syncMarker,
            String query,
            boolean reviewRequested)
    {
        for (int page = 1; page <= MAX_SEARCH_PAGES; page++) {
            PullRequestHistoryPage result =
                    gitHub.searchPullRequestsPaged(pat, query, page, SEARCH_PAGE_SIZE, "updated", "desc");
            List<PullRequest> fresh = result.items().stream()
                    .map(pullRequest -> reviewRequested
                            ? withOrigin(pullRequest, REVIEW_REQUESTED)
                            : workspaceOrigin(pullRequest, currentLogin))
                    .map(this::preserveStoredId)
                    .collect(toImmutableList());
            if (!fresh.isEmpty()) {
                Set<Long> ids = fresh.stream().map(PullRequest::id).collect(toImmutableSet());
                Map<Long, Instant> previous = store.findUpdatedAtByIds(ids);
                store.saveRepositorySyncPage(repository.fullName(), fresh, syncMarker);
                syncPageRefreshes(pat, currentLogin, fresh, previous);
            }
            if (!result.hasMore()) {
                break;
            }
        }
    }

    private void reconcileRepositorySearchMisses(
            String pat, RepoRef repository, String currentLogin, String syncMarker)
    {
        List<PullRequest> refreshed = new ArrayList<>();
        List<PullRequest> changed = new ArrayList<>();
        for (PullRequest stored : store.findRepositoryRowsMissingSync(repository.fullName(), syncMarker)) {
            PullRequestRef ref = PullRequestRef.of(repository.owner(), repository.repo(), stored.number());
            try {
                PullRequest exact = workspaceOrigin(gitHub.getPullRequest(pat, ref), currentLogin);
                if (stored.origin() == REVIEW_REQUESTED) {
                    exact = withOrigin(exact, REVIEW_REQUESTED);
                }
                exact = exact.withId(stored.id());
                refreshed.add(exact);
                if (!Objects.equals(stored.updatedAt(), exact.updatedAt())) {
                    changed.add(exact);
                }
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                log.info("search-missing PR {} fetch failed: {}", ref.fullName(), failure.getMessage());
            }
        }
        if (!refreshed.isEmpty()) {
            store.saveRepositorySyncPage(repository.fullName(), refreshed, syncMarker);
            syncDetails(pat, currentLogin, changed);
        }
    }

    private void syncNotificationPullRequests(
            String pat,
            String currentLogin,
            String syncMarker,
            Set<String> seen)
    {
        for (AttentionPrRef attention : gitHub.fetchAttentionPrRefs(pat)) {
            PullRequestRef ref = attention.ref();
            if (!seen.add(ref.repoFullName() + "#" + ref.number())) {
                continue;
            }
            try {
                PullRequest pullRequest = gitHub.getPullRequest(pat, ref);
                if ("open".equals(pullRequest.state())) {
                    syncDashboardPage(
                            pat,
                            currentLogin,
                            syncMarker,
                            ImmutableList.of(attention.reviewRequested()
                                    ? withOrigin(pullRequest, REVIEW_REQUESTED)
                                    : workspaceOrigin(pullRequest, currentLogin)));
                }
            }
            catch (RuntimeException failure) {
                if (isRateLimit(failure)) {
                    throw failure;
                }
                log.info("notification PR {} fetch failed: {}", ref.fullName(), failure.getMessage());
            }
        }
    }

    private void syncPageRefreshes(
            String pat,
            String currentLogin,
            List<PullRequest> pullRequests,
            Map<Long, Instant> previous)
    {
        Instant now = Instant.now();
        List<PullRequest> details = new ArrayList<>();
        List<PullRequest> statuses = new ArrayList<>();
        for (PullRequest pullRequest : pullRequests) {
            RefreshMode mode = refreshMode(
                    pullRequest,
                    previous.get(pullRequest.id()),
                    snapshots.findDetailSyncedAt(pullRequest.repo(), pullRequest.number()),
                    now);
            if (mode == RefreshMode.FULL) {
                details.add(pullRequest);
            }
            else if (mode == RefreshMode.STATUS) {
                statuses.add(pullRequest);
            }
        }
        syncDetails(pat, currentLogin, details);
        syncStatuses(pat, currentLogin, statuses);
    }

    static RefreshMode refreshMode(
            PullRequest pullRequest,
            Instant previousUpdatedAt,
            Optional<Instant> detailSyncedAt,
            Instant now)
    {
        if (previousUpdatedAt == null
                || !Objects.equals(previousUpdatedAt, pullRequest.updatedAt())
                || detailSyncedAt.isEmpty()) {
            return RefreshMode.FULL;
        }
        if (!"open".equalsIgnoreCase(pullRequest.state())
                || pullRequest.updatedAt() == null
                || pullRequest.updatedAt().isBefore(now.minus(ACTIVE_PULL_REQUEST_WINDOW))) {
            return RefreshMode.NONE;
        }
        return detailSyncedAt.orElseThrow().isAfter(now.minus(STATUS_REFRESH_INTERVAL))
                ? RefreshMode.NONE
                : RefreshMode.STATUS;
    }

    enum RefreshMode { FULL, STATUS, NONE }

    private void deleteDetails(Set<Long> pullRequestIds)
    {
        snapshots.delete(pullRequestIds);
    }

    private static void checkPage(int page, int pageSize)
    {
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
    }

    private void syncDetailQuietly(
            String pat,
            PullRequest pullRequest,
            String currentLogin,
            boolean viewerCanWrite)
    {
        try {
            StoredPrDetail detail = snapshots.refreshDetail(
                    pat,
                    parseRef(pullRequest.repo(), pullRequest.number()),
                    pullRequest.id(),
                    viewerCanWrite);
            storeDetail(pullRequest, detail, currentLogin);
            syncDiffQuietly(pat, pullRequest);
        }
        catch (Exception e) {
            if (!isRateLimit(e)) {
                log.warn("Failed to sync detail for PR {}/{}: {}",
                        pullRequest.repo(), pullRequest.number(), e.getMessage());
            }
        }
    }

    private void syncStatusQuietly(String pat, PullRequest pullRequest, String currentLogin)
    {
        try {
            StoredPrDetail detail = snapshots.refreshStatus(
                    pat,
                    parseRef(pullRequest.repo(), pullRequest.number()),
                    pullRequest.id());
            storeDetail(pullRequest, detail, currentLogin);
        }
        catch (Exception e) {
            if (!isRateLimit(e)) {
                log.warn("Failed to sync status for PR {}/{}: {}",
                        pullRequest.repo(), pullRequest.number(), e.getMessage());
            }
        }
    }

    private void storeDetail(PullRequest pullRequest, StoredPrDetail detail, String currentLogin)
    {
        long prId = pullRequest.id();
        updateListState(prId, detail);
        PrViewState viewState = viewStateStore.findByPrIds(ImmutableSet.of(prId)).get(prId);
        reconcileHandledActionFromReviews(prId, detail.reviews(), currentLogin, viewState);
        viewState = viewStateStore.findByPrIds(ImmutableSet.of(prId)).get(prId);
        if (needsReviewAgain(pullRequest, detail, currentLogin, viewState)) {
            viewStateStore.markNeedsReview(prId);
            viewState = viewStateStore.findByPrIds(ImmutableSet.of(prId)).get(prId);
        }
        Instant viewedAt = viewState != null ? viewState.viewedAt() : null;
        store.updateEnrichment(
                prId,
                PrAttention.aggregateCiStatus(detail),
                detail.raw().additions(),
                detail.raw().deletions(),
                PrAttention.countComments(detail),
                PrAttention.promoteReason(pullRequest, detail, currentLogin, viewedAt, Instant.now()),
                detail.raw().mergeable(),
                detail.raw().mergeableState(),
                PullRequestDetailMapper.latestPushAt(detail.timeline()),
                PullRequestDetailMapper.rolledUpReviewerVerdicts(detail.reviews()),
                detail.raw().headRef());
    }

    private void syncDiffQuietly(String pat, PullRequest pullRequest)
    {
        try {
            snapshots.refreshDefaultDiff(
                    pat,
                    parseRef(pullRequest.repo(), pullRequest.number()),
                    pullRequest.id());
        }
        catch (RuntimeException failure) {
            if (!isRateLimit(failure)) {
                log.warn("Failed to sync diff for PR {}/{}: {}",
                        pullRequest.repo(), pullRequest.number(), failure.getMessage());
            }
        }
    }

    private boolean resolveViewerCanWrite(String pat, String repo)
    {
        try {
            PullRequestRef ref = parseRef(repo, 1);
            return accounts.fetchViewerCanWrite(pat, RepoRef.of(ref.owner(), ref.repo()));
        }
        catch (RuntimeException failure) {
            if (isRateLimit(failure)) {
                throw failure;
            }
            log.warn("Could not resolve viewer permission for {}: {}", repo, failure.getMessage());
            return false;
        }
    }

    private long storedPullRequestId(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number)
                .orElseThrow(() -> localSnapshotMissing("Pull request"));
    }

    private PullRequestRef storedPullRequestRef(long prId)
    {
        return store.findRefById(prId)
                .orElseThrow(() -> localSnapshotMissing("Pull request"));
    }

    private Optional<PullRequestDetail> findPullRequestDetail(String repo, int number)
    {
        return store.findIdByRepoAndNumber(repo, number)
                .flatMap(prId -> snapshots.findDetail(repo, number, prId));
    }

    private static ResponseStatusException localSnapshotMissing(String name)
    {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, name + " has not been refreshed");
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
        PrReviewState review = latestHandledReview(reviews, currentLogin);
        if (review == null) {
            return;
        }
        if (existing != null && existing.handledAction() != null) {
            return;
        }
        viewStateStore.markReviewed(prId, handledAction(review), review.commitId());
    }

    /**
     * Pure-logic version of the review→handled-action mapping, exposed
     * package-private for unit tests. Returns the action implied by the
     * current user's latest review, or {@code null} if no review remains after
     * a dismissal.
     */
    static HandledAction deriveHandledActionFromReviews(List<PrReviewState> reviews, String currentLogin)
    {
        PrReviewState review = latestHandledReview(reviews, currentLogin);
        return review == null ? null : handledAction(review);
    }

    private static PrReviewState latestHandledReview(List<PrReviewState> reviews, String currentLogin)
    {
        if (currentLogin == null || reviews == null || reviews.isEmpty()) {
            return null;
        }
        PrReviewState derived = null;
        for (PrReviewState r : reviews) {
            if (r == null || !currentLogin.equalsIgnoreCase(r.login())) {
                continue;
            }
            if (GithubReviewState.APPROVED.equals(r.state())) {
                derived = r;
            }
            else if (GithubReviewState.CHANGES_REQUESTED.equals(r.state())) {
                derived = r;
            }
            else if (GithubReviewState.COMMENTED.equals(r.state())) {
                derived = r;
            }
            else if ("DISMISSED".equals(r.state())) {
                // A later DISMISSED resets the derived state — if nothing
                // else follows, the user effectively has no stance.
                derived = null;
            }
        }
        return derived;
    }

    private static HandledAction handledAction(PrReviewState review)
    {
        if (GithubReviewState.APPROVED.equals(review.state())) {
            return HandledAction.APPROVED;
        }
        if (GithubReviewState.CHANGES_REQUESTED.equals(review.state())) {
            return HandledAction.CHANGES_REQUESTED;
        }
        return HandledAction.COMMENTED;
    }

    static boolean needsReviewAgain(
            PullRequest pullRequest,
            StoredPrDetail detail,
            String currentLogin,
            PrViewState viewState)
    {
        if (viewState == null || viewState.handledAction() == null || viewState.reviewedAt() == null) {
            return false;
        }
        String currentHeadSha = detail.raw().headSha();
        if (viewState.reviewedHeadSha() != null && currentHeadSha != null
                && !viewState.reviewedHeadSha().equals(currentHeadSha)) {
            return true;
        }
        if (viewState.reviewedHeadSha() == null) {
            Instant latestPush = PullRequestDetailMapper.latestPushAt(detail.timeline());
            if (latestPush != null && latestPush.isAfter(viewState.reviewedAt())) {
                return true;
            }
        }
        if (currentLogin != null && detail.raw().requestedReviewers().stream()
                .anyMatch(currentLogin::equalsIgnoreCase)
                && hasEventAfter(detail.timeline(), "review_requested", viewState.reviewedAt())) {
            return true;
        }
        if (currentLogin != null && pullRequest.author() != null
                && hasAuthorReplyAfter(detail.timeline(), currentLogin, pullRequest.author(), viewState.reviewedAt())) {
            return true;
        }
        return latestReviewState(detail.reviews(), currentLogin)
                .map("DISMISSED"::equalsIgnoreCase)
                .orElse(false);
    }

    private static boolean hasEventAfter(List<PrTimelineEvent> timeline, String event, Instant reviewedAt)
    {
        return timeline.stream().anyMatch(item -> event.equals(item.event())
                && item.timestamp() != null
                && item.timestamp().isAfter(reviewedAt));
    }

    private static boolean hasAuthorReplyAfter(
            List<PrTimelineEvent> timeline,
            String currentLogin,
            String author,
            Instant reviewedAt)
    {
        return timeline.stream()
                .filter(item -> "review_thread".equals(item.event()))
                .filter(item -> item.payload().comments().stream().anyMatch(comment -> comment.inReplyToId() == null
                        && currentLogin.equalsIgnoreCase(comment.actor())))
                .flatMap(item -> item.payload().comments().stream())
                .anyMatch(comment -> comment.inReplyToId() != null
                        && author.equalsIgnoreCase(comment.actor())
                        && comment.timestamp() != null
                        && comment.timestamp().isAfter(reviewedAt));
    }

    private static Optional<String> latestReviewState(List<PrReviewState> reviews, String currentLogin)
    {
        if (currentLogin == null) {
            return Optional.empty();
        }
        String state = null;
        for (PrReviewState review : reviews) {
            if (review != null && currentLogin.equalsIgnoreCase(review.login())) {
                state = review.state();
            }
        }
        return Optional.ofNullable(state);
    }

    private void invalidatePullRequestDetail(String repo, int number)
    {
        store.findIdByRepoAndNumber(repo, number)
                .ifPresent(snapshots::delete);
    }

    private void invalidatePullRequestCaches(String repo, int number)
    {
        invalidatePullRequestDetail(repo, number);
    }

    private void patchCachedDetail(
            Optional<Long> prId,
            UnaryOperator<StoredPrDetail> patcher)
    {
        requireNonNull(patcher, "patcher is null");
        prId.ifPresent(id -> snapshots.patchDetail(id, patcher));
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

    private PullRequest preserveStoredId(PullRequest pullRequest)
    {
        return store.findIdByRepoAndNumber(pullRequest.repo(), pullRequest.number())
                .map(pullRequest::withId)
                .orElse(pullRequest);
    }

    static PullRequest workspaceOrigin(PullRequest pullRequest, String currentLogin)
    {
        PullRequest.Origin origin;
        if (currentLogin != null && currentLogin.equalsIgnoreCase(pullRequest.author())) {
            origin = AUTHORED;
        }
        else if (currentLogin != null && pullRequest.requestedReviewers().stream().anyMatch(currentLogin::equalsIgnoreCase)) {
            origin = REVIEW_REQUESTED;
        }
        else {
            origin = OTHER;
        }
        return withOrigin(pullRequest, origin);
    }

    record DetailRefresh(boolean full, PullRequestDetail detail)
    {
        DetailRefresh
        {
            requireNonNull(detail, "detail is null");
        }
    }
}
