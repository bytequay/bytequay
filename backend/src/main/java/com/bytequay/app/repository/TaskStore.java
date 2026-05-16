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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.domain.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the Tasks surface. Keeps the JPA entities
 * package-private inside {@code repository.sqlite}; the service layer
 * talks only to this interface.
 *
 * <p>Operates on plain domain records — callers don't see entities
 * and aren't bound to JPA's lifecycle semantics. Same shape as
 * {@code EmailMutedSenderStore} / etc.
 */
public interface TaskStore
{
    // ── tasks ────────────────────────────────────────────────────────

    /** Insert or update a task by primary key. The whole row is
     *  rewritten — callers should pass the full updated state. */
    void saveTask(Task task);

    /** Single-row lookup by id. Empty when no such task exists. */
    Optional<Task> findTaskById(String id);

    /** Permanent removal — drops the task row plus its child messages
     *  and per-file rollups. Callers must already have stopped any
     *  live session attached to the task; this method touches only
     *  durable state. No-op when the id doesn't exist. */
    void deleteTask(String id);

    /**
     * Tasks in the given status, newest-{@code updated_at_ms} first.
     * Drives the left-rail status sections; {@code limit} caps page
     * size (the UI shows a fixed-size grid and lazy-loads the rest
     * later).
     */
    List<Task> listTasksByStatus(TaskStatus status, int limit);

    /**
     * Tasks pinned to the given group, newest-{@code updated_at_ms}
     * first. Drives the group detail view.
     */
    List<Task> listTasksByGroup(String groupId, int limit);

    /** Clears the {@code group_id} on every task currently pointing
     *  at {@code groupId}. Called when a group is deleted so the
     *  tasks survive — they just become ungrouped. */
    void unsetGroupOnTasks(String groupId);

    // ── messages ─────────────────────────────────────────────────────

    /**
     * Append one message to a task's conversation log. The pair
     * {@code (taskId, seq)} must be unique — duplicates indicate a
     * StreamProcessor bug and surface as a JPA constraint violation.
     */
    void appendMessage(TaskMessage message);

    /** All messages for a task, oldest-first by seq. */
    List<TaskMessage> listMessages(String taskId);

    // ── files ────────────────────────────────────────────────────────

    /**
     * Insert or update the per-(task, path) rollup. Callers pass the
     * cumulative state (count, lines added/removed, last operation /
     * timestamp); the store overwrites the row.
     */
    void recordFile(TaskFile file);

    /** All files touched by a task, most-recently-touched first. */
    List<TaskFile> listFiles(String taskId);
}
