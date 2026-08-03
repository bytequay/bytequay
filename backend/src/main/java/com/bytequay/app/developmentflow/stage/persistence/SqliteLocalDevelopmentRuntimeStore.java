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
                       turn.operation_id, turn.attempt,
                       turn.status AS turn_status,
                       turn.task_epoch, turn.stage_id, turn.stage_generation,
                       turn.expected_code_fingerprint, turn.expected_head_sha,
                       turn.expected_base_sha, request.id AS request_id,
                       request.kind AS request_kind, request.queue_mode,
                       request.brain_review_episode_id,
                       request.local_feedback_batch_id,
                       request.base_sync_episode_id,
                       request.target_base_sha,
                       owner.checkpoint, owner.version AS stage_version,
                       owner.completed_at_ms, task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       task.aggregate_version AS task_version,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       identity.worktree_path, turn.delivery_lane,
                       turn.launch_input, ticket.id AS ticket_id,
                       ticket.lane_mask, ticket.status AS ticket_status,
                       ticket.last_error AS ticket_last_error,
                       trunk.workspace_id, task.thread_id AS trunk_id,
                       identity.branch_name,
                       CASE
                           WHEN context.base_source IS NULL
                               THEN NULLIF(task.base_branch, '')
                           WHEN context.base_source = 'EXISTING_PR_HEAD'
                               THEN COALESCE(
                                   NULLIF(context.base_ref, ''),
                                   CASE WHEN json_valid(provision.result_evidence)
                                       AND json_extract(
                                           provision.result_evidence, '$.schema')
                                           = 'PROVISION_TASK_V2'
                                       AND json_extract(
                                           provision.result_evidence, '$.baseSource')
                                           = 'EXISTING_PR_HEAD'
                                       THEN NULLIF(json_extract(
                                           provision.result_evidence,
                                           '$.pullRequest.baseRef'), '')
                                   END)
                           ELSE NULLIF(context.base_ref, '')
                       END AS base_branch,
                       COALESCE(NULLIF(task.name, ''), identity.branch_name) AS task_name
                FROM stage_turn turn
                JOIN local_stage_turn_request request
                  ON request.stage_turn_id = turn.id
                JOIN stage owner ON owner.id = turn.stage_id
                JOIN tasks task ON task.id = owner.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN provision_task_operation provision
                  ON provision.id = identity.provision_operation_id
                 AND provision.status = 'ACCEPTED'
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

    public StageTurnFailure openStageTurnFailure(
            StageTurnContext context,
            String error,
            String payloadJson,
            long clearedStageVersion,
            Instant at)
    {
        requireTransaction();
        String failureId = PlanRuntimeCoordinator.id(
                "local-stage-turn-failure", context.turnId());
        String blockerId = requireOrCreateFailureBlocker(
                context, payloadJson, at);
        jdbc.update("""
                INSERT INTO local_stage_turn_failure_v298(
                    id, stage_turn_id, operation_id, task_id, stage_id,
                    stage_generation, blocker_id, error_message, payload_json,
                    cleared_stage_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, failureId, context.turnId(), context.operationId(),
                context.taskId(), context.stageId(), context.stageGeneration(),
                blockerId, error, payloadJson, clearedStageVersion,
                at.toEpochMilli());
        return new StageTurnFailure(
                failureId, context.taskId(), context.stageId(),
                context.stageGeneration(), context.turnId(),
                context.operationId(), blockerId, error, clearedStageVersion, at);
    }

    public Optional<StageTurnRetryReceipt> findStageTurnRetryReceipt(
            String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT retry.id, retry.task_id, retry.stage_id,
                       retry.stage_generation, retry.command_id, retry.actor,
                       retry.reason, retry.blocker_id,
                       retry.predecessor_turn_id, retry.replacement_request_id,
                       retry.replacement_turn_id,
                       replacement.operation_id AS replacement_operation_id,
                       retry.replacement_ticket_id, retry.recorded_at_ms
                  FROM local_stage_turn_retry_v298 retry
                  JOIN stage_turn replacement
                    ON replacement.id = retry.replacement_turn_id
                 WHERE retry.task_id = ? AND retry.command_id = ?
                """, (rs, row) -> stageTurnRetryReceipt(rs), taskId, commandId)
                .stream().findFirst();
    }

    public StageTurnRetryContext requireStageTurnRetryContext(
            String taskId, String failedTurnId, String blockerId)
    {
        List<StageTurnRetryContext> rows = jdbc.query("""
                SELECT failure.id AS failure_id, failure.blocker_id,
                       failure.error_message, failure.payload_json,
                       turn.id AS failed_turn_id,
                       turn.operation_id AS failed_operation_id,
                       turn.attempt AS failed_attempt, turn.purpose,
                       turn.delivery_lane, turn.launch_input,
                       turn.task_epoch, turn.stage_id, turn.stage_generation,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       request.kind AS request_kind,
                       request.brain_review_episode_id,
                       request.local_feedback_batch_id,
                       request.base_sync_episode_id,
                       request.target_base_sha,
                       ticket.id AS failed_ticket_id, ticket.lane_mask,
                       task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id,
                       owner.version AS stage_version, owner.checkpoint
                  FROM local_stage_turn_failure_v298 failure
                  JOIN stage_turn turn ON turn.id = failure.stage_turn_id
                  JOIN local_stage_turn_request request
                    ON request.stage_turn_id = turn.id
                  JOIN dispatch_ticket ticket
                    ON ticket.operation_id = turn.operation_id
                   AND ticket.owner_kind = 'STAGE_TURN'
                   AND ticket.owner_id = turn.id
                   AND ticket.callback_route = 'STAGE_TURN_RESULT'
                  JOIN local_stage_turn_delivery_receipt delivery
                    ON delivery.stage_turn_id = turn.id
                   AND delivery.operation_id = turn.operation_id
                  JOIN task_blocker blocker ON blocker.id = failure.blocker_id
                  JOIN stage owner ON owner.id = turn.stage_id
                  JOIN tasks task ON task.id = owner.task_id
                  JOIN threads trunk ON trunk.id = task.thread_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                 WHERE failure.task_id = ?
                   AND failure.stage_turn_id = ?
                   AND failure.blocker_id = ?
                   AND turn.status = 'FAILED'
                   AND delivery.acceptance = 'ACCEPTED'
                   AND delivery.raw_outcome IN ('FAILED', 'INDETERMINATE')
                   AND ticket.status = 'FAILED'
                   AND ticket.delivery_acceptance = 'ACCEPTED'
                   AND blocker.task_id = task.id
                   AND blocker.stage_id = owner.id
                   AND blocker.owner_kind = 'STAGE'
                   AND blocker.owner_id = owner.id
                   AND blocker.subject_revision = turn.id
                   AND blocker.blocker_type = 'OPERATION_FAILED'
                   AND blocker.status = 'OPEN'
                   AND owner.kind = 'LOCAL_DEVELOPMENT'
                   AND owner.generation = turn.stage_generation
                   AND owner.version = failure.cleared_stage_version
                   AND owner.completed_at_ms IS NULL
                   AND owner.end_reason IS NULL
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND task.epoch = turn.task_epoch
                   AND current.stage_id = owner.id
                   AND current.stage_generation = owner.generation
                   AND code.code_fingerprint = turn.expected_code_fingerprint
                   AND code.head_sha = turn.expected_head_sha
                   AND code.base_sha = turn.expected_base_sha
                   AND NOT EXISTS (
                       SELECT 1 FROM local_stage_turn_retry_v298 retry
                        WHERE retry.failure_id = failure.id
                           OR retry.blocker_id = blocker.id
                           OR retry.predecessor_turn_id = turn.id)
                """, (rs, row) -> stageTurnRetryContext(rs),
                taskId, failedTurnId, blockerId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact failed Local StageTurn, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public List<String> executionLog(String ticketId)
    {
        return jdbc.query("""
                SELECT log.payload
                  FROM agent_execution execution
                  JOIN agent_execution_log log
                    ON log.execution_id = execution.id
                 WHERE execution.ticket_id = ?
                 ORDER BY execution.infrastructure_attempt, log.seq
                """, (rs, row) -> rs.getString("payload"), ticketId);
    }

    private String requireOrCreateFailureBlocker(
            StageTurnContext context, String payloadJson, Instant at)
    {
        List<String> blockers = jdbc.query("""
                SELECT id
                  FROM task_blocker
                 WHERE task_id = ? AND stage_id = ?
                   AND owner_kind = 'STAGE' AND owner_id = ?
                   AND subject_revision = ?
                   AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, (rs, row) -> rs.getString("id"), context.taskId(),
                context.stageId(), context.stageId(), context.turnId());
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM task_blocker
                 WHERE task_id = ? AND stage_id = ?
                   AND owner_kind = 'STAGE' AND owner_id = ?
                   AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, Integer.class, context.taskId(), context.stageId(),
                context.stageId());
        if (open == null || open > 1 || (open == 1 && blockers.size() != 1)) {
            throw new IllegalStateException(
                    "Local Stage already owns another failed operation");
        }
        if (open == 1) {
            return blockers.getFirst();
        }
        String blockerId = PlanRuntimeCoordinator.id(
                "local-stage-turn-failure-blocker", context.turnId());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?, 'OPERATION_FAILED', 'OPEN',
                    ?, ?)
                """, blockerId, context.taskId(), context.stageId(),
                context.stageId(), context.turnId(), payloadJson,
                at.toEpochMilli());
        return blockerId;
    }

    public void insertStageTurnRetry(StageTurnRetry turn)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.purpose(), turn.operationId(), turn.attempt(),
                turn.taskEpoch(), turn.codeFingerprint(), turn.headSha(),
                turn.baseSha(), turn.deliveryLane(), turn.launchInput(),
                turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    base_sync_episode_id, target_base_sha,
                    prompt_digest, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IMMEDIATE', NULL,
                    ?, ?, ?, ?, ?, ?, ?)
                """, turn.requestId(), turn.requestCommandId(), turn.turnId(),
                turn.taskId(), turn.stageId(), turn.taskEpoch(),
                turn.stageGeneration(), turn.requestKind(),
                turn.brainReviewEpisodeId(), turn.localFeedbackBatchId(),
                turn.baseSyncEpisodeId(), turn.targetBaseSha(),
                turn.promptDigest(), turn.requestedBy(),
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
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.laneMask(), turn.workspaceId(), turn.trunkId(),
                turn.taskId(), turn.taskEpoch(), turn.stageId(),
                turn.stageGeneration(), turn.attempt(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha(), turn.requestedAt().toEpochMilli());
    }

    public StageTurnRetryReceipt recordStageTurnRetry(
            StageTurnRetryContext context,
            StageTurnRetry turn,
            String commandId,
            String actor,
            String reason,
            long expectedStageVersion,
            long returnedStageVersion,
            Instant at)
    {
        requireTransaction();
        String retryId = PlanRuntimeCoordinator.id(
                "local-stage-turn-retry", context.failureId() + ":" + commandId);
        jdbc.update("""
                INSERT INTO local_stage_turn_retry_v298(
                    id, task_id, stage_id, stage_generation, command_id, actor,
                    reason, blocker_id, failure_id, predecessor_turn_id,
                    replacement_request_id, replacement_turn_id,
                    replacement_ticket_id, expected_stage_version,
                    returned_stage_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, retryId, context.taskId(), context.stageId(),
                context.stageGeneration(), commandId, actor, reason,
                context.blockerId(), context.failureId(), context.failedTurnId(),
                turn.requestId(), turn.turnId(), turn.ticketId(),
                expectedStageVersion, returnedStageVersion, at.toEpochMilli());
        int resolved = jdbc.update("""
                UPDATE task_blocker
                   SET status = 'RESOLVED', resolved_at_ms = ?,
                       resolution_evidence = ?
                 WHERE id = ? AND task_id = ? AND stage_id = ?
                   AND owner_kind = 'STAGE' AND owner_id = ?
                   AND subject_revision = ?
                   AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, at.toEpochMilli(),
                "retry command " + commandId + " admitted StageTurn "
                        + turn.turnId() + " operation " + turn.operationId()
                        + ": " + reason,
                context.blockerId(), context.taskId(), context.stageId(),
                context.stageId(), context.failedTurnId());
        if (resolved != 1) {
            throw new IllegalStateException(
                    "Local StageTurn failure blocker changed before retry");
        }
        return new StageTurnRetryReceipt(
                retryId, context.taskId(), context.stageId(),
                context.stageGeneration(), commandId, actor, reason,
                context.blockerId(), context.failedTurnId(), turn.requestId(),
                turn.turnId(), turn.operationId(), turn.ticketId(), at);
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
                FROM task_turn turn
                LEFT JOIN task_turn_user_wait_continuation_v266 continuation
                  ON continuation.successor_turn_id = turn.id
                JOIN brain_review_episode episode
                  ON episode.task_turn_id = COALESCE(
                      continuation.logical_turn_id, turn.id)
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN (
                      'DEVELOPMENT_BRAIN_REVIEW',
                      'DEVELOPMENT_BRAIN_RESULT_REPAIR')
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Development Brain owner is missing");
        }
        return rows.getFirst();
    }

    public BrainTurnContext requireBrainTurnContext(String turnId, String operationId)
    {
        List<BrainTurnContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, turn.purpose, turn.operation_id,
                       turn.attempt, turn.delivery_lane, turn.launch_input,
                       turn.status AS turn_status, turn.task_id, turn.task_epoch,
                       turn.trigger_stage_id AS stage_id,
                       turn.trigger_stage_generation AS stage_generation,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       logical.id AS logical_turn_id,
                       logical.operation_id AS logical_operation_id,
                       logical.attempt AS logical_attempt,
                       episode.id AS episode_id, episode.status AS episode_status,
                       episode.dev_report_id,
                       report.stage_turn_id AS predecessor_stage_turn_id,
                       episode.validation_evidence_id,
                       episode.semantic_attempt, episode.task_brain_id,
                       COALESCE(lineage.budget_attempt,
                           episode.semantic_attempt) AS budget_attempt,
                       task.lifecycle_state,
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
                       ticket.lane_mask, ticket.status AS ticket_status
                FROM task_turn turn
                LEFT JOIN task_turn_user_wait_continuation_v266 continuation
                  ON continuation.successor_turn_id = turn.id
                JOIN task_turn logical ON logical.id = COALESCE(
                    continuation.logical_turn_id, turn.id)
                JOIN brain_review_episode episode
                  ON episode.task_turn_id = logical.id
                LEFT JOIN development_brain_retry_budget_lineage_v300 lineage
                  ON lineage.successor_episode_id = episode.id
                JOIN dev_report report ON report.id = episode.dev_report_id
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
                  AND turn.purpose IN (
                      'DEVELOPMENT_BRAIN_REVIEW',
                      'DEVELOPMENT_BRAIN_RESULT_REPAIR')
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
                UNION ALL
                SELECT task_turn_id, operation_id, raw_outcome,
                       raw_result_digest, 'ACCEPTED', brain_review_episode_id,
                       NULL, blocker_id, NULL, recorded_at_ms
                FROM development_brain_protocol_failure_v300
                WHERE task_turn_id = ?
                UNION ALL
                SELECT repair_task_turn_id AS task_turn_id,
                       repair_operation_id AS operation_id,
                       raw_outcome,
                       repair_raw_result_digest AS raw_result_digest,
                       acceptance, repair_brain_review_episode_id,
                       json_extract(terminal_evidence, '$.verdict') AS verdict,
                       json_extract(terminal_evidence, '$.blockerId') AS blocker_id,
                       json_extract(terminal_evidence, '$.nextStageTurnRequestId')
                           AS next_stage_turn_request_id,
                       completed_at_ms AS recorded_at_ms
                FROM development_brain_result_repair_v311
                WHERE repair_task_turn_id = ? AND status <> 'REQUESTED'
                """, (rs, row) -> brainTurnReceipt(rs), turnId, turnId, turnId)
                .stream().findFirst();
    }

    /** True only for the one ordinary protocol retry admitted from V300. */
    public Optional<String> findExactBrainProtocolRetryFailureId(
            BrainTurnContext context)
    {
        requireNonNull(context, "context is null");
        List<String> rows = jdbc.query("""
                SELECT retry.failure_id
                FROM development_brain_retry_v300 retry
                WHERE retry.task_id = ?
                  AND retry.replacement_episode_id = ?
                  AND retry.replacement_turn_id = ?
                  AND retry.replacement_operation_id = ?
                  AND retry.execution_attempt = ?
                  AND retry.stage_id = ?
                  AND retry.stage_generation = ?
                  AND retry.code_fingerprint = ?
                  AND retry.head_sha = ?
                  AND retry.base_sha = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM development_brain_result_repair_v311 repair
                      JOIN development_brain_protocol_failure_v300 failure
                        ON failure.id = repair.source_failure_id
                      WHERE failure.task_turn_id = retry.replacement_turn_id)
                """, (rs, row) -> rs.getString(1),
                context.taskId(), context.episodeId(),
                context.logicalTurnId(), context.logicalOperationId(),
                context.semanticAttempt(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.headSha(), context.baseSha());
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Development Brain ordinary retry is ambiguous");
        }
        return rows.stream().findFirst();
    }

    public String failBrainProtocol(
            BrainTurnContext context,
            String detail,
            String payloadJson,
            Instant at)
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
                """, at.toEpochMilli(), detail,
                context.turnId(), context.operationId());
        int episode = jdbc.update("""
                UPDATE brain_review_episode
                SET status = 'FAILED', completed_at_ms = ?, error_message = ?
                WHERE id = ? AND task_turn_id = ?
                  AND status IN ('REQUESTED', 'REVIEWING')
                """, at.toEpochMilli(), detail, context.episodeId(),
                context.logicalTurnId());
        if (turn != 1 || episode != 1) {
            throw new IllegalStateException(
                    "Development Brain changed before protocol failure");
        }
        List<String> exact = jdbc.query("""
                SELECT id FROM task_blocker
                WHERE task_id = ? AND stage_id IS NULL
                  AND owner_kind = 'TASK' AND owner_id = ?
                  AND subject_revision = ?
                  AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, (rs, row) -> rs.getString(1), context.taskId(),
                context.taskId(), context.turnId());
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        if (!exact.isEmpty()) {
            throw new IllegalStateException(
                    "Development Brain protocol blocker is ambiguous");
        }
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE task_id = ? AND stage_id IS NULL
                  AND owner_kind = 'TASK' AND owner_id = ?
                  AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, Integer.class, context.taskId(), context.taskId());
        if (open == null || open != 0) {
            throw new IllegalStateException(
                    "Another Task operation failure already owns recovery");
        }
        String blockerId = PlanRuntimeCoordinator.id(
                "development-brain-protocol-blocker", context.turnId());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, owner_kind, owner_id, subject_revision,
                    blocker_type, status, payload_json, opened_at_ms)
                VALUES (?, ?, 'TASK', ?, ?, 'OPERATION_FAILED', 'OPEN', ?, ?)
                """, blockerId, context.taskId(), context.taskId(),
                context.turnId(), payloadJson, at.toEpochMilli());
        return blockerId;
    }

    public String cancelBrainResultRepair(
            BrainTurnContext context,
            String detail,
            String payloadJson,
            Instant at)
    {
        requireTransaction();
        int turn = jdbc.update("""
                UPDATE task_turn
                SET status = 'CANCELED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND purpose = 'DEVELOPMENT_BRAIN_RESULT_REPAIR'
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, at.toEpochMilli(), detail,
                context.turnId(), context.operationId());
        int episode = jdbc.update("""
                UPDATE brain_review_episode
                SET status = 'CANCELED', completed_at_ms = ?, error_message = ?
                WHERE id = ? AND task_turn_id = ?
                  AND status IN ('REQUESTED', 'REVIEWING')
                """, at.toEpochMilli(), detail, context.episodeId(),
                context.logicalTurnId());
        if (turn != 1 || episode != 1) {
            throw new IllegalStateException(
                    "Development Brain result repair changed before cancellation");
        }
        return insertBrainResultRepairBlocker(context, payloadJson, at);
    }

    private String insertBrainResultRepairBlocker(
            BrainTurnContext context, String payloadJson, Instant at)
    {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE task_id = ? AND stage_id IS NULL
                  AND owner_kind = 'TASK' AND owner_id = ?
                  AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, Integer.class, context.taskId(), context.taskId());
        if (open == null || open != 0) {
            throw new IllegalStateException(
                    "Another Task operation failure already owns recovery");
        }
        String blockerId = PlanRuntimeCoordinator.id(
                "development-brain-protocol-blocker", context.turnId());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, owner_kind, owner_id, subject_revision,
                    blocker_type, status, payload_json, opened_at_ms)
                VALUES (?, ?, 'TASK', ?, ?, 'OPERATION_FAILED', 'OPEN', ?, ?)
                """, blockerId, context.taskId(), context.taskId(),
                context.turnId(), payloadJson, at.toEpochMilli());
        return blockerId;
    }

    public void insertBrainProtocolFailure(
            BrainTurnContext context,
            String blockerId,
            String rawDigest,
            String detail,
            long clearedTaskVersion,
            Instant at)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO development_brain_protocol_failure_v300(
                    id, task_turn_id, operation_id, brain_review_episode_id,
                    owner_turn_id, owner_operation_id, owner_attempt,
                    task_id, task_epoch, stage_id, stage_generation,
                    stage_version, code_fingerprint, head_sha, base_sha,
                    blocker_id, raw_outcome, raw_result_digest, error_message,
                    cleared_task_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'SUCCEEDED', ?, ?, ?, ?)
                """, PlanRuntimeCoordinator.id(
                        "development-brain-protocol-failure", context.turnId()),
                context.turnId(), context.operationId(), context.episodeId(),
                context.logicalTurnId(), context.logicalOperationId(),
                context.logicalAttempt(), context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(),
                context.stageVersion(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), blockerId, rawDigest,
                detail, clearedTaskVersion, at.toEpochMilli());
    }

    public Optional<BrainProtocolRetryReceipt> findBrainProtocolRetryReceipt(
            String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT id, task_id, command_id, actor, reason, blocker_id,
                       predecessor_turn_id, replacement_episode_id,
                       replacement_turn_id, replacement_operation_id,
                       replacement_ticket_id, stage_id, stage_generation,
                       recorded_at_ms
                FROM development_brain_retry_v300
                WHERE task_id = ? AND command_id = ?
                """, (rs, row) -> brainProtocolRetryReceipt(rs),
                taskId, commandId).stream().findFirst();
    }

    public BrainProtocolRetryContext requireBrainProtocolRetryContext(
            String taskId, String failedTurnId, String blockerId)
    {
        List<BrainProtocolRetryContext> rows = jdbc.query("""
                SELECT failure.id AS failure_id, failure.task_id,
                       failure.task_epoch, failure.stage_id,
                       failure.stage_generation, failure.stage_version,
                       failure.code_fingerprint, failure.head_sha,
                       failure.base_sha, failure.blocker_id,
                       failure.error_message,
                       failure.brain_review_episode_id AS episode_id,
                       failed.id AS failed_turn_id,
                       failed.operation_id AS failed_operation_id,
                       failed.launch_input AS failed_launch_input,
                       failed.delivery_lane, ticket.id AS failed_ticket_id,
                       ticket.lane_mask, episode.semantic_attempt,
                       COALESCE(lineage.budget_attempt,
                           episode.semantic_attempt) AS budget_attempt,
                       episode.task_brain_id, episode.dev_report_id,
                       episode.validation_evidence_id,
                       task.aggregate_version AS task_version,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       identity.worktree_path
                FROM development_brain_protocol_failure_v300 failure
                JOIN task_turn failed ON failed.id = failure.task_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = failed.operation_id
                JOIN brain_review_episode episode
                  ON episode.id = failure.brain_review_episode_id
                LEFT JOIN development_brain_retry_budget_lineage_v300 lineage
                  ON lineage.successor_episode_id = episode.id
                JOIN task_blocker blocker ON blocker.id = failure.blocker_id
                JOIN tasks task ON task.id = failure.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                WHERE failure.task_id = ? AND failure.task_turn_id = ?
                  AND failure.blocker_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = failure.task_epoch
                  AND current.stage_id = failure.stage_id
                  AND current.stage_generation = failure.stage_generation
                  AND owner.kind = 'LOCAL_DEVELOPMENT'
                  AND owner.generation = failure.stage_generation
                  AND owner.version = failure.stage_version
                  AND owner.checkpoint = 'BRAIN_REVIEW'
                  AND owner.completed_at_ms IS NULL
                  AND owner.end_reason IS NULL
                  AND code.code_fingerprint = failure.code_fingerprint
                  AND code.head_sha = failure.head_sha
                  AND code.base_sha = failure.base_sha
                  AND failed.status = 'FAILED'
                  AND failed.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
                  AND episode.status = 'FAILED'
                  AND blocker.task_id = task.id
                  AND blocker.stage_id IS NULL
                  AND blocker.owner_kind = 'TASK'
                  AND blocker.owner_id = task.id
                  AND blocker.subject_revision = failed.id
                  AND blocker.blocker_type = 'OPERATION_FAILED'
                  AND blocker.status = 'OPEN'
                  AND 1 = (SELECT COUNT(*) FROM task_blocker open_failure
                      WHERE open_failure.task_id = task.id
                        AND open_failure.stage_id IS NULL
                        AND open_failure.owner_kind = 'TASK'
                        AND open_failure.owner_id = task.id
                        AND open_failure.blocker_type = 'OPERATION_FAILED'
                        AND open_failure.status = 'OPEN')
                  AND NOT EXISTS (
                      SELECT 1 FROM development_brain_retry_v300 retry
                      WHERE retry.failure_id = failure.id
                         OR retry.blocker_id = blocker.id
                         OR retry.predecessor_turn_id = failed.id)
                  AND NOT EXISTS (
                      SELECT 1 FROM development_brain_retry_v300 prior_retry
                      WHERE prior_retry.replacement_episode_id = episode.id
                         OR prior_retry.replacement_turn_id = failed.id
                         OR prior_retry.replacement_operation_id = failed.operation_id)
                """, (rs, row) -> new BrainProtocolRetryContext(
                        rs.getString("failure_id"), rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getLong("task_version"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getLong("stage_version"),
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha"),
                        rs.getString("blocker_id"),
                        rs.getString("error_message"),
                        rs.getString("episode_id"),
                        rs.getString("failed_turn_id"),
                        rs.getString("failed_operation_id"),
                        rs.getString("failed_ticket_id"),
                        rs.getString("failed_launch_input"),
                        rs.getString("delivery_lane"), rs.getInt("lane_mask"),
                        rs.getInt("semantic_attempt"),
                        rs.getInt("budget_attempt"),
                        rs.getString("task_brain_id"),
                        rs.getString("dev_report_id"),
                        rs.getString("validation_evidence_id"),
                        rs.getString("trunk_id"), rs.getString("workspace_id"),
                        rs.getString("worktree_path")),
                taskId, failedTurnId, blockerId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Development Brain recovery target is stale or ambiguous");
        }
        return rows.getFirst();
    }

    public void insertBrainProtocolRetry(BrainProtocolRetry retry)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'DEVELOPMENT_BRAIN_REVIEW', 'QUEUED', ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?)
                """, retry.turnId(), retry.taskId(), retry.operationId(),
                retry.executionAttempt(), retry.taskEpoch(), retry.stageId(),
                retry.stageGeneration(), retry.codeFingerprint(), retry.headSha(),
                retry.baseSha(), retry.deliveryLane(), retry.launchInput(),
                retry.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, retry.episodeId(), retry.taskBrainId(), retry.taskId(),
                retry.taskEpoch(), retry.stageId(), retry.stageGeneration(),
                retry.devReportId(), retry.validationEvidenceId(), retry.turnId(),
                retry.executionAttempt(), retry.codeFingerprint(), retry.headSha(),
                retry.baseSha(), retry.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO development_brain_retry_budget_lineage_v300(
                    predecessor_episode_id, successor_episode_id,
                    execution_attempt, budget_attempt, consumes_budget,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, 0, ?)
                """, retry.predecessorEpisodeId(), retry.episodeId(),
                retry.executionAttempt(), retry.budgetAttempt(),
                retry.requestedAt().toEpochMilli());
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
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, retry.ticketId(), retry.operationId(), retry.turnId(),
                retry.laneMask(), retry.workspaceId(), retry.trunkId(),
                retry.taskId(), retry.taskEpoch(), retry.stageId(),
                retry.stageGeneration(), retry.executionAttempt(),
                retry.codeFingerprint(), retry.headSha(), retry.baseSha(),
                retry.requestedAt().toEpochMilli());
    }

    public BrainProtocolRetryReceipt recordBrainProtocolRetry(
            BrainProtocolRetryContext context,
            BrainProtocolRetry retry,
            String commandId,
            String taskRequestCommandId,
            String actor,
            String reason,
            long returnedTaskVersion,
            Instant at)
    {
        requireTransaction();
        String receiptId = PlanRuntimeCoordinator.id(
                "development-brain-retry-receipt", context.taskId() + ":" + commandId);
        jdbc.update("""
                INSERT INTO development_brain_retry_v300(
                    id, task_id, command_id, task_request_command_id, actor,
                    reason, failure_id, blocker_id, predecessor_episode_id,
                    predecessor_turn_id, replacement_episode_id,
                    replacement_turn_id, replacement_operation_id,
                    replacement_ticket_id, expected_task_epoch,
                    expected_task_version, returned_task_version, stage_id,
                    stage_generation, stage_version, code_fingerprint,
                    head_sha, base_sha, execution_attempt, budget_attempt,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receiptId, context.taskId(), commandId,
                taskRequestCommandId, actor, reason, context.failureId(),
                context.blockerId(), context.episodeId(), context.failedTurnId(),
                retry.episodeId(), retry.turnId(), retry.operationId(),
                retry.ticketId(), context.taskEpoch(), context.taskVersion(),
                returnedTaskVersion, context.stageId(), context.stageGeneration(),
                context.stageVersion(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), retry.executionAttempt(),
                retry.budgetAttempt(), at.toEpochMilli());
        int resolved = jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = ? AND task_id = ? AND stage_id IS NULL
                  AND owner_kind = 'TASK' AND owner_id = ?
                  AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, at.toEpochMilli(),
                "retry command " + commandId + " admitted TaskTurn "
                        + retry.turnId() + ": " + reason,
                context.blockerId(), context.taskId(), context.taskId());
        if (resolved != 1) {
            throw new IllegalStateException(
                    "Development Brain blocker changed before retry");
        }
        return findBrainProtocolRetryReceipt(context.taskId(), commandId)
                .orElseThrow(() -> new IllegalStateException(
                        "Development Brain retry receipt is missing"));
    }

    public Optional<BrainProtocolRetryReceipt> findBrainResultRepairReceipt(
            String taskId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String actor,
            String reason)
    {
        return jdbc.query("""
                SELECT repair.id, repair.task_id, repair.stage_id,
                       repair.stage_generation, failure.blocker_id,
                       failure.task_turn_id AS failed_turn_id,
                       repair.repair_brain_review_episode_id,
                       repair.repair_task_turn_id,
                       repair.repair_operation_id, repair.repair_ticket_id,
                       repair.requested_at_ms
                FROM development_brain_result_repair_v311 repair
                JOIN development_brain_protocol_failure_v300 failure
                  ON failure.id = repair.source_failure_id
                WHERE repair.task_id = ?
                  AND failure.task_turn_id = ?
                  AND failure.blocker_id = ?
                """, (rs, row) -> new BrainProtocolRetryReceipt(
                        rs.getString("id"), rs.getString("task_id"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"), commandId, actor, reason,
                        rs.getString("blocker_id"),
                        rs.getString("failed_turn_id"),
                        rs.getString("repair_brain_review_episode_id"),
                        rs.getString("repair_task_turn_id"),
                        rs.getString("repair_operation_id"),
                        rs.getString("repair_ticket_id"),
                        instant(rs, "requested_at_ms")),
                taskId, failedTurnId, blockerId).stream().findFirst();
    }

    /** Loads the already-materialized second malformed result after restart. */
    public Optional<BrainResultRepairSource> findBrainResultRepairSource(
            String taskId, String failedTurnId, String blockerId)
    {
        List<BrainResultRepairSource> rows = jdbc.query("""
                SELECT source_retry.failure_id AS predecessor_failure_id,
                       failure.id AS source_failure_id,
                       failure.blocker_id AS source_blocker_id,
                       failure.task_turn_id AS source_turn_id,
                       failure.operation_id AS source_operation_id,
                       failure.raw_result_digest AS source_raw_result_digest,
                       (SELECT json_extract(
                            json_extract(execution.raw_result,
                                '$.payloadJson'), '$.finalText')
                        FROM agent_execution execution
                        WHERE execution.ticket_id = ticket.id
                          AND execution.status = 'SUCCEEDED'
                          AND execution.infrastructure_attempt =
                              ticket.infrastructure_attempts
                          AND execution.raw_result IS NOT NULL
                        ORDER BY execution.infrastructure_attempt DESC
                        LIMIT 1) AS malformed_output,
                       episode.task_brain_id,
                       episode.id AS source_episode_id,
                       failure.task_id, failure.task_epoch,
                       task.aggregate_version AS task_version,
                       failure.stage_id, failure.stage_generation,
                       episode.dev_report_id, episode.validation_evidence_id,
                       failure.code_fingerprint, failure.head_sha,
                       failure.base_sha, episode.semantic_attempt,
                       lineage.budget_attempt, failed.delivery_lane,
                       ticket.lane_mask, failed.launch_input AS source_launch_input,
                       trunk.workspace_id, task.thread_id AS trunk_id,
                       identity.worktree_path
                FROM development_brain_protocol_failure_v300 failure
                JOIN development_brain_retry_v300 source_retry
                  ON source_retry.replacement_episode_id =
                        failure.brain_review_episode_id
                 AND source_retry.replacement_turn_id = failure.owner_turn_id
                 AND source_retry.replacement_operation_id =
                        failure.owner_operation_id
                JOIN task_turn failed ON failed.id = failure.task_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'TASK_TURN'
                 AND ticket.owner_id = failed.id
                 AND ticket.operation_id = failure.operation_id
                JOIN brain_review_episode episode
                  ON episode.id = failure.brain_review_episode_id
                JOIN development_brain_retry_budget_lineage_v300 lineage
                  ON lineage.successor_episode_id = episode.id
                JOIN task_blocker blocker ON blocker.id = failure.blocker_id
                JOIN tasks task ON task.id = failure.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                WHERE failure.task_id = ? AND failure.task_turn_id = ?
                  AND failure.blocker_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = failure.task_epoch
                  AND current.stage_id = failure.stage_id
                  AND current.stage_generation = failure.stage_generation
                  AND owner.kind = 'LOCAL_DEVELOPMENT'
                  AND owner.generation = failure.stage_generation
                  AND owner.version = failure.stage_version
                  AND owner.checkpoint = 'BRAIN_REVIEW'
                  AND owner.completed_at_ms IS NULL
                  AND owner.end_reason IS NULL
                  AND code.code_fingerprint = failure.code_fingerprint
                  AND code.head_sha = failure.head_sha
                  AND code.base_sha = failure.base_sha
                  AND failed.status = 'FAILED'
                  AND failed.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
                  AND episode.status = 'FAILED'
                  AND source_retry.execution_attempt = episode.semantic_attempt
                  AND source_retry.execution_attempt = 2
                  AND lineage.consumes_budget = 0
                  AND blocker.task_id = task.id
                  AND blocker.stage_id IS NULL
                  AND blocker.owner_kind = 'TASK'
                  AND blocker.owner_id = task.id
                  AND blocker.subject_revision = failed.id
                  AND blocker.blocker_type = 'OPERATION_FAILED'
                  AND blocker.status = 'OPEN'
                  AND EXISTS (
                      SELECT 1 FROM agent_execution execution
                      WHERE execution.ticket_id = ticket.id
                        AND execution.status = 'SUCCEEDED'
                        AND execution.infrastructure_attempt =
                            ticket.infrastructure_attempts
                        AND execution.raw_result IS NOT NULL
                        AND json_extract(json_extract(execution.raw_result,
                            '$.payloadJson'), '$.finalText') IS NOT NULL
                        AND length(trim(json_extract(json_extract(
                            execution.raw_result, '$.payloadJson'),
                            '$.finalText'),
                            char(9) || char(10) || char(13) || ' ')) > 0)
                  AND NOT EXISTS (
                      SELECT 1 FROM development_brain_result_repair_v311 repair
                      WHERE repair.source_failure_id = failure.id
                         OR repair.source_task_turn_id = failed.id)
                """, (rs, row) -> new BrainResultRepairSource(
                        rs.getString("predecessor_failure_id"),
                        rs.getString("source_failure_id"),
                        rs.getString("source_blocker_id"),
                        rs.getString("source_turn_id"),
                        rs.getString("source_operation_id"),
                        rs.getString("source_raw_result_digest"),
                        rs.getString("malformed_output"),
                        rs.getString("task_brain_id"),
                        rs.getString("source_episode_id"),
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getLong("task_version"), rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("dev_report_id"),
                        rs.getString("validation_evidence_id"),
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha"),
                        rs.getInt("semantic_attempt"),
                        rs.getInt("budget_attempt"),
                        rs.getString("delivery_lane"), rs.getInt("lane_mask"),
                        rs.getString("source_launch_input"),
                        rs.getString("workspace_id"), rs.getString("trunk_id"),
                        rs.getString("worktree_path")),
                taskId, failedTurnId, blockerId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Development Brain result repair source is ambiguous");
        }
        return rows.stream().findFirst();
    }

    public void insertBrainResultRepairTurn(BrainResultRepair repair)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'DEVELOPMENT_BRAIN_RESULT_REPAIR', 'QUEUED',
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, repair.turnId(), repair.taskId(), repair.operationId(),
                repair.executionAttempt(), repair.taskEpoch(), repair.stageId(),
                repair.stageGeneration(), repair.codeFingerprint(),
                repair.headSha(), repair.baseSha(), repair.deliveryLane(),
                repair.launchInput(), repair.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, repair.episodeId(), repair.taskBrainId(), repair.taskId(),
                repair.taskEpoch(), repair.stageId(), repair.stageGeneration(),
                repair.devReportId(), repair.validationEvidenceId(),
                repair.turnId(), repair.executionAttempt(),
                repair.codeFingerprint(), repair.headSha(), repair.baseSha(),
                repair.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO development_brain_retry_budget_lineage_v300(
                    predecessor_episode_id, successor_episode_id,
                    execution_attempt, budget_attempt, consumes_budget,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, 0, ?)
                """, repair.predecessorEpisodeId(), repair.episodeId(),
                repair.executionAttempt(), repair.budgetAttempt(),
                repair.requestedAt().toEpochMilli());
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
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, repair.ticketId(), repair.operationId(), repair.turnId(),
                repair.laneMask(), repair.workspaceId(), repair.trunkId(),
                repair.taskId(), repair.taskEpoch(), repair.stageId(),
                repair.stageGeneration(), repair.executionAttempt(),
                repair.codeFingerprint(), repair.headSha(), repair.baseSha(),
                repair.requestedAt().toEpochMilli());
    }

    public void recordBrainResultRepair(BrainResultRepair repair, String reason)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO development_brain_result_repair_v311(
                    id, predecessor_failure_id, source_failure_id,
                    source_task_turn_id,
                    source_operation_id, source_raw_result_digest,
                    source_malformed_output, required_result_shape,
                    repair_brain_review_episode_id, repair_task_turn_id,
                    repair_operation_id, repair_ticket_id, task_id, task_epoch,
                    stage_id, stage_generation, code_fingerprint, head_sha,
                    base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, repair.id(), repair.predecessorFailureId(),
                repair.sourceFailureId(),
                repair.sourceTurnId(), repair.sourceOperationId(),
                repair.sourceRawResultDigest(), repair.sourceMalformedOutput(),
                repair.requiredResultShape(), repair.episodeId(),
                repair.turnId(), repair.operationId(), repair.ticketId(),
                repair.taskId(), repair.taskEpoch(), repair.stageId(),
                repair.stageGeneration(), repair.codeFingerprint(),
                repair.headSha(), repair.baseSha(),
                repair.requestedAt().toEpochMilli());
        int resolved = jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = ? AND task_id = ? AND stage_id IS NULL
                  AND owner_kind = 'TASK' AND owner_id = ?
                  AND subject_revision = ?
                  AND blocker_type = 'OPERATION_FAILED' AND status = 'OPEN'
                """, repair.requestedAt().toEpochMilli(),
                "result repair " + repair.turnId() + ": " + reason,
                repair.sourceBlockerId(), repair.taskId(), repair.taskId(),
                repair.sourceTurnId());
        if (resolved != 1) {
            throw new IllegalStateException(
                    "Development Brain result repair blocker changed");
        }
    }

    public void finishBrainResultRepair(
            BrainTurnContext context,
            String status,
            String rawOutcome,
            String rawResultDigest,
            String repairedPayloadDigest,
            String acceptance,
            String terminalEvidence,
            Instant at)
    {
        requireTransaction();
        int updated = jdbc.update("""
                UPDATE development_brain_result_repair_v311
                SET status = ?, raw_outcome = ?, repair_raw_result_digest = ?,
                    repaired_payload_digest = ?, acceptance = ?,
                    terminal_evidence = ?, completed_at_ms = ?
                WHERE repair_task_turn_id = ?
                  AND repair_operation_id = ? AND status = 'REQUESTED'
                  AND completed_at_ms IS NULL
                """, status, rawOutcome, rawResultDigest,
                repairedPayloadDigest, acceptance, terminalEvidence,
                at.toEpochMilli(), context.turnId(), context.operationId());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Development Brain result repair changed before completion");
        }
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
                context.episodeId(), context.logicalTurnId());
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
                """, at.toEpochMilli(), detail, context.episodeId(),
                context.logicalTurnId());
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

    public void supersedeBrain(BrainTurnContext context, String detail, Instant at)
    {
        requireTransaction();
        int turn = jdbc.update("""
                UPDATE task_turn
                SET status = 'SUPERSEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, at.toEpochMilli(), detail, context.turnId(), context.operationId());
        int episode = jdbc.update("""
                UPDATE brain_review_episode
                SET status = 'SUPERSEDED', completed_at_ms = ?, error_message = ?
                WHERE id = ? AND task_turn_id = ?
                  AND status IN ('REQUESTED', 'REVIEWING')
                """, at.toEpochMilli(), detail, context.episodeId(),
                context.logicalTurnId());
        if (turn != 1 || episode != 1) {
            throw new IllegalStateException(
                    "Development Brain changed before supersession");
        }
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
                rs.getString("operation_id"), rs.getInt("attempt"),
                rs.getString("turn_status"), rs.getString("request_id"),
                rs.getString("request_kind"), rs.getString("queue_mode"),
                rs.getString("brain_review_episode_id"),
                rs.getString("local_feedback_batch_id"),
                rs.getString("base_sync_episode_id"),
                rs.getString("target_base_sha"),
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
                rs.getString("worktree_path"), rs.getString("delivery_lane"),
                rs.getString("launch_input"), rs.getString("ticket_id"),
                rs.getInt("lane_mask"), rs.getString("ticket_status"),
                rs.getString("ticket_last_error"), rs.getString("workspace_id"),
                rs.getString("trunk_id"), rs.getString("branch_name"),
                requiredBaseBranch(rs), rs.getString("task_name"));
    }

    private static StageTurnRetryContext stageTurnRetryContext(ResultSet rs)
            throws SQLException
    {
        return new StageTurnRetryContext(
                rs.getString("failure_id"), rs.getString("blocker_id"),
                rs.getString("error_message"), rs.getString("payload_json"),
                rs.getString("failed_turn_id"),
                rs.getString("failed_operation_id"),
                rs.getInt("failed_attempt"), rs.getString("purpose"),
                rs.getString("delivery_lane"), rs.getString("launch_input"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("request_kind"),
                rs.getString("brain_review_episode_id"),
                rs.getString("local_feedback_batch_id"),
                rs.getString("base_sync_episode_id"),
                rs.getString("target_base_sha"),
                rs.getString("failed_ticket_id"), rs.getInt("lane_mask"),
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("stage_version"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")));
    }

    private static StageTurnRetryReceipt stageTurnRetryReceipt(ResultSet rs)
            throws SQLException
    {
        return new StageTurnRetryReceipt(
                rs.getString("id"), rs.getString("task_id"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getString("command_id"), rs.getString("actor"),
                rs.getString("reason"), rs.getString("blocker_id"),
                rs.getString("predecessor_turn_id"),
                rs.getString("replacement_request_id"),
                rs.getString("replacement_turn_id"),
                rs.getString("replacement_operation_id"),
                rs.getString("replacement_ticket_id"),
                instant(rs, "recorded_at_ms"));
    }

    private static String requiredBaseBranch(ResultSet rs)
            throws SQLException
    {
        String baseBranch = rs.getString("base_branch");
        if (baseBranch == null || baseBranch.isBlank()) {
            throw new IllegalStateException(
                    "Local StageTurn has no frozen Task base branch");
        }
        return baseBranch;
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
                rs.getString("turn_id"), rs.getString("purpose"),
                rs.getString("operation_id"), rs.getInt("attempt"),
                rs.getString("turn_status"), rs.getString("delivery_lane"),
                rs.getInt("lane_mask"), rs.getString("launch_input"),
                rs.getString("logical_turn_id"),
                rs.getString("logical_operation_id"),
                rs.getInt("logical_attempt"), rs.getString("episode_id"),
                rs.getString("episode_status"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("dev_report_id"),
                rs.getString("predecessor_stage_turn_id"),
                rs.getString("validation_evidence_id"),
                rs.getInt("semantic_attempt"), rs.getInt("budget_attempt"),
                rs.getString("task_brain_id"), rs.getString("lifecycle_state"),
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

    private static BrainProtocolRetryReceipt brainProtocolRetryReceipt(
            ResultSet rs)
            throws SQLException
    {
        return new BrainProtocolRetryReceipt(
                rs.getString("id"), rs.getString("task_id"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getString("command_id"), rs.getString("actor"),
                rs.getString("reason"), rs.getString("blocker_id"),
                rs.getString("predecessor_turn_id"),
                rs.getString("replacement_episode_id"),
                rs.getString("replacement_turn_id"),
                rs.getString("replacement_operation_id"),
                rs.getString("replacement_ticket_id"),
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
            String turnId, String taskId, String operationId, int attempt,
            String turnStatus, String requestId,
            String requestKind, String queueMode, String brainReviewEpisodeId,
            String localFeedbackBatchId, String baseSyncEpisodeId,
            String targetBaseSha, long taskEpoch, String stageId,
            long stageGeneration, String codeFingerprint, String headSha,
            String baseSha, StageCheckpoint checkpoint, long stageVersion,
            boolean stageCompleted, String taskLifecycle, long currentTaskEpoch,
            long taskVersion, String currentStageId, Long currentStageGeneration,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha, String worktreePath, String deliveryLane,
            String launchInput, String ticketId, int laneMask,
            String ticketStatus, String ticketLastError,
            String workspaceId, String trunkId,
            String branchName, String baseBranch, String taskName)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, attempt,
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
            String unresolvedConcerns, String contextRefs,
            /** Design 3.36: the PR body, written by the agent against the
             *  repository's own template when it has one. */
            String prDescription) {}

    public record CodeSubject(String codeFingerprint, String headSha, String baseSha) {}

    public record DevReport(String id, int revision, CodeSubject output) {}

    public record ValidationRequest(String operationId, String ticketId, ResultFence fence) {}

    public record StageTurnDeliveryReceipt(
            String turnId, String operationId, String rawOutcome,
            String rawResultDigest, String acceptance, String devReportId,
            String validationOperationId, Instant recordedAt) {}

    public record StageTurnFailure(
            String id, String taskId, String stageId, long stageGeneration,
            String turnId, String operationId, String blockerId, String error,
            long clearedStageVersion, Instant recordedAt) {}

    public record StageTurnRetryContext(
            String failureId,
            String blockerId,
            String error,
            String payloadJson,
            String failedTurnId,
            String failedOperationId,
            int failedAttempt,
            String purpose,
            String deliveryLane,
            String failedLaunchInput,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String requestKind,
            String brainReviewEpisodeId,
            String localFeedbackBatchId,
            String baseSyncEpisodeId,
            String targetBaseSha,
            String failedTicketId,
            int laneMask,
            String taskId,
            String trunkId,
            String workspaceId,
            long stageVersion,
            StageCheckpoint checkpoint)
    {
        public ResultFence failedFence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, failedOperationId,
                    failedAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record StageTurnRetry(
            String requestId,
            String requestCommandId,
            String turnId,
            String operationId,
            String ticketId,
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int attempt,
            String purpose,
            String requestKind,
            String brainReviewEpisodeId,
            String localFeedbackBatchId,
            String baseSyncEpisodeId,
            String targetBaseSha,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String deliveryLane,
            int laneMask,
            String launchInput,
            String promptDigest,
            String requestedBy,
            Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, attempt,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record StageTurnRetryReceipt(
            String id,
            String taskId,
            String stageId,
            long stageGeneration,
            String commandId,
            String actor,
            String reason,
            String blockerId,
            String failedTurnId,
            String replacementRequestId,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId,
            Instant recordedAt) {}

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

    public record BrainProtocolRetryContext(
            String failureId, String taskId, long taskEpoch, long taskVersion,
            String stageId, long stageGeneration, long stageVersion,
            String codeFingerprint, String headSha, String baseSha,
            String blockerId, String errorMessage, String episodeId,
            String failedTurnId,
            String failedOperationId, String failedTicketId,
            String failedLaunchInput, String deliveryLane, int laneMask,
            int semanticAttempt, int budgetAttempt, String taskBrainId,
            String devReportId, String validationEvidenceId, String trunkId,
            String workspaceId, String worktreePath) {}

    public record BrainProtocolRetry(
            String episodeId, String turnId, String operationId,
            String ticketId, String taskBrainId, String predecessorEpisodeId,
            String taskId, long taskEpoch, String stageId,
            long stageGeneration, String devReportId,
            String validationEvidenceId, String codeFingerprint,
            String headSha, String baseSha, int executionAttempt,
            int budgetAttempt, String deliveryLane, int laneMask,
            String launchInput, String workspaceId, String trunkId,
            Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    executionAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record BrainProtocolRetryReceipt(
            String id, String taskId, String stageId, long stageGeneration,
            String commandId, String actor, String reason, String blockerId,
            String failedTurnId, String replacementEpisodeId,
            String replacementTurnId, String replacementOperationId,
            String replacementTicketId, Instant recordedAt) {}

    public record BrainResultRepairSource(
            String predecessorFailureId, String sourceFailureId,
            String sourceBlockerId,
            String sourceTurnId, String sourceOperationId,
            String sourceRawResultDigest, String malformedOutput,
            String taskBrainId, String sourceEpisodeId,
            String taskId, long taskEpoch, long taskVersion,
            String stageId, long stageGeneration,
            String devReportId, String validationEvidenceId,
            String codeFingerprint, String headSha, String baseSha,
            int semanticAttempt, int budgetAttempt,
            String deliveryLane, int laneMask, String sourceLaunchInput,
            String workspaceId, String trunkId, String worktreePath) {}

    public record BrainResultRepair(
            String id, String predecessorFailureId, String sourceFailureId,
            String sourceBlockerId,
            String sourceTurnId, String sourceOperationId,
            String sourceRawResultDigest, String sourceMalformedOutput,
            String requiredResultShape, String episodeId, String turnId,
            String operationId, String ticketId, String taskBrainId,
            String predecessorEpisodeId, String taskId, long taskEpoch,
            String stageId, long stageGeneration, String devReportId,
            String validationEvidenceId, String codeFingerprint,
            String headSha, String baseSha, int executionAttempt,
            int budgetAttempt, String deliveryLane, int laneMask,
            String launchInput, String workspaceId, String trunkId,
            Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    executionAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record ValidationDeliveryReceipt(
            String validationOperationId, String operationId, String rawOutcome,
            String rawResultDigest, String acceptance, String validationEvidenceId,
            String brainReviewEpisodeId, Instant recordedAt) {}

    public record BrainTurnContext(
            String turnId, String purpose, String operationId, int attempt,
            String turnStatus, String deliveryLane, int laneMask,
            String launchInput,
            String logicalTurnId, String logicalOperationId, int logicalAttempt,
            String episodeId, String episodeStatus, String taskId,
            long taskEpoch, String stageId, long stageGeneration,
            String codeFingerprint, String headSha, String baseSha,
            String devReportId, String predecessorStageTurnId,
            String validationEvidenceId, int semanticAttempt, int budgetAttempt,
            String taskBrainId,
            String taskLifecycle, long currentTaskEpoch, long taskVersion,
            String currentStageId, Long currentStageGeneration,
            StageCheckpoint checkpoint, long stageVersion, boolean stageCompleted,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha, String worktreePath, String trunkId,
            String workspaceId, String workModelSnapshot, String provider,
            String model, String roleSkill, int maxBrainRounds,
            String ticketId, String ticketStatus)
    {
        public ResultFence deliveryFence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, attempt,
                    codeFingerprint, headSha, baseSha);
        }

        public ResultFence ownerFence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration,
                    logicalOperationId, logicalAttempt,
                    codeFingerprint, headSha, baseSha);
        }

        public boolean resultRepair()
        {
            return "DEVELOPMENT_BRAIN_RESULT_REPAIR".equals(purpose);
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
