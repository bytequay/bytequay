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
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestManagedWorktreeService
{
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String ORIGIN = "https://github.com/acme/project.git";
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.valueOf("acme/project");

    @TempDir
    private Path temporaryDirectory;

    private WorkspacePaths paths;
    private RecordingGitCommandRunner git;
    private ManagedWorktreeService service;
    private Path repository;
    private String startCommit;

    @BeforeEach
    void setUp()
            throws IOException
    {
        paths = new WorkspacePaths(temporaryDirectory.resolve("managed").toString());
        repository = paths.repository(WORKSPACE_ID);
        Files.createDirectories(repository);
        Files.writeString(
                paths.workspace(WORKSPACE_ID).resolve(ManagedWorkspaceGit.OWNERSHIP_FILE),
                WORKSPACE_ID + "\n1\n");
        git = new RecordingGitCommandRunner();
        run(repository, "init", "--initial-branch=main");
        run(repository, "config", "user.name", "ByteQuay Test");
        run(repository, "config", "user.email", "bytequay@example.test");
        run(repository, "remote", "add", "origin", ORIGIN);
        Files.writeString(repository.resolve("README.md"), "start\n");
        run(repository, "add", "README.md");
        run(repository, "commit", "-m", "Start");
        startCommit = run(repository, "rev-parse", "HEAD").output().strip();

        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        service = new ManagedWorktreeService(paths, workspaces, git);
    }

    @Test
    void provisionsDistinctVerifiedTaskWorktrees()
            throws IOException
    {
        TaskWorktree first = service.provision(command(TaskId.random()));
        TaskWorktree second = service.provision(command(TaskId.random()));

        assertThat(first.canonicalWorktree()).isNotEqualTo(second.canonicalWorktree());
        assertThat(first.generatedBranch()).isNotEqualTo(second.generatedBranch());
        assertThat(first.canonicalWorktree().getParent()).isEqualTo(paths.worktrees(WORKSPACE_ID).toRealPath());
        assertThat(paths.canonicalTaskWorktree(WORKSPACE_ID, first.taskId().toString()))
                .isEqualTo(first.canonicalWorktree());
        assertThat(service.provision(command(first.taskId()))).isEqualTo(first);

        WorktreeSnapshot snapshot = service.inspect(first);
        assertThat(snapshot.head()).isEqualTo(startCommit);
        assertThat(snapshot.dirty()).isFalse();
        assertThat(snapshot.gitOperationInProgress()).isFalse();
    }

    @Test
    void derivesTheSameCanonicalTaskPathThroughASymlinkedRoot()
            throws IOException
    {
        Path realRoot = Files.createDirectory(temporaryDirectory.resolve("real-root"));
        Path linkedRoot = Files.createSymbolicLink(temporaryDirectory.resolve("linked-root"), realRoot);
        WorkspacePaths linkedPaths = new WorkspacePaths(linkedRoot.toString());
        Files.createDirectories(linkedPaths.workspace("workspace-2"));
        TaskId taskId = TaskId.random();

        assertThat(linkedPaths.canonicalTaskWorktree("workspace-2", taskId.toString()))
                .isEqualTo(realRoot.toRealPath().resolve("workspace-2/worktrees").resolve(taskId.toString()));
    }

    @Test
    void cleanCleanupRemovesOnlyWorktreeAndRetainsBranch()
    {
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        git.clear();

        service.cleanup(worktree);
        service.cleanup(worktree);

        assertThat(worktree.canonicalWorktree()).doesNotExist();
        assertThat(run(repository, "rev-parse", "refs/heads/" + worktree.generatedBranch()).output().strip())
                .isEqualTo(startCommit);
        assertThat(repository).isDirectory();
        assertThat(git.arguments()).contains(List.of(
                "worktree",
                "remove",
                worktree.canonicalWorktree().toString()));
        assertThat(git.arguments()).noneMatch(arguments -> arguments.contains("--force")
                || arguments.equals(List.of("worktree", "prune"))
                || arguments.equals(List.of("branch", "-D", worktree.generatedBranch())));
    }

    @Test
    void verifiesAndPushesARetainedBranchWithoutALiveWorktree()
            throws IOException
    {
        Path remote = temporaryDirectory.resolve("remote.git");
        run(temporaryDirectory, "init", "--bare", "--initial-branch=main", remote.toString());
        run(repository, "config", "url." + remote.toUri() + ".insteadOf", ORIGIN);
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        Files.writeString(worktree.canonicalWorktree().resolve("change.txt"), "change\n");
        run(worktree.canonicalWorktree(), "add", "change.txt");
        run(worktree.canonicalWorktree(), "commit", "-m", "Change");
        String expectedSha = run(worktree.canonicalWorktree(), "rev-parse", "HEAD").output().strip();
        service.cleanup(worktree);

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.gitAuthenticationEnvironment(anyString(), anyCollection()))
                .thenReturn(ImmutableMap.of());
        RemoteSourceStore remoteSources = mock(RemoteSourceStore.class);
        when(remoteSources.findByWorkspace(WORKSPACE_ID)).thenReturn(List.of());
        ManagedWorkspaceGit workspaceGit = new ManagedWorkspaceGit(paths, git, credentials, remoteSources);

        assertThatThrownBy(() -> workspaceGit.pushBranch(
                workspace(), worktree.generatedBranch(), startCommit))
                .isInstanceOf(ManagedWorkspaceGit.Failure.class)
                .hasMessageContaining("Generated branch does not match");
        WorkspaceBranchPusher.Push pushed = workspaceGit.pushBranch(
                workspace(), worktree.generatedBranch(), expectedSha);

        assertThat(worktree.canonicalWorktree()).doesNotExist();
        assertThat(pushed.sha()).isEqualTo(expectedSha);
        assertThat(run(remote, "rev-parse", "refs/heads/" + worktree.generatedBranch()).output().strip())
                .isEqualTo(expectedSha);
    }

    @Test
    void dirtyCleanupPreservesWorktreeAndBranch()
            throws IOException
    {
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        Path untracked = worktree.canonicalWorktree().resolve("local.txt");
        Files.writeString(untracked, "keep me\n");

        assertThatThrownBy(() -> service.cleanup(worktree))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("changes");

        assertThat(untracked).hasContent("keep me\n");
        assertThat(run(repository, "show-ref", "--verify", "refs/heads/" + worktree.generatedBranch()).successful())
                .isTrue();
    }

    @Test
    void ignoredFileAlsoBlocksCleanup()
            throws IOException
    {
        Path exclude = reportedPath(repository, "info/exclude");
        Files.writeString(exclude, Files.readString(exclude) + "*.ignored\n");
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        Path ignored = worktree.canonicalWorktree().resolve("local.ignored");
        Files.writeString(ignored, "keep me\n");

        assertThat(run(worktree.canonicalWorktree(), "status", "--porcelain=v1").output()).isBlank();
        assertThat(service.inspect(worktree).dirty()).isTrue();
        assertThatThrownBy(() -> service.cleanup(worktree))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("changes");

        assertThat(ignored).hasContent("keep me\n");
        assertThat(worktree.canonicalWorktree()).isDirectory();
    }

    @Test
    void openGitOperationBlocksCleanup()
            throws IOException
    {
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        Path mergeHead = reportedPath(worktree.canonicalWorktree(), "MERGE_HEAD");
        Files.writeString(mergeHead, startCommit + "\n");

        assertThat(service.inspect(worktree).gitOperationInProgress()).isTrue();
        assertThatThrownBy(() -> service.cleanup(worktree))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("open Git operation");
        assertThat(worktree.canonicalWorktree()).isDirectory();
    }

    @Test
    void ownershipMismatchIsPreservedAndReported()
    {
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        run(repository, "remote", "set-url", "origin", "https://github.com/other/project.git");

        assertThatThrownBy(() -> service.inspect(worktree))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("origin");
        assertThatThrownBy(() -> service.cleanup(worktree))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("origin");
        assertThat(worktree.canonicalWorktree()).isDirectory();
    }

    @Test
    void conflictingPathIsNotAdoptedOrRemoved()
            throws IOException
    {
        TaskId taskId = TaskId.random();
        Path conflict = paths.worktrees(WORKSPACE_ID).resolve(taskId.toString());
        Files.createDirectories(conflict);
        Path marker = conflict.resolve("owner.txt");
        Files.writeString(marker, "someone else\n");

        assertThatThrownBy(() -> service.provision(command(taskId)))
                .isInstanceOf(AgentConflictException.class);
        assertThat(marker).hasContent("someone else\n");
    }

    @Test
    void annotatedTagObjectIsNotAcceptedAsAStartCommit()
    {
        run(repository, "tag", "-a", "start-tag", "-m", "Start tag");
        String tagObject = run(repository, "rev-parse", "refs/tags/start-tag").output().strip();
        TaskId taskId = TaskId.random();

        assertThatThrownBy(() -> service.provision(
                new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, REPOSITORY, tagObject)))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("exact start commit");

        assertThat(paths.worktrees(WORKSPACE_ID).resolve(taskId.toString())).doesNotExist();
        assertThat(git.run(repository, "show-ref", "--verify", "--quiet", "refs/heads/bytequay/task/" + taskId).exitCode())
                .isEqualTo(1);
    }

    @Test
    void existingGeneratedBranchIsPreservedAndReported()
    {
        TaskId taskId = TaskId.random();
        String branch = "bytequay/task/" + taskId;
        run(repository, "branch", branch, startCommit);

        assertThatThrownBy(() -> service.provision(command(taskId)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("branch already exists");
        assertThat(run(repository, "rev-parse", branch).output().strip()).isEqualTo(startCommit);
        assertThat(paths.worktrees(WORKSPACE_ID).resolve(taskId.toString())).doesNotExist();
    }

    @Test
    void symbolicLinkAtDerivedPathIsPreservedAndReported()
            throws IOException
    {
        TaskId taskId = TaskId.random();
        Files.createDirectories(paths.worktrees(WORKSPACE_ID));
        Path link = paths.worktrees(WORKSPACE_ID).resolve(taskId.toString());
        Files.createSymbolicLink(link, repository);

        assertThatThrownBy(() -> service.provision(command(taskId)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("occupied");
        assertThat(link).isSymbolicLink();
    }

    @Test
    void loneStaleSequencerDirectoryDoesNotBlockCleanup()
            throws IOException
    {
        TaskWorktree worktree = service.provision(command(TaskId.random()));
        Files.createDirectories(reportedPath(worktree.canonicalWorktree(), "sequencer"));

        assertThat(service.inspect(worktree).gitOperationInProgress()).isFalse();
        service.cleanup(worktree);

        assertThat(worktree.canonicalWorktree()).doesNotExist();
    }

    @Test
    void linkedCheckoutCannotOwnManagedTaskWorktrees()
            throws IOException
    {
        String linkedWorkspaceId = "workspace-linked";
        WorkspacePaths linkedPaths = new WorkspacePaths(temporaryDirectory.resolve("linked-managed").toString());
        Path linkedRepository = linkedPaths.repository(linkedWorkspaceId);
        Files.createDirectories(linkedRepository.getParent());
        Files.writeString(
                linkedPaths.workspace(linkedWorkspaceId).resolve(ManagedWorkspaceGit.OWNERSHIP_FILE),
                linkedWorkspaceId + "\n1\n");
        run(repository, "worktree", "add", "--detach", linkedRepository.toString(), startCommit);
        WorkspaceStore linkedWorkspaces = mock(WorkspaceStore.class);
        when(linkedWorkspaces.findById(linkedWorkspaceId))
                .thenReturn(Optional.of(workspace(linkedWorkspaceId, linkedRepository)));
        ManagedWorktreeService linkedService = new ManagedWorktreeService(linkedPaths, linkedWorkspaces, git);

        assertThatThrownBy(() -> linkedService.provision(new ProvisionWorktreeCommand(
                TaskId.random(),
                linkedWorkspaceId,
                REPOSITORY,
                startCommit)))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("primary checkout");
    }

    private ProvisionWorktreeCommand command(TaskId taskId)
    {
        return new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, REPOSITORY, startCommit);
    }

    private Workspace workspace()
    {
        return workspace(WORKSPACE_ID, repository);
    }

    private Workspace workspace(String workspaceId, Path localRepository)
    {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        RepoRef selected = RepoRef.of(REPOSITORY.owner(), REPOSITORY.name());
        return new Workspace(
                workspaceId,
                "Acme project",
                1,
                selected,
                Optional.empty(),
                Optional.empty(),
                1,
                selected,
                Workspace.Topology.DIRECT,
                "main",
                ORIGIN,
                Optional.empty(),
                Optional.empty(),
                localRepository.toString(),
                Optional.of(now),
                Optional.of(now),
                0,
                now,
                now);
    }

    private Path reportedPath(Path worktree, String name)
    {
        Path reported = Path.of(run(worktree, "rev-parse", "--git-path", name).output().strip());
        return reported.isAbsolute() ? reported : worktree.resolve(reported).normalize();
    }

    private Result run(Path worktree, String... arguments)
    {
        Result result = git.run(worktree, arguments);
        assertThat(result.successful())
                .describedAs("git %s: %s", String.join(" ", arguments), result.output())
                .isTrue();
        return result;
    }

    private static final class RecordingGitCommandRunner
            extends GitCommandRunner
    {
        private final List<List<String>> arguments = new ArrayList<>();

        @Override
        public Result run(Path worktree, List<String> arguments)
        {
            this.arguments.add(List.copyOf(arguments));
            return super.run(worktree, arguments);
        }

        public List<List<String>> arguments()
        {
            return List.copyOf(arguments);
        }

        public void clear()
        {
            arguments.clear();
        }
    }
}
