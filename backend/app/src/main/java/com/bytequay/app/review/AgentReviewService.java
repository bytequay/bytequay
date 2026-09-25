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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one application service that owns private pull-request reviews: their agent task, runs,
 * summaries, findings and discussions. Publication is the user's and lives in
 * {@link ReviewPublicationService}; task execution stays in the agent service.
 */
public interface AgentReviewService
{
    /** The open review of a pull request, else its most recent ended one, else empty. */
    Optional<AgentReviewProjection> find(String workspaceId, String repo, int number);

    AgentReviewProjection get(String reviewId);

    /** Lists open and closed workspace reviews by descending update time, without starting work or refreshing GitHub. */
    List<AgentReviewProjection> list(String workspaceId);

    /** Starts a review, or returns the open one: one review per workspace and pull request. */
    AgentReviewProjection start(String workspaceId, String repo, int number);

    /** Cancels the active run and pauses follow-up; the review and its discussion stay open. */
    AgentReviewProjection stop(String reviewId);

    /** Lifts the pause and starts a run for the newest observed head when nothing is running. */
    AgentReviewProjection resume(String reviewId);

    /** Refreshes the pull request now and follows its head. */
    AgentReviewProjection update(String reviewId);

    /** Completes the task, closes the review durably, then requests clean worktree cleanup. */
    AgentReviewProjection end(String reviewId);

    /** A private question on the summary or a finding: a turn when idle, a visible queue entry when busy. */
    ReviewMessage ask(String reviewId, Optional<String> findingId, Optional<String> cardId, int targetRevision, String message);

    /** Admits a discussion input under the same lock as review head updates and closure. */
    void sendMessage(String reviewId, long expectedTaskVersion, String message, String idempotencyKey, boolean queued);

    ReviewFinding resolveFinding(String reviewId, String findingId, long expectedVersion, String reason);

    ReviewFinding reopenFinding(String reviewId, String findingId, long expectedVersion);

    /** The task a review owns, for the review-scoped activity routes. */
    AgentTask task(String reviewId);

    /**
     * Applies a user's edit to the review's working copy under the review lock and the task's worktree claim, so it
     * cannot land between review steps, or after Stop before the cancellation settles.
     *
     * @param mutation receives the claimed task, whose worktree is present
     * @return the mutation's result
     * @throws com.bytequay.core.review.ReviewConflictException when the review has ended, its working copy is gone, or the agent, a run, an update or queued input holds it
     */
    <T> T mutateWorkingCopy(String reviewId, Function<AgentTask, T> mutation);

    /** Idempotent progression of the current run from durable task facts: acceptance, completion repair, and pause. */
    void reconcile(String reviewId);

    /** Settles only shared preparation after verified runtime recovery, without advancing review assignments. */
    void reconcilePreparation(String reviewId);

    /** Records a newer remote head for every open review without moving its checkout or starting a run. */
    void observeHead(String repo, int number, String headSha);
}
