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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ReviewPassJpaRepository
        extends JpaRepository<ReviewPassEntity, String>
{
    /** Every pass for one thread, oldest first — a thread typically
     *  owns one active pass and a small tail of historical ones. */
    List<ReviewPassEntity> findByThreadIdOrderByCreatedAtMsAsc(String threadId);

    /** Total review spend (milli-USD) across passes created since
     *  {@code sinceMs} — powers the scheduler's rolling daily cost cap.
     *  COALESCE so an empty window returns 0 rather than null. */
    @Query("SELECT COALESCE(SUM(p.costUsdMilli), 0) FROM ReviewPassEntity p "
            + "WHERE p.createdAtMs >= :sinceMs")
    long sumCostUsdMilliSince(@Param("sinceMs") long sinceMs);

    /** Every pass for one PR across all threads. Lets the dashboard
     *  light up "this PR already has 2 review passes" without scanning
     *  the whole table. */
    List<ReviewPassEntity> findByRepoFullNameAndPrNumberOrderByCreatedAtMsDesc(
            String repoFullName, int prNumber);

    List<ReviewPassEntity> findByTaskStageIdIsNotNullAndPhaseIn(List<String> phases);

    /** Newest non-terminal THREAD-hosted pass for a PR — the dashboard's
     *  "is a standalone review open on this PR right now?" lookup. ({@code
     *  phase} stores the lowercase dbValue; terminal = {@code published}.) */
    Optional<ReviewPassEntity>
            findFirstByRepoFullNameAndPrNumberAndHostKindAndPhaseNotOrderByCreatedAtMsDesc(
                    String repoFullName, int prNumber, String hostKind, String phase);
}
