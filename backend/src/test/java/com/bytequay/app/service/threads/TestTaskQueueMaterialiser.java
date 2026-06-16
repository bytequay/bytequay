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

import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskQueueMaterialiser
{
    private static final String THREAD_ID = "t1";
    private static final String NEW_TASK_ID = "t1.k2";

    @Test
    void materialiseHeadCutsQueuedTaskSeedsPromptAndSealsEntry()
    {
        ThreadService threadService = mock(ThreadService.class);
        TaskStore taskStore = mock(TaskStore.class);
        TaskQueueService queue = mock(TaskQueueService.class);

        Task cut = task(NEW_TASK_ID, TaskStatus.PENDING);
        when(threadService.materialiseTask(eq(THREAD_ID), any())).thenReturn(cut);
        when(taskStore.findTaskById(NEW_TASK_ID)).thenReturn(Optional.of(cut));

        TaskQueueMaterialiser materialiser =
                new TaskQueueMaterialiser(threadService, taskStore, queue);
        QueuedTask head = QueuedTask.pending(1, "first slice", BranchBase.MAIN, "start here",
                Instant.ofEpochMilli(1_700_000_000_000L));

        Task result = materialiser.materialiseHead(thread(), head, "/clone");

        assertThat(result.id()).isEqualTo(NEW_TASK_ID);
        verify(taskStore).updatePhase(NEW_TASK_ID, TaskPhase.QUEUED);
        verify(taskStore).setOpeningPrompt(NEW_TASK_ID, "start here");
        verify(queue).removeMaterialised(THREAD_ID, 1);
    }

    @Test
    void materialiseHeadWithoutPromptSkipsOpeningPromptWrite()
    {
        ThreadService threadService = mock(ThreadService.class);
        TaskStore taskStore = mock(TaskStore.class);
        TaskQueueService queue = mock(TaskQueueService.class);

        Task cut = task(NEW_TASK_ID, TaskStatus.PENDING);
        when(threadService.materialiseTask(eq(THREAD_ID), any())).thenReturn(cut);
        when(taskStore.findTaskById(NEW_TASK_ID)).thenReturn(Optional.of(cut));

        TaskQueueMaterialiser materialiser =
                new TaskQueueMaterialiser(threadService, taskStore, queue);
        QueuedTask head = QueuedTask.pending(2, "no prompt", BranchBase.MAIN, null,
                Instant.ofEpochMilli(1_700_000_000_000L));

        materialiser.materialiseHead(thread(), head, "/clone");

        verify(taskStore).updatePhase(NEW_TASK_ID, TaskPhase.QUEUED);
        verify(taskStore, never()).setOpeningPrompt(any(), any());
        verify(queue).removeMaterialised(THREAD_ID, 2);
    }

    private static Task task(String id, TaskStatus status)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task(id, THREAD_ID, 2L, status, "dev/" + id, "/wt/" + id, "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null);
    }

    private static Thread thread()
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Thread(
                THREAD_ID, ThreadKind.LOGIC_LOOP, "anthropic", null, "Thread",
                ThreadStatus.IDLE, "claude", 0L, 0L, 0L, now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null, null, null, List.of(), 1);
    }
}
