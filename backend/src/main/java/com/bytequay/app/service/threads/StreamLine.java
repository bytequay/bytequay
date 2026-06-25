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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Typed wire shape for one line of Claude Code's {@code --output-format
 * stream-json} stdout, plus the nested Anthropic SSE event types those
 * lines wrap. {@link StreamJsonParser} deserialises straight into a
 * variant and pattern-switches; {@link
 * com.bytequay.app.service.ai.ClaudeReviewer} reads the SSE inner types
 * directly when consuming the raw Anthropic streaming API.
 *
 * <h3>Forward compatibility</h3>
 *
 * Every polymorphic interface in this file carries
 * {@code defaultImpl = Unknown.class} on its {@link JsonTypeInfo}. A
 * future Anthropic event type (or a future CLI line shape) deserialises
 * to the matching {@code Unknown} variant instead of throwing, so the
 * parser drops it cleanly rather than crashing the in-flight turn.
 * Combined with {@link JsonIgnoreProperties}{@code (ignoreUnknown=true)}
 * at the interface level, this keeps the parser tolerant of additive
 * upstream changes without losing the static-typing win for the cases
 * we already handle.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
        defaultImpl = StreamLine.Unknown.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = StreamLine.System.class, name = "system"),
        @JsonSubTypes.Type(value = StreamLine.User.class, name = "user"),
        @JsonSubTypes.Type(value = StreamLine.Assistant.class, name = "assistant"),
        @JsonSubTypes.Type(value = StreamLine.StreamEvent.class, name = "stream_event"),
        @JsonSubTypes.Type(value = StreamLine.Result.class, name = "result")
})
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface StreamLine
        permits StreamLine.System, StreamLine.User, StreamLine.Assistant,
                StreamLine.StreamEvent, StreamLine.Result, StreamLine.Unknown
{
    /** Top-level {@code type=system} line. {@code subtype="init"} is
     *  the session-start; other subtypes are routed to {@link Unknown}
     *  by the parser. */
    record System(
            String subtype,
            @JsonProperty("session_id") String sessionId,
            String cwd,
            String model)
            implements StreamLine {}

    /** Top-level {@code type=user} line. The CLI echoes the user's
     *  text back, plus surfaces {@code tool_result} blocks the agent
     *  fed back into the loop. Plain-text user blocks are skipped at
     *  the parser. */
    record User(Message message)
            implements StreamLine {}

    /** Top-level {@code type=assistant} line. {@link Message#content}
     *  carries a mix of text / thinking / tool_use blocks, in source
     *  order. */
    record Assistant(Message message)
            implements StreamLine {}

    /** Per-token partial event the CLI emits when launched with
     *  {@code --include-partial-messages}. Wraps the upstream Anthropic
     *  SSE event verbatim. */
    record StreamEvent(SseEvent event)
            implements StreamLine {}

    /** Trailing turn-level summary line. {@code is_error} is true when
     *  the turn failed before {@code stop_reason=end_turn}. */
    record Result(
            String subtype,
            @JsonProperty("duration_ms") long durationMs,
            Usage usage,
            @JsonProperty("total_cost_usd") double totalCostUsd,
            @JsonProperty("is_error") boolean isError,
            String error)
            implements StreamLine {}

    /** Catch-all for line shapes this parser doesn't model. The
     *  {@link JsonTypeInfo#defaultImpl()} on the interface routes any
     *  un-mapped {@code "type"} value here. */
    record Unknown() implements StreamLine {}

    /** Inner {@code message} object shared by {@link User} and {@link
     *  Assistant} lines. The on-wire shape carries extra fields like
     *  {@code role} / {@code id} / {@code stop_reason} that the parser
     *  doesn't act on — {@link JsonIgnoreProperties} keeps the
     *  record deserialiser tolerant of those. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(List<ContentBlock> content) {}

    /** Token usage block shared by {@link Result} and
     *  {@link SseEvent.MessageDelta}. Anthropic adds new counters over
     *  time (cache reads, prompt-cache writes); ignore the ones we
     *  don't tally. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens) {}

    /** Wrapper used by {@link SseEvent.MessageStart} — Anthropic puts
     *  the prompt-side {@link Usage} a level deeper, inside the start
     *  event's {@code message} object. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageWithUsage(Usage usage) {}

    /** One block inside an assistant or user message's content array. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
            defaultImpl = ContentBlock.Unknown.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContentBlock.Text.class, name = "text"),
            @JsonSubTypes.Type(value = ContentBlock.Thinking.class, name = "thinking"),
            @JsonSubTypes.Type(value = ContentBlock.ToolUse.class, name = "tool_use"),
            @JsonSubTypes.Type(value = ContentBlock.ToolResult.class, name = "tool_result")
    })
    @JsonIgnoreProperties(ignoreUnknown = true)
    sealed interface ContentBlock
            permits ContentBlock.Text, ContentBlock.Thinking,
                    ContentBlock.ToolUse, ContentBlock.ToolResult, ContentBlock.Unknown
    {
        record Text(String text) implements ContentBlock {}

        record Thinking(String thinking) implements ContentBlock {}

        /** {@code input} is the agent's tool-call argument object;
         *  forwarded verbatim to the runtime, so kept as raw JSON. */
        record ToolUse(String id, String name, JsonNode input)
                implements ContentBlock {}

        /** The agent's reply to a prior tool_use. {@code content} is
         *  the tool's output verbatim — string or object depending on
         *  the tool — so kept as raw JSON. */
        record ToolResult(
                @JsonProperty("tool_use_id") String toolUseId,
                JsonNode content,
                @JsonProperty("is_error") boolean isError)
                implements ContentBlock {}

        record Unknown() implements ContentBlock {}
    }

    /** An Anthropic SSE event, as wrapped inside a {@link StreamEvent}
     *  line (or read directly off the Anthropic streaming API by
     *  {@link com.bytequay.app.service.ai.ClaudeReviewer}). */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
            defaultImpl = SseEvent.Unknown.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SseEvent.ContentBlockDelta.class,
                    name = "content_block_delta"),
            @JsonSubTypes.Type(value = SseEvent.MessageStart.class,
                    name = "message_start"),
            @JsonSubTypes.Type(value = SseEvent.MessageDelta.class,
                    name = "message_delta")
    })
    @JsonIgnoreProperties(ignoreUnknown = true)
    sealed interface SseEvent
            permits SseEvent.ContentBlockDelta, SseEvent.MessageStart,
                    SseEvent.MessageDelta, SseEvent.Unknown
    {
        /** Per-token text growth — the chunk we stream into the UI's
         *  in-flight assistant card. */
        record ContentBlockDelta(int index, SseDelta delta)
                implements SseEvent {}

        /** Prompt-side usage advertised at turn start. */
        record MessageStart(MessageWithUsage message)
                implements SseEvent {}

        /** In-flight token counters that climb across the turn. */
        record MessageDelta(Usage usage)
                implements SseEvent {}

        record Unknown() implements SseEvent {}
    }

    /** Inner {@code delta} object of a {@link SseEvent.ContentBlockDelta}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",
            defaultImpl = SseDelta.Unknown.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = SseDelta.TextDelta.class, name = "text_delta"),
            @JsonSubTypes.Type(value = SseDelta.ThinkingDelta.class, name = "thinking_delta")
    })
    @JsonIgnoreProperties(ignoreUnknown = true)
    sealed interface SseDelta
            permits SseDelta.TextDelta, SseDelta.ThinkingDelta, SseDelta.Unknown
    {
        record TextDelta(String text) implements SseDelta {}

        /** A {@code thinking_delta} frame — the next chunk of extended-thinking
         *  text for the in-flight thinking block. */
        record ThinkingDelta(String thinking) implements SseDelta {}

        record Unknown() implements SseDelta {}
    }
}
