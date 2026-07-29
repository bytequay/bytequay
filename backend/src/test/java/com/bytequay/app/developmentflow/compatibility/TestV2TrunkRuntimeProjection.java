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
package com.bytequay.app.developmentflow.compatibility;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestV2TrunkRuntimeProjection
{
    @TempDir
    private Path tempDir;

    @Test
    void appliesExactTypedTrunkStatusPrecedence()
    {
        JdbcTemplate jdbc = database();
        seed(jdbc, "v2", "V2", "ACTIVE", "ERRORED");
        seed(jdbc, "archived", "V2", "ARCHIVED", "RUNNING");
        seed(jdbc, "legacy", "LEGACY", null, "ERRORED");
        V2TrunkRuntimeProjection projection =
                new V2TrunkRuntimeProjection(jdbc);

        Thread stored = thread("v2", ThreadStatus.ERRORED);
        Thread idle = projection.project(stored);
        assertThat(idle.status()).isEqualTo(ThreadStatus.IDLE);
        assertThat(idle.errorMessage()).isNull();
        assertThat(idle.endedAt()).isNull();
        assertThat(idle.agentSessionId()).isNull();
        assertThat(projection.project(thread("legacy", ThreadStatus.ERRORED)))
                .isEqualTo(thread("legacy", ThreadStatus.ERRORED));

        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms)
                VALUES ('turn-1', 'v2', 'CONVERSATION', 'REQUESTED',
                    'operation-1', 1, 'CLI', '{}', 2)
                """);

        assertThat(projection.project(stored).status())
                .isEqualTo(ThreadStatus.PENDING);
        assertThat(projection.listIds(ThreadStatus.PENDING, null, 10))
                .containsExactly("v2");

        jdbc.update("""
                UPDATE thread_turn
                SET status = 'RUNNING', started_at_ms = 3
                WHERE id = 'turn-1'
                """);
        assertThat(projection.project(stored).status())
                .isEqualTo(ThreadStatus.RUNNING);

        jdbc.update("""
                INSERT INTO thread_question(
                    id, turn_id, call_id, prompt, state, created_at_ms)
                VALUES ('question-1', 'turn-1', 'call-1', 'Continue?', 'OPEN', 4)
                """);
        assertThat(projection.project(stored).status())
                .isEqualTo(ThreadStatus.NEEDS_ATTENTION);

        jdbc.update("""
                UPDATE thread_question
                SET state = 'ANSWERED', answer = 'yes', answer_revision = 1,
                    answered_at_ms = 5, answer_free_form = 'yes',
                    answer_actor = 'user', continuation_state = 'READY'
                WHERE id = 'question-1'
                """);
        jdbc.update("""
                UPDATE thread_turn
                SET status = 'FAILED', finished_at_ms = 6,
                    error_message = 'provider failed'
                WHERE id = 'turn-1'
                """);
        assertThat(projection.project(stored).status())
                .isEqualTo(ThreadStatus.PENDING);

        jdbc.update("""
                UPDATE thread_question
                SET continuation_state = 'SUPERSEDED'
                WHERE id = 'question-1'
                """);
        jdbc.update("""
                INSERT INTO permission_request(
                    id, call_id, turn_kind, turn_id, operation_id, capability,
                    parameters_json, parameters_digest, policy_snapshot,
                    state, requested_at_ms)
                VALUES ('permission-1', 'permission-call-1', 'THREAD',
                    'turn-1', 'operation-1', 'process.execute', '{}',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    '{}', 'OPEN', 7)
                """);
        assertThat(projection.project(stored).status())
                .isEqualTo(ThreadStatus.NEEDS_ATTENTION);
        jdbc.update("DELETE FROM permission_request WHERE id = 'permission-1'");

        assertThat(projection.project(stored).status())
                .isEqualTo(ThreadStatus.IDLE);
        assertThat(projection.project(thread("archived", ThreadStatus.RUNNING))
                .status()).isEqualTo(ThreadStatus.ARCHIVED);
        assertThat(projection.listIds(ThreadStatus.IDLE, "workspace-1", 10))
                .containsExactly("v2");
        assertThat(projection.listIds(ThreadStatus.ERRORED, null, 10)).isEmpty();
        assertThat(projection.count(null)).isEqualTo(2);
        assertThat(projection.count("workspace-1")).isEqualTo(2);
    }

    @Test
    void addsEveryTypedExecutionAttemptToTheStoredLifetimeBaseline()
    {
        JdbcTemplate jdbc = database();
        seed(jdbc, "v2", "V2", "ACTIVE", "ERRORED");
        seed(jdbc, "sibling", "V2", "ACTIVE", "IDLE");
        jdbc.update("""
                UPDATE threads
                SET cost_usd_milli = 5, tokens_in = 7, tokens_out = 11
                WHERE id = 'v2'
                """);
        insertTrunkTicket(jdbc, "v2", "ticket-v2", "operation-v2", 20);
        insertExecution(jdbc, "execution-v2-1", "ticket-v2", 1, 13, 17, 19);
        insertExecution(jdbc, "execution-v2-2", "ticket-v2", 2, 23, 29, 31);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('execution-v2-2', 0, '{}', 50)
                """);
        insertTrunkTicket(
                jdbc, "sibling", "ticket-sibling", "operation-sibling", 60);
        insertExecution(
                jdbc, "execution-sibling", "ticket-sibling", 1, 100, 100, 100);
        V2TrunkRuntimeProjection projection =
                new V2TrunkRuntimeProjection(jdbc);
        Thread stored = new Thread(
                "v2", ThreadKind.CLI_AGENT, "codex", "stale-session", "v2",
                ThreadStatus.ERRORED, "model", 5, 7, 11,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, "stale error",
                ThreadFlow.BUILD, "workspace-1", null, null, 1,
                null, null, null);

        Thread projected = projection.project(stored);

        assertThat(projected.costUsdMilli()).isEqualTo(41);
        assertThat(projected.tokensIn()).isEqualTo(53);
        assertThat(projected.tokensOut()).isEqualTo(61);
        assertThat(projected.updatedAt()).isEqualTo(Instant.ofEpochMilli(50));
        assertThat(projection.listIds(ThreadStatus.IDLE, null, 10))
                .containsExactly("sibling", "v2");
        assertThat(projection.listIdsUpdatedSince(
                null, Instant.ofEpochMilli(40)))
                .containsExactly("sibling", "v2");
        assertThat(projection.listIdsUpdatedSince(
                "workspace-1", Instant.ofEpochMilli(51)))
                .containsExactly("sibling");
    }

    private JdbcTemplate database()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("runtime.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("276").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        return jdbc;
    }

    private static void seed(
            JdbcTemplate jdbc, String id, String turnVersion,
            String lifecycle, String status)
    {
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, agent_session_id, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out, created_at_ms,
                    updated_at_ms, ended_at_ms, error_message, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES (?, 'CLI_AGENT', 'codex', 'stale-session', ?, ?, 'model',
                    0, 0, 0, 1, 1, 1, 'stale error', 'workspace-1', 'build', 1,
                    ?, ?)
                """, id, id, status, turnVersion, lifecycle);
    }

    private static Thread thread(String id, ThreadStatus status)
    {
        return new Thread(
                id, ThreadKind.CLI_AGENT, "codex", "stale-session", id,
                status, "model", 0, 0, 0, Instant.EPOCH, Instant.EPOCH,
                Instant.EPOCH, "stale error", ThreadFlow.BUILD,
                "workspace-1", null, null, 1, null, null, null);
    }

    private static void insertTrunkTicket(
            JdbcTemplate jdbc, String trunkId, String ticketId,
            String operationId, long createdAt)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, attempt, status, created_at_ms)
                VALUES (?, ?, 'TEST_AGENT_TURN', 'AGENT_TURN', 'TRUNK', ?,
                    'TEST_RESULT', 1, 1, 0, 0, 'workspace-1', ?, 1,
                    'REQUESTED', ?)
                """, ticketId, operationId, trunkId, trunkId, createdAt);
    }

    private static void insertExecution(
            JdbcTemplate jdbc, String executionId, String ticketId, int attempt,
            long cost, long tokensIn, long tokensOut)
    {
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider, status,
                    started_at_ms, finished_at_ms, cost_usd_milli,
                    tokens_in, tokens_out)
                VALUES (?, ?, ?, 'codex', 'SUCCEEDED', 20, 30, ?, ?, ?)
                """, executionId, ticketId, attempt, cost, tokensIn, tokensOut);
    }
}
