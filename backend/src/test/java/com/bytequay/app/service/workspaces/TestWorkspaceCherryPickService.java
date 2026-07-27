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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageStateMachine;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private final TaskStore tasks = mock(TaskStore.class);
    private final ThreadStore trunks = mock(ThreadStore.class);
    private final ThreadService threadService = mock(ThreadService.class);
    private final StageStateMachine stages = mock(StageStateMachine.class);
    private final ThreadTurnScheduler scheduler =
            mock(ThreadTurnScheduler.class);
    private final AgentRunService runs = mock(AgentRunService.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final WorkspaceCherryPickService service =
            new WorkspaceCherryPickService(
                    resolver,
                    watchedRepos,
                    git,
                    tasks,
                    trunks,
                    threadService,
                    stages,
                    scheduler,
                    runs,
                    ids);

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
    void conflictKeepsTheWorktreeAndSchedulesACiFixInTheSourceTrunk()
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
        Thread trunk = trunk();
        Task sourceTask = mock(Task.class);
        when(sourceTask.threadId()).thenReturn(trunk.id());
        when(tasks.findTaskByBranch("feature"))
                .thenReturn(Optional.of(sourceTask));
        when(trunks.findThreadById(trunk.id()))
                .thenReturn(Optional.of(trunk));
        when(tasks.maxSeqForThread(trunk.id()))
                .thenReturn(Optional.of(2L));
        when(ids.newTaskId(trunk.id(), 3L))
                .thenReturn("task-conflict");
        StageInstance stage = new StageInstance(
                UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "task-conflict",
                StageType.CI_FIXING_STAGE,
                StageState.OPEN,
                Instant.parse("2026-07-17T00:00:00Z"),
                null,
                null);
        when(stages.ensureRunOpen(
                "task-conflict", AgentRun.KIND_CI_FIX,
                StageType.CI_FIXING_STAGE, null))
                .thenReturn(stage);
        AgentRun run = run(trunk.id(), stage.id().toString());
        when(runs.openSchedulerSession(
                eq(trunk),
                eq("task-conflict"),
                eq(stage.id().toString()),
                eq(AgentRun.KIND_CI_FIX),
                anyString()))
                .thenReturn(run);

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
        assertThat(result.trunkId()).isEqualTo(trunk.id());
        assertThat(result.taskId()).isEqualTo("task-conflict");
        assertThat(result.sessionId()).isEqualTo(run.id());
        ArgumentCaptor<Task> savedTask =
                ArgumentCaptor.forClass(Task.class);
        verify(tasks).saveTask(savedTask.capture());
        assertThat(savedTask.getValue().threadId())
                .isEqualTo(trunk.id());
        assertThat(savedTask.getValue().branchName())
                .startsWith("cherry-pick/main-");
        assertThat(savedTask.getValue().worktreePath())
                .isEqualTo(result.worktreePath());
        verify(scheduler).enqueueStageTurn(
                eq(trunk),
                startsWith("Resolve the in-progress cherry-pick"),
                eq("task-conflict"),
                eq(stage.id().toString()),
                any(),
                eq(run.id()),
                eq(TurnLiveness.CODE));
        verify(git, never()).worktreeRemove(
                eq(main), any(Path.class));
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
                "Commit " + sha);
    }

    private static Thread trunk()
    {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new Thread(
                "trunk-source",
                ThreadKind.CLI_AGENT,
                "claude-code",
                null,
                "Source branch",
                ThreadStatus.IDLE,
                "sonnet",
                0,
                0,
                0,
                now,
                now,
                null,
                null,
                ThreadFlow.BUILD,
                WORKSPACE_ID,
                null);
    }

    private static AgentRun run(String threadId, String stageId)
    {
        return new AgentRun(
                "run-conflict",
                "task-conflict",
                AgentRun.KIND_CI_FIX,
                AgentRun.SOURCE_SCHEDULED,
                null,
                null,
                stageId,
                AgentRun.STATUS_QUEUED,
                0,
                null,
                null,
                null,
                Instant.parse("2026-07-17T00:00:00Z"),
                null,
                WORKSPACE_ID,
                threadId,
                "claude-code",
                "sonnet",
                0,
                0,
                0,
                0,
                "Resolve conflict",
                null,
                null);
    }
}
