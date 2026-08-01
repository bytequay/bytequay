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

import static org.assertj.core.api.Assertions.assertThat;

class TestPushFailureMilestoneMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void backfillsActivePermanentFailureIntoPrAndBrainTimelines()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("push-failure.db") + "?foreign_keys=ON";
        MigratedSqliteDatabase.migrate(url);
        try (Connection connection = DriverManager.getConnection(url)) {
            seedPermanentFailure(connection);
        }

        MigratedSqliteDatabase.migrate(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(singleLong(connection, """
                    SELECT COUNT(*) FROM pr_timeline_event
                    WHERE pr_id = 'pr-1' AND event_type = 'pull-request-progress'
                    """)).isEqualTo(1);
            assertThat(singleString(connection, """
                    SELECT json_extract(payload_json, '$.phase') || ':'
                           || json_extract(payload_json, '$.failedStep') || ':'
                           || json_extract(payload_json, '$.reason')
                    FROM pr_timeline_event WHERE pr_id = 'pr-1'
                    """)).isEqualTo("failed:ensure_pull_request:GitHub returned 403 Forbidden");
            assertThat(singleLong(connection, """
                    SELECT COUNT(*) FROM task_stage_event
                    WHERE task_id = 'task-1' AND event_type = 'PULL_REQUEST_PROGRESS'
                    """)).isEqualTo(1);
            assertThat(singleString(connection, """
                    SELECT json_extract(payload_json, '$.branch') || ':'
                           || json_extract(payload_json, '$.baseBranch')
                    FROM task_stage_event
                    WHERE task_id = 'task-1' AND event_type = 'PULL_REQUEST_PROGRESS'
                    """)).isEqualTo("feature/publish:main");
        }
    }

    private static void seedPermanentFailure(Connection connection)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO workspaces(id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'Workspace', '', 0, 1, 1)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow, parallel_slots)
                VALUES (
                    'thread-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE', 'claude-sonnet-4.6',
                    0, 0, 0, 1, 1, 'ws-1', 'build', 1)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms)
                VALUES ('task-1', 'thread-1', 1, 'NEEDS_ATTENTION', 'NEEDS_ATTENTION', 2)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO pr(
                    id, task_id, branch_name, base_branch, title, description,
                    status, created_at_ms, origin)
                VALUES (
                    'pr-1', 'task-1', 'feature/publish', 'main', 'Publish me', '',
                    'local-open', 3, 'task')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO task_stage(
                    id, task_id, stage_type, state, opened_at_ms, closed_at_ms)
                VALUES (
                    'stage-1', 'task-1', 'DEVELOPMENT_STAGE', 'CLOSED', 4, 5)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO task_push_authorization(
                    token, task_id, pr_id, head_sha, code_fingerprint, actor,
                    basis_kind, payload_json, payload_digest, created_at_ms)
                VALUES (
                    'push-1', 'task-1', 'pr-1', 'head-1', 'fingerprint-1', 'AGENT',
                    'brain_review', '{}', 'digest-1', 6)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO task_push_effect(
                    token, effect_key, status, attempts, attempt_limit,
                    last_claimed_at_ms, last_error_class, last_error)
                VALUES (
                    'push-1', 'ensure_pull_request', 'PERMANENT_FAILED', 1, 3,
                    7, 'ResponseStatusException', 'GitHub returned 403 Forbidden')
                """);
    }

    private static String singleString(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long singleLong(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
