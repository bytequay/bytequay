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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GitRunner#addedLines} against a real temp repo. This is the
 * number the Local Development Stage thresholds on to decide whether a Turn
 * wrote enough new code to be worth a cleanup pass, so the two properties that
 * matter are that it counts a Turn's own range (not the whole branch) and that
 * deletions never inflate it.
 */
class TestGitRunnerAddedLines
{
    private final GitRunner git = new GitRunner();

    @Test
    void countsOnlyAdditionsInTheGivenRange(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", lines(10));
        String before = gitOutput(repo, "rev-parse", "HEAD");

        // Two commits in "this Turn" — 30 added lines across both.
        commit(repo, "a.txt", lines(20));
        commit(repo, "b.txt", lines(10));
        String after = gitOutput(repo, "rev-parse", "HEAD");

        // The Turn's own range, not base..HEAD — the 10 lines from the
        // earlier commit are excluded.
        assertThat(git.addedLines(repo, before, after)).isEqualTo(30);
    }

    @Test
    void deletionsDoNotCountAsAdditions(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "big.txt", lines(50));
        String before = gitOutput(repo, "rev-parse", "HEAD");

        // A Turn that only deletes: the deletion WAS the simplification, so
        // it must score zero rather than 50.
        Files.delete(repo.resolve("big.txt"));
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "delete it all");
        String after = gitOutput(repo, "rev-parse", "HEAD");

        assertThat(git.addedLines(repo, before, after)).isZero();
    }

    @Test
    void returnsZeroWhenTheTurnCommittedNothing(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", lines(5));
        String head = gitOutput(repo, "rev-parse", "HEAD");

        // Same sha both sides — short-circuits without shelling out.
        assertThat(git.addedLines(repo, head, head)).isZero();
    }

    @Test
    void returnsZeroForNullShas(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", lines(5));
        String head = gitOutput(repo, "rev-parse", "HEAD");

        // A Turn with no recorded input head must not trigger anything.
        assertThat(git.addedLines(repo, null, head)).isZero();
        assertThat(git.addedLines(repo, head, null)).isZero();
    }

    @Test
    void binaryFilesCountAsZeroRatherThanFailing(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", lines(1));
        String before = gitOutput(repo, "rev-parse", "HEAD");

        // git reports "-" for binary numstat columns; that must parse as 0
        // and not blow up the threshold check.
        Files.write(repo.resolve("blob.bin"), new byte[] {0, 1, 2, 0, 3});
        Files.writeString(repo.resolve("text.txt"), lines(7));
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "binary plus text");
        String after = gitOutput(repo, "rev-parse", "HEAD");

        assertThat(git.addedLines(repo, before, after)).isEqualTo(7);
    }

    private static String lines(int count)
    {
        return IntStream.range(0, count)
                .mapToObj(i -> "line " + i)
                .collect(joining("\n")) + "\n";
    }

    private static void init(Path repo)
            throws IOException, InterruptedException
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
    }

    private static void commit(Path repo, String file, String content)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), content);
        git(repo, "add", ".");
        git(repo, "commit", "-m", "write " + file);
    }

    private static void git(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process process = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + code + ") in " + repo);
        }
    }

    private static String gitOutput(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process process = new ProcessBuilder(cmd)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed in " + repo);
        }
        return output.strip();
    }
}
