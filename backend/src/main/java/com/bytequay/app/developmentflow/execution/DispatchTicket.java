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
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/** Durable infrastructure state for one exact V2 Operation. */
public record DispatchTicket(
        String id,
        long version,
        DispatchEnvelope envelope,
        State state,
        ClaimPurpose claimPurpose,
        String claimOwner,
        String capacityLeaseId,
        Instant claimExpiresAt,
        Instant createdAt,
        Instant nextAttemptAt,
        int infrastructureAttempts,
        Instant startedAt,
        Instant cancelRequestedAt,
        DispatchResult pendingResult,
        DeliveryReceipt deliveryReceipt,
        Instant completedAt,
        String lastError)
{
    public DispatchTicket
    {
        requireNonNull(id, "id is null");
        requireNonNull(envelope, "envelope is null");
        requireNonNull(state, "state is null");
        requireNonNull(createdAt, "createdAt is null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (version < 0 || infrastructureAttempts < 0) {
            throw new IllegalArgumentException("versions and attempts must be non-negative");
        }
        boolean claimed = state == State.CLAIMED || state == State.RUNNING;
        if (claimed != (claimPurpose != null
                && claimOwner != null
                && capacityLeaseId != null
                && claimExpiresAt != null)) {
            throw new IllegalArgumentException(
                    "claimed state requires purpose, owner, capacity, and expiry");
        }
        if (claimOwner != null && claimOwner.isBlank()) {
            throw new IllegalArgumentException(
                    "claimOwner must not be blank");
        }
        if (capacityLeaseId != null && capacityLeaseId.isBlank()) {
            throw new IllegalArgumentException(
                    "capacityLeaseId must not be blank");
        }
        boolean resultDelivery = state == State.RESULT_PENDING;
        if (resultDelivery != (pendingResult != null)) {
            throw new IllegalArgumentException(
                    "result-pending delivery state requires one durable result");
        }
        boolean terminalEvidence = completedAt != null && deliveryReceipt != null;
        if ((completedAt == null) != (deliveryReceipt == null)
                || state.isTerminal() != terminalEvidence) {
            throw new IllegalArgumentException(
                    "terminal state requires completion time and delivery receipt");
        }
    }

    public static DispatchTicket requested(
            String id,
            DispatchEnvelope envelope,
            Instant createdAt)
    {
        return new DispatchTicket(
                id,
                0,
                envelope,
                State.REQUESTED,
                null,
                null,
                null,
                null,
                createdAt,
                createdAt,
                0,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public boolean isEligibleAt(Instant now)
    {
        requireNonNull(now, "now is null");
        return switch (state) {
            case REQUESTED -> true;
            case RETRY_WAIT, RESULT_PENDING ->
                    nextAttemptAt == null || !nextAttemptAt.isAfter(now);
            case RECONCILE_WAIT ->
                    nextAttemptAt != null && !nextAttemptAt.isAfter(now);
            default -> false;
        };
    }

    public boolean hasExpiredClaimAt(Instant now)
    {
        requireNonNull(now, "now is null");
        return claimExpiresAt != null && !claimExpiresAt.isAfter(now);
    }

    public DispatchTicket claim(
            String owner,
            String leaseId,
            Instant expiresAt)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(leaseId, "leaseId is null");
        requireNonNull(expiresAt, "expiresAt is null");
        ClaimPurpose purpose = switch (state) {
            case REQUESTED, RETRY_WAIT -> ClaimPurpose.EXECUTE;
            case RECONCILE_WAIT -> ClaimPurpose.RECONCILE;
            default -> throw new IllegalStateException("ticket is not claimable: " + state);
        };
        return copy(
                State.CLAIMED,
                purpose,
                owner,
                leaseId,
                expiresAt,
                nextAttemptAt,
                infrastructureAttempts,
                startedAt,
                cancelRequestedAt,
                pendingResult,
                deliveryReceipt,
                completedAt,
                lastError);
    }

    public DispatchTicket markRunning(Instant now)
    {
        requireClaim(ClaimPurpose.EXECUTE, ClaimPurpose.RECONCILE);
        return copy(
                State.RUNNING,
                claimPurpose,
                claimOwner,
                capacityLeaseId,
                claimExpiresAt,
                nextAttemptAt,
                infrastructureAttempts + 1,
                now,
                cancelRequestedAt,
                null,
                null,
                null,
                lastError);
    }

    public DispatchTicket heartbeat(Instant expiresAt)
    {
        if (claimOwner == null) {
            throw new IllegalStateException("cannot heartbeat an unclaimed ticket");
        }
        return copy(
                state,
                claimPurpose,
                claimOwner,
                capacityLeaseId,
                requireNonNull(expiresAt, "expiresAt is null"),
                nextAttemptAt,
                infrastructureAttempts,
                startedAt,
                cancelRequestedAt,
                pendingResult,
                deliveryReceipt,
                completedAt,
                lastError);
    }

    public DispatchTicket requestCancel(Instant now)
    {
        requireNonNull(now, "now is null");
        if (state.isTerminal()
                || state == State.RESULT_PENDING
                || cancelRequestedAt != null) {
            return this;
        }
        if (state == State.REQUESTED || state == State.RETRY_WAIT) {
            return copy(
                    State.RESULT_PENDING,
                    null,
                    null,
                    null,
                    null,
                    now,
                    infrastructureAttempts,
                    startedAt,
                    now,
                    DispatchResult.canceled(envelope.fence()),
                    null,
                    null,
                    "cancel requested before launch");
        }
        return copy(
                state,
                claimPurpose,
                claimOwner,
                capacityLeaseId,
                claimExpiresAt,
                nextAttemptAt,
                infrastructureAttempts,
                startedAt,
                now,
                pendingResult,
                deliveryReceipt,
                completedAt,
                lastError);
    }

    public DispatchTicket resultPending(DispatchResult result, Instant now)
    {
        requireNonNull(result, "result is null");
        requireNonNull(now, "now is null");
        return copy(
                State.RESULT_PENDING,
                null,
                null,
                null,
                null,
                now,
                infrastructureAttempts,
                startedAt,
                cancelRequestedAt,
                result,
                null,
                null,
                result.error());
    }

    public DispatchTicket retryWait(String error, Instant nextAttempt)
    {
        return unclaimed(State.RETRY_WAIT, nextAttempt, error, null);
    }

    public DispatchTicket reconcileWait(String error, Instant nextAttempt)
    {
        return unclaimed(State.RECONCILE_WAIT, nextAttempt, error, null);
    }

    /** Parks an ambiguous Operation until an explicit manual probe/retry. */
    public DispatchTicket manualReconciliation(String error)
    {
        if (state != State.RUNNING) {
            throw new IllegalStateException(
                    "manual reconciliation requires a running attempt");
        }
        return unclaimed(State.RECONCILE_WAIT, null, error, null);
    }

    public DispatchTicket deliveryRetry(String error, Instant nextAttempt)
    {
        if (pendingResult == null) {
            throw new IllegalStateException("delivery retry requires a durable result");
        }
        return unclaimed(State.RESULT_PENDING, nextAttempt, error, pendingResult);
    }

    public DispatchTicket submissionRetry(String error, Instant nextAttempt)
    {
        if (state != State.CLAIMED) {
            throw new IllegalStateException("submission retry requires a claimed ticket");
        }
        return switch (claimPurpose) {
            case EXECUTE -> unclaimed(State.RETRY_WAIT, nextAttempt, error, null);
            case RECONCILE -> unclaimed(
                    State.RECONCILE_WAIT, nextAttempt, error, null);
        };
    }

    public DispatchTicket completeDelivery(DeliveryReceipt receipt, Instant now)
    {
        requireNonNull(receipt, "receipt is null");
        requireNonNull(now, "now is null");
        if (state != State.RESULT_PENDING || pendingResult == null) {
            throw new IllegalStateException("ticket has no result to complete");
        }
        State terminal = switch (pendingResult.outcome()) {
            case SUCCEEDED -> State.SUCCEEDED;
            case FAILED, INDETERMINATE -> State.FAILED;
            case CANCELED -> State.CANCELED;
        };
        return copy(
                terminal,
                null,
                null,
                null,
                null,
                nextAttemptAt,
                infrastructureAttempts,
                startedAt,
                cancelRequestedAt,
                null,
                receipt,
                now,
                lastError);
    }

    public DispatchTicket recoverExpiredClaim(Instant now)
    {
        if (!hasExpiredClaimAt(now)) {
            return this;
        }
        return switch (state) {
            case CLAIMED -> switch (claimPurpose) {
                case EXECUTE -> unclaimed(State.REQUESTED, now, lastError, pendingResult);
                case RECONCILE -> unclaimed(
                        State.RECONCILE_WAIT, now, lastError, pendingResult);
            };
            case RUNNING -> unclaimed(
                    State.RECONCILE_WAIT,
                    now,
                    "execution lease expired; reconciliation required",
                    null);
            default -> this;
        };
    }

    private DispatchTicket unclaimed(
            State target,
            Instant next,
            String error,
            DispatchResult result)
    {
        return copy(
                target,
                null,
                null,
                null,
                null,
                next,
                infrastructureAttempts,
                startedAt,
                cancelRequestedAt,
                result,
                null,
                null,
                error);
    }

    private void requireClaim(ClaimPurpose... allowed)
    {
        if (state != State.CLAIMED) {
            throw new IllegalStateException("ticket is not claimed");
        }
        for (ClaimPurpose purpose : allowed) {
            if (claimPurpose == purpose) {
                return;
            }
        }
        throw new IllegalStateException("unexpected claim purpose: " + claimPurpose);
    }

    private DispatchTicket copy(
            State newState,
            ClaimPurpose newClaimPurpose,
            String newClaimOwner,
            String newCapacityLeaseId,
            Instant newClaimExpiresAt,
            Instant newNextAttemptAt,
            int newInfrastructureAttempts,
            Instant newStartedAt,
            Instant newCancelRequestedAt,
            DispatchResult newPendingResult,
            DeliveryReceipt newDeliveryReceipt,
            Instant newCompletedAt,
            String newLastError)
    {
        return new DispatchTicket(
                id,
                version + 1,
                envelope,
                newState,
                newClaimPurpose,
                newClaimOwner,
                newCapacityLeaseId,
                newClaimExpiresAt,
                createdAt,
                newNextAttemptAt,
                newInfrastructureAttempts,
                newStartedAt,
                newCancelRequestedAt,
                newPendingResult,
                newDeliveryReceipt,
                newCompletedAt,
                newLastError);
    }

    public enum State
    {
        REQUESTED,
        RETRY_WAIT,
        RECONCILE_WAIT,
        RESULT_PENDING,
        CLAIMED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELED;

        public boolean isTerminal()
        {
            return this == SUCCEEDED || this == FAILED || this == CANCELED;
        }
    }

    public enum ClaimPurpose
    {
        EXECUTE,
        RECONCILE
    }

    public enum AsyncFamily
    {
        AGENT_TURN,
        VALIDATION,
        LOCAL_GIT,
        GITHUB_EFFECT,
        REMOTE_OBSERVATION,
        MERGE,
        CLEANUP
    }

    public record OperationFence(
            Long taskEpoch,
            String stageId,
            Long stageGeneration,
            String operationId,
            int attempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha)
    {
        public OperationFence
        {
            requireNonNull(operationId, "operationId is null");
            if (operationId.isBlank() || attempt < 1) {
                throw new IllegalArgumentException(
                        "operationId must be non-blank and attempt must be positive");
            }
            if ((stageId == null) != (stageGeneration == null)) {
                throw new IllegalArgumentException(
                        "stageId and stageGeneration must be supplied together");
            }
            if (taskEpoch != null && taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
            if (stageGeneration != null && stageGeneration < 1) {
                throw new IllegalArgumentException("stageGeneration must be positive");
            }
        }
    }

    public enum OwnerKind
    {
        TRUNK,
        TASK,
        STAGE,
        THREAD_TURN,
        TASK_TURN,
        STAGE_TURN,
        REVIEW_ASSIGNMENT_TURN
    }

    public record OwnerReference(OwnerKind kind, String id, String callbackRoute)
    {
        public OwnerReference
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(id, "id is null");
            requireNonNull(callbackRoute, "callbackRoute is null");
            if (id.isBlank() || callbackRoute.isBlank()) {
                throw new IllegalArgumentException("owner reference fields must not be blank");
            }
        }
    }

    public record DispatchEnvelope(
            String operationKind,
            AsyncFamily family,
            OwnerReference owner,
            OperationFence fence,
            CapacityManager.CapacityRequest capacityRequest)
    {
        public DispatchEnvelope
        {
            requireNonNull(operationKind, "operationKind is null");
            requireNonNull(family, "family is null");
            requireNonNull(owner, "owner is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(capacityRequest, "capacityRequest is null");
            if (operationKind.isBlank()) {
                throw new IllegalArgumentException("operationKind must not be blank");
            }
            if (!fence.operationId().equals(capacityRequest.operationId())) {
                throw new IllegalArgumentException(
                        "fence and capacity request must name the same operation");
            }
            if (capacityRequest.source() != CapacityManager.WorkflowSource.V2) {
                throw new IllegalArgumentException("DispatchTicket requires V2 capacity");
            }
            if (!Objects.equals(
                    fence.taskEpoch(), capacityRequest.scope().taskEpoch())) {
                throw new IllegalArgumentException(
                        "fence and capacity request must name the same Task epoch");
            }
        }
    }

    public record DispatchResult(
            OperationFence fence,
            Outcome outcome,
            String payloadJson,
            String evidenceJson,
            String error)
    {
        public DispatchResult
        {
            requireNonNull(fence, "fence is null");
            requireNonNull(outcome, "outcome is null");
        }

        public static DispatchResult canceled(OperationFence fence)
        {
            return new DispatchResult(
                    fence, Outcome.CANCELED, null, "{}", "cancel requested before launch");
        }
    }

    public enum Outcome
    {
        SUCCEEDED,
        FAILED,
        CANCELED,
        INDETERMINATE
    }

    public record DeliveryReceipt(Acceptance acceptance, String evidenceJson)
    {
        public DeliveryReceipt
        {
            requireNonNull(acceptance, "acceptance is null");
        }
    }

    public enum Acceptance
    {
        ACCEPTED,
        SUPERSEDED,
        REJECTED
    }
}
