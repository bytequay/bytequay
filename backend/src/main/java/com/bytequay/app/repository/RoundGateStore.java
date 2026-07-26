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

import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.domain.RoundGateEffect;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for the durable external-review posting saga. */
public interface RoundGateStore
{
    void insert(RoundGateAuthorization authorization, List<String> effectKeys, int attemptLimit);

    Optional<RoundGateAuthorization> findAuthorization(String token);

    Optional<RoundGateAuthorization> findActiveByRound(String roundId);

    Optional<RoundGateAuthorization> findActiveByTask(String taskId);

    /** Runnable tokens whose first incomplete cursor is due (or whose effects
     * are all complete and only need finalization). */
    List<RoundGateAuthorization> findRecoverable(Instant now, int limit);

    List<RoundGateEffect> findEffects(String token);

    Optional<RoundGateEffect> findEffect(String token, String effectKey);

    boolean claimEffect(
            String token, String effectKey, String owner, Instant now, Instant leaseUntil);

    boolean completeEffect(
            String token, String effectKey, String owner, String evidenceJson, Instant completedAt);

    /** Record an effect found remotely after an ambiguous prior attempt. */
    boolean completeProbedEffect(
            String token, String effectKey, String evidenceJson, Instant completedAt);

    boolean failEffect(
            String token,
            String effectKey,
            String owner,
            RoundGateEffect.Status status,
            String errorClass,
            String error,
            Instant nextAttemptAt);

    boolean markExhausted(
            String token, String effectKey, String errorClass, String error);

    boolean rearmEffect(
            String token, String effectKey, int addedAllowance, Instant retryAt);

    boolean revokeIfUnclaimed(String token, String outcome, Instant revokedAt);

    /** Advance the human-reviewed payload revision without changing round state. */
    boolean bumpGateRevision(
            String taskId, String roundId, int expectedRevision, String activeToken);

    boolean sealActive(String taskId, String outcome, Instant revokedAt);

    boolean consumeIfComplete(String token, String outcome, Instant consumedAt);
}
