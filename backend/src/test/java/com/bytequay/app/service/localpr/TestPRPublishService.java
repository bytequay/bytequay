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

import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.developmentflow.stage.V2PrRemoteControlService;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.threads.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Orchestration coverage for saga delegation and explicit remote PR actions. */
class TestPRPublishService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRService prService = mock(PRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final PullRequestService pullRequestDetails = mock(PullRequestService.class);
    private final ReadyToMergeService readyToMerge = mock(ReadyToMergeService.class);
    private final PullRequestDetail liveDetail = mock(PullRequestDetail.class);
    private final TaskService taskService = mock(TaskService.class);
    private final TaskPushSaga pushSaga = mock(TaskPushSaga.class);
    private final V2PrRemoteControlService v2Controls = mock(V2PrRemoteControlService.class);
    private final V2UserRemoteActionRuntime v2UserRemoteActions =
            mock(V2UserRemoteActionRuntime.class);
    private final PRPublishService service =
            new PRPublishService(
                    prService, taskStore, pullRequests, patResolver, brainReview,
                    pullRequestDetails, readyToMerge, taskService, pushSaga,
                    v2Controls, v2UserRemoteActions);

    {
        when(prService.comments(anyString())).thenReturn(List.of());
        when(taskStore.findWorkflowVersion(anyString()))
                .thenReturn(Optional.of("LEGACY"));
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

    private PR standalonePr(String status)
    {
        return PR.create("pr1", null, "feature/x", "main", "Add cache", "desc", NOW)
                .withStatus(status, NOW);
    }

    private PR standalonePushedPr(String status)
    {
        return PR.createExternal(
                "pr1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145", "@octocat",
                "feature/x", "main", "Add cache", "desc", status, NOW,
                PR.STATUS_MERGED.equals(status) ? NOW : null,
                PR.STATUS_CLOSED.equals(status) ? NOW : null);
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

        assertThat(service.push("push-command", "pr1", true)).isSameAs(local);

        verify(v2Controls).approveAndShip(
                "push-command", "task1", "pr1", true);
        verify(pushSaga, never()).push(anyString(), anyBoolean());
    }

    @Test
    void genericRemoteWriteResolverReturnsTheTaskPrForV2Ownership()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        when(prService.findTaskByRepoAndNumber("acme/widget", 145))
                .thenReturn(Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThat(service.findV2TaskPullRequest("acme/widget", 145))
                .containsSame(remote);
    }

    @Test
    void genericRemoteWriteResolverFailsClosedForActiveV2TaskWithoutLocalPrIdentity()
    {
        when(prService.findTaskByRepoAndNumber("acme/widget", 145))
                .thenReturn(Optional.empty());
        when(taskStore.findActiveTaskByPrRef("acme/widget#145"))
                .thenReturn(Optional.of(task()));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThatThrownBy(() -> service.findV2TaskPullRequest("acme/widget", 145))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.getReason()).contains("local PR identity is unavailable");
                        });
    }

    @Test
    void genericRemoteWriteResolverFailsClosedWhenTaskRouteIsMissing()
    {
        when(prService.findTaskByRepoAndNumber("acme/widget", 145))
                .thenReturn(Optional.of(pushedPr(PR.STATUS_REMOTE_OPEN)));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findV2TaskPullRequest("acme/widget", 145))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.getReason()).contains("no immutable workflow route");
                        });
    }

    @Test
    void v2ManualMergeCreatesExactHeadAuthorityInsteadOfCallingGitHubDirectly()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        when(prService.findById("pr1")).thenReturn(Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThat(service.merge("merge-command", "pr1", "squash"))
                .isSameAs(remote);

        verify(v2Controls).merge("merge-command", "task1", "squash");
        verify(pullRequests, never()).mergePullRequest(any(), any(), any());
        verify(taskService, never()).completeTasksForMergedPr(anyString(), anyInt());
    }

    @Test
    void v2ReviewerVisibleAndCleanupEffectsUseTypedDurableActions()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        PR merged = pushedPr(PR.STATUS_MERGED);
        when(prService.findById("pr1"))
                .thenReturn(Optional.of(remote), Optional.of(remote),
                        Optional.of(merged), Optional.of(merged),
                        Optional.of(remote), Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        service.dequeue("dequeue-command", "pr1");
        service.deleteBranch("delete-command", "pr1");
        service.postComment("comment-command", "pr1", " hello ");

        verify(v2UserRemoteActions).dequeue(
                "dequeue-command", "task1", "pr1");
        verify(v2UserRemoteActions).deleteRemoteBranch(
                "delete-command", "task1", "pr1", "feature/x");
        verify(v2UserRemoteActions).postTopLevelComment(
                "comment-command", "task1", "pr1", "hello",
                HandledAction.COMMENTED);

        verify(pullRequests, never()).dequeuePullRequest(any(), any());
        verify(pullRequests, never()).deleteBranch(any(), any(), anyString());
        verify(pullRequests, never()).createIssueComment(any(), any(), anyString());
        verify(pullRequests, never()).createReview(any(), any(), any());
    }

    @Test
    void v2ApprovalWithoutCommentsCreatesADurableFrozenReviewAction()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        when(prService.findById("pr1"))
                .thenReturn(Optional.of(remote), Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThat(service.publishReview(
                "review-command", "pr1", "APPROVE", List.of(), List.of(), ""))
                .isSameAs(remote);

        verify(v2UserRemoteActions).submitReview(
                "review-command", "task1", "pr1", "APPROVE", "", List.of(),
                HandledAction.APPROVED);
        verify(pullRequests, never()).createReview(any(), any(), any());
    }

    @Test
    void everyV2RemoteActionRequiresTheCallerCommandId()
    {
        PR remote = pushedPr(PR.STATUS_REMOTE_OPEN);
        PR merged = pushedPr(PR.STATUS_MERGED);
        when(prService.findById("pr1")).thenReturn(
                Optional.of(remote), Optional.of(merged), Optional.of(remote),
                Optional.of(remote));
        when(taskStore.findWorkflowVersion("task1")).thenReturn(Optional.of("V2"));

        assertThatThrownBy(() -> service.dequeue(" ", "pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.deleteBranch(null, "pr1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.postComment(null, "pr1", "hello"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> service.publishReview(
                null, "pr1", "APPROVE", List.of(), List.of(), ""))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure -> ((ResponseStatusException) failure)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(v2UserRemoteActions, never()).dequeue(any(), any(), any());
        verify(v2UserRemoteActions, never()).deleteRemoteBranch(
                any(), any(), any(), any());
        verify(v2UserRemoteActions, never()).postTopLevelComment(
                any(), any(), any(), any(), any());
        verify(v2UserRemoteActions, never()).submitReview(
                any(), any(), any(), any(), any(), any(), any());
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

        verify(v2Controls, never()).approveAndShip(
                any(), any(), any(), anyBoolean());
        verify(pushSaga, never()).push(anyString(), anyBoolean());
    }

    @Test
    void historicalPushEventsCannotReenterLegacyReconciliation()
    {
        assertThatThrownBy(() -> service.onPushedElsewhere(new PrPushedEvent(
                "task1", "acme/widget", 145,
                "https://github.com/acme/widget/pull/145")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Historical LEGACY Task-owned PR");

        verify(pushSaga, never()).adoptRemotePullRequest(any(), any(), anyInt(), any());
        verify(prService, never()).recordPush(any(), any(), anyInt(), any());
        verify(prService, never()).findByTask(any());
    }

    @Test
    void historicalLocalReviewEventsCannotReenterLegacyAutoPush()
    {
        assertThatThrownBy(() -> service.onLocalReviewCleared(
                new LocalReviewClearedEvent("task1", "pr1", true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Historical LEGACY Task-owned PR");

        verify(pushSaga, never()).push(anyString(), anyBoolean());
        verify(prService, never()).recordGateApproval(any(), any(), any());
    }

    @Test
    void externalRemoteWritesAuthorizeDurableReviewTrunkCommands()
    {
        PR open = externalPr();
        PR merged = new PR(
                "pr-merged", null, "feature/merged", "main", "Merged change", "",
                PR.STATUS_MERGED, NOW, null, 100,
                "https://github.com/acme/widget/pull/100", null, NOW, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
        when(prService.findById("pr-ext")).thenReturn(Optional.of(open));
        when(prService.findById("pr-merged")).thenReturn(Optional.of(merged));

        assertThat(service.merge(
                "merge-command", "pr-ext", "squash")).isSameAs(open);
        assertThat(service.dequeue(
                "dequeue-command", "pr-ext")).isSameAs(open);
        assertThat(service.deleteBranch(
                "delete-command", "pr-merged")).isSameAs(merged);
        assertThat(service.postComment(
                "comment-command", "pr-ext", "  Thanks!  ")).isSameAs(open);

        verify(v2UserRemoteActions).authorizeExternal(
                "merge-command", "pr-ext", SemanticAction.MERGE,
                ActionPayload.value("squash"));
        verify(v2UserRemoteActions).authorizeExternal(
                "dequeue-command", "pr-ext", SemanticAction.DEQUEUE,
                ActionPayload.empty());
        verify(v2UserRemoteActions).authorizeExternal(
                "delete-command", "pr-merged",
                SemanticAction.DELETE_REMOTE_BRANCH,
                new ActionPayload(
                        1, null, null, "feature/merged", List.of()));
        verify(v2UserRemoteActions).authorizeExternal(
                "comment-command", "pr-ext", null,
                SemanticAction.POST_TOP_LEVEL_COMMENT,
                ActionPayload.body("Thanks!"), HandledAction.COMMENTED.name());
        verifyNoInteractions(pullRequests);
    }

    @Test
    void externalReviewFreezesSelectionForAsynchronousFinalization()
    {
        PR open = externalPr();
        PRComment selected = draft(
                "cm1", PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 42, "Fix this.");
        PRComment excluded = draft(
                "cm2", PRComment.SCOPE_PR, null, null, "Keep local.");
        when(prService.findById("pr-ext")).thenReturn(Optional.of(open));
        when(prService.comments("pr-ext"))
                .thenReturn(List.of(selected, excluded));

        assertThat(service.publishReview(
                "review-command", "pr-ext", "APPROVE",
                List.of(), List.of("cm1"), "Looks good.")).isSameAs(open);

        verify(v2UserRemoteActions).publishExternalReview(
                "review-command", "pr-ext", null, "APPROVE", "Looks good.",
                List.of(selected), HandledAction.APPROVED);
        verify(prService, never()).markPublished(anyString(), any());
        verifyNoInteractions(pullRequests);
    }

    @Test
    void tasklessRemoteWritesWithoutStableCommandFailClosed()
    {
        assertThatThrownBy(() -> service.merge("pr-ext", "squash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");
        assertThatThrownBy(() -> service.dequeue("pr-ext"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");
        assertThatThrownBy(() -> service.deleteBranch("pr-ext"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");
        assertThatThrownBy(() -> service.postComment("pr-ext", "Thanks!"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");
        assertThatThrownBy(() -> service.publishReview("pr-ext"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Idempotency-Key is required");

        verifyNoInteractions(v2UserRemoteActions, pullRequests);
    }

    private PR externalPr()
    {
        return new PR(
                "pr-ext", null, "feature/y", "main", "Fix flaky test", "",
                PR.STATUS_REMOTE_OPEN, NOW, null, 99,
                "https://github.com/acme/widget/pull/99", null, null, null,
                PR.ORIGIN_EXTERNAL, "acme/widget", "@octocat", null, null, null);
    }

    private static PRComment draft(
            String id,
            String scope,
            String filePath,
            Integer lineNumber,
            String body)
    {
        return new PRComment(
                id, "pr-ext", PRComment.ORIGIN_LOCAL, scope, filePath,
                lineNumber, "you", body, NOW, null, null, null, null, null,
                "RIGHT", null, null);
    }
}
