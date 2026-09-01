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

import com.bytequay.core.cherrypick.CherryPickExecution.Status;
import com.bytequay.persistence.sqlite.entity.CherryPickExecutionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CherryPickExecutionJpaRepository
        extends JpaRepository<CherryPickExecutionEntity, String>
{
    Optional<CherryPickExecutionEntity> findByStartIdempotencyKey(String startIdempotencyKey);

    Optional<CherryPickExecutionEntity> findByAgentTaskId(String agentTaskId);

    List<CherryPickExecutionEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    List<CherryPickExecutionEntity> findByStatusOrderByUpdatedAtAsc(Status status, Pageable pageable);
}
