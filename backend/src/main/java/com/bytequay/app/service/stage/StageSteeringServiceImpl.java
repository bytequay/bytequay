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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

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
    private final IterationService iterationService;

    public StageSteeringServiceImpl(
            StageStore stageStore,
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            IterationService iterationService)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.iterationService = requireNonNull(iterationService, "iterationService is null");
    }

    @Override
    public SteerResult steer(UUID stageId, String text)
    {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            throw status(400, "steering message is empty");
        }
        StageInstance stage = stageStore.findStageById(stageId)
                .orElseThrow(() -> status(404, "no stage: " + stageId));
        if (stage.state() != StageState.OPEN && stage.state() != StageState.ACTIVE) {
            throw status(422, "can't steer a " + stage.state() + " stage");
        }
        Task task = taskStore.findTaskById(stage.taskId())
                .orElseThrow(() -> status(404, "no task: " + stage.taskId()));
        Thread devThread = threadStore.findThreadById(task.threadId())
                .orElseThrow(() -> status(404, "no dev thread: " + task.threadId()));

        // Bind the turn to the task explicitly. A task parked at
        // AWAITING_REVIEW (the review gate) is no longer in the thread's
        // active set, so the active-task-derived enqueueTurn would stamp task_id = null
        // and misroute the steer to the trunk planner instead of the dev
        // agent — leaving review comments unaddressed.
        String turnId = scheduler.enqueueTaskTurn(
                devThread, trimmed, task.id(), TurnInitiator.attended("steering"));
        // 1 turn = 1 iteration: open a user_steering iteration so the steer
        // shows up as its own band on the stage detail page. A no-op unless
        // the stage is a monitor stage (the only loop stages with iterations).
        iterationService.begin(task.id(), turnId, IterationService.TRIGGER_USER_STEERING);
        log.debug("Steered stage {} (task {}) via turn {}", stageId, task.id(), turnId);
        return new SteerResult(turnId);
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
