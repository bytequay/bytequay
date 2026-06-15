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
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Auto-approve a shell call when its command is provably read-only (see
 * {@link ReadOnlyShellClassifier}) — so codebase exploration (find /
 * grep / git log …) doesn't make the user answer a prompt for every
 * harmless read. A command that isn't provably read-only falls through
 * to the normal user prompt.
 *
 * <p>Ordered just after {@link AutoGatingStep} (read-only <em>tools</em>)
 * and before {@link WorktreeEditStep}: {@code run_shell} / {@code Bash}
 * are never AUTO-gated by the registry, so this is the only step that can
 * clear a read-only shell without a prompt. Read-only shell never writes
 * or pushes, so the "nothing reaches GitHub without approval" invariant
 * is untouched.
 */
@Component
@Order(310)
public class ReadOnlyShellStep
        implements ApprovalStep
{
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";
    private static final Set<String> SHELL_TOOLS = Set.of("run_shell", "Bash");

    private final McpResponses responses;

    public ReadOnlyShellStep(McpResponses responses)
    {
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (!SHELL_TOOLS.contains(stripMcpServerPrefix(ctx.toolName()))) {
            return ApprovalStepResult.cont();
        }
        if (!ReadOnlyShellClassifier.isReadOnly(commandOf(ctx.toolInput()))) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.allow(ctx.toolInput())));
    }

    private static String commandOf(JsonNode input)
    {
        if (input == null) {
            return "";
        }
        JsonNode command = input.get("command");
        return command != null && command.isTextual() ? command.asText() : "";
    }

    private static String stripMcpServerPrefix(String toolName)
    {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX)
                ? toolName.substring(MCP_TOOL_PREFIX.length())
                : toolName;
    }
}
