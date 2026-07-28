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

class TestDevelopmentFlowDeliveryClaimMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void deliveryClaimIsExactDurableAndCapacityFree()
            throws Exception
    {
        String url = migrated("delivery-claim.db");
        try (Connection connection = connect(url)) {
            seedResultPendingTicket(connection);

            execute(connection, """
                    INSERT INTO dispatch_delivery_claim(
                        ticket_id, ticket_version, claim_owner, claimed_at_ms,
                        heartbeat_at_ms, expires_at_ms)
                    VALUES ('ticket-1', 0, 'delivery-worker-1', 20, 20, 40)
                    """);

            assertThat(number(connection, """
                    SELECT COUNT(*) FROM capacity_lease
                    WHERE ticket_id = 'ticket-1' AND released_at_ms IS NULL
                    """)).isZero();
            assertFails(connection, """
                    INSERT INTO dispatch_delivery_claim(
                        ticket_id, ticket_version, claim_owner, claimed_at_ms,
                        heartbeat_at_ms, expires_at_ms)
                    VALUES ('ticket-1', 0, 'delivery-worker-2', 21, 21, 41)
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'SUCCEEDED',
                        pending_result_outcome = NULL,
                        pending_result_payload = NULL,
                        pending_result_evidence = NULL,
                        pending_result_error = NULL,
                        delivery_acceptance = 'ACCEPTED',
                        delivery_evidence = '{}', completed_at_ms = 30
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_delivery_claim
                    SET heartbeat_at_ms = 19, expires_at_ms = 50
                    WHERE ticket_id = 'ticket-1'
                    """);

            connection.setAutoCommit(false);
            execute(connection, """
                    DELETE FROM dispatch_delivery_claim
                    WHERE ticket_id = 'ticket-1'
                      AND claim_owner = 'delivery-worker-1'
                      AND ticket_version = 0
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'SUCCEEDED',
                        pending_result_outcome = NULL,
                        pending_result_payload = NULL,
                        pending_result_evidence = NULL,
                        pending_result_error = NULL,
                        delivery_acceptance = 'ACCEPTED',
                        delivery_evidence = '{}', completed_at_ms = 30
                    WHERE id = 'ticket-1' AND version = 0
                    """);
            connection.commit();

            assertThat(text(connection,
                    "SELECT status FROM dispatch_ticket WHERE id = 'ticket-1'"))
                    .isEqualTo("SUCCEEDED");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM dispatch_delivery_claim")).isZero();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }

        migrate(url);
        migrate(url);
        try (Connection connection = connect(url)) {
            assertThat(text(connection,
                    "SELECT status FROM dispatch_ticket WHERE id = 'ticket-1'"))
                    .isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void staleTicketVersionCannotBeClaimed()
            throws Exception
    {
        String url = migrated("stale-delivery-claim.db");
        try (Connection connection = connect(url)) {
            seedResultPendingTicket(connection);
            assertFails(connection, """
                    INSERT INTO dispatch_delivery_claim(
                        ticket_id, ticket_version, claim_owner, claimed_at_ms,
                        heartbeat_at_ms, expires_at_ms)
                    VALUES ('ticket-1', 1, 'delivery-worker', 20, 20, 40)
                    """);
        }
    }

    private String migrated(String file)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
        migrate(url);
        return url;
    }

    private static void migrate(String url)
    {
        Flyway.configure().dataSource(url, "", "").target("224").load().migrate();
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void seedResultPendingTicket(Connection connection)
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
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, workspace_id, trunk_id, task_id, task_epoch,
                    attempt, status, pending_result_outcome,
                    pending_result_payload, pending_result_evidence, created_at_ms)
                VALUES ('ticket-1', 'operation-1', 'PROVISION_TASK', 'LOCAL_GIT',
                    'TASK', 'task-1', 'task.provisioned', 16,
                    1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    1, 'RESULT_PENDING', 'SUCCEEDED', '{}', '{}', 10)
                """);
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
