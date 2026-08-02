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

import com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.AdoptionResult;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Disposition;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Operation;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.ResultReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.NormalizationDue;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.NormalizationOperation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BaseRepairAuthorization;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRemoteRepairResultNormalizationMigration
{
    private static final Instant NOW = Instant.ofEpochMilli(600);
    private static final String DIGEST = "a".repeat(64);
    private static final String MALFORMED = """
            The repair is complete.

            {"schemaVersion":1,"summary":"fixed the exact failure"}
            """;

    @TempDir
    private Path tempDir;

    @Test
    void exactHistoricalMalformedCiStageBecomesOnePendingLegacyDue()
            throws Exception
    {
        Seeded seeded = seedV321("malformed-stage.db", true);
        migrateLatest(seeded.dataSource());

        try (Connection connection = connect(seeded.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    """)).isOne();
            assertThat(text(connection, """
                    SELECT source_malformed_output
                    FROM remote_repair_result_normalization_due_v322
                    """)).isEqualTo(MALFORMED);
            assertThat(text(connection, """
                    SELECT candidate_capture_kind
                    FROM remote_repair_result_normalization_due_v322
                    """)).isEqualTo("LEGACY_REFLOG_WINDOW_V1");
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_legacy_eligibility_v322 eligibility
                    JOIN remote_repair_result_normalization_due_v322 due
                      ON due.source_operation_row_id =
                         eligibility.source_operation_row_id
                    WHERE eligibility.ticket_window = 'FAILED_DELIVERED'
                      AND eligibility.legacy_output_subject_shape = 'NULL_V1'
                      AND due.source_code_subject_revision =
                          eligibility.source_code_subject_revision
                      AND due.source_code_subject_kind =
                          eligibility.source_code_subject_kind
                      AND due.source_code_subject_id =
                          eligibility.source_code_subject_id
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322 due
                    JOIN task_current_code_subject_fence_v322 code
                      ON code.task_id = due.task_id
                     AND code.source_code_subject_revision =
                         due.source_code_subject_revision
                     AND code.source_code_subject_kind =
                         due.source_code_subject_kind
                     AND code.source_code_subject_id =
                         due.source_code_subject_id
                    """)).isOne();
            assertThat(text(connection, """
                    SELECT status
                    FROM remote_repair_result_normalization_due_v322
                    """)).isEqualTo("PENDING");
            assertThat(number(connection, """
                    SELECT source_execution_finished_at_ms
                         - source_execution_started_at_ms
                    FROM remote_repair_result_normalization_due_v322
                    """)).isEqualTo(100);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_repair_result_normalization_due_v322
                    WHERE candidate_code_fingerprint IS NULL
                      AND candidate_head_sha IS NULL
                      AND candidate_parent_sha IS NULL
                      AND candidate_source_tree_sha IS NULL
                      AND candidate_result_tree_sha IS NULL
                    """)).isOne();

            assertThat(text(connection, """
                    SELECT status FROM ci_repair_operation
                    WHERE id = 'source-operation-row'
                    """)).isEqualTo("FAILED");
            assertThat(text(connection, """
                    SELECT status FROM stage_turn
                    WHERE id = 'source-stage-turn'
                    """)).isEqualTo("FAILED");
            assertThat(text(connection, """
                    SELECT status FROM task_blocker
                    WHERE id = 'malformed-blocker'
                    """)).isEqualTo("OPEN");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM pragma_foreign_key_check
                    """)).isZero();

            assertThatThrownBy(() -> execute(connection, """
                    UPDATE remote_repair_result_normalization_due_v322
                    SET source_malformed_output = 'forged'
                    WHERE source_operation_row_id = 'source-operation-row'
                    """))
                    .hasMessageContaining("due identity is immutable");
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE remote_repair_legacy_eligibility_v322
                    SET source_malformed_output = 'forged'
                    WHERE source_operation_row_id = 'source-operation-row'
                    """))
                    .hasMessageContaining("legacy eligibility is immutable");
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO remote_repair_legacy_eligibility_v322(id)
                    VALUES ('forged')
                    """))
                    .hasMessageContaining("legacy eligibility is migration-only");
        }

        migrateLatest(seeded.dataSource());
        try (Connection connection = connect(seeded.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    """)).isOne();
        }
    }

    @Test
    void exactResultPendingCrashWindowBecomesOnePendingLegacyDue()
            throws Exception
    {
        Seeded seeded = seedV321(
                "result-pending-crash.db", true, false,
                SourceTicketWindow.RESULT_PENDING_AFTER_OWNER_DELIVERY,
                LegacyOutputShape.NULL_V1);
        migrateLatest(seeded.dataSource());

        try (Connection connection = connect(seeded.url())) {
            assertThat(text(connection, """
                    SELECT status FROM dispatch_ticket
                    WHERE id = 'source-ticket'
                    """)).isEqualTo("RESULT_PENDING");
            assertThat(text(connection, """
                    SELECT ticket_window
                    FROM remote_repair_legacy_eligibility_v322
                    """)).isEqualTo("RESULT_PENDING_AFTER_OWNER_DELIVERY");
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    WHERE status = 'PENDING'
                    """)).isOne();
        }
    }

    @Test
    void recognizedPreV322PartialSubjectIsNeverCandidateProof()
            throws Exception
    {
        Seeded seeded = seedV321(
                "partial-subject.db", true, false,
                SourceTicketWindow.FAILED_DELIVERED,
                LegacyOutputShape.PRE_V322_PARTIAL_V1);
        migrateLatest(seeded.dataSource());

        try (Connection connection = connect(seeded.url())) {
            assertThat(text(connection, """
                    SELECT legacy_output_subject_shape
                    FROM remote_repair_legacy_eligibility_v322
                    """)).isEqualTo("PRE_V322_PARTIAL_V1");
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    WHERE candidate_capture_kind = 'LEGACY_REFLOG_WINDOW_V1'
                      AND candidate_code_fingerprint IS NULL
                      AND candidate_head_sha IS NULL
                      AND candidate_parent_sha IS NULL
                      AND candidate_base_sha IS NULL
                      AND candidate_clean IS NULL
                      AND candidate_merge_base_sha IS NULL
                      AND candidate_source_tree_sha IS NULL
                      AND candidate_result_tree_sha IS NULL
                      AND candidate_source_head_merge_base_sha IS NULL
                      AND candidate_branch_name IS NULL
                    """)).isOne();
        }
    }

    @Test
    void inexactHistoricalPartialSubjectStaysManualWithoutAbortingMigration()
            throws Exception
    {
        Seeded seeded = seedV321(
                "inexact-partial-subject.db", true, false,
                SourceTicketWindow.FAILED_DELIVERED,
                LegacyOutputShape.INEXACT_V1);
        migrateLatest(seeded.dataSource());

        try (Connection connection = connect(seeded.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_legacy_eligibility_v322
                    """)).isZero();
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    """)).isZero();
            assertThat(text(connection, """
                    SELECT status FROM ci_repair_operation
                    WHERE id = 'source-operation-row'
                    """)).isEqualTo("FAILED");
            assertThat(text(connection, """
                    SELECT status FROM task_blocker
                    WHERE id = 'malformed-blocker'
                    """)).isEqualTo("OPEN");
        }
    }

    @Test
    void providerFailureIsNotPromotedIntoResultNormalization()
            throws Exception
    {
        Seeded seeded = seedV321("provider-failure.db", false);
        migrateLatest(seeded.dataSource());

        try (Connection connection = connect(seeded.url())) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    """)).isZero();
            assertThat(text(connection, """
                    SELECT status FROM ci_repair_operation
                    WHERE id = 'source-operation-row'
                    """)).isEqualTo("FAILED");
            assertThat(text(connection, """
                    SELECT status FROM task_blocker
                    WHERE id = 'malformed-blocker'
                    """)).isEqualTo("OPEN");
        }
    }

    @Test
    void compatibilityAuthorizationConsumptionLeavesOriginalClosed()
            throws Exception
    {
        CompatibilityFixture fixture = compatibilityFixture(
                "consume-compatibility-authorization.db");
        BaseRepairAuthorization original = assertClaimedCompatibility(fixture);

        fixture.commands().execute("task-1", () -> {
            fixture.store().consumeBaseRepairAuthorization(
                    original.id(), "normalized repair was consumed", NOW);
            return null;
        });

        assertTerminalCompatibility(
                fixture, original, "CONSUMED",
                "normalized repair was consumed");
    }

    @Test
    void compatibilityAuthorizationClosureLeavesOriginalClosed()
            throws Exception
    {
        CompatibilityFixture fixture = compatibilityFixture(
                "close-compatibility-authorization.db");
        BaseRepairAuthorization original = assertClaimedCompatibility(fixture);

        fixture.commands().execute("task-1", () -> {
            fixture.store().closeBaseRepairAuthorization(
                    original.id(), "normalized repair was closed", NOW);
            return null;
        });

        assertTerminalCompatibility(
                fixture, original, "CLOSED", "normalized repair was closed");
    }

    @Test
    void compatibilityBaseRepairContinuesOnlyFromItsExactRewrittenSubject()
            throws Exception
    {
        CompatibilityFixture fixture = compatibilityFixture(
                "compatibility-base-repair-continuation.db");
        completeLegacyAdoption(fixture);
        JdbcTemplate jdbc = fixture.jdbc();
        SqliteRemoteRepairTurnStore turns =
                new SqliteRemoteRepairTurnStore(jdbc);
        shadowCurrentSubject(
                jdbc, "candidate-shadow", "candidate-shadow-source",
                "candidate-fingerprint", "candidate-head", 705);
        assertThatThrownBy(() -> fixture.commands().execute("task-1", () ->
                turns.insertCiBaseRewriteValidation(
                        turns.requireContext("task-1", "remote-stage-1"),
                        fixture.store().requireCiEpisode(
                                "task-1", "episode-1"),
                        "base-authorization", Instant.ofEpochMilli(706))))
                .hasMessageContaining("lacks exact authorization");
        removeShadowCurrentSubject(jdbc, "candidate-shadow");
        var validation = fixture.commands().execute("task-1", () ->
                turns.insertCiBaseRewriteValidation(
                        turns.requireContext("task-1", "remote-stage-1"),
                        fixture.store().requireCiEpisode(
                                "task-1", "episode-1"),
                        "base-authorization", Instant.ofEpochMilli(710)));
        assertThat(jdbc.update("""
                UPDATE ci_repair_operation
                SET status = 'SUCCEEDED',
                    result_code_fingerprint = 'rewritten-fingerprint',
                    result_head_sha = 'rewritten-head',
                    result_evidence = 'exact rewrite passed',
                    completed_at_ms = 720
                WHERE id = ? AND status IN ('REQUESTED', 'DISPATCHED')
                """, validation.rowId())).isOne();
        assertThat(jdbc.update("""
                INSERT INTO ci_base_repair_rewrite_result_v303(
                    authorization_id, ci_repair_operation_id,
                    input_head_sha, output_head_sha, repair_commit_sha,
                    original_commits_json, proof_json, proof_digest,
                    validation_outcome, recorded_at_ms)
                VALUES ('base-authorization', ?, 'candidate-head',
                    'rewritten-head', 'repair-commit', '[]', '{}', ?,
                    'PASSED', 720)
                """, validation.rowId(), "e".repeat(64))).isOne();
        assertThat(jdbc.update("""
                INSERT INTO ci_base_repair_subject_v303(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, authorization_id,
                    ci_repair_operation_id, code_fingerprint,
                    head_sha, base_sha, recorded_at_ms)
                VALUES ('rewritten-subject', 'task-1', 1,
                    'remote-stage-1', 1, 'base-authorization', ?,
                    'rewritten-fingerprint', 'rewritten-head', 'base-1', 721)
                """, validation.rowId())).isOne();

        shadowCurrentSubject(
                jdbc, "shadow-subject", "shadow-source",
                "rewritten-fingerprint", "rewritten-head", 725);
        assertThat(jdbc.queryForMap("""
                SELECT source_code_subject_kind, source_code_subject_id
                FROM task_current_code_subject_fence_v322
                WHERE task_id = 'task-1'
                """))
                .containsEntry("source_code_subject_kind", "REMOTE_WORKTREE")
                .containsEntry("source_code_subject_id", "shadow-subject");
        assertThatThrownBy(() -> insertBrain(
                jdbc, "wrong-source-brain", "rewritten-fingerprint",
                "rewritten-head"))
                .hasMessageContaining("lacks exact authorization");
        removeShadowCurrentSubject(jdbc, "shadow-subject");

        assertThatThrownBy(() -> insertBrain(
                jdbc, "near-brain", "wrong-fingerprint", "rewritten-head"))
                .hasMessageContaining("lacks exact authorization");
        insertBrain(jdbc, "exact-brain", "rewritten-fingerprint",
                "rewritten-head");
        assertThat(jdbc.update("""
                UPDATE ci_repair_operation
                SET status = 'SUCCEEDED', completed_at_ms = 730
                WHERE id = 'exact-brain-row'
                """)).isOne();

        assertThatThrownBy(() -> insertPush(
                jdbc, "near-push", "rewritten-fingerprint", "wrong-head"))
                .hasMessageContaining("lacks exact authorization");
        insertPush(jdbc, "exact-push", "rewritten-fingerprint",
                "rewritten-head");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                WHERE id IN ('exact-brain-row', 'exact-push-row')
                  AND base_repair_authorization_id = 'base-authorization'
                """, Integer.class)).isEqualTo(2);
    }

    private Seeded seedV321(String fileName, boolean malformed)
            throws Exception
    {
        return seedV321(fileName, malformed, false,
                SourceTicketWindow.FAILED_DELIVERED,
                LegacyOutputShape.NULL_V1);
    }

    private Seeded seedV321(
            String fileName, boolean malformed, boolean baseRepair)
            throws Exception
    {
        return seedV321(fileName, malformed, baseRepair,
                SourceTicketWindow.FAILED_DELIVERED,
                LegacyOutputShape.NULL_V1);
    }

    private Seeded seedV321(
            String fileName,
            boolean malformed,
            boolean baseRepair,
            SourceTicketWindow sourceTicketWindow,
            LegacyOutputShape legacyOutputShape)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(fileName)
                + "?foreign_keys=ON";
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        Flyway.configure().dataSource(dataSource).target("321").load().migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertTypedSnapshot(connection);
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertEpisodeAndFailedTurn(
                    connection, malformed, baseRepair,
                    sourceTicketWindow, legacyOutputShape);
        }
        return new Seeded(url, dataSource);
    }

    private static void insertEpisodeAndFailedTurn(
            Connection connection,
            boolean malformed,
            boolean baseRepair,
            SourceTicketWindow sourceTicketWindow,
            LegacyOutputShape legacyOutputShape)
            throws Exception
    {
        insertRevisionBackedCurrentSubject(connection);
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    '%s', 'OPEN', 0, 3, 2, 3, 100)
                """.formatted(baseRepair
                ? "BASE_DETERMINISTIC" : "TASK_DETERMINISTIC"));
        if (baseRepair) {
            insertClosedBaseRepairAuthoritySource(connection);
        }
        insertObservedFailureFreshness(connection);
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('source-stage-turn', 'remote-stage-1', 1,
                    'REMOTE_CI_REPAIR', 'QUEUED', 'source-operation', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'API',
                    '{"transport":"API","provider":"openai",'
                    || '"model":"model","workingDirectory":"/tmp/task-1"}',
                    101)
                """);
        if (baseRepair) {
            execute(connection, """
                    INSERT INTO ci_repair_operation(
                        id, ci_repair_episode_id, remote_development_stage_id,
                        task_id, task_epoch, stage_generation, kind,
                        operation_id, semantic_attempt, stage_turn_id,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms,
                        base_repair_authorization_id, lease_expected_sha)
                    VALUES ('source-operation-row', 'episode-1',
                        'remote-stage-1', 'task-1', 1, 1, 'FIX_STAGE_TURN',
                        'source-operation', 1, 'source-stage-turn',
                        'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 101,
                        'base-authorization', 'head-1')
                    """);
        }
        else {
            execute(connection, """
                    INSERT INTO ci_repair_operation(
                        id, ci_repair_episode_id, remote_development_stage_id,
                        task_id, task_epoch, stage_generation, kind,
                        operation_id, semantic_attempt, stage_turn_id,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, requested_at_ms)
                    VALUES ('source-operation-row', 'episode-1',
                        'remote-stage-1', 'task-1', 1, 1, 'FIX_STAGE_TURN',
                        'source-operation', 1, 'source-stage-turn',
                        'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 101)
                    """);
        }
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('source-ticket', 'source-operation',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'source-stage-turn', 'REMOTE_CI_STAGE_TURN_RESULT', 2,
                    0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'remote-stage-1', 1, 1, 'fingerprint-1', 'head-1',
                    'base-1', 'REQUESTED', 101)
                """);
        execute(connection, """
                UPDATE ci_repair_operation SET status = 'DISPATCHED'
                WHERE id = 'source-operation-row'
                """);
        execute(connection, """
                UPDATE ci_repair_episode SET status = 'FIXING'
                WHERE id = 'episode-1'
                """);

        String disposition = malformed
                ? "OWNER_OUTPUT_MALFORMED" : "PROVIDER_FAILED";
        String error = malformed
                ? "OWNER_OUTPUT_MALFORMED: expected strict JSON"
                : "provider session failed";
        String finalText = malformed ? MALFORMED : "";
        ObjectMapper json = new ObjectMapper();
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("turnId", "source-stage-turn");
        payload.put("ownerKind", "STAGE_TURN");
        payload.put("purpose", "REMOTE_CI_REPAIR");
        payload.put("finalText", finalText);
        payload.put("inputTokens", 1);
        payload.put("outputTokens", 1);
        payload.put("costUsdMilli", 0);
        payload.put("disposition", disposition);
        payload.put("error", error);
        ObjectNode outputCodeSubject = null;
        if (legacyOutputShape == LegacyOutputShape.NULL_V1) {
            payload.putNull("outputCodeSubject");
        }
        else {
            ObjectNode subject = payload.putObject("outputCodeSubject");
            outputCodeSubject = subject;
            subject.put("codeFingerprint", "candidate-fingerprint");
            subject.put("headSha", "candidate-head");
            subject.put("baseSha", "base-1");
            subject.put("clean", true);
            subject.put("mergeBaseSha", "base-1");
            subject.put("sourceTreeSha", "source-tree");
            subject.put("resultTreeSha", "result-tree");
            subject.put("sourceHeadMergeBaseSha", "head-1");
            subject.put("branchName", "dev/task-1");
            if (legacyOutputShape == LegacyOutputShape.INEXACT_V1) {
                subject.put("unknownProof", "not-recognized");
            }
        }
        String payloadJson = json.writeValueAsString(payload);

        ObjectNode fence = json.createObjectNode();
        fence.put("taskEpoch", 1);
        fence.put("stageId", "remote-stage-1");
        fence.put("stageGeneration", 1);
        fence.put("operationId", "source-operation");
        fence.put("attempt", 1);
        fence.put("expectedCodeFingerprint", "fingerprint-1");
        fence.put("expectedHeadSha", "head-1");
        fence.put("expectedBaseSha", "base-1");
        ObjectNode raw = json.createObjectNode();
        raw.set("fence", fence);
        raw.put("outcome", "FAILED");
        raw.put("payloadJson", payloadJson);
        String evidenceJson = "{}";
        if (outputCodeSubject != null) {
            ObjectNode evidence = json.createObjectNode();
            evidence.put("schemaVersion", 1);
            evidence.put("disposition", disposition);
            evidence.putObject("writerFence");
            evidence.set("outputCodeSubject", outputCodeSubject.deepCopy());
            evidenceJson = json.writeValueAsString(evidence);
        }
        raw.put("evidenceJson", evidenceJson);
        raw.put("error", error);
        String rawJson = json.writeValueAsString(raw);

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = 1, started_at_ms = 200,
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = ?,
                    pending_result_evidence = ?,
                    pending_result_error = ?,
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'remote-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'source-operation',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = 'source-ticket'
                """)) {
            statement.setString(1, payloadJson);
            statement.setString(2, evidenceJson);
            statement.setString(3, error);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('source-execution', 'source-ticket', 1, 'openai',
                    'FAILED', 200, 300, ?)
                """)) {
            statement.setString(1, rawJson);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stage_turn
                SET status = 'FAILED', started_at_ms = 200,
                    finished_at_ms = 301, error_message = ?
                WHERE id = 'source-stage-turn'
                """)) {
            statement.setString(1, error);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE ci_repair_operation
                SET status = 'FAILED', completed_at_ms = 301,
                    error_message = ?
                WHERE id = 'source-operation-row'
                """)) {
            statement.setString(1, error);
            statement.executeUpdate();
        }
        execute(connection, """
                INSERT INTO ci_repair_delivery_receipt(
                    ci_repair_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES ('source-operation-row', 'source-operation', 'FAILED',
                    '%s', 'ACCEPTED', 301)
                """.formatted(DIGEST));
        if (sourceTicketWindow == SourceTicketWindow.FAILED_DELIVERED) {
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
                        delivery_evidence = 'delivered', completed_at_ms = 301,
                        last_error = 'owner result failed'
                    WHERE id = 'source-ticket'
                    """);
        }
        execute(connection, """
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('malformed-blocker', 'task-1', 'remote-stage-1',
                    'EPISODE', 'episode-1', 'head-1', '%s', 'OPEN', '{}', 301)
                """.formatted(malformed
                ? "CI_REPAIR_OUTPUT_MALFORMED" : "CI_REPAIR_TURN_FAILED"));
        if (baseRepair) {
            execute(connection, """
                    UPDATE ci_base_repair_authorization_v303
                    SET status = 'CLOSED', terminal_at_ms = 302,
                        terminal_evidence = 'malformed owner output'
                    WHERE id = 'base-authorization'
                    """);
        }
    }

    private static void insertRevisionBackedCurrentSubject(
            Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome,
                    pending_outcome, neutral_outcome, skipped_outcome,
                    canceled_outcome, created_by, created_at_ms)
                VALUES ('revision-ci-policy', 'task-1', 'binding-1', 2,
                    'REPOSITORY', 'WAITING', 'FAILED', 'WAITING', 'WAITING',
                    'WAITING', 'FAILED', 'FAILED', 'test', 75)
                """);
        execute(connection, """
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch,
                    stage_generation, head_sha, base_sha, normalized_status,
                    policy_outcome, check_count, missing_required_count,
                    evidence, evaluated_at_ms)
                VALUES ('revision-ci-evaluation', 'remote-stage-1',
                    'snapshot-1-1', 'revision-ci-policy', 'task-1', 1, 1,
                    'head-1', 'base-1', 'FAILED', 'FAILED', 1, 0,
                    'prior failure', 76)
                """);
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('revision-episode', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'revision-ci-evaluation', 'head-1', 'base-1',
                    'TASK_DETERMINISTIC', 'OPEN', 0, 3, 2, 3, 77)
                """);
        execute(connection, """
                INSERT INTO ci_repair_turn_freshness_v319(
                    id, ci_repair_episode_id, intent_kind, intent_id,
                    semantic_attempt, execution_attempt,
                    predecessor_snapshot_id,
                    predecessor_observation_revision,
                    accepted_snapshot_id, accepted_observation_revision,
                    accepted_ci_evaluation_id, remote_head_sha,
                    authoritative_base_sha, code_fingerprint, code_head_sha,
                    code_base_sha, prepublish_branch_sync_episode_id,
                    authorized_at_ms)
                VALUES ('revision-freshness', 'revision-episode',
                    'OBSERVED_FAILURE', 'revision-ci-evaluation', 1, 1,
                    NULL, NULL, 'snapshot-1-1', 1,
                    'revision-ci-evaluation', 'head-1', 'base-1',
                    'fingerprint-1', 'head-1', 'base-1', NULL, 78)
                """);
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('revision-stage-turn', 'remote-stage-1', 1,
                    'REMOTE_CI_REPAIR', 'QUEUED', 'revision-operation', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'API',
                    '{"transport":"API","provider":"openai",'
                    || '"model":"model","workingDirectory":"/tmp/task-1"}',
                    79)
                """);
        execute(connection, """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, stage_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('revision-operation-row', 'revision-episode',
                    'remote-stage-1', 'task-1', 1, 1, 'FIX_STAGE_TURN',
                    'revision-operation', 1, 'revision-stage-turn',
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 79)
                """);
        execute(connection, """
                UPDATE ci_repair_operation
                SET status = 'SUCCEEDED',
                    result_code_fingerprint = 'fingerprint-1',
                    result_head_sha = 'head-1', result_evidence = 'prior repair',
                    completed_at_ms = 80
                WHERE id = 'revision-operation-row'
                """);
        execute(connection, """
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 79,
                    finished_at_ms = 80
                WHERE id = 'revision-stage-turn'
                """);
        execute(connection, """
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha, base_sha,
                    recorded_at_ms)
                VALUES ('revision-worktree', 'task-1', 1, 'remote-stage-1', 1,
                    1, 'CI_STAGE_TURN', 'revision-operation', 'fingerprint-1',
                    'head-1', 'base-1', 80)
                """);
        execute(connection, """
                UPDATE ci_repair_episode
                SET status = 'STOPPED', completed_at_ms = 81,
                    stop_reason = 'prior repair fixture completed'
                WHERE id = 'revision-episode'
                """);
    }

    private static void insertTypedSnapshot(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state, observed_at_ms,
                    raw_evidence, ci_provenance_json)
                VALUES ('snapshot-1-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 1, 'observation-1-1', 'acme/widget', 41,
                    'head-1', 'base-1', 'OPEN', 'MERGEABLE', 'NONE', 61, '{}',
                    '{"schemaVersion":3,"complete":true}')
                """);
    }

    private static void insertObservedFailureFreshness(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO ci_repair_turn_freshness_v319(
                    id, ci_repair_episode_id, intent_kind, intent_id,
                    semantic_attempt, execution_attempt,
                    predecessor_snapshot_id,
                    predecessor_observation_revision,
                    accepted_snapshot_id, accepted_observation_revision,
                    accepted_ci_evaluation_id, remote_head_sha,
                    authoritative_base_sha, code_fingerprint, code_head_sha,
                    code_base_sha, prepublish_branch_sync_episode_id,
                    authorized_at_ms)
                VALUES ('source-freshness', 'episode-1', 'OBSERVED_FAILURE',
                    'ci-evaluation-1-1', 1, 1, NULL, NULL, 'snapshot-1-1', 1,
                    'ci-evaluation-1-1', 'head-1', 'base-1', 'fingerprint-1',
                    'head-1', 'base-1', NULL, 101)
                """);
    }

    private static void insertClosedBaseRepairAuthoritySource(
            Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('base-policy', 'task-1', 1, 'TEST', 1, 0, 0, 0,
                    0, 0, 0, 0, 'test', 100)
                """);
        execute(connection, """
                INSERT INTO ci_base_repair_manifest_v303(
                    id, ci_repair_episode_id, failed_ci_evaluation_id,
                    remote_pr_snapshot_id, subject_head_sha, subject_base_sha,
                    subject_manifest_json, manifest_digest, created_at_ms)
                VALUES ('base-manifest', 'episode-1', 'ci-evaluation-1-1',
                    'snapshot-1-1', 'head-1', 'base-1',
                    '{"schema":"CI_BASE_REPAIR_SUBJECT_V1"}', '%s', 100)
                """.formatted("b".repeat(64)));
        execute(connection, """
                INSERT INTO ci_base_repair_authorization_v303(
                    id, ci_repair_episode_id, manifest_id, semantic_attempt,
                    authority_kind, automation_policy_id, blocker_id,
                    command_id, actor, reason, failed_ci_evaluation_id,
                    remote_pr_snapshot_id, expected_worktree_head_sha,
                    subject_head_sha, subject_base_sha, manifest_digest,
                    status, claimed_at_ms)
                VALUES ('base-authorization', 'episode-1', 'base-manifest', 1,
                    'AUTO_APPROVE_POLICY', 'base-policy', NULL,
                    'base-command', NULL, 'repair exact base failure',
                    'ci-evaluation-1-1', 'snapshot-1-1', 'head-1', 'head-1',
                    'base-1', '%s', 'CLAIMED', 100)
                """.formatted("b".repeat(64)));
    }

    private CompatibilityFixture compatibilityFixture(String fileName)
            throws Exception
    {
        Seeded seeded = seedV321(fileName, true, true);
        migrateLatest(seeded.dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(seeded.dataSource());
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(seeded.dataSource()));
        SqliteRemoteRepairNormalizationStore normalizationStore =
                new SqliteRemoteRepairNormalizationStore(jdbc);
        NormalizationDue due = normalizationStore.findPending(1)
                .getFirst();
        ObjectMapper json = new ObjectMapper();
        String turnId = id("remote-repair-normalization-turn", due.id());
        String operationId = id(
                "remote-repair-normalization-operation", due.id());
        ObjectNode endpoint = json.createObjectNode();
        endpoint.put("ownerKind", "TASK_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "TASK_BRAIN_READ_ONLY");
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", "API");
        launch.put("provider", "openai");
        launch.putNull("credentialAccount");
        launch.put("model", "model");
        launch.putNull("reasoningEffort");
        launch.put("workingDirectory", "/tmp/task-1");
        launch.put("systemPrompt", """
                You are a syntax-only result normalizer. Do not inspect files, use tools, edit the workspace, or perform remote effects.
                Return exactly one raw JSON object shaped {"schemaVersion":1,"summary":"string"}.
                Preserve the meaning of the frozen malformed result. Do not add fields, Markdown fences, or surrounding prose.
                """);
        launch.put("prompt", """
                Normalize this frozen malformed Remote CI repair result into the required shape.

                Required shape:
                %s

                Source trace:
                sourceOperationId=%s
                sourceRawResultDigest=%s
                taskId=%s
                taskEpoch=%d
                stageId=%s
                stageGeneration=%d
                sourceCodeSubjectRevision=%d
                sourceCodeSubjectKind=%s
                sourceCodeSubjectId=%s
                expectedCodeFingerprint=%s
                expectedHeadSha=%s
                expectedBaseSha=%s

                Frozen malformed output encoded as one JSON string:
                %s""".formatted(
                        due.requiredResultShape(), due.sourceOperationId(),
                        due.sourceRawResultDigest(), due.taskId(),
                        due.taskEpoch(), due.stageId(), due.stageGeneration(),
                        due.sourceCodeSubjectRevision(),
                        due.sourceCodeSubjectKind(), due.sourceCodeSubjectId(),
                        due.expectedCodeFingerprint(), due.expectedHeadSha(),
                        due.expectedBaseSha(),
                        json.writeValueAsString(due.malformedOutput())));
        launch.set("toolEndpoint", endpoint);
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:8765/api/v2/task-turns/"
                + turnId + "/operations/" + operationId + "/mcp");
        String launchInput = json.writeValueAsString(launch);
        NormalizationOperation operation = commands.execute(
                "task-1", () -> normalizationStore.insertNormalization(
                        due, launchInput,
                        Instant.ofEpochMilli(400)));
        completeNormalization(
                jdbc, json, commands, normalizationStore, operation);
        return new CompatibilityFixture(
                jdbc, commands, new SqliteRemoteRuntimeStore(jdbc));
    }

    private static void completeNormalization(
            JdbcTemplate jdbc,
            ObjectMapper json,
            TaskCommandExecutor commands,
            SqliteRemoteRepairNormalizationStore store,
            NormalizationOperation operation)
            throws Exception
    {
        String normalized =
                "{\"schemaVersion\":1,\"summary\":\"fixed exact failure\"}";
        String rawDigest = "c".repeat(64);
        String normalizedDigest = "d".repeat(64);
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("turnId", operation.turnId());
        payload.put("ownerKind", "TASK_TURN");
        payload.put("purpose", "REMOTE_REPAIR_RESULT_NORMALIZATION");
        payload.put("finalText", normalized);
        payload.put("inputTokens", 1);
        payload.put("outputTokens", 1);
        payload.put("costUsdMilli", 0);
        payload.put("disposition", "SUCCESS");
        payload.putNull("error");
        payload.putNull("outputCodeSubject");
        String payloadJson = json.writeValueAsString(payload);
        ObjectNode fence = json.createObjectNode();
        fence.put("taskEpoch", operation.taskEpoch());
        fence.put("stageId", operation.stageId());
        fence.put("stageGeneration", operation.stageGeneration());
        fence.put("operationId", operation.operationId());
        fence.put("attempt", operation.normalizationAttempt());
        fence.put("expectedCodeFingerprint",
                operation.expectedCodeFingerprint());
        fence.put("expectedHeadSha", operation.expectedHeadSha());
        fence.put("expectedBaseSha", operation.expectedBaseSha());
        ObjectNode raw = json.createObjectNode();
        raw.set("fence", fence);
        raw.put("outcome", "SUCCEEDED");
        raw.put("payloadJson", payloadJson);
        raw.put("evidenceJson", "{}");
        raw.putNull("error");
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = 1, started_at_ms = 450,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?,
                    pending_result_evidence = '{}',
                    pending_result_task_epoch = ?,
                    pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, payloadJson, operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.operationId(),
                operation.normalizationAttempt(),
                operation.expectedCodeFingerprint(), operation.expectedHeadSha(),
                operation.expectedBaseSha(), operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('normalization-execution', ?, 1, 'openai',
                    'SUCCEEDED', 450, 500, ?)
                """, operation.ticketId(), json.writeValueAsString(raw))).isOne();
        ObjectNode evidence = json.createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("normalizationDueId", operation.dueId());
        evidence.put("normalizationTurnId", operation.turnId());
        evidence.put("normalizationOperationId", operation.operationId());
        evidence.put("sourceOperationId", operation.sourceOperationId());
        evidence.put("sourceCodeSubjectRevision",
                operation.sourceCodeSubjectRevision());
        evidence.put("sourceCodeSubjectKind",
                operation.sourceCodeSubjectKind());
        evidence.put("sourceCodeSubjectId", operation.sourceCodeSubjectId());
        evidence.put("rawResultDigest", rawDigest);
        evidence.put("normalizedPayload", normalized);
        evidence.put("normalizedPayloadDigest", normalizedDigest);
        String evidenceJson = json.writeValueAsString(evidence);
        commands.execute(operation.taskId(), () -> {
            store.finishNormalization(
                    operation, "SUCCEEDED", rawDigest, "SUCCEEDED",
                    Acceptance.ACCEPTED,
                    normalized, normalizedDigest,
                    evidenceJson, null,
                    Instant.ofEpochMilli(500));
            return null;
        });
    }

    private static void completeLegacyAdoption(CompatibilityFixture fixture)
            throws Exception
    {
        JdbcTemplate jdbc = fixture.jdbc();
        SqliteRemoteRepairNormalizationStore store =
                new SqliteRemoteRepairNormalizationStore(jdbc);
        String operationId = jdbc.queryForObject("""
                SELECT operation_id
                FROM remote_repair_commit_adoption_operation_v322
                """, String.class);
        Operation operation = store.requireByOperationId(operationId);
        assertThat(jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms,
                    expires_at_ms)
                VALUES ('adoption-capacity', ?, ?, 'V2', 16, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'worker', 17,
                    550, 550, 1000)
                """, operation.ticketId(), operation.operationId())).isOne();
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RUNNING',
                    claim_purpose = 'EXECUTE', claim_owner = 'worker',
                    capacity_lease_id = 'adoption-capacity',
                    claim_expires_at_ms = 1000, infrastructure_attempts = 1,
                    started_at_ms = 550
                WHERE id = ? AND status = 'REQUESTED'
                """, operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES ('/tmp/task-1', 'task-1', 'V2_OPERATION', 550, 1000,
                    'V2', ?, 1, 17, 'worker')
                """, operation.operationId())).isOne();
        ObjectNode evidence = new ObjectMapper().createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("operationId", operation.operationId());
        evidence.put("candidateCaptureKind", "LEGACY_REFLOG_WINDOW_V1");
        evidence.put("candidateHeadSha", "candidate-head");
        evidence.put("candidateParentSha", "head-1");
        evidence.put("sourceCodeSubjectRevision",
                operation.sourceCodeSubjectRevision());
        evidence.put("sourceCodeSubjectKind",
                operation.sourceCodeSubjectKind());
        evidence.put("sourceCodeSubjectId", operation.sourceCodeSubjectId());
        evidence.put("candidateCount", 1);
        evidence.put("sourceExecutionStartedAtMs", 200);
        evidence.put("sourceExecutionFinishedAtMs", 300);
        String evidenceJson = evidence.toString();
        MutationFence fence = mock(MutationFence.class);
        when(fence.fencingToken()).thenReturn(17L);
        Candidate candidate = new Candidate(
                "candidate-head", "source-tree", "result-tree",
                "LEGACY_REFLOG_WINDOW_V1", null);
        ResultReceipt receipt = fixture.commands().execute("task-1", () ->
                store.recordAdopted(
                        operation, fence, candidate, "candidate-fingerprint",
                        evidenceJson, Instant.ofEpochMilli(600)));
        AdoptionResult result = new AdoptionResult(
                1, Disposition.ADOPTED, operation.id(),
                operation.operationId(), operation.normalizationId(),
                operation.sourceOperationId(), operation.sourceHeadSha(),
                operation.expectedBaseSha(), receipt.candidateHeadSha(),
                "source-tree", "result-tree", receipt.id(),
                receipt.resultCodeFingerprint(), receipt.evidence(), null);
        assertThat(jdbc.update("""
                DELETE FROM worktree_leases WHERE operation_id = ?
                """, operation.operationId())).isOne();
        assertThat(jdbc.update("""
                UPDATE capacity_lease
                SET released_at_ms = 650, release_reason = 'completed'
                WHERE id = 'adoption-capacity' AND released_at_ms IS NULL
                """)).isOne();
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 2, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = ?,
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'remote-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = ?,
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = ? AND status = 'RUNNING'
                """, evidenceJson, operation.operationId(),
                operation.ticketId())).isOne();
        fixture.commands().execute("task-1", () -> {
            store.finishAdoption(
                    operation, result, "SUCCEEDED", "f".repeat(64),
                    "SUCCEEDED",
                    Acceptance.ACCEPTED,
                    evidenceJson, null, Instant.ofEpochMilli(700));
            return null;
        });
    }

    private static void insertBrain(
            JdbcTemplate jdbc, String id, String fingerprint, String head)
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, 'task-1', 'REMOTE_CI_BRAIN_REVIEW', 'REQUESTED',
                    ?, 1, 1, 'remote-stage-1', 1, ?, ?, 'base-1', 'API',
                    '{"transport":"API","provider":"openai",'
                    || '"model":"model","workingDirectory":"/tmp/task-1"}',
                    730)
                """, id + "-turn", id, fingerprint, head);
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, task_turn_id, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status,
                    requested_at_ms, base_repair_authorization_id)
                VALUES (?, 'episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'BRAIN_REVIEW', ?, 1, ?, ?, ?, 'base-1', 'REQUESTED', 730,
                    'base-authorization')
                """, id + "-row", id, id + "-turn", fingerprint, head);
    }

    private static void insertPush(
            JdbcTemplate jdbc, String id, String fingerprint, String head)
    {
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status,
                    requested_at_ms, base_repair_authorization_id)
                VALUES (?, 'episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'PUSH_HEAD', ?, 1, ?, ?, 'base-1', 'REQUESTED', 740,
                    'base-authorization')
                """, id + "-row", id, fingerprint, head);
    }

    private static void shadowCurrentSubject(
            JdbcTemplate jdbc,
            String id,
            String operationId,
            String fingerprint,
            String head,
            long recordedAtMs)
    {
        jdbc.execute("DROP TRIGGER IF EXISTS remote_worktree_subject_insert");
        assertThat(jdbc.update("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha, base_sha,
                    recorded_at_ms)
                VALUES (?, 'task-1', 1, 'remote-stage-1', 1,
                    (SELECT COALESCE(MAX(revision) + 1, 1)
                     FROM remote_worktree_subject
                     WHERE task_id = 'task-1' AND task_epoch = 1),
                    'CI_STAGE_TURN', ?, ?, ?, 'base-1', ?)
                """, id, operationId, fingerprint, head,
                recordedAtMs)).isOne();
    }

    private static void removeShadowCurrentSubject(
            JdbcTemplate jdbc, String id)
    {
        assertThat(jdbc.update("""
                DELETE FROM task_code_subject_revision_v320
                WHERE subject_kind = 'REMOTE_WORKTREE'
                  AND subject_id = ?
                """, id)).isOne();
        assertThat(jdbc.update("""
                DELETE FROM remote_worktree_subject
                WHERE id = ?
                """, id)).isOne();
    }

    private static BaseRepairAuthorization assertClaimedCompatibility(
            CompatibilityFixture fixture)
    {
        BaseRepairAuthorization original = fixture.store()
                .requireBaseRepairAuthorization("base-authorization");
        assertThat(original.status()).isEqualTo("CLOSED");
        assertThat(fixture.store().findClaimedBaseRepairAuthorization(
                "episode-1")).contains(original);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM ci_base_repair_reauthorization_v322
                WHERE source_authorization_id = 'base-authorization'
                """, String.class)).isEqualTo("CLAIMED");
        return original;
    }

    private static void assertTerminalCompatibility(
            CompatibilityFixture fixture,
            BaseRepairAuthorization original,
            String expectedStatus,
            String expectedEvidence)
    {
        assertThat(fixture.store().requireBaseRepairAuthorization(
                original.id())).isEqualTo(original);
        assertThat(fixture.jdbc().queryForMap("""
                SELECT status, terminal_at_ms, terminal_evidence
                FROM ci_base_repair_reauthorization_v322
                WHERE source_authorization_id = 'base-authorization'
                """))
                .containsEntry("status", expectedStatus)
                .containsEntry("terminal_at_ms",
                        Math.toIntExact(NOW.toEpochMilli()))
                .containsEntry("terminal_evidence", expectedEvidence);
        assertThat(fixture.store().findClaimedBaseRepairAuthorization(
                "episode-1")).isEmpty();
    }

    private static void migrateLatest(SQLiteDataSource dataSource)
    {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    private record CompatibilityFixture(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store) {}

    private record Seeded(String url, SQLiteDataSource dataSource) {}

    private enum SourceTicketWindow
    {
        FAILED_DELIVERED,
        RESULT_PENDING_AFTER_OWNER_DELIVERY
    }

    private enum LegacyOutputShape
    {
        NULL_V1,
        PRE_V322_PARTIAL_V1,
        INEXACT_V1
    }
}
