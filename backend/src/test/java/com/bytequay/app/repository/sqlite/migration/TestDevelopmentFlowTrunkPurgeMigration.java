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
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            execute(connection, """
                    UPDATE threads
                    SET lifecycle_state = 'ARCHIVED',
                        aggregate_version = aggregate_version + 1
                    WHERE id = 'trunk-1'
                    """);
            assertFails(connection,
                    "DELETE FROM threads WHERE id = 'trunk-1'");
        }

        SQLiteDataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long archivedVersion = jdbc.queryForObject(
                "SELECT aggregate_version FROM threads WHERE id = 'trunk-1'",
                Long.class);
        V2TrunkPurge purge = new V2TrunkPurge(
                jdbc, new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> purge.delete(
                "trunk-1", archivedVersion, () -> {
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

        purge.delete("trunk-1", archivedVersion,
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
