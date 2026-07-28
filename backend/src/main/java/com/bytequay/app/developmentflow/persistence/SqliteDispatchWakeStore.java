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

import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Transaction-compatible access to the exact advisory DispatchTicket wake. */
@Repository
public class SqliteDispatchWakeStore
        implements ExecutionPorts.DispatchWakeStore
{
    static final String TOPIC = "V2_DISPATCH_TICKET_REQUESTED";
    static final String PREFIX = TOPIC + ":";

    private final JdbcTemplate jdbc;

    public SqliteDispatchWakeStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public void enqueue(String ticketId, Instant createdAt)
    {
        requireText(ticketId, "ticketId");
        requireNonNull(createdAt, "createdAt is null");
        String wakeId = PREFIX + ticketId;
        jdbc.update("""
                INSERT INTO outbox(
                    id, dedup_key, aggregate_kind, aggregate_id, topic, payload,
                    status, attempts, available_at_ms, created_at_ms)
                SELECT ?, ?, 'DISPATCH_TICKET', id, ?, id,
                    'PENDING', 0, ?, ?
                FROM dispatch_ticket
                WHERE id = ? AND status = 'REQUESTED' AND created_at_ms = ?
                ON CONFLICT(dedup_key) DO NOTHING
                """,
                wakeId, wakeId, TOPIC,
                createdAt.toEpochMilli(), createdAt.toEpochMilli(),
                ticketId, createdAt.toEpochMilli());
        Integer exact = jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox
                WHERE id = ? AND dedup_key = ?
                  AND aggregate_kind = 'DISPATCH_TICKET'
                  AND aggregate_id = ? AND topic = ? AND payload = ?
                  AND created_at_ms = ?
                """,
                Integer.class,
                wakeId, wakeId, ticketId, TOPIC, ticketId,
                createdAt.toEpochMilli());
        if (exact == null || exact != 1) {
            throw new IllegalStateException(
                    "DispatchTicket does not exist or has conflicting wake: " + ticketId);
        }
    }

    @Override
    public List<ExecutionPorts.DispatchWakeClaim> claimAvailable(
            String claimOwner,
            Instant claimedAt,
            Instant expiresAt,
            int limit)
    {
        requireText(claimOwner, "claimOwner");
        requireNonNull(claimedAt, "claimedAt is null");
        requireNonNull(expiresAt, "expiresAt is null");
        if (!expiresAt.isAfter(claimedAt) || limit < 1) {
            throw new IllegalArgumentException("wake claim expiry and limit are invalid");
        }

        List<Candidate> candidates = jdbc.query("""
                SELECT id, aggregate_id, status, attempts, available_at_ms,
                       claim_owner, lease_until_ms
                FROM outbox
                WHERE aggregate_kind = 'DISPATCH_TICKET'
                  AND topic = ?
                  AND ((status = 'PENDING' AND available_at_ms <= ?)
                    OR (status = 'CLAIMED' AND lease_until_ms <= ?))
                ORDER BY CASE status
                    WHEN 'PENDING' THEN available_at_ms ELSE lease_until_ms END,
                    created_at_ms, id
                LIMIT ?
                """,
                (rs, row) -> new Candidate(
                        rs.getString("id"),
                        rs.getString("aggregate_id"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getLong("available_at_ms"),
                        rs.getString("claim_owner"),
                        nullableLong(rs.getObject("lease_until_ms"))),
                TOPIC, claimedAt.toEpochMilli(), claimedAt.toEpochMilli(), limit);

        List<ExecutionPorts.DispatchWakeClaim> claimed = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int changed = jdbc.update("""
                    UPDATE outbox
                    SET status = 'CLAIMED', attempts = attempts + 1,
                        available_at_ms = ?, claim_owner = ?, lease_until_ms = ?,
                        delivered_at_ms = NULL, last_error = NULL
                    WHERE id = ? AND aggregate_kind = 'DISPATCH_TICKET'
                      AND topic = ? AND status = ? AND attempts = ?
                      AND available_at_ms = ? AND claim_owner IS ?
                      AND lease_until_ms IS ?
                      AND ((status = 'PENDING' AND available_at_ms <= ?)
                        OR (status = 'CLAIMED' AND lease_until_ms <= ?))
                    """,
                    claimedAt.toEpochMilli(), claimOwner, expiresAt.toEpochMilli(),
                    candidate.wakeId(), TOPIC, candidate.status(), candidate.attempts(),
                    candidate.availableAtMs(), candidate.claimOwner(), candidate.leaseUntilMs(),
                    claimedAt.toEpochMilli(), claimedAt.toEpochMilli());
            if (changed == 1) {
                claimed.add(new ExecutionPorts.DispatchWakeClaim(
                        candidate.wakeId(),
                        candidate.ticketId(),
                        candidate.attempts() + 1,
                        claimOwner,
                        claimedAt,
                        expiresAt));
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean markDelivered(
            ExecutionPorts.DispatchWakeClaim claim,
            Instant deliveredAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(deliveredAt, "deliveredAt is null");
        if (deliveredAt.isBefore(claim.claimedAt())) {
            throw new IllegalArgumentException("wake delivery precedes its claim");
        }
        return jdbc.update("""
                UPDATE outbox
                SET status = 'DELIVERED', claim_owner = NULL,
                    lease_until_ms = NULL, delivered_at_ms = ?, last_error = NULL
                WHERE id = ? AND aggregate_kind = 'DISPATCH_TICKET'
                  AND aggregate_id = ? AND topic = ? AND payload = ?
                  AND status = 'CLAIMED' AND attempts = ?
                  AND available_at_ms = ? AND claim_owner = ?
                  AND lease_until_ms = ? AND delivered_at_ms IS NULL
                """,
                deliveredAt.toEpochMilli(),
                claim.wakeId(), claim.ticketId(), TOPIC, claim.ticketId(),
                claim.attempt(), claim.claimedAt().toEpochMilli(), claim.claimOwner(),
                claim.expiresAt().toEpochMilli()) == 1;
    }

    private static Long nullableLong(Object value)
    {
        return value == null ? null : ((Number) value).longValue();
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record Candidate(
            String wakeId,
            String ticketId,
            String status,
            int attempts,
            long availableAtMs,
            String claimOwner,
            Long leaseUntilMs) {}
}
