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
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.service.mcp.approval.ApprovalContext;
import com.bytequay.app.service.mcp.approval.ApprovalStep;
import com.bytequay.app.service.mcp.approval.ApprovalStepResult;
import com.bytequay.app.service.threads.McpPermissionGate;
import com.bytequay.app.service.threads.ThreadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

/**
 * Handles {@code approval_prompt}: Claude's
 * {@code --permission-prompt-tool} target. Delegates the early-exit
 * checks (parked state, AskUserQuestion special-case, auto-gated
 * target, pre-approved budget, autonomy envelope) to a chain of
 * {@link ApprovalStep} beans walked in {@code @Order} order, then
 * handles the terminal "register a user prompt and park the call"
 * flow inline because it owns the deferred wiring (gate lifecycle,
 * race-window budget re-check, timeout / completion callbacks).
 *
 * <p>Adding a new policy is one new {@link ApprovalStep} bean and
 * its {@code @Order} value — no edit here.
 */
@Component
public class ApprovalPromptHandler
        implements ToolHandler
{
    private static final Logger log = LoggerFactory.getLogger(ApprovalPromptHandler.class);

    /** Short MCP tool name; the dispatcher maps this verbatim. */
    public static final String NAME = "approval_prompt";

    /** Cap on the serialised input snippet shown next to a permission
     *  card — longer payloads get truncated with an ellipsis. */
    private static final int PROMPT_SUMMARY_CAP = 240;

    private final List<ApprovalStep> steps;
    private final ThreadService threads;
    private final McpPermissionGate gate;
    private final McpResponses responses;

    public ApprovalPromptHandler(
            List<ApprovalStep> steps,
            ThreadService threads,
            McpPermissionGate gate,
            McpResponses responses)
    {
        // Spring injects ApprovalStep beans in @Order ascending —
        // the chain walks the list as-is. Defensive copy so an
        // upstream mutation can't reorder mid-flight.
        this.steps = List.copyOf(requireNonNull(steps, "steps is null"));
        this.threads = requireNonNull(threads, "threads is null");
        this.gate = requireNonNull(gate, "gate is null");
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

        ApprovalContext approvalCtx = new ApprovalContext(
                threadId, ctx.taskId(), ctx.agentKey(), id, toolName, callId, toolInput, ctx.grants());
        for (ApprovalStep step : steps) {
            ApprovalStepResult result = step.apply(approvalCtx);
            if (result instanceof ApprovalStepResult.Resolve resolve) {
                deferred.setResult(resolve.response());
                return;
            }
        }
        registerUserPrompt(approvalCtx, deferred);
    }

    /**
     * Terminal step: no early check fired, so we register the
     * permission gate, re-check the budget to close the race
     * window between our first budget check and the gate register,
     * surface the prompt in the conversation pane, and wire the
     * deferred's timeout / completion callbacks. This stays inline
     * (not a chain step) because every part of it is coupled to
     * the deferred and the gate's lifecycle.
     */
    private void registerUserPrompt(ApprovalContext ctx, DeferredResult<JsonNode> deferred)
    {
        String threadId = ctx.threadId();
        String callId = ctx.callId();
        String toolName = ctx.toolName();
        JsonNode toolInput = ctx.toolInput();
        JsonNode id = ctx.id();

        // Pass the tool name so a later `Allow next N` grant on the
        // same tool can drain still-pending callIds in one click
        // instead of leaving the user with a backlog of prompts.
        CompletableFuture<PermissionDecision> decisionFuture =
                gate.register(callId, toolName, ctx.agentKey());
        CompletableFuture<PermissionDecision> responseFuture = decisionFuture
                .whenComplete((decision, ex) -> completePrompt(deferred, id, toolInput, decision, ex));

        // Close the race where another prompt grants a budget after
        // the BudgetStep's first check but before this call is
        // visible in the gate. Register first, then re-check before
        // showing the prompt; a hit completes through the same
        // response future.
        OptionalInt remaining = threads.tryConsumeToolBudget(threadId, toolName);
        if (remaining.isPresent()) {
            notePermissionAutoAllowed(threadId, ctx.agentKey(), callId, toolName, remaining.getAsInt());
            gate.decide(callId, PermissionDecision.ALLOW);
            return;
        }
        if (decisionFuture.isDone()) {
            return;
        }

        // Surface the prompt in the conversation pane after the call
        // is registered so a concurrent `Allow next N` can drain it.
        try {
            threads.notifyPermissionRequested(
                    threadId, ctx.agentKey(), callId, toolName, summarize(toolName, toolInput));
        }
        catch (RuntimeException e) {
            log.warn("Failed to surface permission prompt for thread {}: {}",
                    threadId, e.getMessage());
        }
        deferred.onTimeout(() -> {
            gate.cancel(callId);
            // Record the timeout as a denial in the conversation so the
            // prompt resolves instead of lingering as a forever-pending
            // card. gate is already cancelled, so decide() only writes the
            // decision row here (its gate hop is a no-op).
            try {
                threads.decide(threadId, callId, PermissionDecision.DENY);
            }
            catch (RuntimeException e) {
                log.warn("Failed to record approval timeout for thread {}: {}",
                        threadId, e.getMessage());
            }
            // Actionable, honest failure so the agent doesn't misread an
            // unanswered approval as a network problem and loop retrying.
            // The prompt is surfaced in the thread (trunk) and the task
            // window — if it wasn't answered, the fix is to approve it
            // there, not to retry the tool.
            deferred.setResult(responses.toolResponse(id, responses.deny(
                    "No response to the approval prompt for '" + toolName
                            + "'. Approve it in the thread or the task window, or run this from a"
                            + " task window — this is an approval timeout, not a network error."
                            + " Do not retry the tool until it's approved.")));
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

    /** Best-effort: record the auto-approval notice without letting
     *  a notification failure tank the tool call. */
    private void notePermissionAutoAllowed(
            String threadId, String agentKey, String callId, String toolName, int remaining)
    {
        try {
            threads.notifyPermissionAutoAllowed(threadId, agentKey, callId, toolName, remaining);
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
}
