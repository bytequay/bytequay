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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface PrReviewJpaRepository
        extends JpaRepository<PrReviewEntity, Long>
{
    List<PrReviewEntity> findByPrId(Long prId);

    @Modifying
    @Query("DELETE FROM PrReviewEntity e WHERE e.prId = :prId")
    void deleteByPrId(@Param("prId") Long prId);

    /**
     * Returns distinct PR ids whose review rows include a null
     * {@code submitted_at} column. Bounded by the caller via
     * {@link Pageable} to keep the per-sync rate-limit cost
     * predictable.
     */
    @Query("SELECT DISTINCT e.prId FROM PrReviewEntity e WHERE e.submittedAt IS NULL")
    List<Long> findDistinctPrIdsWithNullSubmittedAt(Pageable pageable);
}
