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

        DevelopmentFlowCanaryRoute route = new DevelopmentFlowCanaryRoute();
        assertThat(route.routesNewTaskToV2("workspace-1")).isTrue();
        assertThat(route.routesNewTaskToV2("workspace-3")).isTrue();
        assertThat(route.routesNewTaskToV2(" ")).isFalse();
        assertThat(route.routesNewTaskToV2(null)).isFalse();
        assertThat(route.snapshot().v2Only()).isTrue();
    }

    @Test
    void reportsEveryLegacyRuntimeOwnerWithoutCountingV2ReviewArtifacts()
            throws Exception
    {
        String url = url("legacy-runtime-drain.db");
        migrate(url, "228");
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(connection);
            DevelopmentFlowRemoteProtocolFixture.seedLocalDevelopmentTask(connection, 1);
        }
        migrate(url);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DevelopmentFlowInvariantAuditor auditor =
                new DevelopmentFlowInvariantAuditor(jdbc);

        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms)
                VALUES ('legacy-task', 'trunk-1', 2, 'COMPLETED',
                    'IMPLEMENTING', 1)
                """);
        jdbc.update("""
                INSERT INTO agent_run(
                    id, task_id, kind, status, started_at_ms)
                VALUES
                    ('queued-run', 'legacy-task', 'ci_fix', 'queued', 2),
                    ('running-run', 'legacy-task', 'ci_fix', 'running', 2),
                    ('paused-run', 'legacy-task', 'ci_fix', 'paused', 2),
                    ('gated-run', 'legacy-task', 'review_round',
                        'awaiting_gate', 2),
                    ('detached-review-run', NULL, 'panel_review', 'queued', 2),
                    ('finished-run', 'legacy-task', 'ci_fix', 'succeeded', 2)
                """);
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms, workspace_id,
                    owner_thread_id, owner_task_id)
                VALUES ('v2-review', 'acme/widget', 'pr-1', 'base-1', 'head-1',
                    'ACTIVE', 2, 2, 'workspace-1', 'trunk-1', 'task-1')
                """);
        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, review_round_id, status, started_at_ms)
                VALUES
                    ('v2-review-run', 'panel_review', 'v2-review-round',
                        'running', 2),
                    ('v2-verifier-run', 'panel_review', 'v2-review-round',
                        'queued', 2)
                """);
        jdbc.update("""
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope, start_commit,
                    status, budget_json, cost_cents, created_at_ms)
                VALUES ('v2-review-round', 'v2-review', 'v2-review-run',
                    'manual', 'full', 'head-1', 'RUNNING', '{}', 0, 2)
                """);
        jdbc.update("""
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, claim_key,
                    code_fingerprint, cancel_requested_at_ms, superseded_at_ms)
                VALUES
                    ('legacy-task', 2, NULL, NULL, 'validation-live',
                        'fingerprint-1', NULL, NULL),
                    ('legacy-task', 2, NULL, NULL, 'validation-canceling',
                        'fingerprint-2', 3, NULL),
                    ('legacy-task', 2, 3, 1, 'validation-finished',
                        'fingerprint-3', NULL, NULL),
                    ('legacy-task', 2, NULL, NULL, 'validation-superseded',
                        'fingerprint-4', NULL, 3)
                """);

        DevelopmentFlowInvariantAuditor.DrainStatus live =
                auditor.legacyDrainStatus();
        assertThat(live.drained()).isFalse();
        assertThat(live.nonterminalTasks()).isZero();
        assertThat(live.liveRuns())
                .as("V2-owned detached review runs are not legacy")
                .isEqualTo(5);
        assertThat(live.liveValidationClaims()).isEqualTo(2);

        jdbc.update("""
                UPDATE agent_run
                SET status = 'succeeded', finished_at_ms = 4
                WHERE id IN ('queued-run','running-run','paused-run',
                    'gated-run','detached-review-run')
                """);
        jdbc.update("""
                UPDATE validation_pass
                SET ended_at_ms = 4, passed = 1
                WHERE claim_key = 'validation-live'
                """);
        jdbc.update("""
                UPDATE validation_pass
                SET superseded_at_ms = 4
                WHERE claim_key = 'validation-canceling'
                """);

        DevelopmentFlowInvariantAuditor.DrainStatus drained =
                auditor.legacyDrainStatus();
        assertThat(drained.liveRuns()).isZero();
        assertThat(drained.liveValidationClaims()).isZero();
        assertThat(drained.drained()).isTrue();
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
