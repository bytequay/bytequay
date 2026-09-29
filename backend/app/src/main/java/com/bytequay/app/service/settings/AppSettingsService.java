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
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Typed facade over the durable key-value settings for app-wide preferences. */
@Service
public class AppSettingsService
{
    private static final String QUIET_HOURS_KEY = "github.quietHours";
    private static final String CODEGRAPH_KEY = "codegraph.mode";
    private static final String PREFERENCES_KEY = "app.preferences";
    private static final String DEVELOPMENT_AUTOMATION_KEY = "agent.developmentAutomation";

    private final AppSettingsStore store;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AgentDriverRegistry drivers;

    public AppSettingsService(AppSettingsStore store, ObjectMapper mapper, Clock clock, AgentDriverRegistry drivers)
    {
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
    }

    public DevelopmentAutomation developmentAutomation()
    {
        return store.get(DEVELOPMENT_AUTOMATION_KEY).map(value -> {
            try {
                // Settings saved before the review summary option existed read as off; every other field stays required.
                JsonNode saved = mapper.readTree(value);
                if (saved instanceof ObjectNode object) {
                    object.putIfAbsent("reviewSummary", BooleanNode.FALSE);
                }
                return requireNonNull(mapper.readerFor(DevelopmentAutomation.class)
                        .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .treeToValue(saved, DevelopmentAutomation.class), "Saved development automation is null");
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Saved development automation could not be read", failure);
            }
        }).orElse(DevelopmentAutomation.DEFAULT).forDevelopment();
    }

    public DevelopmentAutomation saveDevelopmentAutomation(DevelopmentAutomation automation)
    {
        automation = automation.forDevelopment();
        validateDevelopmentAutomation(automation);
        try {
            store.put(DEVELOPMENT_AUTOMATION_KEY, mapper.writeValueAsString(automation));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Development automation could not be encoded", failure);
        }
        return automation;
    }

    /** An absent CI lane inherits the development task's lane. Readiness is checked when CI launches. */
    public void validateDevelopmentAutomation(DevelopmentAutomation automation)
    {
        requireNonNull(automation, "automation is null");
        automation.ciLaneId().ifPresent(lane -> checkArgument(
                drivers.require(lane).capabilities().permissionPolicies().contains(AgentPermissionPolicy.CI_AUTOFIX),
                "Agent lane %s does not support CI Autofix", lane));
    }

    public QuietHours quietHours()
    {
        return store.get(QUIET_HOURS_KEY)
                .map(this::readQuietHours)
                .orElse(QuietHours.DEFAULT);
    }

    public QuietHours saveQuietHours(QuietHours quietHours)
    {
        requireNonNull(quietHours, "quietHours is null");
        try {
            store.put(QUIET_HOURS_KEY, mapper.writeValueAsString(quietHours));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("quiet hours could not be encoded", failure);
        }
        return quietHours;
    }

    /** How new agent tasks get a code graph; on by default so the vendored helper is used when present. */
    public CodeGraphMode codeGraphMode()
    {
        return store.get(CODEGRAPH_KEY)
                .map(CodeGraphMode::valueOf)
                .orElse(CodeGraphMode.ON);
    }

    public CodeGraphSetting saveCodeGraph(CodeGraphSetting setting)
    {
        requireNonNull(setting, "setting is null");
        store.put(CODEGRAPH_KEY, setting.mode().name());
        return setting;
    }

    /** True while background GitHub and Jira refresh should stay idle, in local time. */
    public boolean inQuietHours()
    {
        return quietHours().quietAt(clock.instant().atZone(ZoneId.systemDefault()).toLocalTime());
    }

    public AppPreferences preferences()
    {
        return store.get(PREFERENCES_KEY)
                .map(this::readPreferences)
                .orElse(AppPreferences.DEFAULT);
    }

    public AppPreferences savePreferences(AppPreferences preferences)
    {
        requireNonNull(preferences, "preferences is null");
        try {
            store.put(PREFERENCES_KEY, mapper.writeValueAsString(preferences));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("preferences could not be encoded", failure);
        }
        return preferences;
    }

    /** How long a finished task's detailed runtime survives before cleanup deletes it. */
    public Duration taskRuntimeRetention()
    {
        return Duration.ofDays(preferences().taskRetentionDays());
    }

    /** How far back the local Recent record reaches. */
    public int recentRetentionDays()
    {
        return preferences().recentRetentionDays();
    }

    private AppPreferences readPreferences(String value)
    {
        try {
            JsonNode saved = mapper.readTree(value);
            if (saved instanceof ObjectNode object) {
                object.remove("refreshIntervalMinutes");
                object.putIfAbsent("prWarmCount", mapper.getNodeFactory().numberNode(30));
            }
            return mapper.treeToValue(saved, AppPreferences.class);
        }
        catch (JsonProcessingException | IllegalArgumentException corrupt) {
            return AppPreferences.DEFAULT;
        }
    }

    private QuietHours readQuietHours(String value)
    {
        try {
            return mapper.readValue(value, QuietHours.class);
        }
        catch (JsonProcessingException corrupt) {
            return QuietHours.DEFAULT;
        }
    }
}
