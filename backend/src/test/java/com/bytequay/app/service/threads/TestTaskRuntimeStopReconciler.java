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
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.checks.ValidationExecutorRegistry;
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
    private final StageStore stageStore = mock(StageStore.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ValidationPassStore validationStore = mock(ValidationPassStore.class);
    private final ValidationExecutorRegistry executorRegistry = mock(ValidationExecutorRegistry.class);
    private final TaskService taskService = mock(TaskService.class);
    private final ObjectProvider<TaskService> provider = new ObjectProvider<>()
    {
        @Override
        public TaskService getObject()
        {
            return taskService;
        }
    };
    private final TaskRuntimeStopReconciler reconciler = new TaskRuntimeStopReconciler(
            taskStore, stageStore, turnStore, registry, scheduler,
            validationStore, executorRegistry, provider);

    @Test
    void teardownCancelsTurnsEvictsAgentsAndRequestsValidationCancel()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(task("t1", TaskStatus.PAUSED)));
        ThreadAgent agent = mock(ThreadAgent.class);
        when(registry.findStages(List.of("t1"))).thenReturn(List.of(agent));
        when(validationStore.findOpenByTask("t1")).thenReturn(List.of(
                claim("claim-1", null, null)));

        reconciler.reconcileStoppedTask("t1");

        verify(scheduler).cancelTaskTurns("t1");
        verify(agent).interrupt();
        verify(registry).evictStages("thread-1", List.of("t1"));
        verify(validationStore).requestCancel(eq("claim-1"), any(), any());
    }

    @Test
    void teardownIsANoOpForALiveTask()
    {
        when(taskStore.findTaskById("t1")).thenReturn(
                Optional.of(task("t1", TaskStatus.RUNNING)));

        reconciler.reconcileStoppedTask("t1");

        verify(scheduler, never()).cancelTaskTurns(anyString());
        verify(registry, never()).evictStages(anyString(), any());
        verify(validationStore, never()).requestCancel(any(), any(), any());
    }

    @Test
    void barrierHoldsWhileTurnsAgentsOrValidatorsAreLive()
    {
        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of(mock(ThreadTurn.class)));
        assertThat(reconciler.runtimeStopped("t1")).isFalse();

        when(turnStore.listTurnsByExactTaskIdAndStatus("t1", ThreadTurnStatus.QUEUED, 1))
                .thenReturn(List.of());
        when(registry.findStages(List.of("t1"))).thenReturn(List.of(mock(ThreadAgent.class)));
        assertThat(reconciler.runtimeStopped("t1")).isFalse();

        when(registry.findStages(List.of("t1"))).thenReturn(List.of());
        when(validationStore.findOpenByTask("t1")).thenReturn(List.of(
                claim("claim-1", null, null)));
        when(executorRegistry.isInFlight("claim-1")).thenReturn(true);
        assertThat(reconciler.runtimeStopped("t1")).isFalse();

        when(executorRegistry.isInFlight("claim-1")).thenReturn(false);
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
        when(executorRegistry.isInFlight("claim-1")).thenReturn(false);

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
}
