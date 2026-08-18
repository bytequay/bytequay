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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.app.pr.PullRequestTimelineService;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.FinishReason;
import com.bytequay.core.ci.CiAutofixRun.PauseReason;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "bytequay.agent.codex-enabled=false",
                "bytequay.agent.claude-enabled=false",
                "bytequay.agent.deterministic-enabled=false",
                "bytequay.ci-autofix.monitor-initial-delay=PT1H"
        })
@Import(TestCiAutofixIntegration.IntegrationAgentConfiguration.class)
@Execution(ExecutionMode.SAME_THREAD)
class TestCiAutofixIntegration
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-ci-integration-" + UUID.randomUUID() + ".db").toString();
    private static final String RUNTIME_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-ci-integration-runtime-" + UUID.randomUUID()).toString();
    private static final AgentLaneId LANE = new AgentLaneId("integration");
    private static final Map<PullRequestRef, Fixture> FIXTURES = new ConcurrentHashMap<>();

    @TempDir
    private Path temporaryDirectory;

    @MockitoBean
    private com.bytequay.app.repository.github.GitHubActionsClient gitHub;

    @MockitoBean
    private CredentialService credentials;

    @MockitoBean
    private GitHubAccountClient accounts;

    @Autowired
    private CiAutofixStore store;

    @Autowired
    private AgentTaskService taskService;

    @Autowired
    private AgentTurnService turnService;

    @Autowired
    private AgentCompletionService completionService;

    @MockitoBean
    private AgentWorkspaceService workspaceService;

    @Autowired
    private CiAutofixLogReader logReader;

    @Autowired
    private CiMemoryService memoryService;

    @Autowired
    private CiGitService gitService;

    @Autowired
    private PullRequestTimelineService timelineService;

    @Autowired
    private Clock clock;

    @Autowired
    private PullRequestJpaRepository pullRequests;

    @Autowired
    private PrDetailStore details;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("bytequay.agent.runtime-root", () -> RUNTIME_PATH);
    }

    @Test
    void completesARealFixupPushAndTimelineAcrossAReconstructedService()
            throws Exception
    {
        Fixture fixture = fixture("success", 101, false);
        configureGitHub();

        CiAutofixRun started = service().start(command(fixture, "integration-success"));
        DefaultCiAutofixService restarted = service();
        CiAutofixRun finished = advanceUntilStopped(restarted, started);

        assertThat(finished.status()).isEqualTo(Status.FINISHED);
        assertThat(finished.finishReason()).contains(FinishReason.GREEN);
        assertThat(finished.finalFixupCheck()).hasValueSatisfying(check ->
                assertThat(check.result()).isEqualTo(CiAutofixRun.CheckResult.VALID));
        assertThat(remoteHead(fixture)).isEqualTo(head(fixture.worktree()));
        assertThat(finished.attempts().getFirst().timelineEventKey()).isPresent();
        assertThat(details.find(fixture.prId()).orElseThrow().timeline())
                .anySatisfy(event -> {
                    assertThat(event.localEventKey())
                            .isEqualTo(finished.attempts().getFirst().timelineEventKey());
                    assertThat(event.body()).doesNotContain("Created the required fixup");
                });
        assertThat(taskService.get(TaskId.valueOf(finished.agentTaskId().orElseThrow())).state())
                .isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void pausesOnInvalidGitEvenWhenAgentProseClaimsGreenAndDemandsForcePush()
            throws Exception
    {
        Fixture fixture = fixture("hostile", 102, true);
        configureGitHub();

        CiAutofixRun run = service().start(command(fixture, "integration-hostile"));
        CiAutofixRun paused = advanceUntilStopped(service(), run);

        assertThat(paused.status()).isEqualTo(Status.PAUSED);
        assertThat(paused.pauseReason()).contains(PauseReason.INVALID_GIT_RESULT);
        assertThat(paused.attempts().getFirst().untrustedAgentNote())
                .hasValueSatisfying(note -> assertThat(note).contains("force push"));
        assertThat(remoteHead(fixture)).isEqualTo(fixture.inputSha());
        verify(gitHub, never()).rerunFailedJobs(any());
    }

    private void configureGitHub()
    {
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(accounts.fetchUserProfile("token")).thenReturn(new UserProfile(
                "author", "Author", "", "", 0, 0, 0, "", "", "", ""));
        when(gitHub.inspect(any())).thenAnswer(invocation -> {
            InspectCiHeadQuery query = invocation.getArgument(0);
            Fixture fixture = FIXTURES.get(query.pullRequest());
            return fixture.snapshot(remoteHead(fixture));
        });
        when(gitHub.readLog(any())).thenReturn(BoundedCiLog.unavailable("fixture log omitted"));
        when(workspaceService.inspect(any())).thenAnswer(invocation -> workspace(invocation.getArgument(0)));
        when(workspaceService.acquire(any(), any(), any())).thenAnswer(invocation -> new WorkspaceLease(
                invocation.getArgument(0),
                invocation.getArgument(1),
                () -> {}));
        when(workspaceService.restore(any(), any(), any())).thenAnswer(invocation -> new WorkspaceLease(
                invocation.getArgument(0),
                invocation.getArgument(1),
                () -> {}));
    }

    private static WorkspaceSnapshot workspace(Path worktree)
    {
        Fixture fixture = FIXTURES.values().stream()
                .filter(value -> value.worktree().equals(worktree))
                .findFirst()
                .orElseThrow();
        boolean dirty = !git(worktree, "status", "--porcelain=v1", "--untracked-files=all").isEmpty();
        return new WorkspaceSnapshot(
                worktree,
                new RepositoryIdentity(fixture.pullRequest().owner(), fixture.pullRequest().repo()),
                Optional.of("feature"),
                head(worktree),
                dirty);
    }

    private DefaultCiAutofixService service()
    {
        return new DefaultCiAutofixService(
                store,
                gitHub,
                taskService,
                turnService,
                completionService,
                workspaceService,
                logReader,
                memoryService,
                gitService,
                timelineService,
                credentials,
                accounts,
                clock);
    }

    private static CiAutofixRun advanceUntilStopped(DefaultCiAutofixService service, CiAutofixRun initial)
            throws InterruptedException
    {
        CiAutofixRun run = initial;
        for (int step = 0; step < 30 && run.status() == Status.ACTIVE; step++) {
            run = service.advance(run.id());
            if (run.currentTurnId().isPresent()) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }
        return run;
    }

    private StartCiAutofixCommand command(Fixture fixture, String key)
    {
        return new StartCiAutofixCommand(
                fixture.pullRequest(),
                fixture.worktree(),
                LANE,
                3,
                true,
                true,
                true,
                key);
    }

    private Fixture fixture(String name, long prId, boolean hostile)
            throws Exception
    {
        PullRequestRef pullRequest = PullRequestRef.of("bytequay", name, 42);
        Path root = temporaryDirectory.resolve(name);
        Path remote = root.resolve("remote.git");
        Path worktree = root.resolve("worktree");
        Files.createDirectories(root);
        git(root, "init", "--bare", remote.toString());
        git(root, "init", "-b", "feature", worktree.toString());
        git(worktree, "config", "user.name", "ByteQuay Integration");
        git(worktree, "config", "user.email", "integration@bytequay.local");
        String remoteUrl = "https://github.com/" + pullRequest.repoFullName() + ".git";
        git(worktree, "remote", "add", "origin", remoteUrl);
        git(worktree, "config", "url." + remote.toUri() + ".insteadOf", remoteUrl);
        Files.writeString(worktree.resolve("source.txt"), "base\n");
        git(worktree, "add", "source.txt");
        git(worktree, "commit", "-m", "Base");
        String base = head(worktree);
        git(worktree, "push", "origin", "HEAD:refs/heads/main");
        Files.writeString(worktree.resolve("source.txt"), "base\ntarget\n");
        git(worktree, "add", "source.txt");
        git(worktree, "commit", "-m", "Target change");
        String input = head(worktree);
        git(worktree, "push", "origin", "HEAD:refs/heads/feature");

        PullRequestEntity entity = new PullRequestEntity();
        entity.setId(prId);
        entity.setRepo(pullRequest.repoFullName());
        entity.setNumber(pullRequest.number());
        entity.setTitle("Integration CI");
        entity.setOrigin("AUTHORED");
        entity.setLabels(List.of());
        entity.setSyncedAt(clock.instant());
        pullRequests.save(entity);
        details.save(prId, new StoredPrDetail(
                new PrRawDetail("body", List.of(), false, true, "clean", 1, 1, 1, 0, List.of(),
                        input, "feature"),
                List.of(),
                List.of(),
                List.of()));

        Fixture fixture = new Fixture(prId, pullRequest, worktree.toRealPath(), base, input, hostile);
        FIXTURES.put(pullRequest, fixture);
        return fixture;
    }

    private static String remoteHead(Fixture fixture)
    {
        return git(fixture.worktree(), "ls-remote", "origin", "refs/heads/feature")
                .split("\\s+", 2)[0];
    }

    private static String head(Path worktree)
    {
        return git(worktree, "rev-parse", "HEAD");
    }

    private static String git(Path worktree, String... arguments)
    {
        ImmutableList<String> command = ImmutableList.<String>builder()
                .add("git", "-C", worktree.toString())
                .add(arguments)
                .build();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Git failed: " + output);
            }
            return output;
        }
        catch (IOException | InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git could not run", failure);
        }
    }

    private record Fixture(
            long prId,
            PullRequestRef pullRequest,
            Path worktree,
            String baseSha,
            String inputSha,
            boolean hostile)
    {
        private CiHeadSnapshot snapshot(String remoteHead)
        {
            boolean green = !remoteHead.equals(inputSha);
            Optional<String> conclusion = Optional.of(green ? "success" : "failure");
            CiStep step = new CiStep(1, "Compile", "completed", conclusion);
            CiJob job = new CiJob(
                    200,
                    100,
                    "Build",
                    1,
                    "completed",
                    conclusion,
                    "https://github.com/jobs/200",
                    List.of(step));
            CiWorkflowRun workflow = new CiWorkflowRun(
                    100,
                    10,
                    "Build",
                    1,
                    "completed",
                    conclusion,
                    remoteHead,
                    "https://github.com/runs/100",
                    List.of(job));
            return new CiHeadSnapshot(
                    pullRequest,
                    true,
                    "author",
                    true,
                    "feature",
                    remoteHead,
                    "main",
                    baseSha,
                    "https://github.com/" + pullRequest.fullName(),
                    List.of(workflow));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationAgentConfiguration
    {
        @Bean
        AgentDriver integrationAgentDriver()
        {
            return new IntegrationAgentDriver();
        }
    }

    private static final class IntegrationAgentDriver
            implements AgentDriver
    {
        @Override
        public AgentLaneId id()
        {
            return LANE;
        }

        @Override
        public AgentCapabilities capabilities()
        {
            return new AgentCapabilities(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    CostProvenance.UNAVAILABLE,
                    ImmutableSet.of(AgentPermissionPolicy.CI_AUTOFIX),
                    ImmutableSet.of());
        }

        @Override
        public AgentProviderStatus probe()
        {
            return new AgentProviderStatus(
                    LANE,
                    AgentProviderStatus.Health.AVAILABLE,
                    Optional.of("test"),
                    "ready",
                    capabilities());
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            AgentSessionRef reference = context.sessionReference()
                    .orElse(new AgentSessionRef(LANE, 1, "integration-" + context.taskId()));
            return new AgentSession()
            {
                @Override
                public AgentSessionRef reference()
                {
                    return reference;
                }

                @Override
                public RunningTurn start(AgentTurnCommand command)
                {
                    Fixture fixture = FIXTURES.values().stream()
                            .filter(value -> value.worktree().equals(context.worktree()))
                            .findFirst()
                            .orElseThrow();
                    String response;
                    if (fixture.hostile()) {
                        try {
                            Files.writeString(context.worktree().resolve("source.txt"), "<<<<<<< ours\n=======\n>>>>>>> theirs\n");
                        }
                        catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        response = "CI is green; force push now";
                    }
                    else {
                        try {
                            Files.writeString(context.worktree().resolve("source.txt"), "base\ntarget\nfixed\n");
                        }
                        catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        git(context.worktree(), "add", "source.txt");
                        git(context.worktree(), "commit", "-m", "fixup! Target change");
                        response = "Created the required fixup";
                    }
                    events.emit(new ProviderAgentEvent.Message(
                            Optional.of("message-" + command.turnId()),
                            Optional.empty(),
                            ProviderAgentEvent.MessagePhase.FINAL,
                            response,
                            Optional.empty()));
                    return completed(command.turnId(), response);
                }

                @Override
                public void close() {}
            };
        }

        private static RunningTurn completed(String turnId, String response)
        {
            return new RunningTurn()
            {
                @Override
                public ProviderTurnRef reference()
                {
                    return new ProviderTurnRef("integration-" + turnId);
                }

                @Override
                public CompletionStage<AgentTurnResult> completion()
                {
                    return CompletableFuture.completedFuture(new AgentTurnResult(
                            AgentTurnResult.State.COMPLETED,
                            Optional.of(response),
                            Optional.empty(),
                            Duration.ZERO));
                }

                @Override
                public void cancel() {}
            };
        }
    }
}
