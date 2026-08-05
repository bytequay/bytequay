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
package com.bytequay.app.service.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fork clone reads two remotes whose default branches disagree —
 * origin (the fork) on {@code main}, upstream on {@code master}. That
 * disagreement is exactly what the Commits tab and the workspace's base
 * branch have to get right.
 */
class TestGitRunnerRemoteBranches
{
    private final GitRunner git = new GitRunner();

    @Test
    void listsUpstreamBranchesWithoutItsSymbolicHead(@TempDir Path root)
            throws Exception
    {
        Path upstream = root.resolve("upstream");
        Files.createDirectory(upstream);
        initialise(upstream, "master");
        commit(upstream, "a.txt", "a", "Upstream base");
        run(upstream, "branch", "release-1");

        Path forkOrigin = root.resolve("fork-origin");
        Files.createDirectory(forkOrigin);
        initialise(forkOrigin, "main");
        commit(forkOrigin, "b.txt", "b", "Fork base");

        Path clone = root.resolve("clone");
        run(root, "clone", forkOrigin.toString(), clone.toString());
        git.addRemote(clone, "upstream", upstream.toString());
        git.fetchRemote(clone, "upstream");
        git.setRemoteHead(clone, "upstream");

        assertThat(git.listRemoteBranches(clone, "upstream"))
                .as("the remote's symbolic HEAD aliases a listed branch, "
                        + "so offering it as a branch would be a duplicate")
                .containsExactlyInAnyOrder("upstream/master", "upstream/release-1");
        assertThat(git.defaultBranch(clone, "upstream")).contains("master");
        assertThat(git.defaultBranch(clone))
                .as("origin is the fork, whose own default has drifted")
                .contains("main");
    }

    private static void initialise(Path repo, String branch)
            throws IOException, InterruptedException
    {
        run(repo, "init", "-b", branch);
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
    }

    private static void commit(Path repo, String file, String content, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), content);
        run(repo, "add", file);
        run(repo, "commit", "-m", message);
    }

    private static void run(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
