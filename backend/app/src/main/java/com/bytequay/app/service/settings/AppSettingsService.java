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

import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;

import static java.util.Objects.requireNonNull;

/** Typed facade over the durable key-value settings for app-wide preferences. */
@Service
public class AppSettingsService
{
    private static final String QUIET_HOURS_KEY = "github.quietHours";
    private static final String CODEGRAPH_KEY = "codegraph.enabled";

    private final AppSettingsStore store;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AppSettingsService(AppSettingsStore store, ObjectMapper mapper, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
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

    /** Whether agent tasks get a code graph; on by default so the vendored helper is used when present. */
    public boolean codeGraphEnabled()
    {
        return store.get(CODEGRAPH_KEY).map(Boolean::parseBoolean).orElse(true);
    }

    public CodeGraphSetting saveCodeGraph(CodeGraphSetting setting)
    {
        requireNonNull(setting, "setting is null");
        store.put(CODEGRAPH_KEY, Boolean.toString(setting.enabled()));
        return setting;
    }

    /** True while scheduled GitHub refresh jobs should stay idle, in local time. */
    public boolean inQuietHours()
    {
        return quietHours().quietAt(clock.instant().atZone(ZoneId.systemDefault()).toLocalTime());
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
