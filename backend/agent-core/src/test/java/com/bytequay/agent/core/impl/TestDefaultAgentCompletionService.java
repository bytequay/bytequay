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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UsageBaseline;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentCompletionService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/bytequay-completion-worktree");
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "sample");
    private static final TaskMetrics METRICS = new TaskMetrics(
            10,
            2,
            3,
            Optional.empty(),
            CostProvenance.UNAVAILABLE,
            Duration.ofSeconds(4));

    private InMemoryAgentStores stores;
    private RecordingResources resources;
    private RecordingWorkspace workspace;
    private AgentRuntimeRegistry runtimeRegistry;
    private TaskId taskId;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        resources = new RecordingResources();
        workspace = new RecordingWorkspace();
        runtimeRegistry = new AgentRuntimeRegistry();
        taskId = TaskId.random();
        stores.insert(AgentTask.created(
                        taskId,
                        "Completion test",
                        AgentLaneId.CODEX_CLI,
                        REPOSITORY,
                        WORKTREE,
                        AgentPermissionPolicy.MANUAL,
                        NOW)
                .ready(NOW)
                .activate(NOW));
    }

    @Test
    void completesAtomicallyAndIsIdempotent()
    {
        RecordingSession session = new RecordingSession();
        runtimeRegistry.registerSession(taskId, session);
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        TaskCompletion first = service.complete(new CompleteAgentTaskCommand(taskId, Optional.of("reviewed")));
        TaskCompletion repeated = service.complete(new CompleteAgentTaskCommand(taskId, Optional.of("ignored")));
        AgentTask stored = stores.find(taskId).orElseThrow();

        assertThat(first).isEqualTo(repeated);
        assertThat(first.summary().finalResponse()).isEqualTo("final response");
        assertThat(first.summary().workspace()).isEqualTo(workspace.snapshot);
        assertThat(first.summary().metrics()).isEqualTo(METRICS);
        assertThat(first.summary().notes()).contains("reviewed");
        assertThat(first.deleteAfter()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(stored.version()).isEqualTo(1);
        assertThat(session.closed).isTrue();
        assertThat(workspace.releases).isEqualTo(2);
    }

    @Test
    void rejectsAnActiveTurn()
    {
        stores.insert(AgentTurn.accepted(
                TurnId.random(),
                taskId,
                "request-1",
                NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");
    }

    @Test
    void rejectsCleanupBeforeTheRetentionDeadline()
    {
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));

        assertThatThrownBy(() -> service.cleanup(taskId))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    void retriesCleanupWithoutChangingTerminalFacts()
    {
        service(Clock.fixed(NOW, ZoneOffset.UTC))
                .complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        AgentTask completed = stores.find(taskId).orElseThrow();
        resources.failDeletion = true;
        DefaultAgentCompletionService dueService = service(
                Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC));

        assertThat(dueService.cleanup(taskId).state()).isEqualTo(CleanupState.RETRY_PENDING);
        AgentTask retry = stores.find(taskId).orElseThrow();
        assertThat(retry.state()).isEqualTo(completed.state());
        assertThat(retry.summary()).isEqualTo(completed.summary());

        resources.failDeletion = false;
        assertThat(dueService.cleanup(taskId).state()).isEqualTo(CleanupState.DELETED);
        assertThat(dueService.cleanup(taskId).state()).isEqualTo(CleanupState.DELETED);
        assertThat(resources.deletions).isEqualTo(2);
    }

    private DefaultAgentCompletionService service(Clock clock)
    {
        return new DefaultAgentCompletionService(
                stores,
                stores,
                new StaticTaskLog(taskId),
                workspace,
                resources,
                runtimeRegistry,
                new FixedMetrics(),
                clock);
    }

    private static final class StaticTaskLog
            implements TaskLogStore
    {
        private final List<AgentEventEnvelope> events;

        private StaticTaskLog(TaskId taskId)
        {
            events = ImmutableList.of(
                    event(taskId, 1, ProviderAgentEvent.MessagePhase.PARTIAL, "partial"),
                    event(taskId, 2, ProviderAgentEvent.MessagePhase.FINAL, "final response"));
        }

        @Override
        public void append(AgentEventEnvelope envelope)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return events.stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .collect(ImmutableList.toImmutableList());
        }

        @Override
        public long lastSequence(TaskId taskId)
        {
            return events.getLast().sequence();
        }

        @Override
        public BlobRef writeBlob(TaskId taskId, ByteBuffer content, String mediaType)
        {
            throw new UnsupportedOperationException();
        }

        private static AgentEventEnvelope event(
                TaskId taskId,
                long sequence,
                ProviderAgentEvent.MessagePhase phase,
                String text)
        {
            return new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    sequence,
                    taskId,
                    Optional.empty(),
                    NOW,
                    new ProviderAgentEvent.Message(
                            Optional.empty(),
                            Optional.empty(),
                            phase,
                            text,
                            Optional.empty()));
        }
    }

    private static final class RecordingWorkspace
            implements AgentWorkspaceService
    {
        private final WorkspaceSnapshot snapshot = new WorkspaceSnapshot(
                WORKTREE,
                REPOSITORY,
                Optional.of("main"),
                "0123456789abcdef",
                true);
        private int releases;

        @Override
        public WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceLease restore(TaskId taskId, Path canonicalWorktree, RepositoryIdentity repository)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceSnapshot inspect(Path worktree)
        {
            return snapshot;
        }

        @Override
        public void release(TaskId taskId)
        {
            releases++;
        }
    }

    private static final class RecordingResources
            implements AgentResourceService
    {
        private boolean failDeletion;
        private int deletions;

        @Override
        public TaskResources allocate(TaskId taskId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskResources require(TaskId taskId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(TaskId taskId)
        {
            deletions++;
            if (failDeletion) {
                throw new IllegalStateException("disk busy");
            }
        }
    }

    private static final class FixedMetrics
            implements AgentMetricsService
    {
        @Override
        public UsageBaseline beginTurn(TaskId taskId, TurnId turnId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public TurnMetrics record(TaskId taskId, TurnId turnId, ProviderUsageSample sample)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public TurnMetrics finishTurn(TurnId turnId, TurnState terminalState)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskMetrics taskTotals(TaskId taskId)
        {
            return METRICS;
        }
    }

    private static final class RecordingSession
            implements AgentSession
    {
        private boolean closed;

        @Override
        public AgentSessionRef reference()
        {
            return new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "session");
        }

        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }
}
