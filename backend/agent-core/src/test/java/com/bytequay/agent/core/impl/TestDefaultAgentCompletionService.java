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
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentSandboxPolicy;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.BlobRef;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UsageBaseline;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderTurnRef;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.collect.ImmutableList.toImmutableList;
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
    private RecordingWorktreeService worktrees;
    private AgentRuntimeRegistry runtimeRegistry;
    private TaskId taskId;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        resources = new RecordingResources();
        worktrees = new RecordingWorktreeService();
        runtimeRegistry = new AgentRuntimeRegistry();
        taskId = TaskId.random();
        stores.insert(managedTask(taskId, "Completion test"));
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
        assertThat(first.summary().worktree()).contains(worktrees.inspected.getFirst());
        assertThat(first.summary().metrics()).isEqualTo(METRICS);
        assertThat(first.summary().notes()).contains("reviewed");
        assertThat(first.deleteAfter()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(stored.version()).isEqualTo(2);
        assertThat(stored.worktreeMutationClaim()).isEmpty();
        assertThat(stores.outboxEntries())
                .filteredOn(entry -> entry.payload() instanceof AgentEventOutboxPayload.Inline inline
                        && inline.payload() instanceof AgentEventPayload.Lifecycle lifecycle
                        && lifecycle.phase() == AgentEventPayload.LifecyclePhase.TASK_COMPLETED)
                .hasSize(1);
        assertThat(session.closed).isTrue();
        assertThat(worktrees.cleaned).isEmpty();

        service.cleanupWorktree(taskId);
        service.cleanupWorktree(taskId);
        assertThat(worktrees.cleaned).hasSize(1);
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).contains(NOW);
    }

    @Test
    void retriesWorktreeCleanupAfterTheTerminalCommit()
    {
        service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        worktrees.failCleanup = true;
        DefaultAgentCompletionService failedAttempt = service(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        assertThatThrownBy(() -> failedAttempt.cleanupWorktree(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worktree busy");
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).isEmpty();
        assertThat(stores.find(taskId).orElseThrow().updatedAt()).isEqualTo(NOW.plusSeconds(1));

        worktrees.failCleanup = false;
        service(Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)).cleanupWorktree(taskId);
        assertThat(worktrees.cleaned).hasSize(2);
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).contains(NOW.plusSeconds(2));
    }

    @Test
    void completesAnOpenTaskThatNeverStartedATurn()
    {
        TaskId openTaskId = TaskId.random();
        stores.insert(managedTask(openTaskId, "Unused task"));

        TaskCompletion completion = service(Clock.fixed(NOW, ZoneOffset.UTC))
                .complete(new CompleteAgentTaskCommand(openTaskId, Optional.of("cancelled before first turn")));

        assertThat(stores.find(openTaskId).orElseThrow().state()).isEqualTo(TaskState.COMPLETED);
        assertThat(completion.summary().notes()).contains("cancelled before first turn");
    }

    @Test
    void rejectsAnActiveTurn()
    {
        stores.insert(AgentTurn.accepted(
                TurnId.random(),
                taskId,
                "request-1",
                turnSettings(),
                NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");
    }

    @Test
    void abandonsATaskStuckHoldingAWorktreeClaim()
    {
        TurnId turnId = TurnId.random();
        stores.update(stores.find(taskId).orElseThrow()
                .claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, NOW), NOW));
        RecordingSession session = new RecordingSession();
        runtimeRegistry.registerSession(taskId, session);
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        AgentTask failed = service.abandon(taskId, "provider activity could not be shown stopped");

        // Completing is impossible while the claim is held, so this is the only way out. The
        // worktree is retained rather than cleaned, because whatever the provider left is the
        // evidence someone will want.
        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.worktreeMutationClaim()).isEmpty();
        assertThat(failed.terminalReason()).contains("provider activity could not be shown stopped");
        assertThat(worktrees.cleaned).isEmpty();
        // The task's own process is stopped before the task is given up on.
        assertThat(session.closed).isTrue();
    }

    @Test
    void refusesToAbandonATaskWhileItsTurnIsActive()
    {
        AgentTurn active = AgentTurn.accepted(TurnId.random(), taskId, "active", turnSettings(), NOW)
                .markDeliveryStarted(NOW.plusSeconds(1))
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
        stores.update(stores.find(taskId).orElseThrow()
                .claimWorktreeMutation(WorktreeMutationClaim.turn(active.id(), active.createdAt()), NOW));
        stores.insert(active);
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        // Stopping the turn is its own confirmed action; failing the task under it would leave the
        // turn's completion with no open task to record itself against.
        assertThatThrownBy(() -> service.abandon(taskId, "giving up"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void refusesToCompleteATaskStuckHoldingAWorktreeClaim()
    {
        stores.update(stores.find(taskId).orElseThrow()
                .claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        // A turn whose provider could not be shown stopped keeps the claim, and that has to block
        // completing the task as well as starting another turn.
        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("claimed by TURN");
    }

    @Test
    void refusesToAbandonATerminalTask()
    {
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));

        assertThatThrownBy(() -> service.abandon(taskId, "too late"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("already terminal");
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

    @Test
    void retentionCleanupRetriesTheWorktreeBeforeDeletingRuntimeFiles()
    {
        service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        DefaultAgentCompletionService dueService = service(Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC));
        worktrees.failCleanup = true;

        assertThat(dueService.cleanup(taskId).state()).isEqualTo(CleanupState.RETRY_PENDING);
        assertThat(resources.deletions).isZero();

        worktrees.failCleanup = false;
        assertThat(dueService.cleanup(taskId).state()).isEqualTo(CleanupState.DELETED);
        assertThat(resources.deletions).isOne();
    }

    @Test
    void drainsTerminalEventBeforeWorkflowWorktreeCleanup()
    {
        StaticTaskLog log = new StaticTaskLog(taskId);
        log.failNextAppends(2);
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC), log);

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("task log unavailable");
        assertThatThrownBy(() -> service.cleanupWorktree(taskId))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("task log unavailable");
        assertThat(worktrees.cleaned).isEmpty();
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).isEmpty();
        assertThat(stores.listPending(taskId, 10)).hasSize(1);

        service.cleanupWorktree(taskId);

        assertThat(worktrees.cleaned).hasSize(1);
        assertThat(stores.listPending(taskId, 10)).isEmpty();
    }

    @Test
    void drainsTerminalEventBeforeRetentionDeletesResources()
    {
        StaticTaskLog log = new StaticTaskLog(taskId);
        log.failNextAppends(2);
        DefaultAgentCompletionService completion = service(Clock.fixed(NOW, ZoneOffset.UTC), log);
        assertThatThrownBy(() -> completion.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("task log unavailable");
        DefaultAgentCompletionService due = service(
                Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC),
                log);

        assertThat(due.cleanup(taskId).state()).isEqualTo(CleanupState.RETRY_PENDING);
        assertThat(worktrees.cleaned).isEmpty();
        assertThat(resources.deletions).isZero();
        assertThat(stores.listPending(taskId, 10)).hasSize(1);

        assertThat(due.cleanup(taskId).state()).isEqualTo(CleanupState.DELETED);
        assertThat(worktrees.cleaned).hasSize(1);
        assertThat(resources.deletions).isOne();
        assertThat(stores.listPending(taskId, 10)).isEmpty();
    }

    private DefaultAgentCompletionService service(Clock clock)
    {
        return service(clock, new StaticTaskLog(taskId));
    }

    private DefaultAgentCompletionService service(Clock clock, StaticTaskLog log)
    {
        return new DefaultAgentCompletionService(
                stores,
                stores,
                stores,
                stores,
                new DefaultAgentEventService(log, stores, stores, clock),
                log,
                worktrees,
                resources,
                runtimeRegistry,
                new FixedMetrics(),
                clock);
    }

    private static final class StaticTaskLog
            implements TaskLogStore
    {
        private final List<AgentEventEnvelope> events = new ArrayList<>();
        private int failingAppends;

        private StaticTaskLog(TaskId taskId)
        {
            events.add(event(taskId, 1, AgentEventPayload.MessagePhase.PARTIAL, "partial"));
            events.add(event(taskId, 2, AgentEventPayload.MessagePhase.FINAL, "final response"));
        }

        @Override
        public void append(AgentEventEnvelope envelope)
        {
            if (failingAppends > 0) {
                failingAppends--;
                throw new AgentStorageException("task log unavailable");
            }
            events.add(envelope);
        }

        private void failNextAppends(int count)
        {
            failingAppends = count;
        }

        @Override
        public Optional<Long> findSequence(TaskId taskId, EventId eventId)
        {
            return events.stream()
                    .filter(event -> event.taskId().equals(taskId))
                    .filter(event -> event.eventId().equals(eventId))
                    .map(AgentEventEnvelope::sequence)
                    .findFirst();
        }

        @Override
        public List<AgentEventEnvelope> readAfter(TaskId taskId, long afterSequence, int limit)
        {
            return events.stream()
                    .filter(event -> event.sequence() > afterSequence)
                    .limit(limit)
                    .collect(toImmutableList());
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

        @Override
        public ByteBuffer readBlob(TaskId taskId, BlobRef blobRef)
        {
            throw new UnsupportedOperationException();
        }

        private static AgentEventEnvelope event(
                TaskId taskId,
                long sequence,
                AgentEventPayload.MessagePhase phase,
                String text)
        {
            return new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    EventId.legacy(taskId, sequence),
                    sequence,
                    taskId,
                    Optional.empty(),
                    root(),
                    NOW,
                    new AgentEventPayload.AssistantMessage(
                            AgentEventPayload.MessageKind.ANSWER,
                            phase,
                            text));
        }
    }

    private static AgentTask managedTask(TaskId taskId, String title)
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
                title,
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                NOW);
    }

    private static final class RecordingWorktreeService
            implements WorktreeService
    {
        private final List<WorktreeSnapshot> inspected = new ArrayList<>();
        private final List<TaskWorktree> cleaned = new ArrayList<>();
        private boolean failCleanup;

        @Override
        public TaskWorktree provision(ProvisionWorktreeCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorktreeSnapshot inspect(TaskWorktree worktree)
        {
            WorktreeSnapshot snapshot = new WorktreeSnapshot(
                    worktree,
                    "1123456789012345678901234567890123456789",
                    false,
                    false);
            inspected.add(snapshot);
            return snapshot;
        }

        @Override
        public void cleanup(TaskWorktree worktree)
        {
            cleaned.add(worktree);
            if (failCleanup) {
                throw new IllegalStateException("worktree busy");
            }
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
