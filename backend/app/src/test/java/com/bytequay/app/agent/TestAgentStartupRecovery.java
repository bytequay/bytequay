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
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.LaunchOwnerKind;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                launchStore(), taskStore, turnStore)
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
                launchStore(), taskStore, turnStore)
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
                launchStore(), taskStore, turnStore)
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
                launchStore(), taskStore, turnStore)
                .afterSingletonsInstantiated();

        // An old process may still be writing to a worktree, so no agent operation may be served.
        assertThat(servingGate.isReady()).isFalse();
        verify(supervisor).acknowledge("clean");
        verify(supervisor, never()).acknowledge("blocked");
    }

    @Test
    void opensTheGateForUnverifiableControlOnlyMarkerOwnersAndKeepsTheirEvidence()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(
                unverifiableForOwner("claude-probe", ProcessOwner.Kind.CLI_PROBE, "claude-cli"),
                unverifiableForOwner("title-helper", ProcessOwner.Kind.CLI_PROBE, "task-title"),
                unverifiableForOwner("model-catalog", ProcessOwner.Kind.APP_SERVER, "codex-model-catalog")));

        new AgentStartupRecovery(
                outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, turnStore)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isTrue();
        verify(supervisor, never()).acknowledge("claude-probe");
        verify(supervisor, never()).acknowledge("title-helper");
        verify(supervisor, never()).acknowledge("model-catalog");
    }

    @Test
    void opensTheGateForUnverifiableControlOnlyLaunchRowsAndKeepsTheirEvidence()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(
                unverifiable("claude-probe"), unverifiable("title-helper"), unverifiable("model-catalog")));

        new AgentStartupRecovery(
                outboxStore,
                eventService,
                maintenance(taskStore, turnStore),
                supervisor,
                servingGate,
                launchStore(
                        launch("claude-probe", LaunchOwnerKind.CLI_PROBE, "claude-cli"),
                        launch("title-helper", LaunchOwnerKind.CLI_PROBE, "task-title"),
                        launch("model-catalog", LaunchOwnerKind.APP_SERVER, "codex-model-catalog")),
                taskStore,
                turnStore)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isTrue();
        verify(supervisor, never()).acknowledge("claude-probe");
        verify(supervisor, never()).acknowledge("title-helper");
        verify(supervisor, never()).acknowledge("model-catalog");
    }

    @Test
    void keepsTheGateClosedForAnUnrecognizedAppServerOwner()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(
                unverifiableForOwner("unknown-catalog", ProcessOwner.Kind.APP_SERVER, "codex-model-catalog-v2")));

        new AgentStartupRecovery(
                outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, turnStore)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isFalse();
        verify(supervisor, never()).acknowledge("unknown-catalog");
    }

    @Test
    void keepsTheGateClosedWhenMarkerAndLaunchRowOwnersDisagree()
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
                unverifiableForOwner("control-marker", ProcessOwner.Kind.CLI_PROBE, "codex-cli"),
                unverifiableFor("task-marker", taskId.toString())));

        new AgentStartupRecovery(
                outboxStore,
                eventService,
                maintenance(taskStore, turnStore),
                supervisor,
                servingGate,
                launchStore(
                        appServerLaunch("control-marker", taskId),
                        launch("task-marker", LaunchOwnerKind.CLI_PROBE, "codex-cli")),
                taskStore,
                turnStore)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isFalse();
        verify(taskStore, never()).update(any());
        verify(supervisor, never()).acknowledge("control-marker");
        verify(supervisor, never()).acknowledge("task-marker");
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
                launchStore(), taskStore, turnStore)
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
    void resolvesAnUnverifiableClaudeTurnToItsDurableTask()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        TaskId taskId = TaskId.random();
        TurnId turnId = TurnId.random();
        AgentTurn turn = AgentTurn.accepted(
                turnId,
                taskId,
                "recovery-turn",
                new AgentTurnSettings(
                        Optional.empty(),
                        Optional.empty(),
                        AgentPermissionPolicy.AUTO,
                        AgentSandboxPolicy.WORKSPACE_WRITE,
                        Duration.ofMinutes(5),
                        ImmutableList.of()),
                Instant.parse("2026-09-01T00:00:00Z"));
        when(turnStore.find(turnId)).thenReturn(Optional.of(turn));
        when(taskStore.find(taskId)).thenReturn(Optional.of(openTask(taskId)));
        AgentProcessLaunch launch = new AgentProcessLaunch(
                "claude-leaked",
                LaunchOwnerKind.CLAUDE_TURN,
                turnId.toString(),
                true,
                "/usr/local/bin/claude",
                "argv-digest",
                Instant.parse("2026-09-01T00:00:00Z"));
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(unverifiable("claude-leaked")));

        new AgentStartupRecovery(
                outboxStore,
                eventService,
                maintenance(taskStore, turnStore),
                supervisor,
                servingGate,
                launchStore(launch),
                taskStore,
                turnStore)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isTrue();
        ArgumentCaptor<AgentTask> held = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskStore).update(held.capture());
        assertThat(held.getValue().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("claude-leaked", held.getValue().updatedAt()));
        verify(supervisor, never()).acknowledge("claude-leaked");
    }

    @Test
    void settlesRestartedTurnsOnlyAfterEveryLaunchIsClassified()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        TaskId taskId = TaskId.random();
        AgentTask task = openTask(taskId);
        when(taskStore.listLive()).thenReturn(ImmutableList.of(task));
        when(taskStore.find(taskId)).thenReturn(Optional.of(task));
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(stopped("stale")));

        new AgentStartupRecovery(outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, turnStore)
                .afterSingletonsInstantiated();

        // A turn that was mid-flight is judged by the process verdict, so it can only be looked at
        // once every launch has one.
        InOrder order = inOrder(supervisor, turnStore);
        order.verify(supervisor).recoverOwnedProcesses();
        order.verify(turnStore).findActive(taskId);
        verify(supervisor).acknowledge("stale");
        assertThat(servingGate.isReady()).isTrue();
    }

    @Test
    void leavesRestartedTurnsUntouchedWhenAProcessCannotBeAttributed()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        TaskId taskId = TaskId.random();
        when(taskStore.listLive()).thenReturn(ImmutableList.of(openTask(taskId)));
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcesses()).thenReturn(ImmutableList.of(unverifiable("orphan")));

        new AgentStartupRecovery(outboxStore, eventService, maintenance(taskStore, turnStore), supervisor, servingGate,
                launchStore(), taskStore, turnStore)
                .afterSingletonsInstantiated();

        // The stray process could be this task's own, so its turn must not be treated as stopped.
        verify(turnStore, never()).findActive(any());
        verify(taskStore, never()).update(any());
        assertThat(servingGate.isReady()).isFalse();
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
                launchStore(), taskStore, turnStore)
                .afterSingletonsInstantiated();

        // Nothing can be pinned to a worktree, so something may still be writing somewhere nobody
        // can name and no agent operation may be served.
        assertThat(servingGate.isReady()).isFalse();
        verify(taskStore, never()).update(any());
    }

    @Test
    void explicitRetryChecksEveryDurableTaskLaunchBeforeReleasingTheClaim()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        TaskId taskId = TaskId.random();
        AgentTask blocked = openTask(taskId).claimWorktreeMutation(
                WorktreeMutationClaim.recovery("launch-1", Instant.parse("2026-09-01T00:00:00Z")),
                Instant.parse("2026-09-01T00:00:00Z"));
        when(taskStore.find(taskId)).thenReturn(Optional.of(blocked));
        when(taskStore.update(any())).thenAnswer(invocation -> ((AgentTask) invocation.getArgument(0)).withVersion(blocked.version() + 1));
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcess("launch-1")).thenReturn(stopped("launch-1"));
        when(supervisor.recoverOwnedProcess("launch-2")).thenReturn(stopped("launch-2"));
        AgentStartupRecovery recovery = new AgentStartupRecovery(
                outboxStore,
                eventService,
                maintenance(taskStore, turnStore),
                supervisor,
                new AgentServingGate(),
                launchStore(appServerLaunch("launch-1", taskId), appServerLaunch("launch-2", taskId)),
                taskStore,
                turnStore);

        AgentTask stopped = recovery.stopRecoveredRuntime(taskId, "task:" + blocked.version());

        assertThat(stopped.worktreeMutationClaim()).isEmpty();
        InOrder order = inOrder(supervisor, taskStore);
        order.verify(supervisor).recoverOwnedProcess("launch-1");
        order.verify(supervisor).recoverOwnedProcess("launch-2");
        order.verify(taskStore).update(any());
        order.verify(supervisor).acknowledge("launch-1");
        order.verify(supervisor).acknowledge("launch-2");
    }

    @Test
    void explicitRetryKeepsTheTaskBlockedByAnotherUnverifiableLaunch()
    {
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        TaskId taskId = TaskId.random();
        AgentTask blocked = openTask(taskId).claimWorktreeMutation(
                WorktreeMutationClaim.recovery("launch-1", Instant.parse("2026-09-01T00:00:00Z")),
                Instant.parse("2026-09-01T00:00:00Z"));
        when(taskStore.find(taskId)).thenReturn(Optional.of(blocked));
        when(taskStore.update(any())).thenAnswer(invocation -> ((AgentTask) invocation.getArgument(0)).withVersion(blocked.version() + 1));
        ProcessSupervisor supervisor = mock(ProcessSupervisor.class);
        when(supervisor.recoverOwnedProcess("launch-1")).thenReturn(stopped("launch-1"));
        when(supervisor.recoverOwnedProcess("launch-2")).thenReturn(unverifiableFor("launch-2", taskId.toString()));
        AgentStartupRecovery recovery = new AgentStartupRecovery(
                mock(AgentEventOutboxStore.class),
                mock(AgentEventService.class),
                maintenance(taskStore, turnStore),
                supervisor,
                new AgentServingGate(),
                launchStore(appServerLaunch("launch-1", taskId), appServerLaunch("launch-2", taskId)),
                taskStore,
                turnStore);

        assertThatThrownBy(() -> recovery.stopRecoveredRuntime(taskId, "task:" + blocked.version()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("launch-2");

        ArgumentCaptor<AgentTask> held = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskStore).update(held.capture());
        assertThat(held.getValue().worktreeMutationClaim().orElseThrow().ownerId()).isEqualTo("launch-2");
        verify(supervisor).acknowledge("launch-1");
        verify(supervisor, never()).acknowledge("launch-2");
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

    private static AgentProcessLaunchStore launchStore(AgentProcessLaunch... rows)
    {
        AgentProcessLaunchStore store = mock(AgentProcessLaunchStore.class);
        when(store.list()).thenReturn(ImmutableList.copyOf(rows));
        return store;
    }

    private static AgentProcessLaunch appServerLaunch(String launchId, TaskId taskId)
    {
        return launch(launchId, LaunchOwnerKind.APP_SERVER, taskId.toString());
    }

    private static AgentProcessLaunch launch(String launchId, LaunchOwnerKind ownerKind, String ownerId)
    {
        return new AgentProcessLaunch(
                launchId,
                ownerKind,
                ownerId,
                true,
                "/usr/local/bin/codex",
                "argv-digest",
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static OwnedProcessRecovery unverifiableFor(String launchId, String taskId)
    {
        return unverifiableForOwner(launchId, ProcessOwner.Kind.APP_SERVER, taskId);
    }

    private static OwnedProcessRecovery unverifiableForOwner(String launchId, ProcessOwner.Kind ownerKind, String ownerId)
    {
        return new OwnedProcessRecovery(
                launchId,
                Optional.of(new ProcessOwner(ownerKind, ownerId)),
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
                mock(QueuedInputStore.class),
                mock(SteerDeliveryStore.class),
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
