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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

final class InMemoryExecutionSupport
{
    private InMemoryExecutionSupport() {}

    static final class MutableClock
            extends Clock
    {
        private Instant instant;

        MutableClock(Instant instant)
        {
            this.instant = instant;
        }

        void advance(Duration duration)
        {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant()
        {
            return instant;
        }
    }

    static final class CapacityStore
            implements CapacityManager.CapacityLeaseStore
    {
        private final Map<String, CapacityManager.CapacityLease> leases =
                new LinkedHashMap<>();
        private final Map<String, Long> writerTokens = new LinkedHashMap<>();

        @Override
        public synchronized List<CapacityManager.CapacityLease> listActive(Instant now)
        {
            return leases.values().stream()
                    .filter(lease -> lease.isActiveAt(now))
                    .toList();
        }

        @Override
        public synchronized Optional<CapacityManager.CapacityLease> findActiveByOperation(
                String operationId,
                Instant now)
        {
            return leases.values().stream()
                    .filter(lease -> lease.operationId().equals(operationId))
                    .filter(lease -> lease.isActiveAt(now))
                    .findFirst();
        }

        @Override
        public synchronized Optional<CapacityManager.CapacityLease> findById(String leaseId)
        {
            return Optional.ofNullable(leases.get(leaseId));
        }

        @Override
        public synchronized Optional<CapacityManager.CapacityLease> create(
                CapacityManager.CapacityLeaseDraft draft)
        {
            CapacityManager.CapacityRequest request = draft.request();
            if (leases.values().stream()
                    .anyMatch(lease -> lease.isActiveAt(draft.acquiredAt())
                            && lease.operationId().equals(request.operationId()))) {
                return Optional.empty();
            }
            if (request.exclusiveTask() && leases.values().stream()
                    .anyMatch(lease -> lease.isActiveAt(draft.acquiredAt())
                            && lease.exclusiveTask()
                            && request.scope().taskId().equals(lease.scope().taskId()))) {
                return Optional.empty();
            }
            Long token = request.writerRequired()
                    ? writerTokens.merge(request.scope().taskId(), 1L, Long::sum)
                    : null;
            CapacityManager.CapacityLease lease = new CapacityManager.CapacityLease(
                    draft.id(),
                    request.operationId(),
                    request.source(),
                    request.lanes(),
                    request.scope(),
                    request.trunkControl(),
                    request.exclusiveTask(),
                    request.writerRequired(),
                    draft.leaseOwner(),
                    token,
                    draft.acquiredAt(),
                    draft.acquiredAt(),
                    draft.expiresAt(),
                    null);
            leases.put(lease.id(), lease);
            return Optional.of(lease);
        }

        @Override
        public synchronized Optional<CapacityManager.CapacityLease> heartbeat(
                String leaseId,
                String leaseOwner,
                Instant heartbeatAt,
                Instant expiresAt)
        {
            CapacityManager.CapacityLease current = leases.get(leaseId);
            if (current == null
                    || !current.leaseOwner().equals(leaseOwner)
                    || !current.isActiveAt(heartbeatAt)) {
                return Optional.empty();
            }
            CapacityManager.CapacityLease updated = copy(
                    current, heartbeatAt, expiresAt, null);
            leases.put(leaseId, updated);
            return Optional.of(updated);
        }

        @Override
        public synchronized boolean release(
                String leaseId,
                String leaseOwner,
                Instant releasedAt)
        {
            CapacityManager.CapacityLease current = leases.get(leaseId);
            if (current == null || current.releasedAt() != null) {
                return true;
            }
            if (!current.leaseOwner().equals(leaseOwner)) {
                return false;
            }
            leases.put(leaseId, copy(
                    current, current.heartbeatAt(), current.expiresAt(), releasedAt));
            return true;
        }

        @Override
        public synchronized List<CapacityManager.CapacityLease> expire(Instant now)
        {
            List<CapacityManager.CapacityLease> expired = new ArrayList<>();
            leases.replaceAll((id, current) -> {
                if (current.releasedAt() == null && !current.expiresAt().isAfter(now)) {
                    CapacityManager.CapacityLease updated = copy(
                            current, current.heartbeatAt(), current.expiresAt(), now);
                    expired.add(updated);
                    return updated;
                }
                return current;
            });
            return expired;
        }

        synchronized int activeCount(Instant now)
        {
            return listActive(now).size();
        }

        private static CapacityManager.CapacityLease copy(
                CapacityManager.CapacityLease current,
                Instant heartbeatAt,
                Instant expiresAt,
                Instant releasedAt)
        {
            return new CapacityManager.CapacityLease(
                    current.id(),
                    current.operationId(),
                    current.source(),
                    current.lanes(),
                    current.scope(),
                    current.trunkControl(),
                    current.exclusiveTask(),
                    current.writerRequired(),
                    current.leaseOwner(),
                    current.writerFencingToken(),
                    current.acquiredAt(),
                    heartbeatAt,
                    expiresAt,
                    releasedAt);
        }
    }

    static final class TicketStore
            implements ExecutionPorts.DispatchTicketStore
    {
        private final Map<String, DispatchTicket> tickets = new LinkedHashMap<>();

        synchronized void put(DispatchTicket ticket)
        {
            tickets.put(ticket.id(), ticket);
        }

        @Override
        public synchronized List<DispatchTicket> findEligible(Instant now, int limit)
        {
            return tickets.values().stream()
                    .filter(ticket -> ticket.isEligibleAt(now))
                    .sorted(Comparator.comparing(DispatchTicket::createdAt)
                            .thenComparing(DispatchTicket::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized List<DispatchTicket> findExpiredClaims(Instant now, int limit)
        {
            return tickets.values().stream()
                    .filter(ticket -> ticket.hasExpiredClaimAt(now))
                    .sorted(Comparator.comparing(DispatchTicket::claimExpiresAt)
                            .thenComparing(DispatchTicket::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized Optional<DispatchTicket> findById(String ticketId)
        {
            return Optional.ofNullable(tickets.get(ticketId));
        }

        @Override
        public synchronized boolean compareAndSet(
                String ticketId,
                long expectedVersion,
                DispatchTicket replacement)
        {
            DispatchTicket current = tickets.get(ticketId);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            if (!ticketId.equals(replacement.id())
                    || replacement.version() != expectedVersion + 1) {
                throw new IllegalArgumentException("invalid ticket replacement");
            }
            tickets.put(ticketId, replacement);
            return true;
        }

        synchronized DispatchTicket get(String ticketId)
        {
            return tickets.get(ticketId);
        }
    }

    static class DirectExecutorService
            extends AbstractExecutorService
    {
        private boolean shutdown;

        @Override
        public void shutdown()
        {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow()
        {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown()
        {
            return shutdown;
        }

        @Override
        public boolean isTerminated()
        {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
        {
            return shutdown;
        }

        @Override
        public void execute(Runnable command)
        {
            if (shutdown) {
                throw new IllegalStateException("executor is shut down");
            }
            command.run();
        }
    }
}
