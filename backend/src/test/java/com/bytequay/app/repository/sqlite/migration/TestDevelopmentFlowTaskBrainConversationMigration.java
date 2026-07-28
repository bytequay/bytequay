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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowTaskBrainConversationMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void freshSchemaRequiresExactActiveTaskBrainTurnAndTicketAcrossRestart()
            throws Exception
    {
        String url = database("fresh.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedActiveTask(connection);
        }
        migrate(url, "266");
        try (Connection connection = connect(url)) {
            insertConversationTurn(connection, "conversation-1", "operation-1",
                    "fingerprint-1");
            execute(connection, """
                    INSERT INTO task_message(
                        id, turn_id, seq, role, body, created_at_ms)
                    VALUES ('message-1', 'conversation-1', 1, 'USER',
                        'What changed?', 20)
                    """);
            insertConversationTicket(
                    connection, "ticket-1", "conversation-1", "operation-1", 1);

            assertFails(connection, turnSql(
                    "conversation-stale", "operation-stale", "stale-fingerprint"));
            insertConversationTurn(connection, "conversation-bad-ticket",
                    "operation-bad-ticket", "fingerprint-1");
            assertFails(connection, ticketSql(
                    "ticket-bad", "conversation-bad-ticket",
                    "operation-bad-ticket", 0));

            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_turn
                    WHERE purpose = 'TASK_BRAIN_CONVERSATION'
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_attachment
                    WHERE turn_id = 'conversation-1'
                    """)).isZero();
        }

        migrate(url, "266");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT body FROM task_message WHERE id = 'message-1'
                    """)).isEqualTo("What changed?");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void upgradePreservesExistingTypedTurnsAndAddsContinuationLedger()
            throws Exception
    {
        String url = database("upgrade.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedActiveTask(connection);
        }
        migrate(url, "265");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt,
                        task_epoch, trigger_stage_id, trigger_stage_generation,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, delivery_lane, launch_input,
                        requested_at_ms)
                    VALUES ('existing-analysis', 'task-1', 'TASK_LEVEL_ANALYSIS',
                        'REQUESTED', 'existing-operation', 1, 1, 'stage-1', 1,
                        'fingerprint-1', 'base-1', 'base-1', 'CLI', '{}', 19)
                    """);
        }

        migrate(url, "266");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT purpose FROM task_turn WHERE id = 'existing-analysis'
                    """)).isEqualTo("TASK_LEVEL_ANALYSIS");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_master
                    WHERE type = 'table'
                      AND name IN (
                        'task_brain_conversation_result_v266',
                        'task_turn_user_wait_continuation_v266')
                    """)).isEqualTo(2);
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    private static void seedActiveTask(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'gpt-5.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK', 'base-1',
                    'seed', 'build it', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    max_ci_fix_pushes, require_remote_branch_cleanup,
                    created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 1, 2, 0, 'user', 2)
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        execute(connection, """
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES ('task-1', 'assignment-1', 'policy-1', 'DIRECT_USER',
                    'acme/widget', 'acme/widget', 'base-1', 'engine-1',
                    '{"kind":"CLI","agentOrProvider":"codex",\
                      "model":"gpt-5.6","account":null,\
                      "reasoningEffort":"high"}', 3)
                """);
        execute(connection, """
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-1', 'task-1', 'codex', 'gpt-5.6', 'engine-1', 3)
                """);
        execute(connection, """
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-1', 'task-1', 1, 'assignment-1',
                    'provision-operation', 1, 'acme/widget', 'base-1',
                    'dev/task-1', '/tmp/task-1', 'REQUESTED', 4)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES ('provision-ticket', 'provision-operation',
                    'PROVISION_TASK', 'LOCAL_GIT', 'TASK', 'task-1',
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 1, 'base-1', 'REQUESTED', 4)
                """);
        execute(connection, """
                UPDATE provision_task_operation SET status = 'DISPATCHED'
                WHERE id = 'provision-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'local branch exists',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = 'provision-operation',
                    pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = 'provision-ticket'
                """);
        execute(connection, """
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-1',
                    result_head_sha = 'base-1',
                    result_code_fingerprint = 'fingerprint-1', completed_at_ms = 5
                WHERE id = 'provision-1'
                """);
        execute(connection, """
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES ('task-1', 'provision-1', 'acme/widget', 'acme/widget',
                    'dev/task-1', '/tmp/task-1', 'base-1', 'base-1',
                    'fingerprint-1', 5, 5)
                """);
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES ('stage-1', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'LOCAL_REVIEW', 6)
                """);
        execute(connection, """
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'stage-1', 1)
                """);
        execute(connection, """
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        execute(connection, """
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('stage-1', 'task-1', 1, 1)
                """);
    }

    private static void insertConversationTurn(
            Connection connection, String turnId, String operationId,
            String fingerprint)
            throws SQLException
    {
        execute(connection, turnSql(turnId, operationId, fingerprint));
    }

    private static String turnSql(
            String turnId, String operationId, String fingerprint)
    {
        return """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('%1$s', 'task-1', 'TASK_BRAIN_CONVERSATION',
                    'REQUESTED', '%2$s', 1, 1, 'stage-1', 1,
                    '%3$s', 'base-1', 'base-1', 'CLI',
                    '{"schemaVersion":1,"transport":"CLI",\
                      "provider":"codex","credentialAccount":null,\
                      "model":"gpt-5.6","reasoningEffort":"high",\
                      "workingDirectory":"/tmp/task-1",\
                      "systemPrompt":"read only","prompt":"answer",\
                      "toolEndpoint":{"serverName":"bytequay",\
                        "url":"http://127.0.0.1:53123/api/v2/task-turns/%1$s/operations/%2$s/mcp",\
                        "ownerKind":"TASK_TURN","ownerId":"%1$s",\
                        "operationId":"%2$s",\
                        "profile":"TASK_BRAIN_READ_ONLY",\
                        "approvalPromptTool":"mcp__bytequay__approval_prompt"}}',
                    20)
                """.formatted(turnId, operationId, fingerprint);
    }

    private static void insertConversationTicket(
            Connection connection, String ticketId, String turnId,
            String operationId, int exclusiveTask)
            throws SQLException
    {
        execute(connection, ticketSql(
                ticketId, turnId, operationId, exclusiveTask));
    }

    private static String ticketSql(
            String ticketId, String turnId, String operationId,
            int exclusiveTask)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('%1$s', '%3$s', 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', '%2$s', 'TASK_TURN_RESULT', 1, 0, %4$s, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'stage-1', 1, 1,
                    'fingerprint-1', 'base-1', 'base-1', 'REQUESTED', 20)
                """.formatted(ticketId, turnId, operationId, exclusiveTask);
    }

    private String database(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name) + "?foreign_keys=ON";
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        connection.createStatement().executeUpdate(sql);
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> connection.createStatement().executeUpdate(sql))
                .isInstanceOf(SQLException.class);
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet result = connection.createStatement().executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
