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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitRunnerUpstreamCommits
{
    private final GitRunner git = new GitRunner();

    @Test
    void readsTagsAndScopesPickedTrailersToTheTargetRevision(@TempDir Path root)
            throws Exception
    {
        Path upstream = root.resolve("upstream");
        Files.createDirectory(upstream);
        initialise(upstream);
        commit(upstream, "release.txt", "release 1", "Release one (#101)");
        run(upstream, "tag", "v1");
        commit(upstream, "feature.txt", "feature", "Add feature (#102)");
        String featureSha = output(upstream, "rev-parse", "HEAD");

        List<GitRunner.DecoratedCommitEntry> commits =
                git.listDecoratedCommits(upstream, "main", 10);

        assertThat(commits).extracting(GitRunner.DecoratedCommitEntry::subject)
                .containsExactly("Add feature (#102)", "Release one (#101)");
        assertThat(commits.get(1).tags()).containsExactly("v1");

        Path fork = root.resolve("fork");
        Files.createDirectory(fork);
        initialise(fork);
        commit(fork, "base.txt", "base", "Fork base");
        git.fetchObjects(fork, upstream, List.of(featureSha));
        Path worktree = root.resolve("pick-worktree");
        git.worktreeAdd(fork, worktree, "upstream-pick", "main");

        assertThat(git.cherryPick(worktree, List.of(featureSha), true).complete()).isTrue();
        git.worktreeRemove(fork, worktree);

        // git writes the provenance itself; nothing amends the message afterwards.
        assertThat(git.commitDetail(fork, "upstream-pick").orElseThrow().body())
                .contains("(cherry picked from commit " + featureSha + ")");
        assertThat(git.commitDetail(fork, "main").orElseThrow().body())
                .as("an abandoned topic branch must not touch the target branch")
                .doesNotContain("cherry picked from commit");
    }

    @Test
    void plainCherryPickLeavesNoProvenanceLine(@TempDir Path root)
            throws Exception
    {
        Path upstream = root.resolve("upstream");
        Files.createDirectory(upstream);
        initialise(upstream);
        commit(upstream, "a.txt", "a", "Base");
        commit(upstream, "b.txt", "b", "Feature (#102)");
        String featureSha = output(upstream, "rev-parse", "HEAD");
        Path fork = root.resolve("fork");
        Files.createDirectory(fork);
        initialise(fork);
        commit(fork, "base.txt", "base", "Fork base");
        git.fetchObjects(fork, upstream, List.of(featureSha));
        Path worktree = root.resolve("plain-worktree");
        git.worktreeAdd(fork, worktree, "plain-pick", "main");

        assertThat(git.cherryPick(worktree, List.of(featureSha)).complete()).isTrue();
        git.worktreeRemove(fork, worktree);

        assertThat(git.commitDetail(fork, "plain-pick").orElseThrow().body())
                .doesNotContain("cherry picked from commit");
    }

    private static void initialise(Path repo)
            throws IOException, InterruptedException
    {
        run(repo, "init", "-b", "main");
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
        Process process = process(repo, args).start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
    }

    private static String output(Path repo, String... args)
            throws IOException, InterruptedException
    {
        Process process = process(repo, args).start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
        return output.strip();
    }

    private static ProcessBuilder process(Path repo, String... args)
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true);
    }
}
