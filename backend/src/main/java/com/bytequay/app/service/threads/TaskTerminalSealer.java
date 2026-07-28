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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.RoundGateStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskPushStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageStateMachine;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/**
 * The one place every "a task just went terminal" path (remote merge/close
 * observed, in-app cancel) seals the task's open work, so no caller can
 * forget a piece: any still-open review round, live run, or still-open stage
 * stops rendering as live once the task itself is done. Purely a status seal —
 * interrupting live agent subprocesses is the caller's job, since
 * {@link TaskLifecycleDriver} and {@link TaskService} do that with different
 * urgency (fire-and-forget vs. blocking on the subprocess actually exiting).
 */
@Component
class TaskTerminalSealer
{
    private final StageStore stageStore;
    private final StageStateMachine stageMachine;
    private final ReviewRoundService reviewRounds;
    private final AgentRunService agentRuns;
    private final LocalReviewSubmissionStore submissions;
    private final TaskPushStore pushes;
    private final RoundGateStore roundGates;
    private final TaskCommandExecutor commands;
    private final TaskStore tasks;

    TaskTerminalSealer(
            StageStore stageStore, StageStateMachine stageMachine,
            ReviewRoundService reviewRounds, AgentRunService agentRuns,
            LocalReviewSubmissionStore submissions, TaskPushStore pushes,
            RoundGateStore roundGates,
            TaskCommandExecutor commands,
            TaskStore tasks)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.stageMachine = requireNonNull(stageMachine, "stageMachine is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.submissions = requireNonNull(submissions, "submissions is null");
        this.pushes = requireNonNull(pushes, "pushes is null");
        this.roundGates = requireNonNull(roundGates, "roundGates is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    void seal(String taskId, String reason)
    {
        if (tasks.isV2Task(taskId)) {
            return;
        }
        commands.executeVoid(taskId, () -> sealInCommand(taskId, reason));
    }

    void sealInCommand(String taskId, String reason)
    {
        if (tasks.isV2Task(taskId)) {
            return;
        }
        TaskCommandExecutor.requireCurrent(taskId);
        reviewRounds.closeOpenRoundsInCommand(taskId, reason);
        for (AgentRun run : agentRuns.liveRunsByTask(taskId)) {
            agentRuns.transitionInCommand(
                    taskId, run.id(), AgentRun.STATUS_CANCELLED, reason);
        }
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            if (stage.state() != StageState.CLOSED) {
                stageMachine.closeInCommand(taskId, stage.id(), reason);
            }
        }
        // Incomplete review batches are sealed with the task so no sweep
        // can revive superseded feedback.
        submissions.cancelOpenForTask(taskId, reason, Instant.now());
        pushes.sealActive(taskId, reason, Instant.now());
        roundGates.sealActive(taskId, reason, Instant.now());
    }

    /** Runs synchronously in {@link TaskPhaseMachine}'s terminal command. */
    @EventListener
    void onTerminalSealing(TaskTerminalSealingEvent event)
    {
        if (tasks.isV2Task(event.taskId())) {
            return;
        }
        sealInCommand(event.taskId(), event.reason());
    }
}
