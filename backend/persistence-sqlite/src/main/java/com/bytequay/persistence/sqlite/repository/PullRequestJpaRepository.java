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
import java.util.Optional;
import java.util.Set;

public interface PullRequestJpaRepository
        extends JpaRepository<PullRequestEntity, Long>
{
    @Query("SELECT e FROM PullRequestEntity e WHERE e.dashboardVisible = true OR e.dashboardVisible IS NULL")
    Page<PullRequestEntity> findDashboardPage(Pageable pageable);

    @Query("SELECT e FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true")
    Page<PullRequestEntity> findWorkspacePage(@Param("repo") String repo, Pageable pageable);

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE "
            + "(e.dashboardVisible = true OR e.dashboardVisible IS NULL) "
            + "AND (e.state IS NULL OR e.state = 'open')")
    long countOpenDashboard();

    @Query("SELECT COUNT(e) FROM PullRequestEntity e WHERE LOWER(e.repo) = LOWER(:repo) "
            + "AND e.workspaceVisible = true AND (e.state IS NULL OR e.state = 'open')")
    long countOpenWorkspace(@Param("repo") String repo);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PullRequestEntity e SET e.dashboardVisible = false "
            + "WHERE (e.dashboardVisible = true OR e.dashboardVisible IS NULL) "
            + "AND (e.dashboardSyncMarker IS NULL OR e.dashboardSyncMarker <> :syncMarker) "
            + "AND e.id NOT IN (SELECT state.prId FROM PrViewStateEntity state "
            + "WHERE state.reviewedAt > :handledCutoff)")
    int hideStaleDashboardRows(
            @Param("syncMarker") String syncMarker,
            @Param("handledCutoff") Instant handledCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PullRequestEntity e SET e.workspaceVisible = false "
            + "WHERE LOWER(e.repo) = LOWER(:repo) AND e.workspaceVisible = true "
            + "AND (e.workspaceSyncMarker IS NULL OR e.workspaceSyncMarker <> :syncMarker)")
    int hideStaleWorkspaceRows(@Param("repo") String repo, @Param("syncMarker") String syncMarker);

    @Query("SELECT e.id FROM PullRequestEntity e WHERE e.dashboardVisible = false "
            + "AND (e.workspaceVisible IS NULL OR e.workspaceVisible = false)")
    Set<Long> findInvisibleIds();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PullRequestEntity e WHERE e.dashboardVisible = false "
            + "AND (e.workspaceVisible IS NULL OR e.workspaceVisible = false)")
    int deleteInvisibleRows();

    /** Returns the GitHub PR id for the given repo + number pair. */
    @Query("SELECT e.id FROM PullRequestEntity e WHERE e.repo = :repo AND e.number = :number")
    Optional<Long> findIdByRepoAndNumber(@Param("repo") String repo, @Param("number") int number);
}
