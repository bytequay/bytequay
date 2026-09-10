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

import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAppSettingsService
{
    private final Map<String, String> rows = new HashMap<>();
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AppSettingsService settings = new AppSettingsService(new AppSettingsStore()
    {
        @Override
        public Optional<String> get(String key)
        {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public void put(String key, String value)
        {
            rows.put(key, value);
        }

        @Override
        public void delete(String key)
        {
            rows.remove(key);
        }
    }, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), drivers);

    @Test
    void automationDefaultsRemainUnselectedAndReadOnly()
    {
        assertThat(settings.developmentAutomation()).isEqualTo(DevelopmentAutomation.DEFAULT);
        assertThat(settings.developmentAutomation().ciLaneId()).isEmpty();
        assertThat(rows).isEmpty();
        verifyNoInteractions(drivers);
    }

    @Test
    void automationRoundTripsIndependentChoicesAndExplicitCiProvider()
    {
        AgentDriver claude = mock(AgentDriver.class);
        when(drivers.require(AgentLaneId.CLAUDE_CLI)).thenReturn(claude);
        when(claude.capabilities()).thenReturn(new AgentCapabilities(
                false, false, false, false, false, false, false, false, false, false,
                ImmutableSet.of(AgentPermissionPolicy.CI_AUTOFIX), ImmutableSet.of()));
        DevelopmentAutomation chosen = new DevelopmentAutomation(false, true, true, Optional.of(AgentLaneId.CLAUDE_CLI));

        settings.saveDevelopmentAutomation(chosen);

        assertThat(settings.developmentAutomation()).isEqualTo(chosen);
        assertThat(rows).containsKey("agent.developmentAutomation");
        settings.saveDevelopmentAutomation(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(settings.developmentAutomation()).isEqualTo(DevelopmentAutomation.LOCAL_ONLY);
    }

    @Test
    void automationRejectsUnsupportedCiAndCorruptStorageWithoutEnablingDefaults()
    {
        AgentDriver codex = mock(AgentDriver.class);
        when(drivers.require(AgentLaneId.CODEX_CLI)).thenReturn(codex);
        when(codex.capabilities()).thenReturn(AgentCapabilities.none());
        assertThatThrownBy(() -> settings.saveDevelopmentAutomation(
                new DevelopmentAutomation(true, true, true, Optional.of(AgentLaneId.CODEX_CLI))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not support CI Autofix");
        assertThat(rows).isEmpty();
        rows.put("agent.developmentAutomation", "{\"autoCommit\":true}");
        assertThatThrownBy(settings::developmentAutomation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void codeGraphModeDefaultsToOnAndRoundTrips()
    {
        assertThat(settings.codeGraphMode()).isEqualTo(CodeGraphMode.ON);

        settings.saveCodeGraph(new CodeGraphSetting(CodeGraphMode.OFF));

        assertThat(settings.codeGraphMode()).isEqualTo(CodeGraphMode.OFF);
        assertThat(rows).containsEntry("codegraph.mode", "OFF");
    }

    @Test
    void preferencesDefaultAndRoundTripAsOneRow()
    {
        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);
        assertThat(settings.taskRuntimeRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.recentRetentionDays()).isEqualTo(14);
        assertThat(settings.refreshInterval()).isEqualTo(Duration.ofMinutes(6));

        settings.savePreferences(new AppPreferences(30, 21, 15, AppTheme.LIGHT));

        assertThat(settings.taskRuntimeRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(settings.recentRetentionDays()).isEqualTo(21);
        assertThat(settings.refreshInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(rows).containsKey("app.preferences");
    }

    @Test
    void anUnreadableOrOutOfRangePreferenceRowFallsBackToTheDefault()
    {
        rows.put("app.preferences", "{\"taskRetentionDays\":0,\"recentRetentionDays\":14,\"refreshIntervalMinutes\":6,\"theme\":\"SYSTEM\"}");

        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);

        rows.put("app.preferences", "not json");

        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);
    }

    @Test
    void readsTheBooleanSettingWrittenBeforeTheModeExisted()
    {
        rows.put("codegraph.enabled", "false");

        assertThat(settings.codeGraphMode()).isEqualTo(CodeGraphMode.OFF);
    }
}
