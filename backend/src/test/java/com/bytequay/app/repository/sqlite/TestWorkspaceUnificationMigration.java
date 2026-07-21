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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class TestWorkspaceUnificationMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void upgradesExistingWorkspaceDataWithoutReplacingStableRecords()
            throws Exception
    {
        String url = "jdbc:sqlite:"
                + tempDir.resolve("workspace-unification.db")
                + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "")
                .target("180")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            seedWorkspace(connection);
            seedTrunksAndSession(connection);
            seedBacklog(connection);
            seedNotifications(connection);
            seedDuplicateReviewTrunks(connection);
        }

        Flyway.configure().dataSource(url, "", "").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(singleString(connection, """
                    SELECT checksum FROM flyway_schema_history
                    WHERE version = '181'
                    """)).isEqualTo("-1236179157");
            assertWorkspaceAndDefaults(connection);
            assertSessionBackfill(connection);
            assertBacklogBackfill(connection);
            assertNotificationBackfill(connection);
            assertReviewTrunkInvariant(connection);
        }
    }

    private static void seedWorkspace(Connection connection)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO watched_repos(
                    owner, repo, display_order, local_clone_path)
                VALUES ('acme', 'widget', 0, '/repos/widget')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES (
                    'ws-widget', 'Widget',
                    '# Conventions\n\nKeep stable identifiers.',
                    0, 1, 2)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES ('ws-widget', 'acme/widget', 'main', 0, 3)
                """);
    }

    private static void seedTrunksAndSession(Connection connection)
            throws Exception
    {
        insertTrunk(connection, "trunk-dev", "build", 10);
        connection.createStatement().executeUpdate("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, task_type, created_at_ms)
                VALUES ('task-dev', 'trunk-dev', 1, 'COMPLETED',
                        'DEVELOP', 11)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO agent_run(
                    id, task_id, kind, status, iterations,
                    metrics_json, started_at_ms, finished_at_ms)
                VALUES (
                    'run-dev', 'task-dev', 'ci_fix', 'succeeded', 2,
                    '{"provider":"claude-code","model":"sonnet"}',
                    12, 13)
                """);
    }

    private static void seedBacklog(Connection connection) throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO backlog_item(
                    id, thread_id, title, body, tags_json, created_at_ms,
                    workspace_id, priority, source, status, created_by)
                VALUES (
                    'backlog-created', 'trunk-dev', 'Fallback title',
                    'First paragraph.\n\nFull historic detail.', '[]', 20,
                    'ws-widget', 'high', 'trunk-split', 'created', 'agent')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO backlog_item(
                    id, thread_id, title, body, tags_json, created_at_ms,
                    workspace_id, priority, source, status, created_by)
                VALUES (
                    'backlog-discarded', 'trunk-dev', 'Discarded item',
                    '', '[]', 21,
                    'ws-widget', 'low', 'manual',
                    'not-to-proceed', 'user')
                """);
    }

    private static void seedNotifications(Connection connection)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO notifications(
                    id, kind, thread_id, status, payload_json, created_at_ms)
                VALUES (
                    'notification-gate', 'AWAITING_REVIEW', 'trunk-dev',
                    'UNREAD',
                    '{"title":"Approve publish","summary":"Drafts are local"}',
                    30)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO thread_signal(
                    id, thread_id, source_kind, icon_kind, title, body,
                    source_url, created_at_ms)
                VALUES (
                    'signal-ci', 'trunk-dev', 'agent', 'success',
                    'Checks passed', 'All checks are green',
                    'https://example.test/checks', 31)
                """);
    }

    private static void seedDuplicateReviewTrunks(Connection connection)
            throws Exception
    {
        insertTrunk(connection, "trunk-review-old", "review", 40);
        insertTrunk(connection, "trunk-review-new", "review", 41);
        connection.createStatement().executeUpdate("""
                INSERT INTO pr(
                    id, branch_name, base_branch, title, description,
                    status, created_at_ms, remote_pr_number, origin, repo)
                VALUES (
                    'pr-widget-17', 'feature', 'main', 'Widget PR', '',
                    'remote-open', 40, 17, 'external', 'acme/widget')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms,
                    workspace_id, owner_thread_id)
                VALUES (
                    'review-old', 'acme/widget', 'pr-widget-17',
                    'base', 'head', 'COMPLETED', 40, 40,
                    'ws-widget', 'trunk-review-old')
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms,
                    workspace_id, owner_thread_id)
                VALUES (
                    'review-new', 'acme/widget', 'pr-widget-17',
                    'base', 'head', 'COMPLETED', 41, 41,
                    'ws-widget', 'trunk-review-new')
                """);
    }

    private static void insertTrunk(
            Connection connection,
            String id,
            String flow,
            long updatedAt)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO threads(
                    id, kind, provider, agent_session_id, title, status,
                    model, cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, ended_at_ms, error_message,
                    workspace_id, flow, parallel_slots)
                VALUES (
                    '%s', 'CLI_AGENT', 'claude-code', NULL, '%s', 'IDLE',
                    'sonnet', 0, 0, 0,
                    %d, %d, NULL, NULL,
                    'ws-widget', '%s', 1)
                """.formatted(id, id, updatedAt, updatedAt, flow));
    }

    private static void assertWorkspaceAndDefaults(Connection connection)
            throws Exception
    {
        assertThat(singleString(connection, """
                SELECT name FROM workspaces WHERE id = 'ws-widget'
                """)).isEqualTo("Widget");
        assertThat(singleString(connection, """
                SELECT memory_md FROM workspaces WHERE id = 'ws-widget'
                """)).isEqualTo("# Conventions\n\nKeep stable identifiers.");
        assertThat(singleString(connection, """
                SELECT repo_full_name FROM workspace_repos
                WHERE workspace_id = 'ws-widget'
                """)).isEqualTo("acme/widget");
        assertThat(singleString(connection, """
                SELECT json_extract(settings_json, '$.sessionCapUsd')
                FROM workspace_settings WHERE workspace_id = 'ws-widget'
                """)).isEqualTo("1.0");
        assertThat(singleString(connection, """
                SELECT json_extract(settings_json, '$.brainBudgetChars')
                FROM workspace_settings WHERE workspace_id = 'ws-widget'
                """)).isEqualTo("8000");
        // V192 repairs the seed-complete milestone: it means an accepted
        // seed run, not merely a workspace with memory content. ws-widget has
        // no applied seed distill_run, so the milestone is reset to 0.
        assertThat(singleString(connection, """
                SELECT memory_seed_complete || ':' || first_trunk_complete
                FROM workspace_onboarding WHERE workspace_id = 'ws-widget'
                """)).isEqualTo("0:1");
    }

    private static void assertSessionBackfill(Connection connection)
            throws Exception
    {
        assertThat(singleString(connection, """
                SELECT workspace_id || ':' || thread_id || ':' || provider
                       || ':' || model || ':' || outcome
                FROM agent_run WHERE id = 'run-dev'
                """)).isEqualTo(
                        "ws-widget:trunk-dev:claude-code:sonnet:completed");
    }

    private static void assertBacklogBackfill(Connection connection)
            throws Exception
    {
        assertThat(singleString(connection, """
                SELECT item_key || ':' || status || ':' || source
                FROM backlog_item WHERE id = 'backlog-created'
                """)).isEqualTo("BQ-1:open:agent");
        assertThat(singleString(connection, """
                SELECT summary FROM backlog_item
                WHERE id = 'backlog-created'
                """)).isEqualTo("First paragraph.");
        assertThat(singleString(connection, """
                SELECT detail FROM backlog_item
                WHERE id = 'backlog-created'
                """)).isEqualTo(
                        "First paragraph.\n\nFull historic detail.");
        assertThat(singleString(connection, """
                SELECT item_key || ':' || status || ':' || summary
                FROM backlog_item WHERE id = 'backlog-discarded'
                """)).isEqualTo("BQ-2:discarded:Discarded item");
        assertThat(singleString(connection, """
                SELECT next_value FROM workspace_backlog_seq
                WHERE workspace_id = 'ws-widget'
                """)).isEqualTo("3");
    }

    private static void assertNotificationBackfill(Connection connection)
            throws Exception
    {
        assertThat(singleString(connection, """
                SELECT workspace_id || ':' || public_type || ':' || title
                       || ':' || item_path || ':' || dedup_key
                FROM notifications WHERE id = 'notification-gate'
                """)).isEqualTo(
                        "ws-widget:approval-gate:Approve publish:"
                                + "#/workspace/ws-widget/trunks/trunk-dev:"
                                + "legacy:notification-gate");
        assertThat(singleString(connection, """
                SELECT public_type || ':' || title || ':' || summary
                       || ':' || dedup_key
                FROM notifications WHERE id = 'signal:signal-ci'
                """)).isEqualTo(
                        "agent-update:Checks passed:All checks are green:"
                                + "signal:signal-ci");
    }

    private static void assertReviewTrunkInvariant(Connection connection)
            throws Exception
    {
        assertThat(singleString(connection, """
                SELECT pr_ref FROM threads WHERE id = 'trunk-review-old'
                """)).isNull();
        assertThat(singleString(connection, """
                SELECT pr_ref FROM threads WHERE id = 'trunk-review-new'
                """)).isEqualTo("acme/widget#17");
        assertThat(singleString(connection, """
                SELECT count(*) FROM threads
                WHERE workspace_id = 'ws-widget'
                  AND flow = 'review'
                  AND pr_ref = 'acme/widget#17'
                """)).isEqualTo("1");
    }

    private static String singleString(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
