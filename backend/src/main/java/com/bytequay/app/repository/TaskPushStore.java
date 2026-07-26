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

import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.domain.TaskPushEffect;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for the durable first-push authorization saga. */
public interface TaskPushStore
{
    /** Insert the immutable authorization and all PENDING effects together. */
    void insert(TaskPushAuthorization authorization, List<String> effectKeys, int attemptLimit);

    Optional<TaskPushAuthorization> findAuthorization(String token);

    Optional<TaskPushAuthorization> findActiveByTask(String taskId);

    /** Runnable tokens whose first incomplete cursor is due (or whose effects
     * are all complete and only need finalization). Parked/backoff tokens must
     * not starve newer crash recovery behind the bounded sweep. */
    List<TaskPushAuthorization> findRecoverable(Instant now, int limit);

    /** Awaiting-push tasks whose task-origin PR already has a remote identity,
     * but which have no active saga authorization. The persistence query must
     * apply those predicates before its limit so unrelated tasks cannot starve
     * pre-authorization crash recovery. */
    List<String> findOrphanedRemotePullRequestTaskIds(int limit);

    List<TaskPushEffect> findEffects(String token);

    Optional<TaskPushEffect> findEffect(String token, String effectKey);

    /** Claim a due effect and atomically spend one attempt. */
    boolean claimEffect(
            String token, String effectKey, String owner, Instant now, Instant leaseUntil);

    boolean completeEffect(
            String token, String effectKey, String owner, String evidenceJson, Instant completedAt);

    /** Stamp an exact remote fact observed outside this saga (for example a
     * matching remote-open event). The effect is marked ever-attempted so a
     * partially externalized authorization can no longer be revoked. */
    boolean completeObservedEffect(
            String token, String effectKey, String evidenceJson, Instant completedAt);

    boolean failEffect(
            String token,
            String effectKey,
            String owner,
            TaskPushEffect.Status status,
            String errorClass,
            String error,
            Instant nextAttemptAt);

    /** Re-arm exactly one parked permanent/bound-exhausted cursor. The new
     * limit is the attempts already spent plus the positive allowance. */
    boolean rearmEffect(
            String token, String effectKey, int addedAllowance, Instant retryAt);

    /** Revoke only a token whose effects have never been claimed. */
    boolean revokeIfUnclaimed(String token, String outcome, Instant revokedAt);

    /** Terminal task sealing revokes an active cursor regardless of attempts. */
    boolean sealActive(String taskId, String outcome, Instant revokedAt);

    /** Consume only after every effect is durably complete. */
    boolean consumeIfComplete(String token, String outcome, Instant consumedAt);
}
