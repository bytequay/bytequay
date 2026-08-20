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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.core.domain.PrTimelinePayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class PrTimelinePayloadConverter
        implements AttributeConverter<PrTimelinePayload, String>
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Override
    public String convertToDatabaseColumn(PrTimelinePayload payload)
    {
        try {
            return MAPPER.writeValueAsString(payload == null ? PrTimelinePayload.empty() : payload);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize PR timeline payload", e);
        }
    }

    @Override
    public PrTimelinePayload convertToEntityAttribute(String json)
    {
        if (json == null || json.isBlank()) {
            return PrTimelinePayload.empty();
        }
        try {
            return MAPPER.readValue(json, PrTimelinePayload.class);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize PR timeline payload", e);
        }
    }
}
