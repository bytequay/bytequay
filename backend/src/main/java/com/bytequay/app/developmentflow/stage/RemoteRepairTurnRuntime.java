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
import com.bytequay.app.developmentflow.stage.persistence.SqliteAgentResultSubmissionStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteAgentResultSubmissionStore.RepairSubmission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRetryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.BrainRetryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.CodeSubject;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.RepairContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.TurnDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
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

/** Finite typed Turn continuation for branch-conflict repair. */
public final class RemoteRepairTurnRuntime
        implements BranchSyncRuntimeCoordinator.ConflictRepairPort,
        BranchSyncRuntimeCoordinator.BrainReviewPort
{
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
    private final SqliteAgentResultSubmissionStore submissions;
    private final ObjectMapper json;
    /** Names this Turn in every brain-protocol failure message. */
    private static final String BRAIN_LABEL = "Remote repair Brain";
    private final ObjectReader workModelReader;
    private final Clock clock;
    private final int serverPort;
    private SqliteStageSteeringStore steering;
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
    void setReasoningEfforts(ReasoningEffortService reasoningEfforts)
    {
        this.reasoningEfforts = requireNonNull(
                reasoningEfforts, "reasoningEfforts is null");
    }

    /**
     * Persist the summary a branch-conflict repair reports through
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
        if (!purpose.equals("BRANCH_CONFLICT_REPAIR")) {
            throw new IllegalArgumentException(
                    "Remote repair cannot consume " + purpose);
        }
        var handoff = ownerStore.requireRemoteHandoff(request.id());
        if (!handoff.status().equals("PARKED")
                || !handoff.ownerFamily().equals("BRANCH_REPAIR")) {
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
            blockCurrentStageFailure(context, error, now);
            return receipt(ACCEPTED, "Remote repair StageTurn failed");
        }
        RepairSubmission result = requireRepairSubmission(context);
        ValidatedCodeSubject validated = requireOutputCodeSubject(raw, context);
        CodeSubject output = requireChangedOutput(validated);
        turns.finishStageTurn(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", output, result.summary(), null, now);
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
        boolean acceptOutput = request.mode() == V2StageSteeringRuntime.Mode.APPEND
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
            blockCurrentStageFailure(context, error, now);
            return receipt(ACCEPTED, "Remote steering Turn failed");
        }
        RepairSubmission result = requireRepairSubmission(context);
        CodeSubject output = requireChangedOutput(
                requireOutputCodeSubject(raw, context));
        turns.finishSteeringTurn(
                context, raw.outcome().name(), rawDigest, ACCEPTED.name(),
                "SUCCEEDED", output, result.summary(), null, now);
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

    private void stop(TurnDelivery context, String reason, Instant at)
    {
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        remoteStore.failBranchEpisode(episode, reason, at);
    }

    /**
     * A current Stage execution failure is a domain result, not an
     * infrastructure retry and not permission to reopen a fresh Episode on
     * the next identical poll. BranchSync records its exact-subject failure.
     */
    private void blockCurrentStageFailure(
            TurnDelivery context,
            String reason,
            Instant at)
    {
        BranchEpisode episode = remoteStore.findBranchEpisode(
                        context.episodeId())
                .orElseThrow();
        remoteStore.failBranchEpisode(episode, reason, at);
    }

    private EffectDeliveryReceipt duplicate(
            String operationId, String rawDigest)
    {
        EffectDeliveryReceipt receipt = turns.findSteeringReceipt(operationId)
                .orElseGet(() -> remoteStore.findBranchEffectReceipt(operationId)
                        .orElseGet(() -> turns
                                .findReplacementReceipt(operationId)
                                .orElse(null)));
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
        if (noChange && !context.headSha().equals(output.headSha())) {
            throw new IllegalStateException(
                    "Remote repair no-change output was not restored to its source head");
        }
        if (output.discardedNoChangeHeadSha() != null
                && (!noChange
                    || !context.headSha().equals(output.restoredHeadSha())
                    || !output.headSha().equals(output.restoredHeadSha()))) {
            throw new IllegalStateException(
                    "Remote repair no-change restore proof is stale");
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
                && (BRANCH_STAGE_CALLBACK.equals(owner.callbackRoute())
                    || STEERING_CALLBACK.equals(owner.callbackRoute()))
                || owner.kind() == DispatchTicket.OwnerKind.TASK_TURN
                && BRANCH_BRAIN_CALLBACK.equals(owner.callbackRoute());
    }

    private static String expectedRoute(TurnDelivery context)
    {
        if ("STEERING".equals(context.kind())) {
            return STEERING_CALLBACK;
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

}
