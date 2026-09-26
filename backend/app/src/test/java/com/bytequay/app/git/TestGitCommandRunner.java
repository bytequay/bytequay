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

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitCommandRunner
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path repository;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path created = Files.createDirectories(temporaryDirectory.resolve("repo"));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        repository = created.toRealPath();
    }

    @Test
    void resolvesEveryMarkerInOneCallAndKeepsTheRequestedOrder()
            throws IOException
    {
        List<String> markers = ImmutableList.of("CHERRY_PICK_HEAD", "rebase-merge", "MERGE_HEAD");

        List<Path> paths = GIT.gitPaths(repository, markers);

        assertThat(paths).hasSameSizeAs(markers);
        for (int marker = 0; marker < markers.size(); marker++) {
            assertThat(paths.get(marker))
                    .isAbsolute()
                    .isEqualTo(repository.resolve(".git").resolve(markers.get(marker)));
        }

        // The caller decides what an unborn marker means, so a resolved path is
        // reported whether or not the operation is open.
        assertThat(paths).noneMatch(Files::exists);
        Files.writeString(paths.get(2), "0".repeat(40) + "\n", StandardCharsets.UTF_8);
        assertThat(GIT.gitPaths(repository, markers)).map(Files::exists)
                .containsExactly(false, false, true);
    }

    @Test
    void failsOnlyOnLeftoverMarkersNotOnWhitespaceTheAuthorWrote()
    {
        git(repository, "config", "user.name", "ByteQuay Test");
        git(repository, "config", "user.email", "bytequay@example.com");
        GitTestSupport.write(repository, "file.txt", "one\n");
        GitTestSupport.commit(repository, "Base");
        String before = GitTestSupport.head(repository);

        GitTestSupport.write(repository, "file.txt", "one\ntrailing space here   \n\n");
        GitTestSupport.commit(repository, "Faithful copy of untidy upstream content");
        assertThat(GIT.withoutConflictMarkers(repository, before, GitTestSupport.head(repository)))
                .as("upstream whitespace must not fail a faithful pick")
                .isTrue();

        String beforeMarkers = GitTestSupport.head(repository);
        GitTestSupport.write(repository, "file.txt", "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> other\n");
        GitTestSupport.commit(repository, "Left a resolution unfinished");
        assertThat(GIT.withoutConflictMarkers(repository, beforeMarkers, GitTestSupport.head(repository))).isFalse();
    }

    @Test
    void reportsNoPathsOutsideARepository()
            throws IOException
    {
        Path plain = Files.createDirectories(temporaryDirectory.resolve("plain"));

        assertThat(GIT.gitPaths(plain, ImmutableList.of("MERGE_HEAD"))).isEmpty();
    }

    @Test
    void stdoutRunKeepsWarningsOutOfTheOutputAndReportsThemOnFailure()
    {
        String bothStreams = "alias.both=!printf 'out\\0'; echo 'warning: noise' 1>&2";
        assertThat(GIT.run(repository, "-c", bothStreams, "both").output()).contains("warning: noise").contains("out\0");

        GitCommandRunner.Result success = GIT.runStdout(repository, "-c", bothStreams, "both");
        assertThat(success.successful()).isTrue();
        assertThat(success.output()).isEqualTo("out\0");

        GitCommandRunner.Result failure = GIT.runStdout(repository, "-c", bothStreams + "; exit 3", "both");
        assertThat(failure.exitCode()).isEqualTo(3);
        assertThat(failure.output()).isEqualTo("warning: noise\n");
    }

    @Test
    void localBoundedReadsUseConfiguredExecutableSafetyEnvironmentAndOutputCap()
            throws IOException
    {
        Path executable = temporaryDirectory.resolve("local-git");
        Files.writeString(executable, """
                #!/bin/sh
                printf '%s %s %s %s\\n' "$GIT_NO_LAZY_FETCH" "$GIT_NO_REPLACE_OBJECTS" "$GIT_TERMINAL_PROMPT" "$LC_ALL"
                printf '%s\\n' "$@"
                """);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        GitCommandRunner runner = new GitCommandRunner(executable.toString());

        GitCommandRunner.Result read = runner.runLocalBounded(repository, Duration.ofSeconds(2), 4096, ImmutableList.of("cat-file", "blob", "a".repeat(40)));
        assertThat(read.successful()).isTrue();
        assertThat(read.truncated()).isFalse();
        assertThat(read.output()).isEqualTo("1 1 0 C\n-C\n" + repository + "\ncat-file\nblob\n" + "a".repeat(40) + "\n");

        GitCommandRunner.Result bounded = runner.runLocalBounded(repository, Duration.ofSeconds(2), 8, ImmutableList.of("cat-file"));
        assertThat(bounded.successful()).isTrue();
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.output()).isEqualTo("1 1 0 C\n");
    }

    private static void git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isZero();
    }
}
