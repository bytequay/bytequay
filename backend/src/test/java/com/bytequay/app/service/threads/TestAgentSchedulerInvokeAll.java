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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.InMemoryExecutionSupport;
import com.bytequay.app.developmentflow.execution.LegacyCapacityBridge;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AgentScheduler#invokeAll}: out-of-band API work shares the
 * API lane's capacity, results come back in submission order, and a
 * failed item surfaces after the batch.
 */
class TestAgentSchedulerInvokeAll
{
    private AgentScheduler scheduler(int maxApi)
    {
        return new AgentScheduler(
                mock(ThreadStore.class),
                mock(ThreadTurnStore.class),
                mock(ThreadTurnEventStore.class),
                mock(ThreadRegistry.class),
                mock(StageStore.class),
                mock(TaskStore.class),
                /* maxCliRunning */ 1,
                maxApi);
    }

    private AgentScheduler scheduler(int maxApi, LegacyCapacityBridge bridge)
    {
        return new AgentScheduler(
                mock(ThreadStore.class),
                mock(ThreadTurnStore.class),
                mock(ThreadTurnEventStore.class),
                mock(ThreadRegistry.class),
                mock(StageStore.class),
                mock(TaskStore.class),
                null,
                null,
                null,
                null,
                null,
                null,
                bridge,
                /* maxCliRunning */ 4,
                maxApi);
    }

    @Test
    void resultsComeBackInSubmissionOrder()
    {
        AgentScheduler scheduler = scheduler(4);
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int value = i;
            work.add(() -> {
                // Reverse-staggered sleeps so completion order is the
                // opposite of submission order.
                Thread.sleep(10L - value);
                return value;
            });
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), scheduler.invokeAll(work));
    }

    @Test
    void concurrencyFillsButNeverExceedsTheApiLaneCap()
            throws Exception
    {
        int cap = 6;
        AgentScheduler scheduler = scheduler(cap);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch capacityReached = new CountDownLatch(cap);
        CountDownLatch release = new CountDownLatch(1);
        List<Callable<Integer>> work = new ArrayList<>();
        for (int i = 0; i < cap + 1; i++) {
            work.add(() -> {
                int now = running.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                capacityReached.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return now;
                }
                finally {
                    running.decrementAndGet();
                }
            });
        }
        CompletableFuture<List<Integer>> batch = CompletableFuture.supplyAsync(
                () -> scheduler.invokeAll(work));
        try {
            assertTrue(capacityReached.await(2, TimeUnit.SECONDS));
            assertEquals(cap, running.get());
            assertEquals(cap, peak.get());
        }
        finally {
            release.countDown();
        }

        assertEquals(cap + 1, batch.get(2, TimeUnit.SECONDS).size());
        assertEquals(cap, peak.get());
    }

    @Test
    void aFailedItemSurfacesAfterTheBatchFinishes()
    {
        AgentScheduler scheduler = scheduler(2);
        AtomicInteger completed = new AtomicInteger();
        List<Callable<Integer>> work = List.of(
                () -> {
                    completed.incrementAndGet();
                    return 1;
                },
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> {
                    completed.incrementAndGet();
                    return 3;
                });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> scheduler.invokeAll(work));
        assertEquals("boom", failure.getMessage());
        // The healthy items still ran to completion first.
        assertEquals(2, completed.get());
    }

    @Test
    void sharedCapacityDenialWaitsForAReleaseHintWithoutBusySpinning()
            throws Exception
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(now);
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = new CapacityManager(
                store,
                () -> CapacityManager.CapacityPolicy.initial(4, 4, Map.of()),
                clock,
                Duration.ofSeconds(30));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(manager);
        AgentScheduler scheduler = scheduler(6, bridge);
        List<CapacityManager.CapacityLease> v2Leases = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            CapacityManager.CapacityRequest request = v2ApiRequest("v2-" + index);
            v2Leases.add(manager.tryAcquireForTicket(
                    "ticket-" + index, request, "dispatcher").lease().orElseThrow());
        }
        int admissionsBeforeWait = store.admissionTransactions();
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<List<Integer>> waiting = CompletableFuture.supplyAsync(() ->
                scheduler.invokeAll(List.of(() -> calls.incrementAndGet())));
        assertTrue(store.awaitAdmissionTransactions(
                admissionsBeforeWait + 1, Duration.ofSeconds(2)));

        assertEquals(0, calls.get());
        assertEquals(admissionsBeforeWait + 1, store.admissionTransactions());
        assertEquals(5, store.activeCount(now));

        manager.release(v2Leases.getFirst().id(), "dispatcher");
        assertEquals(List.of(1), waiting.get(2, TimeUnit.SECONDS));
        assertTrue(store.activeCount(now) <= 4);
        assertTrue(store.admissionTransactions() <= admissionsBeforeWait + 2);

        for (CapacityManager.CapacityLease lease : v2Leases.subList(1, v2Leases.size())) {
            manager.release(lease.id(), "dispatcher");
        }
    }

    @Test
    void reviewWorkOwnsOneExactReadOnlyReviewAndProviderLease()
            throws Exception
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 2);
        AgentScheduler scheduler = scheduler(6, new LegacyCapacityBridge(manager));
        CapacityManager.CapacityRequest request = reviewRequest(
                "review-seat-1", CapacityManager.CapacityLane.API);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);

        CompletableFuture<String> running = CompletableFuture.supplyAsync(() ->
                scheduler.invokeReviewApi(request, () -> {
                    started.countDown();
                    assertTrue(finish.await(2, TimeUnit.SECONDS));
                    return "done";
                }));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        CapacityManager.CapacityLease lease = store.listActive(now).getFirst();
        assertEquals(request.operationId(), lease.operationId());
        assertEquals(request.operationId(), lease.leaseOwner());
        assertEquals(request.lanes(), lease.lanes());
        assertEquals(request.scope(), lease.scope());
        assertEquals(false, lease.trunkControl());
        assertEquals(false, lease.exclusiveTask());
        assertEquals(false, lease.writerRequired());

        finish.countDown();
        assertEquals("done", running.get(2, TimeUnit.SECONDS));
        assertEquals(0, store.activeCount(now));
    }

    @Test
    void reviewLaneSaturationWaitsForReleaseWithoutSpinning()
            throws Exception
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 1);
        AgentScheduler scheduler = scheduler(6, new LegacyCapacityBridge(manager));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        Callable<Integer> first = () -> {
            int active = running.incrementAndGet();
            peak.accumulateAndGet(active, Math::max);
            firstStarted.countDown();
            try {
                assertTrue(finishFirst.await(2, TimeUnit.SECONDS));
                return 1;
            }
            finally {
                running.decrementAndGet();
            }
        };
        Callable<Integer> second = () -> {
            int active = running.incrementAndGet();
            peak.accumulateAndGet(active, Math::max);
            running.decrementAndGet();
            return 2;
        };

        CompletableFuture<Integer> firstRun = CompletableFuture.supplyAsync(() ->
                scheduler.invokeReviewApi(
                        reviewRequest("review-1", CapacityManager.CapacityLane.API),
                        first));
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        CompletableFuture<Integer> secondRun = CompletableFuture.supplyAsync(() ->
                scheduler.invokeReviewApi(
                        reviewRequest("review-2", CapacityManager.CapacityLane.API),
                        second));
        assertTrue(store.awaitAdmissionTransactions(2, Duration.ofSeconds(2)));
        assertEquals(1, running.get());
        assertEquals(2, store.admissionTransactions());

        finishFirst.countDown();
        assertEquals(1, firstRun.get(2, TimeUnit.SECONDS));
        assertEquals(2, secondRun.get(2, TimeUnit.SECONDS));
        assertEquals(1, peak.get());
        assertEquals(3, store.admissionTransactions());
        assertEquals(0, store.activeCount(now));
    }

    @Test
    void nestedReviewTryDoesNotDeadlockWhenTheLastReviewSlotIsHeld()
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 1);
        AgentScheduler scheduler = scheduler(6, new LegacyCapacityBridge(manager));
        AtomicBoolean nestedLaunch = new AtomicBoolean();

        String result = scheduler.invokeReviewApi(
                reviewRequest("lead", CapacityManager.CapacityLane.API),
                () -> {
                    assertTrue(scheduler.tryInvokeReviewApi(
                            reviewRequest("seat", CapacityManager.CapacityLane.API),
                            () -> {
                                nestedLaunch.set(true);
                                return "never";
                            }).isEmpty());
                    return "lead-continues";
                });

        assertEquals("lead-continues", result);
        assertEquals(false, nestedLaunch.get());
        assertEquals(0, store.activeCount(now));
    }

    @Test
    void nestedReviewFanOutIsAllOrNoneWhenCapacityIsUnavailable()
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 1);
        AgentScheduler scheduler = scheduler(6, new LegacyCapacityBridge(manager));
        AtomicInteger launches = new AtomicInteger();

        String result = scheduler.invokeReviewApi(
                reviewRequest("lead", CapacityManager.CapacityLane.API),
                () -> {
                    Optional<List<Integer>> nested = scheduler.tryInvokeReviewAll(List.of(
                            new AgentScheduler.ReviewWork<>(reviewRequest(
                                    "seat-1", CapacityManager.CapacityLane.API),
                                    launches::incrementAndGet),
                            new AgentScheduler.ReviewWork<>(reviewRequest(
                                    "seat-2", CapacityManager.CapacityLane.API),
                                    launches::incrementAndGet)));
                    assertTrue(nested.isEmpty());
                    return "lead-continues";
                });

        assertEquals("lead-continues", result);
        assertEquals(0, launches.get());
        assertEquals(0, store.activeCount(now));
    }

    @Test
    void nestedFanOutProgressesWhenThePanelIsLargerThanFreeApiCapacity()
            throws Exception
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 6);
        AgentScheduler scheduler = scheduler(6, new LegacyCapacityBridge(manager));
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // API reserves one of six slots for Trunk control. The Lead consumes
        // one ordinary slot, so four of five reviewers can run at once.
        CountDownLatch freeCapacityFilled = new CountDownLatch(4);
        CountDownLatch finish = new CountDownLatch(1);
        Callable<Integer> work = () -> {
            int active = running.incrementAndGet();
            peak.accumulateAndGet(active, Math::max);
            freeCapacityFilled.countDown();
            try {
                assertTrue(finish.await(2, TimeUnit.SECONDS));
                return active;
            }
            finally {
                running.decrementAndGet();
            }
        };
        CompletableFuture<Void> releaser = CompletableFuture.runAsync(() -> {
            try {
                assertTrue(freeCapacityFilled.await(2, TimeUnit.SECONDS));
            }
            catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            finish.countDown();
        });

        List<AgentScheduler.ReviewWork<Integer>> reviewers = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            reviewers.add(new AgentScheduler.ReviewWork<>(reviewRequest(
                    "seat-" + index, CapacityManager.CapacityLane.API), work));
        }
        List<Integer> results = scheduler.invokeReviewApi(
                reviewRequest("lead", CapacityManager.CapacityLane.API),
                () -> scheduler.tryInvokeReviewAll(reviewers)
                        .orElseThrow());

        releaser.get(2, TimeUnit.SECONDS);
        assertEquals(5, results.size());
        assertEquals(4, peak.get());
        assertEquals(0, store.activeCount(now));
    }

    @Test
    void lostReviewLeaseInterruptsItsExactWorkerAndRejectsNominalSuccess()
            throws Exception
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(now);
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = new CapacityManager(
                store,
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(CapacityManager.CapacityLane.REVIEW, 1)),
                clock,
                Duration.ofSeconds(30));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(manager);
        AgentScheduler scheduler = scheduler(6, bridge);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        CompletableFuture<String> running = CompletableFuture.supplyAsync(() ->
                scheduler.invokeReviewApi(
                        reviewRequest("lost-review", CapacityManager.CapacityLane.API),
                        () -> {
                            started.countDown();
                            try {
                                Thread.sleep(Duration.ofMinutes(1));
                            }
                            catch (InterruptedException e) {
                                interrupted.set(true);
                            }
                            return "must-not-commit";
                        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        clock.advance(Duration.ofSeconds(31));
        bridge.maintainLeases();

        CompletionException failure = assertThrows(CompletionException.class, running::join);
        assertTrue(failure.getCause().getMessage().contains("permit is closed"));
        assertTrue(interrupted.get());
        assertEquals(0, store.activeCount(clock.instant()));
    }

    @Test
    void restartedReviewReclaimsTheSameStableOperationAndReleasesIt()
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 1);
        CapacityManager.CapacityRequest request = reviewRequest(
                "restart-seat", CapacityManager.CapacityLane.CLI);
        LegacyCapacityBridge abandonedProcess = new LegacyCapacityBridge(manager);
        LegacyCapacityBridge.Permit abandonedPermit = abandonedProcess.tryAcquire(
                request, request.operationId()).orElseThrow();
        assertEquals(request.operationId(), abandonedPermit.lease().operationId());

        LegacyCapacityBridge restartedBridge = new LegacyCapacityBridge(manager);
        AgentScheduler restarted = scheduler(6, restartedBridge);
        assertEquals("resumed", restarted.invokeReviewCli(request, () -> "resumed"));

        assertEquals(2, store.admissionTransactions());
        assertEquals(0, store.activeCount(now));
        abandonedProcess.close();
    }

    @Test
    void reviewAdmissionRejectsAnAmbientDatabaseTransaction()
    {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager manager = capacityManager(store, now, 1);
        AgentScheduler scheduler = scheduler(6, new LegacyCapacityBridge(manager));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> scheduler.invokeReviewApi(
                            reviewRequest("transaction", CapacityManager.CapacityLane.API),
                            () -> "never"));
            assertTrue(failure.getMessage().contains("outside a database transaction"));
        }
        finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertEquals(0, store.admissionTransactions());
    }

    private static CapacityManager capacityManager(
            InMemoryExecutionSupport.CapacityStore store,
            Instant now,
            int reviewLimit)
    {
        return new CapacityManager(
                store,
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(
                                CapacityManager.CapacityLane.REVIEW, reviewLimit)),
                new InMemoryExecutionSupport.MutableClock(now),
                Duration.ofSeconds(30));
    }

    private static CapacityManager.CapacityRequest reviewRequest(
            String operationId,
            CapacityManager.CapacityLane providerLane)
    {
        return new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CapacityManager.CapacityLane.REVIEW, providerLane),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", "task-1", 3L),
                false,
                false,
                false);
    }

    private static CapacityManager.CapacityRequest v2ApiRequest(String operationId)
    {
        return new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.V2,
                Set.of(CapacityManager.CapacityLane.API),
                new CapacityManager.CapacityScope(null, null, null, null),
                false,
                false,
                false);
    }
}
