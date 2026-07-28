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
package com.bytequay.app.developmentflow.userwait;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static java.util.Objects.requireNonNull;

/**
 * Delivery boundary for the one non-domain terminal Agent Turn outcome. A
 * USER_WAIT succeeds the execution attempt, freezes evidence on the exact
 * typed Turn, and deliberately does not invoke the owner's normal result
 * transition.
 */
public final class V2UserWaitResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final V2UserWaitStore waits;
    private final ExecutionPorts.ResultDeliveryPort delegate;
    private final ObjectMapper json;
    private final ObjectReader resultReader;
    private final Clock clock;

    public V2UserWaitResultDeliveryPort(
            V2UserWaitStore waits,
            ExecutionPorts.ResultDeliveryPort delegate,
            ObjectMapper json,
            Clock clock)
    {
        this.waits = requireNonNull(waits, "waits is null");
        this.delegate = requireNonNull(delegate, "delegate is null");
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(AgentTurnOperationHandler.RawResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
            throws Exception
    {
        AgentTurnOperationHandler.RawResult result = typedResult(owner, rawResult);
        if (result == null
                || result.disposition()
                    != AgentTurnOperationHandler.Disposition.USER_WAIT) {
            return delegate.deliver(owner, expectedFence, rawResult);
        }
        if (!expectedFence.equals(rawResult.fence())
                || rawResult.outcome() != DispatchTicket.Outcome.SUCCEEDED
                || !owner.id().equals(result.turnId())
                || owner.kind() != result.ownerKind()) {
            throw new IllegalArgumentException("typed USER_WAIT result fence is stale");
        }
        AgentTurnOperationHandler.UserWaitRef wait = result.userWait();
        ActiveAgentContextRegistry.TypedOwner typedOwner =
                new ActiveAgentContextRegistry.TypedOwner(
                        owner.kind(), owner.id(), expectedFence.operationId());
        // Persist and validate the exact wait before changing the owner domain.
        // A delivery retry can then safely finish clearing the pending result.
        V2UserWaitStore.UserWaitReceipt durable = waits.recordUserWait(
                typedOwner,
                wait.kind(), wait.id(), digest(rawResult.payloadJson()),
                rawResult.evidenceJson(), clock.instant());
        ObjectNode evidence = json.createObjectNode();
        evidence.put("schema", "TYPED_USER_WAIT_DELIVERY_V1");
        evidence.put("acceptance", ACCEPTED.name());
        evidence.put("turnKind", durable.owner().kind().name());
        evidence.put("turnId", durable.owner().turnId());
        evidence.put("operationId", durable.owner().operationId());
        evidence.put("waitKind", durable.waitKind());
        evidence.put("waitId", durable.waitId());
        return new DispatchTicket.DeliveryReceipt(
                ACCEPTED, json.writeValueAsString(evidence));
    }

    @Override
    public void afterDeliveryCommitted(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult,
            DispatchTicket.DeliveryReceipt receipt)
            throws Exception
    {
        AgentTurnOperationHandler.RawResult result = typedResult(owner, rawResult);
        if (result == null
                || result.disposition()
                    != AgentTurnOperationHandler.Disposition.USER_WAIT) {
            delegate.afterDeliveryCommitted(
                    owner, expectedFence, rawResult, receipt);
        }
    }

    @Override
    public void recoverCommittedDeliveries(int limit)
            throws Exception
    {
        delegate.recoverCommittedDeliveries(limit);
    }

    private AgentTurnOperationHandler.RawResult typedResult(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.DispatchResult rawResult)
    {
        if (owner.kind() != DispatchTicket.OwnerKind.THREAD_TURN
                && owner.kind() != DispatchTicket.OwnerKind.TASK_TURN
                && owner.kind() != DispatchTicket.OwnerKind.STAGE_TURN
                && owner.kind()
                    != DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN) {
            return null;
        }
        try {
            return resultReader.readValue(rawResult.payloadJson());
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "typed Agent Turn result payload is invalid", e);
        }
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
