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
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestResponseRoundFenceCompatibilityMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void legacyFingerprintCanAdvanceWhileV2FenceRemainsImmutable()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("response-round-fence.db")
                + "?foreign_keys=ON";
        migrate(url, "226");
        try (Connection connection = connect(url)) {
            seedOwners(connection);
            execute(connection, """
                    INSERT INTO response_round(
                        id, task_id, idx, status, opened_at_ms, code_fingerprint)
                    VALUES ('legacy-round', 'legacy-task', 1, 'addressing', 3, 'fp-old')
                    """);
            execute(connection, """
                    INSERT INTO response_round(
                        id, task_id, idx, status, opened_at_ms, workflow_version,
                        code_fingerprint, task_epoch, stage_id, stage_generation,
                        operation_id, semantic_attempt, expected_head_sha, expected_base_sha)
                    VALUES ('v2-round', 'v2-task', 1, 'triaging', 3, 'V2',
                        'fp-v2', 1, 'v2-stage', 1, 'operation-1', 1, 'head-1', 'base-1')
                    """);
        }

        migrate(url, "227");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    UPDATE response_round
                    SET code_fingerprint = 'fp-new', kick_attempt = 1
                    WHERE id = 'legacy-round'
                    """);
            assertThat(text(connection, """
                    SELECT code_fingerprint FROM response_round
                    WHERE id = 'legacy-round'
                    """)).isEqualTo("fp-new");

            assertFails(connection, """
                    UPDATE response_round SET code_fingerprint = 'fp-substituted'
                    WHERE id = 'v2-round'
                    """);
            assertFails(connection, """
                    UPDATE response_round SET operation_id = 'operation-2'
                    WHERE id = 'v2-round'
                    """);
            assertFails(connection, """
                    UPDATE response_round SET workflow_version = 'V2'
                    WHERE id = 'legacy-round'
                    """);
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }

        migrate(url, "227");
        migrate(url, "227");
    }

    private static void seedOwners(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms)
                VALUES ('legacy-task', 'trunk-1', 1, 'IDLE', 'PLANNING', 2)
                """);
        execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES ('v2-task', 'trunk-1', 2, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('v2-stage', 'v2-task', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 3)
                """);
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class);
    }
}
