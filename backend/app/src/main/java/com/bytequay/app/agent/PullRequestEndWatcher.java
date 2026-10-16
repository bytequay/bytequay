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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.review.PullRequestsRefreshed;
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
 * again after the next refresh. Reviews end on their own in the review service.
 */
@Component
public class PullRequestEndWatcher
{
    private static final Logger log = LoggerFactory.getLogger(PullRequestEndWatcher.class);
    private static final Set<String> ENDED = ImmutableSet.of("merged", "closed");

    private final AgentTaskStore tasks;
    private final TaskPullRequestOwnership ownership;
    private final AgentCompletionService completions;
    private final AgentServingGate gate;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("pull-request-end-", 0).factory());

    public PullRequestEndWatcher(
            AgentTaskStore tasks,
            TaskPullRequestOwnership ownership,
            AgentCompletionService completions,
            AgentServingGate gate)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.completions = requireNonNull(completions, "completions is null");
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
                ended(task).ifPresent(reason -> completions.complete(new CompleteAgentTaskCommand(task.id(), Optional.of(reason))));
            }
            catch (RuntimeException refused) {
                log.info("Task {} stays open although its pull request ended: {}", task.id(), refused.toString());
            }
        }
    }

    /** Why the task is done: it owns pull requests, and GitHub reports each of them merged or closed. */
    private Optional<String> ended(AgentTask task)
    {
        if (!task.interactive()
                || task.archivedAt().isPresent()
                || task.taskWorktree().isEmpty()
                || task.worktreeRemovedAt().isPresent()
                || task.worktreeMutationClaim().isPresent()) {
            return Optional.empty();
        }
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

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }
}
