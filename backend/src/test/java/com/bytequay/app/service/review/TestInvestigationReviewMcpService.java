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

import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.mcp.McpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.service.agents.ToolExecutor.ToolCallResult.ok;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.BLIND_RECONSTRUCTION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INDEPENDENT_VERIFICATION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.INVESTIGATE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ROUND_GUIDANCE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.SELF_REFUTATION;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.guidanceSubject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestInvestigationReviewMcpService
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final InvestigationReviewTools tools = mock(InvestigationReviewTools.class);
    private final AtomicReference<ToolCall> executed = new AtomicReference<>();
    private InvestigationReviewMcpService service;

    @BeforeEach
    void setUp()
    {
        when(tools.tools(TurnSpec.Transport.ANTHROPIC, false))
                .thenReturn(toolCatalog(
                        "record_assignment", "record_hypothesis", "record_step",
                        "read_diff", "read_file", "search_diff",
                        "record_finding", "record_evidence"));
        when(tools.tools(TurnSpec.Transport.ANTHROPIC, true))
                .thenReturn(toolCatalog("record_verification"));
        ToolExecutor executor = call -> {
            executed.set(call);
            return ok("accepted");
        };
        when(tools.executor("review-1", "assignment-1")).thenReturn(executor);
        service = new InvestigationReviewMcpService(
                tools, new McpResponses(mapper));
    }

    @Test
    void selfRefutationCanReadButCannotCreateFindings()
    {
        JsonNode listed = service.handle(
                "review-1", "assignment-1", SELF_REFUTATION,
                "finding-1|finding-2", null, rpc("tools/list", null));
        assertThat(listed.toString())
                .contains("read_file")
                .contains("record_evidence")
                .doesNotContain("record_finding")
                .doesNotContain("record_verification");

        JsonNode read = service.handle(
                "review-1", "assignment-1", SELF_REFUTATION,
                "finding-1|finding-2", null,
                rpc("tools/call", """
                        {"name":"read_file","arguments":{
                          "step_id":"step-1","path":"A.java"}}
                        """));
        assertThat(read.toString()).contains("accepted");
        assertThat(executed.get().name()).isEqualTo("read_file");

        executed.set(null);
        JsonNode denied = service.handle(
                "review-1", "assignment-1", SELF_REFUTATION,
                "finding-1|finding-2", null,
                rpc("tools/call", """
                        {"name":"record_evidence","arguments":{
                          "finding_id":"finding-3","observation_id":"observation-1",
                          "relation":"REFUTES","proposition":"counterexample",
                          "dependency_mode":"DIRECT_ONLY"}}
                        """));
        assertThat(denied.toString()).contains("outside the frozen finding set");
        assertThat(executed.get()).isNull();
    }

    @Test
    void quickPrimaryReviewCannotListOrCallReadFileButFullReviewCan()
    {
        when(tools.usesQuickReviewScope("assignment-1")).thenReturn(true);

        JsonNode quick = service.handle(
                "review-1", "assignment-1", INVESTIGATE, "subject-1", null,
                rpc("tools/list", null));
        assertThat(quick.toString())
                .contains("read_diff")
                .doesNotContain("read_file");

        JsonNode denied = service.handle(
                "review-1", "assignment-1", INVESTIGATE, "subject-1", null,
                rpc("tools/call", """
                        {"name":"read_file","arguments":{
                          "step_id":"step-1","path":"A.java"}}
                        """));
        assertThat(denied.toString()).contains("tool is not allowed");
        assertThat(executed.get()).isNull();

        when(tools.usesQuickReviewScope("assignment-1")).thenReturn(false);
        JsonNode full = service.handle(
                "review-1", "assignment-1", INVESTIGATE, "subject-1", null,
                rpc("tools/list", null));
        assertThat(full.toString()).contains("read_file");

        JsonNode accepted = service.handle(
                "review-1", "assignment-1", INVESTIGATE, "subject-1", null,
                rpc("tools/call", """
                        {"name":"read_file","arguments":{
                          "step_id":"step-1","path":"A.java"}}
                        """));
        assertThat(accepted.toString()).contains("accepted");
        assertThat(executed.get().name()).isEqualTo("read_file");
    }

    @Test
    void independentVerificationCanWriteOnlyItsFrozenFindingAndRun()
    {
        JsonNode accepted = service.handle(
                "review-1", "assignment-1", INDEPENDENT_VERIFICATION,
                "finding-1", "verifier-run-1",
                rpc("tools/call", """
                        {"name":"record_verification","arguments":{
                          "finding_id":"finding-1","verifier_run_id":"verifier-run-1"}}
                        """));
        assertThat(accepted.toString()).contains("accepted");
        assertThat(executed.get().name()).isEqualTo("record_verification");

        executed.set(null);
        JsonNode denied = service.handle(
                "review-1", "assignment-1", INDEPENDENT_VERIFICATION,
                "finding-1", "verifier-run-1",
                rpc("tools/call", """
                        {"name":"record_verification","arguments":{
                          "finding_id":"finding-2","verifier_run_id":"verifier-run-1"}}
                        """));
        assertThat(denied.toString()).contains("does not match the frozen finding");
        assertThat(executed.get()).isNull();
    }

    @Test
    void reconstructionAndUnknownPurposesFailClosedWithNoTools()
    {
        JsonNode reconstruction = service.handle(
                "review-1", "assignment-1", BLIND_RECONSTRUCTION,
                "finding-1", null, rpc("tools/list", null));
        JsonNode unknown = service.handle(
                "review-1", "assignment-1", "unexpected-purpose",
                "finding-1", null, rpc("tools/list", null));

        assertThat(reconstruction.path("result").path("tools")).isEmpty();
        assertThat(unknown.path("result").path("tools")).isEmpty();
    }

    @Test
    void reviewAssignmentsNeverExposeOrAcceptUserWaitTools()
    {
        when(tools.tools(TurnSpec.Transport.ANTHROPIC, false))
                .thenReturn(toolCatalog(
                        "read_diff", "ask_user_question", "approval_prompt"));

        JsonNode listed = service.handle(
                "review-1", "assignment-1", INVESTIGATE,
                "subject-1", null, rpc("tools/list", null));
        JsonNode question = service.handle(
                "review-1", "assignment-1", INVESTIGATE,
                "subject-1", null,
                rpc("tools/call", """
                        {"name":"ask_user_question","arguments":{}}
                        """));
        JsonNode permission = service.handle(
                "review-1", "assignment-1", INVESTIGATE,
                "subject-1", null,
                rpc("tools/call", """
                        {"name":"approval_prompt","arguments":{}}
                        """));

        assertThat(listed.toString())
                .contains("read_diff")
                .doesNotContain("ask_user_question", "approval_prompt");
        assertThat(question.toString())
                .contains("ReviewAssignmentTurn does not support user waits");
        assertThat(permission.toString())
                .contains("ReviewAssignmentTurn does not support user waits");
        assertThat(executed.get()).isNull();
    }

    @Test
    void guidanceToolPolicyIsFrozenByItsExactTarget()
    {
        JsonNode planner = service.handle(
                "review-1", "assignment-1", ROUND_GUIDANCE,
                guidanceSubject("message-1", "planner"), null,
                rpc("tools/list", null));
        JsonNode panel = service.handle(
                "review-1", "assignment-1", ROUND_GUIDANCE,
                guidanceSubject("message-2", "panel"), null,
                rpc("tools/list", null));

        assertThat(planner.path("result").path("tools")).isEmpty();
        assertThat(panel.toString())
                .contains("read_file")
                .contains("record_finding")
                .doesNotContain("record_verification");
    }

    private ArrayNode toolCatalog(String... names)
    {
        ArrayNode catalog = mapper.createArrayNode();
        for (String name : names) {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", name);
            tool.put("description", name);
            tool.set("input_schema", mapper.createObjectNode().put("type", "object"));
            catalog.add(tool);
        }
        return catalog;
    }

    private JsonNode rpc(String method, String params)
    {
        try {
            String suffix = params == null ? "" : ",\"params\":" + params;
            return mapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                            + method + "\"" + suffix + "}");
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
