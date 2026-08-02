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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.trunk.ThreadTurnHandoff;
import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.developmentflow.trunk.ThreadTurnResultDeliveryPort;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.domain.TrunkTraceEvent;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.bytequay.app.testing.V2TaskSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SqliteTestPools.class)
class TestThreadTurnTraceProjection
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void reloadsStableExactTrunkTraceWithoutConversationOrSiblingLeakage()
            throws Exception
    {
        DataSource dataSource = database(tempDir.resolve("traces.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedOwners(jdbc);
        Flyway.configure().dataSource(dataSource).load().migrate();
        ObjectMapper json = new ObjectMapper();
        TrunkManager manager = new TrunkManager(
                new TaskCommandExecutor(new DataSourceTransactionManager(dataSource)),
                new V2TrunkStore(jdbc));
        ThreadTurnHandoff handoff = new ThreadTurnHandoff(
                manager, json, Clock.fixed(NOW, ZoneOffset.UTC), 53123);
        long trunkVersion = jdbc.queryForObject("""
                SELECT aggregate_version FROM threads WHERE id = 'trunk-1'
                """, Long.class);
        TrunkManager.ThreadTurnRequestReceipt target = handoff.request(
                request("target-command", trunkVersion, "inspect target")).state();
        TrunkManager.ThreadTurnRequestReceipt terminal = handoff.request(
                request("terminal-command", target.state().version(), "fail once")).state();

        insertExecution(jdbc, target.ticketId(), "target-execution", "codex", 10);
        insertTargetLogs(jdbc, json);
        insertExecution(jdbc, terminal.ticketId(), "terminal-execution", "codex", 20);
        insertProviderLog(jdbc, json, "terminal-execution", 0,
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"terminal boom\"}}", 20);
        deliverFailed(manager, json, terminal);

        insertSiblingTurns(jdbc);
        insertExecution(jdbc, "task-ticket", "task-execution", "codex", 30);
        insertProviderLog(jdbc, json, "task-execution", 0,
                reasoning("task sibling must stay hidden"), 30);

        ThreadTurnProjection projection = new ThreadTurnProjection(jdbc, json);
        String targetMessageId = requestMessageId(jdbc, target.turnId());
        String terminalMessageId = requestMessageId(jdbc, terminal.turnId());
        List<Long> conversationSeqs = projection.history("trunk-1").stream()
                .map(message -> message.seq())
                .toList();

        List<TrunkTraceEvent> first = projection.traceEvents(
                "trunk-1", List.of(targetMessageId, terminalMessageId));
        List<TrunkTraceEvent> reloaded = projection.traceEvents(
                "trunk-1", List.of(targetMessageId, terminalMessageId));

        assertThat(first).isEqualTo(reloaded);
        assertThat(first)
                .extracting(TrunkTraceEvent::id)
                .containsExactly(
                        "trace:target-execution:0:0",
                        "trace:target-execution:1:0",
                        "trace:target-execution:2:0",
                        "trace:target-execution:3:0");
        assertThat(first)
                .extracting(TrunkTraceEvent::type)
                .containsExactly("tool_call", "tool_result", "thinking", "error");
        assertThat(first)
                .extracting(TrunkTraceEvent::requestMessageId)
                .containsOnly(targetMessageId);
        assertThat(first)
                .extracting(TrunkTraceEvent::executionId)
                .doesNotContain("terminal-execution", "task-execution");
        assertThat(projection.traceEvents(
                "trunk-1", List.of("not-a-loaded-request"))).isEmpty();
        assertThat(projection.history("trunk-1").stream()
                .map(message -> message.seq())
                .toList()).isEqualTo(conversationSeqs);
        assertThat(projection.history("trunk-1").stream()
                .filter(message -> "error".equals(message.type()))
                .map(message -> message.contentJson())
                .toList()).containsExactly("{\"text\":\"terminal boom\"}");
    }

    private static void insertTargetLogs(JdbcTemplate jdbc, ObjectMapper json)
            throws Exception
    {
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('target-execution', 0,
                    '{"event":"tool_started","callId":"call-1",\
                      "tool":"Bash","input":"{\\"command\\":\\"npm test\\"}"}', 10)
                """);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('target-execution', 1,
                    '{"event":"tool_finished","callId":"call-1",\
                      "result":"{\\"output\\":\\"ok\\",\\"exitCode\\":0}",\
                      "isError":false}', 11)
                """);
        insertProviderLog(jdbc, json, "target-execution", 2,
                reasoning("checked the durable state"), 12);
        insertProviderLog(jdbc, json, "target-execution", 3,
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"retryable provider error\"}}", 13);
    }

    private static String reasoning(String text)
    {
        return "{\"type\":\"item.completed\",\"item\":{"
                + "\"type\":\"reasoning\",\"text\":\"" + text + "\"}}";
    }

    private static void insertProviderLog(
            JdbcTemplate jdbc,
            ObjectMapper json,
            String executionId,
            long seq,
            String line,
            long createdAt)
            throws Exception
    {
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES (?, ?, ?, ?)
                """, executionId, seq,
                json.writeValueAsString(Map.of(
                        "stream", "provider", "line", line)),
                createdAt);
    }

    private static void insertSiblingTurns(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('task-turn', 'task-1', 'BRAIN_REVIEW', 'REQUESTED',
                    'task-operation', 1, 1, 'API', '{}', 30)
                """);
        insertSiblingTicket(jdbc, "task-ticket", "task-operation",
                "EXECUTE_TASK_TURN", "TASK_TURN", "task-turn",
                "TASK_TURN_RESULT", null);
    }

    private static void insertSiblingTicket(
            JdbcTemplate jdbc,
            String ticketId,
            String operationId,
            String operationKind,
            String ownerKind,
            String ownerId,
            String callback,
            String stageId)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, status, created_at_ms)
                VALUES (?, ?, ?, 'AGENT_TURN', ?, ?, ?, 2, 0, 1, ?,
                    'workspace-1', 'trunk-1', 'task-1', 1, ?, ?, 1,
                    'REQUESTED', 30)
                """, ticketId, operationId, operationKind, ownerKind, ownerId,
                callback, stageId == null ? 0 : 1, stageId,
                stageId == null ? null : 1);
    }

    private static void insertExecution(
            JdbcTemplate jdbc,
            String ticketId,
            String executionId,
            String provider,
            long startedAt)
    {
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms)
                VALUES (?, ?, 1, ?, 'RUNNING', ?)
                """, executionId, ticketId, provider, startedAt);
    }

    private static void deliverFailed(
            TrunkManager manager,
            ObjectMapper json,
            TrunkManager.ThreadTurnRequestReceipt receipt)
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                null, null, null, receipt.operationId(), 1,
                null, null, null);
        new ThreadTurnResultDeliveryPort(
                manager, new AgentTurnOwnerResultCodec(json), json,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                .deliver(
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.THREAD_TURN,
                                receipt.turnId(), "THREAD_TURN_RESULT"),
                        fence,
                        new DispatchTicket.DispatchResult(
                                fence, DispatchTicket.Outcome.FAILED,
                                null, "{}", "terminal boom"));
    }

    private static String requestMessageId(JdbcTemplate jdbc, String turnId)
    {
        return jdbc.queryForObject("""
                SELECT id FROM thread_message WHERE turn_id = ? AND seq = 1
                """, String.class, turnId);
    }

    private static ThreadTurnHandoff.Request request(
            String commandId, long expectedVersion, String prompt)
    {
        return new ThreadTurnHandoff.Request(
                commandId, "user", "trunk-1", "workspace-1",
                expectedVersion, "PLANNING",
                AgentTurnProviderSession.Transport.CLI, "codex", null,
                "gpt-5.6", "high", Path.of("/tmp"), "trunk role",
                prompt, prompt, List.of(), null, null);
    }

    private static DataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static void seedOwners(JdbcTemplate jdbc)
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
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'test', 1)
                """);
        V2TaskSeed.prepareWorkspaces(jdbc);
        V2TaskSeed.insertAuthorized(jdbc, "assignment-1", transaction ->
                transaction.update("""
                        INSERT INTO task_assignment(
                            id, trunk_id, kind, planning_base_sha, plan_seed,
                            prompt, created_by, created_at_ms,
                            creation_authorization_id)
                        VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                            'base', 'seed', 'prompt', 'test', 1,
                            'authorization-assignment-1')
                        """));
        V2TaskSeed.insertCreated(jdbc, "task-1", transaction ->
                transaction.update("""
                        INSERT INTO tasks(
                            id, thread_id, seq, status, phase, created_at_ms,
                            workflow_version, lifecycle_state, assignment_id,
                            policy_revision_id, creation_receipt_id, name,
                            task_type, opening_prompt, origin)
                        VALUES ('task-1', 'trunk-1', 1, 'IDLE',
                            'IMPLEMENTING', 1, 'V2', 'PROVISIONING',
                            'assignment-1', 'policy-1',
                            'creation-receipt-task-1', 'Test task assignment-1',
                            'DEVELOP', 'prompt', 'user')
                        """));
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version,
                    checkpoint, opened_at_ms)
                VALUES ('stage-1', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 2)
                """);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'stage-1', 1)
                """);
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
    }
}
