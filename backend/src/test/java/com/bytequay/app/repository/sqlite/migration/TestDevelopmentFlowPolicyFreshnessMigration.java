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

import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemotePolicyRedriveRuntime;
import com.bytequay.app.developmentflow.task.TaskPolicyRevisionRedriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.assertFails;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertGreenCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestDevelopmentFlowPolicyFreshnessMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void persistsOnlyAnExactLatestPolicyReadinessRegressionAcrossRestart()
            throws Exception
    {
        String url = "jdbc:sqlite:"
                + tempDir.resolve("policy-freshness.db") + "?foreign_keys=ON";
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url, "268");

        try (Connection connection = connect(url)) {
            seedRemoteSubject(connection);
            execute(connection, automationPolicySql(1, 0));
            execute(connection, readinessSql(
                    "readiness-policy-1", "automation-1", 0, 1));
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'READY_TO_MERGE'
                    WHERE id = 'remote-stage-1'
                    """);

            execute(connection, automationPolicySql(2, 1));
            execute(connection, readinessSql(
                    "readiness-policy-2", "automation-2", 1, 0));
            assertFails(connection, staleMergeAuthorizationSql());
            assertRestartScanFindsPolicyRegression(url);

            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE stage
                        SET version = 2, checkpoint = 'WAITING_REMOTE_REVIEW'
                        WHERE id = 'remote-stage-1'
                          AND version = 1 AND checkpoint = 'READY_TO_MERGE'
                        """);
                execute(connection, """
                        INSERT INTO stage_transition(
                            id, stage_id, command_id, generation,
                            from_checkpoint, to_checkpoint, stage_version,
                            cause, actor, occurred_at_ms)
                        VALUES ('policy-transition', 'remote-stage-1',
                            'policy-command', 1, 'READY_TO_MERGE',
                            'WAITING_REMOTE_REVIEW', 2,
                            'RECONSIDER_REMOTE_READINESS_POLICY',
                            'task-policy-redrive', 90)
                        """);

                assertFails(connection, receiptSql(
                        "stale-policy-receipt", "readiness-policy-1"));
                execute(connection, receiptSql(
                        "current-policy-receipt", "readiness-policy-2"));
                connection.commit();
            }
            catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
            finally {
                connection.setAutoCommit(true);
            }

            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_policy_stage_receipt_v268
                    WHERE id = 'current-policy-receipt'
                      AND proof_id = 'readiness-policy-2'
                      AND returned_checkpoint = 'WAITING_REMOTE_REVIEW'
                    """)).isOne();
            assertFails(connection, """
                    UPDATE remote_policy_stage_receipt_v268
                    SET actor = 'other' WHERE id = 'current-policy-receipt'
                    """);
        }

        migrate(url, "268");
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_policy_stage_receipt_v268
                    WHERE id = 'current-policy-receipt'
                    """)).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    private static void assertRestartScanFindsPolicyRegression(String url)
    {
        PlanRuntimeCoordinator plans = mock(PlanRuntimeCoordinator.class);
        RemotePolicyRedriveRuntime remote = mock(RemotePolicyRedriveRuntime.class);
        new TaskPolicyRevisionRedriver(
                new JdbcTemplate(new DriverManagerDataSource(url)), plans, remote)
                .maintain(Instant.ofEpochMilli(85));
        verify(plans).redrivePolicyApproval("task-1");
        verify(remote).redrive("task-1");
    }

    private static void seedRemoteSubject(Connection connection)
            throws Exception
    {
        insertRemoteOwner(connection, 1);
        insertCiPolicy(connection, 1);
        insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN",
                "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
        acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        insertGreenCi(connection, 1, 1, "head-1", "base-1");
    }

    private static String automationPolicySql(int revision, int minimumApprovals)
    {
        return """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-%1$s', 'task-1', %1$s, 'USER', 1, 0,
                    0, %2$s, 2, 0, 0, 0, 'user', 70 + %1$s)
                """.formatted(revision, minimumApprovals);
    }

    private static String readinessSql(
            String id, String policyId, int requiredApprovals, int ready)
    {
        return """
                INSERT INTO remote_readiness_evidence(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
                    automation_policy_id, head_sha, base_sha, pr_open, non_draft,
                    ci_accepted, write_approval_count,
                    required_write_approval_count, changes_requested_count,
                    unresolved_thread_count, unresolved_comment_count,
                    open_feedback_batch_count, blocking_gate_count,
                    low_risk_required, small_effort_required,
                    low_risk_eligible, small_effort_eligible, mergeability,
                    merge_queue_capability, ready, evidence, observed_at_ms)
                VALUES ('%1$s', 'remote-stage-1', 'task-1', 1, 1,
                    'snapshot-1-1', 'green-ci-1-1', '%2$s', 'head-1', 'base-1',
                    1, 1, 1, 0, %3$s, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    'MERGEABLE', 'UNSUPPORTED', %4$s,
                    'fresh exact current-policy truth', 61)
                """.formatted(id, policyId, requiredApprovals, ready);
    }

    private static String staleMergeAuthorizationSql()
    {
        return """
                INSERT INTO remote_merge_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, readiness_evidence_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    actor_id, status, authorized_at_ms)
                VALUES ('stale-merge-authorization', 'remote-stage-1', 'task-1',
                    1, 1, 'readiness-policy-1', 'automation-1',
                    'head-1', 'base-1', 'MANUAL', 'user', 'ACTIVE', 80)
                """;
    }

    private static String receiptSql(String id, String proofId)
    {
        return """
                INSERT INTO remote_policy_stage_receipt_v268(
                    id, stage_id, task_id, command_id, cause, actor,
                    disposition, expected_task_epoch,
                    expected_stage_generation, expected_stage_version,
                    source_checkpoint, proof_id, returned_kind,
                    returned_generation, returned_version,
                    returned_checkpoint, recorded_at_ms)
                VALUES ('%1$s', 'remote-stage-1', 'task-1', 'policy-command',
                    'RECONSIDER_REMOTE_READINESS_POLICY', 'task-policy-redrive',
                    'APPLIED', 1, 1, 1, 'READY_TO_MERGE', '%2$s',
                    'REMOTE_DEVELOPMENT', 1, 2,
                    'WAITING_REMOTE_REVIEW', 90)
                """.formatted(id, proofId);
    }
}
