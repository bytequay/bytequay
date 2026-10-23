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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.SequenceState;
import com.bytequay.app.git.command.SequenceState.ConflictedPath;
import com.bytequay.app.git.command.SequenceState.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.bytequay.app.git.GitTestSupport.commit;
import static com.bytequay.app.git.GitTestSupport.commitFile;
import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.head;
import static com.bytequay.app.git.GitTestSupport.initRepository;
import static com.bytequay.app.git.GitTestSupport.write;
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
        repository = initRepository(created);
        driver = new GitSequencerDriver(GIT);
    }

    @Test
    void appliesOneSourceCommit()
    {
        String target = commitFile(repository, "seed.txt", "seed\n", "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String first = change("one.txt", "First change");
        git(repository, "checkout", "--quiet", "main");

        SequenceState state = driver.beginCherryPick(repository, target, first);

        assertThat(state.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "rev-list", "--count", target + "..HEAD")).isEqualTo("1");
        assertThat(git(repository, "log", "--format=%B", target + "..HEAD"))
                .contains("(cherry picked from commit " + first + ")");
    }

    @Test
    void stopsOnAConflictAndNamesTheCommitAndPaths()
    {
        write(repository, "file.txt", "one\ntwo\nthree\n");
        commit(repository, "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String conflicting = commitFile(repository, "file.txt", "one\nSOURCE\nthree\n", "Conflicting change");

        git(repository, "checkout", "--quiet", "main");
        String target = commitFile(repository, "file.txt", "one\nTARGET\nthree\n", "Target change");

        SequenceState state = driver.beginCherryPick(repository, target, conflicting);

        assertThat(state.kind()).isEqualTo(Kind.STOPPED_CONFLICT);
        assertThat(state.stoppedCommit()).contains(conflicting);
        assertThat(state.conflicts())
                .extracting(ConflictedPath::path)
                .containsExactly("file.txt");
        assertThat(state.conflicts().getFirst().type()).isEqualTo(ConflictedPath.Type.CONTENT);
        assertThat(state.conflicts().getFirst().stages())
                .extracting(ConflictedPath.IndexStage::stage)
                .containsExactly(1, 2, 3);
        assertThat(state.conflicts().getFirst().stages())
                .allSatisfy(stage -> {
                    assertThat(stage.mode()).matches("[0-7]{6}");
                    assertThat(stage.blobSha()).matches("[0-9a-f]{40}|[0-9a-f]{64}");
                });
        assertThat(state.head()).isEqualTo(target);
    }

    @Test
    void treatsBothKindsOfEmptyTheSameAndSkipsOneAtATime()
    {
        String base = commitFile(repository, "seed.txt", "seed\n", "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        git(repository, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream");
        String emptyUpstream = head(repository);
        String redundant = commitFile(repository, "shared.txt", "shared\n", "Redundant change");
        String unique = change("unique.txt", "Unique change");

        git(repository, "checkout", "--quiet", "-B", "main", base);
        String target = commitFile(repository, "shared.txt", "shared\n", "Target already has it");

        SequenceState first = driver.beginCherryPick(repository, target, emptyUpstream);
        assertThat(first.kind()).as("empty upstream").isEqualTo(Kind.STOPPED_EMPTY);
        assertThat(first.stoppedCommit()).contains(emptyUpstream);

        SequenceState second = driver.skipEmpty(repository, target, emptyUpstream);
        assertThat(second.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(second.head()).isEqualTo(target);

        SequenceState third = driver.beginCherryPick(repository, target, redundant);
        assertThat(third.kind()).as("target-redundant").isEqualTo(Kind.STOPPED_EMPTY);
        SequenceState fourth = driver.skipEmpty(repository, target, redundant);
        assertThat(fourth.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(fourth.head()).isEqualTo(target);

        SequenceState fifth = driver.beginCherryPick(repository, target, unique);
        assertThat(fifth.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "rev-list", "--count", target + "..HEAD"))
                .as("only the one commit with content lands")
                .isEqualTo("1");
    }

    @Test
    void refusesToSkipWhenAnythingDisagrees()
    {
        String base = commitFile(repository, "seed.txt", "seed\n", "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        git(repository, "commit", "--quiet", "--allow-empty", "-m", "Empty upstream");
        String empty = head(repository);

        git(repository, "checkout", "--quiet", "-B", "main", base);
        String target = head(repository);
        driver.beginCherryPick(repository, target, empty);

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
        write(repository, "file.txt", "one\n");
        commit(repository, "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String source = commitFile(repository, "file.txt", "source\n", "Source change");

        git(repository, "checkout", "--quiet", "main");
        String target = commitFile(repository, "file.txt", "target\n", "Target change");

        assertThat(driver.beginCherryPick(repository, "0".repeat(40), source).kind())
                .as("HEAD is not what the caller expected")
                .isEqualTo(Kind.UNSAFE);

        driver.beginCherryPick(repository, target, source);
        assertThat(driver.observe(repository).kind()).isEqualTo(Kind.STOPPED_CONFLICT);
        assertThat(driver.beginCherryPick(repository, target, source).kind())
                .as("an operation is already open")
                .isEqualTo(Kind.UNSAFE);
    }

    @Test
    void reportsADirtyWorktreeWithNoOperationAsUnsafe()
    {
        write(repository, "file.txt", "one\n");
        commit(repository, "Base");
        write(repository, "file.txt", "edited outside ByteQuay\n");

        SequenceState state = driver.observe(repository);
        assertThat(state.kind()).isEqualTo(Kind.UNSAFE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file.txt", "报告.txt", "quoted\"file.txt", "tab\tfile.txt", "line\nfile.txt"})
    void commitsTheConflictSnapshotWithItsCherryPickOrigin(String filename)
    {
        String base = commitFile(repository, filename, "base\n", "Base");

        git(repository, "checkout", "--quiet", "-b", "source");
        String source = commitFile(repository, filename, "source\n", "Conflicting change");

        git(repository, "checkout", "--quiet", "-B", "main", base);
        String target = commitFile(repository, filename, "target\n", "Target change");

        SequenceState conflict = driver.beginCherryPick(repository, target, source);
        assertThat(conflict.conflicts()).extracting(ConflictedPath::path).containsExactly(filename);
        SequenceState snapshot = driver.commitConflictSnapshot(repository, target, source, conflict.conflicts());
        assertThat(snapshot.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "log", "-1", "--format=%B"))
                .contains("(cherry picked from commit " + source + ")");
    }

    private String change(String name, String subject)
    {
        return commitFile(repository, name, subject + "\n", subject);
    }
}
