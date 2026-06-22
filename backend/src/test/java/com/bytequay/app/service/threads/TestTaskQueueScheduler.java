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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.QueuedTaskStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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

class TestTaskQueueScheduler
{
    private static final String THREAD_ID = "t1";
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private TaskStore taskStore;
    private ThreadStore threadStore;
    private TaskQueueService queue;
    private TaskQueueMaterialiser materialiser;
    private TaskPhaseMachine phaseMachine;
    private ApplicationEventPublisher events;
    private NotificationService notifications;
    private TaskQueueScheduler queueScheduler;

    @BeforeEach
    void setUp()
    {
        taskStore = mock(TaskStore.class);
        threadStore = mock(ThreadStore.class);
        queue = mock(TaskQueueService.class);
        materialiser = mock(TaskQueueMaterialiser.class);
        phaseMachine = mock(TaskPhaseMachine.class);
        events = mock(ApplicationEventPublisher.class);
        notifications = mock(NotificationService.class);
        queueScheduler = new TaskQueueScheduler(
                taskStore, threadStore, queue, materialiser, phaseMachine, events, notifications);
    }

    @Test
    void advanceMaterialisesAndStartsNextHeadWhenSlotFree()
    {
        Task completed = task("t1.k1", TaskPhase.COMPLETED, TaskStatus.COMPLETED);
        QueuedTask head = QueuedTask.pending(2, "next", BranchBase.MAIN, "go", NOW);
        Thread thread = threadWith(List.of(
                materializedEntry(1, "t1.k1"),
                head));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID)).thenReturn(List.of(completed));
        when(queue.pendingHead(any())).thenReturn(Optional.of(head));
        Task materialised = task("t1.k2", TaskPhase.QUEUED, TaskStatus.PENDING, "go");
        when(materialiser.materialiseHead(any(), eq(head), eq("/clone"))).thenReturn(materialised);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(materialised));

        queueScheduler.advance(completed);

        verify(queue).markCompleted(THREAD_ID, 1);
        verify(materialiser).materialiseHead(any(), eq(head), eq("/clone"));
        verify(phaseMachine).transition("t1.k2", TaskPhase.PLANNING, "slot_opened", Actor.SCHEDULER);
        verify(events).publishEvent(new PlanKickoffRequested("t1.k2", "go", null));
    }

    @Test
    void advanceDoesNothingWhenAnotherTaskOccupiesTheSlot()
    {
        Task completed = task("t1.k1", TaskPhase.COMPLETED, TaskStatus.COMPLETED);
        Task running = task("t1.k2", TaskPhase.IMPLEMENTING, TaskStatus.RUNNING);
        Thread thread = threadWith(List.of(
                materializedEntry(1, "t1.k1"),
                QueuedTask.pending(2, "next", BranchBase.MAIN, null, NOW)));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID)).thenReturn(List.of(completed, running));

        queueScheduler.advance(completed);

        verify(queue).markCompleted(THREAD_ID, 1);
        verify(materialiser, never()).materialiseHead(any(), any(), any());
        verify(phaseMachine, never()).transition(any(), any(), any(), any());
    }

    @Test
    void advanceIsNoOpWhenQueueIsDry()
    {
        Task completed = task("t1.k1", TaskPhase.COMPLETED, TaskStatus.COMPLETED);
        Thread thread = threadWith(List.of(materializedEntry(1, "t1.k1")));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID)).thenReturn(List.of(completed));
        when(queue.pendingHead(any())).thenReturn(Optional.empty());

        queueScheduler.advance(completed);

        verify(materialiser, never()).materialiseHead(any(), any(), any());
    }

    @Test
    void advanceNotifiesWhenHeadIsStacked()
    {
        Task completed = task("t1.k1", TaskPhase.COMPLETED, TaskStatus.COMPLETED);
        QueuedTask head = QueuedTask.pending(2, "stacked one", BranchBase.STACKED_ON_PREVIOUS, null, NOW);
        Thread thread = threadWith(List.of(materializedEntry(1, "t1.k1"), head));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID)).thenReturn(List.of(completed));
        when(queue.pendingHead(any())).thenReturn(Optional.of(head));
        Task materialised = task("t1.k2", TaskPhase.QUEUED, TaskStatus.PENDING);
        when(materialiser.materialiseHead(any(), eq(head), eq("/clone"))).thenReturn(materialised);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(materialised));

        queueScheduler.advance(completed);

        verify(notifications).notifyAwaitingReview(eq(THREAD_ID), eq("t1.k2"), any());
        verify(phaseMachine).transition("t1.k2", TaskPhase.PLANNING, "slot_opened", Actor.SCHEDULER);
    }

    @Test
    void startNextIfIdleRunsHeadOnIdleThreadUsingLatestTaskWorkingDir()
    {
        // The idle-kick path (queue_task on a free slot, no working-dir
        // hint): resolve the clone from the thread's latest task and start
        // the head straight away rather than waiting for a completion.
        QueuedTask head = QueuedTask.pending(1, "next", BranchBase.MAIN, "go", NOW);
        Thread thread = threadWith(List.of(head));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID)).thenReturn(List.of()); // slot free
        when(queue.pendingHead(any())).thenReturn(Optional.of(head));
        when(taskStore.findLatestTaskForThread(THREAD_ID))
                .thenReturn(Optional.of(task("t1.k0", TaskPhase.COMPLETED, TaskStatus.COMPLETED)));
        Task materialised = task("t1.k1", TaskPhase.QUEUED, TaskStatus.PENDING, "go");
        when(materialiser.materialiseHead(any(), eq(head), eq("/clone"))).thenReturn(materialised);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(materialised));

        Optional<Task> started = queueScheduler.startNextIfIdle(THREAD_ID, null);

        assertThat(started).isPresent();
        verify(materialiser).materialiseHead(any(), eq(head), eq("/clone"));
        verify(phaseMachine).transition("t1.k1", TaskPhase.PLANNING, "slot_opened", Actor.SCHEDULER);
    }

    @Test
    void startupKickStartsPendingQueuesFromBeforeRestart()
    {
        QueuedTask head = QueuedTask.pending(1, "next", BranchBase.MAIN, "go", NOW);
        Thread thread = threadWith(List.of(head));
        when(threadStore.threadIdsWithPendingQueue()).thenReturn(List.of(THREAD_ID));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID)).thenReturn(List.of());
        when(queue.pendingHead(any())).thenReturn(Optional.of(head));
        when(taskStore.findLatestTaskForThread(THREAD_ID))
                .thenReturn(Optional.of(task("t1.k0", TaskPhase.COMPLETED, TaskStatus.COMPLETED)));
        Task materialised = task("t1.k1", TaskPhase.QUEUED, TaskStatus.PENDING, "go");
        when(materialiser.materialiseHead(any(), eq(head), eq("/clone"))).thenReturn(materialised);
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(materialised));

        queueScheduler.startPendingQueuesOnStartup();

        verify(materialiser).materialiseHead(any(), eq(head), eq("/clone"));
        verify(phaseMachine).transition("t1.k1", TaskPhase.PLANNING, "slot_opened", Actor.SCHEDULER);
    }

    @Test
    void startNextIfIdleDoesNothingWhenSlotBusy()
    {
        QueuedTask head = QueuedTask.pending(1, "next", BranchBase.MAIN, null, NOW);
        Thread thread = threadWith(List.of(head));
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(taskStore.listTasksByThread(THREAD_ID))
                .thenReturn(List.of(task("t1.k9", TaskPhase.IMPLEMENTING, TaskStatus.RUNNING)));

        Optional<Task> started = queueScheduler.startNextIfIdle(THREAD_ID, "/clone");

        assertThat(started).isEmpty();
        verify(materialiser, never()).materialiseHead(any(), any(), any());
    }

    private static QueuedTask materializedEntry(int position, String taskId)
    {
        return new QueuedTask(position, "done", BranchBase.MAIN, null,
                QueuedTaskStatus.MATERIALIZED, taskId, NOW);
    }

    private static Task task(String id, TaskPhase phase, TaskStatus status)
    {
        return task(id, phase, status, null);
    }

    private static Task task(String id, TaskPhase phase, TaskStatus status, String openingPrompt)
    {
        return new Task(id, THREAD_ID, 1L, status, "dev/" + id, "/wt/" + id, "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                NOW, null, null, null, null, null, null, phase, null, 0, null, openingPrompt);
    }

    private static Thread threadWith(List<QueuedTask> queue)
    {
        return new Thread(
                THREAD_ID, ThreadKind.LOGIC_LOOP, "anthropic", null, "Thread",
                ThreadStatus.IDLE, "claude", 0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null, null, queue, 1);
    }
}
