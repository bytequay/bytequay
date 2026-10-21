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
import com.bytequay.app.agenda.AgendaPages;
import com.bytequay.app.agenda.AgendaSnapshot;
import com.bytequay.core.domain.ServedRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.agent.spi.ToolSchemas.object;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The {@code assistant_context} tool for one message. The agenda's kinds read a fresh snapshot on every call; the rest read what runs
 * now, the cards the message lists as visible, the page it was sent from, any item named by its ref or found by its title, number or
 * key, what a task said last, and what a new task can start with. Every ref a call hands out joins the refs served earlier that day,
 * so a later message may act on it.
 */
final class AssistantContext
{
    static final String NAME = "assistant_context";
    private static final List<String> KINDS = ImmutableList.<String>builder().addAll(AgendaPages.KINDS)
            .add("running", "waiting", "screen", "lookup", "result", "search", "start_options").build();
    /** The kinds that read one item the agent names by its ref. */
    private static final Set<String> NAMED = ImmutableSet.of("lookup", "result");
    private static final int MAXIMUM_ARGUMENTS = 4096;

    private final AssistantReader reader;
    private final LocalDate day;
    private final List<String> visible;
    private final Optional<String> onScreen;
    private final ObjectMapper mapper;
    private final Map<String, ServedRef> served;

    /**
     * @param visible the cards on screen when the message was sent
     * @param onScreen the ref of what the user was looking at
     * @param served the refs earlier messages that day were handed
     */
    AssistantContext(AssistantReader reader, LocalDate day, List<String> visible, Optional<String> onScreen, Map<String, ServedRef> served, ObjectMapper mapper)
    {
        this.reader = requireNonNull(reader, "reader is null");
        this.day = requireNonNull(day, "day is null");
        this.visible = ImmutableList.copyOf(visible);
        this.onScreen = requireNonNull(onScreen, "onScreen is null");
        this.served = new LinkedHashMap<>(served);
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    ReviewTool tool()
    {
        Map<String, Object> schema = object(ImmutableMap.of(
                "kind", ImmutableMap.of("type", "string", "enum", KINDS,
                        "description", "overview first; changes since the last agenda, mine (your open pull requests), tasks (agent tasks waiting or idle), "
                                + "asks (what people want from you), epics (work in flight and ready to start), history (earlier agendas), running (tasks at "
                                + "work now), waiting (the cards on screen and what answers them), screen (the page the user is on), lookup (one pull "
                                + "request, task, review or work item by its ref), result (what a task or review said last and how its latest turn ended), "
                                + "search (stored pull requests, issues, Jira tickets, tasks, work items and Epics by query), "
                                + "start_options (repositories, agents and automation for a new task)"),
                "page", ImmutableMap.of("type", "integer", "minimum", 0, "description", "From nextPage; 0 when absent"),
                "ref", ImmutableMap.of("type", "string", "description", "For lookup and result: a ref such as pr:owner/name#12, task:id, review:id or work:id"),
                "query", ImmutableMap.of("type", "string", "description", "For search: words every matching title holds, or a number such as 31402 or a key "
                        + "such as ICE-7")), "kind");
        return new ReviewTool(NAME, "Read the user's day in ByteQuay as it is now: what moved, what runs and waits, what is on screen, how a task can start, and any "
                        + "stored item by its title, number or key. Read-only.",
                schema, this::call, true);
    }

    /** Every ref handed out today, with the kind of page that named it first. */
    synchronized Map<String, ServedRef> served()
    {
        return ImmutableMap.copyOf(served);
    }

    /** A ref an action handed out, such as the task it started, so a later message may name it. */
    synchronized void serve(String ref, ServedRef value)
    {
        served.putIfAbsent(ref, value);
    }

    synchronized String call(String arguments)
    {
        try {
            checkArgument(arguments.length() <= MAXIMUM_ARGUMENTS, "arguments are too long");
            JsonNode request = mapper.readTree(arguments.isBlank() ? "{}" : arguments);
            String kind = request.path("kind").asText("");
            if (AgendaPages.KINDS.contains(kind)) {
                return agenda(arguments);
            }
            checkArgument(KINDS.contains(kind), "kind must be one of %s", String.join(", ", KINDS));
            boolean named = NAMED.contains(kind);
            Set<String> fields = named ? ImmutableSet.of("kind", "page", "ref")
                    : kind.equals("search") ? ImmutableSet.of("kind", "page", "query") : ImmutableSet.of("kind", "page");
            request.fieldNames().forEachRemaining(field -> checkArgument(fields.contains(field), "Unknown field: %s", field));
            String ref = request.path("ref").asText("").strip();
            checkArgument(!named || !ref.isEmpty(), "%s needs a ref", kind);
            JsonNode page = request.path("page");
            checkArgument(page.isMissingNode() || page.isNull() || page.canConvertToInt() && page.asInt() >= 0, "page must be a non-negative integer");
            AssistantReader.Page read = switch (kind) {
                case "running" -> reader.running();
                case "waiting" -> reader.waiting(visible);
                case "screen" -> reader.screen(onScreen);
                case "lookup" -> reader.lookup(ref);
                case "result" -> reader.result(ref);
                case "search" -> reader.search(request.path("query").asText(""), page.asInt(0));
                case "start_options" -> reader.startOptions();
                default -> throw new IllegalArgumentException("Unknown kind " + kind);
            };
            read.refs().forEach((handedOut, target) -> served.putIfAbsent(handedOut, new ServedRef(kind, target)));
            return mapper.writeValueAsString(read.body());
        }
        catch (JsonProcessingException invalid) {
            return error("arguments are not JSON");
        }
        catch (IllegalArgumentException invalid) {
            return error(invalid.getMessage());
        }
        catch (RuntimeException failure) {
            // A store that cannot be read narrows the answer to this call; the conversation goes on.
            return error("ByteQuay could not read that now: " + Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName()));
        }
    }

    /** The agenda's own pages over a snapshot read for this call; the refs a page names keep where they opened when it named them. */
    private String agenda(String arguments)
    {
        AgendaSnapshot snapshot = reader.agenda(day);
        AgendaPages pages = new AgendaPages(snapshot, mapper);
        String text = pages.call(arguments);
        pages.served().forEach((ref, kind) -> served.putIfAbsent(ref, new ServedRef(kind, Optional.of(snapshot.refs().get(ref).target()))));
        return text;
    }

    private String error(String message)
    {
        try {
            return mapper.writeValueAsString(ImmutableMap.of("error", ImmutableMap.of("message", message)));
        }
        catch (JsonProcessingException impossible) {
            return "{\"error\":{\"message\":\"invalid request\"}}";
        }
    }
}
