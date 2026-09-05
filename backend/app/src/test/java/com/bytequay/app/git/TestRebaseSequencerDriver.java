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

import com.bytequay.app.git.SequenceState.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.bytequay.app.git.GitTestSupport.git;
import static org.assertj.core.api.Assertions.assertThat;

class TestRebaseSequencerDriver
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private RebaseSequencerDriver driver;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path created = Files.createDirectories(temporaryDirectory.resolve("repo"));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        git(created, "config", "user.name", "ByteQuay Test");
        git(created, "config", "user.email", "bytequay@example.com");
        repository = created.toRealPath();
        driver = new RebaseSequencerDriver(GIT);
    }

    @Test
    void cleanlyRebasesOntoTheFrozenBase()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", "feature");
        String original = change("feature.txt", "feature\n", "Feature change");
        git(repository, "checkout", "--quiet", "main");
        String base = change("main.txt", "main\n", "Main change");
        git(repository, "checkout", "--quiet", "feature");

        SequenceState state = driver.begin(repository, original, base);

        assertThat(state.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "merge-base", "--is-ancestor", base, state.head())).isEmpty();
        assertThat(state.head()).isNotEqualTo(original);
    }

    @Test
    void observesExactRebaseHeadAndContinuesAStagedResolutionWithoutAnEditor()
    {
        Conflict conflict = conflict("file.txt");
        git(repository, "config", "core.editor", "false");

        SequenceState stopped = driver.begin(repository, conflict.originalTip(), conflict.baseTip());
        assertThat(stopped.kind()).isEqualTo(Kind.STOPPED_CONFLICT);
        assertThat(stopped.stoppedCommit()).contains(conflict.sourceCommit());
        assertThat(stopped.conflicts()).extracting(SequenceState.ConflictedPath::path)
                .containsExactly("file.txt");

        write("file.txt", "resolved\n");
        git(repository, "add", "file.txt");
        SequenceState staged = driver.observe(repository);
        assertThat(staged.kind()).isEqualTo(Kind.STOPPED_RESOLVED);

        SequenceState completed = driver.continueRebase(
                repository, staged.head(), conflict.sourceCommit());
        assertThat(completed.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "merge-base", "--is-ancestor", conflict.baseTip(), completed.head()))
                .isEmpty();
    }

    @Test
    void skipsAConflictResolutionThatProducesNoChange()
    {
        Conflict conflict = conflict("file.txt");
        SequenceState stopped = driver.begin(repository, conflict.originalTip(), conflict.baseTip());
        write("file.txt", "main\n");
        git(repository, "add", "file.txt");

        SequenceState empty = driver.observe(repository);
        assertThat(empty.kind()).isEqualTo(Kind.STOPPED_EMPTY);

        SequenceState completed = driver.skipEmpty(
                repository, stopped.head(), conflict.sourceCommit());
        assertThat(completed.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(completed.head()).isEqualTo(conflict.baseTip());
    }

    @Test
    void continueStopsAgainOnTheNextConflictingCommit()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", "feature");
        write("one.txt", "feature one\n");
        commit("Feature one");
        String first = head();
        write("two.txt", "feature two\n");
        commit("Feature two");
        String second = head();
        String original = second;

        git(repository, "checkout", "--quiet", "main");
        write("one.txt", "main one\n");
        write("two.txt", "main two\n");
        commit("Main changes");
        String base = head();
        git(repository, "checkout", "--quiet", "feature");

        SequenceState stopped = driver.begin(repository, original, base);
        assertThat(stopped.stoppedCommit()).contains(first);
        write("one.txt", "resolved one\n");
        git(repository, "add", "one.txt");

        SequenceState next = driver.continueRebase(repository, stopped.head(), first);
        assertThat(next.kind()).isEqualTo(Kind.STOPPED_CONFLICT);
        assertThat(next.stoppedCommit()).contains(second);
    }

    @Test
    void disablesRepositoryAutosquashConfiguration()
    {
        seed();
        git(repository, "checkout", "--quiet", "-b", "feature");
        String first = change("feature.txt", "one\n", "Feature");
        write("feature.txt", "one\ntwo\n");
        git(repository, "add", "feature.txt");
        git(repository, "commit", "--quiet", "--fixup=" + first);
        String original = head();
        git(repository, "config", "rebase.autoSquash", "true");

        git(repository, "checkout", "--quiet", "main");
        String base = change("main.txt", "main\n", "Main");
        git(repository, "checkout", "--quiet", "feature");

        SequenceState completed = driver.begin(repository, original, base);
        assertThat(completed.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(git(repository, "rev-list", "--count", base + "..HEAD")).isEqualTo("2");
    }

    @Test
    void rejectsUntrackedOrPartlyStagedResolutionState()
    {
        Conflict conflict = conflict("file.txt");
        driver.begin(repository, conflict.originalTip(), conflict.baseTip());
        write("file.txt", "resolved\n");
        git(repository, "add", "file.txt");
        write("file.txt", "resolved but changed again\n");

        assertThat(driver.observe(repository).kind()).isEqualTo(Kind.UNSAFE);

        git(repository, "add", "file.txt");
        write("untracked.txt", "outside the resolution\n");
        assertThat(driver.observe(repository).kind()).isEqualTo(Kind.UNSAFE);
    }

    @Test
    void abortRestoresTheExactOriginalTip()
    {
        Conflict conflict = conflict("file.txt");
        driver.begin(repository, conflict.originalTip(), conflict.baseTip());

        SequenceState aborted = driver.abort(repository, conflict.originalTip());

        assertThat(aborted.kind()).isEqualTo(Kind.COMPLETED);
        assertThat(aborted.head()).isEqualTo(conflict.originalTip());
        assertThat(git(repository, "symbolic-ref", "--short", "HEAD")).isEqualTo("feature");
    }

    private Conflict conflict(String path)
    {
        write(path, "seed\n");
        commit("Base");
        git(repository, "checkout", "--quiet", "-b", "feature");
        write(path, "feature\n");
        commit("Feature conflict");
        String source = head();
        String original = source;
        git(repository, "checkout", "--quiet", "main");
        write(path, "main\n");
        commit("Main conflict");
        String base = head();
        git(repository, "checkout", "--quiet", "feature");
        return new Conflict(original, base, source);
    }

    private void seed()
    {
        write("seed.txt", "seed\n");
        commit("Base");
    }

    private String change(String path, String content, String message)
    {
        write(path, content);
        commit(message);
        return head();
    }

    private void write(String path, String content)
    {
        GitTestSupport.write(repository, path, content);
    }

    private void commit(String message)
    {
        GitTestSupport.commit(repository, message);
    }

    private String head()
    {
        return GitTestSupport.head(repository);
    }

    private record Conflict(String originalTip, String baseTip, String sourceCommit) {}
}
