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
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class TestClaudeEventMapper
{
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new Jdk8Module()).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ClaudeEventMapper mapper = new ClaudeEventMapper(SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")), Optional.empty(), true);

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
                    assertThat(usage.sample().semantics()).isEqualTo(ProviderUsageSample.Semantics.FINAL_WITH_SESSION_COST);
                });

        List<ProviderAgentEvent> failed = map("""
                {"type":"result","is_error":true,"duration_ms":20,"duration_api_ms":10,
                "num_turns":1,"session_id":"session-1","total_cost_usd":0.01,
                "usage":{"input_tokens":1,"cache_creation_input_tokens":0,
                "cache_read_input_tokens":0,"output_tokens":1},"subtype":"error_during_execution",
                "errors":["provider failed"],"uuid":"result-error-1"}
                """);
        assertThat(failed).anySatisfy(event -> assertThat(event)
                .isInstanceOfSatisfying(ProviderAgentEvent.Error.class, error -> assertThat(error.message()).isEqualTo("provider failed")));
    }

    @Test
    void reportsTheCostAsItsOwnOnAReleaseThatBeginsEveryProcessAtZero()
    {
        ClaudeEventMapper older = new ClaudeEventMapper(SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty(), false);
        List<ProviderAgentEvent> result = older.map(ClaudeProtocol.decode(objectMapper, """
                {"type":"result","is_error":false,"duration_ms":20,"duration_api_ms":10,"num_turns":1,"session_id":"session-1","total_cost_usd":0.42,
                "usage":{"input_tokens":2,"cache_creation_input_tokens":3,"cache_read_input_tokens":5,"output_tokens":7},"subtype":"success","result":"done","uuid":"result-1"}
                """));
        assertThat(result).filteredOn(ProviderAgentEvent.Usage.class::isInstance).singleElement()
                .isInstanceOfSatisfying(ProviderAgentEvent.Usage.class, usage -> assertThat(usage.sample()).isEqualTo(new ProviderUsageSample(
                        Optional.of("result-1"), ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, 10, 5, 7, Optional.of(new BigDecimal("0.42")))));
    }

    @Test
    void publishesTodoWritePlanOnlyAfterSuccessAndPreservesProgressAndClearing()
    {
        assertThat(todoWrite("todos-1", "null", """
                [{"content":"Read known-secret context","status":"completed","activeForm":"Reading context"},
                 {"content":"Implement fix","status":"in_progress"},{"content":"Run tests","status":"pending"}]
                """)).singleElement().isInstanceOf(ProviderAgentEvent.Tool.class);
        ProviderAgentEvent.Plan plan = todoResult("todos-1", "null", false).stream()
                .filter(ProviderAgentEvent.Plan.class::isInstance).map(ProviderAgentEvent.Plan.class::cast).findFirst().orElseThrow();
        assertThat(plan.items()).extracting(ProviderAgentEvent.PlanItem::text, ProviderAgentEvent.PlanItem::state).containsExactly(
                tuple("Read [REDACTED] context", ProviderAgentEvent.PlanItemState.COMPLETED),
                tuple("Implement fix", ProviderAgentEvent.PlanItemState.IN_PROGRESS),
                tuple("Run tests", ProviderAgentEvent.PlanItemState.PENDING));
        assertThat(plan.providerEventId()).contains("todos-1:plan");
        assertThat(plan.source().providerThreadId()).contains("session-1");
        assertThat(plan.source().providerItemId()).contains("todos-1");
        assertThat(plan.source().parentProviderThreadId()).isEmpty();
        assertThat(todoResult("todos-1", "null", false)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);

        todoWrite("todos-2", "null", """
                [{"content":"Implement fix","status":"completed"},{"content":"Run tests","status":"in_progress"}]
                """);
        assertThat(todoResult("todos-2", "null", false)).filteredOn(ProviderAgentEvent.Plan.class::isInstance)
                .singleElement().satisfies(event -> assertThat(((ProviderAgentEvent.Plan) event).items())
                        .extracting(ProviderAgentEvent.PlanItem::state)
                        .containsExactly(ProviderAgentEvent.PlanItemState.COMPLETED, ProviderAgentEvent.PlanItemState.IN_PROGRESS));
        todoWrite("todos-clear", "null", "[]");
        assertThat(todoResult("todos-clear", "null", false)).filteredOn(ProviderAgentEvent.Plan.class::isInstance)
                .singleElement().satisfies(event -> assertThat(((ProviderAgentEvent.Plan) event).items()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "[null]", "[{}]", "[{\"content\":\" \",\"status\":\"pending\"}]",
            "[{\"content\":3,\"status\":\"pending\"}]", "[{\"content\":\"Step\",\"status\":3}]",
            "[{\"content\":\"Step\",\"status\":\"unknown\"}]",
            "[{\"content\":\"Valid\",\"status\":\"completed\"},{\"content\":\"Missing status\"}]"})
    void ignoresAnInvalidOrIncompleteTodoListWithoutGuessingAPlan(String todos)
    {
        assertThat(todoWrite("todos-invalid", "null", todos)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);
        assertThat(todoResult("todos-invalid", "null", false)).singleElement().isInstanceOf(ProviderAgentEvent.Tool.class);
    }

    @Test
    void failedUnmatchedAndMisownedTodoCallsCannotReplaceAPlan()
    {
        String todos = "[{\"content\":\"Implement\",\"status\":\"in_progress\"}]";
        todoWrite("failed", "null", todos);
        assertThat(todoResult("failed", "null", true)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);
        assertThat(todoResult("unknown", "null", false)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);
        todoWrite("orphan", "\"unknown-parent\"", todos);
        assertThat(todoResult("orphan", "\"unknown-parent\"", false)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);
        startAgent("Agent", false);
        todoWrite("misowned", "\"spawn-1\"", todos);
        assertThat(todoResult("misowned", "null", false)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);
        todoWrite("child", "\"spawn-1\"", todos);
        assertThat(todoResult("child", "\"spawn-1\"", false)).filteredOn(ProviderAgentEvent.Plan.class::isInstance)
                .singleElement().satisfies(event -> {
                    assertThat(event.source().providerThreadId()).contains("claude-agent:spawn-1");
                    assertThat(event.source().parentProviderThreadId()).contains("session-1");
                });
        assertThat(map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"- [ ] Implement"}]},
                "session_id":"session-1","uuid":"prose-plan"}
                """)).noneMatch(ProviderAgentEvent.Plan.class::isInstance);
    }

    private List<ProviderAgentEvent> todoWrite(String toolId, String parent, String todos)
    {
        return map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"%s","name":"TodoWrite","input":{"todos":%s}}]},
                "session_id":"session-1","parent_tool_use_id":%s,"uuid":"%s-start"}
                """.formatted(toolId, todos, parent, toolId));
    }

    private List<ProviderAgentEvent> todoResult(String toolId, String parent, boolean failed)
    {
        return map("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"%s","content":"Todo update","is_error":%s}]},
                "session_id":"session-1","parent_tool_use_id":%s,"uuid":"%s-result"}
                """.formatted(toolId, failed, parent, toolId));
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
        assertThat(ImmutableList.of(started, progress, completed)).noneMatch(ProviderAgentEvent.Command::background);

        ProviderAgentEvent.Tool fetch = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"fetch-1","name":"WebFetch",
                "input":{"url":"https://github.com/delta-io/delta/blob/master/PROTOCOL.md","prompt":"Find the checkpoint rules"}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-fetch"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(fetch.summary()).isEqualTo("https://github.com/delta-io/delta/blob/master/PROTOCOL.md");
        ProviderAgentEvent.Tool web = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"search-1","name":"WebSearch","input":{"query":"delta checkpoint protocol"}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-search"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(web.summary()).isEqualTo("delta checkpoint protocol");

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
        assertThat(nested.source().providerItemId()).contains("task-1");
        assertThat(nested.state()).isEqualTo(ProviderAgentEvent.ActivityState.PROGRESS);
    }

    @Test
    void describesFileStepsWithPathsAsTheWorktreeNamesThem()
    {
        map("""
                {"type":"system","subtype":"init","session_id":"session-1","uuid":"init-1","cwd":"/work/repository/"}
                """);
        ProviderAgentEvent.Tool read = only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"read-1","name":"Read","input":{"file_path":"/work/repository/core/main/DomainTranslator.java","offset":240,"limit":141}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-read"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(read.description()).contains("Read core/main/DomainTranslator.java · lines 240–380");
        ProviderAgentEvent.Tool readResult = only("""
                {"type":"user","message":{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"read-1","content":"final class DomainTranslator {}","is_error":false}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"user-read"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(readResult.description()).isEqualTo(read.description());

        assertThat(only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"grep-1","name":"Grep","input":{"pattern":"extractDecimal","path":"/work/repository/plugin/files"}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-grep"}
                """, ProviderAgentEvent.Tool.class).description()).contains("Searched extractDecimal in plugin/files");
        assertThat(only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"glob-1","name":"Glob","input":{"pattern":"**/*.java","path":"/work/repository"}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-glob"}
                """, ProviderAgentEvent.Tool.class).description()).contains("Listed **/*.java");
        assertThat(only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"read-2","name":"Read","input":{"file_path":"/home/me/.m2/settings.xml","offset":7}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-read-2"}
                """, ProviderAgentEvent.Tool.class).description()).contains("Read /home/me/.m2/settings.xml · from line 7");
        // Any tool's input decodes into the same record, so an MCP tool may use these names for other types.
        assertThat(only("""
                {"type":"assistant","message":{"role":"assistant","content":[
                {"type":"tool_use","id":"mcp-1","name":"mcp__notes__find","input":{"path":["a","b"],"pattern":{"any":true},"limit":"all"}}]},
                "parent_tool_use_id":null,"session_id":"session-1","uuid":"assistant-mcp"}
                """, ProviderAgentEvent.Tool.class).description()).isEmpty();
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
        assertThatThrownBy(() -> ClaudeProtocol.decode(objectMapper, """
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

    @Test
    void mapsNativeForegroundChildAndGrandchildWithStableSources()
            throws IOException
    {
        // Captured from Claude Code 2.1.263; IDs normalized and hidden reasoning and unrelated metadata removed.
        List<ProviderAgentEvent> events = replay("subagents-foreground-2.1.263.jsonl");

        assertThat(mapper.hasFinishedWork()).isTrue();
        assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .extracting(event -> event.source().providerThreadId().orElseThrow(), event -> event.source().parentProviderThreadId().orElseThrow())
                .containsExactly(tuple("claude-agent:tool-1", "session-1"), tuple("claude-agent:tool-2", "claude-agent:tool-1"));
        assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_COMPLETED)
                .hasSize(2);
        ProviderAgentEvent.Message grandchild = events.stream()
                .filter(event -> event instanceof ProviderAgentEvent.Message message
                        && message.messageKind() == ProviderAgentEvent.MessageKind.ANSWER
                        && message.source().providerThreadId().filter("claude-agent:tool-2"::equals).isPresent())
                .map(ProviderAgentEvent.Message.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(grandchild.text()).isEqualTo("GRANDCHILD_OK");
        assertThat(grandchild.source().logicalAgentPath()).contains("Protocol probe to grandchild");
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(ProviderAgentEvent.Message.class, message -> {
            assertThat(message.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
            assertThat(message.source().providerItemId()).isEqualTo(grandchild.source().providerItemId());
        }));
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(ProviderAgentEvent.Tool.class, tool -> {
            assertThat(tool.source().providerItemId()).contains("tool-2");
            assertThat(tool.source().providerThreadId()).contains("claude-agent:tool-1");
            assertThat(tool.source().parentProviderThreadId()).contains("session-1");
        }));
    }

    @Test
    void retainsBackgroundChildAcrossNativeRootFollowUp()
            throws IOException
    {
        List<ProviderAgentEvent> events = replay("subagents-background-2.1.263.jsonl");

        assertThat(mapper.hasFinishedWork()).isTrue();
        assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.SESSION_OPENED)
                .hasSize(2);
        assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                        && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.AGENT_STARTED)
                .singleElement().satisfies(event -> assertThat(event.source().providerThreadId()).contains("claude-agent:tool-1"));
        assertThat(events).filteredOn(event -> event instanceof ProviderAgentEvent.Message message
                        && message.messageKind() == ProviderAgentEvent.MessageKind.ANSWER
                        && message.source().parentProviderThreadId().isPresent())
                .singleElement().satisfies(event -> assertThat(((ProviderAgentEvent.Message) event).text()).isEqualTo("CHILD_OK"));
    }

    @Test
    void waitsForBackgroundTasksAndRetainsChildPartialAcrossRootResult()
    {
        startAgent("Agent", true);
        map("""
                {"type":"system","subtype":"task_started","task_id":"child-1","tool_use_id":"spawn-1",
                "task_type":"local_agent","is_backgrounded":true,"session_id":"session-1","uuid":"started"}
                """);
        map("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"spawn-1","content":"launched"}]},
                "tool_use_result":{"status":"async_launched","agentId":"child-1"},"session_id":"session-1","uuid":"launched"}
                """);
        ProviderAgentEvent.Message partial = only("""
                {"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"child answer"}},
                "session_id":"session-1","parent_tool_use_id":"spawn-1","uuid":"child-partial"}
                """, ProviderAgentEvent.Message.class);
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
        map("""
                {"type":"system","subtype":"background_tasks_changed","tasks":[],"session_id":"session-1","uuid":"empty-inventory"}
                """);
        assertThat(mapper.hasFinishedWork()).isFalse();
        ProviderAgentEvent.Message completed = only("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"child answer"}]},
                "session_id":"session-1","parent_tool_use_id":"spawn-1","uuid":"child-final"}
                """, ProviderAgentEvent.Message.class);
        assertThat(completed.source()).isEqualTo(partial.source());
        map("""
                {"type":"system","subtype":"task_notification","task_id":"child-1","status":"completed","session_id":"session-1","uuid":"finished"}
                """);
        assertThat(mapper.hasFinishedWork()).isTrue();

        map("""
                {"type":"system","subtype":"task_updated","task_id":"child-1","patch":{"status":"running"},"session_id":"session-1","uuid":"resumed"}
                """);
        assertThat(mapper.hasFinishedWork()).isFalse();
        map("""
                {"type":"system","subtype":"task_notification","task_id":"child-1","status":"stopped","session_id":"session-1","uuid":"stopped"}
                """);
        assertThat(mapper.hasFinishedWork()).isTrue();
        map("""
                {"type":"system","subtype":"init","session_id":"session-1","uuid":"root-follow-up"}
                """);
        assertThat(mapper.hasFinishedWork()).isFalse();
    }

    @Test
    void marksEveryEventOfABackgroundBashCall()
    {
        ProviderAgentEvent.Command started = only("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"background-1","name":"Bash",
                "input":{"command":"./mvnw test","run_in_background":true}}]},"session_id":"session-1","uuid":"background-start"}
                """, ProviderAgentEvent.Command.class);
        assertThat(started.background()).isTrue();
        ProviderAgentEvent.Command launched = only("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"background-1",
                "content":"Command running in background with ID: shell-1"}]},"session_id":"session-1","uuid":"background-launched"}
                """, ProviderAgentEvent.Command.class);
        assertThat(launched.background()).isTrue();
        assertThat(launched.exitCode()).isEmpty();
    }

    @Test
    void recordsTheExitCodeClaudeReportsForAFinishedCommand()
    {
        ProviderAgentEvent.Command passed = finishedCommand("passed", "\"BUILD SUCCESS\"", false, "");
        assertThat(passed.state()).isEqualTo(ProviderAgentEvent.ActivityState.COMPLETED);
        assertThat(passed.exitCode()).contains(0);
        ProviderAgentEvent.Command failed = finishedCommand("failed", "\"Exit code 2\\nBUILD FAILURE\"", true, "");
        assertThat(failed.state()).isEqualTo(ProviderAgentEvent.ActivityState.FAILED);
        assertThat(failed.exitCode()).contains(2);
        assertThat(finishedCommand("timeout", "\"Command timed out after 2m 0.0s\"", true, "").exitCode()).isEmpty();
        assertThat(finishedCommand("interrupted", "\"partial output\"", false, ",\"tool_use_result\":{\"stdout\":\"\",\"stderr\":\"\",\"interrupted\":true}").exitCode()).isEmpty();
    }

    private ProviderAgentEvent.Command finishedCommand(String id, String content, boolean error, String metadata)
    {
        map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"%s","name":"Bash","input":{"command":"./mvnw test"}}]},
                "session_id":"session-1","uuid":"%s-start"}
                """.formatted(id, id));
        return only("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"%s","content":%s,"is_error":%s}]}%s,
                "session_id":"session-1","uuid":"%s-end"}
                """.formatted(id, content, error, metadata, id), ProviderAgentEvent.Command.class);
    }

    @Test
    void routesChildCommandsAndCompletesLegacyForegroundTaskWithoutStructuredResult()
    {
        startAgent("Task", false);
        ProviderAgentEvent.Command command = only("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"command-1","name":"Bash","input":{"command":"pwd"}}]},
                "session_id":"session-1","parent_tool_use_id":"spawn-1","uuid":"command-start"}
                """, ProviderAgentEvent.Command.class);
        assertThat(command.source().providerThreadId()).contains("claude-agent:spawn-1");
        assertThat(command.source().parentProviderThreadId()).contains("session-1");
        ProviderAgentEvent.Command completed = only("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"command-1","content":"/worktree"}]},
                "session_id":"session-1","parent_tool_use_id":"spawn-1","uuid":"command-end"}
                """, ProviderAgentEvent.Command.class);
        assertThat(completed.source()).isEqualTo(command.source());
        map("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"spawn-1","content":"done"}]},
                "session_id":"session-1","uuid":"task-end"}
                """);
        result();
        assertThat(mapper.hasFinishedWork()).isTrue();
    }

    @Test
    void doesNotGuessChildIdentityOrTreatRootResultAsToolCompletion()
    {
        ProviderAgentEvent.Message orphan = only("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"unclassified"}]},
                "session_id":"session-1","parent_tool_use_id":"unseen-tool","uuid":"orphan"}
                """, ProviderAgentEvent.Message.class);
        assertThat(orphan.source().providerThreadId()).isEmpty();
        assertThat(orphan.source().parentProviderThreadId()).isEmpty();
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
    }

    @Test
    void backgroundCommandAndUnknownTaskStatusPreventCompletionProof()
    {
        only("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"bash-1","name":"Bash","input":{"command":"sleep 1"}}]},
                "session_id":"session-1","uuid":"bash-start"}
                """, ProviderAgentEvent.Command.class);
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
        only("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"bash-1","content":"backgrounded"}]},
                "tool_use_result":{"backgroundTaskId":"background-1"},"session_id":"session-1","uuid":"bash-background"}
                """, ProviderAgentEvent.Command.class);
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
        map("""
                {"type":"system","subtype":"task_notification","task_id":"background-1","status":"unknown","session_id":"session-1","uuid":"unknown-status"}
                """);
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
    }

    @Test
    void mapsACompactionBoundaryToTheSharedCompactionActivity()
    {
        ProviderAgentEvent.Tool compaction = only("""
                {"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":180000},
                "session_id":"session-1","uuid":"compacted"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(compaction.name()).isEqualTo(ProviderAgentEvent.Tool.CONTEXT_COMPACTION);
        assertThat(compaction.state()).isEqualTo(ProviderAgentEvent.ActivityState.COMPLETED);
        assertThat(compaction.source().parentProviderThreadId()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requesting", "compacting"})
    void requiresANewResultAfterRootWorkResumes(String status)
    {
        result();
        assertThat(mapper.hasFinishedWork()).isTrue();
        map("""
                {"type":"system","subtype":"status","status":"%s","session_id":"session-1"}
                """.formatted(status));
        assertThat(mapper.hasFinishedWork()).isFalse();
        map("""
                {"type":"system","subtype":"status","status":null,"session_id":"session-1"}
                """);
        assertThat(mapper.hasFinishedWork()).isFalse();
        result();
        assertThat(mapper.hasFinishedWork()).isTrue();
    }

    @Test
    void correlatesHeartbeatProgressWithoutCreatingOrRevivingToolWork()
    {
        ProviderAgentEvent.Command started = only("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"bash-1","name":"Bash","input":{"command":"npm ci"}}]},
                "session_id":"session-1","uuid":"bash-start"}
                """, ProviderAgentEvent.Command.class);
        for (int heartbeat = 0; heartbeat < 2; heartbeat++) {
            // Claude 2.1.304 parents a heartbeat on the tool it observes.
            ProviderAgentEvent.Command progress = only("""
                    {"type":"tool_progress","tool_use_id":"bash-1-heartbeat-%s","tool_name":"Bash","heartbeat":true,
                    "elapsed_time_seconds":30,"parent_tool_use_id":"bash-1","session_id":"session-1","uuid":"heartbeat-%s"}
                    """.formatted(heartbeat, heartbeat), ProviderAgentEvent.Command.class);
            assertThat(progress.source()).isEqualTo(started.source());
            assertThat(progress.argv()).containsExactly("npm ci");
            assertThat(progress.state()).isEqualTo(ProviderAgentEvent.ActivityState.PROGRESS);
        }
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
        map("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"bash-1","content":"done"}]},
                "session_id":"session-1","uuid":"bash-end"}
                """);
        result();
        assertThat(mapper.hasFinishedWork()).isTrue();
        assertThat(map("""
                {"type":"tool_progress","tool_use_id":"bash-1-heartbeat-2","tool_name":"Bash","heartbeat":true,
                "elapsed_time_seconds":90,"parent_tool_use_id":"bash-1","session_id":"session-1","uuid":"late-heartbeat"}
                """)).isEmpty();
        assertThat(mapper.hasFinishedWork()).isTrue();
    }

    @Test
    void keepsNestedHeartbeatProgressOnItsKnownChildTool()
    {
        startAgent("Agent", false);
        map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"bash-1","name":"Bash","input":{"command":"sleep 30"}}]},
                "parent_tool_use_id":"spawn-1","session_id":"session-1","uuid":"bash-start"}
                """);
        ProviderAgentEvent.Command progress = only("""
                {"type":"tool_progress","tool_use_id":"bash-1-heartbeat-0","tool_name":"Bash","heartbeat":true,
                "elapsed_time_seconds":30,"parent_tool_use_id":"spawn-1","session_id":"session-1","uuid":"heartbeat"}
                """, ProviderAgentEvent.Command.class);

        assertThat(progress.source().providerItemId()).contains("bash-1");
        assertThat(progress.source().providerThreadId()).contains("claude-agent:spawn-1");
        assertThat(progress.source().parentProviderThreadId()).contains("session-1");
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "missing-heartbeat-0, Bash, null, true",
            "bash-1-heartbeat-invalid, Bash, null, true",
            "bash-1-heartbeat-0, Bash, null, false",
            "bash-1-heartbeat-0, Bash, null, null",
            "bash-1-heartbeat-0, Read, null, true",
            "bash-1-heartbeat-0, Bash, \"other-agent\", true",
    })
    void unrecognizedHeartbeatProgressStillBlocksCompletion(String toolId, String toolName, String parent, String heartbeat)
    {
        map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"bash-1","name":"Bash","input":{"command":"npm ci"}}]},
                "session_id":"session-1","uuid":"bash-start"}
                """);
        ProviderAgentEvent.Tool progress = only("""
                {"type":"tool_progress","tool_use_id":"%s","tool_name":"%s","heartbeat":%s,
                "elapsed_time_seconds":30,"parent_tool_use_id":%s,"session_id":"session-1","uuid":"heartbeat"}
                """.formatted(toolId, toolName, heartbeat, parent), ProviderAgentEvent.Tool.class);
        assertThat(progress.source().providerItemId()).contains(toolId);
        map("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"bash-1","content":"done"}]},
                "session_id":"session-1","uuid":"bash-end"}
                """);
        result();
        assertThat(mapper.hasFinishedWork()).isFalse();
    }

    @Test
    void readsMcpResultContentPartsAsTheirText()
    {
        map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"mcp-1",
                "name":"mcp__bytequay__review_get_context","input":{"kind":"assignment"}}]},"session_id":"session-1","uuid":"mcp-start"}
                """);
        ProviderAgentEvent.Tool completed = only("""
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"mcp-1",
                "content":[{"type":"text","text":"{\\"step\\":2,\\"kind\\":\\"VALIDATE\\"}"}]}]},
                "session_id":"session-1","uuid":"mcp-end"}
                """, ProviderAgentEvent.Tool.class);
        assertThat(completed.name()).isEqualTo("mcp__bytequay__review_get_context");
        assertThat(completed.summary()).isEqualTo("{\"step\":2,\"kind\":\"VALIDATE\"}");
    }

    private List<ProviderAgentEvent> replay(String name)
            throws IOException
    {
        ImmutableList.Builder<ProviderAgentEvent> events = ImmutableList.builder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream(name), StandardCharsets.UTF_8))) {
            reader.lines().forEach(line -> events.addAll(map(line)));
        }
        return events.build();
    }

    private void startAgent(String toolName, boolean background)
    {
        map("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"spawn-1","name":"%s",
                "input":{"description":"Inspect files","prompt":"Find bugs","run_in_background":%s}}]},"session_id":"session-1","uuid":"spawn"}
                """.formatted(toolName, background));
    }

    private void result()
    {
        map("""
                {"type":"result","is_error":false,"duration_ms":1,"duration_api_ms":1,"num_turns":1,"session_id":"session-1",
                "usage":{"input_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1},
                "subtype":"success","result":"Root answer","uuid":"root-result"}
                """);
    }

    private List<ProviderAgentEvent> map(String json)
    {
        return mapper.map(ClaudeProtocol.decode(objectMapper, json));
    }

    private <T extends ProviderAgentEvent> T only(String json, Class<T> type)
    {
        List<ProviderAgentEvent> events = map(json);
        assertThat(events).hasSize(1);
        return type.cast(events.getFirst());
    }
}
