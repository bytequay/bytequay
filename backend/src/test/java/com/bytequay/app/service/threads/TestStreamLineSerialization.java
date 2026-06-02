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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polymorphic deserialisation coverage for {@link StreamLine} and its
 * nested SSE / content-block hierarchies. {@link StreamJsonParser} and
 * {@link com.bytequay.app.service.ai.ClaudeReviewer} both rely on
 * Jackson routing each {@code "type"} discriminator to the right record
 * variant; these tests pin that contract so a regression to the
 * {@code @JsonSubTypes} list — or the {@code defaultImpl = Unknown}
 * forward-compatibility fallback — fails here before reaching the
 * streaming pipeline.
 *
 * <p>Behavioural coverage of the parser itself lives in
 * {@code TestStreamJsonParser}; this suite focuses on the wire
 * contract.
 */
class TestStreamLineSerialization
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void systemLineRoutesToSystemVariant()
            throws Exception
    {
        String json = "{\"type\":\"system\",\"subtype\":\"init\","
                + "\"session_id\":\"sess-1\",\"cwd\":\"/tmp\",\"model\":\"claude-sonnet-4.6\"}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        assertThat(line).isInstanceOfSatisfying(StreamLine.System.class, sys -> {
            assertThat(sys.subtype()).isEqualTo("init");
            assertThat(sys.sessionId()).isEqualTo("sess-1");
            assertThat(sys.cwd()).isEqualTo("/tmp");
            assertThat(sys.model()).isEqualTo("claude-sonnet-4.6");
        });
    }

    @Test
    void userLineParsesNestedToolResultContentBlock()
            throws Exception
    {
        String json = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                + "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_42\","
                + "\"content\":\"hello\",\"is_error\":false}]}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        assertThat(line).isInstanceOf(StreamLine.User.class);
        StreamLine.User user = (StreamLine.User) line;
        assertThat(user.message().content()).hasSize(1);
        assertThat(user.message().content().get(0))
                .isInstanceOfSatisfying(StreamLine.ContentBlock.ToolResult.class, tr -> {
                    assertThat(tr.toolUseId()).isEqualTo("toolu_42");
                    assertThat(tr.content().asText()).isEqualTo("hello");
                    assertThat(tr.isError()).isFalse();
                });
    }

    @Test
    void assistantLineParsesMixedContentBlocksInOrder()
            throws Exception
    {
        String json = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"hmm\"},"
                + "{\"type\":\"text\",\"text\":\"hi\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Read\","
                + "\"input\":{\"path\":\"x.ts\"}}]}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.Assistant a = (StreamLine.Assistant) line;
        List<StreamLine.ContentBlock> blocks = a.message().content();
        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).isInstanceOf(StreamLine.ContentBlock.Thinking.class);
        assertThat(blocks.get(1)).isInstanceOf(StreamLine.ContentBlock.Text.class);
        assertThat(blocks.get(2)).isInstanceOfSatisfying(StreamLine.ContentBlock.ToolUse.class, tu -> {
            assertThat(tu.id()).isEqualTo("toolu_1");
            assertThat(tu.name()).isEqualTo("Read");
            assertThat(tu.input().path("path").asText()).isEqualTo("x.ts");
        });
    }

    @Test
    void streamEventLineRoutesContentBlockDeltaToTextDelta()
            throws Exception
    {
        String json = "{\"type\":\"stream_event\",\"event\":"
                + "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.StreamEvent se = (StreamLine.StreamEvent) line;
        StreamLine.SseEvent.ContentBlockDelta cbd = (StreamLine.SseEvent.ContentBlockDelta) se.event();
        assertThat(cbd.index()).isZero();
        assertThat(cbd.delta()).isInstanceOfSatisfying(
                StreamLine.SseDelta.TextDelta.class,
                td -> assertThat(td.text()).isEqualTo("hello"));
    }

    @Test
    void streamEventMessageStartCarriesPromptUsage()
            throws Exception
    {
        String json = "{\"type\":\"stream_event\",\"event\":"
                + "{\"type\":\"message_start\",\"message\":"
                + "{\"id\":\"msg_1\",\"role\":\"assistant\","
                + "\"usage\":{\"input_tokens\":1024,\"output_tokens\":0}}}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.StreamEvent se = (StreamLine.StreamEvent) line;
        StreamLine.SseEvent.MessageStart ms = (StreamLine.SseEvent.MessageStart) se.event();
        assertThat(ms.message().usage().inputTokens()).isEqualTo(1024L);
        assertThat(ms.message().usage().outputTokens()).isZero();
    }

    @Test
    void streamEventMessageDeltaCarriesRunningUsage()
            throws Exception
    {
        String json = "{\"type\":\"stream_event\",\"event\":"
                + "{\"type\":\"message_delta\","
                + "\"usage\":{\"input_tokens\":1024,\"output_tokens\":256}}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.StreamEvent se = (StreamLine.StreamEvent) line;
        StreamLine.SseEvent.MessageDelta md = (StreamLine.SseEvent.MessageDelta) se.event();
        assertThat(md.usage().inputTokens()).isEqualTo(1024L);
        assertThat(md.usage().outputTokens()).isEqualTo(256L);
    }

    @Test
    void resultLineCarriesTurnMetrics()
            throws Exception
    {
        String json = "{\"type\":\"result\",\"subtype\":\"success\","
                + "\"duration_ms\":1234,"
                + "\"usage\":{\"input_tokens\":2000,\"output_tokens\":500},"
                + "\"total_cost_usd\":0.0125,\"is_error\":false}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.Result r = (StreamLine.Result) line;
        assertThat(r.subtype()).isEqualTo("success");
        assertThat(r.durationMs()).isEqualTo(1234L);
        assertThat(r.usage().inputTokens()).isEqualTo(2000L);
        assertThat(r.usage().outputTokens()).isEqualTo(500L);
        assertThat(r.totalCostUsd()).isEqualTo(0.0125d);
        assertThat(r.isError()).isFalse();
    }

    @Test
    void unknownTopLevelTypeRoutesToUnknownVariant()
            throws Exception
    {
        // Anthropic / the CLI may add new line types; the defaultImpl
        // catches those rather than throwing mid-stream. The parser
        // turns Unknown into an empty event list.
        String json = "{\"type\":\"future_event\",\"payload\":\"whatever\"}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        assertThat(line).isInstanceOf(StreamLine.Unknown.class);
    }

    @Test
    void unknownSseEventTypeRoutesToUnknownInsideStreamEvent()
            throws Exception
    {
        // content_block_start / content_block_stop / ping / error all
        // fall through to SseEvent.Unknown.
        String json = "{\"type\":\"stream_event\",\"event\":"
                + "{\"type\":\"ping\"}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.StreamEvent se = (StreamLine.StreamEvent) line;
        assertThat(se.event()).isInstanceOf(StreamLine.SseEvent.Unknown.class);
    }

    @Test
    void unknownContentBlockTypeRoutesToUnknownVariant()
            throws Exception
    {
        // The assistant content array may grow new block types
        // (e.g. "image", "redacted_thinking"); each surfaces as
        // ContentBlock.Unknown and is skipped at the parser.
        String json = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"image\",\"source\":{\"data\":\"…\"}}]}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.Assistant a = (StreamLine.Assistant) line;
        assertThat(a.message().content()).hasSize(1);
        assertThat(a.message().content().get(0)).isInstanceOf(StreamLine.ContentBlock.Unknown.class);
    }

    @Test
    void unknownSseDeltaTypeRoutesToUnknownInsideContentBlockDelta()
            throws Exception
    {
        // input_json_delta is a real Anthropic delta type we don't
        // currently surface; it should land on SseDelta.Unknown rather
        // than crash the per-token parse.
        String json = "{\"type\":\"stream_event\",\"event\":"
                + "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"x\\\":\"}}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.StreamEvent se = (StreamLine.StreamEvent) line;
        StreamLine.SseEvent.ContentBlockDelta cbd = (StreamLine.SseEvent.ContentBlockDelta) se.event();
        assertThat(cbd.delta()).isInstanceOf(StreamLine.SseDelta.Unknown.class);
    }

    @Test
    void unknownFieldsAreToleratedOnDeserialisation()
            throws Exception
    {
        // @JsonIgnoreProperties(ignoreUnknown=true) is on every level
        // of the hierarchy so additive upstream changes don't break the
        // parse. Pile a few future-looking fields onto a known line and
        // confirm it still resolves.
        String json = "{\"type\":\"system\",\"subtype\":\"init\","
                + "\"session_id\":\"s1\",\"cwd\":\"/x\",\"model\":\"claude\","
                + "\"future_capabilities\":[\"vision\",\"audio\"],"
                + "\"experiment_flag\":true}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        assertThat(line).isInstanceOf(StreamLine.System.class);
    }

    @Test
    void sseEventCanBeDeserialisedDirectlyForRawAnthropicStreams()
            throws Exception
    {
        // ClaudeReviewer reads SSE events off the Anthropic API
        // directly, not via the CLI's stream-json envelope. The inner
        // sealed interface has to be parseable on its own — i.e. the
        // polymorphism config travels with the interface, not the
        // outer StreamLine wrapper.
        String json = "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}";

        StreamLine.SseEvent event = mapper.readValue(json, StreamLine.SseEvent.class);

        StreamLine.SseEvent.ContentBlockDelta cbd = (StreamLine.SseEvent.ContentBlockDelta) event;
        StreamLine.SseDelta.TextDelta td = (StreamLine.SseDelta.TextDelta) cbd.delta();
        assertThat(td.text()).isEqualTo("hi");
    }

    @Test
    void toolUseInputIsPreservedAsJsonNode()
            throws Exception
    {
        // tool_use forwards the agent's arguments verbatim — they're
        // an opaque JSON object whose schema the tool owns, not us.
        // Keep them as JsonNode and assert the round-trip preserves the
        // exact shape.
        String json = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Edit\","
                + "\"input\":{\"file\":\"a.ts\",\"old\":\"foo\",\"new\":\"bar\"}}]}}";

        StreamLine line = mapper.readValue(json, StreamLine.class);

        StreamLine.Assistant a = (StreamLine.Assistant) line;
        StreamLine.ContentBlock.ToolUse tu =
                (StreamLine.ContentBlock.ToolUse) a.message().content().get(0);
        JsonNode input = tu.input();
        assertThat(input.path("file").asText()).isEqualTo("a.ts");
        assertThat(input.path("old").asText()).isEqualTo("foo");
        assertThat(input.path("new").asText()).isEqualTo("bar");
    }
}
