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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteExecutionEvidencePort
        implements ExecutionPorts.ExecutionEvidencePort
{
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final Supplier<String> idSupplier;
    private final ThreadLocal<Connection> transactionConnection =
            new ThreadLocal<>();

    @Autowired
    public SqliteExecutionEvidencePort(DataSource dataSource, ObjectMapper mapper)
    {
        this(dataSource, mapper, () -> UUID.randomUUID().toString());
    }

    SqliteExecutionEvidencePort(
            DataSource dataSource,
            ObjectMapper mapper,
            Supplier<String> idSupplier)
    {
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.idSupplier = requireNonNull(idSupplier, "idSupplier is null");
    }

    @Override
    public String start(
            DispatchTicket ticket,
            CapacityManager.CapacityLease lease,
            DispatchTicket.ClaimPurpose purpose,
            Instant startedAt)
    {
        requireNonNull(ticket, "ticket is null");
        requireNonNull(lease, "lease is null");
        requireNonNull(purpose, "purpose is null");
        requireNonNull(startedAt, "startedAt is null");
        if (ticket.state() != DispatchTicket.State.RUNNING
                || ticket.claimPurpose() != purpose
                || !ticket.id().equals(lease.ticketId())
                || !ticket.envelope().fence().operationId().equals(lease.operationId())
                || !lease.id().equals(ticket.capacityLeaseId())
                || !lease.leaseOwner().equals(ticket.claimOwner())
                || ticket.infrastructureAttempts() < 1) {
            throw new IllegalArgumentException(
                    "execution evidence requires its exact ticket lease and attempt");
        }
        String id = requireText(idSupplier.get(), "generated execution id");
        updateRequired("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    started_at_ms, heartbeat_at_ms)
                SELECT ?, d.id, d.infrastructure_attempts, 'RUNNING', ?, ?
                FROM dispatch_ticket d
                JOIN capacity_lease c ON c.id = d.capacity_lease_id
                WHERE d.id = ? AND d.version = ? AND d.status = 'RUNNING'
                    AND d.claim_purpose = ? AND d.claim_owner = ?
                    AND d.capacity_lease_id = ? AND d.operation_id = ?
                    AND d.infrastructure_attempts = ? AND d.attempt = ?
                    AND d.started_at_ms <= ? AND d.claim_expires_at_ms > ?
                    AND c.ticket_id = d.id AND c.operation_id = d.operation_id
                    AND c.workflow_source = 'V2' AND c.holder = d.claim_owner
                    AND c.lane_mask = d.lane_mask
                    AND c.workspace_id IS d.workspace_id
                    AND c.trunk_id IS d.trunk_id
                    AND c.task_id IS d.task_id
                    AND c.task_epoch IS d.task_epoch
                    AND c.trunk_control = d.trunk_control
                    AND c.exclusive_task = d.exclusive_task
                    AND c.writer_required = d.writer_required
                    AND c.released_at_ms IS NULL AND c.expires_at_ms > ?
                """, statement -> {
                    statement.setString(1, id);
                    statement.setLong(2, startedAt.toEpochMilli());
                    statement.setLong(3, startedAt.toEpochMilli());
                    statement.setString(4, ticket.id());
                    statement.setLong(5, ticket.version());
                    statement.setString(6, purpose.name());
                    statement.setString(7, lease.leaseOwner());
                    statement.setString(8, lease.id());
                    statement.setString(9, lease.operationId());
                    statement.setInt(10, ticket.infrastructureAttempts());
                    statement.setInt(11, ticket.envelope().fence().attempt());
                    statement.setLong(12, startedAt.toEpochMilli());
                    statement.setLong(13, startedAt.toEpochMilli());
                    statement.setLong(14, startedAt.toEpochMilli());
                }, "execution start");
        return id;
    }

    @Override
    public void heartbeat(String executionId, Instant at)
    {
        requireText(executionId, "executionId");
        requireNonNull(at, "at is null");
        updateRequired("""
                UPDATE agent_execution SET heartbeat_at_ms = ?
                WHERE id = ? AND finished_at_ms IS NULL
                    AND heartbeat_at_ms <= ?
                """, statement -> {
                    statement.setLong(1, at.toEpochMilli());
                    statement.setString(2, executionId);
                    statement.setLong(3, at.toEpochMilli());
                }, "execution heartbeat");
    }

    @Override
    public void providerSession(
            String executionId,
            String provider,
            String providerSessionId)
    {
        requireText(executionId, "executionId");
        requireText(provider, "provider");
        requireText(providerSessionId, "providerSessionId");
        updateRequired("""
                UPDATE agent_execution
                SET provider = ?, provider_session_id = ?
                WHERE id = ? AND finished_at_ms IS NULL
                    AND (provider IS NULL OR provider = ?)
                    AND (provider_session_id IS NULL OR provider_session_id = ?)
                """, statement -> {
                    statement.setString(1, provider);
                    statement.setString(2, providerSessionId);
                    statement.setString(3, executionId);
                    statement.setString(4, provider);
                    statement.setString(5, providerSessionId);
                }, "provider session");
    }

    @Override
    public void processStarted(
            String executionId,
            long processPid,
            String logReference)
    {
        requireText(executionId, "executionId");
        if (processPid < 1 || (logReference != null && logReference.isBlank())) {
            throw new IllegalArgumentException("process evidence is invalid");
        }
        updateRequired("""
                UPDATE agent_execution SET process_pid = ?, log_ref = ?, status = 'RUNNING'
                WHERE id = ? AND finished_at_ms IS NULL
                    AND (process_pid IS NULL OR process_pid = ?)
                    AND (log_ref IS NULL OR log_ref IS ?)
                """, statement -> {
                    statement.setLong(1, processPid);
                    statement.setString(2, logReference);
                    statement.setString(3, executionId);
                    statement.setLong(4, processPid);
                    statement.setString(5, logReference);
                }, "process start");
    }

    @Override
    public void appendLog(
            String executionId,
            long sequence,
            String payloadJson,
            Instant createdAt)
    {
        requireText(executionId, "executionId");
        requireNonNull(payloadJson, "payloadJson is null");
        requireNonNull(createdAt, "createdAt is null");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence is negative");
        }
        updateRequired("""
                INSERT INTO agent_execution_log(execution_id, seq, payload, created_at_ms)
                SELECT id, ?, ?, ? FROM agent_execution
                WHERE id = ? AND finished_at_ms IS NULL
                """, statement -> {
                    statement.setLong(1, sequence);
                    statement.setString(2, payloadJson);
                    statement.setLong(3, createdAt.toEpochMilli());
                    statement.setString(4, executionId);
                }, "execution log");
    }

    @Override
    public void recordUsage(
            String executionId,
            long inputTokens,
            long outputTokens,
            long costUsdMilli)
    {
        requireText(executionId, "executionId");
        if (inputTokens < 0 || outputTokens < 0 || costUsdMilli < 0) {
            throw new IllegalArgumentException("usage values are negative");
        }
        updateRequired("""
                UPDATE agent_execution
                SET tokens_in = ?, tokens_out = ?, cost_usd_milli = ?
                WHERE id = ? AND finished_at_ms IS NULL
                """, statement -> {
                    statement.setLong(1, inputTokens);
                    statement.setLong(2, outputTokens);
                    statement.setLong(3, costUsdMilli);
                    statement.setString(4, executionId);
                }, "execution usage");
    }

    @Override
    public void finish(
            String executionId,
            DispatchTicket.DispatchResult result,
            String failure,
            Instant finishedAt)
    {
        requireText(executionId, "executionId");
        requireNonNull(finishedAt, "finishedAt is null");
        String normalizedFailure = normalizeFailure(failure);
        if (result == null && normalizedFailure == null) {
            throw new IllegalArgumentException(
                    "execution without a result requires a failure");
        }
        SqliteTransactions.immediate(dataSource, transactionConnection, connection -> {
            FinishTarget target = finishTarget(connection, executionId)
                    .orElseThrow(() -> new IllegalStateException(
                            "execution finish did not match one live execution"));
            if (target.startedAt().isAfter(finishedAt)) {
                throw new IllegalArgumentException(
                        "execution finish precedes its start");
            }
            boolean currentAttempt = target.executionAttempt()
                    == target.ticketAttempts();
            if (currentAttempt && target.pendingResult() != null
                    && !target.pendingResult().equals(result)) {
                throw new IllegalArgumentException(
                        "execution result does not match the ticket's durable pending result");
            }
            boolean durableCurrentResult = currentAttempt
                    && target.pendingResult() != null
                    && target.pendingResult().equals(result);
            boolean deliveredCurrentResult = result != null && currentAttempt
                    && target.pendingResult() == null
                    && target.terminalStateAccepts(result);
            if (result != null && !durableCurrentResult
                    && !deliveredCurrentResult && normalizedFailure == null) {
                throw new IllegalArgumentException(
                        "an uncommitted execution result requires a failure");
            }
            updateRequired("""
                    UPDATE agent_execution
                    SET status = ?, heartbeat_at_ms = ?, finished_at_ms = ?,
                        raw_result = ?,
                        error_message = CASE
                            WHEN error_message IS NULL THEN ?
                            WHEN ? IS NULL THEN error_message
                            ELSE error_message || char(10) || ?
                        END
                    WHERE id = ? AND finished_at_ms IS NULL
                        AND started_at_ms <= ?
                    """, statement -> {
                        statement.setString(1, status(result));
                        statement.setLong(2, finishedAt.toEpochMilli());
                        statement.setLong(3, finishedAt.toEpochMilli());
                        statement.setString(4, json(result));
                        statement.setString(5, normalizedFailure);
                        statement.setString(6, normalizedFailure);
                        statement.setString(7, normalizedFailure);
                        statement.setString(8, executionId);
                        statement.setLong(9, finishedAt.toEpochMilli());
                    }, "execution finish");
            return null;
        });
    }

    @Override
    public void infrastructureFailure(
            String ticketId,
            String failure,
            Instant recordedAt)
    {
        if (ticketId == null) {
            return;
        }
        requireText(ticketId, "ticketId");
        String message = requireText(failure, "failure");
        requireNonNull(recordedAt, "recordedAt is null");
        String evidence = "infrastructure@" + recordedAt.toEpochMilli() + ": " + message;
        SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE agent_execution
                            SET error_class = COALESCE(error_class, 'INFRASTRUCTURE'),
                                error_message = CASE
                                    WHEN error_message IS NULL THEN ?
                                    ELSE error_message || char(10) || ?
                                END
                            WHERE id = (
                                SELECT id FROM agent_execution
                                WHERE ticket_id = ?
                                ORDER BY infrastructure_attempt DESC
                                LIMIT 1)
                            """)) {
                        statement.setString(1, evidence);
                        statement.setString(2, evidence);
                        statement.setString(3, ticketId);
                        statement.executeUpdate();
                    }
                    return null;
                });
    }

    private Optional<FinishTarget> finishTarget(
            Connection connection,
            String executionId)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.started_at_ms,
                    e.infrastructure_attempt AS execution_attempt,
                    d.infrastructure_attempts AS ticket_attempts,
                    d.status AS ticket_status,
                    d.pending_result_outcome,
                    d.pending_result_payload, d.pending_result_evidence,
                    d.pending_result_error, d.pending_result_task_epoch,
                    d.pending_result_stage_id, d.pending_result_stage_generation,
                    d.pending_result_operation_id, d.pending_result_attempt,
                    d.pending_result_expected_code_fingerprint,
                    d.pending_result_expected_head_sha,
                    d.pending_result_expected_base_sha
                FROM agent_execution e
                JOIN dispatch_ticket d ON d.id = e.ticket_id
                WHERE e.id = ? AND e.finished_at_ms IS NULL
                """)) {
            statement.setString(1, executionId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String outcome = rows.getString("pending_result_outcome");
                DispatchTicket.DispatchResult pending = outcome == null
                        ? null
                        : new DispatchTicket.DispatchResult(
                                new DispatchTicket.OperationFence(
                                        nullableLong(rows, "pending_result_task_epoch"),
                                        rows.getString("pending_result_stage_id"),
                                        nullableLong(rows, "pending_result_stage_generation"),
                                        rows.getString("pending_result_operation_id"),
                                        rows.getInt("pending_result_attempt"),
                                        rows.getString(
                                                "pending_result_expected_code_fingerprint"),
                                        rows.getString("pending_result_expected_head_sha"),
                                        rows.getString("pending_result_expected_base_sha")),
                                DispatchTicket.Outcome.valueOf(outcome),
                                rows.getString("pending_result_payload"),
                                rows.getString("pending_result_evidence"),
                                rows.getString("pending_result_error"));
                return Optional.of(new FinishTarget(
                        Instant.ofEpochMilli(rows.getLong("started_at_ms")),
                        rows.getInt("execution_attempt"),
                        rows.getInt("ticket_attempts"),
                        rows.getString("ticket_status"),
                        pending));
            }
        }
    }

    private void updateRequired(
            String sql,
            StatementBinder binder,
            String action)
    {
        int updated = SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        binder.bind(statement);
                        return statement.executeUpdate();
                    }
                });
        if (updated != 1) {
            throw new IllegalStateException(action + " did not match one live execution");
        }
    }

    private String json(DispatchTicket.DispatchResult result)
    {
        if (result == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(result);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("execution result is not serializable", e);
        }
    }

    private static String status(DispatchTicket.DispatchResult result)
    {
        if (result == null) {
            return "FAILED";
        }
        return switch (result.outcome()) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELED -> "CANCELED";
            case INDETERMINATE -> "UNKNOWN";
        };
    }

    private static String normalizeFailure(String failure)
    {
        if (failure == null) {
            return null;
        }
        if (failure.isBlank()) {
            throw new IllegalArgumentException("failure is blank");
        }
        return failure;
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    @FunctionalInterface
    private interface StatementBinder
    {
        void bind(PreparedStatement statement)
                throws SQLException;
    }

    private record FinishTarget(
            Instant startedAt,
            int executionAttempt,
            int ticketAttempts,
            String ticketStatus,
            DispatchTicket.DispatchResult pendingResult)
    {
        boolean terminalStateAccepts(DispatchTicket.DispatchResult result)
        {
            return switch (result.outcome()) {
                case SUCCEEDED -> ticketStatus.equals("SUCCEEDED");
                case FAILED, INDETERMINATE -> ticketStatus.equals("FAILED");
                case CANCELED -> ticketStatus.equals("CANCELED");
            };
        }
    }
}
