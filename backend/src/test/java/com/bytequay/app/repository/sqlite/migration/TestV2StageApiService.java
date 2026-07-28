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

import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.developmentflow.compatibility.V2BranchGuardProjection;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2StageApiService;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.domain.StreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TestV2StageApiService
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private DispatchTicketControl tickets;
    private V2StageApiService service;

    @BeforeEach
    void setup()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v2-stage-api.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        DevelopmentFlowRemoteProtocolFixture.migrate(url, "228");
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(connection);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(connection, 2);
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url, "257");
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(connection, 2);
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url, "264");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO task_branch_sync_policy_revision(
                    id, task_id, revision, enabled, schedule, source,
                    attempt_limit, command_id, actor, created_at_ms)
                VALUES ('policy-1', 'task-1', 1, 1, 'nightly',
                    'USER_CONFIGURED', 3, 'configure-guard', 'user', 80)
                """);
        tickets = mock(DispatchTicketControl.class);
        service = new V2StageApiService(
                jdbc, new V2DevelopmentFlowProjection(jdbc),
                new V2BranchGuardProjection(jdbc), tickets,
                new ObjectMapper());
    }

    @Test
    void detailIsComposedOnlyFromTypedStageFacts()
    {
        StageDetailData detail = service.detail("task-1", "remote-stage-1");

        assertThat(detail.task().id()).isEqualTo("task-1");
        assertThat(detail.task().repoFullName()).isEqualTo("acme/widget");
        assertThat(detail.stage().id()).isEqualTo("remote-stage-1");
        assertThat(detail.stage().type()).isEqualTo("REMOTE_DEVELOPMENT_STAGE");
        assertThat(detail.stage().state()).isEqualTo("OPEN");
        assertThat(detail.allStages()).extracting(stage -> stage.id())
                .containsExactly("local-stage-1", "remote-stage-1");
        assertThat(detail.conversationThreadId()).isNull();
        assertThat(detail.liveRuns()).isEmpty();
        assertThat(detail.guard().taskId()).isEqualTo("task-1");
        assertThat(detail.guard().enabled()).isTrue();
        assertThat(detail.guard().schedule()).isEqualTo("nightly");
        assertThat(detail.recovery().ci()).isNull();
        assertThat(detail.recovery().cleanup()).isNull();
    }

    @Test
    void detailProjectsOnlyTheExactCurrentExhaustedCiEpisode()
            throws Exception
    {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            insertCiPolicy(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1",
                    "OPEN", "UNKNOWN");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
        }
        jdbc.update("""
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_limit, delivery_retry_limit,
                    push_limit, opened_at_ms)
                VALUES ('episode-exact', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'TASK_DETERMINISTIC', 'OPEN', 0, 0, 1, 0, 95)
                """);
        jdbc.update("""
                UPDATE ci_repair_episode
                   SET status = 'EXHAUSTED',
                       terminal_ci_evaluation_id = 'ci-evaluation-1-1',
                       completed_at_ms = 96
                 WHERE id = 'episode-exact'
                """);

        StageDetailData detail = service.detail("task-1", "remote-stage-1");

        assertThat(detail.recovery().ci().episodeId())
                .isEqualTo("episode-exact");
        assertThat(detail.recovery().ci().actions()).containsExactly(
                "EXTEND_BUDGET", "CONTINUE_WITH_PER_PUSH_APPROVAL",
                "MANUAL_TAKEOVER", "STOP_AUTOMATION");
        assertThat(service.detail("task-2", "remote-stage-2")
                .recovery().ci()).isNull();
    }

    @Test
    void interruptCancelsOnlyTheExactCurrentStageAgentTicket()
    {
        insertAgentTicket(1, "ticket-one", "turn-one", "operation-one");
        insertAgentTicket(2, "ticket-two", "turn-two", "operation-two");

        service.interrupt("task-1", "remote-stage-1");

        verify(tickets).requestCancel("ticket-one");
        verifyNoMoreInteractions(tickets);
    }

    @Test
    void runDetailReadsOnlyItsExactStageOrTaskTurnMessages()
    {
        insertAgentTicket(1, "ticket-one", "turn-one", "operation-one");
        insertAgentTicket(2, "ticket-two", "turn-two", "operation-two");
        jdbc.update("""
                INSERT INTO stage_message(
                    id, turn_id, seq, role, body, created_at_ms)
                VALUES
                    ('message-one', 'turn-one', 1, 'assistant',
                     'exact stage message', 91),
                    ('message-two', 'turn-two', 1, 'assistant',
                     'sibling stage message', 91)
                """);
        insertTaskAgentTicket();
        jdbc.update("""
                INSERT INTO task_message(
                    id, turn_id, seq, role, body, created_at_ms)
                VALUES ('task-message-one', 'task-turn-one', 1, 'assistant',
                    'exact task brain message', 93)
                """);

        StageDetailData stage = service.runDetail("v2-ticket:ticket-one");
        StageDetailData task = service.runDetail("v2-ticket:task-ticket-one");

        assertThat(stage.conversation()).extracting(row -> row.text())
                .contains("steer", "exact stage message")
                .doesNotContain("sibling stage message", "exact task brain message");
        assertThat(task.conversation()).extracting(row -> row.text())
                .contains("Review the implementation", "exact task brain message")
                .doesNotContain("exact stage message", "sibling stage message");
        assertThat(stage.iterations()).extracting(iteration -> iteration.id())
                .containsExactly("turn-one");
        assertThat(task.iterations()).extracting(iteration -> iteration.id())
                .containsExactly("task-turn-one");
    }

    @Test
    void unscopedTaskTurnCannotBorrowTheTasksCurrentStage()
    {
        insertUnscopedTaskAgentTicket();

        assertThatThrownBy(() -> service.runDetail(
                "v2-ticket:unscoped-task-ticket"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no stage-backed V2 log");
    }

    @Test
    void streamReadsNewDurableEvidenceForOnlyTheExactStage()
            throws Exception
    {
        insertAgentTicket(1, "ticket-one", "turn-one", "operation-one");
        insertAgentTicket(2, "ticket-two", "turn-two", "operation-two");
        insertExecution("execution-one", "ticket-one");
        insertExecution("execution-two", "ticket-two");
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<StreamEvent> event = new AtomicReference<>();
        Runnable unsubscribe = service.subscribe(
                "task-1", "remote-stage-1", value -> {
                    event.set(value);
                    received.countDown();
                });
        try {
            jdbc.update("""
                    INSERT INTO agent_execution_log(
                        execution_id, seq, payload, created_at_ms)
                    VALUES ('execution-two', 0,
                        '{"event":"text_delta","blockIndex":0,"chunk":"sibling"}',
                        101)
                    """);
            jdbc.update("""
                    INSERT INTO agent_execution_log(
                        execution_id, seq, payload, created_at_ms)
                    VALUES ('execution-one', 0,
                        '{"event":"text_delta","blockIndex":0,"chunk":"exact"}',
                        102)
                    """);

            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(event.get())
                    .isEqualTo(new StreamEvent.AssistantTextDelta(
                            Instant.ofEpochMilli(102), 0, "exact"));
        }
        finally {
            unsubscribe.run();
        }
    }

    private void insertAgentTicket(
            int number, String ticketId, String turnId, String operationId)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES (?, ?, 1, 'USER_STEERING', 'QUEUED', ?, 1, 1,
                    'API', '{"prompt":"steer"}', 90)
                """, turnId, "remote-stage-" + number, operationId);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN',
                    'STAGE_TURN', ?, 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', ?, 1, ?, 1, 1,
                    'REQUESTED', 90)
                """, ticketId, operationId, turnId, "task-" + number,
                "remote-stage-" + number);
    }

    private void insertExecution(String executionId, String ticketId)
    {
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, status,
                    provider, started_at_ms, heartbeat_at_ms)
                VALUES (?, ?, 1, 'RUNNING', 'openai', 100, 100)
                """, executionId, ticketId);
    }

    private void insertTaskAgentTicket()
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    delivery_lane, launch_input, requested_at_ms)
                VALUES ('task-turn-one', 'task-1',
                    'DEVELOPMENT_BRAIN_REVIEW', 'QUEUED',
                    'task-operation-one', 1, 1, 'remote-stage-1', 1, 'API',
                    '{"prompt":"Review the implementation"}', 92)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, status, created_at_ms)
                VALUES ('task-ticket-one', 'task-operation-one',
                    'EXECUTE_TASK_TURN', 'AGENT_TURN', 'TASK_TURN',
                    'task-turn-one', 'TASK_TURN_RESULT', 2, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1,
                    'REQUESTED', 92)
                """);
    }

    private void insertUnscopedTaskAgentTicket()
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('unscoped-task-turn', 'task-1',
                    'DEVELOPMENT_BRAIN_REVIEW', 'QUEUED',
                    'unscoped-task-operation', 1, 1, 'API',
                    '{"prompt":"Summarize the outcome"}', 94)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, status, created_at_ms)
                VALUES ('unscoped-task-ticket', 'unscoped-task-operation',
                    'EXECUTE_TASK_TURN', 'AGENT_TURN', 'TASK_TURN',
                    'unscoped-task-turn', 'TASK_TURN_RESULT', 2, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1,
                    'REQUESTED', 94)
                """);
    }
}
