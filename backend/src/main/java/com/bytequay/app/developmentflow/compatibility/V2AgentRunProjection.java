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

import com.bytequay.app.domain.AgentRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only AgentRun compatibility shape over typed V2 Turn executions. */
@Component
public final class V2AgentRunProjection
{
    private static final String ID_PREFIX = "v2-ticket:";

    private static final String QUERY = """
            SELECT ticket.id AS ticket_id, ticket.owner_kind,
                   COALESCE(ticket.workspace_id, review.workspace_id,
                       trunk.workspace_id) AS workspace_id,
                   COALESCE(ticket.trunk_id, review.owner_thread_id) AS trunk_id,
                   COALESCE(ticket.task_id, review.owner_task_id) AS task_id,
                   ticket.stage_id,
                   round.id AS review_round_id,
                   owner.kind AS stage_kind,
                   COALESCE(thread_turn.purpose, task_turn.purpose,
                       stage_turn.purpose, review_turn.purpose) AS purpose,
                   COALESCE(thread_turn.status, task_turn.status,
                       stage_turn.status, review_turn.status) AS turn_status,
                   COALESCE(thread_turn.launch_input, task_turn.launch_input,
                       stage_turn.launch_input,
                       review_turn.launch_input) AS launch_input,
                   COALESCE(thread_turn.requested_at_ms,
                       task_turn.requested_at_ms, stage_turn.requested_at_ms,
                       review_turn.requested_at_ms) AS requested_at_ms,
                   COALESCE(thread_turn.started_at_ms, task_turn.started_at_ms,
                       stage_turn.started_at_ms, review_turn.started_at_ms,
                       ticket.started_at_ms, execution.started_at_ms)
                       AS started_at_ms,
                   COALESCE(thread_turn.finished_at_ms,
                       task_turn.finished_at_ms, stage_turn.finished_at_ms,
                       review_turn.finished_at_ms, ticket.completed_at_ms,
                       execution.finished_at_ms) AS finished_at_ms,
                   COALESCE(thread_turn.error_message,
                       task_turn.error_message, stage_turn.error_message,
                       review_turn.error_message, ticket.last_error,
                       execution.error_message) AS error_message,
                   execution.provider,
                   COALESCE(accounting.attempts, 0) AS attempts,
                   COALESCE(accounting.cost_usd_milli, 0) AS cost_usd_milli,
                   COALESCE(accounting.tokens_in, 0) AS tokens_in,
                   COALESCE(accounting.tokens_out, 0) AS tokens_out
            FROM dispatch_ticket ticket
            LEFT JOIN thread_turn
              ON ticket.owner_kind = 'THREAD_TURN'
             AND thread_turn.id = ticket.owner_id
             AND thread_turn.operation_id = ticket.operation_id
            LEFT JOIN task_turn
              ON ticket.owner_kind = 'TASK_TURN'
             AND task_turn.id = ticket.owner_id
             AND task_turn.operation_id = ticket.operation_id
            LEFT JOIN stage_turn
              ON ticket.owner_kind = 'STAGE_TURN'
             AND stage_turn.id = ticket.owner_id
             AND stage_turn.operation_id = ticket.operation_id
            LEFT JOIN review_assignment_turn review_turn
              ON ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
             AND review_turn.id = ticket.owner_id
             AND review_turn.operation_id = ticket.operation_id
            LEFT JOIN stage owner ON owner.id = ticket.stage_id
            LEFT JOIN tasks task ON task.id = ticket.task_id
            LEFT JOIN threads trunk ON trunk.id = ticket.trunk_id
            LEFT JOIN review_assignment assignment
              ON assignment.id = review_turn.assignment_id
            LEFT JOIN review_round round ON round.id = assignment.round_id
            LEFT JOIN review_session review ON review.id = round.session_id
            LEFT JOIN agent_execution execution ON execution.id = (
                SELECT candidate.id
                FROM agent_execution candidate
                WHERE candidate.ticket_id = ticket.id
                ORDER BY candidate.infrastructure_attempt DESC
                LIMIT 1)
            LEFT JOIN (
                SELECT ticket_id, COUNT(*) AS attempts,
                       SUM(cost_usd_milli) AS cost_usd_milli,
                       SUM(tokens_in) AS tokens_in,
                       SUM(tokens_out) AS tokens_out
                FROM agent_execution
                GROUP BY ticket_id) accounting
              ON accounting.ticket_id = ticket.id
            WHERE ticket.async_family = 'AGENT_TURN'
              AND (
                  (thread_turn.id IS NOT NULL AND trunk.turn_version = 'V2')
                  OR (task_turn.id IS NOT NULL
                      AND task.workflow_version = 'V2')
                  OR (stage_turn.id IS NOT NULL
                      AND owner.task_id = task.id
                      AND task.workflow_version = 'V2')
                  OR review_turn.id IS NOT NULL)
              %s
            ORDER BY ticket.created_at_ms DESC, ticket.id DESC
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public V2AgentRunProjection(JdbcTemplate jdbc, ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
    }

    public List<AgentRun> listByWorkspace(String workspaceId)
    {
        requireText(workspaceId, "workspaceId");
        return query("AND COALESCE(ticket.workspace_id, review.workspace_id, "
                + "trunk.workspace_id) = ?", workspaceId);
    }

    public List<AgentRun> listByTask(String taskId)
    {
        requireText(taskId, "taskId");
        return query("AND COALESCE(ticket.task_id, review.owner_task_id) = ?",
                taskId);
    }

    public Optional<AgentRun> findById(String sessionId)
    {
        if (!isV2Id(sessionId)) {
            return Optional.empty();
        }
        return query("AND ticket.id = ?", ticketId(sessionId)).stream()
                .findFirst();
    }

    public static boolean isV2Id(String id)
    {
        return id != null && id.startsWith(ID_PREFIX)
                && id.length() > ID_PREFIX.length();
    }

    private List<AgentRun> query(String filter, Object... arguments)
    {
        return jdbc.query(QUERY.formatted(filter), this::run, arguments);
    }

    private AgentRun run(ResultSet rs, int row)
            throws SQLException
    {
        String turnStatus = rs.getString("turn_status");
        String purpose = rs.getString("purpose");
        String stageKind = rs.getString("stage_kind");
        String ownerKind = rs.getString("owner_kind");
        int attempts = rs.getInt("attempts");
        long requestedAt = rs.getLong("requested_at_ms");
        Long startedAt = nullableLong(rs, "started_at_ms");
        Long finishedAt = nullableLong(rs, "finished_at_ms");
        String error = rs.getString("error_message");
        String projectedStatus = status(turnStatus);
        return new AgentRun(
                ID_PREFIX + rs.getString("ticket_id"),
                rs.getString("task_id"), kind(ownerKind, purpose, stageKind),
                source(stageKind), rs.getString("stage_id"),
                rs.getString("review_round_id"), rs.getString("stage_id"),
                projectedStatus, attempts, null, headline(purpose), null,
                instant(startedAt == null ? requestedAt : startedAt),
                terminal(projectedStatus) && finishedAt != null
                        ? instant(finishedAt) : null,
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("provider"), null,
                rs.getLong("cost_usd_milli"), rs.getLong("tokens_in"),
                rs.getLong("tokens_out"), attempts == 0 ? 0 : 1,
                launchInput(rs.getString("launch_input")), null,
                outcome(turnStatus, error));
    }

    private String launchInput(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            JsonNode value = json.readTree(raw);
            return value.path("prompt").asText(
                    value.path("userMessage").asText(raw));
        }
        catch (Exception ignored) {
            return raw;
        }
    }

    private static String kind(
            String ownerKind, String purpose, String stageKind)
    {
        if ("THREAD_TURN".equals(ownerKind)) {
            return AgentRun.KIND_PLAN;
        }
        if ("REVIEW_ASSIGNMENT_TURN".equals(ownerKind)) {
            return AgentRun.KIND_PANEL_REVIEW;
        }
        String normalized = text(purpose).toUpperCase(Locale.ROOT);
        if ("PLAN".equals(stageKind) || normalized.contains("PLAN")) {
            return AgentRun.KIND_PLAN;
        }
        if (normalized.contains("CI") || normalized.contains("REPAIR")) {
            return AgentRun.KIND_CI_FIX;
        }
        if (normalized.contains("REVIEW")) {
            return AgentRun.KIND_REVIEW;
        }
        return AgentRun.KIND_DEV;
    }

    private static String source(String stageKind)
    {
        if ("REMOTE_DEVELOPMENT".equals(stageKind)) {
            return AgentRun.SOURCE_REMOTE;
        }
        return stageKind == null ? null : AgentRun.SOURCE_LOCAL;
    }

    private static String status(String value)
    {
        return switch (text(value)) {
            case "REQUESTED", "QUEUED" -> AgentRun.STATUS_QUEUED;
            case "CLAIMED", "RUNNING" -> AgentRun.STATUS_RUNNING;
            case "SUCCEEDED" -> AgentRun.STATUS_SUCCEEDED;
            case "FAILED" -> AgentRun.STATUS_FAILED;
            default -> AgentRun.STATUS_CANCELLED;
        };
    }

    private static String outcome(String turnStatus, String error)
    {
        if (error != null && !error.isBlank()) {
            return error;
        }
        return switch (text(turnStatus)) {
            case "SUCCEEDED" -> "completed";
            case "FAILED" -> "failed";
            case "SUPERSEDED" -> "superseded";
            case "CANCELED" -> "cancelled";
            default -> null;
        };
    }

    private static String headline(String purpose)
    {
        String value = text(purpose).replace('_', ' ').toLowerCase(Locale.ROOT);
        return value.isBlank() ? "Agent turn" : Character.toUpperCase(value.charAt(0))
                + value.substring(1);
    }

    private static boolean terminal(String status)
    {
        return AgentRun.STATUS_SUCCEEDED.equals(status)
                || AgentRun.STATUS_FAILED.equals(status)
                || AgentRun.STATUS_CANCELLED.equals(status);
    }

    private static String ticketId(String sessionId)
    {
        return sessionId.substring(ID_PREFIX.length());
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(long value)
    {
        return Instant.ofEpochMilli(value);
    }

    private static String text(String value)
    {
        return value == null ? "" : value;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
