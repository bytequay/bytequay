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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler.CommentAction;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewBuildCommentOperationHandler
{
    private static final Instant NOW =
            Instant.parse("2026-07-29T02:00:00Z");

    @Test
    void delayedGitHubVisibilityKeepsOneMutationAndOneSemanticAttempt()
            throws Exception
    {
        ReviewBuildCommentOperationHandler.OperationStore store = mock(
                ReviewBuildCommentOperationHandler.OperationStore.class);
        ReviewBuildCommentOperationHandler.Gateway gateway = mock(
                ReviewBuildCommentOperationHandler.Gateway.class);
        ReviewBuildCommentOperationHandler handler =
                new ReviewBuildCommentOperationHandler(
                        store, gateway, new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        ExecutionContext context = context();
        CommentAction requested = action(ActionStatus.REQUESTED, 0);
        CommentAction claimed = action(ActionStatus.CLAIMED, 1);
        when(store.require("operation-1"))
                .thenReturn(requested, claimed, claimed);
        when(store.claim(
                "action-1", 0, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(store.claim(
                "action-1", 1, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.execute(claimed, context)).thenReturn(new EffectResult(
                false, null, "review not visible yet"));
        when(gateway.probe(claimed, context))
                .thenReturn(new EffectResult(
                        false, null, "inline comments not visible yet"))
                .thenReturn(new EffectResult(
                        true, "review:91", "exact review is visible"));
        when(store.deferProbe(
                "action-1", 1, NOW, NOW.plusSeconds(5),
                "review not visible yet")).thenReturn(true);
        when(store.deferProbe(
                "action-1", 1, NOW, NOW.plusSeconds(5),
                "inline comments not visible yet")).thenReturn(true);

        assertThatThrownBy(() -> handler.execute(context))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class);
        assertThatThrownBy(() -> handler.reconcile(context))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class);
        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(
                DispatchTicket.Outcome.SUCCEEDED);
        verify(gateway).execute(claimed, context);
        verify(gateway, times(2)).probe(claimed, context);
        verify(store).deferProbe(
                "action-1", 1, NOW, NOW.plusSeconds(5),
                "review not visible yet");
        verify(store).deferProbe(
                "action-1", 1, NOW, NOW.plusSeconds(5),
                "inline comments not visible yet");
        verify(store, never()).finishIndeterminate(
                eq("action-1"), eq(1), anyString(), eq(NOW));
        verify(store).finishSucceeded(
                "action-1", 1, "review:91", "exact review is visible", NOW);
    }

    @Test
    void exhaustedObservationBudgetReturnsNeedsAttentionWithoutAnotherMutation()
            throws Exception
    {
        ReviewBuildCommentOperationHandler.OperationStore store = mock(
                ReviewBuildCommentOperationHandler.OperationStore.class);
        ReviewBuildCommentOperationHandler.Gateway gateway = mock(
                ReviewBuildCommentOperationHandler.Gateway.class);
        ReviewBuildCommentOperationHandler handler =
                new ReviewBuildCommentOperationHandler(
                        store, gateway, new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        ExecutionContext context = context();
        CommentAction requested = action(ActionStatus.REQUESTED, 0);
        CommentAction claimed = action(ActionStatus.CLAIMED, 1);
        when(store.require("operation-1")).thenReturn(requested);
        when(store.claim(
                "action-1", 0, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.execute(claimed, context)).thenReturn(new EffectResult(
                false, null, "review still not visible"));
        when(store.deferProbe(
                "action-1", 1, NOW, NOW.plusSeconds(5),
                "review still not visible")).thenReturn(false);

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("needs attention", "exhausted");
        verify(gateway).execute(claimed, context);
        verify(gateway, never()).probe(claimed, context);
        verify(store, never()).finishIndeterminate(
                eq("action-1"), eq(1), anyString(), eq(NOW));
    }

    private static ExecutionContext context()
    {
        ExecutionContext context = mock(ExecutionContext.class);
        DispatchTicket.DispatchEnvelope envelope = mock(
                DispatchTicket.DispatchEnvelope.class);
        CapacityManager.CapacityLease lease = mock(
                CapacityManager.CapacityLease.class);
        when(context.envelope()).thenReturn(envelope);
        when(context.capacityLease()).thenReturn(lease);
        when(context.executionId()).thenReturn("execution-1");
        when(envelope.operationKind()).thenReturn(
                ReviewBuildCommentOperationHandler.OPERATION_KIND);
        when(envelope.owner()).thenReturn(new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                ReviewBuildCommentOperationHandler.CALLBACK_ROUTE));
        when(envelope.fence()).thenReturn(new DispatchTicket.OperationFence(
                null, null, null, "operation-1", 1,
                null, "head-1", null));
        when(lease.expiresAt()).thenReturn(NOW.plusSeconds(30));
        return context;
    }

    private static CommentAction action(
            ActionStatus status, int attemptCount)
    {
        String payloadJson = """
                {"version":1,"body":"Summary","reviewAction":"COMMENT",
                "branchName":null,"drafts":[]}
                """.replace("\n", "");
        return new CommentAction(
                "action-1", "operation-1", status, 1, attemptCount, 3,
                "trunk-1", "pass-1", "command-1", "workspace",
                "acme/widget", "fork/widget", 17, "feature", "head-1",
                payloadJson, digest(payloadJson),
                new ActionPayload(1, "Summary", "COMMENT", null, List.of()),
                NOW.minusSeconds(1), List.of("review:80"), null, null);
    }
}
