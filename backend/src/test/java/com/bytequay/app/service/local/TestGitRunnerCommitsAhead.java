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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GitRunner#listCommitsAhead} against a real temp repo —
 * the fix for the Commits panel showing the base branch's history. A task
 * branch cut from {@code main} must report only the commits IT added
 * ({@code base..HEAD}), never the commits already on {@code main}.
 */
class TestGitRunnerCommitsAhead
{
    private final GitRunner git = new GitRunner(ForkJoinPool.commonPool());

    @Test
    void listsOnlyCommitsAddedOnTopOfTheBaseBranch(@TempDir Path repo)
            throws Exception
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        commit(repo, "base.txt", "base commit on main");

        // Two commits on a task branch cut from main.
        git(repo, "checkout", "-b", "dev/test");
        commit(repo, "a.txt", "task: first change");
        commit(repo, "b.txt", "task: second change");

        List<GitRunner.CommitEntry> ahead = git.listCommitsAhead(repo, "main", 100);

        // Only the two task commits — NOT the base commit on main.
        assertThat(ahead).hasSize(2);
        assertThat(ahead).extracting(GitRunner.CommitEntry::subject)
                .containsExactly("task: second change", "task: first change");
    }

    @Test
    void returnsEmptyWhenTheBranchIsEvenWithItsBase(@TempDir Path repo)
            throws Exception
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        commit(repo, "base.txt", "base commit on main");

        // A branch cut from main with no new commits — what a freshly-cut,
        // never-run task looks like. The panel must show nothing.
        git(repo, "checkout", "-b", "dev/test");

        assertThat(git.listCommitsAhead(repo, "main", 100)).isEmpty();
    }

    @Test
    void returnsEmptyForABlankBase(@TempDir Path repo)
            throws Exception
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        commit(repo, "base.txt", "base commit on main");

        assertThat(git.listCommitsAhead(repo, "", 100)).isEmpty();
    }

    private static void commit(Path repo, String file, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), message);
        git(repo, "add", ".");
        git(repo, "commit", "-m", message);
    }

    private static void git(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + code + ") in " + repo);
        }
    }
}
