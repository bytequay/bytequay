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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * One planned future task on a {@link Thread}'s trunk-owned queue. The
 * queue is a positional list the trunk lines up; its head materialises
 * into a real {@link Task} (in the {@link TaskPhase#QUEUED} phase) when
 * the active task on the thread completes.
 *
 * <p>While {@link QueuedTaskStatus#PENDING} the entry is editable
 * (title / branch base / opening prompt) and the trunk can reorder or
 * drop it. Once it flips to {@link QueuedTaskStatus#MATERIALIZED} the
 * plan is frozen — the only way to abandon it is to cancel the
 * materialised Task ({@link #materializedTaskId}).
 *
 * @param position 1-indexed; order is the run order.
 * @param materializedTaskId the {@code tasks.id} (TEXT) this entry
 *                           materialised into, or null while PENDING /
 *                           DROPPED.
 */
public record QueuedTask(
        int position,
        String title,
        BranchBase branchBase,
        String initialPrompt,
        QueuedTaskStatus status,
        String materializedTaskId,
        Instant createdAt)
{
    /** PENDING entry freshly planned by the trunk — no materialised
     *  task yet. */
    public static QueuedTask pending(int position, String title, BranchBase branchBase,
            String initialPrompt, Instant createdAt)
    {
        return new QueuedTask(position, title,
                branchBase == null ? BranchBase.MAIN : branchBase,
                initialPrompt, QueuedTaskStatus.PENDING, null, createdAt);
    }

    /** Copy with a new position — used by reorder. */
    public QueuedTask withPosition(int newPosition)
    {
        return new QueuedTask(newPosition, title, branchBase, initialPrompt,
                status, materializedTaskId, createdAt);
    }

    /** Copy flipped to a new status (and optional materialised task id). */
    public QueuedTask withStatus(QueuedTaskStatus newStatus, String taskId)
    {
        return new QueuedTask(position, title, branchBase, initialPrompt,
                newStatus, taskId, createdAt);
    }
}
