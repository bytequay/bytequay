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
package com.bytequay.agent.cli.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/** The line a ByteQuay tool returns about what a call read or saved; the task log shows it in place of the tool's name. */
public final class ToolActivityLine
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LENGTH = 200;

    private ToolActivityLine() {}

    public static Optional<String> fromResult(JsonNode content)
    {
        return object(content).map(result -> result.path("activityLine")).filter(JsonNode::isTextual).map(JsonNode::textValue).map(String::strip)
                .filter(line -> !line.isEmpty() && line.length() <= MAX_LENGTH && line.indexOf('\n') < 0);
    }

    private static Optional<JsonNode> object(JsonNode content)
    {
        if (content == null || content.path("isError").asBoolean(false)) {
            return Optional.empty();
        }
        JsonNode blocks = content.isObject() && content.has("content") ? content.path("content") : content;
        JsonNode text = blocks.isArray() && blocks.size() == 1 && blocks.get(0).path("type").asText().equals("text") ? blocks.get(0).path("text") : blocks;
        if (!text.isTextual() || text.textValue().length() > 128 * 1024) {
            return Optional.empty();
        }
        try {
            JsonNode result = MAPPER.readTree(text.textValue());
            return result != null && result.isObject() && !result.has("error") ? Optional.of(result) : Optional.empty();
        }
        catch (JsonProcessingException ignored) {
            // A malformed provider echo carries nothing a receipt or a transcript row can rely on.
            return Optional.empty();
        }
    }
}
