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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.localreview.LocalReviewMessage;
import com.bytequay.core.localreview.LocalReviewStore;
import com.bytequay.core.localreview.LocalReviewThread;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalQuestions
{
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void readsTheUsersQuestionsFromEveryPrivateCardNewestFirst()
    {
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        AgentTaskStore tasks = mock(AgentTaskStore.class);
        TaskDiscussionStore discussions = mock(TaskDiscussionStore.class);
        LocalReviewStore comments = mock(LocalReviewStore.class);
        AgentReviewStore reviews = mock(AgentReviewStore.class);
        Workspace workspace = inline(Workspace.class);
        when(workspace.id()).thenReturn("workspace");
        when(workspaces.findAllRetained()).thenReturn(ImmutableList.of(workspace));
        AgentTask task = inline(AgentTask.class);
        TaskId taskId = TaskId.random();
        when(task.id()).thenReturn(taskId);
        when(task.title()).thenReturn("Fix scan planning");
        when(tasks.listRecentInteractive("workspace", 50)).thenReturn(ImmutableList.of(task));

        TaskDiscussion discussion = inline(TaskDiscussion.class);
        ImmutableList<DiscussionMessage> messages = ImmutableList.of(
                discussionMessage("d-1", DiscussionMessage.Author.USER, "What is a manifest list?", NOW.minusSeconds(30)),
                discussionMessage("d-2", DiscussionMessage.Author.AGENT, "A manifest list names every manifest.", NOW.minusSeconds(29)),
                discussionMessage("d-3", DiscussionMessage.Author.USER, "Summarize this review comment.", NOW));
        when(discussion.messages()).thenReturn(messages);
        when(discussions.listByTask(taskId.toString())).thenReturn(ImmutableList.of(discussion));

        LocalReviewThread thread = inline(LocalReviewThread.class);
        LocalReviewMessage comment = inline(LocalReviewMessage.class);
        when(comment.id()).thenReturn("c-1");
        when(comment.author()).thenReturn(LocalReviewMessage.Author.USER);
        when(comment.body()).thenReturn("Why does this skip deleted files?");
        when(comment.createdAt()).thenReturn(NOW.minusSeconds(10));
        when(thread.messages()).thenReturn(ImmutableList.of(comment));
        when(thread.repo()).thenReturn("trinodb/trino");
        when(thread.number()).thenReturn(31252);
        when(thread.path()).thenReturn("plugin/trino-delta-lake/Checkpoint.java");
        when(comments.listByTask(taskId.toString())).thenReturn(ImmutableList.of(thread));

        AgentReview review = inline(AgentReview.class);
        when(review.id()).thenReturn("review-1");
        when(review.repo()).thenReturn("trinodb/trino");
        when(review.number()).thenReturn(OptionalInt.of(31300));
        when(reviews.listReviews("workspace")).thenReturn(ImmutableList.of(review));
        ReviewMessage question = inline(ReviewMessage.class);
        when(question.id()).thenReturn("r-1");
        when(question.author()).thenReturn(ReviewMessage.Author.USER);
        when(question.body()).thenReturn("Is the snapshot id stable here?");
        when(question.createdAt()).thenReturn(NOW.minusSeconds(20));
        when(reviews.listMessages("review-1")).thenReturn(ImmutableList.of(question));

        GoalQuestions questions = new GoalQuestions(workspaces, tasks, discussions, comments, reviews);
        assertThat(questions.nearest(goal("Ship the release", ""), 20))
                .extracting(GoalQuestions.Question::id, GoalQuestions.Question::where)
                .containsExactly(
                        tuple("comment:c-1", "trinodb/trino#31252 plugin/trino-delta-lake/Checkpoint.java"),
                        tuple("review:r-1", "trinodb/trino#31300"),
                        tuple("discussion:d-1", "Task Fix scan planning"));
        assertThat(questions.nearest(goal("Iceberg reads", "Manifest lists"), 2)).as("a question on the goal's topic comes first, however old")
                .extracting(GoalQuestions.Question::id).containsExactly("discussion:d-1", "comment:c-1");
    }

    private static GoalView goal(String title, String topic)
    {
        return new GoalView("goal-1", "workspace", title, "", true, Optional.empty(), Optional.empty(), 1, Optional.empty(), false, ImmutableList.of(),
                ImmutableList.of(), topic.isEmpty() ? ImmutableList.of() : ImmutableList.of(new GoalTopic("goal-1:topic", "goal-1", "workspace", topic,
                        GoalTopic.Level.NEW, ImmutableList.of(), false, NOW)), NOW, NOW);
    }

    private static DiscussionMessage discussionMessage(String id, DiscussionMessage.Author author, String body, Instant at)
    {
        DiscussionMessage message = inline(DiscussionMessage.class);
        when(message.id()).thenReturn(id);
        when(message.author()).thenReturn(author);
        when(message.body()).thenReturn(body);
        when(message.createdAt()).thenReturn(at);
        return message;
    }

    private static <T> T inline(Class<T> type)
    {
        return mock(type, withSettings().mockMaker(MockMakers.INLINE));
    }
}
