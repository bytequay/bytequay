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

import java.util.Collection;
import java.util.List;

interface TaskTurnJpaRepository
        extends JpaRepository<TaskTurnEntity, String>
{
    List<TaskTurnEntity> findByStatusOrderByCreatedAtMsAscIdAsc(String status, Pageable pageable);

    @Query("""
            SELECT turn
            FROM TaskTurnEntity turn
            WHERE turn.status = :status
              AND (turn.createdAtMs > :createdAtMs
                OR (turn.createdAtMs = :createdAtMs AND turn.id > :id))
            ORDER BY turn.createdAtMs ASC, turn.id ASC
            """)
    List<TaskTurnEntity> findByStatusAfterCursor(
            @Param("status") String status,
            @Param("createdAtMs") long createdAtMs,
            @Param("id") String id,
            Pageable pageable);

    List<TaskTurnEntity> findByStatusInOrderByCreatedAtMsAscIdAsc(Collection<String> statuses, Pageable pageable);

    List<TaskTurnEntity> findByTaskIdAndStatusOrderByCreatedAtMsDescIdDesc(
            String taskId,
            String status,
            Pageable pageable);

    List<TaskTurnEntity> findByTaskIdOrderByCreatedAtMsDescIdDesc(String taskId, Pageable pageable);
}
