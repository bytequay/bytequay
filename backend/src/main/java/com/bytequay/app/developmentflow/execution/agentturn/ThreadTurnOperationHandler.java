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
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.skills.RoleDefinition;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.tools.PermissionResolver;
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
import java.util.List;
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

/** Executes one exact V2 Trunk-owned ThreadTurn on reserved control capacity. */
public final class ThreadTurnOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "EXECUTE_THREAD_TURN";
    public static final String CALLBACK_ROUTE = "THREAD_TURN_RESULT";

    private static final int PAYLOAD_VERSION = 1;
    private final Store store;
    private final AgentTurnProviderSession provider;
    private final ActiveAgentContextRegistry activeContexts;
    private final ToolExposurePolicy tools;
    private final ObjectMapper mapper;
    private final ObjectReader launchReader;
    private final Clock clock;

    public ThreadTurnOperationHandler(
            Store store,
            AgentTurnProviderSession provider,
            ActiveAgentContextRegistry activeContexts,
            ToolExposurePolicy tools,
            ObjectMapper mapper,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
        this.tools = requireNonNull(tools, "tools is null");
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
                    "typed ThreadTurn owner does not exist");
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
                    "invalid frozen ThreadTurn launch input: " + e.getMessage());
        }
        String inputError = validateInput(turn, envelope, input);
        if (inputError != null) {
            return failure(envelope, turn, INVALID_LAUNCH_INPUT, inputError);
        }
        if (context.isCancellationRequested()) {
            return canceled(envelope, turn, input, "canceled before provider launch");
        }

        AgentTurnProviderSession.Request request = new AgentTurnProviderSession.Request(
                input.transport(), input.provider(), input.credentialAccount(), input.model(),
                input.reasoningEffort(), Path.of(input.workingDirectory()),
                input.systemPrompt(), input.prompt(), input.toolEndpoint(),
                AgentTurnProviderSession.Access.READ_ONLY);
        Observer observer = new Observer(context);
        StartDisposition started = store.tryStart(
                turn.turnId(), turn.operationId(), clock.instant());
        if (started == StartDisposition.OTHER_TURN_RUNNING) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "another ThreadTurn is running for this Trunk");
        }
        if (started != StartDisposition.STARTED) {
            return failure(envelope, turn, STALE_FENCE,
                    "typed ThreadTurn is no longer launchable");
        }
        try (AgentTurnProviderSession.Session session = provider.open(request, observer)) {
            String agentKey = mcpAgentKey(turn.turnId(), turn.operationId());
            activeContexts.put(
                    turn.trunkId(), agentKey, context(turn),
                    new PermissionResolver.RunningScope(
                            ThreadScope.TRUNK,
                            null, null, turn.turnId()),
                    new ActiveAgentContextRegistry.TypedOwner(
                            DispatchTicket.OwnerKind.THREAD_TURN,
                            turn.turnId(), turn.operationId()));
            try {
                context.onCancellation(session::cancel);
                AgentTurnProviderSession.Result result = session.startAndAwait(null);
                context.recordUsage(
                        result.inputTokens(), result.outputTokens(), result.costUsdMilli());
                if (result.completion() == AgentTurnProviderSession.Completion.CANCELED) {
                    return canceled(
                            envelope, turn, input,
                            result.error() == null
                                    ? "provider session canceled" : result.error());
                }
                return providerResult(envelope, turn, input, result);
            }
            finally {
                activeContexts.remove(turn.trunkId(), agentKey);
            }
        }
        catch (ExecutionPorts.RetryableExecutionException retryable) {
            store.resetAfterLaunchFailure(
                    turn.turnId(), turn.operationId(), clock.instant());
            throw retryable;
        }
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        ExactTurn turn = store.find(envelope.owner().id()).orElse(null);
        AgentTurnOperationHandler.RawResult payload = raw(
                envelope, turn, null, null, "", 0, 0, 0, null,
                RECONCILIATION_REQUIRED,
                "provider attempt requires owner reconciliation");
        AgentTurnOperationHandler.Evidence evidence = evidence(
                turn, RECONCILIATION_REQUIRED,
                "provider attempt was not replayed");
        return new DispatchTicket.DispatchResult(
                envelope.fence(), INDETERMINATE, json(payload), json(evidence),
                payload.error());
    }

    public static String mcpAgentKey(String turnId, String operationId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        return "v2-thread-turn:" + turnId + ":" + operationId;
    }

    private String validateInput(
            ExactTurn turn,
            DispatchTicket.DispatchEnvelope envelope,
            AgentTurnOperationHandler.LaunchInput input)
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint = input.toolEndpoint();
        if (endpoint.ownerKind() != DispatchTicket.OwnerKind.THREAD_TURN
                || !endpoint.ownerId().equals(turn.turnId())
                || !endpoint.operationId().equals(turn.operationId())
                || endpoint.profile()
                != AgentTurnProviderSession.ToolProfile.TRUNK_CONTROL_READ_ONLY) {
            return "tool endpoint is not scoped to the exact ThreadTurn";
        }
        CapacityManager.CapacityLane requiredLane = switch (input.transport()) {
            case CLI -> CapacityManager.CapacityLane.CLI;
            case API -> CapacityManager.CapacityLane.API;
        };
        if (!envelope.capacityRequest().lanes().equals(Set.of(requiredLane))) {
            return input.transport() + " ThreadTurn requires exactly the "
                    + requiredLane + " capacity lane";
        }
        return null;
    }

    private ResolvedAgentContext context(ExactTurn turn)
    {
        boolean completionSummary =
                "TASK_COMPLETION_SUMMARY".equals(turn.purpose());
        RoleDefinition role = RoleRegistry.definition(
                completionSummary ? ByteQuayRole.BRAIN : ByteQuayRole.TRUNK);
        return new ResolvedAgentContext(
                role.role(), role.version(), role.permissionRole(), null,
                role.capabilities(), List.of(), List.of(),
                role.resources(), completionSummary
                        ? tools.completionSummaryTools()
                        : tools.activeTools(ByteQuayRole.TRUNK, null));
    }

    private DispatchTicket.DispatchResult providerResult(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnOperationHandler.LaunchInput input,
            AgentTurnProviderSession.Result result)
    {
        boolean succeeded = result.completion()
                == AgentTurnProviderSession.Completion.SUCCEEDED;
        AgentTurnOperationHandler.Disposition disposition = succeeded
                ? PROVIDER_SUCCEEDED : PROVIDER_FAILED;
        AgentTurnOperationHandler.RawResult payload = raw(
                envelope, turn, input.transport(), input.provider(), result.finalText(),
                result.inputTokens(), result.outputTokens(), result.costUsdMilli(),
                result.processPid(), disposition, result.error(),
                result.providerSessionId());
        return new DispatchTicket.DispatchResult(
                envelope.fence(), succeeded ? SUCCEEDED : FAILED,
                json(payload), json(evidence(turn, disposition, result.error())),
                result.error());
    }

    private DispatchTicket.DispatchResult canceled(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnOperationHandler.LaunchInput input,
            String error)
    {
        AgentTurnOperationHandler.RawResult payload = raw(
                envelope, turn, input.transport(), input.provider(), "",
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
                envelope, turn, null, null, "", 0, 0, 0, null,
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
            String finalText,
            long inputTokens,
            long outputTokens,
            long costUsdMilli,
            Long processPid,
            AgentTurnOperationHandler.Disposition disposition,
            String error)
    {
        return raw(envelope, turn, transport, provider, finalText, inputTokens,
                outputTokens, costUsdMilli, processPid, disposition, error, null);
    }

    private static AgentTurnOperationHandler.RawResult raw(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String finalText,
            long inputTokens,
            long outputTokens,
            long costUsdMilli,
            Long processPid,
            AgentTurnOperationHandler.Disposition disposition,
            String error,
            String providerSessionId)
    {
        return new AgentTurnOperationHandler.RawResult(
                PAYLOAD_VERSION,
                turn == null ? envelope.owner().id() : turn.turnId(),
                DispatchTicket.OwnerKind.THREAD_TURN,
                turn == null ? null : turn.purpose(),
                transport, provider, providerSessionId, finalText,
                inputTokens, outputTokens, costUsdMilli, processPid,
                disposition, error);
    }

    private static AgentTurnOperationHandler.Evidence evidence(
            ExactTurn turn,
            AgentTurnOperationHandler.Disposition disposition,
            String detail)
    {
        return new AgentTurnOperationHandler.Evidence(
                PAYLOAD_VERSION, disposition,
                turn == null ? null : digest(turn.launchInput()), null, detail);
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not encode ThreadTurn evidence", e);
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

        boolean resetAfterLaunchFailure(
                String turnId, String operationId, Instant resetAt);

        Optional<String> findMcpTrunk(
                String turnId, String operationId, Instant now);
    }

    public enum StartDisposition
    {
        STARTED,
        OTHER_TURN_RUNNING,
        STALE
    }

    public record ExactTurn(
            String turnId,
            String trunkId,
            String workspaceId,
            String purpose,
            String turnStatus,
            String operationId,
            int attempt,
            String launchInput,
            String trunkLifecycle,
            String planningOperationId,
            String expectedBaseSha,
            String currentBaseSha)
    {
        public ExactTurn
        {
            requireText(turnId, "turnId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(purpose, "purpose");
            requireText(turnStatus, "turnStatus");
            requireText(operationId, "operationId");
            requireText(launchInput, "launchInput");
            requireText(trunkLifecycle, "trunkLifecycle");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
            if ((planningOperationId == null) != (expectedBaseSha == null)) {
                throw new IllegalArgumentException(
                        "ThreadTurn planning fence is incomplete");
            }
        }

        public ExactTurn(
                String turnId,
                String trunkId,
                String workspaceId,
                String purpose,
                String turnStatus,
                String operationId,
                int attempt,
                String launchInput,
                String trunkLifecycle)
        {
            this(turnId, trunkId, workspaceId, purpose, turnStatus,
                    operationId, attempt, launchInput, trunkLifecycle,
                    null, null, null);
        }

        private String validate(DispatchTicket.DispatchEnvelope envelope)
        {
            DispatchTicket.OperationFence fence = envelope.fence();
            CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
            if (!OPERATION_KIND.equals(envelope.operationKind())
                    || envelope.family() != AGENT_TURN
                    || envelope.owner().kind() != DispatchTicket.OwnerKind.THREAD_TURN
                    || !envelope.owner().id().equals(turnId)
                    || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())) {
                return "DispatchTicket does not name the exact ThreadTurn";
            }
            if (!operationId.equals(fence.operationId())
                    || attempt != fence.attempt()
                    || fence.taskEpoch() != null
                    || fence.stageId() != null
                    || fence.stageGeneration() != null
                    || fence.expectedCodeFingerprint() != null
                    || fence.expectedHeadSha() != null
                    || !Objects.equals(
                    expectedBaseSha, fence.expectedBaseSha())) {
                return "DispatchTicket fence differs from the immutable ThreadTurn";
            }
            if (planningOperationId != null
                    && !Objects.equals(expectedBaseSha, currentBaseSha)) {
                return "ThreadTurn planning snapshot is no longer current";
            }
            if (!workspaceId.equals(capacity.scope().workspaceId())
                    || !trunkId.equals(capacity.scope().trunkId())
                    || capacity.scope().taskId() != null
                    || capacity.scope().taskEpoch() != null
                    || !capacity.trunkControl()
                    || capacity.exclusiveTask()
                    || capacity.writerRequired()) {
                return "ThreadTurn capacity is not reserved Trunk control";
            }
            if ("ARCHIVED".equals(trunkLifecycle)
                    || !("REQUESTED".equals(turnStatus)
                    || "QUEUED".equals(turnStatus)
                    || "CLAIMED".equals(turnStatus))) {
                return "ThreadTurn is no longer launchable";
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
