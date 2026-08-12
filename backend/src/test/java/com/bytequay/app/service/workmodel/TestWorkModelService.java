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

import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.domain.WorkModelOptions;
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class TestWorkModelService
{
    @Test
    void anUnknownCliAgentHasNoBinaryToGuessAt()
    {
        assertThat(WorkModelService.cliBinary("claude-code")).isEqualTo("claude");
        assertThat(WorkModelService.cliBinary("codex")).isEqualTo("codex");
        // Refusing beats defaulting: a caller about to fork a subprocess would
        // otherwise launch a real, wrong agent for an id this build never knew.
        assertThatThrownBy(() -> WorkModelService.cliBinary("gemini-cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown CLI agent");
    }

    @Test
    void cliAgentsAlwaysAppear()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());

        WorkModelOptions options = service(credentials).options();

        assertThat(options.cliAgents())
                .extracting(WorkModelOptions.WorkModelAgentOption::id)
                .containsExactly("claude-code", "codex");
    }

    @Test
    void apiProvidersHiddenWhenNoCredentialOnFile()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());

        WorkModelOptions options = service(credentials).options();

        // Every provider in the catalog has zero credentials → empty
        // API list. CLI agents always appear regardless because they
        // auth outside ByteQuay.
        assertThat(options.apiProviders()).isEmpty();
    }

    @Test
    void apiProviderSurfacesAllStoredAccounts()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        when(credentials.listByTypeAndName(eq(CredentialType.AI), eq("anthropic")))
                .thenReturn(ImmutableList.of(
                        credential(1, "anthropic", "personal", true, now),
                        credential(2, "anthropic", "team", false, now)));

        WorkModelOptions options = service(credentials).options();

        assertThat(options.apiProviders())
                .extracting(WorkModelOptions.WorkModelProviderOption::id)
                .containsExactly("anthropic");
        WorkModelOptions.WorkModelProviderOption anthropic = options.apiProviders().get(0);
        assertThat(anthropic.accounts())
                .extracting(WorkModelOptions.WorkModelAccount::name)
                .containsExactly("personal", "team");
        assertThat(anthropic.accounts().get(0).isDefault()).isTrue();
        assertThat(anthropic.accounts().get(1).isDefault()).isFalse();
        // Validity is unknown until the credential surface's test-probe
        // wires through; the picker renders this as a neutral chip.
        assertThat(anthropic.accounts().get(0).valid()).isNull();
    }

    @Test
    void everyAgentExposesItsCatalogDefault()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());

        WorkModelOptions options = service(credentials).options();

        for (WorkModelOptions.WorkModelAgentOption agent : options.cliAgents()) {
            assertThat(agent.defaultModel()).isNotBlank();
            assertThat(agent.models())
                    .as("agent %s lists its default in the model entries", agent.id())
                    .anySatisfy(m -> {
                        assertThat(m.id()).isEqualTo(agent.defaultModel());
                        assertThat(m.isDefault()).isTrue();
                    });
        }
    }

    @Test
    void exposesEffortChoicesForClaudeAndApiReasoningModels()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        when(credentials.listByTypeAndName(any(), any())).thenReturn(List.of());
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        when(credentials.listByTypeAndName(eq(CredentialType.AI), eq("anthropic")))
                .thenReturn(List.of(credential(
                        1, "anthropic", "personal", true, now)));
        when(credentials.listByTypeAndName(eq(CredentialType.AI), eq("openai")))
                .thenReturn(List.of(credential(
                        2, "openai", "personal", true, now)));

        WorkModelOptions options = service(credentials).options();

        assertThat(options.cliAgents().stream()
                .filter(agent -> agent.id().equals("claude-code"))
                .findFirst().orElseThrow().models().getFirst()
                .supportedReasoningEfforts())
                .extracting(WorkModelOptions.WorkModelReasoningEffort::id)
                .contains("low", "medium", "high", "max");
        assertThat(options.apiProviders().stream()
                .filter(provider -> provider.id().equals("anthropic"))
                .findFirst().orElseThrow().models().getFirst()
                .supportedReasoningEfforts()).isNotEmpty();
        assertThat(options.apiProviders().stream()
                .filter(provider -> provider.id().equals("openai"))
                .findFirst().orElseThrow().models().getFirst()
                .supportedReasoningEfforts())
                .extracting(WorkModelOptions.WorkModelReasoningEffort::id)
                .containsExactly("minimal", "low", "medium", "high");
    }

    @Test
    void effortIsValidatedAgainstTheModelNotASharedEnum()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        WorkModelService service = service(credentials);

        // Claude and Codex do not share a ladder.
        assertThat(service.resolveEffort(
                WorkModelKind.CLI, "claude-code", "claude-opus-4-8", "xhigh"))
                .isEqualTo("xhigh");
        assertThat(service.resolveEffort(
                WorkModelKind.API, "openai", "gpt-5", "minimal"))
                .isEqualTo("minimal");
        // xhigh is Claude-only, minimal is Codex-only: each falls back to the
        // other engine's default rather than being sent and rejected upstream.
        assertThat(service.resolveEffort(
                WorkModelKind.API, "openai", "gpt-5", "xhigh"))
                .isEqualTo("medium");
        assertThat(service.resolveEffort(
                WorkModelKind.CLI, "claude-code", "claude-opus-4-8", "minimal"))
                .isEqualTo("high");
        // Sonnet has no xhigh rung even though Opus does.
        assertThat(service.resolveEffort(
                WorkModelKind.API, "anthropic", "claude-sonnet-4-6", "xhigh"))
                .isEqualTo("high");
        // A model with no effort control sends none at all.
        assertThat(service.resolveEffort(
                WorkModelKind.API, "anthropic", "claude-haiku-4-5", "high"))
                .isNull();
    }

    @Test
    void codexEffortsComeFromItsLiveCatalogWhenAvailable()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        CodexModelCatalogProbe codexModels = Mockito.mock(CodexModelCatalogProbe.class);
        when(codexModels.models(false)).thenReturn(Optional.of(List.of(
                new CodexModelCatalogProbe.Model(
                        "gpt-6-preview",
                        "GPT-6 preview",
                        null,
                        true,
                        "low",
                        List.of(new CodexModelCatalogProbe.ReasoningEffort("low", null),
                                new CodexModelCatalogProbe.ReasoningEffort("ludicrous", null))))));
        WorkModelService service = new WorkModelService(credentials, codexModels);

        // The curated list never heard of this model or that rung; the live
        // catalog is what Codex will actually accept.
        assertThat(service.resolveEffort(
                WorkModelKind.CLI, "codex", "gpt-6-preview", "ludicrous"))
                .isEqualTo("ludicrous");
        assertThat(service.resolveEffort(
                WorkModelKind.CLI, "codex", "gpt-6-preview", "xhigh"))
                .isEqualTo("low");
    }

    @Test
    void freezingACodexPickerChoiceCapturesTheLiveDefaultModel()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        CodexModelCatalogProbe codexModels = Mockito.mock(CodexModelCatalogProbe.class);
        when(codexModels.models(false)).thenReturn(Optional.of(List.of(
                new CodexModelCatalogProbe.Model(
                        "gpt-live-default",
                        "GPT live default",
                        null,
                        true,
                        "medium",
                        List.of()))));

        assertThat(new WorkModelService(credentials, codexModels).freezeChoice("cli:codex"))
                .contains(new WorkModel(
                        WorkModelKind.CLI, "codex", "gpt-live-default", null));
    }

    @Test
    void freezingAnApiChoiceCapturesItsDefaultModelAndAccount()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
        when(credentials.getDefault(CredentialType.AI, "anthropic"))
                .thenReturn(Optional.of(credential(
                        1, "anthropic", "personal", true, now)));

        String defaultModel = WorkModelCatalog.provider("anthropic").defaultModel().id();
        assertThat(service(credentials).freezeChoice("api:anthropic"))
                .contains(new WorkModel(
                        WorkModelKind.API, "anthropic", defaultModel, "personal"));
    }

    @Test
    void freezingTheLocalChoiceNeedsNoCredential()
    {
        CredentialService credentials = Mockito.mock(CredentialService.class);

        assertThat(service(credentials).freezeChoice("local"))
                .contains(new WorkModel(
                        WorkModelKind.API, "deepseek", "deepseek-v4-flash", null));
    }

    private static WorkModelService service(CredentialService credentials)
    {
        CodexModelCatalogProbe codexModels = Mockito.mock(CodexModelCatalogProbe.class);
        when(codexModels.models(anyBoolean())).thenReturn(Optional.empty());
        return new WorkModelService(credentials, codexModels);
    }

    private static Credential credential(long id, String provider, String instance, boolean isDefault, Instant now)
    {
        return new Credential(
                id,
                CredentialType.AI,
                provider,
                instance,
                /* label */ provider + " " + instance,
                /* preview */ "sk-…",
                /* notes */ "",
                isDefault,
                /* configJson */ null,
                now,
                now,
                /* lastUsedAt */ null);
    }
}
