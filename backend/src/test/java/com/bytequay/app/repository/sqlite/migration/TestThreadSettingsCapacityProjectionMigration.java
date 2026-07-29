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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestThreadSettingsCapacityProjectionMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void projectsLegacyLimitsOnceAndRejectsInvalidFutureOverrides()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("capacity.db")
                + "?foreign_keys=ON";
        migrate(url, "274");
        try (Connection connection = DriverManager.getConnection(url)) {
            seed(connection);
        }

        migrate(url, "275");

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(integer(connection, "absent")).isEqualTo(3);
            assertThat(integer(connection, "sparse")).isEqualTo(4);
            assertThat(text(connection, "sparse", "prompt_addendum"))
                    .isEqualTo("keep me");
            assertThat(longValue(connection, "sparse", "updated_at_ms"))
                    .isEqualTo(101);
            assertThat(integer(connection, "invalid-fallback")).isEqualTo(5);
            assertThat(integer(connection, "invalid-inherited")).isNull();
            assertThat(integer(connection, "explicit")).isEqualTo(2);

            assertThat(parallelSlots(connection, "absent")).isEqualTo(3);
            assertThat(parallelSlots(connection, "invalid-fallback")).isEqualTo(5);
            assertThat(parallelSlots(connection, "explicit")).isEqualTo(7);
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM flyway_schema_history
                    WHERE version IN ('207', '208', '213')
                      AND type = 'JDBC'
                    """)).isEqualTo(3);

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO thread_settings(
                        thread_id, max_running_tasks, updated_at_ms)
                    VALUES ('guarded-insert', 0, 200)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "thread_settings.max_running_tasks must be positive");
            assertThat(count(connection, """
                    SELECT COUNT(*) FROM thread_settings
                    WHERE thread_id = 'guarded-insert'
                    """)).isZero();

            assertThatThrownBy(() -> execute(connection, """
                    UPDATE thread_settings SET max_running_tasks = -1
                    WHERE thread_id = 'explicit'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "thread_settings.max_running_tasks must be positive");
            assertThat(integer(connection, "explicit")).isEqualTo(2);

            execute(connection, """
                    UPDATE thread_settings SET max_running_tasks = NULL
                    WHERE thread_id = 'explicit'
                    """);
        }

        // The projection is a migration, not an ongoing mirror of the
        // legacy column. A later explicit clear remains inherited.
        migrate(url, "275");
        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(integer(connection, "explicit")).isNull();
            assertThat(parallelSlots(connection, "explicit")).isEqualTo(7);
        }
    }

    private static void seed(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES
                    ('absent', 'CLI_AGENT', 'codex', 'Absent', 'IDLE',
                        'gpt', 0, 0, 0, 1, 100, 'workspace', 'build', 3,
                        'V2', 'ACTIVE'),
                    ('sparse', 'CLI_AGENT', 'codex', 'Sparse', 'IDLE',
                        'gpt', 0, 0, 0, 1, 101, 'workspace', 'build', 4,
                        'V2', 'ACTIVE'),
                    ('invalid-fallback', 'CLI_AGENT', 'codex', 'Invalid',
                        'IDLE', 'gpt', 0, 0, 0, 1, 102, 'workspace',
                        'build', 5, 'V2', 'ACTIVE'),
                    ('invalid-inherited', 'CLI_AGENT', 'codex', 'Inherited',
                        'IDLE', 'gpt', 0, 0, 0, 1, 103, 'workspace',
                        'build', 1, 'V2', 'ACTIVE'),
                    ('explicit', 'CLI_AGENT', 'codex', 'Explicit', 'IDLE',
                        'gpt', 0, 0, 0, 1, 104, 'workspace', 'build', 7,
                        'V2', 'ACTIVE'),
                    ('guarded-insert', 'CLI_AGENT', 'codex', 'Guarded',
                        'IDLE', 'gpt', 0, 0, 0, 1, 105, 'workspace',
                        'build', 1, 'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO thread_settings(
                    thread_id, max_running_tasks, prompt_addendum, updated_at_ms)
                VALUES
                    ('sparse', NULL, 'keep me', 101),
                    ('invalid-fallback', 0, NULL, 102),
                    ('invalid-inherited', -4, NULL, 103),
                    ('explicit', 2, NULL, 104)
                """);
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .locations("classpath:db/migration")
                .javaMigrations(
                        new BackfillTurnLiveness(),
                        new BackfillLocalReviewSubmissions(new ObjectMapper()),
                        new NormalizeDeadLifecycleStates())
                .target(target)
                .load()
                .migrate();
    }

    private static Integer integer(Connection connection, String threadId)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery("""
                SELECT max_running_tasks FROM thread_settings
                WHERE thread_id = '%s'
                """.formatted(threadId))) {
            assertThat(rows.next()).isTrue();
            int value = rows.getInt(1);
            return rows.wasNull() ? null : value;
        }
    }

    private static int parallelSlots(Connection connection, String threadId)
            throws SQLException
    {
        return count(connection, """
                SELECT parallel_slots FROM threads WHERE id = '%s'
                """.formatted(threadId));
    }

    private static String text(Connection connection, String threadId, String column)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(
                "SELECT " + column + " FROM thread_settings WHERE thread_id = '"
                        + threadId + "'")) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long longValue(
            Connection connection, String threadId, String column)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(
                "SELECT " + column + " FROM thread_settings WHERE thread_id = '"
                        + threadId + "'")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static int count(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
