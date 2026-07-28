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
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowCanaryGuardsMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void rejectsLegacyOwnersForV2WithoutBlockingLegacySiblingDrain()
            throws Exception
    {
        String url = url("mixed-route.db");
        migrate(url, "228");
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(connection);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(connection, 1);
        }
        migrate(url);
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN", "MERGEABLE");
            DevelopmentFlowRemoteProtocolFixture.acceptSnapshot(
                    connection, 1, 1, "head-1", "base-1");
            execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms,
                        workflow_version)
                    VALUES ('legacy-task', 'trunk-1', 2, 'PENDING',
                        'IMPLEMENTING', 2, 'LEGACY')
                    """);

            assertThatThrownBy(() -> execute(connection, """
                    UPDATE tasks SET status = 'RUNNING' WHERE id = 'task-1'
                    """))
                    .hasMessageContaining("LEGACY Task authority");
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO task_stage(
                        id, task_id, stage_type, state, opened_at_ms)
                    VALUES ('legacy-stage-for-v2', 'task-1',
                        'DEVELOPMENT_STAGE', 'OPEN', 80)
                    """))
                    .hasMessageContaining("LEGACY Stage");
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO thread_turns(
                        id, thread_id, lane, status, input, created_at_ms,
                        updated_at_ms, scope)
                    VALUES ('legacy-trunk-turn', 'trunk-1', 'CLI', 'QUEUED',
                        'continue', 80, 80, 'TRUNK')
                    """))
                    .hasMessageContaining("LEGACY Turn");

            execute(connection, """
                    INSERT INTO thread_turns(
                        id, thread_id, task_id, lane, status, input,
                        created_at_ms, updated_at_ms, scope)
                    VALUES ('legacy-task-turn', 'trunk-1', 'legacy-task',
                        'CLI', 'QUEUED', 'continue', 80, 80, 'TASK')
                    """);
            execute(connection, """
                    UPDATE thread_turns
                    SET status = 'SUCCEEDED', updated_at_ms = 81,
                        finished_at_ms = 81
                    WHERE id = 'legacy-task-turn'
                    """);

            assertThat(route(connection, "task-1")).containsExactly("V2", "ACTIVE");
            assertThat(route(connection, "legacy-task"))
                    .containsExactly("LEGACY", null);
            assertThat(text(connection, """
                    SELECT status FROM thread_turns WHERE id = 'legacy-task-turn'
                    """)).isEqualTo("SUCCEEDED");

            execute(connection, """
                    UPDATE threads SET title = 'Renamed V2 Trunk'
                    WHERE id = 'trunk-1'
                    """);
            assertThat(text(connection, """
                    SELECT title FROM threads WHERE id = 'trunk-1'
                    """)).isEqualTo("Renamed V2 Trunk");
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE threads SET lifecycle_state = 'IDLE'
                    WHERE id = 'trunk-1'
                    """))
                    .hasMessageContaining("aggregate version must advance once");
        }

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        V2DevelopmentFlowProjection projection =
                new V2DevelopmentFlowProjection(new JdbcTemplate(dataSource));
        Task projected = projection.project(legacyTaskShape("task-1"));
        assertThat(projected.branchName()).isEqualTo("dev/task-1");
        assertThat(projected.prState()).isEqualTo("remote-open");
        assertThat(projected.pushedAt()).isEqualTo(Instant.ofEpochMilli(51));
        assertThat(projected.phase()).isEqualTo(TaskPhase.PUSHED_AWAITING_CI);
        assertThat(projection.stages("task-1"))
                .extracting(stage -> stage.type())
                .containsExactly("DEVELOPMENT_STAGE", "REMOTE_DEVELOPMENT_STAGE");

        migrate(url);
    }

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

        DevelopmentFlowCanaryRoute disabled =
                new DevelopmentFlowCanaryRoute(false, "");
        DevelopmentFlowCanaryRoute allowListed =
                new DevelopmentFlowCanaryRoute(true, " workspace-1, workspace-2 ");
        DevelopmentFlowCanaryRoute allWorkspaces =
                new DevelopmentFlowCanaryRoute(false, "*");
        assertThat(disabled.routesNewTaskToV2("workspace-1")).isFalse();
        assertThat(allowListed.routesNewTaskToV2("workspace-1")).isTrue();
        assertThat(allowListed.routesNewTaskToV2("workspace-3")).isFalse();
        assertThat(allWorkspaces.routesNewTaskToV2("workspace-3")).isTrue();
        assertThat(allWorkspaces.routesNewTaskToV2(null)).isFalse();
        assertThat(allowListed.snapshot().workspaceAllowList())
                .containsExactlyInAnyOrder("workspace-1", "workspace-2");
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
        migrate(url, "246");
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static String[] route(Connection connection, String taskId)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery("""
                SELECT workflow_version, lifecycle_state
                FROM development_flow_task_route WHERE task_id = '%s'
                """.formatted(taskId))) {
            assertThat(rows.next()).isTrue();
            return new String[] {rows.getString(1), rows.getString(2)};
        }
    }

    private static Task legacyTaskShape(String taskId)
    {
        return new Task(
                taskId, "trunk-1", 1, TaskStatus.PENDING,
                "legacy-branch", "/tmp/legacy-worktree", "main", "/tmp/repo",
                null, null, null, null, null, null, null, null,
                0, 0, 0, null, Instant.ofEpochMilli(1), null, null,
                null, null, null);
    }

    private static String text(Connection connection, String sql)
            throws Exception
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void execute(Connection connection, String sql)
            throws Exception
    {
        connection.createStatement().executeUpdate(sql);
    }
}
