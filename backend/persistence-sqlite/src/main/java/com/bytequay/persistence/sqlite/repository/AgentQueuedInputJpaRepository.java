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

import com.bytequay.persistence.sqlite.entity.AgentQueuedInputEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentQueuedInputJpaRepository
        extends JpaRepository<AgentQueuedInputEntity, String>
{
    Optional<AgentQueuedInputEntity> findByTaskIdAndIdempotencyKey(String taskId, String idempotencyKey);

    List<AgentQueuedInputEntity> findByTaskIdOrderByPositionAsc(String taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AgentQueuedInputEntity input WHERE input.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") String taskId);
}
