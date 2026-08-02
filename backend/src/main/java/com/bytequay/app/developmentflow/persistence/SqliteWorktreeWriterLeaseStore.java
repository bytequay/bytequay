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
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.WorktreeQuarantine;
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
            if (queryOpenQuarantine(
                    connection, requested.taskId(), requested.worktreePath())
                    .isPresent()) {
                return Optional.empty();
            }
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

    @Override
    public Optional<WorktreeQuarantine> findOpenQuarantine(
            String taskId, String worktreePath)
    {
        requireText(taskId, "taskId");
        requireText(worktreePath, "worktreePath");
        return SqliteTransactions.withConnection(
                dataSource,
                transactionConnection,
                connection -> queryOpenQuarantine(
                        connection, taskId, worktreePath));
    }

    @Override
    public WorktreeQuarantine openQuarantine(
            WorktreeWriterLeaseManager.Lease expected,
            WorktreeQuarantine requested,
            Instant openedAt)
    {
        requireNonNull(expected, "expected is null");
        requireNonNull(requested, "requested is null");
        requireNonNull(openedAt, "openedAt is null");
        if (!requested.openedAt().equals(openedAt)
                || !requested.taskId().equals(expected.taskId())
                || !requested.sourceOperationId().equals(
                        expected.operationId())
                || !requested.worktreePath().equals(expected.worktreePath())) {
            throw new IllegalArgumentException(
                    "worktree quarantine differs from its writer lease");
        }
        return SqliteTransactions.immediate(
                dataSource, transactionConnection, connection -> {
                    if (queryExact(connection, expected, openedAt).isEmpty()) {
                        throw new WorktreeWriterLeaseManager
                                .StaleWriterLeaseException(
                                "worktree writer lease is no longer exact");
                    }
                    Optional<WorktreeQuarantine> duplicate =
                            queryQuarantineBySource(
                                    connection, requested.sourceOperationId());
                    if (duplicate.isPresent()) {
                        WorktreeQuarantine exact = duplicate.orElseThrow();
                        if (!exact.equals(requested)) {
                            throw new IllegalStateException(
                                    "worktree quarantine evidence changed on replay");
                        }
                        return exact;
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO agent_turn_worktree_quarantine_v318(
                                id, task_id, stage_id, source_operation_id,
                                worktree_path, expected_branch_name,
                                expected_code_fingerprint, expected_head_sha,
                                observed_branch_name, observed_head_sha,
                                observed_clean, observed_code_fingerprint,
                                probe_error, reason, status, opened_at_ms)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                'OPEN', ?)
                            """)) {
                        statement.setString(1, requested.id());
                        statement.setString(2, requested.taskId());
                        statement.setString(3, requested.stageId());
                        statement.setString(4, requested.sourceOperationId());
                        statement.setString(5, requested.worktreePath());
                        statement.setString(6, requested.expectedBranchName());
                        statement.setString(
                                7, requested.expectedCodeFingerprint());
                        statement.setString(8, requested.expectedHeadSha());
                        statement.setString(9, requested.observedBranchName());
                        statement.setString(10, requested.observedHeadSha());
                        if (requested.observedClean() == null) {
                            statement.setObject(11, null);
                        }
                        else {
                            statement.setInt(
                                    11, requested.observedClean() ? 1 : 0);
                        }
                        statement.setString(
                                12, requested.observedCodeFingerprint());
                        statement.setString(13, requested.probeError());
                        statement.setString(14, requested.reason());
                        statement.setLong(15, openedAt.toEpochMilli());
                        statement.executeUpdate();
                    }
                    return queryQuarantineBySource(
                            connection, requested.sourceOperationId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "worktree quarantine was not persisted"));
                });
    }

    @Override
    public Optional<WorktreeWriterLeaseManager.Lease> tryAcquireCleanupDisposal(
            WorktreeWriterLeaseManager.Lease requested,
            String quarantineId,
            String cleanupOperationId,
            String cleanupStepId,
            Instant now)
    {
        requireNonNull(requested, "requested is null");
        requireText(quarantineId, "quarantineId");
        requireText(cleanupOperationId, "cleanupOperationId");
        requireText(cleanupStepId, "cleanupStepId");
        requireNonNull(now, "now is null");
        return SqliteTransactions.immediate(
                dataSource, transactionConnection, connection -> {
                    reapInvalidV2Leases(connection, now);
                    if (!isExactCleanupDisposal(
                            connection, requested, quarantineId,
                            cleanupOperationId, cleanupStepId, now)) {
                        throw new IllegalArgumentException(
                                "Cleanup quarantine disposal is not exact");
                    }
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
    public Optional<WorktreeWriterLeaseManager.Lease> tryAcquireQuarantineRepair(
            WorktreeWriterLeaseManager.Lease requested,
            String quarantineId,
            String repairOperationId,
            Instant now)
    {
        requireNonNull(requested, "requested is null");
        requireText(quarantineId, "quarantineId");
        requireText(repairOperationId, "repairOperationId");
        requireNonNull(now, "now is null");
        return SqliteTransactions.immediate(
                dataSource, transactionConnection, connection -> {
                    reapInvalidV2Leases(connection, now);
                    if (!isExactQuarantineRepair(
                            connection, requested, quarantineId,
                            repairOperationId, now)) {
                        throw new IllegalArgumentException(
                                "Worktree quarantine repair is not exact");
                    }
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
    public boolean clearForCleanup(
            WorktreeWriterLeaseManager.Lease expected,
            String quarantineId,
            String cleanupOperationId,
            String cleanupStepId,
            String absenceEvidence,
            Instant clearedAt)
    {
        requireNonNull(expected, "expected is null");
        requireText(quarantineId, "quarantineId");
        requireText(cleanupOperationId, "cleanupOperationId");
        requireText(cleanupStepId, "cleanupStepId");
        requireText(absenceEvidence, "absenceEvidence");
        requireNonNull(clearedAt, "clearedAt is null");
        return SqliteTransactions.immediate(
                dataSource, transactionConnection, connection -> {
                    if (queryExact(connection, expected, clearedAt).isEmpty()
                            || !isExactCleanupDisposal(
                                    connection, expected, quarantineId,
                                    cleanupOperationId, cleanupStepId,
                                    clearedAt)) {
                        return false;
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE agent_turn_worktree_quarantine_v318
                               SET status = 'CLEARED',
                                   cleared_by_cleanup_operation_id = ?,
                                   cleared_by_cleanup_step_id = ?,
                                   cleared_at_ms = ?, clear_evidence = ?
                             WHERE id = ? AND task_id = ?
                               AND worktree_path = ? AND status = 'OPEN'
                            """)) {
                        statement.setString(1, cleanupOperationId);
                        statement.setString(2, cleanupStepId);
                        statement.setLong(3, clearedAt.toEpochMilli());
                        statement.setString(4, absenceEvidence);
                        statement.setString(5, quarantineId);
                        statement.setString(6, expected.taskId());
                        statement.setString(7, expected.worktreePath());
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

    private static Optional<WorktreeQuarantine> queryOpenQuarantine(
            Connection connection, String taskId, String worktreePath)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, stage_id, source_operation_id,
                       worktree_path, expected_branch_name,
                       expected_code_fingerprint, expected_head_sha,
                       observed_branch_name, observed_head_sha, observed_clean,
                       observed_code_fingerprint, probe_error, reason,
                       opened_at_ms
                FROM agent_turn_worktree_quarantine_v318
                WHERE status = 'OPEN'
                  AND (task_id = ? OR worktree_path = ?)
                LIMIT 1
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, worktreePath);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(mapQuarantine(result))
                        : Optional.empty();
            }
        }
    }

    private static Optional<WorktreeQuarantine> queryQuarantineBySource(
            Connection connection, String operationId)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, stage_id, source_operation_id,
                       worktree_path, expected_branch_name,
                       expected_code_fingerprint, expected_head_sha,
                       observed_branch_name, observed_head_sha, observed_clean,
                       observed_code_fingerprint, probe_error, reason,
                       opened_at_ms
                FROM agent_turn_worktree_quarantine_v318
                WHERE source_operation_id = ?
                """)) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(mapQuarantine(result))
                        : Optional.empty();
            }
        }
    }

    private static boolean isExactCleanupDisposal(
            Connection connection,
            WorktreeWriterLeaseManager.Lease requested,
            String quarantineId,
            String cleanupOperationId,
            String cleanupStepId,
            Instant now)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                  FROM agent_turn_worktree_quarantine_v318 quarantine
                  JOIN cleanup_operation operation
                    ON operation.id = ?
                   AND operation.task_id = quarantine.task_id
                  JOIN cleanup_step step
                    ON step.id = ?
                   AND step.cleanup_operation_id = operation.id
                  JOIN dispatch_ticket ticket
                    ON ticket.id = operation.dispatch_ticket_id
                   AND ticket.operation_id = operation.operation_id
                  JOIN capacity_lease capacity
                    ON capacity.id = ticket.capacity_lease_id
                   AND capacity.operation_id = ticket.operation_id
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN task_current_stage current
                    ON current.task_id = task.id
                  JOIN stage owner ON owner.id = operation.cleanup_stage_id
                  JOIN task_code_identity identity
                    ON identity.task_id = operation.task_id
                 WHERE quarantine.id = ? AND quarantine.status = 'OPEN'
                   AND quarantine.task_id = ?
                   AND quarantine.worktree_path = ?
                   AND operation.operation_id = ?
                   AND operation.task_epoch = ?
                   AND operation.status = 'ACTIVE'
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'CLEANING'
                   AND task.epoch = operation.task_epoch
                   AND current.stage_id = operation.cleanup_stage_id
                   AND current.stage_generation = operation.stage_generation
                   AND owner.task_id = operation.task_id
                   AND owner.kind = 'CLEANUP'
                   AND owner.generation = operation.stage_generation
                   AND owner.checkpoint = 'CLEANING'
                   AND owner.completed_at_ms IS NULL
                   AND step.task_id = operation.task_id
                   AND step.task_epoch = operation.task_epoch
                   AND step.cleanup_stage_id = operation.cleanup_stage_id
                   AND step.stage_generation = operation.stage_generation
                   AND step.kind = 'REMOVE_WORKTREE'
                   AND step.status = 'CLAIMED'
                   AND ticket.operation_kind = 'RUN_CLEANUP_OPERATION'
                   AND ticket.async_family = 'CLEANUP'
                   AND ticket.owner_kind = 'STAGE'
                   AND ticket.owner_id = operation.cleanup_stage_id
                   AND ticket.callback_route = 'CLEANUP_OPERATION_RESULT'
                   AND ticket.lane_mask = 256
                   AND ticket.exclusive_task = 1
                   AND ticket.status = 'RUNNING'
                   AND ticket.writer_required = 1
                   AND ticket.task_id = operation.task_id
                   AND ticket.task_epoch = operation.task_epoch
                   AND ticket.stage_id = operation.cleanup_stage_id
                   AND ticket.stage_generation = operation.stage_generation
                   AND capacity.workflow_source = 'V2'
                   AND capacity.task_id = operation.task_id
                   AND capacity.task_epoch = operation.task_epoch
                   AND capacity.writer_required = 1
                   AND capacity.fencing_token = ?
                   AND capacity.holder = ?
                   AND capacity.released_at_ms IS NULL
                   AND capacity.expires_at_ms = ?
                   AND capacity.expires_at_ms > ?
                   AND identity.worktree_path = quarantine.worktree_path
                """)) {
            statement.setString(1, cleanupOperationId);
            statement.setString(2, cleanupStepId);
            statement.setString(3, quarantineId);
            statement.setString(4, requested.taskId());
            statement.setString(5, requested.worktreePath());
            statement.setString(6, requested.operationId());
            statement.setLong(7, requested.taskEpoch());
            statement.setLong(8, requested.fencingToken());
            statement.setString(9, requested.leaseOwner());
            statement.setLong(10, requested.expiresAt().toEpochMilli());
            statement.setLong(11, now.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean isExactQuarantineRepair(
            Connection connection,
            WorktreeWriterLeaseManager.Lease requested,
            String quarantineId,
            String repairOperationId,
            Instant now)
            throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                  FROM worktree_quarantine_repair_operation_v318 operation
                  JOIN agent_turn_worktree_quarantine_v318 quarantine
                    ON quarantine.id = operation.quarantine_id
                  JOIN task_blocker blocker ON blocker.id = operation.blocker_id
                  JOIN dispatch_ticket ticket
                    ON ticket.id = operation.dispatch_ticket_id
                   AND ticket.operation_id = operation.operation_id
                  JOIN capacity_lease capacity
                    ON capacity.id = ticket.capacity_lease_id
                   AND capacity.operation_id = ticket.operation_id
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                  JOIN task_code_identity identity ON identity.task_id = task.id
                 WHERE operation.id = ?
                   AND operation.quarantine_id = ?
                   AND operation.task_id = ?
                   AND operation.task_epoch = ?
                   AND operation.operation_id = ?
                   AND operation.worktree_path = ?
                   AND operation.status = 'DISPATCHED'
                   AND quarantine.status = 'OPEN'
                   AND quarantine.task_id = operation.task_id
                   AND quarantine.source_operation_id =
                       operation.source_operation_id
                   AND quarantine.worktree_path = operation.worktree_path
                   AND quarantine.expected_branch_name =
                       operation.expected_branch_name
                   AND quarantine.expected_code_fingerprint =
                       operation.expected_code_fingerprint
                   AND quarantine.expected_head_sha =
                       operation.expected_head_sha
                   AND blocker.status = 'OPEN'
                   AND blocker.task_id = operation.task_id
                   AND blocker.stage_id = quarantine.stage_id
                   AND blocker.owner_kind = 'OPERATION'
                   AND blocker.owner_id = quarantine.source_operation_id
                   AND blocker.blocker_type =
                       'WORKTREE_RESTORE_QUARANTINED'
                   AND blocker.subject_revision = quarantine.id
                   AND ticket.operation_kind =
                       'REPAIR_QUARANTINED_WORKTREE'
                   AND ticket.async_family = 'LOCAL_GIT'
                   AND ticket.owner_kind = 'TASK'
                   AND ticket.owner_id = operation.task_id
                   AND ticket.callback_route =
                       'WORKTREE_QUARANTINE_REPAIR_RESULT'
                   AND ticket.status = 'RUNNING'
                   AND ticket.writer_required = 1
                   AND capacity.workflow_source = 'V2'
                   AND capacity.task_id = operation.task_id
                   AND capacity.task_epoch = operation.task_epoch
                   AND capacity.writer_required = 1
                   AND capacity.fencing_token = ?
                   AND capacity.holder = ?
                   AND capacity.released_at_ms IS NULL
                   AND capacity.expires_at_ms = ?
                   AND capacity.expires_at_ms > ?
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND task.epoch = operation.task_epoch
                   AND current.stage_id = operation.stage_id
                   AND current.stage_generation = operation.stage_generation
                   AND owner.completed_at_ms IS NULL
                   AND code.code_fingerprint =
                       operation.expected_code_fingerprint
                   AND code.head_sha = operation.expected_head_sha
                   AND code.base_sha = operation.expected_base_sha
                   AND identity.worktree_path = operation.worktree_path
                   AND identity.branch_name = operation.expected_branch_name
                """)) {
            statement.setString(1, repairOperationId);
            statement.setString(2, quarantineId);
            statement.setString(3, requested.taskId());
            statement.setLong(4, requested.taskEpoch());
            statement.setString(5, requested.operationId());
            statement.setString(6, requested.worktreePath());
            statement.setLong(7, requested.fencingToken());
            statement.setString(8, requested.leaseOwner());
            statement.setLong(9, requested.expiresAt().toEpochMilli());
            statement.setLong(10, now.toEpochMilli());
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

    private static WorktreeQuarantine mapQuarantine(ResultSet result)
            throws SQLException
    {
        int clean = result.getInt("observed_clean");
        Boolean observedClean = result.wasNull() ? null : clean != 0;
        return new WorktreeQuarantine(
                result.getString("id"),
                result.getString("task_id"),
                result.getString("stage_id"),
                result.getString("source_operation_id"),
                result.getString("worktree_path"),
                result.getString("expected_branch_name"),
                result.getString("expected_code_fingerprint"),
                result.getString("expected_head_sha"),
                result.getString("observed_branch_name"),
                result.getString("observed_head_sha"),
                observedClean,
                result.getString("observed_code_fingerprint"),
                result.getString("probe_error"),
                result.getString("reason"),
                Instant.ofEpochMilli(result.getLong("opened_at_ms")));
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
