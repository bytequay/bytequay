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

import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Step;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepResult;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepStatus;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCleanupProviderSessionReconciliation
{
    @TempDir
    private Path tempDir;

    @Test
    void persistsFailureEvidenceAndRequiresAgentTurnSessionsToFinish()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("cleanup-provider.db");
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(connection);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, workspace_id, trunk_id, task_id, task_epoch,
                        attempt, status, delivery_acceptance, delivery_evidence,
                        created_at_ms, completed_at_ms)
                    VALUES ('agent-ticket', 'agent-operation', 'TEST_AGENT_TURN',
                        'AGENT_TURN', 'TASK', 'task-1', 'TEST_AGENT_RESULT', 1,
                        1, 'workspace-1', 'trunk-1', 'task-1', 1, 1, 'FAILED',
                        'REJECTED', 'provider result was not finalized', 60, 61)
                    """);
            DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                    INSERT INTO agent_execution(
                        id, ticket_id, infrastructure_attempt, status, started_at_ms)
                    VALUES ('live-agent-execution', 'agent-ticket', 1,
                        'RUNNING', 60)
                    """);
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .prepareMergedCleanup(connection);
        }

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqliteCleanupOperationStore store = new SqliteCleanupOperationStore(
                jdbc, new DataSourceTransactionManager(dataSource));
        succeed(store, "cleanup-step-1", Instant.ofEpochMilli(101));
        succeed(store, "cleanup-step-2", Instant.ofEpochMilli(111));
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status, started_at_ms)
                VALUES ('cleanup-execution', 'cleanup-ticket-1', 1,
                    'RUNNING', 120)
                """);

        Step execute = store.claim(
                "cleanup-step-3", ClaimMode.EXECUTE, "cleanup-execution",
                Instant.ofEpochMilli(120), Instant.ofEpochMilli(220));
        store.fail(execute, StepResult.indeterminate(
                "cleanup execution is not a provider session", "indeterminate",
                "provider reconciliation is still pending"),
                Instant.ofEpochMilli(121));

        assertThat(jdbc.queryForMap("""
                SELECT step.status, step.failure_kind, result.outcome
                FROM cleanup_step step
                JOIN cleanup_step_attempt_result result
                  ON result.cleanup_step_id = step.id
                WHERE step.id = 'cleanup-step-3'
                """))
                .containsEntry("status", "FAILED")
                .containsEntry("failure_kind", "INDETERMINATE")
                .containsEntry("outcome", "INDETERMINATE");

        Step probe = store.claim(
                "cleanup-step-3", ClaimMode.PROBE, "cleanup-probe",
                Instant.ofEpochMilli(221), Instant.ofEpochMilli(321));
        StepResult stopped = StepResult.succeeded(
                null, "no provider session remains", "provider-stopped");
        assertThatThrownBy(() -> store.succeed(
                probe, stopped, Instant.ofEpochMilli(222)))
                .hasMessageContaining(
                        "Cleanup result lacks exact claimed or reconciled evidence");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM cleanup_step WHERE id = 'cleanup-step-3'
                """, String.class)).isEqualTo(StepStatus.CLAIMED.name());

        jdbc.update("""
                UPDATE agent_execution
                SET status = 'SUCCEEDED', finished_at_ms = 223
                WHERE id = 'live-agent-execution'
                """);
        store.succeed(probe, stopped, Instant.ofEpochMilli(224));

        assertThat(jdbc.queryForObject("""
                SELECT status FROM cleanup_step WHERE id = 'cleanup-step-3'
                """, String.class)).isEqualTo(StepStatus.SUCCEEDED.name());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM cleanup_step_attempt_result
                WHERE cleanup_step_id = 'cleanup-step-3'
                """, Integer.class)).isEqualTo(2);
    }

    private static void succeed(
            SqliteCleanupOperationStore store, String stepId, Instant at)
    {
        Step step = store.claim(
                stepId, ClaimMode.EXECUTE, "cleanup-execution",
                at, at.plusMillis(100));
        store.succeed(step, StepResult.succeeded(
                null, "cleanup proof", "proof-" + step.ordinal()),
                at.plusMillis(1));
    }
}
