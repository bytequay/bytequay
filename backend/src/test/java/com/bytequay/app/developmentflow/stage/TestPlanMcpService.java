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

import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
