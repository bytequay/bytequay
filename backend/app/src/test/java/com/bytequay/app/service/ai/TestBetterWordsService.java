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
package com.bytequay.app.service.ai;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AgentSkillService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBetterWordsService
{
    private static final String REPOSITORY = "owner/repo";

    @TempDir
    private Path temporaryDirectory;

    private final AgentSkillService skills = mock(AgentSkillService.class);

    @BeforeEach
    void setUp()
    {
        when(skills.instructions()).thenReturn("Use plain language.");
    }

    @Test
    void usesTextOnlyCliSessionAndDeletesScratchResources()
            throws Exception
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.agentModel(eq(AgentModelType.DEVELOPMENT), eq(Optional.of("ws-1")))).thenReturn(
                new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude", "sonnet", "Sonnet", Optional.of("Low")));
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        AgentResourceService resources = mock(AgentResourceService.class);
        Path runtime = temporaryDirectory.resolve("runtime");
        Path provider = runtime.resolve("provider/scratch");
        Files.createDirectories(provider);
        when(resources.allocate(any())).thenReturn(new TaskResources(
                runtime,
                runtime.resolve("events.jsonl"),
                runtime.resolve("blobs"),
                provider));

        AgentDriver driver = mock(AgentDriver.class);
        AgentSession session = mock(AgentSession.class);
        RunningTurn turn = mock(RunningTurn.class);
        when(drivers.require(new AgentLaneId("claude"))).thenReturn(driver);
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(new AgentTurnResult(
                AgentTurnResult.State.COMPLETED,
                Optional.of("Could we rename this for clarity?"),
                Optional.empty(),
                Duration.ofMillis(20),
                true)));

        BetterWordsService service = service(settings, workspaceStore("ws-1", RepoRef.of("owner", "repo")), drivers, resources);
        assertThat(service.improve("rename this var pls", REPOSITORY, Optional.of("ws-1")))
                .isEqualTo("Could we rename this for clarity?");

        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any(AgentEventSink.class));
        assertThat(context.getValue().permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(context.getValue().textOnly()).isTrue();
        assertThat(context.getValue().model()).contains("sonnet");
        assertThat(context.getValue().effort()).contains("Low");
        assertThat(context.getValue().managedInstructions()).isEqualTo("Use plain language.");
        assertThat(context.getValue().worktree()).startsWith(runtime);

        ArgumentCaptor<AgentTurnCommand> command = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(command.capture());
        assertThat(command.getValue().message())
                .contains(
                        "GitHub code review or related issue discussion",
                        "professional, friendly, and concise",
                        "without inventing details",
                        "Keep requirements firm and suggestions optional",
                        "These rewrite requirements take precedence over style preferences",
                        "rename this var pls")
                .contains("Do not use tools");
        verify(session).close();
        verify(skills).instructions();

        ArgumentCaptor<TaskId> taskId = ArgumentCaptor.forClass(TaskId.class);
        verify(resources).allocate(taskId.capture());
        verify(resources).delete(taskId.getValue());
        verify(settings).agentModel(AgentModelType.DEVELOPMENT, Optional.of("ws-1"));
    }

    @Test
    void rewritesOnTheWorkspaceDevelopmentDefaultEvenWhenItIsADeepSeekKeyLane()
            throws Exception
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.agentModel(eq(AgentModelType.DEVELOPMENT), eq(Optional.of("ws-1")))).thenReturn(developmentModel("deepseek-key-7", "deepseek-v4-pro"));
        AgentResourceService resources = mock(AgentResourceService.class);
        Path runtime = temporaryDirectory.resolve("deepseek-runtime");
        Files.createDirectories(runtime.resolve("provider"));
        when(resources.allocate(any())).thenReturn(new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider")));
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        AgentDriver driver = mock(AgentDriver.class);
        AgentSession session = mock(AgentSession.class);
        RunningTurn turn = mock(RunningTurn.class);
        when(drivers.require(new AgentLaneId("deepseek-key-7"))).thenReturn(driver);
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(
                new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("Could you explain this change?"), Optional.empty(), Duration.ofMillis(20), true)));

        BetterWordsService service = service(settings, workspaceStore("ws-1", RepoRef.of("owner", "repo")), drivers, resources);

        assertThat(service.improve("why u do this?", REPOSITORY, Optional.of("ws-1"))).isEqualTo("Could you explain this change?");
        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any(AgentEventSink.class));
        assertThat(context.getValue().model()).contains("deepseek-v4-pro");
        verify(drivers).require(new AgentLaneId("deepseek-key-7"));
        verify(settings, never()).agentModel(eq(AgentModelType.REVIEW), any());
    }

    @Test
    void retainsScratchResourcesWhenCliShutdownCannotBeVerified()
            throws Exception
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.agentModel(eq(AgentModelType.DEVELOPMENT), any())).thenReturn(developmentModel("claude", "sonnet"));
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        AgentResourceService resources = mock(AgentResourceService.class);
        Path runtime = temporaryDirectory.resolve("retained-runtime");
        Path provider = runtime.resolve("provider/scratch");
        Files.createDirectories(provider);
        when(resources.allocate(any())).thenReturn(new TaskResources(
                runtime,
                runtime.resolve("events.jsonl"),
                runtime.resolve("blobs"),
                provider));

        AgentDriver driver = mock(AgentDriver.class);
        AgentSession session = mock(AgentSession.class);
        RunningTurn turn = mock(RunningTurn.class);
        when(drivers.require(new AgentLaneId("claude"))).thenReturn(driver);
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(CompletableFuture.completedFuture(new AgentTurnResult(
                AgentTurnResult.State.COMPLETED,
                Optional.of("Rewritten comment"),
                Optional.empty(),
                Duration.ofMillis(20),
                true)));
        doThrow(new AgentUnavailableException("unverifiable process tree")).when(session).close();

        BetterWordsService service = service(settings, mock(WorkspaceStore.class), drivers, resources);

        assertThatThrownBy(() -> service.improve("rewrite this", REPOSITORY, Optional.empty()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("could not be verified stopped");
        verify(resources, never()).delete(any());
    }

    @Test
    void rejectsInvalidInput()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        AgentResourceService resources = mock(AgentResourceService.class);
        BetterWordsService service = service(settings, mock(WorkspaceStore.class), mock(AgentDriverRegistry.class), resources);

        assertThatThrownBy(() -> service.improve(null, REPOSITORY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Write a comment");
        assertThatThrownBy(() -> service.improve("  ", REPOSITORY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Write a comment");
        assertThatThrownBy(() -> service.improve("x".repeat(20_001), REPOSITORY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
        assertThatThrownBy(() -> service.improve("Please clarify this.", "  ", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository");
        verify(resources, never()).allocate(any());
    }

    @Test
    void rejectsMissingOrMismatchedWorkspaceContext()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        WorkspaceStore workspaces = workspaceStore("wrong-repo", RepoRef.of("other", "repo"));
        BetterWordsService service = service(settings, workspaces, mock(AgentDriverRegistry.class), mock(AgentResourceService.class));

        assertThatThrownBy(() -> service.improve("Please clarify this.", REPOSITORY, Optional.of("removed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer available");
        assertThatThrownBy(() -> service.improve("Please clarify this.", REPOSITORY, Optional.of("wrong-repo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        verify(settings, never()).agentModel(any(), any());
    }

    @Test
    void validatesForkWorkspaceAgainstItsMonitoredRepository()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.agentModel(eq(AgentModelType.DEVELOPMENT), any())).thenReturn(developmentModel("codex", "gpt-5.6-sol"));
        AgentResourceService resources = mock(AgentResourceService.class);
        // The monitored repository matched, so the rewrite runs; stop it at its first provider step.
        when(resources.allocate(any())).thenThrow(new IllegalStateException("reached the provider"));
        BetterWordsService service = service(
                settings,
                linkedWorkspaceStore(
                        "fork-workspace",
                        RepoRef.of("user", "fork"),
                        RepoRef.of("owner", "repo")),
                mock(AgentDriverRegistry.class),
                resources);

        assertThatThrownBy(() -> service.improve(
                "Please clarify this.", "OWNER/REPO", Optional.of("fork-workspace")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reached the provider");
        assertThatThrownBy(() -> service.improve(
                "Please clarify this.", "user/fork", Optional.of("fork-workspace")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    private BetterWordsService service(ModelSettingsService settings, WorkspaceStore workspaces, AgentDriverRegistry drivers, AgentResourceService resources)
    {
        return new BetterWordsService(settings, workspaces, drivers, resources, skills);
    }

    private static WorkspaceStore workspaceStore(String workspaceId, RepoRef repository)
    {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        Workspace workspace = new Workspace(
                workspaceId,
                workspaceId,
                1,
                repository,
                Optional.empty(),
                Optional.empty(),
                1,
                repository,
                Workspace.Topology.DIRECT,
                "main",
                "https://github.com/" + repository.fullName() + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/bytequay-better-words/" + workspaceId,
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(workspace));
        return workspaces;
    }

    private static WorkspaceStore linkedWorkspaceStore(
            String workspaceId,
            RepoRef selectedRepository,
            RepoRef monitoredRepository)
    {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        Workspace workspace = new Workspace(
                workspaceId,
                workspaceId,
                1,
                selectedRepository,
                Optional.of(2L),
                Optional.of(monitoredRepository),
                2,
                monitoredRepository,
                Workspace.Topology.FORK,
                "main",
                "https://github.com/" + selectedRepository.fullName() + ".git",
                Optional.of("main"),
                Optional.of("https://github.com/" + monitoredRepository.fullName() + ".git"),
                "/tmp/bytequay-better-words/" + workspaceId,
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(workspace));
        return workspaces;
    }

    private static AgentModelSetting developmentModel(String configId, String model)
    {
        return new AgentModelSetting(AgentModelType.DEVELOPMENT, true, configId, configId, model, model, Optional.empty());
    }
}
