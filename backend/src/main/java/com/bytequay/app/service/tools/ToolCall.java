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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.ThreadScope;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lane-neutral context for one tool invocation. Both the MCP server
 * and the future in-JVM lane build a {@code ToolCall} and hand it to
 * {@link AgentToolRegistry#invoke}; the handler reads the thread /
 * role from here and gets its typed args bound separately from
 * {@link #arguments}.
 *
 * @param threadId   the thread the call runs in
 * @param arguments  the raw JSON arguments the model emitted — the
 *                   registry binds these into the handler's typed
 *                   args record, but handlers can also read them
 *                   directly when a field isn't on the record
 * @param role       the caller's resolved agent role
 * @param taskId     the task the in-flight turn is scoped to (from the
 *                   running turn's stamped {@code task_id}), or null on a
 *                   trunk turn. Publish handlers resolve their task from
 *                   this rather than guessing the thread's active task,
 *                   which is null for a shipped (IN_REVIEW) task.
 * @param stageId    the stage the in-flight turn is scoped to, or null for
 *                   a task-level / trunk turn
 * @param agentRunId the AgentRun episode the in-flight turn belongs to, or
 *                   null for ordinary task/stage turns
 * @param scope      authoritative trunk/task/stage scope copied from the turn
 */
public record ToolCall(
        String threadId, JsonNode arguments, AgentRole role,
        String taskId, String stageId, String agentRunId, ThreadScope scope,
        String agentKey, String callId)
{
    public ToolCall
    {
        PermissionResolver.agentKeyFor(scope, taskId);
        if (scope == ThreadScope.STAGE && (stageId == null || stageId.isBlank())) {
            throw new IllegalArgumentException("STAGE tool call requires stageId");
        }
        if (scope != ThreadScope.STAGE && stageId != null && !stageId.isBlank()) {
            throw new IllegalArgumentException(scope + " tool call forbids stageId");
        }
        if (callId != null && callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
    }

    public String runtimeAgentKey()
    {
        return agentKey == null || agentKey.isBlank()
                ? PermissionResolver.agentKeyFor(scope, taskId) : agentKey;
    }

    public String requireTaskId()
    {
        if (scope == ThreadScope.TRUNK || taskId == null || taskId.isBlank()) {
            throw new IllegalStateException(scope + " tool call has no taskId");
        }
        return taskId;
    }

    public String requireStageId()
    {
        if (scope != ThreadScope.STAGE || stageId == null || stageId.isBlank()) {
            throw new IllegalStateException(scope + " tool call has no stageId");
        }
        return stageId;
    }

    public ToolCall(
            ThreadScope scope, String threadId, JsonNode arguments, AgentRole role,
            String taskId, String stageId, String agentRunId)
    {
        this(threadId, arguments, role, taskId, stageId, agentRunId, scope, null,
                null);
    }

    public ToolCall(
            String threadId, JsonNode arguments, AgentRole role,
            String taskId, String stageId, String agentRunId, ThreadScope scope)
    {
        this(threadId, arguments, role, taskId, stageId, agentRunId, scope, null,
                null);
    }

    public ToolCall(
            ThreadScope scope, String threadId, JsonNode arguments, AgentRole role,
            String taskId, String stageId)
    {
        this(threadId, arguments, role, taskId, stageId, null, scope, null,
                null);
    }

    public ToolCall(ThreadScope scope, String threadId, JsonNode arguments, AgentRole role)
    {
        this(threadId, arguments, role, null, null, null, scope, null, null);
    }
}
