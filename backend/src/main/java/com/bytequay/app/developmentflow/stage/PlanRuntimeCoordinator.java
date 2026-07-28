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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.BrainLaunchContext;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.EditedRevision;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.McpContext;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanCandidate;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanEditContext;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanEditReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanSubmission;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ProvisionContext;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ProvisionReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ReviewSubmission;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.TurnDeliveryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.TurnDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.TurnRequest;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static java.util.Objects.requireNonNull;

/** Task-owned provisioning acceptance and Plan TaskTurn orchestration. */
public final class PlanRuntimeCoordinator
{
    public static final String PROVISION_CALLBACK = "TASK_PROVISION_RESULT";
    public static final String TURN_CALLBACK = "TASK_TURN_RESULT";

    private static final String PLAN_DRAFT = "PLAN_DRAFT";
    private static final String PLAN_SELF_REVIEW = "PLAN_SELF_REVIEW";
    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final PlanStageManager plan;
    private final SqlitePlanRuntimeStore store;
    private final ObjectMapper json;
    private final ObjectReader provisionEvidenceReader;
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;

    public PlanRuntimeCoordinator(
            TaskCommandExecutor commands,
            TaskManager tasks,
            PlanStageManager plan,
            SqlitePlanRuntimeStore store,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.plan = requireNonNull(plan, "plan is null");
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.provisionEvidenceReader = json.readerFor(
                        ProvisionTaskOperationHandler.ProvisionEvidence.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.workModelReader = json.readerFor(WorkModel.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    public DispatchTicket.DeliveryReceipt deliverProvisioning(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TASK
                || !PROVISION_CALLBACK.equals(owner.callbackRoute())) {
            return receipt(SUPERSEDED, "provision owner mismatch");
        }
        if (!expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "raw provision fence is stale");
        }
        if (rawResult.outcome() != SUCCEEDED) {
            String rawDigest = dispatchResultDigest(rawResult);
            String error = rawResult.error() == null || rawResult.error().isBlank()
                    ? "Provisioning ended " + rawResult.outcome()
                    : rawResult.error();
            TaskManager.ProvisioningFailureResult failure = commands.execute(
                    owner.id(), () -> tasks.acceptProvisioningFailureInCommand(
                            new TaskManager.ProvisioningFailure(
                                    owner.id(), expectedFence.taskEpoch(),
                                    expectedFence.operationId(), expectedFence.attempt(),
                                    rawResult.outcome().name(), rawDigest, error,
                                    clock.instant().toEpochMilli())));
            return receipt(ACCEPTED, provisionFailureReceiptJson(failure));
        }

        ProvisionTaskOperationHandler.ProvisionEvidence evidence =
                decodeProvisionEvidence(rawResult.evidenceJson());
        ProvisionTaskOperationHandler.ProvisionEvidence payload =
                decodeProvisionEvidence(rawResult.payloadJson());
        if (!evidence.equals(payload)) {
            throw new IllegalArgumentException(
                    "Provision payload and evidence must be identical typed values");
        }
        requireProvisionEvidence(owner, expectedFence, evidence);

        ProvisionReceipt accepted = commands.execute(owner.id(), () ->
                acceptProvisioningInCommand(evidence, rawResult.evidenceJson()));
        return receipt(ACCEPTED, provisionReceiptJson(accepted));
    }

    public DispatchTicket.DeliveryReceipt deliverTaskTurn(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TASK_TURN
                || !TURN_CALLBACK.equals(owner.callbackRoute())
                || !owner.id().equals(required(owner.id(), "owner.id"))) {
            return receipt(SUPERSEDED, "Plan TaskTurn owner mismatch");
        }
        String taskId = store.findTurnDeliveryTaskId(
                        owner.id(), expectedFence.operationId())
                .orElse(null);
        if (taskId == null) {
            return receipt(REJECTED, "unknown Plan TaskTurn delivery owner");
        }
        String rawDigest = dispatchResultDigest(rawResult);
        TurnDeliveryReceipt delivered = commands.execute(taskId, () ->
                acceptTaskTurnInCommand(
                        owner.id(), expectedFence, rawResult, rawDigest));
        return receipt(
                DispatchTicket.Acceptance.valueOf(delivered.acceptance()),
                turnReceiptJson(delivered));
    }

    private TurnDeliveryReceipt acceptTaskTurnInCommand(
            String turnId,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest)
    {
        TaskCommandExecutor.requireCurrent(
                store.findTurnDeliveryTaskId(turnId, expectedFence.operationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Plan TaskTurn delivery owner disappeared")));
        TurnDeliveryReceipt duplicate = store.findTurnDeliveryReceipt(turnId)
                .orElse(null);
        if (duplicate != null) {
            if (!expectedFence.operationId().equals(duplicate.operationId())
                    || !rawResult.outcome().name().equals(duplicate.rawOutcome())
                    || !rawDigest.equals(duplicate.rawEvidenceDigest())) {
                throw new IllegalArgumentException(
                        "Plan TaskTurn was delivered with different raw evidence");
            }
            return duplicate;
        }

        TurnDeliveryContext context = store.requireTurnDelivery(
                turnId, expectedFence.operationId());
        if (!expectedFence.equals(context.operationFence())) {
            throw new IllegalStateException(
                    "Dispatcher expected fence differs from persisted Plan TaskTurn");
        }
        Instant now = clock.instant();
        if (!context.isCurrentPlan()) {
            store.finishTurn(context, "SUPERSEDED", "stale Plan result", now);
            return recordTurnDelivery(
                    context, rawResult, rawDigest, SUPERSEDED,
                    "SUPERSEDED", now);
        }
        if (!expectedFence.equals(rawResult.fence())) {
            String error = "TaskTurn returned a result with another operation fence";
            store.finishTurn(context, "FAILED", error, now);
            if (PLAN_SELF_REVIEW.equals(context.purpose())) {
                SqlitePlanRuntimeStore.ReviewOwner review =
                        store.requireReviewOwner(context.turnId());
                store.failSelfReview(context, error, now);
                store.openReviewFailure(
                        context, review.selfReviewId(), review.revisionId(),
                        review.reviewedDigest(), error, now);
                clearTerminalFence(
                        context, "PLAN_SELF_REVIEW_FAILED",
                        review.selfReviewId());
            }
            else {
                store.openTurnFailure(context, "OPERATION_FAILED", error, now);
                clearTerminalFence(
                        context, "PLAN_DRAFT_FAILED", context.turnId());
            }
            return recordTurnDelivery(
                    context, rawResult, rawDigest, ACCEPTED,
                    "PROTOCOL_BLOCKED", now);
        }
        return switch (rawResult.outcome()) {
            case SUCCEEDED -> acceptSuccessfulTurn(
                    context, rawResult, rawDigest, now);
            case FAILED, INDETERMINATE -> acceptFailedTurn(
                    context, rawResult, rawDigest, "FAILED", "TURN_FAILED", now);
            case CANCELED -> acceptFailedTurn(
                    context, rawResult, rawDigest, "CANCELED", "TURN_CANCELED", now);
        };
    }

    private TurnDeliveryReceipt acceptSuccessfulTurn(
            TurnDeliveryContext context,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            Instant now)
    {
        return switch (context.purpose()) {
            case PLAN_DRAFT -> acceptDraftTurn(context, rawResult, rawDigest, now);
            case PLAN_SELF_REVIEW -> acceptReviewTurn(
                    context, rawResult, rawDigest, now);
            default -> throw new IllegalStateException(
                    "Unknown Plan TaskTurn purpose: " + context.purpose());
        };
    }

    private TurnDeliveryReceipt acceptDraftTurn(
            TurnDeliveryContext context,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            Instant now)
    {
        PlanSubmission submission = store.findPlanSubmission(context.turnId())
                .orElse(null);
        if (submission == null) {
            store.finishTurn(
                    context, "FAILED", "successful Plan Turn recorded no revision", now);
            store.openTurnFailure(
                    context, "OPERATION_FAILED",
                    "Plan TaskTurn succeeded without record_plan", now);
            clearTerminalFence(
                    context, "PLAN_DRAFT_FAILED", context.turnId());
            return recordTurnDelivery(
                    context, rawResult, rawDigest, ACCEPTED,
                    "PROTOCOL_BLOCKED", now);
        }
        requireSubmission(context, submission);

        BrainLaunchContext brain = store.requireBrainLaunchContext(context.taskId());
        String reviewTurnId = id("plan-review-turn", submission.revisionId());
        String reviewOperationId = id(
                "plan-review-operation", submission.revisionId());
        TurnRequest review = turn(
                brain, context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), PLAN_SELF_REVIEW,
                reviewTurnId, reviewOperationId,
                id("plan-review-ticket", submission.revisionId()),
                reviewPrompt(context.taskId(), submission), now);
        store.finishTurn(context, "SUCCEEDED", null, now);
        store.insertTurn(review);
        String selfReviewId = id("plan-self-review", submission.revisionId());
        store.insertSelfReview(
                selfReviewId, submission.revisionId(), review.turnId(),
                context.taskEpoch(), submission.contentDigest(), now);
        CommandResult<StageManager.State> accepted =
                plan.acceptDraftedAndRequestSelfReviewInCommand(
                        new StageManager.ResultCommand(
                                id("deliver-plan-draft", context.turnId()),
                                "v2-plan-runtime", context.taskId(), context.fence()),
                        review.fence());
        requireApplied(accepted, "Plan draft result");
        return recordTurnDelivery(
                context, rawResult, rawDigest, ACCEPTED,
                "DRAFT_ACCEPTED", now);
    }

    private TurnDeliveryReceipt acceptReviewTurn(
            TurnDeliveryContext context,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            Instant now)
    {
        ReviewSubmission submission = store.findReviewSubmission(context.turnId())
                .orElse(null);
        store.finishTurn(context, "SUCCEEDED", null, now);
        if (submission == null) {
            SqlitePlanRuntimeStore.ReviewOwner owner =
                    store.requireReviewOwner(context.turnId());
            store.failSelfReview(
                    context, "successful self-review Turn recorded no verdict", now);
            store.openReviewFailure(
                    context, owner.selfReviewId(), owner.revisionId(),
                    owner.reviewedDigest(),
                    "Plan self-review completed without a typed verdict", now);
            clearTerminalFence(
                    context, "PLAN_SELF_REVIEW_NO_VERDICT",
                    owner.selfReviewId());
            return recordTurnDelivery(
                    context, rawResult, rawDigest, ACCEPTED,
                    "REVIEW_BLOCKED", now);
        }
        insertReviewFollowups(submission, context.turnId(), now);
        store.completeSelfReview(submission, now);

        return switch (submission.verdict()) {
            case "APPROVED" -> {
                CommandResult<StageManager.State> accepted =
                        plan.acceptSelfReviewApprovalInCommand(
                                new StageManager.ResultCommand(
                                        id("deliver-plan-review", context.turnId()),
                                        "v2-plan-runtime", context.taskId(),
                                        context.fence()));
                requireApplied(accepted, "Plan self-review approval");
                yield recordTurnDelivery(
                        context, rawResult, rawDigest, ACCEPTED,
                        "REVIEW_APPROVED", now);
            }
            case "CHANGES_REQUESTED" -> {
                BrainLaunchContext brain = store.requireBrainLaunchContext(
                        context.taskId());
                String draftTurnId = id(
                        "plan-redraft-turn", submission.selfReviewId());
                String draftOperationId = id(
                        "plan-redraft-operation", submission.selfReviewId());
                TurnRequest draft = turn(
                        brain, context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), context.codeFingerprint(),
                        context.headSha(), context.baseSha(), PLAN_DRAFT,
                        draftTurnId, draftOperationId,
                        id("plan-redraft-ticket", submission.selfReviewId()),
                        redraftPrompt(context.taskId(), submission), now);
                store.insertTurn(draft);
                CommandResult<StageManager.State> accepted =
                        plan.acceptSelfReviewFindingsAndRequestDraftInCommand(
                                new StageManager.ResultCommand(
                                        id("deliver-plan-review", context.turnId()),
                                        "v2-plan-runtime", context.taskId(),
                                        context.fence()),
                                draft.fence());
                requireApplied(accepted, "Plan self-review findings");
                yield recordTurnDelivery(
                        context, rawResult, rawDigest, ACCEPTED,
                        "REVIEW_FINDINGS", now);
            }
            case "BLOCKED" -> {
                store.openReviewFailure(
                        context, submission.selfReviewId(),
                        submission.revisionId(), submission.reviewedDigest(),
                        "Plan self-review reported a blocking condition", now);
                clearTerminalFence(
                        context, "PLAN_SELF_REVIEW_BLOCKED",
                        submission.selfReviewId());
                yield recordTurnDelivery(
                        context, rawResult, rawDigest, ACCEPTED,
                        "REVIEW_BLOCKED", now);
            }
            default -> throw new IllegalStateException(
                    "Unknown persisted self-review verdict: "
                            + submission.verdict());
        };
    }

    private TurnDeliveryReceipt acceptFailedTurn(
            TurnDeliveryContext context,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            String terminalStatus,
            String domainResult,
            Instant now)
    {
        String error = rawResult.error() == null || rawResult.error().isBlank()
                ? "Plan TaskTurn ended " + rawResult.outcome()
                : rawResult.error();
        store.finishTurn(context, terminalStatus, error, now);
        if (PLAN_SELF_REVIEW.equals(context.purpose())) {
            SqlitePlanRuntimeStore.ReviewOwner owner =
                    store.requireReviewOwner(context.turnId());
            if (owner.attempt() == 1
                    && rawResult.outcome() != DispatchTicket.Outcome.CANCELED) {
                return retrySelfReview(
                        context, owner, rawResult, rawDigest, error, now);
            }
            store.failSelfReview(context, error, now);
            store.openReviewFailure(
                    context, owner.selfReviewId(), owner.revisionId(),
                    owner.reviewedDigest(), error, now);
            clearTerminalFence(
                    context, "PLAN_SELF_REVIEW_FAILED", owner.selfReviewId());
            domainResult = "REVIEW_BLOCKED";
        }
        else {
            store.openTurnFailure(context, "OPERATION_FAILED", error, now);
            clearTerminalFence(
                    context, "PLAN_DRAFT_FAILED", context.turnId());
        }
        return recordTurnDelivery(
                context, rawResult, rawDigest, ACCEPTED, domainResult, now);
    }

    private TurnDeliveryReceipt retrySelfReview(
            TurnDeliveryContext context,
            SqlitePlanRuntimeStore.ReviewOwner owner,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            String error,
            Instant now)
    {
        BrainLaunchContext brain = store.requireBrainLaunchContext(context.taskId());
        PlanCandidate candidate = store.requirePlanCandidate(owner.revisionId());
        String retryTurnId = id("plan-review-retry-turn", owner.selfReviewId());
        String retryOperationId = id(
                "plan-review-retry-operation", owner.selfReviewId());
        TurnRequest retry = turn(
                brain, context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), PLAN_SELF_REVIEW,
                retryTurnId, retryOperationId,
                id("plan-review-retry-ticket", owner.selfReviewId()),
                reviewRetryPrompt(context.taskId(), candidate, error), now);
        store.insertTurn(retry);
        store.insertReviewRetryAttempt(
                owner.selfReviewId(), retry.turnId(), retry.operationId(),
                context.turnId(), now);
        CommandResult<StageManager.State> requested = plan.retrySelfReviewInCommand(
                new StageManager.ResultCommand(
                        id("retry-plan-review", context.turnId()),
                        "v2-plan-runtime", context.taskId(), context.fence()),
                owner.selfReviewId(), retry.turnId(), retry.fence());
        requireApplied(requested, "Plan self-review retry");
        return recordTurnDelivery(
                context, rawResult, rawDigest, ACCEPTED,
                "REVIEW_RETRY_REQUESTED", now);
    }

    private void clearTerminalFence(
            TurnDeliveryContext context, String cause, String proofId)
    {
        CommandResult<StageManager.State> cleared = plan.acceptTerminalTurnInCommand(
                new StageManager.ResultCommand(
                        id("clear-plan-terminal", context.turnId()),
                        "v2-plan-runtime", context.taskId(), context.fence()),
                cause, proofId,
                PLAN_DRAFT.equals(context.purpose())
                        ? StageCheckpoint.DRAFTING
                        : StageCheckpoint.SELF_REVIEW);
        requireApplied(cleared, "Plan terminal result");
    }

    private TurnDeliveryReceipt recordTurnDelivery(
            TurnDeliveryContext context,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            DispatchTicket.Acceptance acceptance,
            String domainResult,
            Instant now)
    {
        TurnDeliveryReceipt receipt = new TurnDeliveryReceipt(
                context.turnId(), context.operationId(),
                rawResult.outcome().name(), rawDigest, acceptance.name(),
                domainResult, now);
        store.insertTurnDeliveryReceipt(receipt);
        return receipt;
    }

    /** Exact operation-scoped authorization used by initialize/tools-list. */
    public McpAuthorization authorizeMcp(String turnId, String operationId)
    {
        String taskId = store.findMcpTaskId(
                        required(turnId, "turnId"),
                        required(operationId, "operationId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "TaskTurn MCP endpoint is not running"));
        return commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            McpContext context = store.authorizeMcp(turnId, operationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "TaskTurn MCP endpoint is stale"));
            return new McpAuthorization(context.taskId(), context.purpose());
        });
    }

    public PlanSubmission recordPlan(
            String turnId, String operationId, String taskId, String content)
    {
        required(taskId, "taskId");
        String normalized = required(content, "content").strip();
        String ownerTask = store.findMcpTaskId(
                        required(turnId, "turnId"),
                        required(operationId, "operationId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "TaskTurn MCP endpoint is not running"));
        return commands.execute(ownerTask, () -> {
            TaskCommandExecutor.requireCurrent(ownerTask);
            McpContext context = store.authorizeMcp(turnId, operationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "TaskTurn MCP endpoint is stale"));
            if (!PLAN_DRAFT.equals(context.purpose())
                    || !taskId.equals(context.taskId())
                    || context.checkpoint() != StageCheckpoint.DRAFTING) {
                throw new IllegalArgumentException(
                        "record_plan is not authorized for this exact TaskTurn");
            }
            String contentDigest = digest(normalized);
            PlanSubmission duplicate = store.findPlanSubmission(turnId).orElse(null);
            if (duplicate != null) {
                if (!operationId.equals(duplicate.operationId())
                        || !contentDigest.equals(duplicate.contentDigest())
                        || !normalized.equals(duplicate.content())
                        || !"AGENT".equals(duplicate.source())) {
                    throw new IllegalArgumentException(
                            "record_plan was already called with different content");
                }
                return duplicate;
            }
            return store.insertPlanSubmission(
                    context, id("plan-revision", operationId), normalized,
                    contentDigest, "AGENT", clock.instant());
        });
    }

    public ReviewSubmission recordSelfReview(
            String turnId,
            String operationId,
            String taskId,
            String verdict,
            List<String> concerns,
            List<String> followUps,
            List<String> stewardship)
    {
        required(taskId, "taskId");
        String normalizedVerdict = required(verdict, "verdict");
        if (!Set.of("APPROVED", "CHANGES_REQUESTED", "BLOCKED")
                .contains(normalizedVerdict)) {
            throw new IllegalArgumentException("self-review verdict is invalid");
        }
        List<String> exactConcerns = strings(concerns, "concerns");
        List<String> exactFollowUps = strings(followUps, "followUps");
        List<String> exactStewardship = strings(stewardship, "stewardship");
        if ("APPROVED".equals(normalizedVerdict) && !exactConcerns.isEmpty()) {
            throw new IllegalArgumentException(
                    "an approved self-review cannot carry concerns");
        }
        String concernsJson = write(json.valueToTree(exactConcerns));
        String followUpsJson = write(json.valueToTree(exactFollowUps));
        String stewardshipJson = write(json.valueToTree(exactStewardship));
        String ownerTask = store.findMcpTaskId(
                        required(turnId, "turnId"),
                        required(operationId, "operationId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "TaskTurn MCP endpoint is not running"));
        return commands.execute(ownerTask, () -> {
            TaskCommandExecutor.requireCurrent(ownerTask);
            McpContext context = store.authorizeMcp(turnId, operationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "TaskTurn MCP endpoint is stale"));
            if (!"PLAN_SELF_REVIEW".equals(context.purpose())
                    || !taskId.equals(context.taskId())
                    || context.checkpoint() != StageCheckpoint.SELF_REVIEW) {
                throw new IllegalArgumentException(
                        "record_plan_self_review is not authorized for this TaskTurn");
            }
            ReviewSubmission duplicate = store.findReviewSubmission(turnId).orElse(null);
            if (duplicate != null) {
                if (!operationId.equals(duplicate.operationId())
                        || !normalizedVerdict.equals(duplicate.verdict())
                        || !concernsJson.equals(duplicate.concernsJson())
                        || !followUpsJson.equals(duplicate.followUpsJson())
                        || !stewardshipJson.equals(duplicate.stewardshipJson())) {
                    throw new IllegalArgumentException(
                            "record_plan_self_review already has another verdict");
                }
                return duplicate;
            }
            return store.insertReviewSubmission(
                    context, normalizedVerdict, concernsJson, followUpsJson,
                    stewardshipJson, clock.instant());
        });
    }

    /** Material user edit followed by a new mandatory review Turn. */
    public PlanEditReceipt editPlan(PlanEditCommand command)
    {
        requireNonNull(command, "command is null");
        String normalized = required(command.content(), "content").strip();
        String contentDigest = digest(normalized);
        return commands.execute(command.taskId(), () -> {
            TaskCommandExecutor.requireCurrent(command.taskId());
            PlanEditReceipt duplicate = store.findPlanEditReceipt(
                            command.requestId())
                    .orElse(null);
            if (duplicate != null) {
                requireSameEdit(command, normalized, contentDigest, duplicate);
                return duplicate;
            }

            PlanEditContext context = store.requirePlanEditContext(
                    command.taskId(), command.stageId(), command.stageGeneration(),
                    command.previousRevisionId(), command.previousSelfReviewId());
            if (context.stageVersion() != command.expectedStageVersion()) {
                throw new IllegalArgumentException("Plan edit has a stale Stage version");
            }
            if (context.previousDigest().equals(contentDigest)) {
                throw new IllegalArgumentException(
                        "Plan edit must materially change the latest revision");
            }
            Instant now = clock.instant();
            String revisionId = id("plan-user-revision", command.requestId());
            EditedRevision revision = store.insertUserRevision(
                    context, revisionId, normalized, contentDigest,
                    command.actor(), now);
            String reviseCommandId = id(
                    "plan-user-edit-revise", command.requestId());
            PlanStageManager.RevisionCommand revise =
                    new PlanStageManager.RevisionCommand(
                            new StageManager.Command(
                                    reviseCommandId, command.actor(), context.taskId(),
                                    context.taskEpoch(), context.stageId(),
                                    context.stageGeneration(), context.stageVersion()),
                            revision.revisionId(), context.previousRevisionId(),
                            revision.contentDigest());
            CommandResult<StageManager.State> revised =
                    plan.reviseBeforeApprovalInCommand(revise);
            requireApplied(revised, "Plan user edit");

            String reviewTurnId = id(
                    "plan-user-edit-review-turn", command.requestId());
            String reviewOperationId = id(
                    "plan-user-edit-review-operation", command.requestId());
            String reviewTicketId = id(
                    "plan-user-edit-review-ticket", command.requestId());
            TurnRequest review = turn(
                    context.brain(), context.taskEpoch(), context.stageId(),
                    context.stageGeneration(), context.codeFingerprint(),
                    context.headSha(), context.baseSha(), PLAN_SELF_REVIEW,
                    reviewTurnId, reviewOperationId, reviewTicketId,
                    reviewPrompt(context.taskId(), new PlanSubmission(
                            command.requestId(), reviewOperationId,
                            revision.revisionId(), revision.revision(),
                            revision.content(), revision.contentDigest(),
                            "USER_EDIT", now)), now);
            store.insertTurn(review);
            String selfReviewId = id(
                    "plan-user-edit-self-review", command.requestId());
            store.insertSelfReview(
                    selfReviewId, revision.revisionId(), review.turnId(),
                    context.taskEpoch(), revision.contentDigest(), now);
            String reviewCommandId = id(
                    "plan-user-edit-request-review", command.requestId());
            PlanStageManager.RevisionCommand requestReview =
                    new PlanStageManager.RevisionCommand(
                            new StageManager.Command(
                                    reviewCommandId, command.actor(), context.taskId(),
                                    context.taskEpoch(), context.stageId(),
                                    context.stageGeneration(), revised.state().version()),
                            revision.revisionId(), context.previousRevisionId(),
                            revision.contentDigest());
            CommandResult<StageManager.State> requested =
                    plan.requestEditedRevisionReviewInCommand(
                            requestReview, review.fence());
            requireApplied(requested, "Edited Plan self-review request");

            PlanEditReceipt receipt = new PlanEditReceipt(
                    command.requestId(), context.taskId(), context.taskEpoch(),
                    context.stageId(), context.stageGeneration(),
                    context.stageVersion(), command.actor(),
                    context.previousRevisionId(), revision.revisionId(),
                    revision.revision(), revision.content(),
                    revision.contentDigest(), selfReviewId, review.turnId(),
                    review.operationId(), review.ticketId(), reviewCommandId, now);
            store.insertPlanEditReceipt(receipt);
            return receipt;
        });
    }

    private static void requireSameEdit(
            PlanEditCommand command,
            String normalized,
            String contentDigest,
            PlanEditReceipt receipt)
    {
        if (!command.taskId().equals(receipt.taskId())
                || !command.stageId().equals(receipt.stageId())
                || command.stageGeneration() != receipt.stageGeneration()
                || command.expectedStageVersion() != receipt.expectedStageVersion()
                || !command.actor().equals(receipt.actor())
                || !command.previousRevisionId().equals(receipt.previousRevisionId())
                || !normalized.equals(receipt.content())
                || !contentDigest.equals(receipt.contentDigest())) {
            throw new IllegalArgumentException(
                    "Plan edit request id was used for different content or owner");
        }
    }

    private ProvisionReceipt acceptProvisioningInCommand(
            ProvisionTaskOperationHandler.ProvisionEvidence evidence,
            String evidenceJson)
    {
        TaskCommandExecutor.requireCurrent(evidence.taskId());
        String evidenceDigest = digest(evidenceJson);
        ProvisionReceipt duplicate = store.findProvisionReceipt(
                        evidence.taskId(), evidence.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!evidenceDigest.equals(duplicate.evidenceDigest())) {
                throw new IllegalStateException(
                        "Provision operation was accepted with different evidence");
            }
            return duplicate;
        }

        ProvisionContext context = store.requireProvisionContext(
                evidence.taskId(), evidence.operationId());
        if (!"PROVISIONING".equals(context.lifecycle())
                || !"DISPATCHED".equals(context.provisionStatus())
                || context.taskEpoch() != evidence.taskEpoch()
                || context.taskVersion() != 0
                || context.provisionAttempt() != 1
                || !context.repositoryId().equals(evidence.repositoryId())
                || !context.branchName().equals(evidence.branchName())
                || !context.worktreePath().equals(evidence.worktreePath())) {
            throw new IllegalStateException(
                    "Decoded provisioning result does not own the frozen Task context");
        }

        String planStageId = id("plan-stage", evidence.operationId());
        String draftTurnId = id("plan-draft-turn", evidence.operationId());
        String draftOperationId = id("plan-draft-operation", evidence.operationId());
        String draftTicketId = id("plan-draft-ticket", evidence.operationId());
        Instant now = clock.instant();

        TaskManager.StageOpening opening = tasks.acceptProvisionedCodeInCommand(
                new TaskManager.ProvisionedCode(
                        evidence.taskId(), evidence.taskEpoch(), evidence.operationId(),
                        context.provisionAttempt(), evidence.repositoryId(),
                        evidence.branchName(), evidence.worktreePath(), evidence.baseSha(),
                        evidence.headSha(), evidence.codeFingerprint(), evidenceJson),
                id("accept-provision", evidence.operationId()),
                "v2-plan-runtime", planStageId, 1);
        PlanStageManager.AcceptedOpening opened = plan.openFromTaskInCommand(opening);
        CommandResult<StageManager.State> stage = opened.stage();

        TurnRequest draft = turn(
                context, evidence, planStageId, 1, PLAN_DRAFT,
                draftTurnId, draftOperationId, draftTicketId,
                draftPrompt(context), now);
        store.insertTurn(draft);
        CommandResult<StageManager.State> requested = plan.requestDraftInCommand(
                new StageManager.Command(
                        id("request-plan-draft", evidence.operationId()),
                        "v2-plan-runtime", evidence.taskId(), evidence.taskEpoch(),
                        planStageId, 1, stage.state().version()),
                draft.turnId(), draft.fence());
        if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException("Initial Plan draft request was superseded");
        }

        ProvisionReceipt receipt = new ProvisionReceipt(
                evidence.taskId(), evidence.operationId(), evidenceDigest,
                planStageId, 1, draftTurnId, draftOperationId, draftTicketId, now);
        store.insertProvisionReceipt(receipt);
        return receipt;
    }

    private TurnRequest turn(
            ProvisionContext context,
            ProvisionTaskOperationHandler.ProvisionEvidence code,
            String stageId,
            long stageGeneration,
            String purpose,
            String turnId,
            String operationId,
            String ticketId,
            String prompt,
            Instant requestedAt)
    {
        return turn(
                new BrainLaunchContext(
                        context.taskId(), context.trunkId(), context.workspaceId(),
                        context.workModelSnapshot(), context.provider(), context.model(),
                        context.roleSkill(), context.worktreePath()),
                code.taskEpoch(), stageId, stageGeneration,
                code.codeFingerprint(), code.headSha(), code.baseSha(), purpose,
                turnId, operationId, ticketId, prompt, requestedAt);
    }

    private TurnRequest turn(
            BrainLaunchContext context,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String purpose,
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
                || model.kind() == WorkModelKind.CLI && model.account() != null
                || model.kind() == WorkModelKind.CLI
                    && !Set.of("codex", "claude-code").contains(context.provider())) {
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
        launch.put("systemPrompt", systemPrompt(context.roleSkill(), purpose));
        launch.put("prompt", prompt);
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
        return new TurnRequest(
                turnId, operationId, ticketId, purpose, context.workspaceId(),
                context.trunkId(), context.taskId(), taskEpoch, stageId,
                stageGeneration, codeFingerprint, headSha, baseSha,
                lane, laneMask, write(launch), requestedAt);
    }

    private String draftPrompt(ProvisionContext context)
    {
        String assignment = switch (context.assignmentKind()) {
            case "NEW_FROM_TRUNK" -> "Plan seed:\n" + context.planSeed()
                    + "\n\nRequested work:\n" + context.prompt();
            case "EXISTING_OWN_PR" -> "Plan the remaining work for existing PR #"
                    + context.pullRequestNumber() + " in "
                    + context.assignmentRepositoryId() + ".";
            case "REVIEW_FINDINGS" -> "Plan fixes for review " + context.sourceId()
                    + " findings " + context.selectedFindingsJson() + ".";
            case "ISSUE" -> "Plan the implementation for issue "
                    + context.sourceId() + ".";
            case "AUTOMATION" -> "Plan the automated request from "
                    + context.producer() + ": " + context.reason();
            case "QUALITY_SCAN" -> "Plan remediation for quality evidence "
                    + context.sourceId() + ".";
            default -> throw new IllegalStateException(
                    "Unknown TaskAssignment kind: " + context.assignmentKind());
        };
        return assignment + "\n\nInspect the repository without changing files. "
                + "Then call record_plan exactly once with task_id='"
                + context.taskId() + "' and a finalized structured plan. "
                + "Do not edit code, commit, push, or create remote effects.";
    }

    private static String reviewPrompt(String taskId, PlanSubmission submission)
    {
        return "Review candidate Plan revision " + submission.revision()
                + " for Task " + taskId + " with digest "
                + submission.contentDigest() + ".\n\n"
                + submission.content()
                + "\n\nPerform exactly one self-review. Call "
                + "record_plan_self_review exactly once with the matching task_id, "
                + "a typed verdict, and explicit concern, follow-up, and Project "
                + "Stewardship arrays. Do not edit files or create remote effects.";
    }

    private static String reviewRetryPrompt(
            String taskId, PlanCandidate candidate, String predecessorError)
    {
        return "Retry the mandatory self-review for Plan revision "
                + candidate.revision() + " of Task " + taskId + " with digest "
                + candidate.contentDigest() + ". The first execution failed: "
                + predecessorError + ".\n\n" + candidate.content()
                + "\n\nCall record_plan_self_review exactly once with a typed "
                + "verdict and explicit concern, follow-up, and Project "
                + "Stewardship arrays. Do not edit files or create remote effects.";
    }

    private static String redraftPrompt(
            String taskId, ReviewSubmission submission)
    {
        return "Revise the current Plan for Task " + taskId
                + " to address the exact self-review concerns below.\n\n"
                + submission.concernsJson()
                + "\n\nCall record_plan exactly once with a materially revised "
                + "candidate Plan. Do not edit files or create remote effects.";
    }

    private void insertReviewFollowups(
            ReviewSubmission submission, String createdBy, Instant createdAt)
    {
        insertFollowups(
                submission, "CONCERN",
                readStrings(submission.concernsJson()), createdBy, createdAt);
        insertFollowups(
                submission, "FOLLOW_UP",
                readStrings(submission.followUpsJson()), createdBy, createdAt);
        insertFollowups(
                submission, "STEWARDSHIP",
                readStrings(submission.stewardshipJson()), createdBy, createdAt);
    }

    private void insertFollowups(
            ReviewSubmission submission,
            String kind,
            List<String> descriptions,
            String createdBy,
            Instant createdAt)
    {
        for (int index = 0; index < descriptions.size(); index++) {
            store.insertFollowup(
                    id("plan-followup-" + kind,
                            submission.selfReviewId() + ":" + (index + 1)),
                    submission.revisionId(), kind, descriptions.get(index),
                    createdBy, createdAt);
        }
    }

    private List<String> readStrings(String value)
    {
        try {
            return json.readerForListOf(String.class).readValue(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Persisted Plan protocol array is invalid", e);
        }
    }

    private static void requireSubmission(
            TurnDeliveryContext context, PlanSubmission submission)
    {
        if (!context.turnId().equals(submission.turnId())
                || !context.operationId().equals(submission.operationId())
                || !"AGENT".equals(submission.source())) {
            throw new IllegalStateException(
                    "Plan submission does not match its exact TaskTurn");
        }
    }

    private static void requireApplied(
            CommandResult<StageManager.State> result, String description)
    {
        if (result.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(description + " was superseded");
        }
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
        return digest(write(node));
    }

    private String turnReceiptJson(TurnDeliveryReceipt receipt)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "PLAN_TASK_TURN_DELIVERY_V1");
        node.put("turnId", receipt.turnId());
        node.put("operationId", receipt.operationId());
        node.put("acceptance", receipt.acceptance());
        node.put("domainResult", receipt.domainResult());
        return write(node);
    }

    private ProvisionTaskOperationHandler.ProvisionEvidence decodeProvisionEvidence(
            String value)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provision evidence is missing");
        }
        try {
            return provisionEvidenceReader.readValue(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Provision evidence is invalid", e);
        }
    }

    private WorkModel decodeWorkModel(String value)
    {
        try {
            WorkModel model = workModelReader.readValue(value);
            if (model.kind() == null
                    || model.agentOrProvider() == null
                    || model.agentOrProvider().isBlank()) {
                throw new IllegalArgumentException("Frozen work model is incomplete");
            }
            return model;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Frozen work model is not strict WorkModel JSON", e);
        }
    }

    private static void requireProvisionEvidence(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expected,
            ProvisionTaskOperationHandler.ProvisionEvidence evidence)
    {
        Path worktree = Path.of(required(evidence.worktreePath(), "worktreePath"));
        if (!"PROVISION_TASK_V1".equals(evidence.schema())
                || !owner.id().equals(evidence.taskId())
                || !expected.operationId().equals(evidence.operationId())
                || !Objects.equals(expected.taskEpoch(), evidence.taskEpoch())
                || evidence.baseSource() == null
                || required(evidence.repositoryId(), "repositoryId").isBlank()
                || required(evidence.branchName(), "branchName").isBlank()
                || required(evidence.baseSha(), "baseSha").isBlank()
                || required(evidence.headSha(), "headSha").isBlank()
                || required(evidence.codeFingerprint(), "codeFingerprint").isBlank()
                || !worktree.isAbsolute()
                || !worktree.normalize().equals(worktree)) {
            throw new IllegalArgumentException(
                    "Provision evidence does not match the exact Task operation");
        }
    }

    private String provisionReceiptJson(ProvisionReceipt receipt)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "PROVISION_TO_PLAN_V1");
        node.put("taskId", receipt.taskId());
        node.put("provisionOperationId", receipt.provisionOperationId());
        node.put("planStageId", receipt.planStageId());
        node.put("planStageGeneration", receipt.planStageGeneration());
        node.put("draftTurnId", receipt.draftTurnId());
        node.put("draftOperationId", receipt.draftOperationId());
        return write(node);
    }

    private String provisionFailureReceiptJson(
            TaskManager.ProvisioningFailureResult failure)
    {
        ObjectNode node = json.createObjectNode();
        node.put("schema", "PROVISION_FAILURE_V1");
        node.put("taskId", failure.taskId());
        node.put("operationId", failure.operationId());
        node.put("outcome", failure.rawOutcome());
        node.put("blockerId", failure.blockerId());
        return write(node);
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String value)
    {
        String evidence = value != null && value.startsWith("{")
                ? value
                : write(json.createObjectNode()
                        .put("schema", "PLAN_DELIVERY_V1")
                        .put("result", value));
        return new DispatchTicket.DeliveryReceipt(acceptance, evidence);
    }

    private String write(JsonNode node)
    {
        try {
            return json.writeValueAsString(node);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Plan protocol JSON", e);
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

    private static String systemPrompt(String roleSkill, String purpose)
    {
        String base = "You are the read-only Task Brain for the V2 Plan protocol. "
                + "You may inspect the checked-out Task worktree, but you must not "
                + "modify files or create remote effects. Complete the requested "
                + purpose + " protocol using the provided MCP tools.";
        return roleSkill == null || roleSkill.isBlank()
                ? base
                : base + "\n\nRole skill:\n" + roleSkill;
    }

    public static String id(String namespace, String value)
    {
        return UUID.nameUUIDFromBytes(
                ("bytequay-v2:" + namespace + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(required(value, "digest value")
                                    .getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
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

    private static List<String> strings(List<String> values, String name)
    {
        requireNonNull(values, name + " is null");
        return values.stream().map(value -> required(value, name + " entry").strip())
                .toList();
    }

    public record McpAuthorization(String taskId, String purpose) {}

    public record PlanEditCommand(
            String requestId,
            String actor,
            String taskId,
            String stageId,
            long stageGeneration,
            long expectedStageVersion,
            String previousRevisionId,
            String previousSelfReviewId,
            String content)
    {
        public PlanEditCommand
        {
            required(requestId, "requestId");
            required(actor, "actor");
            required(taskId, "taskId");
            required(stageId, "stageId");
            required(previousRevisionId, "previousRevisionId");
            required(previousSelfReviewId, "previousSelfReviewId");
            required(content, "content");
            if (stageGeneration < 1 || expectedStageVersion < 0) {
                throw new IllegalArgumentException("Plan edit fence is invalid");
            }
        }
    }
}
