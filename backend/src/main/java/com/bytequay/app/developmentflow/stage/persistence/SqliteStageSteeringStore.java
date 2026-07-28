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
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** SQLite protocol store for persisted-before-admission Stage steering. */
@Component
public final class SqliteStageSteeringStore
{
    private final JdbcTemplate jdbc;

    public SqliteStageSteeringStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Optional<Request> findByCommand(String commandId)
    {
        return query("WHERE command_id = ?", commandId).stream().findFirst();
    }

    public Optional<Request> find(String requestId)
    {
        return query("WHERE id = ?", requestId).stream().findFirst();
    }

    public List<Request> findPending(int limit)
    {
        return query("WHERE status = 'PENDING' ORDER BY requested_at_ms, id LIMIT ?", limit);
    }

    public Optional<Predecessor> findPredecessor(ResultFence fence)
    {
        requireNonNull(fence, "fence is null");
        return jdbc.query("""
                SELECT ticket.owner_kind, ticket.owner_id, ticket.id AS ticket_id,
                       ticket.operation_id, ticket.attempt,
                       COALESCE(
                           (SELECT turn.purpose FROM stage_turn turn
                            WHERE turn.id = ticket.owner_id
                              AND turn.operation_id = ticket.operation_id),
                           (SELECT turn.purpose FROM task_turn turn
                            WHERE turn.id = ticket.owner_id
                              AND turn.operation_id = ticket.operation_id),
                           ticket.operation_kind) AS owner_purpose,
                       ticket.expected_code_fingerprint,
                       ticket.expected_head_sha, ticket.expected_base_sha
                FROM dispatch_ticket ticket
                WHERE ticket.operation_id = ? AND ticket.task_epoch = ?
                  AND ticket.stage_id = ? AND ticket.stage_generation = ?
                  AND ticket.attempt = ?
                  AND ticket.expected_code_fingerprint
                        IS ?
                  AND ticket.expected_head_sha IS ?
                  AND ticket.expected_base_sha IS ?
                  AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                """, (rs, row) -> predecessor(rs),
                fence.operationId(), fence.taskEpoch(), fence.stageId(),
                fence.stageGeneration(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha()).stream().findFirst();
    }

    /** Resolves only a terminal StageTurn backed by this exact ready wait. */
    public Optional<Predecessor> findUserWaitPredecessor(
            String turnId,
            String operationId,
            String waitKind,
            String waitId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        return jdbc.query("""
                SELECT ticket.owner_kind, ticket.owner_id,
                       ticket.id AS ticket_id, ticket.operation_id,
                       ticket.attempt, turn.purpose AS owner_purpose,
                       ticket.expected_code_fingerprint,
                       ticket.expected_head_sha, ticket.expected_base_sha
                FROM dispatch_ticket ticket
                JOIN stage_turn turn ON turn.id = ticket.owner_id
                  AND turn.operation_id = ticket.operation_id
                JOIN typed_user_wait_result result
                  ON result.operation_id = ticket.operation_id
                WHERE ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = ? AND ticket.operation_id = ?
                  AND ticket.status = 'SUCCEEDED'
                  AND turn.status = 'SUCCEEDED'
                  AND result.owner_kind = 'STAGE_TURN'
                  AND result.turn_id = turn.id
                  AND result.wait_kind = ? AND result.wait_id = ?
                  AND ((? = 'QUESTION' AND EXISTS (
                        SELECT 1 FROM stage_question question
                        WHERE question.id = ? AND question.turn_id = turn.id
                          AND question.state = 'ANSWERED'
                          AND question.continuation_state = 'READY'))
                    OR (? = 'PERMISSION' AND EXISTS (
                        SELECT 1 FROM permission_request permission
                        WHERE permission.id = ?
                          AND permission.turn_kind = 'STAGE'
                          AND permission.turn_id = turn.id
                          AND permission.operation_id = turn.operation_id
                          AND permission.state <> 'OPEN'
                          AND permission.continuation_state = 'READY')))
                """, (rs, row) -> predecessor(rs), turnId, operationId,
                waitKind, waitId, waitKind, waitId, waitKind, waitId)
                .stream().findFirst();
    }

    public void insertUserWaitLink(
            String requestId,
            String waitKind,
            String waitId,
            Predecessor predecessor)
    {
        requireTransaction();
        requireNonNull(predecessor, "predecessor is null");
        jdbc.update("""
                INSERT INTO stage_turn_user_wait_continuation_v265(
                    request_id, wait_kind, wait_id, predecessor_turn_id,
                    predecessor_operation_id)
                VALUES (?, ?, ?, ?, ?)
                """, requestId, waitKind, waitId, predecessor.ownerId(),
                predecessor.operationId());
    }

    public Optional<String> userWaitSuccessor(String waitKind, String waitId)
    {
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        return jdbc.query("""
                SELECT successor_turn_id
                FROM stage_turn_user_wait_continuation_v265
                WHERE wait_kind = ? AND wait_id = ?
                  AND successor_turn_id IS NOT NULL
                """, (rs, row) -> rs.getString("successor_turn_id"),
                waitKind, waitId).stream().findFirst();
    }

    public boolean isUserWaitContinuation(String requestId)
    {
        requireText(requestId, "requestId");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_turn_user_wait_continuation_v265
                WHERE request_id = ?
                """, Integer.class, requestId);
        return count != null && count == 1;
    }

    public boolean userWaitSubjectIsCurrent(Request request)
    {
        requireNonNull(request, "request is null");
        Predecessor predecessor = requireNonNull(
                request.predecessor(), "predecessor is null");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE' AND task.epoch = ?
                  AND owner.id = ? AND owner.generation = ?
                  AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
                  AND code.code_fingerprint IS ?
                  AND code.head_sha IS ? AND code.base_sha IS ?
                """, Integer.class, request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageGeneration(),
                predecessor.codeFingerprint(), predecessor.headSha(),
                predecessor.baseSha());
        return count != null && count == 1;
    }

    /** Resolves one exact live Remote writer owner; ambiguity fails closed. */
    public Optional<Predecessor> findActiveRemotePredecessor(
            String taskId, String stageId, long taskEpoch, long stageGeneration)
    {
        List<Predecessor> rows = jdbc.query("""
                SELECT ticket.owner_kind, ticket.owner_id, ticket.id AS ticket_id,
                       ticket.operation_id, ticket.attempt,
                       turn.purpose AS owner_purpose,
                       ticket.expected_code_fingerprint,
                       ticket.expected_head_sha, ticket.expected_base_sha
                FROM dispatch_ticket ticket
                JOIN stage_turn turn ON turn.id = ticket.owner_id
                  AND turn.operation_id = ticket.operation_id
                JOIN tasks task ON task.id = ticket.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                WHERE ticket.task_id = ? AND ticket.task_epoch = ?
                  AND ticket.stage_id = ? AND ticket.stage_generation = ?
                  AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                  AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND turn.purpose IN ('REMOTE_CI_REPAIR',
                      'BRANCH_CONFLICT_REPAIR', 'ADDRESS_REMOTE_FEEDBACK')
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE' AND task.epoch = ?
                  AND current.stage_id = ?
                  AND current.stage_generation = ?
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.generation = ? AND owner.completed_at_ms IS NULL
                  AND ((turn.purpose = 'REMOTE_CI_REPAIR' AND EXISTS (
                        SELECT 1 FROM ci_repair_operation operation
                        WHERE operation.stage_turn_id = turn.id
                          AND operation.operation_id = turn.operation_id
                          AND operation.status = 'DISPATCHED'))
                    OR (turn.purpose = 'BRANCH_CONFLICT_REPAIR' AND EXISTS (
                        SELECT 1 FROM branch_sync_dispatch_operation operation
                        WHERE operation.stage_turn_id = turn.id
                          AND operation.operation_id = turn.operation_id
                          AND operation.status = 'DISPATCHED'))
                    OR (turn.purpose = 'ADDRESS_REMOTE_FEEDBACK' AND EXISTS (
                        SELECT 1 FROM remote_feedback_stage_turn_request request
                        JOIN remote_feedback_batch batch
                          ON batch.id = request.remote_feedback_batch_id
                        WHERE request.stage_turn_id = turn.id
                          AND batch.status = 'ADDRESSING')))
                """, (rs, row) -> predecessor(rs), taskId, taskEpoch,
                stageId, stageGeneration, taskEpoch, stageId,
                stageGeneration, stageGeneration);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Remote Stage has more than one live steering owner");
        }
        return rows.stream().findFirst();
    }

    public Optional<Request> findPendingByPredecessor(String operationId)
    {
        return query("WHERE predecessor_operation_id = ? AND status = 'PENDING'",
                operationId).stream().findFirst();
    }

    public RemoteHandoff requireRemoteHandoff(String requestId)
    {
        List<RemoteHandoff> rows = jdbc.query("""
                SELECT request_id, owner_family, owner_purpose,
                       predecessor_turn_id, predecessor_operation_id, status,
                       successor_turn_id
                FROM remote_stage_steering_handoff_v257
                WHERE request_id = ?
                """, (rs, row) -> new RemoteHandoff(
                        rs.getString("request_id"), rs.getString("owner_family"),
                        rs.getString("owner_purpose"),
                        rs.getString("predecessor_turn_id"),
                        rs.getString("predecessor_operation_id"),
                        rs.getString("status"), rs.getString("successor_turn_id")),
                requestId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Remote steering handoff is missing");
        }
        return rows.getFirst();
    }

    public void insert(Request request, List<Attachment> attachments)
    {
        requireTransaction();
        requireNonNull(request, "request is null");
        requireNonNull(attachments, "attachments is null");
        Predecessor previous = request.predecessor();
        jdbc.update("""
                INSERT INTO stage_steering_request_v257(
                    id, command_id, task_id, task_epoch, stage_id, stage_kind,
                    stage_generation, accepted_stage_version,
                    accepted_checkpoint, mode, body, content_digest,
                    predecessor_owner_kind, predecessor_owner_id,
                    predecessor_purpose, predecessor_operation_id,
                    predecessor_ticket_id,
                    predecessor_attempt, predecessor_code_fingerprint,
                    predecessor_head_sha, predecessor_base_sha,
                    cancel_intent_at_ms, status, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, 'PENDING', ?, ?)
                """, request.id(), request.commandId(), request.taskId(),
                request.taskEpoch(), request.stageId(), request.stageKind().name(),
                request.stageGeneration(), request.acceptedStageVersion(),
                request.acceptedCheckpoint().name(), request.mode().name(),
                request.body(), request.contentDigest(),
                previous == null ? null : previous.ownerKind(),
                previous == null ? null : previous.ownerId(),
                previous == null ? null : previous.purpose(),
                previous == null ? null : previous.operationId(),
                previous == null ? null : previous.ticketId(),
                previous == null ? null : previous.attempt(),
                previous == null ? null : previous.codeFingerprint(),
                previous == null ? null : previous.headSha(),
                previous == null ? null : previous.baseSha(),
                request.mode() == V2StageSteeringControl.Mode.CANCEL_AND_REPLACE
                        ? request.requestedAt().toEpochMilli() : null,
                request.requestedBy(), request.requestedAt().toEpochMilli());
        for (Attachment attachment : attachments) {
            jdbc.update("""
                    INSERT INTO stage_steering_attachment_v257(
                        request_id, position, media_type, content_ref, content_digest)
                    VALUES (?, ?, ?, ?, ?)
                    """, request.id(), attachment.position(), attachment.mediaType(),
                    attachment.contentRef(), attachment.contentDigest());
        }
        if (request.stageKind() == StageKind.REMOTE_DEVELOPMENT) {
            String purpose = requireNonNull(request.predecessor(),
                    "Remote steering predecessor is null").purpose();
            String family = switch (purpose) {
                case "REMOTE_CI_REPAIR" -> "CI_REPAIR";
                case "BRANCH_CONFLICT_REPAIR" -> "BRANCH_REPAIR";
                case "ADDRESS_REMOTE_FEEDBACK" -> "REMOTE_FEEDBACK";
                default -> throw new IllegalArgumentException(
                        "Unsupported Remote steering purpose " + purpose);
            };
            jdbc.update("""
                    INSERT INTO remote_stage_steering_handoff_v257(
                        request_id, owner_family, owner_purpose,
                        predecessor_turn_id, predecessor_operation_id, status)
                    VALUES (?, ?, ?, ?, ?, 'PARKED')
                    """, request.id(), family, purpose,
                    request.predecessor().ownerId(),
                    request.predecessor().operationId());
        }
    }

    public boolean predecessorQuiesced(Request request)
    {
        Predecessor predecessor = request.predecessor();
        if (predecessor == null) {
            return true;
        }
        Integer ready = jdbc.queryForObject("""
                SELECT CASE WHEN
                    EXISTS (SELECT 1 FROM dispatch_ticket ticket
                        WHERE ticket.id = ? AND ticket.operation_id = ?
                          AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED'))
                    AND NOT EXISTS (SELECT 1 FROM agent_execution execution
                        WHERE execution.ticket_id = ?
                          AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN'))
                    AND NOT EXISTS (SELECT 1 FROM capacity_lease lease
                        WHERE lease.operation_id = ? AND lease.released_at_ms IS NULL)
                    AND NOT EXISTS (SELECT 1 FROM worktree_leases lease
                        WHERE lease.workflow_version = 'V2'
                          AND lease.operation_id = ?)
                THEN 1 ELSE 0 END
                """, Integer.class, predecessor.ticketId(),
                predecessor.operationId(), predecessor.ticketId(),
                predecessor.operationId(), predecessor.operationId());
        return ready != null && ready == 1;
    }

    public boolean cancellationRequestedFor(String operationId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_steering_request_v257
                WHERE mode = 'CANCEL_AND_REPLACE'
                  AND predecessor_operation_id = ?
                  AND status = 'PENDING'
                  AND cancel_intent_at_ms IS NOT NULL
                """, Integer.class, operationId);
        return count != null && count > 0;
    }

    public List<Attachment> attachments(String requestId)
    {
        return jdbc.query("""
                SELECT position, media_type, content_ref, content_digest
                FROM stage_steering_attachment_v257
                WHERE request_id = ? ORDER BY position
                """, (rs, row) -> new Attachment(
                        rs.getInt("position"), rs.getString("media_type"),
                        rs.getString("content_ref"),
                        rs.getString("content_digest")), requestId);
    }

    public LocalContext requireLocalContext(Request request, long stageVersion)
    {
        List<LocalContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.epoch AS task_epoch,
                       local.stage_id, local.generation AS stage_generation,
                       stage.version AS stage_version, stage.checkpoint,
                       code.code_fingerprint, code.head_sha, code.base_sha,
                       identity.worktree_path, context.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill
                FROM local_development_stage local
                JOIN stage ON stage.id = local.stage_id
                JOIN tasks task ON task.id = local.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                WHERE task.id = ? AND local.stage_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE' AND task.epoch = ?
                  AND local.opened_for_epoch = task.epoch
                  AND local.generation = ?
                  AND current.stage_id = local.stage_id
                  AND current.stage_generation = local.generation
                  AND stage.kind = 'LOCAL_DEVELOPMENT'
                  AND stage.version = ? AND stage.completed_at_ms IS NULL
                  AND stage.checkpoint IN ('IMPLEMENTING',
                      'ADDRESSING_BRAIN_FINDINGS', 'ADDRESSING_LOCAL_FEEDBACK',
                      'LOCAL_REVIEW')
                """, (rs, row) -> localContext(rs), request.taskId(),
                request.stageId(), request.taskEpoch(), request.stageGeneration(),
                stageVersion);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact Local steering admission context is unavailable");
        }
        return rows.getFirst();
    }

    public void insertLocalTurn(LocalTurn turn)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'USER_STEERING', 'QUEUED', ?, 1, ?,
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
                VALUES (?, ?, ?, ?, ?, ?, ?, 'STEERING', 'IMMEDIATE',
                    NULL, NULL, NULL, ?, ?, ?)
                """, turn.localRequestId(), turn.localCommandId(), turn.turnId(),
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

    /** Inserts a same-purpose Local successor linked to its settled wait owner. */
    public void insertLocalContinuationTurn(
            LocalTurn turn, Predecessor predecessor)
    {
        requireTransaction();
        requireNonNull(turn, "turn is null");
        requireNonNull(predecessor, "predecessor is null");
        int stageTurn = jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT ?, ?, ?, previous.purpose, 'QUEUED', ?, ?, ?,
                    ?, ?, ?, ?, ?, ?
                FROM stage_turn previous
                WHERE previous.id = ? AND previous.operation_id = ?
                  AND previous.status = 'SUCCEEDED'
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.attempt(), turn.taskEpoch(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.deliveryLane(), turn.launchInput(),
                turn.requestedAt().toEpochMilli(), predecessor.ownerId(),
                predecessor.operationId());
        if (stageTurn != 1) {
            throw new IllegalStateException(
                    "Local user-wait predecessor changed before admission");
        }
        int request = jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    prompt_digest, requested_by, requested_at_ms)
                SELECT ?, ?, ?, previous.task_id,
                    previous.local_development_stage_id, previous.task_epoch,
                    previous.stage_generation, previous.kind,
                    'CANCEL_AND_REPLACE', previous.stage_turn_id,
                    previous.brain_review_episode_id,
                    previous.local_feedback_batch_id, ?, ?, ?
                FROM local_stage_turn_request previous
                WHERE previous.stage_turn_id = ?
                """, turn.localRequestId(), turn.localCommandId(), turn.turnId(),
                turn.promptDigest(), turn.requestedBy(),
                turn.requestedAt().toEpochMilli(), predecessor.ownerId());
        if (request != 1) {
            throw new IllegalStateException(
                    "Local user-wait request owner is missing");
        }
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
                turn.laneMask(), turn.workspaceId(), turn.trunkId(), turn.taskId(),
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.attempt(), turn.codeFingerprint(), turn.headSha(),
                turn.baseSha(), turn.requestedAt().toEpochMilli());
    }

    public PlanSource requirePlanSource(Request request, long stageVersion)
    {
        List<PlanSource> rows = jdbc.query("""
                SELECT revision.id AS revision_id, revision.content,
                       review.id AS self_review_id
                FROM stage
                JOIN tasks task ON task.id = stage.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                JOIN plan_self_review review ON review.plan_revision_id = revision.id
                WHERE task.id = ? AND stage.id = ? AND task.epoch = ?
                  AND stage.generation = ? AND stage.version = ?
                  AND stage.kind = 'PLAN' AND stage.checkpoint = 'AWAITING_APPROVAL'
                  AND stage.completed_at_ms IS NULL
                  AND task.workflow_version = 'V2' AND task.lifecycle_state = 'ACTIVE'
                  AND current.stage_id = stage.id
                  AND current.stage_generation = stage.generation
                  AND review.status = 'SUCCEEDED' AND review.verdict = 'APPROVED'
                  AND review.reviewed_digest = revision.content_digest
                  AND NOT EXISTS (SELECT 1 FROM plan_revision newer
                      WHERE newer.plan_stage_id = stage.id
                        AND newer.revision > revision.revision)
                """, (rs, row) -> new PlanSource(
                        rs.getString("revision_id"), rs.getString("self_review_id"),
                        rs.getString("content")), request.taskId(), request.stageId(),
                request.taskEpoch(), request.stageGeneration(), stageVersion);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact reviewed Plan steering source is unavailable");
        }
        return rows.getFirst();
    }

    public void markAdmitted(
            String requestId, String ownerKind, String ownerId,
            String operationId, Instant admittedAt)
    {
        requireTransaction();
        int remote = jdbc.update("""
                UPDATE remote_stage_steering_handoff_v257
                SET status = 'CONSUMED', successor_turn_id = ?, consumed_at_ms = ?
                WHERE request_id = ? AND status = 'PARKED'
                """, ownerId, admittedAt.toEpochMilli(), requestId);
        if (remote > 1) {
            throw new IllegalStateException("Remote steering handoff is ambiguous");
        }
        jdbc.update("""
                UPDATE stage_turn_user_wait_continuation_v265
                SET successor_turn_id = ?, successor_operation_id = ?,
                    admitted_at_ms = ?
                WHERE request_id = ? AND successor_turn_id IS NULL
                """, ownerId, operationId, admittedAt.toEpochMilli(), requestId);
        int changed = jdbc.update("""
                UPDATE stage_steering_request_v257
                SET status = 'ADMITTED', successor_owner_kind = ?,
                    successor_owner_id = ?, successor_operation_id = ?,
                    admitted_at_ms = ?
                WHERE id = ? AND status = 'PENDING'
                """, ownerKind, ownerId, operationId,
                admittedAt.toEpochMilli(), requestId);
        if (changed != 1) {
            throw new IllegalStateException("Stage steering changed before admission");
        }
    }

    public void markSuperseded(String requestId, String reason)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE stage_steering_request_v257
                SET status = 'SUPERSEDED', terminal_reason = ?
                WHERE id = ? AND status = 'PENDING'
                """, reason, requestId);
        if (changed != 1) {
            throw new IllegalStateException("Stage steering changed before supersession");
        }
    }

    private List<Request> query(String suffix, Object... arguments)
    {
        return jdbc.query("""
                SELECT id, command_id, task_id, task_epoch, stage_id, stage_kind,
                       stage_generation, accepted_stage_version,
                       accepted_checkpoint, mode, body, content_digest,
                       predecessor_owner_kind, predecessor_owner_id,
                       predecessor_purpose, predecessor_operation_id,
                       predecessor_ticket_id,
                       predecessor_attempt, predecessor_code_fingerprint,
                       predecessor_head_sha, predecessor_base_sha, status,
                       successor_owner_kind, successor_owner_id,
                       successor_operation_id, requested_by, requested_at_ms
                FROM stage_steering_request_v257
                """ + suffix, (rs, row) -> request(rs), arguments);
    }

    private static Request request(ResultSet rs)
            throws SQLException
    {
        String predecessorOperation = rs.getString("predecessor_operation_id");
        Predecessor predecessor = predecessorOperation == null ? null : new Predecessor(
                rs.getString("predecessor_owner_kind"),
                rs.getString("predecessor_owner_id"),
                rs.getString("predecessor_purpose"),
                rs.getString("predecessor_ticket_id"), predecessorOperation,
                rs.getInt("predecessor_attempt"),
                rs.getString("predecessor_code_fingerprint"),
                rs.getString("predecessor_head_sha"),
                rs.getString("predecessor_base_sha"));
        return new Request(
                rs.getString("id"), rs.getString("command_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"),
                StageKind.valueOf(rs.getString("stage_kind")),
                rs.getLong("stage_generation"),
                rs.getLong("accepted_stage_version"),
                StageCheckpoint.valueOf(rs.getString("accepted_checkpoint")),
                V2StageSteeringControl.Mode.valueOf(rs.getString("mode")),
                rs.getString("body"), rs.getString("content_digest"),
                predecessor, rs.getString("status"),
                rs.getString("successor_owner_kind"),
                rs.getString("successor_owner_id"),
                rs.getString("successor_operation_id"),
                rs.getString("requested_by"),
                Instant.ofEpochMilli(rs.getLong("requested_at_ms")));
    }

    private static Predecessor predecessor(ResultSet rs)
            throws SQLException
    {
        return new Predecessor(
                rs.getString("owner_kind"), rs.getString("owner_id"),
                rs.getString("owner_purpose"), rs.getString("ticket_id"),
                rs.getString("operation_id"),
                rs.getInt("attempt"), rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"));
    }

    private static LocalContext localContext(ResultSet rs)
            throws SQLException
    {
        return new LocalContext(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getLong("stage_version"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("worktree_path"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"));
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Stage steering writes require a command transaction");
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record Request(
            String id, String commandId, String taskId, long taskEpoch,
            String stageId, StageKind stageKind, long stageGeneration,
            long acceptedStageVersion, StageCheckpoint acceptedCheckpoint,
            V2StageSteeringControl.Mode mode, String body, String contentDigest,
            Predecessor predecessor, String status, String successorOwnerKind,
            String successorOwnerId, String successorOperationId,
            String requestedBy, Instant requestedAt) {}

    public record Predecessor(
            String ownerKind, String ownerId, String purpose, String ticketId,
            String operationId, int attempt, String codeFingerprint,
            String headSha, String baseSha) {}

    public record Attachment(
            int position, String mediaType, String contentRef,
            String contentDigest) {}

    public record LocalContext(
            String taskId, String trunkId, String workspaceId, long taskEpoch,
            String stageId, long stageGeneration, long stageVersion,
            StageCheckpoint checkpoint, String codeFingerprint, String headSha,
            String baseSha, String worktreePath, String workModelSnapshot,
            String provider, String model, String roleSkill) {}

    public record LocalTurn(
            String localRequestId, String localCommandId, String turnId,
            String operationId, String ticketId, String workspaceId,
            String trunkId, String taskId, long taskEpoch, String stageId,
            long stageGeneration, int attempt, String codeFingerprint, String headSha,
            String baseSha, String deliveryLane, int laneMask,
            String launchInput, String promptDigest, String requestedBy,
            Instant requestedAt)
    {
        public LocalTurn(
                String localRequestId,
                String localCommandId,
                String turnId,
                String operationId,
                String ticketId,
                String workspaceId,
                String trunkId,
                String taskId,
                long taskEpoch,
                String stageId,
                long stageGeneration,
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
            this(
                    localRequestId, localCommandId, turnId, operationId,
                    ticketId, workspaceId, trunkId, taskId, taskEpoch, stageId,
                    stageGeneration, 1, codeFingerprint, headSha, baseSha,
                    deliveryLane, laneMask, launchInput, promptDigest,
                    requestedBy, requestedAt);
        }

        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, attempt,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record PlanSource(
            String revisionId, String selfReviewId, String content) {}

    public record RemoteHandoff(
            String requestId, String ownerFamily, String ownerPurpose,
            String predecessorTurnId, String predecessorOperationId,
            String status, String successorTurnId) {}
}
