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
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.ByteQuayApplication;
import com.bytequay.app.ci.CheckFixupPositionsCommand;
import com.bytequay.app.ci.CiAutofixMonitorTestDriver;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiBaseSynchronization;
import com.bytequay.app.ci.CiGitInspection;
import com.bytequay.app.ci.CiGitOperations;
import com.bytequay.app.ci.CiGitService;
import com.bytequay.app.ci.CiMemoryService;
import com.bytequay.app.ci.CiMemoryView;
import com.bytequay.app.ci.CiPushResult;
import com.bytequay.app.ci.FixupPositionCheck;
import com.bytequay.app.ci.InspectAgentResultCommand;
import com.bytequay.app.ci.PushCiHeadCommand;
import com.bytequay.app.ci.ReadCiMemoryCommand;
import com.bytequay.app.ci.SynchronizeCiBaseCommand;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.app.web.agent.AgentRequestFilter;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.CredentialStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
class TestCiAutofixRestartEndToEnd
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void reconcilesAPushWhoseResponseWasLost()
            throws Exception
    {
        Path database = temporaryDirectory.resolve("restart.db");
        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Scripts scripts;
        CiAutofixE2eHarness.Fixture fixture;
        CiAutofixRunId runId;
        TaskId taskId;
        String turnId;
        String pushedSha;
        Path rawLog;
        String agentNote;
        String memoryBeforeRestart;
        List<Long> eventSequences;
        TaskMetrics metricsBeforeRestart;
        int branchWrites;

        try (ConfigurableApplicationContext first = openContext(
                database,
                runtimeRoot,
                "create",
                Optional.empty(),
                true)) {
            scripts = new Scripts(
                    first.getBean(ScriptedGitHubActionsClient.class),
                    first.getBean(ScriptedAgentDriver.class));
            CiAutofixE2eHarness harness = harness(first, scripts);
            fixture = harness.createFixture(temporaryDirectory.resolve("git"), "restart");
            E2eGitRepository repository = fixture.repository();
            harness.scriptPrimaryJourney(fixture);
            scripts.agent().enqueueCompleted(
                    repository.pullRequest(),
                    repository.worktree(),
                    "Root cause: compilation missed a generated value. The fixup is ready.",
                    ignored -> repository.createPositionedFixup("restart-fix.txt", "fixed\n"),
                    "restart-local-check",
                    Optional.of("compile"),
                    Optional.of("restart-fix.txt"));
            branchWrites = repository.headRefUpdates();

            CiAutofixE2eHarness.StartResult started = harness.start(fixture, "restart-start");
            runId = started.runId();
            CiAutofixRun uncertain = driveUntilRemoteContainsJournal(first, repository, runId, 8);
            taskId = TaskId.valueOf(uncertain.agentTaskId().orElseThrow());
            turnId = uncertain.attempts().getFirst().agentTurnId().orElseThrow();
            pushedSha = uncertain.pushJournal().orElseThrow().localHeadSha();
            Path runtimeDirectory = first.getBean(AgentResourceService.class).require(taskId).runtimeDirectory();
            rawLog = runtimeDirectory
                    .resolve(uncertain.attempts().getFirst().failures().getFirst().log().orElseThrow().path());
            agentNote = uncertain.attempts().getFirst().untrustedAgentNote().orElseThrow();
            memoryBeforeRestart = Files.readString(runtimeDirectory.resolve("ci-memory.md"));
            eventSequences = first.getBean(AgentEventService.class).readAfter(taskId, 0, 100).stream()
                    .map(event -> event.sequence())
                    .collect(toImmutableList());
            metricsBeforeRestart = first.getBean(AgentMetricsService.class).taskTotals(taskId);

            assertThat(first.getBean(LostPushCiGitService.class).pushCalls()).isEqualTo(1);
            assertThat(uncertain.pushJournal()).isPresent();
            assertThat(uncertain.attempts().getFirst().pushedSha()).isEmpty();
            assertThat(repository.remoteHead()).isEqualTo(pushedSha);
            assertThat(repository.headRefUpdates()).isEqualTo(branchWrites + 1);
            assertThat(first.getBean(PrDetailStore.class).find(fixture.prId()).orElseThrow().timeline()).isEmpty();
            assertThat(Files.readString(rawLog)).isEqualTo(CiAutofixE2eHarness.RAW_COMPILE_LOG);
            assertThat(memoryBeforeRestart).contains("Backend compile", "restart-fix.txt");
            assertThat(eventSequences).isNotEmpty();
            assertThat(metricsBeforeRestart.inputTokens()).isPositive();
            assertThat(metricsBeforeRestart.outputTokens()).isPositive();
            assertThat(scripts.agent().turns()).hasSize(1);

            harness.scriptGreenCi(fixture, pushedSha);
        }

        try (ConfigurableApplicationContext second = openContext(
                database,
                runtimeRoot,
                "none",
                Optional.of(scripts),
                false)) {
            CiAutofixService service = second.getBean(CiAutofixService.class);
            CiAutofixMonitorTestDriver monitor = second.getBean(CiAutofixMonitorTestDriver.class);
            CiAutofixRun finished = driveUntilFinished(service, monitor, runId, 10);
            PrDetailStore details = second.getBean(PrDetailStore.class);
            AgentTaskService tasks = second.getBean(AgentTaskService.class);
            AgentEventService events = second.getBean(AgentEventService.class);
            AgentMetricsService metrics = second.getBean(AgentMetricsService.class);
            CiMemoryService memory = second.getBean(CiMemoryService.class);

            assertThat(finished.finishReason()).contains(CiAutofixRun.FinishReason.GREEN);
            assertThat(finished.pushJournal()).isEmpty();
            assertThat(finished.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.agentTurnId()).contains(turnId);
                assertThat(attempt.pushedSha()).contains(pushedSha);
                assertThat(attempt.timelineEventKey()).isPresent();
                assertThat(attempt.untrustedAgentNote()).contains(agentNote);
            });
            assertThat(finished.finalFixupCheck()).hasValueSatisfying(check -> {
                assertThat(check.headSha()).isEqualTo(pushedSha);
                assertThat(check.result()).isEqualTo(CiAutofixRun.CheckResult.VALID);
            });
            assertThat(tasks.get(taskId).state()).isEqualTo(TaskState.COMPLETED);
            assertThat(tasks.get(taskId).summary()).hasValueSatisfying(summary -> {
                assertThat(summary.finalResponse()).isEqualTo(agentNote);
                assertThat(summary.metrics()).isEqualTo(metricsBeforeRestart);
            });
            assertThat(scripts.agent().turns()).hasSize(1);
            assertThat(fixture.repository().remoteHead()).isEqualTo(pushedSha);
            assertThat(fixture.repository().headRefUpdates()).isEqualTo(branchWrites + 1);
            assertThat(fixture.repository().commitSubjects()).containsExactly("First change", "Target change", "fixup! Target change", "Last change");
            assertThat(Files.readString(rawLog)).isEqualTo(CiAutofixE2eHarness.RAW_COMPILE_LOG);
            assertThat(events.readAfter(taskId, 0, 100)).extracting(event -> event.sequence())
                    .containsExactlyElementsOf(eventSequences);
            assertThat(metrics.taskTotals(taskId)).isEqualTo(metricsBeforeRestart);
            CiMemoryView memoryAfterRestart = memory.read(new ReadCiMemoryCommand(taskId, Optional.of("compile")));
            assertThat(memoryAfterRestart.matchedSections()).isPositive();
            assertThat(memoryAfterRestart.markdown()).contains("Backend compile", "restart-fix.txt", agentNote);

            List<String> timelineKeys = details.find(fixture.prId()).orElseThrow().timeline().stream()
                    .map(event -> event.localEventKey().orElseThrow())
                    .collect(toImmutableList());
            assertThat(timelineKeys).containsExactly(finished.attempts().getFirst().timelineEventKey().orElseThrow());
            var checkedAt = finished.finalFixupCheck().orElseThrow().checkedAt();
            monitor.tick();
            monitor.tick();
            CiAutofixRun unchanged = service.get(runId);

            assertThat(unchanged.finalFixupCheck().orElseThrow().checkedAt()).isEqualTo(checkedAt);
            assertThat(details.find(fixture.prId()).orElseThrow().timeline()).hasSize(1);
            assertThat(fixture.repository().headRefUpdates()).isEqualTo(branchWrites + 1);
            assertThat(scripts.agent().turns()).hasSize(1);
        }
    }

    private ConfigurableApplicationContext openContext(
            Path database,
            Path runtimeRoot,
            String ddlMode,
            Optional<Scripts> scripts,
            boolean losePushResult)
    {
        SpringApplication application = new SpringApplicationBuilder(ByteQuayApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(new RestartInitializer(scripts, losePushResult))
                .build();
        application.setRegisterShutdownHook(false);
        return application.run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.datasource.url=jdbc:sqlite:" + database + "?foreign_keys=ON",
                "--spring.jpa.hibernate.ddl-auto=" + ddlMode,
                "--bytequay.agent.runtime-root=" + runtimeRoot,
                "--bytequay.agent.codex-enabled=false",
                "--bytequay.agent.claude-enabled=false",
                "--bytequay.agent.desktop-session-token=" + CiAutofixE2eHarness.SESSION,
                "--bytequay.scheduling.enabled=false",
                "--logging.file.name=" + temporaryDirectory.resolve("backend.log"),
                "--logging.level.com.bytequay.app.ci.DefaultCiAutofixService=OFF",
                "--spring.main.banner-mode=off");
    }

    private static CiAutofixE2eHarness harness(ConfigurableApplicationContext context, Scripts scripts)
    {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilters(context.getBean(AgentRequestFilter.class))
                .build();
        return new CiAutofixE2eHarness(
                mvc,
                context.getBean(ObjectMapper.class),
                context.getBean(CiAutofixService.class),
                context.getBean(CiAutofixMonitorTestDriver.class),
                scripts.gitHub(),
                scripts.agent(),
                context.getBean(AgentTaskService.class),
                context.getBean(AgentTurnService.class),
                context.getBean(AgentEventService.class),
                context.getBean(AgentResourceService.class),
                context.getBean(PullRequestJpaRepository.class),
                context.getBean(PrDetailStore.class),
                context.getBean(Clock.class));
    }

    private static CiAutofixRun driveUntilRemoteContainsJournal(
            ConfigurableApplicationContext context,
            E2eGitRepository repository,
            CiAutofixRunId runId,
            int maximumTicks)
    {
        CiAutofixService service = context.getBean(CiAutofixService.class);
        CiAutofixMonitorTestDriver monitor = context.getBean(CiAutofixMonitorTestDriver.class);
        CiAutofixRun run = service.get(runId);
        for (int tick = 0; tick < maximumTicks; tick++) {
            if (run.pushJournal().isPresent()
                    && repository.remoteHead().equals(run.pushJournal().orElseThrow().localHeadSha())) {
                return run;
            }
            monitor.tick();
            run = service.get(runId);
        }
        throw new AssertionError("Push did not reach the remote with its journal retained: " + run);
    }

    private static CiAutofixRun driveUntilFinished(
            CiAutofixService service,
            CiAutofixMonitorTestDriver monitor,
            CiAutofixRunId runId,
            int maximumTicks)
    {
        CiAutofixRun run = service.get(runId);
        for (int tick = 0; tick < maximumTicks && run.status() != CiAutofixRun.Status.FINISHED; tick++) {
            monitor.tick();
            run = service.get(runId);
        }
        assertThat(run.status()).isEqualTo(CiAutofixRun.Status.FINISHED);
        return run;
    }

    private record Scripts(ScriptedGitHubActionsClient gitHub, ScriptedAgentDriver agent) {}

    private record RestartInitializer(Optional<Scripts> scripts, boolean losePushResult)
            implements ApplicationContextInitializer<GenericApplicationContext>
    {
        @Override
        public void initialize(GenericApplicationContext context)
        {
            context.registerBean(
                    ScriptedGitHubActionsClient.class,
                    () -> scripts.map(Scripts::gitHub)
                            .orElseGet(() -> new ScriptedGitHubActionsClient(context.getBean(TaskLogStore.class))),
                    bean -> bean.setPrimary(true));
            context.registerBean(
                    ScriptedAgentDriver.class,
                    () -> scripts.map(Scripts::agent)
                            .orElseGet(() -> new ScriptedAgentDriver(context.getBean(CiMemoryService.class))));
            context.registerBean(
                    "restartCredentialService",
                    CredentialService.class,
                    () -> new CredentialService(
                            context.getBean(CredentialStore.class),
                            context.getBean(GhCliService.class))
                    {
                        @Override
                        public String resolveGitHubToken()
                        {
                            return "test-token";
                        }
                    },
                    bean -> bean.setPrimary(true));
            context.registerBean(
                    "restartGitHubAccountClient",
                    GitHubAccountClient.class,
                    () -> new GitHubAccountClient(
                            context.getBean("gitHubRestClient", RestClient.class),
                            context.getBean("gitHubGraphQLRestClient", RestClient.class))
                    {
                        @Override
                        public UserProfile fetchUserProfile(String pat)
                        {
                            return new UserProfile("author", "Author", "", "", 0, 0, 0, "", "", "", "");
                        }
                    },
                    bean -> bean.setPrimary(true));
            context.registerBean(CiAutofixMonitorTestDriver.class);
            if (losePushResult) {
                context.registerBean(
                        LostPushCiGitService.class,
                        () -> new LostPushCiGitService(context.getBean(CiGitOperations.class)),
                        bean -> bean.setPrimary(true));
            }
        }
    }

    private static final class LostPushCiGitService
            implements CiGitService
    {
        private final CiGitService delegate;
        private int pushCalls;

        private LostPushCiGitService(CiGitService delegate)
        {
            this.delegate = requireNonNull(delegate, "delegate is null");
        }

        @Override
        public CiBaseSynchronization synchronizeBase(SynchronizeCiBaseCommand command)
        {
            return delegate.synchronizeBase(command);
        }

        @Override
        public boolean removeSynchronizedBase(SynchronizeCiBaseCommand command)
        {
            return delegate.removeSynchronizedBase(command);
        }

        @Override
        public CiGitInspection inspectAgentResult(InspectAgentResultCommand command)
        {
            return delegate.inspectAgentResult(command);
        }

        @Override
        public CiPushResult push(PushCiHeadCommand command)
        {
            pushCalls++;
            CiPushResult result = delegate.push(command);
            checkState(result.outcome() == CiPushResult.Outcome.PUSHED, "test push did not succeed: %s", result);
            String remoteHead = E2eGitRepository.git(
                    command.worktree(),
                    "ls-remote",
                    "origin",
                    "refs/heads/" + command.headRef()).split("\\s+", 2)[0];
            checkState(remoteHead.equals(command.journal().localHeadSha()), "remote did not receive the journaled SHA");
            return pushCalls == 1
                    ? CiPushResult.failed(CiPushResult.Outcome.UNCERTAIN, "test discarded the successful push result")
                    : result;
        }

        @Override
        public FixupPositionCheck checkFixupPositions(CheckFixupPositionsCommand command)
        {
            return delegate.checkFixupPositions(command);
        }

        private int pushCalls()
        {
            return pushCalls;
        }
    }
}
