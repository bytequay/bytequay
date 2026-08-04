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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator.SteeringAdmission;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanEditReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.TurnRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Attachment;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.PlanSource;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Predecessor;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Persist-first Stage steering and restart-safe owner admission. */
@Component
public final class V2StageSteeringRuntime
        implements V2StageSteeringControl, ExecutionPorts.MaintenanceWork
{
    private static final Logger log =
            LoggerFactory.getLogger(V2StageSteeringRuntime.class);
    /** Sent with an automatic replacement so the agent knows its edits survive. */
    private static final String MISSING_RESULT_STEER =
            missingResultSteer("record_development_result");
    private static final String MISSING_FEEDBACK_RESULT_STEER =
            missingResultSteer("record_feedback_repair");
    /** Written by the pre-delivery gate; matched to tell a Brain review that
     *  never reported from one that genuinely failed. */
    private static final String MISSING_VERDICT_ERROR =
            "succeeded without record_development_verdict";

    private static String missingResultSteer(String tool)
    {
        return "Your previous attempt finished its work but ended without "
                + "calling " + tool + ", so the Turn could not be accepted. The "
                + "edits it made are already committed in this worktree — "
                + "inspect them with git rather than redoing the work, then "
                + "call " + tool + " to report them.";
    }

    /** Reads and rewrites a structured Plan revision when the user steers it. */
    private static final ObjectMapper STEERING_JSON = new ObjectMapper();
    private static final String ACTOR = "user";
    private static final int SWEEP_LIMIT = 32;

    private final TaskCommandExecutor commands;
    private final StageManager.Store stages;
    private final SqliteStageSteeringStore store;
    private final LocalDevelopmentRuntimeCoordinator local;
    private final PlanRuntimeCoordinator plan;
    private final ChatAttachmentStore attachmentStore;
    private final DispatchTicketControl tickets;
    private final ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs;
    private final ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback;
    private final Clock clock;

    @Autowired
    public V2StageSteeringRuntime(
            TaskCommandExecutor commands,
            StageManager.Store stages,
            SqliteStageSteeringStore store,
            LocalDevelopmentRuntimeCoordinator local,
            PlanRuntimeCoordinator plan,
            ChatAttachmentStore attachmentStore,
            DispatchTicketControl tickets,
            ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs,
            ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback)
    {
        this(commands, stages, store, local, plan, attachmentStore,
                tickets, remoteRepairs, remoteFeedback, Clock.systemUTC());
    }

    V2StageSteeringRuntime(
            TaskCommandExecutor commands,
            StageManager.Store stages,
            SqliteStageSteeringStore store,
            LocalDevelopmentRuntimeCoordinator local,
            PlanRuntimeCoordinator plan,
            ChatAttachmentStore attachmentStore,
            DispatchTicketControl tickets,
            ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs,
            ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.stages = requireNonNull(stages, "stages is null");
        this.store = requireNonNull(store, "store is null");
        this.local = requireNonNull(local, "local is null");
        this.plan = requireNonNull(plan, "plan is null");
        this.attachmentStore = requireNonNull(
                attachmentStore, "attachmentStore is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.remoteRepairs = requireNonNull(
                remoteRepairs, "remoteRepairs is null");
        this.remoteFeedback = requireNonNull(
                remoteFeedback, "remoteFeedback is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public String steer(
            String taskId, String stageId, String text,
            List<String> images, Mode mode,
            String expectedPredecessorStageTurnId)
    {
        requireNonNull(mode, "mode is null");
        String expected = optionalText(
                expectedPredecessorStageTurnId,
                "expectedPredecessorStageTurnId");
        if (expected != null && mode != Mode.CANCEL_AND_REPLACE) {
            throw rejected(
                    "An expected predecessor is valid only for CANCEL_AND_REPLACE");
        }
        String requestId = expected == null
                ? UUID.randomUUID().toString()
                : stableId("stage-replacement-request", stageId, expected);
        String commandId = expected == null
                ? UUID.randomUUID().toString()
                : stableId("stage-replacement-command", stageId, expected);
        return steerWithCommandId(
                requestId, commandId, taskId, stageId,
                text == null ? "" : text.strip(), images, mode, expected);
    }

    String steerWithCommandId(
            String requestId, String commandId, String taskId, String stageId,
            String body, List<String> images, Mode mode)
    {
        return steerWithCommandId(
                requestId, commandId, taskId, stageId, body, images, mode, null);
    }

    String steerWithCommandId(
            String requestId, String commandId, String taskId, String stageId,
            String body, List<String> images, Mode mode,
            String expectedPredecessorStageTurnId)
    {
        requireNonNull(mode, "mode is null");
        String expected = optionalText(
                expectedPredecessorStageTurnId,
                "expectedPredecessorStageTurnId");
        if (expected != null && mode != Mode.CANCEL_AND_REPLACE) {
            throw rejected(
                    "An expected predecessor is valid only for CANCEL_AND_REPLACE");
        }
        List<String> paths = attachmentStore.save(stageId, images);
        List<Attachment> attachments = attachments(paths);
        String contentDigest = contentDigest(body, attachments);
        Request persisted = commands.execute(taskId, () -> {
            Request duplicate = store.findByCommand(commandId).orElse(null);
            if (duplicate != null) {
                if (!duplicate.id().equals(requestId)
                        || !duplicate.taskId().equals(taskId)
                        || !duplicate.stageId().equals(stageId)
                        || duplicate.mode() != mode
                        || !duplicate.contentDigest().equals(contentDigest)
                        || !matchesExpectedPredecessor(
                                duplicate.predecessor(), expected)) {
                    throw rejected("Steering command id already names another input");
                }
                return duplicate;
            }
            StageManager.OwnerState owner = stages.findOwner(taskId, stageId)
                    .orElseThrow(() -> new CommandRejectedException(
                            NOT_FOUND, "No V2 Stage " + stageId + " for Task " + taskId));
            validateNew(owner, stageId, mode);
            StageManager.State stage = owner.stage();
            Predecessor predecessor = stage.kind() == StageKind.REMOTE_DEVELOPMENT
                    ? store.findActiveRemotePredecessor(
                            taskId, stageId, owner.taskEpoch(), stage.generation())
                            .orElse(null)
                    : predecessor(stage.pendingResult());
            if (stage.kind() == StageKind.REMOTE_DEVELOPMENT) {
                validateRemoteTarget(predecessor, owner);
            }
            if (!matchesExpectedPredecessor(predecessor, expected)) {
                String current = predecessor == null
                        ? "none" : predecessor.ownerId();
                String message = "CANCEL_AND_REPLACE expected predecessor "
                        .concat("StageTurn %s but the current owner is %s");
                throw rejected(message.formatted(expected, current));
            }
            if (mode == Mode.CANCEL_AND_REPLACE && predecessor == null) {
                throw rejected("CANCEL_AND_REPLACE has no exact active Stage operation; "
                        + describe(owner));
            }
            Request request = new Request(
                    requestId, commandId, taskId, owner.taskEpoch(), stageId,
                    stage.kind(), stage.generation(), stage.version(),
                    stage.checkpoint(), mode, body, contentDigest, predecessor,
                    "PENDING", null, null, null, ACTOR, clock.instant());
            if (stage.checkpoint() == StageCheckpoint.LOCAL_REVIEW
                    && store.hasLiveLocalPublishBaseSync(request)) {
                throw rejected(
                        "Local review steering waits for active publish base sync");
            }
            store.insert(request, attachments);
            return request;
        });
        signalCancellation(persisted);
        attempt(persisted.id());
        return persisted.id();
    }

    /**
     * Converts one answered StageTurn wait into the same durable owner command
     * used by ordinary Stage steering.  The predecessor is terminal, so no
     * scheduler wake or cancellation race participates in admission.
     */
    public ContinuationResult continueUserWait(
            String predecessorTurnId,
            String predecessorOperationId,
            String taskId,
            String stageId,
            String waitKind,
            String waitId,
            String answer)
    {
        requireText(predecessorTurnId, "predecessorTurnId");
        requireText(predecessorOperationId, "predecessorOperationId");
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        requireText(answer, "answer");
        String existingSuccessor = store.userWaitSuccessor(waitKind, waitId)
                .orElse(null);
        if (existingSuccessor != null) {
            return ContinuationResult.admitted(existingSuccessor);
        }
        String requestId = stableId("stage-wait-request", waitKind, waitId);
        String commandId = stableId("stage-wait-command", waitKind, waitId);
        String body = continuationBody(waitKind, answer);
        String digest = contentDigest(body, List.of());
        Request request = commands.execute(taskId, () -> {
            Request duplicate = store.findByCommand(commandId).orElse(null);
            if (duplicate != null) {
                if (!duplicate.id().equals(requestId)
                        || !duplicate.taskId().equals(taskId)
                        || !duplicate.stageId().equals(stageId)
                        || !duplicate.contentDigest().equals(digest)) {
                    throw rejected(
                            "Stage wait command id already names another input");
                }
                return duplicate;
            }
            Predecessor predecessor = store.findUserWaitPredecessor(
                            predecessorTurnId, predecessorOperationId,
                            waitKind, waitId)
                    .orElse(null);
            if (predecessor == null) {
                return null;
            }
            StageManager.OwnerState owner = stages.findOwner(taskId, stageId)
                    .orElse(null);
            if (owner == null) {
                return null;
            }
            validateNew(owner, stageId, Mode.CANCEL_AND_REPLACE);
            StageManager.State stage = owner.stage();
            if (stage.kind() == StageKind.REMOTE_DEVELOPMENT) {
                validateRemoteTarget(predecessor, owner);
            }
            Request admitted = new Request(
                    requestId, commandId, taskId, owner.taskEpoch(), stageId,
                    stage.kind(), stage.generation(), stage.version(),
                    stage.checkpoint(), Mode.CANCEL_AND_REPLACE, body, digest,
                    predecessor, "PENDING", null, null, null, ACTOR,
                    clock.instant());
            store.insert(admitted, List.of());
            store.insertUserWaitLink(requestId, waitKind, waitId, predecessor);
            return admitted;
        });
        if (request == null) {
            return ContinuationResult.superseded(
                    "Stage owner or wait fence is no longer current");
        }
        attempt(request.id());
        Request persisted = store.find(request.id()).orElseThrow();
        if (persisted.status().equals("ADMITTED")) {
            return ContinuationResult.admitted(persisted.successorOwnerId());
        }
        if (persisted.status().equals("SUPERSEDED")) {
            return ContinuationResult.superseded(
                    "Stage continuation was superseded");
        }
        return ContinuationResult.pending();
    }

    @Override
    public void maintain(Instant now)
    {
        relaunchTurnsThatReportedNoResult();
        relaunchRemoteFeedbackTurnsThatReportedNoResult();
        repairBrainReviewsThatReportedNoVerdict();
        repairRemoteBrainReviewsThatReportedNoVerdict();
        for (Request request : store.findPending(SWEEP_LIMIT)) {
            signalCancellation(request);
            attempt(request.id());
        }
    }

    /**
     * Offer one replacement to a Local Development Turn that finished its work
     * but never reported it.
     *
     * <p>The Turn's edits are committed in the worktree; only the report is
     * missing, and without it the Stage cannot advance. This asks for exactly
     * what the user's own Retry asks for — a CANCEL_AND_REPLACE against the
     * parked predecessor — so the replacement runs through the same reviewed
     * path rather than a second one built for automation.
     *
     * <p>Idempotent by construction: {@link #steer} derives the request and
     * command ids from the stage and predecessor, so a second sweep before the
     * first request is admitted produces the same ids and no duplicate.
     */
    /**
     * Offer one repair to a Development Brain review whose verdict did not
     * parse. The Brain read the code and formed an opinion; it just wrote that
     * opinion as prose. Reuses the same entry point the user's Retry Brain
     * review button calls, which is idempotent on its command id.
     */
    private void repairBrainReviewsThatReportedNoVerdict()
    {
        for (SqliteStageSteeringStore.ParkedBrainReview parked
                : store.findParkedBrainReviews(SWEEP_LIMIT)) {
            try {
                local.retryFailedBrainReview(
                        parked.taskId(), parked.failedTurnId(), parked.blockerId(),
                        stableId("brain-review-repair", parked.taskId(),
                                parked.failedTurnId()),
                        "automation/brain-review-repair",
                        "the Brain review did not report a parseable verdict");
                log.info("Repairing Brain review {} on task {}: {}",
                        parked.failedTurnId(), parked.taskId(), parked.message());
            }
            catch (RuntimeException e) {
                log.warn("Could not repair Brain review {}: {}",
                        parked.failedTurnId(), e.toString());
            }
        }
    }

    private void relaunchTurnsThatReportedNoResult()
    {
        for (SqliteStageSteeringStore.ParkedResult parked
                : store.findParkedMissingResults(SWEEP_LIMIT)) {
            try {
                steer(parked.taskId(), parked.stageId(), MISSING_RESULT_STEER,
                        List.of(), Mode.CANCEL_AND_REPLACE, parked.turnId());
                log.info("Relaunching Local Development Turn {} on stage {}: "
                                + "it finished without reporting a result ({})",
                        parked.turnId(), parked.stageId(), parked.error());
            }
            catch (RuntimeException e) {
                // One unhealthy row must not stop the rest of the sweep; the
                // Turn stays parked and the user can still replace it by hand.
                log.warn("Could not relaunch Local Development Turn {}: {}",
                        parked.turnId(), e.toString());
            }
        }
    }

    /** The same offer to a Remote feedback repair that committed its changes
     *  and then ended without recording them. */
    private void relaunchRemoteFeedbackTurnsThatReportedNoResult()
    {
        for (SqliteStageSteeringStore.ParkedResult parked
                : store.findParkedRemoteFeedbackResults(SWEEP_LIMIT)) {
            try {
                steer(parked.taskId(), parked.stageId(),
                        MISSING_FEEDBACK_RESULT_STEER, List.of(),
                        Mode.CANCEL_AND_REPLACE, parked.turnId());
                log.info("Relaunching Remote feedback Turn {} on stage {}: "
                                + "it finished without reporting a result ({})",
                        parked.turnId(), parked.stageId(), parked.error());
            }
            catch (RuntimeException e) {
                log.warn("Could not relaunch Remote feedback Turn {}: {}",
                        parked.turnId(), e.toString());
            }
        }
    }

    /**
     * Offer one repair to a Remote repair Brain review that ended without
     * recording a verdict. Reuses the entry point the user's own recovery
     * control calls, which is idempotent on its command id — so this is one
     * automatic attempt per failed Turn, not a relaunch loop.
     */
    private void repairRemoteBrainReviewsThatReportedNoVerdict()
    {
        RemoteRepairTurnRuntime repairs = remoteRepairs.getIfAvailable();
        if (repairs == null) {
            return;
        }
        for (SqliteStageSteeringStore.ParkedBrainReview parked
                : store.findParkedRemoteBrainReviews(
                        MISSING_VERDICT_ERROR, SWEEP_LIMIT)) {
            try {
                repairs.retryFailedBrain(
                        parked.taskId(), parked.failedTurnId(), parked.blockerId(),
                        stableId("remote-brain-review-repair", parked.taskId(),
                                parked.failedTurnId()),
                        "automation/remote-brain-review-repair",
                        "the Brain review did not report a verdict");
                log.info("Repairing Remote Brain review {} on task {}: {}",
                        parked.failedTurnId(), parked.taskId(), parked.message());
            }
            catch (RuntimeException e) {
                log.warn("Could not repair Remote Brain review {}: {}",
                        parked.failedTurnId(), e.toString());
            }
        }
    }

    private void attempt(String requestId)
    {
        Request request = store.find(requestId).orElse(null);
        if (request == null || !request.status().equals("PENDING")) {
            return;
        }
        commands.executeVoid(request.taskId(), () -> attemptInCommand(requestId));
    }

    private void attemptInCommand(String requestId)
    {
        Request request = store.find(requestId).orElse(null);
        if (request == null || !request.status().equals("PENDING")) {
            return;
        }
        StageManager.OwnerState owner = stages.findOwner(
                        request.taskId(), request.stageId())
                .orElse(null);
        String stale = staleReason(request, owner);
        if (stale != null) {
            store.markSuperseded(request.id(), stale);
            return;
        }
        boolean userWait = store.isUserWaitContinuation(request.id());
        if (owner.taskLifecycle() != TaskLifecycle.ACTIVE) {
            return;
        }
        if (!userWait && isMalformedLocalResultPending(request, owner.stage())) {
            SteeringAdmission admission = local
                    .replaceMalformedResultPendingInCommand(
                            request, owner.stage().version());
            store.markAdmitted(
                    request.id(), "STAGE_TURN", admission.turnId(),
                    admission.operationId(), clock.instant());
            return;
        }
        if (!store.predecessorQuiesced(request)) {
            return;
        }
        if (userWait && !store.userWaitSubjectIsCurrent(request)) {
            store.markSuperseded(
                    request.id(), "Stage user-wait code subject changed");
            return;
        }
        if (request.stageKind() != StageKind.REMOTE_DEVELOPMENT) {
            if (userWait && !pendingMatchesPredecessor(
                    owner.stage().pendingResult(), request.predecessor())) {
                store.markSuperseded(
                        request.id(), "Stage pending result changed before continuation");
                return;
            }
            if (!userWait && owner.stage().pendingResult() != null) {
                return;
            }
        }
        switch (request.stageKind()) {
            case LOCAL_DEVELOPMENT -> admitLocal(request, owner.stage());
            case PLAN -> admitPlan(request, owner.stage());
            case REMOTE_DEVELOPMENT -> admitRemote(request);
            case CLEANUP -> throw new IllegalStateException(
                    "Cleanup cannot own persisted steering");
        }
    }

    private void admitLocal(Request request, StageManager.State stage)
    {
        if (stage.checkpoint() != StageCheckpoint.IMPLEMENTING
                && stage.checkpoint() != StageCheckpoint.ADDRESSING_BRAIN_FINDINGS
                && stage.checkpoint() != StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK
                && stage.checkpoint() != StageCheckpoint.LOCAL_REVIEW) {
            return;
        }
        if (stage.checkpoint() == StageCheckpoint.LOCAL_REVIEW
                && store.hasLiveLocalPublishBaseSync(request)) {
            return;
        }
        SteeringAdmission admission = store.isUserWaitContinuation(request.id())
                ? local.admitUserWaitContinuationInCommand(
                        request, stage.version())
                : local.admitSteeringInCommand(request, stage.version());
        store.markAdmitted(
                request.id(), "STAGE_TURN", admission.turnId(),
                admission.operationId(), clock.instant());
    }

    private void admitPlan(Request request, StageManager.State stage)
    {
        if (stage.checkpoint() != StageCheckpoint.AWAITING_APPROVAL) {
            return;
        }
        PlanSource source = store.requirePlanSource(request, stage.version());
        String content = planContent(source.content(), request,
                store.attachments(request.id()));
        PlanEditReceipt admission = plan.editPlanInCommand(
                new PlanRuntimeCoordinator.PlanEditCommand(
                        request.id(), ACTOR, request.taskId(), request.stageId(),
                        request.stageGeneration(), stage.version(),
                        source.revisionId(), source.selfReviewId(), content));
        store.markAdmitted(
                request.id(), "TASK_TURN", admission.reviewTurnId(),
                admission.reviewOperationId(), clock.instant());
    }

    private void validateNew(
            StageManager.OwnerState owner, String requestedStageId, Mode mode)
    {
        StageManager.State stage = owner.stage();
        if (owner.taskLifecycle() != TaskLifecycle.ACTIVE
                || !requestedStageId.equals(owner.currentStageId())
                || stage.endReason() != null) {
            throw rejected("Stage steering owner is inactive; " + describe(owner));
        }
        if (stage.kind() == StageKind.CLEANUP) {
            throw rejected("Cleanup Stage cannot be steered; " + describe(owner));
        }
        if (stage.kind() == StageKind.PLAN && mode == Mode.CANCEL_AND_REPLACE) {
            throw rejected("Plan owner supports APPEND only; " + describe(owner));
        }
    }

    private static void validateRemoteTarget(
            Predecessor predecessor, StageManager.OwnerState owner)
    {
        if (predecessor == null
                || !"STAGE_TURN".equals(predecessor.ownerKind())
                || !(predecessor.purpose().equals("REMOTE_CI_REPAIR")
                    || predecessor.purpose().equals("BRANCH_CONFLICT_REPAIR")
                    || predecessor.purpose().equals("ADDRESS_REMOTE_FEEDBACK"))) {
            throw rejected("Remote steering requires an exact active CI, branch, "
                    + "or feedback StageTurn owner; " + describe(owner));
        }
    }

    private void admitRemote(Request request)
    {
        Predecessor owner = requireNonNull(
                request.predecessor(), "Remote steering owner is missing");
        if (owner.purpose().equals("ADDRESS_REMOTE_FEEDBACK")) {
            TurnRequest admitted = remoteFeedback.getObject()
                    .admitSteeringInCommand(request);
            store.markAdmitted(
                    request.id(), "STAGE_TURN", admitted.turnId(),
                    admitted.operationId(), clock.instant());
            return;
        }
        RemoteRepairTurnRuntime repairs = remoteRepairs.getObject();
        if (owner.purpose().equals("REMOTE_CI_REPAIR")
                && !repairs.prepareCiSteeringInCommand(request)) {
            return;
        }
        SqliteRemoteRepairTurnStore.TurnRequest admitted =
                repairs.admitSteeringInCommand(request);
        store.markAdmitted(
                request.id(), "STAGE_TURN", admitted.turnId(),
                admitted.operationId(), clock.instant());
    }

    private Predecessor predecessor(ResultFence pending)
    {
        if (pending == null) {
            return null;
        }
        return store.findPredecessor(pending)
                .orElseThrow(() -> rejected(
                        "Stage pending result has no exact live DispatchTicket: "
                                + pending.operationId()));
    }

    private void signalCancellation(Request request)
    {
        if (!request.status().equals("PENDING")
                || request.mode() != Mode.CANCEL_AND_REPLACE
                || request.predecessor() == null
                || store.isUserWaitContinuation(request.id())) {
            return;
        }
        tickets.requestCancel(request.predecessor().ticketId());
    }

    private static String staleReason(
            Request request, StageManager.OwnerState owner)
    {
        if (owner == null) {
            return "Stage owner disappeared";
        }
        StageManager.State stage = owner.stage();
        if (owner.taskEpoch() != request.taskEpoch()) {
            return "Task epoch changed from " + request.taskEpoch()
                    + " to " + owner.taskEpoch();
        }
        if (!request.stageId().equals(owner.currentStageId())
                || stage.generation() != request.stageGeneration()
                || stage.kind() != request.stageKind()) {
            return "Stage owner changed: " + describe(owner);
        }
        if (stage.endReason() != null
                || owner.taskLifecycle() == TaskLifecycle.CANCELING
                || owner.taskLifecycle() == TaskLifecycle.CLEANING
                || owner.taskLifecycle().isTerminal()) {
            return "Stage owner stopped: " + describe(owner);
        }
        return null;
    }

    private List<Attachment> attachments(List<String> paths)
    {
        List<Attachment> result = new ArrayList<>();
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index);
            ChatAttachmentStore.Attachment attachment = attachmentStore.read(path);
            result.add(new Attachment(
                    index + 1, attachment.mimeType(), path,
                    sha256(attachment.bytes())));
        }
        return List.copyOf(result);
    }

    private static String contentDigest(String body, List<Attachment> attachments)
    {
        StringBuilder canonical = new StringBuilder(body);
        attachments.forEach(attachment -> canonical
                .append('\n').append(attachment.position())
                .append(':').append(attachment.mediaType())
                .append(':').append(attachment.contentDigest()));
        return sha256(canonical.toString().getBytes(UTF_8));
    }

    /**
     * The next revision, carrying what the user steered.
     *
     * <p>A structured revision takes the steering as a field. Appending to it as
     * text would look like it worked and silently lose the user's words: Jackson
     * reads the leading object and stops, so everything after the JSON — the
     * steering itself — is never seen again. Pre-protocol Markdown revisions keep
     * the append.
     */
    private static String planContent(
            String previous, Request request, List<Attachment> attachments)
    {
        StringBuilder steering = new StringBuilder(request.body());
        if (!attachments.isEmpty()) {
            steering.append("\n\nDurable attachments:\n");
            attachments.forEach(attachment -> steering
                    .append("- ").append(attachment.contentRef()).append('\n'));
        }
        try {
            JsonNode plan = STEERING_JSON.readTree(previous);
            if (plan != null && plan.isObject()) {
                ObjectNode next = ((ObjectNode) plan).deepCopy();
                String existing = next.path("userSteering").asText("");
                next.put("userSteering", existing.isBlank()
                        ? steering.toString()
                        : existing + "\n\n" + steering);
                return STEERING_JSON.writeValueAsString(next);
            }
        }
        catch (JsonProcessingException ignored) {
            // Revisions recorded before the structured protocol were Markdown.
        }
        return previous + "\n\n## User steering\n\n" + steering;
    }

    private static String sha256(byte[] value)
    {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String describe(StageManager.OwnerState owner)
    {
        StageManager.State stage = owner.stage();
        return "task=" + owner.taskLifecycle() + ", currentStage="
                + owner.currentStageId() + ", owner=" + stage.kind() + "/"
                + stage.checkpoint() + "/g" + stage.generation()
                + "/v" + stage.version();
    }

    private static CommandRejectedException rejected(String message)
    {
        return new CommandRejectedException(INVALID_STATE, message);
    }

    private static boolean pendingMatchesPredecessor(
            ResultFence pending, Predecessor predecessor)
    {
        return pending != null && predecessor != null
                && pending.operationId().equals(predecessor.operationId())
                && pending.attempt() == predecessor.attempt()
                && Objects.equals(
                        pending.expectedCodeFingerprint(),
                        predecessor.codeFingerprint())
                && Objects.equals(
                        pending.expectedHeadSha(), predecessor.headSha())
                && Objects.equals(
                        pending.expectedBaseSha(), predecessor.baseSha());
    }

    private boolean isMalformedLocalResultPending(
            Request request, StageManager.State stage)
    {
        if (request.stageKind() != StageKind.LOCAL_DEVELOPMENT
                || request.mode() != Mode.CANCEL_AND_REPLACE
                || request.acceptedStageVersion() != stage.version()
                || request.acceptedCheckpoint() != stage.checkpoint()
                || (stage.checkpoint() != StageCheckpoint.IMPLEMENTING
                    && stage.checkpoint()
                            != StageCheckpoint.ADDRESSING_BRAIN_FINDINGS
                    && stage.checkpoint()
                            != StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK)
                || !pendingMatchesPredecessor(
                        stage.pendingResult(), request.predecessor())) {
            return false;
        }
        return store.malformedLocalResultPendingReady(
                request, stage.pendingResult());
    }

    private static String continuationBody(String waitKind, String answer)
    {
        return "User resolved the "
                + waitKind.toLowerCase(Locale.ROOT).replace('_', ' ')
                + ": " + answer + "\nContinue the same Stage work.";
    }

    private static String stableId(String kind, String left, String right)
    {
        return UUID.nameUUIDFromBytes(
                (kind + ":" + left + ":" + right).getBytes(UTF_8)).toString();
    }

    private static boolean matchesExpectedPredecessor(
            Predecessor predecessor, String expectedStageTurnId)
    {
        return expectedStageTurnId == null
                || (predecessor != null
                && predecessor.ownerKind().equals("STAGE_TURN")
                && predecessor.ownerId().equals(expectedStageTurnId));
    }

    private static String optionalText(String value, String name)
    {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw rejected(name + " is blank");
        }
        return value.strip();
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record ContinuationResult(
            String status, String successorTurnId, String detail)
    {
        public static ContinuationResult admitted(String turnId)
        {
            return new ContinuationResult("ADMITTED", turnId, null);
        }

        public static ContinuationResult pending()
        {
            return new ContinuationResult("PENDING", null, null);
        }

        public static ContinuationResult superseded(String detail)
        {
            return new ContinuationResult("SUPERSEDED", null, detail);
        }
    }
}
