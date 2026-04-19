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
package com.bytequay.app.repository.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Map;

/**
 * JPA attribute converter that serializes {@code Map<String, String>} to a
 * JSON text column. Used by columns like {@code label_colors} that store a
 * small sidecar of name → value mappings.
 */
@Converter
class StringMapConverter
        implements AttributeConverter<Map<String, String>, String>
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, String> map)
    {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(map);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize string map", e);
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String json)
    {
        if (json == null || json.isBlank()) {
            return ImmutableMap.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize string map", e);
        }
    }
}
