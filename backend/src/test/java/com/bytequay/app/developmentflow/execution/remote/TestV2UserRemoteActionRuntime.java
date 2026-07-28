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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
