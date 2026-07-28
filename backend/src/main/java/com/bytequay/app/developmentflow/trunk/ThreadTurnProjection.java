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
        return jdbc.query("""
                SELECT turn.id AS turn_id, turn.purpose, turn.status,
                       turn.operation_id, turn.attempt, turn.delivery_lane,
                       turn.requested_at_ms, turn.started_at_ms,
                       turn.finished_at_ms, turn.error_message,
                       ticket.id AS ticket_id, ticket.status AS ticket_status,
                       ticket.cancel_requested_at_ms
                FROM thread_turn turn
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'THREAD_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                WHERE turn.trunk_id = ?
                ORDER BY request.returned_trunk_version DESC
                LIMIT ?
                """,
                (rs, row) -> new TurnView(
                        rs.getString("turn_id"), rs.getString("purpose"),
                        rs.getString("status"), rs.getString("operation_id"),
                        rs.getInt("attempt"), rs.getString("delivery_lane"),
                        rs.getString("ticket_id"),
                        rs.getString("ticket_status"),
                        instant(rs.getObject("requested_at_ms")),
                        instant(rs.getObject("started_at_ms")),
                        instant(rs.getObject("finished_at_ms")),
                        instant(rs.getObject("cancel_requested_at_ms")),
                        rs.getString("error_message")),
                trunkId, Math.min(limit, MAX_TURNS));
    }

    /** Tickets whose external execution can still be interrupted. */
    public List<String> cancelableTicketIds(String trunkId)
    {
        requireText(trunkId, "trunkId");
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

    /** Tickets with an execution that a user interrupt can stop immediately. */
    public List<String> interruptibleTicketIds(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT ticket.id
                FROM dispatch_ticket ticket
                JOIN thread_turn turn ON turn.id = ticket.owner_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                WHERE turn.trunk_id = ?
                  AND ticket.owner_kind = 'THREAD_TURN'
                  AND ticket.operation_id = turn.operation_id
                  AND ticket.status IN ('CLAIMED', 'RUNNING')
                ORDER BY request.returned_trunk_version
                """, (rs, row) -> rs.getString("id"), trunkId);
    }

    /** Existing UI history shape, projected without nullable-scope inference. */
    public List<ThreadMessage> history(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT message.id, message.role, message.body,
                       message.created_at_ms
                FROM thread_message message
                JOIN thread_turn turn ON turn.id = message.turn_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                LEFT JOIN trunk_thread_turn_result_receipt result
                  ON result.turn_id = turn.id
                WHERE turn.trunk_id = ?
                ORDER BY CASE message.seq
                    WHEN 1 THEN request.returned_trunk_version
                    ELSE result.returned_trunk_version END,
                    message.seq
                """, (rs, row) -> new ThreadMessage(
                        rs.getString("id"), trunkId, null, row + 1L,
                        rs.getString("role"), "text",
                        textJson(rs.getString("body")), null, null, null, null,
                        Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                        null, ThreadScope.TRUNK), trunkId);
    }

    private String textJson(String text)
    {
        try {
            return json.writeValueAsString(Map.of("text", text));
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
            String ticketId,
            String ticketStatus,
            Instant requestedAt,
            Instant startedAt,
            Instant finishedAt,
            Instant cancelRequestedAt,
            String error)
    {}
}
