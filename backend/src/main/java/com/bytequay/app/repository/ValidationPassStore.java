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

import com.bytequay.app.domain.ValidationClaim;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@code validation_pass} audit log. */
public interface ValidationPassStore
{
    /** Open a new run row; returns its generated id. */
    long startPass(String taskId, Instant startedAt);

    /** Close the run row with its outcome. */
    void finishPass(long id, Instant endedAt, boolean passed, int fixRounds, String failuresJson);

    /** Insert a STARTED claim row. Empty on a claim-key conflict — the
     *  caller reloads via {@link #findByClaimKey} (idempotent claims). */
    default Optional<Long> insertClaim(
            String claimKey, String taskId, String context, String roundId,
            String codeFingerprint, Long throughSequence, String rootSetDigest, Instant startedAt)
    {
        throw new UnsupportedOperationException("insertClaim");
    }

    default Optional<ValidationClaim> findByClaimKey(String claimKey)
    {
        throw new UnsupportedOperationException("findByClaimKey");
    }

    /** Newest accepted green claim for a task/context. Used only to bind a
     *  round opening to the validation evidence that admitted its phase. */
    default Optional<ValidationClaim> findLatestGreenByTaskAndContext(
            String taskId, String context)
    {
        return Optional.empty();
    }

    /** Bind the validation evidence that opened a Brain round. The claim is
     * immutable after first binding, so retries can prove exact source
     * identity instead of treating an equal code fingerprint as the same
     * handoff. */
    default boolean bindRoundIfUnbound(String claimKey, String roundId)
    {
        throw new UnsupportedOperationException("bindRoundIfUnbound");
    }

    /** Latest validation evidence for one round-owned context. */
    default Optional<ValidationClaim> findLatestByRoundAndContext(
            String roundId, String context)
    {
        return Optional.empty();
    }

    /** CAS ownership: succeeds only while the claim is live (not ended /
     *  cancelled / superseded) and unowned or lease-expired. */
    default boolean acquireOwner(
            String claimKey, String ownerId, String executorIdentity, Instant leaseUntil, Instant now)
    {
        throw new UnsupportedOperationException("acquireOwner");
    }

    /** Renew the owner's lease; false when ownership was lost. */
    default boolean renewLease(String claimKey, String ownerId, Instant leaseUntil, Instant heartbeatAt)
    {
        throw new UnsupportedOperationException("renewLease");
    }

    /** Terminal completion CAS: only the current owner of the exact
     *  fingerprint may finish the pass, exactly once. */
    default boolean completeOwned(
            String claimKey, String ownerId, String codeFingerprint,
            Instant endedAt, boolean passed, String failuresJson)
    {
        throw new UnsupportedOperationException("completeOwned");
    }

    /** Durable cancellation request; the executor and the cancellation
     *  reconciler act on it. False when the claim is already terminal. */
    default boolean requestCancel(String claimKey, Instant requestedAt, Instant deadline)
    {
        throw new UnsupportedOperationException("requestCancel");
    }

    /** Mark a live claim superseded by a newer submission/fingerprint. */
    default boolean markSuperseded(String claimKey, Instant at)
    {
        throw new UnsupportedOperationException("markSuperseded");
    }

    /** STARTED claims with no live lease and no cancel/supersede mark —
     *  the startup/sweep resume set. */
    default List<ValidationClaim> findResumableStarted(Instant now)
    {
        throw new UnsupportedOperationException("findResumableStarted");
    }

    /** Every open (non-terminal, non-superseded) claim owned by one task,
     *  including cancellation-pending rows — the stop barrier's
     *  validation-liveness input. */
    default List<ValidationClaim> findOpenByTask(String taskId)
    {
        throw new UnsupportedOperationException("findOpenByTask");
    }

    /** Open claims with a live cancellation request — the cancellation
     *  reconciler's work set, carrying the identity its absence proof
     *  needs. */
    default List<PendingValidationCancel> findCancelPending()
    {
        throw new UnsupportedOperationException("findCancelPending");
    }

    /** Bump the durable interruption-attempt counter on one claim. */
    default void incrementCancelAttempts(String claimKey)
    {
        throw new UnsupportedOperationException("incrementCancelAttempts");
    }

    /** A cancellation-pending claim as the reconciler sees it. */
    record PendingValidationCancel(
            String claimKey,
            String taskId,
            String executorIdentity,
            Instant leaseUntil,
            Instant deadline)
    {
    }
}
