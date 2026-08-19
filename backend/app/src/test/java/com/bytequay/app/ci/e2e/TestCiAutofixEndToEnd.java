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
package com.bytequay.app.ci.e2e;

import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.ci.CiAutofixMonitorTestDriver;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiMemoryService;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.ci.CiAutofixAttempt.CiResult;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.CredentialStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "bytequay.agent.codex-enabled=false",
                "bytequay.agent.claude-enabled=false",
                "bytequay.agent.deterministic-enabled=false",
                "bytequay.agent.desktop-session-token=" + CiAutofixE2eHarness.SESSION,
                "bytequay.ci-autofix.monitor-initial-delay=PT1H",
                "logging.level.com.bytequay.app.ci.DefaultCiAutofixService=OFF"
        })
@AutoConfigureMockMvc
@Import({TestCiAutofixEndToEnd.E2eConfiguration.class, CiAutofixMonitorTestDriver.class})
@Execution(ExecutionMode.SAME_THREAD)
class TestCiAutofixEndToEnd
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-ci-e2e-" + UUID.randomUUID() + ".db").toString();
    private static final String RUNTIME_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-ci-e2e-runtime-" + UUID.randomUUID()).toString();

    @TempDir
    private Path temporaryDirectory;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CiAutofixService service;

    @Autowired
    private CiAutofixMonitorTestDriver monitor;

    @Autowired
    private ScriptedGitHubActionsClient gitHub;

    @Autowired
    private ScriptedAgentDriver agent;

    @Autowired
    private AgentTaskService tasks;

    @Autowired
    private AgentTurnService turns;

    @Autowired
    private AgentEventService events;

    @Autowired
    private AgentResourceService resources;

    @Autowired
    private PullRequestJpaRepository pullRequests;

    @Autowired
    private PrDetailStore details;

    @Autowired
    private Clock clock;

    private CiAutofixE2eHarness harness;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("bytequay.agent.runtime-root", () -> RUNTIME_PATH);
    }

    @BeforeEach
    void setUp()
    {
        gitHub.reset();
        agent.reset();
        harness = new CiAutofixE2eHarness(
                mvc,
                objectMapper,
                service,
                monitor,
                gitHub,
                agent,
                tasks,
                turns,
                events,
                resources,
                pullRequests,
                details,
                clock);
    }

    @AfterEach
    void tearDown()
    {
        harness.close();
    }

    @Test
    void startsThroughHttpAndManualMonitorTickWithoutPublishing()
            throws Exception
    {
        CiAutofixE2eHarness.Fixture fixture = harness.createFixture(temporaryDirectory, "smoke");
        harness.scriptFailingCi(fixture, true);

        CiAutofixE2eHarness.StartResult started = harness.start(fixture, "smoke-start");

        assertThat(started.response().required("status").textValue()).isEqualTo("ACTIVE");
        assertThat(started.response().required("activity").textValue()).isEqualTo("FIXING");
        assertThat(started.response().required("attemptsUsed").intValue()).isEqualTo(1);
        CiAutofixRun run = harness.run(started.runId());
        assertThat(run.agentTaskId()).isPresent();
        assertThat(run.currentTurnId()).isPresent();
        assertThat(agent.turns()).hasSize(1);
        assertThat(gitHub.logCalls()).hasSize(1);
        Path log = resources.require(TaskId.valueOf(run.agentTaskId().orElseThrow()))
                .runtimeDirectory()
                .resolve(run.attempts().getFirst().failures().getFirst().log().orElseThrow().path());
        assertThat(Files.isRegularFile(log)).isTrue();
        assertThat(fixture.repository().remoteHead()).isEqualTo(fixture.repository().initialHeadSha());
        assertThat(fixture.repository().status()).isEmpty();

        int inspectionsBeforeTick = gitHub.inspectCalls().size();
        harness.tick();

        assertThat(gitHub.inspectCalls()).hasSize(inspectionsBeforeTick + 1);
        assertThat(gitHub.logCalls()).hasSize(1);
        assertThat(harness.run(started.runId()).currentTurnId()).isPresent();
        assertThat(fixture.repository().remoteHead()).isEqualTo(fixture.repository().initialHeadSha());
        CiAutofixE2eHarness.Snapshot snapshot = harness.snapshot(started.runId());
        assertThat(snapshot.recentEventKinds()).contains("LIFECYCLE");
        assertThat(snapshot.runtimeFiles()).anyMatch(path -> path.endsWith("events.jsonl"));
    }

    @Test
    void fixesTwoFailuresAndCompletesGreen()
            throws Exception
    {
        CiAutofixE2eHarness.Fixture fixture = harness.createFixture(temporaryDirectory, "green");
        E2eGitRepository repository = fixture.repository();
        assertThat(repository.commitSubjects())
                .containsExactly("First change", "Target change", "Last change");
        harness.scriptPrimaryJourney(fixture);
        agent.enqueueCompleted(
                repository.pullRequest(),
                repository.worktree(),
                "CI is green. Root cause: a generated value was missing. "
                        + "Attempt: added the compile repair. Local check: passed.",
                ignored -> repository.createPositionedFixup("ci-fix.txt", "compile repaired\n"),
                "compile-local-check",
                Optional.empty(),
                Optional.of("ci-fix.txt"));
        agent.enqueueCompleted(
                repository.pullRequest(),
                repository.worktree(),
                "Root cause: the test expected the repaired value. "
                        + "Attempt: folded the test repair into the fixup. Local check: passed.",
                ignored -> repository.foldPositionedFixup(
                        "ci-fix.txt",
                        "compile repaired\ntest repaired\n"),
                "test-local-check",
                Optional.of("compile"),
                Optional.of("ci-fix.txt"));

        String idempotencyKey = "green-start";
        CiAutofixE2eHarness.StartResult started = harness.start(fixture, idempotencyKey);
        CiAutofixRun initial = harness.run(started.runId());
        Path log = resources.require(TaskId.valueOf(initial.agentTaskId().orElseThrow()))
                .runtimeDirectory()
                .resolve(initial.attempts().getFirst().failures().getFirst().log().orElseThrow().path());
        assertThat(Files.readString(log)).isEqualTo(CiAutofixE2eHarness.RAW_COMPILE_LOG);
        Path realLog = log.toRealPath();
        assertThat(agent.turns()).singleElement().satisfies(turn ->
                assertThat(turn.attachments()).singleElement().satisfies(attachment ->
                        assertThat(attachment.path()).isEqualTo(realLog)));
        assertThat(gitHub.logCalls()).hasSize(1);
        assertThat(initial.attempts()).hasSize(1);

        CiAutofixRun firstPublished = harness.driveUntil(
                started.runId(),
                run -> run.attempts().getFirst().pushedSha().isPresent(),
                10);
        String firstPushedSha = firstPublished.attempts().getFirst().pushedSha().orElseThrow();
        assertThat(repository.remoteHead()).isEqualTo(firstPushedSha);
        assertThat(repository.status()).isEmpty();
        assertThat(repository.commitSubjects()).containsExactly(
                "First change",
                "Target change",
                "fixup! Target change",
                "Last change");
        harness.tick();
        assertThat(harness.run(started.runId()).attempts().getFirst().timelineEventKey()).isPresent();

        harness.scriptTestFailure(fixture, firstPushedSha);
        CiAutofixRun secondPublished = harness.driveUntil(
                started.runId(),
                run -> run.attempts().size() == 2 && run.attempts().getLast().pushedSha().isPresent(),
                12);
        String secondPushedSha = secondPublished.attempts().getLast().pushedSha().orElseThrow();
        assertThat(secondPublished.attempts().getFirst().observedCiResult())
                .isEqualTo(CiResult.FAILED);
        assertThat(secondPublished.attempts().getLast().inputSha()).isEqualTo(firstPushedSha);
        assertThat(agent.memoryReads()).singleElement().satisfies(read -> {
            assertThat(read.query()).contains("compile");
            assertThat(read.result().matchedSections()).isPositive();
            assertThat(read.result().markdown()).contains("Backend compile");
        });
        assertThat(repository.remoteHead()).isEqualTo(secondPushedSha);
        assertThat(repository.status()).isEmpty();
        assertThat(repository.commitSubjects()).containsExactly(
                "First change",
                "Target change",
                "fixup! Target change",
                "Last change");
        assertThat(repository.commitSubjects().stream()
                .filter(subject -> subject.equals("fixup! Target change")))
                .hasSize(1);

        harness.scriptGreenCi(fixture, secondPushedSha);
        CiAutofixRun checked = harness.driveUntil(
                started.runId(),
                run -> run.finalFixupCheck().isPresent(),
                10);
        assertThat(checked.finalFixupCheck()).hasValueSatisfying(check -> {
            assertThat(check.headSha()).isEqualTo(secondPushedSha);
            assertThat(check.result()).isEqualTo(CiAutofixRun.CheckResult.VALID);
        });
        TaskId taskId = TaskId.valueOf(checked.agentTaskId().orElseThrow());
        assertThat(tasks.get(taskId).state()).isNotEqualTo(TaskState.COMPLETED);

        harness.tick();
        CiAutofixRun finished = harness.run(started.runId());
        assertThat(finished.status()).isEqualTo(CiAutofixRun.Status.FINISHED);
        assertThat(finished.finishReason()).contains(CiAutofixRun.FinishReason.GREEN);
        assertThat(tasks.get(taskId).state()).isEqualTo(TaskState.COMPLETED);
        assertThat(finished.attempts()).hasSize(2);
        assertThat(finished.attempts().getFirst().inputSha()).isEqualTo(repository.initialHeadSha());
        assertThat(finished.attempts().getFirst().expectedRemoteSha()).contains(repository.initialHeadSha());
        assertThat(finished.attempts().getFirst().pushedSha()).contains(firstPushedSha);
        assertThat(finished.attempts().getLast().expectedRemoteSha()).contains(firstPushedSha);
        assertThat(finished.attempts().getLast().pushedSha()).contains(secondPushedSha);
        assertThat(finished.attempts().getLast().observedCiResult())
                .isEqualTo(CiResult.PASSED);
        assertThat(finished.attempts().getFirst().failures().getFirst().signature())
                .isNotEqualTo(finished.attempts().getLast().failures().getFirst().signature());
        assertThat(finished.warnings()).isEmpty();
        assertThat(agent.gitActions()).extracting(ScriptedAgentDriver.GitAction::name)
                .containsExactly("compile-local-check", "test-local-check");

        String memory = Files.readString(resources.require(taskId).runtimeDirectory().resolve("ci-memory.md"));
        assertThat(memory)
                .contains("Backend compile", "Backend tests", "ci-fix.txt", "FAILED", "PASSED")
                .contains("generated value was missing", "test expected the repaired value")
                .doesNotContain("RAW_COMPILE_LOG_SENTINEL", "RAW_TEST_LOG_SENTINEL");
        assertThat(events.readAfter(taskId, 0, 100)).extracting(event -> event.event().kind().name())
                .contains("LIFECYCLE", "COMMAND", "FILE", "USAGE", "MESSAGE");

        List<String> timelineKeys = details.find(fixture.prId()).orElseThrow().timeline().stream()
                .filter(event -> event.localEventKey().isPresent())
                .map(event -> event.localEventKey().orElseThrow())
                .toList();
        assertThat(timelineKeys).containsExactlyInAnyOrderElementsOf(finished.attempts().stream()
                .map(attempt -> attempt.timelineEventKey().orElseThrow())
                .toList());
        assertThat(timelineKeys).hasSize(2);

        JsonNode response = harness.getResponse(started.runId());
        assertThat(response.required("attempts")).hasSize(2);
        assertThat(response.required("finalCheck").required("result").textValue()).isEqualTo("VALID");
        assertThat(response.required("metrics").required("inputTokens").longValue()).isPositive();
        assertThat(response.required("metrics").required("outputTokens").longValue()).isPositive();
        assertThat(response.toString())
                .doesNotContain("RAW_COMPILE_LOG_SENTINEL", "RAW_TEST_LOG_SENTINEL");

        int turnCount = agent.turns().size();
        int logCount = gitHub.logCalls().size();
        int inspectCount = gitHub.inspectCalls().size();
        CiAutofixE2eHarness.StartResult repeated = harness.start(fixture, idempotencyKey);
        assertThat(repeated.runId()).isEqualTo(started.runId());
        assertThat(service.list(200).stream()
                .filter(run -> run.scope().pullRequest().equals(repository.pullRequest())))
                .singleElement();
        assertThat(agent.turns()).hasSize(turnCount);
        assertThat(gitHub.logCalls()).hasSize(logCount);
        assertThat(gitHub.inspectCalls()).hasSize(inspectCount);
        assertThat(details.find(fixture.prId()).orElseThrow().timeline()).hasSize(2);
        assertThat(repository.remoteHead()).isEqualTo(secondPushedSha);

        var checkedAt = finished.finalFixupCheck().orElseThrow().checkedAt();
        harness.tick();
        assertThat(harness.run(started.runId()).finalFixupCheck().orElseThrow().checkedAt())
                .isEqualTo(checkedAt);
    }

    @Test
    void rejectsMissingDesktopSessionBeforeStartingAServiceRun()
            throws Exception
    {
        CiAutofixE2eHarness.Fixture fixture = harness.createFixture(temporaryDirectory, "session");
        int runsBefore = service.list(200).size();

        assertThat(harness.startWithoutSession(fixture)).isEqualTo(401);

        assertThat(service.list(200)).hasSize(runsBefore);
        assertThat(gitHub.inspectCalls()).isEmpty();
        assertThat(agent.turns()).isEmpty();
    }

    @Test
    void identifiesUnscriptedGitHubAndAgentCalls()
            throws Exception
    {
        PullRequestRef unknown = PullRequestRef.of("bytequay", "unknown", 91);
        assertThatThrownBy(() -> gitHub.inspect(new InspectCiHeadQuery(unknown)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unknown.fullName());

        CiAutofixE2eHarness.Fixture fixture = harness.createFixture(temporaryDirectory, "unscripted-agent");
        harness.scriptFailingCi(fixture, false);

        CiAutofixE2eHarness.StartResult started = harness.start(fixture, "unscripted-agent-start");
        CiAutofixRun run = harness.run(started.runId());

        assertThat(run.status()).isEqualTo(CiAutofixRun.Status.PAUSED);
        assertThat(run.summary()).hasValueSatisfying(summary -> assertThat(summary)
                .contains(fixture.repository().pullRequest().fullName())
                .contains(fixture.repository().worktree().toString()));
        assertThat(fixture.repository().remoteHead()).isEqualTo(fixture.repository().initialHeadSha());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class E2eConfiguration
    {
        @Bean
        @Primary
        ScriptedGitHubActionsClient scriptedGitHubActionsClient(TaskLogStore logs)
        {
            return new ScriptedGitHubActionsClient(logs);
        }

        @Bean
        ScriptedAgentDriver scriptedAgentDriver(CiMemoryService memory)
        {
            return new ScriptedAgentDriver(memory);
        }

        @Bean
        @Primary
        CredentialService e2eCredentialService(CredentialStore store, GhCliService ghCli)
        {
            return new CredentialService(store, ghCli)
            {
                @Override
                public String resolveGitHubToken()
                {
                    return "test-token";
                }
            };
        }

        @Bean
        @Primary
        GitHubAccountClient e2eGitHubAccountClient(
                RestClient gitHubRestClient,
                @Qualifier("gitHubGraphQLRestClient") RestClient gitHubGraphQLRestClient)
        {
            return new GitHubAccountClient(gitHubRestClient, gitHubGraphQLRestClient)
            {
                @Override
                public UserProfile fetchUserProfile(String pat)
                {
                    return new UserProfile("author", "Author", "", "", 0, 0, 0, "", "", "", "");
                }
            };
        }
    }
}
