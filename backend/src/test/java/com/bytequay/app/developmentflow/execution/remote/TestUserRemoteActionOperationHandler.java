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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestUserRemoteActionOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-07-29T02:00:00Z");

    private UserRemoteActionOperationHandler.OperationStore store;
    private UserRemoteActionOperationHandler.Gateway gateway;
    private UserRemoteActionOperationHandler handler;
    private ExecutionContext context;

    @BeforeEach
    void setUp()
    {
        store = mock(UserRemoteActionOperationHandler.OperationStore.class);
        gateway = mock(UserRemoteActionOperationHandler.Gateway.class);
        handler = new UserRemoteActionOperationHandler(
                store, gateway, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = context();
    }

    @Test
    void reconciliationUsesOnlyTheProbeAndPersistsExactSuccess()
            throws Exception
    {
        Action requested = action(ActionStatus.REQUESTED, 0);
        Action claimed = action(ActionStatus.CLAIMED, 1);
        when(store.require("operation-1")).thenReturn(requested);
        when(store.claim(
                "action-1", 0, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.probe(claimed, context)).thenReturn(
                new EffectResult(true, "issue-comment:91", "exact proof"));

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        verify(gateway).probe(claimed, context);
        verify(store).finishSucceeded(
                "action-1", 1, "issue-comment:91", "exact proof", NOW);
    }

    @Test
    void cancellationAfterClaimIsDurableBeforeItReachesTheDispatcher()
            throws Exception
    {
        Action requested = action(ActionStatus.REQUESTED, 0);
        Action claimed = action(ActionStatus.CLAIMED, 1);
        when(store.require("operation-1")).thenReturn(requested);
        when(store.claim(
                "action-1", 0, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.execute(claimed, context)).thenThrow(
                new ExecutionPorts.OperationCanceledException("user canceled"));

        assertThatThrownBy(() -> handler.execute(context))
                .isInstanceOf(ExecutionPorts.OperationCanceledException.class);
        verify(store).finishCanceled("action-1", 1, "user canceled", NOW);
    }

    @Test
    void remoteIdBaselineIsDurableBeforeTheFirstEffect()
            throws Exception
    {
        Action requested = action(ActionStatus.REQUESTED, 0, null);
        Action claimed = action(ActionStatus.CLAIMED, 1, null);
        Action baselined = action(
                ActionStatus.CLAIMED, 1, List.of("issue-comment:90"));
        when(store.require("operation-1"))
                .thenReturn(requested, baselined);
        when(store.claim(
                "action-1", 0, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.captureBaseline(claimed, context)).thenReturn(
                List.of("issue-comment:90"));
        when(gateway.execute(baselined, context)).thenReturn(
                new EffectResult(true, "issue-comment:91", "exact proof"));

        handler.execute(context);

        var order = inOrder(store, gateway);
        order.verify(gateway).captureBaseline(claimed, context);
        order.verify(store).recordRecoveryBaseline(
                "action-1", 1, List.of("issue-comment:90"));
        order.verify(gateway).execute(baselined, context);
    }

    @Test
    void baselineReadFailureRetriesExecutionBecauseNoEffectWasAttempted()
            throws Exception
    {
        Action requested = action(ActionStatus.REQUESTED, 0, null);
        Action firstClaim = action(ActionStatus.CLAIMED, 1, null);
        Action failed = action(ActionStatus.FAILED, 1, null);
        Action retryClaim = action(ActionStatus.CLAIMED, 2, null);
        Action baselined = action(
                ActionStatus.CLAIMED, 2, List.of("issue-comment:90"));
        when(store.require("operation-1"))
                .thenReturn(requested, failed, failed, baselined);
        when(store.claim(
                "action-1", 0, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(firstClaim);
        when(store.claim(
                "action-1", 1, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(retryClaim);
        when(gateway.captureBaseline(firstClaim, context))
                .thenThrow(new IllegalStateException("GitHub read failed"));
        when(gateway.captureBaseline(retryClaim, context)).thenReturn(
                List.of("issue-comment:90"));
        when(gateway.execute(baselined, context)).thenReturn(
                new EffectResult(true, "issue-comment:91", "exact proof"));

        assertThatThrownBy(() -> handler.execute(context))
                .isInstanceOf(ExecutionPorts.RetryableExecutionException.class)
                .hasMessageContaining("baseline read failed");
        DispatchTicket.DispatchResult retried = handler.execute(context);

        assertThat(retried.outcome()).isEqualTo(
                DispatchTicket.Outcome.SUCCEEDED);
        verify(store).finishFailed(
                "action-1", 1, "GitHub read failed", NOW);
        verify(gateway).execute(baselined, context);
    }

    @Test
    void restartReconciliationProbesAnExpiredClaimEvenAfterCancellation()
            throws Exception
    {
        Action claimed = action(ActionStatus.CLAIMED, 1);
        when(context.isCancellationRequested()).thenReturn(true);
        when(store.require("operation-1")).thenReturn(claimed);
        when(store.claim(
                "action-1", 1, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.probe(claimed, context)).thenReturn(
                new EffectResult(true, "issue-comment:91", "exact proof"));

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        verify(gateway).probe(claimed, context);
        verify(store).finishSucceeded(
                "action-1", 1, "issue-comment:91", "exact proof", NOW);
    }

    @Test
    void commentAndCloseReconciliationResumesItsOrderedIdempotentSteps()
            throws Exception
    {
        Action claimed = composite(ActionStatus.CLAIMED, 1);
        when(store.require("operation-1")).thenReturn(claimed);
        when(store.claim(
                "action-1", 1, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.execute(claimed, context)).thenReturn(
                new EffectResult(true,
                        "comment-and-close:issue-comment:91", "both proven"));

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        verify(gateway).execute(claimed, context);
        verify(gateway, never()).probe(claimed, context);
    }

    @Test
    void restartAfterClaimBeforeBaselineSafelyExecutesAfterCapturingIt()
            throws Exception
    {
        Action expiredClaim = action(ActionStatus.CLAIMED, 1, null);
        Action baselined = action(
                ActionStatus.CLAIMED, 1, List.of("issue-comment:90"));
        when(store.require("operation-1"))
                .thenReturn(expiredClaim, baselined);
        when(store.claim(
                "action-1", 1, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(expiredClaim);
        when(gateway.captureBaseline(expiredClaim, context)).thenReturn(
                List.of("issue-comment:90"));
        when(gateway.execute(baselined, context)).thenReturn(
                new EffectResult(true, "issue-comment:91", "exact proof"));

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        verify(gateway).execute(baselined, context);
        verify(gateway, never()).probe(baselined, context);
    }

    @Test
    void pushedCiCommitWaitsForDelayedPrHeadWithoutSpendingAnotherAttempt()
            throws Exception
    {
        context = context("fingerprint-1");
        Action requested = ciTrigger(ActionStatus.REQUESTED, 0);
        Action claimed = ciTrigger(ActionStatus.CLAIMED, 1);
        when(store.require("operation-1"))
                .thenReturn(requested, claimed);
        when(store.claim(
                "action-1", 0, ClaimMode.EXECUTE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(store.claim(
                "action-1", 1, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.execute(claimed, context))
                .thenReturn(new EffectResult(
                        false, null,
                        "empty commit is pushed; waiting for the PR head probe"))
                .thenReturn(new EffectResult(
                        true, "ci-trigger-empty-commit:head-2",
                        "PR head reached the pushed empty commit"));

        assertThatThrownBy(() -> handler.execute(context))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class)
                .satisfies(failure -> assertThat(
                        ((ExecutionPorts.OperationDeferredException) failure)
                                .retryAt())
                        .isEqualTo(NOW.plusSeconds(5)));
        DispatchTicket.DispatchResult recovered = handler.reconcile(context);

        assertThat(recovered.outcome()).isEqualTo(
                DispatchTicket.Outcome.SUCCEEDED);
        verify(store).deferProbe(
                "action-1", 1, NOW.plusSeconds(5),
                "empty commit is pushed; waiting for the PR head probe");
        verify(store, never()).finishIndeterminate(
                "action-1", 1,
                "V2 user remote action outcome is not proven: empty commit is pushed; waiting for the PR head probe",
                NOW);
        verify(store).finishSucceeded(
                "action-1", 1, "ci-trigger-empty-commit:head-2",
                "PR head reached the pushed empty commit", NOW);
    }

    @Test
    void exhaustedAmbiguityReturnsATerminalFailureForDelivery()
            throws Exception
    {
        Action indeterminate = action(ActionStatus.INDETERMINATE, 2);
        Action claimed = action(ActionStatus.CLAIMED, 3);
        Action abandoned = action(ActionStatus.ABANDONED, 3);
        when(store.require("operation-1"))
                .thenReturn(indeterminate, abandoned);
        when(store.claim(
                "action-1", 2, ClaimMode.PROBE, "execution-1", NOW,
                NOW.plusSeconds(30))).thenReturn(claimed);
        when(gateway.probe(claimed, context)).thenReturn(
                new EffectResult(false, null, "not observable"));

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("not observable");
        verify(store).finishIndeterminate(
                "action-1", 3,
                "V2 user remote action outcome is not proven: not observable",
                NOW);
    }

    @Test
    void restartReturnsTheAlreadyDurableTerminalFailureWithoutReclaiming()
            throws Exception
    {
        Action abandoned = action(ActionStatus.ABANDONED, 3);
        when(store.require("operation-1")).thenReturn(abandoned);

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("stale or exhausted");
        verify(store).require("operation-1");
    }

    private static ExecutionContext context()
    {
        return context(null);
    }

    private static ExecutionContext context(String expectedCodeFingerprint)
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
                UserRemoteActionOperationHandler.OPERATION_KIND);
        when(envelope.owner()).thenReturn(new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, "task-1",
                UserRemoteActionOperationHandler.CALLBACK_ROUTE));
        when(envelope.fence()).thenReturn(fence(expectedCodeFingerprint));
        when(lease.expiresAt()).thenReturn(NOW.plusSeconds(30));
        return context;
    }

    private static DispatchTicket.OperationFence fence()
    {
        return fence(null);
    }

    private static DispatchTicket.OperationFence fence(
            String expectedCodeFingerprint)
    {
        return new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                expectedCodeFingerprint, "head-1", "base-1");
    }

    private static Action action(ActionStatus status, int attemptCount)
    {
        return action(status, attemptCount, List.of());
    }

    private static Action action(
            ActionStatus status, int attemptCount, List<String> baseline)
    {
        String payloadJson = """
                {"version":1,"body":"hello","reviewAction":null,
                "branchName":null,"drafts":[]}
                """.replace("\n", "");
        return new Action(
                "action-1", "operation-1",
                ActionKind.POST_TOP_LEVEL_COMMENT, status,
                1, attemptCount, 3, "task-1", "command-1", 1, "stage-1", 1,
                "binding-1", "pr-1", "acme/widget", "acme/widget", 17,
                "feature", "head-1", "base-1", payloadJson,
                digest(payloadJson),
                new ActionPayload(1, "hello", null, null, List.of()),
                "COMMENTED", NOW.minusSeconds(1),
                baseline,
                status == ActionStatus.SUCCEEDED ? "effect-1" : null,
                status == ActionStatus.SUCCEEDED ? "proof" : null);
    }

    private static Action composite(ActionStatus status, int attemptCount)
    {
        String payloadJson = """
                {"version":2,"body":"closing","reviewAction":null,
                "branchName":null,"drafts":[],"targetId":null,
                "value":null,"selected":null,"filePath":null,
                "lineNumber":null,"side":null,"startLine":null,
                "startSide":null}
                """.replace("\n", "");
        return new Action(
                "action-1", "operation-1", ActionKind.DEQUEUE,
                SemanticAction.COMMENT_AND_CLOSE, status,
                1, attemptCount, 3, "task-1", "command-1", 1, "stage-1",
                1, "binding-1", "pr-1", "acme/widget", "acme/widget", 17,
                "feature", "head-1", "base-1", payloadJson,
                digest(payloadJson), ActionPayload.body("closing"), null,
                NOW.minusSeconds(1), List.of("issue-comment:90"), null, null);
    }

    private static Action ciTrigger(ActionStatus status, int attemptCount)
    {
        ActionPayload payload = ActionPayload.empty();
        String payloadJson = """
                {"version":2,"body":null,"reviewAction":null,
                "branchName":null,"drafts":[],"targetId":null,
                "value":null,"selected":null,"filePath":null,
                "lineNumber":null,"side":null,"startLine":null,
                "startSide":null}
                """.replace("\n", "");
        return new Action(
                "action-1", "operation-1", ActionKind.DEQUEUE,
                SemanticAction.TRIGGER_CI_EMPTY_COMMIT, status,
                1, attemptCount, 3, "task-1", "command-1", 1,
                "stage-1", 1, "binding-1", "pr-1", "acme/widget",
                "acme/widget", 17, "feature", "/tmp/worktree",
                "fingerprint-1", "head-1", "base-1", payloadJson,
                digest(payloadJson), payload, null, NOW.minusSeconds(1),
                List.of("local-head:head-1", "remote-head:head-1",
                        "pr-head:head-1"),
                status == ActionStatus.SUCCEEDED
                        ? "ci-trigger-empty-commit:head-2" : null,
                status == ActionStatus.SUCCEEDED
                        ? "PR head reached the pushed empty commit" : null);
    }
}
