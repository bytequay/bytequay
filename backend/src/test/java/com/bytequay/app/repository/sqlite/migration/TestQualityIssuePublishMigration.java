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

import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Status;
import com.bytequay.app.developmentflow.execution.quality.SqliteQualityIssuePublishStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestQualityIssuePublishMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void onlyExactCanceledV2ProposalCanOwnGithubTicket()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("quality-issue.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            prerequisites(connection);
            seed(connection);
        }
        migrateOnlyV285(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, "PRAGMA foreign_keys = ON");
            insertOperation(connection);
            assertThatThrownBy(() -> execute(connection, ticket(16)))
                    .hasMessageContaining(
                            "quality issue publish ticket is stale or unowned");
            execute(connection, ticket(32));
            execute(connection, """
                    INSERT INTO v2_quality_issue_publish_dispatch_v285(
                        publish_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES ('publish-1', 'ticket-1', 'operation-1', 12)
                    """);

            assertThat(text(connection, """
                    SELECT operation_kind || '|' || async_family || '|'
                        || owner_kind || '|' || lane_mask || '|'
                        || exclusive_task || '|' || callback_route
                    FROM dispatch_ticket WHERE id = 'ticket-1'
                    """)).isEqualTo(
                            "PUBLISH_V2_QUALITY_ISSUE|GITHUB_EFFECT|TASK|32|1|V2_QUALITY_ISSUE_RESULT");
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE v2_quality_issue_publish_v285
                    SET issue_title = 'changed' WHERE id = 'publish-1'
                    """)).hasMessageContaining("identity is immutable");
            execute(connection, """
                    UPDATE v2_quality_issue_publish_v285
                    SET status = 'FAILED', last_error = 'rejected',
                        effect_completed_at_ms = 13
                    WHERE id = 'publish-1'
                    """);
        }

        SqliteQualityIssuePublishStore store =
                new SqliteQualityIssuePublishStore(
                        new JdbcTemplate(new DriverManagerDataSource(url)),
                        mock(TransactionTemplate.class),
                        mock(SqliteDispatchWakeStore.class));
        Instant deliveredAt = Instant.ofEpochMilli(14);
        var first = store.finishDelivery(
                "operation-1", Status.FAILED, "{}", "rejected", deliveredAt);
        var replay = store.finishDelivery(
                "operation-1", Status.FAILED, "{}", "rejected",
                Instant.ofEpochMilli(15));

        assertThat(first.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(replay.deliveredAt()).isEqualTo(deliveredAt);
        assertThat(replay.resultJson()).isEqualTo("{}");
        assertThatThrownBy(() -> store.finishDelivery(
                "operation-1", Status.FAILED, "{\"changed\":true}",
                "rejected", Instant.ofEpochMilli(16)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed differently");

        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, """
                    UPDATE v2_quality_issue_publish_v285
                    SET result_json = '{}', last_error = 'rejected',
                        delivered_at_ms = 14
                    WHERE id = 'publish-1'
                    """);
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE v2_quality_issue_publish_v285
                    SET result_json = '{"changed":true}'
                    WHERE id = 'publish-1'
                    """))
                    .hasMessageContaining("quality issue delivery is immutable");
            assertThat(text(connection, """
                    SELECT status || '|' || result_json || '|'
                        || last_error || '|' || delivered_at_ms
                    FROM v2_quality_issue_publish_v285
                    WHERE id = 'publish-1'
                    """)).isEqualTo("FAILED|{}|rejected|14");
        }
    }

    private void migrateOnlyV285(String url)
            throws Exception
    {
        Path migrations = tempDir.resolve("migrations");
        Files.createDirectories(migrations);
        try (InputStream source = requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V285__quality_issue_publish_runtime.sql"))) {
            Files.copy(source, migrations.resolve(
                    "V285__quality_issue_publish_runtime.sql"));
        }
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("filesystem:" + migrations)
                .baselineVersion("284")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private static void prerequisites(Connection connection)
            throws Exception
    {
        execute(connection, "PRAGMA foreign_keys = ON");
        execute(connection, "CREATE TABLE workspaces(id TEXT PRIMARY KEY)");
        execute(connection, """
                CREATE TABLE threads(
                    id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL)
                """);
        execute(connection, """
                CREATE TABLE tasks(
                    id TEXT PRIMARY KEY, thread_id TEXT NOT NULL,
                    workflow_version TEXT NOT NULL,
                    lifecycle_state TEXT NOT NULL, epoch INTEGER NOT NULL)
                """);
        execute(connection, """
                CREATE TABLE notifications(
                    id TEXT PRIMARY KEY, kind TEXT NOT NULL,
                    thread_id TEXT, task_id TEXT, status TEXT NOT NULL,
                    payload_json TEXT NOT NULL, read_at_ms INTEGER)
                """);
        execute(connection, """
                CREATE TABLE dispatch_ticket(
                    id TEXT PRIMARY KEY, operation_id TEXT, operation_kind TEXT,
                    async_family TEXT, owner_kind TEXT, owner_id TEXT,
                    callback_route TEXT, lane_mask INTEGER,
                    trunk_control INTEGER, exclusive_task INTEGER,
                    writer_required INTEGER, workspace_id TEXT, trunk_id TEXT,
                    task_id TEXT, task_epoch INTEGER, stage_id TEXT,
                    stage_generation INTEGER, attempt INTEGER,
                    expected_code_fingerprint TEXT, expected_head_sha TEXT,
                    expected_base_sha TEXT, status TEXT, created_at_ms INTEGER)
                """);
        execute(connection, """
                CREATE TABLE v2_trunk_purge_authorization_v269(
                    trunk_id TEXT PRIMARY KEY)
                """);
    }

    private static void seed(Connection connection)
            throws Exception
    {
        execute(connection, "INSERT INTO workspaces VALUES ('workspace-1')");
        execute(connection,
                "INSERT INTO threads VALUES ('trunk-1', 'workspace-1')");
        execute(connection, """
                INSERT INTO tasks VALUES (
                    'task-1', 'trunk-1', 'V2', 'CANCELING', 7)
                """);
        execute(connection, """
                INSERT INTO notifications(
                    id, kind, thread_id, task_id, status, payload_json)
                VALUES ('notification-1', 'AWAITING_REVIEW', 'trunk-1',
                    'task-1', 'RESOLVING',
                    '{"action":"create_issue",'
                    || '"source":"automation:quality-scan",'
                    || '"title":"Finding","body":"Details",'
                    || '"repo":{"owner":"acme","repo":"widget"}}')
                """);
    }

    private static void insertOperation(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO v2_quality_issue_publish_v285(
                    id, operation_id, notification_id, task_id, task_epoch,
                    workspace_id, trunk_id, repo_owner, repo_name, issue_title,
                    issue_body, idempotency_marker, payload_digest, status,
                    authorized_at_ms)
                VALUES ('publish-1', 'operation-1', 'notification-1', 'task-1', 7,
                    'workspace-1', 'trunk-1', 'acme', 'widget', 'Finding',
                    'Details\n\n<!-- bytequay-quality-scan:v1 -->\n\n'
                      || '<!-- bytequay-quality-issue-operation:v1 id=operation-1 -->',
                    '<!-- bytequay-quality-issue-operation:v1 id=operation-1 -->',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'REQUESTED', 10)
                """);
    }

    private static String ticket(int lane)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('ticket-1', 'operation-1', 'PUBLISH_V2_QUALITY_ISSUE',
                    'GITHUB_EFFECT', 'TASK', 'task-1',
                    'V2_QUALITY_ISSUE_RESULT', %s, 0, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 7, NULL, NULL, 1,
                    NULL, NULL, NULL, 'REQUESTED', 11)
                """.formatted(lane);
    }

    private static void execute(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
