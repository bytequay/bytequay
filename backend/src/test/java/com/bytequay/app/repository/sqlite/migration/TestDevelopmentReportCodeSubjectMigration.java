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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Evidence;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.RawResult;
import com.bytequay.app.developmentflow.persistence.SqliteCapacityLeaseStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.persistence.SqliteExecutionEvidencePort;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.AdoptionResult;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Disposition;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Operation;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.ResultReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.NormalizationDue;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.NormalizationOperation;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.ClaimPurpose.EXECUTE;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.INVALID_LAUNCH_INPUT;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRevisionBackedCurrentSubject;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SqliteTestPools.class)
class TestDevelopmentReportCodeSubjectMigration
{
    private static final String[] REBUILT_TABLES = {
            "task_code_subject_revision_v320",
            "remote_repair_legacy_eligibility_v322",
            "remote_repair_result_normalization_due_v322",
            "remote_repair_result_normalization_operation_v322",
            "ci_base_repair_reauthorization_v322",
            "remote_repair_commit_adoption_operation_v322",
            "remote_repair_commit_adoption_result_v322",
            "remote_repair_commit_adoption_delivery_v322"};
    private static final String SOURCE_DIGEST = "a".repeat(64);
    private static final String NORMALIZATION_DIGEST = "b".repeat(64);
    private static final String NORMALIZED_PAYLOAD_DIGEST = "c".repeat(64);
    private static final String ADOPTION_DIGEST = "d".repeat(64);
    private static final String MALFORMED = """
            Repair complete.

            {"schemaVersion":1,"summary":"fixed the exact failure"}
            """;
    private static final String NORMALIZED =
            "{\"schemaVersion\":1,\"summary\":\"fixed the exact failure\"}";

    @TempDir
    private Path tempDir;

    @Test
    void populatedV322LineageSurvivesCanonicalRebuildByteForByte()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v323.db")
                + "?foreign_keys=ON";
        DataSource dataSource = dataSource(url);
        migrate(dataSource, "321");
        try (Connection connection = connect(url)) {
            seedRemoteTask(connection);
            insertRevisionBackedCurrentSubject(connection);
            insertMalformedBaseRepair(connection, 301);
        }
        migrate(dataSource, "322");
        completeLegacyNormalizationAndAdoption(dataSource);

        Map<String, List<String>> before;
        long sequence;
        try (Connection connection = connect(url)) {
            for (String table : REBUILT_TABLES) {
                assertThat(number(connection,
                        "SELECT COUNT(*) FROM " + table))
                        .as(table)
                        .isPositive();
            }
            execute(connection, """
                    UPDATE sqlite_sequence SET seq = 77
                    WHERE name = 'task_code_subject_revision_v320'
                    """);
            sequence = number(connection, """
                    SELECT seq FROM sqlite_sequence
                    WHERE name = 'task_code_subject_revision_v320'
                    """);
            before = snapshotTables(connection);
        }

        migrate(dataSource, null);

        try (Connection connection = connect(url)) {
            assertThat(snapshotTables(connection)).isEqualTo(before);
            assertThat(number(connection, """
                    SELECT seq FROM sqlite_sequence
                    WHERE name = 'task_code_subject_revision_v320'
                    """)).isEqualTo(sequence);
            assertThat(text(connection, """
                    SELECT sql FROM sqlite_schema
                    WHERE type = 'table'
                      AND name = 'task_code_subject_revision_v320'
                    """)).contains("'DEVELOPMENT_REPORT'");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM sqlite_schema
                    WHERE name LIKE '%_v323_shadow'
                    """)).isZero();
        }
    }

    @Test
    void acceptedDevelopmentReportRecoversTicketCompletedAfterOwnerDelivery()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v323-ticket-lag.db")
                + "?foreign_keys=ON";
        DataSource dataSource = dataSource(url);
        migrate(dataSource, "321");
        try (Connection connection = connect(url)) {
            seedRemoteTask(connection);
            insertMalformedBaseRepair(connection, 303);
        }
        migrate(dataSource, "322");
        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_code_subject_revision_v320
                    WHERE task_id = 'task-1'
                    """)).isZero();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_repair_legacy_eligibility_v322
                    """)).isZero();
        }

        migrate(dataSource, null);

        try (Connection connection = connect(url)) {
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM accepted_development_report_code_subject_v323 report
                    JOIN agent_execution execution
                      ON execution.id = 'source-execution'
                    WHERE report.subject_id = 'report-1'
                      AND report.recorded_at_ms <= execution.started_at_ms
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT ticket.completed_at_ms - source.completed_at_ms
                    FROM dispatch_ticket ticket
                    JOIN ci_repair_operation source
                      ON source.operation_id = ticket.operation_id
                    WHERE source.id = 'source-operation-row'
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_legacy_eligibility_v322 eligibility
                    JOIN remote_repair_result_normalization_due_v322 due
                      ON due.source_operation_row_id =
                         eligibility.source_operation_row_id
                    WHERE eligibility.source_code_subject_kind =
                              'DEVELOPMENT_REPORT'
                      AND eligibility.source_code_subject_id = 'report-1'
                      AND eligibility.ticket_window = 'FAILED_DELIVERED'
                      AND due.status = 'PENDING'
                      AND due.source_code_subject_revision =
                          eligibility.source_code_subject_revision
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_legacy_eligibility_v322
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*)
                    FROM remote_repair_result_normalization_due_v322
                    """)).isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void preProviderNormalizerLaunchFailureRearmsTheSameInfrastructureLineage()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v324-rearm.db")
                + "?foreign_keys=ON";
        DataSource dataSource = dataSource(url);
        migrate(dataSource, "321");
        try (Connection connection = connect(url)) {
            seedRemoteTask(connection);
            insertMalformedBaseRepair(connection, 303);
        }
        migrate(dataSource, "323");
        FailedNormalization failed = failNormalizationBeforeProvider(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Map<String, Object> executionBefore = new LinkedHashMap<>(
                jdbc.queryForMap("SELECT * FROM agent_execution WHERE id = ?",
                        failed.executionId()));
        Map<String, Object> authorityBefore = new LinkedHashMap<>(
                jdbc.queryForMap("""
                        SELECT episode.status AS episode_status,
                               episode.fix_attempt_count,
                               episode.push_count,
                               blocker.status AS blocker_status,
                               code.source_code_subject_revision,
                               code.source_code_subject_kind,
                               code.source_code_subject_id,
                               code.code_fingerprint, code.head_sha, code.base_sha,
                               authorization.status AS authorization_status,
                               authorization.terminal_at_ms,
                               authorization.terminal_evidence
                        FROM ci_repair_episode episode
                        JOIN task_blocker blocker
                          ON blocker.owner_kind = 'EPISODE'
                         AND blocker.owner_id = episode.id
                         AND blocker.blocker_type =
                             'CI_REPAIR_OUTPUT_MALFORMED'
                        JOIN task_current_code_subject_fence_v322 code
                          ON code.task_id = episode.task_id
                        JOIN ci_base_repair_authorization_v303 authorization
                          ON authorization.ci_repair_episode_id = episode.id
                        WHERE episode.id = ?
                        """, failed.operation().episodeId()));
        String normalizationTrigger = triggerSql(
                jdbc, "remote_repair_result_normalization_operation_terminal_v322");
        String ticketTrigger = triggerSql(
                jdbc, "dispatch_ticket_terminal_immutable");

        migrate(dataSource, null);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_result_normalization_operation_v322
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                  AND raw_outcome IS NULL
                  AND normalization_raw_result_digest IS NULL
                  AND normalized_payload IS NULL
                  AND normalized_payload_digest IS NULL
                  AND acceptance IS NULL AND terminal_evidence IS NULL
                  AND completed_at_ms IS NULL AND error_message IS NULL
                """, Integer.class, failed.operation().id(),
                failed.operation().operationId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_turn
                WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                  AND started_at_ms IS NULL AND finished_at_ms IS NULL
                  AND error_message IS NULL
                """, Integer.class, failed.operation().turnId(),
                failed.operation().operationId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                  AND version = 5 AND infrastructure_attempts = 1
                  AND claim_purpose IS NULL AND claim_owner IS NULL
                  AND capacity_lease_id IS NULL
                  AND claim_expires_at_ms IS NULL
                  AND next_attempt_at_ms IS NULL
                  AND cancel_requested_at_ms IS NULL
                  AND started_at_ms IS NULL
                  AND pending_result_outcome IS NULL
                  AND pending_result_payload IS NULL
                  AND pending_result_evidence IS NULL
                  AND pending_result_error IS NULL
                  AND delivery_acceptance IS NULL
                  AND delivery_evidence IS NULL
                  AND completed_at_ms IS NULL AND last_error IS NULL
                """, Integer.class, failed.operation().ticketId(),
                failed.operation().operationId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_result_normalization_due_v322
                WHERE id = ? AND status = 'DISPATCHED'
                  AND normalization_operation_row_id = ?
                """, Integer.class, failed.operation().dueId(),
                failed.operation().id())).isOne();
        assertThat(new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT * FROM agent_execution WHERE id = ?",
                failed.executionId()))).isEqualTo(executionBefore);
        assertThat(new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT episode.status AS episode_status,
                       episode.fix_attempt_count,
                       episode.push_count,
                       blocker.status AS blocker_status,
                       code.source_code_subject_revision,
                       code.source_code_subject_kind,
                       code.source_code_subject_id,
                       code.code_fingerprint, code.head_sha, code.base_sha,
                       authorization.status AS authorization_status,
                       authorization.terminal_at_ms,
                       authorization.terminal_evidence
                FROM ci_repair_episode episode
                JOIN task_blocker blocker
                  ON blocker.owner_kind = 'EPISODE'
                 AND blocker.owner_id = episode.id
                 AND blocker.blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
                JOIN task_current_code_subject_fence_v322 code
                  ON code.task_id = episode.task_id
                JOIN ci_base_repair_authorization_v303 authorization
                  ON authorization.ci_repair_episode_id = episode.id
                WHERE episode.id = ?
                """, failed.operation().episodeId())))
                .isEqualTo(authorityBefore);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_execution_log
                WHERE execution_id = ?
                """, Integer.class, failed.executionId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_execution_process_attempt
                WHERE execution_id = ?
                """, Integer.class, failed.executionId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_commit_adoption_operation_v322
                WHERE normalization_operation_row_id = ?
                """, Integer.class, failed.operation().id())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_reauthorization_v322
                WHERE normalization_operation_row_id = ?
                """, Integer.class, failed.operation().id())).isZero();
        assertThat(triggerSql(jdbc,
                "remote_repair_result_normalization_operation_terminal_v322"))
                .isEqualTo(normalizationTrigger);
        assertThat(triggerSql(jdbc, "dispatch_ticket_terminal_immutable"))
                .isEqualTo(ticketTrigger);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM outbox
                WHERE aggregate_kind = 'DISPATCH_TICKET'
                  AND aggregate_id = ?
                """, String.class, failed.operation().ticketId()))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check",
                Integer.class)).isZero();

        SqliteDispatchTicketStore tickets =
                new SqliteDispatchTicketStore(dataSource);
        assertThat(tickets.findById(failed.operation().ticketId()))
                .get()
                .matches(ticket -> ticket.isEligibleAt(
                        Instant.ofEpochMilli(700)));

        Instant retryAt = Instant.ofEpochMilli(700);
        CapacityManager capacity = new CapacityManager(
                new SqliteCapacityLeaseStore(dataSource),
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of()),
                Clock.fixed(retryAt, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        DispatchTicket requested = tickets.findById(
                failed.operation().ticketId()).orElseThrow();
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(),
                "retry-worker").lease().orElseThrow();
        DispatchTicket claimed = requested.claim(
                "retry-worker", lease.id(), lease.expiresAt());
        assertThat(tickets.compareAndSet(
                requested.id(), requested.version(), claimed)).isTrue();
        DispatchTicket running = claimed.markRunning(retryAt);
        assertThat(tickets.compareAndSet(
                claimed.id(), claimed.version(), running)).isTrue();
        String retryExecutionId = new SqliteExecutionEvidencePort(
                dataSource, new ObjectMapper()).start(
                        running, lease, EXECUTE, retryAt);

        assertThat(running.infrastructureAttempts()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_execution
                WHERE ticket_id = ? AND infrastructure_attempt = 2
                  AND id = ? AND status = 'RUNNING'
                """, Integer.class, running.id(), retryExecutionId)).isOne();
        assertThat(new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT * FROM agent_execution WHERE id = ?",
                failed.executionId()))).isEqualTo(executionBefore);
    }

    @Test
    void normalizerLaunchRearmFailsClosedWhenProviderStartEvidenceExists()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v324-near-miss.db")
                + "?foreign_keys=ON";
        DataSource dataSource = dataSource(url);
        migrate(dataSource, "321");
        try (Connection connection = connect(url)) {
            seedRemoteTask(connection);
            insertMalformedBaseRepair(connection, 303);
        }
        migrate(dataSource, "323");
        FailedNormalization failed = failNormalizationBeforeProvider(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.update("""
                UPDATE agent_execution SET provider = 'claude-code'
                WHERE id = ?
                """, failed.executionId())).isOne();

        assertThatThrownBy(() -> migrate(dataSource, null))
                .hasMessageContaining("V324__remote_repair_normalizer_launch_rearm");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_result_normalization_operation_v322 operation
                JOIN task_turn turn
                  ON turn.id = operation.normalization_task_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.id = ? AND operation.status = 'FAILED'
                  AND turn.status = 'FAILED' AND ticket.status = 'FAILED'
                  AND ticket.version = 4
                """, Integer.class, failed.operation().id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, failed.operation().episodeId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check",
                Integer.class)).isZero();
    }

    private static FailedNormalization failNormalizationBeforeProvider(
            DataSource dataSource)
            throws Exception
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper();
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        SqliteRemoteRepairNormalizationStore store =
                new SqliteRemoteRepairNormalizationStore(jdbc);
        NormalizationDue due = store.findPending(1).getFirst();
        String launchInput = normalizationLaunch(json, due);
        NormalizationOperation operation = commands.execute(
                due.taskId(), () -> store.insertNormalization(
                        due, launchInput,
                        Instant.ofEpochMilli(400)));

        SqliteDispatchWakeStore wakes = new SqliteDispatchWakeStore(jdbc);
        for (var wake : wakes.claimAvailable(
                "wake-worker", Instant.ofEpochMilli(410),
                Instant.ofEpochMilli(420), 10)) {
            assertThat(wakes.markDelivered(
                    wake, Instant.ofEpochMilli(411))).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM outbox
                WHERE aggregate_id = ? AND status = 'DELIVERED'
                """, Integer.class, operation.ticketId())).isOne();

        String executionId = "normalization-invalid-launch-execution";
        String error = "invalid frozen Agent Turn launch input: Cannot construct "
                + "instance of `com.bytequay.app.developmentflow.execution."
                + "agentturn.AgentTurnProviderSession$OwnerToolEndpoint`, "
                + "problem: approvalPromptTool must name the scoped ByteQuay gate\n"
                + " at [Source: REDACTED (`StreamReadFeature."
                + "INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: "
                + (launchInput.length() - 1) + "] "
                + "(through reference chain: com.bytequay.app.developmentflow."
                + "execution.agentturn.AgentTurnOperationHandler$LaunchInput"
                + "[\"toolEndpoint\"])";
        RawResult payload = new RawResult(
                1, operation.turnId(), TASK_TURN,
                "REMOTE_REPAIR_RESULT_NORMALIZATION",
                null, null, null, "", 0, 0, 0, null,
                INVALID_LAUNCH_INPUT, error);
        Evidence evidence = new Evidence(
                1, INVALID_LAUNCH_INPUT, "f".repeat(64), null, error);
        String payloadJson = json.writeValueAsString(payload);
        String evidenceJson = json.writeValueAsString(evidence);
        DispatchTicket.DispatchResult rawResult =
                new DispatchTicket.DispatchResult(
                        new DispatchTicket.OperationFence(
                                operation.taskEpoch(), operation.stageId(),
                                operation.stageGeneration(),
                                operation.operationId(),
                                operation.normalizationAttempt(),
                                operation.expectedCodeFingerprint(),
                                operation.expectedHeadSha(),
                                operation.expectedBaseSha()),
                        DispatchTicket.Outcome.FAILED,
                        payloadJson, evidenceJson, error);

        assertThat(jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                SELECT 'normalization-invalid-launch-capacity', id,
                       operation_id, 'V2', lane_mask, trunk_control,
                       exclusive_task, writer_required, workspace_id,
                       trunk_id, task_id, task_epoch, 'normalization-worker',
                       420, 420, 520
                FROM dispatch_ticket WHERE id = ? AND status = 'REQUESTED'
                """, operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'CLAIMED',
                    claim_purpose = 'EXECUTE',
                    claim_owner = 'normalization-worker',
                    capacity_lease_id =
                        'normalization-invalid-launch-capacity',
                    claim_expires_at_ms = 520
                WHERE id = ? AND version = 0 AND status = 'REQUESTED'
                """, operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 2, status = 'RUNNING',
                    infrastructure_attempts = 1, started_at_ms = 430
                WHERE id = ? AND version = 1 AND status = 'CLAIMED'
                """, operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    started_at_ms, heartbeat_at_ms)
                VALUES (?, ?, 1, 'RUNNING', 430, 430)
                """, executionId, operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                UPDATE capacity_lease
                SET released_at_ms = 450, release_reason = 'RELEASED'
                WHERE id = 'normalization-invalid-launch-capacity'
                  AND released_at_ms IS NULL
                """)).isOne();
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 3, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    next_attempt_at_ms = 450,
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = ?,
                    pending_result_evidence = ?,
                    pending_result_error = ?,
                    pending_result_task_epoch = ?,
                    pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?,
                    last_error = ?
                WHERE id = ? AND version = 2 AND status = 'RUNNING'
                """, payloadJson, evidenceJson, error,
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.operationId(),
                operation.normalizationAttempt(),
                operation.expectedCodeFingerprint(),
                operation.expectedHeadSha(), operation.expectedBaseSha(),
                error, operation.ticketId())).isOne();
        assertThat(jdbc.update("""
                UPDATE agent_execution
                SET status = 'FAILED', heartbeat_at_ms = 451,
                    finished_at_ms = 451, raw_result = ?
                WHERE id = ? AND status = 'RUNNING'
                """, json.writeValueAsString(rawResult), executionId)).isOne();

        ObjectNode terminal = json.createObjectNode();
        terminal.put("schemaVersion", 1);
        terminal.put("normalizationDueId", operation.dueId());
        terminal.put("normalizationTurnId", operation.turnId());
        terminal.put("normalizationOperationId", operation.operationId());
        terminal.put("sourceOperationId", operation.sourceOperationId());
        terminal.put("sourceCodeSubjectRevision",
                operation.sourceCodeSubjectRevision());
        terminal.put("sourceCodeSubjectKind",
                operation.sourceCodeSubjectKind());
        terminal.put("sourceCodeSubjectId", operation.sourceCodeSubjectId());
        terminal.put("rawResultDigest", NORMALIZATION_DIGEST);
        String terminalEvidence = json.writeValueAsString(terminal);
        commands.execute(operation.taskId(), () -> {
            store.finishNormalization(
                    operation, "FAILED", NORMALIZATION_DIGEST, "FAILED",
                    DispatchTicket.Acceptance.ACCEPTED,
                    null, null, terminalEvidence, error,
                    Instant.ofEpochMilli(460));
            return null;
        });
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 4, status = 'FAILED',
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
                    delivery_evidence =
                        'ACCEPTED:Remote repair result normalization failed',
                    completed_at_ms = 461, last_error = ?
                WHERE id = ? AND version = 3
                  AND status = 'RESULT_PENDING'
                """, error, operation.ticketId())).isOne();
        return new FailedNormalization(operation, executionId);
    }

    private static String triggerSql(JdbcTemplate jdbc, String name)
    {
        return jdbc.queryForObject("""
                SELECT sql FROM sqlite_schema
                WHERE type = 'trigger' AND name = ?
                """, String.class, name);
    }

    private static void seedRemoteTask(Connection connection)
            throws SQLException
    {
        seedWorkspaceAndTrunk(connection);
        seedPublishedRemoteTask(connection, 1);
        insertRemoteOwner(connection, 1);
        insertCiPolicy(connection, 1);
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
        insertFailedCi(connection, 1, 1, "head-1", "base-1");
        acceptSnapshot(connection, 1, 1, "head-1", "base-1");
    }

    private static void insertMalformedBaseRepair(
            Connection connection, long ticketCompletedAt)
            throws Exception
    {
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('episode-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'BASE_DETERMINISTIC', 'OPEN', 0, 3, 2, 3, 100)
                """);
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
                """.formatted("e".repeat(64)));
        execute(connection, """
                INSERT INTO ci_base_repair_authorization_v303(
                    id, ci_repair_episode_id, manifest_id, semantic_attempt,
                    authority_kind, automation_policy_id, command_id, reason,
                    failed_ci_evaluation_id, remote_pr_snapshot_id,
                    expected_worktree_head_sha, subject_head_sha,
                    subject_base_sha, manifest_digest, status, claimed_at_ms)
                VALUES ('base-authorization', 'episode-1', 'base-manifest', 1,
                    'AUTO_APPROVE_POLICY', 'base-policy', 'base-command',
                    'repair exact base failure', 'ci-evaluation-1-1',
                    'snapshot-1-1', 'head-1', 'head-1', 'base-1', '%s',
                    'CLAIMED', 100)
                """.formatted("e".repeat(64)));
        execute(connection, """
                INSERT INTO ci_repair_turn_freshness_v319(
                    id, ci_repair_episode_id, intent_kind, intent_id,
                    semantic_attempt, execution_attempt,
                    accepted_snapshot_id, accepted_observation_revision,
                    accepted_ci_evaluation_id, remote_head_sha,
                    authoritative_base_sha, code_fingerprint, code_head_sha,
                    code_base_sha, authorized_at_ms)
                VALUES ('source-freshness', 'episode-1', 'OBSERVED_FAILURE',
                    'ci-evaluation-1-1', 1, 1, 'snapshot-1-1', 1,
                    'ci-evaluation-1-1', 'head-1', 'base-1', 'fingerprint-1',
                    'head-1', 'base-1', 101)
                """);
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
                    '{"schemaVersion":1,"transport":"API",'
                    || '"provider":"openai","credentialAccount":null,'
                    || '"model":"model","reasoningEffort":null,'
                    || '"workingDirectory":"/tmp/task-1"}', 101)
                """);
        execute(connection, """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, stage_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms,
                    base_repair_authorization_id, lease_expected_sha)
                VALUES ('source-operation-row', 'episode-1',
                    'remote-stage-1', 'task-1', 1, 1, 'FIX_STAGE_TURN',
                    'source-operation', 1, 'source-stage-turn',
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 101,
                    'base-authorization', 'head-1')
                """);
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

        ObjectMapper json = new ObjectMapper();
        String error = "OWNER_OUTPUT_MALFORMED: expected strict JSON";
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("turnId", "source-stage-turn");
        payload.put("ownerKind", "STAGE_TURN");
        payload.put("purpose", "REMOTE_CI_REPAIR");
        payload.put("finalText", MALFORMED);
        payload.put("inputTokens", 1);
        payload.put("outputTokens", 1);
        payload.put("costUsdMilli", 0);
        payload.put("disposition", "OWNER_OUTPUT_MALFORMED");
        payload.put("error", error);
        payload.putNull("outputCodeSubject");
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
        raw.put("evidenceJson", "{}");
        raw.put("error", error);
        String rawJson = json.writeValueAsString(raw);

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = 1, started_at_ms = 200,
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = ?, pending_result_evidence = '{}',
                    pending_result_error = ?, pending_result_task_epoch = 1,
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
            statement.setString(2, error);
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
        execute(connection, """
                UPDATE stage_turn
                SET status = 'FAILED', started_at_ms = 200,
                    finished_at_ms = 301,
                    error_message = 'OWNER_OUTPUT_MALFORMED: expected strict JSON'
                WHERE id = 'source-stage-turn'
                """);
        execute(connection, """
                UPDATE ci_repair_operation
                SET status = 'FAILED', completed_at_ms = 301,
                    error_message = 'OWNER_OUTPUT_MALFORMED: expected strict JSON'
                WHERE id = 'source-operation-row'
                """);
        execute(connection, """
                INSERT INTO ci_repair_delivery_receipt(
                    ci_repair_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES ('source-operation-row', 'source-operation', 'FAILED',
                    '%s', 'ACCEPTED', 301)
                """.formatted(SOURCE_DIGEST));
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
                    delivery_evidence = 'delivered', completed_at_ms = %s,
                    last_error = 'owner result failed'
                WHERE id = 'source-ticket'
                """.formatted(ticketCompletedAt));
        execute(connection, """
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('malformed-blocker', 'task-1', 'remote-stage-1',
                    'EPISODE', 'episode-1', 'head-1',
                    'CI_REPAIR_OUTPUT_MALFORMED', 'OPEN', '{}', 301)
                """);
        execute(connection, """
                UPDATE ci_base_repair_authorization_v303
                SET status = 'CLOSED', terminal_at_ms = 302,
                    terminal_evidence = 'malformed owner output'
                WHERE id = 'base-authorization'
                """);
    }

    private static void completeLegacyNormalizationAndAdoption(
            DataSource dataSource)
            throws Exception
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        SqliteRemoteRepairNormalizationStore store =
                new SqliteRemoteRepairNormalizationStore(jdbc);
        ObjectMapper json = new ObjectMapper();
        NormalizationDue due = store.findPending(1).getFirst();
        String launchInput = normalizationLaunch(json, due);
        NormalizationOperation normalization = commands.execute(
                "task-1", () -> store.insertNormalization(
                        due, launchInput,
                        Instant.ofEpochMilli(400)));
        markNormalizationResultPending(jdbc, json, normalization);
        String terminalEvidence = normalizationEvidence(json, normalization);
        commands.execute("task-1", () -> {
            store.finishNormalization(
                    normalization, "SUCCEEDED", NORMALIZATION_DIGEST,
                    "SUCCEEDED", DispatchTicket.Acceptance.ACCEPTED,
                    NORMALIZED, NORMALIZED_PAYLOAD_DIGEST,
                    terminalEvidence, null,
                    Instant.ofEpochMilli(500));
            return null;
        });

        String adoptionOperationId = jdbc.queryForObject("""
                SELECT operation_id
                FROM remote_repair_commit_adoption_operation_v322
                """, String.class);
        Operation adoption = store.requireAdoptionDelivery(
                "task-1", adoptionOperationId);
        ResultReceipt result = recordAdoption(
                jdbc, commands, store, json, adoption);
        AdoptionResult payload = new AdoptionResult(
                1, Disposition.ADOPTED, adoption.id(), adoption.operationId(),
                adoption.normalizationId(), adoption.sourceOperationId(),
                adoption.sourceHeadSha(), adoption.expectedBaseSha(),
                result.candidateHeadSha(), "source-tree", "result-tree",
                result.id(), result.resultCodeFingerprint(), result.evidence(),
                null);
        markAdoptionResultPending(
                jdbc, adoption, json.writeValueAsString(payload),
                result.evidence());
        commands.execute("task-1", () -> store.finishAdoption(
                adoption, payload, "SUCCEEDED", ADOPTION_DIGEST,
                "SUCCEEDED", DispatchTicket.Acceptance.ACCEPTED,
                result.evidence(), null, Instant.ofEpochMilli(700)));
    }

    private static String normalizationLaunch(
            ObjectMapper json, NormalizationDue due)
            throws Exception
    {
        String turnId = id("remote-repair-normalization-turn", due.id());
        String operationId = id(
                "remote-repair-normalization-operation", due.id());
        ObjectNode endpoint = json.createObjectNode();
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:8765/api/v2/task-turns/"
                + turnId + "/operations/" + operationId + "/mcp");
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
        launch.put("systemPrompt",
                "You are a syntax-only result normalizer. Do not inspect files, "
                        + "use tools, edit the workspace, or perform remote effects.\n"
                        + "Return exactly one raw JSON object shaped "
                        + "{\"schemaVersion\":1,\"summary\":\"string\"}.\n"
                        + "Preserve the meaning of the frozen malformed result. "
                        + "Do not add fields, Markdown fences, or surrounding prose.\n");
        launch.put("prompt", "Normalize this frozen malformed Remote CI repair "
                + "result into the required shape.\n\nRequired shape:\n"
                + due.requiredResultShape() + "\n\nSource trace:\n"
                + "sourceOperationId=" + due.sourceOperationId() + "\n"
                + "sourceRawResultDigest=" + due.sourceRawResultDigest() + "\n"
                + "taskId=" + due.taskId() + "\n"
                + "taskEpoch=" + due.taskEpoch() + "\n"
                + "stageId=" + due.stageId() + "\n"
                + "stageGeneration=" + due.stageGeneration() + "\n"
                + "sourceCodeSubjectRevision="
                + due.sourceCodeSubjectRevision() + "\n"
                + "sourceCodeSubjectKind=" + due.sourceCodeSubjectKind() + "\n"
                + "sourceCodeSubjectId=" + due.sourceCodeSubjectId() + "\n"
                + "expectedCodeFingerprint="
                + due.expectedCodeFingerprint() + "\n"
                + "expectedHeadSha=" + due.expectedHeadSha() + "\n"
                + "expectedBaseSha=" + due.expectedBaseSha() + "\n\n"
                + "Frozen malformed output encoded as one JSON string:\n"
                + json.writeValueAsString(due.malformedOutput()));
        launch.set("toolEndpoint", endpoint);
        return json.writeValueAsString(launch);
    }

    private static void markNormalizationResultPending(
            JdbcTemplate jdbc,
            ObjectMapper json,
            NormalizationOperation operation)
            throws Exception
    {
        ObjectNode payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("turnId", operation.turnId());
        payload.put("ownerKind", "TASK_TURN");
        payload.put("purpose", "REMOTE_REPAIR_RESULT_NORMALIZATION");
        payload.put("finalText", NORMALIZED);
        payload.put("disposition", "PROVIDER_SUCCEEDED");
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
                    pending_result_payload = ?, pending_result_evidence = '{}',
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?, pending_result_attempt = ?,
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
    }

    private static String normalizationEvidence(
            ObjectMapper json, NormalizationOperation operation)
            throws Exception
    {
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
        evidence.put("rawResultDigest", NORMALIZATION_DIGEST);
        evidence.put("normalizedPayload", NORMALIZED);
        evidence.put("normalizedPayloadDigest", NORMALIZED_PAYLOAD_DIGEST);
        return json.writeValueAsString(evidence);
    }

    private static ResultReceipt recordAdoption(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteRemoteRepairNormalizationStore store,
            ObjectMapper json,
            Operation operation)
            throws Exception
    {
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
        MutationFence fence = mock(MutationFence.class);
        when(fence.fencingToken()).thenReturn(17L);
        Candidate candidate = new Candidate(
                "candidate-head", "source-tree", "result-tree",
                "LEGACY_REFLOG_WINDOW_V1", null);
        ObjectNode evidence = json.createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("operationId", operation.operationId());
        evidence.put("sourceCodeSubjectRevision",
                operation.sourceCodeSubjectRevision());
        evidence.put("sourceCodeSubjectKind", operation.sourceCodeSubjectKind());
        evidence.put("sourceCodeSubjectId", operation.sourceCodeSubjectId());
        evidence.put("candidateCaptureKind", "LEGACY_REFLOG_WINDOW_V1");
        evidence.put("candidateHeadSha", "candidate-head");
        evidence.put("candidateParentSha", "head-1");
        evidence.put("candidateCount", 1);
        evidence.put("sourceExecutionStartedAtMs", 200);
        evidence.put("sourceExecutionFinishedAtMs", 300);
        String evidenceJson = json.writeValueAsString(evidence);
        return commands.execute("task-1", () -> store.recordAdopted(
                operation, fence, candidate, "candidate-fingerprint",
                evidenceJson, Instant.ofEpochMilli(600)));
    }

    private static void markAdoptionResultPending(
            JdbcTemplate jdbc,
            Operation operation,
            String payload,
            String evidence)
    {
        assertThat(jdbc.update("""
                DELETE FROM worktree_leases
                WHERE operation_id = ? AND task_id = ? AND task_epoch = ?
                """, operation.operationId(), operation.taskId(),
                operation.taskEpoch())).isOne();
        assertThat(jdbc.update("""
                UPDATE capacity_lease
                SET released_at_ms = 650,
                    release_reason = 'adoption execution complete'
                WHERE id = 'adoption-capacity' AND operation_id = ?
                  AND released_at_ms IS NULL
                """, operation.operationId())).isOne();
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 2, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?, pending_result_evidence = ?,
                    pending_result_error = NULL,
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?, pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ? AND status = 'RUNNING'
                """, payload, evidence, operation.taskEpoch(),
                operation.stageId(), operation.stageGeneration(),
                operation.operationId(), operation.attempt(),
                operation.sourceCodeFingerprint(), operation.sourceHeadSha(),
                operation.expectedBaseSha(), operation.ticketId())).isOne();
    }

    private static Map<String, List<String>> snapshotTables(
            Connection connection)
            throws SQLException
    {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : REBUILT_TABLES) {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM " + table);
                    ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                while (result.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int column = 1;
                            column <= metadata.getColumnCount(); column++) {
                        Object value = result.getObject(column);
                        String encoded = value == null
                                ? "<null>"
                                : value.getClass().getName() + ":" + value;
                        row.append(metadata.getColumnName(column))
                                .append('=')
                                .append(encoded.length()).append(':')
                                .append(encoded).append(';');
                    }
                    rows.add(row.toString());
                }
            }
            Collections.sort(rows);
            snapshot.put(table, List.copyOf(rows));
        }
        return Map.copyOf(snapshot);
    }

    private static DataSource dataSource(String url)
    {
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static void migrate(DataSource dataSource, String target)
    {
        var configuration = Flyway.configure().dataSource(dataSource);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private record FailedNormalization(
            NormalizationOperation operation,
            String executionId) {}
}
