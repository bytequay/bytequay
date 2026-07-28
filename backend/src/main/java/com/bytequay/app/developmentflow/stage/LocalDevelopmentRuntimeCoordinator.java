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
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.BrainFixTurn;
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
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore.ValidationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Path;
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

/** Local-owned initial implementation, StageTurn delivery, and validation arm. */
public final class LocalDevelopmentRuntimeCoordinator
{
    public static final String TURN_CALLBACK = "STAGE_TURN_RESULT";

    private static final String ACTOR = "v2-local-runtime";
    private final TaskCommandExecutor commands;
    private final LocalDevelopmentStageManager local;
    private final TaskManager tasks;
    private final SqliteLocalDevelopmentRuntimeStore store;
    private final CodeFingerprints fingerprints;
    private final GitRunner git;
    private final ObjectMapper json;
    private final ObjectReader workModelReader;
    private final ObjectReader developmentResultReader;
    private final ObjectReader validationResultReader;
    private final ObjectReader brainResultReader;
    private final Clock clock;
    private final int serverPort;
    private SqliteStageSteeringStore steering;
    private V2LocalReviewControl localReview;

    public LocalDevelopmentRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalDevelopmentStageManager local,
            SqliteLocalDevelopmentRuntimeStore store,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.local = requireNonNull(local, "local is null");
        this.store = requireNonNull(store, "store is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.git = requireNonNull(git, "git is null");
        this.json = requireNonNull(json, "json is null");
        this.workModelReader = json.readerFor(WorkModel.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.developmentResultReader = json.readerFor(DevelopmentResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validationResultReader = json.readerFor(
                        LocalValidationOperationHandler.ValidationResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.brainResultReader = json.readerFor(BrainResult.class)
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

    /** Materializes one already-durable steering request under its Task stripe. */
    public SteeringAdmission admitSteeringInCommand(Request request, long stageVersion)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        SqliteStageSteeringStore ownerStore = requireNonNull(
                steering, "Stage steering store is not configured");
        LocalContext context = ownerStore.requireLocalContext(request, stageVersion);
        String localRequestId = id("local-steering-request", request.id());
        String localCommandId = id("persist-local-steering", request.id());
        String turnId = id("local-steering-turn", request.id());
        String operationId = id("local-steering-operation", request.id());
        String ticketId = id("local-steering-ticket", request.id());
        String prompt = steeringPrompt(request, ownerStore.attachments(request.id()));
        WorkModel workModel = decodeWorkModel(context.workModelSnapshot());
        int laneMask = workModel.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(), workModel,
                context.worktreePath(), turnId, operationId, prompt);
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
        String prompt = steeringPrompt(request, ownerStore.attachments(request.id()));
        WorkModel workModel = decodeWorkModel(context.workModelSnapshot());
        int laneMask = workModel.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(), workModel,
                context.worktreePath(), turnId, operationId, prompt);
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
            store.finishStageTurn(
                    context, "SUPERSEDED", "replaced by durable user steering", now);
            clearResult(context);
            StageTurnDeliveryReceipt recorded = new StageTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    SUPERSEDED.name(), null, null, now);
            store.insertStageTurnReceipt(recorded);
            return receipt(SUPERSEDED, deliveryResult(recorded));
        }
        if (result.outcome() != SUCCEEDED) {
            String terminal = result.outcome() == CANCELED ? "CANCELED" : "FAILED";
            store.finishStageTurn(context, terminal, result.payload().error(), now);
            if (localReview != null && context.localFeedbackBatchId() != null) {
                localReview.rejectFeedbackResultInCommand(
                        context.taskId(), context.localFeedbackBatchId(),
                        context.turnId(), terminal, result.payload().error(),
                        context.isCurrent());
            }
            CommandResult<StageManager.State> cleared = clearResult(context);
            DispatchTicket.Acceptance acceptance = cleared.disposition()
                    == CommandResult.Disposition.SUPERSEDED ? SUPERSEDED : ACCEPTED;
            StageTurnDeliveryReceipt recorded = new StageTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    acceptance.name(), null, null, now);
            store.insertStageTurnReceipt(recorded);
            return receipt(acceptance, deliveryResult(recorded));
        }

        DevelopmentReport development = decodeDevelopmentResult(
                result.payload().finalText());
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

        CodeSubject output = observe(Path.of(context.worktreePath()), context.baseSha());
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
            BrainTurnDeliveryReceipt recorded = new BrainTurnDeliveryReceipt(
                    turnId, context.operationId(), result.outcome().name(), rawDigest,
                    SUPERSEDED.name(), context.episodeId(), null, null, null, now);
            store.insertBrainTurnReceipt(recorded);
            return receipt(SUPERSEDED,
                    SUPERSEDED.name() + ":" + context.operationId());
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
                if (acceptance == ACCEPTED && localReview != null) {
                    localReview.admitQueuedInCommand(context.taskId());
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

        BrainResult verdict = decodeBrainResult(result.payload().finalText());
        TaskManager.BrainVerdict domainVerdict = switch (verdict.verdict()) {
            case "APPROVED" -> TaskManager.BrainVerdict.APPROVED;
            case "CHANGES_REQUESTED" -> TaskManager.BrainVerdict.CHANGES_REQUESTED;
            default -> throw new IllegalArgumentException(
                    "Unknown Development Brain verdict: " + verdict.verdict());
        };
        int unresolved = verdict.findings().size();
        if (domainVerdict == TaskManager.BrainVerdict.APPROVED && unresolved != 0
                || domainVerdict == TaskManager.BrainVerdict.CHANGES_REQUESTED
                    && unresolved == 0) {
            throw new IllegalArgumentException(
                    "Development Brain verdict and findings disagree");
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
        BrainTurnDeliveryReceipt recorded = new BrainTurnDeliveryReceipt(
                turnId, context.operationId(), result.outcome().name(), rawDigest,
                ACCEPTED.name(), context.episodeId(), domainVerdict.name(), null,
                nextRequestId, now);
        store.insertBrainTurnReceipt(recorded);
        return receipt(ACCEPTED, ACCEPTED.name() + ":" + context.operationId());
    }

    private BrainFixTurn createBrainFixTurn(
            BrainTurnContext context,
            BrainResult verdict,
            Instant now)
    {
        String requestId = id("brain-fix-request", context.episodeId());
        String commandId = id("request-brain-fix", context.episodeId());
        String turnId = id("brain-fix-turn", context.episodeId());
        String operationId = id("brain-fix-operation", context.episodeId());
        String ticketId = id("brain-fix-ticket", context.episodeId());
        String prompt = "Address every finding from the Task Brain against this "
                + "exact code subject:\n\n" + String.join("\n", verdict.findings())
                + "\n\nReturn the same strict Local development result JSON.";
        WorkModel model = decodeWorkModel(context.workModelSnapshot());
        String lane = model.kind().name();
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(
                context.provider(), context.model(), context.roleSkill(),
                model, context.worktreePath(), turnId, operationId, prompt);
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

    private BrainResult decodeBrainResult(String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Development Brain result is missing");
        }
        try {
            BrainResult result = brainResultReader.readValue(value);
            if (result.schemaVersion() != 1) {
                throw new IllegalArgumentException(
                        "Unsupported Development Brain result version");
            }
            required(result.verdict(), "verdict");
            required(result.summary(), "summary");
            if (result.findings().stream().anyMatch(
                    finding -> finding == null || finding.isBlank())) {
                throw new IllegalArgumentException(
                        "Development Brain findings must be non-blank");
            }
            return result;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Development Brain result is not strict JSON", e);
        }
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
                + "worktree. Return the development Brain verdict through the "
                + "owner-scoped tool.\n\nIntent:\n" + context.implementedIntent()
                + "\n\nFiles:\n" + context.fileSummary()
                + "\n\nValidation:\n" + context.validationSummary()
                + "\n\nKnown risks:\n" + context.knownRisks()
                + "\n\nUnresolved concerns:\n" + context.unresolvedConcerns();
    }

    private static String brainSystemPrompt(String roleSkill)
    {
        String base = "You are the read-only Task Brain reviewing one exact "
                + "Local Development code subject. Do not modify files or create "
                + "remote effects.";
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

    private String implementationPrompt(InitialContext context)
    {
        return "Implement this approved plan in the checked-out Task worktree:\n\n"
                + context.planContent()
                + "\n\nDo not push or create remote effects. When finished, return only "
                + "strict JSON with schemaVersion=1 and these string fields: "
                + "implementedIntent, commitSummary, fileSummary, validationSummary, "
                + "knownRisks, unresolvedConcerns, contextRefs.";
    }

    private static String implementationSystemPrompt(String roleSkill)
    {
        String base = "You are the code-writing Stage owner for V2 Local Development. "
                + "Work only in the supplied Task worktree and implement the approved "
                + "plan. Do not push, publish, merge, or mutate another Task.";
        return roleSkill == null || roleSkill.isBlank()
                ? base
                : base + "\n\nRole skill:\n" + roleSkill;
    }

    private DevelopmentReport decodeDevelopmentResult(String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Local development result is missing");
        }
        try {
            DevelopmentResult decoded = developmentResultReader.readValue(value);
            if (decoded.schemaVersion() != 1) {
                throw new IllegalArgumentException(
                        "Unsupported Local development result version");
            }
            return new DevelopmentReport(
                    required(decoded.implementedIntent(), "implementedIntent"),
                    required(decoded.commitSummary(), "commitSummary"),
                    required(decoded.fileSummary(), "fileSummary"),
                    required(decoded.validationSummary(), "validationSummary"),
                    required(decoded.knownRisks(), "knownRisks"),
                    required(decoded.unresolvedConcerns(), "unresolvedConcerns"),
                    required(decoded.contextRefs(), "contextRefs"));
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Local development result is not strict JSON", e);
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

    private CodeSubject observe(Path worktree, String baseSha)
    {
        try {
            return new CodeSubject(
                    fingerprints.fingerprint(worktree), git.headSha(worktree), baseSha);
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not read Local worktree", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Local worktree inspection was interrupted", e);
        }
    }

    private CommandResult<StageManager.State> acceptCodeResult(
            StageTurnContext context, String reportId)
    {
        StageManager.ResultCommand command = resultCommand(
                context, id("accept-local-code", context.operationId()));
        return switch (context.requestKind()) {
            case "IMPLEMENTATION" ->
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
            case "IMPLEMENTATION" ->
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
                "\nDo not push or create remote effects. Return only strict JSON "
                        + "with schemaVersion=1 and string fields implementedIntent, "
                        + "commitSummary, fileSummary, validationSummary, knownRisks, "
                        + "unresolvedConcerns, contextRefs.").toString();
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

    private record DevelopmentResult(
            int schemaVersion,
            String implementedIntent,
            String commitSummary,
            String fileSummary,
            String validationSummary,
            String knownRisks,
            String unresolvedConcerns,
            String contextRefs) {}

    private record BrainResult(
            int schemaVersion,
            String verdict,
            String summary,
            List<String> findings)
    {
        private BrainResult
        {
            requireNonNull(findings, "findings is null");
            findings = List.copyOf(findings);
        }
    }
}
