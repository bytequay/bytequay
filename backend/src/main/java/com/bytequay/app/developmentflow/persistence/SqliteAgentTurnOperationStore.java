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

import java.sql.ResultSet;
import java.sql.SQLException;
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
                SELECT turn.id, turn.task_id, turn.task_epoch,
                       turn.trigger_stage_id AS turn_stage_id,
                       turn.trigger_stage_generation AS turn_stage_generation,
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
                       brain.provider AS brain_provider, brain.model AS brain_model
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
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
                SELECT turn.id, owner.task_id, turn.task_epoch,
                       turn.stage_id AS turn_stage_id,
                       turn.stage_generation AS turn_stage_generation,
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
                JOIN task_code_identity identity ON identity.task_id = task.id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE turn.id = ? AND task.workflow_version = 'V2'
                """,
                (rs, row) -> exactTurn(
                        DispatchTicket.OwnerKind.STAGE_TURN, rs),
                turnId).stream().findFirst();
    }

    private static AgentTurnOperationHandler.ExactTurn exactTurn(
            DispatchTicket.OwnerKind ownerKind, ResultSet rs)
            throws SQLException
    {
        return new AgentTurnOperationHandler.ExactTurn(
                ownerKind,
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("turn_stage_id"),
                nullableLong(rs, "turn_stage_generation"),
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
