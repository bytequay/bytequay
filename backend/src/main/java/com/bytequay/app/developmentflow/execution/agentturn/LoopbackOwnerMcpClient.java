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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

/** Minimal Streamable-HTTP client for one already-validated loopback endpoint. */
public final class LoopbackOwnerMcpClient
        implements ApiAgentTurnProviderSession.OwnerMcpClient
{
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final HttpClient http;
    private final ObjectMapper json;
    private final AtomicLong requestIds = new AtomicLong();

    public LoopbackOwnerMcpClient(ObjectMapper json)
    {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                json);
    }

    LoopbackOwnerMcpClient(HttpClient http, ObjectMapper json)
    {
        this.http = requireNonNull(http, "http is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public List<ToolDefinition> list(
            AgentTurnProviderSession.OwnerToolEndpoint endpoint,
            AgentTurnProviderSession.WriterFence writerFence)
            throws InterruptedException
    {
        JsonNode result = rpc(endpoint, writerFence, "tools/list", json.createObjectNode());
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            throw new IllegalStateException("owner MCP tools/list returned no tools array");
        }
        List<ToolDefinition> found = new ArrayList<>();
        for (JsonNode tool : tools) {
            found.add(new ToolDefinition(
                    requiredText(tool, "name"),
                    tool.path("description").asText(""),
                    requiredObject(tool, "inputSchema")));
        }
        return List.copyOf(found);
    }

    @Override
    public ToolExecutor.ToolCallResult call(
            AgentTurnProviderSession.OwnerToolEndpoint endpoint,
            AgentTurnProviderSession.WriterFence writerFence,
            ToolCall call)
    {
        requireNonNull(call, "call is null");
        ObjectNode params = json.createObjectNode();
        params.put("name", call.name());
        params.set("arguments", call.input() == null
                ? json.createObjectNode()
                : call.input().deepCopy());
        try {
            JsonNode result = rpc(endpoint, writerFence, "tools/call", params);
            JsonNode content = result.path("content");
            if (!content.isArray()) {
                return ToolExecutor.ToolCallResult.error(
                        "owner MCP tools/call returned no content array");
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText())) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(item.path("text").asText(""));
                }
            }
            return new ToolExecutor.ToolCallResult(
                    text.toString(), result.path("isError").asBoolean(false));
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("owner MCP tool call was interrupted", interrupted);
        }
    }

    private JsonNode rpc(
            AgentTurnProviderSession.OwnerToolEndpoint endpoint,
            AgentTurnProviderSession.WriterFence writerFence,
            String method,
            JsonNode params)
            throws InterruptedException
    {
        requireNonNull(endpoint, "endpoint is null");
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIds.incrementAndGet());
        request.put("method", method);
        request.set("params", requireNonNull(params, "params is null"));

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.url()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        request.toString(), StandardCharsets.UTF_8));
        addWriterHeaders(builder, writerFence);
        HttpResponse<String> response;
        try {
            response = http.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException failure) {
            throw new IllegalStateException("owner MCP request failed", failure);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "owner MCP request returned HTTP " + response.statusCode());
        }
        JsonNode envelope;
        try {
            envelope = json.readTree(response.body());
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("owner MCP response is not JSON", failure);
        }
        if (envelope.hasNonNull("error")) {
            throw new IllegalStateException(
                    "owner MCP error: " + envelope.path("error").path("message").asText());
        }
        JsonNode result = envelope.path("result");
        if (result.isMissingNode() || result.isNull()) {
            throw new IllegalStateException("owner MCP response has no result");
        }
        return result;
    }

    private static void addWriterHeaders(
            HttpRequest.Builder request,
            AgentTurnProviderSession.WriterFence fence)
    {
        if (fence == null) {
            return;
        }
        request.header("X-ByteQuay-Task-Id", fence.taskId());
        request.header("X-ByteQuay-Operation-Id", fence.operationId());
        request.header("X-ByteQuay-Task-Epoch", Long.toString(fence.taskEpoch()));
        request.header(
                "X-ByteQuay-Writer-Fencing-Token",
                Long.toString(fence.fencingToken()));
    }

    private static String requiredText(JsonNode object, String field)
    {
        String value = object.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("owner MCP tool has no " + field);
        }
        return value;
    }

    private static JsonNode requiredObject(JsonNode object, String field)
    {
        JsonNode value = object.path(field);
        if (!value.isObject()) {
            throw new IllegalStateException("owner MCP tool has no object " + field);
        }
        return value;
    }
}
