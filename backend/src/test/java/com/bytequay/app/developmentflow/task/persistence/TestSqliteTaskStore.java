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
package com.bytequay.app.developmentflow.task.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.CancellationToCleanupHandoff;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.stage.persistence.StagePersistenceTestSupport;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskControlMaintainer;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestSqliteTaskStore
{
    @TempDir
    private Path tempDir;

    @Test
    void idleArchiveSelectsOnlyDueTypedTasksAndLeavesLegacySiblingUntouched()
    {
        Path file = tempDir.resolve("idle-archive-selection.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(
                jdbc, "task-due", "plan-due", 1,
                now.minus(2, ChronoUnit.DAYS).toEpochMilli());
        seedActiveTaskWithCode(
                jdbc, "task-fresh", "plan-fresh", 2,
                now.minus(10, ChronoUnit.MINUTES).toEpochMilli());
        seedLegacySibling(
                jdbc, "task-legacy", 3,
                now.minus(3, ChronoUnit.DAYS).toEpochMilli());
        migrate(file, "263");

        ControlFixture fixture = controls(dataSource);
        assertThat(fixture.controls().idleArchiveCandidates(cutoff, now, 10))
                .containsExactly("task-due");
        assertThat(fixture.controls().archiveIfIdle("task-due", cutoff, now))
                .isTrue();
        assertThat(fixture.controls().archiveIfIdle("task-fresh", cutoff, now))
                .isFalse();

        assertThat(fixture.store().findById("task-due").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVING);
        assertThat(fixture.store().findById("task-fresh").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);
        assertThat(jdbc.queryForMap("""
                SELECT workflow_version, status, phase, lifecycle_state
                  FROM tasks WHERE id = 'task-legacy'
                """))
                .containsEntry("workflow_version", "LEGACY")
                .containsEntry("status", "IDLE")
                .containsEntry("phase", "PLANNING")
                .containsEntry("lifecycle_state", null);
        assertThat(count(jdbc, "task_transition", "task-legacy")).isZero();
        assertThat(count(jdbc, "task_command_receipt", "task-legacy")).isZero();
    }

    @Test
    void idleArchiveRechecksLiveTicketInsideSerializedTaskCommand()
    {
        Path file = tempDir.resolve("idle-archive-race.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(
                jdbc, "task-race", "plan-race", 1,
                now.minus(2, ChronoUnit.DAYS).toEpochMilli());
        migrate(file, "263");

        ControlFixture fixture = controls(dataSource);
        assertThat(fixture.controls().idleArchiveCandidates(cutoff, now, 10))
                .containsExactly("task-race");

        seedLivePlanTurn(jdbc, "task-race", "plan-race", "race");

        assertThat(fixture.controls().archiveIfIdle("task-race", cutoff, now))
                .isFalse();
        assertThat(fixture.store().findById("task-race").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);
        assertThat(count(jdbc, "task_transition", "task-race")).isZero();
        assertThat(count(jdbc, "task_command_receipt", "task-race")).isZero();
    }

    @Test
    void idleArchiveBlocksOpenAndReadyTypedWaitsInsideSerializedCommand()
    {
        Path file = tempDir.resolve("idle-archive-user-wait.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        long old = now.minus(2, ChronoUnit.DAYS).toEpochMilli();
        seedTrunk(jdbc);
        seedActiveTaskWithCode(jdbc, "task-wait", "plan-wait", 1, old);
        migrate(file, "263");
        seedLivePlanTurn(jdbc, "task-wait", "plan-wait", "wait");
        jdbc.update("""
                UPDATE task_turn SET status = 'RUNNING', started_at_ms = ?
                WHERE id = 'plan-turn-wait'
                """, old + 5);
        V2UserWaitStore waits = new V2UserWaitStore(jdbc);
        ActiveAgentContextRegistry.TypedOwner owner =
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        "plan-turn-wait", "plan-operation-wait");
        waits.insertQuestion(
                owner, "question-wait", "call-wait", "Choose", null,
                "[]", true, Instant.ofEpochMilli(old + 6));
        waits.recordUserWait(
                owner, "QUESTION", "question-wait", "digest", "evidence",
                Instant.ofEpochMilli(old + 7));
        jdbc.update("""
                UPDATE task_turn
                SET status = 'SUCCEEDED', finished_at_ms = ?
                WHERE id = 'plan-turn-wait'
                """, old + 7);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = 'plan-ticket-wait'");

        ControlFixture fixture = controls(dataSource);
        assertThat(fixture.controls().idleArchiveCandidates(cutoff, now, 10))
                .doesNotContain("task-wait");
        assertThat(fixture.controls().archiveIfIdle("task-wait", cutoff, now))
                .isFalse();

        waits.answerQuestion(
                "question-wait", 0, null, "continue", "user",
                Instant.ofEpochMilli(old + 8));
        assertThat(fixture.controls().archiveIfIdle("task-wait", cutoff, now))
                .isFalse();

        waits.cancelOpenWaitsForTask(
                "task-wait", "user", "Task canceled",
                Instant.ofEpochMilli(old + 9));
        assertThat(fixture.controls().archiveIfIdle("task-wait", cutoff, now))
                .isTrue();
    }

    @Test
    void idleArchiveCompletesAfterRestartAndRevivesThroughExactStageOwner()
    {
        Path file = tempDir.resolve("idle-archive-restart.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Instant now = Instant.now().minusMillis(100)
                .truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(
                jdbc, "task-revive", "plan-revive", 1,
                now.minus(2, ChronoUnit.DAYS).toEpochMilli());
        migrate(file, "263");

        ControlFixture beforeRestart = controls(dataSource);
        assertThat(beforeRestart.controls().archiveIfIdle(
                "task-revive", cutoff, now)).isTrue();

        ControlFixture restarted = controls(dataSource);
        TaskControlMaintainer archiveMaintainer = maintainer(
                restarted, List.of());
        archiveMaintainer.maintain(now);
        archiveMaintainer.maintain(now);
        assertThat(restarted.store().findById("task-revive")
                .orElseThrow().lifecycle()).isEqualTo(TaskLifecycle.ARCHIVED);

        assertThat(restarted.controls().resume("task-revive").lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);
        SqliteStageResumeRearmStore rearms =
                new SqliteStageResumeRearmStore(restarted.jdbc());
        TaskResumeOwner planOwner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return StageKind.PLAN;
            }

            @Override
            public Acceptance accept(Request request)
            {
                return rearms.accept(request, kind());
            }
        };
        TaskControlMaintainer resumeMaintainer = maintainer(
                restarted, List.of(planOwner));
        resumeMaintainer.maintain(now);
        resumeMaintainer.maintain(now);

        assertThat(restarted.store().findById("task-revive").orElseThrow())
                .returns(TaskLifecycle.ACTIVE, TaskManager.State::lifecycle)
                .returns("plan-revive", TaskManager.State::currentStageId);
        assertThat(restarted.jdbc().queryForMap("""
                SELECT task.status, task.phase, owner.kind, owner.generation,
                       owner.checkpoint
                  FROM tasks task
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                 WHERE task.id = 'task-revive'
                """))
                .containsEntry("status", "IDLE")
                .containsEntry("phase", "PLANNING")
                .containsEntry("kind", "PLAN")
                .containsEntry("generation", 1)
                .containsEntry("checkpoint", "DRAFTING");
        assertThat(count(restarted.jdbc(), "task_archive_liveness", "task-revive"))
                .isEqualTo(1);
        assertThat(count(restarted.jdbc(),
                "task_resume_reconciliation_v256", "task-revive"))
                .isEqualTo(1);
        assertThat(restarted.jdbc().queryForMap("""
                SELECT status, stage_kind, stage_id
                  FROM task_resume_handoff_v256
                 WHERE task_id = 'task-revive'
                """))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("stage_kind", "PLAN")
                .containsEntry("stage_id", "plan-revive");
        Instant afterCadence = now.plus(2, ChronoUnit.DAYS);
        assertThat(restarted.controls().idleArchiveCandidates(
                afterCadence.minus(1, ChronoUnit.DAYS), afterCadence, 10))
                .doesNotContain("task-revive");
        assertThat(count(restarted.jdbc(), "cleanup_stage", "task-revive"))
                .isZero();
        assertThat(restarted.jdbc().queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class))
                .isZero();
    }

    @Test
    void duplicateStaleRollbackRestartAndDeferredEvidenceFailClosed()
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("task.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTask(jdbc, "task-1", "plan-1", 1);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2TaskStore store = new V2TaskStore(jdbc);
        TaskManager manager = new TaskManager(commands, store);
        TaskManager.Command pause = new TaskManager.Command(
                "pause-1", "user", "task-1", 1, 1);

        assertThat(manager.requestPause(pause).state())
                .returns(TaskLifecycle.PAUSING, TaskManager.State::lifecycle)
                .returns(2L, TaskManager.State::version);
        assertThat(manager.requestPause(pause).disposition().name()).isEqualTo("DUPLICATE");

        TaskManager restarted = new TaskManager(
                commands, new V2TaskStore(new JdbcTemplate(dataSource)));
        assertThat(restarted.requestPause(pause).disposition().name()).isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "task_transition", "task-1")).isEqualTo(1);
        assertThat(count(jdbc, "task_command_receipt", "task-1")).isEqualTo(1);
        assertThatThrownBy(() -> restarted.requestArchive(new TaskManager.Command(
                "stale-1", "user", "task-1", 1, 1)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));

        seedActiveTask(jdbc, "task-cancel", "plan-cancel", 2);
        TaskManager cancelManager = new TaskManager(commands, store);
        TaskManager.Command cancel = new TaskManager.Command(
                "cancel-1", "user", "task-cancel", 1, 1);
        TaskManager.State canceled = cancelManager.requestCancel(cancel).state();
        assertThat(canceled)
                .returns(TaskLifecycle.CANCELING, TaskManager.State::lifecycle)
                .returns(TaskManager.TerminalOutcome.CANCELED,
                        TaskManager.State::terminalIntent);
        assertThat(new V2TaskStore(new JdbcTemplate(dataSource))
                .findById("task-cancel")).contains(canceled);
        assertThat(jdbc.queryForObject("""
                SELECT kind FROM task_terminal_intent
                WHERE task_id = 'task-cancel' AND accepted = 1
                """, String.class)).isEqualTo("CANCELED");

        seedActiveTask(jdbc, "task-rollback", "plan-rollback", 3);
        TaskManager.State expected = store.findById("task-rollback").orElseThrow();
        TaskManager.State updated = new TaskManager.State(
                expected.id(), expected.trunkId(), TaskLifecycle.PAUSING,
                expected.epoch(), expected.version() + 1, expected.currentStageId(),
                null, null, null, null);
        assertThatThrownBy(() -> commands.execute(
                "task-rollback",
                () -> store.commit(
                        "bad-command", "NOT_A_CAUSE", "user", 1L, 1L,
                        null, null, null, null, null, null, expected, updated)))
                .isInstanceOf(DataAccessException.class);
        assertThat(store.findById("task-rollback")).contains(expected);
        assertThat(count(jdbc, "task_transition", "task-rollback")).isZero();

        seedSatisfiedPauseBarrier(jdbc, "task-rollback", "barrier-1");
        assertThat(store.findSatisfiedQuiescence("task-rollback", "barrier-1"))
                .isPresent();
        assertThatThrownBy(() -> store.recordSuperseded(
                "outside", "ACCEPT_BRAIN_VERDICT", "brain", null, null,
                null, null, null, null, null, null, expected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command transaction");
    }

    @Test
    void typedControlEvidenceDrivesRealHandoffAndUsesLeaseExpiryAtEvidenceTime()
    {
        Path file = tempDir.resolve("typed-control.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(jdbc, "task-control", "plan-control", 1);
        seedActiveTaskWithCode(jdbc, "task-live-lease", "plan-live-lease", 2);
        long now = System.currentTimeMillis();
        seedRetainedWorktreeLease(jdbc, "task-control", "plan-control", 11, 20);
        seedRetainedWorktreeLease(
                jdbc, "task-live-lease", "plan-live-lease", 12, now + 60_000);
        migrate(file, "230");

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2TaskStore store = new V2TaskStore(jdbc);
        TaskManager manager = new TaskManager(commands, store);
        TaskControlHandoff handoff = new TaskControlHandoff(commands, manager);

        assertThat(manager.requestPause(new TaskManager.Command(
                "pause-live", "user", "task-live-lease", 1, 1)).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSING);
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES ('barrier-live', 'task-live-lease', 1, 'PAUSE', 'REQUESTED', ?)
                """, now);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = ?, evidence = 'legacy'
                WHERE id = 'barrier-live'
                """, now + 1)).isInstanceOf(DataAccessException.class);

        TaskManager.Command requestPause = new TaskManager.Command(
                "pause-control", "user", "task-control", 1, 1);
        assertThat(manager.requestPause(requestPause).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSING);
        long pauseAt = now + 2;
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES ('barrier-control', 'task-control', 1,
                    'PAUSE', 'REQUESTED', ?)
                """, pauseAt - 1);
        jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = ?, evidence = 'legacy'
                WHERE id = 'barrier-control'
                """, pauseAt);
        String pauseDigest = pauseDigest(
                "task-control", "barrier-control", "plan-control", pauseAt, pauseAt);
        assertThatThrownBy(() -> insertPauseEvidence(
                jdbc, "task-control", "barrier-control", "plan-control",
                pauseAt, pauseAt, "tampered"))
                .isInstanceOf(DataAccessException.class);
        insertPauseEvidence(
                jdbc, "task-control", "barrier-control", "plan-control",
                pauseAt, pauseAt, pauseDigest);

        TaskManager.PauseCompletionCommand pause = new TaskManager.PauseCompletionCommand(
                new TaskManager.Command("complete-pause", "system", "task-control", 1, 2),
                "barrier-control", "plan-control", 1,
                StageCheckpoint.DRAFTING, pauseDigest);
        assertThat(handoff.completePause(pause).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSED);
        assertThat(handoff.completePause(pause).disposition().name()).isEqualTo("DUPLICATE");

        assertThat(manager.requestResume(new TaskManager.Command(
                "request-resume", "user", "task-control", 1, 3)).state().lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);
        long resumeAt = pauseAt + 1;
        String resumeDigest = resumeDigest(
                "task-control", "resume-control", "barrier-control",
                "plan-control", resumeAt);
        insertResumeEvidence(jdbc, resumeAt, resumeDigest);
        TaskManager.ResumeCompletionCommand resume = new TaskManager.ResumeCompletionCommand(
                new TaskManager.Command("complete-resume", "system", "task-control", 1, 4),
                "resume-control", "plan-control", 1,
                StageCheckpoint.DRAFTING, resumeDigest);
        assertThat(handoff.completeResume(resume).state().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);

        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('archive-live-turn', 'task-control', 'BRAIN_REVIEW',
                    'REQUESTED', 'archive-live-operation', 1, 1,
                    'API', 'review', ?)
                """, resumeAt + 1);
        assertThat(manager.requestArchive(new TaskManager.Command(
                "request-archive", "user", "task-control", 1, 5)).state().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVING);
        long archiveAt = resumeAt + 2;
        String archiveDigest = archiveDigest(
                "task-control", "archive-control", "plan-control", archiveAt);
        assertThatThrownBy(() -> insertArchiveEvidence(jdbc, archiveAt, archiveDigest))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("""
                UPDATE task_turn
                SET status = 'CANCELED', finished_at_ms = ?
                WHERE id = 'archive-live-turn'
                """, archiveAt);
        insertArchiveEvidence(jdbc, archiveAt, archiveDigest);

        TaskManager.ArchiveCompletionCommand archive = new TaskManager.ArchiveCompletionCommand(
                new TaskManager.Command("complete-archive", "system", "task-control", 1, 6),
                "archive-control", "plan-control", 1, archiveDigest);
        assertThat(handoff.completeArchive(archive).state().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVED);
        assertThat(new V2TaskStore(new JdbcTemplate(dataSource))
                .findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class)).isZero();
    }

    @Test
    void maintenanceCompletesControlsAndBuildsCancellationCleanupExactlyOnce()
    {
        Path file = tempDir.resolve("task-control-runtime.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(jdbc, "task-control", "plan-control", 1);
        seedActiveTaskWithCode(jdbc, "task-sibling", "plan-sibling", 2);
        seedActiveTaskWithCode(jdbc, "task-cancel", "plan-cancel", 3);
        seedActiveTaskWithCode(
                jdbc, "task-legacy-cancel", "plan-legacy-cancel", 4);
        seedRetainedWorktreeLease(
                jdbc, "task-cancel", "plan-cancel", 31,
                System.currentTimeMillis() + 60_000);

        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor legacyCommands =
                new TaskCommandExecutor(transactionManager);
        new TaskManager(legacyCommands, new V2TaskStore(jdbc)).requestCancel(
                new TaskManager.Command(
                        "legacy-cancel-runtime", "user",
                        "task-legacy-cancel", 1, 1));
        jdbc.execute("DROP TRIGGER task_terminal_intent_immutable");
        jdbc.update("""
                UPDATE task_terminal_intent
                   SET source = 'REQUEST_CANCEL', source_id = NULL
                 WHERE task_id = 'task-legacy-cancel'
                """);
        jdbc.execute("""
                CREATE TRIGGER task_terminal_intent_immutable
                BEFORE UPDATE ON task_terminal_intent
                BEGIN SELECT RAISE(ABORT, 'task terminal intent is immutable'); END
                """);

        migrate(file, "256");
        assertThat(jdbc.queryForMap("""
                SELECT source, source_id FROM task_terminal_intent
                 WHERE task_id = 'task-legacy-cancel'
                """))
                .containsEntry("source", "USER_CANCEL")
                .containsEntry("source_id", "legacy-cancel-runtime");

        TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
        V2TaskStore taskStore = new V2TaskStore(jdbc);
        TaskManager tasks = new TaskManager(commands, taskStore);
        SqliteTaskControlRuntimeStore runtimeStore =
                new SqliteTaskControlRuntimeStore(jdbc, transactionManager);
        TaskControlHandoff controls = new TaskControlHandoff(commands, tasks);
        List<CancellationToCleanupHandoff> cancellationOwners = List.of(
                StagePersistenceTestSupport.cancellationToCleanup(
                        commands, jdbc, tasks));
        TaskControlMaintainer maintainerWithoutResumeOwner = new TaskControlMaintainer(
                runtimeStore,
                controls,
                cancellationOwners,
                ignored -> {});
        Instant now = Instant.now().minusSeconds(1);

        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('sibling-live-turn', 'task-sibling', 'BRAIN_REVIEW',
                    'REQUESTED', 'sibling-live-operation', 1, 1,
                    'API', 'review', ?)
                """, now.toEpochMilli());
        assertThat(tasks.requestPause(new TaskManager.Command(
                "pause-runtime", "user", "task-control", 1, 1))
                .state().lifecycle()).isEqualTo(TaskLifecycle.PAUSING);
        maintainerWithoutResumeOwner.maintain(now);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSED);
        assertThat(taskStore.findById("task-sibling").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);

        assertThat(tasks.requestResume(new TaskManager.Command(
                "resume-paused-runtime", "user", "task-control", 1, 3))
                .state().lifecycle()).isEqualTo(TaskLifecycle.RESUMING);
        maintainerWithoutResumeOwner.maintain(now);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);
        assertThat(jdbc.queryForMap("""
                SELECT status, stage_kind, task_version
                  FROM task_resume_handoff_v256
                 WHERE task_id = 'task-control'
                """))
                .containsEntry("status", "PENDING")
                .containsEntry("stage_kind", "PLAN")
                .containsEntry("task_version", 4);

        var resumeEvidence = jdbc.queryForMap("""
                SELECT id, reconciliation_digest
                  FROM task_resume_reconciliation_v256
                 WHERE task_id = 'task-control'
                """);
        assertThatThrownBy(() -> controls.completeResume(
                new TaskManager.ResumeCompletionCommand(
                        new TaskManager.Command(
                                "complete-resume-without-owner", "system",
                                "task-control", 1, 4),
                        (String) resumeEvidence.get("id"), "plan-control", 1,
                        StageCheckpoint.DRAFTING,
                        (String) resumeEvidence.get("reconciliation_digest"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);

        TaskResumeOwner planResumeOwner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return StageKind.PLAN;
            }

            @Override
            public Acceptance accept(Request request)
            {
                assertThat(request.taskId()).isEqualTo("task-control");
                assertThat(request.stageId()).isEqualTo("plan-control");
                assertThat(request.restoreCheckpoint())
                        .isEqualTo(StageCheckpoint.DRAFTING);
                return new Acceptance(
                        request.handoffId(),
                        "plan-rearm:" + request.handoffId(),
                        "test-plan-runtime");
            }
        };
        TaskControlMaintainer maintainer = new TaskControlMaintainer(
                runtimeStore,
                controls,
                List.of(planResumeOwner),
                cancellationOwners,
                ignored -> {});
        maintainer.maintain(now);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);
        assertThat(jdbc.queryForMap("""
                SELECT status, owner_proof_id, accepted_by
                  FROM task_resume_handoff_v256
                 WHERE task_id = 'task-control'
                """))
                .containsEntry("status", "ACCEPTED")
                .containsEntry("accepted_by", "test-plan-runtime");

        assertThat(tasks.requestPause(new TaskManager.Command(
                "pause-runtime-again", "user", "task-control", 1, 5))
                .state().lifecycle()).isEqualTo(TaskLifecycle.PAUSING);
        maintainer.maintain(now);
        assertThat(tasks.requestResume(new TaskManager.Command(
                "resume-paused-runtime-again", "user", "task-control", 1, 7))
                .state().lifecycle()).isEqualTo(TaskLifecycle.RESUMING);
        maintainer.maintain(now);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_quiescence_barrier
                 WHERE task_id = 'task-control' AND reason = 'PAUSE'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_resume_reconciliation_v256
                 WHERE task_id = 'task-control' AND source_kind = 'PAUSE'
                """, Integer.class)).isEqualTo(2);

        assertThat(tasks.requestArchive(new TaskManager.Command(
                "archive-runtime", "user", "task-control", 1, 9))
                .state().lifecycle()).isEqualTo(TaskLifecycle.ARCHIVING);
        maintainer.maintain(now);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVED);

        assertThat(tasks.requestResume(new TaskManager.Command(
                "resume-archived-runtime", "user", "task-control", 1, 11))
                .state().lifecycle()).isEqualTo(TaskLifecycle.RESUMING);
        maintainer.maintain(now);
        assertThat(taskStore.findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);

        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('cancel-old-turn', 'task-cancel', 'BRAIN_REVIEW',
                    'REQUESTED', 'cancel-old-operation', 1, 1,
                    'API', 'review', ?)
                """, now.toEpochMilli());
        assertThat(tasks.requestCancel(new TaskManager.Command(
                "cancel-runtime", "user", "task-cancel", 1, 1))
                .state().lifecycle()).isEqualTo(TaskLifecycle.CANCELING);
        maintainer.maintain(now);
        assertThat(taskStore.findById("task-cancel").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.CANCELING);
        assertThat(jdbc.queryForMap("""
                SELECT source, source_id FROM task_terminal_intent
                 WHERE task_id = 'task-cancel'
                """))
                .containsEntry("source", "USER_CANCEL")
                .containsEntry("source_id", "cancel-runtime");
        assertThat(count(jdbc, "task_terminal_acceptance", "task-cancel"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM task_quiescence_barrier
                 WHERE task_id = 'task-cancel' AND reason = 'CANCEL'
                """, String.class)).isEqualTo("REQUESTED");

        jdbc.update("""
                UPDATE task_turn SET status = 'CANCELED', finished_at_ms = ?
                 WHERE id = 'cancel-old-turn'
                """, now.toEpochMilli());
        jdbc.update("DELETE FROM worktree_leases WHERE task_id = 'task-cancel'");
        maintainer.maintain(now);
        assertThat(taskStore.findById("task-cancel").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.CLEANING);
        assertThat(jdbc.queryForMap("""
                SELECT kind, checkpoint, end_reason FROM stage
                 WHERE id = (SELECT stage_id FROM task_current_stage
                              WHERE task_id = 'task-cancel')
                """))
                .containsEntry("kind", "CLEANUP")
                .containsEntry("checkpoint", "WAITING_QUIESCENCE")
                .containsEntry("end_reason", null);
        assertThat(jdbc.queryForObject("""
                SELECT end_reason FROM stage WHERE id = 'plan-cancel'
                """, String.class)).isEqualTo("TASK_CANCELED");

        maintainer.maintain(now);
        assertThat(count(jdbc, "cleanup_stage", "task-cancel")).isEqualTo(1);
        assertThat(count(jdbc, "cleanup_operation", "task-cancel")).isEqualTo(1);
        assertThat(count(jdbc, "cleanup_step", "task-cancel")).isEqualTo(11);
        assertThat(count(jdbc, "dispatch_ticket", "task-cancel")).isEqualTo(1);

        ArrayList<String> restartedCancellations = new ArrayList<>();
        DataSourceTransactionManager restartedTransactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor restartedCommands =
                new TaskCommandExecutor(restartedTransactions);
        V2TaskStore restartedStore = new V2TaskStore(new JdbcTemplate(dataSource));
        TaskManager restartedTasks = new TaskManager(restartedCommands, restartedStore);
        TaskControlMaintainer restartedMaintainer = new TaskControlMaintainer(
                new SqliteTaskControlRuntimeStore(
                        new JdbcTemplate(dataSource), restartedTransactions),
                new TaskControlHandoff(restartedCommands, restartedTasks),
                List.of(StagePersistenceTestSupport.cancellationToCleanup(
                        restartedCommands,
                        new JdbcTemplate(dataSource),
                        restartedTasks)),
                restartedCancellations::add);
        restartedMaintainer.maintain(now);
        restartedMaintainer.maintain(now);

        assertThat(restartedCancellations).isEmpty();
        assertThat(count(jdbc, "task_terminal_acceptance", "task-cancel"))
                .isEqualTo(1);
        assertThat(count(jdbc, "cleanup_stage", "task-cancel")).isEqualTo(1);
        assertThat(count(jdbc, "cleanup_operation", "task-cancel")).isEqualTo(1);
        assertThat(count(jdbc, "cleanup_step", "task-cancel")).isEqualTo(11);
        assertThat(count(jdbc, "dispatch_ticket", "task-cancel")).isEqualTo(1);
        jdbc.update("""
                UPDATE task_turn SET status = 'SUCCEEDED'
                 WHERE id = 'cancel-old-turn'
                """);
        restartedMaintainer.maintain(now);
        assertThat(restartedStore.findById("task-cancel").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.CLEANING);
        assertThat(count(jdbc, "cleanup_operation", "task-cancel")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class)).isZero();
    }

    @Test
    void resumeOwnerMaterializesOnePlanSuccessorOnlyAfterActiveAndRollsBackFaults()
    {
        Path file = tempDir.resolve("resume-rearm.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(jdbc, "task-resume", "plan-resume", 1);
        seedActiveTaskWithCode(jdbc, "task-fault", "plan-fault", 2);
        seedActiveTaskWithCode(jdbc, "task-sibling", "plan-sibling", 3);
        migrate(file, "257");
        seedCanceledPlanTurn(jdbc, "task-resume", "plan-resume", "current");
        seedCanceledPlanTurn(jdbc, "task-fault", "plan-fault", "fault");

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2TaskStore taskStore = new V2TaskStore(jdbc);
        TaskManager tasks = new TaskManager(commands, taskStore);
        TaskControlHandoff controls = new TaskControlHandoff(commands, tasks);
        SqliteTaskControlRuntimeStore controlsStore =
                new SqliteTaskControlRuntimeStore(jdbc, transactions);
        SqliteStageResumeRearmStore rearms =
                new SqliteStageResumeRearmStore(jdbc);
        Instant now = Instant.now().minusMillis(100);

        TaskResumeOwner planOwner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return StageKind.PLAN;
            }

            @Override
            public Acceptance accept(Request request)
            {
                return rearms.accept(request, kind());
            }
        };
        TaskControlMaintainer noOwner = new TaskControlMaintainer(
                controlsStore, controls, List.of(), List.of(), ignored -> {});
        TaskControlMaintainer withOwner = new TaskControlMaintainer(
                controlsStore, controls, List.of(planOwner), List.of(), ignored -> {});

        pauseAndRequestResume(jdbc, tasks, noOwner, "task-resume", now);
        TaskResumeOwner.Request parked = resumeRequest(jdbc, "task-resume");
        commands.execute("task-resume", () ->
                rearms.accept(parked, StageKind.PLAN));
        assertThat(taskStore.findById("task-resume").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_resume_rearm_successor_v257
                WHERE handoff_id = ?
                """, Integer.class, parked.handoffId())).isZero();

        withOwner.maintain(now.plusMillis(1));
        assertThat(taskStore.findById("task-resume").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);
        SqliteStageResumeRearmStore.Intent intent = rearms
                .pending(StageKind.PLAN, 10).stream()
                .filter(candidate -> candidate.taskId().equals("task-resume"))
                .findFirst().orElseThrow();
        commands.executeVoid("task-resume", () ->
                rearms.materializePlanDraft(intent, now.plusMillis(2)));

        assertThat(jdbc.queryForMap("""
                SELECT intent.status, successor.status AS successor_status,
                       successor.semantic_attempt, successor.owner_kind
                FROM stage_resume_rearm_intent_v257 intent
                JOIN stage_resume_rearm_successor_v257 successor
                  ON successor.handoff_id = intent.handoff_id
                WHERE intent.task_id = 'task-resume'
                """))
                .containsEntry("status", "MATERIALIZED")
                .containsEntry("successor_status", "ARMED")
                .containsEntry("semantic_attempt", 2)
                .containsEntry("owner_kind", "TASK_TURN");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE task_id = 'task-resume' AND purpose = 'PLAN_DRAFT'
                  AND status = 'REQUESTED'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE task_id = 'task-sibling'
                """, Integer.class)).isZero();
        SqliteStageResumeRearmStore restarted =
                new SqliteStageResumeRearmStore(new JdbcTemplate(dataSource));
        assertThat(restarted.pending(StageKind.PLAN, 10))
                .noneMatch(candidate -> candidate.taskId().equals("task-resume"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_resume_rearm_successor_v257
                WHERE handoff_id = ?
                """, Integer.class, parked.handoffId())).isEqualTo(1);

        pauseAndRequestResume(
                jdbc, tasks, noOwner, "task-fault", now.plusMillis(3));
        withOwner.maintain(now.plusMillis(4));
        SqliteStageResumeRearmStore.Intent fault = rearms
                .pending(StageKind.PLAN, 10).stream()
                .filter(candidate -> candidate.taskId().equals("task-fault"))
                .findFirst().orElseThrow();
        jdbc.execute("""
                CREATE TRIGGER fail_resume_ticket
                BEFORE INSERT ON dispatch_ticket
                WHEN EXISTS (
                    SELECT 1 FROM stage_resume_rearm_successor_v257 successor
                    WHERE successor.operation_id = NEW.operation_id
                      AND successor.status = 'PREPARED')
                BEGIN SELECT RAISE(ABORT, 'injected resume ticket failure'); END
                """);
        assertThatThrownBy(() -> commands.executeVoid("task-fault", () ->
                rearms.materializePlanDraft(fault, now.plusMillis(5))))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_resume_rearm_successor_v257
                WHERE handoff_id = ?
                """, Integer.class, fault.handoffId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE task_id = 'task-fault' AND attempt = 2
                """, Integer.class)).isZero();
        jdbc.execute("DROP TRIGGER fail_resume_ticket");
        commands.executeVoid("task-fault", () ->
                rearms.materializePlanDraft(fault, now.plusMillis(6)));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stage_resume_rearm_successor_v257
                WHERE handoff_id = ? AND status = 'ARMED'
                """, Integer.class, fault.handoffId())).isEqualTo(1);
    }

    @Test
    void policyCommandsAppendAndSelectRevisionsWithoutInvalidatingFrozenAutomation()
    {
        Path file = tempDir.resolve("task-policy.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTask(jdbc, "task-policy", "plan-policy", 1);
        migrate(file, "249");
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TaskManager manager = new TaskManager(commands, new V2TaskStore(jdbc));
        TaskManager.PolicyCommand first = new TaskManager.PolicyCommand(
                new TaskManager.Command(
                        "revise-policy-1", "user", "task-policy", 1, 1),
                "policy-2", true, true, 2, 4, 5, true, "permission-2");

        TaskManager.PolicyRevision selected = manager.revisePolicy(first).state();

        assertThat(selected)
                .returns("policy-2", TaskManager.PolicyRevision::id)
                .returns(2, TaskManager.PolicyRevision::revision)
                .returns(true, TaskManager.PolicyRevision::autoMerge)
                .returns(2, TaskManager.PolicyRevision::minApprovals);
        assertThat(new V2TaskStore(new JdbcTemplate(dataSource))
                .findById("task-policy").orElseThrow().version()).isEqualTo(2);
        assertThat(manager.revisePolicy(first).disposition().name())
                .isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "task_policy_command_receipt", "task-policy"))
                .isEqualTo(1);
        assertThatThrownBy(() -> manager.requestPause(new TaskManager.Command(
                "revise-policy-1", "user", "task-policy", 1, 2)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason().name())
                                .isEqualTo("COMMAND_ID_CONFLICT"));
        assertThatThrownBy(() -> manager.revisePolicy(new TaskManager.PolicyCommand(
                new TaskManager.Command(
                        "stale-policy", "user", "task-policy", 1, 1),
                "policy-stale", true, false, 1, 4, 5, true, null)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_policy_revision SET min_approvals = 0
                WHERE id = 'policy-2'
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE tasks
                SET policy_revision_id = 'policy-1', aggregate_version = 3
                WHERE id = 'task-policy'
                """)).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-1', 'task-policy', 1, 'PUBLISH', 1, 1,
                    0, 2, 2, 1, 1, 0, 'system', 10)
                """);
        TaskManager.PolicyRevision changed = manager.revisePolicy(
                new TaskManager.PolicyCommand(
                        new TaskManager.Command(
                                "revise-policy-2", "user", "task-policy", 1, 2),
                        "policy-3", false, false, 1, 6, 7, false, null))
                .state();

        assertThat(changed.id()).isEqualTo("policy-3");
        assertThat(jdbc.queryForMap("""
                SELECT revision, auto_approve, auto_merge,
                       minimum_write_approvals
                FROM task_automation_policy
                WHERE task_id = 'task-policy'
                ORDER BY revision DESC LIMIT 1
                """))
                .containsEntry("revision", 2)
                .containsEntry("auto_approve", 0)
                .containsEntry("auto_merge", 0)
                .containsEntry("minimum_write_approvals", 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class)).isZero();
    }

    @Test
    void remoteClosedCleanupKeepsItsObservedHeadWithoutASubjectFence()
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("remote-closed.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTask(jdbc, "task-closed", "remote-closed", 1);
        V2TaskStore store = new V2TaskStore(jdbc);
        TaskManager.State expected = store.findById("task-closed").orElseThrow();
        TaskManager.State updated = new TaskManager.State(
                expected.id(), expected.trunkId(), TaskLifecycle.CLEANING,
                expected.epoch(), expected.version() + 1, "cleanup-closed",
                null, null, null, TaskManager.TerminalOutcome.REMOTE_CLOSED);
        ResultFence observation = new ResultFence(
                1, "remote-closed", 1, "observe-operation-1", 1,
                null, "head-1", "base-1");

        // Seal the open Stage and hand off to Cleanup inside the command
        // transaction — the current-Stage foreign key is deferred to commit.
        new TaskCommandExecutor(new DataSourceTransactionManager(dataSource))
                .execute("task-closed", () -> {
                    jdbc.update("""
                            UPDATE stage
                            SET checkpoint = 'COMPLETED', end_reason = 'NORMAL',
                                completed_at_ms = 3, version = version + 1
                            WHERE id = 'remote-closed'
                            """);
                    jdbc.update("""
                            INSERT INTO stage(
                                id, task_id, kind, generation, version,
                                checkpoint, opened_at_ms)
                            VALUES ('cleanup-closed', 'task-closed', 'CLEANUP',
                                1, 0, 'WAITING_QUIESCENCE', 4)
                            """);
                    return store.commit(
                            "close-command", "OPEN_REMOTE_CLOSED_CLEANUP", "observer",
                            1L, 1L, observation, null, "observation-1",
                            "cleanup-closed", StageKind.CLEANUP, 1L, expected, updated);
                });

        assertThat(jdbc.queryForObject("""
                SELECT subject_operation_id FROM task_command_receipt
                WHERE task_id = 'task-closed'
                """, String.class)).isNull();
        assertThat(jdbc.queryForMap("""
                SELECT source, source_id, observed_head_sha
                FROM task_terminal_intent WHERE task_id = 'task-closed'
                """))
                .containsEntry("source", "REMOTE_OBSERVATION")
                .containsEntry("source_id", "observation-1")
                .containsEntry("observed_head_sha", "head-1");
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void pauseAndRequestResume(
            JdbcTemplate jdbc, TaskManager tasks, TaskControlMaintainer maintainer,
            String taskId, Instant now)
    {
        long version = taskVersion(jdbc, taskId);
        tasks.requestPause(new TaskManager.Command(
                "pause-" + taskId, "user", taskId, 1, version));
        maintainer.maintain(now);
        tasks.requestResume(new TaskManager.Command(
                "resume-" + taskId, "user", taskId, 1,
                taskVersion(jdbc, taskId)));
        maintainer.maintain(now.plusMillis(1));
    }

    private static long taskVersion(JdbcTemplate jdbc, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = ?",
                Long.class, taskId);
    }

    private static TaskResumeOwner.Request resumeRequest(
            JdbcTemplate jdbc, String taskId)
    {
        return jdbc.queryForObject("""
                SELECT id, task_id, task_epoch, task_version, stage_id,
                       stage_kind, stage_generation, stage_version,
                       restore_checkpoint, reconciliation_id,
                       code_fingerprint, head_sha, base_sha
                FROM task_resume_handoff_v256
                WHERE task_id = ? AND status = 'PENDING'
                """, (rs, row) -> new TaskResumeOwner.Request(
                        rs.getString("id"), rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getLong("task_version"),
                        rs.getString("stage_id"),
                        StageKind.valueOf(rs.getString("stage_kind")),
                        rs.getLong("stage_generation"),
                        rs.getLong("stage_version"),
                        StageCheckpoint.valueOf(
                                rs.getString("restore_checkpoint")),
                        rs.getString("reconciliation_id"),
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha")),
                taskId);
    }

    private static void seedCanceledPlanTurn(
            JdbcTemplate jdbc, String taskId, String stageId, String suffix)
    {
        seedLivePlanTurn(jdbc, taskId, stageId, suffix);
        String turnId = "plan-turn-" + suffix;
        String ticketId = "plan-ticket-" + suffix;
        jdbc.update("""
                UPDATE task_turn SET status = 'CANCELED', started_at_ms = 12,
                    finished_at_ms = 13, error_message = 'paused'
                WHERE id = ?
                """, turnId);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'CANCELED', cancel_requested_at_ms = 12,
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'paused', completed_at_ms = 13
                WHERE id = ?
                """, ticketId);
        jdbc.update("""
                INSERT INTO plan_task_turn_delivery_receipt(
                    task_turn_id, operation_id, raw_outcome,
                    raw_evidence_digest, acceptance, domain_result,
                    recorded_at_ms)
                VALUES (?, ?, 'CANCELED', ?, 'ACCEPTED', 'TURN_CANCELED', 13)
                """, turnId, "plan-operation-" + suffix, "d".repeat(64));
    }

    private static void seedLivePlanTurn(
            JdbcTemplate jdbc, String taskId, String stageId, String suffix)
    {
        String turnId = "plan-turn-" + suffix;
        String operationId = "plan-operation-" + suffix;
        jdbc.update("""
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES (?, ?, 'openai', 'model', 'engine', 10)
                """, "brain-" + suffix, taskId);
        String launch = """
                {"schemaVersion":1,"transport":"API","provider":"openai",
                 "model":"model","workingDirectory":"%s","prompt":"draft",
                 "toolEndpoint":{"serverName":"bytequay",
                 "url":"http://127.0.0.1:8080/api/v2/task-turns/%s/operations/%s/mcp",
                 "ownerKind":"TASK_TURN","ownerId":"%s",
                 "operationId":"%s","profile":"TASK_BRAIN_READ_ONLY",
                 "approvalPromptTool":"mcp__bytequay__approval_prompt"}}
                """.formatted("/tmp/" + taskId, turnId, operationId,
                turnId, operationId);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'PLAN_DRAFT', 'REQUESTED', ?, 1, 1, ?, 1,
                    'fp-code', 'base-code', 'base-code', 'API', ?, 11)
                """, turnId, taskId, operationId, stageId, launch);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', ?, 'TASK_TURN_RESULT', 2, 1, 0,
                    'workspace-1', 'trunk-1', ?, 1, ?, 1, 1,
                    'fp-code', 'base-code', 'base-code', 'REQUESTED', 11)
                """, "plan-ticket-" + suffix, operationId, turnId, taskId, stageId);
    }

    private static void migrate(Path file, String target)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static void seedTrunk(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'workspace-1', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'trunk-1', 'IDLE', 'test',
                    0, 0, 0, 1, 1, 'workspace-1', 'build', 2, 'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'test', 1)
                """);
    }

    private static void seedActiveTask(
            JdbcTemplate jdbc, String taskId, String stageId, int sequence)
    {
        String assignment = "assignment-" + taskId;
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', 'base', 'seed', 'prompt',
                    'test', 1)
                """, assignment);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', 1,
                    'V2', 'PROVISIONING', ?, 'policy-1')
                """, taskId, sequence, assignment);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'PLAN', 1, 0, 'DRAFTING', 2)
                """, stageId, taskId);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, stageId);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
    }

    private static void seedSatisfiedPauseBarrier(
            JdbcTemplate jdbc, String taskId, String barrierId)
    {
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES (?, ?, 1, 'PAUSE', 'REQUESTED', 1)
                """, barrierId, taskId);
        jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = 2, evidence = 'opaque'
                WHERE id = ?
                """, barrierId);
    }

    private static void seedActiveTaskWithCode(
            JdbcTemplate jdbc, String taskId, String stageId, int sequence)
    {
        seedActiveTaskWithCode(jdbc, taskId, stageId, sequence, 1);
    }

    private static void seedActiveTaskWithCode(
            JdbcTemplate jdbc, String taskId, String stageId, int sequence,
            long createdAt)
    {
        String assignment = "assignment-" + taskId;
        String provision = "provision-" + taskId;
        String operation = "provision-operation-" + taskId;
        String ticket = "provision-ticket-" + taskId;
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', 'base-code',
                    'seed', 'prompt', 'test', ?)
                """, assignment, createdAt);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', ?,
                    'V2', 'PROVISIONING', ?, 'policy-1')
                """, taskId, sequence, createdAt, assignment);
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES (?, ?, 'policy-1', 'DIRECT_USER', 'acme/widget',
                    'acme/widget', 'base-code', 'engine', 'work-model', ?)
                """, taskId, assignment, createdAt + 1);
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES (?, ?, 1, ?, ?, 1, 'acme/widget', 'base-code', ?, ?,
                    'REQUESTED', ?)
                """, provision, taskId, assignment, operation,
                "dev/" + taskId, "/tmp/" + taskId, createdAt + 2);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES (?, ?, 'PROVISION_TASK', 'LOCAL_GIT', 'TASK', ?,
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, 1, 'base-code', 'REQUESTED', ?)
                """, ticket, operation, taskId, taskId, createdAt + 2);
        jdbc.update("UPDATE provision_task_operation SET status = 'DISPATCHED' WHERE id = ?",
                provision);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'worktree-created',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = ?, pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'base-code'
                WHERE id = ?
                """, operation, ticket);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-code',
                    result_head_sha = 'base-code',
                    result_code_fingerprint = 'fp-code', completed_at_ms = ?
                WHERE id = ?
                """, createdAt + 3, provision);
        jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, 'acme/widget', 'acme/widget', ?, ?,
                    'base-code', 'base-code', 'fp-code', ?, ?)
                """, taskId, provision, "dev/" + taskId, "/tmp/" + taskId,
                createdAt + 3, createdAt + 3);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = ?", ticket);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'PLAN', 1, 0, 'DRAFTING', ?)
                """, stageId, taskId, createdAt + 4);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, stageId);
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
        jdbc.update("""
                INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                VALUES (?, ?, 1, 1)
                """, stageId, taskId);
    }

    private static void seedLegacySibling(
            JdbcTemplate jdbc, String taskId, int sequence, long createdAt)
    {
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', ?, 'LEGACY')
                """, taskId, sequence, createdAt);
    }

    private static ControlFixture controls(SQLiteDataSource dataSource)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2TaskStore store = new V2TaskStore(jdbc);
        TaskManager tasks = new TaskManager(commands, store);
        V2TaskControlService controls = new V2TaskControlService(
                tasks, store, mock(DispatchTicketControl.class),
                mock(RemoteCiRepairRuntimeCoordinator.class), jdbc);
        return new ControlFixture(jdbc, transactions, commands, store, tasks, controls);
    }

    private static TaskControlMaintainer maintainer(
            ControlFixture fixture, List<TaskResumeOwner> resumeOwners)
    {
        return new TaskControlMaintainer(
                new SqliteTaskControlRuntimeStore(
                        fixture.jdbc(), fixture.transactions()),
                new TaskControlHandoff(fixture.commands(), fixture.tasks()),
                resumeOwners, List.of(), ignored -> {});
    }

    private record ControlFixture(
            JdbcTemplate jdbc,
            DataSourceTransactionManager transactions,
            TaskCommandExecutor commands,
            V2TaskStore store,
            TaskManager tasks,
            V2TaskControlService controls) {}

    private static void seedRetainedWorktreeLease(
            JdbcTemplate jdbc, String taskId, String stageId, int fence, long expiresAt)
    {
        String ticket = "lease-ticket-" + taskId;
        String operation = "lease-operation-" + taskId;
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'LOCAL_WORKTREE_WRITE', 'LOCAL_GIT', 'STAGE', ?,
                    'STAGE_LOCAL_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, ?, 1, 1, 'fp-code', 'base-code', 'base-code',
                    'REQUESTED', 10)
                """, ticket, operation, stageId, taskId, stageId);
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES (?, ?, ?, 'V2', 16, 0, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, ?, ?, 10, 10, ?)
                """, "lease-capacity-" + taskId, ticket, operation, taskId,
                "holder-" + taskId, fence, expiresAt);
        jdbc.update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES (?, ?, 'CLI_AGENT', 10, ?, 'V2', ?, 1, ?, ?)
                """, "/tmp/" + taskId, taskId, expiresAt, operation, fence,
                "holder-" + taskId);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = ?", ticket);
    }

    private static void insertPauseEvidence(
            JdbcTemplate jdbc, String taskId, String barrierId, String stageId,
            long barrierAt, long recordedAt, String digest)
    {
        jdbc.update("""
                INSERT INTO task_pause_evidence(
                    barrier_id, task_id, task_epoch, stage_id, stage_generation,
                    restore_checkpoint, code_fingerprint, head_sha, base_sha,
                    barrier_completed_at_ms, stop_evidence_digest, status,
                    recorded_at_ms)
                VALUES (?, ?, 1, ?, 1, 'DRAFTING', 'fp-code', 'base-code',
                    'base-code', ?, ?, 'SATISFIED', ?)
                """, barrierId, taskId, stageId, barrierAt, digest, recordedAt);
    }

    private static void insertResumeEvidence(
            JdbcTemplate jdbc, long recordedAt, String digest)
    {
        jdbc.update("""
                INSERT INTO task_resume_reconciliation(
                    id, task_id, task_epoch, pause_barrier_id, stage_id,
                    stage_generation, restore_checkpoint,
                    paused_code_fingerprint, paused_head_sha, paused_base_sha,
                    active_task_turn_count, active_stage_turn_count,
                    active_plan_review_count, active_validation_count,
                    active_brain_episode_count, active_provision_operation_count,
                    active_writer_dispatch_count, active_agent_execution_count,
                    unreconciled_execution_count, live_writer_capacity_count,
                    live_worktree_lease_count, active_publish_operation_count,
                    unreconciled_publish_operation_count,
                    active_publish_effect_count, reconciliation_digest,
                    status, recorded_at_ms)
                VALUES ('resume-control', 'task-control', 1, 'barrier-control',
                    'plan-control', 1, 'DRAFTING', 'fp-code', 'base-code',
                    'base-code', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    ?, 'SATISFIED', ?)
                """, digest, recordedAt);
    }

    private static void insertArchiveEvidence(
            JdbcTemplate jdbc, long recordedAt, String digest)
    {
        jdbc.update("""
                INSERT INTO task_archive_liveness(
                    id, task_id, task_epoch, stage_id, stage_generation,
                    active_task_turn_count, active_stage_turn_count,
                    active_review_turn_count, active_plan_review_count,
                    active_validation_count, active_brain_episode_count,
                    active_provision_operation_count, active_dispatch_count,
                    active_agent_execution_count, unreconciled_execution_count,
                    live_capacity_lease_count, live_worktree_lease_count,
                    active_quiescence_count, active_replan_count,
                    active_feedback_batch_count, active_publish_operation_count,
                    unreconciled_publish_operation_count,
                    active_publish_effect_count,
                    active_publish_authorization_count, open_permission_count,
                    accepted_terminal_intent_count, open_cleanup_stage_count,
                    liveness_digest, status, recorded_at_ms)
                VALUES ('archive-control', 'task-control', 1, 'plan-control', 1,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, ?, 'SATISFIED', ?)
                """, digest, recordedAt);
    }

    private static String pauseDigest(
            String taskId, String barrierId, String stageId,
            long barrierAt, long recordedAt)
    {
        return "pause:v1:" + hex(taskId) + ":1:" + hex(barrierId) + ":"
                + hex(stageId) + ":1:" + hex("DRAFTING") + ":"
                + hex("fp-code") + ":" + hex("base-code") + ":"
                + hex("base-code") + ":" + barrierAt + ":" + recordedAt;
    }

    private static String resumeDigest(
            String taskId, String reconciliationId, String barrierId,
            String stageId, long recordedAt)
    {
        return "resume:v1:" + hex(taskId) + ":1:" + hex(reconciliationId) + ":"
                + hex(barrierId) + ":" + hex(stageId) + ":1:"
                + hex("DRAFTING") + ":" + hex("fp-code") + ":"
                + hex("base-code") + ":" + hex("base-code")
                + ":0".repeat(14) + ":" + recordedAt;
    }

    private static String archiveDigest(
            String taskId, String evidenceId, String stageId, long recordedAt)
    {
        return "archive:v1:" + hex(taskId) + ":1:" + hex(evidenceId) + ":"
                + hex(stageId) + ":1" + ":0".repeat(22) + ":" + recordedAt;
    }

    private static String hex(String value)
    {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int count(JdbcTemplate jdbc, String table, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id = ?",
                Integer.class,
                taskId);
    }
}
