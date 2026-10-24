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

import com.bytequay.persistence.sqlite.entity.PrDetailEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PrDetailJpaRepository
        extends JpaRepository<PrDetailEntity, Long>
{
    @Modifying
    @Query("DELETE FROM PrDetailEntity e WHERE e.prId IN :ids")
    void deleteByPrIdIn(@Param("ids") Set<Long> ids);

    @Query("SELECT e.syncedAt FROM PrDetailEntity e WHERE e.prId = :prId")
    Optional<Instant> findSyncedAtByPrId(@Param("prId") long prId);

    @Query("SELECT e.metadataValidatedAt AS metadataValidatedAt, e.discussionValidatedAt AS discussionValidatedAt, e.checksValidatedAt AS checksValidatedAt, "
            + "e.discussionSourceUpdatedAt AS discussionSourceUpdatedAt FROM PrDetailEntity e WHERE e.prId = :prId")
    Optional<Freshness> findFreshness(@Param("prId") long prId);

    @Query("SELECT e.prId AS prId, e.authorAvatarUrl AS authorAvatarUrl FROM PrDetailEntity e "
            + "WHERE e.prId IN :ids AND e.authorAvatarUrl IS NOT NULL AND TRIM(e.authorAvatarUrl) <> ''")
    List<AuthorAvatar> findAuthorAvatarUrls(@Param("ids") Set<Long> ids);

    @Query("SELECT e.prId AS prId, e.state AS state, e.draft AS draft, e.mergedAt AS mergedAt FROM PrDetailEntity e WHERE e.prId IN :ids")
    List<StateSummary> findStates(@Param("ids") Set<Long> ids);

    @Query("SELECT e.prId AS prId, e.requestedReviewers AS requestedReviewers FROM PrDetailEntity e WHERE e.prId IN :ids")
    List<RequestedReviewers> findRequestedReviewers(@Param("ids") Set<Long> ids);

    interface Freshness
    {
        Instant getMetadataValidatedAt();

        Instant getDiscussionValidatedAt();

        Instant getChecksValidatedAt();

        Instant getDiscussionSourceUpdatedAt();
    }

    interface RequestedReviewers
    {
        long getPrId();

        List<String> getRequestedReviewers();
    }

    interface StateSummary
    {
        long getPrId();

        String getState();

        boolean isDraft();

        Instant getMergedAt();
    }

    interface AuthorAvatar
    {
        long getPrId();

        String getAuthorAvatarUrl();
    }
}
