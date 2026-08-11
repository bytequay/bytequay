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
package com.bytequay.app.flow.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestNewFlowDatabase
{
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    private Path temporaryDirectory;

    @Test
    void installsAndRevalidatesTheExactBundle()
    {
        Path path = temporaryDirectory.resolve("new-flow.db");
        DataSource dataSource = dataSource(path);
        NewFlowDatabase database = new NewFlowDatabase(dataSource, CLOCK);

        database.bootstrap();
        database.bootstrap();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT schema_version FROM flow_schema_baseline",
                Integer.class)).isEqualTo(NewFlowDatabase.SCHEMA_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT bundle_digest FROM flow_schema_baseline",
                String.class)).isEqualTo(NewFlowDatabase.bundleDigest());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_schema "
                        + "WHERE type = 'table' AND name IN ("
                        + "'flow_runtime_task', 'flow_ci_round', "
                        + "'flow_user_gate', "
                        + "'flow_github_external_effect_plan')",
                Integer.class)).isEqualTo(4);
    }

    @Test
    void rejectsPartialAndDriftedSchemas()
    {
        Path partialPath = temporaryDirectory.resolve("partial.db");
        JdbcTemplate partial = new JdbcTemplate(dataSource(partialPath));
        partial.execute("CREATE TABLE stray (id TEXT PRIMARY KEY)");
        assertThatThrownBy(() -> new NewFlowDatabase(
                dataSource(partialPath), CLOCK).bootstrap())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partial new-flow schema");

        Path driftPath = temporaryDirectory.resolve("drift.db");
        DataSource driftSource = dataSource(driftPath);
        NewFlowDatabase drift = new NewFlowDatabase(driftSource, CLOCK);
        drift.bootstrap();
        new JdbcTemplate(driftSource).execute(
                "CREATE TABLE unauthorized_schema_drift (id INTEGER)");
        assertThatThrownBy(drift::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match its baseline");

        Path droppedPath = temporaryDirectory.resolve("dropped.db");
        DataSource droppedSource = dataSource(droppedPath);
        NewFlowDatabase dropped = new NewFlowDatabase(
                droppedSource, CLOCK);
        dropped.bootstrap();
        new JdbcTemplate(droppedSource).execute(
                "DROP INDEX flow_runtime_claimable_ticket");
        assertThatThrownBy(dropped::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match its baseline");

        Path markerPath = temporaryDirectory.resolve("marker.db");
        DataSource markerSource = dataSource(markerPath);
        NewFlowDatabase marker = new NewFlowDatabase(markerSource, CLOCK);
        marker.bootstrap();
        new JdbcTemplate(markerSource).update(
                "UPDATE flow_schema_baseline SET bundle_digest = 'tampered'");
        assertThatThrownBy(marker::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match its baseline");
    }

    @Test
    void midBundleFailureRollsBackAndCleanRestartSucceeds()
    {
        Path path = temporaryDirectory.resolve("rollback.db");
        DataSource dataSource = dataSource(path);
        NewFlowDatabase failing = new NewFlowDatabase(
                dataSource,
                CLOCK,
                installed -> {
                    if (installed == 2) {
                        throw new IllegalStateException("injected failure");
                    }
                });

        assertThatThrownBy(failing::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected failure");
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM sqlite_schema "
                        + "WHERE name NOT LIKE 'sqlite_%'",
                Integer.class)).isZero();

        new NewFlowDatabase(dataSource, CLOCK).bootstrap();
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM flow_schema_baseline",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsForeignKeyViolationsAndUnreadableFiles()
            throws Exception
    {
        Path foreignKeyPath = temporaryDirectory.resolve("foreign-key.db");
        DataSource foreignKeySource = dataSource(foreignKeyPath);
        NewFlowDatabase database = new NewFlowDatabase(
                foreignKeySource, CLOCK);
        database.bootstrap();
        try (Connection connection = foreignKeySource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.executeUpdate(
                    """
                    INSERT INTO flow_runtime_task_lifecycle_revision (
                        lifecycle_revision_id, task_id, sequence, from_status,
                        to_status, reason_code, recorded_at
                    ) VALUES ('broken', 'missing-task', 1, NULL,
                              'CREATED', 'BROKEN', 1)
                    """);
        }
        assertThatThrownBy(database::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("violates a foreign key");

        Path corruptPath = temporaryDirectory.resolve("corrupt.db");
        NewFlowDatabase corrupt = new NewFlowDatabase(
                dataSource(corruptPath), CLOCK);
        corrupt.bootstrap();
        Files.write(corruptPath, new byte[] {1, 2, 3, 4});
        assertThatThrownBy(corrupt::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap failed");
    }

    @Test
    void concurrentBootstrapsProduceOneCompleteMarker()
            throws Exception
    {
        Path path = temporaryDirectory.resolve("concurrent.db");
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        Runnable bootstrap = () -> {
            try {
                start.await();
                new NewFlowDatabase(dataSource(path), CLOCK).bootstrap();
            }
            catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        };
        Thread first = Thread.ofPlatform().start(bootstrap);
        Thread second = Thread.ofPlatform().start(bootstrap);
        start.countDown();
        first.join();
        second.join();

        assertThat(failures).isEmpty();
        assertThat(new JdbcTemplate(dataSource(path)).queryForObject(
                "SELECT COUNT(*) FROM flow_schema_baseline",
                Integer.class)).isEqualTo(1);
    }

    private static DataSource dataSource(Path path)
    {
        return new DriverManagerDataSource(
                "jdbc:sqlite:" + path
                        + "?foreign_keys=ON&busy_timeout=30000");
    }
}
