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
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
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
        stores.insert(AgentTurn.accepted(turnId, liveTaskId, "request-1", NOW));
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
        assertThat(approvals.denied).containsExactly(turnId);
        assertThat(stores.find(terminal.id())).contains(terminal);
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
                        "Recovery test",
                        AgentLaneId.CODEX_CLI,
                        REPOSITORY,
                        WORKTREE.resolve(taskId.toString()),
                        AgentPermissionPolicy.AUTO,
                        NOW)
                .ready(NOW)
                .activate(NOW);
    }

    private static final class RecordingWorkspace
            implements AgentWorkspaceService
    {
        private final List<TaskId> restored = new ArrayList<>();
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
        public void release(TaskId taskId) {}
    }

    private static final class RecordingApprovals
            implements AgentApprovalService
    {
        private final List<TurnId> denied = new ArrayList<>();

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
            denied.add(turnId);
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
