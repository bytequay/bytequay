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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface TaskCheckpointJpaRepository
        extends JpaRepository<TaskCheckpointEntity, String>
{
    /** Every active checkpoint for a task (Overall + per-segment),
     *  Overall first, then segments newest-first by seq. "Active"
     *  means {@code superseded_at_ms IS NULL} — supplanted Overall
     *  rows stay in the table as history but never show in the rail
     *  or the cross-task picker. */
    @Query("""
            SELECT c FROM TaskCheckpointEntity c
            WHERE c.taskId = :taskId
              AND c.supersededAtMs IS NULL
            ORDER BY c.isOverall DESC, c.seq DESC
            """)
    List<TaskCheckpointEntity> findActiveForTask(@Param("taskId") String taskId);

    /** The most recent per-segment checkpoint. The scheduler uses
     *  it to compute "first_msg_seq = lastSegment.lastMsgSeq + 1"
     *  when deciding whether to generate a new segment. */
    @Query("""
            SELECT c FROM TaskCheckpointEntity c
            WHERE c.taskId = :taskId
              AND c.isOverall = 0
            ORDER BY c.seq DESC
            """)
    List<TaskCheckpointEntity> findLastSegment(@Param("taskId") String taskId, Pageable page);

    /** Currently-active Overall row, if any. Two queries with
     *  {@code findActiveForTask} would be O(N+1) where N is the
     *  segment count; this is a single-row lookup the scheduler
     *  refreshes against. */
    @Query("""
            SELECT c FROM TaskCheckpointEntity c
            WHERE c.taskId = :taskId
              AND c.isOverall = 1
              AND c.supersededAtMs IS NULL
            """)
    Optional<TaskCheckpointEntity> findActiveOverall(@Param("taskId") String taskId);

    /** Highest seq currently assigned for the task's per-segment
     *  rows. {@code null} when no segment exists yet. The store
     *  uses this to allocate the next sequential id when inserting
     *  a new per-segment checkpoint. */
    @Query("""
            SELECT MAX(c.seq) FROM TaskCheckpointEntity c
            WHERE c.taskId = :taskId
              AND c.isOverall = 0
            """)
    Long maxSegmentSeq(@Param("taskId") String taskId);
}
