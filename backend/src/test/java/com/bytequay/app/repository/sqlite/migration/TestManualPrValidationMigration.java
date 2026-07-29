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

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class TestManualPrValidationMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void acceptedResultProjectsOneStableCheckAndTimelineRow()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("manual-validation.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            createPrerequisites(connection);
            seedSubject(connection);
        }
        migrateOnlyV279(url);

        String result = """
                {"schemaVersion":1,"operationId":"operation-1","prId":"pr-1",
                 "taskId":"task-1","taskEpoch":1,"subjectCurrent":true,
                 "passed":false,"testRun":{"ecosystem":"maven","passed":false,
                 "durationMs":7,"failures":[{"source":"test","detail":"failed"}],
                 "startedAtMs":100,"completedAtMs":107},
                 "failures":[{"source":"test","detail":"failed"}],
                 "observedCodeFingerprint":"fp-1","observedHeadSha":"head-1",
                 "observedBaseSha":"base-1","startedAtMs":100,"completedAtMs":107}
                """;
        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, """
                    INSERT INTO manual_pr_validation_operation(
                        id, command_id, pr_id, task_id, task_epoch, worktree_path,
                        code_fingerprint, expected_head_sha, expected_base_sha,
                        status, requested_at_ms)
                    VALUES ('operation-1', 'command-1', 'pr-1', 'task-1', 1,
                        '/tmp/worktree', 'fp-1', 'head-1', 'base-1',
                        'REQUESTED', 90)
                    """);
            updateResult(connection, result);
            updateResult(connection, result);

            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pr_check")).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pr_timeline_event")).isOne();
            assertThat(text(connection, """
                    SELECT id || '|' || kind || '|' || name || '|' || status
                    FROM pr_check
                    """)).isEqualTo(
                            "manual-pr-validation-check:operation-1|local|maven test|failed");
            assertThat(text(connection, """
                    SELECT id || '|' || event_type || '|' || actor
                    FROM pr_timeline_event
                    """)).isEqualTo(
                            "manual-pr-validation-event:operation-1|ci|claude-code");
        }
    }

    private void migrateOnlyV279(String url)
            throws Exception
    {
        Path migrations = tempDir.resolve("migrations");
        Files.createDirectories(migrations);
        try (InputStream source = requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V279__manual_pr_validation_runtime.sql"))) {
            Files.copy(source, migrations.resolve(
                    "V279__manual_pr_validation_runtime.sql"));
        }
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("filesystem:" + migrations)
                .baselineVersion("278")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private static void createPrerequisites(Connection connection)
            throws Exception
    {
        execute(connection, """
                CREATE TABLE threads(
                    id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL)
                """);
        execute(connection, """
                CREATE TABLE tasks(
                    id TEXT PRIMARY KEY, thread_id TEXT NOT NULL,
                    workflow_version TEXT NOT NULL, lifecycle_state TEXT NOT NULL,
                    epoch INTEGER NOT NULL)
                """);
        execute(connection, """
                CREATE TABLE pr(
                    id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id))
                """);
        execute(connection, """
                CREATE TABLE task_code_identity(
                    task_id TEXT PRIMARY KEY, worktree_path TEXT NOT NULL,
                    code_fingerprint TEXT NOT NULL, local_head_sha TEXT NOT NULL,
                    base_sha TEXT NOT NULL)
                """);
        execute(connection, """
                CREATE VIEW task_current_code_subject_v230 AS
                SELECT task_id, code_fingerprint, local_head_sha AS head_sha, base_sha
                FROM task_code_identity
                """);
        execute(connection, """
                CREATE TABLE dispatch_ticket(
                    id TEXT PRIMARY KEY, operation_id TEXT, operation_kind TEXT,
                    async_family TEXT, owner_kind TEXT, owner_id TEXT,
                    callback_route TEXT, lane_mask INTEGER, trunk_control INTEGER,
                    exclusive_task INTEGER, writer_required INTEGER,
                    workspace_id TEXT, trunk_id TEXT, task_id TEXT,
                    task_epoch INTEGER, stage_id TEXT, stage_generation INTEGER,
                    attempt INTEGER, expected_code_fingerprint TEXT,
                    expected_head_sha TEXT, expected_base_sha TEXT,
                    status TEXT, created_at_ms INTEGER)
                """);
        execute(connection, """
                CREATE TABLE pr_check(
                    id TEXT PRIMARY KEY, pr_id TEXT NOT NULL, kind TEXT NOT NULL,
                    name TEXT NOT NULL, status TEXT NOT NULL, duration_ms INTEGER,
                    started_at_ms INTEGER NOT NULL, finished_at_ms INTEGER,
                    run_id TEXT)
                """);
        execute(connection, """
                CREATE TABLE pr_timeline_event(
                    id TEXT PRIMARY KEY, pr_id TEXT NOT NULL,
                    event_type TEXT NOT NULL, actor TEXT NOT NULL,
                    is_local_only INTEGER NOT NULL, stripped_on_push_at_ms INTEGER,
                    created_at_ms INTEGER NOT NULL, payload_json TEXT,
                    remote_event_id INTEGER)
                """);
    }

    private static void seedSubject(Connection connection)
            throws Exception
    {
        execute(connection,
                "INSERT INTO threads VALUES ('trunk-1', 'workspace-1')");
        execute(connection, """
                INSERT INTO tasks VALUES (
                    'task-1', 'trunk-1', 'V2', 'ACTIVE', 1)
                """);
        execute(connection,
                "INSERT INTO pr VALUES ('pr-1', 'task-1')");
        execute(connection, """
                INSERT INTO task_code_identity VALUES (
                    'task-1', '/tmp/worktree', 'fp-1', 'head-1', 'base-1')
                """);
    }

    private static void updateResult(Connection connection, String result)
            throws Exception
    {
        try (var statement = connection.prepareStatement("""
                UPDATE manual_pr_validation_operation
                SET status = 'COMPLETED', result_json = ?, completed_at_ms = 107
                WHERE id = 'operation-1' AND status = 'REQUESTED'
                """)) {
            statement.setString(1, result);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int number(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getInt(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }
}
