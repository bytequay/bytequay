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

import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PullRequestJpaRepository
        extends JpaRepository<PullRequestEntity, Long>
{
    @Query("SELECT e FROM PullRequestEntity e WHERE e.dashboardVisible = true OR e.dashboardVisible IS NULL")
    Page<PullRequestEntity> findDashboardPage(Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE (e.dashboardVisible = true OR e.dashboardVisible IS NULL) "
            + "AND (LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number)")
    Page<PullRequestEntity> searchDashboardPage(
            @Param("search") String search,
            @Param("number") Integer number,
            Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true")
    Page<PullRequestEntity> findWorkspacePage(@Param("repo") String repo, Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true "
            + "AND (LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number)")
    Page<PullRequestEntity> searchWorkspacePage(
            @Param("repo") String repo,
            @Param("search") String search,
            @Param("number") Integer number,
            Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) "
            + "AND e.workspaceVisible = true "
            + "AND (e.workspaceSyncMarker IS NULL OR e.workspaceSyncMarker <> :syncMarker)")
    List<PullRequestEntity> findWorkspaceRowsMissingSync(
            @Param("repo") String repo,
            @Param("syncMarker") String syncMarker);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE "
            + "(e.dashboardVisible = true OR e.dashboardVisible IS NULL) "
            + "AND (e.state IS NULL OR e.state = 'open')")
    long countOpenDashboard();

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE "
            + "(e.dashboardVisible = true OR e.dashboardVisible IS NULL) "
            + "AND (e.state IS NULL OR e.state = 'open') "
            + "AND (LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number)")
    long countOpenDashboardSearch(@Param("search") String search, @Param("number") Integer number);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) "
            + "AND e.workspaceVisible = true AND (e.state IS NULL OR e.state = 'open')")
    long countOpenWorkspace(@Param("repo") String repo);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) "
            + "AND e.workspaceVisible = true AND (e.state IS NULL OR e.state = 'open') "
            + "AND (LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number)")
    long countOpenWorkspaceSearch(
            @Param("repo") String repo,
            @Param("search") String search,
            @Param("number") Integer number);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PullRequestEntity e SET e.dashboardVisible = false "
            + "WHERE (e.dashboardVisible = true OR e.dashboardVisible IS NULL) "
            + "AND (e.dashboardSyncMarker IS NULL OR e.dashboardSyncMarker <> :syncMarker) "
            + "AND ((LOWER(e.state) IN ('closed', 'merged') AND e.updatedAt < :retentionCutoff "
            + "AND (e.closedAt IS NULL OR e.closedAt < :retentionCutoff)) "
            + "OR ((e.state IS NULL OR LOWER(e.state) NOT IN ('closed', 'merged')) "
            + "AND e.syncedAt < :retentionCutoff)) "
            + "AND e.id NOT IN (SELECT state.prId FROM PrViewStateEntity state "
            + "WHERE state.reviewedAt > :retentionCutoff)")
    int hideStaleDashboardRows(
            @Param("syncMarker") String syncMarker,
            @Param("retentionCutoff") Instant retentionCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PullRequestEntity e SET e.workspaceVisible = false "
            + "WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true "
            + "AND LOWER(e.state) IN ('closed', 'merged') AND e.updatedAt < :retentionCutoff "
            + "AND (e.closedAt IS NULL OR e.closedAt < :retentionCutoff)")
    int hideExpiredWorkspaceRows(@Param("repo") String repo, @Param("retentionCutoff") Instant retentionCutoff);

    @Query("SELECT e.id FROM PullRequestEntity e WHERE e.dashboardVisible = false "
            + "AND (e.workspaceVisible IS NULL OR e.workspaceVisible = false)")
    Set<Long> findInvisibleIds();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PullRequestEntity e WHERE e.dashboardVisible = false "
            + "AND (e.workspaceVisible IS NULL OR e.workspaceVisible = false)")
    int deleteInvisibleRows();

    @Query("SELECT e.id FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.number = :number")
    Optional<Long> findIdByRepoAndNumber(@Param("repo") String repo, @Param("number") int number);
}
