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
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.CodeGraphService;
import com.bytequay.agent.core.CompleteAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.impl.DefaultAgentCompletionService;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.core.store.TaskLogStore;
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
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.core.context.ContextPreparationResult.Outcome.EMPTY;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
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
    private final AgentTaskStore taskStore = mock(AgentTaskStore.class, CALLS_REAL_METHODS);
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
    private final RepositoryContextService notebooks = new RepositoryContextService(settings, mapper, workspaceStore, reviews, Clock.systemUTC());
    private final ContextLearningPlanStore plans = mock(ContextLearningPlanStore.class);
    private final ContextDiscoveryReportStore reports = new ContextDiscoveryReportStore(settings, mapper);
    private final ContextBuildStore builds = new ContextBuildStore(settings, taskStore, mapper);
    private final RepositoryContextPreparation preparation = new RepositoryContextPreparation(notebooks, mock(ContextInputReader.class), workspaces,
            mock(ContextSourceChecker.class));
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
            TaskSummary summary = new TaskSummary("Learned repository", Optional.empty(), AgentMetrics.empty(), command.notes(), ImmutableList.of());
            savedTasks.compute(command.taskId(), (id, task) -> task.complete(summary, NOW, DELETE_AFTER));
            return new TaskCompletion(command.taskId(), summary, NOW, DELETE_AFTER);
        });
        doAnswer(call -> {
            savedTasks.compute(call.getArgument(0), (id, task) -> task.withWorktreeRemovedAt(NOW));
            return null;
        }).when(completions).cleanupWorktree(any(TaskId.class));
        service = createService(completions);
    }

    @Test
    void explicitlyLaunchesWithoutAReviewAndFreezesBindingBeforeTaskAndTurnEvenWhenReviewsAreOff()
    {
        notebooks.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.of(false), Optional.empty(), Optional.empty()));
        notebooks.configure(WORKSPACE, 1, new EditContextConfiguration(Optional.empty(), Optional.empty(), Optional.of(ImmutableList.of(new EditContextConfiguration.Source(
                "docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE, "https://example.com/docs")))));
        assertThat(service.get(WORKSPACE).build()).isEmpty();
        verifyNoInteractions(tasks, turns, drivers, git, reviews, coordinator);
        ContextBuildService.Request request = new ContextBuildService.Request("learn", 2, 0, ImmutableList.of("project"), ImmutableList.of());
        RegisteredContextSource selectedDoc = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs", ContextEntryOrigin.USER, Optional.empty());
        ContextBuild.Assignment assignment = new ContextBuild.Assignment("plan", "topic:1", "Project", 0, "Project overview", "",
                ImmutableList.of(), ImmutableList.of("README.md"));
        ContextLearningPlan accepted = acceptedPlan(ImmutableList.of(selectedDoc), ImmutableList.of("docs"), assignment);
        when(plans.find(WORKSPACE)).thenReturn(Optional.of(accepted));
        when(git.run(REPOSITORY_PATH, "rev-parse", "--verify", SHA + "^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));

        ContextBuildService.View view = service.learn(WORKSPACE, request, SHA, ImmutableList.of(selectedDoc), assignment);

        ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
        assertThat(view.buildRequestSequence()).isEqualTo(1);
        assertThat(view.canStart()).isFalse();
        assertThat(bound.repository()).isEqualTo(REPOSITORY);
        assertThat(bound.physicalRepository().owner()).isEqualTo(FORK.owner());
        assertThat(bound.repositoryId()).isEqualTo(7);
        assertThat(bound.baselineSha()).isEqualTo(SHA);
        assertThat(bound.assignment()).contains(assignment);
        assertThat(bound.documentationSources()).containsExactly(new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs", ContextEntryOrigin.USER, Optional.empty()));
        AgentTask task = savedTasks.get(TaskId.valueOf(bound.taskId()));
        assertThat(task.interactive()).isFalse();
        assertThat(task.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(notebooks.getNotebook(WORKSPACE).useInReviews()).isFalse();
        ArgumentCaptor<StartAgentTurnCommand> start = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        InOrder order = inOrder(tasks, coordinator, turns);
        order.verify(tasks).create(any(CreateAgentTaskCommand.class));
        order.verify(coordinator).watch(bound);
        order.verify(turns).start(start.capture());
        assertThat(start.getValue().message()).contains("Project overview", "the approved paths, the documentation inventory", "check the lead first");
        verifyNoInteractions(reviews);
    }

    @Test
    void investigationLearnsOneNoteScopeAtTheMonitoredHeadWithItsDispute()
    {
        ContextBuild.Investigation investigation = new ContextBuild.Investigation("knowledge:project", 3, "project", "Snapshots", "Reads reuse the snapshot",
                "Current text (v3):\nReads reuse the snapshot\n\nDisputed since v3. Each side:\n- WRONG on v3 by a review: \"Reads reuse the snapshot\"");
        assertThatThrownBy(() -> service.investigate(WORKSPACE, new ContextBuildService.Request("investigate", 0, 0, ImmutableList.of("other"), ImmutableList.of()),
                investigation)).hasMessageContaining("its note's scope");

        service.investigate(WORKSPACE, new ContextBuildService.Request("investigate", 0, 0, ImmutableList.of("project"), ImmutableList.of()), investigation);

        ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
        assertThat(bound.mode()).isEqualTo(ContextBuild.Mode.LEARN);
        assertThat(bound.investigation()).contains(investigation);
        assertThat(bound.assignment()).isEmpty();
        assertThat(bound.baselineSha()).isEqualTo(SHA);
        assertThat(bound.documentationSources()).isEmpty();
        ArgumentCaptor<CreateAgentTaskCommand> created = ArgumentCaptor.forClass(CreateAgentTaskCommand.class);
        verify(tasks).create(created.capture());
        assertThat(created.getValue().title()).isEqualTo("Investigate owner/repository");
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(started.capture());
        assertThat(started.getValue().message()).contains("\"Reads reuse the snapshot\"", "WRONG on v3 by a review", "repository_context_settle",
                "KEEP_PREVIOUS", "root cause of at most 300 bytes");
    }

    @Test
    void aBatchAsksATopicsNextQuestionAsANewTurnOfTheSameTaskAndFinishesItAfterTheLast()
    {
        AtomicReference<ContextLearningPlan> plan = batchedPlan();
        ContextBuild asked = builds.find(WORKSPACE).orElseThrow();
        answer();
        finishTurn(TurnState.COMPLETED, true);

        service.reconcile(WORKSPACE, asked.taskId());
        assertThat(savedTasks.get(TaskId.valueOf(asked.taskId())).state()).isEqualTo(TaskState.OPEN);
        verify(completions, never()).cleanupWorktree(any());
        verify(coordinator, never()).unwatch(anyString());

        plan.set(plan.get().withBatch(Optional.of(new ContextLearningPlan.Batch("learn", ImmutableList.of(),
                ImmutableList.of(new ContextLearningPlan.Target("topic:1", 0), new ContextLearningPlan.Target("topic:1", 1)), 2, false))));
        service.learn(WORKSPACE, new ContextBuildService.Request("learn#2", notebooks.getNotebook(WORKSPACE).revision(), 1, ImmutableList.of("project"),
                ImmutableList.of()), SHA, ImmutableList.of(), new ContextBuild.Assignment("plan", "topic:1", "Project", 1, "Where are the tests?", "",
                ImmutableList.of(), ImmutableList.of("README.md")));

        ContextBuild next = builds.find(WORKSPACE).orElseThrow();
        assertThat(next.taskId()).isEqualTo(asked.taskId());
        assertThat(next.branch()).isEqualTo(asked.branch());
        assertThat(next.turnKey()).isNotEqualTo(asked.turnKey());
        verify(tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        ArgumentCaptor<StartAgentTurnCommand> starts = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns, times(2)).start(starts.capture());
        assertThat(starts.getAllValues().get(1).message()).contains("this turn asks a new one", "Where are the tests?");
        assertThat(service.get(WORKSPACE).runs()).extracting(ContextRun::sequence, ContextRun::taskId, ContextRun::outcome)
                .containsExactly(tuple(1L, asked.taskId(), ContextRun.Outcome.NO_NOTE), tuple(2L, asked.taskId(), ContextRun.Outcome.RUNNING));

        answer();
        finishTurn(TurnState.COMPLETED, true);
        service.reconcile(WORKSPACE, next.taskId());
        assertThat(savedTasks.get(TaskId.valueOf(asked.taskId())).state()).isEqualTo(TaskState.COMPLETED);
        verify(completions).cleanupWorktree(TaskId.valueOf(asked.taskId()));
        verify(coordinator).unwatch(asked.taskId());
    }

    @Test
    void stopBetweenTwoQuestionsOfATaskEndsTheBatchAndCleansTheTaskUp()
    {
        AtomicReference<ContextLearningPlan> plan = batchedPlan();
        ContextBuild asked = builds.find(WORKSPACE).orElseThrow();
        answer();
        finishTurn(TurnState.COMPLETED, true);
        service.reconcile(WORKSPACE, asked.taskId());
        assertThat(savedTasks.get(TaskId.valueOf(asked.taskId())).state()).isEqualTo(TaskState.OPEN);

        ContextBuildService.View stopped = service.stop(WORKSPACE, asked.taskId());

        assertThat(plan.get().batch()).isEmpty();
        assertThat(stopped.canStart()).isTrue();
        verify(completions).cleanupWorktree(TaskId.valueOf(asked.taskId()));
        verify(turns, times(1)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void rejectsSourceExpansionThatWinsBeforeTaskAdmission()
    {
        notebooks.configure(WORKSPACE, 0, new EditContextConfiguration(Optional.empty(), Optional.empty(), Optional.of(ImmutableList.of(
                new EditContextConfiguration.Source("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://example.com/docs/"),
                new EditContextConfiguration.Source("extra", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://other.example.org/spec/")))));
        List<RegisteredContextSource> sources = notebooks.getNotebook(WORKSPACE).sources();
        ContextBuild.Assignment assignment = new ContextBuild.Assignment("plan", "topic:1", "Project", 0, "Project overview", "",
                ImmutableList.of(), ImmutableList.of("README.md"));
        ContextLearningPlan original = acceptedPlan(ImmutableList.of(sources.getFirst()), ImmutableList.of("docs"), assignment);
        ContextLearningPlan.Topic changed = original.topics().getFirst().withSource("extra");
        ContextLearningPlan expanded = original.withSources(ImmutableList.of(changed), ContextDocumentInventory.initial(sources));
        AtomicReference<ContextLearningPlan> plan = new AtomicReference<>(original);
        when(plans.find(WORKSPACE)).thenAnswer(ignored -> Optional.of(plan.get()));
        when(git.run(REPOSITORY_PATH, "rev-parse", "--verify", SHA + "^{commit}"))
                .thenAnswer(ignored -> {
                    plan.set(expanded);
                    return new GitCommandRunner.Result(0, SHA + "\n", false);
                });
        ContextBuildService.Request request = new ContextBuildService.Request("learn", 1, 0, ImmutableList.of("project"), ImmutableList.of());

        assertThatThrownBy(() -> service.learn(WORKSPACE, request, SHA, ImmutableList.of(sources.getFirst()), assignment))
                .hasMessageContaining("topic or sources changed");
        assertThat(builds.find(WORKSPACE)).isEmpty();
        verify(tasks, never()).create(any());
        verify(turns, never()).start(any());
    }

    @Test
    void discoveryNeedsNoScopesAndReadOnlyStatusNeverLaunchesIt()
    {
        assertThat(service.get(WORKSPACE).build()).isEmpty();
        verifyNoInteractions(tasks, turns, drivers, git);
        ContextBuildService.Request request = new ContextBuildService.Request("discover", 0, 0, ImmutableList.of(), ImmutableList.of());

        ContextBuildService.View first = service.discover(WORKSPACE, request, false);
        assertThat(builds.find(WORKSPACE).orElseThrow().mode()).isEqualTo(ContextBuild.Mode.DISCOVER);
        ContextBuild accepted = builds.find(WORKSPACE).orElseThrow();
        assertThat(accepted.scopeIds()).isEmpty();
        assertThat(accepted.documentationSources()).isEmpty();
        assertThat(service.discover(WORKSPACE, request, false)).isEqualTo(first);
        assertThat(service.get(WORKSPACE)).isEqualTo(first);
        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, request)).hasMessageContaining("Select scopes");
        verify(tasks).create(any(CreateAgentTaskCommand.class));
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(started.capture());
        assertThat(started.getValue().message()).contains("deeply nested", "partial report", "later user approval",
                "separate focused topics", "supplement component topics", "missing questions in remaining",
                "All questions in a topic must share notePaths", "split questions into separate topics when their applicability differs");
    }

    @Test
    void discoveryContinuationRevisitsMissingComponentQuestionsInSurveyedAreas()
    {
        ContextDiscoveryReport report = new ContextDiscoveryReport("11111111-1111-4111-8111-111111111111", WORKSPACE, 7, REPOSITORY, SHA,
                "Shared storage survey", ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of("plugin/other/ still needs its own write-contract questions"), true, ContextDiscoveryReport.Coverage.none(), NOW);
        reports.save(Optional.empty(), report);
        when(git.run(REPOSITORY_PATH, "rev-parse", "--verify", SHA + "^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));

        service.discover(WORKSPACE, new ContextBuildService.Request("continue-discovery", 0, 0, ImmutableList.of(), ImmutableList.of()), true);

        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(started.capture());
        assertThat(started.getValue().message()).contains("remaining questions", "missing component questions from earlier broad topics",
                "even when their directories were surveyed", "missing questions in remaining", "Earlier topics, sources and evidence are kept");
        assertThat(reports.find(WORKSPACE)).contains(report);
    }

    @Test
    void unavailableProviderFailsBeforeAllocatingBindingTaskOrWorktree()
    {
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(), "Sign in", AgentCapabilities.none()));

        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, request("learn", 0))).isInstanceOf(AgentProviderUnavailableException.class);

        assertThat(builds.find(WORKSPACE)).isEmpty();
        verifyNoInteractions(tasks, turns, git, credentials, coordinator);
    }

    @Test
    void duplicateRequestsReplayAndStaleOrChangedRequestsCannotReplaceAnOpenTask()
    {
        ContextBuildService.Request request = request("first", 0);
        ContextBuildService.View first = service.learnHistory(WORKSPACE, request);
        assertThat(service.learnHistory(WORKSPACE, request)).isEqualTo(first);
        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, new ContextBuildService.Request("first", 0, 0, ImmutableList.of("project"),
                ImmutableList.of(new ContextBuild.History(ContextHistoryReader.SourceKind.ISSUE, 3)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different learning inputs");
        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, request("second", 0))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, request("second", 1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("previous learning task");
        verify(tasks).create(any(CreateAgentTaskCommand.class));
        verify(turns).start(any(StartAgentTurnCommand.class));

        finishTurn(TurnState.FAILED, true);
        TaskId firstTask = TaskId.valueOf(first.build().orElseThrow().taskId());
        savedTasks.compute(firstTask, (id, task) -> task.fail("Stopped", NOW, DELETE_AFTER));
        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, request("second", 1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cleaned up");
        ContextBuildService.View retained = service.get(WORKSPACE);
        assertThat(retained.canStart()).isFalse();
        assertThat(retained.build().orElseThrow().task().orElseThrow().worktreeRemovedAt()).isEmpty();
        verify(completions, never()).cleanupWorktree(any());
        assertThat(savedTasks.get(firstTask).worktreeRemovedAt()).isEmpty();
        completions.cleanupWorktree(firstTask);
        ContextBuildService.View second = service.learnHistory(WORKSPACE, request("second", 1));
        assertThat(second.buildRequestSequence()).isEqualTo(2);
        assertThat(second.build().orElseThrow().taskId()).isNotEqualTo(first.build().orElseThrow().taskId());
        assertThatThrownBy(() -> service.learnHistory(WORKSPACE, request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
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
        notebooks.savePreparation(WORKSPACE, initial,
                new RepositoryNotebook(1, 0, true, initial.sources(), initial.scopes(), initial.entries(), initial.sourceEvidence(), Optional.of(active)));
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
                Optional.of(new ContextBuild.Result(ImmutableList.of(result))), ImmutableList.of(), ImmutableList.of())));
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

    @Test
    void completedLearningPreservesDirtyWorkAndIgnoredOutputUntilExplicitlyCleared(@TempDir Path directory)
            throws IOException
    {
        GitCommandRunner localGit = new GitCommandRunner();
        WorkspacePaths paths = new WorkspacePaths(directory.resolve("managed").toString());
        Path repository = paths.repository(WORKSPACE);
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE).resolve(".bytequay-workspace"), WORKSPACE + "\n1\n");
        runGit(localGit, repository, "init", "--initial-branch=main");
        runGit(localGit, repository, "remote", "add", "origin", "https://github.com/contributor/repository.git");
        Files.writeString(repository.resolve("README.md"), "Original repository text\n");
        Files.writeString(repository.resolve(".gitignore"), "build/\n");
        runGit(localGit, repository, "add", ".");
        runGit(localGit, repository, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-qm", "Fixture");
        String baseline = runGit(localGit, repository, "rev-parse", "HEAD").strip();
        when(workspaceStore.findById(WORKSPACE)).thenReturn(Optional.of(new Workspace(WORKSPACE, "Repository", 1, FORK,
                Optional.of(7L), Optional.of(REPOSITORY), 7, REPOSITORY, Workspace.Topology.FORK, "main", "https://github.com/contributor/repository.git",
                Optional.of("main"), Optional.of("https://github.com/owner/repository.git"), repository.toString(), Optional.empty(), Optional.empty(), 0, NOW, NOW)));
        when(workspaces.requireReadableRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE, repository, 1, FORK,
                OptionalLong.of(7), REPOSITORY, "upstream", "origin", "main", ImmutableList.of()));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/upstream/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, baseline + "\n", false));
        ManagedWorktreeService managed = new ManagedWorktreeService(paths, workspaceStore, localGit);
        doAnswer(call -> {
            CreateAgentTaskCommand command = call.getArgument(0);
            TaskWorktree tree = managed.provision(new ProvisionWorktreeCommand(command.taskId(), WORKSPACE, command.physicalRepository(),
                    command.startCommit(), command.generatedBranch()));
            AgentTask task = AgentTask.created(command.taskId(), false, command.title(), TitleSource.USER, command.laneId(), tree, command.defaults(), NOW);
            savedTasks.put(task.id(), task);
            return task;
        }).when(tasks).create(any(CreateAgentTaskCommand.class));
        when(taskStore.update(any(AgentTask.class))).thenAnswer(call -> {
            AgentTask task = call.getArgument(0);
            savedTasks.put(task.id(), task);
            return task;
        });
        AgentUnitOfWork unit = mock(AgentUnitOfWork.class);
        when(unit.execute(any())).thenAnswer(call -> ((AgentUnitOfWork.Work<?>) call.getArgument(0)).run());
        AgentMetricsService metrics = mock(AgentMetricsService.class);
        when(metrics.taskTotals(any())).thenReturn(AgentMetrics.empty());
        AgentResourceService resources = mock(AgentResourceService.class);
        Path runtime = directory.resolve("runtime");
        when(resources.require(any())).thenReturn(new TaskResources(runtime, runtime.resolve("events"), runtime.resolve("blobs"), runtime.resolve("provider")));
        service = createService(new DefaultAgentCompletionService(taskStore, turnStore, mock(QueuedInputStore.class), unit,
                mock(AgentEventOutboxStore.class), mock(AgentEventService.class), mock(TaskLogStore.class), managed, resources,
                new AgentRuntimeRegistry(ignored -> {}), metrics, mock(CodeGraphService.class), () -> Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC)));
        RepositoryNotebook saved = notebooks.editEntry(WORKSPACE, 0, new EditContextEntry("knowledge:project", "project", "Project", "Summary",
                "Keep the accepted knowledge", ImmutableList.of(), ImmutableList.of()));
        ContextBuild bound = start();
        TaskId taskId = TaskId.valueOf(bound.taskId());
        ContextRepositoryRead empty = new ContextRepositoryRead("context-read:empty", new ContextReference.RepositoryFile(7, REPOSITORY, "README.md", baseline),
                baseline, NOW, 1, 0, 0, 0, false, Optional.empty());
        ContextPreparationResult result = new ContextPreparationResult("attempt", fingerprint(), EMPTY, ImmutableList.of(), ImmutableList.of(empty), "No new knowledge");
        builds.save(Optional.of(bound), bound.withCapture(new ContextBuild.Capture(ImmutableList.of(empty), ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of(),
                Optional.of(new ContextBuild.Result(ImmutableList.of(result))), ImmutableList.of(), ImmutableList.of())));
        finishTurn(TurnState.COMPLETED, true);
        Path tree = savedTasks.get(taskId).worktree();
        Files.writeString(tree.resolve("README.md"), "Unfinished experiment\n");
        Files.writeString(tree.resolve("scratch.txt"), "Untracked work\n");
        Files.createDirectories(tree.resolve("build"));
        Files.writeString(tree.resolve("build/result.txt"), "Ignored experiment result\n");

        ContextBuildService.View dirty = service.get(WORKSPACE);

        assertThat(dirty.canStart()).isFalse();
        assertThat(savedTasks.get(taskId).state()).isEqualTo(TaskState.OPEN);
        assertThat(savedTasks.get(taskId).worktreeMutationClaim()).isEmpty();
        assertThat(builds.find(WORKSPACE).orElseThrow().capture().gaps()).anyMatch(gap -> gap.contains("cleanup needs attention"));
        assertThat(Files.readString(tree.resolve("README.md"))).isEqualTo("Unfinished experiment\n");
        assertThat(tree.resolve("scratch.txt")).exists();
        assertThat(notebooks.getNotebook(WORKSPACE)).isEqualTo(saved);

        Files.writeString(tree.resolve("README.md"), "Original repository text\n");
        Files.delete(tree.resolve("scratch.txt"));
        ContextBuildService.View ignored = service.get(WORKSPACE);
        assertThat(ignored.canStart()).isFalse();
        assertThat(savedTasks.get(taskId).state()).isEqualTo(TaskState.COMPLETED);
        assertThat(savedTasks.get(taskId).worktreeRemovedAt()).isEmpty();
        assertThat(tree.resolve("build/result.txt")).exists();
        assertThat(notebooks.getNotebook(WORKSPACE)).isEqualTo(saved);

        Files.delete(tree.resolve("build/result.txt"));
        Files.delete(tree.resolve("build"));
        assertThat(service.get(WORKSPACE).canStart()).isTrue();
        assertThat(savedTasks.get(taskId).worktreeRemovedAt()).contains(NOW);
        assertThat(tree).doesNotExist();
        assertThat(notebooks.getNotebook(WORKSPACE)).isEqualTo(saved);
        verify(tasks).create(any(CreateAgentTaskCommand.class));
        verify(turns).start(any(StartAgentTurnCommand.class));
    }

    private ContextLearningPlan acceptedPlan(List<RegisteredContextSource> selected, List<String> sourceIds,
            ContextBuild.Assignment assignment)
    {
        ContextLearningPlan.Topic topic = new ContextLearningPlan.Topic(assignment.topicId(), assignment.topicTitle(),
                "project", "sha256:" + "a".repeat(64), assignment.paths(), Optional.of(ImmutableList.of()), assignment.repositoryFiles(), sourceIds,
                ImmutableList.of(ContextLearningPlan.Question.open(assignment.question())));
        return new ContextLearningPlan(assignment.planId(), WORKSPACE, "report", "accept", "sha256:" + "b".repeat(64),
                notebooks.getNotebook(WORKSPACE).revision(), 0, ImmutableList.of(topic.id()), 7, REPOSITORY, SHA,
                ImmutableList.of(topic), selected, ContextDocumentInventory.initial(selected), ImmutableList.of(), NOW, Optional.empty());
    }

    /** Starts the first of a topic's two questions in a batch that asks the second next. */
    private AtomicReference<ContextLearningPlan> batchedPlan()
    {
        ContextBuild.Assignment first = new ContextBuild.Assignment("plan", "topic:1", "Project", 0, "Project overview", "",
                ImmutableList.of(), ImmutableList.of("README.md"));
        ContextLearningPlan accepted = acceptedPlan(ImmutableList.of(), ImmutableList.of(), first);
        ContextLearningPlan.Topic topic = accepted.topics().getFirst().withQuestions(ImmutableList.of(ContextLearningPlan.Question.open("Project overview"),
                ContextLearningPlan.Question.open("Where are the tests?")));
        AtomicReference<ContextLearningPlan> plan = new AtomicReference<>(accepted.withProgress(ImmutableList.of(topic), ImmutableList.of())
                .withBatch(Optional.of(new ContextLearningPlan.Batch("learn", ImmutableList.of(new ContextLearningPlan.Target("topic:1", 1)),
                        ImmutableList.of(new ContextLearningPlan.Target("topic:1", 0)), 2, false))));
        when(plans.find(WORKSPACE)).thenAnswer(ignored -> Optional.of(plan.get()));
        when(plans.save(any(), any(ContextLearningPlan.class))).thenAnswer(call -> {
            plan.set(call.getArgument(1));
            return plan.get();
        });
        when(git.run(REPOSITORY_PATH, "rev-parse", "--verify", SHA + "^{commit}")).thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));
        service.learn(WORKSPACE, new ContextBuildService.Request("learn", notebooks.getNotebook(WORKSPACE).revision(), 0, ImmutableList.of("project"),
                ImmutableList.of()), SHA, ImmutableList.of(), first);
        return plan;
    }

    /** Records the current question as answered, as the progress tool does. */
    private void answer()
    {
        ContextBuild current = builds.find(WORKSPACE).orElseThrow();
        builds.save(Optional.of(current), current.withCapture(new ContextBuild.Capture(ImmutableList.of(), ImmutableMap.of(),
                ImmutableMap.of(ContextRun.PROGRESS, ContextRun.COMPLETED), ImmutableMap.of(), Optional.of(new ContextBuild.Result(ImmutableList.of())),
                ImmutableList.of(), ImmutableList.of())));
    }

    private ContextBuildService createService(AgentCompletionService completion)
    {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("contextBuildCoordinator", coordinator);
        return new ContextBuildService(builds, notebooks, plans, reports, preparation, workspaces, models, credentials, drivers, tasks, taskStore, turns, turnStore,
                completion, approvals, beans.getBeanProvider(ContextBuildCoordinator.class), git, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String runGit(GitCommandRunner git, Path repository, String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("%s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }

    private ContextBuild start()
    {
        service.learnHistory(WORKSPACE, request("learn", 0));
        return builds.find(WORKSPACE).orElseThrow();
    }

    private ContextBuildService.Request request(String key, long sequence)
    {
        return new ContextBuildService.Request(key, notebooks.getNotebook(WORKSPACE).revision(), sequence, ImmutableList.of("project"),
                ImmutableList.of(new ContextBuild.History(ContextHistoryReader.SourceKind.PR, 7)));
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
