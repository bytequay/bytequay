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
package com.bytequay.app.web.agent;

import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentProcessLaunchStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.SteerDeliveryStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.store.PullRequestStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAgentHttpErrors
{
    private MockMvc mvc;
    private AgentTaskService taskService;
    private AgentTurnService turnService;
    private AgentStartupRecovery startupRecovery;
    private AgentDriverRegistry driverRegistry;
    private AgentDriver driver;
    private WorkspaceRepositoryService workspaces;
    private GitCommandRunner git;

    @BeforeEach
    void setUp()
    {
        taskService = mock(AgentTaskService.class);
        turnService = mock(AgentTurnService.class);
        startupRecovery = startupRecovery();
        driverRegistry = mock(AgentDriverRegistry.class);
        driver = mock(AgentDriver.class);
        workspaces = mock(WorkspaceRepositoryService.class);
        git = mock(GitCommandRunner.class);
        AgentController controller = new AgentController(
                taskService,
                new InteractiveAgentTaskCreator(
                        taskService,
                        turnService,
                        driverRegistry,
                        mock(ModelSettingsService.class),
                        workspaces,
                        git),
                startupRecovery,
                turnService,
                mock(AgentEventService.class),
                mock(AgentApprovalService.class),
                mock(AgentCompletionService.class),
                driverRegistry,
                mock(ModelSettingsService.class),
                mock(AgentResourceService.class),
                mock(RecentTouchRecorder.class),
                mock(PullRequestStore.class));
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsTypedProviderFailureBeforeInteractiveAllocation()
            throws Exception
    {
        AgentLaneId claude = new AgentLaneId("claude");
        when(taskService.getInteractive(any(), any())).thenAnswer(invocation -> {
            throw new AgentTaskNotFoundException(invocation.getArgument(1));
        });
        when(driverRegistry.require(claude)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(
                claude,
                AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                java.util.Optional.of("2.1.259"),
                "Claude sign-in required",
                AgentCapabilities.none(),
                java.util.Optional.of("claude auth login")));

        mvc.perform(post("/agent/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskId":"11111111-1111-1111-1111-111111111111",
                                "workspaceId":"workspace-1","message":"Review this",
                                "laneId":"claude","defaults":{"model":"opus","effort":"High",
                                "permissionPolicy":"AUTO"},"idempotencyKey":"create-1"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.laneId").value("claude"))
                .andExpect(jsonPath("$.readiness").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.diagnostic").value("Claude sign-in required"))
                .andExpect(jsonPath("$.signInCommand").value("claude auth login"));

        verify(taskService, never()).createInteractive(any());
        verify(turnService, never()).start(any());
        verifyNoInteractions(workspaces, git);
    }

    @Test
    void mapsDomainFailuresToStableHttpStatuses()
            throws Exception
    {
        String taskId = TaskId.random().toString();
        when(taskService.getInteractive(any(), any()))
                .thenThrow(new AgentTaskNotFoundException(TaskId.valueOf(taskId)));
        mvc.perform(get("/agent/tasks/{taskId}", taskId)
                        .queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/agent/tasks/{taskId}", taskId))
                .andExpect(status().isBadRequest());

        when(turnService.start(any())).thenThrow(new AgentConflictException("active turn"));
        mvc.perform(post("/agent/tasks/{taskId}/turns", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/agent/tasks/{taskId}/turns", taskId)
                        .header("Idempotency-Key", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());

        when(driverRegistry.statuses()).thenThrow(new AgentUnavailableException("provider unavailable"));
        mvc.perform(get("/agent/providers/status"))
                .andExpect(status().isServiceUnavailable());
    }

    private static AgentStartupRecovery startupRecovery()
    {
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentMaintenance maintenance = new AgentMaintenance(
                taskStore,
                turnStore,
                mock(QueuedInputStore.class),
                mock(SteerDeliveryStore.class),
                mock(AgentUnitOfWork.class),
                outboxStore,
                mock(AgentApprovalService.class),
                mock(AgentMetricsService.class),
                mock(AgentCompletionService.class),
                new AgentRuntimeRegistry(),
                Clock.systemUTC());
        return new AgentStartupRecovery(
                outboxStore,
                eventService,
                maintenance,
                mock(ProcessSupervisor.class),
                new AgentServingGate(),
                mock(AgentProcessLaunchStore.class),
                taskStore,
                turnStore);
    }
}
