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

class TestV2LocalStageStore
{
    @TempDir
    private Path tempDir;

    @Test
    void changedCodeSubjectCanAdvanceImplementationToValidation()
    {
        SQLiteDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        Flyway.configure().dataSource(dataSource).target("237").load().migrate();
        ResultFence source = new ResultFence(
                1, "local-stage", 1, "implementation-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        seedImplementationRequest(jdbc);

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2StageStore store = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, store, store);
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                source,
                "implementation-request"));

        jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 6, finished_at_ms = 7
                WHERE id = 'implementation-turn'
                """);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, decisions_json, invariants_json,
                    tricky_spots_json, test_map_json, followups_json,
                    created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs, source_code_fingerprint, source_head_sha,
                    source_base_sha)
                VALUES (
                    'report-1', 'task-1', 'implemented', '[]', '[]', '[]',
                    '[]', '[]', 7, 'V2', 'local-stage', 1, 1,
                    'implementation-turn', 1,
                    'fingerprint-new', 'head-new', 'head-old',
                    'implement the approved plan', '', 'changed one file',
                    '', '', '', '[]',
                    'fingerprint-old', 'head-old', 'head-old')
                """);

        StageManager.State accepted = commands.execute("task-1", () ->
                local.acceptImplementationResultInCommand(
                        new StageManager.ResultCommand(
                                "accept-implementation", "dispatcher", "task-1", source),
                        "report-1")).state();

        assertThat(accepted.checkpoint().name()).isEqualTo("VALIDATING");
        assertThat(accepted.pendingResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT head_sha FROM dev_report WHERE id = 'report-1'",
                String.class)).isEqualTo("head-new");
        assertThat(jdbc.queryForObject(
                "SELECT source_head_sha FROM dev_report WHERE id = 'report-1'",
                String.class)).isEqualTo("head-old");
    }

    private SQLiteDataSource database()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("local-store.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void seedLocalOwner(JdbcTemplate jdbc)
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
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK', 'head-old',
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
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES ('task-1', 'assignment-1', 'policy-1', 'DIRECT_USER',
                    'acme/widget', 'acme/widget', 'head-old', 'engine', 'model', 2)
                """);
        seedProvisionedCode(jdbc);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES ('local-stage', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 3)
                """);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'local-stage', 1)
                """);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        jdbc.update("""
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('local-stage', 'task-1', 1, 1)
                """);
    }

    private static void seedProvisionedCode(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-1', 'task-1', 1, 'assignment-1',
                    'provision-operation', 1, 'acme/widget', 'head-old',
                    'dev/task-1', '/tmp/task-1', 'REQUESTED', 2)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES ('provision-ticket', 'provision-operation',
                    'PROVISION_TASK', 'LOCAL_GIT', 'TASK', 'task-1',
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 1, 'head-old', 'REQUESTED', 2)
                """);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'DISPATCHED' WHERE id = 'provision-1'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'created',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = 'provision-operation',
                    pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'head-old'
                WHERE id = 'provision-ticket'
                """);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'head-old',
                    result_head_sha = 'head-old',
                    result_code_fingerprint = 'fingerprint-old',
                    completed_at_ms = 3
                WHERE id = 'provision-1'
                """);
        jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES ('task-1', 'provision-1', 'acme/widget', 'acme/widget',
                    'dev/task-1', '/tmp/task-1', 'head-old', 'head-old',
                    'fingerprint-old', 3, 3)
                """);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = 'provision-ticket'");
    }

    private static void seedImplementationRequest(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('implementation-turn', 'local-stage', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED',
                    'implementation-operation', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'API', '{}', 4)
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES ('implementation-request', 'request-implementation',
                    'implementation-turn', 'task-1', 'local-stage', 1, 1,
                    'IMPLEMENTATION', 'IMMEDIATE',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'runtime', 4)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('implementation-ticket', 'implementation-operation',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'implementation-turn', 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 4)
                """);
    }
}
