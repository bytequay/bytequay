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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler;
import com.bytequay.app.developmentflow.execution.remote.SqliteRemoteFeedbackEffectOperationStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.stage.V2ReadinessAssistanceRuntime;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.AssistanceKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.Availability;
import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

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
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReadinessAssistanceProtocol
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void authorizesOnlyOneExplicitExactHeadEffectAndReusesFeedbackExecution()
            throws Exception
    {
        Database database = database("manual-assistance.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_readiness_assistance_v273
                    """)).isZero();
        }
        SqliteReadinessAssistanceStore store = store(database);
        Availability available = store.availability(
                "task-1", "remote-stage-1").orElseThrow();
        AuthorizationRequest request = request(
                "command-1", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request.");

        var first = store.authorize(request, NOW);
        var duplicate = store.authorize(request, NOW.plusSeconds(1));
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(store.availability("task-1", "remote-stage-1")).isEmpty();
        try (Connection connection = connect(database.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_readiness_assistance_v273
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM outbox
                    WHERE topic = 'V2_DISPATCH_TICKET_REQUESTED'
                      AND aggregate_id = (
                          SELECT dispatch_ticket_id
                          FROM remote_readiness_assistance_dispatch_v273)
                    """)).isOne();
        }

        SqliteRemoteFeedbackEffectOperationStore effects =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());
        RemoteFeedbackEffectOperationHandler.Effect effect =
                effects.require(first.operationId());
        assertThat(effect.kind()).isEqualTo(
                RemoteFeedbackEffectOperationHandler.EffectKind
                        .POST_MAINTAINER_NUDGE);
        assertThat(effect.operationKind()).isEqualTo(
                RemoteFeedbackEffectOperationHandler
                        .READINESS_ASSISTANCE_OPERATION_KIND);
        assertThat(effect.callbackRoute()).isEqualTo(
                RemoteFeedbackEffectOperationHandler
                        .READINESS_ASSISTANCE_CALLBACK_ROUTE);
        assertThat(effect.headSha()).isEqualTo("head-1");
        assertThat(effect.baseSha()).isEqualTo("base-1");

        effect = effects.claim(
                effect.id(), 0,
                RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                "worker", NOW, NOW.plusSeconds(30));
        effects.finishSucceeded(
                effect.id(), effect.attemptCount(), "issue-comment:41",
                "maintainer nudge 41 on head-1", NOW.plusSeconds(2));
        assertThat(store.require(first.operationId()).status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsMergeCapableStalePolicyAndMovedHeadSubjects()
            throws Exception
    {
        Database database = database("stale-assistance.db", 1, 2, 3);
        Availability stalePolicy;
        Availability staleHead;
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, true);
            seedExactReady(connection, 2, false);
            seedExactReady(connection, 3, false);
        }
        SqliteReadinessAssistanceStore store = store(database);
        assertThat(store.availability("task-1", "remote-stage-1")).isEmpty();
        stalePolicy = store.availability(
                "task-2", "remote-stage-2").orElseThrow();
        staleHead = store.availability(
                "task-3", "remote-stage-3").orElseThrow();

        try (Connection connection = connect(database.url())) {
            execute(connection, automationPolicySql(2, 2, 1));
            execute(connection, readinessSql(
                    2, "readiness-2-2", 2, 1, 0));

            insertSnapshot(connection, 3, 2, "new-head-3", false);
            insertGreenCi(connection, 3, 2, "new-head-3", "base-3");
            acceptSnapshot(connection, 3, 2, "new-head-3", "base-3");
        }

        assertThat(store.availability("task-2", "remote-stage-2")).isEmpty();
        assertThat(store.availability("task-3", "remote-stage-3")).isEmpty();
        assertThatThrownBy(() -> store.authorize(request(
                "stale-policy", "task-2", stalePolicy,
                AssistanceKind.REQUEST_REVIEWER, "reviewer-one",
                "reviewer-one"), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current exact truth");
        assertThatThrownBy(() -> store.authorize(request(
                "stale-head", "task-3", staleHead,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge."), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current exact truth");
    }

    @Test
    void exhaustsTheBoundedEffectAndRequiresFreshManualAuthorization()
            throws Exception
    {
        Database database = database("exhausted-assistance.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
        }
        SqliteReadinessAssistanceStore assistance = store(database);
        Availability available = assistance.availability(
                "task-1", "remote-stage-1").orElseThrow();
        var action = assistance.authorize(request(
                "exhaust-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."), NOW);
        SqliteRemoteFeedbackEffectOperationStore effects =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());

        for (int expectedAttempt = 0; expectedAttempt < 3; expectedAttempt++) {
            var claimed = effects.claim(
                    action.id(), expectedAttempt,
                    RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                    "worker", NOW.plusSeconds(expectedAttempt * 2L),
                    NOW.plusSeconds(expectedAttempt * 2L + 30));
            effects.finishFailed(
                    claimed.id(), claimed.attemptCount(), "remote failure",
                    NOW.plusSeconds(expectedAttempt * 2L + 1));
            assertThat(assistance.require(action.operationId()).status())
                    .isEqualTo(expectedAttempt == 2 ? "ABANDONED" : "FAILED");
            if (expectedAttempt < 2) {
                assertThat(assistance.availability(
                        "task-1", "remote-stage-1")).isEmpty();
            }
        }

        SqliteRemoteFeedbackEffectOperationStore afterRestart =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());
        var exhausted = afterRestart.require(action.operationId());
        assertThat(exhausted.status()).isEqualTo(
                RemoteFeedbackEffectOperationHandler.EffectStatus.ABANDONED);
        assertThatThrownBy(() -> afterRestart.claim(
                exhausted.id(), exhausted.attemptCount(),
                RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                "worker", NOW.plusSeconds(10), NOW.plusSeconds(40)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim lost");
        assertThat(assistance.availability("task-1", "remote-stage-1"))
                .isPresent();
        var reauthorized = assistance.authorize(request(
                "fresh-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."),
                NOW.plusSeconds(12));
        assertThat(reauthorized.id()).isNotEqualTo(action.id());
        assertThat(reauthorized.status()).isEqualTo("REQUESTED");
    }

    @Test
    void restartCanOnlyProbeAnExpiredClaimWithoutAnotherMutationAttempt()
            throws Exception
    {
        Database database = database("claimed-assistance.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
        }
        SqliteReadinessAssistanceStore assistance = store(database);
        Availability available = assistance.availability(
                "task-1", "remote-stage-1").orElseThrow();
        var action = assistance.authorize(request(
                "claim-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."), NOW);
        SqliteRemoteFeedbackEffectOperationStore beforeCrash =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());
        var claimed = beforeCrash.claim(
                action.id(), 0,
                RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                "first-worker", NOW, NOW.plusSeconds(30));
        assertThat(claimed.attemptCount()).isOne();

        SqliteRemoteFeedbackEffectOperationStore afterRestart =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());
        assertThatThrownBy(() -> afterRestart.claim(
                action.id(), 1,
                RemoteFeedbackEffectOperationHandler.ClaimMode.PROBE,
                "early-worker", NOW.plusSeconds(29), NOW.plusSeconds(59)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim lost");
        var recovered = afterRestart.claim(
                action.id(), 1,
                RemoteFeedbackEffectOperationHandler.ClaimMode.PROBE,
                "recovery-worker", NOW.plusSeconds(30), NOW.plusSeconds(60));
        assertThat(recovered.attemptCount()).isOne();
        afterRestart.finishSucceeded(
                recovered.id(), recovered.attemptCount(), "issue-comment:42",
                "recovered exact nudge proof", NOW.plusSeconds(31));
        assertThat(afterRestart.require(action.operationId()).status())
                .isEqualTo(
                        RemoteFeedbackEffectOperationHandler.EffectStatus.SUCCEEDED);
    }

    @Test
    void cancellationBeforeClaimTerminalizesTheManualAction()
            throws Exception
    {
        Database database = database("cancel-before-claim.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
        }
        SqliteReadinessAssistanceStore assistance = store(database);
        Availability available = assistance.availability(
                "task-1", "remote-stage-1").orElseThrow();
        var action = assistance.authorize(request(
                "cancel-before-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."), NOW);

        DispatchTicket.DeliveryReceipt receipt = runtime(database, assistance)
                .deliver(owner(action), fence(action),
                        DispatchTicket.DispatchResult.canceled(fence(action)));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(assistance.require(action.operationId()).status())
                .isEqualTo("ABANDONED");
        assertThat(assistance.availability("task-1", "remote-stage-1"))
                .isPresent();
    }

    @Test
    void cancellationAfterClaimTerminalizesTheRetryableFailure()
            throws Exception
    {
        Database database = database("cancel-after-claim.db", 1);
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
        }
        SqliteReadinessAssistanceStore assistance = store(database);
        Availability available = assistance.availability(
                "task-1", "remote-stage-1").orElseThrow();
        var action = assistance.authorize(request(
                "cancel-after-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."), NOW);
        SqliteRemoteFeedbackEffectOperationStore effects =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());
        var claimed = effects.claim(
                action.id(), 0,
                RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                "worker", NOW, NOW.plusSeconds(30));
        effects.finishFailed(
                claimed.id(), claimed.attemptCount(), "cancel requested",
                NOW.plusSeconds(1));
        assertThat(assistance.availability("task-1", "remote-stage-1"))
                .isEmpty();

        DispatchTicket.DeliveryReceipt receipt = runtime(database, assistance)
                .deliver(owner(action), fence(action),
                        DispatchTicket.DispatchResult.canceled(fence(action)));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(assistance.require(action.operationId()).status())
                .isEqualTo("ABANDONED");
        assertThat(assistance.availability("task-1", "remote-stage-1"))
                .isPresent();
    }

    @Test
    void purgeRequiresTerminalAssistanceAndAnAcceptedTerminalTicket()
            throws Exception
    {
        Database database = database("purge-assistance.db", 1);
        SqliteReadinessAssistanceStore assistance = store(database);
        Availability available;
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
            available = assistance.availability(
                    "task-1", "remote-stage-1").orElseThrow();
        }
        var action = assistance.authorize(request(
                "purge-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."), NOW);
        SqliteRemoteFeedbackEffectOperationStore effects =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());

        try (Connection connection = connect(database.url())) {
            assertFails(connection, """
                    DELETE FROM remote_readiness_assistance_v273
                    WHERE id = '%s'
                    """.formatted(action.id()));
            execute(connection,
                    "DROP TRIGGER v2_trunk_purge_authorization_insert_v269");
            execute(connection, """
                    UPDATE threads
                    SET lifecycle_state = 'ARCHIVED', aggregate_version = 1
                    WHERE id = 'trunk-1'
                    """);
            assertFails(connection, purgeAuthorizationSql());
        }

        for (int expectedAttempt = 0; expectedAttempt < 3; expectedAttempt++) {
            var claimed = effects.claim(
                    action.id(), expectedAttempt,
                    RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                    "worker", NOW.plusSeconds(expectedAttempt * 2L),
                    NOW.plusSeconds(expectedAttempt * 2L + 30));
            effects.finishFailed(
                    claimed.id(), claimed.attemptCount(), "remote failure",
                    NOW.plusSeconds(expectedAttempt * 2L + 1));
        }

        try (Connection connection = connect(database.url())) {
            assertFails(connection, purgeAuthorizationSql());
            terminalizeTicket(connection, action.operationId());
            execute(connection, purgeAuthorizationSql());
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM v2_trunk_purge_authorization_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isOne();
            execute(connection, """
                    DELETE FROM remote_readiness_assistance_v273
                    WHERE id = '%s'
                    """.formatted(action.id()));
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_readiness_assistance_v273
                    WHERE id = '%s'
                    """.formatted(action.id()))).isZero();
        }
    }

    @Test
    void realPurgeDeletesReadinessReceiptDispatchAndActionBeforeTheTask()
            throws Exception
    {
        Database database = database("real-purge-assistance.db", 1);
        SqliteReadinessAssistanceStore assistance = store(database);
        Availability available;
        try (Connection connection = connect(database.url())) {
            seedExactReady(connection, 1, false);
            available = assistance.availability(
                    "task-1", "remote-stage-1").orElseThrow();
        }
        var action = assistance.authorize(request(
                "real-purge-command", "task-1", available,
                AssistanceKind.POST_MAINTAINER_NUDGE, null,
                "Please merge this exact ready pull request."), NOW);
        SqliteRemoteFeedbackEffectOperationStore effects =
                new SqliteRemoteFeedbackEffectOperationStore(
                        database.jdbc(), database.transactions());
        var claimed = effects.claim(
                action.id(), 0,
                RemoteFeedbackEffectOperationHandler.ClaimMode.EXECUTE,
                "worker", NOW, NOW.plusSeconds(30));
        effects.finishSucceeded(
                claimed.id(), claimed.attemptCount(), "issue-comment:41",
                "maintainer nudge 41 on head-1", NOW.plusSeconds(1));
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence(action), DispatchTicket.Outcome.SUCCEEDED,
                "{}", "maintainer nudge 41 on head-1", null);
        DispatchTicket.DeliveryReceipt receipt = runtime(database, assistance)
                .deliver(owner(action), fence(action), result);
        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        try (Connection connection = connect(database.url())) {
            terminalizeSucceededTicket(connection, action.operationId());
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .prepareMergedCleanup(connection);
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .settleSuccessfulRuntimeCleanup(connection);
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .completeCleanup(connection);
            TestDevelopmentFlowCleanupOutcomeProtocolMigration
                    .terminalizeTask(connection);
            execute(connection, """
                    INSERT INTO trunk_transition(
                        id, trunk_id, command_id, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    SELECT 'deliver-outcome-transition', trunk_id, delivery_key,
                        'ACTIVE', 'ACTIVE', 1, 'ACCEPT_TASK_OUTCOME',
                        'test', 510
                    FROM trunk_outcome_inbox WHERE task_id = 'task-1'
                    """);
            execute(connection, """
                    UPDATE threads SET aggregate_version = 1
                    WHERE id = 'trunk-1'
                    """);
            execute(connection, """
                    UPDATE trunk_outcome_inbox
                    SET status = 'DELIVERED', delivered_at_ms = 510,
                        delivery_evidence = 'exact outcome delivery',
                        returned_trunk_version = 1
                    WHERE task_id = 'task-1'
                    """);
            execute(connection, """
                    UPDATE outbox
                    SET status = 'CLAIMED', attempts = attempts + 1,
                        claim_owner = 'purge-test',
                        lease_until_ms = available_at_ms + 1000
                    WHERE aggregate_kind = 'DISPATCH_TICKET'
                      AND status = 'PENDING'
                    """);
            execute(connection, """
                    UPDATE outbox
                    SET status = 'DELIVERED', claim_owner = NULL,
                        lease_until_ms = NULL,
                        delivered_at_ms = available_at_ms + 1
                    WHERE aggregate_kind = 'DISPATCH_TICKET'
                      AND status = 'CLAIMED'
                    """);
            execute(connection, """
                    UPDATE threads
                    SET lifecycle_state = 'ARCHIVED', aggregate_version = 2
                    WHERE id = 'trunk-1'
                    """);
            assertThat(number(connection, """
                    SELECT nonterminal_task_count + incomplete_cleanup_count
                         + open_wait_count + live_turn_count + live_ticket_count
                         + live_execution_count + live_operation_count
                         + incomplete_stage_count + live_lease_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isZero();
        }

        V2TrunkPurge purge = new V2TrunkPurge(
                database.jdbc(),
                new DataSourceTransactionManager(database.dataSource()));
        purge.delete("trunk-1", 2, () -> {
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*)
                    FROM remote_readiness_assistance_receipt_v273
                    """, Integer.class)).isZero();
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*)
                    FROM remote_readiness_assistance_dispatch_v273
                    """, Integer.class)).isZero();
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM remote_readiness_assistance_v273
                    """, Integer.class)).isZero();
            database.jdbc().update(
                    "DELETE FROM threads WHERE id = 'trunk-1'");
        });
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM threads WHERE id = 'trunk-1'",
                Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM v2_trunk_purge_authorization_v269",
                Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check",
                Integer.class)).isZero();
    }

    private Database database(String name, int... taskNumbers)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            for (int taskNumber : taskNumbers) {
                seedPublishedRemoteTask(connection, taskNumber);
            }
        }
        migrate(url, "273");
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        return new Database(
                url, jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                source);
    }

    private static SqliteReadinessAssistanceStore store(Database database)
    {
        return new SqliteReadinessAssistanceStore(
                database.jdbc(), database.transactions(),
                new SqliteDispatchWakeStore(database.jdbc()));
    }

    private static V2ReadinessAssistanceRuntime runtime(
            Database database, SqliteReadinessAssistanceStore store)
    {
        return new V2ReadinessAssistanceRuntime(
                store,
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(database.dataSource())),
                new ObjectMapper());
    }

    private static DispatchTicket.OwnerReference owner(
            SqliteReadinessAssistanceStore.Action action)
    {
        return new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.STAGE, action.stageId(),
                RemoteFeedbackEffectOperationHandler
                        .READINESS_ASSISTANCE_CALLBACK_ROUTE);
    }

    private static DispatchTicket.OperationFence fence(
            SqliteReadinessAssistanceStore.Action action)
    {
        return new DispatchTicket.OperationFence(
                action.taskEpoch(), action.stageId(), action.stageGeneration(),
                action.operationId(), 1, null, action.headSha(),
                action.baseSha());
    }

    private static void seedExactReady(
            Connection connection, int taskNumber, boolean viewerCanMerge)
            throws Exception
    {
        insertRemoteOwner(connection, taskNumber);
        insertCiPolicy(connection, taskNumber);
        insertSnapshot(
                connection, taskNumber, 1, "head-" + taskNumber,
                viewerCanMerge);
        acceptSnapshot(connection, taskNumber, 1,
                "head-" + taskNumber, "base-" + taskNumber);
        insertGreenCi(connection, taskNumber, 1,
                "head-" + taskNumber, "base-" + taskNumber);
        execute(connection, automationPolicySql(taskNumber, 1, 0));
        execute(connection, readinessSql(
                taskNumber, "readiness-" + taskNumber + "-1", 1, 0, 1));
        transitionReady(connection, taskNumber);
    }

    private static void insertSnapshot(
            Connection connection,
            int taskNumber,
            int revision,
            String head,
            boolean viewerCanMerge)
            throws Exception
    {
        execute(connection, """
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state,
                    observed_at_ms, merge_queue_capability,
                    viewer_login, viewer_can_merge)
                VALUES ('snapshot-%1$s-%2$s', 'remote-stage-%1$s',
                    'task-%1$s', 1, 1, 'binding-%1$s', %2$s,
                    'observation-%1$s-%2$s', 'acme/widget', 40 + %1$s,
                    '%3$s', 'base-%1$s', 'OPEN', 'MERGEABLE', 'NONE',
                    60 + %2$s, 'UNSUPPORTED', 'viewer-%1$s', %4$s)
                """.formatted(
                taskNumber, revision, head, viewerCanMerge ? 1 : 0));
    }

    private static void transitionReady(Connection connection, int taskNumber)
            throws Exception
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'READY_TO_MERGE'
                    WHERE id = 'remote-stage-%1$s' AND version = 0
                      AND checkpoint = 'WAITING_CI'
                    """.formatted(taskNumber));
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor,
                        occurred_at_ms)
                    VALUES ('ready-transition-%1$s', 'remote-stage-%1$s',
                        'ready-command-%1$s', 1, 'WAITING_CI',
                        'READY_TO_MERGE', 1, 'TEST_TRANSITION', 'test', 80)
                    """.formatted(taskNumber));
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
                    automation_policy_id, head_sha, base_sha, pr_open,
                    non_draft, ci_accepted, write_approval_count,
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

    private static AuthorizationRequest request(
            String commandId,
            String taskId,
            Availability available,
            AssistanceKind kind,
            String target,
            String payload)
    {
        return new AuthorizationRequest(
                commandId, taskId, available.taskEpoch(), available.stageId(),
                available.stageGeneration(), available.snapshotId(),
                available.readinessId(), available.policyId(),
                available.headSha(), available.baseSha(), kind, target, payload);
    }

    private static String purgeAuthorizationSql()
    {
        return """
                INSERT INTO v2_trunk_purge_authorization_v269(
                    trunk_id, archived_version, authorized_at_ms)
                VALUES ('trunk-1', 1, 100)
                """;
    }

    private static void terminalizeTicket(
            Connection connection, String operationId)
            throws Exception
    {
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_error = 'effect budget exhausted',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'remote-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = '%1$s',
                    pending_result_attempt = 1,
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE operation_id = '%1$s'
                """.formatted(operationId));
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 2, status = 'FAILED',
                    pending_result_outcome = NULL,
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = NULL,
                    pending_result_task_epoch = NULL,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = NULL,
                    pending_result_attempt = NULL,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = NULL,
                    pending_result_expected_base_sha = NULL,
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = '{}', completed_at_ms = 101
                WHERE operation_id = '%s'
                """.formatted(operationId));
        assertThat(text(connection, """
                SELECT status FROM dispatch_ticket
                WHERE operation_id = '%s'
                """.formatted(operationId))).isEqualTo("FAILED");
    }

    private static void terminalizeSucceededTicket(
            Connection connection, String operationId)
            throws Exception
    {
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = 'maintainer nudge proof',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'remote-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = '%1$s',
                    pending_result_attempt = 1,
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE operation_id = '%1$s'
                """.formatted(operationId));
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 2, status = 'SUCCEEDED',
                    pending_result_outcome = NULL,
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = NULL,
                    pending_result_task_epoch = NULL,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = NULL,
                    pending_result_attempt = NULL,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = NULL,
                    pending_result_expected_base_sha = NULL,
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'readiness assistance accepted',
                    completed_at_ms = 101
                WHERE operation_id = '%s'
                """.formatted(operationId));
    }

    private record Database(
            String url,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SQLiteDataSource dataSource)
    {
    }
}
