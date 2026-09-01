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
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.CleanupResult;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.ResolveApprovalCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
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
