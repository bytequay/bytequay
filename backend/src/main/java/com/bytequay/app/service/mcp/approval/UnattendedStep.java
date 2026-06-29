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

import com.bytequay.app.beans.mcp.UnattendedGatePayload;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.mcp.McpResponses;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.tools.SecurityType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Autonomy envelope for an unattended turn (e.g. the CI auto-fix
 * coordinator). There's no human to answer a prompt, so the
 * decision is by capability: if the built-in tool maps to a
 * capability the thread's grants allow, it is in-bounds and runs
 * under that standing policy without a prompt; otherwise it is
 * out-of-bounds and we escalate to a needs-attention notification
 * and deny, rather than registering a prompt that would only time
 * out.
 *
 * <p>For attended turns this step yields straight through.
 */
@Component
@Order(500)
public class UnattendedStep
        implements ApprovalStep
{
    private static final Logger log = LoggerFactory.getLogger(UnattendedStep.class);

    /** Cap on the serialised input snippet shown next to a needs-
     *  attention notification — longer payloads get truncated. */
    private static final int PAYLOAD_SUMMARY_CAP = 240;

    private final ThreadTurnStore turnStore;
    private final ThreadService threads;
    private final NotificationService notifications;
    private final McpResponses responses;

    public UnattendedStep(
            ThreadTurnStore turnStore,
            ThreadService threads,
            NotificationService notifications,
            McpResponses responses)
    {
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (!isUnattended(ctx.threadId())) {
            return ApprovalStepResult.cont();
        }
        SecurityType capability = capabilityForBuiltinTool(ctx.toolName());
        if (capability != null && ctx.grants().contains(capability)) {
            notePermissionAutoAllowed(ctx.threadId(), ctx.agentKey(), ctx.callId(), ctx.toolName());
            return ApprovalStepResult.resolve(
                    responses.toolResponse(ctx.id(), responses.allow(ctx.toolInput())));
        }
        escalateUnattendedGate(ctx.threadId(), ctx.toolName(), ctx.toolInput());
        return ApprovalStepResult.resolve(responses.toolResponse(ctx.id(), responses.deny(""
                + "This turn is running unattended and '" + ctx.toolName()
                + "' is outside its autonomy envelope. The request has been "
                + "escalated to the user. STOP NOW: end the turn immediately, "
                + "do not retry, do not apologize.")));
    }

    /** True when the thread's in-flight turn was started by an
     *  automated trigger rather than a person. Absent a running
     *  turn we treat the call as attended — the safe default keeps
     *  the existing prompt flow rather than auto-allowing or
     *  escalating on a guess. */
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

    /** Map a Claude built-in tool to the capability it exercises so
     *  an unattended turn's grants can decide whether it is in-
     *  bounds. Returns {@code null} for tools with no capability
     *  mapping (web access, sub-agents, …) — those are out-of-bounds
     *  for an unattended turn and escalate. */
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
     *  to a needs-attention notification so the human can take
     *  over. Best effort — a failure to persist the notice must not
     *  turn into a protocol error on the agent's deny response. */
    private void escalateUnattendedGate(String threadId, String toolName, JsonNode toolInput)
    {
        try {
            String taskId = runningTurn(threadId).map(ThreadTurn::taskId).orElse(null);
            UnattendedGatePayload payload = new UnattendedGatePayload(toolName, summarize(toolInput, toolName));
            notifications.notifyNeedsAttention(threadId, taskId, responses.mapper().writeValueAsString(payload));
        }
        catch (RuntimeException | JsonProcessingException e) {
            log.warn("Failed to escalate unattended gate for thread {}: {}",
                    threadId, e.getMessage());
        }
    }

    /** Best-effort: record the auto-approval notice without letting
     *  a notification failure tank the tool call. {@code -1} is the
     *  sentinel used elsewhere for "no countable remaining slots". */
    private void notePermissionAutoAllowed(String threadId, String agentKey, String callId, String toolName)
    {
        try {
            threads.notifyPermissionAutoAllowed(threadId, agentKey, callId, toolName, -1);
        }
        catch (RuntimeException e) {
            log.warn("Failed to record auto-approval notice for thread {}: {}",
                    threadId, e.getMessage());
        }
    }

    private static String summarize(JsonNode input, String fallback)
    {
        if (input == null || input.isMissingNode() || input.isNull()) {
            return fallback;
        }
        String s = input.toString();
        return s.length() > PAYLOAD_SUMMARY_CAP ? s.substring(0, PAYLOAD_SUMMARY_CAP - 3) + "…" : s;
    }
}
