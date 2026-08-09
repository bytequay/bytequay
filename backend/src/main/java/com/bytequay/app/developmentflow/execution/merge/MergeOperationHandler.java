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
package com.bytequay.app.developmentflow.execution.merge;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.MERGE;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Executes one exact-head merge effect and parks while RemoteObserver owns truth. */
public final class MergeOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "MERGE_REMOTE_PULL_REQUEST";
    public static final String CALLBACK_ROUTE = "REMOTE_MERGE_RESULT";

    private static final int PAYLOAD_VERSION = 1;

    private final OperationStore operations;
    private final MergeEffects effects;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration observationPoll;

    public MergeOperationHandler(
            OperationStore operations,
            MergeEffects effects,
            ObjectMapper json,
            Clock clock,
            Duration observationPoll)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.observationPoll = positive(observationPoll, "observationPoll");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        return drive(context);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        return drive(context);
    }

    private DispatchTicket.DispatchResult drive(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        String operationId = context.envelope().fence().operationId();
        Instant now = clock.instant();
        operations.reconcileAcceptedObservation(operationId, now);
        OperationSnapshot snapshot = operations.requireByOperationId(operationId);
        validate(context.envelope(), snapshot.request());

        if (snapshot.request().status().isTerminal()) {
            return terminal(context.envelope(), snapshot.request());
        }
        if (context.isCancellationRequested()) {
            operations.cancel(operationId, "merge canceled before the next effect", now);
            return terminal(context.envelope(),
                    operations.requireByOperationId(operationId).request());
        }

        Optional<ClaimSpec> next = snapshot.nextClaim(now);
        if (next.isEmpty()) {
            if (snapshot.request().attemptCount() >= snapshot.request().attemptLimit()
                    && (snapshot.needsAnotherProbe(now)
                        || snapshot.hasBouncedQueueEntry())) {
                operations.block(
                        operationId,
                        BlockReason.MANUAL_INTERVENTION,
                        "merge observation/probe budget exhausted",
                        now);
                return terminal(context.envelope(),
                        operations.requireByOperationId(operationId).request());
            }
            throw deferred("waiting for accepted remote merge truth",
                    snapshot.retryAt(now, observationPoll));
        }

        ClaimSpec claimSpec = next.orElseThrow();
        PreparedEffect prepared;
        try {
            prepared = requireNonNull(
                    effects.prepare(snapshot.request(), claimSpec),
                    "prepared merge effect is null");
        }
        catch (RemoteTruthPendingException pending) {
            throw deferred(pending.getMessage(), now.plus(observationPoll));
        }
        catch (SubjectRejectedException rejected) {
            operations.block(
                    operationId,
                    BlockReason.MERGEABILITY_REGRESSED,
                    rejected.getMessage(),
                    clock.instant());
            return terminal(context.envelope(),
                    operations.requireByOperationId(operationId).request());
        }
        catch (PermissionDeniedException denied) {
            operations.block(
                    operationId,
                    BlockReason.PERMISSION_DENIED,
                    denied.getMessage(),
                    clock.instant());
            return terminal(context.envelope(),
                    operations.requireByOperationId(operationId).request());
        }
        catch (RuntimeException failure) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "merge preflight failed before any remote mutation: "
                            + failure.getMessage(),
                    failure);
        }

        now = clock.instant();
        snapshot = operations.requireByOperationId(operationId);
        validate(context.envelope(), snapshot.request());
        if (snapshot.request().status().isTerminal()) {
            return terminal(context.envelope(), snapshot.request());
        }
        if (context.isCancellationRequested()) {
            operations.cancel(operationId, "merge canceled before effect claim", now);
            return terminal(context.envelope(),
                    operations.requireByOperationId(operationId).request());
        }
        if (snapshot.nextClaim(now).filter(claimSpec::equals).isEmpty()) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "merge operation changed during preflight");
        }

        Instant leaseUntil = min(
                context.capacityLease().expiresAt(), now.plus(observationPoll));
        if (!leaseUntil.isAfter(now)) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "merge capacity lease expired before effect claim");
        }
        EffectClaim claim = operations.tryClaim(
                        operationId, claimSpec, context.executionId(),
                        now, leaseUntil)
                .orElseThrow(() -> new ExecutionPorts.RetryableExecutionException(
                        "merge effect claim changed concurrently"));
        try {
            EffectEvidence evidence = effects.perform(
                    snapshot.request(), claim, prepared);
            if (claim.spec().mode() == ClaimMode.EXECUTE
                    && evidence.observedByProbe()) {
                throw deferred(
                        "remote terminal truth awaits accepted RemoteObserver evidence",
                        leaseUntil);
            }
            if (!operations.markAwaiting(claim, evidence, clock.instant())) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "merge effect claim changed before its evidence committed");
            }
            throw deferred("merge effect awaits RemoteObserver", leaseUntil);
        }
        catch (RemoteTruthPendingException pending) {
            throw deferred(pending.getMessage(), leaseUntil);
        }
        catch (SubjectRejectedException rejected) {
            operations.block(
                    operationId,
                    BlockReason.MERGEABILITY_REGRESSED,
                    rejected.getMessage(),
                    clock.instant());
            return terminal(context.envelope(),
                    operations.requireByOperationId(operationId).request());
        }
        catch (PermissionDeniedException denied) {
            operations.block(
                    operationId,
                    BlockReason.PERMISSION_DENIED,
                    denied.getMessage(),
                    clock.instant());
            return terminal(context.envelope(),
                    operations.requireByOperationId(operationId).request());
        }
        catch (ExecutionPorts.OperationDeferredException deferred) {
            throw deferred;
        }
        catch (RuntimeException unknown) {
            String detail = "merge effect outcome is unknown: " + unknown.getMessage();
            if (!operations.markIndeterminate(claim, detail, clock.instant())) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "merge effect and its durable claim diverged", unknown);
            }
            throw new ExecutionPorts.IndeterminateExecutionException(detail, unknown);
        }
    }

    private DispatchTicket.DispatchResult terminal(
            DispatchTicket.DispatchEnvelope envelope, MergeRequest request)
    {
        MergeResult payload = new MergeResult(
                PAYLOAD_VERSION, request.mergeOperationId(), request.operationId(),
                request.taskId(), request.stageId(), request.status(),
                request.headSha(), request.baseSha(), request.lastError());
        DispatchTicket.Outcome outcome = switch (request.status()) {
            case SUCCEEDED -> SUCCEEDED;
            case CANCELED -> CANCELED;
            case FAILED, BLOCKED -> FAILED;
            default -> throw new IllegalStateException(
                    "non-terminal merge operation cannot produce a result");
        };
        return new DispatchTicket.DispatchResult(
                envelope.fence(), outcome, json(payload), json(payload),
                request.lastError());
    }

    private static void validate(
            DispatchTicket.DispatchEnvelope envelope, MergeRequest request)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        CapacityManager.CapacityScope scope = capacity.scope();
        boolean liveOwner = request.status().isTerminal()
                || ("V2".equals(request.workflowVersion())
                    && "ACTIVE".equals(request.taskLifecycle())
                    && request.currentTaskEpoch() == request.taskEpoch()
                    && request.stageId().equals(request.currentStageId())
                    && request.currentStageGeneration() == request.stageGeneration()
                    && "MERGING".equals(request.stageCheckpoint()));
        boolean exact = OPERATION_KIND.equals(envelope.operationKind())
                && envelope.family() == DispatchTicket.AsyncFamily.MERGE
                && envelope.owner().kind() == STAGE
                && envelope.owner().id().equals(request.stageId())
                && CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                && fence.taskEpoch() != null
                && fence.taskEpoch() == request.taskEpoch()
                && request.stageId().equals(fence.stageId())
                && fence.stageGeneration() != null
                && fence.stageGeneration() == request.stageGeneration()
                && fence.operationId().equals(request.operationId())
                && fence.attempt() == request.semanticAttempt()
                && fence.expectedCodeFingerprint() == null
                && request.headSha().equals(fence.expectedHeadSha())
                && request.baseSha().equals(fence.expectedBaseSha())
                && capacity.source() == V2
                && capacity.lanes().equals(ImmutableSet.of(MERGE))
                && !capacity.trunkControl()
                && capacity.exclusiveTask()
                && !capacity.writerRequired()
                && request.workspaceId().equals(scope.workspaceId())
                && request.trunkId().equals(scope.trunkId())
                && request.taskId().equals(scope.taskId())
                && scope.taskEpoch() != null
                && scope.taskEpoch() == request.taskEpoch()
                && liveOwner;
        if (!exact) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "merge envelope or persisted exact-head owner is stale");
        }
    }

    private ExecutionPorts.OperationDeferredException deferred(
            String message, Instant retryAt)
    {
        return new ExecutionPorts.OperationDeferredException(message, retryAt);
    }

    private String json(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serializing typed merge evidence failed", e);
        }
    }

    private static Instant min(Instant left, Instant right)
    {
        return left.isBefore(right) ? left : right;
    }

    private static Duration positive(Duration value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public interface OperationStore
    {
        OperationSnapshot requireByOperationId(String operationId);

        void reconcileAcceptedObservation(String operationId, Instant at);

        Optional<EffectClaim> tryClaim(
                String operationId,
                ClaimSpec spec,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        boolean markAwaiting(EffectClaim claim, EffectEvidence evidence, Instant at);

        boolean markIndeterminate(EffectClaim claim, String detail, Instant at);

        void block(
                String operationId, BlockReason reason, String detail, Instant at);

        void cancel(String operationId, String detail, Instant at);
    }

    public interface MergeEffects
    {
        PreparedEffect prepare(MergeRequest request, ClaimSpec claim);

        EffectEvidence perform(
                MergeRequest request, EffectClaim claim, PreparedEffect prepared);
    }

    public interface PreparedEffect {}

    public enum MergeMode
    {
        DIRECT,
        MERGE_QUEUE
    }

    public enum OperationStatus
    {
        REQUESTED,
        CLAIMED,
        QUEUE_ENTERED,
        AWAITING_OBSERVATION,
        SUCCEEDED,
        FAILED,
        BLOCKED,
        CANCELED;

        public boolean isTerminal()
        {
            return this == SUCCEEDED || this == FAILED
                    || this == BLOCKED || this == CANCELED;
        }
    }

    public enum AttemptStatus
    {
        CLAIMED,
        AWAITING_OBSERVATION,
        SUCCEEDED,
        FAILED,
        INDETERMINATE
    }

    public enum QueueEntryStatus
    {
        ENTERED,
        BOUNCED,
        MERGED,
        REMOVED
    }

    public enum EffectKind
    {
        DIRECT_MERGE,
        ENTER_QUEUE
    }

    public enum ClaimMode
    {
        EXECUTE,
        PROBE
    }

    public enum BlockReason
    {
        PERMISSION_DENIED,
        MERGEABILITY_REGRESSED,
        MANUAL_INTERVENTION
    }

    public record MergeRequest(
            String mergeOperationId,
            String authorizationId,
            String authorizationReadinessId,
            String operationId,
            String stageId,
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            long stageGeneration,
            int semanticAttempt,
            MergeMode mode,
            String mergeMethod,
            OperationStatus status,
            int attemptCount,
            int attemptLimit,
            int queueBounceCount,
            int maxQueueReenqueues,
            String headSha,
            String baseSha,
            String remoteRepositoryId,
            int remotePrNumber,
            String currentReadinessId,
            String workflowVersion,
            String taskLifecycle,
            long currentTaskEpoch,
            String currentStageId,
            long currentStageGeneration,
            String stageCheckpoint,
            String lastError)
    {
        public MergeRequest
        {
            requireText(mergeOperationId, "mergeOperationId");
            requireText(authorizationId, "authorizationId");
            requireText(authorizationReadinessId, "authorizationReadinessId");
            requireText(operationId, "operationId");
            requireText(stageId, "stageId");
            requireText(taskId, "taskId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireNonNull(mode, "mode is null");
            requireText(mergeMethod, "mergeMethod");
            if (!ImmutableSet.of("merge", "squash", "rebase").contains(mergeMethod)) {
                throw new IllegalArgumentException("unsupported mergeMethod");
            }
            requireNonNull(status, "status is null");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            requireText(remoteRepositoryId, "remoteRepositoryId");
            requireText(workflowVersion, "workflowVersion");
            requireText(taskLifecycle, "taskLifecycle");
            if (taskEpoch < 1 || stageGeneration < 1 || semanticAttempt < 1
                    || attemptCount < 0 || attemptLimit < 1
                    || queueBounceCount < 0 || maxQueueReenqueues < 0
                    || remotePrNumber < 1 || currentTaskEpoch < 1
                    || currentStageGeneration < 0) {
                throw new IllegalArgumentException("merge operation counters are invalid");
            }
        }
    }

    public record EffectAttempt(
            String id,
            int ordinal,
            EffectKind kind,
            Integer effectOrdinal,
            String readinessEvidenceId,
            String idempotencyKey,
            ClaimMode claimMode,
            AttemptStatus status,
            Instant claimedAt,
            Instant leaseUntil)
    {
        public EffectAttempt
        {
            requireText(id, "id");
            requireNonNull(kind, "kind is null");
            requireText(readinessEvidenceId, "readinessEvidenceId");
            requireText(idempotencyKey, "idempotencyKey");
            requireNonNull(claimMode, "claimMode is null");
            requireNonNull(status, "status is null");
            requireNonNull(claimedAt, "claimedAt is null");
            requireNonNull(leaseUntil, "leaseUntil is null");
            if (ordinal < 1 || !leaseUntil.isAfter(claimedAt)
                    || (kind == EffectKind.DIRECT_MERGE) != (effectOrdinal == null)) {
                throw new IllegalArgumentException("merge attempt identity is invalid");
            }
        }
    }

    public record QueueEntry(int ordinal, QueueEntryStatus status)
    {
        public QueueEntry
        {
            requireNonNull(status, "status is null");
            if (ordinal < 1) {
                throw new IllegalArgumentException("queue ordinal must be positive");
            }
        }
    }

    public record OperationSnapshot(
            MergeRequest request,
            Optional<EffectAttempt> latestAttempt,
            Optional<QueueEntry> latestQueueEntry)
    {
        public OperationSnapshot
        {
            requireNonNull(request, "request is null");
            requireNonNull(latestAttempt, "latestAttempt is null");
            requireNonNull(latestQueueEntry, "latestQueueEntry is null");
        }

        Optional<ClaimSpec> nextClaim(Instant now)
        {
            requireNonNull(now, "now is null");
            if (request.status().isTerminal()
                    || request.attemptCount() >= request.attemptLimit()) {
                return Optional.empty();
            }
            if (latestQueueEntry.isPresent()
                    && latestQueueEntry.orElseThrow().status() == QueueEntryStatus.BOUNCED) {
                int nextOrdinal = latestQueueEntry.orElseThrow().ordinal() + 1;
                if (nextOrdinal > request.maxQueueReenqueues() + 1
                        || request.currentReadinessId() == null) {
                    return Optional.empty();
                }
                return Optional.of(new ClaimSpec(
                        ClaimMode.EXECUTE, EffectKind.ENTER_QUEUE, nextOrdinal,
                        request.currentReadinessId(),
                        request.operationId() + ":queue:" + nextOrdinal));
            }
            if (latestAttempt.isEmpty()) {
                EffectKind kind = request.mode() == MergeMode.DIRECT
                        ? EffectKind.DIRECT_MERGE : EffectKind.ENTER_QUEUE;
                Integer effectOrdinal = kind == EffectKind.ENTER_QUEUE ? 1 : null;
                return Optional.of(new ClaimSpec(
                        ClaimMode.EXECUTE, kind, effectOrdinal,
                        request.authorizationReadinessId(),
                        request.operationId() + (kind == EffectKind.DIRECT_MERGE
                                ? ":direct" : ":queue:1")));
            }
            EffectAttempt attempt = latestAttempt.orElseThrow();
            if ((attempt.status() == AttemptStatus.CLAIMED
                    || attempt.status() == AttemptStatus.AWAITING_OBSERVATION)
                    && attempt.leaseUntil().isAfter(now)) {
                return Optional.empty();
            }
            if (attempt.status() == AttemptStatus.INDETERMINATE
                    || attempt.status() == AttemptStatus.CLAIMED
                    || attempt.status() == AttemptStatus.AWAITING_OBSERVATION) {
                return Optional.of(new ClaimSpec(
                        ClaimMode.PROBE, attempt.kind(), attempt.effectOrdinal(),
                        attempt.readinessEvidenceId(), attempt.idempotencyKey()));
            }
            return Optional.empty();
        }

        boolean needsAnotherProbe(Instant now)
        {
            return latestAttempt.filter(attempt ->
                            attempt.status() == AttemptStatus.INDETERMINATE
                                    || ((attempt.status() == AttemptStatus.CLAIMED
                                            || attempt.status() == AttemptStatus.AWAITING_OBSERVATION)
                                        && !attempt.leaseUntil().isAfter(now)))
                    .isPresent();
        }

        boolean hasBouncedQueueEntry()
        {
            return latestQueueEntry
                    .filter(entry -> entry.status() == QueueEntryStatus.BOUNCED)
                    .isPresent();
        }

        Instant retryAt(Instant now, Duration poll)
        {
            return latestAttempt
                    .filter(attempt -> attempt.status() == AttemptStatus.CLAIMED
                            || attempt.status() == AttemptStatus.AWAITING_OBSERVATION)
                    .map(EffectAttempt::leaseUntil)
                    .filter(at -> at.isAfter(now))
                    .orElseGet(() -> now.plus(poll));
        }
    }

    public record ClaimSpec(
            ClaimMode mode,
            EffectKind kind,
            Integer effectOrdinal,
            String readinessEvidenceId,
            String idempotencyKey)
    {
        public ClaimSpec
        {
            requireNonNull(mode, "mode is null");
            requireNonNull(kind, "kind is null");
            requireText(readinessEvidenceId, "readinessEvidenceId");
            requireText(idempotencyKey, "idempotencyKey");
        }
    }

    public record EffectClaim(
            String id,
            String mergeOperationId,
            int ordinal,
            ClaimSpec spec,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        public EffectClaim
        {
            requireText(id, "id");
            requireText(mergeOperationId, "mergeOperationId");
            requireNonNull(spec, "spec is null");
            requireText(claimOwner, "claimOwner");
            requireNonNull(claimedAt, "claimedAt is null");
            requireNonNull(leaseUntil, "leaseUntil is null");
            if (ordinal < 1 || !leaseUntil.isAfter(claimedAt)) {
                throw new IllegalArgumentException("merge effect claim is invalid");
            }
        }
    }

    public record EffectEvidence(
            String externalEffectId,
            String detail,
            boolean observedByProbe)
    {
        public EffectEvidence
        {
            requireText(detail, "detail");
            if (externalEffectId != null && externalEffectId.isBlank()) {
                throw new IllegalArgumentException("externalEffectId must not be blank");
            }
        }
    }

    public record MergeResult(
            int version,
            String mergeOperationId,
            String operationId,
            String taskId,
            String stageId,
            OperationStatus status,
            String headSha,
            String baseSha,
            String detail) {}

    public static class SubjectRejectedException
            extends RuntimeException
    {
        public SubjectRejectedException(String message)
        {
            super(message);
        }
    }

    public static final class RemoteTruthPendingException
            extends RuntimeException
    {
        public RemoteTruthPendingException(String message)
        {
            super(message);
        }
    }

    public static final class PermissionDeniedException
            extends RuntimeException
    {
        public PermissionDeniedException(String message)
        {
            super(message);
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
