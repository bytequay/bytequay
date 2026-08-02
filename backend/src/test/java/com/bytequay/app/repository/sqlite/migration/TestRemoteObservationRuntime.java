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

import com.bytequay.app.developmentflow.compatibility.V2BranchGuardProjection;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.BaseCiHistoryRewriter;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.EffectRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BaseRepairAuthorization;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiBudgets;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
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
    void remoteRepairContextTreatsStewardshipExceptionAsAutoApproveOff()
            throws Exception
    {
        Runtime runtime = runtime(
                candidate -> RemoteCiRepairRuntimeCoordinator
                        .Classification.UNKNOWN,
                new CiBudgets(0, 1, 2, 1),
                store -> (candidate, episode) -> {});
        SqliteRemoteRepairTurnStore turns =
                new SqliteRemoteRepairTurnStore(runtime.jdbc());

        insertAutomationPolicy(
                runtime, "automation-enabled", 1, true, false);
        assertThat(turns.requireContext(
                "task-1", "remote-stage-1").autoApprove()).isTrue();

        insertAutomationPolicy(
                runtime, "automation-stewardship", 2, false, true);
        assertThat(turns.requireContext(
                "task-1", "remote-stage-1").autoApprove()).isFalse();
    }

    @Test
    void loadsObservationContextWithTheProductionSingleConnectionPool()
            throws Exception
    {
        Path file = tempDir.resolve("single-connection-observation.db");
        String migrationUrl = "jdbc:sqlite:%s".formatted(file);
        migrate(migrationUrl);
        try (Connection connection = connect(migrationUrl)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(migrationUrl);
        try (Connection connection = connect(migrationUrl)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
        }
        migrate(migrationUrl);
        try (Connection connection = connect(migrationUrl)) {
            connection.createStatement().executeUpdate("""
                    INSERT INTO remote_ci_required_check(
                        ci_policy_revision_id, check_name)
                    VALUES ('ci-policy-1', 'build')
                    """);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("%s?foreign_keys=ON&busy_timeout=30000"
                .formatted(migrationUrl));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(250);
        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TaskCommandExecutor commands = new TaskCommandExecutor(
                    new DataSourceTransactionManager(dataSource));
            SqliteRemoteRuntimeStore store = new SqliteRemoteRuntimeStore(jdbc);
            ObservationRequest request = commands.execute("task-1", () -> {
                RemoteContext context = store.requireRemoteContext(
                        "task-1", "remote-stage-1");
                return store.insertObservation(context, NOW);
            });

            RemoteObservationOperationHandler.OperationContext context =
                    store.requireObservation(request.operationId());

            assertThat(context.request().repositoryId()).isEqualTo("acme/widget");
            assertThat(context.request().requiredCheckNames())
                    .containsExactly("build");
            assertThat(jdbc.update("""
                    UPDATE dispatch_ticket
                    SET version = version + 1,
                        status = 'RECONCILE_WAIT',
                        infrastructure_attempts = 3,
                        started_at_ms = ?,
                        next_attempt_at_ms = NULL,
                        last_error = 'Failed to obtain JDBC Connection'
                    WHERE operation_id = ? AND status = 'REQUESTED'
                    """, NOW.toEpochMilli(), request.operationId())).isEqualTo(1);
            assertThat(store.findParkedObservations(
                    NOW.plusSeconds(30), 10))
                    .singleElement()
                    .satisfies(parked -> {
                        assertThat(parked.taskId()).isEqualTo("task-1");
                        assertThat(parked.stageId()).isEqualTo("remote-stage-1");
                    });
        }
    }

    @Test
    void provenBaseRepairOpensANewExactBlockerForEveryManualAttempt()
            throws Exception
    {
        Runtime runtime = runtime(
                candidate -> RemoteCiRepairRuntimeCoordinator
                        .Classification.BASE_DETERMINISTIC,
                new CiBudgets(0, 2, 2, 2),
                store -> (candidate, episode) -> store.blockCiEpisode(
                        episode, "CI_BASE_REPAIR_REQUIRED",
                        "manual base repair required", "{}", NOW));
        deliverStrictObservation(
                runtime, "base-failure", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        String firstBlocker = runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                WHERE owner_id = ? AND status = 'OPEN'
                """, String.class, episodeId);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());

        BaseRepairAuthorization first = commands.execute("task-1", () -> {
            CiEpisode episode = store.requireCiEpisode("task-1", episodeId);
            BaseRepairAuthorization authorization = store.authorizeBaseRepair(
                    episode, null, firstBlocker, "manual-base-repair-1",
                    "MANUAL", "user", "manual base repair", "head-1", NOW);
            store.insertManualCiTurnIntent(episode, authorization.id(), NOW);
            return authorization;
        });
        insertAutomationPolicy(runtime, "manual-base-policy", 1, false);
        deliverStrictObservation(
                runtime, "base-failure-fresh", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900);
        completeChangedCiFix(
                runtime, episodeId, first.id(), "fingerprint-2", "head-2",
                "base-1", NOW.plusMillis(1));
        commands.execute("task-1", () -> {
            store.blockCiEpisode(
                    store.requireCiEpisode("task-1", episodeId),
                    "CI_BASE_REPAIR_REQUIRED",
                    "manual base repair required", "{}",
                    NOW.plusMillis(1));
            return null;
        });

        assertThat(runtime.jdbc().queryForList("""
                SELECT id FROM task_blocker
                WHERE owner_id = ? AND blocker_type = 'CI_BASE_REPAIR_REQUIRED'
                ORDER BY opened_at_ms, id
                """, String.class, episodeId))
                .hasSize(2)
                .contains(firstBlocker);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                WHERE owner_id = ? AND status = 'OPEN'
                """, String.class, episodeId)).isNotEqualTo(firstBlocker);
    }

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
    void persistsStrictCiProvenanceInItsTypedSnapshotColumn()
            throws Exception
    {
        Runtime runtime = runtime();
        ObservationRequest request = runtime.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        RemoteCiProvenance.AggregateEvidence aggregate =
                new RemoteCiProvenance.AggregateEvidence(
                        "a".repeat(40), ".github/workflows/ci.yml",
                        101L, 1, 9_001L, "ci-success",
                        List.of(new RemoteCiProvenance.AggregateDependency(
                                "build", "github-check:1",
                                RemoteCiPolicy.CheckState.FAILED)));
        RemoteCiProvenance.CheckEvidence aggregateHead =
                new RemoteCiProvenance.CheckEvidence(
                        "github-check:9", new RemoteCiProvenance.CheckProfile(
                                15_368L, "github-actions", 7L,
                                ".github/workflows/ci.yml", "CI success"),
                        11L, 11L, 101L, 1, "head-1", "head-1",
                        "pull_request", RemoteCiPolicy.CheckState.FAILED,
                        true, Set.of(), null, aggregate);
        RemoteCiProvenance provenance = new RemoteCiProvenance(
                4, "acme/widget", 41, "head-1", "base-1", null,
                true, List.of(), List.of(
                        new RemoteCiProvenance.CheckComparison(
                                aggregateHead, null)));
        RemoteObservationOperationHandler.Observation observation =
                new RemoteObservationOperationHandler.Observation(
                        4, "strict-provenance", "head-1", "base-1",
                        RemoteObservationOperationHandler.PrState.OPEN,
                        RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                        RemoteObservationOperationHandler.MergeQueueState.NONE,
                        0, 0, 0, 0, 0, 0,
                        List.of(new RemoteCiPolicy.Check(
                                "CHECK_RUN", "build-strict", "build",
                                RemoteCiPolicy.CheckState.PASSED,
                                "completed", "success", null, 900L, "{}")),
                        List.of(), "octocat", true, provenance,
                        "{\"strict\":true}", 900);
        DispatchTicket.OperationFence fence = observationFence(
                request, "head-1", "base-1");
        String payload = runtime.json().writeValueAsString(observation);
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);

        runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));

        assertThat(runtime.jdbc().queryForObject("""
                SELECT json_extract(ci_provenance_json, '$.schemaVersion')
                FROM remote_pr_snapshot WHERE observation_key = ?
                """, Integer.class, "strict-provenance")).isEqualTo(4);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT json_extract(ci_provenance_json, '$.complete')
                FROM remote_pr_snapshot WHERE observation_key = ?
                """, Integer.class, "strict-provenance")).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT json_extract(ci_provenance_json,
                    '$.checks[0].head.aggregateEvidence.aggregateJobKey')
                FROM remote_pr_snapshot WHERE observation_key = ?
                """, String.class, "strict-provenance"))
                .isEqualTo("ci-success");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT json_extract(ci_provenance_json,
                    '$.checks[0].head.aggregateEvidence.dependencies[0].externalCheckId')
                FROM remote_pr_snapshot WHERE observation_key = ?
                """, String.class, "strict-provenance"))
                .isEqualTo("github-check:1");

        assertThat(List.of(
                "{}",
                "{\"schemaVersion\":null}",
                "{\"schemaVersion\":\"4\"}",
                "{\"schemaVersion\":4.0}"))
                .allSatisfy(malformed -> assertThatThrownBy(() ->
                        copySnapshotWithProvenance(runtime, malformed))
                        .isInstanceOf(DataAccessException.class));
    }

    @Test
    void keepsAlreadyDurableSchemaThreeConcreteProvenanceAdmissible()
            throws Exception
    {
        Runtime runtime = runtime();
        ObservationRequest request = runtime.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        RemoteCiProvenance provenance = new RemoteCiProvenance(
                3, "acme/widget", 41, "head-1", "base-1", null,
                true, List.of(), List.of());
        RemoteObservationOperationHandler.Observation observation =
                new RemoteObservationOperationHandler.Observation(
                        3, "legacy-strict-provenance", "head-1", "base-1",
                        RemoteObservationOperationHandler.PrState.OPEN,
                        RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                        RemoteObservationOperationHandler.MergeQueueState.NONE,
                        0, 0, 0, 0, 0, 0,
                        List.of(new RemoteCiPolicy.Check(
                                "CHECK_RUN", "build-legacy", "build",
                                RemoteCiPolicy.CheckState.PASSED,
                                "completed", "success", null, 900L, "{}")),
                        List.of(), "octocat", true, provenance,
                        "{\"strict\":true}", 900);
        DispatchTicket.OperationFence fence = observationFence(
                request, "head-1", "base-1");
        String payload = runtime.json().writeValueAsString(observation);
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);

        runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));

        assertThat(runtime.jdbc().queryForObject("""
                SELECT json_extract(ci_provenance_json, '$.schemaVersion')
                FROM remote_pr_snapshot WHERE observation_key = ?
                """, Integer.class, "legacy-strict-provenance")).isEqualTo(3);
    }

    @Test
    void replaysPreviouslyPersistedGitHubCheckKindAliasExactlyOnce()
            throws Exception
    {
        Runtime runtime = runtime();
        ObservationRequest request = runtime.coordinator().requestObservation(
                "task-1", "remote-stage-1");
        DispatchTicket.OperationFence fence = observationFence(
                request, "head-1", "base-1");
        String payload = runtime.json().writeValueAsString(observation(
                        "legacy-check-kind", "head-1", "base-1",
                        RemoteCiPolicy.CheckState.PASSED, 800))
                .replace("\"CHECK_RUN\"", "\"GITHUB_CHECK_RUN\"");
        assertThat(payload).contains("GITHUB_CHECK_RUN");
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                payload, payload, null);

        DispatchTicket.DeliveryReceipt first = runtime.coordinator().deliver(
                observationOwner(), fence, result);
        DispatchTicket.DeliveryReceipt duplicate = runtime.coordinator().deliver(
                observationOwner(), fence, result);

        assertThat(first.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(duplicate.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT check_kind FROM remote_ci_check_snapshot",
                String.class)).isEqualTo("CHECK_RUN");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_ci_evaluation", Integer.class))
                .isOne();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_observation_delivery_receipt",
                Integer.class)).isOne();
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
    void terminalClosedObservationStopsCiRepairAndClearsCleanupWork()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "failed-before-close", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);
        assertThat(openRemoteEpisodeCleanupBlockers(runtime)).isOne();

        deliverObservation(
                runtime, "closed-during-repair", "head-1", "base-1",
                RemoteObservationOperationHandler.PrState.CLOSED,
                RemoteCiPolicy.CheckState.FAILED, 900);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, stop_reason FROM ci_repair_episode
                """))
                .containsEntry("status", "STOPPED")
                .containsEntry(
                        "stop_reason",
                        "Remote pull request became terminal before CI repair completed");
        assertThat(openRemoteEpisodeCleanupBlockers(runtime)).isZero();

        deliverObservation(
                runtime, "closed-replay", "head-1", "base-1",
                RemoteObservationOperationHandler.PrState.CLOSED,
                RemoteCiPolicy.CheckState.FAILED, 950);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM ci_repair_episode", Integer.class)).isOne();
        assertThat(openRemoteEpisodeCleanupBlockers(runtime)).isZero();
    }

    @Test
    void observationBeforePushDeliveryKeepsTheEpisodeAndReobservesAfterward()
            throws Exception
    {
        Runtime runtime = runtime(
                candidate -> RemoteCiRepairRuntimeCoordinator
                        .Classification.TASK_DETERMINISTIC,
                new CiBudgets(0, 1, 2, 1),
                store -> (candidate, episode) -> {});
        deliverObservation(
                runtime, "failure-before-push", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);

        runtime.jdbc().update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('test-automation-policy', 'task-1', 1, 'TEST',
                    1, 1, 0, 0, 0, 0, 0, 0, 'test', 900)
                """);
        completeChangedCiFix(
                runtime, episodeId, null, "fingerprint-2", "head-2",
                "base-1", NOW.minusMillis(99));
        assertThat(runtime.jdbc().update("""
                UPDATE ci_repair_episode
                SET status = 'VALIDATING'
                WHERE id = ? AND status = 'FIXING' AND fix_attempt_count = 1
                """, episodeId)).isOne();

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());
        SqliteRemoteRepairTurnStore turns =
                new SqliteRemoteRepairTurnStore(runtime.jdbc());
        var push = commands.execute("task-1", () -> turns.insertCiPush(
                turns.requireContext("task-1", "remote-stage-1"),
                store.requireCiEpisode("task-1", episodeId), NOW));

        deliverObservation(
                runtime, "pushed-head-before-delivery", "head-2", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 950);

        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM ci_repair_episode WHERE id = ?
                """, String.class, episodeId)).isEqualTo("AWAITING_PUSH_CI");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT push_count FROM ci_repair_episode WHERE id = ?
                """, Integer.class, episodeId)).isZero();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_ci_evaluation
                WHERE head_sha = 'head-2' AND policy_outcome = 'ACCEPTED'
                """, Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM ci_repair_operation WHERE operation_id = ?
                """, String.class, push.operationId())).isEqualTo("DISPATCHED");

        DispatchTicket.OperationFence pushFence =
                new DispatchTicket.OperationFence(
                        1L, "remote-stage-1", 1L, push.operationId(), 1,
                        "fingerprint-2", "head-2", "base-1");
        RemoteEffectOperationHandler.Result pushResult =
                new RemoteEffectOperationHandler.Result(
                        1, push.operationId(),
                        RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                        "fingerprint-2", "head-2", "base-1",
                        "push accepted", null);
        String pushPayload = runtime.json().writeValueAsString(pushResult);
        markResultPending(
                runtime.jdbc(), push.operationId(), pushFence, pushPayload);

        DispatchTicket.DeliveryReceipt pushReceipt =
                runtime.repair().deliverEffect(
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.STAGE,
                                "remote-stage-1", "REMOTE_CI_PUSH_RESULT"),
                        pushFence,
                        new DispatchTicket.DispatchResult(
                                pushFence, DispatchTicket.Outcome.SUCCEEDED,
                                pushPayload, pushPayload, null));

        assertThat(pushReceipt.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, push_count, last_pushed_head_sha
                FROM ci_repair_episode WHERE id = ?
                """, episodeId))
                .containsEntry("status", "AWAITING_PUSH_CI")
                .containsEntry("push_count", 1)
                .containsEntry("last_pushed_head_sha", "head-2");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_observation_operation
                WHERE status IN ('REQUESTED', 'DISPATCHED')
                """, Integer.class)).isOne();

        deliverObservation(
                runtime, "green-after-push-delivery", "head-2", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 1_000);

        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM ci_repair_episode WHERE id = ?
                """, String.class, episodeId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void stoppedCiSubjectDoesNotReopenOnLaterObservation()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "failure-before-stop", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        assertThat(runtime.repair().stopAutomation(
                "task-1", episodeId, "stop-exact-subject", "user",
                "explicit stop").status()).isEqualTo("STOPPED");

        deliverObservation(
                runtime, "same-failure-after-stop", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM ci_repair_episode", Integer.class))
                .isOne();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM ci_repair_episode", String.class))
                .isEqualTo("STOPPED");
    }

    @Test
    void classificationBlockerKeepsOneLiveEpisodeForTheExactHead()
            throws Exception
    {
        Runtime runtime = runtime(
                candidate -> RemoteCiRepairRuntimeCoordinator.Classification.UNKNOWN,
                new CiBudgets(0, 1, 2, 1),
                store -> (candidate, episode) -> store.blockCiEpisode(
                        episode, "CI_FAILURE_CLASSIFICATION_REQUIRED",
                        "CI failure origin is unknown",
                        "{\"choices\":[\"CLASSIFY_TASK\",\"CLASSIFY_BASE\"]}",
                        NOW));

        deliverObservation(
                runtime, "unknown-failure-one", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);
        deliverObservation(
                runtime, "unknown-failure-two", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM ci_repair_episode", Integer.class))
                .isOne();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM ci_repair_episode", String.class))
                .isEqualTo("OPEN");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker WHERE status = 'OPEN'
                """, Integer.class)).isOne();

        deliverObservation(
                runtime, "green-with-blocker", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 950);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM ci_repair_episode", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM task_blocker", String.class))
                .isEqualTo("RESOLVED");
    }

    @Test
    void exactProofAtomicallySupersedesUnknownWithoutResettingItsBudget()
            throws Exception
    {
        AtomicReference<RemoteCiRepairRuntimeCoordinator.Classification>
                classification = new AtomicReference<>(
                        RemoteCiRepairRuntimeCoordinator.Classification.UNKNOWN);
        Runtime runtime = runtime(
                ignored -> classification.get(),
                new CiBudgets(3, 4, 5, 3),
                store -> (candidate, episode) -> {
                    if ("UNKNOWN".equals(episode.classification())) {
                        store.blockCiEpisode(
                                episode, "CI_FAILURE_CLASSIFICATION_REQUIRED",
                                "CI failure origin is unknown", "{}", NOW);
                    }
                });
        deliverStrictObservation(
                runtime, "unknown-strict-proof", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());
        String predecessorId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        insertAutomationPolicy(runtime, "unknown-history-policy", 1, true);
        completeChangedCiFix(
                runtime, predecessorId, null, "fingerprint-2", "head-2",
                "base-1", NOW.minusMillis(99));
        assertThat(runtime.jdbc().update("""
                UPDATE ci_repair_episode
                SET status = 'OPEN', rerun_count = 1,
                    delivery_retry_count = 1, push_count = 1,
                    last_pushed_head_sha = 'historical-repair-head'
                WHERE id = ? AND status = 'FIXING' AND fix_attempt_count = 1
                """, predecessorId)).isOne();
        CiEpisode predecessor =
                store.requireCiEpisode("task-1", predecessorId);
        RemoteObservationConsumer.Candidate first = runtime.accepted().get();

        ObservationEvidence differentSubject = new ObservationEvidence(
                first.evidence().snapshotId(),
                first.evidence().ciEvaluationId(),
                first.evidence().revision(),
                "different-head", first.evidence().baseSha(),
                first.evidence().ciOutcome(),
                first.evidence().observedAtMs());
        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> store.supersedeUnknownCiEpisode(
                        predecessor, first.context(), differentSubject,
                        "BASE_DETERMINISTIC", "a".repeat(64), NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact subject");

        // Reusing the predecessor's own evaluation cannot create a successor.
        // The stop and blocker resolution must roll back with that failed insert.
        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> store.supersedeUnknownCiEpisode(
                        predecessor, first.context(), first.evidence(),
                        "BASE_DETERMINISTIC", "b".repeat(64), NOW)))
                .isInstanceOf(RuntimeException.class);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT episode.status AS episode_status,
                       blocker.status AS blocker_status
                FROM ci_repair_episode episode
                JOIN task_blocker blocker ON blocker.owner_id = episode.id
                WHERE episode.id = ?
                """, predecessorId))
                .containsEntry("episode_status", "OPEN")
                .containsEntry("blocker_status", "OPEN");

        classification.set(
                RemoteCiRepairRuntimeCoordinator.Classification
                        .BASE_DETERMINISTIC);
        deliverStrictObservation(
                runtime, "proven-base-same-subject", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900);

        String successorId = runtime.jdbc().queryForObject("""
                SELECT successor_episode_id
                FROM ci_repair_episode_supersession_v303
                WHERE predecessor_episode_id = ?
                """, String.class, predecessorId);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT rerun_count, rerun_limit, fix_attempt_count,
                       fix_attempt_limit, delivery_retry_count,
                       delivery_retry_limit, push_count, push_limit,
                       last_pushed_head_sha, status
                FROM ci_repair_episode WHERE id = ?
                """, successorId))
                .containsEntry("rerun_count", 1)
                .containsEntry("rerun_limit", 3)
                .containsEntry("fix_attempt_count", 1)
                .containsEntry("fix_attempt_limit", 4)
                .containsEntry("delivery_retry_count", 1)
                .containsEntry("delivery_retry_limit", 5)
                .containsEntry("push_count", 1)
                .containsEntry("push_limit", 3)
                .containsEntry(
                        "last_pushed_head_sha", "historical-repair-head")
                .containsEntry("status", "OPEN");
        assertThat(runtime.jdbc().queryForMap("""
                SELECT predecessor.status AS predecessor_status,
                       blocker.status AS blocker_status,
                       successor.status AS successor_status
                FROM ci_repair_episode_supersession_v303 supersession
                JOIN ci_repair_episode predecessor
                  ON predecessor.id = supersession.predecessor_episode_id
                JOIN ci_repair_episode successor
                  ON successor.id = supersession.successor_episode_id
                JOIN task_blocker blocker
                  ON blocker.owner_id = predecessor.id
                WHERE supersession.predecessor_episode_id = ?
                """, predecessorId))
                .containsEntry("predecessor_status", "STOPPED")
                .containsEntry("blocker_status", "RESOLVED")
                .containsEntry("successor_status", "OPEN");

        RemoteObservationConsumer.Candidate proven = runtime.accepted().get();
        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> store.supersedeUnknownCiEpisode(
                        predecessor, proven.context(), proven.evidence(),
                        "BASE_DETERMINISTIC", "c".repeat(64), NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed before supersession");

        deliverStrictObservation(
                runtime, "proven-base-replay", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 950);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM ci_repair_episode", Integer.class))
                .isEqualTo(2);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_repair_episode_supersession_v303
                """, Integer.class)).isOne();
    }

    @Test
    void exactPolicyAuthorizationIsIdempotentAndAcceptedPushConsumesIt()
            throws Exception
    {
        Runtime runtime = baseRepairRuntime();
        deliverStrictObservation(
                runtime, "policy-base-failure", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());
        SqliteRemoteRepairTurnStore turns =
                new SqliteRemoteRepairTurnStore(runtime.jdbc());
        CiEpisode episode = onlyCiEpisode(runtime);

        insertAutomationPolicy(runtime, "automation-policy-disabled", 1, false);
        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        episode, "automation-policy-disabled", null,
                        "auto-command",
                        "AUTO_APPROVE_POLICY", null, "auto repair",
                        "head-1", NOW)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Base repair authorization is not exact");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_manifest_v303
                """, Integer.class)).isZero();

        insertAutomationPolicy(runtime, "automation-policy-2", 2, true);
        BaseRepairAuthorization authorization = commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        episode, "automation-policy-2", null, "auto-command",
                        "AUTO_APPROVE_POLICY", null, "auto repair",
                        "head-1", NOW));
        BaseRepairAuthorization replay = commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        episode, "automation-policy-2", null, "auto-command",
                        "AUTO_APPROVE_POLICY", null, "auto repair",
                        "head-1", NOW));
        assertThat(replay.id()).isEqualTo(authorization.id());
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_manifest_v303
                """, Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_authorization_v303
                """, Integer.class)).isOne();
        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        episode, "automation-policy-2", null, "auto-command",
                        "AUTO_APPROVE_POLICY", null, "auto repair",
                        "different-worktree-head", NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other values");

        completeChangedCiFix(
                runtime, episode.id(), authorization.id(), "fingerprint-2",
                "head-2", "base-1", NOW.plusMillis(1));
        EffectRequest validation = commands.execute("task-1", () -> {
            CiEpisode current = store.requireCiEpisode(
                    "task-1", episode.id());
            return turns.insertCiBaseRewriteValidation(
                    turns.requireContext("task-1", "remote-stage-1"),
                    current, authorization.id(), NOW.plusMillis(1));
        });
        String rewriteEvidence = successfulBaseRewriteEvidence(
                runtime, authorization, "head-2", "head-3");
        assertThat(deliverCiEffect(
                runtime, validation,
                "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT",
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "fingerprint-3", "head-3",
                rewriteEvidence, null).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.jdbc().queryForMap("""
                SELECT code_fingerprint, head_sha, base_sha
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """))
                .containsEntry("code_fingerprint", "fingerprint-3")
                .containsEntry("head_sha", "head-3")
                .containsEntry("base_sha", "base-1");
        assertThat(runtime.jdbc().queryForMap("""
                SELECT subject_kind, code_fingerprint, head_sha
                FROM task_code_subject_revision_v320
                WHERE task_id = 'task-1' AND task_epoch = 1
                ORDER BY revision DESC LIMIT 1
                """))
                .containsEntry("subject_kind", "CI_BASE_REPAIR")
                .containsEntry("code_fingerprint", "fingerprint-3")
                .containsEntry("head_sha", "head-3");

        EffectRequest push = commands.execute("task-1", () -> {
            CiEpisode current = store.requireCiEpisode(
                    "task-1", episode.id());
            return turns.insertCiPush(
                    turns.requireContext("task-1", "remote-stage-1"),
                    current, authorization.id(), NOW.plusMillis(1));
        });
        assertThat(deliverCiEffect(
                runtime, push, "REMOTE_CI_PUSH_RESULT",
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "fingerprint-3", "head-3",
                "accepted exact push", null).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        assertThat(store.requireBaseRepairAuthorization(authorization.id()))
                .satisfies(consumed -> {
                    assertThat(consumed.status()).isEqualTo("CONSUMED");
                    assertThat(consumed.terminalEvidence())
                            .isEqualTo("accepted exact push");
                });
        assertThat(store.requireCiEpisode("task-1", episode.id()))
                .satisfies(current -> {
                    assertThat(current.pushCount()).isOne();
                    assertThat(current.lastPushedHeadSha()).isEqualTo("head-3");
                    assertThat(current.status()).isEqualTo("AWAITING_PUSH_CI");
                });
    }

    @Test
    void failedBaseValidationClosesConsentAndFreshRetryNeedsANewBlocker()
            throws Exception
    {
        Runtime runtime = baseRepairRuntime();
        deliverStrictObservation(
                runtime, "manual-base-failure", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 800);

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());
        SqliteRemoteRepairTurnStore turns =
                new SqliteRemoteRepairTurnStore(runtime.jdbc());
        CiEpisode episode = onlyCiEpisode(runtime);
        insertAutomationPolicy(
                runtime, "manual-automation-policy", 1, false);
        assertThatThrownBy(() -> commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        episode, null, "missing-blocker", "manual-command-1",
                        "MANUAL", "user", "manual repair", "head-1", NOW)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Base repair authorization is not exact");

        commands.execute("task-1", () -> {
            store.blockCiEpisode(
                    episode, "CI_BASE_REPAIR_REQUIRED",
                    "manual base repair required", "{}", NOW);
            return null;
        });
        String firstBlocker = onlyOpenBaseRepairBlocker(runtime, episode.id());
        BaseRepairAuthorization first = commands.execute("task-1", () -> {
            BaseRepairAuthorization authorization = store.authorizeBaseRepair(
                    episode, null, firstBlocker, "manual-command-1", "MANUAL",
                    "user", "manual repair", "head-1", NOW);
            store.insertManualCiTurnIntent(episode, authorization.id(), NOW);
            return authorization;
        });
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE id = ?
                """, String.class, firstBlocker)).isEqualTo("RESOLVED");

        deliverStrictObservation(
                runtime, "manual-base-failure-fresh", "head-1", "base-1",
                RemoteCiPolicy.CheckState.FAILED, 900);

        completeChangedCiFix(
                runtime, episode.id(), first.id(), "fingerprint-2",
                "head-2", "base-1", NOW.plusMillis(1));
        EffectRequest validation = commands.execute("task-1", () -> {
            CiEpisode current = store.requireCiEpisode(
                    "task-1", episode.id());
            return turns.insertCiBaseRewriteValidation(
                    turns.requireContext("task-1", "remote-stage-1"),
                    current, first.id(), NOW.plusMillis(1));
        });
        String failedEvidence = runtime.json().writeValueAsString(
                new RemoteEffectOperationHandler.BaseRewriteEvidence(
                        "CI_BASE_REWRITE_V1", first.id(),
                        first.manifestDigest(),
                        baseRewriteProof("head-2", "head-3"),
                        List.of(new ValidationFailure(
                                "frontend", "lint failed"))));
        assertThat(deliverCiEffect(
                runtime, validation,
                "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT",
                RemoteEffectOperationHandler.Disposition.FAILED,
                "fingerprint-3", "head-3",
                failedEvidence, "lint failed").acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(store.requireBaseRepairAuthorization(first.id()).status())
                .isEqualTo("CLOSED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_rewrite_result_v303
                """, Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT validation_outcome
                FROM ci_base_repair_rewrite_result_v303
                """, String.class)).isEqualTo("FAILED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_subject_v303
                """, Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForMap("""
                SELECT code_fingerprint, head_sha, base_sha
                FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """))
                .containsEntry("code_fingerprint", "fingerprint-2")
                .containsEntry("head_sha", "head-2")
                .containsEntry("base_sha", "base-1");
        assertThatThrownBy(() -> runtime.jdbc().update("""
                INSERT INTO ci_base_repair_subject_v303(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, authorization_id,
                    ci_repair_operation_id, code_fingerprint,
                    head_sha, base_sha, recorded_at_ms)
                VALUES ('forged-failed-subject', 'task-1', 1,
                    'remote-stage-1', 1, ?, ?, 'fingerprint-3',
                    'head-3', 'base-1', 903)
                """, first.id(), validation.rowId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "Base repair subject lacks passed rewrite proof");

        commands.execute("task-1", () -> {
            CiEpisode current = store.requireCiEpisode(
                    "task-1", episode.id());
            store.reopenBaseRepairEpisode(current);
            CiEpisode reopened = store.requireCiEpisode(
                    "task-1", episode.id());
            store.blockCiEpisode(
                    reopened, "CI_BASE_REPAIR_REQUIRED",
                    "manual retry required", "{}", NOW.plusMillis(2));
            return null;
        });
        String secondBlocker = onlyOpenBaseRepairBlocker(
                runtime, episode.id());
        assertThat(secondBlocker).isNotEqualTo(firstBlocker);

        BaseRepairAuthorization closedReplay = commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        store.requireCiEpisode("task-1", episode.id()),
                        null, firstBlocker, "manual-command-1",
                        "MANUAL", "user", "manual repair", "head-1", NOW));
        assertThat(closedReplay.status()).isEqualTo("CLOSED");
        BaseRepairAuthorization second = commands.execute(
                "task-1", () -> store.authorizeBaseRepair(
                        store.requireCiEpisode("task-1", episode.id()),
                        null, secondBlocker, "manual-command-2",
                        "MANUAL", "user", "manual retry", "head-2",
                        NOW.plusMillis(3)));
        assertThat(second.semanticAttempt()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("CLAIMED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM task_blocker WHERE id = ?
                """, String.class, secondBlocker)).isEqualTo("RESOLVED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_manifest_v303
                """, Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM ci_base_repair_authorization_v303
                """, Integer.class)).isEqualTo(2);
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
                "base-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("task command");
        runtime.branch().start(
                "task-1", "remote-stage-1", "sync-command",
                "base-2");
        assertThat(runtime.guardProjection().project("task-1").state())
                .isEqualTo("fixing");
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
        assertThat(runtime.guardProjection().project("task-1"))
                .satisfies(guard -> {
                    assertThat(guard.enabled()).isTrue();
                    assertThat(guard.state()).isEqualTo("healthy");
                    assertThat(guard.health().behindBy()).isZero();
                    assertThat(guard.health().mergeable()).isTrue();
                    assertThat(guard.health().checksGreen()).isTrue();
                });
    }

    @Test
    void branchSyncExhaustionIsFiniteForAnUnchangedSubject()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "before-branch-exhaustion", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);
        deliverObservation(
                runtime, "branch-exhaustion-base-advance", "head-1", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 900);

        for (int attempt = 1;
                attempt <= V2BranchSyncPolicyManager.DEFAULT_ATTEMPT_LIMIT;
                attempt++) {
            deliverBranchEffect(
                    runtime, "FETCH_COMPARE", "head-1", "base-1", null,
                    RemoteEffectOperationHandler.Disposition.FAILED,
                    "head-1", "base-1");
        }

        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, attempt_count, attempt_limit
                FROM branch_sync_effect_step WHERE ordinal = 1
                """))
                .containsEntry("status", "FAILED")
                .containsEntry(
                        "attempt_count",
                        V2BranchSyncPolicyManager.DEFAULT_ATTEMPT_LIMIT)
                .containsEntry(
                        "attempt_limit",
                        V2BranchSyncPolicyManager.DEFAULT_ATTEMPT_LIMIT);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_dispatch_operation",
                Integer.class)).isEqualTo(
                        V2BranchSyncPolicyManager.DEFAULT_ATTEMPT_LIMIT);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM branch_sync_episode
                """, String.class)).isEqualTo("FAILED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_blocker
                WHERE blocker_type = 'BRANCH_SYNC_EXHAUSTED'
                  AND status = 'OPEN'
                """, Integer.class)).isOne();

        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM branch_sync_episode", String.class);
        String blockerId = runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                WHERE blocker_type = 'BRANCH_SYNC_EXHAUSTED'
                """, String.class);
        int ticketsBeforeControl = runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM dispatch_ticket", Integer.class);
        var controlled = runtime.branch().controlExhausted(
                "task-1", episodeId, blockerId, "branch-control-command",
                BranchSyncRuntimeCoordinator.BranchControlAction
                        .MANUAL_TAKEOVER,
                "user", "continue outside automation");
        var replay = runtime.branch().controlExhausted(
                "task-1", episodeId, blockerId, "branch-control-command",
                BranchSyncRuntimeCoordinator.BranchControlAction
                        .MANUAL_TAKEOVER,
                "user", "continue outside automation");

        assertThat(replay).isEqualTo(controlled);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_control_command_v319",
                Integer.class)).isOne();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT status FROM task_blocker WHERE id = ?",
                String.class, blockerId)).isEqualTo("RESOLVED");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM dispatch_ticket", Integer.class))
                .isEqualTo(ticketsBeforeControl);

        deliverObservation(
                runtime, "branch-exhaustion-same-subject", "head-1", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 1_000);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_episode", Integer.class))
                .isOne();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_dispatch_operation",
                Integer.class)).isEqualTo(
                        V2BranchSyncPolicyManager.DEFAULT_ATTEMPT_LIMIT);
    }

    @Test
    void manualBranchSyncReplayRetainsItsObservationBeforeAndAfterConsumption()
            throws Exception
    {
        Runtime runtime = runtime();
        try (Connection connection = runtime.dataSource().getConnection()) {
            insertSnapshot(
                    connection, 1, 1, "head-1", "base-2", "OPEN",
                    "MERGEABLE");
            insertFailedCi(connection, 1, 1, "head-1", "base-2");
            acceptSnapshot(connection, 1, 1, "head-1", "base-2");
        }
        runtime.jdbc().update("""
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_limit, delivery_retry_limit,
                    push_limit, opened_at_ms)
                VALUES ('manual-sync-ci', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-2',
                    'TASK_DETERMINISTIC', 'OPEN', 0, 2, 2, 2, 800)
                """);
        runtime.jdbc().update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('manual-sync-blocker', 'task-1', 'remote-stage-1',
                    'STAGE', 'remote-stage-1', 'snapshot-1-1',
                    'CI_BRANCH_SYNC_REQUIRED', 'OPEN', '{}', 801)
                """);

        var first = runtime.branch().startCiPrecondition(
                "task-1", "manual-sync-ci", "manual-sync-blocker",
                "manual-sync-command", "user", "sync exact base");
        var beforeConsumption = runtime.branch().startCiPrecondition(
                "task-1", "manual-sync-ci", "manual-sync-blocker",
                "manual-sync-command", "user", "sync exact base");

        assertThat(beforeConsumption.observation().operationId())
                .isEqualTo(first.observation().operationId());
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_observation_operation",
                Integer.class)).isOne();

        deliverObservation(
                runtime, first.observation(), "manual-sync-later",
                "head-1", "base-2", RemoteCiPolicy.CheckState.FAILED, 900);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM ci_branch_sync_manual_authorization_v319
                WHERE command_id = 'manual-sync-command'
                """, String.class)).isEqualTo("CONSUMED");

        var afterConsumption = runtime.branch().startCiPrecondition(
                "task-1", "manual-sync-ci", "manual-sync-blocker",
                "manual-sync-command", "user", "sync exact base");
        assertThat(afterConsumption.observation().operationId())
                .isEqualTo(first.observation().operationId());
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_observation_operation",
                Integer.class)).isOne();
    }

    @Test
    void terminalMergedObservationStopsBranchSyncAndClearsCleanupWork()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "initial-before-merge", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);
        insertLiveBranchEpisode(runtime);
        assertThat(openRemoteEpisodeCleanupBlockers(runtime)).isOne();

        deliverObservation(
                runtime, "merged-during-branch-sync", "head-1", "base-1",
                RemoteObservationOperationHandler.PrState.MERGED,
                RemoteCiPolicy.CheckState.PASSED, 900);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT status, error_message FROM branch_sync_episode
                """))
                .containsEntry("status", "STOPPED")
                .containsEntry(
                        "error_message",
                        "Remote pull request became terminal before branch sync completed");
        assertThat(openRemoteEpisodeCleanupBlockers(runtime)).isZero();

        deliverObservation(
                runtime, "merged-replay", "head-1", "base-1",
                RemoteObservationOperationHandler.PrState.MERGED,
                RemoteCiPolicy.CheckState.PASSED, 950);
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_episode", Integer.class)).isOne();
        assertThat(openRemoteEpisodeCleanupBlockers(runtime)).isZero();
    }

    @Test
    void acceptedPublishArmsTheDefaultPolicyExactlyOnce()
            throws Exception
    {
        Runtime runtime = runtime();
        assertThat(runtime.guardProjection().project("task-1").enabled()).isFalse();

        armFirstPushPolicy(runtime);
        armFirstPushPolicy(runtime);

        assertThat(runtime.policies().current("task-1"))
                .satisfies(policy -> {
                    assertThat(policy.enabled()).isTrue();
                    assertThat(policy.source()).isEqualTo("FIRST_PUSH_DEFAULT");
                    assertThat(policy.attemptLimit()).isEqualTo(8);
                });
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_branch_sync_policy_revision
                WHERE task_id = 'task-1'
                """, Integer.class)).isOne();
    }

    @Test
    void explicitDisableSurvivesRestartAndPreventsBaseDriftSync()
            throws Exception
    {
        Runtime runtime = runtime();
        runtime.policies().update("task-1", false, "nightly");
        V2BranchSyncPolicyManager restarted = new V2BranchSyncPolicyManager(
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(runtime.dataSource())),
                runtime.jdbc(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(restarted.current("task-1").enabled()).isFalse();
        assertThat(restarted.current("task-1").source())
                .isEqualTo("USER_CONFIGURED");

        deliverObservation(
                runtime, "disabled-initial", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);
        deliverObservation(
                runtime, "disabled-base-advance", "head-1", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 900);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_episode", Integer.class))
                .isZero();
        assertThat(runtime.policies().current("task-1").enabled()).isFalse();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM task_branch_sync_policy_revision
                WHERE task_id = 'task-1'
                """, Integer.class)).isOne();
    }

    @Test
    void independentRemoteHeadMoveNeverStartsOrRewritesBranchSync()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "head-move-initial", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);

        deliverObservation(
                runtime, "independent-head-move", "head-2", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 900);

        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_episode", Integer.class))
                .isZero();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_sync_dispatch_operation",
                Integer.class)).isZero();
        assertThat(runtime.jdbc().queryForMap("""
                SELECT head_sha, base_sha FROM task_current_code_subject_v230
                WHERE task_id = 'task-1'
                """))
                .containsEntry("head_sha", "head-1")
                .containsEntry("base_sha", "base-1");
        assertThat(runtime.jdbc().queryForMap("""
                SELECT current_head_sha, current_base_sha
                FROM remote_development_stage
                WHERE stage_id = 'remote-stage-1'
                """))
                .containsEntry("current_head_sha", "head-2")
                .containsEntry("current_base_sha", "base-2");
    }

    @Test
    void conflictAndFailureProjectWithoutMutatingLegacyGuardState()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "projection-initial", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);
        runtime.branch().start(
                "task-1", "remote-stage-1", "projection-conflict", "base-2");
        deliverBranchEffect(
                runtime, "FETCH_COMPARE", "head-1", "base-1", null,
                RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                "head-1", "base-1");
        deliverBranchEffect(
                runtime, "MECHANICAL_REBASE", "head-1", "base-1", null,
                RemoteEffectOperationHandler.Disposition.CONFLICT,
                "head-1", "base-2");
        assertThat(runtime.guardProjection().project("task-1").state())
                .isEqualTo("conflicted");

        runtime.jdbc().update("""
                UPDATE branch_sync_episode
                SET status = 'FAILED', completed_at_ms = 1100,
                    error_message = 'repair failed'
                WHERE status = 'CONFLICT_REPAIR'
                """);
        assertThat(runtime.guardProjection().project("task-1").state())
                .isEqualTo("needs_attention");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM branch_guard", Integer.class)).isZero();
    }

    @Test
    void acceptedPureBaseAdvanceStartsTheTypedBranchSyncEpisode()
            throws Exception
    {
        Runtime runtime = runtime();
        deliverObservation(
                runtime, "initial-before-base-advance", "head-1", "base-1",
                RemoteCiPolicy.CheckState.PASSED, 800);

        deliverObservation(
                runtime, "base-advanced", "head-1", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 900);

        assertThat(runtime.jdbc().queryForMap("""
                SELECT source_snapshot_id, old_head_sha, observed_base_sha,
                       target_base_sha, branch_sync_policy_revision_id,
                       policy_source, status
                FROM branch_sync_episode
                """))
                .containsEntry("old_head_sha", "head-1")
                .containsEntry("observed_base_sha", "base-2")
                .containsEntry("target_base_sha", "base-2")
                .containsEntry("policy_source", "FIRST_PUSH_DEFAULT")
                .containsEntry("status", "OPEN");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT branch_sync_policy_revision_id IS NOT NULL
                FROM branch_sync_episode
                """, Boolean.class)).isTrue();
        assertThat(runtime.guardProjection().project("task-1").state())
                .isEqualTo("fixing");
        assertThat(runtime.jdbc().queryForMap("""
                SELECT kind, expected_head_sha, expected_base_sha,
                       target_base_sha, status
                FROM branch_sync_dispatch_operation
                """))
                .containsEntry("kind", "FETCH_COMPARE")
                .containsEntry("expected_head_sha", "head-1")
                .containsEntry("expected_base_sha", "base-1")
                .containsEntry("target_base_sha", "base-2")
                .containsEntry("status", "DISPATCHED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM branch_sync_episode
                """, Integer.class)).isOne();

        // A repeated observation sees the live Episode and cannot create a
        // second owner for the same Task/Stage subject.
        deliverObservation(
                runtime, "base-advanced-repeat", "head-1", "base-2",
                RemoteCiPolicy.CheckState.PASSED, 950);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT COUNT(*) FROM branch_sync_episode
                """, Integer.class)).isOne();
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

        assertThatThrownBy(() -> runtime.repair().extendBudget(
                "task-2", episodeId, "wrong-task", 1, 0, 0,
                "user", "wrong Task"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runtime.repair().extendBudget(
                "task-1", episodeId, "extend-rerun", 1, 0, 0,
                "user", "one more rerun").status()).isEqualTo("OPEN");
        assertThat(runtime.repair().extendBudget(
                "task-1", episodeId, "extend-rerun", 1, 0, 0,
                "user", "one more rerun").rerunLimit()).isEqualTo(2);
        assertThatThrownBy(() -> runtime.repair().extendBudget(
                "task-1", episodeId, "extend-rerun", 2, 0, 0,
                "user", "one more rerun"))
                .isInstanceOf(IllegalArgumentException.class);
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
        return runtime(
                candidate -> RemoteCiRepairRuntimeCoordinator
                        .Classification.FLAKY,
                new CiBudgets(1, 0, 2, 0),
                store -> (candidate, episode) -> {
                    throw new AssertionError(
                            "flaky CI must never start code repair");
                });
    }

    private Runtime baseRepairRuntime()
            throws Exception
    {
        return runtime(
                ignored -> RemoteCiRepairRuntimeCoordinator
                        .Classification.BASE_DETERMINISTIC,
                new CiBudgets(0, 2, 2, 2),
                ignored -> new RemoteCiRepairRuntimeCoordinator
                        .DeterministicRepairPort()
                {
                    @Override
                    public void startInCommand(
                            RemoteObservationConsumer.Candidate candidate,
                            CiEpisode episode)
                    {
                    }

                    @Override
                    public void acceptValidationInCommand(
                            CiEpisode episode,
                            RemoteEffectOperationHandler.Result result)
                    {
                    }
                });
    }

    /**
     * Every case in this class starts from the same seeded database, so it is built once
     * and copied per test rather than replaying the migration chain each time.
     */
    private static void seedRuntime(Path database)
            throws Exception
    {
        String url = "jdbc:sqlite:" + database;
        migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url);
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
        }
        migrate(url);
    }

    private Runtime runtime(
            RemoteCiRepairRuntimeCoordinator.FailureClassifier classifier,
            CiBudgets budgets,
            Function<SqliteRemoteRuntimeStore,
                    RemoteCiRepairRuntimeCoordinator.DeterministicRepairPort>
                    repairs)
            throws Exception
    {
        Path file = tempDir.resolve("observation-runtime.db");
        String migrationUrl = "jdbc:sqlite:" + file;
        MigratedSqliteDatabase.copyFixture(
                "remote-observation-runtime", file, TestRemoteObservationRuntime::seedRuntime);

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(migrationUrl + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        SqliteRemoteRuntimeStore store = new SqliteRemoteRuntimeStore(jdbc);
        V2BranchSyncPolicyManager policies = new V2BranchSyncPolicyManager(
                commands, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        AtomicReference<RemoteObservationConsumer.Candidate> accepted =
                new AtomicReference<>();
        RemoteCiRepairRuntimeCoordinator repair =
                new RemoteCiRepairRuntimeCoordinator(
                        commands, store, classifier, budgets,
                        repairs.apply(store),
                        new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        BranchSyncRuntimeCoordinator branch = new BranchSyncRuntimeCoordinator(
                commands, store, policies,
                (episode, result) -> {},
                (episode, step, result) -> {
                    throw new AssertionError("Brain review is disabled");
                },
                false, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationConsumer consumer = (candidate, acceptance) -> {
            acceptance.accept();
            accepted.set(candidate);
            if (branch.acceptObservationInCommand(candidate)
                    == RemoteCiRepairRuntimeCoordinator.ObservationDisposition
                            .CONTINUE) {
                repair.acceptObservationInCommand(candidate);
            }
            return RemoteObservationConsumer.Consumption.ACCEPTED;
        };
        ObjectMapper json = new ObjectMapper();
        RemoteObservationRuntimeCoordinator coordinator =
                new RemoteObservationRuntimeCoordinator(
                        commands, store, consumer, json,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        return new Runtime(
                dataSource, jdbc, json, coordinator, repair, branch, policies,
                new V2BranchGuardProjection(jdbc), accepted);
    }

    private static CiEpisode onlyCiEpisode(Runtime runtime)
    {
        String episodeId = runtime.jdbc().queryForObject(
                "SELECT id FROM ci_repair_episode", String.class);
        return new SqliteRemoteRuntimeStore(runtime.jdbc())
                .requireCiEpisode("task-1", episodeId);
    }

    private static void copySnapshotWithProvenance(
            Runtime runtime, String provenance)
    {
        runtime.jdbc().update("""
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state,
                    effective_approval_count, write_approval_count,
                    changes_requested_count, requested_reviewer_count,
                    unresolved_thread_count, unresolved_comment_count,
                    observed_at_ms, raw_evidence, ci_provenance_json)
                SELECT 'malformed-provenance', remote_development_stage_id,
                       task_id, task_epoch, stage_generation,
                       remote_pr_binding_id, observation_revision + 1,
                       'malformed-provenance', remote_repository_id,
                       remote_pr_number, head_sha, base_sha, pr_state,
                       mergeability, merge_queue_state,
                       effective_approval_count, write_approval_count,
                       changes_requested_count, requested_reviewer_count,
                       unresolved_thread_count, unresolved_comment_count,
                       observed_at_ms, raw_evidence, ?
                  FROM remote_pr_snapshot
                 WHERE observation_key = 'strict-provenance'
                """, provenance);
    }

    private static void insertAutomationPolicy(
            Runtime runtime, String id, int revision, boolean autoApprove)
    {
        insertAutomationPolicy(runtime, id, revision, autoApprove, false);
    }

    private static void insertAutomationPolicy(
            Runtime runtime,
            String id,
            int revision,
            boolean autoApprove,
            boolean stewardshipException)
    {
        runtime.jdbc().update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES (?, 'task-1', ?, 'TEST', ?, 0, 0, 0, 0, 0, 0, ?,
                    'test', 900)
                """, id, revision, autoApprove ? 1 : 0,
                stewardshipException ? 1 : 0);
    }

    private static void insertRepairCodeSubject(
            Runtime runtime,
            String suffix,
            String headSha,
            String fingerprint,
            long recordedAtMs)
    {
        runtime.jdbc().update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES (?, 'remote-stage-1', 1, 'REMOTE_CI_REPAIR',
                    'SUCCEEDED', ?, 1, 1, 'fingerprint-1', 'head-1', 'base-1',
                    'CLI', 'repair', ?, ?, ?)
                """, suffix + "-turn", suffix + "-operation",
                recordedAtMs - 1, recordedAtMs - 1, recordedAtMs);
        runtime.jdbc().update("""
                INSERT INTO remote_code_subject(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, stage_turn_id, source_code_fingerprint,
                    source_head_sha, source_base_sha, code_fingerprint,
                    head_sha, base_sha, created_at_ms)
                VALUES (?, 'remote-stage-1', 'task-1', 1, 1, ?,
                    'fingerprint-1', 'head-1', 'base-1', ?, ?, 'base-1', ?)
                """, suffix + "-subject", suffix + "-turn",
                fingerprint, headSha, recordedAtMs);
    }

    private static String successfulBaseRewriteEvidence(
            Runtime runtime,
            BaseRepairAuthorization authorization,
            String inputHead,
            String outputHead)
            throws Exception
    {
        return runtime.json().writeValueAsString(
                new RemoteEffectOperationHandler.BaseRewriteEvidence(
                        "CI_BASE_REWRITE_V1", authorization.id(),
                        authorization.manifestDigest(),
                        baseRewriteProof(inputHead, outputHead), List.of()));
    }

    private static BaseCiHistoryRewriter.Proof baseRewriteProof(
            String inputHead, String outputHead)
    {
        return new BaseCiHistoryRewriter.Proof(
                "base-1", "head-1", inputHead,
                List.of("head-1"), List.of(inputHead),
                "repair-commit", outputHead, "repair-patch",
                List.of(), "original-series", "original-series",
                "tree", "tree", false);
    }

    private static String onlyOpenBaseRepairBlocker(
            Runtime runtime, String episodeId)
    {
        return runtime.jdbc().queryForObject("""
                SELECT id FROM task_blocker
                WHERE owner_id = ? AND blocker_type = 'CI_BASE_REPAIR_REQUIRED'
                  AND status = 'OPEN'
                """, String.class, episodeId);
    }

    private static void insertLiveBranchEpisode(Runtime runtime)
    {
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        commands.executeVoid("task-1", () -> {
            var policy = runtime.policies().armOnFirstPushInCommand("task-1");
            store.insertBranchEpisode(
                    store.requireRemoteContext("task-1", "remote-stage-1"),
                    "terminal-branch-sync", "base-2", policy.id(),
                    policy.source(), policy.attemptLimit(), NOW);
        });
    }

    private static void armFirstPushPolicy(Runtime runtime)
    {
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        commands.executeVoid("task-1", () ->
                runtime.policies().armOnFirstPushInCommand("task-1"));
    }

    private static int openRemoteEpisodeCleanupBlockers(Runtime runtime)
    {
        return runtime.jdbc().queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM ci_repair_episode episode
                     WHERE episode.task_id = 'task-1' AND episode.task_epoch = 1
                       AND episode.status NOT IN (
                           'SUCCEEDED','EXHAUSTED','STOPPED'))
                  + (SELECT COUNT(*) FROM branch_sync_episode episode
                     WHERE episode.task_id = 'task-1' AND episode.task_epoch = 1
                       AND episode.status NOT IN (
                           'SUCCEEDED','FAILED','STOPPED'))
                """, Integer.class);
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
        deliverObservation(
                runtime, key, head, base,
                RemoteObservationOperationHandler.PrState.OPEN,
                state, observedAtMs);
    }

    private static void deliverObservation(
            Runtime runtime,
            ObservationRequest request,
            String key,
            String head,
            String base,
            RemoteCiPolicy.CheckState state,
            long observedAtMs)
            throws Exception
    {
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
                key, head, base,
                RemoteObservationOperationHandler.PrState.OPEN,
                state, observedAtMs));
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);
        runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
    }

    private static void deliverObservation(
            Runtime runtime,
            String key,
            String head,
            String base,
            RemoteObservationOperationHandler.PrState prState,
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
                key, head, base, prState, state, observedAtMs));
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);
        runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
    }

    private static void deliverStrictObservation(
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
        String payload = runtime.json().writeValueAsString(strictObservation(
                key, head, base, state, observedAtMs));
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);
        runtime.coordinator().deliver(
                observationOwner(), fence,
                new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));
    }

    private static DispatchTicket.DeliveryReceipt deliverCiEffect(
            Runtime runtime,
            EffectRequest request,
            String callback,
            RemoteEffectOperationHandler.Disposition disposition,
            String resultFingerprint,
            String resultHead,
            String evidence,
            String error)
            throws Exception
    {
        EffectOperation operation = runtime.jdbc().queryForObject("""
                SELECT operation_id, semantic_attempt,
                       expected_code_fingerprint, expected_head_sha,
                       expected_base_sha
                FROM ci_repair_operation WHERE operation_id = ?
                """, (rs, row) -> new EffectOperation(
                        rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha")),
                request.operationId());
        DispatchTicket.OperationFence fence =
                new DispatchTicket.OperationFence(
                        1L, "remote-stage-1", 1L, operation.operationId(),
                        operation.attempt(), operation.expectedFingerprint(),
                        operation.expectedHead(), operation.expectedBase());
        RemoteEffectOperationHandler.Result result =
                new RemoteEffectOperationHandler.Result(
                        1, operation.operationId(), disposition,
                        resultFingerprint, resultHead, operation.expectedBase(),
                        evidence, error);
        String payload = runtime.json().writeValueAsString(result);
        markResultPending(
                runtime.jdbc(), operation.operationId(), fence, payload);
        return runtime.repair().deliverEffect(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                        callback),
                fence,
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
        return observation(
                key, head, base,
                RemoteObservationOperationHandler.PrState.OPEN,
                state, observedAtMs);
    }

    private static RemoteObservationOperationHandler.Observation observation(
            String key,
            String head,
            String base,
            RemoteObservationOperationHandler.PrState prState,
            RemoteCiPolicy.CheckState state,
            long observedAtMs)
    {
        return new RemoteObservationOperationHandler.Observation(
                1, key, head, base,
                prState,
                RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                RemoteObservationOperationHandler.MergeQueueState.NONE,
                0, 0, 0, 0, 0, 0,
                List.of(new RemoteCiPolicy.Check(
                        "CHECK_RUN", "build:" + key, "build", state,
                        "completed", state.name(), null, observedAtMs, "{}")),
                "{\"key\":\"" + key + "\"}", observedAtMs);
    }

    private static RemoteObservationOperationHandler.Observation strictObservation(
            String key,
            String head,
            String base,
            RemoteCiPolicy.CheckState state,
            long observedAtMs)
    {
        RemoteCiProvenance provenance = new RemoteCiProvenance(
                4, "acme/widget", 41, head, base, null, true,
                List.of(), List.of());
        return new RemoteObservationOperationHandler.Observation(
                4, key, head, base,
                RemoteObservationOperationHandler.PrState.OPEN,
                RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                RemoteObservationOperationHandler.MergeQueueState.NONE,
                0, 0, 0, 0, 0, 0,
                List.of(new RemoteCiPolicy.Check(
                        "CHECK_RUN", "build:" + key, "build", state,
                        "completed", state.name(), null, observedAtMs, "{}")),
                List.of(), "octocat", true, provenance,
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

    /**
     * Completes a real CI repair Turn through its durable operation and accepted
     * delivery receipt.  Tests that need an already-consumed CI fix budget must
     * not mutate {@code fix_attempt_count} without this V318 tree proof.
     */
    private static void completeChangedCiFix(
            Runtime runtime,
            String episodeId,
            String baseRepairAuthorizationId,
            String resultFingerprint,
            String resultHead,
            String resultBase,
            Instant at)
    {
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(runtime.dataSource()));
        SqliteRemoteRuntimeStore store =
                new SqliteRemoteRuntimeStore(runtime.jdbc());
        SqliteRemoteRepairTurnStore turns =
                new SqliteRemoteRepairTurnStore(runtime.jdbc());
        var request = commands.execute("task-1", () -> {
            CiEpisode episode = store.requireCiEpisode("task-1", episodeId);
            var accepted = runtime.accepted().get();
            assertThat(accepted).as("accepted CI observation").isNotNull();
            var freshness = store.authorizeCiTurn(accepted, episode, at)
                    .orElseThrow(() -> new IllegalStateException(
                            "fixture lacks an exact V319 CI freshness proof"));
            assertThat(freshness.episodeId()).isEqualTo(episodeId);
            var context = turns.requireContext("task-1", "remote-stage-1");
            var turnRequest = baseRepairAuthorizationId == null
                    ? turns.insertCiStageTurn(context, episode, "{}", "API", 2, at)
                    : turns.insertCiBaseRepairStageTurn(
                            context, episode, baseRepairAuthorizationId,
                            "{}", "API", 2, at);
            if (baseRepairAuthorizationId != null
                    && "MANUAL".equals(store.requireBaseRepairAuthorization(
                            baseRepairAuthorizationId).authorityKind())) {
                store.consumeManualCiTurnIntent(
                        store.findManualCiTurnIntent(episodeId).orElseThrow(), at);
            }
            return turnRequest;
        });
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, request.operationId(),
                request.attempt(), "fingerprint-1", "head-1", "base-1");
        markResultPending(runtime.jdbc(), request.operationId(), fence,
                "{\"repair\":\"changed\"}");
        commands.execute("task-1", () -> {
            var delivery = turns.requireTurnDelivery(
                    request.turnId(), request.operationId());
            turns.finishChangedCiStageTurn(
                    delivery, "SUCCEEDED", "c".repeat(64),
                    new SqliteRemoteRepairTurnStore.CodeSubject(
                            resultFingerprint, resultHead, resultBase),
                    "tree-before-" + request.operationId(),
                    "tree-after-" + request.operationId(),
                    "repair changed the checked-out tree", at.plusMillis(1));
            return null;
        });
    }

    private record Runtime(
            SQLiteDataSource dataSource,
            JdbcTemplate jdbc,
            ObjectMapper json,
            RemoteObservationRuntimeCoordinator coordinator,
            RemoteCiRepairRuntimeCoordinator repair,
            BranchSyncRuntimeCoordinator branch,
            V2BranchSyncPolicyManager policies,
            V2BranchGuardProjection guardProjection,
            AtomicReference<RemoteObservationConsumer.Candidate> accepted) {}

    private record EffectOperation(
            String operationId,
            int attempt,
            String expectedFingerprint,
            String expectedHead,
            String expectedBase) {}
}
