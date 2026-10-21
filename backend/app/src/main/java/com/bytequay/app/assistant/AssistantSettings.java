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
package com.bytequay.app.assistant;

import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** The assistant's switches in Settings → Agents → Features. */
@Component
public class AssistantSettings
{
    private static final Logger log = LoggerFactory.getLogger(AssistantSettings.class);
    private static final String KEY = "assistant.permissions";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public AssistantSettings(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public AssistantPermissions permissions()
    {
        return settings.get(KEY).map(value -> {
            try {
                return mapper.readValue(value, AssistantPermissions.class);
            }
            catch (JsonProcessingException unreadable) {
                // Unreadable switches read as off, which is the safe way for them to fail.
                log.warn("Ignoring unreadable assistant permissions: {}", unreadable.getMessage());
                return AssistantPermissions.DEFAULT;
            }
        }).orElse(AssistantPermissions.DEFAULT);
    }

    public AssistantPermissions save(AssistantPermissions permissions)
    {
        requireNonNull(permissions, "permissions is null");
        try {
            settings.put(KEY, mapper.writeValueAsString(permissions));
        }
        catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not save the assistant permissions", impossible);
        }
        return permissions;
    }
}
