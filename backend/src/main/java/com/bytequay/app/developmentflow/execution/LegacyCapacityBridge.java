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
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/** Admission-only bridge for legacy workers; it contains no workflow behavior. */
public final class LegacyCapacityBridge
{
    private final CapacityManager capacityManager;

    public LegacyCapacityBridge(CapacityManager capacityManager)
    {
        this.capacityManager = requireNonNull(capacityManager, "capacityManager is null");
    }

    public Optional<Permit> tryAcquire(
            CapacityManager.CapacityRequest request,
            String leaseOwner)
    {
        requireNonNull(request, "request is null");
        if (request.source() != CapacityManager.WorkflowSource.LEGACY) {
            throw new IllegalArgumentException("legacy bridge requires a LEGACY request");
        }
        requireNonNull(leaseOwner, "leaseOwner is null");
        return capacityManager.tryAcquire(request, leaseOwner).lease()
                .map(lease -> new Permit(capacityManager, request, leaseOwner, lease));
    }

    public static final class Permit
            implements AutoCloseable
    {
        private final CapacityManager manager;
        private final CapacityManager.CapacityRequest request;
        private final String leaseOwner;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile CapacityManager.CapacityLease lease;

        private Permit(
                CapacityManager manager,
                CapacityManager.CapacityRequest request,
                String leaseOwner,
                CapacityManager.CapacityLease lease)
        {
            this.manager = manager;
            this.request = request;
            this.leaseOwner = leaseOwner;
            this.lease = lease;
        }

        public CapacityManager.CapacityLease lease()
        {
            if (closed.get()) {
                throw new IllegalStateException("legacy capacity permit is closed");
            }
            return manager.requireExactLease(lease.id(), request, leaseOwner);
        }

        public void heartbeat()
        {
            if (closed.get()) {
                return;
            }
            lease = manager.heartbeat(lease.id(), leaseOwner)
                    .orElseThrow(() -> new IllegalStateException(
                            "legacy capacity lease is no longer active"));
        }

        @Override
        public void close()
        {
            if (closed.compareAndSet(false, true)) {
                manager.release(lease.id(), leaseOwner);
            }
        }
    }
}
