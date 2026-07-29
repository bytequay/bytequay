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
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLegacyTaskCreationRetirementMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void keepsHistoricalLegacyTasksReadableAndRejectsNewOnes()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("retirement.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url, "276");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'model', 0, 0, 0, 1, 1, 'workspace-1', 'build', 1)
                """);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version)
                VALUES ('historical-task', 'trunk-1', 1, 'COMPLETED',
                    'COMPLETED', 1, 'LEGACY')
                """);

        migrate(url, "277");

        assertThat(jdbc.queryForObject(
                "SELECT workflow_version FROM tasks WHERE id = 'historical-task'",
                String.class)).isEqualTo("LEGACY");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version)
                VALUES ('new-legacy-task', 'trunk-1', 2, 'PENDING',
                    'IMPLEMENTING', 2, 'LEGACY')
                """))
                .hasMessageContaining("LEGACY Task creation is retired");
    }

    @Test
    void laterProductionMigrationsKeepTheRetirementFence()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("full-retirement.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url, "290");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'model', 0, 0, 0, 1, 1, 'workspace-1', 'build', 1)
                """);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'trigger' AND name = 'legacy_task_creation_retired'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'manual_pr_validation_operation'
                """, Integer.class)).isOne();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version)
                VALUES ('new-legacy-task', 'trunk-1', 1, 'PENDING',
                    'IMPLEMENTING', 2, 'LEGACY')
                """))
                .hasMessageContaining("LEGACY Task creation is retired");
    }

    private static void migrate(String url, String target)
    {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, "", "")
                .locations("classpath:db/migration")
                .javaMigrations(
                        new BackfillTurnLiveness(),
                        new BackfillLocalReviewSubmissions(new ObjectMapper()),
                        new NormalizeDeadLifecycleStates());
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
