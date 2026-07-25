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
package com.bytequay.app.repository;

import com.bytequay.app.domain.ReviewRound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for {@link ReviewRound}. */
public interface ReviewRoundStore
{
    /** Full-row upsert. Legacy write path — it rewrites {@code status},
     *  so lifecycle callers migrate to {@link #insert} plus the targeted
     *  methods below, which cannot clobber a concurrent transition. */
    ReviewRound save(ReviewRound round);

    /** Insert a freshly opened round — the one write that legitimately
     *  carries an initial status. */
    default ReviewRound insert(ReviewRound round)
    {
        return save(round);
    }

    /** Compare-and-set the status column alone: move to {@code to} only
     *  while the row still holds {@code expected}. Never touches any
     *  other column.
     *
     *  @return true when the row was updated */
    default boolean updateStatusIf(String id, String expected, String to)
    {
        throw new UnsupportedOperationException("updateStatusIf");
    }

    /** Targeted stats write; never touches status. */
    default void updateStats(String id, ReviewRound.ReviewRoundStats stats)
    {
        throw new UnsupportedOperationException("updateStats");
    }

    /** Targeted run-linkage write; never touches status. */
    default void updateRunId(String id, String runId)
    {
        throw new UnsupportedOperationException("updateRunId");
    }

    /** Targeted verdict write; never touches status. */
    default void updateBrainVerdict(String id, String verdict)
    {
        throw new UnsupportedOperationException("updateBrainVerdict");
    }

    /** Targeted gate-timestamp write; never touches status. */
    default void updateGateTimes(String id, Instant gatedAt, Instant postedAt)
    {
        throw new UnsupportedOperationException("updateGateTimes");
    }

    Optional<ReviewRound> findById(String id);

    /** A task's rounds, newest-first. */
    List<ReviewRound> findByTask(String taskId);

    /** The task's currently live round (not posted/closed), if any — the
     *  idempotent "already collecting" check before opening a new one. */
    Optional<ReviewRound> findLiveByTask(String taskId);

    /** Every currently live round across all tasks — the backstop sweep's
     *  input (see {@code BrainReviewServiceImpl.reconcileStalledRounds}).
     *  Small table; an unfiltered scan is fine at this scale. */
    List<ReviewRound> findAllLive();

    /** Next 1-based round index for a task (highest existing + 1, or 1). */
    int nextIndex(String taskId);
}
