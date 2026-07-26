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
package com.bytequay.app.scheduler;

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceAutomationState;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceAutomationStateStore;
import com.bytequay.app.service.threads.ParkedProposalService;
import com.bytequay.app.service.threads.TaskService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.service.workspaces.WorkspaceConfigurationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceQualityScanCoordinator
{
    @TempDir
    private Path clone;

    @Test
    void enabledWorkspaceQueuesAttributedPlannerTaskThroughThreadService()
    {
        Fixture fixture = fixture(enabledSettings());

        fixture.coordinator().tick();

        ArgumentCaptor<ThreadService.NewTaskRequest> request =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(fixture.threads()).materialiseTask(eq("quality-thread"), request.capture());
        assertThat(request.getValue().taskType())
                .isEqualTo(WorkspaceQualityScanCoordinator.TASK_TYPE);
        assertThat(request.getValue().origin()).isEqualTo("quality-scan");
        assertThat(request.getValue().initialPrompt())
                .contains("clean-code defects", "do not edit files");
        ArgumentCaptor<WorkspaceAutomationState> state =
                ArgumentCaptor.forClass(WorkspaceAutomationState.class);
        verify(fixture.states()).save(state.capture());
        assertThat(state.getValue().lastRunJson()).contains("\"outcome\":\"RUNNING\"");
    }

    @Test
    void disabledWorkspaceDoesNotCreateAgentWork()
    {
        Fixture fixture = fixture(WorkspaceSettingsDto.defaults());

        fixture.coordinator().tick();

        verify(fixture.threads(), never()).create(any());
        verify(fixture.threads(), never()).materialiseTask(any(), any());
        verify(fixture.states(), never()).save(any());
    }

    @Test
    void onlyConcreteHighConfidenceFindingBecomesRemoteIssueProposal()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode high = mapper.readTree("""
                {
                  "status": "finalized",
                  "goal": "Avoid repeated repository scans",
                  "understanding": {"summary": "FooService scans every row on each request."},
                  "intent": {
                    "summary": "Cache the immutable lookup.",
                    "steps": [{"ordinal": 1, "action": "Cache by repository id."}],
                    "validationStrategy": "Measure query count before and after."
                  },
                  "signals": {"confidence": "high"}
                }
                """);
        JsonNode low = high.deepCopy();
        ((ObjectNode) low.path("signals")).put("confidence", "low");
        JsonNode none = high.deepCopy();
        ((ObjectNode) none).put("goal", "NO_ACTIONABLE_FINDING: repository is healthy");

        assertThat(WorkspaceQualityScanCoordinator.isComplete(high)).isTrue();
        assertThat(WorkspaceQualityScanCoordinator.isPublishableFinding(high)).isTrue();
        assertThat(WorkspaceQualityScanCoordinator.isPublishableFinding(low)).isFalse();
        assertThat(WorkspaceQualityScanCoordinator.isNoActionableFinding(none)).isTrue();
        assertThat(WorkspaceQualityScanCoordinator.isPublishableFinding(none)).isFalse();
        assertThat(WorkspaceQualityScanCoordinator.issueTitle(high))
                .isEqualTo("Avoid repeated repository scans");
        assertThat(WorkspaceQualityScanCoordinator.issueBody(high))
                .contains("FooService", "Cache by repository id", "Measure query count");
        assertThat(WorkspaceQualityScanCoordinator.findingFingerprint(high))
                .isEqualTo(WorkspaceQualityScanCoordinator.findingFingerprint(high.deepCopy()));
    }

    @Test
    void failedPlanningTaskIsCancelledAndReportedInsteadOfBlockingForever()
    {
        Fixture fixture = fixture(enabledSettings());
        Task task = mock(Task.class);
        when(task.id()).thenReturn("scan-task");
        when(task.threadId()).thenReturn("quality-thread");
        when(task.status()).thenReturn(TaskStatus.RUNNING);
        when(task.createdAt()).thenReturn(Instant.now());
        when(fixture.taskStore().listByPhaseAndOrigin(
                TaskPhase.PLANNING, Task.ORIGIN_QUALITY_SCAN)).thenReturn(List.of(task));
        when(fixture.threadStore().findThreadById("quality-thread"))
                .thenReturn(Optional.of(fixture.thread()));
        UUID stageId = UUID.randomUUID();
        StageInstance stage = new StageInstance(
                stageId, "scan-task", StageType.PLAN_STAGE, StageState.ACTIVE,
                Instant.now(), null, null);
        when(fixture.stages().findActiveStage("scan-task")).thenReturn(Optional.of(stage));
        when(fixture.stages().findEventsByStage(stageId)).thenReturn(List.of(new StageEvent(
                UUID.randomUUID(), stageId, "scan-task", StageEventType.PLAN_FAILED,
                Instant.now(), "{}")));

        fixture.coordinator().tick();

        verify(fixture.taskService()).cancelTask("quality-thread", "scan-task");
        ArgumentCaptor<WorkspaceAutomationState> state =
                ArgumentCaptor.forClass(WorkspaceAutomationState.class);
        verify(fixture.states()).save(state.capture());
        assertThat(state.getValue().lastRunJson())
                .contains("\"outcome\":\"FAILED\"", "planning turn failed");
        verify(fixture.threads(), never()).materialiseTask(any(), any());
    }

    private Fixture fixture(WorkspaceSettingsDto settings)
    {
        WorkspaceService workspaces = mock(WorkspaceService.class);
        WorkspaceConfigurationService configuration =
                mock(WorkspaceConfigurationService.class);
        WorkspaceRepositoryResolver resolver = mock(WorkspaceRepositoryResolver.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        ThreadStore threadStore = mock(ThreadStore.class);
        TaskStore taskStore = mock(TaskStore.class);
        ThreadService threads = mock(ThreadService.class);
        StageStore stages = mock(StageStore.class);
        TaskService taskService = mock(TaskService.class);
        ParkedProposalService parked = mock(ParkedProposalService.class);
        WorkspaceAutomationStateStore states = mock(WorkspaceAutomationStateStore.class);
        Instant now = Instant.now();
        Workspace workspace = new Workspace("w1", "Widget", "", false, null, now, now);
        Thread thread = new Thread(
                "quality-thread", ThreadKind.CLI_AGENT, "claude-code", null,
                "Automated code quality", ThreadStatus.PENDING, null,
                0, 0, 0, now, now, null, null, ThreadFlow.BUILD,
                "w1", null, null, 1, null, null);
        when(workspaces.list()).thenReturn(List.of(workspace));
        when(workspaces.require("w1")).thenReturn(workspace);
        when(configuration.settings("w1")).thenReturn(settings);
        when(configuration.detached("w1")).thenReturn(false);
        when(resolver.resolve("w1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0, clone.toString(), null, null)));
        when(threadStore.listThreadsByWorkspace("w1")).thenReturn(List.of());
        when(taskStore.listByPhaseAndOrigin(
                TaskPhase.PLANNING, Task.ORIGIN_QUALITY_SCAN)).thenReturn(List.of());
        when(threads.create(any())).thenReturn(thread);
        when(states.find("w1", WorkspaceQualityScanCoordinator.KIND))
                .thenReturn(Optional.empty());
        WorkModelResolver workModels = mock(WorkModelResolver.class);
        when(workModels.resolveForWorkspace("w1", SessionAudience.PLAN))
                .thenReturn(new WorkModelResolver.Resolved(
                        new WorkModel(WorkModelKind.CLI, "claude-code", null, null),
                        new WorkModelResolver.Provenance(
                                WorkModelResolver.Source.WORKSPACE, "w1", "workspace Widget")));
        WorkspaceQualityScanCoordinator coordinator = new WorkspaceQualityScanCoordinator(
                workspaces, configuration, resolver, watchedRepos, threadStore,
                taskStore, threads, stages, taskService, parked, states,
                new ObjectMapper(), Runnable::run, workModels);
        return new Fixture(
                coordinator, threads, states, taskStore, threadStore, stages,
                taskService, thread);
    }

    private static WorkspaceSettingsDto enabledSettings()
    {
        return new WorkspaceSettingsDto(
                1, 10, true, 60, 8_000, 30,
                List.of("plan", "dev", "review", "ci-fix"),
                Map.of(), true, false, true, false);
    }

    private record Fixture(
            WorkspaceQualityScanCoordinator coordinator,
            ThreadService threads,
            WorkspaceAutomationStateStore states,
            TaskStore taskStore,
            ThreadStore threadStore,
            StageStore stages,
            TaskService taskService,
            Thread thread) {}
}
