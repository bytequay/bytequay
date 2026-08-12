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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.NewFlowAgentLaunches.Config;
import com.bytequay.app.flow.runtime.NewFlowAgentLaunches.LaunchUnavailableException;
import com.bytequay.app.service.agents.TurnSpec.Transport;
import com.bytequay.app.service.workmodel.WorkModelService;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The engine a CI repair launches on is the user's workspace pick, resolved
 * fresh per run. Nothing in this path may fall back to a compiled-in provider.
 */
class TestNewFlowEngineResolver
{
    private static final String REPO = "acme/widgets";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WorkspaceEngineSettings settings = mock(WorkspaceEngineSettings.class);
    private final WorkModelService workModels = mock(WorkModelService.class);
    private final NewFlowEngineResolver resolver = new NewFlowEngineResolver(
            mock(FlowRuntime.class), jdbc, settings, workModels, new ObjectMapper());

    @Test
    void eachRoleReadsItsOwnWorkspaceAudience()
    {
        assertThat(NewFlowEngineResolver.audienceFor(AgentRole.CI_FIXER)).isEqualTo("ci-fix");
        assertThat(NewFlowEngineResolver.audienceFor(AgentRole.CI_LEARNER)).isEqualTo("ci-fix");
        assertThat(NewFlowEngineResolver.audienceFor(AgentRole.TASK_AGENT)).isEqualTo("dev");
        assertThat(NewFlowEngineResolver.audienceFor(AgentRole.ADVERSARIAL_REVIEWER))
                .isEqualTo("review");
    }

    @Test
    void theCiFixPickBecomesTheLaunchConfig()
    {
        workspace("ws-1");
        WorkModel picked = new WorkModel(
                WorkModelKind.API, "anthropic", null, "work", "xhigh");
        when(settings.forAudience("ws-1", "ci-fix"))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(picked, true)));
        when(workModels.freeze(picked)).thenReturn(new WorkModel(
                WorkModelKind.API, "anthropic", "claude-opus-4-8", "work", "xhigh"));
        when(workModels.resolveEffort(any(), eq("anthropic"), eq("claude-opus-4-8"), eq("xhigh")))
                .thenReturn("xhigh");

        Config config = resolver.resolve(task(), AgentRole.CI_FIXER);

        assertThat(config.providerName()).isEqualTo("anthropic");
        assertThat(config.transport()).isEqualTo(Transport.ANTHROPIC);
        assertThat(config.model()).isEqualTo("claude-opus-4-8");
        assertThat(config.reasoningEffort()).isEqualTo("xhigh");
        assertThat(config.credentialName()).isEqualTo("anthropic");
        assertThat(config.credentialInstance()).isEqualTo("work");
    }

    @Test
    void anUnconfiguredWorkspaceFallsBackToWhatIsInstalled()
    {
        workspace("ws-1");
        when(settings.forAudience(anyString(), anyString())).thenReturn(Optional.empty());
        when(jdbc.queryForList(contains("work_model_json"), eq(String.class), any()))
                .thenReturn(List.of());
        WorkModel discovered = new WorkModel(WorkModelKind.API, "openai", null, null, null);
        when(workModels.discoverEngines()).thenReturn(List.of(discovered));
        when(workModels.freeze(discovered)).thenReturn(new WorkModel(
                WorkModelKind.API, "openai", "gpt-5", "default api", null));

        Config config = resolver.resolve(task(), AgentRole.CI_FIXER);

        assertThat(config.providerName()).isEqualTo("openai");
        assertThat(config.transport()).isEqualTo(Transport.OPENAI_COMPAT);
        assertThat(config.model()).isEqualTo("gpt-5");
    }

    @Test
    void aMachineWithNoEngineAtAllFailsRatherThanInventingOne()
    {
        workspace("ws-1");
        when(settings.forAudience(anyString(), anyString())).thenReturn(Optional.empty());
        when(jdbc.queryForList(contains("work_model_json"), eq(String.class), any()))
                .thenReturn(List.of());
        when(workModels.discoverEngines()).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve(task(), AgentRole.CI_FIXER))
                .isInstanceOf(LaunchUnavailableException.class)
                .hasMessageContaining("no agent engine is configured");
    }

    @Test
    void discoveryPassesOverEnginesTheRuntimeCannotRun()
    {
        workspace("ws-1");
        when(settings.forAudience(anyString(), anyString())).thenReturn(Optional.empty());
        when(jdbc.queryForList(contains("work_model_json"), eq(String.class), any()))
                .thenReturn(List.of());
        WorkModel cli = new WorkModel(WorkModelKind.CLI, "claude-code", null, null, null);
        WorkModel api = new WorkModel(WorkModelKind.API, "openai", null, null, null);
        when(workModels.discoverEngines()).thenReturn(List.of(cli, api));
        when(workModels.freeze(api)).thenReturn(new WorkModel(
                WorkModelKind.API, "openai", "gpt-5", "default api", null));

        // An installed CLI is offered first but cannot be launched yet, so
        // discovery keeps walking instead of parking a red build.
        assertThat(resolver.resolve(task(), AgentRole.CI_FIXER).providerName())
                .isEqualTo("openai");
    }

    @Test
    void anExplicitCliPickResolvesToACliLaunchNotAnApiOne()
    {
        workspace("ws-1");
        WorkModel picked = new WorkModel(WorkModelKind.CLI, "claude-code", null, null, null);
        when(settings.forAudience("ws-1", "ci-fix"))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(picked, true)));
        when(workModels.freeze(picked)).thenReturn(new WorkModel(
                WorkModelKind.CLI, "claude-code", "claude-opus-4-8", null, null));

        Config config = resolver.resolve(task(), AgentRole.CI_FIXER);

        assertThat(config.execution()).isEqualTo(AgentExecution.CLI);
        assertThat(config.providerName()).isEqualTo("claude-code");
        assertThat(config.model()).isEqualTo("claude-opus-4-8");
        assertThat(config.cliBinary()).isEqualTo("claude");
        // The absences are the point. A CLI turn is authorized by the user's own
        // login, so a config that carried a credential name or an endpoint would
        // be claiming an authorization this program does not hold.
        assertThat(config.transport()).isNull();
        assertThat(config.endpoint()).isNull();
        assertThat(config.credentialName()).isNull();
        assertThat(config.credentialInstance()).isNull();
        assertThat(config.maxOutputTokens()).isNull();
        assertThat(config.maxToolIterations()).isNull();
    }

    @Test
    void aCodexPickNamesTheCodexBinary()
    {
        workspace("ws-1");
        WorkModel picked = new WorkModel(WorkModelKind.CLI, "codex", null, null, null);
        when(settings.forAudience("ws-1", "dev"))
                .thenReturn(Optional.of(new WorkspaceEngineSettings.Engine(picked, true)));
        when(workModels.freeze(picked)).thenReturn(new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-5", null, "high"));
        when(workModels.resolveEffort(any(), eq("codex"), eq("gpt-5"), eq("high")))
                .thenReturn("high");

        // Both CLI engines are supported; the workspace pick decides, not a
        // compiled-in preference for one vendor's agent.
        Config config = resolver.resolve(task(), AgentRole.TASK_AGENT);

        assertThat(config.cliBinary()).isEqualTo("codex");
        assertThat(config.reasoningEffort()).isEqualTo("high");
    }

    private void workspace(String workspaceId)
    {
        when(jdbc.queryForList(contains("workspace_repos"), eq(String.class), eq(REPO)))
                .thenReturn(List.of(workspaceId));
    }

    private static Task task()
    {
        Task task = mock(Task.class);
        when(task.repositoryId()).thenReturn(REPO);
        when(task.status()).thenReturn(TaskStatus.ACTIVE);
        return task;
    }
}
