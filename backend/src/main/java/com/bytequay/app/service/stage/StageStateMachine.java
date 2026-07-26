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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.statemachine.StateMachine;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The sole production writer of a stage's lifecycle columns. Stage state has
 * one deliberately small graph ({@code OPEN <-> CLOSED}); task status and
 * phase/run ownership supply the guards that make reopening safe.
 */
@Component
public class StageStateMachine
{
    private static final StateMachine<StageState> GRAPH =
            StateMachine.<StageState>builder("stage")
                    .edge(StageState.OPEN, StageState.CLOSED)
                    .edge(StageState.CLOSED, StageState.OPEN)
                    .build();

    private final StageStore stages;
    private final TaskStore tasks;
    private final AgentRunStore runs;
    private final StageBudgetService budgets;
    private final TaskCommandExecutor commands;
    private final ApplicationEventPublisher events;

    public StageStateMachine(
            StageStore stages,
            TaskStore tasks,
            AgentRunStore runs,
            StageBudgetService budgets,
            TaskCommandExecutor commands,
            ApplicationEventPublisher events)
    {
        this.stages = requireNonNull(stages, "stages is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.budgets = requireNonNull(budgets, "budgets is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.events = requireNonNull(events, "events is null");
    }

    /** Ensure the stage owned by the task's current phase is open. */
    public StageInstance ensurePhaseOpen(String taskId, StageType type, UUID callerStageId)
    {
        return commands.execute(taskId,
                () -> ensurePhaseOpenInCommand(taskId, type, callerStageId));
    }

    /** Same-transaction phase projection used by {@link StageLifecycle}. */
    public StageInstance ensurePhaseOpenInCommand(
            String taskId, StageType type, UUID callerStageId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireTask(taskId);
        requireRunnableOwner(task, type, Owner.PHASE);
        return ensureOpenInCommand(task, type, callerStageId);
    }

    /** Open a new phase chapter without reviving closed history. Replan
     * uses this so the approved PlanStage stays immutable and the new
     * revision gets a distinct stage identity. */
    public StageInstance openFreshPhaseInCommand(
            String taskId, StageType type, UUID callerStageId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireTask(taskId);
        requireRunnableOwner(task, type, Owner.PHASE);
        requireCallerBelongsToTask(taskId, callerStageId);
        if (stages.findActiveStage(taskId).isPresent()) {
            throw conflict("task " + taskId + " already has an open stage");
        }
        StageInstance opened = stages.openStage(taskId, type, callerStageId);
        budgets.onStageOpened(opened);
        return opened;
    }

    /** Ensure a pure container stage for the named run kind is open. */
    public StageInstance ensureRunOpen(
            String taskId, String runKind, StageType type, UUID callerStageId)
    {
        return commands.execute(taskId,
                () -> ensureRunOpenInCommand(taskId, runKind, type, callerStageId));
    }

    /** Same-transaction run admission primitive. */
    public StageInstance ensureRunOpenInCommand(
            String taskId, String runKind, StageType type, UUID callerStageId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Task task = requireTask(taskId);
        requireRunnableOwner(task, type, Owner.RUN);
        requireRunKindOwns(runKind, type);
        requireCallerBelongsToTask(taskId, callerStageId);
        return ensureOpenInCommand(task, type, callerStageId);
    }

    /** Reopen an existing stage only while its current phase or a live run
     *  still owns it. */
    public StageInstance reopenOwned(UUID stageId)
    {
        StageInstance stage = requireStage(stageId);
        return commands.execute(stage.taskId(),
                () -> reopenOwnedInCommand(stage.taskId(), stageId));
    }

    public StageInstance reopenOwnedInCommand(String taskId, UUID stageId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        StageInstance stage = requireStage(stageId);
        if (!stage.taskId().equals(taskId)) {
            throw conflict("stage " + stageId + " does not belong to task " + taskId);
        }
        Task task = requireTask(taskId);
        requireRunnable(task, stage.type());
        boolean phaseOwns = stage.type().allowedPhases().contains(task.phase());
        boolean runOwns = runs.findLiveByTask(taskId).stream()
                .anyMatch(run -> stage.id().toString().equals(run.stageId()));
        if (!phaseOwns && !runOwns) {
            throw conflict("stage " + stageId + " has no current phase or run owner");
        }
        return reopenInCommand(stage);
    }

    /** Close by stage id. Unknown and already-closed rows are idempotent
     *  no-ops. */
    public boolean close(UUID stageId, String reason)
    {
        return close(stageId, reason, Map.of());
    }

    public boolean close(UUID stageId, String reason, Map<String, Object> extraPayload)
    {
        requireNonNull(stageId, "stageId is null");
        Optional<StageInstance> stage = stages.findStageById(stageId);
        return stage.isPresent() && commands.execute(stage.orElseThrow().taskId(),
                () -> closeInCommand(
                        stage.orElseThrow().taskId(), stageId, reason, extraPayload));
    }

    public boolean closeInCommand(String taskId, UUID stageId, String reason)
    {
        return closeInCommand(taskId, stageId, reason, Map.of());
    }

    /** Same-transaction close used by phase, run, review, and terminal
     *  projections. Publishes the runtime eviction signal only for a real
     *  OPEN -> CLOSED compare-and-set. */
    public boolean closeInCommand(
            String taskId, UUID stageId, String reason, Map<String, Object> extraPayload)
    {
        requireNonNull(extraPayload, "extraPayload is null");
        TaskCommandExecutor.requireCurrent(taskId);
        StageInstance stage = stages.findStageById(stageId).orElse(null);
        if (stage == null || stage.state() == StageState.CLOSED) {
            return false;
        }
        if (!stage.taskId().equals(taskId)) {
            throw conflict("stage " + stageId + " does not belong to task " + taskId);
        }
        if (!GRAPH.checkTransition(stageId, stage.state(), StageState.CLOSED)) {
            return false;
        }
        Instant now = Instant.now();
        if (!stages.updateStateIf(stageId, StageState.OPEN, StageState.CLOSED, now)) {
            StageInstance current = requireStage(stageId);
            if (current.state() == StageState.CLOSED) {
                return false;
            }
            throw conflict("stage " + stageId + " changed while it was closing");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason == null ? "" : reason);
        payload.putAll(extraPayload);
        stages.recordEvent(stageId, taskId, StageEventType.CLOSED, payload);
        events.publishEvent(new StageClosedEvent(taskId, stageId.toString()));
        return true;
    }

    private StageInstance ensureOpenInCommand(Task task, StageType type, UUID callerStageId)
    {
        Optional<StageInstance> existing = stages.findStageByType(task.id(), type);
        if (existing.isEmpty()) {
            StageInstance opened = stages.openStage(task.id(), type, callerStageId);
            budgets.onStageOpened(opened);
            return opened;
        }
        StageInstance found = existing.orElseThrow();
        if (found.state() == StageState.OPEN) {
            return found;
        }
        return reopenInCommand(found);
    }

    private StageInstance reopenInCommand(StageInstance stage)
    {
        if (!GRAPH.checkTransition(stage.id(), stage.state(), StageState.OPEN)) {
            return stage;
        }
        if (!stages.updateStateIf(stage.id(), StageState.CLOSED, StageState.OPEN, null)) {
            StageInstance current = requireStage(stage.id());
            if (current.state() == StageState.OPEN) {
                return current;
            }
            throw conflict("stage " + stage.id() + " changed while it was reopening");
        }
        stages.recordEvent(stage.id(), stage.taskId(), StageEventType.REOPENED, Map.of());
        return requireStage(stage.id());
    }

    private void requireRunnableOwner(Task task, StageType type, Owner owner)
    {
        requireRunnable(task, type);
        if (owner == Owner.PHASE && !type.allowedPhases().contains(task.phase())) {
            throw conflict("phase " + task.phase() + " does not own " + type);
        }
        if (owner == Owner.RUN && !type.allowedPhases().isEmpty()) {
            throw conflict(type + " is phase-owned, not a run container");
        }
    }

    private static void requireRunnable(Task task, StageType type)
    {
        // Cleanup is a terminal marker projected from COMPLETED and is
        // immediately closed by StageLifecycle; it never admits work.
        if (type == StageType.CLEANUP_STAGE && task.phase() == TaskPhase.COMPLETED) {
            return;
        }
        TaskStatus status = task.status();
        if (status.isDone()
                || status == TaskStatus.PAUSED
                || status == TaskStatus.NEEDS_ATTENTION
                || status == TaskStatus.ARCHIVED
                || status == TaskStatus.ERRORED
                || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            throw conflict("task " + task.id() + " is stopped at "
                    + status + "/" + task.phase());
        }
    }

    private static void requireRunKindOwns(String runKind, StageType type)
    {
        StageType expected = switch (requireNonNull(runKind, "runKind is null")) {
            case AgentRun.KIND_CI_FIX -> StageType.CI_FIXING_STAGE;
            case AgentRun.KIND_REVIEW_ROUND -> StageType.REVIEW_ROUND_STAGE;
            case AgentRun.KIND_BRANCH_GUARD -> StageType.BRANCH_GUARD_STAGE;
            case AgentRun.KIND_REVIEW, AgentRun.KIND_PANEL_REVIEW -> StageType.REVIEW_STAGE;
            default -> throw conflict("run kind " + runKind + " does not own a container stage");
        };
        if (expected != type) {
            throw conflict("run kind " + runKind + " does not own " + type);
        }
    }

    private void requireCallerBelongsToTask(String taskId, UUID callerStageId)
    {
        if (callerStageId == null) {
            return;
        }
        StageInstance caller = requireStage(callerStageId);
        if (!caller.taskId().equals(taskId)) {
            throw conflict("caller stage " + callerStageId + " belongs to another task");
        }
    }

    private Task requireTask(String taskId)
    {
        return tasks.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
    }

    private StageInstance requireStage(UUID stageId)
    {
        return stages.findStageById(requireNonNull(stageId, "stageId is null"))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no stage: " + stageId));
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(409), message);
    }

    private enum Owner
    {
        PHASE,
        RUN
    }
}
