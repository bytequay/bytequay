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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Marks one exact Draft ready and waits for accepted non-Draft observation. */
public final class RemoteMarkReadyOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "MARK_REMOTE_PR_READY";
    public static final String CALLBACK_ROUTE = "REMOTE_MARK_READY_RESULT";

    private final OperationStore store;
    private final MarkReadyGateway github;
    private final ObjectMapper json;
    private final Clock clock;

    public RemoteMarkReadyOperationHandler(
            OperationStore store,
            MarkReadyGateway github,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.github = requireNonNull(github, "github is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        Operation operation = store.require(context.envelope().fence().operationId());
        requireExactFence(context, operation);
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Mark-ready canceled before GitHub mutation");
        }
        Operation claimed = store.claim(
                operation.id(), operation.attemptCount(), "EXECUTE",
                context.executionId(), clock.instant(),
                context.capacityLease().expiresAt());
        try {
            github.markReady(claimed, context);
            store.awaitObservation(claimed.id(), claimed.attemptCount());
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Mark-ready submitted; accepted non-Draft observation pending");
        }
        catch (ExecutionPorts.IndeterminateExecutionException e) {
            throw e;
        }
        catch (RetryableMarkReadyException e) {
            store.finishFailed(
                    claimed.id(), claimed.attemptCount(), e.getMessage(), clock.instant());
            throw new ExecutionPorts.RetryableExecutionException(e.getMessage(), e);
        }
        catch (Exception e) {
            store.finishIndeterminate(
                    claimed.id(), claimed.attemptCount(), e.getMessage(), clock.instant());
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Mark-ready outcome is unknown", e);
        }
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        Operation operation = store.require(context.envelope().fence().operationId());
        requireExactFence(context, operation);
        if (operation.status() == Status.SUCCEEDED) {
            return success(context.envelope().fence(), operation.resultSnapshotId(),
                    operation.evidence());
        }
        if (operation.status() == Status.INDETERMINATE) {
            Operation claimed = store.claim(
                    operation.id(), operation.attemptCount(), "PROBE",
                    context.executionId(), clock.instant(),
                    context.capacityLease().expiresAt());
            store.awaitObservation(claimed.id(), claimed.attemptCount());
            operation = store.require(claimed.operationId());
        }
        else if (operation.status() == Status.CLAIMED) {
            store.awaitObservation(operation.id(), operation.attemptCount());
            operation = store.require(operation.operationId());
        }
        if (operation.status() != Status.AWAITING_OBSERVATION) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Mark-ready is not in a probe-safe state");
        }
        Observation observation = store.findAcceptedReadyObservation(operation)
                .orElse(null);
        if (observation == null) {
            throw new ExecutionPorts.RetryableExecutionException(
                    "Accepted non-Draft observation is not available yet");
        }
        store.finishSucceeded(
                operation.id(), observation.snapshotId(), observation.evidence(),
                clock.instant());
        return success(context.envelope().fence(), observation.snapshotId(),
                observation.evidence());
    }

    private static void requireExactFence(
            ExecutionContext context, Operation operation)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.owner().kind() != DispatchTicket.OwnerKind.STAGE
                || !CALLBACK_ROUTE.equals(
                        envelope.owner().callbackRoute())
                || !operation.stageId().equals(envelope.owner().id())
                || !operation.operationId().equals(fence.operationId())
                || !Objects.equals(operation.taskEpoch(), fence.taskEpoch())
                || !operation.stageId().equals(fence.stageId())
                || !Objects.equals(operation.stageGeneration(), fence.stageGeneration())
                || operation.semanticAttempt() != fence.attempt()
                || !operation.headSha().equals(fence.expectedHeadSha())
                || !operation.baseSha().equals(fence.expectedBaseSha())) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Mark-ready operation differs from its dispatch fence");
        }
    }

    private DispatchTicket.DispatchResult success(
            DispatchTicket.OperationFence fence,
            String snapshotId,
            String evidence)
    {
        try {
            String payload = json.writeValueAsString(
                    new Observation(true, snapshotId, evidence));
            return new DispatchTicket.DispatchResult(
                    fence, DispatchTicket.Outcome.SUCCEEDED, payload, payload, null);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode mark-ready result", e);
        }
    }

    public interface OperationStore
    {
        Operation require(String operationId);

        Operation claim(
                String id,
                int expectedAttemptCount,
                String claimMode,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        void awaitObservation(String id, int attempt);

        void finishSucceeded(
                String id, String snapshotId, String evidence, Instant completedAt);

        void finishFailed(String id, int attempt, String error, Instant completedAt);

        void finishIndeterminate(
                String id, int attempt, String evidence, Instant completedAt);

        Optional<Observation> findAcceptedReadyObservation(Operation operation);
    }

    public interface MarkReadyGateway
    {
        void markReady(Operation operation, ExecutionContext context)
                throws Exception;
    }

    public enum Status
    {
        REQUESTED,
        CLAIMED,
        AWAITING_OBSERVATION,
        SUCCEEDED,
        FAILED,
        INDETERMINATE,
        CANCELED
    }

    public record Operation(
            String id,
            String operationId,
            String authorizationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            String repositoryId,
            int pullRequestNumber,
            String headSha,
            String baseSha,
            Status status,
            int attemptCount,
            int attemptLimit,
            String resultSnapshotId,
            String evidence)
    {
        public Operation
        {
            requireNonNull(repositoryId, "repositoryId is null");
            if (repositoryId.isBlank() || pullRequestNumber < 1) {
                throw new IllegalArgumentException(
                        "Mark-ready Remote identity is invalid");
            }
        }
    }

    public record Observation(boolean open, String snapshotId, String evidence)
    {
        public Observation
        {
            if (open && (snapshotId == null || snapshotId.isBlank()
                    || evidence == null || evidence.isBlank())) {
                throw new IllegalArgumentException(
                        "Open observation requires snapshot and evidence");
            }
        }
    }

    public static final class RetryableMarkReadyException
            extends Exception
    {
        public RetryableMarkReadyException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }
    }
}
