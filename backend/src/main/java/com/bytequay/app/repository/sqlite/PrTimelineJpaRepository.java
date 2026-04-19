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

interface PrTimelineJpaRepository
        extends JpaRepository<PrTimelineEntity, Long>
{
    List<PrTimelineEntity> findByPrId(Long prId);

    @Modifying
    @Query("DELETE FROM PrTimelineEntity e WHERE e.prId = :prId")
    void deleteByPrId(@Param("prId") Long prId);

    /**
     * Returns the GitHub event ids already stored for this PR. Used by
     * the incremental save path to filter the freshly-fetched timeline
     * down to genuinely-new events before insert.
     */
    @Query("SELECT e.githubId FROM PrTimelineEntity e WHERE e.prId = :prId AND e.githubId IS NOT NULL")
    List<Long> findGithubIdsByPrId(@Param("prId") Long prId);
}
