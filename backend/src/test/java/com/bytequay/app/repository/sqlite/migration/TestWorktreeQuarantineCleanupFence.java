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

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.Lease;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.Step;
import com.bytequay.app.developmentflow.execution.cleanup.CleanupOperationHandler.StepResult;
import com.bytequay.app.developmentflow.execution.cleanup.SqliteCleanupOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteWorktreeWriterLeaseStore;
import com.bytequay.app.testing.SqliteTestPools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestWorktreeQuarantineCleanupFence
{
    private static final Instant CLEARED_AT = Instant.ofEpochMilli(100);

    @TempDir
    private Path tempDir;

    @Test
    void cleanupClearsOnlyWhileItsExactCapacityAndWriterFencesRemainLive()
            throws Exception
    {
        Fixture fixture = fixture("cleanup-exact.db");

        assertThat(fixture.store().tryAcquireCleanupDisposal(
                fixture.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", CLEARED_AT)).contains(fixture.writer());
        assertThat(fixture.store().clearForCleanup(
                fixture.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", "worktree path is absent", CLEARED_AT))
                .isTrue();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM agent_turn_worktree_quarantine_v318
                WHERE id = 'quarantine-1'
                """, String.class)).isEqualTo("CLEARED");
    }

    @Test
    void quarantineOpenRejectsAWriterThatExpiresAtItsObservation()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("expired-open.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            seedSourceWriter(connection, 1000, 82);

            assertThatThrownBy(() -> insertQuarantine(
                    connection, "expired-quarantine", 82))
                    .hasMessageContaining("quarantine source is not exact");
        }

    }

    @Test
    void cleanupLeavesQuarantineOpenWhenItsCapacityWasReleasedOrReplaced()
            throws Exception
    {
        Fixture released = fixture("cleanup-released.db");
        assertThat(released.store().tryAcquireCleanupDisposal(
                released.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", CLEARED_AT)).contains(released.writer());
        released.jdbc().update("""
                UPDATE capacity_lease
                   SET released_at_ms = 101, release_reason = 'lost worker'
                 WHERE id = 'cleanup-lease-1'
                """);

        assertThat(released.store().clearForCleanup(
                released.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", "worktree path is absent", CLEARED_AT))
                .isFalse();
        assertOpen(released);

        Fixture replaced = fixture("cleanup-replaced.db");
        assertThat(replaced.store().tryAcquireCleanupDisposal(
                replaced.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", CLEARED_AT)).contains(replaced.writer());
        replaceCleanupCapacity(replaced);

        assertThat(replaced.store().clearForCleanup(
                replaced.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", "worktree path is absent", CLEARED_AT))
                .isFalse();
        assertOpen(replaced);

        Fixture expired = fixture("cleanup-expired.db");
        assertThat(expired.store().tryAcquireCleanupDisposal(
                expired.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", CLEARED_AT)).contains(expired.writer());

        assertThat(expired.store().clearForCleanup(
                expired.writer(), "quarantine-1", "cleanup-operation-1",
                "cleanup-step-8", "worktree path is absent",
                Instant.ofEpochMilli(1000))).isFalse();
        assertOpen(expired);
    }

    private Fixture fixture(String file)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file)
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            seedSourceWriter(connection, 1000, 1000);
            insertQuarantine(connection, "quarantine-1", 82);
            releaseSourceWriter(connection);
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .prepareMergedCleanup(connection);
        }
        DataSource dataSource = SqliteTestPools.open(url);
        claimRemoveWorktree(new JdbcTemplate(dataSource));
        return new Fixture(
                new JdbcTemplate(dataSource),
                new SqliteWorktreeWriterLeaseStore(dataSource),
                new Lease("/tmp/task-1", "task-1", "cleanup-operation-id-1",
                        1, 1, "cleanup-worker", Instant.ofEpochMilli(90),
                        Instant.ofEpochMilli(1000)));
    }

    private static void seedSourceWriter(
            Connection connection, long capacityExpiresAt, long writerExpiresAt)
            throws Exception
    {
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms)
                VALUES ('source-turn-1', 'remote-stage-1', 1, 'TEST_WRITE',
                    'RUNNING', 'source-operation-1', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'CLI', '{}', 80, 81)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('source-ticket-1', 'source-operation-1', 'SOURCE_WRITE',
                    'LOCAL_GIT', 'STAGE_TURN', 'source-turn-1',
                    'SOURCE_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 'remote-stage-1', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 80)
                """);
        execute(connection, """
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('source-lease-1', 'source-ticket-1',
                    'source-operation-1', 'V2', 16, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'source-worker', 17,
                    81, 81, %s)
                """.formatted(capacityExpiresAt));
        execute(connection, """
                UPDATE dispatch_ticket
                   SET version = 1, status = 'RUNNING',
                       claim_purpose = 'EXECUTE', claim_owner = 'source-worker',
                       capacity_lease_id = 'source-lease-1',
                       claim_expires_at_ms = 1000, started_at_ms = 81
                 WHERE id = 'source-ticket-1'
                """);
        execute(connection, """
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, holder_pid,
                    acquired_at_ms, expires_at_ms, workflow_version,
                    operation_id, task_epoch, fencing_token, lease_owner)
                VALUES ('/tmp/task-1', 'task-1', 'V2_OPERATION', NULL,
                    81, %s, 'V2', 'source-operation-1', 1, 17,
                    'source-worker')
                """.formatted(writerExpiresAt));
    }

    private static void insertQuarantine(
            Connection connection, String quarantineId, long openedAt)
            throws Exception
    {
        execute(connection, """
                INSERT INTO agent_turn_worktree_quarantine_v318(
                    id, task_id, stage_id, source_operation_id,
                    worktree_path, expected_branch_name,
                    expected_code_fingerprint, expected_head_sha,
                    observed_branch_name, observed_head_sha, observed_clean,
                    observed_code_fingerprint, reason, status, opened_at_ms)
                VALUES ('%1$s', 'task-1', 'remote-stage-1',
                    'source-operation-1', '/tmp/task-1', 'dev/task-1',
                    'fingerprint-1', 'head-1', 'dev/task-1', 'dirty-head', 0,
                    'dirty-fingerprint', 'restore was not exact', 'OPEN', %2$s)
                """.formatted(quarantineId, openedAt));
    }

    private static void releaseSourceWriter(Connection connection)
            throws Exception
    {
        // A released lease alone does not make the preceding source operation
        // quiescent. Cleanup step 2 proves both no live turns and no live
        // non-Cleanup tickets, so model the source operation's durable terminal
        // outcome before using this fixture to advance the Cleanup rail.
        execute(connection, """
                UPDATE stage_turn
                   SET status = 'CANCELED', finished_at_ms = 83
                 WHERE id = 'source-turn-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                   SET version = 2, status = 'CANCELED',
                       claim_purpose = NULL, claim_owner = NULL,
                       capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                       delivery_acceptance = 'SUPERSEDED',
                       delivery_evidence = 'source work reconciled before cleanup',
                       completed_at_ms = 83
                 WHERE id = 'source-ticket-1'
                """);
        execute(connection, """
                DELETE FROM worktree_leases
                WHERE operation_id = 'source-operation-1'
                """);
        execute(connection, """
                UPDATE capacity_lease
                   SET released_at_ms = 83, release_reason = 'source ended'
                 WHERE id = 'source-lease-1'
                """);
    }

    private static void claimRemoveWorktree(JdbcTemplate jdbc)
    {
        SqliteCleanupOperationStore cleanup = new SqliteCleanupOperationStore(
                jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
        for (int ordinal = 1; ordinal <= 7; ordinal++) {
            Step step = cleanup.claim(
                    "cleanup-step-" + ordinal, ClaimMode.EXECUTE,
                    "cleanup-worker", Instant.ofEpochMilli(95 + ordinal),
                    Instant.ofEpochMilli(1000));
            if (ordinal == 6) {
                settleCleanupInteractions(jdbc);
            }
            cleanup.succeed(step, StepResult.succeeded(
                    null, "exact cleanup proof", "cleanup-proof-" + ordinal),
                    Instant.ofEpochMilli(95 + ordinal));
        }
        cleanup.claim("cleanup-step-8", ClaimMode.EXECUTE, "cleanup-worker",
                Instant.ofEpochMilli(103), Instant.ofEpochMilli(1000));
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = 2, status = 'RUNNING', started_at_ms = 95
                 WHERE id = 'cleanup-ticket-1'
                """);
    }

    private static void settleCleanupInteractions(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE notifications
                   SET status = 'DISMISSED', read_at_ms = 101
                 WHERE id = 'cleanup-notification-1'
                """);
        jdbc.update("""
                INSERT INTO permission_answer_attempt(
                    id, permission_id, expected_revision, proposed_state,
                    actor, answer, outcome, attempted_at_ms)
                VALUES ('cleanup-answer-attempt-1', 'cleanup-permission-1',
                    0, 'CANCELED', 'cleanup-worker',
                    'cleanup canceled request', 'ACCEPTED', 101)
                """);
        jdbc.update("""
                UPDATE permission_request
                   SET state = 'CANCELED', answer = 'cleanup canceled request',
                       answer_revision = answer_revision + 1,
                       answered_at_ms = 101, answer_actor = 'cleanup-worker',
                       continuation_state = 'CANCELED'
                 WHERE id = 'cleanup-permission-1'
                """);
        jdbc.update("""
                INSERT INTO cleanup_interaction_dismissal_evidence(
                    id, cleanup_step_id, cleanup_operation_id, task_id,
                    task_epoch, dismissed_notification_count,
                    canceled_permission_count, notification_scope_evidence,
                    permission_scope_evidence, recorded_at_ms)
                VALUES ('cleanup-interaction-evidence-1', 'cleanup-step-6',
                    'cleanup-operation-1', 'task-1', 1, 1, 1,
                    'all task notifications dismissed',
                    'all task permissions canceled', 101)
                """);
    }

    private static void replaceCleanupCapacity(Fixture fixture)
    {
        fixture.jdbc().update("""
                UPDATE capacity_lease
                   SET released_at_ms = 101, release_reason = 'replaced'
                 WHERE id = 'cleanup-lease-1'
                """);
        fixture.jdbc().update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('cleanup-lease-2', 'cleanup-ticket-1',
                    'cleanup-operation-id-1', 'V2', 256, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'cleanup-worker', 2,
                    101, 101, 1000)
                """);
        fixture.jdbc().update("""
                UPDATE dispatch_ticket
                   SET version = version + 1, capacity_lease_id = 'cleanup-lease-2'
                 WHERE id = 'cleanup-ticket-1'
                """);
    }

    private static void assertOpen(Fixture fixture)
    {
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM agent_turn_worktree_quarantine_v318
                WHERE id = 'quarantine-1'
                """, String.class)).isEqualTo("OPEN");
    }

    private record Fixture(
            JdbcTemplate jdbc,
            SqliteWorktreeWriterLeaseStore store,
            Lease writer) {}
}
