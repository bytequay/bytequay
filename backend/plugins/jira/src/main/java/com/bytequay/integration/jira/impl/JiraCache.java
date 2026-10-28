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
package com.bytequay.integration.jira.impl;

import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class JiraCache
{
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public JiraCache(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public <T> Optional<T> read(String key, Class<T> type)
    {
        return settings.get("jira." + key).map(value -> decode(value, type));
    }

    public void write(String key, Object value)
    {
        settings.put("jira." + key, encode(value));
    }

    public List<String> keys()
    {
        return settings.keys("jira.").stream().map(key -> key.substring("jira.".length())).collect(toImmutableList());
    }

    public void delete(String key)
    {
        settings.delete("jira." + key);
    }

    public String encode(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to encode Jira data", e);
        }
    }

    public <T> T decode(String value, Class<T> type)
    {
        try {
            return mapper.readValue(value, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read saved Jira data", e);
        }
    }
}
