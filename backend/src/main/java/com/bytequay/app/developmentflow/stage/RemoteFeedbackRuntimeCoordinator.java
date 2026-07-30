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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.OutputCodeSubject;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RuntimeDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.BrainContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.BrainRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.FeedbackContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.NewBrain;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.NewTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ReplyDraft;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.StageTurnContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.TurnRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ValidationAttempt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ValidationContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ValidationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Attachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Remote-owned feedback repair, canonical validation, and Task Brain loop. */
public final class RemoteFeedbackRuntimeCoordinator
{
    public static final String TURN_CALLBACK = "REMOTE_FEEDBACK_TURN_RESULT";
    public static final String VALIDATION_CALLBACK =
            "REMOTE_FEEDBACK_VALIDATION_RESULT";
    public static final String BRAIN_CALLBACK = "REMOTE_FEEDBACK_BRAIN_RESULT";

    private static final String ACTOR = "v2-remote-feedback";

    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final RemoteDevelopmentStageManager remote;
    private final SqliteRemoteDevelopmentRuntimeStore remoteStore;
    private final SqliteRemoteFeedbackLoopStore store;
    private final ObjectMapper json;
    private final ObjectReader repairReader;
    private final ObjectReader validationReader;
    private final ObjectReader brainReader;
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;
    private SqliteStageSteeringStore steering;

    public RemoteFeedbackRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteDevelopmentRuntimeStore remoteStore,
            SqliteRemoteFeedbackLoopStore store,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.remoteStore = requireNonNull(remoteStore, "remoteStore is null");
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.repairReader = strictReader(RepairResult.class);
        this.validationReader = strictReader(
                RemoteFeedbackValidationOperationHandler.ValidationResult.class);
        this.brainReader = strictReader(BrainResult.class);
        this.workModelReader = strictReader(WorkModel.class);
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

    public TurnRequest admitSteeringInCommand(Request request)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        SqliteStageSteeringStore ownerStore = requireNonNull(
                steering, "Stage steering store is not configured");
        var handoff = ownerStore.requireRemoteHandoff(request.id());
        if (!handoff.status().equals("PARKED")
                || !handoff.ownerFamily().equals("REMOTE_FEEDBACK")
                || !request.predecessor().purpose()
                        .equals("ADDRESS_REMOTE_FEEDBACK")) {
            throw new IllegalStateException(
                    "Remote feedback steering handoff is not exact");
        }
        StageTurnContext predecessor = store.requireStageTurnContext(
                request.predecessor().ownerId(),
                request.predecessor().operationId());
        store.supersedeUndeliveredStageTurn(predecessor, clock.instant());
        FeedbackContext context = store.requireFeedbackContext(
                predecessor.batchId());
        return createTurn(
                context, predecessor.semanticAttempt() + 1,
                predecessor.turnId(), steeringPrompt(
                        request, ownerStore.attachments(request.id())),
                request, ownerStore);
    }

    public TurnRequest start(String taskId, String batchId)
    {
        return commands.execute(taskId, () -> startInCommand(taskId, batchId));
    }

    public TurnRequest startInCommand(String taskId, String batchId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        TurnRequest duplicate = store.findTurn(batchId, 1).orElse(null);
        if (duplicate != null) {
            return duplicate;
        }
        FeedbackContext context = store.requireFeedbackContext(batchId);
        if (!taskId.equals(context.taskId()) || !"FROZEN".equals(context.batchStatus())) {
            throw new IllegalStateException("Remote feedback start is stale");
        }
        CommandResult<StageManager.State> begun = remote.beginRemoteFeedbackInCommand(
                new RemoteDevelopmentStageManager.FeedbackCommand(
                        stageCommand(context,
                                id("begin-remote-feedback", context.batchId())),
                        context.batchId(), context.sourceSnapshotId(),
                        context.contentDigest()));
        if (begun.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Remote feedback start was superseded");
        }
        store.markAddressing(batchId);
        return createTurn(
                store.requireFeedbackContext(batchId), 1, null,
                initialPrompt(context));
    }

    public DispatchTicket.DeliveryReceipt deliverStageTurn(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (result.owner().kind() != DispatchTicket.OwnerKind.STAGE_TURN
                || !TURN_CALLBACK.equals(result.owner().callbackRoute())) {
            return receipt(SUPERSEDED, "Remote StageTurn owner mismatch");
        }
        String taskId = store.requireStageTurnTaskId(
                result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverStageTurnInCommand(result));
    }

    public DispatchTicket.DeliveryReceipt deliverValidation(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(result, "result is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !VALIDATION_CALLBACK.equals(owner.callbackRoute())
                || !fence.equals(result.fence())) {
            return receipt(SUPERSEDED, "Remote validation owner mismatch");
        }
        String taskId = store.requireValidationTaskId(fence.operationId());
        return commands.execute(taskId, () ->
                deliverValidationInCommand(owner, fence, result));
    }

    public DispatchTicket.DeliveryReceipt deliverBrain(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (result.owner().kind() != DispatchTicket.OwnerKind.TASK_TURN
                || !BRAIN_CALLBACK.equals(result.owner().callbackRoute())) {
            return receipt(SUPERSEDED, "Remote Brain owner mismatch");
        }
        String taskId = store.requireBrainTaskId(
                result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverBrainInCommand(result));
    }

    private DispatchTicket.DeliveryReceipt deliverStageTurnInCommand(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        String rawDigest = digest(write(result));
        RuntimeDeliveryReceipt duplicate = duplicate(
                result.fence().operationId(), TURN_CALLBACK, rawDigest);
        if (duplicate != null) {
            return receipt(value(duplicate.acceptance()), duplicate.evidence());
        }
        StageTurnContext context = store.requireStageTurnContext(
                result.owner().id(), result.fence().operationId());
        if (!context.fence().equals(toFence(result.fence()))) {
            throw new IllegalArgumentException(
                    "Remote StageTurn result differs from its persisted fence");
        }
        Instant now = clock.instant();
        Request pendingSteering = steering == null ? null
                : steering.findPendingByPredecessor(context.operationId())
                        .orElse(null);
        if (pendingSteering != null) {
            return finishForPendingSteering(
                    result, context, rawDigest, pendingSteering, now);
        }
        if (result.outcome() != SUCCEEDED) {
            store.finishStageTurn(
                    context, result.outcome() == CANCELED ? "CANCELED" : "FAILED",
                    result.payload().error(), now);
            return record(context.operationId(), TURN_CALLBACK, rawDigest, ACCEPTED,
                    "Remote StageTurn ended " + result.outcome(), now);
        }
        if (!context.current()) {
            store.finishStageTurn(context, "SUPERSEDED", "stale Remote batch", now);
            return record(context.operationId(), TURN_CALLBACK, rawDigest,
                    SUPERSEDED, "Remote StageTurn subject is stale", now);
        }
        RepairResult resultValue = decodeRepair(result.payload().finalText());
        CodeSubject output = requireOutputCodeSubject(result, context);
        store.finishStageTurn(context, "SUCCEEDED", null, now);
        String repairId = id("remote-feedback-repair", context.operationId());
        List<ReplyDraft> drafts = resultValue.replies().stream()
                .map(reply -> replyDraft(repairId, reply))
                .toList();
        String validationId = id("remote-feedback-validation", repairId);
        String validationTicket = id("remote-feedback-validation-ticket", repairId);
        ValidationRequest validation = store.insertRepairAndValidation(
                context, repairId, output.headSha(), output.fingerprint(),
                required(resultValue.summary(), "summary"),
                digest(result.payload().finalText()), drafts,
                validationId, validationTicket, now);
        return record(context.operationId(), TURN_CALLBACK, rawDigest, ACCEPTED,
                "validation-requested:" + validation.operationId(), now);
    }

    private DispatchTicket.DeliveryReceipt finishForPendingSteering(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            StageTurnContext context,
            String rawDigest,
            Request request,
            Instant now)
    {
        boolean acceptOutput = request.mode() == V2StageSteeringControl.Mode.APPEND
                && raw.outcome() == SUCCEEDED && context.current();
        if (!acceptOutput) {
            store.finishStageTurn(
                    context, "SUPERSEDED",
                    "replaced by durable user steering", now);
            return record(context.operationId(), TURN_CALLBACK, rawDigest,
                    SUPERSEDED, "Remote feedback predecessor was superseded", now);
        }
        RepairResult result = decodeRepair(raw.payload().finalText());
        CodeSubject output = requireOutputCodeSubject(raw, context);
        store.finishStageTurn(context, "SUCCEEDED", null, now);
        String repairId = id("remote-feedback-repair", context.operationId());
        List<ReplyDraft> drafts = result.replies().stream()
                .map(reply -> replyDraft(repairId, reply))
                .toList();
        store.insertRepairForSteering(
                context, repairId, output.headSha(), output.fingerprint(),
                required(result.summary(), "summary"),
                digest(raw.payload().finalText()), drafts, now);
        return record(context.operationId(), TURN_CALLBACK, rawDigest,
                ACCEPTED, "Remote feedback predecessor completed before steering",
                now);
    }

    private DispatchTicket.DeliveryReceipt deliverValidationInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult raw)
    {
        String rawDigest = digest(write(raw));
        RuntimeDeliveryReceipt duplicate = duplicate(
                fence.operationId(), VALIDATION_CALLBACK, rawDigest);
        if (duplicate != null) {
            return receipt(value(duplicate.acceptance()), duplicate.evidence());
        }
        ValidationContext context = store.requireValidationContext(fence.operationId());
        if (!context.stageId().equals(owner.id())
                || !context.fence().equals(toFence(fence))) {
            throw new IllegalArgumentException(
                    "Remote validation result differs from its persisted fence");
        }
        Instant now = clock.instant();
        if (raw.outcome() != SUCCEEDED) {
            store.finishValidationWithoutEvidence(
                    context, raw.outcome() == CANCELED ? "CANCELED" : "FAILED",
                    raw.error(), now);
            return record(context.operationId(), VALIDATION_CALLBACK, rawDigest,
                    ACCEPTED, "Remote validation ended " + raw.outcome(), now);
        }
        RemoteFeedbackValidationOperationHandler.ValidationResult result =
                decodeValidation(raw);
        requireValidationResult(context, result);
        if (!context.current() || !result.subjectCurrent()) {
            store.finishValidationWithoutEvidence(
                    context, "SUPERSEDED", "stale Remote repair", now);
            return record(context.operationId(), VALIDATION_CALLBACK, rawDigest,
                    SUPERSEDED, "Remote validation subject is stale", now);
        }
        String failures = write(result.failures());
        ValidationAttempt attempt = store.completeValidation(
                context, result.passed(), failures, raw.evidenceJson(),
                Instant.ofEpochMilli(result.startedAtMs()),
                Instant.ofEpochMilli(result.completedAtMs()));
        String next;
        if (!result.passed()) {
            FeedbackContext feedback = store.requireFeedbackContext(
                    requireBatchId(context.repairResultId()));
            TurnRequest turn = createTurn(
                    feedback, context.semanticAttempt() + 1,
                    context.repairStageTurnId(),
                    "Fix every canonical validation failure for the same frozen "
                            + "Remote feedback batch:\n" + failures);
            next = "repair-requested:" + turn.turnId();
        }
        else if (brainRequired(context.repairResultId())) {
            BrainRequest brain = createBrain(context, attempt, now);
            FeedbackContext feedback = store.requireFeedbackContext(
                    requireBatchId(context.repairResultId()));
            CommandResult<TaskManager.State> requested =
                    tasks.requestBrainReviewInCommand(
                            new TaskManager.BrainReviewRequestCommand(
                                    id("request-remote-feedback-brain",
                                            brain.episodeId()),
                                    ACTOR, context.taskId(), context.taskEpoch(),
                                    feedback.taskVersion(), brain.episodeId(),
                                    brain.fence()));
            if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException(
                        "Remote feedback Brain request was superseded");
            }
            next = "brain-requested:" + brain.episodeId();
        }
        else {
            store.insertFinalValidation(context, attempt, raw.evidenceJson());
            store.moveAwaitingApproval(requireBatchId(context.repairResultId()));
            next = "awaiting-user-approval";
        }
        return record(context.operationId(), VALIDATION_CALLBACK, rawDigest,
                ACCEPTED, next, now);
    }

    private DispatchTicket.DeliveryReceipt deliverBrainInCommand(
            AgentTurnOwnerResultCodec.OwnerResult raw)
    {
        String rawDigest = digest(write(raw));
        RuntimeDeliveryReceipt duplicate = duplicate(
                raw.fence().operationId(), BRAIN_CALLBACK, rawDigest);
        if (duplicate != null) {
            return receipt(value(duplicate.acceptance()), duplicate.evidence());
        }
        BrainContext context = store.requireBrainContext(
                raw.owner().id(), raw.fence().operationId());
        if (!context.deliveryFence().equals(toFence(raw.fence()))) {
            throw new IllegalArgumentException(
                    "Remote Brain result differs from its persisted fence");
        }
        Instant now = clock.instant();
        TaskManager.ResultCommand taskCommand = new TaskManager.ResultCommand(
                id("accept-remote-feedback-brain", context.operationId()),
                ACTOR, context.taskId(), context.ownerFence());
        if (!context.current() || !tasks.isCurrentBrainResultInCommand(taskCommand)) {
            store.supersedeBrain(context, "stale Remote Brain subject", now);
            return record(context.operationId(), BRAIN_CALLBACK, rawDigest,
                    SUPERSEDED, "Remote Brain subject is stale", now);
        }
        if (raw.outcome() != SUCCEEDED) {
            throw new IllegalStateException(
                    "Remote Brain failed without a typed terminal decision");
        }
        BrainResult result = decodeBrain(raw.payload().finalText());
        TaskManager.BrainVerdict verdict = switch (result.verdict()) {
            case "APPROVED" -> TaskManager.BrainVerdict.APPROVED;
            case "CHANGES_REQUESTED" -> TaskManager.BrainVerdict.CHANGES_REQUESTED;
            default -> throw new IllegalArgumentException(
                    "Unknown Remote Brain verdict: " + result.verdict());
        };
        int findings = result.findings().size();
        if (verdict == TaskManager.BrainVerdict.APPROVED && findings != 0
                || verdict == TaskManager.BrainVerdict.CHANGES_REQUESTED
                    && findings == 0) {
            throw new IllegalArgumentException(
                    "Remote Brain verdict and findings disagree");
        }
        store.completeBrain(
                context, verdict.name(), findings,
                required(result.summary(), "summary"), now);
        TaskManager.BrainVerdictResult accepted =
                tasks.acceptBrainVerdictInCommand(taskCommand, verdict);
        if (accepted.task().disposition()
                == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Remote Brain verdict became stale");
        }
        String next;
        if (verdict == TaskManager.BrainVerdict.APPROVED) {
            store.insertFinalBrain(context, result.summary(), now);
            store.moveAwaitingApproval(context.batchId());
            next = "awaiting-user-approval";
        }
        else {
            FeedbackContext feedback = store.requireFeedbackContext(context.batchId());
            TurnRequest turn = createTurn(
                    feedback, context.semanticAttempt() + 1,
                    context.repairStageTurnId(),
                    "Address every Task Brain finding for this exact Remote "
                            + "feedback batch:\n" + String.join("\n", result.findings()));
            next = "repair-requested:" + turn.turnId();
        }
        return record(context.operationId(), BRAIN_CALLBACK, rawDigest,
                ACCEPTED, next, now);
    }

    private TurnRequest createTurn(
            FeedbackContext context,
            int attempt,
            String predecessorTurnId,
            String prompt)
    {
        return createTurn(
                context, attempt, predecessorTurnId, prompt, null, null);
    }

    private TurnRequest createTurn(
            FeedbackContext context,
            int attempt,
            String predecessorTurnId,
            String prompt,
            Request steeringRequest,
            SqliteStageSteeringStore steeringStore)
    {
        String requestId = id("remote-feedback-request",
                context.batchId() + ":" + attempt);
        String turnId = id("remote-feedback-turn",
                context.batchId() + ":" + attempt);
        String operationId = id("remote-feedback-operation",
                context.batchId() + ":" + attempt);
        String ticketId = id("remote-feedback-ticket",
                context.batchId() + ":" + attempt);
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        requireEngine(context, model);
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = launch(
                context, model, "STAGE_TURN", turnId, operationId,
                "STAGE_DEVELOPMENT", stageSystemPrompt(context.roleSkill()), prompt);
        if (steeringRequest != null) {
            SqliteStageSteeringStore ownerStore = requireNonNull(
                    steeringStore, "steeringStore is null");
            StageCliContinuity.freezeImages(
                    json, launch, ownerStore.attachments(steeringRequest.id()));
            applyCliContinuity(
                    launch, steeringRequest, context, model, prompt,
                    ownerStore);
        }
        else if (predecessorTurnId != null && steering != null) {
            StageCliContinuity.applyExact(
                    json, launch, predecessorTurnId, model.kind(), prompt,
                    steering, new StageCliContinuity.Fence(
                            context.stageId(), context.stageGeneration(),
                            context.codeFingerprint(), context.localHeadSha(),
                            context.baseSha(), context.provider(), context.model(),
                            context.worktreePath()));
        }
        return store.insertTurn(new NewTurn(
                requestId, context.batchId(), turnId, operationId, ticketId,
                attempt, predecessorTurnId, context.workspaceId(), context.trunkId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.localHeadSha(), context.baseSha(), lane, laneMask,
                write(launch), digest(prompt), ACTOR, clock.instant()));
    }

    private BrainRequest createBrain(
            ValidationContext validation, ValidationAttempt attempt, Instant now)
    {
        String batchId = requireBatchId(validation.repairResultId());
        FeedbackContext context = store.requireFeedbackContext(batchId);
        String episodeId = id("remote-feedback-brain-episode",
                validation.operationId());
        String turnId = id("remote-feedback-brain-turn", validation.operationId());
        String operationId = id(
                "remote-feedback-brain-operation", validation.operationId());
        String ticketId = id("remote-feedback-brain-ticket", validation.operationId());
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        requireEngine(context, model);
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        String prompt = "Review the current local repair against frozen Remote "
                + "feedback batch " + batchId + ". Return APPROVED only when every "
                + "item is addressed and every proposed reply is accurate.";
        ObjectNode launch = launch(
                context, model, "TASK_TURN", turnId, operationId,
                "TASK_BRAIN_READ_ONLY", brainSystemPrompt(context.roleSkill()),
                prompt);
        return store.insertBrain(new NewBrain(
                episodeId, batchId, attempt.id(), turnId, operationId, ticketId,
                validation.semanticAttempt(), context.workspaceId(),
                context.trunkId(), context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(),
                validation.codeFingerprint(), validation.headSha(),
                validation.baseSha(), lane, laneMask, write(launch), now));
    }

    private ObjectNode launch(
            FeedbackContext context,
            WorkModel model,
            String ownerKind,
            String turnId,
            String operationId,
            String profile,
            String systemPrompt,
            String prompt)
    {
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", model.kind().name());
        launch.put("provider", context.provider());
        putNullable(launch, "credentialAccount", model.account());
        launch.put("model", context.model());
        putNullable(launch, "reasoningEffort", model.reasoningEffort());
        launch.put("workingDirectory", context.worktreePath());
        launch.put("systemPrompt", systemPrompt);
        launch.put("prompt", prompt);
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/" + ("TASK_TURN".equals(ownerKind)
                    ? "task-turns/" : "stage-turns/") + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", ownerKind);
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", profile);
        endpoint.put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return launch;
    }

    private void applyCliContinuity(
            ObjectNode launch,
            Request request,
            FeedbackContext context,
            WorkModel model,
            String currentPrompt,
            SqliteStageSteeringStore ownerStore)
    {
        StageCliContinuity.apply(
                json, launch, request, model.kind(), currentPrompt, ownerStore,
                new StageCliContinuity.Fence(
                        context.stageId(), context.stageGeneration(),
                        context.codeFingerprint(), context.localHeadSha(),
                        context.baseSha(), context.provider(), context.model(),
                        context.worktreePath()));
    }

    private String initialPrompt(FeedbackContext context)
    {
        StringBuilder prompt = new StringBuilder(
                "Address this frozen Remote review batch. Modify only this Task "
                        + "worktree; do not push or post to GitHub. Prepare reply/resolve "
                        + "drafts for the user gate.\n");
        for (var item : context.items()) {
            prompt.append("\n#").append(item.ordinal()).append(' ')
                    .append(item.kind()).append(" target=")
                    .append(item.externalTarget()).append(" revision=")
                    .append(item.externalRevision()).append("\n")
                    .append(Objects.toString(item.body(), ""));
        }
        prompt.append("\n\nReturn strict JSON: {schemaVersion:1, summary:string, ")
                .append("replies:[{batchItemOrdinal,kind,body,externalTarget}]}. ")
                .append("kind is POST_INLINE_REPLY, POST_TOP_LEVEL_REPLY, or ")
                .append("RESOLVE_THREAD; RESOLVE_THREAD has null body.");
        return prompt.toString();
    }

    private static String steeringPrompt(
            Request request, List<Attachment> attachments)
    {
        StringBuilder prompt = new StringBuilder(
                "Continue the same exact frozen Remote feedback batch and apply "
                        + "this user steering before validation. Do not push or "
                        + "post to GitHub.\n\n")
                .append(request.body());
        if (!attachments.isEmpty()) {
            prompt.append("\n\nDurable attachments:\n");
            attachments.forEach(attachment -> prompt
                    .append("- ").append(attachment.contentRef()).append('\n'));
        }
        prompt.append("\n\nReturn the normal strict Remote feedback repair JSON.");
        return prompt.toString();
    }

    private ReplyDraft replyDraft(String repairId, ReplyResult reply)
    {
        if (reply.batchItemOrdinal() < 1
                || (!"POST_INLINE_REPLY".equals(reply.kind())
                    && !"POST_TOP_LEVEL_REPLY".equals(reply.kind())
                    && !"RESOLVE_THREAD".equals(reply.kind()))) {
            throw new IllegalArgumentException("Remote reply draft is invalid");
        }
        boolean resolve = "RESOLVE_THREAD".equals(reply.kind());
        if (resolve != (reply.body() == null)) {
            throw new IllegalArgumentException(
                    "Remote reply body does not match its effect kind");
        }
        if (!resolve) {
            required(reply.body(), "reply body");
        }
        int ordinal = reply.ordinal() == null
                ? reply.batchItemOrdinal() : reply.ordinal();
        return new ReplyDraft(
                id("remote-reply-draft", repairId + ":" + ordinal), ordinal,
                reply.batchItemOrdinal(), reply.kind(), reply.body(),
                resolve ? null : digest(reply.body()), reply.externalTarget());
    }

    private RepairResult decodeRepair(String value)
    {
        try {
            RepairResult result = repairReader.readValue(required(value, "repair result"));
            if (result.schemaVersion() != 1) {
                throw new IllegalArgumentException("Unsupported repair result version");
            }
            required(result.summary(), "summary");
            return result;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Remote repair result is not strict JSON", e);
        }
    }

    private RemoteFeedbackValidationOperationHandler.ValidationResult decodeValidation(
            DispatchTicket.DispatchResult raw)
    {
        if (!Objects.equals(raw.payloadJson(), raw.evidenceJson())) {
            throw new IllegalArgumentException(
                    "Remote validation payload and evidence must be identical");
        }
        try {
            return validationReader.readValue(raw.payloadJson());
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Remote validation result is invalid", e);
        }
    }

    private BrainResult decodeBrain(String value)
    {
        try {
            BrainResult result = brainReader.readValue(required(value, "Brain result"));
            if (result.schemaVersion() != 1
                    || result.findings().stream().anyMatch(
                            finding -> finding == null || finding.isBlank())) {
                throw new IllegalArgumentException("Remote Brain result is invalid");
            }
            return result;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Remote Brain result is not strict JSON", e);
        }
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

    private static void requireEngine(FeedbackContext context, WorkModel model)
    {
        if (!context.provider().equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !context.model().equals(model.model())) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model identify different engines");
        }
    }

    private static CodeSubject requireOutputCodeSubject(
            AgentTurnOwnerResultCodec.OwnerResult result,
            StageTurnContext context)
    {
        OutputCodeSubject output =
                result.requireOutputCodeSubject(context.baseSha());
        if (!output.clean()) {
            throw new IllegalStateException(
                    "Remote feedback Turn left uncommitted changes");
        }
        if (context.headSha().equals(output.headSha())) {
            throw new IllegalStateException(
                    "Remote feedback Turn did not create a new committed head");
        }
        return new CodeSubject(
                output.codeFingerprint(), output.headSha(), output.baseSha());
    }

    private RuntimeDeliveryReceipt duplicate(
            String operationId, String route, String rawDigest)
    {
        RuntimeDeliveryReceipt receipt = remoteStore.findRuntimeDeliveryReceipt(
                        operationId)
                .orElse(null);
        if (receipt != null && (!route.equals(receipt.callbackRoute())
                || !rawDigest.equals(receipt.rawResultDigest()))) {
            throw new IllegalStateException(
                    "Remote operation was delivered with different evidence");
        }
        return receipt;
    }

    private DispatchTicket.DeliveryReceipt record(
            String operationId,
            String route,
            String rawDigest,
            DispatchTicket.Acceptance acceptance,
            String evidence,
            Instant at)
    {
        remoteStore.insertRuntimeDeliveryReceipt(new RuntimeDeliveryReceipt(
                operationId, route, rawDigest, acceptance.name(), evidence, at));
        return receipt(acceptance, evidence);
    }

    private String requireBatchId(String repairId)
    {
        return store.requireFeedbackContextByRepair(repairId);
    }

    private boolean brainRequired(String repairId)
    {
        return store.requireBrainReviewRequired(repairId);
    }

    private ObjectReader strictReader(Class<?> type)
    {
        return json.readerFor(type)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static void requireValidationResult(
            ValidationContext context,
            RemoteFeedbackValidationOperationHandler.ValidationResult result)
    {
        if (!context.id().equals(result.validationOperationId())
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
                    "Remote validation result does not match its exact operation");
        }
    }

    private static StageManager.Command stageCommand(
            FeedbackContext context, String commandId)
    {
        return new StageManager.Command(
                commandId, ACTOR, context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(), context.stageVersion());
    }

    private static ResultFence toFence(DispatchTicket.OperationFence fence)
    {
        return new ResultFence(
                requireNonNull(fence.taskEpoch(), "task epoch is null"),
                requireNonNull(fence.stageId(), "stage id is null"),
                requireNonNull(fence.stageGeneration(), "stage generation is null"),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
    }

    private static DispatchTicket.Acceptance value(String value)
    {
        return DispatchTicket.Acceptance.valueOf(value);
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, acceptance.name() + ":" + evidence);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Remote evidence", e);
        }
    }

    private static String required(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
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

    private static String stageSystemPrompt(String roleSkill)
    {
        String base = "You own one Remote Development feedback repair. Edit only "
                + "the supplied Task worktree. Do not push or post reviewer-visible "
                + "content; return typed drafts for a later user authorization.";
        return roleSkill == null || roleSkill.isBlank()
                ? base : base + "\n\nRole skill:\n" + roleSkill;
    }

    private static String brainSystemPrompt(String roleSkill)
    {
        String base = "You are the read-only Task Brain reviewing one exact Remote "
                + "feedback repair. Do not edit files or perform remote effects.";
        return roleSkill == null || roleSkill.isBlank()
                ? base : base + "\n\nRole skill:\n" + roleSkill;
    }

    public record RepairResult(
            int schemaVersion, String summary, List<ReplyResult> replies)
    {
        public RepairResult
        {
            replies = List.copyOf(requireNonNull(replies, "replies is null"));
        }
    }

    public record ReplyResult(
            Integer ordinal,
            int batchItemOrdinal,
            String kind,
            String body,
            String externalTarget) {}

    public record BrainResult(
            int schemaVersion, String verdict, String summary,
            List<String> findings)
    {
        public BrainResult
        {
            findings = List.copyOf(requireNonNull(findings, "findings is null"));
        }
    }

    private record CodeSubject(String fingerprint, String headSha, String baseSha) {}
}
