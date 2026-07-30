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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore.UnwatchedRepositoryException;
import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2UserRemoteActionRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-29T01:00:00Z");

    private SqliteUserRemoteActionStore store;
    private PRService prs;
    private InvestigationReviewService investigationReviews;
    private V2UserRemoteActionRuntime runtime;

    @BeforeEach
    void setUp()
    {
        store = mock(SqliteUserRemoteActionStore.class);
        prs = mock(PRService.class);
        investigationReviews = mock(InvestigationReviewService.class);
        runtime = new V2UserRemoteActionRuntime(
                store, prs, new ObjectMapper(), investigationReviews,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void anUnwatchedRepositoryKeepsItsOwnReasonWhileStaleSubjectsStayGeneric()
    {
        SqliteExternalPrActionStore externalActions =
                mock(SqliteExternalPrActionStore.class);
        V2UserRemoteActionRuntime external = new V2UserRemoteActionRuntime(
                store, externalActions, null, null, prs, new ObjectMapper(),
                investigationReviews);
        // doThrow, not when(...): re-stubbing a throwing method through
        // when() would call the mock and raise the previous stub.
        doThrow(new UnwatchedRepositoryException("acme/widget"))
                .when(externalActions).authorize(any(), any());

        assertThatThrownBy(() -> external.publishExternalReview(
                "command-1", "pr-1", null, "APPROVE", "", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ByteQuay must watch acme/widget"
                        + " before publishing to its pull requests")
                .hasMessageNotContaining("no longer matches");

        doThrow(new IllegalStateException("cached head moved"))
                .when(externalActions).authorize(any(), any());

        assertThatThrownBy(() -> external.publishExternalReview(
                "command-2", "pr-1", null, "APPROVE", "", List.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "no longer matches the exact cached PR/head");
    }

    @Test
    void restartFinalizationMarksOnlyDraftsThatStillMatchTheFrozenReview()
    {
        FrozenDraft exact = draft("draft-exact", "exact body");
        FrozenDraft changed = draft("draft-changed", "old body");
        Action action = action(
                ActionKind.SUBMIT_REVIEW, ActionStatus.SUCCEEDED,
                new ActionPayload(
                        1, "summary", "COMMENT", null,
                        List.of(exact, changed)));
        when(store.findCommittedUnfinalized(25)).thenReturn(List.of(action));
        when(prs.comments("pr-1")).thenReturn(List.of(
                comment("draft-exact", "exact body"),
                comment("draft-changed", "edited after authorization")));

        runtime.recoverCommittedDeliveries(25);

        verify(prs).markPublished("draft-exact", NOW);
        verify(prs, never()).markPublished(eq("draft-changed"), any());
        verify(investigationReviews, never()).recordPublished(
                any(), any(), any(), any());
        verify(store).markFinalized("action-1", ActionStatus.SUCCEEDED, NOW);
    }

    @Test
    void finalizationTreatsALegacyNullFileSideAsTheFrozenRightSide()
    {
        FrozenDraft frozen = draft("legacy-draft", "exact body");
        Action action = action(
                ActionKind.SUBMIT_REVIEW, ActionStatus.SUCCEEDED,
                new ActionPayload(
                        1, "summary", "COMMENT", null, List.of(frozen)));
        when(store.findCommittedUnfinalized(25)).thenReturn(List.of(action));
        when(prs.comments("pr-1")).thenReturn(List.of(
                comment("legacy-draft", "exact body", null)));

        runtime.recoverCommittedDeliveries(25);

        verify(prs).markPublished("legacy-draft", NOW);
        verify(investigationReviews).recordPublished(
                "pr-1", "COMMENT", List.of(), List.of("legacy-draft"));
        verify(store).markFinalized("action-1", ActionStatus.SUCCEEDED, NOW);
    }

    @Test
    void genericCancellationTerminalizesAndFinalizesWithoutAPrMutation()
    {
        Action requested = action(
                ActionKind.POST_TOP_LEVEL_COMMENT, ActionStatus.REQUESTED,
                new ActionPayload(1, "hello", null, null, List.of()));
        Action canceled = action(
                ActionKind.POST_TOP_LEVEL_COMMENT, ActionStatus.CANCELED,
                requested.payload());
        when(store.require("operation-1"))
                .thenReturn(requested, canceled);
        when(store.terminalizeDeliveryFailure(
                eq("operation-1"), eq(ActionStatus.CANCELED), any(), eq(NOW)))
                .thenReturn(canceled);
        DispatchTicket.OperationFence fence = fence();
        DispatchTicket.DispatchResult result = DispatchTicket.DispatchResult.canceled(
                fence);

        DispatchTicket.DeliveryReceipt receipt = runtime.deliver(
                owner(), fence, result);
        runtime.afterDeliveryCommitted(owner(), fence, result, receipt);

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        verify(store).markFinalized("action-1", ActionStatus.CANCELED, NOW);
        verify(prs, never()).markPublished(any(), any());
    }

    @Test
    void provenApprovalWithoutDraftsRecordsPublicationOnlyDuringFinalization()
    {
        Action action = action(
                ActionKind.SUBMIT_REVIEW, ActionStatus.SUCCEEDED,
                new ActionPayload(1, "", "APPROVE", null, List.of()));
        when(store.findCommittedUnfinalized(25)).thenReturn(List.of(action));
        when(prs.comments("pr-1")).thenReturn(List.of());

        runtime.recoverCommittedDeliveries(25);

        verify(investigationReviews).recordPublished(
                "pr-1", "APPROVE", List.of(), List.of());
        verify(store).markFinalized("action-1", ActionStatus.SUCCEEDED, NOW);
    }

    @Test
    void ciTriggerDeliveryAcceptsItsExactFrozenWorktreeFingerprint()
            throws Exception
    {
        Action action = ciTriggerAction();
        when(store.require("operation-1")).thenReturn(action);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                "fingerprint-1", "head-1", "base-1");
        UserRemoteActionOperationHandler.EffectResult payload =
                new UserRemoteActionOperationHandler.EffectResult(
                        true, "ci-trigger-empty-commit:head-2",
                        "exact empty-commit proof");
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                new ObjectMapper().writeValueAsString(payload), "{}", null);

        DispatchTicket.DeliveryReceipt receipt = runtime.deliver(
                owner(), fence, result);

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
    }

    @Test
    void everyVisibleParityControlAuthorizesOneNamedDurableSemanticAction()
    {
        runtime.rerunFailedChecks("c-1", "task-1", "pr-1");
        runtime.triggerCiViaEmptyCommit("c-21", "task-1", "pr-1");
        runtime.setDraft("c-2", "task-1", "pr-1", true);
        runtime.updateTitle("c-3", "task-1", "pr-1", " New title ");
        runtime.updateBody("c-4", "task-1", "pr-1", "New body");
        runtime.closePullRequest("c-5", "task-1", "pr-1");
        runtime.commentAndClose("c-6", "task-1", "pr-1", "Closing");
        runtime.replyToReviewThread("c-7", "task-1", "pr-1", 7, "Reply");
        runtime.editIssueComment("c-8", "task-1", "pr-1", 8, "Edit");
        runtime.editReviewComment("c-9", "task-1", "pr-1", 9, "Edit");
        runtime.deleteIssueComment("c-10", "task-1", "pr-1", 10);
        runtime.deleteReviewComment("c-11", "task-1", "pr-1", 11);
        runtime.addReviewer("c-12", "task-1", "pr-1", "alice");
        runtime.removeReviewer("c-13", "task-1", "pr-1", "bob");
        runtime.setAssignee("c-14", "task-1", "pr-1", "alice", true);
        runtime.setLabel("c-15", "task-1", "pr-1", "bug", false);
        runtime.createInlineComment(
                "c-16", "task-1", "pr-1", "Inline", "src/A.java", 12,
                "RIGHT", null, null);
        runtime.addPullRequestReaction(
                "c-17", "task-1", "pr-1", "heart");
        runtime.addReviewCommentReaction(
                "c-18", "task-1", "pr-1", 18, "rocket");
        runtime.addIssueCommentReaction(
                "c-19", "task-1", "pr-1", 19, "+1");
        runtime.setThreadResolved(
                "c-20", "task-1", "pr-1", 20, true);

        ArgumentCaptor<AuthorizationRequest> requests =
                ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(store, times(21)).authorize(requests.capture(), eq(NOW));
        assertThat(requests.getAllValues())
                .extracting(AuthorizationRequest::semanticAction)
                .containsExactly(
                        SemanticAction.RERUN_FAILED_CHECKS,
                        SemanticAction.TRIGGER_CI_EMPTY_COMMIT,
                        SemanticAction.SET_DRAFT_STATE,
                        SemanticAction.UPDATE_TITLE,
                        SemanticAction.UPDATE_BODY,
                        SemanticAction.CLOSE_PULL_REQUEST,
                        SemanticAction.COMMENT_AND_CLOSE,
                        SemanticAction.REPLY_REVIEW_THREAD,
                        SemanticAction.EDIT_ISSUE_COMMENT,
                        SemanticAction.EDIT_REVIEW_COMMENT,
                        SemanticAction.DELETE_ISSUE_COMMENT,
                        SemanticAction.DELETE_REVIEW_COMMENT,
                        SemanticAction.ADD_REVIEWER,
                        SemanticAction.REMOVE_REVIEWER,
                        SemanticAction.SET_ASSIGNEE,
                        SemanticAction.SET_LABEL,
                        SemanticAction.CREATE_INLINE_COMMENT,
                        SemanticAction.REACT_PULL_REQUEST,
                        SemanticAction.REACT_REVIEW_COMMENT,
                        SemanticAction.REACT_ISSUE_COMMENT,
                        SemanticAction.SET_THREAD_RESOLUTION);
        assertThat(requests.getAllValues().get(3).payload().value())
                .isEqualTo("New title");
        assertThat(requests.getAllValues().get(6).payload().body())
                .isEqualTo("Closing");
    }

    private static Action action(
            ActionKind kind, ActionStatus status, ActionPayload payload)
    {
        return new Action(
                "action-1", "operation-1", kind, status,
                1, status == ActionStatus.REQUESTED ? 0 : 1, 3,
                "task-1", "command-1", 1, "stage-1", 1, "binding-1", "pr-1",
                "acme/widget", "acme/widget", 17, "feature", "head-1",
                "base-1", "{}", "digest", payload, null, NOW.minusSeconds(1),
                List.of(),
                status == ActionStatus.SUCCEEDED ? "review:71" : null,
                status == ActionStatus.SUCCEEDED ? "exact review proof" : null);
    }

    private static Action ciTriggerAction()
    {
        return new Action(
                "action-1", "operation-1", ActionKind.DEQUEUE,
                SemanticAction.TRIGGER_CI_EMPTY_COMMIT,
                ActionStatus.SUCCEEDED, 1, 1, 3,
                "task-1", "command-1", 1, "stage-1", 1,
                "binding-1", "pr-1", "acme/widget", "acme/widget", 17,
                "feature", "/tmp/worktree", "fingerprint-1",
                "head-1", "base-1", "{}", "digest",
                ActionPayload.empty(), null, NOW.minusSeconds(1), List.of(),
                "ci-trigger-empty-commit:head-2",
                "exact empty-commit proof");
    }

    private static FrozenDraft draft(String id, String body)
    {
        return new FrozenDraft(
                id, "file-line", "src/A.java", 12, "RIGHT",
                null, null, body, null);
    }

    private static PRComment comment(String id, String body)
    {
        return comment(id, body, "RIGHT");
    }

    private static PRComment comment(String id, String body, String side)
    {
        return new PRComment(
                id, "pr-1", PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE, "src/A.java", 12, "user", body,
                NOW.minusSeconds(10), null, null, null, null, null,
                side, null, null, null, null);
    }

    private static DispatchTicket.OperationFence fence()
    {
        return new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                null, "head-1", "base-1");
    }

    private static DispatchTicket.OwnerReference owner()
    {
        return new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, "task-1",
                UserRemoteActionOperationHandler.CALLBACK_ROUTE);
    }
}
