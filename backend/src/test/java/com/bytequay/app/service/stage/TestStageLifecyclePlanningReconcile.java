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
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup planning reconcile: a live PLANNING task with no stages at
 * all gets exactly one PlanStage + one planning kickoff; a task that
 * already has any stage, or is stopped, is left alone.
 */
class TestStageLifecyclePlanningReconcile
{
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    private final StageStore stageStore = mock(StageStore.class);
    private final StageStateMachine stageMachine = mock(StageStateMachine.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final StageLifecycle lifecycle =
            new StageLifecycle(stageStore, stageMachine, taskStore, events);

    @Test
    void stagelessLivePlanningTaskGetsOnePlanStageAndOneKick()
    {
        Task task = planningTask("task-1", TaskStatus.IDLE);
        when(taskStore.listByPhases(anyList(), anyInt())).thenReturn(List.of(task));
        when(stageStore.findStagesByTask("task-1")).thenReturn(List.of());
        when(stageMachine.ensurePhaseOpen("task-1", StageType.PLAN_STAGE, null))
                .thenReturn(stage("task-1"));

        lifecycle.reconcilePlanningTasksOnStartup();

        verify(stageMachine).ensurePhaseOpen("task-1", StageType.PLAN_STAGE, null);
        verify(events).publishEvent(new PlanKickoffRequested("task-1", "do the thing", null));
    }

    @Test
    void taskWithAnyStageIsLeftAlone()
    {
        Task task = planningTask("task-2", TaskStatus.IDLE);
        when(taskStore.listByPhases(anyList(), anyInt())).thenReturn(List.of(task));
        when(stageStore.findStagesByTask("task-2")).thenReturn(List.of(stage("task-2")));

        lifecycle.reconcilePlanningTasksOnStartup();

        verify(stageMachine, never()).ensurePhaseOpen(any(), any(), any());
        verify(events, never()).publishEvent(any(PlanKickoffRequested.class));
    }

    @Test
    void stoppedTaskIsNeverReArmed()
    {
        Task task = planningTask("task-3", TaskStatus.NEEDS_ATTENTION);
        when(taskStore.listByPhases(anyList(), anyInt())).thenReturn(List.of(task));

        lifecycle.reconcilePlanningTasksOnStartup();

        verify(stageMachine, never()).ensurePhaseOpen(any(), any(), any());
        verify(events, never()).publishEvent(any(PlanKickoffRequested.class));
    }

    private static Task planningTask(String id, TaskStatus status)
    {
        return new Task(
                id, "thread-1", 1L, status, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, TaskPhase.PLANNING, null, 0, null, "do the thing", "user");
    }

    private static StageInstance stage(String taskId)
    {
        return new StageInstance(
                UUID.randomUUID(), taskId, StageType.PLAN_STAGE,
                StageState.OPEN, NOW, null, null, null);
    }
}
