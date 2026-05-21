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

    /** Newest-non-terminal task for a thread — the "active task". */
    List<TaskEntity> findByThreadIdAndStatusInOrderBySeqDesc(String threadId, List<String> statuses);

    /** Orphan scan used by startup reconciliation: rows still marked
     *  RUNNING are stale because their subprocess is gone. */
    List<TaskEntity> findByStatusOrderByCreatedAtMsAsc(String status, Pageable pageable);

    /** Used by the automation coordinator's CI-fail subscriber. */
    List<TaskEntity> findByLinkedPrNumberIsNotNullOrderByCreatedAtMsDesc(Pageable pageable);
}
