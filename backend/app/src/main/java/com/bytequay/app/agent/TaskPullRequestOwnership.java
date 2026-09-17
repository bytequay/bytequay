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
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Whether a pull request belongs to an interactive task, for the surfaces that act on the task's worktree for that pull request. */
@Component
public class TaskPullRequestOwnership
{
    private final WorkspaceStore workspaces;
    private final PullRequestTaskStore assignments;
    private final PullRequestStore pullRequests;
    private final ManagedWorktreeService worktrees;

    public TaskPullRequestOwnership(WorkspaceStore workspaces, PullRequestTaskStore assignments, PullRequestStore pullRequests, ManagedWorktreeService worktrees)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
    }

    /**
     * The pull request is in the task workspace's monitored repository and is either assigned to the task, or stored with the task's
     * generated branch or its worktree's current branch as its head branch. A current-branch match must be the user's own pull
     * request: a renamed branch can share a common name, such as {@code patch-1}, with another author's fork.
     *
     * @param open whether the pull request must also be open
     * @throws AgentConflictException when the task has no worktree whose branches could match
     */
    public boolean owns(AgentTask task, String repo, int number, boolean open)
    {
        if (task.workspaceId().flatMap(workspaces::findById).filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(repo)).isEmpty()) {
            return false;
        }
        if (assignments.findByTaskId(task.id().toString()).filter(assigned -> assigned.repo().equalsIgnoreCase(repo) && assigned.number() == number).isPresent()) {
            return !open || pullRequests.findIdByRepoAndNumber(repo, number).flatMap(pullRequests::findById).filter(TaskPullRequestOwnership::isOpen).isPresent();
        }
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new AgentConflictException("Task worktree is unavailable"));
        // The agent may have renamed the branch; the current branch is read only when the generated one does not match.
        return headBranchOf(repo, number, worktree.generatedBranch(), open, false)
                || worktrees.currentBranch(worktree).filter(branch -> headBranchOf(repo, number, branch, open, true)).isPresent();
    }

    private boolean headBranchOf(String repo, int number, String branch, boolean open, boolean authored)
    {
        return pullRequests.findByHeadRef(repo, branch).stream()
                .anyMatch(pr -> pr.number() == number && (!open || isOpen(pr)) && (!authored || pr.origin() == PullRequest.Origin.AUTHORED));
    }

    private static boolean isOpen(PullRequest pullRequest)
    {
        return "open".equalsIgnoreCase(pullRequest.state());
    }
}
