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

interface StageMessageJpaRepository
        extends JpaRepository<StageMessageEntity, String>
{
    /** Transcript order — oldest-first by per-stage seq. */
    List<StageMessageEntity> findByStageIdOrderBySeqAsc(String stageId);

    /** Per-task aggregation across all of a task's stage transcripts,
     *  transcript order. */
    List<StageMessageEntity> findByTaskIdOrderBySeqAsc(String taskId);

    /** Highest seq for a stage, or {@code null} when the stage has no
     *  messages yet. Coalesced in the application layer (no DB-specific
     *  {@code COALESCE}) — mirrors {@link ThreadMessageJpaRepository#maxSeq}. */
    @Query("""
            SELECT MAX(m.seq) FROM StageMessageEntity m
            WHERE m.stageId = :stageId
            """)
    Long maxSeq(@Param("stageId") String stageId);

    /** Sum of {@code tokensIn + tokensOut} across the inclusive per-stage seq
     *  range; null token counts treated as zero, empty range coalesced to 0. */
    @Query("""
            SELECT COALESCE(
                     SUM(COALESCE(m.tokensIn, 0) + COALESCE(m.tokensOut, 0)),
                     0)
            FROM StageMessageEntity m
            WHERE m.stageId = :stageId
              AND m.seq >= :firstSeq
              AND m.seq <= :lastSeq
            """)
    long sumTokensBetween(
            @Param("stageId") String stageId,
            @Param("firstSeq") long firstSeq,
            @Param("lastSeq") long lastSeq);

    /** Inclusive-range slice in transcript order. */
    @Query("""
            SELECT m FROM StageMessageEntity m
            WHERE m.stageId = :stageId
              AND m.seq >= :firstSeq
              AND m.seq <= :lastSeq
            ORDER BY m.seq ASC
            """)
    List<StageMessageEntity> findByStageIdAndSeqBetween(
            @Param("stageId") String stageId,
            @Param("firstSeq") long firstSeq,
            @Param("lastSeq") long lastSeq);

    /** Cascade delete when the parent stage is removed. */
    void deleteByStageId(String stageId);
}
