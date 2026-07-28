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

import com.bytequay.app.developmentflow.stage.V2PrRemoteControlService;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.threads.TaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestration coverage for saga delegation and explicit remote PR actions. */
class TestPRPublishService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRService prService = mock(PRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final PullRequestService pullRequestDetails = mock(PullRequestService.class);
    private final ReadyToMergeService readyToMerge = mock(ReadyToMergeService.class);
    private final PullRequestDetail liveDetail = mock(PullRequestDetail.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TaskPushSaga pushSaga = mock(TaskPushSaga.class);
    private final V2PrRemoteControlService v2Controls = mock(V2PrRemoteControlService.class);
    private final PRPublishService service =
            new PRPublishService(
                    prService, taskStore, pullRequests, patResolver, brainReview,
                    pullRequestDetails, readyToMerge, taskService, pushSaga,
                    v2Controls, Runnable::run);

    {
        when(prService.comments(anyString())).thenReturn(List.of());
        when(taskStore.findWorkflowVersion(anyString()))
                .thenReturn(Optional.of("LEGACY"));
        when(watchedRepos.findAll()).thenReturn(List.of());
        when(pullRequestDetails.fetchFreshPullRequestDetail(anyString(), anyInt())).thenReturn(liveDetail);
        when(readyToMerge.isReadyForMerge(nullable(String.class), eq(liveDetail))).thenReturn(true);
    }

    private PR pr(String status)
    {
        return PR.create("pr1", "task1", "feature/x", "main", "Add cache", "desc", NOW)
                .withStatus(status, NOW);
    }

    private PR pushedPr(String status)
    {
        return PR.create("pr1", "task1", "feature/x", "main", "Add cache", "desc", NOW)
                .withRemote("acme/widget", 145, "https://github.com/acme/widget/pull/145", NOW)
                .withStatus(status, NOW);
    }

    private Task task()
    {
        return taskAt(TaskStatus.AWAITING_REVIEW, TaskPhase.AWAITING_PUSH);
    }

    private Task taskAt(TaskStatus status, TaskPhase phase)
    {
        return new Task(
                "task1", "thread-1", 1L, status,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, phase, null, 0, null, null);
    }

    @Test
    void pushDelegatesToTheDurableSaga()
    {
        PR pushed = pushedPr(PR.STATUS_REMOTE_DRAFTED);
        when(pushSaga.push("pr1", false)).thenReturn(pushed);

        assertThat(service.push("pr1")).isSameAs(pushed);

        verify(pushSaga).push("pr1", false);
    }

    @Test
    void explicitApprovalDelegatesWithTheHumanOverride()
    {
        PR pushed = pushedPr(PR.STATUS_REMOTE_DRAFTED);
        when(pushSaga.push("pr1", true)).thenReturn(pushed);

        assertThat(service.push("pr1", true)).isSameAs(pushed);

        verify(pushSaga).push("pr1", true);
    }

    @Test
    void v2ApprovalCreatesTheTypedPublishOperationInsteadOfCallingTheLegacySaga()
    {
        PR local = pr(PR.STATUS_LOCAL_OPEN);
        when(prService.findById("pr1")).thenReturn(Optional.of(local));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThat(service.push("pr1", true)).isSameAs(local);

        verify(v2Controls).approveAndShip("task1", "pr1", true);
        verify(pushSaga, never()).push(anyString(), anyBoolean());
    }

    @Test
    void v2ManualMergeCreatesExactHeadAuthorityInsteadOfCallingGitHubDirectly()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        when(prService.findById("pr1")).thenReturn(Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThat(service.merge("pr1", "squash")).isSameAs(remote);

        verify(v2Controls).merge("task1", "squash");
        verify(pullRequests, never()).mergePullRequest(any(), any(), any());
        verify(taskService, never()).completeTasksForMergedPr(anyString(), anyInt());
    }

    @Test
    void v2ReviewerVisibleAndCleanupEffectsFailClosedBeforeRemoteIo()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        when(prService.findById("pr1")).thenReturn(Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThatThrownBy(() -> service.dequeue("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed durable V2 merge control");
        assertThatThrownBy(() -> service.deleteBranch("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed V2 Cleanup operation");
        assertThatThrownBy(() -> service.postComment("pr1", "hello"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed V2 feedback authorization");
        assertThatThrownBy(() -> service.publishReview("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("typed V2 review authorization");

        verify(pullRequests, never()).dequeuePullRequest(any(), any());
        verify(pullRequests, never()).deleteBranch(any(), any(), anyString());
        verify(pullRequests, never()).createIssueComment(any(), any(), anyString());
        verify(pullRequests, never()).createReview(any(), any(), any());
    }

    @Test
    void v2RemoteFactsNeverEnterTheLegacyPushAdoptionPath()
    {
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        service.reconcilePushedElsewhere(new PrPushedEvent(
                "task1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145"));

        verify(pushSaga, never()).adoptRemotePullRequest(any(), any(), anyInt(), any());
        verify(prService, never()).recordPush(any(), any(), anyInt(), any());
    }

    @Test
    void taskOwnedRemoteEffectFailsClosedWithoutAnImmutableWorkflowRoute()
    {
        PR local = pr(PR.STATUS_LOCAL_OPEN);
        when(prService.findById("pr1")).thenReturn(Optional.of(local));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.push("pr1", true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no immutable workflow route");

        verify(v2Controls, never()).approveAndShip(any(), any(), anyBoolean());
        verify(pushSaga, never()).push(anyString(), anyBoolean());
    }

    @Test
    void onPushedElsewhereAdvancesALocalOpenRowToRemoteDrafted()
    {
        // Mirrors a push/open_pr gate or the ship/next tool flow — none of
        // which call this service's own push(), so the row would otherwise
        // stay stuck offering "ready to push" for a push that already
        // happened.
        when(prService.findByTask("task1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));

        service.onPushedElsewhere(new PrPushedEvent("task1", "acme/widget", 145, "https://github.com/acme/widget/pull/145"));

        verify(prService).recordPush("pr1", "acme/widget", 145, "https://github.com/acme/widget/pull/145");
        verify(brainReview, never()).reviewBeforeLocalOpen(any(), any());
    }

    @Test
    void pushedElsewhereListenerHandsWorkOffTheCommittingThread()
    {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        PRPublishService asynchronous = new PRPublishService(
                prService, taskStore, pullRequests, patResolver, brainReview,
                pullRequestDetails, readyToMerge, taskService, pushSaga,
                v2Controls, submitted::set);
        when(prService.findByTask("task1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));
        PrPushedEvent event = new PrPushedEvent(
                "task1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145");

        asynchronous.onPushedElsewhere(event);

        assertThat(submitted.get()).isNotNull();
        verify(pushSaga, never()).adoptRemotePullRequest(any(), any(), anyInt(), any());
        submitted.get().run();
        verify(prService).recordPush(
                "pr1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145");
    }

    @Test
    void onPushedElsewhereLetsAnActiveSagaAdoptTheRemoteFact()
    {
        when(pushSaga.adoptRemotePullRequest(
                "task1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145"))
                .thenReturn(true);

        service.onPushedElsewhere(new PrPushedEvent(
                "task1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145"));

        verify(prService, never()).recordPush(any(), any(), anyInt(), any());
        verify(prService, never()).findByTask(any());
    }

    @Test
    void onPushedElsewhereRunsTheBrainReviewFirstWhenStillLocalDrafted()
    {
        when(prService.findByTask("task1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_DRAFTED)));
        when(brainReview.reviewBeforeLocalOpen("pr1", PRTimelineEntry.ACTOR_AGENT))
                .thenReturn(pr(PR.STATUS_LOCAL_OPEN));

        service.onPushedElsewhere(new PrPushedEvent("task1", "acme/widget", 145, "https://github.com/acme/widget/pull/145"));

        verify(brainReview).reviewBeforeLocalOpen("pr1", PRTimelineEntry.ACTOR_AGENT);
        verify(prService).recordPush("pr1", "acme/widget", 145, "https://github.com/acme/widget/pull/145");
    }

    @Test
    void onPushedElsewhereIsANoOpForATaskWithNoPr()
    {
        when(prService.findByTask("task-none")).thenReturn(Optional.empty());

        service.onPushedElsewhere(new PrPushedEvent("task-none", "x/y", 145, "https://github.com/x/y/pull/145"));

        verify(prService, never()).recordPush(any(), any(), anyInt(), any());
    }

    @Test
    void onLocalReviewClearedRecordsTheAutoApprovedPushGateWhenAutoMergeIsOn()
    {
        when(taskStore.isAutoMerge("task1")).thenReturn(true);
        when(pushSaga.push("pr1", false)).thenReturn(pushedPr(PR.STATUS_REMOTE_DRAFTED));

        service.onLocalReviewCleared(new LocalReviewClearedEvent("task1", "pr1", true));

        verify(pushSaga).push("pr1", false);
        verify(prService).recordGateApproval("pr1", "push", "auto-merge");
    }

    @Test
    void onLocalReviewClearedDoesNothingOnAnEscalationEvenWithAutoMergeOn()
    {
        when(taskStore.isAutoMerge("task1")).thenReturn(true);

        service.onLocalReviewCleared(new LocalReviewClearedEvent("task1", "pr1", false));

        verify(pushSaga, never()).push(any(), anyBoolean());
    }

    @Test
    void onLocalReviewClearedDoesNothingWithoutAutoMerge()
    {
        when(taskStore.isAutoMerge("task1")).thenReturn(false);

        service.onLocalReviewCleared(new LocalReviewClearedEvent("task1", "pr1", true));

        verify(pushSaga, never()).push(any(), anyBoolean());
    }

    @Test
    void onLocalReviewClearedSwallowsAPushFailureLeavingTheManualButtonAsTheFallback()
    {
        // E.g. an open local comment thread — push()'s own precondition
        // check throws; auto-merge just leaves it for the user to push
        // manually rather than propagating the failure.
        when(taskStore.isAutoMerge("task1")).thenReturn(true);
        when(pushSaga.push("pr1", false)).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "not ready"));

        service.onLocalReviewCleared(new LocalReviewClearedEvent("task1", "pr1", true));

        verify(pushSaga).push("pr1", false);
        verify(prService, never()).recordGateApproval(any(), any(), any());
    }

    @Test
    void mergeMergesTheRemotePrAndFlipsToMerged()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(pullRequests.mergePullRequest(eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any()))
                .thenReturn(new MergeResult("sha123", true, "Merged"));
        PR merged = pushedPr(PR.STATUS_MERGED);
        when(prService.recordMerged("pr1")).thenReturn(merged);

        PR result = service.merge("pr1", "squash");

        verify(pullRequests).mergePullRequest(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any());
        verify(prService).recordMerged("pr1");
        verify(taskService).completeTasksForMergedPr("acme/widget", 145);
        assertThat(result).isSameAs(merged);
    }

    @Test
    void mergeRejectsAStillDraftPrWithoutMarkingItReady()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_DRAFTED)));

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not an open, review-ready remote PR");

        verify(pullRequests, never()).setPullRequestDraft(any(), any(), anyBoolean());
        verify(pullRequests, never()).mergePullRequest(any(), any(), any());
    }

    @Test
    void mergeRejectsAPrThatWasNeverPushed()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not an open, review-ready remote PR");
    }

    @Test
    void mergeRejectsWhenFreshReadinessIsNotClear()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(readyToMerge.isReadyForMerge("task1", liveDetail)).thenReturn(false);

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not ready to merge");

        verify(pullRequestDetails).fetchFreshPullRequestDetail("acme/widget", 145);
        verify(pullRequests, never()).mergePullRequest(any(), any(), any());
        verify(pullRequests, never()).enqueuePullRequest(any(), any());
    }

    @Test
    void mergeSurfacesGitHubRefusalWithoutFlipping()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        try {
            when(git.remoteSlug(Path.of("/tmp/repo"), "origin"))
                    .thenReturn(Optional.of(new RepoRef("acme", "widget")));
            when(pullRequests.mergePullRequest(any(), any(), any()))
                    .thenReturn(new MergeResult(null, false, "not mergeable"));
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        assertThatThrownBy(() -> service.merge("pr1", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("did not merge");
        verify(prService, never()).recordMerged(any());
    }

    @Test
    void mergeEnqueuesInsteadOfMergingWhenTheBranchHasAMergeQueue()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PullRequestRef ref = new PullRequestRef("acme", "widget", 145);
        when(pullRequests.probeMergeQueue("ghp", ref))
                .thenReturn(Optional.of(new PullRequestRepository.MergeQueueProbe("PR_nodeid123")));
        when(pullRequests.enqueuePullRequest("ghp", "PR_nodeid123"))
                .thenReturn(MergeResult.enqueued("Queued"));

        PR result = service.merge("pr1", "squash");

        verify(pullRequests, never()).mergePullRequest(any(), any(), any());
        verify(prService, never()).recordMerged(any());
        verify(taskService).authorizeMergeForPr("acme/widget", 145);
        assertThat(result.status()).isEqualTo(PR.STATUS_REMOTE_OPEN);
    }

    @Test
    void mergeFallsBackToEnqueueOnA405RequiringTheMergeQueue()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PullRequestRef ref = new PullRequestRef("acme", "widget", 145);
        // Probe sees no queue (a ruleset-driven queue GraphQL can't see), so a
        // direct merge is attempted first and bounces with GitHub's 405.
        when(pullRequests.mergePullRequest(eq("ghp"), eq(ref), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "requires merge queue"));
        when(pullRequests.pullRequestNodeId("ghp", ref)).thenReturn(Optional.of("PR_nodeid456"));
        when(pullRequests.enqueuePullRequest("ghp", "PR_nodeid456"))
                .thenReturn(MergeResult.enqueued("Queued"));

        PR result = service.merge("pr1", "squash");

        verify(pullRequests).enqueuePullRequest("ghp", "PR_nodeid456");
        verify(prService, never()).recordMerged(any());
        verify(taskService).authorizeMergeForPr("acme/widget", 145);
        assertThat(result.status()).isEqualTo(PR.STATUS_REMOTE_OPEN);
    }

    @Test
    void dequeueRemovesThePrFromTheMergeQueue()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.dequeue("pr1");

        verify(pullRequests).dequeuePullRequest("ghp", new PullRequestRef("acme", "widget", 145));
    }

    @Test
    void deleteBranchDeletesOnGitHubAndStampsBranchDeletedAt()
            throws Exception
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_MERGED)));
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin")).thenReturn(Optional.of(new RepoRef("acme", "widget")));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PR deleted = pushedPr(PR.STATUS_MERGED);
        when(prService.recordBranchDeleted("pr1")).thenReturn(deleted);

        PR result = service.deleteBranch("pr1");

        verify(pullRequests).deleteBranch("ghp", new PullRequestRef("acme", "widget", 145), "feature/x");
        assertThat(result).isSameAs(deleted);
    }

    @Test
    void deleteBranchRejectsAPrThatIsNotMerged()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));

        assertThatThrownBy(() -> service.deleteBranch("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("is not merged");
        verify(prService, never()).recordBranchDeleted(any());
    }

    @Test
    void postCommentPublishesToThePushedPrAfterMerge()
            throws Exception
    {
        PR merged = pushedPr(PR.STATUS_MERGED);
        when(prService.findById("pr1")).thenReturn(Optional.of(merged));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        PR result = service.postComment("pr1", "  Thanks!  ");

        verify(pullRequests).createIssueComment(
                "ghp", new PullRequestRef("acme", "widget", 145), "Thanks!");
        assertThat(result).isSameAs(merged);
        // remote comes off the PR row, not the task's (possibly-gone) working dir
        verify(taskStore, never()).findTaskById(any());
    }

    @Test
    void mergeResolvesTheRemoteDirectlyForAnExternalPrWithoutTouchingAnyTask()
            throws Exception
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        PullRequestRef ref = new PullRequestRef("acme", "widget", 99);
        when(pullRequests.mergePullRequest(eq("ghp"), eq(ref), any()))
                .thenReturn(new MergeResult("sha123", true, "Merged"));
        when(prService.recordMerged("pr-ext")).thenReturn(externalPr());

        service.merge("pr-ext", "squash");

        verify(pullRequests).mergePullRequest(eq("ghp"), eq(ref), any());
        verify(taskStore, never()).findTaskById(any());
    }

    @Test
    void dequeueResolvesTheRemoteDirectlyForAnExternalPr()
            throws Exception
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.dequeue("pr-ext");

        verify(pullRequests).dequeuePullRequest("ghp", new PullRequestRef("acme", "widget", 99));
        verify(taskStore, never()).findTaskById(any());
    }

    @Test
    void deleteBranchResolvesTheRemoteDirectlyForAnExternalPr()
            throws Exception
    {
        PR merged = new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_MERGED, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, NOW, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(merged));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");
        when(prService.recordBranchDeleted("pr-ext")).thenReturn(merged);

        service.deleteBranch("pr-ext");

        verify(pullRequests).deleteBranch("ghp", new PullRequestRef("acme", "widget", 99), "feature/y");
        verify(taskStore, never()).findTaskById(any());
    }

    private PR externalPr()
    {
        return new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "", PR.STATUS_REMOTE_OPEN, NOW,
                null, 99, "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
    }

    private PR dashboardPr(PullRequest.Origin watchReason)
    {
        return externalPr().withGithubSync(new PR.PRSyncSnapshot(
                watchReason, NOW, List.of(), Map.of(), false, null, 0, 0, 0,
                null, null, null, null, Map.of(), List.of(), false, null));
    }

    private static PRComment draft(String id, String scope, String filePath, Integer lineNumber, String body)
    {
        return new PRComment(id, "pr-ext", PRComment.ORIGIN_LOCAL, scope,
                filePath, lineNumber, "you", body, NOW, null, null, null, null, null,
                "RIGHT", null, null);
    }

    @Test
    void publishReviewBatchesDraftsIntoOneGitHubReviewThenMarksThemPublished()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PRComment prLevel = draft("cm1", PRComment.SCOPE_PR, null, null, "Nice work overall.");
        PRComment lineLevel = draft("cm2", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Fix this.");
        when(prService.comments("pr-ext")).thenReturn(List.of(prLevel, lineLevel));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext");

        verify(pullRequests).createReview(eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), any());
        verify(prService).markPublished(eq("cm1"), any());
        verify(prService).markPublished(eq("cm2"), any());
    }

    @Test
    void publishReviewPreservesMultiLineCommentRanges()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PRComment ranged = new PRComment(
                "cm-range", "pr-ext", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 42, "agent", "Guard this range.", NOW,
                null, null, null, null, null, "RIGHT", 40, "RIGHT");
        when(prService.comments("pr-ext")).thenReturn(List.of(ranged));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext");

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), command.capture());
        assertThat(command.getValue().comments()).singleElement().satisfies(comment -> {
            assertThat(comment.path()).isEqualTo("src/Foo.java");
            assertThat(comment.line()).contains(42);
            assertThat(comment.startLine()).contains(40);
            assertThat(comment.startSide()).contains("RIGHT");
        });
    }

    @Test
    void publishReviewSkipsResolvedLocalDrafts()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PRComment pending = draft(
                "cm1", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Publish this.");
        PRComment resolved = draft(
                "cm2", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 43, "Already resolved.")
                .withResolved(NOW, PRTimelineEntry.ACTOR_USER);
        when(prService.comments("pr-ext")).thenReturn(List.of(pending, resolved));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext");

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), command.capture());
        assertThat(command.getValue().comments()).singleElement()
                .extracting(comment -> comment.body()).isEqualTo("Publish this.");
        verify(prService).markPublished(eq("cm1"), any());
        verify(prService, never()).markPublished(eq("cm2"), any());
    }

    @Test
    void publishReviewSkipsThreadRepliesAsSeparateReviewRoots()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PRComment root = draft(
                "cm1", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Publish this root.");
        PRComment reply = new PRComment(
                "cm2", "pr-ext", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 42, "you", "Keep this reply local.", NOW,
                null, null, null, "cm1", null, "RIGHT", null, null);
        when(prService.comments("pr-ext")).thenReturn(List.of(root, reply));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext");

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), command.capture());
        assertThat(command.getValue().comments()).singleElement()
                .extracting(comment -> comment.body()).isEqualTo("Publish this root.");
        verify(prService).markPublished(eq("cm1"), any());
        verify(prService, never()).markPublished(eq("cm2"), any());
    }

    @Test
    void explicitEmptySelectionApprovesWithoutPublishingPendingDrafts()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        PRComment pending = draft("cm1", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Do not publish me.");
        when(prService.comments("pr-ext")).thenReturn(List.of(pending));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext", "APPROVE", List.of(), List.of());

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), command.capture());
        assertThat(command.getValue().event()).isEqualTo("APPROVE");
        assertThat(command.getValue().body()).isEmpty();
        assertThat(command.getValue().comments()).isEmpty();
        verify(prService, never()).markPublished(eq("cm1"), any());
    }

    @Test
    void publishReviewIncludesTheOverallReviewBody()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(prService.comments("pr-ext")).thenReturn(List.of());
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext", "APPROVE", List.of(), List.of(), "Looks good to me.");

        ArgumentCaptor<CreateReviewCommand> command = ArgumentCaptor.forClass(CreateReviewCommand.class);
        verify(pullRequests).createReview(
                eq("ghp"), eq(new PullRequestRef("acme", "widget", 99)), command.capture());
        assertThat(command.getValue().event()).isEqualTo("APPROVE");
        assertThat(command.getValue().body()).contains("Looks good to me.");
    }

    @Test
    void publishedReviewsClearTheDashboardReviewRequestWithTheirVerdict()
    {
        when(prService.findById("pr-ext"))
                .thenReturn(Optional.of(dashboardPr(PullRequest.Origin.REVIEW_REQUESTED)));
        when(prService.comments("pr-ext")).thenReturn(List.of());
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr-ext", "APPROVE", List.of(), List.of(), "Looks good.");
        service.publishReview("pr-ext", "COMMENT", List.of(), List.of(), "Please check this.");
        service.publishReview("pr-ext", "REQUEST_CHANGES", List.of(), List.of(), "Please revise this.");

        verify(prService).markHandled("pr-ext", HandledAction.APPROVED);
        verify(prService).markHandled("pr-ext", HandledAction.COMMENTED);
        verify(prService).markHandled("pr-ext", HandledAction.CHANGES_REQUESTED);
    }

    @Test
    void topLevelCommentClearsOnlyADashboardReviewRequest()
    {
        when(prService.findById("pr-ext"))
                .thenReturn(Optional.of(dashboardPr(PullRequest.Origin.REVIEW_REQUESTED)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.postComment("pr-ext", "Thanks!");

        verify(prService).markHandled("pr-ext", HandledAction.COMMENTED);
    }

    @Test
    void commentOnAnAuthoredPrDoesNotChangeDashboardTriage()
    {
        when(prService.findById("pr-ext"))
                .thenReturn(Optional.of(dashboardPr(PullRequest.Origin.AUTHORED)));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.postComment("pr-ext", "Thanks!");

        verify(prService, never()).markHandled(anyString(), any());
    }

    @Test
    void publishReviewPublishesTaskPrOnceItReachesTheRemoteStage()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        PRComment fresh = new PRComment(
                "cm-task", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 10, "you", "Reviewing the remote PR.", NOW,
                null, null, null, null, null, "RIGHT", null, null);
        when(prService.comments("pr1")).thenReturn(List.of(fresh));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr1");

        verify(pullRequests).createReview(eq("ghp"), eq(new PullRequestRef("acme", "widget", 145)), any());
        verify(prService).markPublished(eq("cm-task"), any());
    }

    @Test
    void publishReviewStillRejectsALocalPhaseTaskPrWithNoRemoteIdentity()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pr(PR.STATUS_LOCAL_OPEN)));

        assertThatThrownBy(() -> service.publishReview("pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no remote identity");

        verify(pullRequests, never()).createReview(any(), any(), any());
    }

    @Test
    void publishReviewExcludesTaskDraftsStrippedOnPush()
    {
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        PRComment stripped = new PRComment(
                "cm-stripped", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, "you", "Drafted before the push — stays private.", NOW,
                null, null, NOW, null, null, "RIGHT", null, null);
        PRComment fresh = new PRComment(
                "cm-fresh", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, "you", "Drafted while reviewing the remote PR.", NOW,
                null, null, null, null, null, "RIGHT", null, null);
        when(prService.comments("pr1")).thenReturn(List.of(stripped, fresh));
        when(patResolver.resolve("acme/widget")).thenReturn("ghp");

        service.publishReview("pr1");

        verify(prService).markPublished(eq("cm-fresh"), any());
        verify(prService, never()).markPublished(eq("cm-stripped"), any());
    }

    @Test
    void publishReviewRejectsWhenThereAreNoDraftsToPublish()
    {
        when(prService.findById("pr-ext")).thenReturn(Optional.of(externalPr()));
        when(prService.comments("pr-ext")).thenReturn(List.of());

        assertThatThrownBy(() -> service.publishReview("pr-ext"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no draft comments");
        verify(pullRequests, never()).createReview(any(), any(), any());
    }
}
