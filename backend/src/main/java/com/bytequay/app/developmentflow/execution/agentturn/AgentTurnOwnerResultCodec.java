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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import static java.util.Objects.requireNonNull;

/** Strict decoding boundary used by typed Turn result commands. */
public final class AgentTurnOwnerResultCodec
{
    private final ObjectReader reader;

    public AgentTurnOwnerResultCodec(ObjectMapper mapper)
    {
        requireNonNull(mapper, "mapper is null");
        this.reader = mapper.readerFor(AgentTurnOperationHandler.RawResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public OwnerResult decode(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.THREAD_TURN
                && owner.kind() != DispatchTicket.OwnerKind.TASK_TURN
                && owner.kind() != DispatchTicket.OwnerKind.STAGE_TURN) {
            throw new IllegalArgumentException("Agent Turn result has a non-Turn owner");
        }
        if (!expectedFence.equals(rawResult.fence())) {
            throw new IllegalArgumentException("Agent Turn result fence is stale");
        }
        AgentTurnOperationHandler.RawResult payload;
        try {
            payload = reader.readValue(rawResult.payloadJson());
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Agent Turn result payload is invalid", e);
        }
        if (!owner.id().equals(payload.turnId()) || owner.kind() != payload.ownerKind()) {
            throw new IllegalArgumentException("Agent Turn result owner is stale");
        }
        requireCompatible(rawResult.outcome(), payload.disposition());
        return new OwnerResult(owner, expectedFence, rawResult.outcome(), payload);
    }

    private static void requireCompatible(
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition)
    {
        boolean compatible = switch (outcome) {
            case SUCCEEDED -> disposition
                    == AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED;
            case CANCELED -> disposition
                    == AgentTurnOperationHandler.Disposition.PROVIDER_CANCELED;
            case INDETERMINATE -> disposition
                    == AgentTurnOperationHandler.Disposition.RECONCILIATION_REQUIRED;
            case FAILED -> disposition != AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED
                    && disposition != AgentTurnOperationHandler.Disposition.PROVIDER_CANCELED
                    && disposition
                    != AgentTurnOperationHandler.Disposition.RECONCILIATION_REQUIRED;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Agent Turn result outcome and disposition disagree");
        }
    }

    @FunctionalInterface
    public interface OwnerDeliveryPort
    {
        DispatchTicket.DeliveryReceipt deliver(OwnerResult result)
                throws Exception;
    }

    public record OwnerResult(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.RawResult payload)
    {
        public OwnerResult
        {
            requireNonNull(owner, "owner is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(payload, "payload is null");
        }
    }
}
