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
                   COALESCE(ticket.stage_id,
                       task_turn.trigger_stage_id) AS stage_id,
                   COALESCE(round.id,
                       feedback.remote_feedback_batch_id) AS review_round_id,
                   owner.kind AS stage_kind,
                   COALESCE(thread_turn.purpose, task_turn.purpose,
                       stage_turn.purpose, review_turn.purpose) AS purpose,
                   COALESCE(thread_turn.attempt, task_turn.attempt,
                       stage_turn.attempt, review_turn.attempt) AS turn_attempt,
                   COALESCE(thread_turn.status, task_turn.status,
                       stage_turn.status, review_turn.status) AS turn_status,
                   ticket.status AS ticket_status,
                   execution.status AS execution_status,
                   task.lifecycle_state AS task_lifecycle,
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
                   COALESCE(execution.infrastructure_attempt, 0)
                       AS infrastructure_attempt,
                   CASE
                     WHEN ticket.owner_kind = 'THREAD_TURN' AND EXISTS (
                       SELECT 1 FROM thread_question question
                       WHERE question.turn_id = thread_turn.id
                         AND question.state = 'OPEN') THEN 'QUESTION'
                     WHEN ticket.owner_kind = 'TASK_TURN' AND EXISTS (
                       SELECT 1 FROM task_question question
                       WHERE question.turn_id = task_turn.id
                         AND question.state = 'OPEN') THEN 'QUESTION'
                     WHEN ticket.owner_kind = 'STAGE_TURN' AND EXISTS (
                       SELECT 1 FROM stage_question question
                       WHERE question.turn_id = stage_turn.id
                         AND question.state = 'OPEN') THEN 'QUESTION'
                     WHEN ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                       AND EXISTS (
                         SELECT 1 FROM review_assignment_question question
                         WHERE question.turn_id = review_turn.id
                           AND question.state = 'OPEN') THEN 'QUESTION'
                     WHEN EXISTS (
                       SELECT 1 FROM permission_request permission
                       WHERE permission.turn_id = ticket.owner_id
                         AND permission.operation_id = ticket.operation_id
                         AND permission.state = 'OPEN') THEN 'PERMISSION'
                     ELSE NULL
                   END AS user_wait_kind,
                   EXISTS (
                       SELECT 1 FROM typed_user_wait_result result
                       WHERE result.operation_id = ticket.operation_id
                         AND result.owner_kind = ticket.owner_kind
                         AND result.turn_id = ticket.owner_id)
                       AS user_wait_recorded,
                   COALESCE((
                       SELECT SUM(item.cost_usd_milli)
                       FROM agent_execution item
                       WHERE item.ticket_id = ticket.id), 0) AS cost_usd_milli,
                   COALESCE((
                       SELECT SUM(item.tokens_in)
                       FROM agent_execution item
                       WHERE item.ticket_id = ticket.id), 0) AS tokens_in,
                   COALESCE((
                       SELECT SUM(item.tokens_out)
                       FROM agent_execution item
                       WHERE item.ticket_id = ticket.id), 0) AS tokens_out
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
            LEFT JOIN stage owner ON owner.id = COALESCE(
                ticket.stage_id, task_turn.trigger_stage_id)
            LEFT JOIN tasks task ON task.id = ticket.task_id
            LEFT JOIN threads trunk ON trunk.id = ticket.trunk_id
            LEFT JOIN review_assignment assignment
              ON assignment.id = review_turn.assignment_id
            LEFT JOIN review_round round ON round.id = assignment.round_id
            LEFT JOIN review_session review ON review.id = round.session_id
            LEFT JOIN remote_feedback_stage_turn_request feedback
              ON feedback.stage_turn_id = stage_turn.id
            LEFT JOIN agent_execution execution ON execution.id = (
                SELECT candidate.id
                FROM agent_execution candidate
                WHERE candidate.ticket_id = ticket.id
                ORDER BY candidate.infrastructure_attempt DESC
                LIMIT 1)
            WHERE ticket.async_family = 'AGENT_TURN'
              AND (
                  thread_turn.id IS NOT NULL
                  OR task_turn.id IS NOT NULL
                  OR (stage_turn.id IS NOT NULL
                      AND owner.task_id = task.id)
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

    public List<AgentRun> listByTrunk(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return query("AND COALESCE(ticket.trunk_id, review.owner_thread_id) = ?",
                trunkId);
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
        int attempt = rs.getInt("turn_attempt");
        long requestedAt = rs.getLong("requested_at_ms");
        Long startedAt = nullableLong(rs, "started_at_ms");
        Long finishedAt = nullableLong(rs, "finished_at_ms");
        String error = rs.getString("error_message");
        String waitKind = rs.getString("user_wait_kind");
        String projectedStatus = status(
                turnStatus, rs.getString("ticket_status"),
                rs.getString("execution_status"),
                rs.getString("task_lifecycle"), waitKind,
                rs.getBoolean("user_wait_recorded"));
        FrozenLaunch launch = launch(rs.getString("launch_input"));
        return new AgentRun(
                ID_PREFIX + rs.getString("ticket_id"),
                rs.getString("task_id"), kind(ownerKind, purpose, stageKind),
                source(stageKind), rs.getString("stage_id"),
                rs.getString("review_round_id"), rs.getString("stage_id"),
                projectedStatus, attempt, null, headline(purpose), null,
                instant(startedAt == null ? requestedAt : startedAt),
                terminal(projectedStatus) && finishedAt != null
                        ? instant(finishedAt) : null,
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                firstText(launch.provider(), rs.getString("provider")),
                launch.model(),
                rs.getLong("cost_usd_milli"), rs.getLong("tokens_in"),
                rs.getLong("tokens_out"),
                rs.getInt("infrastructure_attempt"),
                launch.prompt(), pauseReason(projectedStatus, waitKind),
                terminal(projectedStatus) ? outcome(turnStatus, error) : null);
    }

    private FrozenLaunch launch(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return new FrozenLaunch(raw, null, null);
        }
        try {
            JsonNode value = json.readTree(raw);
            return new FrozenLaunch(
                    value.path("prompt").asText(
                            value.path("userMessage").asText(raw)),
                    nullableText(value.path("provider").asText(null)),
                    nullableText(value.path("model").asText(null)));
        }
        catch (Exception ignored) {
            return new FrozenLaunch(raw, null, null);
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
        if ("BRANCH_CONFLICT_REPAIR".equals(normalized)) {
            return AgentRun.KIND_BRANCH_GUARD;
        }
        if ("ADDRESS_REMOTE_FEEDBACK".equals(normalized)) {
            return AgentRun.KIND_REVIEW_ROUND;
        }
        if (normalized.contains("CI")) {
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

    private static String status(
            String turn, String ticket, String execution,
            String taskLifecycle, String userWaitKind,
            boolean userWaitRecorded)
    {
        String terminal = terminalStatus(turn, ticket);
        if (terminal != null
                && !(userWaitRecorded && userWaitKind != null)) {
            return terminal;
        }
        if ("PAUSING".equals(taskLifecycle) || "PAUSED".equals(taskLifecycle)) {
            return AgentRun.STATUS_PAUSED;
        }
        if (userWaitKind != null) {
            return AgentRun.STATUS_AWAITING_GATE;
        }
        if ("STARTING".equals(execution) || "RUNNING".equals(execution)) {
            return AgentRun.STATUS_RUNNING;
        }
        return switch (text(ticket)) {
            case "REQUESTED", "RETRY_WAIT", "RECONCILE_WAIT" ->
                    AgentRun.STATUS_QUEUED;
            case "RESULT_PENDING", "CLAIMED", "RUNNING", "DELIVERING" ->
                    AgentRun.STATUS_RUNNING;
            case "FAILED" -> AgentRun.STATUS_FAILED;
            case "CANCELED" -> AgentRun.STATUS_CANCELLED;
            default -> switch (text(turn)) {
            case "REQUESTED", "QUEUED" -> AgentRun.STATUS_QUEUED;
            case "CLAIMED", "RUNNING" -> AgentRun.STATUS_RUNNING;
            case "SUCCEEDED" -> AgentRun.STATUS_SUCCEEDED;
            case "FAILED" -> AgentRun.STATUS_FAILED;
            default -> AgentRun.STATUS_CANCELLED;
            };
        };
    }

    private static String terminalStatus(String turn, String ticket)
    {
        if (!"SUCCEEDED".equals(ticket)
                && !"FAILED".equals(ticket)
                && !"CANCELED".equals(ticket)) {
            return null;
        }
        return switch (text(turn)) {
            case "SUCCEEDED" -> AgentRun.STATUS_SUCCEEDED;
            case "FAILED" -> AgentRun.STATUS_FAILED;
            case "CANCELED", "SUPERSEDED" -> AgentRun.STATUS_CANCELLED;
            default -> switch (ticket) {
                case "FAILED" -> AgentRun.STATUS_FAILED;
                case "CANCELED" -> AgentRun.STATUS_CANCELLED;
                default -> AgentRun.STATUS_SUCCEEDED;
            };
        };
    }

    private static String pauseReason(String status, String userWaitKind)
    {
        if (AgentRun.STATUS_PAUSED.equals(status)) {
            return "Task is paused";
        }
        return switch (text(userWaitKind)) {
            case "QUESTION" -> "Waiting for user";
            case "PERMISSION" -> "Waiting for permission";
            default -> null;
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

    static String ticketId(String sessionId)
    {
        if (!isV2Id(sessionId)) {
            throw new IllegalArgumentException("not a V2 run id: " + sessionId);
        }
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

    private static String nullableText(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstText(String preferred, String fallback)
    {
        return preferred == null ? fallback : preferred;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record FrozenLaunch(String prompt, String provider, String model) {}
}
