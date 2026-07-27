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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskCompletionAnnouncer
{
    private static final String THREAD = "ws.t1";
    private static final String TASK = "ws.t1.k1";
    private static final String TURN = "turn-1";
    private static final String BRAIN_THREAD = "brain-1";

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskCompletionAnnouncer announcer =
            new TaskCompletionAnnouncer(taskStore, threadStore, mapper);

    private TaskTurnFinishedEvent finished(boolean failed)
    {
        return new TaskTurnFinishedEvent(TASK, TURN, failed);
    }

    private Task task(long seq)
    {
        return new Task(TASK, THREAD, seq, TaskStatus.RUNNING, "feat/x", null, "main", null, null, null,
                null, null, null, null, null, null, 0L, 0L, 0L, null, Instant.EPOCH, null, null, null, null, null);
    }

    private Task taskWithPr(long seq, int prNumber, String prState)
    {
        return new Task(TASK, THREAD, seq, TaskStatus.RUNNING, "feat/x", null, "main", null, null, null,
                prNumber, prState, null, null, null, null, 0L, 0L, 0L, null, Instant.EPOCH, null, null, null, null, null);
    }

    /** A task with an explicit {@code endedAt}, for the sweep's grace-window
     *  check — everything else defaulted like {@link #task}. */
    private Task taskEndedAt(long seq, Instant endedAt)
    {
        return new Task(TASK, THREAD, seq, TaskStatus.COMPLETED, "feat/x", null, "main", null, null, null,
                null, null, null, null, null, null, 0L, 0L, 0L, null, Instant.EPOCH, endedAt, null, null, null, null);
    }

    private Thread brainThread()
    {
        return new Thread(
                BRAIN_THREAD, ThreadKind.BRAIN_AGENT, "anthropic", null, "Brain · " + TASK,
                ThreadStatus.IDLE, "claude-haiku-4-5-20251001", 0L, 0L, 0L,
                Instant.EPOCH, Instant.EPOCH, null, null, ThreadFlow.BUILD, "ws-default", null, null, 1, TASK);
    }

    private ThreadMessage assistantText(String text)
    {
        return new ThreadMessage(
                "m-brain-1", BRAIN_THREAD, TASK, 1L, "assistant", "text",
                mapper.createObjectNode().put("text", text).toString(),
                null, null, null, null, Instant.EPOCH, null, ThreadScope.TASK);
    }

    @Test
    void writesTheBrainsAnswerWhenTheTurnSucceeds()
            throws Exception
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN)).thenReturn(Optional.of(task(3L)));
        when(threadStore.findBrainThreadByTask(TASK)).thenReturn(Optional.of(brainThread()));
        when(threadStore.listMessages(BRAIN_THREAD)).thenReturn(List.of(assistantText("Cleaned up dead endpoints.")));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.onTurnFinished(finished(false));

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        ThreadMessage m = captor.getValue();
        assertThat(m.threadId()).isEqualTo(THREAD);
        assertThat(m.taskId()).isNull();                 // trunk-scoped so it shows in the trunk feed
        assertThat(m.type()).isEqualTo("task_summary");
        assertThat(m.seq()).isEqualTo(8L);
        JsonNode env = mapper.readTree(m.contentJson());
        assertThat(env.get("text").asText()).isEqualTo("Cleaned up dead endpoints.");
        assertThat(env.get("taskId").asText()).isEqualTo(TASK);
        assertThat(env.get("taskSeq").asInt()).isEqualTo(3);
        verify(taskStore).clearPendingCompletionSummaryTurnId(TASK);
    }

    @Test
    void fallsBackWhenTheTurnFailed()
            throws Exception
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN)).thenReturn(Optional.of(task(3L)));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.onTurnFinished(finished(true));

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        JsonNode env = mapper.readTree(captor.getValue().contentJson());
        assertThat(env.get("text").asText()).contains("feat/x");
    }

    @Test
    void fallsBackWhenTheBrainAnswerIsBlank()
            throws Exception
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN)).thenReturn(Optional.of(task(3L)));
        when(threadStore.findBrainThreadByTask(TASK)).thenReturn(Optional.of(brainThread()));
        when(threadStore.listMessages(BRAIN_THREAD)).thenReturn(List.of(assistantText("")));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.onTurnFinished(finished(false));

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        JsonNode env = mapper.readTree(captor.getValue().contentJson());
        assertThat(env.get("text").asText()).contains("feat/x");
    }

    @Test
    void fallbackNamesTheClosedPrWhenNotMerged()
            throws Exception
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN))
                .thenReturn(Optional.of(taskWithPr(3L, 30, "closed")));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.onTurnFinished(finished(true));

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        JsonNode env = mapper.readTree(captor.getValue().contentJson());
        assertThat(env.get("text").asText()).contains("#30").contains("closed without merging");
    }

    @Test
    void fallbackNamesTheMergedPrWhenMerged()
            throws Exception
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN))
                .thenReturn(Optional.of(taskWithPr(3L, 30, "merged")));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.onTurnFinished(finished(true));

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        JsonNode env = mapper.readTree(captor.getValue().contentJson());
        assertThat(env.get("text").asText()).contains("#30").contains("— merged.").doesNotContain("closed");
    }

    @Test
    void ignoresATurnFinishedEventThatIsntACompletionSummaryTurn()
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId("some-other-turn")).thenReturn(Optional.empty());

        announcer.onTurnFinished(new TaskTurnFinishedEvent(TASK, "some-other-turn", false));

        verify(threadStore, never()).appendMessage(any());
    }

    @Test
    void isIdempotentWhenAMarkerAlreadyExists()
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN)).thenReturn(Optional.of(task(3L)));
        ThreadMessage existing = new ThreadMessage(
                "m0", THREAD, null, 5L, "assistant", "task_summary",
                "{\"text\":\"done\",\"taskId\":\"" + TASK + "\",\"taskSeq\":3}",
                null, null, null, null, Instant.EPOCH, null, ThreadScope.TRUNK);
        when(threadStore.listMessages(THREAD)).thenReturn(List.of(existing));

        announcer.onTurnFinished(finished(true));

        verify(threadStore, never()).appendMessage(any());
        verify(taskStore).clearPendingCompletionSummaryTurnId(TASK);
    }

    @Test
    void neverThrowsWhenTheWriteFails()
    {
        when(taskStore.findTaskByPendingCompletionSummaryTurnId(TURN)).thenReturn(Optional.of(task(3L)));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(0L));
        // Simulate a store failure — the completion must not blow up over a marker.
        doThrow(new RuntimeException("db down")).when(threadStore).appendMessage(any());

        announcer.onTurnFinished(finished(true));   // must not throw
    }

    @Test
    void sweepWritesFallbackForATaskPastTheGraceWindowWithNoMarker()
            throws Exception
    {
        when(taskStore.listByPhases(any(), anyInt())).thenReturn(List.of(taskEndedAt(3L, Instant.EPOCH)));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.sweepStaleCompletions();

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        JsonNode env = mapper.readTree(captor.getValue().contentJson());
        assertThat(env.get("text").asText()).contains("feat/x");
        verify(taskStore).clearPendingCompletionSummaryTurnId(TASK);
    }

    @Test
    void sweepSkipsATaskStillInsideTheGraceWindow()
    {
        when(taskStore.listByPhases(any(), anyInt())).thenReturn(List.of(taskEndedAt(3L, Instant.now())));

        announcer.sweepStaleCompletions();

        verify(threadStore, never()).appendMessage(any());
    }

    @Test
    void sweepSkipsATaskThatAlreadyHasAMarker()
    {
        when(taskStore.listByPhases(any(), anyInt())).thenReturn(List.of(taskEndedAt(3L, Instant.EPOCH)));
        ThreadMessage existing = new ThreadMessage(
                "m0", THREAD, null, 5L, "assistant", "task_summary",
                "{\"text\":\"done\",\"taskId\":\"" + TASK + "\",\"taskSeq\":3}",
                null, null, null, null, Instant.EPOCH, null, ThreadScope.TRUNK);
        when(threadStore.listMessages(THREAD)).thenReturn(List.of(existing));

        announcer.sweepStaleCompletions();

        verify(threadStore, never()).appendMessage(any());
    }
}
