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
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.WorktreeMutationClaim;
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
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.agent.AgentStartupRecovery;
import com.bytequay.app.agent.AgentTaskStartupProgress;
import com.bytequay.app.agent.InteractiveAgentTaskCreator;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.agent.LocalReviewSessionService;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private AgentCompletionService completionService;
    private CiAutofixStore ciRuns;
    private CiAutofixService ci;
    private LocalReviewSessionService localReviewSessions;

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
        completionService = mock(AgentCompletionService.class);
        ciRuns = mock(CiAutofixStore.class);
        ci = mock(CiAutofixService.class);
        localReviewSessions = mock(LocalReviewSessionService.class);
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.developmentAutomation()).thenReturn(DevelopmentAutomation.DEFAULT);
        com.bytequay.app.context.RepositoryContextService repositoryContext = mock(com.bytequay.app.context.RepositoryContextService.class);
        when(repositoryContext.getNotebook(org.mockito.ArgumentMatchers.anyString())).thenReturn(com.bytequay.core.context.RepositoryNotebook.empty());
        AgentTaskStartupProgress startupProgress = new AgentTaskStartupProgress();
        AgentController controller = new AgentController(
                taskService,
                new InteractiveAgentTaskCreator(
                        taskService,
                        turnService,
                        driverRegistry,
                        mock(ModelSettingsService.class),
                        workspaces,
                        mock(WorkspaceProvisioningService.class),
                        git,
                        appSettings,
                        startupProgress,
                        mock(com.bytequay.agent.core.AgentTaskTitleService.class),
                        mock(PullRequestStore.class),
                        mock(CredentialService.class)),
                startupRecovery,
                turnService,
                mock(AgentEventService.class),
                mock(AgentApprovalService.class),
                completionService,
                mock(AgentMetricsService.class),
                driverRegistry,
                mock(ModelSettingsService.class),
                mock(AgentResourceService.class),
                mock(RecentTouchRecorder.class),
                mock(PullRequestStore.class),
                mock(PullRequestTaskStore.class),
                new ManagedWorktreeService(new WorkspacePaths(System.getProperty("java.io.tmpdir")), mock(WorkspaceStore.class), git),
                mock(PullRequestService.class),
                mock(WorkspaceStore.class),
                appSettings,
                ciRuns,
                ci,
                startupProgress,
                localReviewSessions,
                new AgentStreamRegistry(),
                mock(IssueDevelopmentService.class),
                repositoryContext);
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
    void refusesToDeleteARunningTaskAndTreatsAnAbsentOneAsDeleted()
            throws Exception
    {
        TaskId taskId = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(
                taskId,
                "workspace-1",
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktrees").resolve(taskId.toString()),
                "bytequay/task/" + taskId,
                "0123456789012345678901234567890123456789");
        AgentTask task = AgentTask.created(
                taskId,
                true,
                "Delete me",
                TitleSource.USER,
                AgentLaneId.CODEX_CLI,
                worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                Instant.parse("2026-08-16T00:00:00Z"));
        when(taskService.getInteractive("workspace-1", taskId)).thenReturn(task);
        doThrow(new AgentConflictException("Task has an active turn")).when(completionService).delete(taskId);

        mvc.perform(delete("/agent/tasks/{taskId}", taskId.toString()).queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isConflict());
        // A patch must say what changes; neither or both is a bad request, not a guess.
        mvc.perform(patch("/agent/tasks/{taskId}", taskId.toString())
                        .queryParam("workspaceId", "workspace-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        when(taskService.getInteractive("workspace-1", taskId)).thenThrow(new AgentTaskNotFoundException(taskId));
        mvc.perform(delete("/agent/tasks/{taskId}", taskId.toString()).queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isNoContent());
        verify(completionService).delete(taskId);
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
        when(taskService.get(TaskId.valueOf(taskId))).thenReturn(AgentTask.created(
                TaskId.valueOf(taskId),
                java.util.Optional.of("workspace-1"),
                true,
                "Active task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktrees").resolve(taskId),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO),
                Instant.parse("2026-08-16T00:00:00Z")));
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

    @Test
    void localReviewSyncReturnsTheUpdatedHeadAndRetainedExperimentWarning()
            throws Exception
    {
        AgentTask task = deliveryTask().withDevelopmentContinuation(false, Instant.EPOCH)
                .withLocalReviewHead(Optional.of("a".repeat(40)), Instant.EPOCH).withVersion(7);
        AgentTask updated = task.withLocalReviewHead(Optional.of("b".repeat(40)), Instant.EPOCH).withVersion(8);
        String warning = "Private experiments conflicted with the updated code and were discarded.";
        when(taskService.getInteractive("workspace-1", task.id())).thenReturn(task);
        when(localReviewSessions.synchronize(task.id(), 7)).thenReturn(new LocalReviewSessionService.SyncResult(updated, Optional.of(warning)));

        mvc.perform(post("/agent/tasks/{taskId}/local-review/sync", task.id()).queryParam("workspaceId", "workspace-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(task.id().toString()))
                .andExpect(jsonPath("$.task.localReviewHead").value("b".repeat(40)))
                .andExpect(jsonPath("$.task.version").value(8))
                .andExpect(jsonPath("$.warning").value(warning));

        var order = inOrder(taskService, localReviewSessions);
        order.verify(taskService).getInteractive("workspace-1", task.id());
        order.verify(localReviewSessions).synchronize(task.id(), 7);
        verifyNoMoreInteractions(localReviewSessions);
    }

    @Test
    void localReviewSyncChecksWorkspaceOwnershipBeforeChangingTheCheckout()
            throws Exception
    {
        TaskId taskId = TaskId.random();
        when(taskService.getInteractive(any(), any())).thenThrow(new AgentTaskNotFoundException(taskId));

        mvc.perform(post("/agent/tasks/{taskId}/local-review/sync", taskId).queryParam("workspaceId", "another-workspace")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/agent/tasks/{taskId}/local-review/sync", TaskId.random()).queryParam("workspaceId", "workspace-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/agent/tasks/{taskId}/local-review/sync", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(localReviewSessions, turnService, git);
    }

    @Test
    void taskReadsExposeLinkedCiWithoutDrivingItOrInspectingGit()
            throws Exception
    {
        AgentTask task = deliveryTask();
        Instant now = task.createdAt();
        String head = "a".repeat(40);
        CiAutofixRun run = CiAutofixRun.create(CiAutofixRunId.random(), "owned-ci",
                new CiAutofixRun.Scope(new PullRequestRef("bytequay", "sample", 42), RepoRef.of("bytequay", "sample"), "workspace-1", RepoRef.of("bytequay", "sample"), "author",
                        task.taskWorktree().orElseThrow().generatedBranch(), "main", "claude-cli", Optional.of(task.id().toString())),
                head, head, head, new CiAutofixRun.Authorization(true, true, true, 3, now, now.plusSeconds(28_800)), now);
        when(taskService.getInteractive("workspace-1", task.id())).thenReturn(task);
        when(ciRuns.listByParentTaskId(task.id().toString())).thenReturn(ImmutableList.of(run));
        when(ci.canMessage(run)).thenReturn(false);

        mvc.perform(get("/agent/tasks/{taskId}", task.id()).queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.developmentCi.runId").value(run.id().toString()))
                .andExpect(jsonPath("$.developmentCi.activity").value("PREPARING_AGENT"))
                .andExpect(jsonPath("$.developmentCi.attemptsUsed").value(0))
                .andExpect(jsonPath("$.developmentCi.maxAttempts").value(3))
                .andExpect(jsonPath("$.developmentCi.canMessage").value(false))
                .andExpect(jsonPath("$.developmentCi.pullRequestUrl").value("https://github.com/bytequay/sample/pull/42"));
        verify(ci).canMessage(run);
        verifyNoMoreInteractions(ci);
        verifyNoInteractions(turnService, completionService, git);
    }

    @Test
    void deleteStopsDeliveryAndRemovesLinkedLocalWorkBeforeTheParent()
            throws Exception
    {
        AgentTask task = deliveryTask();
        when(taskService.getInteractive("workspace-1", task.id())).thenReturn(task);
        when(turnService.stopImpact(task.id())).thenReturn(new AgentRuntimeStopImpact(task.id(), "owner-generation", Optional.empty(), false));

        mvc.perform(delete("/agent/tasks/{taskId}", task.id()).queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isNoContent());
        var order = inOrder(turnService, ci, completionService);
        order.verify(turnService).stopImpact(task.id());
        order.verify(turnService).stopRuntime(task.id(), "owner-generation");
        order.verify(ci).deleteDevelopment(task.id());
        order.verify(completionService).delete(task.id());
    }

    @Test
    void unknownCiWriterPreventsParentDeletion()
            throws Exception
    {
        AgentTask task = deliveryTask().withDevelopmentContinuation(false, Instant.EPOCH);
        when(taskService.getInteractive("workspace-1", task.id())).thenReturn(task);
        doThrow(new AgentConflictException("CI repair agent stop is unproven")).when(ci).deleteDevelopment(task.id());

        mvc.perform(delete("/agent/tasks/{taskId}", task.id()).queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isConflict());
        verify(completionService, never()).delete(any());
    }

    @Test
    void markedRecoveredParentUsesRecoveryProofBeforeDelete()
            throws Exception
    {
        AgentTask task = deliveryTask().claimWorktreeMutation(WorktreeMutationClaim.recovery("unverified-provider", Instant.EPOCH), Instant.EPOCH);
        when(taskService.getInteractive("workspace-1", task.id())).thenReturn(task);
        when(turnService.stopImpact(task.id())).thenReturn(new AgentRuntimeStopImpact(task.id(), "task:0", Optional.empty(), false));

        // The separate recovery store cannot establish ownership; Delete must propagate that refusal.
        mvc.perform(delete("/agent/tasks/{taskId}", task.id()).queryParam("workspaceId", "workspace-1"))
                .andExpect(status().isNotFound());
        verify(turnService, never()).stopRuntime(any(), any());
        verifyNoInteractions(ci, completionService);
    }

    @Test
    void markingFailedCannotBypassLinkedWriterShutdown()
            throws Exception
    {
        AgentTask task = deliveryTask();
        when(taskService.getInteractive("workspace-1", task.id())).thenReturn(task);

        mvc.perform(post("/agent/tasks/{taskId}/mark-failed", task.id()).queryParam("workspaceId", "workspace-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Start over\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(completionService);
    }

    private static AgentTask deliveryTask()
    {
        TaskId id = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(id, "workspace-1", new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-worktrees").resolve(id.toString()), "bytequay/task/" + id, "a".repeat(40));
        return AgentTask.created(id, true, "Delivery", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH).withDevelopmentContinuation(true, Instant.EPOCH);
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
                new AgentRuntimeRegistry(ignored -> {}),
                Clock.systemUTC());
        return new AgentStartupRecovery(
                outboxStore,
                eventService,
                maintenance,
                mock(ProcessSupervisor.class),
                new AgentServingGate(),
                mock(AgentProcessLaunchStore.class),
                taskStore,
                turnStore, mock(AgentTurnService.class));
    }
}
