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
package com.bytequay.app.cherrypick;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecution.Workspace;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.app.agent.AgentServingGates.readyAgentServingGate;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCherryPickExecutionMonitorJob
{
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.plus(Duration.ofMinutes(2)), ZoneOffset.UTC);

    @Test
    void doesNotAdvanceWhileAgentRecoveryIsIncomplete()
    {
        CherryPickExecutionStore store = mock(CherryPickExecutionStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CherryPickExecutionService service = mock(CherryPickExecutionService.class);

        new CherryPickExecutionMonitorJob(store, taskStore, service, new AgentServingGate(), CLOCK, 20).monitor();

        verifyNoInteractions(store, taskStore, service);
    }

    @Test
    void retriesFinishedExecutionWorktreeCleanup()
    {
        CherryPickExecutionStore store = mock(CherryPickExecutionStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CherryPickExecutionService service = mock(CherryPickExecutionService.class);
        TaskId orphanedTaskId = TaskId.random();
        TaskId taskId = TaskId.random();
        AgentTask task = AgentTask.created(
                        taskId,
                        Optional.empty(),
                        false,
                        "Cherry-pick",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-cherry-pick-monitor"),
                        AgentPermissionPolicy.CHERRY_PICK,
                        NOW)
                .fail("finished", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)));
        AgentTask orphanedTask = AgentTask.created(
                        orphanedTaskId,
                        Optional.empty(),
                        false,
                        "Cherry-pick",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-orphaned-cherry-pick-monitor"),
                        AgentPermissionPolicy.CHERRY_PICK,
                        NOW.minusSeconds(1))
                .fail("finished", NOW, NOW.plus(Duration.ofDays(7)));
        CherryPickExecutionId executionId = CherryPickExecutionId.random();
        CherryPickExecution execution = finishedExecution(executionId, taskId);
        when(taskStore.listPendingWorktreeCleanup(
                AgentPermissionPolicy.CHERRY_PICK,
                NOW.plus(Duration.ofMinutes(1))))
                .thenReturn(ImmutableList.of(orphanedTask, task));
        when(store.findByAgentTaskId(orphanedTaskId.toString())).thenReturn(Optional.empty());
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(execution));

        new CherryPickExecutionMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1).monitor();

        verify(service).advance(executionId);
    }

    @Test
    void retriesTaskCompletionForFinishedExecutionWithLiveTask()
    {
        CherryPickExecutionStore store = mock(CherryPickExecutionStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CherryPickExecutionService service = mock(CherryPickExecutionService.class);
        TaskId taskId = TaskId.random();
        AgentTask task = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Cherry-pick",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-cherry-pick-live-monitor"),
                AgentPermissionPolicy.CHERRY_PICK,
                NOW);
        CherryPickExecutionId executionId = CherryPickExecutionId.random();
        when(taskStore.listLive()).thenReturn(ImmutableList.of(task));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finishedExecution(executionId, taskId)));

        new CherryPickExecutionMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1).monitor();

        verify(service).advance(executionId);
    }

    @Test
    void backsOffRecentlyFailedTaskCompletion()
    {
        CherryPickExecutionStore store = mock(CherryPickExecutionStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CherryPickExecutionService service = mock(CherryPickExecutionService.class);
        TaskId taskId = TaskId.random();
        AgentTask recentlyUpdated = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Cherry-pick",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-cherry-pick-live-backoff"),
                AgentPermissionPolicy.CHERRY_PICK,
                NOW.plusSeconds(90));
        CherryPickExecutionId executionId = CherryPickExecutionId.random();
        when(taskStore.listLive()).thenReturn(ImmutableList.of(recentlyUpdated));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finishedExecution(executionId, taskId)));

        new CherryPickExecutionMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1).monitor();

        verifyNoInteractions(service);
    }

    private static CherryPickExecution finishedExecution(CherryPickExecutionId executionId, TaskId taskId)
    {
        return CherryPickExecution.create(
                        executionId,
                        "idempotency-key",
                        new Workspace(
                                Optional.of("workspace-id"),
                                RepoRef.of("bytequay", "sample"),
                                Optional.empty(),
                                "origin",
                                "origin",
                                "main",
                                "origin-main",
                                "bytequay/task/monitor",
                                Path.of("/tmp/bytequay-cherry-pick-monitor"),
                                "codex"),
                        Optional.empty(),
                        new Range("1111111111111111111111111111111111111111", "2222222222222222222222222222222222222222", "3333333333333333333333333333333333333333"),
                        ImmutableList.of(CherryPickItem.planned(
                                1,
                                "2222222222222222222222222222222222222222",
                                "1111111111111111111111111111111111111111",
                                "Commit",
                                Optional.empty(),
                                Disposition.TO_CHERRY_PICK)),
                        NOW)
                .withAgentTask(taskId.toString(), NOW.plusSeconds(1))
                .finish(FinishReason.CANCELLED, "Cancelled", NOW.plusSeconds(2));
    }
}
