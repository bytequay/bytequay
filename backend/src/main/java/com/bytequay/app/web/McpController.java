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

import com.bytequay.app.service.mcp.McpServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * HTTP entry point for the MCP server. Owns the URL surface
 * ({@code @RequestMapping}) and the HTTP-verb binding
 * ({@code @PostMapping} / {@code @PathVariable} / {@code @RequestBody})
 * because those are transport concerns; the
 * {@link com.bytequay.app.service.mcp.McpServiceImpl} interface stays
 * HTTP-agnostic so the same business contract can be exercised by an
 * in-JVM lane, a CLI dispatcher, or anything else that wants to call
 * {@code handle(...)} without dragging Spring web along.
 *
 * <p>Per the service-layer convention, controllers don't depend on
 * other controllers — wiring goes through services.
 */
@RestController
public class McpController
{
    private final McpServiceImpl service;

    public McpController(McpServiceImpl service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    /** Header a Streamable-HTTP MCP server uses to hand the client a
     *  session id at {@code initialize} time. We use the thread id — it is
     *  stable, unique, and visible-ASCII, which is all the spec requires. */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    /**
     * The mapped entry point. Adds the two pieces of Streamable-HTTP framing
     * that a strict client needs but the in-JVM / direct callers don't, then
     * delegates to the transport-agnostic JSON-RPC handler below:
     *
     * <ul>
     *   <li><b>{@code Mcp-Session-Id} header</b> — the spec lets a server
     *       assign a session at {@code initialize}; a strict client carries
     *       it forward on every later request. Emitting it costs nothing and
     *       satisfies clients that expect one.</li>
     *   <li><b>HTTP 202 for notifications</b> — the Streamable-HTTP spec says
     *       a JSON-RPC <em>notification</em> (no {@code id}, e.g.
     *       {@code notifications/initialized}) MUST be answered with
     *       {@code 202 Accepted} and an empty body. Returning a plain 200 (as
     *       a bare {@code @ResponseBody} would) makes the stricter clients —
     *       notably the {@code rmcp} Streamable-HTTP client Codex uses — treat
     *       the handshake as a transport failure ("Transport channel closed,
     *       when send initialized notification") and abort <em>before</em>
     *       ever calling {@code tools/list}. Claude's HTTP client tolerates
     *       the 200, which is why it lists/uses our tools and Codex did not.</li>
     * </ul>
     */
    /**
     * Explicit runtime entry point. The URL carries either a task id or the
     * reserved trunk key, so role/capability resolution targets that runtime.
     */
    @PostMapping("/api/threads/{threadId}/agents/{agentKey}/mcp")
    public DeferredResult<JsonNode> handle(
            @PathVariable String threadId,
            @PathVariable String agentKey,
            @RequestBody JsonNode request,
            HttpServletResponse response)
    {
        return dispatch(threadId, agentKey, request, response);
    }

    private DeferredResult<JsonNode> dispatch(
            String threadId, String agentKey, JsonNode request, HttpServletResponse response)
    {
        response.setHeader(SESSION_HEADER, threadId == null ? "" : threadId);
        if (isNotification(request)) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
        }
        return handle(threadId, agentKey, request);
    }

    /** A JSON-RPC notification carries no {@code id}; the JSON-RPC methods we
     *  answer with no body are exactly the {@code notifications/*} ones. */
    private static boolean isNotification(JsonNode request)
    {
        return request != null
                && request.isObject()
                && request.path("method").asText("").startsWith("notifications/");
    }

    /**
     * The transport-agnostic core: validates the envelope and hands off to
     * the service. Kept separate (and unmapped) so the in-JVM lane and the
     * unit suite can exercise the JSON-RPC contract without a servlet
     * response in hand.
     */
    DeferredResult<JsonNode> handle(
            String threadId,
            String agentKey,
            JsonNode request)
    {
        // First-round transport-level validation. Anything deeper —
        // jsonrpc version, method name catalog, params shape, per-tool
        // args — stays in the service where it surfaces as the proper
        // JSON-RPC error envelope (-32600 / -32601 / -32602 / -32700)
        // rather than an HTTP 4xx, because clients of JSON-RPC expect
        // protocol errors over HTTP 200 by convention.
        if (threadId == null || threadId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "threadId is required");
        }
        if (agentKey == null || agentKey.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "agentKey is required");
        }
        if (request == null || request.isMissingNode() || request.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "request body is required");
        }
        if (!request.isObject()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "request body must be a JSON-RPC object");
        }
        return service.handle(threadId, agentKey, request);
    }
}
