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
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.RetryableActionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static java.util.Objects.requireNonNull;

/** Executes one exact comment-only review owned by a zero-Task BUILD Trunk. */
public final class ReviewBuildCommentOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND =
            "APPLY_REVIEW_BUILD_COMMENTS";
    public static final String CALLBACK_ROUTE =
            "REVIEW_BUILD_COMMENT_RESULT";
    public static final String REVIEW_PASS_OPERATION_KIND =
            "PUBLISH_STANDALONE_REVIEW_PASS";
    public static final String REVIEW_PASS_CALLBACK_ROUTE =
            "STANDALONE_REVIEW_PASS_PUBLICATION_RESULT";
    private static final Duration REVIEW_PROPAGATION_POLL =
            Duration.ofSeconds(5);

    private final OperationStore store;
    private final Gateway gateway;
    private final ObjectMapper json;
    private final Clock clock;

    public ReviewBuildCommentOperationHandler(
            OperationStore store, Gateway gateway, ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.gateway = requireNonNull(gateway, "gateway is null");
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
        CommentAction action = store.require(fence.operationId());
        requireExactFence(context, action);
        if (action.status() == ActionStatus.SUCCEEDED) {
            return succeeded(fence, action.externalEffectId(), action.evidence());
        }
        if (action.status() == ActionStatus.CANCELED) {
            return DispatchTicket.DispatchResult.canceled(fence);
        }
        if (action.status() == ActionStatus.ABANDONED) {
            return failed(fence,
                    "review build comment authorization is stale or exhausted");
        }
        if (mode == ClaimMode.EXECUTE && context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "review build comments canceled before claim");
        }

        CommentAction claimed = store.claim(
                action.id(), action.attemptCount(), mode,
                context.executionId(), clock.instant(),
                context.capacityLease().expiresAt());
        if (claimed.status() == ActionStatus.ABANDONED) {
            return failed(fence,
                    "review build comment authorization is stale or exhausted");
        }
        boolean firstBaseline = claimed.recoveryBaseline() == null;
        if (firstBaseline) {
            try {
                List<String> baseline = List.copyOf(requireNonNull(
                        gateway.captureBaseline(claimed, context),
                        "review build comment baseline is null"));
                store.recordRecoveryBaseline(
                        claimed.id(), claimed.attemptCount(), baseline);
                claimed = store.require(claimed.operationId());
            }
            catch (ExecutionPorts.OperationCanceledException failure) {
                store.finishCanceled(
                        claimed.id(), claimed.attemptCount(),
                        failure.getMessage(), clock.instant());
                throw failure;
            }
            catch (Exception failure) {
                store.finishFailed(
                        claimed.id(), claimed.attemptCount(), message(failure),
                        clock.instant());
                if (store.require(claimed.operationId()).status()
                        == ActionStatus.ABANDONED) {
                    return failed(fence, message(failure));
                }
                throw new ExecutionPorts.RetryableExecutionException(
                        "review build comment baseline read failed", failure);
            }
        }
        try {
            EffectResult result = mode == ClaimMode.EXECUTE || firstBaseline
                    ? gateway.execute(claimed, context)
                    : gateway.probe(claimed, context);
            if (!result.proven()) {
                Instant observedAt = clock.instant();
                Instant retryAt = observedAt.plus(REVIEW_PROPAGATION_POLL);
                boolean deferred = store.deferProbe(
                        claimed.id(), claimed.attemptCount(), observedAt,
                        retryAt, result.evidence());
                if (!deferred) {
                    return failed(fence,
                            "suggested-change review needs attention: "
                                    + "observation budget exhausted");
                }
                throw new ExecutionPorts.OperationDeferredException(
                        result.evidence(), retryAt);
            }
            store.finishSucceeded(
                    claimed.id(), claimed.attemptCount(),
                    result.externalEffectId(), result.evidence(),
                    clock.instant());
            return succeeded(fence, result.externalEffectId(), result.evidence());
        }
        catch (ExecutionPorts.OperationDeferredException deferred) {
            // GitHub review and inline-comment reads can lag review creation.
            // Keep the exact action claimed and probe the same semantic
            // attempt again; a second review is never authorized.
            throw deferred;
        }
        catch (ExecutionPorts.IndeterminateExecutionException failure) {
            store.finishIndeterminate(
                    claimed.id(), claimed.attemptCount(), failure.getMessage(),
                    clock.instant());
            if (store.require(claimed.operationId()).status()
                    == ActionStatus.ABANDONED) {
                return failed(fence, failure.getMessage());
            }
            throw failure;
        }
        catch (ExecutionPorts.OperationCanceledException failure) {
            store.finishCanceled(
                    claimed.id(), claimed.attemptCount(), failure.getMessage(),
                    clock.instant());
            throw failure;
        }
        catch (RetryableActionException failure) {
            store.finishFailed(
                    claimed.id(), claimed.attemptCount(), failure.getMessage(),
                    clock.instant());
            if (store.require(claimed.operationId()).status()
                    == ActionStatus.ABANDONED) {
                return failed(fence, failure.getMessage());
            }
            throw new ExecutionPorts.RetryableExecutionException(
                    failure.getMessage(), failure);
        }
        catch (Exception failure) {
            store.finishIndeterminate(
                    claimed.id(), claimed.attemptCount(), message(failure),
                    clock.instant());
            if (store.require(claimed.operationId()).status()
                    == ActionStatus.ABANDONED) {
                return failed(fence, message(failure));
            }
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "review build comment submission failed after claim",
                    failure);
        }
    }

    private static void requireExactFence(
            ExecutionContext context, CommentAction action)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        DispatchTicket.OperationFence fence = envelope.fence();
        boolean standalone = action.id().startsWith(
                SqliteReviewPassPublicationStore.ACTION_PREFIX);
        String operationKind = standalone
                ? REVIEW_PASS_OPERATION_KIND : OPERATION_KIND;
        String callbackRoute = standalone
                ? REVIEW_PASS_CALLBACK_ROUTE : CALLBACK_ROUTE;
        if (!operationKind.equals(envelope.operationKind())
                || envelope.owner().kind() != DispatchTicket.OwnerKind.TRUNK
                || !callbackRoute.equals(envelope.owner().callbackRoute())
                || !action.threadId().equals(envelope.owner().id())
                || fence.taskEpoch() != null
                || fence.stageId() != null
                || fence.stageGeneration() != null
                || action.semanticAttempt() != fence.attempt()
                || fence.expectedCodeFingerprint() != null
                || !action.expectedHeadSha().equals(fence.expectedHeadSha())
                || fence.expectedBaseSha() != null
                || !action.operationId().equals(fence.operationId())
                || !action.payloadDigest().equals(
                        digest(action.payloadJson()))) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "review build comments differ from their dispatch fence");
        }
    }

    private DispatchTicket.DispatchResult succeeded(
            DispatchTicket.OperationFence fence,
            String externalEffectId,
            String evidence)
    {
        try {
            EffectResult result = new EffectResult(
                    true, externalEffectId, evidence);
            return new DispatchTicket.DispatchResult(
                    fence, DispatchTicket.Outcome.SUCCEEDED,
                    json.writeValueAsString(result),
                    json.writeValueAsString(new EffectEvidence(
                            externalEffectId, evidence)), null);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Cannot encode review build comment result", failure);
        }
    }

    private static DispatchTicket.DispatchResult failed(
            DispatchTicket.OperationFence fence, String error)
    {
        return new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.FAILED, null, "{}",
                requireNonNull(error, "error is null"));
    }

    public interface OperationStore
    {
        CommentAction require(String operationId);

        CommentAction claim(
                String actionId,
                int expectedAttemptCount,
                ClaimMode mode,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        void recordRecoveryBaseline(
                String actionId, int attempt, List<String> remoteEffectIds);

        boolean deferProbe(
                String actionId, int attempt, Instant observedAt,
                Instant retryAt,
                String evidence);

        void finishSucceeded(
                String actionId, int attempt, String externalEffectId,
                String evidence, Instant completedAt);

        void finishFailed(
                String actionId, int attempt, String error, Instant completedAt);

        void finishIndeterminate(
                String actionId, int attempt, String evidence,
                Instant completedAt);

        void finishCanceled(
                String actionId, int attempt, String error, Instant completedAt);
    }

    public interface Gateway
    {
        List<String> captureBaseline(
                CommentAction action, ExecutionContext context)
                throws Exception;

        EffectResult execute(CommentAction action, ExecutionContext context)
                throws Exception;

        EffectResult probe(CommentAction action, ExecutionContext context)
                throws Exception;
    }

    public record CommentAction(
            String id,
            String operationId,
            ActionStatus status,
            int semanticAttempt,
            int attemptCount,
            int attemptLimit,
            String threadId,
            String reviewPassId,
            String commandId,
            String workspaceId,
            String remoteRepositoryId,
            String headRepositoryId,
            int pullRequestNumber,
            String branchName,
            String expectedHeadSha,
            String payloadJson,
            String payloadDigest,
            ActionPayload payload,
            Instant authorizedAt,
            List<String> recoveryBaseline,
            String externalEffectId,
            String evidence)
    {
        public CommentAction
        {
            requireText(id, "id");
            requireText(operationId, "operationId");
            requireNonNull(status, "status is null");
            requireText(threadId, "threadId");
            requireText(reviewPassId, "reviewPassId");
            requireText(commandId, "commandId");
            requireText(workspaceId, "workspaceId");
            requireText(remoteRepositoryId, "remoteRepositoryId");
            requireText(headRepositoryId, "headRepositoryId");
            requireText(branchName, "branchName");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(payloadJson, "payloadJson");
            requireText(payloadDigest, "payloadDigest");
            requireNonNull(payload, "payload is null");
            requireNonNull(authorizedAt, "authorizedAt is null");
            recoveryBaseline = recoveryBaseline == null
                    ? null : List.copyOf(recoveryBaseline);
            if (semanticAttempt != 1 || attemptCount < 0 || attemptLimit < 1
                    || pullRequestNumber < 1) {
                throw new IllegalArgumentException(
                        "review build comment action fence is invalid");
            }
        }
    }

    private static String message(Throwable failure)
    {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
