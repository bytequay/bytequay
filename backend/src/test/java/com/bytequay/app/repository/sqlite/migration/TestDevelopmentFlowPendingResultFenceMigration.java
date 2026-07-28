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
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowPendingResultFenceMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void upgradesPendingRowsWithACompleteImmutableFence()
            throws Exception
    {
        String url = url("pending-upgrade.db");
        migrate(url, "225");
        try (Connection connection = connect(url)) {
            seedTask(connection);
            insertPendingTicketBeforeFenceMigration(connection);
            execute(connection, """
                    INSERT INTO dispatch_delivery_claim(
                        ticket_id, ticket_version, claim_owner, claimed_at_ms,
                        heartbeat_at_ms, expires_at_ms)
                    VALUES ('ticket-1', 0, 'delivery-worker', 11, 11, 40)
                    """);
        }

        migrate(url, "226");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT pending_result_operation_id FROM dispatch_ticket
                    WHERE id = 'ticket-1'
                    """)).isEqualTo("operation-1");
            assertThat(number(connection, """
                    SELECT pending_result_task_epoch FROM dispatch_ticket
                    WHERE id = 'ticket-1'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM dispatch_delivery_claim
                    WHERE ticket_id = 'ticket-1' AND ticket_version = 0
                    """)).isOne();
            execute(connection, """
                    DELETE FROM dispatch_delivery_claim
                    WHERE ticket_id = 'ticket-1' AND claim_owner = 'delivery-worker'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, pending_result_operation_id = 'substituted-operation'
                    WHERE id = 'ticket-1'
                    """);
            completeTicket(connection, "ticket-1", 1);
            assertThat(text(connection,
                    "SELECT status FROM dispatch_ticket WHERE id = 'ticket-1'"))
                    .isEqualTo("SUCCEEDED");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }

        migrate(url, "226");
        migrate(url, "226");
    }

    @Test
    void storesAStaleRawFenceVerbatimAndRequiresAtomicClear()
            throws Exception
    {
        String url = url("pending-shape.db");
        migrate(url, "226");
        try (Connection connection = connect(url)) {
            seedTask(connection);
            insertRequestedTicket(connection, "ticket-1", "operation-1");

            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = '{}', pending_result_evidence = '{}'
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = '{}', pending_result_evidence = '{}',
                        pending_result_operation_id = 'other-operation',
                        pending_result_attempt = 2,
                        pending_result_stage_id = 'stale-stage'
                    WHERE id = 'ticket-1'
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = '{}', pending_result_evidence = '{}',
                        pending_result_task_epoch = 99,
                        pending_result_stage_id = 'stale-stage',
                        pending_result_stage_generation = 9,
                        pending_result_operation_id = 'other-operation',
                        pending_result_attempt = 2,
                        pending_result_expected_code_fingerprint = 'stale-fingerprint',
                        pending_result_expected_head_sha = 'stale-head',
                        pending_result_expected_base_sha = 'stale-base'
                    WHERE id = 'ticket-1'
                    """);

            assertThat(text(connection, """
                    SELECT pending_result_operation_id FROM dispatch_ticket
                    WHERE id = 'ticket-1'
                    """)).isEqualTo("other-operation");
            assertThat(number(connection, """
                    SELECT pending_result_task_epoch FROM dispatch_ticket
                    WHERE id = 'ticket-1'
                    """)).isEqualTo(99);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 2, pending_result_operation_id = 'third-operation'
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 2, pending_result_payload = '{"substituted":true}'
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 2, status = 'RETRY_WAIT',
                        pending_result_outcome = NULL,
                        pending_result_payload = NULL,
                        pending_result_evidence = NULL,
                        pending_result_error = NULL,
                        pending_result_task_epoch = NULL,
                        pending_result_stage_id = NULL,
                        pending_result_stage_generation = NULL,
                        pending_result_operation_id = NULL,
                        pending_result_attempt = NULL,
                        pending_result_expected_code_fingerprint = NULL,
                        pending_result_expected_head_sha = NULL,
                        pending_result_expected_base_sha = NULL
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 2, status = 'SUCCEEDED',
                        pending_result_outcome = NULL,
                        pending_result_payload = NULL,
                        pending_result_evidence = NULL,
                        delivery_acceptance = 'SUPERSEDED',
                        delivery_evidence = '{}', completed_at_ms = 30
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, terminalUpdateSql("ticket-1", 2, "FAILED"));

            completeTicket(connection, "ticket-1", 2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM dispatch_ticket
                    WHERE id = 'ticket-1'
                      AND pending_result_operation_id IS NULL
                      AND pending_result_task_epoch IS NULL
                    """)).isOne();
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 3, status = 'RESULT_PENDING',
                        completed_at_ms = NULL, delivery_acceptance = NULL,
                        delivery_evidence = NULL,
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = '{}', pending_result_evidence = '{}',
                        pending_result_task_epoch = 1,
                        pending_result_operation_id = 'operation-1',
                        pending_result_attempt = 1
                    WHERE id = 'ticket-1'
                    """);
        }
    }

    @Test
    void derivesTerminalStatusFromTheRawOutcome()
            throws Exception
    {
        String url = url("pending-outcomes.db");
        migrate(url, "226");
        try (Connection connection = connect(url)) {
            seedTask(connection);
            insertRequestedTicket(connection, "ticket-failed", "operation-failed");
            insertRequestedTicket(connection, "ticket-unknown", "operation-unknown");
            insertRequestedTicket(connection, "ticket-canceled", "operation-canceled");
            recordPendingResult(connection,
                    "ticket-failed", "operation-failed", "FAILED");
            recordPendingResult(connection,
                    "ticket-unknown", "operation-unknown", "INDETERMINATE");
            recordPendingResult(connection,
                    "ticket-canceled", "operation-canceled", "CANCELED");

            assertFails(connection,
                    terminalUpdateSql("ticket-failed", 2, "SUCCEEDED"));
            assertFails(connection,
                    terminalUpdateSql("ticket-unknown", 2, "CANCELED"));
            assertFails(connection,
                    terminalUpdateSql("ticket-canceled", 2, "FAILED"));

            completeTicket(connection, "ticket-failed", 2, "FAILED");
            completeTicket(connection, "ticket-unknown", 2, "FAILED");
            completeTicket(connection, "ticket-canceled", 2, "CANCELED");
        }
    }

    @Test
    void roundTripsAnUnscopedRawFenceAcrossRestart()
            throws Exception
    {
        String url = url("pending-unscoped.db");
        migrate(url, "226");
        try (Connection connection = connect(url)) {
            seedTask(connection);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        workspace_id, trunk_id, attempt, status, created_at_ms)
                    VALUES ('ticket-trunk', 'operation-trunk', 'THINK', 'AGENT_TURN',
                        'TRUNK', 'trunk-1', 'trunk.turn', 1,
                        'workspace-1', 'trunk-1', 1, 'REQUESTED', 10)
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = '{}', pending_result_evidence = '{}',
                        pending_result_operation_id = 'operation-trunk',
                        pending_result_attempt = 1
                    WHERE id = 'ticket-trunk'
                    """);
        }

        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT pending_result_operation_id FROM dispatch_ticket
                    WHERE id = 'ticket-trunk'
                    """)).isEqualTo("operation-trunk");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM dispatch_ticket
                    WHERE id = 'ticket-trunk'
                      AND pending_result_task_epoch IS NULL
                      AND pending_result_stage_id IS NULL
                      AND pending_result_stage_generation IS NULL
                    """)).isOne();
            completeTicket(connection, "ticket-trunk", 2);
        }
    }

    private String url(String file)
    {
        return "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
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

    private static void seedTask(Connection connection)
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
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
    }

    private static void insertPendingTicketBeforeFenceMigration(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    workspace_id, trunk_id, task_id, task_epoch, attempt,
                    expected_code_fingerprint, expected_head_sha, expected_base_sha,
                    status, pending_result_outcome, pending_result_payload,
                    pending_result_evidence, created_at_ms)
                VALUES ('ticket-1', 'operation-1', 'VALIDATE', 'VALIDATION',
                    'TASK', 'task-1', 'task.validation', 4,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1',
                    'RESULT_PENDING', 'SUCCEEDED', '{}', '{}', 10)
                """);
    }

    private static void insertRequestedTicket(
            Connection connection, String ticketId, String operationId)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    workspace_id, trunk_id, task_id, task_epoch, attempt,
                    expected_code_fingerprint, expected_head_sha, expected_base_sha,
                    status, created_at_ms)
                VALUES ('%s', '%s', 'VALIDATE', 'VALIDATION',
                    'TASK', 'task-1', 'task.validation', 4,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 10)
                """.formatted(ticketId, operationId));
    }

    private static void completeTicket(Connection connection, String ticketId, int version)
            throws SQLException
    {
        completeTicket(connection, ticketId, version, "SUCCEEDED");
    }

    private static void completeTicket(
            Connection connection, String ticketId, int version, String status)
            throws SQLException
    {
        execute(connection, terminalUpdateSql(ticketId, version, status));
    }

    private static String terminalUpdateSql(String ticketId, int version, String status)
    {
        return """
                UPDATE dispatch_ticket
                SET version = %s, status = '%s',
                    pending_result_outcome = NULL,
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = NULL,
                    pending_result_task_epoch = NULL,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = NULL,
                    pending_result_attempt = NULL,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = NULL,
                    pending_result_expected_base_sha = NULL,
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = '{}', completed_at_ms = 30
                WHERE id = '%s'
                """.formatted(version, status, ticketId);
    }

    private static void recordPendingResult(
            Connection connection, String ticketId, String operationId, String outcome)
            throws SQLException
    {
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = '%s',
                    pending_result_payload = '{}', pending_result_evidence = '{}',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = '%s',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = '%s'
                """.formatted(outcome, operationId, ticketId));
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class);
    }
}
