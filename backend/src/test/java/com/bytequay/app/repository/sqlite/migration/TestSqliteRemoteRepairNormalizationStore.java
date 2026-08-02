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
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.AdoptionResult;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Disposition;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Operation;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.ResultReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairNormalizationStore.AdoptionCompletion;
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
import java.time.Instant;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SqliteTestPools.class)
class TestSqliteRemoteRepairNormalizationStore
{
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
    void normalizationAndAdoptionAreOneShotAndPreserveTheFailedSource()
            throws Exception
    {
        Fixture fixture = fixture();
        JdbcTemplate jdbc = fixture.jdbc();
        SqliteRemoteRepairNormalizationStore store = fixture.store();

        NormalizationDue due = store.findPending(10).getFirst();
        assertThat(due.current()).isTrue();
        assertThat(due.sourceCodeSubjectKind())
                .isEqualTo("DEVELOPMENT_REPORT");
        assertThat(due.sourceCodeSubjectId()).isEqualTo("report-1");
        assertThat(due.candidateCaptureKind())
                .isEqualTo("FROZEN_WRITER_PROOF_V1");

        String launchInput = normalizationLaunch(fixture.json(), due);
        NormalizationOperation normalization = fixture.commands().execute(
                "task-1", () -> store.insertNormalization(
                        due, launchInput,
                        Instant.ofEpochMilli(400)));
        assertThat(store.findPending(10)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_result_normalization_operation_v322
                WHERE normalization_due_id = ? AND status = 'DISPATCHED'
                """, Integer.class, due.id())).isOne();
        assertThat(store.requireNormalizationTaskId(
                normalization.turnId(), normalization.operationId()))
                .isEqualTo("task-1");
        assertThat(store.requireNormalizationDelivery(
                normalization.turnId(), normalization.operationId()))
                .isEqualTo(normalization);

        markNormalizationResultPending(fixture, normalization);
        String terminalEvidence = normalizationEvidence(
                fixture.json(), normalization);
        fixture.commands().execute("task-1", () -> {
            store.finishNormalization(
                    normalization, "SUCCEEDED", NORMALIZATION_DIGEST,
                    "SUCCEEDED", DispatchTicket.Acceptance.ACCEPTED,
                    NORMALIZED, NORMALIZED_PAYLOAD_DIGEST, terminalEvidence,
                    null, Instant.ofEpochMilli(500));
            return null;
        });

        var normalizationReceipt = store.findNormalizationReceipt(
                normalization.operationId()).orElseThrow();
        assertThat(normalizationReceipt.deliveryReceipt()).isEqualTo(
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED,
                        "ACCEPTED:Remote repair commit adoption requested"));
        assertThat(store.findNormalizationReceipt(
                normalization.operationId()).orElseThrow())
                .isEqualTo(normalizationReceipt);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_commit_adoption_operation_v322
                WHERE normalization_operation_row_id = ?
                """, Integer.class, normalization.id())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_reauthorization_v322
                WHERE normalization_operation_row_id = ?
                """, Integer.class, normalization.id())).isZero();

        assertThatThrownBy(() -> fixture.commands().execute("task-1", () -> {
            store.finishNormalization(
                    normalization, "SUCCEEDED", NORMALIZATION_DIGEST,
                    "SUCCEEDED", DispatchTicket.Acceptance.ACCEPTED,
                    NORMALIZED, NORMALIZED_PAYLOAD_DIGEST, terminalEvidence,
                    null, Instant.ofEpochMilli(501));
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_repair_commit_adoption_operation_v322
                WHERE normalization_operation_row_id = ?
                """, Integer.class, normalization.id())).isOne();

        Operation adoption = adoption(store, jdbc);
        assertThat(adoption.currentOwner()).isTrue();
        assertThat(adoption.sourceCodeSubjectKind())
                .isEqualTo("DEVELOPMENT_REPORT");
        assertThat(adoption.sourceCodeSubjectId()).isEqualTo("report-1");
        ResultReceipt result = recordAdoption(fixture, adoption);
        AdoptionResult payload = new AdoptionResult(
                1, Disposition.ADOPTED, adoption.id(), adoption.operationId(),
                adoption.normalizationId(), adoption.sourceOperationId(),
                adoption.sourceHeadSha(), adoption.expectedBaseSha(),
                result.candidateHeadSha(), "source-tree", "result-tree",
                result.id(), result.resultCodeFingerprint(), result.evidence(),
                null);
        markAdoptionResultPending(
                jdbc, adoption, fixture.json().writeValueAsString(payload),
                result.evidence());
        AdoptionCompletion completion = fixture.commands().execute(
                "task-1", () -> store.finishAdoption(
                        adoption, payload, "SUCCEEDED", ADOPTION_DIGEST,
                        "SUCCEEDED", DispatchTicket.Acceptance.ACCEPTED,
                        result.evidence(), null, Instant.ofEpochMilli(700)));

        assertThat(completion.shouldValidate()).isTrue();
        assertThat(completion.authorizationId())
                .isEqualTo("base-authorization");
        assertThat(jdbc.queryForMap("""
                SELECT code_fingerprint, head_sha, base_sha
                FROM remote_code_subject
                WHERE stage_turn_id = 'source-stage-turn'
                """))
                .containsEntry("code_fingerprint", "candidate-fingerprint")
                .containsEntry("head_sha", "candidate-head")
                .containsEntry("base_sha", "base-1");
        assertThat(jdbc.queryForMap("""
                SELECT source_kind, source_operation_id, code_fingerprint,
                       head_sha, base_sha
                FROM remote_worktree_subject
                WHERE source_operation_id = ?
                """, adoption.operationId()))
                .containsEntry("source_kind", "CI_STAGE_TURN")
                .containsEntry("source_operation_id", adoption.operationId())
                .containsEntry("code_fingerprint", "candidate-fingerprint")
                .containsEntry("head_sha", "candidate-head")
                .containsEntry("base_sha", "base-1");
        assertThat(jdbc.queryForMap("""
                SELECT fix_attempt_count, status
                FROM ci_repair_episode WHERE id = 'episode-1'
                """))
                .containsEntry("fix_attempt_count", 1)
                .containsEntry("status", "FIXING");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM task_blocker WHERE id = 'malformed-blocker'
                """, String.class)).isEqualTo("RESOLVED");
        var adoptionReceipt = store.findAdoptionReceipt(
                adoption.operationId()).orElseThrow();
        assertThat(adoptionReceipt.deliveryReceipt()).isEqualTo(
                new DispatchTicket.DeliveryReceipt(
                        DispatchTicket.Acceptance.ACCEPTED,
                        "ACCEPTED:CI validation requested after normalized repair adoption"));
        assertThat(store.findAdoptionReceipt(
                adoption.operationId()).orElseThrow())
                .isEqualTo(adoptionReceipt);
        assertThat(store.requireAdoptionTaskId(
                "task-1", adoption.operationId())).isEqualTo("task-1");

        assertThatThrownBy(() -> fixture.commands().execute("task-1", () ->
                store.finishAdoption(
                        adoption, payload, "SUCCEEDED", ADOPTION_DIGEST,
                        "SUCCEEDED", DispatchTicket.Acceptance.ACCEPTED,
                        result.evidence(), null, Instant.ofEpochMilli(701))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM remote_worktree_subject
                WHERE source_operation_id = ?
                """, Integer.class, adoption.operationId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT fix_attempt_count FROM ci_repair_episode
                WHERE id = 'episode-1'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM ci_base_repair_authorization_v303
                WHERE id = 'base-authorization'
                """, String.class)).isEqualTo("CLAIMED");
        assertSourceFailureWasPreserved(jdbc);
    }

    private Fixture fixture()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("normalization-store.db")
                + "?foreign_keys=ON";
        DataSource dataSource = SqliteTestPools.open(url);
        Flyway.configure().dataSource(dataSource).target("322").load().migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
            insertTypedSnapshot(connection);
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        }
        Flyway.configure().dataSource(dataSource).load().migrate();
        try (Connection connection = connect(url)) {
            seedMalformedBaseRepair(connection, new ObjectMapper());
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new Fixture(
                jdbc,
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(dataSource)),
                new SqliteRemoteRepairNormalizationStore(jdbc),
                new ObjectMapper());
    }

    private static void seedMalformedBaseRepair(
            Connection connection, ObjectMapper json)
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

        String error = "OWNER_OUTPUT_MALFORMED: expected strict JSON";
        String payloadJson = sourcePayload(json, error);
        String rawResult = sourceRawResult(json, payloadJson, error);
        var pending = connection.prepareStatement("""
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
                """);
        try (pending) {
            pending.setString(1, payloadJson);
            pending.setString(2, error);
            pending.executeUpdate();
        }
        var execution = connection.prepareStatement("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('source-execution', 'source-ticket', 1, 'openai',
                    'FAILED', 200, 300, ?)
                """);
        try (execution) {
            execution.setString(1, rawResult);
            execution.executeUpdate();
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
                    delivery_evidence = 'delivered', completed_at_ms = 301,
                    last_error = 'owner result failed'
                WHERE id = 'source-ticket'
                """);
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
                INSERT INTO remote_repair_result_normalization_due_v322(
                    id, ci_repair_episode_id, source_operation_row_id,
                    source_operation_id, source_stage_turn_id,
                    source_dispatch_ticket_id, source_agent_execution_id,
                    source_base_repair_authorization_id, blocker_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, semantic_attempt, execution_attempt,
                    source_code_subject_revision, source_code_subject_kind,
                    source_code_subject_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, source_malformed_output,
                    source_raw_result_digest, required_result_shape,
                    candidate_capture_kind, candidate_code_fingerprint,
                    candidate_head_sha, candidate_parent_sha,
                    candidate_base_sha, candidate_clean,
                    candidate_merge_base_sha, candidate_source_tree_sha,
                    candidate_result_tree_sha,
                    candidate_source_head_merge_base_sha,
                    candidate_branch_name,
                    source_execution_started_at_ms,
                    source_execution_finished_at_ms, status, recorded_at_ms)
                SELECT 'normalization-due', 'episode-1',
                    'source-operation-row', 'source-operation',
                    'source-stage-turn', 'source-ticket', 'source-execution',
                    'base-authorization', 'malformed-blocker', 'task-1', 1,
                    'remote-stage-1', 1, 1, 1,
                    code.source_code_subject_revision,
                    code.source_code_subject_kind,
                    code.source_code_subject_id,
                    'fingerprint-1', 'head-1',
                    'base-1', %s, '%s',
                    '{"schemaVersion":1,"summary":"string"}',
                    'FROZEN_WRITER_PROOF_V1', 'candidate-fingerprint',
                    'candidate-head', 'head-1', 'base-1', 1, 'base-1',
                    'source-tree', 'result-tree', 'head-1', 'dev/task-1',
                    200, 300, 'PENDING', 301
                FROM task_current_code_subject_fence_v322 code
                WHERE code.task_id = 'task-1'
                """.formatted(sql(MALFORMED), SOURCE_DIGEST));
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

    private static String sourcePayload(ObjectMapper json, String error)
            throws Exception
    {
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
        ObjectNode subject = payload.putObject("outputCodeSubject");
        subject.put("codeFingerprint", "candidate-fingerprint");
        subject.put("headSha", "candidate-head");
        subject.put("baseSha", "base-1");
        subject.put("clean", true);
        subject.put("mergeBaseSha", "base-1");
        subject.put("sourceTreeSha", "source-tree");
        subject.put("resultTreeSha", "result-tree");
        subject.putNull("discardedNoChangeHeadSha");
        subject.putNull("restoredHeadSha");
        subject.put("sourceHeadMergeBaseSha", "head-1");
        subject.put("candidateParentSha", "head-1");
        subject.put("branchName", "dev/task-1");
        return json.writeValueAsString(payload);
    }

    private static String sourceRawResult(
            ObjectMapper json, String payloadJson, String error)
            throws Exception
    {
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
        return json.writeValueAsString(raw);
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
                + "sourceCodeSubjectKind="
                + due.sourceCodeSubjectKind() + "\n"
                + "sourceCodeSubjectId=" + due.sourceCodeSubjectId() + "\n"
                + "expectedCodeFingerprint=" + due.expectedCodeFingerprint() + "\n"
                + "expectedHeadSha=" + due.expectedHeadSha() + "\n"
                + "expectedBaseSha=" + due.expectedBaseSha() + "\n\n"
                + "Frozen malformed output encoded as one JSON string:\n"
                + json.writeValueAsString(due.malformedOutput()));
        launch.set("toolEndpoint", endpoint);
        return json.writeValueAsString(launch);
    }

    private static void markNormalizationResultPending(
            Fixture fixture, NormalizationOperation operation)
            throws Exception
    {
        ObjectMapper json = fixture.json();
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
        JdbcTemplate jdbc = fixture.jdbc();
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

    private static Operation adoption(
            SqliteRemoteRepairNormalizationStore store, JdbcTemplate jdbc)
    {
        String operationId = jdbc.queryForObject("""
                SELECT operation_id
                FROM remote_repair_commit_adoption_operation_v322
                """, String.class);
        Operation operation = store.requireByOperationId(operationId);
        assertThat(store.requireAdoptionTaskId("task-1", operationId))
                .isEqualTo("task-1");
        assertThat(store.requireAdoptionDelivery("task-1", operationId))
                .isEqualTo(operation);
        return operation;
    }

    private static ResultReceipt recordAdoption(
            Fixture fixture, Operation operation)
            throws Exception
    {
        JdbcTemplate jdbc = fixture.jdbc();
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
                "FROZEN_WRITER_PROOF_V1", null);
        ObjectNode evidence = fixture.json().createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("operationId", operation.operationId());
        evidence.put("sourceCodeSubjectRevision",
                operation.sourceCodeSubjectRevision());
        evidence.put("sourceCodeSubjectKind",
                operation.sourceCodeSubjectKind());
        evidence.put("sourceCodeSubjectId", operation.sourceCodeSubjectId());
        evidence.put("candidateCaptureKind", "FROZEN_WRITER_PROOF_V1");
        evidence.put("candidateHeadSha", "candidate-head");
        evidence.put("candidateParentSha", "head-1");
        evidence.put("candidateCount", 1);
        evidence.put("sourceExecutionStartedAtMs", 200);
        evidence.put("sourceExecutionFinishedAtMs", 300);
        String evidenceJson = fixture.json().writeValueAsString(evidence);
        return fixture.commands().execute("task-1", () -> fixture.store()
                .recordAdopted(
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
                """, payload, evidence,
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.operationId(),
                operation.attempt(), operation.sourceCodeFingerprint(),
                operation.sourceHeadSha(), operation.expectedBaseSha(),
                operation.ticketId())).isOne();
    }

    private static void assertSourceFailureWasPreserved(JdbcTemplate jdbc)
    {
        assertThat(jdbc.queryForMap("""
                SELECT operation.status AS operation_status,
                       turn.status AS turn_status, ticket.status AS ticket_status,
                       blocker.status AS blocker_status,
                       authorization.status AS authorization_status
                FROM ci_repair_operation operation
                JOIN stage_turn turn ON turn.id = operation.stage_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                JOIN task_blocker blocker ON blocker.id = 'malformed-blocker'
                JOIN ci_base_repair_authorization_v303 authorization
                  ON authorization.id = operation.base_repair_authorization_id
                WHERE operation.id = 'source-operation-row'
                """))
                .containsEntry("operation_status", "FAILED")
                .containsEntry("turn_status", "FAILED")
                .containsEntry("ticket_status", "FAILED")
                .containsEntry("blocker_status", "RESOLVED")
                .containsEntry("authorization_status", "CLAIMED");
    }

    private static String sql(String value)
    {
        return "'" + value.replace("'", "''") + "'";
    }

    private record Fixture(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteRemoteRepairNormalizationStore store,
            ObjectMapper json) {}
}
