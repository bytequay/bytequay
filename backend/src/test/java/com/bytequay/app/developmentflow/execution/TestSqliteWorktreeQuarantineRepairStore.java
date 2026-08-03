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
package com.bytequay.app.developmentflow.execution;

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.QuarantineRepair;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.WorktreeQuarantine;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.RepairResult;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.ResultReceipt;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairRuntime;
import com.bytequay.app.developmentflow.persistence.SqliteCapacityLeaseStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.persistence.SqliteExecutionTestSupport;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeQuarantineRepairStore.Admission;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeWriterLeaseStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.SqliteTestPools;
import com.bytequay.app.testing.V2TaskSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestSqliteWorktreeQuarantineRepairStore
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void acceptedRepairOfHistoricalStageQuarantineClearsItsExactBlocker()
            throws Exception
    {
        Fixture fixture = fixture("accepted.db", true);

        Admission admitted = request(
                fixture, "repair-command-1", "restore exact worktree");
        Admission replay = request(
                fixture, "repair-command-1", "restore exact worktree");

        assertThat(admitted.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.operation()).isEqualTo(admitted.operation());
        assertThat(admitted.operation().sourceOperationId())
                .isEqualTo("source-operation");
        assertThat(admitted.operation().stageId()).isEqualTo("current-stage");
        assertThat(admitted.operation().attempt()).isOne();
        assertThat(fixture.jdbc().queryForMap("""
                SELECT owner_kind, owner_id, task_id
                  FROM dispatch_ticket
                 WHERE id = ?
                """, admitted.operation().ticketId()))
                .containsEntry("owner_kind", "TASK")
                .containsEntry("owner_id", "task")
                .containsEntry("task_id", "task");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT stage_id FROM agent_turn_worktree_quarantine_v318
                WHERE id = ?
                """, String.class, fixture.quarantine().id()))
                .isEqualTo("source-stage");

        PendingResult pending = successfulResult(
                fixture, admitted.operation(), "repair-worker-1");
        DispatchTicket.DeliveryReceipt delivered = fixture.runtime().deliver(
                pending.owner(), pending.fence(), pending.raw());

        assertThat(delivered.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(fixture.runtime().deliver(
                pending.owner(), pending.fence(), pending.raw()))
                .isEqualTo(delivered);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM agent_turn_worktree_quarantine_v318
                WHERE id = ?
                """, String.class, fixture.quarantine().id()))
                .isEqualTo("CLEARED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE id = ?
                """, String.class, fixture.blockerId()))
                .isEqualTo("RESOLVED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM worktree_quarantine_repair_operation_v318
                WHERE id = ?
                """, String.class, admitted.operation().id()))
                .isEqualTo("SUCCEEDED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM worktree_quarantine_repair_delivery_v318
                WHERE repair_operation_id = ?
                """, Integer.class, admitted.operation().id())).isOne();
    }

    @Test
    void failedAndCanceledRepairsKeepQuarantineOpenAndAdvanceRetryAttempt()
            throws Exception
    {
        Fixture fixture = fixture("terminal-retry.db", true);
        Admission first = request(
                fixture, "repair-command-failed", "first repair");
        PendingResult failed = terminalResult(
                fixture, first.operation(), "repair-worker-failed",
                DispatchTicket.Outcome.FAILED, Disposition.FAILED,
                "repair did not restore the subject");

        assertThat(fixture.runtime().deliver(
                failed.owner(), failed.fence(), failed.raw()).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertOpen(fixture, first.operation(), "FAILED");

        Admission second = request(
                fixture, "repair-command-canceled", "second repair");
        assertThat(second.operation().attempt()).isEqualTo(2);
        PendingResult canceled = terminalResult(
                fixture, second.operation(), "repair-worker-canceled",
                DispatchTicket.Outcome.CANCELED, Disposition.CANCELED,
                "repair was canceled");

        assertThat(fixture.runtime().deliver(
                canceled.owner(), canceled.fence(), canceled.raw()).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertOpen(fixture, second.operation(), "CANCELED");

        Admission third = request(
                fixture, "repair-command-retry", "retry repair");
        assertThat(third.operation().attempt()).isEqualTo(3);
        assertThatThrownBy(() -> request(
                fixture, "repair-command-overlap", "overlap"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact open blocker");
    }

    @Test
    void successfulResultFromAReplacedOwnerIsSupersededWithoutClearing()
            throws Exception
    {
        Fixture fixture = fixture("stale.db", false);
        Admission admitted = request(
                fixture, "repair-command-stale", "repair old owner");
        PendingResult pending = successfulResult(
                fixture, admitted.operation(), "repair-worker-stale");

        replaceCurrentStage(fixture, "replacement-stage", 2);
        DispatchTicket.DeliveryReceipt delivered = fixture.runtime().deliver(
                pending.owner(), pending.fence(), pending.raw());

        assertThat(delivered.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.SUPERSEDED);
        assertOpen(fixture, admitted.operation(), "SUPERSEDED");

        Admission retry = request(
                fixture, "repair-command-current", "repair current owner");
        assertThat(retry.operation().stageId()).isEqualTo("replacement-stage");
        assertThat(retry.operation().attempt()).isEqualTo(2);
    }

    @Test
    void displayedStageACapabilityCannotArmAfterStageBBecomesCurrent()
    {
        Fixture fixture = fixture("stale-display-fence.db", false);
        replaceCurrentStage(fixture, "stage-b", 2);

        assertThatThrownBy(() -> fixture.runtime().request(
                "task", fixture.quarantine().id(), fixture.blockerId(),
                1, "source-stage", 1, fixture.worktreePath(),
                fixture.branchName(), "fingerprint-1", "base", "base",
                "repair-command-stage-a", "user", "stale displayed fence"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact open blocker");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM worktree_quarantine_repair_operation_v318
                """, Integer.class)).isZero();

        Admission current = request(
                fixture, "repair-command-stage-b", "current displayed fence");
        assertThat(current.operation().stageId()).isEqualTo("stage-b");
        assertThat(current.operation().stageGeneration()).isEqualTo(2);
    }

    @Test
    void receiptReplayRequiresItsFreshCapacityAndWriterFence()
            throws Exception
    {
        Fixture fixture = fixture("released-replay-fence.db", true);
        Operation operation = request(
                fixture, "repair-command-replay", "replay exact proof")
                .operation();
        String worker = "repair-worker-replay";
        Running running = claim(
                fixture.tickets(), fixture.capacity(),
                fixture.tickets().findById(operation.ticketId()).orElseThrow(),
                worker, fixture.clock());
        ExecutionContext context = context(
                running.running(), running.capacity(), fixture.capacity(),
                worker, fixture.clock());
        QuarantineRepair repair = fixture.writers().acquireQuarantineRepair(
                context, operation.worktreePath(), operation.quarantineId(),
                operation.id());

        fixture.writers().authorizeQuarantineRepair(context, repair).run(fence -> {
            ResultReceipt receipt = fixture.store().recordRestored(
                    operation, fence, operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), "original exact proof",
                    NOW.plusSeconds(10));
            assertThat(receipt.writerFencingToken())
                    .isEqualTo(fence.fencingToken());
            assertThat(fixture.store().recordRestored(
                    operation, fence, operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), "fresh replay proof",
                    NOW.plusSeconds(11))).isEqualTo(receipt);
            assertThat(fixture.capacity().release(
                    running.capacity().id(), worker)).isTrue();

            assertThatThrownBy(() -> fixture.store().recordRestored(
                    operation, fence, operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), "released replay proof",
                    NOW.plusSeconds(12)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lacks exact live proof");
            return null;
        });
        context.closeWriterResource();

        assertOpen(fixture, operation, "DISPATCHED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM worktree_quarantine_repair_result_v318
                 WHERE repair_operation_id = ?
                """, Integer.class, operation.id())).isOne();
    }

    @Test
    void deliveryRejectsWrongSchemaOutcomeDispositionAndSubject()
            throws Exception
    {
        Fixture fixture = fixture("strict-result-shape.db", true);
        Operation operation = request(
                fixture, "repair-command-shape", "strict result shape")
                .operation();
        PendingResult pending = terminalResult(
                fixture, operation, "repair-worker-shape",
                DispatchTicket.Outcome.FAILED, Disposition.FAILED,
                "repair failed");
        RepairResult valid = fixture.json().readValue(
                pending.raw().payloadJson(), RepairResult.class);

        assertRejected(fixture, pending, altered(
                valid, 2, valid.disposition(), valid.repairOperationId(),
                valid.expectedBaseSha()), DispatchTicket.Outcome.FAILED,
                valid.error());
        assertRejected(fixture, pending, altered(
                valid, valid.schemaVersion(), Disposition.CANCELED,
                valid.repairOperationId(), valid.expectedBaseSha()),
                DispatchTicket.Outcome.FAILED, valid.error());
        assertRejected(fixture, pending, altered(
                valid, valid.schemaVersion(), valid.disposition(),
                "another-repair-operation", valid.expectedBaseSha()),
                DispatchTicket.Outcome.FAILED, valid.error());
        assertRejected(fixture, pending, altered(
                valid, valid.schemaVersion(), valid.disposition(),
                valid.repairOperationId(), "another-base"),
                DispatchTicket.Outcome.FAILED, valid.error());

        assertOpen(fixture, operation, "DISPATCHED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*)
                  FROM worktree_quarantine_repair_delivery_v318
                 WHERE repair_operation_id = ?
                """, Integer.class, operation.id())).isZero();
    }

    @Test
    void persistedQuarantineRejectsAnOtherwiseValidOrdinaryWriter()
    {
        Fixture fixture = fixture("ordinary-writer.db", true);
        DispatchTicket requested = ticket(
                "ordinary-ticket", "ordinary-operation", "current-stage", 2,
                DispatchTicket.OwnerKind.STAGE, "current-stage",
                "ORDINARY_RESULT", "ORDINARY_WRITE", NOW.plusSeconds(30));
        SqliteExecutionTestSupport.insertTicket(fixture.database(), requested);
        Running running = claim(
                fixture.tickets(), fixture.capacity(), requested,
                "ordinary-worker", fixture.clock());
        ExecutionContext context = context(
                running.running(), running.capacity(), fixture.capacity(),
                "ordinary-worker", fixture.clock());

        assertThatThrownBy(() -> fixture.writers().acquire(
                context, fixture.worktreePath()))
                .isInstanceOf(
                        WorktreeWriterLeaseManager.WorktreeQuarantinedException.class)
                .hasMessageContaining(fixture.quarantine().id());
        fixture.capacity().release(
                running.capacity().id(), "ordinary-worker");
    }

    private Fixture fixture(String file, boolean replaceSource)
    {
        SqliteExecutionTestSupport.Database database =
                SqliteExecutionTestSupport.database(tempDir.resolve(file));
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        V2TaskSeed.completeProvisioning(
                database.jdbc(), "task", "base", "base",
                "fingerprint-1", "provisioned", 2);
        SqliteExecutionTestSupport.seedStage(
                database, "task", "source-stage");

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());
        SqliteCapacityLeaseStore capacityStore = new SqliteCapacityLeaseStore(
                database.dataSource());
        CapacityManager capacity = new CapacityManager(
                capacityStore,
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(LOCAL_GIT, 4)),
                clock,
                Duration.ofMinutes(5));
        SqliteWorktreeWriterLeaseStore worktreeStore =
                new SqliteWorktreeWriterLeaseStore(database.dataSource());
        WorktreeWriterLeaseManager writers = new WorktreeWriterLeaseManager(
                worktreeStore, clock);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(database.dataSource()));
        SqliteWorktreeQuarantineRepairStore repairStore =
                new SqliteWorktreeQuarantineRepairStore(database.jdbc());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        WorktreeQuarantineRepairRuntime runtime =
                new WorktreeQuarantineRepairRuntime(
                        commands, repairStore, json, clock);

        Map<String, Object> identity = database.jdbc().queryForMap("""
                SELECT worktree_path, branch_name
                FROM task_code_identity WHERE task_id = 'task'
                """);
        String worktreePath = (String) identity.get("worktree_path");
        String branchName = (String) identity.get("branch_name");
        database.jdbc().update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('source-turn', 'source-stage', 1, 'TEST_WRITE',
                    'QUEUED', 'source-operation', 1, 1,
                    'fingerprint-1', 'base', 'base', 'CLI', '{}', ?)
                """, NOW.toEpochMilli());
        DispatchTicket sourceTicket = ticket(
                "source-ticket", "source-operation", "source-stage", 1,
                DispatchTicket.OwnerKind.STAGE_TURN, "source-turn",
                "SOURCE_RESULT", "SOURCE_WRITE", NOW);
        SqliteExecutionTestSupport.insertTicket(database, sourceTicket);
        Running source = claim(
                tickets, capacity, sourceTicket, "source-worker", clock);
        ExecutionContext sourceContext = context(
                source.running(), source.capacity(), capacity,
                "source-worker", clock);
        WorktreeWriterLeaseManager.Lease sourceWriter = writers.acquire(
                sourceContext, worktreePath);
        WorktreeQuarantine quarantine = writers.quarantine(
                sourceContext, sourceWriter,
                new WorktreeWriterLeaseManager.QuarantineEvidence(
                        "task", "source-stage", "source-operation",
                        worktreePath, branchName, "fingerprint-1", "base",
                        branchName, "dirty-head", false, "dirty-fingerprint",
                        null, "source restore was not exact"));
        sourceContext.closeWriterResource();
        capacity.release(source.capacity().id(), "source-worker");

        Fixture fixture = new Fixture(
                database, database.jdbc(), tickets, capacity, writers, commands,
                repairStore, runtime, json, clock, worktreePath, branchName,
                quarantine, "worktree-quarantine-v318:" + quarantine.id());
        if (replaceSource) {
            replaceCurrentStage(fixture, "current-stage", 2);
        }
        return fixture;
    }

    private static void replaceCurrentStage(
            Fixture fixture, String stageId, int generation)
    {
        fixture.commands().execute("task", () -> {
            fixture.jdbc().update("""
                    UPDATE stage
                       SET version = version + 1, checkpoint = 'COMPLETED',
                           completed_at_ms = ?, end_reason = 'NORMAL'
                     WHERE id = (SELECT stage_id FROM task_current_stage
                                  WHERE task_id = 'task')
                    """, NOW.plusSeconds(generation).toEpochMilli());
            fixture.jdbc().update("""
                    INSERT INTO stage(
                        id, task_id, kind, generation, version,
                        checkpoint, opened_at_ms)
                    VALUES (?, 'task', 'LOCAL_DEVELOPMENT', ?, 0,
                        'IMPLEMENTING', ?)
                    """, stageId, generation,
                    NOW.plusSeconds(generation).toEpochMilli());
            fixture.jdbc().update("""
                    UPDATE task_current_stage
                       SET stage_id = ?, stage_generation = ?
                     WHERE task_id = 'task'
                    """, stageId, generation);
            return null;
        });
    }

    private static Admission request(
            Fixture fixture, String commandId, String reason)
    {
        Map<String, Object> current = fixture.jdbc().queryForMap("""
                SELECT task.epoch, current.stage_id, current.stage_generation,
                       code.code_fingerprint, code.head_sha, code.base_sha
                  FROM tasks task
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                 WHERE task.id = 'task'
                """);
        return fixture.runtime().request(
                "task", fixture.quarantine().id(), fixture.blockerId(),
                ((Number) current.get("epoch")).longValue(),
                (String) current.get("stage_id"),
                ((Number) current.get("stage_generation")).longValue(),
                fixture.worktreePath(), fixture.branchName(),
                (String) current.get("code_fingerprint"),
                (String) current.get("head_sha"),
                (String) current.get("base_sha"),
                commandId, "user", reason);
    }

    private static PendingResult successfulResult(
            Fixture fixture, Operation operation, String worker)
            throws Exception
    {
        Running running = claim(
                fixture.tickets(), fixture.capacity(),
                fixture.tickets().findById(operation.ticketId()).orElseThrow(),
                worker, fixture.clock());
        ExecutionContext context = context(
                running.running(), running.capacity(), fixture.capacity(),
                worker, fixture.clock());
        QuarantineRepair repair = fixture.writers().acquireQuarantineRepair(
                context, operation.worktreePath(), operation.quarantineId(),
                operation.id());
        ResultReceipt receipt = fixture.writers().authorizeQuarantineRepair(
                context, repair).run(fence -> fixture.store().recordRestored(
                        operation, fence, operation.expectedBranchName(),
                        operation.expectedCodeFingerprint(),
                        operation.expectedHeadSha(), "exact restored evidence",
                        NOW.plusSeconds(10)));
        RepairResult result = new RepairResult(
                1, Disposition.RESTORED, operation.id(),
                operation.operationId(), operation.quarantineId(), receipt.id(),
                operation.expectedBranchName(),
                operation.expectedCodeFingerprint(), operation.expectedHeadSha(),
                operation.expectedBaseSha(),
                receipt.resultBranchName(), receipt.resultCodeFingerprint(),
                receipt.resultHeadSha(), true, true,
                receipt.writerFencingToken(), receipt.evidence(), null);
        return pending(fixture, running, context, result,
                DispatchTicket.Outcome.SUCCEEDED, null, worker);
    }

    private static PendingResult terminalResult(
            Fixture fixture,
            Operation operation,
            String worker,
            DispatchTicket.Outcome outcome,
            Disposition disposition,
            String error)
            throws Exception
    {
        Running running = claim(
                fixture.tickets(), fixture.capacity(),
                fixture.tickets().findById(operation.ticketId()).orElseThrow(),
                worker, fixture.clock());
        ExecutionContext context = context(
                running.running(), running.capacity(), fixture.capacity(),
                worker, fixture.clock());
        RepairResult result = new RepairResult(
                1, disposition, operation.id(), operation.operationId(),
                operation.quarantineId(), null, operation.expectedBranchName(),
                operation.expectedCodeFingerprint(), operation.expectedHeadSha(),
                operation.expectedBaseSha(),
                null, null, null, null, null, null, null, error);
        return pending(fixture, running, context, result, outcome, error, worker);
    }

    private static PendingResult pending(
            Fixture fixture,
            Running running,
            ExecutionContext context,
            RepairResult result,
            DispatchTicket.Outcome outcome,
            String error,
            String worker)
            throws Exception
    {
        String payload = fixture.json().writeValueAsString(result);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                running.running().envelope().fence(), outcome,
                payload, payload, error);
        context.closeWriterResource();
        DispatchTicket pending = running.running().resultPending(
                raw, NOW.plusSeconds(20));
        assertThat(fixture.tickets().compareAndSet(
                running.running().id(), running.running().version(), pending))
                .isTrue();
        fixture.capacity().release(running.capacity().id(), worker);
        return new PendingResult(
                pending.envelope().owner(), pending.envelope().fence(), raw);
    }

    private static void assertRejected(
            Fixture fixture,
            PendingResult pending,
            RepairResult result,
            DispatchTicket.Outcome outcome,
            String error)
            throws Exception
    {
        String payload = fixture.json().writeValueAsString(result);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                pending.fence(), outcome, payload, payload, error);
        assertThatThrownBy(() -> fixture.runtime().deliver(
                pending.owner(), pending.fence(), raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RepairResult altered(
            RepairResult result,
            int schemaVersion,
            Disposition disposition,
            String repairOperationId,
            String expectedBaseSha)
    {
        return new RepairResult(
                schemaVersion, disposition, repairOperationId,
                result.operationId(), result.quarantineId(),
                result.resultReceiptId(), result.expectedBranchName(),
                result.expectedCodeFingerprint(), result.expectedHeadSha(),
                expectedBaseSha, result.resultBranchName(),
                result.resultCodeFingerprint(), result.resultHeadSha(),
                result.resultClean(), result.gitOperationStateClear(),
                result.writerFencingToken(), result.evidence(), result.error());
    }

    private static Running claim(
            SqliteDispatchTicketStore tickets,
            CapacityManager capacity,
            DispatchTicket requested,
            String worker,
            Clock clock)
    {
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(), worker)
                .lease().orElseThrow();
        DispatchTicket claimed = requested.claim(
                worker, lease.id(), clock.instant().plusSeconds(240));
        assertThat(tickets.compareAndSet(
                requested.id(), requested.version(), claimed)).isTrue();
        DispatchTicket running = claimed.markRunning(
                clock.instant().plusSeconds(1));
        assertThat(tickets.compareAndSet(
                claimed.id(), claimed.version(), running)).isTrue();
        return new Running(running, lease);
    }

    private static ExecutionContext context(
            DispatchTicket running,
            CapacityManager.CapacityLease lease,
            CapacityManager capacity,
            String worker,
            Clock clock)
    {
        return new ExecutionContext(
                running.envelope(), lease,
                new ExecutionContext.Cancellation(), new NoopEvidence(),
                "execution-" + running.id(), clock,
                () -> capacity.requireExactLeaseForTicket(
                        running.id(), lease.id(),
                        running.envelope().capacityRequest(), worker));
    }

    private static DispatchTicket ticket(
            String ticketId,
            String operationId,
            String stageId,
            long stageGeneration,
            DispatchTicket.OwnerKind ownerKind,
            String ownerId,
            String callback,
            String operationKind,
            Instant createdAt)
    {
        CapacityManager.CapacityRequest capacity =
                new CapacityManager.CapacityRequest(
                        operationId, V2, Set.of(LOCAL_GIT),
                        new CapacityManager.CapacityScope(
                                "workspace", "trunk", "task", 1L),
                        false, true, true);
        return DispatchTicket.requested(
                ticketId,
                new DispatchTicket.DispatchEnvelope(
                        operationKind, DispatchTicket.AsyncFamily.LOCAL_GIT,
                        new DispatchTicket.OwnerReference(
                                ownerKind, ownerId, callback),
                        new DispatchTicket.OperationFence(
                                1L, stageId, stageGeneration, operationId, 1,
                                "fingerprint-1", "base", "base"),
                        capacity),
                createdAt);
    }

    private static void assertOpen(
            Fixture fixture, Operation operation, String operationStatus)
    {
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM worktree_quarantine_repair_operation_v318
                WHERE id = ?
                """, String.class, operation.id())).isEqualTo(operationStatus);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM agent_turn_worktree_quarantine_v318
                WHERE id = ?
                """, String.class, fixture.quarantine().id())).isEqualTo("OPEN");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE id = ?
                """, String.class, fixture.blockerId())).isEqualTo("OPEN");
    }

    private record Fixture(
            SqliteExecutionTestSupport.Database database,
            JdbcTemplate jdbc,
            SqliteDispatchTicketStore tickets,
            CapacityManager capacity,
            WorktreeWriterLeaseManager writers,
            TaskCommandExecutor commands,
            SqliteWorktreeQuarantineRepairStore store,
            WorktreeQuarantineRepairRuntime runtime,
            ObjectMapper json,
            Clock clock,
            String worktreePath,
            String branchName,
            WorktreeQuarantine quarantine,
            String blockerId)
    {
    }

    private record Running(
            DispatchTicket running,
            CapacityManager.CapacityLease capacity)
    {
    }

    private record PendingResult(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult raw)
    {
    }

    private static final class NoopEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        @Override
        public String start(
                DispatchTicket ticket,
                CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose,
                Instant startedAt)
        {
            return "execution";
        }

        @Override
        public void heartbeat(String executionId, Instant at) {}

        @Override
        public void providerSession(
                String executionId, String provider, String providerSessionId) {}

        @Override
        public void processStarted(
                String executionId, long processPid, String logReference) {}

        @Override
        public void appendLog(
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt) {}

        @Override
        public void recordUsage(
                String executionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli) {}

        @Override
        public void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt) {}
    }
}
