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
package com.bytequay.app.workspace;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;

/** Which pull requests belong to an interactive task, for the surfaces that act on the task's worktree for that pull request. */
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
            return !open || find(task.workspaceId().orElseThrow(), repo, number).filter(TaskPullRequestOwnership::isOpen).isPresent();
        }
        TaskWorktree worktree = task.taskWorktree().orElseThrow(() -> new AgentConflictException("Task worktree is unavailable"));
        // The agent may have renamed the branch; the current branch is read only when the generated one does not match.
        return headBranchOf(worktree.workspaceId(), repo, number, worktree.generatedBranch(), open, false)
                || worktrees.currentBranch(worktree).filter(branch -> headBranchOf(worktree.workspaceId(), repo, number, branch, open, true)).isPresent();
    }

    /**
     * The assigned pull request, else the stored rows on the generated branch and on the worktree's current branch (that one only
     * when {@code AUTHORED}), newest number first; empty without a worktree. What {@code GET /agent/tasks/{taskId}/pull-requests} serves.
     */
    public List<PullRequest> pullRequests(AgentTask task)
    {
        return assignedPullRequests(task).orElseGet(() -> task.taskWorktree()
                .map(worktree -> branchPullRequests(repository(worktree.workspaceId()), worktree, branches(worktree)))
                .orElse(ImmutableList.of()));
    }

    /** The pull request the task links: the assigned one, else the newest live one on the generated branch. What the task response names. */
    public Optional<PullRequest> linkedPullRequest(AgentTask task)
    {
        return assignedPullRequests(task).flatMap(assigned -> assigned.stream().findFirst())
                .or(() -> task.taskWorktree().flatMap(worktree -> workspaces.findById(worktree.workspaceId())
                        .flatMap(workspace -> newestLive(byHead(worktree.workspaceId(), workspace.monitoredRepository().fullName(), worktree.generatedBranch())))));
    }

    public Optional<List<PullRequest>> assignedPullRequests(AgentTask task)
    {
        return assignments.findByTaskId(task.id().toString())
                .map(pr -> find(pr.workspaceId(), pr.repo(), pr.number()).stream().collect(toImmutableList()));
    }

    public List<String> branches(TaskWorktree worktree)
    {
        return Stream.concat(Stream.of(worktree.generatedBranch()), worktrees.currentBranch(worktree).stream())
                .distinct()
                .collect(toImmutableList());
    }

    /** A task owns the physical fork; pull requests live in the workspace's monitored repository. */
    public RepoRef repository(String workspaceId)
    {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId))
                .monitoredRepository();
    }

    /** A renamed branch can share a common name with another author's fork, so a current-branch match counts only for the user's own pull request. */
    public List<PullRequest> branchPullRequests(RepoRef repository, TaskWorktree worktree, List<String> branches)
    {
        return branches.stream()
                .flatMap(branch -> byHead(worktree.workspaceId(), repository.fullName(), branch).stream()
                        .filter(pr -> branch.equals(worktree.generatedBranch()) || pr.origin() == PullRequest.Origin.AUTHORED))
                .sorted(comparingInt(PullRequest::number).reversed())
                .collect(toImmutableList());
    }

    public static boolean isOpen(PullRequest pullRequest)
    {
        return "open".equalsIgnoreCase(pullRequest.state());
    }

    /**
     * An open pull request is the one the task works on; without one, the merged one is what it delivered. A closed, unmerged
     * row is never linked. Within each state the newest number wins.
     */
    static Optional<PullRequest> newestLive(List<PullRequest> pullRequests)
    {
        Comparator<PullRequest> newest = comparingInt(PullRequest::number);
        return pullRequests.stream().filter(pr -> !"merged".equals(pr.state()) && !"closed".equals(pr.state())).max(newest)
                .or(() -> pullRequests.stream().filter(pr -> "merged".equals(pr.state())).max(newest));
    }

    private Optional<PullRequest> find(String workspaceId, String repo, int number)
    {
        return workspaces.findById(workspaceId).flatMap(workspace -> workspace.isGitHub()
                ? pullRequests.findIdByRepoAndNumber(repo, number).flatMap(pullRequests::findById)
                : workspaces.findHostingRepositoryId(workspaceId).flatMap(id -> pullRequests.findByHostingRepository(id, number)));
    }

    private List<PullRequest> byHead(String workspaceId, String repo, String branch)
    {
        return workspaces.findById(workspaceId).filter(workspace -> !workspace.isGitHub())
                .map(workspace -> workspaces.findHostingRepositoryId(workspaceId).map(id -> pullRequests.findByHostingHeadRef(id, branch)).orElse(ImmutableList.of()))
                .orElseGet(() -> pullRequests.findByHeadRef(repo, branch));
    }

    private boolean headBranchOf(String workspaceId, String repo, int number, String branch, boolean open, boolean authored)
    {
        return byHead(workspaceId, repo, branch).stream()
                .anyMatch(pr -> pr.number() == number && (!open || isOpen(pr)) && (!authored || pr.origin() == PullRequest.Origin.AUTHORED));
    }
}
