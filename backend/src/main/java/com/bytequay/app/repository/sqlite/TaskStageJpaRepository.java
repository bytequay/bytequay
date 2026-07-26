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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface TaskStageJpaRepository
        extends JpaRepository<TaskStageEntity, String>
{
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskStageEntity s SET s.state = :to, s.closedAtMs = :closedAtMs "
            + "WHERE s.id = :id AND s.state = :expected")
    int casState(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("to") String to,
            @Param("closedAtMs") Long closedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskStageEntity s SET s.metricsJson = :metricsJson WHERE s.id = :id")
    int updateMetricsJson(@Param("id") String id, @Param("metricsJson") String metricsJson);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskStageEntity s SET s.workModelJson = :workModelJson WHERE s.id = :id")
    int updateWorkModelJson(@Param("id") String id, @Param("workModelJson") String workModelJson);

    /** A task's stages oldest-first. */
    List<TaskStageEntity> findByTaskIdOrderByOpenedAtMsAsc(String taskId);

    /** Latest stage in one of the given states. */
    Optional<TaskStageEntity> findFirstByTaskIdAndStateInOrderByOpenedAtMsDesc(
            String taskId, List<String> states);

    /** Latest stage of a given type in a given state. */
    Optional<TaskStageEntity> findFirstByTaskIdAndStageTypeAndStateOrderByOpenedAtMsDesc(
            String taskId, String stageType, String state);
}
