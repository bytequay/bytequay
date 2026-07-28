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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.stage.persistence.SqlitePlanRuntimeStore;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationHandoff;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;

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
        SQLiteDataSource dataSource = database("plan-runtime.db");
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
    void rejectsTerminalProvisionFailureUntilTaskRecoveryOwnsIt()
    {
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, "task-failed",
                PlanRuntimeCoordinator.PROVISION_CALLBACK);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, "provision-failed", 1,
                null, null, null);
        DispatchTicket.DeliveryReceipt rejected = runtime(
                database("failed-provision.db")).plan().deliverProvisioning(
                        owner, fence, new DispatchTicket.DispatchResult(
                                fence, FAILED, null, null, "git failed"));

        assertThat(rejected.acceptance()).isEqualTo(REJECTED);
        assertThat(rejected.evidenceJson()).contains("explicit recovery");
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
    }

    private Bootstrapped bootstrap(String name)
            throws Exception
    {
        Path repositoryRoot = tempDir.resolve(name + "-repo").toAbsolutePath();
        SQLiteDataSource dataSource = database(name);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc, repositoryRoot);
        Runtime runtime = runtime(dataSource);
        TaskCreationHandoff.Result created = runtime.creation().create(
                creationCommand(repositoryRoot));
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

    private SQLiteDataSource database(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("235").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static Runtime runtime(SQLiteDataSource dataSource)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TrunkManager.Store trunkStore;
        TaskManager.Store taskStore;
        StageManager.Store stageStore;
        PlanStageManager.ApprovalStore approvalStore;
        PlanStageManager.RevisionStore revisionStore;
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
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
        }
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TrunkManager trunks = new TrunkManager(commands, trunkStore);
        TaskManager tasks = new TaskManager(commands, taskStore);
        PlanStageManager plan = new PlanStageManager(
                commands, stageStore, approvalStore, revisionStore);
        TaskCreationHandoff creation = new TaskCreationHandoff(
                commands, trunks, tasks, new IdGenerator(ignored -> 1));
        PlanRuntimeCoordinator coordinator = new PlanRuntimeCoordinator(
                commands, tasks, plan, new SqlitePlanRuntimeStore(jdbc),
                new ObjectMapper(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC),
                53123);
        return new Runtime(creation, coordinator);
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
        TaskAssignment.Direct repositories = new TaskAssignment.Direct(REPOSITORY);
        TaskAssignment assignment = new TaskAssignment.NewFromTrunk(
                new TaskAssignment.Identity(
                        "assignment-plan", TRUNK, "authorization-plan", "user", NOW),
                new TaskAssignment.DirectUser(), "plan seed", "implement request");
        TaskCreationInput input = new TaskCreationInput(
                WORKSPACE, assignment,
                new TaskCreationInput.TaskPolicy(
                        "policy-plan", TRUNK, 1, "TRUNK", true, false,
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

    private static int count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static int countWhere(JdbcTemplate jdbc, String table, String predicate)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
                Integer.class);
    }

    private record Runtime(
            TaskCreationHandoff creation,
            PlanRuntimeCoordinator plan) {}

    private record Bootstrapped(
            SQLiteDataSource dataSource,
            JdbcTemplate jdbc,
            Runtime runtime,
            String taskId) {}

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
