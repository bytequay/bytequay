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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.domain.RoundGateEffect;
import com.bytequay.app.repository.RoundGateStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
class SqliteRoundGateStore
        implements RoundGateStore
{
    private final RoundGateAuthorizationJpaRepository authorizations;
    private final RoundGateEffectJpaRepository effects;

    SqliteRoundGateStore(
            RoundGateAuthorizationJpaRepository authorizations,
            RoundGateEffectJpaRepository effects)
    {
        this.authorizations = requireNonNull(authorizations, "authorizations is null");
        this.effects = requireNonNull(effects, "effects is null");
    }

    @Override
    @Transactional
    public void insert(
            RoundGateAuthorization authorization, List<String> effectKeys, int attemptLimit)
    {
        if (attemptLimit < 1 || effectKeys.isEmpty()) {
            throw new IllegalArgumentException("round-gate effects and a positive attempt limit are required");
        }
        RoundGateAuthorizationEntity entity = new RoundGateAuthorizationEntity();
        entity.setToken(authorization.token());
        entity.setTaskId(authorization.taskId());
        entity.setRoundId(authorization.roundId());
        entity.setGateRevision(authorization.gateRevision());
        entity.setAttempt(authorization.attempt());
        entity.setActor(authorization.actor().name());
        entity.setCodeFingerprint(authorization.codeFingerprint());
        entity.setPayloadJson(authorization.payloadJson());
        entity.setPayloadDigest(authorization.payloadDigest());
        entity.setEffectKeysJson(authorization.effectKeysJson());
        entity.setApprovedAtMs(authorization.approvedAt().toEpochMilli());
        entity.setRevokedAtMs(epoch(authorization.revokedAt()));
        entity.setConsumedAtMs(epoch(authorization.consumedAt()));
        entity.setOutcome(authorization.outcome());
        authorizations.saveAndFlush(entity);

        for (String key : effectKeys) {
            RoundGateEffectEntity effect = new RoundGateEffectEntity();
            effect.setToken(authorization.token());
            effect.setEffectKey(key);
            effect.setStatus(RoundGateEffect.Status.PENDING.name());
            effect.setAttempts(0);
            effect.setAttemptLimit(attemptLimit);
            effects.save(effect);
        }
        effects.flush();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoundGateAuthorization> findAuthorization(String token)
    {
        return authorizations.findById(token).map(SqliteRoundGateStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoundGateAuthorization> findActiveByRound(String roundId)
    {
        return authorizations
                .findFirstByRoundIdAndRevokedAtMsIsNullAndConsumedAtMsIsNull(roundId)
                .map(SqliteRoundGateStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoundGateAuthorization> findActiveByTask(String taskId)
    {
        return authorizations
                .findFirstByTaskIdAndRevokedAtMsIsNullAndConsumedAtMsIsNull(taskId)
                .map(SqliteRoundGateStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoundGateAuthorization> findRecoverable(Instant now, int limit)
    {
        return authorizations.findRecoverable(
                        now.toEpochMilli(), PageRequest.of(0, limit))
                .stream()
                .map(SqliteRoundGateStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoundGateEffect> findEffects(String token)
    {
        return effects.findByTokenOrderByIdAsc(token).stream()
                .map(SqliteRoundGateStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoundGateEffect> findEffect(String token, String effectKey)
    {
        return effects.findByTokenAndEffectKey(token, effectKey)
                .map(SqliteRoundGateStore::toDomain);
    }

    @Override
    @Transactional
    public boolean claimEffect(
            String token, String effectKey, String owner, Instant now, Instant leaseUntil)
    {
        return effects.claim(
                token, effectKey, owner, now.toEpochMilli(), leaseUntil.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean completeEffect(
            String token, String effectKey, String owner, String evidenceJson, Instant completedAt)
    {
        return effects.complete(
                token, effectKey, owner, evidenceJson, completedAt.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean completeProbedEffect(
            String token, String effectKey, String evidenceJson, Instant completedAt)
    {
        return effects.completeProbed(
                token, effectKey, evidenceJson, completedAt.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean failEffect(
            String token,
            String effectKey,
            String owner,
            RoundGateEffect.Status status,
            String errorClass,
            String error,
            Instant nextAttemptAt)
    {
        if (status != RoundGateEffect.Status.RETRYABLE_FAILED
                && status != RoundGateEffect.Status.PERMANENT_FAILED) {
            throw new IllegalArgumentException("not a failed effect status: " + status);
        }
        return effects.fail(
                token, effectKey, owner, status.name(), errorClass, error,
                epoch(nextAttemptAt)) == 1;
    }

    @Override
    @Transactional
    public boolean markExhausted(
            String token, String effectKey, String errorClass, String error)
    {
        return effects.markExhausted(token, effectKey, errorClass, error) == 1;
    }

    @Override
    @Transactional
    public boolean rearmEffect(
            String token, String effectKey, int addedAllowance, Instant retryAt)
    {
        if (addedAllowance < 1) {
            throw new IllegalArgumentException("added allowance must be positive");
        }
        return effects.rearm(
                token, effectKey, addedAllowance, retryAt.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean revokeIfUnclaimed(String token, String outcome, Instant revokedAt)
    {
        return authorizations.revokeIfUnclaimed(token, outcome, revokedAt.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean bumpGateRevision(
            String taskId, String roundId, int expectedRevision, String activeToken)
    {
        return authorizations.bumpGateRevision(
                taskId, roundId, expectedRevision, activeToken) == 1;
    }

    @Override
    @Transactional
    public boolean sealActive(String taskId, String outcome, Instant revokedAt)
    {
        return authorizations.sealActive(taskId, outcome, revokedAt.toEpochMilli()) == 1;
    }

    @Override
    @Transactional
    public boolean consumeIfComplete(String token, String outcome, Instant consumedAt)
    {
        return authorizations.consumeIfComplete(token, outcome, consumedAt.toEpochMilli()) == 1;
    }

    private static RoundGateAuthorization toDomain(RoundGateAuthorizationEntity e)
    {
        return new RoundGateAuthorization(
                e.getToken(), e.getTaskId(), e.getRoundId(), e.getGateRevision(), e.getAttempt(),
                Actor.valueOf(e.getActor()), e.getCodeFingerprint(), e.getPayloadJson(),
                e.getPayloadDigest(), e.getEffectKeysJson(), Instant.ofEpochMilli(e.getApprovedAtMs()),
                instant(e.getRevokedAtMs()), instant(e.getConsumedAtMs()), e.getOutcome());
    }

    private static RoundGateEffect toDomain(RoundGateEffectEntity e)
    {
        return new RoundGateEffect(
                e.getId(), e.getToken(), e.getEffectKey(),
                RoundGateEffect.Status.valueOf(e.getStatus()), e.getAttempts(), e.getAttemptLimit(),
                instant(e.getFirstClaimedAtMs()), instant(e.getLastClaimedAtMs()), e.getClaimOwner(),
                instant(e.getLeaseUntilMs()), e.getLastErrorClass(), e.getLastError(),
                instant(e.getNextAttemptAtMs()), e.getEvidenceJson(), instant(e.getCompletedAtMs()));
    }

    private static Long epoch(Instant value)
    {
        return value == null ? null : value.toEpochMilli();
    }

    private static Instant instant(Long value)
    {
        return value == null ? null : Instant.ofEpochMilli(value);
    }
}
