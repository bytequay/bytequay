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
        JsonNode id,
        String toolName,
        String callId,
        JsonNode toolInput,
        Set<SecurityType> grants)
{
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
