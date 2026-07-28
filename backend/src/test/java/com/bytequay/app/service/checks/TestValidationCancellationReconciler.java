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
package com.bytequay.app.service.checks;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.InMemoryExecutionSupport;
import com.bytequay.app.developmentflow.execution.LegacyCapacityBridge;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.repository.ValidationPassStore.PendingValidationCancel;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestValidationCancellationReconciler
{
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private final ValidationPassStore store = mock(ValidationPassStore.class);
    private final ValidationExecutorRegistry registry = new ValidationExecutorRegistry(
            new LegacyCapacityBridge(new CapacityManager(
                    new InMemoryExecutionSupport.CapacityStore(),
                    () -> CapacityManager.CapacityPolicy.initial(
                            4, 4, Map.of(CapacityManager.CapacityLane.VALIDATION, 4)),
                    new InMemoryExecutionSupport.MutableClock(Instant.now()),
                    Duration.ofSeconds(30))));
    private final TaskStore taskStore = mock(TaskStore.class);
    private final TaskPhaseMachine machine = mock(TaskPhaseMachine.class);
    private final ValidationCancellationReconciler reconciler =
            new ValidationCancellationReconciler(store, registry, taskStore, machine);

    @Test
    void provenAbsentClaimIsSuperseded()
    {
        // Not in flight, foreign executor identity, lease expired.
        when(store.findCancelPending()).thenReturn(List.of(new PendingValidationCancel(
                "claim-1", "t1", "999@elsewhere", NOW.minusSeconds(60), null)));

        reconciler.sweep();

        verify(store).markSuperseded(eq("claim-1"), any());
        verify(machine, never()).parkOperational(any(), any(), any());
    }

    @Test
    void inFlightExecutorIsInterruptedAndTheAttemptRecorded()
            throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        registry.submitIfAbsent("claim-1", validationRequest("claim-1"), () -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            }
            catch (InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        when(store.findCancelPending()).thenReturn(List.of(new PendingValidationCancel(
                "claim-1", "t1", "999@elsewhere", Instant.now().plusSeconds(120), null)));

        reconciler.sweep();

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(store).incrementCancelAttempts("claim-1");
        verify(store, never()).markSuperseded(any(), any());
    }

    private static CapacityManager.CapacityRequest validationRequest(String claimKey)
    {
        return new CapacityManager.CapacityRequest(
                ValidationExecutorRegistry.operationId(claimKey),
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.VALIDATION),
                new CapacityManager.CapacityScope("workspace", "trunk", "t1", 1L),
                false,
                true,
                false);
    }

    @Test
    void unprovableStopPastTheDeadlineParksARunnableTask()
    {
        // Foreign executor with a live lease: absence cannot be proved.
        when(store.findCancelPending()).thenReturn(List.of(new PendingValidationCancel(
                "claim-1", "t1", "999@elsewhere", Instant.now().plusSeconds(120),
                NOW.minusSeconds(60))));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(task(TaskStatus.RUNNING)));

        reconciler.sweep();

        verify(machine).parkOperational("t1", Actor.AGENT, "validation_stop_failed");
    }

    @Test
    void unprovableStopOnAPausedTaskOnlyRecordsTheFailure()
    {
        when(store.findCancelPending()).thenReturn(List.of(new PendingValidationCancel(
                "claim-1", "t1", "999@elsewhere", Instant.now().plusSeconds(120),
                NOW.minusSeconds(60))));
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(task(TaskStatus.PAUSED)));

        reconciler.sweep();

        verify(machine, never()).parkOperational(any(), any(), any());
    }

    @Test
    void thisProcessesFinishedExecutorProvesAbsenceDespiteALiveLease()
    {
        // Identity matches this JVM and nothing is in flight — gone is gone,
        // even before the durable lease expires.
        String mine = ProcessHandle.current().pid() + "@";
        when(store.findCancelPending()).thenReturn(List.of(new PendingValidationCancel(
                "claim-1", "t1", mine + hostOf(), Instant.now().plusSeconds(120), null)));

        reconciler.sweep();

        verify(store).markSuperseded(eq("claim-1"), any());
    }

    private static String hostOf()
    {
        try {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (UnknownHostException e) {
            return "localhost";
        }
    }

    private static Task task(TaskStatus status)
    {
        return new Task(
                "t1", "thread-1", 1L, status,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                NOW, null, null, null, null, null);
    }
}
