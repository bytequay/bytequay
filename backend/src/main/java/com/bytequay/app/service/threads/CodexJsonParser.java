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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.StreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Converts one line of {@code codex exec --json} stdout into zero or
 * more {@link StreamEvent}s — the Codex analogue of
 * {@link StreamJsonParser}.
 *
 * <p>Codex's event stream is flatter than Claude's. Each line is a JSON
 * object with a {@code "type"}:
 *
 * <ul>
 *   <li>{@code thread.started} — carries {@code thread_id}, the session
 *       id we resume with ({@code codex exec resume <id>}). Mapped
 *       to {@link StreamEvent.SessionStarted}.</li>
 *   <li>{@code turn.started} — turn boundary, no payload; ignored.</li>
 *   <li>{@code item.started} / {@code item.completed} — wrap an
 *       {@code item} whose own {@code type} is {@code agent_message}
 *       (assistant prose), {@code reasoning} (thinking), or
 *       {@code command_execution} (a shell tool call). A command's
 *       {@code item.started} maps to {@link StreamEvent.ToolCallStarted}
 *       and its {@code item.completed} to {@link
 *       StreamEvent.ToolCallDone} (carrying the aggregated output +
 *       exit code).</li>
 *   <li>{@code turn.completed} — carries a {@code usage} block; mapped to
 *       {@link StreamEvent.TurnDone}. Codex bills against the user's
 *       OpenAI subscription, so no per-turn dollar cost is reported and
 *       the cost field is left at zero.</li>
 * </ul>
 *
 * <p>Unrecognized line types, unknown item types, and malformed JSON all
 * return an empty list rather than throwing — the same forward-compatible
 * tolerance {@link StreamJsonParser} applies, so a new Codex event type
 * can't crash a running turn.
 */
public class CodexJsonParser
        implements CliStreamParser
{
    private final ObjectMapper mapper;

    public CodexJsonParser(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public List<StreamEvent> parse(String line, Instant now)
    {
        requireNonNull(line, "line is null");
        requireNonNull(now, "now is null");
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            // Codex prints a plain "Reading additional input from
            // stdin..." preamble before the JSONL begins; skip any
            // non-JSON line.
            return ImmutableList.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(trimmed);
        }
        catch (Exception ignored) {
            return ImmutableList.of();
        }
        String type = root.path("type").asText("");
        return switch (type) {
            case "thread.started" -> ImmutableList.of(new StreamEvent.SessionStarted(
                    now, root.path("thread_id").asText(""), "", ""));
            case "item.started" -> parseItem(root.path("item"), now, /* started */ true);
            case "item.completed" -> parseItem(root.path("item"), now, /* started */ false);
            case "turn.completed" -> parseTurnCompleted(root.path("usage"), now);
            default -> ImmutableList.of();
        };
    }

    private List<StreamEvent> parseItem(JsonNode item, Instant now, boolean started)
    {
        if (item == null || item.isMissingNode()) {
            return ImmutableList.of();
        }
        String itemType = item.path("type").asText("");
        String itemId = item.path("id").asText("");
        return switch (itemType) {
            case "command_execution" -> started
                    ? ImmutableList.of(new StreamEvent.ToolCallStarted(
                            now, itemId, "command_execution", commandInputJson(item)))
                    : ImmutableList.of(new StreamEvent.ToolCallDone(
                            now, itemId, commandOutputJson(item), commandIsError(item)));
            // Prose + reasoning only matter once complete — Codex doesn't
            // stream partials in --json, so item.started for these carries
            // no text yet.
            case "agent_message" -> started
                    ? ImmutableList.of()
                    : ImmutableList.of(new StreamEvent.AssistantText(
                            now, item.path("text").asText("")));
            case "reasoning" -> started
                    ? ImmutableList.of()
                    : ImmutableList.of(
                            new StreamEvent.ThinkingStarted(now, item.path("text").asText("")),
                            new StreamEvent.ThinkingDone(now));
            default -> ImmutableList.of();
        };
    }

    /** {@code {"command": "<argv>"}} — the input shape the tool-call card
     *  and {@code ToolFileOps} read. Built through the mapper so the
     *  command string is correctly JSON-escaped. */
    private String commandInputJson(JsonNode item)
    {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", item.path("command").asText(""));
        return input.toString();
    }

    /** {@code {"output": "<aggregated>", "exitCode": <n>}} — the raw
     *  command result the tool-result card renders. */
    private String commandOutputJson(JsonNode item)
    {
        ObjectNode output = mapper.createObjectNode();
        output.put("output", item.path("aggregated_output").asText(""));
        JsonNode exit = item.path("exit_code");
        if (exit.isInt()) {
            output.put("exitCode", exit.intValue());
        }
        return output.toString();
    }

    private boolean commandIsError(JsonNode item)
    {
        JsonNode exit = item.path("exit_code");
        return exit.isInt() && exit.intValue() != 0;
    }

    private List<StreamEvent> parseTurnCompleted(JsonNode usage, Instant now)
    {
        long tokensIn = usage.path("input_tokens").asLong(0L);
        long tokensOut = usage.path("output_tokens").asLong(0L);
        return ImmutableList.of(new StreamEvent.TurnDone(
                now, /* durationMs */ 0L, /* costUsdMilli */ 0L, tokensIn, tokensOut));
    }
}
