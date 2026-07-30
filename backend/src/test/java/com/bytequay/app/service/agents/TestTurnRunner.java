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
package com.bytequay.app.service.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the shared provider tool-round loop. The
 * Anthropic wire shape is locked here (the thread-agent
 * characterization suite covers the OpenAI-compatible dialect through
 * the ds4 seam); plus the executor round-trip and the hook-driven
 * abort that callers use for budget caps.
 */
class TestTurnRunner
{
    private static final String ANTHROPIC_TOOL_ROUND = """
            data: {"type":"message_start","message":{"usage":{"input_tokens":12}}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"echo"}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"value\\":\\"x\\"}"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","usage":{"output_tokens":7}}

            data: {"type":"message_stop"}
            """;

    private static final String ANTHROPIC_FINAL_ROUND = """
            data: {"type":"message_start","message":{"usage":{"input_tokens":30}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}

            data: {"type":"message_delta","usage":{"output_tokens":3}}

            data: {"type":"message_stop"}
            """;

    /** One round carrying BOTH a text answer and a (verifying) tool call. */
    private static final String ANTHROPIC_TEXT_PLUS_TOOL = """
            data: {"type":"message_start","message":{"usage":{"input_tokens":12}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"the answer"}}

            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"echo"}}

            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"value\\":\\"x\\"}"}}

            data: {"type":"content_block_stop","index":1}

            data: {"type":"message_delta","usage":{"output_tokens":7}}

            data: {"type":"message_stop"}
            """;

    /** A round with neither text nor tool calls — the model had nothing
     *  to add after the tool result. */
    private static final String ANTHROPIC_EMPTY_FINAL = """
            data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

            data: {"type":"message_delta","usage":{"output_tokens":1}}

            data: {"type":"message_stop"}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    private Deque<String> responses;
    private List<String> requestBodies;
    private TurnRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
            throws Exception
    {
        responses = new ArrayDeque<>();
        requestBodies = new ArrayList<>();
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requestBodies.add(requestBody(request));
                    String body = responses.pollFirst();
                    if (body == null) {
                        body = ANTHROPIC_FINAL_ROUND;
                    }
                    HttpResponse<InputStream> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    when(response.body()).thenReturn(new ByteArrayInputStream(
                            body.getBytes(StandardCharsets.UTF_8)));
                    return response;
                });
        runner = new TurnRunner(httpClient, mapper);
    }

    @Test
    void roundTripsOneToolCallThroughTheExecutor()
    {
        responses.add(ANTHROPIC_TOOL_ROUND);
        responses.add(ANTHROPIC_FINAL_ROUND);

        List<ToolCall> executed = new ArrayList<>();
        List<String> hookOrder = new ArrayList<>();
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public void onToolCallStarted(String callId, String toolName, String inputJson)
            {
                hookOrder.add("started:" + callId);
            }

            @Override
            public void onToolCallDone(String callId, String resultText, boolean isError)
            {
                hookOrder.add("done:" + callId + ":" + resultText + ":" + isError);
            }
        };

        TurnResult result = runner.runTurn(spec(2), call -> {
            executed.add(call);
            return ToolExecutor.ToolCallResult.ok("echoed " + call.input().path("value").asText());
        }, hooks);

        assertEquals("done", result.finalText());
        assertEquals(TurnResult.End.COMPLETED, result.end());
        assertEquals(2, result.rounds());
        assertEquals(42L, result.tokensIn());
        assertEquals(10L, result.tokensOut());

        // The executor saw exactly one parsed call.
        assertEquals(1, executed.size());
        assertEquals("toolu_1", executed.get(0).id());
        assertEquals("echo", executed.get(0).name());
        assertEquals("x", executed.get(0).input().path("value").asText());
        assertEquals("{\"value\":\"x\"}", executed.get(0).rawArguments());

        // Hooks bracket the execution in order.
        assertEquals(List.of("started:toolu_1", "done:toolu_1:echoed x:false"), hookOrder);

        // Round 2 carries the Anthropic follow-up contract: the
        // assistant echo with the tool_use block, then a user message
        // holding the tool_result.
        assertEquals(2, requestBodies.size());
        String secondRequest = requestBodies.get(1);
        assertTrue(secondRequest.contains(
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                        + "\"name\":\"echo\",\"input\":{\"value\":\"x\"}}]}"),
                "missing assistant tool_use echo in: " + secondRequest);
        assertTrue(secondRequest.contains(
                "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\","
                        + "\"content\":\"echoed x\"}]}"),
                "missing tool_result message in: " + secondRequest);
    }

    @Test
    void errorResultsCarryTheErrorFlagOnTheWire()
    {
        responses.add(ANTHROPIC_TOOL_ROUND);
        responses.add(ANTHROPIC_FINAL_ROUND);

        runner.runTurn(spec(2),
                call -> ToolExecutor.ToolCallResult.error("budget exceeded"),
                TurnHooks.NONE);

        String secondRequest = requestBodies.get(1);
        assertTrue(secondRequest.contains(
                "{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\","
                        + "\"content\":\"budget exceeded\",\"is_error\":true}"),
                "missing is_error tool_result in: " + secondRequest);
    }

    @Test
    void abortHookStopsTheLoopAtTheRoundBoundary()
    {
        responses.add(ANTHROPIC_TOOL_ROUND);
        responses.add(ANTHROPIC_TOOL_ROUND);

        TurnHooks capAfterFirstRound = new TurnHooks()
        {
            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                return true;
            }
        };
        TurnResult result = runner.runTurn(spec(5),
                call -> ToolExecutor.ToolCallResult.ok("never reached"),
                capAfterFirstRound);

        assertEquals(TurnResult.End.ABORTED, result.end());
        assertEquals(1, result.rounds());
        // The abort fires before tool dispatch — nothing executed,
        // only one provider request went out.
        assertEquals(1, requestBodies.size());
    }

    @Test
    void iterationBoundForcesAToolsOffWrapUpRound()
    {
        // Three tool rounds exhaust spec(3) without the model ever giving
        // a final answer. The runner then forces one tools-off round so
        // the turn ends with a summary, not a silent empty completion.
        for (int i = 0; i < 3; i++) {
            responses.add(ANTHROPIC_TOOL_ROUND);
        }
        responses.add(ANTHROPIC_FINAL_ROUND); // the forced wrap-up answer
        TurnResult result = runner.runTurn(spec(3),
                call -> ToolExecutor.ToolCallResult.ok("ok"),
                TurnHooks.NONE);

        assertEquals(TurnResult.End.MAX_STEPS, result.end());
        assertEquals(4, result.rounds());
        assertEquals(4, requestBodies.size());
        assertEquals("done", result.finalText());
    }

    @Test
    void keepsTheLastNonEmptyTextWhenAFinalRoundIsBlank()
    {
        // The model answers in round 1 (text + a verifying tool call),
        // then the post-tool round comes back empty. The answer must
        // survive — an empty round never overwrites a good answer (the
        // bug that persisted a silent blank assistant message).
        responses.add(ANTHROPIC_TEXT_PLUS_TOOL);
        responses.add(ANTHROPIC_EMPTY_FINAL);

        TurnResult result = runner.runTurn(spec(5),
                call -> ToolExecutor.ToolCallResult.ok("ok"),
                TurnHooks.NONE);

        assertEquals("the answer", result.finalText());
        assertEquals(TurnResult.End.COMPLETED, result.end());
        assertEquals(2, result.rounds());
    }

    @Test
    void wrapUpRoundWithholdsTheToolCatalog()
    {
        // The model keeps calling tools through the bound; the forced
        // wrap-up round must omit the tools so it can only answer.
        for (int i = 0; i < 2; i++) {
            responses.add(ANTHROPIC_TOOL_ROUND);
        }
        responses.add(ANTHROPIC_FINAL_ROUND);
        runner.runTurn(specWithTools(2),
                call -> ToolExecutor.ToolCallResult.ok("ok"),
                TurnHooks.NONE);

        assertEquals(3, requestBodies.size());
        // The two in-loop rounds offered the tool catalog…
        assertTrue(requestBodies.get(0).contains("\"tools\""),
                "loop round should carry tools: " + requestBodies.get(0));
        // …the wrap-up round (request #3) withheld it.
        assertFalse(requestBodies.get(2).contains("\"tools\""),
                "wrap-up round must not offer tools: " + requestBodies.get(2));
    }

    @Test
    void sendsAnthropicEffortInOutputConfig()
            throws Exception
    {
        runner.runTurn(specWithEffort(
                        TurnSpec.Transport.ANTHROPIC, "max"),
                call -> ToolExecutor.ToolCallResult.ok("unused"),
                TurnHooks.NONE);

        JsonNode body = mapper.readTree(requestBodies.getFirst());
        assertEquals("max", body.path("output_config").path("effort").asText());
        assertFalse(body.has("reasoning_effort"));
    }

    @Test
    void sendsOpenAiEffortAtTheTopLevel()
            throws Exception
    {
        responses.add("""
                data: {"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":1}}

                data: [DONE]
                """);
        runner.runTurn(specWithEffort(
                        TurnSpec.Transport.OPENAI_COMPAT, "high"),
                call -> ToolExecutor.ToolCallResult.ok("unused"),
                TurnHooks.NONE);

        JsonNode body = mapper.readTree(requestBodies.getFirst());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertFalse(body.has("output_config"));
    }

    private TurnSpec spec(int maxIterations)
    {
        return spec(maxIterations, /* tools */ null);
    }

    private TurnSpec specWithTools(int maxIterations)
    {
        ArrayNode tools = mapper.createArrayNode();
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", "echo");
        tool.put("description", "echo back");
        tool.set("input_schema", mapper.createObjectNode());
        tools.add(tool);
        return spec(maxIterations, tools);
    }

    private TurnSpec spec(int maxIterations, ArrayNode tools)
    {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", "go");
        messages.add(user);
        return new TurnSpec(
                TurnSpec.Transport.ANTHROPIC,
                "https://provider.test/v1/messages",
                "test-key",
                "claude-sonnet-4-6",
                "system prompt",
                messages,
                tools,
                1024,
                maxIterations);
    }

    private TurnSpec specWithEffort(
            TurnSpec.Transport transport, String effort)
    {
        ArrayNode messages = mapper.createArrayNode();
        messages.add(mapper.createObjectNode()
                .put("role", "user")
                .put("content", "go"));
        return new TurnSpec(
                transport,
                "https://provider.test/v1/messages",
                "test-key",
                transport == TurnSpec.Transport.ANTHROPIC
                        ? "claude-opus-4-8" : "gpt-5",
                effort,
                transport == TurnSpec.Transport.ANTHROPIC
                        ? "system prompt" : null,
                messages,
                null,
                1024,
                1);
    }

    private static String requestBody(HttpRequest request)
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> body = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>()
        {
            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item)
            {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable failure)
            {
                body.completeExceptionally(failure);
            }

            @Override
            public void onComplete()
            {
                body.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        });
        return body.join();
    }
}
