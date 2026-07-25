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

import java.util.List;
import java.util.Optional;

interface PrJpaRepository
        extends JpaRepository<PrEntity, String>
{
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PrEntity p SET p.localReviewEpoch = p.localReviewEpoch + 1 WHERE p.id = :prId")
    int incrementLocalReviewEpoch(@Param("prId") String prId);

    /** The single local PR for a task, if one has been created. */
    Optional<PrEntity> findByTaskId(String taskId);

    /**
     * The external PR already synced in for this (repo, remote PR number).
     * Scoped to {@code origin = 'external'}: a task that publishes its PR
     * also stamps the same remote number onto its own {@code origin='task'}
     * row, so an unscoped lookup would match both and throw
     * NonUniqueResultException. The partial unique index guarantees at most
     * one external row per (repo, number), so this stays single-valued.
     */
    @Query("SELECT p FROM PrEntity p WHERE p.repo = ?1 AND p.remotePrNumber = ?2 AND p.origin = 'external'")
    Optional<PrEntity> findByRepoAndRemotePrNumber(String repo, Integer remotePrNumber);

    /**
     * The task-origin row pushed to this (repo, number). {@code findFirst}
     * rather than a unique lookup so a stray duplicate can't throw before the
     * reconcile backfill has run.
     */
    Optional<PrEntity> findFirstByRepoAndRemotePrNumberAndOrigin(
            String repo, Integer remotePrNumber, String origin);

    /** Every PR currently on the dashboard — {@code watch_reason} is set
     *  precisely for rows the relevant-PR search has surfaced. */
    List<PrEntity> findByWatchReasonIsNotNull();

    /** Pushed task rows missing their {@code repo} (legacy half-pushed rows). */
    @Query("SELECT p FROM PrEntity p WHERE p.origin = 'task' AND p.repo IS NULL "
            + "AND p.remotePrUrl IS NOT NULL AND p.remotePrNumber IS NOT NULL")
    List<PrEntity> findPushedTaskPrsMissingRepo();

    /** Ids of task rows that have a separate external row for the same GitHub PR. */
    @Query("SELECT p.id FROM PrEntity p WHERE p.origin = 'task' "
            + "AND p.repo IS NOT NULL AND p.remotePrNumber IS NOT NULL "
            + "AND EXISTS (SELECT 1 FROM PrEntity e WHERE e.origin = 'external' "
            + "AND e.repo = p.repo AND e.remotePrNumber = p.remotePrNumber)")
    List<String> findTaskPrIdsWithExternalTwin();
}
