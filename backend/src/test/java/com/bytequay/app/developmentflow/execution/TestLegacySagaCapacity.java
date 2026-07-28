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
package com.bytequay.app.developmentflow.execution;

import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.LegacyTaskScopeResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestLegacySagaCapacity
{
    @Test
    void acquiresExactLegacyScopeAndReleasesOnce()
    {
        LegacyCapacityBridge bridge = mock(LegacyCapacityBridge.class);
        LegacyTaskScopeResolver scopes = mock(LegacyTaskScopeResolver.class);
        LegacyCapacityBridge.Permit permit = mock(LegacyCapacityBridge.Permit.class);
        CapacityManager.CapacityScope scope = new CapacityManager.CapacityScope(
                "workspace-2", "trunk-3", "task-7", 9L);
        when(scopes.resolve("task-7")).thenReturn(scope);
        when(bridge.tryAcquire(any(), eq("legacy-effect:42"), any()))
                .thenReturn(Optional.of(permit));
        LegacySagaCapacity capacity = new LegacySagaCapacity(bridge, scopes);

        LegacySagaCapacity.Attempt attempt = capacity.tryAcquire(
                "task-7", "legacy-effect:42",
                Set.of(CapacityManager.CapacityLane.GITHUB)).orElseThrow();
        attempt.requireLive();
        attempt.close();
        attempt.close();

        ArgumentCaptor<CapacityManager.CapacityRequest> request =
                ArgumentCaptor.forClass(CapacityManager.CapacityRequest.class);
        verify(bridge).tryAcquire(request.capture(), eq("legacy-effect:42"), any());
        assertThat(request.getValue()).isEqualTo(new CapacityManager.CapacityRequest(
                "legacy-effect:42",
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.GITHUB),
                scope,
                false,
                true,
                false));
        verify(permit).lease();
        verify(permit).close();
    }

    @Test
    void saturatedGithubLaneReturnsWithoutCreatingAnAttempt()
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(Instant.EPOCH);
        CapacityManager manager = new CapacityManager(
                new InMemoryExecutionSupport.CapacityStore(),
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of(CapacityManager.CapacityLane.GITHUB, 1)),
                clock,
                Duration.ofSeconds(30));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(manager);
        LegacyTaskScopeResolver scopes = mock(LegacyTaskScopeResolver.class);
        when(scopes.resolve("task-7")).thenReturn(new CapacityManager.CapacityScope(
                "workspace-2", "trunk-3", "task-7", 9L));
        CapacityManager.CapacityRequest occupyingRequest = new CapacityManager.CapacityRequest(
                "occupying-github-effect",
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.GITHUB),
                new CapacityManager.CapacityScope(
                        "workspace-2", "trunk-3", "other-task", 1L),
                false,
                true,
                false);
        CapacityManager.CapacityLease occupying = manager.tryAcquire(
                occupyingRequest, "occupying-github-effect").lease().orElseThrow();

        assertThat(new LegacySagaCapacity(bridge, scopes).tryAcquire(
                "task-7", "legacy-effect:42",
                Set.of(CapacityManager.CapacityLane.GITHUB))).isEmpty();

        assertThat(manager.release(occupying.id(), "occupying-github-effect")).isTrue();
    }

    @Test
    void definitiveLossInterruptsTheExactOwnerAndLeavesInterruptObservable()
    {
        LegacyCapacityBridge bridge = mock(LegacyCapacityBridge.class);
        LegacyTaskScopeResolver scopes = mock(LegacyTaskScopeResolver.class);
        LegacyCapacityBridge.Permit permit = mock(LegacyCapacityBridge.Permit.class);
        AtomicReference<Runnable> stop = new AtomicReference<>();
        when(scopes.resolve("task-7")).thenReturn(new CapacityManager.CapacityScope(
                "workspace-2", "trunk-3", "task-7", 9L));
        when(bridge.tryAcquire(any(), any(), any())).thenAnswer(invocation -> {
            stop.set(invocation.getArgument(2));
            return Optional.of(permit);
        });
        LegacySagaCapacity.Attempt attempt = new LegacySagaCapacity(bridge, scopes)
                .tryAcquire("task-7", "legacy-effect:42",
                        Set.of(CapacityManager.CapacityLane.GITHUB))
                .orElseThrow();

        try {
            stop.get().run();

            assertThat(attempt.leaseLost()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            attempt.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(permit, never()).lease();
        }
        finally {
            // Test harness cleanup after proving production code did not clear it.
            Thread.interrupted();
        }
    }

    @Test
    void acquisitionFailsBeforeScopeOrBridgeInsideATransaction()
    {
        LegacyCapacityBridge bridge = mock(LegacyCapacityBridge.class);
        LegacyTaskScopeResolver scopes = mock(LegacyTaskScopeResolver.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new LegacySagaCapacity(bridge, scopes).tryAcquire(
                    "task-7", "legacy-effect:42",
                    Set.of(CapacityManager.CapacityLane.GITHUB)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a transaction");
            verifyNoInteractions(scopes, bridge);
        }
        finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void scopeResolverUsesOnlyExactTaskTrunkAndPersistedEpoch()
    {
        TaskStore tasks = mock(TaskStore.class);
        ThreadStore threads = mock(ThreadStore.class);
        Task task = mock(Task.class);
        com.bytequay.app.domain.Thread trunk = mock(com.bytequay.app.domain.Thread.class);
        when(task.id()).thenReturn("task-7");
        when(task.threadId()).thenReturn("trunk-3");
        when(trunk.id()).thenReturn("trunk-3");
        when(trunk.workspaceId()).thenReturn("workspace-2");
        when(tasks.findTaskById("task-7")).thenReturn(Optional.of(task));
        when(tasks.findTaskEpoch("task-7")).thenReturn(OptionalLong.of(9L));
        when(threads.findThreadById("trunk-3")).thenReturn(Optional.of(trunk));

        assertThat(new LegacyTaskScopeResolver(tasks, threads).resolve("task-7"))
                .isEqualTo(new CapacityManager.CapacityScope(
                        "workspace-2", "trunk-3", "task-7", 9L));
        verify(tasks).findTaskById("task-7");
        verify(tasks).findTaskEpoch("task-7");
        verify(threads).findThreadById("trunk-3");
    }

    @Test
    void missingPersistedEpochFailsClosedBeforeBridgeAdmission()
    {
        TaskStore tasks = mock(TaskStore.class);
        ThreadStore threads = mock(ThreadStore.class);
        LegacyCapacityBridge bridge = mock(LegacyCapacityBridge.class);
        Task task = mock(Task.class);
        com.bytequay.app.domain.Thread trunk = mock(com.bytequay.app.domain.Thread.class);
        when(task.id()).thenReturn("task-7");
        when(task.threadId()).thenReturn("trunk-3");
        when(trunk.id()).thenReturn("trunk-3");
        when(tasks.findTaskById("task-7")).thenReturn(Optional.of(task));
        when(tasks.findTaskEpoch("task-7")).thenReturn(OptionalLong.empty());
        when(threads.findThreadById("trunk-3")).thenReturn(Optional.of(trunk));
        LegacySagaCapacity capacity = new LegacySagaCapacity(
                bridge, new LegacyTaskScopeResolver(tasks, threads));

        assertThatThrownBy(() -> capacity.tryAcquire(
                "task-7", "legacy-effect:42",
                Set.of(CapacityManager.CapacityLane.GITHUB)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exact epoch");
        verifyNoInteractions(bridge);
    }
}
