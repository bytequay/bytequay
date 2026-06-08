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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestParkedProposalService
{
    private final TaskStore tasks = mock(TaskStore.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ParkedProposalService service =
            new ParkedProposalService(tasks, notifications, new ObjectMapper());

    @Test
    void parkWritesAwaitingReviewTaskAndActionableNotification()
            throws Exception
    {
        Task task = runningTask();

        service.park(task, Map.of("action", "push", "source", "mcp:push"));

        ArgumentCaptor<Task> parked = ArgumentCaptor.forClass(Task.class);
        verify(tasks).saveTask(parked.capture());
        assertThat(parked.getValue().status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(notifications).notifyAwaitingReview(
                eq(task.threadId()), eq(task.id()), payload.capture());
        JsonNode json = new ObjectMapper().readTree(payload.getValue());
        assertThat(json.path("action").asText()).isEqualTo("push");
        assertThat(json.path("source").asText()).isEqualTo("mcp:push");
    }

    @Test
    void notificationFailureIsPropagatedToTheCaller()
    {
        Task task = runningTask();
        when(notifications.notifyAwaitingReview(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("notification write failed"));

        assertThatThrownBy(() -> service.park(task, Map.of("action", "push")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification write failed");
    }

    @Test
    void discardAdvanceResumesTaskAndFinalizesClaim()
    {
        Task task = taskAt(TaskStatus.AWAITING_REVIEW, 123);
        Notification proposal = proposal();
        when(tasks.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(notifications.finishResolution(proposal.id())).thenReturn(true);

        service.finishDiscarded(proposal, true);

        ArgumentCaptor<Task> resumed = ArgumentCaptor.forClass(Task.class);
        verify(tasks).saveTask(resumed.capture());
        assertThat(resumed.getValue().status()).isEqualTo(TaskStatus.IDLE);
        assertThat(resumed.getValue().processPid()).isNull();
        verify(notifications).finishResolution(proposal.id());
    }

    @Test
    void interruptedNextWithActiveSuccessorKeepsPriorTaskParked()
    {
        Task task = taskAt(TaskStatus.AWAITING_REVIEW, null);
        Task successor = new Task(
                "task-2", "thread-1", 2L, TaskStatus.PENDING,
                "feature/y", "/tmp/wt/y", "main", "/tmp/repo",
                null, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L, null,
                task.createdAt(), null, null, null, null, null);
        Notification proposal = proposal();
        when(tasks.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(successor));
        when(notifications.finishResolution(proposal.id())).thenReturn(true);

        service.finishInterruptedApproval(proposal, "next_task");

        verify(tasks, never()).saveTask(any());
        verify(notifications).finishResolution(proposal.id());
    }

    @Test
    void interruptedOrdinaryPublishFinishesConfirmedTask()
    {
        Task task = taskAt(TaskStatus.AWAITING_REVIEW, null);
        Notification proposal = proposal();
        when(tasks.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(notifications.finishResolution(proposal.id())).thenReturn(true);

        service.finishInterruptedApproval(proposal, "push");

        ArgumentCaptor<Task> finished = ArgumentCaptor.forClass(Task.class);
        verify(tasks).saveTask(finished.capture());
        assertThat(finished.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        verify(notifications).finishResolution(proposal.id());
    }

    @Test
    void interruptedShipFinishesInsteadOfReopeningPotentiallyShippedTask()
    {
        Task task = taskAt(TaskStatus.AWAITING_REVIEW, null);
        Notification proposal = proposal();
        when(tasks.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(notifications.finishResolution(proposal.id())).thenReturn(true);

        service.finishInterruptedApproval(proposal, "ship_task");

        ArgumentCaptor<Task> finished = ArgumentCaptor.forClass(Task.class);
        verify(tasks).saveTask(finished.capture());
        assertThat(finished.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        verify(notifications).finishResolution(proposal.id());
    }

    private static Task runningTask()
    {
        return taskAt(TaskStatus.RUNNING, null);
    }

    private static Task taskAt(TaskStatus status, Integer processPid)
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                "task-1", "thread-1", 1L, status,
                "feature/x", "/tmp/wt/x", "main", "/tmp/repo",
                processPid, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L, null,
                now, null, null, null, null, null);
    }

    private static Notification proposal()
    {
        return new Notification(
                "notif-1", NotificationKind.AWAITING_REVIEW, "thread-1", "task-1",
                NotificationStatus.RESOLVING, "{\"action\":\"next_task\"}",
                Instant.parse("2026-05-22T12:00:00Z"), null);
    }
}
