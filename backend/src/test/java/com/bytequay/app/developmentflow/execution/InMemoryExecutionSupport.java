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
import java.util.function.Function;

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
        public synchronized <T> T inAdmissionTransaction(
                Function<CapacityManager.CapacityLeaseStore, T> work)
        {
            return work.apply(this);
        }

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
                    draft.ticketId(),
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
                    null,
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
                    current, heartbeatAt, expiresAt, null, null);
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
                    current,
                    current.heartbeatAt(),
                    current.expiresAt(),
                    releasedAt,
                    "RELEASED"));
            return true;
        }

        @Override
        public synchronized List<CapacityManager.CapacityLease> expire(Instant now)
        {
            List<CapacityManager.CapacityLease> expired = new ArrayList<>();
            leases.replaceAll((id, current) -> {
                if (current.releasedAt() == null && !current.expiresAt().isAfter(now)) {
                    CapacityManager.CapacityLease updated = copy(
                            current,
                            current.heartbeatAt(),
                            current.expiresAt(),
                            now,
                            "EXPIRED");
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
                Instant releasedAt,
                String releaseReason)
        {
            return new CapacityManager.CapacityLease(
                    current.id(),
                    current.ticketId(),
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
                    releasedAt,
                    releaseReason);
        }
    }

    static final class WorktreeStore
            implements WorktreeWriterLeaseManager.Store
    {
        private final Map<String, WorktreeWriterLeaseManager.Lease> byPath =
                new LinkedHashMap<>();
        private int heartbeats;
        private boolean failNextRelease;

        @Override
        public synchronized Optional<WorktreeWriterLeaseManager.Lease> tryAcquire(
                WorktreeWriterLeaseManager.Lease requested,
                Instant now)
        {
            byPath.values().removeIf(lease -> !lease.isActiveAt(now));
            if (byPath.containsKey(requested.worktreePath())
                    || byPath.values().stream().anyMatch(
                            lease -> lease.taskId().equals(requested.taskId()))) {
                return Optional.empty();
            }
            byPath.put(requested.worktreePath(), requested);
            return Optional.of(requested);
        }

        @Override
        public synchronized Optional<WorktreeWriterLeaseManager.Lease> findExact(
                WorktreeWriterLeaseManager.Lease expected,
                Instant now)
        {
            return Optional.ofNullable(byPath.get(expected.worktreePath()))
                    .filter(expected::sameIdentity);
        }

        @Override
        public synchronized Optional<WorktreeWriterLeaseManager.Lease> heartbeat(
                WorktreeWriterLeaseManager.Lease expected,
                Instant heartbeatAt,
                Instant expiresAt)
        {
            WorktreeWriterLeaseManager.Lease current = byPath.get(
                    expected.worktreePath());
            if (current == null
                    || !expected.sameIdentity(current)
                    || !current.isActiveAt(heartbeatAt)
                    || !expiresAt.isAfter(heartbeatAt)) {
                return Optional.empty();
            }
            WorktreeWriterLeaseManager.Lease updated =
                    new WorktreeWriterLeaseManager.Lease(
                            current.worktreePath(),
                            current.taskId(),
                            current.operationId(),
                            current.taskEpoch(),
                            current.fencingToken(),
                            current.leaseOwner(),
                            current.acquiredAt(),
                            expiresAt);
            byPath.put(updated.worktreePath(), updated);
            heartbeats++;
            return Optional.of(updated);
        }

        @Override
        public synchronized boolean release(
                WorktreeWriterLeaseManager.Lease expected,
                Instant releasedAt)
        {
            if (failNextRelease) {
                failNextRelease = false;
                throw new IllegalStateException("test release failed");
            }
            WorktreeWriterLeaseManager.Lease current = byPath.get(
                    expected.worktreePath());
            if (current != null && expected.sameIdentity(current)) {
                byPath.remove(expected.worktreePath());
            }
            return true;
        }

        synchronized int heartbeatCount()
        {
            return heartbeats;
        }

        synchronized void failNextRelease()
        {
            failNextRelease = true;
        }
    }

    static final class TicketStore
            implements ExecutionPorts.DispatchTicketStore
    {
        private static final String UNSCOPED = "\u0000";
        private static final Comparator<DispatchTicket> TICKET_FIFO = Comparator
                .comparing(DispatchTicket::createdAt)
                .thenComparing(DispatchTicket::id);
        private static final Comparator<ExecutionPorts.TicketScanCursor> CURSOR_ORDER =
                Comparator.comparingInt(ExecutionPorts.TicketScanCursor::candidateRound)
                        .thenComparingInt(ExecutionPorts.TicketScanCursor::trunkRound)
                        .thenComparing(ExecutionPorts.TicketScanCursor::workspaceOrderKey)
                        .thenComparing(ExecutionPorts.TicketScanCursor::trunkOrderKey)
                        .thenComparing(ExecutionPorts.TicketScanCursor::createdAt)
                        .thenComparing(ExecutionPorts.TicketScanCursor::ticketId);

        private final Map<String, DispatchTicket> tickets = new LinkedHashMap<>();
        private final Map<String, DispatchDeliveryClaim> deliveryClaims =
                new LinkedHashMap<>();

        synchronized void put(DispatchTicket ticket)
        {
            tickets.put(ticket.id(), ticket);
        }

        @Override
        public synchronized ExecutionPorts.TicketScanPage findEligiblePage(
                Instant now,
                ExecutionPorts.TicketScanCursor cursor,
                int limit)
        {
            if (limit < 1) {
                throw new IllegalArgumentException("limit must be positive");
            }
            Map<ScopeKey, List<DispatchTicket>> byTrunk = new LinkedHashMap<>();
            tickets.values().stream()
                    .filter(ticket -> ticket.isEligibleAt(now))
                    .forEach(ticket -> byTrunk.computeIfAbsent(
                                    scope(ticket), ignored -> new ArrayList<>())
                            .add(ticket));

            Map<String, List<ScopeKey>> scopesByWorkspace = new LinkedHashMap<>();
            byTrunk.keySet().forEach(scope -> scopesByWorkspace
                    .computeIfAbsent(scope.workspaceKey(), ignored -> new ArrayList<>())
                    .add(scope));
            scopesByWorkspace.values().forEach(scopes -> scopes.sort(
                    Comparator.comparing(ScopeKey::trunkKey)));

            List<RankedTicket> ranked = new ArrayList<>();
            scopesByWorkspace.forEach((workspaceKey, workspaceScopes) -> {
                for (int trunkRound = 0; trunkRound < workspaceScopes.size(); trunkRound++) {
                    ScopeKey scope = workspaceScopes.get(trunkRound);
                    Map<ScanClass, DispatchTicket> classHeads = new LinkedHashMap<>();
                    byTrunk.get(scope).stream()
                            .sorted(TICKET_FIFO)
                            .forEach(ticket -> classHeads.putIfAbsent(
                                    scanClass(ticket), ticket));
                    List<DispatchTicket> heads = classHeads.values().stream()
                            .sorted(TICKET_FIFO)
                            .toList();
                    for (int candidateRound = 0;
                            candidateRound < heads.size();
                            candidateRound++) {
                        DispatchTicket ticket = heads.get(candidateRound);
                        ranked.add(new RankedTicket(
                                ticket,
                                new ExecutionPorts.TicketScanCursor(
                                        candidateRound,
                                        trunkRound,
                                        workspaceKey,
                                        scope.trunkKey(),
                                        ticket.createdAt(),
                                        ticket.id())));
                    }
                }
            });
            List<RankedTicket> page = ranked.stream()
                    .sorted(Comparator.comparing(RankedTicket::cursor, CURSOR_ORDER))
                    .filter(candidate -> cursor == null
                            || CURSOR_ORDER.compare(candidate.cursor(), cursor) > 0)
                    .limit(limit)
                    .toList();
            return new ExecutionPorts.TicketScanPage(
                    page.stream().map(RankedTicket::ticket).toList(),
                    page.isEmpty() ? null : page.get(page.size() - 1).cursor());
        }

        private static ScopeKey scope(DispatchTicket ticket)
        {
            CapacityManager.CapacityScope scope =
                    ticket.envelope().capacityRequest().scope();
            return new ScopeKey(
                    orderKey(scope.workspaceId()), orderKey(scope.trunkId()));
        }

        private static ScanClass scanClass(DispatchTicket ticket)
        {
            if (ticket.state() == DispatchTicket.State.RESULT_PENDING) {
                return ScanClass.DELIVERY;
            }
            if (ticket.envelope().capacityRequest().trunkControl()) {
                return ScanClass.TRUNK_CONTROL;
            }
            return ScanClass.ORDINARY;
        }

        private static String orderKey(String value)
        {
            return value == null ? UNSCOPED : value;
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
        public synchronized List<DispatchDeliveryClaim> findExpiredDeliveryClaims(
                Instant now,
                int limit)
        {
            return deliveryClaims.values().stream()
                    .filter(claim -> claim.isExpiredAt(now))
                    .sorted(Comparator.comparing(DispatchDeliveryClaim::expiresAt)
                            .thenComparing(DispatchDeliveryClaim::ticketId))
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
            if (deliveryClaims.containsKey(ticketId)) {
                return false;
            }
            if (!ticketId.equals(replacement.id())
                    || replacement.version() != expectedVersion + 1) {
                throw new IllegalArgumentException("invalid ticket replacement");
            }
            tickets.put(ticketId, replacement);
            return true;
        }

        @Override
        public synchronized Optional<DispatchDeliveryClaim> claimDelivery(
                String ticketId,
                long ticketVersion,
                String claimOwner,
                Instant claimedAt,
                Instant expiresAt)
        {
            DispatchTicket ticket = tickets.get(ticketId);
            if (ticket == null
                    || ticket.version() != ticketVersion
                    || ticket.state() != DispatchTicket.State.RESULT_PENDING
                    || deliveryClaims.containsKey(ticketId)) {
                return Optional.empty();
            }
            DispatchDeliveryClaim claim = new DispatchDeliveryClaim(
                    ticketId,
                    ticketVersion,
                    claimOwner,
                    claimedAt,
                    claimedAt,
                    expiresAt);
            deliveryClaims.put(ticketId, claim);
            return Optional.of(claim);
        }

        @Override
        public synchronized Optional<DispatchDeliveryClaim> heartbeatDeliveryClaim(
                DispatchDeliveryClaim claim,
                Instant heartbeatAt,
                Instant expiresAt)
        {
            DispatchDeliveryClaim current = deliveryClaims.get(claim.ticketId());
            if (!sameDeliveryClaim(current, claim) || current.isExpiredAt(heartbeatAt)) {
                return Optional.empty();
            }
            DispatchDeliveryClaim updated = new DispatchDeliveryClaim(
                    current.ticketId(),
                    current.ticketVersion(),
                    current.claimOwner(),
                    current.claimedAt(),
                    heartbeatAt,
                    expiresAt);
            deliveryClaims.put(updated.ticketId(), updated);
            return Optional.of(updated);
        }

        @Override
        public synchronized boolean replaceTicketAndReleaseDeliveryClaim(
                DispatchDeliveryClaim claim,
                DispatchTicket replacement)
        {
            DispatchDeliveryClaim currentClaim = deliveryClaims.get(claim.ticketId());
            DispatchTicket currentTicket = tickets.get(claim.ticketId());
            if (!sameDeliveryClaim(currentClaim, claim)
                    || currentTicket == null
                    || !claim.owns(currentTicket)) {
                return false;
            }
            if (!claim.ticketId().equals(replacement.id())
                    || replacement.version() != claim.ticketVersion() + 1) {
                throw new IllegalArgumentException("invalid delivery ticket replacement");
            }
            deliveryClaims.remove(claim.ticketId());
            tickets.put(claim.ticketId(), replacement);
            return true;
        }

        @Override
        public synchronized boolean releaseExpiredDeliveryClaim(
                DispatchDeliveryClaim claim,
                Instant expiredAt)
        {
            DispatchDeliveryClaim current = deliveryClaims.get(claim.ticketId());
            if (!sameDeliveryClaim(current, claim) || !current.isExpiredAt(expiredAt)) {
                return false;
            }
            deliveryClaims.remove(claim.ticketId());
            return true;
        }

        synchronized Optional<DispatchDeliveryClaim> getDeliveryClaim(String ticketId)
        {
            return Optional.ofNullable(deliveryClaims.get(ticketId));
        }

        private static boolean sameDeliveryClaim(
                DispatchDeliveryClaim left,
                DispatchDeliveryClaim right)
        {
            return left != null
                    && left.ticketId().equals(right.ticketId())
                    && left.ticketVersion() == right.ticketVersion()
                    && left.claimOwner().equals(right.claimOwner())
                    && left.claimedAt().equals(right.claimedAt());
        }

        synchronized DispatchTicket get(String ticketId)
        {
            return tickets.get(ticketId);
        }

        private enum ScanClass
        {
            ORDINARY,
            TRUNK_CONTROL,
            DELIVERY
        }

        private record ScopeKey(String workspaceKey, String trunkKey) {}

        private record RankedTicket(
                DispatchTicket ticket,
                ExecutionPorts.TicketScanCursor cursor) {}
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
