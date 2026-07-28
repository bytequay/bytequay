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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.ThreadTurnOperationHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static java.util.Objects.requireNonNull;

/** Delivers one exact dispatcher fact to the Trunk-owned ThreadTurn command. */
public final class ThreadTurnResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final TrunkManager trunks;
    private final AgentTurnOwnerResultCodec codec;
    private final ObjectMapper json;
    private final Clock clock;

    public ThreadTurnResultDeliveryPort(
            TrunkManager trunks,
            AgentTurnOwnerResultCodec codec,
            ObjectMapper json,
            Clock clock)
    {
        this.trunks = requireNonNull(trunks, "trunks is null");
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
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.THREAD_TURN
                || !ThreadTurnOperationHandler.CALLBACK_ROUTE.equals(
                owner.callbackRoute())) {
            return receipt(SUPERSEDED, "ThreadTurn owner route mismatch", null);
        }
        if (expectedFence.taskEpoch() != null
                || expectedFence.stageId() != null
                || expectedFence.stageGeneration() != null
                || expectedFence.expectedCodeFingerprint() != null
                || expectedFence.expectedHeadSha() != null
                || expectedFence.expectedBaseSha() != null
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "ThreadTurn result fence is stale", null);
        }

        String rawDigest = dispatchResultDigest(rawResult);
        String finalText = null;
        if (rawResult.payloadJson() != null
                && !rawResult.payloadJson().isBlank()) {
            AgentTurnOwnerResultCodec.OwnerResult decoded = codec.decode(
                    owner, expectedFence, rawResult);
            if (rawResult.outcome() == DispatchTicket.Outcome.SUCCEEDED) {
                finalText = decoded.payload().finalText();
            }
        }
        else if (rawResult.outcome() == DispatchTicket.Outcome.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "successful ThreadTurn result has no typed payload");
        }

        TrunkManager.ThreadTurnResultFact fact =
                new TrunkManager.ThreadTurnResultFact(
                        id("result", expectedFence.operationId(), rawDigest),
                        "v2-thread-turn-delivery", owner.id(),
                        expectedFence.operationId(), expectedFence.attempt(),
                        rawResult.outcome().name(), rawDigest, finalText,
                        rawResult.error(), clock.instant());
        try {
            CommandResult<TrunkManager.ThreadTurnResultReceipt> result =
                    trunks.acceptThreadTurnResult(fact);
            DispatchTicket.Acceptance acceptance =
                    "SUPERSEDED".equals(result.state().acceptance())
                            ? SUPERSEDED : ACCEPTED;
            return receipt(acceptance, "ThreadTurn result recorded", result.state());
        }
        catch (CommandRejectedException e) {
            if (e.reason() == NOT_FOUND) {
                return receipt(REJECTED, e.getMessage(), null);
            }
            if (e.reason() == STALE_VERSION
                    || e.reason()
                    == CommandRejectedException.Reason.INVALID_STATE) {
                return receipt(SUPERSEDED, e.getMessage(), null);
            }
            throw e;
        }
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance,
            String result,
            TrunkManager.ThreadTurnResultReceipt domain)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "THREAD_TURN_DELIVERY_V1");
        node.put("acceptance", acceptance.name());
        node.put("result", result);
        if (domain != null) {
            node.put("trunkId", domain.state().id());
            node.put("trunkVersion", domain.state().version());
            node.put("turnId", domain.turnId());
            node.put("operationId", domain.operationId());
            node.put("turnStatus", domain.terminalStatus());
        }
        return new DispatchTicket.DeliveryReceipt(
                acceptance, write(node));
    }

    private String dispatchResultDigest(DispatchTicket.DispatchResult result)
    {
        ObjectNode node = json.createObjectNode();
        node.put("outcome", result.outcome().name());
        putNullable(node, "payload", result.payloadJson());
        putNullable(node, "evidence", result.evidenceJson());
        putNullable(node, "error", result.error());
        ObjectNode fence = node.putObject("fence");
        if (result.fence().taskEpoch() == null) {
            fence.putNull("taskEpoch");
        }
        else {
            fence.put("taskEpoch", result.fence().taskEpoch());
        }
        putNullable(fence, "stageId", result.fence().stageId());
        if (result.fence().stageGeneration() == null) {
            fence.putNull("stageGeneration");
        }
        else {
            fence.put("stageGeneration", result.fence().stageGeneration());
        }
        fence.put("operationId", result.fence().operationId());
        fence.put("attempt", result.fence().attempt());
        putNullable(
                fence, "expectedCodeFingerprint",
                result.fence().expectedCodeFingerprint());
        putNullable(fence, "expectedHeadSha", result.fence().expectedHeadSha());
        putNullable(fence, "expectedBaseSha", result.fence().expectedBaseSha());
        return ThreadTurnHandoff.digest(write(node));
    }

    private static void putNullable(ObjectNode node, String name, String value)
    {
        if (value == null) {
            node.putNull(name);
        }
        else {
            node.put(name, value);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode ThreadTurn delivery", e);
        }
    }

    private static String id(String kind, String operationId, String digest)
    {
        return UUID.nameUUIDFromBytes(
                ("v2-thread-turn:" + kind + ":" + operationId + ":" + digest)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
