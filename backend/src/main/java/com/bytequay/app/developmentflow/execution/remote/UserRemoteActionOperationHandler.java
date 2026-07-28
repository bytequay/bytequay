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
import java.util.List;
import java.util.Objects;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static java.util.Objects.requireNonNull;

/** Executes one exact remote action authorized by a human invocation. */
public final class UserRemoteActionOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "APPLY_V2_USER_REMOTE_ACTION";
    public static final String CALLBACK_ROUTE = "V2_USER_REMOTE_ACTION_RESULT";

    private final OperationStore store;
    private final Gateway gateway;
    private final ObjectMapper json;
    private final Clock clock;

    public UserRemoteActionOperationHandler(
            OperationStore store, Gateway gateway, ObjectMapper json, Clock clock)
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
        Action action = store.require(fence.operationId());
        requireExactFence(context, action);
        if (action.status() == ActionStatus.SUCCEEDED) {
            return succeeded(fence, action.externalEffectId(), action.evidence());
        }
        if (action.status() == ActionStatus.CANCELED) {
            return DispatchTicket.DispatchResult.canceled(fence);
        }
        if (action.status() == ActionStatus.ABANDONED) {
            return failed(fence,
                    "V2 user remote action authorization is stale or exhausted");
        }
        if (mode == ClaimMode.EXECUTE
                && context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "V2 user remote action canceled before claim");
        }

        Action claimed = store.claim(
                action.id(), action.attemptCount(), mode,
                context.executionId(), clock.instant(),
                context.capacityLease().expiresAt());
        if (claimed.status() == ActionStatus.ABANDONED) {
            return failed(fence,
                    "V2 user remote action authorization is stale or exhausted");
        }
        boolean firstBaseline = claimed.recoveryBaseline() == null;
        if (firstBaseline) {
            try {
                List<String> baseline = List.copyOf(requireNonNull(
                        gateway.captureBaseline(claimed, context),
                        "V2 user remote action baseline is null"));
                store.recordRecoveryBaseline(
                        claimed.id(), claimed.attemptCount(), baseline);
                claimed = store.require(claimed.operationId());
                if (claimed.recoveryBaseline() == null) {
                    throw new IllegalStateException(
                            "V2 user remote action baseline was not persisted");
                }
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
                        "V2 user remote action baseline read failed",
                        failure);
            }
        }
        try {
            EffectResult result = mode == ClaimMode.EXECUTE || firstBaseline
                    ? gateway.execute(claimed, context)
                    : gateway.probe(claimed, context);
            if (!result.proven()) {
                throw new ExecutionPorts.IndeterminateExecutionException(
                        "V2 user remote action outcome is not proven: "
                                + result.evidence());
            }
            store.finishSucceeded(
                    claimed.id(), claimed.attemptCount(),
                    result.externalEffectId(), result.evidence(), clock.instant());
            return succeeded(fence, result.externalEffectId(), result.evidence());
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
                    claimed.id(), claimed.attemptCount(), failure.getMessage(),
                    clock.instant());
            if (store.require(claimed.operationId()).status()
                    == ActionStatus.ABANDONED) {
                return failed(fence, message(failure));
            }
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "V2 user remote action failed after claim", failure);
        }
    }

    private static void requireExactFence(ExecutionContext context, Action action)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.owner().kind() != DispatchTicket.OwnerKind.TASK
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !action.taskId().equals(envelope.owner().id())
                || !Objects.equals(action.taskEpoch(), fence.taskEpoch())
                || !action.stageId().equals(fence.stageId())
                || !Objects.equals(action.stageGeneration(), fence.stageGeneration())
                || action.semanticAttempt() != fence.attempt()
                || fence.expectedCodeFingerprint() != null
                || !action.headSha().equals(fence.expectedHeadSha())
                || !action.baseSha().equals(fence.expectedBaseSha())
                || !action.operationId().equals(fence.operationId())
                || !action.payloadDigest().equals(digest(action.payloadJson()))) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "V2 user remote action differs from its dispatch fence");
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
                    "Cannot encode V2 user remote action result", failure);
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
        Action require(String operationId);

        Action claim(
                String actionId,
                int expectedAttemptCount,
                ClaimMode mode,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        void finishSucceeded(
                String actionId,
                int attempt,
                String externalEffectId,
                String evidence,
                Instant completedAt);

        void finishFailed(
                String actionId, int attempt, String error, Instant completedAt);

        void finishIndeterminate(
                String actionId, int attempt, String evidence, Instant completedAt);

        void finishCanceled(
                String actionId, int attempt, String error, Instant completedAt);

        void recordRecoveryBaseline(
                String actionId, int attempt, List<String> remoteEffectIds);
    }

    public interface Gateway
    {
        List<String> captureBaseline(Action action, ExecutionContext context)
                throws Exception;

        EffectResult execute(Action action, ExecutionContext context)
                throws Exception;

        EffectResult probe(Action action, ExecutionContext context)
                throws Exception;
    }

    public enum ActionKind
    {
        DEQUEUE,
        DELETE_REMOTE_BRANCH,
        POST_TOP_LEVEL_COMMENT,
        SUBMIT_REVIEW
    }

    public enum ActionStatus
    {
        REQUESTED,
        CLAIMED,
        SUCCEEDED,
        FAILED,
        INDETERMINATE,
        CANCELED,
        ABANDONED
    }

    public enum ClaimMode { EXECUTE, PROBE }

    public record FrozenDraft(
            String id,
            String scope,
            String filePath,
            Integer lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String body,
            String findingId)
    {
        public FrozenDraft
        {
            requireText(id, "draft id");
            requireText(scope, "draft scope");
            requireText(body, "draft body");
        }
    }

    public record ActionPayload(
            int version,
            String body,
            String reviewAction,
            String branchName,
            List<FrozenDraft> drafts)
    {
        public ActionPayload
        {
            if (version != 1) {
                throw new IllegalArgumentException(
                        "unsupported V2 user remote action payload version");
            }
            drafts = List.copyOf(drafts == null ? List.of() : drafts);
        }
    }

    public record Action(
            String id,
            String operationId,
            ActionKind kind,
            ActionStatus status,
            int semanticAttempt,
            int attemptCount,
            int attemptLimit,
            String taskId,
            String commandId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String remotePrBindingId,
            String prId,
            String remoteRepositoryId,
            String headRepositoryId,
            int pullRequestNumber,
            String branchName,
            String headSha,
            String baseSha,
            String payloadJson,
            String payloadDigest,
            ActionPayload payload,
            String handledAction,
            Instant authorizedAt,
            List<String> recoveryBaseline,
            String externalEffectId,
            String evidence)
    {
        public Action
        {
            requireText(id, "id");
            requireText(operationId, "operationId");
            requireNonNull(kind, "kind is null");
            requireNonNull(status, "status is null");
            requireText(taskId, "taskId");
            requireText(commandId, "commandId");
            requireText(stageId, "stageId");
            requireText(remotePrBindingId, "remotePrBindingId");
            requireText(prId, "prId");
            requireText(remoteRepositoryId, "remoteRepositoryId");
            requireText(headRepositoryId, "headRepositoryId");
            requireText(branchName, "branchName");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            requireText(payloadJson, "payloadJson");
            requireText(payloadDigest, "payloadDigest");
            requireNonNull(payload, "payload is null");
            requireNonNull(authorizedAt, "authorizedAt is null");
            recoveryBaseline = recoveryBaseline == null
                    ? null : List.copyOf(recoveryBaseline);
            if (semanticAttempt != 1 || attemptCount < 0 || attemptLimit < 1
                    || taskEpoch < 1 || stageGeneration < 1
                    || pullRequestNumber < 1) {
                throw new IllegalArgumentException(
                        "V2 user remote action fence is invalid");
            }
        }
    }

    public record EffectResult(
            boolean proven, String externalEffectId, String evidence)
    {
        public EffectResult
        {
            requireText(evidence, "evidence");
            if (proven) {
                requireText(externalEffectId, "externalEffectId");
            }
            else if (externalEffectId != null) {
                throw new IllegalArgumentException(
                        "unproven effect cannot have an external identity");
            }
        }
    }

    public record EffectEvidence(String externalEffectId, String evidence) {}

    public static final class RetryableActionException
            extends Exception
    {
        public RetryableActionException(String message)
        {
            super(message);
        }
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private static String message(Throwable failure)
    {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
