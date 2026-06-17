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

/**
 * Pins {@link CodexJsonParser} against the real {@code codex exec --json}
 * event shapes captured from the CLI (codex-cli 0.139.0).
 */
class TestCodexJsonParser
{
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    private final CodexJsonParser parser = new CodexJsonParser(new ObjectMapper());

    @Test
    void mapsThreadStartedToSessionStartedWithThreadIdAsSessionId()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"thread.started\",\"thread_id\":\"019ed46d-5df9-7151\"}", NOW);

        assertThat(events).hasSize(1);
        StreamEvent.SessionStarted started = (StreamEvent.SessionStarted) events.get(0);
        assertThat(started.sessionId()).isEqualTo("019ed46d-5df9-7151");
        assertThat(started.timestamp()).isEqualTo(NOW);
    }

    @Test
    void ignoresTurnStartedAndUnknownTypes()
    {
        assertThat(parser.parse("{\"type\":\"turn.started\"}", NOW)).isEmpty();
        assertThat(parser.parse("{\"type\":\"something.new\"}", NOW)).isEmpty();
    }

    @Test
    void skipsTheNonJsonStdinPreamble()
    {
        assertThat(parser.parse("Reading additional input from stdin...", NOW)).isEmpty();
    }

    @Test
    void mapsCompletedAgentMessageToAssistantText()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\","
                        + "\"type\":\"agent_message\",\"text\":\"done\"}}", NOW);

        assertThat(events).hasSize(1);
        assertThat(((StreamEvent.AssistantText) events.get(0)).text()).isEqualTo("done");
    }

    @Test
    void startedAgentMessageEmitsNothingSinceJsonModeDoesNotStreamPartials()
    {
        assertThat(parser.parse(
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\","
                        + "\"type\":\"agent_message\",\"text\":\"\"}}", NOW)).isEmpty();
    }

    @Test
    void mapsCommandExecutionStartToToolCallStartedWithCommandInput()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\","
                        + "\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc 'echo hi'\","
                        + "\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"in_progress\"}}", NOW);

        assertThat(events).hasSize(1);
        StreamEvent.ToolCallStarted call = (StreamEvent.ToolCallStarted) events.get(0);
        assertThat(call.callId()).isEqualTo("item_0");
        assertThat(call.toolName()).isEqualTo("command_execution");
        assertThat(call.inputJson()).contains("echo hi");
    }

    @Test
    void mapsCommandExecutionCompletionToToolCallDoneWithExitCode()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\","
                        + "\"type\":\"command_execution\",\"command\":\"/bin/zsh -lc 'echo hi'\","
                        + "\"aggregated_output\":\"hi\\n\",\"exit_code\":0,\"status\":\"completed\"}}", NOW);

        assertThat(events).hasSize(1);
        StreamEvent.ToolCallDone done = (StreamEvent.ToolCallDone) events.get(0);
        assertThat(done.callId()).isEqualTo("item_0");
        assertThat(done.isError()).isFalse();
        assertThat(done.outputJson()).contains("hi");
    }

    @Test
    void flagsNonZeroExitAsToolError()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\","
                        + "\"type\":\"command_execution\",\"command\":\"false\","
                        + "\"aggregated_output\":\"\",\"exit_code\":1,\"status\":\"completed\"}}", NOW);

        assertThat(((StreamEvent.ToolCallDone) events.get(0)).isError()).isTrue();
    }

    @Test
    void mapsTurnCompletedToTurnDoneWithTokenUsageAndZeroCost()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":28825,"
                        + "\"cached_input_tokens\":4992,\"output_tokens\":21,"
                        + "\"reasoning_output_tokens\":14}}", NOW);

        assertThat(events).hasSize(1);
        StreamEvent.TurnDone done = (StreamEvent.TurnDone) events.get(0);
        assertThat(done.tokensIn()).isEqualTo(28825L);
        assertThat(done.tokensOut()).isEqualTo(21L);
        // Codex bills against the OpenAI subscription — no per-turn dollar
        // cost is reported, so the cost field stays zero.
        assertThat(done.costUsdMilli()).isZero();
    }

    @Test
    void mapsCompletedReasoningToThinkingStartedThenDone()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_3\","
                        + "\"type\":\"reasoning\",\"text\":\"weighing options\"}}", NOW);

        assertThat(events).hasSize(2);
        assertThat(((StreamEvent.ThinkingStarted) events.get(0)).summary()).isEqualTo("weighing options");
        assertThat(events.get(1)).isInstanceOf(StreamEvent.ThinkingDone.class);
    }

    @Test
    void ignoresUnknownItemTypesForwardCompatibly()
    {
        assertThat(parser.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_4\","
                        + "\"type\":\"file_change\",\"path\":\"/x\"}}", NOW)).isEmpty();
    }

    @Test
    void threadStartedWithoutIdYieldsAnEmptySessionId()
    {
        List<StreamEvent> events = parser.parse("{\"type\":\"thread.started\"}", NOW);

        assertThat(((StreamEvent.SessionStarted) events.get(0)).sessionId()).isEmpty();
    }

    @Test
    void treatsNullExitCodeOnCompletionAsNonError()
    {
        List<StreamEvent> events = parser.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_5\","
                        + "\"type\":\"command_execution\",\"command\":\"sleep\","
                        + "\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"completed\"}}", NOW);

        assertThat(((StreamEvent.ToolCallDone) events.get(0)).isError()).isFalse();
    }

    @Test
    void returnsEmptyOnMalformedJson()
    {
        assertThat(parser.parse("{not valid json", NOW)).isEmpty();
    }

    @Test
    void returnsEmptyOnBlankLine()
    {
        assertThat(parser.parse("   ", NOW)).isEmpty();
    }
}
