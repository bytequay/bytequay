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

import com.bytequay.persistence.sqlite.entity.PrTimelineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrTimelineJpaRepository
        extends JpaRepository<PrTimelineEntity, Long>
{
    List<PrTimelineEntity> findByPrIdOrderByTimestampAscIdAsc(Long prId);

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

    /**
     * Returns the GitHub event ids already stored for this PR. Used by
     * the incremental save path to filter the freshly-fetched timeline
     * down to genuinely-new events before insert.
     */
    @Query("SELECT e.githubId FROM PrTimelineEntity e WHERE e.prId = :prId AND e.githubId IS NOT NULL")
    List<Long> findGithubIdsByPrId(@Param("prId") Long prId);

    /**
     * Returns the {@code pr_id}s whose timeline contains a {@code commented}
     * event with the given GitHub id. Used to route an edit / reaction
     * mutation to the correct cached detail when the caller has the
     * comment id but not the PR id. List shape rather than Optional in
     * case the same comment surfaces in multiple cached PRs (shouldn't
     * happen, but defensive against the unique constraint being scoped to
     * (pr_id, github_id)).
     */
    @Query("SELECT e.prId FROM PrTimelineEntity e WHERE e.githubId = :githubId AND e.event = 'commented'")
    List<Long> findPrIdsByCommentedEventGithubId(@Param("githubId") long githubId);
}
