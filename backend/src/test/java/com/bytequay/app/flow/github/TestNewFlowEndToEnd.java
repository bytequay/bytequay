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
package com.bytequay.app.flow.github;

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixRecords.LearningCompletionState;
import com.bytequay.app.flow.ci.CiAutofixRecords.PolicyResolution;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.ci.CiLearningCoordinator;
import com.bytequay.app.flow.ci.CiObservationCoordinator;
import com.bytequay.app.flow.gate.UserGateRecords.UserGate;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.runtime.CiAutofixDispatcher;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.LocalChecks;
import com.bytequay.app.flow.runtime.LocalChecks.ProfileDefinition;
import com.bytequay.app.flow.runtime.NewFlowAgentBridgeConfiguration;
import com.bytequay.app.flow.runtime.NewFlowAgentLaunches;
import com.bytequay.app.flow.runtime.NewFlowConfiguration;
import com.bytequay.app.flow.runtime.NewFlowEngineResolver;
import com.bytequay.app.flow.runtime.TaskCommands;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.workmodel.WorkModelService;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.FAILED_ACTIONS;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.CiObservationMode.GREEN;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.ciObservationExecutor;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.ciUpdateLane;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialLane;
import static com.bytequay.app.flow.github.GitHubProviderFixtures.initialRepositoryObservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class TestNewFlowEndToEnd
{
    private static final Duration TTL = Duration.ofMinutes(30);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void productionCompositionRunsInitialRedRepairCiUpdateGreenAndLearning()
            throws Exception
    {
        Path repository = temporaryDirectory.resolve("repository");
        Path worktrees = temporaryDirectory.resolve("worktrees");
        Path database = temporaryDirectory.resolve("new-flow.db");
        Path primaryDatabase = temporaryDirectory.resolve("primary.db");
        initializeRepository(repository);

        DataSource primary = new DriverManagerDataSource(
                "jdbc:sqlite:" + primaryDatabase);
        new JdbcTemplate(primary).execute(
                "CREATE TABLE legacy_owner (value TEXT NOT NULL)");
        new JdbcTemplate(primary).update(
                "INSERT INTO legacy_owner VALUES ('unchanged')");
        byte[] primaryBefore = Files.readAllBytes(primaryDatabase);

        WatchedRepoStore watched = mock(WatchedRepoStore.class);
        when(watched.find("octocat", "bytequay")).thenReturn(Optional.of(
                new WatchedRepo(
                        1, "octocat", "bytequay", 0,
                        repository.toString(), null, null)));
        CredentialStore credentials = credentials();
        TurnRunner runner = mock(TurnRunner.class);
        NewFlowEngineResolver engines = mock(NewFlowEngineResolver.class);
        when(engines.resolve(any(FlowRuntimeRecords.AgentRun.class))).thenReturn(
                NewFlowAgentLaunches.Config.api(
                        "openai", TurnSpec.Transport.OPENAI_COMPAT,
                        "https://models.example.test/v1/chat/completions",
                        "test-model", "medium", "openai", "default api",
                        1024, 2));
        CountDownLatch reviewerEntered = new CountDownLatch(1);
        CountDownLatch policyReady = new CountDownLatch(1);
        AtomicInteger turns = new AtomicInteger();
        when(runner.runTurn(any(), any(), any())).thenAnswer(invocation -> {
            ToolExecutor tools = invocation.getArgument(1);
            return runModelTurn(
                    turns.incrementAndGet(), tools,
                    reviewerEntered, policyReady);
        });

        AtomicReference<FlowRuntime> runtimeRef = new AtomicReference<>();
        GitHubInitialRepositoryObserver initialObserver = mock(
                GitHubInitialRepositoryObserver.class);
        when(initialObserver.observe(anyString())).thenAnswer(invocation ->
                initialRepositoryObservation(
                        runtimeRef.get(), invocation.getArgument(0),
                        "101", "101", "repo-secret".toCharArray(),
                        new AtomicInteger()));

        new ApplicationContextRunner()
                .withBean("legacyPrimaryDataSource", DataSource.class,
                        () -> primary,
                        definition -> definition.setPrimary(true))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WatchedRepoStore.class, () -> watched)
                .withBean(CredentialStore.class, () -> credentials)
                .withBean(TurnRunner.class, () -> runner)
                // The engine is workspace configuration in production; this
                // trace is about the flow, so it pins one resolved engine
                // instead of seeding settings rows.
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(primary))
                .withBean(WorkspaceEngineSettings.class,
                        () -> mock(WorkspaceEngineSettings.class))
                .withBean(WorkModelService.class, () -> mock(WorkModelService.class))
                .withBean(
                        "testEngineResolver",
                        NewFlowEngineResolver.class,
                        () -> engines,
                        definition -> definition.setPrimary(true))
                .withBean(
                        "testInitialRepositoryObserver",
                        GitHubInitialRepositoryObserver.class,
                        () -> initialObserver,
                        definition -> definition.setPrimary(true))
                .withPropertyValues(
                        "bytequay.new-flow.database-path=" + database,
                        "bytequay.new-flow.worktree-root=" + worktrees)
                .withUserConfiguration(
                        NewFlowConfiguration.class,
                        NewFlowAgentBridgeConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FlowRuntime runtime = context.getBean(FlowRuntime.class);
                    runtimeRef.set(runtime);
                    context.getBean(GitHubInitialPublishDispatcher.class)
                            .close();
                    context.getBean(GitHubCiUpdateDispatcher.class).close();
                    context.getBean(GitHubCiObservationDispatcher.class)
                            .close();

                    LocalChecks checks = context.getBean(LocalChecks.class);
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(
                            "newFlowDataSource", DataSource.class));
                    checks.recordPolicy(
                            "octocat/bytequay", null,
                            "e2e-local-policy:v1",
                            "e2e-local-policy-digest:v1",
                            List.of(new ProfileDefinition(
                                    "compile", List.of("/usr/bin/true"), ".",
                                    List.of(), Duration.ofSeconds(5),
                                    List.of(
                                            GateIntent.INITIAL_PUBLISH,
                                            GateIntent.CI_UPDATE))));

                    TaskCommands commands = context.getBean(TaskCommands.class);
                    Task task = commands.startTask(
                            "e2e-request", "octocat/bytequay",
                            "Implement the exact end-to-end change");
                    assertThat(commands.startTask(
                            "e2e-request", "octocat/bytequay",
                            "Implement the exact end-to-end change"))
                            .isEqualTo(task);
                    assertThatThrownBy(() -> commands.startTask(
                            "e2e-request", "octocat/bytequay",
                            "Conflicting goal"))
                            .isInstanceOf(IllegalStateException.class);

                    await(reviewerEntered);
                    Task withPr = awaitValue(() -> runtime.task(task.taskId())
                            .filter(current -> current.prId() != null));
                    PullRequestSubject localPr = runtime.pullRequest(
                            withPr.prId()).orElseThrow();
                    CiAutofix autofix = context.getBean(CiAutofix.class);
                    autofix.recordPolicy(
                            task.repositoryId(), localPr.scopeKey(),
                            localPr.targetBaseRef(), "e2e-required-ci:v1",
                            "e2e-required-ci-digest:v1",
                            PolicyResolution.RESOLVED, null,
                            List.of("GITHUB_CHECK:7:build"),
                            List.of("SUCCESS"));
                    policyReady.countDown();

                    UserGates gates = context.getBean(UserGates.class);
                    UserGate initialGate = awaitValue(() ->
                            gates.initialGate(localPr.prId()));
                    DisplayedGate initialDisplay = displayed(
                            jdbc, initialGate);
                    var initialAuthorization = gates.authorizeInitialPublish(
                            initialGate.gateId(), initialDisplay.revision(),
                            initialDisplay.subjectDigest(),
                            initialDisplay.actionDigest(),
                            "e2e-initial-authorization");
                    GitHubEffects effects = context.getBean(
                            GitHubEffects.class);
                    var initialPlan = effects.initialPublishPlan(
                            initialAuthorization.planId()).orElseThrow();
                    Clock clock = context.getBean(
                            "newFlowClock", Clock.class);
                    var initialLane = initialLane(
                            runtime, gates, effects, initialPlan, clock);
                    try (var dispatcher =
                            new GitHubInitialPublishDispatcher(
                                    runtime, gates, effects,
                                    initialLane.executor(),
                                    new GitHubInitialPublishDispatcher.Config(
                                            "e2e-initial-publisher", TTL,
                                            Duration.ofMillis(10), 1))) {
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                    }
                    assertThat(initialLane.pushes()).hasValue(1);
                    assertThat(initialLane.posts()).hasValue(1);
                    assertThat(effects.initialPublishStepReceipts(
                            initialPlan.planId())).hasSize(2);

                    CiObservationCoordinator coordinator = context.getBean(
                            CiObservationCoordinator.class);
                    CiLearningCoordinator learning = context.getBean(
                            CiLearningCoordinator.class);
                    CiAutofixDispatcher agents = context.getBean(
                            CiAutofixDispatcher.class);
                    try (var dispatcher = new GitHubCiObservationDispatcher(
                            runtime,
                            ciObservationExecutor(
                                    runtime, coordinator, localPr.prId(),
                                    clock, FAILED_ACTIONS),
                            agents, "e2e-red-observer", TTL,
                            Duration.ofMillis(10), 1)) {
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                    }
                    PullRequestSubject published = runtime.pullRequest(
                            localPr.prId()).orElseThrow();
                    var policy = autofix.currentPolicy(
                            published.repositoryId(), published.scopeKey())
                            .orElseThrow();
                    var red = autofix.round(
                            published.prId(), published.currentRemoteHead(),
                            policy.policyRevisionId()).orElseThrow();
                    assertThat(red.state()).isEqualTo(RoundState.QUEUED);

                    UserGate ciGate = awaitValue(() ->
                            gates.gate(localPr.prId()));
                    DisplayedGate ciDisplay = displayed(jdbc, ciGate);
                    var ciAuthorization = gates.authorizeCiUpdate(
                            ciGate.gateId(), ciDisplay.revision(),
                            ciDisplay.subjectDigest(),
                            ciDisplay.actionDigest(),
                            "e2e-ci-update-authorization");
                    var updateLane = ciUpdateLane(
                            runtime, gates, effects,
                            effects.steps(ciAuthorization.planId()).getFirst(),
                            clock);
                    try (var dispatcher = new GitHubCiUpdateDispatcher(
                            runtime, gates, effects, updateLane.executor(),
                            new GitHubCiUpdateDispatcher.Config(
                                    "e2e-ci-update-publisher", TTL,
                                    Duration.ofMillis(10), 1))) {
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                    }
                    assertThat(updateLane.pushes()).hasValue(1);

                    try (var dispatcher = new GitHubCiObservationDispatcher(
                            runtime,
                            ciObservationExecutor(
                                    runtime, coordinator, localPr.prId(),
                                    clock, GREEN),
                            agents, "e2e-green-observer", TTL,
                            Duration.ofMillis(10), 1)) {
                        assertThat(dispatcher.dispatchOnce()).isTrue();
                    }
                    PullRequestSubject repaired = runtime.pullRequest(
                            localPr.prId()).orElseThrow();
                    var green = autofix.round(
                            repaired.prId(), repaired.currentRemoteHead(),
                            policy.policyRevisionId()).orElseThrow();
                    assertThat(green.state()).isEqualTo(RoundState.GREEN);
                    agents.wake();

                    awaitCondition(() -> jdbc.queryForObject(
                            "SELECT COUNT(*) FROM flow_ci_lesson",
                            Integer.class) == 1);
                    String learningOperation = jdbc.queryForObject(
                            "SELECT learning_operation_id FROM flow_ci_lesson",
                            String.class);
                    var completion = learning.learningCompletion(
                            learningOperation).orElseThrow();
                    assertThat(completion.state()).isEqualTo(
                            LearningCompletionState.CANDIDATE);
                    assertThat(learning.lesson(completion.lessonId()))
                            .hasValueSatisfying(lesson -> {
                                assertThat(lesson.title())
                                        .isEqualTo("Exact CI repair");
                                assertThat(lesson.markdown())
                                        .contains("failed build");
                            });
                    assertThat(turns).hasValue(8);
                    assertThat(jdbc.queryForList(
                            "SELECT name FROM sqlite_master WHERE type='table'",
                            String.class))
                            .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains("harness"))
                            .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains("remote_ci_repair"));
                    assertThat(List.of(context.getBeanDefinitionNames()))
                            .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains("harness"))
                            .noneMatch(name -> name.toLowerCase(Locale.ROOT)
                                    .contains("remotecirepair"));
                });

        assertThat(Files.readAllBytes(primaryDatabase))
                .containsExactly(primaryBefore);
    }

    private static CredentialStore credentials()
    {
        CredentialStore credentials = mock(CredentialStore.class);
        Credential credential = new Credential(
                7, CredentialType.AI, "openai", "default api",
                "test", "***", null, true, null,
                Instant.parse("2026-08-12T00:00:00.123456789Z"),
                Instant.parse("2026-08-12T00:00:01.123456789Z"), null);
        when(credentials.find(
                CredentialType.AI, "openai", "default api"))
                .thenReturn(Optional.of(credential));
        when(credentials.getSecret(
                CredentialType.AI, "openai", "default api"))
                .thenReturn(Optional.of("ai-secret"));
        return credentials;
    }

    private static TurnResult runModelTurn(
            int turn,
            ToolExecutor tools,
            CountDownLatch reviewerEntered,
            CountDownLatch policyReady)
            throws Exception
    {
        switch (turn) {
            case 1 -> {
                call(tools, "read_initial_task_context", "{}");
                call(tools, "write_file",
                        "{\"path\":\"initial.txt\","
                                + "\"content\":\"initial change\\n\"}");
                call(tools, "commit_initial_change", "{}");
                call(tools, "run_checks",
                        "{\"command\":[\"/usr/bin/true\"],"
                                + "\"working_directory\":\".\"}");
                call(tools, "request_initial_review",
                        "{\"title\":\"Initial change\","
                                + "\"body\":\"Exact initial work\"}");
                return result(TurnResult.End.INTERRUPTED);
            }
            case 2 -> {
                call(tools, "read_diff", "{}");
                reviewerEntered.countDown();
                await(policyReady);
                return result(TurnResult.End.COMPLETED);
            }
            case 3 -> {
                call(tools, "read_initial_review_context", "{}");
                call(tools, "read_candidate_diff", "{}");
                call(tools, "ready_for_initial_publish", "{}");
                return result(TurnResult.End.INTERRUPTED);
            }
            case 4 -> {
                call(tools, "read_ci_failure_context", "{}");
                call(tools, "read_ci_log", "{\"index\":0,\"offset\":0}");
                call(tools, "write_file",
                        "{\"path\":\"repair.txt\","
                                + "\"content\":\"fixed build\\n\"}");
                call(tools, "commit_repair", "{}");
                return result(TurnResult.End.COMPLETED);
            }
            case 5 -> {
                call(tools, "read_ci_fix_context", "{}");
                call(tools, "read_candidate_diff", "{}");
                call(tools, "run_checks",
                        "{\"command\":[\"/usr/bin/true\"],"
                                + "\"working_directory\":\".\"}");
                call(tools, "spawn_adversarial_reviewer", "{}");
                return result(TurnResult.End.INTERRUPTED);
            }
            case 6 -> {
                call(tools, "read_diff", "{}");
                return result(TurnResult.End.COMPLETED);
            }
            case 7 -> {
                call(tools, "read_ci_fix_context", "{}");
                call(tools, "read_candidate_diff", "{}");
                call(tools, "ready_for_review", "{}");
                return result(TurnResult.End.INTERRUPTED);
            }
            case 8 -> {
                call(tools, "read_repair_evidence", "{}");
                call(tools, "read_ci_log", "{\"index\":0,\"offset\":0}");
                call(tools, "save_ci_lesson",
                        "{\"title\":\"Exact CI repair\","
                                + "\"markdown\":\"The failed build was fixed.\"}");
                return result(TurnResult.End.INTERRUPTED);
            }
            default -> throw new AssertionError(
                    "unexpected model turn " + turn);
        }
    }

    private static void call(
            ToolExecutor tools, String name, String arguments)
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor.ToolCallResult result = tools.execute(new ToolCall(
                "call-" + name, name, arguments,
                mapper.readTree(arguments)));
        assertThat(result.isError())
                .as(name + ": " + result.text())
                .isFalse();
    }

    private static TurnResult result(TurnResult.End end)
    {
        return new TurnResult("opaque", 1, 1, 0, 1, end);
    }

    private static DisplayedGate displayed(
            JdbcTemplate jdbc, UserGate gate)
    {
        return jdbc.queryForObject(
                "SELECT revision, subject_digest, action_digest "
                        + "FROM flow_user_gate_revision "
                        + "WHERE gate_id = ? AND revision = ?",
                (result, row) -> new DisplayedGate(
                        result.getLong("revision"),
                        result.getString("subject_digest"),
                        result.getString("action_digest")),
                gate.gateId(), gate.currentRevision());
    }

    private record DisplayedGate(
            long revision, String subjectDigest, String actionDigest) {}

    private static <T> T awaitValue(Supplier<Optional<T>> supplier)
    {
        AtomicReference<T> value = new AtomicReference<>();
        awaitCondition(() -> {
            Optional<T> candidate = supplier.get();
            candidate.ifPresent(value::set);
            return candidate.isPresent();
        });
        return value.get();
    }

    // Wall-clock budgets, not behaviour: four forks share this machine, and a
    // whole-composition trace that runs in 15 seconds alone can take three
    // times that beside its siblings. Too tight and the timeout reports a
    // failure the flow never had.
    private static final Duration AWAIT_BUDGET = Duration.ofSeconds(60);

    private static void await(CountDownLatch latch)
    {
        try {
            if (!latch.await(AWAIT_BUDGET.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("latch did not complete");
            }
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitCondition(CheckedCondition condition)
    {
        long deadline = System.nanoTime() + AWAIT_BUDGET.toNanos();
        try {
            while (!condition.test()) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(
                            "condition did not become true");
                }
                Thread.sleep(10);
            }
        }
        catch (Exception failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new AssertionError(failure);
        }
    }

    @FunctionalInterface
    private interface CheckedCondition
    {
        boolean test()
                throws Exception;
    }

    private static void initializeRepository(Path root)
            throws IOException, InterruptedException
    {
        Files.createDirectories(root);
        git(root, "init", "-b", "main");
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "test@bytequay.invalid");
        Files.writeString(
                root.resolve("base.txt"), "base\n",
                StandardCharsets.UTF_8);
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
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }
}
