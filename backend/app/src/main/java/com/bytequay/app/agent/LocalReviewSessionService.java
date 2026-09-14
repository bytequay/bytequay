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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.app.git.ReviewExperiments;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.agent.DevelopmentFollowupCoordinator.matchingPr;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** A clean published revision separates intended development from private experiments. */
@Service
public class LocalReviewSessionService
{
    private final AgentTurnService turns;
    private final DevelopmentGitInspector inspector;
    private final ReviewExperiments experiments;

    public LocalReviewSessionService(AgentTurnService turns, DevelopmentGitInspector inspector, ReviewExperiments experiments)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.inspector = requireNonNull(inspector, "inspector is null");
        this.experiments = requireNonNull(experiments, "experiments is null");
    }

    public AgentTask start(TaskId taskId, long expectedVersion)
    {
        return turns.updateLocalReview(taskId, expectedVersion, task -> {
            if (task.localReviewHead().isPresent()) {
                return task.localReviewHead();
            }
            DevelopmentBaseline baseline = inspector.capture(task);
            requireClean(baseline.local());
            DevelopmentPublicationFacts publication = inspector.inspectPublication(baseline.worktree(), baseline.route());
            String head = baseline.local().headSha();
            checkState(matchingPr(baseline.route(), publication).filter(pr -> pr.headSha().equals(head)).isPresent(),
                    "Create or update the draft pull request before starting local review");
            DevelopmentLocalFacts current = inspector.inspectLocal(baseline.worktree(), baseline.route());
            requireClean(current);
            checkState(current.headSha().equals(head), "Development changed while starting local review; try again");
            return Optional.of(head);
        });
    }

    public SyncResult synchronize(TaskId taskId, long expectedVersion)
    {
        AtomicReference<Optional<String>> warning = new AtomicReference<>(Optional.empty());
        AgentTask updated = turns.updateLocalReview(taskId, expectedVersion, task -> {
            String reviewedHead = task.localReviewHead().orElseThrow(() -> new AgentConflictException("This task is not in local review"));
            DevelopmentBaseline baseline = inspector.capture(task);
            DevelopmentPublicationFacts publication = inspector.inspectPublication(baseline.worktree(), baseline.route());
            String remoteHead = matchingPr(baseline.route(), publication)
                    .orElseThrow(() -> new AgentConflictException("The task has no open pull request to synchronize")).headSha();
            warning.set(experiments.synchronize(baseline.worktree(), reviewedHead, remoteHead,
                    // updateLocalReview owns the idle task's mutation claim throughout this callback.
                    () -> inspector.synchronizeSource(baseline, reviewedHead, remoteHead, () -> {})));
            return Optional.of(remoteHead);
        });
        return new SyncResult(updated, warning.get());
    }

    /** Called only after the user explicitly confirms discarding all private experiments. */
    public AgentTask finish(TaskId taskId, long expectedVersion)
    {
        return turns.updateLocalReview(taskId, expectedVersion, task -> {
            String head = task.localReviewHead().orElseThrow(() -> new AgentConflictException("This task is not in local review"));
            experiments.discard(task.taskWorktree().orElseThrow(), head);
            return Optional.empty();
        });
    }

    public record SyncResult(AgentTask task, Optional<String> warning)
    {
        public SyncResult
        {
            requireNonNull(task, "task is null");
            requireNonNull(warning, "warning is null");
        }
    }

    private static void requireClean(DevelopmentLocalFacts facts)
    {
        checkState(facts.uncommittedFileCount() == 0 && !facts.gitOperationInProgress(),
                "Local review requires a clean committed revision; uncommitted changes were preserved");
    }
}
