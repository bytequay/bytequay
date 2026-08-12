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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofixCoordinator.CleanupBinding;
import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cleanup completion and sealing, queued-evidence selection across task
 * terminal states, and the ready gates a sealed subject opens.
 */
@Execution(ExecutionMode.CONCURRENT)
class TestCiAutofixCoordinatorCleanupAndGates
        extends BaseTestCiAutofixCoordinator
{
    @Test
    void recoveredNeverLaunchedCleanupBlockRetainsCanceledRunAndResult()
            throws IOException
    {
        ReservedCleanup reserved = reserveCleanup("recover-block");
        CleanupBinding first = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET claim_expires_at = ? WHERE operation_id = ?
                """,
                NOW.minusMillis(1).toEpochMilli(),
                reserved.claim().operationId());
        assertThat(runtime.recoverExpiredClaim(
                reserved.claim().operationId(),
                reserved.claim().generation())).isTrue();
        runtime.redriveRetryable(reserved.claim().operationId());
        Claim recovered = claim(OperationKind.RUN_CI_FIXER);
        Files.writeString(
                reserved.dirtyPath(),
                "changed after recovered admission\n",
                StandardCharsets.UTF_8);
        restart();

        assertThat(coordinator.beginCleanup(
                recovered, repositoryRoot, TTL)).isEmpty();

        CiCleanupCompletion blocked = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        AgentResult canceled = runtime.resultForRun(first.run().runId())
                .orElseThrow();
        assertThat(blocked.outcome())
                .isEqualTo(CleanupOutcome.ADMISSION_BLOCKED);
        assertThat(blocked.runId()).isEqualTo(first.run().runId());
        assertThat(blocked.resultRef()).isEqualTo(canceled.resultId());
        assertThat(canceled.terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(canceled.finalContent()).isNull();
        assertThat(canceled.stopProofRef())
                .startsWith("never-launched-stop-");
        assertThat(runtime.runForOperation(recovered.operationId()))
                .hasValueSatisfying(run -> assertThat(run.state())
                        .isEqualTo(RunState.CANCELED));
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.IDLE);
                    assertThat(session.lastRunId()).isEqualTo(first.run().runId());
                });
        assertThat(runtime.operation(recovered.operationId()).orElseThrow()
                .resultRef()).isEqualTo(canceled.resultId());
        assertThat(count(
                "flow_runtime_agent_process_attempt",
                "run_id = '%s'".formatted(first.run().runId())))
                .isZero();

        restart();
        assertThat(coordinator.beginCleanup(
                recovered, repositoryRoot, TTL)).isEmpty();
        assertThat(count("flow_ci_cleanup_completion", "1 = 1")).isOne();
        assertThat(runtime.resultForRun(first.run().runId()))
                .contains(canceled);
    }

    @Test
    void lateCleanupCompletionFailureRollsBackAndRetriesWithoutBody()
    {
        ReservedCleanup reserved = reserveCleanup("late-rollback");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        int changeSetsBefore = count(
                "flow_runtime_change_set_revision", "1 = 1");
        int reconciliationsBefore = count(
                "flow_runtime_operation", "kind = 'RECONCILE_TASK'");
        int inboxBefore = count("flow_runtime_inbox", "1 = 1");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        try {
                            Files.delete(reserved.dirtyPath());
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        commitCiChange(
                                "late-output.txt", "fixed\n", "late cleanup");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        jdbc.execute("""
                CREATE TRIGGER fail_cleanup_completion
                BEFORE INSERT ON flow_ci_cleanup_completion
                BEGIN
                    SELECT RAISE(ABORT, 'forced completion failure');
                END
                """);

        assertThatThrownBy(() -> coordinator.awaitCleanup(
                supervisor, binding, handle, TTL))
                .isInstanceOf(RuntimeException.class);
        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .isEmpty();
        assertThat(runtime.resultForRun(binding.run().runId())).isEmpty();
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .changeSetRevisionId())
                .isEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
        assertThat(runtime.operation(reserved.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.operation(reserved.claim().operationId())
                .orElseThrow().resultRef()).isNull();
        assertThat(runtime.runForOperation(reserved.claim().operationId()))
                .hasValueSatisfying(run -> assertThat(run.state())
                        .isEqualTo(RunState.RUNNING));
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.RUNNING);
                    assertThat(session.lastRunId())
                            .isEqualTo(binding.run().runId());
                });
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                "operation_id = '%s' AND delivery_state = 'CLAIMED'"
                        .formatted(reserved.claim().operationId())))
                .isOne();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(reserved.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();
        assertThat(count("flow_runtime_change_set_revision", "1 = 1"))
                .isEqualTo(changeSetsBefore);
        assertThat(count("flow_runtime_inbox", "1 = 1"))
                .isEqualTo(inboxBefore);
        assertThat(count(
                "flow_runtime_operation", "kind = 'RECONCILE_TASK'"))
                .isEqualTo(reconciliationsBefore);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();

        jdbc.execute("DROP TRIGGER fail_cleanup_completion");
        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .isPresent();
        assertThat(runtime.operation(reserved.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(runtime.runForOperation(reserved.claim().operationId()))
                .hasValueSatisfying(run -> assertThat(run.state())
                        .isEqualTo(RunState.COMPLETED));
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> assertThat(session.state())
                        .isEqualTo(SessionState.IDLE));
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                "operation_id = '%s' AND delivery_state = 'DONE'"
                        .formatted(reserved.claim().operationId())))
                .isOne();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .hasSize(1);
    }

    @Test
    void cleanupFinalStateCannotBePreparedBeforeExactStop()
            throws Exception
    {
        ReservedCleanup reserved = reserveCleanup("live-inspection");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    entered.countDown();
                    try {
                        assertThat(release.await(
                                TTL.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    capability.runTool(() -> {
                        try {
                            Files.delete(reserved.dirtyPath());
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        commitCiChange(
                                "after-stop-proof.txt",
                                "changed\n",
                                "post inspection change");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        assertThat(entered.await(TTL.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        assertThatThrownBy(() -> runtime.prepareChangeSet(
                reserved.claim(),
                binding.fence(),
                repositoryRoot,
                reserved.predecessor().inputChangeSetRevisionId()))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("stopped final-state");
        assertThatThrownBy(() -> runtime.adoptChangeSet(
                reserved.claim(),
                binding.fence(),
                repositoryRoot,
                reserved.predecessor().inputChangeSetRevisionId()))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("stopped final-state");
        assertThatThrownBy(() -> runtime.prepareCiCleanupFinalState(
                reserved.claim(),
                binding.fence(),
                repositoryRoot,
                reserved.predecessor().inputChangeSetRevisionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped");

        release.countDown();
        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .hasValueSatisfying(completion -> assertThat(completion.outcome())
                        .isEqualTo(CleanupOutcome.FIX_PREPARED));
    }

    @Test
    void stableFinalInspectionFailureStoresTypedAttentionOnce()
    {
        ReservedCleanup reserved = reserveCleanup("final-untrusted");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        try {
                            Files.createDirectories(
                                    repositoryRoot.resolve(".git/rr-cache"));
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "pretend clean",
                            null);
                });

        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        CiCleanupCompletion completion = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();

        assertThat(completion.outcome())
                .isEqualTo(CleanupOutcome.NEEDS_ATTENTION);
        assertThat(completion.inspectionFailureCode())
                .isEqualTo(FailureCode.UNTRUSTED_REPOSITORY_STATE);
        assertThat(completion.finalStateDigest()).isNull();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .waitingMutationStateRef()).isNotNull();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
    }

    @Test
    void lateCleanupSealFailureRollsBackRuntimeHandoffAndRetriesExactly()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        Path dirty = Path.of(task.worktreePath()).resolve("rollback-dirty.txt");
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        try {
                            Files.writeString(
                                    dirty,
                                    "dirty\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        jdbc.execute("""
                CREATE TRIGGER fail_cleanup_seal_insert
                BEFORE INSERT ON flow_ci_cleanup_seal
                BEGIN
                    SELECT RAISE(ABORT, 'forced cleanup seal failure');
                END
                """);

        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(RuntimeException.class);

        String oldOperation = started.claim().operationId();
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.ACTIVE);
        assertThat(autofix.cleanupSealForRepair(
                started.binding().attempt().attemptId())).isEmpty();
        assertThat(runtime.operation(oldOperation).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.operation(oldOperation).orElseThrow().resultRef())
                .isNull();
        String oldTicketPredicate = """
                operation_id = '%s' AND delivery_state = 'CLAIMED'
                """.formatted(oldOperation);
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                oldTicketPredicate))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.RUNNING);
                    assertThat(session.lastRunId())
                            .isEqualTo(started.binding().run().runId());
                });
        String oldInboxPredicate = """
                selected_by_operation_id = '%s'
                AND handled_by_operation_id IS NULL
                """.formatted(oldOperation);
        assertThat(count(
                "flow_runtime_inbox",
                oldInboxPredicate))
                .isOne();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isEqualTo(oldOperation);
        String oldLeasePredicate = """
                operation_id = '%s'
                """.formatted(oldOperation);
        assertThat(count(
                "flow_runtime_writer_lease",
                oldLeasePredicate))
                .isOne();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isZero();

        jdbc.execute("DROP TRIGGER fail_cleanup_seal_insert");
        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        var seal = autofix.cleanupSealForRepair(
                started.binding().attempt().attemptId()).orElseThrow();

        assertThat(result.finalContent()).isEqualTo("opaque");
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(seal.successorOperationId());
        assertThat(count("flow_ci_cleanup_seal", "1 = 1")).isOne();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> assertThat(session.state())
                        .isEqualTo(SessionState.IDLE));
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void transientSealFailuresStayRetryableAndStaleAuthorityCannotBlock()
    {
        assertThat(CiAutofixCoordinator.blocksCleanupSeal(
                FailureCode.MOVED_DURING_INSPECTION)).isFalse();
        assertThat(CiAutofixCoordinator.blocksCleanupSeal(
                FailureCode.TIMEOUT)).isFalse();
        assertThat(CiAutofixCoordinator.blocksCleanupSeal(
                FailureCode.INTERRUPTED)).isFalse();
        assertThat(CiAutofixCoordinator.blocksCleanupSeal(
                FailureCode.UNTRUSTED_REPOSITORY_STATE)).isTrue();

        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED, "opaque", null);
        var prepared = new AtomicReference<FlowRuntime.PreparedNonCleanState>();
        var handle = supervisor.launch(
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                "TEST_FAIL_BEFORE_DOMAIN",
                (runId, claim, fence, finished) -> {
                    prepared.set(runtime.prepareNonCleanState(
                            claim,
                            fence,
                            repositoryRoot,
                            started.binding().attempt()
                                    .inputChangeSetRevisionId()));
                    throw new IllegalStateException("hold stopped owner");
                },
                capability -> {
                    capability.runTool(() -> {
                        try {
                            Files.writeString(
                                    Path.of(task.worktreePath()).resolve(
                                            "stale-dirty.txt"),
                                    "dirty\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return completion;
                });
        assertThatThrownBy(() -> supervisor.awaitAndFinalize(
                handle, TTL, "TEST_FAIL_BEFORE_DOMAIN"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hold stopped owner");
        assertThat(prepared.get()).isNotNull();

        Claim staleClaim = new Claim(
                started.claim().operationId(),
                started.claim().taskId(),
                started.claim().kind(),
                started.claim().generation(),
                "forged-token",
                started.claim().workerId(),
                started.claim().expiresAt());
        assertThatThrownBy(() -> runtime.handoffStoppedCiRunToCleanup(
                started.binding().run().runId(),
                staleClaim,
                started.fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef(),
                started.binding().attempt().attemptId(),
                prepared.get()))
                .isInstanceOf(IllegalStateException.class);

        WriterFence staleFence = new WriterFence(
                started.fence().taskId(),
                started.fence().operationId(),
                started.fence().taskEpoch(),
                started.fence().holderKind(),
                started.fence().fencingToken() + 1,
                started.fence().claimGeneration(),
                started.fence().claimTokenDigest(),
                started.fence().headSha(),
                started.fence().treeDigest(),
                started.fence().snapshotEvidenceRef(),
                started.fence().expiresAt());
        assertThatThrownBy(() -> runtime.handoffStoppedCiRunToCleanup(
                started.binding().run().runId(),
                started.claim(),
                staleFence,
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef(),
                started.binding().attempt().attemptId(),
                prepared.get()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.ACTIVE);
        assertThat(autofix.cleanupSealForRepair(
                started.binding().attempt().attemptId())).isEmpty();
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();
    }

    @Test
    void untrustedDirtyStateIsBlockedWithoutManufacturingASeal()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        Path unsafeDirty = Path.of(task.worktreePath()).resolve(
                "unsafe-dirty.txt");
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        try {
                            Files.writeString(
                                    unsafeDirty,
                                    "dirty\n",
                                    StandardCharsets.UTF_8);
                            Files.createDirectories(
                                    repositoryRoot.resolve(".git/rr-cache"));
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });

        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(InspectionFailure.class)
                .satisfies(failure -> assertThat(
                        ((InspectionFailure) failure).code())
                        .isEqualTo(FailureCode.UNTRUSTED_REPOSITORY_STATE));
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();
        assertThat(attempt.state()).isEqualTo(AttemptState.NEEDS_ATTENTION);
        assertThat(attempt.resultRef()).isNull();
        assertThat(autofix.roundById(attempt.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.NEEDS_ATTENTION);
        assertThat(autofix.cleanupSealForRepair(attempt.attemptId())).isEmpty();
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isZero();
    }

    @Test
    void remoteHeadMovementFailsClosedUntilExactSubjectIsRestored()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> commitCiChange(
                            "remote-race.txt", "fixed\n", "fix CI"));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        String moved = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        runtime.advanceRemoteHead(pr.prId(), publishedHead, moved);

        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();

        runtime.advanceRemoteHead(pr.prId(), moved, publishedHead);
        coordinator.awaitRepair(supervisor, started.binding(), handle, TTL);

        assertThat(bodies).hasValue(1);
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
    }

    @Test
    void newerSameHeadRedIsQueuedAndSelectedOnceAfterRestart()
    {
        CiRound old = enqueueFailedRound();
        var newer = autofix.observeCi(pr.prId(), check(
                "new-check", "new-run", "FAILURE", "failure-2",
                NOW.plusSeconds(30)));
        var newerLog = autofix.attachLog(
                newer.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(coordinator.selectNext(reconciliation)).isEmpty();

        CiRound successor = autofix.round(
                pr.prId(), publishedHead, old.policyRevisionId()).orElseThrow();
        assertThat(successor.roundId()).isNotEqualTo(old.roundId());
        assertThat(successor.state()).isEqualTo(RoundState.QUEUED);
        assertThat(successor.failedLogRefs()).containsExactly(newerLog.logRef());
        assertThat(autofix.roundById(old.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);

        restart();
        Claim successorReconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(successorReconciliation)
                .orElseThrow();
        assertThat(selected.ownerId()).isEqualTo(successor.roundId());
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isEqualTo(1);
    }

    @Test
    void newerStillRedPolicyIsQueuedWithoutAnotherProviderDelivery()
    {
        CiRound old = enqueueFailedRound();
        var policy = autofix.recordPolicy(
                "repo-1", "main", "main", "ruleset:2", "digest:2",
                PolicyResolution.RESOLVED, null,
                List.of("build"), List.of("SUCCESS"));

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(coordinator.selectNext(reconciliation)).isEmpty();

        CiRound successor = autofix.round(
                pr.prId(), publishedHead, policy.policyRevisionId()).orElseThrow();
        assertThat(successor.state()).isEqualTo(RoundState.QUEUED);
        assertThat(successor.failedLogRefs()).isEqualTo(old.failedLogRefs());
        assertThat(autofix.roundById(old.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);

        restart();
        Claim successorReconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(successorReconciliation)
                .orElseThrow();
        assertThat(selected.ownerId()).isEqualTo(successor.roundId());
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isEqualTo(1);
    }

    @Test
    void policyAndHeadChangesCannotSelectOldQueuedEvidence()
    {
        CiRound old = enqueueFailedRound();
        autofix.recordPolicy(
                "repo-1", "main", "main", "ruleset:2", "digest:2",
                PolicyResolution.RESOLVED, null,
                List.of("build", "lint"), List.of("SUCCESS"));

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(coordinator.selectNext(reconciliation)).isEmpty();
        assertThat(autofix.roundById(old.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();

        autofix.recordPolicy(
                "repo-1", "main", "main", "ruleset:3", "digest:3",
                PolicyResolution.RESOLVED, null,
                List.of("build"), List.of("SUCCESS"));
        CiRound currentRed = failedRound("failure-3", NOW.plusSeconds(60));
        autofix.attachLog(
                currentRed.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        runtime.advanceRemoteHead(pr.prId(), publishedHead, "H2");
        assertThatThrownBy(() -> coordinator.enqueueRepair(currentRed.roundId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact published Task/PR head");
        assertThat(autofix.roundById(currentRed.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
    }

    @Test
    void parkedTaskKeepsCiFactAndSelectsItOnceAfterResume()
    {
        CiRound red = failedRound("parked-red", NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        transition(TaskStatus.WAITING_USER);

        var registration = coordinator.enqueueRepair(red.roundId());

        assertThat(registration.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(registration.reconciliationOperationId()).isNotNull();
        assertThat(registration.terminalReason()).isNull();
        assertThat(runtime.claimNext("parked-worker", TTL)).isEmpty();

        transition(TaskStatus.ACTIVE);
        Claim claimed = claim(OperationKind.RECONCILE_TASK);
        transition(TaskStatus.WAITING_USER);
        assertThat(coordinator.selectNext(claimed)).isEmpty();
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();

        transition(TaskStatus.ACTIVE);
        Claim resumed = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(resumed).orElseThrow();
        assertThat(selected.ownerId()).isEqualTo(red.roundId());
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isEqualTo(1);
    }

    @Test
    void attentionTaskQueuesCiFactWithoutLaunchingWriter()
    {
        CiRound red = failedRound("attention-red", NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        transition(TaskStatus.NEEDS_ATTENTION);

        var registration = coordinator.enqueueRepair(red.roundId());

        assertThat(registration.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(registration.reconciliationOperationId()).isNotNull();
        assertThat(registration.terminalReason()).isNull();
        assertThat(runtime.claimNext("attention-worker", TTL)).isEmpty();
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();
    }

    @Test
    void completedTaskKeepsTerminalCiAuditFact()
    {
        assertTerminalCiAudit(TaskStatus.COMPLETED);
    }

    @Test
    void canceledTaskKeepsTerminalCiAuditFact()
    {
        assertTerminalCiAudit(TaskStatus.CANCELED);
    }

    @Test
    void queuedRoundRedeliversAfterTaskCompletesAndRuntimeRestarts()
    {
        assertQueuedThenTerminalRedelivery(null, TaskStatus.COMPLETED);
    }

    @Test
    void queuedRoundRedeliversAfterTaskCancelsAndRuntimeRestarts()
    {
        assertQueuedThenTerminalRedelivery(null, TaskStatus.CANCELED);
    }

    @Test
    void parkedQueuedRoundRedeliversAfterTaskTerminates()
    {
        assertQueuedThenTerminalRedelivery(
                TaskStatus.WAITING_USER, TaskStatus.CANCELED);
    }

    @Test
    void ciFixRoundTripsThroughFreshReviewerAndSameTaskSession()
            throws Exception
    {
        ReviewReady ready = prepareCleanReview("review-roundtrip");
        String taskSession = ready.binding().run().sessionId();
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var parentSupervisor = new InProcessWriterAgentSupervisor(runtime);
        var parentHandle = ready.review().launchTaskInspection(
                parentSupervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runChecks();
                    String movedDuringParent = "c".repeat(40);
                    runtime.advanceRemoteHead(
                            pr.prId(), publishedHead, movedDuringParent);
                    request.set(capability.spawnAdversarialReviewer());
                    assertThatThrownBy(
                            capability::spawnAdversarialReviewer)
                            .isInstanceOf(FlowRuntime
                                    .StaleCapabilityException.class);
                    assertThatThrownBy(() -> capability.runTool(() -> {}))
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "{\"verdict\":\"ignore-parent-prose\"}",
                            null);
                });
        AgentResult parentResult = ready.review().awaitTaskInspection(
                parentSupervisor,
                ready.binding(),
                parentHandle,
                TTL);

        ReviewerRequest storedRequest = request.get();
        assertThat(storedRequest).isNotNull();
        assertThat(storedRequest.repositoryRoot())
                .isEqualTo(repositoryRoot.toRealPath().toString());
        assertThat(storedRequest.changeSetRevisionId())
                .isEqualTo(ready.binding().run()
                        .inputChangeSetRevisionId());
        assertThat(storedRequest.remoteHeadSha()).isEqualTo(publishedHead);
        assertThat(storedRequest.checkRunRefs()).hasSize(1);
        assertThat(parentResult.finalContent())
                .isEqualTo("{\"verdict\":\"ignore-parent-prose\"}");
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.PARKED_CHILD);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_writer_lease WHERE task_id = ?",
                Integer.class,
                task.taskId())).isZero();

        Claim reviewerClaim = claim(OperationKind.RUN_REVIEWER);
        var reviewerStart = ready.review().beginReviewer(
                storedRequest.requestId(), reviewerClaim);
        CountDownLatch reviewerStarted = new CountDownLatch(1);
        CountDownLatch releaseReviewer = new CountDownLatch(1);
        var reviewerSupervisor = new InProcessReviewerAgentSupervisor(runtime);
        var reviewerHandle = ready.review().launchReviewer(
                reviewerSupervisor,
                reviewerStart,
                reviewerClaim,
                capability -> {
                    reviewerStarted.countDown();
                    awaitLatch(releaseReviewer);
                    assertThat(capability.listTree()).isNotEmpty();
                    assertThat(capability.readDiff()).isNotEmpty();
                    return new InProcessReviewerAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "{\"approved\":false,\"arbitrary\":true}",
                            null);
                });
        assertThat(reviewerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        String movedRemote = "a".repeat(40);
        runtime.advanceRemoteHead(
                pr.prId(), "c".repeat(40), movedRemote);
        releaseReviewer.countDown();
        AgentResult reviewerResult = ready.review().awaitReviewer(
                reviewerSupervisor, reviewerHandle, TTL);
        assertThat(reviewerResult.finalContent())
                .isEqualTo("{\"approved\":false,\"arbitrary\":true}");

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = runtime.selectNext(reconciliation).orElseThrow();
        assertThat(selected.ownerKind()).isEqualTo("AGENT_RUN");
        Claim resultClaim = claim(OperationKind.RUN_TASK_TURN);
        var resultBinding = ready.review().beginReviewerResultContinuation(
                resultClaim, TTL);
        assertThat(resultBinding.result()).isEqualTo(reviewerResult);
        assertThat(resultBinding.run().sessionId()).isEqualTo(taskSession);
        assertThat(resultBinding.run().inputRemoteHeadSha())
                .isEqualTo(movedRemote);
        AtomicInteger resultBodies = new AtomicInteger();
        var resultHandle = ready.review().launchReviewerResultContinuation(
                parentSupervisor,
                resultBinding,
                resultClaim,
                capability -> {
                    resultBodies.incrementAndGet();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "opaque result consumed; no readiness verdict",
                            null);
                });
        AgentResult consumed = ready.review()
                .awaitReviewerResultContinuation(
                        parentSupervisor,
                        resultBinding,
                        resultHandle,
                        TTL);

        assertThat(resultBodies).hasValue(1);
        assertThat(consumed.finalContent())
                .isEqualTo("opaque result consumed; no readiness verdict");
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.IDLE);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind()
                        == PendingKind.AGENT_RESULT_READY)
                .singleElement()
                .satisfies(work -> assertThat(work.handledByOperationId())
                        .isEqualTo(resultClaim.operationId()));
    }

    @Test
    void acceptedTaskTerminalAndCompletedReviewerWinLateCancellation()
            throws Exception
    {
        ReviewReady ready = prepareCleanReview("late-cancel-terminal");
        CiFixReviewCoordinator.TaskToolContext taskContext =
                ready.review().taskToolContext(ready.binding());
        assertThat(taskContext.readCiFixContext())
                .contains("taskGoal=" + task.goalText());
        jdbc.update(
                "UPDATE flow_runtime_task SET goal_text = ? WHERE task_id = ?",
                "substituted task goal",
                task.taskId());
        assertThatThrownBy(taskContext::readCiFixContext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context changed");
        jdbc.update(
                "UPDATE flow_runtime_task SET goal_text = ? WHERE task_id = ?",
                task.goalText(),
                task.taskId());
        CountDownLatch terminalAccepted = new CountDownLatch(1);
        CountDownLatch releaseTaskBody = new CountDownLatch(1);
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var writerSupervisor = new InProcessWriterAgentSupervisor(runtime);
        var writerHandle = ready.review().launchTaskInspection(
                writerSupervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runChecks();
                    request.set(capability.spawnAdversarialReviewer());
                    terminalAccepted.countDown();
                    awaitLatch(releaseTaskBody);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "accepted terminal result",
                            null);
                });
        awaitLatch(terminalAccepted);
        AtomicReference<InProcessWriterAgentSupervisor.Cancellation>
                cancellation = new AtomicReference<>();
        AtomicReference<Throwable> cancellationFailure =
                new AtomicReference<>();
        CountDownLatch cancelEntered = new CountDownLatch(1);
        Thread cancelThread = Thread.ofVirtual().start(() -> {
            cancelEntered.countDown();
            try {
                cancellation.set(writerSupervisor.cancel(writerHandle, TTL));
            }
            catch (Throwable failure) {
                cancellationFailure.set(failure);
            }
        });
        awaitLatch(cancelEntered);
        awaitThreadBlockedOrEnded(cancelThread);
        releaseTaskBody.countDown();
        joinThread(cancelThread);

        assertThat(cancellationFailure.get()).isNull();
        assertThat(cancellation.get().disposition()).isEqualTo(
                InProcessWriterAgentSupervisor.CancellationDisposition
                        .ALREADY_FINISHED);
        assertThat(cancellation.get().result().terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(request.get()).isNotNull();

        Claim reviewerClaim = claim(OperationKind.RUN_REVIEWER);
        var reviewerStart = ready.review().beginReviewer(
                request.get().requestId(), reviewerClaim);
        CountDownLatch reviewerReturning = new CountDownLatch(1);
        var reviewerSupervisor = new InProcessReviewerAgentSupervisor(runtime);
        var reviewerHandle = ready.review().launchReviewer(
                reviewerSupervisor,
                reviewerStart,
                reviewerClaim,
                capability -> {
                    reviewerReturning.countDown();
                    return new InProcessReviewerAgentSupervisor
                            .AgentCompletion(
                                    TerminalOutcome.COMPLETED,
                                    "completed reviewer result",
                                    null);
                });
        awaitLatch(reviewerReturning);
        awaitNamedThreadTermination(
                "flow-reviewer-agent-" + reviewerHandle.executionId());

        AgentResult reviewerResult = reviewerSupervisor.cancel(
                reviewerHandle, TTL);
        assertThat(reviewerResult.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(reviewerResult.finalContent())
                .isEqualTo("completed reviewer result");
    }

    @Test
    void canceledFailedTerminalEffectRevokesBeforeAnotherToolCanRun()
    {
        ReviewerResultReady ready = prepareReviewerResult(
                "failed-terminal-cancel");
        UserGates blockedGates = mock(UserGates.class);
        CountDownLatch terminalEntered = new CountDownLatch(1);
        CountDownLatch failTerminal = new CountDownLatch(1);
        when(blockedGates.prepareReadyRequest(
                any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    terminalEntered.countDown();
                    awaitLatch(failTerminal);
                    throw new IllegalStateException(
                            "forced terminal transaction rollback");
                });
        var blockedReview = new CiFixReviewCoordinator(
                autofix, runtime, localChecks, blockedGates);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        AtomicInteger laterToolEffects = new AtomicInteger();
        AtomicReference<Throwable> laterToolFailure = new AtomicReference<>();
        var handle = blockedReview.launchReviewerResultContinuation(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    assertThatThrownBy(capability::readyForReview)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("forced terminal");
                    try {
                        capability.runTool(laterToolEffects::incrementAndGet);
                    }
                    catch (Throwable failure) {
                        laterToolFailure.set(failure);
                    }
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "terminal failure was not authority",
                            null);
                });
        awaitLatch(terminalEntered);
        AtomicReference<InProcessWriterAgentSupervisor.Cancellation>
                cancellation = new AtomicReference<>();
        AtomicReference<Throwable> cancellationFailure =
                new AtomicReference<>();
        Thread cancelThread = Thread.ofVirtual().start(() -> {
            try {
                cancellation.set(supervisor.cancel(handle, TTL));
            }
            catch (Throwable failure) {
                cancellationFailure.set(failure);
            }
        });
        awaitThreadBlockedOrEnded(cancelThread);
        failTerminal.countDown();
        joinThread(cancelThread);

        assertThat(cancellationFailure.get()).isNull();
        assertThat(laterToolEffects).hasValue(0);
        assertThat(laterToolFailure.get())
                .isInstanceOf(FlowRuntime.StaleCapabilityException.class);
        assertThat(cancellation.get().result().terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(runtime.readyForReviewRequestForRun(
                ready.binding().run().runId())).isEmpty();
    }

    @Test
    void terminalReadyOpensExactLocalGateAndWinsOverParentFailure()
    {
        ReviewerResultReady ready = prepareReviewerResult("ready-gate");
        int operationCount = count("flow_runtime_operation", "1 = 1");
        int ticketCount = count("flow_runtime_dispatch_ticket", "1 = 1");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        AtomicReference<String> acceptance = new AtomicReference<>();
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            acceptance.set(
                                    capability.readyForReview().status());
                            assertThatThrownBy(capability::readyForReview)
                                    .isInstanceOf(FlowRuntime
                                            .StaleCapabilityException.class);
                            assertThatThrownBy(
                                    capability::spawnAdversarialReviewer)
                                    .isInstanceOf(FlowRuntime
                                            .StaleCapabilityException.class);
                            assertThatThrownBy(capability::runChecks)
                                    .isInstanceOf(FlowRuntime
                                            .StaleCapabilityException.class);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.FAILED,
                                            "opaque failure after seal",
                                            "PARENT_FAILED_AFTER_READY");
                        });

        AgentResult result = ready.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        handle,
                        TTL);

        assertThat(acceptance).hasValue("ACCEPTED_SEALED");
        assertThat(result.terminalOutcome()).isEqualTo(TerminalOutcome.FAILED);
        var gate = userGates.gate(pr.prId()).orElseThrow();
        var revision = userGates.revisionForRun(
                ready.binding().run().runId()).orElseThrow();
        var subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(gate.currentRevision()).isEqualTo(revision.revision());
        assertThat(subject.taskId()).isEqualTo(task.taskId());
        assertThat(subject.prId()).isEqualTo(pr.prId());
        assertThat(subject.proposedHead())
                .isEqualTo(runtime.currentChangeSet(task.taskId())
                        .orElseThrow().headSha());
        assertThat(subject.expectedRemoteHead()).isEqualTo(publishedHead);
        assertThat(subject.localChecks()).hasSize(1);
        assertThat(subject.localReview().ownerPresent()).isTrue();
        assertThat(subject.localReview().bindingId()).isNotBlank();
        assertThat(subject.localReview().batchIds()).isEmpty();
        assertThat(subject.localReview().latestRevisionIds()).isEmpty();
        var localReview = userGates.localReviewBinding(
                subject.localReview().bindingId()).orElseThrow();
        assertThat(localReview.prId()).isEqualTo(pr.prId());
        assertThat(localReview.candidateChangeSetRevisionId())
                .isEqualTo(subject.changeSetRevisionId());
        assertThat(localReview.digest())
                .isEqualTo(subject.localReview().digest());
        assertThat(subject.ciMemoryRefs()).isEmpty();
        assertThat(subject.ciObservationIds()).isNotEmpty();
        assertThat(subject.failedLogRefs()).isNotEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_user_gate_transition "
                        + "WHERE gate_id = ? AND from_state IS NULL "
                        + "AND to_state = 'OPEN'",
                Integer.class,
                gate.gateId())).isEqualTo(1);
        var replay = userGates.prepareFinalization(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence());
        assertThat(userGates.finalizeReady(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                TerminalOutcome.FAILED,
                "opaque failure after seal",
                "PARENT_FAILED_AFTER_READY",
                replay)).isEqualTo(result);
        assertThat(userGates.revisionForRun(
                ready.binding().run().runId())).contains(revision);
        assertThat(count(
                "flow_user_gate_local_review_binding", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_user_gate_subject", "1 = 1"))
                .isEqualTo(1);
        assertThat(count(
                "flow_runtime_ready_for_review_request", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation", "1 = 1"))
                .isEqualTo(operationCount);
        assertThat(count("flow_runtime_dispatch_ticket", "1 = 1"))
                .isEqualTo(ticketCount);
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isZero();
    }

    @Test
    void localReviewBindingInsertRollsBackWithReadySubject()
    {
        ReviewerResultReady ready = prepareReviewerResult(
                "binding-rollback");
        jdbc.execute("""
                CREATE TRIGGER fail_ready_subject
                BEFORE INSERT ON flow_user_gate_subject
                BEGIN
                    SELECT RAISE(ABORT, 'forced ready subject failure');
                END
                """);
        AtomicInteger permittedAfterFailure = new AtomicInteger();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            assertThatThrownBy(capability::readyForReview)
                                    .isInstanceOf(RuntimeException.class)
                                    .hasMessageContaining(
                                            "forced ready subject failure");
                            capability.runTool(
                                    permittedAfterFailure::incrementAndGet);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque ready rollback",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        assertThat(permittedAfterFailure).hasValue(1);
        assertThat(count(
                "flow_user_gate_local_review_binding", "1 = 1"))
                .isZero();
        assertThat(count("flow_user_gate_subject", "1 = 1")).isZero();
        assertThat(runtime.readyForReviewRequestForRun(
                ready.binding().run().runId())).isEmpty();
        assertThat(userGates.gate(pr.prId())).isEmpty();
    }

    @Test
    void localReviewBindingIsUniqueForTheExactPrCandidate()
    {
        CompletedReady ready = openReadyGate("binding-unique");
        var subject = userGates.subject(
                ready.revision().subjectManifestRef()).orElseThrow();

        assertThatThrownBy(() -> jdbc.update(
                """
                INSERT INTO flow_user_gate_local_review_binding (
                    binding_id, pr_id, candidate_change_set_revision_id,
                    binding_digest, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                "conflicting-binding",
                subject.prId(),
                subject.changeSetRevisionId(),
                "conflicting-digest",
                NOW.plusSeconds(1).toEpochMilli()))
                .isInstanceOf(RuntimeException.class);
        assertThat(count(
                "flow_user_gate_local_review_binding", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void subjectSchemaRejectsImpossibleLocalReviewBindings()
    {
        CompletedReady ready = openReadyGate("binding-checks");
        String subjectId = ready.revision().subjectManifestRef();

        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_user_gate_subject
                SET local_review_owner_present = 0
                WHERE subject_id = ?
                """,
                subjectId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_user_gate_subject
                SET local_review_binding_id = NULL
                WHERE subject_id = ?
                """,
                subjectId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_user_gate_subject
                SET local_review_batch_refs_json = '["x"]'
                WHERE subject_id = ?
                """,
                subjectId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_user_gate_subject
                SET local_review_revision_refs_json = '["x"]'
                WHERE subject_id = ?
                """,
                subjectId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_user_gate_subject
                SET local_review_digest = 'wrong-digest'
                WHERE subject_id = ?
                """,
                subjectId)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void subjectReadFailsClosedOnBindingCorruption() throws Exception
    {
        CompletedReady ready = openReadyGate("binding-corruption");
        var subject = userGates.subject(
                ready.revision().subjectManifestRef()).orElseThrow();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate(
                    "UPDATE flow_user_gate_local_review_binding "
                            + "SET binding_digest = 'corrupt' "
                            + "WHERE binding_id = '"
                            + subject.localReview().bindingId() + "'");
        }

        assertThatThrownBy(() -> userGates.subject(subject.subjectId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "local-review binding does not match gate subject");
    }

    @Test
    void historicalAbsentBindingIsReadButNeverSynthesized() throws Exception
    {
        CompletedReady ready = openReadyGate("binding-absent");
        String subjectId = ready.revision().subjectManifestRef();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate(
                    "UPDATE flow_user_gate_subject "
                            + "SET local_review_owner_present = 0, "
                            + "local_review_binding_id = NULL "
                            + "WHERE subject_id = '" + subjectId + "'");
        }

        var historical = userGates.subject(subjectId).orElseThrow();
        assertThat(historical.localReview().ownerPresent()).isFalse();
        assertThat(historical.localReview().bindingId()).isNull();
        int bindingCount = count(
                "flow_user_gate_local_review_binding", "1 = 1");
        userGates.replayReadyRequest(
                ready.ready().binding().run().runId());
        assertThat(userGates.subject(subjectId).orElseThrow()
                .localReview().ownerPresent()).isFalse();
        assertThat(count(
                "flow_user_gate_local_review_binding", "1 = 1"))
                .isEqualTo(bindingCount);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                ready.revision().gateId(),
                ready.revision().revision(),
                ready.revision().subjectDigest(),
                ready.revision().actionDigest(),
                "historical-absent-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("LOCAL_REVIEW_INCOMPLETE");
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
    }

    @Test
    void publicationExecutionAddsOnlyTheOneShotConsentOwner()
    {
        openReadyGate("binding-yagni");

        assertThat(jdbc.queryForList(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND (
                    name LIKE 'flow_user_gate_local_review_thread%'
                    OR name LIKE 'flow_user_gate_local_review_revision%'
                    OR name LIKE 'flow_user_gate_local_review_batch%'
                    OR name LIKE 'flow_user_gate_local_review_current%'
                    OR name LIKE '%provider_call%'
                )
                """,
                String.class)).isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master "
                        + "WHERE type = 'table' AND name LIKE '%consent%' "
                        + "ORDER BY name",
                String.class)).containsExactly(
                        "flow_user_gate_ci_consent_current",
                        "flow_user_gate_ci_consent_revision");
    }

    @Test
    void terminalReadyGateWinsOverParentCancellation()
    {
        ReviewerResultReady ready = prepareReviewerResult("canceled-ready");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.readyForReview();
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.CANCELED,
                                            "opaque canceled after seal",
                                            "PARENT_CANCELED_AFTER_READY");
                        });
        AgentResult result = ready.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        handle,
                        TTL);

        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(userGates.revisionForRun(
                ready.binding().run().runId())).isPresent();
        assertThat(userGates.gate(pr.prId())).isPresent();
    }

    @Test
    void cleanupReadyGateFreezesRepairAndCleanupResults()
    {
        ReviewReady cleanupReview = prepareCleanCleanupReview(
                "cleanup-ready");
        ReviewerResultReady ready = prepareReviewerResult(
                cleanupReview, "cleanup-ready");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.readyForReview();
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque cleanup ready",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        var revision = userGates.revisionForRun(
                ready.binding().run().runId()).orElseThrow();
        var subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.originCiFixSourceKind()).isEqualTo("CLEANUP");
        assertThat(subject.cleanupId())
                .isEqualTo(subject.originCiFixSourceId());
        assertThat(subject.cleanupResultId()).isNotBlank();
        assertThat(subject.repairAttemptId()).isNotBlank();
        assertThat(subject.repairResultId()).isNotBlank();
        assertThat(subject.cleanupResultId())
                .isNotEqualTo(subject.repairResultId());
    }

    @Test
    void initialCiFixTurnCannotSealReadyWithoutACompletedReviewer()
    {
        ReviewReady ready = prepareCleanReview("ready-before-review");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runChecks();
                    assertThatThrownBy(capability::readyForReview)
                            .isInstanceOf(UserGates
                                    .ReadyRejectedException.class)
                            .hasMessageContaining(
                                    "EXACT_HEAD_REVIEW_MISSING");
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "opaque premature ready",
                            null);
                });

        ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        assertThat(runtime.readyForReviewRequestForRun(
                ready.binding().run().runId())).isEmpty();
        assertThat(userGates.gate(pr.prId())).isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
    }

    @Test
    void remoteAdvanceAfterReadySealSettlesTypedAttentionWithoutGate()
    {
        ReviewerResultReady ready = prepareReviewerResult(
                "ready-remote-drift");
        CountDownLatch sealed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            assertThat(capability.readyForReview().status())
                                    .isEqualTo("ACCEPTED_SEALED");
                            sealed.countDown();
                            awaitLatch(release);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque ready",
                                            null);
                        });
        awaitLatch(sealed);
        runtime.advanceRemoteHead(
                pr.prId(), publishedHead, "d".repeat(40));
        release.countDown();

        ready.ready().review().awaitReviewerResultContinuation(
                supervisor,
                ready.binding(),
                handle,
                TTL);

        assertThat(userGates.gate(pr.prId())).isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.readyAttentionReasonForRun(
                ready.binding().run().runId()))
                .contains("REVIEW_READINESS_STALE");
    }

    @Test
    void localCheckPolicyAdvanceAfterReadySealPreventsStaleGate()
    {
        assertPostSealDriftNeedsAttention(
                "local-policy-after-ready",
                () -> publishCheckPolicy(
                        "advanced-after-ready", List.of("/usr/bin/true")));
    }

    @Test
    void requiredCiPolicyAdvanceAfterReadySealPreventsStaleGate()
    {
        assertPostSealDriftNeedsAttention(
                "ci-policy-after-ready",
                () -> autofix.recordPolicy(
                        "repo-1",
                        "main",
                        "main",
                        "ruleset:after-ready",
                        "digest:after-ready",
                        PolicyResolution.RESOLVED,
                        null,
                        List.of("build"),
                        List.of("SUCCESS")));
    }

    @Test
    void targetBaseAdvanceAfterReadySealPreventsStaleGate()
    {
        assertPostSealDriftNeedsAttention(
                "target-base-after-ready",
                () -> jdbc.update(
                        "UPDATE flow_runtime_pr SET target_base_ref = ? "
                                + "WHERE pr_id = ?",
                        "release",
                        pr.prId()));
    }

    @Test
    void overlappingRemoteAdvanceWaitsForGateOwnerLocks()
    {
        ReviewerResultReady ready = prepareReviewerResult(
                "locked-remote-ready");
        CountDownLatch sealed = new CountDownLatch(1);
        CountDownLatch finishBody = new CountDownLatch(1);
        CountDownLatch finalRead = new CountDownLatch(1);
        CountDownLatch releaseFinalRead = new CountDownLatch(1);
        CountDownLatch remoteStarted = new CountDownLatch(1);
        CountDownLatch remoteAdvanced = new CountDownLatch(1);
        AtomicReference<Throwable> finalizerFailure = new AtomicReference<>();
        AtomicReference<Throwable> remoteFailure = new AtomicReference<>();
        AtomicInteger finalReads = new AtomicInteger();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.readyForReview();
                            sealed.countDown();
                            awaitLatch(finishBody);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque locked ready",
                                            null);
                        });
        awaitLatch(sealed);
        publishedSubjectHook.set(() -> {
            if (finalReads.incrementAndGet() == 2) {
                publishedSubjectHook.set(null);
                finalRead.countDown();
                awaitLatch(releaseFinalRead);
            }
        });
        finishBody.countDown();
        Thread finalizer = new Thread(() -> {
            try {
                ready.ready().review().awaitReviewerResultContinuation(
                        supervisor, ready.binding(), handle, TTL);
            }
            catch (Throwable failure) {
                finalizerFailure.set(failure);
            }
        });
        finalizer.start();
        awaitLatch(finalRead);
        String advancedHead = "e".repeat(40);
        Thread remote = new Thread(() -> {
            try {
                remoteStarted.countDown();
                runtime.advanceRemoteHead(
                        pr.prId(), publishedHead, advancedHead);
            }
            catch (Throwable failure) {
                remoteFailure.set(failure);
            }
            finally {
                remoteAdvanced.countDown();
            }
        });
        remote.start();
        awaitLatch(remoteStarted);
        assertThat(awaitLatch(remoteAdvanced, Duration.ofMillis(200)))
                .isFalse();
        releaseFinalRead.countDown();
        joinThread(finalizer);
        awaitLatch(remoteAdvanced);
        joinThread(remote);

        assertThat(finalizerFailure.get()).isNull();
        assertThat(userGates.gate(pr.prId())).isPresent();
        assertThat(userGates.subject(userGates.revisionForRun(
                ready.binding().run().runId()).orElseThrow()
                .subjectManifestRef()).orElseThrow().expectedRemoteHead())
                .isEqualTo(publishedHead);
        if (remoteFailure.get() != null) {
            runtime.advanceRemoteHead(
                    pr.prId(), publishedHead, advancedHead);
        }
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(advancedHead);
    }

    @Test
    void failedRequiredLocalCheckBlocksReadyBeforeTerminalSeal()
    {
        publishCheckPolicy("failed-ready", List.of("/usr/bin/false"));
        ReviewerResultReady ready = prepareReviewerResult(
                "failed-ready");
        AtomicInteger permittedAfterRejection = new AtomicInteger();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            assertThatThrownBy(capability::readyForReview)
                                    .isInstanceOf(UserGates
                                            .ReadyRejectedException.class)
                                    .hasMessageContaining(
                                            "LOCAL_CHECK_FAILED");
                            capability.runTool(
                                    permittedAfterRejection::incrementAndGet);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque blocked ready",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        assertThat(runtime.readyForReviewRequestForRun(
                ready.binding().run().runId())).isEmpty();
        assertThat(userGates.gate(pr.prId())).isEmpty();
        assertThat(permittedAfterRejection).hasValue(1);
    }

    @Test
    void unavailableRequiredLocalCheckOpensManualOnlyGate()
    {
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "unavailable-consent");
        publishCheckPolicy(
                "unavailable-ready",
                List.of("/definitely-missing-bytequay-check"));
        ReviewerResultReady ready = prepareReviewerResult(
                "unavailable-ready");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.readyForReview();
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque manual ready",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        var revision = userGates.revisionForRun(
                ready.binding().run().runId()).orElseThrow();
        var subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.manualOnly()).isTrue();
        var unavailable = subject.localChecks().getFirst();
        assertThat(subject.warningCodes())
                .containsExactly(
                        "LOCAL_CHECK_UNAVAILABLE:" + unavailable.profileId());
        assertThat(subject.localChecks()).singleElement()
                .satisfies(check -> assertThat(check.conclusion())
                        .isEqualTo(LocalCheckConclusion.UNAVAILABLE));
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "manual-unavailable")).isNotNull();
    }

    @Test
    void freshReviewerTerminalSealExcludesReadyInTheSameTaskTurn()
    {
        ReviewerResultReady ready = prepareReviewerResult(
                "reviewer-before-ready");
        AtomicReference<ReviewerRequest> successor = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            successor.set(
                                    capability.spawnAdversarialReviewer());
                            assertThatThrownBy(capability::readyForReview)
                                    .isInstanceOf(FlowRuntime
                                            .StaleCapabilityException.class);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque successor review",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        assertThat(successor.get()).isNotNull();
        assertThat(runtime.taskTerminalRequest(
                ready.binding().run().runId()).orElseThrow().kind().name())
                .isEqualTo("REVIEWER");
        assertThat(runtime.readyForReviewRequestForRun(
                ready.binding().run().runId())).isEmpty();
        assertThat(userGates.gate(pr.prId())).isEmpty();
    }

    @Test
    void laterReadySupersedesOnlyOpenRevisionAndHistoricalReplayIsStable()
    {
        CompletedReady first = openReadyGate("first-ready-revision");
        String firstCandidate = runtime.currentChangeSet(task.taskId())
                .orElseThrow().headSha();
        runtime.advanceRemoteHead(
                pr.prId(), publishedHead, firstCandidate);
        publishedHead = firstCandidate;
        CompletedReady second = openReadyGate(
                "second-ready-revision", "failure-second-ready");

        var gate = userGates.gate(pr.prId()).orElseThrow();
        assertThat(first.revision().gateId()).isEqualTo(gate.gateId());
        assertThat(first.revision().revision()).isEqualTo(1);
        assertThat(second.revision().gateId()).isEqualTo(gate.gateId());
        assertThat(second.revision().revision()).isEqualTo(2);
        assertThat(gate.currentRevision()).isEqualTo(2);
        assertThat(userGates.transitions(gate.gateId()))
                .extracting(transition -> transition.fromState() + "->"
                        + transition.toState() + ":"
                        + transition.reasonCode())
                .containsExactly(
                        "null->OPEN:READY",
                        "OPEN->STALE:SUPERSEDED_BY_READY",
                        "null->OPEN:READY");

        var replay = userGates.prepareFinalization(
                first.ready().binding().run().runId(),
                first.ready().claim(),
                first.ready().binding().fence());
        assertThat(userGates.finalizeReady(
                first.ready().binding().run().runId(),
                first.ready().claim(),
                first.ready().binding().fence(),
                TerminalOutcome.COMPLETED,
                first.finalContent(),
                null,
                replay)).isEqualTo(first.result());
        assertThat(userGates.gate(pr.prId()).orElseThrow().currentRevision())
                .isEqualTo(2);
        assertThat(userGates.revisionForRun(
                first.ready().binding().run().runId()))
                .contains(first.revision());
        var firstSubject = userGates.subject(
                first.revision().subjectManifestRef()).orElseThrow();
        var secondSubject = userGates.subject(
                second.revision().subjectManifestRef()).orElseThrow();
        assertThat(firstSubject.localReview().ownerPresent()).isTrue();
        assertThat(secondSubject.localReview().ownerPresent()).isTrue();
        assertThat(firstSubject.localReview().bindingId())
                .isNotEqualTo(secondSubject.localReview().bindingId());
        assertThat(count(
                "flow_user_gate_local_review_binding", "1 = 1"))
                .isEqualTo(2);
    }
}
