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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.local.ds4.Ds4Status;
import com.bytequay.app.service.threads.tools.AgentTool;
import com.bytequay.app.service.threads.tools.AgentToolContext;
import com.bytequay.app.service.threads.tools.LogicLoopToolRegistry;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolOutcome;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization suite for the in-JVM agent tool-round loop. These
 * fixtures lock the loop's externally observable behaviour — the exact
 * provider request payloads, the persisted message sequence, the
 * emitted stream events, and the loop's bounding rules — so the
 * TurnRunner extraction can be verified as behaviour-neutral.
 *
 * <p>The agent is pointed at a local scripted SSE server through the
 * ds4 endpoint seam (the one provider path whose URL is injectable
 * without touching the class under test). Responses are byte-stable
 * text blocks; request bodies are recorded verbatim and compared as
 * whole strings, which locks the wire format down to field order.
 */
class TestLogicLoopThreadAgentCharacterization
{
    private static final String THREAD_ID = "thread-1";
    private static final String SYSTEM_PROMPT = "You are a test agent.";

    /** One round that immediately terminates on final text. */
    private static final String SSE_FINAL_TEXT = """
            data: {"choices":[{"delta":{"content":"Hello"}}]}

            data: {"choices":[{"delta":{"content":" world"}}]}

            data: {"usage":{"prompt_tokens":10,"completion_tokens":5},"choices":[]}

            data: [DONE]
            """;

    /** One round that asks for the read-only file tool, arguments
     *  split across two chunks the way real providers stream them. */
    private static final String SSE_FILE_TOOL_CALL = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_file_content","arguments":"{\\"path\\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.txt\\"}"}}]}}]}

            data: {"usage":{"prompt_tokens":20,"completion_tokens":8},"choices":[]}

            data: [DONE]
            """;

    /** One round that asks for the parked {@code push} tool. */
    private static final String SSE_PUSH_TOOL_CALL = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_push","function":{"name":"push","arguments":"{}"}}]}}]}

            data: {"usage":{"prompt_tokens":15,"completion_tokens":6},"choices":[]}

            data: [DONE]
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    private ScriptedSseServer server;
    private ExecutorService executor;
    private ThreadStore threadStore;
    private AgentToolRegistry cliLaneTools;
    private List<ThreadMessage> appendedMessages;
    private List<StreamEvent> events;

    @BeforeEach
    void setUp()
            throws IOException
    {
        server = new ScriptedSseServer();
        executor = Executors.newFixedThreadPool(2);
        appendedMessages = new CopyOnWriteArrayList<>();
        events = new CopyOnWriteArrayList<>();

        threadStore = mock(ThreadStore.class);
        when(threadStore.maxMessageSeq(THREAD_ID)).thenReturn(Optional.empty());
        when(threadStore.listRecentMessages(anyString(), anyInt())).thenReturn(List.of());
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread()));
        doAnswer(invocation -> {
            appendedMessages.add(invocation.getArgument(0));
            return null;
        }).when(threadStore).appendMessage(any());

        cliLaneTools = mock(AgentToolRegistry.class);
        when(cliLaneTools.all()).thenReturn(List.of(pushSpec()));
        when(cliLaneTools.byName("push")).thenReturn(Optional.of(pushSpec()));
    }

    @AfterEach
    void tearDown()
    {
        server.stop();
        executor.shutdownNow();
    }

    @Test
    void singleRoundTerminatesOnFinalText()
            throws Exception
    {
        server.enqueue(SSE_FINAL_TEXT);
        LogicLoopThreadAgent agent = agent();

        agent.send("hi").toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(1, server.requestBodies.size());
        assertEquals(ThreadStatus.IDLE, agent.status());

        // Persisted sequence: the user turn, then the assistant text.
        assertEquals(
                List.of("user/text", "assistant/text"),
                appendedMessages.stream().map(m -> m.role() + "/" + m.type()).toList());
        assertEquals("{\"text\":\"Hello world\"}", appendedMessages.get(1).contentJson());
        assertEquals(10L, appendedMessages.get(1).tokensIn());
        assertEquals(5L, appendedMessages.get(1).tokensOut());

        // Event spine: UserMessage → SessionStarted → deltas → usage →
        // AssistantText → TurnDone.
        assertEquals(
                List.of("UserMessage", "SessionStarted", "AssistantTextDelta",
                        "AssistantTextDelta", "UsageUpdated", "AssistantText", "TurnDone"),
                events.stream().map(e -> e.getClass().getSimpleName()).toList());

        // The exact outbound payload, locked byte-for-byte.
        assertEquals(expectedFirstRequest("hi"), server.requestBodies.get(0));
    }

    @Test
    void multiRoundReadOnlyToolChain()
            throws Exception
    {
        server.enqueue(SSE_FILE_TOOL_CALL);
        server.enqueue(SSE_FINAL_TEXT);
        LogicLoopThreadAgent agent = agent();

        agent.send("read a.txt").toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(2, server.requestBodies.size());
        assertEquals(ThreadStatus.IDLE, agent.status());

        assertEquals(
                List.of("user/text", "tool/tool_call", "tool/tool_result", "assistant/text"),
                appendedMessages.stream().map(m -> m.role() + "/" + m.type()).toList());
        assertEquals(
                "{\"callId\":\"call_1\",\"toolName\":\"get_file_content\","
                        + "\"input\":{\"path\":\"a.txt\"}}",
                appendedMessages.get(1).contentJson());
        assertEquals(
                "{\"callId\":\"call_1\",\"text\":\"FILE a.txt: hello\",\"isError\":false}",
                appendedMessages.get(2).contentJson());

        // Tokens accumulate across both rounds onto the final message.
        assertEquals(30L, appendedMessages.get(3).tokensIn());
        assertEquals(13L, appendedMessages.get(3).tokensOut());

        // Round 2 carries the assistant tool_calls echo plus the
        // role:tool result, in the provider's required shape.
        assertEquals(expectedSecondRequest("read a.txt"), server.requestBodies.get(1));
    }

    @Test
    void parkedPublishToolIsRefusedWithoutReachingTheHandler()
            throws Exception
    {
        server.enqueue(SSE_PUSH_TOOL_CALL);
        server.enqueue(SSE_FINAL_TEXT);
        LogicLoopThreadAgent agent = agent();

        agent.send("push it").toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(ThreadStatus.IDLE, agent.status());
        assertEquals(
                List.of("user/text", "tool/tool_call", "tool/tool_result", "assistant/text"),
                appendedMessages.stream().map(m -> m.role() + "/" + m.type()).toList());

        // The parked tool is refused at the gate: the result is an
        // error pointing at the parked-proposal flow, and the CLI-lane
        // handler is never invoked — nothing can publish from here.
        JsonNode result = mapper.readTree(appendedMessages.get(2).contentJson());
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("text").asText().contains("parked-proposal flow"),
                "expected the parked-pointer refusal, got: " + result.path("text").asText());
        verify(cliLaneTools, never()).invoke(anyString(), any());
    }

    @Test
    void toolIterationCapForcesAWrapUpRound()
            throws Exception
    {
        // The provider asks for a tool on every round through the cap;
        // the loop then forces one tools-off wrap-up round so the turn
        // ends with an answer rather than a silent empty completion.
        for (int i = 0; i < 12; i++) {
            server.enqueue(SSE_FILE_TOOL_CALL);
        }
        server.enqueue(SSE_FINAL_TEXT); // the forced wrap-up answer
        LogicLoopThreadAgent agent = agent();

        agent.send("loop forever").toCompletableFuture().get(30, TimeUnit.SECONDS);

        // 12 tool rounds + 1 wrap-up round.
        assertEquals(13, server.requestBodies.size());
        assertEquals(ThreadStatus.IDLE, agent.status());

        long toolCalls = appendedMessages.stream().filter(m -> "tool_call".equals(m.type())).count();
        long toolResults = appendedMessages.stream().filter(m -> "tool_result".equals(m.type())).count();
        assertEquals(12, toolCalls);
        assertEquals(12, toolResults);
        // The final assistant message carries the wrap-up answer, not a blank.
        ThreadMessage last = appendedMessages.get(appendedMessages.size() - 1);
        assertEquals("assistant", last.role());
        assertEquals("{\"text\":\"Hello world\"}", last.contentJson());
        assertEquals(12 * 20L + 10L, last.tokensIn());
        assertEquals(12 * 8L + 5L, last.tokensOut());
    }

    // ── Wiring ────────────────────────────────────────────────────────

    private LogicLoopThreadAgent agent()
    {
        Ds4LifecycleService ds4 = mock(Ds4LifecycleService.class);
        when(ds4.status()).thenReturn(new Ds4Status(
                Ds4State.RUNNING, server.endpoint(), 1L, Instant.now(), false, 0, null));
        LogicLoopToolRegistry registry = new LogicLoopToolRegistry(
                List.of(new FakeFileTool()), cliLaneTools, mapper);
        LogicLoopThreadAgent agent = new LogicLoopThreadAgent(
                thread(),
                threadStore,
                mapper,
                executor,
                mock(CredentialService.class),
                new WorkModel(WorkModelKind.API, "deepseek", "deepseek-v4-flash", null),
                /* workingDir */ null,
                SYSTEM_PROMPT,
                registry,
                ds4,
                /* ds4Instrumentation */ null,
                /* permissionGate */ null);
        agent.subscribeToEvents(events::add);
        return agent;
    }

    private static Thread thread()
    {
        Instant t0 = Instant.ofEpochMilli(0);
        // A non-null parentTaskId keeps the loop at task altitude — the trunk
        // allowlist would otherwise filter the fake tools out of the rendered
        // catalog. Altitude now derives from the thread's parentTaskId.
        return new Thread(
                THREAD_ID, ThreadKind.LOGIC_LOOP, "deepseek", null,
                "test thread", ThreadStatus.IDLE, "deepseek-v4-flash",
                0L, 0L, 0L, t0, t0, null, null,
                ThreadFlow.BUILD, "ws-default", null, null, 1, "task-1");
    }

    private ToolSpec pushSpec()
    {
        try {
            Method handler = TestLogicLoopThreadAgentCharacterization.class
                    .getDeclaredMethod("pushHandler");
            return new ToolSpec(
                    "push", "Push commits to the remote.", "",
                    SecurityType.GIT_PUSH, Gating.PARKED, Set.of(AgentRole.ANY), Set.of(),
                    "{\"type\":\"object\"}", Object.class, this, handler);
        }
        catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** Never invoked — exists so the push ToolSpec is "dispatchable"
     *  (return type {@code ToolOutcome}) and survives the registry's
     *  declaration-only-stub filter. */
    static ToolOutcome pushHandler()
    {
        return ToolOutcome.Completed.ok("unreachable");
    }

    // ── Expected payloads ────────────────────────────────────────────

    private String expectedFirstRequest(String userInput)
    {
        return "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":4096,\"stream\":true,"
                + "\"stream_options\":{\"include_usage\":true},"
                + "\"messages\":[{\"role\":\"system\",\"content\":\"" + SYSTEM_PROMPT + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + userInput + "\"}],"
                + "\"tools\":" + expectedToolsArray() + ",\"tool_choice\":\"auto\"}";
    }

    private String expectedSecondRequest(String userInput)
    {
        return "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":4096,\"stream\":true,"
                + "\"stream_options\":{\"include_usage\":true},"
                + "\"messages\":[{\"role\":\"system\",\"content\":\"" + SYSTEM_PROMPT + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + userInput + "\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"get_file_content\","
                + "\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"call_1\","
                + "\"content\":\"FILE a.txt: hello\"}],"
                + "\"tools\":" + expectedToolsArray() + ",\"tool_choice\":\"auto\"}";
    }

    private String expectedToolsArray()
    {
        return "[{\"type\":\"function\",\"function\":{\"name\":\"get_file_content\","
                + "\"description\":\"Read a file.\",\"parameters\":{\"type\":\"object\","
                + "\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}},"
                + "{\"type\":\"function\",\"function\":{\"name\":\"push\","
                + "\"description\":\"Push commits to the remote.\","
                + "\"parameters\":{\"type\":\"object\"}}}]";
    }

    // ── Fakes ────────────────────────────────────────────────────────

    /** Read-only native tool the loop can chain through. */
    private final class FakeFileTool
            implements AgentTool
    {
        @Override
        public String name()
        {
            return "get_file_content";
        }

        @Override
        public String description()
        {
            return "Read a file.";
        }

        @Override
        public JsonNode inputSchema()
        {
            try {
                return mapper.readTree(
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},"
                                + "\"required\":[\"path\"]}");
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean isReadOnly()
        {
            return true;
        }

        @Override
        public Result invoke(JsonNode input, AgentToolContext ctx)
        {
            return Result.ok("FILE " + input.path("path").asText() + ": hello");
        }
    }

    /** Minimal scripted SSE server: serves the queued bodies in order
     *  and records every request body verbatim. */
    private static final class ScriptedSseServer
    {
        private final HttpServer httpServer;
        private final ConcurrentLinkedDeque<String> responses = new ConcurrentLinkedDeque<>();
        private final List<String> requestBodies = new ArrayList<>();

        ScriptedSseServer()
                throws IOException
        {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/v1/chat/completions", exchange -> {
                String body = new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                synchronized (requestBodies) {
                    requestBodies.add(body);
                }
                String response = responses.pollFirst();
                if (response == null) {
                    response = "data: [DONE]\n";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            httpServer.start();
        }

        void enqueue(String sseBody)
        {
            responses.addLast(sseBody);
        }

        String endpoint()
        {
            return "http://127.0.0.1:" + httpServer.getAddress().getPort();
        }

        void stop()
        {
            httpServer.stop(0);
        }
    }
}
