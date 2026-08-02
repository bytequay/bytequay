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

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.mcp.McpResponses;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Once a task on this thread is at {@code AWAITING_REVIEW} or
 * {@code NEEDS_ATTENTION} the agent has finished its turn from the
 * user's perspective. Further built-in tool calls (Edit, Write,
 * Bash, …) must not silently fire a permission prompt as if work
 * were still in progress, and a pre-approved budget must not let
 * one slip through either. This is the structural park-guard;
 * everything else in the chain (auto-gating, budget, autonomy) is
 * skipped once we deny here.
 */
@Component
@Order(100)
public class ParkGuardStep
        implements ApprovalStep
{
    private static final Set<TaskPhase> LOCAL_AGENT_PHASES = Set.of(
            TaskPhase.INTERNAL_REVIEW, TaskPhase.ADDRESSING_LOCAL_COMMENTS);

    private static final String PARK_DENY_MESSAGE = ""
            + "This thread is parked at the publish gate. The user must "
            + "approve or discard the proposed change before further "
            + "tool calls are accepted. STOP NOW: end the turn "
            + "immediately, do not attempt further tools, do not "
            + "apologize.";

    private final TaskStore taskStore;
    private final McpResponses responses;

    public ParkGuardStep(TaskStore taskStore, McpResponses responses)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.responses = requireNonNull(responses, "responses is null");
    }

    @Override
    public ApprovalStepResult apply(ApprovalContext ctx)
    {
        // Typed lifecycle state is owned by the exact V2 runtime; retained
        // legacy Task rows cannot park or unpark that Turn.
        if (ctx.isTypedV2Owner()) {
            return ApprovalStepResult.cont();
        }
        if (!isThreadParked(ctx.threadId(), ctx.taskId())) {
            return ApprovalStepResult.cont();
        }
        return ApprovalStepResult.resolve(
                responses.toolResponse(ctx.id(), responses.deny(PARK_DENY_MESSAGE)));
    }

    /** True when this thread has an unresolved blocking parked
     *  state. A successfully approved {@code next_task} deliberately
     *  leaves its prior sibling in {@code AWAITING_REVIEW} while
     *  work continues in the newly active task, so a historical
     *  parked row must not block prompts from that successor. In
     *  contrast, NEEDS_ATTENTION remains blocking until the user
     *  resolves it. */
    private boolean isThreadParked(String threadId, String taskId)
    {
        List<Task> tasks = taskStore.listTasksByThread(threadId);
        List<Task> scopedTasks = taskId == null
                ? tasks
                : tasks.stream().filter(t -> taskId.equals(t.id())).toList();
        if (scopedTasks.stream().anyMatch(t -> t.status() == TaskStatus.NEEDS_ATTENTION
                || t.phase() == TaskPhase.NEEDS_ATTENTION)) {
            return true;
        }
        // Legacy ship_task parks wrote AWAITING_REVIEW before the canonical
        // local Brain/fix loop finished. That stale runtime status must not
        // freeze the active local phase; its proposal is rejected separately.
        if (taskId != null && tasks.stream().anyMatch(t -> taskId.equals(t.id())
                && t.status() == TaskStatus.AWAITING_REVIEW
                && t.phase() != null && LOCAL_AGENT_PHASES.contains(t.phase()))) {
            return false;
        }
        return !taskStore.hasActiveTask(threadId)
                && tasks.stream().anyMatch(t -> t.status() == TaskStatus.AWAITING_REVIEW);
    }
}
