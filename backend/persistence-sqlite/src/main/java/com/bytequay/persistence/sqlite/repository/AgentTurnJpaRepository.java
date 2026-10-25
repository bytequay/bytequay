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
package com.bytequay.persistence.sqlite.repository;

import com.bytequay.persistence.sqlite.entity.AgentTurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AgentTurnJpaRepository
        extends JpaRepository<AgentTurnEntity, String>
{
    Optional<AgentTurnEntity> findByTaskIdAndIdempotencyKey(String taskId, String idempotencyKey);

    Optional<AgentTurnEntity> findByActiveTaskId(String taskId);

    List<AgentTurnEntity> findByTaskIdOrderByCreatedAtAsc(String taskId);

    @Query(value = """
            SELECT turn.* FROM agent_task task JOIN agent_turn turn ON turn.id = (
                SELECT latest.id FROM agent_turn latest WHERE latest.task_id = task.id
                ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)
            WHERE task.id IN :ids
            """, nativeQuery = true)
    List<AgentTurnEntity> findLatest(@Param("ids") Set<String> taskIds);

    @Query(value = """
            SELECT turn.* FROM agent_task task JOIN agent_turn turn ON turn.id = (
                SELECT latest.id FROM agent_turn latest WHERE latest.task_id = task.id
                    AND latest.development_result IS NOT NULL AND latest.refinement_parent_user_turn_id IS NULL
                ORDER BY latest.created_at DESC, latest.id DESC LIMIT 1)
            WHERE task.id IN :ids
            """, nativeQuery = true)
    List<AgentTurnEntity> findLatestDevelopmentResults(@Param("ids") Set<String> taskIds);

    List<AgentTurnEntity> findByActiveTaskIdIsNotNullOrderByCreatedAtAsc();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AgentTurnEntity turn WHERE turn.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") String taskId);
}
