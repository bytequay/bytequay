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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteLocalReviewBrainHandoffStore
{
    public record Handoff(
            String validationClaimKey,
            String taskId,
            long throughSequence,
            String codeFingerprint,
            int deliveryFailures) {}

    private final JdbcTemplate jdbc;

    SqliteLocalReviewBrainHandoffStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Transactional
    public void insert(
            String validationClaimKey, String taskId, long throughSequence,
            String codeFingerprint, Instant createdAt)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO local_review_brain_handoff
                    (validation_claim_key, task_id, through_sequence, code_fingerprint, created_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """,
                validationClaimKey, taskId, throughSequence, codeFingerprint,
                createdAt.toEpochMilli());
    }

    @Transactional(readOnly = true)
    public List<Handoff> listUnconsumedByTask(String taskId)
    {
        return jdbc.query("""
                SELECT validation_claim_key, task_id, through_sequence, code_fingerprint, delivery_failures
                FROM local_review_brain_handoff
                WHERE task_id = ? AND consumed_at_ms IS NULL
                ORDER BY created_at_ms
                """,
                (rs, i) -> new Handoff(
                        rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getInt(5)),
                taskId);
    }

    @Transactional(readOnly = true)
    public List<Handoff> listUnconsumed(int limit)
    {
        return jdbc.query("""
                SELECT validation_claim_key, task_id, through_sequence, code_fingerprint, delivery_failures
                FROM local_review_brain_handoff
                WHERE consumed_at_ms IS NULL
                ORDER BY created_at_ms
                LIMIT ?
                """,
                (rs, i) -> new Handoff(
                        rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getInt(5)),
                limit);
    }

    @Transactional
    public void markConsumed(String validationClaimKey, Instant at)
    {
        jdbc.update("""
                UPDATE local_review_brain_handoff SET consumed_at_ms = ?
                WHERE validation_claim_key = ? AND consumed_at_ms IS NULL
                """,
                at.toEpochMilli(), validationClaimKey);
    }

    @Transactional
    public void incrementDeliveryFailures(String validationClaimKey)
    {
        jdbc.update("""
                UPDATE local_review_brain_handoff SET delivery_failures = delivery_failures + 1
                WHERE validation_claim_key = ?
                """,
                validationClaimKey);
    }
}
