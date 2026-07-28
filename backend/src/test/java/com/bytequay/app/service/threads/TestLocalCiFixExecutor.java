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
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.runs.AgentRunService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalCiFixExecutor
{
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");
    private static final String STAGE_ID = "00000000-0000-0000-0000-0000000000a1";
    private static final String WORKTREE = "/tmp/acme/.worktrees/task-1";
    private static final List<ValidationFailure> FAILURES =
            List.of(new ValidationFailure("test", "AppTest.foo failed"));

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final WorktreeLeaseService leaseService = mock(WorktreeLeaseService.class);
    private final TaskStore taskStore = mock(TaskStore.class);

    private final LocalCiFixExecutor executor = new LocalCiFixExecutor(
            threadStore, stageStore, agentRuns, scheduler, leaseService, taskStore);

    @Test
    void queuesAFixTurnWhenAnIdleTaskFailsLocalCi()
    {
        wire("task-1", "thread-1", ThreadStatus.IDLE, /* iterations */ 0);
        when(scheduler.enqueueStageTurn(any(), any(), any(), any(), any(), any(), any())).thenReturn("turn-1");

        boolean queued = executor.tryFix(newTask("task-1", "thread-1", WORKTREE), FAILURES);

        assertThat(queued).isTrue();
        verify(scheduler).enqueueStageTurn(any(), any(), eq("task-1"), eq(STAGE_ID), any(), any(), any());
        verify(agentRuns).recordIteration(eq("run-1"), any());
    }

    @Test
    void parksWhenTheFixBudgetIsSpent()
    {
        wire("task-1", "thread-1", ThreadStatus.IDLE, /* iterations */ LocalCiFixExecutor.MAX_ATTEMPTS);

        boolean queued = executor.tryFix(newTask("task-1", "thread-1", WORKTREE), FAILURES);

        assertThat(queued).isFalse();
        verify(agentRuns).transition("run-1", AgentRun.STATUS_FAILED, "local_ci_attempts_exhausted");
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void parksWhenThereIsNoWorktreeToFixIn()
    {
        boolean queued = executor.tryFix(newTask("task-1", "thread-1", null), FAILURES);

        assertThat(queued).isFalse();
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyLocalFixExecutorNeverClaimsAV2Task()
    {
        Task task = newTask("v2-task", "thread-1", WORKTREE);
        when(taskStore.isV2Task(task.id())).thenReturn(true);

        assertThat(executor.tryFix(task, FAILURES)).isFalse();

        verify(threadStore, never()).findThreadById(any());
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWithoutParkingWhileTheAgentIsStillWorking()
    {
        when(threadStore.findThreadById("thread-1"))
                .thenReturn(Optional.of(newThread("thread-1", ThreadStatus.RUNNING)));

        boolean queued = executor.tryFix(newTask("task-1", "thread-1", WORKTREE), FAILURES);

        assertThat(queued).isTrue();
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void closeIfGreenClosesALiveLocalRun()
    {
        when(agentRuns.findByTask("task-1", AgentRun.KIND_CI_FIX, null))
                .thenReturn(List.of(run("task-1", 1)));

        executor.closeIfGreen("task-1");

        verify(agentRuns).transition("run-1", AgentRun.STATUS_SUCCEEDED, "local_checks_green");
    }

    private void wire(String taskId, String threadId, ThreadStatus status, int iterations)
    {
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(newThread(threadId, status)));
        when(leaseService.isHeldByAnotherTask(WORKTREE, taskId)).thenReturn(false);
        when(stageStore.findActiveStage(taskId)).thenReturn(Optional.of(stage(taskId)));
        when(agentRuns.openInStage(eq(taskId), eq(AgentRun.KIND_CI_FIX), eq(AgentRun.SOURCE_LOCAL),
                eq(STAGE_ID), eq(LocalCiFixExecutor.MAX_ATTEMPTS)))
                .thenReturn(run(taskId, iterations));
    }

    private static StageInstance stage(String taskId)
    {
        return new StageInstance(
                UUID.fromString(STAGE_ID), taskId, StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.OPEN, NOW, null, null);
    }

    private static AgentRun run(String taskId, int iterations)
    {
        return new AgentRun(
                "run-1", taskId, AgentRun.KIND_CI_FIX, AgentRun.SOURCE_LOCAL,
                STAGE_ID, null, STAGE_ID, AgentRun.STATUS_RUNNING, iterations,
                LocalCiFixExecutor.MAX_ATTEMPTS, null, null, NOW, null);
    }

    private static Task newTask(String id, String threadId, String worktreePath)
    {
        return new Task(
                id, threadId, 1L, TaskStatus.IDLE, "fix/" + id, worktreePath, "main",
                "/tmp/acme", null, null, null, null, null, "DEVELOP", 7, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null);
    }

    private static Thread newThread(String id, ThreadStatus status)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", null, "Local CI fix test", status,
                "claude-sonnet-4.6", 0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-1", null, null);
    }
}
