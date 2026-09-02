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
package com.bytequay.app.agent;

import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessOwner;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentProcessLaunch;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAgentStartupRecovery
{
    @Test
    void drainsBeforeAndAfterMaintenanceThenOpensTheGate()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentMaintenance maintenance = maintenance(taskStore, turnStore);
        AgentServingGate servingGate = new AgentServingGate();
        AgentEventOutboxEntry before = event("before");
        AgentEventOutboxEntry after = event("after");
        when(outboxStore.listPending(100)).thenReturn(
                ImmutableList.of(before),
                ImmutableList.of(),
                ImmutableList.of(after),
                ImmutableList.of());
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(stopped("stale-launch")));

        new AgentStartupRecovery(outboxStore, eventService, maintenance, supervisor, servingGate,
                launchStore(), taskStore, FIXED_CLOCK)
                .afterSingletonsInstantiated();

        InOrder order = inOrder(eventService, turnStore, taskStore, supervisor);
        order.verify(eventService).drain(before.eventId());
        order.verify(turnStore).listRecoverable();
        order.verify(taskStore).listLive();
        order.verify(eventService).drain(after.eventId());
        order.verify(supervisor).recoverOwnedProcesses();
        order.verify(supervisor).acknowledge("stale-launch");
        assertThat(servingGate.isReady()).isTrue();
    }

    @Test
    void keepsTheGateClosedWhenOutboxRecoveryFails()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentMaintenance maintenance = maintenance(taskStore, turnStore);
        AgentServingGate servingGate = new AgentServingGate();
        AgentEventOutboxEntry event = event("broken");
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of(event));
        doThrow(new IllegalStateException("broken log")).when(eventService).drain(event.eventId());

        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);

        new AgentStartupRecovery(outboxStore, eventService, maintenance, supervisor, servingGate,
                launchStore(), taskStore, FIXED_CLOCK)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isFalse();
        verifyNoInteractions(turnStore, taskStore, supervisor);
    }

    @Test
    void keepsTheGateClosedWhenMaintenanceRecoveryFails()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        when(turnStore.listRecoverable()).thenThrow(new IllegalStateException("broken recovery"));

        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);

        new AgentStartupRecovery(
                outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, FIXED_CLOCK)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isFalse();
        verifyNoInteractions(eventService, taskStore, supervisor);
    }

    @Test
    void keepsTheGateClosedWhenAnOwnedProcessCannotBeVerifiedStopped()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses())
                .thenReturn(ImmutableList.of(stopped("clean"), unverifiable("blocked")));

        new AgentStartupRecovery(
                outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, FIXED_CLOCK)
                .afterSingletonsInstantiated();

        // An old process may still be writing to a worktree, so no agent operation may be served.
        assertThat(servingGate.isReady()).isFalse();
        verify(supervisor).acknowledge("clean");
        verify(supervisor, never()).acknowledge("blocked");
    }

    @Test
    void blocksOnlyTheTaskThatLeftAnUnverifiableProcess()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        TaskId taskId = TaskId.random();
        when(taskStore.find(taskId)).thenReturn(Optional.of(openTask(taskId)));
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(
                stopped("clean"), unverifiableFor("leaked", taskId.toString())));

        new AgentStartupRecovery(
                outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, FIXED_CLOCK)
                .afterSingletonsInstantiated();

        // The launch names one task, so only that task's worktree is held and every other task can
        // still be served.
        assertThat(servingGate.isReady()).isTrue();
        ArgumentCaptor<AgentTask> held = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskStore).update(held.capture());
        assertThat(held.getValue().worktreeMutationClaim().orElseThrow().kind())
                .isEqualTo(WorktreeMutationClaim.Kind.RECOVERY);
        assertThat(held.getValue().worktreeMutationClaim().orElseThrow().ownerId()).isEqualTo("leaked");
        // The marker is kept for inspection, so the block survives another restart.
        verify(supervisor).acknowledge("clean");
        verify(supervisor, never()).acknowledge("leaked");
    }

    @Test
    void keepsBlockingEverythingWhenAnUnverifiableProcessNamesNoLiveTask()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        TaskId taskId = TaskId.random();
        when(taskStore.find(taskId)).thenReturn(Optional.empty());
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses())
                .thenReturn(ImmutableList.of(unverifiableFor("orphan", taskId.toString())));

        new AgentStartupRecovery(
                outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, FIXED_CLOCK)
                .afterSingletonsInstantiated();

        // Nothing can be pinned to a worktree, so something may still be writing somewhere nobody
        // can name and no agent operation may be served.
        assertThat(servingGate.isReady()).isFalse();
        verify(taskStore, never()).update(any());
    }

    private static AgentTask openTask(TaskId taskId)
    {
        return AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Recovered task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-recovery-" + taskId),
                AgentPermissionPolicy.AUTO,
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);

    private static AgentProcessLaunchStore launchStore(AgentProcessLaunch... rows)
    {
        AgentProcessLaunchStore store = mock(AgentProcessLaunchStore.class);
        when(store.list()).thenReturn(ImmutableList.copyOf(rows));
        return store;
    }

    private static OwnedProcessRecovery unverifiableFor(String launchId, String taskId)
    {
        return new OwnedProcessRecovery(
                launchId,
                Optional.of(new ProcessOwner(ProcessOwner.Kind.APP_SERVER, taskId)),
                Optional.empty(),
                OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                "unverifiable");
    }

    private static OwnedProcessRecovery stopped(String launchId)
    {
        return new OwnedProcessRecovery(
                launchId,
                Optional.empty(),
                Optional.empty(),
                OwnedProcessRecovery.Outcome.VERIFIED_STOPPED,
                "stopped");
    }

    private static OwnedProcessRecovery unverifiable(String launchId)
    {
        return new OwnedProcessRecovery(
                launchId,
                Optional.empty(),
                Optional.empty(),
                OwnedProcessRecovery.Outcome.UNVERIFIABLE,
                "unverifiable");
    }

    private static AgentMaintenance maintenance(AgentTaskStore taskStore, AgentTurnStore turnStore)
    {
        return new AgentMaintenance(
                taskStore,
                turnStore,
                mock(AgentUnitOfWork.class),
                mock(AgentEventOutboxStore.class),
                mock(AgentApprovalService.class),
                mock(AgentMetricsService.class),
                mock(AgentCompletionService.class),
                new AgentRuntimeRegistry(),
                Clock.systemUTC());
    }

    private static AgentEventOutboxEntry event(String message)
    {
        return new AgentEventOutboxEntry(
                EventId.random(),
                TaskId.random(),
                Optional.empty(),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.Error("startup", message, false)),
                Instant.parse("2026-09-01T00:00:00Z"),
                Optional.empty());
    }
}
