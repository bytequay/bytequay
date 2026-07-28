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

import com.bytequay.app.developmentflow.execution.remote.RemoteMarkReadyOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.remote.RemoteMarkReadyOperationHandler.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Durable mark-ready claim and accepted-observation state. */
@Repository
public class SqliteRemoteMarkReadyOperationStore
        implements RemoteMarkReadyOperationHandler.OperationStore
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteRemoteMarkReadyOperationStore(
            JdbcTemplate jdbc, TransactionTemplate transactions)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
    }

    @Override
    public Operation require(String operationId)
    {
        List<Operation> rows = jdbc.query("""
                SELECT operation.id, operation.operation_id,
                       operation.mark_ready_authorization_id,
                       operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       binding.remote_repository_id, binding.remote_pr_number,
                       operation.head_sha, operation.base_sha, operation.status,
                       operation.attempt_count, operation.attempt_limit,
                       operation.result_snapshot_id, operation.evidence
                FROM remote_mark_ready_operation operation
                JOIN remote_mark_ready_dispatch dispatch
                  ON dispatch.remote_mark_ready_operation_id = operation.id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN remote_pr_binding binding
                  ON binding.id = remote.remote_pr_binding_id
                WHERE operation.operation_id = ?
                """, (rs, row) -> operation(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact mark-ready operation is missing");
        }
        return rows.getFirst();
    }

    @Override
    public Operation claim(
            String id,
            int expectedAttemptCount,
            String claimMode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        return requireNonNull(transactions.execute(status -> {
            int changed = jdbc.update("""
                    UPDATE remote_mark_ready_operation
                    SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                        claim_mode = ?, claim_owner = ?, claimed_at_ms = ?,
                        lease_until_ms = ?, result_snapshot_id = NULL,
                        evidence = NULL, last_error = NULL,
                        completed_at_ms = NULL
                    WHERE id = ? AND attempt_count = ?
                    """, claimMode, claimOwner, claimedAt.toEpochMilli(),
                    leaseUntil.toEpochMilli(), id, expectedAttemptCount);
            if (changed != 1) {
                throw new IllegalStateException("Mark-ready claim lost");
            }
            String operationId = jdbc.queryForObject("""
                    SELECT operation_id FROM remote_mark_ready_operation
                    WHERE id = ?
                    """, String.class, id);
            return require(requireNonNull(operationId, "operation id is missing"));
        }), "Mark-ready claim returned null");
    }

    @Override
    public void awaitObservation(String id, int attempt)
    {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE remote_mark_ready_operation
                    SET status = 'AWAITING_OBSERVATION', claim_mode = NULL,
                        claim_owner = NULL, claimed_at_ms = NULL,
                        lease_until_ms = NULL
                    WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                    """, id, attempt);
            if (changed != 1) {
                throw new IllegalStateException("Mark-ready observation wait lost");
            }
        });
    }

    @Override
    public void finishSucceeded(
            String id, String snapshotId, String evidence, Instant completedAt)
    {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE remote_mark_ready_operation
                    SET status = 'SUCCEEDED', result_snapshot_id = ?,
                        evidence = ?, last_error = NULL, completed_at_ms = ?
                    WHERE id = ? AND status = 'AWAITING_OBSERVATION'
                    """, snapshotId, evidence, completedAt.toEpochMilli(), id);
            if (changed != 1) {
                throw new IllegalStateException("Mark-ready success lost");
            }
        });
    }

    @Override
    public void finishFailed(
            String id, int attempt, String error, Instant completedAt)
    {
        finishClaim(id, attempt, "FAILED", null, error, completedAt);
    }

    @Override
    public void finishIndeterminate(
            String id, int attempt, String evidence, Instant completedAt)
    {
        finishClaim(id, attempt, "INDETERMINATE", evidence, evidence, completedAt);
    }

    @Override
    public Optional<RemoteMarkReadyOperationHandler.Observation>
            findAcceptedReadyObservation(Operation operation)
    {
        requireNonNull(operation, "operation is null");
        return jdbc.query("""
                SELECT snapshot.id,
                       COALESCE(NULLIF(snapshot.raw_evidence, ''),
                           'accepted non-Draft Remote observation') AS evidence
                FROM remote_development_stage remote
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                WHERE remote.stage_id = ?
                  AND remote.task_id = ?
                  AND remote.generation = ?
                  AND remote.current_head_sha = ?
                  AND remote.current_base_sha = ?
                  AND snapshot.task_epoch = ?
                  AND snapshot.stage_generation = ?
                  AND snapshot.remote_repository_id = ?
                  AND snapshot.remote_pr_number = ?
                  AND snapshot.head_sha = ?
                  AND snapshot.base_sha = ?
                  AND snapshot.pr_state = 'OPEN'
                """, (rs, row) -> new RemoteMarkReadyOperationHandler.Observation(
                        true, rs.getString("id"),
                        rs.getString("evidence")),
                operation.stageId(), operation.taskId(), operation.stageGeneration(),
                operation.headSha(), operation.baseSha(), operation.taskEpoch(),
                operation.stageGeneration(), operation.repositoryId(),
                operation.pullRequestNumber(), operation.headSha(),
                operation.baseSha()).stream().findFirst();
    }

    private void finishClaim(
            String id,
            int attempt,
            String target,
            String evidence,
            String error,
            Instant completedAt)
    {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE remote_mark_ready_operation
                    SET status = ?, claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        evidence = ?, last_error = ?, completed_at_ms = ?
                    WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                    """, target, evidence, error, completedAt.toEpochMilli(), id, attempt);
            if (changed != 1) {
                throw new IllegalStateException("Mark-ready result lost");
            }
        });
    }

    private static Operation operation(ResultSet rs)
            throws SQLException
    {
        return new Operation(
                rs.getString("id"), rs.getString("operation_id"),
                rs.getString("mark_ready_authorization_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"), rs.getInt("semantic_attempt"),
                rs.getString("remote_repository_id"),
                rs.getInt("remote_pr_number"),
                rs.getString("head_sha"), rs.getString("base_sha"),
                Status.valueOf(rs.getString("status")), rs.getInt("attempt_count"),
                rs.getInt("attempt_limit"), rs.getString("result_snapshot_id"),
                rs.getString("evidence"));
    }
}
