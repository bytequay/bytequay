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
package com.bytequay.app.service.settings;

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderStatus.Readiness;
import com.bytequay.agent.spi.ProviderModel;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.ModelSettingsService.ModelOption;
import com.bytequay.app.service.settings.ModelSettingsService.ModelSettings;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.ModelApiKeyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestModelSettingsService
{
    private static final AgentLaneId CODEX = new AgentLaneId("codex");

    private AgentDriverRegistry drivers;
    private AgentDriver codexDriver;
    private ModelSettingsService service;

    @BeforeEach
    void setUp()
    {
        drivers = mock(AgentDriverRegistry.class);
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                new AgentProviderStatus(
                        new AgentLaneId("claude"), Readiness.READY,
                        Optional.of("2.1.4"), "ready", AgentCapabilities.none()),
                new AgentProviderStatus(
                        CODEX, Readiness.READY,
                        Optional.of("0.145.0"), "ready", AgentCapabilities.none())));
        // The claude driver cannot enumerate models; the mock's empty default
        // list stands in for that. Codex reports a live catalog.
        when(drivers.require(new AgentLaneId("claude"))).thenReturn(mock(AgentDriver.class));
        codexDriver = mock(AgentDriver.class);
        when(codexDriver.models()).thenReturn(ImmutableList.of(
                new ProviderModel(
                        "gpt-5.6-sol", "GPT-5.6-Sol", "Latest frontier agentic coding model.",
                        ImmutableList.of("low", "medium", "high", "xhigh", "max", "ultra"),
                        Optional.of("low"), true),
                new ProviderModel(
                        "gpt-5.6-luna", "GPT-5.6-Luna", "Fast and affordable agentic coding model.",
                        ImmutableList.of("low", "medium", "high", "xhigh", "max"),
                        Optional.of("medium"), false)));
        when(drivers.require(CODEX)).thenReturn(codexDriver);
        service = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                new InMemoryAppSettingsStore(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        service.seedCatalogs();
        service.refreshCatalogs();
    }

    @Test
    void defaultsToClaudeMainWithSeededAndProbedCatalogs()
    {
        ModelSettings settings = service.get();
        assertThat(settings.mainId()).isEqualTo("claude");
        var claude = settings.providers().getFirst();
        assertThat(claude.readiness()).isEqualTo(Readiness.READY);
        assertThat(claude.selectedModel()).isEqualTo("opus");
        assertThat(claude.selectedEffort()).isEqualTo("High");
        assertThat(claude.main()).isTrue();
        var codex = settings.providers().get(1);
        assertThat(codex.readiness()).isEqualTo(Readiness.READY);
        assertThat(codex.models()).extracting(ModelOption::id)
                .containsExactly("gpt-5.6-sol", "gpt-5.6-luna");
        assertThat(codex.selectedModel()).isEqualTo("gpt-5.6-sol");
        assertThat(codex.selectedEffort()).isEqualTo("low");
    }

    @Test
    void selectionIsValidatedAgainstTheCatalog()
    {
        service.select("claude", "sonnet", Optional.of("Max"));
        var claude = service.get().providers().getFirst();
        assertThat(claude.selectedModel()).isEqualTo("sonnet");
        assertThat(claude.selectedEffort()).isEqualTo("Max");

        assertThatThrownBy(() -> service.select("claude", "gpt-5.2", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.select("claude", "opus", Optional.of("Turbo")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.select("codex", "gpt-5.6-luna", Optional.of("ultra")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiKeysCanBecomeMainAndFallBackWhenRemoved()
    {
        service.addApiKey("DeepSeek", "personal-deepseek", "sk-4fabcdefghijklmnope2a1");
        service.setMain("key:personal-deepseek");
        ModelSettings settings = service.get();
        assertThat(settings.mainId()).isEqualTo("key:personal-deepseek");
        var key = settings.apiKeys().getFirst();
        assertThat(key.maskedKey()).doesNotContain("abcdefgh").contains("••••");
        assertThat(key.selectedModel()).isEqualTo("deepseek-chat");
        assertThat(key.main()).isTrue();

        assertThat(service.removeApiKey("personal-deepseek").mainId()).isEqualTo("claude");
    }

    @Test
    void unknownProvidersAndDuplicateAliasesAreRejected()
    {
        service.addApiKey("OpenAI", "team", "sk-secret");
        assertThatThrownBy(() -> service.addApiKey("OpenAI", "team", "sk-other"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addApiKey("Acme", "acme", "sk-secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setMain("key:missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceOverrideRoundTripsAndValidates()
    {
        assertThat(service.workspaceOverride("ws-1")).isEmpty();
        service.setWorkspaceOverride("ws-1", "codex", "gpt-5.6-sol", Optional.of("xhigh"));
        var override = service.workspaceOverride("ws-1").orElseThrow();
        assertThat(override.model()).isEqualTo("gpt-5.6-sol");
        assertThat(override.modelLabel()).isEqualTo("GPT-5.6-Sol");
        assertThat(override.effort()).contains("xhigh");

        assertThatThrownBy(() -> service.setWorkspaceOverride("ws-1", "codex", "opus", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        service.clearWorkspaceOverride("ws-1");
        assertThat(service.workspaceOverride("ws-1")).isEmpty();
    }

    @Test
    void refreshRewritesTheStoredCatalogOnEveryRun()
    {
        when(codexDriver.models()).thenReturn(ImmutableList.of(
                new ProviderModel(
                        "gpt-6-sol", "GPT-6-Sol", "Next frontier model.",
                        ImmutableList.of("low", "high"), Optional.of("low"), true)));
        service.refreshCatalogs();

        var codex = service.get().providers().get(1);
        assertThat(codex.models()).extracting(ModelOption::id).containsExactly("gpt-6-sol");
        assertThat(codex.selectedModel()).isEqualTo("gpt-6-sol");
        assertThat(codex.selectedEffort()).isEqualTo("low");
    }

    @Test
    void probeFailureKeepsTheStoredCatalog()
    {
        when(drivers.require(CODEX)).thenThrow(new IllegalStateException("codex is gone"));
        service.refreshCatalogs();

        var codex = service.get().providers().get(1);
        assertThat(codex.selectedModel()).isEqualTo("gpt-5.6-sol");
    }

    @Test
    void newerSeedVersionReplacesTheStoredClaudeCatalog()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        store.put(
                "model.catalog.claude",
                "{\"version\":\"seed-1\",\"defaultModelId\":\"opus\",\"models\":"
                        + "[{\"id\":\"opus\",\"name\":\"Opus 5\",\"description\":\"\","
                        + "\"efforts\":[\"High\"],\"defaultEffort\":\"High\"}]}");
        ModelSettingsService upgraded = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                store,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        upgraded.seedCatalogs();

        var claude = upgraded.get().providers().getFirst();
        assertThat(claude.models()).extracting(ModelOption::name)
                .containsExactly("Fable", "Opus", "Sonnet", "Haiku");
    }

    @Test
    void catalogsComeOnlyFromTheStore()
    {
        ModelSettingsService unseeded = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                new InMemoryAppSettingsStore(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
        var claude = unseeded.get().providers().getFirst();
        assertThat(claude.models()).isEmpty();
        assertThat(claude.selectedModel()).isEmpty();
    }

    private static final class InMemoryModelApiKeyStore
            implements ModelApiKeyStore
    {
        private final List<ModelApiKey> keys = new ArrayList<>();
        private final List<String> secrets = new ArrayList<>();

        @Override
        public List<ModelApiKey> findAll()
        {
            return ImmutableList.copyOf(keys);
        }

        @Override
        public Optional<String> findSecret(String alias)
        {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).alias().equals(alias)) {
                    return Optional.of(secrets.get(i));
                }
            }
            return Optional.empty();
        }

        @Override
        public void save(ModelApiKey key, String secret)
        {
            keys.add(key);
            secrets.add(secret);
        }

        @Override
        public void delete(String alias)
        {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).alias().equals(alias)) {
                    keys.remove(i);
                    secrets.remove(i);
                    return;
                }
            }
        }
    }
}
