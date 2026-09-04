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
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.InputDeliveryId;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentMaintenance
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/bytequay-recovery-worktree");
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "sample");

    @Test
    void interruptsKnownUnsentTurnAndReleasesItsClaim()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        TurnId turnId = TurnId.random();
        stores.insert(managedTask(taskId));
        stores.insert(AgentTurn.accepted(turnId, taskId, "request-1", turnSettings(), NOW));
        RecordingApprovals approvals = new RecordingApprovals();

        maintenance(stores, approvals, new RecordingCompletion(), new AdvancingClock(NOW.plusSeconds(10))).recover();

        assertThat(stores.find(turnId).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(turnId).orElseThrow().terminalReason())
                .contains("Backend restarted while the turn was active");
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.listPending(taskId, 10)).hasSize(1);
        assertThat(approvals.recoveredAll).isTrue();
    }

    @Test
    void keepsUncertainTurnAndItsClaimAfterRestart()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTurn uncertain = AgentTurn.accepted(
                        TurnId.random(), taskId, "uncertain", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1));
        stores.insert(managedTask(taskId));
        stores.insert(uncertain);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        assertThat(stores.find(uncertain.id()).orElseThrow().state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.turn(uncertain.id(), uncertain.createdAt()));
    }

    @Test
    void keepsRunningProviderTurnAndItsClaimAfterRestart()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTurn running = AgentTurn.accepted(
                        TurnId.random(), taskId, "delivered", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
        stores.insert(managedTask(taskId));
        stores.insert(running);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.RUNNING);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.turn(running.id(), running.createdAt()));
    }

    @Test
    void interruptsARestartedTurnAndReleasesItsWorktreeWhenItsProcessVerifiedStopped()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTurn running = runningTurn(taskId);
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(running.id(), running.createdAt()), NOW));
        stores.insert(running);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(), ImmutableSet.of("launch-1"), true);

        // The transport is gone either way; a verified stop is what makes the worktree safe again.
        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(interruptionMessages(stores)).singleElement().asString().contains("verified stopped");
    }

    @Test
    void interruptsARestartedTurnAndReservesItsWorktreeWhenItsProcessIsUnverifiable()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTurn running = runningTurn(taskId);
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(running.id(), running.createdAt()), NOW));
        stores.insert(running);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10))
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
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTurn terminal = runningTurn(taskId)
                .finish(TurnState.FAILED, TurnMetrics.empty(), Optional.of("not shown stopped"), NOW.plusSeconds(2));
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.turn(terminal.id(), terminal.createdAt()), NOW));
        stores.insert(terminal);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(taskId, "launch-1"), ImmutableSet.of(), true);

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-1", NOW.plusSeconds(10)));
    }

    @Test
    void releasesARecoveryClaimOnceItsLaunchVerifiesStopped()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId released = TaskId.random();
        TaskId stillBlocked = TaskId.random();
        stores.insert(managedTask(released).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW));
        stores.insert(managedTask(stillBlocked).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-2", NOW), NOW));

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10))
                .settleAfterProcessRecovery(ImmutableMap.of(), ImmutableSet.of("launch-1"), true);

        assertThat(stores.find(released).orElseThrow().worktreeMutationClaim()).isEmpty();
        assertThat(stores.find(stillBlocked).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-2", NOW));
    }

    @Test
    void movesARecoveryClaimToAnotherUnverifiableLaunchFromTheSameTask()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW));

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10))
                .settleAfterProcessRecovery(
                        ImmutableMap.of(taskId, "launch-2"),
                        ImmutableSet.of("launch-1"),
                        true);

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-2", NOW.plusSeconds(10)));
    }

    @Test
    void explicitRecoveryCheckReleasesOrRetargetsTheClaimWithoutRuntimeState()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTask blocked = managedTask(taskId).claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW);
        stores.insert(blocked);
        AgentMaintenance maintenance = maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10));

        AgentTask stillBlocked = maintenance.settleRecoveryClaim(
                taskId,
                blocked.version(),
                "launch-1",
                Optional.of("launch-2"));

        assertThat(stillBlocked.worktreeMutationClaim())
                .contains(WorktreeMutationClaim.recovery("launch-2", NOW.plusSeconds(10)));

        AgentTask released = maintenance.settleRecoveryClaim(
                taskId,
                stillBlocked.version(),
                "launch-2",
                Optional.empty());

        assertThat(released.worktreeMutationClaim()).isEmpty();
    }

    @Test
    void leavesOtherRestartedTurnsAloneWhileAProcessRemainsUnattributed()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId blocked = TaskId.random();
        TaskId unknown = TaskId.random();
        AgentTurn blockedTurn = runningTurn(blocked);
        AgentTurn unknownTurn = runningTurn(unknown);
        stores.insert(managedTask(blocked).claimWorktreeMutation(WorktreeMutationClaim.turn(blockedTurn.id(), blockedTurn.createdAt()), NOW));
        stores.insert(managedTask(unknown).claimWorktreeMutation(WorktreeMutationClaim.turn(unknownTurn.id(), unknownTurn.createdAt()), NOW));
        stores.insert(blockedTurn);
        stores.insert(unknownTurn);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10))
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
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId waitingTask = TaskId.random();
        TaskId unsentTask = TaskId.random();
        TaskId uncertainTask = TaskId.random();
        stores.insert(managedTask(waitingTask));
        stores.insert(managedTask(unsentTask));
        stores.insert(managedTask(uncertainTask));
        QueuedAgentInput waiting = queuedInput(waitingTask);
        AgentTurn unsent = AgentTurn.accepted(TurnId.random(), unsentTask, "queue:unsent", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .finishKnownUnsent(TurnMetrics.empty(), "not sent", NOW.plusSeconds(2));
        QueuedAgentInput claimedUnsent = queuedInput(unsentTask).starting(unsent.id(), EventId.random(), NOW);
        AgentTurn sent = AgentTurn.accepted(TurnId.random(), uncertainTask, "queue:sent", turnSettings(), NOW).markDeliveryStarted(NOW.plusSeconds(1));
        QueuedAgentInput claimedSent = queuedInput(uncertainTask).starting(sent.id(), EventId.random(), NOW);
        stores.insert(waiting);
        stores.insert(unsent);
        stores.insert(claimedUnsent);
        stores.insert(sent);
        stores.insert(claimedSent);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        assertThat(stores.find(waiting.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(stores.find(waiting.id()).orElseThrow().pauseReason()).contains("Backend restarted");
        // Never sent: it waits again. Sent without an answer: nobody knows, so only the user may decide.
        assertThat(stores.find(claimedUnsent.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.PAUSED);
        assertThat(stores.find(claimedSent.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.UNCERTAIN);
    }

    @Test
    void settlesSteersCaughtByARestartWithoutResendingThem()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(managedTask(taskId));
        AgentTurn running = runningTurn(taskId);
        stores.insert(running);
        AgentInputDelivery accepted = AgentInputDelivery.accepted(InputDeliveryId.random(), taskId, running.id(), "steer-1", NOW);
        AgentInputDelivery sending = AgentInputDelivery.accepted(InputDeliveryId.random(), taskId, running.id(), "steer-2", NOW).sending(NOW);
        stores.insert(accepted);
        stores.insert(sending);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

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
        return QueuedAgentInput.queued(
                QueuedInputId.random(),
                taskId,
                1,
                "queue-1",
                new BlobRef("blobs/queue-1", "text/plain; charset=utf-8", 5),
                "later",
                turnSettings(),
                NOW);
    }

    @Test
    void releasesAbandonedCompletionClaim()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        WorktreeMutationClaim claim = new WorktreeMutationClaim(
                WorktreeMutationClaim.Kind.COMPLETION,
                "completion-1",
                NOW);
        stores.insert(managedTask(taskId).claimWorktreeMutation(claim, NOW));

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).isEmpty();
    }

    @Test
    void retainsTerminalTurnClaimUntilProviderQuiescenceIsProved()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        AgentTurn terminal = AgentTurn.accepted(TurnId.random(), taskId, "terminal", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.COMPLETED, TurnMetrics.empty(), Optional.empty(), NOW.plusSeconds(2));
        WorktreeMutationClaim claim = WorktreeMutationClaim.turn(terminal.id(), terminal.createdAt());
        stores.insert(managedTask(taskId).claimWorktreeMutation(claim, NOW));
        stores.insert(terminal);

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        assertThat(stores.find(taskId).orElseThrow().worktreeMutationClaim()).contains(claim);
    }

    @Test
    void leavesIdleManagedTaskOpen()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(managedTask(taskId));

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.listPending(taskId, 10)).isEmpty();
    }

    @Test
    void failsLiveLegacyTaskWithoutManagedWorktreeFacts()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(legacyTask(taskId));

        maintenance(stores, new RecordingApprovals(), new RecordingCompletion(), NOW.plusSeconds(10)).recover();

        AgentTask failed = stores.find(taskId).orElseThrow();
        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.terminalReason()).contains("Task has no managed worktree ownership facts");
        assertThat(stores.listPending(taskId, 10)).hasSize(1);
    }

    @Test
    void submitsDueAndRetryPendingTasksForCleanup()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        AgentTask failed = managedTask(TaskId.random()).fail(
                "failed",
                NOW,
                NOW.plus(Duration.ofDays(7)));
        stores.insert(failed);
        RecordingCompletion completion = new RecordingCompletion();
        Instant maintenanceTime = NOW.plus(Duration.ofDays(8));
        AgentMaintenance maintenance = maintenance(
                stores,
                new RecordingApprovals(),
                completion,
                maintenanceTime);

        List<CleanupResult> results = maintenance.cleanupDue(10);

        assertThat(completion.cleaned).containsExactly(failed.id());
        assertThat(results).containsExactly(new CleanupResult(
                failed.id(),
                CleanupState.DELETED,
                Optional.of(maintenanceTime),
                Optional.empty()));
    }

    @Test
    void backsOffFailedRetentionCleanupSoAnotherDueTaskCanProgress()
    {
        Instant maintenanceTime = NOW.plus(Duration.ofDays(8));
        InMemoryAgentStores stores = new InMemoryAgentStores();
        AgentTask retryPending = managedTask(TaskId.random())
                .fail("failed", NOW, NOW.plus(Duration.ofDays(7)))
                .withCleanupState(CleanupState.DELETE_DUE, Optional.empty(), maintenanceTime.minusSeconds(30))
                .withCleanupState(CleanupState.RETRY_PENDING, Optional.of("still dirty"), maintenanceTime.minusSeconds(30));
        AgentTask due = managedTask(TaskId.random()).fail(
                "failed",
                NOW.plusSeconds(1),
                NOW.plus(Duration.ofDays(7)).plusSeconds(1));
        stores.insert(retryPending);
        stores.insert(due);
        RecordingCompletion completion = new RecordingCompletion();

        maintenance(stores, new RecordingApprovals(), completion, maintenanceTime).cleanupDue(1);

        assertThat(completion.cleaned).containsExactly(due.id());
    }

    @Test
    void preservesWorkflowWorktreeUntilItsWorkflowRecordsCleanup()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        AgentTask workflowTask = managedTask(TaskId.random(), AgentPermissionPolicy.CI_AUTOFIX)
                .fail("workflow finished", NOW, NOW.plus(Duration.ofDays(7)));
        stores.insert(workflowTask);
        RecordingCompletion completion = new RecordingCompletion();

        List<CleanupResult> results = maintenance(
                stores,
                new RecordingApprovals(),
                completion,
                NOW.plus(Duration.ofDays(8)))
                .cleanupDue(10);

        assertThat(results).isEmpty();
        assertThat(completion.cleaned).isEmpty();
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(
                Optional.empty(),
                Optional.empty(),
                AgentPermissionPolicy.AUTO,
                AgentSandboxPolicy.WORKSPACE_WRITE,
                Duration.ofMinutes(5),
                ImmutableList.of());
    }

    private static AgentMaintenance maintenance(
            InMemoryAgentStores stores,
            RecordingApprovals approvals,
            RecordingCompletion completion,
            Instant now)
    {
        return maintenance(stores, approvals, completion, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static AgentMaintenance maintenance(
            InMemoryAgentStores stores,
            RecordingApprovals approvals,
            RecordingCompletion completion,
            Clock clock)
    {
        return new AgentMaintenance(
                stores,
                stores,
                stores,
                stores,
                stores,
                stores,
                approvals,
                new DefaultAgentMetricsService(stores, stores, clock),
                completion,
                new AgentRuntimeRegistry(),
                clock);
    }

    private static AgentTask managedTask(TaskId taskId)
    {
        return managedTask(taskId, AgentPermissionPolicy.AUTO);
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
        return AgentTask.created(
                taskId,
                false,
                "Recovery test",
                TitleSource.USER,
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(permissionPolicy),
                NOW);
    }

    private static AgentTask legacyTask(TaskId taskId)
    {
        return AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Legacy recovery test",
                AgentLaneId.CODEX_CLI,
                REPOSITORY,
                WORKTREE.resolve(taskId.toString()),
                AgentPermissionPolicy.AUTO,
                NOW);
    }

    private static final class RecordingApprovals
            implements AgentApprovalService
    {
        private boolean recoveredAll;

        @Override
        public CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApprovalDecision resolve(ResolveApprovalCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void denyPending(TurnId turnId)
        {
            throw new AssertionError("Recovery should close all durable approvals before recovering turns");
        }

        @Override
        public void denyAllPending()
        {
            recoveredAll = true;
        }
    }

    private static final class RecordingCompletion
            implements AgentCompletionService
    {
        private final List<TaskId> cleaned = new ArrayList<>();

        @Override
        public TaskCompletion complete(CompleteAgentTaskCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentTask abandon(TaskId taskId, String reason)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanupWorktree(TaskId taskId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public CleanupResult cleanup(TaskId taskId)
        {
            cleaned.add(taskId);
            return new CleanupResult(
                    taskId,
                    CleanupState.DELETED,
                    Optional.of(NOW.plus(Duration.ofDays(8))),
                    Optional.empty());
        }
    }

    private static final class AdvancingClock
            extends Clock
    {
        private Instant instant;

        private AdvancingClock(Instant instant)
        {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public synchronized Instant instant()
        {
            Instant current = instant;
            instant = instant.plusMillis(1);
            return current;
        }
    }
}
