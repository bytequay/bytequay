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

import com.bytequay.app.beans.mcp.ApprovalPromptArgs;
import com.bytequay.app.beans.mcp.RunShellArgs;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.AgentTool;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.SecurityType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * Minimal MCP server contract. Claude Code is configured via
 * {@code --mcp-config} to talk to one URL per thread, and via
 * {@code --permission-prompt-tool} to route tool approvals through the
 * {@code approval_prompt} tool declared below.
 *
 * <p>The interface is HTTP-agnostic by design — Spring REST annotations
 * live on {@link com.bytequay.app.web.McpController}, the controller's
 * job is the transport. What lives here is the <em>semantic</em>
 * contract: the dispatcher method and the two tool declarations the
 * registry scans at startup. Moving {@link AgentTool} onto the
 * interface means a glance at this file tells the reader exactly which
 * MCP tools this service surfaces, independent of whichever impl
 * fulfils the contract.
 *
 * <p>Only three JSON-RPC methods are implemented at the wire — {@code
 * initialize}, {@code tools/list}, {@code tools/call} — because that
 * is all {@code --permission-prompt-tool} actually invokes. Other MCP
 * surfaces (resources, prompts, sampling) are not used and would just
 * be dead code.
 */
public interface McpService
{
    /**
     * Handle one JSON-RPC request for the given thread. Returns a
     * {@link DeferredResult} so the {@code tools/call} permission-
     * prompt path can park the Tomcat thread until the user clicks
     * Allow / Deny; synchronous methods ({@code initialize},
     * {@code tools/list}) resolve immediately.
     */
    /**
     * Handle one JSON-RPC request for a specific agent on the thread.
     * {@code agentKey} identifies the connecting runtime — a task id for a
     * Task-owned agent or the reserved trunk key for the planning agent.
     * It scopes role / capability / running-turn resolution exactly.
     */
    DeferredResult<JsonNode> handle(String threadId, String agentKey, JsonNode request);

    /**
     * MCP {@code approval_prompt} — Claude's
     * {@code --permission-prompt-tool} target. Declared here so the
     * registry's startup scan finds the args record and registers the
     * tool catalog entry; dispatch flows through {@link #handle}'s
     * tool-call branch. Impls fulfil this with an empty body.
     */
    @AgentTool(
            name = "approval_prompt",
            description = "Asks the user to allow or deny a tool call. "
                    + "Returns a JSON envelope with behavior=allow|deny.",
            security = SecurityType.MCP,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    void declareApprovalPrompt(ApprovalPromptArgs args);

    /**
     * MCP {@code run_shell} — the escape-hatch tool that runs a
     * bounded shell command in the active task's worktree, gated on
     * each call by a user prompt. Declared here for the same
     * registry-scan reason as {@link #declareApprovalPrompt}.
     */
    @AgentTool(
            name = "run_shell",
            description = "Run a bounded shell command in the active task's worktree. "
                    + "Each call surfaces a permission prompt to the user — no command "
                    + "runs without an explicit click. Policy: 60-second timeout, 256 KB "
                    + "output cap, plain argv only, no shell operators. Quotes and "
                    + "backslash escapes are honoured, so quote any path containing "
                    + "spaces. Use as an escape "
                    + "hatch for ad-hoc probes; prefer the test runner / ship_task / "
                    + "request_review for longer flows.",
            security = SecurityType.CODE_EXEC,
            gating = Gating.GATED,
            roles = AgentRole.TASK)
    void declareRunShell(RunShellArgs args);
}
