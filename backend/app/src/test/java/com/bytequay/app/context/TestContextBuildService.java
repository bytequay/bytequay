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
package com.bytequay.app.context;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.core.context.ContextPreparationResult.Outcome.EMPTY;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextBuildService
{
    private static final String WORKSPACE = "workspace";
    private static final String SHA = "a".repeat(40);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETE_AFTER = NOW.plus(Duration.ofDays(7));
    private static final Path REPOSITORY_PATH = Path.of("/tmp/context-learn-repository");
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");
    private static final RepoRef FORK = RepoRef.of("contributor", "repository");
    private static final AgentLaneId LANE = new AgentLaneId("codex");
    private static final AgentTurnSettings TURN_SETTINGS = new AgentTurnSettings(Optional.of("model"), Optional.of("medium"),
            AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of());

    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentTaskStore taskStore = mock(AgentTaskStore.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentCompletionService completions = mock(AgentCompletionService.class);
    private final AgentApprovalService approvals = mock(AgentApprovalService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final ContextBuildCoordinator coordinator = mock(ContextBuildCoordinator.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final RepositoryContextService notebooks = new RepositoryContextService(settings, mapper, workspaceStore, reviews);
    private final ContextBuildStore builds = new ContextBuildStore(settings, taskStore, mapper);
    private final RepositoryContextPreparation preparation = new RepositoryContextPreparation(notebooks, mock(ContextInputReader.class), workspaces);
    private final Map<TaskId, AgentTask> savedTasks = new LinkedHashMap<>();
    private final Map<TurnId, AgentTurn> savedTurns = new LinkedHashMap<>();
    private ContextBuildService service;

    @BeforeEach
    void setup()
    {
        when(workspaceStore.findById(WORKSPACE)).thenReturn(Optional.of(new Workspace(WORKSPACE, "Repository", 1, FORK,
                Optional.of(7L), Optional.of(REPOSITORY), 7, REPOSITORY, Workspace.Topology.FORK, "main", "https://github.com/contributor/repository.git",
                Optional.of("main"), Optional.of("https://github.com/owner/repository.git"), REPOSITORY_PATH.toString(), Optional.empty(), Optional.empty(), 0, NOW, NOW)));
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE, REPOSITORY_PATH, 1, FORK,
                OptionalLong.of(7), REPOSITORY, "upstream", "origin", "main", ImmutableList.of()));
        when(models.agentModel(AgentModelType.REVIEW, Optional.of(WORKSPACE)))
                .thenReturn(new AgentModelSetting(AgentModelType.REVIEW, true, LANE.value(), "Codex", "model", "Model", Optional.of("medium")));
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("token", new UserProfile("contributor", "Contributor", "", "", 0, 0, 0, "", "", "", "")));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        when(git.run(REPOSITORY_PATH, "rev-parse", "--verify", "refs/remotes/upstream/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));
        when(taskStore.find(any(TaskId.class))).thenAnswer(call -> Optional.ofNullable(savedTasks.get(call.getArgument(0))));
        when(tasks.get(any(TaskId.class))).thenAnswer(call -> savedTasks.get(call.getArgument(0)));
        when(turnStore.find(any(TurnId.class))).thenAnswer(call -> Optional.ofNullable(savedTurns.get(call.getArgument(0))));
        when(turns.findTurnByIdempotencyKey(any(TaskId.class), anyString())).thenAnswer(call -> savedTurns.values().stream()
                .filter(turn -> turn.taskId().equals(call.getArgument(0)) && turn.idempotencyKey().equals(call.getArgument(1))).findFirst());
        when(tasks.create(any(CreateAgentTaskCommand.class))).thenAnswer(call -> {
            CreateAgentTaskCommand command = call.getArgument(0);
            ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
            assertThat(bound.taskId()).isEqualTo(command.taskId().toString());
            assertThat(bound.baselineSha()).isEqualTo(command.startCommit());
            assertThat(bound.branch()).isEqualTo(command.generatedBranch());
            TaskWorktree tree = new TaskWorktree(command.taskId(), WORKSPACE, command.physicalRepository(),
                    Path.of("/tmp/context-learn-tasks").resolve(command.taskId().toString()), command.generatedBranch(), command.startCommit());
            AgentTask task = AgentTask.created(command.taskId(), false, command.title(), TitleSource.USER, command.laneId(), tree, command.defaults(), NOW);
            savedTasks.put(task.id(), task);
            return task;
        });
        when(turns.start(any(StartAgentTurnCommand.class))).thenAnswer(call -> {
            StartAgentTurnCommand command = call.getArgument(0);
            ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
            assertThat(command.taskId().toString()).isEqualTo(bound.taskId());
            assertThat(command.idempotencyKey()).isEqualTo(bound.turnKey());
            assertThat(command.developmentAutomation()).isEmpty();
            AgentTurn turn = AgentTurn.accepted(TurnId.random(), command.taskId(), command.idempotencyKey(), TURN_SETTINGS, NOW)
                    .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty());
            savedTurns.put(turn.id(), turn);
            savedTasks.compute(turn.taskId(), (id, task) -> task.claimWorktreeMutation(WorktreeMutationClaim.turn(turn.id(), NOW), NOW));
            return turn;
        });
        when(turns.cancel(any(TurnId.class))).thenAnswer(call -> savedTurns.compute(call.getArgument(0), (id, turn) -> turn.requestCancellation(NOW)));
        when(completions.abandon(any(TaskId.class), anyString())).thenAnswer(call -> savedTasks.compute(call.getArgument(0),
                (id, task) -> task.fail(call.getArgument(1), NOW, DELETE_AFTER)));
        when(completions.complete(any(CompleteAgentTaskCommand.class))).thenAnswer(call -> {
            CompleteAgentTaskCommand command = call.getArgument(0);
            TaskSummary summary = new TaskSummary("Learned repository", Optional.empty(), AgentMetrics.empty(), command.notes());
            savedTasks.compute(command.taskId(), (id, task) -> task.complete(summary, NOW, DELETE_AFTER));
            return new TaskCompletion(command.taskId(), summary, NOW, DELETE_AFTER);
        });
        doAnswer(call -> {
            savedTasks.compute(call.getArgument(0), (id, task) -> task.withWorktreeRemovedAt(NOW));
            return null;
        }).when(completions).cleanupWorktree(any(TaskId.class));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("contextBuildCoordinator", coordinator);
        service = new ContextBuildService(builds, notebooks, preparation, workspaces, models, credentials, drivers, tasks, taskStore, turns, turnStore,
                completions, approvals, beans.getBeanProvider(ContextBuildCoordinator.class), git, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void explicitlyLaunchesWithoutAReviewAndFreezesBindingBeforeTaskAndTurnEvenWhenReviewsAreOff()
    {
        notebooks.setUseInReviews(WORKSPACE, 0, false);
        notebooks.configure(WORKSPACE, 1, new EditContextConfiguration(Optional.empty(), Optional.of(ImmutableList.of(new EditContextConfiguration.Source(
                "docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE, "https://example.com/docs"))), Optional.empty()));
        assertThat(service.get(WORKSPACE).build()).isEmpty();
        verifyNoInteractions(tasks, turns, drivers, git, reviews, coordinator);
        ContextBuildService.Request request = new ContextBuildService.Request("learn", 2, 0, ImmutableList.of("project"),
                ImmutableList.of(new ContextBuild.History(ContextHistoryReader.SourceKind.PR, 12)));

        ContextBuildService.View view = service.start(WORKSPACE, request);

        ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
        assertThat(view.buildRequestSequence()).isEqualTo(1);
        assertThat(view.canStart()).isFalse();
        assertThat(bound.repository()).isEqualTo(REPOSITORY);
        assertThat(bound.physicalRepository().owner()).isEqualTo(FORK.owner());
        assertThat(bound.repositoryId()).isEqualTo(7);
        assertThat(bound.baselineSha()).isEqualTo(SHA);
        assertThat(bound.history()).isEqualTo(request.history());
        assertThat(bound.documentationSources()).containsExactly(new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs", ContextEntryOrigin.USER));
        AgentTask task = savedTasks.get(TaskId.valueOf(bound.taskId()));
        assertThat(task.interactive()).isFalse();
        assertThat(task.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(notebooks.getNotebook(WORKSPACE).useInReviews()).isFalse();
        ArgumentCaptor<StartAgentTurnCommand> start = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        InOrder order = inOrder(tasks, coordinator, turns);
        order.verify(tasks).create(any(CreateAgentTaskCommand.class));
        order.verify(coordinator).watch(bound);
        order.verify(turns).start(start.capture());
        assertThat(start.getValue().message()).contains("directory structure", "repository documentation and files", "every registered documentation source");
        verifyNoInteractions(reviews);
    }

    @Test
    void unavailableProviderFailsBeforeAllocatingBindingTaskOrWorktree()
    {
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(), "Sign in", AgentCapabilities.none()));

        assertThatThrownBy(() -> service.start(WORKSPACE, request("learn", 0))).isInstanceOf(AgentProviderUnavailableException.class);

        assertThat(builds.find(WORKSPACE)).isEmpty();
        verifyNoInteractions(tasks, turns, git, credentials, coordinator);
    }

    @Test
    void duplicateRequestsReplayAndStaleOrChangedRequestsCannotReplaceAnOpenTask()
    {
        ContextBuildService.Request request = request("first", 0);
        ContextBuildService.View first = service.start(WORKSPACE, request);
        assertThat(service.start(WORKSPACE, request)).isEqualTo(first);
        assertThatThrownBy(() -> service.start(WORKSPACE, new ContextBuildService.Request("first", 0, 0, ImmutableList.of("project"),
                ImmutableList.of(new ContextBuild.History(ContextHistoryReader.SourceKind.ISSUE, 3)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different learning inputs");
        assertThatThrownBy(() -> service.start(WORKSPACE, request("second", 0))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> service.start(WORKSPACE, request("second", 1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("previous learning task");
        verify(tasks).create(any(CreateAgentTaskCommand.class));
        verify(turns).start(any(StartAgentTurnCommand.class));

        finishTurn(TurnState.FAILED, true);
        TaskId firstTask = TaskId.valueOf(first.build().orElseThrow().taskId());
        savedTasks.compute(firstTask, (id, task) -> task.fail("Stopped", NOW, DELETE_AFTER));
        assertThatThrownBy(() -> service.start(WORKSPACE, request("second", 1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cleaned up");
        ContextBuildService.View retained = service.get(WORKSPACE);
        assertThat(retained.canStart()).isFalse();
        assertThat(retained.build().orElseThrow().task().orElseThrow().worktreePath()).isEqualTo(savedTasks.get(firstTask).worktree().toString());
        verify(completions, never()).cleanupWorktree(any());
        assertThat(savedTasks.get(firstTask).worktreeRemovedAt()).isEmpty();
        completions.cleanupWorktree(firstTask);
        ContextBuildService.View second = service.start(WORKSPACE, request("second", 1));
        assertThat(second.buildRequestSequence()).isEqualTo(2);
        assertThat(second.build().orElseThrow().taskId()).isNotEqualTo(first.build().orElseThrow().taskId());
        assertThatThrownBy(() -> service.start(WORKSPACE, request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        verify(tasks, times(2)).create(any(CreateAgentTaskCommand.class));
        verify(turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void stopKeepsOwnershipUntilRuntimeSettlesAndPreservesNotesWithoutStartingAgain()
    {
        ContextBuild bound = start();
        AgentTurn turn = savedTurns.values().iterator().next();
        RepositoryNotebook initial = notebooks.getNotebook(WORKSPACE);
        ActiveContextPreparation active = new ActiveContextPreparation("attempt", "begin", "sha256:" + "b".repeat(64), bound.taskId(), turn.id().toString(),
                bound.turnKey(), Optional.empty(), Optional.empty(), 0, ImmutableList.of(fingerprint()));
        notebooks.savePreparation(WORKSPACE, initial, new RepositoryNotebook(1, 0, true, initial.sources(), initial.scopes(), initial.entries(),
                initial.suppressedKnowledgeIds(), initial.sourceEvidence(), Optional.of(active)));
        RepositoryNotebook edited = notebooks.editEntry(WORKSPACE, 0, new EditContextEntry("knowledge:project", "project", "Project overview", "User summary",
                "Keep this user correction", ImmutableList.of(), ImmutableList.of()));

        service.stop(WORKSPACE, bound.taskId());

        verify(turns).cancel(turn.id());
        assertThat(notebooks.getNotebook(WORKSPACE)).isEqualTo(edited);
        verifyNoInteractions(completions);
        assertThatThrownBy(() -> service.authorize(bound.taskId(), Optional.of(turn.id()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stopped");

        finishTurn(TurnState.CANCELLED, true);
        ContextBuildService.View settled = service.get(WORKSPACE);

        RepositoryNotebook saved = notebooks.getNotebook(WORKSPACE);
        assertThat(saved.entries()).isEqualTo(edited.entries());
        assertThat(saved.activePreparation()).isEmpty();
        assertThat(saved.scopes().getFirst().preparation().orElseThrow().outcome()).isEqualTo(FAILED);
        assertThat(settled.build().orElseThrow().task().orElseThrow().state()).isEqualTo("FAILED");
        assertThat(settled.canStart()).isTrue();
        verify(completions).abandon(any(TaskId.class), anyString());
        verify(completions, never()).complete(any());
        verify(completions).cleanupWorktree(TaskId.valueOf(bound.taskId()));
        verify(turns).start(any(StartAgentTurnCommand.class));
        verify(tasks).create(any(CreateAgentTaskCommand.class));
    }

    @Test
    void acceptedReportCompletesOnlyAfterTerminalRuntimeReleasesItsClaim()
    {
        ContextBuild bound = start();
        ContextRepositoryRead empty = new ContextRepositoryRead("context-read:empty", new ContextReference.RepositoryFile(7, REPOSITORY, "README.md", SHA), SHA,
                NOW, 1, 0, 0, 0, false, Optional.empty());
        ContextPreparationResult result = new ContextPreparationResult("attempt", fingerprint(), EMPTY, ImmutableList.of(), ImmutableList.of(empty), "No useful content");
        builds.save(Optional.of(bound), bound.withCapture(new ContextBuild.Capture(ImmutableList.of(empty), ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of(),
                Optional.of(new ContextBuild.Result(ImmutableList.of(result))), ImmutableList.of())));
        finishTurn(TurnState.COMPLETED, false);

        service.reconcile(WORKSPACE, bound.taskId());
        verifyNoInteractions(completions);
        releaseClaim(TaskId.valueOf(bound.taskId()));
        service.reconcile(WORKSPACE, bound.taskId());

        InOrder order = inOrder(completions, coordinator);
        order.verify(completions).complete(any(CompleteAgentTaskCommand.class));
        order.verify(completions).cleanupWorktree(TaskId.valueOf(bound.taskId()));
        order.verify(coordinator).unwatch(bound.taskId());
        assertThat(savedTasks.get(TaskId.valueOf(bound.taskId())).state()).isEqualTo(TaskState.COMPLETED);
        verify(completions, never()).abandon(any(), anyString());
        verify(turns).start(any(StartAgentTurnCommand.class));
    }

    private ContextBuild start()
    {
        service.start(WORKSPACE, request("learn", 0));
        return builds.find(WORKSPACE).orElseThrow();
    }

    private ContextBuildService.Request request(String key, long sequence)
    {
        return new ContextBuildService.Request(key, notebooks.getNotebook(WORKSPACE).revision(), sequence, ImmutableList.of("project"), ImmutableList.of());
    }

    private void finishTurn(TurnState state, boolean release)
    {
        AgentTurn turn = savedTurns.values().stream().filter(value -> !value.state().terminal()).findFirst().orElseThrow();
        savedTurns.put(turn.id(), turn.finish(state, AgentMetrics.empty(), state == TurnState.COMPLETED ? Optional.empty() : Optional.of("Stopped"), NOW));
        if (release) {
            releaseClaim(turn.taskId());
        }
    }

    private void releaseClaim(TaskId taskId)
    {
        savedTasks.compute(taskId, (id, task) -> task.releaseWorktreeMutation(task.worktreeMutationClaim().orElseThrow(), NOW));
    }

    private static ContextInputFingerprint fingerprint()
    {
        return ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", SHA), ImmutableList.of());
    }
}
