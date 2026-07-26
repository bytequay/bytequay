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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface RoundGateAuthorizationJpaRepository
        extends JpaRepository<RoundGateAuthorizationEntity, String>
{
    Optional<RoundGateAuthorizationEntity>
            findFirstByRoundIdAndRevokedAtMsIsNullAndConsumedAtMsIsNull(String roundId);

    Optional<RoundGateAuthorizationEntity>
            findFirstByTaskIdAndRevokedAtMsIsNullAndConsumedAtMsIsNull(String taskId);

    @Query(value = """
            SELECT authorization.*
            FROM round_gate_authorization authorization
            JOIN response_round round ON round.id = authorization.round_id
            JOIN agent_run run ON run.id = round.run_id
            JOIN tasks task ON task.id = authorization.task_id
            WHERE authorization.revoked_at_ms IS NULL
              AND authorization.consumed_at_ms IS NULL
              AND round.active_gate_token = authorization.token
              AND round.gate_revision = authorization.gate_revision
              AND round.code_fingerprint = authorization.code_fingerprint
              AND round.status = 'awaiting_gate'
              AND run.status = 'awaiting_gate'
              AND task.phase = 'AWAITING_REMOTE_REVIEW'
              AND task.status = 'IN_REVIEW'
              AND (
                    NOT EXISTS (
                        SELECT 1 FROM round_gate_effect effect
                        WHERE effect.token = authorization.token
                          AND effect.status <> 'COMPLETED'
                    )
                 OR EXISTS (
                        SELECT 1
                        FROM round_gate_effect cursor
                        WHERE cursor.token = authorization.token
                          AND cursor.id = (
                              SELECT MIN(first_effect.id)
                              FROM round_gate_effect first_effect
                              WHERE first_effect.token = authorization.token
                                AND first_effect.status <> 'COMPLETED'
                          )
                          AND (
                                cursor.status = 'PENDING'
                             OR (cursor.status = 'RETRYABLE_FAILED'
                                 AND (cursor.attempts >= cursor.attempt_limit
                                      OR cursor.next_attempt_at_ms IS NULL
                                      OR cursor.next_attempt_at_ms <= :now))
                             OR (cursor.status = 'IN_FLIGHT'
                                 AND cursor.lease_until_ms IS NOT NULL
                                 AND cursor.lease_until_ms <= :now)
                          )
                    )
              )
            ORDER BY authorization.approved_at_ms ASC
            """, nativeQuery = true)
    List<RoundGateAuthorizationEntity> findRecoverable(
            @Param("now") long now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoundGateAuthorizationEntity a SET a.revokedAtMs = :atMs, "
            + "a.outcome = :outcome WHERE a.token = :token "
            + "AND a.revokedAtMs IS NULL AND a.consumedAtMs IS NULL "
            + "AND NOT EXISTS (SELECT e.id FROM RoundGateEffectEntity e "
            + "WHERE e.token = :token AND e.attempts > 0)")
    int revokeIfUnclaimed(
            @Param("token") String token,
            @Param("outcome") String outcome,
            @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE response_round
            SET gate_revision = gate_revision + 1,
                active_gate_token = NULL
            WHERE id = :roundId
              AND task_id = :taskId
              AND gate_revision = :expectedRevision
              AND status IN ('awaiting_gate', 'addressing')
              AND (
                    (:activeToken IS NULL AND active_gate_token IS NULL)
                 OR active_gate_token = :activeToken
              )
            """, nativeQuery = true)
    int bumpGateRevision(
            @Param("taskId") String taskId,
            @Param("roundId") String roundId,
            @Param("expectedRevision") int expectedRevision,
            @Param("activeToken") String activeToken);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoundGateAuthorizationEntity a SET a.revokedAtMs = :atMs, "
            + "a.outcome = :outcome WHERE a.taskId = :taskId "
            + "AND a.revokedAtMs IS NULL AND a.consumedAtMs IS NULL")
    int sealActive(
            @Param("taskId") String taskId,
            @Param("outcome") String outcome,
            @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoundGateAuthorizationEntity a SET a.consumedAtMs = :atMs, "
            + "a.outcome = :outcome WHERE a.token = :token "
            + "AND a.revokedAtMs IS NULL AND a.consumedAtMs IS NULL "
            + "AND NOT EXISTS (SELECT e.id FROM RoundGateEffectEntity e "
            + "WHERE e.token = :token AND e.status <> 'COMPLETED')")
    int consumeIfComplete(
            @Param("token") String token,
            @Param("outcome") String outcome,
            @Param("atMs") long atMs);
}
