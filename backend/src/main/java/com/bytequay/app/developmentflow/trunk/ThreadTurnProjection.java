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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Read-only compatibility projection over physically typed ThreadTurns. */
@Repository
public class ThreadTurnProjection
{
    private static final int MAX_TURNS = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ThreadTurnProjection(JdbcTemplate jdbc, ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
    }

    public List<TurnView> turns(String trunkId, int limit)
    {
        requireText(trunkId, "trunkId");
        if (limit <= 0) {
            return List.of();
        }
        String pendingPlanning = planningRuntimeExists() ? """
                UNION ALL
                SELECT operation.reserved_thread_turn_id,
                       json_extract(operation.launch_intent, '$.purpose'),
                       CASE operation.status
                         WHEN 'SUCCEEDED' THEN 'REQUESTED'
                         ELSE operation.status END,
                       operation.operation_id,
                       operation.semantic_attempt,
                       json_extract(operation.launch_intent, '$.transport'),
                       operation.requested_at_ms, NULL,
                       CASE WHEN operation.status = 'SUCCEEDED'
                         THEN NULL ELSE operation.completed_at_ms END,
                       CASE WHEN operation.status IN ('REQUESTED', 'SUCCEEDED')
                         THEN NULL
                         ELSE COALESCE(operation.error_message,
                           'Planning-base refresh ' || lower(operation.status)
                           || ' before this turn could start') END,
                       operation.actor,
                       json_extract(operation.launch_intent, '$.userMessage'),
                       ticket.id, ticket.status,
                       ticket.cancel_requested_at_ms,
                       request.returned_trunk_version
                FROM planning_base_refresh_operation operation
                JOIN trunk_planning_base_request_receipt request
                  ON request.planning_operation_id = operation.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.trunk_id = ?
                  AND operation.launched_thread_turn_id IS NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM thread_turn turn
                    WHERE turn.id = operation.reserved_thread_turn_id)
                """ : "";
        List<Object> arguments = new ArrayList<>();
        arguments.add(trunkId);
        if (!pendingPlanning.isEmpty()) {
            arguments.add(trunkId);
        }
        arguments.add(Math.min(limit, MAX_TURNS));
        return jdbc.query("""
                WITH projected AS (
                    SELECT turn.id AS turn_id, turn.purpose, turn.status,
                           turn.operation_id, turn.attempt, turn.delivery_lane,
                           turn.requested_at_ms, turn.started_at_ms,
                           turn.finished_at_ms, turn.error_message,
                           request.actor, message.body AS user_message,
                           ticket.id AS ticket_id,
                           ticket.status AS ticket_status,
                           ticket.cancel_requested_at_ms,
                           request.returned_trunk_version AS order_version
                    FROM thread_turn turn
                    JOIN trunk_thread_turn_request_receipt request
                      ON request.turn_id = turn.id
                    JOIN dispatch_ticket ticket
                      ON ticket.owner_kind = 'THREAD_TURN'
                     AND ticket.owner_id = turn.id
                     AND ticket.operation_id = turn.operation_id
                    JOIN thread_message message
                      ON message.turn_id = turn.id AND message.seq = 1
                    WHERE turn.trunk_id = ?
                      AND turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                    %s
                )
                SELECT turn_id, purpose, status, operation_id, attempt,
                       delivery_lane, requested_at_ms, started_at_ms,
                       finished_at_ms, error_message, actor, user_message,
                       ticket_id, ticket_status, cancel_requested_at_ms
                FROM projected
                ORDER BY order_version DESC
                LIMIT ?
                """.formatted(pendingPlanning),
                (rs, row) -> new TurnView(
                        rs.getString("turn_id"), rs.getString("purpose"),
                        rs.getString("status"), rs.getString("operation_id"),
                        rs.getInt("attempt"), rs.getString("delivery_lane"),
                        rs.getString("actor"), rs.getString("user_message"),
                        rs.getString("ticket_id"),
                        rs.getString("ticket_status"),
                        instant(rs.getObject("requested_at_ms")),
                        instant(rs.getObject("started_at_ms")),
                        instant(rs.getObject("finished_at_ms")),
                        instant(rs.getObject("cancel_requested_at_ms")),
                        rs.getString("error_message")),
                arguments.toArray());
    }

    /** Tickets whose external execution can still be interrupted. */
    public List<String> cancelableTicketIds(String trunkId)
    {
        requireText(trunkId, "trunkId");
        if (planningRuntimeExists()) {
            return activeTicketIds(trunkId, false);
        }
        return jdbc.query("""
                SELECT ticket.id
                FROM dispatch_ticket ticket
                JOIN thread_turn turn ON turn.id = ticket.owner_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                WHERE turn.trunk_id = ?
                  AND turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                  AND ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.operation_id = turn.operation_id
                  AND ticket.status IN (
                      'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                      'CLAIMED', 'RUNNING')
                ORDER BY request.returned_trunk_version
                """, (rs, row) -> rs.getString("id"), trunkId);
    }

    /** Tickets with an execution that a user interrupt can stop immediately. */
    public List<String> interruptibleTicketIds(String trunkId)
    {
        requireText(trunkId, "trunkId");
        if (planningRuntimeExists()) {
            return activeTicketIds(trunkId, true);
        }
        return jdbc.query("""
                SELECT ticket.id
                FROM dispatch_ticket ticket
                JOIN thread_turn turn ON turn.id = ticket.owner_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                WHERE turn.trunk_id = ?
                  AND turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                  AND ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.operation_id = turn.operation_id
                  AND ticket.status IN ('CLAIMED', 'RUNNING')
                ORDER BY request.returned_trunk_version
                """, (rs, row) -> rs.getString("id"), trunkId);
    }

    private List<String> activeTicketIds(String trunkId, boolean runningOnly)
    {
        String statuses = runningOnly
                ? "'CLAIMED', 'RUNNING'"
                : "'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', "
                        + "'CLAIMED', 'RUNNING'";
        return jdbc.query("""
                WITH projected AS (
                    SELECT ticket.id,
                           request.returned_trunk_version AS order_version
                    FROM dispatch_ticket ticket
                    JOIN thread_turn turn ON turn.id = ticket.owner_id
                    JOIN trunk_thread_turn_request_receipt request
                      ON request.turn_id = turn.id
                    WHERE turn.trunk_id = ?
                      AND turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                      AND ticket.owner_kind = 'THREAD_TURN'
                      AND ticket.operation_id = turn.operation_id
                      AND ticket.status IN (%s)
                    UNION ALL
                    SELECT ticket.id, request.returned_trunk_version
                    FROM planning_base_refresh_operation operation
                    JOIN trunk_planning_base_request_receipt request
                      ON request.planning_operation_id = operation.id
                    JOIN dispatch_ticket ticket
                      ON ticket.id = operation.dispatch_ticket_id
                    WHERE operation.trunk_id = ?
                      AND operation.launched_thread_turn_id IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM thread_turn turn
                        WHERE turn.id = operation.reserved_thread_turn_id)
                      AND ticket.status IN (%s)
                )
                SELECT id FROM projected ORDER BY order_version
                """.formatted(statuses, statuses),
                (rs, row) -> rs.getString("id"), trunkId, trunkId);
    }

    /** Existing UI history shape, projected without nullable-scope inference. */
    public List<ThreadMessage> history(String trunkId)
    {
        requireText(trunkId, "trunkId");
        List<Object> arguments = new ArrayList<>();
        arguments.add(trunkId);
        String pendingPlanning = "";
        if (planningRuntimeExists()) {
            pendingPlanning = """
                    UNION ALL
                    SELECT operation.id || ':request', 'user',
                           json_extract(operation.launch_intent, '$.userMessage'),
                           'text', NULL, NULL, operation.requested_at_ms,
                           request.returned_trunk_version, 1
                    FROM planning_base_refresh_operation operation
                    JOIN trunk_planning_base_request_receipt request
                      ON request.planning_operation_id = operation.id
                    WHERE operation.trunk_id = ?
                      AND operation.launched_thread_turn_id IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM thread_turn turn
                        WHERE turn.id = operation.reserved_thread_turn_id)
                    UNION ALL
                    SELECT operation.id || ':result', 'assistant',
                           'Planning-base refresh ' || lower(operation.status)
                             || ' before this turn could start'
                             || CASE WHEN operation.error_message IS NULL
                               THEN '.' ELSE ': ' || operation.error_message END,
                           'text', NULL, NULL, operation.completed_at_ms,
                           result.returned_trunk_version, 2
                    FROM planning_base_refresh_operation operation
                    JOIN trunk_planning_base_result_receipt result
                      ON result.planning_operation_id = operation.id
                    WHERE operation.trunk_id = ?
                      AND operation.status IN (
                        'FAILED', 'CANCELED', 'SUPERSEDED')
                      AND operation.launched_thread_turn_id IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM thread_turn turn
                        WHERE turn.id = operation.reserved_thread_turn_id)
                    """;
            arguments.add(trunkId);
            arguments.add(trunkId);
        }
        String taskOutcomes = "";
        if (taskOutcomeRuntimeExists()) {
            taskOutcomes = """
                    UNION ALL
                    SELECT inbox.id, 'assistant',
                           CASE outcome.summary_state
                             WHEN 'BRAIN_GENERATED' THEN outcome.summary_text
                             ELSE inbox.fallback_summary_text END,
                           'task_summary', outcome.task_id, task.seq,
                           inbox.delivered_at_ms,
                           inbox.returned_trunk_version, 0
                    FROM trunk_outcome_inbox inbox
                    JOIN task_outcome outcome
                      ON outcome.id = inbox.task_outcome_id
                    JOIN tasks task ON task.id = outcome.task_id
                    WHERE inbox.trunk_id = ? AND inbox.status = 'DELIVERED'
                    """;
            arguments.add(trunkId);
        }
        return jdbc.query("""
                WITH projected AS (
                    SELECT message.id, message.role, message.body,
                           'text' AS message_type,
                           NULL AS task_id, NULL AS task_seq,
                           message.created_at_ms,
                           CASE message.seq
                             WHEN 1 THEN request.returned_trunk_version
                             ELSE result.returned_trunk_version END
                               AS order_version,
                           message.seq AS item_seq
                    FROM thread_message message
                    JOIN thread_turn turn ON turn.id = message.turn_id
                    JOIN trunk_thread_turn_request_receipt request
                      ON request.turn_id = turn.id
                    LEFT JOIN trunk_thread_turn_result_receipt result
                      ON result.turn_id = turn.id
                    WHERE turn.trunk_id = ?
                      AND turn.purpose <> 'TASK_COMPLETION_SUMMARY'
                    %s
                    %s
                )
                SELECT id, role, body, message_type, task_id, task_seq,
                       created_at_ms
                FROM projected
                ORDER BY order_version, item_seq, id
                """.formatted(pendingPlanning, taskOutcomes),
                (rs, row) -> new ThreadMessage(
                        rs.getString("id"), trunkId, null, row + 1L,
                        rs.getString("role"), rs.getString("message_type"),
                        contentJson(
                                rs.getString("message_type"),
                                rs.getString("body"), rs.getString("task_id"),
                                (Number) rs.getObject("task_seq")),
                        null, null, null, null,
                        Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                        null, ThreadScope.TRUNK), arguments.toArray());
    }

    private boolean planningRuntimeExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'planning_base_refresh_operation'
                """, Integer.class);
        return count != null && count == 1;
    }

    private boolean taskOutcomeRuntimeExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pragma_table_info('trunk_outcome_inbox')
                WHERE name = 'fallback_summary_text'
                """, Integer.class);
        return count != null && count == 1;
    }

    private String contentJson(
            String type, String text, String taskId, Number taskSeq)
    {
        try {
            if (!"task_summary".equals(type)) {
                return json.writeValueAsString(Map.of("text", text));
            }
            return json.writeValueAsString(Map.of(
                    "text", text,
                    "taskId", taskId,
                    "taskSeq", taskSeq.longValue()));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not project ThreadTurn message", e);
        }
    }

    private static Instant instant(Object value)
    {
        return value == null ? null
                : Instant.ofEpochMilli(((Number) value).longValue());
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record TurnView(
            String turnId,
            String purpose,
            String status,
            String operationId,
            int attempt,
            String deliveryLane,
            String actor,
            String userMessage,
            String ticketId,
            String ticketStatus,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant cancelRequestedAt,
            String error)
    {}
}
