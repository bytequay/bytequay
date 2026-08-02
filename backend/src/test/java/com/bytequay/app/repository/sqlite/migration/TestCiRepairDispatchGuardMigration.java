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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCiRepairDispatchGuardMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void baseRewriteValidationRequiresItsExactWriterTicket()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("ci-rewrite-dispatch.db");
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
        }
        migrate(url);

        try (Connection connection = connect(url)) {
            seedAuthorizedBaseValidation(connection);

            assertThatThrownBy(() -> insertTicket(
                    connection, "legacy-ticket", "VALIDATE_REMOTE_CI_REPAIR",
                    "REMOTE_CI_VALIDATION_RESULT", false))
                    .hasMessageContaining("CI repair DispatchTicket is not exact");
            assertThatThrownBy(() -> insertTicket(
                    connection, "unleased-ticket",
                    "REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR",
                    "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT", false))
                    .hasMessageContaining("CI repair DispatchTicket is not exact");

            assertThat(insertTicket(
                    connection, "exact-ticket",
                    "REWRITE_VALIDATE_REMOTE_CI_BASE_REPAIR",
                    "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT", true)).isOne();
            assertThat(queryInt(connection, """
                    SELECT writer_required FROM dispatch_ticket
                    WHERE id = 'exact-ticket'
                    """)).isOne();
        }
    }

    private static void seedAuthorizedBaseValidation(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, observation_revision,
                    observation_key, remote_repository_id, remote_pr_number,
                    head_sha, base_sha, pr_state, mergeability, merge_queue_state,
                    observed_at_ms, raw_evidence, ci_provenance_json)
                VALUES ('snapshot-1-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 1, 'observation-1-1', 'acme/widget', 41,
                    'head-1', 'base-1', 'OPEN', 'MERGEABLE', 'NONE', 61, '{}',
                    '{"schemaVersion":3,"complete":true}')
                """);
        insertFailedCi(connection, 1, 1, "head-1", "base-1");
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('base-episode', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'BASE_DETERMINISTIC', 'OPEN', 0, 2, 2, 2, 80)
                """);
        execute(connection, """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-policy', 'task-1', 1, 'TEST', 1, 0, 0,
                    0, 0, 0, 0, 0, 'test', 81)
                """);
        execute(connection, """
                INSERT INTO ci_base_repair_manifest_v303(
                    id, ci_repair_episode_id, failed_ci_evaluation_id,
                    remote_pr_snapshot_id, subject_head_sha, subject_base_sha,
                    subject_manifest_json, manifest_digest, created_at_ms)
                VALUES ('base-manifest', 'base-episode', 'ci-evaluation-1-1',
                    'snapshot-1-1', 'head-1', 'base-1', '{}',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    82)
                """);
        execute(connection, """
                INSERT INTO ci_base_repair_authorization_v303(
                    id, ci_repair_episode_id, manifest_id, semantic_attempt,
                    authority_kind, automation_policy_id, command_id, reason,
                    failed_ci_evaluation_id, remote_pr_snapshot_id,
                    expected_worktree_head_sha, subject_head_sha, subject_base_sha,
                    manifest_digest, status, claimed_at_ms)
                VALUES ('base-authorization', 'base-episode', 'base-manifest', 1,
                    'AUTO_APPROVE_POLICY', 'automation-policy', 'authorize-base',
                    'repair base-owned failure', 'ci-evaluation-1-1',
                    'snapshot-1-1', 'head-1', 'head-1', 'base-1',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CLAIMED', 83)
                """);
        execute(connection, """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha,
                    base_repair_authorization_id, status, requested_at_ms)
                VALUES ('base-validation-row', 'base-episode', 'remote-stage-1',
                    'task-1', 1, 1, 'VALIDATE', 'base-validation-operation', 1,
                    'fingerprint-1', 'head-1', 'base-1', 'base-authorization',
                    'REQUESTED', 84)
                """);
    }

    private static int insertTicket(
            Connection connection,
            String ticketId,
            String operationKind,
            String callbackRoute,
            boolean writerRequired)
            throws SQLException
    {
        try (var statement = connection.prepareStatement("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, 'base-validation-operation', ?, 'VALIDATION',
                    'STAGE', 'remote-stage-1', ?, 4, 0, 1, ?, 'workspace-1',
                    'trunk-1', 'task-1', 1, 'remote-stage-1', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 85)
                """)) {
            statement.setString(1, ticketId);
            statement.setString(2, operationKind);
            statement.setString(3, callbackRoute);
            statement.setInt(4, writerRequired ? 1 : 0);
            return statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int queryInt(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }
}
