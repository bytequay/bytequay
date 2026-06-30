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
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.stage.IterationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.service.workspaces.WorkspaceService.DEFAULT_WORKSPACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IterationService iterationService = mock(IterationService.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final StageStore stageStore = mock(StageStore.class);

    @Test
    void enqueuesAnAutoFixTurnWhenOptedInAndThreadIdle()
    {
        Task task = newTask("task-1", "thread-1");
        Thread thread = newThread("thread-1", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        // The CI-fixing stage is PAUSED while it waits on remote CI — findActive
        // would miss it; findLiveStageByType pins it so the turn is stage-scoped.
        UUID ciStageId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        when(stageStore.findLiveStageByType("task-1", StageType.CI_FIXING_STAGE))
                .thenReturn(Optional.of(new StageInstance(
                        ciStageId, "task-1", StageType.CI_FIXING_STAGE, StageState.PAUSED,
                        Instant.parse("2026-05-15T12:00:00Z"), null, null)));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        ArgumentCaptor<Thread> threadArg = ArgumentCaptor.forClass(Thread.class);
        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> taskIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stageArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TurnInitiator> initiatorArg = ArgumentCaptor.forClass(TurnInitiator.class);
        // Bound to the task id AND its CI-fixing stage, so it lands on the
        // task's agent and its messages go to stage_messages, not the thread.
        verify(scheduler).enqueueTaskTurn(
                threadArg.capture(), promptArg.capture(), taskIdArg.capture(),
                stageArg.capture(), initiatorArg.capture());
        assertThat(threadArg.getValue().id()).isEqualTo("thread-1");
        assertThat(taskIdArg.getValue()).isEqualTo("task-1");
        assertThat(stageArg.getValue()).isEqualTo(ciStageId.toString());
        assertThat(promptArg.getValue())
                .contains("CI is failing")
                .contains(REPO)
                .contains("#" + PR_NUMBER)
                .contains("backend-tests");
        // An automated trigger marks the turn unattended so the
        // approval gate escalates rather than waiting for a click.
        assertThat(initiatorArg.getValue().attended()).isFalse();
        assertThat(initiatorArg.getValue().source()).isEqualTo("auto-fix-ci-fail");
    }

    @Test
    void skipsWhenRepoNotOptedIn()
    {
        Task task = newTask("task-2", "thread-2");
        Thread thread = newThread("thread-2", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ false, /* leaseHeld */ false);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void skipsWhenTheWorktreeIsHeldByAnotherAgent()
    {
        Task task = newTask("task-3", "thread-3");
        Thread thread = newThread("thread-3", ThreadStatus.IDLE);
        wireFailingCi(task, thread, /* autoFixEnabled */ true, /* leaseHeld */ true);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
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

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
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

        // Exactly one enqueue across the two sweeps.
        verify(scheduler).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void reRunsFailedChecksFirstForAShippedTask()
            throws Exception
    {
        Task task = newShippedTask("ship-1", "thread-1");
        wireShippedFailingCi(task);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        // Cheapest first: the failed checks are re-run in place by PR number —
        // no agent turn, no NEEDS_ATTENTION yet — even though the repo never
        // opted into dashboard auto-fix (shipped tasks are always-on).
        verify(pullRequests).rerunFailedChecks(REPO, PR_NUMBER);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
        verify(notificationService, never())
                .notifyNeedsAttention(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotReRunAgainWhileTheFirstReRunIsStillInFlight()
            throws Exception
    {
        Task task = newShippedTask("ship-2", "thread-2");
        wireShippedFailingCi(task);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();
        coordinator.scanForFailingCi();

        // The cooldown holds the loop while the re-run is in flight, so the
        // second sweep neither re-runs again nor jumps to an agent turn.
        verify(pullRequests).rerunFailedChecks(REPO, PR_NUMBER);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void spawnsAnAutonomousAgentFixTurnAfterTheReRunFails()
    {
        Task task = newShippedTask("ship-3", "thread-3");
        wireShippedFailingCi(task);
        Thread thread = newThread("thread-3", ThreadStatus.IDLE);
        when(threadStore.findThreadById(eq("thread-3"))).thenReturn(Optional.of(thread));
        when(leaseService.isHeld(eq(WORKTREE_PATH))).thenReturn(false);
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any())).thenReturn("turn-id");
        AutomationCoordinator coordinator = newCoordinator();
        // Pretend the cheap re-run already ran and didn't clear CI.
        coordinator.seedCiFixAttemptsForTest("ship-3", 1);

        coordinator.scanForFailingCi();

        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> taskIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TurnInitiator> initiatorArg = ArgumentCaptor.forClass(TurnInitiator.class);
        // Bound to the task id → runs on the task's agent, never the trunk.
        verify(scheduler).enqueueTaskTurn(
                any(), promptArg.capture(), taskIdArg.capture(), any(), initiatorArg.capture());
        assertThat(taskIdArg.getValue()).isEqualTo("ship-3");
        // Autonomous CI-fix prompt: pushes its own fix, no review wait.
        assertThat(promptArg.getValue())
                .contains("shipped PR")
                .contains("git push");
        assertThat(initiatorArg.getValue().attended()).isFalse();
        assertThat(initiatorArg.getValue().source()).isEqualTo("ci-fix-shipped");
        // The re-run is not invoked again on the agent attempt.
        verify(pullRequests, never()).rerunFailedChecks(anyString(), anyString());
    }

    @Test
    void escalatesToNeedsAttentionAfterTheAttemptBudgetIsSpent()
    {
        Task task = newShippedTask("ship-4", "thread-4");
        wireShippedFailingCi(task);
        AutomationCoordinator coordinator = newCoordinator();
        coordinator.seedCiFixAttemptsForTest("ship-4", 3);

        coordinator.scanForFailingCi();

        // Budget spent → hand it to the user; no more re-runs or agent turns.
        verify(notificationService).notifyNeedsAttention(eq("thread-4"), eq("ship-4"), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
        verify(pullRequests, never()).rerunFailedChecks(anyString(), anyString());
    }

    @Test
    void autoPushesAShippedCiFixCommitWithForceWithLease()
            throws Exception
    {
        // The CI-fix agent commits but can't raw-push (blocked); when its turn
        // finishes, the app pushes the commit force-with-lease so CI re-runs.
        Task task = newShippedTask("ship-push", "thread-1");
        when(taskStore.findTaskById("ship-push")).thenReturn(Optional.of(task));
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        when(turnStore.findTurnById("turn-x")).thenReturn(Optional.of(new ThreadTurn(
                "turn-x", "thread-1", "ship-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", now, now, now, now, null,
                TurnInitiator.unattended("ci-fix-shipped"))));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-push", "turn-x", false));

        // The push runs off-thread; await it.
        verify(git, timeout(2000)).pushForceWithLease(Path.of(WORKTREE_PATH));
    }

    @Test
    void doesNotAutoPushForANonCiFixTurn()
            throws Exception
    {
        when(turnStore.findTurnById("turn-y")).thenReturn(Optional.of(new ThreadTurn(
                "turn-y", "thread-1", "ship-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "chat",
                Instant.parse("2026-05-15T12:00:00Z"), Instant.parse("2026-05-15T12:00:00Z"),
                null, null, null, TurnInitiator.user())));
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-push", "turn-y", false));

        verify(git, after(300).never()).pushForceWithLease(any());
    }

    @Test
    void reRunsFailingCiViaThePrNumberNotTheWorktree()
            throws Exception
    {
        // Attempt-0 re-run must resolve the head commit from the PR (number
        // overload), never the local worktree — a reaped/missing worktree used
        // to dead-end every sweep on "could not resolve HEAD".
        Task task = newShippedTask("ship-rerun", "thread-9");
        wireShippedFailingCi(task);
        AutomationCoordinator coordinator = newCoordinator();

        coordinator.scanForFailingCi();

        verify(pullRequests).rerunFailedChecks(REPO, PR_NUMBER);
        verify(git, never()).headSha(any());
    }

    @Test
    void seedsTheCiFixKickoffWithThePriorStagePrDescription()
    {
        // A fresh CI-fix agent no longer resumes the dev session, so its
        // kickoff is seeded with the Development summary = the PR description.
        Task task = newShippedTask("ship-seed", "thread-seed");
        wireShippedFailingCi(task);
        when(pullRequests.getPullRequestDetail(eq(REPO), eq(PR_NUMBER)))
                .thenReturn(liveDetailWithFailingCi("Built the widget and wired it up."));
        Thread thread = newThread("thread-seed", ThreadStatus.IDLE);
        when(threadStore.findThreadById(eq("thread-seed"))).thenReturn(Optional.of(thread));
        when(leaseService.isHeld(eq(WORKTREE_PATH))).thenReturn(false);
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any())).thenReturn("turn-id");
        AutomationCoordinator coordinator = newCoordinator();
        // Re-run already spent → this sweep enqueues the agent fix turn.
        coordinator.seedCiFixAttemptsForTest("ship-seed", 1);

        coordinator.scanForFailingCi();

        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueTaskTurn(any(), promptArg.capture(), eq("ship-seed"), any(), any());
        assertThat(promptArg.getValue())
                .contains("Context from prior stages")
                .contains("Built the widget and wired it up.");
    }

    private void wireShippedFailingCi(Task task)
    {
        when(taskStore.listWithLinkedPr(anyInt())).thenReturn(List.of(task));
        // A shipped task reads its PR's CI state LIVE by ref (a forced
        // refresh) — NOT the dashboard cache — so the loop fires on the
        // freshest state even when the PR was never synced into the local
        // pull_request table.
        when(pullRequests.refreshPullRequestDetail(eq(REPO), eq(PR_NUMBER)))
                .thenReturn(liveDetailWithFailingCi());
        when(notificationService.listUnread()).thenReturn(List.of());
        // Deliberately NOT stubbing the dashboard store / prDetailStore /
        // workspaceStore.findRepo — a shipped task must auto-fix off the live
        // PR even with no cached row and the per-repo opt-in absent.
    }

    /** A live PR detail whose only check run is failing — what the shipped
     *  CI-fix loop now fetches directly from GitHub. */
    private static PullRequestDetail liveDetailWithFailingCi()
    {
        return liveDetailWithFailingCi(/* body */ null);
    }

    private static PullRequestDetail liveDetailWithFailingCi(String body)
    {
        return new PullRequestDetail(
                REPO, PR_NUMBER, body, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.FAILING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "completed", "failure", null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static Task newShippedTask(String id, String threadId)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
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
                now, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null,
                /* pushedAt */ null, TaskPhase.PUSHED_AWAITING_CI, /* agendaJson */ null,
                /* consecutiveAutoPushes */ 0, /* linkedPrRef */ REPO + "#" + PR_NUMBER);
    }

    private AutomationCoordinator newCoordinator()
    {
        return new AutomationCoordinator(
                leaseService, taskStore, threadStore, watchedRepoStore,
                pullRequestStore, prDetailStore, notificationService,
                workspaceStore, scheduler, pullRequests, git, mapper, iterationService, turnStore,
                stageStore);
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
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any())).thenReturn("turn-mock-id");
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
                /* agentSessionId */ null,
                now, /* endedAt */ null, /* errorMessage */ null,
                /* name */ null, /* roleSkill */ null, /* workModel */ null);
    }

    private static Thread newThread(String id, ThreadStatus status)
    {
        Instant now = Instant.parse("2026-05-15T12:00:00Z");
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Auto-fix test thread", status,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }
}
