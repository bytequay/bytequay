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
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
    private ModelSettingsService models;
    private AppSettingsService settings;
    private WorkspaceRepositoryService workspaces;
    private WorkspaceProvisioningService provisioning;
    private GitCommandRunner git;
    private AgentTaskStartupProgress progress;
    private InteractiveAgentTaskCreator creator;
    private CredentialService credentials;

    @BeforeEach
    void setUp()
    {
        tasks = mock(AgentTaskService.class);
        turns = mock(AgentTurnService.class);
        driver = mock(AgentDriver.class);
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        models = mock(ModelSettingsService.class);
        settings = mock(AppSettingsService.class);
        when(settings.developmentAutomation()).thenReturn(DevelopmentAutomation.DEFAULT);
        workspaces = mock(WorkspaceRepositoryService.class);
        provisioning = mock(WorkspaceProvisioningService.class);
        when(provisioning.synchronizeCode("workspace-1"))
                .thenReturn(new WorkspaceCodeSyncResult("workspace-1", true, Optional.of(Instant.now())));
        git = mock(GitCommandRunner.class);
        when(drivers.require(AgentLaneId.CODEX_CLI)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(tasks.getInteractive(eq("workspace-1"), any())).thenAnswer(invocation -> {
            throw new AgentTaskNotFoundException(invocation.getArgument(1));
        });
        ready();
        credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential(
                "test-token", new UserProfile("chenjian2664", "", "", "", 0, 0, 0, "", "", "", "")));
        progress = new AgentTaskStartupProgress();
        creator = new InteractiveAgentTaskCreator(tasks, turns, drivers, models, workspaces, provisioning, git, settings, credentials, progress);
    }

    @Test
    void resolvesSelectedOriginDefaultBranchAndCreatesManagedTask()
    {
        Path repository = temporaryDirectory.resolve("repository");
        WorkspaceRepositoryContext workspace = workspace(repository);
        when(workspaces.requireReadyRepository("workspace-1")).thenReturn(workspace);
        when(git.run(repository, "rev-parse", "--verify", "refs/remotes/origin/main^{commit}"))
                .thenReturn(new GitCommandRunner.Result(0, START_COMMIT + "\n", false));

        create(new AgentTaskDefaults(
                        Optional.of("model-a"),
                        Optional.of("high"),
                        AgentPermissionPolicy.AUTO));

        ArgumentCaptor<CreateInteractiveAgentTaskCommand> command =
                ArgumentCaptor.forClass(CreateInteractiveAgentTaskCommand.class);
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

        create(new AgentTaskDefaults(
                        Optional.of("model-a"),
                        Optional.of("high"),
                        AgentPermissionPolicy.AUTO));

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
                .thenReturn(new WorkspaceCodeSyncResult("workspace-1", false, Optional.of(Instant.now().minusSeconds(300))));

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
        AgentTask created = creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI,
                defaults(), "create-key", Optional.empty(), Optional.of(DevelopmentAutomation.LOCAL_ONLY));

        assertThat(created.defaults().developmentAutomation()).contains(DevelopmentAutomation.DEFAULT);
        when(settings.developmentAutomation()).thenReturn(DevelopmentAutomation.LOCAL_ONLY);
        doReturn(created).when(tasks).getInteractive("workspace-1", taskId);
        when(credentials.resolveGitHubCredential()).thenThrow(new IllegalStateException("Account no longer available"));

        assertThat(creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI,
                defaults(), "create-key", Optional.empty(), Optional.of(DevelopmentAutomation.LOCAL_ONLY)))
                .isSameAs(created);
        verify(credentials, times(1)).resolveGitHubCredential();
        verify(settings, times(1)).developmentAutomation();
        verify(tasks, times(1)).createInteractive(any());
        assertThatThrownBy(() -> creator.create(taskId, "workspace-1", "Task", AgentLaneId.CODEX_CLI,
                defaults(), "create-key", Optional.empty(), Optional.of(DevelopmentAutomation.DEFAULT)))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("creation request changed");
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

    @Test
    void invalidModelDoesNotProbeOrResolveWorkspace()
    {
        AgentTaskDefaults defaults = new AgentTaskDefaults(
                Optional.of("unknown-model"),
                Optional.of("high"),
                AgentPermissionPolicy.AUTO);
        doThrow(new IllegalArgumentException("unknown model"))
                .when(models)
                .validateSelection("codex", "unknown-model", Optional.of("high"), Optional.empty());

        assertThatThrownBy(() -> create(defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown model");

        verify(driver).probe();
        verifyNoInteractions(provisioning, workspaces, git);
    }

    @Test
    void invalidEffortDoesNotProbeOrResolveWorkspace()
    {
        AgentTaskDefaults defaults = new AgentTaskDefaults(
                Optional.of("model-a"),
                Optional.of("unsupported"),
                AgentPermissionPolicy.AUTO);
        doThrow(new IllegalArgumentException("unknown effort"))
                .when(models)
                .validateSelection("codex", "model-a", Optional.of("unsupported"), Optional.empty());

        assertThatThrownBy(() -> create(defaults))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown effort");

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

    private void ready()
    {
        when(driver.probe()).thenReturn(new AgentProviderStatus(
                AgentLaneId.CODEX_CLI,
                AgentProviderStatus.Readiness.READY,
                Optional.of("test"),
                "ready",
                AgentCapabilities.none()));
    }

    private AgentTask create(AgentTaskDefaults defaults)
    {
        return creator.create(
                TASK_ID,
                "workspace-1",
                "Review",
                AgentLaneId.CODEX_CLI,
                defaults,
                "create-key");
    }

    private static AgentTaskDefaults defaults()
    {
        return new AgentTaskDefaults(
                Optional.of("model-a"),
                Optional.of("high"),
                AgentPermissionPolicy.AUTO);
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
