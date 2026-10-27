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

import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.ProviderQuotaSnapshot;
import com.bytequay.core.domain.ModelApiKey;
import com.bytequay.core.store.ModelApiKeyStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestProviderUsageService
{
    @Test
    void isolatesAccountCachesAndDoesNotServeRemovedAccounts()
    {
        AgentDriverRegistry registry = mock(AgentDriverRegistry.class);
        ClaudeAccounts accounts = mock(ClaudeAccounts.class);
        ClaudeAccount work = new ClaudeAccount("11111111-1111-4111-8111-111111111111", "Work", Optional.empty(), Optional.empty(), Optional.empty());
        when(accounts.list()).thenReturn(ImmutableList.of(work));
        AgentDriver codex = mock(AgentDriver.class);
        AgentDriver claude = mock(AgentDriver.class);
        when(codex.id()).thenReturn(AgentLaneId.CODEX_CLI);
        when(claude.id()).thenReturn(work.laneId());
        when(registry.require(AgentLaneId.CODEX_CLI)).thenReturn(codex);
        when(registry.require(work.laneId())).thenReturn(claude);
        when(codex.quota()).thenReturn(new ProviderQuotaSnapshot(Optional.of("pro"), ImmutableList.of(), Optional.empty()));
        when(claude.quota()).thenReturn(new ProviderQuotaSnapshot(Optional.of("enterprise"), ImmutableList.of(), Optional.empty()));
        ProviderUsageService service = new ProviderUsageService(registry, accounts, mock(ModelApiKeyStore.class), Clock.systemUTC());
        assertThat(service.usage("codex").plan()).contains("pro");
        assertThat(service.usage(work.laneId().value()).plan()).contains("enterprise");
        service.usage("codex");
        verify(codex, times(1)).quota();
        when(accounts.list()).thenReturn(ImmutableList.of());
        assertThatThrownBy(() -> service.usage(work.laneId().value())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failuresAreCachedAndRawDiagnosticsDoNotReachTheUi()
    {
        AgentDriverRegistry registry = mock(AgentDriverRegistry.class);
        ClaudeAccounts accounts = mock(ClaudeAccounts.class);
        when(accounts.list()).thenReturn(ImmutableList.of());
        AgentDriver driver = mock(AgentDriver.class);
        when(driver.id()).thenReturn(AgentLaneId.CODEX_CLI);
        when(registry.require(AgentLaneId.CODEX_CLI)).thenReturn(driver);
        when(driver.quota()).thenThrow(new IllegalStateException("sensitive provider diagnostic"));
        ProviderUsageService service = new ProviderUsageService(registry, accounts, mock(ModelApiKeyStore.class), Clock.systemUTC());
        assertThat(service.usage("codex").message().orElseThrow()).doesNotContain("sensitive");
        assertThat(service.usage("codex").windows()).isEmpty();
        verify(driver, times(1)).quota();
    }

    @Test
    void routesDeepSeekBalanceToItsOwnKeyLaneAndCachesByAccount()
    {
        AgentDriverRegistry registry = mock(AgentDriverRegistry.class);
        ModelApiKeyStore keys = mock(ModelApiKeyStore.class);
        ModelApiKey key = new ModelApiKey("11111111-1111-4111-8111-111111111111", "ds-001", "DeepSeek", "masked", Instant.now());
        when(keys.findAll()).thenReturn(ImmutableList.of(key));
        when(keys.findById(key.id())).thenReturn(Optional.of(key));
        AgentLaneId lane = new AgentLaneId("deepseek-key-" + key.id());
        AgentDriver driver = mock(AgentDriver.class);
        when(registry.require(lane)).thenReturn(driver);
        when(driver.quota()).thenReturn(new ProviderQuotaSnapshot(Optional.empty(), ImmutableList.of(), Optional.of("Available balance: USD 12.34.")));
        ProviderUsageService service = new ProviderUsageService(registry, mock(ClaudeAccounts.class), keys, Clock.systemUTC());
        String accountId = "api-key-" + key.id();
        var usage = service.usage(accountId);
        assertThat(usage.accountId()).isEqualTo(accountId);
        assertThat(usage.message()).contains("Available balance: USD 12.34.");
        assertThat(usage.windows()).isEmpty();
        assertThat(service.usage(accountId)).isSameAs(usage);
        verify(driver, times(1)).quota();
        verify(registry, never()).require(AgentLaneId.CODEX_CLI);
        when(keys.findAll()).thenReturn(ImmutableList.of());
        assertThatThrownBy(() -> service.usage(accountId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsApiAliasesWithoutReadingSecretsAndLeavesUnsupportedKeysUnavailable()
    {
        AgentDriverRegistry registry = mock(AgentDriverRegistry.class);
        ClaudeAccounts accounts = mock(ClaudeAccounts.class);
        ModelApiKeyStore keys = mock(ModelApiKeyStore.class);
        when(accounts.list()).thenReturn(ImmutableList.of());
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        ModelApiKey deepSeek = new ModelApiKey("11111111-1111-4111-8111-111111111111", "ds-001", "DeepSeek", "masked hint", created);
        ModelApiKey openAi = new ModelApiKey("22222222-2222-4222-8222-222222222222", "Work.OpenAI_2", "OpenAI", "masked hint", created);
        when(keys.findAll()).thenReturn(ImmutableList.of(deepSeek, openAi));
        when(keys.findById(openAi.id())).thenReturn(Optional.of(openAi));
        ProviderUsageService service = new ProviderUsageService(registry, accounts, keys, Clock.systemUTC());
        assertThat(service.accounts()).containsExactly(
                new ProviderUsageService.Account("codex", "Codex"),
                new ProviderUsageService.Account("claude", "Claude"),
                new ProviderUsageService.Account("api-key-" + deepSeek.id(), "DeepSeek · ds-001"),
                new ProviderUsageService.Account("api-key-" + openAi.id(), "OpenAI · Work.OpenAI_2"));
        var usage = service.usage("api-key-" + openAi.id());
        assertThat(usage.accountId()).isEqualTo("api-key-" + openAi.id());
        assertThat(usage.windows()).isEmpty();
        assertThat(usage.message().orElseThrow()).contains("API-key account");
        verify(keys, never()).findSecretById(anyString());
        verifyNoInteractions(registry);

        ModelApiKey replacement = new ModelApiKey("33333333-3333-4333-8333-333333333333", "ds-001", "DeepSeek", "new hint", created);
        when(keys.findAll()).thenReturn(ImmutableList.of(replacement));
        assertThatThrownBy(() -> service.usage("api-key-" + deepSeek.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.usage("api-key-" + replacement.id()).accountId()).isEqualTo("api-key-" + replacement.id());
    }
}
