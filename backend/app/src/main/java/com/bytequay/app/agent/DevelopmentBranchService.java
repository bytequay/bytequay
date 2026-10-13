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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskNames;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PullRequestTaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Local branch naming within the active development turn's existing worktree claim. */
@Component
public class DevelopmentBranchService
{
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentRuntimeRegistry runtime;
    private final DevelopmentGitInspector git;
    private final AgentReviewStore reviews;
    private final PullRequestTaskStore assignments;
    private final ObjectProvider<DevelopmentFollowupCoordinator> followups;
    private final Clock clock;

    public DevelopmentBranchService(AgentTaskStore tasks, AgentTurnStore turns, AgentRuntimeRegistry runtime, DevelopmentGitInspector git,
            AgentReviewStore reviews, PullRequestTaskStore assignments, ObjectProvider<DevelopmentFollowupCoordinator> followups, Clock clock)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.git = requireNonNull(git, "git is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.followups = requireNonNull(followups, "followups is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public String rename(TaskId taskId, TurnId turnId, String suffix)
    {
        new TaskNames("Name branch", suffix);
        synchronized (runtime.taskLock(taskId)) {
            AgentTask task = tasks.require(taskId);
            checkState(task.interactive() && task.state() == TaskState.OPEN && task.localReviewHead().isEmpty()
                    && reviews.findReviewByTask(taskId.toString()).isEmpty(), "Branch naming requires a development task");
            AgentTurn turn = turns.findActive(taskId).orElseThrow(() -> new IllegalStateException("The development turn has ended"));
            checkState(turn.id().equals(turnId) && turn.state() == TurnState.RUNNING && turn.cancellationRequestedAt().isEmpty() && turn.developmentOrigin().isEmpty()
                    && turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent(),
                    "Branch naming requires the active user development turn");
            List<AgentTurn> history = turns.list(taskId);
            checkState(history.size() == 1 && history.getFirst().id().equals(turnId), "Branch naming is only available during the first turn");
            checkState(task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.TURN && claim.ownerId().equals(turnId.toString())).isPresent(),
                    "The development turn no longer owns the worktree");
            TaskWorktree previous = task.taskWorktree().orElseThrow();
            if (assignments.findByTaskId(taskId.toString()).isPresent()) {
                return "Keep the imported PR branch: " + previous.generatedBranch();
            }
            String prefix = TaskBranches.prefix(previous.generatedBranch().split("/", 2)[0]);
            checkState(previous.generatedBranch().startsWith(prefix), "The task branch has no user namespace");
            return WorkspaceOperationLocks.withLock(previous.workspaceId(), () -> {
                TaskWorktree renamed = git.renameBranch(git.capture(task), prefix + suffix, updated -> {
                    tasks.update(tasks.require(taskId).withBranch(updated.generatedBranch(), Instant.now(clock)));
                });
                if (!renamed.equals(previous)) {
                    followups.getObject().branchRenamed(taskId, turnId, previous, renamed);
                    return "Renamed the local task branch: " + renamed.generatedBranch();
                }
                return "Keep the current branch (already named or published): " + previous.generatedBranch();
            });
        }
    }
}
