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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStageSteeringService
{
    private static final Instant NOW = Instant.parse("2026-06-21T09:00:00Z");

    private StageStore stageStore;
    private TaskStore taskStore;
    private ThreadStore threadStore;
    private ThreadTurnScheduler scheduler;
    private IterationService iterationService;
    private StageSteeringServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        taskStore = mock(TaskStore.class);
        threadStore = mock(ThreadStore.class);
        scheduler = mock(ThreadTurnScheduler.class);
        iterationService = mock(IterationService.class);
        service = new StageSteeringServiceImpl(
                stageStore, taskStore, threadStore, scheduler, iterationService);
    }

    @Test
    void enqueuesASteeringTurnAndOpensAnIteration()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.ACTIVE)));
        when(taskStore.findTaskById("task-7")).thenReturn(Optional.of(task("task-7", "thread-9")));
        when(threadStore.findThreadById("thread-9")).thenReturn(Optional.of(thread("thread-9")));
        when(scheduler.enqueueTurn(any(), eq("Fix the retry default"), any())).thenReturn("turn-3");

        StageSteeringService.SteerResult result = service.steer(stageId, "  Fix the retry default  ");

        assertThat(result.turnId()).isEqualTo("turn-3");
        verify(scheduler).enqueueTurn(any(), eq("Fix the retry default"), any(TurnInitiator.class));
        verify(iterationService).begin("task-7", "turn-3", IterationService.TRIGGER_USER_STEERING);
    }

    @Test
    void rejectsAClosedStage()
    {
        UUID stageId = UUID.randomUUID();
        when(stageStore.findStageById(stageId)).thenReturn(Optional.of(
                stage(stageId, "task-7", StageState.CLOSED)));

        assertThatThrownBy(() -> service.steer(stageId, "go"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(422));
        verify(scheduler, never()).enqueueTurn(any(), any(), any());
    }

    @Test
    void rejectsAnEmptyMessage()
    {
        UUID stageId = UUID.randomUUID();

        assertThatThrownBy(() -> service.steer(stageId, "   "))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(400));
        verify(scheduler, never()).enqueueTurn(any(), any(), any());
    }

    private static StageInstance stage(UUID id, String taskId, StageState state)
    {
        return new StageInstance(id, taskId, StageType.CI_FIXING_STAGE, state, NOW, null, null);
    }

    private static Task task(String id, String threadId)
    {
        return new Task(
                id, threadId, 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, 42, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null, null,
                TaskPhase.PUSHED_AWAITING_CI, null, 0, null);
    }

    private static Thread thread(String id)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", null, "Dev thread",
                ThreadStatus.RUNNING, "claude-sonnet-4.6", 0L, 0L, 0L,
                NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }
}
