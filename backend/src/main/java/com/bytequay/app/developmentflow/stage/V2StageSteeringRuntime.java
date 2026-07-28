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
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
    private static final String ACTOR = "user";
    private static final int SWEEP_LIMIT = 32;

    private final TaskCommandExecutor commands;
    private final StageManager.Store stages;
    private final SqliteStageSteeringStore store;
    private final LocalDevelopmentRuntimeCoordinator local;
    private final PlanRuntimeCoordinator plan;
    private final ChatAttachmentStore attachmentStore;
    private final ObjectProvider<ExecutionDispatcher> dispatcher;
    private final ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs;
    private final ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback;
    private final Clock clock;

    public V2StageSteeringRuntime(
            TaskCommandExecutor commands,
            StageManager.Store stages,
            SqliteStageSteeringStore store,
            LocalDevelopmentRuntimeCoordinator local,
            PlanRuntimeCoordinator plan,
            ChatAttachmentStore attachmentStore,
            ObjectProvider<ExecutionDispatcher> dispatcher,
            ObjectProvider<RemoteRepairTurnRuntime> remoteRepairs,
            ObjectProvider<RemoteFeedbackRuntimeCoordinator> remoteFeedback)
    {
        this(commands, stages, store, local, plan, attachmentStore,
                dispatcher, remoteRepairs, remoteFeedback, Clock.systemUTC());
    }

    V2StageSteeringRuntime(
            TaskCommandExecutor commands,
            StageManager.Store stages,
            SqliteStageSteeringStore store,
            LocalDevelopmentRuntimeCoordinator local,
            PlanRuntimeCoordinator plan,
            ChatAttachmentStore attachmentStore,
            ObjectProvider<ExecutionDispatcher> dispatcher,
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
        this.dispatcher = requireNonNull(dispatcher, "dispatcher is null");
        this.remoteRepairs = requireNonNull(
                remoteRepairs, "remoteRepairs is null");
        this.remoteFeedback = requireNonNull(
                remoteFeedback, "remoteFeedback is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public String steer(
            String taskId, String stageId, String text,
            List<String> images, Mode mode)
    {
        return steerWithCommandId(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                taskId, stageId, text == null ? "" : text.strip(), images, mode);
    }

    String steerWithCommandId(
            String requestId, String commandId, String taskId, String stageId,
            String body, List<String> images, Mode mode)
    {
        requireNonNull(mode, "mode is null");
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
                        || !duplicate.contentDigest().equals(contentDigest)) {
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
            if (mode == Mode.CANCEL_AND_REPLACE && predecessor == null) {
                throw rejected("CANCEL_AND_REPLACE has no exact active Stage operation; "
                        + describe(owner));
            }
            Request request = new Request(
                    requestId, commandId, taskId, owner.taskEpoch(), stageId,
                    stage.kind(), stage.generation(), stage.version(),
                    stage.checkpoint(), mode, body, contentDigest, predecessor,
                    "PENDING", null, null, null, ACTOR, clock.instant());
            store.insert(request, attachments);
            return request;
        });
        signalCancellation(persisted);
        attempt(persisted.id());
        return persisted.id();
    }

    @Override
    public void maintain(Instant now)
    {
        for (Request request : store.findPending(SWEEP_LIMIT)) {
            signalCancellation(request);
            attempt(request.id());
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
        if (owner.taskLifecycle() != TaskLifecycle.ACTIVE
                || !store.predecessorQuiesced(request)
                || request.stageKind() != StageKind.REMOTE_DEVELOPMENT
                    && owner.stage().pendingResult() != null) {
            return;
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
        SteeringAdmission admission = local.admitSteeringInCommand(
                request, stage.version());
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
        SqliteRemoteRepairTurnStore.TurnRequest admitted = remoteRepairs.getObject()
                .admitSteeringInCommand(request);
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
        if (request.mode() != Mode.CANCEL_AND_REPLACE
                || request.predecessor() == null) {
            return;
        }
        ExecutionDispatcher current = dispatcher.getIfAvailable();
        if (current != null) {
            current.requestCancel(request.predecessor().ticketId());
        }
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

    private static String planContent(
            String previous, Request request, List<Attachment> attachments)
    {
        StringBuilder content = new StringBuilder(previous)
                .append("\n\n## User steering\n\n")
                .append(request.body());
        if (!attachments.isEmpty()) {
            content.append("\n\nDurable attachments:\n");
            attachments.forEach(attachment -> content
                    .append("- ").append(attachment.contentRef()).append('\n'));
        }
        return content.toString();
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
}
