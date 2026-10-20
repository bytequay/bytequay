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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.Converter;

import static com.google.common.base.Preconditions.checkArgument;

@Converter
public class DevelopmentResultReportConverter
        extends JsonColumnConverter<DevelopmentResultReport>
{
    private static final ObjectMapper MAPPER = JsonColumns.mapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    DevelopmentResultReportConverter()
    {
        super(new TypeReference<>() {}, "development readiness", null, null, MAPPER);
    }

    @Override
    DevelopmentResultReport read(String json)
            throws JsonProcessingException
    {
        JsonNode value = MAPPER.readTree(json);
        checkArgument(value.path("readyForDelivery").isBoolean(), "Development readiness must be a boolean");
        if (value instanceof ObjectNode object) {
            object.putIfAbsent("reviewReplies", object.arrayNode());
        }
        return MAPPER.treeToValue(value, DevelopmentResultReport.class);
    }
}
