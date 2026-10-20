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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestInteractiveAgentTaskCreator
{
    private static final String START_COMMIT = "0123456789012345678901234567890123456789";
    private static final TaskId TASK_ID = TaskId.valueOf("11111111-1111-1111-1111-111111111111");

    @TempDir
    private Path temporaryDirectory;

    private AgentTaskService tasks;
    private AgentTurnService turns;
    private AgentDriver driver;
    private AgentDriverRegistry drivers;
    private ModelSettingsService models;
    private AppSettingsService settings;
    private WorkspaceRepositoryService workspaces;
    private WorkspaceProvisioningService provisioning;
    private GitCommandRunner git;
    private AgentTaskStartupProgress progress;
    private InteractiveAgentTaskCreator creator;
    private PullRequestStore pullRequests;

    @BeforeEach
    void setUp()
    {
        tasks = mock(AgentTaskService.class);
        turns = mock(AgentTurnService.class);
        driver = mock(AgentDriver.class);
        drivers = mock(AgentDriverRegistry.class);
        models = mock(ModelSettingsService.class);
        settings = mock(AppSettingsService.class);
        when(settings.developmentAutomation()).thenReturn(DevelopmentAutomation.DEFAULT);
        workspaces = mock(WorkspaceRepositoryService.class);
        provisioning = mock(WorkspaceProvisioningService.class);
        when(provisioning.synchronizeCode("workspace-1"))
                .thenReturn(new WorkspaceCodeSyncResult("workspace-1", true, Optional.of(Instant.now()), ImmutableSet.of()));
        git = mock(GitCommandRunner.class);
        when(drivers.require(AgentLaneId.CODEX_CLI)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(tasks.getInteractive(eq("workspace-1"), any())).thenAnswer(invocation -> {
            throw new AgentTaskNotFoundException(invocation.getArgument(1));
        });
        ready();
        progress = new AgentTaskStartupProgress();
        pullRequests = mock(PullRequestStore.class);
        when(git.run(any(Path.class), eq("for-each-ref"), eq("--format=%(refname)"), any(String.class), any(String.class), any(String.class)))
                .thenReturn(new GitCommandRunner.Result(0, "", false));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("test-token", new UserProfile("Chenjian2664", "", "", "", 0, 0, 0, "", "", "", "")));
        creator = new InteractiveAgentTaskCreator(tasks, turns, drivers, models, workspaces, provisioning, git, settings, progress, pullRequests, credentials);
    }

    @Test
    void timingIsOptInAndContainsOnlyPhaseTimestampsAndTaskIds()
    {
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT, false));
        Logger timing = (Logger) LoggerFactory.getLogger("com.bytequay.agent.SendTiming");
        Level previousLevel = timing.getLevel();
        boolean previousAdditive = timing.isAdditive();
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        timing.addAppender(captured);
        timing.setAdditive(false);
        try {
            timing.setLevel(Level.INFO);
            create(defaults());
            assertThat(captured.list).isEmpty();

            timing.setLevel(Level.DEBUG);
            creator.create(TASK_ID, "workspace-1", "private prompt token=do-not-log", AgentLaneId.CODEX_CLI, defaults(), "private-request-key",
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
            assertThat(captured.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).matches("send-timing phase=[a-z_.]+ epochMs=\\d+ taskId=" + TASK_ID + " turnId=-");
            });
            assertThat(captured.list.stream().map(ILoggingEvent::getFormattedMessage))
                    .anyMatch(line -> line.startsWith("send-timing phase=task.repository.begin "))
                    .anyMatch(line -> line.startsWith("send-timing phase=task.repository.end "));
        }
        finally {
            timing.detachAppender(captured);
            captured.stop();
            timing.setLevel(previousLevel);
            timing.setAdditive(previousAdditive);
        }
    }

    @Test
    void keepsThePersistedCreationFingerprintLayout()
    {
        String fingerprint = InteractiveAgentTaskCreator.fingerprint(
                "workspace-1",
                "Fix the bug",
                new AgentLaneId("codex-cli"),
                new AgentTaskDefaults(Optional.of("gpt-5"), Optional.of("high"), AgentPermissionPolicy.AUTO, Optional.of("fast")),
                "key-1",
                Optional.of("attachments-digest"),
                Optional.empty(),
                Optional.of("owner/repo#7"));

        assertThat(fingerprint).isEqualTo("a72d5dcc35062057b813e16e1d1c7867d1a599563d83d5aa86020e3510fb6c70");
    }

    @Test
    void relatedInvestigationUsesExactCommitAndPreservesLocalOnlyAutomation()
    {
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", START_COMMIT + "^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT + "\n", false));
        var automation = new DevelopmentAutomation(true, false, false, Optional.empty(), false, false);
        var defaults = new AgentTaskDefaults(Optional.of("model-a"), Optional.empty(), AgentPermissionPolicy.AUTO).withDevelopmentAutomation(automation);
        creator.createRelated(TASK_ID, "workspace-1", "Investigate", AgentLaneId.CODEX_CLI, defaults, "request-1", Optional.of(START_COMMIT));
        var command = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(tasks).createInteractive(command.capture());
        assertThat(command.getValue().startCommit()).isEqualTo(START_COMMIT);
        assertThat(command.getValue().defaults().developmentAutomation()).contains(automation);
        assertThat(progress.current(TASK_ID)).isEmpty();
        verify(git, never()).run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}");
        when(git.run(repository, "rev-parse", "--verify", START_COMMIT + "^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, "f".repeat(40), false));
        assertThatThrownBy(() -> creator.createRelated(TASK_ID, "workspace-1", "Investigate", AgentLaneId.CODEX_CLI, defaults, "request-1", Optional.of(START_COMMIT)))
                .isInstanceOf(RuntimeException.class);
        verify(tasks).createInteractive(any());
    }

    @Test
    void resolvesSelectedOriginDefaultBranchAndCreatesManagedTask()
    {
        Path repository = temporaryDirectory.resolve("repository");
        WorkspaceRepositoryContext workspace = workspace(repository);
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace);
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT + "\n", false));

        create(new AgentTaskDefaults(Optional.of("model-a"), Optional.of("high"), AgentPermissionPolicy.AUTO));

        ArgumentCaptor<CreateInteractiveAgentTaskCommand> command = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(tasks).createInteractive(command.capture());
        assertThat(command.getValue().workspaceId()).isEqualTo("workspace-1");
        assertThat(command.getValue().physicalRepository())
                .isEqualTo(new RepositoryIdentity("selected", "fork"));
        assertThat(command.getValue().startCommit()).isEqualTo(START_COMMIT);
        assertThat(command.getValue().generatedBranch()).isEqualTo("chenjian2664/bytequay/review-11111111");
        assertThat(command.getValue().defaults().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        InOrder order = inOrder(provisioning, workspaces, git, tasks);
        order.verify(provisioning).synchronizeCode("workspace-1");
        order.verify(workspaces).requireReadyRepository("workspace-1");
        order.verify(git).run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}");
        order.verify(tasks).createInteractive(any());
        // The controller clears the phase once its request returns; the creator leaves its last step behind.
        assertThat(progress.current(TASK_ID)).contains(AgentTaskStartupProgress.Phase.WORKTREE);
    }

    @Test
    void resolvesUpstreamDefaultBranchWhileKeepingTheForkAsPhysicalRepository()
    {
        Path repository = temporaryDirectory.resolve("repository");
        WorkspaceRepositoryContext workspace = new WorkspaceRepositoryContext(
                "workspace-1",
                repository,
                1,
                RepoRef.of("selected", "fork"),
                OptionalLong.of(2),
                RepoRef.of("upstream", "project"),
                "upstream",
                "origin",
                "master",
                ImmutableList.of());
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace);
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/upstream/master^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT + "\n", false));

        create(new AgentTaskDefaults(Optional.of("model-a"), Optional.of("high"), AgentPermissionPolicy.AUTO));

        ArgumentCaptor<CreateInteractiveAgentTaskCommand> command = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(tasks).createInteractive(command.capture());
        assertThat(command.getValue().physicalRepository()).isEqualTo(new RepositoryIdentity("selected", "fork"));
        assertThat(command.getValue().startCommit()).isEqualTo(START_COMMIT);
        assertThat(command.getValue().defaults().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        InOrder order = inOrder(provisioning, workspaces, git, tasks);
        order.verify(provisioning).synchronizeCode("workspace-1");
        order.verify(workspaces).requireReadyRepository("workspace-1");
        order.verify(git).run(repository, "rev-parse", "--verify", "refs/remotes/upstream/master^{commit}");
        order.verify(tasks).createInteractive(any());
        verify(git, never()).run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}");
    }

    @Test
    void failedFetchDoesNotUsePreviouslySynchronizedRefOrCreateTask()
    {
        when(provisioning.synchronizeCode("workspace-1"))
                .thenReturn(new WorkspaceCodeSyncResult("workspace-1", false, Optional.of(Instant.now().minusSeconds(300)), ImmutableSet.of()));

        assertThatThrownBy(() -> create(defaults()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("synchronization failed");

        verifyNoInteractions(workspaces, git);
        verify(tasks, never()).createInteractive(any());
    }

    @Test
    void firstTurnOverrideDoesNotReplaceSavedDefaultsAndReplayKeepsTheOriginalPolicy()
    {
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT, false));
        when(tasks.createInteractive(any())).thenAnswer(invocation -> {
            CreateInteractiveAgentTaskCommand command = invocation.getArgument(0);
            return AgentTask.created(
                    command.taskId(),
                    true,
                    Optional.of(command.creationFingerprint()),
                    "Task",
                    TitleSource.USER,
                    command.laneId(),
                    new TaskWorktree(command.taskId(), command.workspaceId(), command.physicalRepository(),
                            temporaryDirectory.resolve(command.taskId().toString()), command.generatedBranch(), START_COMMIT),
                    command.defaults(),
                    Instant.parse("2026-09-01T00:00:00Z"));
        });
        TaskId taskId = TaskId.random();
        AgentTask created = creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI, defaults(), "create-key", Optional.empty(),
                Optional.of(DevelopmentAutomation.LOCAL_ONLY), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(created.defaults().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        when(settings.developmentAutomation()).thenReturn(DevelopmentAutomation.LOCAL_ONLY);
        doReturn(created).when(tasks).getInteractive("workspace-1", taskId);

        assertThat(creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI, defaults(), "create-key", Optional.empty(),
                Optional.of(DevelopmentAutomation.LOCAL_ONLY), Optional.empty(), Optional.empty(), Optional.empty()))
                .isSameAs(created);
        verify(settings, times(1)).developmentAutomation();
        verify(tasks, times(1)).createInteractive(any());
        JiraIssueSource jira = new JiraIssueSource("connection", "cloud", "10001", "APP-7", "https://company.atlassian.net/browse/APP-7",
                "Jira title", "Jira description", Instant.EPOCH);
        assertThatThrownBy(() -> creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI, defaults(), "create-key", Optional.empty(),
                Optional.of(DevelopmentAutomation.LOCAL_ONLY), Optional.empty(), Optional.of(jira), Optional.empty()))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("creation request changed");
        assertThatThrownBy(() -> creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI, defaults(), "create-key", Optional.empty(),
                Optional.of(DevelopmentAutomation.LOCAL_ONLY), Optional.of(issue()), Optional.empty(), Optional.empty()))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("creation request changed");
        assertThatThrownBy(() -> creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI, defaults(), "create-key", Optional.empty(),
                Optional.of(DevelopmentAutomation.DEFAULT), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("creation request changed");
    }

    @Test
    void deepSeekCreationKeepsAcceptedCommitAndCiSettings()
    {
        AgentLaneId lane = new AgentLaneId("deepseek-key-11111111-1111-1111-1111-111111111111");
        DevelopmentAutomation automation = new DevelopmentAutomation(true, false, true, Optional.of(AgentLaneId.CODEX_CLI));
        when(drivers.require(lane)).thenReturn(driver);
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT, false));
        when(tasks.createInteractive(any())).thenAnswer(invocation -> {
            CreateInteractiveAgentTaskCommand command = invocation.getArgument(0);
            return AgentTask.created(command.taskId(), true, Optional.of(command.creationFingerprint()), "Task", TitleSource.USER,
                    command.laneId(), new TaskWorktree(command.taskId(), command.workspaceId(), command.physicalRepository(),
                    temporaryDirectory.resolve(command.taskId().toString()), command.generatedBranch(), START_COMMIT),
                    command.defaults(), Instant.parse("2026-09-01T00:00:00Z"));
        });

        AgentTask created = creator.create(TASK_ID, "workspace-1", "Task", lane, defaults().withDevelopmentAutomation(automation), "create-key",
                Optional.empty(), Optional.of(automation), Optional.empty(), Optional.empty(), Optional.empty());

        ArgumentCaptor<CreateInteractiveAgentTaskCommand> command = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(tasks).createInteractive(command.capture());
        assertThat(command.getValue().defaults().developmentAutomation()).contains(automation);
        doReturn(created).when(tasks).getInteractive("workspace-1", TASK_ID);
        assertThat(creator.create(TASK_ID, "workspace-1", "Task", lane, defaults().withDevelopmentAutomation(automation), "create-key", Optional.empty(),
                Optional.of(automation), Optional.empty(), Optional.empty(), Optional.empty())).isSameAs(created);
        assertThatThrownBy(() -> creator.create(TASK_ID, "workspace-1", "Task", lane, defaults().withDevelopmentAutomation(automation), "create-key",
                Optional.empty(), Optional.of(new DevelopmentAutomation(true, false, false, Optional.empty())), Optional.empty(), Optional.empty(),
                Optional.empty()))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("creation request changed");
        verify(driver, times(2)).probe(Optional.of("model-a"));
        verify(settings, never()).developmentAutomation();
        verify(settings, times(2)).validateDevelopmentAutomation(automation);
    }

    @Test
    void deepSeekRejectsInvalidSelectedCiLaneBeforeRepositoryWork()
    {
        AgentLaneId lane = new AgentLaneId("deepseek-key-11111111-1111-1111-1111-111111111111");
        DevelopmentAutomation invalid = new DevelopmentAutomation(true, false, true, Optional.of(new AgentLaneId("unsupported")));
        doThrow(new IllegalArgumentException("Agent lane unsupported does not support CI Autofix"))
                .when(settings).validateDevelopmentAutomation(invalid);

        assertThatThrownBy(() -> creator.create(TASK_ID, "workspace-1", "Task", lane, defaults(), "create-key", Optional.empty(), Optional.of(invalid),
                Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support CI Autofix");

        verifyNoInteractions(provisioning, workspaces, git);
        verify(tasks, never()).createInteractive(any());
    }

    @Test
    void readinessFailureDoesNotResolveWorkspaceOrGit()
    {
        when(driver.probe()).thenReturn(new AgentProviderStatus(
                AgentLaneId.CODEX_CLI,
                AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(),
                "login required",
                AgentCapabilities.none()));

        assertThatThrownBy(() -> create(defaults()))
                .isInstanceOfSatisfying(AgentProviderUnavailableException.class, failure ->
                        assertThat(failure.status().readiness())
                                .isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED));

        verifyNoInteractions(provisioning, workspaces, git);
    }

    @ParameterizedTest
    @CsvSource({"unknown-model, high, unknown model", "model-a, unsupported, unknown effort"})
    void invalidSelectionDoesNotResolveWorkspaceAfterReadinessProbe(String model, String effort, String message)
    {
        AgentTaskDefaults defaults = new AgentTaskDefaults(Optional.of(model), Optional.of(effort), AgentPermissionPolicy.AUTO);
        doThrow(new IllegalArgumentException(message))
                .when(models)
                .validateSelection("codex", model, Optional.of(effort), Optional.empty());

        assertThatThrownBy(() -> create(defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);

        verify(driver).probe();
        verifyNoInteractions(provisioning, workspaces, git);
    }

    @Test
    void missingRemoteDefaultBranchDoesNotCreateTask()
    {
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(GitCommandRunner.Result.failed("missing ref"));

        assertThatThrownBy(() -> create(defaults()))
                .hasMessageContaining("start commit");

        verify(tasks, never()).createInteractive(any());
    }

    @Test
    void provisionsImmediatelyWithLocalNamesAndTheAlreadyCheckedProvider()
    {
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT, false));
        creator.create(TASK_ID, "workspace-1", "Develop the following GitHub issue", AgentLaneId.CODEX_CLI, defaults(), "create-key", Optional.empty(),
                Optional.empty(), Optional.of(issue()), Optional.empty(), Optional.empty());

        ArgumentCaptor<CreateInteractiveAgentTaskCommand> command = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(tasks).createInteractive(command.capture());
        assertThat(command.getValue().names()).isEmpty();
        assertThat(command.getValue().generatedBranch()).isEqualTo("chenjian2664/bytequay/develop-the-following-github-11111111");
        assertThat(command.getValue().useTrustedCreationReadiness()).isTrue();
        verify(driver, times(1)).probe();
    }

    @Test
    void avoidsProvisionalBranchesRetainedByGitOrAPullRequest()
    {
        Path repository = temporaryDirectory.resolve("repository");
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace(repository));
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT, false));
        when(git.run(repository, "for-each-ref", "--format=%(refname)",
                "refs/heads/chenjian2664/bytequay/review-11111111", "refs/remotes/origin/chenjian2664/bytequay/review-11111111",
                "refs/remotes/upstream/chenjian2664/bytequay/review-11111111"))
                .thenReturn(new GitCommandRunner.Result(0, "refs/remotes/origin/chenjian2664/bytequay/review-11111111\n", false));
        create(defaults());
        ArgumentCaptor<CreateInteractiveAgentTaskCommand> command = ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
        verify(tasks).createInteractive(command.capture());
        assertThat(command.getValue().generatedBranch()).isEqualTo("chenjian2664/bytequay/review-11111111-11111111");

        when(git.run(repository, "for-each-ref", "--format=%(refname)",
                "refs/heads/chenjian2664/bytequay/review-11111111", "refs/remotes/origin/chenjian2664/bytequay/review-11111111",
                "refs/remotes/upstream/chenjian2664/bytequay/review-11111111"))
                .thenReturn(new GitCommandRunner.Result(0, "", false));
        PullRequest previous = new PullRequest(1, "selected/fork", 1, "Fix samples", "user", "https://github.com/selected/fork/pull/1",
                Instant.EPOCH, Instant.EPOCH, PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, null, null, null,
                ImmutableList.of(), null, 0, 0, 0, "closed", Instant.EPOCH, null, null, null, null, "chenjian2664/bytequay/review-11111111", null, null, null, null);
        when(pullRequests.findByHeadRef("selected/fork", "chenjian2664/bytequay/review-11111111")).thenReturn(ImmutableList.of(previous));
        create(defaults());
        verify(tasks, times(2)).createInteractive(command.capture());
        assertThat(command.getValue().generatedBranch()).isEqualTo("chenjian2664/bytequay/review-11111111-11111111");
    }

    private static GitHubIssueSource issue()
    {
        return new GitHubIssueSource("github.com", 70, "selected/fork", 31178, "https://github.com/selected/fork/issues/31178",
                "Duplicate table samples", "TABLESAMPLE produces duplicates", Instant.parse("2026-09-01T00:00:00Z"));
    }

    private void ready()
    {
        AgentProviderStatus ready = new AgentProviderStatus(AgentLaneId.CODEX_CLI, AgentProviderStatus.Readiness.READY, Optional.of("test"), "ready", AgentCapabilities.none());
        when(driver.probe()).thenReturn(ready);
        when(driver.probe(any())).thenReturn(ready);
    }

    private AgentTask create(AgentTaskDefaults defaults)
    {
        return creator.create(TASK_ID, "workspace-1", "Review", AgentLaneId.CODEX_CLI, defaults, "create-key", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static AgentTaskDefaults defaults()
    {
        return new AgentTaskDefaults(Optional.of("model-a"), Optional.of("high"), AgentPermissionPolicy.AUTO);
    }

    private WorkspaceRepositoryContext workspace(Path repository)
    {
        return new WorkspaceRepositoryContext(
                "workspace-1",
                repository,
                1,
                RepoRef.of("selected", "fork"),
                OptionalLong.empty(),
                RepoRef.of("selected", "fork"),
                "origin",
                "origin",
                "main",
                ImmutableList.of());
    }
}
