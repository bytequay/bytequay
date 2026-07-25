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
package com.bytequay.app.repository.sqlite.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrade fixture for the turn-liveness backfill: code-executing turn
 * roles gain the flag, each projectable task gets its pointer from
 * exact evidence, an ERRORED task keeps its provable failed turn for
 * the retry intent, and ambiguity parks visibly instead of guessing.
 */
class TestBackfillTurnLiveness
{
    @TempDir
    private Path tempDir;

    @Test
    void classifiesTurnsAndBackfillsPointers()
            throws Exception
    {
        String url = seedAt201("liveness.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            task(connection, "task-run", 1, "RUNNING", "IMPLEMENTING");
            turn(connection, "t-run", "task-run", "RUNNING", "user", 10);

            task(connection, "task-idle", 2, "IDLE", "IMPLEMENTING");
            turn(connection, "t-old", "task-idle", "COMPLETED", "user", 10);
            turn(connection, "t-q1", "task-idle", "QUEUED", "local-ci-fix", 20);
            turn(connection, "t-q2", "task-idle", "QUEUED", "user", 30);
            turn(connection, "t-brain", "task-idle", "QUEUED", "brain-review", 5);

            task(connection, "task-err", 3, "ERRORED", "VALIDATING");
            turn(connection, "t-fail", "task-err", "FAILED", "steering", 10);

            task(connection, "task-ambiguous", 4, "RUNNING", "IMPLEMENTING");
            turn(connection, "t-r1", "task-ambiguous", "RUNNING", "user", 10);
            turn(connection, "t-r2", "task-ambiguous", "RUNNING", "user", 20);
        }

        migrateTo202(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            // Narration roles never gain the flag; code roles do.
            assertThat(scalar(connection,
                    "SELECT affects_task_liveness FROM thread_turns WHERE id = 't-brain'"))
                    .isEqualTo("0");
            assertThat(scalar(connection,
                    "SELECT affects_task_liveness FROM thread_turns WHERE id = 't-q1'"))
                    .isEqualTo("1");

            assertThat(scalar(connection,
                    "SELECT current_liveness_turn_id FROM tasks WHERE id = 'task-run'"))
                    .isEqualTo("t-run");
            // Oldest QUEUED code turn wins the idle pointer.
            assertThat(scalar(connection,
                    "SELECT current_liveness_turn_id FROM tasks WHERE id = 'task-idle'"))
                    .isEqualTo("t-q1");
            assertThat(scalar(connection,
                    "SELECT current_liveness_turn_id FROM tasks WHERE id = 'task-err'"))
                    .isEqualTo("t-fail");

            // Two live candidates: both cancelled, task parked with its
            // phase checkpoint and a durable status event.
            assertThat(scalar(connection,
                    "SELECT status FROM tasks WHERE id = 'task-ambiguous'"))
                    .isEqualTo("NEEDS_ATTENTION");
            assertThat(scalar(connection,
                    "SELECT recovery_phase FROM tasks WHERE id = 'task-ambiguous'"))
                    .isEqualTo("IMPLEMENTING");
            assertThat(scalar(connection,
                    "SELECT status FROM thread_turns WHERE id = 't-r1'"))
                    .isEqualTo("CANCELLED");
            assertThat(scalar(connection,
                    "SELECT count(*) FROM task_status_event WHERE task_id = 'task-ambiguous'"))
                    .isEqualTo("1");
        }
    }

    private String seedAt201(String dbName)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(dbName) + "?foreign_keys=ON";
        Flyway.configure()
                .dataSource(url, "", "")
                .javaMigrations(new NormalizeDeadLifecycleStates())
                .target("201")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT OR IGNORE INTO workspaces(id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                    VALUES ('ws-default', 'Default', '', 0, 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO threads(
                        id, kind, provider, title, status, model,
                        cost_usd_milli, tokens_in, tokens_out,
                        created_at_ms, updated_at_ms,
                        workspace_id, flow, parallel_slots)
                    VALUES (
                        'thread-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE', 'claude-sonnet-4.6',
                        0, 0, 0, 1, 1, 'ws-default', 'build', 1)
                    """);
        }
        return url;
    }

    private static void migrateTo202(String url)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .javaMigrations(new NormalizeDeadLifecycleStates(), new BackfillTurnLiveness())
                .target("202")
                .load()
                .migrate();
    }

    private static void task(Connection connection, String id, int seq, String status, String phase)
            throws Exception
    {
        connection.createStatement().executeUpdate(
                "INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms) "
                        + "VALUES ('" + id + "', 'thread-1', " + seq + ", '" + status + "', '" + phase + "', 1)");
    }

    private static void turn(
            Connection connection, String id, String taskId, String status, String source, long createdAtMs)
            throws Exception
    {
        connection.createStatement().executeUpdate(
                "INSERT INTO thread_turns("
                        + "id, thread_id, task_id, lane, status, input, created_at_ms, updated_at_ms, "
                        + "initiator_attended, initiator_source) "
                        + "VALUES ('" + id + "', 'thread-1', '" + taskId + "', 'CLI', '" + status + "', "
                        + "'input', " + createdAtMs + ", " + createdAtMs + ", 0, '" + source + "')");
    }

    private static String scalar(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
