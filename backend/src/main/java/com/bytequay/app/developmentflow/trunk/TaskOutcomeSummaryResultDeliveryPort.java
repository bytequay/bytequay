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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static java.util.Objects.requireNonNull;

/** Exact TASK_OUTCOME_SUMMARY_RESULT consumer and crash-recovery hook. */
public final class TaskOutcomeSummaryResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    public static final String CALLBACK_ROUTE = "TASK_OUTCOME_SUMMARY_RESULT";

    private final SqliteTaskOutcomeSummaryStore store;
    private final TaskOutcomeSummaryRuntime runtime;
    private final AgentTurnOwnerResultCodec codec;
    private final ObjectMapper json;
    private final Clock clock;

    public TaskOutcomeSummaryResultDeliveryPort(
            SqliteTaskOutcomeSummaryStore store,
            TaskOutcomeSummaryRuntime runtime,
            AgentTurnOwnerResultCodec codec,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.codec = requireNonNull(codec, "codec is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        if (owner.kind() != DispatchTicket.OwnerKind.TASK_TURN
                || !CALLBACK_ROUTE.equals(owner.callbackRoute())) {
            return receipt(REJECTED, "TaskOutcome summary route mismatch", null);
        }
        AgentTurnOwnerResultCodec.OwnerResult decoded =
                codec.decode(owner, expectedFence, rawResult);
        String rawDigest = ThreadTurnHandoff.digest(rawResult.payloadJson() == null
                ? "" : rawResult.payloadJson());
        SqliteTaskOutcomeSummaryStore.Completion completed =
                store.complete(decoded, rawDigest, clock.instant());
        return receipt(ACCEPTED, "TaskOutcome summary result recorded", completed);
    }

    @Override
    public void afterDeliveryCommitted(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult,
            DispatchTicket.DeliveryReceipt receipt)
    {
        if (receipt.acceptance() == ACCEPTED) {
            runtime.maintain(clock.instant());
        }
    }

    @Override
    public void recoverCommittedDeliveries(int limit)
    {
        runtime.maintain(clock.instant());
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String result,
            SqliteTaskOutcomeSummaryStore.Completion completion)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "TASK_OUTCOME_SUMMARY_DELIVERY_V1");
        node.put("result", result);
        if (completion != null) {
            node.put("taskOutcomeId", completion.taskOutcomeId());
            node.put("status", completion.status());
            node.put("duplicate", completion.duplicate());
        }
        try {
            return new DispatchTicket.DeliveryReceipt(
                    acceptance, json.writeValueAsString(node));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not encode TaskOutcome summary delivery", e);
        }
    }
}
