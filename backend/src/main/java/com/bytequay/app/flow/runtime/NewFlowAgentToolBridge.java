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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.Objects.requireNonNull;

/**
 * Serves one running agent's own tools over MCP, for the duration of that run.
 *
 * <p><b>Why this exists.</b> An in-JVM turn hands its {@code ToolExecutor}
 * straight to the turn runner. A subprocess cannot be handed a Java object, so
 * the same executor has to be reachable over a socket — and reachable only while
 * its run is live, only by that run, and only for the tools the program chose
 * for that role. This is the whole of that surface.
 *
 * <p><b>The same executor, deliberately.</b> The registered executor is the one
 * the in-JVM body would have used. Building a second one for CLI turns is the
 * obvious shortcut and the wrong one: the two would drift, and the drift would
 * mean an agent's tools behaving differently depending on which engine the
 * workspace happened to name — a difference no reviewer would think to look for.
 *
 * <p><b>Scope is the run id, and the window is the turn.</b> {@link #open}
 * registers; closing the handle deregisters. A call naming a run that is not
 * open is refused, so a subprocess that outlived its turn — the case
 * {@link ProcessGroup} exists to make rare rather than impossible — finds a dead
 * endpoint rather than a live worktree. That is the second line of defence
 * behind the process group, not a substitute for it.
 *
 * <p>Not a general MCP server: no session negotiation beyond {@code initialize},
 * no notifications, no resources or prompts. Those are unused here, and every
 * one of them would be another way in.
 */
public final class NewFlowAgentToolBridge
{
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper;
    private final Map<String, Live> live = new ConcurrentHashMap<>();

    public NewFlowAgentToolBridge(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    private static final class Live
    {
        private final Thread owner;
        private final ArrayNode tools;
        private final ToolExecutor executor;
        private final ConcurrentLinkedQueue<PendingCall> pending =
                new ConcurrentLinkedQueue<>();
        private volatile boolean open = true;

        private Live(Thread owner, ArrayNode tools, ToolExecutor executor)
        {
            this.owner = owner;
            this.tools = tools;
            this.executor = executor;
        }
    }

    private record PendingCall(
            JsonNode id,
            JsonNode params,
            CompletableFuture<JsonNode> result) {}

    /** A registration that must be closed when the turn ends. */
    public interface Registration
            extends AutoCloseable
    {
        /** What the subprocess is told to connect to, relative to the server. */
        String path();

        /** Runs queued tool calls on the thread that owns the agent turn. */
        void executePendingCalls();

        @Override
        void close();
    }

    /**
     * Opens the endpoint for one run.
     *
     * @param tools the manifest this run's program chose, in MCP shape
     * @throws IllegalStateException when the run is already open, which would
     *         otherwise let a redelivered launch serve tools against a stale
     *         executor
     */
    public Registration open(String runId, ArrayNode tools, ToolExecutor executor)
    {
        requireText(runId, "runId");
        requireNonNull(tools, "tools is null");
        requireNonNull(executor, "executor is null");
        Live registered = new Live(
                Thread.currentThread(), (ArrayNode) tools.deepCopy(), executor);
        if (live.putIfAbsent(runId, registered) != null) {
            throw new IllegalStateException(
                    "agent tool bridge is already open for run " + runId);
        }
        return new Registration()
        {
            @Override
            public String path()
            {
                return "/api/new-flow/runs/" + runId + "/mcp";
            }

            @Override
            public void executePendingCalls()
            {
                if (Thread.currentThread() != registered.owner) {
                    throw new IllegalStateException(
                            "agent tool calls require the turn's owner thread");
                }
                PendingCall call;
                while ((call = registered.pending.poll()) != null) {
                    try {
                        call.result().complete(NewFlowAgentToolBridge.this.call(
                                call.id(), registered, call.params()));
                    }
                    catch (RuntimeException | Error failure) {
                        call.result().completeExceptionally(failure);
                        throw failure;
                    }
                }
            }

            @Override
            public void close()
            {
                registered.open = false;
                live.remove(runId, registered);
                PendingCall call;
                while ((call = registered.pending.poll()) != null) {
                    call.result().complete(error(
                            call.id(), -32000, "agent turn has stopped"));
                }
            }
        };
    }

    boolean isOpen(String runId)
    {
        return runId != null && live.containsKey(runId);
    }

    /**
     * Answers one JSON-RPC request for {@code runId}, or empty when no such run
     * is open. Empty is the caller's cue to return 404 rather than an RPC error:
     * a closed run is not a bad request, it is not this program's endpoint any
     * more.
     */
    public Optional<JsonNode> handle(String runId, JsonNode request)
    {
        requireText(runId, "runId");
        requireNonNull(request, "request is null");
        Live registered = live.get(runId);
        if (registered == null) {
            return Optional.empty();
        }
        JsonNode id = request.path("id");
        String method = request.path("method").asText("");
        return Optional.of(switch (method) {
            case "initialize" -> result(id, initialize());
            case "tools/list" -> result(id, mapper.createObjectNode()
                    .set("tools", registered.tools));
            case "tools/call" -> dispatchCall(
                    id, registered, request.path("params"));
            // Unknown methods get the JSON-RPC code for exactly that, rather
            // than a transport error the agent would read as the tool failing.
            default -> error(id, -32601, "unsupported method " + method);
        });
    }

    private JsonNode dispatchCall(JsonNode id, Live registered, JsonNode params)
    {
        if (Thread.currentThread() == registered.owner) {
            return call(id, registered, params);
        }
        CompletableFuture<JsonNode> result = new CompletableFuture<>();
        PendingCall pending = new PendingCall(
                id.deepCopy(), params.deepCopy(), result);
        registered.pending.add(pending);
        if (!registered.open && registered.pending.remove(pending)) {
            result.complete(error(id, -32000, "agent turn has stopped"));
        }
        return result.join();
    }

    private ObjectNode initialize()
    {
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.putObject("tools");
        ObjectNode response = mapper.createObjectNode()
                .put("protocolVersion", PROTOCOL_VERSION);
        response.set("capabilities", capabilities);
        response.putObject("serverInfo")
                .put("name", "bytequay")
                .put("version", "1");
        return response;
    }

    private JsonNode call(JsonNode id, Live registered, JsonNode params)
    {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return error(id, -32602, "tools/call requires a tool name");
        }
        if (!offers(registered, name)) {
            // Refused here rather than passed through. The executor may well
            // reject it too, but the manifest is the program's decision about
            // what this role may do, and it should not depend on a second
            // component agreeing.
            return error(id, -32602, "tool is not available");
        }
        JsonNode arguments = params.path("arguments");
        ObjectNode input = arguments.isObject()
                ? (ObjectNode) arguments.deepCopy() : mapper.createObjectNode();
        ToolExecutor.ToolCallResult outcome = registered.executor.execute(
                new ToolCall(name + ":" + id.asText("0"), name,
                        input.toString(), input));
        ObjectNode content = mapper.createObjectNode();
        ArrayNode items = content.putArray("content");
        items.addObject()
                .put("type", "text")
                .put("text", outcome.text() == null ? "" : outcome.text());
        // A tool that failed is reported as a failed tool, not a failed
        // request: the agent is expected to read it and try something else.
        content.put("isError", outcome.isError());
        return result(id, content);
    }

    private boolean offers(Live registered, String name)
    {
        for (JsonNode tool : registered.tools) {
            if (name.equals(tool.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode result(JsonNode id, JsonNode payload)
    {
        ObjectNode response = mapper.createObjectNode()
                .put("jsonrpc", "2.0");
        response.set("id", id == null || id.isMissingNode()
                ? mapper.nullNode() : id.deepCopy());
        response.set("result", payload);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message)
    {
        ObjectNode response = mapper.createObjectNode()
                .put("jsonrpc", "2.0");
        response.set("id", id == null || id.isMissingNode()
                ? mapper.nullNode() : id.deepCopy());
        response.putObject("error")
                .put("code", code)
                .put("message", message);
        return response;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
