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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.BranchGuard;
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
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestBranchGuardJob
{
    private static final String TASK_ID = "t1.k1";
    private static final Path WORKTREE = Path.of("/tmp/wt");

    private final BranchGuardStore guards = mock(BranchGuardStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final NotificationService notifications = mock(NotificationService.class);

    @Test
    void skipsEntirelyWhenTheThreadIsNotIdle()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread(ThreadStatus.RUNNING)));

        job.checkOne(guard(BranchGuard.STATE_IN_SYNC));

        verifyNoInteractions(git);
        verify(guards, never()).save(any());
    }

    @Test
    void inSyncWhenBaseHasNotMovedBeyondTheMergeBase()
            throws Exception
    {
        BranchGuardJob job = job(List.of());
        wireIdleTask();
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main"));

        job.checkOne(guard(BranchGuard.STATE_DRIFTING));

        verify(git, never()).rebasePreview(any(), any(), any());
        verify(guards).save(argThatState(BranchGuard.STATE_IN_SYNC));
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
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_BRANCH_GUARD), eq(AgentRun.SOURCE_SCHEDULED),
                eq(null), eq(StageType.BRANCH_GUARD_STAGE), eq(null))).thenReturn(run);

        job.checkOne(guard(BranchGuard.STATE_IN_SYNC));

        verify(git).rebase(WORKTREE, "origin/main");
        verify(git).pushForceWithLease(WORKTREE);
        verify(agentRuns).transition(run.id(), AgentRun.STATUS_SUCCEEDED, "rebased_and_pushed");
        verify(guards).save(argThatState(BranchGuard.STATE_IN_SYNC));
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any(), any());
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
        when(agentRuns.open(any(), any(), any(), any(), any(), any())).thenReturn(run);

        job.checkOne(guard(BranchGuard.STATE_IN_SYNC));

        verify(git, never()).rebase(any(), any());
        verify(git, never()).pushForceWithLease(any());
        verify(scheduler).enqueueTaskTurn(any(Thread.class), anyString(), eq(TASK_ID),
                eq(run.stageId()), any(TurnInitiator.class));
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
        when(agentRuns.open(any(), any(), any(), any(), any(), any())).thenReturn(run);

        job.checkOne(guard(BranchGuard.STATE_IN_SYNC));

        verify(git).rebase(WORKTREE, "origin/main");
        verify(git, never()).pushForceWithLease(any());
        verify(agentRuns).transition(run.id(), AgentRun.STATUS_FAILED, "checks_failed_after_rebase");
        verify(guards).save(argThatState(BranchGuard.STATE_NEEDS_ATTENTION));
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
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn("stage1")));
        when(git.hasUncommittedChanges(WORKTREE)).thenReturn(false);
        when(git.resolveCommitSha(WORKTREE, "origin/main")).thenReturn(Optional.of("sha-main-new"));
        when(git.mergeBase(WORKTREE, "HEAD", "origin/main")).thenReturn(Optional.of("sha-main-new"));

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(git).pushForceWithLease(WORKTREE);
        verify(agentRuns).transition("run1", AgentRun.STATUS_SUCCEEDED, "rebased_and_pushed");
        verify(guards).save(argThatState(BranchGuard.STATE_IN_SYNC));
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
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn("stage1")));
        when(git.hasUncommittedChanges(WORKTREE)).thenReturn(true);

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(git, never()).pushForceWithLease(any());
        verify(guards).save(argThatState(BranchGuard.STATE_NEEDS_ATTENTION));
        verify(notifications).notifyNeedsAttention(eq("t1"), eq(TASK_ID), anyString());
    }

    @Test
    void fixTurnFinishedIgnoresATurnFromAnUnrelatedStage()
    {
        BranchGuardJob job = job(List.of());
        BranchGuard fixing = guard(BranchGuard.STATE_FIXING).withLastRun("run1", Instant.now());
        when(guards.findByTask(TASK_ID)).thenReturn(Optional.of(fixing));
        AgentRun run = run(AgentRun.STATUS_RUNNING);
        when(agentRuns.findById("run1")).thenReturn(Optional.of(run));
        when(turnStore.findTurnById("turn-1")).thenReturn(Optional.of(turn("some-other-stage")));

        job.onFixTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(guards, never()).save(any());
        verifyNoInteractions(git);
    }

    private void wireIdleTask()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread(ThreadStatus.IDLE)));
    }

    private BranchGuardJob job(List<ValidationCheck> checks)
    {
        return new BranchGuardJob(guards, taskStore, threadStore, scheduler, turnStore, git, checks,
                agentRuns, notifications, new ObjectMapper());
    }

    private static BranchGuard argThatState(String state)
    {
        return argThat(g -> g != null && state.equals(g.state()));
    }

    private static BranchGuard guard(String state)
    {
        return new BranchGuard(TASK_ID, true, BranchGuard.SCHEDULE_NIGHTLY, state, null, null);
    }

    private static AgentRun run(String status)
    {
        return new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_BRANCH_GUARD, AgentRun.SOURCE_SCHEDULED, null, null,
                "stage1", status, 0, null, null, null, Instant.now(), null);
    }

    private static ThreadTurn turn(String stageId)
    {
        return new ThreadTurn(
                "turn-1", "t1", TASK_ID, ThreadResourceLane.CLI, ThreadTurnStatus.COMPLETED, "prompt",
                Instant.now(), Instant.now(), Instant.now(), Instant.now(), null,
                TurnInitiator.unattended("branch-guard-fix"), stageId, ThreadScope.TASK);
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
        return new Task(
                TASK_ID, "t1", 1L, TaskStatus.IN_REVIEW, "dev/x", WORKTREE.toString(), "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", 42, null,
                0L, 0L, 0L, null, Instant.now(), null, null, null, null, null,
                null, TaskPhase.AWAITING_REMOTE_REVIEW, null, 0, "acme/widgets#42");
    }
}
