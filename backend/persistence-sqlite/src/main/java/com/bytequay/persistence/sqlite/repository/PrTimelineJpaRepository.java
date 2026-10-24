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

    @Query("""
            SELECT e.prId AS prId, e.event AS event, e.actor AS actor, e.timestamp AS timestamp
            FROM PrTimelineEntity e WHERE e.prId IN :ids AND e.event IN ('commented', 'reviewed', 'review_commented', 'committed')
            """)
    List<ChangeEvent> findChangeEvents(@Param("ids") Set<Long> ids);

    interface ChangeEvent
    {
        long getPrId();

        String getEvent();

        String getActor();

        Instant getTimestamp();
    }

    @Query(value = """
            SELECT pr_id AS prId, event FROM (
                SELECT pr_id, event, ROW_NUMBER() OVER (PARTITION BY pr_id ORDER BY timestamp DESC, id DESC) AS position
                FROM pr_timeline WHERE pr_id IN :ids AND event IN ('added_to_merge_queue', 'removed_from_merge_queue')
            ) WHERE position = 1
            """, nativeQuery = true)
    List<QueueState> findLatestQueueEvents(@Param("ids") Set<Long> ids);

    interface QueueState
    {
        long getPrId();

        String getEvent();
    }

    // Inline replies carry their own timestamps inside the payload, so every thread remains a candidate.
    // LENGTH keeps SQLite off the unique local_event_key index: every GitHub row shares its NULL key, so the
    // planner's one-row guess for IS NULL would scan the whole table for each correlated lookup instead of seeking by PR.
    @Query("""
            SELECT e.prId AS prId, e.event AS event, e.actor AS actor, e.timestamp AS timestamp, e.body AS body, e.payload AS payload
            FROM PrTimelineEntity e
            WHERE e.prId IN :ids AND LENGTH(e.localEventKey) IS NULL AND e.event NOT IN :ignored
              AND (e.event = 'review_thread' OR (e.timestamp IS NOT NULL AND NOT EXISTS (
                  SELECT newer.id FROM PrTimelineEntity newer
                  WHERE newer.prId = e.prId AND LENGTH(newer.localEventKey) IS NULL AND newer.event NOT IN :ignored
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

        String getBody();

        PrTimelinePayload getPayload();
    }

    @Query("""
            SELECT e.prId AS prId, e.actor AS actor, e.timestamp AS timestamp, e.payload AS payload
            FROM PrTimelineEntity e
            WHERE e.prId IN :ids AND e.event = 'committed' AND LENGTH(e.localEventKey) IS NULL AND e.timestamp > :after
            ORDER BY e.timestamp DESC, e.id DESC
            """)
    List<CommitCandidate> findCommitCandidates(@Param("ids") Set<Long> ids, @Param("after") Instant after);

    interface CommitCandidate
    {
        long getPrId();

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
