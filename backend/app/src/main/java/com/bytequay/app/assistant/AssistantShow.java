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
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantList;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.AssistantReceipt.Operation;
import com.bytequay.core.domain.AssistantReceipt.Outcome;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The {@code assistant_show} tool: what the assistant shows the user, which changes no data. A list names refs the user could see, on
 * screen or handed out that day, and the panel draws each from ByteQuay's own data, so the assistant never writes a row. An open goes
 * to a page or such an item only on the user's own words, and leaves a receipt either way. A draft is a new GitHub issue the user
 * edits and creates as an Epic with a click.
 */
@Component
class AssistantShow
{
    static final String NAME = "assistant_show";
    private static final int MAXIMUM_ARGUMENTS = 16 * 1024;
    private static final int TITLE = 80;
    private static final int DRAFT_TITLE = 200;
    private static final int DRAFT_BODY = 8000;
    /** What a list can draw: the items ByteQuay keeps, not the cards and repositories a message may also name. */
    private static final Set<String> LISTED = ImmutableSet.of("pr", "issue", "jira", "task", "review", "work", "epic");
    private static final Map<String, AssistantList.Style> STYLES = ImmutableMap.of(
            "rows", AssistantList.Style.ROWS,
            "cards", AssistantList.Style.CARDS,
            "timeline", AssistantList.Style.TIMELINE);
    private static final Map<String, Set<String>> FIELDS = ImmutableMap.of(
            "list", ImmutableSet.of("op", "style", "title", "refs"),
            "open", ImmutableSet.of("op", "ref", "page", "words"),
            "draft_epic", ImmutableSet.of("op", "workspace", "title", "body"));
    // Twins of the sidebar's AppPage in AppSidebar.tsx and the Settings sections in settings/routes.ts, by the names the app shows; change them together.
    private static final Map<String, String> PAGES = ImmutableMap.<String, String>builder()
            .put("home", "Home")
            .put("workspaces", "Workspaces")
            .put("teams", "Circles")
            .put("recent", "Recent")
            .put("pulls", "Pull requests")
            .put("jira", "Jira")
            .put("google", "Google Apps")
            .put("settings", "Settings")
            .put("settings/general", "Settings · General")
            .put("settings/appearance", "Settings · Appearance")
            .put("settings/browser", "Settings · Browser")
            .put("settings/models", "Settings · Models")
            .put("settings/skills", "Settings · Skills")
            .put("settings/workflow", "Settings · Workflow")
            .put("settings/github", "Settings · GitHub")
            .put("settings/jira", "Settings · Jira")
            .put("settings/google", "Settings · Google Apps")
            .put("settings/lab", "Settings · Lab")
            .buildOrThrow();

    private final AssistantActions actions;
    private final AssistantRows rows;
    private final WorkspaceStore workspaces;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantShow(AssistantActions actions, AssistantRows rows, WorkspaceStore workspaces, ObjectMapper mapper, Clock clock)
    {
        this.actions = requireNonNull(actions, "actions is null");
        this.rows = requireNonNull(rows, "rows is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * The tool for one message: it shows only what that message had on screen or the day handed out, opens only on its words, and adds
     * what it shows and the receipts it leaves through {@code items}.
     */
    ReviewTool tool(AssistantItem message, AssistantContext context, Consumer<AssistantItem> items)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER, "only a user's message can show");
        Map<String, Object> schema = ImmutableMap.of(
                "type", "object",
                "properties", ImmutableMap.of(
                        "op", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(FIELDS.keySet())),
                        "style", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(STYLES.keySet()),
                                "description", "rows by default; cards for a few items that need the user; timeline for history"),
                        "title", ImmutableMap.of("type", "string", "description", "list: a few words that name the list; draft_epic: the issue's title"),
                        "refs", ImmutableMap.of("type", "array", "items", ImmutableMap.of("type", "string"), "maxItems", AssistantList.MAXIMUM_REFS,
                                "description", "list: refs that assistant_context returned or that are on screen, in the order to show them"),
                        "ref", ImmutableMap.of("type", "string", "description", "open: an item's ref that assistant_context returned or that is on screen"),
                        "page", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(PAGES.keySet()), "description", "open: a page of the app or a Settings section"),
                        "words", ImmutableMap.of("type", "string", "description", "open: the user's exact words in their latest message that ask for it"),
                        "workspace", ImmutableMap.of("type", "string", "description", "draft_epic: the ws: ref from start_options of the repository the issue goes to"),
                        "body", ImmutableMap.of("type", "string", "description", "draft_epic: the issue's description for the user to edit")),
                "required", ImmutableList.of("op"),
                "additionalProperties", false);
        return new ReviewTool(NAME, "Show the user several items as a list in the panel, which the app draws from its own data; open a page or an item when the "
                + "user's latest message asks, with a receipt; or draft a new GitHub issue that the user edits and creates as an Epic with a click.", schema,
                arguments -> call(message, context, items, arguments), false);
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
            return switch (op) {
                case "list" -> list(message, context, items, body);
                case "open" -> open(message, context, items, body);
                default -> draft(message, context, items, body);
            };
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

    /** Goes where the user's own words ask: a page of the app, or an item they could see. Done or refused, it leaves a receipt. */
    private String open(AssistantItem message, AssistantContext context, Consumer<AssistantItem> items, JsonNode body)
    {
        checkArgument(body.path("words").isTextual(), "open needs the user's words");
        String words = body.path("words").asText();
        Optional<String> ref = text(body, "ref");
        Optional<String> page = text(body, "page");
        checkArgument(ref.isPresent() != page.isPresent(), "open takes a ref or a page");
        checkArgument(page.map(PAGES::containsKey).orElse(true), "page must be one of %s", String.join(", ", PAGES.keySet()));
        checkArgument(ref.map(named -> LISTED.contains(AgendaRefs.kind(named))).orElse(true), "open takes an item's ref or a page");
        Optional<String> unsaid = AssistantActions.unsaid(message, words);
        if (unsaid.isPresent()) {
            return receipt(message, items, unsaid.orElseThrow(), AssistantReceipt.refused(Operation.OPEN, Optional.empty()), words);
        }
        if (page.isPresent()) {
            return receipt(message, items, "Opened " + PAGES.get(page.orElseThrow()), new AssistantReceipt(Operation.OPEN, Outcome.DONE, Optional.empty(), Optional.empty(),
                    page, Optional.empty(), Optional.empty(), Optional.empty()), words);
        }
        String named = ref.orElseThrow();
        if (!actions.shown(message, context).contains(named)) {
            return receipt(message, items, "That isn't on screen", AssistantReceipt.refused(Operation.OPEN, Optional.empty()), words);
        }
        // Where it opens and what it is called come from the stores now, not from what the assistant was told.
        Optional<AssistantRow> row = Optional.ofNullable(rows.rows(ImmutableList.of(named)).get(named));
        if (row.isEmpty()) {
            return receipt(message, items, "That isn't in ByteQuay any more", AssistantReceipt.refused(Operation.OPEN, Optional.empty()), words);
        }
        return receipt(message, items, "Opened “" + clip(row.orElseThrow().title(), TITLE) + "”", AssistantReceipt.done(Operation.OPEN, Optional.of(row.orElseThrow().target())),
                words);
    }

    /** Puts a new GitHub issue in front of the user to edit and create as an Epic; the assistant itself creates nothing. */
    private String draft(AssistantItem message, AssistantContext context, Consumer<AssistantItem> items, JsonNode body)
    {
        String repository = body.path("workspace").asText("").strip();
        String title = body.path("title").asText("").strip();
        checkArgument(repository.startsWith("ws:"), "draft_epic needs a ws: ref");
        checkArgument(!title.isEmpty(), "draft_epic needs a title");
        if (!actions.shown(message, context).contains(repository)) {
            return reply("refused", "That repository isn't on screen or handed out today; read start_options first", ImmutableList.of());
        }
        Optional<Workspace> watched = workspaces.findById(repository.substring("ws:".length()));
        if (watched.isEmpty()) {
            return reply("refused", "That repository is not watched any more", ImmutableList.of());
        }
        AssistantDraft draft = new AssistantDraft(watched.orElseThrow().id(), watched.orElseThrow().monitoredRepository().fullName(),
                clip(body.path("body").asText("").strip(), DRAFT_BODY), UUID.randomUUID().toString());
        items.accept(AssistantItem.draft(message.day(), clock.instant(), clip(title, DRAFT_TITLE), draft));
        return reply("done", "Showed the draft; the user edits it and creates the issue as an Epic with a click", ImmutableList.of());
    }

    /** Leaves the receipt the user reads, with the words the assistant acted on, and tells the assistant the same line. */
    private String receipt(AssistantItem message, Consumer<AssistantItem> items, String line, AssistantReceipt receipt, String words)
    {
        items.accept(AssistantItem.receipt(message.day(), clock.instant(), line, receipt.withWords(words)));
        return reply(receipt.outcome().name().toLowerCase(Locale.ROOT), line, ImmutableList.of());
    }

    private static Optional<String> text(JsonNode body, String field)
    {
        return Optional.of(body.path(field).asText("").strip()).filter(value -> !value.isEmpty());
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
