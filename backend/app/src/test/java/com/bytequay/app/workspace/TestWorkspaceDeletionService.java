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

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.artifact.ArtifactService;
import com.bytequay.app.context.ContextSpecCheckService;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceDeletionService
{
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final String WORKSPACE_ID = "workspace-1";
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "project");
    private static final RepositoryIdentity PHYSICAL_REPOSITORY = new RepositoryIdentity("owner", "project");
    private static final AgentTaskDefaults DEFAULTS = new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO);

    @TempDir
    private Path temporary;

    private final GitCommandRunner git = new GitCommandRunner();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentCompletionService completion = mock(AgentCompletionService.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final ContextSpecCheckService specChecks = mock(ContextSpecCheckService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final List<AgentTask> ownedTasks = new ArrayList<>();
    private WorkspacePaths paths;
    private Workspace workspace;
    private WorkspaceDeletionService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        paths = new WorkspacePaths(temporary.resolve("workspaces").toString());
        Path repository = paths.repository(WORKSPACE_ID);
        workspace = new Workspace(
                WORKSPACE_ID, "Project", 100, REPOSITORY, Optional.empty(), Optional.empty(), 100, REPOSITORY, Topology.DIRECT,
                "main", "https://github.com/owner/project.git", Optional.empty(), Optional.empty(), repository.toString(),
                Optional.of(NOW), Optional.empty(), 0, NOW, NOW);
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE_ID).resolve(ManagedWorkspaceGit.OWNERSHIP_FILE), ManagedWorkspaceGit.ownership(workspace));
        run(repository, "init", "-q", "-b", "main");
        run(repository, "remote", "add", "origin", workspace.originCloneUrl());
        run(repository, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--allow-empty", "-q", "-m", "Initial");
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(tasks.listByWorkspace(WORKSPACE_ID)).thenAnswer(ignored -> ImmutableList.copyOf(ownedTasks));
        doAnswer(invocation -> {
            ownedTasks.removeIf(task -> task.id().equals(invocation.getArgument(0)));
            return null;
        }).when(completion).delete(any());
        service = new WorkspaceDeletionService(workspaces, tasks, turns, completion, worktreeService, paths, git, models, specChecks, artifacts);
    }

    @Test
    void deletesOwnedTasksDirtyCheckoutAndSettingsWithoutFollowingRepositorySymlinks()
            throws IOException
    {
        AgentTask task = task();
        ownedTasks.add(task);
        Path external = Files.writeString(temporary.resolve("keep.txt"), "keep");
        Path repository = paths.repository(workspace);
        Files.writeString(repository.resolve("uncommitted.txt"), "local work");
        Files.createSymbolicLink(repository.resolve("outside"), external);

        delete();

        verify(completion).delete(task.id());
        verify(models).clearWorkspaceOverrides(WORKSPACE_ID);
        verify(specChecks).deleteWorkspaceData(WORKSPACE_ID);
        verify(artifacts).deleteWorkspaceData(WORKSPACE_ID);
        verify(workspaces).delete(WORKSPACE_ID);
        assertThat(paths.workspace(WORKSPACE_ID)).doesNotExist();
        assertThat(external).hasContent("keep");
    }

    @Test
    void requiresConfirmationAndExactRepositoryNameBeforeInspectingOrDeletingTasks()
    {
        assertThatThrownBy(() -> service.delete(WORKSPACE_ID, false, REPOSITORY.fullName())).isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(() -> service.delete(WORKSPACE_ID, true, "another/project")).isInstanceOf(WorkspaceException.class);

        verifyNoInteractions(tasks, completion, worktreeService, models, specChecks, artifacts);
        verify(workspaces, never()).delete(any());
        assertThat(paths.repository(workspace)).isDirectory();
    }

    @Test
    void refusesEveryTaskBeforeDeletingAnyWhenOneHasAnActiveTurnOrClaim()
    {
        AgentTask idle = task();
        AgentTask active = task();
        ownedTasks.add(idle);
        ownedTasks.add(active);
        AgentTurnSettings settings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5),
                ImmutableList.of(), Optional.empty(), Optional.empty());
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), active.id(), "active-request", settings, NOW);
        when(turns.findActive(active.id())).thenReturn(Optional.of(turn));

        assertThatThrownBy(this::delete).isInstanceOf(AgentConflictException.class);

        when(turns.findActive(active.id())).thenReturn(Optional.empty());
        ownedTasks.set(1, active.claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW));
        assertThatThrownBy(this::delete).isInstanceOf(AgentConflictException.class);
        verifyNoInteractions(completion, specChecks, artifacts);
        verify(workspaces, never()).delete(any());
    }

    @Test
    void refusesActiveWorkflowsAndUnverifiableProviderCleanup()
    {
        when(workspaces.hasActiveWorkflows(WORKSPACE_ID)).thenReturn(true);
        assertThatThrownBy(this::delete).isInstanceOf(WorkspaceException.class);
        verifyNoInteractions(completion);

        when(workspaces.hasActiveWorkflows(WORKSPACE_ID)).thenReturn(false);
        AgentTask task = task();
        ownedTasks.add(task);
        doThrow(new AgentConflictException("process cleanup remains unverified")).when(completion).delete(task.id());

        assertThatThrownBy(this::delete).hasMessageContaining("unverified");
        verify(workspaces, never()).delete(any());
        assertThat(paths.repository(workspace)).isDirectory();
    }

    @Test
    void refusesAnUnownedWorkspaceOrUnknownWorktreeWithoutDeletingTasks()
            throws IOException
    {
        ownedTasks.add(task());
        Path marker = paths.workspace(WORKSPACE_ID).resolve(ManagedWorkspaceGit.OWNERSHIP_FILE);
        Files.writeString(marker, "another workspace\n");
        assertThatThrownBy(this::delete).isInstanceOf(WorkspaceException.class);
        Files.writeString(marker, ManagedWorkspaceGit.ownership(workspace));
        Path externalWorktree = temporary.resolve("external-worktree");
        run(paths.repository(workspace), "worktree", "add", "-q", "-b", "external", externalWorktree.toString());

        assertThatThrownBy(this::delete).isInstanceOf(WorkspaceException.class);

        verifyNoInteractions(completion);
        verify(workspaces, never()).delete(any());
        assertThat(externalWorktree).isDirectory();
        assertThat(paths.repository(workspace)).isDirectory();
    }

    @Test
    void refusesSymlinkedWorktreeParentWithoutFollowingIt()
            throws IOException
    {
        Path external = Files.createDirectory(temporary.resolve("external"));
        Files.writeString(external.resolve("keep.txt"), "keep");
        Files.createSymbolicLink(paths.worktrees(WORKSPACE_ID), external);

        assertThatThrownBy(this::delete).isInstanceOf(WorkspaceException.class);

        verify(workspaces, never()).delete(any());
        assertThat(external.resolve("keep.txt")).hasContent("keep");
    }

    @Test
    void verifiesKnownWorktreeBeforeDelegatingItsDestruction()
            throws IOException
    {
        TaskId taskId = TaskId.random();
        Path worktree = paths.worktrees(WORKSPACE_ID).resolve(taskId.toString());
        String branch = "bytequay/task/" + taskId;
        String head = run(paths.repository(workspace), "rev-parse", "HEAD");
        run(paths.repository(workspace), "worktree", "add", "-q", "-b", branch, worktree.toString());
        TaskWorktree owned = new TaskWorktree(taskId, WORKSPACE_ID, PHYSICAL_REPOSITORY, worktree.toRealPath(), branch, head);
        AgentTask task = AgentTask.created(taskId, true, "Task", TitleSource.USER, AgentLaneId.CODEX_CLI, owned, DEFAULTS, NOW);
        ownedTasks.add(task);
        doAnswer(invocation -> {
            run(paths.repository(workspace), "worktree", "remove", "--force", worktree.toString());
            run(paths.repository(workspace), "branch", "-D", branch);
            ownedTasks.clear();
            return null;
        }).when(completion).delete(taskId);

        delete();

        verify(worktreeService).inspect(owned);
        verify(completion).delete(taskId);
        assertThat(paths.workspace(WORKSPACE_ID)).doesNotExist();
    }

    @Test
    void finalRecheckPreservesWorkspaceIfAnotherTaskAppearsDuringCleanup()
    {
        AgentTask original = task();
        AgentTask raced = task();
        ownedTasks.add(original);
        doAnswer(invocation -> {
            ownedTasks.clear();
            ownedTasks.add(raced);
            return null;
        }).when(completion).delete(original.id());

        assertThatThrownBy(this::delete).isInstanceOf(WorkspaceException.class);

        verify(workspaces, never()).delete(any());
        assertThat(paths.repository(workspace)).isDirectory();
    }

    @Test
    void permitsAnEmptyDirectoryAfterMarkerRemovalButPreservesUnmarkedContent()
            throws IOException
    {
        delete();
        Files.createDirectory(paths.workspace(WORKSPACE_ID));
        Path unexpected = Files.writeString(paths.workspace(WORKSPACE_ID).resolve("keep.txt"), "keep");

        assertThatThrownBy(this::delete).isInstanceOf(WorkspaceException.class);
        assertThat(unexpected).hasContent("keep");

        Files.delete(unexpected);
        delete();

        assertThat(paths.workspace(WORKSPACE_ID)).doesNotExist();
    }

    @Test
    void retainsWorkspaceForRetryWhenSpecCheckDeletionFails()
    {
        doThrow(new IllegalStateException("Spec checks could not be deleted")).when(specChecks).deleteWorkspaceData(WORKSPACE_ID);

        assertThatThrownBy(this::delete).hasMessageContaining("Spec checks");

        verify(workspaces, never()).delete(any());
        doNothing().when(specChecks).deleteWorkspaceData(WORKSPACE_ID);

        delete();

        verify(workspaces).delete(WORKSPACE_ID);
    }

    private AgentTask task()
    {
        return AgentTask.created(TaskId.random(), Optional.of(WORKSPACE_ID), true, "Task", AgentLaneId.CODEX_CLI, PHYSICAL_REPOSITORY, paths.repository(workspace), DEFAULTS, NOW);
    }

    private void delete()
    {
        service.delete(WORKSPACE_ID, true, REPOSITORY.fullName());
    }

    private String run(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = git.run(directory, arguments);
        assertThat(result.successful()).describedAs(result.output()).isTrue();
        return result.output().strip();
    }
}
