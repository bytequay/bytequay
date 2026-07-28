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
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteTaskStore
{
    @TempDir
    private Path tempDir;

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
        assertThat(store.findPauseEvidence("task-rollback", "barrier-1")).isEmpty();
        assertThat(store.findResumeEvidence("task-rollback", "resume-1")).isEmpty();
        assertThat(store.findArchiveEvidence("task-rollback", "archive-1")).isEmpty();
        assertThatThrownBy(() -> store.recordSuperseded(
                "outside", "ACCEPT_BRAIN_VERDICT", "brain", null, null,
                null, null, null, null, null, null, expected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command transaction");
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
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

    private static int count(JdbcTemplate jdbc, String table, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id = ?",
                Integer.class,
                taskId);
    }
}
