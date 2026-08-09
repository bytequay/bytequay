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
import com.bytequay.app.developmentflow.stage.V2AutomationPlanService;
import com.bytequay.app.developmentflow.stage.V2AutomationPlanService.Snapshot;
import com.bytequay.app.developmentflow.stage.V2AutomationPlanService.State;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceAutomationState;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceAutomationStateStore;
import com.bytequay.app.service.threads.ParkedProposalService;
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
    void dependsOnTypedV2PlanOwnerOnly()
    {
        assertThat(WorkspaceQualityScanCoordinator.class.getConstructors()[0]
                .getParameterTypes())
                .extracting(Class::getSimpleName)
                .doesNotContain("StageStore", "TaskService");
    }

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
        when(fixture.plans().listCurrent(
                "w1", Task.ORIGIN_QUALITY_SCAN,
                WorkspaceQualityScanCoordinator.TASK_TYPE))
                .thenReturn(List.of(snapshot(State.FAILED, "{}")));

        fixture.coordinator().tick();

        verify(fixture.taskControls()).cancelByAutomation(
                "scan-task", WorkspaceQualityScanCoordinator.KIND);
        ArgumentCaptor<WorkspaceAutomationState> state =
                ArgumentCaptor.forClass(WorkspaceAutomationState.class);
        verify(fixture.states()).save(state.capture());
        assertThat(state.getValue().lastRunJson())
                .contains("\"outcome\":\"FAILED\"", "planning turn failed");
        verify(fixture.threads(), never()).materialiseTask(any(), any());
    }

    @Test
    void reviewedFindingClosesV2TaskBeforeParkingUserGatedIssue()
            throws Exception
    {
        Fixture fixture = fixture(enabledSettings());
        JsonNode finding = new ObjectMapper().readTree("""
                {"status":"finalized","goal":"Avoid repeated scans",
                 "understanding":{"summary":"The service scans every row."},
                 "intent":{"steps":[{"action":"Cache by repository id."}]},
                 "signals":{"confidence":"high"}}
                """);
        when(fixture.plans().listCurrent(
                "w1", Task.ORIGIN_QUALITY_SCAN,
                WorkspaceQualityScanCoordinator.TASK_TYPE))
                .thenReturn(List.of(snapshot(State.REVIEWED, finding.toString())));
        fixture.coordinator().tick();

        verify(fixture.taskControls()).cancelByAutomation(
                "scan-task", WorkspaceQualityScanCoordinator.KIND);
        verify(fixture.parked()).parkReadOnlyV2Proposal(
                eq("quality-thread"), eq("scan-task"), any());
        verify(fixture.parked(), never()).park(any(), any());
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
        V2AutomationPlanService plans = mock(V2AutomationPlanService.class);
        V2TaskControlService taskControls = mock(V2TaskControlService.class);
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
        when(plans.listCurrent(any(), any(), any())).thenReturn(List.of());
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
                taskStore, threads, plans, taskControls, parked, states,
                new ObjectMapper(), Runnable::run, workModels);
        return new Fixture(
                coordinator, threads, states, taskStore, threadStore, plans,
                taskControls, parked, thread);
    }

    private static Snapshot snapshot(State state, String content)
    {
        return new Snapshot(
                "scan-task", "quality-thread", "w1", Task.ORIGIN_QUALITY_SCAN,
                WorkspaceQualityScanCoordinator.TASK_TYPE, null, Instant.now(),
                1, 4, "plan-stage", 1L, 3L, "revision", content,
                "self-review", state, state == State.FAILED ? "PLAN_REVIEW_FAILURE" : null);
    }

    private static WorkspaceSettingsDto enabledSettings()
    {
        return new WorkspaceSettingsDto(
                1, 10, true, 60, 8_000, 30,
                List.of("plan", "dev", "review", "ci-fix"),
                Map.of(), true, false, true, false, null);
    }

    private record Fixture(
            WorkspaceQualityScanCoordinator coordinator,
            ThreadService threads,
            WorkspaceAutomationStateStore states,
            TaskStore taskStore,
            ThreadStore threadStore,
            V2AutomationPlanService plans,
            V2TaskControlService taskControls,
            ParkedProposalService parked,
            Thread thread) {}
}
