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
package com.bytequay.app.developmentflow.stage.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.ProvisionToPlanHandoff;
import com.bytequay.app.developmentflow.stage.StageEndReason;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestV2AggregateHandoffStore
{
    @TempDir
    private Path tempDir;

    @Test
    void provisioningHandoffReplaysAfterRestartAndRollsBackAcrossOwners()
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("handoff.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedAcceptedProvisioning(jdbc, "task-1", "operation-1", 1);
        seedAcceptedProvisioning(
                jdbc, "task-rollback", "operation-rollback", 2);
        Flyway.configure().dataSource(dataSource).target("235").load().migrate();

        Stores first = stores(dataSource);
        ProvisionToPlanHandoff handoff = handoff(dataSource, first);
        TaskManager.ProvisioningCommand command = command(
                "task-1", "operation-1", "plan-1", 1);
        ProvisionToPlanHandoff.Result applied = handoff.accept(command);

        assertThat(applied.task().currentStageId()).isEqualTo("plan-1");
        assertThat(applied.plan().disposition().name()).isEqualTo("APPLIED");
        assertThat(count(jdbc, "task_transition", "task-1")).isEqualTo(1);
        assertThat(count(jdbc, "task_command_receipt", "task-1")).isEqualTo(1);
        assertThat(count(jdbc, "stage_transition", "plan-1")).isEqualTo(1);
        assertThat(count(jdbc, "stage_command_receipt", "plan-1")).isEqualTo(1);
        assertThat(count(jdbc, "plan_stage", "plan-1")).isEqualTo(1);
        assertThat(first.stages().findOwner("task-1", "plan-1").orElseThrow().stage())
                .isEqualTo(applied.plan().state());
        seedPlanEvidence(jdbc);
        assertThat(first.approvals().findLatestApproval(
                "task-1", "plan-1", 1, "approval-2"))
                .contains(new PlanStageManager.ApprovalEvidence(
                        "task-1", "plan-1", 1, "approval-2",
                        "revision-2", "review-2", "digest-2"));
        assertThat(first.revisions().findRevision(
                "task-1", "plan-1", 1, "revision-2"))
                .contains(new PlanStageManager.RevisionEvidence(
                        "task-1", "plan-1", 1, "revision-2",
                        "revision-1", "digest-2"));
        assertThat(first.localEvidence().findPublishAuthorization(
                "task-1", "plan-1", 1, "missing-authorization")).isEmpty();

        jdbc.update("""
                INSERT INTO plan_revision(
                    id, plan_stage_id, revision, content, content_digest,
                    source, created_by, created_at_ms)
                VALUES ('revision-3', 'plan-1', 3, 'newest plan', 'digest-3',
                    'USER_EDIT', 'user', 12)
                """);
        assertThat(first.approvals().findLatestApproval(
                "task-1", "plan-1", 1, "approval-2")).isEmpty();
        assertThat(first.revisions().findRevision(
                "task-1", "plan-1", 1, "revision-2")).isEmpty();
        assertThat(first.revisions().findRevision(
                "task-1", "plan-1", 1, "revision-3"))
                .contains(new PlanStageManager.RevisionEvidence(
                        "task-1", "plan-1", 1, "revision-3",
                        "revision-2", "digest-3"));

        StageManager.State expected = first.stages()
                .findOwner("task-1", "plan-1").orElseThrow().stage();
        assertThatThrownBy(() -> new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource)).execute("task-1", () -> {
                    jdbc.update("""
                            UPDATE tasks SET epoch = 2, aggregate_version = 2
                            WHERE id = 'task-1'
                            """);
                    return first.stages().commit(
                            "owner-race", "SEAL_FOR_REPLAN", "user",
                            1L, 1L, 0L, null, null, "race-proof", expected,
                            new StageManager.State(
                                    "plan-1", "task-1", expected.kind(), 1, 1,
                                    expected.checkpoint(),
                                    StageEndReason.SUPERSEDED_BY_REPLAN, null));
                }))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(CONCURRENT_UPDATE));
        assertThat(jdbc.queryForObject(
                "SELECT epoch FROM tasks WHERE id = 'task-1'", Long.class)).isOne();
        first.close();

        Stores restarted = stores(dataSource);
        assertThat(restarted.stages().findOwner(
                "task-1", "plan-1").orElseThrow().stage())
                .isEqualTo(applied.plan().state());
        ProvisionToPlanHandoff.Result duplicate = handoff(dataSource, restarted)
                .accept(command);
        assertThat(duplicate.plan().disposition().name()).isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "task_transition", "task-1")).isEqualTo(1);
        assertThat(count(jdbc, "stage_transition", "plan-1")).isEqualTo(1);

        TaskManager.ProvisioningCommand invalidGeneration = command(
                "task-rollback", "operation-rollback", "plan-rollback", 2);
        assertThatThrownBy(() -> handoff(dataSource, restarted).accept(invalidGeneration))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM tasks WHERE id = 'task-rollback'
                """, String.class)).isEqualTo("PROVISIONING");
        assertThat(jdbc.queryForObject("""
                SELECT aggregate_version FROM tasks WHERE id = 'task-rollback'
                """, Long.class)).isZero();
        assertThat(count(jdbc, "task_current_stage", "task-rollback")).isZero();
        assertThat(count(jdbc, "task_transition", "task-rollback")).isZero();
        assertThat(count(jdbc, "task_command_receipt", "task-rollback")).isZero();
        assertThat(count(jdbc, "stage", "plan-rollback")).isZero();
        restarted.close();
    }

    private static ProvisionToPlanHandoff handoff(
            SQLiteDataSource dataSource, Stores stores)
    {
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TaskManager tasks = new TaskManager(commands, stores.tasks());
        PlanStageManager plan = new PlanStageManager(
                commands, stores.stages(), stores.approvals(), stores.revisions());
        return new ProvisionToPlanHandoff(commands, tasks, plan);
    }

    private static Stores stores(SQLiteDataSource dataSource)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(DataSourceTransactionManager.class,
                () -> transactionManager);
        context.registerBean(TransactionTemplate.class,
                () -> new TransactionTemplate(transactionManager));
        context.registerBean(SqliteDispatchWakeStore.class,
                () -> new SqliteDispatchWakeStore(jdbc));
        context.scan(
                "com.bytequay.app.developmentflow.task.persistence",
                "com.bytequay.app.developmentflow.stage.persistence");
        context.refresh();
        return new Stores(
                context,
                context.getBean(TaskManager.Store.class),
                context.getBean(StageManager.Store.class),
                context.getBean(PlanStageManager.ApprovalStore.class),
                context.getBean(PlanStageManager.RevisionStore.class),
                context.getBean(LocalDevelopmentStageManager.EvidenceStore.class));
    }

    private static TaskManager.ProvisioningCommand command(
            String taskId, String operationId, String stageId, long generation)
    {
        return new TaskManager.ProvisioningCommand(
                "activate-" + taskId,
                "dispatcher",
                taskId,
                0,
                new ResultFence(
                        1, null, 0, operationId, 1,
                        "fingerprint-" + taskId, "base", "base"),
                stageId,
                generation);
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void seedTrunk(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 1, 'user', 2)
                """);
    }

    private static void seedAcceptedProvisioning(
            JdbcTemplate jdbc, String taskId, String operationId, int sequence)
    {
        String assignmentId = "assignment-" + taskId;
        String operationRowId = "provision-" + taskId;
        String ticketId = "ticket-" + taskId;
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', 'base', 'seed', 'build',
                    'user', 2)
                """, assignmentId);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', ?, 'policy-1')
                """, taskId, sequence, assignmentId);
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES (?, ?, 'policy-1', 'DIRECT_USER', 'acme/widget',
                    'acme/widget', 'base', 'engine-v1', 'model-v1', 3)
                """, taskId, assignmentId);
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES (?, ?, 1, ?, ?, 1, 'acme/widget', 'base', ?, ?,
                    'REQUESTED', 4)
                """,
                operationRowId, taskId, assignmentId, operationId,
                "dev/" + taskId, "/tmp/" + taskId);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES (?, ?, 'PROVISION_TASK', 'LOCAL_GIT', 'TASK', ?,
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, 1, 'base', 'REQUESTED', 4)
                """, ticketId, operationId, taskId, taskId);
        jdbc.update("""
                UPDATE provision_task_operation SET status = 'DISPATCHED'
                WHERE id = ?
                """, operationRowId);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'exact local Git result',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = ?,
                    pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'base'
                WHERE id = ?
                """, operationId, ticketId);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base',
                    result_head_sha = 'base', result_code_fingerprint = ?,
                    completed_at_ms = 5
                WHERE id = ?
                """, "fingerprint-" + taskId, operationRowId);
        jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, 'acme/widget', 'acme/widget', ?, ?,
                    'base', 'base', ?, 5, 5)
                """, taskId, operationRowId, "dev/" + taskId,
                "/tmp/" + taskId, "fingerprint-" + taskId);
    }

    private static void seedPlanEvidence(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO plan_revision(
                    id, plan_stage_id, revision, content, content_digest,
                    source, created_by, created_at_ms)
                VALUES ('revision-1', 'plan-1', 1, 'first plan', 'digest-1',
                    'AGENT', 'agent', 6)
                """);
        jdbc.update("""
                INSERT INTO plan_revision(
                    id, plan_stage_id, revision, content, content_digest,
                    source, created_by, created_at_ms)
                VALUES ('revision-2', 'plan-1', 2, 'revised plan', 'digest-2',
                    'USER_EDIT', 'user', 7)
                """);
        jdbc.update("""
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-1', 'task-1', 'openai', 'review-model',
                    'engine-v1', 8)
                """);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt, task_epoch,
                    trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                SELECT 'task-turn-2', code.task_id, 'PLAN_SELF_REVIEW',
                       'REQUESTED', 'review-operation-2', 1, 1, 'plan-1', 1,
                       code.code_fingerprint, code.local_head_sha, code.base_sha,
                       'API', json_object(
                           'schemaVersion', 1,
                           'transport', 'API',
                           'provider', 'openai',
                           'model', 'review-model',
                           'workingDirectory', code.worktree_path,
                           'prompt', 'review revision-2',
                           'toolEndpoint', json_object(
                               'url', 'http://127.0.0.1:53123/api/v2/task-turns/'
                                   || 'task-turn-2/operations/review-operation-2/mcp',
                               'ownerKind', 'TASK_TURN',
                               'ownerId', 'task-turn-2',
                               'operationId', 'review-operation-2',
                               'profile', 'TASK_BRAIN_READ_ONLY',
                               'approvalPromptTool',
                                   'mcp__bytequay__approval_prompt')),
                       8
                  FROM task_code_identity code
                 WHERE code.task_id = 'task-1'
                """);
        jdbc.update("""
                INSERT INTO plan_self_review(
                    id, plan_revision_id, task_turn_id, task_epoch,
                    reviewed_digest, status, requested_at_ms)
                VALUES ('review-2', 'revision-2', 'task-turn-2', 1,
                    'digest-2', 'REQUESTED', 8)
                """);
        jdbc.update("""
                INSERT INTO plan_self_review_attempt(
                    self_review_id, attempt, task_turn_id, operation_id,
                    predecessor_turn_id, requested_at_ms)
                VALUES ('review-2', 1, 'task-turn-2', 'review-operation-2',
                    NULL, 8)
                """);
        jdbc.update("""
                UPDATE task_turn
                SET status = 'SUCCEEDED', started_at_ms = 8, finished_at_ms = 9
                WHERE id = 'task-turn-2'
                """);
        jdbc.update("""
                UPDATE plan_self_review
                SET status = 'SUCCEEDED', verdict = 'APPROVED', completed_at_ms = 9
                WHERE id = 'review-2'
                """);
        jdbc.update("""
                INSERT INTO plan_approval(
                    id, plan_revision_id, self_review_id, approval_kind,
                    policy_revision_id, actor, approved_at_ms)
                VALUES ('approval-2', 'revision-2', 'review-2', 'HUMAN',
                    'policy-1', 'user', 10)
                """);
    }

    private static int count(JdbcTemplate jdbc, String table, String ownerId)
    {
        String ownerColumn = switch (table) {
            case "task_current_stage", "task_transition", "task_command_receipt" -> "task_id";
            case "stage_transition", "stage_command_receipt", "plan_stage" -> "stage_id";
            case "stage" -> "id";
            default -> throw new IllegalArgumentException("Unknown table: " + table);
        };
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + ownerColumn + " = ?",
                Integer.class,
                ownerId);
    }

    private record Stores(
            AnnotationConfigApplicationContext context,
            TaskManager.Store tasks,
            StageManager.Store stages,
            PlanStageManager.ApprovalStore approvals,
            PlanStageManager.RevisionStore revisions,
            LocalDevelopmentStageManager.EvidenceStore localEvidence)
    {
        private void close()
        {
            context.close();
        }
    }
}
