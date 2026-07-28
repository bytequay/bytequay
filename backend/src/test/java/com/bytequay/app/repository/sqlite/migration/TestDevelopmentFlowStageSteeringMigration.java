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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer;
import com.bytequay.app.developmentflow.stage.RemoteObservationRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxItem;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.InboxKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.Provenance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.NewTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Predecessor;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskControlMaintainer;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import java.util.Locale;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertFailedCi;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowStageSteeringMigration
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);

    @TempDir
    private Path tempDir;

    @Test
    void remoteOwnersConsumeTheirOwnHandoffsAndSupersedeLatePredecessors()
            throws Exception
    {
        Fixture fixture = fixture();

        fixture.commands().executeVoid("task-1", () -> {
            fixture.jdbc().update("""
                    INSERT INTO ci_repair_episode(
                        id, remote_development_stage_id, task_id, task_epoch,
                        stage_generation, remote_pr_binding_id,
                        failed_ci_evaluation_id, subject_head_sha,
                        subject_base_sha, classification, status,
                        rerun_limit, fix_attempt_limit, delivery_retry_limit,
                        push_limit, opened_at_ms)
                    VALUES ('ci-episode', 'remote-stage-1', 'task-1', 1, 1,
                        'binding-1', 'ci-evaluation-1-1', 'head-1', 'base-1',
                        'TASK_DETERMINISTIC', 'OPEN', 0, 2, 2, 2, 900)
                    """);
            var episode = fixture.remote().requireCiEpisode(
                    "task-1", "ci-episode");
            var context = fixture.repairs().requireContext(
                    "task-1", "remote-stage-1");
            fixture.repairs().insertCiStageTurn(
                    context, episode, "{}", "API", 2, NOW);
        });
        consumeRepair(fixture, 1, "CI_REPAIR", "REMOTE_CI_REPAIR");

        fixture.commands().executeVoid("task-2", () -> {
            fixture.jdbc().update("""
                    INSERT INTO task_branch_sync_policy_revision(
                        id, task_id, revision, enabled, schedule, source,
                        attempt_limit, command_id, actor, created_at_ms)
                    VALUES ('branch-policy-2', 'task-2', 1, 1, 'nightly',
                        'USER_CONFIGURED', 2, 'branch-policy-command',
                        'user', 999)
                    """);
            var context = fixture.remote().requireRemoteContext(
                    "task-2", "remote-stage-2");
            var episode = fixture.remote().insertBranchEpisode(
                    context, "branch-command", "base-target",
                    "branch-policy-2", "USER_CONFIGURED", 2, NOW);
            fixture.jdbc().update("""
                    UPDATE branch_sync_effect_step
                    SET status = 'SUCCEEDED', attempt_count = 1,
                        evidence = 'complete', completed_at_ms = 1001
                    WHERE branch_sync_episode_id = ? AND ordinal IN (1, 2)
                    """, episode.id());
            var step = fixture.remote().requireBranchStep(episode.id(), 3);
            var repairContext = fixture.repairs().requireContext(
                    "task-2", "remote-stage-2");
            fixture.repairs().insertBranchStageTurn(
                    repairContext, episode, step, "{}", "API", 2, NOW);
        });
        consumeRepair(
                fixture, 2, "BRANCH_REPAIR", "BRANCH_CONFLICT_REPAIR");

        seedFeedbackPredecessor(fixture);
        Request feedback = insertRequest(
                fixture, 3, "feedback-steering", "ADDRESS_REMOTE_FEEDBACK");
        cancelTicket(fixture.jdbc(), feedback.predecessor().ticketId());
        fixture.commands().executeVoid("task-3", () -> {
            var previous = fixture.feedback().requireStageTurnContext(
                    feedback.predecessor().ownerId(),
                    feedback.predecessor().operationId());
            fixture.feedback().supersedeUndeliveredStageTurn(previous, NOW);
            String turnId = "feedback-successor-turn";
            String operationId = "feedback-successor-operation";
            var next = fixture.feedback().insertTurn(new NewTurn(
                    "feedback-successor-request", "batch-3", turnId,
                    operationId, "feedback-successor-ticket", 2,
                    feedback.predecessor().ownerId(), "workspace-1", "trunk-1",
                    "task-3", 1, "remote-stage-3", 1,
                    "fingerprint-3", "head-3", "base-3", "API", 2, "{}",
                    "f".repeat(64), "test", NOW));
            fixture.steering().markAdmitted(
                    feedback.id(), "STAGE_TURN", next.turnId(),
                    next.operationId(), NOW);
        });

        assertConsumed(fixture.jdbc(), "feedback-steering", "REMOTE_FEEDBACK");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM stage_turn WHERE id = ?
                """, String.class, feedback.predecessor().ownerId()))
                .isEqualTo("SUPERSEDED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_stage_turn_request
                WHERE remote_feedback_batch_id = 'batch-3'
                  AND semantic_attempt = 2
                """, Integer.class)).isOne();
    }

    @Test
    void answeredRemoteFeedbackWaitAdmitsOnlyItsExactSuccessorAcrossRestart()
            throws Exception
    {
        Fixture fixture = fixture();
        seedFeedbackPredecessor(fixture);
        V2UserWaitStore waits = new V2UserWaitStore(fixture.jdbc());
        ActiveAgentContextRegistry.TypedOwner owner =
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.STAGE_TURN,
                        "feedback-turn-1", "feedback-operation-1");
        fixture.commands().executeVoid("task-3", () -> {
            fixture.jdbc().update("""
                    UPDATE stage_turn
                    SET status = 'RUNNING', started_at_ms = 1000
                    WHERE id = 'feedback-turn-1' AND status = 'QUEUED'
                    """);
            waits.insertQuestion(
                    owner, "feedback-question", "feedback-question-call",
                    "Continue addressing this feedback?", null, "[]", true,
                    NOW.plusMillis(1));
            waits.answerQuestion(
                    "feedback-question", 0, null, "Continue", "user",
                    NOW.plusMillis(2));
            markTicketResultPending(fixture.jdbc(), "feedback-ticket-1");
            waits.recordUserWait(
                    owner, "QUESTION", "feedback-question", "payload-digest",
                    "{\"schema\":\"TYPED_USER_WAIT_DELIVERY_V1\"}",
                    NOW.plusMillis(3));
            finishTicket(fixture.jdbc(), "feedback-ticket-1");
        });

        Predecessor predecessor = fixture.steering().findUserWaitPredecessor(
                "feedback-turn-1", "feedback-operation-1", "QUESTION",
                "feedback-question").orElseThrow();
        long stageVersion = fixture.jdbc().queryForObject(
                "SELECT version FROM stage WHERE id = 'remote-stage-3'",
                Long.class);
        Request request = new Request(
                "feedback-wait-steering", "feedback-wait-command", "task-3", 1,
                "remote-stage-3", StageKind.REMOTE_DEVELOPMENT, 1,
                stageVersion, StageCheckpoint.ADDRESSING_REMOTE_FEEDBACK,
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE,
                "Continue", "b".repeat(64), predecessor, "PENDING",
                null, null, null, "user", NOW.plusMillis(4));
        fixture.commands().executeVoid("task-3", () -> {
            fixture.steering().insert(request, List.of());
            fixture.steering().insertUserWaitLink(
                    request.id(), "QUESTION", "feedback-question", predecessor);
        });

        assertThatThrownBy(() -> fixture.commands().executeVoid("task-3", () ->
                fixture.feedback().insertTurn(feedbackContinuation(
                        "stale-head", predecessor.ownerId()))))
                .isInstanceOf(DataAccessException.class);
        fixture.commands().executeVoid("task-3", () -> {
            var next = fixture.feedback().insertTurn(feedbackContinuation(
                    "head-3", predecessor.ownerId()));
            fixture.steering().markAdmitted(
                    request.id(), "STAGE_TURN", next.turnId(),
                    next.operationId(), NOW.plusMillis(5));
        });

        SqliteStageSteeringStore restarted = new SqliteStageSteeringStore(
                new JdbcTemplate(fixture.dataSource()));
        assertThat(restarted.userWaitSuccessor(
                "QUESTION", "feedback-question"))
                .contains("feedback-wait-successor-turn");
        assertThat(fixture.jdbc().queryForMap("""
                SELECT request.status, handoff.status AS handoff_status,
                       continuation.successor_turn_id
                FROM stage_steering_request_v257 request
                JOIN remote_stage_steering_handoff_v257 handoff
                  ON handoff.request_id = request.id
                JOIN stage_turn_user_wait_continuation_v265 continuation
                  ON continuation.request_id = request.id
                WHERE request.id = 'feedback-wait-steering'
                """))
                .containsEntry("status", "ADMITTED")
                .containsEntry("handoff_status", "CONSUMED")
                .containsEntry(
                        "successor_turn_id", "feedback-wait-successor-turn");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_stage_turn_request
                WHERE remote_feedback_batch_id = 'batch-3'
                  AND semantic_attempt = 2
                """, Integer.class)).isOne();
    }

    @Test
    void remoteFeedbackResumeRearmsExactlyOnceAndRollsBackAfterPreparedFault()
            throws Exception
    {
        Fixture fixture = fixture();
        seedFeedbackPredecessor(fixture);
        var previous = fixture.feedback().requireStageTurnContext(
                "feedback-turn-1", "feedback-operation-1");
        fixture.commands().executeVoid("task-3", () ->
                fixture.feedback().finishStageTurn(
                        previous, "CANCELED", "paused", NOW));
        cancelTicket(fixture.jdbc(), "feedback-ticket-1");
        fixture.jdbc().update("""
                DELETE FROM dispatch_ticket
                WHERE task_id = 'task-3' AND id <> 'feedback-ticket-1'
                """);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(fixture.dataSource());
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        TaskManager tasks = new TaskManager(
                commands, taskStore(fixture.jdbc(), transactions));
        SqliteTaskControlRuntimeStore controlStore =
                new SqliteTaskControlRuntimeStore(fixture.jdbc(), transactions);
        TaskControlHandoff controls = new TaskControlHandoff(commands, tasks);
        SqliteStageResumeRearmStore rearms =
                new SqliteStageResumeRearmStore(fixture.jdbc());
        TaskResumeOwner owner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return StageKind.REMOTE_DEVELOPMENT;
            }

            @Override
            public Acceptance accept(TaskResumeOwner.Request request)
            {
                return rearms.accept(request, kind());
            }
        };
        TaskControlMaintainer withoutOwner = new TaskControlMaintainer(
                controlStore, controls, List.of(), List.of(), ignored -> {});
        TaskControlMaintainer withOwner = new TaskControlMaintainer(
                controlStore, controls, List.of(owner), List.of(), ignored -> {});

        tasks.requestPause(new TaskManager.Command(
                "pause-remote-feedback", "user", "task-3", 1,
                taskVersion(fixture.jdbc(), "task-3")));
        withoutOwner.maintain(NOW.plusMillis(1));
        tasks.requestResume(new TaskManager.Command(
                "resume-remote-feedback", "user", "task-3", 1,
                taskVersion(fixture.jdbc(), "task-3")));
        withoutOwner.maintain(NOW.plusMillis(2));
        assertThat(taskLifecycle(fixture.jdbc(), "task-3"))
                .isEqualTo(TaskLifecycle.RESUMING.name());
        withOwner.maintain(NOW.plusMillis(3));
        assertThat(taskLifecycle(fixture.jdbc(), "task-3"))
                .isEqualTo(TaskLifecycle.ACTIVE.name());

        SqliteStageResumeRearmStore.Intent intent = rearms
                .pending(StageKind.REMOTE_DEVELOPMENT, 10).stream()
                .filter(candidate -> candidate.taskId().equals("task-3"))
                .findFirst().orElseThrow();
        fixture.jdbc().execute("""
                CREATE TRIGGER fail_remote_resume_ticket
                BEFORE INSERT ON dispatch_ticket
                WHEN EXISTS (
                    SELECT 1 FROM stage_resume_rearm_successor_v257 successor
                    WHERE successor.operation_id = NEW.operation_id
                      AND successor.status = 'PREPARED')
                BEGIN SELECT RAISE(ABORT, 'injected Remote resume failure'); END
                """);
        assertThatThrownBy(() -> commands.executeVoid("task-3", () ->
                rearms.materializeRemoteFeedback(intent, NOW.plusMillis(4))))
                .isInstanceOf(DataAccessException.class);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_resume_rearm_successor_v257
                WHERE handoff_id = ?
                """, Integer.class, intent.handoffId())).isZero();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_stage_turn_request
                WHERE remote_feedback_batch_id = 'batch-3'
                  AND semantic_attempt = 2
                """, Integer.class)).isZero();

        fixture.jdbc().execute("DROP TRIGGER fail_remote_resume_ticket");
        commands.executeVoid("task-3", () ->
                rearms.materializeRemoteFeedback(intent, NOW.plusMillis(5)));
        assertThat(fixture.jdbc().queryForMap("""
                SELECT intent.status, successor.status AS successor_status,
                       successor.owner_kind, successor.semantic_attempt
                FROM stage_resume_rearm_intent_v257 intent
                JOIN stage_resume_rearm_successor_v257 successor
                  ON successor.handoff_id = intent.handoff_id
                WHERE intent.handoff_id = ?
                """, intent.handoffId()))
                .containsEntry("status", "MATERIALIZED")
                .containsEntry("successor_status", "ARMED")
                .containsEntry("owner_kind", "STAGE_TURN")
                .containsEntry("semantic_attempt", 2);
        assertThat(new SqliteStageResumeRearmStore(
                new JdbcTemplate(fixture.dataSource()))
                .pending(StageKind.REMOTE_DEVELOPMENT, 10))
                .noneMatch(candidate -> candidate.taskId().equals("task-3"));
    }

    @Test
    void remoteObservationWaitResumeRearmsObserverWithoutWorkerCapacity()
            throws Exception
    {
        Fixture fixture = fixture();
        fixture.jdbc().update(
                "DELETE FROM dispatch_ticket WHERE task_id = 'task-1'");
        Resume resume = acceptResume(
                fixture, "task-1", StageKind.REMOTE_DEVELOPMENT,
                NOW.plusMillis(10));
        RemoteObservationConsumer consumer = (candidate, acceptance) -> {
            acceptance.accept();
            return RemoteObservationConsumer.Consumption.ACCEPTED;
        };
        RemoteObservationRuntimeCoordinator observations =
                new RemoteObservationRuntimeCoordinator(
                        resume.commands(), fixture.remote(), consumer,
                        new ObjectMapper(),
                        Clock.fixed(NOW.plusMillis(11), ZoneOffset.UTC));

        resume.commands().executeVoid("task-1", () ->
                resume.rearms().materializeObservation(
                        resume.intent(), observations.requestObservationInCommand(
                                "task-1", "remote-stage-1"),
                        NOW.plusMillis(11)));

        assertThat(fixture.jdbc().queryForMap("""
                SELECT intent.status, successor.status AS successor_status,
                       successor.owner_kind, operation.status AS operation_status,
                       ticket.status AS ticket_status
                FROM stage_resume_rearm_intent_v257 intent
                JOIN stage_resume_rearm_successor_v257 successor
                  ON successor.handoff_id = intent.handoff_id
                JOIN remote_observation_operation operation
                  ON operation.id = successor.owner_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = successor.dispatch_ticket_id
                WHERE intent.task_id = 'task-1'
                """))
                .containsEntry("status", "MATERIALIZED")
                .containsEntry("successor_status", "ARMED")
                .containsEntry("owner_kind", "REMOTE_OBSERVATION")
                .containsEntry("operation_status", "DISPATCHED")
                .containsEntry("ticket_status", "REQUESTED");
        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM capacity_lease", Integer.class)).isZero();
    }

    private static void consumeRepair(
            Fixture fixture, int taskNumber, String family, String purpose)
    {
        String requestId = family.toLowerCase(Locale.ROOT) + "-steering";
        Request request = insertRequest(fixture, taskNumber, requestId, purpose);
        cancelTicket(fixture.jdbc(), request.predecessor().ticketId());
        fixture.commands().executeVoid("task-" + taskNumber, () -> {
            var next = fixture.repairs().insertSteeringTurn(
                    request, "{}", "API", 2, NOW);
            fixture.steering().markAdmitted(
                    request.id(), "STAGE_TURN", next.turnId(),
                    next.operationId(), NOW);
        });

        assertConsumed(fixture.jdbc(), requestId, family);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM stage_turn WHERE id = ?
                """, String.class, request.predecessor().ownerId()))
                .isEqualTo("SUPERSEDED");
        String operationTable = family.equals("CI_REPAIR")
                ? "ci_repair_operation" : "branch_sync_dispatch_operation";
        assertThat(fixture.jdbc().queryForObject(
                "SELECT status FROM " + operationTable + " WHERE operation_id = ?",
                String.class, request.predecessor().operationId()))
                .isEqualTo("SUPERSEDED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM remote_repair_steering_turn_v257
                WHERE request_id = ? AND status = 'REQUESTED'
                """, Integer.class, requestId)).isOne();
    }

    private static Request insertRequest(
            Fixture fixture, int taskNumber, String requestId, String purpose)
    {
        String taskId = "task-" + taskNumber;
        String stageId = "remote-stage-" + taskNumber;
        Predecessor predecessor = fixture.steering()
                .findActiveRemotePredecessor(taskId, stageId, 1, 1)
                .orElseThrow();
        assertThat(predecessor.purpose()).isEqualTo(purpose);
        long version = fixture.jdbc().queryForObject(
                "SELECT version FROM stage WHERE id = ?", Long.class, stageId);
        StageCheckpoint checkpoint = StageCheckpoint.valueOf(
                fixture.jdbc().queryForObject(
                        "SELECT checkpoint FROM stage WHERE id = ?",
                        String.class, stageId));
        Request request = new Request(
                requestId, requestId + "-command", taskId, 1, stageId,
                StageKind.REMOTE_DEVELOPMENT, 1, version, checkpoint,
                V2StageSteeringControl.Mode.APPEND, "Please adjust this",
                "a".repeat(64), predecessor, "PENDING", null, null, null,
                "test", NOW.minusMillis(1));
        fixture.commands().executeVoid(taskId, () ->
                fixture.steering().insert(request, List.of()));
        return request;
    }

    private static void seedFeedbackPredecessor(Fixture fixture)
    {
        InboxItem item = new InboxItem(
                "inbox-3", "task-3", 1, "remote-stage-3", 1, "binding-3",
                "snapshot-3-1", InboxKind.TOP_LEVEL_COMMENT, "comment-key-3",
                1, "head-3", "base-3", "reviewer", Provenance.EXTERNAL,
                false, null, "comment-3", null, null, "please fix",
                SqliteRemoteDevelopmentRuntimeStore.digest("please fix"), null,
                null, null, NOW.minusMillis(10), "raw");
        fixture.commands().executeVoid("task-3", () -> {
            fixture.feedbackSource().ingest(item);
            assertThat(fixture.feedbackSource().freezeNextBatch(
                    "batch-3", "task-3", "remote-stage-3", false,
                    "test", NOW.minusMillis(9))).isPresent();
            fixture.jdbc().update("""
                    UPDATE stage SET version = version + 1,
                        checkpoint = 'ADDRESSING_REMOTE_FEEDBACK'
                    WHERE id = 'remote-stage-3'
                    """);
            fixture.feedback().markAddressing("batch-3");
            fixture.feedback().insertTurn(new NewTurn(
                    "feedback-request-1", "batch-3", "feedback-turn-1",
                    "feedback-operation-1", "feedback-ticket-1", 1, null,
                    "workspace-1", "trunk-1", "task-3", 1,
                    "remote-stage-3", 1, "fingerprint-3", "head-3", "base-3",
                    "API", 2, "{}", "e".repeat(64), "test",
                    NOW.minusMillis(8)));
        });
    }

    private static NewTurn feedbackContinuation(
            String headSha, String predecessorTurnId)
    {
        return new NewTurn(
                "feedback-wait-successor-request", "batch-3",
                "feedback-wait-successor-turn",
                "feedback-wait-successor-operation",
                "feedback-wait-successor-ticket", 2, predecessorTurnId,
                "workspace-1", "trunk-1", "task-3", 1,
                "remote-stage-3", 1, "fingerprint-3", headSha, "base-3",
                "API", 2, "{}", "d".repeat(64), "test", NOW.plusMillis(5));
    }

    private static void markTicketResultPending(JdbcTemplate jdbc, String ticketId)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = stage_id,
                    pending_result_stage_generation = stage_generation,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint =
                        expected_code_fingerprint,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE id = ? AND status = 'REQUESTED'
                """, ticketId);
    }

    private static void finishTicket(JdbcTemplate jdbc, String ticketId)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'SUCCEEDED',
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
                    delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = '{}', completed_at_ms = 1004
                WHERE id = ? AND status = 'RESULT_PENDING'
                """, ticketId);
    }

    private static void cancelTicket(JdbcTemplate jdbc, String ticketId)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'CANCELED',
                    cancel_requested_at_ms = 1000,
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'steering won', completed_at_ms = 1000
                WHERE id = ?
                """, ticketId);
    }

    private static void assertConsumed(
            JdbcTemplate jdbc, String requestId, String family)
    {
        assertThat(jdbc.queryForMap("""
                SELECT request.status, handoff.status AS handoff_status,
                       handoff.owner_family
                FROM stage_steering_request_v257 request
                JOIN remote_stage_steering_handoff_v257 handoff
                  ON handoff.request_id = request.id
                WHERE request.id = ?
                """, requestId))
                .containsEntry("status", "ADMITTED")
                .containsEntry("handoff_status", "CONSUMED")
                .containsEntry("owner_family", family);
    }

    private static TaskManager.Store taskStore(
            JdbcTemplate jdbc, DataSourceTransactionManager transactions)
    {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(
                    DataSourceTransactionManager.class, () -> transactions);
            context.scan("com.bytequay.app.developmentflow.task.persistence");
            context.refresh();
            return context.getBean(TaskManager.Store.class);
        }
    }

    private static long taskVersion(JdbcTemplate jdbc, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = ?",
                Long.class, taskId);
    }

    private static String taskLifecycle(JdbcTemplate jdbc, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT lifecycle_state FROM tasks WHERE id = ?",
                String.class, taskId);
    }

    private static Resume acceptResume(
            Fixture fixture, String taskId, StageKind kind, Instant now)
    {
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(fixture.dataSource());
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        TaskManager tasks = new TaskManager(
                commands, taskStore(fixture.jdbc(), transactions));
        SqliteTaskControlRuntimeStore controlStore =
                new SqliteTaskControlRuntimeStore(fixture.jdbc(), transactions);
        TaskControlHandoff controls = new TaskControlHandoff(commands, tasks);
        SqliteStageResumeRearmStore rearms =
                new SqliteStageResumeRearmStore(fixture.jdbc());
        TaskResumeOwner owner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return kind;
            }

            @Override
            public Acceptance accept(TaskResumeOwner.Request request)
            {
                return rearms.accept(request, kind());
            }
        };
        TaskControlMaintainer withoutOwner = new TaskControlMaintainer(
                controlStore, controls, List.of(), List.of(), ignored -> {});
        TaskControlMaintainer withOwner = new TaskControlMaintainer(
                controlStore, controls, List.of(owner), List.of(), ignored -> {});
        tasks.requestPause(new TaskManager.Command(
                "pause-" + taskId, "user", taskId, 1,
                taskVersion(fixture.jdbc(), taskId)));
        withoutOwner.maintain(now);
        tasks.requestResume(new TaskManager.Command(
                "resume-" + taskId, "user", taskId, 1,
                taskVersion(fixture.jdbc(), taskId)));
        withoutOwner.maintain(now.plusMillis(1));
        withOwner.maintain(now.plusMillis(2));
        SqliteStageResumeRearmStore.Intent intent = rearms.pending(kind, 10).stream()
                .filter(candidate -> candidate.taskId().equals(taskId))
                .findFirst().orElseThrow();
        return new Resume(commands, rearms, intent);
    }

    private Fixture fixture()
            throws Exception
    {
        Path file = tempDir.resolve("stage-steering.db");
        String url = "jdbc:sqlite:" + file;
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            seedPublishedRemoteTask(connection, 2);
            seedPublishedRemoteTask(connection, 3);
        }
        migrate(url, "232");
        try (Connection connection = connect(url)) {
            for (int task = 1; task <= 3; task++) {
                insertRemoteOwner(connection, task);
                insertCiPolicy(connection, task);
                insertSnapshot(connection, task, 1, "head-" + task,
                        "base-" + task, "OPEN", "MERGEABLE");
                acceptSnapshot(connection, task, 1, "head-" + task,
                        "base-" + task);
            }
            insertFailedCi(connection, 1, 1, "head-1", "base-1");
        }
        migrate(url, "265");

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url + "?foreign_keys=ON&busy_timeout=30000");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        return new Fixture(
                dataSource, jdbc, commands, new SqliteStageSteeringStore(jdbc),
                new SqliteRemoteRuntimeStore(jdbc),
                new SqliteRemoteRepairTurnStore(jdbc),
                new SqliteRemoteDevelopmentRuntimeStore(jdbc),
                new SqliteRemoteFeedbackLoopStore(jdbc));
    }

    private record Fixture(
            SQLiteDataSource dataSource,
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteStageSteeringStore steering,
            SqliteRemoteRuntimeStore remote,
            SqliteRemoteRepairTurnStore repairs,
            SqliteRemoteDevelopmentRuntimeStore feedbackSource,
            SqliteRemoteFeedbackLoopStore feedback) {}

    private record Resume(
            TaskCommandExecutor commands,
            SqliteStageResumeRearmStore rearms,
            SqliteStageResumeRearmStore.Intent intent) {}
}
