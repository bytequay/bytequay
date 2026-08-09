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
import com.bytequay.app.service.stage.PlanStageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskRuntimeStopReconciler
{
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ValidationPassStore validationStore = mock(ValidationPassStore.class);
    private final TaskService taskService = mock(TaskService.class);
    private final PlanStageService planStages = mock(PlanStageService.class);
    private final ObjectProvider<TaskService> provider = new ObjectProvider<>()
    {
        @Override
        public TaskService getObject()
        {
            return taskService;
        }
    };
    private final TaskRuntimeStopReconciler reconciler = new TaskRuntimeStopReconciler(
            taskStore, turnStore, registry, scheduler,
            validationStore, provider, provider(planStages));

    @Test
    void teardownCancelsTurnsEvictsAgentsAndRequestsValidationCancel()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(task("t1", TaskStatus.PAUSED)));
        Agent agent = mock(Agent.class);
        when(registry.findTaskAgents(List.of("t1"))).thenReturn(List.of(agent));
        when(validationStore.findOpenByTask("t1")).thenReturn(List.of(
                claim("claim-1", null, null)));

        reconciler.reconcileStoppedTask("t1");

        verify(scheduler).cancelTaskTurns("t1");
        verify(agent).interrupt();
        verify(registry).evictTaskAgent("thread-1", "t1");
        verify(validationStore).requestCancel(eq("claim-1"), any(), any());
    }

    @Test
    void teardownIsANoOpForALiveTask()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(task("t1", TaskStatus.RUNNING)));

        reconciler.reconcileStoppedTask("t1");

        verify(scheduler, never()).cancelTaskTurns(anyString());
        verify(registry, never()).evictTaskAgent(anyString(), anyString());
        verify(validationStore, never()).requestCancel(any(), any(), any());
    }

    @Test
    void legacyStopReconcilerNeverClaimsAV2Task()
    {
        when(taskStore.isV2Task("v2-task")).thenReturn(true);

        reconciler.reconcileStoppedTask("v2-task");

        verify(taskStore, never()).findTaskById("v2-task");
        verify(scheduler, never()).cancelTaskTurns("v2-task");
        verify(registry, never()).evictTaskAgent(anyString(), anyString());
    }

    @Test
    void barrierHoldsWhileTurnsAgentsOrValidatorsAreLive()
    {
        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of(mock(ThreadTurn.class)));
        assertThat(reconciler.runtimeStopped("t1")).isFalse();

        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of());
        when(registry.findTaskAgents(List.of("t1"))).thenReturn(List.of(mock(Agent.class)));
        assertThat(reconciler.runtimeStopped("t1")).isFalse();

        when(registry.findTaskAgents(List.of("t1"))).thenReturn(List.of());
        when(validationStore.findOpenByTask("t1")).thenReturn(List.of(
                claim("claim-1", null, Instant.now().plusSeconds(120))));
        assertThat(reconciler.runtimeStopped("t1")).isFalse();
    }

    @Test
    void barrierClearsOnceEverythingIsProvablyGone()
    {
        // Open claim whose lease expired and whose executor is absent —
        // a crashed validator no longer blocks the barrier.
        when(validationStore.findOpenByTask("t1")).thenReturn(List.of(
                claim("claim-1", null, NOW)));
        assertThat(reconciler.runtimeStopped("t1")).isTrue();
    }

    @Test
    void sweepCompletesAPendingResumeOnceTheBarrierHolds()
    {
        Task paused = task("t1", TaskStatus.PAUSED);
        when(taskStore.listByStatuses(any(), eq(200))).thenReturn(List.of(paused));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(paused));
        when(taskStore.resumeRequestedAt("t1")).thenReturn(Optional.of(NOW));

        reconciler.sweep();

        verify(taskService).completeRequestedResume("t1");
    }

    @Test
    void sweepCompletesAPendingRecoveryOnceTheBarrierHolds()
    {
        Task parked = task("t1", TaskStatus.NEEDS_ATTENTION);
        when(taskStore.listByStatuses(any(), eq(200))).thenReturn(List.of(parked));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.of(new TaskRecoveryRequest(
                "req-1", TaskRecoveryRequest.KIND_NORMAL, null, NOW)));

        reconciler.sweep();

        verify(taskService).completeRequestedRecovery("t1");
    }

    @Test
    void startupCompletesRecoveryOnlyAfterSchedulerStartupRecovery()
    {
        Task parked = task("t1", TaskStatus.NEEDS_ATTENTION);
        when(taskStore.listByStatuses(any(), eq(200))).thenReturn(List.of(parked));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.of(new TaskRecoveryRequest(
                "req-1", TaskRecoveryRequest.KIND_NORMAL, null, NOW)));

        reconciler.reconcileOnStartup();

        verify(taskService, never()).completeRequestedRecovery("t1");

        reconciler.completePendingRequestsOnStartup();

        verify(taskService).completeRequestedRecovery("t1");
    }

    @Test
    void sweepCompletesGuidedReplanThroughItsNamedOwner()
    {
        Task parked = task("t1", TaskStatus.NEEDS_ATTENTION);
        when(taskStore.listByStatuses(any(), eq(200))).thenReturn(List.of(parked));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(parked));
        when(taskStore.recoveryRequest("t1")).thenReturn(Optional.of(new TaskRecoveryRequest(
                "req-1", TaskRecoveryRequest.KIND_REPLAN, null, NOW)));

        reconciler.sweep();

        verify(planStages).completeRequestedReplan("t1");
        verify(taskService, never()).completeRequestedRecovery("t1");
    }

    @Test
    void parkAndTerminalTransitionsTriggerTeardown()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(task("t1", TaskStatus.NEEDS_ATTENTION)));

        reconciler.onPhaseTransitioned(new TaskPhaseTransitionedEvent(
                "t1", TaskPhase.VALIDATING, TaskPhase.NEEDS_ATTENTION, "validation_failed"));
        verify(scheduler).cancelTaskTurns("t1");

        reconciler.onPhaseTransitioned(new TaskPhaseTransitionedEvent(
                "t2", TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING, "ready_for_checks"));
        verify(taskStore, never()).findTaskById("t2");
    }

    @Test
    void sweepLeavesAPausedTaskWithoutAResumeRequestAlone()
    {
        Task paused = task("t1", TaskStatus.PAUSED);
        when(taskStore.listByStatuses(any(), eq(200))).thenReturn(List.of(paused));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(paused));
        when(taskStore.resumeRequestedAt("t1")).thenReturn(Optional.empty());

        reconciler.sweep();

        verify(taskService, never()).completeRequestedResume(anyString());
        // Teardown still ran — a stopped task sheds leftover runtime even
        // with nothing to resume.
        verify(scheduler).cancelTaskTurns("t1");
    }

    private static Task task(String id, TaskStatus status)
    {
        return new Task(
                id, "thread-1", 1L, status,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                NOW, null, null, null, null, null);
    }

    private static ValidationClaim claim(String key, Instant cancelRequestedAt, Instant leaseUntil)
    {
        return new ValidationClaim(
                1L, key, "t1", "dev_round", "round-1", "fp-1",
                null, null, NOW, null, null, null,
                cancelRequestedAt, null, "owner-1", leaseUntil);
    }

    private static <T> ObjectProvider<T> provider(T value)
    {
        return new ObjectProvider<>()
        {
            @Override
            public T getObject()
            {
                return value;
            }
        };
    }
}
