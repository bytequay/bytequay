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

import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.TaskState;
import com.bytequay.persistence.sqlite.entity.AgentTaskEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AgentTaskJpaRepository
        extends JpaRepository<AgentTaskEntity, String>
{
    List<AgentTaskEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    List<AgentTaskEntity> findByStateNotInOrderByCreatedAtAsc(Collection<TaskState> states);

    @Query("""
            FROM AgentTaskEntity task
            WHERE task.state IN :terminalStates
              AND task.cleanupState <> :deleted
              AND (task.deleteAfter <= :now OR task.cleanupState = :retryPending)
            ORDER BY task.deleteAfter ASC
            """)
    List<AgentTaskEntity> findCleanupDue(
            @Param("terminalStates") Collection<TaskState> terminalStates,
            @Param("deleted") CleanupState deleted,
            @Param("retryPending") CleanupState retryPending,
            @Param("now") Instant now,
            Pageable pageable);
}
