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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchDeliveryClaim;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteDispatchTicketStore
        implements ExecutionPorts.DispatchTicketStore
{
    private static final int SQLITE_CONSTRAINT = 19;

    private final DataSource dataSource;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    public SqliteDispatchTicketStore(DataSource dataSource)
    {
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
    }

    @Override
    public ExecutionPorts.TicketScanPage findEligiblePage(
            Instant now,
            ExecutionPorts.TicketScanCursor cursor,
            int limit)
    {
        requireNonNull(now, "now is null");
        positiveLimit(limit);
        String cursorClause = cursor == null ? "" : """
                WHERE (candidate_round, trunk_round, workspace_order_key,
                    trunk_order_key, created_at_ms, id) > (?, ?, ?, ?, ?, ?)
                """;
        String sql = """
                WITH eligible AS (
                    SELECT d.*,
                        COALESCE(d.workspace_id, char(0)) AS workspace_order_key,
                        COALESCE(d.trunk_id, char(0)) AS trunk_order_key,
                        CASE
                            WHEN d.status = 'RESULT_PENDING' THEN 'DELIVERY'
                            WHEN d.trunk_control = 1 THEN 'TRUNK_CONTROL'
                            ELSE 'ORDINARY'
                        END AS scan_class
                    FROM dispatch_ticket d
                    WHERE (
                        d.status = 'REQUESTED'
                        OR (d.status IN ('RETRY_WAIT', 'RESULT_PENDING')
                            AND (d.next_attempt_at_ms IS NULL
                                OR d.next_attempt_at_ms <= ?))
                        OR (d.status = 'RECONCILE_WAIT'
                            AND d.next_attempt_at_ms IS NOT NULL
                            AND d.next_attempt_at_ms <= ?))
                      AND (d.status <> 'RESULT_PENDING' OR NOT EXISTS (
                          SELECT 1 FROM dispatch_delivery_claim c
                          WHERE c.ticket_id = d.id))
                      AND (d.owner_kind <> 'THREAD_TURN' OR NOT EXISTS (
                          SELECT 1 FROM dispatch_ticket preceding
                          WHERE preceding.trunk_id = d.trunk_id
                            AND preceding.id <> d.id
                            AND preceding.owner_kind = 'THREAD_TURN'
                            AND preceding.status NOT IN (
                                'SUCCEEDED', 'FAILED', 'CANCELED')
                            AND (preceding.created_at_ms < d.created_at_ms
                              OR (preceding.created_at_ms = d.created_at_ms
                                AND preceding.id < d.id))))
                ),
                class_ranked AS (
                    SELECT eligible.*,
                        ROW_NUMBER() OVER (
                            PARTITION BY workspace_order_key, trunk_order_key, scan_class
                            ORDER BY created_at_ms, id) AS class_rank
                    FROM eligible
                ),
                heads AS (
                    SELECT * FROM class_ranked WHERE class_rank = 1
                ),
                trunk_ranked AS (
                    SELECT workspace_order_key, trunk_order_key,
                        ROW_NUMBER() OVER (
                            PARTITION BY workspace_order_key
                            ORDER BY trunk_order_key) - 1 AS trunk_round
                    FROM (
                        SELECT DISTINCT workspace_order_key, trunk_order_key
                        FROM eligible)
                ),
                ranked AS (
                    SELECT heads.*,
                        ROW_NUMBER() OVER (
                            PARTITION BY heads.workspace_order_key, heads.trunk_order_key
                            ORDER BY heads.created_at_ms, heads.id) - 1 AS candidate_round,
                        trunk_ranked.trunk_round
                    FROM heads
                    JOIN trunk_ranked USING (workspace_order_key, trunk_order_key)
                )
                SELECT * FROM ranked
                %s
                ORDER BY candidate_round, trunk_round, workspace_order_key,
                    trunk_order_key, created_at_ms, id
                LIMIT ?
                """.formatted(cursorClause);

        return SqliteTransactions.withConnection(
                dataSource, transactionConnection, connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        int index = 1;
                        statement.setLong(index++, now.toEpochMilli());
                        statement.setLong(index++, now.toEpochMilli());
                        if (cursor != null) {
                            statement.setInt(index++, cursor.candidateRound());
                            statement.setInt(index++, cursor.trunkRound());
                            statement.setString(index++, cursor.workspaceOrderKey());
                            statement.setString(index++, cursor.trunkOrderKey());
                            statement.setLong(index++, cursor.createdAt().toEpochMilli());
                            statement.setString(index++, cursor.ticketId());
                        }
                        statement.setInt(index, limit);
                        try (ResultSet result = statement.executeQuery()) {
                            List<DispatchTicket> tickets = new ArrayList<>();
                            ExecutionPorts.TicketScanCursor next = null;
                            while (result.next()) {
                                tickets.add(mapTicket(result));
                                next = new ExecutionPorts.TicketScanCursor(
                                        result.getInt("candidate_round"),
                                        result.getInt("trunk_round"),
                                        result.getString("workspace_order_key"),
                                        result.getString("trunk_order_key"),
                                        instant(result, "created_at_ms"),
                                        result.getString("id"));
                            }
                            return new ExecutionPorts.TicketScanPage(tickets, next);
                        }
                    }
                });
    }

    @Override
    public List<DispatchTicket> findExpiredClaims(Instant now, int limit)
    {
        requireNonNull(now, "now is null");
        positiveLimit(limit);
        return queryTickets("""
                SELECT * FROM dispatch_ticket
                WHERE status IN ('CLAIMED', 'RUNNING')
                    AND claim_expires_at_ms <= ?
                ORDER BY claim_expires_at_ms, id
                LIMIT ?
                """, statement -> {
                    statement.setLong(1, now.toEpochMilli());
                    statement.setInt(2, limit);
                });
    }

    @Override
    public List<DispatchDeliveryClaim> findExpiredDeliveryClaims(
            Instant now,
            int limit)
    {
        requireNonNull(now, "now is null");
        positiveLimit(limit);
        return queryClaims("""
                SELECT * FROM dispatch_delivery_claim
                WHERE expires_at_ms <= ?
                ORDER BY expires_at_ms, ticket_id
                LIMIT ?
                """, statement -> {
                    statement.setLong(1, now.toEpochMilli());
                    statement.setInt(2, limit);
                });
    }

    @Override
    public Optional<DispatchTicket> findById(String ticketId)
    {
        requireText(ticketId, "ticketId");
        return oneTicket("SELECT * FROM dispatch_ticket WHERE id = ?",
                statement -> statement.setString(1, ticketId));
    }

    @Override
    public boolean compareAndSet(
            String ticketId,
            long expectedVersion,
            DispatchTicket replacement)
    {
        requireText(ticketId, "ticketId");
        requireNonNull(replacement, "replacement is null");
        if (!ticketId.equals(replacement.id())
                || replacement.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("invalid ticket replacement");
        }
        Optional<DispatchTicket> current = findById(ticketId);
        if (current.isEmpty() || current.orElseThrow().version() != expectedVersion) {
            return false;
        }
        requireSameIdentity(current.orElseThrow(), replacement);
        return writeTicket(
                null, replacement, expectedVersion, true) == 1;
    }

    @Override
    public Optional<DispatchDeliveryClaim> claimDelivery(
            String ticketId,
            long ticketVersion,
            String claimOwner,
            Instant claimedAt,
            Instant expiresAt)
    {
        DispatchDeliveryClaim claim = new DispatchDeliveryClaim(
                ticketId, ticketVersion, claimOwner, claimedAt, claimedAt, expiresAt);
        try {
            int inserted = update("""
                    INSERT INTO dispatch_delivery_claim(
                        ticket_id, ticket_version, claim_owner, claimed_at_ms,
                        heartbeat_at_ms, expires_at_ms)
                    SELECT id, version, ?, ?, ?, ?
                    FROM dispatch_ticket
                    WHERE id = ? AND version = ? AND status = 'RESULT_PENDING'
                        AND pending_result_outcome IS NOT NULL
                        AND claim_owner IS NULL AND capacity_lease_id IS NULL
                        AND NOT EXISTS (
                            SELECT 1 FROM dispatch_delivery_claim c
                            WHERE c.ticket_id = dispatch_ticket.id)
                    """, statement -> {
                        statement.setString(1, claimOwner);
                        statement.setLong(2, claimedAt.toEpochMilli());
                        statement.setLong(3, claimedAt.toEpochMilli());
                        statement.setLong(4, expiresAt.toEpochMilli());
                        statement.setString(5, ticketId);
                        statement.setLong(6, ticketVersion);
                    });
            return inserted == 1 ? Optional.of(claim) : Optional.empty();
        }
        catch (RuntimeException e) {
            if (isConstraint(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<DispatchDeliveryClaim> heartbeatDeliveryClaim(
            DispatchDeliveryClaim claim,
            Instant heartbeatAt,
            Instant expiresAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(heartbeatAt, "heartbeatAt is null");
        requireNonNull(expiresAt, "expiresAt is null");
        if (!expiresAt.isAfter(heartbeatAt)) {
            throw new IllegalArgumentException("claim expiry must follow heartbeat");
        }
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE dispatch_delivery_claim
                            SET heartbeat_at_ms = ?, expires_at_ms = ?
                            WHERE ticket_id = ? AND ticket_version = ? AND claim_owner = ?
                                AND claimed_at_ms = ? AND expires_at_ms > ?
                                AND heartbeat_at_ms <= ? AND expires_at_ms <= ?
                            RETURNING *
                            """)) {
                        statement.setLong(1, heartbeatAt.toEpochMilli());
                        statement.setLong(2, expiresAt.toEpochMilli());
                        bindClaimIdentity(statement, 3, claim);
                        statement.setLong(7, heartbeatAt.toEpochMilli());
                        statement.setLong(8, heartbeatAt.toEpochMilli());
                        statement.setLong(9, expiresAt.toEpochMilli());
                        try (ResultSet result = statement.executeQuery()) {
                            return result.next()
                                    ? Optional.of(mapClaim(result))
                                    : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public boolean replaceTicketAndReleaseDeliveryClaim(
            DispatchDeliveryClaim claim,
            DispatchTicket replacement)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(replacement, "replacement is null");
        if (!claim.ticketId().equals(replacement.id())
                || replacement.version() != claim.ticketVersion() + 1) {
            throw new IllegalArgumentException("invalid delivery ticket replacement");
        }
        try {
            return SqliteTransactions.immediate(
                    dataSource, transactionConnection, connection -> {
                        DispatchTicket current = oneTicket(
                                connection,
                                "SELECT * FROM dispatch_ticket WHERE id = ?",
                                statement -> statement.setString(1, claim.ticketId()))
                                .orElseThrow(StaleDeliveryClaim::new);
                        if (!claim.owns(current)) {
                            throw new StaleDeliveryClaim();
                        }
                        requireSameIdentity(current, replacement);
                        try (PreparedStatement statement = connection.prepareStatement("""
                                DELETE FROM dispatch_delivery_claim
                                WHERE ticket_id = ? AND ticket_version = ?
                                    AND claim_owner = ? AND claimed_at_ms = ?
                                """)) {
                            bindClaimIdentity(statement, 1, claim);
                            if (statement.executeUpdate() != 1) {
                                throw new StaleDeliveryClaim();
                            }
                        }
                        if (writeTicket(
                                connection,
                                replacement,
                                claim.ticketVersion(),
                                false) != 1) {
                            throw new StaleDeliveryClaim();
                        }
                        return true;
                    });
        }
        catch (StaleDeliveryClaim ignored) {
            return false;
        }
    }

    @Override
    public boolean releaseExpiredDeliveryClaim(
            DispatchDeliveryClaim claim,
            Instant expiredAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(expiredAt, "expiredAt is null");
        return update("""
                DELETE FROM dispatch_delivery_claim
                WHERE ticket_id = ? AND ticket_version = ? AND claim_owner = ?
                    AND claimed_at_ms = ? AND heartbeat_at_ms = ?
                    AND expires_at_ms = ? AND expires_at_ms <= ?
                """, statement -> {
                    bindClaimIdentity(statement, 1, claim);
                    statement.setLong(5, claim.heartbeatAt().toEpochMilli());
                    statement.setLong(6, claim.expiresAt().toEpochMilli());
                    statement.setLong(7, expiredAt.toEpochMilli());
                }) == 1;
    }

    private int writeTicket(
            Connection supplied,
            DispatchTicket replacement,
            long expectedVersion,
            boolean rejectDeliveryClaim)
    {
        String claimClause = rejectDeliveryClaim
                ? " AND NOT EXISTS (SELECT 1 FROM dispatch_delivery_claim c WHERE c.ticket_id = dispatch_ticket.id)"
                : "";
        String sql = """
                UPDATE dispatch_ticket
                SET version = ?, status = ?, claim_purpose = ?, claim_owner = ?,
                    capacity_lease_id = ?, claim_expires_at_ms = ?,
                    next_attempt_at_ms = ?, cancel_requested_at_ms = ?,
                    infrastructure_attempts = ?, started_at_ms = ?,
                    pending_result_outcome = ?, pending_result_payload = ?,
                    pending_result_evidence = ?, pending_result_error = ?,
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?, pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?, delivery_acceptance = ?,
                    delivery_evidence = ?, completed_at_ms = ?, last_error = ?
                WHERE id = ? AND version = ?
                """ + claimClause;
        SqliteTransactions.SqlWork<Integer> work = connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindReplacement(statement, replacement, expectedVersion);
                return statement.executeUpdate();
            }
        };
        return supplied == null
                ? SqliteTransactions.withConnection(dataSource, transactionConnection, work)
                : run(work, supplied);
    }

    private static void bindReplacement(
            PreparedStatement statement,
            DispatchTicket ticket,
            long expectedVersion)
            throws SQLException
    {
        int index = 1;
        statement.setLong(index++, ticket.version());
        statement.setString(index++, ticket.state().name());
        statement.setString(index++, name(ticket.claimPurpose()));
        statement.setString(index++, ticket.claimOwner());
        statement.setString(index++, ticket.capacityLeaseId());
        setInstant(statement, index++, ticket.claimExpiresAt());
        setInstant(statement, index++, ticket.nextAttemptAt());
        setInstant(statement, index++, ticket.cancelRequestedAt());
        statement.setInt(index++, ticket.infrastructureAttempts());
        setInstant(statement, index++, ticket.startedAt());
        DispatchTicket.DispatchResult pending = ticket.pendingResult();
        statement.setString(index++, pending == null ? null : pending.outcome().name());
        statement.setString(index++, pending == null ? null : pending.payloadJson());
        statement.setString(index++, pending == null ? null : pending.evidenceJson());
        statement.setString(index++, pending == null ? null : pending.error());
        DispatchTicket.OperationFence rawFence = pending == null ? null : pending.fence();
        setLong(statement, index++, rawFence == null ? null : rawFence.taskEpoch());
        statement.setString(index++, rawFence == null ? null : rawFence.stageId());
        setLong(statement, index++, rawFence == null ? null : rawFence.stageGeneration());
        statement.setString(index++, rawFence == null ? null : rawFence.operationId());
        setLong(statement, index++, rawFence == null ? null : (long) rawFence.attempt());
        statement.setString(index++, rawFence == null
                ? null : rawFence.expectedCodeFingerprint());
        statement.setString(index++, rawFence == null ? null : rawFence.expectedHeadSha());
        statement.setString(index++, rawFence == null ? null : rawFence.expectedBaseSha());
        DispatchTicket.DeliveryReceipt receipt = ticket.deliveryReceipt();
        statement.setString(index++, receipt == null ? null : receipt.acceptance().name());
        statement.setString(index++, receipt == null ? null : receipt.evidenceJson());
        setInstant(statement, index++, ticket.completedAt());
        statement.setString(index++, ticket.lastError());
        statement.setString(index++, ticket.id());
        statement.setLong(index, expectedVersion);
    }

    private static DispatchTicket mapTicket(ResultSet result)
            throws SQLException
    {
        DispatchTicket.OperationFence envelopeFence = new DispatchTicket.OperationFence(
                nullableLong(result, "task_epoch"),
                result.getString("stage_id"),
                nullableLong(result, "stage_generation"),
                result.getString("operation_id"),
                result.getInt("attempt"),
                result.getString("expected_code_fingerprint"),
                result.getString("expected_head_sha"),
                result.getString("expected_base_sha"));
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                result.getString("operation_id"),
                CapacityManager.WorkflowSource.V2,
                CapacityManager.CapacityLane.fromMask(result.getInt("lane_mask")),
                new CapacityManager.CapacityScope(
                        result.getString("workspace_id"),
                        result.getString("trunk_id"),
                        result.getString("task_id"),
                        nullableLong(result, "task_epoch")),
                result.getInt("trunk_control") != 0,
                result.getInt("exclusive_task") != 0,
                result.getInt("writer_required") != 0);
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                result.getString("operation_kind"),
                DispatchTicket.AsyncFamily.valueOf(result.getString("async_family")),
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.valueOf(result.getString("owner_kind")),
                        result.getString("owner_id"),
                        result.getString("callback_route")),
                envelopeFence,
                capacity);

        String outcome = result.getString("pending_result_outcome");
        DispatchTicket.DispatchResult pending = null;
        if (outcome != null) {
            DispatchTicket.OperationFence rawFence = new DispatchTicket.OperationFence(
                    nullableLong(result, "pending_result_task_epoch"),
                    result.getString("pending_result_stage_id"),
                    nullableLong(result, "pending_result_stage_generation"),
                    result.getString("pending_result_operation_id"),
                    result.getInt("pending_result_attempt"),
                    result.getString("pending_result_expected_code_fingerprint"),
                    result.getString("pending_result_expected_head_sha"),
                    result.getString("pending_result_expected_base_sha"));
            pending = new DispatchTicket.DispatchResult(
                    rawFence,
                    DispatchTicket.Outcome.valueOf(outcome),
                    result.getString("pending_result_payload"),
                    result.getString("pending_result_evidence"),
                    result.getString("pending_result_error"));
        }
        String acceptance = result.getString("delivery_acceptance");
        DispatchTicket.DeliveryReceipt receipt = acceptance == null
                ? null
                : new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.valueOf(acceptance),
                        result.getString("delivery_evidence"));
        String purpose = result.getString("claim_purpose");
        return new DispatchTicket(
                result.getString("id"),
                result.getLong("version"),
                envelope,
                DispatchTicket.State.valueOf(result.getString("status")),
                purpose == null ? null : DispatchTicket.ClaimPurpose.valueOf(purpose),
                result.getString("claim_owner"),
                result.getString("capacity_lease_id"),
                nullableInstant(result, "claim_expires_at_ms"),
                instant(result, "created_at_ms"),
                nullableInstant(result, "next_attempt_at_ms"),
                result.getInt("infrastructure_attempts"),
                nullableInstant(result, "started_at_ms"),
                nullableInstant(result, "cancel_requested_at_ms"),
                pending,
                receipt,
                nullableInstant(result, "completed_at_ms"),
                result.getString("last_error"));
    }

    private static DispatchDeliveryClaim mapClaim(ResultSet result)
            throws SQLException
    {
        return new DispatchDeliveryClaim(
                result.getString("ticket_id"),
                result.getLong("ticket_version"),
                result.getString("claim_owner"),
                instant(result, "claimed_at_ms"),
                instant(result, "heartbeat_at_ms"),
                instant(result, "expires_at_ms"));
    }

    private Optional<DispatchTicket> oneTicket(String sql, StatementBinder binder)
    {
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> oneTicket(connection, sql, binder));
    }

    private static Optional<DispatchTicket> oneTicket(
            Connection connection,
            String sql,
            StatementBinder binder)
            throws SQLException
    {
        List<DispatchTicket> found = queryTickets(connection, sql, binder);
        if (found.size() > 1) {
            throw new IllegalStateException("ticket query returned multiple rows");
        }
        return found.stream().findFirst();
    }

    private List<DispatchTicket> queryTickets(String sql, StatementBinder binder)
    {
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> queryTickets(connection, sql, binder));
    }

    private static List<DispatchTicket> queryTickets(
            Connection connection,
            String sql,
            StatementBinder binder)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<DispatchTicket> tickets = new ArrayList<>();
                while (result.next()) {
                    tickets.add(mapTicket(result));
                }
                return List.copyOf(tickets);
            }
        }
    }

    private List<DispatchDeliveryClaim> queryClaims(
            String sql, StatementBinder binder)
    {
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        binder.bind(statement);
                        try (ResultSet result = statement.executeQuery()) {
                            List<DispatchDeliveryClaim> claims = new ArrayList<>();
                            while (result.next()) {
                                claims.add(mapClaim(result));
                            }
                            return List.copyOf(claims);
                        }
                    }
                });
    }

    private int update(String sql, StatementBinder binder)
    {
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        binder.bind(statement);
                        return statement.executeUpdate();
                    }
                });
    }

    private static <T> T run(
            SqliteTransactions.SqlWork<T> work, Connection connection)
    {
        try {
            return work.apply(connection);
        }
        catch (SQLException e) {
            throw SqliteTransactions.failure("Dispatch ticket update failed", e);
        }
    }

    private static void requireSameIdentity(
            DispatchTicket current, DispatchTicket replacement)
    {
        if (!current.id().equals(replacement.id())
                || !current.envelope().equals(replacement.envelope())
                || !current.createdAt().equals(replacement.createdAt())) {
            throw new IllegalArgumentException("ticket replacement changes immutable identity");
        }
    }

    private static void bindClaimIdentity(
            PreparedStatement statement,
            int start,
            DispatchDeliveryClaim claim)
            throws SQLException
    {
        statement.setString(start, claim.ticketId());
        statement.setLong(start + 1, claim.ticketVersion());
        statement.setString(start + 2, claim.claimOwner());
        statement.setLong(start + 3, claim.claimedAt().toEpochMilli());
    }

    private static boolean isConstraint(Throwable failure)
    {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sql
                    && sql.getErrorCode() == SQLITE_CONSTRAINT) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(result.getLong(column));
    }

    private static Instant nullableInstant(ResultSet result, String column)
            throws SQLException
    {
        Long value = nullableLong(result, column);
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private static void setLong(PreparedStatement statement, int index, Long value)
            throws SQLException
    {
        if (value == null) {
            statement.setObject(index, null);
        }
        else {
            statement.setLong(index, value);
        }
    }

    private static void setInstant(
            PreparedStatement statement, int index, Instant value)
            throws SQLException
    {
        setLong(statement, index, value == null ? null : value.toEpochMilli());
    }

    private static String name(Enum<?> value)
    {
        return value == null ? null : value.name();
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void positiveLimit(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    @FunctionalInterface
    private interface StatementBinder
    {
        void bind(PreparedStatement statement)
                throws SQLException;
    }

    private static final class StaleDeliveryClaim
            extends RuntimeException
    {
    }
}
