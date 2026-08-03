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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

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

    /**
     * Flat on purpose. The stored revision is nested to match the plan-card
     * reader, but the model is asked for one level — a schema it can satisfy
     * beats one that mirrors our storage. The server does the nesting.
     */
    private static final String PLAN_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["task_id","goal","understanding","intent","steps",
             "risk","effort","confidence"],
             "properties":{"task_id":{"type":"string","minLength":1},
             "goal":{"type":"string","minLength":1,
             "description":"ONE sentence naming the objective. No preamble."},
             "understanding":{"type":"string","minLength":1,
             "description":"What you established about the change before planning it."},
             "intent":{"type":"string","minLength":1,
             "description":"What you intend to do, in one or two sentences."},
             "steps":{"type":"array","minItems":1,
             "description":"The minimal ordered set of moves that get the work done.",
             "items":{"type":"object","additionalProperties":false,
             "required":["action"],
             "properties":{
             "action":{"type":"string","minLength":1,
             "description":"A SHORT imperative naming ONE move. Not a code body."},
             "files":{"type":"array","items":{"type":"string","minLength":1}},
             "rationale":{"type":"string","description":"One line of why."},
             "risk":{"type":"string","enum":["low","med","high","opt"]}}}},
             "validation":{"type":"string",
             "description":"How the change will be checked."},
             "risk":{"type":"string","enum":["low","medium","high"],
             "description":"Overall risk of the change as planned."},
             "effort":{"type":"string","enum":["small","medium","large"],
             "description":"Overall size of the work."},
             "confidence":{"type":"string","enum":["low","medium","high"],
             "description":"How confident you are the plan succeeds as written."},
             "out_of_scope":{"type":"array","items":{"type":"string","minLength":1},
             "description":"What this task deliberately does NOT do."}}}
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
            case RECORD_PLAN -> submitted(id, () -> {
                RecordPlanArgs args = planReader.readValue(arguments);
                PlanSubmission saved = coordinator.recordPlan(
                        turnId, operationId, args.taskId(), planContent(args));
                return "Recorded Plan revision " + saved.revision()
                        + " (" + saved.contentDigest() + ").";
            });
            case RECORD_REVIEW -> submitted(id, () -> {
                RecordReviewArgs args = reviewReader.readValue(arguments);
                ReviewSubmission saved = coordinator.recordSelfReview(
                        turnId, operationId, args.taskId(), args.verdict(),
                        args.concerns(), args.followUps(), args.stewardship());
                return "Recorded Plan self-review " + saved.verdict()
                        + " for " + saved.reviewedDigest() + ".";
            });
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

    /** The body of a record tool: persists the submission and returns the
     *  receipt text, or throws to reject it. */
    @FunctionalInterface
    private interface Submission
    {
        String submit()
                throws IOException;
    }

    /**
     * Frame a record tool's outcome. A rejection — arguments that miss the
     * schema, or a submission the coordinator refuses — comes back as an MCP
     * tool-execution error rather than a JSON-RPC protocol error, so the brain
     * reads the reason and can correct its call inside the same turn. The spec
     * draws exactly this line: clients SHOULD hand tool-execution errors to the
     * model for self-correction, and only MAY forward protocol errors, which
     * are "less likely to result in successful recovery".
     *
     * <p>Safe to retry after: both coordinator entry points validate before
     * they open a command or touch the store, so a rejected call has recorded
     * nothing.
     */
    private JsonNode submitted(JsonNode id, Submission submission)
    {
        try {
            return responses.plainText(id, submission.submit());
        }
        catch (IOException e) {
            return responses.toolError(
                    id, "Arguments do not match the tool schema: " + e.getMessage());
        }
        catch (IllegalArgumentException | IllegalStateException e) {
            return responses.toolError(id, "Submission rejected: " + e.getMessage());
        }
    }

    /**
     * The stored revision: the nested shape the plan card reads, built from the
     * flat one the model was asked for. Field order is fixed by construction, so
     * an identical re-submission digests identically and stays idempotent.
     */
    private static final List<String> RISK = List.of("low", "medium", "high");
    private static final List<String> EFFORT = List.of("small", "medium", "large");

    /**
     * Publishing a schema does not enforce it: Jackson binds what arrives and
     * checks Java types, so an omitted required field arrives as null and an
     * enum outside the list arrives verbatim. The check has to be here. It
     * returns a tool error, so the brain reads the reason and re-submits.
     */
    private static String requireOneOf(String value, List<String> allowed, String field)
    {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException(
                    field + " must be one of " + allowed + ", got: "
                            + (value == null ? "nothing" : value));
        }
        return normalized;
    }

    private String planContent(RecordPlanArgs args)
            throws JsonProcessingException
    {
        String risk = requireOneOf(args.risk(), RISK, "risk");
        String effort = requireOneOf(args.effort(), EFFORT, "effort");
        String confidence = requireOneOf(args.confidence(), RISK, "confidence");
        ObjectNode plan = responses.mapper().createObjectNode();
        plan.put("status", "finalized");
        plan.put("goal", args.goal());
        plan.putObject("understanding").put("summary", args.understanding());
        ObjectNode intent = plan.putObject("intent");
        intent.put("summary", args.intent());
        ArrayNode steps = intent.putArray("steps");
        int ordinal = 0;
        for (PlanStep step : args.steps() == null ? List.<PlanStep>of() : args.steps()) {
            ObjectNode node = steps.addObject();
            node.put("ordinal", ++ordinal);
            node.put("action", step.action());
            ArrayNode files = node.putArray("files");
            if (step.files() != null) {
                step.files().forEach(files::add);
            }
            if (step.rationale() != null && !step.rationale().isBlank()) {
                node.put("rationale", step.rationale());
            }
            if (step.risk() != null && !step.risk().isBlank()) {
                node.put("risk", step.risk());
            }
        }
        intent.put("validationStrategy",
                args.validation() == null ? "" : args.validation());
        // The card shows these as pills on the approval decision. They are the
        // planner's own assessment and nothing automated reads them.
        ObjectNode signals = plan.putObject("signals");
        signals.put("riskLevel", risk);
        signals.put("estimatedComplexity", effort);
        signals.put("confidence", confidence);
        ArrayNode outOfScope = plan.putArray("outOfScope");
        if (args.outOfScope() != null) {
            args.outOfScope().forEach(outOfScope::add);
        }
        return responses.mapper().writeValueAsString(plan);
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
            String goal,
            String understanding,
            String intent,
            List<PlanStep> steps,
            String validation,
            String risk,
            String effort,
            String confidence,
            @JsonProperty("out_of_scope") List<String> outOfScope) {}

    public record PlanStep(
            String action,
            List<String> files,
            String rationale,
            String risk) {}

    public record RecordReviewArgs(
            @JsonProperty("task_id") String taskId,
            String verdict,
            List<String> concerns,
            @JsonProperty("follow_ups") List<String> followUps,
            List<String> stewardship) {}
}
