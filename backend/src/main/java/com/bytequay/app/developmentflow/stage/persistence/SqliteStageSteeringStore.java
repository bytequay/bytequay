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

    /**
     * The provider is already quiescent, but delivery could not decode the
     * immutable successful result.  This is the one case where replacement
     * may fence a non-terminal DispatchTicket instead of waiting for it to
     * become terminal first.
     */
    public boolean malformedLocalResultPendingReady(
            Request request, ResultFence pending)
    {
        requireNonNull(request, "request is null");
        requireNonNull(pending, "pending is null");
        Predecessor predecessor = request.predecessor();
        if (request.stageKind() != StageKind.LOCAL_DEVELOPMENT
                || request.mode()
                        != V2StageSteeringControl.Mode.CANCEL_AND_REPLACE
                || predecessor == null
                || !"STAGE_TURN".equals(predecessor.ownerKind())) {
            return false;
        }
        Integer ready = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM stage_steering_request_v257 steering
                JOIN stage owner ON owner.id = steering.stage_id
                JOIN tasks task ON task.id = steering.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage_turn turn
                  ON turn.id = steering.predecessor_owner_id
                 AND turn.operation_id = steering.predecessor_operation_id
                JOIN local_stage_turn_request local_request
                  ON local_request.stage_turn_id = turn.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = steering.predecessor_ticket_id
                 AND ticket.operation_id = turn.operation_id
                WHERE steering.id = ? AND steering.status = 'PENDING'
                  AND steering.stage_kind = 'LOCAL_DEVELOPMENT'
                  AND steering.mode = 'CANCEL_AND_REPLACE'
                  AND steering.predecessor_owner_kind = 'STAGE_TURN'
                  AND owner.version = steering.accepted_stage_version
                  AND owner.checkpoint = steering.accepted_checkpoint
                  AND owner.completed_at_ms IS NULL AND owner.end_reason IS NULL
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = steering.task_epoch
                  AND current.stage_id = owner.id
                  AND current.stage_generation = owner.generation
                  AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND turn.task_epoch = ? AND turn.stage_id = ?
                  AND turn.stage_generation = ? AND turn.attempt = ?
                  AND turn.expected_code_fingerprint IS ?
                  AND turn.expected_head_sha IS ?
                  AND turn.expected_base_sha IS ?
                  AND local_request.task_id = task.id
                  AND local_request.local_development_stage_id = owner.id
                  AND local_request.task_epoch = task.epoch
                  AND local_request.stage_generation = owner.generation
                  AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'STAGE_TURN_RESULT'
                  AND ticket.status = 'RESULT_PENDING'
                  AND ticket.pending_result_outcome = 'SUCCEEDED'
                  AND ticket.delivery_acceptance IS NULL
                  AND ticket.last_error LIKE ?
                  AND ticket.pending_result_task_epoch = ?
                  AND ticket.pending_result_stage_id = ?
                  AND ticket.pending_result_stage_generation = ?
                  AND ticket.pending_result_operation_id = ?
                  AND ticket.pending_result_attempt = ?
                  AND ticket.pending_result_expected_code_fingerprint IS ?
                  AND ticket.pending_result_expected_head_sha IS ?
                  AND ticket.pending_result_expected_base_sha IS ?
                  AND NOT EXISTS (SELECT 1
                      FROM local_stage_turn_delivery_receipt receipt
                      WHERE receipt.stage_turn_id = turn.id)
                  AND NOT EXISTS (SELECT 1 FROM dispatch_delivery_claim claim
                      WHERE claim.ticket_id = ticket.id)
                  AND NOT EXISTS (SELECT 1 FROM agent_execution execution
                      WHERE execution.ticket_id = ticket.id
                        AND execution.status IN ('STARTING', 'RUNNING', 'UNKNOWN'))
                  AND EXISTS (SELECT 1 FROM agent_execution execution
                      WHERE execution.ticket_id = ticket.id
                        AND execution.infrastructure_attempt =
                            ticket.infrastructure_attempts
                        AND execution.status = 'SUCCEEDED'
                        AND execution.finished_at_ms IS NOT NULL
                        AND execution.raw_result IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM capacity_lease lease
                      WHERE lease.operation_id = turn.operation_id
                        AND lease.released_at_ms IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM worktree_leases lease
                      WHERE lease.workflow_version = 'V2'
                        AND lease.operation_id = turn.operation_id)
                """, Integer.class, request.id(),
                pending.taskEpoch(), pending.stageId(), pending.stageGeneration(),
                pending.attempt(), pending.expectedCodeFingerprint(),
                pending.expectedHeadSha(), pending.expectedBaseSha(),
                DispatchTicket.RESULT_PROTOCOL_FAILURE_PREFIX + "%",
                pending.taskEpoch(), pending.stageId(), pending.stageGeneration(),
                pending.operationId(), pending.attempt(),
                pending.expectedCodeFingerprint(), pending.expectedHeadSha(),
                pending.expectedBaseSha());
        return ready != null && ready == 1;
    }

    public boolean cancellationRequestedFor(String operationId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_steering_request_v257
                WHERE mode = 'CANCEL_AND_REPLACE'
                  AND predecessor_operation_id = ?
                  AND status IN ('PENDING', 'ADMITTED')
                  AND cancel_intent_at_ms IS NOT NULL
                  AND NOT EXISTS (SELECT 1
                      FROM stage_turn_user_wait_continuation_v265 continuation
                      WHERE continuation.request_id = stage_steering_request_v257.id)
                """, Integer.class, operationId);
        return count != null && count > 0;
    }

    public Optional<CliContinuation> cliContinuation(
            Request request,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String provider,
            String model,
            String workingDirectory)
    {
        requireNonNull(request, "request is null");
        Predecessor predecessor = requireNonNull(
                request.predecessor(), "predecessor is null");
        requireText(stageId, "stageId");
        requireText(codeFingerprint, "codeFingerprint");
        requireText(headSha, "headSha");
        requireText(baseSha, "baseSha");
        requireText(provider, "provider");
        requireText(model, "model");
        requireText(workingDirectory, "workingDirectory");
        if (!"STAGE_TURN".equals(predecessor.ownerKind())) {
            return Optional.empty();
        }
        return jdbc.query("""
                WITH lineage_candidate(
                    turn_id, operation_id, ticket_id, successor_rank,
                    steering_order) AS (
                    SELECT ?, ?, ?, 0, 0
                    UNION ALL
                    SELECT steering.successor_owner_id,
                           steering.successor_operation_id, ticket.id, 1,
                           steering.rowid
                    FROM stage_steering_request_v257 steering
                    JOIN dispatch_ticket ticket
                      ON ticket.owner_kind = 'STAGE_TURN'
                     AND ticket.owner_id = steering.successor_owner_id
                     AND ticket.operation_id = steering.successor_operation_id
                    WHERE ? = 'APPEND'
                      AND steering.task_id = ? AND steering.task_epoch = ?
                      AND steering.stage_id = ?
                      AND steering.stage_generation = ?
                      AND steering.status = 'ADMITTED'
                      AND steering.successor_owner_kind = 'STAGE_TURN'
                      AND steering.rowid < (
                          SELECT current.rowid
                          FROM stage_steering_request_v257 current
                          WHERE current.id = ?)
                ),
                latest_lineage AS (
                    SELECT * FROM lineage_candidate
                    ORDER BY successor_rank DESC, steering_order DESC
                    LIMIT 1
                )
                SELECT turn.launch_input, execution.id AS execution_id,
                       execution.provider_session_id,
                       CASE WHEN json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.disposition') = 'USER_WAIT'
                         THEN CASE WHEN json_type(turn.launch_input,
                             '$.resumeSessionId') IS NULL THEN 0
                           ELSE json_extract(turn.launch_input,
                             '$.priorCumulativeInputTokens') END
                         ELSE json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.providerCumulativeInputTokens') END
                           AS cumulative_input_tokens,
                       CASE WHEN json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.disposition') = 'USER_WAIT'
                         THEN CASE WHEN json_type(turn.launch_input,
                             '$.resumeSessionId') IS NULL THEN 0
                           ELSE json_extract(turn.launch_input,
                             '$.priorCumulativeOutputTokens') END
                         ELSE json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.providerCumulativeOutputTokens') END
                           AS cumulative_output_tokens,
                       CASE WHEN execution.provider_session_id IS NOT NULL
                           AND execution.provider_session_id <> ''
                           AND execution.provider = ?
                           AND json_extract(turn.launch_input, '$.transport') =
                               'CLI'
                           AND json_extract(turn.launch_input, '$.provider') = ?
                           AND json_extract(turn.launch_input, '$.model') = ?
                           AND json_extract(
                               turn.launch_input, '$.workingDirectory') = ?
                           AND json_extract(
                               turn.launch_input, '$.toolEndpoint.profile') =
                               'STAGE_DEVELOPMENT'
                           AND (? <> 'CANCEL_AND_REPLACE' OR NOT (
                               EXISTS (
                                   SELECT 1 FROM stage_turn later_turn
                                   WHERE later_turn.stage_id = turn.stage_id
                                     AND later_turn.stage_generation =
                                         turn.stage_generation
                                     AND later_turn.rowid > turn.rowid)
                               OR EXISTS (
                                   SELECT 1 FROM stage_turn live
                                   WHERE live.stage_id = turn.stage_id
                                     AND live.stage_generation =
                                         turn.stage_generation
                                     AND live.status IN ('REQUESTED', 'QUEUED',
                                         'CLAIMED', 'RUNNING'))))
                       THEN 1 ELSE 0 END AS session_reusable
                FROM latest_lineage candidate
                JOIN stage_turn turn
                  ON turn.id = candidate.turn_id
                 AND turn.operation_id = candidate.operation_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = candidate.ticket_id
                 AND ticket.owner_kind = 'STAGE_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                 AND ticket.status = 'SUCCEEDED'
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                 AND execution.status = 'SUCCEEDED'
                WHERE turn.status = 'SUCCEEDED'
                  AND turn.stage_id = ? AND turn.stage_generation = ?
                  AND COALESCE(
                      json_extract(json_extract(execution.raw_result,
                          '$.payloadJson'),
                          '$.outputCodeSubject.codeFingerprint'),
                      turn.expected_code_fingerprint) = ?
                  AND COALESCE(
                      json_extract(json_extract(execution.raw_result,
                          '$.payloadJson'), '$.outputCodeSubject.headSha'),
                      turn.expected_head_sha) = ?
                  AND COALESCE(
                      json_extract(json_extract(execution.raw_result,
                          '$.payloadJson'), '$.outputCodeSubject.baseSha'),
                      turn.expected_base_sha) = ?
                  AND (? = 'CANCEL_AND_REPLACE' OR (
                      execution.provider_session_id IS NOT NULL
                      AND execution.provider_session_id <> ''
                      AND execution.provider = ?
                      AND json_extract(turn.launch_input, '$.transport') = 'CLI'
                      AND json_extract(turn.launch_input, '$.provider') = ?
                      AND json_extract(turn.launch_input, '$.model') = ?
                      AND json_extract(
                          turn.launch_input, '$.workingDirectory') = ?
                      AND json_extract(
                          turn.launch_input, '$.toolEndpoint.profile') =
                          'STAGE_DEVELOPMENT'))
                  AND (? = 'CANCEL_AND_REPLACE' OR NOT EXISTS (
                      SELECT 1 FROM stage_turn live
                      WHERE live.stage_id = turn.stage_id
                        AND live.stage_generation = turn.stage_generation
                        AND live.status IN (
                            'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')))
                ORDER BY execution.infrastructure_attempt DESC
                LIMIT 1
                """, (rs, row) -> new CliContinuation(
                rs.getString("launch_input"), rs.getString("execution_id"),
                rs.getString("provider_session_id"),
                nullableLong(rs, "cumulative_input_tokens"),
                nullableLong(rs, "cumulative_output_tokens"),
                rs.getInt("session_reusable") == 1),
                predecessor.ownerId(), predecessor.operationId(),
                predecessor.ticketId(), request.mode().name(), request.taskId(),
                request.taskEpoch(), request.stageId(), request.stageGeneration(),
                request.id(),
                provider, provider, model, workingDirectory,
                request.mode().name(),
                stageId, stageGeneration,
                codeFingerprint, headSha, baseSha,
                request.mode().name(), provider, provider, model,
                workingDirectory,
                request.mode().name())
                .stream().findFirst();
    }

    public Optional<CliContinuation> cliContinuation(
            String predecessorTurnId,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String provider,
            String model,
            String workingDirectory)
    {
        requireText(predecessorTurnId, "predecessorTurnId");
        requireText(stageId, "stageId");
        requireText(codeFingerprint, "codeFingerprint");
        requireText(headSha, "headSha");
        requireText(baseSha, "baseSha");
        requireText(provider, "provider");
        requireText(model, "model");
        requireText(workingDirectory, "workingDirectory");
        return jdbc.query("""
                SELECT turn.launch_input, execution.id AS execution_id,
                       execution.provider_session_id,
                       CASE WHEN json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.disposition') = 'USER_WAIT'
                         THEN CASE WHEN json_type(turn.launch_input,
                             '$.resumeSessionId') IS NULL THEN 0
                           ELSE json_extract(turn.launch_input,
                             '$.priorCumulativeInputTokens') END
                         ELSE json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.providerCumulativeInputTokens') END
                           AS cumulative_input_tokens,
                       CASE WHEN json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.disposition') = 'USER_WAIT'
                         THEN CASE WHEN json_type(turn.launch_input,
                             '$.resumeSessionId') IS NULL THEN 0
                           ELSE json_extract(turn.launch_input,
                             '$.priorCumulativeOutputTokens') END
                         ELSE json_extract(json_extract(
                           execution.raw_result, '$.payloadJson'),
                           '$.providerCumulativeOutputTokens') END
                           AS cumulative_output_tokens,
                       CASE WHEN execution.provider_session_id IS NOT NULL
                           AND execution.provider_session_id <> ''
                           AND execution.provider = ?
                           AND json_extract(turn.launch_input, '$.transport') =
                               'CLI'
                           AND json_extract(turn.launch_input, '$.provider') = ?
                           AND json_extract(turn.launch_input, '$.model') = ?
                           AND json_extract(
                               turn.launch_input, '$.workingDirectory') = ?
                           AND json_extract(
                               turn.launch_input, '$.toolEndpoint.profile') =
                               'STAGE_DEVELOPMENT'
                           AND NOT EXISTS (
                               SELECT 1 FROM stage_turn later
                               WHERE later.stage_id = turn.stage_id
                                 AND later.stage_generation =
                                     turn.stage_generation
                                 AND later.rowid > turn.rowid)
                       THEN 1 ELSE 0 END AS session_reusable
                FROM stage_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'STAGE_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                 AND ticket.status = 'SUCCEEDED'
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                 AND execution.status = 'SUCCEEDED'
                WHERE turn.id = ?
                  AND turn.status = 'SUCCEEDED'
                  AND turn.stage_id = ? AND turn.stage_generation = ?
                  AND COALESCE(
                      json_extract(json_extract(execution.raw_result,
                          '$.payloadJson'),
                          '$.outputCodeSubject.codeFingerprint'),
                      turn.expected_code_fingerprint) = ?
                  AND COALESCE(
                      json_extract(json_extract(execution.raw_result,
                          '$.payloadJson'), '$.outputCodeSubject.headSha'),
                      turn.expected_head_sha) = ?
                  AND COALESCE(
                      json_extract(json_extract(execution.raw_result,
                          '$.payloadJson'), '$.outputCodeSubject.baseSha'),
                      turn.expected_base_sha) = ?
                ORDER BY execution.infrastructure_attempt DESC
                LIMIT 1
                """, (rs, row) -> new CliContinuation(
                rs.getString("launch_input"), rs.getString("execution_id"),
                rs.getString("provider_session_id"),
                nullableLong(rs, "cumulative_input_tokens"),
                nullableLong(rs, "cumulative_output_tokens"),
                rs.getInt("session_reusable") == 1),
                provider, provider, model, workingDirectory,
                predecessorTurnId, stageId, stageGeneration,
                codeFingerprint, headSha, baseSha)
                .stream().findFirst();
    }

    public List<String> executionLog(String executionId)
    {
        requireText(executionId, "executionId");
        return jdbc.query("""
                SELECT payload FROM agent_execution_log
                WHERE execution_id = ? ORDER BY seq
                """, (rs, row) -> rs.getString("payload"), executionId);
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
                  AND NOT EXISTS (
                      SELECT 1
                      FROM local_publish_base_sync_episode base_sync
                      WHERE base_sync.task_id = task.id
                        AND base_sync.task_epoch = task.epoch
                        AND base_sync.local_development_stage_id = local.stage_id
                        AND base_sync.stage_generation = local.generation
                        AND base_sync.status NOT IN (
                            'HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED'))
                """, (rs, row) -> localContext(rs), request.taskId(),
                request.stageId(), request.taskEpoch(), request.stageGeneration(),
                stageVersion);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact Local steering admission context is unavailable");
        }
        return rows.getFirst();
    }

    /** True only when the request's exact Local owner has a live base-sync saga. */
    public boolean hasLiveLocalPublishBaseSync(Request request)
    {
        requireNonNull(request, "request is null");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM local_publish_base_sync_episode
                WHERE task_id = ? AND task_epoch = ?
                  AND local_development_stage_id = ?
                  AND stage_generation = ?
                  AND status NOT IN (
                      'HANDED_OFF', 'FAILED', 'CANCELED', 'SUPERSEDED')
                """, Integer.class, request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageGeneration());
        return count != null && count > 0;
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

    /**
     * Atomically records a new semantic attempt and fences the exact Local
     * StageTurn whose successful raw result could not be delivered.
     */
    public void insertMalformedLocalResultReplacementTurn(
            LocalTurn turn, Request steering)
    {
        requireTransaction();
        requireNonNull(turn, "turn is null");
        requireNonNull(steering, "steering is null");
        Predecessor predecessor = requireNonNull(
                steering.predecessor(), "predecessor is null");
        ResultFence pending = new ResultFence(
                steering.taskEpoch(), steering.stageId(),
                steering.stageGeneration(), predecessor.operationId(),
                predecessor.attempt(), predecessor.codeFingerprint(),
                predecessor.headSha(), predecessor.baseSha());
        if (!malformedLocalResultPendingReady(steering, pending)) {
            throw new IllegalStateException(
                    "Malformed Local RESULT_PENDING owner changed before admission");
        }
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
                  AND previous.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, turn.turnId(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.attempt(), turn.taskEpoch(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.deliveryLane(), turn.launchInput(),
                turn.requestedAt().toEpochMilli(), predecessor.ownerId(),
                predecessor.operationId());
        if (stageTurn != 1) {
            throw new IllegalStateException(
                    "Malformed Local result predecessor changed before admission");
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
                    "Malformed Local result request owner is missing");
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
        int cancellation = jdbc.update("""
                UPDATE local_stage_turn_request
                SET cancellation_requested_at_ms = ?
                WHERE stage_turn_id = ?
                  AND cancellation_requested_at_ms IS NULL
                """, turn.requestedAt().toEpochMilli(), predecessor.ownerId());
        int superseded = jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUPERSEDED',
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?,
                    error_message = 'replaced after malformed result delivery'
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, turn.requestedAt().toEpochMilli(), predecessor.ownerId(),
                predecessor.operationId());
        if (cancellation != 1 || superseded != 1) {
            throw new IllegalStateException(
                    "Malformed Local result predecessor was not fenced exactly once");
        }
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

    private static Long nullableLong(ResultSet rs, String name)
            throws SQLException
    {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
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

    public record CliContinuation(
            String launchInput, String executionId, String providerSessionId,
            Long cumulativeInputTokens, Long cumulativeOutputTokens,
            boolean sessionReusable)
    {
        public CliContinuation(
                String launchInput,
                String executionId,
                String providerSessionId)
        {
            this(launchInput, executionId, providerSessionId, 0L, 0L, true);
        }

        public CliContinuation(
                String launchInput,
                String executionId,
                String providerSessionId,
                boolean sessionReusable)
        {
            this(launchInput, executionId, providerSessionId, 0L, 0L,
                    sessionReusable);
        }

        public CliContinuation
        {
            requireText(launchInput, "launchInput");
            requireText(executionId, "executionId");
            if (sessionReusable) {
                requireText(providerSessionId, "providerSessionId");
            }
            if ((cumulativeInputTokens == null)
                    != (cumulativeOutputTokens == null)
                    || (cumulativeInputTokens != null
                    && (cumulativeInputTokens < 0 || cumulativeOutputTokens < 0))) {
                throw new IllegalArgumentException(
                        "cumulative usage baseline is invalid");
            }
        }
    }

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
