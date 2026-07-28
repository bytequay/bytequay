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
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact persistence boundary for Local Development Turns and validation. */
@Repository
public class SqliteLocalDevelopmentRuntimeStore
{
    private final JdbcTemplate jdbc;

    public SqliteLocalDevelopmentRuntimeStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Optional<InitialImplementationReceipt> findInitialReceipt(
            String taskId, String stageId, String approvalId)
    {
        return jdbc.query("""
                SELECT local_development_stage_id, task_id, plan_approval_id,
                       stage_turn_request_id, stage_turn_id, operation_id,
                       ticket_id, recorded_at_ms
                FROM local_initial_implementation_receipt
                WHERE task_id = ? AND local_development_stage_id = ?
                  AND plan_approval_id = ?
                """, (rs, row) -> initialReceipt(rs), taskId, stageId, approvalId)
                .stream().findFirst();
    }

    public InitialContext requireInitialContext(
            String taskId, String stageId, String approvalId)
    {
        List<InitialContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.epoch AS task_epoch,
                       task.aggregate_version AS task_version,
                       local.stage_id, local.generation AS stage_generation,
                       stage.version AS stage_version, stage.checkpoint,
                       code.code_fingerprint, code.head_sha, code.base_sha,
                       identity.worktree_path, context.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill,
                       approval.id AS approval_id, revision.id AS revision_id,
                       revision.content AS plan_content,
                       revision.content_digest AS plan_digest
                FROM local_development_stage local
                JOIN stage ON stage.id = local.stage_id
                JOIN tasks task ON task.id = local.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN plan_approval approval ON approval.id = ?
                JOIN plan_revision revision ON revision.id = approval.plan_revision_id
                JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                JOIN stage plan_owner ON plan_owner.id = plan.stage_id
                WHERE task.id = ? AND local.stage_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND local.opened_for_epoch = task.epoch
                  AND current.stage_id = local.stage_id
                  AND current.stage_generation = local.generation
                  AND stage.kind = 'LOCAL_DEVELOPMENT'
                  AND stage.version = 0 AND stage.checkpoint = 'IMPLEMENTING'
                  AND stage.completed_at_ms IS NULL
                  AND plan.task_id = task.id
                  AND plan_owner.completed_at_ms IS NOT NULL
                  AND plan_owner.end_reason = 'NORMAL'
                """, (rs, row) -> initialContext(rs), approvalId, taskId, stageId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact initial Local context, found " + rows.size());
        }
        return rows.getFirst();
    }

    public void insertInitialTurn(InitialTurn turn)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'IMPLEMENT_LOCAL_PLAN', 'QUEUED', ?, 1, ?,
                    ?, ?, ?, ?, ?, ?)
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.taskEpoch(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha(), turn.deliveryLane(),
                turn.launchInput(), turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    prompt_digest, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'IMPLEMENTATION', 'IMMEDIATE',
                    NULL, NULL, NULL, ?, ?, ?)
                """, turn.requestId(), turn.commandId(), turn.turnId(),
                turn.taskId(), turn.stageId(), turn.taskEpoch(),
                turn.stageGeneration(), turn.promptDigest(), turn.requestedBy(),
                turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN',
                    'STAGE_TURN', ?, 'STAGE_TURN_RESULT', ?, 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.laneMask(), turn.workspaceId(), turn.trunkId(), turn.taskId(),
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.requestedAt().toEpochMilli());
    }

    public void insertInitialReceipt(InitialImplementationReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO local_initial_implementation_receipt(
                    local_development_stage_id, task_id, plan_approval_id,
                    stage_turn_request_id, stage_turn_id, operation_id,
                    ticket_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.stageId(), receipt.taskId(), receipt.approvalId(),
                receipt.requestId(), receipt.turnId(), receipt.operationId(),
                receipt.ticketId(), receipt.recordedAt().toEpochMilli());
    }

    public String requireStageTurnTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT request.task_id
                FROM local_stage_turn_request request
                JOIN stage_turn turn ON turn.id = request.stage_turn_id
                WHERE turn.id = ? AND turn.operation_id = ?
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Local StageTurn owner is missing");
        }
        return rows.getFirst();
    }

    public Optional<StageTurnDeliveryReceipt> findStageTurnReceipt(String turnId)
    {
        return jdbc.query("""
                SELECT stage_turn_id, operation_id, raw_outcome,
                       raw_result_digest, acceptance, dev_report_id,
                       validation_operation_id, recorded_at_ms
                FROM local_stage_turn_delivery_receipt
                WHERE stage_turn_id = ?
                """, (rs, row) -> stageTurnReceipt(rs), turnId)
                .stream().findFirst();
    }

    public StageTurnContext requireStageTurnContext(String turnId, String operationId)
    {
        List<StageTurnContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, request.task_id,
                       turn.operation_id, turn.status AS turn_status,
                       turn.task_epoch, turn.stage_id, turn.stage_generation,
                       turn.expected_code_fingerprint, turn.expected_head_sha,
                       turn.expected_base_sha, request.id AS request_id,
                       request.kind AS request_kind, request.queue_mode,
                       request.brain_review_episode_id,
                       request.local_feedback_batch_id,
                       owner.checkpoint, owner.version AS stage_version,
                       owner.completed_at_ms, task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       task.aggregate_version AS task_version,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       identity.worktree_path, ticket.id AS ticket_id,
                       ticket.status AS ticket_status,
                       trunk.workspace_id, task.thread_id AS trunk_id
                FROM stage_turn turn
                JOIN local_stage_turn_request request
                  ON request.stage_turn_id = turn.id
                JOIN stage owner ON owner.id = turn.stage_id
                JOIN tasks task ON task.id = owner.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'STAGE_TURN_RESULT'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> stageTurnContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Local StageTurn delivery, found " + rows.size());
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
            throw new IllegalStateException("Local StageTurn changed before delivery");
        }
    }

    public DevReport insertDevReport(
            StageTurnContext context,
            DevelopmentReport report,
            CodeSubject output,
            Instant at)
    {
        requireTransaction();
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM dev_report
                WHERE workflow_version = 'V2'
                  AND local_development_stage_id = ?
                """, Integer.class, context.stageId());
        int revision = requireNonNull(next, "next DevReport revision is null");
        String id = PlanRuntimeCoordinator.id(
                "local-dev-report", context.turnId());
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    source_code_fingerprint, source_head_sha, source_base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs)
                VALUES (?, ?, ?, ?, 'V2', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?)
                """, id, context.taskId(), report.implementedIntent(),
                at.toEpochMilli(), context.stageId(), context.taskEpoch(),
                context.stageGeneration(), context.turnId(), revision,
                output.codeFingerprint(), output.headSha(), output.baseSha(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                report.implementedIntent(), report.commitSummary(), report.fileSummary(),
                report.validationSummary(), report.knownRisks(),
                report.unresolvedConcerns(), report.contextRefs());
        return new DevReport(id, revision, output);
    }

    public ValidationRequest insertValidation(
            StageTurnContext context, DevReport report, Instant at)
    {
        requireTransaction();
        String operationId = PlanRuntimeCoordinator.id(
                "local-validation-operation", report.id());
        String ticketId = PlanRuntimeCoordinator.id(
                "local-validation-ticket", report.id());
        jdbc.update("""
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, operationId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), report.id(),
                operationId, report.output().codeFingerprint(),
                report.output().headSha(), report.output().baseSha(),
                at.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION',
                    'STAGE', ?, 'STAGE_VALIDATION_RESULT', 4, 0, 1, 0,
                    ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, context.stageId(),
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                report.output().codeFingerprint(), report.output().headSha(),
                report.output().baseSha(), at.toEpochMilli());
        int changed = jdbc.update("""
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                """, operationId, operationId);
        if (changed != 1) {
            throw new IllegalStateException("Validation operation changed before dispatch");
        }
        return new ValidationRequest(
                operationId, ticketId, new ResultFence(
                        context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), operationId, 1,
                        report.output().codeFingerprint(), report.output().headSha(),
                        report.output().baseSha()));
    }

    public void insertStageTurnReceipt(StageTurnDeliveryReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO local_stage_turn_delivery_receipt(
                    stage_turn_id, operation_id, raw_outcome, raw_result_digest,
                    acceptance, dev_report_id, validation_operation_id,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.turnId(), receipt.operationId(), receipt.rawOutcome(),
                receipt.rawResultDigest(), receipt.acceptance(),
                receipt.devReportId(), receipt.validationOperationId(),
                receipt.recordedAt().toEpochMilli());
    }

    public String requireValidationTaskId(String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT task_id FROM validation_operation WHERE operation_id = ?
                """, (rs, row) -> rs.getString(1), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Local Validation owner is missing");
        }
        return rows.getFirst();
    }

    public ValidationContext requireValidationContext(String operationId)
    {
        List<ValidationContext> rows = jdbc.query("""
                SELECT operation.id AS validation_operation_id,
                       operation.operation_id, operation.status,
                       operation.semantic_attempt, operation.task_id,
                       operation.task_epoch,
                       operation.local_development_stage_id AS stage_id,
                       operation.stage_generation, operation.dev_report_id,
                       operation.code_fingerprint, operation.expected_head_sha,
                       operation.expected_base_sha, report.implemented_intent,
                       report.commit_summary, report.file_summary,
                       report.validation_summary, report.known_risks,
                       report.unresolved_concerns, report.context_refs,
                       task.lifecycle_state, task.epoch AS current_task_epoch,
                       task.aggregate_version AS task_version,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.checkpoint, owner.version AS stage_version,
                       owner.completed_at_ms, code.code_fingerprint AS current_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       identity.worktree_path, task.thread_id AS trunk_id,
                       trunk.workspace_id, context.work_model_snapshot,
                       brain.id AS task_brain_id, brain.provider, brain.model,
                       brain.role_skill, ticket.id AS ticket_id,
                       ticket.status AS ticket_status
                FROM validation_operation operation
                JOIN dev_report report ON report.id = operation.dev_report_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN stage owner ON owner.id = operation.local_development_stage_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.operation_id = ?
                  AND ticket.owner_kind = 'STAGE'
                  AND ticket.owner_id = operation.local_development_stage_id
                  AND ticket.callback_route = 'STAGE_VALIDATION_RESULT'
                """, (rs, row) -> validationContext(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Local Validation, found " + rows.size());
        }
        return rows.getFirst();
    }

    public Optional<ValidationDeliveryReceipt> findValidationReceipt(
            String validationOperationId)
    {
        return jdbc.query("""
                SELECT validation_operation_id, operation_id, raw_outcome,
                       raw_result_digest, acceptance, validation_evidence_id,
                       brain_review_episode_id, recorded_at_ms
                FROM local_validation_delivery_receipt
                WHERE validation_operation_id = ?
                """, (rs, row) -> validationReceipt(rs), validationOperationId)
                .stream().findFirst();
    }

    public void finishValidationWithoutEvidence(
            ValidationContext context, String status, String error, Instant at)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE validation_operation
                SET status = ?, completed_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                """, status, at.toEpochMilli(), error,
                context.validationOperationId(), context.operationId());
        if (changed != 1) {
            throw new IllegalStateException("Validation changed before completion");
        }
    }

    public ValidationEvidence completeValidation(
            ValidationContext context,
            boolean passed,
            String failuresJson,
            String evidenceJson,
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
        String evidenceId = PlanRuntimeCoordinator.id(
                "local-validation-evidence", context.operationId());
        jdbc.update("""
                INSERT INTO validation_evidence(
                    id, validation_operation_id, validation_pass_id, task_id,
                    task_epoch, stage_id, stage_generation, code_fingerprint,
                    head_sha, base_sha, passed, failures_digest, evidence,
                    completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, evidenceId, context.validationOperationId(),
                requireNonNull(passId, "validation pass id is null"),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), passed ? 1 : 0,
                passed ? null : PlanRuntimeCoordinator.digest(failuresJson),
                evidenceJson, completedAt.toEpochMilli());
        int changed = jdbc.update("""
                UPDATE validation_operation
                SET status = 'COMPLETED', completed_at_ms = ?, error_message = NULL
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                """, completedAt.toEpochMilli(), context.validationOperationId(),
                context.operationId());
        if (changed != 1) {
            throw new IllegalStateException("Validation changed before completion");
        }
        return new ValidationEvidence(evidenceId, passId, passed);
    }

    public BrainReviewRequest insertBrainReview(
            ValidationContext context,
            ValidationEvidence evidence,
            String turnId,
            String operationId,
            String ticketId,
            String episodeId,
            String deliveryLane,
            int laneMask,
            String launchInput,
            Instant requestedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'DEVELOPMENT_BRAIN_REVIEW', 'QUEUED', ?, 1, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?)
                """, turnId, context.taskId(), operationId, context.taskEpoch(),
                context.stageId(), context.stageGeneration(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                deliveryLane, launchInput, requestedAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, episodeId, context.taskBrainId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.devReportId(), evidence.id(), turnId,
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                requestedAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', ?, 'TASK_TURN_RESULT', ?, 0, 1, 0,
                    ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, turnId, laneMask,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                requestedAt.toEpochMilli());
        return new BrainReviewRequest(
                episodeId, turnId, operationId, ticketId,
                new ResultFence(
                        context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), operationId, 1,
                        context.codeFingerprint(), context.headSha(),
                        context.baseSha()));
    }

    public void insertValidationReceipt(ValidationDeliveryReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO local_validation_delivery_receipt(
                    validation_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, validation_evidence_id,
                    brain_review_episode_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.validationOperationId(), receipt.operationId(),
                receipt.rawOutcome(), receipt.rawResultDigest(), receipt.acceptance(),
                receipt.validationEvidenceId(), receipt.brainReviewEpisodeId(),
                receipt.recordedAt().toEpochMilli());
    }

    public String requireBrainTurnTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT episode.task_id
                FROM brain_review_episode episode
                JOIN task_turn turn ON turn.id = episode.task_turn_id
                WHERE turn.id = ? AND turn.operation_id = ?
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Development Brain owner is missing");
        }
        return rows.getFirst();
    }

    public BrainTurnContext requireBrainTurnContext(String turnId, String operationId)
    {
        List<BrainTurnContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       turn.status AS turn_status, turn.task_id, turn.task_epoch,
                       turn.trigger_stage_id AS stage_id,
                       turn.trigger_stage_generation AS stage_generation,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       episode.id AS episode_id, episode.status AS episode_status,
                       episode.dev_report_id, episode.validation_evidence_id,
                       episode.semantic_attempt, task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       task.aggregate_version AS task_version,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.checkpoint, owner.version AS stage_version,
                       owner.completed_at_ms, code.code_fingerprint AS current_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       identity.worktree_path, task.thread_id AS trunk_id,
                       trunk.workspace_id, creation.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill,
                       policy.max_brain_rounds, ticket.id AS ticket_id,
                       ticket.status AS ticket_status
                FROM task_turn turn
                JOIN brain_review_episode episode ON episode.task_turn_id = turn.id
                JOIN tasks task ON task.id = turn.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN stage owner ON owner.id = turn.trigger_stage_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context creation ON creation.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> brainTurnContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Development Brain delivery, found " + rows.size());
        }
        return rows.getFirst();
    }

    public Optional<BrainTurnDeliveryReceipt> findBrainTurnReceipt(String turnId)
    {
        return jdbc.query("""
                SELECT task_turn_id, operation_id, raw_outcome,
                       raw_result_digest, acceptance, brain_review_episode_id,
                       verdict, blocker_id, next_stage_turn_request_id,
                       recorded_at_ms
                FROM local_brain_turn_delivery_receipt
                WHERE task_turn_id = ?
                """, (rs, row) -> brainTurnReceipt(rs), turnId)
                .stream().findFirst();
    }

    public void completeBrainVerdict(
            BrainTurnContext context,
            String verdict,
            int unresolvedFindingCount,
            String summary,
            Instant at)
    {
        requireTransaction();
        int turn = jdbc.update("""
                UPDATE task_turn
                SET status = 'SUCCEEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = NULL
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, at.toEpochMilli(), context.turnId(), context.operationId());
        int episode = jdbc.update("""
                UPDATE brain_review_episode
                SET status = 'SUCCEEDED', verdict = ?,
                    unresolved_finding_count = ?, verdict_summary = ?,
                    completed_at_ms = ?, error_message = NULL
                WHERE id = ? AND task_turn_id = ?
                  AND status IN ('REQUESTED', 'REVIEWING')
                """, verdict, unresolvedFindingCount, summary, at.toEpochMilli(),
                context.episodeId(), context.turnId());
        if (turn != 1 || episode != 1) {
            throw new IllegalStateException("Development Brain changed before verdict");
        }
    }

    public String exhaustBrainBudget(
            BrainTurnContext context, String detail, Instant at)
    {
        requireTransaction();
        int turn = jdbc.update("""
                UPDATE task_turn
                SET status = 'FAILED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, at.toEpochMilli(), detail, context.turnId(), context.operationId());
        int episode = jdbc.update("""
                UPDATE brain_review_episode
                SET status = 'BUDGET_EXHAUSTED', completed_at_ms = ?,
                    error_message = ?
                WHERE id = ? AND task_turn_id = ?
                  AND status IN ('REQUESTED', 'REVIEWING')
                """, at.toEpochMilli(), detail, context.episodeId(), context.turnId());
        if (turn != 1 || episode != 1) {
            throw new IllegalStateException(
                    "Development Brain changed before budget exhaustion");
        }
        String blockerId = PlanRuntimeCoordinator.id(
                "brain-budget-blocker", context.episodeId());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?,
                    'BRAIN_BUDGET_EXHAUSTED', 'OPEN', ?, ?)
                """, blockerId, context.taskId(), context.stageId(),
                context.stageId(), context.episodeId(), detail,
                at.toEpochMilli());
        return blockerId;
    }

    public void insertBrainFixTurn(BrainFixTurn turn)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'ADDRESS_BRAIN_FINDINGS', 'QUEUED', ?, 1, ?,
                    ?, ?, ?, ?, ?, ?)
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.taskEpoch(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha(), turn.deliveryLane(),
                turn.launchInput(), turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    prompt_digest, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BRAIN_FINDINGS', 'IMMEDIATE',
                    NULL, ?, NULL, ?, ?, ?)
                """, turn.requestId(), turn.commandId(), turn.turnId(),
                turn.taskId(), turn.stageId(), turn.taskEpoch(),
                turn.stageGeneration(), turn.episodeId(), turn.promptDigest(),
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
                    'STAGE_TURN', ?, 'STAGE_TURN_RESULT', ?, 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.laneMask(), turn.workspaceId(), turn.trunkId(), turn.taskId(),
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.requestedAt().toEpochMilli());
    }

    public void insertBrainTurnReceipt(BrainTurnDeliveryReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO local_brain_turn_delivery_receipt(
                    task_turn_id, operation_id, raw_outcome, raw_result_digest,
                    acceptance, brain_review_episode_id, verdict, blocker_id,
                    next_stage_turn_request_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.turnId(), receipt.operationId(), receipt.rawOutcome(),
                receipt.rawResultDigest(), receipt.acceptance(), receipt.episodeId(),
                receipt.verdict(), receipt.blockerId(), receipt.nextRequestId(),
                receipt.recordedAt().toEpochMilli());
    }

    private static InitialContext initialContext(ResultSet rs)
            throws SQLException
    {
        return new InitialContext(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("worktree_path"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("approval_id"), rs.getString("revision_id"),
                rs.getString("plan_content"), rs.getString("plan_digest"));
    }

    private static InitialImplementationReceipt initialReceipt(ResultSet rs)
            throws SQLException
    {
        return new InitialImplementationReceipt(
                rs.getString("task_id"),
                rs.getString("local_development_stage_id"),
                rs.getString("plan_approval_id"),
                rs.getString("stage_turn_request_id"),
                rs.getString("stage_turn_id"), rs.getString("operation_id"),
                rs.getString("ticket_id"), instant(rs, "recorded_at_ms"));
    }

    private static StageTurnContext stageTurnContext(ResultSet rs)
            throws SQLException
    {
        return new StageTurnContext(
                rs.getString("turn_id"), rs.getString("task_id"),
                rs.getString("operation_id"),
                rs.getString("turn_status"), rs.getString("request_id"),
                rs.getString("request_kind"), rs.getString("queue_mode"),
                rs.getString("brain_review_episode_id"),
                rs.getString("local_feedback_batch_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getLong("stage_version"),
                nullableLong(rs, "completed_at_ms") != null,
                rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"), rs.getLong("task_version"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                rs.getString("current_code_fingerprint"),
                rs.getString("current_head_sha"), rs.getString("current_base_sha"),
                rs.getString("worktree_path"), rs.getString("ticket_id"),
                rs.getString("ticket_status"), rs.getString("workspace_id"),
                rs.getString("trunk_id"));
    }

    private static StageTurnDeliveryReceipt stageTurnReceipt(ResultSet rs)
            throws SQLException
    {
        return new StageTurnDeliveryReceipt(
                rs.getString("stage_turn_id"), rs.getString("operation_id"),
                rs.getString("raw_outcome"), rs.getString("raw_result_digest"),
                rs.getString("acceptance"), rs.getString("dev_report_id"),
                rs.getString("validation_operation_id"),
                instant(rs, "recorded_at_ms"));
    }

    private static ValidationContext validationContext(ResultSet rs)
            throws SQLException
    {
        return new ValidationContext(
                rs.getString("validation_operation_id"),
                rs.getString("operation_id"), rs.getString("status"),
                rs.getInt("semantic_attempt"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getString("dev_report_id"),
                rs.getString("code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("implemented_intent"),
                rs.getString("commit_summary"), rs.getString("file_summary"),
                rs.getString("validation_summary"), rs.getString("known_risks"),
                rs.getString("unresolved_concerns"), rs.getString("context_refs"),
                rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"), rs.getLong("task_version"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getLong("stage_version"),
                nullableLong(rs, "completed_at_ms") != null,
                rs.getString("current_fingerprint"),
                rs.getString("current_head_sha"), rs.getString("current_base_sha"),
                rs.getString("worktree_path"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getString("work_model_snapshot"),
                rs.getString("task_brain_id"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("ticket_id"), rs.getString("ticket_status"));
    }

    private static ValidationDeliveryReceipt validationReceipt(ResultSet rs)
            throws SQLException
    {
        return new ValidationDeliveryReceipt(
                rs.getString("validation_operation_id"),
                rs.getString("operation_id"), rs.getString("raw_outcome"),
                rs.getString("raw_result_digest"), rs.getString("acceptance"),
                rs.getString("validation_evidence_id"),
                rs.getString("brain_review_episode_id"),
                instant(rs, "recorded_at_ms"));
    }

    private static BrainTurnContext brainTurnContext(ResultSet rs)
            throws SQLException
    {
        return new BrainTurnContext(
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getString("turn_status"), rs.getString("episode_id"),
                rs.getString("episode_status"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("dev_report_id"),
                rs.getString("validation_evidence_id"),
                rs.getInt("semantic_attempt"), rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"), rs.getLong("task_version"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getLong("stage_version"),
                nullableLong(rs, "completed_at_ms") != null,
                rs.getString("current_fingerprint"),
                rs.getString("current_head_sha"), rs.getString("current_base_sha"),
                rs.getString("worktree_path"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getString("work_model_snapshot"),
                rs.getString("provider"), rs.getString("model"),
                rs.getString("role_skill"), rs.getInt("max_brain_rounds"),
                rs.getString("ticket_id"), rs.getString("ticket_status"));
    }

    private static BrainTurnDeliveryReceipt brainTurnReceipt(ResultSet rs)
            throws SQLException
    {
        return new BrainTurnDeliveryReceipt(
                rs.getString("task_turn_id"), rs.getString("operation_id"),
                rs.getString("raw_outcome"), rs.getString("raw_result_digest"),
                rs.getString("acceptance"),
                rs.getString("brain_review_episode_id"), rs.getString("verdict"),
                rs.getString("blocker_id"),
                rs.getString("next_stage_turn_request_id"),
                instant(rs, "recorded_at_ms"));
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Local runtime writes require a command transaction");
        }
    }

    public record InitialContext(
            String taskId, String trunkId, String workspaceId, long taskEpoch,
            long taskVersion, String stageId, long stageGeneration,
            long stageVersion, StageCheckpoint checkpoint,
            String codeFingerprint, String headSha, String baseSha,
            String worktreePath, String workModelSnapshot, String provider,
            String model, String roleSkill, String approvalId, String revisionId,
            String planContent, String planDigest) {}

    public record InitialTurn(
            String requestId, String commandId, String turnId, String operationId,
            String ticketId, String workspaceId, String trunkId, String taskId,
            long taskEpoch, String stageId, long stageGeneration,
            String codeFingerprint, String headSha, String baseSha,
            String deliveryLane, int laneMask, String launchInput,
            String promptDigest, String requestedBy, Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record InitialImplementationReceipt(
            String taskId, String stageId, String approvalId, String requestId,
            String turnId, String operationId, String ticketId, Instant recordedAt) {}

    public record StageTurnContext(
            String turnId, String taskId, String operationId, String turnStatus,
            String requestId,
            String requestKind, String queueMode, String brainReviewEpisodeId,
            String localFeedbackBatchId, long taskEpoch, String stageId,
            long stageGeneration, String codeFingerprint, String headSha,
            String baseSha, StageCheckpoint checkpoint, long stageVersion,
            boolean stageCompleted, String taskLifecycle, long currentTaskEpoch,
            long taskVersion, String currentStageId, Long currentStageGeneration,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha, String worktreePath, String ticketId,
            String ticketStatus, String workspaceId, String trunkId)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }

        public boolean isCurrent()
        {
            return "ACTIVE".equals(taskLifecycle)
                    && currentTaskEpoch == taskEpoch
                    && stageId.equals(currentStageId)
                    && currentStageGeneration != null
                    && currentStageGeneration == stageGeneration
                    && !stageCompleted
                    && Objects.equals(codeFingerprint, currentCodeFingerprint)
                    && Objects.equals(headSha, currentHeadSha)
                    && Objects.equals(baseSha, currentBaseSha);
        }
    }

    public record DevelopmentReport(
            String implementedIntent, String commitSummary, String fileSummary,
            String validationSummary, String knownRisks,
            String unresolvedConcerns, String contextRefs) {}

    public record CodeSubject(String codeFingerprint, String headSha, String baseSha) {}

    public record DevReport(String id, int revision, CodeSubject output) {}

    public record ValidationRequest(String operationId, String ticketId, ResultFence fence) {}

    public record StageTurnDeliveryReceipt(
            String turnId, String operationId, String rawOutcome,
            String rawResultDigest, String acceptance, String devReportId,
            String validationOperationId, Instant recordedAt) {}

    public record ValidationContext(
            String validationOperationId, String operationId, String status,
            int semanticAttempt, String taskId, long taskEpoch, String stageId,
            long stageGeneration, String devReportId, String codeFingerprint,
            String headSha, String baseSha, String implementedIntent,
            String commitSummary, String fileSummary, String validationSummary,
            String knownRisks, String unresolvedConcerns, String contextRefs,
            String taskLifecycle, long currentTaskEpoch, long taskVersion,
            String currentStageId, Long currentStageGeneration,
            StageCheckpoint checkpoint, long stageVersion, boolean stageCompleted,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha, String worktreePath, String trunkId,
            String workspaceId, String workModelSnapshot, String taskBrainId,
            String provider, String model, String roleSkill, String ticketId,
            String ticketStatus)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    semanticAttempt, codeFingerprint, headSha, baseSha);
        }

        public boolean isCurrent()
        {
            return "ACTIVE".equals(taskLifecycle)
                    && currentTaskEpoch == taskEpoch
                    && stageId.equals(currentStageId)
                    && currentStageGeneration != null
                    && currentStageGeneration == stageGeneration
                    && checkpoint == StageCheckpoint.VALIDATING
                    && !stageCompleted
                    && Objects.equals(codeFingerprint, currentCodeFingerprint)
                    && Objects.equals(headSha, currentHeadSha)
                    && Objects.equals(baseSha, currentBaseSha);
        }
    }

    public record ValidationEvidence(String id, long passId, boolean passed) {}

    public record BrainReviewRequest(
            String episodeId, String turnId, String operationId,
            String ticketId, ResultFence fence) {}

    public record ValidationDeliveryReceipt(
            String validationOperationId, String operationId, String rawOutcome,
            String rawResultDigest, String acceptance, String validationEvidenceId,
            String brainReviewEpisodeId, Instant recordedAt) {}

    public record BrainTurnContext(
            String turnId, String operationId, String turnStatus,
            String episodeId, String episodeStatus, String taskId,
            long taskEpoch, String stageId, long stageGeneration,
            String codeFingerprint, String headSha, String baseSha,
            String devReportId, String validationEvidenceId, int semanticAttempt,
            String taskLifecycle, long currentTaskEpoch, long taskVersion,
            String currentStageId, Long currentStageGeneration,
            StageCheckpoint checkpoint, long stageVersion, boolean stageCompleted,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha, String worktreePath, String trunkId,
            String workspaceId, String workModelSnapshot, String provider,
            String model, String roleSkill, int maxBrainRounds,
            String ticketId, String ticketStatus)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }

        public boolean isCurrent()
        {
            return "ACTIVE".equals(taskLifecycle)
                    && currentTaskEpoch == taskEpoch
                    && stageId.equals(currentStageId)
                    && currentStageGeneration != null
                    && currentStageGeneration == stageGeneration
                    && checkpoint == StageCheckpoint.BRAIN_REVIEW
                    && !stageCompleted
                    && Objects.equals(codeFingerprint, currentCodeFingerprint)
                    && Objects.equals(headSha, currentHeadSha)
                    && Objects.equals(baseSha, currentBaseSha);
        }
    }

    public record BrainFixTurn(
            String requestId, String commandId, String turnId, String operationId,
            String ticketId, String episodeId, String workspaceId, String trunkId,
            String taskId, long taskEpoch, String stageId, long stageGeneration,
            String codeFingerprint, String headSha, String baseSha,
            String deliveryLane, int laneMask, String launchInput,
            String promptDigest, String requestedBy, Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record BrainTurnDeliveryReceipt(
            String turnId, String operationId, String rawOutcome,
            String rawResultDigest, String acceptance, String episodeId,
            String verdict, String blockerId, String nextRequestId,
            Instant recordedAt) {}
}
