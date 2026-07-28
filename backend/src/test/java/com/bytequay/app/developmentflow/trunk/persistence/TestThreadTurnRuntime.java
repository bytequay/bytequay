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

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.ThreadTurnOperationHandler;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.persistence.SqliteThreadTurnOperationStore;
import com.bytequay.app.developmentflow.trunk.PlanningBaseTurnRuntime;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.developmentflow.trunk.ThreadTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.developmentflow.trunk.V2ThreadControlService;
import com.bytequay.app.developmentflow.trunk.V2TrunkPurge;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.COMMAND_ID_CONFLICT;
import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestThreadTurnRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void requestDeliverCancelAndRestartKeepOneExactConversation()
            throws Exception
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("thread-turn.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        ObjectMapper json = new ObjectMapper();
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TrunkManager manager = new TrunkManager(commands, new V2TrunkStore(jdbc));
        ThreadTurnHandoff firstHandoff = new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123);
        ThreadTurnHandoff.Request firstRequest = request(
                "request-1", 0, "plan the first task", "compiled first");

        CommandResult<TrunkManager.ThreadTurnRequestReceipt> first =
                firstHandoff.request(firstRequest);
        assertThat(first.state().state()).isEqualTo(
                new TrunkManager.State("trunk-1", TrunkLifecycle.ACTIVE, 1));
        assertThat(firstHandoff.request(firstRequest).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThat(new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC),
                53123).request(firstRequest).disposition())
                .isEqualTo(CommandResult.Disposition.DUPLICATE);
        assertThatThrownBy(() -> firstHandoff.request(request(
                "request-1", 0, "different", "compiled different")))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(COMMAND_ID_CONFLICT));
        assertThatThrownBy(() -> firstHandoff.request(request(
                "stale", 0, "stale", "compiled stale")))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(STALE_VERSION));

        ThreadTurnHandoff secondHandoff = new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                53123);
        CommandResult<TrunkManager.ThreadTurnRequestReceipt> second =
                secondHandoff.request(request(
                        "request-2", 1, "plan another task", "compiled second"));
        assertThat(second.state().state().version()).isEqualTo(2);

        assertThat(jdbc.queryForMap("""
                SELECT operation_kind, async_family, owner_kind, callback_route,
                       lane_mask, trunk_control, exclusive_task, writer_required,
                       task_id, stage_id
                FROM dispatch_ticket WHERE id = ?
                """, first.state().ticketId()))
                .containsEntry("operation_kind", "EXECUTE_THREAD_TURN")
                .containsEntry("async_family", "AGENT_TURN")
                .containsEntry("owner_kind", "THREAD_TURN")
                .containsEntry("callback_route", "THREAD_TURN_RESULT")
                .containsEntry("lane_mask", 1)
                .containsEntry("trunk_control", 1)
                .containsEntry("exclusive_task", 0)
                .containsEntry("writer_required", 0)
                .containsEntry("task_id", null)
                .containsEntry("stage_id", null);
        assertThat(new SqliteDispatchTicketStore(dataSource)
                .findEligiblePage(NOW.plusSeconds(2), null, 20)
                .tickets())
                .extracting(ticket -> ticket.envelope().owner().kind())
                .containsExactly(DispatchTicket.OwnerKind.THREAD_TURN);
        SqliteThreadTurnOperationStore operationStore =
                new SqliteThreadTurnOperationStore(jdbc);
        assertThat(operationStore.tryStart(
                first.state().turnId(), first.state().operationId(),
                NOW.plusSeconds(2)))
                .isEqualTo(ThreadTurnOperationHandler.StartDisposition.STARTED);
        assertThat(operationStore.tryStart(
                second.state().turnId(), second.state().operationId(),
                NOW.plusSeconds(2)))
                .isEqualTo(
                        ThreadTurnOperationHandler.StartDisposition.OTHER_TURN_RUNNING);
        assertThat(operationStore.findMcpTrunk(
                first.state().turnId(), first.state().operationId(),
                NOW.plusSeconds(2))).isEmpty();

        ThreadTurnResultDeliveryPort delivery = new ThreadTurnResultDeliveryPort(
                manager, new AgentTurnOwnerResultCodec(json), json,
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        DispatchTicket.DispatchResult successful = success(
                json, first.state().turnId(), first.state().operationId(),
                "Here is the proposed task");

        DispatchTicket.DeliveryReceipt accepted = delivery.deliver(
                owner(first.state()), successful.fence(), successful);
        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state || ':' || aggregate_version
                FROM threads WHERE id = 'trunk-1'
                """, String.class)).isEqualTo("ACTIVE:3");

        TrunkManager restartedManager = new TrunkManager(
                commands, new V2TrunkStore(new JdbcTemplate(dataSource)));
        ThreadTurnResultDeliveryPort restartedDelivery =
                new ThreadTurnResultDeliveryPort(
                        restartedManager, new AgentTurnOwnerResultCodec(json), json,
                        Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        assertThat(restartedDelivery.deliver(
                owner(first.state()), successful.fence(), successful).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(count(jdbc, "trunk_thread_turn_result_receipt")).isOne();
        assertThat(count(jdbc, "thread_message")).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT aggregate_version FROM threads WHERE id = 'trunk-1'
                """, Integer.class)).isEqualTo(3);

        DispatchTicket.OperationFence secondFence = fence(
                second.state().operationId());
        DispatchTicket.DeliveryReceipt canceled = restartedDelivery.deliver(
                owner(second.state()), secondFence,
                DispatchTicket.DispatchResult.canceled(secondFence));
        assertThat(canceled.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM thread_turn WHERE id = ?
                """, String.class, second.state().turnId())).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state || ':' || aggregate_version
                FROM threads WHERE id = 'trunk-1'
                """, String.class)).isEqualTo("IDLE:4");

        ThreadTurnProjection projection = new ThreadTurnProjection(jdbc, json);
        assertThat(projection.history("trunk-1"))
                .extracting(message -> message.role() + ":" + message.scope())
                .containsExactly(
                        "user:TRUNK", "user:TRUNK", "assistant:TRUNK");
        assertThat(projection.turns("trunk-1", 10))
                .extracting(ThreadTurnProjection.TurnView::status)
                .containsExactly("CANCELED", "SUCCEEDED");
        assertThat(count(jdbc, "trunk_transition")).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check",
                Integer.class)).isZero();
    }

    @Test
    void migrationRejectsOrdinaryCapacityForAThreadTurnAndRestarts()
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("guards.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms)
                VALUES ('bad-turn', 'trunk-1', 'PLANNING', 'REQUESTED',
                    'bad-operation', 1, 'CLI', '{}', 2)
                """);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, attempt, status,
                    next_attempt_at_ms, created_at_ms)
                VALUES ('bad-ticket', 'bad-operation', 'EXECUTE_THREAD_TURN',
                    'AGENT_TURN', 'THREAD_TURN', 'bad-turn',
                    'THREAD_TURN_RESULT', 1, 0, 0, 0,
                    'workspace-1', 'trunk-1', 1, 'REQUESTED', 2, 2)
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("reserved Trunk-control");

        Flyway.configure().dataSource(dataSource).target("247").load().migrate();
        assertThat(count(jdbc, "trunk_thread_turn_request_receipt")).isZero();
    }

    @Test
    void v2SseReadsOnlyCommittedLogsForItsExactTrunk()
            throws Exception
    {
        SQLiteDataSource dataSource = latestDatabase(
                tempDir.resolve("thread-turn-sse.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-2', 'CLI_AGENT', 'codex', 'trunk-2', 'IDLE',
                    'gpt-5.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'IDLE')
                """);
        ObjectMapper json = new ObjectMapper();
        V2TrunkStore trunkStore = new V2TrunkStore(jdbc);
        TrunkManager manager = new TrunkManager(
                new TaskCommandExecutor(new DataSourceTransactionManager(dataSource)),
                trunkStore);
        ThreadTurnHandoff handoff = new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123);
        TrunkManager.ThreadTurnRequestReceipt target = handoff.request(
                request("request-target", "trunk-1", 0,
                        "target prompt", "compiled target")).state();
        TrunkManager.ThreadTurnRequestReceipt sibling = handoff.request(
                request("request-sibling", "trunk-2", 0,
                        "sibling prompt", "compiled sibling")).state();
        ThreadTurnProjection projection = new ThreadTurnProjection(jdbc, json);
        V2ThreadControlService service = new V2ThreadControlService(
                mock(PlanningBaseTurnRuntime.class),
                projection, mock(DispatchTicketControl.class), manager,
                mock(V2TrunkPurge.class), mock(ThreadEngineOverrides.class),
                mock(RoleRegistry.class), mock(SessionKnowledgeProvider.class));
        List<String> chunks = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        Runnable unsubscribe = service.subscribe("trunk-1", event -> {
            if (event instanceof StreamEvent.AssistantTextDelta delta) {
                chunks.add(delta.textChunk());
                if ("target".equals(delta.textChunk())) {
                    received.countDown();
                }
            }
        });
        try {
            startAndLog(jdbc, sibling, "sibling", NOW.plusSeconds(1));
            startAndLog(jdbc, target, "target", NOW.plusSeconds(2));
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(chunks).containsExactly("target");
            assertThat(projection.activeTurns(10))
                    .extracting(ThreadTurnProjection.TurnView::trunkId)
                    .containsExactlyInAnyOrder("trunk-1", "trunk-2");
            assertThat(projection.turnEvents("trunk-1"))
                    .isEqualTo(projection.turnEvents("trunk-1"))
                    .extracting(event -> event.id())
                    .doesNotHaveDuplicates();
        }
        finally {
            unsubscribe.run();
        }
    }

    private static ThreadTurnHandoff.Request request(
            String commandId, long expectedVersion,
            String userMessage, String compiledPrompt)
    {
        return request(commandId, "trunk-1", expectedVersion,
                userMessage, compiledPrompt);
    }

    private static ThreadTurnHandoff.Request request(
            String commandId, String trunkId, long expectedVersion,
            String userMessage, String compiledPrompt)
    {
        return new ThreadTurnHandoff.Request(
                commandId, "user", trunkId, "workspace-1",
                expectedVersion, "PLANNING",
                AgentTurnProviderSession.Transport.CLI, "codex", null,
                "gpt-5.6", "high", Path.of("/tmp"), "trunk role",
                userMessage, compiledPrompt);
    }

    private static void startAndLog(
            JdbcTemplate jdbc,
            TrunkManager.ThreadTurnRequestReceipt receipt,
            String chunk,
            Instant startedAt)
    {
        String leaseId = "lease-" + receipt.turnId();
        String executionId = "execution-" + receipt.turnId();
        long expiresAt = startedAt.plusSeconds(60).toEpochMilli();
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, holder, acquired_at_ms,
                    heartbeat_at_ms, expires_at_ms)
                VALUES (?, ?, ?, 'V2', 1, 1, 0, 0, 'workspace-1', ?,
                    'sse-test', ?, ?, ?)
                """, leaseId, receipt.ticketId(), receipt.operationId(),
                receipt.state().id(), startedAt.toEpochMilli(),
                startedAt.toEpochMilli(), expiresAt);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'CLAIMED',
                    claim_purpose = 'EXECUTE', claim_owner = 'sse-test',
                    capacity_lease_id = ?, claim_expires_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, leaseId, expiresAt, receipt.ticketId());
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RUNNING',
                    infrastructure_attempts = 1, started_at_ms = ?
                WHERE id = ? AND status = 'CLAIMED'
                """, startedAt.toEpochMilli(), receipt.ticketId());
        new SqliteThreadTurnOperationStore(jdbc).tryStart(
                receipt.turnId(), receipt.operationId(), startedAt);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms)
                VALUES (?, ?, 1, 'codex', 'RUNNING', ?)
                """, executionId, receipt.ticketId(), startedAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES (?, 0, ?, ?)
                """, executionId,
                "{\"event\":\"text_delta\",\"blockIndex\":0,\"chunk\":\""
                        + chunk + "\"}",
                startedAt.toEpochMilli());
    }

    private static DispatchTicket.OwnerReference owner(
            TrunkManager.ThreadTurnRequestReceipt receipt)
    {
        return new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.THREAD_TURN, receipt.turnId(),
                "THREAD_TURN_RESULT");
    }

    private static DispatchTicket.DispatchResult success(
            ObjectMapper json, String turnId, String operationId, String finalText)
            throws Exception
    {
        DispatchTicket.OperationFence fence = fence(operationId);
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, turnId, DispatchTicket.OwnerKind.THREAD_TURN,
                        "PLANNING", AgentTurnProviderSession.Transport.CLI,
                        "codex", "session-1", finalText,
                        1, 2, 0, 123L,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        null);
        return new DispatchTicket.DispatchResult(
                fence, DispatchTicket.Outcome.SUCCEEDED,
                json.writeValueAsString(payload), "{}", null);
    }

    private static DispatchTicket.OperationFence fence(String operationId)
    {
        return new DispatchTicket.OperationFence(
                null, null, null, operationId, 1,
                null, null, null);
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("247").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static SQLiteDataSource latestDatabase(Path file)
    {
        String url = "jdbc:sqlite:" + file
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("269").load().migrate();
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
