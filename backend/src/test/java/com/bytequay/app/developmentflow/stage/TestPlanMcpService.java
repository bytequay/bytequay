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

import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore.PlanSubmission;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestPlanMcpService
{
    private final ObjectMapper mapper = new ObjectMapper();
    private PlanRuntimeCoordinator coordinator;
    private PlanMcpService service;

    @BeforeEach
    void setUp()
    {
        coordinator = mock(PlanRuntimeCoordinator.class);
        service = new PlanMcpService(coordinator, new McpResponses(mapper));
    }

    @Test
    void draftPermissionAllowsOnlyRecordPlanAndPreservesItsInput()
            throws Exception
    {
        authorize("PLAN_DRAFT");
        ObjectNode input = mapper.createObjectNode()
                .put("task_id", "task-1")
                .put("content", "Implement the requested change.");

        assertAllowed(permission("mcp__bytequay__record_plan", input, "tool-1"), input);
        assertAllowed(permission("record_plan", input, "tool-2"), input);
        assertDenied(permission(
                "mcp__bytequay__record_plan_self_review", input, "tool-3"));
    }

    @Test
    void selfReviewPermissionAllowsOnlyItsMatchingResultTool()
            throws Exception
    {
        authorize("PLAN_SELF_REVIEW");
        ObjectNode input = mapper.createObjectNode()
                .put("task_id", "task-1")
                .put("verdict", "APPROVED");

        assertAllowed(permission(
                "mcp__bytequay__record_plan_self_review", input, "tool-1"), input);
        assertAllowed(permission("record_plan_self_review", input, "tool-2"), input);
        assertDenied(permission("mcp__bytequay__record_plan", input, "tool-3"));
    }

    @Test
    void selfReviewToolPublishesTheStrictAcceptedSubmissionContract()
    {
        authorize("PLAN_SELF_REVIEW");
        ObjectNode request = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list");

        JsonNode response = service.handle(
                "turn-1", "operation-1", request);
        JsonNode tool = response.path("result").path("tools").path(0);

        assertThat(tool.path("name").asText())
                .isEqualTo("record_plan_self_review");
        assertThat(tool.path("description").asText())
                .contains("exactly one accepted typed self-review")
                .contains("APPROVED requires concerns=[]")
                .contains("rejected call that recorded nothing")
                .contains("Prose is never a verdict");
        JsonNode properties = tool.path("inputSchema").path("properties");
        assertThat(properties.path("verdict").path("description").asText())
                .contains("APPROVED requires concerns=[]");
        assertThat(properties.path("concerns").path("description").asText())
                .contains("Must be [] for APPROVED");
        assertThat(properties.path("follow_ups").path("description").asText())
                .contains("Non-blocking caveats");
        assertThat(properties.path("stewardship").path("description").asText())
                .contains("Non-blocking Project Stewardship");
    }

    @Test
    void foreignLookalikeMissingAndUnidentifiedToolsFailClosed()
            throws Exception
    {
        authorize("PLAN_DRAFT");
        ObjectNode input = mapper.createObjectNode().put("task_id", "task-1");

        assertDenied(permission("mcp__other__record_plan", input, "tool-1"));
        assertDenied(permission("other_record_plan", input, "tool-2"));
        assertDenied(permission(null, input, "tool-3"));
        assertDenied(permission("mcp__bytequay__record_plan", input, ""));
    }

    @Test
    void staleOperationCannotAuthorizeAPlanResult()
    {
        when(coordinator.authorizeMcp("turn-1", "operation-1"))
                .thenThrow(new IllegalArgumentException("TaskTurn MCP endpoint is stale"));

        JsonNode response = permission(
                "mcp__bytequay__record_plan",
                mapper.createObjectNode().put("task_id", "task-1"),
                "tool-1");

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText())
                .contains("stale");
        assertThat(response.has("result")).isFalse();
    }

    @Test
    void recordPlanStoresTheNestedShapeThePlanCardReads()
            throws Exception
    {
        // The model is asked for a flat payload; the card reads a nested one.
        // Nothing type-checks across that seam, so pin it here — otherwise a
        // rename on either side silently produces a 0-step card again.
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        when(coordinator.recordPlan(
                eq("turn-1"), eq("operation-1"), eq("task-1"), content.capture()))
                .thenReturn(new PlanSubmission(
                        "turn-1", "operation-1", "revision-1", 1,
                        "{}", "digest", "AGENT", Instant.EPOCH));

        ObjectNode arguments = mapper.createObjectNode()
                .put("task_id", "task-1")
                .put("goal", "Raise the nav font size")
                .put("understanding", "The token is 12px today.")
                .put("intent", "Bump the token and re-run lint.");
        ObjectNode step = arguments.putArray("steps").addObject();
        step.put("action", "Bump the token to 14px");
        step.putArray("files").add("frontend/src/css/v3-nav.css");
        arguments.put("validation", "npm run lint");
        arguments.putArray("out_of_scope").add("sibling nav stylesheets");

        call("record_plan", arguments);

        JsonNode stored = mapper.readTree(content.getValue());
        assertThat(stored.path("status").asText()).isEqualTo("finalized");
        assertThat(stored.path("goal").asText()).isEqualTo("Raise the nav font size");
        assertThat(stored.path("understanding").path("summary").asText())
                .isEqualTo("The token is 12px today.");
        assertThat(stored.path("intent").path("summary").asText())
                .isEqualTo("Bump the token and re-run lint.");
        assertThat(stored.path("intent").path("validationStrategy").asText())
                .isEqualTo("npm run lint");
        assertThat(stored.path("outOfScope").path(0).asText())
                .isEqualTo("sibling nav stylesheets");
        JsonNode first = stored.path("intent").path("steps").path(0);
        assertThat(first.path("ordinal").asInt()).isEqualTo(1);
        assertThat(first.path("action").asText()).isEqualTo("Bump the token to 14px");
        assertThat(first.path("files").path(0).asText())
                .isEqualTo("frontend/src/css/v3-nav.css");
    }

    @Test
    void aRejectedSelfReviewComesBackAsACorrectableToolError()
    {
        // The brain is told a rejected call may be corrected and retried.
        // That only holds if the rejection reaches it as a tool-execution
        // error: a JSON-RPC protocol error is far less recoverable, and the
        // permission deny envelope ends the turn outright.
        when(coordinator.recordSelfReview(
                "turn-1", "operation-1", "task-1", "APPROVED",
                List.of("still leaks the writer lease"),
                List.of(), List.of()))
                .thenThrow(new IllegalArgumentException(
                        "an APPROVED self-review cannot carry concerns"));

        ObjectNode arguments = mapper.createObjectNode()
                .put("task_id", "task-1")
                .put("verdict", "APPROVED");
        arguments.putArray("concerns").add("still leaks the writer lease");
        arguments.putArray("follow_ups");
        arguments.putArray("stewardship");

        JsonNode response = call("record_plan_self_review", arguments);

        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").path(0).path("text").asText())
                .contains("cannot carry concerns");
    }

    @Test
    void argumentsThatMissTheSchemaComeBackAsACorrectableToolError()
    {
        // concerns must be an array of strings; a bare string fails the
        // strict reader. The brain should learn that from the tool result
        // rather than from a -32700 it cannot act on.
        ObjectNode arguments = mapper.createObjectNode()
                .put("task_id", "task-1")
                .put("verdict", "APPROVED")
                .put("concerns", "none");

        JsonNode response = call("record_plan_self_review", arguments);

        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        assertThat(response.path("result").path("content").path(0).path("text").asText())
                .contains("do not match the tool schema");
    }

    @Test
    void anUnknownToolStaysAProtocolError()
    {
        // The complement: an unknown tool is not something the model can
        // correct by resubmitting, so it keeps the JSON-RPC error channel.
        JsonNode response = call("record_something_else", mapper.createObjectNode());

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(response.has("result")).isFalse();
    }

    private JsonNode call(String toolName, JsonNode arguments)
    {
        ObjectNode params = mapper.createObjectNode().put("name", toolName);
        params.set("arguments", arguments);
        ObjectNode request = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call");
        request.set("params", params);
        return service.handle("turn-1", "operation-1", request);
    }

    private void authorize(String purpose)
    {
        when(coordinator.authorizeMcp("turn-1", "operation-1"))
                .thenReturn(new PlanRuntimeCoordinator.McpAuthorization(
                        "task-1", purpose));
    }

    private JsonNode permission(
            String toolName, JsonNode input, String toolUseId)
    {
        ObjectNode arguments = mapper.createObjectNode();
        if (toolName != null) {
            arguments.put("tool_name", toolName);
        }
        arguments.set("input", input);
        arguments.put("tool_use_id", toolUseId);
        ObjectNode params = mapper.createObjectNode()
                .put("name", "approval_prompt")
                .set("arguments", arguments);
        ObjectNode request = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/call");
        request.set("params", params);
        return service.handle("turn-1", "operation-1", request);
    }

    private void assertAllowed(JsonNode response, JsonNode expectedInput)
            throws Exception
    {
        JsonNode envelope = envelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("allow");
        assertThat(envelope.path("updatedInput")).isEqualTo(expectedInput);
    }

    private void assertDenied(JsonNode response)
            throws Exception
    {
        JsonNode envelope = envelope(response);
        assertThat(envelope.path("behavior").asText()).isEqualTo("deny");
        assertThat(envelope.has("updatedInput")).isFalse();
    }

    private JsonNode envelope(JsonNode response)
            throws Exception
    {
        String text = response.path("result").path("content")
                .path(0).path("text").asText();
        return mapper.readTree(text);
    }
}
