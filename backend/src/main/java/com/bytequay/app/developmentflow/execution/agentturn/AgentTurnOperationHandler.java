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
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.AGENT_TURN;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.INDETERMINATE;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.READ_ONLY;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Access.WORKTREE_WRITE;
import static java.util.Objects.requireNonNull;

/** Executes one exact typed V2 TaskTurn or StageTurn without changing domain state. */
public final class AgentTurnOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String TASK_OPERATION_KIND = "EXECUTE_TASK_TURN";
    public static final String STAGE_OPERATION_KIND = "EXECUTE_STAGE_TURN";

    private static final int PAYLOAD_VERSION = 1;

    private final Store store;
    private final AgentTurnProviderSession provider;
    private final WorktreeWriterLeaseManager writerLeases;
    private final ObjectMapper mapper;
    private final ObjectReader launchReader;

    public AgentTurnOperationHandler(
            Store store,
            AgentTurnProviderSession provider,
            WorktreeWriterLeaseManager writerLeases,
            ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.writerLeases = requireNonNull(writerLeases, "writerLeases is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.launchReader = mapper.readerFor(LaunchInput.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        Optional<ExactTurn> loaded = store.find(envelope.owner().kind(), envelope.owner().id());
        if (loaded.isEmpty()) {
            return failure(envelope, null, Disposition.OWNER_NOT_FOUND,
                    "typed Turn owner does not exist");
        }
        ExactTurn turn = loaded.orElseThrow();
        String fenceError = turn.validate(envelope);
        if (fenceError != null) {
            return failure(envelope, turn, Disposition.STALE_FENCE, fenceError);
        }

        LaunchInput input;
        try {
            input = launchReader.readValue(turn.launchInput());
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            return failure(envelope, turn, Disposition.INVALID_LAUNCH_INPUT,
                    "invalid frozen Agent Turn launch input: " + e.getMessage());
        }
        if (!Path.of(turn.worktreePath()).equals(Path.of(input.workingDirectory()))) {
            return failure(envelope, turn, Disposition.INVALID_LAUNCH_INPUT,
                    "launch working directory is not the Task worktree");
        }
        if (turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN
                && (!turn.brainProvider().equals(input.provider())
                || !turn.brainModel().equals(input.model()))) {
            return failure(envelope, turn, Disposition.INVALID_LAUNCH_INPUT,
                    "Task Brain provider/model differs from its immutable identity");
        }
        AgentTurnProviderSession.ToolProfile expectedProfile =
                turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN
                        ? AgentTurnProviderSession.ToolProfile.TASK_BRAIN_READ_ONLY
                        : AgentTurnProviderSession.ToolProfile.STAGE_DEVELOPMENT;
        AgentTurnProviderSession.OwnerToolEndpoint endpoint = input.toolEndpoint();
        if (endpoint.ownerKind() != turn.ownerKind()
                || !endpoint.ownerId().equals(turn.turnId())
                || !endpoint.operationId().equals(turn.operationId())
                || endpoint.profile() != expectedProfile) {
            return failure(envelope, turn, Disposition.INVALID_LAUNCH_INPUT,
                    "tool endpoint is not scoped to the exact typed Turn");
        }

        CapacityManager.CapacityLane requiredLane = switch (input.transport()) {
            case CLI -> CapacityManager.CapacityLane.CLI;
            case API -> CapacityManager.CapacityLane.API;
        };
        if (!envelope.capacityRequest().lanes().equals(Set.of(requiredLane))) {
            return failure(envelope, turn, Disposition.INVALID_LAUNCH_INPUT,
                    input.transport() + " Agent Turn requires exactly the "
                            + requiredLane + " capacity lane");
        }
        if (context.isCancellationRequested()) {
            return canceled(
                    envelope, turn, input, null, "canceled before provider launch");
        }

        AgentTurnProviderSession.Access access = turn.ownerKind()
                == DispatchTicket.OwnerKind.STAGE_TURN ? WORKTREE_WRITE : READ_ONLY;
        WorktreeWriterLeaseManager.Lease writerLease = null;
        if (access == WORKTREE_WRITE) {
            writerLease = writerLeases.acquire(context, turn.worktreePath());
        }

        AgentTurnProviderSession.Request request = new AgentTurnProviderSession.Request(
                input.transport(),
                input.provider(),
                input.credentialAccount(),
                input.model(),
                input.reasoningEffort(),
                Path.of(input.workingDirectory()),
                input.systemPrompt(),
                input.prompt(),
                endpoint,
                access);
        Observer observer = new Observer(context);
        try (AgentTurnProviderSession.Session session = provider.open(request, observer)) {
            context.onCancellation(session::cancel);
            ProviderRun run = writerLease == null
                    ? new ProviderRun(session.startAndAwait(null), null)
                    : runWithWriterFence(context, writerLease, session);
            AgentTurnProviderSession.Result result = run.result();
            context.recordUsage(
                    result.inputTokens(), result.outputTokens(), result.costUsdMilli());
            if (result.completion() == AgentTurnProviderSession.Completion.CANCELED) {
                return canceled(envelope, turn, input, run.writerFence(),
                        result.error() == null ? "provider session canceled" : result.error());
            }
            return providerResult(envelope, turn, input, run);
        }
    }

    private ProviderRun runWithWriterFence(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease writerLease,
            AgentTurnProviderSession.Session session)
            throws Exception
    {
        WorktreeWriterLeaseManager.WriterAuthorization authorization =
                writerLeases.authorizeMutation(context, writerLease);
        try {
            return authorization.run(fence -> {
                AgentTurnProviderSession.WriterFence providerFence =
                        new AgentTurnProviderSession.WriterFence(
                                fence.worktreePath(),
                                fence.taskId(),
                                fence.operationId(),
                                fence.taskEpoch(),
                                fence.fencingToken());
                try {
                    return new ProviderRun(
                            session.startAndAwait(providerFence), providerFence);
                }
                catch (Exception e) {
                    throw new ProviderRunException(e);
                }
            });
        }
        catch (ProviderRunException e) {
            throw e.failure();
        }
    }

    /** A claimed attempt is never replayed; owner reconciliation decides what happened. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        ExactTurn turn = store.find(envelope.owner().kind(), envelope.owner().id())
                .orElse(null);
        RawResult payload = new RawResult(
                PAYLOAD_VERSION,
                envelope.owner().id(),
                envelope.owner().kind(),
                turn == null ? null : turn.purpose(),
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                null,
                Disposition.RECONCILIATION_REQUIRED,
                "provider attempt requires owner/worktree reconciliation");
        Evidence evidence = new Evidence(
                PAYLOAD_VERSION,
                Disposition.RECONCILIATION_REQUIRED,
                turn == null ? null : digest(turn.launchInput()),
                null,
                "provider attempt was not replayed");
        return new DispatchTicket.DispatchResult(
                envelope.fence(), INDETERMINATE, json(payload), json(evidence),
                payload.error());
    }

    private DispatchTicket.DispatchResult providerResult(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            LaunchInput input,
            ProviderRun run)
    {
        AgentTurnProviderSession.Result result = run.result();
        boolean succeeded = result.completion()
                == AgentTurnProviderSession.Completion.SUCCEEDED;
        Disposition disposition = succeeded
                ? Disposition.PROVIDER_SUCCEEDED : Disposition.PROVIDER_FAILED;
        RawResult payload = new RawResult(
                PAYLOAD_VERSION,
                turn.turnId(),
                turn.ownerKind(),
                turn.purpose(),
                input.transport(),
                input.provider(),
                result.providerSessionId(),
                result.finalText(),
                result.inputTokens(),
                result.outputTokens(),
                result.costUsdMilli(),
                result.processPid(),
                disposition,
                result.error());
        Evidence evidence = new Evidence(
                PAYLOAD_VERSION,
                disposition,
                digest(turn.launchInput()),
                run.writerFence(),
                result.error());
        return new DispatchTicket.DispatchResult(
                envelope.fence(), succeeded ? SUCCEEDED : FAILED,
                json(payload), json(evidence), result.error());
    }

    private DispatchTicket.DispatchResult canceled(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            LaunchInput input,
            AgentTurnProviderSession.WriterFence writerFence,
            String error)
    {
        RawResult payload = new RawResult(
                PAYLOAD_VERSION,
                turn.turnId(),
                turn.ownerKind(),
                turn.purpose(),
                input.transport(),
                input.provider(),
                null,
                "",
                0,
                0,
                0,
                null,
                Disposition.PROVIDER_CANCELED,
                error);
        Evidence evidence = new Evidence(
                PAYLOAD_VERSION,
                Disposition.PROVIDER_CANCELED,
                digest(turn.launchInput()),
                writerFence,
                error);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), CANCELED, json(payload), json(evidence), error);
    }

    private DispatchTicket.DispatchResult failure(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            Disposition disposition,
            String error)
    {
        RawResult payload = new RawResult(
                PAYLOAD_VERSION,
                envelope.owner().id(),
                envelope.owner().kind(),
                turn == null ? null : turn.purpose(),
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                null,
                disposition,
                error);
        Evidence evidence = new Evidence(
                PAYLOAD_VERSION,
                disposition,
                turn == null ? null : digest(turn.launchInput()),
                null,
                error);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), FAILED, json(payload), json(evidence), error);
    }

    private String json(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode Agent Turn evidence", e);
        }
    }

    private static String digest(String value)
    {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public interface Store
    {
        Optional<ExactTurn> find(DispatchTicket.OwnerKind ownerKind, String turnId);
    }

    public record ExactTurn(
            DispatchTicket.OwnerKind ownerKind,
            String turnId,
            String taskId,
            long taskEpoch,
            String stageId,
            Long stageGeneration,
            String purpose,
            String turnStatus,
            String operationId,
            int semanticAttempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String launchInput,
            String worktreePath,
            String taskLifecycle,
            String currentStageId,
            Long currentStageGeneration,
            boolean stageCompleted,
            String currentCodeFingerprint,
            String currentHeadSha,
            String currentBaseSha,
            String brainProvider,
            String brainModel)
    {
        public ExactTurn
        {
            requireNonNull(ownerKind, "ownerKind is null");
            requireText(turnId, "turnId");
            requireText(taskId, "taskId");
            requireText(purpose, "purpose");
            requireText(turnStatus, "turnStatus");
            requireText(operationId, "operationId");
            requireText(launchInput, "launchInput");
            requireText(worktreePath, "worktreePath");
            requireText(taskLifecycle, "taskLifecycle");
            if (taskEpoch < 1 || semanticAttempt < 1) {
                throw new IllegalArgumentException("Turn epoch and attempt must be positive");
            }
            if ((stageId == null) != (stageGeneration == null)) {
                throw new IllegalArgumentException("Stage identity must be complete");
            }
            if ((currentStageId == null) != (currentStageGeneration == null)) {
                throw new IllegalArgumentException("current Stage identity must be complete");
            }
            if (ownerKind == DispatchTicket.OwnerKind.STAGE_TURN
                    && (expectedCodeFingerprint == null
                    || expectedCodeFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "code-producing StageTurn requires a fingerprint");
            }
            Path exactWorktree = Path.of(worktreePath);
            if (!exactWorktree.isAbsolute()
                    || !exactWorktree.normalize().equals(exactWorktree)) {
                throw new IllegalArgumentException(
                        "worktreePath must be an absolute normalized path");
            }
            if (ownerKind == DispatchTicket.OwnerKind.TASK_TURN) {
                requireText(brainProvider, "brainProvider");
                requireText(brainModel, "brainModel");
            }
        }

        private String validate(DispatchTicket.DispatchEnvelope envelope)
        {
            DispatchTicket.OwnerReference owner = envelope.owner();
            DispatchTicket.OperationFence fence = envelope.fence();
            CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
            boolean stageTurn = ownerKind == DispatchTicket.OwnerKind.STAGE_TURN;
            String expectedKind = stageTurn ? STAGE_OPERATION_KIND : TASK_OPERATION_KIND;
            if (ownerKind != DispatchTicket.OwnerKind.TASK_TURN && !stageTurn) {
                return "Agent Turn owner kind is unsupported";
            }
            if (envelope.family() != AGENT_TURN
                    || !expectedKind.equals(envelope.operationKind())
                    || owner.kind() != ownerKind
                    || !owner.id().equals(turnId)) {
                return "DispatchTicket does not name the exact typed Turn";
            }
            if (!operationId.equals(fence.operationId())
                    || taskEpoch != value(fence.taskEpoch())
                    || semanticAttempt != fence.attempt()
                    || !Objects.equals(stageId, fence.stageId())
                    || !Objects.equals(stageGeneration, fence.stageGeneration())
                    || !Objects.equals(expectedCodeFingerprint,
                    fence.expectedCodeFingerprint())
                    || !Objects.equals(expectedHeadSha, fence.expectedHeadSha())
                    || !Objects.equals(expectedBaseSha, fence.expectedBaseSha())) {
                return "DispatchTicket fence differs from the immutable Turn";
            }
            if (!taskId.equals(capacity.scope().taskId())
                    || taskEpoch != value(capacity.scope().taskEpoch())
                    || !capacity.exclusiveTask()
                    || capacity.trunkControl()
                    || stageTurn != capacity.writerRequired()) {
                return "Agent Turn capacity scope or writer mode is invalid";
            }
            if (!"ACTIVE".equals(taskLifecycle)
                    || !("REQUESTED".equals(turnStatus)
                    || "QUEUED".equals(turnStatus)
                    || "CLAIMED".equals(turnStatus))) {
                return "typed Turn is no longer launchable";
            }
            if (stageId != null
                    && (!stageId.equals(currentStageId)
                    || !stageGeneration.equals(currentStageGeneration)
                    || stageCompleted)) {
                return "Turn Stage generation is no longer current";
            }
            if (!matchesSubject(expectedCodeFingerprint, currentCodeFingerprint)
                    || !matchesSubject(expectedHeadSha, currentHeadSha)
                    || !matchesSubject(expectedBaseSha, currentBaseSha)) {
                return "Turn code subject is no longer current";
            }
            return null;
        }

        private static long value(Long value)
        {
            return value == null ? -1 : value;
        }

        private static boolean matchesSubject(String expected, String current)
        {
            return expected == null || expected.equals(current);
        }
    }

    public record LaunchInput(
            int schemaVersion,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String credentialAccount,
            String model,
            String reasoningEffort,
            String workingDirectory,
            String systemPrompt,
            String prompt,
            AgentTurnProviderSession.OwnerToolEndpoint toolEndpoint)
    {
        public LaunchInput
        {
            if (schemaVersion != PAYLOAD_VERSION) {
                throw new IllegalArgumentException("unsupported launch input version");
            }
            requireNonNull(transport, "transport is null");
            requireText(provider, "provider");
            requireText(model, "model");
            requireText(workingDirectory, "workingDirectory");
            requireText(prompt, "prompt");
            requireNonNull(toolEndpoint, "toolEndpoint is null");
            Path path = Path.of(workingDirectory);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalArgumentException(
                        "workingDirectory must be an absolute normalized path");
            }
            if (credentialAccount != null && credentialAccount.isBlank()) {
                throw new IllegalArgumentException(
                        "credentialAccount must not be blank");
            }
            if (transport == AgentTurnProviderSession.Transport.CLI
                    && credentialAccount != null) {
                throw new IllegalArgumentException(
                        "CLI provider credentials are managed outside ByteQuay");
            }
            if (reasoningEffort != null && reasoningEffort.isBlank()) {
                throw new IllegalArgumentException("reasoningEffort must not be blank");
            }
            if (systemPrompt != null && systemPrompt.isBlank()) {
                throw new IllegalArgumentException("systemPrompt must not be blank");
            }
        }
    }

    public record RawResult(
            int schemaVersion,
            String turnId,
            DispatchTicket.OwnerKind ownerKind,
            String purpose,
            AgentTurnProviderSession.Transport transport,
            String provider,
            String providerSessionId,
            String finalText,
            long inputTokens,
            long outputTokens,
            long costUsdMilli,
            Long processPid,
            Disposition disposition,
            String error)
    {
        public RawResult
        {
            if (schemaVersion != PAYLOAD_VERSION) {
                throw new IllegalArgumentException("unsupported raw result version");
            }
            requireText(turnId, "turnId");
            requireNonNull(ownerKind, "ownerKind is null");
            requireNonNull(disposition, "disposition is null");
            if (finalText == null) {
                finalText = "";
            }
            if (inputTokens < 0 || outputTokens < 0 || costUsdMilli < 0) {
                throw new IllegalArgumentException("raw result usage must be non-negative");
            }
        }
    }

    public record Evidence(
            int schemaVersion,
            Disposition disposition,
            String launchInputDigest,
            AgentTurnProviderSession.WriterFence writerFence,
            String detail)
    {
        public Evidence
        {
            if (schemaVersion != PAYLOAD_VERSION) {
                throw new IllegalArgumentException("unsupported evidence version");
            }
            requireNonNull(disposition, "disposition is null");
            if (launchInputDigest != null && launchInputDigest.length() != 64) {
                throw new IllegalArgumentException("launchInputDigest must be SHA-256");
            }
        }
    }

    private record ProviderRun(
            AgentTurnProviderSession.Result result,
            AgentTurnProviderSession.WriterFence writerFence)
    {
        private ProviderRun
        {
            requireNonNull(result, "result is null");
        }
    }

    private static final class ProviderRunException
            extends RuntimeException
    {
        private final Exception failure;

        private ProviderRunException(Exception failure)
        {
            super(requireNonNull(failure, "failure is null"));
            this.failure = failure;
        }

        private Exception failure()
        {
            return failure;
        }
    }

    public enum Disposition
    {
        PROVIDER_SUCCEEDED,
        PROVIDER_FAILED,
        PROVIDER_CANCELED,
        OWNER_NOT_FOUND,
        STALE_FENCE,
        INVALID_LAUNCH_INPUT,
        RECONCILIATION_REQUIRED
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
