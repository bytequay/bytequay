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
import jakarta.persistence.AttributeConverter;

/**
 * Base for the JPA attribute converters that persist a small collection as a
 * JSON text column. Every converter carried the same null/empty guards and
 * try/catch around {@link ObjectMapper}; subclasses now only declare the
 * element type, the human-readable label for error messages, and the values
 * used when the column or the value is empty.
 *
 * @param <T> the attribute type (a {@code List} or {@code Map})
 */
abstract class JsonColumnConverter<T>
        implements AttributeConverter<T, String>
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeReference<T> type;
    private final String label;
    private final String emptyColumn;
    private final T emptyValue;

    JsonColumnConverter(TypeReference<T> type, String label, String emptyColumn, T emptyValue)
    {
        this.type = type;
        this.label = label;
        this.emptyColumn = emptyColumn;
        this.emptyValue = emptyValue;
    }

    /** True when {@code value} carries nothing worth persisting. */
    protected abstract boolean isEmpty(T value);

    @Override
    public String convertToDatabaseColumn(T value)
    {
        if (value == null || isEmpty(value)) {
            return emptyColumn;
        }
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize " + label, e);
        }
    }

    @Override
    public T convertToEntityAttribute(String json)
    {
        if (json == null || json.isBlank()) {
            return emptyValue;
        }
        try {
            return MAPPER.readValue(json, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize " + label, e);
        }
    }
}
