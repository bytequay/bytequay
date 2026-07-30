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
import java.sql.ResultSet;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class TestWorkspaceBaseBranchBackfillMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void fillsMissingBranchesFromRepositoryMetadataWithoutReplacingOverrides()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("workspace-base-branch.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            createPrerequisites(connection);
            seedRows(connection);
        }

        migrateOnlyV296(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(branch(connection, "acme/null-base")).isEqualTo("main");
            assertThat(branch(connection, "acme/blank-base")).isEqualTo("release/1.x");
            assertThat(branch(connection, "acme/explicit-base")).isEqualTo("develop");
            assertThat(branch(connection, "acme/blank-metadata")).isNull();
            assertThat(branch(connection, "acme/no-metadata")).isNull();
        }
    }

    private void migrateOnlyV296(String url)
            throws Exception
    {
        Path migrations = tempDir.resolve("migrations");
        Files.createDirectories(migrations);
        try (InputStream source = requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V296__backfill_workspace_base_branch.sql"))) {
            Files.copy(source, migrations.resolve(
                    "V296__backfill_workspace_base_branch.sql"));
        }
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("filesystem:" + migrations)
                .baselineVersion("295")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private static void createPrerequisites(Connection connection)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                CREATE TABLE workspace_repos(
                    workspace_id TEXT NOT NULL,
                    repo_full_name TEXT NOT NULL,
                    default_base_branch TEXT,
                    PRIMARY KEY (workspace_id, repo_full_name))
                """);
        connection.createStatement().executeUpdate("""
                CREATE TABLE repo_meta(
                    owner TEXT NOT NULL,
                    repo TEXT NOT NULL,
                    full_name TEXT NOT NULL,
                    default_branch TEXT,
                    PRIMARY KEY (owner, repo))
                """);
    }

    private static void seedRows(Connection connection)
            throws Exception
    {
        connection.createStatement().executeUpdate("""
                INSERT INTO workspace_repos VALUES
                    ('workspace-1', 'acme/null-base', NULL),
                    ('workspace-1', 'acme/blank-base', '   '),
                    ('workspace-1', 'acme/explicit-base', 'develop'),
                    ('workspace-1', 'acme/blank-metadata', NULL),
                    ('workspace-1', 'acme/no-metadata', NULL)
                """);
        connection.createStatement().executeUpdate("""
                INSERT INTO repo_meta VALUES
                    ('Acme', 'null-base', 'Acme/Null-Base', 'main'),
                    ('acme', 'blank-base', 'acme/blank-base', '  release/1.x  '),
                    ('acme', 'explicit-base', 'acme/explicit-base', 'main'),
                    ('acme', 'blank-metadata', 'acme/blank-metadata', '   ')
                """);
    }

    private static String branch(Connection connection, String repository)
            throws Exception
    {
        try (var statement = connection.prepareStatement("""
                SELECT default_base_branch
                FROM workspace_repos
                WHERE repo_full_name = ?
                """)) {
            statement.setString(1, repository);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }
}
