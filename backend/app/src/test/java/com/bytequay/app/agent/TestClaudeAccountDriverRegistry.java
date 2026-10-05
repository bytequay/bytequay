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

import com.bytequay.agent.cli.claude.ClaudeCliDriver;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.ClaudeAccount;
import com.bytequay.app.service.settings.ClaudeAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestClaudeAccountDriverRegistry
{
    @Test
    void isolatesAccountReadinessAndReusesOnlyTheSelectedAccountsDriver()
    {
        ClaudeAccounts accounts = new ClaudeAccounts(new InMemoryAppSettingsStore(), new ObjectMapper());
        ClaudeAccount work = accounts.add("Work", "work@example.com");
        ClaudeAccount personal = accounts.add("Personal", "personal@example.com");
        ClaudeCliDriver workDriver = mock(ClaudeCliDriver.class, withSettings().mockMaker(MockMakers.INLINE));
        ClaudeCliDriver personalDriver = mock(ClaudeCliDriver.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workDriver.probe()).thenReturn(new AgentProviderStatus(work.laneId(), AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(), "Sign in", AgentCapabilities.none()));
        when(personalDriver.probe()).thenReturn(new AgentProviderStatus(personal.laneId(), AgentProviderStatus.Readiness.READY,
                Optional.empty(), "Ready", AgentCapabilities.none()));
        AgentDriverRegistry cli = mock(AgentDriverRegistry.class);
        when(cli.statuses()).thenReturn(ImmutableList.of());
        try (ClaudeAccountDriverRegistry registry = new ClaudeAccountDriverRegistry(cli, accounts, true,
                account -> account.equals(work) ? workDriver : personalDriver)) {
            assertThat(registry.require(work.laneId())).isSameAs(workDriver);
            assertThat(registry.require(personal.laneId())).isSameAs(personalDriver);
            assertThat(registry.require(work.laneId())).isSameAs(workDriver);
            assertThat(registry.statuses()).extracting(AgentProviderStatus::readiness)
                    .containsExactly(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, AgentProviderStatus.Readiness.READY);
            assertThatThrownBy(() -> registry.require(new AgentLaneId("claude-account-unknown"))).isInstanceOf(AgentUnavailableException.class);
            registry.require(AgentLaneId.CODEX_CLI);
            verify(cli).require(AgentLaneId.CODEX_CLI);
            accounts.remove(work.id());
            assertThatThrownBy(() -> registry.require(work.laneId())).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("removed");
            assertThat(registry.statuses()).extracting(AgentProviderStatus::laneId).containsExactly(personal.laneId());
            assertThat(registry.require(personal.laneId())).isSameAs(personalDriver);
            // Existing session handles remain usable for finish/stop; removal never closes them underneath a turn.
            verify(workDriver, never()).close();
        }
        verify(workDriver).close();
        verify(personalDriver).close();
        try (ClaudeAccountDriverRegistry disabled = new ClaudeAccountDriverRegistry(cli, accounts, false, ignored -> workDriver)) {
            assertThat(disabled.statuses()).isEmpty();
            assertThatThrownBy(() -> disabled.require(work.laneId())).isInstanceOf(AgentUnavailableException.class);
        }
    }
}
