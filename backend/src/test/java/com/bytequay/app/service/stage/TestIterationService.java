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
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.sqlite.IterationStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestIterationService
{
    private final IterationStore iterations = mock(IterationStore.class);
    private final StageStore stages = mock(StageStore.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final ThreadTurnEventStore events = mock(ThreadTurnEventStore.class);
    private final IterationService service = new IterationService(
            iterations, stages, tasks, events);

    @Test
    void recordsAndReadsHistoricalIterationSummaries()
    {
        String taskId = "task-1";
        UUID stageId = UUID.randomUUID();
        UUID iterationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        TaskStageIteration iteration = TaskStageIteration.opened(
                iterationId, stageId, taskId, "turn-1", 1, "red_ci", now);
        Task task = mock(Task.class);
        when(task.threadId()).thenReturn("thread-1");
        when(tasks.findTaskById(taskId)).thenReturn(Optional.of(task));
        when(iterations.findById(iterationId)).thenReturn(Optional.of(iteration));
        when(stages.findStagesByTask(taskId)).thenReturn(List.of(
                new StageInstance(stageId, taskId, StageType.CI_FIXING_STAGE,
                        StageState.CLOSED, now, now.plusSeconds(1), null)));
        when(iterations.findByStage(stageId)).thenAnswer(ignored -> List.of(
                iteration.withSummary("fixed flaky test", now.plusSeconds(1))));

        assertThat(service.recordSummary(iterationId, "fixed flaky test").summaryText())
                .isEqualTo("fixed flaky test");
        assertThat(service.latestCiFixingSummaries(taskId))
                .containsExactly("fixed flaky test");
        verify(events).appendEvent(any());
    }
}
