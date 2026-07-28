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

import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit test for {@link AutomationCoordinator}'s DETECTION half —
 * deciding whether a task's linked PR is failing CI and handing off to
 * {@link CiFixRunExecutor} (mocked here). The loop itself (re-run / agent
 * turn / escalate / push) is {@link TestCiFixRunExecutor}'s job — this
 * split mirrors the production split (plan-rail-runs.md R7).
 */
class TestAutomationCoordinatorAutoFix
{
    private static final String REPO = "acme/widgets";
    private static final String CLONE_PATH = "/tmp/acme-widgets";
    private static final String WORKTREE_PATH = "/tmp/acme-widgets/.worktrees/task-1";
    private static final int PR_NUMBER = 42;
    private static final long PR_ID = 9001L;
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");

    private final WorktreeLeaseService leaseService = mock(WorktreeLeaseService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final WatchedRepoStore watchedRepoStore = mock(WatchedRepoStore.class);
    private final PullRequestStore pullRequestStore = mock(PullRequestStore.class);
    private final PrDetailStore prDetailStore = mock(PrDetailStore.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CiFixRunExecutor ciFixRunExecutor = mock(CiFixRunExecutor.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WorktreeService worktreeService = mock(WorktreeService.class);

    private AutomationCoordinator newCoordinator()
    {
        return new AutomationCoordinator(
                leaseService, taskStore, watchedRepoStore, pullRequestStore, prDetailStore,
                notificationService, pullRequests, mapper, ciFixRunExecutor,
                threadStore, worktreeService);
    }

    @Test
    void reapIdlePlanningWorktreesRemovesOrphansAndIdleThreadsOnly(@TempDir Path tempDir)
            throws IOException
    {
        Path clone = tempDir.resolve("clone");
        Path planningRoot = clone.resolve(".worktrees").resolve("_planning");
        Files.createDirectories(planningRoot.resolve("thread-live"));
        Files.createDirectories(planningRoot.resolve("thread-idle"));
        Files.createDirectories(planningRoot.resolve("thread-gone"));

        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(1, "acme", "widgets", 0, clone.toString(), null, null)));
        when(threadStore.findThreadById("thread-live"))
                .thenReturn(Optional.of(threadTouchedAt("thread-live", Instant.now())));
        when(threadStore.findThreadById("thread-idle"))
                .thenReturn(Optional.of(threadTouchedAt(
                        "thread-idle", Instant.now().minus(Duration.ofDays(30)))));
        when(threadStore.findThreadById("thread-gone"))
                .thenReturn(Optional.empty());

        newCoordinator().reapIdlePlanningWorktrees();

        verify(worktreeService).removePlanningWorktree(Path.of(clone.toString()), "thread-idle");
        verify(worktreeService).removePlanningWorktree(Path.of(clone.toString()), "thread-gone");
        verify(worktreeService, never()).removePlanningWorktree(any(), eq("thread-live"));
    }

    private static Thread threadTouchedAt(String id, Instant updatedAt)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", null,
                "Trunk", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, updatedAt, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }

    @Test
    void dashboardTaskEmitsNotificationAndHandsOffToTheExecutor()
    {
        Task task = newDashboardTask("task-1");
        wireDashboardFailingCi(task);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(notificationService).notifyNeedsAttention(eq("thread-1"), eq("task-1"), anyString());
        verify(ciFixRunExecutor).tryAutoFix(
                eq(task), eq(REPO), eq(List.of("backend-tests")), any());
    }

    @Test
    void legacyCiSweepNeverClaimsAV2Task()
    {
        Task task = newDashboardTask("v2-task");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(taskStore.isV2Task(task.id())).thenReturn(true);

        newCoordinator().scanForFailingCi();

        verify(notificationService, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
        verify(ciFixRunExecutor, never()).tryAutoFix(any(), anyString(), any(), any());
        verify(pullRequests, never()).refreshPullRequestDetail(anyString(), anyInt());
    }

    @Test
    void dashboardTaskDoesNotReNotifyWhenAnUnreadNotificationAlreadyExists()
    {
        Task task = newDashboardTask("task-2");
        wireDashboardFailingCi(task);
        when(notificationService.listUnread()).thenReturn(List.of(
                new Notification(
                        "n1", NotificationKind.NEEDS_ATTENTION, "thread-2", "task-2",
                        NotificationStatus.UNREAD, "{}", NOW, null)));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(notificationService, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
        verify(ciFixRunExecutor, never()).tryAutoFix(any(), anyString(), any(), any());
    }

    @Test
    void dashboardTaskSkipsWhenThePrIsOnlyReviewedNotAuthored()
    {
        // The linked PR belongs to someone else — the user only reviews it.
        // Red CI there is the author's to fix, so no bell should ring.
        Task task = newDashboardTask("task-4");
        wireDashboardFailingCi(task);
        when(pullRequestStore.findById(PR_ID))
                .thenReturn(Optional.of(prWithOrigin(PullRequest.Origin.REVIEW_REQUESTED)));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(notificationService, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
        verify(ciFixRunExecutor, never()).tryAutoFix(any(), anyString(), any(), any());
    }

    @Test
    void dashboardTaskSkipsWhenNoRepoMatchesTheWorkingDir()
    {
        Task task = newDashboardTask("task-3");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(watchedRepoStore.findAll()).thenReturn(List.of());
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(notificationService, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
        verify(ciFixRunExecutor, never()).tryAutoFix(any(), anyString(), any(), any());
    }

    @Test
    void shippedTaskDrivesTheExecutorLoopWhenLiveCiIsFailing()
    {
        Task task = newShippedTask("ship-1", "thread-1");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(pullRequests.refreshPullRequestDetail(REPO, PR_NUMBER)).thenReturn(liveDetailFailing());
        when(ciFixRunExecutor.driveShippedCiFix(eq(task), eq(REPO), any())).thenReturn(false);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(ciFixRunExecutor).driveShippedCiFix(eq(task), eq(REPO), any());
        verify(ciFixRunExecutor, never()).closeIfGreen(any());
    }

    @Test
    void shippedTaskClosesAnyLiveRunWhenLiveCiIsGreen()
    {
        Task task = newShippedTask("ship-2", "thread-2");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(pullRequests.refreshPullRequestDetail(REPO, PR_NUMBER)).thenReturn(liveDetailGreen());
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(ciFixRunExecutor).closeIfGreen(task);
        verify(ciFixRunExecutor, never()).driveShippedCiFix(any(), anyString(), any());
    }

    @Test
    void shippedTaskKeepsTheLiveRunOpenWhileCiIsPending()
    {
        Task task = newShippedTask("ship-4", "thread-4");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(pullRequests.refreshPullRequestDetail(REPO, PR_NUMBER)).thenReturn(liveDetailPending());
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(ciFixRunExecutor, never()).closeIfGreen(any());
        verify(ciFixRunExecutor, never()).driveShippedCiFix(any(), anyString(), any());
    }

    @Test
    void shippedTaskWaitsForRemainingChecksAfterTheFirstFailure()
    {
        Task task = newShippedTask("ship-mixed", "thread-mixed");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(pullRequests.refreshPullRequestDetail(REPO, PR_NUMBER))
                .thenReturn(liveDetailFailingWithPendingCheck());
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(ciFixRunExecutor, never()).driveShippedCiFix(any(), anyString(), any());
        verify(ciFixRunExecutor, never()).closeIfGreen(any());
    }

    @Test
    void shippedTaskDoesNotCrashWhenTheLiveFetchFails()
    {
        Task task = newShippedTask("ship-3", "thread-3");
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(pullRequests.refreshPullRequestDetail(REPO, PR_NUMBER))
                .thenThrow(new RuntimeException("network blip"));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(ciFixRunExecutor, never()).driveShippedCiFix(any(), anyString(), any());
        verify(ciFixRunExecutor, never()).closeIfGreen(any());
    }

    @Test
    void parkedAndTerminalTasksAreSkippedBeforeReadingRemoteCi()
    {
        Task parked = newShippedTask("ship-parked", "thread-parked")
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        Task completed = newShippedTask("ship-done", "thread-done")
                .withStatus(TaskStatus.COMPLETED);
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(parked, completed));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(pullRequests, never()).refreshPullRequestDetail(anyString(), eq(PR_NUMBER));
        verify(ciFixRunExecutor, never()).driveShippedCiFix(any(), anyString(), any());
        verify(ciFixRunExecutor, never()).closeIfGreen(any());
    }

    private void wireDashboardFailingCi(Task task)
    {
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(task));
        when(watchedRepoStore.findAll()).thenReturn(List.of(
                new WatchedRepo(/* id */ 1L, "acme", "widgets", /* displayOrder */ 0,
                        CLONE_PATH, /* upstreamRemoteName */ null, /* viewFocus */ "fork")));
        when(pullRequestStore.findIdByRepoAndNumber(eq(REPO), eq(PR_NUMBER)))
                .thenReturn(Optional.of(PR_ID));
        when(pullRequestStore.findById(PR_ID))
                .thenReturn(Optional.of(prWithOrigin(PullRequest.Origin.AUTHORED)));
        when(prDetailStore.find(eq(PR_ID))).thenReturn(Optional.of(detailWithFailingCi()));
        when(notificationService.listUnread()).thenReturn(List.of());
    }

    private static PullRequest prWithOrigin(PullRequest.Origin origin)
    {
        return new PullRequest(PR_ID, REPO, PR_NUMBER, "title", "ebyhr", "url",
                NOW, NOW, origin, List.of(), null, false, null, null, null, List.of(),
                null, 0, 0, 0, null,
                "open", null, null, null, null, null, null,
                null, null, null);
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

    private static PullRequestDetail liveDetailFailing()
    {
        return new PullRequestDetail(
                REPO, PR_NUMBER, null, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.FAILING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "completed", "failure", null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static PullRequestDetail liveDetailGreen()
    {
        return new PullRequestDetail(
                REPO, PR_NUMBER, null, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.PASSING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "completed", "success", null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static PullRequestDetail liveDetailPending()
    {
        return new PullRequestDetail(
                REPO, PR_NUMBER, null, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.PENDING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "in_progress", null, null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static PullRequestDetail liveDetailFailingWithPendingCheck()
    {
        return new PullRequestDetail(
                REPO, PR_NUMBER, null, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.FAILING, List.of(), List.of(),
                List.of(
                        new PullRequestDetail.CheckRun(
                                null, "frontend-checks", "completed", "failure",
                                null, null, null),
                        new PullRequestDetail.CheckRun(
                                null, "commit-check", "in_progress", null,
                                null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static Task newDashboardTask(String id)
    {
        String threadId = "thread-" + id.substring(id.length() - 1);
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
                /* agentSessionId */ null,
                NOW, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
    }

    private static Task newShippedTask(String id, String threadId)
    {
        return new Task(
                id, threadId, /* seq */ 1L, TaskStatus.IN_REVIEW,
                /* branchName */ "dev/" + id,
                WORKTREE_PATH,
                /* baseBranch */ "main",
                /* workingDir */ CLONE_PATH,
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ PR_NUMBER,
                /* linkedIssueNumber */ null,
                /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                /* agentSessionId */ null,
                NOW, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null,
                /* pushedAt */ null, TaskPhase.PUSHED_AWAITING_CI, /* agendaJson */ null,
                /* consecutiveAutoPushes */ 0, /* linkedPrRef */ REPO + "#" + PR_NUMBER);
    }
}
