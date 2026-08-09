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
import com.bytequay.app.service.mcp.McpServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

/** Exact operation-scoped Streamable-HTTP endpoint for every V2 TaskTurn. */
@RestController
@RequestMapping("/api/v2/task-turns/{turnId}/operations/{operationId}/mcp")
public final class PlanMcpController
{
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private final PlanMcpService plans;
    private final McpServiceImpl tools;
    private final AgentTurnOperationHandler.Store turns;
    private final Clock clock;

    @Autowired
    public PlanMcpController(
            PlanMcpService plans,
            McpServiceImpl tools,
            AgentTurnOperationHandler.Store turns)
    {
        this(plans, tools, turns, Clock.systemUTC());
    }

    PlanMcpController(
            PlanMcpService plans,
            McpServiceImpl tools,
            AgentTurnOperationHandler.Store turns,
            Clock clock)
    {
        this.plans = requireNonNull(plans, "plans is null");
        this.tools = requireNonNull(tools, "tools is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @PostMapping
    public DeferredResult<JsonNode> handle(
            @PathVariable String turnId,
            @PathVariable String operationId,
            @RequestBody JsonNode request,
            HttpServletResponse response)
    {
        if (turnId == null || turnId.isBlank()
                || operationId == null || operationId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "turnId and operationId are required");
        }
        if (request == null || !request.isObject()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "request body must be a JSON-RPC object");
        }
        response.setHeader(SESSION_HEADER, turnId + ":" + operationId);
        if (request.path("method").asText("").startsWith("notifications/")) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
        }
        AgentTurnOperationHandler.McpContext context = turns.authorizeMcp(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        turnId, operationId, clock.instant())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "TaskTurn operation is not active"));
        if (context.purpose().equals("PLAN_DRAFT")
                || context.purpose().equals("PLAN_SELF_REVIEW")) {
            DeferredResult<JsonNode> result = new DeferredResult<>();
            result.setResult(plans.handle(turnId, operationId, request));
            return result;
        }
        return tools.handle(
                context.trunkId(),
                AgentTurnOperationHandler.mcpAgentKey(
                        DispatchTicket.OwnerKind.TASK_TURN, turnId, operationId),
                request);
    }
}
