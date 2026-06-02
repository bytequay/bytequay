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

import com.bytequay.app.service.mcp.McpService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import static java.util.Objects.requireNonNull;

/**
 * HTTP entry point for the MCP server. Owns the URL surface
 * ({@code @RequestMapping}) and the HTTP-verb binding
 * ({@code @PostMapping} / {@code @PathVariable} / {@code @RequestBody})
 * because those are transport concerns; the
 * {@link com.bytequay.app.service.mcp.McpService} interface stays
 * HTTP-agnostic so the same business contract can be exercised by an
 * in-JVM lane, a CLI dispatcher, or anything else that wants to call
 * {@code handle(...)} without dragging Spring web along.
 *
 * <p>Per the service-layer convention, controllers don't depend on
 * other controllers — wiring goes through services.
 */
@RestController
@RequestMapping("/api/threads/{threadId}/mcp")
public class McpController
{
    private final McpService service;

    public McpController(McpService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    @PostMapping
    public DeferredResult<JsonNode> handle(
            @PathVariable String threadId,
            @RequestBody JsonNode request)
    {
        return service.handle(threadId, request);
    }
}
