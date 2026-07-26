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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrade fixture for the dead-lifecycle-state normalization: legacy rows
 * holding QUEUED / AWAITING are rewritten before the enum values are
 * deleted, and an unexplainable ACTIVE/PAUSED stage row fails the
 * migration visibly instead of being guessed away.
 */
class TestNormalizeDeadLifecycleStates
{
    @TempDir
    private Path tempDir;

    @Test
    void normalizesLegacyQueuedAndAwaitingRows()
            throws Exception
    {
        String url = seedAt198("legacy.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms)
                    VALUES ('task-queued', 'thread-1', 1, 'AWAITING', 'QUEUED', 1)
                    """);
            connection.createStatement().executeUpdate(
                    "UPDATE threads SET status = 'AWAITING' WHERE id = 'thread-1'");
        }

        migrateTo204(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(scalar(connection,
                    "SELECT phase FROM tasks WHERE id = 'task-queued'")).isEqualTo("PLANNING");
            assertThat(scalar(connection,
                    "SELECT status FROM tasks WHERE id = 'task-queued'")).isEqualTo("IDLE");
            assertThat(scalar(connection,
                    "SELECT status FROM threads WHERE id = 'thread-1'")).isEqualTo("IDLE");
        }
    }

    @Test
    void failsPreflightWhenAStageRowHoldsAStateWithNoKnownWriter()
            throws Exception
    {
        String url = seedAt198("stage.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms)
                    VALUES ('task-1', 'thread-1', 1, 'IDLE', 'IMPLEMENTING', 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO task_stage(id, task_id, stage_type, state, opened_at_ms)
                    VALUES ('stage-active', 'task-1', 'DEVELOPMENT_STAGE', 'ACTIVE', 1)
                    """);
        }

        assertThatThrownBy(() -> migrateTo204(url))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("stage-active");
    }

    private String seedAt198(String dbName)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(dbName) + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("198").load().migrate();
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

    private static void migrateTo204(String url)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .javaMigrations(new NormalizeDeadLifecycleStates())
                .target("204")
                .load()
                .migrate();
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
