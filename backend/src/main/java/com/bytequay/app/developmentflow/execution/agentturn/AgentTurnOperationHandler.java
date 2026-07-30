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
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.agents.ToolExposurePolicy;
import com.bytequay.app.service.agents.ToolExposurePolicy.V2Profile;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.skills.RoleDefinition;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.tools.PermissionResolver;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
    public static final String TASK_OUTCOME_SUMMARY_OPERATION_KIND =
            "GENERATE_TASK_OUTCOME_SUMMARY";

    private static final int PAYLOAD_VERSION = 1;

    private final Store store;
    private final AgentTurnProviderSession provider;
    private final WorktreeWriterLeaseManager writerLeases;
    private final CodeFingerprints fingerprints;
    private final GitRunner git;
    private final ActiveAgentContextRegistry activeContexts;
    private final ToolExposurePolicy tools;
    private final ObjectMapper mapper;
    private final ObjectReader launchReader;

    public AgentTurnOperationHandler(
            Store store,
            AgentTurnProviderSession provider,
            WorktreeWriterLeaseManager writerLeases,
            CodeFingerprints fingerprints,
            GitRunner git,
            ActiveAgentContextRegistry activeContexts,
            ToolExposurePolicy tools,
            ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.writerLeases = requireNonNull(writerLeases, "writerLeases is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.git = requireNonNull(git, "git is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
        this.tools = requireNonNull(tools, "tools is null");
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
        Set<CapacityManager.CapacityLane> requiredLanes =
                completionSummary(turn) || terminalTaskBrainConversation(turn)
                ? Set.of(CapacityManager.CapacityLane.REVIEW, requiredLane)
                : Set.of(requiredLane);
        if (!envelope.capacityRequest().lanes().equals(requiredLanes)) {
            return failure(envelope, turn, Disposition.INVALID_LAUNCH_INPUT,
                    input.transport() + " Agent Turn requires exact capacity lanes "
                            + requiredLanes);
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
                input.images(),
                endpoint,
                access,
                null,
                input.resumeSessionId(),
                input.fallbackPrompt(),
                input.priorCumulativeInputTokens(),
                input.priorCumulativeOutputTokens());
        Observer observer = new Observer(context);
        String agentKey = mcpAgentKey(
                turn.ownerKind(), turn.turnId(), turn.operationId());
        activeContexts.put(
                turn.trunkId(), agentKey, runtimeContext(turn), runningScope(turn),
                new ActiveAgentContextRegistry.TypedOwner(
                        turn.ownerKind(), turn.turnId(), turn.operationId()));
        try {
            try (AgentTurnProviderSession.Session session = provider.open(request, observer)) {
                context.onCancellation(session::cancel);
                if (!activeContexts.attachStop(
                        turn.trunkId(), agentKey, session::cancel)) {
                    throw new IllegalStateException(
                            "typed Agent Turn provider stop hook was not attached");
                }
                ProviderRun run = writerLease == null
                        ? new ProviderRun(session.startAndAwait(null), null, null)
                        : runWithWriterFence(context, writerLease, session, turn);
                AgentTurnProviderSession.Result result = run.result();
                context.recordUsage(
                        result.inputTokens(), result.outputTokens(), result.costUsdMilli());
                Optional<String> stopReason = activeContexts.stopReason(
                        turn.trunkId(), agentKey);
                if (stopReason.isPresent()
                        && stopReason.orElseThrow().startsWith("USER_WAIT:")) {
                    return userWait(
                            envelope, turn, input, run.writerFence(),
                            userWaitRef(stopReason.orElseThrow()));
                }
                if (result.completion() == AgentTurnProviderSession.Completion.CANCELED) {
                    String error = result.error() == null
                            ? "provider session canceled" : result.error();
                    return canceled(envelope, turn, input, run.writerFence(),
                            error);
                }
                return providerResult(envelope, turn, input, run);
            }
        }
        finally {
            activeContexts.remove(turn.trunkId(), agentKey);
        }
    }

    public static String mcpAgentKey(
            DispatchTicket.OwnerKind ownerKind,
            String turnId,
            String operationId)
    {
        requireNonNull(ownerKind, "ownerKind is null");
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        if (ownerKind != DispatchTicket.OwnerKind.TASK_TURN
                && ownerKind != DispatchTicket.OwnerKind.STAGE_TURN) {
            throw new IllegalArgumentException("typed agent MCP owner is unsupported");
        }
        return "v2-" + ownerKind.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + ":" + turnId + ":" + operationId;
    }

    private ResolvedAgentContext runtimeContext(ExactTurn turn)
    {
        ByteQuayRole role = turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN
                ? ByteQuayRole.BRAIN : ByteQuayRole.TASK;
        StageType stageType = stageType(turn.stageKind());
        RoleDefinition definition = RoleRegistry.definition(role);
        return new ResolvedAgentContext(
                definition.role(), definition.version(), definition.permissionRole(),
                stageType, definition.capabilities(), List.of(), List.of(),
                definition.resources(), runtimeTools(turn));
    }

    private Set<String> runtimeTools(ExactTurn turn)
    {
        if (completionSummary(turn)) {
            return tools.completionSummaryTools();
        }
        if (automaticTaskBrainReview(turn)) {
            return tools.automaticTaskBrainReviewTools();
        }
        return tools.v2Tools(v2Profile(turn));
    }

    private static V2Profile v2Profile(ExactTurn turn)
    {
        if (turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN) {
            if ("PLAN_DRAFT".equals(turn.purpose())
                    || "PLAN_SELF_REVIEW".equals(turn.purpose())) {
                return V2Profile.PLAN_PROTOCOL;
            }
            return V2Profile.TASK_BRAIN_READ_ONLY;
        }
        return switch (turn.stageKind()) {
            case "PLAN" -> V2Profile.PLAN_PROTOCOL;
            case "LOCAL_DEVELOPMENT" -> V2Profile.LOCAL_DEVELOPMENT;
            case "REMOTE_DEVELOPMENT" -> V2Profile.REMOTE_DEVELOPMENT;
            case "CLEANUP" -> V2Profile.CLEANUP;
            default -> throw new IllegalArgumentException(
                    "unknown V2 Stage tool profile: " + turn.stageKind());
        };
    }

    private static PermissionResolver.RunningScope runningScope(ExactTurn turn)
    {
        ThreadScope scope = turn.ownerKind() == DispatchTicket.OwnerKind.STAGE_TURN
                ? ThreadScope.STAGE : ThreadScope.TASK;
        return new PermissionResolver.RunningScope(
                scope, turn.taskId(), turn.stageId(), turn.turnId());
    }

    private static StageType stageType(String kind)
    {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "PLAN" -> StageType.PLAN_STAGE;
            case "LOCAL_DEVELOPMENT" -> StageType.DEVELOPMENT_STAGE;
            case "REMOTE_DEVELOPMENT" -> StageType.REMOTE_DEVELOPMENT_STAGE;
            case "CLEANUP" -> StageType.CLEANUP_STAGE;
            default -> throw new IllegalArgumentException("unknown V2 Stage kind: " + kind);
        };
    }

    private ProviderRun runWithWriterFence(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease writerLease,
            AgentTurnProviderSession.Session session,
            ExactTurn turn)
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
                    AgentTurnProviderSession.Result result =
                            session.startAndAwait(providerFence);
                    OutputCodeSubject output = null;
                    if (result.completion()
                            == AgentTurnProviderSession.Completion.SUCCEEDED) {
                        checkpointProviderChanges(turn);
                        output = observeOutput(turn);
                    }
                    return new ProviderRun(result, providerFence, output);
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

    private void checkpointProviderChanges(ExactTurn turn)
    {
        Path worktree = Path.of(turn.worktreePath());
        try {
            if (!git.hasUncommittedChanges(worktree)) {
                return;
            }
            git.stageAll(worktree, List.of(WorktreeService.HOOK_DIR_REL));
            git.commit(worktree, checkpointMessage(turn));
        }
        catch (IOException e) {
            throw new IllegalStateException(
                    "could not checkpoint Agent Turn output", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Agent Turn output checkpoint was interrupted", e);
        }
    }

    private static String checkpointMessage(ExactTurn turn)
    {
        return "ByteQuay checkpoint: " + turn.purpose();
    }

    private OutputCodeSubject observeOutput(ExactTurn turn)
    {
        Path worktree = Path.of(turn.worktreePath());
        try {
            String headSha = git.headSha(worktree);
            boolean clean = !git.hasUncommittedChanges(worktree);
            String mergeBaseSha = turn.expectedBaseSha() == null
                    ? null
                    : git.mergeBase(worktree, headSha, turn.expectedBaseSha())
                            .orElse(null);
            return new OutputCodeSubject(
                    fingerprints.fingerprint(worktree), headSha,
                    turn.expectedBaseSha(), clean, mergeBaseSha);
        }
        catch (IOException e) {
            throw new IllegalStateException("could not capture Agent Turn output", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Agent Turn output capture was interrupted", e);
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
                result.error(),
                null,
                run.outputCodeSubject(),
                result.cumulativeInputTokens(),
                result.cumulativeOutputTokens());
        Evidence evidence = new Evidence(
                PAYLOAD_VERSION,
                disposition,
                digest(turn.launchInput()),
                run.writerFence(),
                result.error(),
                run.outputCodeSubject());
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

    private DispatchTicket.DispatchResult userWait(
            DispatchTicket.DispatchEnvelope envelope,
            ExactTurn turn,
            LaunchInput input,
            AgentTurnProviderSession.WriterFence writerFence,
            UserWaitRef wait)
    {
        RawResult payload = new RawResult(
                PAYLOAD_VERSION, turn.turnId(), turn.ownerKind(), turn.purpose(),
                input.transport(), input.provider(), null, "", 0, 0, 0, null,
                Disposition.USER_WAIT, null, wait, null);
        Evidence evidence = new Evidence(
                PAYLOAD_VERSION, Disposition.USER_WAIT, digest(turn.launchInput()),
                writerFence, wait.kind() + ":" + wait.id());
        return new DispatchTicket.DispatchResult(
                envelope.fence(), SUCCEEDED, json(payload), json(evidence), null);
    }

    public static UserWaitRef userWaitRef(String reason)
    {
        requireText(reason, "reason");
        String[] parts = reason.split(":", 3);
        if (parts.length != 3 || !parts[0].equals("USER_WAIT")
                || (!parts[1].equals("QUESTION")
                    && !parts[1].equals("PERMISSION"))
                || parts[2].isBlank()) {
            throw new IllegalArgumentException("invalid typed user-wait reason");
        }
        return new UserWaitRef(parts[1], parts[2]);
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

        default Optional<McpContext> authorizeMcp(
                DispatchTicket.OwnerKind ownerKind,
                String turnId,
                String operationId,
                Instant now)
        {
            return Optional.empty();
        }
    }

    public record McpContext(
            DispatchTicket.OwnerKind ownerKind,
            String trunkId,
            String workspaceId,
            String taskId,
            long taskEpoch,
            String stageId,
            Long stageGeneration,
            String purpose)
    {
        public McpContext
        {
            requireNonNull(ownerKind, "ownerKind is null");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(taskId, "taskId");
            requireText(purpose, "purpose");
            if (taskEpoch < 1 || (stageId == null) != (stageGeneration == null)) {
                throw new IllegalArgumentException("typed MCP scope is invalid");
            }
        }
    }

    public record ExactTurn(
            DispatchTicket.OwnerKind ownerKind,
            String turnId,
            String trunkId,
            String workspaceId,
            String taskId,
            long taskEpoch,
            String stageId,
            Long stageGeneration,
            String stageKind,
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
        /** Compatibility shape retained for focused handler tests. */
        public ExactTurn(
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
            this(ownerKind, turnId, "trunk-1", "workspace-1", taskId,
                    taskEpoch, stageId, stageGeneration,
                    stageId == null ? null : "LOCAL_DEVELOPMENT",
                    purpose, turnStatus, operationId, semanticAttempt,
                    expectedCodeFingerprint, expectedHeadSha, expectedBaseSha,
                    launchInput, worktreePath, taskLifecycle, currentStageId,
                    currentStageGeneration, stageCompleted, currentCodeFingerprint,
                    currentHeadSha, currentBaseSha, brainProvider, brainModel);
        }

        public ExactTurn
        {
            requireNonNull(ownerKind, "ownerKind is null");
            requireText(turnId, "turnId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
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
            if ((stageId == null) != (stageKind == null)) {
                throw new IllegalArgumentException("Stage kind must match Stage identity");
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
            boolean completionSummary = completionSummary(this);
            boolean terminalConversation = terminalTaskBrainConversation(this);
            String expectedKind = stageTurn ? STAGE_OPERATION_KIND
                    : completionSummary ? TASK_OUTCOME_SUMMARY_OPERATION_KIND
                    : TASK_OPERATION_KIND;
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
                    || capacity.exclusiveTask()
                        != !(completionSummary || terminalConversation)
                    || capacity.trunkControl()
                    || stageTurn != capacity.writerRequired()) {
                return "Agent Turn capacity scope or writer mode is invalid";
            }
            boolean lifecycleAllows = completionSummary || terminalConversation
                    ? Set.of("COMPLETED", "CANCELED", "REMOTE_CLOSED")
                            .contains(taskLifecycle)
                    : "ACTIVE".equals(taskLifecycle);
            if (!lifecycleAllows
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

    private static boolean completionSummary(ExactTurn turn)
    {
        return turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN
                && "TASK_COMPLETION_SUMMARY".equals(turn.purpose());
    }

    private static boolean automaticTaskBrainReview(ExactTurn turn)
    {
        return turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN
                && Set.of(
                        "REMOTE_CI_BRAIN_REVIEW",
                        "BRANCH_SYNC_BRAIN_REVIEW").contains(turn.purpose());
    }

    private static boolean terminalTaskBrainConversation(ExactTurn turn)
    {
        return taskBrainConversation(turn)
                && turn.stageId() == null
                && Set.of("COMPLETED", "CANCELED", "REMOTE_CLOSED")
                        .contains(turn.taskLifecycle());
    }

    private static boolean taskBrainConversation(ExactTurn turn)
    {
        return turn.ownerKind() == DispatchTicket.OwnerKind.TASK_TURN
                && "TASK_BRAIN_CONVERSATION".equals(turn.purpose());
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
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
                    List<AgentTurnProviderSession.ImageAttachment> images,
            AgentTurnProviderSession.OwnerToolEndpoint toolEndpoint,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    String resumeSessionId,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    String fallbackPrompt,
            long priorCumulativeInputTokens,
            long priorCumulativeOutputTokens)
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
            images = images == null ? List.of() : List.copyOf(images);
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
            if (resumeSessionId != null && resumeSessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "resumeSessionId must not be blank");
            }
            if (fallbackPrompt != null && fallbackPrompt.isBlank()) {
                throw new IllegalArgumentException(
                        "fallbackPrompt must not be blank");
            }
            if ((resumeSessionId == null) != (fallbackPrompt == null)) {
                throw new IllegalArgumentException(
                        "resumeSessionId and fallbackPrompt must be supplied together");
            }
            if (resumeSessionId != null
                    && transport != AgentTurnProviderSession.Transport.CLI) {
                throw new IllegalArgumentException(
                        "only CLI launch input may resume a provider session");
            }
            if (priorCumulativeInputTokens < 0
                    || priorCumulativeOutputTokens < 0) {
                throw new IllegalArgumentException(
                        "prior cumulative usage must be non-negative");
            }
            if (resumeSessionId == null
                    && (priorCumulativeInputTokens != 0
                    || priorCumulativeOutputTokens != 0)) {
                throw new IllegalArgumentException(
                        "prior cumulative usage requires a resumed session");
            }
        }

        public LaunchInput(
                int schemaVersion,
                AgentTurnProviderSession.Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                String workingDirectory,
                String systemPrompt,
                String prompt,
                List<AgentTurnProviderSession.ImageAttachment> images,
                AgentTurnProviderSession.OwnerToolEndpoint toolEndpoint)
        {
            this(schemaVersion, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, null, null, 0, 0);
        }

        public LaunchInput(
                int schemaVersion,
                AgentTurnProviderSession.Transport transport,
                String provider,
                String credentialAccount,
                String model,
                String reasoningEffort,
                String workingDirectory,
                String systemPrompt,
                String prompt,
                List<AgentTurnProviderSession.ImageAttachment> images,
                AgentTurnProviderSession.OwnerToolEndpoint toolEndpoint,
                String resumeSessionId,
                String fallbackPrompt)
        {
            this(schemaVersion, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    images, toolEndpoint, resumeSessionId, fallbackPrompt, 0, 0);
        }

        public LaunchInput(
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
            this(schemaVersion, transport, provider, credentialAccount, model,
                    reasoningEffort, workingDirectory, systemPrompt, prompt,
                    List.of(), toolEndpoint, null, null, 0, 0);
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
            String error,
            UserWaitRef userWait,
            OutputCodeSubject outputCodeSubject,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    Long providerCumulativeInputTokens,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    Long providerCumulativeOutputTokens)
    {
        public RawResult(
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
            this(schemaVersion, turnId, ownerKind, purpose, transport, provider,
                    providerSessionId, finalText, inputTokens, outputTokens,
                    costUsdMilli, processPid, disposition, error, null, null,
                    null, null);
        }

        public RawResult(
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
                String error,
                UserWaitRef userWait)
        {
            this(schemaVersion, turnId, ownerKind, purpose, transport, provider,
                    providerSessionId, finalText, inputTokens, outputTokens,
                    costUsdMilli, processPid, disposition, error, userWait, null,
                    null, null);
        }

        public RawResult(
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
                String error,
                UserWaitRef userWait,
                OutputCodeSubject outputCodeSubject)
        {
            this(schemaVersion, turnId, ownerKind, purpose, transport, provider,
                    providerSessionId, finalText, inputTokens, outputTokens,
                    costUsdMilli, processPid, disposition, error, userWait,
                    outputCodeSubject, null, null);
        }

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
            if ((providerCumulativeInputTokens == null)
                    != (providerCumulativeOutputTokens == null)) {
                throw new IllegalArgumentException(
                        "provider cumulative usage must be supplied together");
            }
            if (providerCumulativeInputTokens != null
                    && (providerCumulativeInputTokens < inputTokens
                    || providerCumulativeOutputTokens < outputTokens)) {
                throw new IllegalArgumentException(
                        "provider cumulative usage must include raw Turn usage");
            }
            if ((disposition == Disposition.USER_WAIT) != (userWait != null)) {
                throw new IllegalArgumentException(
                        "USER_WAIT disposition and reference disagree");
            }
            if ((ownerKind == DispatchTicket.OwnerKind.STAGE_TURN
                    && disposition == Disposition.PROVIDER_SUCCEEDED)
                    != (outputCodeSubject != null)) {
                throw new IllegalArgumentException(
                        "successful StageTurn and output code subject disagree");
            }
        }
    }

    /** Immutable worktree facts captured before the writer CapacityLease ends. */
    public record OutputCodeSubject(
            String codeFingerprint,
            String headSha,
            String baseSha,
            boolean clean,
            String mergeBaseSha)
    {
        public OutputCodeSubject
        {
            requireText(codeFingerprint, "codeFingerprint");
            requireText(headSha, "headSha");
            if (baseSha != null && baseSha.isBlank()) {
                throw new IllegalArgumentException("baseSha is blank");
            }
            if (mergeBaseSha != null && mergeBaseSha.isBlank()) {
                throw new IllegalArgumentException("mergeBaseSha is blank");
            }
        }
    }

    public record UserWaitRef(String kind, String id)
    {
        public UserWaitRef
        {
            requireText(kind, "kind");
            requireText(id, "id");
            if (!kind.equals("QUESTION") && !kind.equals("PERMISSION")) {
                throw new IllegalArgumentException("unsupported user wait kind: " + kind);
            }
        }
    }

    public record Evidence(
            int schemaVersion,
            Disposition disposition,
            String launchInputDigest,
            AgentTurnProviderSession.WriterFence writerFence,
            String detail,
            OutputCodeSubject outputCodeSubject)
    {
        public Evidence(
                int schemaVersion,
                Disposition disposition,
                String launchInputDigest,
                AgentTurnProviderSession.WriterFence writerFence,
                String detail)
        {
            this(schemaVersion, disposition, launchInputDigest, writerFence,
                    detail, null);
        }

        public Evidence
        {
            if (schemaVersion != PAYLOAD_VERSION) {
                throw new IllegalArgumentException("unsupported evidence version");
            }
            requireNonNull(disposition, "disposition is null");
            if (launchInputDigest != null && launchInputDigest.length() != 64) {
                throw new IllegalArgumentException("launchInputDigest must be SHA-256");
            }
            if (outputCodeSubject != null
                    && (disposition != Disposition.PROVIDER_SUCCEEDED
                    || writerFence == null)) {
                throw new IllegalArgumentException(
                        "output code subject requires successful writer evidence");
            }
        }
    }

    private record ProviderRun(
            AgentTurnProviderSession.Result result,
            AgentTurnProviderSession.WriterFence writerFence,
            OutputCodeSubject outputCodeSubject)
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
        USER_WAIT,
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
