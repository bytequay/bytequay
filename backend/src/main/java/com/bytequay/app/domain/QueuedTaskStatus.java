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

/**
 * Lifecycle of one {@link QueuedTask} entry on a thread's queue.
 *
 * <ul>
 *   <li>{@link #PENDING} — planned, editable, not yet materialised.
 *       The trunk can reorder, edit, or drop it.</li>
 *   <li>{@link #MATERIALIZED} — promoted to a real {@link Task} row
 *       (carries its {@code materializedTaskId}); the plan is frozen.
 *       Cancel the Task to abandon it; the entry can't be re-edited or
 *       dropped via the queue tools.</li>
 *   <li>{@link #COMPLETED} — its materialised Task finished.</li>
 *   <li>{@link #DROPPED} — removed before materialising; the row stays
 *       for audit but is excluded from queue advancement.</li>
 * </ul>
 */
public enum QueuedTaskStatus
{
    PENDING,
    MATERIALIZED,
    COMPLETED,
    DROPPED;

    /** Tolerant parse: an unknown / null value falls back to
     *  {@link #PENDING} rather than throwing on a stale row. */
    public static QueuedTaskStatus fromWire(String value)
    {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        for (QueuedTaskStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return PENDING;
    }
}
