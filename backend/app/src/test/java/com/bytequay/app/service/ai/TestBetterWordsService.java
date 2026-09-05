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
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService.MainModel;
import com.bytequay.app.service.settings.ModelSettingsService.WorkspaceAgentModel;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.ModelApiKeyStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestBetterWordsService
{
    private static final String REPOSITORY = "owner/repo";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void usesTextOnlyCliSessionAndDeletesScratchResources()
            throws Exception
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.workspaceOverride("ws-1")).thenReturn(Optional.of(new WorkspaceAgentModel(
                "claude", "Claude", "sonnet", "Sonnet", Optional.of("Low"))));
        ModelApiKeyStore keys = mock(ModelApiKeyStore.class);
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
                Duration.ofMillis(20))));

        BetterWordsService service = service(
                settings,
                workspaceStore("ws-1", RepoRef.of("owner", "repo")),
                keys,
                drivers,
                resources,
                unusedClient(),
                unusedClient());
        assertThat(service.improve("rename this var pls", REPOSITORY, Optional.of("ws-1")))
                .isEqualTo("Could we rename this for clarity?");

        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any(AgentEventSink.class));
        assertThat(context.getValue().permissionPolicy()).isEqualTo(AgentPermissionPolicy.AUTO);
        assertThat(context.getValue().textOnly()).isTrue();
        assertThat(context.getValue().model()).contains("sonnet");
        assertThat(context.getValue().effort()).contains("Low");
        assertThat(context.getValue().worktree()).startsWith(runtime);

        ArgumentCaptor<AgentTurnCommand> command = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(command.capture());
        assertThat(command.getValue().message())
                .contains(
                        "natural, native-sounding",
                        "precise, concise",
                        "professional, and friendly",
                        "rename this var pls")
                .contains("Do not use tools");
        verify(session).close();

        ArgumentCaptor<TaskId> taskId = ArgumentCaptor.forClass(TaskId.class);
        verify(resources).allocate(taskId.capture());
        verify(resources).delete(taskId.getValue());
        verify(settings, never()).mainModel();
    }

    @Test
    void retainsScratchResourcesWhenCliShutdownCannotBeVerified()
            throws Exception
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.mainModel()).thenReturn(mainModel("claude", "sonnet"));
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
                Duration.ofMillis(20))));
        doThrow(new AgentUnavailableException("unverifiable process tree")).when(session).close();

        BetterWordsService service = service(
                settings,
                mock(WorkspaceStore.class),
                mock(ModelApiKeyStore.class),
                drivers,
                resources,
                unusedClient(),
                unusedClient());

        assertThatThrownBy(() -> service.improve("rewrite this", REPOSITORY, Optional.empty()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("could not be verified stopped");
        verify(resources, never()).delete(any());
    }

    @Test
    void usesOpenAiResponsesApiWithTheSelectedModelAndKey()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.workspaceOverride("ws-without-override")).thenReturn(Optional.empty());
        when(settings.mainModel()).thenReturn(mainModel("key:work", "gpt-5.2-mini"));
        ModelApiKeyStore keys = apiKeyStore("work", "OpenAI", "sk-openai-secret");

        RestClient.Builder openAiBuilder = RestClient.builder().baseUrl("https://openai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(openAiBuilder).build();
        server.expect(once(), requestTo("https://openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-openai-secret"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model": "gpt-5.2-mini",
                          "input": "fix it asap",
                          "max_output_tokens": 1200,
                          "store": false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"status":"completed","output":[{"content":[{"type":"output_text","text":"Could we address this soon?"}]}]}
                        """, MediaType.APPLICATION_JSON));

        BetterWordsService service = service(
                settings,
                workspaceStore("ws-without-override", RepoRef.of("owner", "repo")),
                keys,
                mock(AgentDriverRegistry.class),
                mock(AgentResourceService.class),
                openAiBuilder.build(),
                unusedClient());
        assertThat(service.improve("fix it asap", REPOSITORY, Optional.of("ws-without-override")))
                .isEqualTo("Could we address this soon?");
        verify(settings).mainModel();
        server.verify();
    }

    @Test
    void usesDeepSeekChatCompletionsWithTheSelectedModelAndKey()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.mainModel()).thenReturn(mainModel("key:personal", "deepseek-chat"));
        ModelApiKeyStore keys = apiKeyStore("personal", "DeepSeek", "sk-deepseek-secret");

        RestClient.Builder deepSeekBuilder = RestClient.builder().baseUrl("https://deepseek.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(deepSeekBuilder).build();
        server.expect(once(), requestTo("https://deepseek.test/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-deepseek-secret"))
                .andExpect(content().json("""
                        {
                          "model": "deepseek-chat",
                          "messages": [
                            {"role": "system"},
                            {"role": "user", "content": "why u do this?"}
                          ],
                          "max_tokens": 1200,
                          "stream": false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Could you explain the reason for this change?"}}]}
                        """, MediaType.APPLICATION_JSON));

        BetterWordsService service = service(
                settings,
                mock(WorkspaceStore.class),
                keys,
                mock(AgentDriverRegistry.class),
                mock(AgentResourceService.class),
                unusedClient(),
                deepSeekBuilder.build());
        assertThat(service.improve("why u do this?", REPOSITORY, Optional.empty()))
                .isEqualTo("Could you explain the reason for this change?");
        server.verify();
    }

    @Test
    void rejectsTruncatedProviderResponses()
    {
        ModelSettingsService openAiSettings = mock(ModelSettingsService.class);
        when(openAiSettings.mainModel()).thenReturn(mainModel("key:work", "gpt-5.2-mini"));
        RestClient.Builder openAiBuilder = RestClient.builder().baseUrl("https://openai.test");
        MockRestServiceServer openAiServer = MockRestServiceServer.bindTo(openAiBuilder).build();
        openAiServer.expect(once(), requestTo("https://openai.test/v1/responses"))
                .andRespond(withSuccess("""
                        {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                         "output":[{"content":[{"type":"output_text","text":"Cut off"}]}]}
                        """, MediaType.APPLICATION_JSON));

        BetterWordsService openAi = service(
                openAiSettings,
                mock(WorkspaceStore.class),
                apiKeyStore("work", "OpenAI", "sk-openai-secret"),
                mock(AgentDriverRegistry.class),
                mock(AgentResourceService.class),
                openAiBuilder.build(),
                unusedClient());
        assertThatThrownBy(() -> openAi.improve("A long comment", REPOSITORY, Optional.empty()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("stopped before finishing");
        openAiServer.verify();

        ModelSettingsService deepSeekSettings = mock(ModelSettingsService.class);
        when(deepSeekSettings.mainModel()).thenReturn(mainModel("key:personal", "deepseek-chat"));
        RestClient.Builder deepSeekBuilder = RestClient.builder().baseUrl("https://deepseek.test");
        MockRestServiceServer deepSeekServer = MockRestServiceServer.bindTo(deepSeekBuilder).build();
        deepSeekServer.expect(once(), requestTo("https://deepseek.test/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"length",
                         "message":{"role":"assistant","content":"Cut off"}}]}
                        """, MediaType.APPLICATION_JSON));

        BetterWordsService deepSeek = service(
                deepSeekSettings,
                mock(WorkspaceStore.class),
                apiKeyStore("personal", "DeepSeek", "sk-deepseek-secret"),
                mock(AgentDriverRegistry.class),
                mock(AgentResourceService.class),
                unusedClient(),
                deepSeekBuilder.build());
        assertThatThrownBy(() -> deepSeek.improve("A long comment", REPOSITORY, Optional.empty()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("stopped before finishing");
        deepSeekServer.verify();
    }

    @Test
    void rejectsInvalidInputAndCliLanesThatCannotDisableTools()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        AgentResourceService resources = mock(AgentResourceService.class);
        BetterWordsService service = service(
                settings,
                mock(WorkspaceStore.class),
                mock(ModelApiKeyStore.class),
                mock(AgentDriverRegistry.class),
                resources,
                unusedClient(),
                unusedClient());

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

        when(settings.mainModel()).thenReturn(mainModel("codex", "gpt-5.6-sol"));
        assertThatThrownBy(() -> service.improve("Please make this clearer.", REPOSITORY, Optional.empty()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("selected CLI model");
        verify(resources, never()).allocate(any());
    }

    @Test
    void rejectsMissingOrMismatchedWorkspaceContext()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        WorkspaceStore workspaces = workspaceStore("wrong-repo", RepoRef.of("other", "repo"));
        BetterWordsService service = service(
                settings,
                workspaces,
                mock(ModelApiKeyStore.class),
                mock(AgentDriverRegistry.class),
                mock(AgentResourceService.class),
                unusedClient(),
                unusedClient());

        assertThatThrownBy(() -> service.improve("Please clarify this.", REPOSITORY, Optional.of("removed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer available");
        assertThatThrownBy(() -> service.improve("Please clarify this.", REPOSITORY, Optional.of("wrong-repo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
        verify(settings, never()).workspaceOverride(any());
        verify(settings, never()).mainModel();
    }

    @Test
    void validatesForkWorkspaceAgainstItsMonitoredRepository()
    {
        ModelSettingsService settings = mock(ModelSettingsService.class);
        when(settings.workspaceOverride("fork-workspace")).thenReturn(Optional.empty());
        when(settings.mainModel()).thenReturn(mainModel("codex", "gpt-5.6-sol"));
        BetterWordsService service = service(
                settings,
                linkedWorkspaceStore(
                        "fork-workspace",
                        RepoRef.of("user", "fork"),
                        RepoRef.of("owner", "repo")),
                mock(ModelApiKeyStore.class),
                mock(AgentDriverRegistry.class),
                mock(AgentResourceService.class),
                unusedClient(),
                unusedClient());

        assertThatThrownBy(() -> service.improve(
                "Please clarify this.", "OWNER/REPO", Optional.of("fork-workspace")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("selected CLI model");
        assertThatThrownBy(() -> service.improve(
                "Please clarify this.", "user/fork", Optional.of("fork-workspace")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    private static BetterWordsService service(
            ModelSettingsService settings,
            WorkspaceStore workspaces,
            ModelApiKeyStore keys,
            AgentDriverRegistry drivers,
            AgentResourceService resources,
            RestClient openAi,
            RestClient deepSeek)
    {
        return new BetterWordsService(settings, workspaces, keys, drivers, resources, openAi, deepSeek);
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

    private static MainModel mainModel(String configId, String model)
    {
        return new MainModel(configId, configId, model, model, Optional.empty());
    }

    private static ModelApiKeyStore apiKeyStore(String alias, String provider, String secret)
    {
        ModelApiKeyStore keys = mock(ModelApiKeyStore.class);
        when(keys.findAll()).thenReturn(ImmutableList.of(
                new ModelApiKey(alias, provider, Instant.parse("2026-08-28T00:00:00Z"))));
        when(keys.findSecret(alias)).thenReturn(Optional.of(secret));
        return keys;
    }

    private static RestClient unusedClient()
    {
        return RestClient.builder().baseUrl("https://unused.test").build();
    }
}
