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
package com.bytequay.app.domain;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * One reviewer batch + the agent's entire response to it — triage, fix
 * commits, drafted replies, and any nested {@code ci_fix} run — gated
 * behind a single posting approval (plan-rail-runs.md R11-R13).
 *
 * @param idx 1-based round number for this task (round 1, 2, 3...)
 * @param reviewers the reviewer handles this batch came from, e.g. "@alice"
 * @param runId the round's own {@code agent_run(kind=review_round)} row,
 *              null until the run opens
 * @param gatedAt when drafts became ready for the user to review, null while live
 * @param postedAt when the user approved the gate (posted + pushed), null until then
 * @param origin {@code external} (a real reviewer's comment batch) or
 *               {@code brain} (a dev-end adversarial review the brain opened
 *               on itself, no external reviewer involved — R20-R24)
 * @param brainVerdict the brain's latest verdict on this round's diff —
 *                     {@code approved} / {@code changes_requested} / null
 *                     before the brain has reviewed it
 * @param iteration how many review turns have been enqueued so far — bumped
 *                  when a review turn is scheduled, not when the verdict
 *                  tool is called, so a turn that never calls {@code
 *                  record_review_verdict} still counts against the budget
 *                  instead of wedging the loop open forever
 * @param budget max review-fix cycles before escalating to the human
 *               (default 5, R23)
 */
public record ReviewRound(
        String id,
        String taskId,
        int idx,
        List<String> reviewers,
        ReviewRoundState status,
        ReviewRoundStats stats,
        String runId,
        Instant openedAt,
        Instant gatedAt,
        Instant postedAt,
        String origin,
        String brainVerdict,
        int iteration,
        int budget,
        ReviewRoundState pausedFrom,
        String codeFingerprint,
        int enqueueFailures,
        int kickAttempt,
        int gateRevision,
        String activeGateToken,
        Instant closedAt)
{
    public static final ReviewRoundState STATUS_TRIAGING = ReviewRoundState.TRIAGING;
    public static final ReviewRoundState STATUS_ADDRESSING = ReviewRoundState.ADDRESSING;
    public static final ReviewRoundState STATUS_AWAITING_GATE = ReviewRoundState.AWAITING_GATE;
    public static final ReviewRoundState STATUS_POSTED = ReviewRoundState.POSTED;
    public static final ReviewRoundState STATUS_CLOSED = ReviewRoundState.CLOSED;
    /** Operational failure parked for an explicit task-scoped Resume. */
    public static final ReviewRoundState STATUS_PAUSED = ReviewRoundState.PAUSED;

    public static final String ORIGIN_EXTERNAL = "external";
    public static final String ORIGIN_BRAIN = "brain";

    public static final String VERDICT_APPROVED = "approved";
    public static final String VERDICT_CHANGES_REQUESTED = "changes_requested";

    /** Default review-fix cycles before a brain review escalates to the
     *  human (R23) — deliberately conservative: two agents can deadlock
     *  arguing style or philosophy, and the human is the tiebreaker. */
    public static final int DEFAULT_BRAIN_BUDGET = 5;

    public ReviewRound
    {
        requireNonNull(status, "status is null");
    }

    /** Compatibility constructor for rows created before the P3b
     *  coordination checkpoints were added. */
    public ReviewRound(
            String id,
            String taskId,
            int idx,
            List<String> reviewers,
            ReviewRoundState status,
            ReviewRoundStats stats,
            String runId,
            Instant openedAt,
            Instant gatedAt,
            Instant postedAt,
            String origin,
            String brainVerdict,
            int iteration,
            int budget)
    {
        this(id, taskId, idx, reviewers, status, stats, runId, openedAt,
                gatedAt, postedAt, origin, brainVerdict, iteration, budget,
                null, null, 0, 0, 0, null, null);
    }

    public record ReviewRoundStats(int fixed, int replied, int pushedBack, int open)
    {
        public static ReviewRoundStats empty()
        {
            return new ReviewRoundStats(0, 0, 0, 0);
        }
    }

    /** True while the round is actively coordinating work. A PAUSED row
     * remains a durable checkpoint, but is not live or driveable. */
    public boolean isLive()
    {
        return status.isLive();
    }

    /** True once the brain's iteration budget is spent — the loop stops and
     *  escalates to the human regardless of the latest verdict (R23). */
    public boolean brainBudgetExhausted()
    {
        return iteration >= budget;
    }

    public ReviewRound withRunId(String runId)
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, brainVerdict, iteration, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }

    public ReviewRound withStatus(ReviewRoundState status)
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, brainVerdict, iteration, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }

    public ReviewRound withStats(ReviewRoundStats stats)
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, brainVerdict, iteration, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }

    public ReviewRound withGatedAt(Instant gatedAt)
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, brainVerdict, iteration, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }

    public ReviewRound withPostedAt(Instant postedAt)
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, brainVerdict, iteration, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }

    /** Copy with the brain's latest verdict recorded. Does not touch {@code
     *  iteration} — that's bumped separately, when the review turn is
     *  scheduled, so a skipped verdict call can't stall the budget. */
    public ReviewRound withBrainVerdict(String verdict)
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, verdict, iteration, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }

    /** Copy for a freshly scheduled review iteration. The prior verdict is
     *  deliberately cleared: if the new reviewer omits its verdict tool call,
     *  the loop must not silently reuse the preceding iteration's decision. */
    public ReviewRound withIterationBumped()
    {
        return new ReviewRound(id, taskId, idx, reviewers, status, stats, runId, openedAt, gatedAt, postedAt,
                origin, null, iteration + 1, budget, pausedFrom, codeFingerprint,
                enqueueFailures, kickAttempt, gateRevision, activeGateToken, closedAt);
    }
}
