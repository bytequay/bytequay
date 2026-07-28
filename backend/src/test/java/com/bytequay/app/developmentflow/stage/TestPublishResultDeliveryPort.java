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
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.Context;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.Receipt;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private TaskStore tasks;
    private PublishResultDeliveryPort delivery;

    @BeforeEach
    void setUp()
    {
        store = new MemoryStore(context());
        local = mock(LocalDevelopmentStageManager.class);
        handoff = mock(LocalToRemoteHandoff.class);
        observations = mock(RemoteObservationRuntimeCoordinator.class);
        prs = mock(PRService.class);
        tasks = mock(TaskStore.class);
        delivery = new PublishResultDeliveryPort(
                new TaskCommandExecutor(new NoopTransactions()),
                local, handoff, observations, prs, tasks, store, JSON,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void successfulPublishCreatesOneRemoteOwnerAndReplaysItsReceipt()
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
        verify(tasks).markPushed("task-1", NOW);
        verify(tasks).linkPullRequest("task-1", 17, "draft");
        verify(tasks).linkTaskToPr("task-1", "acme/widget#17");
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
                    context.authorizationId(), context.manifestId(), context.prId(),
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
