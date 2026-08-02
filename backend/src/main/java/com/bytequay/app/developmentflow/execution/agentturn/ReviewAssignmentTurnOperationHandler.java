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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.AGENT_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.INDETERMINATE;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.INVALID_LAUNCH_INPUT;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.OWNER_NOT_FOUND;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.PROVIDER_CANCELED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.PROVIDER_FAILED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.RECONCILIATION_REQUIRED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.STALE_FENCE;
import static java.util.Objects.requireNonNull;

/** Executes one exact, read-only ReviewAssignmentTurn. */
public final class ReviewAssignmentTurnOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "EXECUTE_REVIEW_ASSIGNMENT_TURN";
    public static final String CALLBACK_ROUTE = "REVIEW_ASSIGNMENT_TURN_RESULT";

    private static final int PAYLOAD_VERSION = 1;

    private final Store store;
    private final AgentTurnProviderSession provider;
    private final ObjectMapper mapper;
    private final ObjectReader launchReader;
    private final Clock clock;

    public ReviewAssignmentTurnOperationHandler(
            Store store,
            AgentTurnProviderSession provider,
            ObjectMapper mapper,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.launchReader = mapper.readerFor(AgentTurnOperationHandler.LaunchInput.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        Optional<ExactTurn> loaded = store.find(envelope.owner().id());
        if (loaded.isEmpty()) {
            return failure(envelope, null, OWNER_NOT_FOUND,
                    "typed ReviewAssignmentTurn owner does not exist");
        }
        ExactTurn turn = loaded.orElseThrow();
        String fenceError = turn.validate(envelope);
        if (fenceError != null) {
            return failure(envelope, turn, STALE_FENCE, fenceError);
        }

        AgentTurnOperationHandler.LaunchInput input;
        try {
            input = launchReader.readValue(turn.launchInput());
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            return failure(envelope, turn, INVALID_LAUNCH_INPUT,
                    "invalid frozen ReviewAssignmentTurn launch input: " + e.getMessage());
        }
        String inputError = validateInput(turn, envelope, input);
        if (inputError != null) {
            return failure(envelope, turn, INVALID_LAUNCH_INPUT, inputError);
        }
        if (context.isCancellationRequested()) {
            return canceled(envelope, turn, input, "canceled before provider launch");
        }

        StartDisposition started = store.tryStart(
                turn.turnId(), turn.operationId(), clock.instant());
        if (started == StartDisposition.ROUND_WAITING) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "an earlier review round still owns this session");
        }
        if (started != StartDisposition.STARTED) {
            return failure(envelope, turn, STALE_FENCE,
                    "typed ReviewAssignmentTurn is no longer launchable");
        }

        AgentTurnProviderSession.Request request = new AgentTurnProviderSession.Request(
                input.transport(), input.provider(), input.credentialAccount(), input.model(),
                input.reasoningEffort(), Path.of(input.workingDirectory()),
                input.systemPrompt(), input.prompt(), input.images(),
                input.toolEndpoint(),
                AgentTurnProviderSession.Access.READ_ONLY,
                turn.costCapUsdMilli(),
                input.resumeSessionId(),
                input.fallbackPrompt(),
                input.priorCumulativeInputTokens(),
                input.priorCumulativeOutputTokens());
        try (AgentTurnProviderSession.Session session = provider.open(
                request, new Observer(context))) {
            context.onCancellation(session::cancel);
            AgentTurnProviderSession.Result result = session.startAndAwait(null);
            context.recordUsage(
                    result.inputTokens(), result.outputTokens(), result.costUsdMilli());
            if (result.completion() == AgentTurnProviderSession.Completion.CANCELED) {
                return canceled(envelope, turn, input,
                        result.error() == null
                                ? "provider session canceled" : result.error());
            }
            return providerResult(envelope, turn, input, result);
        }
    }

    /** A claimed provider attempt is never replayed after a lost lease. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        ExactTurn turn = store.find(envelope.owner().id()).orElse(null);
        AgentTurnOperationHandler.RawResult payload = raw(
                envelope, turn, null, null, null, "", 0, 0, 0, null,
                RECONCILIATION_REQUIRED,
                "provider attempt requires exact review-owner reconciliation");
        return new DispatchTicket.DispatchResult(
                envelope.fence(), INDETERMINATE, json(payload),
                json(evidence(turn, RECONCILIATION_REQUIRED,
                        "provider attempt was not replayed")),
                payload.error());
    }

    private static String validateInput(
            ExactTurn turn,
            DispatchTicket.DispatchEnvelope envelope,
            AgentTurnOperationHandler.LaunchInput input)
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint = input.toolEndpoint();
        if (endpoint.ownerKind() != DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN
                || !endpoint.ownerId().equals(turn.turnId())
                || !endpoint.operationId().equals(turn.operationId())
                || endpoint.profile()
                != AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY
                || endpoint.approvalPromptTool() == null) {
            return "tool endpoint is not scoped to the exact ReviewAssignmentTurn";
        }
        CapacityManager.CapacityLane runnerLane = switch (input.transport()) {
            case CLI -> CapacityManager.CapacityLane.CLI;
            case API -> CapacityManager.CapacityLane.API;
        };
        if (!envelope.capacityRequest().lanes().equals(
                Set.of(CapacityManager.CapacityLane.REVIEW, runnerLane))) {
            return input.transport() + " review Turn requires exactly REVIEW and "
                    + runnerLane + " capacity";
        }
        return null;
    }

    private DispatchTicket.DispatchResult providerResult(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnOperationHandler.LaunchInput input,
            AgentTurnProviderSession.Result result)
    {
        boolean exceeded = result.costUsdMilli() > turn.costCapUsdMilli();
        boolean succeeded = result.completion()
                == AgentTurnProviderSession.Completion.SUCCEEDED && !exceeded;
        AgentTurnOperationHandler.Disposition disposition = succeeded
                ? PROVIDER_SUCCEEDED : PROVIDER_FAILED;
        String error = exceeded
                ? "provider exceeded the frozen review Turn cost cap"
                : result.error();
        AgentTurnOperationHandler.RawResult payload = new AgentTurnOperationHandler.RawResult(
                PAYLOAD_VERSION, turn.turnId(),
                DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                turn.purpose(), input.transport(), input.provider(),
                result.providerSessionId(), result.finalText(), result.inputTokens(),
                result.outputTokens(), result.costUsdMilli(), result.processPid(),
                disposition, error, null, null,
                result.cumulativeInputTokens(),
                result.cumulativeOutputTokens());
        return new DispatchTicket.DispatchResult(
                envelope.fence(), succeeded ? SUCCEEDED : FAILED,
                json(payload), json(evidence(turn, disposition, error)), error);
    }

    private DispatchTicket.DispatchResult canceled(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnOperationHandler.LaunchInput input,
            String error)
    {
        AgentTurnOperationHandler.RawResult payload = raw(
                envelope, turn, input.transport(), input.provider(), null, "",
                0, 0, 0, null, PROVIDER_CANCELED, error);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), CANCELED, json(payload),
                json(evidence(turn, PROVIDER_CANCELED, error)), error);
    }

    private DispatchTicket.DispatchResult failure(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnOperationHandler.Disposition disposition,
            String error)
    {
        AgentTurnOperationHandler.RawResult payload = raw(
                envelope, turn, null, null, null, "", 0, 0, 0, null,
                disposition, error);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), FAILED, json(payload),
                json(evidence(turn, disposition, error)), error);
    }

    private static AgentTurnOperationHandler.RawResult raw(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String providerSessionId,
            String finalText,
            long inputTokens,
            long outputTokens,
            long costUsdMilli,
            Long processPid,
            AgentTurnOperationHandler.Disposition disposition,
            String error)
    {
        return new AgentTurnOperationHandler.RawResult(
                PAYLOAD_VERSION,
                turn == null ? envelope.owner().id() : turn.turnId(),
                DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                turn == null ? null : turn.purpose(), transport, provider,
                providerSessionId, finalText, inputTokens, outputTokens,
                costUsdMilli, processPid, disposition, error);
    }

    private static AgentTurnOperationHandler.Evidence evidence(
            ExactTurn turn,
            AgentTurnOperationHandler.Disposition disposition,
            String detail)
    {
        return new AgentTurnOperationHandler.Evidence(
                PAYLOAD_VERSION, disposition,
                turn == null ? null : digest(
                        turn.launchInput() + "\u0000" + turn.costCapUsdMilli()),
                null, detail);
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not encode ReviewAssignmentTurn evidence", e);
        }
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public interface Store
    {
        Optional<ExactTurn> find(String turnId);

        StartDisposition tryStart(String turnId, String operationId, Instant startedAt);

        Optional<McpOwner> findMcpOwner(
                String turnId, String operationId, Instant now);
    }

    public enum StartDisposition
    {
        STARTED,
        ROUND_WAITING,
        STALE
    }

    public record McpOwner(
            String reviewId,
            String assignmentId,
            String purpose,
            String subjectKey,
            String verifierRunId)
    {
        public McpOwner
        {
            requireText(reviewId, "reviewId");
            requireText(assignmentId, "assignmentId");
            requireText(purpose, "purpose");
            requireText(subjectKey, "subjectKey");
            if (verifierRunId != null) {
                requireText(verifierRunId, "verifierRunId");
            }
        }
    }

    public record ExactTurn(
            String turnId,
            String assignmentId,
            String roundId,
            String reviewId,
            String purpose,
            String subjectKey,
            String verifierRunId,
            String turnStatus,
            String operationId,
            int attempt,
            String startCommit,
            String launchInput,
            long costCapUsdMilli,
            String roundStatus,
            String reviewStatus,
            String workspaceId,
            String trunkId,
            String taskId,
            Long taskEpoch,
            String taskLifecycle,
            String currentHeadSha)
    {
        public ExactTurn
        {
            requireText(turnId, "turnId");
            requireText(assignmentId, "assignmentId");
            requireText(roundId, "roundId");
            requireText(reviewId, "reviewId");
            requireText(purpose, "purpose");
            requireText(subjectKey, "subjectKey");
            if (verifierRunId != null) {
                requireText(verifierRunId, "verifierRunId");
            }
            requireText(turnStatus, "turnStatus");
            requireText(operationId, "operationId");
            requireText(startCommit, "startCommit");
            requireText(launchInput, "launchInput");
            requireText(roundStatus, "roundStatus");
            requireText(reviewStatus, "reviewStatus");
            requireText(currentHeadSha, "currentHeadSha");
            if (attempt < 1 || costCapUsdMilli < 1
                    || (taskId == null) != (taskEpoch == null)) {
                throw new IllegalArgumentException(
                        "review Turn attempt, cost cap, or Task scope is invalid");
            }
            if ("independent-verification".equals(purpose)
                    != (verifierRunId != null)) {
                throw new IllegalArgumentException(
                        "review verifier purpose and run id do not match");
            }
            if (taskId != null) {
                requireText(workspaceId, "workspaceId");
                requireText(trunkId, "trunkId");
                requireText(taskLifecycle, "taskLifecycle");
            }
        }

        private String validate(DispatchTicket.DispatchEnvelope envelope)
        {
            DispatchTicket.OperationFence fence = envelope.fence();
            CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
            if (!OPERATION_KIND.equals(envelope.operationKind())
                    || envelope.family() != AGENT_TURN
                    || envelope.owner().kind()
                    != DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN
                    || !envelope.owner().id().equals(turnId)
                    || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())) {
                return "DispatchTicket does not name the exact ReviewAssignmentTurn";
            }
            if (!operationId.equals(fence.operationId())
                    || attempt != fence.attempt()
                    || !Objects.equals(taskEpoch, fence.taskEpoch())
                    || fence.stageId() != null
                    || fence.stageGeneration() != null
                    || fence.expectedCodeFingerprint() != null
                    || !startCommit.equals(fence.expectedHeadSha())
                    || fence.expectedBaseSha() != null) {
                return "DispatchTicket fence differs from the immutable review Turn";
            }
            CapacityManager.CapacityScope scope = capacity.scope();
            if (!Objects.equals(workspaceId, scope.workspaceId())
                    || !Objects.equals(trunkId, scope.trunkId())
                    || !Objects.equals(taskId, scope.taskId())
                    || !Objects.equals(taskEpoch, scope.taskEpoch())
                    || capacity.trunkControl()
                    || capacity.exclusiveTask()
                    || capacity.writerRequired()) {
                return "ReviewAssignmentTurn capacity scope or writer mode is invalid";
            }
            if (!"ACTIVE".equals(reviewStatus)
                    || !("QUEUED".equals(roundStatus) || "RUNNING".equals(roundStatus))
                    || !("REQUESTED".equals(turnStatus)
                    || "QUEUED".equals(turnStatus)
                    || "CLAIMED".equals(turnStatus))
                    || (taskId != null && !"ACTIVE".equals(taskLifecycle))
                    || !startCommit.equals(currentHeadSha)) {
                return "review Turn owner or reviewed commit is no longer current";
            }
            return null;
        }
    }

    private static final class Observer
            implements AgentTurnProviderSession.Observer
    {
        private final ExecutionContext context;

        private Observer(ExecutionContext context)
        {
            this.context = requireNonNull(context, "context is null");
        }

        @Override
        public void providerSession(String provider, String sessionId)
        {
            context.providerSession(provider, sessionId);
        }

        @Override
        public void processStarted(long pid, String logReference)
        {
            context.processStarted(pid, logReference);
        }

        @Override
        public void log(long sequence, String payloadJson)
        {
            context.appendLog(sequence, payloadJson);
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
