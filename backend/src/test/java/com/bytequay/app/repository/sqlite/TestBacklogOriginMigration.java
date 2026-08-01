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

import com.bytequay.app.testing.MigratedSqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestBacklogOriginMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void backfillsConservativeOriginsAndMakesThemImmutable()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("backlog-origin.db");
        MigratedSqliteDatabase.migrate(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            insert(connection, "manual", "manual", "user", "[\"quality-scan\"]");
            insert(connection, "agent", "agent", "trunk-agent", "[]");
            insert(connection, "monitor", "agent", "trunk-agent", "[\"bytequay-intake\"]");
            insert(connection, "scan", "agent", "trunk-agent",
                    "[\"bytequay-intake\",\"quality-scan\"]");
            insert(connection, "legacy-agent", "manual", "agent", "[]");
        }

        MigratedSqliteDatabase.migrate(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(origin(connection, "manual")).isEqualTo("user");
            assertThat(origin(connection, "agent")).isEqualTo("agent");
            assertThat(origin(connection, "monitor")).isEqualTo("issue-monitor");
            assertThat(origin(connection, "scan")).isEqualTo("quality-scan");
            assertThat(origin(connection, "legacy-agent")).isEqualTo("agent");
            assertThatThrownBy(() -> connection.createStatement().executeUpdate(
                    "UPDATE backlog_item SET origin = 'agent' WHERE id = 'manual'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("backlog item origin is immutable");
        }
    }

    private static void insert(
            Connection connection, String id, String source, String createdBy, String tags)
            throws SQLException
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO backlog_item(
                    id, thread_id, title, tags_json, created_at_ms, source, created_by)
                VALUES ('%s', 'thread-1', '%s', '%s', 1, '%s', '%s')
                """.formatted(id, id, tags, source, createdBy));
    }

    private static String origin(Connection connection, String id)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(
                "SELECT origin FROM backlog_item WHERE id = '" + id + "'")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
