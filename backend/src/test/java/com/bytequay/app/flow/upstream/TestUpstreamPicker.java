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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.upstream.UpstreamPicker.Outcome;
import com.bytequay.app.flow.upstream.UpstreamPicker.PickResult;
import com.bytequay.app.flow.upstream.UpstreamPicker.UnresolvedRepairException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestUpstreamPicker
{
    @TempDir
    private Path temporaryDirectory;

    private Path repository;
    private String cleanCommit;
    private String conflictingCommit;
    private String alreadyPresentCommit;

    @BeforeEach
    void createRangeAndFork()
            throws Exception
    {
        repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        git("init", "-b", "main");
        write("shared.txt", "shared v1\n");
        write("contested.txt", "original\n");
        git("add", "-A");
        git("commit", "-m", "base");

        git("checkout", "-b", "upstream");
        write("added.txt", "upstream addition\n");
        git("add", "-A");
        git("commit", "-m", "Add a file the fork does not have");
        cleanCommit = revision("HEAD");

        write("contested.txt", "upstream rewrite\n");
        git("add", "-A");
        git("commit", "-m", "Rewrite the contested file upstream");
        conflictingCommit = revision("HEAD");

        write("shared.txt", "shared v2\n");
        git("add", "-A");
        git("commit", "-m", "Bump the shared file");
        alreadyPresentCommit = revision("HEAD");

        git("checkout", "main");
        write("contested.txt", "fork rewrite\n");
        write("shared.txt", "shared v2\n");
        git("add", "-A");
        git("commit", "-m", "Fork changes, including one upstream also made");
    }

    @Test
    void appliesACleanCommitWithItsUpstreamProvenance()
    {
        UpstreamPicker picker = new UpstreamPicker(repository);
        String before = picker.head();

        PickResult result = picker.pick(cleanCommit);

        assertThat(result.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(result.head()).isNotEqualTo(before);
        assertThat(result.provenanceVerified()).isTrue();
        assertThat(picker.message(result.head()))
                .contains("(cherry picked from commit " + cleanCommit + ")");
        assertThat(picker.clean()).isTrue();
        assertThat(picker.sequencerActive()).isFalse();
    }

    @Test
    void skipsACommitTheForkAlreadyCarriesInsteadOfParking()
    {
        UpstreamPicker picker = new UpstreamPicker(repository);
        String before = picker.head();

        PickResult result = picker.pick(alreadyPresentCommit);

        assertThat(result.outcome()).isEqualTo(Outcome.EMPTY);
        assertThat(result.commitSha()).isNull();
        assertThat(picker.head()).isEqualTo(before);
        // The point of skipping: Git will not record an empty commit, so a
        // sequencer left open here could never be finished by anyone.
        assertThat(picker.sequencerActive()).isFalse();
        assertThat(picker.clean()).isTrue();
    }

    @Test
    void keepsConflictEvidenceOpenUntilAResolvedPickCanBeContinued()
            throws Exception
    {
        UpstreamPicker picker = new UpstreamPicker(repository);
        String preHead = picker.head();

        PickResult result = picker.pick(conflictingCommit);

        assertThat(result.outcome()).isEqualTo(Outcome.CONFLICTED);
        assertThat(result.head()).isEqualTo(preHead);
        assertThat(result.commitSha()).isNull();
        assertThat(result.conflictedPaths()).containsExactly("contested.txt");
        assertThat(result.provenanceVerified()).isFalse();
        assertThat(picker.sequencerActive()).isTrue();
        assertThat(picker.clean()).isFalse();

        assertThatThrownBy(() ->
                picker.continuePick(
                        preHead, conflictingCommit, result.conflictedPaths()))
                .isInstanceOf(UnresolvedRepairException.class)
                .hasMessageContaining("conflict marker");
        assertThat(revision("HEAD")).isEqualTo(preHead);
        assertThat(git("log", "--all", "-p"))
                .doesNotContain("<<<<<<<", ">>>>>>>");

        write("contested.txt", "merged rewrite\n");
        PickResult continued = picker.continuePick(
                preHead, conflictingCommit, result.conflictedPaths());

        assertThat(continued.commitSha()).isEqualTo(continued.head());
        assertThat(continued.provenanceVerified()).isTrue();
        assertThat(picker.message(continued.commitSha()))
                .contains("(cherry picked from commit "
                        + conflictingCommit + ")");
        assertThat(picker.sequencerActive()).isFalse();
        assertThat(picker.clean()).isTrue();
        assertThat(git("log", "--all", "-p"))
                .doesNotContain("<<<<<<<", ">>>>>>>");
    }

    @Test
    void amendsTheOneFixupAPickCarriesRatherThanAddingASecond()
            throws Exception
    {
        UpstreamPicker picker = new UpstreamPicker(repository);
        PickResult result = picker.pick(conflictingCommit);
        write("contested.txt", "merged rewrite\n");
        PickResult continued = picker.continuePick(
                result.head(), conflictingCommit, result.conflictedPaths());
        String targetSubject = picker.subject(continued.commitSha());
        write("contested.txt", "first attempt\n");
        String first = picker.commitFixup(targetSubject, false);

        write("contested.txt", "second attempt\n");
        String second = picker.commitFixup(targetSubject, true);

        assertThat(second).isNotEqualTo(first);
        assertThat(picker.subject(second))
                .isEqualTo("fixup! " + targetSubject);
        assertThat(revision("HEAD~1")).isEqualTo(continued.commitSha());
        assertThat(Files.readString(repository.resolve("contested.txt")))
                .isEqualTo("second attempt\n");
    }

    @Test
    void refusesToAttributeARepairThatChangedNothing()
            throws IOException
    {
        UpstreamPicker picker = new UpstreamPicker(repository);
        PickResult result = picker.pick(conflictingCommit);
        write("contested.txt", "merged rewrite\n");
        PickResult continued = picker.continuePick(
                result.head(), conflictingCommit, result.conflictedPaths());

        assertThatThrownBy(() -> picker.commitFixup(
                picker.subject(continued.commitSha()), false))
                .isInstanceOf(UnresolvedRepairException.class)
                .hasMessageContaining("nothing to attribute");
    }

    @Test
    void transfersConfirmedObjectsBetweenSeparateRepositories()
            throws Exception
    {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        initialize(source);
        write(source, "source.txt", "only upstream\n");
        git(source, "add", "-A");
        git(source, "commit", "-m", "Source-only commit");
        String sourceCommit = revision(source, "HEAD");
        initialize(target);

        assertThat(UpstreamPicker.hasCommit(target, sourceCommit)).isFalse();

        assertThat(UpstreamPicker.transferObjects(
                source, target, List.of(sourceCommit)))
                .containsExactly(sourceCommit);

        assertThat(UpstreamPicker.hasCommit(target, sourceCommit)).isTrue();
        assertThat(Files.exists(target.resolve(".git/FETCH_HEAD"))).isFalse();
    }

    private void write(String path, String content)
            throws IOException
    {
        write(repository, path, content);
    }

    private static void write(Path root, String path, String content)
            throws IOException
    {
        Files.writeString(
                root.resolve(path), content, StandardCharsets.UTF_8);
    }

    private String revision(String reference)
            throws Exception
    {
        return revision(repository, reference);
    }

    private String git(String... arguments)
            throws IOException, InterruptedException
    {
        return git(repository, arguments);
    }

    private void initialize(Path root)
            throws IOException, InterruptedException
    {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        write(root, "base.txt", root.getFileName() + "\n");
        git(root, "add", "-A");
        git(root, "commit", "-m", "base");
    }

    private static String revision(Path root, String reference)
            throws IOException, InterruptedException
    {
        return git(root, "rev-parse", "--verify", reference).strip();
    }

    private static String git(Path root, String... arguments)
            throws IOException, InterruptedException
    {
        String[] command = new String[arguments.length + 7];
        command[0] = "/usr/bin/git";
        command[1] = "-C";
        command[2] = root.toString();
        command[3] = "-c";
        command[4] = "user.name=ByteQuay Test";
        command[5] = "-c";
        command[6] = "user.email=test@bytequay.invalid";
        System.arraycopy(arguments, 0, command, 7, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    String.join(" ", List.of(arguments)) + ": " + output);
        }
        return output;
    }
}
