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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

final class TestFlowWorktreeInspector
{
    private final FlowWorktreeInspector inspector = new FlowWorktreeInspector();

    @Test
    void repeatedlyInspectsOneCleanLinkedWorktree(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        commit(fixture.worktree(), "task.txt", "task\n", "task change");

        Inspection first = inspect(fixture, fixture.base(), fixture.base());
        Inspection second = inspect(fixture, fixture.base(), fixture.base());

        assertThat(first).isEqualTo(second);
        assertThat(first.headSha()).isEqualTo(revParse(fixture.worktree(), "HEAD"));
        assertThat(first.headTreeDigest()).hasSize(64);
        assertThat(first.baseToHeadDiffDigest()).hasSize(64);
        assertThat(first.differsFromBase()).isTrue();
    }

    @Test
    void rejectsTrackedStagedAndUntrackedDirt(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);

        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "tracked dirt\n",
                StandardCharsets.UTF_8);
        assertFailure(FailureCode.DIRTY, () -> inspect(fixture, fixture.base(), fixture.base()));
        git(fixture.worktree(), "reset", "--hard", "HEAD");

        Files.writeString(
                fixture.worktree().resolve("staged.txt"),
                "staged\n",
                StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", "staged.txt");
        assertFailure(FailureCode.DIRTY, () -> inspect(fixture, fixture.base(), fixture.base()));
        git(fixture.worktree(), "reset", "--hard", "HEAD");
        git(fixture.worktree(), "clean", "-fd");

        Files.writeString(
                fixture.worktree().resolve("untracked.txt"),
                "untracked\n",
                StandardCharsets.UTF_8);
        assertFailure(FailureCode.DIRTY, () -> inspect(fixture, fixture.base(), fixture.base()));
    }

    @Test
    void rejectsAnActualMergeInProgressBeforeOrdinaryDirt(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "task\n",
                StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", "base.txt");
        git(fixture.worktree(), "commit", "-m", "task side");
        String predecessor = revParse(fixture.worktree(), "HEAD");

        Files.writeString(
                fixture.repository().resolve("base.txt"),
                "main\n",
                StandardCharsets.UTF_8);
        git(fixture.repository(), "add", "base.txt");
        git(fixture.repository(), "commit", "-m", "main side");
        assertThat(gitResult(fixture.worktree(), "merge", "main").exitCode()).isNotZero();

        assertFailure(
                FailureCode.GIT_OPERATION_IN_PROGRESS,
                () -> inspect(fixture, fixture.base(), predecessor));

        git(fixture.worktree(), "merge", "--abort");
        assertThat(gitResult(fixture.worktree(), "rebase", "main").exitCode()).isNotZero();
        assertFailure(
                FailureCode.GIT_OPERATION_IN_PROGRESS,
                () -> inspect(fixture, fixture.base(), predecessor));
    }

    @Test
    void rejectsDetachedWrongBranchAndUnrelatedRepository(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        git(fixture.worktree(), "checkout", "--detach");
        assertFailure(
                FailureCode.DETACHED_HEAD,
                () -> inspect(fixture, fixture.base(), fixture.base()));

        git(fixture.worktree(), "switch", fixture.branch());
        git(fixture.worktree(), "switch", "-c", "task/other");
        assertFailure(
                FailureCode.WRONG_BRANCH,
                () -> inspect(fixture, fixture.base(), fixture.base()));

        Path clone = temporaryDirectory.resolve("unrelated-clone");
        git(temporaryDirectory, "clone", fixture.repository().toString(), clone.toString());
        assertFailure(
                FailureCode.WRONG_REPOSITORY,
                () -> inspector.inspect(
                        fixture.repository(),
                        clone,
                        "main",
                        fixture.base(),
                        fixture.base()));
    }

    @Test
    void distinguishesMissingAndSiblingBaseOrPredecessor(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        commit(fixture.repository(), "main.txt", "main\n", "main sibling");
        String sibling = revParse(fixture.repository(), "HEAD");
        commit(fixture.worktree(), "task.txt", "task\n", "task child");

        assertFailure(
                FailureCode.BASE_NOT_ANCESTOR,
                () -> inspect(fixture, sibling, fixture.base()));
        assertFailure(
                FailureCode.PREDECESSOR_NOT_ANCESTOR,
                () -> inspect(fixture, fixture.base(), sibling));
        assertFailure(
                FailureCode.BASE_NOT_FOUND,
                () -> inspect(fixture, "0000000000000000000000000000000000000000", fixture.base()));
        assertFailure(
                FailureCode.PREDECESSOR_NOT_FOUND,
                () -> inspect(fixture, fixture.base(), "0000000000000000000000000000000000000000"));
    }

    @Test
    void treeDigestsTrackContentRenameAndModeButNotEmptyCommit(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "changed\n",
                StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", "base.txt");
        git(fixture.worktree(), "commit", "-m", "content");
        Inspection content = inspect(fixture, fixture.base(), fixture.base());

        git(fixture.worktree(), "mv", "base.txt", "renamed.txt");
        git(fixture.worktree(), "commit", "-m", "rename");
        Inspection rename = inspect(fixture, fixture.base(), fixture.base());

        assertThat(fixture.worktree().resolve("renamed.txt").toFile().setExecutable(true))
                .isTrue();
        git(fixture.worktree(), "update-index", "--chmod=+x", "renamed.txt");
        git(fixture.worktree(), "commit", "-m", "mode");
        Inspection mode = inspect(fixture, fixture.base(), fixture.base());

        git(fixture.worktree(), "commit", "--allow-empty", "-m", "empty");
        Inspection empty = inspect(fixture, fixture.base(), fixture.base());

        assertThat(List.of(
                        content.headTreeDigest(),
                        rename.headTreeDigest(),
                        mode.headTreeDigest()))
                .doesNotHaveDuplicates();
        assertThat(List.of(
                        content.baseToHeadDiffDigest(),
                        rename.baseToHeadDiffDigest(),
                        mode.baseToHeadDiffDigest()))
                .doesNotHaveDuplicates();
        assertThat(empty.headSha()).isNotEqualTo(mode.headSha());
        assertThat(empty.headTreeDigest()).isEqualTo(mode.headTreeDigest());
        assertThat(empty.baseToHeadDiffDigest()).isEqualTo(mode.baseToHeadDiffDigest());
    }

    @Test
    void rejectsLegacyGraftsAndRefsAsCommitInputs(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        assertFailure(
                FailureCode.INVALID_INPUT,
                () -> inspector.inspect(
                        fixture.repository(),
                        fixture.worktree(),
                        fixture.branch(),
                        "HEAD",
                        fixture.base()));

        Path grafts = fixture.repository().resolve(".git/info/grafts");
        Files.createDirectories(grafts.getParent());
        Files.writeString(grafts, fixture.base() + "\n", StandardCharsets.UTF_8);
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
    }

    @Test
    void rejectsExternalCleanFilterBeforeItCanRun(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Files.writeString(
                fixture.worktree().resolve(".gitattributes"),
                "*.txt filter=sleeping\n",
                StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", ".gitattributes");
        git(fixture.worktree(), "commit", "-m", "attributes");

        Path invoked = temporaryDirectory.resolve("filter-invoked");
        Path filter = temporaryDirectory.resolve("filter.sh");
        Files.writeString(
                filter,
                "#!/bin/sh\nprintf invoked > '" + invoked + "'\nsleep 60\ncat\n",
                StandardCharsets.UTF_8);
        assertThat(filter.toFile().setExecutable(true)).isTrue();
        git(fixture.repository(), "config", "filter.sleeping.clean", filter.toString());

        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
        assertThat(invoked).doesNotExist();
    }

    @Test
    void rejectsPromisorObjectHelperBeforeObjectPeeling(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path invoked = temporaryDirectory.resolve("object-helper-invoked");
        Path helper = temporaryDirectory.resolve("object-helper.sh");
        Files.writeString(
                helper,
                "#!/bin/sh\nprintf invoked > '" + invoked + "'\nexit 1\n",
                StandardCharsets.UTF_8);
        assertThat(helper.toFile().setExecutable(true)).isTrue();
        git(
                fixture.repository(),
                "config",
                "core.alternateRefsCommand",
                helper.toString());
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
        assertThat(invoked).doesNotExist();
        git(fixture.repository(), "config", "--unset", "core.alternateRefsCommand");

        git(fixture.repository(), "config", "remote.origin.promisor", "true");
        git(
                fixture.repository(),
                "config",
                "remote.origin.partialCloneFilter",
                "blob:none");
        git(fixture.repository(), "config", "remote.origin.uploadpack", helper.toString());

        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
        assertThat(invoked).doesNotExist();

        git(fixture.repository(), "config", "--remove-section", "remote.origin");
        Path promisorMarker = fixture.repository().resolve(".git/objects/pack/test.promisor");
        Files.write(promisorMarker, new byte[0]);
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
    }

    @Test
    void rejectsGitlinksWithoutEnteringTheSubmodule(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path child = temporaryDirectory.resolve("child");
        Files.createDirectories(child);
        git(child, "init", "-b", "main");
        git(child, "config", "user.name", "ByteQuay Test");
        git(child, "config", "user.email", "test@bytequay.invalid");
        commit(child, "child.txt", "child\n", "child");
        String childHead = revParse(child, "HEAD");

        git(
                fixture.worktree(),
                "update-index",
                "--add",
                "--cacheinfo",
                "160000",
                childHead,
                "vendor/child");
        git(fixture.worktree(), "commit", "-m", "gitlink");

        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
    }

    @Test
    void rejectsAssumeUnchangedAndSkipWorktreeEntries(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        git(fixture.worktree(), "update-index", "--assume-unchanged", "base.txt");
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "hidden assume unchanged\n",
                StandardCharsets.UTF_8);
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));

        git(fixture.worktree(), "update-index", "--no-assume-unchanged", "base.txt");
        git(fixture.worktree(), "reset", "--hard", "HEAD");
        git(fixture.worktree(), "update-index", "--skip-worktree", "base.txt");
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "hidden skip worktree\n",
                StandardCharsets.UTF_8);
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
    }

    @Test
    void rejectsPrimaryCheckoutAndTamperedLinkedRegistration(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        assertFailure(
                FailureCode.NOT_WORKTREE,
                () -> inspector.inspect(
                        fixture.repository(),
                        fixture.repository(),
                        "main",
                        fixture.base(),
                        fixture.base()));

        Path adminDirectory = Path.of(
                gitResult(fixture.worktree(), "rev-parse", "--absolute-git-dir")
                        .stdout()
                        .strip());
        Files.writeString(
                adminDirectory.resolve("gitdir"),
                fixture.repository().resolve(".git") + "\n",
                StandardCharsets.UTF_8);
        assertFailure(
                FailureCode.NOT_WORKTREE,
                () -> inspect(fixture, fixture.base(), fixture.base()));
    }

    @Test
    void ignoresConfiguredSleepingFsmonitor(@TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path invoked = temporaryDirectory.resolve("fsmonitor-invoked");
        Path monitor = temporaryDirectory.resolve("fsmonitor.sh");
        Files.writeString(
                monitor,
                "#!/bin/sh\nprintf invoked > '" + invoked + "'\nsleep 60\n",
                StandardCharsets.UTF_8);
        assertThat(monitor.toFile().setExecutable(true)).isTrue();
        git(fixture.repository(), "config", "core.fsmonitor", monitor.toString());

        Inspection result = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> inspect(fixture, fixture.base(), fixture.base()));

        assertThat(result.headSha()).isEqualTo(fixture.base());
        assertThat(invoked).doesNotExist();
    }

    private Inspection inspect(Fixture fixture, String base, String predecessor)
    {
        return inspector.inspect(
                fixture.repository(),
                fixture.worktree(),
                fixture.branch(),
                base,
                predecessor);
    }

    private static Fixture fixture(Path temporaryDirectory)
            throws Exception
    {
        Path repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.name", "ByteQuay Test");
        git(repository, "config", "user.email", "test@bytequay.invalid");
        commit(repository, "base.txt", "base\n", "base");
        String base = revParse(repository, "HEAD");
        String branch = "task/test";
        Path worktree = temporaryDirectory.resolve("task-worktree");
        git(repository, "worktree", "add", "-b", branch, worktree.toString(), base);
        return new Fixture(repository, worktree, base, branch);
    }

    private static void commit(Path repository, String path, String content, String message)
            throws Exception
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        git(repository, "add", path);
        git(repository, "commit", "-m", message);
    }

    private static String revParse(Path repository, String revision)
            throws Exception
    {
        return gitResult(repository, "rev-parse", revision).stdout().strip();
    }

    private static void git(Path repository, String... arguments)
            throws Exception
    {
        GitResult result = gitResult(repository, arguments);
        if (result.exitCode() != 0) {
            throw new AssertionError(result.stdout());
        }
    }

    private static GitResult gitResult(Path repository, String... arguments)
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        return new GitResult(exitCode, output);
    }

    private static void assertFailure(FailureCode expected, Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        InspectionFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(expected));
    }

    private record Fixture(Path repository, Path worktree, String base, String branch) {}

    private record GitResult(int exitCode, String stdout) {}
}
