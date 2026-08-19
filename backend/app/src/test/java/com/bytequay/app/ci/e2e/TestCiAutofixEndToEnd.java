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
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.ci.CiAutofixMonitorTestDriver;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiMemoryService;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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

    @MockitoBean
    private CredentialService credentials;

    @MockitoBean
    private GitHubAccountClient accounts;

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
        when(credentials.resolveGitHubToken()).thenReturn("test-token");
        when(accounts.fetchUserProfile("test-token")).thenReturn(new UserProfile(
                "author", "Author", "", "", 0, 0, 0, "", "", "", ""));
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
    }
}
