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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.service.threads.TaskQueueScheduler;
import com.bytequay.app.service.threads.TaskQueueService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Trunk-only agent tools that mutate a thread's planned-task queue. The
 * trunk plans the queue; task / reviewer roles can't reach these
 * ({@code roles = TRUNK}). All three are {@link Gating#AUTO} — they only
 * touch {@code threads.queue_json}, never GitHub or shared remote state,
 * so there's nothing to gate.
 *
 * <p>Delegates every mutation to {@link TaskQueueService} so the agent
 * path and the REST path stay byte-for-byte consistent.
 */
@Component
public class TaskQueueToolHandlers
{
    private final TaskQueueService queue;
    private final TaskQueueScheduler scheduler;
    private final ObjectMapper mapper;

    public TaskQueueToolHandlers(
            TaskQueueService queue, TaskQueueScheduler scheduler, ObjectMapper mapper)
    {
        this.queue = requireNonNull(queue, "queue is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Args for {@code queue_task}. */
    public record QueueTaskArgs(
            @ToolParam(description = "Title for the planned task. Required, <= 200 chars.",
                    required = true) String title,
            @ToolParam(description = "Branch base — 'main' (default, off the per-repo merge "
                    + "target, resolved at materialisation time) or 'stacked-on-previous' "
                    + "(chain on the prior task's branch).",
                    wireName = "branch_base") String branchBase,
            @ToolParam(description = "Optional opening prompt seeded into the task when it "
                    + "materialises — the agent's first-turn input.",
                    wireName = "initial_prompt") String initialPrompt) {}

    @AgentTool(
            name = "queue_task",
            description = "Append a planned task to the trunk's queue. Trunk-only; task / "
                    + "reviewer roles can't reach this. The entry stays PENDING until the "
                    + "queue head materialises (the active task on this thread reaches "
                    + "COMPLETED). branch_base is 'main' (default) or 'stacked-on-previous'. "
                    + "Returns the entry's position.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome queueTask(QueueTaskArgs args, ToolCall call)
    {
        try {
            QueuedTask entry = queue.append(
                    call.threadId(), args.title(),
                    BranchBase.fromWire(args.branchBase()), args.initialPrompt());
            // Serial-execution rule: if the thread's slot is free right now,
            // start this entry immediately instead of waiting for a running
            // task to complete (which, on an idle thread, never happens).
            scheduler.startNextIfIdle(call.threadId(), null);
            return ok(new QueueTaskResult(
                    entry.position(), entry.title(), entry.branchBase().wire(), entry.status().name()));
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error("queue_task failed: " + e.getMessage());
        }
    }

    /** Args for {@code reorder_queue}. */
    public record ReorderQueueArgs(
            @ToolParam(description = "Comma-separated 1-indexed permutation of the current "
                    + "PENDING positions, in the desired run order, e.g. '3,1,2'. Must cover "
                    + "exactly the PENDING positions — no inserts, drops, or materialized "
                    + "entries (use queue_task / drop_queued_task for those).",
                    required = true) String order) {}

    @AgentTool(
            name = "reorder_queue",
            description = "Reorder the PENDING entries in the trunk's queue. Pass 'order' as "
                    + "a comma-separated permutation of the current PENDING positions. "
                    + "MATERIALIZED entries keep their slot and cannot be reordered. "
                    + "Trunk-only.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome reorderQueue(ReorderQueueArgs args, ToolCall call)
    {
        List<Integer> order;
        try {
            order = parsePositions(args.order());
        }
        catch (NumberFormatException e) {
            return ToolOutcome.Completed.error(
                    "reorder_queue failed: 'order' must be comma-separated integers, got: "
                            + args.order());
        }
        try {
            List<QueuedTask> result = queue.reorder(call.threadId(), order);
            List<Integer> resultingOrder = new ArrayList<>();
            for (QueuedTask q : result) {
                resultingOrder.add(q.position());
            }
            return ok(new ReorderResult(resultingOrder));
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error("reorder_queue failed: " + e.getMessage());
        }
    }

    /** Args for {@code drop_queued_task}. */
    public record DropQueuedTaskArgs(
            @ToolParam(description = "1-indexed position of the PENDING entry to drop.",
                    required = true) Integer position) {}

    @AgentTool(
            name = "drop_queued_task",
            description = "Remove a PENDING entry from the queue. Status flips to DROPPED; "
                    + "the row stays for audit but is excluded from queue advancement. "
                    + "MATERIALIZED entries cannot be dropped here — cancel the materialized "
                    + "task instead. Trunk-only.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TRUNK)
    public ToolOutcome dropQueuedTask(DropQueuedTaskArgs args, ToolCall call)
    {
        if (args.position() == null) {
            return ToolOutcome.Completed.error("drop_queued_task failed: position is required");
        }
        try {
            QueuedTask dropped = queue.drop(call.threadId(), args.position());
            return ok(new DropResult(dropped.position(), dropped.status().name()));
        }
        catch (IllegalArgumentException e) {
            return ToolOutcome.Completed.error("drop_queued_task failed: " + e.getMessage());
        }
    }

    private static List<Integer> parsePositions(String raw)
    {
        List<Integer> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String token = part.strip();
            if (!token.isEmpty()) {
                out.add(Integer.parseInt(token));
            }
        }
        return out;
    }

    private ToolOutcome ok(Object payload)
    {
        try {
            return ToolOutcome.Completed.ok(mapper.writeValueAsString(payload));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise tool payload: " + payload, e);
        }
    }

    /** Wire shape for {@code queue_task}. */
    public record QueueTaskResult(int position, String title, String branchBase, String status) {}

    /** Wire shape for {@code reorder_queue} — the resulting positions in order. */
    public record ReorderResult(List<Integer> order) {}

    /** Wire shape for {@code drop_queued_task}. */
    public record DropResult(int position, String status) {}
}
