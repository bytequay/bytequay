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
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.store.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSetupSettingsService
{
    private static final AgentLaneId LANE = new AgentLaneId("claude");

    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final CredentialStore credentials = mock(CredentialStore.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
    private final AppSettingsService appSettings = new AppSettingsService(store, mapper, Clock.systemUTC(), drivers);
    private final SetupSettingsService setup = new SetupSettingsService(store, credentials, appSettings, models, drivers, mapper);

    @Test
    void newInstallResumesAfterSigningInAndNeverRestartsAfterCompletion()
    {
        setup.initialize();
        assertThat(setup.preferences()).isEqualTo(new SetupPreferences(SetupPreferences.Step.APPEARANCE, false));
        assertThat(appSettings.developmentAutomation()).isEqualTo(DevelopmentAutomation.DEFAULT);

        setup.save(new SetupPreferences(SetupPreferences.Step.GITHUB, false));
        when(credentials.exists()).thenReturn(true);
        SetupSettingsService restarted = new SetupSettingsService(store, credentials, appSettings, models, drivers, mapper);
        restarted.initialize();
        assertThat(restarted.preferences()).isEqualTo(new SetupPreferences(SetupPreferences.Step.GITHUB, false));

        restarted.save(new SetupPreferences(SetupPreferences.Step.COMPLETE, false));
        when(credentials.exists()).thenReturn(false);
        assertThat(new SetupSettingsService(store, credentials, appSettings, models, drivers, mapper).preferences())
                .isEqualTo(new SetupPreferences(SetupPreferences.Step.COMPLETE, false));
    }

    @Test
    void existingInstallKeepsAssistantAndPreviousAutomationDefaults()
    {
        when(credentials.exists()).thenReturn(true);
        setup.initialize();

        assertThat(setup.preferences()).isEqualTo(new SetupPreferences(SetupPreferences.Step.COMPLETE, true));
        assertThat(appSettings.developmentAutomation()).isEqualTo(new DevelopmentAutomation(true, true, true, Optional.empty()));
    }

    @Test
    void initializationDoesNotReplaceSavedAutomationChoices()
    {
        DevelopmentAutomation saved = new DevelopmentAutomation(true, false, true, Optional.empty(), true, true);
        appSettings.saveDevelopmentAutomation(saved);
        when(credentials.exists()).thenReturn(true);

        setup.initialize();

        assertThat(appSettings.developmentAutomation()).isEqualTo(saved);
    }

    @Test
    void assistantEnablementRequiresAnAvailableGlobalModelWithAutoCapability()
    {
        readyAgent();
        SetupPreferences enabled = new SetupPreferences(SetupPreferences.Step.FEATURES, true);
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED,
                Optional.empty(), "Sign in first", AgentCapabilities.none()));
        assertThatThrownBy(() -> setup.save(enabled)).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("Sign in first");
        assertThat(setup.preferences().assistantEnabled()).isFalse();

        when(driver.capabilities()).thenReturn(new AgentCapabilities(false, false, false, ImmutableSet.of(), ImmutableSet.of()));
        assertThatThrownBy(() -> setup.save(enabled)).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("AUTO");

        readyAgent();
        assertThat(setup.save(enabled)).isEqualTo(enabled);
        verify(models, atLeastOnce()).validateWorkspaceAccess("claude", Optional.empty());
    }

    @Test
    void repositoryOnlyAccountCannotEnableGlobalFeatures()
    {
        readyAgent();
        doThrow(new AgentUnavailableException("Account belongs to a repository")).when(models).validateWorkspaceAccess("claude", Optional.empty());

        assertThatThrownBy(() -> setup.save(new SetupPreferences(SetupPreferences.Step.FEATURES, true)))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("repository");
        assertThat(setup.preferences().assistantEnabled()).isFalse();
    }

    @Test
    void losingTheProviderDoesNotResetConsentOrBlockDisablingAssistant()
    {
        readyAgent();
        setup.save(new SetupPreferences(SetupPreferences.Step.FEATURES, true));
        when(drivers.require(LANE)).thenThrow(new AgentUnavailableException("Agent removed"));

        assertThat(setup.save(new SetupPreferences(SetupPreferences.Step.COMPLETE, true)).assistantEnabled()).isTrue();
        assertThat(setup.save(new SetupPreferences(SetupPreferences.Step.COMPLETE, false)).assistantEnabled()).isFalse();
    }

    private void readyAgent()
    {
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.empty()))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude", "sonnet", "Sonnet", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
    }
}
