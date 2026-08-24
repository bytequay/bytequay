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
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.PullRequestFetch;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestHistoryPage;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPullRequestService
{
    @Mock private GitHubPullRequestReadClient gitHub;
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

    private PullRequestService service;

    @BeforeEach
    void setUp()
    {
        PullRequestSnapshotService snapshots = new PullRequestSnapshotService(
                gitHub, actions, detailStore, diffStore, Runnable::run);
        service = new PullRequestService(
                gitHub, writes, repositories, merges, accounts, store, snapshots, viewStateStore,
                credentialService, Runnable::run);
        org.mockito.Mockito.lenient().when(credentialService.resolveGitHubToken()).thenReturn("pat");
    }

    @Test
    void rejectsMalformedRepositoryNames()
    {
        assertThatThrownBy(() -> service.getPullRequestDetail("repo", 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updatesPullRequestAssigneesAndLabelsThenInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateMetadata(
                "owner/repo", 7, ImmutableList.of(" alice "), ImmutableList.of("bug"));

        verify(writes).updateIssueMetadata(
                "pat",
                PullRequestRef.of("owner", "repo", 7),
                ImmutableList.of("alice"),
                ImmutableList.of("bug"));
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void updatesPullRequestReviewerThenInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.updateReviewerRequest("owner/repo", 7, " alice ", true);

        verify(writes).updateReviewerRequest(
                "pat", PullRequestRef.of("owner", "repo", 7), "alice", true);
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
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
    void workspaceSyncScopesGitHubAndDoesNotReplaceGlobalList()
    {
        RepoRef repository = RepoRef.of("owner", "repo");
        when(gitHub.searchPullRequestsPaged(
                eq("pat"), org.mockito.ArgumentMatchers.contains("repo:owner/repo"), anyInt(), anyInt(),
                eq("updated"), eq("desc")))
                .thenReturn(new PullRequestHistoryPage(ImmutableList.of(), 1, 100, 0, false));

        service.syncRepository(repository);

        verify(store).completeRepositorySync(eq("owner/repo"), anyString());
        verify(store, never()).saveDashboardSyncPage(any(), anyString());
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

        verify(store, never()).updateState(any(Long.class), any(), any(), any());
    }

    @Test
    void missingDetailRefreshesAndReadsTheCachedResult()
    {
        StoredPrDetail stored = stored();
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.empty(), Optional.of(stored));
        service = spy(service);
        doReturn(true).when(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);

        PullRequestDetail result = service.getPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("body");
        verify(service).refreshPullRequestDetailFromGitHub("owner/repo", 7);
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

    @Test
    void explicitDetailRefreshStoresThePullRequestDetailAndDefaultDiff()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        PullRequest pullRequest = pullRequest();
        List<DiffFile> files = ImmutableList.of(
                new DiffFile("a.txt", "modified", 1, 0, "+line"));
        when(gitHub.getPullRequest("pat", ref, Optional.empty()))
                .thenReturn(new PullRequestFetch(Optional.of(pullRequest), Optional.of("\"etag-1\"")));
        when(accounts.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).thenReturn(true);
        when(gitHub.fetchPrDetail("pat", ref)).thenReturn(raw());
        when(gitHub.fetchPrReviews("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrTimeline("pat", ref, null)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrIssueComments("pat", ref, null)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrCommits("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrReviewThreads("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.fetchPrDescriptionReactions("pat", ref)).thenReturn(ImmutableList.of());
        when(gitHub.enrichTimelineReferences(eq("pat"), eq(ref), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head"))
                .thenReturn(ImmutableList.of());
        when(viewStateStore.findByPrIds(ImmutableSet.of(pullRequest.id())))
                .thenReturn(ImmutableMap.of());
        when(gitHub.fetchPrDiffFiles("pat", ref)).thenReturn(files);
        when(gitHub.fetchPrDiffCommits("pat", ref)).thenReturn(ImmutableList.of());

        assertThat(service.refreshPullRequestDetailFromGitHub("owner/repo", 7)).isTrue();

        verify(store).saveObserved(any(PullRequest.class));
        ArgumentCaptor<StoredPrDetail> detail = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(pullRequest.id()), detail.capture());
        assertThat(detail.getValue().viewerCanWrite()).isTrue();
        verify(detailStore).updateEtag(pullRequest.id(), Optional.of("\"etag-1\""));
        verify(diffStore).save(eq(pullRequest.id()), eq(null), eq(null), any(StoredPrDiff.class));
    }

    @Test
    void explicitDetailRefreshUsesTheStoredEtagAndKeepsLocalDataOnNotModified()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.findEtag(12L)).thenReturn(Optional.of("\"etag-1\""));
        when(gitHub.getPullRequest("pat", ref, Optional.of("\"etag-1\"")))
                .thenReturn(new PullRequestFetch(Optional.empty(), Optional.of("\"etag-1\"")));

        assertThat(service.refreshPullRequestDetailFromGitHub("owner/repo", 7)).isFalse();

        verify(gitHub, never()).fetchPrDetail(anyString(), any());
        verify(store, never()).saveObserved(any());
        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void postsCommentAndPatchesCachedTimeline()
    {
        PrTimelineEvent posted = new PrTimelineEvent(99L, "commented", "me", Instant.now(), "hello", Optional.empty());
        when(writes.createIssueComment("pat", PullRequestRef.of("owner", "repo", 7), "hello"))
                .thenReturn(posted);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        service.commentOnPullRequest("owner/repo", 7, "hello");

        ArgumentCaptor<StoredPrDetail> saved = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(12L), saved.capture());
        assertThat(saved.getValue().timeline()).contains(posted);
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
    void changesReviewThreadResolutionAndInvalidatesDetail()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(gitHub.findReviewThreadId("pat", ref, 99L)).thenReturn("PRRT_1");
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.setReviewThreadResolved("owner/repo", 7, 99L, true);

        verify(writes).setReviewThreadResolved("pat", "PRRT_1", true);
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void approvesAndMarksReviewed()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.approvePullRequest("owner/repo", 7, 12L);

        verify(writes).approve("pat", PullRequestRef.of("owner", "repo", 7));
        verify(viewStateStore).markReviewed(12L, HandledAction.APPROVED);
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void submitsReviewVerdictsAndInvalidatesDetail()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.submitReview("owner/repo", 7, 12L, "request_changes", "Please fix this");

        verify(writes).submitReview(
                "pat", PullRequestRef.of("owner", "repo", 7), "REQUEST_CHANGES", "Please fix this");
        verify(viewStateStore).markReviewed(12L, HandledAction.CHANGES_REQUESTED);
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void rejectsCommentReviewWithoutBody()
    {
        assertThatThrownBy(() -> service.submitReview("owner/repo", 7, 12L, "COMMENT", " "))
                .isInstanceOf(ResponseStatusException.class);
        verify(writes, never()).submitReview(anyString(), any(), anyString(), anyString());
    }

    @Test
    void mergesDirectlyWhenNoQueueIsConfigured()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(merges.mergePullRequest(eq("pat"), eq(ref), any(MergePullRequestCommand.class)))
                .thenReturn(new MergeResult("sha", true, "merged"));

        MergeResult result = service.mergePullRequest("owner/repo", 7, 12L, "squash");

        assertThat(result.merged()).isTrue();
        verify(viewStateStore).markReviewed(12L, HandledAction.MERGED);
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
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
        verify(viewStateStore, never()).markReviewed(any(Long.class), any(HandledAction.class));
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
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
        verify(viewStateStore, never()).markReviewed(any(Long.class), any(HandledAction.class));
        verify(detailStore).deleteByPrIds(ImmutableSet.of(12L));
    }

    @Test
    void readsDiffFilesFromLocalStore()
    {
        List<DiffFile> files = ImmutableList.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(diffStore.find(12L, null, null))
                .thenReturn(Optional.of(new StoredPrDiff(files, ImmutableList.of())));

        assertThat(service.getPullRequestDiffFiles("owner/repo", 7)).isEqualTo(files);

        verify(gitHub, never()).fetchPrDiffFiles(anyString(), any());
        verify(credentialService, never()).resolveGitHubToken();
    }

    @Test
    void derivesTheCurrentUsersLatestReviewVerdict()
    {
        assertThat(PullRequestService.deriveHandledActionFromReviews(ImmutableList.of(
                new PrReviewState("me", "APPROVED"),
                new PrReviewState("me", "DISMISSED"),
                new PrReviewState("me", "CHANGES_REQUESTED")), "me"))
                .isEqualTo(HandledAction.CHANGES_REQUESTED);
    }

    private static StoredPrDetail stored()
    {
        return new StoredPrDetail(
                raw(), ImmutableList.of(new PrReviewState("alice", "APPROVED")), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(1L, "build", "completed", "success")),
                ImmutableList.of(),
                true);
    }

    private static PrRawDetail raw()
    {
        return new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 4, 2, 1, 0, ImmutableList.of(),
                "head", "feature");
    }

    private static PullRequest pullRequest()
    {
        return new PullRequest(
                12,
                "owner/repo",
                7,
                "Title",
                "alice",
                "https://github.com/owner/repo/pull/7",
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
                PullRequest.Origin.AUTHORED,
                ImmutableList.of(),
                ImmutableMap.of(),
                false,
                null,
                null,
                null,
                ImmutableList.of(),
                null,
                0,
                0,
                0,
                null,
                "open",
                null,
                null,
                null,
                null,
                null,
                ImmutableMap.of(),
                "feature");
    }
}
