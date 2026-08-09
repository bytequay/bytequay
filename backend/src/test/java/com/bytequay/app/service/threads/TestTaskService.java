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

import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestTaskService
{
    private static final String THREAD = "thread-1";
    private static final String TASK = "task-1";
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");

    private TaskStore taskStore;
    private V2TaskControlService typed;
    private TaskService service;

    @BeforeEach
    void setUp()
    {
        taskStore = mock(TaskStore.class);
        typed = mock(V2TaskControlService.class);
        service = new TaskService(
                mock(ThreadStore.class), taskStore, mock(WatchedRepoStore.class));
        service.setV2Controls(typed);
    }

    @Test
    void legacyMutationFailsClosedBeforeEitherOwnerWrites()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(TASK)));

        assertThatThrownBy(() -> service.setAutoMerge(THREAD, TASK, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        verify(taskStore, never()).setAutoMerge(any(), anyBoolean());
        verifyNoInteractions(typed);
    }

    @Test
    void v2AutoMergeRoutesOnlyToTypedTaskControl()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(TASK)));
        when(taskStore.isV2Task(TASK)).thenReturn(true);
        when(typed.setAutoMerge(TASK, true)).thenReturn(true);

        assertThat(service.setAutoMerge(THREAD, TASK, true)).isTrue();

        verify(typed).setAutoMerge(TASK, true);
        verify(taskStore, never()).setAutoMerge(any(), anyBoolean());
    }

    @Test
    void v2NextReturnsToTrunkWithoutMutatingOrCreatingATask()
    {
        Task current = task(TASK);
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(current));
        when(taskStore.isV2Task(TASK)).thenReturn(true);

        Task result = service.parkAndStartNext(
                THREAD, TASK,
                new TaskService.ShipRequest(null, TaskService.BaseMode.MAIN));

        assertThat(result).isSameAs(current);
        verify(taskStore, never()).saveTask(any());
    }

    @Test
    void legacyAutoMergeRemainsReadable()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(TASK)));
        when(taskStore.isAutoMerge(TASK)).thenReturn(true);

        assertThat(service.isAutoMerge(THREAD, TASK)).isTrue();
    }

    private static Task task(String id)
    {
        return new Task(
                id, THREAD, 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }
}
