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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Evidence;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Kind;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.OperationContext;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Proof;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Result;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.AcceptedBaseMove;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Admission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.AuthorityKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.BaseSyncTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Delivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryAcceptance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryState;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Episode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ManualBlocker;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.OpenRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ResultDisposition;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.StartReceiptEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.TurnContext;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager.Policy;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalPublishBaseSyncRuntimeCoordinator
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private TaskCommandExecutor commands;
    private V2BranchSyncPolicyManager policies;
    private SqliteLocalPublishBaseSyncStore store;
    private LocalDevelopmentRuntimeCoordinator localRuntime;
    private LocalDevelopmentStageManager local;
    private LocalPublishBaseSyncRuntimeCoordinator coordinator;

    @BeforeEach
    void setUp()
    {
        commands = new TaskCommandExecutor(new NoopTransactions());
        policies = mock(V2BranchSyncPolicyManager.class);
        store = mock(SqliteLocalPublishBaseSyncStore.class);
        localRuntime = mock(LocalDevelopmentRuntimeCoordinator.class);
        local = mock(LocalDevelopmentStageManager.class);
        coordinator = new LocalPublishBaseSyncRuntimeCoordinator(
                commands, policies, store, localRuntime, local, JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void frozenAutoApproveArmsBoundedPolicyAndOpensEpisodeEvenWhenDisabled()
    {
        Policy policy = policy(false);
        when(policies.armOnFirstPushInCommand("task-1")).thenReturn(policy);
        when(store.open(any())).thenReturn(admission());
        AcceptedBaseMove moved = baseMove(true);

        commands.executeVoid("task-1", () -> coordinator.afterAccepted(moved));

        ArgumentCaptor<OpenRequest> request = ArgumentCaptor.forClass(OpenRequest.class);
        verify(store).open(request.capture());
        assertThat(request.getValue().sourcePublishOperationId())
                .isEqualTo("publish-1");
        assertThat(request.getValue().targetBaseSha()).isEqualTo("target-base");
        assertThat(request.getValue().branchSyncPolicyRevisionId())
                .isEqualTo(policy.id());
        assertThat(request.getValue().authorityKind())
                .isEqualTo(AuthorityKind.STANDING_TASK_POLICY);
        assertThat(request.getValue().standingPolicyRevisionId())
                .isEqualTo("task-policy-1");
        assertThat(request.getValue().blockerId()).isNull();
        verify(store, never()).openManualBlocker(anyString(), anyString(), any());
    }

    @Test
    void missingStandingApprovalOpensOnlyTheExactManualBlocker()
    {
        AcceptedBaseMove moved = baseMove(false);
        when(store.openManualBlocker("publish-1", "target-base", NOW))
                .thenReturn(blocker("OPEN"));

        commands.executeVoid("task-1", () -> coordinator.afterAccepted(moved));

        verify(store).openManualBlocker("publish-1", "target-base", NOW);
        verify(policies, never()).armOnFirstPushInCommand(anyString());
        verify(store, never()).open(any());
    }

    @Test
    void explicitApprovalUsesOnlyTheFrozenBlockerSubject()
    {
        when(store.findManualBlocker("blocker-1"))
                .thenReturn(Optional.of(blocker("OPEN")));
        when(policies.armOnFirstPushInCommand("task-1"))
                .thenReturn(policy(true));
        when(store.open(any())).thenReturn(admission());

        coordinator.approveManual("task-1", "blocker-1", "user");

        ArgumentCaptor<OpenRequest> request = ArgumentCaptor.forClass(OpenRequest.class);
        verify(store).open(request.capture());
        assertThat(request.getValue().authorityKind()).isEqualTo(AuthorityKind.MANUAL);
        assertThat(request.getValue().sourcePublishOperationId())
                .isEqualTo("publish-1");
        assertThat(request.getValue().targetBaseSha()).isEqualTo("target-base");
        assertThat(request.getValue().blockerId()).isEqualTo("blocker-1");
        assertThat(request.getValue().actor()).isEqualTo("user");
        assertThat(request.getValue().standingPolicyRevisionId()).isNull();
    }

    @Test
    void exhaustedExtensionAllowsOnlyItsDeterministicResolvedReplay()
    {
        ManualBlocker blocker = exhaustedBlocker("RESOLVED");
        when(store.findManualBlocker("exhausted-blocker-1"))
                .thenReturn(Optional.of(blocker));
        when(store.approveFailureBlocker(
                blocker, "extension-command-1", "user", NOW))
                .thenReturn(admission());

        Admission replay = coordinator.extendExhausted(
                "task-1", "episode-1", "exhausted-blocker-1",
                "extension-command-1", "user");

        assertThat(replay).isEqualTo(admission());
        verify(store).approveFailureBlocker(
                blocker, "extension-command-1", "user", NOW);
    }

    @Test
    void acceptedFetchStrictlySchedulesTheMechanicalRebase()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, true);
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        prepareDelivery(delivery, raw, ACCEPTED);

        DispatchTicket.DeliveryReceipt accepted = coordinator.deliver(
                owner(delivery.kind()), fence(), raw);

        assertThat(accepted.acceptance()).isEqualTo(ACCEPTED);
        verify(store).requestRebase("episode-1", NOW);
        verify(localRuntime, never()).createPublishBaseSyncTurnInCommand(
                any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Disposition.class, names = {"REBASED", "CONFLICT"})
    void exactRebaseOutcomeStartsOneStageOwnedBaseSyncTurn(Disposition disposition)
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.MECHANICAL_REBASE, true);
        Result rawResult = rebaseResult(disposition);
        DispatchTicket.DispatchResult raw = raw(rawResult);
        prepareDelivery(delivery, raw, ACCEPTED);
        TurnContext context = context(rawResult);
        BaseSyncTurn turn = turn(rawResult);
        StageManager.State state = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 8, StageCheckpoint.IMPLEMENTING, null, turnFence(turn));
        when(store.requireTurnContext("episode-1")).thenReturn(context);
        when(localRuntime.createPublishBaseSyncTurnInCommand(
                any(), any(), any())).thenReturn(turn);
        when(local.startPublishBaseSyncInCommand(any(), any(), anyString()))
                .thenReturn(CommandResult.applied(state));
        when(store.completeHandoff("episode-1", NOW))
                .thenReturn(startReceipt(turn, state));

        DispatchTicket.DeliveryReceipt accepted = coordinator.deliver(
                owner(delivery.kind()), fence(), raw);

        assertThat(accepted.acceptance()).isEqualTo(ACCEPTED);
        verify(store).insertBaseSyncTurn(turn);
        ArgumentCaptor<Result> durable = ArgumentCaptor.forClass(Result.class);
        verify(localRuntime).createPublishBaseSyncTurnInCommand(
                any(), durable.capture(), any());
        assertThat(durable.getValue().disposition()).isEqualTo(disposition);
        verify(local).startPublishBaseSyncInCommand(
                any(), any(), eq("episode-1"));
        verify(store).completeHandoff("episode-1", NOW);
    }

    @Test
    void staleSubjectIsDurablySupersededWithoutStartingMoreWork()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, false);
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        prepareDelivery(delivery, raw, SUPERSEDED);

        DispatchTicket.DeliveryReceipt superseded = coordinator.deliver(
                owner(delivery.kind()), fence(), raw);

        assertThat(superseded.acceptance()).isEqualTo(SUPERSEDED);
        verify(store, never()).requestRebase(anyString(), any());
        verify(localRuntime, never()).createPublishBaseSyncTurnInCommand(
                any(), any(), any());
    }

    @Test
    void pausingTaskParksExactFetchProofWithoutAdvancingTheEpisode()
            throws Exception
    {
        Delivery delivery = deliveryWithState(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE,
                DeliveryState.PAUSING, "PAUSING");
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        when(store.requireTaskId("operation-1")).thenReturn("task-1");
        when(store.findReceipt("operation-1")).thenReturn(Optional.empty());
        when(store.requireDelivery("operation-1")).thenReturn(delivery);
        when(store.finishClassified(
                any(), any(), anyString(),
                eq(DeliveryAcceptance.PARKED), any(), any()))
                .thenReturn(new DeliveryReceipt(
                        "operation-row-1", "operation-1", SUCCEEDED,
                        "a".repeat(64), SUPERSEDED,
                        DeliveryAcceptance.PARKED, NOW));

        DispatchTicket.DeliveryReceipt parked = coordinator.deliver(
                owner(delivery.kind()), fence(), raw);

        assertThat(parked.acceptance()).isEqualTo(SUPERSEDED);
        verify(store).finishClassified(
                any(), eq(SUCCEEDED), anyString(),
                eq(DeliveryAcceptance.PARKED), any(), eq(NOW));
        verify(store, never()).requestRebase(anyString(), any());
        verify(localRuntime, never()).createPublishBaseSyncTurnInCommand(
                any(), any(), any());
    }

    @Test
    void determinateFailureSettlesItsExactRetryPolicyAfterAcceptance()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, true);
        Result failed = new Result(
                1, "operation-1", Kind.FETCH_COMPARE, Disposition.FAILED,
                null, null, null, "target-base", null, "fetch failed");
        String payload = JSON.writeValueAsString(failed);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence(), FAILED, payload, payload, "fetch failed");
        prepareDelivery(delivery, raw, ACCEPTED);

        DispatchTicket.DeliveryReceipt accepted = coordinator.deliver(
                owner(delivery.kind()), fence(), raw);

        assertThat(accepted.acceptance()).isEqualTo(ACCEPTED);
        verify(store).settleFailure("episode-1", NOW);
        verify(store, never()).requestRebase(anyString(), any());
    }

    @Test
    void exactDuplicateReplaysWithoutRepeatingAnyDomainWrite()
            throws Exception
    {
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        String rawDigest = PlanRuntimeCoordinator.digest(
                JSON.writeValueAsString(raw));
        when(store.requireTaskId("operation-1")).thenReturn("task-1");
        when(store.findReceipt("operation-1")).thenReturn(Optional.of(
                new DeliveryReceipt(
                        "operation-row-1", "operation-1", SUCCEEDED,
                        rawDigest, ACCEPTED, NOW)));
        when(store.requireByOperationId("operation-1"))
                .thenReturn(operationContext());

        DispatchTicket.DeliveryReceipt duplicate = coordinator.deliver(
                owner(SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE),
                fence(), raw);

        assertThat(duplicate.acceptance()).isEqualTo(ACCEPTED);
        verify(store).requireByOperationId("operation-1");
        verify(store, never()).requireDelivery(anyString());
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void duplicateRejectsAnotherStageOwnerBeforeReplay()
            throws Exception
    {
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        prepareDuplicate(raw);
        DispatchTicket.OwnerReference anotherOwner =
                new DispatchTicket.OwnerReference(
                        STAGE, "another-stage",
                        LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK);

        assertThatThrownBy(() -> coordinator.deliver(
                anotherOwner, fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another subject");

        verify(store, never()).requireDelivery(anyString());
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void duplicateRejectsAnotherValidCallbackBeforeReplay()
            throws Exception
    {
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        prepareDuplicate(raw);
        DispatchTicket.OwnerReference anotherCallback =
                new DispatchTicket.OwnerReference(
                        STAGE, "stage-1",
                        LocalPublishBaseSyncOperationHandler.REBASE_CALLBACK);

        assertThatThrownBy(() -> coordinator.deliver(
                anotherCallback, fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another subject");

        verify(store, never()).requireDelivery(anyString());
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void duplicateRejectsAnotherFenceBeforeReplay()
            throws Exception
    {
        DispatchTicket.OperationFence anotherFence =
                new DispatchTicket.OperationFence(
                        2L, "stage-1", 1L, "operation-1", 1,
                        "fingerprint", "head", "base");
        String payload = JSON.writeValueAsString(fetchResult());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                anotherFence, SUCCEEDED, payload, payload, null);
        prepareDuplicate(raw);

        assertThatThrownBy(() -> coordinator.deliver(
                owner(SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE),
                anotherFence, raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another subject");

        verify(store, never()).requireDelivery(anyString());
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void payloadForAnotherOperationIsRejectedBeforePersistence()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, true);
        Result wrong = new Result(
                1, "another-operation", Kind.FETCH_COMPARE,
                Disposition.FETCHED, "fingerprint", "head", "base",
                "target-base", fetchEvidence(), null);
        DispatchTicket.DispatchResult raw = raw(wrong);
        when(store.requireTaskId("operation-1")).thenReturn("task-1");
        when(store.findReceipt("operation-1")).thenReturn(Optional.empty());
        when(store.requireDelivery("operation-1")).thenReturn(delivery);

        assertThatThrownBy(() -> coordinator.deliver(
                owner(delivery.kind()), fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another subject");
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void structurallyInvalidOwnerCallbackAndFenceAreRejectedWithoutReceipt()
            throws Exception
    {
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        DispatchTicket.OwnerReference wrongOwner =
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TASK, "stage-1",
                        LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK);
        DispatchTicket.OwnerReference wrongCallback =
                new DispatchTicket.OwnerReference(
                        STAGE, "stage-1", "ANOTHER_CALLBACK");
        DispatchTicket.OperationFence wrongFence =
                new DispatchTicket.OperationFence(
                        1L, "stage-1", 1L, "another-operation", 1,
                        "fingerprint", "head", "base");

        assertThatThrownBy(() -> coordinator.deliver(wrongOwner, fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> coordinator.deliver(wrongCallback, fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> coordinator.deliver(
                owner(SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE),
                wrongFence, raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
        verify(store, never()).requireTaskId(anyString());
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void knownOperationWithAnotherOwnerIsRejectedRatherThanSuperseded()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, true);
        DispatchTicket.DispatchResult raw = raw(fetchResult());
        when(store.requireTaskId("operation-1")).thenReturn("task-1");
        when(store.findReceipt("operation-1")).thenReturn(Optional.empty());
        when(store.requireDelivery("operation-1")).thenReturn(delivery);
        DispatchTicket.OwnerReference anotherOwner =
                new DispatchTicket.OwnerReference(
                        STAGE, "another-stage",
                        LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK);

        assertThatThrownBy(() -> coordinator.deliver(
                anotherOwner, fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another subject");
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void typedResultRejectsTrailingJsonBeforePersistence()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, true);
        String payload = JSON.writeValueAsString(fetchResult()) + " {}";
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence(), SUCCEEDED, payload, payload, null);
        prepareDelivery(delivery, raw, ACCEPTED);

        assertThatThrownBy(() -> coordinator.deliver(
                owner(delivery.kind()), fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strict typed JSON");
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void typedResultRejectsDuplicateJsonKeysBeforePersistence()
            throws Exception
    {
        Delivery delivery = delivery(
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE, true);
        String encoded = JSON.writeValueAsString(fetchResult());
        String payload = "{\"version\":1," + encoded.substring(1);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence(), SUCCEEDED, payload, payload, null);
        prepareDelivery(delivery, raw, ACCEPTED);

        assertThatThrownBy(() -> coordinator.deliver(
                owner(delivery.kind()), fence(), raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strict typed JSON");
        verify(store, never()).finish(any(), any(), anyString(), any(), any(), any());
    }

    private void prepareDelivery(
            Delivery delivery,
            DispatchTicket.DispatchResult raw,
            DispatchTicket.Acceptance acceptance)
    {
        when(store.requireTaskId("operation-1")).thenReturn("task-1");
        when(store.findReceipt("operation-1")).thenReturn(Optional.empty());
        when(store.requireDelivery("operation-1")).thenReturn(delivery);
        when(store.finish(any(), any(), anyString(), any(), any(), any()))
                .thenReturn(new DeliveryReceipt(
                        "operation-row-1", "operation-1", raw.outcome(),
                        "a".repeat(64), acceptance, NOW));
    }

    private void prepareDuplicate(DispatchTicket.DispatchResult raw)
            throws Exception
    {
        String rawDigest = PlanRuntimeCoordinator.digest(
                JSON.writeValueAsString(raw));
        when(store.requireTaskId("operation-1")).thenReturn("task-1");
        when(store.findReceipt("operation-1")).thenReturn(Optional.of(
                new DeliveryReceipt(
                        "operation-row-1", "operation-1", raw.outcome(),
                        rawDigest, ACCEPTED, NOW)));
        when(store.requireByOperationId("operation-1"))
                .thenReturn(operationContext());
    }

    private static AcceptedBaseMove baseMove(boolean autoApprove)
    {
        return new AcceptedBaseMove(
                "publish-1", "publish-operation", "authorization-1",
                "manifest-1", "task-policy-1", autoApprove,
                "task-1", "stage-1", 1, 1, 1,
                "fingerprint", "head", "base", "target-base", NOW);
    }

    private static Policy policy(boolean enabled)
    {
        return new Policy(
                "branch-policy-1", "task-1", 1, enabled, "nightly",
                "FIRST_PUSH_DEFAULT", 3, NOW);
    }

    private static ManualBlocker blocker(String status)
    {
        return new ManualBlocker(
                "blocker-1", "task-1", "stage-1", "publish-1",
                "base", "target-base", status, NOW);
    }

    private static ManualBlocker exhaustedBlocker(String status)
    {
        return new ManualBlocker(
                "exhausted-blocker-1", "task-1", "stage-1",
                "LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED", "episode-1",
                "publish-1", "base", "target-base", status, NOW);
    }

    private static Admission admission()
    {
        Episode episode = new Episode(
                "episode-1", "publish-1", "stage-1", "task-1", 1, 1,
                "fingerprint", "head", "base", "target-base",
                AuthorityKind.STANDING_TASK_POLICY, "task-policy-1", null, null,
                "branch-policy-1", "command-1", 1, 3, "FETCHING",
                NOW, null, null);
        Operation operation = new Operation(
                "operation-row-1", "episode-1",
                SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE,
                "operation-1", 1, "fingerprint", "head", "base",
                "target-base", "DISPATCHED", null, null, null, null,
                null, NOW, null, null);
        return new Admission(episode, operation);
    }

    private static Delivery delivery(
            SqliteLocalPublishBaseSyncStore.Kind kind, boolean current)
    {
        return new Delivery(
                "operation-row-1", "operation-1", "episode-1", kind,
                "task-1", 1, "stage-1", 1, 1,
                "fingerprint", "head", "base", "target-base",
                kind == SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE
                        ? "FETCHING" : "REBASING",
                current);
    }

    private static Delivery deliveryWithState(
            SqliteLocalPublishBaseSyncStore.Kind kind,
            DeliveryState state,
            String lifecycle)
    {
        return new Delivery(
                "operation-row-1", "operation-1", "episode-1", kind, 1,
                "task-1", 1, "stage-1", 1, 1, 3,
                AuthorityKind.STANDING_TASK_POLICY, 1,
                "fingerprint", "head", "base", "target-base",
                kind == SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE
                        ? "FETCHING" : "REBASING",
                lifecycle, state);
    }

    private static OperationContext operationContext()
    {
        return new OperationContext(
                "operation-1", LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                "SUCCEEDED", "workspace-1", "trunk-1", "task-1", 1,
                "stage-1", 1, 1, "V2", "ACTIVE", 1, "stage-1", 1,
                "LOCAL_REVIEW", "acme/widget", "dev/task-1", "/tmp/task-1",
                "fingerprint", "head", "base", "target-base");
    }

    private static DispatchTicket.OwnerReference owner(
            SqliteLocalPublishBaseSyncStore.Kind kind)
    {
        String callback = kind == SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE
                ? LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK
                : LocalPublishBaseSyncOperationHandler.REBASE_CALLBACK;
        return new DispatchTicket.OwnerReference(STAGE, "stage-1", callback);
    }

    private static DispatchTicket.OperationFence fence()
    {
        return new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                "fingerprint", "head", "base");
    }

    private static DispatchTicket.DispatchResult raw(Result result)
            throws Exception
    {
        String payload = JSON.writeValueAsString(result);
        return new DispatchTicket.DispatchResult(
                fence(), SUCCEEDED, payload, payload, null);
    }

    private static Result fetchResult()
    {
        return new Result(
                1, "operation-1", Kind.FETCH_COMPARE, Disposition.FETCHED,
                "fingerprint", "head", "base", "target-base",
                fetchEvidence(), null);
    }

    private static Evidence fetchEvidence()
    {
        return new Evidence(
                1, Kind.FETCH_COMPARE, Proof.TARGET_PRESENT,
                "acme/widget", "origin", "dev/task-1", "/tmp/task-1",
                "fingerprint", "head", "base", "target-base",
                "fingerprint", "head", "base", List.of(), false);
    }

    private static Result rebaseResult(Disposition disposition)
    {
        boolean rebased = disposition == Disposition.REBASED;
        String fingerprint = rebased ? "rebased-fingerprint" : "fingerprint";
        String head = rebased ? "rebased-head" : "head";
        String base = rebased ? "target-base" : "base";
        Evidence evidence = new Evidence(
                1, Kind.MECHANICAL_REBASE,
                rebased ? Proof.CLEAN : Proof.CONFLICT,
                "acme/widget", null, "dev/task-1", "/tmp/task-1",
                "fingerprint", "head", "base", "target-base",
                fingerprint, head, base,
                rebased ? List.of() : List.of("conflicted.txt"), false);
        return new Result(
                1, "operation-1", Kind.MECHANICAL_REBASE, disposition,
                fingerprint, head, base, "target-base", evidence, null);
    }

    private static TurnContext context(Result result)
            throws Exception
    {
        return new TurnContext(
                "episode-1", "task-1", 1, "stage-1", 1, 7,
                "LOCAL_REVIEW", "RECONCILING",
                result.codeFingerprint(), result.headSha(), result.baseSha(),
                "target-base", ResultDisposition.valueOf(
                        result.disposition().name()),
                JSON.writeValueAsString(result.evidence()),
                "/tmp/task-1", "dev/task-1", "main", "workspace-1",
                "trunk-1", "CODEX", "{}", "openai", "model", "dev",
                "MEDIUM", "Task", "previous-turn");
    }

    private static BaseSyncTurn turn(Result result)
    {
        return new BaseSyncTurn(
                "episode-1", "request-1", "start-command", "turn-1",
                "turn-operation", "turn-ticket", "workspace-1", "trunk-1",
                "task-1", 1, "stage-1", 1, 1,
                result.codeFingerprint(), result.headSha(), result.baseSha(),
                "target-base", "API", 2, "{}", "b".repeat(64),
                "v2-local-runtime", NOW);
    }

    private static ResultFence turnFence(BaseSyncTurn turn)
    {
        return new ResultFence(
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.attempt(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha());
    }

    private static StartReceiptEvidence startReceipt(
            BaseSyncTurn turn, StageManager.State state)
    {
        return new StartReceiptEvidence(
                "receipt-1", "episode-1", turn.requestId(), turn.commandId(),
                turn.requestedBy(), turn.taskId(), turn.stageId(),
                turn.taskEpoch(), turn.stageGeneration(), 7, state.version(),
                turn.operationId(), turn.attempt(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha(), turn.targetBaseSha(), NOW);
    }

    private static final class NoopTransactions
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
