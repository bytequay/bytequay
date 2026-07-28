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

import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeResult;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationStatus;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static java.util.Objects.requireNonNull;

/** Capacity-free acknowledgement for an already-terminal MergeOperation. */
public final class MergeResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private static final String ACTOR = "v2-merge-delivery";

    private final TaskCommandExecutor commands;
    private final RemoteDevelopmentStageManager remote;
    private final MergeOperationHandler.OperationStore operations;
    private final ObjectReader resultReader;
    private final Clock clock;

    public MergeResultDeliveryPort(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            MergeOperationHandler.OperationStore operations,
            ObjectMapper json)
    {
        this(commands, remote, operations, json, Clock.systemUTC());
    }

    public MergeResultDeliveryPort(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            MergeOperationHandler.OperationStore operations,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.operations = requireNonNull(operations, "operations is null");
        this.resultReader = requireNonNull(json, "json is null")
                .readerFor(MergeResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !MergeOperationHandler.CALLBACK_ROUTE.equals(owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "merge delivery fence is stale");
        }
        MergeOperationHandler.MergeRequest persisted = operations
                .requireByOperationId(expectedFence.operationId()).request();
        if (!matchesOwnerFence(owner, expectedFence, persisted)) {
            return receipt(REJECTED, "merge result differs from durable operation");
        }
        if (isGenericInfrastructureFailure(rawResult)) {
            if (!persisted.status().isTerminal()) {
                operations.block(
                        persisted.operationId(),
                        MergeOperationHandler.BlockReason.MANUAL_INTERVENTION,
                        "merge dispatcher infrastructure exhausted: " + rawResult.error(),
                        clock.instant());
                persisted = operations
                        .requireByOperationId(expectedFence.operationId()).request();
            }
            if (!matchesOwnerFence(owner, expectedFence, persisted)
                    || (persisted.status() != OperationStatus.BLOCKED
                        && persisted.status() != OperationStatus.FAILED
                        && persisted.status() != OperationStatus.CANCELED)) {
                return receipt(REJECTED,
                        "generic failure differs from durable merge operation");
            }
            return acceptFailure(expectedFence, persisted);
        }
        if (!persisted.status().isTerminal()) {
            return receipt(REJECTED, "merge result differs from durable operation");
        }
        if (isGenericCancellation(rawResult)) {
            if (persisted.status() != OperationStatus.CANCELED) {
                return receipt(REJECTED,
                        "generic cancellation lacks a canceled merge operation");
            }
            return acceptFailure(expectedFence, persisted);
        }

        MergeResult result = decode(rawResult.payloadJson());
        boolean exact = result.version() == 1
                && persisted.mergeOperationId().equals(result.mergeOperationId())
                && persisted.operationId().equals(result.operationId())
                && persisted.taskId().equals(result.taskId())
                && persisted.stageId().equals(result.stageId())
                && persisted.status() == result.status()
                && persisted.headSha().equals(result.headSha())
                && persisted.baseSha().equals(result.baseSha())
                && Objects.equals(persisted.lastError(), result.detail())
                && outcomeMatches(persisted.status(), rawResult.outcome());
        if (!exact) {
            return receipt(REJECTED, "merge result differs from durable operation");
        }
        if (persisted.status() == OperationStatus.SUCCEEDED) {
            return receipt(ACCEPTED, "terminal merge result acknowledged");
        }
        return acceptFailure(expectedFence, persisted);
    }

    private static boolean matchesOwnerFence(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            MergeOperationHandler.MergeRequest persisted)
    {
        return owner.id().equals(persisted.stageId())
                && expectedFence.taskEpoch() != null
                && expectedFence.taskEpoch() == persisted.taskEpoch()
                && persisted.stageId().equals(expectedFence.stageId())
                && expectedFence.stageGeneration() != null
                && expectedFence.stageGeneration() == persisted.stageGeneration()
                && persisted.operationId().equals(expectedFence.operationId())
                && expectedFence.attempt() == persisted.semanticAttempt()
                && expectedFence.expectedCodeFingerprint() == null
                && persisted.headSha().equals(expectedFence.expectedHeadSha())
                && persisted.baseSha().equals(expectedFence.expectedBaseSha());
    }

    private static boolean isGenericCancellation(
            DispatchTicket.DispatchResult result)
    {
        return result.outcome() == DispatchTicket.Outcome.CANCELED
                && result.payloadJson() == null
                && "{}".equals(result.evidenceJson())
                && result.error() != null
                && !result.error().isBlank();
    }

    private static boolean isGenericInfrastructureFailure(
            DispatchTicket.DispatchResult result)
    {
        return result.outcome() == DispatchTicket.Outcome.FAILED
                && result.payloadJson() == null
                && "{}".equals(result.evidenceJson())
                && result.error() != null
                && !result.error().isBlank();
    }

    private DispatchTicket.DeliveryReceipt acceptFailure(
            DispatchTicket.OperationFence fence,
            MergeOperationHandler.MergeRequest persisted)
    {
        RemoteDevelopmentStageManager.MergeFailureResult result = commands.execute(
                persisted.taskId(),
                () -> remote.acceptMergeFailureInCommand(
                        new StageManager.ResultCommand(
                                "accept-merge-result:" + persisted.operationId(),
                                ACTOR, persisted.taskId(), toResultFence(fence))));
        return result.accepted()
                ? receipt(ACCEPTED, "terminal merge failure accepted")
                : receipt(SUPERSEDED, "terminal merge failure owner is stale");
    }

    private static ResultFence toResultFence(DispatchTicket.OperationFence fence)
    {
        return new ResultFence(
                fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
    }

    private MergeResult decode(String payload)
    {
        try {
            return resultReader.readValue(requireNonNull(payload, "payload is null"));
        }
        catch (Exception failure) {
            throw new IllegalArgumentException("invalid typed merge result", failure);
        }
    }

    private static boolean outcomeMatches(
            OperationStatus status, DispatchTicket.Outcome outcome)
    {
        return switch (status) {
            case SUCCEEDED -> outcome == DispatchTicket.Outcome.SUCCEEDED;
            case CANCELED -> outcome == DispatchTicket.Outcome.CANCELED;
            case FAILED, BLOCKED -> outcome == DispatchTicket.Outcome.FAILED;
            default -> false;
        };
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String detail)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                "{\"schema\":\"REMOTE_MERGE_DELIVERY_V1\",\"detail\":\""
                        + detail + "\"}");
    }
}
