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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestTaskPullRequestOwnership
{
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final ManagedWorktreeService worktrees = mock(ManagedWorktreeService.class, withSettings().mockMaker(MockMakers.INLINE));
    private final TaskPullRequestOwnership ownership = new TaskPullRequestOwnership(workspaces, assignments, pullRequests, worktrees);
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"), Path.of("/managed/worktrees", taskId.toString()),
            "bytequay/task/" + taskId, "a".repeat(40));
    private final AgentTask task = AgentTask.created(taskId, true, "Task", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("owner", "repo"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(worktrees.currentBranch(worktree)).thenReturn(Optional.of("renamed"));
    }

    @Test
    void theTaskOwnsItsAssignedPullRequestOpenOnlyWhenAsked()
    {
        when(assignments.findByTaskId(taskId.toString())).thenReturn(Optional.of(new PullRequestTask(taskId.toString(), "workspace", "owner/repo", 7, "context")));
        when(pullRequests.findIdByRepoAndNumber("Owner/Repo", 7)).thenReturn(Optional.of(7L));
        when(pullRequests.findById(7L)).thenReturn(Optional.of(pullRequest(7, "open", PullRequest.Origin.AUTHORED)));

        assertThat(ownership.owns(task, "Owner/Repo", 7, true)).isTrue();
        when(pullRequests.findById(7L)).thenReturn(Optional.of(pullRequest(7, "closed", PullRequest.Origin.AUTHORED)));
        assertThat(ownership.owns(task, "Owner/Repo", 7, true)).isFalse();
        assertThat(ownership.owns(task, "Owner/Repo", 7, false)).isTrue();
        assertThat(ownership.owns(task, "owner/repo", 8, false)).isFalse();
        assertThat(ownership.owns(task, "other/repo", 7, false)).isFalse();
    }

    @Test
    void theTaskOwnsAPullRequestFromItsGeneratedOrCurrentBranchOpenOnlyWhenAsked()
    {
        when(pullRequests.findByHeadRef("owner/repo", worktree.generatedBranch())).thenReturn(ImmutableList.of(pullRequest(7, "closed", PullRequest.Origin.OTHER)));
        when(pullRequests.findByHeadRef("owner/repo", "renamed")).thenReturn(ImmutableList.of(pullRequest(9, "open", PullRequest.Origin.AUTHORED),
                pullRequest(11, "open", PullRequest.Origin.OTHER)));

        assertThat(ownership.owns(task, "owner/repo", 7, false)).isTrue();
        assertThat(ownership.owns(task, "owner/repo", 7, true)).isFalse();
        assertThat(ownership.owns(task, "owner/repo", 9, true)).isTrue();
        assertThat(ownership.owns(task, "owner/repo", 10, false)).isFalse();
        // Another author's fork on the same common branch name is not the task's pull request.
        assertThat(ownership.owns(task, "owner/repo", 11, false)).isFalse();
        // A repository the workspace does not monitor never matches, whatever its branches.
        assertThat(ownership.owns(task, "other/repo", 7, false)).isFalse();
        verify(pullRequests, never()).findByHeadRef("other/repo", worktree.generatedBranch());
    }

    @Test
    void theTaskPullRequestsAreTheAssignedOneElseItsBranchesNewestFirst()
    {
        when(pullRequests.findByHeadRef("owner/repo", worktree.generatedBranch())).thenReturn(ImmutableList.of(pullRequest(7, "closed", PullRequest.Origin.OTHER)));
        when(pullRequests.findByHeadRef("owner/repo", "renamed")).thenReturn(ImmutableList.of(pullRequest(9, "open", PullRequest.Origin.AUTHORED),
                pullRequest(11, "open", PullRequest.Origin.OTHER)));

        // Another author's pull request on the current branch is left out; the generated branch keeps any origin.
        assertThat(ownership.pullRequests(task)).extracting(PullRequest::number).containsExactly(9, 7);

        when(assignments.findByTaskId(taskId.toString())).thenReturn(Optional.of(new PullRequestTask(taskId.toString(), "workspace", "owner/repo", 3, "context")));
        when(pullRequests.findIdByRepoAndNumber("owner/repo", 3)).thenReturn(Optional.of(3L));
        when(pullRequests.findById(3L)).thenReturn(Optional.of(pullRequest(3, "open", PullRequest.Origin.OTHER)));

        assertThat(ownership.pullRequests(task)).extracting(PullRequest::number).containsExactly(3);
    }

    @Test
    void theLinkedPullRequestIsTheOneTheGeneratedBranchDeliveredEvenWhenANewerAuthoredOneIsOpen()
    {
        when(pullRequests.findByHeadRef("owner/repo", worktree.generatedBranch())).thenReturn(ImmutableList.of(pullRequest(10, "merged", PullRequest.Origin.OTHER)));
        when(pullRequests.findByHeadRef("owner/repo", "renamed")).thenReturn(ImmutableList.of(pullRequest(11, "open", PullRequest.Origin.AUTHORED)));

        // The tab lists #11 first, but the task links the pull request its generated branch delivered.
        assertThat(ownership.pullRequests(task)).extracting(PullRequest::number).containsExactly(11, 10);
        assertThat(ownership.linkedPullRequest(task)).map(PullRequest::number).contains(10);

        // With only a closed row on the generated branch nothing is linked.
        when(pullRequests.findByHeadRef("owner/repo", worktree.generatedBranch())).thenReturn(ImmutableList.of(pullRequest(10, "closed", PullRequest.Origin.OTHER)));
        assertThat(ownership.linkedPullRequest(task)).isEmpty();

        when(assignments.findByTaskId(taskId.toString())).thenReturn(Optional.of(new PullRequestTask(taskId.toString(), "workspace", "owner/repo", 3, "context")));
        when(pullRequests.findIdByRepoAndNumber("owner/repo", 3)).thenReturn(Optional.of(3L));
        when(pullRequests.findById(3L)).thenReturn(Optional.of(pullRequest(3, "closed", PullRequest.Origin.OTHER)));
        assertThat(ownership.linkedPullRequest(task)).map(PullRequest::number).contains(3);
    }

    @Test
    void theNewestLivePullRequestIsTheOpenOneElseTheMergedOneAndNeverAClosedOne()
    {
        PullRequest merged = pullRequest(40, "merged", PullRequest.Origin.AUTHORED);
        PullRequest olderOpen = pullRequest(41, "open", PullRequest.Origin.AUTHORED);
        PullRequest open = pullRequest(43, "open", PullRequest.Origin.AUTHORED);
        PullRequest closed = pullRequest(44, "closed", PullRequest.Origin.AUTHORED);

        assertThat(TaskPullRequestOwnership.newestLive(ImmutableList.of(merged, olderOpen, open, closed))).contains(open);
        assertThat(TaskPullRequestOwnership.newestLive(ImmutableList.of(closed, merged))).contains(merged);
        assertThat(TaskPullRequestOwnership.newestLive(ImmutableList.of(closed))).isEmpty();
    }

    @Test
    void aTaskWithoutAWorktreeHasNoBranchPullRequests()
    {
        AgentTask released = AgentTask.created(taskId, Optional.of("workspace"), true, "Task", AgentLaneId.CODEX_CLI, new RepositoryIdentity("owner", "repo"),
                Path.of("/managed/worktrees", taskId.toString()), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);

        assertThat(ownership.pullRequests(released)).isEmpty();
    }

    private PullRequest pullRequest(int number, String state, PullRequest.Origin origin)
    {
        return new PullRequest(number, "owner/repo", number, "Task", "owner", "https://github.com/owner/repo/pull/" + number, NOW, NOW, origin,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 1, 1, 0, state, null, null, null, null, NOW,
                worktree.generatedBranch(), null, null, null, null);
    }
}
