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
package com.bytequay.app.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Minimal MCP server exposed over HTTP, one URL per thread. Claude
 * Code is configured via {@code --mcp-config} to talk to this
 * endpoint, and via {@code --permission-prompt-tool} to route tool
 * approvals through our single {@code approval_prompt} tool.
 *
 * <p>Only three JSON-RPC methods are implemented — {@code initialize},
 * {@code tools/list}, {@code tools/call} — because that is all
 * {@code --permission-prompt-tool} actually invokes. Other MCP
 * surfaces (resources, prompts, sampling) are not used and would
 * just be dead code.
 *
 * <p>The {@code tools/call} handler does not block its Tomcat worker
 * thread — it returns a {@link DeferredResult} that Spring resumes
 * once the user clicks Allow / Deny in the conversation pane.
 *
 * <p>The REST contract lives on this interface (URL prefix, HTTP verb,
 * binding annotations); the controller class is a thin
 * {@code @RestController} that implements the interface and delegates
 * each method to the {@code @Service} implementation. Wiring Spring
 * annotations on the interface keeps the API surface in one place and
 * lets alternate transports (a CLI dispatcher, an in-JVM lane) reuse
 * the same contract without dragging the HTTP layer along.
 */
@RequestMapping("/api/threads/{threadId}/mcp")
public interface McpService
{
    /**
     * Handle one JSON-RPC request for the given thread. Returns a
     * {@link DeferredResult} so the {@code tools/call} permission-
     * prompt path can park the Tomcat thread until the user clicks
     * Allow / Deny; synchronous methods ({@code initialize},
     * {@code tools/list}) resolve immediately.
     */
    @PostMapping
    DeferredResult<JsonNode> handle(
            @PathVariable String threadId,
            @RequestBody JsonNode request);
}
