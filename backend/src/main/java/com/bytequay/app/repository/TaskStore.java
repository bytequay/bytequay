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
import java.util.Collection;
import java.util.Comparator;
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

    /** Record that the task's branch reached the remote at {@code
     *  pushedAt}. Set on a {@code push} approval and on the implicit push
     *  an {@code open_pr} approval performs. Persisted on its own column
     *  (not via {@link #saveTask}) so a later full-row save can't clobber
     *  it. No-op default for test stores; the SQLite store overrides. */
    default void markPushed(String taskId, Instant pushedAt)
    {
    }

    /** Attach the opened PR's number + state to the task so the UI can
     *  show it and deep-link into the in-app PR page. Persisted directly
     *  on the entity (like {@link #markPushed}). No-op default for
     *  test stores; the SQLite store overrides. */
    default void linkPullRequest(String taskId, int prNumber, String prState)
    {
    }

    /** Write the task's {@code ci_state} column from the PR's live CI
     *  status (a {@code PullRequestDetail.CiStatus} name, e.g. {@code
     *  "PASSING"}) so the live-plan rail's CI validation node reflects
     *  reality instead of staying permanently unknown. Entity-level
     *  update (like {@link #linkPullRequest}). No-op default for test
     *  stores; the SQLite store overrides. */
    default void updateCiState(String taskId, String ciState)
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
     *  #markPushed}) so it can't clobber unrelated columns. No-op
     *  default for test stores; the SQLite store overrides. */
    default void completeTask(String taskId, Instant endedAt)
    {
    }

    /** Seal a task as CANCELED with its end timestamp — used when the user
     *  closes a task. Terminal, like {@link #completeTask}. No-op default
     *  for test stores; the SQLite store overrides. */
    default void cancelTask(String taskId, Instant endedAt)
    {
    }

    /** Seal a task as REMOTE_CLOSED with its end timestamp — used when the
     *  task's PR is closed on the remote without merging. Terminal, like
     *  {@link #completeTask}. No-op default for test stores; the SQLite store
     *  overrides. */
    default void remoteCloseTask(String taskId, Instant endedAt)
    {
    }

    // ── dev-lifecycle phase (V106) ─────────────────────────────────────

    /** Write the task's dev-lifecycle {@code phase} column. Load-set-save
     *  (like {@link #markPushed}) so {@code saveTask} — which never
     *  maps phase — can't clobber it. No-op default for test stores. */
    default void updatePhase(String taskId, TaskPhase phase)
    {
    }

    /** Write the task's {@code opening_prompt} accumulator (V110).
     *  Load-set-save like {@link #updatePhase} so {@code saveTask} can't
     *  clobber it. No-op default for test stores. */
    default void setOpeningPrompt(String taskId, String openingPrompt)
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
     *  {@link #markPushed}); the link is set once and lives for the
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

    // ── task-level write mutex (V118) ──────────────────────────────────

    // ── ready-to-merge notify sentinel (V116) ──────────────────────────

    /** Atomically stamp the ready-to-merge sentinel iff unset. Returns true
     *  when this caller won the race (so it fires the notification). False
     *  default for test stores. */
    default boolean markMergeNotificationSentIfUnset(String taskId, Instant at)
    {
        return false;
    }

    /** Clear the ready-to-merge sentinel (a condition broke). No-op default. */
    default void clearMergeNotificationSent(String taskId)
    {
    }

    /** The ready-to-merge sentinel, or empty when not currently armed.
     *  Empty default for test stores. */
    default Optional<Instant> mergeNotificationSentAt(String taskId)
    {
        return Optional.empty();
    }

    /** Atomically stamp the mark-ready gate sentinel iff unset. Returns true
     *  when this caller won the race (so it parks the gate). The gate is
     *  offered once per task and never cleared. False default for test stores. */
    default boolean markReadyGateSentIfUnset(String taskId, Instant at)
    {
        return false;
    }

    // ── standing merge consent + auto-retry state (V129) ────────────────

    /** Record the user's standing consent to merge this task's PR (and reset
     *  the retry counter), so a merge-queue bounce re-enqueues automatically
     *  instead of re-prompting. No-op default for test stores. */
    default void authorizeMerge(String taskId, Instant at)
    {
    }

    /** Drop standing merge consent + reset the retry counter. No-op default. */
    default void clearMergeAuthorization(String taskId)
    {
    }

    /** Whether the task currently carries standing merge consent. False
     *  default for test stores. */
    default boolean isMergeAuthorized(String taskId)
    {
        return false;
    }

    /** The number of silent merge-queue re-enqueues so far. Zero default. */
    default int mergeQueueRetries(String taskId)
    {
        return 0;
    }

    /** Set the merge-queue auto re-enqueue retry counter. No-op default. */
    default void setMergeQueueRetries(String taskId, int retries)
    {
    }

    // ── completion-summary brain turn (V149) ────────────────────────────

    /** Record the in-flight "summarize this task for the trunk" brain turn.
     *  No-op default for test stores; the SQLite store overrides. */
    default void setPendingCompletionSummaryTurnId(String taskId, String turnId)
    {
    }

    /** Clear it once the turn's finish event has been handled, or the
     *  stale-completion sweep gives up on it. No-op default. */
    default void clearPendingCompletionSummaryTurnId(String taskId)
    {
    }

    /** The task's in-flight completion-summary turn id, if any. Empty
     *  default for test stores. */
    default Optional<String> pendingCompletionSummaryTurnId(String taskId)
    {
        return Optional.empty();
    }

    /** The task a finished turn id was summarizing, if it was a tracked
     *  completion-summary turn. Empty default for test stores. */
    default Optional<Task> findTaskByPendingCompletionSummaryTurnId(String turnId)
    {
        return Optional.empty();
    }

    /** Permanent removal. Drops the row plus its child {@code task_files};
     *  FK cascades handle the join. Callers must already have stopped
     *  any live agent attached to the task. */
    void deleteTask(String id);

    /** All tasks for a thread, ordered by seq ascending (sequence of
     *  work units the conversation has rolled through). */
    List<Task> listTasksByThread(String threadId);

    /** Non-terminal tasks for a thread (runtime status not
     *  COMPLETED/ERRORED <em>and</em> dev-lifecycle phase not COMPLETED),
     *  latest seq first. A thread may run several at once; empty for a
     *  0-Task thread (brainstorm / Q&A with no branch). */
    List<Task> activeTasksForThread(String threadId);

    /** Whether the thread has any non-terminal task. A presence check
     *  callers use to gate "this thread is already working on something" —
     *  intent-clearer than materialising the task just to test presence. */
    boolean hasActiveTask(String threadId);

    /** Whether the task is in auto-approve mode: its parked publish gates and
     *  in-turn tool prompts are approved automatically (the final PR merge is
     *  the one exception). False for an unknown id; default off. No-op-ish
     *  default for test stores; the SQLite store overrides with the column. */
    default boolean isAutoApprove(String taskId)
    {
        return false;
    }

    /** Flip the task's auto-approve mode. Persisted (load-set-save like
     *  {@link #markPushed}) so it survives a restart. No-op default for test
     *  stores; the SQLite store overrides. */
    default void setAutoApprove(String taskId, boolean enabled)
    {
    }

    /** Whether the task is in auto-merge mode: on top of auto-approve, the
     *  final PR merge itself is also approved automatically. Only settable
     *  while the task's plan reads low-risk/small-effort (enforced by the
     *  caller, not this store) — the flag is not re-validated afterward.
     *  False for an unknown id; default off. No-op-ish default for test
     *  stores; the SQLite store overrides with the column. */
    default boolean isAutoMerge(String taskId)
    {
        return false;
    }

    /** Flip the task's auto-merge mode. Persisted like {@link
     *  #setAutoApprove}. No-op default for test stores; the SQLite store
     *  overrides. */
    default void setAutoMerge(String taskId, boolean enabled)
    {
    }

    /** How many write-permission approvals a shipped PR needs before it counts
     *  as merge-ready. 0 for an unknown id; default 0. No-op-ish default for
     *  test stores; the SQLite store overrides with the column. */
    default int minApprovals(String taskId)
    {
        return 0;
    }

    /** Set the task's minimum-approvals gate (persisted). No-op default for
     *  test stores; the SQLite store overrides. */
    default void setMinApprovals(String taskId, int minApprovals)
    {
    }

    /** Latest task on a thread by seq, regardless of status. Used by
     *  the resume-from-terminal path: a COMPLETED thread's most-recent
     *  task is also terminal, so {@link #activeTasksForThread} returns
     *  empty, but we still need the task to recover the worktree +
     *  branch when the user picks the conversation back up. Empty for a
     *  0-Task thread. */
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

    /** Tasks currently in one of the given phases, newest-first. The
     *  lifecycle driver uses this to scan only the post-push "remote
     *  spine" — so the {@code limit} bounds in-flight tasks, not every
     *  task that ever linked a PR. */
    List<Task> listByPhases(Collection<TaskPhase> phases, int limit);

    /** Automation tasks in one phase/origin, oldest-first. Unlike the
     *  bounded global lifecycle sweep, this cannot starve an older
     *  workspace-owned task behind unrelated planning work. */
    default List<Task> listByPhaseAndOrigin(TaskPhase phase, String origin)
    {
        return listByPhases(List.of(phase), 10_000).stream()
                .filter(task -> origin.equals(task.origin()))
                .sorted(Comparator.comparing(Task::createdAt))
                .toList();
    }

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
