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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;
import com.google.common.base.Enums;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One possible comment listed by a review's second reader. It is a claim nobody has checked: validation reads the
 * source and decides, exactly as it does for a hypothesis the investigation recorded.
 *
 * @param path the file the lead is about, or empty for one about the commits or the description
 * @param line the new-file line at the head, when the lead has one
 * @param ask what the author should do
 */
record ReviewLead(String path, OptionalInt line, Lens lens, Kind kind, String claim, String ask)
{
    /** The shape the second reader is asked for; its request embeds it. */
    static final String SHAPE = "{\"leads\":[{\"path\":\"src/Example.java\",\"line\":42,\"lens\":\"words\",\"kind\":\"nit\",\"claim\":\"…\",\"ask\":\"…\"}]}";
    static final int MAX_LEADS = 30;
    private static final int MAX_PATH = 300;
    private static final int MAX_TEXT = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    ReviewLead
    {
        checkArgument(requireNonNull(path, "path is null").length() <= MAX_PATH, "path is too long");
        requireNonNull(line, "line is null");
        requireNonNull(lens, "lens is null");
        requireNonNull(kind, "kind is null");
        checkArgument(!requireNonNull(claim, "claim is null").isBlank(), "claim is blank");
        checkArgument(!requireNonNull(ask, "ask is null").isBlank(), "ask is blank");
    }

    /** Which single concern of the second reader's assignment produced the lead. */
    enum Lens
    {
        WORDS,
        MECHANISMS,
        SIBLINGS,
        TESTS,
        RULES,
        FACTS
    }

    enum Kind
    {
        DEFECT,
        SUGGESTION,
        NIT,
        QUESTION
    }

    /** Where the lead points, as a reader would write it. */
    String location()
    {
        if (path.isEmpty()) {
            return "the commits or the description";
        }
        return line.isPresent() ? path + ":" + line.getAsInt() : path;
    }

    /**
     * Reads the second reader's answer. A lead that is not well formed is left out rather than failing the others, and
     * only the first {@link #MAX_LEADS} are kept, since the answer lists the strongest first.
     *
     * @throws IllegalArgumentException when the answer is not one JSON object holding {@code leads}
     */
    static List<ReviewLead> parse(String answer)
    {
        String text = requireNonNull(answer, "answer is null").strip();
        int fence = text.lastIndexOf("```", text.length() - 4);
        if (text.endsWith("```") && fence >= 0 && text.indexOf('\n', fence) > 0) {
            text = text.substring(text.indexOf('\n', fence) + 1, text.length() - 3);
        }
        // A sentence before the object is noise; text after it is ignored by the parser.
        if (text.indexOf('{') > 0) {
            text = text.substring(text.indexOf('{'));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("The second reader's answer is not one JSON object", failure);
        }
        checkArgument(root != null && root.isObject() && root.path("leads").isArray(), "The second reader's answer must be one object holding leads");
        ImmutableList.Builder<ReviewLead> leads = ImmutableList.builder();
        int kept = 0;
        for (JsonNode item : root.get("leads")) {
            Optional<ReviewLead> lead = kept < MAX_LEADS ? lead(item) : Optional.empty();
            if (lead.isPresent()) {
                leads.add(lead.orElseThrow());
                kept++;
            }
        }
        return leads.build();
    }

    private static Optional<ReviewLead> lead(JsonNode item)
    {
        String path = item.path("path").asText("").strip();
        String claim = item.path("claim").asText("").strip();
        String ask = item.path("ask").asText("").strip();
        Optional<Lens> lens = Enums.getIfPresent(Lens.class, item.path("lens").asText("").strip().toUpperCase(Locale.ROOT)).toJavaUtil();
        Optional<Kind> kind = Enums.getIfPresent(Kind.class, item.path("kind").asText("").strip().toUpperCase(Locale.ROOT)).toJavaUtil();
        if (!item.isObject() || claim.isEmpty() || ask.isEmpty() || lens.isEmpty() || kind.isEmpty() || path.length() > MAX_PATH || path.contains("\n")) {
            return Optional.empty();
        }
        int line = item.path("line").asInt(0);
        return Optional.of(new ReviewLead(path, line > 0 && !path.isEmpty() ? OptionalInt.of(line) : OptionalInt.empty(), lens.orElseThrow(), kind.orElseThrow(),
                bounded(claim), bounded(ask)));
    }

    private static String bounded(String text)
    {
        return Ascii.truncate(text, MAX_TEXT + 1, "…");
    }
}
