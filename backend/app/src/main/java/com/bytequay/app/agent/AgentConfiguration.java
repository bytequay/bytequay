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
package com.bytequay.app.agent;

import com.bytequay.agent.cli.claude.ClaudeCliDriver;
import com.bytequay.agent.cli.claude.ClaudePermissionBroker;
import com.bytequay.agent.cli.codex.CodexCliDriver;
import com.bytequay.agent.cli.codex.CodexHome;
import com.bytequay.agent.cli.process.LaunchIntentRecorder;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.impl.DefaultProcessSupervisor;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.impl.DefaultAgentApprovalService;
import com.bytequay.agent.core.impl.DefaultAgentCompletionService;
import com.bytequay.agent.core.impl.DefaultAgentEventService;
import com.bytequay.agent.core.impl.DefaultAgentMetricsService;
import com.bytequay.agent.core.impl.DefaultAgentTaskService;
import com.bytequay.agent.core.impl.DefaultAgentTurnService;
import com.bytequay.agent.core.impl.FileAgentResourceService;
import com.bytequay.agent.core.impl.FileTaskLogStore;
import com.bytequay.agent.core.impl.StaticAgentDriverRegistry;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.app.agent.codegraph.CodeGraphCli;
import com.bytequay.app.agent.codegraph.CodeGraphProperties;
import com.bytequay.app.agent.codegraph.PinnedCodeGraphService;
import com.bytequay.app.ci.CiMemoryService;
import com.bytequay.app.ci.ReadCiMemoryCommand;
import com.bytequay.app.git.GitCommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentProperties.class, CodeGraphProperties.class})
public class AgentConfiguration
{
    @Bean
    public ProcessSupervisor processSupervisor(AgentProperties properties, LaunchIntentRecorder intents)
    {
        return new DefaultProcessSupervisor(properties.runtimeRoot().resolve("_processes"), intents);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "bytequay.agent.codex-enabled", havingValue = "true")
    public CodexCliDriver codexCliDriver(
            AgentProperties properties,
            ObjectMapper objectMapper,
            ProcessSupervisor supervisor)
            throws IOException
    {
        return new CodexCliDriver(
                properties.codexExecutable(),
                objectMapper,
                supervisor,
                CodexHome.prepare(properties.codexHome()));
    }

    @Bean
    @ConditionalOnProperty(name = "bytequay.agent.claude-enabled", havingValue = "true")
    public ClaudePermissionBroker claudePermissionBroker(
            ObjectMapper objectMapper,
            ServletWebServerApplicationContext context,
            CiMemoryService ciMemoryService)
    {
        return new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:" + context.getWebServer().getPort()
                        + "/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> ciMemoryService.read(
                        new ReadCiMemoryCommand(TaskId.valueOf(taskId), query)).markdown());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "bytequay.agent.claude-enabled", havingValue = "true")
    public ClaudeCliDriver claudeCliDriver(
            AgentProperties properties,
            ObjectMapper objectMapper,
            ClaudePermissionBroker permissions,
            ProcessSupervisor supervisor)
    {
        return new ClaudeCliDriver(properties.claudeExecutable(), objectMapper, permissions, supervisor);
    }

    @Bean
    public AgentDriverRegistry agentDriverRegistry(List<AgentDriver> drivers)
    {
        return new StaticAgentDriverRegistry(drivers);
    }

    @Bean(destroyMethod = "close")
    public AgentRuntimeRegistry agentRuntimeRegistry()
    {
        return new AgentRuntimeRegistry();
    }

    @Bean
    public AgentResourceService agentResourceService(AgentProperties properties)
    {
        return new FileAgentResourceService(properties.runtimeRoot());
    }

    @Bean
    public CodeGraphService codeGraphService(CodeGraphProperties properties, AgentProperties agentProperties, GitCommandRunner git)
    {
        return new PinnedCodeGraphService(new CodeGraphCli(properties.bundleHome(), git), git, agentProperties.runtimeRoot().resolve("codegraph-baselines"));
    }

    @Bean
    public TaskLogStore taskLogStore(AgentResourceService resourceService)
    {
        return new FileTaskLogStore(resourceService);
    }

    @Bean
    public Clock agentClock()
    {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService agentApprovalScheduler()
    {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("bytequay-agent-approval").factory());
    }

    @Bean
    public AgentEventService agentEventService(
            TaskLogStore logStore,
            AgentEventOutboxStore outboxStore,
            AgentTaskStore taskStore,
            Clock agentClock)
    {
        return new DefaultAgentEventService(logStore, outboxStore, taskStore, agentClock);
    }

    @Bean
    public AgentMetricsService agentMetricsService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            Clock agentClock)
    {
        return new DefaultAgentMetricsService(taskStore, turnStore, agentClock);
    }

    @Bean
    public AgentApprovalService agentApprovalService(
            AgentApprovalStore approvalStore,
            AgentTurnStore turnStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            ScheduledExecutorService agentApprovalScheduler,
            Clock agentClock,
            AgentRuntimeRegistry runtimeRegistry)
    {
        return new DefaultAgentApprovalService(
                approvalStore,
                turnStore,
                unitOfWork,
                outboxStore,
                eventService,
                agentApprovalScheduler,
                agentClock,
                runtimeRegistry);
    }

    @Bean
    public AgentTaskService agentTaskService(
            AgentTaskStore taskStore,
            WorktreeService worktreeService,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            Clock agentClock)
    {
        return new DefaultAgentTaskService(
                taskStore,
                worktreeService,
                resourceService,
                driverRegistry,
                agentClock);
    }

    @Bean
    public AgentTurnService agentTurnService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            SteerDeliveryStore steerStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            TaskLogStore logStore,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            AgentEventService eventService,
            AgentMetricsService metricsService,
            AgentApprovalService approvalService,
            AgentRuntimeRegistry runtimeRegistry,
            Clock agentClock,
            AgentProperties properties)
    {
        return new DefaultAgentTurnService(
                taskStore,
                turnStore,
                queueStore,
                steerStore,
                unitOfWork,
                outboxStore,
                logStore,
                resourceService,
                driverRegistry,
                eventService,
                metricsService,
                approvalService,
                runtimeRegistry,
                agentClock,
                properties.maximumTurnRuntime(),
                // A queued turn starts from whatever thread ended the last one, which may be the
                // provider's own reader; it must not block that thread on a provider call.
                command -> Thread.ofVirtual().name("bytequay-agent-queue").start(command));
    }

    @Bean
    public AgentCompletionService agentCompletionService(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            TaskLogStore logStore,
            WorktreeService worktreeService,
            AgentResourceService resourceService,
            AgentRuntimeRegistry runtimeRegistry,
            AgentMetricsService metricsService,
            Clock agentClock)
    {
        return new DefaultAgentCompletionService(
                taskStore,
                turnStore,
                queueStore,
                unitOfWork,
                outboxStore,
                eventService,
                logStore,
                worktreeService,
                resourceService,
                runtimeRegistry,
                metricsService,
                agentClock);
    }

    @Bean
    public AgentMaintenance agentMaintenance(
            AgentTaskStore taskStore,
            AgentTurnStore turnStore,
            QueuedInputStore queueStore,
            SteerDeliveryStore steerStore,
            AgentUnitOfWork unitOfWork,
            AgentEventOutboxStore outboxStore,
            AgentApprovalService approvalService,
            AgentMetricsService metricsService,
            AgentCompletionService completionService,
            AgentRuntimeRegistry runtimeRegistry,
            Clock agentClock)
    {
        return new AgentMaintenance(
                taskStore,
                turnStore,
                queueStore,
                steerStore,
                unitOfWork,
                outboxStore,
                approvalService,
                metricsService,
                completionService,
                runtimeRegistry,
                agentClock);
    }
}
