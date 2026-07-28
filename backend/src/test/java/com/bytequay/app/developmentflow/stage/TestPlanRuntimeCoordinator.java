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
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
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
                VALUES (?, 'CLI_AGENT', 'openai', ?, 'IDLE', 'review-model',
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
                        "openai", "review-model", "engine-plan"),
                new TaskCreationInput.WorkModelSnapshot("""
                        {"kind":"CLI","agentOrProvider":"openai",\
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
}
