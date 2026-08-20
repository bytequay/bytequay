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
package com.bytequay.app.git;

import com.bytequay.app.git.SequenceState.ConflictedPath;
import com.bytequay.app.git.SequenceState.Kind;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitSequencerDriver
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private GitSequencerDriver driver;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path created = Files.createDirectories(temporaryDirectory.resolve("repo"));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        git(created, "config", "user.name", "ByteQuay Test");
        git(created, "config", "user.email", "bytequay@example.com");
        repository = created.toRealPath();
        driver = new GitSequencerDriver(GIT);
    }

    @Test
    void appliesAWholeBatchInOneAction()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String target = head();

        git(repository, "checkout", "--quiet", "-b", "source");
        String first = change("one.txt", "First change");
        String second = change("two.txt", "Second change");
        String third = change("three.txt", "Third change");
        git(repository, "checkout", "--quiet", "main");

        SequenceState state = driver.beginCherryPick(
                repository, target, ImmutableList.of(first, second, third));

        assertThat(state.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "rev-list", "--count", target + "..HEAD")).isEqualTo("3");
        assertThat(git(repository, "log", "--format=%B", target + "..HEAD"))
                .contains("(cherry picked from commit " + first + ")")
                .contains("(cherry picked from commit " + second + ")")
                .contains("(cherry picked from commit " + third + ")");
    }

    @Test
    void stopsOnAConflictAndNamesTheCommitAndPaths()
    {
        write("file.txt", "one\ntwo\nthree\n");
        commit("Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String clean = change("clean.txt", "Clean change");
        write("file.txt", "one\nSOURCE\nthree\n");
        commit("Conflicting change");
        String conflicting = head();
        String later = change("later.txt", "Later change");

        git(repository, "checkout", "--quiet", "main");
        write("file.txt", "one\nTARGET\nthree\n");
        commit("Target change");
        String target = head();

        SequenceState state = driver.beginCherryPick(
                repository, target, ImmutableList.of(clean, conflicting, later));

        assertThat(state.kind()).isEqualTo(Kind.STOPPED_CONFLICT);
        assertThat(state.stoppedCommit()).contains(conflicting);
        assertThat(state.conflicts())
                .extracting(ConflictedPath::path)
                .containsExactly("file.txt");
        assertThat(state.conflicts().getFirst().type()).isEqualTo(ConflictedPath.Type.CONTENT);
        assertThat(state.remainingCommits()).contains(later);
        assertThat(state.head()).as("the clean commit before it landed").isNotEqualTo(target);
    }

    @Test
    void reportsAStagedResolutionAndResumesTheRestOfTheBatch()
    {
        write("file.txt", "one\ntwo\n");
        commit("Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        write("file.txt", "one\nSOURCE\n");
        commit("Conflicting change");
        String conflicting = head();
        String later = change("later.txt", "Later change");

        git(repository, "checkout", "--quiet", "main");
        write("file.txt", "one\nTARGET\n");
        commit("Target change");
        String target = head();

        driver.beginCherryPick(repository, target, ImmutableList.of(conflicting, later));
        write("file.txt", "one\nRESOLVED\n");
        git(repository, "add", "file.txt");

        assertThat(driver.observe(repository).kind()).isEqualTo(Kind.STOPPED_RESOLVED);

        SequenceState resumed = driver.continueSequence(repository, conflicting);
        assertThat(resumed.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "rev-list", "--count", target + "..HEAD")).isEqualTo("2");
        assertThat(git(repository, "log", "--format=%B", target + "..HEAD"))
                .as("the -x line survives conflict resolution")
                .contains("(cherry picked from commit " + conflicting + ")");
    }

    @Test
    void treatsBothKindsOfEmptyTheSameAndResumesAfterAGuardedSkip()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String base = head();

        git(repository, "checkout", "--quiet", "-b", "source");
        git(repository, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream");
        String emptyUpstream = head();
        write("shared.txt", "shared\n");
        commit("Redundant change");
        String redundant = head();
        String unique = change("unique.txt", "Unique change");

        git(repository, "checkout", "--quiet", "-B", "main", base);
        write("shared.txt", "shared\n");
        commit("Target already has it");
        String target = head();

        SequenceState first = driver.beginCherryPick(
                repository, target, ImmutableList.of(emptyUpstream, redundant, unique));
        assertThat(first.kind()).as("empty upstream").isEqualTo(Kind.STOPPED_EMPTY);
        assertThat(first.stoppedCommit()).contains(emptyUpstream);

        SequenceState second = driver.skipEmpty(repository, target, emptyUpstream);
        assertThat(second.kind()).as("target-redundant, stopped identically").isEqualTo(Kind.STOPPED_EMPTY);
        assertThat(second.stoppedCommit()).contains(redundant);

        SequenceState third = driver.skipEmpty(repository, target, redundant);
        assertThat(third.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "rev-list", "--count", target + "..HEAD"))
                .as("only the one commit with content lands")
                .isEqualTo("1");
    }

    @Test
    void refusesToSkipWhenAnythingDisagrees()
    {
        write("seed.txt", "seed\n");
        commit("Base");
        String base = head();

        git(repository, "checkout", "--quiet", "-b", "source");
        git(repository, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream");
        String empty = head();

        git(repository, "checkout", "--quiet", "-B", "main", base);
        String target = head();
        driver.beginCherryPick(repository, target, ImmutableList.of(empty));

        assertThat(driver.skipEmpty(repository, "0".repeat(40), empty).kind())
                .as("wrong expected HEAD")
                .isEqualTo(Kind.UNSAFE);
        assertThat(driver.skipEmpty(repository, target, "0".repeat(40)).kind())
                .as("wrong stopped commit")
                .isEqualTo(Kind.UNSAFE);
        assertThat(driver.skipEmpty(repository, target, empty).kind()).isEqualTo(Kind.COMPLETED);
    }

    @Test
    void refusesToBeginWhenAnOperationIsAlreadyOpenOrHeadMoved()
    {
        write("file.txt", "one\n");
        commit("Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        write("file.txt", "source\n");
        commit("Source change");
        String source = head();

        git(repository, "checkout", "--quiet", "main");
        write("file.txt", "target\n");
        commit("Target change");
        String target = head();

        assertThat(driver.beginCherryPick(repository, "0".repeat(40), ImmutableList.of(source)).kind())
                .as("HEAD is not what the caller expected")
                .isEqualTo(Kind.UNSAFE);

        driver.beginCherryPick(repository, target, ImmutableList.of(source));
        assertThat(driver.observe(repository).kind()).isEqualTo(Kind.STOPPED_CONFLICT);
        assertThat(driver.beginCherryPick(repository, target, ImmutableList.of(source)).kind())
                .as("an operation is already open")
                .isEqualTo(Kind.UNSAFE);

        assertThat(driver.abort(repository, target).kind()).isEqualTo(Kind.COMPLETED);
        assertThat(head()).isEqualTo(target);
    }

    @Test
    void reportsADirtyWorktreeWithNoOperationAsUnsafe()
    {
        write("file.txt", "one\n");
        commit("Base");
        write("file.txt", "edited outside ByteQuay\n");

        SequenceState state = driver.observe(repository);
        assertThat(state.kind()).isEqualTo(Kind.UNSAFE);
        assertThat(driver.cleanAndResolved(repository)).isFalse();
    }

    @Test
    void failsOnlyOnLeftoverMarkersNotOnWhitespaceTheAuthorWrote()
    {
        write("file.txt", "one\n");
        commit("Base");
        String before = head();

        write("file.txt", "one\ntrailing space here   \n\n");
        commit("Faithful copy of untidy upstream content");
        assertThat(driver.withoutConflictMarkers(repository, before, head()))
                .as("upstream whitespace must not fail a faithful pick")
                .isTrue();

        String beforeMarkers = head();
        write("file.txt", "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> other\n");
        commit("Left a resolution unfinished");
        assertThat(driver.withoutConflictMarkers(repository, beforeMarkers, head())).isFalse();
    }

    private String change(String name, String subject)
    {
        write(name, subject + "\n");
        commit(subject);
        return head();
    }

    private void write(String name, String content)
    {
        try {
            Files.writeString(repository.resolve(name), content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void commit(String subject)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", subject);
    }

    private String head()
    {
        return git(repository, "rev-parse", "HEAD");
    }

    private static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isZero();
        return result.output().strip();
    }
}
