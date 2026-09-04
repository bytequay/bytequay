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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeEventMapper
{
    private final ClaudeProtocolCodec codec = new ClaudeProtocolCodec(new ObjectMapper());
    private final ClaudeEventMapper mapper = new ClaudeEventMapper(
            SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")));

    @Test
    void mapsRealPartialFinalAndResultMessagesWithoutThinking()
    {
        ProviderAgentEvent.Message partial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,
                "delta":{"type":"text_delta","text":"hello from "}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"delta-1"}
                """, ProviderAgentEvent.Message.class);
        assertThat(partial.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
        assertThat(partial.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
        assertThat(partial.text()).isEqualTo("hello from ");
        ProviderAgentEvent.Message secondPartial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,
                "delta":{"type":"text_delta","text":"draft"}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"delta-2"}
                """, ProviderAgentEvent.Message.class);
        assertThat(partial.providerEventId()).isEmpty();
        assertThat(secondPartial.providerEventId()).isEmpty();
        assertThat(secondPartial.source().providerItemId()).isEqualTo(partial.source().providerItemId());

        List<ProviderAgentEvent> assistant = map("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"thinking","thinking":"hidden","signature":"private"},
                {"type":"text","text":"hello from ByteQuay"}],"usage":{"input_tokens":2,
                "cache_creation_input_tokens":16303,"cache_read_input_tokens":16015,
                "output_tokens":6}},"parent_tool_use_id":null,"session_id":"session-1",
                "uuid":"answer-1","timestamp":"2026-08-16T07:56:00.902Z"}
                """);
        assertThat(assistant).singleElement().isInstanceOfSatisfying(
                ProviderAgentEvent.Message.class,
                message -> {
                    assertThat(message.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
                    assertThat(message.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
                    assertThat(message.text()).isEqualTo("hello from ByteQuay");
                    assertThat(message.providerEventId()).contains("answer-1:text:1");
                    assertThat(message.source().providerItemId()).isEqualTo(partial.source().providerItemId());
                });

        List<ProviderAgentEvent> result = map("""
                {"type":"result","is_error":false,"duration_ms":4743,"duration_api_ms":4697,
                "num_turns":1,"session_id":"session-1","total_cost_usd":0.343245,
                "usage":{"input_tokens":2,"cache_creation_input_tokens":16303,
                "cache_read_input_tokens":16015,"output_tokens":23},"subtype":"success",
                "result":"hello from ByteQuay","terminal_reason":"completed","uuid":"result-1"}
                """);
        assertThat(result).filteredOn(ProviderAgentEvent.Message.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(ProviderAgentEvent.Message.class, message -> {
                    assertThat(message.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.ANSWER);
                    assertThat(message.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
                    assertThat(message.text()).isEqualTo("hello from ByteQuay");
                    assertThat(message.providerEventId()).contains("result-1:answer:0");
                    assertThat(message.source().providerItemId()).isEqualTo(partial.source().providerItemId());
                });
        assertThat(result).filteredOn(ProviderAgentEvent.Usage.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(ProviderAgentEvent.Usage.class, usage -> {
                    assertThat(usage.sample().inputTokens()).isEqualTo(32320);
                    assertThat(usage.sample().cachedInputTokens()).isEqualTo(16015);
                    assertThat(usage.sample().outputTokens()).isEqualTo(23);
                    assertThat(usage.sample().cost()).contains(new BigDecimal("0.343245"));
                    assertThat(usage.sample().costProvenance()).isEqualTo(CostProvenance.CLI_ESTIMATE);
                });

        List<ProviderAgentEvent> failed = map("""
                {"type":"result","is_error":true,"duration_ms":20,"duration_api_ms":10,
                "num_turns":1,"session_id":"session-1","total_cost_usd":0.01,
                "usage":{"input_tokens":1,"cache_creation_input_tokens":0,
                "cache_read_input_tokens":0,"output_tokens":1},"subtype":"error_during_execution",
                "errors":["provider failed"],"uuid":"result-error-1"}
                """);
        assertThat(failed).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(ProviderAgentEvent.Error.class,
                        error -> assertThat(error.message()).isEqualTo("provider failed")));
    }

    @Test
    void mapsTypedCommandsToolsHooksAndNestedTasksWithRedaction()
    {
        ProviderAgentEvent.Command started = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"tool-1","name":"Bash",
                "input":{"command":"echo known-secret","description":"run command"}}]},
                "parent_tool_use_id":"parent-1","session_id":"session-1","uuid":"assistant-1"}
                """, ProviderAgentEvent.Command.class);
        assertThat(started.argv()).containsExactly("echo [REDACTED]");

        ProviderAgentEvent.Command progress = only("""
                {"type":"tool_progress","tool_use_id":"tool-1","tool_name":"Bash",
                "elapsed_time_seconds":1.5,"parent_tool_use_id":"parent-1",
                "session_id":"session-1","uuid":"progress-1"}
                """, ProviderAgentEvent.Command.class);
        assertThat(progress.state()).isEqualTo(ProviderAgentEvent.ActivityState.PROGRESS);
        assertThat(progress.argv()).containsExactly("echo [REDACTED]");
        assertThat(progress.source().providerItemId()).isEqualTo(started.source().providerItemId());
        assertThat(progress.duration()).contains(java.time.Duration.ofMillis(1500));

        ProviderAgentEvent.Command completed = only("""
                {"type":"user","message":{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"tool-1","content":"known-secret output",
                "is_error":false}]},"parent_tool_use_id":"parent-1","session_id":"session-1",
                "uuid":"user-1"}
                """, ProviderAgentEvent.Command.class);
        assertThat(completed.state()).isEqualTo(ProviderAgentEvent.ActivityState.COMPLETED);
        assertThat(completed.output()).contains("[REDACTED] output");

        ProviderAgentEvent.Tool hook = only("""
                {"type":"system","subtype":"hook_response","hook_id":"hook-1",
                "hook_name":"PostToolUse:Bash","hook_event":"PostToolUse","output":"done",
                "exit_code":0,"outcome":"success","session_id":"session-1","uuid":"hook-event-1"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(hook.name()).isEqualTo("hook/PostToolUse:Bash");
        assertThat(hook.state()).isEqualTo(ProviderAgentEvent.ActivityState.COMPLETED);

        ProviderAgentEvent.Tool nested = only("""
                {"type":"system","subtype":"task_progress","task_id":"task-1",
                "description":"checking files","parent_tool_use_id":"parent-1",
                "session_id":"session-1","uuid":"task-event-1"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(nested.parentToolUseId()).contains("parent-1");
        assertThat(nested.state()).isEqualTo(ProviderAgentEvent.ActivityState.PROGRESS);
    }

    @Test
    void correlatesReindexedCompletionWithinItsParent()
    {
        ProviderAgentEvent.Message rootPartial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,
                "delta":{"type":"text_delta","text":"shared "}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"root-delta"}
                """, ProviderAgentEvent.Message.class);
        ProviderAgentEvent.Message rootSecondPartial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,
                "delta":{"type":"text_delta","text":"answer"}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"root-delta-2"}
                """, ProviderAgentEvent.Message.class);
        assertThat(rootSecondPartial.source().providerItemId()).isEqualTo(rootPartial.source().providerItemId());

        ProviderAgentEvent.Message childPartial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,
                "delta":{"type":"text_delta","text":"shared answer"}},"session_id":"session-1",
                "parent_tool_use_id":"task-tool-1","uuid":"child-delta"}
                """, ProviderAgentEvent.Message.class);
        ProviderAgentEvent.Message childFinal = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"text","text":"shared answer"}]},"parent_tool_use_id":"task-tool-1",
                "session_id":"session-1","uuid":"child-answer"}
                """, ProviderAgentEvent.Message.class);
        assertThat(childFinal.source().providerItemId()).isEqualTo(childPartial.source().providerItemId());
        assertThat(childFinal.source().providerItemId()).isNotEqualTo(rootPartial.source().providerItemId());

        ProviderAgentEvent.Message rootFinal = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"text","text":"shared answer"}]},"parent_tool_use_id":null,
                "session_id":"session-1","uuid":"root-answer"}
                """, ProviderAgentEvent.Message.class);
        assertThat(rootFinal.source().providerItemId()).isEqualTo(rootPartial.source().providerItemId());
    }

    @Test
    void doesNotGuessBetweenMatchingReindexedStreams()
    {
        ProviderAgentEvent.Message firstPartial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":1,
                "delta":{"type":"text_delta","text":"same"}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"first-delta"}
                """, ProviderAgentEvent.Message.class);
        ProviderAgentEvent.Message secondPartial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":2,
                "delta":{"type":"text_delta","text":"same"}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"second-delta"}
                """, ProviderAgentEvent.Message.class);

        ProviderAgentEvent.Message completed = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"text","text":"same"}]},"parent_tool_use_id":null,
                "session_id":"session-1","uuid":"answer"}
                """, ProviderAgentEvent.Message.class);

        assertThat(completed.source().providerItemId())
                .isNotEqualTo(firstPartial.source().providerItemId())
                .isNotEqualTo(secondPartial.source().providerItemId());
    }

    @Test
    void clearsItemCorrelationAtTheTerminalResult()
    {
        ProviderAgentEvent.Message partial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,
                "delta":{"type":"text_delta","text":"first"}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"first-delta"}
                """, ProviderAgentEvent.Message.class);
        only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"text","text":"first"}]},"parent_tool_use_id":null,
                "session_id":"session-1","uuid":"first-assistant"}
                """, ProviderAgentEvent.Message.class);
        ProviderAgentEvent.Message firstAnswer = map("""
                {"type":"result","is_error":false,"duration_ms":1,"duration_api_ms":1,
                "num_turns":1,"session_id":"session-1","usage":{"input_tokens":1,
                "cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1},
                "subtype":"success","result":"first","uuid":"first-result"}
                """).stream()
                .filter(ProviderAgentEvent.Message.class::isInstance)
                .map(ProviderAgentEvent.Message.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(firstAnswer.source().providerItemId()).isEqualTo(partial.source().providerItemId());

        ProviderAgentEvent.Message secondAnswer = map("""
                {"type":"result","is_error":false,"duration_ms":1,"duration_api_ms":1,
                "num_turns":1,"session_id":"session-1","usage":{"input_tokens":1,
                "cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1},
                "subtype":"success","result":"second","uuid":"second-result"}
                """).stream()
                .filter(ProviderAgentEvent.Message.class::isInstance)
                .map(ProviderAgentEvent.Message.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(secondAnswer.source().providerItemId()).isNotEqualTo(firstAnswer.source().providerItemId());
    }

    @Test
    void boundsAndRedactsPartialText()
    {
        ProviderAgentEvent.Message partial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,
                "delta":{"type":"text_delta","text":"%s"}},"session_id":"session-1",
                "parent_tool_use_id":null,"uuid":"delta-large"}
                """.formatted("known-secret" + "x".repeat(70_000)), ProviderAgentEvent.Message.class);

        assertThat(partial.text())
                .hasSize(64 * 1024)
                .startsWith("[REDACTED]")
                .doesNotContain("known-secret");
    }

    @Test
    void rejectsMissingRequiredFieldsAndIgnoresUnknownMessages()
    {
        assertThatThrownBy(() -> codec.decode("""
                {"type":"assistant","message":{"role":"assistant","content":[]}}
                """))
                .hasRootCauseMessage("sessionId is null");

        assertThat(map("""
                {"type":"future_message","future":true}
                """)).isEmpty();

        ProviderAgentEvent.Tool progress = only("""
                {"type":"tool_progress","tool_use_id":"tool-1","tool_name":"Bash",
                "elapsed_time_seconds":1.5,"parent_tool_use_id":null,
                "session_id":"session-1","uuid":"progress-1"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(progress.summary()).isEqualTo("Running for 1.5 seconds");
    }

    private List<ProviderAgentEvent> map(String json)
    {
        return mapper.map(codec.decode(json));
    }

    private <T extends ProviderAgentEvent> T only(String json, Class<T> type)
    {
        List<ProviderAgentEvent> events = map(json);
        assertThat(events).hasSize(1);
        return type.cast(events.getFirst());
    }
}
