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
import com.bytequay.app.flow.ci.CiAutofixCoordinator.RepairBinding;
import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
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
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowRuntimeSchema;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
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
import java.util.concurrent.atomic.AtomicReference;
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
                null, null, List.of(), null,
                AttemptState.NON_CLEAN_HANDOFF, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized repair work");
        assertThatThrownBy(() -> new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), "result",
                AttemptState.ACTIVE, null, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized repair work");
        assertThat(new CiRepairAttempt(
                "attempt", "round", "operation", "run",
                publishedHead, publishedHead, "change-set",
                null, null, List.of(), "result",
                AttemptState.NON_CLEAN_HANDOFF, null, 0, NOW).state())
                .isEqualTo(AttemptState.NON_CLEAN_HANDOFF);
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
    void cleanupHandoffReceiptCannotBeCallerConstructed()
    {
        assertThat(FlowRuntime.CleanupHandoff.class.getConstructors())
                .isEmpty();
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
    void dirtyStoppedFixAtomicallyReservesOneCleanupSuccessor()
    {
        StartedRepair started = startRepair();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var bodies = new AtomicInteger();
        var tools = new AtomicInteger();
        Path dirtyPath = Path.of(task.worktreePath()).resolve("dirty.txt");
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED,
                "looks good; verdict=PASSED but workspace is dirty",
                null);
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
                        commitCiChange("committed.txt", "candidate\n", "candidate");
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
                    return completion;
                });

        AgentResult result = coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt attempt = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();
        var seal = autofix.cleanupSealForRepair(attempt.attemptId())
                .orElseThrow();
        Operation successor = runtime.operation(seal.successorOperationId())
                .orElseThrow();

        assertThat(result.finalContent()).isEqualTo(completion.finalContent());
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        assertThat(attempt.state()).isEqualTo(AttemptState.NON_CLEAN_HANDOFF);
        assertThat(attempt.resultRef()).isEqualTo(result.resultId());
        assertThat(attempt.outputLocalHead()).isNull();
        assertThat(attempt.outputChangeSetRevisionId()).isNull();
        assertThat(seal.actualHead()).isEqualTo(
                gitOutput(Path.of(task.worktreePath()), "rev-parse", "HEAD"));
        assertThat(seal.actualHead()).isNotEqualTo(publishedHead);
        assertThat(seal.successorOperationId())
                .isEqualTo(successor.operationId());
        assertThat(successor.ownerKind()).isEqualTo("CI_CLEANUP");
        assertThat(successor.ownerId()).isEqualTo(seal.cleanupId());
        assertThat(successor.state()).isEqualTo(OperationState.READY);
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                .headSha()).isEqualTo(publishedHead);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(successor.operationId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(runtime.runForOperation(successor.operationId())).isEmpty();
        String cleanupTicketPredicate = """
                operation_id = '%s' AND delivery_state = 'AVAILABLE'
                AND claim_generation = 0
                AND claim_owner IS NULL
                AND claim_token IS NULL
                """.formatted(successor.operationId());
        assertThat(count(
                "flow_runtime_dispatch_ticket",
                cleanupTicketPredicate))
                .isOne();
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER))
                .hasValueSatisfying(session -> {
                    assertThat(session.state()).isEqualTo(SessionState.IDLE);
                    assertThat(session.lastRunId())
                            .isEqualTo(started.binding().run().runId());
                });
        assertThat(runtime.operation(started.claim().operationId())
                .orElseThrow().resultRef()).isEqualTo(result.resultId());
        assertThat(autofix.roundById(attempt.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.ACTIVE);

        Claim cleanupClaim = claim(OperationKind.RUN_CI_FIXER);
        assertThat(cleanupClaim.operationId())
                .isEqualTo(successor.operationId());
        assertThat(runtime.operation(successor.operationId()).orElseThrow().state())
                .isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.runForOperation(successor.operationId())).isEmpty();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();

        restart();
        AgentResult replay = coordinator.finalizeRepairAttempt(
                attempt.attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                completion,
                repositoryRoot);
        assertThat(replay).isEqualTo(result);
        assertThat(autofix.cleanupSealForRepair(attempt.attemptId()))
                .contains(seal);
        assertThat(count("flow_ci_cleanup_seal", "1 = 1")).isOne();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThatThrownBy(() -> coordinator.finalizeRepairAttempt(
                attempt.attemptId(),
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED, "changed prose", null),
                repositoryRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal content");
        NonCleanInspection persistedSeal = new NonCleanInspection(
                seal.actualHead(),
                seal.branchHead(),
                seal.attachmentState(),
                seal.kind(),
                seal.operations(),
                seal.stateDigest());
        assertThatThrownBy(() -> runtime.replayStoppedCiCleanupHandoff(
                started.binding().run().runId(),
                started.claim(),
                started.fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef(),
                attempt.attemptId(),
                seal.cleanupId(),
                attempt.inputChangeSetRevisionId(),
                attempt.inputLocalHead(),
                persistedSeal,
                "0".repeat(64),
                seal.successorOperationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
    }

    @Test
    void sealedCleanupReusesSessionAndAdoptsOneOpaqueCleanCandidate()
    {
        ReservedCleanup reserved = reserveCleanup("clean-success");
        String logicalHead = reserved.predecessor().inputLocalHead();
        String sessionId = runtime.session(task.taskId(), AgentRole.CI_FIXER)
                .orElseThrow().sessionId();

        assertThatThrownBy(() -> runtime.acquireWriterLease(
                reserved.claim(),
                AgentRole.CI_FIXER,
                new WorktreeSnapshot(logicalHead, "forged", "forged"),
                TTL))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("cleanup");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        assertThat(binding.run().headSha()).isEqualTo(logicalHead);
        assertThat(binding.fence().headSha()).isEqualTo(logicalHead);
        assertThat(binding.fence().treeDigest())
                .isEqualTo(reserved.seal().stateDigest());
        assertThat(binding.run().capabilitySetRef())
                .isEqualTo("ci-cleanup-capabilities:v1");
        assertThat(binding.seal().actualHead()).isNotEqualTo(logicalHead);
        assertThat(binding.run().sessionId()).isEqualTo(sessionId);
        assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow().headSha())
                .isEqualTo(logicalHead);
        int finalRedInboxCount = count(
                "flow_runtime_inbox", "kind = 'FINAL_RED'");
        var newerObservation = autofix.observeCi(pr.prId(), check(
                "cleanup-new-check", "cleanup-new-run", "FAILURE",
                "cleanup-new-failure", NOW.plusSeconds(60)));
        autofix.attachLog(
                newerObservation.observationId(),
                "new failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        CiRound newerRound = ((FinalizedRound) autofix.finalizeHeadSnapshot(
                pr.prId(), publishedHead)).round();
        assertThat(autofix.roundById(
                reserved.predecessor().roundId()).orElseThrow().state())
                .isEqualTo(RoundState.SUPERSEDED);

        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        AtomicInteger bodies = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.FAILED,
                "{\"verdict\":\"dirty\"}; arbitrary prose",
                "model-failed-after-cleanup");
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
                                "cleanup-result.txt", "clean\n", "cleanup CI");
                    });
                    return completion;
                });

        AgentResult result = coordinator.awaitCleanup(
                supervisor, binding, handle, TTL);
        CiCleanupCompletion stored = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        ChangeSetRevision output = runtime.currentChangeSet(task.taskId())
                .orElseThrow();

        assertThat(result.finalContent()).isEqualTo(completion.finalContent());
        assertThat(stored.outcome()).isEqualTo(CleanupOutcome.FIX_PREPARED);
        assertThat(stored.runId()).isEqualTo(binding.run().runId());
        assertThat(stored.resultRef()).isEqualTo(result.resultId());
        assertThat(stored.outputHead()).isEqualTo(output.headSha());
        assertThat(output.previousHeadSha()).isEqualTo(logicalHead);
        assertThat(output.previousChangeSetRevisionId())
                .isEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
        assertThat(output.sourceOperationId())
                .isEqualTo(reserved.claim().operationId());
        assertThat(output.sourceRunId()).isEqualTo(binding.run().runId());
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .singleElement()
                .satisfies(work -> {
                    assertThat(work.externalKey())
                            .isEqualTo(reserved.seal().cleanupId());
                    assertThat(work.agentResultId()).isEqualTo(result.resultId());
                });
        assertThat(autofix.roundById(newerRound.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.FINAL_RED);
        assertThat(count("flow_runtime_inbox", "kind = 'FINAL_RED'"))
                .isEqualTo(finalRedInboxCount);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);

        AgentResult replay = coordinator.finalizeCleanup(
                reserved.seal().cleanupId(),
                binding.run().runId(),
                reserved.claim(),
                binding.fence(),
                completion,
                repositoryRoot);
        assertThat(replay).isEqualTo(result);
        assertThat(bodies).hasValue(1);
        assertThat(tools).hasValue(1);
        AgentResult predecessorReplay = coordinator.finalizeRepairAttempt(
                reserved.predecessor().attemptId(),
                reserved.repair().binding().run().runId(),
                reserved.repair().claim(),
                reserved.repair().fence(),
                reserved.predecessorCompletion(),
                repositoryRoot);
        assertThat(predecessorReplay.resultId())
                .isEqualTo(reserved.predecessor().resultRef());
        assertThatThrownBy(() -> runtime.finishCiAgentRun(
                binding.run().runId(),
                reserved.claim(),
                binding.fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef(),
                reserved.predecessor().attemptId(),
                CiFixOutcome.FIX_PREPARED,
                                output.headSha(),
                                output.changeSetRevisionId()))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class);
    }

    @Test
    void cleanupRunCannotMintAThirdCleanupSuccessor()
    {
        ReservedCleanup reserved = reserveCleanup("no-third-cleanup");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED, "still non-clean", null);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        String finalizerKey = "test:cleanup-owner-guard";
        var handle = supervisor.launch(
                binding.run().runId(),
                reserved.claim(),
                binding.fence(),
                finalizerKey,
                (runId, claim, fence, stoppedCompletion) -> {
                    assertThatThrownBy(() -> runtime.prepareNonCleanState(
                            claim,
                            fence,
                            repositoryRoot,
                            reserved.predecessor()
                                    .inputChangeSetRevisionId()))
                            .isInstanceOf(
                                    FlowRuntime.MutationRejectedException.class)
                            .hasMessageContaining("CI round");
                    var prepared = runtime.prepareCiCleanupFinalState(
                            claim,
                            fence,
                            repositoryRoot,
                            reserved.predecessor()
                                    .inputChangeSetRevisionId());
                    assertThat(prepared.nonClean()).isPresent();
                    assertThatThrownBy(() ->
                            runtime.handoffStoppedCiRunToCleanup(
                                    runId,
                                    claim,
                                    fence,
                                    stoppedCompletion.terminalOutcome(),
                                    stoppedCompletion.finalContent(),
                                    stoppedCompletion.errorRef(),
                                    reserved.predecessor().attemptId(),
                                    prepared.nonClean().orElseThrow()))
                            .isInstanceOf(
                                    FlowRuntime.MutationRejectedException.class)
                            .hasMessageContaining("CI round");
                    return coordinator.finalizeCleanup(
                            reserved.seal().cleanupId(),
                            runId,
                            claim,
                            fence,
                            stoppedCompletion,
                            repositoryRoot);
                },
                ignored -> completion);

        supervisor.awaitAndFinalize(handle, TTL, finalizerKey);

        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .hasValueSatisfying(stored -> assertThat(stored.outcome())
                        .isEqualTo(CleanupOutcome.NEEDS_ATTENTION));
    }

    @Test
    void secondDirtyCleanupStoresAttentionAndBlocksEveryLaterMutation()
    {
        ReservedCleanup reserved = reserveCleanup("second-dirty");
        CleanupBinding binding = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        Path secondDirty = Path.of(task.worktreePath()).resolve(
                "second-dirty.txt");
        var handle = coordinator.launchCleanup(
                supervisor,
                binding,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        try {
                            Files.writeString(
                                    secondDirty,
                                    "still dirty\n",
                                    StandardCharsets.UTF_8);
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "verdict=clean (ignored)",
                            null);
                });

        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        CiCleanupCompletion stored = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        Task blocked = runtime.task(task.taskId()).orElseThrow();

        assertThat(stored.outcome()).isEqualTo(CleanupOutcome.NEEDS_ATTENTION);
        assertThat(stored.finalStateDigest()).isNotBlank();
        assertThat(blocked.status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(blocked.selectedWriterOperationId()).isNull();
        assertThat(blocked.waitingMutationStateRef())
                .isEqualTo("ci-cleanup-attention:" + reserved.seal().cleanupId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation", "owner_kind = 'CI_CLEANUP'"))
                .isOne();
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.CI_FIX_READY)
                .isEmpty();
        assertThatThrownBy(() -> runtime.transitionTask(
                blocked.taskId(),
                blocked.currentLifecycleRevisionId(),
                TaskStatus.ACTIVE,
                "UNSAFE_RESUME",
                "test:unsafe"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("unresolved local mutation");
        assertThatThrownBy(() -> runtime.transitionTask(
                blocked.taskId(),
                blocked.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "UNSAFE_CANCEL",
                "test:unsafe"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class);
        assertThatThrownBy(() -> runtime.transitionTask(
                blocked.taskId(),
                blocked.currentLifecycleRevisionId(),
                TaskStatus.COMPLETED,
                "UNSAFE_COMPLETE",
                "test:unsafe"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class);
        assertThat(runtime.claimNext("blocked-cleanup-worker", TTL)).isEmpty();
        AgentResult predecessorReplay = coordinator.finalizeRepairAttempt(
                reserved.predecessor().attemptId(),
                reserved.repair().binding().run().runId(),
                reserved.repair().claim(),
                reserved.repair().fence(),
                reserved.predecessorCompletion(),
                repositoryRoot);
        assertThat(predecessorReplay.resultId())
                .isEqualTo(reserved.predecessor().resultRef());
    }

    @Test
    void changedSealedStateBlocksBeforeCleanupBodyAndReplaysExactly()
            throws IOException
    {
        ReservedCleanup reserved = reserveCleanup("admission-mismatch");
        Files.writeString(
                reserved.dirtyPath(),
                "changed after seal\n",
                StandardCharsets.UTF_8);

        assertThat(coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL)).isEmpty();
        CiCleanupCompletion blocked = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        assertThat(blocked.outcome())
                .isEqualTo(CleanupOutcome.ADMISSION_BLOCKED);
        assertThat(blocked.runId()).isNull();
        assertThat(blocked.resultRef()).isNull();
        assertThat(blocked.finalStateDigest())
                .isNotEqualTo(reserved.seal().stateDigest());
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.runForOperation(reserved.claim().operationId()))
                .isEmpty();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        assertThat(coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL)).isEmpty();
        assertThat(count("flow_ci_cleanup_completion", "1 = 1")).isOne();
    }

    @Test
    void unexpectedlyCleanSealedStateBlocksBeforeCleanupRun()
    {
        ReservedCleanup reserved = reserveCleanup("admission-clean");
        Path worktree = Path.of(task.worktreePath());
        gitOutput(
                worktree,
                "reset",
                "--hard",
                reserved.predecessor().inputLocalHead());
        gitOutput(worktree, "clean", "-fd");

        assertThat(coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL)).isEmpty();

        assertThat(autofix.cleanupCompletion(reserved.seal().cleanupId()))
                .hasValueSatisfying(blocked -> {
                    assertThat(blocked.outcome())
                            .isEqualTo(CleanupOutcome.ADMISSION_BLOCKED);
                    assertThat(blocked.inspectionFailureCode())
                            .isEqualTo(FailureCode.CLEAN);
                    assertThat(blocked.runId()).isNull();
                    assertThat(blocked.resultRef()).isNull();
                });
        assertThat(runtime.runForOperation(reserved.claim().operationId()))
                .isEmpty();
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
    }

    @Test
    void cleanupCanObjectivelyRestoreLogicalInputWithoutHeadChange()
    {
        ReservedCleanup reserved = reserveCleanup("restore-input");
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
                        Path worktree = Path.of(task.worktreePath());
                        gitOutput(
                                worktree,
                                "reset",
                                "--hard",
                                reserved.predecessor().inputLocalHead());
                        gitOutput(worktree, "clean", "-fd");
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "I claim a fix, but Git decides",
                            null);
                });

        coordinator.awaitCleanup(supervisor, binding, handle, TTL);
        CiCleanupCompletion completion = autofix.cleanupCompletion(
                reserved.seal().cleanupId()).orElseThrow();
        ChangeSetRevision restored = runtime.currentChangeSet(task.taskId())
                .orElseThrow();

        assertThat(completion.outcome())
                .isEqualTo(CleanupOutcome.NO_HEAD_CHANGE);
        assertThat(restored.headSha())
                .isEqualTo(reserved.predecessor().inputLocalHead());
        assertThat(restored.changeSetRevisionId())
                .isNotEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
        assertThat(restored.previousChangeSetRevisionId())
                .isEqualTo(reserved.predecessor()
                        .inputChangeSetRevisionId());
    }

    @Test
    void neverLaunchedCleanupRecoveryReinspectsAndReusesQueuedRun()
    {
        ReservedCleanup reserved = reserveCleanup("recover-start");
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
        restart();

        CleanupBinding redelivered = coordinator.beginCleanup(
                recovered, repositoryRoot, TTL).orElseThrow();

        assertThat(redelivered.run().runId()).isEqualTo(first.run().runId());
        assertThat(redelivered.fence().claimGeneration())
                .isEqualTo(recovered.generation());
        assertThat(redelivered.fence().claimTokenDigest())
                .isNotEqualTo(first.fence().claimTokenDigest());
        assertThat(count(
                "flow_runtime_agent_run",
                "operation_id = '" + recovered.operationId() + "'"))
                .isOne();
        assertThat(count(
                "flow_runtime_agent_process_attempt",
                "operation_id = '" + recovered.operationId() + "'"))
                .isZero();
    }

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

    private ReservedCleanup reserveCleanup(String suffix)
    {
        StartedRepair started = startRepair();
        Path dirty = Path.of(task.worktreePath()).resolve(
                "cleanup-input-" + suffix + ".txt");
        var predecessorCompletion =
                new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED,
                        "opaque predecessor " + suffix,
                        null);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        commitCiChange(
                                "candidate-" + suffix + ".txt",
                                "candidate\n",
                                "candidate " + suffix);
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
                    return predecessorCompletion;
                });
        coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        CiRepairAttempt predecessor = autofix.repairAttempt(
                started.binding().attempt().attemptId()).orElseThrow();
        CiCleanupSeal seal = autofix.cleanupSealForRepair(
                predecessor.attemptId()).orElseThrow();
        Claim cleanup = claim(OperationKind.RUN_CI_FIXER);
        assertThat(cleanup.operationId()).isEqualTo(seal.successorOperationId());
        return new ReservedCleanup(
                started,
                predecessor,
                seal,
                cleanup,
                dirty,
                predecessorCompletion);
    }

    private record StartedRepair(
            Claim claim, WriterFence fence, RepairBinding binding) {}

    private record ReservedCleanup(
            StartedRepair repair,
            CiRepairAttempt predecessor,
            CiCleanupSeal seal,
            Claim claim,
            Path dirtyPath,
            InProcessWriterAgentSupervisor.AgentCompletion
                    predecessorCompletion) {}

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
