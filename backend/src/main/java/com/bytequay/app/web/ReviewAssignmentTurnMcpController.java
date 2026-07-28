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

import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler.McpOwner;
import com.bytequay.app.service.review.InvestigationReviewMcpService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static java.util.Objects.requireNonNull;

/** Exact read-only MCP endpoint for one live ReviewAssignmentTurn. */
@RestController
@RequestMapping("/api/v2/review-assignment-turns/{turnId}/operations/{operationId}/mcp")
@ConditionalOnProperty(
        name = "bytequay.development-flow.v2-dispatch-enabled",
        havingValue = "true")
public final class ReviewAssignmentTurnMcpController
{
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final InvestigationReviewMcpService service;
    private final ReviewAssignmentTurnOperationHandler.Store turns;
    private final Clock clock;

    @Autowired
    public ReviewAssignmentTurnMcpController(
            InvestigationReviewMcpService service,
            ReviewAssignmentTurnOperationHandler.Store turns)
    {
        this(service, turns, Clock.systemUTC());
    }

    ReviewAssignmentTurnMcpController(
            InvestigationReviewMcpService service,
            ReviewAssignmentTurnOperationHandler.Store turns,
            Clock clock)
    {
        this.service = requireNonNull(service, "service is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @PostMapping
    public JsonNode handle(
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
        McpOwner owner = turns.findMcpOwner(turnId, operationId, clock.instant())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "ReviewAssignmentTurn operation is not active"));
        response.setHeader(SESSION_HEADER, turnId + ":" + operationId);
        if (request.path("method").asText("").startsWith("notifications/")) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
        }
        return service.handle(
                owner.reviewId(), owner.assignmentId(), owner.purpose(),
                owner.subjectKey(), owner.verifierRunId(), request);
    }
}
