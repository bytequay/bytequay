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
package com.bytequay.core.review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable storage for reviews and everything they own; product data, independent of task runtime logs. */
public interface AgentReviewStore
{
    AgentReview insertReview(AgentReview review);

    /** Optimistic update: the stored version must match the given one, and the returned review carries the next version. */
    AgentReview updateReview(AgentReview review);

    Optional<AgentReview> findReview(String reviewId);

    Optional<AgentReview> findOpenReview(String workspaceId, String repo, int number);

    Optional<AgentReview> findReviewByTask(String taskId);

    List<AgentReview> listOpenReviews();

    /** Lists open and closed workspace reviews by descending update time, then ascending ID. */
    List<AgentReview> listReviews(String workspaceId);

    ReviewRun insertRun(ReviewRun run);

    ReviewRun updateRun(ReviewRun run);

    /** Commits a run's acceptance and the review's successful-head cursor together. */
    void acceptRun(AgentReview review, ReviewRun run);

    /** Writes a finding and the run that records the operation replaying it in one transaction. */
    ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run);

    /** Writes a message and the run that records the operation replaying it in one transaction. */
    ReviewMessage insertMessage(ReviewMessage message, ReviewRun run);

    Optional<ReviewRun> findRun(String runId);

    List<ReviewRun> listRuns(String reviewId);

    /**
     * Reads bounded local history across open and closed reviews of one workspace and repository.
     * The storage query applies the limit before loading run records; this is not a complete history search.
     *
     * @param workspaceId owning workspace
     * @param repo case-insensitive owner/repository name
     * @param limit maximum results, from 1 through 10
     * @return runs ordered by descending update time, then ascending ID
     * @throws IllegalArgumentException when the limit is outside the supported range
     */
    List<ReviewRun> listRecentRuns(String workspaceId, String repo, int limit);

    ReviewFinding insertFinding(ReviewFinding finding);

    ReviewFinding updateFinding(ReviewFinding finding);

    Optional<ReviewFinding> findFinding(String findingId);

    List<ReviewFinding> listFindings(String reviewId);

    ReviewMessage insertMessage(ReviewMessage message);

    ReviewMessage updateMessage(ReviewMessage message);

    /** Removes a question that no turn or queue row ever accepted. */
    void deleteMessage(String messageId);

    Optional<ReviewMessage> findMessage(String messageId);

    /** Reads only the preceding messages on one saved discussion target, newest first. */
    List<ReviewMessage> listEarlierMessages(String reviewId, String runId, Optional<String> findingId, Optional<String> cardId,
            int targetRevision, Instant createdAt, String messageId, int limit);

    List<ReviewMessage> listMessages(String reviewId);
}
