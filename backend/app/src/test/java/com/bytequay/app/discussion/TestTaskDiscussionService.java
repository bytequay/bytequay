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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.service.ai.BetterWordsService;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionQuotes;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskDiscussionService
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final TaskId TASK = TaskId.random();
    private static final String ANSWER = "It throws **INVALID_TABLE_PROPERTY**\nwith the joined path.";

    private final TaskDiscussionStore store = mock(TaskDiscussionStore.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final TaskLogStore logStore = mock(TaskLogStore.class);

    private final TaskDiscussionService service = new TaskDiscussionService(
            store, tasks, resources, drivers, logStore, Clock.fixed(NOW, ZoneOffset.UTC), mock(TaskPullRequestOwnership.class),
            new ReviewCommentReader(mock(PullRequestStore.class), mock(PrDetailStore.class)), mock(BetterWordsService.class), mock(AgentReviewStore.class));

    @Test
    void refusesAskingOnceTheWorktreeIsReleasedAfterCompletion()
    {
        // A worktree is only released once the task is done, which is what makes a finished task's
        // threads read-only rather than merely idle.
        AgentTask completed = task().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(tasks.getInteractive("w1", TASK)).thenReturn(completed.withWorktreeRemovedAt(NOW.plusSeconds(60)));

        assertThatThrownBy(() -> service.open(open("INVALID_TABLE_PROPERTY", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("read-only");
        verify(store, never()).insertOrGet(any());
    }

    @Test
    void refusesAskingWhenTheTaskHasNoLiveWorktree()
    {
        when(tasks.getInteractive("w1", TASK)).thenReturn(withoutWorktree());

        assertThatThrownBy(() -> service.open(open("INVALID_TABLE_PROPERTY", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("read-only");
        verify(store, never()).insertOrGet(any());
    }

    @Test
    void refusesASelectionTooShortToAnchor()
    {
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());

        assertThatThrownBy(() -> service.open(open("**", 1, "why?")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Select more");
        verify(logStore, never()).readAfter(any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void refusesWhenTheTaskAlreadyHoldsTheMostDiscussionsItCan()
    {
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());
        when(store.findByAnchor(any(), any(), any())).thenReturn(Optional.empty());
        List<TaskDiscussion> full = IntStream.range(0, TaskDiscussionService.MAXIMUM_DISCUSSIONS_PER_TASK)
                .mapToObj(index -> discussion("d" + index, List.of()))
                .collect(ImmutableList.toImmutableList());
        when(store.listByTask(TASK.toString())).thenReturn(full);

        assertThatThrownBy(() -> service.open(open("INVALID_TABLE_PROPERTY", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("most discussions");
    }

    @Test
    void refusesResolvingAgainstAVersionTheReaderDidNotSee()
    {
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());
        when(store.find("d1")).thenReturn(Optional.of(discussion("d1", List.of())));

        assertThatThrownBy(() -> service.resolve("w1", TASK.toString(), "d1", true, 7))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("changed while you were reading it");
        verify(store, never()).update(any());
    }

    @Test
    void refusesRetryingAQuestionThatIsNotWaitingForOne()
    {
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());
        DiscussionMessage question = new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why?", NOW, Optional.of("The provider stopped"));
        DiscussionMessage answer = new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, "Because.", NOW, Optional.empty());
        when(store.find("d1")).thenReturn(Optional.of(discussion("d1", ImmutableList.of(question, answer))));

        // An answered thread has nothing to retry, and a question that is not the last one was answered after all.
        assertThatThrownBy(() -> service.retry("w1", TASK.toString(), "d1", "m2"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not waiting to be retried");
        assertThatThrownBy(() -> service.retry("w1", TASK.toString(), "d1", "m1"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not waiting to be retried");
        verify(store, never()).update(any());
    }

    @Test
    void projectsAThreadWithoutItsSeedOrProviderSession()
    {
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());
        when(store.listByTask(TASK.toString())).thenReturn(ImmutableList.of(discussion("d1",
                List.of(new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why?", NOW, Optional.empty())))));

        List<TaskDiscussionView> views = service.list("w1", TASK.toString());

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.quote()).isEqualTo("INVALID_TABLE_PROPERTY");
            assertThat(view.occurrence()).isEqualTo(1);
            assertThat(view.pending()).isFalse();
            assertThat(view.messages()).singleElement().satisfies(message -> assertThat(message.body()).isEqualTo("why?"));
        });
    }

    private static OpenDiscussionCommand open(String quote, int occurrence, String question)
    {
        return new OpenDiscussionCommand("w1", TASK.toString(), "turn-10", quote, occurrence, question);
    }

    private static TaskDiscussion discussion(String id, List<DiscussionMessage> messages)
    {
        return new TaskDiscussion(
                id,
                TASK.toString(),
                new DiscussionAnchor("turn-10", "INVALID_TABLE_PROPERTY", 1, DiscussionQuotes.hash("INVALID_TABLE_PROPERTY", 1)),
                Optional.empty(),
                Optional.of("--- conversation so far ---\n\n[agent]\n" + ANSWER),
                "claude",
                Optional.empty(),
                Optional.empty(),
                messages,
                false,
                NOW,
                NOW,
                0);
    }

    private static AgentTask withoutWorktree()
    {
        return AgentTask.created(
                TASK,
                Optional.of("w1"),
                true,
                "Released task",
                AgentLaneId.CLAUDE_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-discussions").resolve(TASK.toString()),
                new AgentTaskDefaults(Optional.of("opus"), Optional.empty(), AgentPermissionPolicy.AUTO),
                NOW);
    }

    private static AgentTask task()
    {
        return AgentTask.created(
                TASK,
                true,
                "Discussable task",
                TitleSource.USER,
                AgentLaneId.CLAUDE_CLI,
                new TaskWorktree(TASK, "w1", new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-discussions").resolve(TASK.toString()),
                        "bytequay/task/" + TASK, "a".repeat(40)),
                new AgentTaskDefaults(Optional.of("opus"), Optional.empty(), AgentPermissionPolicy.AUTO),
                NOW);
    }
}
