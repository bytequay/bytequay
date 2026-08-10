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

import com.bytequay.app.flow.runtime.FlowRuntime.MutationRejectedException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleClaimException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleWriterFenceException;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFlowRuntime
{
    private static final Instant NOW = Instant.parse("2026-08-10T10:15:30Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    @TempDir
    private Path temporaryDirectory;

    private MutableClock clock;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private FlowRuntime runtime;
    private final Map<String, Path> repositoryRoots = new HashMap<>();

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("runtime.db")
                        + "?foreign_keys=ON");
        FlowRuntimeSchema.install(dataSource);
        clock = new MutableClock(NOW);
        jdbc = new JdbcTemplate(dataSource);
        runtime = new FlowRuntime(dataSource, clock);
    }

    @Test
    void restartKeepsOneTaskPrSessionAndOperationBoundRun()
    {
        Task first = startAndProvision("one");
        Task duplicate = runtime.startTask(
                "request-one", "repo-1", "Implement the task",
                "task/one", first.worktreePath());
        assertThat(duplicate.taskId()).isEqualTo(first.taskId());

        Operation initialTurn = selectFromReconciliation();
        ActiveWriter writer = startWriter(
                OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
        assertThat(writer.run().state()).isEqualTo(RunState.QUEUED);

        runtime = new FlowRuntime(dataSource, clock);
        assertThat(runtime.runForOperation(initialTurn.operationId()))
                .contains(runtime.runForOperation(
                        initialTurn.operationId()).orElseThrow());
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = supervisor.launch(
                writer.run().runId(), writer.claim(), writer.fence(),
                capability -> {
                    commitTaskChange(first, "first task change");
                    runtime.adoptChangeSet(
                            writer.claim(), writer.fence(),
                            repositoryRoots.get(first.taskId()), null);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "ordinary prose; not a protocol",
                            null);
                });
        assertThat(supervisor.launch(
                writer.run().runId(), writer.claim(), writer.fence(),
                capability -> new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.FAILED, null, "must-not-run")))
                .isEqualTo(handle);
        supervisor.awaitAndFinish(handle, TTL);
        assertThat(runtime.runForOperation(initialTurn.operationId())
                .orElseThrow().startedAt()).isEqualTo(NOW);

        Task adoptedTask = runtime.task(first.taskId()).orElseThrow();
        PullRequestSubject local = runtime.materializePullRequest(
                first.taskId(), adoptedTask.currentChangeSetRevisionId(),
                "main", "main", "main");
        PullRequestSubject published = runtime.bindRemoteIdentity(
                local.prId(), adoptedTask.currentHeadSha(), "GITHUB", "repo-external-1", 42,
                "PR_node", "https://example.test/pr/42", "receipt:publish");
        PullRequestSubject redelivery = runtime.bindRemoteIdentity(
                local.prId(), adoptedTask.currentHeadSha(), "GITHUB", "repo-external-1", 42,
                "PR_node", "https://example.test/pr/42", "receipt:publish");

        assertThatThrownBy(() -> runtime.materializePullRequest(
                first.taskId(), adoptedTask.currentChangeSetRevisionId(),
                "main", "main", "other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different PR subject");
        assertThat(published.published()).isTrue();
        assertThat(redelivery).isEqualTo(published);
        assertThat(count("flow_runtime_task")).isEqualTo(1);
        assertThat(count("flow_runtime_pr")).isEqualTo(1);
        assertThat(count("flow_runtime_agent_session")).isEqualTo(1);
        assertThat(count("flow_runtime_agent_run")).isEqualTo(1);
        assertThat(count("flow_runtime_agent_process_attempt")).isEqualTo(1);
    }

    @Test
    void expiredActivatedClaimQuarantinesAfterRestartWithoutMintedProof()
    {
        Task task = startAndProvision("activated-recovery");
        Operation turn = selectFromReconciliation();
        ActiveWriter first = startWriter(
                OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
        AgentProcessAttempt firstProcess = activateForRecovery(first);
        clock.advance(TTL.plusSeconds(1));

        runtime = new FlowRuntime(dataSource, clock);
        assertThat(runtime.claimNext("worker-2", TTL)).isEmpty();
        assertThatThrownBy(() -> runtime.assertWriterFence(
                first.claim(), first.fence()))
                .isInstanceOf(StaleWriterFenceException.class);
        var expired = runtime.expiredClaims();
        assertThat(expired).singleElement().satisfies(claim -> {
            assertThat(claim.operationId()).isEqualTo(turn.operationId());
            assertThat(claim.processAttemptId())
                    .isEqualTo(firstProcess.processAttemptId());
            assertThat(claim.processAttemptState())
                    .isEqualTo(ProcessAttemptState.ACTIVATED);
        });
        assertThat(runtime.recoverExpiredClaim(
                turn.operationId(), first.claim().generation())).isFalse();
        assertThat(runtime.recoverExpiredClaim(
                turn.operationId(), first.claim().generation())).isFalse();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.operation(turn.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.FAILED);
        assertThat(runtime.runForOperation(turn.operationId()).orElseThrow()
                .state()).isEqualTo(RunState.RUNNING);
        assertThat(count("flow_runtime_writer_lease")).isEqualTo(1);
        assertThatThrownBy(() -> runtime.redriveRetryable(turn.operationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-launched retry");
    }

    @Test
    void expiredUnactivatedClaimRecoversAfterRestartFromDurableFacts()
    {
        startAndProvision("not-activated");
        Operation turn = selectFromReconciliation();
        ActiveWriter writer = startWriter(
                OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
        assertThat(writer.run().startedAt()).isNull();
        assertThat(count("flow_runtime_agent_process_attempt")).isZero();
        clock.advance(TTL.plusSeconds(1));

        runtime = new FlowRuntime(dataSource, clock);
        var expired = runtime.expiredClaims().getFirst();
        assertThat(expired.operationId()).isEqualTo(turn.operationId());
        assertThat(expired.processAttemptId()).isNull();
        assertThat(expired.processAttemptState()).isNull();
        assertThat(runtime.recoverExpiredClaim(
                expired.operationId(), expired.generation())).isTrue();
        assertThat(runtime.recoverExpiredClaim(
                expired.operationId(), expired.generation())).isTrue();

        assertThat(runtime.operation(turn.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.RETRYABLE);
        runtime.redriveRetryable(turn.operationId());
        assertThat(claim(OperationKind.RUN_TASK_TURN).generation())
                .isEqualTo(writer.claim().generation() + 1);
    }

    @Test
    void expiredReservedClaimQuarantinesBecauseDormantProcessMayExist()
    {
        Task task = startAndProvision("reserved-recovery");
        Operation turn = selectFromReconciliation();
        ActiveWriter writer = startWriter(
                OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
        AgentProcessAttempt reserved = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        clock.advance(TTL.plusSeconds(1));

        runtime = new FlowRuntime(dataSource, clock);
        assertThat(runtime.expiredClaims()).singleElement()
                .satisfies(expired -> {
                    assertThat(expired.processAttemptId())
                            .isEqualTo(reserved.processAttemptId());
                    assertThat(expired.processAttemptState())
                            .isEqualTo(ProcessAttemptState.RESERVED);
                });
        assertThat(runtime.recoverExpiredClaim(
                turn.operationId(), writer.claim().generation())).isFalse();

        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.operation(turn.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.FAILED);
        assertThat(runtime.resultForRun(writer.run().runId())).isEmpty();
        assertThat(runtime.runForOperation(turn.operationId()).orElseThrow()
                .state()).isEqualTo(RunState.QUEUED);
        assertThat(count("flow_runtime_writer_lease")).isEqualTo(1);
        assertThat(runtime.claimNext("worker-2", TTL)).isEmpty();
        assertThatThrownBy(() -> runtime.redriveRetryable(turn.operationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-launched retry");
        assertThat(runtime.pendingWork(task.taskId()))
                .allSatisfy(work -> assertThat(work.handledByOperationId())
                        .isNull());
    }

    @Test
    void fenceIsClaimBoundCappedAndCannotMutateAfterClaimExpiry()
    {
        Task task = startAndProvision("renew");
        selectFromReconciliation();
        Claim claim = runtime.claimNext(
                "worker-1", Duration.ofMinutes(1)).orElseThrow();
        assertThat(claim.kind()).isEqualTo(OperationKind.RUN_TASK_TURN);
        WriterFence fence = runtime.acquireWriterLease(
                claim,
                AgentRole.TASK_AGENT,
                snapshot(task.currentHeadSha()),
                Duration.ofMinutes(10));
        assertThat(fence.expiresAt()).isEqualTo(claim.expiresAt());
        assertThat(fence.claimGeneration()).isEqualTo(claim.generation());

        clock.advance(Duration.ofSeconds(30));
        Claim renewedClaim = runtime.renewClaim(claim, TTL);
        WriterFence renewedFence = runtime.renewWriterLease(
                renewedClaim, fence, Duration.ofMinutes(10));
        assertThat(renewedClaim.generation())
                .isEqualTo(claim.generation());
        assertThat(renewedClaim.claimToken())
                .isEqualTo(claim.claimToken());
        assertThat(renewedFence.fencingToken())
                .isEqualTo(fence.fencingToken());
        assertThat(renewedFence.expiresAt())
                .isEqualTo(renewedClaim.expiresAt());
        Claim wrongClaim = new Claim(
                renewedClaim.operationId(),
                renewedClaim.taskId(),
                renewedClaim.kind(),
                renewedClaim.generation(),
                "wrong-token",
                renewedClaim.workerId(),
                renewedClaim.expiresAt());
        assertThatThrownBy(() -> runtime.assertWriterFence(
                wrongClaim, renewedFence))
                .isInstanceOf(StaleWriterFenceException.class);
        AgentRun run = runtime.startWriterAgent(
                renewedClaim, renewedFence,
                "prompt:task", "capabilities:task");

        Instant forgedExpiry = renewedClaim.expiresAt().plus(TTL);
        jdbc.update(
                """
                UPDATE flow_runtime_writer_lease SET expires_at = ?
                WHERE task_id = ?
                """,
                forgedExpiry.toEpochMilli(),
                task.taskId());
        WriterFence liveRowAfterClaim = new WriterFence(
                renewedFence.taskId(),
                renewedFence.operationId(),
                renewedFence.taskEpoch(),
                renewedFence.holderKind(),
                renewedFence.fencingToken(),
                renewedFence.claimGeneration(),
                renewedFence.claimTokenDigest(),
                renewedFence.headSha(),
                renewedFence.treeDigest(),
                renewedFence.snapshotEvidenceRef(),
                forgedExpiry);
        clock.advance(TTL.plusSeconds(1));

        assertThatThrownBy(() -> runtime.renewClaim(renewedClaim, TTL))
                .isInstanceOf(StaleClaimException.class);
        assertThatThrownBy(() -> runtime.renewWriterLease(
                renewedClaim, liveRowAfterClaim, TTL))
                .isInstanceOf(StaleClaimException.class);
        assertThatThrownBy(() -> runtime.assertWriterFence(
                renewedClaim, liveRowAfterClaim))
                .isInstanceOf(StaleWriterFenceException.class);
        assertThatThrownBy(() -> runtime.reserveInProcessWriterAttempt(
                run.runId(), renewedClaim, liveRowAfterClaim))
                .isInstanceOf(StaleClaimException.class);
        assertThat(runtime.claimNext("worker-2", TTL)).isEmpty();
    }

    @Test
    void waitingTaskKeepsPendingWorkAndResumesTheSameReconciliation()
    {
        Task task = startAndProvision("wait-resume");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Task current = runtime.task(task.taskId()).orElseThrow();
        runtime.transitionTask(
                task.taskId(), current.currentLifecycleRevisionId(),
                TaskStatus.WAITING_USER, "USER_GATE", "gate:1");

        assertThat(runtime.selectNext(reconciliation)).isEmpty();
        assertThat(runtime.pendingWork(task.taskId()))
                .allSatisfy(work -> {
                    assertThat(work.selectedByOperationId()).isNull();
                    assertThat(work.handledByOperationId()).isNull();
                });
        assertThat(runtime.claimNext("worker-2", TTL)).isEmpty();

        current = runtime.task(task.taskId()).orElseThrow();
        runtime.transitionTask(
                task.taskId(), current.currentLifecycleRevisionId(),
                TaskStatus.ACTIVE, "USER_RESUMED", "gate:1");
        Claim resumed = claim(OperationKind.RECONCILE_TASK);
        assertThat(resumed.operationId()).isEqualTo(reconciliation.operationId());
        assertThat(runtime.selectNext(resumed).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
    }

    @Test
    void terminalTaskTransitionSettlesProvisionAndWaitingReconciliation()
    {
        Task neverProvisioned = runtime.startTask(
                "request-terminal-created",
                "repo-1",
                "Cancel before provisioning",
                "task/terminal-created",
                "/worktrees/terminal-created");
        runtime.transitionTask(
                neverProvisioned.taskId(),
                neverProvisioned.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "USER_CANCELED",
                "user-action:cancel");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.task_id = ? AND o.kind = 'PROVISION_TASK'
                  AND o.state = 'CANCELED' AND d.delivery_state = 'DONE'
                """,
                Integer.class,
                neverProvisioned.taskId())).isEqualTo(1);

        Task waiting = startAndProvision("terminal-waiting");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Task current = runtime.task(waiting.taskId()).orElseThrow();
        runtime.transitionTask(
                waiting.taskId(), current.currentLifecycleRevisionId(),
                TaskStatus.WAITING_USER, "USER_GATE", "gate:terminal");
        assertThat(runtime.selectNext(reconciliation)).isEmpty();
        current = runtime.task(waiting.taskId()).orElseThrow();
        runtime.transitionTask(
                waiting.taskId(), current.currentLifecycleRevisionId(),
                TaskStatus.COMPLETED, "USER_COMPLETED", "user-action:complete");
        assertThat(runtime.operation(reconciliation.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CANCELED);
        assertThat(jdbc.queryForObject(
                """
                SELECT delivery_state FROM flow_runtime_dispatch_ticket
                WHERE operation_id = ?
                """,
                String.class,
                reconciliation.operationId())).isEqualTo("DONE");
        assertThat(runtime.claimNext("worker-2", TTL)).isEmpty();
    }

    @Test
    void postTerminalFinalRedIsAuditOnlyAndNeverCreatesReconciliation()
    {
        for (TaskStatus terminal : List.of(
                TaskStatus.COMPLETED, TaskStatus.CANCELED)) {
            String suffix = "post-terminal-" + terminal.name();
            Task task = publishedTask(suffix, terminal == TaskStatus.COMPLETED
                    ? 51
                    : 52);
            Task current = runtime.task(task.taskId()).orElseThrow();
            runtime.transitionTask(
                    task.taskId(),
                    current.currentLifecycleRevisionId(),
                    terminal,
                    "USER_" + terminal.name(),
                    "user-action:" + terminal.name());

            var first = runtime.registerFinalRed(
                    "round-" + suffix,
                    task.taskId(),
                    task.prId(),
                    task.currentHeadSha(),
                    "ci:" + suffix);
            var redelivery = runtime.registerFinalRed(
                    "round-" + suffix,
                    task.taskId(),
                    task.prId(),
                    task.currentHeadSha(),
                    "ci:" + suffix);

            assertThat(redelivery).isEqualTo(first);
            assertThat(first.reconciliationOperationId()).isNull();
            assertThat(first.terminalReason())
                    .isEqualTo("TASK_" + terminal.name());
            assertThat(runtime.pendingWork(task.taskId()))
                    .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                    .singleElement()
                    .satisfies(work -> {
                        assertThat(work.terminalReason())
                                .isEqualTo("TASK_" + terminal.name());
                        assertThat(work.selectedByOperationId()).isNull();
                        assertThat(work.handledByOperationId()).isNull();
                    });
            assertThat(jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_operation
                    WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                      AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                    """,
                    Integer.class,
                    task.taskId())).isZero();
            assertThat(runtime.claimNext("worker-terminal", TTL)).isEmpty();
        }
    }

    @Test
    void liveWriterBlocksLifecycleChangeAndEveryCiBoundaryChecksExactHead()
    {
        Task admissionTask = publishedTask("ci-admission", 41);
        runtime.registerFinalRed(
                "round-admission", admissionTask.taskId(), admissionTask.prId(),
                admissionTask.currentHeadSha(), "ci:admission");
        selectFromReconciliation();
        Claim admissionClaim = claim(OperationKind.RUN_CI_FIXER);
        runtime.advanceRemoteHead(
                admissionTask.prId(), admissionTask.currentHeadSha(), "H-remote-new");
        assertThatThrownBy(() -> runtime.acquireWriterLease(
                admissionClaim,
                AgentRole.CI_FIXER,
                snapshot(admissionTask.currentHeadSha()),
                TTL))
                .isInstanceOf(MutationRejectedException.class);

        Task mutationTask = publishedTask("ci-mutation", 42);
        runtime.registerFinalRed(
                "round-mutation", mutationTask.taskId(), mutationTask.prId(),
                mutationTask.currentHeadSha(), "ci:mutation");
        selectFromReconciliation();
        ActiveWriter writer = startWriter(
                OperationKind.RUN_CI_FIXER, AgentRole.CI_FIXER);
        WriterFence forgedSnapshot = new WriterFence(
                writer.fence().taskId(),
                writer.fence().operationId(),
                writer.fence().taskEpoch(),
                writer.fence().holderKind(),
                writer.fence().fencingToken(),
                writer.fence().claimGeneration(),
                writer.fence().claimTokenDigest(),
                writer.fence().headSha(),
                "tree:forged",
                writer.fence().snapshotEvidenceRef(),
                writer.fence().expiresAt());
        assertThatThrownBy(() -> runtime.assertWriterFence(
                writer.claim(), forgedSnapshot))
                .isInstanceOf(StaleWriterFenceException.class);
        Task current = runtime.task(mutationTask.taskId()).orElseThrow();
        assertThatThrownBy(() -> runtime.transitionTask(
                current.taskId(), current.currentLifecycleRevisionId(),
                TaskStatus.WAITING_USER, "USER_GATE", "gate:2"))
                .isInstanceOf(MutationRejectedException.class)
                .hasMessageContaining("writer is live");
        runtime.advanceRemoteHead(
                mutationTask.prId(), mutationTask.currentHeadSha(), "H-remote-new");
        assertThatThrownBy(() -> runtime.assertWriterFence(
                writer.claim(), writer.fence()))
                .isInstanceOf(MutationRejectedException.class);

        Task selectionTask = publishedTask("ci-selection", 43);
        runtime.advanceRemoteHead(
                selectionTask.prId(), selectionTask.currentHeadSha(), "H-remote-new");
        runtime.registerFinalRed(
                "round-selection", selectionTask.taskId(), selectionTask.prId(),
                "H-remote-new", "ci:selection");
        assertThat(selectFromReconciliationOrEmpty()).isEmpty();
        assertThat(runtime.task(selectionTask.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
    }

    @Test
    void failedAndCanceledTaskAgentRunsMoveTaskToAttention()
    {
        for (TerminalOutcome outcome : List.of(
                TerminalOutcome.FAILED, TerminalOutcome.CANCELED)) {
            String suffix = outcome == TerminalOutcome.FAILED
                    ? "failed"
                    : "canceled";
            Task task = startAndProvision(suffix);
            selectFromReconciliation();
            ActiveWriter writer = startWriter(
                    OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
            runToCompletion(
                    writer,
                    outcome,
                    "opaque stop",
                    "agent-stop:" + suffix);

            assertThat(runtime.task(task.taskId()).orElseThrow().status())
                    .isEqualTo(TaskStatus.NEEDS_ATTENTION);
            assertThat(jdbc.queryForObject(
                    """
                    SELECT reason_code
                    FROM flow_runtime_task_lifecycle_revision
                    WHERE task_id = ? ORDER BY sequence DESC LIMIT 1
                    """,
                    String.class,
                    task.taskId()))
                    .isEqualTo("TASK_AGENT_" + outcome.name());
        }
    }

    @Test
    void simultaneousCiCausesSelectOneWriterAndResultReadyRearms()
    {
        Task task = publishedTask("simultaneous", 44);
        List<CompletableFuture<Void>> ingest = List.of("round-1", "round-2")
                .stream()
                .map(round -> CompletableFuture.runAsync(() ->
                        runtime.registerFinalRed(
                                round,
                                task.taskId(),
                                task.prId(),
                                task.currentHeadSha(),
                                "ci:" + round)))
                .toList();
        ingest.forEach(CompletableFuture::join);

        Operation selected = selectFromReconciliation();
        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_CI_FIXER);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .filteredOn(work -> work.selectedByOperationId() != null)
                .hasSize(1);

        ActiveWriter writer = startWriter(
                OperationKind.RUN_CI_FIXER, AgentRole.CI_FIXER);
        var result = runToCompletion(
                writer,
                TerminalOutcome.FAILED,
                "not-json and no verdict",
                "process-exit:1");

        assertThat(runtime.resultForRun(writer.run().runId()).orElseThrow()
                .finalContent()).isEqualTo("not-json and no verdict");
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind()
                        == PendingKind.AGENT_RESULT_READY)
                .singleElement()
                .satisfies(ready -> {
                    assertThat(ready.agentResultId()).isEqualTo(result.resultId());
                    assertThat(ready.selectedByOperationId()).isNull();
                });
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_inbox i
                JOIN flow_runtime_agent_result r
                  ON r.result_id = i.agent_result_id
                WHERE i.kind = 'AGENT_RESULT_READY'
                """,
                Integer.class)).isEqualTo(1);

        Operation continuation = selectFromReconciliation();
        assertThat(continuation.kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        assertThat(continuation.ownerKind()).isEqualTo("AGENT_RUN");
        assertThat(count("flow_runtime_writer_lease")).isZero();
    }

    private Task publishedTask(String suffix, long prNumber)
    {
        Task task = startAndProvision(suffix);
        finishInitialTaskTurn();
        task = runtime.task(task.taskId()).orElseThrow();
        PullRequestSubject local = runtime.materializePullRequest(
                task.taskId(), task.currentChangeSetRevisionId(),
                "main", "main", "main");
        PullRequestSubject published = runtime.bindRemoteIdentity(
                local.prId(), task.currentHeadSha(), "GITHUB",
                "repo-external-" + suffix, prNumber, "PR_" + suffix,
                "https://example.test/pr/" + prNumber,
                "receipt:" + suffix);
        return runtime.task(published.taskId()).orElseThrow();
    }

    private Task startAndProvision(String suffix)
    {
        Path repository = temporaryDirectory.resolve("repository-" + suffix);
        Path worktree = temporaryDirectory.resolve("worktree-" + suffix);
        initializeRepository(repository, worktree, "task/" + suffix);
        String base = gitOutput(repository, "rev-parse", "HEAD");
        Task task = runtime.startTask(
                "request-" + suffix,
                "repo-1",
                "Implement the task",
                "task/" + suffix,
                worktree.toString());
        Claim provision = claim(OperationKind.PROVISION_TASK);
        runtime.provisionTask(provision, base, base);
        repositoryRoots.put(task.taskId(), repository);
        return runtime.task(task.taskId()).orElseThrow();
    }

    private void finishInitialTaskTurn()
    {
        selectFromReconciliation();
        ActiveWriter writer = startWriter(
                OperationKind.RUN_TASK_TURN, AgentRole.TASK_AGENT);
        Task task = runtime.task(writer.fence().taskId()).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = supervisor.launch(
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    commitTaskChange(task, "initial task change");
                    runtime.adoptChangeSet(
                            writer.claim(),
                            writer.fence(),
                            repositoryRoots.get(task.taskId()),
                            null);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "task complete", null);
                });
        supervisor.awaitAndFinish(handle, TTL);
    }

    private ActiveWriter startWriter(OperationKind kind, AgentRole role)
    {
        Claim claim = claim(kind);
        Task task = runtime.task(claim.taskId()).orElseThrow();
        WriterFence fence = runtime.acquireWriterLease(
                claim, role, snapshot(task.currentHeadSha()), TTL);
        AgentRun run = runtime.startWriterAgent(
                claim,
                fence,
                role == AgentRole.TASK_AGENT ? "prompt:task" : "prompt:ci",
                role == AgentRole.TASK_AGENT
                        ? "capabilities:task"
                        : "capabilities:ci");
        return new ActiveWriter(claim, fence, run);
    }

    private AgentProcessAttempt activateForRecovery(ActiveWriter writer)
    {
        AgentProcessAttempt process = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                process.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
        return runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
    }

    private FlowRuntimeRecords.AgentResult runToCompletion(
            ActiveWriter writer,
            TerminalOutcome outcome,
            String finalContent,
            String errorRef)
    {
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = supervisor.launch(
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> new InProcessWriterAgentSupervisor.AgentCompletion(
                        outcome, finalContent, errorRef));
        return supervisor.awaitAndFinish(handle, TTL);
    }

    private Operation selectFromReconciliation()
    {
        return selectFromReconciliationOrEmpty().orElseThrow();
    }

    private Optional<Operation> selectFromReconciliationOrEmpty()
    {
        Claim claim = claim(OperationKind.RECONCILE_TASK);
        return runtime.selectNext(claim);
    }

    private Claim claim(OperationKind expectedKind)
    {
        Claim claim = runtime.claimNext("worker-1", TTL).orElseThrow();
        assertThat(claim.kind()).isEqualTo(expectedKind);
        return claim;
    }

    private int count(String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void initializeRepository(
            Path repository, Path worktree, String branch)
    {
        try {
            Files.createDirectories(repository);
            gitOutput(repository, "init", "-b", "main");
            gitOutput(repository, "config", "user.name", "ByteQuay Test");
            gitOutput(repository, "config", "user.email", "test@bytequay.invalid");
            Files.writeString(
                    repository.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
            gitOutput(repository, "add", "base.txt");
            gitOutput(repository, "commit", "-m", "base");
            gitOutput(
                    repository,
                    "worktree",
                    "add",
                    "-b",
                    branch,
                    worktree.toString());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void commitTaskChange(Task task, String content)
    {
        try {
            Path worktree = Path.of(task.worktreePath());
            Files.writeString(
                    worktree.resolve("task-change.txt"),
                    content + "\n",
                    StandardCharsets.UTF_8);
            gitOutput(worktree, "add", "task-change.txt");
            gitOutput(worktree, "commit", "-m", "task change");
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String gitOutput(Path directory, String... arguments)
    {
        try {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException(output);
            }
            return output.strip();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static WorktreeSnapshot snapshot(String head)
    {
        return new WorktreeSnapshot(head, "tree:" + head, "snapshot:" + head);
    }

    private record ActiveWriter(Claim claim, WriterFence fence, AgentRun run) {}

    private static final class MutableClock
            extends Clock
    {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial)
        {
            instant = new AtomicReference<>(initial);
        }

        private void advance(Duration duration)
        {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return instant.get();
        }
    }
}
