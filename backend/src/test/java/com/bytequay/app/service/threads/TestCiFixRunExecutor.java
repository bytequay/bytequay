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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
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
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization coverage for the CI-fixing loop, lifted verbatim from
 * {@code TestAutomationCoordinatorAutoFix} when the loop moved off
 * {@link AutomationCoordinator} into this class (plan-rail-runs.md R7).
 * Every assertion here pins the SAME behavior the pre-split tests pinned —
 * only the seams changed: iteration counting is a mocked {@link
 * AgentRunService} instead of an in-memory map, and the entry points are
 * this class's own methods instead of {@code scanForFailingCi()} (detection
 * now lives on {@link AutomationCoordinator}, covered separately).
 */
class TestCiFixRunExecutor
{
    private static final String REPO = "acme/widgets";
    private static final String WORKTREE_PATH = "/tmp/acme-widgets/.worktrees/task-1";
    private static final int PR_NUMBER = 42;
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");
    private static final String REMOTE_STAGE_ID = "00000000-0000-0000-0000-0000000000d1";

    private final WorktreeLeaseService leaseService = mock(WorktreeLeaseService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final RemoteDevelopmentStageService remoteStages = mock(RemoteDevelopmentStageService.class);

    private CiFixRunExecutor newExecutor()
    {
        when(remoteStages.ensureOpen(anyString())).thenReturn(remoteStage());
        return new CiFixRunExecutor(
                leaseService, taskStore, threadStore, workspaceStore, notificationService,
                scheduler, pullRequests, git, mapper, turnStore, agentRuns, remoteStages);
    }

    private static StageInstance remoteStage()
    {
        return new StageInstance(
                UUID.fromString(REMOTE_STAGE_ID), "task-1", StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.OPEN, NOW, null, null);
    }

    /** Stubs {@code agentRuns.openInStage(...)} to return a run already at {@code
     *  iterations}, and {@code recordIteration} to return it bumped by one —
     *  the mocked equivalent of the old {@code seedCiFixAttemptsForTest}. */
    private AgentRun wireRun(String taskId, int iterations)
    {
        AgentRun run = new AgentRun(
                "run-1", taskId, AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, null,
                REMOTE_STAGE_ID, AgentRun.STATUS_RUNNING, iterations, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS,
                null, null, NOW, null);
        when(agentRuns.openInStage(eq(taskId), eq(AgentRun.KIND_CI_FIX), eq(AgentRun.SOURCE_REMOTE),
                eq(REMOTE_STAGE_ID), eq(CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS)))
                .thenReturn(run);
        when(agentRuns.recordIteration(eq("run-1"), any()))
                .thenReturn(run.withIteration(iterations + 1, null));
        return run;
    }

    @Test
    void enqueuesAnAutoFixTurnWhenOptedInAndThreadIdle()
    {
        Task task = newTask("task-1", "thread-1");
        Thread thread = newThread("thread-1", ThreadStatus.IDLE);
        wireDashboardOptIn(thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        wireRun("task-1", 0);
        CiFixRunExecutor executor = newExecutor();

        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));

        ArgumentCaptor<Thread> threadArg = ArgumentCaptor.forClass(Thread.class);
        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> taskIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stageArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TurnInitiator> initiatorArg = ArgumentCaptor.forClass(TurnInitiator.class);
        ArgumentCaptor<String> runArg = ArgumentCaptor.forClass(String.class);
        // Bound to the task id, Remote Development stage, and CI-fix run id.
        verify(scheduler).enqueueTaskTurn(
                threadArg.capture(), promptArg.capture(), taskIdArg.capture(),
                stageArg.capture(), initiatorArg.capture(), runArg.capture());
        assertThat(threadArg.getValue().id()).isEqualTo("thread-1");
        assertThat(taskIdArg.getValue()).isEqualTo("task-1");
        assertThat(stageArg.getValue()).isEqualTo(REMOTE_STAGE_ID);
        assertThat(runArg.getValue()).isEqualTo("run-1");
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
        wireDashboardOptIn(thread, /* autoFixEnabled */ false, /* leaseHeld */ false);
        CiFixRunExecutor executor = newExecutor();

        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void skipsWhenTheWorktreeIsHeldByAnotherAgent()
    {
        Task task = newTask("task-3", "thread-3");
        Thread thread = newThread("thread-3", ThreadStatus.IDLE);
        wireDashboardOptIn(thread, /* autoFixEnabled */ true, /* leaseHeld */ true);
        CiFixRunExecutor executor = newExecutor();

        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void defersWhenTheOwningThreadIsBusy()
    {
        Task task = newTask("task-4", "thread-4");
        // RUNNING means the user is mid-turn — auto-fix must not
        // interrupt; the next sweep retries.
        Thread thread = newThread("thread-4", ThreadStatus.RUNNING);
        wireDashboardOptIn(thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        CiFixRunExecutor executor = newExecutor();

        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void doesNotEnqueueTwiceWithinTheSameProcess()
    {
        Task task = newTask("task-5", "thread-5");
        Thread thread = newThread("thread-5", ThreadStatus.IDLE);
        wireDashboardOptIn(thread, /* autoFixEnabled */ true, /* leaseHeld */ false);
        wireRun("task-5", 0);
        CiFixRunExecutor executor = newExecutor();

        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));
        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));

        // Exactly one enqueue across the two calls — the dedup set guards
        // the enqueue side regardless of how many times detection re-fires.
        verify(scheduler).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void reRunsFailedChecksFirstForAShippedTask()
    {
        Task task = newShippedTask("ship-1", "thread-1");
        wireRun("ship-1", 0);
        when(pullRequests.rerunFailedChecks(REPO, PR_NUMBER)).thenReturn(1);
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        // Cheapest first: the failed checks are re-run in place by PR number —
        // no agent turn, no escalation.
        verify(pullRequests).rerunFailedChecks(REPO, PR_NUMBER);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
        verify(notificationService, never())
                .notifyNeedsAttention(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotReRunAgainWhileTheFirstReRunIsStillInFlight()
    {
        Task task = newShippedTask("ship-2", "thread-2");
        wireRun("ship-2", 0);
        when(pullRequests.rerunFailedChecks(REPO, PR_NUMBER)).thenReturn(1);
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());
        executor.driveShippedCiFix(task, REPO, failingCi());

        // The cooldown holds the loop while the re-run is in flight, so the
        // second call neither re-runs again nor jumps to an agent turn.
        verify(pullRequests).rerunFailedChecks(REPO, PR_NUMBER);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void spawnsAnAutonomousAgentFixTurnAfterTheReRunFails()
    {
        Task task = newShippedTask("ship-3", "thread-3");
        // Pretend the cheap re-run already ran and didn't clear CI.
        wireRun("ship-3", 1);
        Thread thread = newThread("thread-3", ThreadStatus.IDLE);
        when(threadStore.findThreadById(eq("thread-3"))).thenReturn(Optional.of(thread));
        when(leaseService.isHeldByAnotherTask(eq(WORKTREE_PATH), eq("ship-3"))).thenReturn(false);
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("turn-id");
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> taskIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TurnInitiator> initiatorArg = ArgumentCaptor.forClass(TurnInitiator.class);
        // Bound to the task id → runs on the task's agent, never the trunk.
        verify(scheduler).enqueueTaskTurn(
                any(), promptArg.capture(), taskIdArg.capture(), any(), initiatorArg.capture(), eq("run-1"));
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
        wireRun("ship-4", CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS);
        when(notificationService.listUnread()).thenReturn(List.of());
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        // Budget spent → hand it to the user; no more re-runs or agent turns;
        // the run is failed.
        verify(notificationService).notifyNeedsAttention(eq("thread-4"), eq("ship-4"), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any());
        verify(pullRequests, never()).rerunFailedChecks(anyString(), anyString());
        verify(agentRuns).transition("run-1", AgentRun.STATUS_FAILED, "attempts_exhausted");
    }

    @Test
    void autoPushesAShippedCiFixCommitWithForceWithLease()
            throws Exception
    {
        // The CI-fix agent commits but can't raw-push (blocked); when its turn
        // finishes, the app pushes the commit force-with-lease so CI re-runs.
        Task task = newShippedTask("ship-push", "thread-1");
        when(taskStore.findTaskById("ship-push")).thenReturn(Optional.of(task));
        when(turnStore.findTurnById("turn-x")).thenReturn(Optional.of(new ThreadTurn(
                "turn-x", "thread-1", "ship-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended("ci-fix-shipped"))));
        CiFixRunExecutor executor = newExecutor();

        executor.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-push", "turn-x", false));

        // The push runs off-thread; await it.
        verify(git, timeout(2000)).pushForceWithLease(Path.of(WORKTREE_PATH));
    }

    @Test
    void doesNotAutoPushForANonCiFixTurn()
            throws Exception
    {
        when(turnStore.findTurnById("turn-y")).thenReturn(Optional.of(new ThreadTurn(
                "turn-y", "thread-1", "ship-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "chat", NOW, NOW, null, null, null, TurnInitiator.user())));
        CiFixRunExecutor executor = newExecutor();

        executor.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-push", "turn-y", false));

        verify(git, after(300).never()).pushForceWithLease(any());
    }

    @Test
    void reRunsFailingCiViaThePrNumberNotTheWorktree()
            throws Exception
    {
        // Iteration-0 re-run must resolve the head commit from the PR (number
        // overload), never the local worktree — a reaped/missing worktree used
        // to dead-end every sweep on "could not resolve HEAD".
        Task task = newShippedTask("ship-rerun", "thread-9");
        wireRun("ship-rerun", 0);
        when(pullRequests.rerunFailedChecks(REPO, PR_NUMBER)).thenReturn(1);
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        verify(pullRequests).rerunFailedChecks(REPO, PR_NUMBER);
        verify(git, never()).headSha(any());
    }

    @Test
    void seedsTheCiFixKickoffWithThePriorStagePrDescription()
    {
        // A fresh CI-fix agent no longer resumes the dev session, so its
        // kickoff is seeded with the Development summary = the PR description.
        Task task = newShippedTask("ship-seed", "thread-seed");
        // Re-run already spent → this call enqueues the agent fix turn.
        wireRun("ship-seed", 1);
        when(pullRequests.getPullRequestDetail(eq(REPO), eq(PR_NUMBER)))
                .thenReturn(prDetailWithBody("Built the widget and wired it up."));
        Thread thread = newThread("thread-seed", ThreadStatus.IDLE);
        when(threadStore.findThreadById(eq("thread-seed"))).thenReturn(Optional.of(thread));
        when(leaseService.isHeldByAnotherTask(eq(WORKTREE_PATH), eq("ship-seed"))).thenReturn(false);
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("turn-id");
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueTaskTurn(any(), promptArg.capture(), eq("ship-seed"), any(), any(), eq("run-1"));
        assertThat(promptArg.getValue())
                .contains("Context from prior stages")
                .contains("Built the widget and wired it up.");
    }

    @Test
    void closeIfGreenTransitionsTheLiveRunToSucceeded()
    {
        Task task = newShippedTask("ship-green", "thread-1");
        AgentRun run = new AgentRun(
                "run-1", "ship-green", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE, null, null,
                REMOTE_STAGE_ID, AgentRun.STATUS_RUNNING, 2, 1, null, null, NOW, null);
        when(agentRuns.findByTask("ship-green", AgentRun.KIND_CI_FIX, null)).thenReturn(List.of(run));
        CiFixRunExecutor executor = newExecutor();

        executor.closeIfGreen(task);

        verify(agentRuns).transition("run-1", AgentRun.STATUS_SUCCEEDED, "checks_green");
    }

    @Test
    void closeIfGreenIsANoOpWithNoLiveRun()
    {
        Task task = newShippedTask("ship-none", "thread-1");
        when(agentRuns.findByTask("ship-none", AgentRun.KIND_CI_FIX, null)).thenReturn(List.of());
        CiFixRunExecutor executor = newExecutor();

        executor.closeIfGreen(task);

        verify(agentRuns, never()).transition(any(), any(), any());
    }

    private static PullRequestDetail prDetailWithBody(String body)
    {
        return new PullRequestDetail(
                REPO, PR_NUMBER, body, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.FAILING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "completed", "failure", null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private void wireDashboardOptIn(Thread thread, boolean autoFixEnabled, boolean leaseHeld)
    {
        when(workspaceStore.findRepo(eq(DEFAULT_WORKSPACE_ID), eq(REPO)))
                .thenReturn(Optional.of(new WorkspaceRepo(
                        DEFAULT_WORKSPACE_ID, REPO, /* defaultBaseBranch */ null,
                        autoFixEnabled, NOW)));
        when(leaseService.isHeldByAnotherTask(eq(WORKTREE_PATH), anyString())).thenReturn(leaseHeld);
        when(threadStore.findThreadById(eq(thread.id()))).thenReturn(Optional.of(thread));
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn("turn-mock-id");
    }

    private static AutomationCoordinator.CiAggregate failingCi()
    {
        return new AutomationCoordinator.CiAggregate(
                true, List.of("backend-tests"), List.of(checkRunState("backend-tests")), 1);
    }

    private static PrCheckRunState checkRunState(String name)
    {
        return new PrCheckRunState(null, name, "completed", "failure", null, null, null);
    }

    private static Task newShippedTask(String id, String threadId)
    {
        return new Task(
                id, threadId, /* seq */ 1L, TaskStatus.IN_REVIEW,
                /* branchName */ "dev/" + id,
                WORKTREE_PATH,
                /* baseBranch */ "main",
                /* workingDir */ "/tmp/acme-widgets",
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

    private static Task newTask(String id, String threadId)
    {
        return new Task(
                id, threadId, /* seq */ 1L, TaskStatus.IDLE,
                /* branchName */ "auto-fix/" + id,
                WORKTREE_PATH,
                /* baseBranch */ "main",
                /* workingDir */ "/tmp/acme-widgets",
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

    private static Thread newThread(String id, ThreadStatus status)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Auto-fix test thread", status,
                "claude-sonnet-4.6",
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }
}
