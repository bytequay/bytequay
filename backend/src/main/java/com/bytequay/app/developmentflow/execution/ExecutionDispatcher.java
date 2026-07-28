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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Claims, executes, and delivers exact V2 Operations. It has no domain
 * repositories and never interprets operation results.
 */
public final class ExecutionDispatcher
        implements AutoCloseable
{
    private static final ExecutionPorts.DispatchWakeStore NO_WAKES =
            new ExecutionPorts.DispatchWakeStore()
            {
                @Override
                public void enqueue(String ticketId, Instant createdAt) {}

                @Override
                public List<ExecutionPorts.DispatchWakeClaim> claimAvailable(
                        String claimOwner,
                        Instant claimedAt,
                        Instant expiresAt,
                        int limit)
                {
                    return List.of();
                }

                @Override
                public boolean markDelivered(
                        ExecutionPorts.DispatchWakeClaim claim,
                        Instant deliveredAt)
                {
                    return false;
                }
            };

    private final CapacityManager capacityManager;
    private final ExecutionPorts.DispatchTicketStore tickets;
    private final ExecutionPorts.DispatchWakeStore wakes;
    private final ExecutionPorts.OperationHandlerRegistry handlers;
    private final ExecutionPorts.ResultDeliveryPort resultDelivery;
    private final ExecutionPorts.ExecutionEvidencePort evidence;
    private final Clock clock;
    private final Config config;
    private final Supplier<String> claimOwnerSupplier;

    // C25: these are the only two ExecutionDispatcher-owned facilities.
    private final ExecutorService operationExecutor;
    private final ScheduledExecutorService maintenanceExecutor;

    private final ConcurrentHashMap<String, ActiveExecution> active =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveDelivery> activeDeliveries =
            new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean maintaining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ExecutionPorts.TicketScanCursor eligibleCursor;

    public ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            Clock clock,
            Config config)
    {
        this(
                capacityManager,
                tickets,
                NO_WAKES,
                handlers,
                resultDelivery,
                evidence,
                clock,
                config);
    }

    public ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.DispatchWakeStore wakes,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            Clock clock,
            Config config)
    {
        this(
                capacityManager,
                tickets,
                wakes,
                handlers,
                resultDelivery,
                evidence,
                clock,
                config,
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .name("v2-dispatch-maintenance-", 0)
                                .factory()),
                () -> config.dispatcherId() + ":" + UUID.randomUUID());
    }

    ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            Clock clock,
            Config config,
            ExecutorService operationExecutor,
            ScheduledExecutorService maintenanceExecutor)
    {
        this(
                capacityManager,
                tickets,
                NO_WAKES,
                handlers,
                resultDelivery,
                evidence,
                clock,
                config,
                operationExecutor,
                maintenanceExecutor,
                () -> config.dispatcherId() + ":" + UUID.randomUUID());
    }

    ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.DispatchWakeStore wakes,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            Clock clock,
            Config config,
            ExecutorService operationExecutor,
            ScheduledExecutorService maintenanceExecutor)
    {
        this(
                capacityManager,
                tickets,
                wakes,
                handlers,
                resultDelivery,
                evidence,
                clock,
                config,
                operationExecutor,
                maintenanceExecutor,
                () -> config.dispatcherId() + ":" + UUID.randomUUID());
    }

    ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            Clock clock,
            Config config,
            ExecutorService operationExecutor,
            ScheduledExecutorService maintenanceExecutor,
            Supplier<String> claimOwnerSupplier)
    {
        this(
                capacityManager,
                tickets,
                NO_WAKES,
                handlers,
                resultDelivery,
                evidence,
                clock,
                config,
                operationExecutor,
                maintenanceExecutor,
                claimOwnerSupplier);
    }

    ExecutionDispatcher(
            CapacityManager capacityManager,
            ExecutionPorts.DispatchTicketStore tickets,
            ExecutionPorts.DispatchWakeStore wakes,
            ExecutionPorts.OperationHandlerRegistry handlers,
            ExecutionPorts.ResultDeliveryPort resultDelivery,
            ExecutionPorts.ExecutionEvidencePort evidence,
            Clock clock,
            Config config,
            ExecutorService operationExecutor,
            ScheduledExecutorService maintenanceExecutor,
            Supplier<String> claimOwnerSupplier)
    {
        this.capacityManager = requireNonNull(capacityManager, "capacityManager is null");
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.wakes = requireNonNull(wakes, "wakes is null");
        this.handlers = requireNonNull(handlers, "handlers is null");
        this.resultDelivery = requireNonNull(resultDelivery, "resultDelivery is null");
        this.evidence = requireNonNull(evidence, "evidence is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.config = requireNonNull(config, "config is null");
        this.operationExecutor = requireNonNull(operationExecutor, "operationExecutor is null");
        this.maintenanceExecutor = requireNonNull(
                maintenanceExecutor, "maintenanceExecutor is null");
        this.claimOwnerSupplier = requireNonNull(
                claimOwnerSupplier, "claimOwnerSupplier is null");
        if (config.maintenanceInterval().compareTo(capacityManager.leaseDuration()) >= 0) {
            throw new IllegalArgumentException(
                    "maintenanceInterval must be shorter than capacity lease duration");
        }
        if (config.claimLeaseDuration().compareTo(capacityManager.leaseDuration()) > 0) {
            throw new IllegalArgumentException(
                    "claimLeaseDuration must not exceed capacity lease duration");
        }
    }

    public void start()
    {
        ensureOpen();
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long delayMillis = config.maintenanceInterval().toMillis();
        maintenanceExecutor.scheduleWithFixedDelay(
                this::runMaintenanceSafely,
                0,
                delayMillis,
                TimeUnit.MILLISECONDS);
    }

    /** Deterministic entry point used by the scheduler and fault tests. */
    public void runMaintenance()
    {
        ensureOpen();
        if (!maintaining.compareAndSet(false, true)) {
            return;
        }
        try {
            heartbeatActiveExecutions();
            heartbeatActiveDeliveries();
            capacityManager.expireLeases();
            recoverExpiredClaims();
            recoverExpiredDeliveryClaims();
            drainDispatchWakes();
            dispatchEligibleTickets();
        }
        finally {
            maintaining.set(false);
        }
    }

    /** Records cancellation before signaling the exact active attempt. */
    public boolean requestCancel(String ticketId)
    {
        requireNonNull(ticketId, "ticketId is null");
        ensureOpen();
        Optional<DispatchTicket> updated = transform(
                ticketId,
                ticket -> !ticket.state().isTerminal(),
                ticket -> ticket.requestCancel(clock.instant()));
        if (updated.isEmpty()) {
            return false;
        }
        ActiveExecution execution = active.get(ticketId);
        if (execution != null
                && execution.claim().purpose() == DispatchTicket.ClaimPurpose.EXECUTE) {
            signalStop(ticketId, execution);
        }
        return true;
    }

    int activeExecutionCount()
    {
        return active.size() + activeDeliveries.size();
    }

    private void runMaintenanceSafely()
    {
        try {
            runMaintenance();
        }
        catch (RuntimeException ignored) {
            // The next durable sweep retries. A maintenance exception must not
            // kill the sole scheduled facility.
            recordInfrastructureFailure(null, ignored);
        }
    }

    private void dispatchEligibleTickets()
    {
        Instant now = clock.instant();
        List<DispatchTicket> page = scanEligibleTickets(now);
        List<DispatchTicket> eligible = capacityManager.fairOrder(
                page,
                ticket -> ticket.envelope().capacityRequest(),
                DispatchTicket::createdAt,
                DispatchTicket::id);
        for (DispatchTicket candidate : eligible) {
            tryDispatch(candidate);
        }
    }

    private List<DispatchTicket> scanEligibleTickets(Instant now)
    {
        ExecutionPorts.TicketScanPage found = tickets.findEligiblePage(
                now, eligibleCursor, config.scanLimit());
        if (found.tickets().isEmpty() && eligibleCursor != null) {
            eligibleCursor = null;
            found = tickets.findEligiblePage(now, null, config.scanLimit());
        }
        if (!found.tickets().isEmpty()) {
            eligibleCursor = found.nextCursor();
        }
        return found.tickets();
    }

    private void drainDispatchWakes()
    {
        Instant claimedAt = clock.instant();
        List<ExecutionPorts.DispatchWakeClaim> claims;
        try {
            claims = requireNonNull(
                    wakes.claimAvailable(
                            newClaimOwner(),
                            claimedAt,
                            claimedAt.plus(config.claimLeaseDuration()),
                            config.scanLimit()),
                    "wake claims are null");
        }
        catch (RuntimeException failure) {
            recordInfrastructureFailure(null, failure);
            return;
        }
        List<WakeCandidate> candidates = new ArrayList<>();
        for (ExecutionPorts.DispatchWakeClaim claim : claims) {
            try {
                Optional<DispatchTicket> ticket = tickets.findById(claim.ticketId())
                        .filter(candidate -> candidate.isEligibleAt(clock.instant()));
                if (ticket.isPresent()) {
                    candidates.add(new WakeCandidate(claim, ticket.orElseThrow()));
                }
                else {
                    wakes.markDelivered(claim, clock.instant());
                }
            }
            catch (RuntimeException failure) {
                // The exact wake claim expires for restart recovery. The
                // normal ticket scan below remains the correctness backstop.
                recordInfrastructureFailure(claim.ticketId(), failure);
            }
        }
        List<WakeCandidate> ordered;
        try {
            ordered = capacityManager.fairOrder(
                    candidates,
                    candidate -> candidate.ticket().envelope().capacityRequest(),
                    candidate -> candidate.ticket().createdAt(),
                    candidate -> candidate.ticket().id());
        }
        catch (RuntimeException failure) {
            recordInfrastructureFailure(null, failure);
            return;
        }
        for (WakeCandidate candidate : ordered) {
            try {
                tryDispatch(candidate.ticket());
                wakes.markDelivered(candidate.claim(), clock.instant());
            }
            catch (RuntimeException failure) {
                recordInfrastructureFailure(candidate.claim().ticketId(), failure);
            }
        }
    }

    private void tryDispatch(DispatchTicket candidate)
    {
        String claimOwner = newClaimOwner();
        Instant claimedAt = clock.instant();
        Instant claimExpiry = claimedAt.plus(config.claimLeaseDuration());
        if (candidate.state() == DispatchTicket.State.RESULT_PENDING) {
            tickets.claimDelivery(
                            candidate.id(),
                            candidate.version(),
                            claimOwner,
                            claimedAt,
                            claimExpiry)
                    .ifPresent(this::submitDeliveryClaim);
            return;
        }

        CapacityManager.Admission admission = capacityManager.tryAcquireForTicket(
                candidate.id(), candidate.envelope().capacityRequest(), claimOwner);
        if (!admission.isAdmitted()) {
            return;
        }
        CapacityManager.CapacityLease lease = admission.lease().orElseThrow();
        DispatchTicket claimed = candidate.claim(
                claimOwner, lease.id(), claimExpiry);
        if (!tickets.compareAndSet(candidate.id(), candidate.version(), claimed)) {
            capacityManager.release(lease.id(), claimOwner);
            return;
        }
        submitClaim(Claim.from(claimed));
    }

    private String newClaimOwner()
    {
        String claimOwner = requireNonNull(
                claimOwnerSupplier.get(), "generated claim owner is null");
        if (claimOwner.isBlank()) {
            throw new IllegalStateException("generated claim owner must not be blank");
        }
        return claimOwner;
    }

    private void submitClaim(Claim claim)
    {
        try {
            operationExecutor.execute(() -> runClaim(claim));
        }
        catch (RuntimeException e) {
            releaseAfterSubmissionFailure(claim, e);
        }
    }

    private void submitDeliveryClaim(DispatchDeliveryClaim claim)
    {
        try {
            operationExecutor.execute(() -> deliverPendingResult(claim));
        }
        catch (RuntimeException e) {
            releaseAfterDeliverySubmissionFailure(claim, e);
        }
    }

    private void releaseAfterSubmissionFailure(
            Claim claim,
            RuntimeException failure)
    {
        transform(
                claim.ticketId(),
                ticket -> ownsClaim(ticket, claim)
                        && ticket.state() == DispatchTicket.State.CLAIMED,
                ticket -> ticket.submissionRetry(
                        message(failure), clock.instant().plus(config.retryDelay())));
        releaseCapacity(claim);
    }

    private void releaseAfterDeliverySubmissionFailure(
            DispatchDeliveryClaim claim,
            RuntimeException failure)
    {
        tickets.findById(claim.ticketId())
                .filter(claim::owns)
                .map(ticket -> ticket.deliveryRetry(
                        message(failure), clock.instant().plus(config.retryDelay())))
                .ifPresent(replacement -> tickets.replaceTicketAndReleaseDeliveryClaim(
                        claim, replacement));
    }

    private void runClaim(Claim claim)
    {
        Optional<DispatchTicket> current = tickets.findById(claim.ticketId())
                .filter(ticket -> ownsClaim(ticket, claim));
        if (current.isEmpty()) {
            return;
        }
        DispatchTicket claimed = current.get();
        try {
            capacityManager.requireExactLeaseForTicket(
                    claim.ticketId(),
                    claim.capacityLeaseId(),
                    claimed.envelope().capacityRequest(),
                    claim.owner());
            executeOperation(claim);
        }
        finally {
            releaseCapacity(claim);
        }
    }

    private void executeOperation(Claim claim)
    {
        Optional<DispatchTicket> latest = tickets.findById(claim.ticketId())
                .filter(ticket -> ownsClaim(ticket, claim));
        if (latest.isEmpty()) {
            return;
        }
        if (latest.get().cancelRequestedAt() != null
                && latest.get().claimPurpose() == DispatchTicket.ClaimPurpose.EXECUTE) {
            transform(
                    claim.ticketId(),
                    ticket -> ownsClaim(ticket, claim),
                    ticket -> ticket.resultPending(
                            DispatchTicket.DispatchResult.canceled(ticket.envelope().fence()),
                            clock.instant()));
            return;
        }

        Optional<DispatchTicket> running = transform(
                claim.ticketId(),
                ticket -> ownsClaim(ticket, claim)
                        && ticket.state() == DispatchTicket.State.CLAIMED,
                ticket -> ticket.markRunning(clock.instant()));
        if (running.isEmpty()) {
            return;
        }
        DispatchTicket ticket = running.get();
        CapacityManager.CapacityLease lease = capacityManager.requireExactLeaseForTicket(
                claim.ticketId(),
                claim.capacityLeaseId(),
                ticket.envelope().capacityRequest(),
                claim.owner());
        String executionId = null;
        ExecutionContext.Cancellation cancellation = new ExecutionContext.Cancellation();
        ActiveExecution activeExecution = null;
        try {
            try {
                executionId = requireNonNull(
                        evidence.start(
                                ticket, lease, ticket.claimPurpose(), clock.instant()),
                        "execution evidence id is null");
                if (executionId.isBlank()) {
                    throw new IllegalStateException(
                            "execution evidence id must not be blank");
                }
            }
            catch (RuntimeException e) {
                recordRetry(claim, null, e);
                return;
            }

            Optional<DispatchTicket> stillRunning = tickets.findById(claim.ticketId())
                    .filter(value -> ownsClaim(value, claim))
                    .filter(value -> value.state() == DispatchTicket.State.RUNNING)
                    .filter(value -> value.capacityLeaseId().equals(claim.capacityLeaseId()));
            if (stillRunning.isEmpty()) {
                finishEvidence(
                        executionId, null, "execution claim was lost before adapter launch");
                return;
            }
            try {
                capacityManager.requireExactLeaseForTicket(
                        claim.ticketId(),
                        claim.capacityLeaseId(),
                        ticket.envelope().capacityRequest(),
                        claim.owner());
            }
            catch (RuntimeException e) {
                recordRetry(claim, executionId, e);
                return;
            }
            if (stillRunning.get().cancelRequestedAt() != null
                    && ticket.claimPurpose() == DispatchTicket.ClaimPurpose.EXECUTE) {
                recordResult(
                        claim,
                        executionId,
                        DispatchTicket.DispatchResult.canceled(ticket.envelope().fence()));
                return;
            }

            ExecutionContext context = new ExecutionContext(
                    ticket.envelope(),
                    lease,
                    cancellation,
                    evidence,
                    executionId,
                    clock,
                    () -> capacityManager.requireExactLeaseForTicket(
                            claim.ticketId(),
                            claim.capacityLeaseId(),
                            ticket.envelope().capacityRequest(),
                            claim.owner()));
            activeExecution = new ActiveExecution(
                    executionId, claim, cancellation, context);
            active.put(claim.ticketId(), activeExecution);
            if (closed.get()) {
                abandonForShutdown(claim.ticketId(), activeExecution);
                finishEvidence(
                        executionId,
                        indeterminateResult(
                                ticket,
                                "dispatcher stopped before adapter launch"),
                        "dispatcher stopped before adapter launch");
                return;
            }
            tickets.findById(claim.ticketId())
                    .filter(value -> ownsClaim(value, claim))
                    .filter(value -> value.cancelRequestedAt() != null)
                    .filter(ignored -> claim.purpose() == DispatchTicket.ClaimPurpose.EXECUTE)
                    .ifPresent(ignored -> cancellation.cancel());

            ExecutionPorts.OperationHandler handler = handlers.require(
                    ticket.envelope().operationKind());
            DispatchTicket.DispatchResult result =
                    ticket.claimPurpose() == DispatchTicket.ClaimPurpose.RECONCILE
                            ? handler.reconcile(context)
                            : handler.execute(context);
            if (result == null) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "adapter returned no result");
            }
            recordResult(claim, executionId, result);
        }
        catch (ExecutionPorts.RetryableExecutionException e) {
            recordRetry(claim, executionId, e);
        }
        catch (ExecutionPorts.IndeterminateExecutionException e) {
            recordIndeterminate(claim, executionId, e);
        }
        catch (ExecutionPorts.OperationCanceledException e) {
            Optional<DispatchTicket> current = tickets.findById(claim.ticketId())
                    .filter(candidate -> ownsClaim(candidate, claim))
                    .filter(candidate -> candidate.state() == DispatchTicket.State.RUNNING);
            if (claim.purpose() == DispatchTicket.ClaimPurpose.EXECUTE
                    && current.map(DispatchTicket::cancelRequestedAt).isPresent()) {
                recordResult(
                        claim,
                        executionId,
                        DispatchTicket.DispatchResult.canceled(ticket.envelope().fence()));
            }
            else {
                recordIndeterminate(claim, executionId, e);
            }
        }
        catch (Exception e) {
            recordIndeterminate(claim, executionId, e);
        }
        finally {
            if (activeExecution != null) {
                try {
                    activeExecution.context().closeWriterResource();
                }
                catch (RuntimeException firstFailure) {
                    try {
                        activeExecution.context().closeWriterResource();
                    }
                    catch (RuntimeException retryFailure) {
                        firstFailure.addSuppressed(retryFailure);
                    }
                    recordInfrastructureFailure(claim.ticketId(), firstFailure);
                }
                active.remove(claim.ticketId(), activeExecution);
            }
        }
    }

    private void recordResult(
            Claim claim,
            String executionId,
            DispatchTicket.DispatchResult result)
    {
        Optional<DispatchTicket> current = tickets.findById(claim.ticketId())
                .filter(ticket -> ownsClaim(ticket, claim))
                .filter(ticket -> ticket.state() == DispatchTicket.State.RUNNING);
        if (current.isEmpty()) {
            finishEvidence(executionId, result, "result arrived after claim ownership changed");
            return;
        }
        if (claim.capacityLeaseId() != null) {
            try {
                capacityManager.requireExactLeaseForTicket(
                        claim.ticketId(),
                        claim.capacityLeaseId(),
                        current.get().envelope().capacityRequest(),
                        claim.owner());
            }
            catch (RuntimeException e) {
                finishEvidence(executionId, result, message(e));
                return;
            }
        }
        transform(
                claim.ticketId(),
                ticket -> ownsClaim(ticket, claim)
                        && ticket.state() == DispatchTicket.State.RUNNING,
                ticket -> ticket.resultPending(result, clock.instant()));
        finishEvidence(executionId, result, null);
        // If the claim expired, raw evidence remains durable and a reconciler
        // decides whether it is safe to accept or probe the late result.
    }

    private void recordRetry(
            Claim claim,
            String executionId,
            Exception failure)
    {
        Optional<DispatchTicket> current = tickets.findById(claim.ticketId())
                .filter(ticket -> ownsClaim(ticket, claim));
        if (current.isEmpty()) {
            finishEvidence(executionId, null, message(failure));
            return;
        }
        DispatchTicket ticket = current.get();
        if (ticket.infrastructureAttempts() >= config.maxInfrastructureAttempts()) {
            if (claim.purpose() == DispatchTicket.ClaimPurpose.RECONCILE) {
                parkManualReconciliation(claim, executionId, ticket, failure);
                return;
            }
            recordResult(claim, executionId, failedResult(ticket, message(failure)));
            return;
        }
        transform(
                claim.ticketId(),
                candidate -> ownsClaim(candidate, claim)
                        && candidate.state() == DispatchTicket.State.RUNNING,
                candidate -> candidate.claimPurpose() == DispatchTicket.ClaimPurpose.RECONCILE
                        ? candidate.reconcileWait(
                                message(failure), clock.instant().plus(config.retryDelay()))
                        : candidate.retryWait(
                                message(failure), clock.instant().plus(config.retryDelay())));
        finishEvidence(
                executionId,
                claim.purpose() == DispatchTicket.ClaimPurpose.RECONCILE
                        ? indeterminateResult(ticket, message(failure))
                        : null,
                message(failure));
    }

    private void recordIndeterminate(
            Claim claim,
            String executionId,
            Exception failure)
    {
        Optional<DispatchTicket> current = tickets.findById(claim.ticketId());
        if (current.isEmpty()) {
            finishEvidence(executionId, null, message(failure));
            return;
        }
        DispatchTicket ticket = current.get();
        if (ownsClaim(ticket, claim)
                && ticket.state() == DispatchTicket.State.RUNNING
                && claim.purpose() == DispatchTicket.ClaimPurpose.RECONCILE
                && ticket.infrastructureAttempts() >= config.maxInfrastructureAttempts()) {
            parkManualReconciliation(claim, executionId, ticket, failure);
            return;
        }
        transform(
                claim.ticketId(),
                candidate -> ownsClaim(candidate, claim)
                        && candidate.state() == DispatchTicket.State.RUNNING,
                candidate -> candidate.reconcileWait(
                        message(failure), clock.instant().plus(config.retryDelay())));
        finishEvidence(
                executionId,
                indeterminateResult(ticket, message(failure)),
                message(failure));
    }

    private void parkManualReconciliation(
            Claim claim,
            String executionId,
            DispatchTicket ticket,
            Exception failure)
    {
        transform(
                claim.ticketId(),
                candidate -> ownsClaim(candidate, claim)
                        && candidate.state() == DispatchTicket.State.RUNNING,
                candidate -> candidate.manualReconciliation(message(failure)));
        finishEvidence(
                executionId,
                indeterminateResult(ticket, message(failure)),
                message(failure));
    }

    private void deliverPendingResult(DispatchDeliveryClaim claimed)
    {
        Instant heartbeatAt = clock.instant();
        Optional<DispatchDeliveryClaim> liveClaim = tickets.heartbeatDeliveryClaim(
                claimed,
                heartbeatAt,
                heartbeatAt.plus(config.claimLeaseDuration()));
        if (liveClaim.isEmpty()) {
            return;
        }
        DispatchDeliveryClaim claim = liveClaim.get();
        ActiveDelivery delivery = new ActiveDelivery(claim, Thread.currentThread());
        ActiveDelivery previous = activeDeliveries.put(claim.ticketId(), delivery);
        if (previous != null && previous != delivery) {
            previous.interrupt();
        }
        try {
            Optional<DispatchTicket> current = tickets.findById(claim.ticketId())
                    .filter(claim::owns);
            if (current.isEmpty()) {
                return;
            }
            DispatchTicket ticket = current.get();
            DispatchTicket.DeliveryReceipt receipt = resultDelivery.deliver(
                    ticket.envelope().owner(),
                    ticket.envelope().fence(),
                    ticket.pendingResult());
            tickets.replaceTicketAndReleaseDeliveryClaim(
                    claim, ticket.completeDelivery(receipt, clock.instant()));
        }
        catch (Exception e) {
            tickets.findById(claim.ticketId())
                    .filter(claim::owns)
                    .map(ticket -> ticket.deliveryRetry(
                            message(e), clock.instant().plus(config.retryDelay())))
                    .ifPresent(replacement -> tickets.replaceTicketAndReleaseDeliveryClaim(
                            claim, replacement));
        }
        finally {
            activeDeliveries.remove(claim.ticketId(), delivery);
        }
    }

    private void heartbeatActiveExecutions()
    {
        Instant newExpiry = clock.instant().plus(config.claimLeaseDuration());
        active.forEach((ticketId, execution) -> {
            Claim claim = execution.claim();
            Optional<DispatchTicket> current = tickets.findById(ticketId)
                    .filter(ticket -> ownsClaim(ticket, claim));
            if (current.isEmpty()) {
                active.remove(ticketId, execution);
                signalStop(ticketId, execution);
                return;
            }
            if (claim.capacityLeaseId() != null
                    && claim.purpose() == DispatchTicket.ClaimPurpose.EXECUTE
                    && current.get().cancelRequestedAt() != null) {
                signalStop(ticketId, execution);
                return;
            }
            try {
                if (claim.capacityLeaseId() != null && capacityManager.heartbeat(
                        claim.capacityLeaseId(), claim.owner()).isEmpty()) {
                    signalStop(ticketId, execution);
                    return;
                }
                Optional<DispatchTicket> heartbeat = transform(
                        ticketId,
                        ticket -> ownsClaim(ticket, claim),
                        ticket -> ticket.heartbeat(newExpiry));
                if (heartbeat.isEmpty()) {
                    signalStop(ticketId, execution);
                    return;
                }
                if (execution.executionId() != null) {
                    evidence.heartbeat(execution.executionId(), clock.instant());
                }
                execution.context().heartbeatWriterResource();
            }
            catch (RuntimeException e) {
                recordInfrastructureFailure(ticketId, e);
                signalStop(ticketId, execution);
            }
        });
    }

    private void heartbeatActiveDeliveries()
    {
        Instant heartbeatAt = clock.instant();
        Instant newExpiry = heartbeatAt.plus(config.claimLeaseDuration());
        activeDeliveries.forEach((ticketId, delivery) -> {
            try {
                Optional<DispatchDeliveryClaim> updated = tickets.heartbeatDeliveryClaim(
                        delivery.claim(), heartbeatAt, newExpiry);
                if (updated.isEmpty()) {
                    if (activeDeliveries.remove(ticketId, delivery)) {
                        delivery.interrupt();
                    }
                    return;
                }
                delivery.update(updated.get());
            }
            catch (RuntimeException e) {
                recordInfrastructureFailure(ticketId, e);
            }
        });
    }

    private void recoverExpiredClaims()
    {
        Instant now = clock.instant();
        for (DispatchTicket expired : tickets.findExpiredClaims(now, config.scanLimit())) {
            DispatchTicket recovered = expired.recoverExpiredClaim(now);
            if (recovered == expired) {
                continue;
            }
            if (tickets.compareAndSet(expired.id(), expired.version(), recovered)) {
                if (expired.capacityLeaseId() != null && expired.claimOwner() != null) {
                    capacityManager.release(expired.capacityLeaseId(), expired.claimOwner());
                }
                ActiveExecution execution = active.get(expired.id());
                if (execution != null
                        && ownsClaim(expired, execution.claim())
                        && active.remove(expired.id(), execution)) {
                    signalStop(expired.id(), execution);
                }
            }
        }
    }

    private void recoverExpiredDeliveryClaims()
    {
        Instant now = clock.instant();
        for (DispatchDeliveryClaim expired :
                tickets.findExpiredDeliveryClaims(now, config.scanLimit())) {
            if (!tickets.releaseExpiredDeliveryClaim(expired, now)) {
                continue;
            }
            ActiveDelivery delivery = activeDeliveries.get(expired.ticketId());
            if (delivery != null
                    && sameDeliveryClaim(delivery.claim(), expired)
                    && activeDeliveries.remove(expired.ticketId(), delivery)) {
                delivery.interrupt();
            }
        }
    }

    private Optional<DispatchTicket> transform(
            String ticketId,
            Predicate<DispatchTicket> guard,
            Function<DispatchTicket, DispatchTicket> transition)
    {
        while (true) {
            Optional<DispatchTicket> found = tickets.findById(ticketId);
            if (found.isEmpty() || !guard.test(found.get())) {
                return Optional.empty();
            }
            DispatchTicket current = found.get();
            DispatchTicket replacement = transition.apply(current);
            if (replacement == current) {
                return Optional.of(current);
            }
            if (tickets.compareAndSet(ticketId, current.version(), replacement)) {
                return Optional.of(replacement);
            }
        }
    }

    private static boolean ownsClaim(DispatchTicket ticket, Claim claim)
    {
        return claim.ticketId().equals(ticket.id())
                && claim.owner().equals(ticket.claimOwner())
                && claim.purpose() == ticket.claimPurpose()
                && Objects.equals(claim.capacityLeaseId(), ticket.capacityLeaseId());
    }

    private static boolean sameDeliveryClaim(
            DispatchDeliveryClaim left,
            DispatchDeliveryClaim right)
    {
        return left.ticketId().equals(right.ticketId())
                && left.ticketVersion() == right.ticketVersion()
                && left.claimOwner().equals(right.claimOwner())
                && left.claimedAt().equals(right.claimedAt());
    }

    private void releaseCapacity(Claim claim)
    {
        if (claim.capacityLeaseId() != null) {
            capacityManager.release(claim.capacityLeaseId(), claim.owner());
        }
    }

    private void signalStop(String ticketId, ActiveExecution execution)
    {
        execution.cancellation().cancel();
        RuntimeException failure = execution.cancellation().takeStopFailure();
        if (failure != null) {
            recordInfrastructureFailure(ticketId, failure);
        }
    }

    private void recordInfrastructureFailure(String ticketId, RuntimeException failure)
    {
        try {
            evidence.infrastructureFailure(ticketId, message(failure), clock.instant());
        }
        catch (RuntimeException ignored) {
            // The durable ticket remains authoritative even when operational
            // evidence storage is temporarily unavailable.
        }
    }

    private static DispatchTicket.DispatchResult failedResult(
            DispatchTicket ticket,
            String error)
    {
        return new DispatchTicket.DispatchResult(
                ticket.envelope().fence(),
                DispatchTicket.Outcome.FAILED,
                null,
                "{}",
                error);
    }

    private static DispatchTicket.DispatchResult indeterminateResult(
            DispatchTicket ticket,
            String error)
    {
        return new DispatchTicket.DispatchResult(
                ticket.envelope().fence(),
                DispatchTicket.Outcome.INDETERMINATE,
                null,
                "{}",
                error);
    }

    private static String message(Throwable failure)
    {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private void finishEvidence(
            String executionId,
            DispatchTicket.DispatchResult result,
            String failure)
    {
        if (executionId != null) {
            evidence.finish(executionId, result, failure, clock.instant());
        }
    }

    private void ensureOpen()
    {
        if (closed.get()) {
            throw new IllegalStateException("dispatcher is closed");
        }
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        maintenanceExecutor.shutdownNow();
        try {
            active.forEach(this::abandonForShutdown);
            activeDeliveries.values().forEach(ActiveDelivery::interrupt);
        }
        finally {
            operationExecutor.shutdownNow();
        }
    }

    private void abandonForShutdown(String ticketId, ActiveExecution execution)
    {
        Optional<DispatchTicket> abandoned = transform(
                ticketId,
                ticket -> ownsClaim(ticket, execution.claim())
                        && ticket.state() == DispatchTicket.State.RUNNING,
                ticket -> ticket.reconcileWait(
                        "dispatcher stopped during execution; reconciliation required",
                        clock.instant()));
        if (abandoned.isPresent()
                && abandoned.get().state() == DispatchTicket.State.RECONCILE_WAIT) {
            releaseCapacity(execution.claim());
        }
        signalStop(ticketId, execution);
    }

    public record Config(
            String dispatcherId,
            Duration maintenanceInterval,
            Duration claimLeaseDuration,
            Duration retryDelay,
            int maxInfrastructureAttempts,
            int scanLimit)
    {
        public Config
        {
            requireNonNull(dispatcherId, "dispatcherId is null");
            maintenanceInterval = positive(maintenanceInterval, "maintenanceInterval");
            claimLeaseDuration = positive(claimLeaseDuration, "claimLeaseDuration");
            retryDelay = positive(retryDelay, "retryDelay");
            if (dispatcherId.isBlank()) {
                throw new IllegalArgumentException("dispatcherId must not be blank");
            }
            if (maxInfrastructureAttempts < 1 || scanLimit < 1) {
                throw new IllegalArgumentException(
                        "attempt and scan limits must be positive");
            }
            if (maintenanceInterval.compareTo(claimLeaseDuration) >= 0) {
                throw new IllegalArgumentException(
                        "maintenanceInterval must be shorter than claimLeaseDuration");
            }
        }

        private static Duration positive(Duration duration, String name)
        {
            requireNonNull(duration, name + " is null");
            if (duration.isZero() || duration.isNegative() || duration.toMillis() < 1) {
                throw new IllegalArgumentException(name + " must be at least one millisecond");
            }
            return duration;
        }
    }

    private record ActiveExecution(
            String executionId,
            Claim claim,
            ExecutionContext.Cancellation cancellation,
            ExecutionContext context)
    {
        private ActiveExecution
        {
            requireNonNull(claim, "claim is null");
            requireNonNull(cancellation, "cancellation is null");
            requireNonNull(context, "context is null");
            if (executionId != null && executionId.isBlank()) {
                throw new IllegalArgumentException("executionId must not be blank");
            }
        }
    }

    private record WakeCandidate(
            ExecutionPorts.DispatchWakeClaim claim,
            DispatchTicket ticket)
    {
        private WakeCandidate
        {
            requireNonNull(claim, "claim is null");
            requireNonNull(ticket, "ticket is null");
        }
    }

    private static final class ActiveDelivery
    {
        private volatile DispatchDeliveryClaim claim;
        private final Thread worker;

        private ActiveDelivery(DispatchDeliveryClaim claim, Thread worker)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.worker = requireNonNull(worker, "worker is null");
        }

        private DispatchDeliveryClaim claim()
        {
            return claim;
        }

        private void update(DispatchDeliveryClaim updated)
        {
            if (!sameDeliveryClaim(claim, updated)) {
                throw new IllegalArgumentException("cannot replace delivery claim identity");
            }
            claim = updated;
        }

        private void interrupt()
        {
            worker.interrupt();
        }
    }

    private record Claim(
            String ticketId,
            String owner,
            String capacityLeaseId,
            DispatchTicket.ClaimPurpose purpose)
    {
        private Claim
        {
            requireNonNull(ticketId, "ticketId is null");
            requireNonNull(owner, "owner is null");
            requireNonNull(capacityLeaseId, "capacityLeaseId is null");
            requireNonNull(purpose, "purpose is null");
        }

        private static Claim from(DispatchTicket ticket)
        {
            return new Claim(
                    ticket.id(),
                    ticket.claimOwner(),
                    ticket.capacityLeaseId(),
                    ticket.claimPurpose());
        }
    }
}
