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
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderStatus.Health;
import com.bytequay.app.service.InMemoryAppSettingsStore;
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
    private ModelSettingsService service;

    @BeforeEach
    void setUp()
    {
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                new AgentProviderStatus(
                        new AgentLaneId("claude"), Health.AVAILABLE,
                        Optional.of("2.1.4"), "ready", AgentCapabilities.none()),
                new AgentProviderStatus(
                        new AgentLaneId("codex"), Health.UNAVAILABLE,
                        Optional.empty(), "codex CLI not found", AgentCapabilities.none())));
        service = new ModelSettingsService(
                drivers,
                new InMemoryModelApiKeyStore(),
                new InMemoryAppSettingsStore(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void defaultsToClaudeMainWithCatalogDefaults()
    {
        ModelSettings settings = service.get();
        assertThat(settings.mainId()).isEqualTo("claude");
        var claude = settings.providers().getFirst();
        assertThat(claude.selectedModel()).isEqualTo("Opus 5");
        assertThat(claude.selectedEffort()).isEqualTo("High");
        assertThat(claude.main()).isTrue();
        assertThat(settings.providers().get(1).health()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void selectionIsValidatedAgainstTheCatalog()
    {
        service.select("claude", "Sonnet 5", Optional.of("Max"));
        var claude = service.get().providers().getFirst();
        assertThat(claude.selectedModel()).isEqualTo("Sonnet 5");
        assertThat(claude.selectedEffort()).isEqualTo("Max");

        assertThatThrownBy(() -> service.select("claude", "gpt-5.2", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.select("claude", "Opus 5", Optional.of("Turbo")))
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
        service.setWorkspaceOverride("ws-1", "codex", "5.6 Sol", Optional.of("Extra High"));
        var override = service.workspaceOverride("ws-1").orElseThrow();
        assertThat(override.model()).isEqualTo("5.6 Sol");
        assertThat(override.effort()).contains("Extra High");

        assertThatThrownBy(() -> service.setWorkspaceOverride("ws-1", "codex", "Opus 5", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);

        service.clearWorkspaceOverride("ws-1");
        assertThat(service.workspaceOverride("ws-1")).isEmpty();
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
