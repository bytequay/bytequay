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

import java.util.Map;

/** Object input schemas shared by native and MCP tools. */
public final class ToolSchemas
{
    private ToolSchemas() {}

    public static Map<String, Object> object(Map<String, Object> properties, String... required)
    {
        ImmutableMap.Builder<String, Object> schema = ImmutableMap.<String, Object>builder()
                .put("type", "object")
                .put("properties", properties);
        if (required.length > 0) {
            schema.put("required", ImmutableList.copyOf(required));
        }
        return schema.put("additionalProperties", false).buildOrThrow();
    }
}
