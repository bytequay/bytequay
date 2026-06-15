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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;

import java.time.Instant;
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

    /** Find the task whose dev branch matches {@code branchName} — the
     *  PR sync uses this to auto-link a PR (incl. manually-opened ones)
     *  to its task by head ref. Empty default for test stores; the
     *  SQLite store overrides with an indexed query. */
    default Optional<Task> findTaskByBranch(String branchName)
    {
        return Optional.empty();
    }

    /** Whether the task's "accept edits in worktree" toggle is on.
     *  False for an unknown id. Read by {@code WorktreeEditStep} to
     *  decide whether to auto-approve in-worktree file edits. Default
     *  is the safe "off" so in-memory test stores need not implement it;
     *  the SQLite store overrides with the persisted column. */
    default boolean isAcceptEdits(String taskId)
    {
        return false;
    }

    /** Flip the task's "accept edits in worktree" toggle. Persists so
     *  the choice survives a restart, unlike a session tool-budget.
     *  No-op default for test stores; the SQLite store overrides. */
    default void setAcceptEdits(String taskId, boolean enabled)
    {
    }

    /** Record that the task's branch reached the remote at {@code
     *  pushedAt}. Set on a {@code push} approval and on the implicit push
     *  an {@code open_pr} approval performs. Persisted on its own column
     *  (not via {@link #saveTask}) so a later full-row save can't clobber
     *  it — mirrors {@link #setAcceptEdits}. No-op default for test
     *  stores; the SQLite store overrides. */
    default void markPushed(String taskId, Instant pushedAt)
    {
    }

    /** Attach the opened PR's number + state to the task so the UI can
     *  show it and deep-link into the in-app PR page. Persisted directly
     *  on the entity (like {@link #setAcceptEdits}). No-op default for
     *  test stores; the SQLite store overrides. */
    default void linkPullRequest(String taskId, int prNumber, String prState)
    {
    }

    /** Tasks whose {@code linkedPrNumber} equals {@code prNumber}. Used
     *  to advance a shipped task to COMPLETED when its PR merges; the
     *  caller still narrows by repo (PR numbers aren't globally unique).
     *  Empty default for test stores; the SQLite store overrides. */
    default List<Task> findByLinkedPrNumber(int prNumber)
    {
        return List.of();
    }

    /** Seal a task as COMPLETED with its end timestamp — used when the
     *  task's PR merges. Entity-level update (like {@link
     *  #setAcceptEdits}) so it can't clobber unrelated columns. No-op
     *  default for test stores; the SQLite store overrides. */
    default void completeTask(String taskId, Instant endedAt)
    {
    }

    // ── dev-lifecycle phase (V106) ─────────────────────────────────────

    /** Write the task's dev-lifecycle {@code phase} column. Load-set-save
     *  (like {@link #setAcceptEdits}) so {@code saveTask} — which never
     *  maps phase — can't clobber it. No-op default for test stores. */
    default void updatePhase(String taskId, TaskPhase phase)
    {
    }

    /** Append one phase-transition audit row. No-op default for test
     *  stores; the SQLite store inserts into {@code task_phase_event}. */
    default void appendPhaseEvent(
            String taskId, TaskPhase from, TaskPhase to, Instant at, String reason, Actor actor)
    {
    }

    /** A task's phase transitions, oldest-first. Empty default for test
     *  stores. */
    default List<TaskPhaseEvent> listPhaseEvents(String taskId)
    {
        return List.of();
    }

    /** Current consecutive-auto-push counter; 0 for an unknown task. */
    default int consecutiveAutoPushes(String taskId)
    {
        return 0;
    }

    /** The single active (non-COMPLETED) task linked to {@code prRef}
     *  ({@code owner/repo#n}), enforcing the task ↔ PR 1:1-active rule.
     *  Empty default for test stores; the SQLite store overrides. */
    default Optional<Task> findActiveTaskByPrRef(String prRef)
    {
        return Optional.empty();
    }

    /** Permanently link a task to a PR ref. Entity-level update (like
     *  {@link #setAcceptEdits}); the link is set once and lives for the
     *  task. No-op default for test stores. */
    default void linkTaskToPr(String taskId, String prRef)
    {
    }

    /** Every task ever linked to {@code prRef} (the active one plus the
     *  completed/cancelled audit log), oldest first. Empty default for
     *  test stores. */
    default List<Task> findTasksByPrRef(String prRef)
    {
        return List.of();
    }

    /** Set the consecutive-auto-push counter (incremented on an auto
     *  push, reset to 0 on a human push). No-op default for test stores. */
    default void setConsecutiveAutoPushes(String taskId, int value)
    {
    }

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

    /** Latest task on a thread by seq, regardless of status. Used by
     *  the resume-from-terminal path: a COMPLETED thread's most-recent
     *  task is also terminal, so {@link #findActiveTaskForThread}
     *  returns empty, but we still need the task to recover the
     *  worktree + branch when the user picks the conversation back up.
     *  Empty for a 0-Task thread. */
    Optional<Task> findLatestTaskForThread(String threadId);

    /** Highest seq currently assigned in the thread. Used to compute
     *  the next seq on "ship & continue". Empty when no tasks exist. */
    Optional<Long> maxSeqForThread(String threadId);

    /** Orphan scan used by startup reconciliation: rows still marked
     *  RUNNING at startup are stale (their subprocess is gone), so
     *  the reconciler flips them to IDLE. */
    List<Task> listByStatus(TaskStatus status, int limit);

    /** All tasks that carry a {@code linked_pr_number}, newest-first.
     *  Used by the automation coordinator's CI-fail subscriber to find
     *  candidates to check against {@code PrDetailStore}'s check-run
     *  cache. */
    List<Task> listWithLinkedPr(int limit);

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
