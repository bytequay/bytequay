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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeRequest;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationSnapshot;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationStatus;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.BlockReason.MANUAL_INTERVENTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMergeResultDeliveryPort
{
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void blockedResultClearsTheExactPendingFenceAndRearmsReadiness()
            throws Exception
    {
        MergeRequest operation = request(
                OperationStatus.BLOCKED, "mergeability regressed");
        Fixture fixture = fixture(operation);
        MergeOperationHandler.MergeResult payload =
                new MergeOperationHandler.MergeResult(
                        1, operation.mergeOperationId(), operation.operationId(),
                        operation.taskId(), operation.stageId(), operation.status(),
                        operation.headSha(), operation.baseSha(), operation.lastError());
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence(), FAILED, JSON.writeValueAsString(payload),
                JSON.writeValueAsString(payload), operation.lastError());

        DispatchTicket.DeliveryReceipt receipt = fixture.delivery.deliver(
                owner(), fence(), result);

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        verify(fixture.remote).acceptMergeFailureInCommand(any());
    }

    @Test
    void dispatcherCancellationWithoutPayloadUsesTheDurableCanceledOperation()
    {
        MergeRequest operation = request(
                OperationStatus.CANCELED,
                "merge dispatch cancellation requested");
        Fixture fixture = fixture(operation);

        DispatchTicket.DeliveryReceipt receipt = fixture.delivery.deliver(
                owner(), fence(), DispatchTicket.DispatchResult.canceled(fence()));

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        verify(fixture.remote).acceptMergeFailureInCommand(any());
    }

    @Test
    void dispatcherInfrastructureExhaustionTerminalizesBeforeAcceptingGenericFailure()
    {
        MergeRequest requested = request(OperationStatus.REQUESTED, null);
        String detail = "merge dispatcher infrastructure exhausted: worker unavailable";
        MergeRequest canceled = request(OperationStatus.CANCELED, detail);
        MergeOperationHandler.OperationStore operations =
                mock(MergeOperationHandler.OperationStore.class);
        when(operations.requireByOperationId(requested.operationId())).thenReturn(
                snapshot(requested), snapshot(canceled));
        RemoteDevelopmentStageManager remote = remote(canceled);
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        MergeResultDeliveryPort delivery = new MergeResultDeliveryPort(
                new TaskCommandExecutor(new NoopTransactions()), remote,
                operations, JSON, Clock.fixed(now, ZoneOffset.UTC));
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence(), FAILED, null, "{}", "worker unavailable");

        DispatchTicket.DeliveryReceipt receipt = delivery.deliver(
                owner(), fence(), result);

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        verify(operations).block(
                eq(requested.operationId()), eq(MANUAL_INTERVENTION),
                eq(detail), eq(now));
        verify(remote).acceptMergeFailureInCommand(any());
    }

    private static Fixture fixture(MergeRequest operation)
    {
        MergeOperationHandler.OperationStore operations =
                mock(MergeOperationHandler.OperationStore.class);
        when(operations.requireByOperationId(operation.operationId())).thenReturn(
                snapshot(operation));
        RemoteDevelopmentStageManager remote = remote(operation);
        MergeResultDeliveryPort delivery = new MergeResultDeliveryPort(
                new TaskCommandExecutor(new NoopTransactions()),
                remote, operations, JSON);
        return new Fixture(delivery, remote);
    }

    private static OperationSnapshot snapshot(MergeRequest operation)
    {
        return new OperationSnapshot(operation, Optional.empty(), Optional.empty());
    }

    private static RemoteDevelopmentStageManager remote(MergeRequest operation)
    {
        RemoteDevelopmentStageManager remote = mock(RemoteDevelopmentStageManager.class);
        StageManager.State rearmed = new StageManager.State(
                operation.stageId(), operation.taskId(),
                StageKind.REMOTE_DEVELOPMENT, 1, 2,
                StageCheckpoint.READY_TO_MERGE, null, null);
        when(remote.acceptMergeFailureInCommand(any())).thenReturn(
                new RemoteDevelopmentStageManager.MergeFailureResult(
                        CommandResult.applied(rearmed), true));
        return remote;
    }

    private static MergeRequest request(OperationStatus status, String error)
    {
        return new MergeRequest(
                "merge-row", "authorization", "readiness", "operation",
                "remote-stage", "task", "trunk", "workspace", 1, 1, 1,
                MergeMode.DIRECT, status, 1, 3, 0, 0,
                "head", "base", "owner/repo", 17, "readiness", "V2",
                "ACTIVE", 1, "remote-stage", 1, "MERGING", error);
    }

    private static DispatchTicket.OwnerReference owner()
    {
        return new DispatchTicket.OwnerReference(
                STAGE, "remote-stage", MergeOperationHandler.CALLBACK_ROUTE);
    }

    private static DispatchTicket.OperationFence fence()
    {
        return new DispatchTicket.OperationFence(
                1L, "remote-stage", 1L, "operation", 1,
                null, "head", "base");
    }

    private record Fixture(
            MergeResultDeliveryPort delivery,
            RemoteDevelopmentStageManager remote) {}

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
