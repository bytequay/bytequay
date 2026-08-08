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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization coverage for the CI-fixing loop, lifted verbatim from
 * {@code TestAutomationCoordinatorAutoFix} when the loop moved off
 * {@link AutomationCoordinator} into this class (plan-rail-runs.md R7).
 * Every assertion here pins the SAME behavior the pre-split tests pinned —
 * only the seams changed: iteration counting is a mocked {@link
 * AgentRunServiceImpl} instead of an in-memory map, and the entry points are
 * this class's own methods instead of {@code scanForFailingCi()} (detection
 * now lives on {@link AutomationCoordinator}, covered separately).
 */
class TestCiFixRunExecutor
{
    private static final String REPO = "acme/widgets";
    private static final String WORKSPACE_ID = "ws-default";
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
    private final PRService localPrs = mock(PRService.class);
    private final GitRunner git = mock(GitRunner.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final AgentRunServiceImpl agentRuns = mock(AgentRunServiceImpl.class);
    private final RemoteDevelopmentStageService remoteStages = mock(RemoteDevelopmentStageService.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);

    private CiFixRunExecutor newExecutor()
    {
        when(remoteStages.ensureOpen(anyString())).thenReturn(remoteStage());
        return new CiFixRunExecutor(
                leaseService, taskStore, threadStore, workspaceStore, notificationService,
                scheduler, pullRequests, localPrs, git, mapper, turnStore, agentRuns, remoteStages,
                phaseMachine);
    }

    private static StageInstance remoteStage()
    {
        return new StageInstance(
                UUID.fromString(REMOTE_STAGE_ID), "task-1", StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.OPEN, NOW, null, null);
    }

    @Test
    void legacyCiExecutorNeverClaimsAV2Task()
    {
        Task task = newTask("v2-task", "thread-1");
        when(taskStore.isV2Task(task.id())).thenReturn(true);

        newExecutor().tryAutoFix(task, REPO, List.of("backend-tests"), List.of());

        verify(remoteStages, never()).ensureOpen(anyString());
        verify(scheduler, never()).enqueueStageTurn(
                any(), any(), any(), any(), any(), any(), any());
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
        verify(scheduler).enqueueStageTurn(
                threadArg.capture(), promptArg.capture(), taskIdArg.capture(),
                stageArg.capture(), initiatorArg.capture(), runArg.capture(), any());
        assertThat(threadArg.getValue().id()).isEqualTo("thread-1");
        assertThat(taskIdArg.getValue()).isEqualTo("task-1");
        assertThat(stageArg.getValue()).isEqualTo(REMOTE_STAGE_ID);
        assertThat(runArg.getValue()).isEqualTo("run-1");
        assertThat(promptArg.getValue())
                .contains("CI is failing")
                .contains(REPO)
                .contains("#" + PR_NUMBER)
                .contains("backend-tests")
                .contains("commit the fix", "ByteQuay pushes", "do not open a separate review gate")
                .doesNotContain("request_review");
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

        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenTheWorktreeIsHeldByAnotherAgent()
    {
        Task task = newTask("task-3", "thread-3");
        Thread thread = newThread("thread-3", ThreadStatus.IDLE);
        wireDashboardOptIn(thread, /* autoFixEnabled */ true, /* leaseHeld */ true);
        CiFixRunExecutor executor = newExecutor();

        executor.tryAutoFix(task, REPO, List.of("backend-tests"), List.of(checkRunState("backend-tests")));

        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
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

        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
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
        verify(scheduler).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
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
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
        verify(notificationService, never())
                .notifyNeedsAttention(anyString(), anyString(), anyString());
    }

    @Test
    void recordsAUserTriggeredRerunWithTheCurrentHead()
    {
        Task task = newShippedTask("ship-user-rerun", "thread-1");
        PR pr = mock(PR.class);
        when(pr.id()).thenReturn("local-pr");
        wireRun(task.id(), 0);
        when(pullRequests.rerunFailedChecks(REPO, PR_NUMBER)).thenReturn(1);
        when(localPrs.findByTask(task.id())).thenReturn(Optional.of(pr));
        when(localPrs.commits("local-pr")).thenReturn(List.of(new PRCommit(
                "commit-1", "local-pr", "deadbeef", "Fix", 1, 0, NOW, NOW)));
        when(taskStore.listPhaseEvents(task.id())).thenReturn(List.of(new TaskPhaseEvent(
                1L, task.id(), TaskPhase.NEEDS_ATTENTION, TaskPhase.PUSHED_AWAITING_CI,
                NOW, "user_retried_ci", Actor.HUMAN)));

        newExecutor().driveShippedCiFix(task, REPO, failingCi());

        verify(localPrs).recordRemoteCiRerun("local-pr", "user", "deadbeef", 1);
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
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void fallsThroughToAgentWhenGitHubRerunsNoWorkflow()
    {
        Task task = newShippedTask("ship-zero", "thread-zero");
        wireRun(task.id(), 0);
        when(pullRequests.rerunFailedChecks(REPO, PR_NUMBER)).thenReturn(0);
        Thread thread = newThread("thread-zero", ThreadStatus.COMPLETED);
        when(threadStore.findThreadById(thread.id())).thenReturn(Optional.of(thread));
        when(leaseService.isHeldByAnotherTask(WORKTREE_PATH, task.id())).thenReturn(false);
        when(scheduler.enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("turn-id");

        newExecutor().driveShippedCiFix(task, REPO, failingCi());

        verify(localPrs, never()).recordRemoteCiRerun(anyString(), anyString(), any(), anyInt());
        verify(scheduler).enqueueStageTurn(
                eq(thread), anyString(), eq(task.id()), eq(REMOTE_STAGE_ID),
                any(), eq("run-1"), eq(TurnLiveness.CODE));
        verify(agentRuns).recordIteration("run-1", "backend-tests");
    }

    @Test
    void retriesLaterWhenTheGitHubRerunRequestFails()
    {
        Task task = newShippedTask("ship-rerun-error", "thread-error");
        wireRun(task.id(), 0);
        when(pullRequests.rerunFailedChecks(REPO, PR_NUMBER))
                .thenThrow(new RuntimeException("network unavailable"));
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());
        executor.driveShippedCiFix(task, REPO, failingCi());

        verify(pullRequests, times(2)).rerunFailedChecks(REPO, PR_NUMBER);
        verify(agentRuns, never()).recordIteration(anyString(), any());
        verify(scheduler, never())
                .enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
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
        when(scheduler.enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("turn-id");
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> taskIdArg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TurnInitiator> initiatorArg = ArgumentCaptor.forClass(TurnInitiator.class);
        // Bound to the task id → runs on the task's agent, never the trunk.
        verify(scheduler).enqueueStageTurn(
                any(), promptArg.capture(), taskIdArg.capture(), any(), initiatorArg.capture(), eq("run-1"), any());
        assertThat(taskIdArg.getValue()).isEqualTo("ship-3");
        // Autonomous CI-fix prompt: commits for ByteQuay to auto-push, with
        // no second review gate.
        assertThat(promptArg.getValue())
                .contains("shipped PR")
                .contains("deterministic failure reproduces on the PR base branch `main`")
                .contains("first commit after the merge base")
                .contains("followed by all original PR commits")
                .contains("git push");
        assertThat(initiatorArg.getValue().attended()).isFalse();
        assertThat(initiatorArg.getValue().source()).isEqualTo("ci-fix-shipped");
        // The re-run is not invoked again on the agent attempt.
        verify(pullRequests, never()).rerunFailedChecks(anyString(), anyString());
    }

    @Test
    void oneRedCiEpisodeKeepsItsBudgetAcrossFourFixPushesAndThenParks()
            throws Exception
    {
        Task task = newShippedTask("ship-episode", "thread-episode");
        Thread thread = newThread("thread-episode", ThreadStatus.IDLE);
        AtomicReference<AgentRun> episode = new AtomicReference<>(new AgentRun(
                "run-episode", task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, null, REMOTE_STAGE_ID, AgentRun.STATUS_RUNNING,
                /* cheap re-run already spent */ 1, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS,
                null, null, NOW, null));
        when(agentRuns.openInStage(
                task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS))
                .thenAnswer(ignored -> episode.get());
        when(agentRuns.recordIteration(eq("run-episode"), any()))
                .thenAnswer(invocation -> {
                    String headline = invocation.getArgument(1);
                    return episode.updateAndGet(run -> run.withIteration(
                            run.iterations() + 1, headline));
                });
        when(agentRuns.findById("run-episode"))
                .thenAnswer(ignored -> Optional.of(episode.get()));
        when(taskStore.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(threadStore.findThreadById(thread.id())).thenReturn(Optional.of(thread));
        when(leaseService.isHeldByAnotherTask(WORKTREE_PATH, task.id())).thenReturn(false);
        when(notificationService.listUnread()).thenReturn(List.of());
        when(scheduler.enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("turn-1", "turn-2", "turn-3", "turn-4");
        for (int i = 1; i <= 4; i++) {
            String turnId = "turn-" + i;
            when(turnStore.findTurnById(turnId)).thenReturn(Optional.of(ciFixTurn(
                    turnId, task, "run-episode")));
        }

        // Process restarts between scans deliberately discard the in-memory
        // cooldown. The attempt count must survive because it lives on the run.
        for (int i = 1; i <= 4; i++) {
            CiFixRunExecutor scan = newExecutor();
            scan.driveShippedCiFix(task, REPO, failingCi());
            assertThat(episode.get().iterations()).isEqualTo(i + 1);
            scan.autoPushAfterCiFix(new TaskTurnFinishedEvent(
                    task.id(), "turn-" + i, false, true));
            verify(git, timeout(2000).times(i)).pushForceWithLease(Path.of(WORKTREE_PATH));
        }

        newExecutor().driveShippedCiFix(task, REPO, failingCi());

        verify(scheduler, times(4)).enqueueStageTurn(
                any(), anyString(), eq(task.id()), eq(REMOTE_STAGE_ID), any(), eq("run-episode"), any());
        verify(agentRuns, times(5)).openInStage(
                task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS);
        verify(agentRuns).transition("run-episode", AgentRun.STATUS_FAILED, "attempts_exhausted");
        verify(phaseMachine).parkOperational(task.id(), Actor.AGENT, "ci_fix_attempts_exhausted");
        verify(notificationService).notifyNeedsAttention(eq(thread.id()), eq(task.id()), anyString());
        verify(pullRequests, never()).rerunFailedChecks(anyString(), anyString());
    }

    @Test
    void budgetExhaustionParksTheTaskAndASecondScanCannotOpenAFreshRun()
    {
        Task task = newShippedTask("ship-4", "thread-4");
        wireRun("ship-4", CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS);
        when(taskStore.findTaskById("ship-4")).thenReturn(Optional.of(task));
        when(notificationService.listUnread()).thenReturn(List.of());
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        // Budget spent → hand it to the user; no more re-runs or agent turns;
        // the run is failed.
        verify(notificationService).notifyNeedsAttention(eq("thread-4"), eq("ship-4"), anyString());
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
        verify(pullRequests, never()).rerunFailedChecks(anyString(), anyString());
        verify(agentRuns).transition("run-1", AgentRun.STATUS_FAILED, "attempts_exhausted");
        verify(phaseMachine).parkOperational("ship-4", Actor.AGENT, "ci_fix_attempts_exhausted");

        // The next periodic pass reads the persisted parked row. It must stop
        // before fetching CI/opening a new live run at iteration zero.
        Task parked = task.withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.listWithLinkedPr(200)).thenReturn(List.of(parked));
        when(pullRequests.refreshPullRequestDetail(REPO, PR_NUMBER))
                .thenReturn(prDetailWithBody("still failing"));
        AutomationCoordinator coordinator = new AutomationCoordinator(
                leaseService,
                taskStore,
                mock(WatchedRepoStore.class),
                mock(PullRequestStore.class),
                mock(PrDetailStore.class),
                notificationService,
                pullRequests,
                mapper,
                executor,
                mock(ThreadStore.class),
                mock(WorktreeService.class));

        coordinator.scanForFailingCi();

        verify(pullRequests, never()).refreshPullRequestDetail(REPO, PR_NUMBER);
        verify(agentRuns).openInStage(
                "ship-4", AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS);
    }

    @Test
    void anExistingEpisodeKeepsItsPersistedAttemptLimit()
    {
        Task task = newShippedTask("ship-legacy-cap", "thread-legacy-cap");
        AgentRun legacyRun = new AgentRun(
                "run-legacy", task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, null, REMOTE_STAGE_ID, AgentRun.STATUS_RUNNING,
                3, 3, null, null, NOW, null);
        when(agentRuns.openInStage(
                task.id(), AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS)).thenReturn(legacyRun);
        when(taskStore.findTaskById(task.id())).thenReturn(Optional.of(task));
        when(notificationService.listUnread()).thenReturn(List.of());

        newExecutor().driveShippedCiFix(task, REPO, failingCi());

        verify(agentRuns).transition("run-legacy", AgentRun.STATUS_FAILED, "attempts_exhausted");
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void autoPushesAShippedCiFixCommitWithForceWithLease()
            throws Exception
    {
        // The CI-fix agent commits but can't raw-push (blocked); when its turn
        // finishes, the app pushes the commit force-with-lease so CI re-runs.
        Task task = newShippedTask("ship-push", "thread-1");
        when(taskStore.findTaskById("ship-push")).thenReturn(Optional.of(task));
        when(agentRuns.findById("run-push"))
                // completeTurn queues coordinator-owned runs before publishing
                // the event handled by autoPushAfterCiFix.
                .thenReturn(Optional.of(queuedCiFixRun("run-push", "ship-push")));
        when(turnStore.findTurnById("turn-x")).thenReturn(Optional.of(new ThreadTurn(
                "turn-x", "thread-1", "ship-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended("ci-fix-shipped"), REMOTE_STAGE_ID,
                ThreadScope.STAGE, "run-push")));
        CiFixRunExecutor executor = newExecutor();

        executor.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-push", "turn-x", false, true));

        // The push runs off-thread; await it.
        verify(git, timeout(2000)).pushForceWithLease(Path.of(WORKTREE_PATH));
    }

    @Test
    void autoPushesAnOptedInDashboardCiFixWithoutALegacyReviewGate()
            throws Exception
    {
        Task task = newTask("dashboard-push", "thread-1");
        when(taskStore.findTaskById("dashboard-push")).thenReturn(Optional.of(task));
        when(agentRuns.findById("run-dashboard"))
                .thenReturn(Optional.of(succeededCiFixRun("run-dashboard", "dashboard-push")));
        when(turnStore.findTurnById("turn-dashboard")).thenReturn(Optional.of(new ThreadTurn(
                "turn-dashboard", "thread-1", "dashboard-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended("auto-fix-ci-fail"), REMOTE_STAGE_ID,
                ThreadScope.STAGE, "run-dashboard")));
        CiFixRunExecutor executor = newExecutor();

        executor.autoPushAfterCiFix(
                new TaskTurnFinishedEvent("dashboard-push", "turn-dashboard", false, true));

        verify(git, timeout(2000)).pushForceWithLease(Path.of(WORKTREE_PATH));
    }

    @Test
    void doesNotPushWhenTheTaskParksAfterTheCompletionEvent()
            throws Exception
    {
        Task active = newShippedTask("ship-race", "thread-1");
        AtomicReference<Task> current = new AtomicReference<>(active);
        when(taskStore.findTaskById("ship-race"))
                .thenAnswer(ignored -> Optional.of(current.get()));
        when(agentRuns.findById("run-race"))
                .thenReturn(Optional.of(runningCiFixRun("run-race", "ship-race")));
        when(turnStore.findTurnById("turn-race")).thenReturn(Optional.of(new ThreadTurn(
                "turn-race", "thread-1", "ship-race", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended("ci-fix-shipped"), REMOTE_STAGE_ID,
                ThreadScope.STAGE, "run-race")));
        CiFixRunExecutor executor = newExecutor();

        // Hold the same lock the async pusher uses, deliver the completion,
        // then park the task before letting the pusher re-read durable state.
        TaskPhaseMachine.withTaskLock("ship-race", () -> {
            executor.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-race", "turn-race", false, true));
            current.set(active.withStatus(TaskStatus.NEEDS_ATTENTION));
            return null;
        });

        verify(git, after(500).never()).pushForceWithLease(any());
        verify(git, never()).commit(any(), anyString());
    }

    @Test
    void doesNotAutoPushForANonCiFixTurn()
            throws Exception
    {
        when(turnStore.findTurnById("turn-y")).thenReturn(Optional.of(new ThreadTurn(
                "turn-y", "thread-1", "ship-push", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "chat", NOW, NOW, null, null, null,
                TurnInitiator.user(), null, ThreadScope.TASK)));
        CiFixRunExecutor executor = newExecutor();

        executor.autoPushAfterCiFix(new TaskTurnFinishedEvent("ship-push", "turn-y", false));

        verify(git, after(300).never()).pushForceWithLease(any());
    }

    @Test
    void parksAShippedCiFixWhenTheAgentMadeNoChanges()
            throws Exception
    {
        Task task = newShippedTask("ship-no-change", "thread-1");
        when(taskStore.findTaskById("ship-no-change")).thenReturn(Optional.of(task));
        when(agentRuns.findById("run-no-change"))
                .thenReturn(Optional.of(runningCiFixRun("run-no-change", "ship-no-change")));
        when(turnStore.findTurnById("turn-no-change")).thenReturn(Optional.of(new ThreadTurn(
                "turn-no-change", "thread-1", "ship-no-change", ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "diagnose", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended("ci-fix-shipped"), REMOTE_STAGE_ID,
                ThreadScope.STAGE, "run-no-change")));
        when(git.hasUncommittedChanges(Path.of(WORKTREE_PATH))).thenReturn(false);
        CiFixRunExecutor executor = newExecutor();

        executor.autoPushAfterCiFix(
                new TaskTurnFinishedEvent("ship-no-change", "turn-no-change", false, false));

        verify(git, after(500).never()).pushForceWithLease(any());
        verify(agentRuns, timeout(2000)).updateHeadline(
                "run-no-change", "No code changes; retry CI manually");
        verify(agentRuns, timeout(2000)).transition(
                "run-no-change", AgentRun.STATUS_FAILED, "no_code_changes");
        verify(phaseMachine, timeout(2000)).parkOperational(
                "ship-no-change", Actor.AGENT, "ci_fix_no_changes");
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
        when(scheduler.enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("turn-id");
        CiFixRunExecutor executor = newExecutor();

        executor.driveShippedCiFix(task, REPO, failingCi());

        ArgumentCaptor<String> promptArg = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueStageTurn(
                any(), promptArg.capture(), eq("ship-seed"), any(), any(), eq("run-1"), any());
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

        verify(agentRuns).updateHeadline("run-1", "CI passed after 2 attempts");
        verify(agentRuns).transition("run-1", AgentRun.STATUS_SUCCEEDED, "checks_green");
    }

    @Test
    void closeIfGreenIsANoOpWithNoLiveRun()
    {
        Task task = newShippedTask("ship-none", "thread-1");
        when(agentRuns.findByTask("ship-none", AgentRun.KIND_CI_FIX, null)).thenReturn(List.of());
        CiFixRunExecutor executor = newExecutor();

        executor.closeIfGreen(task);

        verify(agentRuns, never()).updateHeadline(any(), any());
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
        when(workspaceStore.findRepo(eq(thread.workspaceId()), eq(REPO)))
                .thenReturn(Optional.of(new WorkspaceRepo(
                        thread.workspaceId(), REPO, /* defaultBaseBranch */ null,
                        autoFixEnabled, NOW)));
        when(leaseService.isHeldByAnotherTask(eq(WORKTREE_PATH), anyString())).thenReturn(leaseHeld);
        when(threadStore.findThreadById(eq(thread.id()))).thenReturn(Optional.of(thread));
        when(scheduler.enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any()))
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

    private static AgentRun succeededCiFixRun(String runId, String taskId)
    {
        return new AgentRun(
                runId, taskId, AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, null, REMOTE_STAGE_ID, AgentRun.STATUS_SUCCEEDED,
                1, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS, null, null, NOW, NOW);
    }

    private static AgentRun runningCiFixRun(String runId, String taskId)
    {
        return new AgentRun(
                runId, taskId, AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, null, REMOTE_STAGE_ID, AgentRun.STATUS_RUNNING,
                1, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS, null, null, NOW, null);
    }

    private static AgentRun queuedCiFixRun(String runId, String taskId)
    {
        return new AgentRun(
                runId, taskId, AgentRun.KIND_CI_FIX, AgentRun.SOURCE_REMOTE,
                REMOTE_STAGE_ID, null, REMOTE_STAGE_ID, AgentRun.STATUS_QUEUED,
                1, CiFixRunExecutor.MAX_CI_FIX_ATTEMPTS, null, null, NOW, null);
    }

    private static ThreadTurn ciFixTurn(String turnId, Task task, String runId)
    {
        return new ThreadTurn(
                turnId, task.threadId(), task.id(), ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "fix", NOW, NOW, NOW, NOW, null,
                TurnInitiator.unattended("ci-fix-shipped"), REMOTE_STAGE_ID,
                ThreadScope.STAGE, runId);
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
                ThreadFlow.BUILD, WORKSPACE_ID, null, null);
    }
}
