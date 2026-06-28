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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.stage.StageClosedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reaper stops and evicts only the closed stage's per-stage agent,
 * keying eviction by stage id and resolving the owning thread from the
 * task. A close with no live agent for the stage is a no-op.
 */
class TestStageAgentReaper
{
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final StageAgentReaper reaper = new StageAgentReaper(registry, taskStore);

    @Test
    void stopsAndEvictsTheClosedStageAgent()
    {
        ThreadAgent agent = mock(ThreadAgent.class);
        when(registry.findStage("stage-1")).thenReturn(Optional.of(agent));
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));

        reaper.onStageClosed(new StageClosedEvent("task-1", "stage-1"));

        verify(agent).stop();
        verify(registry).evictStage("thread-1", "stage-1");
    }

    @Test
    void evictsByStageIdEvenWhenNoLiveAgentExists()
    {
        when(registry.findStage("stage-1")).thenReturn(Optional.empty());
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task()));

        reaper.onStageClosed(new StageClosedEvent("task-1", "stage-1"));

        verify(registry).evictStage("thread-1", "stage-1");
    }

    @Test
    void ignoresAClosedEventWithNoStageId()
    {
        reaper.onStageClosed(new StageClosedEvent("task-1", null));

        verify(registry, never()).evictStage(any(), any());
    }

    private static Task task()
    {
        return new Task(
                "task-1", "thread-1", 1L, TaskStatus.RUNNING,
                "auto/task-1", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }
}
