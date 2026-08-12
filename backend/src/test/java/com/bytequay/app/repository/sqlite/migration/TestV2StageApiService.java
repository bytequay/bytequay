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
import com.bytequay.app.beans.stage.StageDetailData.ConversationRow;
import com.bytequay.app.developmentflow.compatibility.V2BranchGuardProjection;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2StageApiService;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
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

@ExtendWith(SqliteTestPools.class)
@Disabled("Legacy V2 stage compatibility surface; its projections are no longer"
        + " maintained and these expectations no longer hold")
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
        }
        DevelopmentFlowRemoteProtocolFixture.migrate(url);
        DataSource dataSource = SqliteTestPools.open(url);
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
                .containsExactly(
                        "local-stage-1", "plan-stage-1", "remote-stage-1");
        assertThat(detail.conversationThreadId()).isNull();
        assertThat(detail.liveRuns()).isEmpty();
        assertThat(detail.guard().taskId()).isEqualTo("task-1");
        assertThat(detail.guard().enabled()).isTrue();
        assertThat(detail.guard().schedule()).isEqualTo("nightly");
        assertThat(detail.recovery().ci()).isNull();
        assertThat(detail.recovery().cleanup()).isNull();
        assertThat(detail.recovery().replacement()).isNull();
        assertThat(detail.recovery().localPublishBaseSync()).isNull();
    }

    @Test
    void closedStageDetailReportsTheTaskPhaseNotTheReadStagePhase()
    {
        // The shell's Stage rail is Task-level, so reading a closed Local
        // Development page must not re-derive the ladder from that Stage's own
        // COMPLETED checkpoint — it used to fall through to IMPLEMENTING and
        // redraw finished Validation/Brain review steps as unstarted.
        assertThat(service.detail("task-1", "local-stage-1").task().currentPhase())
                .isEqualTo("PUSHED_AWAITING_CI");
        assertThat(service.detail("task-1", "remote-stage-1").task().currentPhase())
                .isEqualTo("PUSHED_AWAITING_CI");
        assertThat(service.detail("task-1", "plan-stage-1").task().currentPhase())
                .isEqualTo("PUSHED_AWAITING_CI");
    }

    @Test
    void finishedStageReplaysItsDurableToolCallsFromProviderFrames()
            throws Exception
    {
        // The live stream drops its compact activity list on TurnDone, so a
        // closed Stage has to rebuild its tool log from the durable frames or
        // it keeps only the prompt and the final result.
        ObjectMapper mapper = new ObjectMapper();
        seedFrame(mapper, 1, 20, """
                {"type":"assistant","message":{"content":[{"type":"tool_use",\
                "id":"tu-1","name":"Bash","input":{"command":"mvn -q verify"}}]}}""");
        seedFrame(mapper, 2, 21, """
                {"type":"system","subtype":"status","status":"requesting"}""");
        seedFrame(mapper, 3, 22, """
                {"type":"assistant","message":{"content":[{"type":"thinking",\
                "thinking":"weighing it"}]}}""");
        seedFrame(mapper, 4, 23, "not json at all");
        // Older rows store a raw trace string, not a JSON frame envelope.
        // json_extract raises on those instead of yielding null.
        jdbc.update(
                "INSERT INTO agent_execution_log(execution_id, seq, payload,"
                        + " created_at_ms) VALUES ('development-execution-1', 5,"
                        + " 'raw provider trace', 24)");

        List<ConversationRow> tools = service.detail("task-1", "local-stage-1")
                .conversation().stream()
                .filter(row -> "tool_call".equals(row.kind()))
                .toList();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).toolTag()).isEqualTo("Bash");
        assertThat(tools.get(0).toolDetail()).isEqualTo("mvn -q verify");
        // A frame with no tool_use, and an untypeable frame, add no rows and
        // must not break the read.
        assertThat(service.detail("task-2", "local-stage-2").conversation())
                .noneMatch(row -> "tool_call".equals(row.kind()));
    }

    private void seedFrame(ObjectMapper mapper, int seq, long at, String line)
            throws Exception
    {
        jdbc.update(
                "INSERT INTO agent_execution_log(execution_id, seq, payload,"
                        + " created_at_ms) VALUES ('development-execution-1', ?, ?, ?)",
                seq,
                mapper.writeValueAsString(
                        mapper.createObjectNode()
                                .put("stream", "provider")
                                .put("line", line)),
                at);
    }

    @Test
    void detailProjectsOnlyTheExactOpenLocalPublishBaseSyncBlocker()
            throws Exception
    {
        insertStalledLocalStageTurn(1, " ");
        jdbc.update("""
                UPDATE stage
                   SET version = 2, checkpoint = 'LOCAL_REVIEW'
                 WHERE id = 'retry-local-stage-1' AND version = 1
                """);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('local-base-sync-blocker-1', 'task-1',
                    'retry-local-stage-1', 'STAGE', 'retry-local-stage-1',
                    'publish-operation-1',
                    'LOCAL_PUBLISH_BASE_SYNC_REQUIRED', 'OPEN',
                    '{"sourcePublishOperationId":"publish-operation-1",'
                    || '"sourceBaseSha":"base-old",'
                    || '"targetBaseSha":"base-new"}', 120)
                """);

        StageDetailData detail = service.detail(
                "task-1", "retry-local-stage-1");

        assertThat(detail.recovery().localPublishBaseSync().blockerId())
                .isEqualTo("local-base-sync-blocker-1");
        assertThat(detail.recovery().localPublishBaseSync().sourceBaseSha())
                .isEqualTo("base-old");
        assertThat(detail.recovery().localPublishBaseSync().targetBaseSha())
                .isEqualTo("base-new");
        assertThat(service.detail("task-2", "remote-stage-2")
                .recovery().localPublishBaseSync()).isNull();

        jdbc.update("""
                UPDATE task_blocker
                   SET status = 'RESOLVED', resolved_at_ms = 121,
                       resolution_evidence = 'approved'
                 WHERE id = 'local-base-sync-blocker-1'
                """);

        assertThat(service.detail("task-1", "retry-local-stage-1")
                .recovery().localPublishBaseSync()).isNull();
    }

    @Test
    void detailProjectsOnlyAnExactUndeliveredCurrentStageTurn()
            throws Exception
    {
        insertStalledLocalStageTurn(1, "Local result is not strict JSON");
        insertStalledLocalStageTurn(2, "   ");

        StageDetailData exact = service.detail("task-1", "retry-local-stage-1");

        assertThat(exact.recovery().replacement().stageTurnId())
                .isEqualTo("retry-turn-1");
        assertThat(exact.recovery().replacement().reason())
                .isEqualTo("Local result is not strict JSON");
        assertThat(service.detail("task-1", "remote-stage-1")
                .recovery().replacement()).isNull();
        assertThat(service.detail("task-2", "retry-local-stage-2")
                .recovery().replacement()).isNull();

        jdbc.update("""
                UPDATE dispatch_ticket SET version = 2, last_error = 'late error'
                WHERE id = 'retry-ticket-2'
                """);
        jdbc.update("""
                UPDATE stage_turn
                   SET status = 'FAILED', started_at_ms = 120,
                       finished_at_ms = 121, error_message = 'delivery accepted'
                 WHERE id = 'retry-turn-2'
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_delivery_receipt(
                    stage_turn_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES ('retry-turn-2', 'retry-operation-2', 'FAILED',
                    ?, 'ACCEPTED', 121)
                """, "0".repeat(64));

        assertThat(service.detail("task-2", "retry-local-stage-2")
                .recovery().replacement()).isNull();
    }

    @Test
    void detailDoesNotOfferReplacementBeforeAnOrdinaryFailedResultIsDelivered()
            throws Exception
    {
        insertStalledLocalStageTurn(
                1, "provider failed before result delivery", "FAILED");

        assertThat(service.detail("task-1", "retry-local-stage-1")
                .recovery().replacement()).isNull();
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
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    blocker_type, status, opened_at_ms)
                VALUES ('budget-blocker-1', 'task-1', 'remote-stage-1',
                    'EPISODE', 'episode-exact', 'CI_BUDGET_EXHAUSTED',
                    'OPEN', 96)
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
    void branchSyncExhaustionIsVisibleUntilItsExactControlIsConsumed()
            throws Exception
    {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            insertCiPolicy(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1",
                    "OPEN", "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        }
        jdbc.update("""
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    source_snapshot_id, old_head_sha, observed_base_sha,
                    target_base_sha, branch_sync_policy_revision_id,
                    policy_source, purpose, authority_kind, authority_id,
                    status, attempt_count, attempt_limit, opened_at_ms,
                    completed_at_ms, error_message)
                VALUES ('branch-exhausted-1', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', 'snapshot-1-1', 'head-1', 'base-1',
                    'base-2', 'policy-1', 'USER_CONFIGURED', 'SCHEDULED',
                    'BRANCH_SYNC_POLICY', 'policy-1', 'FAILED', 3, 3, 95,
                    96, 'attempt budget exhausted')
                """);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('branch-exhausted-blocker-1', 'task-1',
                    'remote-stage-1', 'EPISODE', 'branch-exhausted-1',
                    'branch-exhausted-1', 'BRANCH_SYNC_EXHAUSTED', 'OPEN',
                    '{}', 96)
                """);
        jdbc.update("""
                INSERT INTO branch_sync_exhaustion_v319(
                    branch_sync_episode_id, blocker_id, task_id, stage_id,
                    remote_head_sha, remote_base_sha, code_fingerprint,
                    code_head_sha, code_base_sha, reason, exhausted_at_ms)
                SELECT 'branch-exhausted-1', 'branch-exhausted-blocker-1',
                    'task-1', 'remote-stage-1', 'head-1', 'base-1',
                    code_fingerprint, head_sha, base_sha,
                    'attempt budget exhausted', 96
                FROM task_current_code_subject_v230 WHERE task_id = 'task-1'
                """);

        StageDetailData first = service.detail("task-1", "remote-stage-1");

        assertThat(first.recovery().branchSync().episodeId())
                .isEqualTo("branch-exhausted-1");
        assertThat(first.recovery().branchSync().blockerId())
                .isEqualTo("branch-exhausted-blocker-1");
        assertThat(first.recovery().branchSync().attemptCount()).isEqualTo(3);
        assertThat(first.recovery().branchSync().actions()).containsExactly(
                "MANUAL_TAKEOVER", "STOP_AUTOMATION");
        assertThat(first.recovery().ci()).isNull();
        int ticketsBeforeControl = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_ticket", Integer.class);

        jdbc.update("""
                INSERT INTO branch_sync_control_command_v319(
                    id, branch_sync_episode_id, blocker_id, task_id, stage_id,
                    command_id, kind, actor, reason, created_at_ms,
                    consumed_at_ms)
                VALUES ('branch-control-1', 'branch-exhausted-1',
                    'branch-exhausted-blocker-1', 'task-1', 'remote-stage-1',
                    'branch-control-command-1', 'STOP_AUTOMATION',
                    'user/task-recovery', 'stop exact exhausted sync', 97, 97)
                """);
        jdbc.update("""
                UPDATE task_blocker
                   SET status = 'RESOLVED', resolved_at_ms = 97,
                       resolution_evidence = 'STOP_AUTOMATION'
                 WHERE id = 'branch-exhausted-blocker-1'
                   AND status = 'OPEN'
                """);

        StageDetailData second = service.detail("task-1", "remote-stage-1");

        assertThat(second.recovery().branchSync()).isNull();
        assertThat(second.recovery().ci()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_ticket", Integer.class))
                .isEqualTo(ticketsBeforeControl);
    }

    @Test
    void historicalSourceStageQuarantineIsProjectedOnTheCurrentOwnerStage()
    {
        var subject = jdbc.queryForMap("""
                SELECT code.code_fingerprint, code.head_sha, code.base_sha,
                       identity.worktree_path, identity.branch_name
                  FROM task_current_code_subject_v230 code
                  JOIN task_code_identity identity
                    ON identity.task_id = code.task_id
                 WHERE code.task_id = 'task-1'
                """);
        String fingerprint = (String) subject.get("code_fingerprint");
        String head = (String) subject.get("head_sha");
        String base = (String) subject.get("base_sha");
        String worktree = (String) subject.get("worktree_path");
        String branch = (String) subject.get("branch_name");
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('quarantined-source-turn', 'local-stage-1', 1,
                    'TEST_WRITE', 'QUEUED', 'quarantined-source-operation',
                    1, 1, ?, ?, ?, 'CLI', '{}', 90)
                """, fingerprint, head, base);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('quarantined-source-ticket',
                    'quarantined-source-operation', 'TEST_WRITE', 'LOCAL_GIT',
                    'STAGE_TURN', 'quarantined-source-turn', 'TEST_RESULT', 16,
                    0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage-1', 1, 1, ?, ?, ?, 'REQUESTED', 90)
                """, fingerprint, head, base);
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms,
                    expires_at_ms)
                VALUES ('quarantined-source-capacity',
                    'quarantined-source-ticket', 'quarantined-source-operation',
                    'V2', 16, 0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'source-worker', 91, 91, 91, 1000)
                """);
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1, status = 'RUNNING',
                       claim_purpose = 'EXECUTE', claim_owner = 'source-worker',
                       capacity_lease_id = 'quarantined-source-capacity',
                       claim_expires_at_ms = 1000,
                       infrastructure_attempts = 1, started_at_ms = 91
                 WHERE id = 'quarantined-source-ticket'
                """);
        jdbc.update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, holder_pid,
                    acquired_at_ms, expires_at_ms, workflow_version,
                    operation_id, task_epoch, fencing_token, lease_owner)
                VALUES (?, 'task-1', 'V2_OPERATION', NULL, 91, 1000, 'V2',
                    'quarantined-source-operation', 1, 91, 'source-worker')
                """, worktree);
        jdbc.update("""
                INSERT INTO agent_turn_worktree_quarantine_v318(
                    id, task_id, stage_id, source_operation_id,
                    worktree_path, expected_branch_name,
                    expected_code_fingerprint, expected_head_sha,
                    observed_branch_name, observed_head_sha, observed_clean,
                    observed_code_fingerprint, reason, status, opened_at_ms)
                VALUES ('historical-quarantine', 'task-1', 'local-stage-1',
                    'quarantined-source-operation', ?, ?, ?, ?, ?,
                    'dirty-head', 0, 'dirty-fingerprint',
                    'restore was not exact', 'OPEN', 92)
                """, worktree, branch, fingerprint, head, branch);

        StageDetailData detail = service.detail("task-1", "remote-stage-1");

        var recovery = detail.recovery().worktreeQuarantine();
        assertThat(recovery.quarantineId())
                .isEqualTo("historical-quarantine");
        assertThat(recovery.sourceOperationId())
                .isEqualTo("quarantined-source-operation");
        assertThat(recovery.taskEpoch()).isEqualTo(1);
        assertThat(recovery.stageId()).isEqualTo("remote-stage-1");
        assertThat(recovery.stageGeneration()).isEqualTo(1);
        assertThat(recovery.worktreePath()).isEqualTo(worktree);
        assertThat(recovery.expectedBranchName()).isEqualTo(branch);
        assertThat(recovery.expectedCodeFingerprint()).isEqualTo(fingerprint);
        assertThat(recovery.expectedHeadSha()).isEqualTo(head);
        assertThat(recovery.expectedBaseSha()).isEqualTo(base);
        assertThat(recovery.actions())
                .containsExactly("REPAIR_WORKTREE");
        assertThat(detail.recovery().ci()).isNull();
    }

    @Test
    void detailProjectsAnOpenProvenBaseBlockerWithItsExactRepairAction()
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
                VALUES ('episode-base', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                    'BASE_DETERMINISTIC', 'OPEN', 0, 2, 1, 2, 95)
                """);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('base-blocker-1', 'task-1', 'remote-stage-1',
                    'EPISODE', 'episode-base', 'head-1:base-1',
                    'CI_BASE_REPAIR_REQUIRED', 'OPEN',
                    '{"classification":"BASE_DETERMINISTIC"}', 96)
                """);

        StageDetailData detail = service.detail("task-1", "remote-stage-1");

        assertThat(detail.recovery().ci().episodeId())
                .isEqualTo("episode-base");
        assertThat(detail.recovery().ci().blockerId())
                .isEqualTo("base-blocker-1");
        assertThat(detail.recovery().ci().blockerType())
                .isEqualTo("CI_BASE_REPAIR_REQUIRED");
        assertThat(detail.recovery().ci().message())
                .isEqualTo("A proven base-owned CI failure needs approval");
        assertThat(detail.recovery().ci().actions()).containsExactly(
                "START_BASE_REPAIR", "MANUAL_TAKEOVER", "STOP_AUTOMATION");
        assertThat(service.detail("task-2", "remote-stage-2")
                .recovery().ci()).isNull();
    }

    @Test
    void detailProjectsTerminalCiTurnFailuresWithoutBudgetActions()
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
                VALUES ('episode-turn-failure', 'remote-stage-1', 'task-1',
                    1, 1, 'binding-1', 'ci-evaluation-1-1', 'head-1',
                    'base-1', 'TASK_DETERMINISTIC', 'OPEN', 0, 3, 1, 3, 95)
                """);
        jdbc.update("""
                UPDATE ci_repair_episode SET status = 'FIXING'
                 WHERE id = 'episode-turn-failure' AND status = 'OPEN'
                """);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('malformed-turn-blocker', 'task-1', 'remote-stage-1',
                    'EPISODE', 'episode-turn-failure', 'head-1:base-1',
                    'CI_REPAIR_OUTPUT_MALFORMED', 'OPEN',
                    '{"choices":["MANUAL_TAKEOVER","STOP_AUTOMATION"]}', 96)
                """);

        StageDetailData malformed = service.detail(
                "task-1", "remote-stage-1");

        assertThat(malformed.recovery().ci().blockerType())
                .isEqualTo("CI_REPAIR_OUTPUT_MALFORMED");
        assertThat(malformed.recovery().ci().message())
                .isEqualTo("CI repair returned malformed strict output");
        assertThat(malformed.recovery().ci().actions()).containsExactly(
                "MANUAL_TAKEOVER", "STOP_AUTOMATION");

        jdbc.update("""
                UPDATE task_blocker
                   SET status = 'RESOLVED', resolved_at_ms = 97,
                       resolution_evidence = 'test replacement'
                 WHERE id = 'malformed-turn-blocker'
                """);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('failed-turn-blocker', 'task-1', 'remote-stage-1',
                    'EPISODE', 'episode-turn-failure', 'head-1:base-1',
                    'CI_REPAIR_TURN_FAILED', 'OPEN',
                    '{"choices":["MANUAL_TAKEOVER","STOP_AUTOMATION"]}', 98)
                """);

        StageDetailData failed = service.detail("task-1", "remote-stage-1");

        assertThat(failed.recovery().ci().blockerType())
                .isEqualTo("CI_REPAIR_TURN_FAILED");
        assertThat(failed.recovery().ci().message())
                .isEqualTo("CI repair execution failed on this exact subject");
        assertThat(failed.recovery().ci().actions()).containsExactly(
                "MANUAL_TAKEOVER", "STOP_AUTOMATION");
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

    private void insertStalledLocalStageTurn(int number, String error)
            throws Exception
    {
        insertStalledLocalStageTurn(number, error, "SUCCEEDED");
    }

    private void insertStalledLocalStageTurn(
            int number, String error, String outcome)
            throws Exception
    {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                        UPDATE stage
                           SET version = 1, checkpoint = 'COMPLETED',
                               completed_at_ms = 110, end_reason = 'NORMAL'
                         WHERE id = 'remote-stage-%1$s'
                        """.formatted(number));
                DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                        INSERT INTO stage(
                            id, task_id, kind, generation, version,
                            checkpoint, opened_at_ms)
                        VALUES ('retry-local-stage-%1$s', 'task-%1$s',
                            'LOCAL_DEVELOPMENT', 2, 0, 'IMPLEMENTING', 111)
                        """.formatted(number));
                DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                        UPDATE task_current_stage
                           SET stage_id = 'retry-local-stage-%1$s',
                               stage_generation = 2
                         WHERE task_id = 'task-%1$s'
                        """.formatted(number));
                DevelopmentFlowRemoteProtocolFixture.execute(connection, """
                        INSERT INTO local_development_stage(
                            stage_id, task_id, generation, opened_for_epoch)
                        VALUES ('retry-local-stage-%1$s', 'task-%1$s', 2, 1)
                        """.formatted(number));
                connection.commit();
            }
            catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
        }
        var subject = jdbc.queryForMap("""
                SELECT code_fingerprint, head_sha, base_sha
                  FROM task_current_code_subject_v230 WHERE task_id = ?
                """, "task-" + number);
        String fingerprint = (String) subject.get("code_fingerprint");
        String head = (String) subject.get("head_sha");
        String base = (String) subject.get("base_sha");
        String lastError = "SUCCEEDED".equals(outcome) && !error.isBlank()
                ? DispatchTicket.resultProtocolFailure(error)
                : error;
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 2, 'IMPLEMENT_LOCAL_PLAN', 'QUEUED', ?, 1, 1,
                    ?, ?, ?, 'API', '{"prompt":"implement"}', 112)
                """, "retry-turn-" + number, "retry-local-stage-" + number,
                "retry-operation-" + number, fingerprint, head, base);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, 1, 2, 'IMPLEMENTATION', 'IMMEDIATE',
                    ?, 'test', 112)
                """, "retry-request-" + number, "retry-command-" + number,
                "retry-turn-" + number, "task-" + number,
                "retry-local-stage-" + number, "0".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN',
                    'STAGE_TURN', ?, 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', ?, 1, ?, 2, 1, ?, ?, ?,
                    'REQUESTED', 112)
                """, "retry-ticket-" + number, "retry-operation-" + number,
                "retry-turn-" + number, "task-" + number,
                "retry-local-stage-" + number, fingerprint, head, base);
        jdbc.update("""
                UPDATE stage SET version = 1
                 WHERE id = ? AND version = 0
                """, "retry-local-stage-" + number);
        jdbc.update("""
                INSERT INTO stage_initial_result_request(
                    id, stage_id, task_id, stage_kind, command_id, cause, actor,
                    expected_task_epoch, expected_stage_generation,
                    expected_stage_version, returned_stage_version, checkpoint,
                    turn_owner_kind, turn_id, pending_task_epoch,
                    pending_stage_id, pending_stage_generation,
                    pending_operation_id, pending_attempt,
                    pending_code_fingerprint, pending_head_sha,
                    pending_base_sha, requested_at_ms)
                VALUES (?, ?, ?, 'LOCAL_DEVELOPMENT', ?,
                    'REQUEST_LOCAL_RESULT', 'test', 1, 2, 0, 1,
                    'IMPLEMENTING', 'STAGE_TURN', ?, 1, ?, 2, ?, 1,
                    ?, ?, ?, 113)
                """, "retry-initial-" + number,
                "retry-local-stage-" + number, "task-" + number,
                "retry-initial-command-" + number, "retry-turn-" + number,
                "retry-local-stage-" + number, "retry-operation-" + number,
                fingerprint, head, base);
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = 1, status = 'RESULT_PENDING',
                       infrastructure_attempts = 1,
                       pending_result_outcome = ?,
                       pending_result_payload = 'malformed',
                       pending_result_evidence = 'provider completed',
                       pending_result_task_epoch = 1,
                       pending_result_stage_id = ?,
                       pending_result_stage_generation = 2,
                       pending_result_operation_id = ?,
                       pending_result_attempt = 1,
                       pending_result_expected_code_fingerprint = ?,
                       pending_result_expected_head_sha = ?,
                       pending_result_expected_base_sha = ?,
                       last_error = ?
                 WHERE id = ?
                """, outcome, "retry-local-stage-" + number,
                "retry-operation-" + number, fingerprint, head, base,
                lastError,
                "retry-ticket-" + number);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES (?, ?, 1, 'openai', ?, 114, 115, '{}')
                """, "retry-execution-" + number,
                "retry-ticket-" + number, outcome);
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
