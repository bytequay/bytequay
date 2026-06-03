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
import com.bytequay.app.beans.mcp.UnattendedGatePayload;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.AgentToolRegistry;
import com.bytequay.app.service.tools.Gating;
import com.bytequay.app.service.tools.SecurityType;
import com.bytequay.app.service.tools.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Handles {@code approval_prompt}: Claude's
 * {@code --permission-prompt-tool} target. Walks a series of
 * checks — parked-state guard, special-case for AskUserQuestion,
 * auto-gated target tools, pre-approved budgets, the unattended
 * autonomy envelope — and falls through to registering a user
 * prompt surfaced in the conversation pane.
 *
 * <p>The body is one long sequence today; commit 2 splits it into
 * an {@code ApprovalStep} chain so each check is independently
 * testable.
 */
@Component
public class ApprovalPromptHandler
        implements ToolHandler
{
    private static final Logger log = LoggerFactory.getLogger(ApprovalPromptHandler.class);

    /** Short MCP tool name; the dispatcher maps this verbatim. */
    public static final String NAME = "approval_prompt";

    /** Claude Code prefixes MCP tool names with {@code mcp__<server>__}
     *  when passing them to the permission-prompt tool. Stripping it
     *  lets us look the target tool up in the registry by its short
     *  name. Built-in Claude tools (Bash / Edit / Read) come through
     *  unprefixed and miss the registry lookup, which is the safe
     *  fall-through to the normal prompt path. */
    private static final String MCP_TOOL_PREFIX = "mcp__bytequay__";

    /** Cap on the serialised input snippet shown next to a permission
     *  card — longer payloads get truncated with an ellipsis. */
    private static final int PROMPT_SUMMARY_CAP = 240;

    private final ThreadService threads;
    private final TaskStore taskStore;
    private final ThreadTurnStore turnStore;
    private final McpPermissionGate gate;
    private final NotificationService notifications;
    private final AgentToolRegistry registry;
    private final McpResponses responses;

    public ApprovalPromptHandler(
            ThreadService threads,
            TaskStore taskStore,
            ThreadTurnStore turnStore,
            McpPermissionGate gate,
            NotificationService notifications,
            AgentToolRegistry registry,
            McpResponses responses)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public String toolName()
    {
        return NAME;
    }

    @Override
    public void handle(ToolDispatchContext ctx, DeferredResult<JsonNode> deferred)
    {
        JsonNode id = ctx.id();
        String threadId = ctx.threadId();
        ApprovalPromptArgs args;
        try {
            args = responses.bindArgs(ctx.params().arguments(), ApprovalPromptArgs.class);
        }
        catch (JsonProcessingException e) {
            deferred.setResult(responses.error(id, -32602,
                    "invalid approval_prompt args: " + e.getMessage()));
            return;
        }
        String toolName = args.toolName() == null ? "" : args.toolName();
        String callId = args.toolUseId() == null ? "" : args.toolUseId();
        JsonNode toolInput = args.input();
        if (callId.isEmpty()) {
            deferred.setResult(responses.error(id, -32602, "tool_use_id is required"));
            return;
        }

        // Structural park-guard. Once a task on this thread is at
        // AWAITING_REVIEW or NEEDS_ATTENTION the agent has finished
        // its turn from the user's perspective — further built-in
        // tool calls (Edit, Write, Bash, …) must not silently fire
        // a permission prompt as if work were still in progress, and
        // a pre-approved budget must not let one slip through either.
        if (isThreadParked(threadId)) {
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "This thread is parked at the publish gate. The user must "
                            + "approve or discard the proposed change before further "
                            + "tool calls are accepted. STOP NOW: end the turn "
                            + "immediately, do not attempt further tools, do not "
                            + "apologize.")));
            return;
        }

        // AskUserQuestion is Claude asking the user something. The
        // CLI runs in non-interactive mode, so the built-in tool
        // returns an empty answer immediately. We render the question
        // as a rich card in our conversation view, then deny here so
        // Claude ends the turn and waits — the user's reply arrives
        // as the next chat message.
        if ("AskUserQuestion".equals(toolName)) {
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "SUCCESS — your question has been rendered to the user as "
                            + "a rich card showing every option. STOP NOW: do not "
                            + "write any further assistant text in this turn, do not "
                            + "re-ask the question in prose, do not explain or "
                            + "apologize, do not summarize the options. End the turn "
                            + "immediately. The user will type their reply into the "
                            + "chat input and you will see it as the next user "
                            + "message.")));
            return;
        }

        // Gating dispatcher: honour the target tool's declared
        // Gating.AUTO so safe read-only tools (list_skills, read_*,
        // recall_thread, …) never spin on a prompt the user has no
        // reason to answer.
        ToolSpec gatingTarget = registry.byName(stripMcpServerPrefix(toolName)).orElse(null);
        if (gatingTarget != null && gatingTarget.gating() == Gating.AUTO) {
            deferred.setResult(responses.toolResponse(id, responses.allow(toolInput)));
            return;
        }

        // If the user has pre-approved this tool ("Allow next 5",
        // "Always for this tool"), drain one slot and resolve without
        // ever showing a prompt.
        OptionalInt remaining = threads.tryConsumeToolBudget(threadId, toolName);
        if (remaining.isPresent()) {
            notePermissionAutoAllowed(threadId, callId, toolName, remaining.getAsInt());
            deferred.setResult(responses.toolResponse(id, responses.allow(toolInput)));
            return;
        }

        // Autonomy envelope. An unattended turn (e.g. the CI auto-fix
        // coordinator) has no human to answer a prompt. Decide by
        // capability: in-bounds tools run under the standing policy
        // without a prompt; out-of-bounds escalate to a needs-
        // attention notification and deny.
        if (isUnattended(threadId)) {
            SecurityType capability = capabilityForBuiltinTool(toolName);
            if (capability != null && ctx.grants().contains(capability)) {
                notePermissionAutoAllowed(threadId, callId, toolName, -1);
                deferred.setResult(responses.toolResponse(id, responses.allow(toolInput)));
                return;
            }
            escalateUnattendedGate(threadId, toolName, toolInput);
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "This turn is running unattended and '" + toolName + "' is outside its "
                            + "autonomy envelope. The request has been escalated to the user. "
                            + "STOP NOW: end the turn immediately, do not retry, do not apologize.")));
            return;
        }

        // Pass the tool name so a later `Allow next N` grant on the
        // same tool can drain still-pending callIds in one click
        // instead of leaving the user with a backlog of prompts.
        CompletableFuture<PermissionDecision> decisionFuture = gate.register(callId, toolName);
        CompletableFuture<PermissionDecision> responseFuture = decisionFuture
                .whenComplete((decision, ex) -> completePrompt(deferred, id, toolInput, decision, ex));

        // Close the race where another prompt grants a budget after
        // our first budget check but before this call is visible in
        // the gate. Register first, then re-check before showing the
        // prompt; a hit completes through the same response future.
        remaining = threads.tryConsumeToolBudget(threadId, toolName);
        if (remaining.isPresent()) {
            notePermissionAutoAllowed(threadId, callId, toolName, remaining.getAsInt());
            gate.decide(callId, PermissionDecision.ALLOW);
            return;
        }
        if (decisionFuture.isDone()) {
            return;
        }

        // Surface the prompt in the conversation pane after the call
        // is registered so a concurrent `Allow next N` can drain it.
        try {
            threads.notifyPermissionRequested(threadId, callId, toolName, summarize(toolName, toolInput));
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface permission prompt for thread {}: {}",
                    threadId, e.getMessage());
        }
        deferred.onTimeout(() -> {
            gate.cancel(callId);
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("timed out waiting for the user")));
        });
        deferred.onCompletion(() -> {
            responseFuture.cancel(false);
            gate.cancel(callId);
        });
    }

    private void completePrompt(
            DeferredResult<JsonNode> deferred,
            JsonNode id,
            JsonNode toolInput,
            PermissionDecision decision,
            Throwable ex)
    {
        if (ex != null) {
            deferred.setResult(responses.toolResponse(id,
                    responses.deny("interrupted: " + ex.getMessage())));
        }
        else if (decision == PermissionDecision.ALLOW) {
            deferred.setResult(responses.toolResponse(id, responses.allow(toolInput)));
        }
        else {
            deferred.setResult(responses.toolResponse(id, responses.deny("user denied")));
        }
    }

    /** Best-effort: record the auto-approval notice without letting a
     *  notification failure tank the tool call. */
    private void notePermissionAutoAllowed(String threadId, String callId, String toolName, int remaining)
    {
        try {
            threads.notifyPermissionAutoAllowed(threadId, callId, toolName, remaining);
        }
        catch (RuntimeException e) {
            log.warn("Failed to record auto-approval notice for thread {}: {}",
                    threadId, e.getMessage());
        }
    }

    private static String summarize(String toolName, JsonNode input)
    {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return toolName;
        }
        String s = input.toString();
        return s.length() > PROMPT_SUMMARY_CAP ? s.substring(0, PROMPT_SUMMARY_CAP - 3) + "…" : s;
    }

    /** True when the thread's in-flight turn was started by an
     *  automated trigger rather than a person. Absent a running turn
     *  we treat the call as attended — the safe default keeps the
     *  existing prompt flow rather than auto-allowing or escalating
     *  on a guess. */
    private boolean isUnattended(String threadId)
    {
        return runningTurn(threadId)
                .map(ThreadTurn::initiator)
                .map(initiator -> !initiator.attended())
                .orElse(false);
    }

    private Optional<ThreadTurn> runningTurn(String threadId)
    {
        return turnStore.listTurnsByTaskIdAndStatus(threadId, ThreadTurnStatus.RUNNING, 1)
                .stream()
                .findFirst();
    }

    /** Drop the {@code mcp__bytequay__} prefix Claude Code prepends
     *  to MCP tool names so the registry lookup matches the short
     *  name a tool is registered under. Pass-through for built-in
     *  tool names (Bash / Edit / Read), which don't carry the prefix
     *  and aren't in the registry — those miss the lookup and fall
     *  through cleanly. */
    private static String stripMcpServerPrefix(String toolName)
    {
        return toolName != null && toolName.startsWith(MCP_TOOL_PREFIX)
                ? toolName.substring(MCP_TOOL_PREFIX.length())
                : toolName;
    }

    /** Map a Claude built-in tool to the capability it exercises, so
     *  an unattended turn's grants can decide whether it is in-bounds.
     *  Returns {@code null} for tools with no capability mapping (web
     *  access, sub-agents, …) — those are out-of-bounds for an
     *  unattended turn and escalate. */
    private static SecurityType capabilityForBuiltinTool(String toolName)
    {
        return switch (toolName) {
            case "Edit", "Write", "MultiEdit", "NotebookEdit" -> SecurityType.CODE_WRITE;
            case "Read", "Glob", "Grep", "LS" -> SecurityType.CODE_READ;
            case "Bash", "BashOutput", "KillShell" -> SecurityType.CODE_EXEC;
            default -> null;
        };
    }

    /** Escalate an out-of-bounds tool request on an unattended turn
     *  to a needs-attention notification so the human can take over.
     *  Best effort — a failure to persist the notice must not turn
     *  into a protocol error on the agent's deny response. */
    private void escalateUnattendedGate(String threadId, String toolName, JsonNode toolInput)
    {
        try {
            String taskId = runningTurn(threadId).map(ThreadTurn::taskId).orElse(null);
            UnattendedGatePayload payload = new UnattendedGatePayload(toolName, summarize(toolName, toolInput));
            notifications.notifyNeedsAttention(threadId, taskId, responses.mapper().writeValueAsString(payload));
        }
        catch (RuntimeException | JsonProcessingException e) {
            log.warn("Failed to escalate unattended gate for thread {}: {}",
                    threadId, e.getMessage());
        }
    }

    /** True when this thread has an unresolved blocking parked
     *  state. A successfully approved {@code next_task} deliberately
     *  leaves its prior sibling in {@code AWAITING_REVIEW} while
     *  work continues in the newly active task, so a historical
     *  parked row must not block prompts from that successor. In
     *  contrast, NEEDS_ATTENTION remains blocking until the user
     *  resolves it. */
    private boolean isThreadParked(String threadId)
    {
        List<Task> tasks = taskStore.listTasksByThread(threadId);
        if (tasks.stream().anyMatch(t -> t.status() == TaskStatus.NEEDS_ATTENTION)) {
            return true;
        }
        return taskStore.findActiveTaskForThread(threadId).isEmpty()
                && tasks.stream().anyMatch(t -> t.status() == TaskStatus.AWAITING_REVIEW);
    }
}
