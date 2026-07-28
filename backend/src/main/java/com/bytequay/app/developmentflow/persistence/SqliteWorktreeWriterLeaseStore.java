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

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** SQLite-backed exact V2 worktree writer fence. */
@Repository
public class SqliteWorktreeWriterLeaseStore
        implements WorktreeWriterLeaseManager.Store
{
    private static final int SQLITE_CONSTRAINT = 19;
    private static final String V2_AGENT_KIND = "V2_OPERATION";

    private final DataSource dataSource;
    private final ThreadLocal<Connection> transactionConnection = new ThreadLocal<>();

    public SqliteWorktreeWriterLeaseStore(DataSource dataSource)
    {
        this.dataSource = requireNonNull(dataSource, "dataSource is null");
    }

    @Override
    public Optional<WorktreeWriterLeaseManager.Lease> tryAcquire(
            WorktreeWriterLeaseManager.Lease requested,
            Instant now)
    {
        requireNonNull(requested, "requested is null");
        requireNonNull(now, "now is null");
        return SqliteTransactions.immediate(dataSource, transactionConnection, connection -> {
            reapInvalidV2Leases(connection, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO worktree_leases(
                        worktree_path, task_id, agent_kind, holder_pid,
                        acquired_at_ms, expires_at_ms, workflow_version,
                        operation_id, task_epoch, fencing_token, lease_owner)
                    VALUES (?, ?, ?, NULL, ?, ?, 'V2', ?, ?, ?, ?)
                    """)) {
                bindInsert(statement, requested);
                statement.executeUpdate();
                return Optional.of(requested);
            }
            catch (SQLException failure) {
                if (failure.getErrorCode() == SQLITE_CONSTRAINT
                        && hasCompetingLease(connection, requested)) {
                    return Optional.empty();
                }
                throw failure;
            }
        });
    }

    @Override
    public Optional<WorktreeWriterLeaseManager.Lease> findExact(
            WorktreeWriterLeaseManager.Lease expected,
            Instant now)
    {
        requireNonNull(expected, "expected is null");
        requireNonNull(now, "now is null");
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> queryExact(connection, expected, now));
    }

    @Override
    public Optional<WorktreeWriterLeaseManager.Lease> heartbeat(
            WorktreeWriterLeaseManager.Lease expected,
            Instant heartbeatAt,
            Instant expiresAt)
    {
        requireNonNull(expected, "expected is null");
        requireNonNull(heartbeatAt, "heartbeatAt is null");
        requireNonNull(expiresAt, "expiresAt is null");
        if (!expiresAt.isAfter(heartbeatAt)) {
            throw new IllegalArgumentException("writer lease expiry must follow heartbeat");
        }
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE worktree_leases
                            SET expires_at_ms = ?
                            WHERE workflow_version = 'V2'
                              AND worktree_path = ? AND task_id = ?
                              AND operation_id = ? AND task_epoch = ?
                              AND fencing_token = ? AND lease_owner = ?
                              AND acquired_at_ms = ?
                              AND expires_at_ms > ? AND expires_at_ms <= ?
                            RETURNING worktree_path, task_id, operation_id,
                                task_epoch, fencing_token, lease_owner,
                                acquired_at_ms, expires_at_ms
                            """)) {
                        statement.setLong(1, expiresAt.toEpochMilli());
                        bindIdentity(statement, 2, expected);
                        statement.setLong(9, heartbeatAt.toEpochMilli());
                        statement.setLong(10, expiresAt.toEpochMilli());
                        try (ResultSet result = statement.executeQuery()) {
                            return result.next()
                                    ? Optional.of(map(result))
                                    : Optional.empty();
                        }
                    }
                });
    }

    @Override
    public boolean release(
            WorktreeWriterLeaseManager.Lease expected,
            Instant releasedAt)
    {
        requireNonNull(expected, "expected is null");
        requireNonNull(releasedAt, "releasedAt is null");
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM worktree_leases
                            WHERE workflow_version = 'V2'
                              AND worktree_path = ? AND task_id = ?
                              AND operation_id = ? AND task_epoch = ?
                              AND fencing_token = ? AND lease_owner = ?
                              AND acquired_at_ms = ?
                            """)) {
                        bindIdentity(statement, 1, expected);
                        return statement.executeUpdate() == 1;
                    }
                });
    }

    private static void reapInvalidV2Leases(Connection connection, Instant now)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM worktree_leases AS lease
                WHERE lease.workflow_version = 'V2'
                  AND (lease.expires_at_ms <= ? OR NOT EXISTS (
                      SELECT 1 FROM capacity_lease AS capacity
                      WHERE capacity.workflow_source = 'V2'
                        AND capacity.operation_id = lease.operation_id
                        AND capacity.task_id = lease.task_id
                        AND capacity.task_epoch = lease.task_epoch
                        AND capacity.fencing_token = lease.fencing_token
                        AND capacity.holder = lease.lease_owner
                        AND capacity.writer_required = 1
                        AND capacity.released_at_ms IS NULL
                        AND capacity.expires_at_ms > ?))
                """)) {
            statement.setLong(1, now.toEpochMilli());
            statement.setLong(2, now.toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static Optional<WorktreeWriterLeaseManager.Lease> queryExact(
            Connection connection,
            WorktreeWriterLeaseManager.Lease expected,
            Instant now)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT worktree_path, task_id, operation_id, task_epoch,
                    fencing_token, lease_owner, acquired_at_ms, expires_at_ms
                FROM worktree_leases
                WHERE workflow_version = 'V2'
                  AND worktree_path = ? AND task_id = ?
                  AND operation_id = ? AND task_epoch = ?
                  AND fencing_token = ? AND lease_owner = ?
                  AND acquired_at_ms = ? AND expires_at_ms > ?
                """)) {
            bindIdentity(statement, 1, expected);
            statement.setLong(8, now.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private static boolean hasCompetingLease(
            Connection connection,
            WorktreeWriterLeaseManager.Lease requested)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM worktree_leases
                WHERE worktree_path = ?
                   OR (workflow_version = 'V2' AND task_id = ?)
                LIMIT 1
                """)) {
            statement.setString(1, requested.worktreePath());
            statement.setString(2, requested.taskId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void bindInsert(
            PreparedStatement statement,
            WorktreeWriterLeaseManager.Lease lease)
            throws SQLException
    {
        statement.setString(1, lease.worktreePath());
        statement.setString(2, lease.taskId());
        statement.setString(3, V2_AGENT_KIND);
        statement.setLong(4, lease.acquiredAt().toEpochMilli());
        statement.setLong(5, lease.expiresAt().toEpochMilli());
        statement.setString(6, lease.operationId());
        statement.setLong(7, lease.taskEpoch());
        statement.setLong(8, lease.fencingToken());
        statement.setString(9, lease.leaseOwner());
    }

    private static void bindIdentity(
            PreparedStatement statement,
            int first,
            WorktreeWriterLeaseManager.Lease lease)
            throws SQLException
    {
        statement.setString(first, lease.worktreePath());
        statement.setString(first + 1, lease.taskId());
        statement.setString(first + 2, lease.operationId());
        statement.setLong(first + 3, lease.taskEpoch());
        statement.setLong(first + 4, lease.fencingToken());
        statement.setString(first + 5, lease.leaseOwner());
        statement.setLong(first + 6, lease.acquiredAt().toEpochMilli());
    }

    private static WorktreeWriterLeaseManager.Lease map(ResultSet result)
            throws SQLException
    {
        return new WorktreeWriterLeaseManager.Lease(
                result.getString("worktree_path"),
                result.getString("task_id"),
                result.getString("operation_id"),
                result.getLong("task_epoch"),
                result.getLong("fencing_token"),
                result.getString("lease_owner"),
                Instant.ofEpochMilli(result.getLong("acquired_at_ms")),
                Instant.ofEpochMilli(result.getLong("expires_at_ms")));
    }
}
