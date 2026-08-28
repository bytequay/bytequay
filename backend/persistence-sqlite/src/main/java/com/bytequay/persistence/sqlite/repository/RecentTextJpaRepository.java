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

import com.bytequay.persistence.sqlite.entity.RecentTextEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RecentTextJpaRepository
        extends JpaRepository<RecentTextEntity, String>
{
    List<RecentTextEntity> findByItemKeyOrderByWrittenAtDesc(String itemKey);

    /** Case-insensitive contains, newest first. The fragment arrives already lowercased. */
    @Query("SELECT e FROM RecentTextEntity e WHERE LOWER(e.body) LIKE CONCAT('%', :fragment, '%') "
            + "ORDER BY e.writtenAt DESC")
    List<RecentTextEntity> findByBodyContaining(@Param("fragment") String fragment, Limit limit);

    @Modifying
    @Query("DELETE FROM RecentTextEntity e WHERE e.writtenAt < :cutoff")
    int deleteByWrittenAtBefore(@Param("cutoff") Instant cutoff);
}
