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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.stage.BranchSyncRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteCiProvenance;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.ObservationDisposition;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.RemoteFeedbackRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteMergeObservationCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteMergeRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteObservationDomainHooks;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler.MergeQueueCapability;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ObservationRequest;
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestRemoteMergeQueueCapability
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @CsvSource({"SUPPORTED,MERGE_QUEUE", "UNSUPPORTED,DIRECT"})
    void knownCapabilityPersistsAndStartsTheExpectedMergeMode(
            MergeQueueCapability capability, MergeMode expectedMode)
            throws Exception
    {
        Runtime runtime = runtime(capability.name());
        String snapshotId = deliver(runtime, capability, false);

        assertThat(snapshotCapability(runtime, snapshotId))
                .isEqualTo(capability.name());
        String readinessId = makeReady(runtime, capability.name());
        RemoteMergeRuntimeCoordinator.Result started = startMerge(
                runtime, readinessId);

        assertThat(started.receipt().mode()).isEqualTo(expectedMode);
        assertThat(runtime.jdbc().queryForObject("""
                SELECT mode FROM remote_merge_operation
                WHERE operation_id = 'merge-operation'
                """, String.class)).isEqualTo(expectedMode.name());
    }

    @Test
    void missingCapabilityJsonPersistsUnknownAndCannotCreateReadiness()
            throws Exception
    {
        Runtime runtime = runtime("legacy");
        String snapshotId = deliver(
                runtime, MergeQueueCapability.UNKNOWN, true);

        assertThat(snapshotCapability(runtime, snapshotId)).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> makeReady(runtime, "UNKNOWN"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "Ready evidence requires known merge queue capability");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_readiness_evidence", Integer.class))
                .isZero();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_merge_operation", Integer.class))
                .isZero();
    }

    @Test
    void productionFoldCompletesLegacyUnknownThenKnownObservationStartsAutoMerge()
            throws Exception
    {
        Runtime runtime = productionRuntime("legacy-recovery");

        String unknownSnapshot = deliver(
                runtime, MergeQueueCapability.UNKNOWN, true);

        assertThat(snapshotCapability(runtime, unknownSnapshot))
                .isEqualTo("UNKNOWN");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT status FROM remote_observation_operation
                WHERE snapshot_id = ?
                """, String.class, unknownSnapshot)).isEqualTo("ACCEPTED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT checkpoint FROM stage WHERE id = 'remote-stage-1'
                """, String.class)).isEqualTo("WAITING_REMOTE_REVIEW");
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_readiness_evidence", Integer.class))
                .isZero();
        assertThat(runtime.jdbc().queryForObject(
                "SELECT COUNT(*) FROM remote_merge_operation", Integer.class))
                .isZero();

        String knownSnapshot = deliver(
                runtime, MergeQueueCapability.SUPPORTED, false);

        assertThat(snapshotCapability(runtime, knownSnapshot))
                .isEqualTo("SUPPORTED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT checkpoint FROM stage WHERE id = 'remote-stage-1'
                """, String.class)).isEqualTo("MERGING");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT merge_queue_capability
                FROM remote_readiness_evidence
                """, String.class)).isEqualTo("SUPPORTED");
        assertThat(runtime.jdbc().queryForObject("""
                SELECT mode FROM remote_merge_operation
                """, String.class)).isEqualTo("MERGE_QUEUE");
    }

    private Runtime runtime(String suffix)
            throws Exception
    {
        RuntimeBase base = runtimeBase(suffix);
        RemoteObservationRuntimeCoordinator observations =
                new RemoteObservationRuntimeCoordinator(
                        base.commands(), base.observations(),
                        (candidate, acceptance) -> {
                            acceptance.accept();
                            return RemoteObservationConsumer.Consumption.ACCEPTED;
                        },
                        base.json(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Runtime(
                base.jdbc(), base.commands(), base.json(), observations);
    }

    private Runtime productionRuntime(String suffix)
            throws Exception
    {
        RuntimeBase base = runtimeBase(suffix);
        insertAutomationPolicy(base.jdbc());
        SqliteRemoteDevelopmentRuntimeStore development =
                new SqliteRemoteDevelopmentRuntimeStore(base.jdbc());
        RemoteDevelopmentStageManager remoteStage =
                new RemoteDevelopmentStageManager(
                        base.commands(), stageStore(base.jdbc()), development);
        RemoteDevelopmentRuntimeCoordinator remote =
                new RemoteDevelopmentRuntimeCoordinator(
                        base.commands(), remoteStage, development, base.json(),
                        mock(PRService.class),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteMergeRuntimeCoordinator merges = new RemoteMergeRuntimeCoordinator(
                base.commands(), remoteStage,
                new SqliteRemoteMergeRuntimeStore(base.jdbc()));
        RemoteCiRepairRuntimeCoordinator ciRepair = mock(
                RemoteCiRepairRuntimeCoordinator.class);
        when(ciRepair.acceptObservationInCommand(any()))
                .thenReturn(ObservationDisposition.CONTINUE);
        BranchSyncRuntimeCoordinator branchSync = mock(
                BranchSyncRuntimeCoordinator.class);
        when(branchSync.acceptObservationInCommand(any()))
                .thenReturn(ObservationDisposition.CONTINUE);
        PRService prs = mock(PRService.class);
        PR open = mock(PR.class);
        when(open.status()).thenReturn(PR.STATUS_REMOTE_OPEN);
        when(prs.findByTask(any())).thenReturn(Optional.of(open));
        RemoteObservationDomainHooks domainHooks = new RemoteObservationDomainHooks(
                development, remoteStage, ciRepair,
                branchSync,
                mock(RemoteMergeObservationCoordinator.class), merges, prs);
        RemoteDevelopmentObservationConsumer consumer =
                new RemoteDevelopmentObservationConsumer(
                        development, mock(RemoteFeedbackRuntimeCoordinator.class),
                        remote,
                        new RemoteDevelopmentObservationConsumer.Hooks(
                                domainHooks::acceptCiInCommand,
                                domainHooks::acceptBranchInCommand,
                                domainHooks::acceptMergeInCommand,
                                domainHooks::acceptReadinessInCommand),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        RemoteObservationRuntimeCoordinator observations =
                new RemoteObservationRuntimeCoordinator(
                        base.commands(), base.observations(), consumer,
                        base.json(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Runtime(
                base.jdbc(), base.commands(), base.json(), observations);
    }

    private RuntimeBase runtimeBase(String suffix)
            throws Exception
    {
        String migrationUrl = "jdbc:sqlite:"
                + tempDir.resolve("merge-capability-" + suffix + ".db");
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

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(migrationUrl + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        SqliteRemoteRuntimeStore store = new SqliteRemoteRuntimeStore(jdbc);
        return new RuntimeBase(jdbc, commands, json, store);
    }

    private static String deliver(
            Runtime runtime,
            MergeQueueCapability capability,
            boolean omitCapability)
            throws Exception
    {
        ObservationRequest request = runtime.observations().requestObservation(
                "task-1", "remote-stage-1");
        RemoteObservationOperationHandler.Observation observation = observation(
                "observation-" + request.semanticAttempt(), capability);
        ObjectNode payloadNode = runtime.json().valueToTree(observation);
        if (omitCapability) {
            payloadNode.remove("mergeQueueCapability");
        }
        String payload = runtime.json().writeValueAsString(payloadNode);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "remote-stage-1", 1L, request.operationId(),
                request.semanticAttempt(), null, "head-1", "base-1");
        markResultPending(runtime.jdbc(), request.operationId(), fence, payload);
        runtime.observations().deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, "remote-stage-1",
                        RemoteObservationOperationHandler.CALLBACK_ROUTE),
                fence, new DispatchTicket.DispatchResult(
                        fence, SUCCEEDED, payload, payload, null));
        return runtime.jdbc().queryForObject("""
                SELECT id FROM remote_pr_snapshot WHERE observation_key = ?
                """, String.class, observation.observationKey());
    }

    private static RemoteObservationOperationHandler.Observation observation(
            String key, MergeQueueCapability capability)
    {
        RemoteCiProvenance provenance = new RemoteCiProvenance(
                4, "acme/widget", 41, "head-1", "base-1", null,
                true, List.of(), List.of());
        return new RemoteObservationOperationHandler.Observation(
                4, key, "head-1", "base-1",
                RemoteObservationOperationHandler.PrState.OPEN,
                RemoteObservationOperationHandler.Mergeability.MERGEABLE,
                RemoteObservationOperationHandler.MergeQueueState.NONE,
                capability, 0, 0, 0, 0, 0, 0,
                List.of(new RemoteCiPolicy.Check(
                        "CHECK_RUN", "build:" + key, "build",
                        RemoteCiPolicy.CheckState.PASSED, "completed", "success",
                        null, 900L, "{}")),
                List.of(), "octocat", true, provenance, "{}",
                NOW.toEpochMilli());
    }

    private static String makeReady(Runtime runtime, String capability)
    {
        insertAutomationPolicy(runtime.jdbc());
        SqliteRemoteDevelopmentRuntimeStore remote =
                new SqliteRemoteDevelopmentRuntimeStore(runtime.jdbc());
        String readinessId = "readiness-1";
        var readiness = runtime.commands().execute("task-1", () ->
                remote.proveReadiness(
                        readinessId, "task-1", "remote-stage-1", null,
                        "exact readiness", NOW));
        assertThat(readiness.ready()).isTrue();
        assertThat(runtime.jdbc().queryForObject("""
                SELECT merge_queue_capability FROM remote_readiness_evidence
                WHERE id = ?
                """, String.class, readinessId)).isEqualTo(capability);
        assertThat(runtime.jdbc().update("""
                UPDATE stage
                SET version = version + 1, checkpoint = 'READY_TO_MERGE'
                WHERE id = 'remote-stage-1' AND checkpoint = 'WAITING_CI'
                """)).isOne();
        return readinessId;
    }

    private static void insertAutomationPolicy(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-1', 'task-1', 1, 'TEST', 1, 1, 0, 0,
                    2, 0, 0, 0, 'test', 1000)
                """);
    }

    private static StageManager.Store stageStore(JdbcTemplate jdbc)
            throws Exception
    {
        Class<?> type = Class.forName(
                "com.bytequay.app.developmentflow.stage.persistence.V2StageStore");
        var constructor = type.getDeclaredConstructor(JdbcTemplate.class);
        constructor.setAccessible(true);
        return StageManager.Store.class.cast(constructor.newInstance(jdbc));
    }

    private static RemoteMergeRuntimeCoordinator.Result startMerge(
            Runtime runtime, String readinessId)
    {
        RemoteDevelopmentStageManager remote = mock(
                RemoteDevelopmentStageManager.class);
        when(remote.authorizeMergeInCommand(any())).thenReturn(
                CommandResult.applied(mock(StageManager.State.class)));
        RemoteMergeRuntimeCoordinator coordinator =
                new RemoteMergeRuntimeCoordinator(
                        runtime.commands(), remote,
                        new SqliteRemoteMergeRuntimeStore(runtime.jdbc()));
        return coordinator.start(new RemoteMergeRuntimeCoordinator.Command(
                "merge-command", "auto-merge-policy", "task-1",
                "remote-stage-1", readinessId, "merge-authorization",
                "merge-operation", "merge-ticket",
                SqliteRemoteMergeRuntimeStore.AuthorityKind.AUTO_MERGE_POLICY,
                "squash", 3));
    }

    private static String snapshotCapability(Runtime runtime, String snapshotId)
    {
        return runtime.jdbc().queryForObject("""
                SELECT merge_queue_capability FROM remote_pr_snapshot
                WHERE id = ?
                """, String.class, snapshotId);
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
                fence.expectedBaseSha(), operationId)).isOne();
    }

    private record Runtime(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            ObjectMapper json,
            RemoteObservationRuntimeCoordinator observations) {}

    private record RuntimeBase(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            ObjectMapper json,
            SqliteRemoteRuntimeStore observations) {}
}
