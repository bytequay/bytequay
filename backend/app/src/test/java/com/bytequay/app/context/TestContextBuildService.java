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
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
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
import com.bytequay.app.memory.MemoryHarvestService;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.memory.MemorySource;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final String DIRECTORY = "plugin/iceberg/";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETE_AFTER = NOW.plus(Duration.ofDays(7));
    private static final Path REPOSITORY_PATH = Path.of("/tmp/context-check-repository");
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");
    private static final RepoRef FORK = RepoRef.of("contributor", "repository");
    private static final AgentLaneId LANE = new AgentLaneId("codex");
    private static final AgentTurnSettings TURN_SETTINGS = new AgentTurnSettings(Optional.of("model"), Optional.of("medium"),
            AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of(), Optional.empty(), Optional.empty());

    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
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
    private final MemoryHarvestService harvests = mock(MemoryHarvestService.class);
    private final ContextSpecCheckStore checks = mock(ContextSpecCheckStore.class);
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final ContextBuildStore builds = new ContextBuildStore(settings, taskStore, mapper);
    private final Map<TaskId, AgentTask> savedTasks = new LinkedHashMap<>();
    private final Map<TurnId, AgentTurn> savedTurns = new LinkedHashMap<>();
    private ContextBuildService service;

    @BeforeEach
    void setup()
    {
        when(workspaces.requireReadyRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE, REPOSITORY_PATH, 1, FORK,
                OptionalLong.of(7), REPOSITORY, "upstream", "origin", "main", ImmutableList.of()));
        when(models.agentModel(AgentModelType.REVIEW, Optional.of(WORKSPACE)))
                .thenReturn(new AgentModelSetting(AgentModelType.REVIEW, true, LANE.value(), "Codex", "model", "Model", Optional.of("medium")));
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("token", new UserProfile("contributor", "Contributor", "", "", 0, 0, 0, "", "", "", "")));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        when(git.run(REPOSITORY_PATH, "rev-parse", "--verify", SHA + "^{commit}")).thenReturn(new GitCommandRunner.Result(0, SHA + "\n", false));
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
                    Path.of("/tmp/context-check-tasks").resolve(command.taskId().toString()), command.generatedBranch(), command.startCommit());
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
            TaskSummary summary = new TaskSummary("Checked repository", Optional.empty(), AgentMetrics.empty(), command.notes(), ImmutableList.of());
            savedTasks.compute(command.taskId(), (id, task) -> task.complete(summary, NOW, DELETE_AFTER));
            return new TaskCompletion(command.taskId(), summary, NOW, DELETE_AFTER);
        });
        doAnswer(call -> {
            savedTasks.compute(call.getArgument(0), (id, task) -> task.withWorktreeRemovedAt(NOW));
            return null;
        }).when(completions).cleanupWorktree(any(TaskId.class));
        when(worktrees.inspect(any(TaskWorktree.class))).thenAnswer(call -> new WorktreeSnapshot(call.getArgument(0), SHA, false, false, false));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("contextBuildCoordinator", coordinator);
        beans.registerSingleton("memoryHarvestService", harvests);
        service = new ContextBuildService(builds, workspaces, models, credentials, drivers, tasks, taskStore, turns, turnStore, completions, approvals, checks,
                worktrees, beans.getBeanProvider(ContextBuildCoordinator.class), beans.getBeanProvider(MemoryHarvestService.class), git, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void explicitlyLaunchesWithoutAReviewAndFreezesTheBindingBeforeTaskAndTurn()
    {
        assertThat(service.get(WORKSPACE).build()).isEmpty();
        verifyNoInteractions(tasks, turns, drivers, git, coordinator);

        ContextBuildService.View view = start("check", 0);

        ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
        assertThat(view.buildRequestSequence()).isEqualTo(1);
        assertThat(view.canStart()).isFalse();
        assertThat(view.build().orElseThrow().outcome()).isEqualTo(ContextBuildService.Outcome.RUNNING);
        assertThat(view.build().orElseThrow().checkId()).isEqualTo("check-1");
        assertThat(bound.repository()).isEqualTo(REPOSITORY);
        assertThat(bound.physicalRepository().owner()).isEqualTo(FORK.owner());
        assertThat(bound.repositoryId()).isEqualTo(7);
        assertThat(bound.baselineSha()).isEqualTo(SHA);
        AgentTask task = savedTasks.get(TaskId.valueOf(bound.taskId()));
        assertThat(task.interactive()).isFalse();
        assertThat(task.permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(task.title()).isEqualTo("Check owner/repository");
        ArgumentCaptor<StartAgentTurnCommand> start = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        InOrder order = inOrder(tasks, coordinator, turns);
        order.verify(tasks).create(any(CreateAgentTaskCommand.class));
        order.verify(coordinator).watch(bound);
        order.verify(turns).start(start.capture());
        assertThat(start.getValue().message()).contains("in directory " + DIRECTORY + " of owner/repository", "save candidate gaps",
                        "repository_context_read with kind SELECTION", "writes no files and runs no builds", "Never commit, push, comment on or file anything")
                .doesNotContain("Specs to check against");
    }

    @Test
    void aSpecCheckReproducesItsNextCandidateInTheSameTaskFromACleanWorktreeAndDiscardsItsTestsAtTheEnd()
    {
        Path checkout = Path.of("/tmp/context-check-worktree");
        AtomicReference<ContextSpecCheck> check = new AtomicReference<>(specCheck(ContextSpecCheck.Status.PENDING));
        when(checks.find(WORKSPACE, "check-1")).thenAnswer(call -> Optional.of(check.get()));
        when(worktrees.inspect(any(TaskWorktree.class))).thenAnswer(call -> new WorktreeSnapshot(call.getArgument(0), SHA, true, false, true));
        when(worktrees.ownedCheckout(any(TaskWorktree.class))).thenReturn(checkout);
        when(git.run(eq(checkout), any(String[].class))).thenReturn(new GitCommandRunner.Result(0, "", false));

        start("check", 0);
        ContextBuild planned = builds.find(WORKSPACE).orElseThrow();
        checked();
        finishTurn(TurnState.COMPLETED, true);
        service.reconcile(WORKSPACE, planned.taskId());

        assertThat(savedTasks.get(TaskId.valueOf(planned.taskId())).state()).isEqualTo(TaskState.OPEN);
        verify(git, never()).run(eq(checkout), any(String[].class));

        service.check(WORKSPACE, new ContextBuildService.Request("check:1", 1), SHA,
                new ContextBuild.Check("check-1", DIRECTORY, Optional.of(new ContextBuild.Reproduction("c1", "Gap")), "Candidate c1: Gap"));

        ContextBuild reproducing = builds.find(WORKSPACE).orElseThrow();
        assertThat(reproducing.taskId()).isEqualTo(planned.taskId());
        verify(tasks, times(1)).create(any(CreateAgentTaskCommand.class));
        verify(git).run(checkout, "reset", "--quiet", "--hard", SHA);
        verify(git).run(checkout, "clean", "--quiet", "--force", "-d", "--");
        ArgumentCaptor<StartAgentTurnCommand> starts = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns, times(2)).start(starts.capture());
        assertThat(starts.getAllValues().get(1).message()).contains("Reproduce one spec check candidate", "repository_context_read with kind SELECTION",
                        "repository_context_spec_result", "without cd, pipes, redirects or &&", "Never commit, push, comment on or file anything")
                .doesNotContain("Candidate c1: Gap", "readIds");

        check.set(specCheck(ContextSpecCheck.Status.CONFIRMED));
        checked();
        finishTurn(TurnState.COMPLETED, true);
        service.reconcile(WORKSPACE, reproducing.taskId());

        verify(git).run(checkout, "clean", "--quiet", "--force", "-d", "-x");
        assertThat(savedTasks.get(TaskId.valueOf(planned.taskId())).state()).isEqualTo(TaskState.COMPLETED);
        verify(completions).cleanupWorktree(TaskId.valueOf(planned.taskId()));
        ContextBuildService.View finished = service.get(WORKSPACE);
        assertThat(finished.build().orElseThrow().outcome()).isEqualTo(ContextBuildService.Outcome.SAVED);
        assertThat(finished.canStart()).isTrue();
    }

    @Test
    void stoppingASpecCheckEndsItAndKeepsWhatItConfirmed()
    {
        AtomicReference<ContextSpecCheck> check = new AtomicReference<>(specCheck(ContextSpecCheck.Status.PENDING));
        when(checks.find(WORKSPACE, "check-1")).thenAnswer(call -> Optional.of(check.get()));
        when(checks.save(eq(WORKSPACE), any(), any())).thenAnswer(call -> {
            check.set(call.getArgument(2));
            return check.get();
        });
        start("check", 0);
        ContextBuild planned = builds.find(WORKSPACE).orElseThrow();
        AgentTurn turn = savedTurns.values().iterator().next();

        service.stop(WORKSPACE, planned.taskId());

        assertThat(check.get().state()).isEqualTo(ContextSpecCheck.State.STOPPED);
        assertThat(check.get().candidate("c1").status()).isEqualTo(ContextSpecCheck.Status.NOT_REPRODUCED);
        verify(turns).cancel(turn.id());
        verifyNoInteractions(completions);
        assertThatThrownBy(() -> service.authorize(planned.taskId(), Optional.of(turn.id()))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stopped");

        finishTurn(TurnState.CANCELLED, true);
        ContextBuildService.View settled = service.get(WORKSPACE);

        assertThat(settled.build().orElseThrow().outcome()).isEqualTo(ContextBuildService.Outcome.STOPPED);
        assertThat(settled.build().orElseThrow().task().orElseThrow().state()).isEqualTo("FAILED");
        assertThat(settled.canStart()).isTrue();
        verify(completions).abandon(any(TaskId.class), anyString());
        verify(completions, never()).complete(any());
        verify(completions).cleanupWorktree(TaskId.valueOf(planned.taskId()));
        verify(turns).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void unavailableProviderFailsBeforeAllocatingBindingTaskOrWorktree()
    {
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(), "Sign in", AgentCapabilities.none()));

        assertThatThrownBy(() -> start("check", 0)).isInstanceOf(AgentProviderUnavailableException.class);

        assertThat(builds.find(WORKSPACE)).isEmpty();
        verifyNoInteractions(tasks, turns, git, credentials, coordinator);
    }

    @Test
    void aHarvestRunsOneTurnAndSettlesItsHarvestWhenTheTurnEnds()
    {
        ContextBuild.Harvest harvest = new ContextBuild.Harvest("harvest-1", "Learn from my PRs", true,
                ImmutableList.of(new ContextBuild.HarvestPull(31201, "Add a property", "https://github.com/owner/repository/pull/31201", true, NOW)),
                ImmutableList.of(new ContextBuild.HarvestThread("thread-1", 31201, MemorySource.Part.REVIEW_COMMENT, Optional.empty(), true, false,
                        ImmutableList.of(new ContextBuild.HarvestNote("c1", "note-1", "bo", true, "Please document it.", "https://github.com/owner/repository/pull/31201", NOW)))));
        ContextBuildService.View view = service.harvest(WORKSPACE, new ContextBuildService.Request("learn", 0), SHA, harvest);

        assertThat(view.build().orElseThrow().kind()).isEqualTo("HARVEST");
        assertThat(view.build().orElseThrow().checkId()).isEqualTo("harvest-1");
        ArgumentCaptor<CreateAgentTaskCommand> created = ArgumentCaptor.forClass(CreateAgentTaskCommand.class);
        verify(tasks).create(created.capture());
        assertThat(created.getValue().title()).isEqualTo("Learn from owner/repository");
        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(started.capture());
        assertThat(started.getValue().message()).startsWith("Learn repository memory for owner/repository").contains("repository_context_harvest_save");

        finishTurn(TurnState.COMPLETED, true);
        ContextBuildService.View settled = service.get(WORKSPACE);
        assertThat(settled.build().orElseThrow().outcome()).isEqualTo(ContextBuildService.Outcome.SAVED);
        verify(completions).complete(any(CompleteAgentTaskCommand.class));
        verify(harvests).finish(builds.find(WORKSPACE).orElseThrow(), true);
        assertThat(settled.canStart()).isTrue();
    }

    @Test
    void duplicateRequestsReplayAndStaleOrChangedRequestsCannotReplaceAnOpenTask()
    {
        ContextBuildService.View first = start("first", 0);
        assertThat(start("first", 0)).isEqualTo(first);
        assertThatThrownBy(() -> service.check(WORKSPACE, new ContextBuildService.Request("first", 0), SHA,
                new ContextBuild.Check("check-1", DIRECTORY, Optional.empty(), "Another brief")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different run inputs");
        assertThatThrownBy(() -> start("second", 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sequence");
        assertThatThrownBy(() -> service.check(WORKSPACE, new ContextBuildService.Request("second", 1), SHA,
                new ContextBuild.Check("check-2", DIRECTORY, Optional.empty(), "Specs to check against")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("last spec check or harvest must finish");
        verify(tasks).create(any(CreateAgentTaskCommand.class));
        verify(turns).start(any(StartAgentTurnCommand.class));

        finishTurn(TurnState.FAILED, true);
        TaskId firstTask = TaskId.valueOf(first.build().orElseThrow().taskId());
        savedTasks.compute(firstTask, (id, task) -> task.fail("Stopped", NOW, DELETE_AFTER));
        assertThatThrownBy(() -> start("second", 1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cleaned up");
        ContextBuildService.View retained = service.get(WORKSPACE);
        assertThat(retained.canStart()).isFalse();
        assertThat(retained.build().orElseThrow().outcome()).isEqualTo(ContextBuildService.Outcome.FAILED);
        verify(completions, never()).cleanupWorktree(any());
        completions.cleanupWorktree(firstTask);
        ContextBuildService.View second = start("second", 1);
        assertThat(second.buildRequestSequence()).isEqualTo(2);
        assertThat(second.build().orElseThrow().taskId()).isNotEqualTo(first.build().orElseThrow().taskId());
        verify(tasks, times(2)).create(any(CreateAgentTaskCommand.class));
        verify(turns, times(2)).start(any(StartAgentTurnCommand.class));
    }

    @Test
    void aSavedTurnCompletesOnlyAfterTerminalRuntimeReleasesItsClaim()
    {
        start("check", 0);
        ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
        checked();
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
    }

    @Test
    void aTurnThatSavedNothingFailsItsTaskAndRecordsWhy()
    {
        start("check", 0);
        ContextBuild bound = builds.find(WORKSPACE).orElseThrow();
        finishTurn(TurnState.COMPLETED, true);

        service.reconcile(WORKSPACE, bound.taskId());

        verify(completions).abandon(eq(TaskId.valueOf(bound.taskId())), eq("The check turn ended without saving its result. Turn outcome: COMPLETED"));
        assertThat(builds.find(WORKSPACE).orElseThrow().capture().gaps()).containsExactly("The check turn ended without saving its result. Turn outcome: COMPLETED");
        ContextBuildService.View view = service.get(WORKSPACE);
        assertThat(view.build().orElseThrow().outcome()).isEqualTo(ContextBuildService.Outcome.FAILED);
        assertThat(view.build().orElseThrow().detail()).contains("The check turn ended without saving its result. Turn outcome: COMPLETED");
    }

    private ContextBuildService.View start(String key, long sequence)
    {
        return service.check(WORKSPACE, new ContextBuildService.Request(key, sequence), SHA,
                new ContextBuild.Check("check-1", DIRECTORY, Optional.empty(), "Specs to check against"));
    }

    /** What the check tools record once a turn saves its candidates or its result. */
    private void checked()
    {
        ContextBuild current = builds.find(WORKSPACE).orElseThrow();
        builds.save(Optional.of(current), current.withCapture(current.capture().withAttempts(ImmutableMap.of(ContextBuildService.CHECKED, "saved"))));
    }

    private static ContextSpecCheck specCheck(ContextSpecCheck.Status status)
    {
        ContextSpecCheck.Predicted predicted = ContextSpecCheck.Predicted.DIVERGES;
        ContextSpecCheck.Candidate candidate = new ContextSpecCheck.Candidate("c1", predicted.type(), predicted, "Gap", "MUST x", "https://example.com/spec#x",
                ImmutableList.of("plugin/iceberg/README.md:1-2"), "why", 2, Optional.empty(), status, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        return new ContextSpecCheck("check-1", DIRECTORY, SHA, "check", ImmutableList.of("https://example.com/spec"), ImmutableList.of("https://example.com/spec"),
                ImmutableList.of(), ContextSpecCheck.State.REPRODUCING, ImmutableList.of(candidate), ImmutableList.of(), ImmutableList.of(), 1, 8, Optional.empty(), NOW,
                Optional.empty());
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
}
