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
 * Honour the target tool's declared {@link Gating#PARKED} contract:
 * a PARKED tool "never calls the remote / writes directly — it parks
 * the active task at AWAITING_REVIEW and the user's Approve drives the
 * real publish." So the per-call permission prompt is the wrong gate for
 * it — running the tool merely <em>registers</em> the parked proposal,
 * and the human gate is approving that proposal in the notification
 * surface, not a blocking prompt.
 *
 * <p>Auto-allowing the call here lets {@code push} / {@code open_pr} /
 * {@code merge_pr} / {@code request_review} / … park their proposal and
 * return a clean "parked at AWAITING_REVIEW" result. Without it the call
 * fell through to the blocking user prompt, hit the 2-minute decision
 * timeout, got hard-denied, and the agent fell back to raw {@code git
 * push} / the GitHub API — bypassing the gate entirely. Safe because
 * PARKED tools touch nothing remote on their own (the proposal approval
 * is still required).
 *
 * <p>Ordered after {@link AutoGatingStep} and well after
 * {@code ParkGuardStep} (@Order 100), so a thread already parked at the
 * publish gate still refuses a fresh parked-tool call until the user
 * resolves the open proposal — no proposal pile-up.
 */
@Component
@Order(305)
public class ParkedToolStep
        implements ApprovalStep
{
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";

    private final AgentToolRegistry registry;
    private final McpResponses responses;

    public ParkedToolStep(AgentToolRegistry registry, McpResponses responses)
    {
        this.registry = requireNonNull(registry, "registry is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        ToolSpec target = registry.byName(stripMcpServerPrefix(ctx.toolName())).orElse(null);
        if (target == null || target.gating() != Gating.PARKED) {
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
