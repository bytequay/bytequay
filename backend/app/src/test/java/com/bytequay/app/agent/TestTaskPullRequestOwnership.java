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
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.workspace.ManagedWorktreeService;
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

    private PullRequest pullRequest(int number, String state, PullRequest.Origin origin)
    {
        return new PullRequest(number, "owner/repo", number, "Task", "owner", "https://github.com/owner/repo/pull/" + number, NOW, NOW, origin,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 1, 1, 0, state, null, null, null, null, NOW,
                worktree.generatedBranch(), null, null, null, null);
    }
}
