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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitRunnerWorktreeRepair
{
    private final GitRunner git = new GitRunner();

    @Test
    void abortsAConflictedRebaseBackToTheExactSource(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "shared.txt", "base", "base");

        git(repo, "checkout", "-b", "dev/task");
        commit(repo, "shared.txt", "task", "task change");
        String sourceHead = gitOutput(repo, "rev-parse", "HEAD");

        git(repo, "checkout", "main");
        commit(repo, "shared.txt", "main", "main change");
        git(repo, "checkout", "dev/task");
        assertThat(gitExitCode(repo, "rebase", "main")).isNotZero();

        assertThat(git.inProgressOperations(repo))
                .contains("REBASE_HEAD", "AUTO_MERGE")
                .allMatch(operation -> operation.equals("rebase-merge")
                        || operation.equals("rebase-apply")
                        || operation.equals("REBASE_HEAD")
                        || operation.equals("AUTO_MERGE"));

        assertThat(git.abortInProgressOperationForRepair(repo)).isTrue();
        assertThat(git.currentBranch(repo)).isEqualTo("dev/task");
        assertThat(git.headSha(repo)).isEqualTo(sourceHead);
        assertThat(git.statusPorcelainZ(repo)).isEmpty();
        assertThat(git.inProgressOperations(repo)).isEmpty();
    }

    @Test
    void abortsAMergeWithItsAuxiliaryMarkers(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "shared.txt", "base", "base");
        commit(repo, "stable.txt", "stable", "stable file");

        git(repo, "checkout", "-b", "dev/task");
        commit(repo, "shared.txt", "task", "task change");

        git(repo, "checkout", "main");
        commit(repo, "shared.txt", "main", "main change");
        String sourceHead = gitOutput(repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("stable.txt"), "dirty", StandardCharsets.UTF_8);
        assertThat(gitExitCode(repo, "merge", "--autostash", "dev/task")).isNotZero();

        assertThat(git.inProgressOperations(repo))
                .containsExactly("MERGE_HEAD", "MERGE_AUTOSTASH", "AUTO_MERGE");

        assertThat(git.abortInProgressOperationForRepair(repo)).isTrue();
        assertThat(git.currentBranch(repo)).isEqualTo("main");
        assertThat(git.headSha(repo)).isEqualTo(sourceHead);
        assertThat(Files.readString(repo.resolve("stable.txt"), StandardCharsets.UTF_8))
                .isEqualTo("dirty");
        assertThat(git.inProgressOperations(repo)).isEmpty();
    }

    @Test
    void abortsARecognizedBisectBackToItsStartingBranch(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "value.txt", "one", "one");
        String goodHead = gitOutput(repo, "rev-parse", "HEAD");
        commit(repo, "value.txt", "two", "two");
        commit(repo, "value.txt", "three", "three");
        commit(repo, "value.txt", "four", "four");
        commit(repo, "value.txt", "five", "five");
        String sourceHead = gitOutput(repo, "rev-parse", "HEAD");

        git(repo, "bisect", "start");
        git(repo, "bisect", "bad", sourceHead);
        git(repo, "bisect", "good", goodHead);

        assertThat(git.currentBranch(repo)).isNull();
        assertThat(git.headSha(repo)).isNotEqualTo(sourceHead);
        assertThat(git.inProgressOperations(repo))
                .contains("BISECT_START", "BISECT_NAMES", "BISECT_LOG",
                        "BISECT_TERMS", "refs/bisect");

        assertThat(git.abortInProgressOperationForRepair(repo)).isTrue();
        assertThat(git.currentBranch(repo)).isEqualTo("main");
        assertThat(git.headSha(repo)).isEqualTo(sourceHead);
        assertThat(git.inProgressOperations(repo)).isEmpty();
    }

    @Test
    void abortsANoCheckoutBisectAndClearsItsBisectHead(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "value.txt", "one", "one");
        String goodHead = git.headSha(repo);
        commit(repo, "value.txt", "two", "two");
        commit(repo, "value.txt", "three", "three");
        commit(repo, "value.txt", "four", "four");
        commit(repo, "value.txt", "five", "five");
        String sourceHead = git.headSha(repo);

        git(repo, "bisect", "start", "--no-checkout");
        git(repo, "bisect", "bad", sourceHead);
        git(repo, "bisect", "good", goodHead);

        assertThat(git.inProgressOperations(repo))
                .contains("BISECT_START", "BISECT_HEAD", "BISECT_LOG", "refs/bisect");
        assertThat(git.abortInProgressOperationForRepair(repo)).isTrue();
        assertThat(git.currentBranch(repo)).isEqualTo("main");
        assertThat(git.headSha(repo)).isEqualTo(sourceHead);
        assertThat(git.inProgressOperations(repo)).isEmpty();
    }

    @Test
    void leavesAmbiguousOperationMarkersUntouched(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "shared.txt", "base", "base");

        git(repo, "checkout", "-b", "dev/task");
        commit(repo, "shared.txt", "task", "task change");
        String taskHead = gitOutput(repo, "rev-parse", "HEAD");

        git(repo, "checkout", "main");
        commit(repo, "shared.txt", "main", "main change");
        assertThat(gitExitCode(repo, "merge", "dev/task")).isNotZero();

        Path mergeHead = gitPath(repo, "MERGE_HEAD");
        Path cherryPickHead = gitPath(repo, "CHERRY_PICK_HEAD");
        Files.writeString(cherryPickHead, taskHead + "\n", StandardCharsets.UTF_8);
        assertThat(git.inProgressOperations(repo))
                .containsExactly("MERGE_HEAD", "AUTO_MERGE", "CHERRY_PICK_HEAD");

        assertThatThrownBy(() -> git.abortInProgressOperationForRepair(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");

        assertThat(mergeHead).isRegularFile();
        assertThat(cherryPickHead).isRegularFile();
        assertThat(git.inProgressOperations(repo))
                .containsExactly("MERGE_HEAD", "AUTO_MERGE", "CHERRY_PICK_HEAD");
    }

    @Test
    void leavesAStandaloneRebaseHeadUntouched(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "base.txt", "base", "base");
        String head = git.headSha(repo);
        Path rebaseHead = gitPath(repo, "REBASE_HEAD");
        Files.writeString(rebaseHead, head + "\n", StandardCharsets.UTF_8);

        assertThat(git.inProgressOperations(repo)).containsExactly("REBASE_HEAD");
        assertThatThrownBy(() -> git.abortInProgressOperationForRepair(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
        assertThat(rebaseHead).isRegularFile();
        assertThat(Files.readString(rebaseHead, StandardCharsets.UTF_8))
                .isEqualTo(head + "\n");
    }

    @Test
    void leavesStandaloneMergeAuxiliaryMarkersUntouched(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "base.txt", "base", "base");
        String head = git.headSha(repo);
        Path autostash = gitPath(repo, "MERGE_AUTOSTASH");
        Path autoMerge = gitPath(repo, "AUTO_MERGE");
        Files.writeString(autostash, head + "\n", StandardCharsets.UTF_8);
        Files.writeString(autoMerge, head + "\n", StandardCharsets.UTF_8);

        assertThat(git.inProgressOperations(repo))
                .containsExactly("MERGE_AUTOSTASH", "AUTO_MERGE");
        assertThatThrownBy(() -> git.abortInProgressOperationForRepair(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
        assertThat(autostash).isRegularFile();
        assertThat(autoMerge).isRegularFile();
    }

    @Test
    void leavesStandaloneBisectStateUntouched(@TempDir Path repo)
            throws Exception
    {
        initialize(repo);
        commit(repo, "base.txt", "base", "base");
        Path bisectLog = gitPath(repo, "BISECT_LOG");
        Files.writeString(bisectLog, "foreign\n", StandardCharsets.UTF_8);

        assertThat(git.inProgressOperations(repo)).containsExactly("BISECT_LOG");
        assertThatThrownBy(() -> git.abortInProgressOperationForRepair(repo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambiguous");
        assertThat(bisectLog).isRegularFile();
        assertThat(Files.readString(bisectLog, StandardCharsets.UTF_8))
                .isEqualTo("foreign\n");
    }

    private static void initialize(Path repo)
            throws IOException, InterruptedException
    {
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.email", "t@example.com");
        git(repo, "config", "user.name", "Test");
    }

    private static void commit(Path repo, String file, String contents, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), contents, StandardCharsets.UTF_8);
        git(repo, "add", ".");
        git(repo, "commit", "-m", message);
    }

    private static Path gitPath(Path repo, String marker)
            throws IOException, InterruptedException
    {
        Path path = Path.of(gitOutput(repo, "rev-parse", "--git-path", marker));
        return path.isAbsolute() ? path : repo.resolve(path).normalize();
    }

    private static void git(Path repo, String... args)
            throws IOException, InterruptedException
    {
        int code = gitExitCode(repo, args);
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + code + ") in " + repo);
        }
    }

    private static int gitExitCode(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    private static String gitOutput(Path repo, String... args)
            throws IOException, InterruptedException
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
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
