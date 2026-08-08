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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.BranchGuard;
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
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.BranchGuardStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationCheck;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.RebaseOutcome;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.runs.AgentRunServiceImpl;
import com.bytequay.app.service.stage.RemoteDevelopmentStageService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.PullRequestDirtyDetectedEvent;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestBranchGuardJob
{
    private static final String TASK_ID = "t1.k1";
    private static final Path WORKTREE = Path.of("/tmp/wt");
    private static final UUID REMOTE_STAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final BranchGuardStore guards = mock(BranchGuardStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final AgentRunServiceImpl agentRuns = mock(AgentRunServiceImpl.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final RemoteDevelopmentStageService remoteStages = mock(RemoteDevelopmentStageService.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);

    @Test
    void skipsEntirelyWhenTheThreadIsNotIdle()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(guard(BranchGuard.STATE_HEALTHY)));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread(ThreadStatus.RUNNING)));

        job.checkOne(guard(BranchGuard.STATE_HEALTHY));

        verifyNoInteractions(git);
        verify(guards, never()).save(any());
    }

    @Test
    void skipsTerminalTasks()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(guard(BranchGuard.STATE_HEALTHY)));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.COMPLETED)));

        job.checkOne(guard(BranchGuard.STATE_HEALTHY));

        verifyNoInteractions(git);
        verify(guards, never()).save(any());
    }

    @Test
    void healthyWhenBaseHasNotMovedBeyondTheMergeBase()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main"));
        when(pullRequests.getPullRequestDetail("acme/widgets", 42)).thenReturn(greenDetail());

        job.checkOne(guard(BranchGuard.STATE_DRIFTING));

        verify(git, never()).rebasePreview(any(), any(), any());
        verify(guards).save(argThat(g -> BranchGuard.STATE_HEALTHY.equals(g.state())
                && g.health().behindBy() == 0 && Boolean.TRUE.equals(g.health().mergeable())
                && Boolean.TRUE.equals(g.health().checksGreen())));
    }

    @Test
    void redRemoteCiOnAnUpToDateBranchNeverOpensARunOrNotifies()
            throws Exception
    {
        // The boundary rule (plan-rail-runs.md R18): CI failures are exclusively
        // AutomationCoordinator's territory. The guard only mirrors the last known
        // CI state on the chip — it must never react by opening a run itself.
        BranchGuardJob job = job(List.of());
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main"));
        when(pullRequests.getPullRequestDetail("acme/widgets", 42)).thenReturn(redDetail());

        job.checkOne(guard(BranchGuard.STATE_HEALTHY));

        verifyNoInteractions(agentRuns);
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
        verify(guards).save(argThat(g -> BranchGuard.STATE_HEALTHY.equals(g.state())
                && Boolean.FALSE.equals(g.health().checksGreen())));
    }

    @Test
    void happyPathRebasesRunsChecksAndPushes()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-old"));
        when(git.rebasePreview(WORKTREE, "HEAD", "origin/main")).thenReturn(RebaseOutcome.CLEAN);
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.openInStage(eq(TASK_ID), eq(AgentRun.KIND_BRANCH_GUARD),
                eq(AgentRun.SOURCE_SCHEDULED), eq(REMOTE_STAGE_ID.toString()), eq(null))).thenReturn(run);

        job.checkOne(guard(BranchGuard.STATE_HEALTHY));

        verify(git).rebase(WORKTREE, "origin/main");
        verify(git).pushForceWithLease(WORKTREE);
        verify(agentRuns).transition(run.id(), AgentRun.STATUS_SUCCEEDED, "rebased_and_pushed");
        verify(guards).save(argThatState(BranchGuard.STATE_HEALTHY));
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reactiveEventDrivesTheSameCheckAsTick()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-old"));
        when(git.rebasePreview(WORKTREE, "HEAD", "origin/main")).thenReturn(RebaseOutcome.CLEAN);
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.openInStage(eq(TASK_ID), eq(AgentRun.KIND_BRANCH_GUARD),
                eq(AgentRun.SOURCE_SCHEDULED), eq(REMOTE_STAGE_ID.toString()), eq(null))).thenReturn(run);

        job.onPullRequestDirtyDetected(new PullRequestDirtyDetectedEvent(TASK_ID));

        verify(git).rebase(WORKTREE, "origin/main");
        verify(git).pushForceWithLease(WORKTREE);
        verify(guards).save(argThatState(BranchGuard.STATE_HEALTHY));
    }

    @Test
    void reactiveEventNoOpsWhenTheGuardIsGone()
    {
        BranchGuardJob job = job(List.of());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.empty());

        job.onPullRequestDirtyDetected(new PullRequestDirtyDetectedEvent(TASK_ID));

        verifyNoInteractions(git);
        verify(guards, never()).save(any());
    }

    @Test
    void conflictingPreviewHandsOffToAnAgentFixTurnInstead()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-old"));
        when(git.rebasePreview(WORKTREE, "HEAD", "origin/main")).thenReturn(RebaseOutcome.CONFLICTS);
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.openInStage(any(), any(), any(), any(), any())).thenReturn(run);

        job.checkOne(guard(BranchGuard.STATE_HEALTHY));

        verify(git, never()).rebase(any(), any());
        verify(git, never()).pushForceWithLease(any());
        verify(scheduler).enqueueStageTurn(any(Thread.class), anyString(), eq(TASK_ID),
                eq(run.stageId()), any(TurnInitiator.class), eq(run.id()), any());
        verify(guards).save(argThatState(BranchGuard.STATE_CONFLICTED));
        verify(guards).save(argThatState(BranchGuard.STATE_FIXING));
        verify(agentRuns, never()).transition(any(), eq(AgentRun.STATUS_FAILED), any());
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
    }

    @Test
    void checkFailuresAfterACleanRebaseParkNeedsAttentionWithoutPushing()
            throws Exception
    {
        ValidationCheck failingCheck = mock(ValidationCheck.class);
        when(failingCheck.run(eq(TASK_ID), eq(WORKTREE)))
                .thenReturn(List.of(new ValidationFailure("test", "it broke")));
        BranchGuardJob job = job(List.of(failingCheck));
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-old"));
        when(git.rebasePreview(WORKTREE, "HEAD", "origin/main")).thenReturn(RebaseOutcome.CLEAN);
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.openInStage(any(), any(), any(), any(), any())).thenReturn(run);

        job.checkOne(guard(BranchGuard.STATE_HEALTHY));

        verify(git).rebase(WORKTREE, "origin/main");
        verify(git, never()).pushForceWithLease(any());
        verify(agentRuns).transition(run.id(), AgentRun.STATUS_FAILED, "checks_failed_after_rebase");
        verify(guards).save(argThatState(BranchGuard.STATE_NEEDS_ATTENTION));
        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "branch_guard_needs_attention", Actor.AGENT);
    }

    @Test
    void aSecondTickCannotReviveAGuardParkedByTheFirstTick()
            throws Exception
    {
        ValidationCheck failingCheck = mock(ValidationCheck.class);
        when(failingCheck.run(eq(TASK_ID), eq(WORKTREE)))
                .thenReturn(List.of(new ValidationFailure("test", "it broke")));
        BranchGuardJob job = job(List.of(failingCheck));
        wireIdleTask();
        AtomicReference<BranchGuard> stored = new AtomicReference<>(guard(BranchGuard.STATE_HEALTHY));
        when(guards.findByTask(TASK_ID)).thenAnswer(ignored -> Optional.of(stored.get()));
        when(guards.save(any())).thenAnswer(invocation -> {
            BranchGuard saved = invocation.getArgument(0);
            stored.set(saved);
            return saved;
        });
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-old"));
        when(git.rebasePreview(WORKTREE, "HEAD", "origin/main")).thenReturn(RebaseOutcome.CLEAN);
        when(agentRuns.openInStage(any(), any(), any(), any(), any()))
                .thenReturn(run(AgentRun.STATUS_RUNNING));

        job.checkOne(stored.get());
        job.checkOne(stored.get());

        verify(git, times(1)).fetch(WORKTREE);
        verify(git, times(1)).rebase(WORKTREE, "origin/main");
        verify(git, never()).pushForceWithLease(any());
        verify(guards, never()).save(argThatState(BranchGuard.STATE_HEALTHY));
    }

    @Test
    void fixTurnFinishingCaughtUpRunsChecksAndPushes()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        BranchGuard fixing = guard(BranchGuard.STATE_FIXING).withLastRun("run1", Instant.now());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(fixing));
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.findById("run1")).thenReturn(Optional.of(run));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(), "run1")));
        when(git.hasUncommittedChanges(WORKTREE)).thenReturn(false);
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-new"));

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(git).pushForceWithLease(WORKTREE);
        verify(agentRuns).transition("run1", AgentRun.STATUS_SUCCEEDED, "rebased_and_pushed");
        verify(guards).save(argThatState(BranchGuard.STATE_HEALTHY));
    }

    @Test
    void taskParkedWhileAFixTurnIsBeingVerifiedNeverPushes()
            throws Exception
    {
        AtomicReference<Task> storedTask = new AtomicReference<>(task());
        ValidationCheck check = mock(ValidationCheck.class);
        when(check.run(eq(TASK_ID), eq(WORKTREE))).thenAnswer(ignored -> {
            storedTask.set(task(TaskStatus.NEEDS_ATTENTION, TaskPhase.NEEDS_ATTENTION));
            return List.of();
        });
        BranchGuardJob job = job(List.of(check));
        when(taskStore.findTaskById(TASK_ID)).thenAnswer(ignored -> Optional.of(storedTask.get()));
        BranchGuard fixing = guard(BranchGuard.STATE_FIXING).withLastRun("run1", Instant.now());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(fixing));
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.findById("run1")).thenReturn(Optional.of(run));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(), "run1")));
        when(git.hasUncommittedChanges(WORKTREE)).thenReturn(false);
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-new"));

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(git, never()).pushForceWithLease(any());
        verify(agentRuns).transition("run1", AgentRun.STATUS_FAILED, "guard_stopped_before_push");
        verify(guards, never()).save(argThatState(BranchGuard.STATE_HEALTHY));
    }

    @Test
    void fixTurnFinishingStillUnresolvedParksNeedsAttentionWithoutTouchingTheWorktree()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        BranchGuard fixing = guard(BranchGuard.STATE_FIXING).withLastRun("run1", Instant.now());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(fixing));
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.findById("run1")).thenReturn(Optional.of(run));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(), "run1")));
        when(git.hasUncommittedChanges(WORKTREE)).thenReturn(true);

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(git, never()).pushForceWithLease(any());
        verify(guards).save(argThatState(BranchGuard.STATE_NEEDS_ATTENTION));
        verify(notifications).notifyNeedsAttention(eq("t1"), eq(TASK_ID), anyString());
        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "branch_guard_needs_attention", Actor.AGENT);
    }

    @Test
    void fixTurnFinishedIgnoresATurnFromAnUnrelatedRun()
    {
        BranchGuardJob job = job(List.of());
        BranchGuard fixing = guard(BranchGuard.STATE_FIXING).withLastRun("run1", Instant.now());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(fixing));
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.findById("run1")).thenReturn(Optional.of(run));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn(REMOTE_STAGE_ID.toString(), "other-run")));

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(guards, never()).save(any());
        verifyNoInteractions(git);
    }

    private void wireIdleTask()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread(ThreadStatus.IDLE)));
        when(remoteStages.ensureOpen(TASK_ID)).thenReturn(remoteStage());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(guard(BranchGuard.STATE_HEALTHY)));
    }

    @Test
    void legacyBranchGuardNeverClaimsAV2Task()
            throws Exception
    {
        when(taskStore.isV2Task(TASK_ID)).thenReturn(true);

        job(List.of()).checkOne(guard(BranchGuard.STATE_HEALTHY));

        verify(guards, never()).findByTask(TASK_ID);
        verify(git, never()).fetch(any());
    }

    private BranchGuardJob job(List<ValidationCheck> checks)
    {
        return new BranchGuardJob(guards, taskStore, threadStore, scheduler, turnStore, git, checks,
                agentRuns, notifications, pullRequests, new ObjectMapper(), remoteStages, phaseMachine);
    }

    private static BranchGuard argThatState(String state)
    {
        return argThat(g -> g != null && state.equals(g.state()));
    }

    private static BranchGuard guard(String state)
    {
        return new BranchGuard(
                TASK_ID, true, BranchGuard.SCHEDULE_NIGHTLY, state, BranchGuard.Health.UNKNOWN, null, null);
    }

    private static PullRequestDetail greenDetail()
    {
        return new PullRequestDetail(
                "acme/widgets", 42, null, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.PASSING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "completed", "success", null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static PullRequestDetail redDetail()
    {
        return new PullRequestDetail(
                "acme/widgets", 42, null, List.of(), false,
                null, null, 0, 0, 0, 0, 0, 0, 0, List.of(),
                PullRequestDetail.CiStatus.FAILING, List.of(), List.of(),
                List.of(new PullRequestDetail.CheckRun(
                        null, "backend-tests", "completed", "failure", null, null, null)),
                List.of(), List.of(), false,
                null, null, null, null, null, "open", false, false);
    }

    private static AgentRun run(String status)
    {
        return new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_BRANCH_GUARD, AgentRun.SOURCE_SCHEDULED,
                REMOTE_STAGE_ID.toString(), null,
                REMOTE_STAGE_ID.toString(), status, 0, null, null, null, Instant.now(), null);
    }

    private static ThreadTurn turn(String stageId)
    {
        return turn(stageId, null);
    }

    private static ThreadTurn turn(String stageId, String runId)
    {
        return new ThreadTurn(
                "turn-1", "t1", TASK_ID, ThreadResourceLane.CLI, ThreadTurnStatus.COMPLETED, "prompt",
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), null,
                TurnInitiator.unattended("branch-guard-fix"), stageId, ThreadScope.STAGE, runId);
    }

    private static StageInstance remoteStage()
    {
        return new StageInstance(
                REMOTE_STAGE_ID, TASK_ID, StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.OPEN, Instant.now(), null, null);
    }

    private static Thread thread(ThreadStatus status)
    {
        Instant now = Instant.now();
        return new Thread(
                "t1", ThreadKind.CLI_AGENT, "claude-code", null, "Guard test", status,
                "claude-sonnet-4.6", 0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static Task task()
    {
        return task(TaskStatus.IN_REVIEW);
    }

    private static Task task(TaskStatus status)
    {
        return task(status, status == TaskStatus.COMPLETED
                ? TaskPhase.COMPLETED
                : TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    private static Task task(TaskStatus status, TaskPhase phase)
    {
        return new Task(
                TASK_ID, "t1", 1L, status, "dev/x", WORKTREE.toString(), "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", 42, null,
                0L, 0L, 0L, null, Instant.now(), null, null, null, null, null,
                null, phase,
                null, 0, "acme/widgets#42");
    }
}
