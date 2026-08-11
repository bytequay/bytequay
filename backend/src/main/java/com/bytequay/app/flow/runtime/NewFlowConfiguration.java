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

import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixCoordinator;
import com.bytequay.app.flow.ci.CiAutofixRecords.PublishedPrSubject;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.timeline.PrTimelineProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Production composition root for the new-flow foundation only. */
@Configuration(proxyBeanMethods = false)
public class NewFlowConfiguration
{
    private static final Duration CLAIM_TTL = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
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
            @Qualifier("newFlowClock") Clock clock)
    {
        return new UserGates(
                dataSource,
                runtime,
                localChecks,
                autofix,
                effects,
                clock);
    }

    @Bean
    public CiAutofixCoordinator newFlowCiAutofixCoordinator(
            @Qualifier("newFlowDataSource") DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            UserGates userGates,
            @Qualifier("newFlowClock") Clock clock)
    {
        return new CiAutofixCoordinator(
                dataSource, autofix, runtime, userGates, clock);
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
    public PrTimelineProjection newFlowPrTimelineProjection(
            @Qualifier("newFlowDataSource") DataSource dataSource)
    {
        return new PrTimelineProjection(dataSource);
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
