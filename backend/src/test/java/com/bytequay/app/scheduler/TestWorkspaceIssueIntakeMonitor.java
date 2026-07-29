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
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceAutomationState;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceAutomationStateStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceConfigurationService;
import com.bytequay.app.service.workspaces.WorkspaceIssueService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.scheduler.WorkspaceIssueIntakeMonitor.AUTOMATION_KIND;
import static com.bytequay.app.scheduler.WorkspaceIssueIntakeMonitor.Route.AUTO_IMPLEMENT;
import static com.bytequay.app.scheduler.WorkspaceIssueIntakeMonitor.Route.BACKLOG_PERMISSION;
import static com.bytequay.app.scheduler.WorkspaceIssueIntakeMonitor.TRIAGE_TASK_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceIssueIntakeMonitor
{
    @Test
    void dependsOnTypedV2PlanOwnerOnly()
    {
        assertThat(WorkspaceIssueIntakeMonitor.class.getConstructors()[0]
                .getParameterTypes())
                .extracting(Class::getSimpleName)
                .doesNotContain(
                        "StageStore", "PlanStageService", "TaskService",
                        "RetiredThreadTurnScheduler");
    }

    @Test
    void onlyHighConfidenceLowRiskSmallPlansStartImplementation()
            throws Exception
    {
        assertThat(WorkspaceIssueIntakeMonitor.classify(plan("high", "low", "small")))
                .isEqualTo(AUTO_IMPLEMENT);
        assertThat(WorkspaceIssueIntakeMonitor.classify(plan("medium", "low", "small")))
                .isEqualTo(BACKLOG_PERMISSION);
        assertThat(WorkspaceIssueIntakeMonitor.classify(plan("high", "medium", "small")))
                .isEqualTo(BACKLOG_PERMISSION);
        assertThat(WorkspaceIssueIntakeMonitor.classify(plan("high", "low", "large")))
                .isEqualTo(BACKLOG_PERMISSION);
    }

    @Test
    void isolatesWorkspaceFailuresAndBaselinesEveryOtherEnabledWorkspace(@TempDir Path clone)
            throws Exception
    {
        Fixture fixture = new Fixture();
        Workspace broken = workspace("broken", false);
        Workspace healthy = workspace("healthy", false);
        Workspace scratch = workspace("scratch", true);
        when(fixture.workspaces.list()).thenReturn(List.of(broken, healthy, scratch));
        when(fixture.resolver.resolve("broken")).thenReturn(repo("acme", "broken"));
        when(fixture.resolver.resolve("healthy")).thenReturn(repo("acme", "healthy"));
        when(fixture.watched.find("acme", "broken"))
                .thenThrow(new IllegalStateException("watched repo lookup failed"));
        when(fixture.watched.find("acme", "healthy"))
                .thenReturn(Optional.of(watched("acme", "healthy", clone)));
        when(fixture.repos.getOpenRepoIssuesAfter("acme", "healthy", null))
                .thenReturn(new RepoService.IssueIntakeBatch(List.of(), 44));

        fixture.monitor().tick();

        assertThat(fixture.states.find("healthy", AUTOMATION_KIND))
                .get().extracting(WorkspaceAutomationState::cursor).isEqualTo(44);
        JsonNode brokenHealth = fixture.mapper.readTree(fixture.states
                .find("broken", AUTOMATION_KIND).orElseThrow().lastRunJson());
        assertThat(brokenHealth.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(brokenHealth.path("lastError").asText())
                .isEqualTo("watched repo lookup failed");
        verify(fixture.resolver, never()).resolve("scratch");
    }

    @Test
    void queuesUnseenIssuesWithMonitorOrigin(@TempDir Path clone)
    {
        Fixture fixture = new Fixture();
        Workspace workspace = workspace("workspace", false);
        fixture.configure(workspace, clone);
        fixture.states.save(new WorkspaceAutomationState(
                workspace.id(), AUTOMATION_KIND, 10, null, Instant.now()));
        RepoIssue issue = issue(11);
        when(fixture.repos.getOpenRepoIssuesAfter("acme", "repo", 10))
                .thenReturn(new RepoService.IssueIntakeBatch(List.of(issue), 11));
        IssueDetail detail = mock(IssueDetail.class);
        when(detail.body()).thenReturn("Small display bug");
        when(fixture.repos.getIssueDetail("acme", "repo", 11)).thenReturn(detail);
        when(fixture.workspaceIssues.linkedTrunks("workspace", 11)).thenReturn(List.of());
        when(fixture.workspaceIssues.linkToTrunk("workspace", 11, null)).thenReturn("thread");
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("thread");
        when(fixture.threads.find("thread")).thenReturn(Optional.of(thread));
        when(fixture.tasks.listTasksByThread("thread")).thenReturn(List.of());

        fixture.monitor().tick();

        ArgumentCaptor<ThreadService.NewTaskRequest> request =
                ArgumentCaptor.forClass(ThreadService.NewTaskRequest.class);
        verify(fixture.threads).materialiseTask(eq("thread"), request.capture());
        assertThat(request.getValue().taskType()).isEqualTo(TRIAGE_TASK_TYPE);
        assertThat(request.getValue().linkedIssueNumber()).isEqualTo(11);
        assertThat(request.getValue().workspaceId()).isEqualTo("workspace");
        assertThat(request.getValue().origin()).isEqualTo(Task.ORIGIN_ISSUE_MONITOR);
        assertThat(fixture.states.find("workspace", AUTOMATION_KIND))
                .get().extracting(WorkspaceAutomationState::cursor).isEqualTo(11);
    }

    @Test
    void startsOnlyLocalImplementationForSafePlans(@TempDir Path clone)
            throws Exception
    {
        Fixture fixture = new Fixture();
        Workspace workspace = workspace("workspace", false);
        fixture.configure(workspace, clone);
        fixture.states.save(new WorkspaceAutomationState(
                workspace.id(), AUTOMATION_KIND, 10, null, Instant.now()));
        JsonNode safePlan = plan("high", "low", "small");
        Snapshot snapshot = snapshot(safePlan);
        when(fixture.plans.listCurrent(
                "workspace", Task.ORIGIN_ISSUE_MONITOR, TRIAGE_TASK_TYPE))
                .thenReturn(List.of(snapshot));

        WorkspaceIssueIntakeMonitor monitor = fixture.monitor();
        monitor.tick();

        verify(fixture.plans).approveIssueIntake(snapshot);
        verifyNoInteractions(fixture.taskControls);
        WorkspaceIssueIntakeMonitor.MonitorStatus status = monitor.status("workspace");
        assertThat(status.implementationsStarted()).isEqualTo(1);
        assertThat(status.lastOutcome()).isEqualTo("SUCCESS");
        assertThat(status.lastRunAt()).isNotNull();
    }

    @Test
    void asksBeforeBackloggingUnsafePlansAndCancelsTheirTriage(@TempDir Path clone)
            throws Exception
    {
        Fixture fixture = new Fixture();
        Workspace workspace = workspace("workspace", false);
        fixture.configure(workspace, clone);
        fixture.states.save(new WorkspaceAutomationState(
                workspace.id(), AUTOMATION_KIND, 10, null, Instant.now()));
        Snapshot snapshot = snapshot(plan("medium", "low", "small"));
        when(fixture.plans.listCurrent(
                "workspace", Task.ORIGIN_ISSUE_MONITOR, TRIAGE_TASK_TYPE))
                .thenReturn(List.of(snapshot));

        fixture.monitor().tick();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(fixture.threads).sendTrunkUnattended(
                eq("thread"), prompt.capture(), eq("remote-issue-backlog-permission"));
        assertThat(prompt.getValue()).contains(
                "Do not create a backlog item yet",
                "ask_user_question",
                "https://github.com/acme/repo/issues/11");
        var ordered = inOrder(fixture.taskControls, fixture.threads);
        ordered.verify(fixture.taskControls).cancelByAutomation(
                "task", AUTOMATION_KIND);
        ordered.verify(fixture.threads).sendTrunkUnattended(
                eq("thread"), any(), eq("remote-issue-backlog-permission"));
        verify(fixture.plans, never()).approveIssueIntake(any());
    }

    private static Snapshot snapshot(JsonNode plan)
    {
        return new Snapshot(
                "task", "thread", "workspace", Task.ORIGIN_ISSUE_MONITOR,
                TRIAGE_TASK_TYPE, 11, Instant.now(), 1, 7,
                "plan-stage", 1L, 8L, "revision", plan.toString(),
                "self-review", State.REVIEWED, null);
    }

    private static JsonNode plan(String confidence, String risk, String complexity)
            throws JsonProcessingException
    {
        return new ObjectMapper().readTree("""
                {
                  "status": "finalized",
                  "goal": "Fix the reported problem",
                  "intent": {"steps": [{"action": "Change the failing path"}]},
                  "signals": {
                    "confidence": "%s",
                    "riskLevel": "%s",
                    "estimatedComplexity": "%s"
                  }
                }
                """.formatted(confidence, risk, complexity));
    }

    private static Workspace workspace(String id, boolean scratch)
    {
        return new Workspace(id, id, "", scratch, null, Instant.now(), Instant.now());
    }

    private static WorkspaceRepositoryResolver.RepositoryIdentity repo(String owner, String repo)
    {
        return new WorkspaceRepositoryResolver.RepositoryIdentity(
                owner, repo, owner + "/" + repo, "main");
    }

    private static WatchedRepo watched(String owner, String repo, Path clone)
    {
        return new WatchedRepo(1, owner, repo, 0, clone.toString(), null, null);
    }

    private static RepoIssue issue(int number)
    {
        return new RepoIssue(
                number, number, "Issue " + number, "reporter", "open",
                "https://github.com/acme/repo/issues/" + number,
                Instant.now(), List.of(), 0);
    }

    private static final class Fixture
    {
        private final WorkspaceService workspaces = mock(WorkspaceService.class);
        private final WorkspaceConfigurationService configuration =
                mock(WorkspaceConfigurationService.class);
        private final WorkspaceRepositoryResolver resolver =
                mock(WorkspaceRepositoryResolver.class);
        private final WatchedRepoStore watched = mock(WatchedRepoStore.class);
        private final RepoService repos = mock(RepoService.class);
        private final WorkspaceIssueService workspaceIssues = mock(WorkspaceIssueService.class);
        private final ThreadService threads = mock(ThreadService.class);
        private final TaskStore tasks = mock(TaskStore.class);
        private final V2TaskControlService taskControls =
                mock(V2TaskControlService.class);
        private final V2AutomationPlanService plans = mock(V2AutomationPlanService.class);
        private final MemoryStates states = new MemoryStates();
        private final ObjectMapper mapper = new ObjectMapper();

        private Fixture()
        {
            WorkspaceSettingsDto settings = mock(WorkspaceSettingsDto.class);
            when(settings.remoteIssueIntakeEnabled()).thenReturn(true);
            when(configuration.settings(any())).thenReturn(settings);
            when(configuration.detached(any())).thenReturn(false);
            when(plans.listCurrent(any(), any(), any())).thenReturn(List.of());
        }

        private void configure(Workspace workspace, Path clone)
        {
            when(workspaces.list()).thenReturn(List.of(workspace));
            when(workspaces.require(workspace.id())).thenReturn(workspace);
            when(resolver.resolve(workspace.id())).thenReturn(repo("acme", "repo"));
            when(watched.find("acme", "repo"))
                    .thenReturn(Optional.of(watched("acme", "repo", clone)));
            when(repos.getOpenRepoIssuesAfter("acme", "repo", null))
                    .thenReturn(new RepoService.IssueIntakeBatch(List.of(), 0));
            when(repos.getOpenRepoIssuesAfter("acme", "repo", 10))
                    .thenReturn(new RepoService.IssueIntakeBatch(List.of(), 10));
        }

        private WorkspaceIssueIntakeMonitor monitor()
        {
            return new WorkspaceIssueIntakeMonitor(
                    workspaces,
                    configuration,
                    resolver,
                    watched,
                    repos,
                    workspaceIssues,
                    threads,
                    tasks,
                    taskControls,
                    plans,
                    states,
                    mapper,
                    Runnable::run);
        }
    }

    private static final class MemoryStates
            implements WorkspaceAutomationStateStore
    {
        private final Map<String, WorkspaceAutomationState> values = new HashMap<>();

        @Override
        public Optional<WorkspaceAutomationState> find(String workspaceId, String kind)
        {
            return Optional.ofNullable(values.get(workspaceId + "\0" + kind));
        }

        @Override
        public void save(WorkspaceAutomationState state)
        {
            values.put(state.workspaceId() + "\0" + state.kind(), state);
        }
    }
}
