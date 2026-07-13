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
package com.bytequay.app.service.review;

import com.bytequay.app.beans.mcp.Capabilities;
import com.bytequay.app.beans.mcp.InitializeResult;
import com.bytequay.app.beans.mcp.JsonRpcRequest;
import com.bytequay.app.beans.mcp.ListToolsResult;
import com.bytequay.app.beans.mcp.ServerInfo;
import com.bytequay.app.beans.mcp.ToolCallParams;
import com.bytequay.app.beans.mcp.ToolDescriptor;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MCP bridge exposing the same frozen tools to a read-only CLI investigator. */
@Component
public class InvestigationReviewMcpService
{
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final InvestigationReviewTools tools;
    private final McpResponses responses;

    public InvestigationReviewMcpService(InvestigationReviewTools tools, McpResponses responses)
    {
        this.tools = tools;
        this.responses = responses;
    }

    public JsonNode handle(String sessionId, String assignmentId, JsonNode request)
    {
        JsonNode rawId = request.path("id");
        try {
            JsonRpcRequest rpc = responses.mapper().treeToValue(request, JsonRpcRequest.class);
            JsonNode id = rpc.id();
            return switch (rpc.method() == null ? "" : rpc.method()) {
                case "initialize" -> responses.ok(id, new InitializeResult(
                        protocolVersion(rpc.params()), Capabilities.empty(),
                        new ServerInfo("bytequay-investigation-review", "1.0.0")));
                case "tools/list" -> listTools(id);
                case "tools/call" -> call(sessionId, assignmentId, id, rpc.params());
                case "notifications/initialized", "notifications/cancelled" -> null;
                default -> responses.error(id, -32601, "method not found: " + rpc.method());
            };
        }
        catch (JsonProcessingException e) {
            return responses.error(rawId, -32700, "parse error: " + e.getMessage());
        }
        catch (RuntimeException e) {
            return responses.error(rawId, -32603, e.getMessage());
        }
    }

    private JsonNode listTools(JsonNode id)
    {
        ArrayNode catalog = responses.mapper().createArrayNode();
        catalog.addAll(tools.tools(TurnSpec.Transport.ANTHROPIC, false));
        catalog.addAll(tools.tools(TurnSpec.Transport.ANTHROPIC, true));
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode node : catalog) {
            descriptors.add(new ToolDescriptor(
                    node.path("name").asText(), node.path("description").asText(),
                    node.path("input_schema")));
        }
        return responses.ok(id, new ListToolsResult(descriptors));
    }

    private JsonNode call(
            String sessionId, String assignmentId, JsonNode id, JsonNode paramsNode)
    {
        ToolCallParams params;
        try {
            params = responses.bindArgs(paramsNode, ToolCallParams.class);
        }
        catch (JsonProcessingException e) {
            return responses.error(id, -32602, "invalid tools/call params: " + e.getMessage());
        }
        JsonNode arguments = params.arguments();
        ToolExecutor.ToolCallResult result = tools.executor(sessionId, assignmentId).execute(new ToolCall(
                UUID.randomUUID().toString(), params.name(),
                arguments == null ? "{}" : arguments.toString(),
                arguments == null ? responses.mapper().createObjectNode() : arguments));
        return result.isError()
                ? responses.toolResponse(id, responses.deny(result.text()))
                : responses.plainText(id, result.text());
    }

    private static String protocolVersion(JsonNode params)
    {
        String requested = params == null ? null : params.path("protocolVersion").asText(null);
        return requested == null || requested.isBlank() ? PROTOCOL_VERSION : requested;
    }
}
