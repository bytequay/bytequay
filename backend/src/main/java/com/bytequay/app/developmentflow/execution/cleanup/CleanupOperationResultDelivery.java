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
package com.bytequay.app.developmentflow.execution.cleanup;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore.CleanupCompletion;
import com.bytequay.app.developmentflow.stage.CleanupCompletionHandoff;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskManager;

import java.time.Clock;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.CALLBACK_ROUTE;
import static java.util.Objects.requireNonNull;

/** Exact two-phase delivery for {@code CLEANUP_OPERATION_RESULT}. */
public final class CleanupOperationResultDelivery
        implements ExecutionPorts.ResultDeliveryPort
{
    private static final String ACTOR = "CleanupStageManager";

    private final SqliteCleanupOperationStore operations;
    private final CleanupCompletionHandoff handoff;
    private final Clock clock;

    public CleanupOperationResultDelivery(
            SqliteCleanupOperationStore operations,
            CleanupCompletionHandoff handoff,
            Clock clock)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.handoff = requireNonNull(handoff, "handoff is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != STAGE
                || !CALLBACK_ROUTE.equals(owner.callbackRoute())
                || !owner.id().equals(expectedFence.stageId())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(REJECTED, expectedFence.operationId(), "route-or-fence-mismatch");
        }
        if (rawResult.outcome() != SUCCEEDED
                || rawResult.payloadJson() == null
                || rawResult.payloadJson().isBlank()) {
            return receipt(REJECTED, expectedFence.operationId(), "cleanup-did-not-succeed");
        }

        CleanupCompletion pending = operations.findPendingDelivery(expectedFence)
                .orElse(null);
        if (pending == null) {
            return receipt(SUPERSEDED, expectedFence.operationId(), "cleanup-owner-superseded");
        }
        if (!pending.cleanupStageId().equals(owner.id())) {
            return receipt(SUPERSEDED, expectedFence.operationId(), "cleanup-owner-superseded");
        }
        operations.acceptSuccessfulResult(
                expectedFence, rawResult.payloadJson(), clock.instant());
        return receipt(ACCEPTED, expectedFence.operationId(), "cleanup-operation-completed");
    }

    @Override
    public void afterDeliveryCommitted(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult,
            DispatchTicket.DeliveryReceipt receipt)
    {
        if (owner.kind() == STAGE
                && CALLBACK_ROUTE.equals(owner.callbackRoute())
                && receipt.acceptance() == ACCEPTED) {
            finalizeTask(expectedFence.operationId());
        }
    }

    @Override
    public void recoverCommittedDeliveries(int limit)
    {
        for (CleanupCompletion completion : operations.findPendingFinalizations(limit)) {
            finalizeTask(completion.operationId());
        }
    }

    private void finalizeTask(String operationId)
    {
        operations.findPendingFinalization(operationId).ifPresent(completion -> {
            ResultFence fence = resultFence(completion);
            String commandId = commandId(completion);
            CleanupCompletionHandoff.Result result = handoff.accept(
                    new CleanupCompletionHandoff.Command(
                            new TaskManager.Command(
                                    commandId,
                                    ACTOR,
                                    completion.taskId(),
                                    completion.taskEpoch(),
                                    completion.taskVersion()),
                            new StageManager.ResultCommand(
                                    commandId, ACTOR, completion.taskId(), fence)));
            if (result.task().map(CommandResult::disposition)
                    .filter(disposition -> disposition != CommandResult.Disposition.SUPERSEDED)
                    .isEmpty()) {
                throw new IllegalStateException(
                        "accepted Cleanup delivery did not terminalize its Task");
            }
        });
    }

    private static ResultFence resultFence(CleanupCompletion completion)
    {
        return new ResultFence(
                completion.taskEpoch(), completion.cleanupStageId(),
                completion.stageGeneration(), completion.operationId(),
                completion.semanticAttempt(), null, null, null);
    }

    private static String commandId(CleanupCompletion completion)
    {
        return commandId(completion.operationId(), completion.semanticAttempt());
    }

    private static String commandId(String operationId, int attempt)
    {
        return "CLEANUP_RESULT:" + operationId + ":" + attempt;
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance,
            String operationId,
            String outcome)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                "{\"cleanupOperationId\":\"" + json(operationId)
                        + "\",\"outcome\":\"" + outcome + "\"}");
    }

    private static String json(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
