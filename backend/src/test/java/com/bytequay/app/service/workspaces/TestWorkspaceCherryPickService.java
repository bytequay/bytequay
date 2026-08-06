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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceCherryPickService
{
    private static final String WORKSPACE_ID = "ws-widget";

    @TempDir
    private Path tempDir;

    private final WorkspaceRepositoryResolver resolver =
            mock(WorkspaceRepositoryResolver.class);
    private final WatchedRepoStore watchedRepos =
            mock(WatchedRepoStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final WorkspaceCherryPickService service =
            new WorkspaceCherryPickService(
                    resolver,
                    watchedRepos,
                    git);

    @Test
    void cherryPicksAContiguousSelectionOldestFirstAndRemovesWorktree()
            throws Exception
    {
        Path main = prepareRepository();
        when(git.refExists(main, "feature")).thenReturn(true);
        when(git.refExists(main, "main")).thenReturn(true);
        when(git.listCommits(main, "feature", 5_000))
                .thenReturn(List.of(
                        commit("cccc"),
                        commit("bbbb"),
                        commit("aaaa")));
        resolve(main, "cccc");
        resolve(main, "bbbb");
        when(git.cherryPick(any(Path.class), eq(List.of("bbbb", "cccc"))))
                .thenReturn(new GitRunner.CherryPickOutcome(
                        true, 2, null, List.of(), null));

        WorkspaceCherryPickService.CherryPickResult result =
                service.cherryPick(
                        WORKSPACE_ID,
                        "feature",
                        "main",
                        List.of("cccc", "bbbb"));

        assertThat(result.status()).isEqualTo("done");
        assertThat(result.commits()).containsExactly("bbbb", "cccc");
        assertThat(result.appliedCount()).isEqualTo(2);
        assertThat(result.worktreePath()).isNull();
        ArgumentCaptor<Path> worktree = ArgumentCaptor.forClass(Path.class);
        verify(git).worktreeAdd(
                eq(main),
                worktree.capture(),
                startsWith("cherry-pick/main-"),
                eq("main"));
        verify(git).cherryPick(
                worktree.getValue(), List.of("bbbb", "cccc"));
        verify(git).worktreeRemove(main, worktree.getValue());
    }

    @Test
    void rejectsANonContiguousDisplayedRangeBeforeCreatingAWorktree()
            throws Exception
    {
        Path main = prepareRepository();
        when(git.refExists(main, "feature")).thenReturn(true);
        when(git.refExists(main, "main")).thenReturn(true);
        when(git.listCommits(main, "feature", 5_000))
                .thenReturn(List.of(
                        commit("dddd"),
                        commit("cccc"),
                        commit("bbbb"),
                        commit("aaaa")));
        resolve(main, "dddd");
        resolve(main, "bbbb");

        assertThatThrownBy(() -> service.cherryPick(
                WORKSPACE_ID,
                "feature",
                "main",
                List.of("dddd", "bbbb")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contiguous displayed range");

        verify(git, never()).worktreeAdd(
                any(Path.class),
                any(Path.class),
                anyString(),
                anyString());
    }

    @Test
    void conflictKeepsTheWorktreeForManualResolution()
            throws Exception
    {
        Path main = prepareRepository();
        when(git.refExists(main, "feature")).thenReturn(true);
        when(git.refExists(main, "main")).thenReturn(true);
        when(git.listCommits(main, "feature", 5_000))
                .thenReturn(List.of(commit("bbbb"), commit("aaaa")));
        resolve(main, "bbbb");
        when(git.cherryPick(any(Path.class), eq(List.of("bbbb"))))
                .thenReturn(new GitRunner.CherryPickOutcome(
                        false,
                        0,
                        "bbbb",
                        List.of("src/Widget.java"),
                        "merge conflict"));
        WorkspaceCherryPickService.CherryPickResult result =
                service.cherryPick(
                        WORKSPACE_ID,
                        "feature",
                        "main",
                        List.of("bbbb"));

        assertThat(result.status()).isEqualTo("conflicted");
        assertThat(result.conflictPaths())
                .containsExactly("src/Widget.java");
        assertThat(result.worktreePath()).isNotBlank();
        assertThat(result.trunkId()).isNull();
        assertThat(result.taskId()).isNull();
        assertThat(result.sessionId()).isNull();
        assertThat(result.message())
                .contains("Abort to undo it")
                .contains(result.worktreePath());
        verify(git, never()).worktreeRemove(
                eq(main), any(Path.class));
    }

    @Test
    void abortUndoesTheConflictAndTakesTheThrowawayBranchWithIt()
            throws Exception
    {
        Path main = prepareRepository();
        String operationId = "8981198a-776b-4181-bf96-0f1bdc396989";
        Path worktree = retainedWorktree(main, operationId);
        when(git.currentBranch(worktree)).thenReturn("cherry-pick/main-8981198a");

        WorkspaceCherryPickService.CherryPickResult result =
                service.abort(WORKSPACE_ID, operationId);

        assertThat(result.status()).isEqualTo("aborted");
        assertThat(result.resultBranch()).isEqualTo("cherry-pick/main-8981198a");
        verify(git).abortInProgressOperationForRepair(worktree);
        verify(git).worktreeRemove(main, worktree);
        verify(git).deleteBranches(main, List.of("cherry-pick/main-8981198a"));
    }

    @Test
    void abortNeverDeletesABranchItDidNotCreate()
            throws Exception
    {
        Path main = prepareRepository();
        String operationId = "8981198a-776b-4181-bf96-0f1bdc396989";
        Path worktree = retainedWorktree(main, operationId);
        // A worktree that somehow has a real branch checked out: drop the
        // worktree, but deleting master is never this operation's business.
        when(git.currentBranch(worktree)).thenReturn("master");

        WorkspaceCherryPickService.CherryPickResult result =
                service.abort(WORKSPACE_ID, operationId);

        assertThat(result.resultBranch()).isNull();
        verify(git).worktreeRemove(main, worktree);
        verify(git, never()).deleteBranches(eq(main), anyList());
    }

    @Test
    void abortRejectsAnOperationIdThatIsNotAnId()
    {
        prepareRepository();

        assertThatThrownBy(() -> service.abort(WORKSPACE_ID, "../../../etc"))
                .hasMessageContaining("not a cherry-pick id");
    }

    @Test
    void abortReportsWhenNothingWasRetained()
    {
        prepareRepository();

        assertThatThrownBy(() -> service.abort(
                WORKSPACE_ID, "8981198a-776b-4181-bf96-0f1bdc396989"))
                .hasMessageContaining("no retained cherry-pick worktree");
    }

    private static Path retainedWorktree(Path main, String operationId)
            throws Exception
    {
        Path worktree = main.resolveSibling(main.getFileName() + ".bytequay-worktrees")
                .resolve("cherry-pick")
                .resolve(operationId);
        Files.createDirectories(worktree);
        return worktree.toAbsolutePath().normalize();
    }

    private Path prepareRepository()
    {
        Path main = tempDir.resolve("widget")
                .toAbsolutePath()
                .normalize();
        when(resolver.resolve(WORKSPACE_ID))
                .thenReturn(new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        when(watchedRepos.find("acme", "widget"))
                .thenReturn(Optional.of(new WatchedRepo(
                        1,
                        "acme",
                        "widget",
                        0,
                        main.toString(),
                        null,
                        null)));
        return main;
    }

    private void resolve(Path main, String sha)
            throws Exception
    {
        when(git.resolveCommitSha(main, sha))
                .thenReturn(Optional.of(sha));
    }

    private static GitRunner.CommitEntry commit(String sha)
    {
        return new GitRunner.CommitEntry(
                sha,
                sha,
                "Agent",
                "agent@example.test",
                "2026-07-17T00:00:00Z",
                "2026-07-17T00:00:00Z",
                "Commit " + sha);
    }
}
