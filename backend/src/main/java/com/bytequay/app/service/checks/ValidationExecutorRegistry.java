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
import com.bytequay.app.developmentflow.execution.LegacyCapacityBridge;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * In-JVM single-flight admission for validation claims: at most one
 * executor per claim key runs in this process, on a dedicated pool so a
 * minute-long verify never occupies a scheduler or event thread. The
 * durable owner/lease CAS on the claim row is the cross-restart
 * guarantee; this registry is the cheap same-process one.
 */
@Component
public class ValidationExecutorRegistry
{
    private final LegacyCapacityBridge capacity;
    private final ConcurrentHashMap<String, InFlightWork> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService pool =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "validation-executor");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService leaseRenewer =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "validation-lease-renewer");
                thread.setDaemon(true);
                return thread;
            });

    /** One admitted executor: its completion future plus the worker
     *  thread while it runs, so a cancellation can interrupt it. */
    private static final class InFlightWork
    {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile Thread worker;
        volatile boolean capacityLost;
    }

    public ValidationExecutorRegistry(LegacyCapacityBridge capacity)
    {
        this.capacity = requireNonNull(capacity, "capacity is null");
    }

    /**
     * Run {@code work} for {@code claimKey} unless this JVM already has
     * it in flight. Returns true when this call admitted the work.
     */
    public boolean submitIfAbsent(
            String claimKey,
            CapacityManager.CapacityRequest request,
            Runnable work)
    {
        requireNonNull(claimKey, "claimKey is null");
        requireNonNull(request, "request is null");
        requireNonNull(work, "work is null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "validation capacity must be acquired outside a database transaction");
        }
        if (!request.operationId().equals(operationId(claimKey))
                || request.source() != CapacityManager.WorkflowSource.LEGACY
                || !request.lanes().equals(Set.of(CapacityManager.CapacityLane.VALIDATION))) {
            throw new IllegalArgumentException(
                    "validation request does not match its exact claim identity");
        }
        InFlightWork mine = new InFlightWork();
        InFlightWork existing = inFlight.putIfAbsent(claimKey, mine);
        if (existing != null) {
            return false;
        }
        Optional<LegacyCapacityBridge.Permit> admitted;
        try {
            admitted = capacity.tryAcquire(
                    request,
                    request.operationId(),
                    () -> stopForCapacityLoss(mine));
        }
        catch (RuntimeException e) {
            inFlight.remove(claimKey, mine);
            throw e;
        }
        if (admitted.isEmpty()) {
            inFlight.remove(claimKey, mine);
            return false;
        }
        LegacyCapacityBridge.Permit permit = admitted.orElseThrow();
        try {
            pool.execute(() -> runAdmitted(claimKey, mine, permit, work));
        }
        catch (RuntimeException e) {
            inFlight.remove(claimKey, mine);
            closeAfterSubmissionFailure(permit, e);
            throw e;
        }
        return true;
    }

    public static String operationId(String claimKey)
    {
        requireNonNull(claimKey, "claimKey is null");
        if (claimKey.isBlank()) {
            throw new IllegalArgumentException("claimKey must not be blank");
        }
        return "legacy-validation:" + claimKey;
    }

    public boolean isInFlight(String claimKey)
    {
        return inFlight.containsKey(requireNonNull(claimKey, "claimKey is null"));
    }

    /**
     * Interrupt the in-flight executor for {@code claimKey}, if any.
     * The shell runner kills its child process on interrupt, so this is
     * a complete stop request; absence still needs the durable proof.
     * Returns true when a live worker was signalled.
     */
    public boolean requestStop(String claimKey)
    {
        InFlightWork work = inFlight.get(requireNonNull(claimKey, "claimKey is null"));
        if (work == null) {
            return false;
        }
        Thread worker = work.worker;
        if (worker == null) {
            return false;
        }
        worker.interrupt();
        return true;
    }

    /** Schedule a repeating lease-renewal tick; cancel it when done. */
    public ScheduledFuture<?> scheduleLeaseRenewal(Runnable renew, long periodMillis)
    {
        requireNonNull(renew, "renew is null");
        return leaseRenewer.scheduleAtFixedRate(renew, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    private void runAdmitted(
            String claimKey,
            InFlightWork mine,
            LegacyCapacityBridge.Permit permit,
            Runnable work)
    {
        mine.worker = Thread.currentThread();
        try {
            if (mine.capacityLost) {
                throw new IllegalStateException("validation capacity lease was lost before launch");
            }
            permit.lease();
            work.run();
            mine.done.complete(null);
        }
        catch (Throwable t) {
            mine.done.completeExceptionally(t);
        }
        finally {
            mine.worker = null;
            try {
                permit.close();
            }
            catch (RuntimeException e) {
                mine.done.completeExceptionally(e);
            }
            inFlight.remove(claimKey, mine);
        }
    }

    private static void stopForCapacityLoss(InFlightWork work)
    {
        work.capacityLost = true;
        Thread worker = work.worker;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private static void closeAfterSubmissionFailure(
            LegacyCapacityBridge.Permit permit,
            RuntimeException submissionFailure)
    {
        try {
            permit.close();
        }
        catch (RuntimeException releaseFailure) {
            submissionFailure.addSuppressed(releaseFailure);
        }
    }
}
