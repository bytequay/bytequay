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
package com.bytequay.app.repository.sqlite;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowVersionMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void addsValidatedDurableRoutingWithoutChangingHistoricalTasks()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("development-flow-version.db")
                + "?foreign_keys=ON";
        migrateTo(url, "221");

        try (Connection connection = DriverManager.getConnection(url)) {
            insertWorkspace(connection);
            insertThread(connection, "historical-thread", null);
            insertTask(connection, "historical-task", "historical-thread", 1, null);
        }

        migrateTo(url, "222");

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(value(connection,
                    "SELECT turn_version FROM threads WHERE id = 'historical-thread'"))
                    .isEqualTo("LEGACY");
            assertThat(value(connection,
                    "SELECT workflow_version FROM tasks WHERE id = 'historical-task'"))
                    .isEqualTo("LEGACY");

            insertThread(connection, "default-thread", null);
            insertTask(connection, "default-task", "default-thread", 1, null);
            insertThread(connection, "v2-thread", "V2");
            insertTask(connection, "v2-task", "v2-thread", 1, "V2");

            assertThat(value(connection,
                    "SELECT turn_version FROM threads WHERE id = 'default-thread'"))
                    .isEqualTo("LEGACY");
            assertThat(value(connection,
                    "SELECT workflow_version FROM tasks WHERE id = 'default-task'"))
                    .isEqualTo("LEGACY");
            assertThat(value(connection,
                    "SELECT turn_version FROM threads WHERE id = 'v2-thread'"))
                    .isEqualTo("V2");
            assertThat(value(connection,
                    "SELECT workflow_version FROM tasks WHERE id = 'v2-task'"))
                    .isEqualTo("V2");

            assertThatThrownBy(() -> insertThread(connection, "invalid-thread", "V3"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertTask(
                    connection, "invalid-task", "historical-thread", 2, "V3"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    UPDATE tasks SET workflow_version = 'V2'
                    WHERE id = 'historical-task'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("task workflow version is immutable");
        }

        migrateTo(url, "222");

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(value(connection,
                    "SELECT workflow_version FROM tasks WHERE id = 'historical-task'"))
                    .isEqualTo("LEGACY");
            assertThat(value(connection,
                    "SELECT workflow_version FROM tasks WHERE id = 'v2-task'"))
                    .isEqualTo("V2");
            assertThat(value(connection,
                    "SELECT turn_version FROM threads WHERE id = 'v2-thread'"))
                    .isEqualTo("V2");
        }
    }

    private static void migrateTo(String url, String version)
    {
        Flyway.configure().dataSource(url, "", "").target(version).load().migrate();
    }

    private static void insertWorkspace(Connection connection)
            throws SQLException
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
    }

    private static void insertThread(Connection connection, String id, String turnVersion)
            throws SQLException
    {
        String versionColumn = turnVersion == null ? "" : ", turn_version";
        String versionValue = turnVersion == null ? "" : ", '" + turnVersion + "'";
        connection.createStatement().executeUpdate("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow, parallel_slots%s)
                VALUES (
                    '%s', 'CLI_AGENT', 'claude-code', '%s', 'IDLE', 'model',
                    0, 0, 0, 1, 1, 'workspace-1', 'build', 1%s)
                """.formatted(versionColumn, id, id, versionValue));
    }

    private static void insertTask(
            Connection connection,
            String id,
            String threadId,
            int sequence,
            String workflowVersion)
            throws SQLException
    {
        String versionColumn = workflowVersion == null ? "" : ", workflow_version";
        String versionValue = workflowVersion == null ? "" : ", '" + workflowVersion + "'";
        connection.createStatement().executeUpdate("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, created_at_ms%s)
                VALUES ('%s', '%s', %d, 'PENDING', 1%s)
                """.formatted(versionColumn, id, threadId, sequence, versionValue));
    }

    private static String value(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
