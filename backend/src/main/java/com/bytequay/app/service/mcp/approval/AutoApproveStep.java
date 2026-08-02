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

import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.mcp.McpResponses;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Auto-approve mode (per-task, opt-in): when the turn's task has it on, allow
 * any tool prompt that reached this far without a user click. Ordered LAST
 * (after the deny steps — remote-git, park-guard — and the unattended
 * envelope, all lower {@code @Order}), so an explicit deny still wins and only
 * the prompts that would otherwise block on the user are auto-allowed.
 *
 * <p>The final PR merge is a parked {@code merge_pr} proposal, not a tool
 * prompt, so it is never auto-approved here — it stays manually gated, which
 * is the one thing auto-approve mode deliberately keeps in the user's hands.
 */
@Component
@Order(550)
public class AutoApproveStep
        implements ApprovalStep
{
    private final TaskStore taskStore;
    private final TaskManager.Store v2Tasks;
    private final McpResponses responses;

    public AutoApproveStep(
            TaskStore taskStore,
            TaskManager.Store v2Tasks,
            McpResponses responses)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.v2Tasks = requireNonNull(v2Tasks, "v2Tasks is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        if (ctx.taskId() == null || !autoApprove(ctx)) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.allow(ctx.toolInput())));
    }

    private boolean autoApprove(ApprovalContext ctx)
    {
        if (ctx.isTypedV2Owner()) {
            return v2Tasks.effectiveAutoApprove(ctx.taskId());
        }
        return taskStore.isAutoApprove(ctx.taskId());
    }
}
