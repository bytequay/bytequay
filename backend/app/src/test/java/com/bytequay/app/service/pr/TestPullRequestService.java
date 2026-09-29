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

import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.CiJob;
import com.bytequay.app.ci.CiRerunResult;
import com.bytequay.app.ci.CiWorkflowRun;
import com.bytequay.app.ci.RerunFailedJobsCommand;
import com.bytequay.app.repository.github.ConditionalList;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubActionsClient;
import com.bytequay.app.repository.github.GitHubActionsClient.PrCheckRunsFetch;
import com.bytequay.app.repository.github.GitHubMergeClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.AttentionPrRef;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.app.repository.github.GitHubPullRequestSummary;
import com.bytequay.app.repository.github.GitHubPullRequestSummaryClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrDiffEtags;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PrViewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.RecentTextKind;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPullRequestService
{
    @Mock private GitHubPullRequestReadClient gitHub;
    @Mock private GitHubPullRequestSummaryClient summaries;
    @Mock private GitHubPullRequestWriteClient writes;
    @Mock private GitHubRepoClient repositories;
    @Mock private GitHubMergeClient merges;
    @Mock private GitHubActionsClient actions;
    @Mock private GitHubAccountClient accounts;
    @Mock private PullRequestStore store;
    @Mock private PrDetailStore detailStore;
    @Mock private PrDiffStore diffStore;
    @Mock private PrViewStateStore viewStateStore;
    @Mock private CredentialService credentialService;
    @Mock private WorkspaceMetadataService metadata;
    @Mock private WorkspaceStore workspaces;
    @Mock private RecentTouchRecorder recent;
    @Mock private WebServerApplicationContext applicationContext;
    @Mock private WebServer server;

    private PullRequestService service;
    private PullRequestSnapshotService snapshots;

    private static final Instant REVIEWED_AT = Instant.parse("2026-08-27T00:00:00Z");

    @BeforeEach
    void setUp()
    {
        snapshots = new PullRequestSnapshotService(gitHub, actions, detailStore, diffStore);
        service = new PullRequestService(
                gitHub, writes, repositories, merges, accounts, store, snapshots,
                new PullRequestDetailFetcher(gitHub, actions, Runnable::run),
                viewStateStore, recent, credentialService, metadata, summaries, workspaces, applicationContext);
        org.mockito.Mockito.lenient().when(diffStore.findDefaultEtags(anyLong())).thenReturn(PrDiffEtags.none());
        org.mockito.Mockito.lenient().when(credentialService.resolveGitHubToken()).thenReturn("pat");
        org.mockito.Mockito.lenient().when(credentialService.resolveGitHubCredential()).thenReturn(
                new GitHubCredential("pat", new UserProfile("me", "Me", "", "", 0, 0, 0, "", "", "", "")));
    }

    @Test
    void readsAttentionAvatarsFromTheCachedProjectionWithoutFetchingDetails()
    {
        String avatar = "https://avatars.githubusercontent.com/u/123?v=4";
        when(detailStore.findAuthorAvatarUrls(ImmutableSet.of(12L, 13L)))
                .thenReturn(ImmutableMap.of(12L, avatar));

        assertThat(service.findCachedAuthorAvatarUrls(ImmutableSet.of(12L, 13L)))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, avatar));

        verify(detailStore).findAuthorAvatarUrls(ImmutableSet.of(12L, 13L));
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, merges, actions, accounts, diffStore, metadata);
    }

    @Test
    void readsCachedActivityWithoutAttributingAnUnexplainedLaterUpdateToItsActor()
    {
        PrActivitySummary activity = new PrActivitySummary("commented", Optional.of("reviewer"), REVIEWED_AT,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty());
        when(detailStore.findLatestActivities(ImmutableSet.of(12L))).thenReturn(ImmutableMap.of(12L, activity));

        assertThat(service.findCachedLatestActivities(ImmutableList.of(storedPullRequest("open", REVIEWED_AT, null))))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, activity));
        assertThat(service.findCachedLatestActivities(ImmutableList.of(storedPullRequest("open", REVIEWED_AT.minusSeconds(1), null))))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, activity));
        assertThat(service.findCachedLatestActivities(ImmutableList.of(storedPullRequest("open", REVIEWED_AT.plusSeconds(1), null)))).isEmpty();

        verify(detailStore, times(3)).findLatestActivities(ImmutableSet.of(12L));
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, merges, actions, accounts, diffStore, metadata);
    }

    @Test
    void readsCommitsSinceTheLastReviewOnlyForPullRequestsWhoseHeadMovedSince()
    {
        Instant requested = REVIEWED_AT.minusSeconds(3600);
        PullRequest reviewedThenPushed = attentionPullRequest(12, REVIEWED_AT, requested, REVIEWED_AT.plusSeconds(60), null, REVIEWED_AT.plusSeconds(60));
        PullRequest requestedThenPushed = attentionPullRequest(13, null, requested, requested.plusSeconds(60), null);
        PullRequest untouchedSinceReview = attentionPullRequest(14, REVIEWED_AT, requested, REVIEWED_AT.minusSeconds(60), null);
        PullRequest neverAsked = attentionPullRequest(15, null, null, REVIEWED_AT, null);
        List<PrCommitLine> commits = ImmutableList.of(new PrCommitLine("abc", "Fix it", Optional.of("alice"), REVIEWED_AT.plusSeconds(60)));
        when(detailStore.findSyncedAt("owner/repo", 12)).thenReturn(Optional.of(REVIEWED_AT.plusSeconds(60)));
        when(detailStore.findCommitsAfter(ImmutableMap.of(12L, REVIEWED_AT, 13L, requested))).thenReturn(ImmutableMap.of(12L, commits));

        assertThat(service.findCachedCommitsSinceReview(ImmutableList.of(reviewedThenPushed, requestedThenPushed, untouchedSinceReview, neverAsked)))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, commits));

        verify(detailStore).findCommitsAfter(ImmutableMap.of(12L, REVIEWED_AT, 13L, requested));
        verify(detailStore).findSyncedAt("owner/repo", 12);
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, merges, actions, accounts, diffStore, metadata);
    }

    @Test
    void namesTheLatestFailingChecksOfFailingPullRequestsOnly()
    {
        PullRequest failing = attentionPullRequest(12, null, null, null, PullRequestDetail.CiStatus.FAILING, REVIEWED_AT);
        PullRequest passing = attentionPullRequest(13, null, null, null, PullRequestDetail.CiStatus.PASSING);
        when(detailStore.findSyncedAt("owner/repo", 12)).thenReturn(Optional.of(REVIEWED_AT.plusSeconds(1)));
        when(detailStore.findCheckRuns(ImmutableSet.of(12L))).thenReturn(ImmutableMap.of(12L, ImmutableList.of(
                new PrCheckRunState(1L, "ci / test", "completed", "failure", null, "check_run", REVIEWED_AT, REVIEWED_AT),
                new PrCheckRunState(2L, "ci / lint", "completed", "failure", null, "check_run", REVIEWED_AT, REVIEWED_AT),
                new PrCheckRunState(3L, "ci / lint", "completed", "success", null, "check_run", REVIEWED_AT, REVIEWED_AT))));

        assertThat(service.findCachedFailingChecks(ImmutableList.of(failing, passing)))
                .containsExactlyEntriesOf(ImmutableMap.of(12L, ImmutableList.of("ci / test")));

        verify(detailStore).findCheckRuns(ImmutableSet.of(12L));
        verify(detailStore).findSyncedAt("owner/repo", 12);
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, writes, repositories, merges, actions, accounts, diffStore, metadata);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void summaryChangesSuppressStaleOrMissingCheckNamesAndCommitLists(boolean detailExists)
    {
        Instant activityAt = REVIEWED_AT.plusSeconds(60);
        PullRequest changed = attentionPullRequest(12, REVIEWED_AT, null, activityAt, PullRequestDetail.CiStatus.FAILING, activityAt);
        when(detailStore.findSyncedAt("owner/repo", 12)).thenReturn(detailExists ? Optional.of(REVIEWED_AT) : Optional.empty());

        assertThat(service.findCachedCommitsSinceReview(ImmutableList.of(changed))).isEmpty();
        assertThat(service.findCachedFailingChecks(ImmutableList.of(changed))).isEmpty();

        verify(detailStore, times(2)).findSyncedAt("owner/repo", 12);
        verify(detailStore).findCommitsAfter(ImmutableMap.of());
        verify(detailStore).findCheckRuns(ImmutableSet.of());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(gitHub, summaries, writes, repositories, merges, actions, accounts, diffStore, metadata);
    }

    @Test
    void refreshesTaskBranchRowsImmediatelyWithoutSearchOrDetailReads()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        FetchedPullRequest found = pullRequest();
        when(gitHub.fetchPullRequestsByHead("pat", repository, "contributor:feature")).thenReturn(ImmutableList.of(found));

        service.syncRepositoryBranch(repository, "contributor", "feature");
        service.syncRepositoryBranch(repository, "contributor", "feature");

        verify(gitHub, times(2)).fetchPullRequestsByHead("pat", repository, "contributor:feature");
        verify(store, times(2)).saveRepositorySyncPage(eq("owner/repo"), eq(ImmutableList.of(found.withViewerOrigin("me"))), anyString());
        verify(store, never()).completeRepositorySync(anyString(), any());
        verifyNoInteractions(detailStore, diffStore);
    }

    @Test
    void preservesCachedBranchRowsWhenGitHubReturnsNoMatches()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        when(gitHub.fetchPullRequestsByHead("pat", repository, "contributor:feature")).thenReturn(ImmutableList.of());

        service.syncRepositoryBranch(repository, "contributor", "feature");

        verifyNoInteractions(store, detailStore, diffStore);
    }

    @Test
    void monitorEstablishesCardBaselineBeforeEnablingNotificationsWithoutLoadingDetail()
    {
        PullRequest row = storedPullRequest();
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary summary = summary(pullRequest(), PullRequestDetail.CiStatus.FAILING, "checks-v1");
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(summary.pullRequest())).thenReturn(row);

        assertThat(service.setMonitored(row.id(), true)).isEqualTo(row);

        var order = inOrder(store);
        order.verify(store).saveObserved(summary.pullRequest());
        order.verify(store).updateEnrichment(row.id(), summary.pullRequest().updatedAt(), PullRequestDetail.CiStatus.FAILING, 4, 2, 1,
                null, null, REVIEWED_AT, "feature", ImmutableList.of(), row.origin(), Optional.empty(), Optional.of("APPROVED"),
                ImmutableMap.of("summary", "checks-v1"));
        order.verify(store).setMonitored(row.id(), true);
        verifyNoInteractions(gitHub, actions, accounts, detailStore, diffStore, writes);
    }

    @Test
    void disablingMonitoringStaysLocal()
    {
        PullRequest row = storedPullRequest();
        when(store.findById(row.id())).thenReturn(Optional.of(row));

        service.setMonitored(row.id(), false);

        verify(store).setMonitored(row.id(), false);
        verifyNoInteractions(credentialService, gitHub, summaries, actions, accounts, detailStore, diffStore, viewStateStore);
    }

    @Test
    void monitoringRemainsDisabledWhenTheFreshSummaryIsUnavailable()
    {
        PullRequest row = storedPullRequest();
        when(store.findById(row.id())).thenReturn(Optional.of(row));
        when(summaries.fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me")).thenReturn(ImmutableList.of());

        assertThatThrownBy(() -> service.setMonitored(row.id(), true))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(store).findById(row.id());
        verifyNoMoreInteractions(store);
        verifyNoInteractions(gitHub, actions, detailStore, diffStore, viewStateStore, accounts);
    }

    @Test
    void newerExternalReviewUpdatesTheBaselineBeforeCheckingForNewCommits()
    {
        Instant latestReviewAt = REVIEWED_AT.plusSeconds(30);
        PrReviewState review = new PrReviewState(1L, "me", null, false, "APPROVED", latestReviewAt, null, null, "head");
        StoredPrDetail latest = new StoredPrDetail(raw(), ImmutableList.of(review), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(store.findById(12L)).thenReturn(Optional.of(storedPullRequest()));
        when(detailStore.find(12L)).thenReturn(Optional.of(latest));
        when(gitHub.getPullRequest("pat", PullRequestRef.of("owner", "repo", 7), Optional.empty()))
                .thenReturn(new PullRequestFetch(Optional.empty(), Optional.empty()));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));
        PrViewState previous = new PrViewState(12L, REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "old-head");
        PrViewState updated = new PrViewState(12L, REVIEWED_AT, latestReviewAt, HandledAction.APPROVED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of(12L, previous), ImmutableMap.of(12L, updated));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        verify(viewStateStore).markReviewed(12L, HandledAction.APPROVED, "head", latestReviewAt);
        verify(viewStateStore, never()).markNeedsReview(12L);
    }

    @Test
    void watchedRepositoryRowsRemainDiscoverableWhileItsWarmingBelongsToTheRepositorySync()
    {
        service = spy(service);
        doNothing().when(service).warmDetails(anyList());
        RepoRef repository = RepoRef.of("owner", "repo");
        Workspace workspace = new Workspace("workspace", "Workspace", 1, repository, Optional.empty(), Optional.empty(), 1, repository,
                Workspace.Topology.DIRECT, "main", "https://github.com/owner/repo.git", Optional.empty(), Optional.empty(), "/tmp/bytequay-test",
                Optional.empty(), Optional.empty(), 0, REVIEWED_AT, REVIEWED_AT);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace));
        PullRequest monitored = monitoredPullRequest(91);
        PullRequest unmonitored = storedPullRequest(pullRequestNumber(92));
        PullRequestRef monitoredRef = PullRequestRef.of("owner", "repo", 91);
        PullRequestRef notificationRef = PullRequestRef.of("owner", "repo", 93);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(monitored, unmonitored));
        when(gitHub.fetchPrNotificationRefs("pat")).thenReturn(ImmutableList.of(new AttentionPrRef(notificationRef, "subscribed", REVIEWED_AT)));

        service.syncFromGitHub();
        service.syncAttentionSummaries();

        verify(summaries).fetchSummaries("pat", ImmutableList.of(monitoredRef, notificationRef), "me");
        verify(store).saveDashboardSyncPage(eq(ImmutableList.of(pullRequest())), anyString());
        verify(store, times(2)).saveObserved(pullRequest());
        verify(store, never()).completeDashboardSync(anyString(), anyString());
        verifyNoInteractions(detailStore, diffStore, actions, accounts);
    }

    @Test
    void dashboardDiscoversEveryPageWhileWarmingOnlyThirtyNewestPullRequests()
    {
        service = spy(service);
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub(anyString(), anyInt());
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 120).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100))).thenAnswer(invocation -> {
            String query = invocation.getArgument(1);
            int page = invocation.getArgument(2);
            if (!query.equals("is:pr is:open author:@me")) {
                return new PullRequestHistoryPage(ImmutableList.of(), false);
            }
            return new PullRequestHistoryPage(page == 1 ? rows.subList(0, 100) : rows.subList(100, 120), page == 1);
        });

        service.syncFromGitHub();

        verify(store).saveDashboardSyncPage(eq(rows), anyString());
        verify(store, times(120)).saveObserved(any());
        ArgumentCaptor<List<PullRequestRef>> summarized = ArgumentCaptor.forClass(List.class);
        verify(summaries, times(4)).fetchSummaries(eq("pat"), summarized.capture(), eq("me"));
        assertThat(summarized.getAllValues().stream().flatMap(List::stream)).hasSize(120);
        ArgumentCaptor<Integer> warmed = ArgumentCaptor.forClass(Integer.class);
        verify(service, times(30)).refreshPullRequestDetailFromGitHub(eq("owner/repo"), warmed.capture());
        assertThat(warmed.getAllValues()).containsExactlyElementsOf(IntStream.iterate(120, number -> number - 1).limit(30).boxed().toList());
        verify(gitHub).searchPullRequestsPaged("pat", "is:pr is:open author:@me", 2, 100);
        verify(gitHub, times(5)).searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100));
        verify(gitHub).fetchPrNotificationRefs("pat");
        verifyNoMoreInteractions(gitHub);
        verify(store, never()).completeDashboardSync(anyString(), anyString());
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void discoveryLimitFailureRetainsObservedRowsWithoutClaimingACompleteSweep()
    {
        when(gitHub.searchPullRequestsPaged(eq("pat"), eq("is:pr is:open author:@me"), anyInt(), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), true));

        assertThatThrownBy(service::syncFromGitHub).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("search result limit");

        verify(gitHub, times(10)).searchPullRequestsPaged(eq("pat"), eq("is:pr is:open author:@me"), anyInt(), eq(100));
        verify(store).saveObserved(pullRequest());
        verify(store, never()).saveDashboardSyncPage(anyList(), anyString());
        verify(store, never()).completeDashboardSync(anyString(), anyString());
        verifyNoInteractions(summaries, detailStore, diffStore, actions, accounts);
    }

    @Test
    void failedCardEnrichmentRetainsAllDiscoveryAndStillWarmsDetails()
    {
        service = spy(service);
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub(anyString(), anyInt());
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 31).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100))).thenAnswer(invocation ->
                new PullRequestHistoryPage(invocation.getArgument(1).equals("is:pr is:open author:@me") ? rows : ImmutableList.of(), false));
        List<PullRequestRef> firstBatch = rows.subList(0, 30).stream().map(row -> PullRequestRef.of("owner", "repo", row.number())).toList();
        when(summaries.fetchSummaries("pat", firstBatch, "me"))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub API request failed with status 502."));

        service.syncFromGitHub();

        verify(store).saveDashboardSyncPage(eq(rows), anyString());
        verify(store, times(31)).saveObserved(any());
        verify(summaries).fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 31)), "me");
        verify(service, times(30)).refreshPullRequestDetailFromGitHub(eq("owner/repo"), anyInt());
        verify(service, never()).refreshPullRequestDetailFromGitHub("owner/repo", 1);
        verify(store, never()).completeDashboardSync(anyString(), anyString());
    }

    @Test
    void fullDetailWarmingSkipsFreshSnapshotsAndContinuesAfterAnUpstreamFailure()
    {
        service = spy(service);
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 4).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        when(store.findIdByRepoAndNumber("owner/repo", 1)).thenReturn(Optional.of(1L));
        when(store.findIdByRepoAndNumber("owner/repo", 2)).thenReturn(Optional.of(2L));
        PrRawDetail currentRaw = new PrRawDetail(null, null, null, null, null, null, rows.get(1).updatedAt(), null,
                "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 4, 2, 1, 0, ImmutableList.of(), "head", "feature", null);
        StoredPrDetail cached = new StoredPrDetail(currentRaw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        when(detailStore.find(1L)).thenReturn(Optional.of(cached));
        when(detailStore.find(2L)).thenReturn(Optional.of(cached));
        when(detailStore.findSyncedAt("owner/repo", 1)).thenReturn(Optional.of(Instant.now()));
        when(detailStore.findSyncedAt("owner/repo", 2)).thenReturn(Optional.of(Instant.now().minusSeconds(420)));
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub("owner/repo", 2);
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub unavailable"))
                .when(service).refreshPullRequestDetailFromGitHub("owner/repo", 3);
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub("owner/repo", 4);

        service.warmDetails(rows);

        verify(service, never()).refreshPullRequestDetailFromGitHub("owner/repo", 1);
        var order = inOrder(service);
        order.verify(service).refreshPullRequestDetailFromGitHub("owner/repo", 2);
        order.verify(service).refreshPullRequestDetailFromGitHub("owner/repo", 3);
        order.verify(service).refreshPullRequestDetailFromGitHub("owner/repo", 4);
        verifyNoInteractions(gitHub, actions, accounts, diffStore);
    }

    @Test
    void fullDetailWarmingRetriesAFreshPartialSnapshotWithoutItsCachedEtag()
    {
        service = spy(service);
        FetchedPullRequest row = pullRequest();
        PullRequestRef ref = PullRequestRef.of("owner", "repo", row.number());
        PrRawDetail currentRaw = new PrRawDetail(null, null, null, null, null, null, row.updatedAt(), null,
                "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 4, 2, 1, 0, ImmutableList.of(), "head", "feature", null);
        StoredPrDetail partial = new StoredPrDetail(currentRaw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(StoredPrDetail.COMMENTS));
        when(store.findIdByRepoAndNumber("owner/repo", row.number())).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(partial));
        org.mockito.Mockito.lenient().when(detailStore.findSyncedAt("owner/repo", row.number())).thenReturn(Optional.of(Instant.now()));
        org.mockito.Mockito.lenient().when(detailStore.findEtag(12L)).thenReturn(Optional.of("\"cached-etag\""));
        when(gitHub.getPullRequest("pat", ref, Optional.empty()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stop after verifying the unconditional request"));

        service.warmDetails(ImmutableList.of(row));

        verify(service).refreshAllPullRequestDetailFromGitHub("owner/repo", row.number());
        verify(service, never()).refreshPullRequestDetailFromGitHub(anyString(), anyInt());
        verify(gitHub).getPullRequest("pat", ref, Optional.empty());
        verify(detailStore, never()).findEtag(anyLong());
        verifyNoMoreInteractions(gitHub);
        verifyNoInteractions(actions, accounts, diffStore);
    }

    @Test
    void backgroundSummaryUpdatesCiAndReviewCardsWithoutDownloadingDetail()
    {
        service = spy(service);
        doNothing().when(service).warmDetails(anyList());
        FetchedPullRequest fetched = pullRequest("open", REVIEWED_AT.plusSeconds(60), null);
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary summary = summary(fetched, PullRequestDetail.CiStatus.FAILING, "checks-v2");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(fetched), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(fetched)).thenReturn(row);
        PrViewState reviewed = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "previous-head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), reviewed));

        service.syncFromGitHub();

        verify(store).updateEnrichment(eq(row.id()), eq(fetched.updatedAt()), eq(PullRequestDetail.CiStatus.FAILING), eq(4), eq(2), eq(1),
                isNull(), isNull(), any(Instant.class), eq("feature"), eq(ImmutableList.of()), eq(row.origin()), eq(Optional.empty()), eq(Optional.of("APPROVED")),
                eq(ImmutableMap.of("summary", "checks-v2")));
        verify(viewStateStore).markNeedsReview(row.id());
        verify(summaries).fetchSummaries("pat", ImmutableList.of(ref), "me");
        verifyNoMoreInteractions(summaries);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void backgroundSummaryReconcilesANewerViewerReviewBeforeCheckingForAnotherReview()
    {
        service = spy(service);
        doNothing().when(service).warmDetails(anyList());
        PullRequest row = storedPullRequest();
        Instant reviewedAt = REVIEWED_AT.plusSeconds(60);
        GitHubPullRequestSummary summary = new GitHubPullRequestSummary(pullRequest(), PullRequestDetail.CiStatus.PASSING, 4, 2, 1, "head",
                Optional.of(REVIEWED_AT), Optional.of("APPROVED"),
                Optional.of(new PrReviewState(1L, "me", null, false, "APPROVED", reviewedAt, null, null, "head")), Optional.empty(), "review-v2");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(PullRequestRef.of("owner", "repo", 7)), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(pullRequest())).thenReturn(row);
        PrViewState previous = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, HandledAction.APPROVED, "old-head");
        PrViewState updated = new PrViewState(row.id(), REVIEWED_AT, reviewedAt, HandledAction.APPROVED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), previous), ImmutableMap.of(row.id(), updated));

        service.syncFromGitHub();

        verify(viewStateStore).markReviewed(row.id(), HandledAction.APPROVED, "head", reviewedAt);
        verify(viewStateStore, never()).markNeedsReview(row.id());
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void backgroundSummaryReopensHandledReviewOnlyWhenTheAuthorReplied(boolean authorReplied)
    {
        service = spy(service);
        doNothing().when(service).warmDetails(anyList());
        FetchedPullRequest fetched = pullRequest("open", REVIEWED_AT.plusSeconds(60), null).withViewerOrigin("me");
        PullRequest row = storedPullRequest(fetched);
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        GitHubPullRequestSummary summary = summary(fetched, PullRequestDetail.CiStatus.PASSING, "reply-v2");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(fetched), false));
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveObserved(any())).thenReturn(row);
        PrViewState reviewed = new PrViewState(row.id(), REVIEWED_AT, REVIEWED_AT, HandledAction.COMMENTED, "head");
        when(viewStateStore.findByPrIds(ImmutableSet.of(row.id()))).thenReturn(ImmutableMap.of(row.id(), reviewed));
        when(summaries.hasAuthorReplyAfter("pat", ref, "me", "alice", REVIEWED_AT, "reply-v2")).thenReturn(authorReplied);

        service.syncFromGitHub();

        verify(summaries).hasAuthorReplyAfter("pat", ref, "me", "alice", REVIEWED_AT, "reply-v2");
        verify(viewStateStore, times(authorReplied ? 1 : 0)).markNeedsReview(row.id());
        verify(store).updateEnrichment(row.id(), fetched.updatedAt(), PullRequestDetail.CiStatus.PASSING, 4, 2, 1,
                null, null, REVIEWED_AT, "feature", ImmutableList.of(), row.origin(), Optional.empty(), Optional.of("APPROVED"),
                ImmutableMap.of("summary", "reply-v2"));
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void fastSummarySyncReadsOnlyTheFirstHundredRowsPerQueryWithoutDetailsOrPruning()
    {
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 120).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100))).thenAnswer(invocation -> {
            if (!invocation.getArgument(1).equals("is:pr is:open author:@me")) {
                return new PullRequestHistoryPage(ImmutableList.of(), false);
            }
            int page = invocation.getArgument(2);
            return new PullRequestHistoryPage(page == 1 ? rows.subList(0, 100) : rows.subList(100, 120), page == 1);
        });

        service.syncAttentionSummaries();

        verify(gitHub, times(4)).searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100));
        verify(gitHub).fetchPrNotificationRefs("pat");
        verifyNoMoreInteractions(gitHub);
        ArgumentCaptor<FetchedPullRequest> saved = ArgumentCaptor.forClass(FetchedPullRequest.class);
        verify(store, times(100)).saveObserved(saved.capture());
        assertThat(saved.getAllValues()).containsExactlyElementsOf(rows.subList(0, 100));
        verifyNoMoreInteractions(store);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void fastSummaryNotificationsFetchOnlyWhenTheirVersionChanges()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        AttentionPrRef first = new AttentionPrRef(ref, "review_requested", REVIEWED_AT);
        AttentionPrRef changed = new AttentionPrRef(ref, "review_requested", REVIEWED_AT.plusSeconds(60));
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(gitHub.fetchPrNotificationRefs("pat"))
                .thenReturn(ImmutableList.of(first), ImmutableList.of(first), ImmutableList.of(changed));
        when(gitHub.getPullRequest("pat", ref)).thenReturn(pullRequest());

        service.syncAttentionSummaries();
        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(gitHub, times(2)).getPullRequest("pat", ref);
        verify(store, times(2)).saveObserved(pullRequest().withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        verifyNoMoreInteractions(store);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void fastSummaryNotificationsStillDiscoverRequestsWhenSearchIsRateLimited()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Search limit reached"));
        when(gitHub.fetchPrNotificationRefs("pat"))
                .thenReturn(ImmutableList.of(new AttentionPrRef(ref, "review_requested", REVIEWED_AT)));
        when(gitHub.getPullRequest("pat", ref)).thenReturn(pullRequest());

        service.syncAttentionSummaries();

        verify(store).saveObserved(pullRequest().withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        verifyNoMoreInteractions(store);
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void notificationReviewRequestsPromotePreviouslyReviewedSearchResultsWithoutAnotherFetch()
    {
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(gitHub.searchPullRequestsPaged("pat", "is:pr is:open reviewed-by:@me", 1, 100))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(gitHub.fetchPrNotificationRefs("pat"))
                .thenReturn(ImmutableList.of(new AttentionPrRef(PullRequestRef.of("owner", "repo", 7), "review_requested", pullRequest().updatedAt())));

        service.syncAttentionSummaries();

        var order = inOrder(store);
        order.verify(store).saveObserved(pullRequest().withOrigin(PullRequest.Origin.OTHER));
        order.verify(store).saveObserved(pullRequest().withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        verify(gitHub, never()).getPullRequest(anyString(), any());
    }

    @Test
    void newerNotificationReadsAnExactSummaryWhenSearchHasNotCaughtUp()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        Instant updatedAt = pullRequest().updatedAt().plusSeconds(60);
        FetchedPullRequest updated = pullRequest("open", updatedAt, null);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(gitHub.searchPullRequestsPaged("pat", "is:pr is:open reviewed-by:@me", 1, 100))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(pullRequest()), false));
        when(gitHub.fetchPrNotificationRefs("pat"))
                .thenReturn(ImmutableList.of(new AttentionPrRef(ref, "review_requested", updatedAt)));
        when(gitHub.getPullRequest("pat", ref)).thenReturn(updated);

        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(gitHub).getPullRequest("pat", ref);
        verify(store).saveObserved(updated.withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void fastSummaryNotificationsRefreshOtherReasonsOnlyForRetainedAttentionCandidates()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100)))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), false));
        when(gitHub.fetchPrNotificationRefs("pat")).thenReturn(ImmutableList.of(
                new AttentionPrRef(ref, "comment", REVIEWED_AT),
                new AttentionPrRef(PullRequestRef.of("unrelated", "repo", 8), "subscribed", REVIEWED_AT)));
        when(store.findAttentionCandidates()).thenReturn(ImmutableList.of(storedPullRequest()));
        FetchedPullRequest closed = pullRequest("closed", REVIEWED_AT.plusSeconds(60), REVIEWED_AT.plusSeconds(60));
        when(gitHub.getPullRequest("pat", ref)).thenReturn(closed);

        service.syncAttentionSummaries();
        service.syncAttentionSummaries();

        verify(gitHub).getPullRequest("pat", ref);
        verify(gitHub, never()).getPullRequest(eq("pat"), eq(PullRequestRef.of("unrelated", "repo", 8)));
        verify(store).saveObserved(closed.withOrigin(PullRequest.Origin.OTHER));
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void activityFingerprintDetectsIndividualCheckChangesEvenWithSameAggregateAndUpdateTime()
    {
        PrCheckRunState first = new PrCheckRunState(1L, "one", "completed", "failure", null, "check_run", null, null);
        PrCheckRunState second = new PrCheckRunState(2L, "two", "completed", "success", null, "check_run", null, null);
        StoredPrDetail baseline = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(first, second), ImmutableList.of());
        StoredPrDetail reordered = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(second, first), ImmutableList.of());
        StoredPrDetail changed = new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(first, new PrCheckRunState(2L, "two", "completed", "failure", null, "check_run", null, null)), ImmutableList.of());
        assertThat(PullRequestService.activityFingerprints(reordered)).isEqualTo(PullRequestService.activityFingerprints(baseline));
        assertThat(PullRequestService.activityFingerprints(changed)).isNotEqualTo(PullRequestService.activityFingerprints(baseline));
        assertThat(PullRequestDetailMapper.aggregateCiStatus(changed.checkRuns())).isEqualTo(PullRequestDetailMapper.aggregateCiStatus(baseline.checkRuns()));
    }

    @Test
    void partialTimelineStillProducesCiFingerprintAndCollectionOrderingDoesNotChangeRawFingerprint()
    {
        PrRawDetail first = new PrRawDetail(
                "title", "url", "open", "author", null, REVIEWED_AT, REVIEWED_AT, null, "body",
                ImmutableList.of("a", "b"), ImmutableMap.of("a", "111", "b", "222"), ImmutableList.of("a", "b"), null,
                false, true, "clean", 0, 0, 0, 2, ImmutableList.of("a", "b"), "head", "feature", "main");
        PrRawDetail reordered = new PrRawDetail(
                "title", "url", "open", "author", null, REVIEWED_AT, REVIEWED_AT, null, "body",
                ImmutableList.of("b", "a"), ImmutableMap.of("b", "222", "a", "111"), ImmutableList.of("b", "a"), null,
                false, true, "clean", 0, 0, 0, 2, ImmutableList.of("b", "a"), "head", "feature", "main");
        StoredPrDetail baseline = new StoredPrDetail(first, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        StoredPrDetail partial = new StoredPrDetail(reordered, ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(1L, "ci", "completed", "failure", null, "check_run", null, null)), ImmutableList.of(StoredPrDetail.TIMELINE));

        assertThat(PullRequestService.activityFingerprints(partial)).containsKey("checks").doesNotContainKey("timeline");
        assertThat(PullRequestService.activityFingerprints(partial).get("raw")).isEqualTo(PullRequestService.activityFingerprints(baseline).get("raw"));
        assertThat(PullRequestService.activityFingerprints(partial).get("checks")).isNotEqualTo(PullRequestService.activityFingerprints(baseline).get("checks"));
    }

    @Test
    void reviewDecisionUsesActualLatestVerdictsAndDismissals()
    {
        assertThat(PullRequestService.reviewDecision(ImmutableList.of(
                new PrReviewState(null, "reviewer", null, false, "CHANGES_REQUESTED", REVIEWED_AT, null, null, null),
                new PrReviewState(null, "reviewer", null, false, "APPROVED", REVIEWED_AT.plusSeconds(1), null, null, null),
                new PrReviewState(null, "reviewer", null, false, "COMMENTED", REVIEWED_AT.plusSeconds(2), null, null, null))))
                .contains("APPROVED");
        assertThat(PullRequestService.reviewDecision(ImmutableList.of(
                new PrReviewState(null, "reviewer", null, false, "CHANGES_REQUESTED", REVIEWED_AT, null, null, null),
                new PrReviewState(null, "reviewer", null, false, "DISMISSED", REVIEWED_AT.plusSeconds(1), null, null, null))))
                .isEmpty();
    }

    @Test
    void rejectsMalformedRepositoryNames()
    {
        assertThatThrownBy(() -> service.getPullRequestDetail("repo", 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updatesPullRequestAssigneesAndLabelsThenMarksDetailForRefresh()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateMetadata(
                "owner/repo", 7, ImmutableList.of(" alice "), ImmutableList.of("bug"));

        verify(writes).updateIssueMetadata(
                "pat",
                PullRequestRef.of("owner", "repo", 7),
                ImmutableList.of("alice"),
                ImmutableList.of("bug"));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void updatesPullRequestReviewerThenMarksDetailForRefresh()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateReviewerRequest("owner/repo", 7, " alice ", true);

        verify(writes).updateReviewerRequest(
                "pat", PullRequestRef.of("owner", "repo", 7), "alice", true);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void listsRepositoryLabelsForMetadataPicker()
    {
        RepositoryLabel label = new RepositoryLabel(1, "bug", "d73a4a");
        when(repositories.fetchLabels("pat", RepoRef.of("owner", "repo")))
                .thenReturn(ImmutableList.of(label));

        assertThat(service.listRepositoryLabels("owner/repo")).containsExactly(label);
    }

    @Test
    void readsWorkspacePickerUsersAndAvatarsFromLocalMetadata()
    {
        when(metadata.getSnapshot("workspace")).thenReturn(pickerSnapshot());
        when(applicationContext.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(41234);

        assertThat(service.listRepositoryUsers("owner/repo", "workspace"))
                .extracting(user -> user.login() + ":" + user.avatarUrl() + ":" + user.bot())
                .containsExactly(
                        "alice:http://127.0.0.1:41234/api/avatars/avatar-key:false",
                        "robot[bot]:null:true");
        verify(gitHub, never()).fetchAssignableUsers(anyString(), any());
    }

    @Test
    void readsWatchedRepositoryPickerUsersFromLocalMetadataWithoutWorkspaceId()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        when(metadata.findSnapshot(repository)).thenReturn(Optional.of(pickerSnapshot()));
        when(applicationContext.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(41234);

        assertThat(service.listRepositoryUsers("owner/repo", null))
                .extracting(RepoUser::login)
                .containsExactly("alice", "robot[bot]");
        verify(credentialService, never()).resolveGitHubToken();
        verify(gitHub, never()).fetchAssignableUsers(anyString(), any());
    }

    @Test
    void fetchesPickerUsersFromGitHubForUnwatchedRepository()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        RepoUser user = new RepoUser("alice", "https://avatars.example/alice", false);
        when(metadata.findSnapshot(repository)).thenReturn(Optional.empty());
        when(gitHub.fetchAssignableUsers("pat", repository)).thenReturn(ImmutableList.of(user));

        assertThat(service.listRepositoryUsers("owner/repo", null)).containsExactly(user);
        verify(gitHub).fetchAssignableUsers("pat", repository);
    }

    @Test
    void readsOnlyAFailedJobOnTheRequestedPullRequest()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        String headSha = "1111111111111111111111111111111111111111";
        CiJob failed = new CiJob(301, 101, "test", 3, "completed", Optional.of("failure"),
                "https://github.com/owner/repo/actions/runs/101/job/301", ImmutableList.of());
        CiJob passing = new CiJob(302, 101, "build", 3, "completed", Optional.of("success"),
                "https://github.com/owner/repo/actions/runs/101/job/302", ImmutableList.of());
        CiWorkflowRun run = new CiWorkflowRun(101, 201, "CI", 3, "completed", Optional.of("failure"), headSha,
                "https://github.com/owner/repo/actions/runs/101", ImmutableList.of(failed, passing));
        when(actions.inspect(ref)).thenReturn(new CiHeadSnapshot(
                ref, true, "alice", RepoRef.of("owner", "repo"), "feature", headSha, "main",
                "2222222222222222222222222222222222222222", "https://github.com/owner/repo/pull/7", ImmutableList.of(run)));
        when(actions.readJobLog(ref, 301)).thenReturn("test failed");

        assertThat(service.readCheckLog("owner/repo", 7, 301)).isEqualTo("test failed");
        for (long jobId : ImmutableList.of(302L, 999L)) {
            assertThatThrownBy(() -> service.readCheckLog("owner/repo", 7, jobId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("no longer part of the current PR checks");
            verify(actions, never()).readJobLog(ref, jobId);
        }
        assertThatThrownBy(() -> service.readCheckLog("owner/repo", 7, 0)).isInstanceOf(IllegalArgumentException.class);
        verify(actions, never()).rerunFailedJobs(any());
    }

    @Test
    void rerunsFailedGitHubActionsForTheCurrentHead()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        String headSha = "1111111111111111111111111111111111111111";
        CiJob failedJob = new CiJob(
                301, 101, "test", 3, "completed", Optional.of("failure"),
                "https://github.com/owner/repo/actions/runs/101/job/301", ImmutableList.of());
        CiWorkflowRun failedRun = new CiWorkflowRun(
                101, 201, "CI", 3, "completed", Optional.of("failure"), headSha,
                "https://github.com/owner/repo/actions/runs/101", ImmutableList.of(failedJob));
        when(actions.inspect(ref)).thenReturn(new CiHeadSnapshot(
                ref, true, "alice", RepoRef.of("owner", "repo"), "feature", headSha, "main",
                "2222222222222222222222222222222222222222",
                "https://github.com/owner/repo/pull/7", ImmutableList.of(failedRun)));
        when(actions.rerunFailedJobs(any())).thenReturn(new CiRerunResult(CiRerunResult.Outcome.ACCEPTED, "accepted"));

        service.rerunFailedChecks("owner/repo", 7);

        ArgumentCaptor<RerunFailedJobsCommand> command = ArgumentCaptor.forClass(RerunFailedJobsCommand.class);
        verify(actions).rerunFailedJobs(command.capture());
        assertThat(command.getValue()).isEqualTo(new RerunFailedJobsCommand(ref, 101, headSha, 3, true));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void workspaceSyncPreservesNotificationReviewRequestsWithoutPruningRows(boolean notified)
    {
        service = spy(service);
        doNothing().when(service).warmDetails(anyList());
        RepoRef repository = RepoRef.of("owner", "repo");
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest fetched = pullRequest().withViewerOrigin("me");
        GitHubPullRequestSummary summary = summary(fetched, PullRequestDetail.CiStatus.PASSING, "v1");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100))).thenAnswer(invocation ->
                new PullRequestHistoryPage(invocation.<String>getArgument(1).contains("updated:") ? ImmutableList.of(pullRequest()) : ImmutableList.of(), false));
        when(gitHub.fetchPrNotificationRefs("pat")).thenReturn(notified
                ? ImmutableList.of(new AttentionPrRef(ref, "review_requested", REVIEWED_AT)) : ImmutableList.of());
        when(summaries.fetchSummaries("pat", ImmutableList.of(ref), "me")).thenReturn(ImmutableList.of(summary));
        when(store.saveRepositorySyncPage(eq("owner/repo"), anyList(), anyString())).thenAnswer(invocation ->
                invocation.<List<FetchedPullRequest>>getArgument(1).stream().map(TestPullRequestService::storedPullRequest).toList());

        service.syncRepository(repository);

        verify(gitHub, times(2)).searchPullRequestsPaged(eq("pat"), anyString(), eq(1), eq(100));
        verify(gitHub).fetchPrNotificationRefs("pat");
        verifyNoMoreInteractions(gitHub);
        ArgumentCaptor<List<FetchedPullRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(store, times(3)).saveRepositorySyncPage(eq("owner/repo"), saved.capture(), anyString());
        assertThat(saved.getAllValues().getFirst()).containsExactly(fetched);
        assertThat(saved.getValue()).containsExactly(fetched.withOrigin(notified ? PullRequest.Origin.REVIEW_REQUESTED : PullRequest.Origin.OTHER));
        assertThat(saved.getValue().getFirst().requestedReviewers()).isEmpty();
        verify(service).warmDetails(ImmutableList.of(fetched));
        verify(store, never()).findRepositoryRowsMissingSync(anyString(), anyString());
        verify(store, never()).completeRepositorySync(anyString(), any());
        verify(store, never()).saveDashboardSyncPage(anyList(), anyString());
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void workspaceDiscoversAllRecentAndOpenPagesThenWarmsOnlyThirtyNewest()
    {
        service = spy(service);
        doReturn(null).when(service).refreshPullRequestDetailFromGitHub(anyString(), anyInt());
        List<FetchedPullRequest> rows = IntStream.rangeClosed(1, 120).mapToObj(TestPullRequestService::pullRequestNumber).toList();
        FetchedPullRequest olderOpen = pullRequestNumber(121).withViewerOrigin("me");
        when(gitHub.searchPullRequestsPaged(eq("pat"), anyString(), anyInt(), eq(100))).thenAnswer(invocation -> {
            if (invocation.<String>getArgument(1).endsWith("is:open")) {
                return new PullRequestHistoryPage(ImmutableList.of(olderOpen), false);
            }
            int page = invocation.getArgument(2);
            return new PullRequestHistoryPage(page == 1 ? rows.subList(0, 100) : rows.subList(100, 120), page == 1);
        });

        service.syncRepository(RepoRef.of("owner", "repo"));

        ArgumentCaptor<List<FetchedPullRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(store, times(3)).saveRepositorySyncPage(eq("owner/repo"), saved.capture(), anyString());
        assertThat(saved.getAllValues().stream().flatMap(List::stream)).hasSize(121);
        ArgumentCaptor<List<PullRequestRef>> summarized = ArgumentCaptor.forClass(List.class);
        verify(summaries, times(5)).fetchSummaries(eq("pat"), summarized.capture(), eq("me"));
        assertThat(summarized.getAllValues().stream().flatMap(List::stream)).hasSize(121);
        ArgumentCaptor<Integer> warmed = ArgumentCaptor.forClass(Integer.class);
        verify(service, times(30)).refreshPullRequestDetailFromGitHub(eq("owner/repo"), warmed.capture());
        assertThat(warmed.getAllValues()).containsExactlyElementsOf(IntStream.iterate(121, number -> number - 1).limit(30).boxed().toList());
        verify(store, never()).completeRepositorySync(anyString(), any());
        verifyNoInteractions(detailStore, diffStore, actions, accounts, metadata);
    }

    @Test
    void exactFetchesCreatedPullRequestIntoWorkspaceProjection()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        FetchedPullRequest fetched = pullRequest();
        when(gitHub.getPullRequest("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(fetched);

        service.observeWorkspacePullRequest(repository, 7);

        verify(store).saveRepositorySyncPage(
                eq("owner/repo"),
                eq(ImmutableList.of(fetched.withViewerOrigin("me"))),
                anyString());
        verify(gitHub, never()).searchPullRequestsPaged(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void unchangedDefaultDiffIsNotRewritten()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        StoredPrDiff stored = new StoredPrDiff(
                ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line")), ImmutableList.of());
        when(diffStore.find(12L, null, null)).thenReturn(Optional.of(stored));
        when(diffStore.findDefaultEtags(12L))
                .thenReturn(new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-1\"")));
        when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), eq(Optional.of("\"files-1\""))))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("\"files-1\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), eq(Optional.of("\"commits-1\""))))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("\"commits-1\"")));

        snapshots.refreshDefaultDiff("pat", ref, 12L);

        verify(diffStore, never()).saveDefault(anyLong(), any(), any());
    }

    @Test
    void changedCommitsKeepUnchangedFilesAndTheirExpandedBodies()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        List<DiffFile> files = ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        StoredPrDiff stored = new StoredPrDiff(
                files, ImmutableList.of(), ImmutableMap.of("a.txt", new DiffFileContent("old", "new")));
        List<DiffCommit> commits = ImmutableList.of(
                new DiffCommit("sha1", null, "reworded", "me", Instant.parse("2026-08-27T00:00:00Z")));
        when(diffStore.find(12L, null, null)).thenReturn(Optional.of(stored));
        when(diffStore.findDefaultEtags(12L))
                .thenReturn(new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-1\"")));
        when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.empty(), Optional.of("\"files-1\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(commits), Optional.of("\"commits-2\"")));

        snapshots.refreshDefaultDiff("pat", ref, 12L);

        ArgumentCaptor<StoredPrDiff> saved = ArgumentCaptor.forClass(StoredPrDiff.class);
        verify(diffStore).saveDefault(eq(12L), saved.capture(), eq(
                new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-2\""))));
        assertThat(saved.getValue().files()).isEqualTo(files);
        assertThat(saved.getValue().commits()).isEqualTo(commits);
        assertThat(saved.getValue().fileContents()).containsKey("a.txt");
    }

    @Test
    void readsCachedDetail()
    {
        StoredPrDetail stored = stored();
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored));

        PullRequestDetail result = service.getPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("body");
        assertThat(result.approvalCount()).isEqualTo(1);
        assertThat(result.viewerCanWrite()).isTrue();
        verify(gitHub, never()).fetchPrDetail(anyString(), any());
        verify(credentialService, never()).resolveGitHubToken();
    }

    @Test
    void onlyOpeningDetailMarksThePullRequestViewed()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        service.getPullRequestDetail("owner/repo", 7);
        verify(viewStateStore, never()).markViewed(12L);

        service.openPullRequestDetail("owner/repo", 7);
        verify(viewStateStore).markViewed(12L);
    }

    @Test
    void detailReadDoesNotMutateTheStoredListRow()
    {
        Instant updatedAt = Instant.parse("2026-08-21T01:00:00Z");
        Instant mergedAt = Instant.parse("2026-08-21T00:59:00Z");
        StoredPrDetail stored = new StoredPrDetail(
                new PrRawDetail(
                        "title", "url", "merged", "alice", null, null, updatedAt, mergedAt,
                        "body", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, false, "unknown",
                        1, 1, 1, 0, ImmutableList.of(), "head", "feature", "main"),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored));

        service.getPullRequestDetail("owner/repo", 7);

        verify(store, never()).updateDetails(anyLong(), any());
    }

    @Test
    void missingDetailReturnsTheFreshResultWithoutRereadingTheCache()
    {
        PullRequestDetail refreshed = PullRequestDetailMapper.toPullRequestDetail(12L, "owner/repo", 7, stored(), true);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.empty());
        service = spy(service);
        doReturn(new PullRequestService.DetailRefresh(true, refreshed))
                .when(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);

        PullRequestDetail result = service.getPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("body");
        verify(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);
        verify(detailStore).find(12L);
    }

    @Test
    void missingDetailReturnsGitHubNotFound()
    {
        ResponseStatusException notFound = new ResponseStatusException(HttpStatus.NOT_FOUND, "Pull request does not exist");
        service = spy(service);
        doThrow(notFound).when(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);

        assertThatThrownBy(() -> service.getPullRequestDetail("owner/repo", 7))
                .isSameAs(notFound);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fullDetailRefreshPreservesStoredIdentityAndNotificationReviewRequests(boolean backgroundWarm, boolean reviewRequested)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest pullRequest = pullRequest(
                99, "open", Instant.parse("2026-08-21T00:00:00Z"), null);
        List<DiffFile> files = ImmutableList.of(
                new DiffFile("a.txt", "modified", 1, 0, "+line"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        // the store resolves the row on repo and number and hands back the stored row
        when(store.saveObserved(any())).thenAnswer(invocation -> storedPullRequest(invocation.getArgument(0)));
        when(gitHub.fetchPrNotificationRefs("pat")).thenReturn(reviewRequested
                ? ImmutableList.of(new AttentionPrRef(ref, "review_requested", REVIEWED_AT)) : ImmutableList.of());
        when(gitHub.getPullRequest("pat", ref, Optional.empty()))
                .thenReturn(new PullRequestFetch(Optional.of(pullRequest), Optional.of("\"etag-1\"")));
        when(accounts.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).thenReturn(true);
        when(gitHub.fetchPrDetail("pat", ref)).thenReturn(raw());
        when(gitHub.fetchPrReviews("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrCommits("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrReviewThreads("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrDescriptionReactions("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.enrichTimelineReferences(eq("pat"), eq(ref), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head"))
                .thenReturn(ImmutableList.of());
        when(viewStateStore.findByPrIds(ImmutableSet.of(12L)))
                .thenReturn(ImmutableMap.of());
        when(gitHub.fetchPrDiffFiles(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(files), Optional.of("\"files-1\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(ImmutableList.of()), Optional.of("\"commits-1\"")));

        if (backgroundWarm) {
            service.warmDetails(ImmutableList.of(pullRequest));
        }
        else {
            PullRequestService.DetailRefresh refreshed = service.refreshPullRequestDetailFromGitHub("owner/repo", 7);
            assertThat(refreshed.full()).isTrue();
            assertThat(refreshed.detail().body()).isEqualTo("body");
        }

        ArgumentCaptor<FetchedPullRequest> observed = ArgumentCaptor.forClass(FetchedPullRequest.class);
        verify(store).saveObserved(observed.capture());
        assertThat(observed.getValue().githubId()).isEqualTo(99L);
        assertThat(observed.getValue().origin()).isEqualTo(reviewRequested ? PullRequest.Origin.REVIEW_REQUESTED : PullRequest.Origin.OTHER);
        ArgumentCaptor<PullRequest.Origin> enrichedOrigin = ArgumentCaptor.forClass(PullRequest.Origin.class);
        verify(store).updateEnrichment(eq(12L), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(), any(), any(), anyList(), enrichedOrigin.capture(), any(), any(), any());
        assertThat(enrichedOrigin.getValue()).isEqualTo(reviewRequested ? PullRequest.Origin.REVIEW_REQUESTED : PullRequest.Origin.OTHER);
        ArgumentCaptor<StoredPrDetail> detail = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(12L), detail.capture());
        assertThat(detail.getValue().viewerCanWrite()).isTrue();
        verify(detailStore).updateEtag(12L, Optional.of("\"etag-1\""));
        verify(diffStore).saveDefault(eq(12L), any(StoredPrDiff.class), eq(
                new PrDiffEtags(Optional.of("\"files-1\""), Optional.of("\"commits-1\""))));
    }

    @Test
    void failedDiffRefreshClearsThePrEtagAndRetriesWithoutDiscardingCachedDiffs()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        AtomicReference<Optional<String>> etag = new AtomicReference<>(Optional.of("\"old-pr\""));
        List<DiffFile> oldFiles = ImmutableList.of(new DiffFile("old.txt", "modified", 1, 0, "+old"));
        List<DiffFile> newFiles = ImmutableList.of(new DiffFile("new.txt", "modified", 1, 0, "+new"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(store.saveObserved(any())).thenAnswer(invocation -> storedPullRequest(invocation.getArgument(0)));
        when(detailStore.findEtag(12L)).thenAnswer(invocation -> etag.get());
        doAnswer(invocation -> {
            etag.set(invocation.getArgument(1));
            return null;
        }).when(detailStore).updateEtag(eq(12L), any());
        when(gitHub.getPullRequest(eq("pat"), eq(ref), any()))
                .thenReturn(new PullRequestFetch(Optional.of(pullRequest()), Optional.of("\"new-pr\"")));
        when(gitHub.fetchPrDetail("pat", ref)).thenReturn(raw());
        when(gitHub.enrichTimelineReferences(eq("pat"), eq(ref), anyList())).thenAnswer(invocation -> invocation.getArgument(2));
        when(diffStore.find(12L, null, null)).thenReturn(Optional.of(new StoredPrDiff(oldFiles, ImmutableList.of(), ImmutableMap.of())));
        when(diffStore.findDefaultEtags(12L)).thenReturn(new PrDiffEtags(Optional.of("\"old-files\""), Optional.empty()));
        when(gitHub.fetchPrDiffFiles("pat", ref, oldFiles, Optional.of("\"old-files\"")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Diff unavailable"))
                .thenReturn(new ConditionalList<>(Optional.of(newFiles), Optional.of("\"new-files\"")));
        when(gitHub.fetchPrDiffCommits(eq("pat"), eq(ref), anyList(), any()))
                .thenReturn(new ConditionalList<>(Optional.of(ImmutableList.of()), Optional.of("\"commits\"")));

        assertThat(service.refreshPullRequestDetailFromGitHub("owner/repo", 7).full()).isTrue();

        assertThat(etag.get()).isEmpty();
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(diffStore, never()).saveDefault(anyLong(), any(), any());
        verify(diffStore, never()).deleteByPrIds(any());

        assertThat(service.refreshPullRequestDetailFromGitHub("owner/repo", 7).full()).isTrue();

        verify(gitHub).getPullRequest("pat", ref, Optional.of("\"old-pr\""));
        verify(gitHub).getPullRequest("pat", ref, Optional.empty());
        verify(gitHub, times(2)).fetchPrDetail("pat", ref);
        verify(diffStore).saveDefault(eq(12L), eq(new StoredPrDiff(newFiles, ImmutableList.of(), ImmutableMap.of())),
                eq(new PrDiffEtags(Optional.of("\"new-files\""), Optional.of("\"commits\""))));
        assertThat(etag.get()).contains("\"new-pr\"");
    }

    @Test
    void explicitDetailRefreshUsesTheStoredEtagAndRefreshesOnlyStatusesOnNotModified()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.findEtag(12L)).thenReturn(Optional.of("\"etag-1\""));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(gitHub.getPullRequest("pat", ref, Optional.of("\"etag-1\"")))
                .thenReturn(new PullRequestFetch(Optional.empty(), Optional.of("\"etag-1\"")));
        when(actions.refreshPrCheckRuns(
                eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.of("\"checks\""), Optional.of("\"statuses\"")));

        PullRequestService.DetailRefresh refreshed = service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        assertThat(refreshed.full()).isFalse();
        assertThat(refreshed.detail().body()).isEqualTo("body");

        verify(gitHub, never()).fetchPrDetail(anyString(), any());
        verify(store, never()).saveObserved(any());
        verify(detailStore, never()).save(anyLong(), any());
        verify(detailStore).saveStatus(eq(12L), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-60, 60})
    void detailRefreshNeverRegressesTheLatestObservedHeadTimestamp(Long commitOffset)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 12);
        Instant observedPush = REVIEWED_AT.plusSeconds(3600);
        PullRequest beforeSummary = attentionPullRequest(12, null, null, null, null);
        PullRequest current = attentionPullRequest(12, null, null, observedPush, null);
        List<PrTimelineEvent> timeline = commitOffset == null ? ImmutableList.of() : ImmutableList.of(
                new PrTimelineEvent(1L, "committed", "alice", observedPush.plusSeconds(commitOffset), "Commit", Optional.empty()));
        StoredPrDetail cached = new StoredPrDetail(raw(), ImmutableList.of(), timeline, ImmutableList.of(), ImmutableList.of());
        when(store.findIdByRepoAndNumber("owner/repo", 12)).thenReturn(Optional.of(12L));
        when(store.findById(12L)).thenReturn(Optional.of(beforeSummary), Optional.of(current));
        when(detailStore.find(12L)).thenReturn(Optional.of(cached));
        when(gitHub.getPullRequest("pat", ref, Optional.empty())).thenReturn(new PullRequestFetch(Optional.empty(), Optional.empty()));
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));

        service.refreshPullRequestDetailFromGitHub("owner/repo", 12);

        ArgumentCaptor<Instant> pushedAt = ArgumentCaptor.forClass(Instant.class);
        verify(store).updateEnrichment(eq(12L), any(), any(), anyInt(), anyInt(), anyInt(), any(), any(), pushedAt.capture(), any(), anyList(), any(), any(), any(), any());
        assertThat(pushedAt.getValue()).isEqualTo(commitOffset != null && commitOffset > 0 ? observedPush.plusSeconds(commitOffset) : observedPush);
    }

    @Test
    void manualDetailRefreshDoesNotSendTheStoredEtag()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        IllegalStateException failure = new IllegalStateException("stop after request");
        when(gitHub.getPullRequest("pat", ref, Optional.empty())).thenThrow(failure);

        assertThatThrownBy(() -> service.refreshAllPullRequestDetailFromGitHub("owner/repo", 7))
                .isSameAs(failure);

        verify(detailStore, never()).findEtag(anyLong());
    }

    @Test
    void statusRefreshUsesThePrEtagAndRefreshesCiAfterNotModified()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        PrCheckRunState latest = new PrCheckRunState(2L, "build", "completed", "failure", null, "check_run", null, null);
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(detailStore.findEtag(12L)).thenReturn(Optional.of("\"etag-1\""));
        when(gitHub.getPullRequest("pat", ref, Optional.of("\"etag-1\"")))
                .thenReturn(new PullRequestFetch(Optional.empty(), Optional.of("\"etag-1\"")));
        when(actions.refreshPrCheckRuns(
                eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(latest), Optional.of("\"checks-2\""), Optional.of("\"statuses-2\"")));

        StoredPrDetail refreshed = snapshots.refreshStatus("pat", ref, 12L);

        assertThat(refreshed.raw().mergeable()).isTrue();
        assertThat(refreshed.reviews()).containsExactly(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null));
        assertThat(refreshed.checkRuns()).containsExactly(latest);
        verify(detailStore).saveStatus(12L, refreshed);
        verify(detailStore, never()).updateEtag(anyLong(), any());
        verify(detailStore).updateStatusEtags(12L, Optional.of("\"checks-2\""), Optional.of("\"statuses-2\""));
        verify(gitHub, never()).fetchPrReviews(anyString(), any());
        verify(gitHub, never()).fetchPrDiffFiles(anyString(), any(), anyList(), any());
    }

    @Test
    void statusRefreshAppliesChangedMergeabilityFromThePrEndpoint()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        FetchedPullRequest conflicted = pullRequest(
                "open", Instant.parse("2026-08-24T11:00:00Z"), null, false, "dirty");
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(detailStore.findEtag(12L)).thenReturn(Optional.of("\"etag-1\""));
        when(gitHub.getPullRequest("pat", ref, Optional.of("\"etag-1\"")))
                .thenReturn(new PullRequestFetch(Optional.of(conflicted), Optional.of("\"etag-2\"")));
        when(actions.refreshPrCheckRuns(
                eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));

        StoredPrDetail refreshed = snapshots.refreshStatus("pat", ref, 12L);

        assertThat(refreshed.raw().mergeable()).isFalse();
        assertThat(refreshed.raw().mergeableState()).isEqualTo("dirty");
        verify(detailStore, never()).updateEtag(anyLong(), any());
    }

    @Test
    void postsCommentAndPatchesCachedTimeline()
    {
        PrTimelineEvent posted = new PrTimelineEvent(99L, "commented", "me", Instant.now(), "hello", Optional.empty());
        when(writes.createIssueComment("pat", PullRequestRef.of("owner", "repo", 7), "hello"))
                .thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        PullRequestDetail detail = service.commentOnPullRequest("owner/repo", 7, "hello");

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).contains(posted);
        assertThat(detail.recentActivity())
                .extracting(PullRequestDetail.ActivityItem::githubId)
                .containsExactly(99L);
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(diffStore, gitHub);
    }

    @Test
    void postsInlineCommentAndReturnsPatchedCachedTimeline()
    {
        InlineReviewComment comment = new InlineReviewComment(
                "src/Main.java", "Please simplify this", 18, "RIGHT", 16, "RIGHT", "head");
        PrTimelineEvent posted = new PrTimelineEvent(
                99L, "review_thread", "me", Instant.now(), null, Optional.empty());
        StoredPrDetail cached = stored();
        cached = new StoredPrDetail(
                cached.raw(),
                cached.reviews(),
                ImmutableList.of(posted),
                cached.checkRuns(),
                cached.unavailableSections(),
                cached.viewerCanWrite());
        when(writes.createReviewComment("pat", PullRequestRef.of("owner", "repo", 7), comment))
                .thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(cached));

        PullRequestDetail detail = service.createReviewComment("owner/repo", 7, comment);

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).containsExactly(posted);
        assertThat(detail.recentActivity())
                .extracting(PullRequestDetail.ActivityItem::githubId)
                .containsExactly(99L);
        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.COMMENTED), eq("head"), any(Instant.class));
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(diffStore, gitHub);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void routesEachSupportedReactionTargetThroughTheViewerCredential(Boolean viewerHasReacted)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);

        service.toggleReaction("owner/repo", 7, " pull_request ", null, " HEART ", viewerHasReacted);
        service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "+1", viewerHasReacted);
        service.toggleReaction("owner/repo", 7, "review_comment", 42L, "eyes", viewerHasReacted);

        verify(writes).togglePullRequestReaction("pat", ref, "heart", viewerHasReacted);
        verify(writes).toggleIssueCommentReaction("pat", ref, 41, "+1", viewerHasReacted);
        verify(writes).toggleReviewCommentReaction("pat", ref, 42, "eyes", viewerHasReacted);
    }

    @Test
    void setsKnownReactionStateAndMarksCachedDetailForRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(writes.toggleIssueCommentReaction("pat", ref, 41, "heart", false)).thenReturn(false);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        assertThat(service.toggleReaction(
                "owner/repo", 7, "issue_comment", 41L, "heart", false)).isFalse();

        verify(writes).toggleIssueCommentReaction("pat", ref, 41, "heart", false);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void preservesCachedDetailWhenReactionWriteFails()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(writes.toggleIssueCommentReaction("pat", ref, 41, "heart", false))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        assertThatThrownBy(() -> service.toggleReaction(
                "owner/repo", 7, "issue_comment", 41L, "heart", false))
                .isInstanceOf(ResponseStatusException.class);

        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void keepsDetailReadableWhenAReactionOverlapsAConditionalRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        Optional<String> originalEtag = Optional.of("\"etag-1\"");
        AtomicReference<Optional<String>> etag = new AtomicReference<>(originalEtag);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(detailStore.findEtag(12L)).thenAnswer(invocation -> etag.get());
        doAnswer(invocation -> {
            etag.set(invocation.getArgument(1));
            return null;
        }).when(detailStore).updateEtag(eq(12L), any());
        when(gitHub.getPullRequest("pat", ref, originalEtag)).thenAnswer(invocation -> {
            service.toggleReaction("owner/repo", 7, "issue_comment", 41L, "heart", true);
            return new PullRequestFetch(Optional.empty(), originalEtag);
        });
        when(actions.refreshPrCheckRuns(eq("pat"), eq("owner"), eq("repo"), eq("head"), anyList(), any(), any()))
                .thenReturn(new PrCheckRunsFetch(ImmutableList.of(), Optional.empty(), Optional.empty()));

        PullRequestService.DetailRefresh refreshed = service.refreshPullRequestDetailFromGitHub("owner/repo", 7);

        assertThat(refreshed.full()).isFalse();
        assertThat(refreshed.detail().body()).isEqualTo("body");
        assertThat(etag.get()).isEmpty();
        verify(detailStore, never()).deleteByPrIds(any());
        verify(detailStore).saveStatus(eq(12L), any());

        IllegalStateException stopAfterRequest = new IllegalStateException("stop after unconditional request");
        when(gitHub.getPullRequest("pat", ref, Optional.empty())).thenThrow(stopAfterRequest);
        assertThatThrownBy(() -> service.refreshPullRequestDetailFromGitHub("owner/repo", 7)).isSameAs(stopAfterRequest);
        verify(gitHub).getPullRequest("pat", ref, Optional.empty());
    }

    @Test
    void updatesDescriptionAndMarksCachedDetailForRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateDescription("owner/repo", 7, "Updated description");

        verify(writes).updatePullRequestDescription("pat", ref, "Updated description");
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void rejectsInvalidReactionTargetsAndContentBeforeWriting()
    {
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "pull_request", null, "smile", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "pull_request", 41L, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "issue_comment", null, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "review_comment", 0L, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.toggleReaction("owner/repo", 7, "commit", 41L, "heart", null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(writes);
    }

    @ParameterizedTest
    @CsvSource({"issue_comment, COMMENT, comment:41", "review_comment, COMMENT, review-comment:41", "review, REVIEW_BODY, review:41"})
    void editsACommentOnGitHubBeforePatchingOnlyThatTargetsCachedCopy(String target, RecentTextKind kind, String textKey)
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        // Every cached copy shares id 41, so a patch that ignored the target type would change another body too.
        StoredPrDetail expected = switch (target) {
            case "issue_comment" -> editableDetail("edited", "old reply", "old review");
            case "review_comment" -> editableDetail("old comment", "edited", "old review");
            default -> editableDetail("old comment", "old reply", "edited");
        };
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(editableDetail("old comment", "old reply", "old review")));

        PullRequestDetail detail = service.editComment("owner/repo", 7, " " + target.toUpperCase(Locale.ROOT) + " ", 41L, "edited");

        var order = inOrder(writes, detailStore);
        switch (target) {
            case "issue_comment" -> order.verify(writes).updateIssueComment("pat", ref, 41L, "edited");
            case "review_comment" -> order.verify(writes).updateReviewComment("pat", ref, 41L, "edited");
            default -> order.verify(writes).updateReviewBody("pat", ref, 41L, "edited");
        }
        order.verify(detailStore).save(12L, expected);
        assertThat(detail).isEqualTo(PullRequestDetailMapper.toPullRequestDetail(12L, "owner/repo", 7, expected, true));
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(recent).wroteOnPullRequest("owner/repo", 7, kind, textKey, "edited");
        verifyNoMoreInteractions(writes);
        verifyNoInteractions(gitHub, diffStore);
    }

    @Test
    void failedCommentEditLeavesTheCachedDetailAndRecentUntouched()
    {
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.FORBIDDEN, "Must be the comment author");
        doThrow(failure).when(writes).updateReviewComment("pat", PullRequestRef.of("owner", "repo", 7), 41L, "edited");

        assertThatThrownBy(() -> service.editComment("owner/repo", 7, "review_comment", 41L, "edited")).isSameAs(failure);

        verifyNoInteractions(store, detailStore, diffStore, recent);
    }

    @Test
    void rejectsInvalidCommentEditsBeforeWriting()
    {
        for (ThrowingCallable edit : ImmutableList.<ThrowingCallable>of(
                () -> service.editComment("owner/repo", 7, null, 41L, "edited"),
                () -> service.editComment("owner/repo", 7, "pull_request", 41L, "edited"),
                () -> service.editComment("owner/repo", 7, "issue_comment", 0L, "edited"),
                () -> service.editComment("owner/repo", 7, "review", -1L, "edited"),
                () -> service.editComment("owner/repo", 7, "review_comment", 41L, " "),
                () -> service.editComment("owner/repo", 7, "review_comment", 41L, null))) {
            assertThatThrownBy(edit).isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verifyNoInteractions(credentialService, writes, store, detailStore, recent);
    }

    private static StoredPrDetail editableDetail(String commentBody, String replyBody, String reviewBody)
    {
        PrTimelinePayload thread = PrTimelinePayload.builder()
                .path("a.txt")
                .line(4)
                .side("RIGHT")
                .comments(ImmutableList.of(
                        new PrTimelineComment(40L, "me", null, false, REVIEWED_AT, "root", null, 41L, null, ImmutableList.of()),
                        new PrTimelineComment(41L, "me", null, false, REVIEWED_AT, replyBody, null, 41L, 40L, ImmutableList.of())))
                .build();
        return new StoredPrDetail(
                raw(),
                ImmutableList.of(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null), review(41L, "COMMENTED", reviewBody)),
                ImmutableList.of(
                        new PrTimelineEvent(41L, "commented", "me", REVIEWED_AT, commentBody, Optional.empty()),
                        new PrTimelineEvent(41L, "reviewed", "me", REVIEWED_AT, reviewBody, PrTimelinePayload.builder().state("COMMENTED").build(), Optional.empty()),
                        new PrTimelineEvent(40L, "review_thread", "me", REVIEWED_AT, null, thread, Optional.empty())),
                ImmutableList.of(),
                ImmutableList.of(),
                true);
    }

    @Test
    void closesPullRequestAndInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.closePullRequest("owner/repo", 7);

        verify(writes).closePullRequest("pat", PullRequestRef.of("owner", "repo", 7));
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void marksDraftReadyForReviewAndPreservesTheSnapshotForRefresh()
    {
        when(merges.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_7"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.markReadyForReview("owner/repo", 7);

        verify(writes).markReadyForReview("pat", "PR_7");
        verify(recent).pullRequestAction("owner/repo", 7, RecentReason.EDITED, 0);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(writes, detailStore);
        verifyNoInteractions(diffStore);
    }

    @Test
    void failedReadyForReviewInvalidatesTheSnapshotWithoutRetryingTheWrite()
    {
        when(merges.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.of("PR_7"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        ResponseStatusException failure = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ready-for-review response is uncertain");
        doThrow(failure).when(writes).markReadyForReview("pat", "PR_7");

        assertThatThrownBy(() -> service.markReadyForReview("owner/repo", 7)).isSameAs(failure);

        verify(writes).markReadyForReview("pat", "PR_7");
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(writes, detailStore);
        verifyNoInteractions(recent, diffStore);
    }

    @Test
    void rejectsReadyForReviewWhenGitHubReturnsNoNodeId()
    {
        when(merges.pullRequestNodeId("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markReadyForReview("owner/repo", 7))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));

        verifyNoInteractions(writes, detailStore, recent);
    }

    @Test
    void repliesToReviewCommentAndInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.replyToReviewComment("owner/repo", 7, 99L, "fixed");

        verify(writes).replyToReviewComment("pat", PullRequestRef.of("owner", "repo", 7), 99L, "fixed");
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void changesReviewThreadResolutionAndMarksDetailForRefresh()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.findReviewThreadId("pat", ref, 99L)).thenReturn("PRRT_1");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.setReviewThreadResolved("owner/repo", 7, 99L, true);

        verify(writes).setReviewThreadResolved("pat", "PRRT_1", true);
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verify(diffStore, never()).deleteByPrIds(any());
    }

    @Test
    void submitsReviewVerdictsAndPatchesTheCachedDetail()
    {
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview(
                "pat", PullRequestRef.of("owner", "repo", 7), "REQUEST_CHANGES", "Please fix this", ImmutableList.of()))
                .thenReturn(review(501L, "REQUEST_CHANGES", "Please fix this"));

        PullRequestDetail detail = service.submitReview(
                "owner/repo", 7, 12L, "request_changes", "Please fix this", ImmutableList.of());

        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.CHANGES_REQUESTED), eq("head"), any(Instant.class));
        // The submitted review is spliced into the cache, so nothing is thrown away
        // and the caller needs no follow-up fetch.
        verify(detailStore, never()).deleteByPrIds(any());
        verify(gitHub, never()).fetchReviewComments(anyString(), any(), anyLong());
        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(12L), saved.capture());
        assertThat(saved.getValue().reviews()).extracting(PrReviewState::githubId).contains(501L);
        assertThat(saved.getValue().timeline()).singleElement().satisfies(review -> {
            assertThat(review.githubId()).isEqualTo(501L);
            assertThat(review.event()).isEqualTo("reviewed");
            assertThat(review.actor()).isEqualTo("me");
            assertThat(review.body()).isEqualTo("Please fix this");
            assertThat(review.payload().state()).isEqualTo("REQUEST_CHANGES");
        });
        assertThat(detail.recentActivity()).singleElement().satisfies(review -> {
            assertThat(review.githubId()).isEqualTo(501L);
            assertThat(review.eventType()).isEqualTo("reviewed");
            assertThat(review.body()).isEqualTo("Please fix this");
            assertThat(review.payload().state()).isEqualTo("REQUEST_CHANGES");
        });
    }

    @Test
    void readsBackTheInlineCommentsAReviewWasSubmittedWith()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        InlineReviewComment comment = new InlineReviewComment("a.txt", "note", 4, "RIGHT", null, null, "head");
        PrTimelineEvent thread = new PrTimelineEvent(
                901L, "review_thread", "me", REVIEWED_AT, null, Optional.empty());
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview("pat", ref, "COMMENT", "", ImmutableList.of(comment)))
                .thenReturn(review(502L, "COMMENTED", ""));
        when(gitHub.fetchReviewComments("pat", ref, 502L)).thenReturn(ImmutableList.of(thread));

        service.submitReview("owner/repo", 7, 12L, "comment", "", ImmutableList.of(comment));

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).extracting(PrTimelineEvent::githubId).contains(901L);
        assertThat(saved.getValue().reviews()).extracting(PrReviewState::githubId).contains(502L);
    }

    @Test
    void marksACommentReviewAsHandled()
    {
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(writes.submitReview(
                "pat", PullRequestRef.of("owner", "repo", 7), "COMMENT", "Looks good with one note", ImmutableList.of()))
                .thenReturn(review(503L, "COMMENTED", "Looks good with one note"));

        service.submitReview("owner/repo", 7, 12L, "comment", "Looks good with one note", ImmutableList.of());

        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.COMMENTED), eq("head"), any(Instant.class));
    }

    private static PrReviewState review(long githubId, String state, String body)
    {
        return new PrReviewState(githubId, "me", null, false, state, REVIEWED_AT, body, null, "head");
    }

    @Test
    void rejectsOneReviewCarryingCommentsFromDifferentCommits()
    {
        List<InlineReviewComment> comments = ImmutableList.of(
                new InlineReviewComment("a.txt", "note", 4, "RIGHT", null, null, "older"),
                new InlineReviewComment("b.txt", "note", 9, "RIGHT", null, null, "head"));

        assertThatThrownBy(() -> service.submitReview("owner/repo", 7, 12L, "COMMENT", "", comments))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("comments written on one commit");
        verify(writes, never()).submitReview(anyString(), any(), anyString(), anyString(), anyList());
    }

    @Test
    void rejectsCommentReviewWithoutBody()
    {
        assertThatThrownBy(() -> service.submitReview("owner/repo", 7, 12L, "COMMENT", " ", ImmutableList.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(writes, never()).submitReview(anyString(), any(), anyString(), anyString(), anyList());
    }

    @Test
    void mergesDirectlyWhenNoQueueIsConfigured()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));
        when(merges.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenReturn(new MergeResult("sha", true, "merged"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "squash");

        assertThat(result.merged()).isTrue();
        verify(viewStateStore).markReviewed(eq(12L), eq(HandledAction.MERGED), eq("head"), any(Instant.class));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verify(detailStore, never()).deleteByPrIds(any());
        verifyNoInteractions(diffStore);
    }

    @Test
    void enqueuesWhenGitHubRequiresMergeQueue()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(merges.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.METHOD_NOT_ALLOWED, "Merge queue is required"));
        when(merges.pullRequestNodeId("pat", ref)).thenReturn(Optional.of("PR_1"));
        when(merges.enqueuePullRequest("pat", "PR_1")).thenReturn(MergeResult.enqueued("queued"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "squash");

        assertThat(result.queued()).isTrue();
        verify(viewStateStore, never()).markReviewed(anyLong(), any(HandledAction.class), any(), any(Instant.class));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(diffStore, gitHub, actions);
    }

    @Test
    void enqueuesDirectlyForQueueAction()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(merges.pullRequestNodeId("pat", ref)).thenReturn(Optional.of("PR_1"));
        when(merges.enqueuePullRequest("pat", "PR_1")).thenReturn(MergeResult.enqueued("queued"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "queue");

        assertThat(result.queued()).isTrue();
        verify(merges, never()).mergePullRequest(anyString(), any(), any());
        verify(viewStateStore, never()).markReviewed(anyLong(), any(HandledAction.class), any(), any(Instant.class));
        verify(detailStore).updateEtag(12L, Optional.empty());
        verifyNoMoreInteractions(detailStore);
        verifyNoInteractions(diffStore, gitHub, actions);
    }

    @Test
    void readsDiffFilesFromLocalStore()
    {
        List<DiffFile> files = ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(diffStore.find(12L, null, null))
                .thenReturn(Optional.of(new StoredPrDiff(files, ImmutableList.of())));

        assertThat(service.getPullRequestDiffFiles("owner/repo", 7, null, null)).isEqualTo(files);

        verify(gitHub, never()).fetchPrDiffFiles(anyString(), any(), anyList(), any());
        verify(credentialService, never()).resolveGitHubToken();
    }

    @Test
    void derivesTheCurrentUsersLatestReviewVerdict()
    {
        assertThat(PullRequestService.deriveHandledActionFromReviews(ImmutableList.of(
                new PrReviewState(null, "me", null, false, "APPROVED", null, null, null, null),
                new PrReviewState(null, "me", null, false, "DISMISSED", null, null, null, null),
                new PrReviewState(null, "me", null, false, "CHANGES_REQUESTED", null, null, null, null)), "me"))
                .isEqualTo(HandledAction.CHANGES_REQUESTED);
        assertThat(PullRequestService.deriveHandledActionFromReviews(
                ImmutableList.of(new PrReviewState(null, "me", null, false, "COMMENTED", null, null, null, null)), "me"))
                .isEqualTo(HandledAction.COMMENTED);
        assertThat(PullRequestService.deriveHandledActionFromReviews(ImmutableList.of(
                new PrReviewState(null, "ME", null, false, "APPROVED", null, null, null, null),
                new PrReviewState(null, "me", null, false, "DISMISSED", null, null, null, null),
                new PrReviewState(null, "other", null, false, "APPROVED", null, null, null, null)), "me"))
                .isNull();
    }

    @Test
    void doesNotTreatEveryRepositoryPullRequestAsAReviewRequest()
    {
        assertThat(pullRequest().withViewerOrigin("me").origin())
                .isEqualTo(PullRequest.Origin.OTHER);
    }

    @Test
    void requiresAnotherReviewWhenTheHeadChanges()
    {
        PrViewState reviewed = new PrViewState(
                12L,
                Instant.parse("2026-08-20T09:00:00Z"),
                Instant.parse("2026-08-20T10:00:00Z"),
                HandledAction.APPROVED,
                "previous-head");

        assertThat(PullRequestService.needsReviewAgain(storedPullRequest(), stored(), "me", reviewed)).isTrue();
    }

    private static StoredPrDetail stored()
    {
        return new StoredPrDetail(
                raw(), ImmutableList.of(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null)), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(1L, "build", "completed", "success", null, "check_run", null, null)),
                ImmutableList.of(),
                true);
    }

    private static RepositoryMetadataSnapshot pickerSnapshot()
    {
        return new RepositoryMetadataSnapshot(
                1,
                RepoRef.of("owner", "repo"),
                null,
                0,
                ImmutableMap.of(),
                ImmutableList.of(),
                ImmutableList.of(
                        new RepositoryPickerUser(2, "alice", Optional.of("avatar-key"), false),
                        new RepositoryPickerUser(3, "robot[bot]", Optional.empty(), true)),
                ImmutableList.of(),
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Instant.parse("2026-08-21T00:00:00Z")),
                Optional.empty());
    }

    private static PrRawDetail raw()
    {
        return new PrRawDetail(
                null, null, null, null, null, null, null, null, "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 4, 2, 1, 0, ImmutableList.of(),
                "head", "feature", null);
    }

    private static PullRequest storedPullRequest()
    {
        return storedPullRequest(pullRequest());
    }

    private static PullRequest attentionPullRequest(long id, Instant reviewedAt, Instant reviewRequestedAt, Instant headPushedAt, PullRequestDetail.CiStatus ciStatus)
    {
        return attentionPullRequest(id, reviewedAt, reviewRequestedAt, headPushedAt, ciStatus, null);
    }

    private static PullRequest attentionPullRequest(
            long id, Instant reviewedAt, Instant reviewRequestedAt, Instant headPushedAt, PullRequestDetail.CiStatus ciStatus, Instant activityAt)
    {
        return new PullRequest(
                id, "owner/repo", (int) id, "Title", "alice", "https://github.com/owner/repo/pull/" + id,
                REVIEWED_AT, REVIEWED_AT, PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(),
                ImmutableMap.of(), false, null, reviewedAt, null, ImmutableList.of(),
                ciStatus, 0, 0, 0, "open", null, null,
                null, null, headPushedAt, "feature", null, activityAt, reviewRequestedAt, null);
    }

    private static PullRequest storedPullRequest(String state, Instant updatedAt, Instant closedAt)
    {
        return storedPullRequest(pullRequest(state, updatedAt, closedAt));
    }

    private static PullRequest storedPullRequest(FetchedPullRequest fetched)
    {
        return new PullRequest(
                12L, fetched.repo(), fetched.number(), fetched.title(), fetched.author(), fetched.htmlUrl(),
                fetched.createdAt(), fetched.updatedAt(), fetched.origin(), fetched.labels(),
                fetched.labelColors(), fetched.draft(), null, null, null, fetched.requestedReviewers(),
                null, 0, 0, 0, fetched.state(), fetched.closedAt(), fetched.mergedAt(),
                fetched.mergeable(), fetched.mergeableState(), null, fetched.headRef(), null, null, null, null);
    }

    private static GitHubPullRequestSummary summary(FetchedPullRequest fetched, PullRequestDetail.CiStatus ciStatus, String version)
    {
        return new GitHubPullRequestSummary(fetched, ciStatus, 4, 2, 1, "head", Optional.of(REVIEWED_AT), Optional.of("APPROVED"),
                Optional.empty(), Optional.empty(), version);
    }

    private static FetchedPullRequest pullRequestNumber(int number)
    {
        FetchedPullRequest original = pullRequest();
        return new FetchedPullRequest((long) number, original.repo(), number, original.title(), original.author(), original.htmlUrl(), original.createdAt(),
                REVIEWED_AT.plusSeconds(number), original.origin(), original.labels(), original.labelColors(), original.draft(), original.state(),
                original.closedAt(), original.mergedAt(), original.requestedReviewers(), original.mergeable(), original.mergeableState(), original.headRef());
    }

    private static PullRequest monitoredPullRequest(int number)
    {
        PullRequest row = storedPullRequest(pullRequestNumber(number));
        return new PullRequest(row.id(), row.repo(), row.number(), row.title(), row.author(), row.htmlUrl(), row.createdAt(), row.updatedAt(), row.origin(),
                row.labels(), row.labelColors(), row.draft(), row.viewedAt(), row.reviewedAt(), row.handledAction(), row.requestedReviewers(), row.ciStatus(),
                row.additions(), row.deletions(), row.commentCount(), row.state(), row.closedAt(), row.mergedAt(), row.mergeable(), row.mergeableState(),
                row.headPushedAt(), row.headRef(), REVIEWED_AT, row.activityAt(), row.reviewRequestedAt(), row.reviewDecision());
    }

    private static FetchedPullRequest pullRequest()
    {
        return pullRequest("open", Instant.parse("2026-08-21T00:00:00Z"), null);
    }

    private static FetchedPullRequest pullRequest(String state, Instant updatedAt, Instant closedAt)
    {
        return pullRequest(12, state, updatedAt, closedAt);
    }

    private static FetchedPullRequest pullRequest(long githubId, String state, Instant updatedAt, Instant closedAt)
    {
        return pullRequest(githubId, state, updatedAt, closedAt, null, null);
    }

    private static FetchedPullRequest pullRequest(
            String state,
            Instant updatedAt,
            Instant closedAt,
            Boolean mergeable,
            String mergeableState)
    {
        return pullRequest(12, state, updatedAt, closedAt, mergeable, mergeableState);
    }

    private static FetchedPullRequest pullRequest(
            long githubId,
            String state,
            Instant updatedAt,
            Instant closedAt,
            Boolean mergeable,
            String mergeableState)
    {
        return new FetchedPullRequest(
                githubId,
                "owner/repo",
                7,
                "Title",
                "alice",
                "https://github.com/owner/repo/pull/7",
                Instant.parse("2026-08-20T00:00:00Z"),
                updatedAt,
                PullRequest.Origin.AUTHORED,
                ImmutableList.of(),
                ImmutableMap.of(),
                false,
                state,
                closedAt,
                null,
                ImmutableList.of(),
                mergeable,
                mergeableState,
                "feature");
    }
}
