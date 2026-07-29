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
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.Classification;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.CodeSubject;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.RepairContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.TurnDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
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

/** Finite typed Turn continuation shared by CI repair and branch conflicts. */
public final class RemoteRepairTurnRuntime
        implements RemoteCiRepairRuntimeCoordinator.DeterministicRepairPort,
        BranchSyncRuntimeCoordinator.ConflictRepairPort,
        BranchSyncRuntimeCoordinator.BrainReviewPort
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

    private static final String ACTOR = "v2-remote-repair";

    private final TaskCommandExecutor commands;
    private final TaskManager tasks;
    private final SqliteRemoteRuntimeStore remoteStore;
    private final SqliteRemoteRepairTurnStore turns;
    private final ObjectMapper json;
    private final ObjectReader stageReader;
    private final ObjectReader brainReader;
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;
    private final boolean requireCiBrainReview;
    private SqliteStageSteeringStore steering;

    public RemoteRepairTurnRuntime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            SqliteRemoteRuntimeStore remoteStore,
            SqliteRemoteRepairTurnStore turns,
            ObjectMapper json,
            Clock clock,
            int serverPort,
            boolean requireCiBrainReview)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.remoteStore = requireNonNull(remoteStore, "remoteStore is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.json = requireNonNull(json, "json is null");
        this.stageReader = strictReader(StageResult.class);
        this.brainReader = strictReader(BrainResult.class);
        this.workModelReader = strictReader(WorkModel.class);
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
        this.requireCiBrainReview = requireCiBrainReview;
    }

    @Autowired
    void setSteeringStore(SqliteStageSteeringStore steering)
    {
        this.steering = requireNonNull(steering, "steering is null");
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
        WorkModel model = workModel(context);
        String prompt = steeringPrompt(
                request, ownerStore.attachments(request.id()), purpose);
        String turnId = id("remote-repair-steering-turn", request.id());
        String operationId = id("remote-repair-steering-operation", request.id());
        return turns.insertSteeringTurn(
                request,
                write(launch(context, model, "STAGE_TURN", turnId,
                        operationId, "STAGE_DEVELOPMENT",
                        stageSystemPrompt(context.roleSkill()), prompt)),
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
        if (classification == Classification.TASK_DETERMINISTIC) {
            startCiFix(episode,
                    "Fix the deterministic Task-owned CI failures for the exact "
                            + "failed head. Do not push. Commit the repair before "
                            + "returning.\n\nCI evidence:\n"
                            + write(candidate.ciEvaluation().checks()));
            return;
        }
        if (classification == Classification.BASE_DETERMINISTIC) {
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
                    "{\"choices\":[\"CLASSIFY_TASK\",\"CLASSIFY_BASE\","
                            + "\"RERUN\",\"MANUAL_TAKEOVER\"]}",
                    clock.instant());
            return;
        }
        throw new IllegalStateException(
                "Rerun-only CI classification reached code repair");
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
                    current, "Canonical validation failed:\n"
                            + Objects.toString(result.evidence(), result.error()));
            return;
        }
        if (requireCiBrainReview) {
            requestCiBrain(current);
        }
        else {
            requestCiPush(current);
        }
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
        WorkModel model = workModel(context);
        String prompt = "Resolve every conflict while rebasing the exact Task "
                + "head onto base " + episode.targetBaseSha()
                + ". Do not push. Finish the rebase and commit the resolved "
                + "head before returning.\n\nConflict evidence:\n"
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

    public DispatchTicket.DeliveryReceipt deliver(
            AgentTurnOwnerResultCodec.OwnerResult result)
    {
        requireNonNull(result, "result is null");
        if (!supported(result.owner())) {
            return receipt(SUPERSEDED, "Remote repair Turn route is stale");
        }
        String taskId = STEERING_CALLBACK.equals(result.owner().callbackRoute())
                ? turns.requireSteeringTaskId(
                        result.owner().id(), result.fence().operationId())
                : turns.requireTurnTaskId(
                        result.owner().id(), result.fence().operationId());
        return commands.execute(taskId, () -> deliverInCommand(result));
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
            stop(context, error, now);
            return receipt(ACCEPTED, "Remote repair StageTurn failed");
        }
        StageResult result = decodeStage(raw.payload().finalText());
        CodeSubject output = requireOutputCodeSubject(raw, context);
        turns.finishStageTurn(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", output, result.summary(), null, now);
        if ("CI".equals(context.family())) {
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            RepairContext next = turns.requireContext(
                    context.taskId(), context.stageId());
            turns.insertCiValidation(next, episode, now);
            return receipt(ACCEPTED, "CI validation requested");
        }
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
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
            StageResult result = decodeStage(raw.payload().finalText());
            output = requireOutputCodeSubject(raw, context);
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
            stop(context, error, now);
            return receipt(ACCEPTED, "Remote steering Turn failed");
        }
        StageResult result = decodeStage(raw.payload().finalText());
        CodeSubject output = requireOutputCodeSubject(raw, context);
        turns.finishSteeringTurn(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", output, result.summary(), null, now);
        if ("CI".equals(context.family())) {
            CiEpisode episode = remoteStore.requireCiEpisode(
                    context.taskId(), context.episodeId());
            turns.insertCiValidation(
                    turns.requireContext(context.taskId(), context.stageId()),
                    episode, now);
            return receipt(ACCEPTED, "CI validation requested after steering");
        }
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
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
        if (raw.outcome() != SUCCEEDED) {
            throw new IllegalStateException(
                    "Remote Brain failed without a typed terminal verdict");
        }
        BrainResult result = decodeBrain(raw.payload().finalText());
        TaskManager.BrainVerdict verdict = verdict(result);
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
                nextCiFixOrExhaust(
                        episode, "Address every Task Brain finding:\n"
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

    private void startCiFix(CiEpisode episode, String prompt)
    {
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        WorkModel model = workModel(context);
        int attempt = episode.fixAttemptCount() + 1;
        String suffix = episode.id() + ":fix:" + attempt;
        String turnId = id("ci-repair-stage-turn", suffix);
        String operationId = id("ci-repair-operation", suffix);
        turns.insertCiStageTurn(
                context, episode,
                write(launch(context, model, "STAGE_TURN", turnId,
                        operationId, "STAGE_DEVELOPMENT",
                        stageSystemPrompt(context.roleSkill()), prompt)),
                model.kind().name(), laneMask(model), clock.instant());
    }

    private void requestCiBrain(CiEpisode episode)
    {
        RepairContext context = turns.requireContext(
                episode.taskId(), episode.stageId());
        WorkModel model = workModel(context);
        int attempt = episode.fixAttemptCount();
        String suffix = episode.id() + ":brain:" + attempt;
        String turnId = id("ci-repair-task-turn", suffix);
        String operationId = id("ci-repair-operation", suffix);
        String prompt = "Review the exact deterministic CI repair. Return "
                + "APPROVED only when the Task-owned failure is fixed and the "
                + "current head is safe to push.";
        BrainRequest brain = turns.insertCiBrain(
                context, episode,
                write(launch(context, model, "TASK_TURN", turnId,
                        operationId, "TASK_BRAIN_READ_ONLY",
                        brainSystemPrompt(context.roleSkill()), prompt)),
                model.kind().name(), laneMask(model), clock.instant());
        armBrain(context, brain);
    }

    private void requestBranchBrain(
            RepairContext context, BranchEpisode episode, BranchStep step)
    {
        WorkModel model = workModel(context);
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String turnId = id("branch-sync-task-turn", suffix);
        String operationId = id("branch-sync-operation", suffix);
        String prompt = "Review the exact conflict repair rebased onto "
                + episode.targetBaseSha() + ". Return APPROVED only when the "
                + "resolved head preserves Task intent and is safe to push.";
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
        turns.insertCiPush(context, episode, clock.instant());
    }

    private void nextCiFixOrExhaust(CiEpisode episode, String prompt)
    {
        CiEpisode current = remoteStore.requireCiEpisode(
                episode.taskId(), episode.id());
        if (current.fixAttemptCount() < current.fixAttemptLimit()
                && current.pushCount() < current.pushLimit()) {
            startCiFix(current, prompt);
            return;
        }
        String evaluationId = current.lastPushResultEvaluationId() == null
                ? current.failedCiEvaluationId()
                : current.lastPushResultEvaluationId();
        remoteStore.exhaustCiEpisode(current, evaluationId, clock.instant());
    }

    private void stop(TurnDelivery context, String reason, Instant at)
    {
        if ("CI".equals(context.family())) {
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

    private EffectDeliveryReceipt duplicate(
            String operationId, String rawDigest)
    {
        EffectDeliveryReceipt receipt = turns.findSteeringReceipt(operationId)
                .orElseGet(() -> remoteStore.findCiEffectReceipt(operationId)
                        .orElseGet(() -> remoteStore
                                .findBranchEffectReceipt(operationId)
                                .orElse(null)));
        if (receipt != null && !rawDigest.equals(receipt.rawDigest())) {
            throw new IllegalStateException(
                    "Remote repair Turn was redelivered with different evidence");
        }
        return receipt;
    }

    private static CodeSubject requireOutputCodeSubject(
            AgentTurnOwnerResultCodec.OwnerResult result,
            TurnDelivery context)
    {
        OutputCodeSubject output =
                result.requireOutputCodeSubject(context.baseSha());
        if (!output.clean()) {
            throw new IllegalStateException(
                    "Remote repair Turn left uncommitted changes");
        }
        if (context.headSha().equals(output.headSha())) {
            throw new IllegalStateException(
                    "Remote repair Turn did not create a new committed head");
        }
        return new CodeSubject(
                output.codeFingerprint(), output.headSha(), output.baseSha());
    }

    private WorkModel workModel(RepairContext context)
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
        return model;
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

    private BrainResult decodeBrain(String value)
    {
        try {
            BrainResult result = brainReader.readValue(required(
                    value, "Brain result"));
            if (result.schemaVersion() != 1
                    || result.findings().stream().anyMatch(
                            finding -> finding == null || finding.isBlank())) {
                throw new IllegalArgumentException(
                        "Remote repair Brain result is invalid");
            }
            required(result.summary(), "Brain summary");
            return result;
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Remote repair Brain result is not strict JSON", e);
        }
    }

    private static TaskManager.BrainVerdict verdict(BrainResult result)
    {
        TaskManager.BrainVerdict verdict = switch (result.verdict()) {
            case "APPROVED" -> TaskManager.BrainVerdict.APPROVED;
            case "CHANGES_REQUESTED" ->
                    TaskManager.BrainVerdict.CHANGES_REQUESTED;
            default -> throw new IllegalArgumentException(
                    "Unknown Remote repair Brain verdict: " + result.verdict());
        };
        if (verdict == TaskManager.BrainVerdict.APPROVED
                && !result.findings().isEmpty()
                || verdict == TaskManager.BrainVerdict.CHANGES_REQUESTED
                    && result.findings().isEmpty()) {
            throw new IllegalArgumentException(
                    "Remote repair Brain verdict and findings disagree");
        }
        return verdict;
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
                    || BRANCH_BRAIN_CALLBACK.equals(owner.callbackRoute()));
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
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
        String base = "You own one exact Remote Development repair. Edit only "
                + "the supplied Task worktree. Do not push or perform other "
                + "remote effects. Return strict JSON: "
                + "{schemaVersion:1,summary:string}.";
        return roleSkill == null || roleSkill.isBlank()
                ? base : base + "\n\nRole skill:\n" + roleSkill;
    }

    private static String brainSystemPrompt(String roleSkill)
    {
        String base = "You are the read-only Task Brain reviewing one exact "
                + "Remote repair. Do not edit files or perform remote effects. "
                + "Return strict JSON: {schemaVersion:1,verdict:APPROVED|"
                + "CHANGES_REQUESTED,summary:string,findings:string[]}.";
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

    public record StageResult(int schemaVersion, String summary) {}

    public record BrainResult(
            int schemaVersion,
            String verdict,
            String summary,
            List<String> findings)
    {
        public BrainResult
        {
            findings = List.copyOf(requireNonNull(
                    findings, "findings is null"));
        }
    }
}
