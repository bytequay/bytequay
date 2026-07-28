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
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiBudgets;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRemoteObservationRuntime
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @Test
    void acceptsNewExactHeadAndLeavesWaitingWithoutCapacity()
            throws Exception
    {
        Runtime runtime = runtime();
        ObservationRequest request = runtime.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        RemoteObservationOperationHandler.Observation observation =
                new RemoteObservationOperationHandler.Observation(
                        1, "observation-new-head", "head-2", "base-1",
                        RemoteObservationOperationHandler.PrState.OPEN,
                        RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                        RemoteObservationOperationHandler.MergeQueueState.NONE,
                        0, 0, 0, 0, 0, 0,
                        List.of(new RemoteCiPolicy.Check(
                                "CHECK_RUN", "build-2", "build",
                                RemoteCiPolicy.CheckState.QUEUED,
                                "queued", null, null, null, "{}")),
                        "{\"head\":\"head-2\"}", 900);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, request.operationId(),
                request.semanticAttempt(), null, "head-1", "base-1");
        String payload = runtime.json().writeValueAsString(observation);
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);

        DispatchTicket.DeliveryReceipt delivered = runtime.coordinator().deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                        RemoteObservationOperationHandler.CALLBACK_ROUTE),
                fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));

        assertThat(delivered.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.accepted().get()).isNotNull();
        assertThat(runtime.accepted().get().ciEvaluation().outcome())
                .isEqualTo(RemoteCiPolicy.PolicyOutcome.WAITING);
        assertThatThrownBy(() -> runtime.repair().acceptObservationInCommand(
                runtime.accepted().get()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task command");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT current_head_sha FROM remote_development_stage
                WHERE stage_id = 'remote-stage-1'
                """, String.class)).isEqualTo("head-2");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_head_evidence_invalidation
                WHERE accepted_snapshot_id = (
                    SELECT accepted_snapshot_id FROM remote_development_stage
                    WHERE stage_id = 'remote-stage-1')
                """, Integer.class)).isEqualTo(5);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM capacity_lease", Integer.class))
                .isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM dispatch_ticket
                WHERE status IN ('REQUESTED', 'CLAIMED', 'RUNNING')
                  AND created_at_ms >= 1000
                """, Integer.class)).isZero();

        DispatchTicket.DeliveryReceipt duplicate = runtime.coordinator().deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                        RemoteObservationOperationHandler.CALLBACK_ROUTE),
                fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
        assertThat(duplicate.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_pr_snapshot", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedCiRerunsOnceThenClosesOnlyAfterObservedGreen()
            throws Exception
    {
        Runtime runtime = runtime();
        ObservationRequest failedRequest = runtime.coordinator()
                .requestObservation("task-1", "remote-stage-1");
        DispatchTicket.OperationFence failedFence = observationFence(
                failedRequest, "head-1", "base-1");
        String failedPayload = runtime.json().writeValueAsString(observation(
                "failed-observation", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900));
        markResultPending(
                runtime.jdbc(), failedRequest.operationId(), failedFence,
                failedPayload);
        runtime.coordinator().deliver(
                observationOwner(), failedFence,
                new DispatchTicket.DispatchResult(
                        failedFence, DispatchTicket.Outcome.SUCCEEDED,
                        failedPayload, failedPayload, null));

        String rerunOperation = runtime.jdbc().queryForObject("""
                SELECT operation_id FROM ci_repair_operation
                WHERE kind = 'RERUN' AND status = 'DISPATCHED'
                """, String.class);
        DispatchTicket.OperationFence rerunFence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, rerunOperation, 1,
                null, "head-1", "base-1");
        RemoteEffectOperationHandler.Result rerunResult =
                new RemoteEffectOperationHandler.Result(
                        1, rerunOperation,
                        RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                        null, "head-1", "base-1", "rerun accepted", null);
        String rerunPayload = runtime.json().writeValueAsString(rerunResult);
        markResultPending(
                runtime.jdbc(), rerunOperation, rerunFence, rerunPayload);
        DispatchTicket.DeliveryReceipt rerunReceipt = runtime.repair().deliverRerun(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                        "REMOTE_CI_RERUN_RESULT"),
                rerunFence,
                new DispatchTicket.DispatchResult(
                        rerunFence, DispatchTicket.Outcome.SUCCEEDED,
                        rerunPayload, rerunPayload, null));

        assertThat(rerunReceipt.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT rerun_count FROM ci_repair_episode
                WHERE status = 'AWAITING_RERUN'
                """, Integer.class)).isEqualTo(1);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM capacity_lease", Integer.class))
                .isZero();

        ObservationRequest passedRequest = runtime.coordinator()
                .requestObservation("task-1", "remote-stage-1");
        DispatchTicket.OperationFence passedFence = observationFence(
                passedRequest, "head-1", "base-1");
        String passedPayload = runtime.json().writeValueAsString(observation(
                "passed-observation", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 950));
        markResultPending(
                runtime.jdbc(), passedRequest.operationId(), passedFence,
                passedPayload);
        runtime.coordinator().deliver(
                observationOwner(), passedFence,
                new DispatchTicket.DispatchResult(
                        passedFence, DispatchTicket.Outcome.SUCCEEDED,
                        passedPayload, passedPayload, null));

        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM ci_repair_episode
                """, String.class)).isEqualTo("SUCCEEDED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT terminal_ci_evaluation_id IS NOT NULL
                FROM ci_repair_episode
                """, Boolean.class)).isTrue();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM task_blocker", Integer.class)).isZero();
    }

    @Test
    void branchSyncUsesForceWithLeaseThenWaitsForObservedHead()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "initial-green", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);

        assertThatThrownBy(() -> runtime.branch().startInCommand(
                "task-1", "remote-stage-1", "outside-command",
                "base-2", "BASE_ADVANCED", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task command");
        runtime.branch().start(
                "task-1", "remote-stage-1", "sync-command",
                "base-2", "BASE_ADVANCED", 2);
        deliverBranchEffect(
                runtime, "FETCH_COMPARE", "head-1", "base-1", null,
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "head-1", "base-1");
        deliverBranchEffect(
                runtime, "MECHANICAL_REBASE", "head-1", "base-1", null,
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "head-2", "base-2");
        deliverBranchEffect(
                runtime, "VALIDATE", "head-2", "base-2", "fingerprint-2",
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "head-2", "base-2");
        deliverBranchEffect(
                runtime, "FORCE_WITH_LEASE_PUSH", "head-2", "base-2",
                "fingerprint-2",
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "head-2", "base-2");

        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM branch_sync_episode
                """, String.class)).isEqualTo("AWAITING_HEAD");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_push_proof", Integer.class))
                .isEqualTo(1);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT force_with_lease_expected_sha
                FROM branch_sync_push_proof
                """, String.class)).isEqualTo("head-1");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM capacity_lease", Integer.class))
                .isZero();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_worktree_subject", Integer.class))
                .isEqualTo(3);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT code_fingerprint, head_sha, base_sha
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """))
                .containsEntry("code_fingerprint", "fingerprint-2")
                .containsEntry("head_sha", "head-2")
                .containsEntry("base_sha", "base-2");

        deliverObservation(
                runtime, "observed-rebased-head", "head-2", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 1_200);

        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM branch_sync_episode
                """, String.class)).isEqualTo("SUCCEEDED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM branch_sync_effect_step
                WHERE status = 'SUCCEEDED'
                """, Integer.class)).isEqualTo(4);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM branch_sync_effect_step
                WHERE status = 'SKIPPED'
                """, Integer.class)).isEqualTo(2);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_head_evidence_invalidation",
                Integer.class)).isEqualTo(5);
    }

    @Test
    void oldHeadObservationIsHistoryOnlyAfterSubjectAdvances()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "initial", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);
        ObservationRequest stale = runtime.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        advanceRemoteSubject(runtime.jdbc());

        DispatchTicket.OperationFence fence = observationFence(
                stale, "head-1", "base-1");
        String payload = runtime.json().writeValueAsString(observation(
                "late-old-head", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 1_100));
        markResultPending(runtime.jdbc(), stale.operationId(), fence, payload);
        DispatchTicket.DeliveryReceipt receipt = runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));

        assertThat(receipt.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT current_head_sha FROM remote_development_stage
                WHERE stage_id = 'remote-stage-1'
                """, String.class)).isEqualTo("head-concurrent");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_pr_snapshot
                WHERE head_sha = 'head-1'
                """, Integer.class)).isEqualTo(2);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM remote_observation_operation
                WHERE operation_id = ?
                """, String.class, stale.operationId()))
                .isEqualTo("SUPERSEDED");
    }

    @Test
    void exhaustedCiRequiresExplicitBudgetOrStopCommand()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "failure-one", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);
        String rerunOperation = runtime.jdbc().queryForObject("""
                SELECT operation_id FROM ci_repair_operation
                WHERE kind = 'RERUN' AND status = 'DISPATCHED'
                """, String.class);
        DispatchTicket.OperationFence rerunFence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, rerunOperation, 1,
                null, "head-1", "base-1");
        RemoteEffectOperationHandler.Result rerun =
                new RemoteEffectOperationHandler.Result(
                        1, rerunOperation,
                        RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                        null, "head-1", "base-1", "rerun", null);
        String rerunPayload = runtime.json().writeValueAsString(rerun);
        markResultPending(
                runtime.jdbc(), rerunOperation, rerunFence, rerunPayload);
        runtime.repair().deliverRerun(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                        "REMOTE_CI_RERUN_RESULT"),
                rerunFence,
                new DispatchTicket.DispatchResult(
                        rerunFence, DispatchTicket.Outcome.SUCCEEDED,
                        rerunPayload, rerunPayload, null));
        deliverObservation(
                runtime, "failure-after-last-rerun", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900);

        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM ci_repair_episode", String.class))
                .isEqualTo("EXHAUSTED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT payload_json FROM task_blocker WHERE status = 'OPEN'
                """, String.class)).contains(
                        "EXTEND", "PER_PUSH_APPROVAL", "MANUAL_TAKEOVER",
                        "STOP_AUTOMATION");

        assertThat(runtime.repair().extendBudget(
                "task-1", episodeId, "extend-rerun", 1, 0, 0,
                "user", "one more rerun").status()).isEqualTo("OPEN");
        assertThat(runtime.repair().extendBudget(
                "task-1", episodeId, "extend-rerun", 1, 0, 0,
                "user", "one more rerun").rerunLimit()).isEqualTo(2);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE owner_id = ?
                """, String.class, episodeId)).isEqualTo("RESOLVED");

        assertThat(runtime.repair().stopAutomation(
                "task-1", episodeId, "stop-ci", "user", "manual fix")
                .status()).isEqualTo("STOPPED");
    }

    private Runtime runtime()
            throws Exception
    {
        Path file = tempDir.resolve("observation-runtime.db");
        String migrationUrl = "jdbc:sqlite:" + file;
        migrate(migrationUrl, "228");
        try (Connection connection = connect(migrationUrl)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(migrationUrl, "232");
        try (Connection connection = connect(migrationUrl)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
        }
        migrate(migrationUrl, "248");

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(migrationUrl + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        SqliteRemoteRuntimeStore store = new SqliteRemoteRuntimeStore(jdbc);
        AtomicReference<RemoteObservationConsumer.Candidate> accepted =
                new AtomicReference<>();
        RemoteCiRepairRuntimeCoordinator repair =
                new RemoteCiRepairRuntimeCoordinator(
                        commands, store,
                        candidate -> RemoteCiRepairRuntimeCoordinator
                                .Classification.FLAKY,
                        new CiBudgets(1, 0, 2, 0),
                        (candidate, episode) -> {
                            throw new AssertionError(
                                    "flaky CI must never start code repair");
                        },
                        new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        BranchSyncRuntimeCoordinator branch = new BranchSyncRuntimeCoordinator(
                commands, store,
                (episode, result) -> {
                    throw new AssertionError(
                            "no-conflict sync must not start a StageTurn");
                },
                (episode, step, result) -> {
                    throw new AssertionError("Brain review is disabled");
                },
                false, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationConsumer consumer = (candidate, acceptance) -> {
            acceptance.accept();
            accepted.set(candidate);
            repair.acceptObservationInCommand(candidate);
            branch.acceptObservationInCommand(candidate);
            return RemoteObservationConsumer.Consumption.ACCEPTED;
        };
        ObjectMapper json = new ObjectMapper();
        RemoteObservationRuntimeCoordinator coordinator =
                new RemoteObservationRuntimeCoordinator(
                        commands, store, consumer, json,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return new Runtime(jdbc, json, coordinator, repair, branch, accepted);
    }

    private static void deliverObservation(
            Runtime runtime,
            String key,
            String head,
            String base,
            RemoteCiPolicy.CheckState state,
            long observedAtMs)
            throws Exception
    {
        ObservationRequest request = runtime.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        String expectedHead = runtime.jdbc().queryForObject("""
                SELECT expected_head_sha FROM remote_observation_operation
                WHERE operation_id = ?
                """, String.class, request.operationId());
        String expectedBase = runtime.jdbc().queryForObject("""
                SELECT expected_base_sha FROM remote_observation_operation
                WHERE operation_id = ?
                """, String.class, request.operationId());
        DispatchTicket.OperationFence fence = observationFence(
                request, expectedHead, expectedBase);
        String payload = runtime.json().writeValueAsString(observation(
                key, head, base, state, observedAtMs));
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);
        runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
    }

    private static void deliverBranchEffect(
            Runtime runtime,
            String kind,
            String expectedHead,
            String expectedBase,
            String expectedFingerprint,
            RemoteEffectOperationHandler.Disposition disposition,
            String resultHead,
            String resultBase)
            throws Exception
    {
        EffectOperation operation = runtime.jdbc().queryForObject("""
                SELECT operation_id, semantic_attempt,
                       expected_code_fingerprint, expected_head_sha,
                       expected_base_sha
                FROM branch_sync_dispatch_operation
                WHERE kind = ? AND status = 'DISPATCHED'
                """, (rs, row) -> new EffectOperation(
                        rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha")), kind);
        assertThat(operation.expectedHead()).isEqualTo(expectedHead);
        assertThat(operation.expectedBase()).isEqualTo(expectedBase);
        assertThat(operation.expectedFingerprint()).isEqualTo(expectedFingerprint);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, operation.operationId(),
                operation.attempt(), operation.expectedFingerprint(),
                operation.expectedHead(), operation.expectedBase());
        RemoteEffectOperationHandler.Result result =
                new RemoteEffectOperationHandler.Result(
                        1, operation.operationId(), disposition,
                        "fingerprint-2", resultHead, resultBase,
                        "proof:" + kind, null);
        String payload = runtime.json().writeValueAsString(result);
        markResultPending(
                runtime.jdbc(), operation.operationId(), fence, payload);
        String callback = switch (kind) {
            case "FETCH_COMPARE" -> "BRANCH_SYNC_FETCH_RESULT";
            case "MECHANICAL_REBASE" -> "BRANCH_SYNC_REBASE_RESULT";
            case "VALIDATE" -> "BRANCH_SYNC_VALIDATION_RESULT";
            case "FORCE_WITH_LEASE_PUSH" -> "BRANCH_SYNC_PUSH_RESULT";
            default -> throw new IllegalArgumentException("unknown kind " + kind);
        };
        DispatchTicket.DeliveryReceipt delivered = runtime.branch().deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1", callback),
                fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
        assertThat(delivered.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
    }

    private static void advanceRemoteSubject(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state,
                    observed_at_ms, raw_evidence)
                VALUES ('concurrent-snapshot', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', 2, 'concurrent-observation', 'acme/widget',
                    41, 'head-concurrent', 'base-1', 'OPEN', 'MERGEABLE',
                    'NONE', 1000, '{}')
                """);
        jdbc.update("""
                INSERT INTO remote_ci_check_snapshot(
                    id, remote_pr_snapshot_id, check_kind, external_check_id,
                    check_name, normalized_state, provider_status,
                    provider_conclusion, observed_at_ms)
                VALUES ('concurrent-check', 'concurrent-snapshot', 'CHECK_RUN',
                    'concurrent-build', 'build', 'PASSED', 'completed',
                    'success', 1000)
                """);
        jdbc.update("""
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch,
                    stage_generation, head_sha, base_sha, normalized_status,
                    policy_outcome, check_count, missing_required_count,
                    evidence, evaluated_at_ms)
                VALUES ('concurrent-evaluation', 'remote-stage-1',
                    'concurrent-snapshot', 'ci-policy-1', 'task-1', 1, 1,
                    'head-concurrent', 'base-1', 'PASSED', 'ACCEPTED',
                    1, 0, '{}', 1000)
                """);
        assertThat(jdbc.update("""
                UPDATE remote_development_stage
                SET accepted_snapshot_id = 'concurrent-snapshot',
                    accepted_observation_revision = 2,
                    current_head_sha = 'head-concurrent',
                    current_base_sha = 'base-1', subject_changed_at_ms = 1000
                WHERE stage_id = 'remote-stage-1'
                """)).isEqualTo(1);
    }

    private static DispatchTicket.OperationFence observationFence(
            ObservationRequest request, String head, String base)
    {
        return new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, request.operationId(),
                request.semanticAttempt(), null, head, base);
    }

    private static DispatchTicket.OwnerReference observationOwner()
    {
        return new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                RemoteObservationOperationHandler.CALLBACK_ROUTE);
    }

    private static RemoteObservationOperationHandler.Observation observation(
            String key,
            String head,
            String base,
            RemoteCiPolicy.CheckState state,
            long observedAtMs)
    {
        return new RemoteObservationOperationHandler.Observation(
                1, key, head, base,
                RemoteObservationOperationHandler.PrState.OPEN,
                RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                RemoteObservationOperationHandler.MergeQueueState.NONE,
                0, 0, 0, 0, 0, 0,
                List.of(new RemoteCiPolicy.Check(
                        "CHECK_RUN", "build:" + key, "build", state,
                        "completed", state.name(), null, observedAtMs, "{}")),
                "{\"key\":\"" + key + "\"}", observedAtMs);
    }

    private static void markResultPending(
            JdbcTemplate jdbc,
            String operationId,
            DispatchTicket.OperationFence fence,
            String payload)
    {
        assertThat(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?, pending_result_evidence = ?,
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?, pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE operation_id = ? AND status = 'REQUESTED'
                """, payload, payload, fence.taskEpoch(), fence.stageId(),
                fence.stageGeneration(), fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha(), operationId)).isEqualTo(1);
    }

    private record Runtime(
            JdbcTemplate jdbc,
            ObjectMapper json,
            RemoteObservationRuntimeCoordinator coordinator,
            RemoteCiRepairRuntimeCoordinator repair,
            BranchSyncRuntimeCoordinator branch,
            AtomicReference<RemoteObservationConsumer.Candidate> accepted) {}

    private record EffectOperation(
            String operationId,
            int attempt,
            String expectedFingerprint,
            String expectedHead,
            String expectedBase) {}
}
