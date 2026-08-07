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
package com.bytequay.app.service.question;

import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.repository.sqlite.AgentQuestionStore;
import com.bytequay.app.service.threads.ThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentQuestionService
{
    private static final Instant NOW = Instant.parse("2026-06-30T00:00:00Z");

    private AgentQuestionStore store;
    private ThreadService threadService;
    private AgentQuestionService service;

    @BeforeEach
    void setUp()
    {
        store = mock(AgentQuestionStore.class);
        threadService = mock(ThreadService.class);
        service = new AgentQuestionServiceImpl(store, threadService);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void askPersistsAnOpenQuestion()
    {
        AgentQuestion saved = service.ask(
                "t1", "task-1", null, "Postgres or SQLite?", "context",
                List.of(new AgentQuestion.Option("a", "Postgres", null)), true);

        assertThat(saved.status()).isEqualTo("open");
        assertThat(saved.threadId()).isEqualTo("t1");
        assertThat(saved.taskId()).isEqualTo("task-1");
        assertThat(saved.question()).isEqualTo("Postgres or SQLite?");
        assertThat(saved.answeredAt()).isNull();
        assertThat(saved.id()).isNotBlank();
    }

    @Test
    void askRejectsABlankQuestion()
    {
        assertThatThrownBy(() -> service.ask("t1", null, null, "  ", null, List.of(), true))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).save(any());
    }

    @Test
    void answeringAnOptionPostsTheLabelToTheTaskTurn()
    {
        when(store.findById("q1")).thenReturn(Optional.of(open("task-1")));

        AgentQuestion answered = service.answer("q1", "a", null);

        // The picked option's label is posted as the next message on the task.
        verify(threadService).send("t1", "task-1", "Postgres");
        assertThat(answered.status()).isEqualTo("answered");
        assertThat(answered.answerOptionId()).isEqualTo("a");
    }

    @Test
    void answeringFreeFormPostsToTheTrunkWhenThereIsNoTask()
    {
        when(store.findById("q1")).thenReturn(Optional.of(open(null)));

        service.answer("q1", null, "use sqlite");

        verify(threadService).sendTrunk("t1", "use sqlite");
    }

    @Test
    void answeringWithNeitherOptionNorTextIs400()
    {
        when(store.findById("q1")).thenReturn(Optional.of(open("task-1")));
        assertThatThrownBy(() -> service.answer("q1", null, null))
                .isInstanceOf(ResponseStatusException.class);
        verify(threadService, never()).send(any(), any(), any());
    }

    @Test
    void answeringAnAlreadyAnsweredQuestionIs409()
    {
        AgentQuestion answered = open("task-1").withAnswer("a", null, NOW);
        when(store.findById("q1")).thenReturn(Optional.of(answered));
        assertThatThrownBy(() -> service.answer("q1", "a", null))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static AgentQuestion open(String taskId)
    {
        return new AgentQuestion(
                "q1", "t1", taskId, null, "Postgres or SQLite?", "context",
                List.of(new AgentQuestion.Option("a", "Postgres", null)), true,
                AgentQuestion.STATUS_OPEN, null, null, NOW, null);
    }
}
