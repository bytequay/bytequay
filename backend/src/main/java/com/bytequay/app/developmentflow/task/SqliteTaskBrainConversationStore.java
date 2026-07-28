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
package com.bytequay.app.developmentflow.task;

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

/** Persistence boundary for Task-owned, read-only V2 Brain conversation Turns. */
@Repository
public class SqliteTaskBrainConversationStore
{
    private final JdbcTemplate jdbc;

    public SqliteTaskBrainConversationStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public boolean isV2Task(String taskId)
    {
        requireText(taskId, "taskId");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tasks
                WHERE id = ? AND workflow_version = 'V2'
                """, Integer.class, taskId);
        return count != null && count == 1;
    }

    public ConversationContext requireConversationContext(String taskId)
    {
        requireText(taskId, "taskId");
        List<ConversationContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.lifecycle_state, task.epoch,
                       current.stage_id, current.stage_generation,
                       owner.kind AS stage_kind,
                       code.code_fingerprint, code.head_sha, code.base_sha,
                       identity.worktree_path, creation.repository_id,
                       creation.work_model_snapshot, brain.provider, brain.model,
                       brain.role_skill
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_creation_context creation ON creation.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage owner ON owner.id = current.stage_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                """, (rs, row) -> conversationContext(rs), taskId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("no typed Task Brain: " + taskId);
        }
        return rows.getFirst();
    }

    public List<Message> conversation(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query("""
                SELECT message.id, message.turn_id, message.seq, message.role,
                       message.body, message.created_at_ms
                FROM task_message message
                JOIN task_turn turn ON turn.id = message.turn_id
                WHERE turn.task_id = ?
                  AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
                ORDER BY message.created_at_ms, turn.requested_at_ms,
                         message.turn_id, message.seq
                """, (rs, row) -> new Message(
                        rs.getString("id"), rs.getString("turn_id"),
                        rs.getInt("seq"), rs.getString("role"),
                        rs.getString("body"), instant(rs, "created_at_ms")), taskId);
    }

    public void insertConversationTurn(NewTurn turn, Message userMessage,
            List<Attachment> attachments)
    {
        requireTransaction();
        insertTurn(turn);
        insertMessage(userMessage);
        for (Attachment attachment : attachments) {
            jdbc.update("""
                    INSERT INTO task_attachment(
                        id, turn_id, kind, content_ref, media_type, digest,
                        created_at_ms)
                    VALUES (?, ?, 'IMAGE', ?, ?, ?, ?)
                    """, attachment.id(), turn.turnId(), attachment.contentRef(),
                    attachment.mediaType(), attachment.digest(),
                    attachment.createdAt().toEpochMilli());
        }
        insertTicket(turn);
    }

    public Optional<ResultReceipt> findResultReceipt(String turnId)
    {
        requireText(turnId, "turnId");
        return jdbc.query("""
                SELECT task_turn_id, operation_id, raw_outcome,
                       raw_result_digest, acceptance, terminal_status,
                       assistant_message_id, evidence, recorded_at_ms
                FROM task_brain_conversation_result_v266
                WHERE task_turn_id = ?
                """, (rs, row) -> resultReceipt(rs), turnId)
                .stream().findFirst();
    }

    public String requireConversationTaskId(String turnId, String operationId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        List<String> rows = jdbc.query("""
                SELECT task_id FROM task_turn
                WHERE id = ? AND operation_id = ?
                  AND purpose = 'TASK_BRAIN_CONVERSATION'
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Task Brain conversation owner is missing");
        }
        return rows.getFirst();
    }

    public String requireContinuationTaskId(String turnId, String operationId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        List<String> rows = jdbc.query("""
                SELECT task_id FROM task_turn
                WHERE id = ? AND operation_id = ?
                  AND purpose IN (
                    'TASK_BRAIN_CONVERSATION',
                    'DEVELOPMENT_BRAIN_REVIEW',
                    'REMOTE_FEEDBACK_BRAIN_REVIEW')
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Task Brain continuation owner is missing");
        }
        return rows.getFirst();
    }

    public DeliveryContext requireDeliveryContext(String turnId, String operationId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        List<DeliveryContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, turn.operation_id, turn.status,
                       turn.task_id, turn.task_epoch, turn.attempt,
                       turn.trigger_stage_id, turn.trigger_stage_generation,
                       turn.expected_code_fingerprint, turn.expected_head_sha,
                       turn.expected_base_sha, task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.completed_at_ms AS stage_completed_at_ms,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       ticket.status AS ticket_status
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage owner ON owner.id = turn.trigger_stage_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> deliveryContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact Task Brain conversation delivery is missing");
        }
        return rows.getFirst();
    }

    public ResultReceipt finish(
            DeliveryContext context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            String terminalStatus,
            String assistantText,
            String evidence,
            Instant at)
    {
        requireTransaction();
        String assistantId = null;
        if (assistantText != null) {
            assistantId = "task-brain-assistant:" + context.turnId();
            Integer next = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(seq), 0) + 1
                    FROM task_message WHERE turn_id = ?
                    """, Integer.class, context.turnId());
            insertMessage(new Message(
                    assistantId, context.turnId(), requireNonNull(next),
                    "ASSISTANT", assistantText, at));
        }
        int changed = jdbc.update("""
                UPDATE task_turn
                SET status = ?,
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """, terminalStatus, at.toEpochMilli(),
                terminalStatus.equals("SUCCEEDED") ? null : evidence,
                context.turnId(), context.operationId());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Task Brain conversation changed before delivery");
        }
        jdbc.update("""
                INSERT INTO task_brain_conversation_result_v266(
                    task_turn_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, terminal_status,
                    assistant_message_id, evidence, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, context.turnId(), context.operationId(), rawOutcome,
                rawDigest, acceptance, terminalStatus, assistantId, evidence,
                at.toEpochMilli());
        return new ResultReceipt(
                context.turnId(), context.operationId(), rawOutcome, rawDigest,
                acceptance, terminalStatus, assistantId, evidence, at);
    }

    public Optional<String> findContinuationSuccessor(String waitKind, String waitId)
    {
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        return jdbc.query("""
                SELECT successor_turn_id
                FROM task_turn_user_wait_continuation_v266
                WHERE wait_kind = ? AND wait_id = ?
                """, (rs, row) -> rs.getString(1), waitKind, waitId)
                .stream().findFirst();
    }

    public Optional<ContinuationContext> findContinuationContext(
            String turnId,
            String operationId,
            String waitKind,
            String waitId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireText(waitKind, "waitKind");
        requireText(waitId, "waitId");
        List<ContinuationContext> rows = jdbc.query("""
                SELECT source.id AS source_turn_id,
                       source.operation_id AS source_operation_id,
                       source.purpose, source.task_id, source.task_epoch,
                       source.attempt AS source_attempt,
                       source.trigger_stage_id,
                       source.trigger_stage_generation,
                       source.expected_code_fingerprint,
                       source.expected_head_sha, source.expected_base_sha,
                       source.delivery_lane, source.launch_input,
                       logical.id AS logical_turn_id,
                       logical.operation_id AS logical_operation_id,
                       logical.attempt AS logical_attempt,
                       ticket.callback_route, ticket.lane_mask,
                       ticket.exclusive_task, ticket.writer_required,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.completed_at_ms AS stage_completed_at_ms,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha
                FROM task_turn source
                JOIN typed_user_wait_result result
                  ON result.operation_id = source.operation_id
                LEFT JOIN task_turn_user_wait_continuation_v266 prior
                  ON prior.successor_turn_id = source.id
                JOIN task_turn logical
                  ON logical.id = COALESCE(prior.logical_turn_id, source.id)
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = source.operation_id
                JOIN tasks task ON task.id = source.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage owner ON owner.id = source.trigger_stage_id
                LEFT JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                WHERE source.id = ? AND source.operation_id = ?
                  AND source.purpose IN (
                    'TASK_BRAIN_CONVERSATION',
                    'DEVELOPMENT_BRAIN_REVIEW',
                    'REMOTE_FEEDBACK_BRAIN_REVIEW')
                  AND source.status = 'SUCCEEDED'
                  AND result.owner_kind = 'TASK_TURN'
                  AND result.turn_id = source.id
                  AND result.wait_kind = ? AND result.wait_id = ?
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = source.id
                  AND ticket.status = 'SUCCEEDED'
                  AND ((? = 'QUESTION' AND EXISTS (
                        SELECT 1 FROM task_question question
                        WHERE question.id = ? AND question.turn_id = source.id
                          AND question.state = 'ANSWERED'
                          AND question.continuation_state = 'READY'))
                    OR (? = 'PERMISSION' AND EXISTS (
                        SELECT 1 FROM permission_request permission
                        WHERE permission.id = ?
                          AND permission.turn_kind = 'TASK'
                          AND permission.turn_id = source.id
                          AND permission.operation_id = source.operation_id
                          AND permission.state <> 'OPEN'
                          AND permission.continuation_state = 'READY')))
                """, (rs, row) -> continuationContext(rs),
                turnId, operationId, waitKind, waitId,
                waitKind, waitId, waitKind, waitId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Task Brain user wait is ambiguous");
        }
        return rows.stream().findFirst();
    }

    public void insertContinuation(
            ContinuationContext source,
            NewTurn successor,
            String waitKind,
            String waitId,
            Message userMessage)
    {
        requireTransaction();
        insertTurn(successor);
        jdbc.update("""
                INSERT INTO task_turn_user_wait_continuation_v266(
                    wait_kind, wait_id, source_turn_id, source_operation_id,
                    logical_turn_id, logical_operation_id, successor_turn_id,
                    successor_operation_id, purpose, task_id, task_epoch,
                    trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, waitKind, waitId, source.sourceTurnId(),
                source.sourceOperationId(), source.logicalTurnId(),
                source.logicalOperationId(), successor.turnId(),
                successor.operationId(), successor.purpose(), successor.taskId(),
                successor.taskEpoch(), successor.stageId(),
                successor.stageGeneration(), successor.codeFingerprint(),
                successor.headSha(), successor.baseSha(),
                successor.requestedAt().toEpochMilli());
        if (userMessage != null) {
            insertMessage(userMessage);
        }
        insertTicket(successor);
    }

    private void insertTurn(NewTurn turn)
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turn.turnId(), turn.taskId(), turn.purpose(),
                turn.operationId(), turn.attempt(), turn.taskEpoch(),
                turn.stageId(), turn.stageGeneration(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha(), turn.deliveryLane(),
                turn.launchInput(), turn.requestedAt().toEpochMilli());
    }

    private void insertTicket(NewTurn turn)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.callbackRoute(), turn.laneMask(), turn.exclusiveTask() ? 1 : 0,
                turn.writerRequired() ? 1 : 0, turn.workspaceId(), turn.trunkId(),
                turn.taskId(), turn.taskEpoch(), turn.stageId(),
                turn.stageGeneration(), turn.attempt(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha(), turn.requestedAt().toEpochMilli());
    }

    private void insertMessage(Message message)
    {
        jdbc.update("""
                INSERT INTO task_message(
                    id, turn_id, seq, role, body, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, message.id(), message.turnId(), message.seq(),
                message.role(), message.body(), message.createdAt().toEpochMilli());
    }

    private static ConversationContext conversationContext(ResultSet rs)
            throws SQLException
    {
        return new ConversationContext(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getString("lifecycle_state"),
                rs.getLong("epoch"), rs.getString("stage_id"),
                nullableLong(rs, "stage_generation"), rs.getString("stage_kind"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("worktree_path"),
                rs.getString("repository_id"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"));
    }

    private static DeliveryContext deliveryContext(ResultSet rs)
            throws SQLException
    {
        return new DeliveryContext(
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getString("status"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getInt("attempt"),
                rs.getString("trigger_stage_id"),
                nullableLong(rs, "trigger_stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                nullableLong(rs, "stage_completed_at_ms") != null,
                rs.getString("current_code_fingerprint"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"),
                rs.getString("ticket_status"));
    }

    private static ContinuationContext continuationContext(ResultSet rs)
            throws SQLException
    {
        return new ContinuationContext(
                rs.getString("source_turn_id"),
                rs.getString("source_operation_id"), rs.getString("purpose"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getInt("source_attempt"), rs.getString("trigger_stage_id"),
                nullableLong(rs, "trigger_stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("delivery_lane"), rs.getString("launch_input"),
                rs.getString("logical_turn_id"),
                rs.getString("logical_operation_id"),
                rs.getInt("logical_attempt"),
                rs.getString("callback_route"), rs.getInt("lane_mask"),
                rs.getBoolean("exclusive_task"),
                rs.getBoolean("writer_required"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                nullableLong(rs, "stage_completed_at_ms") != null,
                rs.getString("current_code_fingerprint"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"));
    }

    private static ResultReceipt resultReceipt(ResultSet rs)
            throws SQLException
    {
        return new ResultReceipt(
                rs.getString("task_turn_id"), rs.getString("operation_id"),
                rs.getString("raw_outcome"),
                rs.getString("raw_result_digest"), rs.getString("acceptance"),
                rs.getString("terminal_status"),
                rs.getString("assistant_message_id"), rs.getString("evidence"),
                instant(rs, "recorded_at_ms"));
    }

    private static Long nullableLong(ResultSet rs, String name)
            throws SQLException
    {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String name)
            throws SQLException
    {
        return Instant.ofEpochMilli(rs.getLong(name));
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Task Brain conversation store requires a transaction");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record ConversationContext(
            String taskId, String trunkId, String workspaceId,
            String lifecycle, long taskEpoch, String stageId,
            Long stageGeneration, String stageKind, String codeFingerprint,
            String headSha, String baseSha, String worktreePath,
            String repositoryId, String workModelSnapshot, String provider,
            String model, String roleSkill) {}

    public record NewTurn(
            String turnId, String operationId, String ticketId, String purpose,
            String workspaceId, String trunkId, String taskId, long taskEpoch,
            String stageId, Long stageGeneration, String codeFingerprint,
            String headSha, String baseSha, int attempt, String deliveryLane,
            int laneMask, boolean exclusiveTask, boolean writerRequired,
            String callbackRoute, String launchInput, Instant requestedAt) {}

    public record Message(
            String id, String turnId, int seq, String role, String body,
            Instant createdAt) {}

    public record Attachment(
            String id, String contentRef, String mediaType, String digest,
            Instant createdAt) {}

    public record DeliveryContext(
            String turnId, String operationId, String turnStatus, String taskId,
            long taskEpoch, int attempt, String stageId, Long stageGeneration,
            String codeFingerprint, String headSha, String baseSha,
            String lifecycle, long currentTaskEpoch, String currentStageId,
            Long currentStageGeneration, boolean stageCompleted,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha, String ticketStatus)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration == null ? 0 : stageGeneration,
                    operationId, attempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record ResultReceipt(
            String turnId, String operationId, String rawOutcome,
            String rawResultDigest, String acceptance, String terminalStatus,
            String assistantMessageId, String evidence, Instant recordedAt) {}

    public record ContinuationContext(
            String sourceTurnId, String sourceOperationId, String purpose,
            String taskId, long taskEpoch, int sourceAttempt, String stageId,
            Long stageGeneration, String codeFingerprint, String headSha,
            String baseSha, String deliveryLane, String launchInput,
            String logicalTurnId, String logicalOperationId, int logicalAttempt,
            String callbackRoute, int laneMask, boolean exclusiveTask,
            boolean writerRequired, String trunkId, String workspaceId,
            String lifecycle, long currentTaskEpoch, String currentStageId,
            Long currentStageGeneration, boolean stageCompleted,
            String currentCodeFingerprint, String currentHeadSha,
            String currentBaseSha)
    {
        public ResultFence logicalFence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration == null ? 0 : stageGeneration,
                    logicalOperationId, logicalAttempt,
                    codeFingerprint, headSha, baseSha);
        }
    }
}
