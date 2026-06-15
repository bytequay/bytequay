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

import com.bytequay.app.domain.BranchBase;
import com.bytequay.app.domain.QueuedTask;
import com.bytequay.app.domain.QueuedTaskStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Owns the trunk's per-thread task queue: the planned future tasks the
 * trunk lines up, persisted on {@code threads.queue_json}. Mutations
 * here are the single source of truth shared by the agent tools
 * ({@code queue_task} / {@code reorder_queue} / {@code drop_queued_task})
 * and the REST endpoints, so neither path can drift from the other.
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li>{@link QueuedTaskStatus#PENDING} entries are editable / reorder-
 *       able / droppable; {@link QueuedTaskStatus#MATERIALIZED} (and
 *       terminal) entries are frozen — their plan is sealed once a Task
 *       row exists.</li>
 *   <li>Positions are 1-indexed and contiguous-ish; reorder is a
 *       permutation of the PENDING positions only, leaving materialized
 *       entries pinned to their slot.</li>
 * </ul>
 *
 * <p>Materialisation of the queue head into a real {@link
 * com.bytequay.app.domain.Task} lives in
 * {@link TaskQueueMaterialiser} (it needs worktree creation); this class
 * stays a pure queue-state mutator.
 */
@Service
public class TaskQueueService
{
    /** Cap on a queued task's title, matching the tool's contract. */
    public static final int MAX_TITLE_CHARS = 200;

    private final ThreadStore threadStore;
    private final TaskStore taskStore;

    public TaskQueueService(ThreadStore threadStore, TaskStore taskStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    /**
     * Append to (or replace) a QUEUED task's opening-prompt accumulator —
     * the text the agent reads as its first-turn input when the slot
     * opens. Editable only while the task is in {@link TaskPhase#QUEUED};
     * a write to a task in any other phase is rejected (422), since the
     * plan seals once the task starts.
     */
    @Transactional
    public Task updateOpeningPrompt(String taskId, String text, boolean append)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.phase() != TaskPhase.QUEUED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "opening prompt is editable only while the task is QUEUED (phase="
                            + task.phase() + ")");
        }
        String incoming = text == null ? "" : text;
        String existing = task.openingPrompt();
        String next = append && existing != null && !existing.isBlank()
                ? existing + "\n" + incoming
                : incoming;
        taskStore.setOpeningPrompt(taskId, next);
        return taskStore.findTaskById(taskId).orElse(task);
    }

    /** Append a PENDING entry at {@code max(position) + 1}. */
    @Transactional
    public QueuedTask append(String threadId, String title, BranchBase branchBase, String initialPrompt)
    {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("title is required");
        }
        if (trimmed.length() > MAX_TITLE_CHARS) {
            throw new IllegalArgumentException("title exceeds " + MAX_TITLE_CHARS + " chars");
        }
        Thread thread = requireThread(threadId);
        List<QueuedTask> queue = new ArrayList<>(thread.queue());
        int nextPosition = queue.stream().mapToInt(QueuedTask::position).max().orElse(0) + 1;
        QueuedTask entry = QueuedTask.pending(
                nextPosition, trimmed,
                branchBase == null ? BranchBase.MAIN : branchBase,
                blankToNull(initialPrompt), Instant.now());
        queue.add(entry);
        threadStore.updateThreadQueue(threadId, queue);
        return entry;
    }

    /**
     * Reorder the PENDING entries. {@code desiredOrder} is a permutation
     * of the current PENDING positions; the entry currently at
     * {@code desiredOrder[i]} takes the i-th PENDING slot (slots = the
     * sorted set of positions PENDING entries currently occupy).
     * MATERIALIZED / terminal entries keep their position.
     */
    @Transactional
    public List<QueuedTask> reorder(String threadId, List<Integer> desiredOrder)
    {
        Thread thread = requireThread(threadId);
        List<QueuedTask> queue = thread.queue();

        List<QueuedTask> pending = queue.stream()
                .filter(q -> q.status() == QueuedTaskStatus.PENDING)
                .sorted(Comparator.comparingInt(QueuedTask::position))
                .toList();
        List<Integer> pendingSlots = pending.stream().map(QueuedTask::position).sorted().toList();
        Set<Integer> pendingPositions = new HashSet<>(pendingSlots);

        if (desiredOrder == null
                || desiredOrder.size() != pendingPositions.size()
                || new HashSet<>(desiredOrder).size() != desiredOrder.size()
                || !new HashSet<>(desiredOrder).equals(pendingPositions)) {
            throw new IllegalArgumentException(
                    "desired order must be a permutation of the current PENDING positions "
                            + pendingSlots + " (no inserts, drops, or materialized entries)");
        }

        Map<Integer, QueuedTask> byPosition = new LinkedHashMap<>();
        for (QueuedTask q : pending) {
            byPosition.put(q.position(), q);
        }
        // Rebuild the full queue: materialized entries keep their slot;
        // pending entries are reassigned to the pending slots in the new
        // order. Emit in position order for a stable persisted shape.
        Map<Integer, QueuedTask> reslotted = new LinkedHashMap<>();
        for (QueuedTask q : queue) {
            if (q.status() != QueuedTaskStatus.PENDING) {
                reslotted.put(q.position(), q);
            }
        }
        for (int i = 0; i < desiredOrder.size(); i++) {
            QueuedTask moved = byPosition.get(desiredOrder.get(i));
            int slot = pendingSlots.get(i);
            reslotted.put(slot, moved.withPosition(slot));
        }
        List<QueuedTask> out = reslotted.values().stream()
                .sorted(Comparator.comparingInt(QueuedTask::position))
                .toList();
        threadStore.updateThreadQueue(threadId, out);
        return out;
    }

    /** Flip a PENDING entry to DROPPED. MATERIALIZED entries reject —
     *  cancel the materialised Task instead. */
    @Transactional
    public QueuedTask drop(String threadId, int position)
    {
        Thread thread = requireThread(threadId);
        List<QueuedTask> queue = new ArrayList<>(thread.queue());
        QueuedTask target = null;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).position() == position) {
                target = queue.get(i);
                if (target.status() != QueuedTaskStatus.PENDING) {
                    throw new IllegalArgumentException(
                            "position " + position + " is " + target.status()
                                    + " — only PENDING entries can be dropped; cancel the "
                                    + "materialized task instead");
                }
                QueuedTask dropped = target.withStatus(QueuedTaskStatus.DROPPED, null);
                queue.set(i, dropped);
                threadStore.updateThreadQueue(threadId, queue);
                return dropped;
            }
        }
        throw new IllegalArgumentException("no queue entry at position " + position);
    }

    /** Flip a PENDING entry to MATERIALIZED, recording the task id its
     *  plan was sealed into. Called by the materialiser once the Task row
     *  exists. No-op when no entry sits at {@code position}. */
    @Transactional
    public void markMaterialized(String threadId, int position, String taskId)
    {
        Thread thread = requireThread(threadId);
        List<QueuedTask> queue = new ArrayList<>(thread.queue());
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).position() == position) {
                queue.set(i, queue.get(i).withStatus(QueuedTaskStatus.MATERIALIZED, taskId));
                threadStore.updateThreadQueue(threadId, queue);
                return;
            }
        }
    }

    /** Flip a MATERIALIZED entry to COMPLETED once its task finishes.
     *  No-op when no entry sits at {@code position}. */
    @Transactional
    public void markCompleted(String threadId, int position)
    {
        Thread thread = requireThread(threadId);
        List<QueuedTask> queue = new ArrayList<>(thread.queue());
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).position() == position) {
                queue.set(i, queue.get(i).withStatus(
                        QueuedTaskStatus.COMPLETED, queue.get(i).materializedTaskId()));
                threadStore.updateThreadQueue(threadId, queue);
                return;
            }
        }
    }

    /** Lowest-position PENDING entry, or empty when the queue has run
     *  dry. The materialiser and the advance hook both read this. */
    public Optional<QueuedTask> pendingHead(Thread thread)
    {
        return thread.queue().stream()
                .filter(q -> q.status() == QueuedTaskStatus.PENDING)
                .min(Comparator.comparingInt(QueuedTask::position));
    }

    private Thread requireThread(String threadId)
    {
        return threadStore.findThreadById(threadId)
                .orElseThrow(() -> new IllegalArgumentException("thread not found: " + threadId));
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s;
    }
}
