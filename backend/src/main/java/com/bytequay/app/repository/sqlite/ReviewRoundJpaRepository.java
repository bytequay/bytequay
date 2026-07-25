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

interface ReviewRoundJpaRepository
        extends JpaRepository<ReviewRoundEntity, String>
{
    List<ReviewRoundEntity> findByTaskIdOrderByOpenedAtMsDesc(String taskId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.status = :to "
            + "WHERE r.id = :id AND r.status = :expected")
    int casStatus(@Param("id") String id, @Param("expected") String expected, @Param("to") String to);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.statsJson = :statsJson WHERE r.id = :id")
    int updateStatsJson(@Param("id") String id, @Param("statsJson") String statsJson);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.runId = :runId WHERE r.id = :id")
    int updateRunId(@Param("id") String id, @Param("runId") String runId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.brainVerdict = :verdict WHERE r.id = :id")
    int updateBrainVerdict(@Param("id") String id, @Param("verdict") String verdict);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ReviewRoundEntity r SET r.gatedAtMs = :gatedAtMs, r.postedAtMs = :postedAtMs "
            + "WHERE r.id = :id")
    int updateGateTimes(
            @Param("id") String id,
            @Param("gatedAtMs") Long gatedAtMs,
            @Param("postedAtMs") Long postedAtMs);
}
