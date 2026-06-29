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

import com.bytequay.app.beans.mcp.ToolCallParams;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Bundle of everything a {@link ToolHandler} needs for one
 * {@code tools/call}: the JSON-RPC id (for framing the response),
 * the bound params record, the thread the call belongs to, and the
 * role and grants the dispatcher already resolved when role-
 * checking the call.
 *
 * <p>Bundling these into one record means a handler's signature
 * stays a one-arg {@code handle(ctx, deferred)} regardless of how
 * many of the bits it actually reads — a future handler that needs
 * the grants set doesn't shift the interface for everyone else.
 */
public record ToolDispatchContext(
        String threadId,
        /** The task this turn is bound to, from the running turn's stamped
         *  task_id (null for a trunk turn). Authoritative — never re-derived
         *  from a thread-level "active task" guess. */
        String taskId,
        JsonNode id,
        ToolCallParams params,
        AgentRole role,
        Set<SecurityType> grants,
        ToolSpec spec)
{
    /** No task bound to the turn (a trunk turn, or a caller that doesn't
     *  carry task scope). */
    public ToolDispatchContext(
            String threadId, JsonNode id, ToolCallParams params,
            AgentRole role, Set<SecurityType> grants, ToolSpec spec)
    {
        this(threadId, null, id, params, role, grants, spec);
    }
}
