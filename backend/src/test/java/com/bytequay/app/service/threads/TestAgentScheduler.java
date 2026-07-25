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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFile;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorktreeLease;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.WorktreeLeaseStore;
import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionBudgetPolicy;
import com.bytequay.app.service.skills.CavemanPrompt;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.bytequay.app.domain.ThreadKind.CLI_AGENT;
import static com.bytequay.app.domain.ThreadKind.LOGIC_LOOP;
import static com.bytequay.app.domain.ThreadTurnEventType.CODEGRAPH_POLICY;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_CANCELLED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_FAILED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_FINISHED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_QUEUED;
import static com.bytequay.app.domain.ThreadTurnEventType.TURN_STARTED;
import static com.bytequay.app.domain.ThreadTurnEventType.WAITING_FOR_CAPACITY;
import static com.bytequay.app.domain.ThreadTurnStatus.CANCELLED;
import static com.bytequay.app.domain.ThreadTurnStatus.COMPLETED;
import static com.bytequay.app.domain.ThreadTurnStatus.FAILED;
import static com.bytequay.app.domain.ThreadTurnStatus.QUEUED;
import static com.bytequay.app.domain.ThreadTurnStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentScheduler
{
    @Test
    void dispatchesOnlyAfterTheSurroundingTransactionCommits()
            throws InterruptedException
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-transactional", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        TransactionSynchronizationManager.initSynchronization();
        try {
            String turnId = harness.scheduler.enqueueTurn(thread, "after commit");

            assertThat(session.inputs).isEmpty();
            assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                    .isEqualTo(QUEUED);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());

            assertThat(session.firstSend.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.inputs).containsExactly("after commit");
            assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                    .isEqualTo(RUNNING);
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackTurnNeverReachesTheProvider()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-rollback", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        TransactionSynchronizationManager.initSynchronization();
        try {
            harness.scheduler.enqueueTurn(thread, "must roll back");

            assertThat(session.inputs).isEmpty();
        }
        finally {
            // No afterCommit callback is fired on rollback.
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(session.inputs).isEmpty();
    }

    @Test
    void afterCommitCallbackDoesNotResurrectATurnCancelledBeforeItRuns()
            throws InterruptedException
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread brain = thread("brain-cancelled-before-callback", ThreadKind.BRAIN_AGENT);
        RecordingSession session = harness.register(brain);

        TransactionSynchronizationManager.initSynchronization();
        String turnId;
        List<TransactionSynchronization> callbacks;
        try {
            turnId = harness.scheduler.enqueueTaskTurn(
                    brain, "stale callback", "task-1", "stage-1",
                    TurnInitiator.unattended("brain-review"), "run-1");
            callbacks = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        }
        finally {
            // Let cancellation run outside the transaction before the captured
            // afterCommit callback gets CPU time.
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(harness.scheduler.cancelSessionTurns("run-1")).isEqualTo(1);
        callbacks.forEach(TransactionSynchronization::afterCommit);

        assertThat(session.firstSend.await(250, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(session.inputs).isEmpty();
        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status()).isEqualTo(CANCELLED);
    }

    @Test
    void rolledBackSessionCancellationLeavesItsRunningTurnAlone()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread brain = thread("brain-rollback-cancel", ThreadKind.BRAIN_AGENT);
        RecordingSession session = harness.register(brain);
        String turnId = harness.scheduler.enqueueTaskTurn(
                brain, "keep running", "task-1", "stage-1",
                TurnInitiator.unattended("brain-review"), "run-1");

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(harness.scheduler.cancelSessionTurns("run-1")).isZero();
            assertThat(session.interrupts).isZero();
            assertThat(harness.turns.findTurnById(turnId).orElseThrow().status()).isEqualTo(RUNNING);
        }
        finally {
            // No afterCommit callback: model a rollback.
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(session.interrupts).isZero();
        session.completeNext();
        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status()).isEqualTo(COMPLETED);
    }

    @Test
    void committedSessionCancellationInterruptsAfterCommit()
            throws InterruptedException
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread brain = thread("brain-committed-cancel", ThreadKind.BRAIN_AGENT);
        RecordingSession session = harness.register(brain);
        String turnId = harness.scheduler.enqueueTaskTurn(
                brain, "cancel after commit", "task-1", "stage-1",
                TurnInitiator.unattended("brain-review"), "run-1");

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(harness.scheduler.cancelSessionTurns("run-1")).isZero();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(session.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status()).isEqualTo(CANCELLED);
    }

    @Test
    void serializesDifferentStagesThatShareOneBrainSession()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread brain = thread("brain-1", ThreadKind.BRAIN_AGENT);
        RecordingSession session = harness.register(brain);
        String firstStage = "11111111-1111-1111-1111-111111111111";
        String secondStage = "22222222-2222-2222-2222-222222222222";

        String first = harness.scheduler.enqueueTaskTurn(
                brain, "first review", "task-1", firstStage,
                TurnInitiator.unattended("brain-review"));
        String second = harness.scheduler.enqueueTaskTurn(
                brain, "second review", "task-1", secondStage,
                TurnInitiator.unattended("brain-review"));

        assertThat(harness.turns.findTurnById(first).orElseThrow().status()).isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(second).orElseThrow().status()).isEqualTo(QUEUED);
        assertThat(session.inputs).containsExactly("first review");
        assertThat(session.activeTaskIds).containsExactly("task-1");
        assertThat(session.activeStageIds).containsExactly(firstStage);

        session.completeNext();

        assertThat(session.inputs).containsExactly("first review", "second review");
        assertThat(session.activeStageIds).containsExactly(firstStage, secondStage);
    }

    @Test
    void cancellingASessionInterruptsItsRunningTurnAndPersistsCancellation()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread brain = thread("brain-1", ThreadKind.BRAIN_AGENT);
        RecordingSession session = harness.register(brain);
        String turnId = harness.scheduler.enqueueTaskTurn(
                brain, "review", "task-1", "stage-1",
                TurnInitiator.unattended("brain-review"), "run-1");

        assertThat(harness.scheduler.cancelSessionTurns("run-1")).isEqualTo(1);

        assertThat(session.interrupts).isEqualTo(1);
        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                .isEqualTo(CANCELLED);
        assertThat(harness.events.listEventsByTaskId(brain.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(turnId);
                    assertThat(event.event()).isEqualTo(TURN_CANCELLED);
                });
    }

    @Test
    void cancellingASessionTerminallyClosesAnOrphanedRunningTurn()
    {
        TestHarness harness = new TestHarness(2, 4);
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        ThreadTurn orphan = taskTurn(
                "orphan-session-turn", "missing-thread", "task-orphan",
                RUNNING, "run-orphan", now);
        harness.turns.saveTurn(orphan);

        assertThat(harness.scheduler.cancelSessionTurns("run-orphan")).isEqualTo(1);

        ThreadTurn cancelled = harness.turns.findTurnById(orphan.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled.errorMessage()).isEqualTo("cancelled by session control");
        assertThat(harness.scheduler.cancelSessionTurns("run-orphan")).isZero();
    }

    @Test
    void cancellingATaskTerminallyClosesAnOrphanedRunningTurn()
    {
        TestHarness harness = new TestHarness(2, 4);
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        ThreadTurn orphan = taskTurn(
                "orphan-task-turn", "missing-thread", "task-orphan",
                RUNNING, "run-orphan", now);
        harness.turns.saveTurn(orphan);

        assertThat(harness.scheduler.cancelTaskTurns("task-orphan")).isEqualTo(1);

        ThreadTurn cancelled = harness.turns.findTurnById(orphan.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo(CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(cancelled.errorMessage()).isEqualTo("cancelled by task lifecycle action");
        assertThat(harness.scheduler.cancelTaskTurns("task-orphan")).isZero();
    }

    @Test
    void cancellationCanInterruptADispatchedSessionAfterRegistryEviction()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("evicted-thread", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        String turnId = harness.scheduler.enqueueTaskTurn(
                thread, "running", "task-evicted", "stage-evicted",
                TurnInitiator.user(), "run-evicted");
        harness.registry.sessions.remove(thread.id());

        assertThat(harness.scheduler.cancelTaskTurns("task-evicted")).isEqualTo(1);

        assertThat(session.interrupts).isEqualTo(1);
        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                .isEqualTo(CANCELLED);
    }

    @Test
    void cancellationCannotBeOverwrittenByAConcurrentCompletion()
            throws InterruptedException
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("completing-thread", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        String turnId = harness.scheduler.enqueueTaskTurn(
                thread, "running", "task-completing", "stage-completing",
                TurnInitiator.user(), "run-completing");
        session.blockNextStatusRead();
        CountDownLatch completionReturned = new CountDownLatch(1);
        java.lang.Thread.startVirtualThread(() -> {
            try {
                session.completeNext();
            }
            finally {
                completionReturned.countDown();
            }
        });
        assertThat(session.statusReadStarted.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(harness.scheduler.cancelSessionTurns("run-completing")).isEqualTo(1);
        session.releaseStatusRead.countDown();
        assertThat(completionReturned.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(session.interrupts).isEqualTo(1);
        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                .isEqualTo(CANCELLED);
        assertThat(harness.scheduler.cancelSessionTurns("run-completing")).isZero();
    }

    @Test
    void cancellingATaskStopsOnlyThatTasksDurableTurns()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread targetThread = thread("thread-target", CLI_AGENT);
        Thread siblingThread = thread("thread-sibling", CLI_AGENT);
        RecordingSession targetSession = harness.register(targetThread);
        RecordingSession siblingSession = harness.register(siblingThread);

        String running = harness.scheduler.enqueueTaskTurn(
                targetThread, "target running", "task-target", "stage-target",
                TurnInitiator.user(), "run-target");
        String queued = harness.scheduler.enqueueTaskTurn(
                targetThread, "target queued", "task-target", "stage-target",
                TurnInitiator.user(), "run-target-next");
        String sibling = harness.scheduler.enqueueTaskTurn(
                siblingThread, "sibling running", "task-sibling", "stage-sibling",
                TurnInitiator.user(), "run-sibling");

        assertThat(harness.scheduler.cancelTaskTurns("task-target")).isEqualTo(2);

        ThreadTurn cancelledRunning = harness.turns.findTurnById(running).orElseThrow();
        assertThat(cancelledRunning.status()).isEqualTo(CANCELLED);
        assertThat(cancelledRunning.errorMessage())
                .isEqualTo("cancelled by task lifecycle action");
        assertThat(harness.turns.findTurnById(queued).orElseThrow().status()).isEqualTo(CANCELLED);
        assertThat(harness.turns.findTurnById(sibling).orElseThrow().status()).isEqualTo(RUNNING);
        assertThat(targetSession.inputs).containsExactly("target running");
        assertThat(siblingSession.inputs).containsExactly("sibling running");
    }

    @Test
    void budgetPauseSuppressesTheOrdinaryRunSuccessTransition()
    {
        AgentRunService agentRuns = mock(AgentRunService.class);
        SessionBudgetPolicy budgets = mock(SessionBudgetPolicy.class);
        when(budgets.account(any(), any(), any())).thenReturn(true);
        TestHarness harness = new TestHarness(1, 4, agentRuns, budgets);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        harness.scheduler.setApplicationEventPublisher(publisher);
        Thread thread = thread("thread-budget", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String turnId = harness.scheduler.enqueueTaskTurn(
                thread, "finish", "task-1", "stage-1",
                TurnInitiator.user(), "run-ordinary");
        session.completeNext();

        InOrder order = inOrder(agentRuns, budgets);
        order.verify(agentRuns).transition(
                "run-ordinary", AgentRun.STATUS_RUNNING, "scheduler started");
        order.verify(budgets).account(eq("run-ordinary"), any(), any());
        verify(agentRuns, never()).transition(
                "run-ordinary", AgentRun.STATUS_SUCCEEDED, "scheduler turn completed");
        verify(publisher).publishEvent(new TaskTurnBudgetPausedEvent("task-1", turnId));
        verify(publisher, never()).publishEvent(any(TaskTurnFinishedEvent.class));
    }

    @Test
    void stoppedTasksAreCancelledBeforeRecoveredTurnsCanDispatch()
    {
        TestHarness harness = new TestHarness(20, 4);
        Thread thread = thread("thread-stopped-recovery", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        List<TaskStatus> stopped = List.of(
                TaskStatus.PAUSED,
                TaskStatus.NEEDS_ATTENTION,
                TaskStatus.COMPLETED,
                TaskStatus.REMOTE_CLOSED,
                TaskStatus.ERRORED,
                TaskStatus.CANCELED,
                TaskStatus.ARCHIVED);

        int index = 0;
        for (TaskStatus status : stopped) {
            String taskId = "task-stopped-" + index;
            harness.tasks.setStatus(taskId, status);
            harness.turns.saveTurn(taskTurn(
                    "queued-stopped-" + index, thread.id(), taskId,
                    QUEUED, "queued-run-" + index, now.plusMillis(index)));
            harness.turns.saveTurn(taskTurn(
                    "running-stopped-" + index, thread.id(), taskId,
                    RUNNING, "running-run-" + index, now.plusMillis(100 + index)));
            index++;
        }

        harness.scheduler.recoverQueuedTurns();

        assertThat(session.inputs).isEmpty();
        assertThat(harness.turns.turns.values())
                .extracting(ThreadTurn::status)
                .containsOnly(CANCELLED);
        for (TaskStatus status : stopped) {
            assertThat(harness.turns.turns.values())
                    .filteredOn(turn -> turn.errorMessage() != null
                            && turn.errorMessage().endsWith(status.name().toLowerCase(Locale.ROOT)))
                    .hasSize(2);
        }
    }

    @Test
    void stoppedTaskCancellationAlsoClosesItsQueuedSession()
    {
        AgentRunService agentRuns = mock(AgentRunService.class);
        TestHarness harness = new TestHarness(1, 4, agentRuns);
        Thread thread = thread("thread-stopped-session", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        harness.tasks.setStatus("task-stopped", TaskStatus.PAUSED);

        String turnId = harness.scheduler.enqueueTaskTurn(
                thread, "do not run", "task-stopped", "stage-stopped",
                TurnInitiator.user(), "run-stopped");

        assertThat(session.inputs).isEmpty();
        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                .isEqualTo(CANCELLED);
        verify(agentRuns).transition(
                "run-stopped", AgentRun.STATUS_CANCELLED,
                "cancelled because task is paused");
    }

    @Test
    void terminalLifecyclePhaseSuppressesDispatchDespiteRunnableStatus()
    {
        AgentRunService agentRuns = mock(AgentRunService.class);
        TestHarness harness = new TestHarness(2, 4, agentRuns);
        Thread thread = thread("thread-stopped-phase", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        int index = 0;
        for (TaskPhase phase : List.of(TaskPhase.NEEDS_ATTENTION, TaskPhase.COMPLETED)) {
            String taskId = "task-stopped-phase-" + index;
            String runId = "run-stopped-phase-" + index;
            harness.tasks.setStatus(taskId, TaskStatus.IDLE);
            harness.tasks.setPhase(taskId, phase);
            String turnId = harness.scheduler.enqueueTaskTurn(
                    thread, "do not run " + phase, taskId, "stage-" + index,
                    TurnInitiator.user(), runId);

            assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                    .isEqualTo(CANCELLED);
            verify(agentRuns).transition(
                    runId, AgentRun.STATUS_CANCELLED,
                    "cancelled because task phase is "
                            + phase.name().toLowerCase(Locale.ROOT));
            index++;
        }
        assertThat(session.inputs).isEmpty();
    }

    @Test
    void attendedParkedSteeringCanDispatchWithoutRevivingTheTask()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-parked-question", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        harness.tasks.setStatus("task-parked", TaskStatus.NEEDS_ATTENTION);
        harness.tasks.setPhase("task-parked", TaskPhase.NEEDS_ATTENTION);

        String turnId = harness.scheduler.enqueueTaskTurn(
                thread, "what happened?", "task-parked", "stage-remote",
                TurnInitiator.attended(TurnInitiator.SOURCE_PARKED_STEERING));

        assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(session.inputs).containsExactly("what happened?");
    }

    @Test
    void runnableTaskStatusesStillDispatchNormally()
    {
        TestHarness harness = new TestHarness(6, 4);
        Thread thread = thread("thread-runnable-statuses", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        List<TaskStatus> runnable = List.of(
                TaskStatus.PENDING,
                TaskStatus.RUNNING,
                TaskStatus.IDLE,
                TaskStatus.AWAITING_REVIEW,
                TaskStatus.IN_REVIEW);

        int index = 0;
        for (TaskStatus status : runnable) {
            String taskId = "task-runnable-" + index;
            harness.tasks.setStatus(taskId, status);
            harness.scheduler.enqueueTaskTurn(
                    thread, "run " + status, taskId, "stage-" + index,
                    TurnInitiator.user(), "run-" + index);
            index++;
        }

        assertThat(session.inputs).containsExactlyElementsOf(
                runnable.stream().map(status -> "run " + status).toList());
    }

    @Test
    void cancelledTurnStillAccountsUsageWithoutClosingTheRunAsSuccessful()
    {
        AgentRunService agentRuns = mock(AgentRunService.class);
        SessionBudgetPolicy budgets = mock(SessionBudgetPolicy.class);
        TestHarness harness = new TestHarness(1, 4, agentRuns, budgets);
        Thread thread = thread("thread-cancelled-budget", CLI_AGENT);
        harness.register(thread);

        harness.scheduler.enqueueTaskTurn(
                thread, "cancel", "task-1", "stage-1",
                TurnInitiator.user(), "run-cancelled");
        harness.scheduler.cancelSessionTurns("run-cancelled");

        verify(budgets).account(eq("run-cancelled"), any(), any());
        verify(agentRuns, never()).transition(
                "run-cancelled", AgentRun.STATUS_SUCCEEDED, "scheduler turn completed");
    }

    @Test
    void coordinatorOwnedTurnsLeaveTheirEpisodeRunsOpen()
    {
        AgentRunService agentRuns = mock(AgentRunService.class);
        TestHarness harness = new TestHarness(1, 4, agentRuns);
        Thread thread = thread("thread-ci", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        String stageId = "11111111-1111-1111-1111-111111111111";
        harness.stageStore.stages.put(UUID.fromString(stageId), new StageInstance(
                UUID.fromString(stageId), "task-ci", StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.OPEN, Instant.parse("2026-07-22T00:00:00Z"), null, null));

        for (String source : List.of(
                "ci-fix-shipped", "review-round", "brain-review", "brain-review-fix",
                "branch-guard-fix")) {
            String runId = "run-" + source;
            String turnId = harness.scheduler.enqueueTaskTurn(
                    thread, "coordinator step", "task-ci", stageId,
                    TurnInitiator.unattended(source), runId);
            session.completeNext();

            assertThat(harness.turns.findTurnById(turnId).orElseThrow().status())
                    .isEqualTo(COMPLETED);
            verify(agentRuns).transition(runId, AgentRun.STATUS_RUNNING, "scheduler started");
            verify(agentRuns, never()).transition(
                    eq(runId), eq(AgentRun.STATUS_SUCCEEDED), eq("scheduler turn completed"));
            verify(agentRuns, never()).transition(
                    eq(runId), eq(AgentRun.STATUS_FAILED), any());
        }

        harness.scheduler.enqueueTaskTurn(
                thread, "ordinary step", "task-ci", stageId,
                TurnInitiator.user(), "run-ordinary");
        session.completeNext();
        verify(agentRuns).transition(
                "run-ordinary", AgentRun.STATUS_SUCCEEDED, "scheduler turn completed");
    }

    @Test
    void capsCliTurnsAndQueuesOverflow()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String firstTurn = harness.scheduler.enqueueTurn(first, "first");
        String secondTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(firstSession.inputs).containsExactly("first");
        assertThat(secondSession.inputs).isEmpty();
        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(QUEUED);

        firstSession.completeNext();

        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(COMPLETED);
        assertThat(secondSession.inputs).containsExactly("second");
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    @Test
    void enqueueTaskTurnStampsTheExplicitTaskIdEvenWhenTheActiveProjectionIsNull()
    {
        // thread(...) builds a thread whose activeTask projection is null —
        // the state a task in AWAITING_REVIEW / NEEDS_ATTENTION / phase-
        // COMPLETED presents. The task composer binds the turn to its task
        // by explicit id so the row is NOT recorded as a trunk (task_id =
        // null) turn that would leak into the trunk conversation slice.
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        String turnId = harness.scheduler.enqueueTaskTurn(thread, "steer", "task-42");

        assertThat(harness.turns.findTurnById(turnId).orElseThrow().taskId())
                .isEqualTo("task-42");
    }

    @Test
    void enqueueTaskTurnWithNullTaskIdFallsBackToATrunkTurn()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        String turnId = harness.scheduler.enqueueTaskTurn(thread, "plan", null);

        assertThat(harness.turns.findTurnById(turnId).orElseThrow().taskId()).isNull();
    }

    @Test
    void automatedTrunkTurnRetainsItsUnattendedInitiator()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        String turnId = harness.scheduler.enqueueTrunkTurn(
                thread, "ask for backlog permission",
                TurnInitiator.unattended("quality-scan-backlog-permission"));

        ThreadTurn turn = harness.turns.findTurnById(turnId).orElseThrow();
        assertThat(turn.taskId()).isNull();
        assertThat(turn.initiator()).isEqualTo(
                TurnInitiator.unattended("quality-scan-backlog-permission"));
    }

    @Test
    void codingStageActivatesPonytailAndCavemanWithoutChangingUserInput()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        String stageId = "11111111-1111-1111-1111-111111111111";
        Instant now = Instant.parse("2026-07-10T00:00:00Z");
        harness.stageStore.stages.put(UUID.fromString(stageId), new StageInstance(
                UUID.fromString(stageId), "task-1", StageType.DEVELOPMENT_STAGE,
                StageState.OPEN, now, null, null));

        harness.scheduler.enqueueTaskTurn(
                thread, "implement", "task-1", stageId, TurnInitiator.user());

        assertThat(session.inputs).containsExactly("implement");
        assertThat(session.skillNames).containsExactly(List.of(
                "task-execution", "codegraph-first", "ponytail", CavemanPrompt.NAME));
        assertThat(session.toolNames.getFirst())
                .contains("codegraph_explore", "run_checks")
                .doesNotContain("push", "list_skills", "list_tools", "load_skill");
    }

    @Test
    void apiCodingStageActivatesPonytailAndCavemanWithoutChangingUserInput()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", LOGIC_LOOP);
        RecordingSession session = harness.register(thread);
        String stageId = "11111111-1111-1111-1111-111111111111";
        Instant now = Instant.parse("2026-07-10T00:00:00Z");
        harness.stageStore.stages.put(UUID.fromString(stageId), new StageInstance(
                UUID.fromString(stageId), "task-1", StageType.DEVELOPMENT_STAGE,
                StageState.OPEN, now, null, null));

        harness.scheduler.enqueueTaskTurn(
                thread, "implement", "task-1", stageId, TurnInitiator.user());

        assertThat(session.inputs).containsExactly("implement");
        assertThat(session.skillNames).containsExactly(List.of(
                "task-execution", "codegraph-first", "ponytail", CavemanPrompt.NAME));
        assertThat(session.toolNames.getFirst())
                .contains("codegraph_explore", "run_checks")
                .doesNotContain("push", "list_skills", "list_tools", "load_skill");
    }

    @Test
    void brainThreadTaskTurnRoutesToBrainAgentNotStageAgent()
    {
        // A plan self-review turn runs on the BRAIN_AGENT thread but is
        // stamped with the task id + the PLAN_STAGE's id. That stage has no
        // per-stage CLI agent, so it must route to the brain agent — not the
        // stage-agent path (which rejects PLAN_STAGE and fails the turn).
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("brain-1", ThreadKind.BRAIN_AGENT);
        RecordingSession session = harness.register(thread);
        String stageId = "11111111-1111-1111-1111-111111111111";
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        harness.stageStore.stages.put(UUID.fromString(stageId), new StageInstance(
                UUID.fromString(stageId), "task-1", StageType.PLAN_STAGE,
                StageState.OPEN, now, null, null));

        harness.scheduler.enqueueTaskTurn(
                thread, "self-review the plan", "task-1", stageId,
                TurnInitiator.unattended("brain-plan-self-review"));

        assertThat(harness.registry.lastRouted).isEqualTo("brain");
        assertThat(session.inputs).containsExactly("self-review the plan");
        assertThat(session.skillNames).containsExactly(List.of(
                "codegraph-first", "i-have-adhd", CavemanPrompt.NAME));
        assertThat(session.mcpAgentKeys).containsExactly(stageId);
    }

    @Test
    void everyTrunkTurnActivatesManagedSkillsWithoutChangingUserInput()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        harness.scheduler.enqueueTrunkTurn(thread, "what did you find?");

        assertThat(session.inputs).containsExactly("what did you find?");
        assertThat(session.skillNames).containsExactly(List.of(
                "trunk-planner", "codegraph-first", "i-have-adhd", CavemanPrompt.NAME));
        assertThat(session.toolNames.getFirst())
                .contains("codegraph_explore", "create_task")
                .doesNotContain("run_checks", "push", "list_skills", "list_tools", "load_skill");
    }

    @Test
    void apiLaneRunsWhileCliLaneIsFull()
    {
        TestHarness harness = new TestHarness(1, 1);
        Thread cliFirst = thread("cli-1", CLI_AGENT);
        Thread cliSecond = thread("cli-2", CLI_AGENT);
        Thread apiTask = thread("api-1", LOGIC_LOOP);
        RecordingSession cliFirstSession = harness.register(cliFirst);
        RecordingSession cliSecondSession = harness.register(cliSecond);
        RecordingSession apiSession = harness.register(apiTask);

        harness.scheduler.enqueueTurn(cliFirst, "cli first");
        String cliSecondTurn = harness.scheduler.enqueueTurn(cliSecond, "cli second");
        String apiTurn = harness.scheduler.enqueueTurn(apiTask, "api");

        assertThat(cliFirstSession.inputs).containsExactly("cli first");
        assertThat(cliSecondSession.inputs).isEmpty();
        assertThat(apiSession.inputs).containsExactly("api");
        assertThat(harness.turns.findTurnById(cliSecondTurn).orElseThrow().status())
                .isEqualTo(QUEUED);
        assertThat(harness.turns.findTurnById(apiTurn).orElseThrow().lane())
                .isEqualTo(ThreadResourceLane.API);
    }

    @Test
    void sameTaskTurnsDoNotRunConcurrently()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String firstTurn = harness.scheduler.enqueueTurn(thread, "first");
        String secondTurn = harness.scheduler.enqueueTurn(thread, "second");

        assertThat(session.inputs).containsExactly("first");
        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(QUEUED);

        session.completeNext();

        assertThat(session.inputs).containsExactly("first", "second");
        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(COMPLETED);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    @Test
    void differentTasksOnOneThreadRunConcurrently()
    {
        // Two task turns on one thread now key the run gate by task (the
        // registry stage key), not by thread, so both dispatch at once when
        // the CLI lane has room. This is the intra-thread parallelism the
        // per-stage agent runtime enables.
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String firstTurn = harness.scheduler.enqueueTaskTurn(thread, "first", "task-a");
        String secondTurn = harness.scheduler.enqueueTaskTurn(thread, "second", "task-b");

        assertThat(harness.turns.findTurnById(firstTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(session.inputs).containsExactly("first", "second");
    }

    @Test
    void cancelQueuedTurnsRemovesOnlyQueuedTurnsForTask()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String runningTurn = harness.scheduler.enqueueTurn(first, "first");
        String cancelledTurn = harness.scheduler.enqueueTurn(first, "second");
        String otherTaskTurn = harness.scheduler.enqueueTurn(second, "other");

        assertThat(harness.scheduler.cancelQueuedTurns(first.id())).isEqualTo(1);

        assertThat(harness.turns.findTurnById(runningTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
        assertThat(harness.turns.findTurnById(cancelledTurn).orElseThrow().status())
                .isEqualTo(CANCELLED);
        assertThat(harness.turns.findTurnById(otherTaskTurn).orElseThrow().status())
                .isEqualTo(QUEUED);

        firstSession.completeNext();

        assertThat(firstSession.inputs).containsExactly("first");
        assertThat(secondSession.inputs).containsExactly("other");
        assertThat(harness.turns.findTurnById(runningTurn).orElseThrow().status())
                .isEqualTo(COMPLETED);
        assertThat(harness.turns.findTurnById(otherTaskTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    @Test
    void appendsSchedulerEventsForTurnLifecycle()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String turnId = harness.scheduler.enqueueTurn(thread, "first");
        session.completeNext();

        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .extracting(ThreadTurnEvent::event)
                .containsExactlyInAnyOrder(TURN_FINISHED, TURN_STARTED, TURN_QUEUED);
        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .extracting(ThreadTurnEvent::turnId)
                .containsOnly(turnId);
    }

    @Test
    void persistsCodeGraphPolicyMetricsWithTheCompletedTurn()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-codegraph", CLI_AGENT);
        RecordingSession session = harness.register(thread);

        String turnId = harness.scheduler.enqueueTurn(thread, "find auth flow");
        CodeGraphFirstRuntime.shouldRedirect(thread.id(), thread.id());
        CodeGraphFirstRuntime.markAttempted(thread.id(), thread.id());
        CodeGraphFirstRuntime.markSucceeded(thread.id(), thread.id());
        session.completeNext();

        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(turnId);
                    assertThat(event.event()).isEqualTo(CODEGRAPH_POLICY);
                    assertThat(event.message()).isEqualTo(
                            "{\"redirected\":1,\"attempted\":1,\"succeeded\":1,"
                                    + "\"failed\":0,\"fallback\":0,\"ignored\":0}");
                });
    }

    @Test
    void appendsSchedulerEventWhenQueuedTurnIsCancelled()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        harness.register(first);
        harness.register(second);

        harness.scheduler.enqueueTurn(first, "first");
        String cancelledTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(harness.scheduler.cancelQueuedTurns(second.id())).isEqualTo(1);
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .extracting(ThreadTurnEvent::event)
                .containsExactlyInAnyOrder(TURN_CANCELLED, WAITING_FOR_CAPACITY, TURN_QUEUED);
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .extracting(ThreadTurnEvent::turnId)
                .containsOnly(cancelledTurn);
    }

    @Test
    void appendsWaitingEventWhenLaneIsFull()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        harness.register(first);
        harness.register(second);

        harness.scheduler.enqueueTurn(first, "first");
        String waitingTurn = harness.scheduler.enqueueTurn(second, "second");

        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(waitingTurn);
                    assertThat(event.event()).isEqualTo(WAITING_FOR_CAPACITY);
                    assertThat(event.message()).isEqualTo("waiting for cli lane capacity");
                });
    }

    @Test
    void appendsWaitingEventWhenSameTaskAlreadyHasRunningTurn()
    {
        TestHarness harness = new TestHarness(2, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        harness.register(thread);

        harness.scheduler.enqueueTurn(thread, "first");
        String waitingTurn = harness.scheduler.enqueueTurn(thread, "second");

        assertThat(harness.events.listEventsByTaskId(thread.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(waitingTurn);
                    assertThat(event.event()).isEqualTo(WAITING_FOR_CAPACITY);
                    assertThat(event.message()).isEqualTo("waiting for previous turn for this agent");
                });
    }

    @Test
    void cancelQueuedTurnsPagesThroughAllDurableQueuedTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        String threadId = "thread-1";
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, threadId, now.plusMillis(i)));
        }

        assertThat(harness.scheduler.cancelQueuedTurns(threadId)).isEqualTo(1_001);
        assertThat(harness.turns.turns.values())
                .extracting(ThreadTurn::status)
                .containsOnly(CANCELLED);
    }

    @Test
    void recoveryPagesThroughAllOrphanedRunningTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, thread.id(), RUNNING, now.plusMillis(i)));
        }

        harness.scheduler.recoverQueuedTurns();

        assertThat(session.inputs).containsExactly("input");
        assertThat(harness.turns.turns.values())
                .filteredOn(turn -> turn.status() == RUNNING)
                .hasSize(1);
        assertThat(harness.turns.turns.values())
                .filteredOn(turn -> turn.status() == QUEUED)
                .hasSize(1_000);
        assertThat(harness.events.listEventsByTaskId(thread.id(), 2_100))
                .filteredOn(event -> event.event() == TURN_QUEUED)
                .hasSize(1_001);
    }

    @Test
    void recoveryPagesThroughAllDurableQueuedTurns()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread thread = thread("thread-1", CLI_AGENT);
        RecordingSession session = harness.register(thread);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        for (int i = 0; i < 1_001; i++) {
            harness.turns.saveTurn(turn("turn-" + i, thread.id(), QUEUED, now.plusMillis(i)));
        }

        harness.scheduler.recoverQueuedTurns();

        for (int i = 0; i < 1_001; i++) {
            assertThat(session.inputs).hasSize(i + 1);
            session.completeNext();
        }
        assertThat(harness.turns.turns.values())
                .extracting(ThreadTurn::status)
                .containsOnly(COMPLETED);
    }

    @Test
    void recoveryDoesNotDuplicateWaitingEventForAlreadyKnownQueuedTurn()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        harness.turns.saveTurn(turn("turn-1", first.id(), RUNNING, now));
        harness.turns.saveTurn(turn("turn-2", second.id(), RUNNING, now.plusMillis(1)));

        harness.scheduler.recoverQueuedTurns();

        assertThat(firstSession.inputs).containsExactly("input");
        assertThat(secondSession.inputs).isEmpty();
        assertThat(harness.events.listEventsByTaskId(second.id(), 10))
                .filteredOn(event -> event.event() == WAITING_FOR_CAPACITY)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.turnId()).isEqualTo("turn-2"));
    }

    @Test
    void failedTurnReleasesLane()
    {
        TestHarness harness = new TestHarness(1, 4);
        Thread first = thread("thread-1", CLI_AGENT);
        Thread second = thread("thread-2", CLI_AGENT);
        RecordingSession firstSession = harness.register(first);
        RecordingSession secondSession = harness.register(second);

        String firstTurn = harness.scheduler.enqueueTurn(first, "first");
        String secondTurn = harness.scheduler.enqueueTurn(second, "second");

        firstSession.failNext(new IllegalStateException("boom"));

        ThreadTurn failed = harness.turns.findTurnById(firstTurn).orElseThrow();
        assertThat(failed.status()).isEqualTo(FAILED);
        assertThat(failed.errorMessage()).isEqualTo("boom");
        assertThat(harness.events.listEventsByTaskId(first.id(), 10))
                .anySatisfy(event -> {
                    assertThat(event.turnId()).isEqualTo(firstTurn);
                    assertThat(event.event()).isEqualTo(TURN_FAILED);
                    assertThat(event.message()).isEqualTo("boom");
                });
        assertThat(secondSession.inputs).containsExactly("second");
        assertThat(harness.turns.findTurnById(secondTurn).orElseThrow().status())
                .isEqualTo(RUNNING);
    }

    private static Thread thread(String id, ThreadKind kind)
    {
        Instant now = Instant.parse("2026-05-18T12:00:00Z");
        return new Thread(
                id,
                kind,
                kind == CLI_AGENT ? "claude-code" : "openai",
                /* agentSessionId */ null,
                "Thread " + id,
                ThreadStatus.IDLE,
                "model",
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
    }

    private static final class TestHarness
    {
        private final InMemoryTaskStore threads = new InMemoryTaskStore();
        private final InMemoryTaskTurnStore turns = new InMemoryTaskTurnStore();
        private final InMemoryTaskTurnEventStore events = new InMemoryTaskTurnEventStore();
        private final RecordingRegistry registry = new RecordingRegistry();
        private final StubStageStore stageStore = new StubStageStore();
        private final StubTaskStore tasks = new StubTaskStore();
        private final AgentScheduler scheduler;

        private TestHarness(int maxCliRunning, int maxApiRunning)
        {
            this(maxCliRunning, maxApiRunning, null);
        }

        private TestHarness(int maxCliRunning, int maxApiRunning, AgentRunService agentRuns)
        {
            this(maxCliRunning, maxApiRunning, agentRuns, null);
        }

        private TestHarness(
                int maxCliRunning,
                int maxApiRunning,
                AgentRunService agentRuns,
                SessionBudgetPolicy sessionBudgets)
        {
            scheduler = new AgentScheduler(
                    threads, turns, events, registry, stageStore,
                    tasks, null, null, null, agentRuns, sessionBudgets, null,
                    maxCliRunning, maxApiRunning);
        }

        private RecordingSession register(Thread thread)
        {
            RecordingSession session = new RecordingSession(thread);
            threads.saveThread(thread);
            registry.sessions.put(thread.id(), session);
            return session;
        }
    }

    private static final class RecordingRegistry
            extends ThreadRegistry
    {
        private final Map<String, ThreadAgent> sessions = new LinkedHashMap<>();
        /** Which factory the scheduler routed the last turn through. */
        private String lastRouted;

        private RecordingRegistry()
        {
            super(
                    new InMemoryTaskStore(),
                    new StubTaskStore(),
                    new StreamJsonParser(new ObjectMapper()),
                    new ObjectMapper(),
                    new McpPermissionGate(),
                    Executors.newSingleThreadExecutor(),
                    CheckpointTrigger.NOOP,
                    () -> "",
                    new WorktreeLeaseService(new StubLeaseStore()));
        }

        @Override
        public ThreadAgent getOrCreate(Thread thread)
        {
            ThreadAgent session = sessions.get(thread.id());
            if (session == null) {
                throw new IllegalStateException("no session for " + thread.id());
            }
            return session;
        }

        @Override
        public ThreadAgent getOrCreate(Thread thread, Task task, String stageId)
        {
            // The scheduler tests record one session per thread and don't
            // distinguish per-stage agents — route the per-stage call back
            // to the recorded session.
            return getOrCreate(thread);
        }

        @Override
        public ThreadAgent getOrCreateTrunk(Thread thread)
        {
            // The scheduler tests don't distinguish trunk vs task agents.
            // Both return the recorded session for the thread.
            return getOrCreate(thread);
        }

        @Override
        public Optional<ThreadAgent> findByAgentKey(String threadId, String agentKey)
        {
            return Optional.ofNullable(sessions.get(threadId));
        }

        @Override
        public Optional<ThreadAgent> findTrunk(String threadId)
        {
            return Optional.ofNullable(sessions.get(threadId));
        }

        @Override
        public StageAgent getOrCreateStageAgent(Thread thread, Task task, String stageId)
        {
            lastRouted = "stage";
            return super.getOrCreateStageAgent(thread, task, stageId);
        }

        @Override
        public TaskBrainAgent getOrCreateTaskBrainAgent(Thread thread)
        {
            lastRouted = "brain";
            return super.getOrCreateTaskBrainAgent(thread);
        }

        @Override
        public TrunkAgent getOrCreateTrunkAgent(Thread thread)
        {
            lastRouted = "trunk";
            return super.getOrCreateTrunkAgent(thread);
        }
    }

    private static final class StubStageStore
            implements StageStore
    {
        private final Map<UUID, StageInstance> stages = new LinkedHashMap<>();

        @Override public StageInstance openStage(String taskId, StageType type, UUID callerStageId) { throw new UnsupportedOperationException(); }
        @Override public void closeStage(UUID stageId, String reason) {}
        @Override public void closeStage(UUID stageId, String reason, Map<String, Object> extraPayload) {}
        @Override public StageInstance reopenStage(UUID stageId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StageInstance> findStageById(UUID stageId) { return Optional.ofNullable(stages.get(stageId)); }
        @Override public Optional<String> findMetricsJson(UUID stageId) { return Optional.empty(); }
        @Override public void updateMetricsJson(UUID stageId, String metricsJson) {}
        @Override public void updateWorkModel(UUID stageId, WorkModel workModel) {}
        @Override public List<StageInstance> findStagesByTask(String taskId) { return List.of(); }
        @Override public Optional<StageInstance> findActiveStage(String taskId) { return Optional.empty(); }
        @Override public StageEvent recordEvent(UUID stageId, String taskId, StageEventType type, Map<String, Object> payload) { throw new UnsupportedOperationException(); }
        @Override public Optional<StageEvent> findEventById(UUID eventId) { return Optional.empty(); }
        @Override public void updateEventPayload(UUID eventId, Map<String, Object> payload) {}
        @Override public List<StageEvent> findEventsByStage(UUID stageId) { return List.of(); }
        @Override public List<StageEvent> findRecentEventsByStage(UUID stageId, int limit) { return List.of(); }
        @Override public List<StageEvent> findEventsByTask(String taskId) { return List.of(); }
        @Override public ReviewComment saveReviewComment(ReviewComment comment) { throw new UnsupportedOperationException(); }
        @Override public Optional<ReviewComment> findReviewCommentById(UUID id) { return Optional.empty(); }
        @Override public boolean reviewCommentExistsByRemoteLink(String remoteLink) { return false; }
        @Override public List<ReviewComment> findUnresolvedComments(String taskId) { return List.of(); }
        @Override public List<ReviewComment> findCommentsBySource(String taskId, ReviewCommentSource source) { return List.of(); }
        @Override public List<ReviewComment> findUnroundedRemoteComments(String taskId) { return List.of(); }
        @Override public List<ReviewComment> findCommentsByRound(UUID roundId) { return List.of(); }
        @Override public void assignCommentsToRound(List<UUID> commentIds, UUID roundId) {}
    }

    /** Empty TaskStore — the scheduler tests don't exercise the
     *  per-work-unit storage. Same shape as the one in
     *  TestThreadServiceScheduler. */
    private static final class StubTaskStore
            implements TaskStore
    {
        private final Map<String, TaskStatus> statuses = new LinkedHashMap<>();
        private final Map<String, TaskPhase> phases = new LinkedHashMap<>();

        private void setStatus(String taskId, TaskStatus status)
        {
            statuses.put(taskId, status);
        }

        private void setPhase(String taskId, TaskPhase phase)
        {
            phases.put(taskId, phase);
        }

        @Override public void saveTask(Task task) { statuses.put(task.id(), task.status()); }
        @Override public Optional<Task> findTaskById(String id) {
            Task task = mock(Task.class);
            when(task.status()).thenReturn(statuses.getOrDefault(id, TaskStatus.IDLE));
            when(task.phase()).thenReturn(phases.get(id));
            return Optional.of(task);
        }
        @Override public void deleteTask(String id) {}
        @Override public List<Task> listTasksByThread(String threadId) { return List.of(); }
        @Override public boolean hasActiveTask(String threadId) { return !activeTasksForThread(threadId).isEmpty(); }
        @Override public List<Task> activeTasksForThread(String threadId) { return List.of(); }
        @Override public Optional<Task> findLatestTaskForThread(String threadId) { return Optional.empty(); }
        @Override public Optional<Long> maxSeqForThread(String threadId) { return Optional.empty(); }
        @Override public List<Task> listByStatus(TaskStatus status, int limit) { return List.of(); }
        @Override public List<Task> listWithLinkedPr(int limit) { return List.of(); }
        @Override public List<Task> listByPhases(Collection<TaskPhase> phases, int limit) { return List.of(); }
        @Override public void recordFile(TaskFile file) {}
        @Override public List<TaskFile> listFiles(String taskId) { return List.of(); }
    }

    /** Tiny in-memory WorktreeLeaseStore so the registry constructor's
     *  WorktreeLeaseService dep is satisfied without dragging Spring in. */
    private static final class StubLeaseStore
            implements WorktreeLeaseStore
    {
        private final Map<String, WorktreeLease> leases = new LinkedHashMap<>();
        @Override public void save(WorktreeLease lease) { leases.put(lease.worktreePath(), lease); }
        @Override public Optional<WorktreeLease> findByWorktreePath(String worktreePath) {
            return Optional.ofNullable(leases.get(worktreePath));
        }
        @Override public List<WorktreeLease> listForTask(String taskId) {
            return leases.values().stream().filter(l -> l.taskId().equals(taskId)).toList();
        }
        @Override public List<WorktreeLease> listAll() { return List.copyOf(leases.values()); }
        @Override public void releaseByWorktreePath(String worktreePath) { leases.remove(worktreePath); }
    }

    private static final class InMemoryTaskStore
            implements ThreadStore
    {
        private final Map<String, Thread> threads = new LinkedHashMap<>();

        @Override
        public void saveThread(Thread thread)
        {
            threads.put(thread.id(), thread);
        }

        @Override
        public Optional<Thread> findThreadById(String id)
        {
            return Optional.ofNullable(threads.get(id));
        }

        @Override
        public void deleteThread(String id)
        {
            threads.remove(id);
        }

        @Override
        public List<Thread> listTasksByStatus(ThreadStatus status, int limit)
        {
            return threads.values().stream()
                    .filter(thread -> thread.status() == status)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<Thread> listTasksByIds(Collection<String> ids)
        {
            return threads.values().stream()
                    .filter(thread -> ids.contains(thread.id()))
                    .toList();
        }

        @Override
        public List<Thread> listThreadsUpdatedSince(Instant since)
        {
            return threads.values().stream()
                    .filter(thread -> !thread.updatedAt().isBefore(since))
                    .toList();
        }

        @Override
        public void appendMessage(ThreadMessage message) {}

        @Override
        public List<ThreadMessage> listMessages(String threadId)
        {
            return List.of();
        }

        @Override
        public void recordFile(ThreadFile file) {}

        @Override
        public List<ThreadFile> listFiles(String threadId)
        {
            return List.of();
        }
    }

    private static final class InMemoryTaskTurnStore
            implements ThreadTurnStore
    {
        private final Map<String, ThreadTurn> turns = new LinkedHashMap<>();

        @Override
        public void saveTurn(ThreadTurn turn)
        {
            turns.put(turn.id(), turn);
        }

        @Override
        public Optional<ThreadTurn> findTurnById(String id)
        {
            return Optional.ofNullable(turns.get(id));
        }

        @Override
        public List<ThreadTurn> listTurnsByStatus(ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByStatusAfter(ThreadTurnStatus status, Instant createdAfter, String idAfter, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.status() == status)
                    .filter(turn -> turn.createdAt().compareTo(createdAfter) > 0
                            || (turn.createdAt().equals(createdAfter) && turn.id().compareTo(idAfter) > 0))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByStatuses(Collection<ThreadTurnStatus> statuses, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> statuses.contains(turn.status()))
                    .sorted(turnOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByTaskIdAndStatus(String threadId, ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.threadId().equals(threadId))
                    .filter(turn -> turn.status() == status)
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByTaskId(String threadId, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> turn.threadId().equals(threadId))
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByAgentRunId(String agentRunId, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> agentRunId.equals(turn.agentRunId()))
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<ThreadTurn> listTurnsByExactTaskIdAndStatus(
                String taskId, ThreadTurnStatus status, int limit)
        {
            return turns.values().stream()
                    .filter(turn -> taskId.equals(turn.taskId()))
                    .filter(turn -> turn.status() == status)
                    .sorted(threadHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<ThreadTurn> turnOrder()
        {
            return Comparator.comparing(ThreadTurn::createdAt)
                    .thenComparing(ThreadTurn::id);
        }

        private static Comparator<ThreadTurn> threadHistoryOrder()
        {
            return Comparator.comparing(ThreadTurn::createdAt)
                    .thenComparing(ThreadTurn::id)
                    .reversed();
        }
    }

    private static final class InMemoryTaskTurnEventStore
            implements ThreadTurnEventStore
    {
        private final Map<String, ThreadTurnEvent> events = new LinkedHashMap<>();

        @Override
        public void appendEvent(ThreadTurnEvent event)
        {
            events.put(event.id(), event);
        }

        @Override
        public List<ThreadTurnEvent> listEventsByTaskId(String threadId, int limit)
        {
            return events.values().stream()
                    .filter(event -> event.threadId().equals(threadId))
                    .sorted(eventHistoryOrder())
                    .limit(limit)
                    .toList();
        }

        private static Comparator<ThreadTurnEvent> eventHistoryOrder()
        {
            return Comparator.comparing(ThreadTurnEvent::createdAt)
                    .thenComparing(ThreadTurnEvent::id)
                    .reversed();
        }
    }

    private static ThreadTurn turn(String id, String threadId, Instant createdAt)
    {
        return turn(id, threadId, QUEUED, createdAt);
    }

    private static ThreadTurn turn(String id, String threadId, ThreadTurnStatus status, Instant createdAt)
    {
        return new ThreadTurn(
                id,
                threadId,
                /* taskId */ null,
                ThreadResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                /* startedAt */ null,
                /* finishedAt */ null,
                /* errorMessage */ null,
                TurnInitiator.user());
    }

    private static ThreadTurn taskTurn(
            String id,
            String threadId,
            String taskId,
            ThreadTurnStatus status,
            String agentRunId,
            Instant createdAt)
    {
        return new ThreadTurn(
                id,
                threadId,
                taskId,
                ThreadResourceLane.CLI,
                status,
                "input",
                createdAt,
                createdAt,
                status == RUNNING ? createdAt : null,
                /* finishedAt */ null,
                /* errorMessage */ null,
                TurnInitiator.user(),
                "stage-1",
                ThreadScope.STAGE,
                agentRunId);
    }

    private static final class RecordingSession
            implements ThreadAgent
    {
        private final Thread thread;
        private final List<String> inputs = new ArrayList<>();
        private final List<List<String>> skillNames = new ArrayList<>();
        private final List<Set<String>> toolNames = new ArrayList<>();
        private final List<String> mcpAgentKeys = new ArrayList<>();
        private final List<String> activeTaskIds = new ArrayList<>();
        private final List<String> activeStageIds = new ArrayList<>();
        private final ArrayDeque<CompletableFuture<Void>> completions = new ArrayDeque<>();
        private final CountDownLatch firstSend = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch statusReadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseStatusRead = new CountDownLatch(1);
        private ThreadStatus status = ThreadStatus.IDLE;
        private volatile boolean blockStatusRead;
        private int interrupts;

        private RecordingSession(Thread thread)
        {
            this.thread = thread;
        }

        @Override
        public String id()
        {
            return thread.id();
        }

        @Override
        public ThreadKind kind()
        {
            return thread.kind();
        }

        @Override
        public String provider()
        {
            return thread.provider();
        }

        @Override
        public String model()
        {
            return thread.model();
        }

        @Override
        public String workingDir()
        {
            return null;
        }

        @Override
        public String branchName()
        {
            return null;
        }

        @Override
        public ThreadStatus status()
        {
            if (blockStatusRead) {
                statusReadStarted.countDown();
                try {
                    releaseStatusRead.await();
                }
                catch (InterruptedException e) {
                    java.lang.Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted waiting to read test status", e);
                }
                blockStatusRead = false;
            }
            return status;
        }

        @Override
        public AgentMetrics metrics()
        {
            return new AgentMetrics(0, 0, 0, 0, 0, 0);
        }

        @Override
        public List<ThreadMessage> history()
        {
            return List.of();
        }

        @Override
        public CompletionStage<Void> send(String userInput)
        {
            inputs.add(userInput);
            firstSend.countDown();
            status = ThreadStatus.RUNNING;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            completions.add(completion);
            return completion;
        }

        @Override
        public void setActiveManagedSkillNames(List<String> names)
        {
            skillNames.add(names == null ? List.of() : List.copyOf(names));
        }

        @Override
        public void setActiveToolNames(Set<String> names)
        {
            toolNames.add(names == null ? Set.of() : Set.copyOf(names));
        }

        @Override
        public void setMcpAgentKey(String agentKey)
        {
            mcpAgentKeys.add(agentKey);
        }

        @Override
        public void setActiveTask(String taskId)
        {
            activeTaskIds.add(taskId);
        }

        @Override
        public void setActiveStage(String stageId)
        {
            activeStageIds.add(stageId);
        }

        private void completeNext()
        {
            status = ThreadStatus.IDLE;
            completions.removeFirst().complete(null);
        }

        private void failNext(RuntimeException failure)
        {
            status = ThreadStatus.ERRORED;
            completions.removeFirst().completeExceptionally(failure);
        }

        private void blockNextStatusRead()
        {
            blockStatusRead = true;
        }

        @Override
        public void interrupt()
        {
            interrupts++;
            status = ThreadStatus.IDLE;
            CompletableFuture<Void> completion = completions.pollFirst();
            if (completion != null) {
                completion.complete(null);
            }
            interrupted.countDown();
        }

        @Override
        public void resume() {}

        @Override
        public void stop() {}

        @Override
        public void notifyPermissionRequested(String callId, String toolName, String summary) {}

        @Override
        public boolean decide(String callId, PermissionDecision decision)
        {
            return false;
        }

        @Override
        public void grantToolBudget(String toolName, int count) {}

        @Override
        public OptionalInt tryConsumeToolBudget(String toolName)
        {
            return OptionalInt.empty();
        }

        @Override
        public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining) {}

        @Override
        public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
        {
            return () -> {};
        }
    }
}
