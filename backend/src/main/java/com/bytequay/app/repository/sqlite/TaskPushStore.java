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
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskPushEffect;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class TaskPushStore
{
    private final TaskPushAuthorizationJpaRepository authorizations;
    private final TaskPushEffectJpaRepository effects;

    TaskPushStore(
            TaskPushAuthorizationJpaRepository authorizations,
            TaskPushEffectJpaRepository effects)
    {
        this.authorizations = requireNonNull(authorizations, "authorizations is null");
        this.effects = requireNonNull(effects, "effects is null");
    }

    @Transactional
    /** Insert the immutable authorization and all PENDING effects together. */
    public void insert(
            TaskPushAuthorization authorization, List<String> effectKeys, int attemptLimit)
    {
        if (attemptLimit < 1 || effectKeys.isEmpty()) {
            throw new IllegalArgumentException("push effects and a positive attempt limit are required");
        }
        TaskPushAuthorizationEntity entity = new TaskPushAuthorizationEntity();
        entity.setToken(authorization.token());
        entity.setTaskId(authorization.taskId());
        entity.setPrId(authorization.prId());
        entity.setRunId(authorization.runId());
        entity.setHeadSha(authorization.headSha());
        entity.setCodeFingerprint(authorization.codeFingerprint());
        entity.setActor(authorization.actor().name());
        entity.setBasisKind(authorization.basisKind());
        entity.setBasisId(authorization.basisId());
        entity.setOverrideReason(authorization.overrideReason());
        entity.setPayloadJson(authorization.payloadJson());
        entity.setPayloadDigest(authorization.payloadDigest());
        entity.setEffectKeysJson(authorization.effectKeysJson());
        entity.setCreatedAtMs(authorization.createdAt().toEpochMilli());
        entity.setRevokedAtMs(epoch(authorization.revokedAt()));
        entity.setConsumedAtMs(epoch(authorization.consumedAt()));
        entity.setOutcome(authorization.outcome());
        authorizations.saveAndFlush(entity);

        for (String key : effectKeys) {
            TaskPushEffectEntity effect = new TaskPushEffectEntity();
            effect.setToken(authorization.token());
            effect.setEffectKey(key);
            effect.setStatus(TaskPushEffect.Status.PENDING.name());
            effect.setAttempts(0);
            effect.setAttemptLimit(attemptLimit);
            effects.save(effect);
        }
        effects.flush();
    }

    @Transactional(readOnly = true)
    public Optional<TaskPushAuthorization> findAuthorization(String token)
    {
        return authorizations.findById(token).map(TaskPushStore::toDomain);
    }

    @Transactional(readOnly = true)
    public Optional<TaskPushAuthorization> findActiveByTask(String taskId)
    {
        return authorizations
                .findFirstByTaskIdAndRevokedAtMsIsNullAndConsumedAtMsIsNull(taskId)
                .map(TaskPushStore::toDomain);
    }

    @Transactional(readOnly = true)
    /** Runnable tokens whose first incomplete cursor is due (or whose effects
    * are all complete and only need finalization). Parked/backoff tokens must
    * not starve newer crash recovery behind the bounded sweep. */
    public List<TaskPushAuthorization> findRecoverable(Instant now, int limit)
    {
        return authorizations.findRecoverable(
                        now.toEpochMilli(), PageRequest.of(0, limit))
                .stream()
                .map(TaskPushStore::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    /** Awaiting-push tasks whose task-origin PR already has a remote identity,
    * but which have no active saga authorization. The persistence query must
    * apply those predicates before its limit so unrelated tasks cannot starve
    * pre-authorization crash recovery. */
    public List<String> findOrphanedRemotePullRequestTaskIds(int limit)
    {
        return authorizations.findOrphanedRemotePullRequestTaskIds(
                PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<TaskPushEffect> findEffects(String token)
    {
        return effects.findByTokenOrderByIdAsc(token).stream()
                .map(TaskPushStore::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<TaskPushEffect> findEffect(String token, String effectKey)
    {
        return effects.findByTokenAndEffectKey(token, effectKey)
                .map(TaskPushStore::toDomain);
    }

    @Transactional
    /** Claim a due effect and atomically spend one attempt. */
    public boolean claimEffect(
            String token, String effectKey, String owner, Instant now, Instant leaseUntil)
    {
        return effects.claim(
                token, effectKey, owner, now.toEpochMilli(), leaseUntil.toEpochMilli()) == 1;
    }

    @Transactional
    public boolean completeEffect(
            String token, String effectKey, String owner, String evidenceJson, Instant completedAt)
    {
        return effects.complete(
                token, effectKey, owner, evidenceJson, completedAt.toEpochMilli()) == 1;
    }

    @Transactional
    /** Stamp an exact remote fact observed outside this saga (for example a
    * matching remote-open event). The effect is marked ever-attempted so a
    * partially externalized authorization can no longer be revoked. */
    public boolean completeObservedEffect(
            String token, String effectKey, String evidenceJson, Instant completedAt)
    {
        return effects.completeObserved(
                token, effectKey, evidenceJson, completedAt.toEpochMilli()) == 1;
    }

    @Transactional
    public boolean failEffect(
            String token,
            String effectKey,
            String owner,
            TaskPushEffect.Status status,
            String errorClass,
            String error,
            Instant nextAttemptAt)
    {
        if (status != TaskPushEffect.Status.RETRYABLE_FAILED
                && status != TaskPushEffect.Status.PERMANENT_FAILED) {
            throw new IllegalArgumentException("not a failed effect status: " + status);
        }
        return effects.fail(
                token, effectKey, owner, status.name(), errorClass, error,
                epoch(nextAttemptAt)) == 1;
    }

    @Transactional
    /** Re-arm exactly one parked permanent/bound-exhausted cursor. The new
    * limit is the attempts already spent plus the positive allowance. */
    public boolean rearmEffect(
            String token, String effectKey, int addedAllowance, Instant retryAt)
    {
        if (addedAllowance < 1) {
            throw new IllegalArgumentException("added allowance must be positive");
        }
        return effects.rearm(
                token, effectKey, addedAllowance, retryAt.toEpochMilli()) == 1;
    }

    @Transactional
    /** Revoke only a token whose effects have never been claimed. */
    public boolean revokeIfUnclaimed(String token, String outcome, Instant revokedAt)
    {
        return authorizations.revokeIfUnclaimed(token, outcome, revokedAt.toEpochMilli()) == 1;
    }

    @Transactional
    /** Terminal task sealing revokes an active cursor regardless of attempts. */
    public boolean sealActive(String taskId, String outcome, Instant revokedAt)
    {
        return authorizations.sealActive(taskId, outcome, revokedAt.toEpochMilli()) == 1;
    }

    @Transactional
    /** Consume only after every effect is durably complete. */
    public boolean consumeIfComplete(String token, String outcome, Instant consumedAt)
    {
        return authorizations.consumeIfComplete(token, outcome, consumedAt.toEpochMilli()) == 1;
    }

    private static TaskPushAuthorization toDomain(TaskPushAuthorizationEntity e)
    {
        return new TaskPushAuthorization(
                e.getToken(), e.getTaskId(), e.getPrId(), e.getRunId(), e.getHeadSha(),
                e.getCodeFingerprint(), Actor.valueOf(e.getActor()), e.getBasisKind(),
                e.getBasisId(), e.getOverrideReason(), e.getPayloadJson(), e.getPayloadDigest(),
                e.getEffectKeysJson(), Instant.ofEpochMilli(e.getCreatedAtMs()), instant(e.getRevokedAtMs()),
                instant(e.getConsumedAtMs()), e.getOutcome());
    }

    private static TaskPushEffect toDomain(TaskPushEffectEntity e)
    {
        return new TaskPushEffect(
                e.getId(), e.getToken(), e.getEffectKey(),
                TaskPushEffect.Status.valueOf(e.getStatus()), e.getAttempts(), e.getAttemptLimit(),
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
