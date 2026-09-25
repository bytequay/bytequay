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
package com.bytequay.agent.cli.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitWorktreeWriteRoots
{
    @TempDir
    private Path temporary;

    private Path worktree;
    private Path common;
    private Path gitDirectory;

    @BeforeEach
    void setUp()
            throws IOException
    {
        temporary = temporary.toRealPath();
        worktree = Files.createDirectory(temporary.resolve("worktree"));
        common = Files.createDirectories(temporary.resolve("repository/.git"));
        gitDirectory = Files.createDirectories(common.resolve("worktrees/task"));
        Files.createDirectory(common.resolve("objects"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDirectory + "\n");
        Files.writeString(gitDirectory.resolve("gitdir"), worktree.resolve(".git") + "\n");
        Files.writeString(gitDirectory.resolve("commondir"), "../..\n");
        Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/bytequay/task\n");
    }

    @Test
    void grantsOnlyLinkedMetadataAndExactBranchFiles()
    {
        assertThat(GitWorktreeWriteRoots.writableRoots(worktree)).containsExactly(
                worktree.toString(),
                gitDirectory.toString(),
                common.resolve("objects").toString(),
                common.resolve("refs/heads/bytequay/task").toString(),
                common.resolve("refs/heads/bytequay/task.lock").toString(),
                common.resolve("logs/refs/heads/bytequay/task").toString(),
                common.resolve("logs/refs/heads/bytequay/task.lock").toString());
    }

    @Test
    void branchChangeProducesDifferentSnapshot()
            throws IOException
    {
        var original = GitWorktreeWriteRoots.writableRoots(worktree);
        Files.writeString(gitDirectory.resolve("HEAD"), "ref: refs/heads/bytequay/another\n");
        assertThat(GitWorktreeWriteRoots.writableRoots(worktree)).isNotEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rebase-merge", "rebase-apply"})
    void grantsOnlyIndexAndObjectsForDetachedRebase(String directory)
            throws IOException
    {
        createRebase(directory);
        assertThat(GitWorktreeWriteRoots.writableRoots(worktree)).containsExactly(worktree.toString(), gitDirectory.toString(), common.resolve("objects").toString());
    }

    @Test
    void rejectsDetachedHeadWithoutRebase()
            throws IOException
    {
        Files.writeString(gitDirectory.resolve("HEAD"), "a".repeat(40));
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("exactly one rebase state");
    }

    @Test
    void rejectsAmbiguousRebaseState()
            throws IOException
    {
        createRebase("rebase-merge");
        createRebase("rebase-apply");
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("exactly one rebase state");
    }

    @ParameterizedTest
    @ValueSource(strings = {"head-name", "orig-head", "onto"})
    void rejectsSymlinkedRebaseMetadata(String name)
            throws IOException
    {
        Path rebase = createRebase("rebase-merge");
        Path replacement = temporary.resolve(name);
        Files.move(rebase.resolve(name), replacement);
        Files.createSymbolicLink(rebase.resolve(name), replacement);
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("regular file");
    }

    @Test
    void rejectsSymlinkedRebaseDirectory()
            throws IOException
    {
        Path rebase = createRebase("rebase-merge");
        Path replacement = temporary.resolve("rebase");
        Files.move(rebase, replacement);
        Files.createSymbolicLink(rebase, replacement);
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("symbolic link");
    }

    @ParameterizedTest
    @ValueSource(strings = {"head-name", "orig-head", "onto"})
    void rejectsInvalidRebaseMetadata(String name)
            throws IOException
    {
        Path rebase = createRebase("rebase-merge");
        Files.writeString(rebase.resolve(name), "refs/heads/../main");
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("unsafe");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a1234567", "ref: refs/tags/test", "ref: refs/heads/", "ref: refs/heads/../main",
            "ref: refs/heads/main.lock", "ref: refs/heads/a b", "ref: refs/heads/a\nb"})
    void rejectsDetachedOrUnsafeHead(String head)
            throws IOException
    {
        Files.writeString(gitDirectory.resolve("HEAD"), head);
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gitdir", "commondir", "HEAD"})
    void rejectsSymlinkedMetadata(String name)
            throws IOException
    {
        Path metadata = gitDirectory.resolve(name);
        Path replacement = temporary.resolve(name);
        Files.move(metadata, replacement);
        Files.createSymbolicLink(metadata, replacement);
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("regular file");
    }

    @Test
    void rejectsSymlinkedGitFile()
            throws IOException
    {
        Path replacement = temporary.resolve("pointer");
        Files.move(worktree.resolve(".git"), replacement);
        Files.createSymbolicLink(worktree.resolve(".git"), replacement);
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("regular file");
    }

    @Test
    void rejectsSymlinkInGitDirectoryPointer()
            throws IOException
    {
        Path alias = Files.createSymbolicLink(temporary.resolve("alias"), gitDirectory);
        Files.writeString(worktree.resolve(".git"), "gitdir: " + alias + "\n");
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("symbolic link");
    }

    @ParameterizedTest
    @ValueSource(strings = {"objects", "refs", "logs"})
    void rejectsSymlinkedWritableRootAncestors(String name)
            throws IOException
    {
        Path path = common.resolve(name);
        Files.deleteIfExists(path);
        Files.createSymbolicLink(path, Files.createDirectory(temporary.resolve("outside")));
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsSymlinkedBranchFile()
            throws IOException
    {
        Path branch = common.resolve("refs/heads/bytequay/task");
        Files.createDirectories(branch.getParent());
        Files.createSymbolicLink(branch, Files.writeString(temporary.resolve("main"), "protected"));
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsMismatchedBackPointer()
            throws IOException
    {
        Path other = Files.writeString(temporary.resolve("other"), "other");
        Files.writeString(gitDirectory.resolve("gitdir"), other.toString());
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("back-pointer");
    }

    @Test
    void rejectsUnrelatedCommonDirectory()
            throws IOException
    {
        Path other = Files.createDirectory(temporary.resolve("other"));
        Files.writeString(gitDirectory.resolve("commondir"), other.toString());
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("outside the common worktrees directory");
    }

    @Test
    void rejectsOversizedMetadata()
            throws IOException
    {
        Files.writeString(worktree.resolve(".git"), "x".repeat(4097));
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("too large");
    }

    @Test
    void rejectsDirectoryAtBranchFile()
            throws IOException
    {
        Files.createDirectories(common.resolve("refs/heads/bytequay/task"));
        assertThatThrownBy(() -> GitWorktreeWriteRoots.writableRoots(worktree)).hasMessageContaining("branch metadata is not a regular file");
    }

    private Path createRebase(String name)
            throws IOException
    {
        Files.writeString(gitDirectory.resolve("HEAD"), "a".repeat(40));
        Path rebase = Files.createDirectory(gitDirectory.resolve(name));
        Files.writeString(rebase.resolve("head-name"), "refs/heads/bytequay/task\n");
        Files.writeString(rebase.resolve("orig-head"), "b".repeat(40));
        Files.writeString(rebase.resolve("onto"), "a".repeat(40));
        return rebase;
    }
}
