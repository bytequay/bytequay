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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentInputDelivery;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TestAgentMaintenance
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/bytequay-recovery-worktree");
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "sample");

    private final InMemoryAgentStores stores = new InMemoryAgentStores();
    private final AgentApprovalService approvals = mock(AgentApprovalService.class, invocation -> {
        throw new UnsupportedOperationException();
    });
    private final AgentCompletionService completion = mock(AgentCompletionService.class, invocation -> {
        throw new UnsupportedOperationException();
    });

    @BeforeEach
    void setUp()
    {
        doReturn(ImmutableList.of()).when(approvals).listPending(any());
        doNothing().when(approvals).denyAllPending();
        doThrow(new AssertionError("Recovery should close all durable approvals before recovering turns")).when(approvals).denyPending(any());
        doNothing().when(completion).cleanup(any());
    }

    @Test
    void interruptsKnownUnsentTurnAndReleasesItsClaim()
    {
        TaskId taskId = TaskId.random();
        TurnId turnId = TurnId.random();
        stores.insert(managedTask(taskId));
        stores.insert(AgentTurn.accepted(turnId, taskId, "request-1", turnSettings(), NOW));

        maintenance(new AdvancingClock(NOW.plusSeconds(10))).recover();

        assertThat(stores.find(turnId).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(turnId).orElseThrow().terminalReason())
                .contains("Backend restarted while the turn was active");
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.listPending(taskId, 10)).hasSize(1);
        verify(approvals).denyAllPending();
    }

    @Test
    void skipsATaskWhoseActiveTurnDoesNotOwnItsClaimAndRecoversTheRest()
    {
        TaskId inconsistent = TaskId.random();
        TurnId foreign = TurnId.random();
        stores.insert(managedTask(inconsistent));
        stores.update(stores.find(inconsistent).orElseThrow()
                .claimWorktreeMutation(new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "completion-1", NOW), NOW));
        stores.insert(AgentTurn.accepted(foreign, inconsistent, "request-1", turnSettings(), NOW));
        TaskId healthy = TaskId.random();
        TurnId unsent = TurnId.random();
        stores.insert(managedTask(healthy));
        stores.insert(AgentTurn.accepted(unsent, healthy, "request-2", turnSettings(), NOW));

        maintenance(new AdvancingClock(NOW.plusSeconds(10))).recover();

        assertThat(stores.find(foreign).orElseThrow().state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(stores.find(inconsistent).orElseThrow().worktreeMutationClaim().map(WorktreeMutationClaim::kind))
                .contains(WorktreeMutationClaim.Kind.COMPLETION);
        assertThat(stores.find(unsent).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(healthy).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"ACCEPTED", "RUNNING"})
    void keepsDeliveredTurnAndItsClaimAfterRestart(TurnState state)
    {
        TaskId taskId = TaskId.random();
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), taskId, state == TurnState.RUNNING ? "delivered" : "uncertain", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1));
        if (state == TurnState.RUNNING) {
            turn = turn.running(new ProviderTurnRef("provider-turn"), Optional.empty());
        }
        stores.insert(managedTask(taskId));
        stores.insert(turn);

        maintenance(NOW.plusSeconds(10)).recover();

        assertThat(stores.find(turn.id()).orElseThrow().state()).isEqualTo(state);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.turn(turn.id(), turn.createdAt()));
    }

    @Test
    void interruptsARestartedTurnAndReleasesItsWorktreeWhenItsProcessVerifiedStopped()
    {
        TaskId taskId = TaskId.random();
        AgentTurn running = runningTurn(taskId);
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(running.id(), running.createdAt()), NOW));
        stores.insert(running);

        maintenance(NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(), ImmutableSet.of("launch-1"), true);

        // The transport is gone either way; a verified stop is what makes the worktree safe again.
        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(interruptionMessages(stores)).singleElement().asString().contains("verified stopped");
    }

    @Test
    void interruptsARestartedTurnAndReservesItsWorktreeWhenItsProcessIsUnverifiable()
    {
        TaskId taskId = TaskId.random();
        AgentTurn running = runningTurn(taskId);
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(running.id(), running.createdAt()), NOW));
        stores.insert(running);

        maintenance(NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(taskId, "launch-1"), ImmutableSet.of(), true);

        // The launch, not the finished turn, is what now blocks, so a later verification of that
        // launch is what can release it.
        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .hasValueSatisfying(claim -> {
                    assertThat(claim.kind()).isEqualTo(WorktreeMutationClaim.Kind.RECOVERY);
                    assertThat(claim.ownerId()).isEqualTo("launch-1");
                });
        assertThat(interruptionMessages(stores)).singleElement().asString().contains("could not be verified stopped");
    }

    @Test
    void swapsARetainedTurnClaimForTheUnverifiableLaunchThatStillBlocks()
    {
        TaskId taskId = TaskId.random();
        AgentTurn terminal = runningTurn(taskId)
                .finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("not shown stopped"), NOW.plusSeconds(2));
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(terminal.id(), terminal.createdAt()), NOW));
        stores.insert(terminal);

        maintenance(NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(taskId, "launch-1"), ImmutableSet.of(), true);

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-1", NOW.plusSeconds(10)));
    }

    @Test
    void releasesARetainedTerminalTurnClaimOnceItsProcessVerifiesStopped()
    {
        TaskId taskId = TaskId.random();
        AgentTurn terminal = runningTurn(taskId)
                .finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("not shown stopped"), NOW.plusSeconds(2));
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(terminal.id(), terminal.createdAt()), NOW));
        stores.insert(terminal);
        AgentMaintenance maintenance = maintenance(NOW.plusSeconds(10));

        maintenance.recover();
        maintenance.settleAfterProcessRecovery(ImmutableMap.of(), ImmutableSet.of("launch-1"), true);

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.find(terminal.id()).orElseThrow().state()).isEqualTo(TurnState.FAILED);
    }

    @Test
    void releasesARecoveryClaimOnceItsLaunchVerifiesStopped()
    {
        TaskId released = TaskId.random();
        TaskId stillBlocked = TaskId.random();
        stores.insert(managedTask(released).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW));
        stores.insert(managedTask(stillBlocked).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-2", NOW), NOW));

        maintenance(NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(), ImmutableSet.of("launch-1"), true);

        assertThat(stores.find(released).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.find(stillBlocked).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-2", NOW));
    }

    @Test
    void movesARecoveryClaimToAnotherUnverifiableLaunchFromTheSameTask()
    {
        TaskId taskId = TaskId.random();
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW));

        maintenance(NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(taskId, "launch-2"), ImmutableSet.of("launch-1"), true);

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-2", NOW.plusSeconds(10)));
    }

    @Test
    void explicitRecoveryCheckReleasesOrRetargetsTheClaimWithoutRuntimeState()
    {
        TaskId taskId = TaskId.random();
        AgentTask blocked = managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW);
        stores.insert(blocked);
        AgentMaintenance maintenance = maintenance(NOW.plusSeconds(10));

        AgentTask stillBlocked = maintenance.settleRecoveryClaim(taskId, blocked.version(), "launch-1", Optional.of("launch-2"));

        assertThat(stillBlocked.worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-2", NOW.plusSeconds(10)));

        AgentTask released = maintenance.settleRecoveryClaim(taskId, stillBlocked.version(), "launch-2", Optional.empty());

        assertThat(released.worktreeMutationClaim()).isEmpty();
    }

    @Test
    void leavesOtherRestartedTurnsAloneWhileAProcessRemainsUnattributed()
    {
        TaskId blocked = TaskId.random();
        TaskId unknown = TaskId.random();
        AgentTurn blockedTurn = runningTurn(blocked);
        AgentTurn unknownTurn = runningTurn(unknown);
        stores.insert(managedTask(blocked).claimWorktreeMutation(WorktreeMutationClaim.turn(blockedTurn.id(), blockedTurn.createdAt()), NOW));
        stores.insert(managedTask(unknown).claimWorktreeMutation(WorktreeMutationClaim.turn(unknownTurn.id(), unknownTurn.createdAt()), NOW));
        stores.insert(blockedTurn);
        stores.insert(unknownTurn);

        maintenance(NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(blocked, "launch-1"), ImmutableSet.of(), false);

        // The stray process could belong to the task nobody could pin it to, so only the task with
        // its own verdict is settled.
        assertThat(stores.find(blockedTurn.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(unknownTurn.id()).orElseThrow().state()).isEqualTo(TurnState.RUNNING);
        assertThat(stores.find(unknown).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.turn(unknownTurn.id(), unknownTurn.createdAt()));
    }

    private static AgentTurn runningTurn(TaskId taskId)
    {
        return AgentTurn.accepted(TurnId.random(), taskId, "delivered", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
    }

    private static List<String> interruptionMessages(InMemoryAgentStores stores)
    {
        return stores.outboxEntries().stream()
                .map(entry -> entry.payload())
                .filter(payload -> payload instanceof AgentEventOutboxPayload.Inline)
                .map(payload -> ((AgentEventOutboxPayload.Inline) payload).payload())
                .filter(payload -> payload instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.INTERRUPTED)
                .map(payload -> ((AgentEventPayload.Lifecycle) payload).message())
                .collect(toImmutableList());
    }

    @Test
    void pausesTheQueueOnRestartAndSettlesAClaimedInputByWhatItsTurnProves()
    {
        TaskId waitingTask = TaskId.random();
        TaskId unsentTask = TaskId.random();
        TaskId uncertainTask = TaskId.random();
        stores.insert(managedTask(waitingTask));
        stores.insert(managedTask(unsentTask));
        stores.insert(managedTask(uncertainTask));
        QueuedAgentInput waiting = queuedInput(waitingTask);
        AgentTurn unsent = AgentTurn.accepted(TurnId.random(), unsentTask, "queue:unsent", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .finishKnownUnsent(AgentMetrics.empty(), "not sent", NOW.plusSeconds(2));
        QueuedAgentInput claimedUnsent = queuedInput(unsentTask).starting(unsent.id(), EventId.random(), NOW);
        AgentTurn sent = AgentTurn.accepted(TurnId.random(), uncertainTask, "queue:sent", turnSettings(), NOW).markDeliveryStarted(NOW.plusSeconds(1));
        QueuedAgentInput claimedSent = queuedInput(uncertainTask).starting(sent.id(), EventId.random(), NOW);
        stores.insert(waiting);
        stores.insert(unsent);
        stores.insert(claimedUnsent);
        stores.insert(sent);
        stores.insert(claimedSent);

        maintenance(NOW.plusSeconds(10)).recover();

        assertThat(stores.find(waiting.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(stores.find(waiting.id()).orElseThrow().pauseReason()).contains("Backend restarted");
        // Never sent: it waits again. Sent without an answer: nobody knows, so only the user may decide.
        assertThat(stores.find(claimedUnsent.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(stores.find(claimedSent.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.UNCERTAIN);
    }

    @Test
    void settlesSteersCaughtByARestartWithoutResendingThem()
    {
        TaskId taskId = TaskId.random();
        stores.insert(managedTask(taskId));
        AgentTurn running = runningTurn(taskId);
        stores.insert(running);
        AgentInputDelivery accepted = AgentInputDelivery.accepted(InputDeliveryId.random(), taskId, running.id(), "steer-1", NOW);
        AgentInputDelivery sending = AgentInputDelivery.accepted(InputDeliveryId.random(), taskId, running.id(), "steer-2", NOW).sending(NOW);
        stores.insert(accepted);
        stores.insert(sending);

        maintenance(NOW.plusSeconds(10)).recover();

        assertThat(stores.steers()).extracting(AgentInputDelivery::state)
                .containsExactly(AgentInputDelivery.State.FAILED, AgentInputDelivery.State.UNKNOWN);
        assertThat(stores.listUnsettled()).isEmpty();
        // Each outcome reaches the transcript as an error on the turn it was meant for.
        assertThat(stores.outboxEntries().stream()
                .map(entry -> entry.payload())
                .filter(payload -> payload.kind() == AgentEventPayload.Kind.ERROR)
                .count()).isEqualTo(2);
    }

    private static QueuedAgentInput queuedInput(TaskId taskId)
    {
        return QueuedAgentInput.queued(QueuedInputId.random(), taskId, 1, "queue-1", new BlobRef("blobs/queue-1", "text/plain; charset=utf-8", 5), "later", turnSettings(), NOW);
    }

    @Test
    void releasesAbandonedCompletionClaim()
    {
        TaskId taskId = TaskId.random();
        WorktreeMutationClaim claim = new WorktreeMutationClaim(WorktreeMutationClaim.Kind.COMPLETION, "completion-1", NOW);
        stores.insert(managedTask(taskId).claimWorktreeMutation(claim, NOW));

        maintenance(NOW.plusSeconds(10)).recover();

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void releasesALocalRunClaimAtStartupAndReservesTheWorktreeAgainForItsUnverifiableLaunch()
    {
        TaskId verified = TaskId.random();
        TaskId unverifiable = TaskId.random();
        stores.insert(managedTask(verified).claimWorktreeMutation(WorktreeMutationClaim.localRun("run-1", NOW), NOW));
        stores.insert(managedTask(unverifiable).claimWorktreeMutation(WorktreeMutationClaim.localRun("run-2", NOW), NOW));
        AgentMaintenance maintenance = maintenance(NOW.plusSeconds(10));

        maintenance.recover();

        assertThat(stores.find(verified).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.find(unverifiable).orElseThrow().worktreeMutationClaim()).isEmpty();

        maintenance.settleAfterProcessRecovery(ImmutableMap.of(unverifiable, "launch-2"), ImmutableSet.of("launch-1"), true);

        assertThat(stores.find(verified).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.find(unverifiable).orElseThrow().worktreeMutationClaim()).contains(WorktreeMutationClaim.recovery("launch-2", NOW.plusSeconds(10)));
    }

    @Test
    void retainsTerminalTurnClaimUntilProviderQuiescenceIsProved()
    {
        TaskId taskId = TaskId.random();
        AgentTurn terminal = AgentTurn.accepted(TurnId.random(), taskId, "terminal", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW.plusSeconds(2));
        WorktreeMutationClaim claim = WorktreeMutationClaim.turn(terminal.id(), terminal.createdAt());
        stores.insert(managedTask(taskId).claimWorktreeMutation(claim, NOW));
        stores.insert(terminal);

        AgentMaintenance maintenance = maintenance(NOW.plusSeconds(10));
        maintenance.recover();
        maintenance.settleAfterProcessRecovery(ImmutableMap.of(), ImmutableSet.of(), false);

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).contains(claim);
    }

    @Test
    void leavesIdleManagedTaskOpen()
    {
        TaskId taskId = TaskId.random();
        stores.insert(managedTask(taskId));

        maintenance(NOW.plusSeconds(10)).recover();

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.listPending(taskId, 10)).isEmpty();
    }

    @Test
    void archivesOnlyCompletedInteractiveTasksOlderThanTheWindow()
    {
        AgentTask oldCompleted = interactiveTask(TaskId.random()).complete(summary(), NOW, NOW.plus(Duration.ofDays(7)));
        AgentTask freshCompleted = interactiveTask(TaskId.random()).complete(summary(), NOW.plus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(12)));
        AgentTask failed = interactiveTask(TaskId.random()).fail("failed", NOW, NOW.plus(Duration.ofDays(7)));
        AgentTask open = interactiveTask(TaskId.random());
        AgentTask workflow = managedTask(TaskId.random()).complete(summary(), NOW, NOW.plus(Duration.ofDays(7)));
        for (AgentTask task : ImmutableList.of(oldCompleted, freshCompleted, failed, open, workflow)) {
            stores.insert(task);
        }
        Instant sweepTime = NOW.plus(Duration.ofDays(8));
        AgentMaintenance maintenance = maintenance(sweepTime);

        assertThat(maintenance.archiveDue(Duration.ofDays(7), 10)).isEqualTo(1);

        assertThat(stores.find(oldCompleted.id()).orElseThrow().archivedAt()).contains(sweepTime);
        // Recent, failed, open, and workflow-owned tasks stay where the user can see them.
        for (AgentTask untouched : ImmutableList.of(freshCompleted, failed, open, workflow)) {
            assertThat(stores.find(untouched.id()).orElseThrow().archivedAt()).isEmpty();
        }
        assertThat(maintenance.archiveDue(Duration.ofDays(7), 10)).isZero();
    }

    @Test
    void submitsDueAndRetryPendingTasksForCleanup()
    {
        AgentTask failed = managedTask(TaskId.random()).fail("failed", NOW, NOW.plus(Duration.ofDays(7)));
        stores.insert(failed);
        Instant maintenanceTime = NOW.plus(Duration.ofDays(8));
        AgentMaintenance maintenance = maintenance(maintenanceTime);

        maintenance.cleanupDue(10);

        verify(completion).cleanup(failed.id());
        verifyNoMoreInteractions(completion);
    }

    @Test
    void backsOffFailedRetentionCleanupSoAnotherDueTaskCanProgress()
    {
        Instant maintenanceTime = NOW.plus(Duration.ofDays(8));
        AgentTask retryPending = managedTask(TaskId.random())
                .fail("failed", NOW, NOW.plus(Duration.ofDays(7)))
                .withCleanupState(CleanupState.RETRY_PENDING, Optional.of("still dirty"), maintenanceTime.minusSeconds(30));
        AgentTask due = managedTask(TaskId.random()).fail("failed", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)).plusSeconds(1));
        stores.insert(retryPending);
        stores.insert(due);

        maintenance(maintenanceTime).cleanupDue(1);

        verify(completion).cleanup(due.id());
        verifyNoMoreInteractions(completion);
    }

    @Test
    void aTaskWhoseCleanupThrowsDoesNotStopTheTasksBehindIt()
    {
        AgentTask stuck = managedTask(TaskId.random()).fail("failed", NOW, NOW.plus(Duration.ofDays(7)));
        AgentTask due = managedTask(TaskId.random()).fail("failed", NOW.plusSeconds(1), NOW.plus(Duration.ofDays(7)).plusSeconds(1));
        stores.insert(stuck);
        stores.insert(due);
        doThrow(new IllegalStateException("provider is wedged")).when(completion).cleanup(stuck.id());

        maintenance(NOW.plus(Duration.ofDays(8))).cleanupDue(10);

        verify(completion).cleanup(stuck.id());
        verify(completion).cleanup(due.id());
    }

    @Test
    void preservesWorkflowWorktreeUntilItsWorkflowRecordsCleanup()
    {
        AgentTask workflowTask = managedTask(TaskId.random(), AgentPermissionPolicy.CI_AUTOFIX)
                .fail("workflow finished", NOW, NOW.plus(Duration.ofDays(7)));
        stores.insert(workflowTask);

        maintenance(NOW.plus(Duration.ofDays(8))).cleanupDue(10);

        verifyNoInteractions(completion);
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.empty());
    }

    private AgentMaintenance maintenance(Instant now)
    {
        return maintenance(Clock.fixed(now, ZoneOffset.UTC));
    }

    private AgentMaintenance maintenance(Clock clock)
    {
        return new AgentMaintenance(
                stores,
                stores,
                stores,
                stores,
                stores,
                stores,
                approvals,
                new DefaultAgentMetricsService(stores, stores, new InMemoryTaskLogStore(), clock),
                completion,
                new AgentRuntimeRegistry(stores, ignored -> {}, ignored -> false, true),
                clock);
    }

    private static AgentTask managedTask(TaskId taskId)
    {
        return managedTask(taskId, AgentPermissionPolicy.AUTO);
    }

    private static AgentTask interactiveTask(TaskId taskId)
    {
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                REPOSITORY,
                WORKTREE.resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        return AgentTask.created(taskId, true, "Archive test", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private static TaskSummary summary()
    {
        return new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of());
    }

    private static AgentTask managedTask(TaskId taskId, AgentPermissionPolicy permissionPolicy)
    {
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-a",
                REPOSITORY,
                WORKTREE.resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        return AgentTask.created(taskId, false, "Recovery test", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree, AgentTaskDefaults.forPermission(permissionPolicy), NOW);
    }
}
