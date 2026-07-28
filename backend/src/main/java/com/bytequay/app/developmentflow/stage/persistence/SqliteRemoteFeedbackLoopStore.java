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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.developmentflow.ResultFence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact attempt history for the Remote feedback StageTurn/validation/Brain loop. */
@Repository
public class SqliteRemoteFeedbackLoopStore
{
    private final JdbcTemplate jdbc;

    public SqliteRemoteFeedbackLoopStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public FeedbackContext requireFeedbackContext(String batchId)
    {
        List<FeedbackContext> rows = jdbc.query("""
                SELECT batch.id AS batch_id, batch.sequence,
                       batch.source_snapshot_id, batch.content_digest,
                       batch.brain_review_required, batch.status AS batch_status,
                       batch.head_sha AS subject_head_sha, batch.base_sha,
                       task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.epoch AS task_epoch,
                       task.aggregate_version AS task_version,
                       remote.stage_id, remote.generation AS stage_generation,
                       owner.version AS stage_version, owner.checkpoint,
                       identity.worktree_path, context.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill,
                       COALESCE((
                           SELECT repair.code_fingerprint
                           FROM remote_feedback_repair_result repair
                           JOIN remote_feedback_stage_turn_request request
                             ON request.stage_turn_id = repair.repair_stage_turn_id
                           WHERE repair.remote_feedback_batch_id = batch.id
                           ORDER BY request.semantic_attempt DESC LIMIT 1),
                           code.code_fingerprint) AS code_fingerprint,
                       COALESCE((
                           SELECT repair.proposed_head_sha
                           FROM remote_feedback_repair_result repair
                           JOIN remote_feedback_stage_turn_request request
                             ON request.stage_turn_id = repair.repair_stage_turn_id
                           WHERE repair.remote_feedback_batch_id = batch.id
                           ORDER BY request.semantic_attempt DESC LIMIT 1),
                           code.head_sha) AS local_head_sha
                FROM remote_feedback_batch batch
                JOIN remote_development_stage remote
                  ON remote.stage_id = batch.remote_development_stage_id
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = batch.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE batch.id = ? AND batch.status IN ('FROZEN', 'ADDRESSING')
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = batch.task_epoch
                  AND current.stage_id = remote.stage_id
                  AND current.stage_generation = remote.generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                  AND remote.current_head_sha = batch.head_sha
                  AND remote.current_base_sha = batch.base_sha
                """, (rs, row) -> feedbackContext(rs), batchId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Expected one current Remote feedback batch");
        }
        FeedbackContext context = rows.getFirst();
        List<BatchItem> items = jdbc.query("""
                SELECT item.ordinal, item.remote_inbox_item_id,
                       item.external_revision, item.kind, item.frozen_body,
                       item.body_digest, item.external_target
                FROM remote_feedback_batch_item item
                WHERE item.remote_feedback_batch_id = ?
                ORDER BY item.ordinal
                """, (rs, row) -> new BatchItem(
                        rs.getInt("ordinal"), rs.getString("remote_inbox_item_id"),
                        rs.getLong("external_revision"), rs.getString("kind"),
                        rs.getString("frozen_body"), rs.getString("body_digest"),
                        rs.getString("external_target")), batchId);
        return context.withItems(items);
    }

    public Optional<TurnRequest> findTurn(String batchId, int attempt)
    {
        return jdbc.query("""
                SELECT request.id AS request_id, request.stage_turn_id,
                       turn.operation_id, ticket.id AS ticket_id,
                       request.semantic_attempt
                FROM remote_feedback_stage_turn_request request
                JOIN stage_turn turn ON turn.id = request.stage_turn_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE request.remote_feedback_batch_id = ?
                  AND request.semantic_attempt = ?
                """, (rs, row) -> new TurnRequest(
                        rs.getString("request_id"), rs.getString("stage_turn_id"),
                        rs.getString("operation_id"), rs.getString("ticket_id"),
                        rs.getInt("semantic_attempt")), batchId, attempt)
                .stream().findFirst();
    }

    public void markAddressing(String batchId)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE remote_feedback_batch SET status = 'ADDRESSING'
                WHERE id = ? AND status = 'FROZEN'
                """, batchId);
        if (changed != 1) {
            throw new IllegalStateException("Remote feedback batch did not start");
        }
    }

    public TurnRequest insertTurn(NewTurn turn)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'ADDRESS_REMOTE_FEEDBACK', 'QUEUED', ?, ?, ?,
                    ?, ?, ?, ?, ?, ?)
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.attempt(), turn.taskEpoch(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.deliveryLane(), turn.launchInput(),
                turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO remote_feedback_stage_turn_request(
                    id, remote_feedback_batch_id, stage_turn_id, task_id,
                    remote_development_stage_id, task_epoch, stage_generation,
                    semantic_attempt, predecessor_turn_id, prompt_digest,
                    requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turn.requestId(), turn.batchId(), turn.turnId(), turn.taskId(),
                turn.stageId(), turn.taskEpoch(), turn.stageGeneration(),
                turn.attempt(), turn.predecessorTurnId(), turn.promptDigest(),
                turn.requestedBy(), turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN',
                    'STAGE_TURN', ?, 'REMOTE_FEEDBACK_TURN_RESULT', ?,
                    0, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.laneMask(), turn.workspaceId(), turn.trunkId(), turn.taskId(),
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.attempt(), turn.codeFingerprint(), turn.headSha(),
                turn.baseSha(), turn.requestedAt().toEpochMilli());
        return new TurnRequest(
                turn.requestId(), turn.turnId(), turn.operationId(),
                turn.ticketId(), turn.attempt());
    }

    public String requireStageTurnTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT request.task_id
                FROM remote_feedback_stage_turn_request request
                JOIN stage_turn turn ON turn.id = request.stage_turn_id
                WHERE turn.id = ? AND turn.operation_id = ?
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Remote feedback StageTurn is missing");
        }
        return rows.getFirst();
    }

    public StageTurnContext requireStageTurnContext(String turnId, String operationId)
    {
        List<StageTurnContext> rows = jdbc.query("""
                SELECT request.id AS request_id,
                       request.remote_feedback_batch_id AS batch_id,
                       request.semantic_attempt, request.predecessor_turn_id,
                       turn.id AS turn_id, turn.operation_id,
                       turn.expected_code_fingerprint, turn.expected_head_sha,
                       turn.expected_base_sha, turn.status AS turn_status,
                       batch.task_id, batch.task_epoch,
                       batch.remote_development_stage_id AS stage_id,
                       batch.stage_generation, batch.head_sha AS subject_head_sha,
                       batch.base_sha, batch.brain_review_required,
                       identity.worktree_path, ticket.status AS ticket_status,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = batch.task_epoch
                              AND current.stage_id = batch.remote_development_stage_id
                              AND current.stage_generation = batch.stage_generation
                              AND owner.checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                              AND remote.current_head_sha = batch.head_sha
                              AND remote.current_base_sha = batch.base_sha
                              AND batch.status = 'ADDRESSING'
                            THEN 1 ELSE 0 END AS is_current
                FROM remote_feedback_stage_turn_request request
                JOIN stage_turn turn ON turn.id = request.stage_turn_id
                JOIN remote_feedback_batch batch
                  ON batch.id = request.remote_feedback_batch_id
                JOIN tasks task ON task.id = batch.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = batch.remote_development_stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = batch.remote_development_stage_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND ticket.callback_route = 'REMOTE_FEEDBACK_TURN_RESULT'
                """, (rs, row) -> stageTurnContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact Remote StageTurn delivery is missing");
        }
        return rows.getFirst();
    }

    public void finishStageTurn(
            StageTurnContext context, String status, String error, Instant at)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE stage_turn
                SET status = ?, started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, status, at.toEpochMilli(), error,
                context.turnId(), context.operationId());
        if (changed != 1) {
            throw new IllegalStateException("Remote StageTurn changed before delivery");
        }
    }

    public void supersedeUndeliveredStageTurn(
            StageTurnContext context, Instant at)
    {
        requireTransaction();
        jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUPERSEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?,
                    error_message = 'replaced by durable user steering'
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, at.toEpochMilli(), context.turnId(), context.operationId());
    }

    public void insertRepairForSteering(
            StageTurnContext context,
            String repairId,
            String proposedHeadSha,
            String codeFingerprint,
            String summary,
            String resultDigest,
            List<ReplyDraft> drafts,
            Instant at)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO remote_feedback_repair_result(
                    id, remote_feedback_batch_id, repair_stage_turn_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, subject_head_sha, proposed_head_sha,
                    base_sha, code_fingerprint, summary, result_digest,
                    completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, repairId, context.batchId(), context.turnId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.subjectHeadSha(), proposedHeadSha,
                context.baseSha(), codeFingerprint, summary, resultDigest,
                at.toEpochMilli());
        for (ReplyDraft draft : drafts) {
            jdbc.update("""
                    INSERT INTO remote_feedback_reply_draft(
                        id, remote_feedback_batch_id, repair_result_id,
                        batch_item_ordinal, kind, body, body_digest,
                        external_target, ordinal)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, draft.id(), context.batchId(), repairId,
                    draft.batchItemOrdinal(), draft.kind(), draft.body(),
                    draft.bodyDigest(), draft.externalTarget(), draft.ordinal());
        }
    }

    public ValidationRequest insertRepairAndValidation(
            StageTurnContext context,
            String repairId,
            String proposedHeadSha,
            String codeFingerprint,
            String summary,
            String resultDigest,
            List<ReplyDraft> drafts,
            String validationOperationId,
            String validationTicketId,
            Instant at)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO remote_feedback_repair_result(
                    id, remote_feedback_batch_id, repair_stage_turn_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, subject_head_sha, proposed_head_sha,
                    base_sha, code_fingerprint, summary, result_digest,
                    completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, repairId, context.batchId(), context.turnId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.subjectHeadSha(), proposedHeadSha,
                context.baseSha(), codeFingerprint, summary, resultDigest,
                at.toEpochMilli());
        for (ReplyDraft draft : drafts) {
            jdbc.update("""
                    INSERT INTO remote_feedback_reply_draft(
                        id, remote_feedback_batch_id, repair_result_id,
                        batch_item_ordinal, kind, body, body_digest,
                        external_target, ordinal)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, draft.id(), context.batchId(), repairId,
                    draft.batchItemOrdinal(), draft.kind(), draft.body(),
                    draft.bodyDigest(), draft.externalTarget(), draft.ordinal());
        }
        jdbc.update("""
                INSERT INTO remote_feedback_validation_operation(
                    id, remote_feedback_batch_id, repair_result_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, operation_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, validationOperationId, context.batchId(), repairId,
                context.stageId(), context.taskId(), context.taskEpoch(),
                context.stageGeneration(), validationOperationId,
                context.semanticAttempt(), codeFingerprint, proposedHeadSha,
                context.baseSha(), at.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                SELECT ?, ?, 'VALIDATE_REMOTE_FEEDBACK', 'VALIDATION',
                    'STAGE', ?, 'REMOTE_FEEDBACK_VALIDATION_RESULT', 4,
                    0, 1, 0, trunk.workspace_id, task.thread_id, task.id,
                    task.epoch, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?
                FROM tasks task JOIN threads trunk ON trunk.id = task.thread_id
                WHERE task.id = ?
                """, validationTicketId, validationOperationId, context.stageId(),
                context.stageId(), context.stageGeneration(),
                context.semanticAttempt(), codeFingerprint, proposedHeadSha,
                context.baseSha(), at.toEpochMilli(), context.taskId());
        int changed = jdbc.update("""
                UPDATE remote_feedback_validation_operation
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, validationOperationId);
        if (changed != 1) {
            throw new IllegalStateException("Remote validation did not dispatch");
        }
        return new ValidationRequest(
                validationOperationId, validationTicketId,
                new ResultFence(
                        context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), validationOperationId,
                        context.semanticAttempt(), codeFingerprint,
                        proposedHeadSha, context.baseSha()));
    }

    public String requireValidationTaskId(String operationId)
    {
        return requireOneText("""
                SELECT task_id FROM remote_feedback_validation_operation
                WHERE operation_id = ?
                """, operationId, "Remote validation owner is missing");
    }

    public String requireFeedbackContextByRepair(String repairId)
    {
        return requireOneText("""
                SELECT remote_feedback_batch_id
                FROM remote_feedback_repair_result WHERE id = ?
                """, repairId, "Remote feedback repair is missing");
    }

    public boolean requireBrainReviewRequired(String repairId)
    {
        List<Boolean> rows = jdbc.query("""
                SELECT batch.brain_review_required
                FROM remote_feedback_repair_result repair
                JOIN remote_feedback_batch batch
                  ON batch.id = repair.remote_feedback_batch_id
                WHERE repair.id = ?
                """, (rs, row) -> rs.getBoolean(1), repairId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Remote feedback repair is missing");
        }
        return rows.getFirst();
    }

    public ValidationContext requireValidationContext(String operationId)
    {
        List<ValidationContext> rows = jdbc.query("""
                SELECT operation.id, operation.operation_id, operation.status,
                       operation.semantic_attempt, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation, operation.repair_result_id,
                       operation.code_fingerprint, operation.expected_head_sha,
                       operation.expected_base_sha, repair.repair_stage_turn_id,
                       repair.subject_head_sha, identity.worktree_path,
                       ticket.status AS ticket_status,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = operation.task_epoch
                              AND current.stage_id = operation.remote_development_stage_id
                              AND current.stage_generation = operation.stage_generation
                              AND owner.checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                              AND batch.status = 'ADDRESSING'
                              AND remote.current_head_sha = repair.subject_head_sha
                              AND remote.current_base_sha = operation.expected_base_sha
                              AND operation.repair_result_id = (
                                  SELECT latest.repair_result_id
                                  FROM remote_feedback_validation_operation latest
                                  WHERE latest.remote_feedback_batch_id = batch.id
                                  ORDER BY latest.semantic_attempt DESC LIMIT 1)
                            THEN 1 ELSE 0 END AS is_current
                FROM remote_feedback_validation_operation operation
                JOIN remote_feedback_repair_result repair
                  ON repair.id = operation.repair_result_id
                JOIN remote_feedback_batch batch
                  ON batch.id = operation.remote_feedback_batch_id
                JOIN tasks task ON task.id = operation.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = operation.remote_development_stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN dispatch_ticket ticket ON ticket.operation_id = operation.operation_id
                WHERE operation.operation_id = ?
                  AND ticket.callback_route = 'REMOTE_FEEDBACK_VALIDATION_RESULT'
                """, (rs, row) -> validationContext(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact Remote validation is missing");
        }
        return rows.getFirst();
    }

    public ValidationAttempt completeValidation(
            ValidationContext context,
            boolean passed,
            String failuresJson,
            String evidence,
            Instant startedAt,
            Instant completedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, fix_rounds,
                    failures_json, workflow_version, task_epoch, stage_id,
                    stage_generation, operation_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha)
                VALUES (?, ?, ?, ?, 0, ?, 'V2', ?, ?, ?, ?, ?, ?, ?, ?)
                """, context.taskId(), startedAt.toEpochMilli(),
                completedAt.toEpochMilli(), passed ? 1 : 0, failuresJson,
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.operationId(), context.semanticAttempt(),
                context.codeFingerprint(), context.headSha(), context.baseSha());
        Long passId = jdbc.queryForObject("""
                SELECT id FROM validation_pass WHERE operation_id = ?
                """, Long.class, context.operationId());
        String evidenceId = "remote-validation-attempt-" + context.id();
        jdbc.update("""
                INSERT INTO remote_feedback_validation_attempt_evidence(
                    id, remote_feedback_batch_id, repair_result_id,
                    validation_operation_id, validation_pass_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, repair_stage_turn_id, semantic_attempt,
                    subject_head_sha, proposed_head_sha, base_sha,
                    code_fingerprint, passed, failures_json, evidence,
                    completed_at_ms)
                SELECT ?, operation.remote_feedback_batch_id, operation.repair_result_id,
                    operation.id, ?, operation.remote_development_stage_id,
                    operation.task_id, operation.task_epoch,
                    operation.stage_generation, ?, operation.semantic_attempt,
                    ?, operation.expected_head_sha, operation.expected_base_sha,
                    operation.code_fingerprint, ?, ?, ?, ?
                FROM remote_feedback_validation_operation operation
                WHERE operation.id = ?
                """, evidenceId, requireNonNull(passId, "validation pass is null"),
                context.repairStageTurnId(), context.subjectHeadSha(),
                passed ? 1 : 0, failuresJson, evidence,
                completedAt.toEpochMilli(), context.id());
        int changed = jdbc.update("""
                UPDATE remote_feedback_validation_operation
                SET status = 'COMPLETED', completed_at_ms = ?, error_message = NULL
                WHERE id = ? AND status = 'DISPATCHED'
                """, completedAt.toEpochMilli(), context.id());
        if (changed != 1) {
            throw new IllegalStateException("Remote validation changed before delivery");
        }
        return new ValidationAttempt(
                evidenceId, passId, passed, context.semanticAttempt(),
                context.repairResultId(), context.repairStageTurnId());
    }

    public void finishValidationWithoutEvidence(
            ValidationContext context, String status, String error, Instant at)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE remote_feedback_validation_operation
                SET status = ?, completed_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, status, at.toEpochMilli(), error, context.id());
        if (changed != 1) {
            throw new IllegalStateException("Remote validation result lost");
        }
    }

    public String insertFinalValidation(
            ValidationContext context, ValidationAttempt attempt, String evidence)
    {
        requireTransaction();
        String id = "remote-final-validation-" + context.id();
        jdbc.update("""
                INSERT INTO remote_feedback_validation_evidence(
                    id, remote_feedback_batch_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, repair_stage_turn_id, validation_pass_id,
                    validation_operation_id, validation_attempt,
                    subject_head_sha, proposed_head_sha, base_sha,
                    code_fingerprint, passed, evidence, completed_at_ms)
                SELECT ?, operation.remote_feedback_batch_id,
                    operation.remote_development_stage_id, operation.task_id,
                    operation.task_epoch, operation.stage_generation,
                    ?, ?, operation.operation_id, operation.semantic_attempt,
                    ?, operation.expected_head_sha, operation.expected_base_sha,
                    operation.code_fingerprint, 1, ?, operation.completed_at_ms
                FROM remote_feedback_validation_operation operation
                WHERE operation.id = ? AND operation.status = 'COMPLETED'
                """, id, attempt.repairStageTurnId(), attempt.validationPassId(),
                context.subjectHeadSha(), evidence, context.id());
        return id;
    }

    public BrainRequest insertBrain(NewBrain brain)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'REMOTE_FEEDBACK_BRAIN_REVIEW', 'REQUESTED', ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, brain.turnId(), brain.taskId(), brain.operationId(),
                brain.attempt(), brain.taskEpoch(), brain.stageId(),
                brain.stageGeneration(), brain.codeFingerprint(), brain.headSha(),
                brain.baseSha(), brain.deliveryLane(), brain.launchInput(),
                brain.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO remote_feedback_brain_episode(
                    id, remote_feedback_batch_id,
                    validation_attempt_evidence_id, task_id, task_epoch,
                    remote_development_stage_id, stage_generation, task_turn_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, brain.episodeId(), brain.batchId(),
                brain.validationAttemptEvidenceId(), brain.taskId(),
                brain.taskEpoch(), brain.stageId(), brain.stageGeneration(),
                brain.turnId(), brain.attempt(), brain.codeFingerprint(),
                brain.headSha(), brain.baseSha(),
                brain.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', ?, 'REMOTE_FEEDBACK_BRAIN_RESULT', ?,
                    0, 1, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, brain.ticketId(), brain.operationId(), brain.turnId(),
                brain.laneMask(), brain.workspaceId(), brain.trunkId(),
                brain.taskId(), brain.taskEpoch(), brain.stageId(),
                brain.stageGeneration(), brain.attempt(), brain.codeFingerprint(),
                brain.headSha(), brain.baseSha(),
                brain.requestedAt().toEpochMilli());
        return new BrainRequest(
                brain.episodeId(), brain.turnId(), brain.operationId(),
                brain.ticketId(), new ResultFence(
                        brain.taskEpoch(), brain.stageId(), brain.stageGeneration(),
                        brain.operationId(), brain.attempt(),
                        brain.codeFingerprint(), brain.headSha(), brain.baseSha()));
    }

    public String requireBrainTaskId(String turnId, String operationId)
    {
        return requireOneText("""
                SELECT episode.task_id
                FROM remote_feedback_brain_episode episode
                JOIN task_turn turn ON turn.id = episode.task_turn_id
                WHERE turn.id = ? AND turn.operation_id = ?
                """, new Object[] {turnId, operationId},
                "Remote feedback Brain owner is missing");
    }

    public BrainContext requireBrainContext(String turnId, String operationId)
    {
        List<BrainContext> rows = jdbc.query("""
                SELECT episode.id AS episode_id,
                       episode.remote_feedback_batch_id AS batch_id,
                       episode.validation_attempt_evidence_id,
                       episode.task_turn_id AS turn_id, turn.operation_id,
                       episode.semantic_attempt, episode.task_id,
                       episode.task_epoch,
                       episode.remote_development_stage_id AS stage_id,
                       episode.stage_generation, episode.code_fingerprint,
                       episode.expected_head_sha, episode.expected_base_sha,
                       task.aggregate_version AS task_version,
                       owner.version AS stage_version,
                       validation.repair_result_id,
                       validation.validation_pass_id,
                       validation.repair_stage_turn_id,
                       validation.subject_head_sha,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = episode.task_epoch
                              AND current.stage_id = episode.remote_development_stage_id
                              AND current.stage_generation = episode.stage_generation
                              AND owner.checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                              AND batch.status = 'ADDRESSING'
                              AND remote.current_head_sha = validation.subject_head_sha
                              AND remote.current_base_sha = episode.expected_base_sha
                            THEN 1 ELSE 0 END AS is_current
                FROM remote_feedback_brain_episode episode
                JOIN task_turn turn ON turn.id = episode.task_turn_id
                JOIN remote_feedback_validation_attempt_evidence validation
                  ON validation.id = episode.validation_attempt_evidence_id
                JOIN remote_feedback_batch batch
                  ON batch.id = episode.remote_feedback_batch_id
                JOIN tasks task ON task.id = episode.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = episode.remote_development_stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = episode.remote_development_stage_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND ticket.callback_route = 'REMOTE_FEEDBACK_BRAIN_RESULT'
                """, (rs, row) -> brainContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Exact Remote Brain delivery is missing");
        }
        return rows.getFirst();
    }

    public void completeBrain(
            BrainContext context, String verdict, int findings,
            String evidence, Instant at)
    {
        requireTransaction();
        int turn = jdbc.update("""
                UPDATE task_turn SET status = 'SUCCEEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, at.toEpochMilli(), context.turnId(), context.operationId());
        int episode = jdbc.update("""
                UPDATE remote_feedback_brain_episode
                SET status = 'SUCCEEDED', verdict = ?,
                    unresolved_finding_count = ?, evidence = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, verdict, findings, evidence, at.toEpochMilli(),
                context.episodeId());
        if (turn != 1 || episode != 1) {
            throw new IllegalStateException("Remote Brain verdict changed before delivery");
        }
    }

    public void supersedeBrain(BrainContext context, String error, Instant at)
    {
        requireTransaction();
        jdbc.update("""
                UPDATE task_turn SET status = 'SUPERSEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, at.toEpochMilli(), error, context.turnId());
        jdbc.update("""
                UPDATE remote_feedback_brain_episode
                SET status = 'SUPERSEDED', completed_at_ms = ?, evidence = NULL
                WHERE id = ? AND status = 'REQUESTED'
                """, at.toEpochMilli(), context.episodeId());
    }

    public String insertFinalBrain(
            BrainContext context, String evidence, Instant completedAt)
    {
        requireTransaction();
        String validationId = "remote-final-validation-" +
                requireOneText("""
                        SELECT validation_operation_id
                        FROM remote_feedback_validation_attempt_evidence
                        WHERE id = ?
                        """, context.validationAttemptEvidenceId(),
                        "Final Remote validation attempt is missing");
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_validation_evidence
                WHERE id = ?
                """, Integer.class, validationId) == 0) {
            jdbc.update("""
                    INSERT INTO remote_feedback_validation_evidence(
                        id, remote_feedback_batch_id,
                        remote_development_stage_id, task_id, task_epoch,
                        stage_generation, repair_stage_turn_id, validation_pass_id,
                        validation_operation_id, validation_attempt,
                        subject_head_sha, proposed_head_sha, base_sha,
                        code_fingerprint, passed, evidence, completed_at_ms)
                    SELECT ?, validation.remote_feedback_batch_id,
                        validation.remote_development_stage_id, validation.task_id,
                        validation.task_epoch, validation.stage_generation,
                        validation.repair_stage_turn_id, validation.validation_pass_id,
                        operation.operation_id, validation.semantic_attempt,
                        validation.subject_head_sha, validation.proposed_head_sha,
                        validation.base_sha, validation.code_fingerprint,
                        1, validation.evidence, validation.completed_at_ms
                    FROM remote_feedback_validation_attempt_evidence validation
                    JOIN remote_feedback_validation_operation operation
                      ON operation.id = validation.validation_operation_id
                    WHERE validation.id = ? AND validation.passed = 1
                    """, validationId, context.validationAttemptEvidenceId());
        }
        String brainId = "remote-final-brain-" + context.episodeId();
        jdbc.update("""
                INSERT INTO remote_feedback_brain_review_evidence(
                    id, remote_feedback_batch_id, validation_evidence_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, task_turn_id, proposed_head_sha, base_sha,
                    code_fingerprint, verdict, unresolved_finding_count,
                    evidence, completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', 0, ?, ?)
                """, brainId, context.batchId(), validationId, context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.turnId(), context.headSha(), context.baseSha(),
                context.codeFingerprint(), evidence, completedAt.toEpochMilli());
        return brainId;
    }

    public void moveAwaitingApproval(String batchId)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE remote_feedback_batch SET status = 'AWAITING_APPROVAL'
                WHERE id = ? AND status = 'ADDRESSING'
                """, batchId);
        if (changed != 1) {
            throw new IllegalStateException("Remote feedback gate changed");
        }
    }

    private String requireOneText(String sql, Object argument, String error)
    {
        return requireOneText(sql, new Object[] {argument}, error);
    }

    private String requireOneText(String sql, Object[] arguments, String error)
    {
        List<String> rows = jdbc.query(sql, (rs, row) -> rs.getString(1), arguments);
        if (rows.size() != 1) {
            throw new IllegalStateException(error);
        }
        return rows.getFirst();
    }

    private static FeedbackContext feedbackContext(ResultSet rs)
            throws SQLException
    {
        return new FeedbackContext(
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("checkpoint"), rs.getString("batch_id"),
                rs.getInt("sequence"), rs.getString("source_snapshot_id"),
                rs.getString("content_digest"),
                rs.getBoolean("brain_review_required"),
                rs.getString("batch_status"), rs.getString("subject_head_sha"),
                rs.getString("base_sha"), rs.getString("code_fingerprint"),
                rs.getString("local_head_sha"), rs.getString("worktree_path"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"), List.of());
    }

    private static StageTurnContext stageTurnContext(ResultSet rs)
            throws SQLException
    {
        return new StageTurnContext(
                rs.getString("request_id"), rs.getString("batch_id"),
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getInt("semantic_attempt"),
                rs.getString("predecessor_turn_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("subject_head_sha"), rs.getString("base_sha"),
                rs.getBoolean("brain_review_required"),
                rs.getString("worktree_path"), rs.getString("turn_status"),
                rs.getString("ticket_status"), rs.getBoolean("is_current"));
    }

    private static ValidationContext validationContext(ResultSet rs)
            throws SQLException
    {
        return new ValidationContext(
                rs.getString("id"), rs.getString("operation_id"),
                rs.getString("repair_result_id"),
                rs.getString("repair_stage_turn_id"), rs.getInt("semantic_attempt"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getString("subject_head_sha"),
                rs.getString("code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("worktree_path"), rs.getString("status"),
                rs.getString("ticket_status"), rs.getBoolean("is_current"));
    }

    private static BrainContext brainContext(ResultSet rs)
            throws SQLException
    {
        return new BrainContext(
                rs.getString("episode_id"), rs.getString("batch_id"),
                rs.getString("validation_attempt_evidence_id"),
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getInt("semantic_attempt"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getLong("task_version"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getLong("stage_version"), rs.getString("code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("subject_head_sha"),
                rs.getString("repair_result_id"),
                rs.getLong("validation_pass_id"),
                rs.getString("repair_stage_turn_id"), rs.getBoolean("is_current"));
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Remote feedback store requires a transaction");
        }
    }

    public record BatchItem(
            int ordinal, String inboxItemId, long externalRevision, String kind,
            String body, String bodyDigest, String externalTarget) {}

    public record FeedbackContext(
            String workspaceId, String trunkId, String taskId, long taskEpoch,
            long taskVersion, String stageId, long stageGeneration,
            long stageVersion, String checkpoint, String batchId, int sequence,
            String sourceSnapshotId, String contentDigest,
            boolean brainReviewRequired, String batchStatus,
            String subjectHeadSha, String baseSha, String codeFingerprint,
            String localHeadSha, String worktreePath, String workModelSnapshot,
            String provider, String model, String roleSkill, List<BatchItem> items)
    {
        private FeedbackContext withItems(List<BatchItem> items)
        {
            return new FeedbackContext(
                    workspaceId, trunkId, taskId, taskEpoch, taskVersion, stageId,
                    stageGeneration, stageVersion, checkpoint, batchId, sequence,
                    sourceSnapshotId, contentDigest, brainReviewRequired,
                    batchStatus, subjectHeadSha, baseSha, codeFingerprint,
                    localHeadSha, worktreePath, workModelSnapshot, provider, model,
                    roleSkill, List.copyOf(items));
        }
    }

    public record NewTurn(
            String requestId, String batchId, String turnId, String operationId,
            String ticketId, int attempt, String predecessorTurnId,
            String workspaceId, String trunkId, String taskId, long taskEpoch,
            String stageId, long stageGeneration, String codeFingerprint,
            String headSha, String baseSha, String deliveryLane, int laneMask,
            String launchInput, String promptDigest, String requestedBy,
            Instant requestedAt) {}

    public record TurnRequest(
            String requestId, String turnId, String operationId,
            String ticketId, int attempt) {}

    public record StageTurnContext(
            String requestId, String batchId, String turnId, String operationId,
            int semanticAttempt, String predecessorTurnId, String taskId,
            long taskEpoch, String stageId, long stageGeneration,
            String codeFingerprint, String headSha, String subjectHeadSha,
            String baseSha, boolean brainReviewRequired, String worktreePath,
            String turnStatus, String ticketStatus, boolean current)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    semanticAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record ReplyDraft(
            String id, int ordinal, int batchItemOrdinal, String kind,
            String body, String bodyDigest, String externalTarget) {}

    public record ValidationRequest(
            String operationId, String ticketId, ResultFence fence) {}

    public record ValidationContext(
            String id, String operationId, String repairResultId,
            String repairStageTurnId, int semanticAttempt, String taskId,
            long taskEpoch, String stageId, long stageGeneration,
            String subjectHeadSha, String codeFingerprint, String headSha,
            String baseSha, String worktreePath, String status,
            String ticketStatus, boolean current)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    semanticAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record ValidationAttempt(
            String id, long validationPassId, boolean passed, int semanticAttempt,
            String repairResultId, String repairStageTurnId) {}

    public record NewBrain(
            String episodeId, String batchId, String validationAttemptEvidenceId,
            String turnId, String operationId, String ticketId, int attempt,
            String workspaceId, String trunkId, String taskId, long taskEpoch,
            String stageId, long stageGeneration, String codeFingerprint,
            String headSha, String baseSha, String deliveryLane, int laneMask,
            String launchInput, Instant requestedAt) {}

    public record BrainRequest(
            String episodeId, String turnId, String operationId,
            String ticketId, ResultFence fence) {}

    public record BrainContext(
            String episodeId, String batchId, String validationAttemptEvidenceId,
            String turnId, String operationId, int semanticAttempt,
            String taskId, long taskEpoch, long taskVersion, String stageId,
            long stageGeneration, long stageVersion, String codeFingerprint,
            String headSha, String baseSha, String subjectHeadSha,
            String repairResultId, long validationPassId,
            String repairStageTurnId, boolean current)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    semanticAttempt, codeFingerprint, headSha, baseSha);
        }
    }
}
