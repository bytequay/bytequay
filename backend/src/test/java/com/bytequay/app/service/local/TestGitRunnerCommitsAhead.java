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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GitRunner#listCommitsAhead} against a real temp repo —
 * the fix for the Commits panel showing the base branch's history. A task
 * branch cut from {@code main} must report only the commits IT added
 * ({@code base..HEAD}), never the commits already on {@code main}.
 */
class TestGitRunnerCommitsAhead
{
    private final GitRunner git = new GitRunner();

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

    /**
     * The empty-Commits-panel bug: a worktree whose base branch name does
     * not resolve to a local ref. Diffing from the named base fails and
     * drops every commit, but the merge-base SHA (how the task surface now
     * resolves the base) still lists exactly the task's commits.
     */
    @Test
    void diffsFromMergeBaseWhenTheNamedBaseRefIsMissing(@TempDir Path repo)
            throws Exception
    {
        // The repo's base branch is "work", not "main".
        git(repo, "init", "-b", "work");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        commit(repo, "base.txt", "starting point");

        // The task branch is cut from "work" and adds two commits.
        git(repo, "checkout", "-b", "dev/test");
        commit(repo, "a.txt", "task: first change");
        commit(repo, "b.txt", "task: second change");

        // There is no local "main" ref here — the old raw "main..HEAD" path
        // failed and returned nothing.
        assertThat(git.refExists(repo, "main")).isFalse();
        assertThat(git.refExists(repo, "work")).isTrue();

        // Resolving via the merge-base against the real branch lists exactly
        // the commits the task added.
        String mergeBase = git.mergeBase(repo, "HEAD", "work").orElseThrow();
        assertThat(git.listCommitsAhead(repo, mergeBase, 100)).hasSize(2);
    }

    @Test
    void effectiveFilesIncludesCommittedUncommittedAndUntracked(@TempDir Path repo)
            throws Exception
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        commit(repo, "base.txt", "base");

        // A task branch with one committed change, plus working-tree edits
        // the agent made but never committed.
        git(repo, "checkout", "-b", "dev/test");
        commit(repo, "committed.txt", "a committed change");
        Files.writeString(repo.resolve("base.txt"), "edited but not committed");
        Files.writeString(repo.resolve("untracked.txt"), "brand new file");

        List<GitRunner.CommitFileChange> files = git.effectiveFiles(repo, "main");

        // base → working tree: committed + uncommitted + untracked, all shown.
        assertThat(files).extracting(GitRunner.CommitFileChange::path)
                .containsExactlyInAnyOrder("committed.txt", "base.txt", "untracked.txt");

        // The untracked file renders as a full add via the --no-index fallback.
        String patch = git.effectiveFileDiff(repo, "main", "untracked.txt", 0);
        assertThat(patch).contains("new file").contains("+brand new file");
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
