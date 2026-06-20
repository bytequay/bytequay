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

interface TaskStageIterationJpaRepository
        extends JpaRepository<TaskStageIterationEntity, String>
{
    /** The iteration that tracks a given monitor turn (one per turn). */
    Optional<TaskStageIterationEntity> findFirstByTurnId(String turnId);

    /** The iteration whose summary is being solicited by a follow-up turn. */
    Optional<TaskStageIterationEntity> findFirstBySummaryRequestTurnId(String summaryRequestTurnId);

    /** Highest iteration number in a stage, to compute the next one. */
    Optional<TaskStageIterationEntity> findFirstByStageIdOrderByIterationNumberDesc(String stageId);

    /** All iterations of a stage, oldest-first — the stage detail bands. */
    List<TaskStageIterationEntity> findByStageIdOrderByIterationNumberAsc(String stageId);

    /** Most-recent iterations that carry a summary, for a task — the M4
     *  cross-agent context hook. */
    List<TaskStageIterationEntity> findBySummaryTextIsNotNullAndTaskIdOrderByStartedAtMsDesc(
            String taskId, Pageable pageable);
}
