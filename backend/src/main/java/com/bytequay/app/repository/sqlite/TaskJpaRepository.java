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
package com.bytequay.app.repository.sqlite;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface TaskJpaRepository
        extends JpaRepository<TaskEntity, String>
{
    /** All tasks in a thread, ordered by seq ascending — the sequence
     *  of work units the conversation has rolled through. */
    List<TaskEntity> findByThreadIdOrderBySeqAsc(String threadId);

    /** Highest-seq task for a thread; used to compute the next seq
     *  number on "ship & continue". */
    Optional<TaskEntity> findFirstByThreadIdOrderBySeqDesc(String threadId);

    /** Newest task whose dev branch matches a synced PR's head ref —
     *  used to auto-link a PR to its originating task. Branch names are
     *  per-task unique, so at most one matches in practice. */
    Optional<TaskEntity> findFirstByBranchNameOrderBySeqDesc(String branchName);

    /** Newest-non-terminal task for a thread — the "active task". */
    List<TaskEntity> findByThreadIdAndStatusInOrderBySeqDesc(String threadId, List<String> statuses);

    /** Orphan scan used by startup reconciliation: rows still marked
     *  RUNNING are stale because their subprocess is gone. */
    List<TaskEntity> findByStatusOrderByCreatedAtMsAsc(String status, Pageable pageable);

    /** Used by the automation coordinator's CI-fail subscriber. */
    List<TaskEntity> findByLinkedPrNumberIsNotNullOrderByCreatedAtMsDesc(Pageable pageable);

    /** Tasks currently sitting in one of the given phases, newest-first.
     *  The lifecycle driver narrows to the post-push "remote spine" so its
     *  scan cap bounds only in-flight tasks, never the full linked-PR set. */
    List<TaskEntity> findByPhaseInOrderByCreatedAtMsDesc(List<String> phases, Pageable pageable);

    /** Workspace automations reconcile every matching task, oldest first,
     *  rather than sharing the global lifecycle driver's capped window. */
    List<TaskEntity> findByPhaseAndOriginOrderByCreatedAtMsAsc(String phase, String origin);

    /** All tasks linked to a PR number (across repos — the caller
     *  narrows by repo). Drives completion when that PR merges. */
    List<TaskEntity> findByLinkedPrNumber(Integer linkedPrNumber);

    /** The single active (non-COMPLETED) task linked to a PR ref, if any
     *  — the partial unique index guarantees at most one. Enforces the
     *  task ↔ PR 1:1-active rule. */
    Optional<TaskEntity> findFirstByLinkedPrRefAndPhaseNot(String linkedPrRef, String phase);

    /** Every task ever linked to a PR ref (active + completed audit log),
     *  oldest first — backs the PR's linked-task surface. */
    List<TaskEntity> findByLinkedPrRefOrderBySeqAsc(String linkedPrRef);

    // ── ready-to-merge notify sentinel (V116): atomic CAS dedup ─────────

    /** Stamp the sentinel iff unset. Returns 1 when this caller is the
     *  first to detect the ready state (so it fires the notification). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.mergeNotificationSentAtMs = :atMs "
            + "WHERE t.id = :taskId AND t.mergeNotificationSentAtMs IS NULL")
    int setMergeNotificationSentAtIfNull(@Param("taskId") String taskId, @Param("atMs") long atMs);

    /** Clear the sentinel when a ready condition breaks. Returns rows
     *  affected. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.mergeNotificationSentAtMs = null "
            + "WHERE t.id = :taskId AND t.mergeNotificationSentAtMs IS NOT NULL")
    int clearMergeNotificationSentAt(@Param("taskId") String taskId);

    // ── mark-ready gate sentinel (V128): atomic CAS, sent once ──────────

    /** Stamp the ready-gate sentinel iff unset. Returns 1 when this caller is
     *  the first to offer the mark-ready gate (so it parks it). Never cleared
     *  — the gate is offered exactly once per task. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.readyGateSentAtMs = :atMs "
            + "WHERE t.id = :taskId AND t.readyGateSentAtMs IS NULL")
    int setReadyGateSentAtIfNull(@Param("taskId") String taskId, @Param("atMs") long atMs);

    // ── standing merge consent + auto-retry state (V129) ────────────────

    /** Record standing merge consent and reset the retry counter. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.mergeAuthorizedAtMs = :atMs, t.mergeQueueRetries = 0 "
            + "WHERE t.id = :taskId")
    int authorizeMerge(@Param("taskId") String taskId, @Param("atMs") long atMs);

    /** Drop standing merge consent and reset the retry counter. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.mergeAuthorizedAtMs = null, t.mergeQueueRetries = 0 "
            + "WHERE t.id = :taskId")
    int clearMergeAuthorization(@Param("taskId") String taskId);

    /** Set the auto re-enqueue retry counter. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.mergeQueueRetries = :retries WHERE t.id = :taskId")
    int setMergeQueueRetries(@Param("taskId") String taskId, @Param("retries") int retries);

    // ── completion-summary brain turn (V149) ────────────────────────────

    /** Record the in-flight "summarize this task" brain turn. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.pendingCompletionSummaryTurnId = :turnId WHERE t.id = :taskId")
    int setPendingCompletionSummaryTurnId(@Param("taskId") String taskId, @Param("turnId") String turnId);

    /** Clear it once the turn's finish event has been handled (or the
     *  stale-completion sweep gives up on it). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.pendingCompletionSummaryTurnId = null WHERE t.id = :taskId")
    int clearPendingCompletionSummaryTurnId(@Param("taskId") String taskId);

    /** Resolve a finished turn id back to the task it was summarizing. */
    Optional<TaskEntity> findByPendingCompletionSummaryTurnId(String pendingCompletionSummaryTurnId);
}
