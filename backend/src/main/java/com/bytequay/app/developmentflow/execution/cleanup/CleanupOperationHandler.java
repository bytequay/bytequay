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
package com.bytequay.app.developmentflow.execution.cleanup;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.stage.CleanupQuiescenceHandoff;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.task.TaskManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLEANUP;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Executes the fixed, durable eleven-step Cleanup protocol. */
public final class CleanupOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "RUN_CLEANUP_OPERATION";
    public static final String CALLBACK_ROUTE = "CLEANUP_OPERATION_RESULT";
    public static final int STEP_COUNT = 11;

    private final OperationStore operations;
    private final CleanupEffects effects;
    private final CleanupStartPort cleanupStart;
    private final WorktreeWriterLeaseManager writers;
    private final Clock clock;
    private final Duration stepLease;
    private final Duration probeDelay;

    public CleanupOperationHandler(
            OperationStore operations,
            CleanupEffects effects,
            CleanupQuiescenceHandoff quiescence,
            WorktreeWriterLeaseManager writers,
            Clock clock,
            Duration stepLease,
            Duration probeDelay)
    {
        this(
                operations,
                effects,
                operation -> quiescence.accept(cleanupStartCommand(operation)),
                writers,
                clock,
                stepLease,
                probeDelay);
        requireNonNull(quiescence, "quiescence is null");
    }

    public CleanupOperationHandler(
            OperationStore operations,
            CleanupEffects effects,
            CleanupStartPort cleanupStart,
            WorktreeWriterLeaseManager writers,
            Clock clock,
            Duration stepLease,
            Duration probeDelay)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.cleanupStart = requireNonNull(cleanupStart, "cleanupStart is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.stepLease = requirePositive(stepLease, "stepLease");
        this.probeDelay = requirePositive(probeDelay, "probeDelay");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        return run(context);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        return run(context);
    }

    private DispatchTicket.DispatchResult run(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        Operation operation = operations.requireByOperationId(
                context.envelope().fence().operationId());
        validate(context.envelope(), operation);
        cancelBeforeStep(context);

        if (operation.status() == OperationStatus.REQUESTED) {
            operations.activate(operation.id(), clock.instant());
            operation = operations.requireByOperationId(operation.operationId());
        }
        if (operation.stageCheckpoint() == StageCheckpoint.WAITING_QUIESCENCE) {
            cleanupStart.accept(operation);
            operation = operations.requireByOperationId(operation.operationId());
        }
        if (operation.stageCheckpoint() != StageCheckpoint.CLEANING) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Cleanup Operation does not own a CLEANING Stage");
        }

        while (true) {
            cancelBeforeStep(context);
            operation = operations.requireByOperationId(operation.operationId());
            Optional<Step> next = operation.steps().stream()
                    .filter(step -> !step.status().isSettled())
                    .findFirst();
            if (next.isEmpty()) {
                String digest = operation.steps().getLast().latestEvidenceDigest();
                if (digest == null || digest.isBlank()) {
                    throw new ExecutionPorts.IndeterminateExecutionException(
                            "settled Cleanup ledger lacks final evidence");
                }
                return new DispatchTicket.DispatchResult(
                        context.envelope().fence(), SUCCEEDED, digest,
                        "{\"cleanupSummaryDigest\":\"" + digest + "\"}", null);
            }

            Step step = next.orElseThrow();
            if (step.requirement() == Requirement.NOT_APPLICABLE) {
                operations.skip(step.id(), "policy marks step not applicable", clock.instant());
                continue;
            }

            ClaimMode mode = claimMode(step, clock.instant());
            if (step.status() == StepStatus.CLAIMED
                    && step.leaseUntil().isAfter(clock.instant())) {
                throw new ExecutionPorts.OperationDeferredException(
                        "Cleanup step " + step.ordinal() + " still has a live claim",
                        step.leaseUntil());
            }
            if (mode == null) {
                throw new ExecutionPorts.OperationDeferredException(
                        "Cleanup step " + step.ordinal() + " awaits owner retry or waiver",
                        null);
            }

            Step claimed = operations.claim(
                    step.id(), mode, context.executionId(),
                    clock.instant(), clock.instant().plus(stepLease));
            cancelAfterClaim(context, claimed);
            StepResult result = claimed.kind() == StepKind.RECORD_FINAL_EVIDENCE
                    ? finalEvidence(operation)
                    : claimed.kind() == StepKind.REMOVE_WORKTREE
                            ? disposeWorktree(
                                    operation, claimed, mode, context)
                            : mode == ClaimMode.EXECUTE
                                    ? effects.execute(operation, claimed, context)
                                    : effects.probe(operation, claimed, context);
            requireNonNull(result, "Cleanup effect returned no result");
            switch (result.outcome()) {
                case SUCCEEDED -> operations.succeed(claimed, result, clock.instant());
                case FAILED -> {
                    operations.fail(claimed, result, clock.instant());
                    throw new ExecutionPorts.OperationDeferredException(
                            "Cleanup step " + claimed.ordinal() + " failed: "
                                    + result.error(), null);
                }
                case INDETERMINATE -> {
                    operations.fail(claimed, result, clock.instant());
                    throw new ExecutionPorts.OperationDeferredException(
                            "Cleanup step " + claimed.ordinal()
                                    + " requires an idempotent probe",
                            clock.instant().plus(probeDelay));
                }
            }
        }
    }

    private StepResult disposeWorktree(
            Operation operation,
            Step step,
            ClaimMode mode,
            ExecutionContext context)
            throws Exception
    {
        WorktreeWriterLeaseManager.CleanupDisposal disposal =
                writers.acquireCleanupDisposal(
                        context, operation.target().worktreePath(),
                        operation.id(), step.id());
        StepResult result;
        try {
            result = writers.authorizeCleanupDisposal(context, disposal).run(
                    ignored -> {
                        try {
                            return mode == ClaimMode.EXECUTE
                                    ? effects.execute(operation, step, context)
                                    : effects.probe(operation, step, context);
                        }
                        catch (Exception failure) {
                            throw new CleanupEffectFailure(failure);
                        }
                    });
        }
        catch (CleanupEffectFailure failure) {
            throw failure.cause();
        }
        if (result.outcome() == EffectOutcome.SUCCEEDED) {
            writers.settleCleanupDisposal(
                    context, disposal,
                    "Cleanup proved quarantined worktree absent: "
                            + result.evidence());
        }
        return result;
    }

    private static ClaimMode claimMode(Step step, Instant now)
    {
        return switch (step.status()) {
            case REQUESTED -> ClaimMode.EXECUTE;
            case CLAIMED -> step.leaseUntil().isAfter(now) ? null : ClaimMode.PROBE;
            case FAILED -> step.failureKind() == FailureKind.INDETERMINATE
                    ? ClaimMode.PROBE
                    : step.retryRequested() && step.executeAttemptCount() < step.attemptLimit()
                            ? ClaimMode.EXECUTE
                            : null;
            case SUCCEEDED, SKIPPED, WAIVED -> null;
        };
    }

    private static void validate(
            DispatchTicket.DispatchEnvelope envelope, Operation operation)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != DispatchTicket.AsyncFamily.CLEANUP
                || envelope.owner().kind() != STAGE
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !operation.cleanupStageId().equals(envelope.owner().id())
                || !operation.operationId().equals(fence.operationId())
                || operation.semanticAttempt() != fence.attempt()
                || !Objects.equals(operation.taskEpoch(), fence.taskEpoch())
                || !operation.cleanupStageId().equals(fence.stageId())
                || !Objects.equals(operation.stageGeneration(), fence.stageGeneration())
                || capacity.source() != V2
                || !capacity.lanes().equals(Set.of(CLEANUP))
                || !capacity.exclusiveTask()
                || !capacity.writerRequired()
                || !operation.taskId().equals(capacity.scope().taskId())
                || !Objects.equals(operation.taskEpoch(), capacity.scope().taskEpoch())) {
            throw new IllegalStateException("Cleanup dispatch does not match its exact owner");
        }
    }

    private static void cancelBeforeStep(ExecutionContext context)
            throws ExecutionPorts.OperationCanceledException
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Cleanup was canceled before claiming its next step");
        }
    }

    private static void cancelAfterClaim(ExecutionContext context, Step step)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        if (context.isCancellationRequested()) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Cleanup was canceled after step " + step.ordinal()
                            + " was durably claimed");
        }
    }

    private static StepResult finalEvidence(Operation operation)
    {
        String evidence = operation.steps().stream()
                .filter(step -> step.ordinal() < STEP_COUNT)
                .map(step -> step.ordinal() + ":" + step.kind() + ":"
                        + step.status() + ":" + step.latestEvidenceDigest())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        return StepResult.succeeded(null, evidence, digest(evidence));
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String startCommandId(Operation operation)
    {
        return "START_CLEANUP:" + operation.operationId();
    }

    private static TaskManager.CleanupQuiescenceCommand cleanupStartCommand(
            Operation operation)
    {
        return new TaskManager.CleanupQuiescenceCommand(
                startCommandId(operation),
                "CleanupStageManager",
                operation.taskId(),
                operation.taskEpoch(),
                operation.taskVersion(),
                operation.cleanupStageId(),
                operation.stageGeneration(),
                operation.stageVersion(),
                operation.quiescenceBarrierId());
    }

    private static Duration requirePositive(Duration value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static final class CleanupEffectFailure
            extends RuntimeException
    {
        private final Exception cause;

        private CleanupEffectFailure(Exception cause)
        {
            super(requireNonNull(cause, "cause is null"));
            this.cause = cause;
        }

        private Exception cause()
        {
            return cause;
        }
    }

    public interface OperationStore
    {
        Operation requireByOperationId(String operationId);

        void activate(String cleanupOperationId, Instant startedAt);

        Step claim(
                String stepId,
                ClaimMode mode,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        void skip(String stepId, String reason, Instant skippedAt);

        void succeed(Step step, StepResult result, Instant completedAt);

        void fail(Step step, StepResult result, Instant completedAt);
    }

    public interface CleanupEffects
    {
        StepResult execute(Operation operation, Step step, ExecutionContext context)
                throws Exception;

        StepResult probe(Operation operation, Step step, ExecutionContext context)
                throws Exception;
    }

    @FunctionalInterface
    public interface CleanupStartPort
    {
        void accept(Operation operation);
    }

    public record Operation(
            String id,
            String dispatchTicketId,
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            long taskVersion,
            String cleanupStageId,
            long stageGeneration,
            long stageVersion,
            StageCheckpoint stageCheckpoint,
            String terminalAcceptanceId,
            String operationId,
            int semanticAttempt,
            OperationStatus status,
            String quiescenceBarrierId,
            CleanupTarget target,
            List<Step> steps)
    {
        public Operation
        {
            requireText(id, "id");
            requireText(dispatchTicketId, "dispatchTicketId");
            requireText(taskId, "taskId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(cleanupStageId, "cleanupStageId");
            requireText(terminalAcceptanceId, "terminalAcceptanceId");
            requireText(operationId, "operationId");
            requireText(quiescenceBarrierId, "quiescenceBarrierId");
            requireNonNull(stageCheckpoint, "stageCheckpoint is null");
            requireNonNull(status, "status is null");
            requireNonNull(target, "target is null");
            steps = List.copyOf(requireNonNull(steps, "steps is null"));
            if (taskEpoch < 1 || stageGeneration < 1 || semanticAttempt < 1
                    || taskVersion < 0 || stageVersion < 0
                    || steps.size() != STEP_COUNT) {
                throw new IllegalArgumentException("Cleanup Operation identity is invalid");
            }
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).ordinal() != index + 1) {
                    throw new IllegalArgumentException("Cleanup steps are not ordered");
                }
            }
        }
    }

    public record CleanupTarget(
            String repositoryId,
            String publishRepositoryId,
            String repositoryRoot,
            String worktreePath,
            String branchName,
            String remoteName)
    {
        public CleanupTarget
        {
            requireText(repositoryId, "repositoryId");
            requireText(publishRepositoryId, "publishRepositoryId");
            requireText(repositoryRoot, "repositoryRoot");
            requireText(worktreePath, "worktreePath");
            requireText(branchName, "branchName");
            if (remoteName != null && remoteName.isBlank()) {
                throw new IllegalArgumentException("remoteName is blank");
            }
        }
    }

    public record Step(
            String id,
            String cleanupOperationId,
            int ordinal,
            StepKind kind,
            Requirement requirement,
            StepStatus status,
            int attemptCount,
            int executeAttemptCount,
            int attemptLimit,
            ClaimMode claimMode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil,
            FailureKind failureKind,
            String lastError,
            String latestEvidenceDigest,
            boolean retryRequested)
    {
        public Step
        {
            requireText(id, "id");
            requireText(cleanupOperationId, "cleanupOperationId");
            requireNonNull(kind, "kind is null");
            requireNonNull(requirement, "requirement is null");
            requireNonNull(status, "status is null");
            if (ordinal < 1 || ordinal > STEP_COUNT || attemptCount < 0
                    || executeAttemptCount < 0 || attemptLimit < 1
                    || executeAttemptCount > attemptLimit) {
                throw new IllegalArgumentException("Cleanup Step counters are invalid");
            }
            if ((status == StepStatus.CLAIMED) != (claimMode != null
                    && claimOwner != null && claimedAt != null && leaseUntil != null)) {
                throw new IllegalArgumentException("Cleanup Step claim is incomplete");
            }
            if ((status == StepStatus.FAILED) != (failureKind != null)) {
                throw new IllegalArgumentException("Cleanup Step failure is incomplete");
            }
        }
    }

    public record StepResult(
            EffectOutcome outcome,
            String externalEffectId,
            String evidence,
            String evidenceDigest,
            String error)
    {
        public StepResult
        {
            requireNonNull(outcome, "outcome is null");
            requireText(evidence, "evidence");
            requireText(evidenceDigest, "evidenceDigest");
            if ((outcome == EffectOutcome.SUCCEEDED) == (error != null)) {
                throw new IllegalArgumentException("Cleanup effect error is inconsistent");
            }
        }

        public static StepResult succeeded(
                String externalEffectId, String evidence, String evidenceDigest)
        {
            return new StepResult(
                    EffectOutcome.SUCCEEDED, externalEffectId,
                    evidence, evidenceDigest, null);
        }

        public static StepResult failed(
                String evidence, String evidenceDigest, String error)
        {
            return new StepResult(
                    EffectOutcome.FAILED, null, evidence, evidenceDigest,
                    requireText(error, "error"));
        }

        public static StepResult indeterminate(
                String evidence, String evidenceDigest, String error)
        {
            return new StepResult(
                    EffectOutcome.INDETERMINATE, null, evidence, evidenceDigest,
                    requireText(error, "error"));
        }
    }

    public enum OperationStatus { REQUESTED, ACTIVE, COMPLETED }

    public enum StepStatus
    {
        REQUESTED, CLAIMED, SUCCEEDED, FAILED, SKIPPED, WAIVED;

        public boolean isSettled()
        {
            return this == SUCCEEDED || this == SKIPPED || this == WAIVED;
        }
    }

    public enum ClaimMode { EXECUTE, PROBE }

    public enum Requirement { REQUIRED, OPTIONAL, NOT_APPLICABLE }

    public enum FailureKind { DETERMINATE, INDETERMINATE }

    public enum EffectOutcome { SUCCEEDED, FAILED, INDETERMINATE }

    public enum StepKind
    {
        PROVE_NO_NEW_ADMISSIONS,
        RECONCILE_OPEN_WORK,
        STOP_PROVIDER_SESSIONS,
        RECONCILE_VALIDATION,
        SEAL_REVIEW_STATE,
        DISMISS_TASK_INTERACTIONS,
        RELEASE_RUNTIME_LEASES,
        REMOVE_WORKTREE,
        DELETE_LOCAL_BRANCH,
        DELETE_REMOTE_BRANCH,
        RECORD_FINAL_EVIDENCE
    }

    static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
