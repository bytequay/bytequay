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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.TaskAgent;
import com.bytequay.app.service.threads.TaskBrainAgent;
import com.bytequay.app.service.threads.ThreadRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStageRuntimeService
{
    private final StageStore stages = mock(StageStore.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final StageRuntimeService service = new StageRuntimeService(stages, tasks, threads, registry);

    @Test
    void developmentStageResolvesItsOwningTaskAgent()
    {
        UUID stageId = UUID.randomUUID();
        StageInstance stage = stage(stageId, StageType.DEVELOPMENT_STAGE);
        Task task = task();
        Thread thread = thread("dev-thread", ThreadKind.CLI_AGENT, null);
        TaskAgent agent = mock(TaskAgent.class);
        when(stages.findStageById(stageId)).thenReturn(Optional.of(stage));
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(threads.findThreadById("dev-thread")).thenReturn(Optional.of(thread));
        when(registry.getOrCreateTaskAgent(thread, task, stageId.toString())).thenReturn(agent);

        service.subscribe(stageId, ignored -> {});

        verify(registry).getOrCreateTaskAgent(thread, task, stageId.toString());
        verify(agent).subscribeToEvents(any());
    }

    @Test
    void planStageResolvesOnlyTheBoundTaskBrain()
    {
        UUID stageId = UUID.randomUUID();
        StageInstance stage = stage(stageId, StageType.PLAN_STAGE);
        Task task = task();
        Thread brain = thread("brain-1", ThreadKind.BRAIN_AGENT, "task-1");
        TaskBrainAgent agent = mock(TaskBrainAgent.class);
        when(stages.findStageById(stageId)).thenReturn(Optional.of(stage));
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(threads.findBrainThreadByTask("task-1")).thenReturn(Optional.of(brain));
        when(registry.getOrCreateTaskBrainAgent(brain)).thenReturn(agent);

        service.subscribe(stageId, ignored -> {});

        verify(registry).getOrCreateTaskBrainAgent(brain);
        verify(agent).subscribeToEvents(any());
    }

    @Test
    void developmentStageRejectsABrainThread()
    {
        UUID stageId = UUID.randomUUID();
        Task task = task();
        Thread brain = thread("dev-thread", ThreadKind.BRAIN_AGENT, "task-1");
        when(stages.findStageById(stageId))
                .thenReturn(Optional.of(stage(stageId, StageType.DEVELOPMENT_STAGE)));
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(threads.findThreadById("dev-thread"))
                .thenReturn(Optional.of(brain));

        assertThatThrownBy(() -> service.subscribe(stageId, ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("brain thread");
    }

    @Test
    void interruptOnlyTargetsTheStageThatIsActuallyRunning()
    {
        UUID stageId = UUID.randomUUID();
        StageInstance stage = stage(stageId, StageType.DEVELOPMENT_STAGE);
        Task task = task();
        Thread thread = thread("dev-thread", ThreadKind.CLI_AGENT, null);
        TaskAgent agent = mock(TaskAgent.class);
        when(stages.findStageById(stageId)).thenReturn(Optional.of(stage));
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(threads.findThreadById("dev-thread")).thenReturn(Optional.of(thread));
        when(registry.findTask("dev-thread", "task-1")).thenReturn(Optional.of(agent));
        when(agent.activeStageId()).thenReturn("another-stage");

        service.interrupt(stageId);

        verify(agent, never()).interrupt();

        when(agent.activeStageId()).thenReturn(stageId.toString());
        service.interrupt(stageId);

        verify(agent).interrupt();
    }

    private static StageInstance stage(UUID id, StageType type)
    {
        return new StageInstance(id, "task-1", type, StageState.OPEN, Instant.now(), null, null);
    }

    private static Task task()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-1");
        when(task.threadId()).thenReturn("dev-thread");
        return task;
    }

    private static Thread thread(String id, ThreadKind kind, String parentTaskId)
    {
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn(id);
        when(thread.kind()).thenReturn(kind);
        when(thread.parentTaskId()).thenReturn(parentTaskId);
        return thread;
    }
}
