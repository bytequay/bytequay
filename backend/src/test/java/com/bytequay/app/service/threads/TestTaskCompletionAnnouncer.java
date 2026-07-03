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
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskCompletionAnnouncer
{
    private static final String THREAD = "ws.t1";
    private static final String TASK = "ws.t1.k1";

    private final TaskStore taskStore = mock(TaskStore.class);
    private final IterationStore iterationStore = mock(IterationStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskCompletionAnnouncer announcer =
            new TaskCompletionAnnouncer(taskStore, iterationStore, threadStore, mapper);

    private TaskPhaseTransitionedEvent completed()
    {
        return new TaskPhaseTransitionedEvent(TASK, TaskPhase.INTERNAL_REVIEW, TaskPhase.COMPLETED, "pr_merged");
    }

    private Task task(long seq)
    {
        return new Task(TASK, THREAD, seq, TaskStatus.RUNNING, "feat/x", null, "main", null, null, null,
                null, null, null, null, null, null, 0L, 0L, 0L, null, Instant.EPOCH, null, null, null, null, null);
    }

    private TaskStageIteration summarised(String text)
    {
        return TaskStageIteration.opened(UUID.randomUUID(), UUID.randomUUID(), TASK, "turn", 1, "red_ci", Instant.EPOCH)
                .withSummary(text, Instant.EPOCH);
    }

    @Test
    void writesATrunkSummaryMarkerOnCompletion()
            throws Exception
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(3L)));
        when(iterationStore.findRecentSummaries(eq(TASK), anyInt()))
                .thenReturn(List.of(summarised("Hoisted repeated message() calls")));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(7L));

        announcer.onPhaseTransition(completed());

        ArgumentCaptor<ThreadMessage> captor = ArgumentCaptor.forClass(ThreadMessage.class);
        verify(threadStore).appendMessage(captor.capture());
        ThreadMessage m = captor.getValue();
        assertThat(m.threadId()).isEqualTo(THREAD);
        assertThat(m.taskId()).isNull();                 // trunk-scoped so it shows in the trunk feed
        assertThat(m.type()).isEqualTo("task_summary");
        assertThat(m.seq()).isEqualTo(8L);
        JsonNode env = mapper.readTree(m.contentJson());
        assertThat(env.get("text").asText()).isEqualTo("Hoisted repeated message() calls");
        assertThat(env.get("taskId").asText()).isEqualTo(TASK);
        assertThat(env.get("taskSeq").asInt()).isEqualTo(3);
    }

    @Test
    void ignoresNonCompletionTransitions()
    {
        announcer.onPhaseTransition(
                new TaskPhaseTransitionedEvent(TASK, TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING, "x"));
        verify(threadStore, never()).appendMessage(any());
    }

    @Test
    void skipsWhenTheTaskHasNoSummary()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(3L)));
        when(iterationStore.findRecentSummaries(eq(TASK), anyInt())).thenReturn(List.of());

        announcer.onPhaseTransition(completed());

        verify(threadStore, never()).appendMessage(any());
    }

    @Test
    void isIdempotentWhenAMarkerAlreadyExists()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(3L)));
        when(iterationStore.findRecentSummaries(eq(TASK), anyInt())).thenReturn(List.of(summarised("done")));
        ThreadMessage existing = new ThreadMessage(
                "m0", THREAD, null, 5L, "assistant", "task_summary",
                "{\"text\":\"done\",\"taskId\":\"" + TASK + "\",\"taskSeq\":3}",
                null, null, null, null, Instant.EPOCH);
        when(threadStore.listMessages(THREAD)).thenReturn(List.of(existing));

        announcer.onPhaseTransition(completed());

        verify(threadStore, never()).appendMessage(any());
    }

    @Test
    void neverThrowsWhenTheWriteFails()
    {
        when(taskStore.findTaskById(TASK)).thenReturn(Optional.of(task(3L)));
        when(iterationStore.findRecentSummaries(eq(TASK), anyInt())).thenReturn(List.of(summarised("done")));
        when(threadStore.listMessages(THREAD)).thenReturn(List.of());
        when(threadStore.maxMessageSeq(THREAD)).thenReturn(Optional.of(0L));
        when(threadStore.maxMessageSeq(anyString())).thenReturn(Optional.of(0L));
        // Simulate a store failure — the completion must not blow up over a marker.
        doThrow(new RuntimeException("db down")).when(threadStore).appendMessage(any());

        announcer.onPhaseTransition(completed());   // must not throw
    }
}
