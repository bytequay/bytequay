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
package com.bytequay.app.developmentflow.compatibility;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only Thread-shaped status projection for a V2-owned Trunk. */
@Component
public final class V2TrunkRuntimeProjection
{
    private static final String RUNTIME_ROWS = """
            SELECT trunk.id, trunk.workspace_id, trunk.lifecycle_state,
                   trunk.updated_at_ms AS stored_updated_at_ms,
                   CASE
                     WHEN trunk.lifecycle_state = 'ARCHIVED' THEN 'ARCHIVED'
                     WHEN EXISTS (
                       SELECT 1
                       FROM thread_question question
                       JOIN thread_turn turn ON turn.id = question.turn_id
                       WHERE turn.trunk_id = trunk.id
                         AND question.state = 'OPEN')
                       OR EXISTS (
                         SELECT 1
                         FROM permission_request permission
                         JOIN thread_turn turn ON turn.id = permission.turn_id
                          AND turn.operation_id = permission.operation_id
                         WHERE turn.trunk_id = trunk.id
                           AND permission.turn_kind = 'THREAD'
                           AND permission.state = 'OPEN')
                       THEN 'NEEDS_ATTENTION'
                     WHEN EXISTS (
                       SELECT 1
                       FROM thread_turn turn
                       LEFT JOIN dispatch_ticket ticket
                         ON ticket.owner_kind = 'THREAD_TURN'
                        AND ticket.owner_id = turn.id
                        AND ticket.operation_id = turn.operation_id
                       WHERE turn.trunk_id = trunk.id
                         AND (turn.status IN ('CLAIMED', 'RUNNING')
                           OR ticket.status IN (
                             'RESULT_PENDING', 'CLAIMED', 'RUNNING',
                             'DELIVERING')))
                       OR EXISTS (
                         SELECT 1
                         FROM planning_base_refresh_operation operation
                         JOIN dispatch_ticket ticket
                           ON ticket.id = operation.dispatch_ticket_id
                         WHERE operation.trunk_id = trunk.id
                           AND operation.launch_disposition = 'PENDING'
                           AND operation.launched_thread_turn_id IS NULL
                           AND NOT EXISTS (
                             SELECT 1 FROM thread_turn turn
                             WHERE turn.id = operation.reserved_thread_turn_id)
                           AND ticket.status IN (
                             'RESULT_PENDING', 'CLAIMED', 'RUNNING',
                             'DELIVERING'))
                       THEN 'RUNNING'
                     WHEN EXISTS (
                       SELECT 1
                       FROM thread_question question
                       JOIN thread_turn turn ON turn.id = question.turn_id
                       WHERE turn.trunk_id = trunk.id
                         AND question.state = 'ANSWERED'
                         AND question.continuation_state = 'READY')
                       OR EXISTS (
                         SELECT 1
                         FROM permission_request permission
                         JOIN thread_turn turn ON turn.id = permission.turn_id
                          AND turn.operation_id = permission.operation_id
                         WHERE turn.trunk_id = trunk.id
                           AND permission.turn_kind = 'THREAD'
                           AND permission.state <> 'OPEN'
                           AND permission.continuation_state = 'READY')
                       OR EXISTS (
                         SELECT 1
                         FROM thread_turn turn
                         LEFT JOIN dispatch_ticket ticket
                           ON ticket.owner_kind = 'THREAD_TURN'
                          AND ticket.owner_id = turn.id
                          AND ticket.operation_id = turn.operation_id
                         WHERE turn.trunk_id = trunk.id
                           AND (turn.status IN ('REQUESTED', 'QUEUED')
                             OR ticket.status IN (
                               'REQUESTED', 'RETRY_WAIT',
                               'RECONCILE_WAIT')))
                       OR EXISTS (
                         SELECT 1
                         FROM planning_base_refresh_operation operation
                         WHERE operation.trunk_id = trunk.id
                           AND operation.status IN ('REQUESTED', 'SUCCEEDED')
                           AND operation.launch_disposition = 'PENDING'
                           AND operation.launched_thread_turn_id IS NULL
                           AND NOT EXISTS (
                             SELECT 1 FROM thread_turn turn
                             WHERE turn.id = operation.reserved_thread_turn_id))
                       THEN 'PENDING'
                     ELSE 'IDLE'
                   END AS effective_status,
                   (SELECT COALESCE(SUM(execution.cost_usd_milli), 0)
                    FROM agent_execution execution
                    JOIN dispatch_ticket ticket
                      ON ticket.id = execution.ticket_id
                    WHERE ticket.trunk_id = trunk.id) AS typed_cost_usd_milli,
                   (SELECT COALESCE(SUM(execution.tokens_in), 0)
                    FROM agent_execution execution
                    JOIN dispatch_ticket ticket
                      ON ticket.id = execution.ticket_id
                    WHERE ticket.trunk_id = trunk.id) AS typed_tokens_in,
                   (SELECT COALESCE(SUM(execution.tokens_out), 0)
                    FROM agent_execution execution
                    JOIN dispatch_ticket ticket
                      ON ticket.id = execution.ticket_id
                    WHERE ticket.trunk_id = trunk.id) AS typed_tokens_out,
                   MAX(
                     COALESCE((
                       SELECT MAX(COALESCE(ticket.completed_at_ms,
                           ticket.started_at_ms, ticket.created_at_ms))
                       FROM dispatch_ticket ticket
                       WHERE ticket.trunk_id = trunk.id), 0),
                     COALESCE((
                       SELECT MAX(log.created_at_ms)
                       FROM agent_execution_log log
                       JOIN agent_execution execution
                         ON execution.id = log.execution_id
                       JOIN dispatch_ticket ticket
                         ON ticket.id = execution.ticket_id
                       WHERE ticket.trunk_id = trunk.id), 0),
                     COALESCE((
                       SELECT MAX(transition.occurred_at_ms)
                       FROM trunk_transition transition
                       WHERE transition.trunk_id = trunk.id), 0)
                   ) AS typed_updated_at_ms
            FROM threads trunk
            WHERE trunk.turn_version = 'V2'
            """;

    private final JdbcTemplate jdbc;

    public V2TrunkRuntimeProjection(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Optional<RuntimeState> find(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT * FROM (%s) runtime WHERE runtime.id = ?
                """.formatted(RUNTIME_ROWS),
                (rs, row) -> runtimeState(rs), trunkId)
                .stream().findFirst();
    }

    public Thread project(Thread stored)
    {
        requireNonNull(stored, "stored is null");
        return project(stored, find(stored.id()).orElse(null));
    }

    public List<Thread> projectAll(List<Thread> stored)
    {
        requireNonNull(stored, "stored is null");
        if (stored.isEmpty()) {
            return List.of();
        }
        List<String> ids = stored.stream().map(Thread::id).distinct().toList();
        String placeholders = String.join(", ",
                Collections.nCopies(ids.size(), "?"));
        Map<String, RuntimeState> runtimeById = new HashMap<>();
        jdbc.query("""
                SELECT * FROM (%s) runtime WHERE runtime.id IN (%s)
                """.formatted(RUNTIME_ROWS, placeholders), (RowCallbackHandler) rs ->
                        runtimeById.put(rs.getString("id"), runtimeState(rs)),
                ids.toArray());
        return stored.stream()
                .map(thread -> project(thread, runtimeById.get(thread.id())))
                .toList();
    }

    private static Thread project(Thread stored, RuntimeState runtime)
    {
        if (runtime == null) {
            return stored;
        }
        Instant updatedAt = Instant.ofEpochMilli(Math.max(
                stored.updatedAt().toEpochMilli(), runtime.typedUpdatedAtMs()));
        return new Thread(
                stored.id(), stored.kind(), stored.provider(), null,
                stored.title(), runtime.status(), stored.model(),
                stored.costUsdMilli() + runtime.typedCostUsdMilli(),
                stored.tokensIn() + runtime.typedTokensIn(),
                stored.tokensOut() + runtime.typedTokensOut(),
                stored.createdAt(), updatedAt, null, null,
                stored.flow(), stored.workspaceId(), stored.workModel(),
                stored.parentReviewPassId(), stored.parallelSlots(),
                stored.parentTaskId(), stored.prRef(), stored.description());
    }

    private static RuntimeState runtimeState(ResultSet rs)
            throws SQLException
    {
        return new RuntimeState(
                rs.getString("lifecycle_state"),
                ThreadStatus.valueOf(rs.getString("effective_status")),
                rs.getLong("typed_cost_usd_milli"),
                rs.getLong("typed_tokens_in"),
                rs.getLong("typed_tokens_out"),
                rs.getLong("typed_updated_at_ms"));
    }

    public int count(String workspaceId)
    {
        Integer count = workspaceId == null
                ? jdbc.queryForObject("""
                        SELECT COUNT(*) FROM threads
                        WHERE turn_version = 'V2'
                        """, Integer.class)
                : jdbc.queryForObject("""
                        SELECT COUNT(*) FROM threads
                        WHERE turn_version = 'V2' AND workspace_id = ?
                        """, Integer.class, workspaceId);
        return count == null ? 0 : count;
    }

    public List<String> listIds(
            ThreadStatus status, String workspaceId, int limit)
    {
        requireNonNull(status, "status is null");
        if (limit <= 0) {
            return List.of();
        }
        String workspacePredicate = workspaceId == null
                ? "" : "AND runtime.workspace_id = ?";
        String sql = """
                SELECT runtime.id
                FROM (%s) runtime
                WHERE 1 = 1
                  %s
                  AND runtime.effective_status = ?
                ORDER BY MAX(runtime.stored_updated_at_ms,
                             runtime.typed_updated_at_ms) DESC,
                         runtime.id DESC
                LIMIT ?
                """.formatted(RUNTIME_ROWS, workspacePredicate);
        if (workspaceId == null) {
            return jdbc.query(sql, (rs, row) -> rs.getString("id"),
                    status.name(), limit);
        }
        return jdbc.query(sql, (rs, row) -> rs.getString("id"),
                workspaceId, status.name(), limit);
    }

    public List<String> listIdsUpdatedSince(
            String workspaceId, Instant since)
    {
        requireNonNull(since, "since is null");
        String workspacePredicate = workspaceId == null
                ? "" : "AND runtime.workspace_id = ?";
        String sql = """
                SELECT runtime.id
                FROM (%s) runtime
                WHERE 1 = 1
                  %s
                  AND MAX(runtime.stored_updated_at_ms,
                          runtime.typed_updated_at_ms) >= ?
                ORDER BY MAX(runtime.stored_updated_at_ms,
                             runtime.typed_updated_at_ms) DESC,
                         runtime.id DESC
                """.formatted(RUNTIME_ROWS, workspacePredicate);
        if (workspaceId == null) {
            return jdbc.query(sql, (rs, row) -> rs.getString("id"),
                    since.toEpochMilli());
        }
        return jdbc.query(sql, (rs, row) -> rs.getString("id"),
                workspaceId, since.toEpochMilli());
    }

    public record RuntimeState(
            String lifecycle,
            ThreadStatus status,
            long typedCostUsdMilli,
            long typedTokensIn,
            long typedTokensOut,
            long typedUpdatedAtMs)
    {
        public RuntimeState
        {
            requireText(lifecycle, "lifecycle");
            requireNonNull(status, "status is null");
            if (typedCostUsdMilli < 0 || typedTokensIn < 0
                    || typedTokensOut < 0 || typedUpdatedAtMs < 0) {
                throw new IllegalArgumentException(
                        "typed usage and activity must not be negative");
            }
        }

        public RuntimeState(String lifecycle, ThreadStatus status)
        {
            this(lifecycle, status, 0, 0, 0, 0);
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
