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

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.review.PullRequestsRefreshed;
import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.domain.PullRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Completes an interactive task once every pull request it owns is merged or closed on GitHub, as Complete does, and says
 * why on the task.
 *
 * <p>It reads only stored pull requests, which each list refresh keeps current, so it asks GitHub nothing. Completion
 * keeps its own refusals: a task with a turn running, input queued or changes not committed stays open and is tried
 * again after the next refresh. Reviews end on their own in the review service. Open pull requests also trigger opted-in private comment summaries.
 */
@Component
public class PullRequestTaskWatcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestTaskWatcher.class);
    private static final Set<String> ENDED = ImmutableSet.of("merged", "closed");

    private static final int MAXIMUM_BATCH_COMMENTS = 10;
    private static final int MAXIMUM_BATCH_CHARACTERS = 48_000;
    private static final Set<String> SUMMARIZED_KINDS = ImmutableSet.of("review", "review_comment");

    private final AgentTaskStore tasks;
    private final TaskPullRequestOwnership ownership;
    private final AgentCompletionService completions;
    private final AgentServingGate gate;
    private final ReviewCommentReader comments;
    private final TaskDiscussionStore discussions;
    private final TaskDiscussionService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("pull-request-followup-", 0).factory());

    public PullRequestTaskWatcher(
            AgentTaskStore tasks,
            TaskPullRequestOwnership ownership,
            AgentCompletionService completions,
            AgentServingGate gate,
            ReviewCommentReader comments,
            TaskDiscussionStore discussions,
            TaskDiscussionService service)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.comments = requireNonNull(comments, "comments is null");
        this.discussions = requireNonNull(discussions, "discussions is null");
        this.service = requireNonNull(service, "service is null");
    }

    /** The event arrives on the scheduler or an HTTP thread, so the pass runs on the watcher's own thread. */
    @EventListener
    public void onPullRequestsRefreshed(PullRequestsRefreshed event)
    {
        executor.execute(this::pass);
    }

    void pass()
    {
        if (!gate.isReady()) {
            return;
        }
        for (AgentTask task : tasks.listLive()) {
            if (!eligible(task)) {
                continue;
            }
            try {
                ended(task).ifPresent(reason -> completions.complete(new CompleteAgentTaskCommand(task.id(), Optional.of(reason))));
                summarize(task);
            }
            catch (RuntimeException refused) {
                log.info("Pull request follow-up on task {} was deferred: {}", task.id(), refused.toString());
            }
        }
    }

    /** Why the task is done: it owns pull requests, and GitHub reports each of them merged or closed. */
    private Optional<String> ended(AgentTask task)
    {
        List<PullRequest> owned = ownership.pullRequests(task);
        if (owned.isEmpty() || !owned.stream().allMatch(pullRequest -> ENDED.contains(state(pullRequest)))) {
            return Optional.empty();
        }
        PullRequest newest = owned.getFirst();
        return Optional.of("Completed: pull request #" + newest.number() + " was " + state(newest) + " on GitHub");
    }

    private static String state(PullRequest pullRequest)
    {
        return pullRequest.state().toLowerCase(Locale.ROOT);
    }

    private static boolean eligible(AgentTask task)
    {
        // A running turn may be mid-edit, so completion and summaries wait for the next refresh.
        return task.interactive() && task.archivedAt().isEmpty() && task.taskWorktree().isPresent()
                && task.worktreeRemovedAt().isEmpty() && task.worktreeMutationClaim().isEmpty();
    }

    private void summarize(AgentTask task)
    {
        if (task.defaults().developmentAutomation().filter(automation -> automation.reviewSummary() || automation.reviewReply()).isEmpty()) {
            return;
        }
        // Only the pull request the tab shows, so every summary lands where the user can see it.
        Optional<PullRequest> pullRequest = ownership.shownPullRequest(task).filter(TaskPullRequestOwnership::isOpen);
        if (pullRequest.isEmpty()) {
            return;
        }
        String taskId = task.id().toString();
        PullRequest pr = pullRequest.orElseThrow();
        Set<String> covered = discussions.listByTask(taskId).stream()
                .flatMap(discussion -> discussion.comment().stream())
                .map(ReviewCommentAnchor::key)
                .collect(toImmutableSet());
        ImmutableList.Builder<ReviewCommentAnchor> batch = ImmutableList.builder();
        int count = 0;
        int characters = 0;
        for (ReviewCommentItem item : comments.read(pr.repo(), pr.number())) {
            if (!SUMMARIZED_KINDS.contains(item.anchor().kind())
                    || (!task.defaults().developmentAutomation().orElseThrow().reviewSummary() && !item.anchor().kind().equals("review_comment"))
                    || item.bot()
                    || item.author().equalsIgnoreCase(pr.author())
                    || item.createdAt().filter(at -> !at.isBefore(task.createdAt())).isEmpty()
                    || covered.contains(item.anchor().key())) {
                continue;
            }
            int size = item.anchor().snapshot().length();
            if (count == MAXIMUM_BATCH_COMMENTS || (count > 0 && characters + size > MAXIMUM_BATCH_CHARACTERS)) {
                break;
            }
            batch.add(item.anchor());
            count++;
            characters += size;
        }
        List<ReviewCommentAnchor> anchors = batch.build();
        if (!anchors.isEmpty()) {
            service.summarize(task.workspaceId().orElseThrow(), taskId, anchors);
        }
    }

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }
}
