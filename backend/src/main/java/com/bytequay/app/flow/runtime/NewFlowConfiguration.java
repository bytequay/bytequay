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

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiCleanupCoordinator;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator;
import com.bytequay.app.flow.ci.CiLearningCoordinator;
import com.bytequay.app.flow.ci.CiObservationCoordinator;
import com.bytequay.app.flow.ci.CiRepairCoordinator;
import com.bytequay.app.flow.gate.InitialPublishVerificationProvider;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubCiObservationDispatcher;
import com.bytequay.app.flow.github.GitHubCiUpdateDispatcher;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.github.GitHubInitialPublishDispatcher;
import com.bytequay.app.flow.github.GitHubInitialRepositoryObserver;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.timeline.PrTimelineProjection;
import com.bytequay.app.flow.timeline.TaskViews;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.workmodel.WorkModelService;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Production composition root for the new-flow foundation only. */
@Configuration(proxyBeanMethods = false)
public class NewFlowConfiguration
{
    // Exceeds the bounded sum of provisioning Git commands and inspections.
    private static final Duration CLAIM_TTL = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration AGENT_CLAIM_TTL = Duration.ofHours(4);
    private static final Duration AGENT_BODY_TIMEOUT =
            Duration.ofHours(3).plusMinutes(30);
    private static final Duration AGENT_SHUTDOWN_TIMEOUT =
            Duration.ofMinutes(6);
    private static final int CAPACITY = 1;

    @Bean(name = "newFlowClock", defaultCandidate = false)
    public Clock newFlowClock()
    {
        return Clock.systemUTC();
    }

    @Bean(name = "newFlowDataSource", defaultCandidate = false)
    public DataSource newFlowDataSource(
            @Value("${bytequay.new-flow.database-path:${user.home}/Library/Application Support/ByteQuay/new-flow.db}")
                    String databasePath,
            @Qualifier("newFlowClock") Clock clock)
    {
        Path path = Path.of(databasePath).toAbsolutePath().normalize();
        createParent(path);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + path
                        + "?journal_mode=WAL&busy_timeout=30000"
                        + "&synchronous=NORMAL&foreign_keys=ON"
                        + "&temp_store=MEMORY&transaction_mode=IMMEDIATE");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        new NewFlowDatabase(dataSource, clock).bootstrap();
        return dataSource;
    }

    @Bean
    public FlowRuntime newFlowRuntime(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new FlowRuntime(dataSource, clock);
    }

    @Bean
    public LocalChecks newFlowLocalChecks(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            FlowRuntime runtime,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new LocalChecks(dataSource, runtime, clock);
    }

    @Bean
    public CiAutofix newFlowCiAutofix(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            FlowRuntime runtime,
            ObjectMapper objectMapper,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new CiAutofix(
                dataSource,
                objectMapper,
                clock,
                prId -> publishedSubject(runtime, prId));
    }

    @Bean
    public GitHubEffects newFlowGitHubEffects(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            FlowRuntime runtime)
    {
        return new GitHubEffects(dataSource, runtime);
    }

    @Bean
    public UserGates newFlowUserGates(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            FlowRuntime runtime,
            LocalChecks localChecks,
            CiAutofix autofix,
            GitHubEffects effects,
            @Qualifier("newFlowClock") Clock clock,
            ObjectProvider<InitialPublishVerificationProvider>
                    initialVerification)
    {
        return new UserGates(
                dataSource,
                runtime,
                localChecks,
                autofix,
                effects,
                clock,
                initialVerification.getIfAvailable(
                        () -> InitialPublishVerificationProvider.NONE));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public GitHubInitialPublishDispatcher newFlowInitialPublishDispatcher(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            CredentialStore credentials,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new GitHubInitialPublishDispatcher(
                runtime, gates, effects, credentials, clock,
                new GitHubInitialPublishDispatcher.Config(
                        "new-flow-initial-publish",
                        CLAIM_TTL, POLL_INTERVAL, CAPACITY));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public GitHubCiUpdateDispatcher newFlowCiUpdatePublishDispatcher(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            CredentialStore credentials,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new GitHubCiUpdateDispatcher(
                runtime, gates, effects, credentials, clock,
                new GitHubCiUpdateDispatcher.Config(
                        "new-flow-ci-update-publish",
                        CLAIM_TTL, POLL_INTERVAL, CAPACITY));
    }

    @Bean
    public CiLearningCoordinator newFlowCiLearningCoordinator(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            UserGates userGates,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new CiLearningCoordinator(
                dataSource, autofix, runtime, userGates, clock);
    }

    @Bean
    public CiObservationCoordinator newFlowCiObservationCoordinator(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            CiLearningCoordinator learning,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new CiObservationCoordinator(
                dataSource, autofix, runtime, learning, clock);
    }

    @Bean
    public CiRepairCoordinator newFlowCiRepairCoordinator(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            CiLearningCoordinator learning)
    {
        return new CiRepairCoordinator(
                dataSource, autofix, runtime, learning);
    }

    @Bean
    public CiCleanupCoordinator newFlowCiCleanupCoordinator(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime)
    {
        return new CiCleanupCoordinator(dataSource, autofix, runtime);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public GitHubCiObservationDispatcher newFlowCiObservationDispatcher(
            FlowRuntime runtime,
            CiObservationCoordinator coordinator,
            CiAutofixDispatcher ciAgents,
            CredentialStore credentials,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new GitHubCiObservationDispatcher(
                runtime, coordinator, ciAgents, credentials, clock,
                "new-flow-github-ci-observation",
                Duration.ofMinutes(3), POLL_INTERVAL, CAPACITY);
    }

    @Bean
    public CiFixReviewCoordinator newFlowCiFixReviewCoordinator(
            CiAutofix autofix,
            FlowRuntime runtime,
            LocalChecks localChecks,
            UserGates userGates)
    {
        return new CiFixReviewCoordinator(
                autofix, runtime, localChecks, userGates);
    }

    @Bean
    public GitHubInitialRepositoryObserver newFlowInitialRepositoryObserver(
            FlowRuntime runtime, CredentialStore credentials)
    {
        return new GitHubInitialRepositoryObserver(runtime, credentials);
    }

    @Bean
    public InitialTaskCoordinator newFlowInitialTaskCoordinator(
            FlowRuntime runtime,
            TaskProvisioning provisioning,
            LocalChecks localChecks,
            UserGates userGates)
    {
        return new InitialTaskCoordinator(
                runtime, provisioning, localChecks, userGates);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public InitialTaskDispatcher newFlowInitialTaskDispatcher(
            FlowRuntime runtime,
            InitialTaskCoordinator coordinator,
            InProcessWriterAgentSupervisor writerSupervisor,
            InProcessReviewerAgentSupervisor reviewerSupervisor,
            NewFlowAgentBodies bodies,
            GitHubInitialRepositoryObserver repositories)
    {
        return new InitialTaskDispatcher(
                runtime, coordinator, writerSupervisor, reviewerSupervisor,
                bodies, repositories,
                new InitialTaskDispatcher.Config(
                        "new-flow-initial-task",
                        AGENT_CLAIM_TTL,
                        POLL_INTERVAL,
                        AGENT_BODY_TIMEOUT,
                        AGENT_SHUTDOWN_TIMEOUT,
                        CAPACITY));
    }

    @Bean
    public InProcessWriterAgentSupervisor newFlowWriterSupervisor(
            FlowRuntime runtime)
    {
        return new InProcessWriterAgentSupervisor(runtime);
    }

    @Bean
    public InProcessReviewerAgentSupervisor newFlowReviewerSupervisor(
            FlowRuntime runtime)
    {
        return new InProcessReviewerAgentSupervisor(runtime);
    }

    @Bean
    public InProcessCiLearningAgentSupervisor newFlowCiLearningSupervisor(
            FlowRuntime runtime)
    {
        return new InProcessCiLearningAgentSupervisor(runtime);
    }

    @Bean
    public NewFlowEngineResolver newFlowEngineResolver(
            FlowRuntime runtime,
            JdbcTemplate jdbcTemplate,
            WorkspaceEngineSettings engineSettings,
            WorkModelService workModels,
            ObjectMapper objectMapper)
    {
        return new NewFlowEngineResolver(
                runtime, jdbcTemplate, engineSettings, workModels, objectMapper);
    }

    @Bean
    public NewFlowAgentLaunches newFlowAgentLaunches(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            FlowRuntime runtime,
            CredentialStore credentials,
            NewFlowEngineResolver engines,
            @Qualifier("newFlowClock") Clock clock,
            ObjectMapper objectMapper)
    {
        return new NewFlowAgentLaunches(
                dataSource, runtime, credentials, engines, clock, objectMapper);
    }

    @Bean
    NewFlowAgentBodies newFlowAgentBodies(
            NewFlowAgentLaunches launches,
            TurnRunner turnRunner,
            ObjectMapper objectMapper,
            LocalChecks localChecks,
            FlowRuntime runtime,
            NewFlowAgentToolBridge toolBridge,
            @Value("${server.port:53123}") int serverPort)
    {
        return new NewFlowAgentBodies(
                launches,
                turnRunner,
                objectMapper,
                localChecks,
                new NewFlowCliTurn(
                        runtime, toolBridge, objectMapper, serverPort));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public CiAutofixDispatcher newFlowCiAutofixDispatcher(
            FlowRuntime runtime,
            CiRepairCoordinator repairs,
            CiCleanupCoordinator cleanups,
            CiLearningCoordinator learning,
            CiFixReviewCoordinator reviewCoordinator,
            InProcessWriterAgentSupervisor writerSupervisor,
            InProcessReviewerAgentSupervisor reviewerSupervisor,
            InProcessCiLearningAgentSupervisor learningSupervisor,
            NewFlowAgentBodies bodies)
    {
        return new CiAutofixDispatcher(
                runtime,
                repairs,
                cleanups,
                learning,
                reviewCoordinator,
                writerSupervisor,
                reviewerSupervisor,
                learningSupervisor,
                bodies,
                new CiAutofixDispatcher.Config(
                        "new-flow-ci-autofix",
                        AGENT_CLAIM_TTL,
                        POLL_INTERVAL,
                        AGENT_BODY_TIMEOUT,
                        AGENT_SHUTDOWN_TIMEOUT,
                        CAPACITY));
    }

    @Bean
    public PrTimelineProjection newFlowPrTimelineProjection(
            @Qualifier("newFlowDataSource") DataSource dataSource)
    {
        return new PrTimelineProjection(dataSource);
    }

    @Bean
    public TaskViews newFlowTaskViews(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            PrTimelineProjection timeline)
    {
        return new TaskViews(dataSource, timeline);
    }

    @Bean
    public TaskProvisioning.RepositoryCatalog newFlowRepositoryCatalog(
            WatchedRepoStore watchedRepos,
            @Value("${bytequay.new-flow.worktree-root:${user.home}/Library/Application Support/ByteQuay/new-flow-worktrees}")
                    String worktreeRoot)
    {
        Path configuredRoot = Path.of(worktreeRoot)
                .toAbsolutePath().normalize();
        ensureOwnedDirectory(configuredRoot);
        Path root = realDirectory(configuredRoot);
        return repositoryId -> {
            int slash = repositoryId.indexOf('/');
            if (slash < 1 || slash == repositoryId.length() - 1
                    || repositoryId.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException(
                        "repositoryId must be canonical owner/name");
            }
            WatchedRepo watched = watchedRepos.find(
                    repositoryId.substring(0, slash),
                    repositoryId.substring(slash + 1)).orElseThrow(() ->
                            new IllegalArgumentException(
                                    "repository is not configured"));
            if (watched.localClonePath() == null
                    || watched.localClonePath().isBlank()) {
                throw new IllegalStateException(
                        "repository has no configured local clone");
            }
            Path repositoryRoot = Path.of(watched.localClonePath())
                    .toAbsolutePath().normalize();
            String remote = watched.upstreamRemoteName() == null
                    || watched.upstreamRemoteName().isBlank()
                    ? "origin" : watched.upstreamRemoteName();
            Path repositoryWorktrees = root
                    .resolve(repositoryId.substring(0, slash))
                    .resolve(repositoryId.substring(slash + 1))
                    .normalize();
            ensureOwnedDirectory(repositoryWorktrees);
            if (!realDirectory(repositoryWorktrees).startsWith(root)) {
                throw new IllegalStateException(
                        "repository worktree root escaped the app-owned root");
            }
            return new TaskProvisioning.RepositoryConfig(
                    repositoryId,
                    repositoryId.substring(0, slash),
                    repositoryId.substring(slash + 1),
                    repositoryRoot,
                    repositoryRoot.resolve(".git"),
                    remote,
                    "refs/remotes/" + remote + "/HEAD",
                    repositoryWorktrees);
        };
    }

    private static void ensureOwnedDirectory(Path directory)
    {
        try {
            if (Files.isSymbolicLink(directory)) {
                throw new IllegalStateException(
                        "new-flow worktree root is a symbolic link");
            }
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(directory)) {
                throw new IllegalStateException(
                        "new-flow worktree root is not a real directory");
            }
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot create new-flow worktree root", failure);
        }
    }

    private static Path realDirectory(Path directory)
    {
        try {
            return directory.toRealPath();
        }
        catch (IOException failure) {
            throw new UncheckedIOException(
                    "cannot canonicalize new-flow worktree root", failure);
        }
    }

    @Bean
    public TaskProvisioning newFlowTaskProvisioning(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            FlowRuntime runtime,
            TaskProvisioning.RepositoryCatalog catalog,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new TaskProvisioning(dataSource, runtime, catalog, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public NewFlowDispatcher newFlowDispatcher(
            FlowRuntime runtime,
            List<NewFlowDispatcher.Handler> handlers)
    {
        return new NewFlowDispatcher(
                runtime,
                new NewFlowDispatcher.Config(
                        "new-flow-dispatcher",
                        CLAIM_TTL,
                        POLL_INTERVAL,
                        CAPACITY),
                handlers);
    }

    @Bean
    public TaskCommands newFlowTaskCommands(
            TaskProvisioning provisioning,
            NewFlowDispatcher dispatcher,
            InitialTaskDispatcher initialTasks)
    {
        return new TaskCommands(provisioning, dispatcher, initialTasks);
    }

    private static PublishedPrSubject publishedSubject(
            FlowRuntime runtime, String prId)
    {
        PullRequestSubject subject = runtime.pullRequest(prId).orElse(null);
        if (subject == null) {
            return null;
        }
        return new PublishedPrSubject(
                subject.prId(),
                subject.taskId(),
                subject.repositoryId(),
                subject.scopeKey(),
                subject.targetBaseRef(),
                subject.currentRemoteHead());
    }

    private static void createParent(Path path)
    {
        Path parent = path.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        }
        catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot create new-flow database directory", failure);
        }
    }
}
