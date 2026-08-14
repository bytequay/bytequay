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

import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.ci.CiCleanupCoordinator.CleanupBinding;
import com.bytequay.app.flow.ci.CiRepairCoordinator.RepairBinding;
import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedCiUpdate;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGatesSchema;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.github.GitHubEffectsSchema;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixSourceKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GitHubRepositoryLocator;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckPolicyRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowRuntimeSchema;
import com.bytequay.app.flow.runtime.FlowRuntimeTestSupport;
import com.bytequay.app.flow.runtime.InProcessReviewerAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.LocalChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeApplied;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.executeTerminalProbe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture shared by the {@code TestCiAutofixCoordinator*} classes: the
 * four-schema SQLite graph, the owner rebuild helpers, and the Git
 * repository fixtures every coordinator test drives.
 *
 * <p>The tests are split across subclasses so Surefire's forks can divide
 * them. One class held all 161, and because a class never spans forks it
 * pinned a single fork for the length of the whole suite.
 */
abstract class BaseTestCiAutofixCoordinator
{
    static final Instant NOW = Instant.parse("2026-08-10T10:15:30Z");

    static final Duration TTL = Duration.ofMinutes(5);

    /**
     * Budget for the helpers below that wait on another thread reaching a
     * state. They are liveness guards, not timing assertions — nothing here
     * asserts that work is fast, only that it happens — so the budget is far
     * wider than the work needs. These classes run their methods concurrently
     * on a machine already saturated by four Surefire forks spawning Git, and
     * a five second budget failed there while the thread was merely waiting
     * its turn.
     */
    static final Duration LIVENESS_TIMEOUT = Duration.ofSeconds(30);

    private static final Object SCHEMA_TEMPLATE_LOCK = new Object();

    private static volatile byte[] schemaTemplate;

    @TempDir
    Path temporaryDirectory;

    DataSource dataSource;

    JdbcTemplate jdbc;

    FlowRuntime runtime;

    LocalChecks localChecks;

    CiAutofix autofix;

    GitHubEffects githubEffects;

    UserGates userGates;

    CiRepairCoordinator repairCoordinator;

    CiCleanupCoordinator cleanupCoordinator;

    CiLearningCoordinator learningCoordinator;

    CiObservationCoordinator observationCoordinator;

    Task task;

    PullRequestSubject pr;

    Path repositoryRoot;

    String publishedHead;

    Instant runtimeNow;

    /**
     * Request id for this test's fixture task. Every id below it — task, run,
     * and the writer execution id — is a digest of its inputs, so a constant
     * here would give two tests in one JVM the same execution id and they
     * would collide in the supervisor's process-wide live-execution registry.
     * Derived from the test name rather than a counter so a failure replays.
     */
    String fixtureRequestId;

    final AtomicReference<Runnable> publishedSubjectHook =
            new AtomicReference<>();

    @BeforeEach
    void setUp(TestInfo testInfo)
    {
        fixtureRequestId = "request-" + testInfo.getTestMethod()
                .orElseThrow().getName();
        Path database = temporaryDirectory.resolve("flow.db");
        writeSchemaTemplate(database);
        dataSource = sqliteDataSource(database);
        runtimeNow = NOW;
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
        rebuildCiCoordinators(
                Clock.fixed(runtimeNow, ZoneOffset.UTC));
    }

    private static DataSource sqliteDataSource(Path database)
    {
        return new DriverManagerDataSource(
                "jdbc:sqlite:" + database
                        + "?foreign_keys=ON&busy_timeout=5000");
    }

    private static void writeSchemaTemplate(Path database)
    {
        byte[] template = schemaTemplate;
        if (template == null) {
            synchronized (SCHEMA_TEMPLATE_LOCK) {
                template = schemaTemplate;
                if (template == null) {
                    template = createSchemaTemplate(database);
                    schemaTemplate = template;
                }
            }
        }
        try {
            Files.write(database, template);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] createSchemaTemplate(Path database)
    {
        DataSource templateDataSource = sqliteDataSource(database);
        FlowRuntimeSchema.install(templateDataSource);
        CiAutofixSchema.install(templateDataSource);
        UserGatesSchema.install(templateDataSource);
        GitHubEffectsSchema.install(templateDataSource);
        try {
            return Files.readAllBytes(database);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void assertBoundaryUnprovenNeedsAttention(
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

    void assertReviewerCompletionReplacesReconciliation(
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

    ReviewerResultReady prepareReviewerResult(String suffix)
    {
        return prepareReviewerResult(suffix, "failure-1");
    }

    ReviewerResultReady prepareReviewerResult(
            String suffix, String failureRevision)
    {
        ReviewReady ready = prepareCleanReview(suffix, failureRevision);
        return prepareReviewerResult(ready, suffix);
    }

    ReviewerResultReady prepareReviewerResult(
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

    void assertTerminalProviderRejectionReplays(
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

    CompletedReady openReadyGate(String suffix)
    {
        return openReadyGate(suffix, "failure-1");
    }

    CompletedReady openReadyGate(
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
                            capability.completeReview();
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

    CompletedReady openReadyGate(String suffix, CiRound red)
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
                            capability.completeReview();
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

    ReviewerClaim prepareReviewerClaim(String suffix)
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

    void insertReviewerProcessAttempt(
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

    void expireRuntime()
    {
        rebuildOwnerGraph(
                Clock.fixed(
                        NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC),
                false);
    }

    void expireLearningRuntime()
    {
        Instant expiredAt = runtimeNow.plus(TTL).plusSeconds(1);
        rebuildOwnerGraph(
                Clock.fixed(expiredAt, ZoneOffset.UTC), false);
    }

    void markLearningAttemptStopped(String attemptId)
    {
        String error = "RECOVERED_STOPPED_TEST";
        markAttemptStopped(
                attemptId, TerminalOutcome.FAILED, null, error);
    }

    void markAttemptStopped(
            String attemptId,
            TerminalOutcome outcome,
            String content,
            String error)
    {
        String proof = "stopped-proof:" + attemptId;
        String completionDigest = testStableId(
                "in-process-stopped-completion",
                attemptId,
                proof,
                outcome.name(),
                content == null ? "<null>" : content,
                error == null ? "<null>" : error);
        int updated = jdbc.update(
                """
                UPDATE flow_runtime_agent_process_attempt
                SET state = 'STOPPED', stop_type = 'NORMAL_RETURN',
                    stop_proof_ref = ?, stopped_at = ?,
                    capability_revoked_at = COALESCE(
                        capability_revoked_at, ?),
                    completion_outcome = ?, completion_content = ?,
                    completion_error_ref = ?, completion_digest = ?
                WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                """,
                proof,
                NOW.toEpochMilli(),
                NOW.toEpochMilli(),
                outcome.name(),
                content,
                error,
                completionDigest,
                attemptId);
        assertThat(updated).isEqualTo(1);
    }

    static String testStableId(
            String namespace, String... components)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(namespace.getBytes(StandardCharsets.UTF_8));
            for (String component : components) {
                digest.update((byte) 0);
                digest.update(component.getBytes(StandardCharsets.UTF_8));
            }
            return namespace + "-" + HexFormat.of()
                    .formatHex(digest.digest()).substring(0, 32);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void advancePublicationClock(Duration duration)
    {
        runtimeNow = runtimeNow.plus(duration);
        Clock advanced = Clock.fixed(runtimeNow, ZoneOffset.UTC);
        rebuildOwnerGraph(advanced, false);
    }

    ParkedReview parkForReviewer(
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

    record ParkedReview(
            ReviewerRequest request, AgentResult parentResult) {}

    record ReviewerResultReady(
            ReviewReady ready,
            Claim claim,
            CiFixReviewCoordinator.ReviewerResultBinding binding) {}

    record CompletedReady(
            ReviewerResultReady ready,
            AgentResult result,
            GateRevision revision,
            String finalContent) {}

    record ReviewerClaim(
            ReviewerRequest request,
            Claim claim,
            FlowRuntime.ReviewerStart start) {}

    CiRound enqueueFailedRound()
    {
        CiRound red = failedRound("failure-1", NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        return repairCoordinator.enqueueRepair(red.roundId()).round();
    }

    void publishCheckPolicy(String name, List<String> command)
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

    void assertPostSealDriftNeedsAttention(
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
                            capability.completeReview();
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

    ReviewReady prepareCleanCleanupReview(String suffix)
    {
        ReservedCleanup reserved = reserveCleanup(suffix);
        CleanupBinding cleanup = cleanupCoordinator.beginCleanup(
                reserved.claim(), repositoryRoot, TTL).orElseThrow();
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = cleanupCoordinator.launchCleanup(
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
        cleanupCoordinator.awaitCleanup(
                supervisor, cleanup, handle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(repairCoordinator.selectNext(reconciliation).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        CiFixReviewCoordinator review = new CiFixReviewCoordinator(
                autofix, runtime, localChecks, userGates);
        return new ReviewReady(
                review,
                turn,
                review.beginTaskInspection(turn, repositoryRoot, TTL));
    }

    ReviewReady prepareCleanReview(String suffix)
    {
        return prepareCleanReview(suffix, "failure-1");
    }

    ReviewReady prepareCleanReview(
            String suffix, String failureRevision)
    {
        StartedRepair started = startRepair(failureRevision);
        return prepareCleanReview(suffix, started);
    }

    ReviewReady prepareCleanReview(String suffix, CiRound red)
    {
        return prepareCleanReview(suffix, startRepair(red));
    }

    ReviewReady prepareCleanReview(
            String suffix, StartedRepair started)
    {
        var supervisor = new InProcessWriterAgentSupervisor(runtime);
        var handle = repairCoordinator.launchRepair(
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
        repairCoordinator.awaitRepair(
                supervisor, started.binding(), handle, TTL);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = repairCoordinator.selectNext(reconciliation)
                .orElseThrow();
        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        CiFixReviewCoordinator review = new CiFixReviewCoordinator(
                autofix, runtime, localChecks, userGates);
        var binding = review.beginTaskInspection(
                turn, repositoryRoot, TTL);
        return new ReviewReady(review, turn, binding);
    }

    static CiFixReviewOrigin reviewOrigin(ReviewReady ready)
    {
        return new CiFixReviewOrigin(
                ready.binding().input().pendingId(),
                CiFixSourceKind.valueOf(
                        ready.binding().projection().source().name()),
                ready.binding().projection().sourceId());
    }

    static void awaitLatch(CountDownLatch latch)
    {
        try {
            if (!latch.await(
                    LIVENESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    static boolean awaitLatch(
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

    static void joinThread(Thread thread)
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

    static void awaitThreadBlockedOrEnded(Thread thread)
    {
        long deadline = System.nanoTime() + LIVENESS_TIMEOUT.toNanos();
        while (thread.isAlive()
                && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        if (thread.isAlive()
                && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING
                && thread.getState() != Thread.State.BLOCKED) {
            throw new IllegalStateException(
                    "test cancellation did not reach its wait boundary");
        }
    }

    static void awaitNamedThreadTermination(String threadName)
    {
        long deadline = System.nanoTime() + LIVENESS_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            boolean alive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> thread.isAlive()
                            && thread.getName().equals(threadName));
            if (!alive) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException(
                "test thread did not terminate: " + threadName);
    }

    record ReviewReady(
            CiFixReviewCoordinator review,
            Claim claim,
            CiFixReviewCoordinator.TaskInspectionBinding binding) {}

    StartedRepair startRepair()
    {
        return startRepair("failure-1");
    }

    StartedRepair startRepair(String failureRevision)
    {
        CiRound red = failedRound(failureRevision, NOW.plusSeconds(
                failureRevision.equals("failure-1") ? 0 : 60));
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());
        return startRepair(red);
    }

    StartedRepair startRepair(CiRound red)
    {
        CiRound round = repairCoordinator.enqueueRepair(red.roundId()).round();
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = repairCoordinator.selectNext(reconciliation)
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
        RepairBinding binding = repairCoordinator.beginRepair(
                round.roundId(), fix, fence);
        return new StartedRepair(fix, fence, binding);
    }

    ReservedCleanup reserveCleanup(String suffix)
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
        var handle = repairCoordinator.launchRepair(
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
        repairCoordinator.awaitRepair(
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

    record StartedRepair(
            Claim claim, WriterFence fence, RepairBinding binding) {}

    record ReservedCleanup(
            StartedRepair repair,
            CiRepairAttempt predecessor,
            CiCleanupSeal seal,
            Claim claim,
            Path dirtyPath,
            InProcessWriterAgentSupervisor.AgentCompletion
                    predecessorCompletion) {}

    void assertTerminalCiAudit(TaskStatus terminal)
    {
        transition(terminal);
        CiRound red = failedRound("terminal-" + terminal, NOW);
        autofix.attachLog(
                red.checkObservationIds().getFirst(),
                "failure".getBytes(StandardCharsets.UTF_8),
                List.of());

        var first = repairCoordinator.enqueueRepair(red.roundId());
        var duplicate = repairCoordinator.enqueueRepair(red.roundId());

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

    void assertQueuedThenTerminalRedelivery(
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
        var queued = repairCoordinator.enqueueRepair(red.roundId());
        assertThat(queued.round().state()).isEqualTo(RoundState.QUEUED);
        assertThat(queued.reconciliationOperationId()).isNotNull();

        transition(terminal);
        restart();
        var terminalRegistration = repairCoordinator.enqueueRepair(red.roundId());
        var duplicate = repairCoordinator.enqueueRepair(red.roundId());

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

    CiRound failedRound(String revision, Instant startedAt)
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

    Claim observationClaim(String suffix)
    {
        CompletedReady ready = openReadyGate(suffix);
        return publishReadyAndClaimObservation(ready, suffix);
    }

    Claim publishReadyAndClaimObservation(
            CompletedReady ready, String suffix)
    {
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
        pr = runtime.pullRequest(pr.prId()).orElseThrow();
        publishedHead = pr.currentRemoteHead();
        autofix.recordPolicy(
                task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                "github-check-policy:" + suffix,
                "github-check-policy-digest:" + suffix,
                PolicyResolution.RESOLVED, null,
                List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
        return runtime.claimNextCiObservation("observer", TTL)
                .orElseThrow();
    }

    NormalizedCheck check(
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

    PublishedPrSubject publishedSubject(String prId)
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

    Task publishedTask()
    {
        repositoryRoot = temporaryDirectory.resolve("repository");
        Path worktree = temporaryDirectory.resolve("worktree");
        initializeRepository(repositoryRoot, worktree, "task/one");
        String base = gitOutput(repositoryRoot, "rev-parse", "HEAD");
        Task started = FlowRuntimeTestSupport.startTask(runtime,
                fixtureRequestId, "repo-1", "Implement",
                worktree.toString());
        Claim provision = claim(OperationKind.PROVISION_TASK);
        FlowRuntimeTestSupport.provisionTask(runtime, provision, base);
        finishInitialTaskTurn();
        Task adopted = runtime.task(started.taskId()).orElseThrow();
        publishedHead = adopted.currentHeadSha();
        PullRequestSubject local = runtime.materializePullRequest(
                started.taskId(), adopted.currentChangeSetRevisionId(),
                "main", "main", "main");
        pr = FlowRuntimeTestSupport.bindGitHubFixture(
                runtime,
                local.prId(), publishedHead,
                new GitHubRepositoryLocator(
                        "101", "octocat", "bytequay"),
                new GitHubRepositoryLocator(
                        "202", "octocat", "bytequay"), 42,
                "PR_node", "https://example.test/pr/42", "receipt:42");
        return runtime.task(started.taskId()).orElseThrow();
    }

    void restart()
    {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        rebuildOwnerGraph(clock, true);
    }

    void rebuildOwnerGraph(Clock clock, boolean rebuildAutofix)
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
        rebuildCiCoordinators(clock);
    }

    void rebuildCiCoordinators(Clock clock)
    {
        learningCoordinator = new CiLearningCoordinator(
                dataSource, autofix, runtime, userGates, clock);
        observationCoordinator = new CiObservationCoordinator(
                dataSource, autofix, runtime, learningCoordinator, clock);
        repairCoordinator = new CiRepairCoordinator(
                dataSource, autofix, runtime, learningCoordinator);
        cleanupCoordinator = new CiCleanupCoordinator(
                dataSource, autofix, runtime);
    }

    void transition(TaskStatus next)
    {
        Task current = runtime.task(task.taskId()).orElseThrow();
        runtime.transitionTask(
                current.taskId(),
                current.currentLifecycleRevisionId(),
                next,
                "TEST_" + next,
                "test:" + next);
    }

    void finishInitialTaskTurn()
    {
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        Operation selected = runtime.selectNext(reconciliation).orElseThrow();
        assertThat(selected.kind()).isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        Task current = runtime.task(turn.taskId()).orElseThrow();
        WriterFence fence = FlowRuntimeTestSupport.acquireWriterFixture(
                runtime,
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
        var handle = FlowRuntimeTestSupport.launchWriterFixture(
                supervisor,
                runtime,
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

    static void commitTaskChange(Path worktree)
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

    void commitCiChange(String file, String content, String message)
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

    static void initializeRepository(
            Path repository, Path worktree, String branch)
    {
        try {
            Files.createDirectories(repository);
            gitOutput(repository, "init", "-b", "main");
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

    static String gitOutput(Path directory, String... arguments)
    {
        try {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.addAll(List.of(arguments));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put("GIT_AUTHOR_NAME", "ByteQuay Test");
            builder.environment().put(
                    "GIT_AUTHOR_EMAIL", "test@bytequay.invalid");
            builder.environment().put("GIT_COMMITTER_NAME", "ByteQuay Test");
            builder.environment().put(
                    "GIT_COMMITTER_EMAIL", "test@bytequay.invalid");
            Process process = builder.start();
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

    Claim claim(OperationKind expected)
    {
        Claim claim = runtime.claimNext("worker", TTL).orElseThrow();
        assertThat(claim.kind()).isEqualTo(expected);
        return claim;
    }

    int count(String table, String condition)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + condition,
                Integer.class);
    }

    void updateWithoutForeignKeys(String sql, Object... arguments)
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
