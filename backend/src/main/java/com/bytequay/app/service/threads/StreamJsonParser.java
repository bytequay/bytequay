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
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Pure converter from one line of Claude Code's
 * {@code --output-format stream-json} stdout into zero or more
 * {@link StreamEvent}s.
 *
 * <p>One JSON line can fan out into multiple events because a single
 * assistant message may carry several content blocks (mixed text /
 * thinking / tool_use). We emit one event per block in source order so
 * the renderer doesn't have to know about the assistant-message
 * envelope.
 *
 * <p>Unrecognized lines return an empty list rather than throwing —
 * the CLI evolves and we don't want a new event type to crash the
 * parser. Malformed JSON also returns empty (callers log).
 */
public class StreamJsonParser
{
    private final ObjectMapper mapper;

    public StreamJsonParser(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Parse one stream-json line. {@code now} is the wall-clock to
     * stamp on emitted events — the source format does not include
     * timestamps, so we anchor against parse time.
     */
    public List<StreamEvent> parse(String line, Instant now)
    {
        requireNonNull(line, "line is null");
        requireNonNull(now, "now is null");
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return ImmutableList.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(trimmed);
        }
        catch (Exception ignored) {
            return ImmutableList.of();
        }
        if (!root.isObject()) {
            return ImmutableList.of();
        }
        String type = textOrEmpty(root, "type");
        return switch (type) {
            case "system" -> parseSystem(root, now);
            case "user" -> parseUserMessage(root, now);
            case "assistant" -> parseAssistantMessage(root, now);
            case "stream_event" -> parseStreamEvent(root, now);
            case "result" -> parseResult(root, now);
            default -> ImmutableList.of();
        };
    }

    /**
     * Per-token partial event the CLI emits when launched with
     * {@code --include-partial-messages}. Wraps the upstream Anthropic
     * SSE event verbatim. Two subtypes interest us:
     *
     * <ul>
     *   <li>{@code content_block_delta} with a {@code text_delta} —
     *       the chunk-by-chunk text growth we stream into the UI's
     *       in-flight assistant card.</li>
     *   <li>{@code message_delta} carrying a running {@code usage}
     *       object — surfaces in-flight token counts so the metrics
     *       panel climbs live instead of jumping at turn boundary.</li>
     * </ul>
     *
     * <p>Other subtypes (message_start/stop, content_block_start/stop,
     * ping) are ignored here — the full {@code assistant} envelope still
     * arrives at message_stop and feeds {@link #parseAssistantMessage},
     * and turn-level cost / tokens still come in via the trailing
     * {@code result} envelope.
     */
    private static List<StreamEvent> parseStreamEvent(JsonNode root, Instant now)
    {
        JsonNode event = root.path("event");
        String eventType = textOrEmpty(event, "type");
        if ("content_block_delta".equals(eventType)) {
            JsonNode delta = event.path("delta");
            if (!"text_delta".equals(textOrEmpty(delta, "type"))) {
                return ImmutableList.of();
            }
            String chunk = textOrEmpty(delta, "text");
            if (chunk.isEmpty()) {
                return ImmutableList.of();
            }
            int index = event.path("index").asInt(0);
            return ImmutableList.of(new StreamEvent.AssistantTextDelta(now, index, chunk));
        }
        // message_start carries the prompt's input_tokens (typically the
        // dominant number for any single model invocation) under
        // {@code event.message.usage}. Without this, the LIVE bar would
        // sit at "0 tokens" until the trailing message_delta finally
        // lands — Claude Code's CLI shows the input count immediately
        // at turn start, which is what users see and expect.
        if ("message_start".equals(eventType)) {
            JsonNode usage = event.path("message").path("usage");
            if (!usage.isObject()) {
                return ImmutableList.of();
            }
            long tokensIn = usage.path("input_tokens").asLong(0L);
            long tokensOut = usage.path("output_tokens").asLong(0L);
            if (tokensIn == 0L && tokensOut == 0L) {
                return ImmutableList.of();
            }
            return ImmutableList.of(new StreamEvent.UsageUpdated(now, tokensIn, tokensOut));
        }
        if ("message_delta".equals(eventType)) {
            JsonNode usage = event.path("usage");
            if (usage.isMissingNode() || !usage.isObject()) {
                return ImmutableList.of();
            }
            long tokensIn = usage.path("input_tokens").asLong(0L);
            long tokensOut = usage.path("output_tokens").asLong(0L);
            if (tokensIn == 0L && tokensOut == 0L) {
                return ImmutableList.of();
            }
            return ImmutableList.of(new StreamEvent.UsageUpdated(now, tokensIn, tokensOut));
        }
        return ImmutableList.of();
    }

    private static List<StreamEvent> parseSystem(JsonNode root, Instant now)
    {
        if (!"init".equals(textOrEmpty(root, "subtype"))) {
            return ImmutableList.of();
        }
        return ImmutableList.of(new StreamEvent.SessionStarted(
                now,
                textOrEmpty(root, "session_id"),
                textOrEmpty(root, "cwd"),
                textOrEmpty(root, "model")));
    }

    private static List<StreamEvent> parseUserMessage(JsonNode root, Instant now)
    {
        // Claude echoes the user's text back in stream-json. We
        // persist user text on send() so the conversation pane shows
        // it instantly — re-emitting from the parser would double up
        // every prompt. So skip plain-text user messages and only
        // surface tool_result blocks (which the agent emits to feed
        // tool output back into the loop).
        JsonNode message = root.path("message");
        JsonNode content = message.path("content");
        if (!content.isArray()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<StreamEvent> out = ImmutableList.builder();
        for (JsonNode block : content) {
            String blockType = textOrEmpty(block, "type");
            if ("tool_result".equals(blockType)) {
                String callId = textOrEmpty(block, "tool_use_id");
                JsonNode result = block.path("content");
                String outputJson = result.isMissingNode() ? "" : result.toString();
                boolean isError = block.path("is_error").asBoolean(false);
                out.add(new StreamEvent.ToolCallDone(now, callId, outputJson, isError));
            }
        }
        return out.build();
    }

    private static List<StreamEvent> parseAssistantMessage(JsonNode root, Instant now)
    {
        JsonNode content = root.path("message").path("content");
        if (!content.isArray()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<StreamEvent> out = ImmutableList.builder();
        for (JsonNode block : content) {
            String blockType = textOrEmpty(block, "type");
            switch (blockType) {
                case "text" -> out.add(new StreamEvent.AssistantText(now, textOrEmpty(block, "text")));
                case "thinking" -> {
                    out.add(new StreamEvent.ThinkingStarted(now, textOrEmpty(block, "thinking")));
                    out.add(new StreamEvent.ThinkingDone(now));
                }
                case "tool_use" -> out.add(new StreamEvent.ToolCallStarted(
                        now,
                        textOrEmpty(block, "id"),
                        textOrEmpty(block, "name"),
                        block.path("input").toString()));
                default -> {
                    // Skip unknown content block types.
                }
            }
        }
        return out.build();
    }

    private static List<StreamEvent> parseResult(JsonNode root, Instant now)
    {
        long durationMs = root.path("duration_ms").asLong(0L);
        long tokensIn = root.path("usage").path("input_tokens").asLong(0L);
        long tokensOut = root.path("usage").path("output_tokens").asLong(0L);
        long costUsdMilli = Math.round(root.path("total_cost_usd").asDouble(0.0d) * 1000.0d);
        StreamEvent turn = new StreamEvent.TurnDone(now, durationMs, costUsdMilli, tokensIn, tokensOut);
        boolean isError = root.path("is_error").asBoolean(false)
                || "error".equals(textOrEmpty(root, "subtype"));
        if (!isError) {
            return ImmutableList.of(turn);
        }
        String message = root.has("error") ? root.get("error").asText("turn failed")
                : "turn failed";
        return ImmutableList.of(turn, new StreamEvent.ErrorOccurred(now, message, true));
    }

    private static String textOrEmpty(JsonNode node, String field)
    {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : "";
    }
}
