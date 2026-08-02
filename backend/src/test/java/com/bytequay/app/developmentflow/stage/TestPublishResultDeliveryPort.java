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
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishRawResult;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.RemoteReference;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.AcceptedBaseMove;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.BaseMovedAcceptance;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.Context;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.Receipt;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static com.bytequay.app.developmentflow.stage.StageCheckpoint.WAITING_CI;
import static com.bytequay.app.developmentflow.stage.StageKind.REMOTE_DEVELOPMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPublishResultDeliveryPort
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private MemoryStore store;
    private LocalDevelopmentStageManager local;
    private LocalToRemoteHandoff handoff;
    private RemoteObservationRuntimeCoordinator observations;
    private PRService prs;
    private BaseMovedAcceptance baseMoves;
    private PublishResultDeliveryPort delivery;

    @BeforeEach
    void setUp()
    {
        store = new MemoryStore(context());
        local = mock(LocalDevelopmentStageManager.class);
        handoff = mock(LocalToRemoteHandoff.class);
        observations = mock(RemoteObservationRuntimeCoordinator.class);
        prs = mock(PRService.class);
        baseMoves = mock(BaseMovedAcceptance.class);
        delivery = new PublishResultDeliveryPort(
                new TaskCommandExecutor(new NoopTransactions()),
                local, handoff, observations, prs, store, baseMoves, JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successfulPublishUsesStablePrAndTaskHandoffAndReplaysItsReceipt()
            throws Exception
    {
        StageManager.State remote = new StageManager.State(
                PlanRuntimeCoordinator.id("remote-stage", "operation-1"),
                "task-1", REMOTE_DEVELOPMENT, 1, 0, WAITING_CI, null, null);
        when(handoff.acceptInCommand(any())).thenReturn(
                new LocalToRemoteHandoff.Result(
                        CommandResult.applied(remote), Optional.empty(),
                        Optional.of(CommandResult.applied(remote))));
        DispatchTicket.DispatchResult result = succeeded();

        DispatchTicket.DeliveryReceipt first = delivery.deliver(
                owner(), fence(), result);
        DispatchTicket.DeliveryReceipt duplicate = delivery.deliver(
                owner(), fence(), result);

        assertThat(first.acceptance()).isEqualTo(ACCEPTED);
        assertThat(duplicate).isEqualTo(first);
        assertThat(store.published).isEqualTo(1);
        assertThat(store.remoteInitialized).isEqualTo(1);
        assertThat(store.receipt).isPresent();
        verify(handoff).acceptInCommand(any());
        verify(observations).requestObservationInCommand(
                "task-1", remote.id());
        verify(prs).recordPublishedInCommand(
                "pr-1", "acme/widget", 17,
                "https://github.com/acme/widget/pull/17");
    }

    @Test
    void lateSuccessfulPublishPersistsRemoteIdentityWithoutOpeningAStage()
            throws Exception
    {
        StageManager.State localState = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 4, StageCheckpoint.PUBLISHING, null, resultFence());
        when(handoff.acceptInCommand(any())).thenReturn(
                new LocalToRemoteHandoff.Result(
                        CommandResult.superseded(localState),
                        Optional.empty(), Optional.empty()));

        DispatchTicket.DeliveryReceipt receipt = delivery.deliver(
                owner(), fence(), succeeded());

        assertThat(receipt.acceptance()).isEqualTo(SUPERSEDED);
        assertThat(store.published).isEqualTo(1);
        assertThat(store.remoteInitialized).isZero();
        assertThat(store.receipt.orElseThrow().remoteStageId()).isNull();
        verify(observations, never()).requestObservationInCommand(any(), any());
        verify(prs).recordPublishedInCommand(
                "pr-1", "acme/widget", 17,
                "https://github.com/acme/widget/pull/17");
    }

    @Test
    void definiteFailureReturnsTheCurrentLocalStageToReview()
            throws Exception
    {
        store.pendingOutcome = FAILED;
        StageManager.State localState = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 5, StageCheckpoint.LOCAL_REVIEW, null, null);
        when(local.acceptPublishFailureInCommand(any())).thenReturn(
                new LocalDevelopmentStageManager.PublishFailureResult(
                        CommandResult.applied(localState), true));
        PublishRawResult payload = new PublishRawResult(
                1, "publish-1", "operation-1", "task-1", "stage-1",
                PublishOperationHandler.Disposition.FAILED,
                null, "GitHub rejected the request");
        DispatchTicket.DispatchResult failed = new DispatchTicket.DispatchResult(
                fence(), FAILED, JSON.writeValueAsString(payload),
                "{}", "GitHub rejected the request");

        DispatchTicket.DeliveryReceipt receipt = delivery.deliver(
                owner(), fence(), failed);

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        assertThat(store.failed).isEqualTo(FAILED);
        verify(handoff, never()).acceptInCommand(any());
        verify(baseMoves, never()).afterAccepted(any());
    }

    @Test
    void baseMovementUsesTheRealLocalTransitionBackToReview()
            throws Exception
    {
        store.pendingOutcome = FAILED;
        MemoryStageStore stages = new MemoryStageStore(new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 4, StageCheckpoint.PUBLISHING, null, resultFence()));
        LocalDevelopmentStageManager realLocal = new LocalDevelopmentStageManager(
                new TaskCommandExecutor(new NoopTransactions()),
                stages, LocalDevelopmentStageManager.EvidenceStore.empty());
        PublishResultDeliveryPort realDelivery = new PublishResultDeliveryPort(
                new TaskCommandExecutor(new NoopTransactions()),
                realLocal, handoff, observations, prs, store, JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
        DispatchTicket.DeliveryReceipt receipt = realDelivery.deliver(
                owner(), fence(), baseMoved("new-base-sha"));

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        assertThat(stages.state().checkpoint())
                .isEqualTo(StageCheckpoint.LOCAL_REVIEW);
        assertThat(stages.state().pendingResult()).isNull();
        assertThat(stages.superseded()).isZero();
    }

    @Test
    void acceptedBaseMovementNotifiesOnceAcrossRedelivery()
            throws Exception
    {
        store.pendingOutcome = FAILED;
        StageManager.State localState = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 5, StageCheckpoint.LOCAL_REVIEW, null, null);
        when(local.acceptPublishFailureInCommand(any())).thenReturn(
                new LocalDevelopmentStageManager.PublishFailureResult(
                        CommandResult.applied(localState), true));
        DispatchTicket.DispatchResult result = baseMoved("new-base-sha");

        DispatchTicket.DeliveryReceipt first = delivery.deliver(
                owner(), fence(), result);
        DispatchTicket.DeliveryReceipt duplicate = delivery.deliver(
                owner(), fence(), result);

        assertThat(first.acceptance()).isEqualTo(ACCEPTED);
        assertThat(duplicate).isEqualTo(first);
        ArgumentCaptor<AcceptedBaseMove> accepted =
                ArgumentCaptor.forClass(AcceptedBaseMove.class);
        verify(baseMoves).afterAccepted(accepted.capture());
        assertThat(accepted.getValue().operationId()).isEqualTo("operation-1");
        assertThat(accepted.getValue().expectedBaseSha()).isEqualTo("base-sha");
        assertThat(accepted.getValue().observedBaseSha())
                .isEqualTo("new-base-sha");
        assertThat(accepted.getValue().acceptedAt()).isEqualTo(NOW);
    }

    @Test
    void malformedBaseMovementIsRejectedBeforeAnyDomainWrite()
            throws Exception
    {
        store.pendingOutcome = FAILED;

        assertThatThrownBy(() -> delivery.deliver(
                owner(), fence(), baseMoved(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typed payload");
        assertThatThrownBy(() -> delivery.deliver(
                owner(), fence(), baseMoved("base-sha")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typed payload");
        DispatchTicket.DispatchResult mismatchedError = baseMoved("new-base-sha");
        assertThatThrownBy(() -> delivery.deliver(
                owner(), fence(), new DispatchTicket.DispatchResult(
                        mismatchedError.fence(), mismatchedError.outcome(),
                        mismatchedError.payloadJson(), mismatchedError.evidenceJson(),
                        "different outer error")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typed payload");

        assertThat(store.failed).isNull();
        assertThat(store.receipt).isEmpty();
        verify(local, never()).acceptPublishFailureInCommand(any());
        verify(baseMoves, never()).afterAccepted(any());
    }

    @Test
    void supersededBaseMovementDoesNotNotify()
            throws Exception
    {
        store.pendingOutcome = FAILED;
        StageManager.State localState = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 5, StageCheckpoint.LOCAL_REVIEW, null, null);
        when(local.acceptPublishFailureInCommand(any())).thenReturn(
                new LocalDevelopmentStageManager.PublishFailureResult(
                        CommandResult.superseded(localState), false));

        DispatchTicket.DeliveryReceipt receipt = delivery.deliver(
                owner(), fence(), baseMoved("new-base-sha"));

        assertThat(receipt.acceptance()).isEqualTo(SUPERSEDED);
        verify(baseMoves, never()).afterAccepted(any());
    }

    @Test
    void canceledPublishUsesTheSameExactOwnerBoundary()
            throws Exception
    {
        store.pendingOutcome = CANCELED;
        StageManager.State localState = new StageManager.State(
                "stage-1", "task-1", StageKind.LOCAL_DEVELOPMENT,
                1, 5, StageCheckpoint.LOCAL_REVIEW, null, null);
        when(local.acceptPublishFailureInCommand(any())).thenReturn(
                new LocalDevelopmentStageManager.PublishFailureResult(
                        CommandResult.applied(localState), true));
        PublishRawResult payload = new PublishRawResult(
                1, "publish-1", "operation-1", "task-1", "stage-1",
                PublishOperationHandler.Disposition.CANCELED,
                null, "canceled before the next effect");
        DispatchTicket.DispatchResult canceled = new DispatchTicket.DispatchResult(
                fence(), CANCELED, JSON.writeValueAsString(payload),
                "{}", "canceled before the next effect");

        delivery.deliver(owner(), fence(), canceled);

        assertThat(store.failed).isEqualTo(CANCELED);
    }

    @Test
    void payloadFromAnotherOperationFailsBeforeAnyDomainWrite()
            throws Exception
    {
        PublishRawResult payload = new PublishRawResult(
                1, "publish-other", "operation-1", "task-1", "stage-1",
                PublishOperationHandler.Disposition.PUBLISHED,
                remote(), null);
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence(), SUCCEEDED, JSON.writeValueAsString(payload), "{}", null);

        assertThatThrownBy(() -> delivery.deliver(owner(), fence(), result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
        assertThat(store.published).isZero();
        assertThat(store.receipt).isEmpty();
        verify(prs, never()).recordPublishedInCommand(any(), any(), anyInt(), any());
    }

    private static DispatchTicket.DispatchResult succeeded()
            throws Exception
    {
        PublishRawResult payload = new PublishRawResult(
                1, "publish-1", "operation-1", "task-1", "stage-1",
                PublishOperationHandler.Disposition.PUBLISHED, remote(), null);
        return new DispatchTicket.DispatchResult(
                fence(), SUCCEEDED, JSON.writeValueAsString(payload), "{}", null);
    }

    private static DispatchTicket.DispatchResult baseMoved(
            String observedBaseSha)
            throws Exception
    {
        String error = "remote base moved after publish authorization";
        PublishRawResult payload = new PublishRawResult(
                1, "publish-1", "operation-1", "task-1", "stage-1",
                PublishOperationHandler.Disposition.BASE_MOVED,
                null, error, observedBaseSha);
        return new DispatchTicket.DispatchResult(
                fence(), FAILED, JSON.writeValueAsString(payload), "{}", error);
    }

    private static RemoteReference remote()
    {
        return new RemoteReference(
                "acme/widget", 17, "https://github.com/acme/widget/pull/17",
                "dev/task-1", "head-sha", "base-sha");
    }

    private static DispatchTicket.OwnerReference owner()
    {
        return new DispatchTicket.OwnerReference(
                STAGE, "stage-1", PublishOperationHandler.CALLBACK_ROUTE);
    }

    private static DispatchTicket.OperationFence fence()
    {
        return new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                "fingerprint", "head-sha", "base-sha");
    }

    private static ResultFence resultFence()
    {
        return new ResultFence(
                1, "stage-1", 1, "operation-1", 1,
                "fingerprint", "head-sha", "base-sha");
    }

    private static Context context()
    {
        return new Context(
                "publish-1", "operation-1", "authorization-1", "manifest-1",
                "policy-1",
                "pr-1", "task-1", "stage-1", 1, 1, 1,
                "fingerprint", "head-sha", "base-sha",
                "DISPATCHED", "RESULT_PENDING", SUCCEEDED,
                4, 1, true, false, 1,
                PublishOperationHandler.Route.DIRECT,
                "acme/widget", "acme/widget");
    }

    private static final class MemoryStore
            implements PublishResultDeliveryPort.Store
    {
        private final Context context;
        private Optional<Receipt> receipt = Optional.empty();
        private DispatchTicket.Outcome pendingOutcome;
        private int published;
        private int remoteInitialized;
        private DispatchTicket.Outcome failed;

        private MemoryStore(Context context)
        {
            this.context = context;
            this.pendingOutcome = context.pendingOutcome();
        }

        @Override
        public String requireTaskId(String operationId)
        {
            return context.taskId();
        }

        @Override
        public Context requireContext(String operationId)
        {
            return new Context(
                    context.publishOperationId(), context.operationId(),
                    context.authorizationId(), context.manifestId(),
                    context.policyRevisionId(), context.prId(),
                    context.taskId(), context.stageId(), context.taskEpoch(),
                    context.stageGeneration(), context.attempt(),
                    context.codeFingerprint(), context.expectedHeadSha(),
                    context.expectedBaseSha(), context.operationStatus(),
                    context.ticketStatus(), pendingOutcome,
                    context.handoffTaskVersion(), context.nextRemoteGeneration(),
                    context.autoApprove(), context.autoMerge(),
                    context.minimumApprovals(), context.route(),
                    context.baseRepositoryId(), context.headRepositoryId());
        }

        @Override
        public Optional<Receipt> findReceipt(String operationId)
        {
            return receipt;
        }

        @Override
        public void completePublished(
                Context context,
                String bindingId,
                RemoteReference remote,
                String resultEvidence,
                Instant completedAt)
        {
            published++;
        }

        @Override
        public void completeFailure(
                Context context,
                DispatchTicket.Outcome outcome,
                String error,
                Instant completedAt)
        {
            failed = outcome;
        }

        @Override
        public void initializeRemote(
                Context context,
                String bindingId,
                StageManager.State remoteStage,
                String ciPolicyId,
                String automationPolicyId,
                Instant openedAt)
        {
            remoteInitialized++;
        }

        @Override
        public void insertReceipt(Receipt receipt)
        {
            this.receipt = Optional.of(receipt);
        }
    }

    private static final class MemoryStageStore
            implements StageManager.Store
    {
        private StageManager.State state;
        private int superseded;

        private MemoryStageStore(StageManager.State state)
        {
            this.state = state;
        }

        @Override
        public Optional<StageManager.OwnerState> findOwner(
                String taskId, String stageId)
        {
            if (!state.taskId().equals(taskId) || !state.id().equals(stageId)) {
                return Optional.empty();
            }
            return Optional.of(new StageManager.OwnerState(
                    taskId, TaskLifecycle.ACTIVE, 1, stageId, state));
        }

        @Override
        public Optional<StageManager.CommandReceipt> findCommandResult(
                String taskId, String stageId, String commandId)
        {
            return Optional.empty();
        }

        @Override
        public StageManager.State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State expected,
                StageManager.State updated)
        {
            assertThat(state).isEqualTo(expected);
            state = updated;
            return state;
        }

        @Override
        public StageManager.State create(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State state)
        {
            throw new AssertionError("failure delivery must not create a Stage");
        }

        @Override
        public StageManager.State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State current)
        {
            superseded++;
            return current;
        }

        private StageManager.State state()
        {
            return state;
        }

        private int superseded()
        {
            return superseded;
        }
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
