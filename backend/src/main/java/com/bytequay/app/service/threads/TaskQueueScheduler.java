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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Drains the trunk's task queue: when a task on a thread finishes, the
 * next PENDING queue entry is materialised and started, as long as the
 * thread's compute slot is free.
 *
 * <p>Slot model — a task <em>occupies</em> the thread's single v1 slot
 * only while the agent loop is actively running it: phases
 * {@link TaskPhase#IMPLEMENTING}, {@link TaskPhase#VALIDATING},
 * {@link TaskPhase#INTERNAL_REVIEW}, {@link TaskPhase#CI_FIXING},
 * {@link TaskPhase#ADDRESSING_COMMENTS}, {@link TaskPhase#AGENT_RE_REVIEW}.
 * QUEUED, the AWAITING_* holds, PUSHED_AWAITING_CI and
 * AWAITING_REMOTE_REVIEW do not occupy a slot (nothing is running). The
 * cap is {@code thread.parallelSlots()} — invariantly 1 in v1; the
 * count is structured so v2 only flips that number.
 *
 * <p>Runs AFTER_COMMIT of the completing transition so the COMPLETED
 * state is durable before the next entry is cut in its own transaction.
 *
 * <p><strong>v1 branch_base handling.</strong> {@code main} entries
 * auto-materialise and auto-start. {@code stacked-on-previous} entries
 * also materialise + start in v1 but raise an awaiting-review notice —
 * true branch stacking on the prior slice (and the parked
 * start-confirmation it implies) needs a base-ref-aware worktree cut and
 * is a follow-up; the worktree layer cuts from the repo default branch
 * today.
 */
@Component
public class TaskQueueScheduler
{
    private static final Logger log = LoggerFactory.getLogger(TaskQueueScheduler.class);

    /** Phases in which the agent loop is actively running the task and so
     *  holds the thread's compute slot. */
    private static final Set<TaskPhase> SLOT_OCCUPYING = EnumSet.of(
            TaskPhase.IMPLEMENTING,
            TaskPhase.VALIDATING,
            TaskPhase.INTERNAL_REVIEW,
            TaskPhase.CI_FIXING,
            TaskPhase.ADDRESSING_COMMENTS,
            TaskPhase.AGENT_RE_REVIEW);

    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final TaskQueueService queue;
    private final TaskQueueMaterialiser materialiser;
    private final TaskPhaseMachine phaseMachine;
    private final AgentScheduler scheduler;
    private final NotificationService notifications;

    public TaskQueueScheduler(
            TaskStore taskStore,
            ThreadStore threadStore,
            TaskQueueService queue,
            TaskQueueMaterialiser materialiser,
            TaskPhaseMachine phaseMachine,
            AgentScheduler scheduler,
            NotificationService notifications)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.queue = requireNonNull(queue, "queue is null");
        this.materialiser = requireNonNull(materialiser, "materialiser is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
    }

    /** A task reaching COMPLETED frees its slot — advance the queue. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPhaseTransitioned(TaskPhaseTransitionedEvent event)
    {
        if (event.to() != TaskPhase.COMPLETED) {
            return;
        }
        taskStore.findTaskById(event.taskId()).ifPresent(this::advance);
    }

    /**
     * Visible for the materialise path: try to start the next queued
     * entry on {@code completed}'s thread. Marks {@code completed}'s queue
     * entry COMPLETED, then — if a slot is free — materialises and starts
     * the next PENDING head.
     */
    void advance(Task completed)
    {
        String threadId = completed.threadId();
        // Flip the completed task's queue entry (if it came from the queue)
        // to COMPLETED so it drops out of advancement.
        threadStore.findThreadById(threadId).ifPresent(thread ->
                thread.queue().stream()
                        .filter(q -> completed.id().equals(q.materializedTaskId()))
                        .findFirst()
                        .ifPresent(q -> queue.markCompleted(threadId, q.position())));
        startNextIfIdle(threadId, completed.workingDir());
    }

    /**
     * Start the queue's head when a slot is free — the single
     * dequeue-and-run rule, called both when a task completes (a slot
     * frees) and right after a task is queued or created onto an idle
     * thread (the slot is already free). Materialises the head into a
     * QUEUED task, promotes it to IMPLEMENTING, and feeds its opening
     * prompt as the first turn. No-op when a slot is busy, the queue is
     * dry, or no working dir is resolvable.
     *
     * @param workingDirHint the repo clone to cut from when known (the
     *     completed task's dir, or the create_task caller's repo); falls
     *     back to the thread's latest task's working dir.
     */
    public Optional<Task> startNextIfIdle(String threadId, String workingDirHint)
    {
        Thread thread = threadStore.findThreadById(threadId).orElse(null);
        if (thread == null) {
            return Optional.empty();
        }
        if (occupiedSlots(threadId) >= Math.max(1, thread.parallelSlots())) {
            return Optional.empty(); // a task is still running; it'll advance on completion.
        }
        Optional<QueuedTask> headOpt = queue.pendingHead(thread);
        if (headOpt.isEmpty()) {
            return Optional.empty(); // queue ran dry — the trunk can plan more.
        }
        QueuedTask head = headOpt.get();
        String workingDir = workingDirHint != null && !workingDirHint.isBlank()
                ? workingDirHint
                : taskStore.findLatestTaskForThread(threadId)
                        .map(Task::workingDir).orElse(null);
        if (workingDir == null || workingDir.isBlank()) {
            log.warn("Cannot start queued task on thread {}: no working dir resolvable", threadId);
            return Optional.empty();
        }
        Task next = materialiser.materialiseHead(thread, head, workingDir);
        if (head.branchBase() == BranchBase.STACKED_ON_PREVIOUS) {
            notifyStackedCutOffMain(threadId, next.id(), head.title());
        }
        promote(next);
        return Optional.of(next);
    }

    private void promote(Task task)
    {
        phaseMachine.transition(task.id(), TaskPhase.IMPLEMENTING, "slot_opened", Actor.SCHEDULER);
        Task fresh = taskStore.findTaskById(task.id()).orElse(task);
        String openingPrompt = fresh.openingPrompt();
        if (openingPrompt != null && !openingPrompt.isBlank()) {
            threadStore.findThreadById(task.threadId())
                    .ifPresent(thread -> scheduler.enqueueTurn(thread, openingPrompt));
        }
    }

    private int occupiedSlots(String threadId)
    {
        return (int) taskStore.listTasksByThread(threadId).stream()
                .filter(t -> t.phase() != null && SLOT_OCCUPYING.contains(t.phase()))
                .count();
    }

    private void notifyStackedCutOffMain(String threadId, String taskId, String title)
    {
        String payload = "{\"reason\":\"queued as stacked-on-previous but cut off main "
                + "(branch stacking not yet supported)\",\"title\":\"" + escape(title) + "\"}";
        try {
            notifications.notifyAwaitingReview(threadId, taskId, payload);
        }
        catch (RuntimeException e) {
            log.warn("stacked-cut notice for task {} failed: {}", taskId, e.getMessage());
        }
    }

    private static String escape(String s)
    {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
