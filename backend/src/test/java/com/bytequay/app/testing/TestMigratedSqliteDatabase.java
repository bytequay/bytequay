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
package com.bytequay.app.testing;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;

class TestMigratedSqliteDatabase
{
    @TempDir
    private Path tempDir;

    @Test
    void copiedTargetsAreIsolatedAndRemainMigratable()
            throws SQLException
    {
        Path first = tempDir.resolve("first.db");
        Path second = tempDir.resolve("second.db");
        Path latest = tempDir.resolve("latest.db");
        copyTo(first);
        copyTo(second);
        copyTo(latest);

        assertThat(currentVersion(first)).isEqualTo("999");
        assertThat(currentVersion(second)).isEqualTo("999");
        assertThat(currentVersion(latest)).isEqualTo("999");
        assertThat(workspaceExists(latest, "ws-default")).isTrue();
        assertThat(text(latest, """
                SELECT value FROM app_settings
                WHERE key = 'sync.interval.seconds'
                """)).isEqualTo("60");
        assertThat(text(latest, """
                SELECT COUNT(*) FROM reviewer_def
                WHERE id IN ('general-api', 'general-cli',
                    'independent-verifier', 'review-planner')
                """)).isEqualTo("4");
        assertThat(text(latest, """
                SELECT usage FROM skill WHERE name = 'Trino code style'
                """)).isEqualTo("review");
        execute(first, "CREATE TABLE copy_marker (id INTEGER PRIMARY KEY)");
        assertThat(tableExists(first, "copy_marker")).isTrue();
        assertThat(tableExists(second, "copy_marker")).isFalse();

        Flyway.configure().dataSource(url(second), "", "").load().migrate();
        assertThat(currentVersion(second)).isEqualTo("999");
    }

    private static String currentVersion(Path database)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url(database));
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = 1
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static boolean workspaceExists(Path database, String workspaceId)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url(database));
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM workspaces WHERE id = ?")) {
            statement.setString(1, workspaceId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static boolean tableExists(Path database, String table)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url(database));
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static String text(Path database, String sql)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url(database));
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static void execute(Path database, String sql)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(url(database));
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String url(Path database)
    {
        return "jdbc:sqlite:" + database + "?foreign_keys=ON";
    }
}
