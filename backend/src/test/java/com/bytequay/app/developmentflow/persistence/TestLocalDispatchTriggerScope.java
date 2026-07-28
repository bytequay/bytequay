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
package com.bytequay.app.developmentflow.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLocalDispatchTriggerScope
{
    @TempDir
    private Path tempDir;

    @Test
    void localProtocolDoesNotClaimAnotherStagesSharedTurnCallback()
    {
        JdbcTemplate jdbc = database("plan-trigger-scope.db");
        seedOwner(jdbc, "PLAN", "plan-stage");
        migrateRuntime(jdbc);

        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('plan-turn', 'plan-stage', 1, 'PLAN_DRAFT', 'QUEUED',
                    'plan-operation', 1, 1, 'code', 'head', 'base',
                    'API', '{}', 4)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('plan-ticket', 'plan-operation', 'EXECUTE_STAGE_TURN',
                    'AGENT_TURN', 'STAGE_TURN', 'plan-turn',
                    'STAGE_TURN_RESULT', 2, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 'plan-stage', 1, 1,
                    'code', 'head', 'base', 'REQUESTED', 4)
                """);

        assertThat(jdbc.queryForObject(
                "SELECT callback_route FROM dispatch_ticket WHERE id = 'plan-ticket'",
                String.class)).isEqualTo("STAGE_TURN_RESULT");
    }

    @Test
    void localTurnTicketCannotBypassWithAStageOwnerOrMissingRequest()
    {
        JdbcTemplate jdbc = database("local-turn-trigger-scope.db");
        seedOwner(jdbc, "LOCAL_DEVELOPMENT", "local-stage");
        migrateRuntime(jdbc);
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('local-turn', 'local-stage', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED', 'local-operation', 1, 1,
                    'code', 'head', 'base', 'API', '{}', 4)
                """);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('local-ticket', 'local-operation', 'EXECUTE_STAGE_TURN',
                    'AGENT_TURN', 'STAGE', 'local-stage',
                    'STAGE_TURN_RESULT', 2, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 'local-stage', 1, 1,
                    'code', 'head', 'base', 'REQUESTED', 4)
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Local StageTurn ticket is not exact");
    }

    @Test
    void localValidationTicketCannotBypassWithATaskOwnerOrMissingOperation()
    {
        JdbcTemplate jdbc = database("local-validation-trigger-scope.db");
        seedOwner(jdbc, "LOCAL_DEVELOPMENT", "local-stage");
        migrateRuntime(jdbc);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-ticket', 'missing-validation',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION',
                    'TASK', 'task-1', 'STAGE_VALIDATION_RESULT', 4,
                    1, 0, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage', 1, 1, 'code', 'head', 'base',
                    'REQUESTED', 4)
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Local Validation ticket is not exact");
    }

    @Test
    void isolatedReceiptsRejectNullIdentityAndCascadeWithTheirOwner()
    {
        JdbcTemplate jdbc = database("local-receipt-schema.db");
        seedOwner(jdbc, "LOCAL_DEVELOPMENT", "local-stage");
        migrateRuntime(jdbc);
        jdbc.execute("DROP TRIGGER local_stage_command_receipt_insert");
        jdbc.execute("DROP TRIGGER task_brain_request_receipt_insert");

        assertThatThrownBy(() -> insertLocalReceipt(jdbc, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertBrainReceipt(jdbc, null))
                .isInstanceOf(DataAccessException.class);

        insertLocalReceipt(jdbc, "local-receipt-command");
        insertBrainReceipt(jdbc, "runtime");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_stage_command_receipt",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_brain_request_receipt",
                Integer.class)).isOne();

        jdbc.update("DELETE FROM tasks WHERE id = 'task-1'");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_stage_command_receipt",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_brain_request_receipt",
                Integer.class)).isZero();
    }

    private static void insertLocalReceipt(JdbcTemplate jdbc, String commandId)
    {
        jdbc.update("""
                INSERT INTO local_stage_command_receipt(
                    id, stage_id, task_id, command_id, cause, actor, disposition,
                    expected_task_epoch, expected_stage_generation,
                    expected_stage_version, source_checkpoint, proof_id,
                    returned_kind, returned_generation, returned_version,
                    returned_checkpoint, recorded_at_ms)
                VALUES ('local-receipt', 'local-stage', 'task-1', ?,
                    'BEGIN_LOCAL_VALIDATION', 'runtime', 'APPLIED',
                    1, 1, 0, 'IMPLEMENTING', 'report-1',
                    'LOCAL_DEVELOPMENT', 1, 1, 'VALIDATING', 5)
                """, commandId);
    }

    private static void insertBrainReceipt(JdbcTemplate jdbc, String actor)
    {
        jdbc.update("""
                INSERT INTO task_brain_request_receipt(
                    id, task_id, command_id, cause, actor, disposition,
                    expected_task_epoch, expected_task_version,
                    subject_task_epoch, subject_stage_id,
                    subject_stage_generation, subject_operation_id,
                    subject_attempt, proof_id, returned_trunk_id,
                    returned_lifecycle, returned_epoch, returned_version,
                    returned_current_stage_id, returned_pending_task_epoch,
                    returned_pending_stage_id,
                    returned_pending_stage_generation,
                    returned_pending_operation_id, returned_pending_attempt,
                    recorded_at_ms)
                VALUES ('brain-receipt', 'task-1', 'brain-command',
                    'REQUEST_BRAIN_REVIEW', ?, 'APPLIED', 1, 0,
                    1, 'local-stage', 1, 'brain-operation', 1, 'episode-1',
                    'trunk-1', 'ACTIVE', 1, 1, 'local-stage',
                    1, 'local-stage', 1, 'brain-operation', 1, 5)
                """, actor);
    }

    private JdbcTemplate database(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name) + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return new JdbcTemplate(dataSource);
    }

    private static void migrateRuntime(JdbcTemplate jdbc)
    {
        Flyway.configure().dataSource(jdbc.getDataSource())
                .target("236").load().migrate();
    }

    private static void seedOwner(JdbcTemplate jdbc, String kind, String stageId)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK', 'base',
                    'seed', 'build', 'user', 2)
                """);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'user', 2)
                """);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 1, 0, 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES (?, 'task-1', ?, 1, 0, ?, 3)
                """, stageId, kind,
                kind.equals("PLAN") ? "DRAFTING" : "IMPLEMENTING");
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', ?, 1)
                """, stageId);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        if (kind.equals("PLAN")) {
            jdbc.update("""
                    INSERT INTO plan_stage(
                        stage_id, task_id, generation, opened_for_epoch)
                    VALUES (?, 'task-1', 1, 1)
                    """, stageId);
        }
        else {
            jdbc.update("""
                    INSERT INTO local_development_stage(
                        stage_id, task_id, generation, opened_for_epoch)
                    VALUES (?, 'task-1', 1, 1)
                    """, stageId);
        }
    }
}
