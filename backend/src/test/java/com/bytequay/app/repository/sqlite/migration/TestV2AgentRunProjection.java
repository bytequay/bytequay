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

import com.bytequay.app.developmentflow.compatibility.V2AgentRunProjection;
import com.bytequay.app.domain.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class TestV2AgentRunProjection
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private V2AgentRunProjection projection;

    @BeforeEach
    void setup()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v2-runs.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        DevelopmentFlowRemoteProtocolFixture.migrate(url, "228");
        try (Connection connection =
                DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(
                    connection);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(
                    connection, 1);
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url, "264");
        try (Connection connection =
                DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(
                    connection, 1);
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        projection = new V2AgentRunProjection(jdbc, new ObjectMapper());
    }

    @Test
    void projectsTypedThreadTaskAndStageTurnsWithoutAgentRunRows()
    {
        insertThreadTurn();
        insertTaskTurn();
        insertStageTurn();
        insertExecution();

        assertThat(projection.listByWorkspace("workspace-1"))
                .filteredOn(run -> run.id().startsWith("v2-ticket:visible-"))
                .extracting(AgentRun::kind)
                .containsExactlyInAnyOrder(
                        AgentRun.KIND_PLAN,
                        AgentRun.KIND_REVIEW,
                        AgentRun.KIND_DEV);
        assertThat(projection.listByTask("task-1"))
                .filteredOn(run -> run.id().startsWith("v2-ticket:visible-"))
                .extracting(AgentRun::id)
                .containsExactlyInAnyOrder(
                        "v2-ticket:visible-task-ticket",
                        "v2-ticket:visible-stage-ticket");

        AgentRun stage = projection.findById(
                "v2-ticket:visible-stage-ticket").orElseThrow();
        assertThat(stage.workspaceId()).isEqualTo("workspace-1");
        assertThat(stage.threadId()).isEqualTo("trunk-1");
        assertThat(stage.taskId()).isEqualTo("task-1");
        assertThat(stage.stageId()).isEqualTo("remote-stage-1");
        assertThat(stage.source()).isEqualTo(AgentRun.SOURCE_REMOTE);
        assertThat(stage.status()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(stage.provider()).isEqualTo("openai");
        assertThat(stage.costUsdMilli()).isEqualTo(17);
        assertThat(stage.tokensIn()).isEqualTo(23);
        assertThat(stage.tokensOut()).isEqualTo(29);
        assertThat(stage.iterations()).isEqualTo(1);
        assertThat(stage.stepCursor()).isEqualTo(1);
        assertThat(stage.launchInput()).isEqualTo("Address the feedback");
        assertThat(stage.finishedAt()).isNull();
        assertThat(projection.findById("legacy-run")).isEmpty();
    }

    private void insertThreadTurn()
    {
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms)
                VALUES ('visible-thread-turn', 'trunk-1', 'TRUNK_PLANNING',
                    'QUEUED', 'visible-thread-operation', 1, 'API',
                    '{"prompt":"Plan the work"}', 80)
                """);
        insertTicket(
                "visible-thread-ticket", "visible-thread-operation",
                "EXECUTE_THREAD_TURN", "THREAD_TURN", "visible-thread-turn",
                "THREAD_TURN_RESULT", true, null, null);
    }

    private void insertTaskTurn()
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input,
                    requested_at_ms, finished_at_ms)
                VALUES ('visible-task-turn', 'task-1',
                    'DEVELOPMENT_BRAIN_REVIEW', 'SUCCEEDED',
                    'visible-task-operation', 1, 1, 'API',
                    '{"prompt":"Review the implementation"}', 81, 82)
                """);
        insertTicket(
                "visible-task-ticket", "visible-task-operation",
                "EXECUTE_TASK_TURN", "TASK_TURN", "visible-task-turn",
                "TASK_TURN_RESULT", false, "task-1", null);
    }

    private void insertStageTurn()
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch, delivery_lane,
                    launch_input, requested_at_ms, started_at_ms)
                VALUES ('visible-stage-turn', 'remote-stage-1', 1,
                    'USER_STEERING', 'RUNNING',
                    'visible-stage-operation', 1, 1, 'API',
                    '{"prompt":"Address the feedback"}', 83, 84)
                """);
        insertTicket(
                "visible-stage-ticket", "visible-stage-operation",
                "EXECUTE_STAGE_TURN", "STAGE_TURN", "visible-stage-turn",
                "STAGE_TURN_RESULT", false, "task-1", "remote-stage-1");
    }

    private void insertTicket(
            String id,
            String operationId,
            String operationKind,
            String ownerKind,
            String ownerId,
            String callback,
            boolean trunkControl,
            String taskId,
            String stageId)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, status, created_at_ms)
                VALUES (?, ?, ?, 'AGENT_TURN', ?, ?, ?, 2, ?, ?, ?,
                    'workspace-1', 'trunk-1', ?, ?, ?, ?, 1,
                    'REQUESTED', 80)
                """, id, operationId, operationKind, ownerKind, ownerId,
                callback, trunkControl ? 1 : 0, taskId == null ? 0 : 1,
                stageId == null ? 0 : 1, taskId,
                taskId == null ? null : 1, stageId,
                stageId == null ? null : 1);
    }

    private void insertExecution()
    {
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider, status,
                    started_at_ms, heartbeat_at_ms, cost_usd_milli,
                    tokens_in, tokens_out)
                VALUES ('visible-execution', 'visible-stage-ticket', 1,
                    'openai', 'RUNNING', 84, 85, 17, 23, 29)
                """);
        jdbc.update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES ('visible-execution', 0,
                    '{"event":"text_delta","chunk":"working"}', 85)
                """);
    }
}
