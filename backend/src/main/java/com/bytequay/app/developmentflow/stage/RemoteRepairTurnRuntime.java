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
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.OutputCodeSubject;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.Classification;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.AdoptionResult;
import com.bytequay.app.developmentflow.stage.persistence.SqliteAgentResultSubmissionStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteAgentResultSubmissionStore.RepairSubmission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.AdoptionCompletion;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.NormalizationDue;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.NormalizationOperation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.ReplayReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRetryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.CiFixContinuationDue;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.CiNextFixDue;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.CodeSubject;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.RepairContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.TurnDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BaseRepairAuthorization;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ManualCiTurnIntent;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Attachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.fasterxml.jackson.core.JsonParser;
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
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.INDETERMINATE;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Finite typed Turn continuation shared by CI repair and branch conflicts. */
public final class RemoteRepairTurnRuntime
        implements RemoteCiRepairRuntimeCoordinator.DeterministicRepairPort,
        BranchSyncRuntimeCoordinator.ConflictRepairPort,
        BranchSyncRuntimeCoordinator.BrainReviewPort,
        ExecutionPorts.MaintenanceWork
{
    public static final String CI_STAGE_CALLBACK =
            "REMOTE_CI_STAGE_TURN_RESULT";
    public static final String CI_BRAIN_CALLBACK = "REMOTE_CI_BRAIN_RESULT";
    public static final String BRANCH_STAGE_CALLBACK =
            "BRANCH_SYNC_CONFLICT_RESULT";
    public static final String BRANCH_BRAIN_CALLBACK =
            "BRANCH_SYNC_BRAIN_RESULT";
    public static final String STEERING_CALLBACK =
            "REMOTE_REPAIR_STEERING_RESULT";
    public static final String NORMALIZATION_CALLBACK =
            "REMOTE_REPAIR_RESULT_NORMALIZATION_RESULT";

    private static final String ACTOR = "v2-remote-repair";

    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final SqliteRemoteRuntimeStore remoteStore;
    private final SqliteRemoteRepairTurnStore turns;
    private final SqliteAgentResultSubmissionStore submissions;
    private final ObjectMapper json;
    private final ObjectReader stageReader;
    /** Names this Turn in every brain-protocol failure message. */
    private static final String BRAIN_LABEL = "Remote repair Brain";
    private final ObjectReader adoptionReader;
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;
    private SqliteStageSteeringStore steering;
    private SqliteRemoteRepairNormalizationStore normalizations;
    private ReasoningEffortService reasoningEfforts;

    public RemoteRepairTurnRuntime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            SqliteRemoteRuntimeStore remoteStore,
            SqliteRemoteRepairTurnStore turns,
            SqliteAgentResultSubmissionStore submissions,
            ObjectMapper json,
            Clock clock,
            int serverPort)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.remoteStore = requireNonNull(remoteStore, "remoteStore is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.submissions = requireNonNull(submissions, "submissions is null");
        this.json = requireNonNull(json, "json is null");
        this.stageReader = strictReader(StageResult.class);
        this.adoptionReader = strictReader(AdoptionResult.class);
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

    @Autowired
    public void setNormalizationStore(
            SqliteRemoteRepairNormalizationStore normalizations)
    {
        this.normalizations = requireNonNull(
                normalizations, "normalizations is null");
    }

    @Autowired
    void setReasoningEfforts(ReasoningEffortService reasoningEfforts)
    {
        this.reasoningEfforts = requireNonNull(
                reasoningEfforts, "reasoningEfforts is null");
    }

    /**
     * Persist the summary a CI or branch-conflict repair reports through
     * {@code record_repair_summary}. Called from the tool handler while the
     * subprocess is still alive, so a rejection reaches the agent as an MCP tool
     * error it can correct in the same session — unlike the old contract, where
     * the summary was parsed out of the final message after the process had
     * exited and only a normalizer Turn or a human could unstick it.
     *
     * <p>Idempotent: an identical re-submission is accepted, a differing one is
     * rejected. A submission from a Turn that goes stale is inert — delivery
     * reads only its own {@code turnId} and rejects a stale Turn regardless.
     */
    public void recordRepairSummary(String turnId, String operationId, String summary)
    {
        String taskId = submissions.requireStageRepairTurnTaskId(
                requireNonNull(turnId, "turnId is null"),
                requireNonNull(operationId, "operationId is null"));
        RepairSubmission submitted = new RepairSubmission(
                required(summary, "summary"), List.of());
        commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            RepairSubmission existing =
                    submissions.findRepairSubmission(turnId).orElse(null);
            if (existing != null) {
                if (!existing.equals(submitted)) {
                    throw new IllegalArgumentException(
                            "record_repair_summary was already called with a "
                                    + "different summary for this Turn");
                }
                return existing;
            }
            submissions.insertRepairSubmission(
                    turnId, operationId, taskId, submitted, clock.instant());
            return submitted;
        });
    }

    public SqliteRemoteRepairTurnStore.TurnRequest admitSteeringInCommand(
            Request request)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        SqliteStageSteeringStore ownerStore = requireNonNull(
                steering, "Stage steering store is not configured");
        String purpose = request.predecessor().purpose();
        if (!purpose.equals("REMOTE_CI_REPAIR")
                && !purpose.equals("BRANCH_CONFLICT_REPAIR")) {
            throw new IllegalArgumentException(
                    "Remote repair cannot consume " + purpose);
        }
        var handoff = ownerStore.requireRemoteHandoff(request.id());
        String expectedFamily = purpose.equals("REMOTE_CI_REPAIR")
                ? "CI_REPAIR" : "BRANCH_REPAIR";
        if (!handoff.status().equals("PARKED")
                || !handoff.ownerFamily().equals(expectedFamily)) {
            throw new IllegalStateException(
                    "Remote repair steering handoff is not parked for this owner");
        }
        RepairContext context = turns.requireContext(
                request.taskId(), request.stageId());
        WorkModel model = workModel(context, "STAGE_TURN");
        List<Attachment> attachments = ownerStore.attachments(request.id());
        String prompt = steeringPrompt(request, attachments, purpose);
        String turnId = id("remote-repair-steering-turn", request.id());
        String operationId = id("remote-repair-steering-operation", request.id());
        ObjectNode launch = launch(
                context, model, "STAGE_TURN", turnId, operationId,
                "STAGE_DEVELOPMENT", stageSystemPrompt(context.roleSkill()), prompt);
        StageCliContinuity.freezeImages(json, launch, attachments);
        applyCliContinuity(
                launch, request, context, model, prompt, ownerStore);
        return turns.insertSteeringTurn(
                request,
                write(launch),
                model.kind().name(), laneMask(model), clock.instant());
    }

    @Override
    public void startInCommand(Candidate candidate, CiEpisode episode)
    {
        requireNonNull(candidate, "candidate is null");
        requireNonNull(episode, "episode is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        Classification classification = Classification.valueOf(
                episode.classification());
        if (classification == Classification.TASK_DETERMINISTIC
                || classification == Classification.TASK_BRANCH_REPAIRABLE) {
            startCiFix(episode,
                    "Fix every failed CI check for the exact current Task head. "
                            + "Some failures may be base-owned, mixed, or "
                            + "unattributable. Treat all of them as part of this "
                            + "repair. Commit the repair, then report it by "
                            + "calling record_repair_summary."
                            + "\n\nCI evidence:\n"
                            + write(candidate.ciEvaluation().checks()));
            return;
        }
        if (classification == Classification.BASE_DETERMINISTIC) {
            RepairContext context = turns.requireContext(
                    episode.taskId(), episode.stageId());
            if (context.autoApprove()) {
                startBaseRepair(
                        episode, context,
                        id("auto-ci-base-repair",
                                episode.id() + ":" + (episode.fixAttemptCount() + 1)
                                        + ":" + context.automationPolicyId()),
                        "AUTO_APPROVE_POLICY", context.automationPolicyId(),
                        null, null,
                        "Current Task policy authorizes proven base repair",
                        write(candidate.ciEvaluation().checks()));
                return;
            }
            remoteStore.blockCiEpisode(
                    episode, "CI_BASE_REPAIR_REQUIRED",
                    "Base-owned CI failure requires scoped base repair",
                    "{\"choices\":[\"START_BASE_REPAIR\",\"MANUAL_TAKEOVER\","
                            + "\"STOP_AUTOMATION\"]}", clock.instant());
            return;
        }
        if (classification == Classification.UNKNOWN) {
            remoteStore.blockCiEpisode(
                    episode, "CI_FAILURE_CLASSIFICATION_REQUIRED",
                    "CI failure origin is unknown",
                    "{\"choices\":[\"MANUAL_TAKEOVER\","
                            + "\"STOP_AUTOMATION\"]}",
                    clock.instant());
            return;
        }
        throw new IllegalStateException(
                "Rerun-only CI classification reached code repair");
    }

    @Override
    public void startBaseRepairInCommand(
            CiEpisode episode,
            String blockerId,
            String commandId,
            String actor,
            String reason)
    {
        requireNonNull(episode, "episode is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        BaseRepairAuthorization authorization = remoteStore.authorizeBaseRepair(
                episode, null, blockerId, commandId, "MANUAL", actor, reason,
                context.headSha(), clock.instant());
        remoteStore.insertManualCiTurnIntent(
                episode, authorization.id(), clock.instant());
        requestFreshObservation(episode.taskId(), episode.stageId());
    }

    @Override
    public void acceptValidationInCommand(
            CiEpisode episode, RemoteEffectOperationHandler.Result result)
    {
        requireNonNull(episode, "episode is null");
        requireNonNull(result, "result is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        CiEpisode current = remoteStore.requireCiEpisode(
                episode.taskId(), episode.id());
        if (result.disposition()
                != RemoteEffectOperationHandler.Disposition.SUCCEEDED) {
            nextCiFixOrExhaust(
                    current, "VALIDATION_FAILED",
                    "Canonical validation failed:\n"
                            + Objects.toString(result.evidence(), result.error()));
            return;
        }
        requestCiPush(current);
    }

    @Override
    public void startInCommand(
            BranchEpisode episode,
            RemoteEffectOperationHandler.Result rebaseResult)
    {
        requireNonNull(episode, "episode is null");
        requireNonNull(rebaseResult, "rebaseResult is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        if (rebaseResult.disposition()
                != RemoteEffectOperationHandler.Disposition.CONFLICT) {
            throw new IllegalArgumentException(
                    "Branch conflict Turn requires conflict evidence");
        }
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        requireSubject(context, rebaseResult);
        BranchStep step = remoteStore.requireBranchStep(episode.id(), 3);
        WorkModel model = workModel(context, "STAGE_TURN");
        String prompt = "Resolve every conflict while rebasing the exact Task "
                + "head onto base " + episode.targetBaseSha()
                + ". Do not push. Finish the rebase and commit the resolved "
                + "head, then report it by calling record_repair_summary."
                + "\n\nConflict evidence:\n"
                + Objects.toString(rebaseResult.evidence(), "");
        String turnId = id("branch-sync-stage-turn",
                step.id() + ":" + (step.attemptCount() + 1));
        String operationId = id("branch-sync-operation",
                step.id() + ":" + (step.attemptCount() + 1));
        turns.insertBranchStageTurn(
                context, episode, step,
                write(launch(context, model, "STAGE_TURN", turnId,
                        operationId, "STAGE_DEVELOPMENT",
                        stageSystemPrompt(context.roleSkill()), prompt)),
                model.kind().name(), laneMask(model), clock.instant());
    }

    @Override
    public void startInCommand(
            BranchEpisode episode,
            BranchStep step,
            RemoteEffectOperationHandler.Result validationResult)
    {
        requireNonNull(episode, "episode is null");
        requireNonNull(step, "step is null");
        requireNonNull(validationResult, "validationResult is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        requireSubject(context, validationResult);
        requestBranchBrain(context, episode, step);
    }

    @Override
    public void maintain(Instant now)
    {
        requireNonNull(now, "now is null");
        SqliteRemoteRepairNormalizationStore store = normalizationStore();
        for (NormalizationDue due : store.findPending(32)) {
            commands.executeVoid(due.taskId(), () -> {
                NormalizationDue current = store.findPending(due.id())
                        .orElse(null);
                if (current == null) {
                    return;
                }
                if (!current.current()) {
                    store.cancelPending(
                            current,
                            "Remote repair normalization subject is stale",
                            now);
                    return;
                }
                String turnId = id(
                        "remote-repair-normalization-turn", current.id());
                String operationId = id(
                        "remote-repair-normalization-operation", current.id());
                store.insertNormalization(
                        current,
                        normalizationLaunch(current, turnId, operationId),
                        now);
            });
        }
    }

    public DispatchTicket.DeliveryReceipt deliver(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (!supported(result.owner())) {
            return receipt(SUPERSEDED, "Remote repair Turn route is stale");
        }
        if (NORMALIZATION_CALLBACK.equals(
                result.owner().callbackRoute())) {
            String taskId = normalizationStore().requireNormalizationTaskId(
                    result.owner().id(), result.fence().operationId());
            return commands.execute(
                    taskId, () -> deliverNormalizationInCommand(result));
        }
        String taskId = STEERING_CALLBACK.equals(result.owner().callbackRoute())
                ? turns.requireSteeringTaskId(
                        result.owner().id(), result.fence().operationId())
                : turns.requireTurnTaskId(
                        result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverInCommand(result));
    }

    public DispatchTicket.DeliveryReceipt deliverAdoption(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TASK
                || !RemoteRepairCommitAdoptionOperationHandler.CALLBACK_ROUTE
                        .equals(owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            throw new IllegalArgumentException(
                    "Remote repair adoption result route or fence is stale");
        }
        String taskId = normalizationStore().requireAdoptionTaskId(
                owner.id(), expectedFence.operationId());
        return commands.execute(taskId, () -> deliverAdoptionInCommand(
                owner, expectedFence, rawResult));
    }

    private DispatchTicket.DeliveryReceipt deliverNormalizationInCommand(
            AgentTurnOwnerResultCodec.OwnerResult raw)
    {
        SqliteRemoteRepairNormalizationStore store = normalizationStore();
        String rawDigest = digest(write(raw));
        ReplayReceipt duplicate = store.findNormalizationReceipt(
                raw.fence().operationId()).orElse(null);
        if (duplicate != null) {
            requireSameRawResult(duplicate, rawDigest);
            return duplicate.deliveryReceipt();
        }
        NormalizationOperation operation = store.requireNormalizationDelivery(
                raw.owner().id(), raw.fence().operationId());
        if (raw.owner().kind() != DispatchTicket.OwnerKind.TASK_TURN
                || !NORMALIZATION_CALLBACK.equals(
                        raw.owner().callbackRoute())
                || !operation.fence().equals(toFence(raw.fence()))
                || !"REMOTE_REPAIR_RESULT_NORMALIZATION".equals(
                        raw.payload().purpose())) {
            throw new IllegalArgumentException(
                    "Remote repair normalization differs from its exact fence");
        }

        Instant now = clock.instant();
        if (!operation.current()) {
            String error = "Remote repair normalization subject is stale";
            store.finishNormalization(
                    operation, raw.outcome().name(), rawDigest,
                    "SUPERSEDED", SUPERSEDED, null, null,
                    normalizationEvidence(operation, rawDigest, null),
                    error, now);
            return storedNormalizationReceipt(
                    store, operation.operationId(), rawDigest);
        }
        if (raw.outcome() != SUCCEEDED) {
            String status = raw.outcome() == CANCELED ? "CANCELED" : "FAILED";
            String error = Objects.toString(
                    raw.payload().error(), "Result normalization failed");
            store.finishNormalization(
                    operation, raw.outcome().name(), rawDigest,
                    status, ACCEPTED, null, null,
                    normalizationEvidence(operation, rawDigest, null),
                    error, now);
            return storedNormalizationReceipt(
                    store, operation.operationId(), rawDigest);
        }

        String normalizedPayload = raw.payload().finalText();
        try {
            decodeStage(normalizedPayload);
        }
        catch (IllegalArgumentException malformed) {
            String error = "Normalized Remote repair result is malformed: "
                    + malformed.getMessage();
            store.finishNormalization(
                    operation, raw.outcome().name(), rawDigest,
                    "FAILED", ACCEPTED, null, null,
                    normalizationEvidence(operation, rawDigest, null),
                    error, now);
            return storedNormalizationReceipt(
                    store, operation.operationId(), rawDigest);
        }
        String normalizedDigest = digest(normalizedPayload);
        store.finishNormalization(
                operation, raw.outcome().name(), rawDigest,
                "SUCCEEDED", ACCEPTED, normalizedPayload, normalizedDigest,
                normalizationEvidence(
                        operation, rawDigest, normalizedPayload),
                null, now);
        return storedNormalizationReceipt(
                store, operation.operationId(), rawDigest);
    }

    private DispatchTicket.DeliveryReceipt deliverAdoptionInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        SqliteRemoteRepairNormalizationStore store = normalizationStore();
        String rawDigest = digest(write(rawResult));
        ReplayReceipt duplicate = store.findAdoptionReceipt(
                expectedFence.operationId()).orElse(null);
        if (duplicate != null) {
            requireSameRawResult(duplicate, rawDigest);
            return duplicate.deliveryReceipt();
        }
        RemoteRepairCommitAdoptionOperationHandler.Operation operation =
                store.requireAdoptionDelivery(
                        owner.id(), expectedFence.operationId());
        if (!owner.id().equals(operation.taskId())
                || !adoptionFence(operation).equals(toFence(expectedFence))) {
            throw new IllegalArgumentException(
                    "Remote repair adoption differs from its exact fence");
        }

        AdoptionResult result = rawResult.payloadJson() == null
                ? null : decodeAdoption(rawResult.payloadJson());
        requireAdoptionResult(operation, rawResult.outcome(), result);
        DispatchTicket.Acceptance acceptance = operation.currentOwner()
                && (result == null || result.disposition()
                    != RemoteRepairCommitAdoptionOperationHandler.Disposition.STALE)
                ? ACCEPTED : SUPERSEDED;
        String status;
        if (acceptance == SUPERSEDED) {
            status = "SUPERSEDED";
        }
        else if (rawResult.outcome() == SUCCEEDED) {
            status = "SUCCEEDED";
        }
        else if (rawResult.outcome() == CANCELED) {
            status = "CANCELED";
        }
        else {
            status = "FAILED";
        }
        String error = "SUCCEEDED".equals(status) ? null
                : result == null
                        ? Objects.toString(
                                rawResult.error(), "Remote repair adoption failed")
                        : Objects.toString(result.error(), rawResult.error());
        AdoptionCompletion completion = store.finishAdoption(
                operation, result, rawResult.outcome().name(), rawDigest,
                status, acceptance,
                Objects.toString(rawResult.evidenceJson(), write(rawResult)),
                error, clock.instant());
        if (completion.shouldValidate()) {
            CiEpisode episode = remoteStore.requireCiEpisode(
                    operation.taskId(), completion.episodeId());
            RepairContext next = turns.requireContext(
                    operation.taskId(), operation.stageId());
            if (completion.authorizationId() == null) {
                turns.insertCiValidation(next, episode, clock.instant());
            }
            else {
                turns.insertCiBaseRewriteValidation(
                        next, episode, completion.authorizationId(),
                        clock.instant());
            }
        }
        return storedAdoptionReceipt(
                store, operation.operationId(), rawDigest);
    }

    public BrainRetryReceipt retryFailedBrain(
            String taskId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String actor,
            String reason)
    {
        required(taskId, "taskId");
        required(failedTurnId, "failedTurnId");
        required(blockerId, "blockerId");
        required(commandId, "commandId");
        required(actor, "actor");
        required(reason, "reason");
        BrainRetryReceipt existing = turns.findBrainRetryReceipt(
                taskId, commandId).orElse(null);
        if (existing != null) {
            requireBranchBrainRetry(existing.family());
            requireSameRetry(existing, failedTurnId, blockerId, actor, reason);
            return existing;
        }
        return commands.execute(taskId, () -> {
            BrainRetryReceipt duplicate = turns.findBrainRetryReceipt(
                    taskId, commandId).orElse(null);
            if (duplicate != null) {
                requireBranchBrainRetry(duplicate.family());
                requireSameRetry(
                        duplicate, failedTurnId, blockerId, actor, reason);
                return duplicate;
            }
            BrainRetryContext context = turns.requireBrainRetryContext(
                    taskId, failedTurnId, blockerId);
            requireBranchBrainRetry(context.family());
            Instant now = clock.instant();
            String identity = taskId + ":" + commandId;
            String rowId = id("remote-repair-brain-replacement", identity);
            String turnId = id("remote-repair-brain-retry-turn", identity);
            String operationId = id(
                    "remote-repair-brain-retry-operation", identity);
            String ticketId = id("remote-repair-brain-retry-ticket", identity);
            BrainRequest replacement = turns.insertBrainReplacement(
                    context, rowId, turnId, operationId, ticketId,
                    retryLaunch(context, turnId, operationId, commandId), now);
            String taskRequestCommandId = id(
                    "request-remote-repair-brain-retry", identity);
            CommandResult<TaskManager.State> requested =
                    tasks.requestBrainReviewInCommand(
                            new TaskManager.BrainReviewRequestCommand(
                                    taskRequestCommandId, actor, taskId,
                                    context.taskEpoch(), context.taskVersion(),
                                    replacement.rowId(), replacement.fence()));
            if (requested.disposition() != CommandResult.Disposition.APPLIED) {
                throw new IllegalStateException(
                        "Remote repair Brain retry was superseded");
            }
            return turns.recordBrainRetry(
                    context, replacement, blockerId, commandId,
                    taskRequestCommandId, actor, reason, now);
        });
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            AgentTurnOwnerResultCodec.OwnerResult raw)
    {
        String rawDigest = digest(write(raw));
        EffectDeliveryReceipt duplicate = duplicate(
                raw.fence().operationId(), rawDigest);
        if (duplicate != null) {
            return receipt(duplicate.acceptance(), duplicate.rawOutcome());
        }
        TurnDelivery context = STEERING_CALLBACK.equals(
                raw.owner().callbackRoute())
                ? turns.requireSteeringDelivery(
                        raw.owner().id(), raw.fence().operationId())
                : turns.requireTurnDelivery(
                        raw.owner().id(), raw.fence().operationId());
        if (!context.fence().equals(toFence(raw.fence()))
                || !expectedRoute(context).equals(
                        raw.owner().callbackRoute())) {
            throw new IllegalArgumentException(
                    "Remote repair Turn result differs from its exact fence");
        }
        if (raw.owner().kind() == DispatchTicket.OwnerKind.STAGE_TURN) {
            return deliverStage(raw, context, rawDigest);
        }
        return deliverBrain(raw, context, rawDigest);
    }

    private DispatchTicket.DeliveryReceipt deliverStage(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            TurnDelivery context,
            String rawDigest)
    {
        Instant now = clock.instant();
        if ("STEERING".equals(context.kind())) {
            return deliverSteering(raw, context, rawDigest, now);
        }
        Request pendingSteering = steering == null ? null
                : steering.findPendingByPredecessor(context.operationId())
                        .orElse(null);
        if (pendingSteering != null) {
            return finishForPendingSteering(
                    raw, context, rawDigest, pendingSteering, now);
        }
        if (!context.current()) {
            turns.finishStageTurn(
                    context, raw.outcome().name(), rawDigest,
                    SUPERSEDED.name(), "SUPERSEDED", null, null,
                    "stale Remote repair subject", now);
            stop(context, "Remote repair StageTurn subject is stale", now);
            return receipt(SUPERSEDED, "Remote repair subject is stale");
        }
        if (raw.outcome() != SUCCEEDED) {
            String error = Objects.toString(
                    raw.payload().error(), "Agent Turn failed");
            turns.finishStageTurn(
                    context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                    raw.outcome() == CANCELED ? "CANCELED" : "FAILED",
                    null, null, error, now);
            blockCurrentStageFailure(
                    raw, context, rawDigest, error, now);
            return receipt(ACCEPTED, "Remote repair StageTurn failed");
        }
        RepairSubmission result = requireRepairSubmission(context);
        ValidatedCodeSubject validated;
        try {
            validated = requireOutputCodeSubject(raw, context);
        }
        catch (IllegalArgumentException | IllegalStateException failure) {
            if (!"CI".equals(context.family())) {
                throw failure;
            }
            String error = "CI repair output proof rejected: "
                    + failure.getMessage();
            turns.finishStageTurn(
                    context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                    "FAILED", null, result.summary(), error, now);
            if (context.baseRepairAuthorizationId() != null) {
                remoteStore.closeBaseRepairAuthorization(
                        context.baseRepairAuthorizationId(), error, now);
            }
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            remoteStore.blockCiEpisode(
                    episode, "CI_REPAIR_OUTPUT_PROOF_MISSING", error,
                    "{\"choices\":[\"MANUAL_TAKEOVER\","
                            + "\"STOP_AUTOMATION\"]}", now);
            return receipt(ACCEPTED,
                    "CI repair output proof failed closed");
        }
        if ("CI".equals(context.family())) {
            if (validated.noChange()) {
                return acceptCiNoChange(
                        raw, context, rawDigest, result.summary(), validated, now);
            }
            turns.finishChangedCiStageTurn(
                    context, raw.outcome().name(), rawDigest,
                    validated.codeSubject(), validated.sourceTreeSha(),
                    validated.resultTreeSha(), result.summary(), now);
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            RepairContext next = turns.requireContext(
                    context.taskId(), context.stageId());
            if (context.baseRepairAuthorizationId() == null) {
                turns.insertCiValidation(next, episode, now);
            }
            else {
                turns.insertCiBaseRewriteValidation(
                        next, episode,
                        context.baseRepairAuthorizationId(), now);
            }
            return receipt(ACCEPTED, "CI validation requested");
        }
        CodeSubject output = requireChangedOutput(validated);
        turns.finishStageTurn(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", output, result.summary(), null, now);
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        if ("CI_PRECONDITION_LOCAL".equals(episode.purpose())) {
            completeLocalCiPrecondition(episode, output, now);
            return receipt(ACCEPTED,
                    "local CI base precondition completed after conflict repair");
        }
        RepairContext next = turns.requireContext(
                context.taskId(), context.stageId());
        remoteStore.insertBranchEffect(
                remoteStore.requireRemoteContext(
                        context.taskId(), context.stageId()),
                episode, remoteStore.requireBranchStep(episode.id(), 4),
                next.codeFingerprint(), next.headSha(), next.baseSha(), now);
        return receipt(ACCEPTED, "branch validation requested");
    }

    private DispatchTicket.DeliveryReceipt finishForPendingSteering(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            TurnDelivery context,
            String rawDigest,
            Request request,
            Instant now)
    {
        boolean acceptOutput = request.mode() == V2StageSteeringControl.Mode.APPEND
                && raw.outcome() == SUCCEEDED && context.current();
        CodeSubject output = null;
        String summary = null;
        if (acceptOutput) {
            RepairSubmission result = requireRepairSubmission(context);
            output = requireChangedOutput(requireOutputCodeSubject(raw, context));
            summary = result.summary();
        }
        DispatchTicket.Acceptance acceptance = acceptOutput
                ? ACCEPTED : SUPERSEDED;
        turns.finishPredecessorForSteering(
                context, raw.outcome().name(), rawDigest, acceptance.name(),
                acceptOutput ? "SUCCEEDED" : "SUPERSEDED", output, summary,
                acceptOutput ? null : "replaced by durable user steering", now);
        return receipt(acceptance,
                acceptOutput ? "Remote predecessor completed before steering"
                        : "Remote predecessor superseded by steering");
    }

    private DispatchTicket.DeliveryReceipt deliverSteering(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            TurnDelivery context,
            String rawDigest,
            Instant now)
    {
        if (!context.current()) {
            turns.finishSteeringTurn(
                    context, raw.outcome().name(), rawDigest, SUPERSEDED.name(),
                    "SUPERSEDED", null, null, "stale Remote steering subject", now);
            stop(context, "Remote steering subject is stale", now);
            return receipt(SUPERSEDED, "Remote steering subject is stale");
        }
        if (raw.outcome() != SUCCEEDED) {
            String error = Objects.toString(
                    raw.payload().error(), "Remote steering Turn failed");
            turns.finishSteeringTurn(
                    context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                    raw.outcome() == CANCELED ? "CANCELED" : "FAILED",
                    null, null, error, now);
            blockCurrentStageFailure(
                    raw, context, rawDigest, error, now);
            return receipt(ACCEPTED, "Remote steering Turn failed");
        }
        RepairSubmission result = requireRepairSubmission(context);
        CodeSubject output = requireChangedOutput(
                requireOutputCodeSubject(raw, context));
        turns.finishSteeringTurn(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", output, result.summary(), null, now);
        if ("CI".equals(context.family())) {
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            RepairContext next = turns.requireContext(
                    context.taskId(), context.stageId());
            String authorizationId = remoteStore
                    .findClaimedBaseRepairAuthorization(episode.id())
                    .map(BaseRepairAuthorization::id)
                    .orElse(null);
            if (authorizationId == null) {
                turns.insertCiValidation(next, episode, now);
            }
            else {
                turns.insertCiBaseRewriteValidation(
                        next, episode, authorizationId, now);
            }
            return receipt(ACCEPTED, "CI validation requested after steering");
        }
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        if ("CI_PRECONDITION_LOCAL".equals(episode.purpose())) {
            completeLocalCiPrecondition(episode, output, now);
            return receipt(ACCEPTED,
                    "local CI base precondition completed after steering");
        }
        RepairContext next = turns.requireContext(
                context.taskId(), context.stageId());
        remoteStore.insertBranchEffect(
                remoteStore.requireRemoteContext(
                        context.taskId(), context.stageId()),
                episode, remoteStore.requireBranchStep(episode.id(), 4),
                next.codeFingerprint(), next.headSha(), next.baseSha(), now);
        return receipt(ACCEPTED, "branch validation requested after steering");
    }

    private DispatchTicket.DeliveryReceipt deliverBrain(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            TurnDelivery context,
            String rawDigest)
    {
        Instant now = clock.instant();
        TaskManager.ResultCommand command = new TaskManager.ResultCommand(
                id("accept-remote-repair-brain", context.operationId()),
                ACTOR, context.taskId(), context.fence());
        if (!context.current() || !tasks.isCurrentBrainResultInCommand(command)) {
            turns.finishBrain(
                    context, raw.outcome().name(), rawDigest,
                    SUPERSEDED.name(), "SUPERSEDED", null, 0, null,
                    "stale Remote Brain subject", now);
            stop(context, "Remote Brain subject is stale", now);
            return receipt(SUPERSEDED, "Remote Brain subject is stale");
        }
        if (raw.outcome() == INDETERMINATE) {
            throw new IllegalStateException(
                    "Remote repair Brain outcome is indeterminate, not terminal");
        }
        if (raw.outcome() != SUCCEEDED) {
            String error = Objects.toString(
                    raw.payload().error(), "Remote repair Brain failed");
            turns.finishBrain(
                    context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                    raw.outcome() == CANCELED ? "CANCELED" : "FAILED",
                    null, 0, null, error, now);
            String blockerId = turns.openBrainFailureBlocker(
                    context, "{\"error\":" + write(error) + "}", now);
            CommandResult<TaskManager.State> accepted =
                    tasks.acceptBrainProtocolFailureInCommand(
                            command, blockerId);
            if (accepted.disposition() != CommandResult.Disposition.APPLIED) {
                throw new IllegalStateException(
                        "Remote repair Brain failure became stale during delivery");
            }
            turns.recordBrainFailure(
                    context, blockerId, raw.outcome().name(), rawDigest, error,
                    accepted.state().version(), now);
            return receipt(ACCEPTED, "Remote repair Brain failed");
        }
        // The review's final message is prose. Its verdict is the row
        // record_development_verdict wrote while the Turn was running; the
        // pre-delivery gate already refused a SUCCEEDED Turn without one.
        AgentBrainResult result = submissions.findBrainVerdict(context.turnId())
                .orElseThrow(() -> new IllegalArgumentException(
                        BRAIN_LABEL + " succeeded without record_development_verdict"));
        TaskManager.BrainVerdict verdict = result.requireVerdict(BRAIN_LABEL);
        turns.finishBrain(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", verdict.name(), result.findings().size(),
                result.summary(), null, now);
        TaskManager.BrainVerdictResult accepted =
                tasks.acceptBrainVerdictInCommand(command, verdict);
        if (accepted.task().disposition()
                == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Remote Brain verdict became stale during delivery");
        }
        if ("CI".equals(context.family())) {
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            if (verdict == TaskManager.BrainVerdict.APPROVED) {
                requestCiPush(episode);
            }
            else {
                closeBaseAuthorization(
                        episode, "Task Brain requested another repair", now);
                nextCiFixOrExhaust(
                        episode, "BRAIN_CHANGES_REQUESTED",
                        "Address every Task Brain finding:\n"
                                + String.join("\n", result.findings()));
            }
            return receipt(ACCEPTED, verdict.name());
        }
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        if (verdict == TaskManager.BrainVerdict.APPROVED) {
            RepairContext next = turns.requireContext(
                    context.taskId(), context.stageId());
            remoteStore.insertBranchEffect(
                    remoteStore.requireRemoteContext(
                            context.taskId(), context.stageId()),
                    episode, remoteStore.requireBranchStep(episode.id(), 6),
                    next.codeFingerprint(), next.headSha(), next.baseSha(), now);
        }
        else {
            remoteStore.failBranchEpisode(
                    episode, "Task Brain requested branch-sync changes", now);
            turns.openEpisodeBlocker(
                    "BRANCH", context.taskId(), context.stageId(),
                    episode.id(), episode.oldHeadSha(),
                    "BRANCH_SYNC_BRAIN_CHANGES_REQUESTED",
                    "{\"findings\":" + write(result.findings()) + "}", now);
        }
        return receipt(ACCEPTED, verdict.name());
    }

    private void completeLocalCiPrecondition(
            BranchEpisode episode, CodeSubject output, Instant at)
    {
        for (int ordinal = 4; ordinal <= 6; ordinal++) {
            remoteStore.skipBranchStep(
                    remoteStore.requireBranchStep(episode.id(), ordinal),
                    "local CI base precondition publishes only through its pending repair",
                    at);
        }
        remoteStore.succeedLocalCiPrecondition(
                episode,
                new SqliteRemoteRuntimeStore.CodeSubject(
                        output.codeFingerprint(), output.headSha(),
                        output.baseSha()),
                at);
        requestFreshObservation(episode.taskId(), episode.stageId());
    }

    private DispatchTicket.DeliveryReceipt acceptCiNoChange(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            TurnDelivery context,
            String rawDigest,
            String summary,
            ValidatedCodeSubject output,
            Instant now)
    {
        int noChangeCount = turns.finishNoChangeCiStageTurn(
                context, raw.outcome().name(), rawDigest,
                output.sourceTreeSha(), output.resultTreeSha(), summary, now);
        CiEpisode episode = remoteStore.requireCiEpisode(
                context.taskId(), context.episodeId());
        if (noChangeCount == 1) {
            return receipt(ACCEPTED,
                    "CI repair made no tree change; corrective Turn awaits fresh observation");
        }
        if (noChangeCount == 2) {
            remoteStore.blockCiEpisode(
                    episode, "CI_REPAIR_NO_CHANGE",
                    "Two exact CI repair executions produced no committed tree change",
                    "{\"choices\":[\"RETRY_ONCE\",\"MANUAL_TAKEOVER\","
                            + "\"STOP_AUTOMATION\"]}", now);
            return receipt(ACCEPTED, "CI repair no-change blocker opened");
        }
        remoteStore.blockCiEpisode(
                episode, "CI_REPAIR_NO_CHANGE_RETRY_EXHAUSTED",
                "The explicitly authorized final CI repair also produced no "
                        + "committed tree change",
                "{\"choices\":[\"MANUAL_TAKEOVER\","
                        + "\"STOP_AUTOMATION\"]}", now);
        return receipt(ACCEPTED,
                "CI repair no-change retry exhausted blocker opened");
    }

    /**
     * V319 calls this only after accepting a fresh exact Remote observation for
     * the terminal predecessor subject.
     */
    @Override
    public boolean startPendingCiNoChangeContinuationInCommand(CiEpisode episode)
    {
        requireNonNull(episode, "episode is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        CiFixContinuationDue due = turns.findPendingCiFixContinuation(episode.id())
                .orElse(null);
        if (due == null) {
            return false;
        }
        Instant at = clock.instant();
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        WorkModel model = workModel(context, "STAGE_TURN");
        int executionAttempt = due.executionAttempt();
        String suffix = episode.id() + ":fix:"
                + due.semanticAttempt() + ":no-change:"
                + executionAttempt;
        String rowId = id("ci-repair-fix-continuation-row", suffix);
        String turnId = id("ci-repair-fix-continuation-turn", suffix);
        String operationId = id("ci-repair-fix-continuation-operation", suffix);
        String ticketId = id("ci-repair-fix-continuation-ticket", suffix);
        String priorPrompt = stagePrompt(
                turns.requireStageTurnLaunchInput(due.predecessorStageTurnId()));
        String prompt = priorPrompt
                + "\n\nCorrective continuation: the preceding exact CI repair "
                + "execution completed but sourceTreeSha and resultTreeSha were "
                + "equal. It made no substantive code change and consumed no CI "
                + "fix budget. Diagnose why, make the required substantive repair, "
                + "and commit a changed tree. Do not create an empty commit.";
        String launchInput = write(launch(
                context, model, "STAGE_TURN", turnId, operationId,
                "STAGE_DEVELOPMENT", stageSystemPrompt(context.roleSkill()),
                prompt));
        turns.insertCiFixContinuation(
                context, episode, due, rowId, turnId, operationId,
                ticketId, launchInput, model.kind().name(), laneMask(model), at);
        return true;
    }

    private void startCiFix(CiEpisode episode, String prompt)
    {
        startCiFix(episode, null, prompt);
    }

    private void startCiFix(
            CiEpisode episode, String authorizationId, String prompt)
    {
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        WorkModel model = workModel(context, "STAGE_TURN");
        int attempt = episode.fixAttemptCount() + 1;
        String suffix = episode.id() + ":fix:" + attempt;
        String turnId = id("ci-repair-stage-turn", suffix);
        String operationId = id("ci-repair-operation", suffix);
        String boundedPrompt = authorizationId == null
                ? appendOnlyTaskRepairPrompt(prompt)
                : prompt;
        String launchInput = write(launch(
                context, model, "STAGE_TURN", turnId, operationId,
                "STAGE_DEVELOPMENT", stageSystemPrompt(context.roleSkill()),
                ciOwnershipPrompt(boundedPrompt)));
        if (authorizationId == null) {
            turns.insertCiStageTurn(
                    context, episode, launchInput, model.kind().name(),
                    laneMask(model), clock.instant());
        }
        else {
            turns.insertCiBaseRepairStageTurn(
                    context, episode, authorizationId, launchInput,
                    model.kind().name(), laneMask(model), clock.instant());
        }
    }

    private void startBaseRepair(
            CiEpisode episode,
            RepairContext context,
            String commandId,
            String authorityKind,
            String automationPolicyId,
            String blockerId,
            String actor,
            String reason,
            String evidence)
    {
        BaseRepairAuthorization authorization = remoteStore.authorizeBaseRepair(
                episode, automationPolicyId, blockerId, commandId,
                authorityKind, actor, reason, context.headSha(),
                clock.instant());
        if (!"CLAIMED".equals(authorization.status())) {
            return;
        }
        CiEpisode current = remoteStore.requireCiEpisode(
                episode.taskId(), episode.id());
        if (remoteStore.hasLiveCiOperation(current.id())) {
            return;
        }
        if (!"OPEN".equals(current.status())) {
            throw new IllegalStateException(
                    "CI base repair is not ready for one exact Turn");
        }
        startCiFix(
                current, authorization.id(), baseRepairPrompt(current, evidence));
    }

    private static String baseRepairPrompt(CiEpisode episode, String evidence)
    {
        return "Repair the deterministic CI failure proven to exist on exact "
                + "base " + episode.subjectBaseSha() + ". Work only on this "
                + "Task branch; never update or push the base branch. Preserve "
                + "the current history exactly: do not amend, rebase, reset, "
                + "squash, reorder, or force-push. Append one or more ordinary "
                + "repair commits at the current tip, leave a clean committed "
                + "head, then report it by calling record_repair_summary. "
                + "ByteQuay will perform the authorized "
                + "deterministic history rewrite.\n\nCI evidence:\n"
                + Objects.toString(evidence, "");
    }

    private static String appendOnlyTaskRepairPrompt(String prompt)
    {
        return "Repair authority: use only append-only commits on the current "
                + "Task branch. Never rewrite base history or amend, rebase, "
                + "reset, squash, reorder, or force-push existing commits. Do "
                + "not push; leave a clean committed head for ByteQuay's "
                + "canonical validation and exact fenced push.\n\n"
                + prompt;
    }

    private static String ciOwnershipPrompt(String prompt)
    {
        return "CI ownership rule: this pull request owns every failed check on "
                + "its exact tested subject, including failures that appear "
                + "pre-existing or unrelated. Do not dismiss, reclassify, or skip "
                + "a failed check. Strict UNKNOWN and base-provenance decisions "
                + "remain ByteQuay's responsibility; repair every failure this "
                + "Turn is authorized to address.\n\n" + prompt;
    }

    private String stagePrompt(String launchInput)
    {
        try {
            return required(json.readTree(launchInput).path("prompt").asText(null),
                    "Frozen CI repair prompt");
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Frozen CI repair launch is invalid", e);
        }
    }

    private void requestBranchBrain(
            RepairContext context, BranchEpisode episode, BranchStep step)
    {
        WorkModel model = workModel(context, "TASK_TURN");
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String turnId = id("branch-sync-task-turn", suffix);
        String operationId = id("branch-sync-operation", suffix);
        String prompt = "Review the exact conflict repair rebased onto "
                + episode.targetBaseSha() + ". Call record_development_verdict "
                + "with APPROVED only when the resolved head preserves Task "
                + "intent and is safe to push.";
        BrainRequest brain = turns.insertBranchBrain(
                context, episode, step,
                write(launch(context, model, "TASK_TURN", turnId,
                        operationId, "TASK_BRAIN_READ_ONLY",
                        brainSystemPrompt(context.roleSkill()), prompt)),
                model.kind().name(), laneMask(model), clock.instant());
        armBrain(context, brain);
    }

    private void armBrain(RepairContext context, BrainRequest brain)
    {
        CommandResult<TaskManager.State> requested =
                tasks.requestBrainReviewInCommand(
                        new TaskManager.BrainReviewRequestCommand(
                                id("request-remote-repair-brain",
                                        brain.operationId()),
                                ACTOR, context.taskId(), context.taskEpoch(),
                                context.taskVersion(), brain.rowId(),
                                brain.fence()));
        if (requested.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Remote repair Brain request was superseded");
        }
    }

    private void requestCiPush(CiEpisode episode)
    {
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        turns.insertCiPush(
                context, episode, baseAuthorizationId(episode),
                clock.instant());
    }

    private String baseAuthorizationId(CiEpisode episode)
    {
        if (!Classification.BASE_DETERMINISTIC.name().equals(
                episode.classification())) {
            return null;
        }
        return remoteStore.findClaimedBaseRepairAuthorization(episode.id())
                .map(BaseRepairAuthorization::id)
                .orElseThrow(() -> new IllegalStateException(
                        "Proven base repair lost its exact authorization"));
    }

    private void closeBaseAuthorization(
            CiEpisode episode, String evidence, Instant at)
    {
        if (Classification.BASE_DETERMINISTIC.name().equals(
                episode.classification())) {
            remoteStore.findClaimedBaseRepairAuthorization(episode.id())
                    .ifPresent(authorization ->
                            remoteStore.closeBaseRepairAuthorization(
                                    authorization.id(), evidence, at));
        }
    }

    private void nextCiFixOrExhaust(
            CiEpisode episode, String sourceKind, String prompt)
    {
        CiEpisode current = remoteStore.requireCiEpisode(
                episode.taskId(), episode.id());
        if (current.fixAttemptCount() < current.fixAttemptLimit()
                && current.pushCount() < current.pushLimit()) {
            turns.insertCiNextFixDue(
                    current, sourceKind, prompt, clock.instant());
            return;
        }
        String evaluationId = current.lastPushResultEvaluationId() == null
                ? current.failedCiEvaluationId()
                : current.lastPushResultEvaluationId();
        remoteStore.exhaustCiEpisode(current, evaluationId, clock.instant());
    }

    /**
     * V319 calls this after its fresh-observation authorization for the pending
     * row. A validation/Brain delivery never starts another writer directly.
     */
    @Override
    public boolean startPendingCiNextFixInCommand(CiEpisode episode)
    {
        requireNonNull(episode, "episode is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        CiNextFixDue due = turns.findPendingCiNextFix(episode.id()).orElse(null);
        if (due == null) {
            return false;
        }
        CiEpisode current = remoteStore.requireCiEpisode(
                episode.taskId(), episode.id());
        Instant now = clock.instant();
        if (Classification.BASE_DETERMINISTIC.name().equals(
                current.classification())) {
            remoteStore.reopenBaseRepairEpisode(current);
            current = remoteStore.requireCiEpisode(current.taskId(), current.id());
            RepairContext context = turns.requireContext(
                    current.taskId(), current.stageId());
            if (!context.autoApprove()) {
                remoteStore.blockCiEpisode(
                        current, "CI_BASE_REPAIR_REQUIRED",
                        "Base-owned CI failure requires scoped base repair",
                        "{\"choices\":[\"START_BASE_REPAIR\","
                                + "\"MANUAL_TAKEOVER\","
                                + "\"STOP_AUTOMATION\"]}", now);
                return false;
            }
            startBaseRepair(
                    current, context,
                    id("auto-ci-base-repair",
                            current.id() + ":" + due.requestedSemanticAttempt()
                                    + ":" + context.automationPolicyId()),
                    "AUTO_APPROVE_POLICY", context.automationPolicyId(),
                    null, null,
                    "Current Task policy authorizes another proven base repair",
                    due.prompt());
        }
        else {
            startCiFix(current, due.prompt());
        }
        turns.consumeCiNextFixDue(due, now);
        return true;
    }

    @Override
    public boolean startPendingManualCiFixInCommand(CiEpisode episode)
    {
        requireNonNull(episode, "episode is null");
        TaskCommandExecutor.requireCurrent(episode.taskId());
        ManualCiTurnIntent intent = remoteStore.findManualCiTurnIntent(
                episode.id()).orElse(null);
        if (intent == null) {
            return false;
        }
        CiEpisode current = remoteStore.requireCiEpisode(
                episode.taskId(), episode.id());
        startCiFix(
                current, intent.baseRepairAuthorizationId(),
                baseRepairPrompt(
                        current, remoteStore.requireFailedCiEvidence(current)));
        remoteStore.consumeManualCiTurnIntent(intent, clock.instant());
        return true;
    }

    /** Parks CI steering until a post-command accepted observation proves it fresh. */
    public boolean prepareCiSteeringInCommand(Request request)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        if (!"REMOTE_CI_REPAIR".equals(request.predecessor().purpose())) {
            return true;
        }
        CiEpisode episode = remoteStore.findLiveCiEpisode(request.stageId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI steering Episode is no longer live"));
        boolean fresh = remoteStore.prepareCiSteeringFence(
                request.id(), episode.id(), request.predecessor().attempt() + 1,
                clock.instant());
        if (!fresh) {
            requestFreshObservation(request.taskId(), request.stageId());
        }
        return fresh;
    }

    private void requestFreshObservation(String taskId, String stageId)
    {
        if (remoteStore.findLiveObservation(stageId).isEmpty()) {
            remoteStore.insertObservation(
                    remoteStore.requireRemoteContext(taskId, stageId),
                    clock.instant());
        }
    }

    private void stop(TurnDelivery context, String reason, Instant at)
    {
        if ("CI".equals(context.family())) {
            if (context.baseRepairAuthorizationId() != null) {
                remoteStore.closeBaseRepairAuthorization(
                        context.baseRepairAuthorizationId(), reason, at);
            }
            remoteStore.stopCiEpisode(
                    remoteStore.requireCiEpisode(
                            context.taskId(), context.episodeId()),
                    reason, at);
            return;
        }
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        remoteStore.failBranchEpisode(episode, reason, at);
    }

    /**
     * A current Stage execution failure is a domain result, not an
     * infrastructure retry and not permission to reopen a fresh Episode on
     * the next identical poll. Keep CI on its exact subject behind one typed
     * blocker; BranchSync records its existing exact-subject exhaustion.
     */
    private void blockCurrentStageFailure(
            AgentTurnOwnerResultCodec.OwnerResult raw,
            TurnDelivery context,
            String rawDigest,
            String reason,
            Instant at)
    {
        if ("CI".equals(context.family())) {
            boolean malformed = raw.payload().disposition()
                    == Disposition.OWNER_OUTPUT_MALFORMED;
            String malformedOutput = raw.payload().finalText();
            boolean normalizable = malformed
                    && "FIX_STAGE_TURN".equals(context.kind())
                    && malformedOutput != null
                    && !malformedOutput.isBlank();
            if (!normalizable
                    && context.baseRepairAuthorizationId() != null) {
                remoteStore.closeBaseRepairAuthorization(
                        context.baseRepairAuthorizationId(), reason, at);
            }
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            remoteStore.blockCiEpisode(
                    episode,
                    malformed ? "CI_REPAIR_OUTPUT_MALFORMED"
                            : "CI_REPAIR_TURN_FAILED",
                    reason,
                    "{\"choices\":[\"MANUAL_TAKEOVER\","
                            + "\"STOP_AUTOMATION\"]}",
                    at);
            if (normalizable) {
                String blockerId = id("ci-repair-blocker",
                        episode.id() + ":CI_REPAIR_OUTPUT_MALFORMED");
                normalizationStore().insertMalformedDue(
                        context, malformedOutput,
                        raw.payload().outputCodeSubject(), rawDigest,
                        blockerId, at);
            }
            return;
        }
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        remoteStore.failBranchEpisode(episode, reason, at);
    }

    private EffectDeliveryReceipt duplicate(
            String operationId, String rawDigest)
    {
        EffectDeliveryReceipt receipt = turns.findSteeringReceipt(operationId)
                .orElseGet(() -> remoteStore.findCiEffectReceipt(operationId)
                        .orElseGet(() -> remoteStore
                                .findBranchEffectReceipt(operationId)
                                .orElseGet(() -> turns
                                        .findReplacementReceipt(operationId)
                                        .orElseGet(() -> turns
                                                .findCiFixContinuationReceipt(
                                                        operationId)
                                                .orElse(null)))));
        if (receipt != null && !rawDigest.equals(receipt.rawDigest())) {
            throw new IllegalStateException(
                    "Remote repair Turn was redelivered with different evidence");
        }
        return receipt;
    }

    private static ValidatedCodeSubject requireOutputCodeSubject(
            AgentTurnOwnerResultCodec.OwnerResult result,
            TurnDelivery context)
    {
        OutputCodeSubject output =
                result.requireOutputCodeSubject(context.baseSha());
        if (!output.clean()) {
            throw new IllegalStateException(
                    "Remote repair Turn left uncommitted changes");
        }
        if (output.sourceTreeSha() == null || output.resultTreeSha() == null) {
            throw new IllegalStateException(
                    "Remote repair Turn lacks exact writer tree proof");
        }
        boolean noChange = output.sourceTreeSha().equals(output.resultTreeSha());
        if ("CI".equals(context.family())
                && !noChange && !context.headSha().equals(
                output.sourceHeadMergeBaseSha())) {
            throw new IllegalStateException(
                    "Remote CI repair lacks append-only Task-head lineage proof");
        }
        if (noChange && !context.headSha().equals(output.headSha())) {
            throw new IllegalStateException(
                    "Remote CI no-change output was not restored to its source head");
        }
        if (output.discardedNoChangeHeadSha() != null
                && (!noChange
                    || !context.headSha().equals(output.restoredHeadSha())
                    || !output.headSha().equals(output.restoredHeadSha()))) {
            throw new IllegalStateException(
                    "Remote CI no-change restore proof is stale");
        }
        if (!noChange && context.headSha().equals(output.headSha())) {
            throw new IllegalStateException(
                    "Remote repair Turn did not create a new committed head");
        }
        return new ValidatedCodeSubject(
                new CodeSubject(
                        output.codeFingerprint(), output.headSha(), output.baseSha()),
                output.sourceTreeSha(), output.resultTreeSha(), noChange);
    }

    private static CodeSubject requireChangedOutput(ValidatedCodeSubject output)
    {
        if (output.noChange()) {
            throw new IllegalStateException(
                    "Remote repair Turn did not change the committed tree");
        }
        return output.codeSubject();
    }

    private WorkModel workModel(RepairContext context, String ownerKind)
    {
        WorkModel model;
        try {
            model = workModelReader.readValue(context.workModelSnapshot());
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Frozen Remote repair work model is invalid", e);
        }
        if (!context.provider().equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !context.model().equals(model.model())) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model identify different engines");
        }
        if (reasoningEfforts != null) {
            model = "TASK_TURN".equals(ownerKind)
                    ? reasoningEfforts.forTask(
                            context.trunkId(), context.taskId(), model)
                    : reasoningEfforts.forStage(
                            context.trunkId(), context.taskId(),
                            context.stageId(), model);
        }
        return model;
    }

    private String retryLaunch(
            BrainRetryContext context,
            String turnId,
            String operationId,
            String commandId)
    {
        try {
            ObjectNode launch = (ObjectNode) json.readTree(
                    context.failedLaunchInput());
            String prompt = launch.path("fallbackPrompt").isTextual()
                    ? launch.path("fallbackPrompt").asText()
                    : launch.path("prompt").asText(null);
            required(prompt, "Frozen Remote repair Brain prompt");
            launch.remove(List.of(
                    "resumeSessionId", "fallbackPrompt",
                    "priorCumulativeInputTokens",
                    "priorCumulativeOutputTokens"));
            launch.put("prompt", prompt
                    + "\n\nRetry instruction: start one fresh read-only Task Brain "
                    + "session for the same exact subject. Do not resume the "
                    + "failed provider session.\nRetry trace: commandId="
                    + commandId + ", predecessorTurnId="
                    + context.failedTurnId());
            if (!launch.path("toolEndpoint").isObject()) {
                throw new IllegalArgumentException(
                        "Frozen Remote repair Brain endpoint is missing");
            }
            ObjectNode endpoint = (ObjectNode) launch.path("toolEndpoint");
            endpoint.put("ownerId", turnId);
            endpoint.put("operationId", operationId);
            String oldUrl = required(
                    endpoint.path("url").asText(null),
                    "Frozen Remote repair Brain endpoint URL");
            endpoint.put("url", oldUrl
                    .replace(context.failedTurnId(), turnId)
                    .replace(context.failedOperationId(), operationId));
            return write(launch);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Frozen Remote repair Brain launch is invalid", e);
        }
    }

    private static void requireSameRetry(
            BrainRetryReceipt receipt,
            String failedTurnId,
            String blockerId,
            String actor,
            String reason)
    {
        if (!receipt.failedTurnId().equals(failedTurnId)
                || !receipt.blockerId().equals(blockerId)
                || !receipt.actor().equals(actor)
                || !receipt.reason().equals(reason)) {
            throw new IllegalArgumentException(
                    "Remote repair Brain retry command was already used differently");
        }
    }

    private static void requireBranchBrainRetry(String family)
    {
        if (!"BRANCH".equals(family)) {
            throw new IllegalStateException(
                    "CI repair Brain recovery is retired");
        }
    }

    private String normalizationLaunch(
            NormalizationDue due, String turnId, String operationId)
    {
        ObjectNode source;
        try {
            source = (ObjectNode) json.readTree(due.sourceLaunchInput());
        }
        catch (JsonProcessingException | ClassCastException failure) {
            throw new IllegalArgumentException(
                    "Frozen Remote repair launch is invalid", failure);
        }
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", required(
                source.path("transport").asText(null), "source transport"));
        launch.put("provider", required(
                source.path("provider").asText(null), "source provider"));
        putNullable(launch, "credentialAccount",
                source.path("credentialAccount").asText(null));
        launch.put("model", required(
                source.path("model").asText(null), "source model"));
        putNullable(launch, "reasoningEffort",
                source.path("reasoningEffort").asText(null));
        launch.put("workingDirectory", required(
                source.path("workingDirectory").asText(null),
                "source working directory"));
        launch.put("systemPrompt", """
                You are a syntax-only result normalizer. Do not inspect files, use tools, edit the workspace, or perform remote effects.
                Return exactly one raw JSON object shaped {"schemaVersion":1,"summary":"string"}.
                Preserve the meaning of the frozen malformed result. Do not add fields, Markdown fences, or surrounding prose.
                """);
        launch.put("prompt", """
                Normalize this frozen malformed Remote CI repair result into the required shape.

                Required shape:
                %s

                Source trace:
                sourceOperationId=%s
                sourceRawResultDigest=%s
                taskId=%s
                taskEpoch=%d
                stageId=%s
                stageGeneration=%d
                sourceCodeSubjectRevision=%d
                sourceCodeSubjectKind=%s
                sourceCodeSubjectId=%s
                expectedCodeFingerprint=%s
                expectedHeadSha=%s
                expectedBaseSha=%s

                Frozen malformed output encoded as one JSON string:
                %s""".formatted(
                        due.requiredResultShape(), due.sourceOperationId(),
                        due.sourceRawResultDigest(), due.taskId(),
                        due.taskEpoch(), due.stageId(), due.stageGeneration(),
                        due.sourceCodeSubjectRevision(),
                        due.sourceCodeSubjectKind(),
                        due.sourceCodeSubjectId(),
                        due.expectedCodeFingerprint(), due.expectedHeadSha(),
                        due.expectedBaseSha(), write(due.malformedOutput())));
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/task-turns/" + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", "TASK_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "TASK_BRAIN_READ_ONLY");
        return write(launch);
    }

    private String normalizationEvidence(
            NormalizationOperation operation,
            String rawDigest,
            String normalizedPayload)
    {
        ObjectNode evidence = json.createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("normalizationDueId", operation.dueId());
        evidence.put("normalizationTurnId", operation.turnId());
        evidence.put("normalizationOperationId", operation.operationId());
        evidence.put("sourceOperationId", operation.sourceOperationId());
        evidence.put("sourceCodeSubjectRevision",
                operation.sourceCodeSubjectRevision());
        evidence.put("sourceCodeSubjectKind",
                operation.sourceCodeSubjectKind());
        evidence.put("sourceCodeSubjectId", operation.sourceCodeSubjectId());
        evidence.put("rawResultDigest", rawDigest);
        if (normalizedPayload != null) {
            evidence.put("normalizedPayload", normalizedPayload);
            evidence.put("normalizedPayloadDigest", digest(normalizedPayload));
        }
        return write(evidence);
    }

    private AdoptionResult decodeAdoption(String value)
    {
        try {
            AdoptionResult result = adoptionReader.readValue(required(
                    value, "Remote repair adoption result"));
            if (result.schemaVersion() != 1) {
                throw new IllegalArgumentException(
                        "Unsupported Remote repair adoption result version");
            }
            return result;
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Remote repair adoption result is not strict JSON", failure);
        }
    }

    private static void requireAdoptionResult(
            RemoteRepairCommitAdoptionOperationHandler.Operation operation,
            DispatchTicket.Outcome outcome,
            AdoptionResult result)
    {
        if (result == null) {
            if (outcome == SUCCEEDED) {
                throw new IllegalArgumentException(
                        "Successful Remote repair adoption lacks a result");
            }
            return;
        }
        if (!operation.id().equals(result.adoptionOperationId())
                || !operation.operationId().equals(result.operationId())
                || !operation.normalizationId().equals(
                        result.normalizationId())
                || !operation.sourceOperationId().equals(
                        result.sourceOperationId())
                || !operation.sourceHeadSha().equals(result.sourceHeadSha())
                || !operation.expectedBaseSha().equals(
                        result.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Remote repair adoption result identity is stale");
        }
        boolean compatible = switch (outcome) {
            case SUCCEEDED -> result.disposition()
                    == RemoteRepairCommitAdoptionOperationHandler.Disposition.ADOPTED;
            case CANCELED -> result.disposition()
                    == RemoteRepairCommitAdoptionOperationHandler.Disposition.CANCELED;
            case FAILED -> result.disposition()
                    == RemoteRepairCommitAdoptionOperationHandler.Disposition.FAILED
                    || result.disposition()
                        == RemoteRepairCommitAdoptionOperationHandler.Disposition.STALE;
            case INDETERMINATE -> false;
        };
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Remote repair adoption outcome and result disagree");
        }
    }

    private static ResultFence adoptionFence(
            RemoteRepairCommitAdoptionOperationHandler.Operation operation)
    {
        return new ResultFence(
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.operationId(),
                operation.attempt(), operation.sourceCodeFingerprint(),
                operation.sourceHeadSha(), operation.expectedBaseSha());
    }

    private static void requireSameRawResult(
            ReplayReceipt receipt, String rawDigest)
    {
        if (!rawDigest.equals(receipt.rawDigest())) {
            throw new IllegalStateException(
                    "Remote repair operation was redelivered with different evidence");
        }
    }

    private static DispatchTicket.DeliveryReceipt storedNormalizationReceipt(
            SqliteRemoteRepairNormalizationStore store,
            String operationId,
            String rawDigest)
    {
        ReplayReceipt receipt = store.findNormalizationReceipt(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Remote repair normalization receipt is missing"));
        requireSameRawResult(receipt, rawDigest);
        return receipt.deliveryReceipt();
    }

    private static DispatchTicket.DeliveryReceipt storedAdoptionReceipt(
            SqliteRemoteRepairNormalizationStore store,
            String operationId,
            String rawDigest)
    {
        ReplayReceipt receipt = store.findAdoptionReceipt(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Remote repair adoption receipt is missing"));
        requireSameRawResult(receipt, rawDigest);
        return receipt.deliveryReceipt();
    }

    private SqliteRemoteRepairNormalizationStore normalizationStore()
    {
        return requireNonNull(
                normalizations,
                "Remote repair normalization store is not configured");
    }

    private ObjectNode launch(
            RepairContext context,
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
            RepairContext context,
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

    /** The result the Turn recorded through {@code record_repair_summary}. Its
     *  final message is prose and nobody parses it. */
    private RepairSubmission requireRepairSubmission(TurnDelivery context)
    {
        return submissions.findRepairSubmission(context.turnId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Remote repair StageTurn succeeded without "
                                + "record_repair_summary"));
    }

    /**
     * The one payload still carried in a final message: the result normalizer's.
     * It is launched deliberately tool-free — its whole job is to restate a
     * frozen malformed result, and V324 pins the adopted payload to be exactly
     * the text it returned, so there is nothing for a tool to add.
     */
    private StageResult decodeStage(String value)
    {
        try {
            StageResult result = stageReader.readValue(required(
                    value, "Stage repair result"));
            if (result.schemaVersion() != 1) {
                throw new IllegalArgumentException(
                        "Unsupported Remote repair result version");
            }
            required(result.summary(), "Stage repair summary");
            return result;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Remote repair result is not strict JSON", e);
        }
    }

    private static void requireSubject(
            RepairContext context,
            RemoteEffectOperationHandler.Result result)
    {
        if (!Objects.equals(context.codeFingerprint(), result.codeFingerprint())
                || !context.headSha().equals(result.headSha())
                || !context.baseSha().equals(result.baseSha())) {
            throw new IllegalArgumentException(
                    "Remote repair subject differs from prior exact effect");
        }
    }

    private static boolean supported(DispatchTicket.OwnerReference owner)
    {
        return owner.kind() == DispatchTicket.OwnerKind.STAGE_TURN
                && (CI_STAGE_CALLBACK.equals(owner.callbackRoute())
                    || BRANCH_STAGE_CALLBACK.equals(owner.callbackRoute())
                    || STEERING_CALLBACK.equals(owner.callbackRoute()))
                || owner.kind() == DispatchTicket.OwnerKind.TASK_TURN
                && (CI_BRAIN_CALLBACK.equals(owner.callbackRoute())
                    || BRANCH_BRAIN_CALLBACK.equals(owner.callbackRoute())
                    || NORMALIZATION_CALLBACK.equals(owner.callbackRoute()));
    }

    private static String expectedRoute(TurnDelivery context)
    {
        if ("STEERING".equals(context.kind())) {
            return STEERING_CALLBACK;
        }
        if ("CI".equals(context.family())) {
            return "FIX_STAGE_TURN".equals(context.kind())
                    ? CI_STAGE_CALLBACK : CI_BRAIN_CALLBACK;
        }
        return "CONFLICT_REPAIR".equals(context.kind())
                ? BRANCH_STAGE_CALLBACK : BRANCH_BRAIN_CALLBACK;
    }

    private static int laneMask(WorkModel model)
    {
        return model.kind() == WorkModelKind.CLI ? 1 : 2;
    }

    private static ResultFence toFence(DispatchTicket.OperationFence fence)
    {
        return new ResultFence(
                requireNonNull(fence.taskEpoch(), "task epoch is null"),
                requireNonNull(fence.stageId(), "stage id is null"),
                requireNonNull(
                        fence.stageGeneration(), "stage generation is null"),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
    }

    private ObjectReader strictReader(Class<?> type)
    {
        return json.readerFor(type)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize Remote repair evidence", e);
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, acceptance.name() + ":" + evidence);
    }

    private static String required(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private static void putNullable(
            ObjectNode node, String name, String value)
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
        String base = """
                You own one exact Remote Development repair. Edit only the supplied Task worktree.
                Do not push or perform other remote effects.
                Report your result by calling record_repair_summary once, as your last act.
                The repair is accepted on that call; one that ends without it is discarded.
                Your final message is not the result, but do not leave it empty:
                write a short plain summary of what you did. Recovery reads it
                when the tool call is missing.
                """;
        return roleSkill == null || roleSkill.isBlank()
                ? base : base + "\n\nRole skill:\n" + roleSkill;
    }

    private static String brainSystemPrompt(String roleSkill)
    {
        String base = """
                You are the read-only Task Brain reviewing one exact Remote repair.
                Do not edit files or perform remote effects.
                Report your verdict by calling record_development_verdict once, as your last act.
                Set verdict to APPROVED or CHANGES_REQUESTED. APPROVED takes an empty findings list; CHANGES_REQUESTED takes one or more non-blank findings.
                The review is accepted on that call; one that ends without it is discarded.
                Your final message is not the result, but do not leave it empty:
                write a short plain summary of what you did. Recovery reads it
                when the tool call is missing.
                """;
        return roleSkill == null || roleSkill.isBlank()
                ? base : base + "\n\nRole skill:\n" + roleSkill;
    }

    private static String steeringPrompt(
            Request request, List<Attachment> attachments, String purpose)
    {
        StringBuilder prompt = new StringBuilder(
                "Apply this user steering to the exact current Remote repair. ")
                .append("Keep the existing ").append(purpose)
                .append(" objective and frozen subject. Do not push.\n\n")
                .append(request.body());
        if (!attachments.isEmpty()) {
            prompt.append("\n\nDurable attachments:\n");
            attachments.forEach(attachment -> prompt
                    .append("- ").append(attachment.contentRef()).append('\n'));
        }
        return prompt.toString();
    }

    private record ValidatedCodeSubject(
            CodeSubject codeSubject,
            String sourceTreeSha,
            String resultTreeSha,
            boolean noChange) {}

    public record StageResult(int schemaVersion, String summary) {}

}
