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

import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.review.ReviewSessionSnapshotRuntime;
import com.bytequay.app.service.review.TaskReviewRoundSnapshotRuntime;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewSnapshotRuntimeMigrations
{
    @TempDir
    private Path tempDir;

    @Test
    void runtimeRequestsFreezePromptMetadataAndPromptDriftSupersedesThem()
            throws Exception
    {
        String taskUrl = seededDatabase("task-prompt-freeze.db");
        ObjectMapper mapper = new ObjectMapper();
        DriverManagerDataSource taskDataSource = dataSource(taskUrl);
        JdbcTemplate taskJdbc = new JdbcTemplate(taskDataSource);
        TransactionTemplate taskTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(taskDataSource));
        taskJdbc.update("""
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                SELECT 'task-review-head', 'pr-1', code.head_sha,
                    'review head', 0, 0, 30
                FROM task_current_code_subject_v230 code
                WHERE code.task_id = 'task-1'
                """);
        TaskReviewSnapshotRuntime initial = new TaskReviewSnapshotRuntime(
                taskJdbc, mapper);
        PR local = PR.create(
                        "pr-1", "task-1", "dev/task-1", "main",
                        "Implement feature", "Description", Instant.EPOCH)
                .withStatus(PR.STATUS_LOCAL_OPEN, Instant.EPOCH);
        AgentReviewRow taskReview = taskTransaction.execute(
                ignored -> initial.request(local, null));
        assertThat(taskReview).isNotNull();
        String initialOperation = taskJdbc.queryForObject("""
                SELECT id FROM task_review_snapshot_operation_v286
                WHERE review_id = ?
                """, String.class, taskReview.id());
        assertThat(initial.requireExecutionSubject(initialOperation).prTitle())
                .isEqualTo("Implement feature");

        TaskReviewRoundSnapshotRuntime later =
                new TaskReviewRoundSnapshotRuntime(taskJdbc, initial, mapper);
        TaskReviewRoundSnapshotRuntime.ExecutionSubject laterSubject =
                taskTransaction.execute(ignored -> later.request(taskReview,
                        new ReviewSessionSnapshotRuntime.SnapshotCommand(
                                "command-1", "continue", List.of(), null,
                                null, null, null, null)));
        assertThat(laterSubject).isNotNull();
        assertThat(laterSubject.prDescription()).isEqualTo("Description");

        taskJdbc.update("""
                UPDATE pr SET description = 'changed after authorization'
                WHERE id = 'pr-1'
                """);
        assertThat(initial.requireExecutionSubject(initialOperation).current())
                .isFalse();
        assertThat(later.requireExecutionSubject(
                laterSubject.operationId()).current()).isFalse();

        String remoteUrl = seededDatabase("remote-prompt-freeze.db");
        try (Connection connection = open(remoteUrl)) {
            seedStandaloneSubjects(connection);
        }
        DriverManagerDataSource remoteDataSource = dataSource(remoteUrl);
        ReviewSessionSnapshotRuntime remote = new ReviewSessionSnapshotRuntime(
                new JdbcTemplate(remoteDataSource), mapper);
        AgentReviewRow standalone = new AgentReviewRow(
                "review-quick", "acme/quick", "pr-quick",
                "quick-base", "quick-head", "ACTIVE",
                null, null, null);
        ReviewSessionSnapshotRuntime.ExecutionSubject remoteSubject =
                new TransactionTemplate(
                        new DataSourceTransactionManager(remoteDataSource))
                .execute(ignored -> remote.request(standalone,
                        ReviewSessionSnapshotRuntime.Scope.QUICK,
                        new ReviewSessionSnapshotRuntime.SnapshotCommand(
                                "command-2", "initial", List.of(), null,
                                null, null, null, null)));
        assertThat(remoteSubject).isNotNull();
        assertThat(remoteSubject.prTitle()).isEqualTo("quick review");
        jdbc(remoteUrl).update("""
                UPDATE pr SET title = 'changed after authorization'
                WHERE id = 'pr-quick'
                """);
        assertThat(remote.requireExecutionSubject(
                remoteSubject.operationId()).current()).isFalse();
    }

    @Test
    void v293EnforcesExactSnapshotTicketsAndCleansOnlyTerminalOnes()
            throws Exception
    {
        String url = seededDatabase("v293-runtime.db");
        try (Connection connection = open(url)) {
            seedStandaloneSubjects(connection);
            TaskSubject task = seedTaskReview(connection);

            assertThatThrownBy(() -> transaction(connection,
                    standaloneOperation(
                            "quick-wrong-head", "quick-wrong-head-ticket",
                            "review-quick", "pr-quick", "acme/quick", 11,
                            "main", null, null, "quick", "wrong-head")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exact standalone review subject");
            assertThatThrownBy(() -> transaction(connection,
                    standaloneOperation(
                            "task-as-standalone", "task-as-standalone-ticket",
                            "review-task", "pr-1", "acme/widget", 12,
                            "main", null, null, "quick", task.headSha())))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exact standalone review subject");

            assertThatThrownBy(() -> transaction(connection,
                    standaloneOperation(
                            "quick-invalid", "quick-invalid-ticket",
                            "review-quick", "pr-quick", "acme/quick", 11,
                            "main", null, null, "quick", "quick-head"),
                    standaloneTicket(
                            "quick-invalid-ticket", "quick-invalid",
                            "review-quick", null, "REMOTE_OBSERVATION", 48,
                            "quick-head")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DispatchTicket is not exact");
            transaction(connection,
                    standaloneOperation(
                            "quick", "quick-ticket", "review-quick", "pr-quick",
                            "acme/quick", 11, "main", null, null, "quick",
                            "quick-head"),
                    standaloneTicket(
                            "quick-ticket", "quick", "review-quick", null,
                            "REMOTE_OBSERVATION", 64, "quick-head"));
            execute(connection, """
                    UPDATE pr SET title = 'changed after authorization'
                    WHERE id = 'pr-quick'
                    """);
            assertThat(reviewSnapshots(url)
                    .requireExecutionSubject("quick").current()).isFalse();
            execute(connection, """
                    UPDATE pr SET title = 'quick review'
                    WHERE id = 'pr-quick'
                    """);

            assertThatThrownBy(() -> transaction(connection,
                    standaloneOperation(
                            "full-invalid", "full-invalid-ticket", "review-full",
                            "pr-full", "acme/widget", 12, "main", "workspace-1",
                            "/tmp/full", "full", "full-head"),
                    standaloneTicket(
                            "full-invalid-ticket", "full-invalid", "review-full",
                            "workspace-1", "LOCAL_GIT", 64, "full-head")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DispatchTicket is not exact");
            transaction(connection,
                    standaloneOperation(
                            "full", "full-ticket", "review-full", "pr-full",
                            "acme/widget", 12, "main", "workspace-1", "/tmp/full",
                            "full", "full-head"),
                    standaloneTicket(
                            "full-ticket", "full", "review-full", "workspace-1",
                            "LOCAL_GIT", 48, "full-head"));

            assertLiveThenTerminalCleanup(connection,
                    "review_session_snapshot_operation_v293", "quick", "quick-ticket",
                    "live ReviewSession snapshot");
            assertLiveThenTerminalCleanup(connection,
                    "review_session_snapshot_operation_v293", "full", "full-ticket",
                    "live ReviewSession snapshot");

            assertThatThrownBy(() -> transaction(connection,
                    taskRoundOperation(
                            "task-round-wrong", "task-round-wrong-ticket", task,
                            "wrong-head")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exact active V2 Task subject");

            transaction(connection,
                    initialTaskOperation("task-initial", task),
                    initialTaskTicket("task-initial-ticket", "task-initial", task));
            execute(connection, """
                    UPDATE pr SET description = 'changed after authorization'
                    WHERE id = 'pr-1'
                    """);
            assertThat(taskSnapshots(url)
                    .requireExecutionSubject("task-initial").current()).isFalse();
            execute(connection, """
                    UPDATE pr SET description = 'Description'
                    WHERE id = 'pr-1'
                    """);
            assertLiveThenTerminalCleanup(connection,
                    "task_review_snapshot_operation_v286", "task-initial",
                    "task-initial-ticket", "live initial Task review snapshot");

            transaction(connection,
                    taskRoundOperation(
                            "task-round", "task-round-ticket", task, task.headSha()),
                    taskRoundTicket("task-round-ticket", "task-round", task));
            assertLiveThenTerminalCleanup(connection,
                    "task_review_round_snapshot_operation_v293", "task-round",
                    "task-round-ticket", "live Task review round snapshot");

            assertHealthy(connection);
        }
    }

    private String seededDatabase(String name)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name) + "?foreign_keys=ON";
        migrate(url);
        try (Connection connection = open(url)) {
            TestDevelopmentFlowLocalPublishProtocolMigration
                    .seedApprovedLocalSubject(connection);
        }
        migrate(url);
        return url;
    }

    private static void seedStandaloneSubjects(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES ('acme', 'widget', '/tmp/full')
                """);
        seedExternalReview(connection,
                "quick", "acme/quick", 11, null, "quick-base", "quick-head");
        seedExternalReview(connection,
                "full", "acme/widget", 12, "workspace-1", "full-base", "full-head");
    }

    private static void seedExternalReview(
            Connection connection,
            String suffix,
            String repository,
            int number,
            String workspaceId,
            String baseSha,
            String headSha)
            throws Exception
    {
        execute(connection, """
                INSERT INTO pr(
                    id, branch_name, base_branch, title, description, status,
                    created_at_ms, pushed_at_ms, remote_pr_number, remote_pr_url,
                    origin, repo, author, synced_at_ms)
                VALUES ('pr-%1$s', 'feature/%1$s', 'main', '%1$s review', '',
                    'remote-open', 20, 20, %2$s, 'https://example.test/%2$s',
                    'external', '%3$s', 'octocat', 20)
                """.formatted(suffix, number, repository));
        execute(connection, """
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                VALUES ('%1$s-base-commit', 'pr-%1$s', '%2$s', 'base', 0, 0, 20)
                """.formatted(suffix, baseSha));
        execute(connection, """
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                VALUES ('%1$s-head-commit', 'pr-%1$s', '%2$s', 'head', 1, 0, 21)
                """.formatted(suffix, headSha));
        execute(connection, """
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms, workspace_id,
                    owner_thread_id, owner_task_id)
                VALUES ('review-%1$s', '%2$s', 'pr-%1$s', '%3$s', '%4$s',
                    'ACTIVE', 22, 22, %5$s, NULL, NULL)
                """.formatted(
                        suffix, repository, baseSha, headSha,
                        workspaceId == null ? "NULL" : "'" + workspaceId + "'"));
    }

    private static TaskSubject seedTaskReview(Connection connection)
            throws Exception
    {
        TaskSubject task = new TaskSubject(
                text(connection, """
                        SELECT identity.worktree_path
                        FROM task_code_identity identity
                        WHERE identity.task_id = 'task-1'
                        """),
                text(connection, """
                        SELECT code_fingerprint FROM task_current_code_subject_v230
                        WHERE task_id = 'task-1'
                        """),
                text(connection, """
                        SELECT head_sha FROM task_current_code_subject_v230
                        WHERE task_id = 'task-1'
                        """),
                text(connection, """
                        SELECT base_sha FROM task_current_code_subject_v230
                        WHERE task_id = 'task-1'
                        """));
        execute(connection, """
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms, workspace_id,
                    owner_thread_id, owner_task_id)
                VALUES ('review-task', 'acme/widget', 'pr-1', '%1$s', '%2$s',
                    'ACTIVE', 22, 22, 'workspace-1', 'trunk-1', 'task-1')
                """.formatted(task.baseSha(), task.headSha()));
        return task;
    }

    private static String standaloneOperation(
            String id,
            String ticketId,
            String reviewId,
            String prId,
            String repository,
            int number,
            String baseBranch,
            String workspaceId,
            String repositoryRoot,
            String scope,
            String headSha)
    {
        String baseSha = scope.equals("quick") ? "quick-base" : "full-base";
        return """
                INSERT INTO review_session_snapshot_operation_v293(
                    id, dispatch_ticket_id, review_id, command_id, pr_id,
                    repository, remote_pr_number, base_branch,
                    pr_title, pr_description, workspace_id,
                    repository_root, scope, request_json, expected_base_sha,
                    expected_head_sha, status, requested_at_ms)
                VALUES ('%1$s', '%2$s', '%3$s', 'command-%1$s', '%4$s',
                    '%5$s', %6$s, '%7$s',
                    (SELECT title FROM pr WHERE id = '%4$s'),
                    (SELECT description FROM pr WHERE id = '%4$s'),
                    %8$s, %9$s, '%10$s', '{}',
                    '%11$s', '%12$s', 'REQUESTED', 30)
                """.formatted(
                        id, ticketId, reviewId, prId, repository, number, baseBranch,
                        nullable(workspaceId), nullable(repositoryRoot), scope,
                        baseSha, headSha);
    }

    private static String standaloneTicket(
            String ticketId,
            String operationId,
            String reviewId,
            String workspaceId,
            String family,
            int laneMask,
            String headSha)
    {
        String baseSha = operationId.startsWith("quick")
                ? "quick-base"
                : "full-base";
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, trunk_control,
                    exclusive_task, writer_required, workspace_id, attempt,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('%1$s', '%2$s', 'CAPTURE_REVIEW_SESSION_SNAPSHOT',
                    '%3$s', 'REVIEW_SESSION', '%4$s',
                    'REVIEW_SESSION_SNAPSHOT_RESULT', %5$s, 0, 0, 0, %6$s, 1,
                    '%7$s', '%8$s', 'REQUESTED', 30)
                """.formatted(
                        ticketId, operationId, family, reviewId, laneMask,
                        nullable(workspaceId), headSha, baseSha);
    }

    private static String initialTaskOperation(String id, TaskSubject task)
    {
        return """
                INSERT INTO task_review_snapshot_operation_v286(
                    id, review_id, pr_id, repository, remote_pr_number,
                    base_branch, pr_title, pr_description,
                    task_id, task_epoch, worktree_path,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    start_options_json, status, requested_at_ms)
                VALUES ('%1$s', 'review-task', 'pr-1', NULL, NULL,
                    'main', 'Implement feature', 'Description',
                    'task-1', 1, '%2$s',
                    '%3$s', '%4$s', '%5$s', '{}', 'REQUESTED', 40)
                """.formatted(
                        id, task.worktreePath(), task.codeFingerprint(),
                        task.headSha(), task.baseSha());
    }

    private static String initialTaskTicket(
            String ticketId, String operationId, TaskSubject task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, trunk_control,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('%1$s', '%2$s', 'CAPTURE_TASK_REVIEW_SNAPSHOT',
                    'LOCAL_GIT', 'TASK', 'task-1',
                    'TASK_REVIEW_SNAPSHOT_RESULT', 16, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1, '%3$s', '%4$s',
                    '%5$s', 'REQUESTED', 40)
                """.formatted(
                        ticketId, operationId, task.codeFingerprint(),
                        task.headSha(), task.baseSha());
    }

    private static String taskRoundOperation(
            String id, String ticketId, TaskSubject task, String headSha)
    {
        return """
                INSERT INTO task_review_round_snapshot_operation_v293(
                    id, dispatch_ticket_id, review_id, command_id, pr_id,
                    repository, remote_pr_number, base_branch,
                    pr_title, pr_description,
                    task_id, task_epoch, worktree_path, code_fingerprint,
                    expected_head_sha, expected_base_sha, request_json, status,
                    requested_at_ms)
                VALUES ('%1$s', '%2$s', 'review-task', 'command-%1$s', 'pr-1',
                    NULL, NULL, 'main', 'Implement feature', 'Description',
                    'task-1', 1, '%3$s', '%4$s', '%5$s', '%6$s', '{}',
                    'REQUESTED', 50)
                """.formatted(
                        id, ticketId, task.worktreePath(), task.codeFingerprint(),
                        headSha, task.baseSha());
    }

    private static String taskRoundTicket(
            String ticketId, String operationId, TaskSubject task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, trunk_control,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('%1$s', '%2$s', 'CAPTURE_TASK_REVIEW_ROUND_SNAPSHOT',
                    'LOCAL_GIT', 'TASK', 'task-1',
                    'TASK_REVIEW_ROUND_SNAPSHOT_RESULT', 16, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1, '%3$s', '%4$s',
                    '%5$s', 'REQUESTED', 50)
                """.formatted(
                        ticketId, operationId, task.codeFingerprint(),
                        task.headSha(), task.baseSha());
    }

    private static void assertLiveThenTerminalCleanup(
            Connection connection,
            String operationTable,
            String operationId,
            String ticketId,
            String error)
            throws Exception
    {
        String delete = "DELETE FROM " + operationTable + " WHERE id = '"
                + operationId + "'";
        assertThatThrownBy(() -> execute(connection, delete))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(error);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'CANCELED',
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'migration test', completed_at_ms = 90
                WHERE id = '%s'
                """.formatted(ticketId));
        execute(connection, delete);
        assertThat(number(connection, """
                SELECT COUNT(*) FROM dispatch_ticket WHERE id = '%s'
                """.formatted(ticketId))).isZero();
    }

    private static String nullable(String value)
    {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static void migrate(String url)
    {
        MigratedSqliteDatabase.migrate(url);
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

    private static void transaction(Connection connection, String... statements)
            throws Exception
    {
        connection.setAutoCommit(false);
        try {
            for (String statement : statements) {
                execute(connection, statement);
            }
            connection.commit();
        }
        catch (Exception e) {
            connection.rollback();
            throw e;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    private static void assertHealthy(Connection connection)
            throws Exception
    {
        assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                .isZero();
        assertThat(text(connection, "PRAGMA integrity_check")).isEqualTo("ok");
    }

    private static ReviewSessionSnapshotRuntime reviewSnapshots(String url)
    {
        return new ReviewSessionSnapshotRuntime(
                jdbc(url), new ObjectMapper());
    }

    private static TaskReviewSnapshotRuntime taskSnapshots(String url)
    {
        return new TaskReviewSnapshotRuntime(
                jdbc(url), new ObjectMapper());
    }

    private static JdbcTemplate jdbc(String url)
    {
        return new JdbcTemplate(dataSource(url));
    }

    private static DriverManagerDataSource dataSource(String url)
    {
        return new DriverManagerDataSource(url);
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

    private record TaskSubject(
            String worktreePath,
            String codeFingerprint,
            String headSha,
            String baseSha) {}
}
