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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.ValidationExecutorRegistry;
import com.bytequay.app.service.checks.ValidationPassFinishedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskPhaseMachine
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    // Default mock: tryFix returns false, so a failed validation still parks —
    // exactly the pre-fix-loop behaviour these tests assert.
    private final LocalCiFixExecutor localCiFix = mock(LocalCiFixExecutor.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ValidationPassStore validationStore = mock(ValidationPassStore.class);
    private final ValidationExecutorRegistry validationExecutors =
            mock(ValidationExecutorRegistry.class);
    private final CodeFingerprints fingerprints = mock(CodeFingerprints.class);
    private final PlatformTransactionManager transactionManager = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
    private final TaskPhaseMachine machine =
            new TaskPhaseMachine(
                    taskStore, notifications, events, localCiFix, turnStore,
                    validationStore, validationExecutors, fingerprints, commands);

    @Test
    void everyStoppedStatusKeepsFinishedValidationEvidenceUnconsumed()
    {
        for (TaskStatus status : List.of(
                TaskStatus.PAUSED, TaskStatus.NEEDS_ATTENTION,
                TaskStatus.ARCHIVED, TaskStatus.ERRORED,
                TaskStatus.COMPLETED, TaskStatus.REMOTE_CLOSED,
                TaskStatus.CANCELED)) {
            when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                    taskAt("t1", TaskPhase.VALIDATING).withStatus(status)));
            machine.onValidationFinished(new ValidationPassFinishedEvent(
                    "t1", true, List.of()));
        }

        verify(taskStore, never()).updatePhase(eq("t1"), eq(TaskPhase.INTERNAL_REVIEW));
        verify(localCiFix, never()).closeIfGreenInCommand("t1");
    }

    @Test
    void runnableTaskConsumesValidationInsideItsCommand()
    {
        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(taskAt("t1", TaskPhase.VALIDATING)));

        machine.onValidationFinished(new ValidationPassFinishedEvent(
                "t1", true, List.of()));

        verify(localCiFix).closeIfGreenInCommand("t1");
        verify(taskStore).updatePhase("t1", TaskPhase.INTERNAL_REVIEW);
    }

    @Test
    void legalForwardTransitionPersistsAuditsAndPublishes()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        machine.transition("t1", TaskPhase.VALIDATING, "ready_for_checks", Actor.AGENT);

        verify(taskStore).updatePhase("t1", TaskPhase.VALIDATING);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.IMPLEMENTING),
                eq(TaskPhase.VALIDATING), any(), eq("ready_for_checks"), eq(Actor.AGENT));
        verify(events).publishEvent(any(TaskPhaseTransitionedEvent.class));
    }

    @Test
    void legacyStateMachineRejectsAV2TaskBeforeAnyWrite()
    {
        when(taskStore.isV2Task("v2-task")).thenReturn(true);

        assertThatThrownBy(() -> machine.transition(
                "v2-task", TaskPhase.VALIDATING, "misrouted", Actor.AGENT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("owned by TaskManager");

        verify(taskStore, never()).findTaskById("v2-task");
        verify(taskStore, never()).updatePhase(eq("v2-task"), any());
    }

    @Test
    void publicTransitionRejectsAnAmbientTransactionAndInCommandRequiresItsOwner()
    {
        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                assertThatThrownBy(() -> machine.transition(
                        "t1", TaskPhase.VALIDATING, "nested", Actor.AGENT))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ambient transaction"));

        assertThatThrownBy(() -> machine.transitionInCommand(
                "t1", TaskPhase.VALIDATING, "missing_owner", Actor.AGENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active task command");
    }

    @Test
    void illegalForwardTransitionThrowsAndPersistsNothing()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        assertThatThrownBy(() ->
                machine.transition("t1", TaskPhase.AWAITING_PUSH, "skip", Actor.AGENT))
                .isInstanceOf(ResponseStatusException.class);

        verify(taskStore, never()).updatePhase(any(), any());
    }

    @Test
    void needsAttentionAndCompletedAreReachableFromAnyPhase()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.PUSHED_AWAITING_CI)));
        machine.transition("t1", TaskPhase.NEEDS_ATTENTION, "stuck", Actor.HUMAN);
        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);
        verify(taskStore).updateStatusIf("t1", TaskStatus.RUNNING, TaskStatus.NEEDS_ATTENTION);

        when(taskStore.findTaskById("t2")).thenReturn(Optional.of(taskAt("t2", TaskPhase.IMPLEMENTING)));
        machine.transition("t2", TaskPhase.COMPLETED, "closed", Actor.WEBHOOK);
        verify(taskStore).updatePhase("t2", TaskPhase.COMPLETED);
    }

    @Test
    void parkOperationalCheckpointsPhaseAndParksBothAxes()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.VALIDATING)));
        when(taskStore.recoveryPhase("t1")).thenReturn(Optional.empty());

        machine.parkOperational("t1", Actor.AGENT, "validation_failed");

        verify(taskStore).checkpointRecovery(
                "t1", TaskPhase.VALIDATING, "{\"reason\":\"validation_failed\"}");
        verify(taskStore).updateStatusIf("t1", TaskStatus.RUNNING, TaskStatus.NEEDS_ATTENTION);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.RUNNING), eq(TaskStatus.NEEDS_ATTENTION),
                eq(Actor.AGENT), eq("validation_failed"), any());
        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.VALIDATING),
                eq(TaskPhase.NEEDS_ATTENTION), any(), eq("validation_failed"), eq(Actor.AGENT));
    }

    @Test
    void repeatedParkRepairsStatusWithoutClobberingTheCheckpoint()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));

        machine.parkOperational("t1", Actor.AGENT, "still_stuck");

        verify(taskStore, never()).checkpointRecovery(any(), any(), any());
        verify(taskStore, never()).updatePhase(any(), any());
        verify(taskStore).updateStatusIf("t1", TaskStatus.RUNNING, TaskStatus.NEEDS_ATTENTION);
    }

    @Test
    void requestRecoveryIsIdempotentPerKindAndRequiresAPark()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.empty());

        machine.requestRecovery("t1", TaskRecoveryRequest.KIND_NORMAL);
        verify(taskStore).recordRecoveryRequest(
                eq("t1"), any(), eq(TaskRecoveryRequest.KIND_NORMAL), eq(null), any());

        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.of(new TaskRecoveryRequest(
                "req-1", TaskRecoveryRequest.KIND_NORMAL, null, Instant.parse("2026-06-15T12:00:00Z"))));
        machine.requestRecovery("t1", TaskRecoveryRequest.KIND_NORMAL);
        verify(taskStore, times(1)).recordRecoveryRequest(any(), any(), any(), any(), any());

        when(taskStore.findTaskById("t2")).thenReturn(Optional.of(taskAt("t2", TaskPhase.IMPLEMENTING)));
        assertThatThrownBy(() -> machine.requestRecovery("t2", TaskRecoveryRequest.KIND_NORMAL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not parked");
    }

    @Test
    void externalSagaRecoveryKeepsItsExactPayloadAndRequiresItsNamedCompletion()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.empty());

        commands.executeVoid("t1", () -> machine.requestRecoveryInCommand(
                "t1", TaskRecoveryRequest.KIND_EXTERNAL_SAGA, "{\"token\":\"push-1\"}"));

        verify(taskStore).recordRecoveryRequest(
                eq("t1"), any(), eq(TaskRecoveryRequest.KIND_EXTERNAL_SAGA),
                eq("{\"token\":\"push-1\"}"), any());

        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.of(new TaskRecoveryRequest(
                "req-1", TaskRecoveryRequest.KIND_EXTERNAL_SAGA,
                "{\"token\":\"push-1\"}", Instant.parse("2026-06-15T12:00:00Z"))));
        when(taskStore.recoveryPhase("t1")).thenReturn(Optional.of(TaskPhase.AWAITING_PUSH));

        assertThatThrownBy(() -> machine.completeRecovery(
                "t1", Actor.HUMAN, "user_resumed_task", TaskPhase.IMPLEMENTING))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("external-saga recovery completion");

        Task recovered = commands.execute("t1", () ->
                machine.completeExternalSagaRecoveryInCommand(
                        "t1", Actor.HUMAN, "external_saga_recovered",
                        TaskPhase.IMPLEMENTING));
        assertThat(recovered.phase()).isEqualTo(TaskPhase.AWAITING_PUSH);
        assertThat(recovered.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
    }

    @Test
    void rejectedRecoveryClearsOnlyTheExactRequestAndKeepsTheTaskParked()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        TaskRecoveryRequest request = new TaskRecoveryRequest(
                "req-1", TaskRecoveryRequest.KIND_EXTERNAL_SAGA,
                "{\"token\":\"push-1\"}", Instant.parse("2026-06-15T12:00:00Z"));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.of(request));
        when(taskStore.clearRecoveryRequest(eq("t1"), eq("req-1"), any()))
                .thenReturn(true);

        commands.executeVoid("t1", () -> machine.rejectRecoveryRequestInCommand(
                "t1", "req-1", "external_saga_authorization_missing"));

        verify(taskStore).clearRecoveryRequest(
                eq("t1"), eq("req-1"), argThat((String context) ->
                        context.contains("external_saga_authorization_missing")
                                && context.contains("req-1")));
        verify(taskStore, never()).clearRecoveryState(anyString());
        verify(taskStore, never()).updatePhase(anyString(), any());
        verify(taskStore, never()).updateStatusIf(anyString(), any(), any());
    }

    @Test
    void completeRecoveryRestoresTheCheckpointedPhaseAndDerivedStatus()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryPhase("t1")).thenReturn(Optional.of(TaskPhase.AWAITING_REMOTE_REVIEW));

        Task recovered = machine.completeRecovery(
                "t1", Actor.HUMAN, "user_resumed_task", TaskPhase.IMPLEMENTING);

        assertThat(recovered.phase()).isEqualTo(TaskPhase.AWAITING_REMOTE_REVIEW);
        assertThat(recovered.status()).isEqualTo(TaskStatus.IN_REVIEW);
        verify(taskStore).clearRecoveryState("t1");
        verify(taskStore).updateRuntimeFailure("t1", null, null);
        verify(taskStore).updatePhase("t1", TaskPhase.AWAITING_REMOTE_REVIEW);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.NEEDS_ATTENTION),
                eq(TaskPhase.AWAITING_REMOTE_REVIEW), any(), eq("user_resumed_task"), eq(Actor.HUMAN));
        verify(taskStore).updateStatusIf("t1", TaskStatus.NEEDS_ATTENTION, TaskStatus.IN_REVIEW);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.NEEDS_ATTENTION), eq(TaskStatus.IN_REVIEW),
                eq(Actor.HUMAN), eq("user_resumed_task"), any());
    }

    @Test
    void completeRecoveryFallsBackToTheServerDerivedPhaseWithoutACheckpoint()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryPhase("t1")).thenReturn(Optional.empty());

        Task recovered = machine.completeRecovery(
                "t1", Actor.HUMAN, "user_resumed_task", TaskPhase.IMPLEMENTING);

        assertThat(recovered.phase()).isEqualTo(TaskPhase.IMPLEMENTING);
        assertThat(recovered.status()).isEqualTo(TaskStatus.IDLE);
    }

    @Test
    void recoveryClearsTheTerminalPreParkLivenessPointer()
    {
        Task parked = taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        ThreadTurn cancelled = mock(ThreadTurn.class);
        when(cancelled.status()).thenReturn(ThreadTurnStatus.CANCELLED);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryPhase("t1")).thenReturn(Optional.of(TaskPhase.IMPLEMENTING));
        when(taskStore.currentLivenessTurnId("t1")).thenReturn(Optional.of("turn-old"));
        when(turnStore.findTurnById("turn-old")).thenReturn(Optional.of(cancelled));
        when(taskStore.setCurrentLivenessTurnIdIf("t1", "turn-old", null)).thenReturn(true);

        machine.completeRecovery("t1", Actor.HUMAN, "user_resumed_task", TaskPhase.IMPLEMENTING);

        verify(taskStore).setCurrentLivenessTurnIdIf("t1", "turn-old", null);
    }

    @Test
    void completedIsTerminal()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.COMPLETED)));
        assertThatThrownBy(() ->
                machine.transition("t1", TaskPhase.IMPLEMENTING, "reopen", Actor.HUMAN))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void autoPushUnderCapProceedsAndBumpsTheStreak()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(2);

        machine.transition("t1", TaskPhase.PUSHED_AWAITING_CI, "auto_push", Actor.AGENT);

        verify(taskStore).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(taskStore).setConsecutiveAutoPushes("t1", 3);
    }

    @Test
    void humanPushResetsTheStreak()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(4);

        machine.transition("t1", TaskPhase.PUSHED_AWAITING_CI, "human_approved", Actor.HUMAN);

        verify(taskStore).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(taskStore).setConsecutiveAutoPushes("t1", 0);
    }

    @Test
    void staleLocalShipReturnsToValidationAndReleasesTheHumanGate()
    {
        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)
                        .withStatus(TaskStatus.AWAITING_REVIEW)));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE)).thenReturn(true);

        commands.executeVoid("t1", () -> machine.invalidateLocalShipInCommand(
                "t1", Actor.AGENT, "local_push_fingerprint_changed"));

        verify(taskStore).updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE);
        verify(taskStore).updatePhase("t1", TaskPhase.VALIDATING);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.AWAITING_PUSH),
                eq(TaskPhase.VALIDATING), any(),
                eq("local_push_fingerprint_changed"), eq(Actor.AGENT));
    }

    @Test
    void autoPushAtCapParksAtNeedsAttentionInsteadOfPushing()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(TaskPhaseMachine.DEFAULT_AUTO_PUSH_CAP);

        machine.transition("t1", TaskPhase.PUSHED_AWAITING_CI, "auto_push", Actor.SCHEDULER);

        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);
        verify(taskStore, never()).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(notifications).notifyNeedsAttention(eq("thread-1"), eq("t1"), any());
    }

    @Test
    void observedStateCannotUnparkNeedsAttentionButCanCompleteIt()
    {
        Task parkedPhase = taskAt("t1", TaskPhase.NEEDS_ATTENTION);
        Task parkedStatus = taskAt("t2", TaskPhase.AWAITING_REMOTE_REVIEW)
                .withStatus(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parkedPhase));
        when(taskStore.findTaskById("t2")).thenReturn(Optional.of(parkedStatus));

        machine.observe("t1", TaskPhase.AWAITING_REMOTE_REVIEW, "pr_state_observed");
        machine.observe("t2", TaskPhase.PUSHED_AWAITING_CI, "pr_state_observed");

        verify(taskStore, never()).updatePhase("t1", TaskPhase.AWAITING_REMOTE_REVIEW);
        verify(taskStore, never()).updatePhase("t2", TaskPhase.PUSHED_AWAITING_CI);

        machine.observe("t1", TaskPhase.COMPLETED, "pr_merged_observed");

        verify(taskStore).updatePhase("t1", TaskPhase.COMPLETED);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.NEEDS_ATTENTION),
                eq(TaskPhase.COMPLETED), any(), eq("pr_merged_observed"), eq(Actor.WEBHOOK));
    }

    @Test
    void observedNeedsAttentionWritesBothAxes()
    {
        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_REMOTE_REVIEW)));

        machine.observe("t1", TaskPhase.NEEDS_ATTENTION, "merge_queue_failed");

        verify(taskStore).checkpointRecovery(
                "t1", TaskPhase.AWAITING_REMOTE_REVIEW, "{\"reason\":\"merge_queue_failed\"}");
        verify(taskStore).updateStatusIf("t1", TaskStatus.RUNNING, TaskStatus.NEEDS_ATTENTION);
        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.AWAITING_REMOTE_REVIEW),
                eq(TaskPhase.NEEDS_ATTENTION), any(), eq("merge_queue_failed"), eq(Actor.WEBHOOK));
    }

    @Test
    void repeatedObservedNeedsAttentionRepairsMissingStatusWithoutDuplicatePhaseEvent()
    {
        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(taskAt("t1", TaskPhase.NEEDS_ATTENTION)));

        machine.observe("t1", TaskPhase.NEEDS_ATTENTION, "repair_park");

        verify(taskStore).updateStatusIf("t1", TaskStatus.RUNNING, TaskStatus.NEEDS_ATTENTION);
        verify(taskStore, never()).checkpointRecovery(any(), any(), any());
        verify(taskStore, never()).updatePhase(any(), any());
        verify(taskStore, never()).appendPhaseEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void taskLifecycleLockSerializesTheSameTask()
            throws Exception
    {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        Thread first = Thread.startVirtualThread(() ->
                TaskPhaseMachine.withTaskLock("t1", () -> {
                    firstEntered.countDown();
                    await(releaseFirst);
                    return null;
                }));
        assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.startVirtualThread(() -> {
            secondStarted.countDown();
            TaskPhaseMachine.withTaskLock("t1", () -> {
                secondEntered.countDown();
                return null;
            });
        });

        try {
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
        }
        finally {
            releaseFirst.countDown();
        }
        assertThat(secondEntered.await(1, TimeUnit.SECONDS)).isTrue();
        first.join();
        second.join();
    }

    private static void await(CountDownLatch latch)
    {
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void finishTerminalWritesStatusAuditAndDrivesPhaseCompleted()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        machine.finishTerminal("t1", TaskStatus.CANCELED, Actor.HUMAN, "task_cancelled");

        verify(taskStore).cancelTask(eq("t1"), any());
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.RUNNING), eq(TaskStatus.CANCELED),
                eq(Actor.HUMAN), eq("task_cancelled"), any());
        verify(taskStore).updatePhase("t1", TaskPhase.COMPLETED);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.IMPLEMENTING),
                eq(TaskPhase.COMPLETED), any(), eq("task_cancelled"), eq(Actor.HUMAN));
        verify(events).publishEvent(argThat((Object event) -> event instanceof TaskTerminalSealingEvent sealing
                && sealing.taskId().equals("t1")
                && sealing.reason().equals("task_cancelled")));
    }

    @Test
    void finishTerminalIsIdempotentOnAnAlreadyTerminalTask()
    {
        Task done = new Task(
                "t1", "thread-1", 1L, TaskStatus.CANCELED,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                Instant.parse("2026-06-15T12:00:00Z"), null, null, null, null, null,
                null, TaskPhase.COMPLETED, null, 0, null);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(done));

        machine.finishTerminal("t1", TaskStatus.CANCELED, Actor.HUMAN, "task_cancelled");

        verify(taskStore, never()).cancelTask(any(), any());
        verify(taskStore, never()).updatePhase(any(), any());
        verify(taskStore, never()).appendStatusEvent(any(), any(), any(), any(), any(), any());
        verify(events).publishEvent(argThat((Object event) -> event instanceof TaskTerminalSealingEvent sealing
                && sealing.taskId().equals("t1")));
    }

    @Test
    void pauseCheckpointsStatusClearsPidAndAudits()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        Task paused = machine.pause("t1", Actor.HUMAN, "user_paused_task");

        assertThat(paused.status()).isEqualTo(TaskStatus.PAUSED);
        verify(taskStore).checkpointPause("t1", TaskStatus.RUNNING);
        verify(taskStore).clearProcessPid("t1");
        verify(taskStore).updateStatusIf("t1", TaskStatus.RUNNING, TaskStatus.PAUSED);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.RUNNING), eq(TaskStatus.PAUSED),
                eq(Actor.HUMAN), eq("user_paused_task"), any());
    }

    @Test
    void pauseIsIdempotentAndRejectsStoppedStatuses()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.PAUSED)));
        assertThat(machine.pause("t1", Actor.HUMAN, "again").status()).isEqualTo(TaskStatus.PAUSED);
        verify(taskStore, never()).checkpointPause(any(), any());

        when(taskStore.findTaskById("t2")).thenReturn(
                Optional.of(taskAt("t2", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.NEEDS_ATTENTION)));
        assertThatThrownBy(() -> machine.pause("t2", Actor.HUMAN, "nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot be paused");
    }

    @Test
    void requestResumeRequiresPausedAndRecordsTheRequest()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.PAUSED)));

        machine.requestResume("t1");

        verify(taskStore).requestResume(eq("t1"), any());

        when(taskStore.findTaskById("t2")).thenReturn(Optional.of(taskAt("t2", TaskPhase.IMPLEMENTING)));
        assertThatThrownBy(() -> machine.requestResume("t2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not paused");
    }

    @Test
    void completeResumeDerivesTheSafeStatusFromPhase()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH).withStatus(TaskStatus.PAUSED)));

        Task resumed = machine.completeResume("t1", Actor.HUMAN, "user_resumed_task");

        assertThat(resumed.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
        verify(taskStore).clearPauseCheckpoint("t1");
        verify(taskStore).updateRuntimeFailure("t1", null, null);
        verify(taskStore).updateStatusIf("t1", TaskStatus.PAUSED, TaskStatus.AWAITING_REVIEW);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.PAUSED), eq(TaskStatus.AWAITING_REVIEW),
                eq(Actor.HUMAN), eq("user_resumed_task"), any());
        assertThat(TaskPhaseMachine.resumedStatus(TaskPhase.PUSHED_AWAITING_CI))
                .isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(TaskPhaseMachine.resumedStatus(TaskPhase.IMPLEMENTING))
                .isEqualTo(TaskStatus.IDLE);
    }

    @Test
    void completeResumeRefusesWhilePrePauseTurnsAreLive()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.PAUSED)));
        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of(mock(ThreadTurn.class)));

        assertThatThrownBy(() -> machine.completeResume("t1", Actor.HUMAN, "user_resumed_task"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("live pre-pause turns");
        verify(taskStore, never()).updateStatusIf(any(), any(), any());
    }

    @Test
    void resumeAndRecoveryRefuseLiveValidationOwners()
    {
        ValidationClaim live = mock(ValidationClaim.class);
        when(live.claimKey()).thenReturn("claim-live");
        when(live.leaseUntil()).thenReturn(Instant.now().plusSeconds(60));
        when(validationStore.findOpenByTask("t1")).thenReturn(List.of(live));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.PAUSED)));

        assertThatThrownBy(() -> machine.completeResume(
                "t1", Actor.HUMAN, "user_resumed_task"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("live validation");

        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.NEEDS_ATTENTION)
                        .withStatus(TaskStatus.NEEDS_ATTENTION)));
        assertThatThrownBy(() -> machine.completeRecovery(
                "t1", Actor.HUMAN, "user_resumed_task", TaskPhase.IMPLEMENTING))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("live validation");
    }

    @Test
    void resumeClearsTheTerminalPrePauseLivenessPointer()
    {
        Task paused = taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.PAUSED);
        ThreadTurn cancelled = mock(ThreadTurn.class);
        when(cancelled.status()).thenReturn(ThreadTurnStatus.CANCELLED);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(paused));
        when(taskStore.currentLivenessTurnId("t1")).thenReturn(Optional.of("turn-old"));
        when(turnStore.findTurnById("turn-old")).thenReturn(Optional.of(cancelled));
        when(taskStore.setCurrentLivenessTurnIdIf("t1", "turn-old", null)).thenReturn(true);

        machine.completeResume("t1", Actor.HUMAN, "user_resumed_task");

        verify(taskStore).setCurrentLivenessTurnIdIf("t1", "turn-old", null);
    }

    @Test
    void retryErroredRequiresTheExactCurrentFailureAndMovesToIdle()
    {
        Task errored = taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.ERRORED);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(errored));
        when(taskStore.currentLivenessTurnId("t1")).thenReturn(Optional.of("turn-9"));
        ThreadTurn failed = mock(ThreadTurn.class);
        when(failed.status()).thenReturn(ThreadTurnStatus.FAILED);
        when(turnStore.findTurnById("turn-9")).thenReturn(Optional.of(failed));

        assertThat(machine.retryErrored("t1", "turn-9")).isSameAs(failed);

        verify(taskStore).updateStatusIf("t1", TaskStatus.ERRORED, TaskStatus.IDLE);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.ERRORED), eq(TaskStatus.IDLE),
                eq(Actor.HUMAN), eq("task_retry"), any());
        verify(taskStore).updateRuntimeFailure("t1", null, null);

        // A superseded pointer refuses the retry.
        when(taskStore.currentLivenessTurnId("t1")).thenReturn(Optional.of("turn-10"));
        assertThatThrownBy(() -> machine.retryErrored("t1", "turn-9"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no longer");
    }

    @Test
    void localReviewIntentsUseGuardedStatusCasAndAuditEveryMove()
    {
        Task running = taskAt("t1", TaskPhase.IMPLEMENTING);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(running));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.RUNNING, TaskStatus.AWAITING_REVIEW)).thenReturn(true);

        Task parked = commands.execute("t1", () -> machine.parkForLocalReviewInCommand(
                "t1", Actor.AGENT, "publish_proposal_parked"));

        assertThat(parked.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.RUNNING), eq(TaskStatus.AWAITING_REVIEW),
                eq(Actor.AGENT), eq("publish_proposal_parked"), any());

        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(parked.withProcessPid(123)));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IDLE)).thenReturn(true);

        Task resumed = commands.execute("t1", () -> machine.resumeFromLocalReviewInCommand(
                "t1", Actor.HUMAN, "publish_proposal_discarded"));

        assertThat(resumed.status()).isEqualTo(TaskStatus.IDLE);
        assertThat(resumed.processPid()).isNull();
        verify(taskStore).clearProcessPid("t1");
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.AWAITING_REVIEW), eq(TaskStatus.IDLE),
                eq(Actor.HUMAN), eq("publish_proposal_discarded"), any());
    }

    @Test
    void linkedLocalReviewResolutionRestoresRemoteStatusWithoutMovingPhase()
    {
        Task linked = taskAt("t1", TaskPhase.AWAITING_REMOTE_REVIEW)
                .withStatus(TaskStatus.AWAITING_REVIEW)
                .withLinkedPrNumber(42);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(linked));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IN_REVIEW)).thenReturn(true);

        Task restored = commands.execute("t1", () -> machine.markRemoteInReviewInCommand(
                "t1", Actor.HUMAN, "publish_proposal_approved"));

        assertThat(restored.status()).isEqualTo(TaskStatus.IN_REVIEW);
        assertThat(restored.phase()).isEqualTo(TaskPhase.AWAITING_REMOTE_REVIEW);
        verify(taskStore, never()).updatePhase(anyString(), any());
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.AWAITING_REVIEW), eq(TaskStatus.IN_REVIEW),
                eq(Actor.HUMAN), eq("publish_proposal_approved"), any());
    }

    @Test
    void idleRuntimeResumeCannotBypassErroredRetryOrStoppedGuards()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.IDLE)));

        Task idle = commands.execute("t1", () -> machine.resumeIdleRuntimeInCommand("t1"));

        assertThat(idle.status()).isEqualTo(TaskStatus.IDLE);
        verify(taskStore).updateRuntimeFailure("t1", null, null);
        verify(taskStore, never()).updateStatusIf(
                eq("t1"), eq(TaskStatus.ERRORED), eq(TaskStatus.IDLE));

        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.ERRORED)));
        assertThatThrownBy(() -> commands.execute(
                "t1", () -> machine.resumeIdleRuntimeInCommand("t1")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no idle runtime");
    }

    @Test
    void archiveIdleRefusesLiveWorkAndPendingRequests()
    {
        Task idle = taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.IDLE);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(idle));
        when(taskStore.resumeRequestedAt("t1")).thenReturn(Optional.empty());
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.empty());
        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of(mock(ThreadTurn.class)));

        machine.archiveIdle("t1");
        verify(taskStore, never()).updateStatusIf(any(), any(), any());

        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of());
        machine.archiveIdle("t1");
        verify(taskStore).updateStatusIf("t1", TaskStatus.IDLE, TaskStatus.ARCHIVED);
        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.IDLE), eq(TaskStatus.ARCHIVED),
                eq(Actor.SCHEDULER), eq("idle_archived"), any());
    }

    @Test
    void reviveArchivedMovesBackToIdleAndClearsFailureFields()
    {
        Task archived = taskAt("t1", TaskPhase.IMPLEMENTING).withStatus(TaskStatus.ARCHIVED);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(archived));

        Task revived = machine.reviveArchived("t1");

        assertThat(revived.status()).isEqualTo(TaskStatus.IDLE);
        verify(taskStore).updateStatusIf("t1", TaskStatus.ARCHIVED, TaskStatus.IDLE);
        verify(taskStore).updateRuntimeFailure("t1", null, null);

        when(taskStore.findTaskById("t2")).thenReturn(
                Optional.of(taskAt("t2", TaskPhase.IMPLEMENTING)));
        assertThatThrownBy(() -> machine.reviveArchived("t2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not archived");
    }

    @Test
    void automaticLocalShipAuthorizationSpendsItsBudgetBeforeEffects()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.AWAITING_PUSH)
                        .withStatus(TaskStatus.AWAITING_REVIEW)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(2);

        boolean authorized = commands.execute(
                "t1", () -> machine.spendLocalShipAuthorizationInCommand(
                        "t1", Actor.AGENT));

        assertThat(authorized).isTrue();
        verify(taskStore).setConsecutiveAutoPushes("t1", 3);
        verify(events).publishEvent(any(TaskAutoPushEvent.class));
    }

    @Test
    void automaticLocalShipAuthorizationParksAtTheCapBeforeEffects()
    {
        Task awaitingPush = taskAt("t1", TaskPhase.AWAITING_PUSH)
                .withStatus(TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(awaitingPush));
        when(taskStore.consecutiveAutoPushes("t1"))
                .thenReturn(TaskPhaseMachine.DEFAULT_AUTO_PUSH_CAP);

        boolean authorized = commands.execute(
                "t1", () -> machine.spendLocalShipAuthorizationInCommand(
                        "t1", Actor.AGENT));

        assertThat(authorized).isFalse();
        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);
        verify(taskStore, never()).setConsecutiveAutoPushes(anyString(), anyInt());
    }

    @Test
    void authorizedLocalShipMovesBothAxesWithoutChargingTheBudgetAgain()
    {
        Task awaitingPush = taskAt("t1", TaskPhase.AWAITING_PUSH)
                .withStatus(TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(awaitingPush));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IN_REVIEW)).thenReturn(true);
        when(taskStore.consecutiveAutoPushes("t1"))
                .thenReturn(TaskPhaseMachine.DEFAULT_AUTO_PUSH_CAP);

        commands.executeVoid("t1", () -> machine.finalizeLocalShipInCommand(
                "t1", Actor.AGENT, "local_pr_pushed"));

        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.AWAITING_REVIEW), eq(TaskStatus.IN_REVIEW),
                eq(Actor.AGENT), eq("local_pr_pushed"), any());
        verify(taskStore).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(taskStore, never()).setConsecutiveAutoPushes(anyString(), anyInt());
        verify(events, never()).publishEvent(any(TaskAutoPushEvent.class));
    }

    @Test
    void authorizedLocalShipRejectsAStatusRaceBeforeWritingEitherAxis()
    {
        Task awaitingPush = taskAt("t1", TaskPhase.AWAITING_PUSH)
                .withStatus(TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(awaitingPush));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IN_REVIEW)).thenReturn(false);

        assertThatThrownBy(() -> commands.executeVoid(
                "t1", () -> machine.finalizeLocalShipInCommand(
                        "t1", Actor.HUMAN, "local_pr_pushed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed while finalizing");

        verify(taskStore, never()).updatePhase(any(), any());
    }

    @Test
    void remoteFactsMoveForwardButNeverRewindTheRemoteSpine()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.PUSHED_AWAITING_CI)
                        .withStatus(TaskStatus.IN_REVIEW)));

        machine.observeRemoteCiGreen("t1", true, "ci_green");

        verify(taskStore).updatePhase("t1", TaskPhase.AWAITING_READY);
        Mockito.clearInvocations(taskStore);
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.AWAITING_REMOTE_REVIEW)
                        .withStatus(TaskStatus.IN_REVIEW)));

        machine.observeRemoteCiGreen("t1", true, "stale_ci_green");

        verify(taskStore, never()).updatePhase(any(), any());
    }

    @Test
    void pausedRemoteOpenObservationCannotReviveTheTask()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.AWAITING_PUSH)
                        .withStatus(TaskStatus.PAUSED)));

        machine.observeRemoteOpened("t1", "remote_opened");

        verify(taskStore, never()).updateStatusIf(any(), any(), any());
        verify(taskStore, never()).updatePhase(any(), any());
    }

    @Test
    void exactRemoteOpenMovesBothRunnableAxesTogether()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(
                taskAt("t1", TaskPhase.AWAITING_PUSH)
                        .withStatus(TaskStatus.AWAITING_REVIEW)));
        when(taskStore.updateStatusIf(
                "t1", TaskStatus.AWAITING_REVIEW, TaskStatus.IN_REVIEW)).thenReturn(true);

        machine.observeRemoteOpened("t1", "remote_opened");

        verify(taskStore).appendStatusEvent(
                eq("t1"), eq(TaskStatus.AWAITING_REVIEW), eq(TaskStatus.IN_REVIEW),
                eq(Actor.WEBHOOK), eq("remote_opened"), any());
        verify(taskStore).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
    }

    private static Task taskAt(String id, TaskPhase phase)
    {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        return new Task(
                id, "thread-1", 1L, TaskStatus.RUNNING,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, null);
    }

    /** Minimal real Spring transaction boundary so requireCurrent verifies
     *  both the stripe identity and an active transaction in unit tests. */
    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status)
        {
        }
    }
}
