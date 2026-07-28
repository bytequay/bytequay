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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteDispatchWakeStore
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void enqueueJoinsSpringTransactionsAndIsIdempotent()
    {
        SqliteExecutionTestSupport.Database database = database("wake-enqueue.db");
        database.jdbc().update("""
                DELETE FROM outbox WHERE aggregate_kind = 'DISPATCH_TICKET'
                """);
        SqliteDispatchWakeStore wakes = new SqliteDispatchWakeStore(database.jdbc());
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource()));

        transaction.executeWithoutResult(status -> {
            wakes.enqueue("ticket", NOW);
            assertThat(wakeCount(database)).isOne();
            status.setRollbackOnly();
        });
        assertThat(wakeCount(database)).isZero();

        wakes.enqueue("ticket", NOW);
        wakes.enqueue("ticket", NOW);
        assertThat(wakeCount(database)).isOne();
        assertThat(database.jdbc().queryForObject("""
                SELECT aggregate_id || '|' || topic || '|' || payload || '|' || status
                FROM outbox WHERE aggregate_kind = 'DISPATCH_TICKET'
                """, String.class)).isEqualTo(
                "ticket|V2_DISPATCH_TICKET_REQUESTED|ticket|PENDING");

        assertThatThrownBy(() -> wakes.enqueue("ticket", NOW.plusMillis(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting wake");

        DispatchTicket terminal = SqliteExecutionTestSupport.requestedTaskTicket(
                "terminal", "terminal-operation", "workspace", "trunk", "task",
                NOW.plusSeconds(1), VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, terminal);
        database.jdbc().update("""
                DELETE FROM outbox WHERE aggregate_id = 'terminal'
                """);
        database.jdbc().update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'CANCELED', completed_at_ms = ?,
                    delivery_acceptance = 'SUPERSEDED'
                WHERE id = 'terminal'
                """, NOW.plusSeconds(2).toEpochMilli());
        assertThatThrownBy(() -> wakes.enqueue("terminal", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist or has conflicting wake");
    }

    @Test
    void expiredClaimIsRecoveredAfterRestartAndOnlyItsExactAttemptDelivers()
    {
        SqliteExecutionTestSupport.Database database = database("wake-restart.db");
        SqliteDispatchWakeStore first = new SqliteDispatchWakeStore(database.jdbc());
        List<ExecutionPorts.DispatchWakeClaim> claimed = first.claimAvailable(
                "worker-a", NOW.plusSeconds(1), NOW.plusSeconds(11), 10);
        assertThat(claimed).hasSize(1);
        ExecutionPorts.DispatchWakeClaim stale = claimed.getFirst();
        assertThat(stale.attempt()).isOne();
        assertThat(first.claimAvailable(
                "worker-b", NOW.plusSeconds(10), NOW.plusSeconds(20), 10)).isEmpty();

        SqliteDispatchWakeStore restarted = new SqliteDispatchWakeStore(database.jdbc());
        ExecutionPorts.DispatchWakeClaim recovered = restarted.claimAvailable(
                "worker-b", NOW.plusSeconds(11), NOW.plusSeconds(21), 10)
                .getFirst();
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(first.markDelivered(stale, NOW.plusSeconds(12))).isFalse();
        assertThat(restarted.markDelivered(recovered, NOW.plusSeconds(12))).isTrue();
        assertThat(restarted.claimAvailable(
                "worker-c", NOW.plusSeconds(30), NOW.plusSeconds(40), 10)).isEmpty();
        assertThat(database.jdbc().queryForObject("""
                SELECT status || '|' || attempts
                FROM outbox WHERE aggregate_id = 'ticket'
                """, String.class)).isEqualTo("DELIVERED|2");
        assertThat(database.jdbc().queryForObject("""
                SELECT delivered_at_ms FROM outbox WHERE aggregate_id = 'ticket'
                """, Long.class)).isEqualTo(NOW.plusSeconds(12).toEpochMilli());
    }

    private SqliteExecutionTestSupport.Database database(String file)
    {
        SqliteExecutionTestSupport.Database database =
                SqliteExecutionTestSupport.database(tempDir.resolve(file));
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        DispatchTicket ticket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, ticket);
        Flyway.configure().dataSource(database.url(), "", "").target("231").load().migrate();
        return database;
    }

    private static int wakeCount(SqliteExecutionTestSupport.Database database)
    {
        return database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM outbox
                WHERE aggregate_kind = 'DISPATCH_TICKET'
                """, Integer.class);
    }
}
