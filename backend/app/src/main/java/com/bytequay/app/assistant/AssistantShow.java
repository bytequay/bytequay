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
package com.bytequay.app.assistant;

import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantList;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The {@code assistant_show} tool: what the assistant shows the user, which changes no data. A list names refs the user could see, on
 * screen or handed out that day, and the panel draws each from ByteQuay's own data, so the assistant never writes a row.
 */
@Component
class AssistantShow
{
    static final String NAME = "assistant_show";
    private static final int MAXIMUM_ARGUMENTS = 16 * 1024;
    private static final int TITLE = 80;
    /** What a list can draw: the items ByteQuay keeps, not the cards and repositories a message may also name. */
    private static final Set<String> LISTED = ImmutableSet.of("pr", "issue", "jira", "task", "review", "work", "epic");
    private static final Map<String, AssistantList.Style> STYLES = ImmutableMap.of(
            "rows", AssistantList.Style.ROWS,
            "cards", AssistantList.Style.CARDS,
            "timeline", AssistantList.Style.TIMELINE);
    private static final Map<String, Set<String>> FIELDS = ImmutableMap.of("list", ImmutableSet.of("op", "style", "title", "refs"));

    private final AssistantActions actions;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantShow(AssistantActions actions, ObjectMapper mapper, Clock clock)
    {
        this.actions = requireNonNull(actions, "actions is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** The tool for one message: it shows only what that message had on screen or the day handed out, and adds what it shows through {@code items}. */
    ReviewTool tool(AssistantItem message, AssistantContext context, Consumer<AssistantItem> items)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER, "only a user's message can show");
        Map<String, Object> schema = ImmutableMap.of(
                "type", "object",
                "properties", ImmutableMap.of(
                        "op", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(FIELDS.keySet())),
                        "style", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(STYLES.keySet()),
                                "description", "rows by default; cards for a few items that need the user; timeline for history"),
                        "title", ImmutableMap.of("type", "string", "description", "A few words that name the list"),
                        "refs", ImmutableMap.of("type", "array", "items", ImmutableMap.of("type", "string"), "maxItems", AssistantList.MAXIMUM_REFS,
                                "description", "Refs that assistant_context returned or that are on screen, in the order to show them")),
                "required", ImmutableList.of("op"),
                "additionalProperties", false);
        return new ReviewTool(NAME, "Show the user several items as a list in the panel. The app draws each item from its own data, so the list stays current.",
                schema, arguments -> call(message, context, items, arguments), false);
    }

    private String call(AssistantItem message, AssistantContext context, Consumer<AssistantItem> items, String arguments)
    {
        try {
            checkArgument(arguments.length() <= MAXIMUM_ARGUMENTS, "arguments are too long");
            JsonNode body = mapper.readTree(arguments.isBlank() ? "{}" : arguments);
            checkArgument(body.isObject(), "arguments must be an object");
            String op = body.path("op").asText("");
            checkArgument(FIELDS.containsKey(op), "op must be one of %s", String.join(", ", FIELDS.keySet()));
            body.fieldNames().forEachRemaining(field -> checkArgument(FIELDS.get(op).contains(field), "%s takes no %s", op, field));
            return list(message, context, items, body);
        }
        catch (JsonProcessingException invalid) {
            return reply("error", "arguments are not JSON", ImmutableList.of());
        }
        catch (IllegalArgumentException invalid) {
            return reply("error", invalid.getMessage(), ImmutableList.of());
        }
    }

    /** Keeps the refs the user could see, in the assistant's order, and names the ones it drops. */
    private String list(AssistantItem message, AssistantContext context, Consumer<AssistantItem> items, JsonNode body)
    {
        AssistantList.Style style = Optional.ofNullable(STYLES.get(body.path("style").asText("rows")))
                .orElseThrow(() -> new IllegalArgumentException("style must be one of " + String.join(", ", STYLES.keySet())));
        String title = body.path("title").asText("").strip();
        checkArgument(!title.isEmpty(), "list needs a title");
        JsonNode refs = body.path("refs");
        checkArgument(refs.isArray() && !refs.isEmpty(), "list needs refs");
        checkArgument(refs.size() <= AssistantList.MAXIMUM_REFS, "a list holds at most %s refs", AssistantList.MAXIMUM_REFS);
        Set<String> shown = actions.shown(message, context);
        Set<String> kept = new LinkedHashSet<>();
        Set<String> dropped = new LinkedHashSet<>();
        for (JsonNode ref : refs) {
            checkArgument(ref.isTextual(), "refs are strings");
            String named = ref.asText().strip();
            (LISTED.contains(AgendaRefs.kind(named)) && shown.contains(named) ? kept : dropped).add(named);
        }
        if (kept.isEmpty()) {
            return reply("refused", "None of those refs are on screen or handed out today; read them with assistant_context first", ImmutableList.copyOf(dropped));
        }
        items.accept(AssistantItem.list(message.day(), clock.instant(), clip(title, TITLE), new AssistantList(style, ImmutableList.copyOf(kept))));
        return reply("done", dropped.isEmpty() ? "Showed " + kept.size() : "Showed " + kept.size() + "; dropped the refs not on screen or handed out today",
                ImmutableList.copyOf(dropped));
    }

    /** What the tool tells the assistant: what came of the call, and the refs it dropped, if any. */
    private String reply(String outcome, String message, List<String> dropped)
    {
        try {
            return mapper.writeValueAsString(dropped.isEmpty() ? ImmutableMap.of("outcome", outcome, "message", message)
                    : ImmutableMap.of("outcome", outcome, "message", message, "dropped", dropped));
        }
        catch (JsonProcessingException impossible) {
            return "{\"outcome\":\"" + outcome + "\"}";
        }
    }
}
