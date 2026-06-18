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
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A minimal MCP (JSON-RPC) server that exposes the four review tools —
 * {@code get_pr_diff}, {@code get_file_content}, {@code search_code},
 * {@code report_finding} — to a Claude CLI reviewer subprocess, scoped to
 * one review pass + seat.
 *
 * <p>Unlike the thread-agent MCP server (which carries the role/permission
 * registry and the approval-prompt gate), this one is purpose-built and
 * ungated: the tools are read-only plus a finding write to the seat's own
 * pass, so every call auto-runs. The tool LOGIC is not duplicated — each
 * {@code tools/call} runs through the very same {@link SeatToolset}
 * executor the API reviewers use, so a CLI reviewer's {@code report_finding}
 * lands on the rail identically.
 */
@Component
public class ReviewMcpService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewMcpService.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** The tools exposed, in a stable order. */
    private static final List<ReviewToolSchemas.Tool> TOOLS = List.of(
            ReviewToolSchemas.GET_PR_DIFF,
            ReviewToolSchemas.GET_FILE_CONTENT,
            ReviewToolSchemas.SEARCH_CODE,
            ReviewToolSchemas.REPORT_FINDING);

    private final SeatToolset toolset;
    private final ReviewStore reviewStore;
    private final McpResponses responses;

    public ReviewMcpService(SeatToolset toolset, ReviewStore reviewStore, McpResponses responses)
    {
        this.toolset = toolset;
        this.reviewStore = reviewStore;
        this.responses = responses;
    }

    /** Handle one JSON-RPC request for the {@code (passId, participantId)}
     *  seat. Synchronous — review tools never block on a human. */
    public JsonNode handle(String passId, String participantId, JsonNode request)
    {
        JsonNode rawId = request.path("id");
        try {
            JsonRpcRequest rpc = responses.mapper().treeToValue(request, JsonRpcRequest.class);
            String method = rpc.method() == null ? "" : rpc.method();
            JsonNode id = rpc.id();
            return switch (method) {
                case "initialize" -> responses.ok(id, new InitializeResult(
                        PROTOCOL_VERSION, Capabilities.empty(), new ServerInfo("bytequay-review", "1.0.0")));
                case "tools/list" -> listTools(id);
                case "tools/call" -> callTool(passId, participantId, id, rpc.params());
                // Notifications carry no id and want no response body.
                case "notifications/initialized", "notifications/cancelled" -> null;
                default -> responses.error(id, -32601, "method not found: " + method);
            };
        }
        catch (JsonProcessingException e) {
            return responses.error(rawId, -32700, "parse error: " + e.getMessage());
        }
        catch (RuntimeException e) {
            log.warn("Review MCP request failed for pass {} seat {}: {}",
                    passId, participantId, e.getMessage());
            return responses.error(rawId, -32603, e.getMessage());
        }
    }

    private JsonNode listTools(JsonNode id)
    {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (ReviewToolSchemas.Tool tool : TOOLS) {
            JsonNode schema;
            try {
                schema = responses.mapper().readTree(tool.schemaJson());
            }
            catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "review tool " + tool.name() + " has an invalid schema", e);
            }
            descriptors.add(new ToolDescriptor(tool.name(), tool.description(), schema));
        }
        return responses.ok(id, new ListToolsResult(descriptors));
    }

    private JsonNode callTool(String passId, String participantId, JsonNode id, JsonNode paramsNode)
    {
        ToolCallParams params;
        try {
            params = responses.bindArgs(paramsNode, ToolCallParams.class);
        }
        catch (JsonProcessingException e) {
            return responses.error(id, -32602, "invalid tools/call params: " + e.getMessage());
        }
        String name = params.name() == null ? "" : params.name();

        ReviewPass pass = reviewStore.findPassById(passId).orElse(null);
        if (pass == null) {
            return responses.error(id, -32602, "unknown review pass: " + passId);
        }
        ReviewParticipant seat = reviewStore.findParticipantById(participantId).orElse(null);
        if (seat == null || !seat.reviewPassId().equals(passId)) {
            return responses.error(id, -32602, "unknown seat: " + participantId);
        }

        // Run the call through the same executor the API reviewers use, so
        // get_pr_diff / report_finding behave identically.
        ToolExecutor executor = toolset.executorFor(pass, participantId, seat.personaLabel());
        JsonNode arguments = params.arguments();
        ToolExecutor.ToolCallResult result = executor.execute(new ToolCall(
                UUID.randomUUID().toString(), name,
                arguments == null ? "{}" : arguments.toString(),
                arguments == null ? responses.mapper().createObjectNode() : arguments));

        return result.isError()
                ? responses.toolResponse(id, responses.deny(result.text()))
                : responses.plainText(id, result.text());
    }
}
