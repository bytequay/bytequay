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

import com.bytequay.app.service.mcp.McpResponses;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.ToolSpec;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Honour the target tool's declared {@link Gating#AUTO} so safe
 * read-only tools (list_skills, list_tools, load_skill, read_*,
 * recall_thread, …) never spin on a prompt the user has no reason
 * to answer.
 */
@Component
@Order(300)
public class AutoGatingStep
        implements ApprovalStep
{
    /** Claude Code prefixes MCP tool names with {@code mcp__<server>__}
     *  when passing them to the permission-prompt tool. Stripping it
     *  lets us look the target tool up in the registry by its short
     *  name. Built-in Claude tools (Bash / Edit / Read) come through
     *  unprefixed and miss the registry lookup, which is the safe
     *  fall-through to the normal prompt path. */
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";

    private final AgentToolRegistry registry;
    private final McpResponses responses;

    public AutoGatingStep(AgentToolRegistry registry, McpResponses responses)
    {
        this.registry = requireNonNull(registry, "registry is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        ToolSpec target = registry.byName(stripMcpServerPrefix(ctx.toolName())).orElse(null);
        if (target == null || target.gating() != Gating.AUTO) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.allow(ctx.toolInput())));
    }

    private static String stripMcpServerPrefix(String toolName)
    {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX)
                ? toolName.substring(MCP_TOOL_PREFIX.length())
                : toolName;
    }
}
