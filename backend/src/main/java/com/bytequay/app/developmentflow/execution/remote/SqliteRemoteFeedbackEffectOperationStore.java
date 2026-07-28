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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler.Effect;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler.EffectStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Durable ordered claim/probe cursor for Remote feedback effects. */
@Repository
public class SqliteRemoteFeedbackEffectOperationStore
        implements RemoteFeedbackEffectOperationHandler.OperationStore
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteRemoteFeedbackEffectOperationStore(
            JdbcTemplate jdbc, TransactionTemplate transactions)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
    }

    @Override
    public Effect require(String operationId)
    {
        List<Effect> rows = jdbc.query("""
                SELECT step.id, dispatch.operation_id,
                       step.remote_feedback_authorization_id,
                       step.remote_feedback_batch_id, step.ordinal, step.kind,
                       step.remote_inbox_item_id, step.external_target,
                       step.review_action, payload.payload, step.payload_digest,
                       step.idempotency_key, step.status, step.attempt_count,
                       step.attempt_limit, batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id, batch.stage_generation,
                       authorization.head_sha, authorization.base_sha,
                       step.external_effect_id, step.evidence
                FROM remote_feedback_effect_dispatch dispatch
                JOIN remote_feedback_effect_step step
                  ON step.id = dispatch.remote_feedback_effect_step_id
                JOIN remote_feedback_effect_payload payload
                  ON payload.remote_feedback_effect_step_id = step.id
                JOIN remote_feedback_batch batch
                  ON batch.id = step.remote_feedback_batch_id
                JOIN remote_feedback_authorization authorization
                  ON authorization.id = step.remote_feedback_authorization_id
                WHERE dispatch.operation_id = ?
                """, (rs, row) -> effect(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact Remote feedback effect is missing");
        }
        return rows.getFirst();
    }

    @Override
    public Effect claim(
            String effectId,
            int expectedAttemptCount,
            ClaimMode mode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        return requireNonNull(transactions.execute(status -> {
            int changed = jdbc.update("""
                    UPDATE remote_feedback_effect_step
                    SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                        claim_mode = ?, claim_owner = ?, claimed_at_ms = ?,
                        lease_until_ms = ?, external_effect_id = NULL,
                        evidence = NULL, last_error = NULL,
                        completed_at_ms = NULL
                    WHERE id = ? AND attempt_count = ?
                    """, mode.name(), claimOwner, claimedAt.toEpochMilli(),
                    leaseUntil.toEpochMilli(), effectId, expectedAttemptCount);
            if (changed != 1) {
                throw new IllegalStateException("Remote feedback effect claim lost");
            }
            String operationId = jdbc.queryForObject("""
                    SELECT operation_id FROM remote_feedback_effect_dispatch
                    WHERE remote_feedback_effect_step_id = ?
                    """, String.class, effectId);
            return require(requireNonNull(operationId, "effect operation is missing"));
        }), "Remote feedback effect claim returned null");
    }

    @Override
    public void finishSucceeded(
            String effectId,
            int attempt,
            String externalEffectId,
            String evidence,
            Instant completedAt)
    {
        finish(effectId, attempt, "SUCCEEDED", externalEffectId,
                evidence, null, completedAt);
    }

    @Override
    public void finishFailed(
            String effectId, int attempt, String error, Instant completedAt)
    {
        finish(effectId, attempt, "FAILED", null, null, error, completedAt);
    }

    @Override
    public void finishIndeterminate(
            String effectId, int attempt, String evidence, Instant completedAt)
    {
        finish(effectId, attempt, "INDETERMINATE", null, evidence,
                evidence, completedAt);
    }

    private void finish(
            String effectId,
            int attempt,
            String status,
            String externalEffectId,
            String evidence,
            String error,
            Instant completedAt)
    {
        transactions.executeWithoutResult(ignored -> {
            int changed = jdbc.update("""
                    UPDATE remote_feedback_effect_step
                    SET status = ?, claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        external_effect_id = ?, evidence = ?, last_error = ?,
                        completed_at_ms = ?
                    WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                    """, status, externalEffectId, evidence, error,
                    completedAt.toEpochMilli(), effectId, attempt);
            if (changed != 1) {
                throw new IllegalStateException("Remote feedback effect result lost");
            }
        });
    }

    private static Effect effect(ResultSet rs)
            throws SQLException
    {
        return new Effect(
                rs.getString("id"), rs.getString("operation_id"),
                rs.getString("remote_feedback_authorization_id"),
                rs.getString("remote_feedback_batch_id"), rs.getInt("ordinal"),
                EffectKind.valueOf(rs.getString("kind")),
                rs.getString("remote_inbox_item_id"),
                rs.getString("external_target"), rs.getString("review_action"),
                rs.getString("payload"), rs.getString("payload_digest"),
                rs.getString("idempotency_key"),
                EffectStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"), rs.getInt("attempt_limit"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("external_effect_id"),
                rs.getString("evidence"));
    }
}
