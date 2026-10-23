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
package com.bytequay.app.scheduler;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.backport.BackportChainService;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.rebase.BranchRebaseService;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.rebase.BranchRebaseStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.app.agent.runtime.AgentServingGates.readyAgentServingGate;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestCiAutofixMonitoring
{
    private static final Instant MONITOR_NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(MONITOR_NOW, ZoneOffset.UTC);

    private final AgentTaskStore taskStore = mock(AgentTaskStore.class);
    private final CiAutofixService service = mock(CiAutofixService.class);
    private final CiAutofixStore store = mock(CiAutofixStore.class);

    @Test
    void advancesABoundedBatchAndContinuesAfterOneRunFails()
    {
        CiAutofixRun first = run();
        CiAutofixRun second = run();
        when(store.listNonFinished(20)).thenReturn(ImmutableList.of(first, second));
        doThrow(new IllegalStateException("provider unavailable")).when(service).advance(first.id());

        monitor(store, taskStore, service, readyAgentServingGate(), CLOCK).monitorCiAutofix();

        verify(service).advance(first.id());
        verify(service).advance(second.id());
    }

    @ParameterizedTest
    @CsvSource({"2026-08-18T00:00:00Z, 1", "2026-09-01T00:00:00Z, 0"})
    void retriesTaskCompletionAfterBackoff(String updatedAt, int expectedAdvances)
    {
        TaskId taskId = TaskId.random();
        CiAutofixRun finished = run()
                .assignAgentTask(taskId.toString(), Instant.parse("2026-08-18T00:00:01Z"))
                .finish(CiAutofixRun.FinishReason.CANCELLED, "cancelled", Instant.parse("2026-08-18T00:00:02Z"));
        AgentTask task = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "CI Autofix",
                AgentLaneId.CLAUDE_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-ci-monitor"),
                AgentPermissionPolicy.CI_AUTOFIX,
                Instant.parse(updatedAt));
        when(taskStore.listLive()).thenReturn(ImmutableList.of(task));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finished));

        monitor(store, taskStore, service, readyAgentServingGate(), CLOCK).monitorCiAutofix();

        verify(service, times(expectedAdvances)).advance(finished.id());
        verifyNoMoreInteractions(service);
    }

    @Test
    void retriesWorktreeCleanupForFinishedRunsWithTerminalTasks()
    {
        TaskId unfinishedTaskId = TaskId.random();
        TaskId taskId = TaskId.random();
        CiAutofixRun finished = run()
                .assignAgentTask(taskId.toString(), Instant.parse("2026-08-18T00:00:01Z"))
                .finish(CiAutofixRun.FinishReason.GREEN, "green", Instant.parse("2026-08-18T00:00:02Z"));
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        AgentTask task = AgentTask.created(
                        taskId,
                        Optional.empty(),
                        false,
                        "CI Autofix",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-ci-cleanup-monitor"),
                        AgentPermissionPolicy.CI_AUTOFIX,
                        now)
                .fail("finished", now.plusSeconds(1), now.plus(Duration.ofDays(7)));
        AgentTask unfinishedTask = AgentTask.created(
                        unfinishedTaskId,
                        Optional.empty(),
                        false,
                        "CI Autofix",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-ci-blocked-cleanup-monitor"),
                        AgentPermissionPolicy.CI_AUTOFIX,
                        now.minusSeconds(1))
                .fail("finished", now, now.plus(Duration.ofDays(7)));
        CiAutofixRun unfinished = run()
                .assignAgentTask(unfinishedTaskId.toString(), Instant.parse("2026-08-18T00:00:01Z"));
        when(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.CI_AUTOFIX, MONITOR_NOW.minus(Duration.ofMinutes(1))))
                .thenReturn(ImmutableList.of(unfinishedTask, task));
        when(store.findByAgentTaskId(unfinishedTaskId.toString())).thenReturn(Optional.of(unfinished));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finished));

        monitor(store, taskStore, service, readyAgentServingGate(), CLOCK).monitorCiAutofix();

        verify(service).advance(finished.id());
    }

    @Test
    void doesNotAdvanceWhileAgentRecoveryIsIncomplete()
    {
        monitor(store, taskStore, service, new AgentServingGate(), CLOCK).monitorCiAutofix();

        verifyNoInteractions(store, taskStore, service);
    }

    @Test
    void excludesDevelopmentOwnedCiRunsFromBothCleanupPasses()
    {
        AgentTask task = liveTask();
        CiAutofixRun finished = run(Optional.of("parent-task"))
                .assignAgentTask(task.id().toString(), MONITOR_NOW.minusSeconds(90))
                .finish(CiAutofixRun.FinishReason.CANCELLED, "cancelled", MONITOR_NOW.minusSeconds(80));
        when(taskStore.listLive()).thenReturn(ImmutableList.of(task));
        when(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.CI_AUTOFIX, MONITOR_NOW.minusSeconds(60))).thenReturn(ImmutableList.of(task));
        when(store.findByAgentTaskId(task.id().toString())).thenReturn(Optional.of(finished));

        monitor(store, taskStore, service, readyAgentServingGate(), CLOCK).monitorCiAutofix();

        verify(store, times(2)).findByAgentTaskId(task.id().toString());
        verifyNoInteractions(service);
    }

    @Test
    void selectsEachCleanupPassAfterAdvancingThePreviousPassEvenWhenCleanupFails()
    {
        CiAutofixRun running = run();
        AgentTask task = liveTask();
        CiAutofixRun finished = run().assignAgentTask(task.id().toString(), MONITOR_NOW.minusSeconds(90))
                .finish(CiAutofixRun.FinishReason.GREEN, "green", MONITOR_NOW.minusSeconds(80));
        when(store.listNonFinished(20)).thenReturn(ImmutableList.of(running));
        when(taskStore.listLive()).thenReturn(ImmutableList.of(task));
        when(taskStore.listPendingWorktreeCleanup(AgentPermissionPolicy.CI_AUTOFIX, MONITOR_NOW.minusSeconds(60))).thenReturn(ImmutableList.of(task));
        when(store.findByAgentTaskId(task.id().toString())).thenReturn(Optional.of(finished));
        doThrow(new IllegalStateException("cleanup still pending")).when(service).advance(finished.id());

        monitor(store, taskStore, service, readyAgentServingGate(), CLOCK).monitorCiAutofix();

        var order = inOrder(service, taskStore);
        order.verify(service).advance(running.id());
        order.verify(taskStore).listLive();
        order.verify(service).advance(finished.id());
        order.verify(taskStore).listPendingWorktreeCleanup(AgentPermissionPolicy.CI_AUTOFIX, MONITOR_NOW.minusSeconds(60));
        order.verify(service).advance(finished.id());
    }

    private static AgentTask liveTask()
    {
        return AgentTask.created(TaskId.random(), Optional.empty(), false, "CI Autofix", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"), Path.of("/tmp/bytequay-ci-monitor"), AgentPermissionPolicy.CI_AUTOFIX, MONITOR_NOW.minusSeconds(120));
    }

    private static WorkflowMonitorJob monitor(CiAutofixStore store, AgentTaskStore taskStore, CiAutofixService service, AgentServingGate gate, Clock clock)
    {
        return new WorkflowMonitorJob(mock(CherryPickExecutionStore.class), mock(CherryPickExecutionService.class), store, service,
                mock(BranchRebaseStore.class), mock(BranchRebaseService.class), mock(BackportChainStore.class), mock(BackportChainService.class), taskStore, gate, clock);
    }

    private static CiAutofixRun run()
    {
        return run(Optional.empty());
    }

    private static CiAutofixRun run(Optional<String> parentTaskId)
    {
        CiAutofixRunId id = CiAutofixRunId.random();
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        return CiAutofixRun.create(
                id,
                id.toString(),
                new Scope(
                        PullRequestRef.of("bytequay", "sample", 1),
                        RepoRef.of("bytequay", "sample"),
                        "workspace-1",
                        RepoRef.of("bytequay", "sample"),
                        "author",
                        "feature",
                        "main",
                        "codex", parentTaskId),
                "a".repeat(40),
                "a".repeat(40),
                "b".repeat(40),
                new Authorization(now),
                now);
    }
}
