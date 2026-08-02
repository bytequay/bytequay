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

import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowCanaryRoute;
import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowInvariantAuditor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowCanaryGuardsMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void exposesFailClosedCanaryControlsAndEmptyDatabaseDiagnostics()
    {
        String url = migrated("diagnostics.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        DevelopmentFlowInvariantAuditor auditor =
                new DevelopmentFlowInvariantAuditor(new JdbcTemplate(dataSource));

        assertThat(auditor.audit().healthy()).isTrue();
        assertThat(auditor.legacyDrainStatus().drained()).isTrue();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('legacy-workspace', 'Legacy', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots)
                VALUES ('legacy-trunk', 'CLI_AGENT', 'claude-code', 'Legacy',
                    'IDLE', 'model', 0, 0, 0, 1, 1, 'legacy-workspace',
                    'build', 1)
                """);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms)
                VALUES ('legacy-task', 'legacy-trunk', 1, 'IDLE', 'COMPLETED', 1)
                """);
        assertThat(auditor.legacyDrainStatus().nonterminalTasks()).isOne();
        jdbc.update("UPDATE tasks SET status = 'COMPLETED' WHERE id = 'legacy-task'");
        assertThat(auditor.legacyDrainStatus().drained()).isTrue();

        DevelopmentFlowCanaryRoute route = new DevelopmentFlowCanaryRoute();
        assertThat(route.routesNewTaskToV2("workspace-1")).isTrue();
        assertThat(route.routesNewTaskToV2("workspace-3")).isTrue();
        assertThat(route.routesNewTaskToV2(" ")).isFalse();
        assertThat(route.routesNewTaskToV2(null)).isFalse();
        assertThat(route.snapshot().v2Only()).isTrue();
    }

    private String migrated(String name)
    {
        String url = url(name);
        migrate(url);
        return url;
    }

    private String url(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name) + "?foreign_keys=ON";
    }

    private static void migrate(String url)
    {
        MigratedSqliteDatabase.migrate(url);
    }

}
