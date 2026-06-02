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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import static java.util.Objects.requireNonNull;

/**
 * HTTP entry point for the MCP server. Implements {@link McpService}
 * so Spring picks up the URL and HTTP-verb annotations from the
 * interface; every method here delegates straight to the injected
 * service impl so the controller layer holds no business logic of its
 * own. Per the service-layer convention, controllers don't depend on
 * other controllers — wiring goes through services.
 */
@RestController
public class McpController
        implements McpService
{
    private final McpService service;

    public McpController(McpService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    @Override
    public DeferredResult<JsonNode> handle(String threadId, JsonNode request)
    {
        return service.handle(threadId, request);
    }
}
