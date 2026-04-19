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
import java.util.Optional;
import java.util.Set;

public interface PullRequestJpaRepository
        extends JpaRepository<PullRequestEntity, Long>
{
    /** Returns the most recent {@code syncedAt} across all rows. */
    @Query("SELECT MAX(e.syncedAt) FROM PullRequestEntity e")
    Optional<Instant> findMaxSyncedAt();

    /** Deletes all rows whose id is not in the given set. */
    @Modifying
    @Query("DELETE FROM PullRequestEntity e WHERE e.id NOT IN :ids")
    void deleteAllByIdNotIn(@Param("ids") Set<Long> ids);

    /** Returns the GitHub PR id for the given repo + number pair. */
    @Query("SELECT e.id FROM PullRequestEntity e WHERE e.repo = :repo AND e.number = :number")
    Optional<Long> findIdByRepoAndNumber(@Param("repo") String repo, @Param("number") int number);
}
