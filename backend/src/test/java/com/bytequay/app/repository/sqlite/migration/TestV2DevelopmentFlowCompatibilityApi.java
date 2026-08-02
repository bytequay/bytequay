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

import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowInvariantAuditor;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BranchGuardService;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.stage.StageBudgetService;
import com.bytequay.app.service.stage.StageDetailService;
import com.bytequay.app.service.stage.StageServiceImpl;
import com.bytequay.app.service.stage.StageSteeringService;
import com.bytequay.app.service.threads.TaskTraceService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.bytequay.app.web.StageController;
import com.bytequay.app.web.TaskTraceController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestV2DevelopmentFlowCompatibilityApi
{
    private static final String V2_DETAIL_STAGE =
            "00000000-0000-0000-0000-000000000257";

    @TempDir
    private Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private TaskStore tasks;
    private StageStore legacyStages;
    private PullRequestService github;
    private AgentRunService legacyRuns;
    private ReviewRoundService legacyRounds;
    private BranchGuardService legacyGuard;
    private MockMvc stageApi;
    private MockMvc traceApi;
    private DevelopmentFlowInvariantAuditor auditor;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("v2-compatibility.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk(connection);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask(connection, 2);
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        try (Connection connection = DevelopmentFlowRemoteProtocolFixture.connect(url)) {
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner(connection, 2);
            DevelopmentFlowRemoteProtocolFixture.insertSnapshot(
                    connection, 1, 1, "head-1", "base-1", "OPEN", "MERGEABLE");
            DevelopmentFlowRemoteProtocolFixture.acceptSnapshot(
                    connection, 1, 1, "head-1", "base-1");
            DevelopmentFlowRemoteProtocolFixture.insertCiPolicy(connection, 1);
            DevelopmentFlowRemoteProtocolFixture.insertFailedCi(
                    connection, 1, 1, "head-1", "base-1");
            DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation,
                        from_checkpoint, to_checkpoint, stage_version,
                        cause, actor, occurred_at_ms)
                    VALUES
                        ('local-open-fact', 'local-stage-1', 'local-open', 1,
                         NULL, 'LOCAL_REVIEW', 0, 'development_ready', 'AGENT', 6),
                        ('remote-open-fact', 'remote-stage-1', 'remote-open', 1,
                         NULL, 'WAITING_CI', 0, 'published', 'SYSTEM', 52)
                    """);
            DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint,
                        opened_at_ms, completed_at_ms, end_reason)
                    VALUES ('%s', 'task-2', 'PLAN', 2, 0, 'COMPLETED',
                        3, 4, 'NORMAL')
                    """.formatted(V2_DETAIL_STAGE));
            DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation,
                        from_checkpoint, to_checkpoint, stage_version,
                        cause, actor, occurred_at_ms)
                    VALUES ('detail-stage-transition', '%s',
                        'detail-stage-command', 2, 'AWAITING_APPROVAL',
                        'COMPLETED', 0, 'plan_approved', 'USER', 4)
                    """.formatted(V2_DETAIL_STAGE));
            DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms,
                        workflow_version)
                    VALUES ('legacy-task', 'trunk-1', 3, 'IDLE',
                        'IMPLEMENTING', 53, 'LEGACY')
                    """);
        }

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        V2DevelopmentFlowProjection projection = new V2DevelopmentFlowProjection(jdbc);
        auditor = new DevelopmentFlowInvariantAuditor(jdbc);

        tasks = mock(TaskStore.class);
        when(tasks.findTaskById(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "task-1" -> Optional.of(task("task-1", 1));
            case "legacy-task" -> Optional.of(task("legacy-task", 3));
            default -> Optional.empty();
        });
        when(tasks.listPhaseEvents("legacy-task")).thenReturn(List.of(new TaskPhaseEvent(
                1, "legacy-task", null, TaskPhase.IMPLEMENTING,
                Instant.ofEpochMilli(53), "legacy_started", Actor.HUMAN)));

        legacyStages = mock(StageStore.class);
        when(legacyStages.findStagesByTask("legacy-task")).thenReturn(List.of());
        legacyRuns = mock(AgentRunService.class);
        legacyRounds = mock(ReviewRoundService.class);
        legacyGuard = mock(BranchGuardService.class);
        StageServiceImpl stageService = new StageServiceImpl(
                tasks, legacyStages, mock(StageBudgetService.class),
                mock(ThreadTurnEventStore.class), mock(ThreadStore.class),
                mock(ThreadTurnStore.class), legacyRuns, legacyGuard,
                legacyRounds, mapper);
        ReflectionTestUtils.setField(stageService, "v2Projection", projection);
        stageApi = standaloneSetup(new StageController(
                stageService,
                mock(StageDetailService.class),
                mock(StageSteeringService.class),
                mock(PlanStageService.class),
                legacyStages,
                tasks,
                mock(ThreadStore.class),
                mock(WorkModelResolver.class))).build();

        github = mock(PullRequestService.class);
        TaskTraceService traceService = new TaskTraceService(tasks, github);
        ReflectionTestUtils.setField(traceService, "v2Projection", projection);
        traceApi = standaloneSetup(new TaskTraceController(traceService)).build();
    }

    @Test
    void projectsV2BrainAndTraceFromMigratedTypedFactsWithoutLegacyReads()
            throws Exception
    {
        JsonNode brain = body(stageApi.perform(get("/api/tasks/task-1/brain"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(brain.path("task").path("currentPhase").asText())
                .isEqualTo("PUSHED_AWAITING_CI");
        assertThat(brain.path("task").path("repoFullName").asText()).isEqualTo("acme/widget");
        assertThat(brain.path("task").path("prNumber").asInt()).isEqualTo(41);
        assertThat(brain.path("task").path("agentModel").asText()).isEqualTo("review-model");
        assertThat(brain.path("stages").size()).isEqualTo(3);
        assertThat(brain.path("stages").get(0).path("type").asText())
                .isEqualTo("DEVELOPMENT_STAGE");
        assertThat(brain.path("stages").get(1).path("type").asText())
                .isEqualTo("PLAN_STAGE");
        assertThat(brain.path("stages").get(2).path("type").asText())
                .isEqualTo("REMOTE_DEVELOPMENT_STAGE");
        assertThat(brain.path("brainFeed").get(1).path("body").asText())
                .contains("waiting ci");
        assertThat(brain.path("rightRail").path("linkedPr").path("ciStatus").asText())
                .isEqualTo("failing");
        assertThat(brain.path("liveRuns").isEmpty()).isTrue();
        assertThat(brain.path("guard").path("enabled").asBoolean()).isFalse();
        assertThat(brain.path("liveRound").isNull()).isTrue();
        JsonNode activeStages = body(stageApi.perform(
                        get("/api/tasks/task-1/stages/active"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(activeStages).hasSize(1);
        assertThat(activeStages.get(0).path("id").asText())
                .isEqualTo("remote-stage-1");
        JsonNode stage = body(stageApi.perform(
                        get("/api/stages/" + V2_DETAIL_STAGE))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        assertThat(stage.path("stage").path("id").asText())
                .isEqualTo(V2_DETAIL_STAGE);
        assertThat(mapper.readTree(stage.path("events").get(0)
                .path("payloadJson").asText()).path("cause").asText())
                .isEqualTo("plan_approved");

        JsonNode trace = body(traceApi.perform(get("/api/tasks/task-1/trace"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(trace.path("currentPhase").asText()).isEqualTo("PUSHED_AWAITING_CI");
        assertThat(trace.path("currentMilestone").asText()).isEqualTo("WAIT_ON_PR");
        assertThat(trace.path("events").get(0).path("toPhase").asText())
                .isEqualTo("AWAITING_PUSH");
        assertThat(trace.path("events").get(1).path("toPhase").asText())
                .isEqualTo("PUSHED_AWAITING_CI");
        assertThat(trace.path("linkedActivePr").path("ciStatus").asText())
                .isEqualTo("FAILING");

        verify(tasks, never()).listPhaseEvents("task-1");
        verify(legacyStages, never()).findStagesByTask("task-1");
        verify(legacyStages, never()).findStageById(
                UUID.fromString(V2_DETAIL_STAGE));
        verifyNoInteractions(legacyRuns, legacyRounds, legacyGuard, github);
    }

    @Test
    void projectsAnAlreadySyncedTerminalPrStateOverAStaleAcceptedSnapshot()
    {
        jdbc.update("""
                INSERT INTO pull_requests(
                    id, repo, number, title, origin, labels, draft,
                    state, closed_at)
                VALUES (4241, 'acme/widget', 41, 'Task 1', 'AUTHORED', '[]', 1,
                    'closed', '2026-08-01T00:00:00Z')
                """);

        TaskBrainViewData brain = new V2DevelopmentFlowProjection(jdbc)
                .brain(task("task-1", 1));

        assertThat(brain.task().prDraft()).isFalse();
        assertThat(brain.rightRail().linkedPr().status()).isEqualTo("closed");
        assertThat(jdbc.queryForObject("""
                SELECT pr_state FROM remote_pr_snapshot WHERE id = 'snapshot-1-1'
                """, String.class)).isEqualTo("OPEN");
    }

    @Test
    void keepsLegacySiblingOnLegacyTraceAndStageRoutes()
            throws Exception
    {
        JsonNode trace = body(traceApi.perform(get("/api/tasks/legacy-task/trace"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(trace.path("currentPhase").asText()).isEqualTo("IMPLEMENTING");
        assertThat(trace.path("events").get(0).path("reason").asText())
                .isEqualTo("legacy_started");

        JsonNode stages = body(stageApi.perform(get("/api/tasks/legacy-task/stages"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stages.isEmpty()).isTrue();
        verify(tasks).listPhaseEvents("legacy-task");
        verify(legacyStages).findStagesByTask("legacy-task");
    }

    @Test
    void keepsHistoricalCiBrainFailureAuditableWithoutProjectingRetry()
    {
        seedRemoteBrainFailure();
        V2DevelopmentFlowProjection projection =
                new V2DevelopmentFlowProjection(jdbc);

        assertThat(projection.brain(task("task-1", 1)).recovery()).isNull();

        jdbc.update("""
                INSERT INTO ci_repair_delivery_receipt(
                    ci_repair_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES ('projection-brain-row', 'projection-brain-operation',
                    'FAILED', ?, 'ACCEPTED', 105)
                """, "0".repeat(64));

        assertThat(projection.brain(task("task-1", 1)).recovery()).isNull();
        acceptRemoteBrainProtocolFailure();

        assertThat(projection.brain(task("task-1", 1)).recovery()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT family FROM remote_repair_brain_failure_receipt_v309
                 WHERE blocker_id = 'projection-brain-blocker'
                """, String.class)).isEqualTo("CI");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = 106,
                    resolution_evidence = 'replacement admitted'
                WHERE id = 'projection-brain-blocker'
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("blocker lacks its replacement");
        assertThat(projection.brain(task("task-1", 1)).recovery()).isNull();
    }

    @Test
    void doesNotProjectRemoteBrainRecoveryFromSuccessfulRawEvidence()
    {
        seedRemoteBrainFailure(
                "SUCCEEDED", "malformed Brain response", null);
        jdbc.update("""
                INSERT INTO ci_repair_delivery_receipt(
                    ci_repair_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES ('projection-brain-row', 'projection-brain-operation',
                    'SUCCEEDED', ?, 'ACCEPTED', 105)
                """, "1".repeat(64));

        assertThat(new V2DevelopmentFlowProjection(jdbc)
                .brain(task("task-1", 1)).recovery()).isNull();
    }

    @Test
    void auditorReportsLiveTurnWithoutItsTicket()
    {
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('orphan-turn', 'task-1', 'TEST_READ_AUDIT', 'REQUESTED',
                    'orphan-operation', 1, 1, 'API', '{}', 80)
                """);
        DevelopmentFlowInvariantAuditor.Audit audit = auditor.audit();
        assertThat(audit.healthy()).isFalse();
        assertThat(audit.findings()).extracting(
                DevelopmentFlowInvariantAuditor.Finding::code)
                .contains("V2_LIVE_TURN_WITHOUT_TICKET");
    }

    private JsonNode body(String json)
            throws Exception
    {
        return mapper.readTree(json);
    }

    private void seedRemoteBrainFailure()
    {
        seedRemoteBrainFailure("FAILED", null, "provider exited");
    }

    private void seedRemoteBrainFailure(
            String rawOutcome, String rawPayload, String rawError)
    {
        jdbc.update("""
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_count, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('projection-ci-episode', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'TASK_DETERMINISTIC', 'AWAITING_PUSH_CI',
                    0, 1, 2, 2, 2, 100)
                """);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('projection-brain-turn', 'task-1',
                    'REMOTE_CI_BRAIN_REVIEW', 'REQUESTED',
                    'projection-brain-operation', 1, 1, 'remote-stage-1', 1,
                    'fingerprint-1', 'head-1', 'base-1', 'API', '{}', 101)
                """);
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, task_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('projection-brain-row', 'projection-ci-episode',
                    'remote-stage-1', 'task-1', 1, 1, 'BRAIN_REVIEW',
                    'projection-brain-operation', 1, 'projection-brain-turn',
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 101)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('projection-brain-ticket', 'projection-brain-operation',
                    'EXECUTE_TASK_TURN', 'AGENT_TURN', 'TASK_TURN',
                    'projection-brain-turn', 'REMOTE_CI_BRAIN_RESULT', 2,
                    1, 0, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'remote-stage-1', 1, 1, 'fingerprint-1', 'head-1',
                    'base-1', 'REQUESTED', 101)
                """);
        jdbc.update("""
                UPDATE ci_repair_operation SET status = 'DISPATCHED'
                WHERE id = 'projection-brain-row'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = ?, pending_result_payload = ?,
                    pending_result_error = ?,
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'remote-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'projection-brain-operation',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-1',
                    pending_result_expected_head_sha = 'head-1',
                    pending_result_expected_base_sha = 'base-1'
                WHERE id = 'projection-brain-ticket'
                """, rawOutcome, rawPayload, rawError);
        jdbc.update("""
                UPDATE task_turn
                SET status = 'FAILED', started_at_ms = 102,
                    finished_at_ms = 103, error_message = 'provider exited'
                WHERE id = 'projection-brain-turn'
                """);
        jdbc.update("""
                UPDATE ci_repair_operation
                SET status = 'FAILED', completed_at_ms = 103,
                    error_message = 'provider exited'
                WHERE id = 'projection-brain-row'
                """);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, owner_kind, owner_id, subject_revision,
                    blocker_type, status, payload_json, opened_at_ms)
                VALUES ('projection-brain-blocker', 'task-1', 'TASK', 'task-1',
                    'projection-brain-turn', 'REMOTE_REPAIR_BRAIN_FAILED',
                    'OPEN', 'provider exited', 104)
                """);
    }

    private void acceptRemoteBrainProtocolFailure()
    {
        jdbc.update("""
                UPDATE tasks SET aggregate_version = 3
                WHERE id = 'task-1' AND aggregate_version = 2
                """);
        jdbc.update("""
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES ('projection-brain-failure-transition', 'task-1',
                    'projection-brain-failure-command', 1, 'ACTIVE', 'ACTIVE',
                    3, 'ACCEPT_BRAIN_PROTOCOL_FAILURE', 'SYSTEM', 106)
                """);
        jdbc.update("""
                INSERT INTO task_brain_protocol_failure_receipt_v300(
                    id, task_id, command_id, cause, actor, disposition,
                    subject_task_epoch, subject_stage_id,
                    subject_stage_generation, subject_operation_id,
                    subject_attempt, subject_expected_code_fingerprint,
                    subject_expected_head_sha, subject_expected_base_sha,
                    proof_id, returned_trunk_id, returned_lifecycle,
                    returned_epoch, returned_version,
                    returned_current_stage_id, recorded_at_ms)
                VALUES ('projection-brain-failure-receipt', 'task-1',
                    'projection-brain-failure-command',
                    'ACCEPT_BRAIN_PROTOCOL_FAILURE', 'SYSTEM', 'APPLIED',
                    1, 'remote-stage-1', 1, 'projection-brain-operation', 1,
                    'fingerprint-1', 'head-1', 'base-1',
                    'projection-brain-blocker', 'trunk-1', 'ACTIVE', 1, 3,
                    'remote-stage-1', 106)
                """);
        jdbc.update("""
                INSERT INTO remote_repair_brain_failure_receipt_v309(
                    id, family, source_kind, source_operation_row_id,
                    ci_repair_episode_id, task_id, task_epoch,
                    remote_development_stage_id, stage_generation,
                    task_turn_id, operation_id, semantic_attempt,
                    execution_attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, blocker_id,
                    raw_outcome, raw_result_digest, error_message,
                    cleared_task_version, recorded_at_ms)
                VALUES ('projection-brain-typed-failure', 'CI', 'ORIGINAL',
                    'projection-brain-row', 'projection-ci-episode', 'task-1',
                    1, 'remote-stage-1', 1, 'projection-brain-turn',
                    'projection-brain-operation', 1, 1, 'fingerprint-1',
                    'head-1', 'base-1', 'projection-brain-blocker', 'FAILED',
                    ?, 'provider exited', 3, 106)
                """, "0".repeat(64));
    }

    private static Task task(String id, long sequence)
    {
        return new Task(
                id, "trunk-1", sequence, TaskStatus.IDLE,
                "legacy-fallback", "/tmp/legacy-fallback", "main", "/tmp",
                null, null, null, null, null, null, null, null,
                0, 0, 0, null, Instant.ofEpochMilli(1), null, null,
                null, null, null);
    }
}
