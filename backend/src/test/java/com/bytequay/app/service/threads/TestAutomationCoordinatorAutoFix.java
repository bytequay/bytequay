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

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.service.workspaces.WorkspaceService.DEFAULT_WORKSPACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for the auto-fix branch of
 * {@link AutomationCoordinator#scanForFailingCi}. All collaborators
 * are mocked so we can drive the coordinator straight to the
 * enqueue-or-defer decision and verify the {@link ThreadTurnScheduler}
 * call (or lack of one).
 *
 * <p>End-to-end exercise of the CI-fail → notification path lives
 * under the {@link AutomationCoordinator}'s integration coverage and
 * the data-plane tests in the repository-store package.
 */
class TestAutomationCoordinatorAutoFix
{
    private static final String REPO = "acme/widgets";
    private static final String CLONE_PATH = "/tmp/acme-widgets";
    private static final String WORKTREE_PATH = "/tmp/acme-widgets/.worktrees/task-1";
    private static final int PR_NUMBER = 42;
    private static final long PR_ID = 9001L;

    private final WorktreeLeaseService leaseService = mock(WorktreeLeaseService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WatchedRepoStore watchedRepoStore = mock(WatchedRepoStore.class);
    private final PullRequestStore pullRequestStore = mock(PullRequestStore.class);
    private final PrDetailStore prDetailStore = mock(PrDetailStore.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enqueuesAnAutoFixTurnWhenOptedInAndThreadIdle()
    {
        Task task = newTask("task-1", "thread-1");
        Thread thread = newThread("thread-1", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        ArgumentCaptor<Thread> threadArg = ArgumentCaptor.forClass(Thread.class);
        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueTurn(threadArg.capture(), promptArg.capture());
        assertThat(threadArg.getValue().id()).isEqualTo("thread-1");
        assertThat(promptArg.getValue())
                .contains("CI is failing")
                .contains(REPO)
                .contains("#" + PR_NUMBER)
                .contains("backend-tests");
    }

    @Test
    void skipsWhenRepoNotOptedIn()
    {
        Task task = newTask("task-2", "thread-2");
        Thread thread = newThread("thread-2", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ false, /* leaseHeld */ false);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(scheduler, never()).enqueueTurn(any(), anyString());
    }

    @Test
    void skipsWhenTheWorktreeIsHeldByAnotherAgent()
    {
        Task task = newTask("task-3", "thread-3");
        Thread thread = newThread("thread-3", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ true, /* leaseHeld */ true);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(scheduler, never()).enqueueTurn(any(), anyString());
    }

    @Test
    void defersWhenTheOwningThreadIsBusy()
    {
        Task task = newTask("task-4", "thread-4");
        // RUNNING means the user is mid-turn — auto-fix must not
        // interrupt; the next sweep retries.
        Thread thread = newThread("thread-4", ThreadStatus.RUNNING);
        wireFailingCi(task, thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(scheduler, never()).enqueueTurn(any(), anyString());
    }

    @Test
    void doesNotEnqueueTwiceWithinTheSameProcess()
    {
        Task task = newTask("task-5", "thread-5");
        Thread thread = newThread("thread-5", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();
        // Second sweep: still failing, but the dedup set remembers we
        // already queued. The notification path already short-circuits
        // via hasOpenNotificationForTask once an UNREAD row exists;
        // re-arm that side of the world so we can re-enter tryAutoFix
        // and prove the dedup guards the enqueue side too.
        when(notificationService.listUnread()).thenReturn(List.of());

        coordinator.scanForFailingCi();

        // Exactly one enqueueTurn across the two sweeps.
        verify(scheduler).enqueueTurn(any(), anyString());
    }

    private AutomationCoordinator newCoordinator()
    {
        return new AutomationCoordinator(
                leaseService, taskStore, threadStore, watchedRepoStore,
                pullRequestStore, prDetailStore, notificationService,
                workspaceStore, scheduler, mapper);
    }

    private void wireFailingCi(Task task, Thread thread, boolean autoFixEnabled, boolean leaseHeld)
    {
        when(taskStore.listWithLinkedPr(anyInt())).thenReturn(List.of(task));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(/* id */ 1L, "acme", "widgets", /* displayOrder */ 0,
                        CLONE_PATH, /* upstreamRemoteName */ null, /* viewFocus */ "fork")));
        when(pullRequestStore.findIdByRepoAndNumber(eq(REPO), eq(PR_NUMBER)))
                .thenReturn(Optional.of(PR_ID));
        when(prDetailStore.find(eq(PR_ID))).thenReturn(Optional.of(detailWithFailingCi()));
        when(notificationService.listUnread()).thenReturn(List.of());
        when(workspaceStore.findRepo(eq(DEFAULT_WORKSPACE_ID), eq(REPO)))
                .thenReturn(Optional.of(new WorkspaceRepo(
                        DEFAULT_WORKSPACE_ID, REPO, /* defaultBaseBranch */ null,
                        autoFixEnabled, Instant.parse("2026-05-15T12:00:00Z"))));
        when(leaseService.isHeld(eq(WORKTREE_PATH))).thenReturn(leaseHeld);
        when(threadStore.findThreadById(eq(thread.id()))).thenReturn(Optional.of(thread));
        when(scheduler.enqueueTurn(any(), anyString())).thenReturn("turn-mock-id");
    }

    private static StoredPrDetail detailWithFailingCi()
    {
        return new StoredPrDetail(
                new PrRawDetail(null, List.of(), false, null, null,
                        0, 0, 0, 0, List.of(), "abc", null, null, null, null),
                List.of(), List.of(), List.of(),
                List.of(new PrCheckRunState(
                        null, "backend-tests", "completed", "failure", null, null, null)),
                List.of(), List.of());
    }

    private static Task newTask(String id, String threadId)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Task(
                id, threadId, /* seq */ 1L, TaskStatus.IDLE,
                /* branchName */ "auto-fix/" + id,
                WORKTREE_PATH,
                /* baseBranch */ "main",
                /* workingDir */ CLONE_PATH,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ PR_NUMBER,
                /* linkedIssueNumber */ null,
                /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                /* firstMsgSeq */ null, /* lastMsgSeq */ null,
                now, /* endedAt */ null, /* errorMessage */ null);
    }

    private static Thread newThread(String id, ThreadStatus status)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Auto-fix test thread", status,
                /* workingDir */ CLONE_PATH,
                /* branchName */ "auto-fix/" + id,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                null, ThreadFlow.BUILD, null);
    }
}
