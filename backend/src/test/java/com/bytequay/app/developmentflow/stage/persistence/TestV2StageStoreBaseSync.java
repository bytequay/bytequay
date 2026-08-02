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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestV2StageStoreBaseSync
{
    @TempDir
    private Path tempDir;

    @Test
    void dedicatedBaseSyncReceiptReplaysAndProjectsThePendingTurn()
    {
        SQLiteDataSource dataSource = dataSource("base-sync-store.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createFocusedSchema(jdbc);
        seedReviewOwnerAndTurn(jdbc);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2StageStore firstStore = new V2StageStore(jdbc);
        LocalDevelopmentStageManager first =
                new LocalDevelopmentStageManager(commands, firstStore);
        StageManager.Command command = command();
        ResultFence fence = fence();

        CommandResult<StageManager.State> applied = commands.execute(
                "task-1", () -> first.startPublishBaseSyncInCommand(
                        command, fence, "episode-1"));

        assertThat(applied.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_command_receipt", Integer.class))
                .isZero();
        assertThat(jdbc.queryForMap("""
                SELECT cause, episode_id, stage_turn_request_id,
                       expected_stage_version, returned_stage_version,
                       operation_id, target_base_sha
                FROM local_publish_base_sync_start_receipt
                """))
                .containsEntry("cause", "START_LOCAL_BASE_SYNC")
                .containsEntry("episode_id", "episode-1")
                .containsEntry("stage_turn_request_id", "request-1")
                .containsEntry("expected_stage_version", 7)
                .containsEntry("returned_stage_version", 8)
                .containsEntry("operation_id", "base-sync-operation")
                .containsEntry("target_base_sha", "target-base");

        V2StageStore restartedStore = new V2StageStore(jdbc);
        StageManager.State projected = restartedStore.findOwner(
                "task-1", "local-stage").orElseThrow().stage();
        assertThat(projected).isEqualTo(applied.state());
        LocalDevelopmentStageManager restarted =
                new LocalDevelopmentStageManager(commands, restartedStore);
        CommandResult<StageManager.State> replay = commands.execute(
                "task-1", () -> restarted.startPublishBaseSyncInCommand(
                        command, fence, "episode-1"));
        assertThat(replay.disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(replay.state()).isEqualTo(applied.state());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM local_publish_base_sync_start_receipt
                """, Integer.class)).isOne();
    }

    @Test
    void preV315SchemaDoesNotProbeTheAbsentBaseSyncReceiptTable()
    {
        SQLiteDataSource dataSource = dataSource("before-base-sync.db");
        Flyway.configure()
                .dataSource(dataSource)
                .target("314")
                .load()
                .migrate();

        V2StageStore store = new V2StageStore(new JdbcTemplate(dataSource));

        assertThat(store.findCommandResult(
                "missing-task", "missing-stage", "missing-command")).isEmpty();
    }

    private SQLiteDataSource dataSource(String name)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000");
        return dataSource;
    }

    private static StageManager.Command command()
    {
        return new StageManager.Command(
                "start-base-sync", "runtime", "task-1", 2,
                "local-stage", 3, 7);
    }

    private static ResultFence fence()
    {
        return new ResultFence(
                2, "local-stage", 3, "base-sync-operation", 1,
                "rebased-fingerprint", "rebased-head", "target-base");
    }

    private static void createFocusedSchema(JdbcTemplate jdbc)
    {
        jdbc.execute("""
                CREATE TABLE tasks (
                    id TEXT PRIMARY KEY,
                    workflow_version TEXT NOT NULL,
                    lifecycle_state TEXT NOT NULL,
                    epoch INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE stage (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    version INTEGER NOT NULL,
                    checkpoint TEXT NOT NULL,
                    completed_at_ms INTEGER,
                    end_reason TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE task_current_stage (
                    task_id TEXT PRIMARY KEY,
                    stage_id TEXT NOT NULL,
                    stage_generation INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE stage_transition (
                    id TEXT PRIMARY KEY,
                    stage_id TEXT NOT NULL,
                    command_id TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    from_checkpoint TEXT,
                    to_checkpoint TEXT NOT NULL,
                    stage_version INTEGER NOT NULL,
                    cause TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    occurred_at_ms INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE stage_command_receipt (
                    task_id TEXT,
                    stage_id TEXT,
                    command_id TEXT,
                    disposition TEXT,
                    returned_version INTEGER)
                """);
        createEmptyLegacyReceiptTables(jdbc);
        jdbc.execute("""
                CREATE TABLE stage_turn (
                    id TEXT PRIMARY KEY,
                    operation_id TEXT NOT NULL,
                    attempt INTEGER NOT NULL,
                    expected_code_fingerprint TEXT NOT NULL,
                    expected_head_sha TEXT NOT NULL,
                    expected_base_sha TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE local_stage_turn_request (
                    id TEXT PRIMARY KEY,
                    command_id TEXT NOT NULL,
                    stage_turn_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    local_development_stage_id TEXT NOT NULL,
                    task_epoch INTEGER NOT NULL,
                    stage_generation INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    base_sync_episode_id TEXT,
                    target_base_sha TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE local_publish_base_sync_start_receipt (
                    id TEXT PRIMARY KEY,
                    cause TEXT NOT NULL,
                    episode_id TEXT NOT NULL,
                    stage_turn_request_id TEXT NOT NULL,
                    command_id TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    local_development_stage_id TEXT NOT NULL,
                    task_epoch INTEGER NOT NULL,
                    stage_generation INTEGER NOT NULL,
                    expected_stage_version INTEGER NOT NULL,
                    returned_stage_version INTEGER NOT NULL,
                    operation_id TEXT NOT NULL,
                    semantic_attempt INTEGER NOT NULL,
                    expected_code_fingerprint TEXT NOT NULL,
                    expected_head_sha TEXT NOT NULL,
                    expected_base_sha TEXT NOT NULL,
                    target_base_sha TEXT NOT NULL,
                    recorded_at_ms INTEGER NOT NULL)
                """);
    }

    private static void createEmptyLegacyReceiptTables(JdbcTemplate jdbc)
    {
        for (String table : new String[] {
                "stage_initial_result_request",
                "stage_plan_edit_review_request",
                "stage_plan_review_retry_request",
                "stage_plan_terminal_result"}) {
            jdbc.execute("""
                    CREATE TABLE %s (
                        task_id TEXT,
                        stage_id TEXT,
                        command_id TEXT,
                        returned_stage_version INTEGER)
                    """.formatted(table));
        }
    }

    private static void seedReviewOwnerAndTurn(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO tasks(id, workflow_version, lifecycle_state, epoch)
                VALUES ('task-1', 'V2', 'ACTIVE', 2)
                """);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint)
                VALUES ('local-stage', 'task-1', 'LOCAL_DEVELOPMENT', 3, 7,
                    'LOCAL_REVIEW')
                """);
        jdbc.update("""
                INSERT INTO task_current_stage(
                    task_id, stage_id, stage_generation)
                VALUES ('task-1', 'local-stage', 3)
                """);
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, operation_id, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha)
                VALUES ('turn-1', 'base-sync-operation', 1,
                    'rebased-fingerprint', 'rebased-head', 'target-base')
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, base_sync_episode_id, target_base_sha)
                VALUES ('request-1', 'start-base-sync', 'turn-1', 'task-1',
                    'local-stage', 2, 3, 'BASE_SYNC', 'episode-1', 'target-base')
                """);
    }
}
