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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Accepts one exact review-seat result and projects it into AgentReview. */
public final class ReviewAssignmentTurnResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private final Store store;
    private final ObjectReader resultReader;
    private final ObjectMapper json;
    private final Clock clock;
    private final Supplier<Consumer<String>> continuation;

    public ReviewAssignmentTurnResultDeliveryPort(
            Store store,
            ObjectMapper json,
            Clock clock)
    {
        this(store, json, clock, () -> null);
    }

    public ReviewAssignmentTurnResultDeliveryPort(
            Store store,
            ObjectMapper json,
            Clock clock,
            Supplier<Consumer<String>> continuation)
    {
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(AgentTurnOperationHandler.RawResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
        this.continuation = requireNonNull(continuation, "continuation is null");
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
        if (owner.kind() != DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN
                || !ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE.equals(
                owner.callbackRoute())) {
            return rejected("result route does not own a ReviewAssignmentTurn");
        }
        if (!expectedFence.equals(rawResult.fence())) {
            return rejected("raw result fence differs from the delivery fence");
        }

        AgentTurnOperationHandler.RawResult payload = decode(rawResult.payloadJson());
        if (payload != null && (payload.ownerKind()
                != DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN
                || !owner.id().equals(payload.turnId()))) {
            return rejected("raw result does not name the exact review Turn");
        }
        String shapeError = validateOutcome(rawResult, payload);
        if (shapeError != null) {
            return rejected(shapeError);
        }

        ResultCommand command = new ResultCommand(
                owner.id(), expectedFence.operationId(), expectedFence.attempt(),
                expectedFence.taskEpoch(), expectedFence.expectedHeadSha(),
                digest(rawResult), rawResult.outcome(),
                payload == null ? null : payload.disposition(),
                payload == null ? "" : payload.finalText(),
                payload == null ? 0 : payload.inputTokens(),
                payload == null ? 0 : payload.outputTokens(),
                payload == null ? 0 : payload.costUsdMilli(),
                payload == null ? null : payload.providerSessionId(),
                rawResult.payloadJson(), rawResult.evidenceJson(), rawResult.error(),
                clock.instant());
        ResultReceipt receipt = store.accept(command);
        if (receipt.acceptance() == DispatchTicket.Acceptance.ACCEPTED) {
            Consumer<String> resume = continuation.get();
            if (resume != null) {
                resume.accept(command.turnId());
            }
        }
        return new DispatchTicket.DeliveryReceipt(
                receipt.acceptance(), receipt.evidenceJson());
    }

    private AgentTurnOperationHandler.RawResult decode(String payload)
    {
        if (payload == null) {
            return null;
        }
        try {
            return resultReader.readValue(payload);
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid review Turn result payload: " + e.getMessage(), e);
        }
    }

    private static String validateOutcome(
            DispatchTicket.DispatchResult result,
            AgentTurnOperationHandler.RawResult payload)
    {
        if (payload == null) {
            if (result.outcome() == DispatchTicket.Outcome.SUCCEEDED) {
                return "successful result requires its typed provider payload";
            }
            if (!"{}".equals(result.evidenceJson())
                    || result.error() == null || result.error().isBlank()) {
                return "generic dispatcher result requires exact failure evidence";
            }
            return null;
        }
        return switch (result.outcome()) {
            case SUCCEEDED -> payload.disposition()
                    == AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED
                    ? null : "successful result lacks provider success evidence";
            case CANCELED -> payload.disposition()
                    == AgentTurnOperationHandler.Disposition.PROVIDER_CANCELED
                    ? null : "canceled result lacks provider cancellation evidence";
            case FAILED, INDETERMINATE -> payload.disposition()
                    == AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED
                    ? "unsuccessful result claims provider success" : null;
        };
    }

    private DispatchTicket.DeliveryReceipt rejected(String reason)
    {
        try {
            return new DispatchTicket.DeliveryReceipt(
                    DispatchTicket.Acceptance.REJECTED,
                    json.createObjectNode().put("reason", reason).toString());
        }
        catch (RuntimeException ignored) {
            return new DispatchTicket.DeliveryReceipt(
                    DispatchTicket.Acceptance.REJECTED, "{}");
        }
    }

    private static String digest(DispatchTicket.DispatchResult result)
    {
        String source = result.outcome() + "\u0000"
                + string(result.payloadJson()) + "\u0000"
                + string(result.evidenceJson()) + "\u0000" + string(result.error());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String string(String value)
    {
        return value == null ? "" : value;
    }

    public interface Store
    {
        ResultReceipt accept(ResultCommand command);
    }

    public record ResultCommand(
            String turnId,
            String operationId,
            int attempt,
            Long taskEpoch,
            String expectedHeadSha,
            String rawResultDigest,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            long inputTokens,
            long outputTokens,
            long costUsdMilli,
            String providerSessionId,
            String payloadJson,
            String evidenceJson,
            String error,
            Instant recordedAt)
    {
        public ResultCommand
        {
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(rawResultDigest, "rawResultDigest");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(recordedAt, "recordedAt is null");
            finalText = finalText == null ? "" : finalText;
            if (attempt < 1 || inputTokens < 0 || outputTokens < 0
                    || costUsdMilli < 0 || rawResultDigest.length() != 64) {
                throw new IllegalArgumentException("review result evidence is invalid");
            }
        }
    }

    public record ResultReceipt(
            DispatchTicket.Acceptance acceptance,
            String evidenceJson)
    {
        public ResultReceipt
        {
            requireNonNull(acceptance, "acceptance is null");
            evidenceJson = evidenceJson == null ? "{}" : evidenceJson;
        }
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
