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
package com.bytequay.app.agenda;

import com.bytequay.app.service.settings.SetupSettingsService;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

@Component
public class AgendaSettings
{
    private static final Logger log = LoggerFactory.getLogger(AgendaSettings.class);
    private static final String KEY = "agenda.schedule";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final SetupSettingsService setup;

    public AgendaSettings(AppSettingsStore settings, ObjectMapper mapper, SetupSettingsService setup)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.setup = requireNonNull(setup, "setup is null");
    }

    public AgendaSchedule schedule()
    {
        return settings.get(KEY).map(value -> {
            try {
                return mapper.readValue(value, AgendaSchedule.class);
            }
            catch (JsonProcessingException | IllegalArgumentException unreadable) {
                log.warn("Ignoring an unreadable agenda schedule: {}", unreadable.getMessage());
                return AgendaSchedule.DEFAULT;
            }
        }).orElse(AgendaSchedule.DEFAULT);
    }

    public AgendaSchedule save(AgendaSchedule schedule)
    {
        requireNonNull(schedule, "schedule is null");
        if (schedule.enabled() && !schedule().enabled()) {
            setup.requireDevelopmentAgentToEnable();
        }
        try {
            settings.put(KEY, mapper.writeValueAsString(schedule));
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the agenda schedule", impossible);
        }
        return schedule;
    }
}
