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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestStreamJsonParser
{
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");

    private final StreamJsonParser parser = new StreamJsonParser(new ObjectMapper());

    @Test
    void parsesSystemInitIntoSessionStarted()
    {
        String line = """
                {"type":"system","subtype":"init","session_id":"sess-1",
                 "cwd":"/Users/jack/code","model":"claude-sonnet-4.6"}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(1);
        StreamEvent.SessionStarted started = (StreamEvent.SessionStarted) events.get(0);
        assertThat(started.sessionId()).isEqualTo("sess-1");
        assertThat(started.cwd()).isEqualTo("/Users/jack/code");
        assertThat(started.model()).isEqualTo("claude-sonnet-4.6");
        assertThat(started.timestamp()).isEqualTo(NOW);
    }

    @Test
    void ignoresSystemEnvelopesWithoutInitSubtype()
    {
        assertThat(parser.parse(
                "{\"type\":\"system\",\"subtype\":\"compact_boundary\"}",
                NOW)).isEmpty();
    }

    @Test
    void skipsPlainTextUserEchoesToAvoidDoubleCounting()
    {
        // Claude echoes the user's text in stream-json; the session
        // persists user text at send-time so re-emitting here would
        // make every prompt appear twice in the conversation pane.
        String line = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"fix the bug\"}}";

        assertThat(parser.parse(line, NOW)).isEmpty();
    }

    @Test
    void parsesUserToolResultIntoToolCallDone()
    {
        String line = """
                {"type":"user","message":{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"toolu_42",
                   "content":"hello world","is_error":false}
                ]}}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(1);
        StreamEvent.ToolCallDone done = (StreamEvent.ToolCallDone) events.get(0);
        assertThat(done.callId()).isEqualTo("toolu_42");
        assertThat(done.outputJson()).contains("hello world");
        assertThat(done.isError()).isFalse();
    }

    @Test
    void parsesAssistantMixedContentInOrder()
    {
        // Realistic shape: assistant emits a thinking block, then text,
        // then a tool_use — all in one message envelope.
        String line = """
                {"type":"assistant","message":{"id":"msg_1","role":"assistant",
                 "content":[
                   {"type":"thinking","thinking":"hmm — read the file first"},
                   {"type":"text","text":"Reading src/main.ts now."},
                   {"type":"tool_use","id":"toolu_99","name":"Read",
                    "input":{"path":"src/main.ts"}}
                 ]}}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.ThinkingStarted.class);
        assertThat(((StreamEvent.ThinkingStarted) events.get(0)).summary())
                .isEqualTo("hmm — read the file first");
        assertThat(events.get(1)).isInstanceOf(StreamEvent.ThinkingDone.class);
        assertThat(((StreamEvent.AssistantText) events.get(2)).text())
                .isEqualTo("Reading src/main.ts now.");
        StreamEvent.ToolCallStarted call = (StreamEvent.ToolCallStarted) events.get(3);
        assertThat(call.callId()).isEqualTo("toolu_99");
        assertThat(call.toolName()).isEqualTo("Read");
        assertThat(call.inputJson()).contains("src/main.ts");
    }

    @Test
    void parsesResultIntoTurnDoneWithMetrics()
    {
        String line = """
                {"type":"result","subtype":"success","is_error":false,
                 "duration_ms":12345,"total_cost_usd":0.01234,
                 "usage":{"input_tokens":120,"output_tokens":340}}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(1);
        StreamEvent.TurnDone turn = (StreamEvent.TurnDone) events.get(0);
        assertThat(turn.durationMs()).isEqualTo(12_345L);
        assertThat(turn.tokensIn()).isEqualTo(120L);
        assertThat(turn.tokensOut()).isEqualTo(340L);
        // 0.01234 USD × 1000 → 12 (rounded to nearest milli).
        assertThat(turn.costUsdMilli()).isEqualTo(12L);
    }

    @Test
    void parsesErrorResultIntoTurnDonePlusErrorOccurred()
    {
        String line = """
                {"type":"result","subtype":"error","is_error":true,
                 "error":"rate limit exceeded","duration_ms":50,
                 "usage":{"input_tokens":10,"output_tokens":0}}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamEvent.TurnDone.class);
        StreamEvent.ErrorOccurred err = (StreamEvent.ErrorOccurred) events.get(1);
        assertThat(err.message()).isEqualTo("rate limit exceeded");
        assertThat(err.recoverable()).isTrue();
    }

    @Test
    void parsesStreamEventTextDeltaIntoAssistantTextDelta()
    {
        // --include-partial-messages emits Anthropic-shaped stream
        // events; we surface text_delta chunks for the in-flight card.
        String line = """
                {"type":"stream_event","event":{
                  "type":"content_block_delta","index":2,
                  "delta":{"type":"text_delta","text":"Reading "}}}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(1);
        StreamEvent.AssistantTextDelta delta = (StreamEvent.AssistantTextDelta) events.get(0);
        assertThat(delta.blockIndex()).isEqualTo(2);
        assertThat(delta.textChunk()).isEqualTo("Reading ");
    }

    @Test
    void parsesThinkingDeltaIntoThinkingTextDelta()
    {
        // Extended thinking streams as thinking_delta frames; we surface them
        // so the persisted thought isn't empty (the final thinking block is
        // signature-only under --include-partial-messages).
        String line = """
                {"type":"stream_event","event":{
                  "type":"content_block_delta","index":0,
                  "delta":{"type":"thinking_delta","thinking":"Let me search "}}}
                """;

        List<StreamEvent> events = parser.parse(line, NOW);

        assertThat(events).hasSize(1);
        StreamEvent.ThinkingTextDelta delta = (StreamEvent.ThinkingTextDelta) events.get(0);
        assertThat(delta.textChunk()).isEqualTo("Let me search ");
    }

    @Test
    void ignoresStreamEventEnvelopesOtherThanTextDelta()
    {
        // message_start / content_block_start / ping / content_block_stop
        // / message_stop all flow past silently — the final assembled
        // assistant envelope still arrives and feeds the canonical
        // AssistantText path. (message_delta with usage is handled
        // separately — see parsesMessageDeltaUsageAsUsageUpdated.)
        assertThat(parser.parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\","
                        + "\"message\":{\"id\":\"msg_x\"}}}",
                NOW)).isEmpty();
        assertThat(parser.parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                        + "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}}",
                NOW)).isEmpty();
        assertThat(parser.parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"input_json_delta\","
                        + "\"partial_json\":\"{\\\"a\\\":1}\"}}}",
                NOW)).isEmpty();
    }

    @Test
    void parsesMessageDeltaUsageAsUsageUpdated()
    {
        // message_delta envelopes carry the running per-turn usage as
        // the model streams. Surfacing them lets the metrics panel
        // climb live instead of jumping only at TurnDone.
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                        + "\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":1234,\"output_tokens\":42}}}",
                NOW);
        assertThat(events).hasSize(1);
        StreamEvent.UsageUpdated usage = (StreamEvent.UsageUpdated) events.get(0);
        assertThat(usage.tokensIn()).isEqualTo(1234L);
        assertThat(usage.tokensOut()).isEqualTo(42L);
    }

    @Test
    void dropsMessageDeltaWithZeroUsage()
    {
        // Some envelopes carry an empty usage object before the model
        // has produced any output; suppress those so the live counter
        // doesn't flicker through a transient zero state.
        assertThat(parser.parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\","
                        + "\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                NOW)).isEmpty();
    }

    @Test
    void dropsEmptyTextDeltaChunks()
    {
        // The CLI occasionally emits zero-length deltas as part of the
        // streaming protocol; dropping them keeps the in-flight card
        // free of spurious activity.
        assertThat(parser.parse(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"\"}}}",
                NOW)).isEmpty();
    }

    @Test
    void returnsEmptyForBlankAndMalformedInput()
    {
        assertThat(parser.parse("", NOW)).isEmpty();
        assertThat(parser.parse("   \t  ", NOW)).isEmpty();
        assertThat(parser.parse("not json at all", NOW)).isEmpty();
        assertThat(parser.parse("[]", NOW)).isEmpty();
    }

    @Test
    void returnsEmptyForUnknownEnvelopeType()
    {
        assertThat(parser.parse("{\"type\":\"future_kind\",\"foo\":1}", NOW)).isEmpty();
    }
}
