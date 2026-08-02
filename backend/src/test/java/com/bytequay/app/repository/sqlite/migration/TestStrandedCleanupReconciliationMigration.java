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
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;

class TestStrandedCleanupReconciliationMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void rearmsOnlyTheProvenCleanupProbeAndSettlesItsTerminalRemoteChild()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("stranded-cleanup.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url + "?foreign_keys=ON&busy_timeout=30000");
        Flyway.configure().dataSource(dataSource).target("315").load().migrate();

        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            seedCanceledCiRepair(connection);
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .prepareMergedCleanup(connection);
            strandCleanupProbe(connection);
            insertUnrelatedParkedTicket(connection);

            assertThat(text(connection, """
                    SELECT status FROM ci_repair_episode
                    WHERE id = 'stranded-ci-episode'
                    """)).isEqualTo("AWAITING_RERUN");
            assertThat(text(connection, """
                    SELECT status FROM ci_repair_operation
                    WHERE id = 'stranded-ci-operation'
                    """)).isEqualTo("DISPATCHED");
            assertThat(number(connection, """
                    SELECT next_attempt_at_ms IS NULL
                    FROM dispatch_ticket WHERE id = 'cleanup-ticket-1'
                    """)).isOne();
        }

        Flyway.configure().dataSource(dataSource).load().migrate();

        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT status FROM ci_repair_episode
                    WHERE id = 'stranded-ci-episode'
                    """)).isEqualTo("STOPPED");
            assertThat(text(connection, """
                    SELECT status FROM ci_repair_operation
                    WHERE id = 'stranded-ci-operation'
                    """)).isEqualTo("SUPERSEDED");
            assertThat(number(connection, """
                    SELECT completed_at_ms FROM ci_repair_episode
                    WHERE id = 'stranded-ci-episode'
                    """)).isEqualTo(96);
            assertThat(text(connection, """
                    SELECT stop_reason FROM ci_repair_episode
                    WHERE id = 'stranded-ci-episode'
                    """)).contains("remote MERGED observation");
            assertThat(number(connection, """
                    SELECT version FROM dispatch_ticket
                    WHERE id = 'cleanup-ticket-1'
                    """)).isEqualTo(3);
            assertThat(number(connection, """
                    SELECT next_attempt_at_ms FROM dispatch_ticket
                    WHERE id = 'cleanup-ticket-1'
                    """)).isEqualTo(120);
            assertThat(number(connection, """
                    SELECT next_attempt_at_ms IS NULL
                    FROM dispatch_ticket WHERE id = 'unrelated-parked-ticket'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT version FROM dispatch_ticket
                    WHERE id = 'unrelated-parked-ticket'
                    """)).isZero();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            assertThat(text(connection, "PRAGMA integrity_check")).isEqualTo("ok");
        }
    }

    private static void seedCanceledCiRepair(Connection connection)
            throws Exception
    {
        insertRemoteOwner(connection, 1);
        insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN", "MERGEABLE");
        acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        insertCiPolicy(connection, 1);
        insertFailedCi(connection, 1, 1, "head-1", "base-1");
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_limit, delivery_retry_limit,
                    push_limit, opened_at_ms)
                VALUES ('stranded-ci-episode', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'INFRASTRUCTURE', 'OPEN', 1, 0, 0, 0, 75)
                """);
        execute(connection, """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('stranded-ci-operation', 'stranded-ci-episode',
                    'remote-stage-1', 'task-1', 1, 1, 'RERUN',
                    'stranded-ci-operation-id', 1, 'head-1', 'base-1',
                    'REQUESTED', 76)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('stranded-ci-ticket', 'stranded-ci-operation-id',
                    'RERUN_REMOTE_CI', 'GITHUB_EFFECT', 'STAGE',
                    'remote-stage-1', 'REMOTE_CI_RERUN_RESULT', 32,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'remote-stage-1', 1, 1, 'head-1', 'base-1',
                    'REQUESTED', 76)
                """);
        execute(connection, """
                UPDATE ci_repair_operation SET status = 'DISPATCHED'
                WHERE id = 'stranded-ci-operation'
                """);
        execute(connection, """
                UPDATE ci_repair_episode SET status = 'AWAITING_RERUN'
                WHERE id = 'stranded-ci-episode'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'CANCELED',
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'terminal Task superseded launch',
                    completed_at_ms = 96
                WHERE id = 'stranded-ci-ticket'
                """);
    }

    private static void strandCleanupProbe(Connection connection)
            throws Exception
    {
        execute(connection, """
                UPDATE cleanup_step
                SET status = 'CLAIMED', attempt_count = 1,
                    execute_attempt_count = 1, claim_mode = 'EXECUTE',
                    claim_owner = 'cleanup-execute', claimed_at_ms = 100,
                    lease_until_ms = 110
                WHERE id = 'cleanup-step-1'
                """);
        execute(connection, """
                INSERT INTO cleanup_step_attempt_result(
                    id, cleanup_step_id, cleanup_operation_id, task_id,
                    task_epoch, ordinal, attempt, claim_mode, outcome,
                    evidence, evidence_digest, recorded_at_ms)
                VALUES ('cleanup-result-1', 'cleanup-step-1',
                    'cleanup-operation-1', 'task-1', 1, 1, 1, 'EXECUTE',
                    'SUCCEEDED', 'still cleaning', 'step-one-proof', 101)
                """);
        execute(connection, """
                UPDATE cleanup_step
                SET status = 'SUCCEEDED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, completed_at_ms = 101
                WHERE id = 'cleanup-step-1'
                """);
        execute(connection, """
                UPDATE cleanup_step
                SET status = 'CLAIMED', attempt_count = 1,
                    execute_attempt_count = 1, claim_mode = 'EXECUTE',
                    claim_owner = 'cleanup-execute', claimed_at_ms = 105,
                    lease_until_ms = 110
                WHERE id = 'cleanup-step-2'
                """);
        execute(connection, """
                UPDATE cleanup_step
                SET status = 'CLAIMED', attempt_count = 2,
                    claim_mode = 'PROBE', claim_owner = 'cleanup-probe',
                    claimed_at_ms = 120, lease_until_ms = 220
                WHERE id = 'cleanup-step-2'
                """);
        execute(connection, """
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    started_at_ms, finished_at_ms, error_class, error_message)
                VALUES ('cleanup-probe', 'cleanup-ticket-1', 1, 'UNKNOWN',
                    120, 121, 'SQLITE_CONSTRAINT_TRIGGER',
                    'Cleanup result lacks exact claimed or reconciled evidence')
                """);
        execute(connection, """
                UPDATE capacity_lease
                SET released_at_ms = 122, release_reason = 'reconcile wait'
                WHERE id = 'cleanup-lease-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 2, status = 'RECONCILE_WAIT',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    next_attempt_at_ms = NULL, infrastructure_attempts = 1,
                    started_at_ms = 90,
                    last_error = 'Cleanup result lacks exact claimed or reconciled evidence'
                WHERE id = 'cleanup-ticket-1'
                """);
    }

    private static void insertUnrelatedParkedTicket(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    workspace_id, trunk_id, attempt, status,
                    next_attempt_at_ms, last_error, created_at_ms)
                VALUES ('unrelated-parked-ticket', 'unrelated-operation',
                    'OBSERVE_UNRELATED', 'REMOTE_OBSERVATION', 'TRUNK',
                    'trunk-1', 'UNRELATED_RESULT', 64, 'workspace-1',
                    'trunk-1', 1, 'RECONCILE_WAIT', NULL,
                    'Cleanup result lacks exact claimed or reconciled evidence', 130)
                """);
    }
}
