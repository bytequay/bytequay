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
package com.bytequay.app.repository.sqlite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

interface PrDetailJpaRepository
        extends JpaRepository<PrDetailEntity, Long>
{
    @Modifying
    @Query("DELETE FROM PrDetailEntity e WHERE e.prId IN :ids")
    void deleteByPrIdIn(@Param("ids") Set<Long> ids);

    @Query("SELECT e.syncedAt FROM PrDetailEntity e WHERE e.prId = :prId")
    Optional<Instant> findSyncedAtByPrId(@Param("prId") long prId);

    /**
     * Lists (head_ref, pr_number) for every open PR in {@code repo}
     * whose detail has been synced. Drives the IN REVIEW column on
     * the local-repo branches kanban.
     */
    @Query("""
            SELECT new com.bytequay.app.repository.sqlite.HeadRefRow(d.headRef, p.number)
            FROM PrDetailEntity d
            JOIN PullRequestEntity p ON p.id = d.prId
            WHERE p.repo = :repo
              AND p.state = 'open'
              AND d.headRef IS NOT NULL
            """)
    List<HeadRefRow> findOpenHeadRefsForRepo(@Param("repo") String repo);
}
