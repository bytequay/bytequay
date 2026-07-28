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

class TestDevelopmentFlowUserWaitMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void typedQuestionKeepsItsExactTurnAndCardShapeAcrossRestart()
            throws Exception
    {
        String url = database("questions.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedThreadTurn(connection);
            execute(connection, """
                    INSERT INTO thread_question(
                        id, turn_id, call_id, prompt, context, options_json,
                        allow_free_form, state, created_at_ms)
                    VALUES ('question-1', 'thread-turn-1', 'question-call-1',
                        'Choose a strategy', 'The build is ambiguous',
                        '[{"id":"safe","label":"Safe"}]', 0, 'OPEN', 10)
                    """);
            assertFails(connection, """
                    UPDATE thread_question
                    SET answer = 'poisoned', answer_revision = 1,
                        answer_actor = 'intruder', answered_at_ms = 11
                    WHERE id = 'question-1'
                    """);
            assertFails(connection, """
                    UPDATE thread_question SET options_json = '[]'
                    WHERE id = 'question-1'
                    """);
            execute(connection, """
                    UPDATE thread_question
                    SET state = 'ANSWERED', answer = 'Safe', answer_revision = 1,
                        answer_option_id = 'safe', answer_actor = 'user',
                        answered_at_ms = 20, continuation_state = 'READY'
                    WHERE id = 'question-1' AND state = 'OPEN'
                    """);
            execute(connection, """
                    INSERT INTO threads(
                        id, kind, provider, title, status, model,
                        cost_usd_milli, tokens_in, tokens_out,
                        created_at_ms, updated_at_ms, workspace_id, flow,
                        parallel_slots, turn_version, lifecycle_state)
                    VALUES ('trunk-2', 'CLI_AGENT', 'claude-code', 'Other', 'IDLE',
                        'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1',
                        'build', 2, 'V2', 'ACTIVE')
                    """);
            execute(connection, """
                    INSERT INTO thread_turn(
                        id, trunk_id, purpose, status, operation_id, attempt,
                        delivery_lane, launch_input, requested_at_ms)
                    VALUES ('wrong-successor', 'trunk-2', 'CONVERSATION',
                        'REQUESTED', 'wrong-operation', 1, 'CLI', '{}', 21)
                    """);
            assertFails(connection, """
                    UPDATE thread_question
                    SET continuation_state = 'DISPATCHED',
                        successor_turn_id = 'wrong-successor'
                    WHERE id = 'question-1'
                    """);
            execute(connection, """
                    INSERT INTO thread_turn(
                        id, trunk_id, purpose, status, operation_id, attempt,
                        delivery_lane, launch_input, requested_at_ms)
                    VALUES ('right-successor', 'trunk-1', 'CONVERSATION',
                        'REQUESTED', 'right-operation', 2, 'CLI', '{}', 22)
                    """);
            execute(connection, """
                    UPDATE thread_question
                    SET continuation_state = 'DISPATCHED',
                        successor_turn_id = 'right-successor'
                    WHERE id = 'question-1'
                    """);
            assertThat(text(connection, """
                    SELECT context || ':' || options_json || ':' || allow_free_form
                    FROM thread_question WHERE id = 'question-1'
                    """)).isEqualTo(
                            "The build is ambiguous:[{\"id\":\"safe\",\"label\":\"Safe\"}]:0");
            assertThat(text(connection,
                    "SELECT state FROM thread_question WHERE id = 'question-1'"))
                    .isEqualTo("ANSWERED");
            assertThat(text(connection, """
                    SELECT successor_turn_id FROM thread_question
                    WHERE id = 'question-1'
                    """)).isEqualTo("right-successor");
        }

        migrate(url);
        try (Connection connection = connect(url)) {
            assertThat(text(connection,
                    "SELECT turn_id FROM thread_question WHERE id = 'question-1'"))
                    .isEqualTo("thread-turn-1");
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void permissionAnswerIsTerminalAndFiniteGrantConsumesAtomically()
            throws Exception
    {
        String url = database("permissions.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedThreadTurn(connection);
            execute(connection, """
                    INSERT INTO permission_request(
                        id, call_id, turn_kind, turn_id, operation_id, capability,
                        tool_name, parameters_json, parameters_digest, policy_snapshot,
                        state, requested_at_ms)
                    VALUES ('permission-1', 'tool-call-1', 'THREAD', 'thread-turn-1',
                        'thread-operation-1', 'CODE_WRITE', 'run_shell',
                        '{"command":"make test"}', 'digest-1', '{}', 'OPEN', 10)
                    """);
            assertFails(connection, """
                    UPDATE permission_request
                    SET answer = 'poisoned', answer_revision = 1,
                        answer_actor = 'intruder', answered_at_ms = 11,
                        grant_scope_kind = 'TRUNK', grant_scope_id = 'trunk-1',
                        granted_uses = 1, remaining_uses = 1
                    WHERE id = 'permission-1'
                    """);
            assertFails(connection, """
                    UPDATE permission_request SET parameters_json = '{}'
                    WHERE id = 'permission-1'
                    """);
            execute(connection, """
                    UPDATE permission_request
                    SET state = 'ALLOWED_NEXT', answer = '{"count":2}',
                        answer_revision = 1, answered_at_ms = 20,
                        answer_actor = 'user', grant_scope_kind = 'TRUNK',
                        grant_scope_id = 'trunk-1', granted_uses = 2,
                        remaining_uses = 2, continuation_state = 'READY'
                    WHERE id = 'permission-1' AND state = 'OPEN'
                    """);
            execute(connection, consume(30));
            execute(connection, consume(31));
            assertThat(number(connection, """
                    SELECT remaining_uses FROM permission_request
                    WHERE id = 'permission-1'
                    """)).isZero();
            assertThat(number(connection, """
                    SELECT consumed_uses FROM permission_request
                    WHERE id = 'permission-1'
                    """)).isEqualTo(2);

            assertThat(connection.createStatement().executeUpdate(consume(32))).isZero();
            assertFails(connection, """
                    UPDATE permission_request
                    SET state = 'DENIED', answer = 'late', answer_revision = 2,
                        answered_at_ms = 40, answer_actor = 'other'
                    WHERE id = 'permission-1'
                    """);
            execute(connection, """
                    INSERT INTO permission_answer_attempt(
                        id, permission_id, expected_revision, proposed_state,
                        actor, answer, outcome, attempted_at_ms)
                    VALUES ('late-attempt', 'permission-1', 0, 'DENIED',
                        'other', 'late', 'ALREADY_TERMINAL', 40)
                    """);
            assertThat(text(connection, """
                    SELECT outcome FROM permission_answer_attempt
                    WHERE id = 'late-attempt'
                    """)).isEqualTo("ALREADY_TERMINAL");
        }
    }

    @Test
    void originalV263ConsumptionUpgradesWithStableLegacyCallIdentity()
            throws Exception
    {
        String url = database("permission-upgrade.db");
        migrate(url, "263");
        try (Connection connection = connect(url)) {
            seedThreadTurn(connection);
            execute(connection, """
                    INSERT INTO permission_request(
                        id, call_id, turn_kind, turn_id, operation_id,
                        capability, tool_name, parameters_json,
                        parameters_digest, policy_snapshot, state, answer,
                        answer_revision, requested_at_ms, answered_at_ms,
                        grant_scope_kind, grant_scope_id, granted_uses,
                        remaining_uses, consumed_uses, answer_actor,
                        last_consumed_at_ms, continuation_state)
                    VALUES ('permission-upgrade', 'original-call', 'THREAD',
                        'thread-turn-1', 'thread-operation-1', 'CODE_WRITE',
                        'run_shell', '{"command":"make test"}', 'digest-1',
                        '{}', 'ALLOWED_NEXT', '{"decision":"allow"}', 1,
                        10, 20, 'TRUNK', 'trunk-1', 2, 1, 1, 'user', 21,
                        'READY')
                    """);
            execute(connection, """
                    INSERT INTO permission_grant_consumption(
                        id, permission_id, turn_kind, turn_id, operation_id,
                        parameters_digest, remaining_after, consumed_at_ms)
                    VALUES ('legacy-consumption', 'permission-upgrade',
                        'THREAD', 'thread-turn-1', 'thread-operation-1',
                        'digest-1', 1, 21)
                    """);
        }

        migrate(url, "265");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT call_id FROM permission_grant_consumption
                    WHERE id = 'legacy-consumption'
                    """)).isEqualTo(
                            "__legacy_v263_consumption__:legacy-consumption");
            execute(connection, """
                    INSERT INTO permission_grant_consumption(
                        id, permission_id, turn_kind, turn_id, operation_id,
                        call_id, parameters_digest, remaining_after,
                        consumed_at_ms)
                    VALUES ('exact-consumption', 'permission-upgrade',
                        'THREAD', 'thread-turn-1', 'thread-operation-1',
                        'future-call', 'digest-1', 0, 22)
                    """);
            assertFails(connection, """
                    INSERT INTO permission_grant_consumption(
                        id, permission_id, turn_kind, turn_id, operation_id,
                        call_id, parameters_digest, remaining_after,
                        consumed_at_ms)
                    VALUES ('duplicate-consumption', 'permission-upgrade',
                        'THREAD', 'thread-turn-1', 'thread-operation-1',
                        'future-call', 'digest-1', 0, 23)
                    """);
            execute(connection, """
                    INSERT INTO permission_grant_consumption(
                        id, permission_id, turn_kind, turn_id, operation_id,
                        call_id, parameters_digest, remaining_after,
                        consumed_at_ms)
                    VALUES ('other-call-consumption', 'permission-upgrade',
                        'THREAD', 'thread-turn-1', 'thread-operation-1',
                        'other-call', 'digest-1', 0, 24)
                    """);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM permission_grant_consumption
                    WHERE permission_id = 'permission-upgrade'
                    """)).isEqualTo(3);
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    private String database(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name) + "?foreign_keys=ON";
    }

    private static void migrate(String url)
    {
        Flyway.configure().dataSource(url, "", "").load().migrate();
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static void seedThreadTurn(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT OR IGNORE INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1',
                    'build', 2, 'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms, started_at_ms)
                VALUES ('thread-turn-1', 'trunk-1', 'CONVERSATION', 'RUNNING',
                    'thread-operation-1', 1, 'CLI', '{}', 4, 5)
                """);
    }

    private static String consume(long at)
    {
        return """
                UPDATE permission_request
                SET remaining_uses = CASE
                        WHEN remaining_uses = -1 THEN -1 ELSE remaining_uses - 1 END,
                    consumed_uses = consumed_uses + 1,
                    last_consumed_at_ms = %d
                WHERE id = 'permission-1' AND remaining_uses <> 0
                """.formatted(at);
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        connection.createStatement().execute("PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        connection.createStatement().executeUpdate(sql);
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class);
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
