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

import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PullRequestJpaRepository
        extends JpaRepository<PullRequestEntity, Long>
{
    String WATCHED = "EXISTS (SELECT w.id FROM WorkspaceEntity w WHERE w.watched = true AND LOWER(e.repo) = LOWER(CONCAT(w.monitoredOwner, CONCAT('/', w.monitoredRepo))))";
    String DASHBOARD = "e.dashboardVisible = true OR e.monitoredAt IS NOT NULL OR " + WATCHED;

    @Query("SELECT e FROM PullRequestEntity e WHERE e.dashboardVisible = true OR e.origin IN ('AUTHORED', 'REVIEW_REQUESTED') "
            + "OR e.monitoredAt IS NOT NULL OR e.reviewRequestedAt IS NOT NULL "
            + "OR EXISTS (SELECT v.prId FROM PrViewStateEntity v WHERE v.prId = e.id AND v.reviewedAt IS NOT NULL) OR " + WATCHED + " "
            + "ORDER BY e.updatedAt DESC, e.id DESC")
    List<PullRequestEntity> findAttentionCandidates();

    @Query("SELECT e FROM PullRequestEntity e WHERE e.dashboardVisible = true ORDER BY e.updatedAt DESC, e.id DESC")
    List<PullRequestEntity> findDashboardVisible();

    @Query("SELECT e FROM PullRequestEntity e WHERE " + DASHBOARD)
    Page<PullRequestEntity> findDashboardPage(Pageable pageable);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE (" + DASHBOARD + ") AND e.state = 'open'")
    long countOpenDashboard();

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.mergedAt IS NOT NULL "
            + "AND (:search = '' OR LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number) ORDER BY e.mergedAt DESC, e.id DESC")
    List<PullRequestEntity> findMerged(@Param("repo") String repo, @Param("search") String search, @Param("number") Integer number, Pageable pageable);

    Optional<PullRequestEntity> findByHostingRepositoryIdAndNumber(long repositoryId, int number);

    List<PullRequestEntity> findByHostingRepositoryIdAndHeadRefOrderByNumberDesc(long repositoryId, String headRef);

    /** A path names one repository on each server; the most recently updated request wins. */
    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.number = :number ORDER BY e.updatedAt DESC, e.id DESC")
    List<PullRequestEntity> findByPath(@Param("repo") String repo, @Param("number") int number);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.headRef = :headRef ORDER BY e.number DESC")
    List<PullRequestEntity> findByPathAndHeadRef(@Param("repo") String repo, @Param("headRef") String headRef);

    @Query("SELECT e.discussionActivityAt FROM PullRequestEntity e WHERE e.id = :id")
    Optional<Instant> findDiscussionActivityAt(@Param("id") long id);

    @Query("SELECT e.id AS id, e.cardActivity AS cardActivity FROM PullRequestEntity e WHERE e.id IN :ids AND e.cardActivity IS NOT NULL")
    List<CardActivity> findCardActivities(@Param("ids") Set<Long> ids);

    @Query("SELECT e.id AS id, e.attentionActivityAt AS attentionActivityAt FROM PullRequestEntity e WHERE e.id IN :ids "
            + "AND e.attentionActivityAt IS NOT NULL")
    List<AttentionActivity> findAttentionActivities(@Param("ids") Set<Long> ids);

    @Query("SELECT e.id AS id, e.discussionActivityAt AS discussionActivityAt FROM PullRequestEntity e WHERE e.id IN :ids "
            + "AND e.discussionActivityAt IS NOT NULL")
    List<DiscussionActivity> findDiscussionActivities(@Param("ids") Set<Long> ids);

    interface DiscussionActivity
    {
        long getId();

        Instant getDiscussionActivityAt();
    }

    interface CardActivity
    {
        long getId();

        PrActivitySummary getCardActivity();
    }

    interface AttentionActivity
    {
        long getId();

        Instant getAttentionActivityAt();
    }
}
