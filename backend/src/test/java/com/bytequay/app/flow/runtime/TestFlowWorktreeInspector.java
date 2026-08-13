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

import com.bytequay.app.flow.runtime.FlowWorktreeInspector.AttachmentState;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.GitOperation;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanKind;
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
    void rejectsCleanStateAndSealsStableTrackedContent(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        assertFailure(
                FailureCode.CLEAN,
                () -> inspectNonClean(fixture, fixture.base(), fixture.base()));

        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "dirty-one\n",
                StandardCharsets.UTF_8);
        NonCleanInspection first = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        NonCleanInspection repeat = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "dirty-two\n",
                StandardCharsets.UTF_8);
        NonCleanInspection changed = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        git(fixture.repository(), "config", "core.autocrlf", "input");
        NonCleanInspection configChanged = inspectNonClean(
                fixture, fixture.base(), fixture.base());

        assertThat(first).isEqualTo(repeat);
        assertThat(first.kind()).isEqualTo(NonCleanKind.DIRTY);
        assertThat(first.attachmentState()).isEqualTo(AttachmentState.ATTACHED);
        assertThat(first.actualHeadSha()).isEqualTo(fixture.base());
        assertThat(first.branchHeadSha()).isEqualTo(fixture.base());
        assertThat(first.stateDigest()).hasSize(64);
        assertThat(changed.stateDigest()).isNotEqualTo(first.stateDigest());
        assertThat(configChanged.stateDigest())
                .isNotEqualTo(changed.stateDigest());
    }

    @Test
    void stagedAndUntrackedIdentityIsContentPathTypeAndModeSensitive(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path staged = fixture.worktree().resolve("staged.txt");
        Files.writeString(staged, "one\n", StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", "staged.txt");
        NonCleanInspection stagedOne = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.writeString(staged, "two\n", StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", "staged.txt");
        NonCleanInspection stagedTwo = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        assertThat(stagedTwo.stateDigest())
                .isNotEqualTo(stagedOne.stateDigest());

        git(fixture.worktree(), "reset", "--hard", "HEAD");
        Path untracked = fixture.worktree().resolve("loose.txt");
        Files.writeString(untracked, "one\n", StandardCharsets.UTF_8);
        NonCleanInspection looseOne = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.writeString(untracked, "two\n", StandardCharsets.UTF_8);
        NonCleanInspection looseTwo = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.move(untracked, fixture.worktree().resolve("renamed.txt"));
        NonCleanInspection renamed = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.delete(fixture.worktree().resolve("renamed.txt"));
        Files.createSymbolicLink(
                fixture.worktree().resolve("loose.txt"), Path.of("../outside"));
        NonCleanInspection symlink = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.delete(fixture.worktree().resolve("loose.txt"));
        Files.writeString(untracked, "two\n", StandardCharsets.UTF_8);
        assertThat(untracked.toFile().setExecutable(true)).isTrue();
        NonCleanInspection executable = inspectNonClean(
                fixture, fixture.base(), fixture.base());

        assertThat(List.of(
                        looseOne.stateDigest(),
                        looseTwo.stateDigest(),
                        renamed.stateDigest(),
                        symlink.stateDigest(),
                        executable.stateDigest()))
                .doesNotHaveDuplicates();
    }

    @Test
    void rawTrackedBytesCannotCollideThroughTextNormalization(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Files.writeString(
                fixture.worktree().resolve(".gitattributes"),
                "normalized.txt text\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                fixture.worktree().resolve("normalized.txt"),
                "one\ntwo\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                fixture.worktree().resolve("dirty.txt"),
                "clean\n",
                StandardCharsets.UTF_8);
        git(fixture.worktree(), "add", ".gitattributes", "normalized.txt", "dirty.txt");
        git(fixture.worktree(), "commit", "-m", "normalized files");
        String predecessor = revParse(fixture.worktree(), "HEAD");
        Files.writeString(
                fixture.worktree().resolve("dirty.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        Files.write(
                fixture.worktree().resolve("normalized.txt"),
                "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        NonCleanInspection crlf = inspectNonClean(
                fixture, fixture.base(), predecessor);
        Files.write(
                fixture.worktree().resolve("normalized.txt"),
                "one\r\ntwo\n".getBytes(StandardCharsets.UTF_8));
        NonCleanInspection mixed = inspectNonClean(
                fixture, fixture.base(), predecessor);

        assertThat(mixed.stateDigest()).isNotEqualTo(crlf.stateDigest());
    }

    @Test
    void ignoredFilesRemovedFromIndexRemainBoundByPredecessorAndCurrentHead(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture predecessor = fixture(temporaryDirectory.resolve("predecessor"));
        Files.writeString(
                predecessor.worktree().resolve(".gitignore"),
                "generated.bin\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                predecessor.worktree().resolve("generated.bin"),
                "one\n",
                StandardCharsets.UTF_8);
        git(predecessor.worktree(), "add", ".gitignore");
        git(predecessor.worktree(), "add", "-f", "generated.bin");
        git(predecessor.worktree(), "commit", "-m", "tracked generated file");
        String predecessorHead = revParse(predecessor.worktree(), "HEAD");
        git(predecessor.worktree(), "rm", "--cached", "--", "generated.bin");
        NonCleanInspection predecessorOne = inspectNonClean(
                predecessor, predecessor.base(), predecessorHead);
        Files.writeString(
                predecessor.worktree().resolve("generated.bin"),
                "two\n",
                StandardCharsets.UTF_8);
        NonCleanInspection predecessorTwo = inspectNonClean(
                predecessor, predecessor.base(), predecessorHead);
        assertThat(predecessorTwo.stateDigest())
                .isNotEqualTo(predecessorOne.stateDigest());

        Fixture current = fixture(temporaryDirectory.resolve("current"));
        Files.writeString(
                current.worktree().resolve(".gitignore"),
                "new-generated.bin\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                current.worktree().resolve("new-generated.bin"),
                "one\n",
                StandardCharsets.UTF_8);
        git(current.worktree(), "add", ".gitignore");
        git(current.worktree(), "add", "-f", "new-generated.bin");
        git(current.worktree(), "commit", "-m", "new generated file");
        git(current.worktree(), "rm", "--cached", "--", "new-generated.bin");
        NonCleanInspection currentOne = inspectNonClean(
                current, current.base(), current.base());
        Files.writeString(
                current.worktree().resolve("new-generated.bin"),
                "two\n",
                StandardCharsets.UTF_8);
        NonCleanInspection currentTwo = inspectNonClean(
                current, current.base(), current.base());
        assertThat(currentTwo.stateDigest())
                .isNotEqualTo(currentOne.stateDigest());

        Fixture ignored = fixture(temporaryDirectory.resolve("ignored"));
        Files.writeString(
                ignored.worktree().resolve(".gitignore"),
                "cache.bin\n",
                StandardCharsets.UTF_8);
        git(ignored.worktree(), "add", ".gitignore");
        git(ignored.worktree(), "commit", "-m", "ignore cache");
        String ignoredHead = revParse(ignored.worktree(), "HEAD");
        Files.writeString(
                ignored.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                ignored.worktree().resolve("cache.bin"),
                "one\n",
                StandardCharsets.UTF_8);
        NonCleanInspection ignoredOne = inspectNonClean(
                ignored, ignored.base(), ignoredHead);
        Files.writeString(
                ignored.worktree().resolve("cache.bin"),
                "two\n",
                StandardCharsets.UTF_8);
        NonCleanInspection ignoredTwo = inspectNonClean(
                ignored, ignored.base(), ignoredHead);
        assertThat(ignoredTwo.stateDigest())
                .isNotEqualTo(ignoredOne.stateDigest());
    }

    @Test
    void nulDelimitedPathsPreserveSpacesDashNewlineAndUnicode(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        List<String> tracked = List.of(
                "space name.txt", "-leading.txt", "line\nbreak.txt", "雪.txt");
        for (String name : tracked) {
            Files.writeString(
                    fixture.worktree().resolve(name),
                    "tracked\n",
                    StandardCharsets.UTF_8);
        }
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(tracked);
        git(fixture.worktree(), add.toArray(String[]::new));
        git(fixture.worktree(), "commit", "-m", "odd paths");
        String predecessor = revParse(fixture.worktree(), "HEAD");
        Files.writeString(
                fixture.worktree().resolve("line\nbreak.txt"),
                "changed\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                fixture.worktree().resolve("untracked 雪\n.txt"),
                "one\n",
                StandardCharsets.UTF_8);
        NonCleanInspection first = inspectNonClean(
                fixture, fixture.base(), predecessor);
        Files.writeString(
                fixture.worktree().resolve("untracked 雪\n.txt"),
                "two\n",
                StandardCharsets.UTF_8);
        NonCleanInspection second = inspectNonClean(
                fixture, fixture.base(), predecessor);

        assertThat(first.stateDigest()).hasSize(64);
        assertThat(second.stateDigest()).isNotEqualTo(first.stateDigest());
    }

    @Test
    void rejectsTrackedPathThroughSymlinkedParentWithoutReadingOutside(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        commit(fixture.worktree(), "dir/file.txt", "inside\n", "nested file");
        String predecessor = revParse(fixture.worktree(), "HEAD");
        Files.delete(fixture.worktree().resolve("dir/file.txt"));
        Files.delete(fixture.worktree().resolve("dir"));
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(
                outside.resolve("file.txt"),
                "outside-secret\n",
                StandardCharsets.UTF_8);
        Files.createSymbolicLink(fixture.worktree().resolve("dir"), outside);

        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        fixture, fixture.base(), predecessor));
        Files.delete(outside.resolve("file.txt"));
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        fixture, fixture.base(), predecessor));
    }

    @Test
    void commitThenDirtySealsActualNewHeadAgainstItsPredecessor(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        commit(fixture.worktree(), "task.txt", "committed\n", "task commit");
        String committed = revParse(fixture.worktree(), "HEAD");
        Files.writeString(
                fixture.worktree().resolve("task.txt"),
                "committed then dirty\n",
                StandardCharsets.UTF_8);

        NonCleanInspection inspection = inspectNonClean(
                fixture, fixture.base(), fixture.base());

        assertThat(inspection.actualHeadSha()).isEqualTo(committed);
        assertThat(inspection.branchHeadSha()).isEqualTo(committed);
        assertThat(inspection.kind()).isEqualTo(NonCleanKind.DIRTY);
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
    void sealsMergeAndControlMessageContent(@TempDir Path temporaryDirectory)
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
        assertThat(gitResult(fixture.worktree(), "merge", "main").exitCode())
                .isNotZero();

        NonCleanInspection first = inspectNonClean(
                fixture, fixture.base(), predecessor);
        Path mergeMessage = adminDirectory(fixture).resolve("MERGE_MSG");
        Files.writeString(
                mergeMessage,
                Files.readString(mergeMessage, StandardCharsets.UTF_8)
                        + "changed\n",
                StandardCharsets.UTF_8);
        NonCleanInspection changed = inspectNonClean(
                fixture, fixture.base(), predecessor);

        assertThat(first.kind())
                .isEqualTo(NonCleanKind.GIT_OPERATION_IN_PROGRESS);
        assertThat(first.operations()).contains(GitOperation.MERGE);
        assertThat(changed.stateDigest()).isNotEqualTo(first.stateDigest());
    }

    @Test
    void permitsDetachedHeadOnlyForRecognizedRebase(
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
        assertThat(gitResult(fixture.worktree(), "rebase", "main").exitCode())
                .isNotZero();

        NonCleanInspection inspection = inspectNonClean(
                fixture, fixture.base(), predecessor);

        assertThat(inspection.attachmentState())
                .isEqualTo(AttachmentState.DETACHED);
        assertThat(inspection.operations()).contains(GitOperation.REBASE);
        assertThat(inspection.branchHeadSha()).isEqualTo(predecessor);
        git(fixture.worktree(), "rebase", "--abort");
        git(fixture.worktree(), "checkout", "--detach");
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "detached dirty\n",
                StandardCharsets.UTF_8);
        assertFailure(
                FailureCode.DETACHED_HEAD,
                () -> inspectNonClean(
                        fixture, fixture.base(), fixture.base()));
    }

    @Test
    void sealsCherryPickAndSequencerMarkerBytes(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture cherry = fixture(temporaryDirectory.resolve("cherry"));
        Files.writeString(
                cherry.worktree().resolve("base.txt"),
                "task\n",
                StandardCharsets.UTF_8);
        git(cherry.worktree(), "add", "base.txt");
        git(cherry.worktree(), "commit", "-m", "task side");
        String predecessor = revParse(cherry.worktree(), "HEAD");
        Files.writeString(
                cherry.repository().resolve("base.txt"),
                "main\n",
                StandardCharsets.UTF_8);
        git(cherry.repository(), "add", "base.txt");
        git(cherry.repository(), "commit", "-m", "main side");
        String mainCommit = revParse(cherry.repository(), "HEAD");
        assertThat(gitResult(
                cherry.worktree(), "cherry-pick", mainCommit).exitCode())
                .isNotZero();
        NonCleanInspection cherryInspection = inspectNonClean(
                cherry, cherry.base(), predecessor);
        assertThat(cherryInspection.operations())
                .contains(GitOperation.CHERRY_PICK);

        Fixture sequencer = fixture(temporaryDirectory.resolve("sequencer"));
        Path sequencerDirectory = adminDirectory(sequencer).resolve("sequencer");
        Files.createDirectories(sequencerDirectory);
        Files.writeString(
                sequencerDirectory.resolve("todo"),
                "pick one\n",
                StandardCharsets.UTF_8);
        NonCleanInspection first = inspectNonClean(
                sequencer, sequencer.base(), sequencer.base());
        Files.writeString(
                sequencerDirectory.resolve("todo"),
                "pick two\n",
                StandardCharsets.UTF_8);
        NonCleanInspection second = inspectNonClean(
                sequencer, sequencer.base(), sequencer.base());
        assertThat(first.operations()).contains(GitOperation.SEQUENCER);
        assertThat(second.stateDigest()).isNotEqualTo(first.stateDigest());
    }

    @Test
    void sealsLinkedWorktreeBisectRefsAndRejectsUnsafeControlFiles(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path admin = adminDirectory(fixture);
        Path bisectRef = admin.resolve("refs/bisect/good-1");
        Files.createDirectories(bisectRef.getParent());
        Files.writeString(bisectRef, fixture.base() + "\n", StandardCharsets.UTF_8);
        NonCleanInspection first = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        Files.writeString(bisectRef, "f".repeat(40) + "\n", StandardCharsets.UTF_8);
        NonCleanInspection second = inspectNonClean(
                fixture, fixture.base(), fixture.base());
        assertThat(first.operations()).contains(GitOperation.BISECT);
        assertThat(second.stateDigest()).isNotEqualTo(first.stateDigest());

        Files.delete(bisectRef);
        Files.delete(bisectRef.getParent());
        Path sequencer = admin.resolve("sequencer");
        Files.createDirectories(sequencer);
        Files.write(sequencer.resolve("todo"), new byte[1024 * 1024 + 1]);
        assertFailure(
                FailureCode.OUTPUT_LIMIT,
                () -> inspectNonClean(
                        fixture, fixture.base(), fixture.base()));
        Files.delete(sequencer.resolve("todo"));
        ProcessResult fifo = process(sequencer, "/usr/bin/mkfifo", "todo");
        assertThat(fifo.exitCode()).isZero();
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        fixture, fixture.base(), fixture.base()));
    }

    @Test
    void rejectsSpecialWorktreeFilesIndexSymlinksAndSharedRerere(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture untracked = fixture(temporaryDirectory.resolve("untracked"));
        ProcessResult fifo = process(
                untracked.worktree(), "/usr/bin/mkfifo", "pipe");
        assertThat(fifo.exitCode()).isZero();
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        untracked, untracked.base(), untracked.base()));

        Fixture tracked = fixture(temporaryDirectory.resolve("tracked"));
        Files.delete(tracked.worktree().resolve("base.txt"));
        fifo = process(tracked.worktree(), "/usr/bin/mkfifo", "base.txt");
        assertThat(fifo.exitCode()).isZero();
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        tracked, tracked.base(), tracked.base()));

        Fixture index = fixture(temporaryDirectory.resolve("index"));
        Path admin = adminDirectory(index);
        Path outside = temporaryDirectory.resolve("outside-index");
        Files.writeString(outside, "not-an-index\n", StandardCharsets.UTF_8);
        Files.delete(admin.resolve("index"));
        Files.createSymbolicLink(admin.resolve("index"), outside);
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(index, index.base(), index.base()));

        Fixture rerere = fixture(temporaryDirectory.resolve("rerere"));
        Files.writeString(
                rerere.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        Path common = Path.of(gitResult(
                rerere.worktree(),
                "rev-parse",
                "--git-common-dir").stdout().strip());
        Files.createDirectories(common.resolve("rr-cache"));
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        rerere, rerere.base(), rerere.base()));

        Fixture malformed = fixture(temporaryDirectory.resolve("malformed"));
        Files.writeString(
                malformed.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        ProcessResult invalidTarget = process(
                malformed.worktree(),
                "/usr/bin/perl",
                "-e",
                "symlink pack('C',255), 'badlink' or die $!");
        assertThat(invalidTarget.exitCode()).isZero();
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        malformed, malformed.base(), malformed.base()));

        Fixture objects = fixture(temporaryDirectory.resolve("objects"));
        Files.writeString(
                objects.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        Path pack = objects.repository().resolve(".git/objects/pack");
        Path externalPack = temporaryDirectory.resolve("external-pack");
        Files.move(pack, externalPack);
        Files.createSymbolicLink(pack, externalPack);
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        objects, objects.base(), objects.base()));
        Files.delete(malformed.worktree().resolve("badlink"));
        Path malformedAdmin = adminDirectory(malformed);
        Files.createDirectories(malformedAdmin.resolve("sequencer"));
        ProcessResult invalidControl = process(
                malformedAdmin,
                "/usr/bin/perl",
                "-e",
                "my $n=pack('C',255); open(my $f,'>',\"sequencer/$n\")"
                        + " or die $!; print $f 'x'; close $f");
        if (invalidControl.exitCode() == 0) {
            assertFailure(
                    FailureCode.UNTRUSTED_REPOSITORY_STATE,
                    () -> inspectNonClean(
                            malformed, malformed.base(), malformed.base()));
        }

        Fixture locked = fixture(temporaryDirectory.resolve("locked"));
        Path lockedAdmin = adminDirectory(locked);
        Files.createFile(lockedAdmin.resolve("index.lock"));
        assertFailure(
                FailureCode.GIT_OPERATION_IN_PROGRESS,
                () -> inspect(locked, locked.base(), locked.base()));
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(
                        locked, locked.base(), locked.base()));
    }

    @Test
    void rejectsSplitIndexSparseStateAndWrongAttachedBranch(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture split = fixture(temporaryDirectory.resolve("split"));
        Files.writeString(
                split.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        git(split.worktree(), "update-index", "--split-index");
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(split, split.base(), split.base()));

        Fixture sparse = fixture(temporaryDirectory.resolve("sparse"));
        Files.writeString(
                sparse.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        git(sparse.repository(), "config", "core.sparseCheckout", "true");
        assertFailure(
                FailureCode.UNTRUSTED_REPOSITORY_STATE,
                () -> inspectNonClean(sparse, sparse.base(), sparse.base()));

        Fixture wrong = fixture(temporaryDirectory.resolve("wrong"));
        git(wrong.worktree(), "switch", "-c", "task/wrong");
        Files.writeString(
                wrong.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        assertFailure(
                FailureCode.WRONG_BRANCH,
                () -> inspectNonClean(wrong, wrong.base(), wrong.base()));
    }

    @Test
    void nonCleanInspectionDoesNotMutateGitState(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path admin = adminDirectory(fixture);
        Path index = admin.resolve("index");
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);
        byte[] indexBefore = Files.readAllBytes(index);
        var modifiedBefore = Files.getLastModifiedTime(index);
        String headBefore = revParse(fixture.worktree(), "HEAD");
        String branchBefore = revParse(
                fixture.worktree(), "refs/heads/" + fixture.branch());

        inspectNonClean(fixture, fixture.base(), fixture.base());

        assertThat(Files.readAllBytes(index)).isEqualTo(indexBefore);
        assertThat(Files.getLastModifiedTime(index)).isEqualTo(modifiedBefore);
        assertThat(revParse(fixture.worktree(), "HEAD")).isEqualTo(headBefore);
        assertThat(revParse(
                fixture.worktree(), "refs/heads/" + fixture.branch()))
                .isEqualTo(branchBefore);
        assertThat(admin.resolve("index.lock")).doesNotExist();
    }

    @Test
    void rejectsMovementBetweenItsTwoFullObservations(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path tracked = fixture.worktree().resolve("base.txt");
        Files.writeString(tracked, "first dirty\n", StandardCharsets.UTF_8);
        FlowWorktreeInspector movingInspector = new FlowWorktreeInspector(() -> {
            try {
                Files.writeString(
                        tracked, "second dirty\n", StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        assertFailure(
                FailureCode.MOVED_DURING_INSPECTION,
                () -> movingInspector.inspectNonClean(
                        fixture.repository(),
                        fixture.worktree(),
                        fixture.branch(),
                        fixture.base(),
                        fixture.base()));
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
    void acceptsARewrittenHeadOnlyThroughTheRewriteEntryPoint(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        commit(fixture.worktree(), "one.txt", "one\n", "pick one");
        String predecessor = revParse(fixture.worktree(), "HEAD");
        // Rebuild the same content as a different commit, exactly as a
        // program-generated rebase does to everything after its target.
        git(fixture.worktree(), "reset", "--hard", fixture.base());
        commit(fixture.worktree(), "one.txt", "one\n", "pick one rewritten");
        String rewritten = revParse(fixture.worktree(), "HEAD");

        // The ordinary rule still refuses it: a head that abandoned the last
        // adopted work is normally a lost writer turn.
        assertFailure(
                FailureCode.PREDECESSOR_NOT_ANCESTOR,
                () -> inspect(fixture, fixture.base(), predecessor));

        Inspection inspection = inspector.inspectRewritten(
                fixture.repository(),
                fixture.worktree(),
                fixture.branch(),
                fixture.base(),
                predecessor);
        assertThat(inspection.headSha()).isEqualTo(rewritten);

        // Only that one rule relaxes. A predecessor that never existed, and a
        // base that is not an ancestor, still fail closed.
        assertFailure(
                FailureCode.PREDECESSOR_NOT_FOUND,
                () -> inspector.inspectRewritten(
                        fixture.repository(),
                        fixture.worktree(),
                        fixture.branch(),
                        fixture.base(),
                        "0".repeat(40)));
        commit(fixture.repository(), "main.txt", "main\n", "main sibling");
        String sibling = revParse(fixture.repository(), "HEAD");
        assertFailure(
                FailureCode.BASE_NOT_ANCESTOR,
                () -> inspector.inspectRewritten(
                        fixture.repository(),
                        fixture.worktree(),
                        fixture.branch(),
                        sibling,
                        predecessor));
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
    void rejectsSymlinkedLinkedWorktreeRegistrationRoot(
            @TempDir Path temporaryDirectory)
            throws Exception
    {
        Fixture fixture = fixture(temporaryDirectory);
        Path registrations = fixture.repository().resolve(".git/worktrees");
        Path external = temporaryDirectory.resolve("external-registrations");
        Files.move(registrations, external);
        Files.createSymbolicLink(registrations, external);
        Files.writeString(
                fixture.worktree().resolve("base.txt"),
                "dirty\n",
                StandardCharsets.UTF_8);

        assertFailure(
                FailureCode.NOT_WORKTREE,
                () -> inspectNonClean(
                        fixture, fixture.base(), fixture.base()));
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

    private NonCleanInspection inspectNonClean(
            Fixture fixture, String base, String predecessor)
    {
        return inspector.inspectNonClean(
                fixture.repository(),
                fixture.worktree(),
                fixture.branch(),
                base,
                predecessor);
    }

    private static Path adminDirectory(Fixture fixture)
            throws Exception
    {
        return Path.of(gitResult(
                fixture.worktree(),
                "rev-parse",
                "--absolute-git-dir").stdout().strip());
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

    private static ProcessResult process(Path directory, String... arguments)
            throws IOException, InterruptedException
    {
        Process process = new ProcessBuilder(arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new ProcessResult(process.waitFor(), output);
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

    private record ProcessResult(int exitCode, String stdout) {}
}
