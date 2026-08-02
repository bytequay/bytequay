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

import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.CleanupTarget;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.EffectOutcome;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.FailureKind;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Requirement;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Step;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepKind;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepResult;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepStatus;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLEANUP;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCleanupOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void executesTheFixedElevenStepLedgerInOrder()
            throws Exception
    {
        MutableStore store = new MutableStore(operation(requestedSteps()));
        List<Integer> executed = new ArrayList<>();
        CleanupOperationHandler handler = handler(store, new RecordingEffects(executed));

        DispatchTicket.DispatchResult result = handler.execute(context(store.operation));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(executed).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(store.operation.steps()).allMatch(step -> step.status().isSettled());
        assertThat(store.operation.steps().getLast().latestEvidenceDigest())
                .isEqualTo(result.payloadJson());
    }

    @Test
    void expiredClaimUsesProbeWithoutConsumingTheExecuteBudget()
            throws Exception
    {
        List<Step> steps = requestedSteps();
        steps.set(0, new Step(
                "step-1", "cleanup-row", 1, StepKind.PROVE_NO_NEW_ADMISSIONS,
                Requirement.REQUIRED, StepStatus.CLAIMED, 1, 1, 1,
                ClaimMode.EXECUTE, "dead-worker", NOW.minusSeconds(10),
                NOW.minusSeconds(1), null, null, null, false));
        for (int index = 1; index < 10; index++) {
            Step step = steps.get(index);
            steps.set(index, copy(step, Requirement.NOT_APPLICABLE, step.status(),
                    step.attemptCount(), step.executeAttemptCount(), step.claimMode(),
                    step.claimOwner(), step.claimedAt(), step.leaseUntil(),
                    step.failureKind(), step.latestEvidenceDigest(), false));
        }
        MutableStore store = new MutableStore(operation(steps));
        RecordingEffects effects = new RecordingEffects(new ArrayList<>());

        DispatchTicket.DispatchResult result = handler(store, effects)
                .reconcile(context(store.operation));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(effects.executed).isEmpty();
        assertThat(effects.probed).containsExactly(1);
        assertThat(store.operation.steps().getFirst().attemptCount()).isEqualTo(2);
        assertThat(store.operation.steps().getFirst().executeAttemptCount()).isOne();
    }

    @Test
    void determinateFailureWaitsForExplicitRetryAndExecuteBudget()
            throws Exception
    {
        List<Step> steps = requestedSteps();
        steps.set(0, new Step(
                "step-1", "cleanup-row", 1, StepKind.PROVE_NO_NEW_ADMISSIONS,
                Requirement.REQUIRED, StepStatus.FAILED, 1, 1, 2,
                null, null, null, null, FailureKind.DETERMINATE,
                "failed", "failed-digest", false));
        MutableStore store = new MutableStore(operation(steps));
        RecordingEffects effects = new RecordingEffects(new ArrayList<>());
        CleanupOperationHandler handler = handler(store, effects);

        assertThatThrownBy(() -> handler.reconcile(context(store.operation)))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class)
                .satisfies(failure -> assertThat(
                        ((ExecutionPorts.OperationDeferredException) failure).retryAt()).isNull());
        assertThat(effects.executed).isEmpty();

        Step failed = store.operation.steps().getFirst();
        store.replace(copy(
                failed, failed.requirement(), failed.status(), failed.attemptCount(),
                failed.executeAttemptCount(), failed.claimMode(), failed.claimOwner(),
                failed.claimedAt(), failed.leaseUntil(), failed.failureKind(),
                failed.latestEvidenceDigest(), true));
        assertThat(handler.reconcile(context(store.operation)).outcome())
                .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(effects.executed).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(store.operation.steps().getFirst().executeAttemptCount()).isEqualTo(2);
    }

    private static CleanupOperationHandler handler(
            MutableStore store, RecordingEffects effects)
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        return new CleanupOperationHandler(
                store, effects, ignored -> {},
                new WorktreeWriterLeaseManager(
                        new InMemoryExecutionSupport.WorktreeStore(), clock),
                clock,
                Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    private static ExecutionContext context(Operation operation)
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                operation.taskEpoch(), operation.cleanupStageId(),
                operation.stageGeneration(), operation.operationId(),
                operation.semanticAttempt(), null, null, null);
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                operation.operationId(), V2, Set.of(CLEANUP),
                new CapacityManager.CapacityScope(
                        operation.workspaceId(), operation.trunkId(),
                        operation.taskId(), operation.taskEpoch()),
                false, true, true);
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                CleanupOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.CLEANUP,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        operation.cleanupStageId(),
                        CleanupOperationHandler.CALLBACK_ROUTE),
                fence, request);
        CapacityManager.CapacityLease lease = new CapacityManager.CapacityLease(
                "lease", operation.dispatchTicketId(), operation.operationId(), V2,
                Set.of(CLEANUP), request.scope(), false, true, true,
                "worker", 1L, NOW, NOW, NOW.plusSeconds(60), null, null);
        return new ExecutionContext(
                envelope, lease, new ExecutionContext.Cancellation(),
                new NoOpEvidence(), "execution", new InMemoryExecutionSupport.MutableClock(NOW),
                () -> lease);
    }

    private static Operation operation(List<Step> steps)
    {
        return new Operation(
                "cleanup-row", "cleanup-ticket", "task", "trunk", "workspace",
                1, 2, "cleanup-stage", 1, 1, StageCheckpoint.CLEANING,
                "terminal-acceptance", "cleanup-operation", 1,
                OperationStatus.ACTIVE, "cleanup-barrier",
                new CleanupTarget(
                        "owner/repo", "owner/repo", "/tmp/repo",
                        "/tmp/worktree", "codex/task", "origin"),
                steps);
    }

    private static List<Step> requestedSteps()
    {
        List<Step> steps = new ArrayList<>();
        StepKind[] kinds = StepKind.values();
        for (int index = 0; index < kinds.length; index++) {
            steps.add(new Step(
                    "step-" + (index + 1), "cleanup-row", index + 1, kinds[index],
                    Requirement.REQUIRED, StepStatus.REQUESTED, 0, 0, 2,
                    null, null, null, null, null, null, null, false));
        }
        return steps;
    }

    private static Step copy(
            Step step,
            Requirement requirement,
            StepStatus status,
            int attempts,
            int executeAttempts,
            ClaimMode claimMode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil,
            FailureKind failureKind,
            String digest,
            boolean retryRequested)
    {
        return new Step(
                step.id(), step.cleanupOperationId(), step.ordinal(), step.kind(),
                requirement, status, attempts, executeAttempts, step.attemptLimit(),
                claimMode, claimOwner, claimedAt, leaseUntil, failureKind,
                status == StepStatus.FAILED ? "failed" : null,
                digest, retryRequested);
    }

    private static final class MutableStore
            implements CleanupOperationHandler.OperationStore
    {
        private Operation operation;

        private MutableStore(Operation operation)
        {
            this.operation = operation;
        }

        @Override
        public Operation requireByOperationId(String operationId)
        {
            assertThat(operationId).isEqualTo(operation.operationId());
            return operation;
        }

        @Override
        public void activate(String cleanupOperationId, Instant startedAt)
        {
            throw new AssertionError("test operation is already active");
        }

        @Override
        public Step claim(
                String stepId, ClaimMode mode, String claimOwner,
                Instant claimedAt, Instant leaseUntil)
        {
            Step step = step(stepId);
            Step claimed = copy(
                    step, step.requirement(), StepStatus.CLAIMED,
                    step.attemptCount() + 1,
                    step.executeAttemptCount() + (mode == ClaimMode.EXECUTE ? 1 : 0),
                    mode, claimOwner, claimedAt, leaseUntil, null,
                    step.latestEvidenceDigest(), false);
            replace(claimed);
            return claimed;
        }

        @Override
        public void skip(String stepId, String reason, Instant skippedAt)
        {
            Step step = step(stepId);
            replace(copy(
                    step, step.requirement(), StepStatus.SKIPPED,
                    step.attemptCount(), step.executeAttemptCount(),
                    null, null, null, null, null, "skipped", false));
        }

        @Override
        public void succeed(Step step, StepResult result, Instant completedAt)
        {
            replace(copy(
                    step, step.requirement(), StepStatus.SUCCEEDED,
                    step.attemptCount(), step.executeAttemptCount(),
                    null, null, null, null, null, result.evidenceDigest(), false));
        }

        @Override
        public void fail(Step step, StepResult result, Instant completedAt)
        {
            FailureKind kind = result.outcome() == EffectOutcome.INDETERMINATE
                    ? FailureKind.INDETERMINATE
                    : FailureKind.DETERMINATE;
            replace(copy(
                    step, step.requirement(), StepStatus.FAILED,
                    step.attemptCount(), step.executeAttemptCount(),
                    null, null, null, null, kind, result.evidenceDigest(), false));
        }

        private Step step(String stepId)
        {
            return operation.steps().stream()
                    .filter(step -> step.id().equals(stepId))
                    .findFirst().orElseThrow();
        }

        private void replace(Step replacement)
        {
            List<Step> updated = operation.steps().stream()
                    .map(step -> step.id().equals(replacement.id()) ? replacement : step)
                    .toList();
            operation = new Operation(
                    operation.id(), operation.dispatchTicketId(), operation.taskId(),
                    operation.trunkId(), operation.workspaceId(), operation.taskEpoch(),
                    operation.taskVersion(), operation.cleanupStageId(),
                    operation.stageGeneration(), operation.stageVersion(),
                    operation.stageCheckpoint(), operation.terminalAcceptanceId(),
                    operation.operationId(), operation.semanticAttempt(), operation.status(),
                    operation.quiescenceBarrierId(), operation.target(), updated);
        }
    }

    private static final class RecordingEffects
            implements CleanupOperationHandler.CleanupEffects
    {
        private final List<Integer> executed;
        private final List<Integer> probed = new ArrayList<>();

        private RecordingEffects(List<Integer> executed)
        {
            this.executed = executed;
        }

        @Override
        public StepResult execute(Operation operation, Step step, ExecutionContext context)
        {
            executed.add(step.ordinal());
            return success(step);
        }

        @Override
        public StepResult probe(Operation operation, Step step, ExecutionContext context)
        {
            probed.add(step.ordinal());
            return success(step);
        }

        private static StepResult success(Step step)
        {
            String evidence = "evidence-" + step.ordinal();
            return StepResult.succeeded(
                    step.ordinal() >= 8 ? "effect-" + step.ordinal() : null,
                    evidence, "digest-" + step.ordinal());
        }
    }

    private static final class NoOpEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        private final AtomicInteger ids = new AtomicInteger();

        @Override
        public String start(
                DispatchTicket ticket, CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose, Instant startedAt)
        {
            return "execution-" + ids.incrementAndGet();
        }

        @Override
        public void heartbeat(String executionId, Instant at) {}

        @Override
        public void providerSession(
                String executionId, String provider, String providerSessionId) {}

        @Override
        public void processStarted(String executionId, long processPid, String logReference) {}

        @Override
        public void appendLog(
                String executionId, long sequence, String payloadJson, Instant createdAt) {}

        @Override
        public void recordUsage(
                String executionId, long inputTokens, long outputTokens, long costUsdMilli) {}

        @Override
        public void finish(
                String executionId, DispatchTicket.DispatchResult result,
                String failure, Instant finishedAt) {}
    }
}
