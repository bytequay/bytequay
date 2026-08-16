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
package com.bytequay.agent.core;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitAgentWorkspaceService
{
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "sample");

    @TempDir
    private Path temporaryDirectory;

    private Path worktree;
    private GitAgentWorkspaceService service;

    @BeforeEach
    void setUp()
            throws IOException, InterruptedException
    {
        worktree = Files.createDirectory(temporaryDirectory.resolve("worktree"));
        runGit("init", "--initial-branch=main", worktree.toString());
        runGit("-C", worktree.toString(), "config", "user.name", "ByteQuay Test");
        runGit("-C", worktree.toString(), "config", "user.email", "test@bytequay.example");
        runGit("-C", worktree.toString(), "remote", "add", "origin", "https://github.com/bytequay/sample.git");
        Files.writeString(worktree.resolve("README.md"), "sample");
        runGit("-C", worktree.toString(), "add", "README.md");
        runGit("-C", worktree.toString(), "commit", "-m", "Initial commit");
        service = new GitAgentWorkspaceService();
    }

    @Test
    void inspectsAndExclusivelyLeasesCleanWorktree()
            throws IOException
    {
        WorkspaceSnapshot snapshot = service.inspect(worktree);
        assertThat(snapshot.repository()).isEqualTo(REPOSITORY);
        assertThat(snapshot.branch()).contains("main");
        assertThat(snapshot.dirty()).isFalse();

        TaskId firstTask = TaskId.random();
        WorkspaceLease lease = service.acquire(firstTask, worktree, REPOSITORY);
        assertThat(lease.worktree()).isEqualTo(worktree.toRealPath());
        assertThatThrownBy(() -> service.acquire(TaskId.random(), worktree, REPOSITORY))
                .isInstanceOf(AgentConflictException.class);

        lease.close();
        service.acquire(TaskId.random(), worktree, REPOSITORY).close();
        lease.close();
    }

    @Test
    void rejectsDirtyWorktreeButRestoresItsLease()
            throws IOException
    {
        Files.writeString(worktree.resolve("untracked.txt"), "dirty");

        assertThatThrownBy(() -> service.acquire(TaskId.random(), worktree, REPOSITORY))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("changes");

        service.restore(TaskId.random(), worktree, REPOSITORY).close();
    }

    @Test
    void rejectsRepositoryMismatchAndSymbolicLink()
            throws IOException
    {
        assertThatThrownBy(() -> service.acquire(
                TaskId.random(),
                worktree,
                new RepositoryIdentity("other", "repository")))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("does not match");

        Path link = temporaryDirectory.resolve("worktree-link");
        Files.createSymbolicLink(link, worktree);
        assertThatThrownBy(() -> service.inspect(link))
                .isInstanceOf(InvalidAgentRequestException.class)
                .hasMessageContaining("symbolic link");
    }

    private static void runGit(String... arguments)
            throws IOException, InterruptedException
    {
        List<String> command = ImmutableList.<String>builderWithExpectedSize(arguments.length + 1)
                .add("git")
                .add(arguments)
                .build();
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError("Git command failed: " + output);
        }
    }
}
