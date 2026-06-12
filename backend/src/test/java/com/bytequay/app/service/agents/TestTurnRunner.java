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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private ConcurrentLinkedDeque<String> responses;
    private List<String> requestBodies;
    private TurnRunner runner;

    @BeforeEach
    void setUp()
            throws IOException
    {
        responses = new ConcurrentLinkedDeque<>();
        requestBodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requestBodies) {
                requestBodies.add(body);
            }
            String response = responses.pollFirst();
            if (response == null) {
                response = ANTHROPIC_FINAL_ROUND;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        runner = new TurnRunner(HttpClient.newHttpClient(), mapper);
    }

    @AfterEach
    void tearDown()
    {
        server.stop(0);
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
    void iterationBoundEndsTheTurnAsCompleted()
    {
        for (int i = 0; i < 3; i++) {
            responses.add(ANTHROPIC_TOOL_ROUND);
        }
        TurnResult result = runner.runTurn(spec(3),
                call -> ToolExecutor.ToolCallResult.ok("ok"),
                TurnHooks.NONE);

        assertEquals(TurnResult.End.COMPLETED, result.end());
        assertEquals(3, result.rounds());
        assertEquals(3, requestBodies.size());
    }

    private TurnSpec spec(int maxIterations)
    {
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", "go");
        messages.add(user);
        return new TurnSpec(
                TurnSpec.Transport.ANTHROPIC,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages",
                "test-key",
                "claude-sonnet-4-6",
                "system prompt",
                messages,
                /* tools */ null,
                1024,
                maxIterations);
    }
}
