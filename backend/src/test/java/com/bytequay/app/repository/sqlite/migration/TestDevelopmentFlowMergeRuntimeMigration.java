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

import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.BlockReason;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimSpec;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowMergeRuntimeMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void bindsOneConsumedExactHeadMergeToOneTypedTicketAcrossRestart()
            throws Exception
    {
        String url = remoteUrl("merge-runtime.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, authorizationSql(1));
            execute(connection, operationSql(1));

            assertFails(connection, ticketSql(1)
                    .replace("'REMOTE_MERGE_RESULT'", "'wrong-route'"));
            assertFails(connection, ticketSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);

            assertFails(connection, ticketSql(1)
                    .replace("'merge-ticket-1'", "'duplicate-ticket-1'"));
            assertThat(text(connection, """
                    SELECT operation_kind || '|' || async_family || '|' || lane_mask
                    FROM dispatch_ticket WHERE id = 'merge-ticket-1'
                    """)).isEqualTo("MERGE_REMOTE_PULL_REQUEST|MERGE|128");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM outbox
                    WHERE aggregate_kind = 'DISPATCH_TICKET'
                      AND aggregate_id = 'merge-ticket-1'
                    """)).isOne();

            insertSnapshot(connection, 1, 2, "new-head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "new-head-1", "base-1");
            assertFails(connection, attemptSql(1));
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM remote_merge_effect_attempt")).isZero();
        }

        migrate(url, "274");
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM dispatch_ticket
                    WHERE id = 'merge-ticket-1' AND status = 'REQUESTED'
                    """)).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM remote_merge_effect_attempt")).isZero();
            assertThat(text(connection, """
                    SELECT authorization.merge_method || '|' || operation.merge_method
                    FROM remote_merge_authorization authorization
                    JOIN remote_merge_operation operation
                      ON operation.merge_authorization_id = authorization.id
                    WHERE authorization.id = 'merge-auth-1'
                    """)).isEqualTo("rebase|rebase");
            assertFails(connection, """
                    UPDATE remote_merge_operation SET merge_method = 'squash'
                    WHERE id = 'merge-op-1'
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void upgradesExistingMergeOperationsWithTheFormerSquashBehavior()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("merge-method-upgrade.db")
                + "?foreign_keys=ON";
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url, "244");
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, """
                    INSERT INTO remote_merge_authorization(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, readiness_evidence_id,
                        automation_policy_id, head_sha, base_sha, authority_kind,
                        status, authorized_at_ms)
                    VALUES ('merge-auth-1', 'remote-stage-1', 'task-1', 1, 1,
                        'readiness-1-1', 'automation-1-1', 'head-1', 'base-1',
                        'AUTO_MERGE_POLICY', 'ACTIVE', 75)
                    """);
            execute(connection, """
                    INSERT INTO remote_merge_operation(
                        id, merge_authorization_id, remote_development_stage_id,
                        task_id, task_epoch, stage_generation, operation_id,
                        semantic_attempt, head_sha, base_sha, mode,
                        merge_queue_capability, status, attempt_limit,
                        max_queue_reenqueues, requested_at_ms)
                    VALUES ('merge-op-1', 'merge-auth-1', 'remote-stage-1',
                        'task-1', 1, 1, 'merge-operation-1', 1, 'head-1',
                        'base-1', 'DIRECT', 'UNSUPPORTED', 'REQUESTED', 3, 0, 76)
                    """);
        }

        migrate(url, "274");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT authorization.merge_method || '|' || operation.merge_method
                    FROM remote_merge_authorization authorization
                    JOIN remote_merge_operation operation
                      ON operation.merge_authorization_id = authorization.id
                    """)).isEqualTo("squash|squash");
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void supersededAutoMergeCannotClaimItsFirstExternalEffect()
            throws Exception
    {
        String url = remoteUrl("stale-policy-pre-effect.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, authorizationSql(1));
            execute(connection, operationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
            execute(connection, supersedingPolicySql(1));
        }

        SqliteMergeOperationStore store = mergeStore(url);
        assertThat(store.tryClaim(
                "merge-operation-1",
                new ClaimSpec(
                        ClaimMode.EXECUTE, EffectKind.DIRECT_MERGE, null,
                        "readiness-1-1", "merge-operation-1:direct"),
                "worker", Instant.ofEpochMilli(90),
                Instant.ofEpochMilli(100))).isEmpty();

        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(91));

        assertThat(store.requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
        try (Connection connection = connect(url)) {
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM remote_merge_effect_attempt")).isZero();
            assertThat(text(connection, """
                    SELECT checkpoint FROM stage WHERE id = 'remote-stage-1'
                    """)).isEqualTo("MERGING");
        }
    }

    @Test
    void supersedingPolicyDoesNotRewriteAnAlreadyClaimedExternalEffect()
            throws Exception
    {
        String url = remoteUrl("stale-policy-claimed.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, authorizationSql(1));
            execute(connection, operationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
        }

        SqliteMergeOperationStore store = mergeStore(url);
        var claim = store.tryClaim(
                        "merge-operation-1",
                        new ClaimSpec(
                                ClaimMode.EXECUTE, EffectKind.DIRECT_MERGE, null,
                                "readiness-1-1", "merge-operation-1:direct"),
                        "worker", Instant.ofEpochMilli(90),
                        Instant.ofEpochMilli(100))
                .orElseThrow();
        try (Connection connection = connect(url)) {
            execute(connection, supersedingPolicySql(1));
        }

        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(91));
        assertThat(store.markAwaiting(
                claim,
                new EffectEvidence(
                        "remote-merge-id", "merge accepted; awaiting observation",
                        false),
                Instant.ofEpochMilli(92))).isTrue();

        assertThat(store.requireByOperationId("merge-operation-1")
                .request().status())
                .isEqualTo(OperationStatus.AWAITING_OBSERVATION);
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT attempt.status || '|' || operation.status || '|'
                        || stage.checkpoint
                    FROM remote_merge_effect_attempt attempt
                    JOIN remote_merge_operation operation
                      ON operation.id = attempt.merge_operation_id
                    JOIN stage ON stage.id = operation.remote_development_stage_id
                    WHERE operation.id = 'merge-op-1'
                    """)).isEqualTo("AWAITING_OBSERVATION|AWAITING_OBSERVATION|MERGING");
        }
    }

    @Test
    void materializesTerminalFactOnlyFromAcceptedExactRemoteTruth()
            throws Exception
    {
        String url = remoteUrl("remote-terminal-runtime.db", 1);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "MERGED",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");

            assertFails(connection, terminalIntentSql("wrong-head"));
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM task_terminal_intent")).isZero();
            execute(connection, terminalIntentSql("head-1"));

            assertThat(text(connection, """
                    SELECT terminal.kind || '|' || terminal.head_sha || '|'
                        || intent.source || '|' || intent.source_id
                    FROM remote_terminal_observation terminal
                    JOIN task_terminal_intent intent
                      ON intent.id = terminal.task_terminal_intent_id
                    """)).isEqualTo("MERGED|head-1|REMOTE_OBSERVATION|snapshot-1-1");
            assertFails(connection, """
                    UPDATE remote_terminal_observation SET evidence = 'changed'
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void restartsAndCompletesAClaimedDirectEffectFromObservedTruth()
            throws Exception
    {
        String url = remoteUrl("merge-store-restart.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, authorizationSql(1));
            execute(connection, operationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
        }

        SqliteMergeOperationStore store = mergeStore(url);
        var claim = store.tryClaim(
                        "merge-operation-1",
                        new ClaimSpec(
                                ClaimMode.EXECUTE, EffectKind.DIRECT_MERGE, null,
                                "readiness-1-1", "merge-operation-1:direct"),
                        "worker", Instant.ofEpochMilli(90),
                        Instant.ofEpochMilli(100))
                .orElseThrow();
        assertThat(store.markAwaiting(
                claim,
                new EffectEvidence("merge-sha", "GitHub accepted merge", false),
                Instant.ofEpochMilli(91))).isTrue();

        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 2, "head-1", "base-1", "MERGED",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
            execute(connection, terminalIntentSql(
                    "terminal-intent-2", "snapshot-1-2", "head-1"));
        }

        SqliteMergeOperationStore restarted = mergeStore(url);
        restarted.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(100));

        assertThat(restarted.requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.SUCCEEDED);
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT attempt.status || '|' || operation.status
                    FROM remote_merge_effect_attempt attempt
                    JOIN remote_merge_operation operation
                      ON operation.id = attempt.merge_operation_id
                    WHERE operation.id = 'merge-op-1'
                    """)).isEqualTo("SUCCEEDED|SUCCEEDED");
        }
    }

    @Test
    void recordsQueueBouncesAndBlocksAfterTheBoundedReenqueue()
            throws Exception
    {
        String url = remoteUrl("merge-queue-runtime.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "SUPPORTED", 1);
            execute(connection, authorizationSql(1));
            execute(connection, queueOperationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
        }

        SqliteMergeOperationStore store = mergeStore(url);
        var first = store.tryClaim(
                        "merge-operation-1",
                        new ClaimSpec(
                                ClaimMode.EXECUTE, EffectKind.ENTER_QUEUE, 1,
                                "readiness-1-1", "merge-operation-1:queue:1"),
                        "worker", Instant.ofEpochMilli(90),
                        Instant.ofEpochMilli(100))
                .orElseThrow();
        assertThat(store.markAwaiting(
                first, new EffectEvidence(null, "enqueue accepted", false),
                Instant.ofEpochMilli(91))).isTrue();

        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "QUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
        }
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(92));

        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 3, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "DEQUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 3, "head-1", "base-1");
            insertGreenCi(connection, 1, 3, "head-1", "base-1");
            insertReadiness(connection, 1, 3, "SUPPORTED");
        }
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(93));

        var second = store.tryClaim(
                        "merge-operation-1",
                        new ClaimSpec(
                                ClaimMode.EXECUTE, EffectKind.ENTER_QUEUE, 2,
                                "readiness-1-3", "merge-operation-1:queue:2"),
                        "worker", Instant.ofEpochMilli(94),
                        Instant.ofEpochMilli(104))
                .orElseThrow();
        assertThat(store.markAwaiting(
                second, new EffectEvidence(null, "re-enqueue accepted", false),
                Instant.ofEpochMilli(95))).isTrue();

        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 4, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "QUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 4, "head-1", "base-1");
        }
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(96));
        try (Connection connection = connect(url)) {
            insertSnapshot(connection, 1, 5, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "DEQUEUED", "SUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 5, "head-1", "base-1");
        }
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(97));

        assertThat(store.requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.BLOCKED);
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT operation.queue_bounce_count || '|'
                        || entry.ordinal || '|' || entry.status || '|'
                        || blocker.blocker_type
                    FROM remote_merge_operation operation
                    JOIN remote_merge_queue_entry entry
                      ON entry.merge_operation_id = operation.id
                     AND entry.ordinal = 2
                    JOIN task_blocker blocker
                      ON blocker.owner_id = operation.id
                    WHERE operation.id = 'merge-op-1'
                    """)).isEqualTo("1|2|BOUNCED|MERGE_QUEUE_REENQUEUE_EXHAUSTED");
        }
    }

    @Test
    void ticketCancellationAtomicallyTerminatesQueuedAndReconcilingMerges()
            throws Exception
    {
        String url = remoteUrl("merge-cancel-runtime.db", 2);
        try (Connection connection = connect(url)) {
            for (int task = 1; task <= 2; task++) {
                seedReady(connection, task, "UNSUPPORTED", 0);
                execute(connection, authorizationSql(task));
                execute(connection, operationSql(task));
                execute(connection, """
                        UPDATE remote_merge_authorization
                        SET status = 'CONSUMED', terminal_at_ms = 80
                        WHERE id = 'merge-auth-%s'
                        """.formatted(task));
                execute(connection, ticketSql(task));
                authorizeStageForMerge(connection, task);
            }
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = version + 1,
                        status = 'RESULT_PENDING',
                        cancel_requested_at_ms = 90,
                        pending_result_outcome = 'CANCELED',
                        pending_result_evidence = '{}',
                        pending_result_error = 'cancel requested before launch',
                        pending_result_task_epoch = task_epoch,
                        pending_result_stage_id = stage_id,
                        pending_result_stage_generation = stage_generation,
                        pending_result_operation_id = operation_id,
                        pending_result_attempt = attempt,
                        pending_result_expected_head_sha = expected_head_sha,
                        pending_result_expected_base_sha = expected_base_sha
                    WHERE id = 'merge-ticket-1'
                    """);
        }

        SqliteMergeOperationStore store = mergeStore(url);
        var claim = store.tryClaim(
                        "merge-operation-2",
                        new ClaimSpec(
                                ClaimMode.EXECUTE, EffectKind.DIRECT_MERGE, null,
                                "readiness-2-1", "merge-operation-2:direct"),
                        "worker", Instant.ofEpochMilli(90),
                        Instant.ofEpochMilli(100))
                .orElseThrow();
        assertThat(store.markAwaiting(
                claim, new EffectEvidence("remote", "merge requested", false),
                Instant.ofEpochMilli(91))).isTrue();

        try (Connection connection = connect(url)) {
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = version + 1, status = 'RECONCILE_WAIT',
                        next_attempt_at_ms = 100
                    WHERE id = 'merge-ticket-2'
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = version + 1, cancel_requested_at_ms = 95
                    WHERE id = 'merge-ticket-2'
                    """);

            assertThat(text(connection, """
                    SELECT first.status || '|' || second.status || '|'
                        || attempt.status
                    FROM remote_merge_operation first
                    JOIN remote_merge_operation second ON second.id = 'merge-op-2'
                    JOIN remote_merge_effect_attempt attempt
                      ON attempt.merge_operation_id = second.id
                    WHERE first.id = 'merge-op-1'
                    """)).isEqualTo("CANCELED|CANCELED|FAILED");
        }

        assertThat(mergeStore(url).requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
        assertThat(mergeStore(url).requireByOperationId("merge-operation-2")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
    }

    @Test
    void acceptedQueueCapabilityDriftCancelsBeforeTheFirstClaim()
            throws Exception
    {
        String url = remoteUrl("merge-capability-drift.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "SUPPORTED", 1);
            execute(connection, authorizationSql(1));
            execute(connection, queueOperationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
            insertSnapshot(connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
        }

        SqliteMergeOperationStore store = mergeStore(url);
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(90));

        assertThat(store.requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
    }

    @Test
    void acceptedSameHeadRevisionCancelsBeforeTheFirstClaim()
            throws Exception
    {
        String url = remoteUrl("merge-snapshot-drift.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, authorizationSql(1));
            execute(connection, operationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
            insertSnapshot(connection, 1, 2, "head-1", "base-1", "OPEN",
                    "MERGEABLE", "NONE", "UNSUPPORTED", 0, 0, 0, 0);
            acceptSnapshot(connection, 1, 2, "head-1", "base-1");
        }

        SqliteMergeOperationStore store = mergeStore(url);
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(90));

        assertThat(store.requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
        try (Connection connection = connect(url)) {
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM remote_merge_effect_attempt")).isZero();
        }
    }

    @Test
    void executeClaimAtomicallyRequiresCurrentPolicyAndStageOwner()
            throws Exception
    {
        String url = remoteUrl("merge-owner-claim.db", 2);
        try (Connection connection = connect(url)) {
            for (int task = 1; task <= 2; task++) {
                seedReady(connection, task, "UNSUPPORTED", 0);
                execute(connection, authorizationSql(task));
                execute(connection, operationSql(task));
                execute(connection, """
                        UPDATE remote_merge_authorization
                        SET status = 'CONSUMED', terminal_at_ms = 80
                        WHERE id = 'merge-auth-%s'
                        """.formatted(task));
                execute(connection, ticketSql(task));
                authorizeStageForMerge(connection, task);
            }

            execute(connection, automationPolicySql(1, 2, 0));
            assertFails(connection, attemptSql(1));

            execute(connection, """
                    UPDATE stage SET version = version + 1,
                        checkpoint = 'READY_TO_MERGE'
                    WHERE id = 'remote-stage-2'
                    """);
            assertFails(connection, attemptSql(2));
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM remote_merge_effect_attempt")).isZero();
        }

        SqliteMergeOperationStore store = mergeStore(url);
        store.reconcileAcceptedObservation(
                "merge-operation-1", Instant.ofEpochMilli(90));
        assertThat(store.requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
    }

    @Test
    void preClaimManualBlockerDurablyCancelsTheRequestedOperation()
            throws Exception
    {
        String url = remoteUrl("merge-preclaim-block.db", 1);
        try (Connection connection = connect(url)) {
            seedReady(connection, 1, "UNSUPPORTED", 0);
            execute(connection, authorizationSql(1));
            execute(connection, operationSql(1));
            execute(connection, """
                    UPDATE remote_merge_authorization
                    SET status = 'CONSUMED', terminal_at_ms = 80
                    WHERE id = 'merge-auth-1'
                    """);
            execute(connection, ticketSql(1));
            authorizeStageForMerge(connection, 1);
        }

        SqliteMergeOperationStore store = mergeStore(url);
        store.block(
                "merge-operation-1",
                BlockReason.MANUAL_INTERVENTION,
                "dispatcher retry budget exhausted",
                Instant.ofEpochMilli(90));

        assertThat(mergeStore(url).requireByOperationId("merge-operation-1")
                .request().status()).isEqualTo(OperationStatus.CANCELED);
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT blocker_type || '|' || status
                    FROM task_blocker
                    WHERE owner_id = 'merge-op-1'
                    """)).isEqualTo("MERGE_MANUAL_INTERVENTION|OPEN");
        }
    }

    private String remoteUrl(String file, int tasks)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            for (int task = 1; task <= tasks; task++) {
                seedPublishedRemoteTask(connection, task);
            }
        }
        migrate(url, "274");
        return url;
    }

    private static SqliteMergeOperationStore mergeStore(String url)
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
        return new SqliteMergeOperationStore(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    private static void seedReady(
            Connection connection, int task, String capability, int maxReenqueues)
            throws Exception
    {
        insertRemoteOwner(connection, task);
        insertCiPolicy(connection, task);
        execute(connection, automationPolicySql(task, 1, maxReenqueues));
        insertSnapshot(connection, task, 1, "head-" + task, "base-" + task,
                "OPEN", "MERGEABLE", "NONE", capability, 0, 0, 0, 0);
        acceptSnapshot(connection, task, 1, "head-" + task, "base-" + task);
        insertGreenCi(connection, task, 1, "head-" + task, "base-" + task);
        insertReadiness(connection, task, 1, capability);
    }

    private static String automationPolicySql(
            int task, int revision, int maxReenqueues)
    {
        return """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-%1$s-%2$s', 'task-%1$s', %2$s, 'TASK', 1, 1,
                    0, 0, %3$s, 0, 0, 0, 'user', 55 + %2$s)
                """.formatted(task, revision, maxReenqueues);
    }

    private static String supersedingPolicySql(int task)
    {
        return """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-%1$s-2', 'task-%1$s', 2, 'TASK', 1, 0,
                    0, 1, 0, 0, 0, 0, 'user', 90)
                """.formatted(task);
    }

    private static void authorizeStageForMerge(Connection connection, int task)
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
                    VALUES ('merge-transition-%1$s', 'remote-stage-%1$s',
                        'merge-command-%1$s', 1, 'READY_TO_MERGE', 'MERGING',
                        2, 'AUTHORIZE_MERGE', 'system', 81)
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
                    VALUES ('merge-receipt-%1$s', 'remote-stage-%1$s', 'task-%1$s',
                        'merge-command-%1$s', 'AUTHORIZE_MERGE', 'system',
                        'APPLIED', 1, 1, 1, 'READY_TO_MERGE', 1,
                        'remote-stage-%1$s', 1, 'merge-operation-%1$s', 1,
                        'head-%1$s', 'base-%1$s', 'merge-auth-%1$s',
                        'REMOTE_DEVELOPMENT', 1, 2, 'MERGING', 1,
                        'remote-stage-%1$s', 1, 'merge-operation-%1$s', 1,
                        'head-%1$s', 'base-%1$s', 81)
                    """.formatted(task));
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

    private static void insertReadiness(
            Connection connection, int task, int revision, String capability)
            throws Exception
    {
        execute(connection, """
                INSERT INTO remote_readiness_evidence(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_snapshot_id, ci_evaluation_id,
                    automation_policy_id, head_sha, base_sha, pr_open,
                    non_draft, ci_accepted, write_approval_count,
                    required_write_approval_count, changes_requested_count,
                    unresolved_thread_count, unresolved_comment_count,
                    open_feedback_batch_count, blocking_gate_count,
                    low_risk_required, small_effort_required,
                    low_risk_eligible, small_effort_eligible, mergeability,
                    merge_queue_capability, ready, evidence, observed_at_ms)
                VALUES ('readiness-%1$s-%2$s', 'remote-stage-%1$s', 'task-%1$s',
                    1, 1, 'snapshot-%1$s-%2$s', 'green-ci-%1$s-%2$s',
                    'automation-%1$s-1', 'head-%1$s', 'base-%1$s', 1, 1, 1,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'MERGEABLE', '%3$s',
                    1, 'ready', 60 + %2$s)
                """.formatted(task, revision, capability));
    }

    private static String authorizationSql(int task)
    {
        return """
                INSERT INTO remote_merge_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, readiness_evidence_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    status, authorized_at_ms, merge_method)
                VALUES ('merge-auth-%1$s', 'remote-stage-%1$s', 'task-%1$s',
                    1, 1, 'readiness-%1$s-1', 'automation-%1$s-1',
                    'head-%1$s', 'base-%1$s', 'AUTO_MERGE_POLICY', 'ACTIVE', 75,
                    'rebase')
                """.formatted(task);
    }

    private static String operationSql(int task)
    {
        return """
                INSERT INTO remote_merge_operation(
                    id, merge_authorization_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, head_sha, base_sha, mode,
                    merge_queue_capability, status, attempt_limit,
                    max_queue_reenqueues, requested_at_ms, merge_method)
                VALUES ('merge-op-%1$s', 'merge-auth-%1$s',
                    'remote-stage-%1$s', 'task-%1$s', 1, 1,
                    'merge-operation-%1$s', 1, 'head-%1$s', 'base-%1$s',
                    'DIRECT', 'UNSUPPORTED', 'REQUESTED', 3, 0, 76, 'rebase')
                """.formatted(task);
    }

    private static String queueOperationSql(int task)
    {
        return """
                INSERT INTO remote_merge_operation(
                    id, merge_authorization_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, head_sha, base_sha, mode,
                    merge_queue_capability, status, attempt_limit,
                    max_queue_reenqueues, requested_at_ms, merge_method)
                VALUES ('merge-op-%1$s', 'merge-auth-%1$s',
                    'remote-stage-%1$s', 'task-%1$s', 1, 1,
                    'merge-operation-%1$s', 1, 'head-%1$s', 'base-%1$s',
                    'MERGE_QUEUE', 'SUPPORTED', 'REQUESTED', 4, 1, 76, 'rebase')
                """.formatted(task);
    }

    private static String ticketSql(int task)
    {
        return """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES ('merge-ticket-%1$s', 'merge-operation-%1$s',
                    'MERGE_REMOTE_PULL_REQUEST', 'MERGE', 'STAGE',
                    'remote-stage-%1$s', 'REMOTE_MERGE_RESULT', 128, 0, 1, 0,
                    'workspace-1', 'trunk-1', 'task-%1$s', 1,
                    'remote-stage-%1$s', 1, 1, 'head-%1$s', 'base-%1$s',
                    'REQUESTED', 80)
                """.formatted(task);
    }

    private static String attemptSql(int task)
    {
        return """
                INSERT INTO remote_merge_effect_attempt(
                    id, merge_operation_id, ordinal, effect_kind,
                    readiness_evidence_id, idempotency_key, attempt_key,
                    claim_mode, status, claim_owner, claimed_at_ms,
                    lease_until_ms)
                VALUES ('attempt-%1$s', 'merge-op-%1$s', 1, 'DIRECT_MERGE',
                    'readiness-%1$s-1', 'merge-operation-%1$s:direct',
                    'merge-operation-%1$s:direct:attempt:1', 'EXECUTE',
                    'CLAIMED', 'worker', 90, 100)
                """.formatted(task);
    }

    private static String terminalIntentSql(String head)
    {
        return terminalIntentSql("terminal-intent-1", "snapshot-1-1", head);
    }

    private static String terminalIntentSql(
            String intentId, String snapshotId, String head)
    {
        return """
                INSERT INTO task_terminal_intent(
                    id, task_id, kind, source, source_id, observed_head_sha,
                    evidence_json, accepted, recorded_at_ms)
                VALUES ('%1$s', 'task-1', 'COMPLETED',
                    'REMOTE_OBSERVATION', '%2$s', '%3$s',
                    'observer merged', 1, 70)
                """.formatted(intentId, snapshotId, head);
    }
}
