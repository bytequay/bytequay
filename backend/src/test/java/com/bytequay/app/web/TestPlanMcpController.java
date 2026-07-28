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
package com.bytequay.app.web;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.stage.PlanMcpService;
import com.bytequay.app.service.mcp.McpService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPlanMcpController
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final PlanMcpService plans = mock(PlanMcpService.class);
    private final McpService tools = mock(McpService.class);
    private final AgentTurnOperationHandler.Store turns =
            mock(AgentTurnOperationHandler.Store.class);
    private final PlanMcpController controller = new PlanMcpController(
            plans, tools, turns, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void nonPlanTaskTurnUsesTheTypedBrainToolRoute()
            throws Exception
    {
        when(turns.authorizeMcp(
                DispatchTicket.OwnerKind.TASK_TURN,
                "turn-1", "operation-1", NOW))
                .thenReturn(Optional.of(context("DEVELOPMENT_BRAIN_REVIEW")));
        DeferredResult<JsonNode> expected = new DeferredResult<>();
        when(tools.handle(
                eq("trunk-1"),
                eq(AgentTurnOperationHandler.mcpAgentKey(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        "turn-1", "operation-1")), any()))
                .thenReturn(expected);

        DeferredResult<JsonNode> actual = controller.handle(
                "turn-1", "operation-1", request(),
                new MockHttpServletResponse());

        assertThat(actual).isSameAs(expected);
        verify(plans, never()).handle(any(), any(), any());
    }

    @Test
    void planPurposeRetainsItsTypedPlanProtocol()
            throws Exception
    {
        JsonNode response = json.readTree("""
                {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}
                """);
        when(turns.authorizeMcp(
                DispatchTicket.OwnerKind.TASK_TURN,
                "turn-1", "operation-1", NOW))
                .thenReturn(Optional.of(context("PLAN_DRAFT")));
        when(plans.handle(eq("turn-1"), eq("operation-1"), any()))
                .thenReturn(response);

        DeferredResult<JsonNode> result = controller.handle(
                "turn-1", "operation-1", request(),
                new MockHttpServletResponse());

        assertThat(result.getResult()).isEqualTo(response);
        verify(tools, never()).handle(any(), any(), any());
    }

    private JsonNode request()
            throws Exception
    {
        return json.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """);
    }

    private static AgentTurnOperationHandler.McpContext context(String purpose)
    {
        return new AgentTurnOperationHandler.McpContext(
                DispatchTicket.OwnerKind.TASK_TURN,
                "trunk-1", "workspace-1", "task-1", 1,
                "stage-1", 1L, purpose);
    }
}
