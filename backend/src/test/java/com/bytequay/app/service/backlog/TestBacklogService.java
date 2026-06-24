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
package com.bytequay.app.service.backlog;

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.service.threads.TaskQueueScheduler;
import com.bytequay.app.service.threads.TaskQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBacklogService
{
    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");

    private BacklogStore store;
    private TaskQueueService taskQueue;
    private TaskQueueScheduler scheduler;
    private BacklogServiceImpl service;

    @BeforeEach
    void setUp()
    {
        store = mock(BacklogStore.class);
        taskQueue = mock(TaskQueueService.class);
        scheduler = mock(TaskQueueScheduler.class);
        service = new BacklogServiceImpl(store, taskQueue, scheduler);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createPersistsAFreshItem()
    {
        BacklogItem saved = service.create("thread-1", "Add a cost meter", "body text", List.of("ui"));

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store).save(captor.capture());
        BacklogItem persisted = captor.getValue();
        assertThat(persisted.threadId()).isEqualTo("thread-1");
        assertThat(persisted.title()).isEqualTo("Add a cost meter");
        assertThat(persisted.body()).isEqualTo("body text");
        assertThat(persisted.tags()).containsExactly("ui");
        assertThat(persisted.startedAt()).isNull();
        assertThat(persisted.linkedTaskId()).isNull();
        assertThat(persisted.id()).isNotBlank();
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    void createRejectsABlankTitle()
    {
        assertThatThrownBy(() -> service.create("thread-1", "  ", "b", List.of()))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).save(any());
    }

    @Test
    void updateLeavesNullFieldsUnchanged()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));

        service.update("b1", null, "new body", null);

        ArgumentCaptor<BacklogItem> captor = ArgumentCaptor.forClass(BacklogItem.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("Title");
        assertThat(captor.getValue().body()).isEqualTo("new body");
        assertThat(captor.getValue().tags()).containsExactly("ui");
    }

    @Test
    void updateOnUnknownIdIs404()
    {
        when(store.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update("missing", "x", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void startDevelopmentQueuesAndLinksWhenThreadIsIdle()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));
        Task task = mock(Task.class);
        when(task.id()).thenReturn("task-9");
        when(scheduler.startNextIfIdle(eq("thread-1"), isNull())).thenReturn(Optional.of(task));

        BacklogService.StartResult result = service.startDevelopment("b1");

        verify(taskQueue).append(eq("thread-1"), eq("Title"), eq(BranchBase.MAIN), any());
        assertThat(result.taskId()).isEqualTo("task-9");
        assertThat(result.item().startedAt()).isNotNull();
        assertThat(result.item().linkedTaskId()).isEqualTo("task-9");
    }

    @Test
    void startDevelopmentStampsStartedEvenWhenThreadIsBusy()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", false, null)));
        when(scheduler.startNextIfIdle(eq("thread-1"), isNull())).thenReturn(Optional.empty());

        BacklogService.StartResult result = service.startDevelopment("b1");

        assertThat(result.taskId()).isNull();
        assertThat(result.item().startedAt()).isNotNull();
        assertThat(result.item().linkedTaskId()).isNull();
    }

    @Test
    void startDevelopmentOnAStartedItemIs409()
    {
        when(store.findById("b1")).thenReturn(Optional.of(item("b1", true, "task-1")));
        assertThatThrownBy(() -> service.startDevelopment("b1"))
                .isInstanceOf(ResponseStatusException.class);
        verify(taskQueue, never()).append(any(), any(), any(), any());
    }

    @Test
    void listDelegatesToTheStore()
    {
        BacklogItem i = item("b1", false, null);
        when(store.findByThread("thread-1")).thenReturn(List.of(i));
        assertThat(service.list("thread-1")).containsExactly(i);
    }

    private static BacklogItem item(String id, boolean started, String linkedTaskId)
    {
        return new BacklogItem(id, "thread-1", "Title", "Body", List.of("ui"),
                NOW, started ? NOW : null, linkedTaskId);
    }
}
