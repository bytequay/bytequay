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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestValidationExecutorRegistry
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void rejectsAdmissionInsideAnAmbientDatabaseTransaction()
    {
        Fixture fixture = fixture(1);
        String claimKey = "transactional-claim";
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> fixture.registry.submitIfAbsent(
                    claimKey,
                    request(
                            ValidationExecutorRegistry.operationId(claimKey),
                            CapacityManager.WorkflowSource.LEGACY,
                            "transactional-task"),
                    () -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a database transaction");
        }
        finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        assertThat(fixture.registry.isInFlight(claimKey)).isFalse();
        assertThat(fixture.store.activeCount(NOW)).isZero();
    }

    @Test
    void v2ValidationLeaseDeniesLegacyWithoutHoldingAWorkerOrLease()
            throws Exception
    {
        Fixture fixture = fixture(1);
        CapacityManager.CapacityLease v2 = fixture.manager.tryAcquireForTicket(
                "v2-ticket",
                request("v2-validation", CapacityManager.WorkflowSource.V2, "v2-task"),
                "dispatcher").lease().orElseThrow();
        AtomicInteger calls = new AtomicInteger();

        assertThat(fixture.registry.submitIfAbsent(
                "legacy-claim",
                request(
                        ValidationExecutorRegistry.operationId("legacy-claim"),
                        CapacityManager.WorkflowSource.LEGACY,
                        "legacy-task"),
                calls::incrementAndGet)).isFalse();

        assertThat(fixture.registry.isInFlight("legacy-claim")).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(fixture.store.activeCount(NOW)).isEqualTo(1);

        fixture.manager.release(v2.id(), "dispatcher");
        CountDownLatch ran = new CountDownLatch(1);
        assertThat(fixture.registry.submitIfAbsent(
                "legacy-claim",
                request(
                        ValidationExecutorRegistry.operationId("legacy-claim"),
                        CapacityManager.WorkflowSource.LEGACY,
                        "legacy-task"),
                ran::countDown)).isTrue();

        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        awaitFinished(fixture.registry, "legacy-claim");
        assertThat(fixture.store.activeCount(NOW)).isZero();
    }

    @Test
    void admittedValidationUsesExactScopeAndReleasesOnCompletion()
            throws Exception
    {
        Fixture fixture = fixture(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        String claimKey = "exact-claim";

        assertThat(fixture.registry.submitIfAbsent(
                claimKey,
                request(
                        ValidationExecutorRegistry.operationId(claimKey),
                        CapacityManager.WorkflowSource.LEGACY,
                        "task-7"),
                () -> {
                    started.countDown();
                    try {
                        finish.await();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })).isTrue();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        CapacityManager.CapacityLease lease = fixture.store.listActive(NOW).getFirst();
        assertThat(lease.operationId()).isEqualTo(
                ValidationExecutorRegistry.operationId(claimKey));
        assertThat(lease.leaseOwner()).isEqualTo(lease.operationId());
        assertThat(lease.scope()).isEqualTo(new CapacityManager.CapacityScope(
                "workspace", "trunk", "task-7", 1L));
        assertThat(lease.lanes()).containsExactly(CapacityManager.CapacityLane.VALIDATION);
        assertThat(lease.exclusiveTask()).isTrue();
        assertThat(lease.writerRequired()).isFalse();

        finish.countDown();
        awaitFinished(fixture.registry, claimKey);
        assertThat(fixture.store.activeCount(NOW)).isZero();
    }

    @Test
    void definitiveCapacityLossInterruptsTheExactValidator()
            throws Exception
    {
        Fixture fixture = fixture(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        String claimKey = "lost-claim";
        fixture.registry.submitIfAbsent(
                claimKey,
                request(
                        ValidationExecutorRegistry.operationId(claimKey),
                        CapacityManager.WorkflowSource.LEGACY,
                        "task-lost"),
                () -> {
                    started.countDown();
                    try {
                        Thread.sleep(30_000);
                    }
                    catch (InterruptedException e) {
                        interrupted.countDown();
                    }
                });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        fixture.clock.advance(Duration.ofSeconds(31));
        fixture.bridge.maintainLeases();

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        awaitFinished(fixture.registry, claimKey);
        assertThat(fixture.store.activeCount(fixture.clock.instant())).isZero();
    }

    @Test
    void restartReclaimsTheExactStableValidationLease()
            throws Exception
    {
        Fixture fixture = fixture(1);
        String claimKey = "restart-claim";
        CapacityManager.CapacityRequest request = request(
                ValidationExecutorRegistry.operationId(claimKey),
                CapacityManager.WorkflowSource.LEGACY,
                "restart-task");
        // Model the old process dying after capacity admission. Its bridge is
        // gone, but the durable lease remains until the replacement reclaims
        // the exact operation/owner pair.
        LegacyCapacityBridge oldProcess = new LegacyCapacityBridge(fixture.manager);
        LegacyCapacityBridge.Permit oldPermit = oldProcess.tryAcquire(
                request, request.operationId()).orElseThrow();
        assertThat(oldPermit.lease().operationId()).isEqualTo(request.operationId());
        LegacyCapacityBridge replacement = new LegacyCapacityBridge(fixture.manager);
        ValidationExecutorRegistry restarted = new ValidationExecutorRegistry(replacement);
        CountDownLatch ran = new CountDownLatch(1);

        assertThat(restarted.submitIfAbsent(claimKey, request, ran::countDown)).isTrue();
        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        awaitFinished(restarted, claimKey);

        assertThat(fixture.store.activeCount(NOW)).isZero();
        assertThat(fixture.store.admissionTransactions()).isEqualTo(2);
    }

    private static Fixture fixture(int validationLimit)
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = new CapacityManager(
                store,
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4,
                        Map.of(CapacityManager.CapacityLane.VALIDATION, validationLimit)),
                clock,
                Duration.ofSeconds(30));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(manager);
        return new Fixture(
                clock, store, manager, bridge,
                new ValidationExecutorRegistry(bridge));
    }

    private static CapacityManager.CapacityRequest request(
            String operationId,
            CapacityManager.WorkflowSource source,
            String taskId)
    {
        return new CapacityManager.CapacityRequest(
                operationId,
                source,
                Set.of(CapacityManager.CapacityLane.VALIDATION),
                new CapacityManager.CapacityScope("workspace", "trunk", taskId, 1L),
                false,
                true,
                false);
    }

    private static void awaitFinished(
            ValidationExecutorRegistry registry,
            String claimKey)
            throws InterruptedException
    {
        for (int attempt = 0; attempt < 100 && registry.isInFlight(claimKey); attempt++) {
            Thread.sleep(10);
        }
        assertThat(registry.isInFlight(claimKey)).isFalse();
    }

    private record Fixture(
            InMemoryExecutionSupport.MutableClock clock,
            InMemoryExecutionSupport.CapacityStore store,
            CapacityManager manager,
            LegacyCapacityBridge bridge,
            ValidationExecutorRegistry registry) {}
}
