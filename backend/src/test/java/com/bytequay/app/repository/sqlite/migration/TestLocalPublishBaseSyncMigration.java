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
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Admission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.AuthorityKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Delivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ManualBlocker;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.OpenRequest;
import com.bytequay.app.testing.SqliteTestPools;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedApprovedEvidence;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedLocalDevelopmentTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestLocalPublishBaseSyncMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void emptyMigrationKeepsEveryInboundRequestForeignKeyCanonical()
            throws Exception
    {
        String url = migrated("empty-foreign-keys.db");
        try (Connection connection = connect(url)) {
            List<String> inbound = new ArrayList<>();
            for (String table : tableNames(connection)) {
                List<String> targets = foreignKeyTargets(connection, table);
                assertThat(targets)
                        .as("inbound request foreign keys from %s", table)
                        .doesNotContain("local_stage_turn_request_v314");
                if (targets.contains("local_stage_turn_request")) {
                    inbound.add(table);
                }
            }
            assertThat(inbound).contains(
                    "local_brain_turn_delivery_receipt",
                    "local_initial_implementation_receipt",
                    "local_stage_turn_retry_v298",
                    "local_publish_base_sync_start_receipt");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void forwardMigrationAddsDurablePauseRetryAndExtensionState()
            throws Exception
    {
        String url = migrated("pause-retry-schema.db");
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM pragma_table_info(
                        'local_publish_base_sync_episode')
                    WHERE name IN ('retry_of_episode_id', 'resume_cursor')
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM pragma_table_info(
                        'local_publish_base_sync_operation')
                    WHERE name = 'generation'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_schema
                    WHERE type = 'table' AND name IN (
                        'local_publish_base_sync_pause_receipt',
                        'local_publish_base_sync_resume_receipt',
                        'local_publish_base_sync_cancel_receipt',
                        'local_publish_base_sync_budget_extension')
                    """)).isEqualTo(4);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_schema
                    WHERE type = 'trigger' AND name IN (
                        'local_publish_base_sync_pause_receipt_insert',
                        'local_publish_base_sync_resume_receipt_insert',
                        'local_publish_base_sync_cancel_receipt_insert',
                        'local_publish_base_sync_budget_extension_insert')
                    """)).isEqualTo(4);
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void populatedInboundForeignKeySurvivesForwardMigration()
            throws Exception
    {
        String url = url("populated-foreign-key.db");
        DataSource dataSource = dataSource(url);
        Flyway.configure().dataSource(dataSource).target("314").load().migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedLocalDevelopmentTask(connection, 1);
            execute(connection, """
                    INSERT INTO stage_turn(
                        id, stage_id, stage_generation, purpose, status,
                        operation_id, attempt, task_epoch,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, delivery_lane, launch_input,
                        requested_at_ms)
                    VALUES ('steering-turn', 'local-stage-1', 1,
                        'USER_STEERING', 'QUEUED', 'steering-operation', 1, 1,
                        'fingerprint-1', 'head-1', 'base-1', 'CLI', '{}', 20)
                    """);
            execute(connection, """
                    INSERT INTO local_stage_turn_request(
                        id, command_id, stage_turn_id, task_id,
                        local_development_stage_id, task_epoch,
                        stage_generation, kind, queue_mode, prompt_digest,
                        requested_by, requested_at_ms)
                    VALUES ('steering-request', 'steering-command',
                        'steering-turn', 'task-1', 'local-stage-1', 1, 1,
                        'STEERING', 'IMMEDIATE', '%s', 'user', 20)
                    """.formatted("a".repeat(64)));
            execute(connection, """
                    CREATE TABLE populated_inbound_request (
                        id TEXT PRIMARY KEY,
                        request_id TEXT NOT NULL
                            REFERENCES local_stage_turn_request(id))
                    """);
            execute(connection, """
                    INSERT INTO populated_inbound_request(id, request_id)
                    VALUES ('child-1', 'steering-request')
                    """);
            execute(connection, """
                    CREATE TABLE populated_inbound_cascade (
                        id TEXT PRIMARY KEY,
                        request_id TEXT NOT NULL REFERENCES
                            local_stage_turn_request(id) ON DELETE CASCADE)
                    """);
            execute(connection, """
                    INSERT INTO populated_inbound_cascade(id, request_id)
                    VALUES ('cascade-child', 'steering-request')
                    """);
            execute(connection, """
                    CREATE TABLE populated_inbound_set_null (
                        id TEXT PRIMARY KEY,
                        request_id TEXT REFERENCES
                            local_stage_turn_request(id) ON DELETE SET NULL)
                    """);
            execute(connection, """
                    INSERT INTO populated_inbound_set_null(id, request_id)
                    VALUES ('set-null-child', 'steering-request')
                    """);
            execute(connection, """
                    CREATE TABLE populated_inbound_set_default (
                        id TEXT PRIMARY KEY,
                        request_id TEXT NOT NULL DEFAULT 'missing-default'
                            REFERENCES local_stage_turn_request(id)
                            ON DELETE SET DEFAULT)
                    """);
            execute(connection, """
                    INSERT INTO populated_inbound_set_default(id, request_id)
                    VALUES ('set-default-child', 'steering-request')
                    """);
        }

        migrate(url);

        try (Connection connection = connect(url)) {
            assertThat(foreignKeyTargets(
                    connection, "populated_inbound_request"))
                    .containsExactly("local_stage_turn_request");
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM populated_inbound_request child
                    JOIN local_stage_turn_request request
                      ON request.id = child.request_id
                    WHERE child.id = 'child-1'
                      AND request.kind = 'STEERING'
                      AND request.base_sync_episode_id IS NULL
                      AND request.target_base_sha IS NULL
                    """)).isOne();
            for (String table : List.of(
                    "populated_inbound_cascade",
                    "populated_inbound_set_null",
                    "populated_inbound_set_default")) {
                assertThat(foreignKeyTargets(connection, table))
                        .containsExactly("local_stage_turn_request");
                assertThat(number(connection, "SELECT COUNT(*) FROM " + table
                        + " WHERE request_id = 'steering-request'"))
                        .as("preserved child in %s", table)
                        .isOne();
            }
            assertThat(foreignKeyDeleteActions(
                    connection, "populated_inbound_cascade"))
                    .containsExactly("CASCADE");
            assertThat(foreignKeyDeleteActions(
                    connection, "populated_inbound_set_null"))
                    .containsExactly("SET NULL");
            assertThat(foreignKeyDeleteActions(
                    connection, "populated_inbound_set_default"))
                    .containsExactly("SET DEFAULT");
            assertSubtypeRejected(connection, "STEERING", "NULL", "'target-base'");
            assertSubtypeRejected(
                    connection, "STEERING", "'missing-episode'", "'target-base'");
            assertSubtypeRejected(
                    connection, "BASE_SYNC", "'missing-episode'", "NULL");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void migrationConnectionImmediatelyUsesRebuiltSubtypeAndPurgeView()
            throws Exception
    {
        String url = url("same-connection.db");
        Flyway.configure().dataSource(dataSource(url)).target("314").load().migrate();
        seedFailedBaseMove(url);
        insertBranchPolicy(url, false);

        try (Connection connection = connect(url)) {
            long liveOperationsBeforeMigration = number(connection, """
                    SELECT live_operation_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM local_stage_turn_request
                    WHERE kind = 'BASE_SYNC'
                    """)).isZero();

            SingleConnectionDataSource dataSource =
                    new SingleConnectionDataSource(connection, true);
            Flyway.configure().dataSource(dataSource).load().migrate();
            assertThat(number(connection, "PRAGMA foreign_keys")).isOne();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TransactionTemplate transactions = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));
            SqliteLocalPublishBaseSyncStore store =
                    new SqliteLocalPublishBaseSyncStore(jdbc);

            Admission admission = transactions.execute(status -> store.open(
                    new OpenRequest(
                            "same-connection-base-sync",
                            "publish-operation-1", "target-base",
                            "branch-policy-1",
                            AuthorityKind.STANDING_TASK_POLICY, "policy-1",
                            null, null, Instant.ofEpochMilli(60))));
            assertThat(admission).isNotNull();
            assertThat(number(connection, """
                    SELECT live_operation_count
                    FROM v2_trunk_purge_state_v269
                    WHERE trunk_id = 'trunk-1'
                    """)).isEqualTo(liveOperationsBeforeMigration + 2);

            // Isolate the rebuilt table CHECK from its domain trigger. The
            // same physical connection must accept the newly added kind.
            execute(connection,
                    "DROP TRIGGER local_stage_turn_request_insert");
            execute(connection, "PRAGMA foreign_keys = OFF");
            execute(connection, """
                    INSERT INTO local_stage_turn_request(
                        id, command_id, stage_turn_id, task_id,
                        local_development_stage_id, task_epoch,
                        stage_generation, kind, queue_mode,
                        base_sync_episode_id, target_base_sha, prompt_digest,
                        requested_by, requested_at_ms)
                    VALUES ('same-connection-request',
                        'same-connection-request-command',
                        'missing-turn', 'missing-task', 'missing-stage', 1, 1,
                        'BASE_SYNC', 'IMMEDIATE', 'missing-episode',
                        'target-base', '%s', 'runtime-test', 61)
                    """.formatted("d".repeat(64)));
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM local_stage_turn_request
                    WHERE id = 'same-connection-request' AND kind = 'BASE_SYNC'
                    """)).isOne();
        }
    }

    @Test
    void disabledScheduledPolicyStillBoundsStandingFirstPublishRepair()
            throws Exception
    {
        String url = migrated("disabled-policy.db");
        seedFailedBaseMove(url);
        insertBranchPolicy(url, false);
        StoreHarness harness = harness(url);

        Admission admission = harness.transactions().execute(status ->
                harness.store().open(new OpenRequest(
                        "open-standing-base-sync", "publish-operation-1",
                        "target-base", "branch-policy-1",
                        AuthorityKind.STANDING_TASK_POLICY, "policy-1",
                        null, null, Instant.ofEpochMilli(60))));

        assertThat(admission).isNotNull();
        assertThat(admission.episode().status()).isEqualTo("FETCHING");
        assertThat(admission.episode().attemptLimit()).isEqualTo(2);
        assertThat(harness.store().requireTaskId(
                admission.fetchOperation().operationId())).isEqualTo("task-1");
        assertThat(harness.store().requireByOperationId(
                admission.fetchOperation().operationId()).operationKind())
                .isEqualTo("FETCH_LOCAL_PUBLISH_BASE");
        assertThat(harness.jdbc().queryForObject("""
                SELECT live_operation_count
                FROM v2_trunk_purge_state_v269
                WHERE trunk_id = 'trunk-1'
                """, Integer.class)).isGreaterThan(0);
    }

    @Test
    void manualApprovalUsesAcceptedPostDeliveryProof()
            throws Exception
    {
        String url = migrated("manual-post-delivery.db");
        seedFailedBaseMove(url);
        insertBranchPolicy(url, false);
        StoreHarness harness = harness(url);
        ManualBlocker blocker = harness.transactions().execute(status ->
                harness.store().openManualBlocker(
                        "publish-operation-1", "target-base",
                        Instant.ofEpochMilli(60)));
        assertThat(blocker).isNotNull();

        harness.jdbc().update("""
                INSERT INTO publish_delivery_receipt(
                    operation_id, raw_result_digest, outcome, acceptance,
                    remote_stage_id, delivered_at_ms)
                VALUES ('publish-operation-id-1', ?, 'FAILED', 'ACCEPTED',
                    NULL, 61)
                """, "b".repeat(64));
        settlePublishTicket(harness.jdbc());

        Admission admission = harness.transactions().execute(status ->
                harness.store().open(new OpenRequest(
                        "approve-manual-base-sync", "publish-operation-1",
                        "target-base", "branch-policy-1", AuthorityKind.MANUAL,
                        null, blocker.id(), "user", Instant.ofEpochMilli(62))));

        assertThat(admission).isNotNull();
        assertThat(admission.episode().authorityKind())
                .isEqualTo(AuthorityKind.MANUAL);
        assertThat(harness.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE id = ?
                """, String.class, blocker.id())).isEqualTo("RESOLVED");
        assertThat(harness.jdbc().queryForObject("""
                SELECT status FROM dispatch_ticket WHERE id = 'publish-ticket-1'
                """, String.class)).isEqualTo("FAILED");
    }

    @Test
    void resultAfterCurrentStageRemovalSettlesAsSuperseded()
            throws Exception
    {
        String url = migrated("removed-current-stage.db");
        seedFailedBaseMove(url);
        insertBranchPolicy(url, false);
        StoreHarness harness = harness(url);
        Admission admission = harness.transactions().execute(status ->
                harness.store().open(new OpenRequest(
                        "open-standing-base-sync", "publish-operation-1",
                        "target-base", "branch-policy-1",
                        AuthorityKind.STANDING_TASK_POLICY, "policy-1",
                        null, null, Instant.ofEpochMilli(60))));
        assertThat(admission).isNotNull();
        armBaseSyncResult(harness.jdbc(),
                admission.fetchOperation().operationId());
        // Emulate the terminal-cleanup projection; its domain transition is
        // covered by the Cleanup suites.
        harness.jdbc().execute("DROP TRIGGER v2_task_current_stage_delete");
        harness.jdbc().update(
                "DELETE FROM task_current_stage WHERE task_id = 'task-1'");

        Delivery delivery = harness.store().requireDelivery(
                admission.fetchOperation().operationId());
        assertThat(delivery.current()).isFalse();
        DeliveryReceipt receipt = harness.transactions().execute(status ->
                harness.store().finish(
                        delivery, DispatchTicket.Outcome.SUCCEEDED,
                        "c".repeat(64), DispatchTicket.Acceptance.SUPERSEDED,
                        null, Instant.ofEpochMilli(61)));

        assertThat(receipt).isNotNull();
        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(harness.store().findEpisode(admission.episode().id()))
                .get().extracting(SqliteLocalPublishBaseSyncStore.Episode::status)
                .isEqualTo("SUPERSEDED");
    }

    private String migrated(String name)
    {
        String url = url(name);
        migrate(url);
        return url;
    }

    private String url(String name)
    {
        return "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
    }

    private static DataSource dataSource(String url)
    {
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static StoreHarness harness(String url)
    {
        DataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new StoreHarness(
                jdbc, new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)),
                new SqliteLocalPublishBaseSyncStore(jdbc));
    }

    private static List<String> foreignKeyTargets(
            Connection connection, String table)
            throws SQLException
    {
        try (var statement = connection.prepareStatement(
                    "SELECT \"table\" FROM pragma_foreign_key_list(?) "
                            + "WHERE \"table\" LIKE 'local_stage_turn_request%'")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                var targets = new ArrayList<String>();
                while (rows.next()) {
                    targets.add(rows.getString(1));
                }
                return List.copyOf(targets);
            }
        }
    }

    private static List<String> tableNames(Connection connection)
            throws SQLException
    {
        try (var statement = connection.prepareStatement("""
                SELECT name FROM sqlite_schema
                WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                ORDER BY name
                """);
                var rows = statement.executeQuery()) {
            var names = new ArrayList<String>();
            while (rows.next()) {
                names.add(rows.getString(1));
            }
            return List.copyOf(names);
        }
    }

    private static List<String> foreignKeyDeleteActions(
            Connection connection, String table)
            throws SQLException
    {
        try (var statement = connection.prepareStatement(
                    "SELECT on_delete FROM pragma_foreign_key_list(?) "
                            + "WHERE \"table\" = 'local_stage_turn_request'")) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                var actions = new ArrayList<String>();
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
                return List.copyOf(actions);
            }
        }
    }

    private static void assertSubtypeRejected(
            Connection connection, String kind, String episode, String target)
    {
        assertThatThrownBy(() -> execute(connection, """
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch,
                    stage_generation, kind, queue_mode,
                    base_sync_episode_id, target_base_sha, prompt_digest,
                    requested_by, requested_at_ms)
                VALUES ('invalid-request', 'invalid-command',
                    'steering-turn', 'task-1', 'local-stage-1', 1, 1,
                    '%1$s', 'IMMEDIATE', %2$s, %3$s, '%4$s', 'user', 21)
                """.formatted(kind, episode, target, "c".repeat(64))))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("base-sync subtype is incomplete");
    }

    private static void seedFailedBaseMove(String url)
            throws Exception
    {
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedLocalDevelopmentTask(connection, 1);
            seedApprovedEvidence(connection, 1);
            execute(connection, manifestSql());
            execute(connection, authorizationSql());
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
            execute(connection, authorizePublishReceiptSql());
            execute(connection, """
                    INSERT INTO publish_operation(
                        id, publish_authorization_id,
                        local_development_stage_id, task_id, task_epoch,
                        stage_generation, operation_id, semantic_attempt,
                        code_fingerprint, expected_head_sha, expected_base_sha,
                        status, requested_at_ms)
                    VALUES ('publish-operation-1', 'authorization-1',
                        'local-stage-1', 'task-1', 1, 1,
                        'publish-operation-id-1', 1, 'fingerprint-1',
                        'head-1', 'base-1', 'REQUESTED', 32)
                    """);
            insertPublishSteps(connection);
            execute(connection, publishTicketSql());
            execute(connection, """
                    UPDATE publish_operation SET status = 'DISPATCHED'
                    WHERE id = 'publish-operation-1'
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'FAILED',
                        pending_result_payload =
                            '{"version":1,'
                            || '"publishOperationId":"publish-operation-1",'
                            || '"operationId":"publish-operation-id-1",'
                            || '"taskId":"task-1",'
                            || '"stageId":"local-stage-1",'
                            || '"disposition":"BASE_MOVED",'
                            || '"observedBaseSha":"target-base",'
                            || '"error":"base moved"}',
                        pending_result_evidence = 'typed base move',
                        pending_result_error = 'base moved',
                        pending_result_task_epoch = 1,
                        pending_result_stage_id = 'local-stage-1',
                        pending_result_stage_generation = 1,
                        pending_result_operation_id = 'publish-operation-id-1',
                        pending_result_attempt = 1,
                        pending_result_expected_code_fingerprint = 'fingerprint-1',
                        pending_result_expected_head_sha = 'head-1',
                        pending_result_expected_base_sha = 'base-1'
                    WHERE id = 'publish-ticket-1'
                    """);
            execute(connection, """
                    UPDATE publish_operation
                    SET status = 'FAILED', completed_at_ms = 50,
                        error_message = 'base moved'
                    WHERE id = 'publish-operation-1'
                    """);
            execute(connection, """
                    UPDATE publish_authorization SET revoked_at_ms = 50
                    WHERE id = 'authorization-1'
                    """);
            execute(connection, """
                    UPDATE stage SET version = 2, checkpoint = 'LOCAL_REVIEW'
                    WHERE id = 'local-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('publish-failure-transition-1', 'local-stage-1',
                        'accept-publish-failure-1', 1, 'PUBLISHING',
                        'LOCAL_REVIEW', 2, 'ACCEPT_PUBLISH_FAILURE',
                        'v2-publish-delivery', 51)
                    """);
            execute(connection, acceptPublishFailureReceiptSql());
        }
    }

    private static void insertBranchPolicy(String url, boolean enabled)
            throws Exception
    {
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-policy-1', 'task-1', 1, %s,
                        'first-push', 'FIRST_PUSH_DEFAULT', 2,
                        'arm-first-push', 'runtime', 52)
                    """.formatted(enabled ? 1 : 0));
        }
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
        for (int ordinal = 1; ordinal <= kinds.length; ordinal++) {
            execute(connection, """
                    INSERT INTO publish_effect_step(
                        id, publish_operation_id, ordinal, kind,
                        idempotency_key, status, attempt_limit)
                    VALUES ('publish-step-1-%1$s', 'publish-operation-1',
                        %1$s, '%2$s', 'publish-operation-1:%2$s',
                        'REQUESTED', 3)
                    """.formatted(ordinal, kinds[ordinal - 1]));
        }
    }

    private static void settlePublishTicket(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'FAILED',
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
                    delivery_evidence = 'publish failure accepted',
                    completed_at_ms = 61
                WHERE id = 'publish-ticket-1'
                """);
    }

    private static void armBaseSyncResult(JdbcTemplate jdbc, String operationId)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = 'fetched target',
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = stage_id,
                    pending_result_stage_generation = stage_generation,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint =
                        expected_code_fingerprint,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE operation_id = ?
                """, operationId);
    }

    private static String manifestSql()
    {
        return """
                INSERT INTO promotion_manifest(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, pr_id, policy_revision_id,
                    revision, code_fingerprint, head_sha, base_sha, route,
                    base_repository_id, head_repository_id, publish_repository_id,
                    branch_name, head_ref, base_branch, require_clean_worktree,
                    minimum_commits_ahead, require_branch_match,
                    require_base_match, require_publish_permission, pr_title,
                    pr_body, pr_content_revision, pr_content_digest, created_at_ms)
                VALUES ('manifest-1', 'local-stage-1', 'task-1', 1, 1,
                    'report-1', 'pr-1', 'policy-1', 1, 'fingerprint-1',
                    'head-1', 'base-1', 'DIRECT', 'acme/widget', 'acme/widget',
                    'acme/widget', 'dev/task-1', 'dev/task-1', 'main',
                    1, 1, 1, 1, 1, 'Implement feature 1', 'Description',
                    1, 'pr-digest-1', 30)
                """;
    }

    private static String authorizationSql()
    {
        return """
                INSERT INTO publish_authorization(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, manifest_id, dev_report_id,
                    validation_evidence_id, brain_review_episode_id, pr_id,
                    policy_revision_id, code_fingerprint, head_sha, base_sha,
                    route, base_repository_id, head_repository_id,
                    publish_repository_id, branch_name, head_ref, base_branch,
                    pr_content_revision, pr_content_digest, consent_kind,
                    consent_id, actor_id, brain_basis, authorized_operation_id,
                    authorized_attempt, created_at_ms)
                VALUES ('authorization-1', 'local-stage-1', 'task-1', 1, 1,
                    'manifest-1', 'report-1', 'validation-evidence-1',
                    'brain-episode-1', 'pr-1', 'policy-1', 'fingerprint-1',
                    'head-1', 'base-1', 'DIRECT', 'acme/widget', 'acme/widget',
                    'acme/widget', 'dev/task-1', 'dev/task-1', 'main', 1,
                    'pr-digest-1', 'STANDING_TASK', 'policy-1', 'policy',
                    'APPROVED', 'publish-operation-id-1', 1, 31)
                """;
    }

    private static String publishTicketSql()
    {
        return """
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
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1',
                    1, 1, 'fingerprint-1', 'head-1', 'base-1',
                    'REQUESTED', 32)
                """;
    }

    private static String authorizePublishReceiptSql()
    {
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
                VALUES ('publish-receipt-1', 'local-stage-1', 'task-1',
                    'approve-and-ship-command-1', 'AUTHORIZE_PUBLISH', 'policy',
                    'APPLIED', 1, 1, 0, 'LOCAL_REVIEW', 1, 'local-stage-1', 1,
                    'publish-operation-id-1', 1, 'fingerprint-1', 'head-1',
                    'base-1', 'authorization-1', 'LOCAL_DEVELOPMENT', 1, 1,
                    'PUBLISHING', 1, 'local-stage-1', 1,
                    'publish-operation-id-1', 1, 'fingerprint-1', 'head-1',
                    'base-1', 31)
                """;
    }

    private static String acceptPublishFailureReceiptSql()
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
                VALUES ('publish-failure-receipt-1', 'local-stage-1', 'task-1',
                    'accept-publish-failure-1', 'ACCEPT_PUBLISH_FAILURE',
                    'v2-publish-delivery', 'APPLIED', 1, 'local-stage-1', 1,
                    'publish-operation-id-1', 1, 'fingerprint-1', 'head-1',
                    'base-1', 'LOCAL_DEVELOPMENT', 1, 2, 'LOCAL_REVIEW', 51)
                """;
    }

    private record StoreHarness(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SqliteLocalPublishBaseSyncStore store) {}
}
