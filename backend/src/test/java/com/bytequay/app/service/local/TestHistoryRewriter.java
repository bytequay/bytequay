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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link HistoryRewriter} against real temp repos. Every case
 * here is a staged edit the Commits editor can queue — reorder, squash,
 * reword — plus the rollback the editor promises when a rebase can't
 * finish.
 */
class TestHistoryRewriter
{
    private final GitRunner git = new GitRunner();
    private final HistoryRewriter rewriter = new HistoryRewriter(git);

    @Test
    void reordersTwoCommitsInOneRebase(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        commit(repo, "b.txt", "second");

        List<String> shas = shas(repo);
        // shas are newest-first: [second, first, base].
        assertThat(rewriter.rewrite(repo, new HistoryRewriter.RewritePlan(
                "main",
                shas.get(2),
                // oldest first: swap the two commits above base.
                List.of(entry(shas.get(0)), entry(shas.get(1))),
                false)).pushed()).isFalse();

        assertThat(subjects(repo)).containsExactly("first", "second", "base");
    }

    @Test
    void squashesThreeCommitsIntoOneWithTheGivenMessage(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        commit(repo, "b.txt", "wip");
        commit(repo, "c.txt", "fixup: typo");

        List<String> shas = shas(repo);
        assertThat(rewriter.rewrite(repo, new HistoryRewriter.RewritePlan(
                "main",
                shas.get(3),
                List.of(new HistoryRewriter.RewriteEntry(
                        // oldest first — the change lands at "first"'s position.
                        List.of(shas.get(2), shas.get(1), shas.get(0)),
                        "Add a, b and c\n\nCombined from three commits.")),
                false)).headSha()).isNotBlank();

        assertThat(subjects(repo)).containsExactly("Add a, b and c", "base");
        // All three commits' content survives the fold.
        assertThat(repo.resolve("a.txt")).exists();
        assertThat(repo.resolve("b.txt")).exists();
        assertThat(repo.resolve("c.txt")).exists();
        assertThat(git.commitDetail(repo, "HEAD").orElseThrow().body())
                .contains("Combined from three commits.");
    }

    @Test
    void rewordsOneCommitAndLeavesTheRestAlone(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "frist");
        commit(repo, "b.txt", "second");

        List<String> shas = shas(repo);
        assertThat(rewriter.rewrite(repo, new HistoryRewriter.RewritePlan(
                "main",
                shas.get(2),
                List.of(
                        new HistoryRewriter.RewriteEntry(List.of(shas.get(1)), "first"),
                        entry(shas.get(0))),
                false)).pushError()).isNull();

        assertThat(subjects(repo)).containsExactly("second", "first", "base");
    }

    /**
     * The atomicity promise: a todo git can't carry out leaves the
     * branch exactly where it started, so the editor's pending queue is
     * still valid to retry.
     */
    @Test
    void restoresTheOriginalHeadWhenTheRebaseFails(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        commit(repo, "b.txt", "second");

        List<String> shas = shas(repo);
        String before = git.headSha(repo);

        assertThatThrownBy(() -> rewriter.rewrite(repo, new HistoryRewriter.RewritePlan(
                "main",
                shas.get(2),
                // A sha that isn't in this repo at all — git rejects the todo.
                List.of(entry("0123456789abcdef0123456789abcdef01234567")),
                false)))
                .isInstanceOf(HistoryRewriter.RewriteFailedException.class);

        assertThat(git.headSha(repo)).isEqualTo(before);
        assertThat(subjects(repo)).containsExactly("second", "first", "base");
    }

    @Test
    void refusesToRewriteABranchThatIsNotCheckedOut(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        List<String> shas = shas(repo);

        assertThatThrownBy(() -> rewriter.rewrite(repo, new HistoryRewriter.RewritePlan(
                "some-other-branch", shas.get(1), List.of(entry(shas.get(0))), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checked-out branch");
    }

    @Test
    void refusesToRewriteOverADirtyWorkingTree(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        List<String> shas = shas(repo);
        Files.writeString(repo.resolve("base.txt"), "uncommitted edit");

        assertThatThrownBy(() -> rewriter.rewrite(repo, new HistoryRewriter.RewritePlan(
                "main", shas.get(1), List.of(entry(shas.get(0))), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stash");
    }

    /** Line stats and the pushed flag — what the editor's rows render. */
    @Test
    void reportsPerCommitLineStatsAndWhichCommitsTheRemoteHas(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        // Pretend everything so far is published.
        git(repo, "update-ref", "refs/remotes/origin/main", "main");
        commit(repo, "b.txt", "second");

        List<String> shas = shas(repo);
        assertThat(git.unpushedShas(repo, "main", "origin/main"))
                .containsExactly(shas.get(0));
        assertThat(git.commitLineStats(repo, "main", 100, 0).get(shas.get(0)))
                .isEqualTo(new GitRunner.LineStats(1, 0));
        assertThat(git.trackingRef(repo, "main")).contains("origin/main");
    }

    /** A remote-only branch has no local commits — its whole history is
     *  by definition published, so the editor shows no LOCAL group. */
    @Test
    void treatsARemoteOnlyBranchAsFullyPushed(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        git(repo, "update-ref", "refs/remotes/origin/feature", "main");

        assertThat(git.refExists(repo, "refs/remotes/origin/feature")).isTrue();
        assertThat(git.unpushedShas(repo, "origin/feature", "origin/feature")).isEmpty();
    }

    /** Paging: skip steps over the newest commits so the list can append
     *  older history without refetching what it already has. */
    @Test
    void pagesBackwardsWithSkip(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");
        commit(repo, "b.txt", "second");

        assertThat(git.listCommits(repo, "HEAD", 2, 0)).extracting(GitRunner.CommitEntry::subject)
                .containsExactly("second", "first");
        assertThat(git.listCommits(repo, "HEAD", 2, 2)).extracting(GitRunner.CommitEntry::subject)
                .containsExactly("base");
        assertThat(git.commitBodies(repo, "HEAD", 2, 2)).hasSize(1);
        assertThat(git.commitLineStats(repo, "HEAD", 2, 2)).hasSize(1);
    }

    @Test
    void treatsEveryCommitAsUnpushedWhenTheBranchHasNoRemote(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base");
        commit(repo, "a.txt", "first");

        assertThat(git.trackingRef(repo, "main")).isEmpty();
        assertThat(git.unpushedShas(repo, "main", null)).hasSize(2);
    }

    private static HistoryRewriter.RewriteEntry entry(String sha)
    {
        return new HistoryRewriter.RewriteEntry(List.of(sha), null);
    }

    private List<String> shas(Path repo)
            throws IOException, InterruptedException
    {
        return git.listCommits(repo, "HEAD", 100).stream()
                .map(GitRunner.CommitEntry::sha)
                .toList();
    }

    private List<String> subjects(Path repo)
            throws IOException, InterruptedException
    {
        return git.listCommits(repo, "HEAD", 100).stream()
                .map(GitRunner.CommitEntry::subject)
                .toList();
    }

    private static void init(Path repo)
            throws IOException, InterruptedException
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "commit.gpgsign", "false");
    }

    private static void commit(Path repo, String file, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), message + "\n");
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
