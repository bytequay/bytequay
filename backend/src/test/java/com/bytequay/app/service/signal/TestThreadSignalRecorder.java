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
package com.bytequay.app.service.signal;

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.TaskCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestThreadSignalRecorder
{
    private ThreadSignalServiceImpl signals;
    private TaskStore taskStore;
    private ThreadSignalRecorder recorder;

    @BeforeEach
    void setUp()
    {
        signals = mock(ThreadSignalServiceImpl.class);
        taskStore = mock(TaskStore.class);
        recorder = new ThreadSignalRecorder(signals, taskStore);
    }

    @Test
    void recordsAnInfoSignalForANewTask()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-9");
        when(task.threadId()).thenReturn("thread-1");
        when(task.seq()).thenReturn(3L);
        when(taskStore.findTaskById("task-9")).thenReturn(Optional.of(task));

        recorder.onTaskCreated(new TaskCreatedEvent("task-9"));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(signals).record(eq("thread-1"), eq("task-9"), eq("system"), eq("info"),
                title.capture(), any(), any());
        assertThat(title.getValue()).contains("3");
    }

    @Test
    void noOpWhenTheTaskIsUnknown()
    {
        when(taskStore.findTaskById("gone")).thenReturn(Optional.empty());
        recorder.onTaskCreated(new TaskCreatedEvent("gone"));
        verify(signals, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void swallowsExceptionsSoTaskCreationIsNeverDisturbed()
    {
        when(taskStore.findTaskById(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> recorder.onTaskCreated(new TaskCreatedEvent("task-9")))
                .doesNotThrowAnyException();
    }
}
