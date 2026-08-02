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
import com.bytequay.app.service.threads.ThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

/**
 * If the user has pre-approved this tool ("Allow next 5", "Always
 * for this tool"), drain one slot and resolve without ever showing
 * a prompt. We surface a {@code permission_auto_allowed} notice
 * next to the tool call so the user can see which slot was burned
 * and how many are left.
 *
 * <p>Note: there is a second, race-window budget re-check inside
 * {@link com.bytequay.app.service.mcp.ApprovalPromptHandler} after
 * the gate register. That check has to live alongside the gate
 * because it's coupled to the gate registration's ordering — it
 * can't move out into a step without leaking the gate's lifecycle.
 */
@Component
@Order(400)
public class BudgetStep
        implements ApprovalStep
{
    private static final Logger log = LoggerFactory.getLogger(BudgetStep.class);

    private final ThreadService threads;
    private final McpResponses responses;

    public BudgetStep(ThreadService threads, McpResponses responses)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        // V2 grants and PermissionRequests belong to the exact typed Turn.
        // Calling ThreadService here crosses back into the retired nullable
        // legacy permission session and fails before the typed dispatcher can
        // consume or create its durable permission record.
        if (ctx.isTypedV2Owner()) {
            return ApprovalStepResult.cont();
        }
        OptionalInt remaining = threads.tryConsumeToolBudget(
                ctx.threadId(), ctx.agentKey(), ctx.toolName());
        if (remaining.isEmpty()) {
            return ApprovalStepResult.cont();
        }
        notePermissionAutoAllowed(
                ctx.threadId(), ctx.agentKey(), ctx.callId(), ctx.toolName(), remaining.getAsInt());
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.allow(ctx.toolInput())));
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
}
