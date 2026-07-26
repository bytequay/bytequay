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

interface AgentRunJpaRepository
        extends JpaRepository<AgentRunEntity, String>
{
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.status = :to, r.finishedAtMs = :finishedAtMs "
            + "WHERE r.id = :id AND r.status = :expected")
    int casStatus(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("to") String to,
            @Param("finishedAtMs") Long finishedAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.status = :to, r.finishedAtMs = :finishedAtMs, "
            + "r.pauseReason = :pauseReason, r.outcome = :outcome "
            + "WHERE r.id = :id AND r.status = :expected")
    int transition(
            @Param("id") String id,
            @Param("expected") String expected,
            @Param("to") String to,
            @Param("finishedAtMs") Long finishedAtMs,
            @Param("pauseReason") String pauseReason,
            @Param("outcome") String outcome);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.iterations = :iterations, r.costUsdMilli = :costUsdMilli, "
            + "r.tokensIn = :tokensIn, r.tokensOut = :tokensOut WHERE r.id = :id")
    int updateProgress(
            @Param("id") String id,
            @Param("iterations") int iterations,
            @Param("costUsdMilli") long costUsdMilli,
            @Param("tokensIn") long tokensIn,
            @Param("tokensOut") long tokensOut);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.budget = :budget WHERE r.id = :id")
    int updateBudget(@Param("id") String id, @Param("budget") Integer budget);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.headline = :headline, r.outcome = :outcome WHERE r.id = :id")
    int updateHeadline(
            @Param("id") String id,
            @Param("headline") String headline,
            @Param("outcome") String outcome);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.workspaceId = :workspaceId, r.threadId = :threadId, "
            + "r.provider = :provider, r.model = :model, r.launchInput = :launchInput "
            + "WHERE r.id = :id")
    int updateOwnership(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("threadId") String threadId,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("launchInput") String launchInput);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.metricsJson = :metricsJson WHERE r.id = :id")
    int updateMetrics(@Param("id") String id, @Param("metricsJson") String metricsJson);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AgentRunEntity r SET r.costUsdMilli = :costUsdMilli, "
            + "r.tokensIn = :tokensIn, r.tokensOut = :tokensOut, r.stepCursor = :stepCursor "
            + "WHERE r.id = :id")
    int updateAccounting(
            @Param("id") String id,
            @Param("costUsdMilli") long costUsdMilli,
            @Param("tokensIn") long tokensIn,
            @Param("tokensOut") long tokensOut,
            @Param("stepCursor") int stepCursor);

    List<AgentRunEntity> findByWorkspaceIdOrderByStartedAtMsDesc(String workspaceId);

    List<AgentRunEntity> findByThreadIdOrderByStartedAtMsDesc(String threadId);

    /** Every run for a task, newest-first. {@code kind} / {@code
     *  parentStageId} / {@code status} narrowing happens in the store —
     *  the row count per task is small enough that filtering client-side
     *  beats a derived-query permutation for every optional-filter
     *  combination. */
    List<AgentRunEntity> findByTaskIdOrderByStartedAtMsDesc(String taskId);

    List<AgentRunEntity> findByReviewRoundIdOrderByStartedAtMsAsc(String reviewRoundId);
}
