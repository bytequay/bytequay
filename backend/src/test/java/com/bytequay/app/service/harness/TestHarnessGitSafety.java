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

import com.bytequay.app.service.harness.HarnessGitSafety.SafetyException;
import com.bytequay.app.service.harness.HarnessGitSafety.SafetyResult;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestHarnessGitSafety
{
    @TempDir
    Path tempDir;

    private Path remote;
    private Path repo;
    private String baseSha;
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
        baseSha = git(repo, "rev-parse", "HEAD");

        Files.writeString(repo.resolve("plan.txt"), "owned\n");
        git(repo, "add", "plan.txt");
        git(repo, "commit", "-m", "Update plan");
        git(repo, "push", "origin", "main");
        originalHead = git(repo, "rev-parse", "HEAD");
        safety = new HarnessGitSafety(new GitRunner(), new ShellRunner());
    }

    @Test
    void persistsBackupBeforeMutationAndProvesNetNeutralRewriteWithoutPush()
            throws Exception
    {
        Files.writeString(repo.resolve("plan.txt"), "fixed\n");
        AtomicReference<String> backup = new AtomicReference<>();

        SafetyResult result = safety.commitFixupAndAutosquash(
                repo, List.of("plan.txt"), "Update plan", baseSha,
                "origin", "main", () -> true, (ref, head) -> {
                    backup.set(ref);
                    assertThat(head).isEqualTo(originalHead);
                    assertThat(runGit(repo, "rev-parse", "HEAD")).isEqualTo(originalHead);
                    assertThat(runGit(repo, "status", "--porcelain")).contains("plan.txt");
                });

        assertThat(result.backupRef()).isEqualTo(backup.get());
        assertThat(result.proof().emptyTreeDiff()).isTrue();
        assertThat(result.proof().rangeEquivalent()).isTrue();
        assertThat(result.proof().beforeTree()).isEqualTo(result.proof().afterTree());
        assertThat(Files.readString(repo.resolve("plan.txt"))).isEqualTo("fixed\n");
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(git(null, "--git-dir", remote.toString(), "rev-parse", "main"))
                .isEqualTo(originalHead);
        assertThat(git(repo, "show-ref", "--verify", "refs/heads/" + backup.get()))
                .contains(originalHead);
        assertThat(Arrays.stream(HarnessGitSafety.class.getDeclaredMethods())
                .map(method -> method.getName().toLowerCase(Locale.ROOT))
                .noneMatch(name -> name.contains("push")))
                .isTrue();
    }

    @Test
    void rangeProofRejectsUnexpectedCommitAttribution()
    {
        String expected = """
                1:  aaaaaaa = 1:  bbbbbbb Keep first
                2:  ccccccc ! 2:  ddddddd Update plan
                3:  eeeeeee < -:  ------- fixup! Update plan
                """;
        String drifted = """
                1:  aaaaaaa ! 1:  bbbbbbb Keep first
                2:  ccccccc ! 2:  ddddddd Update plan
                3:  eeeeeee < -:  ------- fixup! Update plan
                """;
        String recreated = """
                1:  aaaaaaa = 1:  bbbbbbb Keep first
                2:  ccccccc < -:  ------- Update plan
                3:  eeeeeee < -:  ------- fixup! Update plan
                -:  ------- > 2:  ddddddd Update plan
                """;

        assertThat(HarnessGitSafety.expectedRangeDiff(
                expected, List.of(new HarnessGitSafety.FixupTarget(
                        "ccccccc", "Update plan", "eeeeeee")))).isTrue();
        assertThat(HarnessGitSafety.expectedRangeDiff(
                drifted, List.of(new HarnessGitSafety.FixupTarget(
                        "ccccccc", "Update plan", "eeeeeee")))).isFalse();
        assertThat(HarnessGitSafety.expectedRangeDiff(
                recreated, List.of(new HarnessGitSafety.FixupTarget(
                        "ccccccc", "Update plan", "eeeeeee")))).isTrue();
    }

    @Test
    void pathScopedFixupIncludesANewGeneratedFile()
            throws Exception
    {
        Files.writeString(repo.resolve("generated.txt"), "stable\n");

        SafetyResult result = safety.commitFixupAndAutosquash(
                repo, List.of("generated.txt"), "Update plan", baseSha,
                "origin", "main", () -> true, (ref, head) -> {});

        assertThat(result.proof().rangeEquivalent()).isTrue();
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(git(repo, "ls-files", "generated.txt"))
                .isEqualTo("generated.txt");
        assertThat(Files.readString(repo.resolve("generated.txt")))
                .isEqualTo("stable\n");
    }

    @Test
    void failedProposalCleanupRemovesOnlyItsCapturedGeneratedPath()
            throws Exception
    {
        Files.writeString(repo.resolve("generated.txt"), "partial\n");

        safety.discardTrackedProposal(repo, List.of("generated.txt"));

        assertThat(Files.exists(repo.resolve("generated.txt"))).isFalse();
        assertThat(git(repo, "status", "--porcelain")).isBlank();
    }

    @Test
    void comparesFetchedHeadAndRefusesAChangedRemote()
            throws Exception
    {
        Path other = tempDir.resolve("other");
        git(null, "clone", remote.toString(), other.toString());
        git(other, "config", "user.name", "Other");
        git(other, "config", "user.email", "other@example.com");
        Files.writeString(other.resolve("remote.txt"), "advanced\n");
        git(other, "add", "remote.txt");
        git(other, "commit", "-m", "Advance remote");
        git(other, "push", "origin", "main");
        String advancedRemote = git(other, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("plan.txt"), "fixed\n");

        assertThatThrownBy(() -> safety.commitFixupAndAutosquash(
                repo, List.of("plan.txt"), "Update plan", baseSha,
                "origin", "main", () -> true, (ref, head) -> {}))
                .isInstanceOf(SafetyException.class)
                .hasMessageContaining("remote branch diverged");
        assertThat(git(repo, "rev-parse", "HEAD")).isEqualTo(originalHead);
        assertThat(git(repo, "status", "--porcelain")).contains("plan.txt");
        assertThat(git(null, "--git-dir", remote.toString(), "rev-parse", "main"))
                .isEqualTo(advancedRemote);
    }

    @Test
    void cancellationDuringMutationRestoresTheOriginalHead()
            throws Exception
    {
        Files.writeString(repo.resolve("plan.txt"), "fixed\n");
        AtomicInteger activeChecks = new AtomicInteger();
        AtomicReference<String> backup = new AtomicReference<>();

        assertThatThrownBy(() -> safety.commitFixupAndAutosquash(
                repo, List.of("plan.txt"), "Update plan", baseSha,
                "origin", "main", () -> activeChecks.incrementAndGet() < 4,
                (ref, head) -> backup.set(ref)))
                .isInstanceOf(SafetyException.class)
                .hasMessageContaining("cancelled");
        assertThat(git(repo, "rev-parse", "HEAD")).isEqualTo(originalHead);
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(git(repo, "show-ref", "--verify", "refs/heads/" + backup.get()))
                .contains(originalHead);
    }

    @Test
    void commitsMultipleFixupsBeforeOneNetNeutralNormalization()
            throws Exception
    {
        Files.writeString(repo.resolve("second.txt"), "owned\n");
        git(repo, "add", "second.txt");
        git(repo, "commit", "-m", "Update second");
        git(repo, "push", "origin", "main");
        String batchHead = git(repo, "rev-parse", "HEAD");
        AtomicReference<String> backup = new AtomicReference<>();

        Files.writeString(repo.resolve("plan.txt"), "fixed\n");
        HarnessGitSafety.FixupBatch batch = safety.beginFixupBatch(
                repo, baseSha, "origin", "main", () -> true,
                (ref, head) -> {
                    backup.set(ref);
                    assertThat(head).isEqualTo(batchHead);
                });
        batch.commitFixup(List.of("plan.txt"), "Update plan");
        assertThat(git(repo, "log", "-1", "--pretty=%s"))
                .isEqualTo("fixup! Update plan");

        Files.writeString(repo.resolve("second.txt"), "fixed\n");
        batch.commitFixup(List.of("second.txt"), "Update second");
        assertThat(git(repo, "log", "-1", "--pretty=%s"))
                .isEqualTo("fixup! Update second");

        SafetyResult result = batch.finish();

        assertThat(result.backupRef()).isEqualTo(backup.get());
        assertThat(result.proof().emptyTreeDiff()).isTrue();
        assertThat(result.proof().beforeTree()).isEqualTo(result.proof().afterTree());
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(Files.readString(repo.resolve("plan.txt"))).isEqualTo("fixed\n");
        assertThat(Files.readString(repo.resolve("second.txt"))).isEqualTo("fixed\n");
        assertThat(git(repo, "log", "--pretty=%s", baseSha + "..HEAD"))
                .doesNotContain("fixup!");
        assertThat(git(null, "--git-dir", remote.toString(), "rev-parse", "main"))
                .isEqualTo(batchHead);
    }

    @Test
    void finalFetchRestoresTheBatchWhenRemoteMovesDuringVerification()
            throws Exception
    {
        Files.writeString(repo.resolve("plan.txt"), "fixed\n");
        HarnessGitSafety.FixupBatch batch = safety.beginFixupBatch(
                repo, baseSha, "origin", "main", () -> true, (ref, head) -> {});
        batch.commitFixup(List.of("plan.txt"), "Update plan");

        Path other = tempDir.resolve("other-during-batch");
        git(null, "clone", remote.toString(), other.toString());
        git(other, "config", "user.name", "Other");
        git(other, "config", "user.email", "other@example.com");
        Files.writeString(other.resolve("remote.txt"), "advanced\n");
        git(other, "add", "remote.txt");
        git(other, "commit", "-m", "Advance during batch");
        git(other, "push", "origin", "main");

        assertThatThrownBy(batch::finish)
                .isInstanceOf(SafetyException.class)
                .hasMessageContaining("changed during the fixup batch");
        assertThat(git(repo, "rev-parse", "HEAD")).isEqualTo(originalHead);
        assertThat(git(repo, "status", "--porcelain")).isBlank();
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
