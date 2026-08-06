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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ShellRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestHarnessGitSafety
{
    @TempDir
    Path tempDir;

    private Path remote;
    private Path repo;
    private String originalHead;
    private HarnessGitSafety safety;

    @BeforeEach
    void setUp()
            throws Exception
    {
        remote = tempDir.resolve("remote.git");
        repo = tempDir.resolve("repo");
        git(null, "init", "--bare", "-b", "main", remote.toString());
        git(null, "init", "-b", "main", repo.toString());
        git(repo, "config", "user.name", "Harness Test");
        git(repo, "config", "user.email", "harness@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("plan.txt"), "base\n");
        git(repo, "add", "plan.txt");
        git(repo, "commit", "-m", "Base commit");
        git(repo, "remote", "add", "origin", remote.toString());
        git(repo, "push", "-u", "origin", "main");

        Files.writeString(repo.resolve("plan.txt"), "owned\n");
        git(repo, "add", "plan.txt");
        git(repo, "commit", "-m", "Update plan");
        git(repo, "push", "origin", "main");
        originalHead = git(repo, "rev-parse", "HEAD");
        safety = new HarnessGitSafety(new GitRunner(), new ShellRunner());
    }

    @Test
    void restartRecoveryAbortsMutationAndKeepsTheBackupRef()
            throws Exception
    {
        String backup = "bytequay-backup/ci-harness/restart-" + originalHead.substring(0, 8);
        git(repo, "branch", backup, originalHead);
        Files.writeString(repo.resolve("plan.txt"), "half-written\n");
        git(repo, "add", "plan.txt");
        git(repo, "commit", "-m", "fixup! Update plan");
        assertThat(git(repo, "rev-parse", "HEAD")).isNotEqualTo(originalHead);

        safety.recoverInterrupted(repo, backup, originalHead);

        assertThat(git(repo, "rev-parse", "HEAD")).isEqualTo(originalHead);
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(git(repo, "show-ref", "--verify", "refs/heads/" + backup))
                .contains(originalHead);
    }

    private static String git(Path workingDirectory, String... args)
            throws Exception
    {
        String output = runGit(workingDirectory, args);
        return output.strip();
    }

    private static String runGit(Path workingDirectory, String... args)
    {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new AssertionError(String.join(" ", command) + " failed:\n" + output);
            }
            return output.strip();
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("unable to run git", e);
        }
    }
}
