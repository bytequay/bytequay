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

import com.bytequay.app.developmentflow.stage.V2ReadinessNotificationProjector;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessNotificationProjectionStore;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
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
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestV2ReadinessNotificationProjector
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void emitsOnceAcrossRepeatAndRestartThenResetsOnlyForAReadinessRegression()
            throws Exception
    {
        Database database = database("ready-edges.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1);
        }
        NotificationService notifications = mock(NotificationService.class);

        projector(database, notifications).maintain(NOW);
        projector(database, notifications).maintain(NOW.plusSeconds(5));
        verify(notifications, times(1)).createCanonical(
                eq(NotificationKind.READY_TO_MERGE), eq("workspace-1"),
                eq("trunk-1"), eq("task-1"), eq("review-request"),
                eq("Pull request ready to merge"), anyString(),
                eq("#/workspace/workspace-1/trunks/trunk-1"),
                eq("development-flow:ready-to-merge:remote-stage-1:1"),
                anyString());

        // A stricter policy is durable non-ready evidence, and the Remote
        // owner records the matching readiness regression.
        try (Connection connection = connect(database.url())) {
            execute(connection, automationPolicySql(1, 2, 1));
            execute(connection, readinessSql(1, "readiness-1-2", 2, 1, 0));
            transition(connection, 1, 1, 2,
                    "READY_TO_MERGE", "WAITING_REMOTE_REVIEW");
        }
        projector(database, notifications).maintain(NOW.plusSeconds(10));
        try (Connection connection = connect(database.url())) {
            assertThat(text(connection, """
                    SELECT state
                    FROM remote_readiness_notification_marker_v271
                    WHERE stage_id = 'remote-stage-1'
                    """)).isEqualTo("REGRESSED");
        }

        // A later current-policy proof drives a fresh owner transition and
        // therefore one new notification edge.
        try (Connection connection = connect(database.url())) {
            execute(connection, automationPolicySql(1, 3, 0));
            execute(connection, readinessSql(1, "readiness-1-3", 3, 0, 1));
            transition(connection, 1, 2, 3,
                    "WAITING_REMOTE_REVIEW", "READY_TO_MERGE");
        }
        projector(database, notifications).maintain(NOW.plusSeconds(15));
        verify(notifications, times(1)).createCanonical(
                eq(NotificationKind.READY_TO_MERGE), eq("workspace-1"),
                eq("trunk-1"), eq("task-1"), eq("review-request"),
                eq("Pull request ready to merge"), anyString(),
                eq("#/workspace/workspace-1/trunks/trunk-1"),
                eq("development-flow:ready-to-merge:remote-stage-1:3"),
                anyString());

        // A merge-queue bounce is not a readiness regression. Returning from
        // MERGING to the same exact ready proof must remain throttled.
        try (Connection connection = connect(database.url())) {
            transition(connection, 1, 3, 4,
                    "READY_TO_MERGE", "MERGING");
            transition(connection, 1, 4, 5,
                    "MERGING", "READY_TO_MERGE");
        }
        projector(database, notifications).maintain(NOW.plusSeconds(20));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void isolatesSiblingsAndRejectsStalePolicyAndHeadProofs()
            throws Exception
    {
        Database database = database("exact-siblings.db", 1, 2, 3);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1);

            seedExactReady(connection, 2);
            execute(connection, automationPolicySql(2, 2, 1));
            execute(connection, readinessSql(2, "readiness-2-2", 2, 1, 0));

            seedExactReady(connection, 3);
            insertSnapshot(connection, 3, 2, "new-head-3", "base-3", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            insertGreenCi(connection, 3, 2, "new-head-3", "base-3");
            acceptSnapshot(connection, 3, 2, "new-head-3", "base-3");
        }
        NotificationService notifications = mock(NotificationService.class);

        projector(database, notifications).maintain(NOW);

        ArgumentCaptor<String> taskId = ArgumentCaptor.forClass(String.class);
        verify(notifications).createCanonical(
                eq(NotificationKind.READY_TO_MERGE), eq("workspace-1"),
                eq("trunk-1"), taskId.capture(), eq("review-request"),
                eq("Pull request ready to merge"), anyString(),
                eq("#/workspace/workspace-1/trunks/trunk-1"), anyString(),
                anyString());
        assertThat(taskId.getValue()).isEqualTo("task-1");
        try (Connection connection = connect(database.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_readiness_notification_marker_v271
                    """)).isOne();
            assertThat(text(connection, """
                    SELECT task_id
                    FROM remote_readiness_notification_marker_v271
                    """)).isEqualTo("task-1");
        }
    }

    @Test
    void notificationFailureRollsBackTheClaimSoRestartCanRetry()
            throws Exception
    {
        Database database = database("notification-rollback.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1);
        }
        NotificationService notifications = mock(NotificationService.class);
        when(notifications.createCanonical(
                eq(NotificationKind.READY_TO_MERGE), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenThrow(new IllegalStateException("notification store unavailable"))
                .thenReturn(null);

        assertThatThrownBy(() -> projector(database, notifications).maintain(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification store unavailable");
        try (Connection connection = connect(database.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_readiness_notification_marker_v271
                    """)).isZero();
        }

        projector(database, notifications).maintain(NOW.plusSeconds(5));
        verify(notifications, times(2)).createCanonical(
                eq(NotificationKind.READY_TO_MERGE), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
        try (Connection connection = connect(database.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_readiness_notification_marker_v271
                    """)).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    private Database database(String name, int... taskNumbers)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            for (int taskNumber : taskNumbers) {
                seedPublishedRemoteTask(connection, taskNumber);
            }
        }
        migrate(url);
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        return new Database(url, source, new JdbcTemplate(source));
    }

    private static V2ReadinessNotificationProjector projector(
            Database database, NotificationService notifications)
    {
        return new V2ReadinessNotificationProjector(
                new SqliteReadinessNotificationProjectionStore(
                        database.jdbc(),
                        new TransactionTemplate(
                                new DataSourceTransactionManager(database.source()))),
                notifications,
                new ObjectMapper());
    }

    private static void seedExactReady(Connection connection, int taskNumber)
            throws Exception
    {
        insertRemoteOwner(connection, taskNumber);
        insertCiPolicy(connection, taskNumber);
        insertSnapshot(connection, taskNumber, 1,
                "head-" + taskNumber, "base-" + taskNumber, "OPEN",
                "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
        acceptSnapshot(connection, taskNumber, 1,
                "head-" + taskNumber, "base-" + taskNumber);
        insertGreenCi(connection, taskNumber, 1,
                "head-" + taskNumber, "base-" + taskNumber);
        execute(connection, automationPolicySql(taskNumber, 1, 0));
        execute(connection, readinessSql(
                taskNumber, "readiness-" + taskNumber + "-1", 1, 0, 1));
        transition(connection, taskNumber, 0, 1,
                "WAITING_CI", "READY_TO_MERGE");
    }

    private static void transition(
            Connection connection,
            int taskNumber,
            int fromVersion,
            int toVersion,
            String from,
            String to)
            throws Exception
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    UPDATE stage
                    SET version = %2$s, checkpoint = '%4$s'
                    WHERE id = 'remote-stage-%1$s'
                      AND version = %3$s AND checkpoint = '%5$s'
                    """.formatted(taskNumber, toVersion, fromVersion, to, from));
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor,
                        occurred_at_ms)
                    VALUES (
                        'transition-%1$s-%2$s', 'remote-stage-%1$s',
                        'command-%1$s-%2$s', 1, '%3$s', '%4$s', %2$s,
                        'TEST_TRANSITION', 'test', 100 + %2$s)
                    """.formatted(taskNumber, toVersion, from, to));
            connection.commit();
        }
        catch (Throwable failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    private static String automationPolicySql(
            int taskNumber, int revision, int minimumApprovals)
    {
        return """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-%1$s-%2$s', 'task-%1$s', %2$s, 'USER',
                    1, 0, 0, %3$s, 2, 0, 0, 0, 'user', 70 + %2$s)
                """.formatted(taskNumber, revision, minimumApprovals);
    }

    private static String readinessSql(
            int taskNumber,
            String id,
            int policyRevision,
            int requiredApprovals,
            int ready)
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
                VALUES ('%2$s', 'remote-stage-%1$s', 'task-%1$s', 1, 1,
                    'snapshot-%1$s-1', 'green-ci-%1$s-1',
                    'automation-%1$s-%3$s', 'head-%1$s', 'base-%1$s',
                    1, 1, 1, 0, %4$s, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    'MERGEABLE', 'UNSUPPORTED', %5$s,
                    'fresh exact current-policy truth', 61)
                """.formatted(
                taskNumber, id, policyRevision, requiredApprovals, ready);
    }

    private record Database(
            String url, SQLiteDataSource source, JdbcTemplate jdbc) {}
}
