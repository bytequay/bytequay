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
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLI;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestTerminalExecutionEvidenceRecoveryMigration
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void repairsDeliveredTicketsWhoseExecutionEvidenceNeverFinished()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("terminal-evidence.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url, "310");
        DataSource dataSource = SqliteTestPools.open(url);
        SqliteExecutionTestSupport.Database database =
                new SqliteExecutionTestSupport.Database(
                        url, dataSource, new JdbcTemplate(dataSource));
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);

        DispatchTicket requested = SqliteExecutionTestSupport.requestedAgentTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, requested);
        SqliteCapacityLeaseStore leases = new SqliteCapacityLeaseStore(dataSource);
        CapacityManager capacity = new CapacityManager(
                leases,
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of(VALIDATION, 10)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(), "worker")
                .lease().orElseThrow();
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(dataSource);
        DispatchTicket claimed = requested.claim(
                "worker", lease.id(), NOW.plusSeconds(30));
        assertThat(tickets.compareAndSet(requested.id(), requested.version(), claimed))
                .isTrue();
        DispatchTicket firstRunning = claimed.markRunning(NOW.plusSeconds(1));
        assertThat(tickets.compareAndSet(
                claimed.id(), claimed.version(), firstRunning))
                .isTrue();
        DispatchTicket retry = firstRunning.retryWait(
                "first process disappeared", NOW.plusSeconds(2));
        assertThat(tickets.compareAndSet(
                firstRunning.id(), firstRunning.version(), retry)).isTrue();
        DispatchTicket retryClaim = retry.claim(
                "worker", lease.id(), NOW.plusSeconds(30));
        assertThat(tickets.compareAndSet(
                retry.id(), retry.version(), retryClaim)).isTrue();
        DispatchTicket running = retryClaim.markRunning(NOW.plusSeconds(2));
        assertThat(tickets.compareAndSet(
                retryClaim.id(), retryClaim.version(), running)).isTrue();
        SqliteExecutionEvidencePort evidence = new SqliteExecutionEvidencePort(
                dataSource, new ObjectMapper(), () -> "execution-current");
        String currentExecution = evidence.start(
                running, lease, DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(2));
        database.jdbc().update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    started_at_ms, heartbeat_at_ms)
                VALUES ('execution-abandoned', 'ticket', 1, 'RUNNING', ?, ?)
                """, NOW.plusSeconds(1).toEpochMilli(),
                NOW.plusSeconds(1).toEpochMilli());

        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                new DispatchTicket.OperationFence(
                        1L, null, null, "operation", 1,
                        "raw-fingerprint", "raw-head", "raw-base"),
                DispatchTicket.Outcome.SUCCEEDED,
                "{\"ok\":true}", "{\"source\":\"test\"}", null);
        DispatchTicket pending = running.resultPending(result, NOW.plusSeconds(2));
        assertThat(tickets.compareAndSet(running.id(), running.version(), pending))
                .isTrue();
        evidence.finish(currentExecution, result, null, NOW.plusSeconds(2));
        DispatchTicket delivered = pending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED, "{\"accepted\":true}"),
                NOW.plusSeconds(3));
        assertThat(tickets.compareAndSet(pending.id(), pending.version(), delivered))
                .isTrue();

        assertThat(database.jdbc().queryForObject("""
                SELECT active_agent_execution_count
                FROM task_control_live_work_v256 WHERE task_id = 'task'
                """, Integer.class)).isOne();

        SqliteExecutionTestSupport.seedTask(
                database, "trunk", "task-indeterminate", 2);
        DispatchTicket indeterminateRequested =
                SqliteExecutionTestSupport.requestedAgentTaskTicket(
                        "ticket-indeterminate", "operation-indeterminate",
                        "workspace", "trunk", "task-indeterminate",
                        NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, indeterminateRequested);
        CapacityManager.CapacityLease indeterminateLease = capacity.tryAcquireForTicket(
                indeterminateRequested.id(),
                indeterminateRequested.envelope().capacityRequest(),
                "worker-indeterminate").lease().orElseThrow();
        DispatchTicket indeterminateClaimed = indeterminateRequested.claim(
                "worker-indeterminate", indeterminateLease.id(),
                NOW.plusSeconds(30));
        assertThat(tickets.compareAndSet(
                indeterminateRequested.id(), indeterminateRequested.version(),
                indeterminateClaimed)).isTrue();
        DispatchTicket indeterminateRunning = indeterminateClaimed.markRunning(
                NOW.plusSeconds(1));
        assertThat(tickets.compareAndSet(
                indeterminateClaimed.id(), indeterminateClaimed.version(),
                indeterminateRunning)).isTrue();
        new SqliteExecutionEvidencePort(
                dataSource, new ObjectMapper(), () -> "execution-indeterminate")
                .start(indeterminateRunning, indeterminateLease,
                        DispatchTicket.ClaimPurpose.EXECUTE, NOW.plusSeconds(1));
        DispatchTicket.DispatchResult indeterminate =
                new DispatchTicket.DispatchResult(
                        indeterminateRunning.envelope().fence(),
                        DispatchTicket.Outcome.INDETERMINATE,
                        null, "{\"source\":\"test\"}", "result unknown");
        DispatchTicket indeterminatePending = indeterminateRunning.resultPending(
                indeterminate, NOW.plusSeconds(2));
        assertThat(tickets.compareAndSet(
                indeterminateRunning.id(), indeterminateRunning.version(),
                indeterminatePending)).isTrue();
        DispatchTicket indeterminateDelivered = indeterminatePending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED, "{\"accepted\":true}"),
                NOW.plusSeconds(3));
        assertThat(tickets.compareAndSet(
                indeterminatePending.id(), indeterminatePending.version(),
                indeterminateDelivered)).isTrue();

        seedTerminalTicketWithUnfinishedCurrentExecution(
                database, capacity, tickets, "succeeded", 3,
                DispatchTicket.Outcome.SUCCEEDED);
        seedTerminalTicketWithUnfinishedCurrentExecution(
                database, capacity, tickets, "canceled", 4,
                DispatchTicket.Outcome.CANCELED);
        seedStaleNoLaunchCanceledStageTurn(database, tickets, 5);

        migrate(url, "312");

        assertThat(database.jdbc().queryForMap("""
                SELECT status, finished_at_ms, error_class, error_message
                FROM agent_execution WHERE id = 'execution-abandoned'
                """))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry("error_class", "RECOVERED_SUPERSEDED_ATTEMPT")
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("later infrastructure attempt",
                                "earlier raw outcome is unknown"));
        assertThat(database.jdbc().queryForMap("""
                SELECT status, finished_at_ms, error_class
                FROM agent_execution WHERE id = 'execution-current'
                """))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("finished_at_ms", NOW.plusSeconds(2).toEpochMilli())
                .containsEntry("error_class", null);
        assertThat(database.jdbc().queryForMap("""
                SELECT status, finished_at_ms, error_class, error_message
                FROM agent_execution WHERE id = 'execution-indeterminate'
                """))
                .containsEntry("status", "UNKNOWN")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry(
                        "error_class", "RECOVERED_AMBIGUOUS_TERMINAL_FAILURE")
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("FAILED or INDETERMINATE",
                                "therefore unknown"));
        assertThat(database.jdbc().queryForMap("""
                SELECT status, finished_at_ms, error_class, error_message
                FROM agent_execution WHERE id = 'execution-succeeded'
                """))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry("error_class", "RECOVERED_TERMINAL_TICKET")
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("terminal delivered ticket",
                                "raw result was already accepted"));
        assertThat(database.jdbc().queryForMap("""
                SELECT status, finished_at_ms, error_class, error_message
                FROM agent_execution WHERE id = 'execution-canceled'
                """))
                .containsEntry("status", "CANCELED")
                .containsEntry("finished_at_ms", NOW.plusSeconds(3).toEpochMilli())
                .containsEntry("error_class", "RECOVERED_TERMINAL_TICKET")
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("terminal delivered ticket",
                                "raw result was already accepted"));
        assertThat(database.jdbc().queryForObject("""
                SELECT active_agent_execution_count
                FROM task_control_live_work_v256 WHERE task_id = 'task'
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT active_agent_execution_count
                FROM task_control_live_work_v256
                WHERE task_id = 'task-indeterminate'
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT active_agent_execution_count
                FROM task_control_live_work_v256
                WHERE task_id = 'task-succeeded'
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT active_agent_execution_count
                FROM task_control_live_work_v256
                WHERE task_id = 'task-canceled'
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForMap("""
                SELECT status, finished_at_ms, error_message
                FROM stage_turn WHERE id = 'turn-stale-canceled'
                """))
                .containsEntry("status", "CANCELED")
                .containsEntry("finished_at_ms", NOW.plusSeconds(4).toEpochMilli())
                .satisfies(row -> assertThat((String) row.get("error_message"))
                        .contains("accepted before provider launch"));
        assertThat(database.jdbc().queryForMap("""
                SELECT status, delivery_acceptance, delivery_evidence,
                       completed_at_ms, pending_result_outcome,
                       pending_result_payload, last_error
                FROM dispatch_ticket WHERE id = 'ticket-stale-canceled'
                """))
                .containsEntry("status", "CANCELED")
                .containsEntry("delivery_acceptance", "SUPERSEDED")
                .containsEntry("completed_at_ms", NOW.plusSeconds(4).toEpochMilli())
                .containsEntry("pending_result_outcome", null)
                .containsEntry("pending_result_payload", null)
                .satisfies(row -> {
                    assertThat((String) row.get("delivery_evidence"))
                            .contains("V312_STALE_NO_LAUNCH_CANCELLATION",
                                    "turn-stale-canceled");
                    assertThat((String) row.get("last_error"))
                            .contains("accepted before provider launch");
                });
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE id = 'ticket-stale-canceled'
                  AND status IN ('REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                      'RESULT_PENDING', 'CLAIMED', 'RUNNING', 'DELIVERING')
                """, Integer.class)).isZero();

        SqliteExecutionTestSupport.seedTask(
                database, "trunk", "task-missing-evidence", 6);
        DispatchTicket missingEvidence =
                SqliteExecutionTestSupport.requestedAgentTaskTicket(
                        "ticket-missing-evidence", "operation-missing-evidence",
                        "workspace", "trunk", "task-missing-evidence",
                        NOW, VALIDATION, true, false)
                        .resultPending(new DispatchTicket.DispatchResult(
                                new DispatchTicket.OperationFence(
                                        1L, null, null,
                                        "operation-missing-evidence", 1,
                                        "fingerprint", "head", "base"),
                                DispatchTicket.Outcome.SUCCEEDED,
                                "{}", "{}", null), NOW.plusSeconds(4));
        SqliteExecutionTestSupport.insertTicket(database, missingEvidence);
        assertThat(tickets.claimDelivery(
                missingEvidence.id(), missingEvidence.version(), "delivery",
                NOW.plusSeconds(5), NOW.plusSeconds(30))).isEmpty();
        DispatchTicket forgedTerminal = missingEvidence.completeDelivery(
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED, "{}"),
                NOW.plusSeconds(5));
        assertThatThrownBy(() -> tickets.compareAndSet(
                missingEvidence.id(), missingEvidence.version(), forgedTerminal))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("SQLite statement failed");
    }

    private static void seedStaleNoLaunchCanceledStageTurn(
            SqliteExecutionTestSupport.Database database,
            SqliteDispatchTicketStore tickets,
            int taskSequence)
    {
        String taskId = "task-stale-canceled";
        String remoteStageId = "stage-stale-remote";
        String cleanupStageId = "stage-current-cleanup";
        String turnId = "turn-stale-canceled";
        String operationId = "operation-stale-canceled";
        SqliteExecutionTestSupport.seedTask(
                database, "trunk", taskId, taskSequence);
        database.jdbc().update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES (?, ?, 'REMOTE_DEVELOPMENT', 1, 0, 'WAITING_CI', ?)
                """, remoteStageId, taskId,
                NOW.plusSeconds(1).toEpochMilli());
        database.jdbc().update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, remoteStageId);
        database.jdbc().update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
        database.jdbc().update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 1, 'REMOTE_CI_REPAIR', 'QUEUED', ?, 1, 1,
                    'fingerprint', 'head', 'base', 'CLI', '{}', ?)
                """, turnId, remoteStageId, operationId,
                NOW.plusSeconds(2).toEpochMilli());

        CapacityManager.CapacityRequest capacity =
                new CapacityManager.CapacityRequest(
                        operationId,
                        CapacityManager.WorkflowSource.V2,
                        Set.of(CLI),
                        new CapacityManager.CapacityScope(
                                "workspace", "trunk", taskId, 1L),
                        false, true, true);
        DispatchTicket requested = DispatchTicket.requested(
                "ticket-stale-canceled",
                new DispatchTicket.DispatchEnvelope(
                        "EXECUTE_STAGE_TURN",
                        DispatchTicket.AsyncFamily.AGENT_TURN,
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.STAGE_TURN,
                                turnId, "STAGE_TURN_RESULT"),
                        new DispatchTicket.OperationFence(
                                1L, remoteStageId, 1L, operationId, 1,
                                "fingerprint", "head", "base"),
                        capacity),
                NOW);
        SqliteExecutionTestSupport.insertTicket(database, requested);
        DispatchTicket canceled = requested.requestCancel(NOW.plusSeconds(4));
        assertThat(tickets.compareAndSet(
                requested.id(), requested.version(), canceled)).isTrue();

        database.jdbc().update("""
                UPDATE tasks
                SET lifecycle_state = 'CLEANING', aggregate_version = 2
                WHERE id = ?
                """, taskId);
        database.jdbc().update(
                "DELETE FROM task_current_stage WHERE task_id = ?", taskId);
        database.jdbc().update("""
                UPDATE stage
                SET version = 1, checkpoint = 'COMPLETED', completed_at_ms = ?,
                    end_reason = 'TASK_CANCELED'
                WHERE id = ?
                """, NOW.plusSeconds(3).toEpochMilli(), remoteStageId);
        database.jdbc().update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES (?, ?, 'CLEANUP', 1, 0, 'WAITING_QUIESCENCE', ?)
                """, cleanupStageId, taskId,
                NOW.plusSeconds(3).toEpochMilli());
        database.jdbc().update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, cleanupStageId);
    }

    private static void seedTerminalTicketWithUnfinishedCurrentExecution(
            SqliteExecutionTestSupport.Database database,
            CapacityManager capacity,
            SqliteDispatchTicketStore tickets,
            String suffix,
            int taskSequence,
            DispatchTicket.Outcome outcome)
    {
        String taskId = "task-" + suffix;
        String ticketId = "ticket-" + suffix;
        String operationId = "operation-" + suffix;
        SqliteExecutionTestSupport.seedTask(
                database, "trunk", taskId, taskSequence);
        DispatchTicket requested =
                SqliteExecutionTestSupport.requestedAgentTaskTicket(
                        ticketId, operationId, "workspace", "trunk", taskId,
                        NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, requested);
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                ticketId, requested.envelope().capacityRequest(),
                "worker-" + suffix).lease().orElseThrow();
        DispatchTicket claimed = requested.claim(
                "worker-" + suffix, lease.id(), NOW.plusSeconds(30));
        assertThat(tickets.compareAndSet(
                requested.id(), requested.version(), claimed)).isTrue();
        DispatchTicket running = claimed.markRunning(NOW.plusSeconds(1));
        assertThat(tickets.compareAndSet(
                claimed.id(), claimed.version(), running)).isTrue();
        new SqliteExecutionEvidencePort(
                database.dataSource(), new ObjectMapper(),
                () -> "execution-" + suffix)
                .start(running, lease, DispatchTicket.ClaimPurpose.EXECUTE,
                        NOW.plusSeconds(1));
        DispatchTicket.DispatchResult result = switch (outcome) {
            case SUCCEEDED -> new DispatchTicket.DispatchResult(
                    running.envelope().fence(), outcome,
                    "{\"ok\":true}", "{\"source\":\"test\"}", null);
            case CANCELED -> DispatchTicket.DispatchResult.canceled(
                    running.envelope().fence());
            default -> throw new IllegalArgumentException(
                    "unsupported terminal fixture outcome: " + outcome);
        };
        DispatchTicket pending = running.resultPending(
                result, NOW.plusSeconds(2));
        assertThat(tickets.compareAndSet(
                running.id(), running.version(), pending)).isTrue();
        DispatchTicket terminal = pending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED, "{\"accepted\":true}"),
                NOW.plusSeconds(3));
        assertThat(tickets.compareAndSet(
                pending.id(), pending.version(), terminal)).isTrue();
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .target(target)
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}
