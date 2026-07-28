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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.RECONCILE_WAIT;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.REQUESTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.RESULT_PENDING;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.RETRY_WAIT;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.RUNNING;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestExecutionDispatcher
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void durableCapacityWaitConsumesNeitherWorkerNorLease()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 1)) {
            CountingHandler handler = fixture.register("validation");
            CapacityManager.CapacityRequest blockerRequest = capacityRequest(
                    "blocker", VALIDATION, "blocker-task", false);
            CapacityManager.CapacityLease blocker = fixture.capacityManager.tryAcquireForTicket(
                    "blocker-ticket", blockerRequest, "other-worker").lease().orElseThrow();
            fixture.put(requested("waiting", "validation", VALIDATION, false));

            fixture.dispatcher.runMaintenance();

            assertThat(fixture.ticket("waiting").state()).isEqualTo(REQUESTED);
            assertThat(handler.executeCalls).isZero();
            assertThat(fixture.capacityStore.activeCount(NOW)).isEqualTo(1);

            fixture.capacityManager.release(blocker.id(), "other-worker");
            fixture.dispatcher.runMaintenance();

            assertThat(fixture.ticket("waiting").state()).isEqualTo(RESULT_PENDING);
            assertThat(handler.executeCalls).isEqualTo(1);
            assertThat(fixture.capacityStore.activeCount(NOW)).isZero();

            fixture.dispatcher.runMaintenance();
            assertThat(fixture.ticket("waiting").state()).isEqualTo(SUCCEEDED);
            assertThat(fixture.delivery.calls).isEqualTo(1);
        }
    }

    @Test
    void submissionFailureReleasesCapacityAndRestoresDurableEligibility()
    {
        SharedState state = sharedState(4);
        state.handlers.put("validation", new CountingHandler());
        state.tickets.put(requested("submit-failure", "validation", VALIDATION, false));

        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "first", new RejectingExecutorService("rejected"))) {
            dispatcher.runMaintenance();
            assertThat(state.tickets.get("submit-failure").state()).isEqualTo(RETRY_WAIT);
            assertThat(state.capacityStore.activeCount(NOW)).isZero();
        }

        state.clock.advance(Duration.ofSeconds(5));
        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "replacement", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
            assertThat(state.tickets.get("submit-failure").state()).isEqualTo(RESULT_PENDING);
            dispatcher.runMaintenance();
            assertThat(state.tickets.get("submit-failure").state()).isEqualTo(SUCCEEDED);
        }
    }

    @Test
    void deliverySubmissionFailureAtomicallyReleasesItsExactClaim()
    {
        SharedState state = sharedState(4);
        DispatchTicket pending = requested(
                "delivery-submit-failure", "unused", VALIDATION, false)
                .resultPending(success("delivery-submit-failure"), NOW);
        state.tickets.put(pending);

        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "first", new RejectingExecutorService("rejected"))) {
            dispatcher.runMaintenance();
        }

        DispatchTicket retry = state.tickets.get("delivery-submit-failure");
        assertThat(retry.state()).isEqualTo(RESULT_PENDING);
        assertThat(retry.version()).isEqualTo(pending.version() + 1);
        assertThat(retry.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(state.tickets.getDeliveryClaim(retry.id())).isEmpty();
        assertThat(state.capacityStore.activeCount(NOW)).isZero();

        state.clock.advance(Duration.ofSeconds(5));
        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "replacement", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
        }
        assertThat(state.tickets.get(retry.id()).state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void restartRetriesClaimedWorkButReconcilesRunningWork()
    {
        SharedState state = sharedState(4);
        CountingHandler claimedHandler = new CountingHandler();
        CountingHandler runningHandler = new CountingHandler();
        state.handlers.put("claimed", claimedHandler);
        state.handlers.put("running", runningHandler);
        state.tickets.put(expiredClaim(state, requested(
                "claimed", "claimed", VALIDATION, false), false));
        state.tickets.put(expiredClaim(state, requested(
                "running", "running", VALIDATION, false), true));
        state.clock.advance(Duration.ofSeconds(11));

        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "replacement", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
            dispatcher.runMaintenance();

            assertThat(state.tickets.get("claimed").state()).isEqualTo(RESULT_PENDING);
            assertThat(claimedHandler.executeCalls).isEqualTo(1);
            assertThat(claimedHandler.reconcileCalls).isZero();
            assertThat(state.tickets.get("running").state()).isEqualTo(RESULT_PENDING);
            assertThat(runningHandler.executeCalls).isZero();
            assertThat(runningHandler.reconcileCalls).isEqualTo(1);
        }
    }

    @Test
    void restartRedeliversPersistedResultWithoutReexecutingAdapter()
    {
        SharedState state = sharedState(4);
        CountingHandler handler = new CountingHandler();
        state.handlers.put("validation", handler);
        DispatchTicket ticket = requested("result", "validation", VALIDATION, false)
                .resultPending(success("result"), NOW);
        state.tickets.put(ticket);

        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "replacement", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
        }

        assertThat(state.tickets.get("result").state()).isEqualTo(SUCCEEDED);
        assertThat(handler.executeCalls).isZero();
        assertThat(handler.reconcileCalls).isZero();
        assertThat(state.delivery.calls).isEqualTo(1);
    }

    @Test
    void crashAfterExternalDeliveryRedeliversTheSameResultIdempotently()
            throws Exception
    {
        SharedState state = sharedState(4);
        state.handlers.put("delivery-crash", new CountingHandler());
        DispatchTicket resultPending = requested(
                "delivery-crash", "delivery-crash", VALIDATION, false)
                .resultPending(success("delivery-crash"), NOW);
        state.tickets.put(resultPending);
        assertThat(state.tickets.claimDelivery(
                resultPending.id(),
                resultPending.version(),
                "dead-dispatcher",
                NOW,
                NOW.plusSeconds(10))).isPresent();

        state.delivery.deliver(
                resultPending.envelope().owner(),
                resultPending.envelope().fence(),
                resultPending.pendingResult());
        state.clock.advance(Duration.ofSeconds(11));
        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "replacement", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
        }

        assertThat(state.tickets.get("delivery-crash").state()).isEqualTo(SUCCEEDED);
        assertThat(state.delivery.calls).isEqualTo(2);
        assertThat(state.delivery.uniqueAcceptances).isEqualTo(1);
    }

    @Test
    void expiredDeliveryWorkerCannotCompleteAReplacementClaim()
    {
        SharedState state = sharedState(4);
        DispatchTicket resultPending = requested(
                "delivery-race", "unused", VALIDATION, false)
                .resultPending(success("delivery-race"), NOW);
        state.tickets.put(resultPending);
        DispatchDeliveryClaim expired = state.tickets.claimDelivery(
                        resultPending.id(),
                        resultPending.version(),
                        "old-worker",
                        NOW,
                        NOW.plusSeconds(10))
                .orElseThrow();

        Instant replacementTime = NOW.plusSeconds(11);
        assertThat(state.tickets.releaseExpiredDeliveryClaim(expired, replacementTime)).isTrue();
        DispatchDeliveryClaim replacement = state.tickets.claimDelivery(
                        resultPending.id(),
                        resultPending.version(),
                        "new-worker",
                        replacementTime,
                        replacementTime.plusSeconds(10))
                .orElseThrow();
        DispatchTicket completed = resultPending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(ACCEPTED, "{}"), replacementTime);

        assertThat(state.tickets.replaceTicketAndReleaseDeliveryClaim(expired, completed))
                .isFalse();
        assertThat(state.tickets.get("delivery-race").state()).isEqualTo(RESULT_PENDING);
        assertThat(state.tickets.getDeliveryClaim("delivery-race").orElseThrow().claimOwner())
                .isEqualTo("new-worker");
        assertThat(state.tickets.replaceTicketAndReleaseDeliveryClaim(replacement, completed))
                .isTrue();
        assertThat(state.tickets.get("delivery-race").state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void ownerReceivesExpectedFenceSeparatelyFromAValidSubstitutedResult()
    {
        SharedState state = sharedState(4);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.STAGE,
                "shared-stage",
                "accept-operation-result");
        DispatchTicket expected = requested(
                "expected-operation", "unused", VALIDATION, false, owner);
        DispatchTicket substitute = requested(
                "substitute-operation", "unused", VALIDATION, false, owner);
        state.tickets.put(expected.resultPending(
                success(substitute.envelope().fence()), NOW));

        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "dispatcher", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
        }

        DispatchTicket delivered = state.tickets.get(expected.id());
        assertThat(delivered.state()).isEqualTo(SUCCEEDED);
        assertThat(delivered.deliveryReceipt().acceptance()).isEqualTo(SUPERSEDED);
        assertThat(state.delivery.owners).containsExactly(owner);
        assertThat(state.delivery.expectedFences)
                .containsExactly(expected.envelope().fence());
        assertThat(state.delivery.results)
                .extracting(DispatchTicket.DispatchResult::fence)
                .containsExactly(substitute.envelope().fence());
    }

    @Test
    void queuedCancellationNeverLaunchesAndDeliversCanceledResult()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 4)) {
            CountingHandler handler = fixture.register("validation");
            fixture.put(requested("queued-cancel", "validation", VALIDATION, false));

            assertThat(fixture.dispatcher.requestCancel("queued-cancel")).isTrue();
            assertThat(fixture.ticket("queued-cancel").state()).isEqualTo(RESULT_PENDING);
            assertThat(fixture.ticket("queued-cancel").cancelRequestedAt()).isEqualTo(NOW);
            fixture.dispatcher.runMaintenance();

            assertThat(handler.executeCalls).isZero();
            assertThat(fixture.ticket("queued-cancel").state()).isEqualTo(CANCELED);
            assertThat(fixture.delivery.results).extracting(DispatchTicket.DispatchResult::outcome)
                    .containsExactly(DispatchTicket.Outcome.CANCELED);
        }
    }

    @Test
    void cancellationStillReconcilesAnUnknownExternalOutcome()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 4)) {
            CountingHandler handler = fixture.register("reconcile-cancel");
            DispatchTicket requested = requested(
                    "reconcile-cancel", "reconcile-cancel", VALIDATION, false);
            CapacityManager.CapacityLease seedLease = fixture.capacityManager.tryAcquireForTicket(
                    requested.id(), requested.envelope().capacityRequest(), "seed-worker")
                    .lease().orElseThrow();
            DispatchTicket unknown = requested.claim(
                            "seed-worker", seedLease.id(), NOW.plusSeconds(10))
                    .markRunning(NOW)
                    .reconcileWait("unknown external outcome", NOW);
            fixture.capacityManager.release(seedLease.id(), "seed-worker");
            fixture.put(unknown);

            assertThat(fixture.dispatcher.requestCancel("reconcile-cancel")).isTrue();
            assertThat(fixture.ticket("reconcile-cancel").state())
                    .isEqualTo(RECONCILE_WAIT);
            fixture.dispatcher.runMaintenance();

            assertThat(handler.executeCalls).isZero();
            assertThat(handler.reconcileCalls).isEqualTo(1);
            assertThat(fixture.ticket("reconcile-cancel").state())
                    .isEqualTo(RESULT_PENDING);
        }
    }

    @Test
    void canceledStuckExecutionExpiresIntoReconciliation()
            throws Exception
    {
        SharedState state = sharedState(4);
        ExecutorService operations = Executors.newCachedThreadPool();
        CountDownLatch executeStarted = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        CountDownLatch reconcileFinished = new CountDownLatch(1);
        state.handlers.put("stuck", new ExecutionPorts.OperationHandler()
        {
            @Override
            public DispatchTicket.DispatchResult execute(ExecutionContext context)
                    throws Exception
            {
                executeStarted.countDown();
                if (!releaseExecute.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test execution did not resume");
                }
                return success("stuck");
            }

            @Override
            public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            {
                reconcileFinished.countDown();
                return success("stuck");
            }
        });
        state.tickets.put(requested("stuck", "stuck", VALIDATION, false));

        try (ExecutionDispatcher dispatcher = dispatcher(state, "host", operations)) {
            dispatcher.runMaintenance();
            assertThat(executeStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.requestCancel("stuck")).isTrue();

            state.clock.advance(Duration.ofSeconds(11));
            dispatcher.runMaintenance();
            assertThat(reconcileFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(state.evidence.finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(state.tickets.get("stuck").state()).isEqualTo(RESULT_PENDING);

            releaseExecute.countDown();
            operations.shutdown();
            assertThat(operations.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(state.tickets.get("stuck").state()).isEqualTo(RESULT_PENDING);
            assertThat(state.capacityStore.activeCount(state.clock.instant())).isZero();
        }
    }

    @Test
    void lateExpiredWorkerCannotOverwriteNewReconciliationClaim()
            throws Exception
    {
        SharedState state = sharedState(4);
        ExecutorService oldOperations = Executors.newSingleThreadExecutor();
        ExecutorService newOperations = Executors.newSingleThreadExecutor();
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch reconcileStarted = new CountDownLatch(1);
        CountDownLatch releaseReconcile = new CountDownLatch(1);
        state.handlers.put("race", new ExecutionPorts.OperationHandler()
        {
            @Override
            public DispatchTicket.DispatchResult execute(ExecutionContext context)
                    throws Exception
            {
                oldStarted.countDown();
                if (!releaseOld.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("old execution did not resume");
                }
                return result(context, "old-result");
            }

            @Override
            public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
                    throws Exception
            {
                reconcileStarted.countDown();
                if (!releaseReconcile.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("reconciliation did not resume");
                }
                return result(context, "reconciled-result");
            }
        });
        state.tickets.put(requested("race", "race", VALIDATION, false));
        ExecutionDispatcher oldDispatcher = dispatcher(state, "same-host", oldOperations);
        ExecutionDispatcher replacement = null;
        try {
            oldDispatcher.runMaintenance();
            assertThat(oldStarted.await(2, TimeUnit.SECONDS)).isTrue();
            String oldClaimOwner = state.tickets.get("race").claimOwner();

            state.clock.advance(Duration.ofSeconds(11));
            replacement = dispatcher(state, "same-host", newOperations);
            replacement.runMaintenance();
            assertThat(reconcileStarted.await(2, TimeUnit.SECONDS)).isTrue();
            String newClaimOwner = state.tickets.get("race").claimOwner();
            assertThat(newClaimOwner).isNotEqualTo(oldClaimOwner);

            releaseOld.countDown();
            oldOperations.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(state.tickets.get("race").state()).isEqualTo(RUNNING);
            assertThat(state.tickets.get("race").claimOwner()).isEqualTo(newClaimOwner);

            releaseReconcile.countDown();
            newOperations.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(state.tickets.get("race").state()).isEqualTo(RESULT_PENDING);
            assertThat(state.tickets.get("race").pendingResult().payloadJson())
                    .isEqualTo("reconciled-result");
        }
        finally {
            if (replacement != null) {
                replacement.close();
            }
            oldDispatcher.close();
        }
    }

    @Test
    void slowResultDeliveryIsHeartbeatedWithoutTaskCapacity()
            throws Exception
    {
        ExecutorService operations = Executors.newSingleThreadExecutor();
        try (Fixture fixture = fixture(operations, 4)) {
            fixture.delivery.block = true;
            fixture.put(requested("slow-delivery", "unused", LOCAL_GIT, true)
                    .resultPending(success("slow-delivery"), NOW));

            fixture.dispatcher.runMaintenance();
            assertThat(fixture.delivery.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.capacityStore.activeCount(NOW)).isZero();

            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.dispatcher.runMaintenance();
            fixture.clock.advance(Duration.ofSeconds(6));
            fixture.dispatcher.runMaintenance();
            assertThat(fixture.delivery.calls).isEqualTo(1);
            assertThat(fixture.ticket("slow-delivery").state()).isEqualTo(RESULT_PENDING);
            assertThat(fixture.state.tickets.getDeliveryClaim("slow-delivery")
                    .orElseThrow().expiresAt()).isEqualTo(NOW.plusSeconds(21));

            fixture.delivery.release.countDown();
            operations.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(fixture.ticket("slow-delivery").state()).isEqualTo(SUCCEEDED);
        }
    }

    @Test
    void lateSuccessAfterActiveCancellationIsDeliveredForSupersession()
            throws Exception
    {
        ExecutorService operations = Executors.newSingleThreadExecutor();
        try (Fixture fixture = fixture(operations, 4)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger stopCalls = new AtomicInteger();
            fixture.delivery.acceptance = SUPERSEDED;
            fixture.handlers.put("slow", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                        throws Exception
                {
                    context.onCancellation(stopCalls::incrementAndGet);
                    started.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test handler did not resume");
                    }
                    return success("slow");
                }
            });
            fixture.put(requested("slow", "slow", VALIDATION, false));

            fixture.dispatcher.runMaintenance();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.ticket("slow").state()).isEqualTo(RUNNING);
            assertThat(fixture.dispatcher.requestCancel("slow")).isTrue();
            assertThat(stopCalls).hasValue(1);

            release.countDown();
            assertThat(fixture.evidence.finished.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> drained = operations.submit(() -> {});
            drained.get(2, TimeUnit.SECONDS);
            assertThat(fixture.ticket("slow").pendingResult().outcome())
                    .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);

            fixture.dispatcher.runMaintenance();
            operations.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(fixture.ticket("slow").state()).isEqualTo(SUCCEEDED);
            assertThat(fixture.ticket("slow").deliveryReceipt().acceptance())
                    .isEqualTo(SUPERSEDED);
        }
    }

    @Test
    void distinguishesRetryableAndIndeterminateInfrastructureFailures()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 4)) {
            AtomicInteger retryCalls = new AtomicInteger();
            AtomicInteger executeCalls = new AtomicInteger();
            AtomicInteger reconcileCalls = new AtomicInteger();
            fixture.handlers.put("retry", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                        throws Exception
                {
                    if (retryCalls.incrementAndGet() == 1) {
                        throw new ExecutionPorts.RetryableExecutionException("temporary");
                    }
                    return success("retry");
                }
            });
            fixture.handlers.put("indeterminate", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                        throws Exception
                {
                    executeCalls.incrementAndGet();
                    throw new ExecutionPorts.IndeterminateExecutionException("unknown outcome");
                }

                @Override
                public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
                        throws Exception
                {
                    if (reconcileCalls.incrementAndGet() == 1) {
                        throw new ExecutionPorts.RetryableExecutionException(
                                "reconciliation probe unavailable");
                    }
                    return success("indeterminate");
                }
            });
            fixture.put(requested("retry", "retry", VALIDATION, false));
            fixture.put(requested("indeterminate", "indeterminate", VALIDATION, false));

            fixture.dispatcher.runMaintenance();
            fixture.dispatcher.runMaintenance();
            assertThat(fixture.ticket("retry").state()).isEqualTo(RETRY_WAIT);
            assertThat(fixture.ticket("indeterminate").state()).isEqualTo(RECONCILE_WAIT);

            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.dispatcher.runMaintenance();
            fixture.dispatcher.runMaintenance();
            assertThat(fixture.ticket("retry").state()).isEqualTo(RESULT_PENDING);
            assertThat(fixture.ticket("indeterminate").state()).isEqualTo(RECONCILE_WAIT);

            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.dispatcher.runMaintenance();
            fixture.dispatcher.runMaintenance();
            assertThat(fixture.ticket("indeterminate").state()).isEqualTo(RESULT_PENDING);
            assertThat(retryCalls).hasValue(2);
            assertThat(executeCalls).hasValue(1);
            assertThat(reconcileCalls).hasValue(2);
        }
    }

    @Test
    void unexpectedAdapterExceptionRequiresReconciliationAndNeverFailsTheOperation()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 4)) {
            fixture.handlers.put("unexpected", context -> {
                throw new IllegalStateException("adapter crashed after launch");
            });
            fixture.put(requested("unexpected", "unexpected", VALIDATION, false));

            fixture.dispatcher.runMaintenance();

            DispatchTicket ticket = fixture.ticket("unexpected");
            assertThat(ticket.state()).isEqualTo(RECONCILE_WAIT);
            assertThat(ticket.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(ticket.pendingResult()).isNull();
            assertThat(fixture.delivery.calls).isZero();
            assertThat(fixture.evidence.results)
                    .extracting(DispatchTicket.DispatchResult::outcome)
                    .containsExactly(DispatchTicket.Outcome.INDETERMINATE);
        }
    }

    @Test
    void exhaustedReconciliationParksForManualProbeWithoutFailedResult()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 4)) {
            AtomicInteger reconciliationCalls = new AtomicInteger();
            fixture.handlers.put("manual-probe", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                        throws Exception
                {
                    throw new ExecutionPorts.IndeterminateExecutionException(
                            "external outcome unknown");
                }

                @Override
                public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
                        throws Exception
                {
                    reconciliationCalls.incrementAndGet();
                    throw new ExecutionPorts.RetryableExecutionException(
                            "probe unavailable");
                }
            });
            fixture.put(requested("manual-probe", "manual-probe", VALIDATION, false));

            fixture.dispatcher.runMaintenance();
            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.dispatcher.runMaintenance();
            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.dispatcher.runMaintenance();

            DispatchTicket ticket = fixture.ticket("manual-probe");
            assertThat(reconciliationCalls).hasValue(2);
            assertThat(ticket.state()).isEqualTo(RECONCILE_WAIT);
            assertThat(ticket.nextAttemptAt()).isNull();
            assertThat(ticket.pendingResult()).isNull();
            assertThat(ticket.isEligibleAt(NOW.plus(Duration.ofDays(1)))).isFalse();
            assertThat(fixture.delivery.calls).isZero();
            assertThat(fixture.evidence.results)
                    .extracting(DispatchTicket.DispatchResult::outcome)
                    .containsOnly(DispatchTicket.Outcome.INDETERMINATE);
        }
    }

    @Test
    void shutdownParksRunningWorkWithoutInventingSemanticCancellation()
            throws Exception
    {
        SharedState state = sharedState(4);
        ExecutorService operations = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopRequested = new CountDownLatch(1);
        state.handlers.put("shutdown", new ExecutionPorts.OperationHandler()
        {
            @Override
            public DispatchTicket.DispatchResult execute(ExecutionContext context)
                    throws Exception
            {
                context.onCancellation(stopRequested::countDown);
                started.countDown();
                if (!stopRequested.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("dispatcher did not stop the adapter");
                }
                throw new ExecutionPorts.OperationCanceledException("local worker stopped");
            }

            @Override
            public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            {
                return result(context, "reconciled-after-shutdown");
            }
        });
        state.tickets.put(requested("shutdown", "shutdown", VALIDATION, false));

        ExecutionDispatcher original = dispatcher(state, "original", operations);
        original.runMaintenance();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(state.tickets.get("shutdown").state()).isEqualTo(RUNNING);

        original.close();
        assertThat(operations.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        DispatchTicket abandoned = state.tickets.get("shutdown");
        assertThat(abandoned.state()).isEqualTo(RECONCILE_WAIT);
        assertThat(abandoned.cancelRequestedAt()).isNull();
        assertThat(abandoned.pendingResult()).isNull();
        assertThat(state.capacityStore.activeCount(NOW)).isZero();
        assertThat(state.evidence.results)
                .extracting(DispatchTicket.DispatchResult::outcome)
                .contains(DispatchTicket.Outcome.INDETERMINATE)
                .doesNotContain(
                        DispatchTicket.Outcome.CANCELED,
                        DispatchTicket.Outcome.FAILED);

        try (ExecutionDispatcher replacement = dispatcher(
                state, "replacement", new InMemoryExecutionSupport.DirectExecutorService())) {
            replacement.runMaintenance();
            assertThat(state.tickets.get("shutdown").state()).isEqualTo(RESULT_PENDING);
            replacement.runMaintenance();
        }
        assertThat(state.tickets.get("shutdown").state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void boundedScanRotatesPastCapacityBlockedWorkToDeliverResults()
    {
        SharedState state = sharedState(1);
        CapacityManager.CapacityRequest blockerRequest = capacityRequest(
                "blocker", VALIDATION, "blocker-task", false);
        CapacityManager.CapacityLease blocker = state.capacityManager.tryAcquireForTicket(
                "blocker-ticket", blockerRequest, "other-worker").lease().orElseThrow();
        state.tickets.put(requested("a-blocked", "unused", VALIDATION, false));
        state.tickets.put(requested("b-delivery", "unused", VALIDATION, false)
                .resultPending(success("b-delivery"), NOW));

        try (ExecutionDispatcher dispatcher = dispatcher(
                state,
                "dispatcher",
                new InMemoryExecutionSupport.DirectExecutorService(),
                1)) {
            dispatcher.runMaintenance();
            assertThat(state.tickets.get("a-blocked").state()).isEqualTo(REQUESTED);
            assertThat(state.delivery.calls).isZero();

            dispatcher.runMaintenance();
            assertThat(state.tickets.get("b-delivery").state()).isEqualTo(SUCCEEDED);
            assertThat(state.delivery.calls).isEqualTo(1);
            assertThat(state.capacityStore.activeCount(NOW)).isEqualTo(1);
        }
        state.capacityManager.release(blocker.id(), "other-worker");
    }

    @Test
    void boundedPageSelectsAnotherTrunkBeforeItsOlderSiblingAndWraps()
    {
        SharedState state = sharedState(4);
        List<String> launchOrder = new ArrayList<>();
        registerOrderedHandlers(state, launchOrder, "a1", "a2", "b1");
        state.tickets.put(requestedInTrunk(
                "a1", "trunk-a", NOW.minusSeconds(3)));
        state.tickets.put(requestedInTrunk(
                "a2", "trunk-a", NOW.minusSeconds(2)));
        state.tickets.put(requestedInTrunk(
                "b1", "trunk-b", NOW.minusSeconds(1)));

        try (ExecutionDispatcher dispatcher = dispatcher(
                state,
                "dispatcher",
                new InMemoryExecutionSupport.DirectExecutorService(),
                2)) {
            dispatcher.runMaintenance();
            assertThat(launchOrder).containsExactly("a1", "b1");
            assertThat(state.tickets.get("a2").state()).isEqualTo(REQUESTED);

            dispatcher.runMaintenance();
            assertThat(launchOrder).containsExactly("a1", "b1", "a2");

            dispatcher.runMaintenance();
            dispatcher.runMaintenance();
        }

        assertThat(state.tickets.get("a1").state()).isEqualTo(SUCCEEDED);
        assertThat(state.tickets.get("a2").state()).isEqualTo(SUCCEEDED);
        assertThat(state.tickets.get("b1").state()).isEqualTo(SUCCEEDED);
    }

    @Test
    void replacementDispatcherRestartsWithTheSameScopeDiversePage()
    {
        SharedState state = sharedState(4);
        List<String> launchOrder = new ArrayList<>();
        registerOrderedHandlers(state, launchOrder, "restart-a1", "restart-a2", "restart-b1");
        state.tickets.put(requestedInTrunk(
                "restart-a1", "trunk-a", NOW.minusSeconds(3)));
        state.tickets.put(requestedInTrunk(
                "restart-a2", "trunk-a", NOW.minusSeconds(2)));
        state.tickets.put(requestedInTrunk(
                "restart-b1", "trunk-b", NOW.minusSeconds(1)));

        try (ExecutionDispatcher original = dispatcher(
                state,
                "original",
                new InMemoryExecutionSupport.DirectExecutorService(),
                2)) {
            original.runMaintenance();
        }
        assertThat(launchOrder).containsExactly("restart-a1", "restart-b1");

        try (ExecutionDispatcher replacement = dispatcher(
                state,
                "replacement",
                new InMemoryExecutionSupport.DirectExecutorService(),
                2)) {
            replacement.runMaintenance();
            assertThat(state.tickets.get("restart-a1").state()).isEqualTo(SUCCEEDED);
            assertThat(state.tickets.get("restart-b1").state()).isEqualTo(SUCCEEDED);
            assertThat(state.tickets.get("restart-a2").state()).isEqualTo(REQUESTED);

            replacement.runMaintenance();
        }
        assertThat(launchOrder)
                .containsExactly("restart-a1", "restart-b1", "restart-a2");
    }

    @Test
    void sameTrunkTasksCanRunConcurrentlyAcrossConsecutiveSweeps()
            throws Exception
    {
        SharedState state = sharedState(4);
        ExecutorService operations = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        for (String operationId : List.of("parallel-a", "parallel-b")) {
            state.handlers.put(operationId, context -> {
                if (starts.incrementAndGet() == 1) {
                    firstStarted.countDown();
                }
                bothStarted.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("parallel Tasks did not resume");
                }
                return success(operationId);
            });
        }
        state.tickets.put(requestedInTrunk(
                "parallel-a", "shared-trunk", NOW.minusSeconds(2)));
        state.tickets.put(requestedInTrunk(
                "parallel-b", "shared-trunk", NOW.minusSeconds(1)));

        ExecutionDispatcher dispatcher = dispatcher(
                state, "dispatcher", operations, 1);
        try {
            dispatcher.runMaintenance();
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(state.tickets.get("parallel-a").state()).isEqualTo(RUNNING);
            assertThat(state.tickets.get("parallel-b").state()).isEqualTo(REQUESTED);

            dispatcher.runMaintenance();
            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(state.tickets.get("parallel-a").state()).isEqualTo(RUNNING);
            assertThat(state.tickets.get("parallel-b").state()).isEqualTo(RUNNING);
            assertThat(dispatcher.activeExecutionCount()).isEqualTo(2);
            assertThat(state.capacityStore.activeCount(NOW)).isEqualTo(2);
        }
        finally {
            release.countDown();
            dispatcher.close();
            assertThat(operations.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preservesRawResultFenceAndSuppliesLiveWriterTokenToAdapter()
    {
        try (Fixture fixture = fixture(new InMemoryExecutionSupport.DirectExecutorService(), 4)) {
            AtomicLong fencingToken = new AtomicLong();
            fixture.handlers.put("bad-fence", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                {
                    return success("different-operation");
                }
            });
            fixture.handlers.put("writer", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                {
                    fencingToken.set(context.requireWriterFencingToken());
                    return success("writer");
                }
            });
            fixture.put(requested("bad-fence", "bad-fence", VALIDATION, false));
            fixture.put(requested("writer", "writer", LOCAL_GIT, true));

            fixture.dispatcher.runMaintenance();
            fixture.dispatcher.runMaintenance();
            assertThat(fixture.ticket("bad-fence").pendingResult().outcome())
                    .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(fixture.ticket("bad-fence").pendingResult().fence().operationId())
                    .isEqualTo("different-operation");
            assertThat(fencingToken.get()).isPositive();

            fixture.delivery.acceptance = SUPERSEDED;
            fixture.dispatcher.runMaintenance();
            fixture.dispatcher.runMaintenance();
            assertThat(fixture.ticket("bad-fence").state()).isEqualTo(SUCCEEDED);
            assertThat(fixture.ticket("bad-fence").deliveryReceipt().acceptance())
                    .isEqualTo(SUPERSEDED);
            assertThat(fixture.ticket("writer").state()).isEqualTo(SUCCEEDED);
        }
    }

    @Test
    void expiredWriterTokenIsRejectedAtMutationBoundary()
            throws Exception
    {
        ExecutorService operations = Executors.newSingleThreadExecutor();
        try (Fixture fixture = fixture(operations, 4)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch inspectToken = new CountDownLatch(1);
            AtomicInteger staleRejections = new AtomicInteger();
            fixture.handlers.put("expiring-writer", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                        throws Exception
                {
                    started.countDown();
                    if (!inspectToken.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test token inspection did not resume");
                    }
                    try {
                        context.requireWriterFencingToken();
                    }
                    catch (IllegalStateException expected) {
                        staleRejections.incrementAndGet();
                    }
                    return success("expiring-writer");
                }
            });
            fixture.put(requested(
                    "expiring-writer", "expiring-writer", LOCAL_GIT, true));

            fixture.dispatcher.runMaintenance();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            fixture.clock.advance(Duration.ofSeconds(31));
            inspectToken.countDown();
            operations.submit(() -> {}).get(2, TimeUnit.SECONDS);

            assertThat(staleRejections).hasValue(1);
            assertThat(fixture.ticket("expiring-writer").state()).isEqualTo(RUNNING);
        }
    }

    @Test
    void dispatcherHeartbeatsAndReleasesRegisteredWorktreeLease()
            throws Exception
    {
        ExecutorService operations = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = fixture(operations, 4)) {
            InMemoryExecutionSupport.WorktreeStore worktrees =
                    new InMemoryExecutionSupport.WorktreeStore();
            WorktreeWriterLeaseManager writers = new WorktreeWriterLeaseManager(
                    worktrees, fixture.clock);
            AtomicReference<WorktreeWriterLeaseManager.Lease> held =
                    new AtomicReference<>();
            fixture.handlers.put("registered-writer", new ExecutionPorts.OperationHandler()
            {
                @Override
                public DispatchTicket.DispatchResult execute(ExecutionContext context)
                        throws Exception
                {
                    held.set(writers.acquire(context, "/tmp/registered-writer"));
                    started.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test writer did not resume");
                    }
                    writers.authorizeMutation(context, held.get())
                            .run(fence -> {
                                assertThat(fence.worktreePath())
                                        .isEqualTo("/tmp/registered-writer");
                                return null;
                            });
                    return result(context, "done");
                }
            });
            fixture.put(requested(
                    "registered-writer", "registered-writer", LOCAL_GIT, true));

            fixture.dispatcher.runMaintenance();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            Instant firstExpiry = held.get().expiresAt();

            fixture.clock.advance(Duration.ofSeconds(5));
            fixture.dispatcher.runMaintenance();

            assertThat(worktrees.heartbeatCount()).isEqualTo(1);
            assertThat(worktrees.findExact(held.get(), fixture.clock.instant()))
                    .get()
                    .extracting(WorktreeWriterLeaseManager.Lease::expiresAt)
                    .isEqualTo(firstExpiry.plusSeconds(5));

            worktrees.failNextRelease();
            release.countDown();
            operations.submit(() -> {}).get(2, TimeUnit.SECONDS);

            assertThat(worktrees.findExact(held.get(), fixture.clock.instant())).isEmpty();
            assertThat(fixture.ticket("registered-writer").state())
                    .isEqualTo(RESULT_PENDING);
        }
        finally {
            release.countDown();
        }
    }

    @Test
    void evidenceStartFailureRetriesBeforeLaunchingAdapter()
    {
        SharedState state = sharedState(4);
        state.handlers.put("validation", new CountingHandler());
        state.tickets.put(requested("evidence-failure", "validation", VALIDATION, false));
        state.evidence.failStart = true;

        try (ExecutionDispatcher dispatcher = dispatcher(
                state, "dispatcher", new InMemoryExecutionSupport.DirectExecutorService())) {
            dispatcher.runMaintenance();
            assertThat(state.tickets.get("evidence-failure").state()).isEqualTo(RETRY_WAIT);
            assertThat(((CountingHandler) state.handlers.get("validation")).executeCalls)
                    .isZero();
            assertThat(state.capacityStore.activeCount(NOW)).isZero();

            state.evidence.failStart = false;
            state.clock.advance(Duration.ofSeconds(5));
            dispatcher.runMaintenance();
            assertThat(state.tickets.get("evidence-failure").state())
                    .isEqualTo(RESULT_PENDING);
        }
    }

    @Test
    void executionClaimCannotOutliveItsCapacityLease()
    {
        SharedState state = sharedState(4);
        ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
        try {
            assertThatThrownBy(() -> new ExecutionDispatcher(
                    state.capacityManager,
                    state.tickets,
                    ignored -> new CountingHandler(),
                    state.delivery,
                    state.evidence,
                    state.clock,
                    new ExecutionDispatcher.Config(
                            "dispatcher",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(31),
                            Duration.ofSeconds(5),
                            3,
                            100),
                    new InMemoryExecutionSupport.DirectExecutorService(),
                    maintenance))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity lease duration");
        }
        finally {
            maintenance.shutdownNow();
        }
    }

    private static Fixture fixture(ExecutorService operationExecutor, int validationLimit)
    {
        return new Fixture(sharedState(validationLimit), operationExecutor);
    }

    private static SharedState sharedState(int validationLimit)
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        InMemoryExecutionSupport.CapacityStore capacityStore =
                new InMemoryExecutionSupport.CapacityStore();
        AtomicInteger leaseIds = new AtomicInteger();
        CapacityManager capacityManager = new CapacityManager(
                capacityStore,
                () -> CapacityManager.CapacityPolicy.initial(
                        20, 20, Map.of(VALIDATION, validationLimit, LOCAL_GIT, 4)),
                clock,
                Duration.ofSeconds(30),
                () -> "lease-" + leaseIds.incrementAndGet());
        return new SharedState(
                clock,
                capacityStore,
                capacityManager,
                new InMemoryExecutionSupport.TicketStore(),
                new LinkedHashMap<>(),
                new RecordingDelivery(),
                new RecordingEvidence());
    }

    private static ExecutionDispatcher dispatcher(
            SharedState state,
            String dispatcherId,
            ExecutorService operationExecutor)
    {
        return dispatcher(state, dispatcherId, operationExecutor, 100);
    }

    private static ExecutionDispatcher dispatcher(
            SharedState state,
            String dispatcherId,
            ExecutorService operationExecutor,
            int scanLimit)
    {
        ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
        return new ExecutionDispatcher(
                state.capacityManager,
                state.tickets,
                operationKind -> {
                    ExecutionPorts.OperationHandler handler = state.handlers.get(operationKind);
                    if (handler == null) {
                        throw new IllegalArgumentException(
                                "no handler for operation kind " + operationKind);
                    }
                    return handler;
                },
                state.delivery,
                state.evidence,
                state.clock,
                new ExecutionDispatcher.Config(
                        dispatcherId,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(5),
                        3,
                        scanLimit),
                operationExecutor,
                maintenance);
    }

    private static DispatchTicket expiredClaim(
            SharedState state,
            DispatchTicket ticket,
            boolean running)
    {
        CapacityManager.CapacityLease lease = state.capacityManager.tryAcquireForTicket(
                ticket.id(), ticket.envelope().capacityRequest(), "dead-dispatcher")
                .lease().orElseThrow();
        DispatchTicket claimed = ticket.claim(
                "dead-dispatcher", lease.id(), NOW.plusSeconds(10));
        return running ? claimed.markRunning(NOW) : claimed;
    }

    private static DispatchTicket requested(
            String operationId,
            String operationKind,
            CapacityManager.CapacityLane lane,
            boolean writer)
    {
        return requested(
                operationId,
                operationKind,
                lane,
                writer,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        "stage-" + operationId,
                        "accept-operation-result"));
    }

    private static DispatchTicket requested(
            String operationId,
            String operationKind,
            CapacityManager.CapacityLane lane,
            boolean writer,
            DispatchTicket.OwnerReference owner)
    {
        return requested(
                operationId,
                operationKind,
                lane,
                writer,
                owner,
                "workspace",
                "trunk",
                "task-" + operationId,
                NOW);
    }

    private static DispatchTicket requested(
            String operationId,
            String operationKind,
            CapacityManager.CapacityLane lane,
            boolean writer,
            DispatchTicket.OwnerReference owner,
            String workspaceId,
            String trunkId,
            String taskId,
            Instant createdAt)
    {
        DispatchTicket.OperationFence fence = fence(operationId);
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                operationKind,
                lane == LOCAL_GIT
                        ? DispatchTicket.AsyncFamily.LOCAL_GIT
                        : DispatchTicket.AsyncFamily.VALIDATION,
                owner,
                fence,
                capacityRequest(
                        operationId,
                        lane,
                        workspaceId,
                        trunkId,
                        taskId,
                        writer));
        return DispatchTicket.requested(operationId, envelope, createdAt);
    }

    private static DispatchTicket requestedInTrunk(
            String operationId,
            String trunkId,
            Instant createdAt)
    {
        return requested(
                operationId,
                operationId,
                VALIDATION,
                false,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        "stage-" + operationId,
                        "accept-operation-result"),
                "workspace",
                trunkId,
                "task-" + operationId,
                createdAt);
    }

    private static CapacityManager.CapacityRequest capacityRequest(
            String operationId,
            CapacityManager.CapacityLane lane,
            String taskId,
            boolean writer)
    {
        return capacityRequest(
                operationId, lane, "workspace", "trunk", taskId, writer);
    }

    private static CapacityManager.CapacityRequest capacityRequest(
            String operationId,
            CapacityManager.CapacityLane lane,
            String workspaceId,
            String trunkId,
            String taskId,
            boolean writer)
    {
        return new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.V2,
                Set.of(lane),
                new CapacityManager.CapacityScope(
                        workspaceId, trunkId, taskId, 1L),
                false,
                true,
                writer);
    }

    private static void registerOrderedHandlers(
            SharedState state,
            List<String> launchOrder,
            String... operationIds)
    {
        for (String operationId : operationIds) {
            state.handlers.put(operationId, context -> {
                launchOrder.add(operationId);
                return success(operationId);
            });
        }
    }

    private static DispatchTicket.OperationFence fence(String operationId)
    {
        return new DispatchTicket.OperationFence(
                1L,
                "stage-" + operationId,
                1L,
                operationId,
                1,
                "fingerprint",
                "head",
                "base");
    }

    private static DispatchTicket.DispatchResult success(String operationId)
    {
        return success(fence(operationId));
    }

    private static DispatchTicket.DispatchResult success(
            DispatchTicket.OperationFence fence)
    {
        return new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED, "{}", "{}", null);
    }

    private static DispatchTicket.DispatchResult result(
            ExecutionContext context,
            String payload)
    {
        return new DispatchTicket.DispatchResult(
                context.envelope().fence(),
                DispatchTicket.Outcome.SUCCEEDED,
                payload,
                "{}",
                null);
    }

    private static final class Fixture
            implements AutoCloseable
    {
        private final SharedState state;
        private final InMemoryExecutionSupport.MutableClock clock;
        private final InMemoryExecutionSupport.CapacityStore capacityStore;
        private final CapacityManager capacityManager;
        private final Map<String, ExecutionPorts.OperationHandler> handlers;
        private final RecordingDelivery delivery;
        private final RecordingEvidence evidence;
        private final ExecutionDispatcher dispatcher;

        private Fixture(SharedState state, ExecutorService operationExecutor)
        {
            this.state = state;
            this.clock = state.clock;
            this.capacityStore = state.capacityStore;
            this.capacityManager = state.capacityManager;
            this.handlers = state.handlers;
            this.delivery = state.delivery;
            this.evidence = state.evidence;
            this.dispatcher = dispatcher(state, "dispatcher", operationExecutor);
        }

        private CountingHandler register(String operationKind)
        {
            CountingHandler handler = new CountingHandler();
            handlers.put(operationKind, handler);
            return handler;
        }

        private void put(DispatchTicket ticket)
        {
            state.tickets.put(ticket);
        }

        private DispatchTicket ticket(String ticketId)
        {
            return state.tickets.get(ticketId);
        }

        @Override
        public void close()
        {
            dispatcher.close();
        }
    }

    private record SharedState(
            InMemoryExecutionSupport.MutableClock clock,
            InMemoryExecutionSupport.CapacityStore capacityStore,
            CapacityManager capacityManager,
            InMemoryExecutionSupport.TicketStore tickets,
            Map<String, ExecutionPorts.OperationHandler> handlers,
            RecordingDelivery delivery,
            RecordingEvidence evidence) {}

    private static final class CountingHandler
            implements ExecutionPorts.OperationHandler
    {
        private int executeCalls;
        private int reconcileCalls;

        private CountingHandler() {}

        @Override
        public DispatchTicket.DispatchResult execute(ExecutionContext context)
        {
            executeCalls++;
            return success(context.envelope().fence().operationId());
        }

        @Override
        public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
        {
            reconcileCalls++;
            return success(context.envelope().fence().operationId());
        }
    }

    private static final class RecordingDelivery
            implements ExecutionPorts.ResultDeliveryPort
    {
        private final List<DispatchTicket.DispatchResult> results = new ArrayList<>();
        private final List<DispatchTicket.OperationFence> expectedFences = new ArrayList<>();
        private final List<DispatchTicket.OwnerReference> owners = new ArrayList<>();
        private final Set<String> acceptedOperations = new HashSet<>();
        private DispatchTicket.Acceptance acceptance = ACCEPTED;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean block;
        private int calls;
        private int uniqueAcceptances;

        @Override
        public synchronized DispatchTicket.DeliveryReceipt deliver(
                DispatchTicket.OwnerReference owner,
                DispatchTicket.OperationFence expectedFence,
                DispatchTicket.DispatchResult result)
                throws Exception
        {
            calls++;
            owners.add(owner);
            expectedFences.add(expectedFence);
            results.add(result);
            if (acceptedOperations.add(result.fence().operationId())) {
                uniqueAcceptances++;
            }
            if (block) {
                started.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test delivery did not resume");
                }
            }
            DispatchTicket.Acceptance actual = expectedFence.equals(result.fence())
                    ? acceptance
                    : SUPERSEDED;
            return new DispatchTicket.DeliveryReceipt(actual, "{}");
        }
    }

    private static final class RecordingEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        private final AtomicInteger ids = new AtomicInteger();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<DispatchTicket.DispatchResult> results = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();
        private boolean failStart;

        @Override
        public String start(
                DispatchTicket ticket,
                CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose,
                Instant startedAt)
        {
            if (failStart) {
                throw new IllegalStateException("evidence unavailable");
            }
            return "execution-" + ids.incrementAndGet();
        }

        @Override
        public void heartbeat(String executionId, Instant at) {}

        @Override
        public void providerSession(
                String executionId,
                String provider,
                String providerSessionId) {}

        @Override
        public void processStarted(
                String executionId,
                long processPid,
                String logReference) {}

        @Override
        public void appendLog(
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt) {}

        @Override
        public void recordUsage(
                String executionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli) {}

        @Override
        public synchronized void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt)
        {
            if (result != null) {
                results.add(result);
            }
            failures.add(failure);
            finished.countDown();
        }
    }

    private static final class RejectingExecutorService
            extends InMemoryExecutionSupport.DirectExecutorService
    {
        private final String message;

        private RejectingExecutorService(String message)
        {
            this.message = message;
        }

        @Override
        public void execute(Runnable command)
        {
            throw new IllegalStateException(message);
        }
    }
}
