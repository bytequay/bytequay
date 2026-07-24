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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBacklogTaskLinkMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void addsTheUniqueTaskLinkIndexWhenExistingLinksAreClean()
            throws Exception
    {
        String url = databaseUrl("clean-links.db");
        migrateTo(url, "196");
        seed(url, false);

        migrateTo(url, "197");

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(schemaHistoryCount(connection, "197")).isEqualTo(1);
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    INSERT INTO backlog_item(
                        id, thread_id, title, body, tags_json, created_at_ms,
                        started_at_ms, linked_task_id, workspace_id, priority,
                        source, status, created_by, in_progress_at_ms,
                        resolved_at_ms)
                    VALUES (
                        'backlog-3', 'thread-1', 'Third', '', '[]', 6,
                        6, 'task-1', 'ws-1', 'medium', 'manual', 'resolved',
                        'user', 6, 6)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE constraint failed: backlog_item.linked_task_id");
            assertThatThrownBy(() -> connection.createStatement().executeUpdate("""
                    UPDATE backlog_item
                    SET status = 'open', linked_task_id = NULL, resolved_at_ms = NULL
                    WHERE id = 'backlog-1'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("resolved_backlog_link_is_immutable");
            assertThat(singleInt(connection, """
                    SELECT COUNT(*)
                    FROM backlog_item
                    WHERE id = 'backlog-1'
                      AND status = 'resolved'
                      AND linked_task_id = 'task-1'
                      AND resolved_at_ms IS NOT NULL
                    """)).isOne();

            connection.createStatement().executeUpdate(
                    "DELETE FROM tasks WHERE id = 'task-2'");
            assertThat(singleInt(connection, """
                    SELECT COUNT(*)
                    FROM backlog_item
                    WHERE id = 'backlog-2'
                      AND linked_task_id IS NULL
                    """)).isOne();
        }
    }

    @Test
    void refusesToChooseBetweenLegacyDuplicateLinks()
            throws Exception
    {
        String url = databaseUrl("duplicate-links.db");
        migrateTo(url, "196");
        seed(url, true);

        assertThatThrownBy(() -> migrateTo(url, "197"))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("duplicate_linked_task_id_must_be_repaired");

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(schemaHistoryCount(connection, "197")).isZero();
            assertThat(singleInt(connection, """
                    SELECT COUNT(*)
                    FROM backlog_item
                    WHERE linked_task_id = 'task-1'
                    """)).isEqualTo(2);
            assertThat(singleInt(connection, """
                    SELECT COUNT(*)
                    FROM pragma_index_list('backlog_item')
                    WHERE name = 'idx_backlog_item_linked_task'
                    """)).isZero();
        }
    }

    private String databaseUrl(String fileName)
    {
        return "jdbc:sqlite:" + tempDir.resolve(fileName) + "?foreign_keys=ON";
    }

    private static void migrateTo(String url, String version)
    {
        Flyway.configure().dataSource(url, "", "").target(version).load().migrate();
    }

    private static void seed(String url, boolean duplicate)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO workspaces(
                        id, name, memory_md, is_scratch,
                        created_at_ms, updated_at_ms)
                    VALUES ('ws-1', 'Workspace', '', 0, 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO threads(
                        id, kind, provider, title, status, model,
                        cost_usd_milli, tokens_in, tokens_out,
                        created_at_ms, updated_at_ms,
                        workspace_id, flow, parallel_slots)
                    VALUES (
                        'thread-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE', 'gpt-5',
                        0, 0, 0, 1, 1, 'ws-1', 'build', 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO tasks(
                        id, thread_id, seq, status, task_type, created_at_ms)
                    VALUES
                        ('task-1', 'thread-1', 1, 'COMPLETED', 'DEVELOP', 2),
                        ('task-2', 'thread-1', 2, 'COMPLETED', 'DEVELOP', 3)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO backlog_item(
                        id, thread_id, title, body, tags_json, created_at_ms,
                        started_at_ms, linked_task_id, workspace_id, priority,
                        source, status, created_by, in_progress_at_ms,
                        resolved_at_ms)
                    VALUES
                        ('backlog-1', 'thread-1', 'First', '', '[]', 4,
                         4, 'task-1', 'ws-1', 'medium', 'manual', 'resolved',
                         'user', 4, 4),
                        ('backlog-2', 'thread-1', 'Second', '', '[]', 5,
                         5, '%s', 'ws-1', 'medium', 'manual', 'resolved',
                         'user', 5, 5)
                    """.formatted(duplicate ? "task-1" : "task-2"));
        }
    }

    private static int schemaHistoryCount(Connection connection, String version)
            throws SQLException
    {
        return singleInt(connection, """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '%s' AND success = 1
                """.formatted(version));
    }

    private static int singleInt(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
