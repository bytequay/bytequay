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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolSchemas
{
    @Test
    void preservesRequiredFieldsAndSchemaOrder()
    {
        Map<String, Object> properties = ImmutableMap.of(
                "name", ImmutableMap.of("type", "string", "minLength", 1),
                "arguments", ImmutableMap.of("type", "object", "additionalProperties", true));
        Map<String, Object> schema = ToolSchemas.object(properties, "name", "arguments");

        assertThat(schema).isEqualTo(ImmutableMap.of(
                "type", "object", "properties", properties,
                "required", ImmutableList.of("name", "arguments"), "additionalProperties", false));
        assertThat(schema.keySet()).containsExactly("type", "properties", "required", "additionalProperties");
        assertThat(schema.get("properties")).isSameAs(properties);
    }

    @Test
    void omitsRequiredForOptionalAndEmptyInputs()
    {
        for (Map<String, Object> properties : ImmutableList.of(ImmutableMap.<String, Object>of(), ImmutableMap.<String, Object>of("query", ImmutableMap.of("type", "string")))) {
            Map<String, Object> schema = ToolSchemas.object(properties);
            assertThat(schema).isEqualTo(ImmutableMap.of("type", "object", "properties", properties, "additionalProperties", false));
            assertThat(schema.keySet()).containsExactly("type", "properties", "additionalProperties");
        }
    }

    @Test
    void snapshotsRequiredFieldsAndReturnsAnImmutableSchema()
    {
        String[] required = {"section"};
        Map<String, Object> schema = ToolSchemas.object(ImmutableMap.of("section", ImmutableMap.of("type", "string")), required);
        required[0] = "changed";

        assertThat(schema.get("required")).isEqualTo(ImmutableList.of("section"));
        assertThatThrownBy(() -> schema.put("additionalProperties", true)).isInstanceOf(UnsupportedOperationException.class);
    }
}
