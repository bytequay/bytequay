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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.bytequay.app.web.PatResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for {@link TaskService#shipAndContinue} — the
 * reap step in particular. Without it, every shipped task leaves a
 * {@code .worktrees/<task-id>/} directory on disk forever; the
 * design row says "once a Task's PR is open, its worktree can be
 * pruned to reclaim disk". This test pins both halves of the fix:
 * the persisted shipped row drops its stale worktree pointer, and
 * the on-disk worktree + local branch are removed.
 */
class TestTaskServiceShipAndContinue
{
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final WatchedRepoStore watchedRepoStore = mock(WatchedRepoStore.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final PatResolver patResolver = mock(PatResolver.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final TaskService service = new TaskService(
            threadStore, taskStore, watchedRepoStore, worktreeService,
            git, pullRequests, patResolver,
            registry, workspaces, notifications, mapper);

    @Test
    void shipAndContinueReapsTheShippedWorktreeAndClearsItsPathOnTheRow()
            throws Exception
    {
        String workingDir = "/tmp/acme/widget";
        String shippedWorktreePath = "/tmp/acme/widget/.worktrees/task-1";
        String shippedBranchName = "dev/task-1-fix-the-thing";

        when(threadStore.findThreadById("thread-1")).thenReturn(Optional.of(thread("thread-1")));
        Task shipped = task("task-1", "thread-1", 1L, shippedBranchName,
                shippedWorktreePath, workingDir);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(shipped));
        when(taskStore.findActiveTaskForThread("thread-1")).thenReturn(Optional.of(shipped));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1L, "acme", "widget", 0,
                        workingDir, /* upstreamRemoteName */ null, /* viewFocus */ null)));
        when(workspaces.findDefaultBaseBranch(anyString(), anyString())).thenReturn(Optional.empty());
        when(git.defaultBranch(any(Path.class))).thenReturn(Optional.of("main"));
        when(git.hasUncommittedChanges(any(Path.class))).thenReturn(false);
        when(patResolver.resolve("acme/widget")).thenReturn("ghp_secret");
        when(pullRequests.createPullRequest(eq("ghp_secret"), any(RepoRef.class), any(CreatePullRequestCommand.class)))
                .thenReturn(prWithNumber(42));
        when(worktreeService.create(any(Path.class), anyString(), anyString()))
                .thenReturn(Optional.of(new WorktreeService.WorktreeHandle(
                        Path.of("/tmp/acme/widget/.worktrees/task-2"), "dev/task-2")));
        when(registry.find("thread-1")).thenReturn(Optional.empty());

        Task next = service.shipAndContinue("thread-1", "task-1",
                new TaskService.ShipRequest("Next task", TaskService.BaseMode.MAIN));

        // 1. The worktree gets reaped with the shipped task's exact
        //    paths — same arg shape the design row prescribes.
        verify(worktreeService).remove(
                eq(Path.of(workingDir)),
                eq(shippedWorktreePath),
                eq(shippedBranchName));

        // 2. The shipped row persists with worktreePath = null — no
        //    stale pointer to a directory that's just been removed.
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).saveTask(saved.capture());
        Task shippedAfter = saved.getAllValues().stream()
                .filter(t -> t.id().equals("task-1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("shipped task was never persisted"));
        assertThat(shippedAfter.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(shippedAfter.worktreePath()).isNull();
        // Branch + PR number are preserved as historical record — the
        // branch still exists on the remote even though we deleted the
        // local ref, and the PR number is the user-visible artifact of
        // this ship.
        assertThat(shippedAfter.branchName()).isEqualTo(shippedBranchName);
        assertThat(shippedAfter.prNumber()).isEqualTo(42);
        assertThat(shippedAfter.linkedPrNumber()).isEqualTo(42);

        // 3. The new task is the seq+1 the caller gets back. Sanity
        //    check — without it a bug in step ordering could quietly
        //    persist the new task before reaping the old one's path.
        assertThat(next.seq()).isEqualTo(2L);
        assertThat(next.status()).isEqualTo(TaskStatus.PENDING);
    }

    private static Thread thread(String id)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Test thread", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD, null);
    }

    private static Task task(
            String id, String threadId, long seq,
            String branchName, String worktreePath, String workingDir)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                id, threadId, seq, TaskStatus.RUNNING,
                branchName, worktreePath, /* baseBranch */ "main", workingDir,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                now, /* endedAt */ null, /* errorMessage */ null);
    }

    private static PullRequest prWithNumber(int number)
    {
        return new PullRequest(
                /* id */ 1L, "acme/widget", number, "Test PR", "alice",
                "https://github.com/acme/widget/pull/" + number,
                Instant.parse("2026-05-22T12:00:00Z"),
                Instant.parse("2026-05-22T12:00:00Z"),
                PullRequest.Origin.AUTHORED,
                List.of(), Map.of(), /* draft */ false,
                /* viewedAt */ null, /* reviewedAt */ null,
                /* handledAction */ null,
                List.of(),
                /* ciStatus */ null,
                /* additions */ 0, /* deletions */ 0, /* commentCount */ 0,
                /* attentionReason */ null,
                /* state */ "open",
                /* closedAt */ null, /* mergedAt */ null,
                /* mergeable */ null, /* mergeableState */ null,
                /* headPushedAt */ null,
                /* reviewerVerdicts */ Map.of(),
                /* snoozedUntil */ null, /* snoozeWakeReason */ null,
                /* headRef */ "dev/task-1");
    }
}
