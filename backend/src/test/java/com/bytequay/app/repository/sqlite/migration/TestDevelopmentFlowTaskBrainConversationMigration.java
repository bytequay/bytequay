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

import com.bytequay.app.developmentflow.task.SqliteTaskBrainConversationStore;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.bytequay.app.testing.V2TaskSeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestDevelopmentFlowTaskBrainConversationMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void freshSchemaRequiresExactActiveTaskBrainTurnAndTicketAcrossRestart()
            throws Exception
    {
        String url = database("fresh.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedActiveTask(connection);
        }
        migrate(url);
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

        migrate(url);
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT body FROM task_message WHERE id = 'message-1'
                    """)).isEqualTo("What changed?");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void cliContinuityUsesExactWorktreeAndInsertionOrder()
            throws Exception
    {
        String url = database("cli-continuity.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedActiveTask(connection);
        }
        migrate(url);
        try (Connection connection = connect(url)) {
            completeConversation(
                    connection, "turn-z", "operation-z", "ticket-z",
                    "execution-z", "session-z", "older", 30);
            completeConversation(
                    connection, "turn-a", "operation-a", "ticket-a",
                    "execution-a", "session-a", "newer", 20);
            execute(connection, """
                    INSERT INTO task_attachment(
                        id, turn_id, kind, content_ref, media_type, digest,
                        created_at_ms)
                    VALUES ('attachment-z', 'turn-z', 'IMAGE', '/tmp/z.png',
                            'image/png', '%1$s', 100),
                           ('attachment-a', 'turn-a', 'IMAGE', '/tmp/a.png',
                            'image/png', '%2$s', 10)
                    """.formatted("c".repeat(64), "d".repeat(64)));
        }
        DataSource source = SqliteTestPools.open(url);
        SqliteTaskBrainConversationStore store =
                new SqliteTaskBrainConversationStore(new JdbcTemplate(source));

        assertThat(store.latestSuccessfulCliSession(
                "task-1", 1, "stage-1", 1L,
                "fingerprint-1", "base-1", "base-1",
                "openai", "review-model", "/tmp/task-1"))
                .get()
                .satisfies(session -> {
                    assertThat(session.providerSessionId()).isEqualTo("session-a");
                    assertThat(session.cumulativeInputTokens()).isEqualTo(100);
                    assertThat(session.cumulativeOutputTokens()).isEqualTo(40);
                });
        assertThat(store.latestSuccessfulCliSession(
                "task-1", 1, "stage-1", 1L,
                "fingerprint-1", "base-1", "base-1",
                "openai", "review-model", "/tmp/other"))
                .isEmpty();
        assertThat(store.latestSuccessfulCliSession(
                "task-1", 1, "stage-1", 2L,
                "fingerprint-1", "base-1", "base-1",
                "openai", "review-model", "/tmp/task-1"))
                .isEmpty();
        assertThat(store.conversation("task-1"))
                .filteredOn(message -> message.role().equals("USER"))
                .extracting(SqliteTaskBrainConversationStore.Message::body)
                .containsExactly("older", "newer");
        assertThat(store.conversationAttachments("task-1"))
                .extracting(
                        SqliteTaskBrainConversationStore.Attachment::contentRef)
                .containsExactly("/tmp/z.png", "/tmp/a.png");

        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO task_question(
                        id, turn_id, call_id, prompt, state, answer,
                        answer_revision, created_at_ms, answered_at_ms,
                        answer_free_form, answer_actor, continuation_state)
                    VALUES ('question-a', 'turn-a', 'call-a', 'Continue?',
                        'ANSWERED', 'yes', 1, 22, 23, 'yes', 'user', 'READY')
                    """);
            execute(connection, """
                    INSERT INTO typed_user_wait_result(
                        operation_id, owner_kind, turn_id, wait_kind, wait_id,
                        payload_digest, result_evidence, accepted_at_ms)
                    VALUES ('operation-a', 'TASK_TURN', 'turn-a', 'QUESTION',
                        'question-a', '%s', 'waiting for user', 22)
                    """.formatted("b".repeat(64)));
        }
        assertThat(store.findContinuationContext(
                        "turn-a", "operation-a", "QUESTION", "question-a"))
                .get()
                .extracting(
                        SqliteTaskBrainConversationStore.ContinuationContext::providerSessionId)
                .isEqualTo("session-a");

        try (Connection connection = connect(url)) {
            completeConversation(
                    connection, "turn-b", "operation-b", "ticket-b",
                    "execution-b", "session-b", "latest", 40);
        }
        assertThat(store.findContinuationContext(
                        "turn-a", "operation-a", "QUESTION", "question-a"))
                .get()
                .extracting(
                        SqliteTaskBrainConversationStore.ContinuationContext::providerSessionId)
                .isNull();
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
        V2TaskSeed.prepareWorkspaces(connection);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    max_ci_fix_pushes, require_remote_branch_cleanup,
                    created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 1, 2, 0, 'user', 2)
                """);
        JdbcTemplate jdbc = new JdbcTemplate(
                new SingleConnectionDataSource(connection, true));
        V2TaskSeed.insertAuthorized(jdbc, "assignment-1",
                "{\"kind\":\"CLI\",\"agentOrProvider\":\"openai\","
                        + "\"model\":\"review-model\",\"account\":null,"
                        + "\"reasoningEffort\":null}",
                seed -> seed.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK', 'base-1',
                    'seed', 'build it', 'user', 2,
                    'authorization-assignment-1')
                """));
        V2TaskSeed.insertCreated(jdbc, "task-1", seed -> seed.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id, creation_receipt_id, name, task_type,
                    opening_prompt, origin)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1',
                    'creation-receipt-task-1', 'Test task assignment-1',
                    'DEVELOP', 'build it', 'user')
                """));
        V2TaskSeed.completeProvisioning(
                jdbc, "task-1", "base-1", "base-1", "fingerprint-1",
                "local branch exists", 5);
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
                      "provider":"openai","credentialAccount":null,\
                      "model":"review-model","reasoningEffort":null,\
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

    private static void completeConversation(
            Connection connection,
            String turnId,
            String operationId,
            String ticketId,
            String executionId,
            String sessionId,
            String body,
            long messageTime)
            throws SQLException
    {
        insertConversationTurn(connection, turnId, operationId, "fingerprint-1");
        execute(connection, """
                INSERT INTO task_message(
                    id, turn_id, seq, role, body, created_at_ms)
                VALUES ('user-%1$s', '%1$s', 1, 'USER', '%2$s', %3$d),
                       ('assistant-%1$s', '%1$s', 2, 'ASSISTANT',
                        'answer to %2$s', %3$d)
                """.formatted(turnId, body, messageTime));
        insertConversationTicket(connection, ticketId, turnId, operationId, 1);
        execute(connection, """
                UPDATE task_turn
                SET status = 'SUCCEEDED', started_at_ms = 21, finished_at_ms = 22
                WHERE id = '%1$s'
                """.formatted(turnId));
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'SUCCEEDED',
                    completed_at_ms = 22, delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'accepted'
                WHERE id = '%1$s'
                """.formatted(ticketId));
        execute(connection, """
                INSERT INTO task_brain_conversation_result_v266(
                    task_turn_id, operation_id, raw_outcome, raw_result_digest,
                    acceptance, terminal_status, assistant_message_id,
                    evidence, recorded_at_ms)
                VALUES ('%1$s', '%2$s', 'SUCCEEDED', '%3$s', 'ACCEPTED',
                    'SUCCEEDED', 'assistant-%1$s', 'accepted', 22);
                """.formatted(turnId, operationId, "a".repeat(64)));
        execute(connection, """
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms,
                    raw_result)
                VALUES ('%1$s', '%2$s', 1, 'openai', '%3$s',
                    'SUCCEEDED', 21, 22,
                    json_object('payloadJson', json_object(
                        'providerCumulativeInputTokens', 100,
                        'providerCumulativeOutputTokens', 40)))
                """.formatted(executionId, ticketId, sessionId));
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

    private static void migrate(String url)
    {
        MigratedSqliteDatabase.migrate(url);
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
