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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.GitHubUserMatch;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RepoRef;
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
import com.google.common.collect.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.domain.PullRequestDetail.CiStatus.FAILING;
import static com.bytequay.app.domain.PullRequestDetail.CiStatus.NONE;
import static com.bytequay.app.domain.PullRequestDetail.CiStatus.PASSING;
import static com.bytequay.app.domain.PullRequestDetail.CiStatus.PENDING;
import static com.bytequay.app.repository.AppSettingsStore.Key.PR_SORT_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class TestPullRequestService
{
    @Mock
    private PullRequestRepository gitHub;

    @Mock
    private PullRequestStore store;

    @Mock
    private PrDetailStore detailStore;

    @Mock
    private PrViewStateStore viewStateStore;

    @Mock
    private AppSettingsStore settingsStore;

    // Consumed by @InjectMocks via reflection — Error Prone can't see the use.
    @SuppressWarnings("UnusedVariable")
    @Mock
    private CredentialService credentialService;

    @Mock
    private GitHubResponseCache responseCache;

    @Mock
    private PullRequestDetailInvalidator detailInvalidator;

    @Mock
    private RepoListCache repoListCache;

    @Mock
    private RepoMetadataCacheStore repoMetadataCache;

    @Mock
    private PatResolver patResolver;

    @SuppressWarnings("UnusedVariable")
    @Mock
    private Executor executor;

    // Consumed by @InjectMocks via reflection — Error Prone can't see the use.
    @SuppressWarnings("UnusedVariable")
    @Mock
    private ApplicationEventPublisher eventPublisher;

    // Consumed by @InjectMocks via reflection — Error Prone can't see the use.
    @SuppressWarnings("UnusedVariable")
    @Mock
    private TaskStore taskStore;

    // Consumed by @InjectMocks via reflection — Error Prone can't see the use.
    @SuppressWarnings("UnusedVariable")
    @Mock
    private CollaboratorPermissionService collaboratorPermissions;

    @InjectMocks
    private PullRequestService pullRequestService;

    @BeforeEach
    void stubPatResolver()
    {
        Mockito.lenient().when(patResolver.resolve(anyString())).thenReturn("pat");
        Mockito.lenient().when(patResolver.resolve()).thenReturn("pat");
    }

    // ── searchRelevantForDashboard: notifications backstop ─────────────────────

    @Test
    void testDashboardSweepIncludesReviewRequestSearchDropped()
    {
        // The failing case: GitHub's search index omits a live review request,
        // but the notifications feed (which still sends the email) has it.
        PullRequestService service = dashboardService();
        stubEmptySearches();
        PullRequestRef ref = PullRequestRef.of("acme", "widget", 3405);
        when(gitHub.fetchAttentionPrRefs("pat")).thenReturn(List.of(ref));
        when(gitHub.getPullRequest("pat", ref))
                .thenReturn(samplePr("acme/widget", 3405));

        List<PullRequest> result = service.searchRelevantForDashboard();

        assertThat(result).singleElement().satisfies(pr -> {
            assertThat(pr.repo()).isEqualTo("acme/widget");
            assertThat(pr.number()).isEqualTo(3405);
            assertThat(pr.origin()).isEqualTo(PullRequest.Origin.REVIEW_REQUESTED);
        });
    }

    @Test
    void testDashboardSweepDoesNotRefetchNotificationAlreadyInSearch()
    {
        // Notifications overlap heavily with search; a PR already surfaced by
        // the review-requested search must not be fetched again (deduped by
        // repo#number) and must not appear twice.
        PullRequestService service = dashboardService();
        PullRequest alreadyFound = samplePr("owner/repo", 7);
        when(gitHub.searchPullRequestsPaged(anyString(), argThat(q -> q != null && q.contains("review-requested")),
                anyInt(), anyInt(), any(), any()))
                .thenReturn(new PullRequestHistoryPage(List.of(alreadyFound), 1, 100, 1, false));
        when(gitHub.searchPullRequestsPaged(anyString(), argThat(q -> q == null || !q.contains("review-requested")),
                anyInt(), anyInt(), any(), any()))
                .thenReturn(new PullRequestHistoryPage(List.of(), 1, 100, 0, false));
        when(gitHub.searchPullRequests(anyString(), anyString())).thenReturn(List.of());
        when(gitHub.fetchAttentionPrRefs("pat"))
                .thenReturn(List.of(PullRequestRef.of("owner", "repo", 7)));

        List<PullRequest> result = service.searchRelevantForDashboard();

        assertThat(result).singleElement().satisfies(pr -> assertThat(pr.number()).isEqualTo(7));
        verify(gitHub, never()).getPullRequest(anyString(), any());
    }

    @Test
    void testDashboardSweepExcludesClosedNotificationPr()
    {
        // Notifications (all=true) still list a review request whose PR has
        // since closed/merged. The review-requested search is is:open, so a
        // closed PR must not leak into the "To review" set via this path.
        PullRequestService service = dashboardService();
        stubEmptySearches();
        PullRequestRef ref = PullRequestRef.of("acme", "widget", 3376);
        when(gitHub.fetchAttentionPrRefs("pat")).thenReturn(List.of(ref));
        when(gitHub.getPullRequest("pat", ref))
                .thenReturn(samplePr("acme/widget", 3376, "closed"));

        assertThat(service.searchRelevantForDashboard()).isEmpty();
    }

    @Test
    void testDashboardSweepThrottlesNotificationPollingAndServesCache()
    {
        // Reduce notification-feed load: two back-to-back sweeps (the second
        // within the poll interval) must hit the feed + per-PR fetch only once,
        // yet both still surface the PR from the cached resolved set.
        PullRequestService service = dashboardService();
        stubEmptySearches();
        PullRequestRef ref = PullRequestRef.of("acme", "widget", 3405);
        when(gitHub.fetchAttentionPrRefs("pat")).thenReturn(List.of(ref));
        when(gitHub.getPullRequest("pat", ref))
                .thenReturn(samplePr("acme/widget", 3405));

        List<PullRequest> first = service.searchRelevantForDashboard();
        List<PullRequest> second = service.searchRelevantForDashboard();

        assertThat(first).singleElement().satisfies(pr -> assertThat(pr.number()).isEqualTo(3405));
        assertThat(second).singleElement().satisfies(pr -> assertThat(pr.number()).isEqualTo(3405));
        verify(gitHub, times(1)).fetchAttentionPrRefs("pat");
        verify(gitHub, times(1)).getPullRequest("pat", ref);
    }

    /** A PullRequestService wired with direct (synchronous) executors so the
     *  fetchRelevant futures actually run under the test — the @InjectMocks
     *  Executor mock would no-op and hang the joins. */
    private PullRequestService dashboardService()
    {
        return new PullRequestService(
                gitHub, store, detailStore, viewStateStore, settingsStore, credentialService,
                responseCache, detailInvalidator, repoListCache, repoMetadataCache, patResolver, eventPublisher,
                taskStore, collaboratorPermissions, Runnable::run, Runnable::run);
    }

    private void stubEmptySearches()
    {
        when(gitHub.searchPullRequestsPaged(anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new PullRequestHistoryPage(List.of(), 1, 100, 0, false));
        when(gitHub.searchPullRequests(anyString(), anyString())).thenReturn(List.of());
    }

    private static PullRequest samplePr(String repo, int number)
    {
        return samplePr(repo, number, "open");
    }

    private static PullRequest samplePr(String repo, int number, String state)
    {
        return new PullRequest(number, repo, number, "title", null, "url",
                null, Instant.parse("2026-07-12T00:00:00Z"), PullRequest.Origin.AUTHORED,
                ImmutableList.of(), null, false, null, null, null, ImmutableList.of(),
                null, 0, 0, 0, null,
                state, null, null, null, null, null, null,
                null, null, null);
    }

    // ── listPullRequests ───────────────────────────────────────────────────────

    @Test
    void testListPullRequestsReadsFromStore()
    {
        when(store.findAll()).thenReturn(ImmutableList.of());
        when(settingsStore.get(PR_SORT_ORDER)).thenReturn(Optional.of("updated-desc"));

        pullRequestService.listPullRequests();

        verify(store).findAll();
        verify(settingsStore).get(PR_SORT_ORDER);
    }

    @Test
    void testListPullRequestsDefaultsToSmartWhenNoSetting()
    {
        when(store.findAll()).thenReturn(ImmutableList.of());
        when(settingsStore.get(PR_SORT_ORDER)).thenReturn(Optional.empty());

        pullRequestService.listPullRequests();

        verify(store).findAll();
    }

    // ── getPullRequestDetail input validation ──────────────────────────────────

    @Test
    void testGetPullRequestDetailNoSlashThrows400()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDetail("just-a-name", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testGetPullRequestDetailBlankOwnerThrows400()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDetail("/repo", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testGetPullRequestDetailBlankRepoThrows400()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDetail("owner/", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testGetPullRequestDetailReturnsFromCacheWhenAvailable()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of("bug"), false, true, "clean", 10, 3, 2, 1,
                ImmutableList.of("alice"), "sha",
                "feat/foo", "owner/my-repo", "main", "owner/my-repo");
        StoredPrDetail stored = new StoredPrDetail(
                raw,
                ImmutableList.of(new PrReviewState("alice", "APPROVED")),
                ImmutableList.of(new PullRequestDetail.ChangedFile("file.txt", 5, 2, "modified")),
                ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(null, null, "completed", "success", null, null, null)),
                ImmutableList.of(),
                ImmutableList.of());
        when(store.findIdByRepoAndNumber("owner/my-repo", 42)).thenReturn(Optional.of(7L));
        when(detailStore.find(7L)).thenReturn(Optional.of(stored));
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "my-repo")), any()))
                .thenReturn(true);

        PullRequestDetail result = pullRequestService.getPullRequestDetail("owner/my-repo", 42);

        assertThat(result.repo()).isEqualTo("owner/my-repo");
        assertThat(result.number()).isEqualTo(42);
        assertThat(result.body()).isEqualTo("body");
        assertThat(result.approvalCount()).isEqualTo(1);
        assertThat(result.changesRequestedCount()).isZero();
        assertThat(result.ciStatus()).isEqualTo(PASSING);
        assertThat(result.files()).hasSize(1);
        assertThat(result.viewerCanWrite()).isTrue();
        verify(responseCache).getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "my-repo")), any());
    }

    @Test
    void testFetchFreshPullRequestDetailInvalidatesBeforeReading()
    {
        PullRequestService service = dashboardService();
        PrRawDetail staleRaw = new PrRawDetail(
                "stale", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        StoredPrDetail stale = new StoredPrDetail(
                staleRaw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        PrRawDetail liveRaw = new PrRawDetail(
                "live", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        AtomicBoolean invalidated = new AtomicBoolean();

        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(7L));
        when(detailStore.find(7L)).thenAnswer(ignored ->
                invalidated.get() ? Optional.empty() : Optional.of(stale));
        doAnswer(ignored -> {
            invalidated.set(true);
            return null;
        }).when(detailInvalidator).invalidate("owner/repo", 7);
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any()))
                .thenReturn(true);
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(liveRaw);

        PullRequestDetail result = service.fetchFreshPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("live");
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(gitHub).fetchPrDetail("pat", PullRequestRef.of("owner", "repo", 7));
    }

    @Test
    void testGetPullRequestCiSnapshotReadsViewerPermissionFromCache()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(raw);
        when(gitHub.fetchPrCheckRunsStrict("pat", "owner", "repo", "sha")).thenReturn(ImmutableList.of());
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any()))
                .thenReturn(true);

        PrCiSnapshot result = pullRequestService.getPullRequestCiSnapshot("owner/repo", 7);

        assertThat(result.viewerCanWrite()).isTrue();
        verify(responseCache).getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any());
    }

    @Test
    void testGetPullRequestCiSnapshotRefreshesCachedDetailCheckRuns()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        // Cached detail blob still holds the pre-rerun failure.
        StoredPrDetail stale = new StoredPrDetail(
                raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(1L, "build", "completed", "failure", null, null, null)),
                ImmutableList.of(), ImmutableList.of());
        // GitHub now reports that check re-running.
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(raw);
        when(gitHub.fetchPrCheckRunsStrict("pat", "owner", "repo", "sha"))
                .thenReturn(ImmutableList.of(new PrCheckRunState(1L, "build", "in_progress", null, null, null, null)));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(7L));
        when(detailStore.find(7L)).thenReturn(Optional.of(stale));
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any()))
                .thenReturn(true);

        PrCiSnapshot result = pullRequestService.getPullRequestCiSnapshot("owner/repo", 7);

        // Live aggregate is PENDING, and the cached blob is rewritten with the
        // fresh runs so the detail-page poll won't revert the pill to FAILING.
        assertThat(result.ciStatus()).isEqualTo(PENDING);
        verify(detailStore).save(eq(7L), argThat(d ->
                d.checkRuns().size() == 1 && "in_progress".equals(d.checkRuns().get(0).status())));
    }

    @Test
    void testGetPullRequestCiSnapshotDoesNotPersistAFailedChecksRead()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(raw);
        when(gitHub.fetchPrCheckRunsStrict("pat", "owner", "repo", "sha"))
                .thenThrow(new RuntimeException("checks unavailable"));

        assertThatThrownBy(() -> pullRequestService.getPullRequestCiSnapshot("owner/repo", 7))
                .hasMessageContaining("checks unavailable");

        verify(store, never()).updateCiStatus(anyLong(), any());
        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testRefreshPullRequestDetailRefreshesChecksWhenPrEtagIsUnchanged()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        StoredPrDetail stale = new StoredPrDetail(
                raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(
                        1L, "build", "in_progress", null, null, null, null)),
                ImmutableList.of(), ImmutableList.of());
        AtomicReference<StoredPrDetail> cached = new AtomicReference<>(stale);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(7L));
        when(detailStore.find(7L)).thenAnswer(ignored -> Optional.of(cached.get()));
        doAnswer(invocation -> {
            cached.set(invocation.getArgument(1, StoredPrDetail.class));
            return null;
        }).when(detailStore).save(eq(7L), any(StoredPrDetail.class));
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any()))
                .thenReturn(true);
        when(gitHub.probeChangedSinceEtag(eq("pat"), any(PullRequestRef.class), eq((String) null)))
                .thenReturn(new PullRequestRepository.ProbeResult(true, "etag"));
        when(gitHub.probeChangedSinceEtag(eq("pat"), any(PullRequestRef.class), eq("etag")))
                .thenReturn(new PullRequestRepository.ProbeResult(false, "etag"));
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(raw);
        when(gitHub.fetchPrCheckRunsStrict("pat", "owner", "repo", "sha"))
                .thenReturn(ImmutableList.of(new PrCheckRunState(
                        1L, "build", "completed", "failure", null, null, null)));

        // First probe seeds the ETag; the second simulates an unchanged PR
        // resource whose separate Actions check run has finished.
        pullRequestService.refreshPullRequestDetail("owner/repo", 7);
        PullRequestDetail result = pullRequestService.refreshPullRequestDetail("owner/repo", 7);

        assertThat(result.ciStatus()).isEqualTo(FAILING);
        verify(gitHub).fetchPrCheckRunsStrict("pat", "owner", "repo", "sha");
        verify(store).updateCiStatus(7L, FAILING);
    }

    @Test
    void testGetCheckRunLogFetchesFromGitHubOnEveryCall()
    {
        RepoRef repo = RepoRef.of("owner", "repo");
        when(gitHub.fetchCheckRunLog("pat", repo, 99L))
                .thenReturn(Optional.of("first log"), Optional.of("second log"));

        assertThat(pullRequestService.getCheckRunLog("owner/repo", 99L)).isEqualTo("first log");
        assertThat(pullRequestService.getCheckRunLog("owner/repo", 99L)).isEqualTo("second log");

        verify(gitHub, times(2)).fetchCheckRunLog("pat", repo, 99L);
    }

    // ── countApprovals ─────────────────────────────────────────────────────────

    @Test
    void testCountApprovalsEmpty()
    {
        assertThat(PullRequestDetailMapper.countApprovals(ImmutableList.of())).isZero();
    }

    @Test
    void testCountApprovalsSingleApproval()
    {
        assertThat(PullRequestDetailMapper.countApprovals(ImmutableList.of(
                new PrReviewState("alice", "APPROVED")))).isEqualTo(1);
    }

    @Test
    void testCountApprovalsTwoApprovals()
    {
        assertThat(PullRequestDetailMapper.countApprovals(ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "APPROVED")))).isEqualTo(2);
    }

    @Test
    void testCountApprovalsIgnoresOtherStates()
    {
        assertThat(PullRequestDetailMapper.countApprovals(ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "CHANGES_REQUESTED"),
                new PrReviewState("carol", "COMMENTED")))).isEqualTo(1);
    }

    // ── countChangesRequested ──────────────────────────────────────────────────

    @Test
    void testCountChangesRequestedEmpty()
    {
        assertThat(PullRequestDetailMapper.countChangesRequested(ImmutableList.of())).isZero();
    }

    @Test
    void testCountChangesRequestedSingle()
    {
        assertThat(PullRequestDetailMapper.countChangesRequested(ImmutableList.of(
                new PrReviewState("alice", "CHANGES_REQUESTED")))).isEqualTo(1);
    }

    @Test
    void testCountChangesRequestedTwoDistinct()
    {
        assertThat(PullRequestDetailMapper.countChangesRequested(ImmutableList.of(
                new PrReviewState("alice", "CHANGES_REQUESTED"),
                new PrReviewState("bob", "CHANGES_REQUESTED")))).isEqualTo(2);
    }

    @Test
    void testCountChangesRequestedIgnoresApproved()
    {
        assertThat(PullRequestDetailMapper.countChangesRequested(ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "CHANGES_REQUESTED")))).isEqualTo(1);
    }

    // ── aggregateCiStatus ──────────────────────────────────────────────────────

    @Test
    void testAggregateCiStatusEmptyReturnsNone()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of())).isEqualTo(NONE);
    }

    @Test
    void testAggregateCiStatusAllSuccessReturnsPassing()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "completed", "success", null, null, null),
                new PrCheckRunState(null, null, "completed", "success", null, null, null)))).isEqualTo(PASSING);
    }

    @Test
    void testAggregateCiStatusAnyFailureReturnsFailing()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "completed", "success", null, null, null),
                new PrCheckRunState(null, null, "completed", "failure", null, null, null)))).isEqualTo(FAILING);
    }

    @Test
    void testAggregateCiStatusCancelledReturnsFailing()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "completed", "cancelled", null, null, null)))).isEqualTo(FAILING);
    }

    @Test
    void testAggregateCiStatusInProgressReturnsPending()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "in_progress", null, null, null, null)))).isEqualTo(PENDING);
    }

    @Test
    void testAggregateCiStatusQueuedReturnsPending()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "queued", null, null, null, null)))).isEqualTo(PENDING);
    }

    @Test
    void testAggregateCiStatusFailureTakesPriorityOverPending()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "in_progress", null, null, null, null),
                new PrCheckRunState(null, null, "completed", "failure", null, null, null)))).isEqualTo(FAILING);
    }

    // ── toActivityItems ────────────────────────────────────────────────────────

    @Test
    void testToActivityItemsEmpty()
    {
        assertThat(PullRequestDetailMapper.toActivityItems(ImmutableList.of())).isEmpty();
    }

    @Test
    void testToActivityItemsUninterestingEventsFiltered()
    {
        // "labeled" moved to the interesting set alongside review_requested/
        // head_ref_force_pushed/etc — "subscribed" is a real GitHub timeline
        // event type that still has no UI story, so it stays the filtered case.
        PrTimelineEvent event = new PrTimelineEvent(null, "subscribed", "alice", null, Instant.now(), null, null, null, null, null, null, Reactions.EMPTY);
        assertThat(PullRequestDetailMapper.toActivityItems(ImmutableList.of(event))).isEmpty();
    }

    @Test
    void testToActivityItemsInterestingEventsKept()
    {
        Instant now = Instant.now();
        PrTimelineEvent reviewed = new PrTimelineEvent(null, "reviewed", "alice", "APPROVED", now, null, null, null, null, null, null, Reactions.EMPTY);
        PrTimelineEvent commented = new PrTimelineEvent(null, "commented", "bob", null, now, null, null, null, null, null, null, Reactions.EMPTY);
        List<PullRequestDetail.ActivityItem> items =
                PullRequestDetailMapper.toActivityItems(ImmutableList.of(reviewed, commented));
        assertThat(items).hasSize(2);
    }

    @Test
    void testToActivityItemsResultIsReversedMostRecentFirst()
    {
        Instant now = Instant.now();
        PrTimelineEvent first = new PrTimelineEvent(null, "commented", "first", null, now, null, null, null, null, null, null, Reactions.EMPTY);
        PrTimelineEvent second = new PrTimelineEvent(null, "commented", "second", null, now.plusSeconds(60), null, null, null, null, null, null, Reactions.EMPTY);
        List<PullRequestDetail.ActivityItem> items =
                PullRequestDetailMapper.toActivityItems(ImmutableList.of(first, second));
        assertThat(items.get(0).actor()).isEqualTo("second");
        assertThat(items.get(1).actor()).isEqualTo("first");
    }

    @Test
    void testToActivityItemsKeepsAllEventsNewestFirst()
    {
        // No cap — long-lived PRs can have 300+ events and the user
        // expects to see them all. Verify we keep every event and
        // sort newest-first.
        Instant base = Instant.now();
        List<PrTimelineEvent> events = Lists.newArrayList();
        for (int i = 0; i < 500; i++) {
            events.add(new PrTimelineEvent(null, "commented", "user" + i, null,
                    base.plusSeconds(i), null, null, null, null, null, null, Reactions.EMPTY));
        }
        List<PullRequestDetail.ActivityItem> items = PullRequestDetailMapper.toActivityItems(events);
        assertThat(items).hasSize(500);
        assertThat(items.get(0).actor()).isEqualTo("user499");
        assertThat(items.get(499).actor()).isEqualTo("user0");
    }

    @Test
    void testToActivityItemsMapsEventTypeDirectly()
    {
        PrTimelineEvent event = new PrTimelineEvent(null, "merged", "alice", null, Instant.now(), null, null, null, null, null, null, Reactions.EMPTY);
        List<PullRequestDetail.ActivityItem> items =
                PullRequestDetailMapper.toActivityItems(ImmutableList.of(event));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).eventType()).isEqualTo("merged");
        assertThat(items.get(0).actor()).isEqualTo("alice");
    }

    // ── deriveHandledActionFromReviews ─────────────────────────────────────────

    @Test
    void testDeriveHandledActionReturnsNullForNullLogin()
    {
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, null)).isNull();
    }

    @Test
    void testDeriveHandledActionReturnsNullForEmptyReviews()
    {
        assertThat(PullRequestService.deriveHandledActionFromReviews(ImmutableList.of(), "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionReturnsNullWhenUserHasNoReview()
    {
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("bob", "APPROVED"),
                new PrReviewState("carol", "CHANGES_REQUESTED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionReturnsApprovedWhenUserApproved()
    {
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("bob", "COMMENTED"),
                new PrReviewState("alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    @Test
    void testDeriveHandledActionReturnsChangesRequested()
    {
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("alice", "CHANGES_REQUESTED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.CHANGES_REQUESTED);
    }

    @Test
    void testDeriveHandledActionMatchesLoginCaseInsensitively()
    {
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("Alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    @Test
    void testDeriveHandledActionLaterReviewWins()
    {
        // User first asked for changes, then later approved.
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("alice", "CHANGES_REQUESTED"),
                new PrReviewState("alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    @Test
    void testDeriveHandledActionDismissalResetsState()
    {
        // Approved → dismissed → (nothing). User has no effective stance.
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("alice", "DISMISSED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionIgnoresCommentedReviews()
    {
        // Drive-by COMMENTED shouldn't auto-mark as handled.
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("alice", "COMMENTED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionSkipsOtherUsersEntries()
    {
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("bob", "CHANGES_REQUESTED"),
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("carol", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    // ── commentOnPullRequest ───────────────────────────────────────────────────

    @Test
    void testCommentOnPullRequestPostsCommentOnly()
    {
        pullRequestService.commentOnPullRequest("owner/repo", 7, 99L, "LGTM", false);

        verify(gitHub).createIssueComment(eq("pat"), any(PullRequestRef.class), eq("LGTM"));
        verify(gitHub, never()).updatePullRequest(anyString(), any(), any());
        verify(viewStateStore, never()).markReviewed(anyLong(), any());
        verifyNoInteractions(responseCache);
        verifyNoInteractions(detailInvalidator);
        verifyNoInteractions(repoListCache);
    }

    @Test
    void testCommentOnPullRequestClosesWithoutCommentWhenBodyBlank()
    {
        pullRequestService.commentOnPullRequest("owner/repo", 7, 99L, "   ", true);

        verify(gitHub, never()).createIssueComment(anyString(), any(), anyString());
        verify(gitHub).updatePullRequest(eq("pat"), any(PullRequestRef.class), any(UpdatePullRequestCommand.class));
        verify(viewStateStore).markReviewed(99L, HandledAction.DISMISSED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void testCommentOnPullRequestPostsCommentThenCloses()
    {
        pullRequestService.commentOnPullRequest("owner/repo", 7, 99L, "not needed anymore", true);

        verify(gitHub).createIssueComment(eq("pat"), any(PullRequestRef.class), eq("not needed anymore"));
        verify(gitHub).updatePullRequest(eq("pat"), any(PullRequestRef.class), any(UpdatePullRequestCommand.class));
        verify(viewStateStore).markReviewed(99L, HandledAction.DISMISSED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void taskOwnedCloseResolvesLegacyIdAndSealsImmediately()
    {
        Task owner = Mockito.mock(Task.class);
        when(owner.id()).thenReturn("task-1");
        when(taskStore.findTasksByPrRef("owner/repo#7")).thenReturn(List.of(owner));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(77L));

        pullRequestService.commentOnPullRequest("owner/repo", 7, 0L, "", true);

        verify(viewStateStore).markReviewed(77L, HandledAction.DISMISSED);
        verify(viewStateStore, never()).markReviewed(eq(0L), any());
        verify(eventPublisher).publishEvent(argThat((Object event) -> event instanceof PullRequestClosedEvent closed
                && "owner/repo".equals(closed.repoFullName()) && closed.prNumber() == 7));
    }

    @Test
    void testCommentOnPullRequestAppendsCommentToCachedTimeline()
    {
        PrTimelineEvent posted = new PrTimelineEvent(
                555L, "commented", "bob", null,
                Instant.parse("2026-05-08T01:00:00Z"), "LGTM",
                null, null, null, null, "MEMBER", Reactions.EMPTY);
        when(gitHub.createIssueComment(eq("pat"), any(PullRequestRef.class), eq("LGTM")))
                .thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of())));

        pullRequestService.commentOnPullRequest("owner/repo", 7, 99L, "LGTM", false);

        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        assertThat(captor.getValue().timeline())
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.githubId()).isEqualTo(555L);
                    assertThat(e.event()).isEqualTo("commented");
                    assertThat(e.body()).isEqualTo("LGTM");
                });
    }

    @Test
    void testFetchNewCommentsMapsIssueCommentsToActivityItems()
    {
        when(gitHub.fetchPrIssueComments(eq("pat"), any(PullRequestRef.class),
                eq(Instant.parse("2026-05-08T00:00:00Z"))))
                .thenReturn(ImmutableList.of(new PrTimelineEvent(
                        777L, "commented", "carol", null,
                        Instant.parse("2026-05-08T01:00:00Z"), "ship it",
                        null, null, null, null, "MEMBER", Reactions.EMPTY)));

        List<PullRequestDetail.ActivityItem> items = pullRequestService.fetchNewComments(
                "owner/repo", 7, Instant.parse("2026-05-08T00:00:00Z"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.githubId()).isEqualTo(777L);
            assertThat(item.eventType()).isEqualTo("commented");
            assertThat(item.actor()).isEqualTo("carol");
            assertThat(item.body()).isEqualTo("ship it");
        });
    }

    @Test
    void testCommentOnPullRequestNoOpWhenBlankBodyAndNoClose()
    {
        pullRequestService.commentOnPullRequest("owner/repo", 7, 99L, "", false);

        verifyNoInteractions(gitHub);
        verify(viewStateStore, never()).markReviewed(anyLong(), any());
        verifyNoInteractions(responseCache);
        verifyNoInteractions(detailInvalidator);
    }

    // ── structural mutation invalidation ──────────────────────────────────────

    @Test
    void testSetPullRequestDraftInvalidatesDetail()
    {
        pullRequestService.setPullRequestDraft("owner/repo", 7, true);

        verify(gitHub).setPullRequestDraft(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), eq(true));
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void testAddRequestedReviewerInvalidatesDetail()
    {
        pullRequestService.addRequestedReviewer("owner/repo", 7, " alice ");

        verify(gitHub).requestReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any());
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void testRemoveRequestedReviewerInvalidatesDetail()
    {
        pullRequestService.removeRequestedReviewer("owner/repo", 7, "alice");

        verify(gitHub).removeRequestedReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any());
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void testCreateInlineReviewCommentUsesFreshHeadAndInvalidatesDetail()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.fetchPrDetail("pat", ref)).thenReturn(rawWithHead("fresh-head"));

        pullRequestService.createInlineReviewComment(
                "owner/repo",
                7,
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "abc123",
                null,
                null);

        verify(gitHub).fetchPrDetail("pat", ref);
        verify(gitHub).createInlineReviewComment(
                "pat",
                ref,
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "fresh-head",
                null,
                null);
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testCreateInlineReviewCommentRejectsMissingFreshHeadBeforePosting()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.fetchPrDetail("pat", ref)).thenReturn(rawWithHead(" "));

        assertThatThrownBy(() -> pullRequestService.createInlineReviewComment(
                "owner/repo", 7, "please fix", "src/Main.java", 12, "RIGHT",
                "stale-client-head", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("head SHA is unavailable")
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_GATEWAY.value()));

        verify(gitHub, never()).createInlineReviewComment(
                anyString(), any(), anyString(), anyString(), anyInt(), anyString(),
                anyString(), any(), any());
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testUpdatePullRequestBodyInvalidatesDetail()
    {
        pullRequestService.updatePullRequestBody("owner/repo", 7, "new body");

        verify(gitHub).updatePullRequest(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any(UpdatePullRequestCommand.class));
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    // ── approvePullRequest / mergePullRequest / markHandled / reopen ───────────

    @Test
    void testApprovePullRequestMarksReviewedApproved()
    {
        pullRequestService.approvePullRequest("owner/repo", 7, 99L);

        verify(gitHub).createReview(eq("pat"), any(PullRequestRef.class), any());
        verify(viewStateStore).markReviewed(99L, HandledAction.APPROVED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void testMergePullRequestUsesRebaseAndMarksMerged()
    {
        // The service currently defaults to rebase. If this ever changes, this
        // test is the early-warning: it locks the strategy in so regressions
        // don't silently switch back to a merge-commit.
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    MergePullRequestCommand cmd = invocation.getArgument(2);
                    matcher.capture(cmd);
                    return null;
                });

        pullRequestService.mergePullRequest("owner/repo", 7, 99L, null);

        assertThat(matcher.captured).isNotNull();
        assertThat(matcher.captured.mergeMethod()).isEqualTo("rebase");
        verify(viewStateStore).markReviewed(99L, HandledAction.MERGED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
        verify(repoListCache).invalidatePulls(RepoRef.of("owner", "repo"));
    }

    @Test
    void testMergePullRequestPropagatesSquashStrategy()
    {
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    matcher.capture(invocation.getArgument(2));
                    return null;
                });

        pullRequestService.mergePullRequest("owner/repo", 7, 99L, "squash");

        assertThat(matcher.captured.mergeMethod()).isEqualTo("squash");
    }

    @Test
    void testMergePullRequestPropagatesMergeCommitStrategy()
    {
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    matcher.capture(invocation.getArgument(2));
                    return null;
                });

        pullRequestService.mergePullRequest("owner/repo", 7, 99L, "merge");

        assertThat(matcher.captured.mergeMethod()).isEqualTo("merge");
    }

    @Test
    void testMergePullRequestUnknownStrategyFallsBackToRebase()
    {
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    matcher.capture(invocation.getArgument(2));
                    return null;
                });

        pullRequestService.mergePullRequest("owner/repo", 7, 99L, "garbage");

        assertThat(matcher.captured.mergeMethod()).isEqualTo("rebase");
    }

    @Test
    void taskOwnedPullRequestCannotUseLegacyMergePath()
    {
        when(taskStore.findTasksByPrRef("owner/repo#7"))
                .thenReturn(List.of(Mockito.mock(Task.class)));

        assertThatThrownBy(() -> pullRequestService.mergePullRequest(
                "owner/repo", 7, 99L, "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("task Merge / Close gate");

        verify(gitHub, never()).mergePullRequest(anyString(), any(), any());
        verify(gitHub, never()).enqueuePullRequest(anyString(), anyString());
    }

    @Test
    void taskOwnedPullRequestCannotEnableGitHubAutoMerge()
    {
        when(taskStore.findTasksByPrRef("owner/repo#7"))
                .thenReturn(List.of(Mockito.mock(Task.class)));

        assertThatThrownBy(() -> pullRequestService.enableAutoMerge(
                "owner/repo", 7, 99L, "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("task Merge / Close gate");

        verify(gitHub, never()).enableAutoMerge(anyString(), any(), anyString());
    }

    @Test
    void testMarkHandledUpdatesViewStateOnlyNoGitHubCall()
    {
        pullRequestService.markHandled(99L, HandledAction.MANUAL);

        verify(viewStateStore).markReviewed(99L, HandledAction.MANUAL);
        verifyNoInteractions(gitHub);
    }

    @Test
    void testReopenDelegatesToViewState()
    {
        pullRequestService.reopen(99L);

        verify(viewStateStore).reopen(99L);
        verifyNoInteractions(gitHub);
    }

    /** Simple capturing helper for the rebase-default test. */
    private static final class MergeResultArgMatcher
    {
        MergePullRequestCommand captured;

        void capture(MergePullRequestCommand cmd)
        {
            this.captured = cmd;
        }
    }

    // ── markViewed ─────────────────────────────────────────────────────────────

    @Test
    void testMarkViewedDelegatesToViewStateStore()
    {
        pullRequestService.markViewed(42L);
        verify(viewStateStore).markViewed(42L);
    }

    // ── getPullRequestDiffFiles / getPullRequestCommits ────────────────────────

    @Test
    void testGetPullRequestDiffFilesDelegatesToClient()
    {
        when(gitHub.fetchPrDiffFiles(eq("pat"), any(PullRequestRef.class)))
                .thenReturn(ImmutableList.of());

        List<?> result = pullRequestService.getPullRequestDiffFiles("owner/repo", 7);

        assertThat(result).isEmpty();
        verify(gitHub).fetchPrDiffFiles(eq("pat"), any(PullRequestRef.class));
    }

    @Test
    void testGetPullRequestCommitsDelegatesToClient()
    {
        when(gitHub.fetchPrCommits(eq("pat"), any(PullRequestRef.class)))
                .thenReturn(ImmutableList.of());

        List<?> result = pullRequestService.getPullRequestCommits("owner/repo", 7);

        assertThat(result).isEmpty();
        verify(gitHub).fetchPrCommits(eq("pat"), any(PullRequestRef.class));
    }

    @Test
    void testGetCommitDiffFilesDelegatesThroughResponseCache()
    {
        List<DiffFile> files = ImmutableList.of(new DiffFile("README.md", "modified", 1, 0, "@@"));
        when(responseCache.getCommitDiffFiles(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), eq("sha"), any()))
                .thenReturn(files);

        List<DiffFile> result = pullRequestService.getCommitDiffFiles("owner/repo", 7, "sha");

        assertThat(result).isSameAs(files);
        verify(responseCache).getCommitDiffFiles(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), eq("sha"), any());
        verify(gitHub, never()).fetchCommitDiffFiles(anyString(), any(), anyString());
    }

    @Test
    void testGetFileBlobLinesDelegatesThroughResponseCache()
    {
        List<String> lines = ImmutableList.of("hello");
        when(responseCache.getFileBlobLines(eq("pat"), eq(RepoRef.of("owner", "repo")), eq("README.md"), eq("sha"), any()))
                .thenReturn(lines);

        List<String> result = pullRequestService.getFileBlobLines("owner/repo", "README.md", "sha");

        assertThat(result).isSameAs(lines);
        verify(responseCache).getFileBlobLines(eq("pat"), eq(RepoRef.of("owner", "repo")), eq("README.md"), eq("sha"), any());
        verify(gitHub, never()).fetchFileBlobLines(anyString(), any(), anyString(), anyString());
    }

    @Test
    void testGetSuggestedReviewersDelegatesThroughResponseCache()
    {
        List<SuggestedReviewer> reviewers = ImmutableList.of(new SuggestedReviewer("alice", null, "Alice", false, true));
        when(responseCache.getSuggestedReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any()))
                .thenReturn(reviewers);

        List<SuggestedReviewer> result = pullRequestService.getSuggestedReviewers("owner/repo", 7);

        assertThat(result).isSameAs(reviewers);
        verify(responseCache).getSuggestedReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any());
        verify(gitHub, never()).fetchSuggestedReviewers(anyString(), any());
    }

    @Test
    void testGetPullRequestDiffFilesRejectsInvalidRepo()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDiffFiles("no-slash", 7))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void testExtractClosingReferencesPicksUpAllKeywordVariants()
    {
        String body = "Closes #1 and fixes #2.\nResolved #3 yesterday — also closed #4.\n"
                + "Fix: #5 plus FIXES #6.";
        assertThat(PullRequestTimelineUtil.extractClosingReferences(body, "trinodb", "trino"))
                .containsExactlyInAnyOrder(1, 2, 3, 4, 6);
    }

    @Test
    void testExtractClosingReferencesIgnoresBareHashRefs()
    {
        String body = "Reverts #1\nDiscussed in #99\nSee also #100";
        assertThat(PullRequestTimelineUtil.extractClosingReferences(body, "trinodb", "trino")).isEmpty();
    }

    @Test
    void testExtractClosingReferencesHandlesNullAndBlank()
    {
        assertThat(PullRequestTimelineUtil.extractClosingReferences(null, "trinodb", "trino")).isEmpty();
        assertThat(PullRequestTimelineUtil.extractClosingReferences("", "trinodb", "trino")).isEmpty();
        assertThat(PullRequestTimelineUtil.extractClosingReferences("   ", "trinodb", "trino")).isEmpty();
    }

    @Test
    void testExtractClosingReferencesAcceptsSameRepoUrlForm()
    {
        String body = "Fixes https://github.com/trinodb/trino/issues/1234\n"
                + "Also closes http://github.com/trinodb/trino/issues/5678.";
        assertThat(PullRequestTimelineUtil.extractClosingReferences(body, "trinodb", "trino"))
                .containsExactlyInAnyOrder(1234, 5678);
    }

    @Test
    void testExtractClosingReferencesSkipsCrossRepoUrlForm()
    {
        // Cross-repo: URL points at owner/repo that isn't the PR's. Skip
        // it — fetching that number against the PR's repo would silently
        // return the wrong issue or 404.
        String body = "Fixes https://github.com/other/repo/issues/9";
        assertThat(PullRequestTimelineUtil.extractClosingReferences(body, "trinodb", "trino")).isEmpty();
    }

    @Test
    void testExtractClosingReferencesUrlAndHashFormsCoexist()
    {
        String body = "Closes #1, fixes https://github.com/trinodb/trino/issues/2, "
                + "resolves #3.";
        assertThat(PullRequestTimelineUtil.extractClosingReferences(body, "trinodb", "trino"))
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    // ── Conversation mutation freshness ────────────────────────────────────────
    // Conversation mutations intentionally stay optimistic: they write to
    // GitHub, but do not invalidate the PR-detail caches. This keeps a
    // stale SQLite snapshot from overwriting local optimistic UI.

    @Test
    void testReplyToReviewThreadAppendsReplyToCachedDetail()
    {
        PrReviewThreadMessage reply = new PrReviewThreadMessage(
                999L,
                4357983764L,
                null,
                "bob",
                "thanks",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T01:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                null,
                null, null);
        when(gitHub.replyToReviewComment(
                "pat",
                PullRequestRef.of("trinodb", "trino", 7),
                4357983764L,
                "thanks")).thenReturn(reply);
        when(store.findIdByRepoAndNumber("trinodb/trino", 7)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of())));

        pullRequestService.replyToReviewThread("trinodb/trino", 7, 4357983764L, "thanks");

        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        assertThat(captor.getValue().reviewComments())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.githubId()).isEqualTo(999L);
                    assertThat(m.body()).isEqualTo("thanks");
                    assertThat(m.inReplyTo()).isEqualTo(4357983764L);
                });
    }

    @Test
    void testReplyToReviewThreadSkipsCachePatchWhenPrUnknown()
    {
        when(gitHub.replyToReviewComment(any(), any(), anyLong(), anyString()))
                .thenReturn(new PrReviewThreadMessage(
                        999L, 4357983764L, null, "bob", "thanks",
                        "src/Main.java", 12, "RIGHT", "@@", "abc123",
                        Instant.parse("2026-05-08T01:00:00Z"), Reactions.EMPTY,
                        false, null, null, null, null, "MEMBER", null, null, null));
        when(store.findIdByRepoAndNumber("trinodb/trino", 7)).thenReturn(Optional.empty());

        pullRequestService.replyToReviewThread("trinodb/trino", 7, 4357983764L, "thanks");

        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testEditIssueCommentPatchesCachedTimelineBody()
    {
        PrTimelineEvent commented = new PrTimelineEvent(
                4357983764L,
                "commented",
                "alice",
                null,
                Instant.parse("2026-05-08T00:00:00Z"),
                "old body",
                null,
                null,
                null,
                null,
                "MEMBER",
                Reactions.EMPTY);
        when(detailStore.findPrIdByIssueCommentId(4357983764L)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(commented),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of())));

        pullRequestService.editIssueComment("trinodb/trino", 4357983764L, "updated");

        verify(gitHub).editIssueComment("pat", "trinodb", "trino", 4357983764L, "updated");
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        assertThat(captor.getValue().timeline().get(0).body()).isEqualTo("updated");
    }

    @Test
    void testEditIssueCommentSkipsCachePatchWhenCommentUnknown()
    {
        when(detailStore.findPrIdByIssueCommentId(4357983764L)).thenReturn(Optional.empty());

        pullRequestService.editIssueComment("trinodb/trino", 4357983764L, "updated");

        verify(gitHub).editIssueComment("pat", "trinodb", "trino", 4357983764L, "updated");
        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testEditReviewCommentPatchesCachedReviewBody()
    {
        PrReviewThreadMessage message = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "old body",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                false, null);
        when(detailStore.findPrIdByReviewCommentId(4357983764L)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(message),
                ImmutableList.of())));

        pullRequestService.editReviewComment("trinodb/trino", 4357983764L, "updated");

        verify(gitHub).editReviewComment("pat", "trinodb", "trino", 4357983764L, "updated");
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        PrReviewThreadMessage patched = captor.getValue().reviewComments().get(0);
        assertThat(patched.body()).isEqualTo("updated");
        assertThat(patched.resolved()).isFalse();
        assertThat(patched.graphqlNodeId()).isEqualTo("thread-node-id");
    }

    @Test
    void testEditReviewCommentSkipsCachePatchWhenCommentUnknown()
    {
        when(detailStore.findPrIdByReviewCommentId(4357983764L)).thenReturn(Optional.empty());

        pullRequestService.editReviewComment("trinodb/trino", 4357983764L, "updated");

        verify(gitHub).editReviewComment("pat", "trinodb", "trino", 4357983764L, "updated");
        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testSetReviewThreadResolvedPatchesCachedDetail()
    {
        PrReviewThreadMessage root = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                false, null);
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of())));

        pullRequestService.setReviewThreadResolved("owner/repo", 123L, 4357983764L, true);

        verify(gitHub).resolveReviewThread("pat", "thread-node-id");
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        PrReviewThreadMessage patched = captor.getValue().reviewComments().get(0);
        assertThat(patched.resolved()).isTrue();
        assertThat(patched.body()).isEqualTo("please fix");
        assertThat(patched.graphqlNodeId()).isEqualTo("thread-node-id");
    }

    @Test
    void testSetReviewThreadUnresolvedFlipsFlagBackToFalse()
    {
        PrReviewThreadMessage root = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                true, null);
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of())));

        pullRequestService.setReviewThreadResolved("owner/repo", 123L, 4357983764L, false);

        verify(gitHub).unresolveReviewThread("pat", "thread-node-id");

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        assertThat(captor.getValue().reviewComments().get(0).resolved()).isFalse();
    }

    @Test
    void testSetReviewThreadResolvedDoesNotPatchWhenGitHubFails()
    {
        PrReviewThreadMessage root = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                false, null);
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of())));
        doThrow(new RuntimeException("GitHub down"))
                .when(gitHub).resolveReviewThread("pat", "thread-node-id");

        assertThatThrownBy(() ->
                pullRequestService.setReviewThreadResolved("owner/repo", 123L, 4357983764L, true))
                .isInstanceOf(RuntimeException.class);

        verify(detailStore, never()).save(anyLong(), any());
    }

    // ── Reaction endpoints ─────────────────────────────────────────────────────
    // Regression guard. addReviewCommentReaction / addIssueCommentReaction
    // used to call parseRef(repo, 0), but PullRequestRef requires
    // number > 0 — so every reaction click returned 400 "number must be
    // positive, got: 0". Now they use parseRepoRef. These tests pin
    // both the happy path and the content-allowlist + repo-format
    // validation so the bug can't slip back.

    @Test
    void testMetadataChoicesCombineCurrentAndAvailableValues()
    {
        PullRequestRef ref = PullRequestRef.of("trinodb", "trino", 4074);
        when(gitHub.fetchIssueDetail("pat", ref.repoRef(), 4074)).thenReturn(metadataIssue());
        when(repoMetadataCache.find("trinodb/trino")).thenReturn(Optional.empty());
        when(gitHub.fetchAssignableUsers("pat", ref.repoRef())).thenReturn(
                ImmutableList.of(new GitHubUserMatch("alice", null, null)));
        when(gitHub.fetchRepoLabels("pat", ref.repoRef())).thenReturn(
                ImmutableList.of(new IssueDetail.Label("jdbc", "007f8b")));

        PullRequestService.MetadataChoices choices =
                pullRequestService.getMetadataChoices("trinodb/trino", 4074);

        assertThat(choices.users()).extracting(GitHubUserMatch::login).containsExactly("alice");
        assertThat(choices.assignees()).containsExactly("alice");
        assertThat(choices.selectedLabels()).containsExactly("jdbc");
        verify(repoMetadataCache).save(
                eq("trinodb/trino"), eq(choices.users()), eq(choices.labels()), any(Instant.class));
    }

    @Test
    void testMetadataChoicesUseFreshRepoCache()
    {
        PullRequestRef ref = PullRequestRef.of("trinodb", "trino", 4074);
        RepoMetadataCacheStore.Snapshot cached = new RepoMetadataCacheStore.Snapshot(
                ImmutableList.of(new GitHubUserMatch("cached-user", null, null)),
                ImmutableList.of(new IssueDetail.Label("cached-label", "123456")),
                Instant.now());
        when(gitHub.fetchIssueDetail("pat", ref.repoRef(), 4074)).thenReturn(metadataIssue());
        when(repoMetadataCache.find("trinodb/trino")).thenReturn(Optional.of(cached));

        PullRequestService.MetadataChoices choices =
                pullRequestService.getMetadataChoices("trinodb/trino", 4074);

        assertThat(choices.users()).extracting(GitHubUserMatch::login).containsExactly("cached-user");
        assertThat(choices.labels()).extracting(IssueDetail.Label::name).containsExactly("cached-label");
        verify(gitHub, never()).fetchAssignableUsers(anyString(), any());
        verify(gitHub, never()).fetchRepoLabels(anyString(), any());
        verify(repoMetadataCache, never()).save(anyString(), any(), any(), any());
    }

    @Test
    void testMetadataChoicesRefreshStaleRepoCache()
    {
        PullRequestRef ref = PullRequestRef.of("trinodb", "trino", 4074);
        RepoMetadataCacheStore.Snapshot stale = new RepoMetadataCacheStore.Snapshot(
                ImmutableList.of(new GitHubUserMatch("stale-user", null, null)),
                ImmutableList.of(new IssueDetail.Label("stale-label", "123456")),
                Instant.now().minus(Duration.ofDays(8)));
        when(gitHub.fetchIssueDetail("pat", ref.repoRef(), 4074)).thenReturn(metadataIssue());
        when(repoMetadataCache.find("trinodb/trino")).thenReturn(Optional.of(stale));
        when(gitHub.fetchAssignableUsers("pat", ref.repoRef())).thenReturn(
                ImmutableList.of(new GitHubUserMatch("fresh-user", null, null)));
        when(gitHub.fetchRepoLabels("pat", ref.repoRef())).thenReturn(
                ImmutableList.of(new IssueDetail.Label("fresh-label", "654321")));

        PullRequestService.MetadataChoices choices =
                pullRequestService.getMetadataChoices("trinodb/trino", 4074);

        assertThat(choices.users()).extracting(GitHubUserMatch::login).containsExactly("fresh-user");
        assertThat(choices.labels()).extracting(IssueDetail.Label::name).containsExactly("fresh-label");
        verify(repoMetadataCache).save(
                eq("trinodb/trino"), eq(choices.users()), eq(choices.labels()), any(Instant.class));
    }

    @Test
    void testAssigneeAndLabelSelectionsForwardToGitHub()
    {
        PullRequestRef ref = PullRequestRef.of("trinodb", "trino", 4074);

        pullRequestService.setPullRequestAssignee("trinodb/trino", 4074, "alice", true);
        pullRequestService.setPullRequestLabel("trinodb/trino", 4074, "jdbc", false);

        verify(gitHub).setPullRequestAssignee("pat", ref, "alice", true);
        verify(gitHub).setPullRequestLabel("pat", ref, "jdbc", false);
    }

    private static IssueDetail metadataIssue()
    {
        return new IssueDetail(
                1L, 4074, "title", "body", "author", null, "open", "url",
                Instant.EPOCH, Instant.EPOCH, null,
                ImmutableList.of(new IssueDetail.Label("jdbc", "007f8b")),
                ImmutableList.of(new IssueDetail.Assignee("alice", null)),
                null, ImmutableList.of(), ImmutableList.of(), false);
    }

    @Test
    void testAddPullRequestReactionForwardsToGitHub()
    {
        pullRequestService.addPullRequestReaction("trinodb/trino", 4074, "heart");

        verify(gitHub).addPullRequestReaction(
                "pat", PullRequestRef.of("trinodb", "trino", 4074), "heart");
    }

    @Test
    void testAddPullRequestReactionRejectsInvalidContent()
    {
        assertThatThrownBy(() ->
                pullRequestService.addPullRequestReaction("trinodb/trino", 4074, "fire"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testAddReviewCommentReactionWithValidRepoForwardsToGitHub()
    {
        pullRequestService.addReviewCommentReaction("trinodb/trino", 4357983764L, "+1");

        verify(gitHub).addReviewCommentReaction("pat", "trinodb", "trino", 4357983764L, "+1");
        verify(responseCache, never()).invalidatePullRequest(any());
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testAddReviewCommentReactionBumpsCachedReactionCount()
    {
        PrReviewThreadMessage message = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                new Reactions(2, 0, 0, 0, 0, 1, 0, 0),
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                false, null);
        when(detailStore.findPrIdByReviewCommentId(4357983764L)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(message),
                ImmutableList.of())));

        pullRequestService.addReviewCommentReaction("trinodb/trino", 4357983764L, "heart");

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        Reactions patched = captor.getValue().reviewComments().get(0).reactions();
        assertThat(patched.heart()).isEqualTo(2);
        assertThat(patched.plusOne()).isEqualTo(2);
    }

    @Test
    void testAddReviewCommentReactionSkipsCachePatchWhenCommentUnknown()
    {
        when(detailStore.findPrIdByReviewCommentId(4357983764L)).thenReturn(Optional.empty());

        pullRequestService.addReviewCommentReaction("trinodb/trino", 4357983764L, "+1");

        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testAddIssueCommentReactionWithValidRepoForwardsToGitHub()
    {
        pullRequestService.addIssueCommentReaction("trinodb/trino", 4357983764L, "heart");

        verify(gitHub).addIssueCommentReaction("pat", "trinodb", "trino", 4357983764L, "heart");
        verify(responseCache, never()).invalidatePullRequest(any());
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testAddIssueCommentReactionBumpsCachedReactionCount()
    {
        PrTimelineEvent commented = new PrTimelineEvent(
                4357983764L,
                "commented",
                "alice",
                null,
                Instant.parse("2026-05-08T00:00:00Z"),
                "looks good",
                null,
                null,
                null,
                null,
                "MEMBER",
                new Reactions(0, 0, 0, 0, 0, 0, 0, 1));
        when(detailStore.findPrIdByIssueCommentId(4357983764L)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(commented),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of())));

        pullRequestService.addIssueCommentReaction("trinodb/trino", 4357983764L, "rocket");

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        Reactions patched = captor.getValue().timeline().get(0).reactions();
        assertThat(patched.rocket()).isEqualTo(1);
        assertThat(patched.eyes()).isEqualTo(1);
    }

    @Test
    void testAddIssueCommentReactionSkipsCachePatchWhenCommentUnknown()
    {
        when(detailStore.findPrIdByIssueCommentId(4357983764L)).thenReturn(Optional.empty());

        pullRequestService.addIssueCommentReaction("trinodb/trino", 4357983764L, "heart");

        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testAddReviewCommentReactionRejectsInvalidContent()
    {
        assertThatThrownBy(() ->
                pullRequestService.addReviewCommentReaction("trinodb/trino", 1L, "fire"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testAddReviewCommentReactionRejectsBadRepoShape()
    {
        assertThatThrownBy(() ->
                pullRequestService.addReviewCommentReaction("no-slash", 1L, "+1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testAddIssueCommentReactionRejectsInvalidContent()
    {
        assertThatThrownBy(() ->
                pullRequestService.addIssueCommentReaction("trinodb/trino", 1L, "thumbs"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    // ── updatePullRequestTitle ─────────────────────────────────────────────────

    @Test
    void testUpdateTitleHappyPathCallsGitHubAndBustsCache()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 42)).thenReturn(Optional.of(7L));
        when(store.findById(7L)).thenReturn(Optional.of(prWithTitle("Old title")));

        PullRequestService.PrTitleUpdate result =
                pullRequestService.updatePullRequestTitle("owner/repo", 42, "  New title  ");

        assertThat(result.number()).isEqualTo(42);
        assertThat(result.title()).isEqualTo("New title");
        assertThat(result.updatedAt()).isNotNull();
        verify(gitHub).updatePullRequest(eq("pat"), any(PullRequestRef.class),
                argThat(cmd -> cmd.title().equals(Optional.of("New title"))));
        verify(detailInvalidator).invalidate("owner/repo", 42);
        verify(repoListCache).invalidatePulls(any());
    }

    @Test
    void testUpdateTitleRejectsBlankTitle()
    {
        assertThatThrownBy(() -> pullRequestService.updatePullRequestTitle("owner/repo", 42, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
        verifyNoInteractions(gitHub);
    }

    @Test
    void testUpdateTitleRejectsTooLongTitle()
    {
        String tooLong = "x".repeat(257);
        assertThatThrownBy(() -> pullRequestService.updatePullRequestTitle("owner/repo", 42, tooLong))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testUpdateTitleRejectsUnchangedTitle()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 42)).thenReturn(Optional.of(7L));
        when(store.findById(7L)).thenReturn(Optional.of(prWithTitle("Same title")));

        assertThatThrownBy(() -> pullRequestService.updatePullRequestTitle("owner/repo", 42, "Same title"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
        verify(gitHub, never()).updatePullRequest(any(), any(), any());
    }

    @Test
    void testUpdateTitle404WhenPrNotCached()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pullRequestService.updatePullRequestTitle("owner/repo", 99, "New"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(NOT_FOUND.value()));
    }

    @Test
    void testUpdateTitleMapsGitHubPermissionFailureTo403()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 42)).thenReturn(Optional.of(7L));
        when(store.findById(7L)).thenReturn(Optional.of(prWithTitle("Old")));
        when(gitHub.updatePullRequest(any(), any(), any()))
                .thenThrow(new ResponseStatusException(FORBIDDEN, "denied"));

        assertThatThrownBy(() -> pullRequestService.updatePullRequestTitle("owner/repo", 42, "New"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(FORBIDDEN.value()));
        verify(detailInvalidator, never()).invalidate(any(), anyInt());
    }

    @Test
    void testUpdateTitleMapsOtherGitHubFailureTo502()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 42)).thenReturn(Optional.of(7L));
        when(store.findById(7L)).thenReturn(Optional.of(prWithTitle("Old")));
        when(gitHub.updatePullRequest(any(), any(), any()))
                .thenThrow(new ResponseStatusException(INTERNAL_SERVER_ERROR, "boom"));

        assertThatThrownBy(() -> pullRequestService.updatePullRequestTitle("owner/repo", 42, "New"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_GATEWAY.value()));
    }

    @Test
    void logExcerptEndsAtTheFailureMarkerNotAtTheCleanupThatFollowsIt()
    {
        // Real Actions shape: ISO timestamp per line, ANSI colour on the
        // command echo, the error, then ~4 lines of post-job cleanup that a
        // naive tail would show instead of the failure.
        String log = """
                2026-07-29T18:00:11.1Z [ERROR] Error executing Maven.
                2026-07-29T18:00:11.2Z [ERROR] Extension foo:0.2.0 could not be resolved
                2026-07-29T18:00:11.3Z ##[error]Process completed with exit code 1.
                2026-07-29T18:00:45.2Z ##[group]Run .github/bin/cleanup.sh
                2026-07-29T18:00:45.3Z [36;1m.github/bin/cleanup.sh[0m
                2026-07-29T18:00:45.4Z Removing credentials config
                2026-07-29T18:00:45.5Z Cleaning up orphan processes
                """;

        String excerpt = PullRequestService.trimLogToError(log);

        assertThat(excerpt).endsWith("##[error]Process completed with exit code 1.");
        assertThat(excerpt).contains("[ERROR] Extension foo:0.2.0 could not be resolved");
        assertThat(excerpt).doesNotContain("Cleaning up orphan processes");
        // Timestamps and ANSI codes are stripped so the message isn't crowded out.
        assertThat(excerpt).doesNotContain("2026-07-29T18:00:11");
        assertThat(excerpt).doesNotContain("36;1m");
    }

    @Test
    void logExcerptFallsBackToTheTailWhenNothingMarksTheFailure()
    {
        String log = "2026-07-29T18:00:11.1Z only line, no marker\n";

        assertThat(PullRequestService.trimLogToError(log)).isEqualTo("only line, no marker");
    }

    private static PullRequest prWithTitle(String title)
    {
        return new PullRequest(7L, "owner/repo", 42, title, "alice", "url",
                null, Instant.parse("2026-06-21T09:00:00Z"), PullRequest.Origin.AUTHORED,
                ImmutableList.of(), null, false, null, null, null, ImmutableList.of(),
                null, 0, 0, 0, null,
                "open", null, null, null, null, null, null,
                null, null, null);
    }

    private static PrRawDetail rawWithHead(String headSha)
    {
        return new PrRawDetail(
                null, ImmutableList.of(), false, null, null, 0, 0, 0, 0,
                ImmutableList.of(), headSha, null, null, null, null);
    }
}
