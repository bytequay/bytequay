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

    /** Inserts an accepted run carried from an earlier head, with the findings it carries and the review's cursor, in one transaction. */
    void carryRun(AgentReview review, ReviewRun run, List<ReviewFinding> findings);

    /** Writes a finding and the run that records the operation replaying it in one transaction. */
    ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run);

    /** Writes a message and the run that records the operation replaying it in one transaction. */
    ReviewMessage insertMessage(ReviewMessage message, ReviewRun run);

    /** Writes a withdrawn finding, the message explaining it, and the run that records the operation in one transaction. */
    void withdrawFinding(ReviewFinding finding, ReviewMessage message, ReviewRun run);

    Optional<ReviewRun> findRun(String runId);

    List<ReviewRun> listRuns(String reviewId);

    ReviewFinding insertFinding(ReviewFinding finding);

    ReviewFinding updateFinding(ReviewFinding finding);

    Optional<ReviewFinding> findFinding(String findingId);

    List<ReviewFinding> listFindings(String reviewId);

    ReviewMessage insertMessage(ReviewMessage message);

    ReviewMessage updateMessage(ReviewMessage message);

    /** Removes a question that no turn or queue row ever accepted. */
    void deleteMessage(String messageId);

    Optional<ReviewMessage> findMessage(String messageId);

    List<ReviewMessage> listMessages(String reviewId);
}
