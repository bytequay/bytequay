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
import java.sql.SQLException;
import java.sql.Statement;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewRoundFrozenSnapshotMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void snapshotIsExactImmutableAndOwnedByItsRound()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("review-snapshot.db")
                + "?foreign_keys=ON";
        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, """
                    CREATE TABLE pr(
                        id TEXT PRIMARY KEY, repo TEXT,
                        remote_pr_number INTEGER, base_branch TEXT NOT NULL,
                        title TEXT NOT NULL, description TEXT NOT NULL)
                    """);
            execute(connection, """
                    CREATE TABLE review_session(
                        id TEXT PRIMARY KEY,
                        pr_id TEXT NOT NULL REFERENCES pr(id),
                        base_commit TEXT NOT NULL)
                    """);
            execute(connection, """
                    CREATE TABLE review_round(
                        id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL REFERENCES review_session(id)
                            ON DELETE CASCADE,
                        start_commit TEXT NOT NULL)
                    """);
            execute(connection, """
                    INSERT INTO pr VALUES (
                        'pr-1', 'acme/widget', 42, 'main',
                        'Frozen title', 'Frozen description')
                    """);
            execute(connection, """
                    INSERT INTO review_session VALUES (
                        'review-1', 'pr-1', 'base-1')
                    """);
            execute(connection,
                    "INSERT INTO review_round VALUES ('round-1', 'review-1', 'head-1')");
            execute(connection,
                    "INSERT INTO review_round VALUES ('round-2', 'review-1', 'head-2')");
        }
        migrateOnlyV291(url);

        try (Connection connection = DriverManager.getConnection(url)) {
            execute(connection, snapshotSql("round-1", "head-1"));
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM review_round_snapshot_v291")).isOne();

            assertThatThrownBy(() -> execute(connection, """
                    UPDATE review_round_snapshot_v291
                    SET diff = 'changed'
                    WHERE round_id = 'round-1'
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("snapshot is immutable");
            assertThatThrownBy(() -> execute(
                    connection, snapshotSql("round-2", "wrong-head")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exact ReviewSession subject");

            execute(connection, """
                    UPDATE pr
                    SET repo = 'other/widget', remote_pr_number = 99,
                        base_branch = 'develop', title = 'Mutable title',
                        description = 'Mutable description'
                    WHERE id = 'pr-1'
                    """);
            assertThat(text(connection, """
                    SELECT repository || ':' || remote_pr_number || ':'
                        || base_branch || ':' || pr_title || ':' || pr_description
                    FROM review_round_snapshot_v291
                    WHERE round_id = 'round-1'
                    """))
                    .isEqualTo("acme/widget:42:main:Frozen title:Frozen description");
            assertThat(text(connection, """
                    SELECT file_contents_json
                    FROM review_round_snapshot_v291
                    WHERE round_id = 'round-1'
                    """))
                    .isEqualTo("{\"A.java\":\"frozen body\\n\"}");
            assertThatThrownBy(() -> execute(
                    connection, snapshotSql("round-2", "head-2")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exact ReviewSession subject");

            execute(connection, "DELETE FROM review_round WHERE id = 'round-1'");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM review_round_snapshot_v291")).isZero();
        }
    }

    private void migrateOnlyV291(String url)
            throws Exception
    {
        Path migrations = tempDir.resolve("migrations");
        Files.createDirectories(migrations);
        try (InputStream source = requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V291__review_round_frozen_snapshot.sql"))) {
            Files.copy(source, migrations.resolve(
                    "V291__review_round_frozen_snapshot.sql"));
        }
        Flyway.configure()
                .dataSource(url, null, null)
                .locations("filesystem:" + migrations)
                .baselineVersion("290")
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    private static String snapshotSql(String roundId, String headCommit)
    {
        return """
                INSERT INTO review_round_snapshot_v291(
                    round_id, repository, remote_pr_number, base_branch,
                    pr_title, pr_description, base_commit, head_commit,
                    diff, files_json, file_contents_json,
                    local_root, repository_root, capabilities_json, created_at_ms)
                VALUES ('%s', 'acme/widget', 42, 'main',
                    'Frozen title', 'Frozen description', 'base-1', '%s',
                    'diff', '[]', '{"A.java":"frozen body\\n"}', NULL, NULL,
                    '{"source_mode":"frozen-changed-files","available":[],"unavailable":[]}', 1)
                """.formatted(roundId, headCommit);
    }

    private static void execute(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int number(Connection connection, String sql)
            throws Exception
    {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getInt(1);
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
