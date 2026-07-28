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
package com.bytequay.app.developmentflow.execution.cleanup;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.CleanupTarget;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.FailureKind;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Requirement;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Step;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepKind;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepResult;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepStatus;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Exact SQLite ledger for one current V2 CleanupOperation. */
@Repository
public class SqliteCleanupOperationStore
        implements CleanupOperationHandler.OperationStore
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteCleanupOperationStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = new TransactionTemplate(
                requireNonNull(transactionManager, "transactionManager is null"));
    }

    @Override
    public Operation requireByOperationId(String operationId)
    {
        CleanupOperationHandler.requireText(operationId, "operationId");
        List<OperationRow> rows = jdbc.query("""
                SELECT operation.id, operation.dispatch_ticket_id,
                       operation.task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, operation.task_epoch,
                       task.aggregate_version AS task_version,
                       operation.cleanup_stage_id, operation.stage_generation,
                       owner.version AS stage_version,
                       owner.checkpoint AS stage_checkpoint,
                       operation.terminal_acceptance_id,
                       operation.operation_id, operation.semantic_attempt,
                       operation.status,
                       (SELECT barrier.id
                          FROM task_quiescence_barrier barrier
                         WHERE barrier.task_id = operation.task_id
                           AND barrier.task_epoch = operation.task_epoch
                           AND barrier.status = 'SATISFIED'
                           AND barrier.reason IN ('CLEANUP', 'CANCEL')
                         ORDER BY CASE barrier.reason WHEN 'CLEANUP' THEN 0 ELSE 1 END,
                                  barrier.completed_at_ms DESC, barrier.id
                         LIMIT 1) AS barrier_id,
                       target.repository_id, target.publish_repository_id,
                       watched.local_clone_path AS repository_root,
                       target.worktree_path, target.branch_name
                  FROM cleanup_operation operation
                  JOIN cleanup_stage cleanup
                    ON cleanup.stage_id = operation.cleanup_stage_id
                  JOIN stage owner ON owner.id = cleanup.stage_id
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN threads trunk ON trunk.id = task.thread_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN task_provision_target target ON target.task_id = task.id
                  JOIN watched_repos watched
                    ON lower(watched.owner || '/' || watched.repo)
                     = lower(target.repository_id)
                 WHERE operation.operation_id = ?
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'CLEANING'
                   AND task.epoch = operation.task_epoch
                   AND current.stage_id = operation.cleanup_stage_id
                   AND current.stage_generation = operation.stage_generation
                   AND owner.generation = operation.stage_generation
                   AND owner.completed_at_ms IS NULL
                """, (result, ignored) -> operationRow(result), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "expected one current CleanupOperation graph for "
                            + operationId + ", found " + rows.size());
        }
        OperationRow row = rows.getFirst();
        if (row.barrierId() == null) {
            throw new IllegalStateException("CleanupOperation lacks satisfied quiescence");
        }
        List<Step> steps = jdbc.query("""
                SELECT step.*,
                       (SELECT result.evidence_digest
                          FROM cleanup_step_attempt_result result
                         WHERE result.cleanup_step_id = step.id
                         ORDER BY result.attempt DESC LIMIT 1)
                           AS latest_evidence_digest,
                       EXISTS (
                           SELECT 1 FROM cleanup_step_retry_request retry
                            WHERE retry.cleanup_step_id = step.id
                              AND retry.status = 'PENDING') AS retry_requested
                  FROM cleanup_step step
                 WHERE step.cleanup_operation_id = ?
                 ORDER BY step.ordinal
                """, (result, ignored) -> step(result), row.id());
        return row.toOperation(steps);
    }

    @Override
    public void activate(String cleanupOperationId, Instant startedAt)
    {
        requireNonNull(startedAt, "startedAt is null");
        inTransaction(() -> {
            int changed = jdbc.update("""
                    UPDATE cleanup_operation
                       SET status = 'ACTIVE', started_at_ms = ?
                     WHERE id = ? AND status = 'REQUESTED'
                    """, startedAt.toEpochMilli(), cleanupOperationId);
            if (changed == 0 && !"ACTIVE".equals(jdbc.queryForObject(
                    "SELECT status FROM cleanup_operation WHERE id = ?",
                    String.class, cleanupOperationId))) {
                throw new IllegalStateException("CleanupOperation cannot activate");
            }
        });
    }

    @Override
    public Step claim(
            String stepId,
            ClaimMode mode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        requireNonNull(mode, "mode is null");
        requireNonNull(claimedAt, "claimedAt is null");
        requireNonNull(leaseUntil, "leaseUntil is null");
        CleanupOperationHandler.requireText(claimOwner, "claimOwner");
        return inTransaction(() -> {
            Step before = requireStep(stepId);
            int changed = jdbc.update("""
                    UPDATE cleanup_step
                       SET status = 'CLAIMED',
                           attempt_count = attempt_count + 1,
                           execute_attempt_count = execute_attempt_count
                               + CASE ? WHEN 'EXECUTE' THEN 1 ELSE 0 END,
                           claim_mode = ?, claim_owner = ?, claimed_at_ms = ?,
                           lease_until_ms = ?, failure_kind = NULL,
                           last_error = NULL, completed_at_ms = NULL
                     WHERE id = ? AND status = ? AND attempt_count = ?
                    """, mode.name(), mode.name(), claimOwner,
                    claimedAt.toEpochMilli(), leaseUntil.toEpochMilli(),
                    stepId, before.status().name(), before.attemptCount());
            if (changed != 1) {
                throw new IllegalStateException("CleanupStep claim lost its exact fence");
            }
            return requireStep(stepId);
        });
    }

    @Override
    public void skip(String stepId, String reason, Instant skippedAt)
    {
        requireNonNull(skippedAt, "skippedAt is null");
        CleanupOperationHandler.requireText(reason, "reason");
        inTransaction(() -> {
            Step step = requireStep(stepId);
            if (step.status() == StepStatus.SKIPPED) {
                return;
            }
            jdbc.update("""
                    INSERT INTO cleanup_step_skip_evidence(
                        id, cleanup_step_id, reason, evidence,
                        skipped_by, skipped_at_ms)
                    VALUES (?, ?, ?, ?, 'CleanupStageManager', ?)
                    """, evidenceId("SKIP", stepId, step.attemptCount()), stepId,
                    reason, "requirement=NOT_APPLICABLE", skippedAt.toEpochMilli());
            int changed = jdbc.update("""
                    UPDATE cleanup_step
                       SET status = 'SKIPPED', completed_at_ms = ?
                     WHERE id = ? AND status = 'REQUESTED'
                    """, skippedAt.toEpochMilli(), stepId);
            requireChanged(changed, "CleanupStep skip lost its exact fence");
        });
    }

    @Override
    public void succeed(Step step, StepResult result, Instant completedAt)
    {
        requireNonNull(completedAt, "completedAt is null");
        if (result.outcome() != CleanupOperationHandler.EffectOutcome.SUCCEEDED) {
            throw new IllegalArgumentException("success requires SUCCEEDED effect");
        }
        inTransaction(() -> {
            insertResult(step, result, completedAt);
            int changed = jdbc.update("""
                    UPDATE cleanup_step
                       SET status = 'SUCCEEDED', claim_mode = NULL,
                           claim_owner = NULL, claimed_at_ms = NULL,
                           lease_until_ms = NULL, failure_kind = NULL,
                           last_error = NULL, completed_at_ms = ?
                     WHERE id = ? AND status = 'CLAIMED'
                       AND attempt_count = ? AND claim_mode = ?
                    """, completedAt.toEpochMilli(), step.id(), step.attemptCount(),
                    step.claimMode().name());
            requireChanged(changed, "CleanupStep success lost its exact claim");
            resolveBlockers(step, "RESOLVED", "cleanup step succeeded", completedAt);
        });
    }

    @Override
    public void fail(Step step, StepResult result, Instant completedAt)
    {
        requireNonNull(completedAt, "completedAt is null");
        if (result.outcome() == CleanupOperationHandler.EffectOutcome.SUCCEEDED) {
            throw new IllegalArgumentException("failure requires a non-success effect");
        }
        inTransaction(() -> {
            insertResult(step, result, completedAt);
            String blockerId = evidenceId("BLOCKER", step.id(), step.attemptCount());
            jdbc.update("""
                    INSERT INTO task_blocker(
                        id, task_id, stage_id, owner_kind, owner_id,
                        subject_revision, blocker_type, status, payload_json,
                        opened_at_ms)
                    SELECT ?, task_id, cleanup_stage_id, 'OPERATION',
                           cleanup_operation_id, CAST(ordinal AS TEXT),
                           'CLEANUP_STEP_FAILED', 'OPEN', ?, ?
                      FROM cleanup_step WHERE id = ?
                    """, blockerId,
                    "{\"attempt\":" + step.attemptCount()
                            + ",\"error\":\"" + json(result.error()) + "\"}",
                    completedAt.toEpochMilli(), step.id());
            FailureKind kind = result.outcome()
                    == CleanupOperationHandler.EffectOutcome.INDETERMINATE
                    ? FailureKind.INDETERMINATE
                    : FailureKind.DETERMINATE;
            int changed = jdbc.update("""
                    UPDATE cleanup_step
                       SET status = 'FAILED', claim_mode = NULL,
                           claim_owner = NULL, claimed_at_ms = NULL,
                           lease_until_ms = NULL, failure_kind = ?,
                           last_error = ?, completed_at_ms = ?
                     WHERE id = ? AND status = 'CLAIMED'
                       AND attempt_count = ? AND claim_mode = ?
                    """, kind.name(), result.error(), completedAt.toEpochMilli(),
                    step.id(), step.attemptCount(), step.claimMode().name());
            requireChanged(changed, "CleanupStep failure lost its exact claim");
        });
    }

    public void requestRetry(
            String stepId, String requestedBy, String reason, Instant requestedAt)
    {
        CleanupOperationHandler.requireText(requestedBy, "requestedBy");
        CleanupOperationHandler.requireText(reason, "reason");
        requireNonNull(requestedAt, "requestedAt is null");
        inTransaction(() -> {
            Step step = requireStep(stepId);
            jdbc.update("""
                    INSERT INTO cleanup_step_retry_request(
                        id, cleanup_step_id, cleanup_operation_id, task_id,
                        failed_attempt, requested_by, reason, status,
                        requested_at_ms)
                    SELECT ?, id, cleanup_operation_id, task_id, attempt_count,
                           ?, ?, 'PENDING', ?
                      FROM cleanup_step WHERE id = ?
                    """, evidenceId("RETRY", step.id(), step.attemptCount()),
                    requestedBy, reason, requestedAt.toEpochMilli(), stepId);
        });
    }

    public String waiveOptional(
            String stepId, String actor, String reason, Instant waivedAt)
    {
        CleanupOperationHandler.requireText(actor, "actor");
        CleanupOperationHandler.requireText(reason, "reason");
        requireNonNull(waivedAt, "waivedAt is null");
        return inTransaction(() -> {
            Step step = requireStep(stepId);
            String waiverId = evidenceId("WAIVER", step.id(), step.attemptCount());
            jdbc.update("""
                    INSERT INTO cleanup_step_waiver(
                        id, cleanup_step_id, actor_id, reason, evidence,
                        waived_at_ms)
                    VALUES (?, ?, ?, ?, 'explicit optional cleanup waiver', ?)
                    """, waiverId, stepId, actor, reason, waivedAt.toEpochMilli());
            int changed = jdbc.update("""
                    UPDATE cleanup_step
                       SET status = 'WAIVED', failure_kind = NULL,
                           last_error = NULL
                     WHERE id = ? AND status = 'FAILED'
                       AND requirement = 'OPTIONAL'
                    """, stepId);
            requireChanged(changed, "Cleanup waiver lost its exact failure");
            resolveBlockers(step, "WAIVED", "optional cleanup explicitly waived", waivedAt);
            return waiverId;
        });
    }

    public CleanupCompletion acceptSuccessfulResult(
            DispatchTicket.OperationFence fence,
            String summaryDigest,
            Instant completedAt)
    {
        requireNonNull(fence, "fence is null");
        CleanupOperationHandler.requireText(summaryDigest, "summaryDigest");
        requireNonNull(completedAt, "completedAt is null");
        return inTransaction(() -> {
            CleanupCompletion completion = requireCompletion(
                    fence.operationId(), false).orElseThrow(() ->
                    new IllegalStateException("Cleanup result owner is stale"));
            if (!completion.matches(fence)) {
                throw new IllegalStateException("Cleanup result fence is stale");
            }
            int changed = jdbc.update("""
                    UPDATE cleanup_operation
                       SET status = 'COMPLETED', completed_at_ms = ?,
                           summary_digest = ?
                     WHERE id = ? AND status = 'ACTIVE'
                    """, completedAt.toEpochMilli(), summaryDigest,
                    completion.cleanupOperationId());
            if (changed == 0) {
                String existing = jdbc.queryForObject("""
                        SELECT summary_digest FROM cleanup_operation
                         WHERE id = ? AND status = 'COMPLETED'
                        """, String.class, completion.cleanupOperationId());
                if (!summaryDigest.equals(existing)) {
                    throw new IllegalStateException(
                            "Cleanup result conflicts with completed evidence");
                }
            }
            return completion.withSummaryDigest(summaryDigest);
        });
    }

    public Optional<CleanupCompletion> findPendingDelivery(
            DispatchTicket.OperationFence fence)
    {
        requireNonNull(fence, "fence is null");
        return requireCompletion(fence.operationId(), false)
                .filter(completion -> completion.matches(fence));
    }

    public Optional<CleanupCompletion> findPendingFinalization(String operationId)
    {
        CleanupOperationHandler.requireText(operationId, "operationId");
        return requireCompletion(operationId, true);
    }

    public List<CleanupCompletion> findPendingFinalizations(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return completionRows("""
                WHERE operation.status = 'COMPLETED'
                  AND owner.checkpoint = 'CLEANING'
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                  AND task.lifecycle_state = 'CLEANING'
                  AND NOT EXISTS (
                      SELECT 1 FROM task_outcome outcome
                       WHERE outcome.task_id = task.id)
                ORDER BY operation.completed_at_ms, operation.id
                LIMIT ?
                """, limit);
    }

    private Optional<CleanupCompletion> requireCompletion(
            String operationId, boolean terminalTicket)
    {
        String ticketPredicate = terminalTicket
                ? """
                  AND operation.status = 'COMPLETED'
                  AND owner.checkpoint = 'CLEANING'
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                  """
                : """
                  AND operation.status IN ('ACTIVE', 'COMPLETED')
                  AND owner.checkpoint = 'CLEANING'
                  AND ticket.status = 'RESULT_PENDING'
                  AND ticket.pending_result_outcome = 'SUCCEEDED'
                  """;
        return completionRows("""
                WHERE operation.operation_id = ?
                  AND task.lifecycle_state = 'CLEANING'
                  %s
                """.formatted(ticketPredicate), operationId).stream().findFirst();
    }

    private List<CleanupCompletion> completionRows(String suffix, Object... arguments)
    {
        return jdbc.query("""
                SELECT operation.id AS cleanup_operation_id,
                       operation.operation_id, operation.semantic_attempt,
                       operation.task_id, operation.task_epoch,
                       task.aggregate_version AS task_version,
                       operation.cleanup_stage_id, operation.stage_generation,
                       operation.summary_digest,
                       owner.version AS stage_version,
                       owner.checkpoint AS stage_checkpoint
                  FROM cleanup_operation operation
                  JOIN dispatch_ticket ticket
                    ON ticket.id = operation.dispatch_ticket_id
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                   AND current.stage_id = operation.cleanup_stage_id
                   AND current.stage_generation = operation.stage_generation
                  JOIN stage owner ON owner.id = operation.cleanup_stage_id
                                     AND owner.task_id = task.id
                                     AND owner.generation = operation.stage_generation
                  %s
                """.formatted(suffix), (result, ignored) -> new CleanupCompletion(
                        result.getString("cleanup_operation_id"),
                        result.getString("operation_id"),
                        result.getInt("semantic_attempt"),
                        result.getString("task_id"),
                        result.getLong("task_epoch"),
                        result.getLong("task_version"),
                        result.getString("cleanup_stage_id"),
                        result.getLong("stage_generation"),
                        result.getString("summary_digest"),
                        result.getLong("stage_version"),
                        StageCheckpoint.valueOf(result.getString("stage_checkpoint"))),
                arguments);
    }

    private void insertResult(Step step, StepResult result, Instant completedAt)
    {
        jdbc.update("""
                INSERT INTO cleanup_step_attempt_result(
                    id, cleanup_step_id, cleanup_operation_id, task_id,
                    task_epoch, ordinal, attempt, claim_mode, outcome,
                    external_effect_id, evidence, evidence_digest,
                    error_message, recorded_at_ms)
                SELECT ?, step.id, step.cleanup_operation_id, step.task_id,
                       step.task_epoch, step.ordinal, step.attempt_count,
                       step.claim_mode, ?, ?, ?, ?, ?, ?
                  FROM cleanup_step step
                 WHERE step.id = ? AND step.status = 'CLAIMED'
                   AND step.attempt_count = ? AND step.claim_mode = ?
                """, evidenceId("RESULT", step.id(), step.attemptCount()),
                result.outcome().name(), result.externalEffectId(), result.evidence(),
                result.evidenceDigest(), result.error(), completedAt.toEpochMilli(),
                step.id(), step.attemptCount(), step.claimMode().name());
    }

    private void resolveBlockers(
            Step step, String status, String evidence, Instant resolvedAt)
    {
        jdbc.update("""
                UPDATE task_blocker
                   SET status = ?, resolved_at_ms = ?, resolution_evidence = ?
                 WHERE owner_kind = 'OPERATION' AND owner_id = ?
                   AND subject_revision = ? AND status = 'OPEN'
                """, status, resolvedAt.toEpochMilli(), evidence,
                step.cleanupOperationId(), Integer.toString(step.ordinal()));
    }

    private Step requireStep(String stepId)
    {
        List<Step> rows = jdbc.query("""
                SELECT step.*,
                       (SELECT result.evidence_digest
                          FROM cleanup_step_attempt_result result
                         WHERE result.cleanup_step_id = step.id
                         ORDER BY result.attempt DESC LIMIT 1)
                           AS latest_evidence_digest,
                       EXISTS (
                           SELECT 1 FROM cleanup_step_retry_request retry
                            WHERE retry.cleanup_step_id = step.id
                              AND retry.status = 'PENDING') AS retry_requested
                  FROM cleanup_step step WHERE step.id = ?
                """, (result, ignored) -> step(result), stepId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "expected one CleanupStep " + stepId + ", found " + rows.size());
        }
        return rows.getFirst();
    }

    private static OperationRow operationRow(ResultSet result)
            throws SQLException
    {
        return new OperationRow(
                result.getString("id"),
                result.getString("dispatch_ticket_id"),
                result.getString("task_id"),
                result.getString("trunk_id"),
                result.getString("workspace_id"),
                result.getLong("task_epoch"),
                result.getLong("task_version"),
                result.getString("cleanup_stage_id"),
                result.getLong("stage_generation"),
                result.getLong("stage_version"),
                StageCheckpoint.valueOf(result.getString("stage_checkpoint")),
                result.getString("terminal_acceptance_id"),
                result.getString("operation_id"),
                result.getInt("semantic_attempt"),
                OperationStatus.valueOf(result.getString("status")),
                result.getString("barrier_id"),
                new CleanupTarget(
                        result.getString("repository_id"),
                        result.getString("publish_repository_id"),
                        result.getString("repository_root"),
                        result.getString("worktree_path"),
                        result.getString("branch_name"),
                        null));
    }

    private static Step step(ResultSet result)
            throws SQLException
    {
        return new Step(
                result.getString("id"),
                result.getString("cleanup_operation_id"),
                result.getInt("ordinal"),
                StepKind.valueOf(result.getString("kind")),
                Requirement.valueOf(result.getString("requirement")),
                StepStatus.valueOf(result.getString("status")),
                result.getInt("attempt_count"),
                result.getInt("execute_attempt_count"),
                result.getInt("attempt_limit"),
                valueOf(result.getString("claim_mode"), ClaimMode.class),
                result.getString("claim_owner"),
                instant(result, "claimed_at_ms"),
                instant(result, "lease_until_ms"),
                valueOf(result.getString("failure_kind"), FailureKind.class),
                result.getString("last_error"),
                result.getString("latest_evidence_digest"),
                result.getBoolean("retry_requested"));
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static <E extends Enum<E>> E valueOf(String value, Class<E> type)
    {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String evidenceId(String kind, String owner, int attempt)
    {
        return kind + ":" + owner + ":" + attempt;
    }

    private static String json(String value)
    {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private static void requireChanged(int changed, String message)
    {
        if (changed != 1) {
            throw new IllegalStateException(message);
        }
    }

    private void inTransaction(Runnable work)
    {
        transactions.executeWithoutResult(ignored -> work.run());
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        return transactions.execute(ignored -> work.get());
    }

    private record OperationRow(
            String id,
            String dispatchTicketId,
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            long taskVersion,
            String cleanupStageId,
            long stageGeneration,
            long stageVersion,
            StageCheckpoint stageCheckpoint,
            String terminalAcceptanceId,
            String operationId,
            int semanticAttempt,
            OperationStatus status,
            String barrierId,
            CleanupTarget target)
    {
        private Operation toOperation(List<Step> steps)
        {
            return new Operation(
                    id, dispatchTicketId, taskId, trunkId, workspaceId,
                    taskEpoch, taskVersion, cleanupStageId, stageGeneration,
                    stageVersion, stageCheckpoint, terminalAcceptanceId,
                    operationId, semanticAttempt, status, barrierId, target, steps);
        }
    }

    public record CleanupCompletion(
            String cleanupOperationId,
            String operationId,
            int semanticAttempt,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String cleanupStageId,
            long stageGeneration,
            String summaryDigest,
            long stageVersion,
            StageCheckpoint stageCheckpoint)
    {
        public CleanupCompletion
        {
            CleanupOperationHandler.requireText(
                    cleanupOperationId, "cleanupOperationId");
            CleanupOperationHandler.requireText(operationId, "operationId");
            CleanupOperationHandler.requireText(taskId, "taskId");
            CleanupOperationHandler.requireText(cleanupStageId, "cleanupStageId");
            requireNonNull(stageCheckpoint, "stageCheckpoint is null");
            if (semanticAttempt < 1 || taskEpoch < 1 || taskVersion < 0
                    || stageGeneration < 1 || stageVersion < 0) {
                throw new IllegalArgumentException("Cleanup completion fence is invalid");
            }
        }

        boolean matches(DispatchTicket.OperationFence fence)
        {
            return operationId.equals(fence.operationId())
                    && semanticAttempt == fence.attempt()
                    && Objects.equals(taskEpoch, fence.taskEpoch())
                    && cleanupStageId.equals(fence.stageId())
                    && Objects.equals(stageGeneration, fence.stageGeneration());
        }

        CleanupCompletion withSummaryDigest(String digest)
        {
            return new CleanupCompletion(
                    cleanupOperationId, operationId, semanticAttempt,
                    taskId, taskEpoch, taskVersion, cleanupStageId,
                    stageGeneration, digest, stageVersion, stageCheckpoint);
        }
    }
}
