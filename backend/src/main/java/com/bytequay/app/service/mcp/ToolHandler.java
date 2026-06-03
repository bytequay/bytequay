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
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Strategy for handling one MCP {@code tools/call} whose tool name
 * doesn't dispatch through the registry's default path — currently
 * {@code approval_prompt} and {@code run_shell}. The shared role
 * and capability gates run in {@link McpServiceImpl} before any
 * handler is invoked, so a handler may trust the call is authorised.
 *
 * <p>The interface keeps the dispatcher decoupled from each tool's
 * private flow: adding a new "needs custom flow" tool is a new
 * handler bean plus its {@link #toolName()} entry in the dispatch
 * map — no edit to {@link McpServiceImpl}'s {@code handleToolCall}.
 */
public interface ToolHandler
{
    /**
     * Short MCP tool name this handler is registered for (no
     * {@code mcp__bytequay__} prefix — that's a CLI-side artifact of
     * the permission-prompt path, not the on-wire name). Used by
     * {@link McpServiceImpl} to build the dispatch map at startup.
     */
    String toolName();

    /**
     * Handle the call. Sets {@code deferred}'s result either
     * synchronously (most paths) or asynchronously when the call
     * has to wait on the permission gate. The dispatcher has
     * already verified role visibility and capability grants for
     * the tool itself; per-tool concerns (parked state, autonomy
     * envelope, pre-approved budget) live inside the handler.
     */
    void handle(ToolDispatchContext ctx, DeferredResult<JsonNode> deferred);
}
