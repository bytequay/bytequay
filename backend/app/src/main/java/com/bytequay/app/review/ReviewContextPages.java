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
package com.bytequay.app.review;

import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.bytequay.agent.spi.ToolSchemas.object;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/** Pages captured reference text without exposing the main review's findings, evidence store or write tools. */
final class ReviewContextPages
{
    private static final int PAGE_CHARACTERS = 12_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReviewContextPages() {}

    static ReviewTool tool(Map<String, String> sections, BooleanSupplier active)
    {
        Map<String, String> saved = ImmutableMap.copyOf(sections);
        return new ReviewTool("review_get_context", "Read this independent pass's captured assignment, scope, pr, facts or rules. Follow nextPage; no other review data or writes",
                object(ImmutableMap.of(
                        "kind", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(saved.keySet())),
                        "page", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Integer.MAX_VALUE)),
                        "kind"), arguments -> {
                    try {
                        checkState(active.getAsBoolean(), "This independent pass has stopped or been replaced");
                        JsonNode input = MAPPER.readTree(arguments);
                        checkArgument(input != null && input.isObject() && input.path("kind").isTextual(), "kind is required");
                        checkArgument(input.size() == (input.has("page") ? 2 : 1), "Unexpected arguments");
                        String kind = input.path("kind").textValue();
                        checkArgument(saved.containsKey(kind), "This context kind is unavailable to the independent pass");
                        checkArgument(!input.has("page") || input.get("page").isIntegralNumber() && input.get("page").canConvertToInt(), "page must be an integer");
                        return page(kind, saved.get(kind), input.path("page").asInt(1)).toString();
                    }
                    catch (JsonProcessingException | IllegalArgumentException | IllegalStateException failure) {
                        return JsonNodeFactory.instance.objectNode().put("error", failure.getMessage()).toString();
                    }
        }, true);
    }

    static ObjectNode page(String kind, String text, int page)
    {
        long offset = ((long) page - 1) * PAGE_CHARACTERS;
        checkArgument(page > 0 && offset <= text.length(), "page is outside this context");
        int start = (int) offset;
        int end = (int) Math.min(text.length(), offset + PAGE_CHARACTERS);
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            start--;
        }
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("kind", kind).put("page", page)
                .put("referenceOnly", !kind.equals("assignment")).put("text", text.substring(start, end)).put("totalCharacters", text.length());
        if (end < text.length()) {
            result.put("nextPage", page + 1);
        }
        else {
            result.putNull("nextPage");
        }
        return result;
    }
}
