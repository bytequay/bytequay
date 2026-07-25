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
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private final TaskPhaseMachine machine =
            new TaskPhaseMachine(taskStore, notifications, events, localCiFix, turnStore);

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
    void brainReviewConclusionOpensTheLocalReviewGate()
    {
        when(taskStore.findTaskById("t1"))
                .thenReturn(Optional.of(taskAt("t1", TaskPhase.INTERNAL_REVIEW)));

        machine.onLocalReviewCleared(new LocalReviewClearedEvent("t1", "pr1", true));

        verify(taskStore).updatePhase("t1", TaskPhase.AWAITING_PUSH);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.INTERNAL_REVIEW),
                eq(TaskPhase.AWAITING_PUSH), any(), eq("local_review_opened"), eq(Actor.AGENT));
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
}
