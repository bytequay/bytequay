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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact read-only V2 Turn launch view. It never advances a Turn or aggregate. */
@Repository
public class SqliteAgentTurnOperationStore
        implements AgentTurnOperationHandler.Store
{
    private final JdbcTemplate jdbc;

    public SqliteAgentTurnOperationStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<AgentTurnOperationHandler.ExactTurn> find(
            DispatchTicket.OwnerKind ownerKind, String turnId)
    {
        requireNonNull(ownerKind, "ownerKind is null");
        requireText(turnId, "turnId");
        return switch (ownerKind) {
            case TASK_TURN -> findTaskTurn(turnId);
            case STAGE_TURN -> findStageTurn(turnId);
            default -> Optional.empty();
        };
    }

    private Optional<AgentTurnOperationHandler.ExactTurn> findTaskTurn(String turnId)
    {
        return jdbc.query("""
                SELECT turn.id, task.thread_id AS trunk_id,
                       thread.workspace_id, turn.task_id, turn.task_epoch,
                       turn.trigger_stage_id AS turn_stage_id,
                       turn.trigger_stage_generation AS turn_stage_generation,
                       owner.kind AS stage_kind,
                       turn.purpose, turn.status, turn.operation_id, turn.attempt,
                       turn.expected_code_fingerprint, turn.expected_head_sha,
                       turn.expected_base_sha, turn.launch_input,
                       CASE WHEN turn.purpose = 'TASK_COMPLETION_SUMMARY'
                         OR (turn.purpose = 'TASK_BRAIN_CONVERSATION'
                           AND task.lifecycle_state IN (
                             'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
                         THEN json_extract(turn.launch_input, '$.workingDirectory')
                         ELSE identity.worktree_path END AS worktree_path,
                       task.lifecycle_state,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.completed_at_ms AS turn_stage_completed_at_ms,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       brain.provider AS brain_provider, brain.model AS brain_model
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
                JOIN threads thread ON thread.id = task.thread_id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage owner ON owner.id = turn.trigger_stage_id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE turn.id = ? AND task.workflow_version = 'V2'
                """,
                (rs, row) -> exactTurn(
                        DispatchTicket.OwnerKind.TASK_TURN, rs),
                turnId).stream().findFirst();
    }

    private Optional<AgentTurnOperationHandler.ExactTurn> findStageTurn(String turnId)
    {
        return jdbc.query("""
                SELECT turn.id, task.thread_id AS trunk_id,
                       thread.workspace_id, owner.task_id, turn.task_epoch,
                       turn.stage_id AS turn_stage_id,
                       turn.stage_generation AS turn_stage_generation,
                       owner.kind AS stage_kind,
                       turn.purpose, turn.status, turn.operation_id, turn.attempt,
                       turn.expected_code_fingerprint, turn.expected_head_sha,
                       turn.expected_base_sha, turn.launch_input,
                       identity.worktree_path, task.lifecycle_state,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.completed_at_ms AS turn_stage_completed_at_ms,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       NULL AS brain_provider, NULL AS brain_model
                FROM stage_turn turn
                JOIN stage owner ON owner.id = turn.stage_id
                JOIN tasks task ON task.id = owner.task_id
                JOIN threads thread ON thread.id = task.thread_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE turn.id = ? AND task.workflow_version = 'V2'
                """,
                (rs, row) -> exactTurn(
                        DispatchTicket.OwnerKind.STAGE_TURN, rs),
                turnId).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<AgentTurnOperationHandler.McpContext> authorizeMcp(
            DispatchTicket.OwnerKind ownerKind,
            String turnId,
            String operationId,
            Instant now)
    {
        requireNonNull(ownerKind, "ownerKind is null");
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireNonNull(now, "now is null");
        return switch (ownerKind) {
            case TASK_TURN -> authorizeTaskMcp(turnId, operationId, now);
            case STAGE_TURN -> authorizeStageMcp(turnId, operationId, now);
            default -> Optional.empty();
        };
    }

    private Optional<AgentTurnOperationHandler.McpContext> authorizeTaskMcp(
            String turnId, String operationId, Instant now)
    {
        long at = now.toEpochMilli();
        jdbc.update("""
                UPDATE task_turn
                SET status = 'RUNNING', started_at_ms = COALESCE(started_at_ms, ?),
                    error_message = NULL
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND EXISTS (
                    SELECT 1
                    FROM tasks task
                    LEFT JOIN task_current_stage current
                      ON current.task_id = task.id
                    WHERE task.id = task_turn.task_id
                      AND task.workflow_version = 'V2'
                      AND task.epoch = task_turn.task_epoch
                      AND ((task_turn.purpose = 'TASK_COMPLETION_SUMMARY'
                            AND task.lifecycle_state IN (
                              'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
                            AND task_turn.trigger_stage_id IS NULL)
                        OR (task_turn.purpose = 'TASK_BRAIN_CONVERSATION'
                            AND task.lifecycle_state IN (
                              'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
                            AND task_turn.trigger_stage_id IS NULL)
                        OR (task_turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                            AND task.lifecycle_state = 'ACTIVE'
                            AND (task_turn.trigger_stage_id IS NULL OR (
                              current.stage_id = task_turn.trigger_stage_id
                              AND current.stage_generation =
                                task_turn.trigger_stage_generation)))))
                  AND EXISTS (
                    SELECT 1
                    FROM dispatch_ticket ticket
                    JOIN agent_execution execution
                      ON execution.ticket_id = ticket.id
                    JOIN capacity_lease lease
                      ON lease.id = ticket.capacity_lease_id
                     AND lease.ticket_id = ticket.id
                     AND lease.operation_id = ticket.operation_id
                    WHERE ticket.operation_id = task_turn.operation_id
                      AND ticket.owner_kind = 'TASK_TURN'
                      AND ticket.owner_id = task_turn.id
                      AND ticket.operation_kind = CASE task_turn.purpose
                        WHEN 'TASK_COMPLETION_SUMMARY'
                          THEN 'GENERATE_TASK_OUTCOME_SUMMARY'
                        ELSE 'EXECUTE_TASK_TURN' END
                      AND ticket.task_id = task_turn.task_id
                      AND ticket.task_epoch = task_turn.task_epoch
                      AND ticket.stage_id IS task_turn.trigger_stage_id
                      AND ticket.stage_generation IS
                          task_turn.trigger_stage_generation
                      AND ticket.status = 'RUNNING'
                      AND execution.status = 'RUNNING'
                      AND lease.released_at_ms IS NULL
                      AND lease.task_id = task_turn.task_id
                      AND lease.task_epoch = task_turn.task_epoch
                      AND lease.expires_at_ms > ?)
                """, at, turnId, operationId, at);
        return jdbc.query("""
                SELECT 'TASK_TURN' AS owner_kind, task.thread_id AS trunk_id,
                       thread.workspace_id, turn.task_id, turn.task_epoch,
                       turn.trigger_stage_id AS stage_id,
                       turn.trigger_stage_generation AS stage_generation,
                       turn.purpose
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
                JOIN threads thread ON thread.id = task.thread_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                JOIN capacity_lease lease
                  ON lease.id = ticket.capacity_lease_id
                 AND lease.ticket_id = ticket.id
                 AND lease.operation_id = ticket.operation_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.status = 'RUNNING'
                  AND task.workflow_version = 'V2'
                  AND task.epoch = turn.task_epoch
                  AND ((turn.purpose = 'TASK_COMPLETION_SUMMARY'
                        AND task.lifecycle_state IN (
                          'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
                        AND turn.trigger_stage_id IS NULL)
                    OR (turn.purpose = 'TASK_BRAIN_CONVERSATION'
                        AND task.lifecycle_state IN (
                          'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
                        AND turn.trigger_stage_id IS NULL)
                    OR (turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                        AND task.lifecycle_state = 'ACTIVE'
                        AND (turn.trigger_stage_id IS NULL OR (
                          current.stage_id = turn.trigger_stage_id
                          AND current.stage_generation = turn.trigger_stage_generation))))
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.operation_kind = CASE turn.purpose
                    WHEN 'TASK_COMPLETION_SUMMARY'
                      THEN 'GENERATE_TASK_OUTCOME_SUMMARY'
                    ELSE 'EXECUTE_TASK_TURN' END
                  AND ticket.task_id = turn.task_id
                  AND ticket.task_epoch = turn.task_epoch
                  AND ticket.stage_id IS turn.trigger_stage_id
                  AND ticket.stage_generation IS turn.trigger_stage_generation
                  AND ticket.status = 'RUNNING'
                  AND execution.status = 'RUNNING'
                  AND lease.released_at_ms IS NULL
                  AND lease.task_id = turn.task_id
                  AND lease.task_epoch = turn.task_epoch
                  AND lease.expires_at_ms > ?
                """, (rs, row) -> mcpContext(rs),
                turnId, operationId, at).stream().findFirst();
    }

    private Optional<AgentTurnOperationHandler.McpContext> authorizeStageMcp(
            String turnId, String operationId, Instant now)
    {
        long at = now.toEpochMilli();
        jdbc.update("""
                UPDATE stage_turn
                SET status = 'RUNNING', started_at_ms = COALESCE(started_at_ms, ?),
                    error_message = NULL
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND EXISTS (
                    SELECT 1
                    FROM stage owner
                    JOIN tasks task ON task.id = owner.task_id
                    JOIN task_current_stage current ON current.task_id = task.id
                    WHERE owner.id = stage_turn.stage_id
                      AND owner.generation = stage_turn.stage_generation
                      AND owner.completed_at_ms IS NULL
                      AND task.workflow_version = 'V2'
                      AND task.lifecycle_state = 'ACTIVE'
                      AND task.epoch = stage_turn.task_epoch
                      AND current.stage_id = stage_turn.stage_id
                      AND current.stage_generation = stage_turn.stage_generation)
                  AND EXISTS (
                    SELECT 1
                    FROM dispatch_ticket ticket
                    JOIN agent_execution execution
                      ON execution.ticket_id = ticket.id
                    JOIN capacity_lease lease
                      ON lease.id = ticket.capacity_lease_id
                     AND lease.ticket_id = ticket.id
                     AND lease.operation_id = ticket.operation_id
                    WHERE ticket.operation_id = stage_turn.operation_id
                      AND ticket.owner_kind = 'STAGE_TURN'
                      AND ticket.owner_id = stage_turn.id
                      AND ticket.operation_kind = 'EXECUTE_STAGE_TURN'
                      AND ticket.task_id = (
                        SELECT task_id FROM stage
                        WHERE id = stage_turn.stage_id)
                      AND ticket.task_epoch = stage_turn.task_epoch
                      AND ticket.stage_id = stage_turn.stage_id
                      AND ticket.stage_generation = stage_turn.stage_generation
                      AND ticket.status = 'RUNNING'
                      AND execution.status = 'RUNNING'
                      AND lease.released_at_ms IS NULL
                      AND lease.task_id = ticket.task_id
                      AND lease.task_epoch = ticket.task_epoch
                      AND lease.expires_at_ms > ?)
                """, at, turnId, operationId, at);
        return jdbc.query("""
                SELECT 'STAGE_TURN' AS owner_kind, task.thread_id AS trunk_id,
                       thread.workspace_id, owner.task_id, turn.task_epoch,
                       turn.stage_id, turn.stage_generation, turn.purpose
                FROM stage_turn turn
                JOIN stage owner ON owner.id = turn.stage_id
                JOIN tasks task ON task.id = owner.task_id
                JOIN threads thread ON thread.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                JOIN capacity_lease lease
                  ON lease.id = ticket.capacity_lease_id
                 AND lease.ticket_id = ticket.id
                 AND lease.operation_id = ticket.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.status = 'RUNNING'
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = turn.task_epoch
                  AND owner.completed_at_ms IS NULL
                  AND current.stage_id = turn.stage_id
                  AND current.stage_generation = turn.stage_generation
                  AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.operation_kind = 'EXECUTE_STAGE_TURN'
                  AND ticket.task_id = owner.task_id
                  AND ticket.task_epoch = turn.task_epoch
                  AND ticket.stage_id = turn.stage_id
                  AND ticket.stage_generation = turn.stage_generation
                  AND ticket.status = 'RUNNING'
                  AND execution.status = 'RUNNING'
                  AND lease.released_at_ms IS NULL
                  AND lease.task_id = owner.task_id
                  AND lease.task_epoch = turn.task_epoch
                  AND lease.expires_at_ms > ?
                """, (rs, row) -> mcpContext(rs),
                turnId, operationId, at).stream().findFirst();
    }

    private static AgentTurnOperationHandler.McpContext mcpContext(ResultSet rs)
            throws SQLException
    {
        return new AgentTurnOperationHandler.McpContext(
                DispatchTicket.OwnerKind.valueOf(rs.getString("owner_kind")),
                rs.getString("trunk_id"), rs.getString("workspace_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), nullableLong(rs, "stage_generation"),
                rs.getString("purpose"));
    }

    private static AgentTurnOperationHandler.ExactTurn exactTurn(
            DispatchTicket.OwnerKind ownerKind, ResultSet rs)
            throws SQLException
    {
        return new AgentTurnOperationHandler.ExactTurn(
                ownerKind,
                rs.getString("id"),
                rs.getString("trunk_id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("turn_stage_id"),
                nullableLong(rs, "turn_stage_generation"),
                rs.getString("stage_kind"),
                rs.getString("purpose"),
                rs.getString("status"),
                rs.getString("operation_id"),
                rs.getInt("attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("launch_input"),
                rs.getString("worktree_path"),
                rs.getString("lifecycle_state"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                nullableLong(rs, "turn_stage_completed_at_ms") != null,
                rs.getString("current_code_fingerprint"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"),
                rs.getString("brain_provider"),
                rs.getString("brain_model"));
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
