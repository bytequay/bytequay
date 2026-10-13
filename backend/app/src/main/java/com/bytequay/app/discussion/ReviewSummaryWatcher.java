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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.DevelopmentAutomation;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Starts a private summary for each new review comment on the pull requests of tasks that opted in.
 *
 * <p>It reads only cached pull-request detail and keeps no watch state: a comment is covered once any
 * thread exists for it, so repeated refreshes are idempotent, and comments left past the batch budget
 * or on a busy task are picked up by the next pass.
 */
@Component
public class ReviewSummaryWatcher
{
    private static final Logger log = LoggerFactory.getLogger(ReviewSummaryWatcher.class);
    private static final int MAXIMUM_BATCH_COMMENTS = 10;
    private static final int MAXIMUM_BATCH_CHARACTERS = 48_000;
    private static final Set<String> SUMMARIZED_KINDS = ImmutableSet.of("review", "review_comment");

    private final AgentTaskStore tasks;
    private final TaskPullRequestOwnership ownership;
    private final ReviewCommentReader comments;
    private final TaskDiscussionStore discussions;
    private final TaskDiscussionService service;
    private final AgentServingGate gate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("review-summary-", 0).factory());

    public ReviewSummaryWatcher(
            AgentTaskStore tasks,
            TaskPullRequestOwnership ownership,
            ReviewCommentReader comments,
            TaskDiscussionStore discussions,
            TaskDiscussionService service,
            AgentServingGate gate)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.comments = requireNonNull(comments, "comments is null");
        this.discussions = requireNonNull(discussions, "discussions is null");
        this.service = requireNonNull(service, "service is null");
        this.gate = requireNonNull(gate, "gate is null");
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
            try {
                watch(task);
            }
            catch (RuntimeException refused) {
                log.debug("Review comments on task {} were not summarized: {}", task.id(), refused.toString());
            }
        }
    }

    private void watch(AgentTask task)
    {
        // A running turn may be mid-edit, so a summary checked against its tree would mislead; the next refresh picks it up.
        if (!task.interactive()
                || task.archivedAt().isPresent()
                || task.taskWorktree().isEmpty()
                || task.worktreeRemovedAt().isPresent()
                || task.worktreeMutationClaim().isPresent()
                || task.defaults().developmentAutomation().filter(DevelopmentAutomation::reviewSummary).isEmpty()) {
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
