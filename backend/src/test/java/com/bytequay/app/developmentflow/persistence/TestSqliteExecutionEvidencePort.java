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
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestSqliteExecutionEvidencePort
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void persistsSessionProcessLogsUsageHeartbeatAndRawFinishEvidence()
    {
        SqliteExecutionTestSupport.Database database = SqliteExecutionTestSupport.database(
                tempDir.resolve("execution-evidence.db"));
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        migrateToProcessAttempts(database);
        DispatchTicket requested = SqliteExecutionTestSupport.requestedAgentTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, requested);

        SqliteCapacityLeaseStore leases = new SqliteCapacityLeaseStore(database.dataSource());
        CapacityManager capacity = new CapacityManager(
                leases,
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of(VALIDATION, 2)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(), "worker")
                .lease().orElseThrow();
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());
        DispatchTicket claimed = requested.claim("worker", lease.id(), NOW.plusSeconds(20));
        assertThat(tickets.compareAndSet(requested.id(), 0, claimed)).isTrue();
        DispatchTicket running = claimed.markRunning(NOW.plusSeconds(1));
        assertThat(tickets.compareAndSet(claimed.id(), claimed.version(), running)).isTrue();

        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                database.dataSource(), new ObjectMapper(), () -> "execution-1");
        String executionId = evidence.start(
                running, lease, DispatchTicket.ClaimPurpose.EXECUTE, NOW.plusSeconds(1));
        evidence.providerSession(executionId, "openai", "session-1");
        evidence.processStarted(executionId, 4242, "logs/execution-1.jsonl");
        evidence.processStarted(executionId, 4343, "logs/execution-1-fallback.jsonl");
        evidence.appendLog(executionId, 0, "{\"event\":\"start\"}", NOW.plusSeconds(2));
        evidence.appendLog(executionId, 1, "{\"event\":\"done\"}", NOW.plusSeconds(3));
        evidence.recordUsage(executionId, 100, 40, 7);
        evidence.recordUsage(executionId, 120, 50, 9);
        evidence.heartbeat(executionId, NOW.plusSeconds(4));
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                new DispatchTicket.OperationFence(
                        1L, null, null, "raw-operation", 2,
                        "raw-fingerprint", "raw-head", "raw-base"),
                DispatchTicket.Outcome.SUCCEEDED,
                "{\"ok\":true}",
                "{\"source\":\"test\"}",
                null);
        DispatchTicket pending = running.resultPending(result, NOW.plusSeconds(5));
        assertThat(tickets.compareAndSet(running.id(), running.version(), pending)).isTrue();
        evidence.finish(executionId, result, null, NOW.plusSeconds(5));

        Map<String, Object> row = database.jdbc().queryForMap(
                "SELECT * FROM agent_execution WHERE id = ?", executionId);
        assertThat(row)
                .containsEntry("ticket_id", requested.id())
                .containsEntry("infrastructure_attempt", 1)
                .containsEntry("provider", "openai")
                .containsEntry("provider_session_id", "session-1")
                .containsEntry("process_pid", 4343)
                .containsEntry("log_ref", "logs/execution-1-fallback.jsonl")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("heartbeat_at_ms", NOW.plusSeconds(5).toEpochMilli())
                .containsEntry("finished_at_ms", NOW.plusSeconds(5).toEpochMilli())
                .containsEntry("tokens_in", 120)
                .containsEntry("tokens_out", 50)
                .containsEntry("cost_usd_milli", 9);
        assertThat((String) row.get("raw_result"))
                .contains("raw-operation", "raw-fingerprint", "SUCCEEDED");
        assertThat(database.jdbc().queryForList("""
                SELECT process_attempt, process_pid, log_ref
                FROM agent_execution_process_attempt
                WHERE execution_id = ? ORDER BY process_attempt
                """, executionId))
                .extracting(process -> List.of(
                        process.get("process_attempt"),
                        process.get("process_pid"),
                        process.get("log_ref")))
                .containsExactly(
                        List.of(1, 4242, "logs/execution-1.jsonl"),
                        List.of(2, 4343, "logs/execution-1-fallback.jsonl"));
        assertThat(database.jdbc().queryForList("""
                SELECT seq, payload, created_at_ms
                FROM agent_execution_log WHERE execution_id = ? ORDER BY seq
                """, executionId))
                .extracting(log -> List.of(
                        log.get("seq"), log.get("payload"), log.get("created_at_ms")))
                .containsExactly(
                        List.of(0, "{\"event\":\"start\"}",
                                NOW.plusSeconds(2).toEpochMilli()),
                        List.of(1, "{\"event\":\"done\"}",
                                NOW.plusSeconds(3).toEpochMilli()));

        assertThatThrownBy(() -> evidence.finish(
                executionId, result, null, NOW.plusSeconds(6)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> evidence.processStarted(
                executionId, 4444, "logs/too-late.jsonl"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM agent_execution_process_attempt
                WHERE execution_id = ?
                """, Integer.class, executionId)).isEqualTo(2);
    }

    @Test
    void startRequiresThePersistedLiveTicketClaimLeaseAndAttempt()
    {
        StartedExecution fixture = runningExecution("start-fences.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(), () -> "execution-2");
        DispatchTicket forged = copyWithVersion(
                fixture.running(), fixture.running().version() + 1);

        assertThatThrownBy(() -> evidence.start(
                forged,
                fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> evidence.start(
                fixture.running(),
                fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(31)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(new SqliteCapacityLeaseStore(fixture.database().dataSource())
                .release(fixture.lease().id(), "worker", NOW.plusSeconds(2))).isTrue();
        assertThatThrownBy(() -> evidence.start(
                fixture.running(),
                fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT count(*) FROM agent_execution", Integer.class)).isZero();
    }

    @Test
    void finishRequiresExactDurablePendingResultOrExplicitFailure()
    {
        StartedExecution fixture = runningExecution("finish-fences.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(), () -> "execution-3");
        String executionId = evidence.start(
                fixture.running(),
                fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(1));
        DispatchTicket.DispatchResult durable = result("raw-operation", 2);
        DispatchTicket pending = fixture.running().resultPending(
                durable, NOW.plusSeconds(2));
        assertThat(fixture.tickets().compareAndSet(
                fixture.running().id(), fixture.running().version(), pending)).isTrue();

        assertThatThrownBy(() -> evidence.finish(
                executionId, result("different-operation", 3), null,
                NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable pending result");
        assertThatThrownBy(() -> evidence.finish(
                executionId, null, " ", NOW.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure is blank");
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT finished_at_ms FROM agent_execution WHERE id = ?",
                Long.class,
                executionId)).isNull();

        evidence.finish(executionId, durable, null, NOW.plusSeconds(3));
        assertThat(fixture.database().jdbc().queryForObject(
                "SELECT status FROM agent_execution WHERE id = ?",
                String.class,
                executionId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void maintenanceFinalizesDurableResultEvidenceBeforeDeliveryCanBeClaimed()
    {
        StartedExecution fixture = runningExecution("pending-evidence-recovery.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(), () -> "execution-recovery");
        String executionId = evidence.start(
                fixture.running(), fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE, NOW.plusSeconds(1));
        DispatchTicket.DispatchResult result = result("operation", 1);
        DispatchTicket pending = fixture.running().resultPending(
                result, NOW.plusSeconds(2));
        assertThat(fixture.tickets().compareAndSet(
                fixture.running().id(), fixture.running().version(), pending)).isTrue();

        assertThat(fixture.tickets().claimDelivery(
                pending.id(), pending.version(), "delivery-worker",
                NOW.plusSeconds(2), NOW.plusSeconds(20))).isEmpty();

        evidence.maintain(NOW.plusSeconds(3));

        // The live worker can arrive immediately after the maintenance sweep
        // won the RESULT_PENDING crash-window race. The same exact result
        // converges; an ordinary duplicate finish remains rejected above.
        evidence.finish(executionId, result, null, NOW.plusSeconds(4));

        assertThat(fixture.database().jdbc().queryForMap("""
                SELECT status, finished_at_ms, raw_result, error_class,
                    error_message
                FROM agent_execution WHERE id = ?
                """, executionId))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry("error_class", "RECOVERED_PENDING_RESULT")
                .satisfies(row -> assertThat((String) row.get("raw_result"))
                        .contains("operation", "SUCCEEDED"))
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("RECOVERED_PENDING_RESULT"));
        assertThat(fixture.tickets().claimDelivery(
                pending.id(), pending.version(), "delivery-worker",
                NOW.plusSeconds(3), NOW.plusSeconds(20))).isPresent();
    }

    @Test
    void retryCannotOvertakeUnfinishedExecutionEvidenceFromItsPriorAttempt()
    {
        StartedExecution fixture = runningExecution("retry-evidence-recovery.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(),
                () -> "execution-retry-recovery");
        String executionId = evidence.start(
                fixture.running(), fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE, NOW.plusSeconds(1));
        DispatchTicket waiting = fixture.running().retryWait(
                "provider unavailable", NOW.plusSeconds(3));
        assertThat(fixture.tickets().compareAndSet(
                fixture.running().id(), fixture.running().version(), waiting)).isTrue();

        DispatchTicket prematureClaim = waiting.claim(
                "worker", fixture.lease().id(), NOW.plusSeconds(20));
        assertThat(prematureClaim.state()).isEqualTo(DispatchTicket.State.CLAIMED);
        assertThat(fixture.database().jdbc().queryForObject("""
                SELECT COUNT(*) FROM agent_execution
                WHERE ticket_id = ? AND finished_at_ms IS NULL
                """, Integer.class, waiting.id())).isOne();
        assertThat(fixture.tickets().compareAndSet(
                waiting.id(), waiting.version(), prematureClaim)).isFalse();

        evidence.maintain(NOW.plusSeconds(3));

        assertThat(fixture.database().jdbc().queryForMap("""
                SELECT status, finished_at_ms, raw_result, error_message
                FROM agent_execution WHERE id = ?
                """, executionId))
                .containsEntry("status", "FAILED")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry("raw_result", null)
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("RECOVERED_FAILED_EXECUTION",
                                "Recovered unfinished execution evidence",
                                "provider unavailable"));
        assertThat(fixture.tickets().compareAndSet(
                waiting.id(), waiting.version(), prematureClaim)).isTrue();
    }

    @Test
    void reconcileRecoveryKeepsAnAmbiguousExecutionUnknown()
    {
        StartedExecution fixture = runningExecution("reconcile-evidence-recovery.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(),
                () -> "execution-reconcile-recovery");
        String executionId = evidence.start(
                fixture.running(), fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE, NOW.plusSeconds(1));
        DispatchTicket waiting = fixture.running().reconcileWait(
                "execution lease expired; reconciliation required",
                NOW.plusSeconds(3));
        assertThat(fixture.tickets().compareAndSet(
                fixture.running().id(), fixture.running().version(), waiting)).isTrue();

        evidence.maintain(NOW.plusSeconds(3));

        assertThat(fixture.database().jdbc().queryForMap("""
                SELECT status, finished_at_ms, raw_result, error_message
                FROM agent_execution WHERE id = ?
                """, executionId))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry("raw_result", null)
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("RECOVERED_INDETERMINATE_EXECUTION",
                                "RECONCILE_WAIT"));
    }

    @Test
    void overtakenPriorAttemptRecoveryKeepsItsRawOutcomeUnknown()
    {
        StartedExecution fixture = runningExecution("prior-attempt-recovery.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(),
                () -> "execution-prior-attempt");
        String executionId = evidence.start(
                fixture.running(), fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE, NOW.plusSeconds(1));
        DispatchTicket waiting = fixture.running().retryWait(
                "provider unavailable", NOW.plusSeconds(30));
        assertThat(fixture.tickets().compareAndSet(
                fixture.running().id(), fixture.running().version(), waiting)).isTrue();

        fixture.database().jdbc().update("""
                UPDATE dispatch_ticket
                SET infrastructure_attempts = 2, version = version + 1
                WHERE id = ? AND status = 'RETRY_WAIT'
                """, waiting.id());

        evidence.maintain(NOW.plusSeconds(2));

        assertThat(fixture.database().jdbc().queryForMap("""
                SELECT status, finished_at_ms, raw_result, error_message
                FROM agent_execution WHERE id = ?
                """, executionId))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("finished_at_ms", NOW.plusSeconds(2).toEpochMilli())
                .containsEntry("raw_result", null)
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("RECOVERED_INDETERMINATE_EXECUTION",
                                "execution attempt 1", "ticket attempt 2"));
    }

    @Test
    void finishPersistsAnExplicitFailureWithoutAResult()
    {
        StartedExecution fixture = runningExecution("failed-finish.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(), () -> "execution-4");
        String executionId = evidence.start(
                fixture.running(),
                fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(1));

        evidence.finish(
                executionId, null, "provider unavailable", NOW.plusSeconds(2));

        assertThat(fixture.database().jdbc().queryForMap(
                "SELECT status, raw_result, error_message FROM agent_execution WHERE id = ?",
                executionId))
                .containsEntry("status", "FAILED")
                .containsEntry("raw_result", null)
                .containsEntry("error_message", "provider unavailable");
    }

    @Test
    void infrastructureFailuresRemainDurableBeforeAndAfterFinish()
    {
        StartedExecution fixture = runningExecution("infrastructure-failure.db");
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                fixture.database().dataSource(), new ObjectMapper(), () -> "execution-5");
        String executionId = evidence.start(
                fixture.running(),
                fixture.lease(),
                DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(1));

        evidence.infrastructureFailure(
                fixture.running().id(), "heartbeat failed", NOW.plusSeconds(2));
        evidence.finish(
                executionId, null, "execution stopped", NOW.plusSeconds(3));
        evidence.infrastructureFailure(
                fixture.running().id(), "writer release failed", NOW.plusSeconds(4));
        evidence.infrastructureFailure(null, "global maintenance failed", NOW.plusSeconds(5));

        assertThat(fixture.database().jdbc().queryForMap(
                "SELECT error_class, error_message FROM agent_execution WHERE id = ?",
                executionId))
                .containsEntry("error_class", "INFRASTRUCTURE")
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains(
                                "infrastructure@" + NOW.plusSeconds(2).toEpochMilli()
                                        + ": heartbeat failed",
                                "execution stopped",
                                "infrastructure@" + NOW.plusSeconds(4).toEpochMilli()
                                        + ": writer release failed"));
    }

    private StartedExecution runningExecution(String databaseName)
    {
        SqliteExecutionTestSupport.Database database = SqliteExecutionTestSupport.database(
                tempDir.resolve(databaseName));
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        migrateToProcessAttempts(database);
        DispatchTicket requested = SqliteExecutionTestSupport.requestedAgentTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, requested);
        SqliteCapacityLeaseStore leases = new SqliteCapacityLeaseStore(database.dataSource());
        CapacityManager capacity = new CapacityManager(
                leases,
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of(VALIDATION, 2)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(), "worker")
                .lease().orElseThrow();
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());
        DispatchTicket claimed = requested.claim(
                "worker", lease.id(), NOW.plusSeconds(20));
        assertThat(tickets.compareAndSet(requested.id(), 0, claimed)).isTrue();
        DispatchTicket running = claimed.markRunning(NOW.plusSeconds(1));
        assertThat(tickets.compareAndSet(claimed.id(), claimed.version(), running)).isTrue();
        return new StartedExecution(database, tickets, lease, running);
    }

    private static void migrateToProcessAttempts(
            SqliteExecutionTestSupport.Database database)
    {
        MigratedSqliteDatabase.migrate(database.url());
    }

    private static DispatchTicket.DispatchResult result(String operationId, int attempt)
    {
        return new DispatchTicket.DispatchResult(
                new DispatchTicket.OperationFence(
                        1L, null, null, operationId, attempt,
                        "raw-fingerprint", "raw-head", "raw-base"),
                DispatchTicket.Outcome.SUCCEEDED,
                "{\"ok\":true}",
                "{\"source\":\"test\"}",
                null);
    }

    private static DispatchTicket copyWithVersion(DispatchTicket ticket, long version)
    {
        return new DispatchTicket(
                ticket.id(), version, ticket.envelope(), ticket.state(),
                ticket.claimPurpose(), ticket.claimOwner(), ticket.capacityLeaseId(),
                ticket.claimExpiresAt(), ticket.createdAt(), ticket.nextAttemptAt(),
                ticket.infrastructureAttempts(), ticket.startedAt(),
                ticket.cancelRequestedAt(), ticket.pendingResult(),
                ticket.deliveryReceipt(), ticket.completedAt(), ticket.lastError());
    }

    private record StartedExecution(
            SqliteExecutionTestSupport.Database database,
            SqliteDispatchTicketStore tickets,
            CapacityManager.CapacityLease lease,
            DispatchTicket running) {}
}
