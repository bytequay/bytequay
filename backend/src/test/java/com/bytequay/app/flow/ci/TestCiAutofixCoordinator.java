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

import com.bytequay.app.flow.ci.CiAutofixCoordinator.RepairBinding;
import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowRuntimeSchema;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
    private Path repositoryRoot;
    private String publishedHead;

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
                pr.prId(), publishedHead, old.policyRevisionId()).orElseThrow();
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
    void repairAttemptAllowsMissingOperationAndRunOnlyWhilePending()
    {
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", null,
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), null,
                AttemptState.ACTIVE, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), null,
                AttemptState.PENDING, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                publishedHead, "output-change-set", List.of(), "result",
                AttemptState.FIX_PREPARED, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective head");
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                "cccccccccccccccccccccccccccccccccccccccc",
                "output-change-set", List.of(), "result",
                AttemptState.NO_HEAD_CHANGE, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objective head");
    }

    @Test
    void cleanCiFixStoresOpaqueResultAndOneExactContinuation()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> commitCiChange(
                            "ci-fix.txt", "fixed\n", "fix CI"));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.FAILED,
                            "looks strange; verdict=whatever; {not-json}",
                            "model-ended-after-commit");
                });

        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();
        ChangeSetRevision output = runtime.currentChangeSet(task.taskId())
                .orElseThrow();

        assertThat(result.finalContent())
                .isEqualTo("looks strange; verdict=whatever; {not-json}");
        assertThat(attempt.state()).isEqualTo(AttemptState.FIX_PREPARED);
        assertThat(attempt.outputLocalHead()).isEqualTo(output.headSha());
        assertThat(attempt.outputChangeSetRevisionId())
                .isEqualTo(output.changeSetRevisionId());
        assertThat(attempt.resultRef()).isEqualTo(result.resultId());
        assertThat(output.sourceRunId())
                .isEqualTo(started.binding().run().runId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(ready -> {
                    assertThat(ready.agentResultId()).isEqualTo(result.resultId());
                    assertThat(ready.externalKey()).isEqualTo(attempt.attemptId());
                    assertThat(ready.subjectHead()).isEqualTo(output.headSha());
                    assertThat(ready.payloadRef()).contains("FIX_PREPARED");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();

        AgentResult replay = coordinator.finalizeRepairAttempt(
                attempt.attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.FAILED,
                        "looks strange; verdict=whatever; {not-json}",
                        "model-ended-after-commit"),
                repositoryRoot);
        assertThat(replay).isEqualTo(result);
        assertThatThrownBy(() -> runtime.finishCiAgentRun(
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                TerminalOutcome.FAILED,
                result.finalContent(),
                result.errorRef(),
                "different-attempt",
                CiFixOutcome.FIX_PREPARED,
                output.headSha(),
                output.changeSetRevisionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("continuation identity");
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .hasSize(1);
    }

    @Test
    void cleanNoHeadChangeSettlesWithoutParsingAgentText()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED, "no changes were needed", null));

        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();

        assertThat(attempt.state()).isEqualTo(AttemptState.NO_HEAD_CHANGE);
        assertThat(attempt.outputLocalHead()).isEqualTo(publishedHead);
        assertThat(attempt.resultRef()).isEqualTo(result.resultId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(ready -> {
                    assertThat(ready.subjectHead()).isEqualTo(publishedHead);
                    assertThat(ready.payloadRef()).contains("NO_HEAD_CHANGE");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void launchRedeliveryReusesLiveExecutionAndNeverRerunsBody()
            throws Exception
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bodyStarts = new AtomicInteger();
        var body = (Function<
                InProcessWriterAgentSupervisor.WriterToolCapability,
                InProcessWriterAgentSupervisor.AgentCompletion>) capability -> {
                    bodyStarts.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test body timed out");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                };
        var first = coordinator.launchRepair(
                supervisor, started.binding(), started.claim(), started.fence(),
                repositoryRoot, body);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        RepairBinding redelivered = coordinator.beginRepair(
                started.binding().attempt().roundId(),
                started.claim(),
                started.fence());
        var second = coordinator.launchRepair(
                supervisor, redelivered, started.claim(), started.fence(),
                repositoryRoot, body);

        assertThat(second).isEqualTo(first);
        assertThat(bodyStarts).hasValue(1);
        assertThat(count("flow_runtime_agent_run", "role = 'CI_FIXER'"))
                .isEqualTo(1);
        release.countDown();
        coordinator.awaitRepair(supervisor, redelivered, second, TTL);
    }

    @Test
    void launchRejectsCallerModifiedRepairBindingBeforeBodyExposure()
    {
        StartedRepair started = startRepair();
        CiRepairAttempt attempt = started.binding().attempt();
        CiRepairAttempt changed = new CiRepairAttempt(
                attempt.attemptId(),
                attempt.roundId(),
                attempt.operationId(),
                attempt.agentRunId(),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                attempt.inputRemoteHead(),
                attempt.inputChangeSetRevisionId(),
                attempt.outputLocalHead(),
                attempt.outputChangeSetRevisionId(),
                attempt.localCheckRunIds(),
                attempt.resultRef(),
                attempt.state(),
                attempt.retryOfAttemptId(),
                attempt.retryOrdinal(),
                attempt.createdAt());
        var bodies = new AtomicInteger();

        assertThatThrownBy(() -> coordinator.launchRepair(
                new InProcessWriterAgentSupervisor(runtime),
                new RepairBinding(changed, started.binding().run()),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current active attempt");
        assertThat(bodies).hasValue(0);
    }

    @Test
    void directFinalizationCannotInspectBeforeExactThreadStops()
            throws Exception
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var toolCalls = new AtomicInteger();
        Path transientDirty = Path.of(task.worktreePath())
                .resolve("transient-dirty.txt");
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        toolCalls.incrementAndGet();
                        try {
                            Files.writeString(
                                    transientDirty,
                                    "transient\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test body timed out");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    capability.runTool(() -> {
                        toolCalls.incrementAndGet();
                        try {
                            Files.delete(transientDirty);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> coordinator.finalizeRepairAttempt(
                started.binding().attempt().attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED, "forged-early", null),
                repositoryRoot))
                .isInstanceOf(IllegalStateException.class);
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.ACTIVE);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.ACTIVE);
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);

        release.countDown();
        coordinator.awaitRepair(supervisor, started.binding(), handle, TTL);
        assertThat(toolCalls).hasValue(2);
    }

    @Test
    void lateCiWriteFailureRollsBackWholeFinishAndExactRetryCommits()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        int changeSetsBefore = count(
                "flow_runtime_change_set_revision", "1 = 1");
        int reconciliationsBefore = count(
                "flow_runtime_operation", "kind = 'RECONCILE_TASK'");
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
                        commitCiChange("rollback.txt", "fixed\n", "fix CI");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        jdbc.execute("""
                CREATE TRIGGER fail_ci_attempt_finish
                BEFORE UPDATE ON flow_ci_repair_attempt
                WHEN NEW.state IN ('FIX_PREPARED', 'NO_HEAD_CHANGE')
                BEGIN
                    SELECT RAISE(ABORT, 'forced CI attempt failure');
                END
                """);

        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(RuntimeException.class);

        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.ACTIVE);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().resultRef()).isNull();
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                "operation_id = '" + started.claim().operationId()
                        + "' AND delivery_state = 'CLAIMED'"))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.RUNNING);
                    assertThat(session.lastRunId())
                            .isEqualTo(started.binding().run().runId());
                });
        assertThat(count(
                "flow_runtime_inbox",
                "selected_by_operation_id = '"
                        + started.claim().operationId()
                        + "' AND kind = 'FINAL_RED'"
                        + " AND handled_by_operation_id IS NULL"))
                .isOne();
        assertThat(count("flow_runtime_change_set_revision", "1 = 1"))
                .isEqualTo(changeSetsBefore);
        assertThat(count(
                "flow_runtime_operation", "kind = 'RECONCILE_TASK'"))
                .isEqualTo(reconciliationsBefore);

        jdbc.execute("DROP TRIGGER fail_ci_attempt_finish");
        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);

        assertThat(result.finalContent()).isEqualTo("opaque");
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(count("flow_runtime_agent_run", "role = 'CI_FIXER'"))
                .isOne();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                "operation_id = '" + started.claim().operationId()
                        + "' AND delivery_state = 'DONE'"))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> assertThat(session.state())
                        .isEqualTo(SessionState.IDLE));
        assertThat(count(
                "flow_runtime_inbox",
                "handled_by_operation_id = '"
                        + started.claim().operationId()
                        + "' AND kind = 'FINAL_RED'"))
                .isOne();
    }

    @Test
    void newerSameHeadEvidenceDoesNotInterruptActiveFix() throws Exception
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        Function<InProcessWriterAgentSupervisor.WriterToolCapability,
                InProcessWriterAgentSupervisor.AgentCompletion> body =
                capability -> {
                    bodies.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test body timed out");
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    capability.runTool(() -> {
                        tools.incrementAndGet();
                        commitCiChange("new-evidence.txt", "fixed\n", "fix CI");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                };
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                body);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        var observation = autofix.observeCi(pr.prId(), check(
                "new-active-check", "new-active-run", "FAILURE",
                "new-active-failure", NOW.plusSeconds(60)));
        autofix.attachLog(
                observation.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        CiRound successor = ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), publishedHead)).round();
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(successor.state()).isEqualTo(RoundState.FINAL_RED);

        RepairBinding redelivered = coordinator.beginRepair(
                started.binding().attempt().roundId(),
                started.claim(),
                started.fence());
        var duplicateHandle = coordinator.launchRepair(
                supervisor,
                redelivered,
                started.claim(),
                started.fence(),
                repositoryRoot,
                body);
        assertThat(duplicateHandle).isEqualTo(handle);
        assertThat(redelivered.attempt()).isEqualTo(started.binding().attempt());

        release.countDown();
        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);

        assertThat(result.finalContent()).isEqualTo("done");
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.FIX_PREPARED);
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(autofix.roundById(successor.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .hasSize(1);
    }

    @Test
    void boundQueuedRepairSurvivesSameHeadEvidenceAndRuntimeRestart()
    {
        StartedRepair started = startRepair();
        var bodies = new AtomicInteger();
        int processAttemptsBefore = count(
                "flow_runtime_agent_process_attempt", "1 = 1");
        var observation = autofix.observeCi(pr.prId(), check(
                "prelaunch-check", "prelaunch-run", "FAILURE",
                "prelaunch-failure", NOW.plusSeconds(60)));
        autofix.attachLog(
                observation.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        CiRound successor = ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), publishedHead)).round();
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);

        restart();
        RepairBinding redelivered = coordinator.beginRepair(
                started.binding().attempt().roundId(),
                started.claim(),
                started.fence());
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                redelivered,
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        coordinator.awaitRepair(supervisor, redelivered, handle, TTL);

        assertThat(redelivered.attempt()).isEqualTo(started.binding().attempt());
        assertThat(redelivered.run().runId())
                .isEqualTo(started.binding().run().runId());
        assertThat(bodies).hasValue(1);
        assertThat(autofix.roundById(
                started.binding().attempt().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);
        assertThat(autofix.roundById(successor.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(count("flow_runtime_agent_process_attempt", "1 = 1"))
                .isEqualTo(processAttemptsBefore + 1);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isPresent();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void dirtyStoppedFixIsDurablyBlockedAndNeverAdopted()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        Path dirtyPath = Path.of(task.worktreePath()).resolve("dirty.txt");
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    bodies.incrementAndGet();
                    capability.runTool(() -> {
                        try {
                            Files.writeString(
                                    dirtyPath,
                                    "dirty\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });

        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow().state())
                .isEqualTo(AttemptState.NEEDS_ATTENTION);
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();

        Path worktree = Path.of(task.worktreePath());
        gitOutput(worktree, "add", "dirty.txt");
        gitOutput(worktree, "commit", "-m", "out-of-band cleanup");
        assertThatThrownBy(() -> coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
        assertThat(bodies).hasValue(1);
        assertThat(runtime.resultForRun(started.binding().run().runId()))
                .isEmpty();
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(started.claim().operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isOne();
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

    private CiRound enqueueFailedRound()
    {
        CiRound red = failedRound("failure-1", NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        return coordinator.enqueueRepair(red.roundId()).round();
    }

    private StartedRepair startRepair()
    {
        CiRound round = enqueueFailedRound();
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(reconciliation)
                .orElseThrow();
        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_CI_FIXER);
        Claim fix = claim(OperationKind.RUN_CI_FIXER);
        ChangeSetRevision input = runtime.currentChangeSet(task.taskId())
                .orElseThrow();
        WriterFence fence = runtime.acquireWriterLease(
                fix,
                AgentRole.CI_FIXER,
                new WorktreeSnapshot(
                        input.headSha(),
                        input.headTreeDigest(),
                        "ci-input:" + input.changeSetRevisionId()),
                TTL);
        RepairBinding binding = coordinator.beginRepair(
                round.roundId(), fix, fence);
        return new StartedRepair(fix, fence, binding);
    }

    private record StartedRepair(
            Claim claim, WriterFence fence, RepairBinding binding) {}

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
                pr.prId(), publishedHead)).round();
    }

    private NormalizedCheck check(
            String checkId,
            String runId,
            String conclusion,
            String revision,
            Instant startedAt)
    {
        return new NormalizedCheck(
                publishedHead, "build", checkId, runId, 1, revision, "Build",
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
        repositoryRoot = temporaryDirectory.resolve("repository");
        Path worktree = temporaryDirectory.resolve("worktree");
        initializeRepository(repositoryRoot, worktree, "task/one");
        String base = gitOutput(repositoryRoot, "rev-parse", "HEAD");
        Task started = runtime.startTask(
                "request-1", "repo-1", "Implement", "task/one",
                worktree.toString());
        Claim provision = claim(OperationKind.PROVISION_TASK);
        runtime.provisionTask(provision, base, base);
        finishInitialTaskTurn();
        Task adopted = runtime.task(started.taskId()).orElseThrow();
        publishedHead = adopted.currentHeadSha();
        PullRequestSubject local = runtime.materializePullRequest(
                started.taskId(), adopted.currentChangeSetRevisionId(),
                "main", "main", "main");
        pr = runtime.bindRemoteIdentity(
                local.prId(), publishedHead, "GITHUB", "repo-external", 42,
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
        Task current = runtime.task(turn.taskId()).orElseThrow();
        WriterFence fence = runtime.acquireWriterLease(
                turn,
                AgentRole.TASK_AGENT,
                new WorktreeSnapshot(
                        current.currentHeadSha(),
                        "tree:" + current.currentHeadSha(),
                        "snapshot:" + current.currentHeadSha()),
                TTL);
        AgentRun run = runtime.startWriterAgent(
                turn, fence, "prompt:task", "capabilities:task");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = supervisor.launch(
                run.runId(),
                turn,
                fence,
                capability -> {
                    Task task = runtime.task(fence.taskId()).orElseThrow();
                    Path worktree = Path.of(task.worktreePath());
                    commitTaskChange(worktree);
                    runtime.adoptChangeSet(turn, fence, repositoryRoot, null);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "done", null);
                });
        supervisor.awaitAndFinish(handle, TTL);
    }

    private static void commitTaskChange(Path worktree)
    {
        try {
            Files.writeString(
                    worktree.resolve("task-change.txt"),
                    "change\n",
                    StandardCharsets.UTF_8);
            gitOutput(worktree, "add", "task-change.txt");
            gitOutput(worktree, "commit", "-m", "task change");
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void commitCiChange(String file, String content, String message)
    {
        try {
            Path worktree = Path.of(runtime.task(task.taskId())
                    .orElseThrow().worktreePath());
            Files.writeString(
                    worktree.resolve(file), content, StandardCharsets.UTF_8);
            gitOutput(worktree, "add", file);
            gitOutput(worktree, "commit", "-m", message);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
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
