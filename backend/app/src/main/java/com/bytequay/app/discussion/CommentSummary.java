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
package com.bytequay.app.discussion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Set;

/** The verdict a summary request asks for, and the only judge of whether an answer is one. */
final class CommentSummary
{
    /** The shape the summary request asks for; the comment preamble embeds it. */
    static final String SHAPE = "{\"items\":[{\"ref\":\"c1\",\"ask\":\"…\",\"valid\":\"yes\",\"why\":\"…\",\"do\":\"yes\",\"plan\":\"…\",\"size\":\"small\"}]}";

    private static final Set<String> FIELDS = ImmutableSet.of("ref", "ask", "valid", "why", "do", "plan", "size");
    private static final Set<String> VALID = ImmutableSet.of("yes", "partly", "no", "unsure");
    private static final Set<String> DO = ImmutableSet.of("yes", "no", "your_call");
    private static final Set<String> SIZE = ImmutableSet.of("trivial", "small", "big");
    private static final int MAXIMUM_VERDICT_CHARACTERS = 16_000;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CommentSummary() {}

    /**
     * Validates an answer to a summary request over {@code count} comments and returns one normalized
     * verdict per comment, in {@code c1..cN} order. A missing text field reads as empty, so {@code plan}
     * and {@code size} may be left out when {@code do} is {@code no}, where they are emptied anyway.
     *
     * @throws IllegalArgumentException with the reader-facing reason
     */
    static List<String> parse(String answer, int count)
    {
        String text = answer.strip();
        if (text.startsWith("```") && text.endsWith("```") && text.indexOf('\n') > 0 && text.indexOf('\n') < text.length() - 3) {
            text = text.substring(text.indexOf('\n') + 1, text.length() - 3);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        }
        catch (JsonProcessingException failure) {
            throw invalid("it is not exactly one JSON object");
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.has("items")) {
            throw invalid("it must be one object holding only items");
        }
        JsonNode items = root.get("items");
        if (!items.isArray() || items.size() != count) {
            throw invalid("it must hold exactly " + count + " item" + (count == 1 ? "" : "s"));
        }
        String[] verdicts = new String[count];
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw invalid("every item must be an object");
            }
            item.fieldNames().forEachRemaining(field -> {
                if (!FIELDS.contains(field) || !item.get(field).isTextual()) {
                    throw invalid("an item has an unexpected or non-text field " + field);
                }
            });
            String ref = text(item, "ref");
            int position = ref.matches("c[1-9][0-9]{0,3}") ? Integer.parseInt(ref.substring(1)) : 0;
            if (position < 1 || position > count || verdicts[position - 1] != null) {
                throw invalid("the refs must be c1 to c" + count + ", each once");
            }
            verdicts[position - 1] = verdict(item);
        }
        return ImmutableList.copyOf(verdicts);
    }

    private static String verdict(JsonNode item)
    {
        String ask = text(item, "ask");
        String valid = text(item, "valid");
        String why = text(item, "why");
        String act = text(item, "do");
        String plan = text(item, "plan");
        String size = text(item, "size");
        if (ask.isEmpty() || why.isEmpty()) {
            throw invalid("ask and why must not be blank");
        }
        if (!VALID.contains(valid) || !DO.contains(act)) {
            throw invalid("valid or do is outside its allowed values");
        }
        if (act.equals("no")) {
            plan = "";
            size = "";
        }
        else if (plan.isEmpty() || !SIZE.contains(size)) {
            throw invalid("an item to act on needs a plan and a size");
        }
        ObjectNode verdict = JsonNodeFactory.instance.objectNode()
                .put("ask", ask)
                .put("valid", valid)
                .put("why", why)
                .put("do", act)
                .put("plan", plan)
                .put("size", size);
        String json = verdict.toString();
        if (json.length() > MAXIMUM_VERDICT_CHARACTERS) {
            throw invalid("a verdict is too long");
        }
        return json;
    }

    private static String text(JsonNode item, String field)
    {
        return item.path(field).asText("").strip();
    }

    private static IllegalArgumentException invalid(String detail)
    {
        return new IllegalArgumentException("The agent's summary was not in the expected form: " + detail);
    }
}
