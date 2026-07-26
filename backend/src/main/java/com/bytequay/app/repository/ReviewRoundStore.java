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
import com.bytequay.app.domain.ReviewRoundState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for {@link ReviewRound}. */
public interface ReviewRoundStore
{
    /** Insert a freshly opened round — the one write that legitimately
     *  carries an initial status. */
    ReviewRound insert(ReviewRound round);

    /** Retained only so stale test doubles fail loudly instead of silently
     * rewriting lifecycle columns. Production code must use {@link #insert}
     * or a targeted command update. */
    @Deprecated(forRemoval = true)
    default ReviewRound save(ReviewRound round)
    {
        throw new UnsupportedOperationException("full-row review round save is retired");
    }

    /** Compare-and-set the status column alone: move to {@code to} only
     *  while the row still holds {@code expected}. Never touches any
     *  other column.
     *
     *  @return true when the row was updated */
    @Deprecated(forRemoval = true)
    default boolean updateStatusIf(String id, ReviewRoundState expected, ReviewRoundState to)
    {
        throw new UnsupportedOperationException("raw review round status writes are retired");
    }

    /** Park one live state while preserving the exact state Resume must
     *  restore. */
    default boolean parkIf(String id, ReviewRoundState expected)
    {
        throw new UnsupportedOperationException("parkIf");
    }

    /** Restore a parked round, clear its checkpoint, advance its durable
     *  kick identity, and clear a TRIAGING attempt's stale verdict. */
    default boolean resumeIf(String id, ReviewRoundState pausedFrom)
    {
        throw new UnsupportedOperationException("resumeIf");
    }

    /** Seal any expected non-terminal state and stamp its close time. */
    default boolean sealIf(String id, ReviewRoundState expected, Instant closedAt)
    {
        throw new UnsupportedOperationException("sealIf");
    }

    /** Persist the complete result of one Brain pass without exposing a
     *  load-set-save lifecycle write to callers. */
    default boolean concludeIf(
            String id,
            ReviewRoundState expected,
            ReviewRoundState to,
            AttemptFence attempt,
            ReviewRound.ReviewRoundStats stats,
            String verdict,
            Instant gatedAt,
            Instant closedAt)
    {
        throw new UnsupportedOperationException("concludeIf");
    }

    /** Adopt the exact green post-fix fingerprint and enter the next Brain
     *  pass. The iteration bump and delivery reset are one guarded write. */
    default boolean finishAddressingIf(
            String id,
            AttemptFence attempt,
            String validationClaimKey,
            String codeFingerprint)
    {
        throw new UnsupportedOperationException("finishAddressingIf");
    }

    /** Arm the exact human-reviewed gate revision/fingerprint once. */
    default boolean authorizeGateIf(
            String id,
            int expectedGateRevision,
            String codeFingerprint,
            String activeGateToken)
    {
        throw new UnsupportedOperationException("authorizeGateIf");
    }

    /** Token-fenced final posting edge. */
    default boolean postIf(String id, String activeGateToken, Instant postedAt)
    {
        throw new UnsupportedOperationException("postIf(token)");
    }

    /** Human gate revision before any effect has been claimed. */
    default boolean requestGateChangesIf(String id, int additionalBudget)
    {
        throw new UnsupportedOperationException("requestGateChangesIf");
    }

    /** Revoke stale gate evidence after a pre-effect fingerprint mismatch. */
    default boolean invalidateGateFingerprintIf(
            String id, String activeToken)
    {
        throw new UnsupportedOperationException("invalidateGateFingerprintIf");
    }

    /** Adopt a green gate-revalidation fingerprint without skipping the
     * Brain verification still owed by TRIAGING. */
    default boolean acceptGateValidationIf(
            String id, int expectedKickAttempt, String codeFingerprint)
    {
        throw new UnsupportedOperationException("acceptGateValidationIf");
    }

    /** Guarded verdict write for the currently-owned Brain attempt. */
    default boolean updateBrainVerdictIf(
            String id, ReviewRoundState expected, String verdict)
    {
        throw new UnsupportedOperationException("updateBrainVerdictIf");
    }

    /** A failed/cancelled owned turn advances the durable kick identity and
     *  delivery counter together. */
    default boolean recordDeliveryFailureIf(
            String id, ReviewRoundState expected, int expectedKickAttempt)
    {
        throw new UnsupportedOperationException("recordDeliveryFailureIf");
    }

    /** A successfully admitted/reloaded current kick clears only its failure count. */
    default boolean clearEnqueueFailuresIf(
            String id, ReviewRoundState expected, int expectedKickAttempt)
    {
        throw new UnsupportedOperationException("clearEnqueueFailuresIf");
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

    /** Targeted gate-timestamp write; never touches status. */
    default void updateGateTimes(String id, Instant gatedAt, Instant postedAt)
    {
        throw new UnsupportedOperationException("updateGateTimes");
    }

    Optional<ReviewRound> findById(String id);

    /** A task's rounds, newest-first. */
    List<ReviewRound> findByTask(String taskId);

    /** The task's currently live, driveable round, if any. PAUSED is a
     * durable coordinator checkpoint but is intentionally not live. */
    Optional<ReviewRound> findLiveByTask(String taskId);

    /** Every currently live, driveable round across all tasks — the backstop sweep's
     *  input (see {@code BrainReviewServiceImpl.reconcileStalledRounds}).
     *  Small table; an unfiltered scan is fine at this scale. */
    List<ReviewRound> findAllLive();

    /** Next 1-based round index for a task (highest existing + 1, or 1). */
    int nextIndex(String taskId);

    /** Exact durable attempt identity used by compound lifecycle writes. */
    record AttemptFence(
            int iteration,
            int gateRevision,
            int kickAttempt,
            String turnId,
            String kickKey)
    {
    }
}
