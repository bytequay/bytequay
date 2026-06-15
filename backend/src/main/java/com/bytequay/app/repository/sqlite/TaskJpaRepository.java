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

    /** All tasks linked to a PR number (across repos — the caller
     *  narrows by repo). Drives completion when that PR merges. */
    List<TaskEntity> findByLinkedPrNumber(Integer linkedPrNumber);

    /** The single active (non-COMPLETED) task linked to a PR ref, if any
     *  — the partial unique index guarantees at most one. Enforces the
     *  task ↔ PR 1:1-active rule. */
    Optional<TaskEntity> findFirstByLinkedPrRefAndPhaseNot(String linkedPrRef, String phase);
}
