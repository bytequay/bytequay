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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.AgentBrainResult;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.OutputCodeSubject;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Result;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainFixTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainProtocolRetry;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainProtocolRetryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainProtocolRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainResultRepair;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainResultRepairSource;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainReviewRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainTurnContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainTurnDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.CodeSubject;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.DevReport;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.DevelopmentReport;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.InitialContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.InitialImplementationReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.InitialTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.StageTurnContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.StageTurnDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.StageTurnRetry;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.StageTurnRetryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.StageTurnRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.BaseSyncTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.TurnContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Attachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.skills.SimplifyPrompt;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Local-owned initial implementation, StageTurn delivery, and validation arm. */
public final class LocalDevelopmentRuntimeCoordinator
{
    public static final String TURN_CALLBACK = "STAGE_TURN_RESULT";

    private static final String ACTOR = "v2-local-runtime";
    private static final String RAW_JSON_OBJECT_BOUNDARY =
            "Your response must be exactly one raw JSON object: its first "
                    + "non-whitespace character must be '{' and its last "
                    + "non-whitespace character must be '}'. Do not wrap it in "
                    + "Markdown fences or add prose before or after it.";
    private static final String RETRY_INSTRUCTION =
            "Retry this exact Local Development operation from its complete "
                    + "durable context. Finish the assigned work, run required "
                    + "validation, and report it with record_development_result.";
    private static final String BRAIN_RETRY_INSTRUCTION =
            "Retry the exact Development Brain review from this durable context. "
                    + "Any earlier instruction to submit through an owner-scoped "
                    + "tool is obsolete. Do not submit the verdict through a tool. "
                    + "Return only strict JSON with exactly this shape: "
                    + "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                    + "\"summary\":\"string\",\"findings\":[]}. Set verdict to "
                    + "APPROVED or CHANGES_REQUESTED. APPROVED requires an empty "
                    + "findings array; CHANGES_REQUESTED requires one or more "
                    + "non-blank finding strings. " + RAW_JSON_OBJECT_BOUNDARY;
    /** How a Brain review reports. The tool's schema is the contract; this
     *  only has to name it and say when to call it. */
    private static final String BRAIN_VERDICT_INSTRUCTION =
            "Report your conclusion by calling the record_development_verdict "
                    + "tool as your last act. That call is how the review is "
                    + "accepted: a review that ends without it is discarded. Set "
                    + "verdict to APPROVED or CHANGES_REQUESTED — APPROVED takes "
                    + "an empty findings list, CHANGES_REQUESTED takes one entry "
                    + "per change you want. If the call comes back rejected, read "
                    + "the reason and call it again. Your final message is not "
                    + "read; put the verdict in the tool call, not in prose.";
    private static final String BRAIN_RESULT_SHAPE =
            "{\"schemaVersion\":1,\"verdict\":\"APPROVED\","
                    + "\"summary\":\"string\",\"findings\":[]}";
    /** Request kind of the cleanup Turn a large Development Turn triggers. */
    private static final String SIMPLIFY_KIND = "SIMPLIFY";
    /** {@code stage_turn.purpose} for implementation-family Turns; a cleanup
     *  Turn edits code at IMPLEMENTING, so it carries the same purpose. */
    private static final String IMPLEMENT_PURPOSE = "IMPLEMENT_LOCAL_PLAN";
    /**
     * Added-line floor for triggering a cleanup Turn. Measured per Turn and on
     * additions only, which is what makes this self-limiting: a Turn under the
     * floor never fires, and a cleanup Turn's own output is net-deletion so it
     * scores near zero and cannot re-trigger itself.
     */
    // ponytail: flat threshold — a 400-line Turn that is mostly a generated
    //   migration has nothing to simplify, a 40-line hand-rolled Optional
    //   does. Upgrade path: weight by file type, or drop the floor entirely
    //   if the Turn turns out cheap.
    static final int MIN_ADDED_LINES = 200;
    /**
     * How a Local Development Turn reports its result. The tool's schema is the
     * contract, so this only names the tool and says when to call it — every
     * request kind gets the same sentence instead of its own hand-maintained
     * field list that could drift from what the decoder required.
     */
    static final String DEVELOPMENT_RESULT_INSTRUCTION =
            "Report your result by calling the record_development_result tool as "
                    + "your last act. That call is how the work is accepted: a "
                    + "Turn that ends without it is discarded. If the call comes "
                    + "back rejected, read the reason and call it again with the "
                    + "correction — the Turn is still yours. Your final message "
                    + "is not read; put the result in the tool call, not in prose.";
    private static final String SIMPLIFY_INSTRUCTION =
            "If you changed nothing, say so in implementedIntent and leave "
                    + "commitSummary blank. " + DEVELOPMENT_RESULT_INSTRUCTION;
    private final TaskCommandExecutor commands;
    private final LocalDevelopmentStageManager local;
    private final TaskManager tasks;
    private final SqliteLocalDevelopmentRuntimeStore store;
    private final PRService prs;
    private final ObjectMapper json;
    private final ObjectReader workModelReader;
    private final ObjectReader validationResultReader;
    /** Names this Turn in every brain-protocol failure message. */
    private static final String BRAIN_LABEL = "Development Brain";
    private final Clock clock;
    private final int serverPort;
    private SqliteStageSteeringStore steering;
    private V2LocalReviewControl localReview;
    private ReasoningEffortService reasoningEfforts;

    public LocalDevelopmentRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalDevelopmentStageManager local,
            SqliteLocalDevelopmentRuntimeStore store,
            PRService prs,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.local = requireNonNull(local, "local is null");
        this.store = requireNonNull(store, "store is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.json = requireNonNull(json, "json is null");
        this.workModelReader = json.readerFor(WorkModel.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validationResultReader = json.readerFor(
                        LocalValidationOperationHandler.ValidationResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    @Autowired
    void setSteeringStore(SqliteStageSteeringStore steering)
    {
        this.steering = requireNonNull(steering, "steering is null");
    }

    @Autowired
    void setReasoningEfforts(ReasoningEffortService reasoningEfforts)
    {
        this.reasoningEfforts = requireNonNull(
                reasoningEfforts, "reasoningEfforts is null");
    }

    /** Materializes one already-durable steering request under its Task stripe. */
    public SteeringAdmission admitSteeringInCommand(Request request, long stageVersion)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        SqliteStageSteeringStore ownerStore = requireNonNull(
                steering, "Stage steering store is not configured");
        LocalContext context = ownerStore.requireLocalContext(request, stageVersion);
        if (context.checkpoint() == StageCheckpoint.LOCAL_REVIEW
                && ownerStore.hasLiveLocalPublishBaseSync(request)) {
            throw new IllegalStateException(
                    "Local review steering cannot race active publish base sync");
        }
        String localRequestId = id("local-steering-request", request.id());
        String localCommandId = id("persist-local-steering", request.id());
        String turnId = id("local-steering-turn", request.id());
        String operationId = id("local-steering-operation", request.id());
        String ticketId = id("local-steering-ticket", request.id());
        List<Attachment> attachments = ownerStore.attachments(request.id());
        String prompt = steeringPrompt(request, attachments);
        WorkModel workModel = decodeWorkModel(context.workModelSnapshot());
        workModel = stageEffort(context.trunkId(), context.taskId(),
                context.stageId(), workModel);
        int laneMask = workModel.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(), workModel,
                context.worktreePath(), turnId, operationId, prompt);
        StageCliContinuity.freezeImages(json, launch, attachments);
        applyCliContinuity(
                launch, request, context, workModel, prompt, ownerStore);
        LocalTurn turn = new LocalTurn(
                localRequestId, localCommandId, turnId, operationId, ticketId,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                1, context.codeFingerprint(), context.headSha(), context.baseSha(),
                workModel.kind().name(), laneMask, write(launch), digest(prompt),
                "user", clock.instant());
        ownerStore.insertLocalTurn(turn);
        StageManager.Command command = new StageManager.Command(
                id("admit-local-steering", request.id()), ACTOR,
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.stageVersion());
        CommandResult<StageManager.State> admitted = switch (context.checkpoint()) {
            case IMPLEMENTING -> local.requestImplementationInCommand(
                    command, turn.fence(), localRequestId);
            case ADDRESSING_BRAIN_FINDINGS -> local.requestBrainFixInCommand(
                    command, turn.fence(), localRequestId);
            case ADDRESSING_LOCAL_FEEDBACK -> local.requestLocalFeedbackFixInCommand(
                    command, turn.fence(), localRequestId);
            case LOCAL_REVIEW -> local.admitSteeringFromReviewInCommand(
                    command, turn.fence(), localRequestId);
            default -> throw new IllegalStateException(
                    "Local Stage is not ready for steering at " + context.checkpoint());
        };
        if (admitted.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Current Local steering was superseded");
        }
        return new SteeringAdmission(turnId, operationId, ticketId);
    }

    /** Replaces the exact settled Local StageTurn that produced a user wait. */
    public SteeringAdmission admitUserWaitContinuationInCommand(
            Request request, long stageVersion)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        SqliteStageSteeringStore ownerStore = requireNonNull(
                steering, "Stage steering store is not configured");
        SqliteStageSteeringStore.Predecessor predecessor = requireNonNull(
                request.predecessor(), "user-wait predecessor is missing");
        LocalContext context = ownerStore.requireLocalContext(request, stageVersion);
        String localRequestId = id("local-wait-request", request.id());
        String localCommandId = id("persist-local-wait", request.id());
        String turnId = id("local-wait-turn", request.id());
        String operationId = id("local-wait-operation", request.id());
        String ticketId = id("local-wait-ticket", request.id());
        List<Attachment> attachments = ownerStore.attachments(request.id());
        String prompt = steeringPrompt(request, attachments);
        WorkModel workModel = decodeWorkModel(context.workModelSnapshot());
        workModel = stageEffort(context.trunkId(), context.taskId(),
                context.stageId(), workModel);
        int laneMask = workModel.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(), workModel,
                context.worktreePath(), turnId, operationId, prompt);
        StageCliContinuity.freezeImages(json, launch, attachments);
        applyCliContinuity(
                launch, request, context, workModel, prompt, ownerStore);
        LocalTurn turn = new LocalTurn(
                localRequestId, localCommandId, turnId, operationId, ticketId,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                predecessor.attempt() + 1, context.codeFingerprint(),
                context.headSha(), context.baseSha(), workModel.kind().name(),
                laneMask, write(launch), digest(prompt), "user", clock.instant());
        ownerStore.insertLocalContinuationTurn(turn, predecessor);
        ResultFence completed = new ResultFence(
                request.taskEpoch(), request.stageId(), request.stageGeneration(),
                predecessor.operationId(), predecessor.attempt(),
                predecessor.codeFingerprint(), predecessor.headSha(),
                predecessor.baseSha());
        StageManager.ResultCommand command = new StageManager.ResultCommand(
                id("continue-local-user-wait", request.id()), ACTOR,
                request.taskId(), completed);
        CommandResult<StageManager.State> admitted = switch (context.checkpoint()) {
            case IMPLEMENTING -> local.replaceImplementationTurnInCommand(
                    command, turn.fence(), localRequestId);
            case ADDRESSING_BRAIN_FINDINGS -> local.replaceBrainFixTurnInCommand(
                    command, turn.fence(), localRequestId);
            case ADDRESSING_LOCAL_FEEDBACK ->
                    local.replaceLocalFeedbackTurnInCommand(
                            command, turn.fence(), localRequestId);
            default -> throw new IllegalStateException(
                    "Local Stage cannot continue a user wait at "
                            + context.checkpoint());
        };
        if (admitted.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Current Local user-wait continuation was superseded");
        }
        return new SteeringAdmission(turnId, operationId, ticketId);
    }

    /** Replaces the exact Local Turn whose immutable success could not decode. */
    public SteeringAdmission replaceMalformedResultPendingInCommand(
            Request request, long stageVersion)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        SqliteStageSteeringStore ownerStore = requireNonNull(
                steering, "Stage steering store is not configured");
        SqliteStageSteeringStore.Predecessor predecessor = requireNonNull(
                request.predecessor(), "malformed-result predecessor is missing");
        LocalContext context = ownerStore.requireLocalContext(request, stageVersion);
        String localRequestId = id("local-malformed-result-request", request.id());
        String localCommandId = id("persist-local-malformed-result", request.id());
        String turnId = id("local-malformed-result-turn", request.id());
        String operationId = id("local-malformed-result-operation", request.id());
        String ticketId = id("local-malformed-result-ticket", request.id());
        ResultFence completed = new ResultFence(
                request.taskEpoch(), request.stageId(), request.stageGeneration(),
                predecessor.operationId(), predecessor.attempt(),
                predecessor.codeFingerprint(), predecessor.headSha(),
                predecessor.baseSha());
        StageTurnContext frozen = store.requireStageTurnContext(
                predecessor.ownerId(), predecessor.operationId());
        if (!frozen.fence().equals(completed)
                || !frozen.ticketId().equals(predecessor.ticketId())) {
            throw new IllegalStateException(
                    "Malformed Local result context differs from its predecessor");
        }
        ObjectNode launch;
        try {
            JsonNode stored = json.readTree(frozen.launchInput());
            if (!(stored instanceof ObjectNode retryLaunch)) {
                throw new IllegalStateException(
                        "Stored malformed Local StageTurn launch is not an object");
            }
            String priorPrompt = retryLaunch.hasNonNull("fallbackPrompt")
                    ? required(retryLaunch.path("fallbackPrompt").asText(),
                            "fallbackPrompt")
                    : required(retryLaunch.path("prompt").asText(), "prompt");
            // Design 3.35: a concise rejection brief, not the inlined trace.
            // The successor shares the predecessor's worktree, so Git already
            // describes the work. Inlining the trace cost ~138KB, and since a
            // replacement reuses this prompt as its own prior prompt, a second
            // failure embedded the trace twice.
            StringBuilder retryPrompt = new StringBuilder(priorPrompt);
            retryPrompt.append("\n\nYour previous attempt on this Stage was ")
                    .append("rejected before it could be accepted.\n")
                    .append("Reason: ")
                    .append(DispatchTicket.resultProtocolFailureDetail(
                            required(frozen.ticketLastError(), "ticketLastError")))
                    .append("\nRejected Turn: ").append(predecessor.ownerId())
                    .append("\nAny edits it made are already in this worktree; "
                            + "inspect them with git rather than redoing the work.")
                    .append("\nRead that Turn's full transcript with "
                            + "read_dev_conversation only if the reason above is "
                            + "not enough.")
                    .append("\n\nRetry instruction:\n")
                    .append(RETRY_INSTRUCTION);
            retryLaunch.put("prompt", retryPrompt.toString());
            retryLaunch.remove(List.of(
                    "resumeSessionId", "fallbackPrompt",
                    "priorCumulativeInputTokens",
                    "priorCumulativeOutputTokens"));
            JsonNode endpointNode = retryLaunch.get("toolEndpoint");
            if (!(endpointNode instanceof ObjectNode endpoint)) {
                throw new IllegalStateException(
                        "Stored malformed Local StageTurn has no tool endpoint");
            }
            endpoint.put("url", "http://127.0.0.1:" + serverPort
                    + "/api/v2/stage-turns/" + turnId
                    + "/operations/" + operationId + "/mcp");
            endpoint.put("ownerKind", "STAGE_TURN");
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            launch = retryLaunch;
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Stored malformed Local StageTurn launch is invalid", e);
        }
        String prompt = required(launch.path("prompt").asText(), "prompt");
        LocalTurn turn = new LocalTurn(
                localRequestId, localCommandId, turnId, operationId, ticketId,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                predecessor.attempt() + 1, context.codeFingerprint(),
                context.headSha(), context.baseSha(), frozen.deliveryLane(),
                frozen.laneMask(), write(launch), digest(prompt), "user",
                clock.instant());
        ownerStore.insertMalformedLocalResultReplacementTurn(turn, request);
        StageManager.ResultCommand command = new StageManager.ResultCommand(
                id("replace-local-malformed-result", request.id()), ACTOR,
                request.taskId(), completed);
        CommandResult<StageManager.State> admitted = switch (context.checkpoint()) {
            case IMPLEMENTING -> local.replaceImplementationTurnInCommand(
                    command, turn.fence(), localRequestId);
            case ADDRESSING_BRAIN_FINDINGS -> local.replaceBrainFixTurnInCommand(
                    command, turn.fence(), localRequestId);
            case ADDRESSING_LOCAL_FEEDBACK ->
                    local.replaceLocalFeedbackTurnInCommand(
                            command, turn.fence(), localRequestId);
            default -> throw new IllegalStateException(
                    "Local Stage cannot replace a malformed result at "
                            + context.checkpoint());
        };
        if (admitted.disposition() != CommandResult.Disposition.APPLIED) {
            throw new IllegalStateException(
                    "Malformed Local result replacement was superseded");
        }
        return new SteeringAdmission(turnId, operationId, ticketId);
    }

    @Autowired(required = false)
    public void setV2LocalReview(V2LocalReviewControl localReview)
    {
        this.localReview = requireNonNull(localReview, "localReview is null");
    }

    public InitialImplementationReceipt startInitialImplementation(
            String taskId, String localStageId, String planApprovalId)
    {
        requireText(taskId, "taskId");
        return commands.execute(taskId, () -> startInitialImplementationInCommand(
                taskId, localStageId, planApprovalId));
    }

    /** Builds the one semantic reconciliation Turn after an exact local rebase result. */
    public BaseSyncTurn createPublishBaseSyncTurnInCommand(
            TurnContext context, Result rebase, Instant requestedAt)
    {
        requireNonNull(context, "context is null");
        requireNonNull(rebase, "rebase is null");
        requireNonNull(requestedAt, "requestedAt is null");
        TaskCommandExecutor.requireCurrent(context.taskId());
        if (rebase.kind()
                    != LocalPublishBaseSyncOperationHandler.Kind.MECHANICAL_REBASE
                || rebase.disposition() != Disposition.REBASED
                    && rebase.disposition() != Disposition.CONFLICT
                || !context.targetBaseSha().equals(rebase.targetBaseSha())
                || rebase.evidence() == null) {
            throw new IllegalArgumentException(
                    "BASE_SYNC Turn requires an exact mechanical rebase result");
        }

        String source = context.episodeId();
        String requestId = id("local-publish-base-sync-request", source);
        String commandId = id("start-local-publish-base-sync", source);
        String turnId = id("local-publish-base-sync-turn", source);
        String operationId = id("local-publish-base-sync-turn-operation", source);
        String ticketId = id("local-publish-base-sync-turn-ticket", source);
        String prompt = publishBaseSyncPrompt(rebase);
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        model = stageEffort(
                context.trunkId(), context.taskId(), context.stageId(), model);
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(), model,
                context.worktreePath(), turnId, operationId, prompt);
        if (steering != null) {
            LocalPublishBaseSyncOperationHandler.Evidence evidence =
                    rebase.evidence();
            StageCliContinuity.applyExact(
                    json, launch, context.predecessorStageTurnId(), model.kind(),
                    prompt, steering, new StageCliContinuity.Fence(
                            context.stageId(), context.stageGeneration(),
                            evidence.sourceCodeFingerprint(),
                            evidence.sourceHeadSha(), evidence.sourceBaseSha(),
                            context.provider(), context.model(),
                            context.worktreePath()));
        }
        return new BaseSyncTurn(
                context.episodeId(), requestId, commandId, turnId, operationId,
                ticketId, context.workspaceId(), context.trunkId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), 1, context.codeFingerprint(),
                context.headSha(), context.baseSha(), context.targetBaseSha(),
                model.kind().name(), laneMask, write(launch), digest(prompt),
                ACTOR, requestedAt);
    }

    /** Called by Plan-to-Local handoff while it still owns the Task stripe. */
    public InitialImplementationReceipt startInitialImplementationInCommand(
            String taskId, String localStageId, String planApprovalId)
    {
        requireText(taskId, "taskId");
        requireText(localStageId, "localStageId");
        requireText(planApprovalId, "planApprovalId");
        TaskCommandExecutor.requireCurrent(taskId);
        InitialImplementationReceipt duplicate = store.findInitialReceipt(
                        taskId, localStageId, planApprovalId)
                .orElse(null);
        if (duplicate != null) {
            return duplicate;
        }

        InitialContext context = store.requireInitialContext(
                taskId, localStageId, planApprovalId);
        String commandId = id("request-local-implementation", planApprovalId);
        String requestId = id("local-implementation-request", planApprovalId);
        String turnId = id("local-implementation-turn", planApprovalId);
        String operationId = id("local-implementation-operation", planApprovalId);
        String ticketId = id("local-implementation-ticket", planApprovalId);
        Instant now = clock.instant();
        String prompt = implementationPrompt(context);
        InitialTurn turn = initialTurn(
                context, requestId, commandId, turnId, operationId, ticketId,
                prompt, now);
        store.insertInitialTurn(turn);
        CommandResult<StageManager.State> requested =
                local.startInitialImplementationInCommand(
                        new StageManager.Command(
                                commandId, ACTOR, taskId, context.taskEpoch(),
                                localStageId, context.stageGeneration(),
                                context.stageVersion()),
                        turn.fence(), turn.turnId());
        if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Initial Local implementation request was superseded");
        }
        InitialImplementationReceipt receipt = new InitialImplementationReceipt(
                taskId, localStageId, planApprovalId, requestId, turnId,
                operationId, ticketId, now);
        store.insertInitialReceipt(receipt);
        return receipt;
    }

    /**
     * Persist the result a Local Development Turn reports through
     * {@code record_development_result}. Called from the tool handler while the
     * subprocess is still alive, so a rejection here reaches the agent as an MCP
     * tool error it can correct in the same session — unlike the old contract,
     * where the result was parsed out of the final message after the process had
     * already exited and only a human could unstick it.
     *
     * <p>Idempotent: an identical re-submission is accepted, a differing one is
     * rejected. The Turn's liveness is proved by the caller — the tool is only
     * reachable from the operation-scoped MCP endpoint of a running Turn, and
     * the active-agent registry denies by default between Turns. A submission
     * from a Turn that goes stale is harmless: delivery reads only its own
     * {@code turnId}, and a stale Turn is rejected as SUPERSEDED regardless.
     * ponytail: no re-authorize-under-transaction like the Plan side, which
     * needs it to order numbered user-visible revisions; add one here only if a
     * second writer of this row ever appears.
     */
    public void recordDevelopmentResult(
            String turnId, String operationId, DevelopmentReport report)
    {
        requireNonNull(report, "report is null");
        String taskId = store.requireStageTurnTaskId(
                requireNonNull(turnId, "turnId is null"),
                requireNonNull(operationId, "operationId is null"));
        commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            DevelopmentReport existing =
                    store.findDevelopmentSubmission(turnId).orElse(null);
            if (existing != null) {
                if (!existing.equals(report)) {
                    throw new IllegalArgumentException(
                            "record_development_result was already called with "
                                    + "different content for this Turn");
                }
                return existing;
            }
            store.insertDevelopmentSubmission(
                    turnId, operationId, taskId, report, clock.instant());
            return report;
        });
    }

    /**
     * Persist the verdict a Development Brain review reports through
     * {@code record_development_verdict}. Same reasoning as the Development
     * Turn's result: a review that read the code and formed an opinion should
     * not be discarded for writing that opinion as prose.
     */
    public void recordDevelopmentVerdict(
            String turnId, String operationId, AgentBrainResult verdict)
    {
        requireNonNull(verdict, "verdict is null");
        verdict.requireVerdict(BRAIN_LABEL);
        String taskId = store.requireBrainTurnTaskId(
                requireNonNull(turnId, "turnId is null"),
                requireNonNull(operationId, "operationId is null"));
        commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            AgentBrainResult existing = store.findBrainVerdict(turnId).orElse(null);
            if (existing != null) {
                if (!existing.equals(verdict)) {
                    throw new IllegalArgumentException(
                            "record_development_verdict was already called with "
                                    + "a different verdict for this Turn");
                }
                return existing;
            }
            store.insertBrainVerdict(
                    turnId, operationId, taskId, verdict, clock.instant());
            return verdict;
        });
    }

    public DispatchTicket.DeliveryReceipt deliverStageTurn(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (result.owner().kind() != DispatchTicket.OwnerKind.STAGE_TURN
                || !TURN_CALLBACK.equals(result.owner().callbackRoute())) {
            return receipt(SUPERSEDED, "Local StageTurn owner mismatch");
        }
        String taskId = store.requireStageTurnTaskId(
                result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverStageTurnInCommand(result));
    }

    /** Admits one explicit fresh Turn for an exact accepted Local failure. */
    public StageTurnRetryReceipt retryFailedStageTurn(
            String taskId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(failedTurnId, "failedTurnId");
        requireText(blockerId, "blockerId");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        StageTurnRetryReceipt existing = store.findStageTurnRetryReceipt(
                        taskId, commandId)
                .orElse(null);
        if (existing != null) {
            requireSameRetry(
                    failedTurnId, blockerId, actor, reason, existing);
            return existing;
        }
        return commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            StageTurnRetryReceipt duplicate = store.findStageTurnRetryReceipt(
                            taskId, commandId)
                    .orElse(null);
            if (duplicate != null) {
                requireSameRetry(
                        failedTurnId, blockerId, actor, reason, duplicate);
                return duplicate;
            }
            StageTurnRetryContext context = store.requireStageTurnRetryContext(
                    taskId, failedTurnId, blockerId);
            Instant now = clock.instant();
            StageTurnRetry retry = createStageTurnRetry(
                    context, commandId, actor, now);
            store.insertStageTurnRetry(retry);
            StageManager.Command command = new StageManager.Command(
                    commandId, actor, context.taskId(), context.taskEpoch(),
                    context.stageId(), context.stageGeneration(),
                    context.stageVersion());
            CommandResult<StageManager.State> admitted = requestRetryInCommand(
                    context, retry, command);
            if (admitted.disposition() != CommandResult.Disposition.APPLIED) {
                throw new IllegalStateException(
                        "Current Local StageTurn retry was superseded");
            }
            return store.recordStageTurnRetry(
                    context, retry, commandId, actor, reason,
                    context.stageVersion(), admitted.state().version(), now);
        });
    }

    /** Admits one fresh Task-owned Brain review for an exact protocol failure. */
    public BrainProtocolRetryReceipt retryFailedBrainReview(
            String taskId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(failedTurnId, "failedTurnId");
        requireText(blockerId, "blockerId");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        BrainProtocolRetryReceipt repaired = store.findBrainResultRepairReceipt(
                        taskId, failedTurnId, blockerId, commandId, actor, reason)
                .orElse(null);
        if (repaired != null) {
            return repaired;
        }
        BrainProtocolRetryReceipt existing = store.findBrainProtocolRetryReceipt(
                        taskId, commandId)
                .orElse(null);
        if (existing != null) {
            requireSameBrainRetry(
                    failedTurnId, blockerId, actor, reason, existing);
            return existing;
        }
        return commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            BrainProtocolRetryReceipt duplicate =
                    store.findBrainProtocolRetryReceipt(taskId, commandId)
                            .orElse(null);
            if (duplicate != null) {
                requireSameBrainRetry(
                        failedTurnId, blockerId, actor, reason, duplicate);
                return duplicate;
            }
            BrainProtocolRetryReceipt repairedDuplicate =
                    store.findBrainResultRepairReceipt(
                                    taskId, failedTurnId, blockerId,
                                    commandId, actor, reason)
                            .orElse(null);
            if (repairedDuplicate != null) {
                return repairedDuplicate;
            }
            BrainResultRepairSource repairSource =
                    store.findBrainResultRepairSource(
                                    taskId, failedTurnId, blockerId)
                            .orElse(null);
            if (repairSource != null) {
                Instant now = clock.instant();
                admitBrainResultRepair(repairSource, actor, reason, now);
                return store.findBrainResultRepairReceipt(
                                taskId, failedTurnId, blockerId,
                                commandId, actor, reason)
                        .orElseThrow(() -> new IllegalStateException(
                                "Development Brain result repair receipt is missing"));
            }
            BrainProtocolRetryContext context =
                    store.requireBrainProtocolRetryContext(
                            taskId, failedTurnId, blockerId);
            Instant now = clock.instant();
            BrainProtocolRetry retry = createBrainProtocolRetry(
                    context, commandId, now);
            store.insertBrainProtocolRetry(retry);
            String taskCommandId = id(
                    "request-development-brain-protocol-retry",
                    taskId + ":" + commandId);
            CommandResult<TaskManager.State> admitted =
                    tasks.requestBrainReviewInCommand(
                            new TaskManager.BrainReviewRequestCommand(
                                    taskCommandId, actor, taskId,
                                    context.taskEpoch(), context.taskVersion(),
                                    retry.episodeId(), retry.fence()));
            if (admitted.disposition() != CommandResult.Disposition.APPLIED) {
                throw new IllegalStateException(
                        "Development Brain protocol retry was superseded");
            }
            return store.recordBrainProtocolRetry(
                    context, retry, commandId, taskCommandId, actor, reason,
                    admitted.state().version(), now);
        });
    }

    public DispatchTicket.DeliveryReceipt deliverValidation(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !LocalValidationOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())) {
            return receipt(SUPERSEDED, "Local Validation owner mismatch");
        }
        if (!expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "Local Validation result fence is stale");
        }
        String taskId = store.requireValidationTaskId(expectedFence.operationId());
        return commands.execute(taskId, () -> deliverValidationInCommand(
                owner, expectedFence, rawResult));
    }

    public DispatchTicket.DeliveryReceipt deliverBrainTurn(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (result.owner().kind() != DispatchTicket.OwnerKind.TASK_TURN
                || !PlanRuntimeCoordinator.TURN_CALLBACK.equals(
                        result.owner().callbackRoute())) {
            return receipt(SUPERSEDED, "Development Brain owner mismatch");
        }
        String taskId = store.requireBrainTurnTaskId(
                result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverBrainTurnInCommand(result));
    }

    private DispatchTicket.DeliveryReceipt deliverStageTurnInCommand(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        String turnId = result.owner().id();
        String rawDigest = digest(write(result));
        StageTurnDeliveryReceipt duplicate = store.findStageTurnReceipt(turnId)
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawResultDigest())) {
                throw new IllegalStateException(
                        "Local StageTurn was delivered with different evidence");
            }
            return receipt(
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance()),
                    deliveryResult(duplicate));
        }

        StageTurnContext context = store.requireStageTurnContext(
                turnId, result.fence().operationId());
        if (!context.fence().equals(toResultFence(result.fence()))) {
            throw new IllegalArgumentException(
                    "Local StageTurn result differs from its persisted fence");
        }
        Instant now = clock.instant();
        if (steering != null
                && steering.cancellationRequestedFor(context.operationId())) {
            if (!"SUPERSEDED".equals(context.turnStatus())) {
                store.finishStageTurn(
                        context, "SUPERSEDED",
                        "replaced by durable user steering", now);
            }
            clearResult(context);
            StageTurnDeliveryReceipt recorded = new StageTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    SUPERSEDED.name(), null, null, now);
            store.insertStageTurnReceipt(recorded);
            return receipt(SUPERSEDED, deliveryResult(recorded));
        }
        if (result.outcome() != SUCCEEDED) {
            String terminal = result.outcome() == CANCELED ? "CANCELED" : "FAILED";
            String error = failureDetail(result);
            store.finishStageTurn(context, terminal, error, now);
            if (localReview != null && context.localFeedbackBatchId() != null) {
                localReview.rejectFeedbackResultInCommand(
                        context.taskId(), context.localFeedbackBatchId(),
                        context.turnId(), terminal, error,
                        context.isCurrent());
            }
            CommandResult<StageManager.State> cleared = clearResult(context);
            DispatchTicket.Acceptance acceptance = cleared.disposition()
                    == CommandResult.Disposition.SUPERSEDED ? SUPERSEDED : ACCEPTED;
            if (acceptance == ACCEPTED && "FAILED".equals(terminal)) {
                store.openStageTurnFailure(
                        context, error, failurePayload(result, error),
                        cleared.state().version(), now);
            }
            StageTurnDeliveryReceipt recorded = new StageTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    acceptance.name(), null, null, now);
            store.insertStageTurnReceipt(recorded);
            return receipt(acceptance, deliveryResult(recorded));
        }

        // The Turn's final text is prose and nobody parses it. The result is
        // the row record_development_result wrote while the Turn was running.
        DevelopmentReport development = store
                .findDevelopmentSubmission(context.turnId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Local Development StageTurn succeeded without "
                                + "record_development_result"));
        if (!context.isCurrent()) {
            store.finishStageTurn(context, "SUPERSEDED", "stale Local subject", now);
            if (localReview != null && context.localFeedbackBatchId() != null) {
                localReview.rejectFeedbackResultInCommand(
                        context.taskId(), context.localFeedbackBatchId(),
                        context.turnId(), "SUPERSEDED", "stale Local subject", false);
            }
            clearResult(context);
            StageTurnDeliveryReceipt recorded = new StageTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    SUPERSEDED.name(), null, null, now);
            store.insertStageTurnReceipt(recorded);
            return receipt(SUPERSEDED, deliveryResult(recorded));
        }

        String requiredOutputBase = "BASE_SYNC".equals(context.requestKind())
                ? requireNonNull(
                        context.targetBaseSha(),
                        "Base-sync target base is missing")
                : context.baseSha();
        OutputCodeSubject exactOutput = result.requireOutputCodeSubject(
                requiredOutputBase);
        if (!exactOutput.clean()) {
            throw new IllegalArgumentException(
                    "Local Development result left uncommitted worktree changes");
        }
        if (exactOutput.headSha().equals(exactOutput.baseSha())) {
            throw new IllegalArgumentException(
                    "Local Development result has no commit ahead of its base");
        }
        CodeSubject output = new CodeSubject(
                exactOutput.codeFingerprint(), exactOutput.headSha(),
                exactOutput.baseSha());
        // Design 3.36: the agent's template-aware body, not an empty string.
        prs.createForTaskInCommand(
                context.taskId(), context.branchName(), context.baseBranch(),
                context.taskName(), development.prDescription());
        store.finishStageTurn(context, "SUCCEEDED", null, now);
        if (localReview != null && context.localFeedbackBatchId() != null) {
            localReview.acceptFeedbackResultInCommand(
                    context.taskId(), context.localFeedbackBatchId(), context.turnId());
        }
        DevReport report = store.insertDevReport(context, development, output, now);
        if (localReview != null) {
            localReview.carryFeedbackToCurrentSubjectInCommand(
                    context.taskId(), context.turnId());
        }
        CommandResult<StageManager.State> accepted = acceptCodeResult(context, report.id());
        if (accepted.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Current Local code result became superseded inside its command");
        }
        // A big Turn gets one cleanup pass before validation rather than after:
        // the Stage stays at IMPLEMENTING (as BASE_SYNC does), so there is no
        // checkpoint to race, and validation then runs once over the simplified
        // code instead of once per Turn.
        if (shouldSimplify(context, exactOutput)
                && accepted.state().checkpoint() == StageCheckpoint.IMPLEMENTING) {
            StageTurnRetry simplify = createSimplifyTurn(context, exactOutput, now);
            store.insertStageTurnRetry(simplify);
            CommandResult<StageManager.State> requestedSimplify =
                    local.requestImplementationInCommand(
                            new StageManager.Command(
                                    id("request-local-simplify", simplify.turnId()),
                                    ACTOR, context.taskId(), context.taskEpoch(),
                                    context.stageId(), context.stageGeneration(),
                                    accepted.state().version()),
                            simplify.fence(), simplify.requestId());
            if (requestedSimplify.disposition()
                    == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException(
                        "Local simplify request was superseded");
            }
            StageTurnDeliveryReceipt simplified = new StageTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(),
                    rawDigest, ACCEPTED.name(), report.id(), null, now);
            store.insertStageTurnReceipt(simplified);
            return receipt(ACCEPTED, deliveryResult(simplified));
        }
        CommandResult<StageManager.State> validating = accepted;
        if (accepted.state().checkpoint() == StageCheckpoint.IMPLEMENTING) {
            validating = local.beginValidationInCommand(
                    stageCommand(
                            context, id("begin-local-validation", context.operationId()),
                            accepted.state().version()), report.id());
        }
        ValidationRequest validation = store.insertValidation(context, report, now);
        CommandResult<StageManager.State> requested = local.requestValidationInCommand(
                stageCommand(
                        context, id("request-local-validation", validation.operationId()),
                        validating.state().version()),
                validation.fence(), validation.operationId());
        if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Local validation request was superseded");
        }
        StageTurnDeliveryReceipt recorded = new StageTurnDeliveryReceipt(
                turnId, context.operationId(), result.outcome().name(), rawDigest,
                ACCEPTED.name(), report.id(), validation.operationId(), now);
        store.insertStageTurnReceipt(recorded);
        return receipt(ACCEPTED, deliveryResult(recorded));
    }

    private DispatchTicket.DeliveryReceipt deliverValidationInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        ValidationContext context = store.requireValidationContext(
                expectedFence.operationId());
        String rawDigest = digest(write(rawResult));
        ValidationDeliveryReceipt duplicate = store.findValidationReceipt(
                        context.validationOperationId())
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawResultDigest())) {
                throw new IllegalStateException(
                        "Local Validation was delivered with different evidence");
            }
            return receipt(
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance()),
                    duplicate.acceptance() + ":" + duplicate.operationId());
        }
        if (!context.stageId().equals(owner.id())
                || !context.fence().equals(toResultFence(expectedFence))
                || !"RESULT_PENDING".equals(context.ticketStatus())) {
            throw new IllegalArgumentException(
                    "Local Validation delivery differs from its persisted owner");
        }
        Instant now = clock.instant();
        if (rawResult.outcome() != SUCCEEDED) {
            String status = rawResult.outcome() == CANCELED ? "CANCELED" : "FAILED";
            store.finishValidationWithoutEvidence(
                    context, status, rawResult.error(), now);
            CommandResult<StageManager.State> cleared = local.clearValidationInCommand(
                    new StageManager.ResultCommand(
                            id("clear-local-validation", context.operationId()),
                            ACTOR, context.taskId(), context.fence()),
                    context.validationOperationId());
            DispatchTicket.Acceptance acceptance = cleared.disposition()
                    == CommandResult.Disposition.SUPERSEDED ? SUPERSEDED : ACCEPTED;
            ValidationDeliveryReceipt recorded = new ValidationDeliveryReceipt(
                    context.validationOperationId(), context.operationId(),
                    rawResult.outcome().name(), rawDigest, acceptance.name(),
                    null, null, now);
            store.insertValidationReceipt(recorded);
            return receipt(acceptance,
                    acceptance.name() + ":" + context.operationId());
        }

        LocalValidationOperationHandler.ValidationResult result =
                decodeValidationResult(rawResult);
        requireValidationResult(context, result);
        if (!context.isCurrent() || !result.subjectCurrent()) {
            store.finishValidationWithoutEvidence(
                    context, "SUPERSEDED", "stale Local validation subject", now);
            local.clearValidationInCommand(
                    new StageManager.ResultCommand(
                            id("clear-local-validation", context.operationId()),
                            ACTOR, context.taskId(), context.fence()),
                    context.validationOperationId());
            ValidationDeliveryReceipt recorded = new ValidationDeliveryReceipt(
                    context.validationOperationId(), context.operationId(),
                    rawResult.outcome().name(), rawDigest, SUPERSEDED.name(),
                    null, null, now);
            store.insertValidationReceipt(recorded);
            return receipt(SUPERSEDED,
                    SUPERSEDED.name() + ":" + context.operationId());
        }

        String failuresJson = write(result.failures());
        ValidationEvidence evidence = store.completeValidation(
                context, result.passed(), failuresJson, rawResult.evidenceJson(),
                Instant.ofEpochMilli(result.startedAtMs()),
                Instant.ofEpochMilli(result.completedAtMs()));
        if (!result.passed()) {
            CommandResult<StageManager.State> cleared = local.clearValidationInCommand(
                    new StageManager.ResultCommand(
                            id("clear-local-validation", context.operationId()),
                            ACTOR, context.taskId(), context.fence()),
                    context.validationOperationId());
            DispatchTicket.Acceptance acceptance = cleared.disposition()
                    == CommandResult.Disposition.SUPERSEDED ? SUPERSEDED : ACCEPTED;
            ValidationDeliveryReceipt recorded = new ValidationDeliveryReceipt(
                    context.validationOperationId(), context.operationId(),
                    rawResult.outcome().name(), rawDigest, acceptance.name(),
                    evidence.id(), null, now);
            store.insertValidationReceipt(recorded);
            return receipt(acceptance,
                    acceptance.name() + ":" + context.operationId());
        }

        CommandResult<StageManager.State> reviewed = local.acceptValidationInCommand(
                new StageManager.ResultCommand(
                        id("accept-local-validation", context.operationId()),
                        ACTOR, context.taskId(), context.fence()));
        if (reviewed.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Current green validation became superseded inside its command");
        }
        BrainReviewRequest brain = createBrainReview(context, evidence, now);
        CommandResult<TaskManager.State> task = tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        id("request-development-brain-review", brain.episodeId()),
                        ACTOR, context.taskId(), context.taskEpoch(),
                        context.taskVersion(), brain.episodeId(), brain.fence()));
        if (task.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Task Brain review request was superseded");
        }
        ValidationDeliveryReceipt recorded = new ValidationDeliveryReceipt(
                context.validationOperationId(), context.operationId(),
                rawResult.outcome().name(), rawDigest, ACCEPTED.name(),
                evidence.id(), brain.episodeId(), now);
        store.insertValidationReceipt(recorded);
        return receipt(ACCEPTED, ACCEPTED.name() + ":" + context.operationId());
    }

    private DispatchTicket.DeliveryReceipt deliverBrainTurnInCommand(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        String turnId = result.owner().id();
        String rawDigest = digest(write(result));
        BrainTurnDeliveryReceipt duplicate = store.findBrainTurnReceipt(turnId)
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawResultDigest())) {
                throw new IllegalStateException(
                        "Development Brain was delivered with different evidence");
            }
            DispatchTicket.Acceptance acceptance =
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance());
            return receipt(acceptance,
                    duplicate.acceptance() + ":" + duplicate.operationId());
        }

        BrainTurnContext context = store.requireBrainTurnContext(
                turnId, result.fence().operationId());
        if (!context.deliveryFence().equals(toResultFence(result.fence()))) {
            throw new IllegalArgumentException(
                    "Development Brain result differs from its persisted fence");
        }
        Instant now = clock.instant();
        TaskManager.ResultCommand brainCommand = new TaskManager.ResultCommand(
                id("accept-development-brain", context.operationId()),
                ACTOR, context.taskId(), context.ownerFence());
        if (!context.isCurrent()
                || !tasks.isCurrentBrainResultInCommand(brainCommand)) {
            store.supersedeBrain(context, "stale Development Brain subject", now);
            if (context.resultRepair()) {
                ObjectNode evidence = json.createObjectNode();
                evidence.put("schema",
                        "DEVELOPMENT_BRAIN_RESULT_REPAIR_SUPERSEDED_V1");
                evidence.put("repairTurnId", turnId);
                evidence.put("repairOperationId", context.operationId());
                evidence.put("brainReviewEpisodeId", context.episodeId());
                evidence.put("rawResultDigest", rawDigest);
                evidence.put("reason", "stale Development Brain subject");
                store.finishBrainResultRepair(
                        context, "SUPERSEDED", result.outcome().name(), rawDigest,
                        null, SUPERSEDED.name(), write(evidence), now);
            }
            else {
                BrainTurnDeliveryReceipt recorded = new BrainTurnDeliveryReceipt(
                        turnId, context.operationId(), result.outcome().name(),
                        rawDigest, SUPERSEDED.name(), context.episodeId(), null,
                        null, null, now);
                store.insertBrainTurnReceipt(recorded);
            }
            return receipt(SUPERSEDED,
                    SUPERSEDED.name() + ":" + context.operationId());
        }
        if (context.resultRepair() && result.outcome() != SUCCEEDED) {
            return acceptBrainResultRepairFailure(
                    context, result, rawDigest,
                    new IllegalArgumentException(result.payload().error() == null
                            ? "provider did not produce a repaired result"
                            : result.payload().error()),
                    now);
        }
        if (isBudgetExhaustion(result)) {
            String detail = result.payload().error() == null
                    ? "Brain review budget exhausted"
                    : result.payload().error();
            String blockerId = store.exhaustBrainBudget(context, detail, now);
            CommandResult<TaskManager.State> cleared =
                    tasks.acceptBrainBudgetExhaustionInCommand(
                            new TaskManager.ResultCommand(
                                    id("accept-brain-budget", context.operationId()),
                                    ACTOR, context.taskId(), context.ownerFence()),
                            blockerId);
            DispatchTicket.Acceptance acceptance;
            if (cleared.disposition() == CommandResult.Disposition.SUPERSEDED) {
                acceptance = SUPERSEDED;
            }
            else {
                CommandResult<StageManager.State> stage =
                        local.acceptBrainBudgetExhaustionInCommand(
                                new StageManager.Command(
                                        id("local-brain-budget", context.operationId()),
                                        ACTOR, context.taskId(), context.taskEpoch(),
                                        context.stageId(), context.stageGeneration(),
                                        context.stageVersion()),
                                blockerId);
                acceptance = stage.disposition()
                        == CommandResult.Disposition.SUPERSEDED
                        ? SUPERSEDED : ACCEPTED;
                if (acceptance == ACCEPTED) {
                    prs.requestUserReviewInCommand(context.taskId(), ACTOR);
                    if (localReview != null) {
                        localReview.admitQueuedInCommand(context.taskId());
                    }
                }
            }
            BrainTurnDeliveryReceipt recorded = new BrainTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    acceptance.name(), context.episodeId(), null, blockerId,
                    null, now);
            store.insertBrainTurnReceipt(recorded);
            return receipt(acceptance,
                    acceptance.name() + ":" + context.operationId());
        }
        if (result.outcome() != SUCCEEDED) {
            throw new IllegalStateException(
                    "Development Brain failed without typed budget evidence");
        }

        AgentBrainResult verdict;
        TaskManager.BrainVerdict domainVerdict;
        int unresolved;
        try {
            // A review's final message is prose; its verdict is the row
            // record_development_verdict wrote while the Turn was running. The
            // repair Turn is the one exception — it is launched deliberately
            // tool-free, because its whole job is to restate a verdict that
            // already exists, so it still answers in its final message.
            verdict = context.resultRepair()
                    ? AgentBrainResult.decode(
                            AgentBrainResult.reader(json),
                            result.payload().finalText(), BRAIN_LABEL)
                    : store.findBrainVerdict(context.turnId())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    BRAIN_LABEL + " review succeeded without "
                                            + "record_development_verdict"));
            domainVerdict = verdict.requireVerdict(BRAIN_LABEL);
            unresolved = verdict.findings().size();
        }
        catch (IllegalArgumentException protocolFailure) {
            if (context.resultRepair()) {
                return acceptBrainResultRepairFailure(
                        context, result, rawDigest, protocolFailure, now);
            }
            return acceptBrainProtocolFailure(
                    context, result, rawDigest, protocolFailure, now);
        }
        store.completeBrainVerdict(
                context, domainVerdict.name(), unresolved,
                required(verdict.summary(), "summary"), now);
        TaskManager.BrainVerdictResult accepted = tasks.acceptBrainVerdictInCommand(
                brainCommand,
                domainVerdict);
        if (accepted.task().disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Current Development Brain verdict became superseded");
        }
        TaskManager.AcceptedBrainVerdict proof = accepted.accepted().orElseThrow();
        String nextRequestId = null;
        if (domainVerdict == TaskManager.BrainVerdict.APPROVED) {
            CommandResult<StageManager.State> stage =
                    local.acceptBrainApprovalInCommand(proof);
            if (stage.disposition() == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException("Local Brain approval was superseded");
            }
            prs.requestUserReviewInCommand(context.taskId(), ACTOR);
            if (localReview != null) {
                localReview.admitQueuedInCommand(context.taskId());
            }
        }
        else {
            CommandResult<StageManager.State> stage =
                    local.acceptBrainFindingsInCommand(proof);
            if (stage.disposition() == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException("Local Brain findings were superseded");
            }
            BrainFixTurn fix = createBrainFixTurn(context, verdict, now);
            store.insertBrainFixTurn(fix);
            CommandResult<StageManager.State> requested = local.requestBrainFixInCommand(
                    new StageManager.Command(
                            fix.commandId(), ACTOR, context.taskId(), context.taskEpoch(),
                            context.stageId(), context.stageGeneration(),
                            stage.state().version()),
                    fix.fence(), fix.requestId());
            if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException("Brain-finding fix request was superseded");
            }
            nextRequestId = fix.requestId();
        }
        if (context.resultRepair()) {
            String repairedPayload = result.payload().finalText();
            String repairedPayloadDigest = digest(repairedPayload);
            ObjectNode evidence = json.createObjectNode();
            evidence.put("schema", "DEVELOPMENT_BRAIN_RESULT_REPAIR_SUCCESS_V1");
            evidence.put("repairTurnId", turnId);
            evidence.put("repairOperationId", context.operationId());
            evidence.put("brainReviewEpisodeId", context.episodeId());
            evidence.put("rawResultDigest", rawDigest);
            evidence.put("repairedPayload", repairedPayload);
            evidence.put("repairedPayloadDigest", repairedPayloadDigest);
            evidence.put("verdict", domainVerdict.name());
            evidence.put("summary", verdict.summary());
            evidence.put("unresolvedFindingCount", unresolved);
            putNullable(evidence, "nextStageTurnRequestId", nextRequestId);
            store.finishBrainResultRepair(
                    context, "SUCCEEDED", result.outcome().name(), rawDigest,
                    repairedPayloadDigest, ACCEPTED.name(),
                    write(evidence), now);
        }
        else {
            BrainTurnDeliveryReceipt recorded = new BrainTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    ACCEPTED.name(), context.episodeId(), domainVerdict.name(), null,
                    nextRequestId, now);
            store.insertBrainTurnReceipt(recorded);
        }
        return receipt(ACCEPTED, ACCEPTED.name() + ":" + context.operationId());
    }

    private DispatchTicket.DeliveryReceipt acceptBrainProtocolFailure(
            BrainTurnContext context,
            AgentTurnOwnerResultCodec.OwnerResult result,
            String rawDigest,
            IllegalArgumentException failure,
            Instant now)
    {
        String predecessorFailureId = result.payload().finalText() == null
                || result.payload().finalText().isBlank()
                ? null
                : store.findExactBrainProtocolRetryFailureId(context)
                        .orElse(null);
        boolean admitResultRepair = predecessorFailureId != null;
        String message = failure.getMessage() == null
                || failure.getMessage().isBlank()
                ? "Development Brain returned an invalid owner result"
                : failure.getMessage();
        String detail = "Development Brain protocol failure: " + message;
        ObjectNode payload = json.createObjectNode();
        payload.put("schema", "DEVELOPMENT_BRAIN_PROTOCOL_FAILURE_V1");
        payload.put("failedTurnId", context.turnId());
        payload.put("failedOperationId", context.operationId());
        payload.put("brainReviewEpisodeId", context.episodeId());
        payload.put("stageId", context.stageId());
        payload.put("stageGeneration", context.stageGeneration());
        payload.put("codeFingerprint", context.codeFingerprint());
        payload.put("headSha", context.headSha());
        payload.put("baseSha", context.baseSha());
        payload.put("rawOutcome", result.outcome().name());
        payload.put("rawResultDigest", rawDigest);
        payload.put("message", detail);
        String blockerId = store.failBrainProtocol(
                context, detail, write(payload), now);
        CommandResult<TaskManager.State> cleared =
                tasks.acceptBrainProtocolFailureInCommand(
                        new TaskManager.ResultCommand(
                                id("accept-development-brain-protocol-failure",
                                        context.operationId()),
                                ACTOR, context.taskId(), context.ownerFence()),
                        blockerId);
        if (cleared.disposition() != CommandResult.Disposition.APPLIED) {
            throw new IllegalStateException(
                    "Development Brain protocol failure became superseded");
        }
        store.insertBrainProtocolFailure(
                context, blockerId, rawDigest, detail,
                cleared.state().version(), now);
        if (admitResultRepair) {
            BrainResultRepairSource source = new BrainResultRepairSource(
                    predecessorFailureId,
                    id("development-brain-protocol-failure", context.turnId()),
                    blockerId, context.turnId(), context.operationId(), rawDigest,
                    result.payload().finalText(), context.taskBrainId(),
                    context.episodeId(), context.taskId(), context.taskEpoch(),
                    cleared.state().version(), context.stageId(),
                    context.stageGeneration(), context.devReportId(),
                    context.validationEvidenceId(), context.codeFingerprint(),
                    context.headSha(), context.baseSha(),
                    context.semanticAttempt(), context.budgetAttempt(),
                    context.deliveryLane(), context.laneMask(),
                    context.launchInput(), context.workspaceId(),
                    context.trunkId(), context.worktreePath());
            admitBrainResultRepair(source, ACTOR,
                    "repair malformed Development Brain result", now);
        }
        return receipt(ACCEPTED, ACCEPTED.name() + ":" + context.operationId());
    }

    private DispatchTicket.DeliveryReceipt acceptBrainResultRepairFailure(
            BrainTurnContext context,
            AgentTurnOwnerResultCodec.OwnerResult result,
            String rawDigest,
            IllegalArgumentException failure,
            Instant now)
    {
        String message = failure.getMessage() == null
                || failure.getMessage().isBlank()
                ? "Development Brain result repair was invalid"
                : failure.getMessage();
        String detail = "Development Brain result repair failed: " + message;
        ObjectNode payload = json.createObjectNode();
        payload.put("schema", "DEVELOPMENT_BRAIN_RESULT_REPAIR_FAILURE_V1");
        payload.put("repairTurnId", context.turnId());
        payload.put("repairOperationId", context.operationId());
        payload.put("brainReviewEpisodeId", context.episodeId());
        payload.put("rawOutcome", result.outcome().name());
        payload.put("rawResultDigest", rawDigest);
        payload.put("message", detail);
        boolean canceled = result.outcome() == CANCELED;
        String blockerId = canceled
                ? store.cancelBrainResultRepair(
                        context, detail, write(payload), now)
                : store.failBrainProtocol(
                        context, detail, write(payload), now);
        CommandResult<TaskManager.State> cleared =
                tasks.acceptBrainProtocolFailureInCommand(
                        new TaskManager.ResultCommand(
                                id("accept-development-brain-result-repair-failure",
                                        context.operationId()),
                                ACTOR, context.taskId(), context.ownerFence()),
                        blockerId);
        if (cleared.disposition() != CommandResult.Disposition.APPLIED) {
            throw new IllegalStateException(
                    "Development Brain result repair failure became superseded");
        }
        payload.put("blockerId", blockerId);
        payload.put("taskVersion", cleared.state().version());
        store.finishBrainResultRepair(
                context, canceled ? "CANCELED" : "FAILED",
                result.outcome().name(), rawDigest, null,
                ACCEPTED.name(), write(payload), now);
        return receipt(ACCEPTED, ACCEPTED.name() + ":" + context.operationId());
    }

    private BrainFixTurn createBrainFixTurn(
            BrainTurnContext context,
            AgentBrainResult verdict,
            Instant now)
    {
        String requestId = id("brain-fix-request", context.episodeId());
        String commandId = id("request-brain-fix", context.episodeId());
        String turnId = id("brain-fix-turn", context.episodeId());
        String operationId = id("brain-fix-operation", context.episodeId());
        String ticketId = id("brain-fix-ticket", context.episodeId());
        // Name the fields rather than pointing at "the same" JSON: this Turn is
        // a fresh agent that never saw the prompt being referred to.
        String prompt = "Address every finding from the Task Brain against this "
                + "exact code subject:\n\n" + String.join("\n", verdict.findings())
                + "\n\n" + DEVELOPMENT_RESULT_INSTRUCTION;
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        model = stageEffort(context.trunkId(), context.taskId(),
                context.stageId(), model);
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(),
                model, context.worktreePath(), turnId, operationId, prompt);
        if (steering != null) {
            StageCliContinuity.applyExact(
                    json, launch, context.predecessorStageTurnId(), model.kind(),
                    prompt, steering, new StageCliContinuity.Fence(
                            context.stageId(), context.stageGeneration(),
                            context.codeFingerprint(), context.headSha(),
                            context.baseSha(), context.provider(), context.model(),
                            context.worktreePath()));
        }
        return new BrainFixTurn(
                requestId, commandId, turnId, operationId, ticketId,
                context.episodeId(), context.workspaceId(), context.trunkId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), lane, laneMask,
                write(launch), digest(prompt), ACTOR, now);
    }

    private ObjectNode writerLaunch(
            String provider,
            String modelName,
            String roleSkill,
            WorkModel model,
            String worktreePath,
            String turnId,
            String operationId,
            String prompt)
    {
        if (!provider.equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !modelName.equals(model.model())) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model do not identify one engine");
        }
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", model.kind().name());
        launch.put("provider", provider);
        putNullable(launch, "credentialAccount", model.account());
        launch.put("model", modelName);
        putNullable(launch, "reasoningEffort", model.reasoningEffort());
        launch.put("workingDirectory", worktreePath);
        launch.put("systemPrompt", implementationSystemPrompt(roleSkill));
        launch.put("prompt", prompt);
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/stage-turns/" + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", "STAGE_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "STAGE_DEVELOPMENT");
        endpoint.put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return launch;
    }

    private void applyCliContinuity(
            ObjectNode launch,
            Request request,
            LocalContext context,
            WorkModel model,
            String currentPrompt,
            SqliteStageSteeringStore ownerStore)
    {
        StageCliContinuity.apply(
                json, launch, request, model.kind(), currentPrompt, ownerStore,
                new StageCliContinuity.Fence(
                        context.stageId(), context.stageGeneration(),
                        context.codeFingerprint(), context.headSha(),
                        context.baseSha(), context.provider(), context.model(),
                        context.worktreePath()));
    }

    /**
     * True when this Turn wrote enough new code to be worth one cleanup pass.
     *
     * <p>A cleanup Turn is excluded so the pass cannot chain: its own output is
     * net-deletion and would score near zero anyway, but the explicit guard
     * means a restructuring cleanup that happens to add a lot still stops here.
     * Turns older than the added-line count report null and never trigger.
     */
    private static boolean shouldSimplify(
            StageTurnContext context, OutputCodeSubject output)
    {
        if (SIMPLIFY_KIND.equals(context.requestKind())) {
            return false;
        }
        Integer added = output.addedLines();
        return added != null && added >= MIN_ADDED_LINES;
    }

    /**
     * Cleanup Turn over the code the delivered Turn just committed. Reuses the
     * delivered Turn's stored launch — same provider, model, work model, and
     * worktree — and swaps in the simplify prompt, so no launch plumbing is
     * duplicated. Fenced on the Turn's <em>output</em> subject because the code
     * has already moved.
     */
    private StageTurnRetry createSimplifyTurn(
            StageTurnContext context, OutputCodeSubject output, Instant now)
    {
        String source = context.taskId() + ":" + context.turnId();
        String requestId = id("local-simplify-request", source);
        String turnId = id("local-simplify-turn", source);
        String operationId = id("local-simplify-operation", source);
        String ticketId = id("local-simplify-ticket", source);
        try {
            JsonNode stored = json.readTree(context.launchInput());
            if (!(stored instanceof ObjectNode launch)) {
                throw new IllegalStateException(
                        "Stored Local StageTurn launch is not an object");
            }
            String prompt = simplifyPrompt(output);
            launch.put("prompt", prompt);
            // A fresh session: the cleanup pass must re-read the committed diff
            // rather than inherit the writer's belief about what it wrote.
            launch.remove(List.of(
                    "resumeSessionId", "fallbackPrompt",
                    "priorCumulativeInputTokens",
                    "priorCumulativeOutputTokens"));
            JsonNode endpointNode = launch.get("toolEndpoint");
            if (!(endpointNode instanceof ObjectNode endpoint)) {
                throw new IllegalStateException(
                        "Stored Local StageTurn has no tool endpoint");
            }
            endpoint.put("url", "http://127.0.0.1:" + serverPort
                    + "/api/v2/stage-turns/" + turnId
                    + "/operations/" + operationId + "/mcp");
            endpoint.put("ownerKind", "STAGE_TURN");
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            return new StageTurnRetry(
                    requestId, id("persist-local-simplify", source),
                    turnId, operationId, ticketId, context.taskId(),
                    context.trunkId(), context.workspaceId(), context.taskEpoch(),
                    context.stageId(), context.stageGeneration(),
                    1, IMPLEMENT_PURPOSE,
                    SIMPLIFY_KIND, null, null, null, null,
                    output.codeFingerprint(), output.headSha(), output.baseSha(),
                    context.deliveryLane(), context.laneMask(), write(launch),
                    digest(prompt), ACTOR, now);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Stored Local StageTurn launch is invalid", e);
        }
    }

    private static String simplifyPrompt(OutputCodeSubject output)
    {
        return SimplifyPrompt.body()
                + "\n\nThe Turn you are cleaning up committed "
                + output.addedLines() + " added lines. Review exactly that "
                + "range — `git diff " + output.baseSha() + ".."
                + output.headSha() + "` — and nothing outside it.\n\n"
                + "Commit any cleanup on the current Task branch as one small "
                + "commit. Committing is local and required. Do not push or "
                + "create remote effects. " + SIMPLIFY_INSTRUCTION;
    }

    private StageTurnRetry createStageTurnRetry(
            StageTurnRetryContext context,
            String commandId,
            String actor,
            Instant now)
    {
        String source = context.taskId() + ":" + commandId;
        String requestId = id("local-stage-retry-request", source);
        String turnId = id("local-stage-retry-turn", source);
        String operationId = id("local-stage-retry-operation", source);
        String ticketId = id("local-stage-retry-ticket", source);
        try {
            JsonNode stored = json.readTree(context.failedLaunchInput());
            if (!(stored instanceof ObjectNode launch)) {
                throw new IllegalStateException(
                        "Stored failed Local StageTurn launch is not an object");
            }
            String priorPrompt = launch.hasNonNull("fallbackPrompt")
                    ? required(launch.path("fallbackPrompt").asText(),
                            "fallbackPrompt")
                    : required(launch.path("prompt").asText(), "prompt");
            StringBuilder prompt = new StringBuilder(priorPrompt);
            List<String> trace = store.executionLog(context.failedTicketId());
            if (!trace.isEmpty()) {
                prompt.append("\n\nDurable provider trace from the failed Turn:\n");
                trace.forEach(event -> prompt.append(event).append('\n'));
            }
            prompt.append("\n\nFailure evidence:\n")
                    .append(context.error())
                    .append("\n\nRetry instruction:\n")
                    .append(RETRY_INSTRUCTION);
            launch.put("prompt", prompt.toString());
            launch.remove(List.of(
                    "resumeSessionId", "fallbackPrompt",
                    "priorCumulativeInputTokens",
                    "priorCumulativeOutputTokens"));
            JsonNode endpointNode = launch.get("toolEndpoint");
            if (!(endpointNode instanceof ObjectNode endpoint)) {
                throw new IllegalStateException(
                        "Stored failed Local StageTurn has no tool endpoint");
            }
            endpoint.put("url", "http://127.0.0.1:" + serverPort
                    + "/api/v2/stage-turns/" + turnId
                    + "/operations/" + operationId + "/mcp");
            endpoint.put("ownerKind", "STAGE_TURN");
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            return new StageTurnRetry(
                    requestId, id("persist-local-stage-retry", source),
                    turnId, operationId, ticketId, context.taskId(),
                    context.trunkId(), context.workspaceId(), context.taskEpoch(),
                    context.stageId(), context.stageGeneration(),
                    context.failedAttempt() + 1, context.purpose(),
                    context.requestKind(), context.brainReviewEpisodeId(),
                    context.localFeedbackBatchId(), context.baseSyncEpisodeId(),
                    context.targetBaseSha(), context.codeFingerprint(),
                    context.headSha(), context.baseSha(), context.deliveryLane(),
                    context.laneMask(), write(launch), digest(prompt.toString()),
                    actor, now);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Stored failed Local StageTurn launch is invalid", e);
        }
    }

    private BrainProtocolRetry createBrainProtocolRetry(
            BrainProtocolRetryContext context,
            String commandId,
            Instant now)
    {
        String source = context.taskId() + ":" + commandId;
        String episodeId = id("development-brain-retry-episode", source);
        String turnId = id("development-brain-retry-turn", source);
        String operationId = id("development-brain-retry-operation", source);
        String ticketId = id("development-brain-retry-ticket", source);
        try {
            JsonNode stored = json.readTree(context.failedLaunchInput());
            if (!(stored instanceof ObjectNode launch)) {
                throw new IllegalStateException(
                        "Stored failed Development Brain launch is not an object");
            }
            if (!context.worktreePath().equals(
                    launch.path("workingDirectory").asText())) {
                throw new IllegalStateException(
                        "Stored Development Brain worktree changed");
            }
            String priorPrompt = launch.hasNonNull("fallbackPrompt")
                    ? required(launch.path("fallbackPrompt").asText(),
                            "fallbackPrompt")
                    : required(launch.path("prompt").asText(), "prompt");
            StringBuilder prompt = new StringBuilder(priorPrompt);
            List<String> trace = store.executionLog(context.failedTicketId());
            if (!trace.isEmpty()) {
                prompt.append("\n\nDurable provider trace from the failed Turn:\n");
                trace.forEach(event -> prompt.append(event).append('\n'));
            }
            prompt.append("\n\nProtocol failure evidence:\n")
                    .append(context.errorMessage())
                    .append("\n\nRetry instruction:\n")
                    .append(BRAIN_RETRY_INSTRUCTION);
            launch.put("prompt", prompt.toString());
            launch.remove(List.of(
                    "resumeSessionId", "fallbackPrompt",
                    "priorCumulativeInputTokens",
                    "priorCumulativeOutputTokens"));
            JsonNode endpointNode = launch.get("toolEndpoint");
            if (!(endpointNode instanceof ObjectNode endpoint)
                    || !"TASK_BRAIN_READ_ONLY".equals(
                            endpoint.path("profile").asText())) {
                throw new IllegalStateException(
                        "Stored Development Brain has no read-only Task endpoint");
            }
            endpoint.put("url", "http://127.0.0.1:" + serverPort
                    + "/api/v2/task-turns/" + turnId
                    + "/operations/" + operationId + "/mcp");
            endpoint.put("ownerKind", "TASK_TURN");
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            int executionAttempt = context.semanticAttempt() + 1;
            return new BrainProtocolRetry(
                    episodeId, turnId, operationId, ticketId,
                    context.taskBrainId(), context.episodeId(),
                    context.taskId(), context.taskEpoch(), context.stageId(),
                    context.stageGeneration(), context.devReportId(),
                    context.validationEvidenceId(), context.codeFingerprint(),
                    context.headSha(), context.baseSha(), executionAttempt,
                    context.budgetAttempt(), context.deliveryLane(),
                    context.laneMask(), write(launch), context.workspaceId(),
                    context.trunkId(), now);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Stored failed Development Brain launch is invalid", e);
        }
    }

    private BrainResultRepair admitBrainResultRepair(
            BrainResultRepairSource source,
            String actor,
            String reason,
            Instant now)
    {
        BrainResultRepair repair = createBrainResultRepair(source, now);
        store.insertBrainResultRepairTurn(repair);
        CommandResult<TaskManager.State> admitted =
                tasks.requestBrainReviewInCommand(
                        new TaskManager.BrainReviewRequestCommand(
                                id("request-development-brain-result-repair",
                                        source.sourceFailureId()),
                                actor, source.taskId(), source.taskEpoch(),
                                source.taskVersion(), repair.episodeId(),
                                repair.fence()));
        if (admitted.disposition() != CommandResult.Disposition.APPLIED) {
            throw new IllegalStateException(
                    "Development Brain result repair was superseded");
        }
        store.recordBrainResultRepair(repair, reason);
        return repair;
    }

    private BrainResultRepair createBrainResultRepair(
            BrainResultRepairSource source, Instant now)
    {
        requireNonNull(source.malformedOutput(), "malformedOutput is null");
        String repairId = id(
                "development-brain-result-repair", source.sourceFailureId());
        String episodeId = id(
                "development-brain-result-repair-episode",
                source.sourceFailureId());
        String turnId = id(
                "development-brain-result-repair-turn",
                source.sourceFailureId());
        String operationId = id(
                "development-brain-result-repair-operation",
                source.sourceFailureId());
        String ticketId = id(
                "development-brain-result-repair-ticket",
                source.sourceFailureId());
        String requiredResultShape = BRAIN_RESULT_SHAPE;
        try {
            JsonNode stored = json.readTree(source.sourceLaunchInput());
            if (!(stored instanceof ObjectNode prior)) {
                throw new IllegalStateException(
                        "Stored Development Brain launch is not an object");
            }
            if (!source.worktreePath().equals(
                    prior.path("workingDirectory").asText())) {
                throw new IllegalStateException(
                        "Stored Development Brain worktree changed");
            }
            ObjectNode launch = json.createObjectNode();
            launch.put("schemaVersion", 1);
            launch.put("transport", required(
                    prior.path("transport").asText(), "transport"));
            launch.put("provider", required(
                    prior.path("provider").asText(), "provider"));
            if (prior.hasNonNull("credentialAccount")) {
                launch.put("credentialAccount",
                        prior.path("credentialAccount").asText());
            }
            launch.put("model", required(prior.path("model").asText(), "model"));
            if (prior.hasNonNull("reasoningEffort")) {
                launch.put("reasoningEffort",
                        prior.path("reasoningEffort").asText());
            }
            launch.put("workingDirectory", source.worktreePath());
            launch.put("systemPrompt",
                    "You repair the syntax of one frozen Development Brain "
                            + "response. Do not inspect or modify the worktree, "
                            + "call tools, ask questions, or add new review "
                            + "judgment. Preserve the response's intended "
                            + "meaning and return only the required JSON object.");
            String frozen = json.writeValueAsString(source.malformedOutput());
            launch.put("prompt",
                    "Reconstruct the malformed response below as exactly one "
                            + "strict JSON object with this shape:\n"
                            + requiredResultShape
                            + "\n\nverdict must be APPROVED or CHANGES_REQUESTED. "
                            + "APPROVED requires an empty findings array; "
                            + "CHANGES_REQUESTED requires one or more non-blank "
                            + "finding strings. Do not add facts or perform a new "
                            + "review.\n\nFrozen malformed output encoded as one "
                            + "exact JSON string:\n" + frozen + "\n\n"
                            + RAW_JSON_OBJECT_BOUNDARY);
            ObjectNode endpoint = launch.putObject("toolEndpoint");
            endpoint.put("serverName", "bytequay");
            endpoint.put("url", "http://127.0.0.1:" + serverPort
                    + "/api/v2/task-turns/" + turnId
                    + "/operations/" + operationId + "/mcp");
            endpoint.put("ownerKind", "TASK_TURN");
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            endpoint.put("profile", "TASK_BRAIN_READ_ONLY");
            endpoint.put("approvalPromptTool",
                    "mcp__bytequay__approval_prompt");
            int executionAttempt = source.semanticAttempt() + 1;
            return new BrainResultRepair(
                    repairId, source.predecessorFailureId(),
                    source.sourceFailureId(),
                    source.sourceBlockerId(), source.sourceTurnId(),
                    source.sourceOperationId(), source.sourceRawResultDigest(),
                    source.malformedOutput(), requiredResultShape, episodeId,
                    turnId, operationId, ticketId,
                    source.taskBrainId(), source.sourceEpisodeId(),
                    source.taskId(), source.taskEpoch(), source.stageId(),
                    source.stageGeneration(), source.devReportId(),
                    source.validationEvidenceId(), source.codeFingerprint(),
                    source.headSha(), source.baseSha(), executionAttempt,
                    source.budgetAttempt(), source.deliveryLane(),
                    source.laneMask(), write(launch), source.workspaceId(),
                    source.trunkId(), now);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Stored Development Brain launch is invalid", e);
        }
    }

    private CommandResult<StageManager.State> requestRetryInCommand(
            StageTurnRetryContext context,
            StageTurnRetry retry,
            StageManager.Command command)
    {
        return switch (context.requestKind()) {
            case "IMPLEMENTATION" -> {
                requireCheckpoint(context, StageCheckpoint.IMPLEMENTING);
                yield local.requestImplementationInCommand(
                        command, retry.fence(), retry.requestId());
            }
            case "BRAIN_FINDINGS" -> {
                requireCheckpoint(
                        context, StageCheckpoint.ADDRESSING_BRAIN_FINDINGS);
                yield local.requestBrainFixInCommand(
                        command, retry.fence(), retry.requestId());
            }
            case "LOCAL_FEEDBACK" -> {
                requireCheckpoint(
                        context, StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK);
                yield local.requestLocalFeedbackFixInCommand(
                        command, retry.fence(), retry.requestId());
            }
            case "BASE_SYNC" -> {
                requireCheckpoint(context, StageCheckpoint.IMPLEMENTING);
                yield local.requestImplementationInCommand(
                        command, retry.fence(), retry.requestId());
            }
            case SIMPLIFY_KIND -> {
                requireCheckpoint(context, StageCheckpoint.IMPLEMENTING);
                yield local.requestImplementationInCommand(
                        command, retry.fence(), retry.requestId());
            }
            case "STEERING" -> switch (context.checkpoint()) {
                case IMPLEMENTING -> local.requestImplementationInCommand(
                        command, retry.fence(), retry.requestId());
                case ADDRESSING_BRAIN_FINDINGS -> local.requestBrainFixInCommand(
                        command, retry.fence(), retry.requestId());
                case ADDRESSING_LOCAL_FEEDBACK ->
                        local.requestLocalFeedbackFixInCommand(
                                command, retry.fence(), retry.requestId());
                default -> throw new IllegalStateException(
                        "Failed Local steering is not owned at "
                                + context.checkpoint());
            };
            default -> throw new IllegalStateException(
                    "Unknown failed Local StageTurn request kind: "
                            + context.requestKind());
        };
    }

    private static void requireCheckpoint(
            StageTurnRetryContext context, StageCheckpoint expected)
    {
        if (context.checkpoint() != expected) {
            throw new IllegalStateException(
                    "Failed Local StageTurn is not owned at "
                            + context.checkpoint());
        }
    }

    private static void requireSameRetry(
            String failedTurnId,
            String blockerId,
            String actor,
            String reason,
            StageTurnRetryReceipt receipt)
    {
        if (!receipt.failedTurnId().equals(failedTurnId)
                || !receipt.blockerId().equals(blockerId)
                || !receipt.actor().equals(actor)
                || !receipt.reason().equals(reason)) {
            throw new IllegalArgumentException(
                    "Local StageTurn retry command id names another request");
        }
    }

    private static void requireSameBrainRetry(
            String failedTurnId,
            String blockerId,
            String actor,
            String reason,
            BrainProtocolRetryReceipt receipt)
    {
        if (!receipt.failedTurnId().equals(failedTurnId)
                || !receipt.blockerId().equals(blockerId)
                || !receipt.actor().equals(actor)
                || !receipt.reason().equals(reason)) {
            throw new IllegalArgumentException(
                    "Development Brain retry command id names another request");
        }
    }

    private String failurePayload(
            AgentTurnOwnerResultCodec.OwnerResult result, String error)
    {
        ObjectNode failure = json.createObjectNode();
        failure.put("schema", "LOCAL_STAGE_TURN_FAILURE_V1");
        failure.put("stageTurnId", result.owner().id());
        failure.put("operationId", result.fence().operationId());
        failure.put("outcome", result.outcome().name());
        failure.put("provider", result.payload().provider());
        failure.put("disposition", result.payload().disposition().name());
        failure.put("message", error);
        return write(failure);
    }

    private static String failureDetail(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        String error = result.payload().error();
        String finalText = result.payload().finalText();
        boolean generic = error == null || error.isBlank()
                || "turn failed".equalsIgnoreCase(error.trim())
                || "agent turn failed".equalsIgnoreCase(error.trim());
        if (generic && finalText != null && !finalText.isBlank()) {
            return finalText.trim();
        }
        if (error != null && !error.isBlank()) {
            return error.trim();
        }
        return "Local StageTurn ended " + result.outcome();
    }

    private static boolean isBudgetExhaustion(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        return result.outcome() != SUCCEEDED
                && "BRAIN_BUDGET_EXHAUSTED".equals(result.payload().error());
    }

    private BrainReviewRequest createBrainReview(
            ValidationContext context, ValidationEvidence evidence, Instant now)
    {
        String episodeId = id("development-brain-episode", evidence.id());
        String turnId = id("development-brain-turn", evidence.id());
        String operationId = id("development-brain-operation", evidence.id());
        String ticketId = id("development-brain-ticket", evidence.id());
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        model = taskEffort(context.trunkId(), context.taskId(), model);
        if (!context.provider().equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !context.model().equals(model.model())) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model do not identify one engine");
        }
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", lane);
        launch.put("provider", context.provider());
        putNullable(launch, "credentialAccount", model.account());
        launch.put("model", context.model());
        putNullable(launch, "reasoningEffort", model.reasoningEffort());
        launch.put("workingDirectory", context.worktreePath());
        launch.put("systemPrompt", brainSystemPrompt(context.roleSkill()));
        launch.put("prompt", brainPrompt(context));
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/task-turns/" + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", "TASK_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "TASK_BRAIN_READ_ONLY");
        endpoint.put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return store.insertBrainReview(
                context, evidence, turnId, operationId, ticketId, episodeId,
                lane, laneMask, write(launch), now);
    }

    private static String brainPrompt(ValidationContext context)
    {
        return "Review the implementation against its intent and the current "
                + "worktree. " + BRAIN_VERDICT_INSTRUCTION
                + "\n\nIntent:\n" + context.implementedIntent()
                + "\n\nFiles:\n" + context.fileSummary()
                + "\n\nValidation:\n" + context.validationSummary()
                + "\n\nKnown risks:\n" + context.knownRisks()
                + "\n\nUnresolved concerns:\n" + context.unresolvedConcerns();
    }

    private static String brainSystemPrompt(String roleSkill)
    {
        String base = "You are the read-only Task Brain reviewing one exact "
                + "Local Development code subject. Do not modify files or create "
                + "remote effects. " + BRAIN_VERDICT_INSTRUCTION;
        return roleSkill == null || roleSkill.isBlank()
                ? base
                : base + "\n\nRole skill:\n" + roleSkill;
    }

    private LocalValidationOperationHandler.ValidationResult decodeValidationResult(
            DispatchTicket.DispatchResult rawResult)
    {
        if (!Objects.equals(rawResult.payloadJson(), rawResult.evidenceJson())) {
            throw new IllegalArgumentException(
                    "Validation payload and evidence must be identical typed values");
        }
        try {
            return validationResultReader.readValue(rawResult.payloadJson());
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Local validation result is invalid", e);
        }
    }

    private static void requireValidationResult(
            ValidationContext context,
            LocalValidationOperationHandler.ValidationResult result)
    {
        if (!context.validationOperationId().equals(result.validationOperationId())
                || !context.operationId().equals(result.operationId())
                || !context.taskId().equals(result.taskId())
                || context.taskEpoch() != result.taskEpoch()
                || !context.stageId().equals(result.stageId())
                || context.stageGeneration() != result.stageGeneration()
                || context.semanticAttempt() != result.semanticAttempt()
                || result.subjectCurrent() && (
                    !context.codeFingerprint().equals(
                            result.observedCodeFingerprint())
                    || !context.headSha().equals(result.observedHeadSha())
                    || !context.baseSha().equals(result.observedBaseSha()))) {
            throw new IllegalArgumentException(
                    "Local validation result does not match its exact operation");
        }
    }

    private InitialTurn initialTurn(
            InitialContext context,
            String requestId,
            String commandId,
            String turnId,
            String operationId,
            String ticketId,
            String prompt,
            Instant requestedAt)
    {
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        model = stageEffort(context.trunkId(), context.taskId(),
                context.stageId(), model);
        if (!context.provider().equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !context.model().equals(model.model())
                || model.kind() == WorkModelKind.CLI && model.account() != null) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model do not identify one engine");
        }
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", lane);
        launch.put("provider", context.provider());
        putNullable(launch, "credentialAccount", model.account());
        launch.put("model", context.model());
        putNullable(launch, "reasoningEffort", model.reasoningEffort());
        launch.put("workingDirectory", context.worktreePath());
        launch.put("systemPrompt", implementationSystemPrompt(context.roleSkill()));
        launch.put("prompt", prompt);
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/stage-turns/" + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", "STAGE_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "STAGE_DEVELOPMENT");
        endpoint.put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return new InitialTurn(
                requestId, commandId, turnId, operationId, ticketId,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                lane, laneMask, write(launch), digest(prompt), ACTOR, requestedAt);
    }

    /**
     * The approved plan as prose the implementing agent can read.
     *
     * <p>Plans are stored structured now, and inlining that JSON verbatim put
     * a wall of braces between the agent and the instructions that follow it —
     * including the one naming the tool the Turn is accepted on. Renders the
     * fields the plan actually carries; anything unparseable (a revision
     * recorded before the structured protocol) passes through as written.
     */
    private String readablePlan(String content)
    {
        JsonNode plan;
        try {
            plan = json.readTree(content);
        }
        catch (JsonProcessingException notStructured) {
            return content;
        }
        if (plan == null || !plan.isObject()) {
            return content;
        }
        StringBuilder out = new StringBuilder();
        appendLine(out, "Goal", plan.path("goal").asText(""));
        appendLine(out, "Understanding",
                plan.path("understanding").path("summary").asText(""));
        appendLine(out, "Intent", plan.path("intent").path("summary").asText(""));
        JsonNode steps = plan.path("intent").path("steps");
        if (steps.isArray() && !steps.isEmpty()) {
            out.append("\nSteps:\n");
            int ordinal = 0;
            for (JsonNode step : steps) {
                out.append(++ordinal).append(". ")
                        .append(step.path("action").asText("")).append('\n');
                String files = String.join(", ", textValues(step.path("files")));
                if (!files.isBlank()) {
                    out.append("   files: ").append(files).append('\n');
                }
                String rationale = step.path("rationale").asText("");
                if (!rationale.isBlank()) {
                    out.append("   why: ").append(rationale).append('\n');
                }
            }
        }
        appendLine(out, "Validation",
                plan.path("intent").path("validationStrategy").asText(""));
        List<String> outOfScope = textValues(plan.path("outOfScope"));
        if (!outOfScope.isEmpty()) {
            out.append("\nOut of scope:\n");
            outOfScope.forEach(item -> out.append("- ").append(item).append('\n'));
        }
        return out.toString().strip();
    }

    private static void appendLine(StringBuilder out, String label, String value)
    {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
        }
    }

    private static List<String> textValues(JsonNode array)
    {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    private String implementationPrompt(InitialContext context)
    {
        return "Implement this approved plan in the checked-out Task worktree:\n\n"
                + readablePlan(context.planContent())
                // The commit must be asked for explicitly. Requesting only a
                // `commitSummary` while forbidding "remote effects" read as a
                // blanket ban on committing, and the Turn returned work that
                // existed solely in the worktree.
                + "\n\nCommit your work on the current Task branch as one small "
                + "commit. Committing is local and required; the prohibition "
                + "below is only about the remote. "
                + "Do not push or create remote effects.\n\n"
                // Design 3.36: the agent that reads the repository's template
                // writes the body, so the description matches that template by
                // construction rather than through a mapping we would have to
                // keep in sync.
                + "Write the pull-request body into the tool's pr_description "
                + "argument. First look for "
                + "the repository's template (.github/PULL_REQUEST_TEMPLATE.md, "
                + "a root or docs PULL_REQUEST_TEMPLATE, or a file under "
                + ".github/PULL_REQUEST_TEMPLATE/). If one exists, fill in that "
                + "template's own sections and keep its headings. If none "
                + "exists, write a short body with a Summary section and a "
                + "Validation section.\n\n"
                + DEVELOPMENT_RESULT_INSTRUCTION;
    }

    private static String publishBaseSyncPrompt(Result rebase)
    {
        String action;
        if (rebase.disposition() == Disposition.CONFLICT) {
            String paths = rebase.evidence().conflictPaths().isEmpty()
                    ? "(Git reported conflicts without named paths)"
                    : String.join("\n", rebase.evidence().conflictPaths());
            action = "Git proved that rebasing the Task commits onto the exact "
                    + "new base " + rebase.targetBaseSha() + " conflicts. "
                    + "Perform that rebase in the checked-out Task worktree, "
                    + "resolve every conflict while preserving the approved Task "
                    + "intent, and leave a clean committed branch based on that "
                    + "exact target. Conflicting paths:\n" + paths;
        }
        else {
            action = "The Task commits were mechanically rebased onto the exact "
                    + "new base " + rebase.targetBaseSha() + ". Inspect the "
                    + "checked-out result and confirm that it still preserves the "
                    + "approved Task intent. Make a fix commit only if the base "
                    + "change requires one; otherwise keep the rebased commits as-is.";
        }
        return action + "\n\nRun the appropriate local validation. Do not push, "
                + "publish, merge, or modify another Task. "
                + DEVELOPMENT_RESULT_INSTRUCTION;
    }

    private static String implementationSystemPrompt(String roleSkill)
    {
        String base = "You are the code-writing Stage owner for V2 Local Development. "
                + "Work only in the supplied Task worktree and implement the approved "
                + "plan. Do not push, publish, merge, or mutate another Task. "
                + DEVELOPMENT_RESULT_INSTRUCTION;
        return roleSkill == null || roleSkill.isBlank()
                ? base
                : base + "\n\nRole skill:\n" + roleSkill;
    }

    private WorkModel decodeWorkModel(String value)
    {
        try {
            return workModelReader.readValue(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Frozen work model is invalid", e);
        }
    }

    private WorkModel taskEffort(
            String trunkId, String taskId, WorkModel frozen)
    {
        return reasoningEfforts == null
                ? frozen
                : reasoningEfforts.forTask(trunkId, taskId, frozen);
    }

    private WorkModel stageEffort(
            String trunkId, String taskId, String stageId, WorkModel frozen)
    {
        return reasoningEfforts == null
                ? frozen
                : reasoningEfforts.forStage(
                        trunkId, taskId, stageId, frozen);
    }

    private CommandResult<StageManager.State> acceptCodeResult(
            StageTurnContext context, String reportId)
    {
        StageManager.ResultCommand command = resultCommand(
                context, id("accept-local-code", context.operationId()));
        return switch (context.requestKind()) {
            case "IMPLEMENTATION", "BASE_SYNC", SIMPLIFY_KIND ->
                    local.acceptImplementationResultInCommand(command, reportId);
            case "STEERING" -> switch (context.checkpoint()) {
                case IMPLEMENTING ->
                        local.acceptImplementationResultInCommand(command, reportId);
                case ADDRESSING_BRAIN_FINDINGS ->
                        local.acceptBrainFixResultInCommand(command, reportId);
                case ADDRESSING_LOCAL_FEEDBACK ->
                        local.acceptLocalFeedbackResultInCommand(command, reportId);
                default -> throw new IllegalStateException(
                        "Steering result is not owned at " + context.checkpoint());
            };
            case "BRAIN_FINDINGS" ->
                    local.acceptBrainFixResultInCommand(command, reportId);
            case "LOCAL_FEEDBACK" ->
                    local.acceptLocalFeedbackResultInCommand(command, reportId);
            default -> throw new IllegalStateException(
                    "Unknown Local StageTurn request kind: " + context.requestKind());
        };
    }

    private CommandResult<StageManager.State> clearResult(StageTurnContext context)
    {
        StageManager.ResultCommand command = resultCommand(
                context, id("clear-local-code", context.operationId()));
        return switch (context.requestKind()) {
            case "IMPLEMENTATION", "BASE_SYNC", SIMPLIFY_KIND ->
                    local.clearImplementationTurnInCommand(command, context.requestId());
            case "STEERING" -> switch (context.checkpoint()) {
                case IMPLEMENTING ->
                        local.clearImplementationTurnInCommand(
                                command, context.requestId());
                case ADDRESSING_BRAIN_FINDINGS ->
                        local.clearBrainFixTurnInCommand(command, context.requestId());
                case ADDRESSING_LOCAL_FEEDBACK ->
                        local.clearLocalFeedbackTurnInCommand(
                                command, context.requestId());
                default -> throw new IllegalStateException(
                        "Steering result is not owned at " + context.checkpoint());
            };
            case "BRAIN_FINDINGS" ->
                    local.clearBrainFixTurnInCommand(command, context.requestId());
            case "LOCAL_FEEDBACK" ->
                    local.clearLocalFeedbackTurnInCommand(command, context.requestId());
            default -> throw new IllegalStateException(
                    "Unknown Local StageTurn request kind: " + context.requestKind());
        };
    }

    private static StageManager.ResultCommand resultCommand(
            StageTurnContext context, String commandId)
    {
        return new StageManager.ResultCommand(
                commandId, ACTOR, context.taskId(), context.fence());
    }

    private static StageManager.Command stageCommand(
            StageTurnContext context, String commandId, long stageVersion)
    {
        return new StageManager.Command(
                commandId, ACTOR, context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(), stageVersion);
    }

    private static String steeringPrompt(
            Request request,
            List<SqliteStageSteeringStore.Attachment> attachments)
    {
        StringBuilder prompt = new StringBuilder(
                "Apply this user steering to the current exact Local Development "
                        + "subject:\n\n").append(request.body());
        if (!attachments.isEmpty()) {
            prompt.append("\n\nRead these durable image attachments:\n");
            attachments.forEach(attachment -> prompt
                    .append("- ").append(attachment.contentRef()).append('\n'));
        }
        return prompt.append(
                "\nDo not push or create remote effects. "
                        + DEVELOPMENT_RESULT_INSTRUCTION).toString();
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String result)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "LOCAL_STAGE_DELIVERY_V1");
        node.put("result", result);
        return new DispatchTicket.DeliveryReceipt(acceptance, write(node));
    }

    public record SteeringAdmission(
            String turnId, String operationId, String ticketId) {}

    private static String deliveryResult(StageTurnDeliveryReceipt receipt)
    {
        return receipt.acceptance() + ":" + receipt.operationId();
    }

    private static ResultFence toResultFence(
            DispatchTicket.OperationFence fence)
    {
        return new ResultFence(
                requireNonNull(fence.taskEpoch(), "taskEpoch is null"),
                fence.stageId(), requireNonNull(
                        fence.stageGeneration(), "stageGeneration is null"),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Local protocol JSON", e);
        }
    }

    private static void putNullable(ObjectNode node, String field, String value)
    {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        }
        else {
            node.put(field, value);
        }
    }

    private static String required(String value, String name)
    {
        requireText(value, name);
        return value;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
