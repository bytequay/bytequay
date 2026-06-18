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

import com.bytequay.app.service.review.ReviewMcpService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * The MCP (JSON-RPC over HTTP) endpoint a Claude CLI reviewer subprocess
 * connects to, scoped to one review pass + seat. Mirrors the thread-agent
 * {@code McpController}, but routes to the review tool server.
 */
@RestController
@RequestMapping("/api/reviews/{passId}/seats/{participantId}/mcp")
public class ReviewMcpController
{
    private final ReviewMcpService service;

    public ReviewMcpController(ReviewMcpService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    @PostMapping
    public JsonNode handle(
            @PathVariable String passId,
            @PathVariable String participantId,
            @RequestBody JsonNode request)
    {
        if (passId == null || passId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "passId is required");
        }
        if (participantId == null || participantId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "participantId is required");
        }
        if (request == null || request.isMissingNode() || request.isNull() || !request.isObject()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "request body must be a JSON-RPC object");
        }
        return service.handle(passId, participantId, request);
    }
}
