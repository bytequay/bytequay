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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable typed Turns for deterministic CI and branch-conflict repair. */
@Repository
public class SqliteRemoteRepairTurnStore
{
    private final JdbcTemplate jdbc;

    public SqliteRemoteRepairTurnStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public RepairContext requireContext(String taskId, String stageId)
    {
        List<RepairContext> rows = jdbc.query("""
                SELECT trunk.workspace_id, task.thread_id AS trunk_id,
                       task.id AS task_id, task.epoch AS task_epoch,
                       task.aggregate_version AS task_version,
                       remote.stage_id, remote.generation AS stage_generation,
                       owner.version AS stage_version,
                       identity.worktree_path, creation.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill,
                       code.code_fingerprint, code.head_sha, code.base_sha,
                       remote.current_head_sha, remote.current_base_sha
                FROM remote_development_stage remote
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = remote.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context creation ON creation.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE task.id = ? AND remote.stage_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND current.stage_id = remote.stage_id
                  AND current.stage_generation = remote.generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                """, (rs, row) -> repairContext(rs), taskId, stageId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one current Remote repair context, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public TurnRequest insertCiStageTurn(
            RepairContext context,
            CiEpisode episode,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireTransaction();
        int attempt = episode.fixAttemptCount() + 1;
        String suffix = episode.id() + ":fix:" + attempt;
        String rowId = id("ci-repair-operation-row", suffix);
        String operationId = id("ci-repair-operation", suffix);
        String turnId = id("ci-repair-stage-turn", suffix);
        String ticketId = id("ci-repair-ticket", suffix);
        insertStageTurn(
                turnId, operationId, "REMOTE_CI_REPAIR", attempt, context,
                launchInput, deliveryLane, at);
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, stage_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'FIX_STAGE_TURN', ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), operationId,
                attempt, turnId, context.codeFingerprint(), context.headSha(),
                context.baseSha(), at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_STAGE_TURN", "AGENT_TURN",
                "STAGE_TURN", turnId, "REMOTE_CI_STAGE_TURN_RESULT",
                laneMask, true, true, context, attempt, at);
        dispatchCi(rowId);
        updateOne("""
                UPDATE ci_repair_episode
                SET status = 'FIXING', fix_attempt_count = fix_attempt_count + 1
                WHERE id = ? AND fix_attempt_count = ?
                  AND status IN ('OPEN', 'VALIDATING', 'AWAITING_PUSH_CI')
                """, "CI fix attempt changed before dispatch",
                episode.id(), episode.fixAttemptCount());
        return new TurnRequest(
                "CI", rowId, episode.id(), null, turnId, operationId,
                ticketId, attempt);
    }

    public EffectRequest insertCiValidation(
            RepairContext context, CiEpisode episode, Instant at)
    {
        requireTransaction();
        return insertCiEffect(
                context, episode, "VALIDATE", "VALIDATE_REMOTE_CI_REPAIR",
                "VALIDATION", "REMOTE_CI_VALIDATION_RESULT", 4,
                false, at);
    }

    public BrainRequest insertCiBrain(
            RepairContext context,
            CiEpisode episode,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireTransaction();
        int attempt = episode.fixAttemptCount();
        String suffix = episode.id() + ":brain:" + attempt;
        String rowId = id("ci-repair-operation-row", suffix);
        String operationId = id("ci-repair-operation", suffix);
        String turnId = id("ci-repair-task-turn", suffix);
        String ticketId = id("ci-repair-ticket", suffix);
        insertTaskTurn(
                turnId, operationId, "REMOTE_CI_BRAIN_REVIEW", attempt,
                context, launchInput, deliveryLane, at);
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, task_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'BRAIN_REVIEW', ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), operationId,
                attempt, turnId, context.codeFingerprint(), context.headSha(),
                context.baseSha(), at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_TASK_TURN", "AGENT_TURN",
                "TASK_TURN", turnId, "REMOTE_CI_BRAIN_RESULT", laneMask,
                true, false, context, attempt, at);
        dispatchCi(rowId);
        updateOne("""
                UPDATE ci_repair_episode SET status = 'AWAITING_PUSH_CI'
                WHERE id = ? AND status = 'VALIDATING'
                """, "CI Episode changed before Brain review", episode.id());
        return brainRequest(
                "CI", rowId, episode.id(), null, turnId, operationId,
                ticketId, attempt, context);
    }

    public EffectRequest insertCiPush(
            RepairContext context, CiEpisode episode, Instant at)
    {
        requireTransaction();
        return insertCiEffect(
                context, episode, "PUSH_HEAD", "PUSH_REMOTE_CI_REPAIR",
                "GITHUB_EFFECT", "REMOTE_CI_PUSH_RESULT", 48,
                true, at);
    }

    public TurnRequest insertBranchStageTurn(
            RepairContext context,
            BranchEpisode episode,
            BranchStep step,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireTransaction();
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String rowId = id("branch-sync-operation-row", suffix);
        String operationId = id("branch-sync-operation", suffix);
        String turnId = id("branch-sync-stage-turn", suffix);
        String ticketId = id("branch-sync-ticket", suffix);
        insertStageTurn(
                turnId, operationId, "BRANCH_CONFLICT_REPAIR", attempt,
                context, launchInput, deliveryLane, at);
        jdbc.update("""
                INSERT INTO branch_sync_dispatch_operation(
                    id, branch_sync_episode_id, branch_sync_effect_step_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, kind, operation_id, semantic_attempt,
                    stage_turn_id, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, target_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CONFLICT_REPAIR', ?, ?, ?, ?, ?,
                    ?, ?, 'REQUESTED', ?)
                """, rowId, episode.id(), step.id(), context.stageId(),
                context.taskId(), context.taskEpoch(), context.stageGeneration(),
                operationId, attempt, turnId, context.codeFingerprint(),
                context.headSha(), context.baseSha(), episode.targetBaseSha(),
                at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_STAGE_TURN", "AGENT_TURN",
                "STAGE_TURN", turnId, "BRANCH_SYNC_CONFLICT_RESULT",
                laneMask, true, true, context, attempt, at);
        dispatchBranch(rowId);
        claimBranch(step, operationId, attempt, at);
        return new TurnRequest(
                "BRANCH", rowId, episode.id(), step.id(), turnId,
                operationId, ticketId, attempt);
    }

    /** Materializes one already-persisted CI/branch steering handoff. */
    public TurnRequest insertSteeringTurn(
            Request request,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireTransaction();
        SteeringOwner owner = requireSteeringOwner(request);
        supersedeUndeliveredPredecessor(request, at);
        RepairContext context = requireContext(request.taskId(), request.stageId());
        int attempt = request.predecessor().attempt() + 1;
        String turnId = id("remote-repair-steering-turn", request.id());
        String operationId = id("remote-repair-steering-operation", request.id());
        String ticketId = id("remote-repair-steering-ticket", request.id());
        insertStageTurn(
                turnId, operationId, request.predecessor().purpose(), attempt,
                context, launchInput, deliveryLane, at);
        jdbc.update("""
                INSERT INTO remote_repair_steering_turn_v257(
                    request_id, owner_family, ci_repair_episode_id,
                    branch_sync_episode_id, branch_sync_step_id, stage_turn_id,
                    operation_id, dispatch_ticket_id, semantic_attempt,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, request.id(), owner.ownerFamily(), owner.ciEpisodeId(),
                owner.branchEpisodeId(), owner.branchStepId(), turnId,
                operationId, ticketId, attempt, at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_STAGE_TURN", "AGENT_TURN",
                "STAGE_TURN", turnId, "REMOTE_REPAIR_STEERING_RESULT",
                laneMask, true, true, context, attempt, at);
        return new TurnRequest(
                owner.family(), request.id(),
                owner.ciEpisodeId() == null
                        ? owner.branchEpisodeId() : owner.ciEpisodeId(),
                owner.branchStepId(), turnId, operationId, ticketId, attempt);
    }

    public String requireSteeringTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT request.task_id
                FROM remote_repair_steering_turn_v257 steering
                JOIN stage_steering_request_v257 request
                  ON request.id = steering.request_id
                WHERE steering.stage_turn_id = ? AND steering.operation_id = ?
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Remote repair steering Turn is missing");
        }
        return rows.getFirst();
    }

    public TurnDelivery requireSteeringDelivery(String turnId, String operationId)
    {
        List<TurnDelivery> rows = jdbc.query("""
                SELECT CASE steering.owner_family
                         WHEN 'CI_REPAIR' THEN 'CI' ELSE 'BRANCH' END AS family,
                       steering.request_id AS row_id,
                       COALESCE(steering.ci_repair_episode_id,
                                steering.branch_sync_episode_id) AS episode_id,
                       steering.branch_sync_step_id AS step_id,
                       'STEERING' AS kind, steering.operation_id,
                       request.task_id, request.task_epoch,
                       request.stage_id, request.stage_generation,
                       steering.semantic_attempt, steering.stage_turn_id AS turn_id,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       identity.worktree_path,
                       task.aggregate_version AS task_version,
                       owner.version AS stage_version,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = request.task_epoch
                              AND current.stage_id = request.stage_id
                              AND current.stage_generation = request.stage_generation
                              AND owner.kind = 'REMOTE_DEVELOPMENT'
                              AND owner.completed_at_ms IS NULL
                              AND code.code_fingerprint =
                                  turn.expected_code_fingerprint
                              AND code.head_sha = turn.expected_head_sha
                              AND code.base_sha = turn.expected_base_sha
                            THEN 1 ELSE 0 END AS is_current
                FROM remote_repair_steering_turn_v257 steering
                JOIN stage_steering_request_v257 request
                  ON request.id = steering.request_id
                JOIN stage_turn turn ON turn.id = steering.stage_turn_id
                JOIN tasks task ON task.id = request.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = steering.dispatch_ticket_id
                WHERE steering.stage_turn_id = ? AND steering.operation_id = ?
                  AND steering.status = 'REQUESTED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> turnDelivery(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Remote steering delivery, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public Optional<EffectDeliveryReceipt> findSteeringReceipt(String operationId)
    {
        return jdbc.query("""
                SELECT delivery.request_id AS row_id, delivery.operation_id,
                       request.task_id, delivery.raw_outcome,
                       delivery.raw_result_digest,
                       delivery.acceptance, delivery.recorded_at_ms
                FROM remote_repair_steering_delivery_v257 delivery
                JOIN stage_steering_request_v257 request
                  ON request.id = delivery.request_id
                WHERE delivery.operation_id = ?
                """, (rs, row) -> new EffectDeliveryReceipt(
                        rs.getString("row_id"), rs.getString("operation_id"),
                        rs.getString("task_id"), rs.getString("raw_outcome"),
                        rs.getString("raw_result_digest"),
                        DispatchTicket.Acceptance.valueOf(
                                rs.getString("acceptance")),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                operationId).stream().findFirst();
    }

    public BrainRequest insertBranchBrain(
            RepairContext context,
            BranchEpisode episode,
            BranchStep step,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireTransaction();
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String rowId = id("branch-sync-operation-row", suffix);
        String operationId = id("branch-sync-operation", suffix);
        String turnId = id("branch-sync-task-turn", suffix);
        String ticketId = id("branch-sync-ticket", suffix);
        insertTaskTurn(
                turnId, operationId, "BRANCH_SYNC_BRAIN_REVIEW", attempt,
                context, launchInput, deliveryLane, at);
        jdbc.update("""
                INSERT INTO branch_sync_dispatch_operation(
                    id, branch_sync_episode_id, branch_sync_effect_step_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, kind, operation_id, semantic_attempt,
                    task_turn_id, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, target_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BRAIN_REVIEW', ?, ?, ?, ?, ?, ?,
                    ?, 'REQUESTED', ?)
                """, rowId, episode.id(), step.id(), context.stageId(),
                context.taskId(), context.taskEpoch(), context.stageGeneration(),
                operationId, attempt, turnId, context.codeFingerprint(),
                context.headSha(), context.baseSha(), episode.targetBaseSha(),
                at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_TASK_TURN", "AGENT_TURN",
                "TASK_TURN", turnId, "BRANCH_SYNC_BRAIN_RESULT", laneMask,
                true, false, context, attempt, at);
        dispatchBranch(rowId);
        claimBranch(step, operationId, attempt, at);
        updateOne("""
                UPDATE branch_sync_episode SET status = 'BRAIN_REVIEW'
                WHERE id = ? AND status = 'VALIDATING'
                """, "Branch sync changed before Brain review", episode.id());
        return brainRequest(
                "BRANCH", rowId, episode.id(), step.id(), turnId,
                operationId, ticketId, attempt, context);
    }

    public String requireTurnTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT operation.task_id
                FROM ci_repair_operation operation
                WHERE COALESCE(operation.stage_turn_id,
                        operation.task_turn_id) = ?
                  AND operation.operation_id = ?
                UNION ALL
                SELECT operation.task_id
                FROM branch_sync_dispatch_operation operation
                WHERE COALESCE(operation.stage_turn_id,
                        operation.task_turn_id) = ?
                  AND operation.operation_id = ?
                """, (rs, row) -> rs.getString(1), turnId, operationId,
                turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Remote repair Turn is missing");
        }
        return rows.getFirst();
    }

    public TurnDelivery requireTurnDelivery(String turnId, String operationId)
    {
        List<TurnDelivery> rows = jdbc.query("""
                SELECT 'CI' AS family, operation.id AS row_id,
                       operation.ci_repair_episode_id AS episode_id,
                       NULL AS step_id, operation.kind,
                       operation.operation_id, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       COALESCE(operation.stage_turn_id,
                           operation.task_turn_id) AS turn_id,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha, identity.worktree_path,
                       task.aggregate_version AS task_version,
                       owner.version AS stage_version,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = operation.task_epoch
                              AND current.stage_id =
                                  operation.remote_development_stage_id
                              AND current.stage_generation =
                                  operation.stage_generation
                              AND remote.current_head_sha = COALESCE(
                                  episode.last_pushed_head_sha,
                                  episode.subject_head_sha)
                              AND remote.current_base_sha =
                                  episode.subject_base_sha
                              AND code.code_fingerprint =
                                  operation.expected_code_fingerprint
                              AND code.head_sha = operation.expected_head_sha
                              AND code.base_sha = operation.expected_base_sha
                            THEN 1 ELSE 0 END AS is_current
                FROM ci_repair_operation operation
                JOIN ci_repair_episode episode
                  ON episode.id = operation.ci_repair_episode_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner
                  ON owner.id = operation.remote_development_stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = operation.task_id
                JOIN task_code_identity identity
                  ON identity.task_id = operation.task_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE COALESCE(operation.stage_turn_id,
                        operation.task_turn_id) = ?
                  AND operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                UNION ALL
                SELECT 'BRANCH', operation.id,
                       operation.branch_sync_episode_id,
                       operation.branch_sync_effect_step_id, operation.kind,
                       operation.operation_id, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       COALESCE(operation.stage_turn_id,
                           operation.task_turn_id),
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha, identity.worktree_path,
                       task.aggregate_version, owner.version,
                       CASE WHEN task.lifecycle_state = 'ACTIVE'
                              AND task.epoch = operation.task_epoch
                              AND current.stage_id =
                                  operation.remote_development_stage_id
                              AND current.stage_generation =
                                  operation.stage_generation
                              AND remote.current_head_sha = episode.old_head_sha
                              AND remote.current_base_sha =
                                  episode.observed_base_sha
                              AND code.code_fingerprint =
                                  operation.expected_code_fingerprint
                              AND code.head_sha = operation.expected_head_sha
                              AND code.base_sha = operation.expected_base_sha
                            THEN 1 ELSE 0 END
                FROM branch_sync_dispatch_operation operation
                JOIN branch_sync_episode episode
                  ON episode.id = operation.branch_sync_episode_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner
                  ON owner.id = operation.remote_development_stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = operation.task_id
                JOIN task_code_identity identity
                  ON identity.task_id = operation.task_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE COALESCE(operation.stage_turn_id,
                        operation.task_turn_id) = ?
                  AND operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> turnDelivery(rs), turnId, operationId,
                turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Remote repair Turn delivery, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public void finishStageTurn(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            String status,
            CodeSubject output,
            String evidence,
            String error,
            Instant at)
    {
        requireTransaction();
        finishTurn("stage_turn", context, status, error, at);
        finishOperation(context, status, output, evidence, error, at);
        if ("BRANCH".equals(context.family())) {
            finishBranchStep(context, status, evidence, error, at);
        }
        if ("SUCCEEDED".equals(status)) {
            insertRemoteCodeSubject(context, output, at);
            insertWorktreeSubject(context, output, at);
        }
        insertReceipt(context, rawOutcome, rawDigest, acceptance, at);
    }

    /** Finishes the predecessor while leaving its repair loop open for steering. */
    public void finishPredecessorForSteering(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            String status,
            CodeSubject output,
            String evidence,
            String error,
            Instant at)
    {
        requireTransaction();
        finishTurn("stage_turn", context, status, error, at);
        finishOperation(context, status, output, evidence, error, at);
        if ("SUCCEEDED".equals(status)) {
            insertRemoteCodeSubject(context, output, at);
            insertWorktreeSubject(context, output, at);
        }
        insertReceipt(context, rawOutcome, rawDigest, acceptance, at);
    }

    public void finishSteeringTurn(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            String status,
            CodeSubject output,
            String summary,
            String error,
            Instant at)
    {
        requireTransaction();
        finishTurn("stage_turn", context, status, error, at);
        updateOne("""
                UPDATE remote_repair_steering_turn_v257
                SET status = ?, result_code_fingerprint = ?,
                    result_head_sha = ?, result_summary = ?,
                    completed_at_ms = ?, error_message = ?
                WHERE request_id = ? AND operation_id = ?
                  AND status = 'REQUESTED'
                """, "Remote steering Operation changed before delivery",
                status, output == null ? null : output.codeFingerprint(),
                output == null ? null : output.headSha(), summary,
                at.toEpochMilli(), error, context.rowId(), context.operationId());
        if ("BRANCH".equals(context.family())) {
            boolean succeeded = "SUCCEEDED".equals(status);
            updateOne("""
                    UPDATE branch_sync_effect_step
                    SET status = ?, claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        evidence = ?, last_error = ?, completed_at_ms = ?
                    WHERE id = ? AND status = 'CLAIMED'
                    """, "Branch steering step changed before delivery",
                    succeeded ? "SUCCEEDED" : "FAILED",
                    succeeded ? summary : null, succeeded ? null : error,
                    at.toEpochMilli(), context.stepId());
        }
        if ("SUCCEEDED".equals(status)) {
            jdbc.update("""
                    INSERT INTO remote_steering_code_subject_v257(
                        request_id, task_id, task_epoch, remote_stage_id,
                        stage_generation, stage_turn_id,
                        source_code_fingerprint, source_head_sha, source_base_sha,
                        code_fingerprint, head_sha, base_sha, recorded_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, context.rowId(), context.taskId(), context.taskEpoch(),
                    context.stageId(), context.stageGeneration(), context.turnId(),
                    context.codeFingerprint(), context.headSha(), context.baseSha(),
                    output.codeFingerprint(), output.headSha(), output.baseSha(),
                    at.toEpochMilli());
        }
        jdbc.update("""
                INSERT INTO remote_repair_steering_delivery_v257(
                    request_id, operation_id, raw_outcome, raw_result_digest,
                    acceptance, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, context.rowId(), context.operationId(), rawOutcome,
                rawDigest, acceptance, at.toEpochMilli());
    }

    public void finishBrain(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            String status,
            String verdict,
            int findingCount,
            String summary,
            String error,
            Instant at)
    {
        requireTransaction();
        finishTurn("task_turn", context, status, error, at);
        CodeSubject unchanged = "SUCCEEDED".equals(status)
                ? new CodeSubject(
                        context.codeFingerprint(), context.headSha(),
                        context.baseSha())
                : null;
        finishOperation(context, status, unchanged, summary, error, at);
        if ("BRANCH".equals(context.family())) {
            finishBranchStep(context, status, summary, error, at);
        }
        if ("SUCCEEDED".equals(status)) {
            String table = "CI".equals(context.family())
                    ? "ci_repair_brain_verdict"
                    : "branch_sync_brain_verdict";
            String key = "CI".equals(context.family())
                    ? "ci_repair_operation_id"
                    : "branch_sync_dispatch_operation_id";
            jdbc.update("INSERT INTO " + table + "(" + key
                            + ", task_turn_id, verdict, finding_count, summary,"
                            + " recorded_at_ms) VALUES (?, ?, ?, ?, ?, ?)",
                    context.rowId(), context.turnId(), verdict, findingCount,
                    summary, at.toEpochMilli());
        }
        insertReceipt(context, rawOutcome, rawDigest, acceptance, at);
    }

    public void openEpisodeBlocker(
            String family,
            String taskId,
            String stageId,
            String episodeId,
            String subject,
            String blockerType,
            String payload,
            Instant at)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'EPISODE', ?, ?, ?, 'OPEN', ?, ?)
                ON CONFLICT(id) DO NOTHING
                """, id("remote-repair-blocker",
                        family + ":" + episodeId + ":" + blockerType),
                taskId, stageId, episodeId, subject, blockerType, payload,
                at.toEpochMilli());
    }

    private EffectRequest insertCiEffect(
            RepairContext context,
            CiEpisode episode,
            String kind,
            String operationKind,
            String family,
            String callback,
            int laneMask,
            boolean writer,
            Instant at)
    {
        int attempt = episode.fixAttemptCount();
        String suffix = episode.id() + ":" + kind.toLowerCase(Locale.ROOT)
                + ":" + attempt;
        String rowId = id("ci-repair-operation-row", suffix);
        String operationId = id("ci-repair-operation", suffix);
        String ticketId = id("ci-repair-ticket", suffix);
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), kind,
                operationId, attempt, context.codeFingerprint(),
                context.headSha(), context.baseSha(), at.toEpochMilli());
        insertTicket(
                ticketId, operationId, operationKind, family, "STAGE",
                context.stageId(), callback, laneMask, true, writer, context,
                attempt, at);
        dispatchCi(rowId);
        if ("VALIDATE".equals(kind)) {
            updateOne("""
                    UPDATE ci_repair_episode SET status = 'VALIDATING'
                    WHERE id = ? AND status = 'FIXING'
                    """, "CI Episode changed before validation", episode.id());
        }
        return new EffectRequest(
                rowId, episode.id(), operationId, ticketId, kind, attempt);
    }

    private SteeringOwner requireSteeringOwner(Request request)
    {
        List<SteeringOwner> rows = jdbc.query("""
                SELECT 'CI' AS family, 'CI_REPAIR' AS owner_family,
                       operation.ci_repair_episode_id AS ci_episode_id,
                       NULL AS branch_episode_id, NULL AS branch_step_id
                FROM ci_repair_operation operation
                WHERE operation.operation_id = ?
                  AND operation.stage_turn_id = ?
                  AND operation.task_id = ? AND operation.task_epoch = ?
                  AND operation.remote_development_stage_id = ?
                  AND operation.stage_generation = ?
                  AND operation.kind = 'FIX_STAGE_TURN'
                UNION ALL
                SELECT 'BRANCH', 'BRANCH_REPAIR', NULL,
                       operation.branch_sync_episode_id,
                       operation.branch_sync_effect_step_id
                FROM branch_sync_dispatch_operation operation
                WHERE operation.operation_id = ?
                  AND operation.stage_turn_id = ?
                  AND operation.task_id = ? AND operation.task_epoch = ?
                  AND operation.remote_development_stage_id = ?
                  AND operation.stage_generation = ?
                  AND operation.kind = 'CONFLICT_REPAIR'
                """, (rs, row) -> new SteeringOwner(
                        rs.getString("family"), rs.getString("owner_family"),
                        rs.getString("ci_episode_id"),
                        rs.getString("branch_episode_id"),
                        rs.getString("branch_step_id")),
                request.predecessor().operationId(),
                request.predecessor().ownerId(), request.taskId(),
                request.taskEpoch(), request.stageId(), request.stageGeneration(),
                request.predecessor().operationId(),
                request.predecessor().ownerId(), request.taskId(),
                request.taskEpoch(), request.stageId(), request.stageGeneration());
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Remote repair steering predecessor is not exact");
        }
        return rows.getFirst();
    }

    private void supersedeUndeliveredPredecessor(Request request, Instant at)
    {
        jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUPERSEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?,
                    error_message = 'replaced by durable user steering'
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, at.toEpochMilli(), request.predecessor().ownerId(),
                request.predecessor().operationId());
        jdbc.update("""
                UPDATE ci_repair_operation
                SET status = 'SUPERSEDED', completed_at_ms = ?,
                    error_message = 'replaced by durable user steering'
                WHERE operation_id = ? AND stage_turn_id = ?
                  AND status = 'DISPATCHED'
                """, at.toEpochMilli(), request.predecessor().operationId(),
                request.predecessor().ownerId());
        jdbc.update("""
                UPDATE branch_sync_dispatch_operation
                SET status = 'SUPERSEDED', completed_at_ms = ?,
                    error_message = 'replaced by durable user steering'
                WHERE operation_id = ? AND stage_turn_id = ?
                  AND status = 'DISPATCHED'
                """, at.toEpochMilli(), request.predecessor().operationId(),
                request.predecessor().ownerId());
    }

    private void insertStageTurn(
            String turnId,
            String operationId,
            String purpose,
            int attempt,
            RepairContext context,
            String launchInput,
            String lane,
            Instant at)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turnId, context.stageId(), context.stageGeneration(),
                purpose, operationId, attempt, context.taskEpoch(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                lane, launchInput, at.toEpochMilli());
    }

    private void insertTaskTurn(
            String turnId,
            String operationId,
            String purpose,
            int attempt,
            RepairContext context,
            String launchInput,
            String lane,
            Instant at)
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turnId, context.taskId(), purpose, operationId, attempt,
                context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), lane, launchInput,
                at.toEpochMilli());
    }

    private void insertTicket(
            String ticketId,
            String operationId,
            String operationKind,
            String family,
            String ownerKind,
            String ownerId,
            String callback,
            int laneMask,
            boolean exclusive,
            boolean writer,
            RepairContext context,
            int attempt,
            Instant at)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, operationKind, family, ownerKind,
                ownerId, callback, laneMask, exclusive ? 1 : 0,
                writer ? 1 : 0, context.workspaceId(), context.trunkId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), attempt, context.codeFingerprint(),
                context.headSha(), context.baseSha(), at.toEpochMilli());
    }

    private void dispatchCi(String rowId)
    {
        updateOne("""
                UPDATE ci_repair_operation SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "CI repair Turn did not dispatch", rowId);
    }

    private void dispatchBranch(String rowId)
    {
        updateOne("""
                UPDATE branch_sync_dispatch_operation SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Branch repair Turn did not dispatch", rowId);
    }

    private void claimBranch(
            BranchStep step, String operationId, int attempt, Instant at)
    {
        updateOne("""
                UPDATE branch_sync_effect_step
                SET status = 'CLAIMED', attempt_count = ?,
                    claim_mode = 'EXECUTE', claim_owner = ?,
                    claimed_at_ms = ?, lease_until_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                  AND attempt_count = ?
                """, "Branch repair step changed before dispatch", attempt,
                operationId, at.toEpochMilli(), at.plusSeconds(60).toEpochMilli(),
                step.id(), step.attemptCount());
    }

    private void finishTurn(
            String table,
            TurnDelivery context,
            String status,
            String error,
            Instant at)
    {
        int changed = jdbc.update("UPDATE " + table + " SET status = ?, "
                        + "started_at_ms = COALESCE(started_at_ms, requested_at_ms), "
                        + "finished_at_ms = ?, error_message = ? "
                        + "WHERE id = ? AND operation_id = ? "
                        + "AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')",
                status, at.toEpochMilli(), error, context.turnId(),
                context.operationId());
        if (changed != 1) {
            throw new IllegalStateException("Remote repair Turn changed before delivery");
        }
    }

    private void finishOperation(
            TurnDelivery context,
            String status,
            CodeSubject output,
            String evidence,
            String error,
            Instant at)
    {
        String table = "CI".equals(context.family())
                ? "ci_repair_operation" : "branch_sync_dispatch_operation";
        int changed = jdbc.update("UPDATE " + table + " SET status = ?, "
                        + "result_code_fingerprint = ?, result_head_sha = ?, "
                        + "result_evidence = ?, completed_at_ms = ?, "
                        + "error_message = ? WHERE id = ? AND status = 'DISPATCHED'",
                status, output == null ? null : output.codeFingerprint(),
                output == null ? null : output.headSha(), evidence,
                at.toEpochMilli(), error, context.rowId());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Remote repair Operation changed before delivery");
        }
    }

    private void finishBranchStep(
            TurnDelivery context,
            String status,
            String evidence,
            String error,
            Instant at)
    {
        boolean succeeded = "SUCCEEDED".equals(status);
        updateOne("""
                UPDATE branch_sync_effect_step
                SET status = ?, claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    evidence = ?, last_error = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'CLAIMED'
                """, "Branch repair step changed before delivery",
                succeeded ? "SUCCEEDED" : "FAILED",
                succeeded ? evidence : null, succeeded ? null : error,
                at.toEpochMilli(), context.stepId());
    }

    private void insertRemoteCodeSubject(
            TurnDelivery context, CodeSubject output, Instant at)
    {
        jdbc.update("""
                INSERT INTO remote_code_subject(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, stage_turn_id,
                    source_code_fingerprint, source_head_sha, source_base_sha,
                    code_fingerprint, head_sha, base_sha, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id("remote-code-subject", context.operationId()),
                context.stageId(), context.taskId(), context.taskEpoch(),
                context.stageGeneration(), context.turnId(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                output.codeFingerprint(), output.headSha(), output.baseSha(),
                at.toEpochMilli());
    }

    private void insertWorktreeSubject(
            TurnDelivery context, CodeSubject output, Instant at)
    {
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM remote_worktree_subject
                WHERE task_id = ? AND task_epoch = ?
                """, Integer.class, context.taskId(), context.taskEpoch());
        jdbc.update("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha,
                    base_sha, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id("remote-worktree-subject", context.operationId()),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), revision,
                "CI".equals(context.family())
                        ? "CI_STAGE_TURN" : "BRANCH_STAGE_TURN",
                context.operationId(), output.codeFingerprint(),
                output.headSha(), output.baseSha(), at.toEpochMilli());
    }

    private void insertReceipt(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            Instant at)
    {
        String table = "CI".equals(context.family())
                ? "ci_repair_delivery_receipt"
                : "branch_sync_delivery_receipt";
        String key = "CI".equals(context.family())
                ? "ci_repair_operation_id"
                : "branch_sync_dispatch_operation_id";
        jdbc.update("INSERT INTO " + table + "(" + key
                        + ", operation_id, raw_outcome, raw_result_digest, "
                        + "acceptance, recorded_at_ms) VALUES (?, ?, ?, ?, ?, ?)",
                context.rowId(), context.operationId(), rawOutcome, rawDigest,
                acceptance, at.toEpochMilli());
    }

    private static RepairContext repairContext(ResultSet rs)
            throws SQLException
    {
        return new RepairContext(
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("worktree_path"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("current_head_sha"),
                rs.getString("current_base_sha"));
    }

    private static TurnDelivery turnDelivery(ResultSet rs)
            throws SQLException
    {
        return new TurnDelivery(
                rs.getString("family"), rs.getString("row_id"),
                rs.getString("episode_id"), rs.getString("step_id"),
                rs.getString("kind"), rs.getString("operation_id"),
                rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"), rs.getString("turn_id"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("worktree_path"), rs.getLong("task_version"),
                rs.getLong("stage_version"), rs.getBoolean("is_current"));
    }

    private static BrainRequest brainRequest(
            String family,
            String rowId,
            String episodeId,
            String stepId,
            String turnId,
            String operationId,
            String ticketId,
            int attempt,
            RepairContext context)
    {
        return new BrainRequest(
                family, rowId, episodeId, stepId, turnId, operationId,
                ticketId, attempt, new ResultFence(
                        context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), operationId, attempt,
                        context.codeFingerprint(), context.headSha(),
                        context.baseSha()));
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Remote repair Turn mutation requires a Task transaction");
        }
    }

    private void updateOne(String sql, String failure, Object... arguments)
    {
        if (jdbc.update(sql, arguments) != 1) {
            throw new IllegalStateException(failure);
        }
    }

    public record RepairContext(
            String workspaceId,
            String trunkId,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String worktreePath,
            String workModelSnapshot,
            String provider,
            String model,
            String roleSkill,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String remoteHeadSha,
            String remoteBaseSha) {}

    public record TurnRequest(
            String family,
            String rowId,
            String episodeId,
            String stepId,
            String turnId,
            String operationId,
            String ticketId,
            int attempt) {}

    public record EffectRequest(
            String rowId,
            String episodeId,
            String operationId,
            String ticketId,
            String kind,
            int attempt) {}

    public record BrainRequest(
            String family,
            String rowId,
            String episodeId,
            String stepId,
            String turnId,
            String operationId,
            String ticketId,
            int attempt,
            ResultFence fence) {}

    public record TurnDelivery(
            String family,
            String rowId,
            String episodeId,
            String stepId,
            String kind,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            String turnId,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String worktreePath,
            long taskVersion,
            long stageVersion,
            boolean current)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    semanticAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record CodeSubject(
            String codeFingerprint, String headSha, String baseSha) {}

    private record SteeringOwner(
            String family, String ownerFamily, String ciEpisodeId,
            String branchEpisodeId, String branchStepId) {}
}
