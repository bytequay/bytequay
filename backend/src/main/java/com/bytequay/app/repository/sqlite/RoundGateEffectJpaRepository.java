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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface RoundGateEffectJpaRepository
        extends JpaRepository<RoundGateEffectEntity, Long>
{
    List<RoundGateEffectEntity> findByTokenOrderByIdAsc(String token);

    Optional<RoundGateEffectEntity> findByTokenAndEffectKey(String token, String effectKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE round_gate_effect
            SET status = 'IN_FLIGHT',
                attempts = attempts + 1,
                first_claimed_at_ms = COALESCE(first_claimed_at_ms, :now),
                last_claimed_at_ms = :now,
                claim_owner = :owner,
                lease_until_ms = :leaseUntil,
                next_attempt_at_ms = NULL
            WHERE token = :token
              AND effect_key = :effectKey
              AND attempts < attempt_limit
              AND (
                    status = 'PENDING'
                 OR (status = 'RETRYABLE_FAILED'
                     AND (next_attempt_at_ms IS NULL OR next_attempt_at_ms <= :now))
                 OR (status = 'IN_FLIGHT' AND lease_until_ms < :now)
              )
              AND EXISTS (
                  SELECT 1
                  FROM round_gate_authorization authorization
                  JOIN response_round round ON round.id = authorization.round_id
                  JOIN agent_run run ON run.id = round.run_id
                  JOIN tasks task ON task.id = authorization.task_id
                  WHERE authorization.token = :token
                    AND authorization.revoked_at_ms IS NULL
                    AND authorization.consumed_at_ms IS NULL
                    AND round.active_gate_token = :token
                    AND round.gate_revision = authorization.gate_revision
                    AND round.code_fingerprint = authorization.code_fingerprint
                    AND round.status = 'awaiting_gate'
                    AND run.status = 'awaiting_gate'
                    AND task.phase = 'AWAITING_REMOTE_REVIEW'
                    AND task.status = 'IN_REVIEW'
              )
            """, nativeQuery = true)
    int claim(
            @Param("token") String token,
            @Param("effectKey") String effectKey,
            @Param("owner") String owner,
            @Param("now") long now,
            @Param("leaseUntil") long leaseUntil);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoundGateEffectEntity e SET e.status = 'COMPLETED', "
            + "e.evidenceJson = :evidenceJson, e.completedAtMs = :atMs, "
            + "e.claimOwner = null, e.leaseUntilMs = null, "
            + "e.lastErrorClass = null, e.lastError = null, e.nextAttemptAtMs = null "
            + "WHERE e.token = :token AND e.effectKey = :effectKey "
            + "AND e.status = 'IN_FLIGHT' AND e.claimOwner = :owner")
    int complete(
            @Param("token") String token,
            @Param("effectKey") String effectKey,
            @Param("owner") String owner,
            @Param("evidenceJson") String evidenceJson,
            @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE round_gate_effect
            SET status = 'COMPLETED',
                evidence_json = :evidenceJson,
                completed_at_ms = :atMs,
                claim_owner = NULL,
                lease_until_ms = NULL,
                last_error_class = NULL,
                last_error = NULL,
                next_attempt_at_ms = NULL
            WHERE token = :token
              AND effect_key = :effectKey
              AND attempts > 0
              AND (
                    status = 'RETRYABLE_FAILED'
                 OR (status = 'IN_FLIGHT'
                     AND lease_until_ms IS NOT NULL
                     AND lease_until_ms <= :atMs)
              )
              AND EXISTS (
                  SELECT 1
                  FROM round_gate_authorization authorization
                  JOIN response_round round ON round.id = authorization.round_id
                  JOIN agent_run run ON run.id = round.run_id
                  JOIN tasks task ON task.id = authorization.task_id
                  WHERE authorization.token = :token
                    AND authorization.revoked_at_ms IS NULL
                    AND authorization.consumed_at_ms IS NULL
                    AND round.active_gate_token = :token
                    AND round.gate_revision = authorization.gate_revision
                    AND round.code_fingerprint = authorization.code_fingerprint
                    AND round.status = 'awaiting_gate'
                    AND run.status = 'awaiting_gate'
                    AND task.phase = 'AWAITING_REMOTE_REVIEW'
                    AND task.status = 'IN_REVIEW'
              )
            """, nativeQuery = true)
    int completeProbed(
            @Param("token") String token,
            @Param("effectKey") String effectKey,
            @Param("evidenceJson") String evidenceJson,
            @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoundGateEffectEntity e SET e.status = :status, "
            + "e.lastErrorClass = :errorClass, e.lastError = :error, "
            + "e.nextAttemptAtMs = :nextAttemptAtMs, "
            + "e.claimOwner = null, e.leaseUntilMs = null "
            + "WHERE e.token = :token AND e.effectKey = :effectKey "
            + "AND e.status = 'IN_FLIGHT' AND e.claimOwner = :owner")
    int fail(
            @Param("token") String token,
            @Param("effectKey") String effectKey,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("errorClass") String errorClass,
            @Param("error") String error,
            @Param("nextAttemptAtMs") Long nextAttemptAtMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoundGateEffectEntity e SET e.status = 'PERMANENT_FAILED', "
            + "e.lastErrorClass = :errorClass, e.lastError = :error, "
            + "e.nextAttemptAtMs = null, e.claimOwner = null, e.leaseUntilMs = null "
            + "WHERE e.token = :token AND e.effectKey = :effectKey "
            + "AND e.status <> 'COMPLETED' AND e.attempts >= e.attemptLimit")
    int markExhausted(
            @Param("token") String token,
            @Param("effectKey") String effectKey,
            @Param("errorClass") String errorClass,
            @Param("error") String error);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE round_gate_effect
            SET status = 'RETRYABLE_FAILED',
                attempt_limit = attempts + :addedAllowance,
                next_attempt_at_ms = :retryAt,
                claim_owner = NULL,
                lease_until_ms = NULL
            WHERE token = :token
              AND effect_key = :effectKey
              AND (status = 'PERMANENT_FAILED' OR attempts >= attempt_limit)
              AND EXISTS (
                  SELECT 1
                  FROM round_gate_authorization authorization
                  JOIN response_round round ON round.id = authorization.round_id
                  JOIN tasks task ON task.id = authorization.task_id
                  WHERE authorization.token = :token
                    AND authorization.revoked_at_ms IS NULL
                    AND authorization.consumed_at_ms IS NULL
                    AND round.active_gate_token = :token
                    AND round.status = 'paused'
                    AND round.paused_from = 'awaiting_gate'
                    AND task.phase = 'NEEDS_ATTENTION'
                    AND task.status = 'NEEDS_ATTENTION'
              )
            """, nativeQuery = true)
    int rearm(
            @Param("token") String token,
            @Param("effectKey") String effectKey,
            @Param("addedAllowance") int addedAllowance,
            @Param("retryAt") long retryAt);
}
