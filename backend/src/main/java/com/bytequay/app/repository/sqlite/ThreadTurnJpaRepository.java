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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface ThreadTurnJpaRepository
        extends JpaRepository<ThreadTurnEntity, String>
{
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT OR IGNORE INTO thread_turns (
                id, thread_id, task_id, lane, status, input,
                created_at_ms, updated_at_ms, started_at_ms, finished_at_ms,
                error_message, initiator_attended, initiator_source,
                stage_id, scope, agent_run_id, affects_task_liveness, kick_key)
            VALUES (
                :id, :threadId, :taskId, :lane, :status, :input,
                :createdAtMs, :updatedAtMs, :startedAtMs, :finishedAtMs,
                :errorMessage, :initiatorAttended, :initiatorSource,
                :stageId, :scope, :agentRunId, :affectsTaskLiveness, :kickKey)
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") String id,
            @Param("threadId") String threadId,
            @Param("taskId") String taskId,
            @Param("lane") String lane,
            @Param("status") String status,
            @Param("input") String input,
            @Param("createdAtMs") long createdAtMs,
            @Param("updatedAtMs") long updatedAtMs,
            @Param("startedAtMs") Long startedAtMs,
            @Param("finishedAtMs") Long finishedAtMs,
            @Param("errorMessage") String errorMessage,
            @Param("initiatorAttended") boolean initiatorAttended,
            @Param("initiatorSource") String initiatorSource,
            @Param("stageId") String stageId,
            @Param("scope") String scope,
            @Param("agentRunId") String agentRunId,
            @Param("affectsTaskLiveness") boolean affectsTaskLiveness,
            @Param("kickKey") String kickKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ThreadTurnEntity turn
            SET turn.status = :to,
                turn.updatedAtMs = :updatedAtMs,
                turn.startedAtMs = :startedAtMs,
                turn.finishedAtMs = :finishedAtMs,
                turn.errorMessage = :errorMessage
            WHERE turn.id = :id AND turn.status = :expected
            """)
    int updateStatusIf(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("to") String to,
            @Param("updatedAtMs") long updatedAtMs,
            @Param("startedAtMs") Long startedAtMs,
            @Param("finishedAtMs") Long finishedAtMs,
            @Param("errorMessage") String errorMessage);

    Optional<ThreadTurnEntity> findByKickKey(String kickKey);

    List<ThreadTurnEntity> findByTaskIdAndAffectsTaskLivenessTrueOrderByCreatedAtMsAscIdAsc(
            String taskId, Pageable pageable);

    List<ThreadTurnEntity> findByStatusOrderByCreatedAtMsAscIdAsc(String status, Pageable pageable);

    @Query("""
            SELECT turn
            FROM ThreadTurnEntity turn
            WHERE turn.status = :status
              AND (turn.createdAtMs > :createdAtMs
                OR (turn.createdAtMs = :createdAtMs AND turn.id > :id))
            ORDER BY turn.createdAtMs ASC, turn.id ASC
            """)
    List<ThreadTurnEntity> findByStatusAfterCursor(
            @Param("status") String status,
            @Param("createdAtMs") long createdAtMs,
            @Param("id") String id,
            Pageable pageable);

    List<ThreadTurnEntity> findByStatusInOrderByCreatedAtMsAscIdAsc(Collection<String> statuses, Pageable pageable);

    List<ThreadTurnEntity> findByThreadIdAndStatusOrderByCreatedAtMsDescIdDesc(
            String threadId,
            String status,
            Pageable pageable);

    List<ThreadTurnEntity> findByThreadIdOrderByCreatedAtMsDescIdDesc(String threadId, Pageable pageable);

    List<ThreadTurnEntity> findByAgentRunIdOrderByCreatedAtMsDescIdDesc(
            String agentRunId,
            Pageable pageable);

    List<ThreadTurnEntity> findByTaskIdAndStatusOrderByCreatedAtMsDescIdDesc(
            String taskId,
            String status,
            Pageable pageable);
}
