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

import com.bytequay.agent.spi.DevelopmentResultReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class DevelopmentResultReportConverter
        implements AttributeConverter<DevelopmentResultReport, String>
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    @Override
    public String convertToDatabaseColumn(DevelopmentResultReport value)
    {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot serialize development readiness", failure);
        }
    }

    @Override
    public DevelopmentResultReport convertToEntityAttribute(String json)
    {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, DevelopmentResultReport.class);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot deserialize development readiness", failure);
        }
    }
}
