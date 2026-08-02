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

import com.bytequay.app.testing.MigratedSqliteDatabase;
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
    void currentBaselineKeepsLegacyRowsOutsideV2Execution()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("full-retirement.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url);
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
                    'COMPLETED', 2, 'LEGACY')
                """);
        assertThat(jdbc.queryForObject(
                "SELECT workflow_version FROM tasks WHERE id = 'historical-task'",
                String.class)).isEqualTo("LEGACY");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, workspace_id, trunk_id, task_id,
                    task_epoch, attempt, status, created_at_ms)
                VALUES ('legacy-ticket', 'legacy-operation', 'LEGACY_TEST',
                    'VALIDATION', 'TASK', 'historical-task', 'LEGACY_RESULT', 4,
                    1, 'workspace-1', 'trunk-1', 'historical-task',
                    1, 1, 'REQUESTED', 3)
                """))
                .hasMessageContaining("DispatchTicket Task scope is invalid");
    }

    private static void migrate(String url)
    {
        MigratedSqliteDatabase.migrate(url);
    }
}
