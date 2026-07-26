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

interface TaskPushAuthorizationJpaRepository
        extends JpaRepository<TaskPushAuthorizationEntity, String>
{
    Optional<TaskPushAuthorizationEntity>
            findFirstByTaskIdAndRevokedAtMsIsNullAndConsumedAtMsIsNull(String taskId);

    @Query(value = """
            SELECT authorization.*
            FROM task_push_authorization authorization
            JOIN tasks task ON task.id = authorization.task_id
            WHERE authorization.revoked_at_ms IS NULL
              AND authorization.consumed_at_ms IS NULL
              AND task.phase = 'AWAITING_PUSH'
              AND task.status IN ('IDLE', 'AWAITING_REVIEW')
              AND (
                    NOT EXISTS (
                        SELECT 1 FROM task_push_effect effect
                        WHERE effect.token = authorization.token
                          AND effect.status <> 'COMPLETED'
                    )
                 OR EXISTS (
                        SELECT 1
                        FROM task_push_effect cursor
                        WHERE cursor.token = authorization.token
                          AND cursor.id = (
                              SELECT MIN(first_effect.id)
                              FROM task_push_effect first_effect
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
            ORDER BY authorization.created_at_ms ASC
            """, nativeQuery = true)
    List<TaskPushAuthorizationEntity> findRecoverable(
            @Param("now") long now, Pageable pageable);

    @Query(value = """
            SELECT task.id
            FROM tasks task
            JOIN pr ON pr.task_id = task.id
            WHERE task.phase = 'AWAITING_PUSH'
              AND task.status IN ('IDLE', 'AWAITING_REVIEW')
              AND pr.origin = 'task'
              AND pr.status IN ('remote-drafted', 'remote-open')
              AND pr.repo IS NOT NULL
              AND TRIM(pr.repo) <> ''
              AND pr.remote_pr_number IS NOT NULL
              AND pr.remote_pr_url IS NOT NULL
              AND TRIM(pr.remote_pr_url) <> ''
              AND NOT EXISTS (
                    SELECT 1
                    FROM task_push_authorization authorization
                    WHERE authorization.task_id = task.id
                      AND authorization.revoked_at_ms IS NULL
                      AND authorization.consumed_at_ms IS NULL
              )
            ORDER BY task.created_at_ms ASC, task.id ASC
            """, nativeQuery = true)
    List<String> findOrphanedRemotePullRequestTaskIds(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskPushAuthorizationEntity a SET a.revokedAtMs = :atMs, "
            + "a.outcome = :outcome WHERE a.token = :token "
            + "AND a.revokedAtMs IS NULL AND a.consumedAtMs IS NULL "
            + "AND NOT EXISTS (SELECT e.id FROM TaskPushEffectEntity e "
            + "WHERE e.token = :token AND e.attempts > 0)")
    int revokeIfUnclaimed(
            @Param("token") String token,
            @Param("outcome") String outcome,
            @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskPushAuthorizationEntity a SET a.revokedAtMs = :atMs, "
            + "a.outcome = :outcome WHERE a.taskId = :taskId "
            + "AND a.revokedAtMs IS NULL AND a.consumedAtMs IS NULL")
    int sealActive(
            @Param("taskId") String taskId,
            @Param("outcome") String outcome,
            @Param("atMs") long atMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TaskPushAuthorizationEntity a SET a.consumedAtMs = :atMs, "
            + "a.outcome = :outcome WHERE a.token = :token "
            + "AND a.revokedAtMs IS NULL AND a.consumedAtMs IS NULL "
            + "AND NOT EXISTS (SELECT e.id FROM TaskPushEffectEntity e "
            + "WHERE e.token = :token AND e.status <> 'COMPLETED')")
    int consumeIfComplete(
            @Param("token") String token,
            @Param("outcome") String outcome,
            @Param("atMs") long atMs);
}
