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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PullRequestJpaRepository
        extends JpaRepository<PullRequestEntity, Long>
{
    @Query("SELECT e FROM PullRequestEntity e WHERE e.dashboardVisible = true OR e.origin IN ('AUTHORED', 'REVIEW_REQUESTED') "
            + "OR e.monitoredAt IS NOT NULL OR e.reviewRequestedAt IS NOT NULL "
            + "OR EXISTS (SELECT v.prId FROM PrViewStateEntity v WHERE v.prId = e.id AND v.reviewedAt IS NOT NULL) "
            + "OR (e.workspaceVisible = true AND EXISTS (SELECT w.id FROM WorkspaceEntity w "
            + "WHERE w.watched = true AND LOWER(e.repo) = LOWER(CONCAT(w.monitoredOwner, CONCAT('/', w.monitoredRepo))))) "
            + "ORDER BY e.updatedAt DESC, e.id DESC")
    List<PullRequestEntity> findAttentionCandidates();

    @Query("SELECT e FROM PullRequestEntity e WHERE "
            + "(e.dashboardVisible = true OR e.dashboardVisible IS NULL OR e.monitoredAt IS NOT NULL) "
            + "OR (e.workspaceVisible = true AND EXISTS (SELECT w.id FROM WorkspaceEntity w "
            + "WHERE w.watched = true AND LOWER(e.repo) = LOWER(CONCAT(w.monitoredOwner, CONCAT('/', w.monitoredRepo)))))")
    Page<PullRequestEntity> findDashboardPage(Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true")
    Page<PullRequestEntity> findWorkspacePage(@Param("repo") String repo, Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true "
            + "AND (LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number)")
    Page<PullRequestEntity> searchWorkspacePage(@Param("repo") String repo, @Param("search") String search, @Param("number") Integer number, Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true AND e.mergedAt IS NOT NULL "
            + "AND (:search = '' OR LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number) ORDER BY e.mergedAt DESC, e.id DESC")
    List<PullRequestEntity> findMergedWorkspace(@Param("repo") String repo, @Param("search") String search, @Param("number") Integer number, Pageable pageable);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE "
            + "((e.dashboardVisible = true OR e.dashboardVisible IS NULL OR e.monitoredAt IS NOT NULL) "
            + "OR (e.workspaceVisible = true AND EXISTS (SELECT w.id FROM WorkspaceEntity w "
            + "WHERE w.watched = true AND LOWER(e.repo) = LOWER(CONCAT(w.monitoredOwner, CONCAT('/', w.monitoredRepo)))))) "
            + "AND (e.state IS NULL OR e.state = 'open')")
    long countOpenDashboard();

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) "
            + "AND e.workspaceVisible = true AND (e.state IS NULL OR e.state = 'open')")
    long countOpenWorkspace(@Param("repo") String repo);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) "
            + "AND e.workspaceVisible = true AND (e.state IS NULL OR e.state = 'open') "
            + "AND (LOWER(e.title) LIKE CONCAT('%', CONCAT(LOWER(:search), '%')) OR e.number = :number)")
    long countOpenWorkspaceSearch(@Param("repo") String repo, @Param("search") String search, @Param("number") Integer number);

    /**
     * Looks a pull request up by its natural key. Together with
     * {@code findById} this is the only way a caller should reach a single row:
     * both hit an index and both are served from the persistence context on a
     * repeat read within one transaction.
     */
    Optional<PullRequestEntity> findByRepoIgnoreCaseAndNumber(String repo, int number);

    List<PullRequestEntity> findByRepoIgnoreCaseAndHeadRefOrderByNumberDesc(String repo, String headRef);
}
