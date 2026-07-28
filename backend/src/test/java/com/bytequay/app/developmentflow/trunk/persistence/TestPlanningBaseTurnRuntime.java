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
package com.bytequay.app.developmentflow.trunk.persistence;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.trunk.PlanningBaseRefreshOperationHandler;
import com.bytequay.app.developmentflow.trunk.PlanningBaseTurnRuntime;
import com.bytequay.app.developmentflow.trunk.SqlitePlanningBaseTurnStore;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestPlanningBaseTurnRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void refreshResultLaunchesOneTurnWithItsExactSnapshotAcrossRestart()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("planning.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("repo"));
        Path planning = Files.createDirectory(tempDir.resolve("planning"));
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        SqlitePlanningBaseTurnStore planningStore =
                new SqlitePlanningBaseTurnStore(jdbc);
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        when(repositories.resolve("workspace-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        WatchedRepoStore watched = mock(WatchedRepoStore.class);
        when(watched.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0,
                        repository.toString(), null, null)));
        ThreadTurnHandoff handoff = new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123);
        PlanningBaseTurnRuntime runtime = new PlanningBaseTurnRuntime(
                manager, trunkStore, planningStore, handoff,
                repositories, watched, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
        PlanningBaseTurnRuntime.Request request = new PlanningBaseTurnRuntime.Request(
                "request-1", "user", "trunk-1", "workspace-1",
                "TRUNK_CONVERSATION", AgentTurnProviderSession.Transport.CLI,
                "codex", null, "gpt-5.6", "high", "trunk role",
                "start the next task", "compile a plan");

        PlanningBaseTurnRuntime.Receipt first = runtime.request(request);
        Files.delete(repository);
        PlanningBaseTurnRuntime.Receipt duplicate = runtime.request(request);
        assertThat(first.disposition()).isEqualTo(CommandResult.Disposition.APPLIED);
        assertThat(duplicate.disposition()).isEqualTo(
                CommandResult.Disposition.DUPLICATE);
        assertThat(duplicate.turnId()).isEqualTo(first.turnId());
        assertThat(count(jdbc, "planning_base_refresh_operation")).isOne();
        assertThat(count(jdbc, "dispatch_ticket")).isOne();
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(count(jdbc, "capacity_lease")).isZero();

        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, first.operationId(), 1,
                null, null, null);
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                new PlanningBaseRefreshOperationHandler.Snapshot(
                        1, first.operationId(), "trunk-1", repository.toString(),
                        planning.toString(), "refs/remotes/origin/main",
                        "0123456789abcdef", NOW.toEpochMilli());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                json.writeValueAsString(snapshot), "{}", null);
        markResultPending(jdbc, first.dispatchTicketId(), raw);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE);
        DispatchTicket.DeliveryReceipt accepted =
                runtime.deliver(owner, fence, raw);
        assertThat(accepted.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(new ThreadTurnProjection(jdbc, json)
                .turns("trunk-1", 10))
                .singleElement()
                .satisfies(turn -> {
                    assertThat(turn.turnId()).isEqualTo(first.turnId());
                    assertThat(turn.status()).isEqualTo("REQUESTED");
                    assertThat(turn.finishedAt()).isNull();
                    assertThat(turn.error()).isNull();
                });

        markDelivered(jdbc, first.dispatchTicketId(), accepted);
        runtime.afterDeliveryCommitted(owner, fence, raw, accepted);
        runtime.afterDeliveryCommitted(owner, fence, raw, accepted);
        new PlanningBaseTurnRuntime(
                manager(dataSource, new V2TrunkStore(new JdbcTemplate(dataSource))),
                new V2TrunkStore(new JdbcTemplate(dataSource)),
                new SqlitePlanningBaseTurnStore(new JdbcTemplate(dataSource)),
                new ThreadTurnHandoff(
                        manager, json, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                        53123),
                repositories, watched, json,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                .recoverCommittedDeliveries(20);

        assertThat(count(jdbc, "thread_turn")).isOne();
        assertThat(count(jdbc, "trunk_thread_turn_request_receipt")).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT planning_operation_id, expected_base_sha, status
                FROM thread_turn
                """))
                .containsEntry("planning_operation_id", first.planningOperationId())
                .containsEntry("expected_base_sha", "0123456789abcdef")
                .containsEntry("status", "REQUESTED");
        assertThat(jdbc.queryForMap("""
                SELECT planning_base_sha, planning_repo_root
                FROM threads WHERE id = 'trunk-1'
                """))
                .containsEntry("planning_base_sha", "0123456789abcdef")
                .containsEntry("planning_repo_root", repository.toString());
    }

    @Test
    void failedRefreshKeepsTheAcceptedTurnVisibleAndTerminal()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("failed.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("failed-repo"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Request request =
                new PlanningBaseTurnRuntime.Request(
                        "failed-request", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null,
                        "start the next task", "compile a plan");

        PlanningBaseTurnRuntime.Receipt accepted = runtime.request(request);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, accepted.operationId(), 1,
                null, null, null);
        DispatchTicket.DispatchResult failed = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.FAILED,
                null, "{}", "watched clone unavailable");
        markResultPending(jdbc, accepted.dispatchTicketId(), failed);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE);
        DispatchTicket.DeliveryReceipt receipt = runtime.deliver(
                owner, fence, failed);
        markDelivered(jdbc, accepted.dispatchTicketId(), receipt);

        ThreadTurnProjection projection = new ThreadTurnProjection(
                jdbc, new ObjectMapper());
        assertThat(projection.turns("trunk-1", 10))
                .singleElement()
                .satisfies(turn -> {
                    assertThat(turn.turnId()).isEqualTo(accepted.turnId());
                    assertThat(turn.status()).isEqualTo("FAILED");
                    assertThat(turn.error())
                            .isEqualTo("watched clone unavailable");
                });
        assertThat(projection.history("trunk-1"))
                .hasSize(2)
                .extracting(message -> message.contentJson())
                .anySatisfy(content -> assertThat(content)
                        .contains("start the next task"))
                .anySatisfy(content -> assertThat(content)
                        .contains("failed before this turn could start")
                        .contains("watched clone unavailable"));
        assertThat(count(jdbc, "thread_turn")).isZero();
        assertThat(projection.cancelableTicketIds("trunk-1")).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM threads WHERE id = 'trunk-1'
                """, String.class)).isEqualTo("IDLE");
    }

    @Test
    void pendingRefreshWaitsOutsideCapacityWhileATrunkTurnIsLive()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("wait.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms)
                VALUES ('live-turn', 'trunk-1', 'PLANNING', 'RUNNING',
                    'live-operation', 1, 'CLI', '{}', 1)
                """);
        Path repository = Files.createDirectory(tempDir.resolve("wait-repo"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);

        PlanningBaseTurnRuntime.Receipt receipt = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "waiting", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null,
                        "next", "next"));

        assertThat(new SqlitePlanningBaseTurnStore(jdbc)
                .require(receipt.operationId()).liveThreadTurn()).isTrue();
        assertThat(new SqliteDispatchTicketStore(dataSource)
                .findEligiblePage(NOW.plusSeconds(1), null, 20).tickets())
                .extracting(DispatchTicket::id)
                .doesNotContain(receipt.dispatchTicketId());
        assertThat(count(jdbc, "capacity_lease")).isZero();

        assertThat(new CapacityManager.CapacityRequest(
                receipt.operationId(), CapacityManager.WorkflowSource.V2,
                Set.of(CapacityManager.CapacityLane.LOCAL_GIT),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", null, null),
                true, false, false).trunkControl()).isTrue();
        assertThatThrownBy(() -> new CapacityManager.CapacityRequest(
                "ordinary", CapacityManager.WorkflowSource.V2,
                Set.of(CapacityManager.CapacityLane.LOCAL_GIT),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", null, null),
                false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task writer or Trunk control");
    }

    @Test
    void archivedTrunkSupersedesASuccessfulRefreshWithoutKeepingItsSnapshot()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("archived.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        Path repository = Files.createDirectory(tempDir.resolve("archived-repo"));
        Path planning = Files.createDirectory(tempDir.resolve("archived-planning"));
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = manager(dataSource, trunkStore);
        PlanningBaseTurnRuntime runtime = runtime(
                jdbc, manager, trunkStore, repository);
        PlanningBaseTurnRuntime.Receipt request = runtime.request(
                new PlanningBaseTurnRuntime.Request(
                        "archived-request", "user", "trunk-1", "workspace-1",
                        "TRUNK_CONVERSATION",
                        AgentTurnProviderSession.Transport.CLI,
                        "codex", null, "gpt-5.6", null, null,
                        "next", "next"));
        manager.archive(new TrunkManager.Command(
                "archive", "user", "trunk-1", 1));

        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, request.operationId(), 1,
                null, null, null);
        PlanningBaseRefreshOperationHandler.Snapshot snapshot =
                new PlanningBaseRefreshOperationHandler.Snapshot(
                        1, request.operationId(), "trunk-1",
                        repository.toString(), planning.toString(),
                        "refs/remotes/origin/main", "abcdef0123456789",
                        NOW.toEpochMilli());
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                new ObjectMapper().writeValueAsString(snapshot), "{}", null);
        markResultPending(jdbc, request.dispatchTicketId(), raw);
        DispatchTicket.DeliveryReceipt delivered = runtime.deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TRUNK, "trunk-1",
                        PlanningBaseRefreshOperationHandler.CALLBACK_ROUTE),
                fence, raw);

        assertThat(delivered.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(jdbc.queryForMap("""
                SELECT status, result_worktree_path, result_base_sha
                FROM planning_base_refresh_operation
                WHERE operation_id = ?
                """, request.operationId()))
                .containsEntry("status", "SUPERSEDED")
                .containsEntry("result_worktree_path", null)
                .containsEntry("result_base_sha", null);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM threads WHERE id = 'trunk-1'
                """, String.class)).isEqualTo("ARCHIVED");
    }

    private PlanningBaseTurnRuntime runtime(
            JdbcTemplate jdbc,
            TrunkManager manager,
            V2TrunkStore trunkStore,
            Path repository)
    {
        WorkspaceRepositoryResolver repositories =
                mock(WorkspaceRepositoryResolver.class);
        when(repositories.resolve("workspace-1")).thenReturn(
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "widget", "acme/widget", "main"));
        WatchedRepoStore watched = mock(WatchedRepoStore.class);
        when(watched.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0,
                        repository.toString(), null, null)));
        ObjectMapper json = new ObjectMapper();
        return new PlanningBaseTurnRuntime(
                manager, trunkStore, new SqlitePlanningBaseTurnStore(jdbc),
                new ThreadTurnHandoff(
                        manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123),
                repositories, watched, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TrunkManager manager(
            SQLiteDataSource dataSource, V2TrunkStore store)
    {
        return new TrunkManager(
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(dataSource)),
                store);
    }

    private static void markResultPending(
            JdbcTemplate jdbc,
            String ticketId,
            DispatchTicket.DispatchResult result)
    {
        DispatchTicket.OperationFence fence = result.fence();
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = ?, pending_result_payload = ?,
                    pending_result_evidence = ?, pending_result_error = ?,
                    pending_result_task_epoch = ?, pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?, pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ?
                """, result.outcome().name(), result.payloadJson(),
                result.evidenceJson(), result.error(), fence.taskEpoch(),
                fence.stageId(), fence.stageGeneration(), fence.operationId(),
                fence.attempt(), fence.expectedCodeFingerprint(),
                fence.expectedHeadSha(), fence.expectedBaseSha(), ticketId);
    }

    private static void markDelivered(
            JdbcTemplate jdbc,
            String ticketId,
            DispatchTicket.DeliveryReceipt receipt)
    {
        String outcome = jdbc.queryForObject("""
                SELECT pending_result_outcome FROM dispatch_ticket WHERE id = ?
                """, String.class, ticketId);
        String status = switch (outcome) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "CANCELED" -> "CANCELED";
            default -> "FAILED";
        };
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = ?,
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
                    next_attempt_at_ms = NULL,
                    delivery_acceptance = ?, delivery_evidence = ?,
                    completed_at_ms = ?
                WHERE id = ?
                """, status, receipt.acceptance().name(), receipt.evidenceJson(),
                NOW.plusSeconds(1).toEpochMilli(), ticketId);
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("260").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void seedTrunk(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'workspace-1', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'trunk-1', 'IDLE',
                    'gpt-5.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'IDLE')
                """);
    }

    private static int count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
