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

import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.persistence.sqlite.entity.PrTimelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PrTimelineJpaRepository
        extends JpaRepository<PrTimelineEntity, Long>
{
    List<PrTimelineEntity> findByPrIdOrderByTimestampAscIdAsc(Long prId);

    // Inline replies carry their own timestamps inside the payload, so every thread remains a candidate.
    @Query("""
            SELECT e.prId AS prId, e.event AS event, e.actor AS actor, e.timestamp AS timestamp, e.payload AS payload
            FROM PrTimelineEntity e
            WHERE e.prId IN :ids AND e.localEventKey IS NULL AND e.event NOT IN :ignored
              AND (e.event = 'review_thread' OR (e.timestamp IS NOT NULL AND NOT EXISTS (
                  SELECT newer.id FROM PrTimelineEntity newer
                  WHERE newer.prId = e.prId AND newer.localEventKey IS NULL AND newer.event NOT IN :ignored
                    AND (newer.timestamp > e.timestamp OR (newer.timestamp = e.timestamp AND newer.id > e.id)))))
            ORDER BY e.id
            """)
    List<ActivityCandidate> findLatestActivityCandidates(@Param("ids") Set<Long> ids, @Param("ignored") Set<String> ignored);

    interface ActivityCandidate
    {
        long getPrId();

        String getEvent();

        String getActor();

        Instant getTimestamp();

        PrTimelinePayload getPayload();
    }

    Optional<PrTimelineEntity> findByPrIdAndLocalEventKey(Long prId, String localEventKey);

    @Modifying
    @Query(value = """
            INSERT OR IGNORE INTO pr_timeline
                (pr_id, event, actor, timestamp, body, github_id, local_event_key)
            VALUES
                (:prId, :event, :actor, :timestamp, :body, NULL, :localEventKey)
            """, nativeQuery = true)
    void insertLocalEvent(
            @Param("prId") Long prId,
            @Param("event") String event,
            @Param("actor") String actor,
            @Param("timestamp") String timestamp,
            @Param("body") String body,
            @Param("localEventKey") String localEventKey);

    @Modifying
    @Query("DELETE FROM PrTimelineEntity e WHERE e.prId = :prId")
    void deleteByPrId(@Param("prId") Long prId);
}
