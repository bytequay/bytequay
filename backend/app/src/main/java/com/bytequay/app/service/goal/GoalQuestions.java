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
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.context.ContextText;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.localreview.LocalReviewMessage;
import com.bytequay.core.localreview.LocalReviewStore;
import com.bytequay.core.localreview.LocalReviewThread;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The questions the user asked in their private cards, in every workspace: private threads on pull-request comments and passage
 * discussions, local review comments and learning discussions, and review questions. They record what the user asked while working, and so
 * what they did not yet know. Local reads only; their text is reference data, never instructions.
 */
@Component
public class GoalQuestions
{
    // ponytail: the 50 most recent interactive tasks of each workspace; read further back if older questions start to matter.
    private static final int TASKS_PER_WORKSPACE = 50;
    private static final int MAX_TEXT = 400;
    // ByteQuay writes these requests itself; they are not the user's questions.
    private static final Pattern SUMMARY_REQUEST = Pattern.compile("Summarize (this review comment|these \\d+ review comments)\\.");

    private final WorkspaceStore workspaces;
    private final AgentTaskStore tasks;
    private final TaskDiscussionStore discussions;
    private final LocalReviewStore comments;
    private final AgentReviewStore reviews;

    public GoalQuestions(WorkspaceStore workspaces, AgentTaskStore tasks, TaskDiscussionStore discussions, LocalReviewStore comments, AgentReviewStore reviews)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.discussions = requireNonNull(discussions, "discussions is null");
        this.comments = requireNonNull(comments, "comments is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    /**
     * One question the user asked.
     *
     * @param id what an assessment cites, such as discussion:42
     * @param where the task or pull request it was asked on
     */
    public record Question(String id, String text, String where, Instant at) {}

    /**
     * The user's questions on the goal's topics first: those sharing the most words with the goal, its topics and its work, the newest first
     * among equals, then the newest of the rest.
     */
    public List<Question> nearest(GoalView goal, int limit)
    {
        Set<String> words = GoalCandidates.words(GoalCandidates.context(goal));
        return all().stream()
                .sorted(Comparator.comparingLong((Question question) -> GoalCandidates.words(question.text()).stream().filter(words::contains).count()).reversed()
                        .thenComparing(Question::at, Comparator.reverseOrder()))
                .limit(limit).collect(toImmutableList());
    }

    private List<Question> all()
    {
        List<Question> found = new ArrayList<>();
        for (Workspace workspace : workspaces.findAllRetained()) {
            for (AgentTask task : tasks.listRecentInteractive(workspace.id(), TASKS_PER_WORKSPACE)) {
                for (TaskDiscussion discussion : discussions.listByTask(task.id().toString())) {
                    for (DiscussionMessage message : discussion.messages()) {
                        if (message.author() == DiscussionMessage.Author.USER && !SUMMARY_REQUEST.matcher(message.body().strip()).matches()) {
                            found.add(question("discussion:" + message.id(), message.body(), "Task " + task.title(), message.createdAt()));
                        }
                    }
                }
                for (LocalReviewThread thread : comments.listByTask(task.id().toString())) {
                    for (LocalReviewMessage message : thread.messages()) {
                        if (message.author() == LocalReviewMessage.Author.USER) {
                            found.add(question("comment:" + message.id(), message.body(), thread.repo() + "#" + thread.number() + " " + thread.path(), message.createdAt()));
                        }
                    }
                }
            }
            for (AgentReview review : reviews.listReviews(workspace.id())) {
                String where = review.repo() + (review.number().isPresent() ? "#" + review.number().getAsInt() : "");
                for (ReviewMessage message : reviews.listMessages(review.id())) {
                    if (message.author() == ReviewMessage.Author.USER) {
                        found.add(question("review:" + message.id(), message.body(), where, message.createdAt()));
                    }
                }
            }
        }
        return found;
    }

    private static Question question(String id, String body, String where, Instant at)
    {
        return new Question(id, ContextText.excerpt(body.strip(), MAX_TEXT), where, at);
    }
}
