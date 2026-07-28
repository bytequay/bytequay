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
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/** Executes or probes one already user-authorized Remote feedback effect. */
public final class RemoteFeedbackEffectOperationHandler
        implements ExecutionPorts.OperationHandler
{
    private final OperationStore store;
    private final EffectGateway effects;
    private final ObjectMapper json;
    private final Clock clock;

    public RemoteFeedbackEffectOperationHandler(
            OperationStore store,
            EffectGateway effects,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        return run(context, ClaimMode.EXECUTE);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        return run(context, ClaimMode.PROBE);
    }

    private DispatchTicket.DispatchResult run(
            ExecutionContext context, ClaimMode mode)
            throws Exception
    {
        DispatchTicket.OperationFence fence = context.envelope().fence();
        Effect effect = store.require(fence.operationId());
        requireExactFence(context, effect);
        if (effect.status() == EffectStatus.SUCCEEDED) {
            return succeeded(fence, effect.externalEffectId(), effect.evidence());
        }
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote feedback effect canceled before claim");
        }

        Instant now = clock.instant();
        Effect claimed = store.claim(
                effect.id(), effect.attemptCount(), mode, context.executionId(),
                now, context.capacityLease().expiresAt());
        try {
            EffectResult result = mode == ClaimMode.EXECUTE
                    ? effects.execute(claimed, context)
                    : effects.probe(claimed, context);
            if (!result.proven()) {
                store.finishIndeterminate(
                        claimed.id(), claimed.attemptCount(), result.evidence(),
                        clock.instant());
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "Remote feedback effect outcome is not proven");
            }
            store.finishSucceeded(
                    claimed.id(), claimed.attemptCount(), result.externalEffectId(),
                    result.evidence(), clock.instant());
            return succeeded(fence, result.externalEffectId(), result.evidence());
        }
        catch (ExecutionPorts.IndeterminateExecutionException e) {
            throw e;
        }
        catch (ExecutionPorts.OperationCanceledException e) {
            store.finishFailed(
                    claimed.id(), claimed.attemptCount(), e.getMessage(), clock.instant());
            throw e;
        }
        catch (RetryableEffectException e) {
            store.finishFailed(
                    claimed.id(), claimed.attemptCount(), e.getMessage(), clock.instant());
            throw new ExecutionPorts.RetryableExecutionException(e.getMessage(), e);
        }
        catch (Exception e) {
            store.finishIndeterminate(
                    claimed.id(), claimed.attemptCount(), e.getMessage(), clock.instant());
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Remote feedback effect failed after claim", e);
        }
    }

    private void requireExactFence(ExecutionContext context, Effect effect)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!"APPLY_REMOTE_FEEDBACK_EFFECT".equals(envelope.operationKind())
                || envelope.owner().kind() != DispatchTicket.OwnerKind.STAGE
                || !"REMOTE_FEEDBACK_EFFECT_RESULT".equals(
                        envelope.owner().callbackRoute())
                || !effect.stageId().equals(envelope.owner().id())
                || !Objects.equals(effect.taskEpoch(), fence.taskEpoch())
                || !effect.stageId().equals(fence.stageId())
                || !Objects.equals(effect.stageGeneration(), fence.stageGeneration())
                || !effect.headSha().equals(fence.expectedHeadSha())
                || !effect.baseSha().equals(fence.expectedBaseSha())
                || !effect.operationId().equals(fence.operationId())
                || !effect.payloadDigest().equals(
                        SqliteRemoteDevelopmentRuntimeStore.digest(effect.payload()))) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Remote feedback effect differs from its dispatch fence");
        }
        if (effect.kind() == EffectKind.PUSH_COMMITS) {
            context.requireWriterCapacityLease();
        }
    }

    private DispatchTicket.DispatchResult succeeded(
            DispatchTicket.OperationFence fence,
            String externalEffectId,
            String evidence)
    {
        try {
            return new DispatchTicket.DispatchResult(
                    fence, DispatchTicket.Outcome.SUCCEEDED,
                    json.writeValueAsString(new EffectResult(
                            true, externalEffectId, evidence)),
                    json.writeValueAsString(new EffectEvidence(
                            externalEffectId, evidence)), null);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode Remote effect result", e);
        }
    }

    public interface OperationStore
    {
        Effect require(String operationId);

        Effect claim(
                String effectId,
                int expectedAttemptCount,
                ClaimMode mode,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        void finishSucceeded(
                String effectId,
                int attempt,
                String externalEffectId,
                String evidence,
                Instant completedAt);

        void finishFailed(
                String effectId, int attempt, String error, Instant completedAt);

        void finishIndeterminate(
                String effectId, int attempt, String evidence, Instant completedAt);
    }

    @FunctionalInterface
    public interface EffectGateway
    {
        EffectResult execute(Effect effect, ExecutionContext context)
                throws Exception;

        default EffectResult probe(Effect effect, ExecutionContext context)
                throws Exception
        {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Remote effect adapter has no exact probe");
        }
    }

    public enum ClaimMode { EXECUTE, PROBE }

    public enum EffectStatus { REQUESTED, CLAIMED, SUCCEEDED, FAILED, INDETERMINATE }

    public enum EffectKind
    {
        POST_INLINE_REPLY,
        POST_TOP_LEVEL_REPLY,
        SUBMIT_REVIEW,
        REQUEST_REVIEWER,
        POST_MAINTAINER_NUDGE,
        RESOLVE_THREAD,
        PUSH_COMMITS
    }

    public record Effect(
            String id,
            String operationId,
            String authorizationId,
            String batchId,
            int ordinal,
            EffectKind kind,
            String remoteInboxItemId,
            String externalTarget,
            String reviewAction,
            String payload,
            String payloadDigest,
            String idempotencyKey,
            EffectStatus status,
            int attemptCount,
            int attemptLimit,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String headSha,
            String baseSha,
            String externalEffectId,
            String evidence)
    {
        public Effect
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(status, "status is null");
        }
    }

    public record EffectResult(
            boolean proven, String externalEffectId, String evidence)
    {
        public EffectResult
        {
            if (proven && (externalEffectId == null || externalEffectId.isBlank()
                    || evidence == null || evidence.isBlank())) {
                throw new IllegalArgumentException(
                        "Proven Remote effect requires identity and evidence");
            }
        }
    }

    private record EffectEvidence(String externalEffectId, String evidence) {}

    public static final class RetryableEffectException
            extends Exception
    {
        public RetryableEffectException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }
    }
}
