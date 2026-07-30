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
import java.util.Set;
import java.util.UUID;

import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.BLIND_RECONSTRUCTION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INDEPENDENT_VERIFICATION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INVESTIGATE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ROUND_GUIDANCE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.SELF_REFUTATION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.guidanceTarget;

/**
 * MCP bridge exposing frozen evidence tools to a read-only investigator.
 * ReviewAssignmentTurns may be standalone, so this endpoint never creates a
 * Trunk-routed question or permission wait.
 */
@Component
public class InvestigationReviewMcpService
{
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Set<String> USER_WAIT_TOOLS = Set.of(
            "ask_user_question", "approval_prompt");

    private final InvestigationReviewTools tools;
    private final McpResponses responses;

    public InvestigationReviewMcpService(InvestigationReviewTools tools, McpResponses responses)
    {
        this.tools = tools;
        this.responses = responses;
    }

    public JsonNode handle(String reviewId, String assignmentId, JsonNode request)
    {
        return handle(reviewId, assignmentId, "legacy", "legacy", null, request);
    }

    public JsonNode handle(
            String reviewId,
            String assignmentId,
            String purpose,
            String subjectKey,
            String verifierRunId,
            JsonNode request)
    {
        JsonNode rawId = request.path("id");
        try {
            JsonRpcRequest rpc = responses.mapper().treeToValue(request, JsonRpcRequest.class);
            JsonNode id = rpc.id();
            return switch (rpc.method() == null ? "" : rpc.method()) {
                case "initialize" -> responses.ok(id, new InitializeResult(
                        protocolVersion(rpc.params()), Capabilities.empty(),
                        new ServerInfo("bytequay-investigation-review", "1.0.0")));
                case "tools/list" -> listTools(
                        id, assignmentId, purpose, subjectKey);
                case "tools/call" -> call(
                        reviewId, assignmentId, purpose, subjectKey,
                        verifierRunId, id, rpc.params());
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

    private JsonNode listTools(
            JsonNode id, String assignmentId, String purpose, String subjectKey)
    {
        ArrayNode catalog = responses.mapper().createArrayNode();
        catalog.addAll(tools.tools(TurnSpec.Transport.ANTHROPIC, false));
        catalog.addAll(tools.tools(TurnSpec.Transport.ANTHROPIC, true));
        List<ToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode node : catalog) {
            if (!allowedTools(
                    purpose, subjectKey, tools.usesQuickReviewScope(assignmentId))
                    .contains(node.path("name").asText())) {
                continue;
            }
            descriptors.add(new ToolDescriptor(
                    node.path("name").asText(), node.path("description").asText(),
                    node.path("input_schema")));
        }
        return responses.ok(id, new ListToolsResult(descriptors));
    }

    private JsonNode call(
            String reviewId,
            String assignmentId,
            String purpose,
            String subjectKey,
            String verifierRunId,
            JsonNode id,
            JsonNode paramsNode)
    {
        ToolCallParams params;
        try {
            params = responses.bindArgs(paramsNode, ToolCallParams.class);
        }
        catch (JsonProcessingException e) {
            return responses.error(id, -32602, "invalid tools/call params: " + e.getMessage());
        }
        if (USER_WAIT_TOOLS.contains(params.name())) {
            return responses.toolResponse(id, responses.deny(
                    "ReviewAssignmentTurn does not support user waits"));
        }
        JsonNode arguments = params.arguments();
        if (!allowedTools(
                purpose, subjectKey, tools.usesQuickReviewScope(assignmentId))
                .contains(params.name())) {
            return responses.toolResponse(id, responses.deny(
                    "tool is not allowed for ReviewAssignmentTurn purpose " + purpose));
        }
        if (INDEPENDENT_VERIFICATION.equals(purpose)
                && (arguments == null
                || !subjectKey.equals(arguments.path("finding_id").asText())
                || verifierRunId == null
                || !verifierRunId.equals(arguments.path("verifier_run_id").asText()))) {
            return responses.toolResponse(id, responses.deny(
                    "verification does not match the frozen finding and verifier run"));
        }
        if (SELF_REFUTATION.equals(purpose)
                && "record_evidence".equals(params.name())
                && (arguments == null
                || !"REFUTES".equals(arguments.path("relation").asText())
                || !Set.of(subjectKey.split("\\|")).contains(
                        arguments.path("finding_id").asText()))) {
            return responses.toolResponse(id, responses.deny(
                    "self-refutation evidence is outside the frozen finding set"));
        }
        ToolExecutor.ToolCallResult result = tools.executor(reviewId, assignmentId).execute(new ToolCall(
                UUID.randomUUID().toString(), params.name(),
                arguments == null ? "{}" : arguments.toString(),
                arguments == null ? responses.mapper().createObjectNode() : arguments));
        return result.isError()
                ? responses.toolResponse(id, responses.deny(result.text()))
                : responses.plainText(id, result.text());
    }

    private static Set<String> allowedTools(
            String purpose, String subjectKey, boolean quickReview)
    {
        if (quickReview && INVESTIGATE.equals(purpose)) {
            return Set.of(
                    "record_assignment", "record_hypothesis", "record_step",
                    "read_diff", "search_diff", "record_finding", "record_evidence");
        }
        return switch (purpose) {
            case INVESTIGATE -> Set.of(
                    "record_assignment", "record_hypothesis", "record_step",
                    "read_diff", "read_file", "search_diff",
                    "record_finding", "record_evidence");
            case ROUND_GUIDANCE -> Set.of("planner", "independent-verifier")
                    .contains(guidanceTarget(subjectKey))
                    ? Set.of()
                    : Set.of(
                            "record_assignment", "record_hypothesis", "record_step",
                            "read_diff", "read_file", "search_diff",
                            "record_finding", "record_evidence");
            case SELF_REFUTATION -> Set.of(
                    "record_step", "read_diff", "read_file", "search_diff",
                    "record_evidence");
            case INDEPENDENT_VERIFICATION -> Set.of("record_verification");
            case BLIND_RECONSTRUCTION -> Set.of();
            case "legacy" -> Set.of(
                    "record_assignment", "record_hypothesis", "record_step",
                    "read_diff", "read_file", "search_diff", "record_finding",
                    "record_evidence", "record_verification");
            default -> Set.of();
        };
    }

    private static String protocolVersion(JsonNode params)
    {
        String requested = params == null ? null : params.path("protocolVersion").asText(null);
        return requested == null || requested.isBlank() ? PROTOCOL_VERSION : requested;
    }
}
