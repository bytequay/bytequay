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

import com.bytequay.app.flow.runtime.NewFlowAgentToolBridge;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * The loopback MCP endpoint one running new-flow agent connects to.
 *
 * <p>Live only while that run's turn is open — the bridge owns that window, and
 * a request for a closed run is a 404 rather than an error the agent could
 * mistake for a tool that merely failed.
 *
 * <p>Deliberately not the existing per-turn-kind MCP controllers: those resolve
 * an owner through the thread and stage identity of the flow this runtime
 * replaces. This one is scoped by run id alone.
 */
@RestController
@RequestMapping("/api/new-flow/runs/{runId}/mcp")
public final class NewFlowAgentMcpController
{
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final NewFlowAgentToolBridge bridge;

    public NewFlowAgentMcpController(NewFlowAgentToolBridge bridge)
    {
        this.bridge = requireNonNull(bridge, "bridge is null");
    }

    @PostMapping
    public JsonNode handle(
            @PathVariable String runId,
            @RequestBody JsonNode request,
            HttpServletResponse response)
    {
        if (runId == null || runId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "runId is required");
        }
        if (request == null || !request.isObject()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "request body must be a JSON-RPC object");
        }
        response.setHeader(SESSION_HEADER, runId);
        if (request.path("method").asText("").startsWith("notifications/")) {
            // Accepted and dropped. The agent needs no answer, and answering
            // would mean maintaining state this endpoint deliberately has none of.
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            return null;
        }
        return bridge.handle(runId, request).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "no new-flow agent run is serving tools"));
    }
}
