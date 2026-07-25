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
package com.bytequay.app.service.harness;

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestHarnessWorktreeProvisioner
{
    @Test
    void manualWatchGetsFreshDetachedWorktreeWithoutChangingMainCheckout(@TempDir Path root)
            throws Exception
    {
        GitRunner git = new GitRunner();
        if (!git.isAvailable()) {
            return;
        }
        Path remote = root.resolve("origin.git");
        Path seed = Files.createDirectory(root.resolve("seed"));
        run(root, "init", "--bare", remote.toString());
        initialise(seed);
        commit(seed, "tracked.txt", "base", "Base");
        String mainHead = output(seed, "rev-parse", "HEAD");
        run(seed, "remote", "add", "origin", remote.toString());
        run(seed, "push", "-u", "origin", "main");
        run(seed, "switch", "-c", "feature");
        commit(seed, "feature.txt", "first", "Feature");
        run(seed, "push", "-u", "origin", "feature");

        Path main = root.resolve("clone");
        run(root, "clone", "-b", "main", remote.toString(), main.toString());
        commit(seed, "feature.txt", "fresh", "Fresh remote head");
        String expectedHead = output(seed, "rev-parse", "HEAD");
        run(seed, "push", "origin", "feature");

        Files.writeString(main.resolve("tracked.txt"), "user's uncommitted edit");
        String statusBefore = output(main, "status", "--porcelain=v1");
        WatchedRepoStore watchedRepos = watched(main);
        HarnessWorktreeProvisioner provisioner =
                new HarnessWorktreeProvisioner(watchedRepos, git);

        Path prepared = provisioner.prepare(
                "acme", "widget", main.toString(), "watch-1", "feature");
        Path recovered = provisioner.prepare(
                "acme", "widget", main.toString(), "watch-1", "feature");

        assertThat(prepared).isEqualTo(root.resolve(
                "clone.bytequay-worktrees/ci-harness/watch-1").toRealPath());
        assertThat(recovered).isEqualTo(prepared);
        assertThat(prepared).isNotEqualTo(main);
        assertThat(output(prepared, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("HEAD");
        assertThat(output(prepared, "rev-parse", "HEAD")).isEqualTo(expectedHead);
        assertThat(output(prepared, "status", "--porcelain=v1")).isEmpty();
        assertThat(output(main, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");
        assertThat(output(main, "rev-parse", "HEAD")).isEqualTo(mainHead);
        assertThat(output(main, "status", "--porcelain=v1")).isEqualTo(statusBefore);

        Files.writeString(prepared.resolve("unexpected.txt"), "do not adopt");
        assertThatThrownBy(() -> provisioner.prepare(
                "acme", "widget", main.toString(), "watch-1", "feature"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be safely adopted");
        assertThat(prepared.resolve("unexpected.txt")).exists();
    }

    @Test
    void existingCherryPickWorktreePassesThroughWithoutGitMutation(@TempDir Path root)
            throws Exception
    {
        Path main = Files.createDirectory(root.resolve("clone"));
        Files.createDirectory(main.resolve(".git"));
        Path worktree = Files.createDirectories(root.resolve(
                "clone.bytequay-worktrees/upstream-cherry-pick/job-1"));
        Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere");
        GitRunner git = mock(GitRunner.class);
        HarnessWorktreeProvisioner provisioner =
                new HarnessWorktreeProvisioner(watched(main), git);

        Path prepared = provisioner.prepare(
                "acme", "widget", worktree.toString(), "watch-1", "feature");

        assertThat(prepared).isEqualTo(worktree.toRealPath());
        verifyNoInteractions(git);
    }

    @Test
    void rejectsNestedOrSymlinkedPathsInsideAppWorktreeRoot(@TempDir Path root)
            throws Exception
    {
        Path main = Files.createDirectory(root.resolve("clone"));
        Files.createDirectory(main.resolve(".git"));
        Path appRoot = root.resolve("clone.bytequay-worktrees");
        Path nested = Files.createDirectories(appRoot.resolve("cherry-pick/job-1/nested"));
        Files.writeString(nested.resolve(".git"), "gitdir: elsewhere");
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve(".git"), "gitdir: elsewhere");
        Path symlink = appRoot.resolve("ci-harness/watch-1");
        Files.createDirectories(symlink.getParent());
        Files.createSymbolicLink(symlink, outside);
        HarnessWorktreeProvisioner provisioner =
                new HarnessWorktreeProvisioner(watched(main), mock(GitRunner.class));

        assertThatThrownBy(() -> provisioner.prepare(
                "acme", "widget", nested.toString(), "watch-1", "feature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact app-owned");
        assertThatThrownBy(() -> provisioner.prepare(
                "acme", "widget", symlink.toString(), "watch-1", "feature"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    private static WatchedRepoStore watched(Path main)
    {
        WatchedRepoStore store = mock(WatchedRepoStore.class);
        when(store.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0, main.toString(), null, null)));
        return store;
    }

    private static void initialise(Path repo)
            throws IOException, InterruptedException
    {
        run(repo, "init", "-b", "main");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
    }

    private static void commit(
            Path repo,
            String file,
            String content,
            String message)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), content);
        run(repo, "add", file);
        run(repo, "commit", "-m", message);
    }

    private static void run(Path repo, String... args)
            throws IOException, InterruptedException
    {
        output(repo, args);
    }

    private static String output(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(
                    String.join(" ", command) + " failed (" + exit + "): " + output);
        }
        return output.strip();
    }
}
