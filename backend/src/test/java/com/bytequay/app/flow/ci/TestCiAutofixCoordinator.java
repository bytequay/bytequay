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
import com.bytequay.app.flow.ci.CiAutofixRecords.LearningCompletionState;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedCiUpdate;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateConsentRevision;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGatesSchema;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.github.GitHubEffectsSchema;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetSource;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixSourceKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GitHubRepositoryLocator;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckPolicyRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
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
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.LocalChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.ConnectionCallback;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.FAILED_ACTIONS;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.FAILED_UNSUPPORTED;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.GREEN;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.PENDING;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.UNSTABLE;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeApplied;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeCiObservation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeTerminalProbe;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeUnavailableProbe;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.observation;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.prepareCiObservation;
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
    private LocalChecks localChecks;
    private CiAutofix autofix;
    private GitHubEffects githubEffects;
    private UserGates userGates;
    private CiAutofixCoordinator coordinator;
    private Task task;
    private PullRequestSubject pr;
    private Path repositoryRoot;
    private String publishedHead;
    private Instant runtimeNow;
    private final AtomicReference<Runnable> publishedSubjectHook =
            new AtomicReference<>();

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("flow.db")
                        + "?foreign_keys=ON&busy_timeout=5000");
        runtimeNow = NOW;
        FlowRuntimeSchema.install(dataSource);
        CiAutofixSchema.install(dataSource);
        UserGatesSchema.install(dataSource);
        GitHubEffectsSchema.install(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        runtime = new FlowRuntime(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        task = publishedTask();
        localChecks = new LocalChecks(
                dataSource, runtime, Clock.fixed(NOW, ZoneOffset.UTC));
        localChecks.recordPolicy(
                task.repositoryId(),
                null,
                "test-policy:v1",
                "test-policy-digest:v1",
                List.of(new LocalChecks.ProfileDefinition(
                        "true",
                        List.of("/usr/bin/true"),
                        ".",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(GateIntent.INITIAL_PUBLISH,
                                GateIntent.CI_UPDATE))));
        autofix = new CiAutofix(
                dataSource,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                this::publishedSubject);
        githubEffects = new GitHubEffects(
                dataSource, runtime);
        userGates = new UserGates(
                dataSource,
                runtime,
                localChecks,
                autofix,
                githubEffects,
                Clock.fixed(NOW, ZoneOffset.UTC));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
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
                .hasMessageContaining("typed recovery");
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
                    assertThat(capability.spawnAdversarialReviewer())
                            .isEqualTo(request.get());
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
                            assertThat(capability.readyForReview().status())
                                    .isEqualTo("ACCEPTED_SEALED");
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

    @Test
    void lateSecondRevisionFailureRollsBackAndStoppedRetryDoesNotRerunBody()
    {
        CompletedReady first = openReadyGate("rollback-first-ready");
        String firstCandidate = runtime.currentChangeSet(task.taskId())
                .orElseThrow().headSha();
        runtime.advanceRemoteHead(
                pr.prId(), publishedHead, firstCandidate);
        publishedHead = firstCandidate;
        ReviewerResultReady second = prepareReviewerResult(
                "rollback-second-ready", "failure-rollback-second");
        AtomicInteger bodies = new AtomicInteger();
        CountDownLatch sealed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = second.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        second.binding(),
                        second.claim(),
                        capability -> {
                            bodies.incrementAndGet();
                            capability.readyForReview();
                            sealed.countDown();
                            awaitLatch(release);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque retryable ready",
                                            null);
                        });
        awaitLatch(sealed);
        jdbc.execute("""
                CREATE TRIGGER fail_ready_result
                BEFORE INSERT ON flow_runtime_agent_result
                WHEN NEW.run_id = '%s'
                BEGIN
                    SELECT RAISE(ABORT, 'forced ready result failure');
                END
                """.formatted(second.binding().run().runId()));
        release.countDown();

        assertThatThrownBy(() -> second.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        second.binding(),
                        handle,
                        TTL)).isInstanceOf(RuntimeException.class);

        var gateAfterFailure = userGates.gate(pr.prId()).orElseThrow();
        assertThat(gateAfterFailure.currentRevision()).isEqualTo(1);
        assertThat(userGates.transitions(gateAfterFailure.gateId()))
                .extracting(transition -> transition.reasonCode())
                .containsExactly("READY");
        assertThat(userGates.revisionForRun(
                second.binding().run().runId())).isEmpty();
        assertThat(runtime.resultForRun(second.binding().run().runId()))
                .isEmpty();
        var readyRequest = runtime.readyForReviewRequestForRun(
                second.binding().run().runId()).orElseThrow();
        assertThat(userGates.subject(readyRequest.subjectRef())).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_user_gate_ci_update_action "
                        + "WHERE action_ref = ?",
                Integer.class,
                readyRequest.actionRef())).isEqualTo(1);
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.RUNNING);
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(second.claim().operationId());
        assertThat(bodies).hasValue(1);

        jdbc.execute("DROP TRIGGER fail_ready_result");
        AgentResult retried = second.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        second.binding(),
                        handle,
                        TTL);
        assertThat(retried.runId()).isEqualTo(second.binding().run().runId());
        assertThat(bodies).hasValue(1);
        var gate = userGates.gate(pr.prId()).orElseThrow();
        assertThat(gate.currentRevision()).isEqualTo(2);
        assertThat(userGates.transitions(gate.gateId()))
                .extracting(transition -> transition.reasonCode())
                .containsExactly(
                        "READY", "SUPERSEDED_BY_READY", "READY");
        assertThat(userGates.revisionForRun(
                first.ready().binding().run().runId()))
                .contains(first.revision());
    }

    @Test
    void reviewerReservationRejectsAnOlderEvidenceAttempt()
    {
        ReviewReady ready = prepareCleanReview("latest-check-evidence");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        String finalizerKey = "TEST_LATEST_CHECK:"
                + ready.binding().run().runId();
        var handle = supervisor.launch(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                finalizerKey,
                (runId, claim, fence, completion) ->
                        runtime.finishTaskAgentReviewTurn(
                                runId,
                                claim,
                                fence,
                                completion.terminalOutcome(),
                                completion.finalContent(),
                                completion.errorRef()),
                capability -> {
                    capability.runChecks(localChecks, repositoryRoot, null);
                    ChangeSetRevision current = runtime.currentChangeSet(
                            task.taskId()).orElseThrow();
                    LocalChecks.ReviewerEvidence old =
                            localChecks.reviewerEvidence(
                                    task.taskId(),
                                    current.changeSetRevisionId(),
                                    GateIntent.CI_UPDATE);
                    capability.runChecks(localChecks, repositoryRoot, null);
                    assertThatThrownBy(() ->
                            capability.spawnAdversarialReviewer(
                                    repositoryRoot,
                                    ready.binding().run()
                                            .inputChangeSetRevisionId(),
                                    reviewOrigin(ready),
                                    old))
                            .isInstanceOf(
                                    FlowRuntime.StaleOwnerRevisionException.class)
                            .hasMessageContaining("complete/latest");
                    request.set(capability.spawnAdversarialReviewer(
                            repositoryRoot,
                            ready.binding().run()
                                    .inputChangeSetRevisionId(),
                            reviewOrigin(ready),
                            localChecks.reviewerEvidence(
                                    task.taskId(),
                                    current.changeSetRevisionId(),
                                    GateIntent.CI_UPDATE)));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        supervisor.awaitAndFinalize(handle, TTL, finalizerKey);

        assertThat(request.get().checkRunRefs()).hasSize(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT attempt_sequence FROM flow_runtime_local_check_run
                WHERE check_run_id = ?
                """,
                Long.class,
                request.get().checkRunRefs().getFirst())).isEqualTo(2);
    }

    @Test
    void durableReviewerRequestReplaysAfterPolicyAdvances()
    {
        ReviewReady ready = prepareCleanReview("frozen-policy-replay");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runChecks();
                    request.set(capability.spawnAdversarialReviewer());
                    LocalCheckPolicyRevision current = localChecks
                            .currentPolicy(task.repositoryId()).orElseThrow();
                    localChecks.recordPolicy(
                            task.repositoryId(),
                            current.policyRevisionId(),
                            "test-policy:v2",
                            "test-policy-digest:v2",
                            List.of(new LocalChecks.ProfileDefinition(
                                    "true",
                                    List.of("/usr/bin/true"),
                                    ".",
                                    List.of(),
                                    Duration.ofSeconds(5),
                                    List.of(GateIntent.CI_UPDATE))));
                    assertThat(capability.spawnAdversarialReviewer())
                            .isEqualTo(request.get());
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        assertThat(request.get().localCheckPolicyRevisionId())
                .isNotEqualTo(localChecks.currentPolicy(task.repositoryId())
                        .orElseThrow().policyRevisionId());
    }

    @Test
    void timedOutLocalCheckSealsToolsAndLeavesTaskInAttention()
    {
        assertBoundaryUnprovenNeedsAttention(
                "timeout", List.of("/bin/sleep", "8"),
                Duration.ofSeconds(1));
    }

    @Test
    void heldOpenCheckPipeSealsToolsAndLeavesTaskInAttention()
    {
        assertBoundaryUnprovenNeedsAttention(
                "held-pipe",
                List.of("/bin/sh", "-c", "(sleep 8) & exit 0"),
                Duration.ofSeconds(5));
    }

    private void assertBoundaryUnprovenNeedsAttention(
            String suffix, List<String> command, Duration timeout)
    {
        LocalCheckPolicyRevision current = localChecks.currentPolicy(
                task.repositoryId()).orElseThrow();
        localChecks.recordPolicy(
                task.repositoryId(),
                current.policyRevisionId(),
                "test-policy:" + suffix,
                "test-policy-digest:" + suffix,
                List.of(new LocalChecks.ProfileDefinition(
                        suffix,
                        command,
                        ".",
                        List.of(),
                        timeout,
                        List.of(GateIntent.CI_UPDATE))));
        ReviewReady ready = prepareCleanReview(suffix + "-attention");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    assertThatThrownBy(capability::runChecks)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(
                                    "PROCESS_BOUNDARY_UNPROVEN");
                    assertThatThrownBy(capability::spawnAdversarialReviewer)
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class)
                            .hasMessageContaining("boundary is unresolved");
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_reviewer_request",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_writer_lease",
                Integer.class)).isZero();
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        assertThat(runtime.claimNext("boundary-other", TTL)).isEmpty();
    }

    @Test
    void missingTerminalReviewerCommandConsumesInputAndReplaysAfterCancel()
    {
        ReviewReady ready = prepareCleanReview("missing-review-command");
        var completion = new InProcessWriterAgentSupervisor.AgentCompletion(
                TerminalOutcome.COMPLETED,
                "I claim success without the required command",
                null);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> completion);
        AgentResult result = ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        Task attention = runtime.task(task.taskId()).orElseThrow();
        assertThat(attention.status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.pendingId().equals(
                        ready.binding().input().pendingId()))
                .singleElement()
                .satisfies(work -> assertThat(work.handledByOperationId())
                        .isEqualTo(ready.claim().operationId()));
        assertThatThrownBy(() -> runtime.transitionTask(
                attention.taskId(),
                attention.currentLifecycleRevisionId(),
                TaskStatus.ACTIVE,
                "UNSAFE_RESUME",
                "test:resume"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("typed recovery");
        runtime.transitionTask(
                attention.taskId(),
                attention.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "USER_CANCELED",
                "test:canceled");

        AgentResult replayed = runtime.finishTaskAgentReviewTurn(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef());
        assertThat(replayed).isEqualTo(result);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_writer_lease WHERE task_id = ?",
                Integer.class,
                task.taskId())).isZero();
    }

    @Test
    void taskOwnedCorrectionBecomesTheFrozenReviewerSubject()
    {
        ReviewReady ready = prepareCleanReview("task-correction");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runTool(() -> {
                        commitCiChange(
                                "task-correction.txt",
                                "corrected\n",
                                "Task correction");
                        runtime.adoptChangeSet(
                                ready.claim(),
                                ready.binding().fence(),
                                repositoryRoot,
                                ready.binding().run()
                                        .inputChangeSetRevisionId());
                    });
                    capability.runChecks();
                    request.set(capability.spawnAdversarialReviewer());
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);

        ChangeSetRevision current = runtime.currentChangeSet(task.taskId())
                .orElseThrow();
        assertThat(current.source()).isEqualTo(ChangeSetSource.TASK_AGENT);
        assertThat(current.sourceOperationId())
                .isEqualTo(ready.claim().operationId());
        assertThat(current.sourceRunId())
                .isEqualTo(ready.binding().run().runId());
        assertThat(request.get().changeSetRevisionId())
                .isEqualTo(current.changeSetRevisionId());
        assertThat(request.get().reviewedHeadSha())
                .isEqualTo(current.headSha());

        Claim reviewerClaim = claim(OperationKind.RUN_REVIEWER);
        var reviewerStart = ready.review().beginReviewer(
                request.get().requestId(), reviewerClaim);
        var reviewerSupervisor = new InProcessReviewerAgentSupervisor(runtime);
        var reviewerHandle = ready.review().launchReviewer(
                reviewerSupervisor,
                reviewerStart,
                reviewerClaim,
                capability -> new InProcessReviewerAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "opaque corrected review",
                                null));
        ready.review().awaitReviewer(
                reviewerSupervisor, reviewerHandle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNext(reconciliation).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim continuation = claim(OperationKind.RUN_TASK_TURN);
        var continuationBinding = ready.review()
                .beginReviewerResultContinuation(continuation, TTL);
        var continuationHandle = ready.review()
                .launchReviewerResultContinuation(
                        supervisor,
                        continuationBinding,
                        continuation,
                        capability -> {
                            capability.readyForReview();
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque corrected ready",
                                            null);
                        });
        ready.review().awaitReviewerResultContinuation(
                supervisor,
                continuationBinding,
                continuationHandle,
                TTL);

        var revision = userGates.revisionForRun(
                continuationBinding.run().runId()).orElseThrow();
        var subject = userGates.subject(
                revision.subjectManifestRef()).orElseThrow();
        assertThat(subject.changeSetRevisionId())
                .isEqualTo(current.changeSetRevisionId());
        assertThat(subject.proposedHead()).isEqualTo(current.headSha());
        assertThat(subject.originCiFixPendingId())
                .isEqualTo(request.get().originCiFixPendingId());
        assertThat(subject.originCiFixSourceKind())
                .isEqualTo(request.get().originCiFixSourceKind());
        assertThat(subject.originCiFixSourceId())
                .isEqualTo(request.get().originCiFixSourceId());
    }

    @Test
    void reviewerCompletionReplacesReadyReconciliationWithResultPriority()
    {
        assertReviewerCompletionReplacesReconciliation(false);
    }

    @Test
    void reviewerCompletionReplacesWaitingReconciliationWithResultPriority()
    {
        assertReviewerCompletionReplacesReconciliation(true);
    }

    @Test
    void changedReviewerResultTurnWithoutFreshReviewNeedsAttention()
    {
        ReviewerResultReady ready = prepareReviewerResult("changed-no-review");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.runTool(() -> {
                                commitCiChange(
                                        "changed-after-review.txt",
                                        "changed\n",
                                        "Change after review");
                                runtime.adoptChangeSet(
                                        ready.claim(),
                                        ready.binding().fence(),
                                        repositoryRoot,
                                        ready.binding().run()
                                                .inputChangeSetRevisionId());
                            });
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque no child",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(jdbc.queryForObject(
                """
                SELECT reason_code
                FROM flow_runtime_task_lifecycle_revision
                WHERE task_id = ? ORDER BY sequence DESC LIMIT 1
                """,
                String.class,
                task.taskId())).isEqualTo("CHANGED_WITHOUT_FRESH_REVIEW");
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind()
                        == PendingKind.AGENT_RESULT_READY)
                .singleElement()
                .satisfies(work -> assertThat(work.handledByOperationId())
                        .isEqualTo(ready.claim().operationId()));
    }

    @Test
    void changedReviewerResultTurnCanRequestFreshReviewer()
    {
        ReviewerResultReady ready = prepareReviewerResult("changed-reviewed");
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.runTool(() -> {
                                commitCiChange(
                                        "changed-and-reviewed.txt",
                                        "changed\n",
                                        "Change and review");
                                runtime.adoptChangeSet(
                                        ready.claim(),
                                        ready.binding().fence(),
                                        repositoryRoot,
                                        ready.binding().run()
                                                .inputChangeSetRevisionId());
                            });
                            capability.runChecks();
                            request.set(
                                    capability.spawnAdversarialReviewer());
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque fresh child",
                                            null);
                        });
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        ChangeSetRevision current = runtime.currentChangeSet(task.taskId())
                .orElseThrow();
        assertThat(request.get().changeSetRevisionId())
                .isEqualTo(current.changeSetRevisionId());
        assertThat(request.get().reviewedHeadSha())
                .isEqualTo(current.headSha());
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.PARKED_CHILD);
        assertThat(runtime.operation(request.get().reviewerOperationId())
                .orElseThrow().state()).isEqualTo(OperationState.READY);
    }

    @ParameterizedTest
    @EnumSource(value = TerminalOutcome.class, names = {"FAILED", "CANCELED"})
    void terminalParentWithCommittedRequestStillParksAndActivatesReviewer(
            TerminalOutcome parentOutcome)
    {
        ReviewReady ready = prepareCleanReview(
                "terminal-parent-" + parentOutcome);
        ParkedReview parked = parkForReviewer(ready, parentOutcome);

        assertThat(parked.parentResult().terminalOutcome())
                .isEqualTo(parentOutcome);
        assertThat(runtime.session(task.taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.PARKED_CHILD);
        Claim reviewer = claim(OperationKind.RUN_REVIEWER);
        var start = ready.review().beginReviewer(
                parked.request().requestId(), reviewer);
        assertThat(start.run().state()).isEqualTo(RunState.QUEUED);
        assertThat(start.request()).isEqualTo(parked.request());
    }

    @Test
    void terminalReviewerRequestRejectsConflictingReplayAndLaterTools()
    {
        ReviewReady ready = prepareCleanReview("terminal-replay");
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        String finalizerKey = "TEST_TASK_REVIEW:" + ready.binding().run()
                .runId();
        var handle = supervisor.launch(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                finalizerKey,
                (runId, claim, fence, completion) ->
                        runtime.finishTaskAgentReviewTurn(
                                runId,
                                claim,
                                fence,
                                completion.terminalOutcome(),
                                completion.finalContent(),
                                completion.errorRef()),
                capability -> {
                    capability.runChecks(
                            localChecks, repositoryRoot, null);
                    ChangeSetRevision current = runtime.currentChangeSet(
                            task.taskId()).orElseThrow();
                    ReviewerRequest request = capability
                            .spawnAdversarialReviewer(
                                    repositoryRoot,
                                    ready.binding().run()
                                            .inputChangeSetRevisionId(),
                                    reviewOrigin(ready),
                                    localChecks.reviewerEvidence(
                                            task.taskId(),
                                            current.changeSetRevisionId(),
                                            GateIntent.CI_UPDATE));
                    assertThat(capability.replayAdversarialReviewer(
                            repositoryRoot,
                            ready.binding().run()
                                    .inputChangeSetRevisionId(),
                            reviewOrigin(ready),
                            request.localCheckPolicyRevisionId(),
                            request.checkRunRefs())).isEqualTo(request);
                    assertThatThrownBy(() ->
                            capability.replayAdversarialReviewer(
                                    repositoryRoot,
                                    ready.binding().run()
                                            .inputChangeSetRevisionId(),
                                    reviewOrigin(ready),
                                    request.localCheckPolicyRevisionId(),
                                    List.of("conflicting-check")))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("terminal arguments");
                    assertThatThrownBy(() -> capability.runTool(() -> {}))
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "opaque", null);
                });
        supervisor.awaitAndFinalize(handle, TTL, finalizerKey);
    }

    @Test
    void expiredReservedReviewerRedrivesTheSameRun()
    {
        ReviewerClaim reviewer = prepareReviewerClaim("reserved-expiry");
        insertReviewerProcessAttempt(
                reviewer, "reserved-reviewer", "RESERVED");
        expireRuntime();

        assertThat(runtime.recoverExpiredClaim(
                reviewer.claim().operationId(),
                reviewer.claim().generation())).isTrue();
        assertThat(runtime.operation(reviewer.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.RETRYABLE);
        runtime.redriveRetryable(reviewer.claim().operationId());
        Claim redriven = claim(OperationKind.RUN_REVIEWER);
        var start = runtime.startReviewerAgent(
                reviewer.request().requestId(),
                redriven,
                "adversarial-reviewer-prompt:v1",
                "immutable-git-object-reader:v1");
        assertThat(start.run().runId()).isEqualTo(reviewer.start().run().runId());
        assertThat(redriven.generation())
                .isEqualTo(reviewer.claim().generation() + 1);
    }

    @Test
    void expiredActivatedReviewerQuarantinesWithoutRedrive()
    {
        ReviewerClaim reviewer = prepareReviewerClaim("activated-expiry");
        insertReviewerProcessAttempt(
                reviewer, "activated-reviewer", "ACTIVATED");
        jdbc.update("""
                UPDATE flow_runtime_agent_run
                SET state = 'RUNNING', started_at = ?
                WHERE run_id = ? AND state = 'QUEUED'
                """, NOW.toEpochMilli(), reviewer.start().run().runId());
        expireRuntime();

        assertThat(runtime.recoverExpiredClaim(
                reviewer.claim().operationId(),
                reviewer.claim().generation())).isFalse();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.operation(reviewer.claim().operationId())
                .orElseThrow().state()).isEqualTo(OperationState.FAILED);
        assertThatThrownBy(() -> runtime.redriveRetryable(
                reviewer.claim().operationId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-launched retry");
        assertThat(runtime.claimNext("other-worker", TTL)).isEmpty();
    }

    private void assertReviewerCompletionReplacesReconciliation(
            boolean waiting)
    {
        ReviewReady ready = prepareCleanReview(
                "stale-reconciliation-" + waiting);
        ParkedReview parked = parkForReviewer(
                ready, TerminalOutcome.COMPLETED);
        String stale = runtime.registerFinalRed(
                "reviewer-blocked-" + waiting,
                task.taskId(),
                pr.prId(),
                publishedHead,
                "typed-final-red")
                .reconciliationOperationId();
        if (waiting) {
            jdbc.update("""
                    UPDATE flow_runtime_operation
                    SET state = 'WAITING', result_ref = 'REVIEWER_BLOCKED'
                    WHERE operation_id = ? AND state = 'READY'
                    """, stale);
            jdbc.update("""
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'DONE'
                    WHERE operation_id = ? AND delivery_state = 'AVAILABLE'
                    """, stale);
        }

        Claim reviewerClaim = claim(OperationKind.RUN_REVIEWER);
        var reviewerStart = ready.review().beginReviewer(
                parked.request().requestId(), reviewerClaim);
        var reviewerSupervisor = new InProcessReviewerAgentSupervisor(runtime);
        var reviewerHandle = ready.review().launchReviewer(
                reviewerSupervisor,
                reviewerStart,
                reviewerClaim,
                capability -> new InProcessReviewerAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "opaque reviewer",
                                null));
        AgentResult result = ready.review().awaitReviewer(
                reviewerSupervisor, reviewerHandle, TTL);

        assertThat(runtime.operation(stale).orElseThrow().state())
                .isEqualTo(OperationState.CANCELED);
        assertThat(runtime.operation(stale).orElseThrow().resultRef())
                .isEqualTo("REVIEWER_RESULT_ADVANCED");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = runtime.selectNext(reconciliation).orElseThrow();
        assertThat(selected.ownerKind()).isEqualTo("AGENT_RUN");
        assertThat(selected.ownerId()).isEqualTo(result.runId());
    }

    private ReviewerResultReady prepareReviewerResult(String suffix)
    {
        return prepareReviewerResult(suffix, "failure-1");
    }

    private ReviewerResultReady prepareReviewerResult(
            String suffix, String failureRevision)
    {
        ReviewReady ready = prepareCleanReview(suffix, failureRevision);
        return prepareReviewerResult(ready, suffix);
    }

    private ReviewerResultReady prepareReviewerResult(
            ReviewReady ready, String suffix)
    {
        ParkedReview parked = parkForReviewer(
                ready, TerminalOutcome.COMPLETED);
        Claim reviewerClaim = claim(OperationKind.RUN_REVIEWER);
        var start = ready.review().beginReviewer(
                parked.request().requestId(), reviewerClaim);
        var reviewerSupervisor = new InProcessReviewerAgentSupervisor(runtime);
        var handle = ready.review().launchReviewer(
                reviewerSupervisor,
                start,
                reviewerClaim,
                capability -> new InProcessReviewerAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "opaque reviewer " + suffix,
                                null));
        ready.review().awaitReviewer(reviewerSupervisor, handle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNext(reconciliation).orElseThrow().ownerKind())
                .isEqualTo("AGENT_RUN");
        Claim continuation = claim(OperationKind.RUN_TASK_TURN);
        return new ReviewerResultReady(
                ready,
                continuation,
                ready.review().beginReviewerResultContinuation(
                        continuation, TTL));
    }

    @Test
    void exactManualAuthorizationBuildsOneClaimablePlanAndBeginReplays()
    {
        CompletedReady ready = openReadyGate("manual-authorization");
        GateRevision revision = ready.revision();

        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(),
                revision.revision(),
                revision.subjectDigest(),
                revision.actionDigest(),
                "authorize-manual-1");
        assertThat(userGates.authorizeCiUpdate(
                revision.gateId(),
                revision.revision(),
                revision.subjectDigest(),
                revision.actionDigest(),
                "authorize-manual-1")).isEqualTo(authorized);
        assertThat(githubEffects.steps(authorized.planId())).singleElement()
                .satisfies(step -> {
                    assertThat(step.ordinal()).isEqualTo(1);
                    assertThat(step.forcePush()).isFalse();
                });
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN, GateState.AUTHORIZED);
        assertThat(jdbc.queryForMap(
                """
                SELECT actor_type, actor_id
                FROM flow_user_gate_transition
                WHERE gate_id = ? AND reason_code = 'MANUAL_AUTHORIZATION'
                """,
                revision.gateId()))
                .containsEntry("actor_type", "USER")
                .containsEntry("actor_id", "LOCAL_DESKTOP_USER");

        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        assertThat(userGates.beginCiUpdateEffect(claim))
                .isEqualTo(activation);
        assertThat(activation.operationId())
                .isEqualTo(authorized.operationId());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_step", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void oneShotConsentAuthorizesOnlyTheNewExactGateGraph()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-one-shot");
        assertThat(userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-one-shot")).isEqualTo(consent);

        CompletedReady ready = openReadyGate("automatic-authorization");
        GateRevision revision = ready.revision();
        String authorizationId = jdbc.queryForObject(
                "SELECT authorization_id FROM flow_user_gate_authorization",
                String.class);
        var authorization = userGates.authorization(
                authorizationId).orElseThrow();
        assertThat(authorization.authority())
                .isEqualTo("CI_UPDATE_CONSENT");
        assertThat(authorization.actorId())
                .isEqualTo("USER_GATES_CI_CONSENT");
        assertThat(authorization.consentId()).isEqualTo(consent.consentId());
        assertThat(authorization.consentRevision())
                .isEqualTo(consent.revision());
        assertThat(authorization.consentDigest())
                .isEqualTo(consent.revisionDigest());
        assertThat(count("flow_user_gate_authorization", "consent_id IS NOT NULL"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_attempt", "1 = 1"))
                .isZero();
        assertThat(count("flow_github_external_effect_probe", "1 = 1"))
                .isZero();
        assertThat(count("flow_github_external_effect_receipt", "1 = 1"))
                .isZero();
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.reasonCode())
                .containsExactly("READY", "CI_UPDATE_CONSENT_AUTHORIZATION");
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "later-manual-click"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("AUTHORIZATION_AUTHORITY_CONFLICT");

        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        assertThat(activation.operationId())
                .isEqualTo(authorization.operationId());
    }

    @Test
    void consentDoesNotRetroactivelyAuthorizeAnExistingOpenGate()
    {
        CompletedReady ready = openReadyGate("consent-not-retroactive");
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-after-open");

        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(userGates.transitions(ready.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN);
    }

    @Test
    void revokedConsentStopsOnlyAnUnbegunEffect()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-before-revoke");
        CompletedReady ready = openReadyGate("consent-revoked-before-begin");
        var authorization = userGates.authorization(jdbc.queryForObject(
                "SELECT authorization_id FROM flow_user_gate_authorization",
                String.class)).orElseThrow();
        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(), "revoke-before-begin");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(claim))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("CI_UPDATE_CONSENT_REVOKED");
        assertThat(runtime.operation(authorization.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(ready.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN, GateState.AUTHORIZED, GateState.STALE);
    }

    @Test
    void expiredConsentStopsBeforeBegin()
    {
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofMinutes(10)),
                "grant-before-expiry");
        CompletedReady expiring = openReadyGate("consent-expires-before-begin");
        var expiringAuthorization = userGates.authorization(
                jdbc.queryForObject(
                        "SELECT authorization_id "
                                + "FROM flow_user_gate_authorization",
                        String.class)).orElseThrow();
        advancePublicationClock(Duration.ofMinutes(11));
        Claim expiredClaim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(expiredClaim))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("CI_UPDATE_CONSENT_EXPIRED");
        assertThat(runtime.operation(expiringAuthorization.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.CANCELED);
        assertThat(userGates.transitions(expiring.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN, GateState.AUTHORIZED, GateState.STALE);
    }

    @Test
    void executingConsentEffectIsFrozenAcrossLaterRevocation()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-before-execution");
        openReadyGate("consent-revoked-after-begin");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);

        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(),
                "revoke-after-begin");

        assertThat(userGates.beginCiUpdateEffect(claim))
                .isEqualTo(activation);
        assertThat(runtime.operation(activation.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
    }

    @Test
    void begunConsentRemainsFrozenThroughNeverStartedClaimRecovery()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofMinutes(10)),
                "grant-before-begun-recovery");
        openReadyGate("consent-begun-recovery");
        Claim first = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        userGates.beginCiUpdateEffect(first);
        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(),
                "revoke-begun-recovery");
        advancePublicationClock(TTL.plusSeconds(1));

        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        Claim redelivery = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();

        assertThat(userGates.beginCiUpdateEffect(redelivery).mutationAllowed())
                .isTrue();
        advancePublicationClock(TTL.plusSeconds(1));
        userGates.recoverExpiredCiUpdateEffect(
                redelivery.operationId(), redelivery.generation());
        Claim third = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThat(userGates.beginCiUpdateEffect(third).mutationAllowed())
                .isTrue();
    }

    @Test
    void unbegunConsentRecoveryStillHonorsRevocation()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-before-unbegun-recovery");
        openReadyGate("consent-unbegun-recovery");
        Claim first = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        userGates.revokeCiUpdateConsent(
                consent.consentId(), consent.revision(),
                "revoke-unbegun-recovery");
        advancePublicationClock(TTL.plusSeconds(1));

        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        Claim redelivery = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();

        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(redelivery))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("CI_UPDATE_CONSENT_REVOKED");
    }

    @Test
    void consentGrantIsBoundedAndConcurrentReplayCreatesOneRevision()
            throws Exception
    {
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(24)).plusMillis(1),
                "too-long-consent"))
                .isInstanceOf(IllegalArgumentException.class);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CiUpdateConsentRevision> first =
                new AtomicReference<>();
        AtomicReference<CiUpdateConsentRevision> second =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable grant = () -> {
            awaitLatch(start);
            try {
                CiUpdateConsentRevision value =
                        userGates.grantCiUpdateConsent(
                        task.taskId(), NOW.plus(Duration.ofHours(1)),
                        "concurrent-consent-grant");
                if (!first.compareAndSet(null, value)) {
                    second.set(value);
                }
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        };
        Thread left = new Thread(grant, "left-consent-grant");
        Thread right = new Thread(grant, "right-consent-grant");
        left.start();
        right.start();
        start.countDown();
        left.join(30_000);
        right.join(30_000);

        assertThat(failure.get()).isNull();
        assertThat(first.get()).isEqualTo(second.get());
        assertThat(count(
                "flow_user_gate_ci_consent_revision", "1 = 1"))
                .isEqualTo(1);
        assertThat(count(
                "flow_user_gate_ci_consent_current", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void consentCommandReplayIsExactAndActiveGrantCannotBeReplaced()
    {
        var granted = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "exact-grant-key");
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(2)),
                "exact-grant-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                "different-task", NOW.plus(Duration.ofHours(1)),
                "exact-grant-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
        assertThatThrownBy(() -> userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "second-active-grant"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_ALREADY_ACTIVE");
        assertThat(count(
                "flow_user_gate_ci_consent_revision", "1 = 1"))
                .isEqualTo(1);

        var revoked = userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision(),
                "exact-revoke-key");
        assertThat(userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "exact-grant-key")).isEqualTo(granted);
        assertThat(userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision(),
                "exact-revoke-key")).isEqualTo(revoked);
        assertThat(userGates.currentCiUpdateConsent(task.taskId()))
                .contains(revoked);
        assertThatThrownBy(() -> userGates.revokeCiUpdateConsent(
                "different-consent", granted.revision(),
                "exact-revoke-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
        assertThatThrownBy(() -> userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision() + 1,
                "exact-revoke-key"))
                .isInstanceOf(UserGates.ConsentRejectedException.class)
                .hasMessage("CONSENT_REPLAY_CONFLICT");
    }

    @Test
    void revokedConsentBeforeGateCreationLeavesTheNewGateManual()
    {
        var granted = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "grant-then-revoke");
        userGates.revokeCiUpdateConsent(
                granted.consentId(), granted.revision(),
                "revoke-before-open");

        CompletedReady ready = openReadyGate("revoked-consent-new-gate");

        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(userGates.transitions(ready.revision().gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN);
    }

    @Test
    void automaticAuthorizationRollsBackWithStoppedFinalizationAndRetries()
    {
        userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "rollback-consent");
        ReviewerResultReady ready = prepareReviewerResult(
                "automatic-authorization-rollback");
        String finalContent = "opaque automatic rollback";
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
                                            finalContent,
                                            null);
                        });
        jdbc.execute("""
                CREATE TRIGGER fail_automatic_effect_step
                BEFORE INSERT ON flow_github_external_effect_step
                BEGIN
                    SELECT RAISE(ABORT, 'forced automatic effect failure');
                END
                """);
        assertThatThrownBy(() -> ready.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        handle,
                        TTL)).isInstanceOf(RuntimeException.class);
        assertThat(runtime.resultForRun(
                ready.binding().run().runId())).isEmpty();
        assertThat(userGates.gate(pr.prId())).isEmpty();
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isZero();

        jdbc.execute("DROP TRIGGER fail_automatic_effect_step");
        var prepared = userGates.prepareFinalization(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence());
        AgentResult result = userGates.finalizeReady(
                ready.binding().run().runId(),
                ready.claim(),
                ready.binding().fence(),
                TerminalOutcome.COMPLETED,
                finalContent,
                null,
                prepared);

        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(count("flow_user_gate_authorization", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void consentSchemaAndGraphRejectMixedScopeOrMissingAuthority()
    {
        var consent = userGates.grantCiUpdateConsent(
                task.taskId(), NOW.plus(Duration.ofHours(1)),
                "schema-consent");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE flow_user_gate_ci_consent_revision "
                        + "SET head_repository_name = 'wrong' "
                        + "WHERE consent_id = ? AND revision = ?",
                consent.consentId(), consent.revision()))
                .isInstanceOf(RuntimeException.class);
        openReadyGate("corrupt-consent-graph");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE flow_user_gate_authorization "
                        + "SET consent_id = NULL, consent_revision = NULL, "
                        + "consent_digest = NULL "
                        + "WHERE authority = 'CI_UPDATE_CONSENT'"))
                .isInstanceOf(RuntimeException.class);
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        jdbc.execute((ConnectionCallback<Void>)
                connection -> {
                    connection.createStatement().execute(
                            "PRAGMA foreign_keys=OFF");
                    try (var statement = connection.prepareStatement(
                            "UPDATE flow_user_gate_ci_consent_revision "
                                    + "SET head_repository_name = 'corrupt' "
                                    + "WHERE consent_id = ? "
                                    + "AND revision = ?")) {
                        statement.setString(1, consent.consentId());
                        statement.setLong(2, consent.revision());
                        statement.executeUpdate();
                    }
                    return null;
                });
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(claim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CI_UPDATE consent digest is inconsistent");
    }

    @Test
    void concurrentAuthorizationAndBeginDeliveriesCreateOneExactGraph()
            throws Exception
    {
        CompletedReady ready = openReadyGate("concurrent-authorization");
        GateRevision revision = ready.revision();
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AuthorizedCiUpdate> first = new AtomicReference<>();
        AtomicReference<AuthorizedCiUpdate> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread firstThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                first.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "concurrent-key"));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "first-authorization");
        Thread secondThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                second.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "concurrent-key"));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "second-authorization");
        firstThread.start();
        secondThread.start();
        assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        firstThread.join(30_000);
        secondThread.join(30_000);
        assertThat(firstThread.isAlive()).isFalse();
        assertThat(secondThread.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(first.get()).isEqualTo(second.get());
        assertThat(count("flow_user_gate_authorization", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isEqualTo(1);

        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        CountDownLatch beginStart = new CountDownLatch(1);
        AtomicReference<CiUpdateEffectActivation> firstBegin =
                new AtomicReference<>();
        AtomicReference<CiUpdateEffectActivation> secondBegin =
                new AtomicReference<>();
        Thread firstBeginThread = new Thread(() -> {
            awaitLatch(beginStart);
            try {
                firstBegin.set(userGates.beginCiUpdateEffect(claim));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "first-begin");
        Thread secondBeginThread = new Thread(() -> {
            awaitLatch(beginStart);
            try {
                secondBegin.set(userGates.beginCiUpdateEffect(claim));
            }
            catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "second-begin");
        firstBeginThread.start();
        secondBeginThread.start();
        beginStart.countDown();
        firstBeginThread.join(30_000);
        secondBeginThread.join(30_000);
        assertThat(failure.get()).isNull();
        assertThat(firstBegin.get()).isNotNull();
        assertThat(firstBegin.get()).isEqualTo(secondBegin.get());
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING);
    }

    @Test
    void stableBeginDriftCancelsPublicationAndRearmsParkedReconciliation()
    {
        CompletedReady ready = openReadyGate("publish-stale-rearm");
        var registered = runtime.registerFinalRed(
                "later-round",
                task.taskId(),
                pr.prId(),
                publishedHead,
                "later-payload");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(),
                revision.revision(),
                revision.subjectDigest(),
                revision.actionDigest(),
                "authorize-stale-1");
        Claim publish = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        assertThat(runtime.selectNext(reconciliation)).isEmpty();
        assertThat(runtime.operation(reconciliation.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.WAITING);
        runtime.advanceRemoteHead(
                pr.prId(), publishedHead, publishedHead + "-advanced");
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(publish))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("REMOTE_SUBJECT_STALE");
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(publish))
                .isInstanceOf(UserGates.DurableStaleEffectException.class)
                .hasMessage("REMOTE_SUBJECT_STALE");
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(runtime.operation(reconciliation.operationId())
                .orElseThrow().state()).isEqualTo(OperationState.READY);
        Claim resumed = claim(OperationKind.RECONCILE_TASK);
        assertThat(resumed.operationId())
                .isEqualTo(reconciliation.operationId());
        assertThat(registered.reconciliationOperationId())
                .isEqualTo(reconciliation.operationId());
    }

    @Test
    void authorizationRejectsConflictsAndLateFailureRollsBackEveryOwner()
    {
        CompletedReady ready = openReadyGate("authorization-rollback");
        GateRevision revision = ready.revision();
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                "wrong-subject-digest", revision.actionDigest(),
                "wrong-digest-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("DISPLAYED_GATE_DIGEST_CHANGED");

        jdbc.execute("""
                CREATE TRIGGER fail_effect_step
                BEFORE INSERT ON flow_github_external_effect_step
                BEGIN
                    SELECT RAISE(ABORT, 'forced effect step failure');
                END
                """);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "rollback-key"))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_github_external_effect_plan", "1 = 1"))
                .isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isZero();
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN);

        jdbc.execute("DROP TRIGGER fail_effect_step");
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "rollback-key");
        assertThat(authorized.prSequence()).isEqualTo(1);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "different-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(count("flow_user_gate_authorization", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void authorizationStaleIsTerminalEvenIfInspectionLaterFails()
            throws Exception
    {
        CompletedReady ready = openReadyGate("authorization-stale-terminal");
        GateRevision revision = ready.revision();
        String moved = runtime.currentChangeSet(task.taskId())
                .orElseThrow().headSha();
        runtime.advanceRemoteHead(pr.prId(), publishedHead, moved);
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "stale-terminal-key"))
                .isInstanceOf(UserGates.AuthorizationRejectedException.class)
                .hasMessage("REMOTE_SUBJECT_STALE");
        runtime.advanceRemoteHead(pr.prId(), moved, publishedHead);
        Path git = repositoryRoot.resolve(".git");
        Path hiddenGit = repositoryRoot.resolve(".git-hidden");
        Files.move(git, hiddenGit);
        try {
            assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                    revision.gateId(), revision.revision(),
                    revision.subjectDigest(), revision.actionDigest(),
                    "stale-terminal-key"))
                    .isInstanceOf(
                            UserGates.AuthorizationRejectedException.class)
                    .hasMessage("REMOTE_SUBJECT_STALE");
        }
        finally {
            Files.move(hiddenGit, git);
        }
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN, GateState.STALE);
        assertThat(jdbc.queryForMap(
                """
                SELECT actor_type, actor_id
                FROM flow_user_gate_transition
                WHERE gate_id = ? AND reason_code = 'AUTHORIZATION_STALE'
                """,
                revision.gateId()))
                .containsEntry("actor_type", "PROGRAM")
                .containsEntry("actor_id", "USER_GATES_AUTHORIZATION");
        assertThat(count("flow_user_gate_authorization", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                .isZero();
        runtime.advanceRemoteHead(pr.prId(), publishedHead, moved);
        publishedHead = moved;
        CompletedReady replacement = openReadyGate(
                "authorization-after-stale", "failure-2");
        assertThat(replacement.revision().revision()).isEqualTo(2);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.STALE,
                        GateState.OPEN);
    }

    @Test
    void neverStartedPublishRecoveryIsOwnerSpecificAndIdempotent()
    {
        CompletedReady ready = openReadyGate("publish-recovery");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "recovery-key");
        Claim first = runtime.claimNextPublish(
                "publisher", Duration.ofSeconds(1)).orElseThrow();
        advancePublicationClock(Duration.ofSeconds(2));
        assertThatThrownBy(() -> runtime.recoverExpiredClaim(
                first.operationId(), first.generation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner-specific");
        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);

        Claim second = runtime.claimNextPublish(
                "publisher-2", Duration.ofSeconds(1)).orElseThrow();
        userGates.beginCiUpdateEffect(second);
        advancePublicationClock(Duration.ofSeconds(2));
        userGates.recoverExpiredCiUpdateEffect(
                second.operationId(), second.generation());
        userGates.recoverExpiredCiUpdateEffect(
                second.operationId(), second.generation());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING,
                        GateState.AUTHORIZED);
    }

    @Test
    void transientBeginFailureLeavesTheExactClaimRetryable()
    {
        CompletedReady ready = openReadyGate("publish-begin-rollback");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "begin-rollback-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        jdbc.execute("""
                CREATE TRIGGER fail_effect_begin
                BEFORE INSERT ON flow_user_gate_transition
                WHEN NEW.reason_code = 'EFFECT_BEGIN'
                BEGIN
                    SELECT RAISE(ABORT, 'forced effect begin failure');
                END
                """);
        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(claim))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime.assertPublishClaim(claim).operationId())
                .isEqualTo(authorized.operationId());
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(GateState.OPEN, GateState.AUTHORIZED);

        jdbc.execute("DROP TRIGGER fail_effect_begin");
        assertThat(userGates.beginCiUpdateEffect(claim).operationId())
                .isEqualTo(authorized.operationId());
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN,
                        GateState.AUTHORIZED,
                        GateState.EXECUTING);
    }

    @Test
    void publicationBarrierBlocksReconciliationClaim()
    {
        CompletedReady ready = openReadyGate("publish-barrier");
        GateRevision revision = ready.revision();
        userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "barrier-key");
        runtime.registerFinalRed(
                "barrier-round", task.taskId(), pr.prId(), publishedHead,
                "barrier-payload");
        assertThat(runtime.claimNext("writer", TTL)).isEmpty();
        Task current = runtime.task(task.taskId()).orElseThrow();
        assertThatThrownBy(() -> runtime.transitionTask(
                task.taskId(),
                current.currentLifecycleRevisionId(),
                TaskStatus.WAITING_USER,
                "USER_PAUSED",
                "pause"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("publication is live");
        assertThatThrownBy(() -> runtime.transitionTask(
                task.taskId(),
                current.currentLifecycleRevisionId(),
                TaskStatus.CANCELED,
                "USER_CANCELED",
                "cancel"))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("publication is live");
        assertThat(count("flow_runtime_operation", "kind = 'PUBLISH' "
                + "AND state = 'READY'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentPublicationReservationAndWorkSelectionCannotBothWin()
            throws Exception
    {
        CompletedReady ready = openReadyGate("publish-selection-race");
        runtime.registerFinalRed(
                "publish-selection-later",
                task.taskId(),
                pr.prId(),
                publishedHead,
                "publish-selection-payload");
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        GateRevision revision = ready.revision();
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AuthorizedCiUpdate> authorized =
                new AtomicReference<>();
        AtomicReference<Optional<Operation>> selected =
                new AtomicReference<>();
        AtomicReference<Throwable> authorizationFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> selectionFailure =
                new AtomicReference<>();
        Thread authorizationThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                authorized.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "selection-race-key"));
            }
            catch (Throwable thrown) {
                authorizationFailure.set(thrown);
            }
        }, "publication-reservation");
        Thread selectionThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                selected.set(runtime.selectNext(reconciliation));
            }
            catch (Throwable thrown) {
                selectionFailure.set(thrown);
            }
        }, "work-selection");
        authorizationThread.start();
        selectionThread.start();
        assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        authorizationThread.join(30_000);
        selectionThread.join(30_000);
        assertThat(selectionFailure.get()).isNull();
        if (authorized.get() != null) {
            assertThat(authorizationFailure.get()).isNull();
            assertThat(selected.get()).isEmpty();
            assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                    .isEqualTo(1);
            assertThat(runtime.task(task.taskId()).orElseThrow()
                    .selectedWriterOperationId()).isNull();
        }
        else {
            assertThat(authorizationFailure.get()).isNotNull();
            assertThat(selected.get()).isPresent();
            assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                    .isZero();
            assertThat(runtime.task(task.taskId()).orElseThrow()
                    .selectedWriterOperationId()).isNotNull();
        }
    }

    @Test
    void concurrentPublicationReservationAndTaskTerminationCannotBothWin()
            throws Exception
    {
        CompletedReady ready = openReadyGate("publish-lifecycle-race");
        GateRevision revision = ready.revision();
        Task current = runtime.task(task.taskId()).orElseThrow();
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<AuthorizedCiUpdate> authorized =
                new AtomicReference<>();
        AtomicReference<Throwable> authorizationFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> transitionFailure =
                new AtomicReference<>();
        Thread authorizationThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                authorized.set(userGates.authorizeCiUpdate(
                        revision.gateId(), revision.revision(),
                        revision.subjectDigest(), revision.actionDigest(),
                        "lifecycle-race-key"));
            }
            catch (Throwable thrown) {
                authorizationFailure.set(thrown);
            }
        }, "publication-lifecycle-reservation");
        Thread transitionThread = new Thread(() -> {
            callersReady.countDown();
            awaitLatch(start);
            try {
                runtime.transitionTask(
                        task.taskId(),
                        current.currentLifecycleRevisionId(),
                        TaskStatus.CANCELED,
                        "USER_CANCELED",
                        "race-cancel");
            }
            catch (Throwable thrown) {
                transitionFailure.set(thrown);
            }
        }, "task-termination");
        authorizationThread.start();
        transitionThread.start();
        assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        authorizationThread.join(30_000);
        transitionThread.join(30_000);
        if (authorized.get() != null) {
            assertThat(authorizationFailure.get()).isNull();
            assertThat(transitionFailure.get()).isNotNull();
            assertThat(runtime.task(task.taskId()).orElseThrow().status())
                    .isEqualTo(TaskStatus.ACTIVE);
            assertThat(runtime.operation(authorized.get().operationId())
                    .orElseThrow().state()).isEqualTo(OperationState.READY);
        }
        else {
            assertThat(authorizationFailure.get()).isNotNull();
            assertThat(transitionFailure.get()).isNull();
            assertThat(runtime.task(task.taskId()).orElseThrow().status())
                    .isEqualTo(TaskStatus.CANCELED);
            assertThat(count("flow_runtime_operation", "kind = 'PUBLISH'"))
                    .isZero();
        }
    }

    @Test
    void effectPlanReaderRejectsDigestCorruptionAndSchemaRejectsPayloadDrift()
    {
        CompletedReady ready = openReadyGate("plan-corruption");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "plan-corruption-key");
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE flow_github_external_effect_step
                SET proposed_head = 'different-head' WHERE plan_id = ?
                """,
                authorized.planId())).isInstanceOf(RuntimeException.class);
        jdbc.update(
                """
                UPDATE flow_github_external_effect_step
                SET precondition_digest = 'corrupt' WHERE plan_id = ?
                """,
                authorized.planId());
        assertThatThrownBy(() -> userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "plan-corruption-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest graph");
    }

    @Test
    void appliedProviderProbeConsumesTheExactPlanAndReplays()
    {
        CompletedReady ready = openReadyGate("publish-applied");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-applied-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        githubEffects.recordObservation(
                claim,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        var activated = githubEffects.activateAttempt(
                claim, authorized.planId(), NOW);
        assertThat(count("flow_github_external_effect_attempt", "1 = 1"))
                .isEqualTo(1);
        runtime.consumePublishExecutionHandle(
                activated.executionHandle(), claim,
                activated.attempt().attemptId(),
                activated.attempt().executionTokenDigest());

        var applied = observation(
                runtime, claim, activation, activated.attempt(),
                ProbeOutcome.APPLIED);
        var receipt = userGates.applyCiUpdateObservation(
                claim, activated.executionHandle(), activated.attempt(),
                applied).orElseThrow();
        assertThat(userGates.applyCiUpdateObservation(
                claim, activated.executionHandle(), activated.attempt(),
                applied))
                .contains(receipt);
        assertThatThrownBy(() -> userGates.applyCiUpdateObservation(
                claim, activated.executionHandle(), activated.attempt(),
                observation(
                        runtime, claim, activation, activated.attempt(),
                        ProbeOutcome.UNKNOWN)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(activation.proposedHead());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(userGates.transitions(revision.gateId()))
                .extracting(transition -> transition.toState())
                .containsExactly(
                        GateState.OPEN, GateState.AUTHORIZED,
                        GateState.EXECUTING, GateState.CONSUMED);
        assertThat(githubEffects.exactReceipt(authorized.planId()))
                .contains(receipt);
    }

    @Test
    void receiptOwnedObservationWatchIsAtomicWithAppliedSettlement()
    {
        CompletedReady ready = openReadyGate("receipt-watch-rollback");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "receipt-watch-rollback-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        var applied = observation(
                runtime, claim, activation, null, ProbeOutcome.APPLIED);
        int probesBefore = count(
                "flow_github_external_effect_probe", "1 = 1");
        jdbc.execute("""
                CREATE TRIGGER reject_observation_watch
                BEFORE INSERT ON flow_runtime_operation
                WHEN NEW.kind = 'OBSERVE_CI'
                BEGIN
                    SELECT RAISE(ABORT, 'watch insert rejected');
                END
                """);

        assertThatThrownBy(() -> userGates.applyCiUpdateObservation(
                claim, null, null, applied))
                .isInstanceOf(RuntimeException.class);
        assertThat(githubEffects.exactReceipt(authorized.planId())).isEmpty();
        assertThat(count("flow_github_external_effect_probe", "1 = 1"))
                .isEqualTo(probesBefore);
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(activation.expectedRemoteHead());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState()).isEqualTo(GateState.EXECUTING);
        assertThat(count("flow_runtime_operation", "kind = 'OBSERVE_CI'"))
                .isZero();

        jdbc.execute("DROP TRIGGER reject_observation_watch");
        var receipt = userGates.applyCiUpdateObservation(
                claim, null, null, applied).orElseThrow();
        assertThat(githubEffects.exactReceipt(authorized.planId()))
                .contains(receipt);
        assertThat(runtime.pullRequest(pr.prId()).orElseThrow()
                .currentRemoteHead()).isEqualTo(activation.proposedHead());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(count("flow_runtime_operation", "kind = 'OBSERVE_CI'"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_dispatch_ticket",
                "operation_id IN (SELECT operation_id FROM "
                        + "flow_runtime_operation WHERE kind = 'OBSERVE_CI')"))
                .isEqualTo(1);
    }

    @Test
    void realExecutorCommitsAttemptBeforeItsOnlyProviderCall()
    {
        CompletedReady ready = openReadyGate("executor-attempt-boundary");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "executor-attempt-boundary-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var step = githubEffects.steps(authorized.planId()).getFirst();
        AtomicInteger pushes = new AtomicInteger();

        var receipt = executeApplied(
                runtime,
                userGates,
                githubEffects,
                claim,
                step,
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                () -> assertThat(count(
                        "flow_github_external_effect_attempt",
                        "operation_id = '" + claim.operationId() + "'"))
                        .isEqualTo(1),
                pushes).orElseThrow();

        assertThat(pushes.get()).isEqualTo(1);
        assertThat(count(
                "flow_github_external_effect_receipt", "1 = 1"))
                .isEqualTo(1);

        restart();
        assertThat(executeApplied(
                runtime,
                userGates,
                githubEffects,
                claim,
                githubEffects.steps(authorized.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                () -> {
                    throw new AssertionError(
                            "terminal replay must not call the provider");
                },
                pushes)).contains(receipt);
        assertThat(pushes.get()).isEqualTo(1);
        for (Claim conflicting : List.of(
                new Claim(
                        claim.operationId(), claim.taskId(),
                        OperationKind.RECONCILE_TASK, claim.generation(),
                        claim.claimToken(), claim.workerId(),
                        claim.expiresAt()),
                new Claim(
                        claim.operationId(), claim.taskId(), claim.kind(),
                        claim.generation(), "wrong-token", claim.workerId(),
                        claim.expiresAt()),
                new Claim(
                        claim.operationId(), claim.taskId(), claim.kind(),
                        claim.generation() + 1, claim.claimToken(),
                        claim.workerId(), claim.expiresAt()),
                new Claim(
                        claim.operationId(), "wrong-task", claim.kind(),
                        claim.generation(), claim.claimToken(),
                        claim.workerId(), claim.expiresAt()),
                new Claim(
                        claim.operationId(), claim.taskId(), claim.kind(),
                        claim.generation(), claim.claimToken(),
                        "wrong-worker", claim.expiresAt()))) {
            assertThatThrownBy(() -> executeApplied(
                    runtime,
                    userGates,
                    githubEffects,
                    conflicting,
                    githubEffects.steps(authorized.planId()).getFirst(),
                    Clock.fixed(runtimeNow, ZoneOffset.UTC),
                    () -> {
                        throw new AssertionError(
                                "conflicting replay called the provider");
                    },
                    pushes)).isInstanceOf(RuntimeException.class);
        }
        assertThat(pushes.get()).isEqualTo(1);
    }

    @Test
    void receiptWatchAcceptsExactGreenProviderBatch()
    {
        Claim greenClaim = observationClaim("observe-green");
        CiRound green = executeCiObservation(
                runtime, coordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        assertThat(green.sourceObservationOperationId())
                .isEqualTo(greenClaim.operationId());
        assertThat(green.sourceReceiptId()).isNotBlank();
        assertThat(runtime.pendingWork(task.taskId()))
                .noneMatch(work -> work.kind() == PendingKind.FINAL_RED
                        && work.externalKey().equals(green.roundId()));
        assertThat(runtime.operation(greenClaim.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(jdbc.queryForObject(
                "SELECT not_before FROM flow_runtime_dispatch_ticket "
                        + "WHERE operation_id = ?",
                Long.class, greenClaim.operationId()))
                .isEqualTo(runtimeNow.plus(Duration.ofMinutes(5))
                        .toEpochMilli());
    }

    @Test
    void exactGreenRunsOneIsolatedLearnerAndAcceptedSaveWinsFailedReturn()
    {
        Claim greenClaim = observationClaim("learn-green");
        CiRound green = executeCiObservation(
                runtime, coordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var subject = coordinator.learningSubject(start.run().inputRef())
                .orElseThrow();
        String failedLogRef = subject.failedLogRefs().getFirst();
        assertThat(start.session().role()).isEqualTo(AgentRole.CI_LEARNER);
        assertThat(runtime.session(task.taskId(), AgentRole.CI_FIXER)
                .orElseThrow().sessionId())
                .isNotEqualTo(start.session().sessionId());
        assertThat(count("flow_runtime_writer_lease", "1 = 1")).isZero();
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var handle = supervisor.launch(
                start, learning, coordinator, capability -> {
                    assertThat(capability.readCiRepairEvidence())
                            .contains("greenRound=" + green.roundId())
                            .contains("repairResult=");
                    assertThat(capability.readCiLog(
                            failedLogRef, 0, 1_024))
                            .contains("failure");
                    assertThatThrownBy(() -> capability.readCiLog(
                            "unrelated-log", 0, 1_024))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> capability.readCiLog(
                            failedLogRef, 0, 32_769))
                            .isInstanceOf(IllegalArgumentException.class);
                    capability.saveCiLesson(
                            "Fix the exact CI failure",
                            "The bound repair resolved the failed check.");
                    assertThatThrownBy(capability::readCiRepairEvidence)
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    assertThatThrownBy(() -> capability.saveCiLesson(
                            "Fix the exact CI failure",
                            "The bound repair resolved the failed check."))
                            .isInstanceOf(
                                    FlowRuntime.StaleCapabilityException.class);
                    return new InProcessCiLearningAgentSupervisor
                            .AgentCompletion(
                                    TerminalOutcome.FAILED, null,
                                    "OPAQUE_LEARNER_FAILED_AFTER_SAVE");
                });

        AgentResult result = supervisor.awaitAndFinish(handle, TTL);
        var completion = coordinator.learningCompletion(
                learning.operationId()).orElseThrow();

        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.FAILED);
        assertThat(completion.state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
        assertThat(coordinator.lesson(completion.lessonId()).orElseThrow())
                .satisfies(lesson -> {
                    assertThat(lesson.title())
                            .isEqualTo("Fix the exact CI failure");
                    assertThat(lesson.markdown())
                            .isEqualTo(
                                    "The bound repair resolved the failed check.");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void exactGreenWithoutSaveIsMissedAndNeverInventsALesson()
    {
        Claim greenClaim = observationClaim("learn-missed");
        CiRound green = executeCiObservation(
                runtime, coordinator, greenClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var handle = supervisor.launch(
                start, learning, coordinator,
                capability -> new InProcessCiLearningAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "opaque prose is not a lesson", null));

        supervisor.awaitAndFinish(handle, TTL);
        var completion = coordinator.learningCompletion(
                learning.operationId()).orElseThrow();

        assertThat(completion.state())
                .isEqualTo(LearningCompletionState.MISSED);
        assertThat(completion.lessonId()).isNull();
        assertThat(count("flow_ci_lesson", "1 = 1")).isZero();
    }

    @Test
    void emptyPolicyGreenCommitsWithoutLearningUntilNonemptySuccessor()
    {
        Claim first = observationClaim("learn-empty-policy");
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:empty",
                "github-check-policy-digest:empty",
                PolicyResolution.RESOLVED, null, List.of(),
                List.of("SUCCESS"));

        CiRound emptyGreen = executeCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(emptyGreen.state()).isEqualTo(RoundState.GREEN);
        assertThat(emptyGreen.checkObservationIds()).isEmpty();
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isZero();

        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:nonempty",
                "github-check-policy-digest:nonempty",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound nonemptyGreen = executeCiObservation(
                runtime, coordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(nonemptyGreen.state()).isEqualTo(RoundState.GREEN);
        assertThat(nonemptyGreen.checkObservationIds()).isNotEmpty();
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isEqualTo(1);
    }

    @Test
    void laterGreenPolicyAdvanceKeepsTheFirstReceiptLearningOpportunity()
    {
        Claim first = observationClaim("learn-successor");
        CiRound original = executeCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        String operationId = jdbc.queryForObject(
                "SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE kind = 'RUN_CI_LEARNING'",
                String.class);

        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:successor",
                "github-check-policy-digest:successor",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound successor = executeCiObservation(
                runtime, coordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(successor.roundId()).isNotEqualTo(original.roundId());
        assertThat(autofix.roundById(original.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE kind = 'RUN_CI_LEARNING'",
                String.class)).isEqualTo(operationId);
    }

    @Test
    void queuedLearningStartStalesWithoutLaunchAndCancellationReplays()
    {
        Claim first = observationClaim("learn-stale-start");
        CiRound original = executeCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(original.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning(
                "learner", Duration.ofMinutes(30)).orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);

        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:stale-learning",
                "github-check-policy-digest:stale-learning",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim observation = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound successor = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(successor.roundId()).isNotEqualTo(original.roundId());

        assertThat(coordinator.beginCiLearning(learning)).isEmpty();
        assertThat(coordinator.beginCiLearning(learning)).isEmpty();
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(runtime.run(start.run().runId()).orElseThrow().state())
                .isEqualTo(RunState.CANCELED);
        assertThat(coordinator.learningCompletion(
                learning.operationId()).orElseThrow())
                .satisfies(completion -> {
                    assertThat(completion.state())
                            .isEqualTo(LearningCompletionState.MISSED);
                    assertThat(completion.reasonCode())
                            .isEqualTo("CI_LEARNING_GREEN_SUPERSEDED");
                });
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.ACTIVE);
    }

    @Test
    void unbegunLearningStalesWithoutCreatingAnyAgentOwner()
    {
        Claim first = observationClaim("learn-stale-before-begin");
        CiRound original = executeCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning(
                "learner", Duration.ofMinutes(30)).orElseThrow();
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:stale-before-begin",
                "github-check-policy-digest:stale-before-begin",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        advancePublicationClock(Duration.ofMinutes(6));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim observation = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound successor = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(successor.roundId()).isNotEqualTo(original.roundId());
        assertThat(coordinator.beginCiLearning(learning)).isEmpty();
        assertThat(coordinator.beginCiLearning(learning)).isEmpty();
        assertThat(runtime.operation(learning.operationId()).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.state())
                            .isEqualTo(OperationState.CANCELED);
                    assertThat(operation.resultRef())
                            .isEqualTo("CI_LEARNING_GREEN_SUPERSEDED");
                });
        assertThat(count("flow_runtime_agent_session",
                "role = 'CI_LEARNER'")).isZero();
        assertThat(count("flow_runtime_agent_run",
                "role = 'CI_LEARNER'")).isZero();
        assertThat(count("flow_runtime_agent_result",
                "run_id IN (SELECT run_id FROM flow_runtime_agent_run "
                        + "WHERE role = 'CI_LEARNER')")).isZero();
        assertThat(coordinator.learningCompletion(
                learning.operationId())).isEmpty();
    }

    @Test
    void reservedLearnerExpiryRedrivesTheSameIsolatedRun()
    {
        Claim observation = observationClaim("learn-reserved-expiry");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);

        expireLearningRuntime();

        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isTrue();
        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isTrue();
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(runtime.run(start.run().runId()).orElseThrow().state())
                .isEqualTo(RunState.QUEUED);
        assertThat(autofix.roundById(green.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
        Claim redelivery = runtime.claimNextCiLearning("learner-2", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart same = coordinator.beginCiLearning(
                redelivery).orElseThrow();
        assertThat(redelivery.generation())
                .isEqualTo(learning.generation() + 1);
        assertThat(same.run().runId()).isEqualTo(start.run().runId());
        assertThat(same.session().sessionId())
                .isEqualTo(start.session().sessionId());
        assertThat(count("flow_runtime_agent_run",
                "operation_id = '" + learning.operationId() + "'"))
                .isEqualTo(1);
        assertThat(count("flow_runtime_agent_session",
                "session_id = '" + start.session().sessionId() + "'"))
                .isEqualTo(1);
    }

    @Test
    void activatedLearnerExpiryQuarantinesOnlyTheLearner()
    {
        Claim observation = observationClaim("learn-activated-expiry");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "detached-ci-learner");
        transition(TaskStatus.COMPLETED);

        expireLearningRuntime();

        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(runtime.operation(learning.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.FAILED);
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
        assertThat(coordinator.learningCompletion(
                learning.operationId())).isEmpty();
        assertThat(autofix.roundById(green.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
    }

    @Test
    void stoppedRecoveryKeepsAcceptedSealAndNeverInventsOpaqueProse()
    {
        Claim observation = observationClaim("learn-stopped-recovery");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "stopped-ci-learner");
        runtime.sealCiLearningLesson(
                attempt.processAttemptId(), learning,
                "Recovered exact lesson",
                "The accepted semantic command survives restart.");
        markLearningAttemptStopped(attempt.processAttemptId());

        expireLearningRuntime();

        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        AgentResult result = runtime.resultForRun(
                start.run().runId()).orElseThrow();
        var completion = coordinator.learningCompletion(
                learning.operationId()).orElseThrow();
        assertThat(result.terminalOutcome())
                .isEqualTo(TerminalOutcome.FAILED);
        assertThat(result.finalContent()).isNull();
        assertThat(result.errorRef())
                .isEqualTo("CI_LEARNING_COMPLETION_LOST");
        assertThat(completion.state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
        assertThat(coordinator.lesson(completion.lessonId()).orElseThrow())
                .satisfies(lesson -> {
                    assertThat(lesson.title())
                            .isEqualTo("Recovered exact lesson");
                    assertThat(lesson.markdown()).isEqualTo(
                            "The accepted semantic command survives restart.");
                });
        jdbc.update(
                "UPDATE flow_runtime_agent_run "
                        + "SET failure_reason_code = 'CORRUPT' "
                        + "WHERE run_id = ?",
                start.run().runId());
        assertThatThrownBy(() -> coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replay is inconsistent");
    }

    @Test
    void stoppedRecoveryWithoutCurrentSealedGreenIsMissed()
    {
        Claim observation = observationClaim("learn-stopped-stale");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "stale-stopped-ci-learner");
        runtime.sealCiLearningLesson(
                attempt.processAttemptId(), learning,
                "Stale lesson", "This must not become a candidate.");
        markLearningAttemptStopped(attempt.processAttemptId());
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:stale-before-recovery",
                "github-check-policy-digest:stale-before-recovery",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        expireLearningRuntime();

        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(coordinator.learningCompletion(
                learning.operationId()).orElseThrow())
                .satisfies(completion -> {
                    assertThat(completion.state())
                            .isEqualTo(LearningCompletionState.MISSED);
                    assertThat(completion.reasonCode())
                            .isEqualTo("GREEN_SUPERSEDED");
                });
        assertThat(count("flow_ci_lesson", "1 = 1")).isZero();
    }

    @Test
    void stoppedRecoveryWithoutASealNeverLearnsFromMissingProse()
    {
        Claim observation = observationClaim("learn-stopped-no-seal");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "unsealed-stopped-ci-learner");
        runtime.revokeInProcessCiLearningCapability(
                attempt.processAttemptId(), learning);
        markLearningAttemptStopped(attempt.processAttemptId());

        expireLearningRuntime();

        assertThat(coordinator.recoverExpiredCiLearning(
                learning.operationId(), learning.generation())).isFalse();
        assertThat(coordinator.learningCompletion(
                learning.operationId()).orElseThrow())
                .satisfies(completion -> {
                    assertThat(completion.state())
                            .isEqualTo(LearningCompletionState.MISSED);
                    assertThat(completion.reasonCode())
                            .isEqualTo("LESSON_NOT_PROPOSED");
                });
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        assertThat(count("flow_ci_lesson", "1 = 1")).isZero();
    }

    @Test
    void acceptedLessonSealReplaysAfterGreenAuthorityChanges()
    {
        Claim observation = observationClaim("learn-seal-replay");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var attempt = runtime.reserveInProcessCiLearningAttempt(
                start.run().runId(), learning);
        runtime.activateInProcessCiLearningAttempt(
                attempt.processAttemptId(), learning, 1L, NOW, 1L,
                "seal-replay-ci-learner");
        String digest = coordinator.saveLesson(
                start, learning, attempt.processAttemptId(),
                "Durable response-loss lesson",
                "Identical retry returns the accepted seal.");
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:after-seal",
                "github-check-policy-digest:after-seal",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        assertThat(coordinator.saveLesson(
                start, learning, attempt.processAttemptId(),
                "Durable response-loss lesson",
                "Identical retry returns the accepted seal."))
                .isEqualTo(digest);
        assertThatThrownBy(() -> coordinator.saveLesson(
                start, learning, attempt.processAttemptId(),
                "Conflicting lesson",
                "A second semantic command must not replace the first."))
                .isInstanceOf(IllegalStateException.class);
        assertThat(autofix.roundById(green.roundId()).orElseThrow().state())
                .isEqualTo(RoundState.GREEN);
        assertThat(count("flow_ci_learning_lesson_request", "1 = 1"))
                .isEqualTo(1);
    }

    @Test
    void finalizedCandidateReplaysAfterLaterPolicyAdvance()
    {
        Claim observation = observationClaim("learn-finalize-replay");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        FlowRuntime.CiLearningStart start = coordinator.beginCiLearning(
                learning).orElseThrow();
        var supervisor = new InProcessCiLearningAgentSupervisor(runtime);
        var completion = new InProcessCiLearningAgentSupervisor
                .AgentCompletion(
                        TerminalOutcome.CANCELED, null,
                        "OPAQUE_CANCELED_AFTER_SAVE");
        var handle = supervisor.launch(
                start, learning, coordinator, capability -> {
                    capability.saveCiLesson(
                            "Finalized response-loss lesson",
                            "The candidate remains immutable after response loss.");
                    return completion;
                });
        AgentResult first = supervisor.awaitAndFinish(handle, TTL);
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:after-finalize",
                "github-check-policy-digest:after-finalize",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        AgentResult replay = coordinator.finish(
                start, learning, completion);

        assertThat(replay).isEqualTo(first);
        assertThat(coordinator.learningCompletion(
                learning.operationId()).orElseThrow().state())
                .isEqualTo(LearningCompletionState.CANDIDATE);
        assertThat(count("flow_ci_lesson", "1 = 1")).isEqualTo(1);
    }

    @Test
    void learningBeginRejectsCorruptionAcrossItsFrozenOwnerGraph()
    {
        Claim observation = observationClaim("learn-graph-corruption");
        CiRound green = executeCiObservation(
                runtime, coordinator, observation,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        Claim learning = runtime.claimNextCiLearning("learner", TTL)
                .orElseThrow();
        String subjectId = runtime.operation(
                learning.operationId()).orElseThrow().inputRef();
        String repairResultId = jdbc.queryForObject(
                "SELECT repair_result_id FROM flow_ci_learning_subject "
                        + "WHERE subject_id = ?",
                String.class, subjectId);
        String stopProof = jdbc.queryForObject(
                "SELECT stop_proof_ref FROM flow_runtime_agent_result "
                        + "WHERE result_id = ?",
                String.class, repairResultId);
        jdbc.update(
                "UPDATE flow_runtime_agent_result "
                        + "SET stop_proof_ref = 'corrupt-proof' "
                        + "WHERE result_id = ?",
                repairResultId);
        assertThatThrownBy(() -> coordinator.beginCiLearning(learning))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed content");
        jdbc.update(
                "UPDATE flow_runtime_agent_result SET stop_proof_ref = ? "
                        + "WHERE result_id = ?",
                stopProof, repairResultId);

        String evidenceDigest = jdbc.queryForObject(
                "SELECT evidence_digest "
                        + "FROM flow_ci_learning_green_observation "
                        + "WHERE subject_id = ? AND ordinal = 0",
                String.class, subjectId);
        jdbc.update(
                "UPDATE flow_ci_learning_green_observation "
                        + "SET evidence_digest = 'corrupt-evidence' "
                        + "WHERE subject_id = ? AND ordinal = 0",
                subjectId);
        assertThatThrownBy(() -> coordinator.beginCiLearning(learning))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update(
                "UPDATE flow_ci_learning_green_observation "
                        + "SET evidence_digest = ? "
                        + "WHERE subject_id = ? AND ordinal = 0",
                evidenceDigest, subjectId);

        String receiptDigest = jdbc.queryForObject(
                "SELECT receipt_digest FROM flow_ci_learning_subject "
                        + "WHERE subject_id = ?",
                String.class, subjectId);
        updateWithoutForeignKeys(
                "UPDATE flow_ci_learning_subject "
                        + "SET receipt_digest = ? WHERE subject_id = ?",
                "corrupt-receipt", subjectId);
        assertThatThrownBy(() -> coordinator.beginCiLearning(learning))
                .isInstanceOf(IllegalStateException.class);
        updateWithoutForeignKeys(
                "UPDATE flow_ci_learning_subject "
                        + "SET receipt_digest = ? WHERE subject_id = ?",
                receiptDigest, subjectId);

        assertThat(coordinator.beginCiLearning(learning)).isPresent();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
    }

    @Test
    void receiptWatchQueuesExactRedProviderBatchWithItsLog()
    {
        Claim redClaim = observationClaim("observe-red");
        CiRound red = executeCiObservation(
                runtime, coordinator, redClaim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();

        assertThat(red.state()).isEqualTo(RoundState.QUEUED);
        assertThat(red.failedLogRefs()).hasSize(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .filteredOn(work -> work.externalKey().equals(red.roundId()))
                .singleElement()
                .satisfies(work -> assertThat(work.payloadRef())
                        .isEqualTo("ci-round:" + red.roundId()));
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + redClaim.operationId() + "'"))
                .isEqualTo(1);
    }

    @Test
    void unsupportedFailureRearmsAndLaterGreenIsAccepted()
    {
        Claim first = observationClaim("observe-unsupported");

        assertThat(executeCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_UNSUPPORTED))
                .isEmpty();
        assertThat(runtime.operation(first.operationId()).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.state())
                            .isEqualTo(OperationState.READY);
                    assertThat(operation.resultRef())
                            .isEqualTo("CI_OBSERVATION_PROVIDER_UNSUPPORTED");
                });
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isZero();
        advancePublicationClock(Duration.ofMinutes(6));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        assertThat(retry.operationId()).isEqualTo(first.operationId());
        CiRound green = executeCiObservation(
                runtime, coordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.state()).isEqualTo(RoundState.GREEN);
    }

    @Test
    void unstableProviderReadStoresNothingAndIdenticalRetryReusesRound()
    {
        Claim first = observationClaim("observe-replay");
        assertThat(executeCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), UNSTABLE)).isEmpty();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isZero();

        advancePublicationClock(Duration.ofMinutes(2));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim accepted = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound firstGreen = executeCiObservation(
                runtime, coordinator, accepted,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        int observations = count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'");

        advancePublicationClock(Duration.ofMinutes(6));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        assertThat(runtime.operation(first.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.READY);
        assertThat(jdbc.queryForMap(
                "SELECT delivery_state, not_before FROM "
                        + "flow_runtime_dispatch_ticket WHERE operation_id = ?",
                first.operationId()))
                .containsEntry("delivery_state", "AVAILABLE");
        Claim replay = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound sameGreen = executeCiObservation(
                runtime, coordinator, replay,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();

        assertThat(sameGreen.roundId()).isEqualTo(firstGreen.roundId());
        assertThat(sameGreen.evidenceRevision())
                .isEqualTo(firstGreen.evidenceRevision());
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isEqualTo(observations);
    }

    @Test
    void acceptedBatchResponseLossReplaysTheExactHistoricalRound()
    {
        Claim claim = observationClaim("observe-response-loss");
        var delivery = prepareCiObservation(
                runtime, coordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN);

        CiRound first = coordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()).orElseThrow();
        int observations = count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'");
        int rounds = count("flow_ci_round",
                "source_observation_operation_id = '"
                        + claim.operationId() + "'");
        int finalRed = runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED)
                .toList().size();
        int learningSubjects = count(
                "flow_ci_learning_subject", "receipt_id IS NOT NULL");
        int learningOperations = count(
                "flow_runtime_operation", "kind = 'RUN_CI_LEARNING'");

        CiRound replay = coordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()).orElseThrow();

        assertThat(replay).isEqualTo(first);
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isEqualTo(observations);
        assertThat(count("flow_ci_round",
                "source_observation_operation_id = '"
                        + claim.operationId() + "'"))
                .isEqualTo(rounds);
        assertThat(runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED))
                .hasSize(finalRed);
        assertThat(count(
                "flow_ci_learning_subject", "receipt_id IS NOT NULL"))
                .isEqualTo(learningSubjects);
        assertThat(count(
                "flow_runtime_operation", "kind = 'RUN_CI_LEARNING'"))
                .isEqualTo(learningOperations);
    }

    @Test
    void greenLearningReservationRollsBackWithLateWatchRearmFailure()
    {
        Claim claim = observationClaim("learn-accept-rollback");
        var delivery = prepareCiObservation(
                runtime, coordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN);
        jdbc.execute("""
                CREATE TRIGGER reject_green_learning_rearm
                BEFORE UPDATE OF delivery_state
                ON flow_runtime_dispatch_ticket
                WHEN OLD.operation_id = '%s'
                  AND NEW.delivery_state = 'AVAILABLE'
                BEGIN
                    SELECT RAISE(ABORT, 'green rearm rejected');
                END
                """.formatted(claim.operationId()));

        assertThatThrownBy(() -> coordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("flow_ci_learning_subject", "1 = 1")).isZero();
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isZero();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isZero();
        assertThat(runtime.operation(claim.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);

        jdbc.execute("DROP TRIGGER reject_green_learning_rearm");
        assertThat(coordinator.acceptCiObservation(
                delivery.activation(), delivery.proof())).isPresent();
        assertThat(count("flow_ci_learning_subject", "1 = 1")).isEqualTo(1);
        assertThat(count("flow_runtime_operation",
                "kind = 'RUN_CI_LEARNING'")).isEqualTo(1);
    }

    @Test
    void lateAcceptanceFailureRollsBackEveryOwnerAndExactRetrySucceeds()
    {
        Claim claim = observationClaim("observe-rollback");
        var delivery = prepareCiObservation(
                runtime, coordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS);
        int pendingBefore = runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED)
                .toList().size();
        jdbc.execute("""
                CREATE TRIGGER reject_ci_observation_rearm
                BEFORE UPDATE OF delivery_state
                ON flow_runtime_dispatch_ticket
                WHEN OLD.operation_id = '%s'
                  AND NEW.delivery_state = 'AVAILABLE'
                BEGIN
                    SELECT RAISE(ABORT, 'CI rearm rejected');
                END
                """.formatted(claim.operationId()));

        assertThatThrownBy(() -> coordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isZero();
        assertThat(count("flow_ci_round",
                "source_observation_operation_id = '"
                        + claim.operationId() + "'"))
                .isZero();
        assertThat(runtime.operation(claim.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CLAIMED);
        assertThat(runtime.pendingWork(task.taskId()).stream()
                .filter(work -> work.kind() == PendingKind.FINAL_RED))
                .hasSize(pendingBefore);

        jdbc.execute("DROP TRIGGER reject_ci_observation_rearm");
        CiRound accepted = coordinator.acceptCiObservation(
                delivery.activation(), delivery.proof()).orElseThrow();
        assertThat(accepted.state()).isEqualTo(RoundState.QUEUED);
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isEqualTo(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .filteredOn(work -> work.kind() == PendingKind.FINAL_RED)
                .hasSize(pendingBefore + 1);
    }

    @Test
    void policyAdvanceRejectsOldBatchUntilTheNextCurrentPolicyPoll()
    {
        Claim first = observationClaim("observe-policy-advance");
        var oldPolicyDelivery = prepareCiObservation(
                runtime, coordinator, first,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN);
        var current = autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:advanced",
                "github-check-policy-digest:advanced",
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        assertThat(coordinator.acceptCiObservation(
                oldPolicyDelivery.activation(), oldPolicyDelivery.proof()))
                .isEmpty();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + first.operationId() + "'"))
                .isZero();

        advancePublicationClock(Duration.ofMinutes(2));
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
        Claim retry = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound green = executeCiObservation(
                runtime, coordinator, retry,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), GREEN)
                .orElseThrow();
        assertThat(green.policyRevisionId())
                .isEqualTo(current.policyRevisionId());
        assertThat(green.sourceObservationOperationId())
                .isEqualTo(first.operationId());
    }

    @Test
    void unrelatedInternalFactCannotCompleteAProviderBatch()
    {
        Claim claim = observationClaim("observe-mixed-source");
        autofix.observeCi(pr.prId(), new NormalizedCheck(
                runtime.pullRequest(pr.prId()).orElseThrow().currentRemoteHead(),
                "GITHUB_CHECK:7:build", "internal-check", "internal-run",
                1, "internal-revision", "build", "COMPLETED", "SUCCESS",
                NOW, NOW.plusSeconds(1), NOW.plusSeconds(1),
                "internal:evidence"));

        CiRound collecting = executeCiObservation(
                runtime, coordinator, claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), PENDING)
                .orElseThrow();

        assertThat(collecting.state()).isEqualTo(RoundState.COLLECTING);
        assertThat(collecting.sourceObservationOperationId())
                .isEqualTo(claim.operationId());
        assertThat(collecting.checkObservationIds()).isEmpty();
        assertThat(count("flow_ci_check_observation",
                "source_operation_id = '" + claim.operationId() + "'"))
                .isEqualTo(1);
        assertThat(runtime.pendingWork(task.taskId()))
                .noneMatch(work -> work.kind() == PendingKind.FINAL_RED
                        && work.externalKey().equals(collecting.roundId()));
    }

    @Test
    void newerReceiptSupersessionMakesOldClaimRecoveryIdempotent()
    {
        Claim oldWatch = observationClaim("observe-old-receipt");
        CiRound firstRed = executeCiObservation(
                runtime, coordinator, oldWatch,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();
        CompletedReady secondReady = openReadyGate(
                "observe-second-receipt", firstRed);
        GateRevision secondRevision = secondReady.revision();
        AuthorizedCiUpdate secondAuthorization = userGates.authorizeCiUpdate(
                secondRevision.gateId(), secondRevision.revision(),
                secondRevision.subjectDigest(), secondRevision.actionDigest(),
                "observe-second-receipt-authorization");
        Claim secondPublication = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var secondReceipt = executeApplied(
                runtime, userGates, githubEffects, secondPublication,
                githubEffects.steps(secondAuthorization.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC), () -> {},
                new AtomicInteger()).orElseThrow();

        Claim secondWatch = runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
        CiRound secondRed = executeCiObservation(
                runtime, coordinator, secondWatch,
                Clock.fixed(runtimeNow, ZoneOffset.UTC), FAILED_ACTIONS)
                .orElseThrow();
        CompletedReady thirdReady = openReadyGate(
                "observe-third-receipt", secondRed);
        GateRevision thirdRevision = thirdReady.revision();
        AuthorizedCiUpdate thirdAuthorization = userGates.authorizeCiUpdate(
                thirdRevision.gateId(), thirdRevision.revision(),
                thirdRevision.subjectDigest(), thirdRevision.actionDigest(),
                "observe-third-receipt-authorization");
        Claim thirdPublication = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var thirdReceipt = executeApplied(
                runtime, userGates, githubEffects, thirdPublication,
                githubEffects.steps(thirdAuthorization.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC), () -> {},
                new AtomicInteger()).orElseThrow();

        assertThat(runtime.operation(oldWatch.operationId()).orElseThrow())
                .satisfies(operation -> {
                    assertThat(operation.state())
                            .isEqualTo(OperationState.CANCELED);
                    assertThat(operation.resultRef())
                            .isEqualTo("CI_HEAD_SUPERSEDED:"
                                    + secondReceipt.receiptId());
                });
        assertThat(runtime.operation(secondWatch.operationId()).orElseThrow()
                .resultRef()).isEqualTo(
                        "CI_HEAD_SUPERSEDED:" + thirdReceipt.receiptId());
        runtime.recoverExpiredCiObservation(
                oldWatch.operationId(), oldWatch.generation());
        runtime.recoverExpiredCiObservation(
                oldWatch.operationId(), oldWatch.generation());
        assertThat(count("flow_runtime_operation",
                "kind = 'OBSERVE_CI' AND state <> 'CANCELED'"))
                .isEqualTo(1);
    }

    @Test
    void attemptInsertFailureRollsBackBeforeProviderCall()
    {
        CompletedReady ready = openReadyGate("executor-attempt-rollback");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "executor-attempt-rollback-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var step = githubEffects.steps(authorized.planId()).getFirst();
        jdbc.execute("""
                CREATE TRIGGER reject_effect_attempt
                BEFORE INSERT ON flow_github_external_effect_attempt
                BEGIN
                    SELECT RAISE(ABORT, 'attempt insert rejected');
                END
                """);
        AtomicInteger pushes = new AtomicInteger();

        assertThatThrownBy(() -> executeApplied(
                runtime,
                userGates,
                githubEffects,
                claim,
                step,
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                () -> {},
                pushes)).isInstanceOf(RuntimeException.class);

        assertThat(pushes.get()).isZero();
        assertThat(count(
                "flow_github_external_effect_attempt", "1 = 1")).isZero();
    }

    @Test
    void invalidProviderTargetReplaysWithoutAnotherProviderCommand()
    {
        assertTerminalProviderRejectionReplays(true);
    }

    @Test
    void divergedProviderTargetReplaysWithoutAnotherProviderCommand()
    {
        assertTerminalProviderRejectionReplays(false);
    }

    private void assertTerminalProviderRejectionReplays(
            boolean invalidTarget)
    {
        String suffix = invalidTarget
                ? "provider-invalid-replay" : "provider-diverged-replay";
        CompletedReady ready = openReadyGate(suffix);
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                suffix + "-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        AtomicInteger providerCommands = new AtomicInteger();

        assertThat(executeTerminalProbe(
                runtime,
                userGates,
                githubEffects,
                claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                invalidTarget,
                providerCommands)).isEmpty();
        int afterFirst = providerCommands.get();
        assertThat(executeTerminalProbe(
                runtime,
                userGates,
                githubEffects,
                claim,
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                invalidTarget,
                providerCommands)).isEmpty();

        assertThat(providerCommands.get()).isEqualTo(afterFirst);
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.CANCELED);
        assertThat(githubEffects.attempts(authorized.planId())).isEmpty();
    }

    @Test
    void unavailablePreflightRetainsTheBarrierWithoutAnAttempt()
    {
        CompletedReady ready = openReadyGate("provider-preflight-unavailable");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "provider-preflight-unavailable-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        AtomicInteger pushes = new AtomicInteger();

        assertThat(executeUnavailableProbe(
                runtime,
                userGates,
                githubEffects,
                claim,
                githubEffects.steps(authorized.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                false,
                pushes)).isEmpty();

        assertThat(pushes.get()).isZero();
        assertThat(githubEffects.attempts(authorized.planId())).isEmpty();
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(userGates.transitions(revision.gateId()).getLast())
                .satisfies(transition -> {
                    assertThat(transition.toState())
                            .isEqualTo(GateState.NEEDS_ATTENTION);
                    assertThat(transition.reasonCode())
                            .isEqualTo("EFFECT_PREPARATION_UNAVAILABLE");
                });
    }

    @Test
    void invalidPostAttemptProbeRetainsTheBarrier()
    {
        CompletedReady ready = openReadyGate("provider-post-invalid");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "provider-post-invalid-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        AtomicInteger pushes = new AtomicInteger();

        assertThat(executeUnavailableProbe(
                runtime,
                userGates,
                githubEffects,
                claim,
                githubEffects.steps(authorized.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC),
                true,
                pushes)).isEmpty();

        assertThat(pushes.get()).isEqualTo(1);
        assertThat(githubEffects.attempts(authorized.planId())).hasSize(1);
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(userGates.transitions(revision.gateId()).getLast())
                .satisfies(transition -> {
                    assertThat(transition.toState())
                            .isEqualTo(GateState.NEEDS_ATTENTION);
                    assertThat(transition.reasonCode())
                            .isEqualTo("EFFECT_PROBE_UNAVAILABLE");
                });
    }

    @Test
    void activatedExpiryIsProbeOnlyAndRecoveryReplays()
    {
        CompletedReady ready = openReadyGate("publish-probe-recovery");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-probe-recovery-key");
        Claim first = runtime.claimNextPublish(
                "publisher", Duration.ofSeconds(1)).orElseThrow();
        var activation = userGates.beginCiUpdateEffect(first);
        githubEffects.recordObservation(
                first,
                observation(
                        runtime, first, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        githubEffects.activateAttempt(first, authorized.planId(), NOW);
        advancePublicationClock(Duration.ofSeconds(2));

        assertThatThrownBy(() -> userGates.recoverExpiredCiUpdateEffect(
                first.operationId(), first.generation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe-only");
        userGates.recoverExpiredCiUpdateProbe(
                first.operationId(), first.generation());
        userGates.recoverExpiredCiUpdateProbe(
                first.operationId(), first.generation());
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState())
                .isEqualTo(GateState.NEEDS_ATTENTION);

        advancePublicationClock(Duration.ofSeconds(5));
        Claim probe = runtime.claimNextPublish("probe-worker", TTL)
                .orElseThrow();
        var probeActivation = userGates.beginCiUpdateEffect(probe);
        assertThat(probeActivation.mutationAllowed()).isFalse();
        userGates.applyCiUpdateObservation(
                probe,
                null,
                githubEffects.attempts(authorized.planId()).getLast(),
                observation(
                        runtime,
                        probe,
                        probeActivation,
                        githubEffects.attempts(
                                authorized.planId()).getLast(),
                        ProbeOutcome.ABSENT));
        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState())
                .isEqualTo(GateState.AUTHORIZED);
        advancePublicationClock(Duration.ofSeconds(5));
        Claim retry = runtime.claimNextPublish("retry-worker", TTL)
                .orElseThrow();
        assertThat(userGates.beginCiUpdateEffect(retry).mutationAllowed())
                .isTrue();
    }

    @Test
    void authorityDriftAfterAttemptRetainsProbeOnlyBarrierOnAbsent()
    {
        CompletedReady ready = openReadyGate("publish-authority-unproven");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-authority-unproven-key");
        Claim first = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(first);
        githubEffects.recordObservation(
                first,
                observation(
                        runtime, first, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        var activated = githubEffects.activateAttempt(
                first, authorized.planId(), NOW);
        runtime.consumePublishExecutionHandle(
                activated.executionHandle(),
                first,
                activated.attempt().attemptId(),
                activated.attempt().executionTokenDigest());
        userGates.applyCiUpdateObservation(
                first,
                activated.executionHandle(),
                activated.attempt(),
                observation(
                        runtime,
                        first,
                        activation,
                        activated.attempt(),
                        ProbeOutcome.ABSENT));
        publishCheckPolicy("authority-drift", List.of("/usr/bin/true"));
        advancePublicationClock(Duration.ofSeconds(5));
        Claim second = runtime.claimNextPublish("probe-worker", TTL)
                .orElseThrow();

        assertThatThrownBy(() -> userGates.beginCiUpdateEffect(second))
                .isInstanceOf(
                        UserGates.DurableProbeRequiredException.class);
        advancePublicationClock(Duration.ofSeconds(5));
        Claim third = runtime.claimNextPublish("probe-worker-2", TTL)
                .orElseThrow();
        var probeOnly = userGates.beginCiUpdateEffect(third);
        assertThat(probeOnly.mutationAllowed()).isFalse();
        userGates.applyCiUpdateObservation(
                third,
                null,
                activated.attempt(),
                observation(
                        runtime,
                        third,
                        probeOnly,
                        activated.attempt(),
                        ProbeOutcome.ABSENT));

        assertThat(userGates.transitions(revision.gateId()).getLast()
                .toState()).isEqualTo(GateState.NEEDS_ATTENTION);
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.RETRYABLE);
    }

    @Test
    void sameTimestampUnknownAfterAbsentCannotAuthorizeAttempt()
    {
        CompletedReady ready = openReadyGate("probe-order");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "probe-order-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        githubEffects.recordObservation(
                claim,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.ABSENT),
                NOW);
        githubEffects.recordObservation(
                claim,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.UNKNOWN),
                NOW);

        assertThatThrownBy(() -> githubEffects.activateAttempt(
                claim, authorized.planId(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest exact-expected");
        assertThat(count("flow_github_external_effect_attempt", "1 = 1"))
                .isZero();
    }

    @Test
    void appliedSettlementAcceptsEqualConcurrentRemoteObservation()
    {
        CompletedReady ready = openReadyGate("publish-observed-race");
        GateRevision revision = ready.revision();
        var authorized = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                "publish-observed-race-key");
        Claim claim = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var activation = userGates.beginCiUpdateEffect(claim);
        runtime.advanceRemoteHead(
                activation.prId(), activation.expectedRemoteHead(),
                activation.proposedHead());

        assertThat(userGates.applyCiUpdateObservation(
                claim,
                null,
                null,
                observation(
                        runtime, claim, activation, null,
                        ProbeOutcome.APPLIED))).isPresent();
        assertThat(runtime.operation(authorized.operationId()).orElseThrow()
                .state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(count("flow_github_external_effect_receipt", "1 = 1"))
                .isEqualTo(1);
    }

    private CompletedReady openReadyGate(String suffix)
    {
        return openReadyGate(suffix, "failure-1");
    }

    private CompletedReady openReadyGate(
            String suffix, String failureRevision)
    {
        ReviewerResultReady ready = prepareReviewerResult(
                suffix, failureRevision);
        String finalContent = "opaque ready " + suffix;
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
                                            finalContent,
                                            null);
                        });
        AgentResult result = ready.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        handle,
                        TTL);
        return new CompletedReady(
                ready,
                result,
                userGates.revisionForRun(ready.binding().run().runId())
                        .orElseThrow(),
                finalContent);
    }

    private CompletedReady openReadyGate(String suffix, CiRound red)
    {
        ReviewReady review = prepareCleanReview(suffix, red);
        ReviewerResultReady ready = prepareReviewerResult(review, suffix);
        String finalContent = "opaque ready " + suffix;
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
                                            finalContent,
                                            null);
                        });
        AgentResult result = ready.ready().review()
                .awaitReviewerResultContinuation(
                        supervisor, ready.binding(), handle, TTL);
        return new CompletedReady(
                ready,
                result,
                userGates.revisionForRun(ready.binding().run().runId())
                        .orElseThrow(),
                finalContent);
    }

    private ReviewerClaim prepareReviewerClaim(String suffix)
    {
        ReviewReady ready = prepareCleanReview(suffix);
        ParkedReview parked = parkForReviewer(
                ready, TerminalOutcome.COMPLETED);
        Claim claim = claim(OperationKind.RUN_REVIEWER);
        return new ReviewerClaim(
                parked.request(),
                claim,
                ready.review().beginReviewer(
                        parked.request().requestId(), claim));
    }

    private void insertReviewerProcessAttempt(
            ReviewerClaim reviewer, String id, String state)
    {
        boolean activated = state.equals("ACTIVATED");
        jdbc.update("""
                INSERT INTO flow_runtime_agent_process_attempt (
                    process_attempt_id, run_id, operation_id,
                    claim_generation, claim_token_digest, execution_id,
                    capability_id, state, reserved_at,
                    jvm_pid, jvm_started_at, thread_id, thread_name,
                    activated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                reviewer.start().run().runId(),
                reviewer.claim().operationId(),
                reviewer.claim().generation(),
                "opaque-token-digest",
                id + "-execution",
                id + "-capability",
                state,
                NOW.toEpochMilli(),
                activated ? 1L : null,
                activated ? NOW.toEpochMilli() : null,
                activated ? 1L : null,
                activated ? "unowned-reviewer-thread" : null,
                activated ? NOW.toEpochMilli() : null);
    }

    private void expireRuntime()
    {
        rebuildOwnerGraph(
                Clock.fixed(
                        NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC),
                false);
    }

    private void expireLearningRuntime()
    {
        Instant expiredAt = runtimeNow.plus(TTL).plusSeconds(1);
        rebuildOwnerGraph(
                Clock.fixed(expiredAt, ZoneOffset.UTC), false);
    }

    private void markLearningAttemptStopped(String attemptId)
    {
        int updated = jdbc.update(
                """
                UPDATE flow_runtime_agent_process_attempt
                SET state = 'STOPPED', stop_type = 'NORMAL_RETURN',
                    stop_proof_ref = ?, stopped_at = ?
                WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                  AND capability_revoked_at IS NOT NULL
                """,
                "stopped-proof:" + attemptId, NOW.toEpochMilli(), attemptId);
        assertThat(updated).isEqualTo(1);
    }

    private void advancePublicationClock(Duration duration)
    {
        runtimeNow = runtimeNow.plus(duration);
        Clock advanced = Clock.fixed(runtimeNow, ZoneOffset.UTC);
        rebuildOwnerGraph(advanced, false);
    }

    private ParkedReview parkForReviewer(
            ReviewReady ready, TerminalOutcome outcome)
    {
        AtomicReference<ReviewerRequest> request = new AtomicReference<>();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.review().launchTaskInspection(
                supervisor,
                ready.binding(),
                ready.claim(),
                capability -> {
                    capability.runChecks();
                    request.set(capability.spawnAdversarialReviewer());
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            outcome,
                            "opaque parent " + outcome,
                            outcome == TerminalOutcome.COMPLETED
                                    ? null
                                    : "PARENT_" + outcome);
                });
        AgentResult result = ready.review().awaitTaskInspection(
                supervisor, ready.binding(), handle, TTL);
        return new ParkedReview(request.get(), result);
    }

    private record ParkedReview(
            ReviewerRequest request, AgentResult parentResult) {}

    private record ReviewerResultReady(
            ReviewReady ready,
            Claim claim,
            CiFixReviewCoordinator.ReviewerResultBinding binding) {}

    private record CompletedReady(
            ReviewerResultReady ready,
            AgentResult result,
            GateRevision revision,
            String finalContent) {}

    private record ReviewerClaim(
            ReviewerRequest request,
            Claim claim,
            FlowRuntime.ReviewerStart start) {}

    private CiRound enqueueFailedRound()
    {
        CiRound red = failedRound("failure-1", NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        return coordinator.enqueueRepair(red.roundId()).round();
    }

    private void publishCheckPolicy(String name, List<String> command)
    {
        LocalCheckPolicyRevision current = localChecks.currentPolicy(
                task.repositoryId()).orElseThrow();
        localChecks.recordPolicy(
                task.repositoryId(),
                current.policyRevisionId(),
                "test-policy:" + name,
                "test-policy-digest:" + name,
                List.of(new LocalChecks.ProfileDefinition(
                        name,
                        command,
                        ".",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(GateIntent.CI_UPDATE))));
    }

    private void assertPostSealDriftNeedsAttention(
            String suffix, Runnable drift)
    {
        ReviewerResultReady ready = prepareReviewerResult(suffix);
        CountDownLatch sealed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = ready.ready().review()
                .launchReviewerResultContinuation(
                        supervisor,
                        ready.binding(),
                        ready.claim(),
                        capability -> {
                            capability.readyForReview();
                            sealed.countDown();
                            awaitLatch(release);
                            return new InProcessWriterAgentSupervisor
                                    .AgentCompletion(
                                            TerminalOutcome.COMPLETED,
                                            "opaque post-seal drift",
                                            null);
                        });
        awaitLatch(sealed);
        drift.run();
        release.countDown();
        ready.ready().review().awaitReviewerResultContinuation(
                supervisor, ready.binding(), handle, TTL);

        assertThat(userGates.gate(pr.prId())).isEmpty();
        assertThat(runtime.task(task.taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(runtime.readyAttentionReasonForRun(
                ready.binding().run().runId()))
                .contains("REVIEW_READINESS_STALE");
    }

    private ReviewReady prepareCleanCleanupReview(String suffix)
    {
        ReservedCleanup reserved = reserveCleanup(suffix);
        CleanupBinding cleanup = coordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchCleanup(
                supervisor,
                cleanup,
                reserved.claim(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> {
                        try {
                            Files.delete(reserved.dirtyPath());
                        }
                        catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        commitCiChange(
                                "cleanup-ready-" + suffix + ".txt",
                                "clean\n",
                                "cleanup ready " + suffix);
                    });
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "opaque cleanup " + suffix,
                            null);
                });
        coordinator.awaitCleanup(
                supervisor, cleanup, handle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(coordinator.selectNext(reconciliation).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        CiFixReviewCoordinator review = new CiFixReviewCoordinator(
                autofix, runtime, localChecks, userGates);
        return new ReviewReady(
                review,
                turn,
                review.beginTaskInspection(turn, repositoryRoot, TTL));
    }

    private ReviewReady prepareCleanReview(String suffix)
    {
        return prepareCleanReview(suffix, "failure-1");
    }

    private ReviewReady prepareCleanReview(
            String suffix, String failureRevision)
    {
        StartedRepair started = startRepair(failureRevision);
        return prepareCleanReview(suffix, started);
    }

    private ReviewReady prepareCleanReview(String suffix, CiRound red)
    {
        return prepareCleanReview(suffix, startRepair(red));
    }

    private ReviewReady prepareCleanReview(
            String suffix, StartedRepair started)
    {
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = coordinator.launchRepair(
                supervisor,
                started.binding(),
                started.claim(),
                started.fence(),
                repositoryRoot,
                capability -> {
                    capability.runTool(() -> commitCiChange(
                            "review-" + suffix + ".txt",
                            "candidate\n",
                            "review " + suffix));
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "opaque fixer " + suffix,
                            null);
                });
        coordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = coordinator.selectNext(reconciliation)
                .orElseThrow();
        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        CiFixReviewCoordinator review = new CiFixReviewCoordinator(
                autofix, runtime, localChecks, userGates);
        var binding = review.beginTaskInspection(
                turn, repositoryRoot, TTL);
        return new ReviewReady(review, turn, binding);
    }

    private static CiFixReviewOrigin reviewOrigin(ReviewReady ready)
    {
        return new CiFixReviewOrigin(
                ready.binding().input().pendingId(),
                CiFixSourceKind.valueOf(
                        ready.binding().projection().source().name()),
                ready.binding().projection().sourceId());
    }

    private static void awaitLatch(CountDownLatch latch)
    {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static boolean awaitLatch(
            CountDownLatch latch, Duration timeout)
    {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void joinThread(Thread thread)
    {
        try {
            thread.join(15_000);
            if (thread.isAlive()) {
                throw new IllegalStateException("test thread did not stop");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private record ReviewReady(
            CiFixReviewCoordinator review,
            Claim claim,
            CiFixReviewCoordinator.TaskInspectionBinding binding) {}

    private StartedRepair startRepair()
    {
        return startRepair("failure-1");
    }

    private StartedRepair startRepair(String failureRevision)
    {
        CiRound red = failedRound(failureRevision, NOW.plusSeconds(
                failureRevision.equals("failure-1") ? 0 : 60));
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        return startRepair(red);
    }

    private StartedRepair startRepair(CiRound red)
    {
        CiRound round = coordinator.enqueueRepair(red.roundId()).round();
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

    private Claim observationClaim(String suffix)
    {
        CompletedReady ready = openReadyGate(suffix);
        GateRevision revision = ready.revision();
        AuthorizedCiUpdate authorization = userGates.authorizeCiUpdate(
                revision.gateId(), revision.revision(),
                revision.subjectDigest(), revision.actionDigest(),
                suffix + "-authorization");
        Claim publication = runtime.claimNextPublish("publisher", TTL)
                .orElseThrow();
        var receipt = executeApplied(
                runtime, userGates, githubEffects, publication,
                githubEffects.steps(authorization.planId()).getFirst(),
                Clock.fixed(runtimeNow, ZoneOffset.UTC), () -> {},
                new AtomicInteger()).orElseThrow();
        assertThat(receipt.receiptId()).isNotBlank();
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:" + suffix,
                "github-check-policy-digest:" + suffix,
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        return runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
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
        Runnable hook = publishedSubjectHook.get();
        if (hook != null) {
            hook.run();
        }
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
        pr = runtime.bindGitHubRemoteIdentity(
                local.prId(), publishedHead,
                new GitHubRepositoryLocator(
                        "101", "octocat", "bytequay"),
                new GitHubRepositoryLocator(
                        "202", "octocat", "bytequay"), 42,
                "PR_node", "https://example.test/pr/42", "receipt:42");
        return runtime.task(started.taskId()).orElseThrow();
    }

    private void restart()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        rebuildOwnerGraph(clock, true);
    }

    private void rebuildOwnerGraph(Clock clock, boolean rebuildAutofix)
    {
        runtime = new FlowRuntime(dataSource, clock);
        localChecks = new LocalChecks(dataSource, runtime, clock);
        if (rebuildAutofix) {
            autofix = new CiAutofix(
                    dataSource,
                    new ObjectMapper(),
                    clock,
                    this::publishedSubject);
        }
        githubEffects = new GitHubEffects(dataSource, runtime);
        userGates = new UserGates(
                dataSource,
                runtime,
                localChecks,
                autofix,
                githubEffects,
                clock);
        coordinator = new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates,
                clock);
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

    private void updateWithoutForeignKeys(String sql, Object... arguments)
    {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            connection.createStatement().execute("PRAGMA foreign_keys=OFF");
            try (var statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < arguments.length; index++) {
                    statement.setObject(index + 1, arguments[index]);
                }
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            return null;
        });
    }
}
