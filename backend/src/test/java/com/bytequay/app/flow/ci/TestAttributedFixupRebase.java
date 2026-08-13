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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.AttributedFixupRebase.Boundary;
import com.bytequay.app.flow.ci.AttributedFixupRebase.BoundaryKind;
import com.bytequay.app.flow.ci.AttributedFixupRebase.FailureCode;
import com.bytequay.app.flow.ci.AttributedFixupRebase.RebaseFailure;
import com.bytequay.app.flow.ci.AttributedFixupRebase.SeriesCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the real rewrite against a real repository. The mechanics are the
 * riskiest part of attributed placement, so nothing here is faked: the commits,
 * the rebase, and the conflicts are Git's own.
 */
class TestAttributedFixupRebase
{
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    private Path temporaryDirectory;

    private Path worktree;
    private String base;
    private final AttributedFixupRebase rebase = new AttributedFixupRebase();

    @BeforeEach
    void setUp()
            throws Exception
    {
        worktree = temporaryDirectory.resolve("repository");
        Files.createDirectories(worktree);
        git("init", "--quiet", "--initial-branch=main", ".");
        git("config", "user.email", "ci@example.invalid");
        git("config", "user.name", "ByteQuay Test");
        git("config", "commit.gpgsign", "false");
        commit("base.txt", "base", "base");
        base = head();
    }

    @Test
    void planLeavesAnAlreadyPositionedSeriesAlone()
    {
        var plan = AttributedFixupRebase.plan(List.of(
                new SeriesCommit("a".repeat(40), "pick one"),
                new SeriesCommit("b".repeat(40), "fixup! pick one"),
                new SeriesCommit("c".repeat(40), "pick two")));

        assertThat(plan.identity()).isTrue();
        assertThat(plan.unattributedFixupShas()).isEmpty();
    }

    @Test
    void planRefusesToGuessAnAmbiguousOrUnknownTarget()
    {
        var plan = AttributedFixupRebase.plan(List.of(
                new SeriesCommit("a".repeat(40), "same"),
                new SeriesCommit("b".repeat(40), "same"),
                new SeriesCommit("c".repeat(40), "fixup! same"),
                new SeriesCommit("d".repeat(40), "fixup! never committed")));

        assertThat(plan.unattributedFixupShas())
                .containsExactly("c".repeat(40), "d".repeat(40));
        assertThat(plan.todo()).containsExactly(
                "pick " + "a".repeat(40),
                "pick " + "b".repeat(40),
                "pick " + "c".repeat(40),
                "pick " + "d".repeat(40));
        assertThat(plan.identity()).isTrue();
    }

    @Test
    void aFixupIsMovedBehindItsTargetWhichIsNotRewritten()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        String target = head();
        commit("two.txt", "two", "pick two");
        commit("fix.txt", "fix", "fixup! pick one");

        var rewrite = rebase.reposition(worktree, base, head(), TIMEOUT);

        assertThat(rewrite.outputHead()).isNotEqualTo(rewrite.inputHead());
        assertThat(rewrite.unattributedFixupShas()).isEmpty();
        assertThat(subjects()).containsExactly(
                "pick one", "fixup! pick one", "pick two");
        // The target keeps its exact identity, so it stays comparable to the
        // upstream commit it was picked from.
        assertThat(shas()).element(0).isEqualTo(target);
        assertThat(worktreeIsClean()).isTrue();
    }

    @Test
    void aSecondRepairMergesIntoTheTargetsExistingFixup()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        String target = head();
        commit("fix.txt", "first", "fixup! pick one");
        commit("two.txt", "two", "pick two");
        commit("later.txt", "second", "fixup! pick one");

        rebase.reposition(worktree, base, head(), TIMEOUT);

        assertThat(subjects()).containsExactly(
                "pick one", "fixup! pick one", "pick two");
        assertThat(shas()).element(0).isEqualTo(target);
        assertThat(filesIn(shas().get(1)))
                .containsExactly("fix.txt", "later.txt");
    }

    @Test
    void anUnattributableFixupStaysAPlainTipCommit()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        commit("two.txt", "two", "pick two");
        commit("fix.txt", "fix", "fixup! never committed");

        var rewrite = rebase.reposition(worktree, base, head(), TIMEOUT);

        assertThat(rewrite.outputHead()).isEqualTo(rewrite.inputHead());
        assertThat(rewrite.unattributedFixupShas()).hasSize(1);
        assertThat(subjects()).containsExactly(
                "pick one", "pick two", "fixup! never committed");
    }

    @Test
    void anAlreadyPositionedSeriesIsNotTouchedAtAll()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        commit("fix.txt", "fix", "fixup! pick one");
        commit("two.txt", "two", "pick two");
        String before = head();

        var rewrite = rebase.reposition(worktree, base, before, TIMEOUT);

        assertThat(rewrite.outputHead()).isEqualTo(before);
        assertThat(shas().get(0)).isEqualTo(
                git("rev-list", "--reverse", base + "..HEAD").split("\n")[0]);
    }

    @Test
    void aRepairThatCannotBeMovedRestoresTheHeadItStartedFrom()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        commit("two.txt", "two", "pick two");
        // Repairs a file that does not exist until "pick two", so it cannot be
        // replayed behind "pick one".
        commit("two.txt", "two repaired", "fixup! pick one");
        String before = head();

        assertThatThrownBy(() -> rebase.reposition(
                worktree, base, before, TIMEOUT))
                .isInstanceOf(RebaseFailure.class)
                .satisfies(failure -> assertThat(
                        ((RebaseFailure) failure).code())
                        .isEqualTo(FailureCode.REBASE_FAILED));

        assertThat(head()).isEqualTo(before);
        assertThat(worktreeIsClean()).isTrue();
        assertThat(git("status", "--porcelain=v1", "--branch"))
                .doesNotContain("REBASE");
    }

    @Test
    void aDirtyWorktreeIsNeverRewritten()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        commit("fix.txt", "fix", "fixup! pick one");
        commit("two.txt", "two", "pick two");
        String before = head();
        Files.writeString(worktree.resolve("one.txt"), "edited");

        assertThatThrownBy(() -> rebase.reposition(
                worktree, base, before, TIMEOUT))
                .isInstanceOf(RebaseFailure.class)
                .satisfies(failure -> assertThat(
                        ((RebaseFailure) failure).code())
                        .isEqualTo(FailureCode.DIRTY_WORKTREE));
        assertThat(head()).isEqualTo(before);
    }

    @Test
    void aMovedHeadIsRefusedRatherThanRewritten()
            throws Exception
    {
        commit("one.txt", "one", "pick one");
        String stale = head();
        commit("two.txt", "two", "pick two");

        assertThatThrownBy(() -> rebase.reposition(
                worktree, base, stale, TIMEOUT))
                .isInstanceOf(RebaseFailure.class)
                .satisfies(failure -> assertThat(
                        ((RebaseFailure) failure).code())
                        .isEqualTo(FailureCode.HEAD_MOVED));
    }

    @Test
    void aBareTargetFollowedByItsFixupIsNotABoundary()
    {
        var boundaries = AttributedFixupRebase.boundaries(List.of(
                new SeriesCommit("a".repeat(40), "pick one"),
                new SeriesCommit("b".repeat(40), "fixup! pick one"),
                new SeriesCommit("c".repeat(40), "pick two"),
                new SeriesCommit("d".repeat(40), "fixup! unknown")));

        assertThat(boundaries).containsExactly(
                new Boundary("b".repeat(40), BoundaryKind.TARGET_WITH_FIXUP),
                new Boundary("c".repeat(40), BoundaryKind.PLAIN),
                new Boundary("d".repeat(40), BoundaryKind.FIXUP));
    }

    @Test
    void boundaryBuildsProveTheTargetPlusFixupWithoutBuildingTheBareTarget()
            throws Exception
    {
        commit("state.txt", "broken", "pick one");
        String bareTarget = head();
        commit("state.txt", "compiles", "fixup! pick one");
        commit("two.txt", "two", "pick two");
        String before = head();

        var outcomes = rebase.proveBoundaries(
                worktree, base, before, compiles(), TIMEOUT);

        assertThat(head()).isEqualTo(before);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).allMatch(outcome -> outcome.passed());
        assertThat(outcomes.get(0).kind())
                .isEqualTo(BoundaryKind.TARGET_WITH_FIXUP);
        assertThat(outcomes).noneMatch(
                outcome -> outcome.commitSha().equals(bareTarget));
        assertThat(outcomes.get(0).evidenceRef()).startsWith("sha256:");
    }

    @Test
    void aRedBoundaryDoesNotHideTheBoundariesBehindIt()
            throws Exception
    {
        commit("state.txt", "broken", "pick one");
        commit("two.txt", "two", "pick two");
        commit("state.txt", "compiles", "pick three");
        String before = head();

        var outcomes = rebase.proveBoundaries(
                worktree, base, before, compiles(), TIMEOUT);

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.stream().map(outcome -> outcome.passed()))
                .containsExactly(false, false, true);
        assertThat(head()).isEqualTo(before);
        assertThat(worktreeIsClean()).isTrue();
    }

    private static List<String> compiles()
    {
        return List.of("/usr/bin/grep", "-q", "compiles", "state.txt");
    }

    private boolean worktreeIsClean()
            throws Exception
    {
        return git("status", "--porcelain=v1").isBlank();
    }

    private List<String> subjects()
            throws Exception
    {
        return List.of(git(
                "log", "--reverse", "--format=%s", base + "..HEAD")
                .split("\n"));
    }

    private List<String> shas()
            throws Exception
    {
        return List.of(git(
                "log", "--reverse", "--format=%H", base + "..HEAD")
                .split("\n"));
    }

    private List<String> filesIn(String sha)
            throws Exception
    {
        return List.of(git(
                "show", "--name-only", "--format=", sha).trim().split("\n"));
    }

    private String head()
            throws Exception
    {
        return git("rev-parse", "HEAD");
    }

    private void commit(String file, String content, String subject)
            throws Exception
    {
        Files.writeString(
                worktree.resolve(file), content + "\n", StandardCharsets.UTF_8);
        git("add", file);
        git("commit", "--quiet", "--message", subject);
    }

    private String git(String... arguments)
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(worktree.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of(
                "LC_ALL", "C", "GIT_TERMINAL_PROMPT", "0"));
        Process process = builder.start();
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue())
                .withFailMessage("git %s failed: %s",
                        List.of(arguments), output)
                .isZero();
        return output.trim();
    }
}
