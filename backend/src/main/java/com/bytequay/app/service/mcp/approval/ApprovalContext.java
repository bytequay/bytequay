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
package com.bytequay.app.service.mcp.approval;

import com.bytequay.app.service.tools.SecurityType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Bundle the inputs an {@link ApprovalStep} needs to decide on one
 * {@code approval_prompt} call. The {@code id} stays here only so a
 * step can frame its {@link ApprovalStepResult.Resolve} response —
 * everything else is information about the target tool the agent
 * is asking permission for.
 */
public record ApprovalContext(
        String threadId,
        /** The task this turn is bound to (the running turn's stamped task_id;
         *  null for a trunk turn). Authoritative — gates use this instead of
         *  guessing the thread's "active task". */
        String taskId,
        /** The registry agent key of the agent that issued this call (== the
         *  stage key). Lets a registered permission prompt / auto-allow event
         *  route back to the exact stage agent that raised it. */
        String agentKey,
        JsonNode id,
        String toolName,
        String callId,
        JsonNode toolInput,
        Set<SecurityType> grants)
{
    /** A context with no task / agent scope bound (a trunk turn, or a caller
     *  that doesn't exercise task-scoped gating). Task-scoped steps fail
     *  closed when {@code taskId} is null, so this is the safe default. */
    public ApprovalContext(
            String threadId, JsonNode id, String toolName, String callId,
            JsonNode toolInput, Set<SecurityType> grants)
    {
        this(threadId, null, null, id, toolName, callId, toolInput, grants);
    }

    /** Carries task scope but no agent key — a caller that resolves the task
     *  from the running turn but doesn't route the issuing agent (tests, and
     *  non-MCP paths). */
    public ApprovalContext(
            String threadId, String taskId, JsonNode id, String toolName,
            String callId, JsonNode toolInput, Set<SecurityType> grants)
    {
        this(threadId, taskId, null, id, toolName, callId, toolInput, grants);
    }

    /** Claude Code prefixes MCP tool names with {@code mcp__<server>__};
     *  the registry and gating logic key off the short name. */
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";

    /** The CLI shell built-ins the gate special-cases. */
    private static final Set<String> SHELL_TOOLS = Set.of("run_shell", "Bash");

    /** The tool name with the {@code mcp__<server>__} prefix stripped —
     *  the form the {@code AgentToolRegistry} and gating steps look up. */
    public String shortToolName()
    {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX)
                ? toolName.substring(MCP_TOOL_PREFIX.length())
                : toolName;
    }

    /** True when this call targets a shell tool ({@code run_shell} /
     *  {@code Bash}), the only tools that carry a {@code command}. */
    public boolean isShellTool()
    {
        return SHELL_TOOLS.contains(shortToolName());
    }

    /** The shell {@code command} string from the tool input, or {@code ""}
     *  when absent / non-textual. */
    public String shellCommand()
    {
        if (toolInput == null) {
            return "";
        }
        JsonNode command = toolInput.get("command");
        return command != null && command.isTextual() ? command.asText() : "";
    }
}
