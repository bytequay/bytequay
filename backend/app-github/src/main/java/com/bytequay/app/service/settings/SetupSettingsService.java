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

import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.WorkflowAgentTasks;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.CredentialStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class SetupSettingsService
{
    private static final String KEY = "app.setup";

    private final AppSettingsStore store;
    private final CredentialStore credentials;
    private final AppSettingsService appSettings;
    private final ModelSettingsService models;
    private final AgentDriverRegistry drivers;
    private final ObjectMapper mapper;

    public SetupSettingsService(AppSettingsStore store, CredentialStore credentials, AppSettingsService appSettings,
            ModelSettingsService models, AgentDriverRegistry drivers, ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.models = requireNonNull(models, "models is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Capture a new installation before its first GitHub sign-in can make it look like an existing one. */
    @PostConstruct
    public synchronized void initialize()
    {
        if (store.get(KEY).isEmpty()) {
            boolean existingInstall = credentials.exists();
            appSettings.initializeAutomation(existingInstall);
            write(new SetupPreferences(existingInstall ? SetupPreferences.Step.COMPLETE : SetupPreferences.Step.APPEARANCE, existingInstall));
        }
    }

    public synchronized SetupPreferences preferences()
    {
        initialize();
        try {
            return requireNonNull(mapper.readValue(store.get(KEY).orElseThrow(), SetupPreferences.class), "Saved setup preferences are null");
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Saved setup preferences could not be read", failure);
        }
    }

    public synchronized SetupPreferences save(SetupPreferences preferences)
    {
        requireNonNull(preferences, "preferences is null");
        SetupPreferences current = preferences();
        if (preferences.assistantEnabled() && !current.assistantEnabled()) {
            requireDevelopmentAgentToEnable();
        }
        write(preferences);
        return preferences;
    }

    /** Assistant and the daily agenda run outside repositories and require the global Development model. */
    public AgentModelSetting requireUsableDevelopmentAgent()
    {
        return requireDevelopmentAgent(true);
    }

    /**
     * Turning a feature on trusts the readiness Settings already shows, because a provider probe runs several CLI commands that
     * can take tens of seconds. Each run still probes before it starts.
     */
    public void requireDevelopmentAgentToEnable()
    {
        requireDevelopmentAgent(false);
    }

    private AgentModelSetting requireDevelopmentAgent(boolean probe)
    {
        AgentModelSetting model = models.agentModel(AgentModelType.DEVELOPMENT, Optional.empty());
        models.validateWorkspaceAccess(model.configId(), Optional.empty());
        models.validateSelection(model.configId(), model.model(), model.effort(), Optional.empty());
        AgentLaneId lane = new AgentLaneId(model.configId());
        drivers.checkWorkspaceAccess(lane, Optional.empty());
        if (probe || !models.lastCheckedReady(lane, AgentPermissionPolicy.AUTO)) {
            WorkflowAgentTasks.requireReady(drivers, lane, AgentPermissionPolicy.AUTO, Optional.of(model.model()));
        }
        return model;
    }

    private void write(SetupPreferences preferences)
    {
        try {
            store.put(KEY, mapper.writeValueAsString(preferences));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Setup preferences could not be encoded", failure);
        }
    }
}
