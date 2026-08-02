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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.beans.mcp.ApprovalPromptArgs;
import com.bytequay.app.beans.mcp.Capabilities;
import com.bytequay.app.beans.mcp.InitializeResult;
import com.bytequay.app.beans.mcp.JsonRpcRequest;
import com.bytequay.app.beans.mcp.ListToolsResult;
import com.bytequay.app.beans.mcp.ServerInfo;
import com.bytequay.app.beans.mcp.ToolCallParams;
import com.bytequay.app.beans.mcp.ToolDescriptor;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanSubmission;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.ReviewSubmission;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Minimal operation-scoped MCP server for the code-read-only V2 Task Brain. */
@Component
public final class PlanMcpService
{
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String RECORD_PLAN = "record_plan";
    private static final String RECORD_REVIEW = "record_plan_self_review";
    private static final String APPROVAL_PROMPT = "approval_prompt";
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";

    private static final String PLAN_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["task_id","content"],
             "properties":{"task_id":{"type":"string","minLength":1},
             "content":{"type":"string","minLength":1}}}
            """;
    private static final String REVIEW_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["task_id","verdict","concerns","follow_ups","stewardship"],
             "properties":{"task_id":{"type":"string","minLength":1},
             "verdict":{"type":"string","enum":["APPROVED","CHANGES_REQUESTED","BLOCKED"],
             "description":"Typed verdict. APPROVED requires concerns=[]."},
             "concerns":{"type":"array","items":{"type":"string","minLength":1},
             "description":"Plan defects that prevent approval. Must be [] for APPROVED."},
             "follow_ups":{"type":"array","items":{"type":"string","minLength":1},
             "description":"Non-blocking caveats and follow-up work."},
             "stewardship":{"type":"array","items":{"type":"string","minLength":1},
             "description":"Non-blocking Project Stewardship notes."}}}
            """;
    private static final String APPROVAL_SCHEMA = """
            {"type":"object","additionalProperties":true}
            """;

    private final PlanRuntimeCoordinator coordinator;
    private final McpResponses responses;
    private final ObjectReader planReader;
    private final ObjectReader reviewReader;

    public PlanMcpService(
            PlanRuntimeCoordinator coordinator, McpResponses responses)
    {
        this.coordinator = requireNonNull(coordinator, "coordinator is null");
        this.responses = requireNonNull(responses, "responses is null");
        this.planReader = responses.mapper().readerFor(RecordPlanArgs.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.reviewReader = responses.mapper().readerFor(RecordReviewArgs.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public JsonNode handle(String turnId, String operationId, JsonNode request)
    {
        JsonNode rawId = request.path("id");
        try {
            JsonRpcRequest rpc = responses.mapper()
                    .treeToValue(request, JsonRpcRequest.class);
            String method = rpc.method() == null ? "" : rpc.method();
            return switch (method) {
                case "initialize" -> initialize(
                        turnId, operationId, rpc.id(), rpc.params());
                case "tools/list" -> listTools(turnId, operationId, rpc.id());
                case "tools/call" -> call(
                        turnId, operationId, rpc.id(), rpc.params());
                case "notifications/initialized", "notifications/cancelled" -> {
                    coordinator.authorizeMcp(turnId, operationId);
                    yield null;
                }
                default -> responses.error(
                        rpc.id(), -32601, "method not found: " + method);
            };
        }
        catch (IOException e) {
            return responses.error(rawId, -32700, "parse error: " + e.getMessage());
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return responses.error(rawId, -32602, e.getMessage());
        }
    }

    private JsonNode initialize(
            String turnId, String operationId, JsonNode id, JsonNode params)
    {
        coordinator.authorizeMcp(turnId, operationId);
        String requested = params == null
                ? null : params.path("protocolVersion").asText(null);
        return responses.ok(id, new InitializeResult(
                requested == null || requested.isBlank()
                        ? PROTOCOL_VERSION : requested,
                Capabilities.empty(), new ServerInfo("bytequay-plan", "1.0.0")));
    }

    private JsonNode listTools(String turnId, String operationId, JsonNode id)
            throws JsonProcessingException
    {
        PlanRuntimeCoordinator.McpAuthorization authorization =
                coordinator.authorizeMcp(turnId, operationId);
        ToolDescriptor primary = switch (authorization.purpose()) {
            case "PLAN_DRAFT" -> descriptor(
                    RECORD_PLAN,
                    "Persist exactly one immutable candidate Plan revision for this Task.",
                    PLAN_SCHEMA);
            case "PLAN_SELF_REVIEW" -> descriptor(
                    RECORD_REVIEW,
                    "Record exactly one accepted typed self-review for the current Plan revision. "
                            + "APPROVED requires concerns=[]; non-blocking caveats belong in "
                            + "follow_ups or stewardship. A rejected call that recorded nothing "
                            + "may be corrected and retried. Prose is never a verdict.",
                    REVIEW_SCHEMA);
            default -> throw new IllegalArgumentException(
                    "TaskTurn purpose is not a Plan protocol purpose");
        };
        return responses.ok(id, new ListToolsResult(List.of(
                primary,
                descriptor(
                        APPROVAL_PROMPT,
                        "Permission prompt for this code-read-only Task Brain; only its exact Plan result is allowed.",
                        APPROVAL_SCHEMA))));
    }

    private JsonNode call(
            String turnId, String operationId, JsonNode id, JsonNode paramsNode)
            throws IOException
    {
        ToolCallParams params = responses.bindArgs(paramsNode, ToolCallParams.class);
        JsonNode arguments = params.arguments() == null
                ? responses.mapper().createObjectNode() : params.arguments();
        return switch (params.name() == null ? "" : params.name()) {
            case RECORD_PLAN -> {
                RecordPlanArgs args = planReader.readValue(arguments);
                PlanSubmission saved = coordinator.recordPlan(
                        turnId, operationId, args.taskId(), args.content());
                yield responses.plainText(id,
                        "Recorded Plan revision " + saved.revision()
                                + " (" + saved.contentDigest() + ").");
            }
            case RECORD_REVIEW -> {
                RecordReviewArgs args = reviewReader.readValue(arguments);
                ReviewSubmission saved = coordinator.recordSelfReview(
                        turnId, operationId, args.taskId(), args.verdict(),
                        args.concerns(), args.followUps(), args.stewardship());
                yield responses.plainText(id,
                        "Recorded Plan self-review " + saved.verdict()
                                + " for " + saved.reviewedDigest() + ".");
            }
            case APPROVAL_PROMPT -> {
                ApprovalPromptArgs args = responses.bindArgs(
                        arguments, ApprovalPromptArgs.class);
                PlanRuntimeCoordinator.McpAuthorization authorization =
                        coordinator.authorizeMcp(turnId, operationId);
                String expected = switch (authorization.purpose()) {
                    case "PLAN_DRAFT" -> RECORD_PLAN;
                    case "PLAN_SELF_REVIEW" -> RECORD_REVIEW;
                    default -> null;
                };
                if (expected != null
                        && expected.equals(shortToolName(args.toolName()))
                        && args.toolUseId() != null
                        && !args.toolUseId().isBlank()) {
                    yield responses.toolResponse(
                            id, responses.allow(args.input()));
                }
                yield responses.toolResponse(id, responses.deny(
                        "The V2 Task Brain may submit only its exact Plan result; this permission is denied."));
            }
            default -> responses.error(id, -32601, "unknown tool: " + params.name());
        };
    }

    private ToolDescriptor descriptor(
            String name, String description, String schema)
            throws JsonProcessingException
    {
        return new ToolDescriptor(
                name, description, responses.mapper().readTree(schema));
    }

    private static String shortToolName(String toolName)
    {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX)
                ? toolName.substring(MCP_TOOL_PREFIX.length())
                : toolName;
    }

    public record RecordPlanArgs(
            @JsonProperty("task_id") String taskId,
            String content) {}

    public record RecordReviewArgs(
            @JsonProperty("task_id") String taskId,
            String verdict,
            List<String> concerns,
            @JsonProperty("follow_ups") List<String> followUps,
            List<String> stewardship) {}
}
