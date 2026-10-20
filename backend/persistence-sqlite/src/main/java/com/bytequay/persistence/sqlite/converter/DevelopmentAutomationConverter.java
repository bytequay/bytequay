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

import com.bytequay.agent.core.DevelopmentAutomation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.Converter;

@Converter
public class DevelopmentAutomationConverter
        extends JsonColumnConverter<DevelopmentAutomation>
{
    private static final ObjectMapper MAPPER = JsonColumns.mapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    @Override
    DevelopmentAutomation read(String json)
            throws JsonProcessingException
    {
        var saved = MAPPER.readTree(json);
        if (saved instanceof ObjectNode object) {
            DevelopmentAutomation.upgradeSaved(object);
        }
        return MAPPER.treeToValue(saved, DevelopmentAutomation.class);
    }

    DevelopmentAutomationConverter()
    {
        super(new TypeReference<>() {}, "development automation", null, null, MAPPER);
    }
}
