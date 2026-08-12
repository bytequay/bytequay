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
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCapacityLeaseStore
        implements CapacityManager.CapacityLeaseStore
{
    private static final int SQLITE_CONSTRAINT = 19;

    private final DataSource dataSource;
    private final ThreadLocal<Connection> admissionConnection = new ThreadLocal<>();

    public SqliteCapacityLeaseStore(DataSource dataSource)
    {
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
    }

    @Override
    public <T> T inAdmissionTransaction(
            Function<CapacityManager.CapacityLeaseStore, T> work)
    {
        requireNonNull(work, "work is null");
        return SqliteTransactions.immediate(
                dataSource, admissionConnection, ignored -> work.apply(this));
    }

    @Override
    public CapacityManager.CapacityPolicySnapshot policySnapshot(
            CapacityManager.CapacityScope scope)
    {
        requireNonNull(scope, "scope is null");
        Connection connection = admissionConnection.get();
        if (connection == null) {
            throw new IllegalStateException(
                    "capacity policy snapshot requires an admission transaction");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT workspace.settings_json,
                       trunk_settings.max_running_tasks
                FROM (SELECT 1) seed
                LEFT JOIN workspace_settings workspace
                  ON workspace.workspace_id = ?
                LEFT JOIN threads trunk
                  ON trunk.id = ? AND trunk.workspace_id = ?
                LEFT JOIN thread_settings trunk_settings
                  ON trunk_settings.thread_id = trunk.id
                """)) {
            statement.setString(1, scope.workspaceId());
            statement.setString(2, scope.trunkId());
            statement.setString(3, scope.workspaceId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "capacity policy snapshot query returned no row");
                }
                return new CapacityManager.CapacityPolicySnapshot(
                        result.getString("settings_json"),
                        nullableInt(result, "max_running_tasks"));
            }
        }
        catch (SQLException e) {
            throw SqliteTransactions.failure("Capacity policy snapshot failed", e);
        }
    }

    @Override
    public List<CapacityManager.CapacityLease> listActive(Instant now)
    {
        requireNonNull(now, "now is null");
        return query("""
                SELECT lease.* FROM capacity_lease lease
                JOIN dispatch_ticket d ON d.id = lease.ticket_id
                WHERE lease.released_at_ms IS NULL AND lease.expires_at_ms > ?
                    %s
                ORDER BY lease.acquired_at_ms, lease.id
                """.formatted(
                        SqliteDispatchTicketStore.executableTicketPredicate("d")),
                statement -> statement.setLong(1, now.toEpochMilli()));
    }

    @Override
    public Optional<CapacityManager.CapacityLease> findActiveByOperation(
            String operationId,
            Instant now)
    {
        requireText(operationId, "operationId");
        requireNonNull(now, "now is null");
        return one("""
                SELECT lease.* FROM capacity_lease lease
                JOIN dispatch_ticket d ON d.id = lease.ticket_id
                WHERE lease.operation_id = ? AND lease.released_at_ms IS NULL
                    AND lease.expires_at_ms > ?
                    %s
                """.formatted(
                        SqliteDispatchTicketStore.executableTicketPredicate("d")), statement -> {
                    statement.setString(1, operationId);
                    statement.setLong(2, now.toEpochMilli());
                });
    }

    @Override
    public Optional<CapacityManager.CapacityLease> findById(String leaseId)
    {
        requireText(leaseId, "leaseId");
        return one("SELECT * FROM capacity_lease WHERE id = ?",
                statement -> statement.setString(1, leaseId));
    }

    @Override
    public Optional<CapacityManager.CapacityLease> create(
            CapacityManager.CapacityLeaseDraft draft)
    {
        requireNonNull(draft, "draft is null");
        Connection connection = admissionConnection.get();
        if (connection == null) {
            throw new IllegalStateException(
                    "capacity lease creation requires an admission transaction");
        }
        CapacityManager.CapacityRequest request = draft.request();
        Long fencingToken = request.writerRequired()
                ? nextWriterToken(connection, request.scope().taskId())
                : null;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required, workspace_id,
                    trunk_id, task_id, task_epoch, holder, fencing_token,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms,
                    released_at_ms, release_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                """)) {
            int index = 1;
            statement.setString(index++, draft.id());
            statement.setString(index++, draft.ticketId());
            statement.setString(index++, request.operationId());
            statement.setString(index++, request.source().name());
            statement.setInt(index++, CapacityManager.CapacityLane.toMask(request.lanes()));
            statement.setInt(index++, bool(request.trunkControl()));
            statement.setInt(index++, bool(request.exclusiveTask()));
            statement.setInt(index++, bool(request.writerRequired()));
            statement.setString(index++, request.scope().workspaceId());
            statement.setString(index++, request.scope().trunkId());
            statement.setString(index++, request.scope().taskId());
            setLong(statement, index++, request.scope().taskEpoch());
            statement.setString(index++, draft.leaseOwner());
            setLong(statement, index++, fencingToken);
            statement.setLong(index++, draft.acquiredAt().toEpochMilli());
            statement.setLong(index++, draft.acquiredAt().toEpochMilli());
            statement.setLong(index, draft.expiresAt().toEpochMilli());
            statement.executeUpdate();
        }
        catch (SQLException e) {
            if (isExpectedAdmissionContention(e)) {
                return Optional.empty();
            }
            throw SqliteTransactions.failure("Capacity lease insert failed", e);
        }
        return Optional.of(new CapacityManager.CapacityLease(
                draft.id(), draft.ticketId(), request.operationId(), request.source(),
                request.lanes(), request.scope(), request.trunkControl(),
                request.exclusiveTask(), request.writerRequired(), draft.leaseOwner(),
                fencingToken, draft.acquiredAt(), draft.acquiredAt(), draft.expiresAt(),
                null, null));
    }

    @Override
    public Optional<CapacityManager.CapacityLease> heartbeat(
            String leaseId,
            String leaseOwner,
            Instant heartbeatAt,
            Instant expiresAt)
    {
        requireText(leaseId, "leaseId");
        requireText(leaseOwner, "leaseOwner");
        requireNonNull(heartbeatAt, "heartbeatAt is null");
        requireNonNull(expiresAt, "expiresAt is null");
        if (!expiresAt.isAfter(heartbeatAt)) {
            throw new IllegalArgumentException("lease expiry must follow heartbeat");
        }
        return SqliteTransactions.withConnection(
                dataSource,
                admissionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE capacity_lease
                            SET heartbeat_at_ms = ?, expires_at_ms = ?
                            WHERE id = ? AND holder = ? AND released_at_ms IS NULL
                                AND expires_at_ms > ? AND heartbeat_at_ms <= ?
                                AND expires_at_ms <= ?
                            RETURNING *
                            """)) {
                        statement.setLong(1, heartbeatAt.toEpochMilli());
                        statement.setLong(2, expiresAt.toEpochMilli());
                        statement.setString(3, leaseId);
                        statement.setString(4, leaseOwner);
                        statement.setLong(5, heartbeatAt.toEpochMilli());
                        statement.setLong(6, heartbeatAt.toEpochMilli());
                        statement.setLong(7, expiresAt.toEpochMilli());
                        try (ResultSet result = statement.executeQuery()) {
                            return result.next()
                                    ? Optional.of(map(result))
                                    : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public boolean release(String leaseId, String leaseOwner, Instant releasedAt)
    {
        requireText(leaseId, "leaseId");
        requireText(leaseOwner, "leaseOwner");
        requireNonNull(releasedAt, "releasedAt is null");
        int updated = update("""
                UPDATE capacity_lease
                SET released_at_ms = ?, release_reason = 'RELEASED'
                WHERE id = ? AND holder = ? AND released_at_ms IS NULL
                """, statement -> {
                    statement.setLong(1, releasedAt.toEpochMilli());
                    statement.setString(2, leaseId);
                    statement.setString(3, leaseOwner);
                });
        if (updated == 1) {
            return true;
        }
        return findById(leaseId)
                .map(lease -> lease.releasedAt() != null)
                .orElse(true);
    }

    @Override
    public List<CapacityManager.CapacityLease> expire(Instant now)
    {
        requireNonNull(now, "now is null");
        return SqliteTransactions.immediate(dataSource, admissionConnection, connection -> {
            List<CapacityManager.CapacityLease> expired = query(
                    connection,
                    """
                    SELECT lease.* FROM capacity_lease lease
                    JOIN dispatch_ticket d ON d.id = lease.ticket_id
                    WHERE lease.released_at_ms IS NULL
                        AND lease.expires_at_ms <= ?
                        %s
                    ORDER BY lease.expires_at_ms, lease.id
                    """.formatted(
                            SqliteDispatchTicketStore.executableTicketPredicate("d")),
                    statement -> statement.setLong(1, now.toEpochMilli()));
            List<CapacityManager.CapacityLease> updated = new ArrayList<>();
            for (CapacityManager.CapacityLease lease : expired) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE capacity_lease
                        SET released_at_ms = ?, release_reason = 'EXPIRED'
                        WHERE id = ? AND released_at_ms IS NULL AND expires_at_ms <= ?
                        """)) {
                    statement.setLong(1, now.toEpochMilli());
                    statement.setString(2, lease.id());
                    statement.setLong(3, now.toEpochMilli());
                    if (statement.executeUpdate() == 1) {
                        updated.add(copyReleased(lease, now, "EXPIRED"));
                    }
                }
            }
            return List.copyOf(updated);
        });
    }

    private Long nextWriterToken(Connection connection, String taskId)
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(fencing_token), 0) + 1
                FROM capacity_lease WHERE task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("writer fencing token query returned no row");
                }
                return result.getLong(1);
            }
        }
        catch (SQLException e) {
            throw SqliteTransactions.failure("Writer fencing token query failed", e);
        }
    }

    private Optional<CapacityManager.CapacityLease> one(
            String sql, StatementBinder binder)
    {
        List<CapacityManager.CapacityLease> found = query(sql, binder);
        if (found.size() > 1) {
            throw new IllegalStateException("capacity lease query returned multiple rows");
        }
        return found.stream().findFirst();
    }

    private List<CapacityManager.CapacityLease> query(
            String sql, StatementBinder binder)
    {
        return SqliteTransactions.withConnection(
                dataSource,
                admissionConnection,
                connection -> query(connection, sql, binder));
    }

    private static List<CapacityManager.CapacityLease> query(
            Connection connection,
            String sql,
            StatementBinder binder)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<CapacityManager.CapacityLease> leases = new ArrayList<>();
                while (result.next()) {
                    leases.add(map(result));
                }
                return List.copyOf(leases);
            }
        }
    }

    private int update(String sql, StatementBinder binder)
    {
        return SqliteTransactions.withConnection(dataSource, admissionConnection, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                return statement.executeUpdate();
            }
        });
    }

    private static CapacityManager.CapacityLease map(ResultSet result)
            throws SQLException
    {
        return new CapacityManager.CapacityLease(
                result.getString("id"),
                result.getString("ticket_id"),
                result.getString("operation_id"),
                CapacityManager.WorkflowSource.valueOf(result.getString("workflow_source")),
                CapacityManager.CapacityLane.fromMask(result.getInt("lane_mask")),
                new CapacityManager.CapacityScope(
                        result.getString("workspace_id"),
                        result.getString("trunk_id"),
                        result.getString("task_id"),
                        nullableLong(result, "task_epoch")),
                result.getInt("trunk_control") != 0,
                result.getInt("exclusive_task") != 0,
                result.getInt("writer_required") != 0,
                result.getString("holder"),
                nullableLong(result, "fencing_token"),
                instant(result, "acquired_at_ms"),
                instant(result, "heartbeat_at_ms"),
                instant(result, "expires_at_ms"),
                nullableInstant(result, "released_at_ms"),
                result.getString("release_reason"));
    }

    private static CapacityManager.CapacityLease copyReleased(
            CapacityManager.CapacityLease lease,
            Instant releasedAt,
            String reason)
    {
        return new CapacityManager.CapacityLease(
                lease.id(), lease.ticketId(), lease.operationId(), lease.source(),
                lease.lanes(), lease.scope(), lease.trunkControl(), lease.exclusiveTask(),
                lease.writerRequired(), lease.leaseOwner(), lease.writerFencingToken(),
                lease.acquiredAt(), lease.heartbeatAt(), lease.expiresAt(),
                releasedAt, reason);
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet result, String column)
            throws SQLException
    {
        int value = result.getInt(column);
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

    private static int bool(boolean value)
    {
        return value ? 1 : 0;
    }

    private static boolean isExpectedAdmissionContention(SQLException failure)
    {
        if (failure.getErrorCode() != SQLITE_CONSTRAINT) {
            return false;
        }
        String message = failure.getMessage();
        return message != null && (
                message.contains("UNIQUE constraint failed: capacity_lease.operation_id")
                        || message.contains("UNIQUE constraint failed: capacity_lease.ticket_id")
                        || message.contains("UNIQUE constraint failed: capacity_lease.task_id"));
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    @FunctionalInterface
    private interface StatementBinder
    {
        void bind(PreparedStatement statement)
                throws SQLException;
    }
}
