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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.persistence.V2UserWaitStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskControlMaintainer;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskReplanMaintainer;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationHandoff;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@ExtendWith(SqliteTestPools.class)
class TestPlanRuntimeCoordinator
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final String WORKSPACE = "workspace-plan";
    private static final String TRUNK = "trunk-plan";
    private static final String REPOSITORY = "acme/widget";

    @TempDir
    private Path tempDir;

    @Test
    void acceptsProvisioningOnceAndRestartsWithTheSamePlanTurn()
            throws Exception
    {
        Path repositoryRoot = tempDir.resolve("repo").toAbsolutePath();
        DataSource dataSource = database("plan-runtime.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        Runtime first = runtime(dataSource);
        TaskCreationHandoff.Result created = first.creation().create(
                creationCommand(repositoryRoot));
        String taskId = created.task().task().id();
        String operationId = created.task().receipt().operationId();
        String worktree = created.task().target().worktreePath().toString();
        ProvisionTaskOperationHandler.ProvisionEvidence evidence =
                new ProvisionTaskOperationHandler.ProvisionEvidence(
                        "PROVISION_TASK_V1", operationId, taskId, 1,
                        ProvisionTaskOperationHandler.BaseSource.FRESH_REMOTE_BASE,
                        REPOSITORY, created.task().target().branchName(), worktree,
                        "base-sha", "base-sha", "code-fingerprint");
        String evidenceJson = new ObjectMapper().writeValueAsString(evidence);
        markProvisionResultPending(jdbc, operationId, evidenceJson);

        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, taskId,
                PlanRuntimeCoordinator.PROVISION_CALLBACK);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, operationId, 1, null, null, null);
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, SUCCEEDED, evidenceJson, evidenceJson, null);

        DispatchTicket.DeliveryReceipt accepted =
                first.plan().deliverProvisioning(owner, fence, result);

        assertThat(accepted.acceptance()).isEqualTo(ACCEPTED);
        assertThat(jdbc.queryForMap("""
                SELECT task.lifecycle_state, task.aggregate_version,
                       operation.status AS provision_status,
                       stage.kind, stage.version, stage.checkpoint,
                       request.turn_owner_kind, request.pending_operation_id,
                       turn.purpose, turn.status AS turn_status,
                       ticket.status AS ticket_status
                FROM tasks task
                JOIN provision_task_operation operation ON operation.task_id = task.id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN stage_initial_result_request request ON request.stage_id = stage.id
                JOIN task_turn turn ON turn.id = request.turn_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE task.id = ?
                """, taskId))
                .containsEntry("lifecycle_state", "ACTIVE")
                .containsEntry("aggregate_version", 1)
                .containsEntry("provision_status", "ACCEPTED")
                .containsEntry("kind", "PLAN")
                .containsEntry("version", 1)
                .containsEntry("checkpoint", "DRAFTING")
                .containsEntry("turn_owner_kind", "TASK_TURN")
                .containsEntry("purpose", "PLAN_DRAFT")
                .containsEntry("turn_status", "REQUESTED")
                .containsEntry("ticket_status", "REQUESTED");
        assertThat(count(jdbc, "task_code_identity")).isOne();
        assertThat(count(jdbc, "task_provision_plan_receipt")).isOne();
        assertThat(countWhere(jdbc, "outbox",
                "aggregate_kind = 'DISPATCH_TICKET' AND status = 'PENDING'"))
                .isEqualTo(2);

        Runtime restarted = runtime(dataSource);
        DispatchTicket.DeliveryReceipt replay =
                restarted.plan().deliverProvisioning(owner, fence, result);
        assertThat(replay.acceptance()).isEqualTo(ACCEPTED);
        assertThat(replay.evidenceJson()).isEqualTo(accepted.evidenceJson());
        assertThat(count(jdbc, "stage")).isOne();
        assertThat(count(jdbc, "task_turn")).isOne();
        assertThat(count(jdbc, "dispatch_ticket")).isEqualTo(2);
        assertThat(count(jdbc, "stage_initial_result_request")).isOne();

        DispatchTicket.OperationFence stale = new DispatchTicket.OperationFence(
                1L, null, null, operationId, 2, null, null, null);
        DispatchTicket.DeliveryReceipt superseded = restarted.plan()
                .deliverProvisioning(owner, fence,
                        new DispatchTicket.DispatchResult(
                                stale, SUCCEEDED, evidenceJson, evidenceJson, null));
        assertThat(superseded.acceptance()).isEqualTo(SUPERSEDED);
        assertThat(count(jdbc, "task_turn")).isOne();
    }

    @Test
    void answeredPlanWaitAtomicallyAdmitsOneStableSuccessorAcrossRestart()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-user-wait.db");
        Runtime first = runtime(bootstrap.dataSource());
        RunningTurn predecessor = startCurrentTurn(
                bootstrap.jdbc(), bootstrap.taskId());
        bootstrap.jdbc().update("""
                UPDATE task_turn
                SET status = 'RUNNING', started_at_ms = ?
                WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                """, NOW.toEpochMilli(), predecessor.turnId(),
                predecessor.operationId());
        ActiveAgentContextRegistry.TypedOwner owner =
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        predecessor.turnId(), predecessor.operationId());
        V2UserWaitStore waits = new V2UserWaitStore(bootstrap.jdbc());
        waits.insertQuestion(
                owner, "plan-question", "plan-question-call",
                "Continue with this Plan?", null, "[]", true,
                NOW.plusSeconds(1));
        waits.answerQuestion(
                "plan-question", 0, null, "Proceed", "user",
                NOW.plusSeconds(2));
        bootstrap.jdbc().update("""
                UPDATE agent_execution
                SET provider_session_id = 'plan-session-1'
                WHERE ticket_id = ? AND infrastructure_attempt = 1
                """, predecessor.ticketId());
        bootstrap.jdbc().update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES (?, 0, 'prior Plan provider trace', ?)
                """, "execution-" + predecessor.operationId(),
                NOW.plusSeconds(2).toEpochMilli());
        finishAsUserWait(bootstrap.jdbc(), predecessor);
        waits.recordUserWait(
                owner, "QUESTION", "plan-question", "payload-digest",
                "{\"schema\":\"TYPED_USER_WAIT_DELIVERY_V1\"}",
                NOW.plusSeconds(4));
        finalizePendingTickets(bootstrap.jdbc());

        String successor = first.plan().continueUserWait(
                predecessor.turnId(), predecessor.operationId(),
                "QUESTION", "plan-question", "Proceed");
        assertThat(successor).isNotEqualTo(predecessor.turnId());
        assertThat(bootstrap.jdbc().queryForMap("""
                SELECT predecessor.status AS predecessor_status,
                       successor.status AS successor_status,
                       successor.purpose, continuation.returned_stage_version,
                       stage.checkpoint
                FROM plan_turn_user_wait_continuation_v265 continuation
                JOIN task_turn predecessor
                  ON predecessor.id = continuation.predecessor_turn_id
                JOIN task_turn successor
                  ON successor.id = continuation.successor_turn_id
                JOIN stage ON stage.id = continuation.stage_id
                WHERE continuation.wait_kind = 'QUESTION'
                  AND continuation.wait_id = 'plan-question'
                """))
                .containsEntry("predecessor_status", "SUCCEEDED")
                .containsEntry("successor_status", "REQUESTED")
                .containsEntry("purpose", "PLAN_DRAFT")
                .containsEntry("returned_stage_version", 2)
                .containsEntry("checkpoint", "DRAFTING");
        JsonNode launch = new ObjectMapper().readTree(
                bootstrap.jdbc().queryForObject("""
                        SELECT launch_input FROM task_turn WHERE id = ?
                        """, String.class, successor));
        assertThat(launch.path("resumeSessionId").asText())
                .isEqualTo("plan-session-1");
        assertThat(launch.path("prompt").asText())
                .contains("User resolved the question: Proceed")
                .doesNotContain("prior Plan provider trace");
        assertThat(launch.path("fallbackPrompt").asText())
                .contains("prior Plan provider trace")
                .contains("User resolved the question: Proceed");

        String replay = runtime(bootstrap.dataSource()).plan().continueUserWait(
                predecessor.turnId(), predecessor.operationId(),
                "QUESTION", "plan-question", "Proceed");
        assertThat(replay).isEqualTo(successor);
        assertThat(countWhere(bootstrap.jdbc(), "task_turn",
                "purpose = 'PLAN_DRAFT'")).isEqualTo(2);
        assertThat(count(bootstrap.jdbc(),
                "plan_turn_user_wait_continuation_v265")).isOne();

        bootstrap.jdbc().update("""
                UPDATE task_turn SET status = 'SUCCEEDED' WHERE id = ?
                """, successor);
        assertThat(new SqlitePlanRuntimeStore(bootstrap.jdbc())
                .findUserWaitContext(
                        predecessor.turnId(), predecessor.operationId(),
                        "QUESTION", "plan-question"))
                .hasValueSatisfying(context -> assertThat(
                        context.providerSessionId()).isNull());
    }

    @Test
    void incompatibleOrMissingCliSessionStartsFreshWithTheCompleteTrace()
            throws Exception
    {
        record Case(String name, String provider, String session) {}
        for (Case test : List.of(
                new Case("provider-mismatch", "claude-code", "foreign-session"),
                new Case("missing-session", "codex", null))) {
            Bootstrapped bootstrap = bootstrap("plan-wait-" + test.name() + ".db");
            Runtime runtime = runtime(bootstrap.dataSource());
            RunningTurn predecessor = startCurrentTurn(
                    bootstrap.jdbc(), bootstrap.taskId());
            bootstrap.jdbc().update("""
                    UPDATE task_turn
                    SET status = 'RUNNING', started_at_ms = ?
                    WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                    """, NOW.toEpochMilli(), predecessor.turnId(),
                    predecessor.operationId());
            ActiveAgentContextRegistry.TypedOwner owner =
                    new ActiveAgentContextRegistry.TypedOwner(
                            DispatchTicket.OwnerKind.TASK_TURN,
                            predecessor.turnId(), predecessor.operationId());
            V2UserWaitStore waits = new V2UserWaitStore(bootstrap.jdbc());
            waits.insertQuestion(
                    owner, "plan-question", "plan-question-call",
                    "Continue with this Plan?", null, "[]", true,
                    NOW.plusSeconds(1));
            waits.answerQuestion(
                    "plan-question", 0, null, "Proceed", "user",
                    NOW.plusSeconds(2));
            bootstrap.jdbc().update("""
                    UPDATE agent_execution
                    SET provider = ?, provider_session_id = ?
                    WHERE ticket_id = ? AND infrastructure_attempt = 1
                    """, test.provider(), test.session(), predecessor.ticketId());
            String trace = "trace for " + test.name();
            bootstrap.jdbc().update("""
                    INSERT INTO agent_execution_log(
                        execution_id, seq, payload, created_at_ms)
                    VALUES (?, 0, ?, ?)
                    """, "execution-" + predecessor.operationId(), trace,
                    NOW.plusSeconds(2).toEpochMilli());
            finishAsUserWait(bootstrap.jdbc(), predecessor);
            waits.recordUserWait(
                    owner, "QUESTION", "plan-question", "payload-digest",
                    "{\"schema\":\"TYPED_USER_WAIT_DELIVERY_V1\"}",
                    NOW.plusSeconds(4));
            finalizePendingTickets(bootstrap.jdbc());

            String successor = runtime.plan().continueUserWait(
                    predecessor.turnId(), predecessor.operationId(),
                    "QUESTION", "plan-question", "Proceed");
            JsonNode launch = new ObjectMapper().readTree(
                    bootstrap.jdbc().queryForObject("""
                            SELECT launch_input FROM task_turn WHERE id = ?
                            """, String.class, successor));
            assertThat(launch.has("resumeSessionId")).isFalse();
            assertThat(launch.has("fallbackPrompt")).isFalse();
            assertThat(launch.path("prompt").asText())
                    .contains(trace)
                    .contains("User resolved the question: Proceed");
        }
    }

    @Test
    void livePlanTurnForcesFreshUserWaitContinuation()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-wait-live-consumer.db");
        Runtime runtime = runtime(bootstrap.dataSource());
        RunningTurn predecessor = startCurrentTurn(
                bootstrap.jdbc(), bootstrap.taskId());
        bootstrap.jdbc().update("""
                UPDATE task_turn
                SET status = 'RUNNING', started_at_ms = ?
                WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                """, NOW.toEpochMilli(), predecessor.turnId(),
                predecessor.operationId());
        ActiveAgentContextRegistry.TypedOwner owner =
                new ActiveAgentContextRegistry.TypedOwner(
                        DispatchTicket.OwnerKind.TASK_TURN,
                        predecessor.turnId(), predecessor.operationId());
        V2UserWaitStore waits = new V2UserWaitStore(bootstrap.jdbc());
        waits.insertQuestion(
                owner, "plan-question", "plan-question-call",
                "Continue with this Plan?", null, "[]", true,
                NOW.plusSeconds(1));
        waits.answerQuestion(
                "plan-question", 0, null, "Proceed", "user",
                NOW.plusSeconds(2));
        bootstrap.jdbc().update("""
                UPDATE agent_execution
                SET provider_session_id = 'plan-session-1'
                WHERE ticket_id = ? AND infrastructure_attempt = 1
                """, predecessor.ticketId());
        bootstrap.jdbc().update("""
                INSERT INTO agent_execution_log(
                    execution_id, seq, payload, created_at_ms)
                VALUES (?, 0, 'trace before concurrent Plan Turn', ?)
                """, "execution-" + predecessor.operationId(),
                NOW.plusSeconds(2).toEpochMilli());
        finishAsUserWait(bootstrap.jdbc(), predecessor);
        waits.recordUserWait(
                owner, "QUESTION", "plan-question", "payload-digest",
                "{\"schema\":\"TYPED_USER_WAIT_DELIVERY_V1\"}",
                NOW.plusSeconds(4));
        finalizePendingTickets(bootstrap.jdbc());
        bootstrap.jdbc().update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT 'live-plan-consumer', task_id, 'PLAN_SELF_REVIEW',
                    'REQUESTED', 'live-plan-operation', 1, task_epoch,
                    trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane,
                    json_set(launch_input,
                        '$.toolEndpoint.ownerId', 'live-plan-consumer',
                        '$.toolEndpoint.operationId', 'live-plan-operation',
                        '$.toolEndpoint.url',
                        'http://127.0.0.1/api/v2/task-turns/live-plan-consumer/operations/live-plan-operation/mcp'),
                    ?
                FROM task_turn WHERE id = ?
                """, NOW.plusSeconds(5).toEpochMilli(), predecessor.turnId());

        String successor = runtime.plan().continueUserWait(
                predecessor.turnId(), predecessor.operationId(),
                "QUESTION", "plan-question", "Proceed");
        assertThat(successor).isNotNull();
        JsonNode launch = new ObjectMapper().readTree(
                bootstrap.jdbc().queryForObject("""
                        SELECT launch_input FROM task_turn WHERE id = ?
                        """, String.class, successor));
        assertThat(launch.has("resumeSessionId")).isFalse();
        assertThat(launch.has("fallbackPrompt")).isFalse();
        assertThat(launch.path("prompt").asText())
                .contains("trace before concurrent Plan Turn");
        assertThat(count(bootstrap.jdbc(),
                "plan_turn_user_wait_continuation_v265")).isOne();
    }

    @Test
    void resumedSelfReviewFailureStillReceivesItsOrdinaryRetryAcrossRestart()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-resume-review.db");
        Runtime first = runtime(bootstrap.dataSource());
        JdbcTemplate jdbc = bootstrap.jdbc();

        RunningTurn draft = startCurrentTurn(jdbc, bootstrap.taskId());
        first.plan().recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(),
                "1. Implement safely.\n2. Verify the result.");
        deliverSucceeded(first.plan(), jdbc, draft);
        finalizePendingTickets(jdbc);

        Map<String, Object> predecessor = jdbc.queryForMap("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       ticket.id AS ticket_id
                FROM task_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE turn.task_id = ? AND turn.purpose = 'PLAN_SELF_REVIEW'
                  AND turn.status = 'REQUESTED' AND ticket.status = 'REQUESTED'
                """, bootstrap.taskId());
        jdbc.update("""
                UPDATE task_turn
                   SET status = 'CANCELED', started_at_ms = ?,
                       finished_at_ms = ?, error_message = 'paused'
                 WHERE id = ? AND status = 'REQUESTED'
                """, NOW.plusSeconds(2).toEpochMilli(),
                NOW.plusSeconds(3).toEpochMilli(), predecessor.get("turn_id"));
        jdbc.update("""
                UPDATE dispatch_ticket
                   SET version = version + 1, status = 'CANCELED',
                       cancel_requested_at_ms = ?,
                       delivery_acceptance = 'ACCEPTED',
                       delivery_evidence = 'paused', completed_at_ms = ?
                 WHERE id = ? AND status = 'REQUESTED'
                """, NOW.plusSeconds(2).toEpochMilli(),
                NOW.plusSeconds(3).toEpochMilli(), predecessor.get("ticket_id"));

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(bootstrap.dataSource());
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        TaskControlHandoff controls = new TaskControlHandoff(
                commands, first.tasks());
        SqliteStageResumeRearmStore rearms =
                new SqliteStageResumeRearmStore(jdbc);
        TaskResumeOwner owner = new TaskResumeOwner()
        {
            @Override
            public StageKind kind()
            {
                return StageKind.PLAN;
            }

            @Override
            public Acceptance accept(Request request)
            {
                return rearms.accept(request, kind());
            }
        };
        TaskControlMaintainer withoutOwner = new TaskControlMaintainer(
                first.controlStore(), controls, List.of(), List.of(), ignored -> {});
        TaskControlMaintainer withOwner = new TaskControlMaintainer(
                first.controlStore(), controls, List.of(owner), List.of(), ignored -> {});

        first.tasks().requestPause(new TaskManager.Command(
                "pause-plan-review", "user", bootstrap.taskId(), 1,
                taskVersion(jdbc, bootstrap.taskId())));
        withoutOwner.maintain(NOW.plusSeconds(4));
        first.tasks().requestResume(new TaskManager.Command(
                "resume-plan-review", "user", bootstrap.taskId(), 1,
                taskVersion(jdbc, bootstrap.taskId())));
        withoutOwner.maintain(NOW.plusSeconds(5));
        withOwner.maintain(NOW.plusSeconds(6));
        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM tasks WHERE id = ?",
                String.class, bootstrap.taskId()))
                .isEqualTo(TaskLifecycle.ACTIVE.name());

        new PlanTaskResumeOwner(rearms, commands).maintain(NOW.plusSeconds(7));
        new PlanTaskResumeOwner(
                new SqliteStageResumeRearmStore(
                        new JdbcTemplate(bootstrap.dataSource())),
                new TaskCommandExecutor(
                        new DataSourceTransactionManager(bootstrap.dataSource())))
                .maintain(NOW.plusSeconds(8));

        assertThat(jdbc.queryForMap("""
                SELECT intent.status, successor.status AS successor_status,
                       successor.domain_attempt, successor.dispatch_attempt
                FROM stage_resume_rearm_intent_v257 intent
                JOIN stage_resume_async_successor_v272 successor
                  ON successor.handoff_id = intent.handoff_id
                WHERE intent.task_id = ?
                """, bootstrap.taskId()))
                .containsEntry("status", "MATERIALIZED")
                .containsEntry("successor_status", "ARMED")
                .containsEntry("domain_attempt", 2)
                .containsEntry("dispatch_attempt", 1);
        assertThat(countWhere(jdbc, "plan_self_review_resume_attempt_v272",
                "semantic_attempt = 2")).isOne();
        assertThat(countWhere(jdbc, "task_turn",
                "purpose = 'PLAN_SELF_REVIEW' AND status = 'REQUESTED'"))
                .isOne();

        RunningTurn resumed = startCurrentTurn(jdbc, bootstrap.taskId());
        DispatchTicket.DeliveryReceipt failed = deliverFailed(
                runtime(bootstrap.dataSource()).plan(), jdbc, resumed,
                "provider failed after resume");
        assertThat(failed.acceptance()).isEqualTo(ACCEPTED);
        assertThat(jdbc.queryForMap("""
                SELECT COUNT(*) AS attempts, MAX(semantic_attempt) AS latest
                FROM plan_self_review_all_attempt_v265
                WHERE self_review_id = (
                    SELECT id FROM plan_self_review LIMIT 1)
                """))
                .containsEntry("attempts", 3)
                .containsEntry("latest", 3);
        assertThat(jdbc.queryForMap("""
                SELECT infrastructure_attempt, execution_attempt
                FROM plan_self_review_failure_execution_v272
                """))
                .containsEntry("infrastructure_attempt", 2)
                .containsEntry("execution_attempt", 3);
        assertThat(countWhere(jdbc, "task_turn",
                "purpose = 'PLAN_SELF_REVIEW' AND status = 'REQUESTED'"))
                .isOne();

        DispatchTicket.DeliveryReceipt replay = runtime(bootstrap.dataSource())
                .plan().deliverTaskTurn(
                        resumed.owner(), resumed.fence(), resumed.result());
        assertThat(replay).isEqualTo(failed);
        assertThat(countWhere(jdbc, "plan_self_review_attempt", "attempt = 2"))
                .isOne();
    }

    @Test
    void acceptsTerminalProvisionFailureIntoAnExactTaskBlocker()
            throws Exception
    {
        Path repositoryRoot = tempDir.resolve("failed-provision-repo").toAbsolutePath();
        DataSource dataSource = database("failed-provision.db");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        Runtime runtime = runtime(dataSource);
        TaskCreationHandoff.Result created = runtime.creation().create(
                creationCommand(repositoryRoot));
        String taskId = created.task().task().id();
        String operationId = created.task().receipt().operationId();
        markProvisionFailurePending(jdbc, operationId, "git failed");
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, taskId,
                PlanRuntimeCoordinator.PROVISION_CALLBACK);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, operationId, 1,
                null, null, null);
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, FAILED, null, null, "git failed");
        DispatchTicket.DeliveryReceipt accepted = runtime.plan().deliverProvisioning(
                        owner, fence, new DispatchTicket.DispatchResult(
                                fence, FAILED, null, null, "git failed"));

        assertThat(accepted.acceptance()).isEqualTo(ACCEPTED);
        assertThat(accepted.evidenceJson()).contains("PROVISION_FAILURE_V1");
        assertThat(count(jdbc, "task_provision_failure_receipt")).isOne();
        assertThat(countWhere(jdbc, "task_blocker",
                "blocker_type = 'PROVISIONING_FAILED' AND status = 'OPEN'"))
                .isOne();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM provision_task_operation WHERE operation_id = ?
                """, String.class, operationId)).isEqualTo("FAILED");

        DispatchTicket.DeliveryReceipt replay = runtime(dataSource).plan()
                .deliverProvisioning(owner, fence, result);
        assertThat(replay).isEqualTo(accepted);
    }

    @Test
    void retriesOnlyTheExactFailedActivePlanDraftAndReplaysIdempotently()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-draft-recovery.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator plan = runtime(bootstrap.dataSource()).plan();
        RunningTurn failed = startCurrentTurn(jdbc, bootstrap.taskId());
        JsonNode predecessorLaunch = new ObjectMapper().readTree(
                jdbc.queryForObject("""
                        SELECT launch_input FROM task_turn WHERE id = ?
                        """, String.class, failed.turnId()));

        deliverSucceeded(plan, jdbc, failed);
        finalizePendingTickets(jdbc);
        String blockerId = jdbc.queryForObject("""
                SELECT id FROM task_blocker
                WHERE task_id = ? AND blocker_type = 'OPERATION_FAILED'
                  AND status = 'OPEN'
                """, String.class, bootstrap.taskId());
        assertThat(jdbc.queryForObject("""
                SELECT subject_revision FROM task_blocker WHERE id = ?
                """, String.class, blockerId)).isEqualTo(failed.turnId());
        int turnsBeforeRetry = count(jdbc, "task_turn");

        assertThatThrownBy(() -> plan.retryFailedDraft(
                bootstrap.taskId(), "another-turn", blockerId,
                "retry-wrong-turn", "user", "retry the Plan"))
                .isInstanceOf(CommandRejectedException.class);
        assertThat(count(jdbc, "task_turn")).isEqualTo(turnsBeforeRetry);

        String stageId = jdbc.queryForObject("""
                SELECT stage_id FROM task_current_stage WHERE task_id = ?
                """, String.class, bootstrap.taskId());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id, blocker_type,
                    status, payload_json, opened_at_ms)
                VALUES ('ambiguous-plan-failure', ?, ?, 'STAGE', ?,
                    'OPERATION_FAILED', 'OPEN', '{}', ?)
                """, bootstrap.taskId(), stageId, stageId,
                NOW.plusSeconds(20).toEpochMilli()))
                .hasMessageContaining("UNIQUE constraint failed");
        assertThat(count(jdbc, "task_turn")).isEqualTo(turnsBeforeRetry);

        jdbc.update("""
                UPDATE task_blocker SET subject_revision = 'another-turn'
                WHERE id = ?
                """, blockerId);
        assertThatThrownBy(() -> plan.retryFailedDraft(
                bootstrap.taskId(), failed.turnId(), blockerId,
                "retry-wrong-lineage", "user", "retry the Plan"))
                .isInstanceOf(CommandRejectedException.class);
        assertThat(count(jdbc, "task_turn")).isEqualTo(turnsBeforeRetry);

        // A blocker created before subject_revision was populated remains
        // recoverable only through its deterministic failed-turn identifier.
        jdbc.update("""
                UPDATE task_blocker SET subject_revision = NULL WHERE id = ?
                """, blockerId);
        PlanRuntimeCoordinator.PlanDraftRetryReceipt accepted =
                plan.retryFailedDraft(
                        bootstrap.taskId(), failed.turnId(), blockerId,
                        "retry-plan-draft", "user", "retry the exact failed Plan");

        assertThat(accepted.taskId()).isEqualTo(bootstrap.taskId());
        assertThat(accepted.failedTurnId()).isEqualTo(failed.turnId());
        assertThat(accepted.blockerId()).isEqualTo(blockerId);
        assertThat(jdbc.queryForMap("""
                SELECT task.lifecycle_state, stage.checkpoint, stage.version,
                    blocker.status AS blocker_status,
                    failed.status AS failed_status,
                    replacement.status AS replacement_status,
                    ticket.status AS ticket_status
                FROM stage_plan_draft_retry_request_v297 retry
                JOIN tasks task ON task.id = retry.task_id
                JOIN stage ON stage.id = retry.stage_id
                JOIN task_blocker blocker ON blocker.id = retry.blocker_id
                JOIN task_turn failed ON failed.id = retry.predecessor_turn_id
                JOIN task_turn replacement
                  ON replacement.id = retry.replacement_turn_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = retry.replacement_ticket_id
                WHERE retry.task_id = ? AND retry.command_id = ?
                """, bootstrap.taskId(), "retry-plan-draft"))
                .containsEntry("lifecycle_state", "ACTIVE")
                .containsEntry("checkpoint", "DRAFTING")
                .containsEntry("version", 3)
                .containsEntry("blocker_status", "RESOLVED")
                .containsEntry("failed_status", "FAILED")
                .containsEntry("replacement_status", "REQUESTED")
                .containsEntry("ticket_status", "REQUESTED");
        JsonNode replacementLaunch = new ObjectMapper().readTree(
                jdbc.queryForObject("""
                        SELECT launch_input FROM task_turn WHERE id = ?
                        """, String.class, accepted.replacementTurnId()));
        String frozenPrompt = predecessorLaunch.hasNonNull("fallbackPrompt")
                ? predecessorLaunch.path("fallbackPrompt").asText()
                : predecessorLaunch.path("prompt").asText();
        assertThat(replacementLaunch.path("prompt").asText())
                .isEqualTo(frozenPrompt);
        assertThat(replacementLaunch.has("resumeSessionId")).isFalse();
        assertThat(count(jdbc, "stage_plan_draft_retry_request_v297")).isOne();
        assertThat(count(jdbc, "task_turn")).isEqualTo(turnsBeforeRetry + 1);

        PlanRuntimeCoordinator.PlanDraftRetryReceipt replay =
                runtime(bootstrap.dataSource()).plan().retryFailedDraft(
                        bootstrap.taskId(), failed.turnId(), blockerId,
                        "retry-plan-draft", "user", "retry the exact failed Plan");
        assertThat(replay).isEqualTo(accepted);
        assertThat(count(jdbc, "task_turn")).isEqualTo(turnsBeforeRetry + 1);

        assertThatThrownBy(() -> plan.retryFailedDraft(
                bootstrap.taskId(), failed.turnId(), blockerId,
                "retry-stale-plan-draft", "user", "retry again"))
                .isInstanceOf(CommandRejectedException.class);
        assertThat(count(jdbc, "task_turn")).isEqualTo(turnsBeforeRetry + 1);
    }

    @Test
    void projectsReviewedCanonicalJsonPlanAsTheAwaitingApprovalCard()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("canonical-plan-card-projection.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator plan = bootstrap.runtime().plan();
        RunningTurn draft = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(), """
                {
                  "status": "finalized",
                  "understanding": {
                    "summary": "Task names use one shared left-navigation CSS rule."
                  },
                  "intent": {
                    "summary": "Use the standard 14px token for task names.",
                    "steps": [
                      {
                        "order": 1,
                        "file": "frontend/src/css/workspace-task-v2.css",
                        "action": "Replace the nested font-size token with the 14px token."
                      },
                      {
                        "order": 2,
                        "files": [
                          "frontend/src/ui/shell/TaskSidebar.tsx",
                          "frontend/src/ui/shell/TaskSidebar.test.tsx"
                        ],
                        "action": "Confirm active and sibling task rows share the selector."
                      },
                      {
                        "order": 3,
                        "action": "Inspect the final diff for only the intended substitution."
                      }
                    ],
                    "validationStrategy": [
                      "Run the focused TaskSidebar tests.",
                      "Run npx tsc --noEmit."
                    ],
                    "expectedFilesChanged": [
                      "frontend/src/css/workspace-task-v2.css"
                    ]
                  }
                }
                """);
        deliverSucceeded(plan, jdbc, draft);

        RunningTurn review = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordSelfReview(
                review.turnId(), review.operationId(), bootstrap.taskId(),
                "APPROVED", List.of(), List.of(), List.of());
        deliverSucceeded(plan, jdbc, review);

        TaskBrainViewData.PlanCard card = new V2DevelopmentFlowProjection(jdbc)
                .brain(legacyTask(bootstrap.taskId())).rightRail().plan();

        assertThat(card).isNotNull();
        assertThat(card.state()).isEqualTo("awaiting");
        assertThat(card.goal())
                .isEqualTo("Task names use one shared left-navigation CSS rule.");
        assertThat(card.understandingSummary()).isEqualTo(card.goal());
        assertThat(card.intentSummary())
                .isEqualTo("Use the standard 14px token for task names.");
        assertThat(card.steps()).hasSize(3);
        assertThat(card.steps().get(0)).satisfies(step -> {
            assertThat(step.ordinal()).isEqualTo(1);
            assertThat(step.action())
                    .isEqualTo("Replace the nested font-size token with the 14px token.");
            assertThat(step.files())
                    .containsExactly("frontend/src/css/workspace-task-v2.css");
        });
        assertThat(card.steps().get(1).files()).containsExactly(
                "frontend/src/ui/shell/TaskSidebar.tsx",
                "frontend/src/ui/shell/TaskSidebar.test.tsx");
        assertThat(card.steps().get(2).ordinal()).isEqualTo(3);
        assertThat(card.validationStrategy()).isEqualTo("""
                Run the focused TaskSidebar tests.
                Run npx tsc --noEmit.""");
    }

    @Test
    void projectsReviewedMarkdownPlanAsTheAwaitingApprovalCard()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-card-projection.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator plan = bootstrap.runtime().plan();
        RunningTurn draft = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(), """
                # Plan: Increase left-nav task name font size to 14px

                ## Goal
                Bump the shared left-nav nested row font size from 13px to 14px.

                ## Change (single edit)
                `frontend/src/css/base.css:359`
                ```
                -  --left-nav-nested-font-size: 13px;
                +  --left-nav-nested-font-size: 14px;
                ```

                ## Why one edit suffices
                Every left-nav task row reads the shared variable.

                ## Validation
                - No automated tests cover these font sizes.
                - Visually confirm task rows match the thread-row size.

                ## Scope guardrails
                - Do NOT touch `--left-nav-font-size`.
                - Single-line change only.
                """);
        deliverSucceeded(plan, jdbc, draft);

        RunningTurn review = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordSelfReview(
                review.turnId(), review.operationId(), bootstrap.taskId(),
                "APPROVED", List.of(),
                List.of("Confirm nested stage rows remain readable"), List.of());
        deliverSucceeded(plan, jdbc, review);

        V2DevelopmentFlowProjection projection =
                new V2DevelopmentFlowProjection(jdbc);
        TaskBrainViewData.PlanCard card = projection
                .brain(legacyTask(bootstrap.taskId())).rightRail().plan();

        assertThat(card).isNotNull();
        assertThat(card.state()).isEqualTo("awaiting");
        assertThat(card.status()).isEqualTo("finalized");
        assertThat(card.source()).isEqualTo("brain");
        assertThat(card.goal())
                .isEqualTo("Bump the shared left-nav nested row font size from 13px to 14px.");
        assertThat(card.steps()).singleElement().satisfies(step -> {
            assertThat(step.action())
                    .isEqualTo("Update frontend/src/css/base.css:359");
            assertThat(step.files()).containsExactly("frontend/src/css/base.css");
            assertThat(step.detail())
                    .contains("## Why one edit suffices")
                    .contains("## Scope guardrails");
        });
        assertThat(card.validationStrategy())
                .contains("Visually confirm task rows");
        assertThat(card.outOfScope()).containsExactly(
                "Do NOT touch `--left-nav-font-size`.",
                "Single-line change only.");
        assertThat(card.followups()).singleElement()
                .satisfies(followup -> assertThat(followup.note())
                        .isEqualTo("Confirm nested stage rows remain readable"));

        Map<String, Object> owner = jdbc.queryForMap("""
                SELECT task.epoch, task.aggregate_version,
                       stage.id AS stage_id, stage.generation, stage.version,
                       revision.id AS revision_id, review.id AS review_id
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                WHERE task.id = ?
                ORDER BY revision.revision DESC LIMIT 1
                """, bootstrap.taskId());
        plan.approvePlan(new PlanRuntimeCoordinator.PlanApprovalCommand(
                "approve-projected-plan", "user", bootstrap.taskId(),
                ((Number) owner.get("epoch")).longValue(),
                ((Number) owner.get("aggregate_version")).longValue(),
                (String) owner.get("stage_id"),
                ((Number) owner.get("generation")).longValue(),
                ((Number) owner.get("version")).longValue(),
                (String) owner.get("revision_id"),
                (String) owner.get("review_id")));

        TaskBrainViewData.PlanCard locked = projection
                .brain(legacyTask(bootstrap.taskId())).rightRail().plan();
        assertThat(locked).isNotNull();
        assertThat(locked.state()).isEqualTo("locked");
        assertThat(locked.goal()).isEqualTo(card.goal());
        assertThat(locked.steps()).isEqualTo(card.steps());
        assertThat(locked.followups()).isEqualTo(card.followups());
    }

    @Test
    void recordsOneReviewPerRevisionAndRedraftsBeforeApproval()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-cycle.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator plan = bootstrap.runtime().plan();

        RunningTurn draftOne = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordPlan(
                draftOne.turnId(), draftOne.operationId(), bootstrap.taskId(),
                "1. Inspect the code.\n2. Implement the change.\n3. Test it.");
        deliverSucceeded(plan, jdbc, draftOne);

        RunningTurn reviewOne = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordSelfReview(
                reviewOne.turnId(), reviewOne.operationId(), bootstrap.taskId(),
                "CHANGES_REQUESTED", List.of("Add rollback coverage"),
                List.of("Confirm production telemetry"), List.of());
        deliverSucceeded(plan, jdbc, reviewOne);

        RunningTurn draftTwo = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordPlan(
                draftTwo.turnId(), draftTwo.operationId(), bootstrap.taskId(),
                "1. Inspect the code.\n2. Implement the change.\n"
                        + "3. Test it, including rollback coverage.");
        deliverSucceeded(plan, jdbc, draftTwo);

        RunningTurn reviewTwo = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordSelfReview(
                reviewTwo.turnId(), reviewTwo.operationId(), bootstrap.taskId(),
                "APPROVED", List.of(), List.of("Watch rollout telemetry"),
                List.of());
        DispatchTicket.DeliveryReceipt approved =
                deliverSucceeded(plan, jdbc, reviewTwo);

        assertThat(approved.acceptance()).isEqualTo(ACCEPTED);
        assertThat(jdbc.queryForMap("""
                SELECT stage.checkpoint, stage.version,
                       COUNT(DISTINCT revision.id) AS revisions,
                       COUNT(DISTINCT review.id) AS reviews
                FROM task_current_stage current
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_stage plan ON plan.stage_id = stage.id
                JOIN plan_revision revision ON revision.plan_stage_id = plan.stage_id
                JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                WHERE current.task_id = ?
                """, bootstrap.taskId()))
                .containsEntry("checkpoint", "AWAITING_APPROVAL")
                .containsEntry("version", 5)
                .containsEntry("revisions", 2)
                .containsEntry("reviews", 2);
        assertThat(countWhere(jdbc, "plan_self_review", "status = 'SUCCEEDED'"))
                .isEqualTo(2);
        assertThat(countWhere(jdbc, "plan_followup", "kind = 'CONCERN'"))
                .isOne();
        assertThat(countWhere(jdbc, "plan_followup", "kind = 'FOLLOW_UP'"))
                .isEqualTo(2);
        assertThat(count(jdbc, "plan_task_turn_delivery_receipt")).isEqualTo(4);

        DispatchTicket.DeliveryReceipt replay = runtime(bootstrap.dataSource())
                .plan().deliverTaskTurn(
                        reviewTwo.owner(), reviewTwo.fence(), reviewTwo.result());
        assertThat(replay).isEqualTo(approved);
        assertThat(count(jdbc, "task_turn")).isEqualTo(4);

        Map<String, Object> latest = jdbc.queryForMap("""
                SELECT stage.id AS stage_id, stage.generation,
                       stage.version, revision.id AS revision_id,
                       review.id AS review_id
                FROM task_current_stage current
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                WHERE current.task_id = ?
                ORDER BY revision.revision DESC LIMIT 1
                """, bootstrap.taskId());
        SqlitePlanRuntimeStore.PlanEditReceipt edit = plan.editPlan(
                new PlanRuntimeCoordinator.PlanEditCommand(
                        "edit-plan-1", "user", bootstrap.taskId(),
                        (String) latest.get("stage_id"),
                        ((Number) latest.get("generation")).longValue(),
                        ((Number) latest.get("version")).longValue(),
                        (String) latest.get("revision_id"),
                        (String) latest.get("review_id"),
                        "1. Inspect the code.\n2. Implement safely.\n"
                                + "3. Test rollback and rollout telemetry."));
        assertThat(edit.revision()).isEqualTo(3);
        assertThat(jdbc.queryForMap("""
                SELECT checkpoint, version FROM stage WHERE id = ?
                """, edit.stageId()))
                .containsEntry("checkpoint", "SELF_REVIEW")
                .containsEntry("version", 7);
        assertThat(count(jdbc, "plan_user_edit_receipt")).isOne();

        SqlitePlanRuntimeStore.PlanEditReceipt editReplay = runtime(
                bootstrap.dataSource()).plan().editPlan(
                        new PlanRuntimeCoordinator.PlanEditCommand(
                                "edit-plan-1", "user", bootstrap.taskId(),
                                edit.stageId(), edit.stageGeneration(),
                                edit.expectedStageVersion(),
                                edit.previousRevisionId(),
                                (String) latest.get("review_id"), edit.content()));
        assertThat(editReplay).isEqualTo(edit);

        RunningTurn editedReview = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordSelfReview(
                editedReview.turnId(), editedReview.operationId(),
                bootstrap.taskId(), "APPROVED", List.of(), List.of(),
                List.of());
        deliverSucceeded(plan, jdbc, editedReview);
        assertThat(jdbc.queryForMap("""
                SELECT checkpoint, version FROM stage WHERE id = ?
                """, edit.stageId()))
                .containsEntry("checkpoint", "AWAITING_APPROVAL")
                .containsEntry("version", 8);
        assertThat(count(jdbc, "plan_revision")).isEqualTo(3);
        assertThat(count(jdbc, "plan_self_review")).isEqualTo(3);

        Map<String, Object> approvalOwner = jdbc.queryForMap("""
                SELECT task.epoch, task.aggregate_version,
                       stage.id AS stage_id, stage.generation, stage.version
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                WHERE task.id = ?
                """, bootstrap.taskId());
        SqlitePlanRuntimeStore.AcceptedApproval approval = plan.approvePlan(
                new PlanRuntimeCoordinator.PlanApprovalCommand(
                        "approve-edited-plan", "user", bootstrap.taskId(),
                        ((Number) approvalOwner.get("epoch")).longValue(),
                        ((Number) approvalOwner.get("aggregate_version")).longValue(),
                        (String) approvalOwner.get("stage_id"),
                        ((Number) approvalOwner.get("generation")).longValue(),
                        ((Number) approvalOwner.get("version")).longValue(),
                        edit.revisionId(), edit.selfReviewId()));

        assertThat(approval.kind()).isEqualTo("HUMAN");
        assertThat(jdbc.queryForMap("""
                SELECT task.aggregate_version, stage.kind, stage.checkpoint,
                       stage.version
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                WHERE task.id = ?
                """, bootstrap.taskId()))
                .containsEntry("aggregate_version", 2)
                .containsEntry("kind", "LOCAL_DEVELOPMENT")
                .containsEntry("checkpoint", "IMPLEMENTING")
                .containsEntry("version", 1);
        assertThat(count(jdbc, "local_initial_implementation_receipt")).isOne();
        assertThat(countWhere(jdbc, "stage_turn", "status = 'QUEUED'"))
                .isOne();

        SqlitePlanRuntimeStore.AcceptedApproval approvalReplay = runtime(
                bootstrap.dataSource()).plan().approvePlan(
                        new PlanRuntimeCoordinator.PlanApprovalCommand(
                                "approve-edited-plan", "user", bootstrap.taskId(),
                                ((Number) approvalOwner.get("epoch")).longValue(),
                                ((Number) approvalOwner.get("aggregate_version")).longValue(),
                                (String) approvalOwner.get("stage_id"),
                                ((Number) approvalOwner.get("generation")).longValue(),
                                ((Number) approvalOwner.get("version")).longValue(),
                                edit.revisionId(), edit.selfReviewId()));
        assertThat(approvalReplay).isEqualTo(approval);
        assertThat(count(jdbc, "local_initial_implementation_receipt")).isOne();
    }

    @Test
    void policyApprovalStartsLocalUnlessStewardshipIsOpen()
            throws Exception
    {
        Bootstrapped automatic = bootstrap("plan-auto-approval.db", true);
        RunningTurn draft = startCurrentTurn(
                automatic.jdbc(), automatic.taskId());
        automatic.runtime().plan().recordPlan(
                draft.turnId(), draft.operationId(), automatic.taskId(),
                "1. Implement.\n2. Validate.");
        deliverSucceeded(automatic.runtime().plan(), automatic.jdbc(), draft);
        RunningTurn review = startCurrentTurn(
                automatic.jdbc(), automatic.taskId());
        automatic.runtime().plan().recordSelfReview(
                review.turnId(), review.operationId(), automatic.taskId(),
                "APPROVED", List.of(), List.of(), List.of());
        deliverSucceeded(automatic.runtime().plan(), automatic.jdbc(), review);

        assertThat(countWhere(
                automatic.jdbc(), "plan_approval", "approval_kind = 'POLICY'"))
                .isOne();
        assertThat(count(automatic.jdbc(), "local_initial_implementation_receipt"))
                .isOne();
        assertThat(automatic.jdbc().queryForMap("""
                SELECT stage.kind, stage.checkpoint, stage.version
                FROM task_current_stage current
                JOIN stage ON stage.id = current.stage_id
                WHERE current.task_id = ?
                """, automatic.taskId()))
                .containsEntry("kind", "LOCAL_DEVELOPMENT")
                .containsEntry("checkpoint", "IMPLEMENTING")
                .containsEntry("version", 1);

        Bootstrapped stewardship = bootstrap("plan-stewardship.db", true);
        RunningTurn stewardshipDraft = startCurrentTurn(
                stewardship.jdbc(), stewardship.taskId());
        stewardship.runtime().plan().recordPlan(
                stewardshipDraft.turnId(), stewardshipDraft.operationId(),
                stewardship.taskId(), "1. Implement carefully.\n2. Validate.");
        deliverSucceeded(
                stewardship.runtime().plan(), stewardship.jdbc(),
                stewardshipDraft);
        RunningTurn stewardshipReview = startCurrentTurn(
                stewardship.jdbc(), stewardship.taskId());
        stewardship.runtime().plan().recordSelfReview(
                stewardshipReview.turnId(), stewardshipReview.operationId(),
                stewardship.taskId(), "APPROVED", List.of(), List.of(),
                List.of("User must confirm the data migration"));
        deliverSucceeded(
                stewardship.runtime().plan(), stewardship.jdbc(),
                stewardshipReview);

        assertThat(count(stewardship.jdbc(), "plan_approval")).isZero();
        assertThat(stewardship.jdbc().queryForObject("""
                SELECT checkpoint FROM stage
                WHERE id = (SELECT stage_id FROM task_current_stage WHERE task_id = ?)
                """, String.class, stewardship.taskId()))
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void enablingPolicyRedrivesAnAlreadyWaitingPlan()
            throws Exception
    {
        Bootstrapped waiting = bootstrap("plan-policy-redrive.db", false);
        RunningTurn draft = startCurrentTurn(waiting.jdbc(), waiting.taskId());
        waiting.runtime().plan().recordPlan(
                draft.turnId(), draft.operationId(), waiting.taskId(),
                "1. Implement.\n2. Validate.");
        deliverSucceeded(waiting.runtime().plan(), waiting.jdbc(), draft);
        RunningTurn review = startCurrentTurn(waiting.jdbc(), waiting.taskId());
        waiting.runtime().plan().recordSelfReview(
                review.turnId(), review.operationId(), waiting.taskId(),
                "APPROVED", List.of(), List.of(), List.of());
        deliverSucceeded(waiting.runtime().plan(), waiting.jdbc(), review);

        assertThat(waiting.jdbc().queryForObject("""
                SELECT checkpoint FROM stage
                WHERE id = (SELECT stage_id FROM task_current_stage WHERE task_id = ?)
                """, String.class, waiting.taskId()))
                .isEqualTo("AWAITING_APPROVAL");

        Map<String, Object> owner = waiting.jdbc().queryForMap("""
                SELECT epoch, aggregate_version FROM tasks WHERE id = ?
                """, waiting.taskId());
        TaskManager.PolicyRevision current = waiting.runtime().tasks()
                .policy(waiting.taskId());
        waiting.runtime().tasks().revisePolicy(new TaskManager.PolicyCommand(
                new TaskManager.Command(
                        "enable-plan-auto-approve", "user", waiting.taskId(),
                        ((Number) owner.get("epoch")).longValue(),
                        ((Number) owner.get("aggregate_version")).longValue()),
                "policy-plan-redrive", true, false,
                current.minApprovals(), current.maxBrainRounds(),
                current.maxCiFixPushes(),
                current.requireRemoteBranchCleanup(),
                current.permissionPolicyRef()));

        assertThat(waiting.runtime().plan()
                .redrivePolicyApproval(waiting.taskId())).isTrue();
        assertThat(waiting.jdbc().queryForMap("""
                SELECT stage.kind, stage.checkpoint
                FROM task_current_stage current
                JOIN stage ON stage.id = current.stage_id
                WHERE current.task_id = ?
                """, waiting.taskId()))
                .containsEntry("kind", "LOCAL_DEVELOPMENT")
                .containsEntry("checkpoint", "IMPLEMENTING");
        assertThat(runtime(waiting.dataSource()).plan()
                .redrivePolicyApproval(waiting.taskId())).isFalse();
        assertThat(countWhere(
                waiting.jdbc(), "plan_approval", "approval_kind = 'POLICY'"))
                .isOne();
    }

    @Test
    void retriesTheFirstSelfReviewFailureOnceThenBlocksAndClearsTheFence()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-review-retry.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator plan = bootstrap.runtime().plan();

        RunningTurn draft = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(),
                "1. Implement.\n2. Validate.");
        deliverSucceeded(plan, jdbc, draft);

        RunningTurn firstReview = startCurrentTurn(jdbc, bootstrap.taskId());
        DispatchTicket.DeliveryReceipt firstFailure =
                deliverFailed(plan, jdbc, firstReview, "provider exited");

        assertThat(firstFailure.evidenceJson())
                .contains("REVIEW_RETRY_REQUESTED");
        assertThat(countWhere(jdbc, "plan_self_review_attempt", "attempt = 2"))
                .isOne();
        assertThat(countWhere(jdbc, "plan_self_review", "status = 'REQUESTED'"))
                .isOne();
        assertThat(jdbc.queryForMap("""
                SELECT checkpoint, version FROM stage
                WHERE id = (SELECT stage_id FROM task_current_stage WHERE task_id = ?)
                """, bootstrap.taskId()))
                .containsEntry("checkpoint", "SELF_REVIEW")
                .containsEntry("version", 3);

        DispatchTicket.DeliveryReceipt firstReplay = runtime(
                bootstrap.dataSource()).plan().deliverTaskTurn(
                        firstReview.owner(), firstReview.fence(), firstReview.result());
        assertThat(firstReplay).isEqualTo(firstFailure);

        RunningTurn retry = startCurrentTurn(jdbc, bootstrap.taskId());
        DispatchTicket.DeliveryReceipt secondFailure =
                deliverFailed(plan, jdbc, retry, "provider exited again");

        assertThat(secondFailure.evidenceJson()).contains("REVIEW_BLOCKED");
        assertThat(countWhere(jdbc, "plan_self_review", "status = 'FAILED'"))
                .isOne();
        assertThat(countWhere(jdbc, "task_blocker", "status = 'OPEN'"))
                .isOne();
        assertThat(count(jdbc, "stage_plan_terminal_result")).isOne();
        assertThat(countWhere(jdbc, "task_turn", "status = 'REQUESTED'"))
                .isZero();
        assertThat(jdbc.queryForMap("""
                SELECT checkpoint, version FROM stage
                WHERE id = (SELECT stage_id FROM task_current_stage WHERE task_id = ?)
                """, bootstrap.taskId()))
                .containsEntry("checkpoint", "SELF_REVIEW")
                .containsEntry("version", 4);

        DispatchTicket.DeliveryReceipt secondReplay = runtime(
                bootstrap.dataSource()).plan().deliverTaskTurn(
                        retry.owner(), retry.fence(), retry.result());
        assertThat(secondReplay).isEqualTo(secondFailure);
        assertThat(countWhere(jdbc, "plan_self_review_attempt", "attempt = 2"))
                .isOne();
    }

    @Test
    void initialAndRetryReviewPromptsPublishTheStrictAcceptedSubmissionContract()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-review-prompt-contract.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator plan = bootstrap.runtime().plan();

        RunningTurn draft = startCurrentTurn(jdbc, bootstrap.taskId());
        plan.recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(),
                "1. Implement.\n2. Validate.");
        deliverSucceeded(plan, jdbc, draft);

        JsonNode initial = new ObjectMapper().readTree(jdbc.queryForObject("""
                SELECT launch_input FROM task_turn
                WHERE task_id = ? AND purpose = 'PLAN_SELF_REVIEW'
                """, String.class, bootstrap.taskId()));
        assertStrictReviewSubmissionContract(initial);

        RunningTurn failed = startCurrentTurn(jdbc, bootstrap.taskId());
        deliverFailed(plan, jdbc, failed, "provider exited");

        JsonNode retry = new ObjectMapper().readTree(jdbc.queryForObject("""
                SELECT turn.launch_input
                FROM plan_self_review_attempt attempt
                JOIN task_turn turn ON turn.id = attempt.task_turn_id
                WHERE attempt.attempt = 2
                """, String.class));
        assertStrictReviewSubmissionContract(retry);
    }

    @Test
    void satisfiedReplanOpensANewEpochAndArmsOneFreshDraft()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-replan.db");
        JdbcTemplate jdbc = bootstrap.jdbc();
        PlanRuntimeCoordinator runtime = bootstrap.runtime().plan();
        RunningTurn draft = startCurrentTurn(jdbc, bootstrap.taskId());
        runtime.recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(),
                "1. Implement the first approach.\n2. Validate it.");
        deliverSucceeded(runtime, jdbc, draft);
        RunningTurn review = startCurrentTurn(jdbc, bootstrap.taskId());
        runtime.recordSelfReview(
                review.turnId(), review.operationId(), bootstrap.taskId(),
                "APPROVED", List.of(), List.of(), List.of());
        deliverSucceeded(runtime, jdbc, review);
        finalizePendingTickets(jdbc);

        Map<String, Object> source = jdbc.queryForMap("""
                SELECT task.epoch, task.aggregate_version,
                       stage.id AS stage_id, stage.generation, stage.version
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                WHERE task.id = ?
                """, bootstrap.taskId());
        String barrierId = "replan-barrier";
        String replanId = "replan-request";
        String commandId = "apply-replan";
        String newPlanId = "replacement-plan";
        long epoch = ((Number) source.get("epoch")).longValue();
        long generation = ((Number) source.get("generation")).longValue();
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES (?, ?, ?, 'REPLAN', 'REQUESTED', ?)
                """, barrierId, bootstrap.taskId(), epoch, NOW.toEpochMilli());
        jdbc.update("""
                INSERT INTO task_replan_request(
                    id, task_id, source_stage_id, source_generation,
                    source_task_epoch, target_task_epoch,
                    quiescence_barrier_id, command_id, reason, requested_by,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'user', 'REQUESTED', ?)
                """, replanId, bootstrap.taskId(), source.get("stage_id"),
                generation, epoch, epoch + 1, barrierId, commandId,
                "the first approach exposed a design flaw", NOW.toEpochMilli());
        jdbc.update("""
                UPDATE task_replan_request SET status = 'QUIESCING' WHERE id = ?
                """, replanId);
        jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = ?, evidence = ?
                WHERE id = ?
                """, NOW.plusSeconds(10).toEpochMilli(), "quiesced", barrierId);

        TaskManager.ReplanCommand command = new TaskManager.ReplanCommand(
                commandId, "user", bootstrap.taskId(),
                ((Number) source.get("aggregate_version")).longValue(), epoch,
                (String) source.get("stage_id"), generation,
                ((Number) source.get("version")).longValue(), replanId,
                barrierId, newPlanId, generation + 1);
        ReplanHandoff.Result accepted = bootstrap.runtime().replan().accept(command);

        assertThat(accepted.task().epoch()).isEqualTo(epoch + 1);
        assertThat(jdbc.queryForMap("""
                SELECT stage.kind, stage.generation, stage.version,
                       stage.checkpoint, turn.task_epoch,
                       turn.trigger_stage_generation, turn.purpose,
                       ticket.status AS ticket_status
                FROM task_current_stage current
                JOIN stage ON stage.id = current.stage_id
                JOIN stage_initial_result_request request
                  ON request.stage_id = stage.id
                JOIN task_turn turn ON turn.id = request.turn_id
                JOIN dispatch_ticket ticket ON ticket.operation_id = turn.operation_id
                WHERE current.task_id = ?
                """, bootstrap.taskId()))
                .containsEntry("kind", "PLAN")
                .containsEntry("generation", 2)
                .containsEntry("version", 1)
                .containsEntry("checkpoint", "DRAFTING")
                .containsEntry("task_epoch", 2)
                .containsEntry("trigger_stage_generation", 2)
                .containsEntry("purpose", "PLAN_DRAFT")
                .containsEntry("ticket_status", "REQUESTED");
        assertThat(count(jdbc, "task_replan_plan_receipt")).isOne();

        ReplanHandoff.Result replay = bootstrap.runtime().replan().accept(command);
        assertThat(replay.plan().disposition().name()).isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "task_replan_plan_receipt")).isOne();
        assertThat(countWhere(jdbc, "task_turn", "task_epoch = 2")).isOne();
    }

    @Test
    void productControlApprovesLatestPlanAndOwnsFollowupStatus()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-product-approval.db");
        ReviewedPlan reviewed = reviewedPlan(
                bootstrap, List.of("Watch rollout telemetry"));
        V2PlanControlService controls = controls(
                bootstrap.runtime(), ignored -> true);

        V2PlanControlService.Approval approval = controls.approve(
                reviewed.stageId());

        assertThat(approval.taskId()).isEqualTo(bootstrap.taskId());
        assertThat(bootstrap.jdbc().queryForMap("""
                SELECT stage.kind, stage.checkpoint
                  FROM task_current_stage current
                  JOIN stage ON stage.id = current.stage_id
                 WHERE current.task_id = ?
                """, bootstrap.taskId()))
                .containsEntry("kind", "LOCAL_DEVELOPMENT")
                .containsEntry("checkpoint", "IMPLEMENTING");

        controls.resolveFollowup(
                bootstrap.taskId(), reviewed.stageId(), reviewed.followupId(),
                "addressed");
        Map<String, Object> resolved = bootstrap.jdbc().queryForMap("""
                SELECT status, resolution, resolved_at_ms
                  FROM plan_followup WHERE id = ?
                """, reviewed.followupId());
        assertThat(resolved)
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolution", "user: addressed");
        assertThat(resolved.get("resolved_at_ms")).isNotNull();
        controls.resolveFollowup(
                bootstrap.taskId(), reviewed.stageId(), reviewed.followupId(),
                "addressed");
        assertThatThrownBy(() -> controls.resolveFollowup(
                bootstrap.taskId(), reviewed.stageId(), reviewed.followupId(),
                "dismissed"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value())
                                .isEqualTo(409));
        assertThatThrownBy(() -> controls.resolveFollowup(
                bootstrap.taskId(), approval.localStageId(),
                reviewed.followupId(), "dismissed"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value())
                                .isEqualTo(404));
    }

    @Test
    void productControlRejectsAStaleReviewedRevision()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-product-stale.db");
        ReviewedPlan reviewed = reviewedPlan(bootstrap, List.of());
        SqlitePlanRuntimeStore.ApprovalContext stale = bootstrap.runtime()
                .planStore().findLatestApprovalContext(reviewed.stageId())
                .orElseThrow();
        bootstrap.runtime().plan().editPlan(
                new PlanRuntimeCoordinator.PlanEditCommand(
                        "stale-revision-edit", "user", bootstrap.taskId(),
                        stale.stageId(), stale.stageGeneration(), stale.stageVersion(),
                        stale.revisionId(), stale.selfReviewId(),
                        "1. Replace the reviewed approach.\n2. Validate it."));

        assertThatThrownBy(() -> controls(
                bootstrap.runtime(), ignored -> true).approve(reviewed.stageId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value())
                                .isEqualTo(409));
        assertThat(count(bootstrap.jdbc(), "plan_approval")).isZero();
    }

    @Test
    void productReplanWaitsForOldWorkThenRestartsExactlyOnce()
            throws Exception
    {
        Bootstrapped bootstrap = bootstrap("plan-product-replan.db");
        ReviewedPlan reviewed = reviewedPlan(bootstrap, List.of());
        List<String> cancellationRequests = new ArrayList<>();
        V2PlanControlService first = controls(bootstrap.runtime(), ticketId -> {
            cancellationRequests.add(ticketId);
            return true;
        });
        first.approve(reviewed.stageId());

        V2PlanControlService.Replan waiting = first.replan(bootstrap.taskId());
        V2PlanControlService.Replan duplicate = first.replan(bootstrap.taskId());

        assertThat(waiting.preparing()).isTrue();
        assertThat(waiting.planStageId()).isNull();
        assertThat(duplicate).isEqualTo(waiting);
        assertThat(cancellationRequests).isNotEmpty();
        assertThat(count(bootstrap.jdbc(), "task_replan_request")).isOne();
        assertThat(countWhere(bootstrap.jdbc(), "task_replan_request",
                "status = 'QUIESCING'")).isOne();
        assertThat(countWhere(bootstrap.jdbc(), "plan_stage", "generation = 2"))
                .isZero();

        bootstrap.jdbc().update("""
                UPDATE stage_turn
                   SET status = 'CANCELED', started_at_ms = ?, finished_at_ms = ?,
                       error_message = 'replan'
                 WHERE stage_id IN (
                       SELECT id FROM stage WHERE task_id = ?)
                   AND status IN ('REQUESTED', 'QUEUED')
                """, NOW.plusSeconds(70).toEpochMilli(),
                NOW.plusSeconds(71).toEpochMilli(), bootstrap.taskId());
        bootstrap.jdbc().update("""
                UPDATE dispatch_ticket
                   SET version = version + 1, status = 'CANCELED',
                       cancel_requested_at_ms = ?, delivery_acceptance = 'ACCEPTED',
                       delivery_evidence = 'replan', completed_at_ms = ?
                 WHERE task_id = ? AND status = 'REQUESTED'
                """, NOW.plusSeconds(70).toEpochMilli(),
                NOW.plusSeconds(71).toEpochMilli(), bootstrap.taskId());

        Runtime restarted = runtime(bootstrap.dataSource());
        V2PlanControlService.Replan completed = controls(
                restarted, ignored -> true).replan(bootstrap.taskId());

        assertThat(completed.preparing()).isFalse();
        assertThat(completed.planStageId()).isNotBlank();
        assertThat(count(bootstrap.jdbc(), "task_replan_request")).isOne();
        assertThat(countWhere(bootstrap.jdbc(), "task_replan_request",
                "status = 'APPLIED'")).isOne();
        assertThat(countWhere(bootstrap.jdbc(), "plan_stage", "generation = 2"))
                .isOne();
        assertThat(count(bootstrap.jdbc(), "task_replan_plan_receipt")).isOne();
        assertThat(countWhere(bootstrap.jdbc(), "task_turn",
                "task_epoch = 2 AND purpose = 'PLAN_DRAFT'")).isOne();

    }

    private static ReviewedPlan reviewedPlan(
            Bootstrapped bootstrap, List<String> followups)
    {
        PlanRuntimeCoordinator plan = bootstrap.runtime().plan();
        RunningTurn draft = startCurrentTurn(
                bootstrap.jdbc(), bootstrap.taskId());
        plan.recordPlan(
                draft.turnId(), draft.operationId(), bootstrap.taskId(),
                "1. Implement the reviewed approach.\n2. Validate it.");
        deliverSucceeded(plan, bootstrap.jdbc(), draft);
        RunningTurn review = startCurrentTurn(
                bootstrap.jdbc(), bootstrap.taskId());
        plan.recordSelfReview(
                review.turnId(), review.operationId(), bootstrap.taskId(),
                "APPROVED", List.of(), followups, List.of());
        deliverSucceeded(plan, bootstrap.jdbc(), review);
        finalizePendingTickets(bootstrap.jdbc());
        Map<String, Object> owner = bootstrap.jdbc().queryForMap("""
                SELECT stage.id AS stage_id, revision.id AS revision_id,
                       self_review.id AS self_review_id
                  FROM task_current_stage current
                  JOIN stage ON stage.id = current.stage_id
                  JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                  JOIN plan_self_review self_review
                    ON self_review.plan_revision_id = revision.id
                 WHERE current.task_id = ?
                 ORDER BY revision.revision DESC LIMIT 1
                """, bootstrap.taskId());
        String followupId = bootstrap.jdbc().query("""
                SELECT followup.id
                  FROM plan_followup followup
                 WHERE followup.plan_revision_id = ? AND followup.kind = 'FOLLOW_UP'
                 ORDER BY followup.created_at_ms, followup.id LIMIT 1
                """, (result, ignored) -> result.getString("id"),
                owner.get("revision_id")).stream().findFirst().orElse(null);
        return new ReviewedPlan(
                (String) owner.get("stage_id"),
                (String) owner.get("revision_id"),
                (String) owner.get("self_review_id"), followupId);
    }

    private static Task legacyTask(String taskId)
    {
        return new Task(
                taskId, TRUNK, 1, TaskStatus.IDLE,
                "legacy-fallback", "/tmp/legacy-fallback", "main", "/tmp",
                null, null, null, null, null, null, null, null,
                0, 0, 0, null, NOW, null, null,
                null, null, null);
    }

    private static void assertStrictReviewSubmissionContract(JsonNode launch)
    {
        String prompt = launch.path("prompt").asText()
                .replaceAll("\\s+", " ").strip();
        assertThat(prompt)
                .contains("exactly one accepted self-review")
                .contains("APPROVED requires concerns=[]")
                .contains("non-blocking caveats in follow_ups or stewardship")
                .contains("A rejected call that recorded nothing may be corrected")
                .contains("Final prose is never a verdict");
    }

    private static V2PlanControlService controls(
            Runtime runtime, Predicate<String> cancellations)
    {
        TaskReplanMaintainer replans = new TaskReplanMaintainer(
                runtime.controlStore(),
                List.of(runtime.replan(), runtime.localReplan()), cancellations);
        return new V2PlanControlService(
                runtime.plan(), runtime.planStore(), runtime.planManager(),
                runtime.tasks(), runtime.controlStore(), replans,
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    private Bootstrapped bootstrap(String name)
            throws Exception
    {
        return bootstrap(name, false);
    }

    private Bootstrapped bootstrap(String name, boolean autoApprove)
            throws Exception
    {
        Path repositoryRoot = tempDir.resolve(name + "-repo").toAbsolutePath();
        DataSource dataSource = database(name);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        Runtime runtime = runtime(dataSource);
        TaskCreationHandoff.Result created = runtime.creation().create(
                creationCommand(repositoryRoot, autoApprove));
        String taskId = created.task().task().id();
        String operationId = created.task().receipt().operationId();
        ProvisionTaskOperationHandler.ProvisionEvidence evidence =
                new ProvisionTaskOperationHandler.ProvisionEvidence(
                        "PROVISION_TASK_V1", operationId, taskId, 1,
                        ProvisionTaskOperationHandler.BaseSource.FRESH_REMOTE_BASE,
                        REPOSITORY, created.task().target().branchName(),
                        created.task().target().worktreePath().toString(),
                        "base-sha", "base-sha", "code-fingerprint");
        String evidenceJson = new ObjectMapper().writeValueAsString(evidence);
        markProvisionResultPending(jdbc, operationId, evidenceJson);
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, taskId,
                PlanRuntimeCoordinator.PROVISION_CALLBACK);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, operationId, 1, null, null, null);
        assertThat(runtime.plan().deliverProvisioning(
                owner, fence, new DispatchTicket.DispatchResult(
                        fence, SUCCEEDED, evidenceJson, evidenceJson, null))
                .acceptance()).isEqualTo(ACCEPTED);
        return new Bootstrapped(dataSource, jdbc, runtime, taskId);
    }

    private static RunningTurn startCurrentTurn(JdbcTemplate jdbc, String taskId)
    {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT turn.id AS turn_id, turn.operation_id,
                       turn.task_epoch, turn.trigger_stage_id,
                       turn.trigger_stage_generation,
                       turn.expected_code_fingerprint,
                       turn.expected_head_sha, turn.expected_base_sha,
                       ticket.id AS ticket_id, ticket.lane_mask,
                       ticket.workspace_id, ticket.trunk_id,
                       ticket.exclusive_task, ticket.writer_required
                FROM task_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE turn.task_id = ? AND turn.status = 'REQUESTED'
                  AND ticket.status = 'REQUESTED'
                ORDER BY turn.requested_at_ms DESC
                LIMIT 1
                """, taskId);
        String turnId = (String) row.get("turn_id");
        String operationId = (String) row.get("operation_id");
        String ticketId = (String) row.get("ticket_id");
        String leaseId = "lease-" + operationId;
        long expires = NOW.plusSeconds(600).toEpochMilli();
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES (?, ?, ?, 'V2', ?, 0, ?, ?, ?, ?, ?, ?, 'test-runner',
                    ?, ?, ?)
                """, leaseId, ticketId, operationId, row.get("lane_mask"),
                row.get("exclusive_task"), row.get("writer_required"),
                row.get("workspace_id"), row.get("trunk_id"), taskId,
                row.get("task_epoch"), NOW.toEpochMilli(), NOW.toEpochMilli(),
                expires);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'CLAIMED',
                    claim_purpose = 'EXECUTE', claim_owner = 'test-runner',
                    capacity_lease_id = ?, claim_expires_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, leaseId, expires, ticketId);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RUNNING',
                    infrastructure_attempts = infrastructure_attempts + 1,
                    started_at_ms = ?
                WHERE id = ? AND status = 'CLAIMED'
                """, NOW.toEpochMilli(), ticketId);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms)
                VALUES (?, ?, 1, 'codex', 'RUNNING', ?)
                """, "execution-" + operationId, ticketId, NOW.toEpochMilli());
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                ((Number) row.get("task_epoch")).longValue(),
                (String) row.get("trigger_stage_id"),
                ((Number) row.get("trigger_stage_generation")).longValue(),
                operationId, 1,
                (String) row.get("expected_code_fingerprint"),
                (String) row.get("expected_head_sha"),
                (String) row.get("expected_base_sha"));
        return new RunningTurn(
                turnId, operationId, ticketId, leaseId, fence, null);
    }

    private static DispatchTicket.DeliveryReceipt deliverSucceeded(
            PlanRuntimeCoordinator plan, JdbcTemplate jdbc, RunningTurn running)
    {
        jdbc.update("""
                UPDATE agent_execution
                SET status = 'SUCCEEDED', finished_at_ms = ?, raw_result = '{}'
                WHERE ticket_id = ? AND infrastructure_attempt = 1
                """, NOW.plusSeconds(1).toEpochMilli(), running.ticketId());
        jdbc.update("""
                UPDATE capacity_lease
                SET released_at_ms = ?, release_reason = 'completed'
                WHERE id = ? AND released_at_ms IS NULL
                """, NOW.plusSeconds(1).toEpochMilli(), running.leaseId());
        String evidence = "{\"schema\":\"TASK_TURN_RESULT_V1\"}";
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?, pending_result_evidence = ?,
                    pending_result_error = NULL,
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = stage_id,
                    pending_result_stage_generation = stage_generation,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint = expected_code_fingerprint,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE id = ? AND status = 'RUNNING'
                """, evidence, evidence, running.ticketId());
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                running.fence(), SUCCEEDED, evidence, evidence, null);
        RunningTurn completed = new RunningTurn(
                running.turnId(), running.operationId(), running.ticketId(),
                running.leaseId(), running.fence(), result);
        DispatchTicket.DeliveryReceipt receipt = plan.deliverTaskTurn(
                completed.owner(), completed.fence(), result);
        running.result = result;
        return receipt;
    }

    private static DispatchTicket.DeliveryReceipt deliverFailed(
            PlanRuntimeCoordinator plan,
            JdbcTemplate jdbc,
            RunningTurn running,
            String error)
    {
        jdbc.update("""
                UPDATE agent_execution
                SET status = 'FAILED', finished_at_ms = ?, raw_result = ?
                WHERE ticket_id = ? AND infrastructure_attempt = 1
                """, NOW.plusSeconds(1).toEpochMilli(), error,
                running.ticketId());
        jdbc.update("""
                UPDATE capacity_lease
                SET released_at_ms = ?, release_reason = 'failed'
                WHERE id = ? AND released_at_ms IS NULL
                """, NOW.plusSeconds(1).toEpochMilli(), running.leaseId());
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = ?,
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = stage_id,
                    pending_result_stage_generation = stage_generation,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint = expected_code_fingerprint,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE id = ? AND status = 'RUNNING'
                """, error, running.ticketId());
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                running.fence(), FAILED, null, null, error);
        running.result = result;
        return plan.deliverTaskTurn(
                running.owner(), running.fence(), result);
    }

    private DataSource database(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        DataSource dataSource = SqliteTestPools.open(url);
        return dataSource;
    }

    private static Runtime runtime(DataSource dataSource)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TrunkManager.Store trunkStore;
        TaskManager.Store taskStore;
        StageManager.Store stageStore;
        PlanStageManager.ApprovalStore approvalStore;
        PlanStageManager.RevisionStore revisionStore;
        PlanStageManager.UserWaitStore userWaitStore;
        LocalDevelopmentStageManager.EvidenceStore localEvidence;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(
                    DataSourceTransactionManager.class, () -> transactionManager);
            context.registerBean(
                    TransactionTemplate.class,
                    () -> new TransactionTemplate(transactionManager));
            context.registerBean(
                    SqliteDispatchWakeStore.class,
                    () -> new SqliteDispatchWakeStore(jdbc));
            context.scan(
                    "com.bytequay.app.developmentflow.trunk.persistence",
                    "com.bytequay.app.developmentflow.task.persistence",
                    "com.bytequay.app.developmentflow.stage.persistence");
            context.refresh();
            trunkStore = context.getBean(TrunkManager.Store.class);
            taskStore = context.getBean(TaskManager.Store.class);
            stageStore = context.getBean(StageManager.Store.class);
            approvalStore = context.getBean(PlanStageManager.ApprovalStore.class);
            revisionStore = context.getBean(PlanStageManager.RevisionStore.class);
            userWaitStore = context.getBean(PlanStageManager.UserWaitStore.class);
            localEvidence = context.getBean(
                    LocalDevelopmentStageManager.EvidenceStore.class);
        }
        TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
        TrunkManager trunks = new TrunkManager(commands, trunkStore);
        TaskManager tasks = new TaskManager(commands, taskStore);
        SqlitePlanRuntimeStore planRuntimeStore = new SqlitePlanRuntimeStore(jdbc);
        PlanStageManager plan = new PlanStageManager(
                commands, stageStore, approvalStore, revisionStore,
                planRuntimeStore, userWaitStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, localEvidence);
        PlanToLocalHandoff planToLocal = new PlanToLocalHandoff(
                commands, plan, tasks, local);
        ObjectMapper json = new ObjectMapper();
        LocalDevelopmentRuntimeCoordinator localRuntime =
                new LocalDevelopmentRuntimeCoordinator(
                        commands, tasks, local,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc),
                        mock(PRService.class), json,
                        Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC), 53123);
        TaskCreationHandoff creation = new TaskCreationHandoff(
                commands, trunks, tasks, new IdGenerator(ignored -> 1));
        PlanRuntimeCoordinator coordinator = new PlanRuntimeCoordinator(
                commands, tasks, plan, planRuntimeStore,
                planToLocal, localRuntime, json,
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC), 53123);
        ReplanHandoff replan = new ReplanHandoff(
                commands, plan, tasks, plan,
                coordinator::startReplanDraftInCommand);
        ReplanHandoff localReplan = new ReplanHandoff(
                commands, local, tasks, plan,
                coordinator::startReplanDraftInCommand);
        SqliteTaskControlRuntimeStore controlStore =
                new SqliteTaskControlRuntimeStore(jdbc, transactionManager);
        return new Runtime(
                creation, coordinator, replan, localReplan, tasks, plan,
                planRuntimeStore, controlStore);
    }

    private static void seedTrunk(JdbcTemplate jdbc, Path repositoryRoot)
    {
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES ('acme', 'widget', ?)
                """, repositoryRoot.toString());
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES (?, ?, '', 0, 1, 1)
                """, WORKSPACE, WORKSPACE);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES (?, ?, 'main', 0, 1)
                """, WORKSPACE, REPOSITORY);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'review-model',
                    0, 0, 0, 1, 1, ?, 'build', 4, 'V2', 'ACTIVE')
                """, TRUNK, TRUNK, WORKSPACE);
    }

    private static TaskCreationHandoff.Command creationCommand(Path repositoryRoot)
    {
        return creationCommand(repositoryRoot, false);
    }

    private static TaskCreationHandoff.Command creationCommand(
            Path repositoryRoot, boolean autoApprove)
    {
        TaskAssignment.Direct repositories = new TaskAssignment.Direct(REPOSITORY);
        TaskAssignment assignment = new TaskAssignment.NewFromTrunk(
                new TaskAssignment.Identity(
                        "assignment-plan", TRUNK, "authorization-plan", "user", NOW),
                new TaskAssignment.DirectUser(), "plan seed", "implement request");
        TaskCreationInput input = new TaskCreationInput(
                WORKSPACE, assignment,
                new TaskCreationInput.TaskPolicy(
                        "policy-plan", TRUNK, 1, "TRUNK", autoApprove, false,
                        1, 3, 3, true, Optional.of("permissions"), "user", NOW),
                new TaskCreationInput.FreshRemoteBase(repositories, "main"),
                new TaskCreationInput.EngineSnapshot(
                        "codex", "review-model", "engine-plan"),
                new TaskCreationInput.WorkModelSnapshot("""
                        {"kind":"CLI","agentOrProvider":"codex",\
                        "model":"review-model","account":null,\
                        "reasoningEffort":"high"}
                        """.replace("\\\n", "")),
                NOW);
        return new TaskCreationHandoff.Command(
                new TrunkManager.TaskCreationCommand("create-plan", "user", 0, input),
                repositoryRoot);
    }

    private static void markProvisionResultPending(
            JdbcTemplate jdbc, String operationId, String evidenceJson)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?, pending_result_evidence = ?,
                    pending_result_error = NULL,
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE operation_id = ? AND status = 'REQUESTED'
                """, evidenceJson, evidenceJson, operationId);
    }

    private static void markProvisionFailurePending(
            JdbcTemplate jdbc, String operationId, String error)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = ?,
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE operation_id = ? AND status = 'REQUESTED'
                """, error, operationId);
    }

    private static void finalizePendingTickets(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE agent_execution
                SET status = (
                        SELECT CASE ticket.pending_result_outcome
                            WHEN 'SUCCEEDED' THEN 'SUCCEEDED'
                            WHEN 'FAILED' THEN 'FAILED'
                            WHEN 'CANCELED' THEN 'CANCELED'
                            ELSE 'UNKNOWN'
                        END
                        FROM dispatch_ticket ticket
                        WHERE ticket.id = agent_execution.ticket_id),
                    heartbeat_at_ms = COALESCE(heartbeat_at_ms, ?),
                    finished_at_ms = COALESCE(finished_at_ms, ?),
                    raw_result = (
                        SELECT json_object(
                            'fence', json_object(
                                'taskEpoch', ticket.pending_result_task_epoch,
                                'stageId', ticket.pending_result_stage_id,
                                'stageGeneration',
                                    ticket.pending_result_stage_generation,
                                'operationId', ticket.pending_result_operation_id,
                                'attempt', ticket.pending_result_attempt,
                                'expectedCodeFingerprint',
                                    ticket.pending_result_expected_code_fingerprint,
                                'expectedHeadSha',
                                    ticket.pending_result_expected_head_sha,
                                'expectedBaseSha',
                                    ticket.pending_result_expected_base_sha),
                            'outcome', ticket.pending_result_outcome,
                            'payloadJson', ticket.pending_result_payload,
                            'evidenceJson', ticket.pending_result_evidence,
                            'error', ticket.pending_result_error)
                        FROM dispatch_ticket ticket
                        WHERE ticket.id = agent_execution.ticket_id)
                WHERE EXISTS (
                    SELECT 1 FROM dispatch_ticket ticket
                    WHERE ticket.id = agent_execution.ticket_id
                      AND ticket.status = 'RESULT_PENDING'
                      AND ticket.async_family = 'AGENT_TURN'
                      AND agent_execution.infrastructure_attempt =
                          ticket.infrastructure_attempts)
                """, NOW.plusSeconds(5).toEpochMilli(),
                NOW.plusSeconds(5).toEpochMilli());
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
                    delivery_evidence = '{}', completed_at_ms = ?
                WHERE status = 'RESULT_PENDING'
                """, NOW.plusSeconds(5).toEpochMilli());
    }

    private static void finishAsUserWait(
            JdbcTemplate jdbc, RunningTurn running)
    {
        jdbc.update("""
                UPDATE agent_execution
                SET status = 'SUCCEEDED', finished_at_ms = ?, raw_result = '{}'
                WHERE ticket_id = ? AND infrastructure_attempt = 1
                """, NOW.plusSeconds(3).toEpochMilli(), running.ticketId());
        jdbc.update("""
                UPDATE capacity_lease
                SET released_at_ms = ?, release_reason = 'user_wait'
                WHERE id = ? AND released_at_ms IS NULL
                """, NOW.plusSeconds(3).toEpochMilli(), running.leaseId());
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_error = NULL,
                    pending_result_task_epoch = task_epoch,
                    pending_result_stage_id = stage_id,
                    pending_result_stage_generation = stage_generation,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint = expected_code_fingerprint,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE id = ? AND status = 'RUNNING'
                """, running.ticketId());
    }

    private static int count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static long taskVersion(JdbcTemplate jdbc, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = ?",
                Long.class, taskId);
    }

    private static int countWhere(JdbcTemplate jdbc, String table, String predicate)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
                Integer.class);
    }

    private record Runtime(
            TaskCreationHandoff creation,
            PlanRuntimeCoordinator plan,
            ReplanHandoff replan,
            ReplanHandoff localReplan,
            TaskManager tasks,
            PlanStageManager planManager,
            SqlitePlanRuntimeStore planStore,
            SqliteTaskControlRuntimeStore controlStore) {}

    private record Bootstrapped(
            DataSource dataSource,
            JdbcTemplate jdbc,
            Runtime runtime,
            String taskId) {}

    private record ReviewedPlan(
            String stageId,
            String revisionId,
            String selfReviewId,
            String followupId) {}

    private static final class RunningTurn
    {
        private final String turnId;
        private final String operationId;
        private final String ticketId;
        private final String leaseId;
        private final DispatchTicket.OperationFence fence;
        private DispatchTicket.DispatchResult result;

        private RunningTurn(
                String turnId,
                String operationId,
                String ticketId,
                String leaseId,
                DispatchTicket.OperationFence fence,
                DispatchTicket.DispatchResult result)
        {
            this.turnId = turnId;
            this.operationId = operationId;
            this.ticketId = ticketId;
            this.leaseId = leaseId;
            this.fence = fence;
            this.result = result;
        }

        private String turnId() { return turnId; }

        private String operationId() { return operationId; }

        private String ticketId() { return ticketId; }

        private String leaseId() { return leaseId; }

        private DispatchTicket.OperationFence fence() { return fence; }

        private DispatchTicket.DispatchResult result() { return result; }

        private DispatchTicket.OwnerReference owner()
        {
            return new DispatchTicket.OwnerReference(
                    DispatchTicket.OwnerKind.TASK_TURN, turnId,
                    PlanRuntimeCoordinator.TURN_CALLBACK);
        }
    }
}
