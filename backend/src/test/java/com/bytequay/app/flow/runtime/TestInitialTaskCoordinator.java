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

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiAutofixSchema;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGatesSchema;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.github.GitHubEffectsSchema;
import com.bytequay.app.flow.github.GitHubInitialRepositoryObserver;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.LocalChecks.ProfileDefinition;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialRepositoryObservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestInitialTaskCoordinator
{
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    @TempDir
    private Path temporaryDirectory;

    private FlowRuntime runtime;
    private DataSource dataSource;
    private TaskProvisioning provisioning;
    private LocalChecks localChecks;
    private CiAutofix autofix;
    private UserGates gates;
    private InitialTaskCoordinator coordinator;
    private Path repository;
    private MutableClock clock;

    @BeforeEach
    void setUp()
            throws Exception
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("flow.db")
                        + "?foreign_keys=ON&busy_timeout=5000");
        FlowRuntimeSchema.install(dataSource);
        CiAutofixSchema.install(dataSource);
        UserGatesSchema.install(dataSource);
        GitHubEffectsSchema.install(dataSource);
        clock = new MutableClock(NOW);
        runtime = new FlowRuntime(dataSource, clock);

        repository = temporaryDirectory.resolve("repository");
        Path worktrees = temporaryDirectory.resolve("worktrees");
        Files.createDirectories(worktrees);
        initializeRepository(repository);
        provisioning = new TaskProvisioning(
                dataSource,
                runtime,
                ignored -> new TaskProvisioning.RepositoryConfig(
                        "octocat/bytequay", "octocat", "bytequay",
                        repository, repository.resolve(".git"), "origin",
                        "refs/remotes/origin/HEAD", worktrees),
                clock);
        localChecks = new LocalChecks(dataSource, runtime, clock);
        localChecks.recordPolicy(
                "octocat/bytequay", null, "test-local-policy:v1",
                "test-local-policy-digest:v1",
                List.of(new ProfileDefinition(
                        "compile", List.of("/usr/bin/true"), ".",
                        List.of(), Duration.ofSeconds(5),
                        List.of(GateIntent.INITIAL_PUBLISH))));
        autofix = new CiAutofix(
                dataSource,
                new ObjectMapper(),
                clock,
                prId -> {
                    PullRequestSubject pr = runtime.pullRequest(prId)
                            .orElseThrow();
                    return new PublishedPrSubject(
                            pr.prId(), pr.taskId(), pr.repositoryId(),
                            pr.scopeKey(), pr.targetBaseRef(),
                            pr.currentRemoteHead());
                });
        GitHubEffects effects = new GitHubEffects(dataSource, runtime);
        gates = new UserGates(
                dataSource, runtime, localChecks, autofix, effects, clock);
        coordinator = new InitialTaskCoordinator(
                runtime, provisioning, localChecks, gates);
    }

    @Test
    void realSupervisorsCommitReviewAndOpenOneManualInitialGate()
    {
        Task task = provisioning.startTask(
                "request-1", "octocat/bytequay", "Add the exact file");
        Claim provision = runtime.claimNextForDispatch(
                Set.of(OperationKind.PROVISION_TASK), "provision", TTL, 1)
                .orElseThrow();
        provisioning.execute(provision);

        Claim firstTurn = selectAndClaimInitial();
        Task beforeFirstTurn = runtime.task(task.taskId()).orElseThrow();
        assertThatThrownBy(() -> runtime.acquireWriterLease(
                firstTurn,
                AgentRole.TASK_AGENT,
                new WorktreeSnapshot(
                        beforeFirstTurn.currentHeadSha(),
                        "caller-tree", "caller-evidence"),
                TTL))
                .isInstanceOf(FlowRuntime.MutationRejectedException.class)
                .hasMessageContaining("inspected admission");
        InitialTaskCoordinator.TaskBinding first = coordinator.beginTask(
                firstTurn, TTL);
        InProcessWriterAgentSupervisor writers =
                new InProcessWriterAgentSupervisor(runtime);
        AtomicReference<String> context = new AtomicReference<>();
        var firstHandle = coordinator.launchTask(
                writers, first, firstTurn,
                () -> {
                    throw new AssertionError(
                            "first turn cannot observe GitHub");
                },
                capability -> {
                    context.set(capability.readContext());
                    NewFlowWorkspaceTools workspace =
                            new NewFlowWorkspaceTools(
                                    Path.of(runtime.task(task.taskId())
                                            .orElseThrow().worktreePath()));
                    workspace.writeFile("feature.txt", "implemented\n");
                    String head = capability.callTool(
                            workspace::commitTaskChange);
                    capability.adoptCommittedHead(head);
                    capability.requestReview(
                            "Implement exact Task", "Adds feature.txt");
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.FAILED,
                            "opaque failure after accepted review request",
                            "model-ended-after-terminal");
                });
        failAgentResultInsert();
        assertThatThrownBy(() -> coordinator.awaitTask(
                writers, first, firstHandle, TTL))
                .isInstanceOf(RuntimeException.class);
        allowAgentResultInsert();
        clock.advance(TTL.plusSeconds(1));
        AgentResult parent = coordinator.recoverExpiredStoppedTask(
                firstTurn.operationId(), firstTurn.generation(), TTL);
        assertThat(coordinator.awaitTask(
                writers, first, firstHandle, TTL)).isEqualTo(parent);
        assertThatThrownBy(() -> coordinator.recoverExpiredStoppedTask(
                firstTurn.operationId(), firstTurn.generation(), TTL))
                .isInstanceOf(RuntimeException.class);

        assertThat(context.get()).contains(
                "taskGoal=Add the exact file",
                "initialBase=");
        assertThat(parent.terminalOutcome()).isEqualTo(TerminalOutcome.FAILED);
        Task afterCommit = runtime.task(task.taskId()).orElseThrow();
        PullRequestSubject localPr = runtime.pullRequest(afterCommit.prId())
                .orElseThrow();
        ReviewerRequest firstRequest = runtime.reviewerRequestForParentRun(
                first.run().runId()).orElseThrow();
        assertThat(localPr.published()).isFalse();
        assertThat(firstRequest.intendedGateKind())
                .isEqualTo(GateIntent.INITIAL_PUBLISH);
        assertThat(firstRequest.remoteHeadSha()).isNull();
        assertThat(firstRequest.originCiFixPendingId()).isNull();

        Claim reviewerClaim = claimInitial(OperationKind.RUN_REVIEWER);
        FlowRuntime.ReviewerStart reviewer = coordinator.beginReviewer(
                firstRequest.requestId(), reviewerClaim);
        InProcessReviewerAgentSupervisor reviewers =
                new InProcessReviewerAgentSupervisor(runtime);
        AtomicInteger reviewReads = new AtomicInteger();
        var reviewerHandle = coordinator.launchReviewer(
                reviewers, reviewer, reviewerClaim,
                capability -> {
                    assertThat(capability.readDiff()).isNotEmpty();
                    reviewReads.incrementAndGet();
                    return new InProcessReviewerAgentSupervisor.AgentCompletion(
                            TerminalOutcome.FAILED,
                            "A correction is required", "REVIEW_FAILED");
                });
        AgentResult failedReview = coordinator.awaitReviewer(
                reviewers, reviewerHandle, TTL);
        assertThat(failedReview.terminalOutcome())
                .isEqualTo(TerminalOutcome.FAILED);
        assertThat(reviewReads).hasValue(1);

        Claim correctionClaim = selectAndClaimInitial();
        InitialTaskCoordinator.TaskBinding correction = coordinator.beginTask(
                correctionClaim, TTL);
        var correctionHandle = coordinator.launchTask(
                writers, correction, correctionClaim,
                () -> {
                    throw new AssertionError(
                            "failed review cannot authorize publication");
                },
                capability -> {
                    assertThat(capability.readContext()).contains(
                            "reviewOutcome=FAILED",
                            "reviewError=REVIEW_FAILED");
                    assertThatThrownBy(capability::readyForInitialPublish)
                            .isInstanceOf(IllegalStateException.class);
                    NewFlowWorkspaceTools workspace =
                            new NewFlowWorkspaceTools(Path.of(runtime.task(
                                    task.taskId()).orElseThrow()
                                    .worktreePath()));
                    workspace.writeFile("feature.txt", "corrected\n");
                    String head = capability.callTool(
                            workspace::commitTaskChange);
                    capability.adoptCommittedHead(head);
                    capability.adoptCommittedHead(head);
                    capability.requestReview(
                            "Corrected exact Task", "Corrects feature.txt");
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "fresh review requested", null);
                });
        coordinator.awaitTask(
                writers, correction, correctionHandle, TTL);
        PullRequestSubject correctedPr = runtime.pullRequest(localPr.prId())
                .orElseThrow();
        assertThat(correctedPr.prId()).isEqualTo(localPr.prId());
        assertThat(correctedPr.published()).isFalse();
        ReviewerRequest correctedRequest =
                runtime.reviewerRequestForParentRun(
                        correction.run().runId()).orElseThrow();

        Claim correctedReviewerClaim = claimInitial(
                OperationKind.RUN_REVIEWER);
        FlowRuntime.ReviewerStart correctedReviewer =
                coordinator.beginReviewer(
                        correctedRequest.requestId(), correctedReviewerClaim);
        var correctedReviewerHandle = coordinator.launchReviewer(
                reviewers, correctedReviewer, correctedReviewerClaim,
                capability -> {
                    assertThat(capability.readDiff()).isNotEmpty();
                    return new InProcessReviewerAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "No blocking findings", null);
                });
        AgentResult reviewed = coordinator.awaitReviewer(
                reviewers, correctedReviewerHandle, TTL);
        assertThat(reviewed.terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);

        autofix.recordPolicy(
                task.repositoryId(), localPr.scopeKey(),
                localPr.targetBaseRef(), "test-required-ci:v1",
                "test-required-ci-digest:v1", PolicyResolution.RESOLVED,
                null, List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));

        Claim readyClaim = selectAndClaimInitial();
        InitialTaskCoordinator.TaskBinding ready = coordinator.beginTask(
                readyClaim, TTL);
        AtomicInteger repositoryLookups = new AtomicInteger();
        char[] token = "repo-secret".toCharArray();
        var readyHandle = coordinator.launchTask(
                writers, ready, readyClaim,
                () -> initialRepositoryObservation(
                        runtime, ready.run().runId(), "101", "101",
                        token, repositoryLookups),
                capability -> {
                    assertThat(capability.readContext())
                            .contains("reviewSummary=No blocking findings");
                    assertThat(capability.readCandidateDiff()).isNotEmpty();
                    capability.readyForInitialPublish();
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.CANCELED,
                            "opaque cancellation after accepted readiness",
                            "model-ended-after-terminal");
                });
        AgentResult readyResult = coordinator.awaitTask(
                writers, ready, readyHandle, TTL);

        assertThat(readyResult.terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(repositoryLookups).hasValue(1);
        assertThat(token).containsOnly('\0');
        assertThat(runtime.task(task.taskId()).orElseThrow()
                .selectedWriterOperationId()).isNull();
        var gate = gates.initialGate(localPr.prId()).orElseThrow();
        assertThat(gates.transitions(gate.gateId()))
                .singleElement()
                .satisfies(transition -> {
                    assertThat(transition.toState()).isEqualTo(GateState.OPEN);
                    assertThat(transition.reasonCode()).isEqualTo("READY");
                });
    }

    @Test
    void stoppedInitialContinuationWithoutTerminalSettlesExactAttention()
    {
        ReviewedInitial reviewed = reviewedInitial("missing-terminal");
        Claim continuationClaim = selectAndClaimInitial();
        InitialTaskCoordinator.TaskBinding continuation = coordinator.beginTask(
                continuationClaim, TTL);
        var handle = coordinator.launchTask(
                reviewed.writers(), continuation, continuationClaim,
                () -> {
                    throw new AssertionError(
                            "missing terminal must not observe GitHub");
                },
                capability -> new InProcessWriterAgentSupervisor.AgentCompletion(
                        TerminalOutcome.COMPLETED,
                        "opaque prose without a command", null));

        failAgentResultInsert();
        assertThatThrownBy(() -> coordinator.awaitTask(
                reviewed.writers(), continuation, handle, TTL))
                .isInstanceOf(RuntimeException.class);
        allowAgentResultInsert();
        clock.advance(TTL.plusSeconds(1));
        AgentResult recovered = coordinator.recoverExpiredStoppedTask(
                continuationClaim.operationId(),
                continuationClaim.generation(), TTL);
        AgentResult replay = coordinator.awaitTask(
                reviewed.writers(), continuation, handle, TTL);

        assertThat(replay).isEqualTo(recovered);
        assertThat(runtime.task(reviewed.task().taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_task_lifecycle_revision "
                        + "WHERE task_id = ? AND reason_code = ? "
                        + "AND evidence_ref = ?",
                Integer.class,
                reviewed.task().taskId(),
                "MISSING_INITIAL_TERMINAL_REQUEST",
                "agent-result:" + recovered.resultId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_agent_result "
                        + "WHERE run_id = ?",
                Integer.class, continuation.run().runId()))
                .isEqualTo(1);
        assertThatThrownBy(() -> coordinator.recoverExpiredStoppedTask(
                continuationClaim.operationId(),
                continuationClaim.generation(), TTL))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void taskCommandsAndProductionLanesDriveBoundedModelToInitialGate()
            throws Exception
    {
        CredentialStore credentials = mock(CredentialStore.class);
        Credential credential = new Credential(
                7, CredentialType.AI, "openai", "default api",
                "test", "***", null, true, null,
                NOW.minusSeconds(60), NOW, null);
        when(credentials.find(
                CredentialType.AI, "openai", "default api"))
                .thenReturn(Optional.of(credential));
        when(credentials.getSecret(
                CredentialType.AI, "openai", "default api"))
                .thenReturn(Optional.of("ai-secret"));
        NewFlowAgentLaunches launches = new NewFlowAgentLaunches(
                dataSource, runtime, credentials,
                new NewFlowAgentLaunches.Config(
                        "openai", TurnSpec.Transport.OPENAI_COMPAT,
                        "https://models.example.test/v1/chat/completions",
                        "test-model", "medium", "openai", "default api",
                        1024, 2),
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        TurnRunner runner = mock(TurnRunner.class);
        CountDownLatch reviewerEntered = new CountDownLatch(1);
        CountDownLatch policyReady = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger();
        when(runner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            ToolExecutor tools = invocation.getArgument(1);
            int turn = turns.incrementAndGet();
            if (turn == 1) {
                assertTool(tools, "read_initial_task_context", "{}");
                assertTool(tools, "write_file",
                        "{\"path\":\"command-flow.txt\","
                                + "\"content\":\"implemented\\n\"}");
                assertTool(tools, "commit_initial_change", "{}");
                assertTool(tools, "request_initial_review",
                        "{\"title\":\"Command flow\","
                                + "\"body\":\"Exact initial change\"}");
                return turnResult(TurnResult.End.INTERRUPTED);
            }
            if (turn == 2) {
                assertTool(tools, "read_diff", "{}");
                reviewerEntered.countDown();
                if (!policyReady.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("required CI policy was not set");
                }
                return turnResult(TurnResult.End.COMPLETED);
            }
            if (turn == 3) {
                assertTool(tools, "read_initial_review_context", "{}");
                assertTool(tools, "read_candidate_diff", "{}");
                assertTool(tools, "ready_for_initial_publish", "{}");
                return turnResult(TurnResult.End.INTERRUPTED);
            }
            throw new AssertionError("unexpected model turn " + turn);
        });
        NewFlowAgentBodies bodies = new NewFlowAgentBodies(
                launches, runner, new ObjectMapper(), localChecks);
        GitHubInitialRepositoryObserver repositories = mock(
                GitHubInitialRepositoryObserver.class);
        when(repositories.observe(anyString())).thenAnswer(invocation ->
                initialRepositoryObservation(
                        runtime, invocation.getArgument(0), "101", "101",
                        "repo-secret".toCharArray(), new AtomicInteger()));
        NewFlowDispatcher generic = new NewFlowDispatcher(
                runtime,
                new NewFlowDispatcher.Config(
                        "generic-command-test", TTL,
                        Duration.ofMillis(20), 1),
                List.of(provisioning));
        InitialTaskDispatcher initial = new InitialTaskDispatcher(
                runtime, coordinator,
                new InProcessWriterAgentSupervisor(runtime),
                new InProcessReviewerAgentSupervisor(runtime),
                bodies, repositories,
                new InitialTaskDispatcher.Config(
                        "initial-command-test", TTL,
                        Duration.ofMillis(20), Duration.ofMinutes(10),
                        Duration.ofSeconds(5), 1));
        TaskCommands commands = new TaskCommands(
                provisioning, generic, initial);
        generic.start();
        initial.start();
        try {
            Task task = commands.startTask(
                    "command-request", "octocat/bytequay",
                    "Implement through TaskCommands");
            assertThat(reviewerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Task reviewedTask = runtime.task(task.taskId()).orElseThrow();
            PullRequestSubject pr = runtime.pullRequest(reviewedTask.prId())
                    .orElseThrow();
            autofix.recordPolicy(
                    task.repositoryId(), pr.scopeKey(), pr.targetBaseRef(),
                    "command-required-ci:v1",
                    "command-required-ci-digest:v1",
                    PolicyResolution.RESOLVED, null,
                    List.of("GITHUB_CHECK:7:build"), List.of("SUCCESS"));
            policyReady.countDown();
            awaitCondition(() -> gates.initialGate(pr.prId()).isPresent());

            assertThat(turns).hasValue(3);
            assertThat(gates.initialGate(pr.prId())).isPresent();
            assertThat(runtime.task(task.taskId()).orElseThrow()
                    .selectedWriterOperationId()).isNull();
            assertThat(runtime.currentChangeSet(task.taskId()).orElseThrow()
                    .headSha()).isEqualTo(git(
                            Path.of(runtime.task(task.taskId()).orElseThrow()
                                    .worktreePath()),
                            "rev-parse", "HEAD").strip());
        }
        finally {
            policyReady.countDown();
            initial.close();
            generic.close();
        }
    }

    private ReviewedInitial reviewedInitial(String suffix)
    {
        Task task = provisioning.startTask(
                "request-" + suffix, "octocat/bytequay",
                "Review the exact initial change");
        Claim provision = runtime.claimNextForDispatch(
                Set.of(OperationKind.PROVISION_TASK), "provision", TTL, 1)
                .orElseThrow();
        provisioning.execute(provision);
        Claim firstClaim = selectAndClaimInitial();
        InitialTaskCoordinator.TaskBinding first = coordinator.beginTask(
                firstClaim, TTL);
        InProcessWriterAgentSupervisor writers =
                new InProcessWriterAgentSupervisor(runtime);
        var firstHandle = coordinator.launchTask(
                writers, first, firstClaim,
                () -> {
                    throw new AssertionError("first turn cannot observe GitHub");
                },
                capability -> {
                    NewFlowWorkspaceTools workspace =
                            new NewFlowWorkspaceTools(Path.of(runtime.task(
                                    task.taskId()).orElseThrow()
                                    .worktreePath()));
                    workspace.writeFile(suffix + ".txt", "implemented\n");
                    String head = capability.callTool(
                            workspace::commitTaskChange);
                    capability.adoptCommittedHead(head);
                    capability.requestReview("Initial", "Exact change");
                    return new InProcessWriterAgentSupervisor.AgentCompletion(
                            TerminalOutcome.COMPLETED, "requested", null);
                });
        coordinator.awaitTask(writers, first, firstHandle, TTL);
        PullRequestSubject pr = runtime.pullRequest(
                runtime.task(task.taskId()).orElseThrow().prId()).orElseThrow();
        ReviewerRequest request = runtime.reviewerRequestForParentRun(
                first.run().runId()).orElseThrow();
        Claim reviewerClaim = claimInitial(OperationKind.RUN_REVIEWER);
        FlowRuntime.ReviewerStart reviewer = coordinator.beginReviewer(
                request.requestId(), reviewerClaim);
        InProcessReviewerAgentSupervisor reviewers =
                new InProcessReviewerAgentSupervisor(runtime);
        var reviewerHandle = coordinator.launchReviewer(
                reviewers, reviewer, reviewerClaim,
                capability -> new InProcessReviewerAgentSupervisor
                        .AgentCompletion(
                                TerminalOutcome.COMPLETED,
                                "reviewed", null));
        coordinator.awaitReviewer(reviewers, reviewerHandle, TTL);
        return new ReviewedInitial(task, pr, writers);
    }

    private void failAgentResultInsert()
    {
        new JdbcTemplate(dataSource).execute(
                "CREATE TRIGGER reject_initial_agent_result "
                        + "BEFORE INSERT ON flow_runtime_agent_result "
                        + "BEGIN SELECT RAISE(ABORT, 'forced'); END");
    }

    private void allowAgentResultInsert()
    {
        new JdbcTemplate(dataSource).execute(
                "DROP TRIGGER reject_initial_agent_result");
    }

    private record ReviewedInitial(
            Task task,
            PullRequestSubject pr,
            InProcessWriterAgentSupervisor writers) {}

    private Claim selectAndClaimInitial()
    {
        Claim reconciliation = claimInitial(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNextInitial(reconciliation)).isPresent();
        return claimInitial(OperationKind.RUN_TASK_TURN);
    }

    private static void assertTool(
            ToolExecutor executor, String name, String arguments)
            throws Exception
    {
        ToolExecutor.ToolCallResult result = executor.execute(new ToolCall(
                "call-" + name,
                name,
                arguments,
                new ObjectMapper().readTree(arguments)));
        assertThat(result.isError()).as(name + ": " + result.text()).isFalse();
    }

    private static TurnResult turnResult(TurnResult.End end)
    {
        return new TurnResult("opaque", 1, 1, 0, 1, end);
    }

    private static void awaitCondition(CheckedCondition condition)
            throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.test()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition did not become true");
            }
            Thread.sleep(10);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition
    {
        boolean test()
                throws Exception;
    }

    private Claim claimInitial(OperationKind expected)
    {
        Claim claim = runtime.claimNextInitialTask("initial", TTL, 1)
                .orElseThrow();
        assertThat(claim.kind()).isEqualTo(expected);
        return claim;
    }

    private static void initializeRepository(Path root)
            throws IOException, InterruptedException
    {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "test@bytequay.invalid");
        Files.writeString(
                root.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
        git(root, "add", "base.txt");
        git(root, "commit", "-m", "base");
        git(root, "remote", "add", "origin",
                "https://github.com/octocat/bytequay.git");
        String head = git(root, "rev-parse", "HEAD").strip();
        git(root, "update-ref", "refs/remotes/origin/main", head);
        git(root, "symbolic-ref", "refs/remotes/origin/HEAD",
                "refs/remotes/origin/main");
    }

    private static String git(Path root, String... arguments)
            throws IOException, InterruptedException
    {
        String[] command = new String[arguments.length + 3];
        command[0] = "/usr/bin/git";
        command[1] = "-C";
        command[2] = root.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        private MutableClock(Instant now)
        {
            this.now = now;
        }

        private void advance(Duration duration)
        {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
