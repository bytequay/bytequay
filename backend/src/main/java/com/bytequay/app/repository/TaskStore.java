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
import com.bytequay.app.domain.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the work-unit Task — a branch + worktree +
 * PR row that belongs to a Thread. Mirrors the shape of
 * {@link ThreadStore} but at the task level; the service layer talks
 * only to this interface.
 *
 * <p>This interface lands in its own commit ahead of the migration
 * that actually creates the {@code tasks} table; the queries below
 * won't execute until that migration ships.
 */
public interface TaskStore
{
    // ── tasks ──────────────────────────────────────────────────────────

    /** Insert or update a task by primary key. The whole row is
     *  rewritten — callers pass the full updated state. */
    void saveTask(Task task);

    /** Single-row lookup by id. */
    Optional<Task> findTaskById(String id);

    /** Permanent removal. Drops the row plus its child {@code task_files};
     *  FK cascades handle the join. Callers must already have stopped
     *  any live agent attached to the task. */
    void deleteTask(String id);

    /** All tasks for a thread, ordered by seq ascending (sequence of
     *  work units the conversation has rolled through). */
    List<Task> listTasksByThread(String threadId);

    /** Latest non-terminal task for a thread, i.e. the "active" one.
     *  Empty for a 0-Task thread (brainstorm / Q&A with no branch). */
    Optional<Task> findActiveTaskForThread(String threadId);

    /** Highest seq currently assigned in the thread. Used to compute
     *  the next seq on "ship & continue". Empty when no tasks exist. */
    Optional<Long> maxSeqForThread(String threadId);

    /** Orphan scan used by startup reconciliation: rows still marked
     *  RUNNING at startup are stale (their subprocess is gone), so
     *  the reconciler flips them to IDLE. */
    List<Task> listByStatus(TaskStatus status, int limit);

    // ── files ────────────────────────────────────────────────────────

    /**
     * Insert or update the per-(task, path) rollup. Callers pass the
     * cumulative state (count, line deltas, last operation /
     * timestamp); the store overwrites the row.
     */
    void recordFile(TaskFile file);

    /** All files touched in a task's worktree, most-recently-touched
     *  first. Drives the "Files touched" sidebar. */
    List<TaskFile> listFiles(String taskId);
}
