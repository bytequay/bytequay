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

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/** Admission-only bridge for legacy workers; it contains no workflow behavior. */
public final class LegacyCapacityBridge
        implements AutoCloseable
{
    private final CapacityManager capacityManager;
    private final ConcurrentHashMap<String, Permit> activePermits =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingRelease> pendingReleases =
            new ConcurrentHashMap<>();

    public LegacyCapacityBridge(CapacityManager capacityManager)
    {
        this.capacityManager = requireNonNull(capacityManager, "capacityManager is null");
    }

    public Optional<Permit> tryAcquire(
            CapacityManager.CapacityRequest request,
            String leaseOwner)
    {
        return tryAcquire(request, leaseOwner, () -> {});
    }

    /**
     * Acquires and registers one LEGACY permit. The stop callback is invoked
     * exactly once when the bridge can prove that the lease was lost.
     */
    public Optional<Permit> tryAcquire(
            CapacityManager.CapacityRequest request,
            String leaseOwner,
            Runnable stopOnLeaseLoss)
    {
        requireNonNull(request, "request is null");
        if (request.source() != CapacityManager.WorkflowSource.LEGACY) {
            throw new IllegalArgumentException("legacy bridge requires a LEGACY request");
        }
        requireNonNull(leaseOwner, "leaseOwner is null");
        requireNonNull(stopOnLeaseLoss, "stopOnLeaseLoss is null");
        return capacityManager.tryAcquire(request, leaseOwner).lease()
                .map(lease -> register(new Permit(
                        capacityManager,
                        request,
                        leaseOwner,
                        lease,
                        stopOnLeaseLoss,
                        activePermits)));
    }

    public long availabilityVersion()
    {
        return capacityManager.availabilityVersion();
    }

    public CapacityManager.AvailabilityRegistration onCapacityAvailable(
            Runnable listener)
    {
        return capacityManager.onCapacityAvailable(listener);
    }

    /** Release an exact stable operation left by restart/orphan recovery. */
    public boolean releaseOperation(String operationId, String leaseOwner)
    {
        requireNonNull(operationId, "operationId is null");
        requireNonNull(leaseOwner, "leaseOwner is null");
        try {
            boolean released = capacityManager.releaseLegacyOperation(
                    operationId, leaseOwner);
            pendingReleases.remove(operationId, new PendingRelease(operationId, leaseOwner));
            return released;
        }
        catch (RuntimeException e) {
            pendingReleases.put(operationId, new PendingRelease(operationId, leaseOwner));
            throw e;
        }
    }

    /**
     * One quick Spring-scheduler tick: expire stale durable rows, renew every
     * registered LEGACY permit, and stop a worker whose lease is definitively
     * gone. Transient store failures remain retryable until local expiry.
     */
    public void maintainLeases()
    {
        pendingReleases.values().forEach(release -> {
            try {
                capacityManager.releaseLegacyOperation(
                        release.operationId(), release.leaseOwner());
                pendingReleases.remove(release.operationId(), release);
            }
            catch (RuntimeException ignored) {
                // Retry the exact stable identity on the next maintenance tick.
            }
        });
        activePermits.values().forEach(Permit::maintain);
        try {
            // Registered workers are stopped before expiry publishes the
            // capacity-available hint. This avoids waking a replacement while
            // the old local worker has not yet received its stop signal.
            capacityManager.expireLeases();
        }
        catch (RuntimeException ignored) {
            // Each permit already applied its local-expiry fail-closed rule.
        }
    }

    int activePermitCount()
    {
        return activePermits.size();
    }

    @Override
    public void close()
    {
        activePermits.values().forEach(permit -> {
            try {
                permit.close();
            }
            catch (RuntimeException ignored) {
                // Process shutdown cannot retry; the short lease still expires.
            }
        });
        pendingReleases.values().forEach(release -> {
            try {
                capacityManager.releaseLegacyOperation(
                        release.operationId(), release.leaseOwner());
            }
            catch (RuntimeException ignored) {
                // The durable lease expires shortly after process shutdown.
            }
        });
    }

    private Permit register(Permit permit)
    {
        Permit existing = activePermits.putIfAbsent(permit.leaseId(), permit);
        if (existing != null) {
            throw new IllegalStateException(
                    "legacy capacity lease is already registered: " + permit.leaseId());
        }
        return permit;
    }

    private record PendingRelease(String operationId, String leaseOwner) {}

    public static final class Permit
            implements AutoCloseable
    {
        private final CapacityManager manager;
        private final CapacityManager.CapacityRequest request;
        private final String leaseOwner;
        private final Runnable stopOnLeaseLoss;
        private final ConcurrentHashMap<String, Permit> registry;
        private CapacityManager.CapacityLease lease;
        private boolean releaseRequested;
        private boolean closed;
        private boolean lossNotified;

        private Permit(
                CapacityManager manager,
                CapacityManager.CapacityRequest request,
                String leaseOwner,
                CapacityManager.CapacityLease lease,
                Runnable stopOnLeaseLoss,
                ConcurrentHashMap<String, Permit> registry)
        {
            this.manager = manager;
            this.request = request;
            this.leaseOwner = leaseOwner;
            this.lease = lease;
            this.stopOnLeaseLoss = stopOnLeaseLoss;
            this.registry = registry;
        }

        public synchronized CapacityManager.CapacityLease lease()
        {
            if (closed || releaseRequested) {
                throw new IllegalStateException("legacy capacity permit is closed");
            }
            return manager.requireExactLease(lease.id(), request, leaseOwner);
        }

        public synchronized void heartbeat()
        {
            if (closed || releaseRequested) {
                return;
            }
            lease = manager.heartbeat(lease.id(), leaseOwner)
                    .orElseThrow(() -> new IllegalStateException(
                            "legacy capacity lease is no longer active"));
        }

        @Override
        public synchronized void close()
        {
            if (closed) {
                return;
            }
            releaseRequested = true;
            try {
                requireReleased(manager.release(lease.id(), leaseOwner));
                markClosed();
            }
            catch (RuntimeException e) {
                // Remain registered so the maintenance tick retries release
                // without extending a lease for work that already stopped.
                throw e;
            }
        }

        private String leaseId()
        {
            return lease.id();
        }

        private void maintain()
        {
            Runnable lostCallback = null;
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (releaseRequested) {
                    try {
                        requireReleased(manager.release(lease.id(), leaseOwner));
                        markClosed();
                    }
                    catch (RuntimeException ignored) {
                        if (!manager.currentInstant().isBefore(lease.expiresAt())) {
                            markClosed();
                        }
                    }
                    return;
                }
                try {
                    Optional<CapacityManager.CapacityLease> renewed =
                            manager.heartbeat(lease.id(), leaseOwner);
                    if (renewed.isPresent()) {
                        lease = renewed.orElseThrow();
                    }
                    else {
                        lostCallback = markLost();
                    }
                }
                catch (RuntimeException ignored) {
                    if (!manager.currentInstant().isBefore(lease.expiresAt())) {
                        lostCallback = markLost();
                    }
                }
            }
            if (lostCallback != null) {
                try {
                    lostCallback.run();
                }
                catch (RuntimeException ignored) {
                    // Stopping is best effort and already recorded exactly once.
                }
            }
        }

        private Runnable markLost()
        {
            if (lossNotified) {
                return null;
            }
            lossNotified = true;
            closed = true;
            registry.remove(lease.id(), this);
            return stopOnLeaseLoss;
        }

        private void markClosed()
        {
            closed = true;
            registry.remove(lease.id(), this);
        }

        private void requireReleased(boolean released)
        {
            if (!released) {
                throw new IllegalStateException(
                        "exact legacy capacity release was rejected: " + lease.id());
            }
        }
    }
}
