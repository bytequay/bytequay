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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.HistoryRewriter.RewriteFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBaseCiHistoryRewriter
{
    private final GitRunner git = new GitRunner();
    private final BaseCiHistoryRewriter rewriter =
            new BaseCiHistoryRewriter(git);

    @Test
    void putsOneSquashedRepairBeforeTheExactTaskSeries(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base", "base");
        String base = head(repo);
        git(repo, "switch", "-c", "task");

        author(repo, "First Author", "first@example.com");
        commit(repo, "task-a.txt", "task a", "Task one\n\nKeep this body.");
        String taskA = head(repo);
        author(repo, "Second Author", "second@example.com");
        commit(repo, "task-b.txt", "task b", "Task two");
        String taskB = head(repo);
        String frozenHead = taskB;

        author(repo, "Repair Agent", "repair@example.com");
        commit(repo, "base-fix-a.txt", "repair a", "Repair base lint");
        String repairA = head(repo);
        commit(repo, "base-fix-b.txt", "repair b", "Tighten base lint repair");
        String repairB = head(repo);
        String stageOutput = head(repo);
        String inputTree = git.commitTreeSha(repo, stageOutput);

        BaseCiHistoryRewriter.Request request =
                new BaseCiHistoryRewriter.Request(
                        repo, "task", base, frozenHead,
                        List.of(taskA, taskB), stageOutput);
        BaseCiHistoryRewriter.Result result = rewriter.rewrite(request);
        BaseCiHistoryRewriter.Result recovered = rewriter.recover(request);

        List<String> range = git.commitShasInRange(repo, base, result.headSha());
        assertThat(range).hasSize(3);
        assertThat(result.proof().repairCommitSha()).isEqualTo(range.getFirst());
        assertThat(result.proof().foldedRepairInputShas())
                .containsExactly(repairA, repairB);
        assertThat(result.proof().originalCommitShas())
                .containsExactly(taskA, taskB);
        assertThat(result.proof().originalCommitProofs())
                .extracting(BaseCiHistoryRewriter.CommitProof::authorEmail)
                .containsExactly("first@example.com", "second@example.com");
        assertThat(result.proof().originalCommitProofs())
                .allSatisfy(proof ->
                        assertThat(proof.outputPatchId())
                                .isEqualTo(proof.inputPatchId()));
        assertThat(result.proof().originalPatchSeriesDigest())
                .isEqualTo(result.proof().rewrittenPatchSeriesDigest());
        assertThat(result.proof().inputTreeSha()).isEqualTo(inputTree);
        assertThat(result.proof().outputTreeSha()).isEqualTo(inputTree);
        assertThat(result.proof().repeatedRepair()).isFalse();
        assertThat(recovered).isEqualTo(result);
        assertThat(subjects(repo))
                .containsExactly("Task two", "Task one", "Repair base lint", "base");
        assertThat(git.commitDetail(repo, range.get(1)).orElseThrow().body())
                .isEqualTo("Keep this body.\n");
    }

    @Test
    void foldsANewRepairIntoTheExistingRepair(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base", "base");
        String base = head(repo);
        git(repo, "switch", "-c", "task");
        commit(repo, "task.txt", "task", "Task change");
        String task = head(repo);
        commit(repo, "base-fix.txt", "first\n", "Repair base");

        BaseCiHistoryRewriter.Result first = rewriter.rewrite(
                new BaseCiHistoryRewriter.Request(
                        repo, "task", base, task, List.of(task), head(repo)));
        String existingRepair = first.proof().repairCommitSha();

        commit(repo, "base-fix.txt", "first\nsecond\n", "Extend base repair");
        String appendedRepair = head(repo);
        BaseCiHistoryRewriter.Result second = rewriter.rewrite(
                new BaseCiHistoryRewriter.Request(
                        repo, "task", base, task, List.of(task), appendedRepair));

        assertThat(second.proof().repeatedRepair()).isTrue();
        assertThat(second.proof().foldedRepairInputShas())
                .containsExactly(existingRepair, appendedRepair);
        assertThat(second.proof().frozenOriginalHeadSha()).isEqualTo(task);
        assertThat(second.proof().originalCommitShas()).containsExactly(task);
        assertThat(git.commitShasInRange(repo, base, second.headSha()))
                .hasSize(2);
        assertThat(second.proof().originalCommitProofs().getFirst().inputSha())
                .isNotEqualTo(task);
        assertThat(Files.readString(
                repo.resolve("base-fix.txt"), StandardCharsets.UTF_8))
                .isEqualTo("first\nsecond\n");
    }

    @Test
    void rejectsMergeHistoryWithoutChangingHead(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "base.txt", "base", "base");
        String base = head(repo);
        git(repo, "switch", "-c", "task");
        commit(repo, "task.txt", "task", "Task change");
        String task = head(repo);
        git(repo, "switch", "-c", "side");
        commit(repo, "side.txt", "side", "Side change");
        git(repo, "switch", "task");
        commit(repo, "repair.txt", "repair", "Repair base");
        git(repo, "merge", "--no-ff", "side", "-m", "Merge side");
        String before = head(repo);

        assertThatThrownBy(() -> rewriter.rewrite(
                new BaseCiHistoryRewriter.Request(
                        repo, "task", base, task, List.of(task), before)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("linear history");
        assertThat(head(repo)).isEqualTo(before);
    }

    @Test
    void restoresTheStageTurnHeadWhenReorderingConflicts(@TempDir Path repo)
            throws Exception
    {
        init(repo);
        commit(repo, "shared.txt", "base\n", "base");
        String base = head(repo);
        git(repo, "switch", "-c", "task");
        commit(repo, "shared.txt", "task\n", "Task changes shared line");
        String task = head(repo);
        commit(repo, "shared.txt", "repair\n", "Repair changes same line");
        String stageOutput = head(repo);

        assertThatThrownBy(() -> rewriter.rewrite(
                new BaseCiHistoryRewriter.Request(
                        repo, "task", base, task,
                        List.of(task), stageOutput)))
                .isInstanceOf(RewriteFailedException.class);

        assertThat(head(repo)).isEqualTo(stageOutput);
        assertThat(Files.readString(
                repo.resolve("shared.txt"), StandardCharsets.UTF_8))
                .isEqualTo("repair\n");
        assertThat(git.statusPorcelainZ(repo)).isEmpty();
    }

    private List<String> subjects(Path repo)
            throws IOException, InterruptedException
    {
        return git.listCommits(repo, "HEAD", 100).stream()
                .map(GitRunner.CommitEntry::subject)
                .toList();
    }

    private String head(Path repo)
            throws IOException, InterruptedException
    {
        return git.headSha(repo);
    }

    private static void init(Path repo)
            throws IOException, InterruptedException
    {
        git(repo, "init", "-b", "main");
        author(repo, "Test", "test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
    }

    private static void author(Path repo, String name, String email)
            throws IOException, InterruptedException
    {
        git(repo, "config", "user.name", name);
        git(repo, "config", "user.email", email);
    }

    private static void commit(
            Path repo, String file, String content, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(
                repo.resolve(file), content + (content.endsWith("\n") ? "" : "\n"),
                StandardCharsets.UTF_8);
        git(repo, "add", ".");
        String[] paragraphs = message.split("\\n\\n", -1);
        if (paragraphs.length == 1) {
            git(repo, "commit", "-m", message);
            return;
        }
        git(repo, "commit", "-m", paragraphs[0], "-m", paragraphs[1]);
    }

    private static void git(Path repo, String... args)
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
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed (" + code + "): "
                            + output);
        }
    }
}
