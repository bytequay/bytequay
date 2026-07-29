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
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkspaceRepositoryQuiescenceMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void detachAndRecloneAreReciprocalWithV2Admission()
            throws Exception
    {
        String url = "jdbc:sqlite:"
                + tempDir.resolve("workspace-quiescence.db")
                + "?foreign_keys=ON";
        migrate(url, "228");
        try (Connection connection = open(url)) {
            TestDevelopmentFlowLocalPublishProtocolMigration
                    .seedApprovedLocalSubject(connection);
        }
        migrate(url, "293");

        try (Connection connection = open(url)) {
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE workspaces
                    SET detached_at_ms = 100
                    WHERE id = 'workspace-1'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("detach requires V2 quiescence");
            assertThatThrownBy(() -> execute(connection,
                    reclone("reclone-blocked")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("re-clone requires V2 quiescence");

            // Isolate the reciprocal admission guard: force each destructive
            // state as if its quiescence check won the serialized write race,
            // then prove no later Workspace ticket can enter.
            execute(connection,
                    "DROP TRIGGER workspace_repository_detach_quiescence_v293");
            execute(connection, """
                    UPDATE workspaces
                    SET detached_at_ms = 101
                    WHERE id = 'workspace-1'
                    """);
            assertThatThrownBy(() -> execute(connection,
                    trunkTicket("detached-ticket")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Workspace repository is unavailable");

            execute(connection, """
                    UPDATE workspaces
                    SET detached_at_ms = NULL
                    WHERE id = 'workspace-1'
                    """);
            execute(connection,
                    "DROP TRIGGER workspace_repository_reclone_quiescence_v293");
            execute(connection, reclone("reclone-forced"));
            assertThatThrownBy(() -> execute(connection,
                    trunkTicket("reclone-ticket")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Workspace repository is unavailable");

            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            assertThat(text(connection, "PRAGMA integrity_check")).isEqualTo("ok");
        }
    }

    private static String reclone(String id)
    {
        return """
                INSERT INTO workspace_creation(
                    id, operation_kind, owner, repo, write_mode, state,
                    progress_current, progress_total, workspace_id, attempt,
                    created_at_ms, updated_at_ms)
                VALUES ('%s', 'reclone', 'acme', 'widget', 'DIRECT', 'queued',
                    0, 2, 'workspace-1', 1, 100, 100)
                """.formatted(id);
    }

    private static String trunkTicket(String id)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, attempt, status, created_at_ms)
                VALUES ('%1$s', '%1$s-operation', 'MIGRATION_PROBE',
                    'REMOTE_OBSERVATION', 'TRUNK', 'trunk-1',
                    'MIGRATION_PROBE_RESULT', 64, 0, 0, 0,
                    'workspace-1', 'trunk-1', 1, 'REQUESTED', 102)
                """.formatted(id);
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .target(target)
                .load()
                .migrate();
    }

    private static Connection open(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int number(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
