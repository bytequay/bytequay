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
package com.bytequay.app.service.brain;

import com.bytequay.app.beans.brain.BrainMessageResponse;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.ChatAttachmentStore;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskPhaseTransitionedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBrainService
{
    private static final String TASK_ID = "task-1";
    private static final String DEV_THREAD = "dev-thread";

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final WorkModelResolver workModelResolver = mock(WorkModelResolver.class);
    private final ChatAttachmentStore attachmentStore = new ChatAttachmentStore();

    private final BrainServiceImpl service = new BrainServiceImpl(
            taskStore, threadStore, scheduler, idGenerator, workModelResolver, attachmentStore, new ObjectMapper());

    @Test
    void createsBrainThreadOnFirstMessageAndEnqueuesTurn()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(task.threadId()).thenReturn(DEV_THREAD);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("turn-1");

        BrainMessageResponse out = service.sendMessage(TASK_ID, "How many pushes?", null);

        // A brain thread was created and saved with the right kind + parent.
        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(saved.capture());
        assertThat(saved.getValue().kind()).isEqualTo(ThreadKind.BRAIN_AGENT);
        assertThat(saved.getValue().parentTaskId()).isEqualTo(TASK_ID);
        // The answering turn was enqueued on that thread.
        verify(scheduler).enqueueTurn(eq(saved.getValue()), eq("How many pushes?"), any());
        assertThat(out.turnId()).isEqualTo("turn-1");
        assertThat(out.brainThreadId()).isEqualTo("ws-default.brain-1");
    }

    @Test
    void brainThreadFollowsTheResolvedCliWorkModel()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(task.threadId()).thenReturn(DEV_THREAD);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("turn-1");
        // Project default resolves to a claude-code CLI model.
        WorkModelResolver.Resolved resolved = mock(WorkModelResolver.Resolved.class);
        when(resolved.choice()).thenReturn(
                new WorkModel(WorkModelKind.CLI, "claude-code", "claude-sonnet-4-6", null));
        when(workModelResolver.resolveForThread(DEV_THREAD)).thenReturn(resolved);

        service.sendMessage(TASK_ID, "How many pushes?", null);

        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(saved.capture());
        assertThat(saved.getValue().workModel()).isNotNull();
        assertThat(saved.getValue().workModel().kind()).isEqualTo(WorkModelKind.CLI);
        assertThat(saved.getValue().workModel().agentOrProvider()).isEqualTo("claude-code");
    }

    @Test
    void reusesExistingBrainThread()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        Thread existing = mock(Thread.class);
        when(existing.id()).thenReturn("ws-default.brain-existing");
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(existing));
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("turn-2");

        BrainMessageResponse out = service.sendMessage(TASK_ID, "again", null);

        verify(threadStore, never()).saveThread(any());
        assertThat(out.brainThreadId()).isEqualTo("ws-default.brain-existing");
    }

    @Test
    void planKickoffCreatesBrainThreadAndEnqueuesPlanningTurn()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(task.threadId()).thenReturn(DEV_THREAD);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("plan-turn-1");

        service.onPlanKickoff(new PlanKickoffRequested(TASK_ID, "fix the flaky retry test", null));

        // The brain thread is created and a planning turn is enqueued on it,
        // carrying the seed prompt and the record_plan instruction.
        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(saved.capture());
        assertThat(saved.getValue().kind()).isEqualTo(ThreadKind.BRAIN_AGENT);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueTurn(eq(saved.getValue()), prompt.capture(), any());
        assertThat(prompt.getValue())
                .contains("fix the flaky retry test")
                .contains("record_plan");
    }

    @Test
    void planKickoffCopiesTheTrunkSeedOntoTheBrainThread()
    {
        Instant cut = Instant.parse("2026-06-22T10:00:00Z");
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(task.threadId()).thenReturn(DEV_THREAD);
        when(task.createdAt()).thenReturn(cut);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(taskStore.listTasksByThread(DEV_THREAD)).thenReturn(List.of(task));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(threadStore.listMessages(DEV_THREAD)).thenReturn(List.of(
                msg(1, "user", "{\"text\":\"let's tidy the AssertJ nits\"}", cut.minusSeconds(60)),
                msg(2, "assistant", "{\"text\":\"Got it — cutting the task.\"}", cut.minusSeconds(30))));
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("plan-turn-1");

        service.onPlanKickoff(new PlanKickoffRequested(TASK_ID, "tidy nits", null));

        // The trunk seed is copied onto the brain thread (single source),
        // preserving roles — not inlined into the prompt.
        ArgumentCaptor<ThreadMessage> copied = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore, times(2)).appendMessage(copied.capture());
        assertThat(copied.getAllValues()).extracting(ThreadMessage::role)
                .containsExactly("user", "assistant");
        assertThat(copied.getAllValues().get(0).threadId()).isEqualTo("ws-default.brain-1");
        assertThat(copied.getAllValues().get(0).contentJson()).contains("let's tidy the AssertJ nits");
        assertThat(copied.getAllValues().get(1).contentJson()).contains("Got it — cutting the task.");
    }

    private static ThreadMessage msg(long seq, String role, String contentJson, Instant ts)
    {
        return new ThreadMessage(
                "m" + seq, DEV_THREAD, null, seq, role, "text", contentJson,
                null, null, null, null, ts);
    }

    @Test
    void taskCompletedKicksOffASummaryTurnAndRecordsItsTurnId()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(task.threadId()).thenReturn(DEV_THREAD);
        when(task.prNumber()).thenReturn(30);
        when(task.prState()).thenReturn("merged");
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(taskStore.pendingCompletionSummaryTurnId(TASK_ID)).thenReturn(Optional.empty());
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("summary-turn-1");

        service.onTaskCompleted(
                new TaskPhaseTransitionedEvent(TASK_ID, TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.COMPLETED, "pr_merged"));

        ArgumentCaptor<Thread> saved = ArgumentCaptor.forClass(Thread.class);
        verify(threadStore).saveThread(saved.capture());
        assertThat(saved.getValue().kind()).isEqualTo(ThreadKind.BRAIN_AGENT);
        ArgumentCaptor<TurnInitiator> initiator = ArgumentCaptor.forClass(TurnInitiator.class);
        verify(scheduler).enqueueTurn(eq(saved.getValue()), anyString(), initiator.capture());
        assertThat(initiator.getValue().attended()).isFalse();
        verify(taskStore).setPendingCompletionSummaryTurnId(TASK_ID, "summary-turn-1");
    }

    @Test
    void taskCompletedPromptNamesTheMergedOrClosedPr()
    {
        Task merged = mock(Task.class);
        when(merged.id()).thenReturn(TASK_ID);
        when(merged.threadId()).thenReturn(DEV_THREAD);
        when(merged.prNumber()).thenReturn(30);
        when(merged.prState()).thenReturn("merged");
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(merged));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("summary-turn-1");

        service.onTaskCompleted(
                new TaskPhaseTransitionedEvent(TASK_ID, TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.COMPLETED, "pr_merged"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueTurn(any(), prompt.capture(), any());
        assertThat(prompt.getValue()).contains("#30").contains("was merged");
    }

    @Test
    void taskCompletedSkipsWhenATurnIsAlreadyPending()
    {
        when(taskStore.pendingCompletionSummaryTurnId(TASK_ID)).thenReturn(Optional.of("already-pending"));

        service.onTaskCompleted(
                new TaskPhaseTransitionedEvent(TASK_ID, TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.COMPLETED, "pr_merged"));

        verify(scheduler, never()).enqueueTurn(any(), anyString(), any());
    }

    @Test
    void taskCompletedLeavesNoPendingTurnIdWhenEnqueueFails()
    {
        Task task = mock(Task.class);
        when(task.id()).thenReturn(TASK_ID);
        when(task.threadId()).thenReturn(DEV_THREAD);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.empty());
        Thread devThread = mock(Thread.class);
        when(devThread.workspaceId()).thenReturn("ws-default");
        when(threadStore.findThreadById(DEV_THREAD)).thenReturn(Optional.of(devThread));
        when(idGenerator.newThreadId(any())).thenReturn("ws-default.brain-1");
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenThrow(new RuntimeException("scheduler busy"));

        service.onTaskCompleted(
                new TaskPhaseTransitionedEvent(TASK_ID, TaskPhase.AWAITING_REMOTE_REVIEW, TaskPhase.COMPLETED, "pr_merged"));

        // No pending turn id recorded — TaskCompletionAnnouncer's sweep is the backstop.
        verify(taskStore, never()).setPendingCompletionSummaryTurnId(anyString(), anyString());
    }

    @Test
    void ignoresNonCompletionPhaseTransitions()
    {
        service.onTaskCompleted(
                new TaskPhaseTransitionedEvent(TASK_ID, TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING, "x"));

        verify(scheduler, never()).enqueueTurn(any(), anyString(), any());
    }

    @Test
    void rejectsBlankTextAndUnknownTask()
    {
        assertThatThrownBy(() -> service.sendMessage(TASK_ID, "  ", null))
                .isInstanceOf(ResponseStatusException.class);

        when(taskStore.findTaskById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sendMessage("missing", "hi", null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
