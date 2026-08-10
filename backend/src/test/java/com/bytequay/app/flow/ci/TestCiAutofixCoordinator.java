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

import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowRuntimeSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCiAutofixCoordinator
{
    private static final Instant NOW = Instant.parse("2026-08-10T10:15:30Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private FlowRuntime runtime;
    private CiAutofix autofix;
    private CiAutofixCoordinator coordinator;
    private Task task;
    private PullRequestSubject pr;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("flow.db")
                        + "?foreign_keys=ON");
        FlowRuntimeSchema.install(dataSource);
        CiAutofixSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        runtime = new FlowRuntime(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        task = publishedTask();
        autofix = new CiAutofix(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                this::publishedSubject);
        coordinator = new CiAutofixCoordinator(dataSource, autofix, runtime);
    }

    @Test
    void enqueueIsAtomicLogCompleteAndIdempotent()
    {
        CiRound red = failedRound("failure-1", NOW);

        assertThatThrownBy(() -> coordinator.enqueueRepair(red.roundId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing bounded log");
        assertThat(autofix.roundById(red.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);

        var observation = red.checkObservationIds().stream()
                .findFirst().orElseThrow();
        var log = autofix.attachLog(
                observation,
                "failing output".getBytes(StandardCharsets.UTF_8),
                List.of());
        jdbc.execute("""
                CREATE TRIGGER fail_ci_inbox
                BEFORE INSERT ON flow_runtime_inbox
                WHEN NEW.source = 'CI'
                BEGIN
                    SELECT RAISE(ABORT, 'forced inbox failure');
                END
                """);
        assertThatThrownBy(() -> coordinator.enqueueRepair(red.roundId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(autofix.roundById(red.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .isEmpty();
        jdbc.execute("DROP TRIGGER fail_ci_inbox");

        var first = coordinator.enqueueRepair(red.roundId());
        var duplicate = coordinator.enqueueRepair(red.roundId());

        assertThat(duplicate).isEqualTo(first);
        assertThat(first.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(first.round().failedLogRefs()).containsExactly(log.logRef());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .hasSize(1);
        assertThat(count("flow_runtime_operation", "kind = 'RECONCILE_TASK'"))
                .isEqualTo(2);
    }

    @Test
    void newerSameHeadGreenCancelsQueuedRedBeforeWriterSelection()
    {
        CiRound old = enqueueFailedRound();
        autofix.observeCi(pr.prId(), check(
                "new-check", "new-run", "SUCCESS", "success-2",
                NOW.plusSeconds(30)));

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(coordinator.selectNext(reconciliation)).isEmpty();

        CiRound oldStored = autofix.roundById(old.roundId()).orElseThrow();
        CiRound successor = autofix.round(
                pr.prId(), "H1", old.policyRevisionId()).orElseThrow();
        assertThat(oldStored.state()).isEqualTo(RoundState.SUPERSEDED);
        assertThat(oldStored.checkObservationIds())
                .isEqualTo(old.checkObservationIds());
        assertThat(successor.evidenceRevision()).isEqualTo(1);
        assertThat(successor.state()).isEqualTo(RoundState.GREEN);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.externalKey().equals(old.roundId()))
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.handledByOperationId())
                            .isEqualTo(reconciliation.operationId());
                    assertThat(work.selectedByOperationId()).isNull();
                });
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();
    }

    @Test
    void exactCurrentRedSelectsOneCiWriterOperation()
    {
        CiRound queued = enqueueFailedRound();

        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(reconciliation)
                .orElseThrow();

        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_CI_FIXER);
        assertThat(selected.ownerKind()).isEqualTo("CI_ROUND");
        assertThat(selected.ownerId()).isEqualTo(queued.roundId());
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isEqualTo(selected.operationId());
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isEqualTo(1);
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
                pr.prId(), "H1", old.policyRevisionId()).orElseThrow();
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
                pr.prId(), "H1", policy.policyRevisionId()).orElseThrow();
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
        runtime.advanceRemoteHead(pr.prId(), "H1", "H2");
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

    private CiRound enqueueFailedRound()
    {
        CiRound red = failedRound("failure-1", NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        return coordinator.enqueueRepair(red.roundId()).round();
    }

    private void assertTerminalCiAudit(TaskStatus terminal)
    {
        transition(terminal);
        CiRound red = failedRound("terminal-" + terminal, NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());

        var first = coordinator.enqueueRepair(red.roundId());
        var duplicate = coordinator.enqueueRepair(red.roundId());

        assertThat(duplicate).isEqualTo(first);
        assertThat(first.round().state()).isEqualTo(RoundState.FINAL_RED);
        assertThat(first.reconciliationOperationId()).isNull();
        assertThat(first.terminalReason()).isEqualTo("TASK_" + terminal);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.terminalReason())
                            .isEqualTo("TASK_" + terminal);
                    assertThat(work.selectedByOperationId()).isNull();
                });
        assertThat(count(
                "flow_runtime_operation",
                "kind = 'RECONCILE_TASK' AND state IN "
                        + "('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();
    }

    private void assertQueuedThenTerminalRedelivery(
            TaskStatus parkedStatus, TaskStatus terminal)
    {
        CiRound red = failedRound("queued-terminal-" + terminal, NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        if (parkedStatus != null) {
            transition(parkedStatus);
        }
        var queued = coordinator.enqueueRepair(red.roundId());
        assertThat(queued.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(queued.reconciliationOperationId()).isNotNull();

        transition(terminal);
        restart();
        var terminalRegistration = coordinator.enqueueRepair(red.roundId());
        var duplicate = coordinator.enqueueRepair(red.roundId());

        assertThat(duplicate).isEqualTo(terminalRegistration);
        assertThat(terminalRegistration.round().state())
                .isEqualTo(RoundState.QUEUED);
        assertThat(terminalRegistration.reconciliationOperationId()).isNull();
        assertThat(terminalRegistration.terminalReason())
                .isEqualTo("TASK_" + terminal);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.terminalReason())
                            .isEqualTo("TASK_" + terminal);
                    assertThat(work.selectedByOperationId()).isNull();
                });
        assertThat(count(
                "flow_runtime_operation",
                "kind = 'RECONCILE_TASK' AND state IN "
                        + "('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'RUN_CI_FIXER'"))
                .isZero();
    }

    private CiRound failedRound(String revision, Instant startedAt)
    {
        if (autofix.currentPolicy("repo-1", "main").isEmpty()) {
            autofix.recordPolicy(
                    "repo-1", "main", "main", "ruleset", "digest:1",
                    PolicyResolution.RESOLVED, null,
                    List.of("build"), List.of("SUCCESS"));
        }
        autofix.observeCi(pr.prId(), check(
                "check-" + revision,
                "run-" + revision,
                "FAILURE",
                revision,
                startedAt));
        return ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), "H1")).round();
    }

    private NormalizedCheck check(
            String checkId,
            String runId,
            String conclusion,
            String revision,
            Instant startedAt)
    {
        return new NormalizedCheck(
                "H1", "build", checkId, runId, 1, revision, "Build",
                "COMPLETED", conclusion, startedAt,
                startedAt.plusSeconds(10), startedAt.plusSeconds(10),
                "raw:" + revision);
    }

    private PublishedPrSubject publishedSubject(String prId)
    {
        PullRequestSubject current = runtime.pullRequest(prId).orElseThrow();
        return new PublishedPrSubject(
                current.prId(),
                current.taskId(),
                current.repositoryId(),
                current.scopeKey(),
                current.targetBaseRef(),
                current.currentRemoteHead());
    }

    private Task publishedTask()
    {
        Task started = runtime.startTask(
                "request-1", "repo-1", "Implement", "task/one",
                temporaryDirectory.resolve("worktree").toString());
        Claim provision = claim(OperationKind.PROVISION_TASK);
        runtime.provisionTask(provision, "B0", "H1");
        finishInitialTaskTurn();
        PullRequestSubject local = runtime.materializePullRequest(
                started.taskId(), "H1", "main", "main", "main");
        pr = runtime.bindRemoteIdentity(
                local.prId(), "H1", "GITHUB", "repo-external", 42,
                "PR_node", "https://example.test/pr/42", "receipt:42");
        return runtime.task(started.taskId()).orElseThrow();
    }

    private void restart()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        runtime = new FlowRuntime(dataSource, clock);
        autofix = new CiAutofix(
                dataSource, new ObjectMapper(), clock, this::publishedSubject);
        coordinator = new CiAutofixCoordinator(dataSource, autofix, runtime);
    }

    private void transition(TaskStatus next)
    {
        Task current = runtime.task(task.taskId()).orElseThrow();
        runtime.transitionTask(
                current.taskId(),
                current.currentLifecycleRevisionId(),
                next,
                "TEST_" + next,
                "test:" + next);
    }

    private void finishInitialTaskTurn()
    {
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = runtime.selectNext(reconciliation).orElseThrow();
        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        WriterFence fence = runtime.acquireWriterLease(
                turn,
                AgentRole.TASK_AGENT,
                new WorktreeSnapshot("H1", "tree:H1", "snapshot:H1"),
                TTL);
        AgentRun run = runtime.startWriterAgent(
                turn, fence, "prompt:task", "capabilities:task");
        var process = runtime.reserveProcessAttempt(
                run.runId(), turn, fence);
        runtime.activateProcessAttempt(
                process.processAttemptId(), turn, fence, "pid:task");
        runtime.finishAgentRun(
                run.runId(), turn, fence, TerminalOutcome.COMPLETED,
                "done", null, "process:task");
    }

    private Claim claim(OperationKind expected)
    {
        Claim claim = runtime.claimNext("worker", TTL).orElseThrow();
        assertThat(claim.kind()).isEqualTo(expected);
        return claim;
    }

    private int count(String table, String condition)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition,
                Integer.class);
    }
}
