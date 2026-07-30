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

class TestAgentExecutionProcessAttemptMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void v294BackfillsAndGuardsSequentialProcessEvidence()
            throws Exception
    {
        String url = "jdbc:sqlite:"
                + tempDir.resolve("agent-process-attempt.db")
                + "?foreign_keys=ON";
        migrate(url, "228");
        try (Connection connection = open(url)) {
            TestDevelopmentFlowLocalPublishProtocolMigration
                    .seedApprovedLocalSubject(connection);
        }
        migrate(url, "293");
        try (Connection connection = open(url)) {
            execute(connection, """
                    INSERT INTO agent_execution(
                        id, ticket_id, infrastructure_attempt, process_pid,
                        log_ref, status, started_at_ms, heartbeat_at_ms)
                    VALUES ('execution-1', 'validation-ticket-1', 1, 4242,
                        'agent-turn/codex', 'RUNNING', 20, 20)
                    """);
        }

        migrate(url, "294");
        try (Connection connection = open(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM agent_execution_process_attempt
                    WHERE execution_id = 'execution-1'
                      AND process_attempt = 1
                      AND process_pid = 4242
                      AND log_ref = 'agent-turn/codex'
                    """)).isOne();
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO agent_execution_process_attempt(
                        execution_id, process_attempt, process_pid, log_ref)
                    VALUES ('execution-1', 3, 4343, 'agent-turn/codex')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("must be sequential");
            execute(connection, """
                    INSERT INTO agent_execution_process_attempt(
                        execution_id, process_attempt, process_pid, log_ref)
                    VALUES ('execution-1', 2, 4343, 'agent-turn/codex')
                    """);
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE agent_execution_process_attempt SET process_pid = 4444
                    WHERE execution_id = 'execution-1' AND process_attempt = 2
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("is immutable");

            execute(connection,
                    "DELETE FROM agent_execution WHERE id = 'execution-1'");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM agent_execution_process_attempt
                    WHERE execution_id = 'execution-1'
                    """)).isZero();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
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
}
