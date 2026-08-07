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

    /** Park one live state while preserving the exact state Resume must
     *  restore. */
    boolean parkIf(String id, ReviewRoundState expected);

    /** Restore a parked round, clear its checkpoint, advance its durable
     *  kick identity, and clear a TRIAGING attempt's stale verdict. */
    boolean resumeIf(String id, ReviewRoundState pausedFrom);

    /** Seal any expected non-terminal state and stamp its close time. */
    boolean sealIf(String id, ReviewRoundState expected, Instant closedAt);

    /** Persist the complete result of one Brain pass without exposing a
     *  load-set-save lifecycle write to callers. */
    boolean concludeIf(
            String id,
            ReviewRoundState expected,
            ReviewRoundState to,
            AttemptFence attempt,
            ReviewRound.ReviewRoundStats stats,
            String verdict,
            Instant gatedAt,
            Instant closedAt);

    /** Adopt the exact green post-fix fingerprint and enter the next Brain
     *  pass. The iteration bump and delivery reset are one guarded write. */
    boolean finishAddressingIf(
            String id,
            AttemptFence attempt,
            String validationClaimKey,
            String codeFingerprint);

    /** Arm the exact human-reviewed gate revision/fingerprint once. */
    boolean authorizeGateIf(
            String id,
            int expectedGateRevision,
            String codeFingerprint,
            String activeGateToken);

    /** Token-fenced final posting edge. */
    boolean postIf(String id, String activeGateToken, Instant postedAt);

    /** Human gate revision before any effect has been claimed. */
    boolean requestGateChangesIf(String id, int additionalBudget);

    /** Revoke stale gate evidence after a pre-effect fingerprint mismatch. */
    boolean invalidateGateFingerprintIf(
            String id, String activeToken);

    /** Adopt a green gate-revalidation fingerprint without skipping the
     * Brain verification still owed by TRIAGING. */
    boolean acceptGateValidationIf(
            String id, int expectedKickAttempt, String codeFingerprint);

    /** Guarded verdict write for the currently-owned Brain attempt. */
    boolean updateBrainVerdictIf(
            String id, ReviewRoundState expected, String verdict);

    /** A failed/cancelled owned turn advances the durable kick identity and
     *  delivery counter together. */
    boolean recordDeliveryFailureIf(
            String id, ReviewRoundState expected, int expectedKickAttempt);

    /** A successfully admitted/reloaded current kick clears only its failure count. */
    boolean clearEnqueueFailuresIf(
            String id, ReviewRoundState expected, int expectedKickAttempt);

    /** Targeted stats write; never touches status. */
    void updateStats(String id, ReviewRound.ReviewRoundStats stats);

    /** Targeted run-linkage write; never touches status. */
    void updateRunId(String id, String runId);

    /** Targeted gate-timestamp write; never touches status. */
    void updateGateTimes(String id, Instant gatedAt, Instant postedAt);

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
