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
import java.util.function.Supplier;

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
    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final ParkedProposalService service =
            new ParkedProposalService(
                    tasks, notifications, new ObjectMapper(), commands, phaseMachine);

    TestParkedProposalService()
    {
        when(commands.execute(anyString(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    }

    @Test
    void parkWritesAwaitingReviewTaskAndActionableNotification()
            throws Exception
    {
        Task task = runningTask();
        when(phaseMachine.parkForLocalReviewInCommand(
                task.id(), Actor.AGENT, "publish_proposal_parked"))
                .thenReturn(task.withStatus(TaskStatus.AWAITING_REVIEW));

        service.park(task, Map.of("action", "push", "source", "mcp:push"));

        verify(phaseMachine).parkForLocalReviewInCommand(
                task.id(), Actor.AGENT, "publish_proposal_parked");
        verify(tasks, never()).saveTask(any());
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
        when(phaseMachine.parkForLocalReviewInCommand(
                task.id(), Actor.AGENT, "publish_proposal_parked"))
                .thenReturn(task.withStatus(TaskStatus.AWAITING_REVIEW));
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

        verify(phaseMachine).resumeFromLocalReviewInCommand(
                task.id(), Actor.HUMAN, "publish_proposal_discarded");
        verify(notifications).finishResolution(proposal.id());
    }

    @Test
    void interruptedNextResumesThePriorParkedTask()
    {
        // next_task no longer cuts a successor; it just resumes the prior
        // parked task locally so the user can keep editing.
        Task task = taskAt(TaskStatus.AWAITING_REVIEW, null);
        Notification proposal = proposal();
        when(tasks.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(notifications.finishResolution(proposal.id())).thenReturn(true);

        service.finishInterruptedApproval(proposal, "next_task");

        verify(phaseMachine).resumeFromLocalReviewInCommand(
                task.id(), Actor.HUMAN, "interrupted_next_resumed");
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

        verify(phaseMachine).finishTerminalInCommand(
                task.id(), TaskStatus.COMPLETED,
                Actor.HUMAN, "interrupted_publish_closed");
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

        verify(phaseMachine).finishTerminalInCommand(
                task.id(), TaskStatus.COMPLETED,
                Actor.HUMAN, "interrupted_publish_closed");
        verify(notifications).finishResolution(proposal.id());
    }

    private static Task runningTask()
    {
        return taskAt(TaskStatus.RUNNING, null);
    }

    @Test
    void approvedMidLoopPushKeepsAShippedTaskInReview()
    {
        // A shipped task (linked PR) parks a push to land a CI fix; approving
        // it must NOT complete the task — it is still in the PR / CI-fix loop.
        // Closing it would let the orphan sweep reap the worktree the loop
        // needs, dead-ending the autonomous fix.
        Task task = shippedTask();
        Notification proposal = proposal();
        when(tasks.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(notifications.finishResolution(proposal.id())).thenReturn(true);

        service.finishApproved(proposal, /* taskAlreadyAdvanced */ false);

        verify(phaseMachine).markRemoteInReviewInCommand(
                task.id(), Actor.HUMAN, "publish_proposal_approved");
    }

    /** A shipped task: AWAITING_REVIEW with a linked PR, so it is in the
     *  post-ship CI-fix / review loop. */
    private static Task shippedTask()
    {
        Instant now = Instant.parse("2026-05-22T12:00:00Z");
        return new Task(
                "task-1", "thread-1", 1L, TaskStatus.AWAITING_REVIEW,
                "feature/x", "/tmp/wt/x", "main", "/tmp/repo",
                null, null, null, null, null,
                "DEVELOP", /* linkedPrNumber */ 21, /* linkedIssueNumber */ null,
                0L, 0L, 0L, null,
                now, null, null, null, null, null);
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
