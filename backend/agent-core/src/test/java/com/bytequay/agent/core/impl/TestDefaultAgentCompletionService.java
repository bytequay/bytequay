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
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
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
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestDefaultAgentCompletionService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final Path WORKTREE = Path.of("/tmp/bytequay-completion-worktree");
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "sample");
    private static final AgentMetrics METRICS = new AgentMetrics(10, 2, 3, Optional.empty(), Duration.ofSeconds(4));

    private final AgentMetricsService metrics = mock(AgentMetricsService.class, invocation -> {
        throw new UnsupportedOperationException();
    });
    private InMemoryAgentStores stores;
    private AgentResourceService resources;
    private int worktreeCacheDeletions;
    private boolean failCacheDeletion;
    private boolean failResourceDeletion;
    private int resourceDeletions;
    private int retainedTranscripts;
    private RecordingWorktreeService worktrees;
    private AgentRuntimeRegistry runtimeRegistry;
    private TaskId taskId;
    private UnaryOperator<AgentTask> idleWorktreePreparation = task -> task;

    @BeforeEach
    void setUp()
    {
        doReturn(METRICS).when(metrics).taskTotals(any());
        stores = new InMemoryAgentStores();
        resources = resourceService();
        worktrees = new RecordingWorktreeService();
        runtimeRegistry = new AgentRuntimeRegistry(stores, ignored -> {}, ignored -> false, true);
        taskId = TaskId.random();
        stores.insert(managedTask(taskId, "Completion test"));
    }

    @Test
    void endingAndDeletingBorrowerPreservesDirtyDevelopmentCheckout()
    {
        AgentTask owner = stores.update(stores.find(taskId).orElseThrow().claimWorktreeMutation(WorktreeMutationClaim.localRun("developer-build", NOW), NOW));
        TaskId reviewerId = TaskId.random();
        AgentTask reviewer = created(reviewerId, false, "Reviewer", TitleSource.USER, owner.laneId(),
                owner.taskWorktree().orElseThrow(), owner.defaults(), NOW);
        stores.insert(reviewer);
        worktrees.uncommitted = true;
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.delete(taskId)).isInstanceOf(AgentConflictException.class).hasMessageContaining("End the review");
        service.complete(new CompleteAgentTaskCommand(reviewerId, Optional.empty()));
        service.cleanupWorktree(reviewerId);
        service.delete(reviewerId);
        assertThat(worktrees.deleted).isEmpty();
        assertThat(stores.find(taskId)).contains(owner);
    }

    @Test
    void developmentCompletesOnlyAfterUncommittedChangesAreHandled()
    {
        AgentTask original = stores.find(taskId).orElseThrow();
        AgentTask reviewing = created(taskId, true, original.title(), original.titleSource(), original.laneId(),
                original.taskWorktree().orElseThrow(), original.defaults(), NOW);
        stores.update(reviewing);
        worktrees.uncommitted = true;

        assertThatThrownBy(() -> service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("not clean");
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
        worktrees.uncommitted = false;
        service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void unfinishedContinuationBlocksCompletionAndDeletionBetweenTurns()
    {
        stores.update(stores.find(taskId).orElseThrow().withDevelopmentContinuation(true, NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Development automation is running");
        assertThatThrownBy(() -> service.delete(taskId))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Development automation is running");
        assertThat(worktrees.deleted).isEmpty();
        assertThat(stores.find(taskId).orElseThrow().developmentContinuationUnfinished()).isTrue();
    }

    @Test
    void liveContinuationOwnerAlsoBlocksDestructiveOperations()
    {
        runtimeRegistry.reserveDevelopmentOwner(taskId, TurnId.random());
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()))).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> service.delete(taskId)).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> service.abandon(taskId, "Stopped automation")).isInstanceOf(AgentConflictException.class);
        assertThat(worktrees.deleted).isEmpty();
    }

    @Test
    void lostContinuationRetainsRecoveryClaimUntilWriterIsProvenStopped()
    {
        WorktreeMutationClaim claim = WorktreeMutationClaim.recovery("lost-launch", NOW);
        stores.update(stores.find(taskId).orElseThrow().withDevelopmentContinuation(true, NOW).claimWorktreeMutation(claim, NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.abandon(taskId, "Lost automation"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("must stop its writers");
        assertThat(stores.find(taskId).orElseThrow()).satisfies(task -> {
            assertThat(task.state()).isEqualTo(TaskState.OPEN);
            assertThat(task.worktreeMutationClaim()).contains(claim);
            assertThat(task.developmentContinuationUnfinished()).isTrue();
        });
        stores.update(stores.find(taskId).orElseThrow().releaseWorktreeMutation(claim, NOW));
        AgentTask failed = service.abandon(taskId, "Lost automation");
        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.developmentContinuationUnfinished()).isFalse();
        assertThat(failed.worktreeMutationClaim()).isEmpty();
        assertThat(worktrees.deleted).isEmpty();
    }

    @Test
    void completionAndCleanupUseTheBranchRecoveredWhileTheTaskIsStillIdle()
    {
        String branch = "user/bytequay/recovered-branch";
        List<AgentTask> preparations = new ArrayList<>();
        idleWorktreePreparation = current -> {
            assertThat(stores.findActive(current.id())).isEmpty();
            assertThat(current.worktreeMutationClaim()).isEmpty();
            preparations.add(current);
            return current.state() == TaskState.OPEN ? stores.update(current.withBranch(branch, NOW)) : current;
        };
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        service.cleanupWorktree(taskId);

        assertThat(preparations).extracting(AgentTask::state).containsExactly(TaskState.OPEN, TaskState.COMPLETED);
        assertThat(worktrees.inspected).singleElement().satisfies(snapshot -> assertThat(snapshot.worktree().generatedBranch()).isEqualTo(branch));
        assertThat(worktrees.cleaned).singleElement().satisfies(worktree -> assertThat(worktree.generatedBranch()).isEqualTo(branch));
        assertThat(stores.find(taskId).orElseThrow().taskWorktree().orElseThrow().generatedBranch()).isEqualTo(branch);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abandonRecoversTheBranchAfterVerifiedStopBeforeMakingTheTaskTerminal(boolean recoveryClaim)
    {
        if (recoveryClaim) {
            stores.update(stores.find(taskId).orElseThrow().claimWorktreeMutation(WorktreeMutationClaim.recovery("lost-launch", NOW), NOW));
        }
        AgentSession session = session();
        runtimeRegistry.registerSession(taskId, session);
        String branch = "user/bytequay/recovered-branch";
        List<AgentTask> preparations = new ArrayList<>();
        idleWorktreePreparation = current -> {
            verify(session, atLeastOnce()).close();
            assertThat(stores.findActive(current.id())).isEmpty();
            preparations.add(current);
            return current.state() == TaskState.OPEN ? stores.update(current.withBranch(branch, NOW)) : current;
        };
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        AgentTask failed = service.abandon(taskId, "Stopped after recovery");
        service.cleanupWorktree(taskId);

        assertThat(failed.state()).isEqualTo(TaskState.FAILED);
        assertThat(failed.worktreeMutationClaim()).isEmpty();
        assertThat(preparations).extracting(AgentTask::state).containsExactly(TaskState.OPEN, TaskState.FAILED);
        assertThat(worktrees.cleaned).singleElement().satisfies(worktree -> assertThat(worktree.generatedBranch()).isEqualTo(branch));
    }

    @Test
    void deleteUsesTheRecoveredBranchAfterVerifiedStop()
    {
        AgentSession session = session();
        runtimeRegistry.registerSession(taskId, session);
        String branch = "user/bytequay/recovered-branch";
        idleWorktreePreparation = current -> {
            verify(session, atLeastOnce()).close();
            return stores.update(current.withBranch(branch, NOW));
        };

        service(Clock.fixed(NOW, ZoneOffset.UTC)).delete(taskId);

        assertThat(worktrees.deleted).singleElement().satisfies(worktree -> assertThat(worktree.generatedBranch()).isEqualTo(branch));
        assertThat(stores.find(taskId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "unverified"})
    void refusesIdleRecoveryForCompletionDeletionAndAbandonUntilTheWriterIsStopped(String blocker)
    {
        idleWorktreePreparation = current -> {
            throw new AssertionError("An active or unverified worktree must not be prepared");
        };
        if (blocker.equals("active")) {
            stores.insert(AgentTurn.accepted(TurnId.random(), taskId, "active", turnSettings(), NOW));
        }
        else {
            AgentSession session = mock(AgentSession.class);
            doThrow(new IllegalStateException("process tree still running")).when(session).close();
            runtimeRegistry.registerSession(taskId, session);
            assertThatThrownBy(() -> runtimeRegistry.closeSession(taskId)).isInstanceOf(IllegalStateException.class);
        }
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()))).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> service.delete(taskId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.abandon(taskId, "Cannot proceed yet")).isInstanceOf(RuntimeException.class);
        assertThat(worktrees.inspected).isEmpty();
        assertThat(worktrees.deleted).isEmpty();
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void completesAtomicallyAndIsIdempotent()
    {
        AgentSession session = session();
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
        verify(session, atLeastOnce()).close();
        assertThat(worktrees.cleaned).isEmpty();

        service.cleanupWorktree(taskId);
        service.cleanupWorktree(taskId);
        assertThat(worktrees.cleaned).hasSize(1);
        assertThat(worktreeCacheDeletions).isOne();
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
        assertThat(worktreeCacheDeletions).isZero();

        worktrees.failCleanup = false;
        service(Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)).cleanupWorktree(taskId);
        assertThat(worktrees.cleaned).hasSize(2);
        assertThat(worktreeCacheDeletions).isOne();
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).contains(NOW.plusSeconds(2));
    }

    @Test
    void retriesCacheDeletionBeforeMarkingTheWorktreeRemoved()
    {
        service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        failCacheDeletion = true;
        assertThatThrownBy(() -> service(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)).cleanupWorktree(taskId))
                .hasMessage("cache busy");
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).isEmpty();
        failCacheDeletion = false;
        service(Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)).cleanupWorktree(taskId);
        assertThat(worktreeCacheDeletions).isEqualTo(2);
        assertThat(stores.find(taskId).orElseThrow().worktreeRemovedAt()).contains(NOW.plusSeconds(2));
    }

    @Test
    void completesWithIgnoredBuildOutputButNotWithUncommittedWork()
    {
        worktrees.ignoredFiles = true;
        worktrees.uncommitted = true;
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not clean");
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);

        worktrees.uncommitted = false;
        service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.COMPLETED);
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
        stores.insert(AgentTurn.accepted(TurnId.random(), taskId, "request-1", turnSettings(), NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");
    }

    @Test
    void refusesToCompleteWhileInputWaitsInTheQueue()
    {
        stores.insert(queuedInput(taskId, 1, "queue-1"));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.complete(new CompleteAgentTaskCommand(taskId, Optional.empty())))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("queued input");
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void abandonsUncertainInputWhenTheTaskCompletes()
    {
        QueuedAgentInput uncertain = queuedInput(taskId, 1, "queue-1")
                .starting(TurnId.random(), EventId.random(), NOW)
                .uncertain("Backend restarted while the input was being sent", NOW);
        stores.insert(uncertain);
        // A consumed input is history and blocks nothing.
        stores.insert(queuedInput(taskId, 2, "queue-2").starting(TurnId.random(), EventId.random(), NOW).started(NOW));
        runtimeRegistry.registerSession(taskId, session());

        service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));

        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.COMPLETED);
        // Completing declined to resend it; it does not claim the provider never got it.
        assertThat(stores.find(uncertain.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.ABANDONED);
        assertThat(stores.find(uncertain.id()).orElseThrow().pauseReason()).contains("Task completed without resending it");
    }

    private static QueuedAgentInput queuedInput(TaskId taskId, long position, String idempotencyKey)
    {
        return QueuedAgentInput.queued(
                QueuedInputId.random(),
                taskId,
                position,
                idempotencyKey,
                new BlobRef("blobs/queue-" + position, "text/plain; charset=utf-8", 5),
                "later",
                turnSettings(),
                NOW);
    }

    @Test
    void deletesAnIdleTaskWithItsCheckoutBranchResourcesAndRows()
    {
        stores.insert(queuedInput(taskId, 1, "queue-1"));
        AgentSession session = session();
        runtimeRegistry.registerSession(taskId, session);
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        service.delete(taskId);

        verify(session, atLeastOnce()).close();
        // Delete, not cleanup: the branch goes with the checkout, dirty or not.
        assertThat(worktrees.deleted).hasSize(1);
        assertThat(worktrees.cleaned).isEmpty();
        assertThat(resourceDeletions).isEqualTo(1);
        assertThat(stores.find(taskId)).isEmpty();
        assertThat(stores.listQueued(taskId)).isEmpty();

        // Deleting what is already gone is a no-op, not an error.
        service.delete(taskId);
        assertThat(worktrees.deleted).hasSize(1);
        assertThat(resourceDeletions).isEqualTo(1);
    }

    @Test
    void refusesToDeleteWhileATurnRunsOrTheWorktreeIsClaimed()
    {
        stores.insert(AgentTurn.accepted(TurnId.random(), taskId, "request-1", turnSettings(), NOW));
        TaskId claimed = TaskId.random();
        stores.insert(managedTask(claimed, "Claimed").claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.delete(taskId))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("active turn");
        assertThatThrownBy(() -> service.delete(claimed))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("claimed");

        assertThat(worktrees.deleted).isEmpty();
        assertThat(resourceDeletions).isZero();
        assertThat(stores.find(taskId)).isPresent();
        assertThat(stores.find(claimed)).isPresent();
    }

    @Test
    void deletesATaskStuckHoldingARecoveryClaim()
    {
        stores.update(stores.find(taskId).orElseThrow()
                .claimWorktreeMutation(WorktreeMutationClaim.recovery("launch-1", NOW), NOW));
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        // The claim holds a worktree nothing is known to be writing; refusing here left the task
        // with no way out of recovery at all.
        service.delete(taskId);

        assertThat(worktrees.deleted).hasSize(1);
        assertThat(stores.find(taskId)).isEmpty();
    }

    @Test
    void keepsTheTaskWhenItsCheckoutCannotBeDeleted()
    {
        worktrees.failDelete = true;
        DefaultAgentCompletionService service = service(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.delete(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("branch checked out elsewhere");

        // Git refused first, so nothing else was touched and the task is still there to retry.
        assertThat(resourceDeletions).isZero();
        assertThat(stores.find(taskId)).isPresent();
    }

    @Test
    void abandonsATaskStuckHoldingAWorktreeClaim()
    {
        idleWorktreePreparation = current -> {
            throw new AssertionError("A stale turn claim is not idle recovery authority");
        };
        TurnId turnId = TurnId.random();
        stores.update(stores.find(taskId).orElseThrow()
                .claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, NOW), NOW));
        AgentSession session = session();
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
        verify(session, atLeastOnce()).close();
    }

    @Test
    void removesWaitingQueueRowsAndAbandonsUncertainOnMarkFailed()
    {
        QueuedAgentInput waiting = queuedInput(taskId, 1, "queue-1");
        QueuedAgentInput paused = queuedInput(taskId, 2, "queue-2").paused("provider unavailable", NOW);
        QueuedAgentInput uncertain = queuedInput(taskId, 3, "queue-3")
                .starting(TurnId.random(), EventId.random(), NOW)
                .uncertain("delivery was not acknowledged", NOW);
        AgentTurn knownUnsentTurn = AgentTurn.accepted(TurnId.random(), taskId, "known-unsent", turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .finishKnownUnsent(AgentMetrics.empty(), "not sent", NOW);
        AgentTurn attemptedTurn = AgentTurn.accepted(TurnId.random(), taskId, "attempted", turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .finish(TurnState.INTERRUPTED, AgentMetrics.empty(), Optional.of("response lost"), NOW);
        QueuedAgentInput knownUnsent = queuedInput(taskId, 4, "queue-4")
                .starting(knownUnsentTurn.id(), EventId.random(), NOW);
        QueuedAgentInput attempted = queuedInput(taskId, 5, "queue-5")
                .starting(attemptedTurn.id(), EventId.random(), NOW);
        stores.insert(waiting);
        stores.insert(paused);
        stores.insert(uncertain);
        stores.insert(knownUnsentTurn);
        stores.insert(attemptedTurn);
        stores.insert(knownUnsent);
        stores.insert(attempted);

        service(Clock.fixed(NOW, ZoneOffset.UTC)).abandon(taskId, "provider cannot recover");

        assertThat(stores.find(waiting.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.REMOVED);
        assertThat(stores.find(paused.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.REMOVED);
        assertThat(stores.find(uncertain.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.ABANDONED);
        assertThat(stores.find(knownUnsent.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.REMOVED);
        assertThat(stores.find(attempted.id()).orElseThrow().state()).isEqualTo(QueuedAgentInput.State.ABANDONED);
        assertThat(stores.find(taskId).orElseThrow().state()).isEqualTo(TaskState.FAILED);
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
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.empty());
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
        failResourceDeletion = true;
        DefaultAgentCompletionService dueService = service(Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC));

        assertThat(cleanup(dueService)).isEqualTo(CleanupState.RETRY_PENDING);
        AgentTask retry = stores.find(taskId).orElseThrow();
        assertThat(retry.state()).isEqualTo(completed.state());
        assertThat(retry.summary()).isEqualTo(completed.summary());

        failResourceDeletion = false;
        assertThat(cleanup(dueService)).isEqualTo(CleanupState.DELETED);
        assertThat(cleanup(dueService)).isEqualTo(CleanupState.DELETED);
        assertThat(retainedTranscripts).isEqualTo(2);
        assertThat(resourceDeletions).isZero();  // retention keeps the transcript; only deleting the task removes it
    }

    @Test
    void retentionCleanupRetriesTheWorktreeBeforeDeletingRuntimeFiles()
    {
        service(Clock.fixed(NOW, ZoneOffset.UTC)).complete(new CompleteAgentTaskCommand(taskId, Optional.empty()));
        DefaultAgentCompletionService dueService = service(Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC));
        worktrees.failCleanup = true;

        assertThat(cleanup(dueService)).isEqualTo(CleanupState.RETRY_PENDING);
        assertThat(retainedTranscripts).isZero();

        worktrees.failCleanup = false;
        assertThat(cleanup(dueService)).isEqualTo(CleanupState.DELETED);
        assertThat(retainedTranscripts).isOne();
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
        DefaultAgentCompletionService due = service(Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC), log);

        assertThat(cleanup(due)).isEqualTo(CleanupState.RETRY_PENDING);
        assertThat(worktrees.cleaned).isEmpty();
        assertThat(retainedTranscripts).isZero();
        assertThat(stores.listPending(taskId, 10)).hasSize(1);

        assertThat(cleanup(due)).isEqualTo(CleanupState.DELETED);
        assertThat(worktrees.cleaned).hasSize(1);
        assertThat(retainedTranscripts).isOne();
        assertThat(stores.listPending(taskId, 10)).isEmpty();
    }

    private CleanupState cleanup(DefaultAgentCompletionService service)
    {
        service.cleanup(taskId);
        return stores.find(taskId).orElseThrow().cleanupState();
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
                stores,
                new DefaultAgentEventService(log, stores, stores, clock),
                log,
                worktrees,
                resources,
                runtimeRegistry,
                metrics,
                () -> Duration.ofDays(7),
                clock,
                task -> idleWorktreePreparation.apply(task));
    }

    private static final class StaticTaskLog
            extends InMemoryTaskLogStore
    {
        private int failingAppends;

        private StaticTaskLog(TaskId taskId)
        {
            append(event(taskId, 1, MessagePhase.PARTIAL, "partial"));
            append(event(taskId, 2, MessagePhase.FINAL, "final response"));
        }

        @Override
        public void append(AgentEventEnvelope envelope)
        {
            if (failingAppends > 0) {
                failingAppends--;
                throw new AgentStorageException("task log unavailable");
            }
            super.append(envelope);
        }

        private void failNextAppends(int count)
        {
            failingAppends = count;
        }

        private static AgentEventEnvelope event(TaskId taskId, long sequence, MessagePhase phase, String text)
        {
            return new AgentEventEnvelope(
                    AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                    EventId.random(),
                    sequence,
                    taskId,
                    Optional.empty(),
                    root(),
                    NOW,
                    new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, phase, text));
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
        return created(taskId, false, title, TitleSource.USER, AgentLaneId.CODEX_CLI, worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private static final class RecordingWorktreeService
            implements WorktreeService
    {
        private final List<WorktreeSnapshot> inspected = new ArrayList<>();
        private final List<TaskWorktree> cleaned = new ArrayList<>();
        private final List<TaskWorktree> deleted = new ArrayList<>();
        private boolean failCleanup;
        private boolean failDelete;
        private boolean ignoredFiles;
        private boolean uncommitted;

        @Override
        public Path ownedCheckout(TaskWorktree worktree)
        {
            return worktree.canonicalWorktree();
        }

        @Override
        public TaskWorktree provision(ProvisionWorktreeCommand command)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorktreeSnapshot inspect(TaskWorktree worktree)
        {
            WorktreeSnapshot snapshot = new WorktreeSnapshot(worktree, "1123456789012345678901234567890123456789", ignoredFiles || uncommitted, false, uncommitted);
            inspected.add(snapshot);
            return snapshot;
        }

        @Override
        public void delete(TaskWorktree worktree)
        {
            deleted.add(worktree);
            if (failDelete) {
                throw new IllegalStateException("branch checked out elsewhere");
            }
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

    private AgentResourceService resourceService()
    {
        AgentResourceService resources = mock(AgentResourceService.class, invocation -> {
            throw new UnsupportedOperationException();
        });
        doAnswer(invocation -> {
            worktreeCacheDeletions++;
            if (failCacheDeletion) {
                throw new IllegalStateException("cache busy");
            }
            return null;
        }).when(resources).deleteWorktreeCache(any());
        doAnswer(invocation -> resources(invocation.getArgument(0))).when(resources).require(any());
        doAnswer(invocation -> resources(invocation.getArgument(0))).when(resources).pathsForDeletion(any());
        doAnswer(invocation -> {
            resourceDeletions++;
            deleteResources();
            return null;
        }).when(resources).delete(any());
        doAnswer(invocation -> {
            retainedTranscripts++;
            deleteResources();
            return null;
        }).when(resources).deleteExceptTranscript(any());
        return resources;
    }

    private void deleteResources()
    {
        if (failResourceDeletion) {
            throw new IllegalStateException("disk busy");
        }
    }

    private static TaskResources resources(TaskId taskId)
    {
        // Completion hands these paths to the code graph for index deletion; nothing here touches the disk.
        Path runtime = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-completion-test", taskId.toString()).toAbsolutePath().normalize();
        return new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider").resolve("scratch"));
    }

    private static AgentSession session()
    {
        AgentSession session = mock(AgentSession.class, invocation -> {
            throw new UnsupportedOperationException();
        });
        doReturn(new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "session")).when(session).reference();
        doReturn(false).when(session).retained();
        doNothing().when(session).close();
        return session;
    }
}
