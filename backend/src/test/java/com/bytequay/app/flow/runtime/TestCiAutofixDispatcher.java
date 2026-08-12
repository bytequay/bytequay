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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.ci.CiCleanupCoordinator;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator;
import com.bytequay.app.flow.ci.CiLearningCoordinator;
import com.bytequay.app.flow.ci.CiRepairCoordinator;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCiAutofixDispatcher
{
    @Test
    void repairAvailableStopsOnlyTheLiveLearnerBeforeRepairDispatch()
            throws Exception
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        CiRepairCoordinator repairs = mock(CiRepairCoordinator.class);
        CiAutofixDispatcher dispatcher = new CiAutofixDispatcher(
                runtime, repairs, mock(CiCleanupCoordinator.class),
                mock(CiLearningCoordinator.class),
                mock(CiFixReviewCoordinator.class),
                mock(InProcessWriterAgentSupervisor.class),
                mock(InProcessReviewerAgentSupervisor.class),
                mock(InProcessCiLearningAgentSupervisor.class),
                mock(NewFlowAgentBodies.class),
                new CiAutofixDispatcher.Config(
                        "test-ci", Duration.ofHours(4),
                        Duration.ofSeconds(1), Duration.ofHours(3),
                        Duration.ofSeconds(2), 1));
        @SuppressWarnings("unchecked")
        AtomicReference<Runnable> active =
                (AtomicReference<Runnable>) ReflectionTestUtils.getField(
                        dispatcher, "activeCancellation");
        @SuppressWarnings("unchecked")
        AtomicReference<String> operationRef =
                (AtomicReference<String>) ReflectionTestUtils.getField(
                        dispatcher, "activeOperationId");
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerExited = new CountDownLatch(1);
        Thread providerWait = Thread.ofVirtual().start(() -> {
            providerEntered.countDown();
            try {
                new CountDownLatch(1).await();
            }
            catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
            finally {
                providerExited.countDown();
            }
        });
        assertThat(providerEntered.await(1, TimeUnit.SECONDS)).isTrue();
        AtomicInteger learnerCancellation = new AtomicInteger();
        active.set(() -> {
            learnerCancellation.incrementAndGet();
            providerWait.interrupt();
            try {
                providerWait.join(1_000);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            if (providerWait.isAlive()) {
                throw new IllegalStateException(
                        "provider wait ignored cooperative cancellation");
            }
        });
        operationRef.set("learner");
        when(runtime.operation("learner")).thenReturn(Optional.of(
                operation("learner", "CI_ROUND",
                        OperationKind.RUN_CI_LEARNING)));

        dispatcher.repairAvailable();

        assertThat(providerExited.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(learnerCancellation).hasValue(1);

        AtomicInteger writerCancellation = new AtomicInteger();
        active.set(writerCancellation::incrementAndGet);
        operationRef.set("writer");
        when(runtime.operation("writer")).thenReturn(Optional.of(
                operation("writer", "CI_ROUND",
                        OperationKind.RUN_CI_FIXER)));
        dispatcher.repairAvailable();
        assertThat(writerCancellation).hasValue(0);

        active.set(null);
        operationRef.set(null);
        Claim repair = new Claim(
                "repair", "task-1", OperationKind.RECONCILE_TASK,
                1, "token", "test-ci", Instant.EPOCH.plusSeconds(60));
        when(runtime.expiredClaims()).thenReturn(List.of());
        when(runtime.claimNextCiAutofix(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.of(repair));

        assertThat(dispatcher.dispatchOnce()).isTrue();
        verify(repairs).selectNext(repair);
    }

    @Test
    void retainedTransientFinalizerHandsDurableStoppedClaimToRecovery()
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        CiRepairCoordinator repairs = mock(CiRepairCoordinator.class);
        CiFixReviewCoordinator reviews = mock(CiFixReviewCoordinator.class);
        CiAutofixDispatcher dispatcher = new CiAutofixDispatcher(
                runtime, repairs, mock(CiCleanupCoordinator.class),
                mock(CiLearningCoordinator.class), reviews,
                mock(InProcessWriterAgentSupervisor.class),
                mock(InProcessReviewerAgentSupervisor.class),
                mock(InProcessCiLearningAgentSupervisor.class),
                mock(NewFlowAgentBodies.class),
                new CiAutofixDispatcher.Config(
                        "test-ci", Duration.ofHours(4),
                        Duration.ofSeconds(1), Duration.ofHours(3),
                        Duration.ofMinutes(5), 1));
        AtomicInteger attempts = new AtomicInteger();
        Runnable finalizer = () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("transient database failure");
            }
            IllegalStateException timeout = new IllegalStateException(
                    "body deadline elapsed");
            timeout.addSuppressed(
                    new FlowRuntime.StaleClaimException("claim expired"));
            throw timeout;
        };
        @SuppressWarnings("unchecked")
        AtomicReference<Runnable> active =
                (AtomicReference<Runnable>) ReflectionTestUtils.getField(
                        dispatcher, "activeCancellation");
        @SuppressWarnings("unchecked")
        AtomicReference<String> operationRef =
                (AtomicReference<String>) ReflectionTestUtils.getField(
                        dispatcher, "activeOperationId");
        active.set(finalizer);
        operationRef.set("operation-1");

        ExpiredClaim stopped = new ExpiredClaim(
                "operation-1", "task-1", OperationKind.RUN_CI_FIXER,
                1, Instant.EPOCH, "run-1", "process-1",
                ProcessAttemptState.STOPPED);
        when(runtime.expiredClaims()).thenReturn(List.of(stopped));
        when(runtime.operation("operation-1")).thenReturn(Optional.of(
                operation("operation-1", "CI_ROUND",
                        OperationKind.RUN_CI_FIXER)));
        when(runtime.claimNextCiAutofix(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.empty());
        when(runtime.claimNextCiLearning(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.empty());

        assertThatThrownBy(dispatcher::dispatchOnce)
                .hasMessage("transient database failure");
        assertThat(active.get()).isSameAs(finalizer);

        assertThat(dispatcher.dispatchOnce()).isTrue();
        assertThat(active.get()).isNull();
        assertThat(operationRef.get()).isNull();

        assertThat(dispatcher.dispatchOnce()).isTrue();
        verify(repairs).recoverExpiredStoppedRepair(
                "operation-1", 1, Duration.ofHours(4));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void routesEveryStoppedCiOwnerWithoutRelaunchingItsBody()
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        CiRepairCoordinator repairs = mock(CiRepairCoordinator.class);
        CiCleanupCoordinator cleanups = mock(CiCleanupCoordinator.class);
        CiLearningCoordinator learning = mock(CiLearningCoordinator.class);
        CiFixReviewCoordinator reviews = mock(CiFixReviewCoordinator.class);
        CiAutofixDispatcher dispatcher = new CiAutofixDispatcher(
                runtime, repairs, cleanups, learning, reviews,
                mock(InProcessWriterAgentSupervisor.class),
                mock(InProcessReviewerAgentSupervisor.class),
                mock(InProcessCiLearningAgentSupervisor.class),
                mock(NewFlowAgentBodies.class),
                new CiAutofixDispatcher.Config(
                        "test-ci", Duration.ofHours(4),
                        Duration.ofSeconds(1), Duration.ofHours(3),
                        Duration.ofMinutes(5), 1));
        List<ExpiredClaim> stopped = List.of(
                stopped("repair", OperationKind.RUN_CI_FIXER),
                stopped("cleanup", OperationKind.RUN_CI_FIXER),
                stopped("task-fix", OperationKind.RUN_TASK_TURN),
                stopped("task-result", OperationKind.RUN_TASK_TURN),
                stopped("reviewer", OperationKind.RUN_REVIEWER),
                stopped("learner", OperationKind.RUN_CI_LEARNING));
        when(runtime.expiredClaims()).thenReturn(stopped);
        when(runtime.operation("repair")).thenReturn(Optional.of(
                operation("repair", "CI_ROUND",
                        OperationKind.RUN_CI_FIXER)));
        when(runtime.operation("cleanup")).thenReturn(Optional.of(
                operation("cleanup", "CI_CLEANUP",
                        OperationKind.RUN_CI_FIXER)));
        when(runtime.operation("task-fix")).thenReturn(Optional.of(
                operation("task-fix", "CI_ATTEMPT",
                        OperationKind.RUN_TASK_TURN)));
        when(runtime.operation("task-result")).thenReturn(Optional.of(
                operation("task-result", "AGENT_RUN",
                        OperationKind.RUN_TASK_TURN)));
        when(runtime.operation("reviewer")).thenReturn(Optional.of(
                operation("reviewer", "REVIEW_REQUEST",
                        OperationKind.RUN_REVIEWER)));
        ReviewerRequest request = mock(ReviewerRequest.class);
        when(request.intendedGateKind()).thenReturn(GateIntent.CI_UPDATE);
        when(runtime.reviewerRequestForReviewerRun("owner-1"))
                .thenReturn(Optional.of(request));
        when(runtime.reviewerRequest("owner-1"))
                .thenReturn(Optional.of(request));
        when(runtime.claimNextCiAutofix(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.empty());
        when(runtime.claimNextCiLearning(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.empty());

        assertThat(dispatcher.dispatchOnce()).isTrue();

        verify(repairs).recoverExpiredStoppedRepair(
                "repair", 1, Duration.ofHours(4));
        verify(cleanups).recoverExpiredStoppedCleanup(
                "cleanup", 1, Duration.ofHours(4));
        verify(reviews).recoverExpiredStoppedTaskTurn(
                "task-fix", 1, Duration.ofHours(4));
        verify(reviews).recoverExpiredStoppedTaskTurn(
                "task-result", 1, Duration.ofHours(4));
        verify(reviews).recoverExpiredStoppedReviewer(
                "reviewer", 1, Duration.ofHours(4));
        verify(learning).recoverExpiredCiLearning("learner", 1);
    }

    @Test
    void redrivesOnlyRecoveredNeverLaunchedAndReservedOwners()
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        CiAutofixDispatcher dispatcher = new CiAutofixDispatcher(
                runtime, mock(CiRepairCoordinator.class),
                mock(CiCleanupCoordinator.class),
                mock(CiLearningCoordinator.class),
                mock(CiFixReviewCoordinator.class),
                mock(InProcessWriterAgentSupervisor.class),
                mock(InProcessReviewerAgentSupervisor.class),
                mock(InProcessCiLearningAgentSupervisor.class),
                mock(NewFlowAgentBodies.class),
                new CiAutofixDispatcher.Config(
                        "test-ci", Duration.ofHours(4),
                        Duration.ofSeconds(1), Duration.ofHours(3),
                        Duration.ofMinutes(5), 1));
        ExpiredClaim writer = new ExpiredClaim(
                "writer", "task-1", OperationKind.RUN_CI_FIXER,
                1, Instant.EPOCH, null, null, null);
        ExpiredClaim reviewer = new ExpiredClaim(
                "reviewer", "task-1", OperationKind.RUN_REVIEWER,
                1, Instant.EPOCH, "run-reviewer", "process-reviewer",
                ProcessAttemptState.RESERVED);
        Operation claimedWriter = operation(
                "writer", "CI_ROUND", OperationKind.RUN_CI_FIXER);
        Operation retryableWriter = withState(
                claimedWriter, OperationState.RETRYABLE);
        Operation claimedReviewer = operation(
                "reviewer", "REVIEW_REQUEST", OperationKind.RUN_REVIEWER);
        Operation retryableReviewer = withState(
                claimedReviewer, OperationState.RETRYABLE);
        when(runtime.expiredClaims()).thenReturn(List.of(writer, reviewer));
        when(runtime.operation("writer")).thenReturn(
                Optional.of(claimedWriter), Optional.of(retryableWriter));
        when(runtime.operation("reviewer")).thenReturn(
                Optional.of(claimedReviewer), Optional.of(retryableReviewer));
        ReviewerRequest request = mock(ReviewerRequest.class);
        when(request.intendedGateKind()).thenReturn(GateIntent.CI_UPDATE);
        when(runtime.reviewerRequest("owner-1"))
                .thenReturn(Optional.of(request));
        when(runtime.recoverExpiredClaim("writer", 1)).thenReturn(true);
        when(runtime.recoverExpiredClaim("reviewer", 1)).thenReturn(true);
        when(runtime.claimNextCiAutofix(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.empty());
        when(runtime.claimNextCiLearning(
                "test-ci", Duration.ofHours(4), 1))
                .thenReturn(Optional.empty());

        assertThat(dispatcher.dispatchOnce()).isTrue();

        verify(runtime).redriveRetryable("writer");
        verify(runtime).redriveRetryable("reviewer");
        verify(runtime).claimNextCiAutofix(
                "test-ci", Duration.ofHours(4), 1);
    }

    private static Operation operation(
            String id, String ownerKind, OperationKind kind)
    {
        return new Operation(
                id, ownerKind, "owner-1", "task-1", kind,
                "subject", "input", null, OperationState.CLAIMED,
                1, null, Instant.EPOCH);
    }

    private static ExpiredClaim stopped(
            String operationId, OperationKind kind)
    {
        return new ExpiredClaim(
                operationId, "task-1", kind, 1, Instant.EPOCH,
                "run-" + operationId, "process-" + operationId,
                ProcessAttemptState.STOPPED);
    }

    private static Operation withState(
            Operation operation, OperationState state)
    {
        return new Operation(
                operation.operationId(), operation.ownerKind(),
                operation.ownerId(), operation.taskId(), operation.kind(),
                operation.subjectDigest(), operation.inputRef(),
                operation.workWatermark(), state, operation.attempt(),
                operation.resultRef(), operation.createdAt());
    }
}
