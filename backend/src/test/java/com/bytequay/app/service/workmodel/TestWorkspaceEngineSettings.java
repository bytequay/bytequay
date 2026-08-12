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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The workspace settings page writes picker choice ids; these are the
 *  mappings the resolver depends on. */
class TestWorkspaceEngineSettings
{
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WorkspaceEngineSettings settings =
            new WorkspaceEngineSettings(jdbc, new ObjectMapper());

    @Test
    void parsesEachChoiceIdShape()
    {
        assertThat(WorkspaceEngineSettings.parseChoice("cli:codex"))
                .contains(new WorkModel(WorkModelKind.CLI, "codex", null, null));
        assertThat(WorkspaceEngineSettings.parseChoice("api:anthropic:work"))
                .contains(new WorkModel(WorkModelKind.API, "anthropic", null, "work"));
        // No account named — the provider's ★ default credential applies.
        assertThat(WorkspaceEngineSettings.parseChoice("api:openai"))
                .contains(new WorkModel(WorkModelKind.API, "openai", null, null));
        assertThat(WorkspaceEngineSettings.parseChoice("local"))
                .contains(new WorkModel(WorkModelKind.API, "deepseek", "deepseek-v4-flash", null));
    }

    @Test
    void theTrailingSegmentCarriesReasoningEffort()
    {
        // Effort is always the fourth segment, so a CLI choice pads the model
        // slot it does not use.
        assertThat(WorkspaceEngineSettings.parseChoice("cli:claude-code::xhigh"))
                .contains(new WorkModel(
                        WorkModelKind.CLI, "claude-code", null, null, "xhigh"));
        assertThat(WorkspaceEngineSettings.parseChoice("cli:codex:gpt-5:minimal"))
                .contains(new WorkModel(
                        WorkModelKind.CLI, "codex", "gpt-5", null, "minimal"));
        assertThat(WorkspaceEngineSettings.parseChoice("api:openai:default api:high"))
                .contains(new WorkModel(
                        WorkModelKind.API, "openai", null, "default api", "high"));
        // Older three-segment values keep meaning exactly what they meant.
        assertThat(WorkspaceEngineSettings.parseChoice("api:openai:default api"))
                .contains(new WorkModel(
                        WorkModelKind.API, "openai", null, "default api", null));
    }

    @Test
    void unknownOrEmptyChoicesResolveToNothingRatherThanAGuess()
    {
        assertThat(WorkspaceEngineSettings.parseChoice(null)).isEmpty();
        assertThat(WorkspaceEngineSettings.parseChoice("")).isEmpty();
        assertThat(WorkspaceEngineSettings.parseChoice("claude-opus-4-8")).isEmpty();
        assertThat(WorkspaceEngineSettings.parseChoice("cli:")).isEmpty();
    }

    @Test
    void completeModelsKeepTheirCompactPickerIdentity()
    {
        assertThat(WorkspaceEngineSettings.pickerChoice(new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-5.3-codex", null)))
                .contains("cli:codex");
        assertThat(WorkspaceEngineSettings.pickerChoice(new WorkModel(
                WorkModelKind.API, "anthropic", "claude-opus-4-8", "work")))
                .contains("api:anthropic:work");
        assertThat(WorkspaceEngineSettings.pickerChoice(new WorkModel(
                WorkModelKind.API, "deepseek", "deepseek-v4-flash", null)))
                .contains("local");
    }

    @Test
    void aRolesOwnPickWinsOverTheWorkspaceDefault()
    {
        stubSettings("""
                {"providers": {"default": "cli:claude-code", "dev": "cli:codex"}}
                """);

        Optional<WorkspaceEngineSettings.Engine> dev = settings.forAudience("ws-1", "dev");

        assertThat(dev).isPresent();
        assertThat(dev.get().model().agentOrProvider()).isEqualTo("codex");
        assertThat(dev.get().fromRole()).isTrue();
    }

    @Test
    void aRoleWithoutItsOwnPickInheritsTheWorkspaceDefault()
    {
        stubSettings("""
                {"providers": {"default": "cli:claude-code", "dev": "cli:codex"}}
                """);

        Optional<WorkspaceEngineSettings.Engine> review = settings.forAudience("ws-1", "review");

        assertThat(review).isPresent();
        assertThat(review.get().model().agentOrProvider()).isEqualTo("claude-code");
        assertThat(review.get().fromRole()).isFalse();
    }

    @Test
    void anEmptyRoleValueMeansInherit()
    {
        stubSettings("""
                {"providers": {"default": "cli:codex", "dev": ""}}
                """);

        assertThat(settings.forAudience("ws-1", "dev"))
                .hasValueSatisfying(engine -> {
                    assertThat(engine.model().agentOrProvider()).isEqualTo("codex");
                    assertThat(engine.fromRole()).isFalse();
                });
    }

    @Test
    void noSettingsRowOrNoProvidersResolvesToNothing()
    {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());
        assertThat(settings.forAudience("ws-1", "dev")).isEmpty();

        stubSettings("{\"providers\": {}}");
        assertThat(settings.forAudience("ws-1", "dev")).isEmpty();

        // A settings row we can't parse must not fail the turn.
        stubSettings("not json");
        assertThat(settings.forAudience("ws-1", "dev")).isEmpty();
    }

    private void stubSettings(String json)
    {
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of(json));
    }
}
