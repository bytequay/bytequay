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
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.service.pr.PullRequestsRefreshed;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.PullRequest;
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

import static java.util.Objects.requireNonNull;

/**
 * Completes an interactive task once every pull request it owns is merged or closed on GitHub, as Complete does, and says
 * why on the task.
 *
 * <p>It reads only stored pull requests, which each list refresh keeps current, so it asks GitHub nothing. Completion
 * keeps its own refusals: a task with a turn running, input queued or changes not committed stays open and is tried
 * again after the next refresh. A review of the task's own changes borrows its working copy, so it ends first, as End
 * does; one that cannot end yet keeps the task open too. Pull request reviews end on their own in the review service. A
 * task still open is offered to Auto-Review fix, which reads the refreshed comments.
 */
@Component
public class PullRequestTaskWatcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestTaskWatcher.class);
    private static final Set<String> ENDED = ImmutableSet.of("merged", "closed");

    private final AgentTaskStore tasks;
    private final TaskPullRequestOwnership ownership;
    private final AgentCompletionService completions;
    private final AgentServingGate gate;
    private final ReviewFixService reviewFixes;
    private final AgentReviewService reviews;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("pull-request-followup-", 0).factory());

    public PullRequestTaskWatcher(
            AgentTaskStore tasks,
            TaskPullRequestOwnership ownership,
            AgentCompletionService completions,
            AgentServingGate gate,
            ReviewFixService reviewFixes,
            AgentReviewService reviews)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.completions = requireNonNull(completions, "completions is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.reviewFixes = requireNonNull(reviewFixes, "reviewFixes is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
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
                Optional<PullRequest> ended = ended(task);
                if (ended.isPresent()) {
                    String outcome = "pull request #" + ended.get().number() + " was " + state(ended.get()) + " on GitHub";
                    reviews.endForTask(task.id(), "Agent review ended: " + outcome);
                    completions.complete(new CompleteAgentTaskCommand(task.id(), Optional.of("Completed: " + outcome)));
                }
                else {
                    reviewFixes.request(task);
                }
            }
            catch (RuntimeException refused) {
                log.info("Pull request follow-up on task {} was deferred: {}", task.id(), refused.toString());
            }
        }
    }

    /** The newest pull request the task owns, when it owns some and GitHub reports each of them merged or closed. */
    private Optional<PullRequest> ended(AgentTask task)
    {
        List<PullRequest> owned = ownership.pullRequests(task);
        if (owned.isEmpty() || !owned.stream().allMatch(pullRequest -> ENDED.contains(state(pullRequest)))) {
            return Optional.empty();
        }
        return Optional.of(owned.getFirst());
    }

    private static String state(PullRequest pullRequest)
    {
        return pullRequest.state().toLowerCase(Locale.ROOT);
    }

    private static boolean eligible(AgentTask task)
    {
        // A running turn may be mid-edit, so completion waits for the next refresh.
        return task.interactive() && task.archivedAt().isEmpty() && task.taskWorktree().isPresent()
                && task.worktreeRemovedAt().isEmpty() && task.worktreeMutationClaim().isEmpty();
    }

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }
}
