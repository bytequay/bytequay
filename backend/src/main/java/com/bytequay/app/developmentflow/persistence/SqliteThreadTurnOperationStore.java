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

import com.bytequay.app.developmentflow.execution.agentturn.ThreadTurnOperationHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact execution and operation-scoped MCP view for V2 ThreadTurns. */
@Repository
public class SqliteThreadTurnOperationStore
        implements ThreadTurnOperationHandler.Store
{
    private final JdbcTemplate jdbc;

    public SqliteThreadTurnOperationStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<ThreadTurnOperationHandler.ExactTurn> find(String turnId)
    {
        requireText(turnId, "turnId");
        return jdbc.query("""
                SELECT turn.id, turn.trunk_id, trunk.workspace_id,
                       turn.purpose, turn.status, turn.operation_id,
                       turn.attempt, turn.launch_input, trunk.lifecycle_state
                FROM thread_turn turn
                JOIN threads trunk ON trunk.id = turn.trunk_id
                WHERE turn.id = ? AND trunk.turn_version = 'V2'
                """,
                (rs, row) -> new ThreadTurnOperationHandler.ExactTurn(
                        rs.getString("id"), rs.getString("trunk_id"),
                        rs.getString("workspace_id"), rs.getString("purpose"),
                        rs.getString("status"), rs.getString("operation_id"),
                        rs.getInt("attempt"), rs.getString("launch_input"),
                        rs.getString("lifecycle_state")),
                turnId).stream().findFirst();
    }

    @Override
    public ThreadTurnOperationHandler.StartDisposition tryStart(
            String turnId, String operationId, Instant startedAt)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireNonNull(startedAt, "startedAt is null");
        int changed = jdbc.update("""
                UPDATE thread_turn
                SET status = 'RUNNING', started_at_ms = ?, error_message = NULL
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED')
                  AND started_at_ms IS NULL AND finished_at_ms IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM thread_turn other
                      WHERE other.trunk_id = thread_turn.trunk_id
                        AND other.id <> thread_turn.id
                        AND other.status = 'RUNNING')
                """, startedAt.toEpochMilli(), turnId, operationId);
        if (changed == 1) {
            return ThreadTurnOperationHandler.StartDisposition.STARTED;
        }
        boolean another = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM thread_turn target
                    JOIN thread_turn other ON other.trunk_id = target.trunk_id
                    WHERE target.id = ? AND target.operation_id = ?
                      AND target.status IN ('REQUESTED', 'QUEUED', 'CLAIMED')
                      AND other.id <> target.id AND other.status = 'RUNNING')
                """, Boolean.class, turnId, operationId));
        return another
                ? ThreadTurnOperationHandler.StartDisposition.OTHER_TURN_RUNNING
                : ThreadTurnOperationHandler.StartDisposition.STALE;
    }

    @Override
    public boolean resetAfterLaunchFailure(
            String turnId, String operationId, Instant resetAt)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireNonNull(resetAt, "resetAt is null");
        return jdbc.update("""
                UPDATE thread_turn
                SET status = 'REQUESTED', started_at_ms = NULL,
                    error_message = NULL
                WHERE id = ? AND operation_id = ? AND status = 'RUNNING'
                  AND started_at_ms <= ? AND finished_at_ms IS NULL
                """, turnId, operationId, resetAt.toEpochMilli()) == 1;
    }

    @Override
    public Optional<String> findMcpTrunk(
            String turnId, String operationId, Instant now)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireNonNull(now, "now is null");
        return jdbc.query("""
                SELECT turn.trunk_id
                FROM thread_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                JOIN capacity_lease lease
                  ON lease.id = ticket.capacity_lease_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.status = 'RUNNING'
                  AND ticket.operation_kind = 'EXECUTE_THREAD_TURN'
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'THREAD_TURN_RESULT'
                  AND ticket.status = 'RUNNING'
                  AND ticket.trunk_control = 1
                  AND ticket.exclusive_task = 0
                  AND ticket.writer_required = 0
                  AND ticket.task_id IS NULL
                  AND lease.ticket_id = ticket.id
                  AND lease.operation_id = turn.operation_id
                  AND lease.trunk_control = 1
                  AND lease.released_at_ms IS NULL
                  AND lease.expires_at_ms > ?
                """, (rs, row) -> rs.getString("trunk_id"),
                turnId, operationId, now.toEpochMilli())
                .stream().findFirst();
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
