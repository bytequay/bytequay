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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Narrow persistence and adapter ports used by the delivery-only dispatcher. */
public final class ExecutionPorts
{
    private ExecutionPorts() {}

    public interface DispatchTicketStore
    {
        /**
         * Returns a bounded, scope-diverse page strictly after the cursor.
         * The store selects the oldest ordinary admission candidate per
         * Trunk, plus separate oldest capacity-free delivery and Trunk-control
         * heads so neither can hide behind blocked Task work. It interleaves
         * those heads by Workspace and Trunk before applying {@code limit}.
         * A SQL implementation can express this with window ranks; it must
         * never fill a page with a second ordinary candidate from one Trunk
         * while another Trunk head is eligible.
         */
        TicketScanPage findEligiblePage(
                Instant now,
                TicketScanCursor cursor,
                int limit);

        List<DispatchTicket> findExpiredClaims(Instant now, int limit);

        List<DispatchDeliveryClaim> findExpiredDeliveryClaims(Instant now, int limit);

        Optional<DispatchTicket> findById(String ticketId);

        boolean compareAndSet(String ticketId, long expectedVersion, DispatchTicket replacement);

        /** Inserts only against the exact RESULT_PENDING ticket version. */
        Optional<DispatchDeliveryClaim> claimDelivery(
                String ticketId,
                long ticketVersion,
                String claimOwner,
                Instant claimedAt,
                Instant expiresAt);

        /** Renews only the same unexpired claim identity. */
        Optional<DispatchDeliveryClaim> heartbeatDeliveryClaim(
                DispatchDeliveryClaim claim,
                Instant heartbeatAt,
                Instant expiresAt);

        /**
         * Deletes the exact delivery claim, then replaces its exact ticket
         * version in one transaction. Neither mutation may commit alone.
         */
        boolean replaceTicketAndReleaseDeliveryClaim(
                DispatchDeliveryClaim claim,
                DispatchTicket replacement);

        /**
         * Deletes only the same still-expired claim identity, never a
         * heartbeat-renewed or replacement claim.
         */
        boolean releaseExpiredDeliveryClaim(
                DispatchDeliveryClaim claim,
                Instant expiredAt);
    }

    /** Advisory committed wakes for exact DispatchTickets. */
    public interface DispatchWakeStore
    {
        /** Idempotently records the one typed wake for an existing ticket. */
        void enqueue(String ticketId, Instant createdAt);

        /** Claims pending or exactly expired wakes without claiming a ticket. */
        List<DispatchWakeClaim> claimAvailable(
                String claimOwner,
                Instant claimedAt,
                Instant expiresAt,
                int limit);

        /** Delivers only the same durable wake claim. */
        boolean markDelivered(DispatchWakeClaim claim, Instant deliveredAt);
    }

    public record DispatchWakeClaim(
            String wakeId,
            String ticketId,
            int attempt,
            String claimOwner,
            Instant claimedAt,
            Instant expiresAt)
    {
        public DispatchWakeClaim
        {
            requireText(wakeId, "wakeId");
            requireText(ticketId, "ticketId");
            requireText(claimOwner, "claimOwner");
            requireNonNull(claimedAt, "claimedAt is null");
            requireNonNull(expiresAt, "expiresAt is null");
            if (attempt < 1 || !expiresAt.isAfter(claimedAt)) {
                throw new IllegalArgumentException("wake claim evidence is invalid");
            }
        }

        private static void requireText(String value, String name)
        {
            requireNonNull(value, name + " is null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }
    }

    public record TicketScanPage(
            List<DispatchTicket> tickets,
            TicketScanCursor nextCursor)
    {
        public TicketScanPage
        {
            requireNonNull(tickets, "tickets is null");
            tickets = List.copyOf(tickets);
            if (tickets.isEmpty() != (nextCursor == null)) {
                throw new IllegalArgumentException(
                        "a non-empty scan page requires its continuation cursor");
            }
        }
    }

    /** Durable ordering key returned by the scope-diverse store query. */
    public record TicketScanCursor(
            int candidateRound,
            int trunkRound,
            String workspaceOrderKey,
            String trunkOrderKey,
            Instant createdAt,
            String ticketId)
    {
        public TicketScanCursor
        {
            requireNonNull(workspaceOrderKey, "workspaceOrderKey is null");
            requireNonNull(trunkOrderKey, "trunkOrderKey is null");
            requireNonNull(createdAt, "createdAt is null");
            requireNonNull(ticketId, "ticketId is null");
            if (candidateRound < 0 || trunkRound < 0
                    || workspaceOrderKey.isBlank()
                    || trunkOrderKey.isBlank()
                    || ticketId.isBlank()) {
                throw new IllegalArgumentException("scan cursor fields are invalid");
            }
        }
    }

    @FunctionalInterface
    public interface OperationHandlerRegistry
    {
        OperationHandler require(String operationKind);
    }

    public interface OperationHandler
    {
        DispatchTicket.DispatchResult execute(ExecutionContext context)
                throws Exception;

        default DispatchTicket.DispatchResult reconcile(ExecutionContext context)
                throws Exception
        {
            throw new IndeterminateExecutionException(
                    "operation requires adapter-specific reconciliation");
        }
    }

    @FunctionalInterface
    public interface ResultDeliveryPort
    {
        DispatchTicket.DeliveryReceipt deliver(
                DispatchTicket.OwnerReference owner,
                DispatchTicket.OperationFence expectedFence,
                DispatchTicket.DispatchResult rawResult)
                throws Exception;

        /**
         * Runs only after the accepted receipt and terminal ticket committed.
         * Domain finalizers use this boundary when their durable outcome must
         * prove that delivery itself is complete. A failure is recovered by
         * {@link #recoverCommittedDeliveries(int)}; it never rewinds a ticket.
         */
        default void afterDeliveryCommitted(
                DispatchTicket.OwnerReference owner,
                DispatchTicket.OperationFence expectedFence,
                DispatchTicket.DispatchResult rawResult,
                DispatchTicket.DeliveryReceipt receipt)
                throws Exception {}

        /** Restart backstop for a committed receipt whose finalizer did not run. */
        default void recoverCommittedDeliveries(int limit)
                throws Exception {}
    }

    /**
     * Durable domain backstops run on the dispatcher's sole scheduled
     * facility. Implementations may request work but never perform the
     * asynchronous effect themselves.
     */
    @FunctionalInterface
    public interface MaintenanceWork
    {
        void maintain(Instant now)
                throws Exception;
    }

    /**
     * The Operation deliberately parked without holding capacity. A durable
     * owner decision or the supplied retry time must re-arm its ticket.
     */
    public static final class OperationDeferredException
            extends Exception
    {
        private final Instant retryAt;

        public OperationDeferredException(String message, Instant retryAt)
        {
            super(requireNonNull(message, "message is null"));
            this.retryAt = retryAt;
        }

        public Instant retryAt()
        {
            return retryAt;
        }
    }

    public interface ExecutionEvidencePort
    {
        String start(
                DispatchTicket ticket,
                CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose,
                Instant startedAt);

        void heartbeat(String executionId, Instant at);

        void providerSession(
                String executionId,
                String provider,
                String providerSessionId);

        void processStarted(String executionId, long processPid, String logReference);

        void appendLog(
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt);

        void recordUsage(
                String executionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli);

        void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt);

        default void infrastructureFailure(
                String ticketId,
                String failure,
                Instant recordedAt) {}
    }

    public static final class RetryableExecutionException
            extends Exception
    {
        public RetryableExecutionException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }

        public RetryableExecutionException(String message, Throwable cause)
        {
            super(requireNonNull(message, "message is null"), cause);
        }
    }

    /** A durable provider result cannot satisfy its typed owner contract. */
    public static final class ResultProtocolException
            extends Exception
    {
        public ResultProtocolException(String message, Throwable cause)
        {
            super(requireNonNull(message, "message is null"), cause);
        }
    }

    public static final class IndeterminateExecutionException
            extends Exception
    {
        public IndeterminateExecutionException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }

        public IndeterminateExecutionException(String message, Throwable cause)
        {
            super(requireNonNull(message, "message is null"), cause);
        }
    }

    public static final class OperationCanceledException
            extends Exception
    {
        public OperationCanceledException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }
    }
}
