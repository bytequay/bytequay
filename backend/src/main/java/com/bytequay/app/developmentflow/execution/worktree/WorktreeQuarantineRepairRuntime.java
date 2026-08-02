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
package com.bytequay.app.developmentflow.execution.worktree;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore.Admission;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore.DeliveryReceipt;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore.DeliveryRequest;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore.RepairRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.INDETERMINATE;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static java.util.Objects.requireNonNull;

/** User command and exact result-delivery boundary for worktree repair. */
public final class WorktreeQuarantineRepairRuntime
        implements ExecutionPorts.ResultDeliveryPort
{
    private final TaskCommandExecutor commands;
    private final SqliteWorktreeQuarantineRepairStore store;
    private final ObjectMapper json;
    private final ObjectReader resultReader;
    private final Clock clock;

    public WorktreeQuarantineRepairRuntime(
            TaskCommandExecutor commands,
            SqliteWorktreeQuarantineRepairStore store,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(
                        WorktreeQuarantineRepairOperationHandler
                                .RepairResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Admission request(
            String taskId,
            String quarantineId,
            String blockerId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String worktreePath,
            String expectedBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String commandId,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(quarantineId, "quarantineId");
        requireText(blockerId, "blockerId");
        if (taskEpoch < 1 || stageGeneration < 1) {
            throw new IllegalArgumentException(
                    "Quarantine repair epoch or Stage generation is invalid");
        }
        requireText(stageId, "stageId");
        requireText(worktreePath, "worktreePath");
        requireText(expectedBranchName, "expectedBranchName");
        requireText(expectedCodeFingerprint, "expectedCodeFingerprint");
        requireText(expectedHeadSha, "expectedHeadSha");
        requireText(expectedBaseSha, "expectedBaseSha");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        return commands.execute(taskId, () -> store.request(new RepairRequest(
                taskId, quarantineId, blockerId, taskEpoch, stageId,
                stageGeneration, worktreePath, expectedBranchName,
                expectedCodeFingerprint, expectedHeadSha, expectedBaseSha,
                commandId, actor, reason, clock.instant())));
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
        if (owner.kind() != DispatchTicket.OwnerKind.TASK
                || !WorktreeQuarantineRepairOperationHandler.CALLBACK_ROUTE
                        .equals(owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            throw new IllegalArgumentException(
                    "Quarantine repair delivery owner or fence is invalid");
        }
        if (rawResult.outcome() == INDETERMINATE) {
            throw new IllegalArgumentException(
                    "Indeterminate quarantine repair must reconcile first");
        }
        WorktreeQuarantineRepairOperationHandler.RepairResult result =
                readResult(rawResult.payloadJson());
        if (!expectedFence.operationId().equals(result.operationId())
                || !expectedFence.expectedCodeFingerprint().equals(
                        result.expectedCodeFingerprint())
                || !expectedFence.expectedHeadSha().equals(
                        result.expectedHeadSha())
                || !expectedFence.expectedBaseSha().equals(
                        result.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Quarantine repair result has the wrong frozen subject");
        }
        String taskId = store.requireTaskId(expectedFence.operationId());
        if (!owner.id().equals(taskId)) {
            throw new IllegalArgumentException(
                    "Quarantine repair result has the wrong Task owner");
        }
        DeliveryReceipt delivered = commands.execute(taskId, () -> store.deliver(
                new DeliveryRequest(
                        expectedFence.operationId(), rawResult.outcome(),
                        digest(requireNonNull(rawResult.payloadJson(),
                                "repair payload is null")),
                        result, rawResult.error(), clock.instant())));
        return new DispatchTicket.DeliveryReceipt(
                delivered.acceptance(), write(delivered));
    }

    private WorktreeQuarantineRepairOperationHandler.RepairResult readResult(
            String payload)
    {
        requireNonNull(payload, "repair payload is null");
        try {
            return resultReader.readValue(payload);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Malformed quarantine repair result", failure);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Could not serialize quarantine repair delivery", failure);
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
