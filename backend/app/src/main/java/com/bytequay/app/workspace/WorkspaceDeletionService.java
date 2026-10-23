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
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.artifact.ArtifactService;
import com.bytequay.app.context.ContextSpecCheckService;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.util.Objects.requireNonNull;

/** Deletes explicitly confirmed local workspace data after runtime and filesystem ownership checks. */
@Service
public class WorkspaceDeletionService
{
    private final WorkspaceStore workspaces;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentCompletionService completion;
    private final WorktreeService worktreeService;
    private final WorkspacePaths paths;
    private final GitCommandRunner git;
    private final ModelSettingsService models;
    private final ContextSpecCheckService specChecks;
    private final ArtifactService artifacts;

    public WorkspaceDeletionService(
            WorkspaceStore workspaces,
            AgentTaskStore tasks,
            AgentTurnStore turns,
            AgentCompletionService completion,
            WorktreeService worktreeService,
            WorkspacePaths paths,
            GitCommandRunner git,
            ModelSettingsService models,
            ContextSpecCheckService specChecks,
            ArtifactService artifacts)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.completion = requireNonNull(completion, "completion is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.paths = requireNonNull(paths, "paths is null");
        this.git = requireNonNull(git, "git is null");
        this.models = requireNonNull(models, "models is null");
        this.specChecks = requireNonNull(specChecks, "specChecks is null");
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
    }

    public void delete(String workspaceId, boolean confirmed, String repositoryName)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        if (!confirmed || !workspace.selectedRepository().fullName().equals(repositoryName)) {
            throw WorkspaceException.invalidRepository("Confirm deletion and enter the exact repository name before deleting this workspace.");
        }
        List<AgentTask> ownedTasks = tasks.listByWorkspace(workspaceId);
        requireIdle(workspaceId, ownedTasks);
        Lock lock = WorkspaceOperationLocks.get(workspaceId);
        withLock(lock, () -> {
            try {
                verifyDirectory(workspace, ownedTasks);
            }
            catch (IOException failure) {
                throw deletionFailed();
            }
            return null;
        });

        // Task deletion acquires its task lock before the workspace lock; never invert that order here.
        for (AgentTask task : ownedTasks) {
            completion.delete(task.id());
        }

        withLock(lock, () -> {
            try {
                Workspace current = requireWorkspace(workspaces, workspaceId);
                if (!current.selectedRepository().equals(workspace.selectedRepository()) || !tasks.listByWorkspace(workspaceId).isEmpty()) {
                    throw WorkspaceException.busy();
                }
                requireIdle(workspaceId, ImmutableList.of());
                verifyDirectory(current, ImmutableList.of());
                deleteDirectory(current);
                models.clearWorkspaceOverrides(workspaceId);
                specChecks.deleteWorkspaceData(workspaceId);
                artifacts.deleteWorkspaceData(workspaceId);
                workspaces.delete(workspaceId);
            }
            catch (IOException failure) {
                throw deletionFailed();
            }
            return null;
        });
    }

    private void requireIdle(String workspaceId, List<AgentTask> ownedTasks)
    {
        if (workspaces.hasActiveWorkflows(workspaceId)) {
            throw WorkspaceException.busy();
        }
        for (AgentTask task : ownedTasks) {
            if (task.worktreeMutationClaim().isPresent() || turns.findActive(task.id()).isPresent()) {
                throw new AgentConflictException("Stop the workspace's running tasks and resolve their process cleanup before deleting it.");
            }
        }
    }

    private void verifyDirectory(Workspace workspace, List<AgentTask> ownedTasks)
            throws IOException
    {
        Path directory = paths.workspace(workspace.id());
        Path repository = paths.repository(workspace);
        if (Files.notExists(directory, NOFOLLOW_LINKS)) {
            if (ownedTasks.stream().anyMatch(task -> task.taskWorktree().isPresent())) {
                throw unsafeDirectory();
            }
            return;
        }
        requireDirectory(paths.root());
        requireDirectory(directory);
        if (!directory.toRealPath().getParent().equals(paths.root().toRealPath())) {
            throw unsafeDirectory();
        }
        Path marker = directory.resolve(ManagedWorkspaceGit.OWNERSHIP_FILE);
        if (Files.notExists(marker, NOFOLLOW_LINKS) && ownedTasks.isEmpty()) {
            try (var children = Files.list(directory)) {
                if (children.findAny().isEmpty()) {
                    return;
                }
            }
        }
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || !Files.readString(marker).equals(ManagedWorkspaceGit.ownership(workspace))) {
            throw unsafeDirectory();
        }
        Set<String> knownEntries = ImmutableSet.of(ManagedWorkspaceGit.OWNERSHIP_FILE, "repository", "worktrees");
        try (var children = Files.list(directory)) {
            if (children.anyMatch(child -> !knownEntries.contains(child.getFileName().toString()))) {
                throw unsafeDirectory();
            }
        }
        Set<Path> knownWorktrees = ownedTasks.stream().flatMap(task -> task.taskWorktree().stream())
                .map(TaskWorktree::canonicalWorktree).collect(toImmutableSet());
        Path worktrees = paths.worktrees(workspace.id());
        if (!Files.notExists(worktrees, NOFOLLOW_LINKS)) {
            requireDirectory(worktrees);
            Path canonicalWorktrees = worktrees.toRealPath();
            try (var children = Files.list(worktrees)) {
                if (children.anyMatch(child -> Files.isSymbolicLink(child) || !knownWorktrees.contains(canonicalWorktrees.resolve(child.getFileName())))) {
                    throw unsafeDirectory();
                }
            }
        }
        if (Files.notExists(repository, NOFOLLOW_LINKS)) {
            if (!knownWorktrees.isEmpty()) {
                throw unsafeDirectory();
            }
            return;
        }
        requireDirectory(repository);
        Path canonicalRepository = repository.toRealPath();
        Path dotGit = repository.resolve(".git");
        requireDirectory(dotGit);
        if (!gitPath(repository, "rev-parse", "--show-toplevel").equals(canonicalRepository)
                || !gitPath(repository, "rev-parse", "--git-common-dir").equals(dotGit.toRealPath())
                || !output(repository, "config", "--get-all", "remote.origin.url").strip().equals(workspace.originCloneUrl())) {
            throw unsafeDirectory();
        }
        Set<Path> registered = Arrays.stream(output(repository, "worktree", "list", "--porcelain", "-z").split("\0"))
                .filter(value -> value.startsWith("worktree "))
                .map(value -> Path.of(value.substring("worktree ".length())).toAbsolutePath().normalize())
                .collect(toImmutableSet());
        if (!registered.contains(canonicalRepository)
                || registered.stream().anyMatch(path -> !path.equals(canonicalRepository) && !knownWorktrees.contains(path))) {
            throw unsafeDirectory();
        }
        for (AgentTask task : ownedTasks) {
            if (task.taskWorktree().isPresent() && Files.exists(task.worktree(), NOFOLLOW_LINKS)) {
                worktreeService.inspect(task.taskWorktree().orElseThrow());
            }
        }
    }

    private void deleteDirectory(Workspace workspace)
            throws IOException
    {
        Path directory = paths.workspace(workspace.id());
        if (Files.notExists(directory, NOFOLLOW_LINKS)) {
            return;
        }
        Path marker = directory.resolve(ManagedWorkspaceGit.OWNERSHIP_FILE);
        for (Path owned : ImmutableList.of(paths.repository(workspace), paths.worktrees(workspace.id()))) {
            if (Files.notExists(owned, NOFOLLOW_LINKS)) {
                continue;
            }
            requireDirectory(owned);
            MoreFiles.deleteRecursively(owned, RecursiveDeleteOption.ALLOW_INSECURE);
        }
        try (var children = Files.list(directory)) {
            if (children.anyMatch(child -> !child.equals(marker))) {
                throw unsafeDirectory();
            }
        }
        Files.deleteIfExists(marker);
        Files.delete(directory);
    }

    private Path gitPath(Path repository, String... arguments)
            throws IOException
    {
        return repository.resolve(output(repository, arguments).strip()).toRealPath();
    }

    private String output(Path repository, String... arguments)
    {
        return git.run(repository, arguments).requireOutput(ignored -> unsafeDirectory());
    }

    private static void requireDirectory(Path path)
    {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw unsafeDirectory();
        }
    }

    private static WorkspaceException unsafeDirectory()
    {
        return WorkspaceException.invalidRepository("Workspace files or worktrees could not be verified as ByteQuay-owned. They were preserved.");
    }

    private static WorkspaceException deletionFailed()
    {
        return WorkspaceException.invalidRepository("Workspace files could not be deleted. The workspace record was kept so deletion can be retried.");
    }
}
