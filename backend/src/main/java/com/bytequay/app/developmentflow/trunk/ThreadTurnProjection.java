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

import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.domain.TrunkTraceEvent;
import com.bytequay.app.service.threads.CliStreamParser;
import com.bytequay.app.service.threads.CodexJsonParser;
import com.bytequay.app.service.threads.MessageAttachments;
import com.bytequay.app.service.threads.StreamJsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Read-only compatibility projection over physically typed ThreadTurns. */
@Repository
public class ThreadTurnProjection
{
    private static final int MAX_TURNS = 500;
    private static final int MAX_EVENTS = 200;
    private static final int MAX_LOG_ROWS = 100;
    private static final int MAX_TRACE_REQUESTS = 100;
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

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
                SELECT operation.trunk_id,
                       operation.reserved_thread_turn_id,
                       json_extract(operation.launch_intent, '$.purpose'),
                       CASE
                         WHEN operation.launch_disposition = 'SUPPRESSED'
                           THEN 'CANCELED'
                         WHEN operation.status = 'SUCCEEDED' THEN 'REQUESTED'
                         ELSE operation.status END,
                       operation.operation_id,
                       operation.semantic_attempt,
                       json_extract(operation.launch_intent, '$.transport'),
                       operation.requested_at_ms, NULL,
                       CASE
                         WHEN operation.launch_disposition = 'SUPPRESSED'
                           THEN operation.launched_at_ms
                         WHEN operation.status = 'SUCCEEDED' THEN NULL
                         ELSE operation.completed_at_ms END,
                       CASE
                         WHEN operation.launch_disposition = 'SUPPRESSED'
                           THEN operation.launch_disposition_reason
                         WHEN operation.status IN ('REQUESTED', 'SUCCEEDED') THEN NULL
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
                    SELECT turn.trunk_id, turn.id AS turn_id, turn.purpose, turn.status,
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
                    %s
                )
                SELECT trunk_id, turn_id, purpose, status, operation_id, attempt,
                       delivery_lane, requested_at_ms, started_at_ms,
                       finished_at_ms, error_message, actor, user_message,
                       ticket_id, ticket_status, cancel_requested_at_ms
                FROM projected
                ORDER BY order_version DESC
                LIMIT ?
                """.formatted(pendingPlanning),
                ThreadTurnProjection::turnView,
                arguments.toArray());
    }

    /** Physically typed active Trunk Turns across all V2 Trunks, oldest first. */
    public List<TurnView> activeTurns(int limit)
    {
        if (limit <= 0) {
            return List.of();
        }
        return jdbc.query("""
                SELECT turn.trunk_id, turn.id AS turn_id, turn.purpose,
                       turn.status, turn.operation_id, turn.attempt,
                       turn.delivery_lane, turn.requested_at_ms,
                       turn.started_at_ms, turn.finished_at_ms,
                       turn.error_message, request.actor,
                       message.body AS user_message,
                       ticket.id AS ticket_id, ticket.status AS ticket_status,
                       ticket.cancel_requested_at_ms
                FROM thread_turn turn
                JOIN threads trunk
                  ON trunk.id = turn.trunk_id AND trunk.turn_version = 'V2'
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'THREAD_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                 AND ticket.trunk_id = turn.trunk_id
                JOIN thread_message message
                  ON message.turn_id = turn.id AND message.seq = 1
                WHERE turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                ORDER BY turn.requested_at_ms, turn.id
                LIMIT ?
                """, ThreadTurnProjection::turnView,
                Math.min(limit, MAX_TURNS));
    }

    /** Stable compatibility events derived only from typed Turn/ticket facts. */
    public List<ThreadTurnEvent> turnEvents(String trunkId)
    {
        requireText(trunkId, "trunkId");
        List<ThreadTurnEvent> events = new ArrayList<>();
        for (TurnView turn : physicalTurns(trunkId, MAX_TURNS)) {
            events.add(event(turn, "queued", ThreadTurnEventType.TURN_QUEUED,
                    turn.requestedAt(), "V2 ThreadTurn queued"));
            if (turn.startedAt() != null) {
                events.add(event(turn, "started", ThreadTurnEventType.TURN_STARTED,
                        turn.startedAt(), "V2 ThreadTurn started"));
            }
            if (turn.finishedAt() != null) {
                ThreadTurnEventType type = switch (turn.status()) {
                    case "SUCCEEDED" -> ThreadTurnEventType.TURN_FINISHED;
                    case "CANCELED" -> ThreadTurnEventType.TURN_CANCELLED;
                    case "FAILED", "SUPERSEDED" -> ThreadTurnEventType.TURN_FAILED;
                    default -> null;
                };
                if (type != null) {
                    events.add(event(
                            turn, "finished", type, turn.finishedAt(),
                            turn.error() == null ? "V2 ThreadTurn "
                                    + turn.status().toLowerCase(Locale.ROOT)
                                    : turn.error()));
                }
            }
            else if (List.of("REQUESTED", "RETRY_WAIT", "RECONCILE_WAIT")
                    .contains(turn.ticketStatus())) {
                events.add(event(
                        turn, "capacity", ThreadTurnEventType.WAITING_FOR_CAPACITY,
                        turn.requestedAt(), "Waiting for V2 execution capacity ("
                                + turn.ticketStatus() + ")"));
            }
        }
        return events.stream()
                .sorted(Comparator.comparing(ThreadTurnEvent::createdAt)
                        .thenComparing(ThreadTurnEvent::id).reversed())
                .limit(MAX_EVENTS)
                .toList();
    }

    public long latestLogRow(String trunkId)
    {
        requireText(trunkId, "trunkId");
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(log.rowid), 0)
                FROM agent_execution_log log
                JOIN agent_execution execution ON execution.id = log.execution_id
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                JOIN thread_turn turn
                  ON turn.id = ticket.owner_id
                 AND turn.operation_id = ticket.operation_id
                WHERE ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.trunk_id = ? AND turn.trunk_id = ?
                """, Long.class, trunkId, trunkId);
        return value == null ? 0 : value;
    }

    /** Newly committed provider events for exact physical ThreadTurn tickets. */
    public List<LogEvent> logEventsAfter(String trunkId, long cursor)
    {
        requireText(trunkId, "trunkId");
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor is negative");
        }
        List<LogEvent> result = new ArrayList<>();
        for (LogFacts row : jdbc.query("""
                SELECT log.rowid AS row_id, log.payload, log.created_at_ms,
                       execution.provider
                FROM agent_execution_log log
                JOIN agent_execution execution ON execution.id = log.execution_id
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                JOIN thread_turn turn
                  ON turn.id = ticket.owner_id
                 AND turn.operation_id = ticket.operation_id
                WHERE ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.trunk_id = ? AND turn.trunk_id = ?
                  AND log.rowid > ?
                ORDER BY log.rowid
                LIMIT ?
                """, (rs, row) -> new LogFacts(
                        rs.getLong("row_id"), rs.getString("payload"),
                        rs.getLong("created_at_ms"), rs.getString("provider")),
                trunkId, trunkId, cursor, MAX_LOG_ROWS)) {
            result.add(new LogEvent(row.rowId(), streamEvents(row)));
        }
        return List.copyOf(result);
    }

    /**
     * Durable provider trace for only the typed request messages in the
     * caller's current conversation window. Trace rows retain their physical
     * execution/log identity and never borrow a {@link ThreadMessage} seq.
     */
    public List<TrunkTraceEvent> traceEvents(
            String trunkId, List<String> requestMessageIds)
    {
        requireText(trunkId, "trunkId");
        requireNonNull(requestMessageIds, "requestMessageIds is null");
        List<String> requested = requestMessageIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > MAX_TRACE_REQUESTS) {
            throw new IllegalArgumentException(
                    "too many requestMessageIds: " + requested.size());
        }
        requested.forEach(id -> requireText(id, "requestMessageId"));

        String projectedRequestId = planningRuntimeExists() ? """
                CASE
                  WHEN turn.planning_operation_id IS NOT NULL
                    THEN turn.id || ':request'
                  ELSE request_message.id END
                """ : "request_message.id";
        String placeholders = String.join(", ",
                Collections.nCopies(requested.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(trunkId);
        arguments.add(trunkId);
        arguments.addAll(requested);
        List<TraceLogFacts> rows = jdbc.query("""
                SELECT turn.id AS turn_id,
                       %s AS request_message_id,
                       execution.id AS execution_id,
                       log.seq AS log_seq,
                       log.payload, log.created_at_ms, execution.provider,
                       result.terminal_status,
                       result.assistant_message_id
                FROM agent_execution_log log
                JOIN agent_execution execution
                  ON execution.id = log.execution_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = execution.ticket_id
                JOIN thread_turn turn
                  ON turn.id = ticket.owner_id
                 AND turn.operation_id = ticket.operation_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN thread_message request_message
                  ON request_message.turn_id = turn.id
                 AND request_message.seq = 1
                LEFT JOIN trunk_thread_turn_result_receipt result
                  ON result.turn_id = turn.id
                WHERE ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.trunk_id = ?
                  AND turn.trunk_id = ?
                  AND %s IN (%s)
                ORDER BY request.returned_trunk_version,
                         execution.infrastructure_attempt,
                         execution.id, log.seq
                """.formatted(
                        projectedRequestId, projectedRequestId, placeholders),
                (rs, row) -> new TraceLogFacts(
                        rs.getString("turn_id"),
                        rs.getString("request_message_id"),
                        rs.getString("execution_id"),
                        rs.getLong("log_seq"),
                        rs.getString("payload"),
                        rs.getLong("created_at_ms"),
                        rs.getString("provider"),
                        rs.getString("terminal_status"),
                        rs.getString("assistant_message_id")),
                arguments.toArray());

        List<TrunkTraceEvent> result = new ArrayList<>();
        for (TraceLogFacts row : rows) {
            List<StreamEvent> events = streamEvents(new LogFacts(
                    1, row.payload(), row.createdAtMs(), row.provider()));
            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                traceEvent(trunkId, row, eventIndex, events.get(eventIndex))
                        .ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    public DeletionState deletionState(String trunkId)
    {
        requireText(trunkId, "trunkId");
        List<DeletionState> rows = jdbc.query("""
                SELECT trunk_id, lifecycle_state, aggregate_version,
                       nonterminal_task_count, incomplete_cleanup_count,
                       open_wait_count, live_turn_count, live_ticket_count,
                       live_execution_count, live_operation_count,
                       incomplete_stage_count, live_lease_count
                FROM v2_trunk_purge_state_v269
                WHERE trunk_id = ?
                """, (rs, row) -> new DeletionState(
                        rs.getString("trunk_id"),
                        rs.getString("lifecycle_state"),
                        rs.getLong("aggregate_version"),
                        rs.getInt("nonterminal_task_count"),
                        rs.getInt("incomplete_cleanup_count"),
                        rs.getInt("open_wait_count"),
                        rs.getInt("live_turn_count"),
                        rs.getInt("live_ticket_count"),
                        rs.getInt("live_execution_count"),
                        rs.getInt("live_operation_count"),
                        rs.getInt("incomplete_stage_count"),
                        rs.getInt("live_lease_count")), trunkId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("no V2 Trunk: " + trunkId);
        }
        return rows.getFirst();
    }

    /** Tickets whose external execution can still be interrupted. */
    public List<String> cancelableTicketIds(String trunkId)
    {
        requireText(trunkId, "trunkId");
        if (planningRuntimeExists()) {
            return activeTicketIds(trunkId);
        }
        return jdbc.query("""
                SELECT ticket.id
                FROM dispatch_ticket ticket
                JOIN thread_turn turn ON turn.id = ticket.owner_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                WHERE turn.trunk_id = ?
                  AND ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.operation_id = turn.operation_id
                  AND ticket.status IN (
                      'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                      'CLAIMED', 'RUNNING')
                ORDER BY request.returned_trunk_version
                """, (rs, row) -> rs.getString("id"), trunkId);
    }

    /** Running exact Trunk Turn, otherwise newest work or pending launch. */
    public Optional<String> latestCancelableTurnId(String trunkId)
    {
        requireText(trunkId, "trunkId");
        String pendingPlanning = planningRuntimeExists() ? """
                UNION ALL
                SELECT operation.reserved_thread_turn_id AS turn_id,
                       request.returned_trunk_version AS order_version,
                       1 AS cancel_priority
                FROM planning_base_refresh_operation operation
                JOIN trunk_planning_base_request_receipt request
                  ON request.planning_operation_id = operation.id
                WHERE operation.trunk_id = ?
                  AND operation.status IN ('REQUESTED', 'SUCCEEDED')
                  AND operation.launch_disposition = 'PENDING'
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
        return jdbc.query("""
                WITH candidates AS (
                    SELECT turn.id AS turn_id,
                           request.returned_trunk_version AS order_version,
                           CASE WHEN ticket.status IN ('CLAIMED', 'RUNNING')
                             THEN 0 ELSE 1 END AS cancel_priority
                    FROM thread_turn turn
                    JOIN trunk_thread_turn_request_receipt request
                      ON request.turn_id = turn.id
                    JOIN dispatch_ticket ticket
                      ON ticket.owner_kind = 'THREAD_TURN'
                     AND ticket.owner_id = turn.id
                     AND ticket.operation_id = turn.operation_id
                    WHERE turn.trunk_id = ?
                      AND ticket.status IN (
                        'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                        'CLAIMED', 'RUNNING')
                    %s
                )
                SELECT turn_id
                FROM candidates
                ORDER BY cancel_priority, order_version DESC
                LIMIT 1
                """.formatted(pendingPlanning),
                (rs, row) -> rs.getString("turn_id"), arguments.toArray())
                .stream().findFirst();
    }

    /** Active DispatchTicket for one exact Trunk Turn, if it still has one. */
    public Optional<String> cancelableTicketId(String trunkId, String turnId)
    {
        requireText(trunkId, "trunkId");
        requireText(turnId, "turnId");
        String pendingPlanning = planningRuntimeExists() ? """
                UNION ALL
                SELECT ticket.id
                FROM planning_base_refresh_operation operation
                JOIN dispatch_ticket ticket
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.trunk_id = ?
                  AND operation.reserved_thread_turn_id = ?
                  AND operation.launched_thread_turn_id IS NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM thread_turn turn
                    WHERE turn.id = operation.reserved_thread_turn_id)
                  AND ticket.status IN (
                    'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                    'CLAIMED', 'RUNNING')
                """ : "";
        List<Object> arguments = new ArrayList<>();
        arguments.add(trunkId);
        arguments.add(turnId);
        if (!pendingPlanning.isEmpty()) {
            arguments.add(trunkId);
            arguments.add(turnId);
        }
        return jdbc.query("""
                SELECT ticket.id
                FROM dispatch_ticket ticket
                JOIN thread_turn turn ON turn.id = ticket.owner_id
                WHERE ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.operation_id = turn.operation_id
                  AND turn.trunk_id = ? AND turn.id = ?
                  AND ticket.status IN (
                    'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                    'CLAIMED', 'RUNNING')
                %s
                LIMIT 1
                """.formatted(pendingPlanning),
                (rs, row) -> rs.getString("id"), arguments.toArray())
                .stream().findFirst();
    }

    private List<String> activeTicketIds(String trunkId)
    {
        String statuses = "'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT', "
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
        arguments.add(trunkId);
        boolean planningRuntime = planningRuntimeExists();
        String pendingPlanning = "";
        if (planningRuntime) {
            pendingPlanning = """
                    UNION ALL
                    SELECT operation.reserved_thread_turn_id || ':request', 'user',
                           json_extract(operation.launch_intent, '$.userMessage'),
                           (SELECT json_group_array(
                                 json_extract(image.value, '$.path'))
                            FROM json_each(
                              operation.launch_intent, '$.images') image),
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
                           CASE WHEN operation.launch_disposition = 'SUPPRESSED'
                             THEN 'Turn canceled before launch'
                             ELSE 'Planning-base refresh ' || lower(operation.status)
                               || ' before this turn could start' END
                             || CASE WHEN operation.error_message IS NULL
                               THEN CASE WHEN operation.launch_disposition = 'SUPPRESSED'
                                 THEN ': ' || operation.launch_disposition_reason
                                 ELSE '.' END
                               ELSE ': ' || operation.error_message END,
                           NULL,
                           'text', NULL, NULL, operation.completed_at_ms,
                           result.returned_trunk_version, 2
                    FROM planning_base_refresh_operation operation
                    JOIN trunk_planning_base_result_receipt result
                      ON result.planning_operation_id = operation.id
                    WHERE operation.trunk_id = ?
                      AND (operation.status IN (
                        'FAILED', 'CANCELED', 'SUPERSEDED')
                        OR operation.launch_disposition = 'SUPPRESSED')
                      AND operation.launched_thread_turn_id IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM thread_turn turn
                        WHERE turn.id = operation.reserved_thread_turn_id)
                    """;
            arguments.add(trunkId);
            arguments.add(trunkId);
        }
        String planningRequestVersion = planningRuntime ? """
                COALESCE(
                  planning_request.returned_trunk_version,
                  request.returned_trunk_version)
                """ : "request.returned_trunk_version";
        String projectedMessageId = planningRuntime ? """
                CASE
                  WHEN message.seq = 1
                    AND turn.planning_operation_id IS NOT NULL
                    THEN turn.id || ':request'
                  ELSE message.id END
                """ : "message.id";
        String planningRequestJoin = planningRuntime ? """
                LEFT JOIN trunk_planning_base_request_receipt planning_request
                  ON planning_request.planning_operation_id =
                     turn.planning_operation_id
                """ : "";
        String taskOutcomes = "";
        if (taskOutcomeRuntimeExists()) {
            taskOutcomes = """
                    UNION ALL
                    SELECT inbox.id, 'assistant',
                           CASE outcome.summary_state
                             WHEN 'BRAIN_GENERATED' THEN outcome.summary_text
                             ELSE inbox.fallback_summary_text END,
                           NULL,
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
        List<ThreadMessage> projected = jdbc.query("""
                WITH projected AS (
                    SELECT %s AS id,
                           message.role, message.body,
                           CASE WHEN message.seq = 1
                             THEN (
                               SELECT json_group_array(content_ref)
                               FROM (
                                 SELECT attachment.content_ref
                                 FROM thread_attachment attachment
                                 WHERE attachment.turn_id = turn.id
                                 ORDER BY attachment.id
                               )
                             )
                             ELSE NULL END AS images_json,
                           'text' AS message_type,
                           NULL AS task_id, NULL AS task_seq,
                           message.created_at_ms,
                           CASE message.seq
                             WHEN 1 THEN %s
                             ELSE result.returned_trunk_version END
                               AS order_version,
                           message.seq AS item_seq
                    FROM thread_message message
                    JOIN thread_turn turn ON turn.id = message.turn_id
                    JOIN trunk_thread_turn_request_receipt request
                      ON request.turn_id = turn.id
                    LEFT JOIN trunk_thread_turn_result_receipt result
                      ON result.turn_id = turn.id
                    %s
                    WHERE turn.trunk_id = ?
                    UNION ALL
                    SELECT turn.id || ':result', 'assistant',
                           CASE result.terminal_status
                             WHEN 'FAILED' THEN COALESCE(
                               turn.error_message, 'Thread turn failed.')
                             WHEN 'CANCELED' THEN 'Turn canceled.'
                             WHEN 'SUPERSEDED' THEN 'Turn superseded.'
                             ELSE 'Turn completed without a response.' END,
                           NULL,
                           CASE result.terminal_status
                             WHEN 'FAILED' THEN 'error'
                             ELSE 'text' END,
                           NULL, NULL, result.recorded_at_ms,
                           result.returned_trunk_version, 2
                    FROM trunk_thread_turn_result_receipt result
                    JOIN thread_turn turn ON turn.id = result.turn_id
                    WHERE result.trunk_id = ?
                      AND result.assistant_message_id IS NULL
                    %s
                    %s
                )
                SELECT id, role, body, images_json, message_type, task_id, task_seq,
                       created_at_ms, order_version
                FROM projected
                ORDER BY order_version, item_seq, id
                """.formatted(
                        projectedMessageId, planningRequestVersion,
                        planningRequestJoin,
                        pendingPlanning, taskOutcomes),
                (rs, row) -> new ThreadMessage(
                        rs.getString("id"), trunkId, null,
                        compatibilitySeq(rs.getLong("order_version")),
                        rs.getString("role"), rs.getString("message_type"),
                        contentJson(
                                rs.getString("message_type"),
                                rs.getString("body"), rs.getString("task_id"),
                                (Number) rs.getObject("task_seq"),
                                rs.getString("images_json")),
                        null, null, null, null,
                        Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                        null, ThreadScope.TRUNK), arguments.toArray());
        Set<Long> seen = new HashSet<>();
        for (ThreadMessage message : projected) {
            if (!seen.add(message.seq())) {
                throw new IllegalStateException(
                        "typed conversation version exposes multiple rows: %s"
                                .formatted(-message.seq()));
            }
        }
        return projected;
    }

    /**
     * Compatibility readers merge retained LEGACY rows with typed rows.
     * Keep the two physical sequence spaces disjoint without inventing a
     * persisted mirror: LEGACY rows retain their positive thread seq while a
     * typed row uses the negative of the durable Trunk aggregate version that
     * made it visible. Callers order the positive partition first and the
     * negative partition by absolute value.
     */
    private static long compatibilitySeq(long orderVersion)
    {
        if (orderVersion <= 0 || orderVersion > MAX_SAFE_JSON_INTEGER) {
            throw new IllegalStateException(
                    "typed conversation row has no JSON-safe positive Trunk version");
        }
        return -orderVersion;
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
            String type, String text, String taskId, Number taskSeq,
            String imagesJson)
    {
        if (!"task_summary".equals(type)) {
            return MessageAttachments.encodeMessage(
                    json, text, imagePaths(imagesJson));
        }
        try {
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

    private List<String> imagePaths(String imagesJson)
    {
        if (imagesJson == null) {
            return List.of();
        }
        try {
            JsonNode images = json.readTree(imagesJson);
            if (!images.isArray()) {
                throw new IllegalStateException(
                        "ThreadTurn attachment list is not an array");
            }
            List<String> paths = new ArrayList<>();
            images.forEach(image -> paths.add(image.asText()));
            return List.copyOf(paths);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "could not project ThreadTurn attachments", e);
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

    private List<TurnView> physicalTurns(String trunkId, int limit)
    {
        return jdbc.query("""
                SELECT turn.trunk_id, turn.id AS turn_id, turn.purpose,
                       turn.status, turn.operation_id, turn.attempt,
                       turn.delivery_lane, turn.requested_at_ms,
                       turn.started_at_ms, turn.finished_at_ms,
                       turn.error_message, request.actor,
                       message.body AS user_message,
                       ticket.id AS ticket_id, ticket.status AS ticket_status,
                       ticket.cancel_requested_at_ms
                FROM thread_turn turn
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'THREAD_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                 AND ticket.trunk_id = turn.trunk_id
                JOIN thread_message message
                  ON message.turn_id = turn.id AND message.seq = 1
                WHERE turn.trunk_id = ?
                ORDER BY request.returned_trunk_version DESC
                LIMIT ?
                """, ThreadTurnProjection::turnView, trunkId, limit);
    }

    private static TurnView turnView(ResultSet result, int row)
            throws SQLException
    {
        return new TurnView(
                result.getString("trunk_id"), result.getString("turn_id"),
                result.getString("purpose"), result.getString("status"),
                result.getString("operation_id"), result.getInt("attempt"),
                result.getString("delivery_lane"), result.getString("actor"),
                result.getString("user_message"), result.getString("ticket_id"),
                result.getString("ticket_status"),
                instant(result.getObject("requested_at_ms")),
                instant(result.getObject("started_at_ms")),
                instant(result.getObject("finished_at_ms")),
                instant(result.getObject("cancel_requested_at_ms")),
                result.getString("error_message"));
    }

    private static ThreadTurnEvent event(
            TurnView turn,
            String suffix,
            ThreadTurnEventType type,
            Instant createdAt,
            String message)
    {
        return new ThreadTurnEvent(
                "v2:" + turn.turnId() + ":" + suffix,
                turn.turnId(), turn.trunkId(), null, type, createdAt, message);
    }

    private List<StreamEvent> streamEvents(LogFacts row)
    {
        try {
            JsonNode payload = json.readTree(row.payload());
            Instant at = instant(row.createdAtMs());
            if ("provider".equals(payload.path("stream").asText())) {
                String line = payload.path("line").asText("");
                return parser(row.provider(), line).parse(line, at);
            }
            return switch (payload.path("event").asText()) {
                case "text_delta" -> List.of(new StreamEvent.AssistantTextDelta(
                        at, payload.path("blockIndex").asInt(),
                        payload.path("chunk").asText("")));
                case "tool_started" -> List.of(new StreamEvent.ToolCallStarted(
                        at, payload.path("callId").asText(""),
                        payload.path("tool").asText(""),
                        payload.path("input").asText("")));
                case "tool_finished" -> List.of(new StreamEvent.ToolCallDone(
                        at, payload.path("callId").asText(""),
                        payload.path("result").asText(""),
                        payload.path("isError").asBoolean()));
                default -> List.of();
            };
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private CliStreamParser parser(String provider, String line)
    {
        String normalized = provider == null ? ""
                : provider.toLowerCase(Locale.ROOT);
        if (normalized.contains("codex") || line.contains("\"thread.started\"")
                || line.contains("\"turn.completed\"")) {
            return new CodexJsonParser(json);
        }
        return new StreamJsonParser(json);
    }

    private Optional<TrunkTraceEvent> traceEvent(
            String trunkId,
            TraceLogFacts row,
            int eventIndex,
            StreamEvent event)
    {
        String type;
        Map<String, Object> content = new LinkedHashMap<>();
        if (event instanceof StreamEvent.ThinkingStarted thinking) {
            if (thinking.summary() == null || thinking.summary().isBlank()) {
                return Optional.empty();
            }
            type = "thinking";
            content.put("text", thinking.summary());
        }
        else if (event instanceof StreamEvent.ThinkingTextDelta thinking) {
            if (thinking.textChunk() == null || thinking.textChunk().isBlank()) {
                return Optional.empty();
            }
            type = "thinking";
            content.put("text", thinking.textChunk());
        }
        else if (event instanceof StreamEvent.ToolCallStarted tool) {
            type = "tool_call";
            content.put("callId", tool.callId());
            content.put("toolName", tool.toolName());
            content.put("input", jsonValue(tool.inputJson()));
        }
        else if (event instanceof StreamEvent.ToolCallDone tool) {
            type = "tool_result";
            content.put("callId", tool.callId());
            content.put("output", jsonValue(tool.outputJson()));
            content.put("isError", tool.isError());
        }
        else if (event instanceof StreamEvent.ErrorOccurred error) {
            // A terminal failure/cancel/supersede already has one durable
            // assistant row in history(). Do not echo the same terminal fact
            // through the trace channel as well.
            if (row.assistantMessageId() == null
                    && ("FAILED".equals(row.terminalStatus())
                    || "CANCELED".equals(row.terminalStatus())
                    || "SUPERSEDED".equals(row.terminalStatus()))) {
                return Optional.empty();
            }
            type = "error";
            content.put("text", error.message());
            content.put("recoverable", error.recoverable());
        }
        else {
            // Assistant prose and terminal markers belong to history(); live
            // usage/session events are not durable Trunk work rows.
            return Optional.empty();
        }
        String id = "trace:" + row.executionId() + ":" + row.logSeq()
                + ":" + eventIndex;
        return Optional.of(new TrunkTraceEvent(
                id, trunkId, row.turnId(), row.requestMessageId(),
                row.executionId(), row.logSeq(), eventIndex, type,
                traceJson(content), event.timestamp()));
    }

    private Object jsonValue(String value)
    {
        try {
            return json.readTree(value);
        }
        catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private String traceJson(Map<String, Object> content)
    {
        try {
            return json.writeValueAsString(content);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not project Trunk trace", e);
        }
    }

    public record TurnView(
            String trunkId,
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

    public record LogEvent(long cursor, List<StreamEvent> events)
    {
        public LogEvent
        {
            if (cursor < 1) {
                throw new IllegalArgumentException("cursor must be positive");
            }
            events = List.copyOf(requireNonNull(events, "events is null"));
        }
    }

    public record DeletionState(
            String trunkId,
            String lifecycle,
            long version,
            int nonterminalTasks,
            int incompleteCleanups,
            int openWaits,
            int liveTurns,
            int liveTickets,
            int liveExecutions,
            int liveOperations,
            int incompleteStages,
            int liveLeases)
    {
        public DeletionState
        {
            requireText(trunkId, "trunkId");
            requireText(lifecycle, "lifecycle");
            if (version < 0) {
                throw new IllegalArgumentException("version is negative");
            }
        }

        public Optional<String> blocker()
        {
            if (nonterminalTasks > 0) {
                return Optional.of(nonterminalTasks
                        + " V2 task" + (nonterminalTasks == 1 ? " is" : "s are")
                        + " not terminal; finish cancellation and Cleanup first.");
            }
            if (incompleteCleanups > 0) {
                return Optional.of(incompleteCleanups
                        + " V2 task" + (incompleteCleanups == 1 ? " lacks" : "s lack")
                        + " exact completed Cleanup evidence.");
            }
            int liveWork = liveTurns + liveTickets + liveExecutions
                    + liveOperations + incompleteStages + liveLeases;
            if (openWaits > 0) {
                return Optional.of(openWaits
                        + " typed user wait" + (openWaits == 1 ? " is" : "s are")
                        + " still open.");
            }
            if (liveWork > 0) {
                return Optional.of(liveWork
                        + " V2 work item" + (liveWork == 1 ? " is" : "s are")
                        + " not quiescent.");
            }
            return Optional.empty();
        }
    }

    private record LogFacts(
            long rowId, String payload, long createdAtMs, String provider)
    {}

    private record TraceLogFacts(
            String turnId,
            String requestMessageId,
            String executionId,
            long logSeq,
            String payload,
            long createdAtMs,
            String provider,
            String terminalStatus,
            String assistantMessageId)
    {}
}
