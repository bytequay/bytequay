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
package com.bytequay.app.service.stage;

import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.MessageAttachments;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static com.bytequay.app.domain.TurnInitiator.SOURCE_PARKED_STEERING;
import static java.util.Objects.requireNonNull;

/**
 * Enqueues a steering turn on the Task's dev thread. The agent's own
 * user-message echo is the conversation row; the steered message is
 * attributed to the stage by time window at read time (see the stage
 * detail metrics + brain feed), so nothing is written here beyond the turn
 * and, for monitor stages, its iteration.
 */
@Service
public class StageSteeringServiceImpl
        implements StageSteeringService
{
    private static final Logger log = LoggerFactory.getLogger(StageSteeringServiceImpl.class);

    private final StageStore stageStore;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final AgentRunService agentRuns;
    private final TaskCommandExecutor commands;
    private final IterationService iterationService;
    private final ChatAttachmentStore attachmentStore;
    private final ObjectMapper mapper;
    private V2ControlRouteStore v2Routes;
    private V2StageSteeringControl v2Steering;

    public StageSteeringServiceImpl(
            StageStore stageStore,
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            AgentRunService agentRuns,
            TaskCommandExecutor commands,
            IterationService iterationService,
            ChatAttachmentStore attachmentStore,
            ObjectMapper mapper)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.iterationService = requireNonNull(iterationService, "iterationService is null");
        this.attachmentStore = requireNonNull(attachmentStore, "attachmentStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Autowired
    void setV2Routes(V2ControlRouteStore v2Routes)
    {
        this.v2Routes = requireNonNull(v2Routes, "v2Routes is null");
    }

    @Autowired(required = false)
    void setV2Steering(V2StageSteeringControl v2Steering)
    {
        this.v2Steering = requireNonNull(v2Steering, "v2Steering is null");
    }

    @Override
    public SteerResult steer(
            UUID stageId, String text, List<String> images, Mode mode)
    {
        requireNonNull(mode, "mode is null");
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty() && (images == null || images.isEmpty())) {
            throw status(400, "steering message is empty");
        }
        String v2TaskId = v2Routes == null
                ? null : v2Routes.taskForStage(stageId.toString()).orElse(null);
        if (v2TaskId != null) {
            if (v2Steering == null) {
                throw status(503, "V2 Stage steering is not configured");
            }
            String turnId = v2Steering.steer(
                    v2TaskId, stageId.toString(), trimmed, images,
                    V2StageSteeringControl.Mode.valueOf(mode.name()));
            return new SteerResult(turnId);
        }
        if (mode != Mode.APPEND) {
            throw status(422,
                    "CANCEL_AND_REPLACE is available only for V2 stages");
        }
        String taskId = stageStore.findStageById(stageId)
                .map(StageInstance::taskId)
                .orElseThrow(() -> status(404, "no stage: " + stageId));
        SteerResult result = commands.execute(
                taskId, () -> steerInCommand(stageId, taskId, trimmed, images));
        log.debug("Steered stage {} (task {}) via turn {}", stageId, taskId, result.turnId());
        return result;
    }

    private SteerResult steerInCommand(
            UUID stageId, String commandTaskId, String text, List<String> images)
    {
        StageInstance stage = stageStore.findStageById(stageId)
                .orElseThrow(() -> status(404, "no stage: " + stageId));
        if (!stage.taskId().equals(commandTaskId)) {
            throw status(409, "stage changed tasks while steering: " + stageId);
        }
        if (stage.state() != StageState.OPEN) {
            throw status(422, "can't steer a " + stage.state() + " stage");
        }
        Task task = taskStore.findTaskById(stage.taskId())
                .orElseThrow(() -> status(404, "no task: " + stage.taskId()));
        Thread devThread = threadStore.findThreadById(task.threadId())
                .orElseThrow(() -> status(404, "no dev thread: " + task.threadId()));

        // Fold any pasted screenshots into the turn text the same way
        // ThreadController.encodeWithImages does for trunk/task-brain sends,
        // keyed on the dev thread's id (the same id space the attachment
        // GET endpoint already serves from).
        List<String> paths = attachmentStore.save(devThread.id(), images);
        String input = MessageAttachments.encode(mapper, text, paths);

        // Bind the turn to the task explicitly. A task parked at
        // AWAITING_REVIEW (the review gate) is no longer in the thread's
        // active set, so the active-task-derived enqueueTurn would stamp task_id = null
        // and misroute the steer to the trunk planner instead of the dev
        // agent — leaving review comments unaddressed.
        boolean parked = task.phase() == TaskPhase.NEEDS_ATTENTION
                && task.status() == TaskStatus.NEEDS_ATTENTION;
        TurnInitiator initiator = TurnInitiator.attended(
                parked ? SOURCE_PARKED_STEERING : "steering");
        AgentRun run = agentRuns.openSchedulerSessionInCommand(
                devThread, task.id(), stage.id().toString(),
                sessionKind(devThread, stage.type()), input);
        String turnId = scheduler.enqueueStageTurn(
                devThread, input, task.id(), stage.id().toString(), initiator, run.id());
        // 1 turn = 1 iteration: open a user_steering iteration so the steer
        // shows up as its own band on the stage detail page. A no-op unless
        // the stage is a monitor stage (the only loop stages with iterations).
        if (!parked) {
            iterationService.begin(task.id(), turnId, IterationService.TRIGGER_USER_STEERING);
        }
        return new SteerResult(turnId);
    }

    /** Keep scheduler-session classification aligned with AgentScheduler. */
    private static String sessionKind(Thread thread, StageType type)
    {
        if (thread.flow() == ThreadFlow.REVIEW) {
            return AgentRun.KIND_REVIEW;
        }
        if (type == StageType.CI_FIXING_STAGE) {
            return AgentRun.KIND_CI_FIX;
        }
        if (type == StageType.PLAN_STAGE || thread.kind() == ThreadKind.BRAIN_AGENT) {
            return AgentRun.KIND_PLAN;
        }
        if (type == StageType.REVIEW_STAGE || type == StageType.REVIEW_ROUND_STAGE) {
            return AgentRun.KIND_REVIEW;
        }
        return AgentRun.KIND_DEV;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
