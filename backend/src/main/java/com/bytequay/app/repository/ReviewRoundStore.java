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

import java.util.List;
import java.util.Optional;

/** Persistence boundary for {@link ReviewRound}. */
public interface ReviewRoundStore
{
    ReviewRound save(ReviewRound round);

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
