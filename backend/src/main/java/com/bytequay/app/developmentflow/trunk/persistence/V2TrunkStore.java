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
package com.bytequay.app.developmentflow.trunk.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static java.util.Objects.requireNonNull;

/** Spring-transaction-bound persistence for the Trunk aggregate. */
@Component
final class V2TrunkStore
        implements TrunkManager.Store
{
    private final JdbcTemplate jdbc;

    V2TrunkStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<TrunkManager.State> findById(String trunkId)
    {
        return jdbc.query("""
                SELECT id, lifecycle_state, aggregate_version
                FROM threads
                WHERE id = ? AND turn_version = 'V2'
                """,
                (rs, row) -> new TrunkManager.State(
                        rs.getString("id"),
                        TrunkLifecycle.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("aggregate_version")),
                trunkId).stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.CommandReceipt> findCommandResult(
            String trunkId, String commandId)
    {
        return jdbc.query("""
                SELECT cause, actor, disposition, expected_version,
                       returned_lifecycle, returned_version
                FROM trunk_command_receipt
                WHERE trunk_id = ? AND command_id = ?
                """,
                (rs, row) -> new TrunkManager.CommandReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(rs.getString("returned_lifecycle")),
                                rs.getLong("returned_version")),
                        rs.getString("cause"),
                        rs.getString("actor"),
                        rs.getLong("expected_version"),
                        CommandResult.Disposition.valueOf(rs.getString("disposition"))),
                trunkId, commandId).stream().findFirst();
    }

    @Override
    public TrunkManager.State commit(
            String commandId,
            String cause,
            String actor,
            long expectedVersion,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        if (!expected.id().equals(updated.id())
                || expected.version() != expectedVersion
                || updated.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Trunk commit fence is inconsistent");
        }
        int changed = jdbc.update("""
                UPDATE threads
                SET lifecycle_state = ?, aggregate_version = ?
                WHERE id = ?
                  AND turn_version = 'V2'
                  AND lifecycle_state = ?
                  AND aggregate_version = ?
                """,
                updated.lifecycle().name(), updated.version(), expected.id(),
                expected.lifecycle().name(), expectedVersion);
        if (changed != 1) {
            throw concurrent("Trunk changed before commit: " + expected.id());
        }

        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), expected.id(), commandId, expected.lifecycle().name(),
                updated.lifecycle().name(), updated.version(), cause, actor, now);
        jdbc.update("""
                INSERT INTO trunk_command_receipt(
                    id, trunk_id, command_id, cause, actor, disposition,
                    expected_version, returned_lifecycle, returned_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?)
                """,
                id(), expected.id(), commandId, cause, actor, expectedVersion,
                updated.lifecycle().name(), updated.version(), now);
        return updated;
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Trunk writes require the command transaction");
        }
    }

    private static CommandRejectedException concurrent(String message)
    {
        return new CommandRejectedException(CONCURRENT_UPDATE, message);
    }

    private static String id()
    {
        return UUID.randomUUID().toString();
    }
}
