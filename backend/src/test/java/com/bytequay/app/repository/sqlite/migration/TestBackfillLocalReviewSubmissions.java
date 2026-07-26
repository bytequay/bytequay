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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrade fixture for the submitted-local-review backfill: timeline
 * submissions become durable rows in deterministic sequence, evidence
 * stamps completed/canceled outcomes, live loops stay open and
 * unbound, and the PR epoch becomes the maximum sequence.
 */
class TestBackfillLocalReviewSubmissions
{
    @TempDir
    private Path tempDir;

    @Test
    void backfillsDeterministicRowsWithEvidenceStamps()
            throws Exception
    {
        String url = seedAt207("submissions.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            // A live addressing loop: first batch's root resolved, second open.
            task(connection, "task-live", 1, "IDLE", "ADDRESSING_LOCAL_COMMENTS");
            pr(connection, "pr-live", "task-live", "local-open", 500L);
            comment(connection, "c-done", "pr-live", 100L, true);
            comment(connection, "c-open", "pr-live", 200L, false);
            submittedEvent(connection, "evt-1", "pr-live", 300L, "[\"c-done\"]");
            submittedEvent(connection, "evt-2", "pr-live", 400L, "[\"c-open\"]");

            // A shipped task: its batch is historical, never redriven.
            task(connection, "task-shipped", 2, "IN_REVIEW", "AWAITING_REMOTE_REVIEW");
            pr(connection, "pr-shipped", "task-shipped", "remote-open", null);
            comment(connection, "c-shipped", "pr-shipped", 100L, false);
            submittedEvent(connection, "evt-3", "pr-shipped", 300L, "[\"c-shipped\"]");
        }

        migrateTo208(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(scalar(connection,
                    "SELECT submission_seq FROM local_review_submission WHERE timeline_event_id = 'evt-1'"))
                    .isEqualTo("1");
            // All roots closed: completed at the addressed watermark.
            assertThat(scalar(connection,
                    "SELECT completed_at_ms FROM local_review_submission WHERE timeline_event_id = 'evt-1'"))
                    .isEqualTo("500");

            assertThat(scalar(connection,
                    "SELECT submission_seq FROM local_review_submission WHERE timeline_event_id = 'evt-2'"))
                    .isEqualTo("2");
            assertThat(scalar(connection,
                    "SELECT completed_at_ms IS NULL AND canceled_at_ms IS NULL AND agent_run_id IS NULL "
                            + "FROM local_review_submission WHERE timeline_event_id = 'evt-2'"))
                    .isEqualTo("1");

            assertThat(scalar(connection,
                    "SELECT cancel_reason FROM local_review_submission WHERE timeline_event_id = 'evt-3'"))
                    .isEqualTo("backfill_historical");

            assertThat(scalar(connection,
                    "SELECT local_review_epoch FROM pr WHERE id = 'pr-live'"))
                    .isEqualTo("2");
        }
    }

    private String seedAt207(String dbName)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(dbName) + "?foreign_keys=ON";
        Flyway.configure()
                .dataSource(url, "", "")
                .javaMigrations(new NormalizeDeadLifecycleStates(), new BackfillTurnLiveness())
                .target("207")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT OR IGNORE INTO workspaces(id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                    VALUES ('ws-default', 'Default', '', 0, 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO threads(
                        id, kind, provider, title, status, model,
                        cost_usd_milli, tokens_in, tokens_out,
                        created_at_ms, updated_at_ms,
                        workspace_id, flow, parallel_slots)
                    VALUES (
                        'thread-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE', 'claude-sonnet-4.6',
                        0, 0, 0, 1, 1, 'ws-default', 'build', 1)
                    """);
        }
        return url;
    }

    private static void migrateTo208(String url)
    {
        Flyway.configure()
                .dataSource(url, "", "")
                .javaMigrations(
                        new NormalizeDeadLifecycleStates(),
                        new BackfillTurnLiveness(),
                        new BackfillLocalReviewSubmissions(new ObjectMapper()))
                .target("208")
                .load()
                .migrate();
    }

    private static void task(Connection connection, String id, int seq, String status, String phase)
            throws Exception
    {
        connection.createStatement().executeUpdate(
                "INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms) "
                        + "VALUES ('" + id + "', 'thread-1', " + seq + ", '" + status + "', '" + phase + "', 1)");
    }

    private static void pr(
            Connection connection, String id, String taskId, String status, Long addressedThroughMs)
            throws Exception
    {
        connection.createStatement().executeUpdate(
                "INSERT INTO pr(id, task_id, branch_name, base_branch, title, description, status, "
                        + "created_at_ms, origin, local_addressed_through_ms) "
                        + "VALUES ('" + id + "', '" + taskId + "', 'dev/x', 'main', 'T', '', "
                        + "'" + status + "', 1, 'task', "
                        + (addressedThroughMs == null ? "NULL" : addressedThroughMs) + ")");
    }

    private static void comment(
            Connection connection, String id, String prId, long createdAtMs, boolean resolved)
            throws Exception
    {
        connection.createStatement().executeUpdate(
                "INSERT INTO pr_comment(id, pr_id, origin, scope, author, body, created_at_ms, "
                        + "resolved_at_ms) VALUES ('" + id + "', '" + prId + "', 'local', 'pr', "
                        + "'user', 'b', " + createdAtMs + ", "
                        + (resolved ? createdAtMs + 10 : "NULL") + ")");
    }

    private static void submittedEvent(
            Connection connection, String id, String prId, long createdAtMs, String commentIdsJson)
            throws Exception
    {
        connection.createStatement().executeUpdate(
                "INSERT INTO pr_timeline_event(id, pr_id, event_type, actor, is_local_only, "
                        + "created_at_ms, payload_json) VALUES ('" + id + "', '" + prId + "', "
                        + "'review', 'user', 1, " + createdAtMs + ", "
                        + "'{\"reviewEvent\":\"submitted\",\"commentIds\":" + commentIdsJson + "}')");
    }

    private static String scalar(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
