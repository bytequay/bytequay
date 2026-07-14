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
    void backfillRecreatesTheDeletedDefaultWorkspaceForAnUnattachedReview()
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
            assertThat(singleString(connection,
                    "SELECT name FROM workspaces WHERE id = 'ws-default'"))
                    .isEqualTo("ByteQuay");
            assertThat(singleString(connection,
                    "SELECT workspace_id FROM threads WHERE id = 'agent-review-review-external'"))
                    .isEqualTo("ws-default");
            assertThat(singleString(connection,
                    "SELECT owner_thread_id FROM review_session WHERE id = 'review-external'"))
                    .isEqualTo("agent-review-review-external");
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
}
