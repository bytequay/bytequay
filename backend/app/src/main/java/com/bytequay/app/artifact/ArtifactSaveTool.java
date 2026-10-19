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
package com.bytequay.app.artifact;

import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The writer's one save: the page with its title and suggestions, checked so that a refusal tells the agent what to fix. */
final class ArtifactSaveTool
{
    static final String NAME = "artifact_save";
    static final int MAXIMUM_PAGE = 256 * 1024;
    static final int MAXIMUM_TITLE = 120;
    static final int MAXIMUM_CARDS = 3;
    static final int MAXIMUM_CARD_TITLE = 80;
    static final int MAXIMUM_CARD_ASK = 1000;
    private static final Pattern THEME = Pattern.compile("<link\\b[^>]*\\bhref\\s*=\\s*[\"']?theme\\.css(?=[\"'\\s/>])", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(?:src|href|action|poster|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))|url\\(\\s*[\"']?([^\"')\\s]*)", Pattern.CASE_INSENSITIVE);

    record Page(String title, String html, List<Card> next) {}

    record Card(String title, String ask) {}

    private ArtifactSaveTool() {}

    /**
     * The save the writer's session carries; only its root agent may call it.
     *
     * @param save keeps an accepted page and returns what the agent is told
     */
    static ReviewTool tool(ObjectMapper mapper, Function<Page, String> save)
    {
        ImmutableMap<String, Object> text = ImmutableMap.of("type", "string");
        ImmutableMap<String, Object> card = ImmutableMap.of("type", "object", "additionalProperties", false, "required", ImmutableList.of("title", "ask"),
                "properties", ImmutableMap.of(
                        "title", ImmutableMap.of("type", "string", "maxLength", MAXIMUM_CARD_TITLE, "description", "What the follow-up page covers"),
                        "ask", ImmutableMap.of("type", "string", "maxLength", MAXIMUM_CARD_ASK, "description", "The whole request to write it, in the user's voice")));
        ImmutableMap<String, Object> schema = ImmutableMap.of("type", "object", "additionalProperties", false, "required", ImmutableList.of("title", "html"),
                "properties", ImmutableMap.of(
                        "title", ImmutableMap.<String, Object>builder().putAll(text).put("maxLength", MAXIMUM_TITLE).put("description", "The page's title").buildOrThrow(),
                        "html", ImmutableMap.<String, Object>builder().putAll(text).put("description", "The whole HTML document, linking theme.css").buildOrThrow(),
                        "next", ImmutableMap.of("type", "array", "maxItems", MAXIMUM_CARDS, "items", card,
                                "description", "Up to three follow-up pages worth reading next; none is fine")));
        return new ReviewTool(NAME, "Save the page for the user. Each save replaces the page and its Next cards.", schema, arguments -> {
            Page page;
            try {
                page = parse(mapper.readTree(arguments));
            }
            catch (Exception malformed) {
                return "Not saved: the arguments must be an object with a title, the html, and optional next cards.";
            }
            return problem(page).map(reason -> "Not saved: " + reason).orElseGet(() -> save.apply(page));
        });
    }

    private static Page parse(JsonNode arguments)
    {
        ImmutableList.Builder<Card> cards = ImmutableList.builder();
        for (JsonNode card : arguments.path("next")) {
            cards.add(new Card(card.path("title").asText("").strip(), card.path("ask").asText("").strip()));
        }
        return new Page(arguments.path("title").asText("").strip(), arguments.path("html").asText(""), cards.build());
    }

    /** Why the page cannot be kept, said so the agent can fix it; empty when it can. */
    static Optional<String> problem(Page page)
    {
        if (page.title().isEmpty() || page.title().length() > MAXIMUM_TITLE || page.title().contains("\n")) {
            return Optional.of("the title must be one line of 1 to " + MAXIMUM_TITLE + " characters.");
        }
        if (page.html().isBlank()) {
            return Optional.of("the html is empty.");
        }
        if (page.html().getBytes(StandardCharsets.UTF_8).length > MAXIMUM_PAGE) {
            return Optional.of("the page is larger than " + MAXIMUM_PAGE / 1024 + " KiB. Shorten it.");
        }
        if (!THEME.matcher(page.html()).find()) {
            return Optional.of("the page must link the app theme with <link rel=\"stylesheet\" href=\"theme.css\">.");
        }
        Matcher reference = REFERENCE.matcher(page.html());
        while (reference.find()) {
            String value = Optional.ofNullable(reference.group(1)).or(() -> Optional.ofNullable(reference.group(2)))
                    .or(() -> Optional.ofNullable(reference.group(3))).orElse(reference.group(4)).strip();
            if (!inside(value)) {
                return Optional.of("the page points outside itself at " + value + ". The page cannot load or open anything else; inline it, use #anchors, "
                        + "or cite the source as text.");
            }
        }
        if (page.next().size() > MAXIMUM_CARDS) {
            return Optional.of("at most " + MAXIMUM_CARDS + " next cards.");
        }
        for (Card card : page.next()) {
            if (card.title().isEmpty() || card.title().length() > MAXIMUM_CARD_TITLE || card.ask().isEmpty() || card.ask().length() > MAXIMUM_CARD_ASK) {
                return Optional.of("each next card needs a title of 1 to " + MAXIMUM_CARD_TITLE + " characters and an ask of 1 to " + MAXIMUM_CARD_ASK + ".");
            }
        }
        return Optional.empty();
    }

    private static boolean inside(String value)
    {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.startsWith("#") || lower.startsWith("data:") || lower.equals("theme.css");
    }
}
