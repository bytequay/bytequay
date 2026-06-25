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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Strings.nullToEmpty;
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
 * parser. Malformed JSON also returns empty (callers log). The
 * tolerance is structural: the wire shape is deserialised into
 * {@link StreamLine}, whose polymorphic interfaces all advertise a
 * {@code defaultImpl = Unknown} so unmapped {@code "type"} values land
 * on a benign variant instead of throwing.
 */
public class StreamJsonParser
        implements CliStreamParser
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
    @Override
    public List<StreamEvent> parse(String line, Instant now)
    {
        requireNonNull(line, "line is null");
        requireNonNull(now, "now is null");
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return ImmutableList.of();
        }
        StreamLine streamLine;
        try {
            streamLine = mapper.readValue(trimmed, StreamLine.class);
        }
        catch (Exception ignored) {
            return ImmutableList.of();
        }
        return switch (streamLine) {
            case StreamLine.System sys -> parseSystem(sys, now);
            case StreamLine.User user -> parseUserMessage(user, now);
            case StreamLine.Assistant assistant -> parseAssistantMessage(assistant, now);
            case StreamLine.StreamEvent se -> parseStreamEvent(se, now);
            case StreamLine.Result result -> parseResult(result, now);
            case StreamLine.Unknown ignored -> ImmutableList.of();
        };
    }

    private static List<StreamEvent> parseSystem(StreamLine.System sys, Instant now)
    {
        if (!"init".equals(sys.subtype())) {
            return ImmutableList.of();
        }
        return ImmutableList.of(new StreamEvent.SessionStarted(
                now,
                nullToEmpty(sys.sessionId()),
                nullToEmpty(sys.cwd()),
                nullToEmpty(sys.model())));
    }

    private static List<StreamEvent> parseUserMessage(StreamLine.User user, Instant now)
    {
        // Claude echoes the user's text back in stream-json. We
        // persist user text on send() so the conversation pane shows
        // it instantly — re-emitting from the parser would double up
        // every prompt. So skip plain-text user messages and only
        // surface tool_result blocks (which the agent emits to feed
        // tool output back into the loop).
        if (user.message() == null || user.message().content() == null) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<StreamEvent> out = ImmutableList.builder();
        for (StreamLine.ContentBlock block : user.message().content()) {
            if (block instanceof StreamLine.ContentBlock.ToolResult tr) {
                String outputJson = tr.content() == null ? "" : tr.content().toString();
                out.add(new StreamEvent.ToolCallDone(
                        now, nullToEmpty(tr.toolUseId()), outputJson, tr.isError()));
            }
        }
        return out.build();
    }

    private static List<StreamEvent> parseAssistantMessage(StreamLine.Assistant assistant, Instant now)
    {
        if (assistant.message() == null || assistant.message().content() == null) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<StreamEvent> out = ImmutableList.builder();
        for (StreamLine.ContentBlock block : assistant.message().content()) {
            switch (block) {
                case StreamLine.ContentBlock.Text text ->
                        out.add(new StreamEvent.AssistantText(now, nullToEmpty(text.text())));
                case StreamLine.ContentBlock.Thinking thinking -> {
                    out.add(new StreamEvent.ThinkingStarted(now, nullToEmpty(thinking.thinking())));
                    out.add(new StreamEvent.ThinkingDone(now));
                }
                case StreamLine.ContentBlock.ToolUse tu ->
                        out.add(new StreamEvent.ToolCallStarted(
                                now,
                                nullToEmpty(tu.id()),
                                nullToEmpty(tu.name()),
                                tu.input() == null ? "null" : tu.input().toString()));
                // ToolResult blocks belong in user-role messages; if
                // the CLI ever emits one inside an assistant envelope
                // we skip rather than mis-route it. Unknown is the
                // forward-compat catch-all.
                case StreamLine.ContentBlock.ToolResult ignored -> {}
                case StreamLine.ContentBlock.Unknown ignored -> {}
            }
        }
        return out.build();
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
     *   <li>{@code message_start} / {@code message_delta} carrying a
     *       running {@code usage} object — surfaces in-flight token
     *       counts so the metrics panel climbs live instead of jumping
     *       at turn boundary.</li>
     * </ul>
     *
     * <p>Other subtypes (content_block_start/stop, ping) land on
     * {@link StreamLine.SseEvent.Unknown} via the {@code defaultImpl}
     * and produce no events here — the full {@code assistant} envelope
     * still arrives at message_stop and feeds {@link
     * #parseAssistantMessage}, and turn-level cost / tokens still come
     * in via the trailing {@code result} envelope.
     */
    private static List<StreamEvent> parseStreamEvent(StreamLine.StreamEvent line, Instant now)
    {
        StreamLine.SseEvent event = line.event();
        if (event == null) {
            return ImmutableList.of();
        }
        return switch (event) {
            case StreamLine.SseEvent.ContentBlockDelta cbd -> {
                if (cbd.delta() instanceof StreamLine.SseDelta.TextDelta td
                        && td.text() != null
                        && !td.text().isEmpty()) {
                    yield ImmutableList.of(new StreamEvent.AssistantTextDelta(
                            now, cbd.index(), td.text()));
                }
                // Thinking text streams as thinking_delta frames; stitch them so
                // the persisted thought isn't empty (the final assistant
                // message's thinking block is signature-only in partial mode).
                if (cbd.delta() instanceof StreamLine.SseDelta.ThinkingDelta tk
                        && tk.thinking() != null
                        && !tk.thinking().isEmpty()) {
                    yield ImmutableList.of(new StreamEvent.ThinkingTextDelta(now, tk.thinking()));
                }
                yield ImmutableList.of();
            }
            case StreamLine.SseEvent.MessageStart ms ->
                    usageEvents(ms.message() == null ? null : ms.message().usage(), now);
            case StreamLine.SseEvent.MessageDelta md -> usageEvents(md.usage(), now);
            case StreamLine.SseEvent.Unknown ignored -> ImmutableList.of();
        };
    }

    /** Emit a {@link StreamEvent.UsageUpdated} when the running token
     *  counters have actually moved off zero — otherwise the metrics
     *  panel would flap on every empty SSE frame. */
    private static List<StreamEvent> usageEvents(StreamLine.Usage usage, Instant now)
    {
        if (usage == null) {
            return ImmutableList.of();
        }
        if (usage.inputTokens() == 0L && usage.outputTokens() == 0L) {
            return ImmutableList.of();
        }
        return ImmutableList.of(new StreamEvent.UsageUpdated(
                now, usage.inputTokens(), usage.outputTokens()));
    }

    private static List<StreamEvent> parseResult(StreamLine.Result result, Instant now)
    {
        StreamLine.Usage usage = result.usage();
        long tokensIn = usage == null ? 0L : usage.inputTokens();
        long tokensOut = usage == null ? 0L : usage.outputTokens();
        long costUsdMilli = Math.round(result.totalCostUsd() * 1000.0d);
        StreamEvent turn = new StreamEvent.TurnDone(
                now, result.durationMs(), costUsdMilli, tokensIn, tokensOut);
        boolean isError = result.isError() || "error".equals(result.subtype());
        if (!isError) {
            return ImmutableList.of(turn);
        }
        String message = result.error() == null || result.error().isEmpty()
                ? "turn failed"
                : result.error();
        return ImmutableList.of(turn, new StreamEvent.ErrorOccurred(now, message, true));
    }
}
