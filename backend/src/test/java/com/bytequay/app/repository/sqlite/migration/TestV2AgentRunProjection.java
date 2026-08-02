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
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SqliteTestPools.class)
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
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        try (Connection connection =
                DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(
                    connection);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(
                    connection, 1);
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        try (Connection connection =
                DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(
                    connection, 1);
        }
        DataSource dataSource = SqliteTestPools.open(url);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index'
                  AND name = 'idx_dispatch_ticket_agent_turn_workspace_v267'
                """, Integer.class)).isEqualTo(1);
        projection = new V2AgentRunProjection(jdbc, new ObjectMapper());
    }

    @Test
    void projectsTypedThreadTaskAndStageTurnsWithoutAgentRunRows()
    {
        insertThreadTurn();
        insertTaskTurn();
        insertStageTurn();
        completeTicket("visible-task-ticket", 82);
        claimTicket("visible-stage-ticket", "visible-stage-operation");
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
        assertThat(stage.model()).isEqualTo("gpt-5.6");
        assertThat(stage.costUsdMilli()).isEqualTo(17);
        assertThat(stage.tokensIn()).isEqualTo(23);
        assertThat(stage.tokensOut()).isEqualTo(29);
        assertThat(stage.iterations()).isEqualTo(1);
        assertThat(stage.stepCursor()).isEqualTo(1);
        assertThat(stage.launchInput()).isEqualTo("Address the feedback");
        assertThat(stage.finishedAt()).isNull();
        assertThat(projection.findById("legacy-run")).isEmpty();
    }

    @Test
    void dispatcherWaitAndFrozenTurnIdentityDriveStatusAndProgress()
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('retry-turn', 'task-1', 'DEVELOPMENT_BRAIN_REVIEW',
                    'QUEUED', 'retry-operation', 3, 1, 'API',
                    '{"provider":"openai","model":"gpt-5.6-mini",\
                      "prompt":"Review again"}', 100)
                """);
        insertTicket(
                "retry-ticket", "retry-operation", "EXECUTE_TASK_TURN",
                "TASK_TURN", "retry-turn", "TASK_TURN_RESULT", false,
                "task-1", null);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RETRY_WAIT',
                    next_attempt_at_ms = 200, infrastructure_attempts = 2,
                    last_error = 'provider unavailable'
                WHERE id = 'retry-ticket'
                """);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider, status,
                    started_at_ms, finished_at_ms, cost_usd_milli,
                    tokens_in, tokens_out)
                VALUES
                    ('retry-execution-1', 'retry-ticket', 1, 'openai',
                     'FAILED', 101, 102, 5, 7, 11),
                    ('retry-execution-2', 'retry-ticket', 2, 'openai',
                     'FAILED', 103, 104, 13, 17, 19)
                """);

        AgentRun retry = projection.findById(
                "v2-ticket:retry-ticket").orElseThrow();

        assertThat(retry.status()).isEqualTo(AgentRun.STATUS_QUEUED);
        assertThat(retry.iterations()).isEqualTo(3);
        assertThat(retry.stepCursor()).isEqualTo(2);
        assertThat(retry.provider()).isEqualTo("openai");
        assertThat(retry.model()).isEqualTo("gpt-5.6-mini");
        assertThat(retry.costUsdMilli()).isEqualTo(18);
        assertThat(retry.tokensIn()).isEqualTo(24);
        assertThat(retry.tokensOut()).isEqualTo(30);
    }

    @Test
    void terminalHistoryStaysTerminalWhenItsTaskIsPaused()
    {
        insertTaskTurn();
        completeTicket("visible-task-ticket", 82);
        jdbc.update("""
                UPDATE tasks
                SET aggregate_version = aggregate_version + 1,
                    lifecycle_state = 'PAUSED'
                WHERE id = 'task-1'
                """);

        AgentRun completed = projection.findById(
                "v2-ticket:visible-task-ticket").orElseThrow();

        assertThat(completed.status()).isEqualTo(AgentRun.STATUS_SUCCEEDED);
        assertThat(completed.finishedAt()).isNotNull();
    }

    @Test
    void exactOpenUserWaitOverridesItsSuccessfulDelivery()
    {
        insertTaskTurn();
        completeTicket("visible-task-ticket", 82);
        jdbc.update("""
                INSERT INTO task_question(
                    id, turn_id, call_id, prompt, state, created_at_ms)
                VALUES ('question-1', 'visible-task-turn', 'call-1',
                    'Which option?', 'OPEN', 83)
                """);
        jdbc.update("""
                INSERT INTO typed_user_wait_result(
                    operation_id, owner_kind, turn_id, wait_kind, wait_id,
                    payload_digest, result_evidence, accepted_at_ms)
                VALUES ('visible-task-operation', 'TASK_TURN',
                    'visible-task-turn', 'QUESTION', 'question-1',
                    'payload', 'evidence', 84)
                """);

        AgentRun waiting = projection.findById(
                "v2-ticket:visible-task-ticket").orElseThrow();

        assertThat(waiting.status()).isEqualTo(AgentRun.STATUS_AWAITING_GATE);
        assertThat(waiting.pauseReason()).isEqualTo("Waiting for user");
        assertThat(waiting.finishedAt()).isNull();
        assertThat(waiting.outcome()).isNull();
    }

    @Test
    void typedThreadHistorySurvivesAQuiescentRoutingVersionChange()
    {
        jdbc.update("""
                INSERT INTO thread_turn(
                    id, trunk_id, purpose, status, operation_id, attempt,
                    delivery_lane, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms)
                VALUES ('historical-thread-turn', 'trunk-1',
                    'TRUNK_PLANNING', 'SUCCEEDED',
                    'historical-thread-operation', 1, 'API',
                    '{"provider":"openai","model":"gpt-5.6",\
                      "prompt":"Plan"}', 90, 91, 92)
                """);
        insertTicket(
                "historical-thread-ticket", "historical-thread-operation",
                "EXECUTE_THREAD_TURN", "THREAD_TURN",
                "historical-thread-turn", "THREAD_TURN_RESULT", true,
                null, null);
        completeTicket("historical-thread-ticket", 92);
        jdbc.update("""
                UPDATE threads
                SET aggregate_version = aggregate_version + 1,
                    turn_version = 'LEGACY'
                WHERE id = 'trunk-1'
                """);

        assertThat(projection.findById(
                "v2-ticket:historical-thread-ticket")).isPresent();
    }

    @Test
    void remoteFeedbackTurnKeepsItsBatchRelationshipAndPublicKind()
    {
        insertRemoteFeedbackTurn();

        AgentRun feedback = projection.findById(
                "v2-ticket:feedback-ticket").orElseThrow();

        assertThat(feedback.kind()).isEqualTo(AgentRun.KIND_REVIEW_ROUND);
        assertThat(feedback.reviewRoundId()).isEqualTo("feedback-batch-1");
        assertThat(feedback.source()).isEqualTo(AgentRun.SOURCE_REMOTE);
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
                    '{"provider":"openai","model":"gpt-5.6",\
                      "prompt":"Address the feedback"}', 83, 84)
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

    private void claimTicket(String ticketId, String operationId)
    {
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms,
                    expires_at_ms)
                SELECT 'lease-' || id, id, operation_id, 'V2', lane_mask,
                       trunk_control, exclusive_task, writer_required,
                       workspace_id, trunk_id, task_id, task_epoch,
                       'test-worker', 1, 84, 84, 1000
                FROM dispatch_ticket WHERE id = ? AND operation_id = ?
                """, ticketId, operationId);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RUNNING',
                    claim_purpose = 'EXECUTE', claim_owner = 'test-worker',
                    capacity_lease_id = 'lease-' || id,
                    claim_expires_at_ms = 900,
                    infrastructure_attempts = 1, started_at_ms = 84
                WHERE id = ?
                """, ticketId);
    }

    private void insertRemoteFeedbackTurn()
    {
        jdbc.update("""
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state, observed_at_ms)
                VALUES ('feedback-snapshot', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', 1, 'feedback-observation', 'acme/widget',
                    41, 'head-1', 'base-1', 'OPEN', 'MERGEABLE', 'NONE', 90)
                """);
        jdbc.update("""
                UPDATE remote_development_stage
                SET accepted_snapshot_id = 'feedback-snapshot',
                    accepted_observation_revision = 1,
                    subject_changed_at_ms = 90
                WHERE stage_id = 'remote-stage-1'
                """);
        jdbc.update("""
                INSERT INTO remote_inbox_item(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    remote_pr_snapshot_id, kind, external_key,
                    external_revision, head_sha, base_sha, actor_login,
                    provenance, ignored, comment_id, body, body_digest,
                    observed_at_ms)
                VALUES ('feedback-inbox', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'feedback-snapshot', 'TOP_LEVEL_COMMENT',
                    'comment-1', 1, 'head-1', 'base-1', 'reviewer',
                    'EXTERNAL', 0, 'comment-1', 'Please fix this',
                    'feedback-body-digest', 91)
                """);
        jdbc.update("""
                INSERT INTO remote_feedback_batch(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    sequence, head_sha, base_sha, status,
                    brain_review_required, item_count, created_at_ms)
                VALUES ('feedback-batch-1', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'feedback-snapshot', 1, 'head-1', 'base-1',
                    'BUILDING', 1, 1, 92)
                """);
        jdbc.update("""
                INSERT INTO remote_feedback_batch_item(
                    remote_feedback_batch_id, ordinal, remote_inbox_item_id,
                    external_revision, kind, frozen_body, body_digest,
                    selected_by, selected_at_ms)
                VALUES ('feedback-batch-1', 1, 'feedback-inbox', 1,
                    'TOP_LEVEL_COMMENT', 'Please fix this',
                    'feedback-body-digest', 'user', 93)
                """);
        jdbc.update("""
                UPDATE remote_feedback_batch
                SET status = 'FROZEN', content_digest = 'batch-digest',
                    frozen_at_ms = 94
                WHERE id = 'feedback-batch-1'
                """);
        jdbc.update("""
                UPDATE stage
                SET version = version + 1,
                    checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                WHERE id = 'remote-stage-1'
                """);
        jdbc.update("""
                UPDATE remote_feedback_batch SET status = 'ADDRESSING'
                WHERE id = 'feedback-batch-1'
                """);
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT 'feedback-turn', 'remote-stage-1', 1,
                    'ADDRESS_REMOTE_FEEDBACK', 'QUEUED',
                    'feedback-operation', 1, 1, code_fingerprint,
                    'head-1', 'base-1', 'API',
                    '{"provider":"openai","model":"gpt-5.6",\
                      "prompt":"Address feedback"}', 95
                FROM task_code_identity WHERE task_id = 'task-1'
                """);
        jdbc.update("""
                INSERT INTO remote_feedback_stage_turn_request(
                    id, remote_feedback_batch_id, stage_turn_id, task_id,
                    remote_development_stage_id, task_epoch, stage_generation,
                    semantic_attempt, predecessor_turn_id, prompt_digest,
                    requested_by, requested_at_ms)
                VALUES ('feedback-request', 'feedback-batch-1',
                    'feedback-turn', 'task-1', 'remote-stage-1', 1, 1, 1,
                    NULL, ?, 'user', 95)
                """, "a".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                SELECT 'feedback-ticket', 'feedback-operation',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'feedback-turn', 'REMOTE_FEEDBACK_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'remote-stage-1', 1, 1, code_fingerprint,
                    'head-1', 'base-1', 'REQUESTED', 95
                FROM task_code_identity WHERE task_id = 'task-1'
                """);
    }

    private void completeTicket(String ticketId, long completedAt)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'SUCCEEDED',
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'accepted', completed_at_ms = ?
                WHERE id = ?
                """, completedAt, ticketId);
    }
}
