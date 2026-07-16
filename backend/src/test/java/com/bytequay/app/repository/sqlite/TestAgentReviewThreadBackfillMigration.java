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

class TestAgentReviewThreadBackfillMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void unattachedReviewBecomesRemoteOnlyInsteadOfRecreatingDefaultWorkspace()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("upgrade.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("170").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO workspaces(id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                    VALUES ('ws-project', 'Project', '', 0, 1, 1)
                    """);
            connection.createStatement().executeUpdate(
                    "DELETE FROM workspaces WHERE id = 'ws-default'");
            connection.createStatement().executeUpdate("""
                    INSERT INTO pr(
                        id, branch_name, base_branch, title, description, status,
                        created_at_ms, remote_pr_number, origin, repo)
                    VALUES ('pr-external', 'head', 'main', 'External PR', '', 'remote-open',
                            1, 7, 'external', 'outside/repo')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO review_session(
                        id, repo_id, pr_id, base_commit, reviewed_head_commit,
                        status, created_at_ms, updated_at_ms)
                    VALUES ('review-external', 'outside/repo', 'pr-external',
                            'base', 'head', 'ACTIVE', 1, 1)
                    """);
        }

        Flyway.configure().dataSource(url, "", "").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(exists(connection,
                    "SELECT 1 FROM workspaces WHERE id = 'ws-default'")).isFalse();
            assertThat(singleString(connection,
                    "SELECT workspace_id FROM review_session WHERE id = 'review-external'"))
                    .isNull();
            assertThat(singleString(connection,
                    "SELECT owner_thread_id FROM review_session WHERE id = 'review-external'"))
                    .isNull();
        }
    }

    @Test
    void reviewWithAnUnambiguousLocalCloneMovesOutOfTheDefaultWorkspace()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("local-upgrade.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("170").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO watched_repos(owner, repo, display_order, local_clone_path)
                    VALUES ('acme', 'widget', 0, '/repos/widget')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO workspace_repos(
                        workspace_id, repo_full_name, default_base_branch, auto_fix_enabled, added_at_ms)
                    VALUES ('ws-default', 'acme/widget', NULL, 0, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO pr(
                        id, branch_name, base_branch, title, description, status,
                        created_at_ms, remote_pr_number, origin, repo)
                    VALUES ('pr-widget', 'head', 'main', 'Widget PR', '', 'remote-open',
                            1, 8, 'external', 'acme/widget')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO review_session(
                        id, repo_id, pr_id, base_commit, reviewed_head_commit,
                        status, created_at_ms, updated_at_ms)
                    VALUES ('review-widget', 'acme/widget', 'pr-widget',
                            'base', 'head', 'ACTIVE', 1, 1)
                    """);
        }

        Flyway.configure().dataSource(url, "", "").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(exists(connection,
                    "SELECT 1 FROM workspaces WHERE id = 'ws-default'")).isFalse();
            assertThat(singleString(connection,
                    "SELECT workspace_id FROM review_session WHERE id = 'review-widget'"))
                    .isEqualTo("ws-local-repo-1");
            assertThat(singleString(connection,
                    "SELECT workspace_id FROM threads WHERE id = "
                            + "(SELECT owner_thread_id FROM review_session WHERE id = 'review-widget')"))
                    .isEqualTo("ws-local-repo-1");
        }
    }

    @Test
    void lifecycleBackfillUpgradesADatabaseAlreadyAtV172()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("round-lifecycle.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("172").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(singleString(connection, """
                    SELECT checksum FROM flyway_schema_history WHERE version = '172'
                    """)).isEqualTo("914534129");
            connection.createStatement().executeUpdate("""
                    INSERT INTO pr(
                        id, branch_name, base_branch, title, description, status,
                        created_at_ms, origin, repo)
                    VALUES ('pr-running-review', 'head', 'main', 'Running review', '',
                            'remote-open', 1, 'external', 'acme/repo')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO agent_run(id, kind, status, iterations, budget, started_at_ms)
                    VALUES ('run-running-review', 'panel_review', 'running', 0, 50, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO review_session(
                        id, repo_id, pr_id, base_commit, reviewed_head_commit,
                        status, created_at_ms, updated_at_ms)
                    VALUES ('review-running', 'acme/repo', 'pr-running-review',
                            'base', 'head', 'ACTIVE', 1, 1)
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO review_round(
                        id, session_id, agent_run_id, trigger, scope, start_commit,
                        status, budget_json, created_at_ms)
                    VALUES ('round-running', 'review-running', 'run-running-review',
                            'continuation', 'full', 'head', 'RUNNING',
                            '{"cost_cap_cents":50,"wall_clock_minutes":10}', 1)
                    """);
            assertThat(singleString(connection, """
                    SELECT lifecycle_finalized FROM review_round WHERE id = 'round-running'
                    """)).isEqualTo("1");
        }

        Flyway.configure().dataSource(url, "", "").load().migrate();

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(singleString(connection, """
                    SELECT lifecycle_finalized FROM review_round WHERE id = 'round-running'
                    """)).isEqualTo("0");
        }
    }

    private static String singleString(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static boolean exists(Connection connection, String sql) throws Exception
    {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            return result.next();
        }
    }
}
