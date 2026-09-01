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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

/** Creates and removes deterministic task worktrees without owning workflow state. */
@Component
public final class ManagedWorktreeService
        implements WorktreeService
{
    private static final List<String> GIT_OPERATION_PATHS = ImmutableList.of(
            "MERGE_HEAD",
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "REBASE_HEAD",
            "BISECT_LOG",
            "BISECT_START",
            "rebase-merge",
            "rebase-apply");

    private final WorkspacePaths paths;
    private final WorkspaceStore workspaces;
    private final GitCommandRunner git;

    public ManagedWorktreeService(
            WorkspacePaths paths,
            WorkspaceStore workspaces,
            GitCommandRunner git)
    {
        this.paths = requireNonNull(paths, "paths is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
    }

    @Override
    public TaskWorktree provision(ProvisionWorktreeCommand command)
    {
        requireNonNull(command, "command is null");
        Lock lock = WorkspaceOperationLocks.get(command.workspaceId());
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(command.workspaceId());
            verifyPhysicalRepository(workspace, command.physicalRepository());
            Path repository = verifyManagedRepository(workspace);
            requireExactCommit(repository, command.startCommit(), "start commit");
            prepareWorktreeParent(workspace);

            Path requestedPath = paths.canonicalTaskWorktree(
                    command.workspaceId(), command.taskId().toString());
            String branch = "bytequay/task/" + command.taskId();
            if (Files.exists(requestedPath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(requestedPath)
                        || !Files.isDirectory(requestedPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw conflict("The generated task worktree path is occupied");
                }
                TaskWorktree existing = recordedWorktree(command, requestedPath.toRealPath(), branch);
                WorktreeSnapshot snapshot;
                try {
                    snapshot = inspectVerified(workspace, repository, existing);
                }
                catch (InvalidAgentRequestException | AgentStorageException failure) {
                    throw new AgentConflictException(
                            "The generated task worktree path contains a mismatched or incomplete worktree",
                            failure);
                }
                if (!snapshot.head().equals(command.startCommit())
                        || snapshot.dirty()
                        || snapshot.gitOperationInProgress()) {
                    throw conflict("The existing task worktree does not match the original clean start commit");
                }
                return existing;
            }
            if (!Files.notExists(requestedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentStorageException("Cannot determine whether the generated task worktree path exists");
            }
            if (registeredWorktreePaths(repository).contains(requestedPath.toAbsolutePath().normalize())) {
                throw conflict("Git still registers the task worktree path");
            }
            if (branchExists(repository, branch)) {
                throw conflict("The generated task branch already exists");
            }

            Result created = git.run(repository, ImmutableList.of(
                    "worktree",
                    "add",
                    "--quiet",
                    "-b",
                    branch,
                    requestedPath.toString(),
                    command.startCommit()));
            if (!created.successful()) {
                throw new AgentStorageException("Cannot create task worktree: " + diagnostic(created));
            }

            try {
                TaskWorktree worktree = recordedWorktree(command, requestedPath.toRealPath(), branch);
                WorktreeSnapshot snapshot = inspectVerified(workspace, repository, worktree);
                if (!snapshot.head().equals(command.startCommit())
                        || snapshot.dirty()
                        || snapshot.gitOperationInProgress()) {
                    throw conflict("The created task worktree did not verify at the clean start commit");
                }
                return worktree;
            }
            catch (IOException failure) {
                throw new AgentStorageException("Cannot resolve the created task worktree", failure);
            }
        }
        catch (IOException failure) {
            throw new InvalidAgentRequestException("Cannot resolve the existing task worktree", failure);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public WorktreeSnapshot inspect(TaskWorktree worktree)
    {
        requireNonNull(worktree, "worktree is null");
        Workspace workspace = requireWorkspace(worktree.workspaceId());
        verifyPhysicalRepository(workspace, worktree.physicalRepository());
        return inspectVerified(workspace, verifyManagedRepository(workspace), worktree);
    }

    @Override
    public void cleanup(TaskWorktree worktree)
    {
        requireNonNull(worktree, "worktree is null");
        Lock lock = WorkspaceOperationLocks.get(worktree.workspaceId());
        lock.lock();
        try {
            Workspace workspace = requireWorkspace(worktree.workspaceId());
            verifyPhysicalRepository(workspace, worktree.physicalRepository());
            Path repository = verifyManagedRepository(workspace);
            verifyDerivedPath(worktree);

            if (Files.notExists(worktree.canonicalWorktree(), LinkOption.NOFOLLOW_LINKS)) {
                if (registeredWorktreePaths(repository).contains(worktree.canonicalWorktree())) {
                    throw conflict("Git still registers the missing task worktree");
                }
                requireBranch(repository, worktree.generatedBranch());
                return;
            }
            if (!Files.exists(worktree.canonicalWorktree(), LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentStorageException("Cannot determine whether the task worktree exists");
            }

            WorktreeSnapshot snapshot = inspectVerified(workspace, repository, worktree);
            if (snapshot.dirty()) {
                throw conflict("Task worktree has tracked, staged, or untracked changes");
            }
            if (snapshot.gitOperationInProgress()) {
                throw conflict("Task worktree has an open Git operation");
            }

            Result removed = git.run(repository, ImmutableList.of(
                    "worktree",
                    "remove",
                    worktree.canonicalWorktree().toString()));
            if (!removed.successful()) {
                throw new AgentStorageException("Cannot remove task worktree: " + diagnostic(removed));
            }
            if (registeredWorktreePaths(repository).contains(worktree.canonicalWorktree())
                    || !Files.notExists(worktree.canonicalWorktree(), LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentStorageException("Git did not fully remove the task worktree");
            }
            requireBranch(repository, worktree.generatedBranch());
        }
        finally {
            lock.unlock();
        }
    }

    private WorktreeSnapshot inspectVerified(
            Workspace workspace,
            Path repository,
            TaskWorktree worktree)
    {
        verifyDerivedPath(worktree);
        requireExactCommit(repository, worktree.startCommit(), "recorded start commit");
        try {
            Path expectedParent = paths.worktrees(workspace.id()).toRealPath();
            Path requestedPath = worktree.canonicalWorktree();
            if (Files.isSymbolicLink(requestedPath)
                    || !Files.isDirectory(requestedPath, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Task worktree is missing or is not a directory");
            }
            Path canonical = requestedPath.toRealPath();
            if (!canonical.equals(requestedPath) || !canonical.getParent().equals(expectedParent)) {
                throw invalid("Task worktree escaped its managed workspace");
            }
            Path topLevel = gitPath(canonical, "rev-parse", "--show-toplevel");
            if (!topLevel.equals(canonical)) {
                throw invalid("Task worktree is not the Git worktree root");
            }
            if (!commonGitDirectory(canonical).equals(commonGitDirectory(repository))) {
                throw invalid("Task worktree belongs to another managed repository");
            }
            requireOrigin(canonical, workspace.originCloneUrl());

            String branch = requireOutput(
                    git.run(canonical, "symbolic-ref", "--quiet", "--short", "HEAD"),
                    "read task worktree branch");
            if (!branch.equals(worktree.generatedBranch())) {
                throw invalid("Task worktree is on another branch");
            }
            String head = requireOutput(
                    git.run(canonical, "rev-parse", "--verify", "HEAD^{commit}"),
                    "read task worktree HEAD");
            boolean dirty = !requireOutput(
                    git.run(canonical, "status", "--porcelain=v1", "--untracked-files=all", "--ignored=matching"),
                    "read task worktree status").isEmpty();
            boolean operationInProgress = GIT_OPERATION_PATHS.stream()
                    .map(operation -> reportedGitPath(canonical, "rev-parse", "--git-path", operation))
                    .anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
            return new WorktreeSnapshot(worktree, head, dirty, operationInProgress);
        }
        catch (IOException failure) {
            throw new InvalidAgentRequestException("Cannot verify task worktree ownership", failure);
        }
    }

    private Path verifyManagedRepository(Workspace workspace)
    {
        Path repository = paths.repository(workspace);
        try {
            if (Files.isSymbolicLink(repository)
                    || !Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Managed repository is missing or is not a directory");
            }
            Path canonical = repository.toRealPath();
            Path workspaceDirectory = paths.workspace(workspace.id());
            Path marker = workspaceDirectory.resolve(ManagedWorkspaceGit.OWNERSHIP_FILE);
            if (Files.isSymbolicLink(workspaceDirectory)
                    || !canonical.getParent().equals(workspaceDirectory.toRealPath())
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(marker).equals(ManagedWorkspaceGit.ownership(workspace))) {
                throw invalid("Managed repository escaped its workspace");
            }
            if (!gitPath(canonical, "rev-parse", "--show-toplevel").equals(canonical)) {
                throw invalid("Managed repository is not the Git worktree root");
            }
            Path dotGit = canonical.resolve(".git");
            if (Files.isSymbolicLink(dotGit)
                    || !Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)
                    || !commonGitDirectory(canonical).equals(dotGit.toRealPath())) {
                throw invalid("Managed repository is not the owned primary checkout");
            }
            requireOrigin(canonical, workspace.originCloneUrl());
            return canonical;
        }
        catch (IOException failure) {
            throw new InvalidAgentRequestException("Cannot verify managed repository ownership", failure);
        }
    }

    private void prepareWorktreeParent(Workspace workspace)
    {
        Path parent = paths.worktrees(workspace.id());
        try {
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(parent);
            }
            if (Files.isSymbolicLink(parent)
                    || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || !parent.toRealPath().getParent().equals(paths.workspace(workspace.id()).toRealPath())) {
                throw invalid("Managed worktree parent is invalid");
            }
        }
        catch (IOException failure) {
            throw new AgentStorageException("Cannot prepare managed worktree parent", failure);
        }
    }

    private void verifyDerivedPath(TaskWorktree worktree)
    {
        try {
            Path canonicalExpected = paths.canonicalTaskWorktree(
                    worktree.workspaceId(), worktree.taskId().toString());
            if (!canonicalExpected.equals(worktree.canonicalWorktree())) {
                throw invalid("Recorded task worktree path is not task-derived");
            }
        }
        catch (IOException failure) {
            throw new InvalidAgentRequestException("Cannot verify recorded task worktree path", failure);
        }
    }

    private void verifyPhysicalRepository(Workspace workspace, RepositoryIdentity repository)
    {
        RepositoryIdentity expected = new RepositoryIdentity(
                workspace.selectedRepository().owner(),
                workspace.selectedRepository().repo());
        if (!expected.equals(repository)) {
            throw invalid("Task repository does not match the workspace origin");
        }
    }

    private void requireOrigin(Path repository, String expectedUrl)
    {
        String origin = requireOutput(
                git.run(repository, "config", "--get-all", "remote.origin.url"),
                "read origin URL");
        if (!origin.equals(expectedUrl) || origin.lines().count() != 1) {
            throw invalid("Managed repository origin does not match the workspace");
        }
        Result pushUrls = git.run(repository, "config", "--get-all", "remote.origin.pushurl");
        if (pushUrls.successful()) {
            throw invalid("Managed repository origin has separate push routing");
        }
        if (pushUrls.exitCode() != 1) {
            throw new AgentStorageException("Cannot inspect origin push routing: " + diagnostic(pushUrls));
        }
    }

    private void requireExactCommit(Path repository, String commit, String description)
    {
        Result result = git.run(repository, "cat-file", "-t", commit);
        if (!result.successful() || !result.output().strip().equals("commit")) {
            throw invalid("The exact " + description + " is not available in the managed repository");
        }
    }

    private void requireBranch(Path repository, String branch)
    {
        Result result = git.run(repository, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (!result.successful()) {
            throw invalid("The generated task branch is not available in the managed repository");
        }
    }

    private boolean branchExists(Path repository, String branch)
    {
        Result result = git.run(repository, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new AgentStorageException("Cannot inspect generated task branch: " + diagnostic(result));
    }

    private List<Path> registeredWorktreePaths(Path repository)
    {
        String output = requireOutput(
                git.run(repository, "worktree", "list", "--porcelain"),
                "list registered worktrees");
        return output.lines()
                .filter(line -> line.startsWith("worktree "))
                .map(line -> Path.of(line.substring("worktree ".length())).toAbsolutePath().normalize())
                .toList();
    }

    private Path commonGitDirectory(Path repository)
    {
        return gitPath(repository, "rev-parse", "--git-common-dir");
    }

    private Path gitPath(Path workingDirectory, String... arguments)
    {
        Path resolved = reportedGitPath(workingDirectory, arguments);
        try {
            return resolved.toRealPath();
        }
        catch (IOException failure) {
            throw new InvalidAgentRequestException("Git reported a path that cannot be resolved", failure);
        }
    }

    private Path reportedGitPath(Path workingDirectory, String... arguments)
    {
        String output = requireOutput(git.run(workingDirectory, arguments), "read Git path");
        Path path = Path.of(output);
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).normalize();
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> invalid("Workspace is not watched: " + workspaceId));
    }

    private static TaskWorktree recordedWorktree(
            ProvisionWorktreeCommand command,
            Path canonicalWorktree,
            String branch)
    {
        return new TaskWorktree(
                command.taskId(),
                command.workspaceId(),
                command.physicalRepository(),
                canonicalWorktree,
                branch,
                command.startCommit());
    }

    private static String requireOutput(Result result, String operation)
    {
        if (!result.successful()) {
            throw new AgentStorageException("Cannot " + operation + ": " + diagnostic(result));
        }
        if (result.truncated()) {
            throw new AgentStorageException("Cannot " + operation + ": Git output was truncated");
        }
        return result.output().strip();
    }

    private static String diagnostic(Result result)
    {
        String output = result.output().strip();
        return output.isEmpty() ? "Git exited with status " + result.exitCode() : output;
    }

    private static InvalidAgentRequestException invalid(String message)
    {
        return new InvalidAgentRequestException(message);
    }

    private static AgentConflictException conflict(String message)
    {
        return new AgentConflictException(message);
    }
}
