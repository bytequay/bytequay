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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePublishResultStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowLocalPublishProtocolMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void expiredPublishClaimCanOnlyBeRecoveredByProbeBeforeReexecution()
            throws Exception
    {
        String url = migrated("publish-expired-claim.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
            execute(connection, manifestSql("manifest-1", 1, 1));
            insertStandingPublishAuthorization(connection);
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('publish-transition-1', 'local-stage-1',
                        'approve-and-ship-command-1', 1, 'LOCAL_REVIEW',
                        'PUBLISHING', 1, 'AUTHORIZE_PUBLISH', 'policy', 31)
                    """);
            execute(connection, authorizePublishReceiptSql(
                    "publish-receipt-1", "authorization-1"));
            execute(connection, """
                    INSERT INTO publish_operation(
                        id, publish_authorization_id, local_development_stage_id,
                        task_id, task_epoch, stage_generation, operation_id,
                        semantic_attempt, code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('publish-operation-1', 'authorization-1',
                        'local-stage-1', 'task-1', 1, 1,
                        'publish-operation-id-1', 1, 'fp-1', 'head-1',
                        'base-1', 'REQUESTED', 32)
                    """);
            insertPublishSteps(connection);
            insertPublishTicket(connection);
            execute(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);
        }

        migrate(url, "238");
        try (Connection connection = connect(url)) {
            assertFails(connection, probeStepSql(1, 99));
            execute(connection, claimStepSql(1, 100));
            assertFails(connection, expiredProbeStepSql(1, 199));
            assertFails(connection, expiredExecuteStepSql(1, 200));
            execute(connection, expiredProbeStepSql(1, 200));
            assertThat(text(connection, """
                    SELECT claim_mode || '|' || attempt_count
                    FROM publish_effect_step
                    WHERE publish_operation_id = 'publish-operation-1'
                      AND ordinal = 1
                    """)).isEqualTo("PROBE|2");
            execute(connection, """
                    UPDATE publish_effect_step
                    SET status = 'FAILED', claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        last_error = 'probe proved remote effect absent',
                        completed_at_ms = 201
                    WHERE publish_operation_id = 'publish-operation-1'
                      AND ordinal = 1
                    """);
            execute(connection, claimStepSql(1, 202));
            execute(connection, completeStepSql(1, 203));

            execute(connection, claimStepSql(2, 204));
            execute(connection, failStepSql(2, 205));
            execute(connection, claimStepSql(2, 206));
            execute(connection, failStepSql(2, 207));
            execute(connection, claimStepSql(2, 208));
            execute(connection, expiredProbeStepSql(2, 308));
            assertThat(number(connection, """
                    SELECT attempt_count FROM publish_effect_step
                    WHERE publish_operation_id = 'publish-operation-1'
                      AND ordinal = 2
                    """)).isEqualTo(4);
            execute(connection, completeStepSql(2, 309));
        }
    }

    @Test
    void canonicalFeedbackBatchDrivesRealStageManagerAndIsolatesNewerRevision()
            throws Exception
    {
        String url = migrated("typed-feedback.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
            execute(connection, """
                    INSERT INTO local_review_submission(
                        id, task_id, pr_id, submission_seq, root_ids_json,
                        root_snapshot_json, submitted_through_ms, created_at_ms)
                    VALUES ('submission-1', 'task-1', 'pr-1', 1,
                        '["thread-1"]', '{"thread-1":"first comment"}', 20, 20)
                    """);
            execute(connection, """
                    INSERT INTO local_review_submission(
                        id, task_id, pr_id, submission_seq, root_ids_json,
                        root_snapshot_json, submitted_through_ms, created_at_ms)
                    VALUES ('submission-2', 'task-1', 'pr-1', 2,
                        '["thread-1"]', '{"thread-1":"second comment"}', 22, 22)
                    """);
            execute(connection, """
                    INSERT INTO local_review_thread(
                        id, pr_id, task_id, local_development_stage_id,
                        task_epoch, stage_generation, scope, source,
                        created_by, created_at_ms)
                    VALUES ('thread-1', 'pr-1', 'task-1', 'local-stage-1',
                        1, 1, 'PR', 'USER', 'user', 20)
                    """);
            execute(connection, commentRevisionSql(
                    "revision-1", 1, null, "first comment", "digest-1", "SUBMITTED", 20));
            execute(connection, feedbackBatchSql("batch-1", "submission-1", 1, 21));
            execute(connection, feedbackItemSql(
                    "batch-1", "revision-1", "digest-1", "first comment", 21));
            execute(connection, commentRevisionSql(
                    "revision-2", 2, "revision-1", "second comment",
                    "digest-2", "SUBMITTED", 22));
            execute(connection, feedbackBatchSql("batch-2", "submission-2", 2, 23));
            execute(connection, feedbackItemSql(
                    "batch-2", "revision-2", "digest-2", "second comment", 23));
        }

        migrate(url, "230");
        migrate(url, "230");
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM local_feedback_batch
                    WHERE content_digest IS NULL
                    """)).isEqualTo(2);
            freezeBatch(connection, "batch-1", 24);
            String firstDigest = text(connection, """
                    SELECT content_digest FROM local_feedback_batch WHERE id = 'batch-1'
                    """);
            freezeBatch(connection, "batch-2", 25);
            String secondDigest = text(connection, """
                    SELECT content_digest FROM local_feedback_batch WHERE id = 'batch-2'
                    """);
            assertThat(firstDigest).isNotEqualTo(secondDigest);
            assertThat(text(connection, """
                    SELECT content_digest FROM local_feedback_batch WHERE id = 'batch-1'
                    """)).isEqualTo(firstDigest);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }

        // V2StageStore's result-request evidence became part of the manager
        // contract in V235; bring this real-store command fixture to that
        // additive schema before exercising the manager.
        migrate(url, "235");

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        try (StageStores stores = stageStores(dataSource)) {
            TaskCommandExecutor commands = new TaskCommandExecutor(
                    new DataSourceTransactionManager(dataSource));
            LocalDevelopmentStageManager manager = new LocalDevelopmentStageManager(
                    commands, stores.stage(), stores.evidence());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String firstDigest = jdbc.queryForObject("""
                    SELECT content_digest FROM local_feedback_batch WHERE id = 'batch-1'
                    """, String.class);
            String secondDigest = jdbc.queryForObject("""
                    SELECT content_digest FROM local_feedback_batch WHERE id = 'batch-2'
                    """, String.class);
            StageManager.Command stage = new StageManager.Command(
                    "submit-feedback", "user", "task-1", 1,
                    "local-stage-1", 1, 0);
            assertThatThrownBy(() -> manager.submitLocalFeedback(
                    new LocalDevelopmentStageManager.FeedbackCommand(
                            stage, "batch-1", "submission-1", secondDigest)))
                    .isInstanceOf(CommandRejectedException.class);
            assertThatThrownBy(() -> manager.submitLocalFeedback(
                    new LocalDevelopmentStageManager.FeedbackCommand(
                            new StageManager.Command(
                                    "stale-generation", "user", "task-1", 1,
                                    "local-stage-1", 2, 0),
                            "batch-1", "submission-1", firstDigest)))
                    .isInstanceOf(CommandRejectedException.class);

            var applied = manager.submitLocalFeedback(
                    new LocalDevelopmentStageManager.FeedbackCommand(
                            stage, "batch-1", "submission-1", firstDigest));
            assertThat(applied.state().checkpoint())
                    .isEqualTo(StageCheckpoint.ADDRESSING_LOCAL_FEEDBACK);
            assertThat(manager.submitLocalFeedback(
                    new LocalDevelopmentStageManager.FeedbackCommand(
                            stage, "batch-1", "submission-1", firstDigest))
                    .disposition().name()).isEqualTo("DUPLICATE");
            assertThat(stores.evidence().findLocalFeedback(
                    "task-1", "local-stage-1", 1, "batch-2")).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM stage_command_receipt WHERE command_id = ?",
                    Integer.class, "submit-feedback")).isOne();
        }
    }

    @Test
    void upgradesPopulatedV227AndKeepsLegacyStoresWritableAcrossRestart()
            throws Exception
    {
        String url = url("legacy-upgrade.db");
        migrate(url, "227");
        try (Connection connection = connect(url)) {
            seedLegacyRows(connection);
        }

        migrate(url, "228");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT workflow_version FROM dev_report WHERE id = 'legacy-report'
                    """)).isEqualTo("LEGACY");
            assertThat(text(connection, """
                    SELECT local_development_stage_id FROM dev_report
                    WHERE id = 'legacy-report'
                    """)).isNull();
            execute(connection, """
                    UPDATE dev_report
                    SET summary = 'updated through the legacy upsert path',
                        decisions_json = '[{"what":"keep legacy writable"}]',
                        created_at_ms = 10
                    WHERE id = 'legacy-report'
                    """);
            assertThat(text(connection, """
                    SELECT summary FROM dev_report WHERE id = 'legacy-report'
                    """)).isEqualTo("updated through the legacy upsert path");
            assertThat(number(connection, "SELECT COUNT(*) FROM validation_pass")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM local_review_submission")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM local_review_brain_handoff")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM task_push_authorization")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM publish_authorization")).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void v2WorktreeLeaseRequiresTheProvisionedOrEstablishedTaskPath()
            throws Exception
    {
        String provisioningUrl = migrated("worktree-provisioning.db");
        try (Connection connection = connect(provisioningUrl)) {
            seedProvisioningLeaseOwners(connection);
            assertFails(connection, worktreeLeaseSql(
                    "task-provision-2", "provision-operation-2", 2,
                    "/tmp/provision-1"));
            assertFails(connection, worktreeLeaseSql(
                    "task-provision-1", "provision-operation-1", 1,
                    "/tmp/wrong-path"));
            execute(connection, worktreeLeaseSql(
                    "task-provision-1", "provision-operation-1", 1,
                    "/tmp/provision-1"));
        }

        String establishedUrl = migrated("worktree-established.db");
        try (Connection connection = connect(establishedUrl)) {
            seedApprovedLocalSubject(connection);
            insertEstablishedWorktreeCapacity(connection);
            assertFails(connection, worktreeLeaseSql(
                    "task-1", "established-operation-1", 10,
                    "/tmp/stale-task-path"));
            execute(connection, worktreeLeaseSql(
                    "task-1", "established-operation-1", 10,
                    "/tmp/task-1"));
        }
    }

    @Test
    void localEvidenceIsExactAndFeedbackTurnCannotResolveANewerRevision()
            throws Exception
    {
        String url = migrated("local-feedback.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);

            assertFails(connection, """
                    UPDATE dev_report SET implemented_intent = 'substituted'
                    WHERE id = 'report-1'
                    """);
            assertFails(connection, """
                    INSERT INTO validation_evidence(
                        id, validation_operation_id, validation_pass_id,
                        task_id, task_epoch, stage_id, stage_generation,
                        code_fingerprint, head_sha, base_sha, passed,
                        evidence, completed_at_ms)
                    VALUES ('stale-evidence', 'validation-operation-1', 1,
                        'task-1', 1, 'local-stage-1', 1,
                        'wrong-fingerprint', 'head-1', 'base-1', 1,
                        'wrong subject', 12)
                    """);

            execute(connection, """
                    INSERT INTO local_review_thread(
                        id, pr_id, task_id, local_development_stage_id,
                        task_epoch, stage_generation, scope, source,
                        created_by, created_at_ms)
                    VALUES ('thread-1', 'pr-1', 'task-1', 'local-stage-1',
                        1, 1, 'PR', 'USER', 'user', 20)
                    """);
            execute(connection, commentRevisionSql(
                    "revision-1", 1, null, "first comment", "digest-1", "PENDING", 20));
            execute(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, stage_id, owner_kind, owner_id,
                        subject_revision, blocker_type, status, opened_at_ms)
                    VALUES ('feedback-blocker-1', 'task-1', 'local-stage-1',
                        'STAGE', 'local-stage-1', 'revision-1',
                        'LOCAL_FEEDBACK_OPEN', 'OPEN', 20)
                    """);
            execute(connection, """
                    INSERT INTO local_feedback_batch(
                        id, local_development_stage_id, task_id, task_epoch,
                        stage_generation, pr_id, dev_report_id, sequence,
                        code_fingerprint, head_sha, base_sha, status, created_at_ms)
                    VALUES ('batch-1', 'local-stage-1', 'task-1', 1, 1,
                        'pr-1', 'report-1', 1, 'fp-1', 'head-1', 'base-1',
                        'BUILDING', 21)
                    """);
            execute(connection, """
                    INSERT INTO local_feedback_batch_item(
                        batch_id, position, thread_id, comment_revision_id,
                        body_digest, frozen_body, frozen_thread_content,
                        selected_by, selected_at_ms)
                    VALUES ('batch-1', 1, 'thread-1', 'revision-1', 'digest-1',
                        'first comment', 'thread snapshot revision one', 'user', 21)
                    """);
            assertFails(connection, """
                    UPDATE local_feedback_batch
                    SET status = 'FROZEN', frozen_at_ms = 22 WHERE id = 'batch-1'
                    """);
            execute(connection, """
                    UPDATE local_review_comment_revision
                    SET state = 'SUBMITTED', state_version = 1,
                        state_changed_at_ms = 22
                    WHERE id = 'revision-1'
                    """);
            execute(connection, """
                    UPDATE local_feedback_batch
                    SET status = 'FROZEN', frozen_at_ms = 22 WHERE id = 'batch-1'
                    """);
            assertFails(connection, """
                    UPDATE local_feedback_batch_item SET frozen_body = 'changed'
                    WHERE batch_id = 'batch-1' AND position = 1
                    """);

            execute(connection, """
                    INSERT INTO stage_turn(
                        id, stage_id, stage_generation, purpose, status,
                        operation_id, attempt, task_epoch,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, delivery_lane, launch_input,
                        requested_at_ms)
                    VALUES ('feedback-turn-1', 'local-stage-1', 1,
                        'ADDRESS_LOCAL_FEEDBACK', 'REQUESTED',
                        'feedback-turn-operation-1', 1, 1,
                        'fp-1', 'head-1', 'base-1', 'CLI', 'batch-1', 23)
                    """);
            execute(connection, """
                    UPDATE local_feedback_batch SET stage_turn_id = 'feedback-turn-1'
                    WHERE id = 'batch-1'
                    """);
            execute(connection, """
                    UPDATE local_feedback_batch SET status = 'DISPATCHED'
                    WHERE id = 'batch-1'
                    """);
            execute(connection, commentRevisionSql(
                    "revision-2", 2, "revision-1", "newer reply", "digest-2", "PENDING", 24));
            execute(connection, """
                    UPDATE stage_turn
                    SET status = 'SUCCEEDED', started_at_ms = 23, finished_at_ms = 25
                    WHERE id = 'feedback-turn-1'
                    """);
            execute(connection, """
                    UPDATE local_review_comment_revision
                    SET state = 'ADDRESSED', state_version = 2,
                        state_changed_at_ms = 25, terminal_at_ms = 25
                    WHERE id = 'revision-1'
                    """);
            execute(connection, """
                    UPDATE local_feedback_batch
                    SET status = 'ADDRESSED', completed_at_ms = 25
                    WHERE id = 'batch-1'
                    """);

            assertThat(text(connection, """
                    SELECT state FROM local_review_comment_revision WHERE id = 'revision-1'
                    """)).isEqualTo("ADDRESSED");
            assertThat(text(connection, """
                    SELECT state FROM local_review_comment_revision WHERE id = 'revision-2'
                    """)).isEqualTo("PENDING");
            assertFails(connection, """
                    UPDATE local_review_comment_revision
                    SET state = 'ADDRESSED', state_version = 1,
                        state_changed_at_ms = 26, terminal_at_ms = 26
                    WHERE id = 'revision-2'
                    """);
        }
    }

    @Test
    void publishSagaRequiresFrozenAuthorizationOrderedEffectsAndExactRemoteBinding()
            throws Exception
    {
        String url = migrated("publish-saga.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);

            assertFails(connection, """
                    INSERT INTO publish_override(
                        id, local_development_stage_id, task_id, task_epoch,
                        stage_generation, dev_report_id, code_fingerprint,
                        head_sha, base_sha, actor_kind, actor_id, reason, created_at_ms)
                    VALUES ('automation-override', 'local-stage-1', 'task-1', 1, 1,
                        'report-1', 'fp-1', 'head-1', 'base-1', 'AUTOMATION',
                        'scheduler', 'automation must not override', 30)
                    """);
            assertFails(connection, manifestSql("dirty-manifest", 1, 0));
            execute(connection, manifestSql("manifest-1", 1, 1));
            insertStandingPublishAuthorization(connection);
            assertFails(connection, """
                    UPDATE publish_authorization SET head_sha = 'substituted'
                    WHERE id = 'authorization-1'
                    """);
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('publish-transition-1', 'local-stage-1',
                        'approve-and-ship-command-1', 1, 'LOCAL_REVIEW',
                        'PUBLISHING', 1, 'AUTHORIZE_PUBLISH', 'policy', 31)
                    """);
            assertFails(connection, authorizePublishReceiptSql(
                    "publish-receipt-without-proof", null));
            execute(connection, authorizePublishReceiptSql(
                    "publish-receipt-1", "authorization-1"));
            execute(connection, """
                    INSERT INTO publish_operation(
                        id, publish_authorization_id, local_development_stage_id,
                        task_id, task_epoch, stage_generation, operation_id,
                        semantic_attempt, code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('publish-operation-1', 'authorization-1', 'local-stage-1',
                        'task-1', 1, 1, 'publish-operation-id-1', 1,
                        'fp-1', 'head-1', 'base-1', 'REQUESTED', 32)
                    """);
            assertFails(connection, """
                    UPDATE publish_operation
                    SET remote_pr_number = 99, result_evidence = 'preseeded'
                    WHERE id = 'publish-operation-1'
                    """);
            assertFails(connection, """
                    INSERT INTO publish_effect_step(
                        id, publish_operation_id, ordinal, kind, idempotency_key,
                        status, attempt_limit, evidence, completed_at_ms)
                    VALUES ('preseeded-step', 'publish-operation-1', 1,
                        'VERIFY_SUBJECT', 'preseeded-step-key', 'REQUESTED', 3,
                        'evidence before claim', 32)
                    """);
            insertPublishSteps(connection);
            assertFails(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);
            insertPublishTicket(connection);
            execute(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);
            assertFails(connection, claimStepSql(2, 1));

            execute(connection, claimStepSql(1, 1));
            execute(connection, """
                    UPDATE publish_effect_step
                    SET status = 'FAILED', claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        last_error = 'transient failure', completed_at_ms = 2
                    WHERE publish_operation_id = 'publish-operation-1' AND ordinal = 1
                    """);
            assertFails(connection, """
                    UPDATE publish_effect_step
                    SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                        claim_mode = 'EXECUTE', claim_owner = 'worker',
                        claimed_at_ms = 3, lease_until_ms = 103
                    WHERE publish_operation_id = 'publish-operation-1' AND ordinal = 1
                    """);
            execute(connection, claimStepSql(1, 3));
            execute(connection, completeStepSql(1, 4));

            execute(connection, claimStepSql(2, 5));
            execute(connection, """
                    UPDATE publish_effect_step
                    SET status = 'INDETERMINATE', claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        last_error = 'remote outcome unknown', completed_at_ms = 6
                    WHERE publish_operation_id = 'publish-operation-1' AND ordinal = 2
                    """);
            assertFails(connection, claimStepSql(2, 7));
            execute(connection, probeStepSql(2, 7));
            execute(connection, completeStepSql(2, 8));

            for (int ordinal = 3; ordinal <= 6; ordinal++) {
                execute(connection, claimStepSql(ordinal, ordinal));
                execute(connection, completeStepSql(ordinal, ordinal));
            }
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = 'published',
                        pending_result_evidence = 'remote head fetched',
                        pending_result_task_epoch = 1,
                        pending_result_stage_id = 'local-stage-1',
                        pending_result_stage_generation = 1,
                        pending_result_operation_id = 'publish-operation-id-1',
                        pending_result_attempt = 1,
                        pending_result_expected_code_fingerprint = 'fp-1',
                        pending_result_expected_head_sha = 'head-1',
                        pending_result_expected_base_sha = 'base-1'
                    WHERE id = 'publish-ticket-1'
                    """);
            assertFails(connection, """
                    UPDATE publish_operation
                    SET status = 'SUCCEEDED', remote_repository_id = 'acme/widget',
                        remote_pr_number = 42, remote_pr_url = 'https://example/pr/42',
                        remote_head_ref = 'dev/task-1', remote_head_sha = 'wrong-head',
                        remote_base_sha = 'base-1', result_evidence = 'proof',
                        completed_at_ms = 50
                    WHERE id = 'publish-operation-1'
                    """);
            execute(connection, """
                    UPDATE publish_operation
                    SET status = 'SUCCEEDED', remote_repository_id = 'acme/widget',
                        remote_pr_number = 42, remote_pr_url = 'https://example/pr/42',
                        remote_head_ref = 'dev/task-1', remote_head_sha = 'head-1',
                        remote_base_sha = 'base-1', result_evidence = 'proof',
                        completed_at_ms = 50
                    WHERE id = 'publish-operation-1'
                    """);
            assertFails(connection, remoteBindingSql("wrong-binding", "wrong-head"));
            execute(connection, remoteBindingSql("binding-1", "head-1"));
            execute(connection, """
                    UPDATE publish_authorization
                    SET consumed_at_ms = 51, outcome = 'PUBLISHED'
                    WHERE id = 'authorization-1'
                    """);
            assertThat(text(connection, """
                    SELECT remote_head_sha FROM remote_pr_binding WHERE id = 'binding-1'
                    """)).isEqualTo("head-1");
            assertFails(connection, """
                    UPDATE remote_pr_binding SET remote_pr_number = 43 WHERE id = 'binding-1'
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            execute(connection, "DELETE FROM tasks WHERE id = 'task-1'");
            assertThat(number(connection, "SELECT COUNT(*) FROM publish_operation")).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM remote_pr_binding")).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void v229RejectsSupersetLaneMasksForValidationAndPublish()
            throws Exception
    {
        String url = url("exact-operation-lanes.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
        }
        migrate(url, "229");

        try (Connection connection = connect(url)) {
            execute(connection, manifestSql("manifest-1", 1, 1));
            insertStandingPublishAuthorization(connection);
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('publish-transition-1', 'local-stage-1',
                        'approve-and-ship-command-1', 1, 'LOCAL_REVIEW',
                        'PUBLISHING', 1, 'AUTHORIZE_PUBLISH', 'policy', 31)
                    """);
            execute(connection, authorizePublishReceiptSql(
                    "publish-receipt-1", "authorization-1"));
            execute(connection, """
                    INSERT INTO publish_operation(
                        id, publish_authorization_id, local_development_stage_id,
                        task_id, task_epoch, stage_generation, operation_id,
                        semantic_attempt, code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('publish-operation-1', 'authorization-1',
                        'local-stage-1', 'task-1', 1, 1,
                        'publish-operation-id-1', 1, 'fp-1', 'head-1',
                        'base-1', 'REQUESTED', 32)
                    """);
            insertPublishSteps(connection);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES ('publish-ticket-extra-lane', 'publish-operation-id-1',
                        'PUBLISH_LOCAL_DEVELOPMENT', 'GITHUB_EFFECT', 'STAGE',
                        'local-stage-1', 'STAGE_PUBLISH_RESULT', 49, 1, 1,
                        'workspace-1', 'trunk-1', 'task-1', 1,
                        'local-stage-1', 1, 1, 'fp-1', 'head-1', 'base-1',
                        'REQUESTED', 32)
                    """);
            assertFails(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);

            execute(connection, """
                    INSERT INTO validation_operation(
                        id, local_development_stage_id, task_id, task_epoch,
                        stage_generation, dev_report_id, operation_id,
                        semantic_attempt, code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('validation-operation-extra-lane', 'local-stage-1',
                        'task-1', 1, 1, 'report-1',
                        'validation-operation-id-extra-lane', 2,
                        'fp-1', 'head-1', 'base-1', 'REQUESTED', 33)
                    """);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES ('validation-ticket-extra-lane',
                        'validation-operation-id-extra-lane',
                        'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                        'local-stage-1', 'STAGE_VALIDATION_RESULT', 5, 1, 0,
                        'workspace-1', 'trunk-1', 'task-1', 1,
                        'local-stage-1', 1, 2, 'fp-1', 'head-1', 'base-1',
                        'REQUESTED', 33)
                    """);
            assertFails(connection, """
                    UPDATE validation_operation SET status = 'DISPATCHED'
                    WHERE id = 'validation-operation-extra-lane'
                    """);

            execute(connection, """
                    INSERT INTO validation_operation(
                        id, local_development_stage_id, task_id, task_epoch,
                        stage_generation, dev_report_id, operation_id,
                        semantic_attempt, code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('validation-operation-writer', 'local-stage-1',
                        'task-1', 1, 1, 'report-1',
                        'validation-operation-id-writer', 3,
                        'fp-1', 'head-1', 'base-1', 'REQUESTED', 34)
                    """);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES ('validation-ticket-writer',
                        'validation-operation-id-writer',
                        'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                        'local-stage-1', 'STAGE_VALIDATION_RESULT', 4, 1, 1,
                        'workspace-1', 'trunk-1', 'task-1', 1,
                        'local-stage-1', 1, 3, 'fp-1', 'head-1', 'base-1',
                        'REQUESTED', 34)
                    """);
            assertFails(connection, """
                    UPDATE validation_operation SET status = 'DISPATCHED'
                    WHERE id = 'validation-operation-writer'
                    """);
        }
    }

    @Test
    void v239RequiresTheExactLocalStagePublishCallback()
            throws Exception
    {
        String url = migrated("exact-publish-callback.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
        }
        migrate(url, "239");

        try (Connection connection = connect(url)) {
            execute(connection, manifestSql("manifest-1", 1, 1));
            insertStandingPublishAuthorization(connection);
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('publish-transition-1', 'local-stage-1',
                        'approve-and-ship-command-1', 1, 'LOCAL_REVIEW',
                        'PUBLISHING', 1, 'AUTHORIZE_PUBLISH', 'policy', 31)
                    """);
            execute(connection, authorizePublishReceiptSql(
                    "publish-receipt-1", "authorization-1"));
            execute(connection, """
                    INSERT INTO publish_operation(
                        id, publish_authorization_id, local_development_stage_id,
                        task_id, task_epoch, stage_generation, operation_id,
                        semantic_attempt, code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('publish-operation-1', 'authorization-1',
                        'local-stage-1', 'task-1', 1, 1,
                        'publish-operation-id-1', 1, 'fp-1', 'head-1',
                        'base-1', 'REQUESTED', 32)
                    """);
            insertPublishSteps(connection);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES ('publish-ticket-wrong', 'publish-operation-id-1',
                        'PUBLISH_LOCAL_DEVELOPMENT', 'GITHUB_EFFECT', 'STAGE',
                        'local-stage-1', 'WRONG_ROUTE', 48, 1, 1,
                        'workspace-1', 'trunk-1', 'task-1', 1,
                        'local-stage-1', 1, 1, 'fp-1', 'head-1', 'base-1',
                        'REQUESTED', 32)
                    """);
            assertFails(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);

            execute(connection, "DELETE FROM dispatch_ticket WHERE id = 'publish-ticket-wrong'");
            insertPublishTicket(connection);
            execute(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);
            assertThat(text(connection, """
                    SELECT status FROM publish_operation
                    WHERE id = 'publish-operation-1'
                    """)).isEqualTo("DISPATCHED");
        }
    }

    @Test
    void v241AcceptsOnlyAnExactTerminalPublishDeliveryReceipt()
            throws Exception
    {
        String url = migrated("publish-delivery-receipt.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
        }
        migrate(url, "241");

        try (Connection connection = connect(url)) {
            seedSuccessfulPublish(connection);
            assertFails(connection, """
                    INSERT INTO publish_delivery_receipt(
                        operation_id, raw_result_digest, outcome, acceptance,
                        remote_stage_id, delivered_at_ms)
                    VALUES ('publish-operation-id-1', 'raw-digest',
                        'SUCCEEDED', 'ACCEPTED', NULL, 60)
                    """);
            execute(connection, """
                    INSERT INTO publish_delivery_receipt(
                        operation_id, raw_result_digest, outcome, acceptance,
                        remote_stage_id, delivered_at_ms)
                    VALUES ('publish-operation-id-1', 'raw-digest',
                        'SUCCEEDED', 'SUPERSEDED', NULL, 60)
                    """);
            assertFails(connection, """
                    UPDATE publish_delivery_receipt
                    SET raw_result_digest = 'different'
                    WHERE operation_id = 'publish-operation-id-1'
                    """);
            assertThat(text(connection, """
                    SELECT outcome || '|' || acceptance
                    FROM publish_delivery_receipt
                    WHERE operation_id = 'publish-operation-id-1'
                    """)).isEqualTo("SUCCEEDED|SUPERSEDED");
        }
    }

    @Test
    void sqlitePublishDeliveryStoreTerminalizesTheExactFailedOperation()
            throws Exception
    {
        String url = migrated("publish-delivery-store.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
        }
        migrate(url, "241");
        try (Connection connection = connect(url)) {
            seedDispatchedFailedPublish(connection);
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        SqlitePublishResultStore store = new SqlitePublishResultStore(
                new JdbcTemplate(dataSource));

        var context = store.requireContext("publish-operation-id-1");
        store.completeFailure(
                context, DispatchTicket.Outcome.FAILED,
                "definite GitHub rejection", Instant.ofEpochMilli(50));

        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT status || '|' || error_message
                    FROM publish_operation
                    WHERE id = 'publish-operation-1'
                    """)).isEqualTo("FAILED|definite GitHub rejection");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM publish_authorization
                    WHERE id = 'authorization-1'
                      AND revoked_at_ms = 50
                      AND consumed_at_ms IS NULL
                    """)).isEqualTo(1);
        }
    }

    @Test
    void canceledAndSupersededAuthorizationsRequireMatchingTerminalOperations()
            throws Exception
    {
        for (String outcome : new String[] {"CANCELED", "SUPERSEDED"}) {
            String url = migrated("authorization-" + outcome + ".db");
            try (Connection connection = connect(url)) {
                seedApprovedLocalSubject(connection);
                execute(connection, manifestSql("manifest-1", 1, 1));
                insertStandingPublishAuthorization(connection);
                execute(connection, """
                        INSERT INTO publish_operation(
                            id, publish_authorization_id, local_development_stage_id,
                            task_id, task_epoch, stage_generation, operation_id,
                            semantic_attempt, code_fingerprint, expected_head_sha,
                            expected_base_sha, status, requested_at_ms)
                        VALUES ('publish-operation-1', 'authorization-1',
                            'local-stage-1', 'task-1', 1, 1,
                            'publish-operation-id-1', 1, 'fp-1', 'head-1',
                            'base-1', 'REQUESTED', 32)
                        """);
                assertFails(connection, """
                        UPDATE publish_authorization
                        SET consumed_at_ms = 33, outcome = '%s'
                        WHERE id = 'authorization-1'
                        """.formatted(outcome));
                execute(connection, """
                        UPDATE publish_operation
                        SET status = '%s', completed_at_ms = 34,
                            error_message = 'terminal publish decision'
                        WHERE id = 'publish-operation-1'
                        """.formatted(outcome));
                String wrongOutcome = outcome.equals("CANCELED") ? "SUPERSEDED" : "CANCELED";
                assertFails(connection, """
                        UPDATE publish_authorization
                        SET consumed_at_ms = 35, outcome = '%s'
                        WHERE id = 'authorization-1'
                        """.formatted(wrongOutcome));
                execute(connection, """
                        UPDATE publish_authorization
                        SET consumed_at_ms = 35, outcome = '%s'
                        WHERE id = 'authorization-1'
                        """.formatted(outcome));
                assertThat(text(connection, """
                        SELECT outcome FROM publish_authorization
                        WHERE id = 'authorization-1'
                        """)).isEqualTo(outcome);
            }
        }
    }

    @Test
    void forkManifestFreezesQualifiedHeadAndUpstreamBase()
            throws Exception
    {
        String url = migrated("fork-manifest.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection, true);
            assertFails(connection, manifestSql("wrong-direct-manifest", 1, 1));
            assertFails(connection, forkManifestSql("bare-fork-manifest", "dev/task-1"));
            execute(connection, forkManifestSql("fork-manifest-1", "jack:dev/task-1"));
            assertThat(text(connection, """
                    SELECT base_repository_id || '|' || head_repository_id || '|' || head_ref
                    FROM promotion_manifest WHERE id = 'fork-manifest-1'
                    """)).isEqualTo("acme/widget|jack/widget|jack:dev/task-1");
        }
    }

    @Test
    void commandReceiptsReplayOldSnapshotsAndDoNotConflateSupersededCommands()
            throws Exception
    {
        String url = migrated("command-receipts.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
            execute(connection, taskReceiptSql("task-superseded-1", "task-command-1"));
            execute(connection, taskReceiptSql("task-superseded-2", "task-command-2"));
            assertFails(connection, taskReceiptSql(
                    "task-duplicate", "task-command-duplicate")
                    .replace("'SUPERSEDED'", "'DUPLICATE'"));

            execute(connection, """
                    UPDATE threads SET lifecycle_state = 'IDLE', aggregate_version = 1
                    WHERE id = 'trunk-1'
                    """);
            execute(connection, """
                    INSERT INTO trunk_transition(
                        id, trunk_id, command_id, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('trunk-transition-1', 'trunk-1', 'trunk-command-1',
                        'ACTIVE', 'IDLE', 1, 'MARK_IDLE', 'user', 60)
                    """);
            execute(connection, trunkReceiptSql("trunk-applied-1", "trunk-command-1"));

            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'VALIDATING'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, stageReceiptSql(
                    "stage-superseded-1", "stage-command-1", "SUPERSEDED",
                    "VALIDATING", 1));
            execute(connection, stageReceiptSql(
                    "stage-superseded-2", "stage-command-2", "SUPERSEDED",
                    "VALIDATING", 1));

            execute(connection, """
                    UPDATE stage SET version = 2, checkpoint = 'BRAIN_REVIEW'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('transition-1', 'local-stage-1', 'stage-command-applied', 1,
                        'VALIDATING', 'BRAIN_REVIEW', 2,
                        'ACCEPT_VALIDATION', 'user', 60)
                    """);
            execute(connection, stageReceiptSql(
                    "stage-applied", "stage-command-applied", "APPLIED",
                    "BRAIN_REVIEW", 2));
            execute(connection, """
                    UPDATE stage SET version = 3, checkpoint = 'LOCAL_REVIEW'
                    WHERE id = 'local-stage-1'
                    """);

            assertThat(number(connection, """
                    SELECT COUNT(*) FROM stage_command_receipt
                    WHERE disposition = 'SUPERSEDED' AND returned_version = 1
                    """)).isEqualTo(2);
            assertThat(text(connection, """
                    SELECT returned_checkpoint FROM stage_command_receipt
                    WHERE id = 'stage-applied'
                    """)).isEqualTo("BRAIN_REVIEW");
            assertThat(text(connection, """
                    SELECT expected_stage_version FROM stage_command_receipt
                    WHERE id = 'stage-applied'
                    """)).isNull();
            assertThat(text(connection, """
                    SELECT checkpoint FROM stage WHERE id = 'local-stage-1'
                    """)).isEqualTo("LOCAL_REVIEW");
            assertFails(connection, stageReceiptSql(
                    "stage-duplicate", "stage-command-1", "SUPERSEDED",
                    "LOCAL_REVIEW", 3));
            assertFails(connection, """
                    UPDATE task_command_receipt SET returned_version = 99
                    WHERE id = 'task-superseded-1'
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM task_command_receipt")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM trunk_command_receipt")).isOne();
            assertThat(text(connection, """
                    SELECT returned_last_brain_operation_id || '|' || returned_lifecycle
                    FROM task_command_receipt WHERE id = 'task-superseded-1'
                    """)).isEqualTo("brain-turn-operation-1|ACTIVE");
        }
    }

    @Test
    void canceledCleanupOpensWaitingThenAcceptsItsSatisfiedBarrierAtomically()
            throws Exception
    {
        String url = migrated("canceled-cleanup.db");
        try (Connection connection = connect(url)) {
            seedApprovedLocalSubject(connection);
            execute(connection, """
                    UPDATE tasks
                    SET lifecycle_state = 'CANCELING', aggregate_version = 2
                    WHERE id = 'task-1'
                    """);
            execute(connection, """
                    INSERT INTO task_terminal_intent(
                        id, task_id, kind, source, accepted, recorded_at_ms)
                    VALUES ('cancel-intent-1', 'task-1', 'CANCELED',
                        'USER_CANCEL', 1, 60)
                    """);
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('cancel-request-transition-1', 'task-1',
                        'cancel-request-command-1', 1, 'ACTIVE', 'CANCELING',
                        2, 'REQUEST_CANCEL', 'user', 60)
                    """);
            execute(connection, """
                    INSERT INTO task_command_receipt(
                        id, task_id, command_id, cause, actor, disposition,
                        expected_task_epoch, expected_task_version,
                        returned_trunk_id, returned_lifecycle, returned_epoch,
                        returned_version, returned_current_stage_id,
                        returned_terminal_intent, recorded_at_ms)
                    VALUES ('cancel-request-receipt-1', 'task-1',
                        'cancel-request-command-1', 'REQUEST_CANCEL', 'user',
                        'APPLIED', 1, 1, 'trunk-1', 'CANCELING', 1, 2,
                        'local-stage-1', 'CANCELED', 60)
                    """);
            execute(connection, """
                    INSERT INTO task_quiescence_barrier(
                        id, task_id, task_epoch, reason, status, requested_at_ms)
                    VALUES ('cancel-barrier-1', 'task-1', 1, 'CANCEL',
                        'REQUESTED', 61)
                    """);
            execute(connection, """
                    UPDATE task_quiescence_barrier
                    SET status = 'SATISFIED', completed_at_ms = 62,
                        evidence = 'all Task work is quiescent'
                    WHERE id = 'cancel-barrier-1'
                    """);

            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE stage
                        SET version = 1, completed_at_ms = 63,
                            end_reason = 'TASK_CANCELED'
                        WHERE id = 'local-stage-1'
                        """);
                execute(connection, """
                        INSERT INTO stage_transition(
                            id, stage_id, command_id, generation,
                            from_checkpoint, to_checkpoint, stage_version,
                            cause, actor, occurred_at_ms)
                        VALUES ('cancel-seal-transition-1', 'local-stage-1',
                            'open-canceled-cleanup-command-1', 1,
                            'LOCAL_REVIEW', 'LOCAL_REVIEW', 1,
                            'SEAL_FOR_TASK_CANCELLATION', 'user', 63)
                        """);
                execute(connection, """
                        INSERT INTO stage_command_receipt(
                            id, stage_id, task_id, command_id, cause, actor,
                            disposition, expected_task_epoch,
                            expected_stage_generation, expected_stage_version,
                            proof_id, returned_kind, returned_generation,
                            returned_version, returned_checkpoint,
                            returned_end_reason, recorded_at_ms)
                        VALUES ('cancel-seal-receipt-1', 'local-stage-1', 'task-1',
                            'open-canceled-cleanup-command-1',
                            'SEAL_FOR_TASK_CANCELLATION', 'user', 'APPLIED',
                            1, 1, 0, 'cancel-barrier-1', 'LOCAL_DEVELOPMENT',
                            1, 1, 'LOCAL_REVIEW', 'TASK_CANCELED', 63)
                        """);
                execute(connection, """
                        INSERT INTO stage(
                            id, task_id, kind, generation, version,
                            checkpoint, opened_at_ms)
                        VALUES ('cleanup-stage-1', 'task-1', 'CLEANUP', 1, 0,
                            'WAITING_QUIESCENCE', 64)
                        """);
                execute(connection, """
                        UPDATE task_current_stage
                        SET stage_id = 'cleanup-stage-1', stage_generation = 1
                        WHERE task_id = 'task-1'
                        """);
                execute(connection, """
                        UPDATE tasks
                        SET lifecycle_state = 'CLEANING', aggregate_version = 3
                        WHERE id = 'task-1'
                        """);
                execute(connection, """
                        INSERT INTO task_transition(
                            id, task_id, command_id, epoch, from_state, to_state,
                            aggregate_version, cause, actor, occurred_at_ms)
                        VALUES ('open-cleanup-task-transition-1', 'task-1',
                            'open-canceled-cleanup-command-1', 1,
                            'CANCELING', 'CLEANING', 3,
                            'OPEN_CANCELED_CLEANUP', 'user', 64)
                        """);
                execute(connection, """
                        INSERT INTO task_command_receipt(
                            id, task_id, command_id, cause, actor, disposition,
                            expected_task_epoch, expected_task_version, proof_id,
                            next_stage_id, next_stage_kind, next_stage_generation,
                            returned_trunk_id, returned_lifecycle, returned_epoch,
                            returned_version, returned_current_stage_id,
                            returned_terminal_intent, recorded_at_ms)
                        VALUES ('open-cleanup-task-receipt-1', 'task-1',
                            'open-canceled-cleanup-command-1',
                            'OPEN_CANCELED_CLEANUP', 'user', 'APPLIED', 1, 2,
                            'cancel-barrier-1', 'cleanup-stage-1', 'CLEANUP', 1,
                            'trunk-1', 'CLEANING', 1, 3, 'cleanup-stage-1',
                            'CANCELED', 64)
                        """);
                execute(connection, """
                        INSERT INTO stage_transition(
                            id, stage_id, command_id, generation,
                            from_checkpoint, to_checkpoint, stage_version,
                            cause, actor, occurred_at_ms)
                        VALUES ('open-cleanup-stage-transition-1',
                            'cleanup-stage-1', 'open-canceled-cleanup-command-1',
                            1, NULL, 'WAITING_QUIESCENCE', 0,
                            'OPEN_CANCELED_CLEANUP', 'user', 64)
                        """);
                assertFails(connection, canceledCleanupOpeningReceiptSql(
                        "wrong-cleanup-opening", "wrong-barrier"));
                execute(connection, canceledCleanupOpeningReceiptSql(
                        "cleanup-opening-receipt-1", "cancel-barrier-1"));

                execute(connection, """
                        UPDATE stage SET version = 1, checkpoint = 'CLEANING'
                        WHERE id = 'cleanup-stage-1'
                        """);
                execute(connection, """
                        INSERT INTO stage_transition(
                            id, stage_id, command_id, generation,
                            from_checkpoint, to_checkpoint, stage_version,
                            cause, actor, occurred_at_ms)
                        VALUES ('cleanup-quiescence-transition-1',
                            'cleanup-stage-1',
                            'cancel-cleanup-quiescence/open-canceled-cleanup-command-1',
                            1, 'WAITING_QUIESCENCE', 'CLEANING', 1,
                            'ACCEPT_CLEANUP_QUIESCENCE', 'user', 65)
                        """);
                assertFails(connection, cleanupQuiescenceReceiptSql(
                        "wrong-cleanup-quiescence", null));
                execute(connection, cleanupQuiescenceReceiptSql(
                        "cleanup-quiescence-receipt-1", "WAITING_QUIESCENCE"));
                connection.commit();
            }
            catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
            finally {
                connection.setAutoCommit(true);
            }

            assertThat(text(connection, """
                    SELECT checkpoint || '|' || version
                    FROM stage WHERE id = 'cleanup-stage-1'
                    """)).isEqualTo("CLEANING|1");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM stage_command_receipt
                    WHERE stage_id = 'cleanup-stage-1'
                      AND cause IN ('OPEN_CANCELED_CLEANUP',
                          'ACCEPT_CLEANUP_QUIESCENCE')
                    """)).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    private String url(String file)
    {
        return "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
    }

    private String migrated(String file)
    {
        String url = url(file);
        migrate(url, "228");
        return url;
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

    private static void seedProvisioningLeaseOwners(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-provision', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-provision', 'CLI_AGENT', 'claude-code', 'Trunk',
                    'IDLE', 'claude-sonnet-4.6', 0, 0, 0, 1, 1,
                    'workspace-provision', 'build', 2, 'V2', 'ACTIVE')
                """);
        for (int index = 1; index <= 2; index++) {
            execute(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                        created_by, created_at_ms)
                    VALUES ('assignment-provision-%1$s', 'trunk-provision',
                        'NEW_FROM_TRUNK', 'base-%1$s', 'seed', 'build it', 'user', 2)
                    """.formatted(index));
            execute(connection, """
                    INSERT INTO task_policy_revision(
                        id, trunk_id, revision, source, auto_approve,
                        created_by, created_at_ms)
                    VALUES ('policy-provision-%1$s', 'trunk-provision', %1$s,
                        'TRUNK', 0, 'user', 2)
                    """.formatted(index));
            execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms,
                        workflow_version, lifecycle_state, assignment_id,
                        policy_revision_id)
                    VALUES ('task-provision-%1$s', 'trunk-provision', %1$s,
                        'IDLE', 'PLANNING', 2, 'V2', 'PROVISIONING',
                        'assignment-provision-%1$s', 'policy-provision-%1$s')
                    """.formatted(index));
            execute(connection, """
                    INSERT INTO task_creation_context(
                        task_id, assignment_id, policy_revision_id, provenance,
                        repository_id, publish_repository_id, planning_base_sha,
                        engine_snapshot, work_model_snapshot, created_at_ms)
                    VALUES ('task-provision-%1$s', 'assignment-provision-%1$s',
                        'policy-provision-%1$s', 'DIRECT_USER', 'acme/widget',
                        'acme/widget', 'base-%1$s', 'engine-v1', 'model-v1', 3)
                    """.formatted(index));
            execute(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_base_sha,
                        requested_branch_name, requested_worktree_path,
                        status, created_at_ms)
                    VALUES ('provision-%1$s', 'task-provision-%1$s', 1,
                        'assignment-provision-%1$s', 'provision-operation-%1$s',
                        1, 'acme/widget', 'base-%1$s', 'dev/provision-%1$s',
                        '/tmp/provision-%1$s', 'REQUESTED', 4)
                    """.formatted(index));
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, attempt, expected_base_sha,
                        status, created_at_ms)
                    VALUES ('provision-ticket-%1$s', 'provision-operation-%1$s',
                        'PROVISION_TASK', 'LOCAL_GIT', 'TASK',
                        'task-provision-%1$s', 'TASK_PROVISION_RESULT', 16, 1, 1,
                        'workspace-provision', 'trunk-provision',
                        'task-provision-%1$s', 1, 1, 'base-%1$s', 'REQUESTED', 4)
                    """.formatted(index));
            execute(connection, """
                    UPDATE provision_task_operation SET status = 'DISPATCHED'
                    WHERE id = 'provision-%s'
                    """.formatted(index));
            execute(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch, holder,
                        fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-provision-%1$s', 'provision-ticket-%1$s',
                        'provision-operation-%1$s', 'V2', 16, 0, 1, 1,
                        'workspace-provision', 'trunk-provision',
                        'task-provision-%1$s', 1, 'worker-%1$s', %1$s, 5, 5, 100)
                    """.formatted(index));
        }
    }

    private static void insertEstablishedWorktreeCapacity(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('established-ticket-1', 'established-operation-1',
                    'LOCAL_WORKTREE_WRITE', 'LOCAL_GIT', 'STAGE',
                    'local-stage-1', 'STAGE_LOCAL_RESULT', 16, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1,
                    1, 'fp-1', 'head-1', 'base-1', 'REQUESTED', 30)
                """);
        execute(connection, """
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('established-capacity-1', 'established-ticket-1',
                    'established-operation-1', 'V2', 16, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'worker-10',
                    10, 30, 30, 100)
                """);
    }

    private static String worktreeLeaseSql(
            String taskId, String operationId, int fencingToken, String path)
    {
        return """
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES ('%s', '%s', 'CLI_AGENT', 31, 90, 'V2', '%s', 1, %s,
                    'worker-%s')
                """.formatted(path, taskId, operationId, fencingToken, fencingToken);
    }

    private static void seedLegacyRows(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('legacy-workspace', 'Legacy', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version)
                VALUES ('legacy-trunk', 'CLI_AGENT', 'claude-code', 'Legacy trunk',
                    'IDLE', 'claude-sonnet-4.6', 0, 0, 0, 1, 1,
                    'legacy-workspace', 'build', 1, 'LEGACY')
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms, workflow_version)
                VALUES ('legacy-task', 'legacy-trunk', 1, 'IDLE', 'PLANNING', 2, 'LEGACY')
                """);
        execute(connection, """
                INSERT INTO dev_report(
                    id, task_id, summary, decisions_json, invariants_json,
                    tricky_spots_json, test_map_json, followups_json, created_at_ms)
                VALUES ('legacy-report', 'legacy-task', 'legacy summary', '[]', '[]',
                    '[]', '[]', '[]', 3)
                """);
        execute(connection, """
                INSERT INTO validation_pass(
                    task_id, started_at_ms, claim_key, code_fingerprint)
                VALUES ('legacy-task', 3, 'legacy-validation', 'legacy-fp')
                """);
        execute(connection, """
                INSERT INTO local_review_submission(
                    id, task_id, pr_id, submission_seq, root_ids_json,
                    root_snapshot_json, submitted_through_ms, created_at_ms)
                VALUES ('legacy-submission', 'legacy-task', 'legacy-pr', 1,
                    '["root"]', '{"root":"body"}', 3, 3)
                """);
        execute(connection, """
                INSERT INTO local_review_brain_handoff(
                    validation_claim_key, task_id, through_sequence,
                    code_fingerprint, created_at_ms)
                VALUES ('legacy-handoff', 'legacy-task', 1, 'legacy-fp', 3)
                """);
        execute(connection, """
                INSERT INTO task_push_authorization(
                    token, task_id, pr_id, head_sha, code_fingerprint, actor,
                    basis_kind, payload_json, payload_digest, created_at_ms)
                VALUES ('legacy-push', 'legacy-task', 'legacy-pr', 'legacy-head',
                    'legacy-fp', 'user', 'LEGACY', '{}', 'digest', 3)
                """);
    }

    private static void seedApprovedLocalSubject(Connection connection)
            throws SQLException
    {
        seedApprovedLocalSubject(connection, false);
    }

    private static void seedApprovedLocalSubject(Connection connection, boolean fork)
            throws SQLException
    {
        seedV2TaskAndLocalReport(connection, fork);
        execute(connection, """
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('validation-operation-1', 'local-stage-1', 'task-1', 1, 1,
                    'report-1', 'validation-operation-id-1', 1,
                    'fp-1', 'head-1', 'base-1', 'REQUESTED', 10)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-ticket-1', 'validation-operation-id-1',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                    'local-stage-1', 'STAGE_VALIDATION_RESULT', 4, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1, 1,
                    'fp-1', 'head-1', 'base-1', 'REQUESTED', 10)
                """);
        execute(connection, """
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = 'validation-operation-1'
                """);
        execute(connection, """
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, fix_rounds,
                    failures_json, claim_key, code_fingerprint, workflow_version,
                    task_epoch, stage_id, stage_generation, operation_id,
                    semantic_attempt, expected_head_sha, expected_base_sha)
                VALUES ('task-1', 10, 11, 1, 0, '[]', 'validation-claim-1',
                    'fp-1', 'V2', 1, 'local-stage-1', 1,
                    'validation-operation-id-1', 1, 'head-1', 'base-1')
                """);
        execute(connection, """
                INSERT INTO validation_evidence(
                    id, validation_operation_id, validation_pass_id,
                    task_id, task_epoch, stage_id, stage_generation,
                    code_fingerprint, head_sha, base_sha, passed,
                    evidence, completed_at_ms)
                VALUES ('validation-evidence-1', 'validation-operation-1', 1,
                    'task-1', 1, 'local-stage-1', 1,
                    'fp-1', 'head-1', 'base-1', 1, 'all checks passed', 11)
                """);
        execute(connection, """
                UPDATE validation_operation
                SET status = 'COMPLETED', completed_at_ms = 11
                WHERE id = 'validation-operation-1'
                """);
        execute(connection, """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input, requested_at_ms)
                VALUES ('brain-turn-1', 'task-1', 'DEVELOPMENT_BRAIN_REVIEW',
                    'REQUESTED', 'brain-turn-operation-1', 1, 1,
                    'local-stage-1', 1, 'fp-1', 'head-1', 'base-1',
                    'API', 'review report-1', 12)
                """);
        execute(connection, """
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('brain-episode-1', 'brain-1', 'task-1', 1,
                    'local-stage-1', 1, 'report-1', 'validation-evidence-1',
                    'brain-turn-1', 1, 'fp-1', 'head-1', 'base-1',
                    'REQUESTED', 12)
                """);
        execute(connection, """
                UPDATE task_turn
                SET status = 'SUCCEEDED', started_at_ms = 12, finished_at_ms = 13
                WHERE id = 'brain-turn-1'
                """);
        execute(connection, """
                UPDATE brain_review_episode
                SET status = 'SUCCEEDED', verdict = 'APPROVED',
                    verdict_summary = 'approved', completed_at_ms = 13
                WHERE id = 'brain-episode-1'
                """);
    }

    private static void seedV2TaskAndLocalReport(Connection connection, boolean fork)
            throws SQLException
    {
        String upstreamRepository = fork ? "'acme/widget'" : "NULL";
        String publishRepository = fork ? "jack/widget" : "acme/widget";
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
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1',
                    'build', 2, 'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build it', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 1, 'user', 2)
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        execute(connection, """
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, upstream_repository_id, publish_repository_id,
                    planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES ('task-1', 'assignment-1', 'policy-1', 'DIRECT_USER',
                    'acme/widget', %s, '%s', 'base-1',
                    'engine-v1', 'model-v1', 3)
                """.formatted(upstreamRepository, publishRepository));
        execute(connection, """
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-1', 'task-1', 'openai', 'review-model', 'engine-v1', 3)
                """);
        execute(connection, """
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-1', 'task-1', 1, 'assignment-1',
                    'provision-operation-1', 1, 'acme/widget', 'base-1',
                    'dev/task-1', '/tmp/task-1', 'REQUESTED', 4)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES ('provision-ticket-1', 'provision-operation-1',
                    'PROVISION_TASK', 'LOCAL_GIT', 'TASK', 'task-1',
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 1, 'base-1', 'REQUESTED', 4)
                """);
        execute(connection, """
                UPDATE provision_task_operation SET status = 'DISPATCHED'
                WHERE id = 'provision-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'local branch exists',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = 'provision-operation-1',
                    pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = 'provision-ticket-1'
                """);
        execute(connection, """
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-1',
                    result_head_sha = 'base-1', result_code_fingerprint = 'fp-initial',
                    completed_at_ms = 5
                WHERE id = 'provision-1'
                """);
        execute(connection, """
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    upstream_repository_id, publish_repository_id,
                    branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES ('task-1', 'provision-1', 'acme/widget', %s, '%s',
                    'dev/task-1', '/tmp/task-1', 'base-1', 'base-1',
                    'fp-initial', 5, 5)
                """.formatted(upstreamRepository, publishRepository));
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('local-stage-1', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'LOCAL_REVIEW', 6)
                """);
        execute(connection, """
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'local-stage-1', 1)
                """);
        execute(connection, """
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        execute(connection, """
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('local-stage-1', 'task-1', 1, 1)
                """);
        execute(connection, """
                INSERT INTO pr(
                    id, task_id, branch_name, base_branch, title, description,
                    status, created_at_ms, origin)
                VALUES ('pr-1', 'task-1', 'dev/task-1', 'main',
                    'Implement feature', 'Description', 'local-open', 6, 'task')
                """);
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES ('development-turn-1', 'local-stage-1', 1,
                    'IMPLEMENT_LOCAL_DEVELOPMENT', 'SUCCEEDED',
                    'development-turn-operation-1', 1, 1,
                    'fp-1', 'head-1', 'base-1', 'CLI', 'implement', 6, 6, 7)
                """);
        execute(connection, """
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns, context_refs)
                VALUES ('report-1', 'task-1', 'implemented feature', 7, 'V2',
                    'local-stage-1', 1, 1, 'development-turn-1', 1,
                    'fp-1', 'head-1', 'base-1', 'implement feature',
                    'one commit', 'two files', 'tests pending', 'none', 'none',
                    'turn:development-turn-1')
                """);
    }

    private static String commentRevisionSql(
            String id, int revision, String previousId, String body,
            String digest, String state, long at)
    {
        String previous = previousId == null ? "NULL" : "'" + previousId + "'";
        return """
                INSERT INTO local_review_comment_revision(
                    id, thread_id, task_id, local_development_stage_id,
                    task_epoch, stage_generation, dev_report_id, revision,
                    previous_revision_id, author_kind, body, body_digest,
                    code_fingerprint, head_sha, base_sha, state,
                    created_at_ms, state_changed_at_ms)
                VALUES ('%s', 'thread-1', 'task-1', 'local-stage-1',
                    1, 1, 'report-1', %s, %s, 'USER', '%s', '%s',
                    'fp-1', 'head-1', 'base-1', '%s', %s, %s)
                """.formatted(id, revision, previous, body, digest, state, at, at);
    }

    private static String feedbackBatchSql(
            String batchId, String submissionId, int sequence, long at)
    {
        return """
                INSERT INTO local_feedback_batch(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, pr_id, dev_report_id, source_submission_id,
                    sequence, code_fingerprint, head_sha, base_sha,
                    status, created_at_ms)
                VALUES ('%s', 'local-stage-1', 'task-1', 1, 1,
                    'pr-1', 'report-1', '%s', %s, 'fp-1', 'head-1', 'base-1',
                    'BUILDING', %s)
                """.formatted(batchId, submissionId, sequence, at);
    }

    private static String feedbackItemSql(
            String batchId, String revisionId, String bodyDigest,
            String body, long at)
    {
        return """
                INSERT INTO local_feedback_batch_item(
                    batch_id, position, thread_id, comment_revision_id,
                    body_digest, frozen_body, frozen_thread_content,
                    selected_by, selected_at_ms)
                VALUES ('%s', 1, 'thread-1', '%s', '%s', '%s',
                    'thread snapshot for %s', 'user', %s)
                """.formatted(batchId, revisionId, bodyDigest, body, revisionId, at);
    }

    private static void freezeBatch(Connection connection, String batchId, long at)
            throws SQLException
    {
        execute(connection, """
                UPDATE local_feedback_batch
                SET status = 'FROZEN', frozen_at_ms = %s,
                    content_digest = (
                        SELECT content_digest
                        FROM local_feedback_batch_digest_v230
                        WHERE batch_id = '%s')
                WHERE id = '%s'
                """.formatted(at, batchId, batchId));
    }

    private static StageStores stageStores(SQLiteDataSource dataSource)
    {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.scan("com.bytequay.app.developmentflow.stage.persistence");
        context.refresh();
        return new StageStores(
                context,
                context.getBean(StageManager.Store.class),
                context.getBean(LocalDevelopmentStageManager.EvidenceStore.class));
    }

    private static String manifestSql(String id, int revision, int clean)
    {
        return """
                INSERT INTO promotion_manifest(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, pr_id, policy_revision_id,
                    revision, code_fingerprint, head_sha, base_sha, route,
                    base_repository_id, head_repository_id, publish_repository_id,
                    branch_name, head_ref, base_branch, worktree_clean, commits_ahead,
                    branch_verified, base_verified, permission_clear, pr_title,
                    pr_body, pr_content_revision, pr_content_digest, created_at_ms)
                VALUES ('%s', 'local-stage-1', 'task-1', 1, 1, 'report-1',
                    'pr-1', 'policy-1', %s, 'fp-1', 'head-1', 'base-1',
                    'DIRECT', 'acme/widget', 'acme/widget', 'acme/widget',
                    'dev/task-1', 'dev/task-1', 'main', %s, 1, 1, 1, 1,
                    'Implement feature', 'Description', 1, 'pr-digest-1', 30)
                """.formatted(id, revision, clean);
    }

    private static void insertStandingPublishAuthorization(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO publish_authorization(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, manifest_id, dev_report_id,
                    validation_evidence_id, brain_review_episode_id,
                    pr_id, policy_revision_id, code_fingerprint, head_sha,
                    base_sha, route, base_repository_id, head_repository_id,
                    publish_repository_id, branch_name, head_ref, base_branch,
                    pr_content_revision, pr_content_digest, consent_kind,
                    consent_id, actor_id, brain_basis, authorized_operation_id,
                    authorized_attempt, created_at_ms)
                VALUES ('authorization-1', 'local-stage-1', 'task-1', 1, 1,
                    'manifest-1', 'report-1', 'validation-evidence-1',
                    'brain-episode-1', 'pr-1', 'policy-1', 'fp-1',
                    'head-1', 'base-1', 'DIRECT', 'acme/widget', 'acme/widget',
                    'acme/widget', 'dev/task-1', 'dev/task-1', 'main', 1,
                    'pr-digest-1', 'STANDING_TASK', 'policy-1', 'policy',
                    'APPROVED', 'publish-operation-id-1', 1, 31)
                """);
    }

    private static String forkManifestSql(String id, String headRef)
    {
        return """
                INSERT INTO promotion_manifest(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, pr_id, policy_revision_id,
                    revision, code_fingerprint, head_sha, base_sha, route,
                    base_repository_id, head_repository_id, publish_repository_id,
                    branch_name, head_ref, base_branch, worktree_clean, commits_ahead,
                    branch_verified, base_verified, permission_clear, pr_title,
                    pr_body, pr_content_revision, pr_content_digest, created_at_ms)
                VALUES ('%s', 'local-stage-1', 'task-1', 1, 1, 'report-1',
                    'pr-1', 'policy-1', 1, 'fp-1', 'head-1', 'base-1',
                    'FORK', 'acme/widget', 'jack/widget', 'jack/widget',
                    'dev/task-1', '%s', 'main', 1, 1, 1, 1, 1,
                    'Implement feature', 'Description', 1, 'pr-digest-1', 30)
                """.formatted(id, headRef);
    }

    private static void insertPublishSteps(Connection connection)
            throws SQLException
    {
        String[] kinds = {
                "VERIFY_SUBJECT",
                "RECONCILE_BRANCH_BASE",
                "PUSH_BRANCH",
                "CREATE_OR_ADOPT_DRAFT_PR",
                "FETCH_REMOTE_DETAIL",
                "PROVE_REMOTE_HEAD"};
        for (int i = 0; i < kinds.length; i++) {
            execute(connection, """
                    INSERT INTO publish_effect_step(
                        id, publish_operation_id, ordinal, kind, idempotency_key,
                        status, attempt_limit)
                    VALUES ('publish-step-%s', 'publish-operation-1', %s, '%s',
                        'publish-operation-1:%s', 'REQUESTED', 3)
                    """.formatted(i + 1, i + 1, kinds[i], kinds[i]));
        }
    }

    private static void insertPublishTicket(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('publish-ticket-1', 'publish-operation-id-1',
                    'PUBLISH_LOCAL_DEVELOPMENT', 'GITHUB_EFFECT', 'STAGE',
                    'local-stage-1', 'STAGE_PUBLISH_RESULT', 48, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1, 1,
                    'fp-1', 'head-1', 'base-1', 'REQUESTED', 32)
                """);
    }

    private static String claimStepSql(int ordinal, long at)
    {
        return """
                UPDATE publish_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = 'EXECUTE', claim_owner = 'worker',
                    claimed_at_ms = %s, lease_until_ms = %s,
                    evidence = NULL, last_error = NULL, completed_at_ms = NULL
                WHERE publish_operation_id = 'publish-operation-1' AND ordinal = %s
                """.formatted(at, at + 100, ordinal);
    }

    private static String probeStepSql(int ordinal, long at)
    {
        return """
                UPDATE publish_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = 'PROBE', claim_owner = 'worker',
                    claimed_at_ms = %s, lease_until_ms = %s,
                    evidence = NULL, last_error = NULL, completed_at_ms = NULL
                WHERE publish_operation_id = 'publish-operation-1' AND ordinal = %s
                """.formatted(at, at + 100, ordinal);
    }

    private static String expiredProbeStepSql(int ordinal, long at)
    {
        return """
                UPDATE publish_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = 'PROBE', claim_owner = 'recovery-worker',
                    claimed_at_ms = %s, lease_until_ms = %s,
                    evidence = NULL, last_error = NULL, completed_at_ms = NULL
                WHERE publish_operation_id = 'publish-operation-1' AND ordinal = %s
                """.formatted(at, at + 100, ordinal);
    }

    private static String expiredExecuteStepSql(int ordinal, long at)
    {
        return """
                UPDATE publish_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = 'EXECUTE', claim_owner = 'recovery-worker',
                    claimed_at_ms = %s, lease_until_ms = %s,
                    evidence = NULL, last_error = NULL, completed_at_ms = NULL
                WHERE publish_operation_id = 'publish-operation-1' AND ordinal = %s
                """.formatted(at, at + 100, ordinal);
    }

    private static String completeStepSql(int ordinal, long at)
    {
        return """
                UPDATE publish_effect_step
                SET status = 'SUCCEEDED', claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    evidence = 'step %s complete', last_error = NULL,
                    completed_at_ms = %s
                WHERE publish_operation_id = 'publish-operation-1' AND ordinal = %s
                """.formatted(ordinal, at + 1, ordinal);
    }

    private static String failStepSql(int ordinal, long at)
    {
        return """
                UPDATE publish_effect_step
                SET status = 'FAILED', claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    evidence = NULL, last_error = 'definite miss',
                    completed_at_ms = %s
                WHERE publish_operation_id = 'publish-operation-1' AND ordinal = %s
                """.formatted(at, ordinal);
    }

    private static String remoteBindingSql(String id, String headSha)
    {
        return """
                INSERT INTO remote_pr_binding(
                    id, task_id, pr_id, publish_operation_id,
                    publish_authorization_id, manifest_id, route,
                    base_repository_id, head_repository_id, remote_repository_id,
                    remote_pr_number, remote_pr_url, remote_head_ref,
                    remote_head_sha, remote_base_sha, evidence, bound_at_ms)
                VALUES ('%s', 'task-1', 'pr-1', 'publish-operation-1',
                    'authorization-1', 'manifest-1', 'DIRECT', 'acme/widget',
                    'acme/widget', 'acme/widget', 42, 'https://example/pr/42',
                    'dev/task-1', '%s', 'base-1', 'binding proof', 51)
                """.formatted(id, headSha);
    }

    private static void seedSuccessfulPublish(Connection connection)
            throws SQLException
    {
        execute(connection, manifestSql("manifest-1", 1, 1));
        insertStandingPublishAuthorization(connection);
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                WHERE id = 'local-stage-1'
                """);
        execute(connection, """
                INSERT INTO stage_transition(
                    id, stage_id, command_id, generation, from_checkpoint,
                    to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                VALUES ('publish-transition-1', 'local-stage-1',
                    'approve-and-ship-command-1', 1, 'LOCAL_REVIEW',
                    'PUBLISHING', 1, 'AUTHORIZE_PUBLISH', 'policy', 31)
                """);
        execute(connection, authorizePublishReceiptSql(
                "publish-receipt-1", "authorization-1"));
        execute(connection, """
                INSERT INTO publish_operation(
                    id, publish_authorization_id, local_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('publish-operation-1', 'authorization-1',
                    'local-stage-1', 'task-1', 1, 1,
                    'publish-operation-id-1', 1, 'fp-1', 'head-1',
                    'base-1', 'REQUESTED', 32)
                """);
        insertPublishSteps(connection);
        insertPublishTicket(connection);
        execute(connection, """
                UPDATE publish_operation SET status = 'DISPATCHED'
                WHERE id = 'publish-operation-1'
                """);
        for (int ordinal = 1; ordinal <= 6; ordinal++) {
            execute(connection, claimStepSql(ordinal, ordinal));
            execute(connection, completeStepSql(ordinal, ordinal));
        }
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'published',
                    pending_result_evidence = 'remote head fetched',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'local-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'publish-operation-id-1',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fp-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = 'publish-ticket-1'
                """);
        execute(connection, """
                UPDATE publish_operation
                SET status = 'SUCCEEDED', remote_repository_id = 'acme/widget',
                    remote_pr_number = 42, remote_pr_url = 'https://example/pr/42',
                    remote_head_ref = 'dev/task-1', remote_head_sha = 'head-1',
                    remote_base_sha = 'base-1', result_evidence = 'proof',
                    completed_at_ms = 50
                WHERE id = 'publish-operation-1'
                """);
        execute(connection, remoteBindingSql("binding-1", "head-1"));
        execute(connection, """
                UPDATE publish_authorization
                SET consumed_at_ms = 51, outcome = 'PUBLISHED'
                WHERE id = 'authorization-1'
                """);
    }

    private static void seedDispatchedFailedPublish(Connection connection)
            throws SQLException
    {
        execute(connection, manifestSql("manifest-1", 1, 1));
        insertStandingPublishAuthorization(connection);
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                WHERE id = 'local-stage-1'
                """);
        execute(connection, """
                INSERT INTO stage_transition(
                    id, stage_id, command_id, generation, from_checkpoint,
                    to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                VALUES ('publish-transition-1', 'local-stage-1',
                    'approve-and-ship-command-1', 1, 'LOCAL_REVIEW',
                    'PUBLISHING', 1, 'AUTHORIZE_PUBLISH', 'policy', 31)
                """);
        execute(connection, authorizePublishReceiptSql(
                "publish-receipt-1", "authorization-1"));
        execute(connection, """
                INSERT INTO publish_operation(
                    id, publish_authorization_id, local_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('publish-operation-1', 'authorization-1',
                    'local-stage-1', 'task-1', 1, 1,
                    'publish-operation-id-1', 1, 'fp-1', 'head-1',
                    'base-1', 'REQUESTED', 32)
                """);
        insertPublishSteps(connection);
        insertPublishTicket(connection);
        execute(connection, """
                UPDATE publish_operation SET status = 'DISPATCHED'
                WHERE id = 'publish-operation-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = 'failed',
                    pending_result_evidence = 'definite rejection',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'local-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'publish-operation-id-1',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fp-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1',
                    pending_result_error = 'definite GitHub rejection'
                WHERE id = 'publish-ticket-1'
                """);
    }

    private static String taskReceiptSql(String id, String commandId)
    {
        return """
                INSERT INTO task_command_receipt(
                    id, task_id, command_id, cause, actor, disposition,
                    subject_task_epoch, subject_stage_id,
                    subject_stage_generation, subject_operation_id,
                    subject_attempt, subject_expected_code_fingerprint,
                    subject_expected_head_sha, subject_expected_base_sha,
                    brain_verdict, returned_trunk_id, returned_lifecycle,
                    returned_epoch, returned_version, returned_current_stage_id,
                    returned_last_brain_verdict, returned_last_brain_task_epoch,
                    returned_last_brain_stage_id,
                    returned_last_brain_stage_generation,
                    returned_last_brain_operation_id,
                    returned_last_brain_attempt,
                    returned_last_brain_code_fingerprint,
                    returned_last_brain_head_sha, returned_last_brain_base_sha,
                    recorded_at_ms)
                VALUES ('%s', 'task-1', '%s', 'ACCEPT_BRAIN_VERDICT', 'user',
                    'SUPERSEDED', 1, 'local-stage-1', 1,
                    'brain-turn-operation-1', 1, 'fp-1', 'head-1', 'base-1',
                    'APPROVED', 'trunk-1', 'ACTIVE', 1, 1, 'local-stage-1',
                    'APPROVED', 1, 'local-stage-1', 1,
                    'brain-turn-operation-1', 1, 'fp-1', 'head-1', 'base-1', 60)
                """.formatted(id, commandId);
    }

    private static String trunkReceiptSql(String id, String commandId)
    {
        return """
                INSERT INTO trunk_command_receipt(
                    id, trunk_id, command_id, cause, actor, disposition,
                    expected_version, returned_lifecycle, returned_version,
                    recorded_at_ms)
                VALUES ('%s', 'trunk-1', '%s', 'MARK_IDLE', 'user',
                    'APPLIED', 0, 'IDLE', 1, 60)
                """.formatted(id, commandId);
    }

    private static String stageReceiptSql(
            String id, String commandId, String disposition,
            String returnedCheckpoint, int returnedVersion)
    {
        return """
                INSERT INTO stage_command_receipt(
                    id, stage_id, task_id, command_id, cause, actor, disposition,
                    subject_task_epoch, subject_stage_id,
                    subject_stage_generation, subject_operation_id,
                    subject_attempt, subject_expected_code_fingerprint,
                    subject_expected_head_sha, subject_expected_base_sha,
                    returned_kind, returned_generation, returned_version,
                    returned_checkpoint, recorded_at_ms)
                VALUES ('%s', 'local-stage-1', 'task-1', '%s',
                    'ACCEPT_VALIDATION', 'user', '%s', 1, 'local-stage-1', 1,
                    'validation-operation-id-1', 1, 'fp-1', 'head-1', 'base-1',
                    'LOCAL_DEVELOPMENT', 1, %s, '%s', 60)
                """.formatted(
                id,
                commandId,
                disposition,
                returnedVersion,
                returnedCheckpoint);
    }

    private static String authorizePublishReceiptSql(String id, String proofId)
    {
        String proof = proofId == null ? "NULL" : "'" + proofId + "'";
        return """
                INSERT INTO stage_command_receipt(
                    id, stage_id, task_id, command_id, cause, actor, disposition,
                    expected_task_epoch, expected_stage_generation,
                    expected_stage_version, source_checkpoint,
                    subject_task_epoch, subject_stage_id,
                    subject_stage_generation, subject_operation_id,
                    subject_attempt, subject_expected_code_fingerprint,
                    subject_expected_head_sha, subject_expected_base_sha, proof_id,
                    returned_kind, returned_generation, returned_version,
                    returned_checkpoint, returned_pending_task_epoch,
                    returned_pending_stage_id, returned_pending_stage_generation,
                    returned_pending_operation_id, returned_pending_attempt,
                    returned_pending_code_fingerprint, returned_pending_head_sha,
                    returned_pending_base_sha, recorded_at_ms)
                VALUES ('%s', 'local-stage-1', 'task-1',
                    'approve-and-ship-command-1', 'AUTHORIZE_PUBLISH', 'policy',
                    'APPLIED', 1, 1, 0, 'LOCAL_REVIEW', 1, 'local-stage-1', 1,
                    'publish-operation-id-1', 1, 'fp-1', 'head-1', 'base-1', %s,
                    'LOCAL_DEVELOPMENT', 1, 1, 'PUBLISHING', 1,
                    'local-stage-1', 1, 'publish-operation-id-1', 1,
                    'fp-1', 'head-1', 'base-1', 31)
                """.formatted(id, proof);
    }

    private static String canceledCleanupOpeningReceiptSql(String id, String proofId)
    {
        return """
                INSERT INTO stage_command_receipt(
                    id, stage_id, task_id, command_id, cause, actor, disposition,
                    proof_id, returned_kind, returned_generation,
                    returned_version, returned_checkpoint, recorded_at_ms)
                VALUES ('%s', 'cleanup-stage-1', 'task-1',
                    'open-canceled-cleanup-command-1',
                    'OPEN_CANCELED_CLEANUP', 'user', 'APPLIED', '%s',
                    'CLEANUP', 1, 0, 'WAITING_QUIESCENCE', 64)
                """.formatted(id, proofId);
    }

    private static String cleanupQuiescenceReceiptSql(String id, String sourceCheckpoint)
    {
        String source = sourceCheckpoint == null ? "NULL" : "'" + sourceCheckpoint + "'";
        return """
                INSERT INTO stage_command_receipt(
                    id, stage_id, task_id, command_id, cause, actor, disposition,
                    expected_task_epoch, expected_stage_generation,
                    expected_stage_version, source_checkpoint, proof_id,
                    returned_kind, returned_generation, returned_version,
                    returned_checkpoint, recorded_at_ms)
                VALUES ('%s', 'cleanup-stage-1', 'task-1',
                    'cancel-cleanup-quiescence/open-canceled-cleanup-command-1',
                    'ACCEPT_CLEANUP_QUIESCENCE', 'user', 'APPLIED',
                    1, 1, 0, %s, 'cancel-barrier-1',
                    'CLEANUP', 1, 1, 'CLEANING', 65)
                """.formatted(id, source);
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

    private record StageStores(
            AnnotationConfigApplicationContext context,
            StageManager.Store stage,
            LocalDevelopmentStageManager.EvidenceStore evidence)
            implements AutoCloseable
    {
        @Override
        public void close()
        {
            context.close();
        }
    }
}
