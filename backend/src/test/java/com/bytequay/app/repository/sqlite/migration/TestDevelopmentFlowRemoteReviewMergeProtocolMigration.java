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
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowRemoteReviewMergeProtocolMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void marksDraftReadyOnlyThroughFreshManualOrAutoApproveConsent()
            throws Exception
    {
        String url = remoteUrl("mark-ready.db", 1);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertPolicy(connection, 1, 1, 1, 0, 1, 0, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "DRAFT",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertGreenCi(connection, 1, 1, "head-1", "base-1");

            assertFails(connection, markReadyAuthorizationSql(
                    "ready-auto-blocked", 1, 1, "AUTO_APPROVE_POLICY", null));
            execute(connection, markReadyAuthorizationSql(
                    "ready-manual", 1, 1, "MANUAL", "user-1"));
            assertFails(connection, """
                    UPDATE remote_mark_ready_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'ready-manual'
                    """);
            execute(connection, markReadyOperationSql("ready-operation", "ready-manual", 1));
            assertFails(connection, claimMarkReadySql("ready-operation", "EXECUTE", 81));
            execute(connection, """
                    UPDATE remote_mark_ready_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'ready-manual'
                    """);
            execute(connection, claimMarkReadySql("ready-operation", "EXECUTE", 81));
            assertFails(connection, """
                    UPDATE remote_mark_ready_operation
                    SET claim_owner = 'other-worker'
                    WHERE id = 'ready-operation'
                    """);
            assertFails(connection, """
                    UPDATE remote_mark_ready_operation
                    SET evidence = 'fabricated result'
                    WHERE id = 'ready-operation'
                    """);
            assertFails(connection, claimMarkReadySql("ready-operation", "PROBE", 180));
            assertFails(connection, claimMarkReadySql("ready-operation", "EXECUTE", 181));
            execute(connection, claimMarkReadySql("ready-operation", "PROBE", 181));
            execute(connection, """
                    UPDATE remote_mark_ready_operation
                    SET status = 'AWAITING_OBSERVATION', claim_mode = NULL,
                        claim_owner = NULL, claimed_at_ms = NULL, lease_until_ms = NULL
                    WHERE id = 'ready-operation'
                    """);
            assertFails(connection, """
                    UPDATE remote_mark_ready_operation
                    SET status = 'SUCCEEDED', result_snapshot_id = 'snapshot-1-1',
                        evidence = 'still draft', completed_at_ms = 82
                    WHERE id = 'ready-operation'
                    """);

            insertSnapshot(connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            execute(connection, """
                    UPDATE remote_mark_ready_operation
                    SET status = 'SUCCEEDED', result_snapshot_id = 'snapshot-1-2',
                        evidence = 'observer reports open', completed_at_ms = 82
                    WHERE id = 'ready-operation'
                    """);
            assertFails(connection, """
                    UPDATE remote_mark_ready_authorization SET actor_id = 'other'
                    WHERE id = 'ready-manual'
                    """);

            assertFails(connection, policySql(1, 2, 0, 1, 0, 0, 1));
            execute(connection, policySql(1, 2, 1, 1, 0, 0, 1));
            insertSnapshot(connection, 1, 3, "head-1", "base-1", "DRAFT",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 3, "head-1", "base-1");
            insertGreenCi(connection, 1, 3, "head-1", "base-1");
            execute(connection, markReadyAuthorizationSql(
                    "ready-auto", 1, 3, "AUTO_APPROVE_POLICY", null));
            assertThat(text(connection, """
                    SELECT authority_kind FROM remote_mark_ready_authorization
                    WHERE id = 'ready-auto'
                    """)).isEqualTo("AUTO_APPROVE_POLICY");
        }
    }

    @Test
    void greenCiMarksDraftReadyWithoutAutoApprove()
            throws Exception
    {
        String url = remoteUrl("policy-mark-ready.db", 1);
        migrate(url);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertPolicy(connection, 1, 1, 0, 0, 0, 0, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "DRAFT",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertGreenCi(connection, 1, 1, "head-1", "base-1");

            execute(connection, markReadyAuthorizationSql(
                    "ready-without-auto-approve", 1, 1,
                    "AUTO_APPROVE_POLICY", null));
        }
    }

    @Test
    void freezesTypedFeedbackAndRecoversAuthorizedEffectsWithoutSecondConsent()
            throws Exception
    {
        String url = remoteUrl("feedback.db", 1);
        try (Connection connection = connect(url)) {
            seedOpenGreen(connection, 1, "UNSUPPORTED");
            execute(connection, inboxVerdictSql("verdict-1", "review-1", "EXTERNAL", 0));
            execute(connection, """
                    INSERT INTO remote_inbox_item(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id, remote_pr_snapshot_id,
                        kind, external_key, external_revision, head_sha, base_sha,
                        actor_login, provenance, ignored, comment_id, body, body_digest,
                        observed_at_ms)
                    VALUES ('own-reply', 'remote-stage-1', 'task-1', 1, 1,
                        'binding-1', 'snapshot-1-1', 'TOP_LEVEL_COMMENT',
                        'comment-own', 1, 'head-1', 'base-1', 'app', 'OWN_REPLY', 1,
                        'comment-own', 'mirrored', 'own-digest', 75)
                    """);
            assertFails(connection, inboxVerdictSql(
                    "duplicate-verdict", "review-1", "EXTERNAL", 0));

            execute(connection, batchSql("batch-1", 1, 1, 1));
            assertFails(connection, batchItemSql("batch-1", 1, "own-reply",
                    "TOP_LEVEL_COMMENT", "mirrored", "own-digest"));
            execute(connection, batchItemSql("batch-1", 1, "verdict-1",
                    "REVIEW_VERDICT", "please fix the null path", "verdict-digest"));
            execute(connection, """
                    UPDATE remote_feedback_batch
                    SET status = 'FROZEN', content_digest = 'batch-digest',
                        frozen_at_ms = 80
                    WHERE id = 'batch-1'
                    """);
            execute(connection, """
                    UPDATE remote_feedback_batch SET status = 'ADDRESSING'
                    WHERE id = 'batch-1'
                    """);
            insertFeedbackStageTurn(connection);
            insertFeedbackValidationPass(connection);
            execute(connection, validationEvidenceSql());
            assertFails(connection, feedbackAuthorizationSql(null));
            insertFeedbackBrainTurn(connection);
            execute(connection, brainEvidenceSql());
            execute(connection, """
                    UPDATE remote_feedback_batch SET status = 'AWAITING_APPROVAL'
                    WHERE id = 'batch-1'
                    """);
            assertFails(connection, feedbackAuthorizationSql("feedback-brain-1")
                    .replace("'USER_ACTION'", "'AUTO_APPROVE_POLICY'"));
            execute(connection, feedbackAuthorizationSql("feedback-brain-1"));

            assertFails(connection, effectSql(
                    "untyped-review", 1, "SUBMIT_REVIEW", null, null));
            assertFails(connection, effectSql(
                    "wrong-kind", 1, "POST_INLINE_REPLY", "verdict-1", null));
            execute(connection, effectSql(
                    "reply-step", 1, "POST_TOP_LEVEL_REPLY", "verdict-1", null));
            execute(connection, effectSql(
                    "push-step", 2, "PUSH_COMMITS", null, null));
            execute(connection, """
                    UPDATE remote_feedback_batch SET status = 'AUTHORIZED'
                    WHERE id = 'batch-1'
                    """);
            execute(connection, """
                    UPDATE remote_feedback_batch SET status = 'APPLYING'
                    WHERE id = 'batch-1'
                    """);
            assertFails(connection, claimEffectSql("push-step", "EXECUTE", 91));
            execute(connection, claimEffectSql("reply-step", "EXECUTE", 91));
            assertFails(connection, """
                    UPDATE remote_feedback_effect_step
                    SET lease_until_ms = 999
                    WHERE id = 'reply-step'
                    """);
            assertFails(connection, """
                    UPDATE remote_feedback_effect_step
                    SET external_effect_id = 'fabricated-effect',
                        evidence = 'fabricated result'
                    WHERE id = 'reply-step'
                    """);
            assertFails(connection, claimEffectSql("reply-step", "PROBE", 190));
            assertFails(connection, claimEffectSql("reply-step", "EXECUTE", 191));
            execute(connection, claimEffectSql("reply-step", "PROBE", 191));
            execute(connection, finishEffectSql(
                    "reply-step", "INDETERMINATE", null, "reply outcome unknown", 192));
        }

        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_feedback_authorization
                    WHERE remote_feedback_batch_id = 'batch-1'
                    """)).isOne();
            assertFails(connection, claimEffectSql("reply-step", "EXECUTE", 93));
            execute(connection, claimEffectSql("reply-step", "PROBE", 93));
            execute(connection, finishEffectSql(
                    "reply-step", "SUCCEEDED", "reply-remote-1", "reply exists", 94));
            execute(connection, claimEffectSql("push-step", "EXECUTE", 95));
            execute(connection, finishEffectSql(
                    "push-step", "SUCCEEDED", "head-fixed", "remote head moved", 96));
            assertFails(connection, """
                    UPDATE remote_feedback_batch
                    SET status = 'COMPLETED', completed_at_ms = 97
                    WHERE id = 'batch-1'
                    """);

            execute(connection, inboxCommentSql("comment-2", "comment-2"));
            execute(connection, batchSql("batch-2", 2, 1, 0));
            execute(connection, batchItemSql("batch-2", 1, "comment-2",
                    "TOP_LEVEL_COMMENT", "one more thing", "comment-digest"));
            insertSnapshot(connection, 1, 2, "head-fixed", "base-1", "OPEN",
                    "UNKNOWN", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-fixed", "base-1");
            execute(connection, """
                    UPDATE remote_feedback_batch
                    SET status = 'COMPLETED', result_head_sha = 'head-fixed',
                        result_snapshot_id = 'snapshot-1-2', completed_at_ms = 97
                    WHERE id = 'batch-1'
                    """);
            assertFails(connection, """
                    UPDATE remote_feedback_effect_step SET evidence = 'changed'
                    WHERE id = 'reply-step'
                    """);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_feedback_batch
                    WHERE remote_development_stage_id = 'remote-stage-1'
                    """)).isEqualTo(2);
        }
    }

    @Test
    void failsClosedOnUnknownQueueCapabilityAndInvalidatesConsentOnNewHead()
            throws Exception
    {
        String url = remoteUrl("readiness.db", 1);
        try (Connection connection = connect(url)) {
            seedOpenGreen(connection, 1, "UNKNOWN");
            execute(connection, policySql(1, 1, 1, 1, 0, 0, 2));
            assertFails(connection, readinessSql(1, 1, "automation-1-1",
                    "UNKNOWN", "MERGEABLE", 1));
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_readiness_evidence
                    WHERE remote_development_stage_id = 'remote-stage-1'
                    """)).isZero();

            insertSnapshot(connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            insertGreenCi(connection, 1, 2, "head-1", "base-1");
            execute(connection, readinessSql(1, 2, "automation-1-1",
                    "SUPPORTED", "MERGEABLE", 1));
            execute(connection, mergeAuthorizationSql("merge-auth-2", 1, 2));
            assertFails(connection, mergeOperationSql(
                    "unsafe-direct", "merge-auth-2", 1,
                    "DIRECT", "UNSUPPORTED", 0));
            execute(connection, mergeOperationSql(
                    "queue-operation", "merge-auth-2", 1,
                    "MERGE_QUEUE", "SUPPORTED", 2));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 90
                    WHERE id = 'merge-auth-2'
                    """);
            insertSnapshot(connection, 1, 3, "head-new", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 3, "head-new", "base-1");
            assertFails(connection, mergeAttemptSql(
                    "stale-queue-attempt", "queue-operation", 1,
                    "ENTER_QUEUE", 1, "readiness-1-2", "EXECUTE", 91));
            assertFails(connection, """
                    UPDATE remote_merge_operation
                    SET status = 'CLAIMED', attempt_count = attempt_count + 1
                    WHERE id = 'queue-operation'
                    """);
            assertThat(text(connection, """
                    SELECT status FROM remote_merge_operation
                    WHERE id = 'queue-operation'
                    """)).isEqualTo("REQUESTED");
        }
    }

    @Test
    void boundsMergeQueueBouncesAndCompletesOnlyFromObservedMergedTruth()
            throws Exception
    {
        String url = remoteUrl("merge-queue.db", 2);
        try (Connection connection = connect(url)) {
            seedMergeReady(connection, 1, "SUPPORTED", 1);
            execute(connection, mergeAuthorizationSql("queue-auth", 1, 1));
            execute(connection, mergeOperationSql(
                    "queue-op", "queue-auth", 1, "MERGE_QUEUE", "SUPPORTED", 1));
            assertFails(connection, mergeAttemptSql(
                    "queue-attempt-1", "queue-op", 1, "ENTER_QUEUE", 1,
                    "readiness-1-1", "EXECUTE", 81));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'queue-auth'
                    """);
            assertFails(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'REVOKED', terminal_at_ms = 81
                    WHERE id = 'queue-auth'
                    """);
            authorizeStageForMerge(
                    connection, 1, "queue-op-operation-id", "queue-auth");
            execute(connection, mergeAttemptSql(
                    "queue-attempt-1", "queue-op", 1, "ENTER_QUEUE", 1,
                    "readiness-1-1", "EXECUTE", 81));
            execute(connection, claimMergeOperationSql("queue-op"));
            assertFails(connection, """
                    UPDATE remote_merge_effect_attempt
                    SET external_effect_id = 'fabricated-effect',
                        evidence = 'fabricated observation'
                    WHERE id = 'queue-attempt-1'
                    """);
            assertFails(connection, queueEntrySql(
                    "entry-1", "queue-attempt-1", 1, "snapshot-1-1",
                    "readiness-1-1"));

            insertSnapshot(connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "QUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            execute(connection, queueEntrySql(
                    "entry-1", "queue-attempt-1", 1, "snapshot-1-2",
                    "readiness-1-1"));
            execute(connection, finishMergeAttemptSql(
                    "queue-attempt-1", "SUCCEEDED", "snapshot-1-2",
                    "observer reports queued", 82));
            execute(connection, """
                    UPDATE remote_merge_operation SET status = 'QUEUE_ENTERED'
                    WHERE id = 'queue-op'
                    """);
            execute(connection, """
                    UPDATE remote_merge_operation SET status = 'AWAITING_OBSERVATION'
                    WHERE id = 'queue-op'
                    """);
            insertSnapshot(connection, 1, 3, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 3, "head-1", "base-1");
            assertFails(connection, observeQueueEntrySql(
                    "entry-1", "BOUNCED", "snapshot-1-3", 63));
            insertSnapshot(connection, 1, 4, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "DEQUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 4, "head-1", "base-1");
            execute(connection, observeQueueEntrySql(
                    "entry-1", "BOUNCED", "snapshot-1-4", 64));
            execute(connection, """
                    UPDATE remote_merge_operation SET queue_bounce_count = 1
                    WHERE id = 'queue-op'
                    """);
            assertFails(connection, mergeAttemptSql(
                    "queue-attempt-2", "queue-op", 2, "ENTER_QUEUE", 2,
                    "readiness-1-1", "EXECUTE", 91));
            insertGreenCi(connection, 1, 4, "head-1", "base-1");
            execute(connection, readinessSql(1, 4, "automation-1-1",
                    "SUPPORTED", "MERGEABLE", 1));
            execute(connection, mergeAttemptSql(
                    "queue-attempt-2", "queue-op", 2, "ENTER_QUEUE", 2,
                    "readiness-1-4", "EXECUTE", 91));
            execute(connection, claimMergeOperationSql("queue-op"));
            insertSnapshot(connection, 1, 5, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "QUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 5, "head-1", "base-1");
            execute(connection, queueEntrySql(
                    "entry-2", "queue-attempt-2", 2, "snapshot-1-5",
                    "readiness-1-4"));
            execute(connection, finishMergeAttemptSql(
                    "queue-attempt-2", "SUCCEEDED", "snapshot-1-5",
                    "observer reports requeued", 92));
            execute(connection, """
                    UPDATE remote_merge_operation SET status = 'QUEUE_ENTERED'
                    WHERE id = 'queue-op'
                    """);
            execute(connection, """
                    UPDATE remote_merge_operation SET status = 'AWAITING_OBSERVATION'
                    WHERE id = 'queue-op'
                    """);
            insertSnapshot(connection, 1, 6, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "DEQUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 6, "head-1", "base-1");
            execute(connection, observeQueueEntrySql(
                    "entry-2", "BOUNCED", "snapshot-1-6", 66));
            insertGreenCi(connection, 1, 6, "head-1", "base-1");
            execute(connection, readinessSql(1, 6, "automation-1-1",
                    "SUPPORTED", "MERGEABLE", 1));
            assertFails(connection, mergeAttemptSql(
                    "queue-attempt-3", "queue-op", 3, "ENTER_QUEUE", 3,
                    "readiness-1-6", "EXECUTE", 101));
            assertFails(connection, """
                    UPDATE remote_merge_operation SET queue_bounce_count = 2
                    WHERE id = 'queue-op'
                    """);
            execute(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, stage_id, owner_kind, owner_id,
                        subject_revision, blocker_type, status, opened_at_ms)
                    VALUES ('queue-blocker', 'task-1', 'remote-stage-1',
                        'OPERATION', 'queue-op', 'head-1',
                        'MERGE_QUEUE_REENQUEUE_EXHAUSTED', 'OPEN', 100)
                    """);
            execute(connection, """
                    UPDATE remote_merge_operation
                    SET status = 'BLOCKED',
                        block_reason = 'QUEUE_REENQUEUE_EXHAUSTED',
                        completed_at_ms = 101
                    WHERE id = 'queue-op'
                    """);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_terminal_intent
                    WHERE task_id = 'task-1' AND accepted = 1
                    """)).isZero();
            assertFails(connection, """
                    UPDATE remote_merge_queue_entry SET evidence = 'changed'
                    WHERE id = 'entry-2'
                    """);

            seedMergeReady(connection, 2, "UNSUPPORTED", 0);
            execute(connection, mergeAuthorizationSql("direct-auth", 2, 1));
            execute(connection, mergeOperationSql(
                    "direct-op", "direct-auth", 2, "DIRECT", "UNSUPPORTED", 0));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 110
                    WHERE id = 'direct-auth'
                    """);
            authorizeStageForMerge(
                    connection, 2, "direct-op-operation-id", "direct-auth");
            execute(connection, mergeAttemptSql(
                    "direct-attempt-1", "direct-op", 1, "DIRECT_MERGE", null,
                    "readiness-2-1", "EXECUTE", 111));
            execute(connection, claimMergeOperationSql("direct-op"));
            assertFails(connection, mergeAttemptSql(
                    "direct-attempt-2", "direct-op", 2, "DIRECT_MERGE", null,
                    "readiness-2-1", "PROBE", 210));
            assertFails(connection, mergeAttemptSql(
                    "direct-attempt-2", "direct-op", 2, "DIRECT_MERGE", null,
                    "readiness-2-1", "EXECUTE", 211));
            execute(connection, mergeAttemptSql(
                    "direct-attempt-2", "direct-op", 2, "DIRECT_MERGE", null,
                    "readiness-2-1", "PROBE", 211));
            execute(connection, claimMergeOperationSql("direct-op"));
            execute(connection, finishMergeAttemptSql(
                    "direct-attempt-2", "INDETERMINATE", null,
                    "probe outcome unknown", 212));
            execute(connection, mergeAttemptSql(
                    "direct-attempt-3", "direct-op", 3, "DIRECT_MERGE", null,
                    "readiness-2-1", "PROBE", 213));
            execute(connection, claimMergeOperationSql("direct-op"));
            execute(connection, finishMergeAttemptSql(
                    "direct-attempt-3", "AWAITING_OBSERVATION", null, null, 0));
            execute(connection, """
                    UPDATE remote_merge_operation SET status = 'AWAITING_OBSERVATION'
                    WHERE id = 'direct-op'
                    """);
            insertSnapshot(connection, 2, 2, "head-2", "base-2", "MERGED",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 2, 2, "head-2", "base-2");
            execute(connection, finishMergeAttemptSql(
                    "direct-attempt-3", "SUCCEEDED", "snapshot-2-2",
                    "observer reports merged", 120));
            execute(connection, """
                    INSERT INTO task_terminal_intent(
                        id, task_id, kind, source, source_id, observed_head_sha,
                        evidence_json, accepted, recorded_at_ms)
                    VALUES ('terminal-intent-2', 'task-2', 'COMPLETED',
                        'REMOTE_OBSERVATION', 'snapshot-2-2', 'head-2',
                        'merged', 1, 120)
                    """);
            execute(connection, """
                    UPDATE remote_merge_operation
                    SET status = 'SUCCEEDED',
                        terminal_observation_id =
                            'remote-terminal-observation:terminal-intent-2',
                        completed_at_ms = 121
                    WHERE id = 'direct-op'
                    """);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_terminal_intent
                    WHERE task_id = 'task-2' AND accepted = 1
                    """)).isOne();
            assertFails(connection, """
                    UPDATE remote_terminal_observation SET evidence = 'changed'
                    WHERE id = 'remote-terminal-observation:terminal-intent-2'
                    """);
            assertFails(connection, """
                    UPDATE remote_merge_operation SET last_error = 'changed'
                    WHERE id = 'direct-op'
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    private String url(String file)
    {
        return "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
    }

    private String remoteUrl(String file, int taskCount)
            throws Exception
    {
        String url = url(file);
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            for (int task = 1; task <= taskCount; task++) {
                seedPublishedRemoteTask(connection, task);
            }
        }
        migrate(url);
        return url;
    }

    private static void seedOpenGreen(Connection connection, int task, String capability)
            throws Exception
    {
        insertRemoteOwner(connection, task);
        insertCiPolicy(connection, task);
        insertSnapshot(connection, task, 1, "head-" + task, "base-" + task,
                "OPEN", "MERGEABLE", "NONE", capability, 0, 0, 0, 0);
        acceptSnapshot(connection, task, 1, "head-" + task, "base-" + task);
        insertGreenCi(connection, task, 1, "head-" + task, "base-" + task);
    }

    private static void seedMergeReady(
            Connection connection, int task, String capability, int maxReenqueues)
            throws Exception
    {
        seedOpenGreen(connection, task, capability);
        execute(connection, policySql(task, 1, 1, 1, 0, 0, maxReenqueues));
        execute(connection, readinessSql(task, 1, "automation-" + task + "-1",
                capability, "MERGEABLE", 1));
    }

    private static void authorizeStageForMerge(
            Connection connection, int task, String operationId, String authorizationId)
            throws Exception
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'READY_TO_MERGE'
                    WHERE id = 'remote-stage-%1$s' AND version = 0
                      AND checkpoint = 'WAITING_CI'
                    """.formatted(task));
            execute(connection, """
                    UPDATE stage SET version = 2, checkpoint = 'MERGING'
                    WHERE id = 'remote-stage-%1$s' AND version = 1
                      AND checkpoint = 'READY_TO_MERGE'
                    """.formatted(task));
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('merge-transition-authority-%1$s',
                        'remote-stage-%1$s', 'merge-command-authority-%1$s', 1,
                        'READY_TO_MERGE', 'MERGING', 2, 'AUTHORIZE_MERGE',
                        'system', 81)
                    """.formatted(task));
            execute(connection, """
                    INSERT INTO stage_command_receipt(
                        id, stage_id, task_id, command_id, cause, actor,
                        disposition, expected_task_epoch,
                        expected_stage_generation, expected_stage_version,
                        source_checkpoint, subject_task_epoch, subject_stage_id,
                        subject_stage_generation, subject_operation_id,
                        subject_attempt, subject_expected_head_sha,
                        subject_expected_base_sha, proof_id, returned_kind,
                        returned_generation, returned_version,
                        returned_checkpoint, returned_pending_task_epoch,
                        returned_pending_stage_id,
                        returned_pending_stage_generation,
                        returned_pending_operation_id, returned_pending_attempt,
                        returned_pending_head_sha, returned_pending_base_sha,
                        recorded_at_ms)
                    VALUES ('merge-receipt-authority-%1$s',
                        'remote-stage-%1$s', 'task-%1$s',
                        'merge-command-authority-%1$s', 'AUTHORIZE_MERGE',
                        'system', 'APPLIED', 1, 1, 1, 'READY_TO_MERGE', 1,
                        'remote-stage-%1$s', 1, '%2$s', 1, 'head-%1$s',
                        'base-%1$s', '%3$s', 'REMOTE_DEVELOPMENT', 1, 2,
                        'MERGING', 1, 'remote-stage-%1$s', 1, '%2$s', 1,
                        'head-%1$s', 'base-%1$s', 81)
                    """.formatted(task, operationId, authorizationId));
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

    private static void insertPolicy(
            Connection connection, int task, int revision, int autoApprove,
            int autoMerge, int keepDraft, int minimumApprovals, int maxReenqueues)
            throws Exception
    {
        execute(connection, policySql(task, revision, autoApprove, autoMerge,
                keepDraft, minimumApprovals, maxReenqueues));
    }

    private static String policySql(
            int task, int revision, int autoApprove, int autoMerge,
            int keepDraft, int minimumApprovals, int maxReenqueues)
    {
        return """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals, max_merge_queue_reenqueues,
                    require_low_risk, require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-%1$s-%2$s', 'task-%1$s', %2$s, 'USER',
                    %3$s, %4$s, %5$s, %6$s, %7$s, 0, 0, 0, 'user', 70 + %2$s)
                """.formatted(task, revision, autoApprove, autoMerge, keepDraft,
                minimumApprovals, maxReenqueues);
    }

    private static String markReadyAuthorizationSql(
            String id, int task, int snapshotRevision, String authority, String actor)
    {
        String actorSql = actor == null ? "NULL" : "'" + actor + "'";
        return """
                INSERT INTO remote_mark_ready_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    actor_id, status, authorized_at_ms)
                VALUES ('%1$s', 'remote-stage-%2$s', 'task-%2$s', 1, 1,
                    'snapshot-%2$s-%3$s', 'green-ci-%2$s-%3$s',
                    'automation-%2$s-%4$s', 'head-%2$s', 'base-%2$s', '%5$s',
                    %6$s, 'ACTIVE', 75)
                """.formatted(id, task, snapshotRevision,
                snapshotRevision == 3 ? 2 : 1, authority, actorSql);
    }

    private static String markReadyOperationSql(String id, String authorization, int task)
    {
        return """
                INSERT INTO remote_mark_ready_operation(
                    id, mark_ready_authorization_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, head_sha, base_sha, status, attempt_limit,
                    requested_at_ms)
                VALUES ('%1$s', '%2$s', 'remote-stage-%3$s', 'task-%3$s', 1, 1,
                    '%1$s-operation-id', 1, 'head-%3$s', 'base-%3$s',
                    'REQUESTED', 2, 76)
                """.formatted(id, authorization, task);
    }

    private static String claimMarkReadySql(String id, String mode, long at)
    {
        return """
                UPDATE remote_mark_ready_operation
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = '%2$s', claim_owner = 'worker',
                    claimed_at_ms = %3$s, lease_until_ms = %3$s + 100,
                    result_snapshot_id = NULL, evidence = NULL,
                    last_error = NULL, completed_at_ms = NULL
                WHERE id = '%1$s'
                """.formatted(id, mode, at);
    }

    private static String inboxVerdictSql(
            String id, String reviewId, String provenance, int ignored)
    {
        return """
                INSERT INTO remote_inbox_item(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, remote_pr_snapshot_id,
                    kind, external_key, external_revision, head_sha, base_sha,
                    actor_login, provenance, ignored, review_id, body, body_digest,
                    verdict, observed_at_ms)
                VALUES ('%1$s', 'remote-stage-1', 'task-1', 1, 1, 'binding-1',
                    'snapshot-1-1', 'REVIEW_VERDICT', '%2$s', 1, 'head-1',
                    'base-1', 'reviewer', '%3$s', %4$s, '%2$s',
                    'please fix the null path', 'verdict-digest',
                    'CHANGES_REQUESTED', 75)
                """.formatted(id, reviewId, provenance, ignored);
    }

    private static String inboxCommentSql(String id, String externalKey)
    {
        return """
                INSERT INTO remote_inbox_item(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, remote_pr_snapshot_id,
                    kind, external_key, external_revision, head_sha, base_sha,
                    actor_login, provenance, ignored, comment_id, body, body_digest,
                    observed_at_ms)
                VALUES ('%1$s', 'remote-stage-1', 'task-1', 1, 1, 'binding-1',
                    'snapshot-1-1', 'TOP_LEVEL_COMMENT', '%2$s', 1, 'head-1',
                    'base-1', 'reviewer', 'EXTERNAL', 0, '%2$s', 'one more thing',
                    'comment-digest', 90)
                """.formatted(id, externalKey);
    }

    private static String batchSql(String id, int sequence, int itemCount, int brainRequired)
    {
        return """
                INSERT INTO remote_feedback_batch(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    sequence, head_sha, base_sha, status, brain_review_required,
                    item_count, created_at_ms)
                VALUES ('%1$s', 'remote-stage-1', 'task-1', 1, 1, 'binding-1',
                    'snapshot-1-1', %2$s, 'head-1', 'base-1', 'BUILDING',
                    %4$s, %3$s, 76)
                """.formatted(id, sequence, itemCount, brainRequired);
    }

    private static String batchItemSql(
            String batch, int ordinal, String inbox, String kind, String body, String digest)
    {
        return """
                INSERT INTO remote_feedback_batch_item(
                    remote_feedback_batch_id, ordinal, remote_inbox_item_id,
                    external_revision, kind, frozen_body, body_digest,
                    selected_by, selected_at_ms)
                VALUES ('%1$s', %2$s, '%3$s', 1, '%4$s', '%5$s', '%6$s',
                    'user', 77)
                """.formatted(batch, ordinal, inbox, kind, body, digest);
    }

    private static void insertFeedbackStageTurn(Connection connection)
            throws Exception
    {
        execute(connection, """
                UPDATE stage SET version = 1,
                    checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                WHERE id = 'remote-stage-1' AND version = 0
                  AND checkpoint = 'WAITING_CI'
                """);
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES ('feedback-stage-turn', 'remote-stage-1', 1,
                    'ADDRESS_REMOTE_FEEDBACK', 'QUEUED',
                    'feedback-stage-turn-operation', 1, 1, 'fingerprint-1',
                    'head-1', 'base-1', 'CLI', 'address feedback', 81)
                """);
        execute(connection, """
                INSERT INTO remote_feedback_stage_turn_request(
                    id, remote_feedback_batch_id, stage_turn_id, task_id,
                    remote_development_stage_id, task_epoch, stage_generation,
                    semantic_attempt, predecessor_turn_id, prompt_digest,
                    requested_by, requested_at_ms)
                VALUES ('feedback-stage-request', 'batch-1',
                    'feedback-stage-turn', 'task-1', 'remote-stage-1', 1, 1,
                    1, NULL,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'test', 81)
                """);
        execute(connection, """
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 81,
                    finished_at_ms = 82
                WHERE id = 'feedback-stage-turn'
                """);
        execute(connection, """
                INSERT INTO remote_feedback_repair_result(
                    id, remote_feedback_batch_id, repair_stage_turn_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, subject_head_sha, proposed_head_sha,
                    base_sha, code_fingerprint, summary, result_digest,
                    completed_at_ms)
                VALUES ('feedback-repair-1', 'batch-1', 'feedback-stage-turn',
                    'task-1', 1, 'remote-stage-1', 1, 'head-1', 'head-fixed',
                    'base-1', 'fixed-fingerprint', 'addressed feedback',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    82)
                """);
    }

    private static String validationEvidenceSql()
    {
        return """
                INSERT INTO remote_feedback_validation_evidence(
                    id, remote_feedback_batch_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, repair_stage_turn_id,
                    validation_pass_id, validation_operation_id,
                    validation_attempt,
                    subject_head_sha, proposed_head_sha, base_sha,
                    code_fingerprint, passed, evidence, completed_at_ms)
                VALUES ('feedback-validation-1', 'batch-1', 'remote-stage-1',
                    'task-1', 1, 1, 'feedback-stage-turn',
                    (SELECT id FROM validation_pass
                     WHERE claim_key = 'feedback-validation-claim'),
                    'feedback-validation-operation', 1, 'head-1', 'head-fixed',
                    'base-1', 'fixed-fingerprint', 1, 'tests passed', 82)
                """;
    }

    private static void insertFeedbackValidationPass(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO remote_feedback_validation_operation(
                    id, remote_feedback_batch_id, repair_result_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, operation_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('feedback-validation-operation', 'batch-1',
                    'feedback-repair-1', 'remote-stage-1', 'task-1', 1, 1,
                    'feedback-validation-operation', 1, 'fixed-fingerprint',
                    'head-fixed', 'base-1', 'REQUESTED', 81)
                """);
        execute(connection, """
                UPDATE remote_feedback_validation_operation
                SET status = 'DISPATCHED'
                WHERE id = 'feedback-validation-operation'
                """);
        execute(connection, """
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, fix_rounds,
                    failures_json, claim_key, code_fingerprint, workflow_version,
                    task_epoch, stage_id, stage_generation, operation_id,
                    semantic_attempt, expected_head_sha, expected_base_sha)
                VALUES ('task-1', 81, 82, 1, 0, '[]',
                    'feedback-validation-claim', 'fixed-fingerprint', 'V2', 1,
                    'remote-stage-1', 1, 'feedback-validation-operation', 1,
                    'head-fixed', 'base-1')
                """);
        execute(connection, """
                INSERT INTO remote_feedback_validation_attempt_evidence(
                    id, remote_feedback_batch_id, repair_result_id,
                    validation_operation_id, validation_pass_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, repair_stage_turn_id, semantic_attempt,
                    subject_head_sha, proposed_head_sha, base_sha,
                    code_fingerprint, passed, failures_json, evidence,
                    completed_at_ms)
                VALUES ('feedback-validation-attempt-1', 'batch-1',
                    'feedback-repair-1', 'feedback-validation-operation',
                    (SELECT id FROM validation_pass
                     WHERE claim_key = 'feedback-validation-claim'),
                    'remote-stage-1', 'task-1', 1, 1, 'feedback-stage-turn', 1,
                    'head-1', 'head-fixed', 'base-1', 'fixed-fingerprint',
                    1, '[]', 'tests passed', 82)
                """);
        execute(connection, """
                UPDATE remote_feedback_validation_operation
                SET status = 'COMPLETED', completed_at_ms = 82
                WHERE id = 'feedback-validation-operation'
                """);
    }

    private static void insertFeedbackBrainTurn(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('feedback-brain-turn', 'task-1',
                    'REMOTE_FEEDBACK_BRAIN_REVIEW', 'REQUESTED',
                    'feedback-brain-turn-operation', 1, 1, 'remote-stage-1', 1,
                    'fixed-fingerprint', 'head-fixed', 'base-1', 'API',
                    'review feedback fix', 83)
                """);
        execute(connection, """
                INSERT INTO remote_feedback_brain_episode(
                    id, remote_feedback_batch_id,
                    validation_attempt_evidence_id, task_id, task_epoch,
                    remote_development_stage_id, stage_generation, task_turn_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('feedback-brain-episode', 'batch-1',
                    'feedback-validation-attempt-1', 'task-1', 1,
                    'remote-stage-1', 1, 'feedback-brain-turn', 1,
                    'fixed-fingerprint', 'head-fixed', 'base-1', 'REQUESTED', 83)
                """);
        execute(connection, """
                UPDATE task_turn
                SET status = 'SUCCEEDED', started_at_ms = 83,
                    finished_at_ms = 84
                WHERE id = 'feedback-brain-turn'
                """);
        execute(connection, """
                UPDATE remote_feedback_brain_episode
                SET status = 'SUCCEEDED', verdict = 'APPROVED',
                    unresolved_finding_count = 0, evidence = 'brain approved',
                    completed_at_ms = 84
                WHERE id = 'feedback-brain-episode'
                """);
    }

    private static String brainEvidenceSql()
    {
        return """
                INSERT INTO remote_feedback_brain_review_evidence(
                    id, remote_feedback_batch_id, validation_evidence_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, task_turn_id, proposed_head_sha, base_sha,
                    code_fingerprint, verdict, unresolved_finding_count,
                    evidence, completed_at_ms)
                VALUES ('feedback-brain-1', 'batch-1', 'feedback-validation-1',
                    'task-1', 1, 'remote-stage-1', 1, 'feedback-brain-turn',
                    'head-fixed', 'base-1', 'fixed-fingerprint', 'APPROVED', 0,
                    'brain approved', 84)
                """;
    }

    private static String feedbackAuthorizationSql(String brainEvidence)
    {
        String brainSql = brainEvidence == null ? "NULL" : "'" + brainEvidence + "'";
        return """
                INSERT INTO remote_feedback_authorization(
                    id, remote_feedback_batch_id, validation_evidence_id,
                    brain_review_evidence_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, head_sha, base_sha,
                    item_count, content_digest, effect_count, authority_kind,
                    authorized_by, authorized_at_ms)
                VALUES ('feedback-authorization', 'batch-1',
                    'feedback-validation-1', %s, 'remote-stage-1', 'task-1',
                    1, 1, 'head-1', 'base-1', 1, 'batch-digest', 2,
                    'USER_ACTION', 'user', 85)
                """.formatted(brainSql);
    }

    private static String effectSql(
            String id, int ordinal, String kind, String inbox, String reviewAction)
    {
        String inboxSql = inbox == null ? "NULL" : "'" + inbox + "'";
        String reviewSql = reviewAction == null ? "NULL" : "'" + reviewAction + "'";
        return """
                INSERT INTO remote_feedback_effect_step(
                    id, remote_feedback_authorization_id, remote_feedback_batch_id,
                    ordinal, kind, remote_inbox_item_id, external_target,
                    review_action, payload_digest, idempotency_key, status,
                    attempt_limit)
                VALUES ('%1$s', 'feedback-authorization', 'batch-1', %2$s,
                    '%3$s', %4$s, NULL, %5$s, '%1$s-payload',
                    'feedback:%1$s', 'REQUESTED', 3)
                """.formatted(id, ordinal, kind, inboxSql, reviewSql);
    }

    private static String claimEffectSql(String id, String mode, long at)
    {
        return """
                UPDATE remote_feedback_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = '%2$s', claim_owner = 'worker',
                    claimed_at_ms = %3$s, lease_until_ms = %3$s + 100,
                    external_effect_id = NULL, evidence = NULL,
                    last_error = NULL, completed_at_ms = NULL
                WHERE id = '%1$s'
                """.formatted(id, mode, at);
    }

    private static String finishEffectSql(
            String id, String status, String externalId, String evidence, long at)
    {
        String externalSql = externalId == null ? "NULL" : "'" + externalId + "'";
        return """
                UPDATE remote_feedback_effect_step
                SET status = '%2$s', claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    external_effect_id = %3$s, evidence = '%4$s',
                    completed_at_ms = %5$s
                WHERE id = '%1$s'
                """.formatted(id, status, externalSql, evidence, at);
    }

    private static String readinessSql(
            int task, int snapshotRevision, String policyId,
            String capability, String mergeability, int ready)
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
                VALUES ('readiness-%1$s-%2$s', 'remote-stage-%1$s', 'task-%1$s',
                    1, 1, 'snapshot-%1$s-%2$s', 'green-ci-%1$s-%2$s', '%3$s',
                    'head-%1$s', 'base-%1$s', 1, 1, 1, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, '%5$s', '%4$s', %6$s, 'fresh exact truth',
                    60 + %2$s)
                """.formatted(task, snapshotRevision, policyId,
                capability, mergeability, ready);
    }

    private static String mergeAuthorizationSql(String id, int task, int snapshotRevision)
    {
        return """
                INSERT INTO remote_merge_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, readiness_evidence_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    status, authorized_at_ms)
                VALUES ('%1$s', 'remote-stage-%2$s', 'task-%2$s', 1, 1,
                    'readiness-%2$s-%3$s', 'automation-%2$s-1',
                    'head-%2$s', 'base-%2$s', 'AUTO_MERGE_POLICY', 'ACTIVE', 75)
                """.formatted(id, task, snapshotRevision);
    }

    private static String mergeOperationSql(
            String id, String authorization, int task, String mode,
            String capability, int maxReenqueues)
    {
        return """
                INSERT INTO remote_merge_operation(
                    id, merge_authorization_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, head_sha, base_sha, mode,
                    merge_queue_capability, status, attempt_limit,
                    max_queue_reenqueues,
                    requested_at_ms)
                VALUES ('%1$s', '%2$s', 'remote-stage-%3$s', 'task-%3$s', 1, 1,
                    '%1$s-operation-id', 1, 'head-%3$s', 'base-%3$s', '%4$s',
                    '%5$s', 'REQUESTED', 6, %6$s, 76)
                """.formatted(id, authorization, task, mode, capability, maxReenqueues);
    }

    private static String mergeAttemptSql(
            String id, String operation, int ordinal, String effectKind,
            Integer effectOrdinal, String readiness, String mode, long claimedAt)
    {
        String effectOrdinalSql = effectOrdinal == null ? "NULL" : effectOrdinal.toString();
        String idempotencyKey = operation + "-operation-id:" +
                (effectOrdinal == null ? "direct" : "queue:" + effectOrdinal);
        return """
                INSERT INTO remote_merge_effect_attempt(
                    id, merge_operation_id, ordinal, effect_kind, effect_ordinal,
                    readiness_evidence_id, idempotency_key, attempt_key,
                    claim_mode, status, claim_owner, claimed_at_ms,
                    lease_until_ms)
                VALUES ('%1$s', '%2$s', %3$s, '%4$s', %5$s, '%6$s', '%7$s',
                    '%7$s:attempt:%3$s', '%8$s', 'CLAIMED', 'worker', %9$s,
                    %9$s + 100)
                """.formatted(id, operation, ordinal, effectKind, effectOrdinalSql,
                readiness, idempotencyKey, mode, claimedAt);
    }

    private static String claimMergeOperationSql(String operation)
    {
        return """
                UPDATE remote_merge_operation
                SET status = 'CLAIMED', attempt_count = attempt_count + 1
                WHERE id = '%s'
                """.formatted(operation);
    }

    private static String finishMergeAttemptSql(
            String id, String status, String snapshot, String evidence, long completedAt)
    {
        String snapshotSql = snapshot == null ? "NULL" : "'" + snapshot + "'";
        String completedSql = status.equals("AWAITING_OBSERVATION") ?
                "NULL" : Long.toString(completedAt);
        String evidenceSql = evidence == null ? "NULL" : "'" + evidence + "'";
        return """
                UPDATE remote_merge_effect_attempt
                SET status = '%2$s', observed_snapshot_id = %3$s,
                    evidence = %4$s, completed_at_ms = %5$s
                WHERE id = '%1$s'
                """.formatted(id, status, snapshotSql, evidenceSql, completedSql);
    }

    private static String queueEntrySql(
            String id, String attempt, int ordinal, String snapshot, String readiness)
    {
        return """
                INSERT INTO remote_merge_queue_entry(
                    id, merge_operation_id, merge_effect_attempt_id, ordinal,
                    queue_entry_id, head_sha, status, entered_snapshot_id,
                    readiness_evidence_id,
                    entered_at_ms, evidence)
                VALUES ('%1$s', 'queue-op', '%2$s', %3$s, 'queue-remote-%3$s',
                    'head-1', 'ENTERED', '%4$s', '%5$s', 90,
                    'observer reports queued')
                """.formatted(id, attempt, ordinal, snapshot, readiness);
    }

    private static String observeQueueEntrySql(
            String id, String status, String snapshot, long observedAt)
    {
        return """
                UPDATE remote_merge_queue_entry
                SET status = '%2$s', observed_snapshot_id = '%3$s',
                    observed_at_ms = %4$s
                WHERE id = '%1$s'
                """.formatted(id, status, snapshot, observedAt);
    }
}
