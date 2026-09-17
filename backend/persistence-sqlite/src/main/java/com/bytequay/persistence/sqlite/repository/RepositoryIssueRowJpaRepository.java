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

import com.bytequay.persistence.sqlite.entity.RepositoryIssueEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepositoryIssueRowJpaRepository
        extends JpaRepository<RepositoryIssueEntity, Long>
{
    Page<RepositoryIssueEntity> findByRepositoryId(long repositoryId, Pageable pageable);

    Optional<RepositoryIssueEntity> findByRepositoryIdAndNumber(long repositoryId, int number);

    @Query(value = """
            SELECT issue.* FROM repository_issue_rows issue
            WHERE issue.repository_id = :repositoryId
              AND EXISTS (SELECT 1 FROM json_each(issue.assignees) assignee WHERE lower(assignee.value) = lower(:login))
              AND NOT EXISTS (SELECT 1 FROM issue_goal goal WHERE goal.host = 'github.com' AND goal.issue_id = issue.id AND goal.is_goal = 1)
              AND NOT EXISTS (SELECT 1 FROM issue_task task WHERE task.host = 'github.com' AND task.issue_id = issue.id)
            ORDER BY issue.updated_at DESC, issue.number DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<RepositoryIssueEntity> findAssignedEligible(@Param("repositoryId") long repositoryId, @Param("login") String login, @Param("limit") int limit);

    @Modifying
    @Query("delete from RepositoryIssueEntity issue where issue.repositoryId = :repositoryId "
            + "and (issue.syncMarker is null or issue.syncMarker <> :syncMarker)")
    int deleteStale(
            @Param("repositoryId") long repositoryId,
            @Param("syncMarker") String syncMarker);
}
