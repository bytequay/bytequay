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
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.MergePullRequestCommand;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrViewStateStore;
import com.bytequay.core.store.PullRequestStore;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestPullRequestService
{
    @Mock private GitHubPullRequestReadClient gitHub;
    @Mock private GitHubPullRequestWriteClient writes;
    @Mock private GitHubMergeClient merges;
    @Mock private GitHubActionsClient actions;
    @Mock private GitHubAccountClient accounts;
    @Mock private PullRequestStore store;
    @Mock private PrDetailStore detailStore;
    @Mock private PrViewStateStore viewStateStore;
    @Mock private CredentialService credentialService;

    private PullRequestService service;

    @BeforeEach
    void setUp()
    {
        service = new PullRequestService(
                gitHub, writes, merges, actions, accounts, store, detailStore, viewStateStore,
                credentialService, Runnable::run, Runnable::run);
        org.mockito.Mockito.lenient().when(credentialService.resolveGitHubToken()).thenReturn("pat");
    }

    @Test
    void rejectsMalformedRepositoryNames()
    {
        assertThatThrownBy(() -> service.getPullRequestDetail("repo", 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void readsCachedDetail()
    {
        StoredPrDetail stored = stored();
        when(accounts.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).thenReturn(true);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored));

        PullRequestDetail result = service.getPullRequestDetail("owner/repo", 7);

        assertThat(result.body()).isEqualTo("body");
        assertThat(result.approvalCount()).isEqualTo(1);
        assertThat(result.viewerCanWrite()).isTrue();
        verify(gitHub, never()).fetchPrDetail(anyString(), any());
    }

    @Test
    void reconcilesCachedMergedDetailIntoTheListRow()
    {
        Instant updatedAt = Instant.parse("2026-08-21T01:00:00Z");
        Instant mergedAt = Instant.parse("2026-08-21T00:59:00Z");
        StoredPrDetail stored = new StoredPrDetail(
                new PrRawDetail(
                        "title", "url", "merged", "alice", null, null, updatedAt, mergedAt,
                        "body", List.of(), Map.of(), List.of(), null, false, false, "unknown",
                        1, 1, 1, 0, List.of(), "head", "feature", "main"),
                List.of(), List.of(), List.of());
        when(accounts.fetchViewerCanWrite("pat", RepoRef.of("owner", "repo"))).thenReturn(true);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored));

        service.getPullRequestDetail("owner/repo", 7);

        verify(store).updateState(12L, "merged", updatedAt, mergedAt);
    }

    @Test
    void fetchesAndCachesColdDetail()
    {
        PullRequestRef ref = PullRequestRef.of("owner", "repo", 7);
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.empty());
        when(gitHub.fetchPrDetail("pat", ref)).thenReturn(raw());
        when(gitHub.fetchPrReviews("pat", ref)).thenReturn(List.of(new PrReviewState("alice", "APPROVED")));
        when(gitHub.fetchPrTimeline("pat", ref, null)).thenReturn(List.of());
        when(gitHub.fetchPrIssueComments("pat", ref, null)).thenReturn(List.of());
        when(actions.fetchPrCheckRuns("pat", "owner", "repo", "head")).thenReturn(List.of());

        service.getPullRequestDetail("owner/repo", 7);

        verify(detailStore).save(eq(12L), any(StoredPrDetail.class));
    }

    @Test
    void refreshInvalidatesBeforeReading()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));
        when(detailStore.find(12L)).thenReturn(Optional.of(stored()));

        service.refreshPullRequestDetail("owner/repo", 7);

        verify(detailStore).deleteByPrIds(Set.of(12L));
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
        verify(detailStore).deleteByPrIds(Set.of(12L));
    }

    @Test
    void approvesAndMarksReviewed()
    {
        when(store.findIdByRepoAndNumber("owner/repo", 7)).thenReturn(Optional.of(12L));

        service.approvePullRequest("owner/repo", 7, 12L);

        verify(writes).approve("pat", PullRequestRef.of("owner", "repo", 7));
        verify(viewStateStore).markReviewed(12L, HandledAction.APPROVED);
        verify(detailStore).deleteByPrIds(Set.of(12L));
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
        verify(detailStore).deleteByPrIds(Set.of(12L));
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
        verify(detailStore).deleteByPrIds(Set.of(12L));
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
        verify(detailStore).deleteByPrIds(Set.of(12L));
    }

    @Test
    void returnsDiffFilesFromGitHub()
    {
        List<DiffFile> files = List.of(new DiffFile("a.txt", "modified", 1, 0, "+line"));
        when(gitHub.fetchPrDiffFiles("pat", PullRequestRef.of("owner", "repo", 7))).thenReturn(files);
        assertThat(service.getPullRequestDiffFiles("owner/repo", 7)).isEqualTo(files);
    }

    @Test
    void derivesTheCurrentUsersLatestReviewVerdict()
    {
        assertThat(PullRequestService.deriveHandledActionFromReviews(List.of(
                new PrReviewState("me", "APPROVED"),
                new PrReviewState("me", "DISMISSED"),
                new PrReviewState("me", "CHANGES_REQUESTED")), "me"))
                .isEqualTo(HandledAction.CHANGES_REQUESTED);
    }

    private static StoredPrDetail stored()
    {
        return new StoredPrDetail(
                raw(), List.of(new PrReviewState("alice", "APPROVED")), List.of(),
                List.of(new PrCheckRunState(1L, "build", "completed", "success")));
    }

    private static PrRawDetail raw()
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 4, 2, 1, 0, List.of(),
                "head", "feature");
    }
}
