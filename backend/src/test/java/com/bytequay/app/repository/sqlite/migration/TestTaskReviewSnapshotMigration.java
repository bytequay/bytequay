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
import java.sql.SQLException;
import java.sql.Statement;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTaskReviewSnapshotMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void operationAndTicketRequireTheExactActiveTaskWriterSubject()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("task-review.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            createPrerequisites(connection);
            seedSubject(connection);
        }
        migrateOnlyV286(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            insertOperation(connection);
            execute(connection, ticketSql(1));

            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM task_review_snapshot_operation_v286 operation
                    JOIN dispatch_ticket ticket
                      ON ticket.operation_id = operation.id
                    """)).isOne();
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE task_review_snapshot_operation_v286
                    SET expected_head_sha = 'other'
                    WHERE id = 'operation-1'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("identity is immutable");
        }
    }

    @Test
    void ticketWithoutTheWriterLeaseIsRejected()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("task-review-fence.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            createPrerequisites(connection);
            seedSubject(connection);
        }
        migrateOnlyV286(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            insertOperation(connection);
            assertThatThrownBy(() -> execute(connection, ticketSql(0)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DispatchTicket is not exact");
        }
    }

    private void migrateOnlyV286(String url)
            throws Exception
    {
        Path migrations = tempDir.resolve("migrations-" + Math.abs(url.hashCode()));
        Files.createDirectories(migrations);
        try (InputStream source = requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V286__task_review_snapshot_runtime.sql"))) {
            Files.copy(source, migrations.resolve(
                    "V286__task_review_snapshot_runtime.sql"));
        }
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("filesystem:" + migrations)
                .baselineVersion("285")
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
                    id TEXT PRIMARY KEY, task_id TEXT REFERENCES tasks(id),
                    repo TEXT, remote_pr_number INTEGER,
                    base_branch TEXT NOT NULL, title TEXT NOT NULL,
                    description TEXT NOT NULL)
                """);
        execute(connection, """
                CREATE TABLE pr_commit(
                    id TEXT PRIMARY KEY, pr_id TEXT NOT NULL, sha TEXT NOT NULL)
                """);
        execute(connection, """
                CREATE TABLE review_session(
                    id TEXT PRIMARY KEY, repo_id TEXT NOT NULL, pr_id TEXT NOT NULL,
                    base_commit TEXT NOT NULL, reviewed_head_commit TEXT NOT NULL,
                    status TEXT NOT NULL, workspace_id TEXT, owner_thread_id TEXT,
                    owner_task_id TEXT, created_at_ms INTEGER, updated_at_ms INTEGER)
                """);
        execute(connection, """
                CREATE TABLE task_code_identity(
                    task_id TEXT PRIMARY KEY, worktree_path TEXT NOT NULL,
                    code_fingerprint TEXT NOT NULL, local_head_sha TEXT NOT NULL,
                    base_sha TEXT NOT NULL)
                """);
        execute(connection, """
                CREATE VIEW task_current_code_subject_v230 AS
                SELECT task_id, code_fingerprint, local_head_sha AS head_sha,
                       base_sha
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
    }

    private static void seedSubject(Connection connection)
            throws Exception
    {
        execute(connection,
                "INSERT INTO threads VALUES ('trunk-1', 'workspace-1')");
        execute(connection, """
                INSERT INTO tasks VALUES (
                    'task-1', 'trunk-1', 'V2', 'ACTIVE', 2)
                """);
        execute(connection, """
                INSERT INTO pr VALUES (
                    'pr-1', 'task-1', NULL, NULL, 'main', 'Title', 'Description')
                """);
        execute(connection,
                "INSERT INTO pr_commit VALUES ('commit-1', 'pr-1', 'head-1')");
        execute(connection, """
                INSERT INTO task_code_identity VALUES (
                    'task-1', '/tmp/worktree', 'fingerprint-1',
                    'head-1', 'base-1')
                """);
        execute(connection, """
                INSERT INTO review_session VALUES (
                    'review-1', 'local', 'pr-1', 'base-1', 'head-1',
                    'ACTIVE', 'workspace-1', 'trunk-1', 'task-1', 1, 1)
                """);
    }

    private static void insertOperation(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO task_review_snapshot_operation_v286(
                    id, review_id, pr_id, repository, remote_pr_number,
                    base_branch, pr_title, pr_description,
                    task_id, task_epoch, worktree_path,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    start_options_json, status, requested_at_ms)
                VALUES ('operation-1', 'review-1', 'pr-1', NULL, NULL,
                    'main', 'Title', 'Description', 'task-1', 2,
                    '/tmp/worktree', 'fingerprint-1', 'head-1', 'base-1',
                    '{}', 'REQUESTED', 2)
                """);
    }

    private static String ticketSql(int writerRequired)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, trunk_control,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('ticket-1', 'operation-1',
                    'CAPTURE_TASK_REVIEW_SNAPSHOT', 'LOCAL_GIT', 'TASK',
                    'task-1', 'TASK_REVIEW_SNAPSHOT_RESULT', 16, 0, 1, %d,
                    'workspace-1', 'trunk-1', 'task-1', 2, NULL, NULL, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 3)
                """.formatted(writerRequired);
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
}
