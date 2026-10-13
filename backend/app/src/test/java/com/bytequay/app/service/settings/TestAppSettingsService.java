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
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAppSettingsService
{
    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AppSettingsService settings = new AppSettingsService(store, new ObjectMapper().findAndRegisterModules(), Clock.systemUTC(), drivers);

    @Test
    void automationDefaultsInheritTheDevelopmentAgentWithoutWritingSettings()
    {
        assertThat(settings.developmentAutomation()).isEqualTo(DevelopmentAutomation.DEFAULT);
        assertThat(settings.developmentAutomation().ciLaneId()).isEmpty();
        assertThat(store.snapshot()).isEmpty();
        verifyNoInteractions(drivers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude", "codex"})
    void automationRoundTripsIndependentChoicesAndExplicitCiRepairAgent(String laneId)
    {
        AgentLaneId lane = new AgentLaneId(laneId);
        AgentDriver driver = mock(AgentDriver.class);
        when(drivers.require(lane)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(new AgentCapabilities(false, false, false, ImmutableSet.of(AgentPermissionPolicy.CI_AUTOFIX), ImmutableSet.of()));
        DevelopmentAutomation chosen = new DevelopmentAutomation(false, true, true, Optional.of(lane), true);

        settings.saveDevelopmentAutomation(chosen);

        assertThat(settings.developmentAutomation()).isEqualTo(chosen.forDevelopment());
        assertThat(settings.developmentAutomation().reviewSummary()).isTrue();
        assertThat(store.snapshot()).containsKey("agent.developmentAutomation");
        settings.saveDevelopmentAutomation(DevelopmentAutomation.LOCAL_ONLY);
        assertThat(settings.developmentAutomation()).isEqualTo(DevelopmentAutomation.LOCAL_ONLY.forDevelopment());
    }

    @Test
    void savedAutoCommitOptOutCannotDisableDevelopmentCommits()
    {
        store.put("agent.developmentAutomation", "{\"autoCommit\":false,\"autoDraftPr\":false,\"autoCiFix\":false,\"ciLaneId\":null,\"reviewSummary\":false}");
        assertThat(settings.developmentAutomation()).isEqualTo(DevelopmentAutomation.LOCAL_ONLY.forDevelopment());
    }

    @Test
    void automationRejectsUnsupportedCiAndCorruptStorageWithoutEnablingDefaults()
    {
        AgentLaneId lane = new AgentLaneId("unsupported");
        AgentDriver driver = mock(AgentDriver.class);
        when(drivers.require(lane)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        assertThatThrownBy(() -> settings.saveDevelopmentAutomation(new DevelopmentAutomation(true, true, true, Optional.of(lane))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not support CI Autofix");
        assertThat(store.snapshot()).isEmpty();
        store.put("agent.developmentAutomation", "{\"autoCommit\":true}");
        assertThatThrownBy(settings::developmentAutomation).isInstanceOf(IllegalStateException.class);
        store.put("agent.developmentAutomation", "{\"autoCommit\":true,\"autoDraftPr\":true,\"autoCiFix\":true,\"ciLaneId\":null}");
        assertThat(settings.developmentAutomation()).isEqualTo(new DevelopmentAutomation(true, true, true, Optional.empty(), false));
    }

    @Test
    void preferencesDefaultAndRoundTripAsOneRow()
    {
        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);
        assertThat(settings.taskRuntimeRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.recentRetentionDays()).isEqualTo(14);

        settings.savePreferences(new AppPreferences(30, 21, AppTheme.LIGHT, 30));

        assertThat(settings.taskRuntimeRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(settings.recentRetentionDays()).isEqualTo(21);
        assertThat(store.snapshot()).containsKey("app.preferences");
        assertThat(store.get("app.preferences").orElseThrow()).doesNotContain("refreshIntervalMinutes");
    }

    @Test
    void oldPreferencesKeepTheirValuesAndDefaultToThirtyWarmPullRequests()
    {
        store.put("app.preferences", "{\"taskRetentionDays\":30,\"recentRetentionDays\":21,\"refreshIntervalMinutes\":15,\"theme\":\"LIGHT\"}");
        assertThat(settings.preferences()).isEqualTo(new AppPreferences(30, 21, AppTheme.LIGHT, 30));
    }

    @Test
    void smallerWarmCountRoundTripsAndInvalidCountsAreRejected()
    {
        AppPreferences fewer = new AppPreferences(7, 14, AppTheme.SYSTEM, 10);
        settings.savePreferences(fewer);
        assertThat(settings.preferences()).isEqualTo(fewer);
        assertThatThrownBy(() -> new AppPreferences(7, 14, AppTheme.SYSTEM, 11))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prWarmCount");
    }

    @Test
    void anUnreadableOrOutOfRangePreferenceRowFallsBackToTheDefault()
    {
        store.put("app.preferences", "{\"taskRetentionDays\":0,\"recentRetentionDays\":14,\"refreshIntervalMinutes\":6,\"theme\":\"SYSTEM\"}");

        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);

        store.put("app.preferences", "not json");

        assertThat(settings.preferences()).isEqualTo(AppPreferences.DEFAULT);
    }
}
