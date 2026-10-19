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
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The writer's two tools: offering a page once the scope is settled, and saving it, each checked so that a refusal says what to fix. */
final class ArtifactTools
{
    static final int MAXIMUM_PAGE = 256 * 1024;
    static final int MAXIMUM_TITLE = 120;
    static final int MAXIMUM_COVERS = 400;
    private static final Pattern KEY = Pattern.compile("p[1-9][0-9]{0,3}");
    private static final Pattern THEME = Pattern.compile("<link\\b[^>]*\\bhref\\s*=\\s*[\"']?theme\\.css(?=[\"'\\s/>])", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(?:src|href|action|poster|srcset)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))|url\\(\\s*[\"']?([^\"')\\s]*)", Pattern.CASE_INSENSITIVE);

    /**
     * A page the agent saves.
     *
     * @param key the page to save a new version of; absent for a new page
     */
    record Page(String title, String html, Optional<String> key) {}

    record Offer(String title, String covers) {}

    private ArtifactTools() {}

    /**
     * The offer the writer's session carries; only its root agent may call it.
     *
     * @param offer keeps an accepted offer and returns what the agent is told
     */
    static ReviewTool offer(ObjectMapper mapper, Function<Offer, String> offer)
    {
        ImmutableMap<String, Object> schema = ImmutableMap.of("type", "object", "additionalProperties", false, "required", ImmutableList.of("title", "covers"),
                "properties", ImmutableMap.of(
                        "title", ImmutableMap.of("type", "string", "maxLength", MAXIMUM_TITLE, "description", "The page's title"),
                        "covers", ImmutableMap.of("type", "string", "maxLength", MAXIMUM_COVERS, "description", "One sentence on what the page would cover")));
        return new ReviewTool("artifact_offer", "Offer to turn the conversation into a page once its scope is settled. The user answers with a button.", schema,
                arguments -> {
                    JsonNode node;
                    try {
                        node = mapper.readTree(arguments);
                    }
                    catch (Exception malformed) {
                        return "Not offered: the arguments must be an object with a title and covers.";
                    }
                    Offer parsed = new Offer(node.path("title").asText("").strip(), node.path("covers").asText("").strip());
                    if (!line(parsed.title(), MAXIMUM_TITLE) || parsed.covers().isEmpty() || parsed.covers().length() > MAXIMUM_COVERS) {
                        return "Not offered: give a one-line title of 1 to " + MAXIMUM_TITLE + " characters and covers of 1 to " + MAXIMUM_COVERS + ".";
                    }
                    return offer.apply(parsed);
                });
    }

    /**
     * The save the writer's session carries; only its root agent may call it.
     *
     * @param save keeps an accepted page and returns what the agent is told
     */
    static ReviewTool save(ObjectMapper mapper, Function<Page, String> save)
    {
        ImmutableMap<String, Object> schema = ImmutableMap.of("type", "object", "additionalProperties", false, "required", ImmutableList.of("title", "html"),
                "properties", ImmutableMap.of(
                        "title", ImmutableMap.of("type", "string", "maxLength", MAXIMUM_TITLE, "description", "The page's title"),
                        "html", ImmutableMap.of("type", "string", "description", "The whole HTML document, linking theme.css"),
                        "page", ImmutableMap.of("type", "string", "description", "The key of a page you saved before, such as p1, to save its next version; "
                                + "leave it out for a new page")));
        return new ReviewTool("artifact_save", "Save a page for the user. Each save is a new version the user can step back from.", schema, arguments -> {
            Page page;
            try {
                JsonNode node = mapper.readTree(arguments);
                page = new Page(node.path("title").asText("").strip(), node.path("html").asText(""),
                        Optional.of(node.path("page").asText("").strip()).filter(key -> !key.isEmpty()));
            }
            catch (Exception malformed) {
                return "Not saved: the arguments must be an object with a title, the html, and optionally the page key.";
            }
            return problem(page).map(reason -> "Not saved: " + reason).orElseGet(() -> save.apply(page));
        });
    }

    /** Why the page cannot be kept, said so the agent can fix it; empty when it can. */
    static Optional<String> problem(Page page)
    {
        if (!line(page.title(), MAXIMUM_TITLE)) {
            return Optional.of("the title must be one line of 1 to " + MAXIMUM_TITLE + " characters.");
        }
        if (page.key().filter(key -> !KEY.matcher(key).matches()).isPresent()) {
            return Optional.of("the page key must be one an earlier save returned, such as p1.");
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
        return Optional.empty();
    }

    private static boolean line(String text, int maximum)
    {
        return !text.isEmpty() && text.length() <= maximum && !text.contains("\n");
    }

    private static boolean inside(String value)
    {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() || lower.startsWith("#") || lower.startsWith("data:") || lower.equals("theme.css");
    }
}
