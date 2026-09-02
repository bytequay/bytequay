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
    void reportsNoPathsOutsideARepository()
            throws IOException
    {
        Path plain = Files.createDirectories(temporaryDirectory.resolve("plain"));

        assertThat(GIT.gitPaths(plain, ImmutableList.of("MERGE_HEAD"))).isEmpty();
    }

    private static void git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isZero();
    }
}
