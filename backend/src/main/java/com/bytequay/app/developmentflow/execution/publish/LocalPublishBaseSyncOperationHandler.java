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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Runs one exact, local-only base-sync effect before Local publish is retried. */
public final class LocalPublishBaseSyncOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String FETCH_COMPARE = "FETCH_LOCAL_PUBLISH_BASE";
    public static final String MECHANICAL_REBASE = "REBASE_LOCAL_PUBLISH_BASE";
    public static final String FETCH_CALLBACK = "LOCAL_PUBLISH_BASE_FETCH_RESULT";
    public static final String REBASE_CALLBACK = "LOCAL_PUBLISH_BASE_REBASE_RESULT";

    private static final int SCHEMA_VERSION = 1;

    private final Store store;
    private final Effects effects;
    private final WorktreeWriterLeaseManager writers;
    private final ObjectMapper json;

    public LocalPublishBaseSyncOperationHandler(
            Store store,
            Effects effects,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json)
    {
        this.store = requireNonNull(store, "store is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext execution)
            throws Exception
    {
        return perform(execution, Mode.EXECUTE);
    }

    /** Reconciles one exact Git operation under the same writer fence. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext execution)
            throws Exception
    {
        return perform(execution, Mode.PROBE);
    }

    private DispatchTicket.DispatchResult perform(
            ExecutionContext execution, Mode mode)
            throws Exception
    {
        requireNonNull(execution, "execution is null");
        DispatchTicket.DispatchEnvelope envelope = execution.envelope();
        OperationContext operation = store.requireByOperationId(
                envelope.fence().operationId());
        LifecycleMode lifecycle = requireExact(envelope, operation, mode);
        if (mode == Mode.EXECUTE) {
            requireActive(execution);
        }
        if (lifecycle == LifecycleMode.CANCELING) {
            return canceled(envelope.fence(),
                    "Task cancellation discarded local publish base-sync work");
        }

        WorktreeWriterLeaseManager.Lease writer = writers.acquire(
                execution, operation.worktreePath());
        Optional<Result> result;
        try {
            result = writers.authorizeMutation(execution, writer).run(fence -> {
                try {
                    if (lifecycle == LifecycleMode.PAUSING) {
                        return effects.settlePause(operation, fence);
                    }
                    if (mode == Mode.PROBE) {
                        return Optional.of(
                                effects.probe(operation, execution, fence));
                    }
                    else {
                        return Optional.of(
                                effects.execute(operation, execution, fence));
                    }
                }
                catch (Exception failure) {
                    throw new EffectFailure(failure);
                }
            });
        }
        catch (EffectFailure failure) {
            throw failure.cause();
        }
        if (result.isEmpty()) {
            return canceled(envelope.fence(),
                    "Pause parked local publish base-sync before its effect completed");
        }
        Result exact = result.orElseThrow();
        requireExactResult(operation, exact);
        String payload = write(exact);
        DispatchTicket.Outcome outcome = exact.disposition() == Disposition.FAILED
                ? FAILED
                : SUCCEEDED;
        return new DispatchTicket.DispatchResult(
                envelope.fence(), outcome, payload, payload, exact.error());
    }

    private static LifecycleMode requireExact(
            DispatchTicket.DispatchEnvelope envelope,
            OperationContext operation,
            Mode mode)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        Shape shape = shape(operation.operationKind());
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        CapacityManager.CapacityScope scope = capacity.scope();
        boolean exact = shape != null
                && operation.operationKind().equals(envelope.operationKind())
                && envelope.family() == DispatchTicket.AsyncFamily.LOCAL_GIT
                && envelope.owner().kind() == STAGE
                && operation.stageId().equals(envelope.owner().id())
                && shape.callbackRoute().equals(
                        envelope.owner().callbackRoute())
                && operation.operationId().equals(fence.operationId())
                && Long.valueOf(operation.taskEpoch()).equals(fence.taskEpoch())
                && operation.stageId().equals(fence.stageId())
                && Long.valueOf(operation.stageGeneration()).equals(
                        fence.stageGeneration())
                && operation.semanticAttempt() == fence.attempt()
                && operation.expectedCodeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                && operation.expectedHeadSha().equals(fence.expectedHeadSha())
                && operation.expectedBaseSha().equals(fence.expectedBaseSha())
                && operation.operationId().equals(capacity.operationId())
                && capacity.source() == V2
                && capacity.lanes().equals(ImmutableSet.of(LOCAL_GIT))
                && !capacity.trunkControl()
                && capacity.exclusiveTask()
                && capacity.writerRequired()
                && operation.workspaceId().equals(scope.workspaceId())
                && operation.trunkId().equals(scope.trunkId())
                && operation.taskId().equals(scope.taskId())
                && Long.valueOf(operation.taskEpoch()).equals(scope.taskEpoch())
                && "V2".equals(operation.workflowVersion())
                && "DISPATCHED".equals(operation.operationStatus());
        if (!exact) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Local publish base-sync envelope or owner fence is stale");
        }
        boolean currentOwner = operation.currentTaskEpoch() == operation.taskEpoch()
                && operation.stageId().equals(operation.currentStageId())
                && operation.currentStageGeneration()
                        == operation.stageGeneration()
                && "LOCAL_REVIEW".equals(operation.stageCheckpoint());
        if ("ACTIVE".equals(operation.taskLifecycle()) && currentOwner) {
            return LifecycleMode.ACTIVE;
        }
        if (mode == Mode.PROBE
                && ("PAUSING".equals(operation.taskLifecycle())
                    || "PAUSED".equals(operation.taskLifecycle())
                    || "RESUMING".equals(operation.taskLifecycle()))
                && currentOwner) {
            return LifecycleMode.PAUSING;
        }
        if (mode == Mode.PROBE
                && ("CANCELING".equals(operation.taskLifecycle())
                    || "CLEANING".equals(operation.taskLifecycle())
                    || "CANCELED".equals(operation.taskLifecycle()))
                && operation.currentTaskEpoch() == operation.taskEpoch() + 1) {
            return LifecycleMode.CANCELING;
        }
        throw new ExecutionPorts.IndeterminateExecutionException(
                "Local publish base-sync Task lifecycle fence is stale");
    }

    private static void requireExactResult(
            OperationContext operation, Result result)
    {
        requireNonNull(result, "base-sync result is null");
        Kind kind = kind(operation.operationKind());
        if (!operation.operationId().equals(result.operationId())
                || result.kind() != kind
                || !operation.targetBaseSha().equals(result.targetBaseSha())) {
            throw new IllegalArgumentException(
                    "Local publish base-sync result has the wrong subject");
        }
        if (result.disposition() == Disposition.FAILED) {
            return;
        }
        boolean sourceResult = result.disposition() == Disposition.FETCHED
                || result.disposition() == Disposition.CONFLICT;
        boolean exactSubject = sourceResult
                ? operation.expectedCodeFingerprint().equals(
                            result.codeFingerprint())
                        && operation.expectedHeadSha().equals(result.headSha())
                        && operation.expectedBaseSha().equals(result.baseSha())
                : result.disposition() == Disposition.REBASED
                        && !operation.expectedHeadSha().equals(result.headSha())
                        && operation.targetBaseSha().equals(result.baseSha());
        Evidence evidence = result.evidence();
        if (!exactSubject || evidence == null
                || evidence.kind() != kind
                || !operation.repositoryId().equals(evidence.repositoryId())
                || !operation.branchName().equals(evidence.branchName())
                || !operation.worktreePath().equals(evidence.worktreePath())
                || !operation.expectedCodeFingerprint().equals(
                        evidence.sourceCodeFingerprint())
                || !operation.expectedHeadSha().equals(evidence.sourceHeadSha())
                || !operation.expectedBaseSha().equals(evidence.sourceBaseSha())
                || !operation.targetBaseSha().equals(evidence.targetBaseSha())
                || !result.codeFingerprint().equals(
                        evidence.resultCodeFingerprint())
                || !result.headSha().equals(evidence.resultHeadSha())
                || !result.baseSha().equals(evidence.resultBaseSha())) {
            throw new IllegalArgumentException(
                    "Local publish base-sync result evidence is not exact");
        }
    }

    private String write(Result result)
    {
        try {
            return json.writeValueAsString(result);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize Local publish base-sync result", e);
        }
    }

    private static DispatchTicket.DispatchResult canceled(
            DispatchTicket.OperationFence fence, String error)
    {
        return new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.CANCELED, null, "{}", error);
    }

    private static Shape shape(String operationKind)
    {
        return switch (operationKind) {
            case FETCH_COMPARE -> new Shape(Kind.FETCH_COMPARE, FETCH_CALLBACK);
            case MECHANICAL_REBASE ->
                    new Shape(Kind.MECHANICAL_REBASE, REBASE_CALLBACK);
            default -> null;
        };
    }

    private static Kind kind(String operationKind)
    {
        Shape shape = shape(operationKind);
        if (shape == null) {
            throw new IllegalArgumentException(
                    "Unsupported Local publish base-sync operation "
                            + operationKind);
        }
        return shape.kind();
    }

    private static void requireActive(ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Local publish base-sync was canceled");
        }
    }

    public interface Store
    {
        OperationContext requireByOperationId(String operationId);
    }

    public interface Effects
    {
        Result execute(
                OperationContext operation,
                ExecutionContext execution,
                WorktreeWriterLeaseManager.MutationFence writerFence)
                throws Exception;

        Result probe(
                OperationContext operation,
                ExecutionContext execution,
                WorktreeWriterLeaseManager.MutationFence writerFence)
                throws Exception;

        /**
         * Proves and parks an interrupted effect without starting new work.
         * Empty means the exact source was restored or the effect never began.
         */
        default Optional<Result> settlePause(
                OperationContext operation,
                WorktreeWriterLeaseManager.MutationFence writerFence)
                throws Exception
        {
            throw new UnsupportedOperationException(
                    "pause settlement is not implemented");
        }
    }

    public enum Kind
    {
        FETCH_COMPARE,
        MECHANICAL_REBASE
    }

    public enum Mode
    {
        EXECUTE,
        PROBE
    }

    private enum LifecycleMode
    {
        ACTIVE,
        PAUSING,
        CANCELING
    }

    public enum Disposition
    {
        FETCHED,
        REBASED,
        CONFLICT,
        FAILED
    }

    public enum Proof
    {
        TARGET_PRESENT,
        CLEAN,
        CONFLICT
    }

    public record OperationContext(
            String operationId,
            String operationKind,
            String operationStatus,
            String workspaceId,
            String trunkId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            String workflowVersion,
            String taskLifecycle,
            long currentTaskEpoch,
            String currentStageId,
            long currentStageGeneration,
            String stageCheckpoint,
            String repositoryId,
            String branchName,
            String worktreePath,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String targetBaseSha)
    {
        public OperationContext
        {
            requireText(operationId, "operationId");
            requireText(operationKind, "operationKind");
            requireText(operationStatus, "operationStatus");
            requireText(workspaceId, "workspaceId");
            requireText(trunkId, "trunkId");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(workflowVersion, "workflowVersion");
            requireText(taskLifecycle, "taskLifecycle");
            requireText(repositoryId, "repositoryId");
            requireText(branchName, "branchName");
            requireText(worktreePath, "worktreePath");
            requireText(expectedCodeFingerprint, "expectedCodeFingerprint");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireText(targetBaseSha, "targetBaseSha");
            if (shape(operationKind) == null) {
                throw new IllegalArgumentException(
                        "unsupported Local publish base-sync operation");
            }
            if (taskEpoch < 1 || stageGeneration < 1 || semanticAttempt < 1
                    || currentTaskEpoch < 1 || currentStageGeneration < 0) {
                throw new IllegalArgumentException(
                        "Local publish base-sync identity is invalid");
            }
            if (expectedBaseSha.equals(targetBaseSha)) {
                throw new IllegalArgumentException(
                        "targetBaseSha must differ from expectedBaseSha");
            }
            Path path = Path.of(worktreePath);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalArgumentException(
                        "worktreePath must be absolute and normalized");
            }
        }

        public Kind kind()
        {
            return LocalPublishBaseSyncOperationHandler.kind(operationKind);
        }
    }

    public record Evidence(
            int version,
            Kind kind,
            Proof proof,
            String repositoryId,
            String remoteName,
            String branchName,
            String worktreePath,
            String sourceCodeFingerprint,
            String sourceHeadSha,
            String sourceBaseSha,
            String targetBaseSha,
            String resultCodeFingerprint,
            String resultHeadSha,
            String resultBaseSha,
            List<String> conflictPaths,
            boolean recovered)
    {
        public Evidence
        {
            if (version != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported Local publish base-sync evidence version");
            }
            requireNonNull(kind, "kind is null");
            requireNonNull(proof, "proof is null");
            requireText(repositoryId, "repositoryId");
            requireText(branchName, "branchName");
            requireText(worktreePath, "worktreePath");
            requireText(sourceCodeFingerprint, "sourceCodeFingerprint");
            requireText(sourceHeadSha, "sourceHeadSha");
            requireText(sourceBaseSha, "sourceBaseSha");
            requireText(targetBaseSha, "targetBaseSha");
            requireText(resultCodeFingerprint, "resultCodeFingerprint");
            requireText(resultHeadSha, "resultHeadSha");
            requireText(resultBaseSha, "resultBaseSha");
            conflictPaths = List.copyOf(requireNonNull(
                    conflictPaths, "conflictPaths is null"));
            if (sourceBaseSha.equals(targetBaseSha)) {
                throw new IllegalArgumentException(
                        "evidence target must differ from its source base");
            }
            boolean sourceResult = sourceCodeFingerprint.equals(
                            resultCodeFingerprint)
                    && sourceHeadSha.equals(resultHeadSha)
                    && sourceBaseSha.equals(resultBaseSha);
            boolean valid = switch (proof) {
                case TARGET_PRESENT -> kind == Kind.FETCH_COMPARE
                        && isText(remoteName) && conflictPaths.isEmpty()
                        && sourceResult;
                case CLEAN -> kind == Kind.MECHANICAL_REBASE
                        && remoteName == null && conflictPaths.isEmpty()
                        && targetBaseSha.equals(resultBaseSha)
                        && !sourceHeadSha.equals(resultHeadSha);
                case CONFLICT -> kind == Kind.MECHANICAL_REBASE
                        && remoteName == null && sourceResult;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "Local publish base-sync evidence shape is invalid");
            }
            Path path = Path.of(worktreePath);
            if (!path.isAbsolute() || !path.normalize().equals(path)) {
                throw new IllegalArgumentException(
                        "evidence worktreePath must be absolute and normalized");
            }
        }
    }

    public record Result(
            int version,
            String operationId,
            Kind kind,
            Disposition disposition,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String targetBaseSha,
            Evidence evidence,
            String error)
    {
        public Result
        {
            if (version != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported Local publish base-sync result version");
            }
            requireText(operationId, "operationId");
            requireNonNull(kind, "kind is null");
            requireNonNull(disposition, "disposition is null");
            requireText(targetBaseSha, "targetBaseSha");
            boolean failed = disposition == Disposition.FAILED;
            if (failed != (error != null)
                    || failed != (evidence == null)
                    || failed != (codeFingerprint == null)
                    || failed != (headSha == null)
                    || failed != (baseSha == null)
                    || failed && !isText(error)) {
                throw new IllegalArgumentException(
                        "Local publish base-sync result shape is invalid");
            }
            if (!failed) {
                requireText(codeFingerprint, "codeFingerprint");
                requireText(headSha, "headSha");
                requireText(baseSha, "baseSha");
                if (error != null || evidence.kind() != kind) {
                    throw new IllegalArgumentException(
                            "Local publish base-sync success evidence is invalid");
                }
            }
            boolean validDisposition = switch (kind) {
                case FETCH_COMPARE -> disposition == Disposition.FETCHED
                        || disposition == Disposition.FAILED;
                case MECHANICAL_REBASE -> disposition == Disposition.REBASED
                        || disposition == Disposition.CONFLICT
                        || disposition == Disposition.FAILED;
            };
            if (!validDisposition) {
                throw new IllegalArgumentException(
                        "Local publish base-sync disposition is invalid");
            }
        }
    }

    private record Shape(Kind kind, String callbackRoute) {}

    private static final class EffectFailure
            extends RuntimeException
    {
        private final Exception cause;

        private EffectFailure(Exception cause)
        {
            super(requireNonNull(cause, "cause is null"));
            this.cause = cause;
        }

        private Exception cause()
        {
            return cause;
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static boolean isText(String value)
    {
        return value != null && !value.isBlank();
    }
}
