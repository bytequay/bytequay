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
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.AgentWorkspaceService;
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
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    void interruptsActiveTurnsAndRestoresLiveLeases()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId liveTaskId = TaskId.random();
        stores.insert(activeTask(liveTaskId));
        TurnId turnId = TurnId.random();
        stores.insert(AgentTurn.accepted(turnId, liveTaskId, "request-1", turnSettings(), NOW));
        AgentTask terminal = activeTask(TaskId.random()).fail(
                "failed",
                NOW.plusSeconds(1),
                NOW.plus(Duration.ofDays(7)));
        stores.insert(terminal);
        RecordingWorkspace workspace = new RecordingWorkspace();
        RecordingApprovals approvals = new RecordingApprovals();
        Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);
        AgentMaintenance maintenance = maintenance(stores, workspace, approvals, new RecordingCompletion(), clock);

        maintenance.recover();

        assertThat(stores.find(turnId).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(turnId).orElseThrow().terminalReason())
                .contains("Backend restarted while the turn was active");
        assertThat(workspace.restored).containsExactly(liveTaskId);
        assertThat(approvals.recoveredAll).isTrue();
        assertThat(stores.find(terminal.id())).contains(terminal);
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

    @Test
    void failsOnlyTheTaskWhoseLeaseCannotBeRestored()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(activeTask(taskId));
        RecordingWorkspace workspace = new RecordingWorkspace();
        workspace.failRestore = true;
        Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

        maintenance(stores, workspace, new RecordingApprovals(), new RecordingCompletion(), clock).recover();

        AgentTask failed = stores.find(taskId).orElseThrow();
        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.terminalReason()).hasValueSatisfying(reason -> assertThat(reason).contains("Cannot restore worktree lease"));
        assertThat(failed.deleteAfter()).contains(NOW.plusSeconds(10).plus(Duration.ofDays(7)));
    }

    @Test
    void interruptsKnownUnsentTurnAndKeepsUncertainTurnActive()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId unsentTaskId = TaskId.random();
        TaskId uncertainTaskId = TaskId.random();
        stores.insert(activeTask(unsentTaskId));
        stores.insert(activeTask(uncertainTaskId));
        AgentTurn unsentTurn = AgentTurn.accepted(
                TurnId.random(), unsentTaskId, "unsent", turnSettings(), NOW);
        AgentTurn uncertainTurn = AgentTurn.accepted(
                        TurnId.random(), uncertainTaskId, "uncertain", turnSettings(), NOW.plusSeconds(1))
                .markDeliveryStarted(NOW.plusSeconds(2));
        stores.insert(unsentTurn);
        stores.insert(uncertainTurn);
        RecordingWorkspace workspace = new RecordingWorkspace();
        Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

        maintenance(stores, workspace, new RecordingApprovals(), new RecordingCompletion(), clock).recover();

        assertThat(stores.find(unsentTurn.id()).orElseThrow().state()).isEqualTo(TurnState.INTERRUPTED);
        assertThat(stores.find(uncertainTurn.id()).orElseThrow().state()).isEqualTo(TurnState.ACCEPTED);
        assertThat(stores.find(unsentTaskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(uncertainTaskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.findActive(uncertainTaskId)).contains(uncertainTurn);
        assertThat(workspace.restored).containsExactlyInAnyOrder(unsentTaskId, uncertainTaskId);
    }

    @Test
    void keepsAStartedProviderTurnActiveAfterRestart()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(activeTask(taskId));
        AgentTurn accepted = AgentTurn.accepted(
                TurnId.random(), taskId, "delivered", turnSettings(), NOW);
        AgentTurn running = accepted.markDeliveryStarted(NOW.plusSeconds(1)).running(
                new ProviderTurnRef("provider-turn"),
                Optional.empty());
        stores.insert(running);
        RecordingWorkspace workspace = new RecordingWorkspace();
        Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

        maintenance(stores, workspace, new RecordingApprovals(), new RecordingCompletion(), clock).recover();

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.find(running.id()).orElseThrow().state()).isEqualTo(TurnState.RUNNING);
        assertThat(stores.findActive(taskId)).contains(running);
        assertThat(workspace.restored).containsExactly(taskId);
    }

    @Test
    void keepsTaskOpenWhenLeaseRestoreFailsWhileTurnMayStillRun()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        TaskId taskId = TaskId.random();
        stores.insert(activeTask(taskId));
        AgentTurn running = AgentTurn.accepted(
                        TurnId.random(), taskId, "delivered", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
        stores.insert(running);
        RecordingWorkspace workspace = new RecordingWorkspace();
        workspace.failRestore = true;
        Clock clock = Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC);

        maintenance(stores, workspace, new RecordingApprovals(), new RecordingCompletion(), clock).recover();

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        assertThat(stores.findActive(taskId)).contains(running);
        assertThat(workspace.released).isEmpty();
    }

    @Test
    void submitsDueAndRetryPendingTasksForCleanup()
    {
        InMemoryAgentStores stores = new InMemoryAgentStores();
        AgentTask failed = activeTask(TaskId.random()).fail(
                "failed",
                NOW,
                NOW.plus(Duration.ofDays(7)));
        stores.insert(failed);
        RecordingCompletion completion = new RecordingCompletion();
        Clock clock = Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC);
        AgentMaintenance maintenance = maintenance(
                stores,
                new RecordingWorkspace(),
                new RecordingApprovals(),
                completion,
                clock);

        List<CleanupResult> results = maintenance.cleanupDue(10);

        assertThat(completion.cleaned).containsExactly(failed.id());
        assertThat(results).containsExactly(new CleanupResult(failed.id(), CleanupState.DELETED, Optional.of(Instant.now(clock)), Optional.empty()));
    }

    private static AgentMaintenance maintenance(
            InMemoryAgentStores stores,
            RecordingWorkspace workspace,
            RecordingApprovals approvals,
            RecordingCompletion completion,
            Clock clock)
    {
        return new AgentMaintenance(
                stores,
                stores,
                workspace,
                approvals,
                new DefaultAgentMetricsService(stores, stores, clock),
                completion,
                new AgentRuntimeRegistry(),
                clock);
    }

    private static AgentTask activeTask(TaskId taskId)
    {
        return AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "Recovery test",
                AgentLaneId.CODEX_CLI,
                REPOSITORY,
                WORKTREE.resolve(taskId.toString()),
                AgentPermissionPolicy.AUTO,
                NOW);
    }

    private static final class RecordingWorkspace
            implements AgentWorkspaceService
    {
        private final List<TaskId> restored = new ArrayList<>();
        private final List<TaskId> released = new ArrayList<>();
        private boolean failRestore;

        @Override
        public WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceLease restore(TaskId taskId, Path canonicalWorktree, RepositoryIdentity repository)
        {
            if (failRestore) {
                throw new IllegalStateException("worktree missing");
            }
            restored.add(taskId);
            return new WorkspaceLease(taskId, canonicalWorktree, () -> {});
        }

        @Override
        public WorkspaceSnapshot inspect(Path worktree)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(TaskId taskId)
        {
            released.add(taskId);
        }
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
        public CleanupResult cleanup(TaskId taskId)
        {
            cleaned.add(taskId);
            return new CleanupResult(taskId, CleanupState.DELETED, Optional.of(NOW.plus(Duration.ofDays(8))),
                    Optional.empty());
        }
    }
}
