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
                       policy.id AS automation_policy_id,
                       policy.auto_approve = 1
                           AND policy.stewardship_exception = 0
                           AS auto_approve,
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
                JOIN task_automation_policy policy ON policy.id = (
                    SELECT latest.id FROM task_automation_policy latest
                    WHERE latest.task_id = task.id
                    ORDER BY latest.revision DESC LIMIT 1)
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
        return insertCiStageTurn(
                context, episode, null, launchInput, deliveryLane,
                laneMask, at);
    }

    public TurnRequest insertCiBaseRepairStageTurn(
            RepairContext context,
            CiEpisode episode,
            String authorizationId,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireText(authorizationId, "authorizationId");
        return insertCiStageTurn(
                context, episode, authorizationId, launchInput,
                deliveryLane, laneMask, at);
    }

    private TurnRequest insertCiStageTurn(
            RepairContext context,
            CiEpisode episode,
            String authorizationId,
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
                    expected_base_sha, base_repair_authorization_id,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'FIX_STAGE_TURN', ?, ?, ?, ?, ?, ?,
                    ?, 'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), operationId,
                attempt, turnId, context.codeFingerprint(), context.headSha(),
                context.baseSha(), authorizationId, at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_STAGE_TURN", "AGENT_TURN",
                "STAGE_TURN", turnId, "REMOTE_CI_STAGE_TURN_RESULT",
                laneMask, true, true, context, attempt, at);
        dispatchCi(rowId);
        updateOne("""
                UPDATE ci_repair_episode
                SET status = 'FIXING'
                WHERE id = ? AND fix_attempt_count = ?
                  AND status IN ('OPEN', 'VALIDATING', 'AWAITING_PUSH_CI')
                """, "CI fix Episode changed before dispatch",
                episode.id(), episode.fixAttemptCount());
        return new TurnRequest(
                "CI", rowId, episode.id(), null, turnId, operationId,
                ticketId, attempt);
    }

    /**
     * Starts the one automatic execution continuation authorized by an exact
     * equal-tree result. Its execution fence advances while its semantic CI
     * budget attempt deliberately does not.
     */
    public TurnRequest insertCiFixContinuation(
            RepairContext context,
            CiEpisode episode,
            CiFixContinuationDue due,
            String rowId,
            String turnId,
            String operationId,
            String ticketId,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        requireTransaction();
        if (!episode.id().equals(due.episodeId())
                || due.semanticAttempt() != episode.fixAttemptCount() + 1) {
            throw new IllegalArgumentException(
                    "CI fix continuation due is not exact");
        }
        int executionAttempt = due.executionAttempt();
        insertStageTurn(
                turnId, operationId, "REMOTE_CI_REPAIR", executionAttempt,
                context, launchInput, deliveryLane, at);
        jdbc.update("""
                INSERT INTO ci_repair_fix_continuation_operation_v318(
                    id, ci_repair_episode_id, continuation_due_id,
                    predecessor_operation_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, stage_turn_id, operation_id,
                    dispatch_ticket_id, semantic_attempt, execution_attempt,
                    base_repair_authorization_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, episode.id(), due.id(), due.predecessorOperationId(),
                context.stageId(), context.taskId(), context.taskEpoch(),
                context.stageGeneration(), turnId, operationId, ticketId,
                due.semanticAttempt(), executionAttempt,
                due.baseRepairAuthorizationId(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                at.toEpochMilli());
        insertTicket(
                ticketId, operationId, "EXECUTE_STAGE_TURN", "AGENT_TURN",
                "STAGE_TURN", turnId, "REMOTE_CI_STAGE_TURN_RESULT",
                laneMask, true, true, context, executionAttempt, at);
        updateOne("""
                UPDATE ci_repair_fix_continuation_operation_v318
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "CI fix continuation did not dispatch", rowId);
        updateOne("""
                UPDATE ci_repair_fix_continuation_due_v318
                SET status = 'DISPATCHED', continuation_operation_id = ?,
                    consumed_at_ms = ?
                WHERE id = ? AND status = 'PENDING'
                """, "CI fix continuation due changed before dispatch",
                rowId, at.toEpochMilli(), due.id());
        return new TurnRequest(
                "CI", rowId, episode.id(), null, turnId, operationId,
                ticketId, executionAttempt);
    }

    public EffectRequest insertCiValidation(
            RepairContext context, CiEpisode episode, Instant at)
    {
        requireTransaction();
        return insertCiEffect(
                context, episode, "VALIDATE", "VALIDATE_REMOTE_CI_REPAIR",
                "VALIDATION", "REMOTE_CI_VALIDATION_RESULT", 4,
                false, null, null, at);
    }

    public EffectRequest insertCiBaseRewriteValidation(
            RepairContext context,
            CiEpisode episode,
            String authorizationId,
            Instant at)
    {
        requireTransaction();
        requireText(authorizationId, "authorizationId");
        return insertCiEffect(
                context, episode, "VALIDATE",
                "REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR", "VALIDATION",
                "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT", 4,
                true, authorizationId, null, at);
    }

    public BrainRequest insertCiBrain(
            RepairContext context,
            CiEpisode episode,
            String launchInput,
            String deliveryLane,
            int laneMask,
            Instant at)
    {
        return insertCiBrain(
                context, episode, null, launchInput, deliveryLane,
                laneMask, at);
    }

    public BrainRequest insertCiBrain(
            RepairContext context,
            CiEpisode episode,
            String authorizationId,
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
                    expected_base_sha, base_repair_authorization_id,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'BRAIN_REVIEW', ?, ?, ?, ?, ?, ?,
                    ?, 'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), operationId,
                attempt, turnId, context.codeFingerprint(), context.headSha(),
                context.baseSha(), authorizationId, at.toEpochMilli());
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
        return insertCiPush(context, episode, null, at);
    }

    public EffectRequest insertCiPush(
            RepairContext context,
            CiEpisode episode,
            String authorizationId,
            Instant at)
    {
        requireTransaction();
        return insertCiEffect(
                context, episode, "PUSH_HEAD", "PUSH_REMOTE_CI_REPAIR",
                "GITHUB_EFFECT", "REMOTE_CI_PUSH_RESULT", 48,
                true, authorizationId, context.remoteHeadSha(), at);
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
                       'STEERING' AS kind,
                       NULL AS base_repair_authorization_id,
                       steering.operation_id,
                       request.task_id, request.task_epoch,
                       request.stage_id, request.stage_generation,
                       steering.semantic_attempt,
                       steering.semantic_attempt AS execution_attempt,
                       steering.stage_turn_id AS turn_id,
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
                            THEN 1 ELSE 0 END AS is_current,
                       0 AS is_replacement
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

    public Optional<EffectDeliveryReceipt> findReplacementReceipt(
            String operationId)
    {
        return jdbc.query("""
                SELECT delivery.replacement_operation_id AS row_id,
                       delivery.operation_id, operation.task_id,
                       delivery.raw_outcome, delivery.raw_result_digest,
                       delivery.acceptance, delivery.recorded_at_ms
                FROM remote_repair_brain_replacement_delivery_v309 delivery
                JOIN remote_repair_brain_replacement_operation_v309 operation
                  ON operation.id = delivery.replacement_operation_id
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

    public Optional<EffectDeliveryReceipt> findCiFixContinuationReceipt(
            String operationId)
    {
        return jdbc.query("""
                SELECT delivery.continuation_operation_id AS row_id,
                       delivery.operation_id, operation.task_id,
                       delivery.raw_outcome, delivery.raw_result_digest,
                       delivery.acceptance, delivery.recorded_at_ms
                FROM ci_repair_fix_continuation_delivery_v318 delivery
                JOIN ci_repair_fix_continuation_operation_v318 operation
                  ON operation.id = delivery.continuation_operation_id
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
                UNION ALL
                SELECT operation.task_id
                FROM remote_repair_brain_replacement_operation_v309 operation
                WHERE operation.task_turn_id = ?
                  AND operation.operation_id = ?
                UNION ALL
                SELECT operation.task_id
                FROM ci_repair_fix_continuation_operation_v318 operation
                WHERE operation.stage_turn_id = ?
                  AND operation.operation_id = ?
                """, (rs, row) -> rs.getString(1), turnId, operationId,
                turnId, operationId, turnId, operationId,
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
                       operation.base_repair_authorization_id,
                       operation.operation_id, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       operation.semantic_attempt AS execution_attempt,
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
                            THEN 1 ELSE 0 END AS is_current,
                       0 AS is_replacement,
                       0 AS is_continuation
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
                       NULL,
                       operation.operation_id, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       operation.semantic_attempt AS execution_attempt,
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
                            THEN 1 ELSE 0 END,
                       0,
                       0
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
                UNION ALL
                SELECT operation.family, operation.id,
                       COALESCE(operation.ci_repair_episode_id,
                           operation.branch_sync_episode_id),
                       operation.branch_sync_effect_step_id, 'BRAIN_REVIEW',
                       operation.base_repair_authorization_id,
                       operation.operation_id, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       operation.execution_attempt,
                       operation.task_turn_id,
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
                              AND owner.kind = 'REMOTE_DEVELOPMENT'
                              AND owner.generation = operation.stage_generation
                              AND owner.completed_at_ms IS NULL
                              AND remote.generation = operation.stage_generation
                              AND code.code_fingerprint IS
                                  operation.expected_code_fingerprint
                              AND code.head_sha = operation.expected_head_sha
                              AND code.base_sha = operation.expected_base_sha
                              AND ((operation.family = 'CI'
                                    AND ci.status = 'AWAITING_PUSH_CI'
                                    AND remote.current_head_sha = COALESCE(
                                        ci.last_pushed_head_sha,
                                        ci.subject_head_sha)
                                    AND remote.current_base_sha =
                                        ci.subject_base_sha)
                                OR (operation.family = 'BRANCH'
                                    AND branch.status = 'BRAIN_REVIEW'
                                    AND step.kind = 'BRAIN_REVIEW'
                                    AND step.status = 'CLAIMED'
                                    AND step.claim_owner =
                                        operation.operation_id
                                    AND step.attempt_count =
                                        operation.semantic_attempt
                                    AND remote.current_head_sha =
                                        branch.old_head_sha
                                    AND remote.current_base_sha =
                                        branch.observed_base_sha))
                            THEN 1 ELSE 0 END,
                       1,
                       0
                FROM remote_repair_brain_replacement_operation_v309 operation
                JOIN tasks task ON task.id = operation.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner
                  ON owner.id = operation.remote_development_stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                 AND remote.task_id = operation.task_id
                LEFT JOIN ci_repair_episode ci
                  ON operation.family = 'CI'
                 AND ci.id = operation.ci_repair_episode_id
                 AND ci.remote_development_stage_id =
                     operation.remote_development_stage_id
                 AND ci.task_id = operation.task_id
                 AND ci.task_epoch = operation.task_epoch
                 AND ci.stage_generation = operation.stage_generation
                LEFT JOIN branch_sync_episode branch
                  ON operation.family = 'BRANCH'
                 AND branch.id = operation.branch_sync_episode_id
                 AND branch.remote_development_stage_id =
                     operation.remote_development_stage_id
                 AND branch.task_id = operation.task_id
                 AND branch.task_epoch = operation.task_epoch
                 AND branch.stage_generation = operation.stage_generation
                LEFT JOIN branch_sync_effect_step step
                  ON operation.family = 'BRANCH'
                 AND step.id = operation.branch_sync_effect_step_id
                 AND step.branch_sync_episode_id = branch.id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = operation.task_id
                JOIN task_code_identity identity
                  ON identity.task_id = operation.task_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.task_turn_id = ?
                  AND operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                UNION ALL
                SELECT 'CI', operation.id, operation.ci_repair_episode_id,
                       NULL, 'FIX_STAGE_TURN',
                       operation.base_repair_authorization_id,
                       operation.operation_id, operation.task_id,
                       operation.task_epoch,
                       operation.remote_development_stage_id,
                       operation.stage_generation, operation.semantic_attempt,
                       operation.execution_attempt, operation.stage_turn_id,
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
                              AND owner.kind = 'REMOTE_DEVELOPMENT'
                              AND owner.completed_at_ms IS NULL
                              AND episode.status = 'FIXING'
                              AND remote.current_head_sha = COALESCE(
                                  episode.last_pushed_head_sha,
                                  episode.subject_head_sha)
                              AND remote.current_base_sha =
                                  episode.subject_base_sha
                              AND code.code_fingerprint =
                                  operation.expected_code_fingerprint
                              AND code.head_sha = operation.expected_head_sha
                              AND code.base_sha = operation.expected_base_sha
                            THEN 1 ELSE 0 END,
                       0,
                       1
                FROM ci_repair_fix_continuation_operation_v318 operation
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
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.stage_turn_id = ?
                  AND operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> turnDelivery(rs), turnId, operationId,
                turnId, operationId, turnId, operationId,
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

    public void finishChangedCiStageTurn(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            CodeSubject output,
            String sourceTreeSha,
            String resultTreeSha,
            String evidence,
            Instant at)
    {
        requireTransaction();
        requireCiFix(context);
        requireText(sourceTreeSha, "sourceTreeSha");
        requireText(resultTreeSha, "resultTreeSha");
        if (sourceTreeSha.equals(resultTreeSha)) {
            throw new IllegalArgumentException("Changed CI fix has an equal tree");
        }
        finishTurn("stage_turn", context, "SUCCEEDED", null, at);
        finishOperation(context, "SUCCEEDED", output, evidence, null, at);
        insertReceipt(
                context, rawOutcome, rawDigest,
                DispatchTicket.Acceptance.ACCEPTED.name(), at);
        insertCiFixTreeResult(
                context, "CHANGED", sourceTreeSha, resultTreeSha,
                rawDigest, at);
        updateOne("""
                UPDATE ci_repair_episode
                SET fix_attempt_count = fix_attempt_count + 1
                WHERE id = ? AND fix_attempt_count = ?
                  AND status = 'FIXING'
                """, "CI fix budget changed before accepted tree result",
                context.episodeId(), context.semanticAttempt() - 1);
        insertRemoteCodeSubject(context, output, at);
        insertWorktreeSubject(context, output, at);
    }

    /** Records a successful provider execution whose committed tree is equal. */
    public int finishNoChangeCiStageTurn(
            TurnDelivery context,
            String rawOutcome,
            String rawDigest,
            String sourceTreeSha,
            String resultTreeSha,
            String summary,
            Instant at)
    {
        requireTransaction();
        requireCiFix(context);
        requireText(sourceTreeSha, "sourceTreeSha");
        requireText(resultTreeSha, "resultTreeSha");
        if (!sourceTreeSha.equals(resultTreeSha)) {
            throw new IllegalArgumentException("No-change CI fix changed its tree");
        }
        String error = "CI_REPAIR_NO_CHANGE: " + summary;
        finishTurn("stage_turn", context, "FAILED", error, at);
        finishOperation(context, "FAILED", null, summary, error, at);
        insertReceipt(
                context, rawOutcome, rawDigest,
                DispatchTicket.Acceptance.ACCEPTED.name(), at);
        insertCiFixTreeResult(
                context, "NO_CHANGE", sourceTreeSha, resultTreeSha,
                rawDigest, at);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ci_repair_fix_tree_result_v318
                WHERE ci_repair_episode_id = ? AND semantic_attempt = ?
                  AND disposition = 'NO_CHANGE'
                """, Integer.class, context.episodeId(),
                context.semanticAttempt());
        int exactCount = requireNonNull(count, "CI no-change count is null");
        if (exactCount == 1) {
            String treeResultId = id(
                    "ci-repair-fix-tree-result", context.operationId());
            AcceptedRemoteSubject accepted = requireAcceptedRemoteSubject(
                    context.episodeId());
            jdbc.update("""
                    INSERT INTO ci_repair_fix_continuation_due_v318(
                        id, ci_repair_episode_id, predecessor_tree_result_id,
                        predecessor_operation_id, predecessor_stage_turn_id,
                        predecessor_accepted_snapshot_id,
                        predecessor_accepted_observation_revision,
                        semantic_attempt, execution_attempt, status,
                        recorded_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, id("ci-repair-fix-continuation-due",
                            context.operationId()),
                    context.episodeId(), treeResultId, context.operationId(),
                    context.turnId(), accepted.snapshotId(),
                    accepted.observationRevision(), context.semanticAttempt(),
                    context.executionAttempt() + 1, at.toEpochMilli());
        }
        return exactCount;
    }

    public Optional<CiFixContinuationDue> findPendingCiFixContinuation(
            String episodeId)
    {
        return jdbc.query("""
                SELECT due.id, due.ci_repair_episode_id,
                       due.predecessor_operation_id,
                       due.predecessor_stage_turn_id,
                       due.predecessor_accepted_snapshot_id,
                       due.predecessor_accepted_observation_revision,
                       due.semantic_attempt, due.execution_attempt,
                       operation.base_repair_authorization_id,
                       due.recorded_at_ms
                FROM ci_repair_fix_continuation_due_v318 due
                LEFT JOIN ci_repair_operation operation
                  ON operation.operation_id = due.predecessor_operation_id
                LEFT JOIN ci_repair_fix_continuation_operation_v318 continuation
                  ON continuation.operation_id = due.predecessor_operation_id
                WHERE due.ci_repair_episode_id = ? AND due.status = 'PENDING'
                """, (rs, row) -> new CiFixContinuationDue(
                        rs.getString("id"),
                        rs.getString("ci_repair_episode_id"),
                        rs.getString("predecessor_operation_id"),
                        rs.getString("predecessor_stage_turn_id"),
                        rs.getString("predecessor_accepted_snapshot_id"),
                        rs.getInt("predecessor_accepted_observation_revision"),
                        rs.getInt("semantic_attempt"),
                        rs.getInt("execution_attempt"),
                        rs.getString("base_repair_authorization_id"),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                episodeId).stream().findFirst();
    }

    public CiNextFixDue insertCiNextFixDue(
            CiEpisode episode, String sourceKind, String prompt, Instant at)
    {
        requireTransaction();
        int requestedAttempt = episode.fixAttemptCount() + 1;
        AcceptedRemoteSubject accepted = requireAcceptedRemoteSubject(
                episode.id());
        String dueId = id("ci-repair-next-fix-due",
                episode.id() + ":" + requestedAttempt);
        jdbc.update("""
                INSERT INTO ci_repair_next_fix_due_v318(
                    id, ci_repair_episode_id, source_kind,
                    source_semantic_attempt, requested_semantic_attempt,
                    predecessor_accepted_snapshot_id,
                    predecessor_accepted_observation_revision,
                    prompt, status, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, dueId, episode.id(), sourceKind,
                episode.fixAttemptCount(), requestedAttempt,
                accepted.snapshotId(), accepted.observationRevision(), prompt,
                at.toEpochMilli());
        return findPendingCiNextFix(episode.id()).orElseThrow();
    }

    public Optional<CiNextFixDue> findPendingCiNextFix(String episodeId)
    {
        return jdbc.query("""
                SELECT id, ci_repair_episode_id, source_kind,
                       source_semantic_attempt, requested_semantic_attempt,
                       predecessor_accepted_snapshot_id,
                       predecessor_accepted_observation_revision,
                       prompt, recorded_at_ms
                FROM ci_repair_next_fix_due_v318
                WHERE ci_repair_episode_id = ? AND status = 'PENDING'
                """, (rs, row) -> new CiNextFixDue(
                        rs.getString("id"),
                        rs.getString("ci_repair_episode_id"),
                        rs.getString("source_kind"),
                        rs.getInt("source_semantic_attempt"),
                        rs.getInt("requested_semantic_attempt"),
                        rs.getString("predecessor_accepted_snapshot_id"),
                        rs.getInt("predecessor_accepted_observation_revision"),
                        rs.getString("prompt"),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                episodeId)
                .stream().findFirst();
    }

    public void consumeCiNextFixDue(CiNextFixDue due, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE ci_repair_next_fix_due_v318
                SET status = 'DISPATCHED',
                    dispatched_operation_row_id = (
                        SELECT id FROM ci_repair_operation
                        WHERE ci_repair_episode_id = ?
                          AND kind = 'FIX_STAGE_TURN'
                          AND semantic_attempt = ?
                          AND status = 'DISPATCHED'),
                    consumed_at_ms = ?
                WHERE id = ? AND status = 'PENDING'
                """, "Next CI fix due changed before dispatch",
                due.episodeId(), due.requestedSemanticAttempt(),
                at.toEpochMilli(), due.id());
    }

    private AcceptedRemoteSubject requireAcceptedRemoteSubject(String episodeId)
    {
        List<AcceptedRemoteSubject> rows = jdbc.query("""
                SELECT remote.accepted_snapshot_id,
                       remote.accepted_observation_revision
                FROM ci_repair_episode episode
                JOIN remote_development_stage remote
                  ON remote.stage_id = episode.remote_development_stage_id
                WHERE episode.id = ? AND remote.accepted_snapshot_id IS NOT NULL
                """, (rs, row) -> new AcceptedRemoteSubject(
                        rs.getString("accepted_snapshot_id"),
                        rs.getInt("accepted_observation_revision")), episodeId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "CI repair accepted Remote predecessor is missing");
        }
        return rows.getFirst();
    }

    public String requireStageTurnLaunchInput(String turnId)
    {
        List<String> rows = jdbc.query("""
                SELECT launch_input FROM stage_turn WHERE id = ?
                """, (rs, row) -> rs.getString(1), turnId);
        if (rows.size() != 1) {
            throw new IllegalStateException("CI repair StageTurn input is missing");
        }
        return rows.getFirst();
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
        if (context.replacement()) {
            finishReplacementBrain(
                    context, status, verdict, findingCount,
                    summary, error, at);
        }
        else {
            finishOperation(context, status, unchanged, summary, error, at);
        }
        if ("BRANCH".equals(context.family())) {
            finishBranchStep(context, status, summary, error, at);
        }
        if (!context.replacement() && "SUCCEEDED".equals(status)) {
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

    private void finishReplacementBrain(
            TurnDelivery context,
            String status,
            String verdict,
            int findingCount,
            String summary,
            String error,
            Instant at)
    {
        updateOne("""
                UPDATE remote_repair_brain_replacement_operation_v309
                SET status = ?, result_evidence = ?, verdict = ?,
                    finding_count = ?, result_summary = ?, completed_at_ms = ?,
                    error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, "Remote repair replacement Operation changed before delivery",
                status, summary, verdict,
                verdict == null ? null : findingCount, summary,
                at.toEpochMilli(), error, context.rowId());
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

    public String openBrainFailureBlocker(
            TurnDelivery context, String detail, Instant at)
    {
        requireTransaction();
        String blockerId = id("remote-repair-brain-failure-blocker",
                context.turnId());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, owner_kind, owner_id, subject_revision,
                    blocker_type, status, payload_json, opened_at_ms)
                VALUES (?, ?, 'TASK', ?, ?, 'REMOTE_REPAIR_BRAIN_FAILED',
                    'OPEN', ?, ?)
                ON CONFLICT(id) DO NOTHING
                """, blockerId, context.taskId(), context.taskId(),
                context.turnId(), detail, at.toEpochMilli());
        return blockerId;
    }

    public String recordBrainFailure(
            TurnDelivery context,
            String blockerId,
            String rawOutcome,
            String rawDigest,
            String error,
            long clearedTaskVersion,
            Instant at)
    {
        requireTransaction();
        String receiptId = id(
                "remote-repair-brain-failure-receipt",
                context.operationId());
        jdbc.update("""
                INSERT INTO remote_repair_brain_failure_receipt_v309(
                    id, family, source_kind, source_operation_row_id,
                    ci_repair_episode_id, branch_sync_episode_id,
                    branch_sync_effect_step_id, base_repair_authorization_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, task_turn_id, operation_id,
                    semantic_attempt, execution_attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, blocker_id, raw_outcome,
                    raw_result_digest, error_message, cleared_task_version,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?)
                """, receiptId, context.family(),
                context.replacement() ? "REPLACEMENT" : "ORIGINAL",
                context.rowId(),
                "CI".equals(context.family()) ? context.episodeId() : null,
                "BRANCH".equals(context.family()) ? context.episodeId() : null,
                context.stepId(), context.baseRepairAuthorizationId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.turnId(),
                context.operationId(), context.semanticAttempt(),
                context.executionAttempt(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), blockerId, rawOutcome,
                rawDigest, error, clearedTaskVersion, at.toEpochMilli());
        return receiptId;
    }

    public Optional<BrainRetryReceipt> findBrainRetryReceipt(
            String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT family, task_id, stage_id, episode_id, failed_turn_id,
                       blocker_id, command_id, actor, reason,
                       replacement_turn_id, replacement_operation_id,
                       replacement_ticket_id, recorded_at_ms
                FROM remote_repair_brain_retry_command_v309
                WHERE task_id = ? AND command_id = ?
                """, (rs, row) -> brainRetryReceipt(rs), taskId, commandId)
                .stream().findFirst();
    }

    public BrainRetryContext requireBrainRetryContext(
            String taskId, String failedTurnId, String blockerId)
    {
        List<BrainRetryContext> rows = jdbc.query("""
                SELECT failure.family,
                       failure.source_operation_row_id AS row_id,
                       failure.id AS failure_receipt_id,
                       COALESCE(failure.ci_repair_episode_id,
                                failure.branch_sync_episode_id) AS episode_id,
                       failure.branch_sync_effect_step_id AS step_id,
                       failure.base_repair_authorization_id,
                       failure.task_id, failure.task_epoch,
                       failure.remote_development_stage_id AS stage_id,
                       failure.stage_generation,
                       failure.semantic_attempt, failure.execution_attempt,
                       failed.id AS failed_turn_id,
                       failed.operation_id AS failed_operation_id,
                       failed.launch_input AS failed_launch_input,
                       failed.delivery_lane, ticket.lane_mask,
                       task.aggregate_version AS task_version,
                       owner.version AS stage_version,
                       code.code_fingerprint, code.head_sha, code.base_sha,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       COALESCE(step.attempt_count, 0) AS branch_attempt_count
                FROM remote_repair_brain_failure_receipt_v309 failure
                JOIN remote_repair_brain_failure_source_v309 source
                  ON source.family = failure.family
                 AND source.source_kind = failure.source_kind
                 AND source.source_operation_row_id =
                     failure.source_operation_row_id
                 AND source.task_turn_id = failure.task_turn_id
                 AND source.operation_id = failure.operation_id
                JOIN task_turn failed ON failed.id = failure.task_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = failed.operation_id
                 AND ticket.owner_kind = 'TASK_TURN'
                 AND ticket.owner_id = failed.id
                 AND ticket.task_id = failure.task_id
                 AND ticket.task_epoch = failure.task_epoch
                 AND ticket.stage_id = failure.remote_development_stage_id
                 AND ticket.stage_generation = failure.stage_generation
                 AND ticket.attempt = failure.execution_attempt
                 AND ticket.expected_code_fingerprint IS
                     failure.expected_code_fingerprint
                 AND ticket.expected_head_sha = failure.expected_head_sha
                 AND ticket.expected_base_sha = failure.expected_base_sha
                JOIN tasks task ON task.id = failure.task_id
                JOIN task_applied_protocol_snapshot_v309 current_task
                  ON current_task.task_id = task.id
                 AND current_task.returned_version = (
                     SELECT MAX(latest.returned_version)
                     FROM task_applied_protocol_snapshot_v309 latest
                     WHERE latest.task_id = task.id
                       AND latest.returned_version <= task.aggregate_version)
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = failure.remote_development_stage_id
                 AND remote.task_id = failure.task_id
                 AND remote.generation = failure.stage_generation
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                JOIN task_blocker blocker ON blocker.id = ?
                JOIN task_brain_protocol_failure_receipt_v300 protocol
                  ON protocol.task_id = task.id
                 AND protocol.proof_id = failure.blocker_id
                 AND protocol.subject_task_epoch = failure.task_epoch
                 AND protocol.subject_stage_id =
                     failure.remote_development_stage_id
                 AND protocol.subject_stage_generation =
                     failure.stage_generation
                 AND protocol.subject_operation_id = failure.operation_id
                 AND protocol.subject_attempt = failure.execution_attempt
                 AND protocol.subject_expected_code_fingerprint =
                     failure.expected_code_fingerprint
                 AND protocol.subject_expected_head_sha =
                     failure.expected_head_sha
                 AND protocol.subject_expected_base_sha =
                     failure.expected_base_sha
                 AND protocol.returned_version = failure.cleared_task_version
                 AND protocol.returned_pending_operation_id IS NULL
                LEFT JOIN ci_repair_episode ci
                  ON failure.family = 'CI'
                 AND ci.id = failure.ci_repair_episode_id
                LEFT JOIN branch_sync_episode branch
                  ON failure.family = 'BRANCH'
                 AND branch.id = failure.branch_sync_episode_id
                LEFT JOIN branch_sync_effect_step step
                  ON failure.family = 'BRANCH'
                 AND step.id = failure.branch_sync_effect_step_id
                 AND step.branch_sync_episode_id = branch.id
                WHERE failure.task_id = ? AND failed.id = ?
                  AND failure.family = 'BRANCH'
                  AND failure.blocker_id = blocker.id
                  AND source.ci_repair_episode_id IS
                      failure.ci_repair_episode_id
                  AND source.branch_sync_episode_id IS
                      failure.branch_sync_episode_id
                  AND source.branch_sync_effect_step_id IS
                      failure.branch_sync_effect_step_id
                  AND source.base_repair_authorization_id IS
                      failure.base_repair_authorization_id
                  AND source.task_id = failure.task_id
                  AND source.task_epoch = failure.task_epoch
                  AND source.remote_development_stage_id =
                      failure.remote_development_stage_id
                  AND source.stage_generation = failure.stage_generation
                  AND source.semantic_attempt = failure.semantic_attempt
                  AND source.execution_attempt = failure.execution_attempt
                  AND source.expected_code_fingerprint =
                      failure.expected_code_fingerprint
                  AND source.expected_head_sha = failure.expected_head_sha
                  AND source.expected_base_sha = failure.expected_base_sha
                  AND source.status = failure.raw_outcome
                  AND source.error_message = failure.error_message
                  AND failed.status = failure.raw_outcome
                  AND ((failure.family = 'CI'
                        AND failed.purpose = 'REMOTE_CI_BRAIN_REVIEW')
                    OR (failure.family = 'BRANCH'
                        AND failed.purpose = 'BRANCH_SYNC_BRAIN_REVIEW'))
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = failure.task_epoch
                  AND task.aggregate_version >= failure.cleared_task_version
                  AND current_task.returned_pending_operation_id IS NULL
                  AND current.stage_id = failure.remote_development_stage_id
                  AND current.stage_generation = failure.stage_generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.generation = failure.stage_generation
                  AND owner.completed_at_ms IS NULL
                  AND code.code_fingerprint IS
                      failure.expected_code_fingerprint
                  AND code.head_sha = failure.expected_head_sha
                  AND code.base_sha = failure.expected_base_sha
                  AND failed.task_id = task.id
                  AND failed.task_epoch = failure.task_epoch
                  AND failed.trigger_stage_id =
                      failure.remote_development_stage_id
                  AND failed.trigger_stage_generation =
                      failure.stage_generation
                  AND failed.operation_id = failure.operation_id
                  AND failed.attempt = failure.execution_attempt
                  AND failed.expected_code_fingerprint IS
                      failure.expected_code_fingerprint
                  AND failed.expected_head_sha = failure.expected_head_sha
                  AND failed.expected_base_sha = failure.expected_base_sha
                  AND failed.error_message = failure.error_message
                  AND blocker.task_id = task.id
                  AND blocker.stage_id IS NULL
                  AND blocker.owner_kind = 'TASK'
                  AND blocker.owner_id = task.id
                  AND blocker.subject_revision = failed.id
                  AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
                  AND blocker.status = 'OPEN'
                  AND protocol.returned_trunk_id = task.thread_id
                  AND protocol.returned_lifecycle = 'ACTIVE'
                  AND protocol.returned_epoch = failure.task_epoch
                  AND protocol.returned_current_stage_id = current.stage_id
                  AND ((failure.family = 'CI'
                        AND ci.task_id = task.id
                        AND ci.task_epoch = failure.task_epoch
                        AND ci.remote_development_stage_id = current.stage_id
                        AND ci.stage_generation = failure.stage_generation
                        AND ci.status = 'AWAITING_PUSH_CI'
                        AND remote.current_head_sha = COALESCE(
                            ci.last_pushed_head_sha, ci.subject_head_sha)
                        AND remote.current_base_sha = ci.subject_base_sha)
                    OR (failure.family = 'BRANCH'
                        AND branch.task_id = task.id
                        AND branch.task_epoch = failure.task_epoch
                        AND branch.remote_development_stage_id = current.stage_id
                        AND branch.stage_generation = failure.stage_generation
                        AND branch.status = 'BRAIN_REVIEW'
                        AND step.kind = 'BRAIN_REVIEW'
                        AND step.status = 'FAILED'
                        AND step.attempt_count = failure.semantic_attempt
                        AND remote.current_head_sha = branch.old_head_sha
                        AND remote.current_base_sha = branch.observed_base_sha))
                  AND NOT EXISTS (
                      SELECT 1 FROM remote_repair_brain_retry_command_v309 retry
                      WHERE retry.failed_turn_id = failed.id
                         OR retry.blocker_id = blocker.id)
                """, (rs, row) -> new BrainRetryContext(
                        rs.getString("family"), rs.getString("row_id"),
                        rs.getString("failure_receipt_id"),
                        rs.getString("episode_id"), rs.getString("step_id"),
                        rs.getString("base_repair_authorization_id"),
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getLong("task_version"), rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getLong("stage_version"),
                        rs.getInt("semantic_attempt"),
                        rs.getInt("execution_attempt"),
                        rs.getString("failed_turn_id"),
                        rs.getString("failed_operation_id"),
                        rs.getString("failed_launch_input"),
                        rs.getString("delivery_lane"), rs.getInt("lane_mask"),
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha"),
                        rs.getString("trunk_id"), rs.getString("workspace_id"),
                        rs.getInt("branch_attempt_count")),
                blockerId, taskId, failedTurnId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Remote repair Brain recovery target is stale or ambiguous");
        }
        return rows.getFirst();
    }

    public BrainRequest insertBrainReplacement(
            BrainRetryContext context,
            String rowId,
            String turnId,
            String operationId,
            String ticketId,
            String launchInput,
            Instant at)
    {
        requireTransaction();
        if (!"BRANCH".equals(context.family())) {
            throw new IllegalStateException(
                    "CI repair Brain recovery is retired");
        }
        int attempt = context.executionAttempt() + 1;
        RepairContext repair = requireContext(context.taskId(), context.stageId());
        insertTaskTurn(turnId, operationId, "BRANCH_SYNC_BRAIN_REVIEW",
                attempt, repair, launchInput, context.deliveryLane(), at);
        jdbc.update("""
                INSERT INTO remote_repair_brain_replacement_operation_v309(
                    id, family, predecessor_failure_receipt_id,
                    predecessor_turn_id, predecessor_operation_id,
                    ci_repair_episode_id, branch_sync_episode_id,
                    branch_sync_effect_step_id, base_repair_authorization_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, task_turn_id, operation_id,
                    dispatch_ticket_id, semantic_attempt, execution_attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, 'REQUESTED', ?)
                """, rowId, context.family(), context.failureReceiptId(),
                context.failedTurnId(), context.failedOperationId(),
                null, context.episodeId(),
                context.stepId(), context.baseRepairAuthorizationId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), turnId, operationId, ticketId,
                context.semanticAttempt(), attempt,
                context.codeFingerprint(), context.headSha(),
                context.baseSha(), at.toEpochMilli());
        insertTicket(ticketId, operationId, "EXECUTE_TASK_TURN", "AGENT_TURN",
                "TASK_TURN", turnId, "BRANCH_SYNC_BRAIN_RESULT",
                context.laneMask(), true, false, repair, attempt, at);
        updateOne("""
                UPDATE remote_repair_brain_replacement_operation_v309
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Remote repair Brain replacement did not dispatch", rowId);
        updateOne("""
                UPDATE branch_sync_effect_step
                SET status = 'CLAIMED', claim_mode = 'EXECUTE', claim_owner = ?,
                    claimed_at_ms = ?, lease_until_ms = ?,
                    evidence = NULL, last_error = NULL,
                    completed_at_ms = NULL
                WHERE id = ? AND status = 'FAILED'
                  AND attempt_count = ?
                """, "Branch Brain step changed before retry", operationId,
                at.toEpochMilli(), at.plusSeconds(60).toEpochMilli(),
                context.stepId(), context.branchAttemptCount());
        return brainRequest(context.family(), rowId, context.episodeId(),
                context.stepId(), turnId, operationId, ticketId, attempt, repair);
    }

    public BrainRetryReceipt recordBrainRetry(
            BrainRetryContext context,
            BrainRequest replacement,
            String blockerId,
            String commandId,
            String taskRequestCommandId,
            String actor,
            String reason,
            Instant at)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO remote_repair_brain_retry_command_v309(
                    id, family, task_id, stage_id, episode_id, failed_turn_id,
                    blocker_id, failure_receipt_id, command_id,
                    task_request_command_id, actor,
                    reason, replacement_operation_row_id,
                    replacement_turn_id, replacement_operation_id,
                    replacement_ticket_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id("remote-repair-brain-retry-command",
                        context.taskId() + ":" + commandId),
                context.family(), context.taskId(), context.stageId(),
                context.episodeId(), context.failedTurnId(), blockerId,
                context.failureReceiptId(), commandId, taskRequestCommandId,
                actor, reason,
                replacement.rowId(), replacement.turnId(),
                replacement.operationId(), replacement.ticketId(),
                at.toEpochMilli());
        updateOne("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = ? AND task_id = ? AND stage_id IS NULL
                  AND owner_kind = 'TASK' AND owner_id = ?
                  AND blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
                  AND status = 'OPEN'
                """, "Remote repair Brain blocker changed before retry",
                at.toEpochMilli(), "retry command " + commandId
                        + " admitted TaskTurn " + replacement.turnId()
                        + ": " + reason,
                blockerId, context.taskId(), context.taskId());
        return findBrainRetryReceipt(context.taskId(), commandId)
                .orElseThrow(() -> new IllegalStateException(
                        "Remote repair Brain retry receipt is missing"));
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
            String authorizationId,
            String leaseExpectedSha,
            Instant at)
    {
        int attempt = episode.fixAttemptCount();
        String prepublishBranchEpisodeId = "PUSH_HEAD".equals(kind)
                ? findPrepublishBranchEpisode(episode.id(), attempt)
                : null;
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
                    expected_head_sha, expected_base_sha,
                    base_repair_authorization_id,
                    prepublish_branch_sync_episode_id, lease_expected_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), kind,
                operationId, attempt, context.codeFingerprint(),
                context.headSha(), context.baseSha(), authorizationId,
                prepublishBranchEpisodeId, leaseExpectedSha,
                at.toEpochMilli());
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
        else if ("PUSH_HEAD".equals(kind)) {
            updateOne("""
                    UPDATE ci_repair_episode SET status = 'AWAITING_PUSH_CI'
                    WHERE id = ?
                      AND status IN ('VALIDATING', 'AWAITING_PUSH_CI')
                    """, "CI Episode changed before push", episode.id());
        }
        return new EffectRequest(
                rowId, episode.id(), operationId, ticketId, kind, attempt);
    }

    private String findPrepublishBranchEpisode(
            String ciRepairEpisodeId, int semanticAttempt)
    {
        List<String> rows = jdbc.query("""
                SELECT DISTINCT prepublish_branch_sync_episode_id
                FROM ci_repair_turn_freshness_v319
                WHERE ci_repair_episode_id = ?
                  AND semantic_attempt = ?
                  AND prepublish_branch_sync_episode_id IS NOT NULL
                """,
                (rs, row) -> rs.getString(
                        "prepublish_branch_sync_episode_id"),
                ciRepairEpisodeId, semanticAttempt);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "CI push has more than one prepublish BranchSync Episode");
        }
        return rows.isEmpty() ? null : rows.getFirst();
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
        String table = context.replacement()
                ? "remote_repair_brain_replacement_operation_v309"
                : context.continuation()
                    ? "ci_repair_fix_continuation_operation_v318"
                : "CI".equals(context.family())
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
        String table = context.replacement()
                ? "remote_repair_brain_replacement_delivery_v309"
                : context.continuation()
                    ? "ci_repair_fix_continuation_delivery_v318"
                : "CI".equals(context.family())
                    ? "ci_repair_delivery_receipt"
                    : "branch_sync_delivery_receipt";
        String key = context.replacement()
                ? "replacement_operation_id"
                : context.continuation()
                    ? "continuation_operation_id"
                : "CI".equals(context.family())
                    ? "ci_repair_operation_id"
                    : "branch_sync_dispatch_operation_id";
        jdbc.update("INSERT INTO " + table + "(" + key
                        + ", operation_id, raw_outcome, raw_result_digest, "
                        + "acceptance, recorded_at_ms) VALUES (?, ?, ?, ?, ?, ?)",
                context.rowId(), context.operationId(), rawOutcome, rawDigest,
                acceptance, at.toEpochMilli());
    }

    private void insertCiFixTreeResult(
            TurnDelivery context,
            String disposition,
            String sourceTreeSha,
            String resultTreeSha,
            String rawDigest,
            Instant at)
    {
        jdbc.update("""
                INSERT INTO ci_repair_fix_tree_result_v318(
                    id, ci_repair_episode_id, source_kind,
                    source_operation_row_id, operation_id, stage_turn_id,
                    semantic_attempt, execution_attempt, disposition,
                    source_tree_sha, result_tree_sha, raw_result_digest,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id("ci-repair-fix-tree-result", context.operationId()),
                context.episodeId(),
                context.continuation() ? "CONTINUATION" : "ORIGINAL",
                context.rowId(), context.operationId(), context.turnId(),
                context.semanticAttempt(), context.executionAttempt(),
                disposition, sourceTreeSha, resultTreeSha, rawDigest,
                at.toEpochMilli());
    }

    private static void requireCiFix(TurnDelivery context)
    {
        if (!"CI".equals(context.family())
                || !"FIX_STAGE_TURN".equals(context.kind())
                || context.replacement()) {
            throw new IllegalArgumentException("Turn is not a CI fix StageTurn");
        }
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
                rs.getString("automation_policy_id"),
                rs.getBoolean("auto_approve"),
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
                rs.getString("kind"),
                rs.getString("base_repair_authorization_id"),
                rs.getString("operation_id"),
                rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"), rs.getInt("execution_attempt"),
                rs.getString("turn_id"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("worktree_path"), rs.getLong("task_version"),
                rs.getLong("stage_version"), rs.getBoolean("is_current"),
                rs.getBoolean("is_replacement"),
                rs.getBoolean("is_continuation"));
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

    private static BrainRetryReceipt brainRetryReceipt(ResultSet rs)
            throws SQLException
    {
        return new BrainRetryReceipt(
                rs.getString("family"), rs.getString("task_id"),
                rs.getString("stage_id"), rs.getString("episode_id"),
                rs.getString("failed_turn_id"), rs.getString("blocker_id"),
                rs.getString("command_id"), rs.getString("actor"),
                rs.getString("reason"), rs.getString("replacement_turn_id"),
                rs.getString("replacement_operation_id"),
                rs.getString("replacement_ticket_id"),
                Instant.ofEpochMilli(rs.getLong("recorded_at_ms")));
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Remote repair Turn mutation requires a Task transaction");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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
            String automationPolicyId,
            boolean autoApprove,
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

    public record CiFixContinuationDue(
            String id,
            String episodeId,
            String predecessorOperationId,
            String predecessorStageTurnId,
            String predecessorAcceptedSnapshotId,
            int predecessorAcceptedObservationRevision,
            int semanticAttempt,
            int executionAttempt,
            String baseRepairAuthorizationId,
            Instant recordedAt) {}

    public record CiNextFixDue(
            String id,
            String episodeId,
            String sourceKind,
            int sourceSemanticAttempt,
            int requestedSemanticAttempt,
            String predecessorAcceptedSnapshotId,
            int predecessorAcceptedObservationRevision,
            String prompt,
            Instant recordedAt) {}

    private record AcceptedRemoteSubject(
            String snapshotId, int observationRevision) {}

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

    public record BrainRetryContext(
            String family,
            String rowId,
            String failureReceiptId,
            String episodeId,
            String stepId,
            String baseRepairAuthorizationId,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String stageId,
            long stageGeneration,
            long stageVersion,
            int semanticAttempt,
            int executionAttempt,
            String failedTurnId,
            String failedOperationId,
            String failedLaunchInput,
            String deliveryLane,
            int laneMask,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String trunkId,
            String workspaceId,
            int branchAttemptCount) {}

    public record BrainRetryReceipt(
            String family,
            String taskId,
            String stageId,
            String episodeId,
            String failedTurnId,
            String blockerId,
            String commandId,
            String actor,
            String reason,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId,
            Instant recordedAt) {}

    public record TurnDelivery(
            String family,
            String rowId,
            String episodeId,
            String stepId,
            String kind,
            String baseRepairAuthorizationId,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            int executionAttempt,
            String turnId,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String worktreePath,
            long taskVersion,
            long stageVersion,
            boolean current,
            boolean replacement,
            boolean continuation)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    executionAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record CodeSubject(
            String codeFingerprint, String headSha, String baseSha) {}

    private record SteeringOwner(
            String family, String ownerFamily, String ciEpisodeId,
            String branchEpisodeId, String branchStepId) {}
}
