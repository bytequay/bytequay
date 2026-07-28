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

import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.assertFails;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowTrunkPurgeMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void freshDatabaseRequiresExactTransactionScopedAuthorization()
            throws Exception
    {
        String url = database("fresh-purge.db");
        migrate(url, "269");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            execute(connection, """
                    UPDATE threads
                    SET lifecycle_state = 'ARCHIVED', aggregate_version = 1
                    WHERE id = 'trunk-1'
                    """);
            assertFails(connection,
                    "DELETE FROM threads WHERE id = 'trunk-1'");
        }

        SQLiteDataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        V2TrunkPurge purge = new V2TrunkPurge(
                jdbc, new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> purge.delete(
                "trunk-1", 1, () -> {
                    jdbc.update("DELETE FROM threads WHERE id = 'trunk-1'");
                    throw new IllegalStateException("rollback proof");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback proof");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM threads WHERE id = 'trunk-1'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM v2_trunk_purge_authorization_v269",
                Integer.class)).isZero();

        purge.delete("trunk-1", 1,
                () -> jdbc.update("DELETE FROM threads WHERE id = 'trunk-1'"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM threads WHERE id = 'trunk-1'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM v2_trunk_purge_authorization_v269",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspaces WHERE id = 'workspace-1'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check",
                Integer.class)).isZero();
    }

    @Test
    void upgradeRejectsIncompleteCleanupOpenWaitAndLiveOperation()
            throws Exception
    {
        String url = database("upgrade-blockers.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url, "268");
        migrate(url, "269");

        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO stage_question(
                        id, turn_id, call_id, prompt, state, created_at_ms)
                    VALUES ('open-question', 'development-turn-1',
                        'open-call', 'What should change?', 'OPEN', 100)
                    """);
            execute(connection, """
                    INSERT INTO stage_steering_request_v257(
                        id, command_id, task_id, task_epoch, stage_id,
                        stage_kind, stage_generation, accepted_stage_version,
                        accepted_checkpoint, mode, body, content_digest,
                        status, requested_by, requested_at_ms)
                    SELECT 'pending-steer', 'pending-steer-command', task.id,
                           task.epoch, owner.id, owner.kind, owner.generation,
                           owner.version, owner.checkpoint, 'APPEND',
                           'Continue with this constraint',
                           '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                           'PENDING', 'user', 101
                    FROM tasks task
                    JOIN task_current_stage current ON current.task_id = task.id
                    JOIN stage owner ON owner.id = current.stage_id
                    WHERE task.id = 'task-1'
                    """);

            assertThat(number(connection, """
                    SELECT nonterminal_task_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT incomplete_cleanup_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT open_wait_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT live_operation_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isGreaterThanOrEqualTo(1);
            assertThat(number(connection, """
                    SELECT incomplete_stage_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isGreaterThanOrEqualTo(1);

            execute(connection, """
                    UPDATE threads
                    SET lifecycle_state = 'ARCHIVED',
                        aggregate_version = aggregate_version + 1
                    WHERE id = 'trunk-1'
                    """);
            assertFails(connection, """
                    INSERT INTO v2_trunk_purge_authorization_v269(
                        trunk_id, archived_version, authorized_at_ms)
                    SELECT id, aggregate_version, 102 FROM threads
                    WHERE id = 'trunk-1'
                    """);
        }

        migrate(url, "269");
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM stage_steering_request_v257
                    WHERE id = 'pending-steer'
                    """)).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    private String database(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
    }

    private static SQLiteDataSource dataSource(String url)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }
}
