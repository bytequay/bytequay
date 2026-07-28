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

import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.persistence.SqliteAgentTurnOperationStore;
import com.bytequay.app.developmentflow.stage.LocalBrainResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentRuntimeCoordinator;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TestV2LocalStageStore
{
    @TempDir
    private Path tempDir;

    @Test
    void changedCodeSubjectCanAdvanceImplementationToValidation()
    {
        SQLiteDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        Flyway.configure().dataSource(dataSource).target("237").load().migrate();
        ResultFence source = new ResultFence(
                1, "local-stage", 1, "implementation-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        seedImplementationRequest(jdbc);

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2StageStore store = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, store, store);
        commands.execute("task-1", () -> local.requestImplementationInCommand(
                new StageManager.Command(
                        "request-implementation", "runtime", "task-1",
                        1, "local-stage", 1, 0),
                source,
                "implementation-request"));

        jdbc.update("""
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 6, finished_at_ms = 7
                WHERE id = 'implementation-turn'
                """);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, decisions_json, invariants_json,
                    tricky_spots_json, test_map_json, followups_json,
                    created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs, source_code_fingerprint, source_head_sha,
                    source_base_sha)
                VALUES (
                    'report-1', 'task-1', 'implemented', '[]', '[]', '[]',
                    '[]', '[]', 7, 'V2', 'local-stage', 1, 1,
                    'implementation-turn', 1,
                    'fingerprint-new', 'head-new', 'head-old',
                    'implement the approved plan', '', 'changed one file',
                    '', '', '', '[]',
                    'fingerprint-old', 'head-old', 'head-old')
                """);

        StageManager.State accepted = commands.execute("task-1", () ->
                local.acceptImplementationResultInCommand(
                        new StageManager.ResultCommand(
                                "accept-implementation", "dispatcher", "task-1", source),
                        "report-1")).state();

        assertThat(accepted.checkpoint().name()).isEqualTo("VALIDATING");
        assertThat(accepted.pendingResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT head_sha FROM dev_report WHERE id = 'report-1'",
                String.class)).isEqualTo("head-new");
        assertThat(jdbc.queryForObject(
                "SELECT source_head_sha FROM dev_report WHERE id = 'report-1'",
                String.class)).isEqualTo("head-old");
    }

    @Test
    void brainBudgetExhaustionTerminalizesAndClearsExactTaskFence()
            throws Exception
    {
        SQLiteDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        Flyway.configure().dataSource(dataSource).target("237").load().migrate();
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "brain-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markResultPending(jdbc, "brain-ticket", fence);

        AgentTurnOperationHandler.ExactTurn exactTurn =
                new SqliteAgentTurnOperationStore(jdbc)
                        .find(DispatchTicket.OwnerKind.TASK_TURN, "brain-turn")
                        .orElseThrow();
        assertThat(exactTurn.purpose()).isEqualTo("DEVELOPMENT_BRAIN_REVIEW");
        assertThat(exactTurn.operationId()).isEqualTo("brain-operation");

        SqliteLocalDevelopmentRuntimeStore runtime =
                new SqliteLocalDevelopmentRuntimeStore(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = brainDelivery(
                mapper, fence, DispatchTicket.Outcome.FAILED,
                AgentTurnOperationHandler.Disposition.PROVIDER_FAILED,
                "", "BRAIN_BUDGET_EXHAUSTED");
        DispatchTicket.DeliveryReceipt accepted = new LocalBrainResultDeliveryPort(
                runtime(commands, tasks, local, runtime, mapper))
                .deliver(delivery);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        TaskManager.State restarted = taskStore.findById("task-1").orElseThrow();
        assertThat(restarted.pendingBrainResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_turn WHERE id = 'brain-turn'",
                String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("LOCAL_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_blocker WHERE blocker_type = "
                        + "'BRAIN_BUDGET_EXHAUSTED' AND status = 'OPEN'",
                Integer.class)).isOne();

        TaskManager restartedTasks = new TaskManager(
                commands, taskStore(jdbc, transactions));
        LocalDevelopmentStageManager restartedLocal =
                new LocalDevelopmentStageManager(
                        commands, new V2StageStore(jdbc), new V2StageStore(jdbc));
        DispatchTicket.DeliveryReceipt duplicate = new LocalBrainResultDeliveryPort(
                runtime(
                        commands, restartedTasks, restartedLocal,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper))
                .deliver(delivery);
        assertThat(duplicate.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_brain_turn_delivery_receipt",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_brain_budget_receipt",
                Integer.class)).isOne();
    }

    @Test
    void brainChangesVerdictCreatesOneExactStageOwnedFixTurn()
            throws Exception
    {
        SQLiteDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        Flyway.configure().dataSource(dataSource).target("237").load().migrate();
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "brain-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);

        ObjectMapper mapper = new ObjectMapper();
        AgentTurnOwnerResultCodec.OwnerResult delivery = brainDelivery(
                mapper, fence, DispatchTicket.Outcome.SUCCEEDED,
                AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                """
                        {"schemaVersion":1,"verdict":"CHANGES_REQUESTED",
                         "summary":"one issue remains","findings":["fix A"]}
                        """,
                null);
        DispatchTicket.DeliveryReceipt accepted = new LocalBrainResultDeliveryPort(
                runtime(
                        commands, tasks, local,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper))
                .deliver(delivery);

        assertThat(accepted.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        TaskManager.State restarted = taskStore.findById("task-1").orElseThrow();
        assertThat(restarted.pendingBrainResult()).isNull();
        assertThat(restarted.lastBrainVerdict())
                .isEqualTo(TaskManager.BrainVerdict.CHANGES_REQUESTED);
        StageManager.State restartedStage = stageStore
                .findOwner("task-1", "local-stage").orElseThrow().stage();
        assertThat(restartedStage.checkpoint().name())
                .isEqualTo("ADDRESSING_BRAIN_FINDINGS");
        assertThat(restartedStage.pendingResult()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT verdict FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("CHANGES_REQUESTED");

        String fixTurnId = jdbc.queryForObject(
                "SELECT id FROM stage_turn WHERE purpose = 'ADDRESS_BRAIN_FINDINGS'",
                String.class);
        AgentTurnOperationHandler.ExactTurn fixTurn =
                new SqliteAgentTurnOperationStore(jdbc)
                        .find(DispatchTicket.OwnerKind.STAGE_TURN, fixTurnId)
                        .orElseThrow();
        assertThat(fixTurn.taskId()).isEqualTo("task-1");
        assertThat(fixTurn.stageId()).isEqualTo("local-stage");
        assertThat(fixTurn.expectedCodeFingerprint()).isEqualTo("fingerprint-old");
        assertThat(fixTurn.brainProvider()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM dispatch_ticket ticket
                JOIN stage_turn turn ON turn.operation_id = ticket.operation_id
                WHERE turn.id = ? AND ticket.owner_kind = 'STAGE_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'STAGE_TURN_RESULT'
                  AND ticket.writer_required = 1
                """, Integer.class, fixTurnId)).isOne();
    }

    @Test
    void taskCancellationSupersedesItsLateBrainResult()
            throws Exception
    {
        SQLiteDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedLocalOwner(jdbc);
        Flyway.configure().dataSource(dataSource).target("237").load().migrate();
        seedBrainReview(jdbc);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        TaskCommandExecutor commands = new TaskCommandExecutor(transactions);
        V2StageStore stageStore = new V2StageStore(jdbc);
        TaskManager.Store taskStore = taskStore(jdbc, transactions);
        TaskManager tasks = new TaskManager(commands, taskStore);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stageStore, stageStore);
        ResultFence fence = new ResultFence(
                1, "local-stage", 1, "brain-operation", 1,
                "fingerprint-old", "head-old", "head-old");
        commands.execute("task-1", () -> tasks.requestBrainReviewInCommand(
                new TaskManager.BrainReviewRequestCommand(
                        "request-brain", "runtime", "task-1", 1, 1,
                        "brain-episode", fence)));
        markSucceededResultPending(jdbc, "brain-ticket", fence);
        tasks.requestCancel(new TaskManager.Command(
                "cancel-task", "user", "task-1", 1, 2));

        ObjectMapper mapper = new ObjectMapper();
        DispatchTicket.DeliveryReceipt receipt = new LocalBrainResultDeliveryPort(
                runtime(
                        commands, tasks, local,
                        new SqliteLocalDevelopmentRuntimeStore(jdbc), mapper))
                .deliver(brainDelivery(
                        mapper, fence, DispatchTicket.Outcome.SUCCEEDED,
                        AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED,
                        """
                                {"schemaVersion":1,"verdict":"APPROVED",
                                 "summary":"late","findings":[]}
                                """,
                        null));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        TaskManager.State canceled = taskStore.findById("task-1").orElseThrow();
        assertThat(canceled.lifecycle().name()).isEqualTo("CANCELING");
        assertThat(canceled.epoch()).isEqualTo(2);
        assertThat(canceled.pendingBrainResult()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM task_turn WHERE id = 'brain-turn'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM brain_review_episode WHERE id = 'brain-episode'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT checkpoint FROM stage WHERE id = 'local-stage'",
                String.class)).isEqualTo("BRAIN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_blocker", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_turn "
                        + "WHERE purpose = 'ADDRESS_BRAIN_FINDINGS'",
                Integer.class)).isZero();
    }

    private static LocalDevelopmentRuntimeCoordinator runtime(
            TaskCommandExecutor commands,
            TaskManager tasks,
            LocalDevelopmentStageManager local,
            SqliteLocalDevelopmentRuntimeStore store,
            ObjectMapper mapper)
    {
        return new LocalDevelopmentRuntimeCoordinator(
                commands, tasks, local, store,
                mock(CodeFingerprints.class), mock(GitRunner.class), mapper,
                Clock.fixed(Instant.ofEpochMilli(20), ZoneOffset.UTC), 8080);
    }

    private static AgentTurnOwnerResultCodec.OwnerResult brainDelivery(
            ObjectMapper mapper,
            ResultFence fence,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            String finalText,
            String error)
            throws Exception
    {
        DispatchTicket.OperationFence operationFence = new DispatchTicket.OperationFence(
                fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
        DispatchTicket.OwnerReference owner = new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK_TURN,
                "brain-turn", "TASK_TURN_RESULT");
        AgentTurnOperationHandler.RawResult payload =
                new AgentTurnOperationHandler.RawResult(
                        1, "brain-turn", DispatchTicket.OwnerKind.TASK_TURN,
                        "DEVELOPMENT_BRAIN_REVIEW",
                        AgentTurnProviderSession.Transport.API, "openai", "session",
                        finalText, 1, 1, 1, null, disposition, error);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                operationFence, outcome, mapper.writeValueAsString(payload),
                "{}", error);
        return new AgentTurnOwnerResultCodec(mapper).decode(
                owner, operationFence, raw);
    }

    private SQLiteDataSource database()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("local-store.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void seedLocalOwner(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK', 'head-old',
                    'seed', 'build', 'user', 2)
                """);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'user', 2)
                """);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 1, 0, 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES ('task-1', 'assignment-1', 'policy-1', 'DIRECT_USER',
                    'acme/widget', 'acme/widget', 'head-old', 'engine',
                    '{"kind":"API","agentOrProvider":"openai",'
                    || '"model":"review-model","account":null,'
                    || '"reasoningEffort":null}', 2)
                """);
        jdbc.update("""
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-1', 'task-1', 'openai', 'review-model', 'engine', 2)
                """);
        seedProvisionedCode(jdbc);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms)
                VALUES ('local-stage', 'task-1', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 3)
                """);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'local-stage', 1)
                """);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        jdbc.update("""
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('local-stage', 'task-1', 1, 1)
                """);
    }

    private static void seedProvisionedCode(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-1', 'task-1', 1, 'assignment-1',
                    'provision-operation', 1, 'acme/widget', 'head-old',
                    'dev/task-1', '/tmp/task-1', 'REQUESTED', 2)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES ('provision-ticket', 'provision-operation',
                    'PROVISION_TASK', 'LOCAL_GIT', 'TASK', 'task-1',
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    'task-1', 1, 1, 'head-old', 'REQUESTED', 2)
                """);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'DISPATCHED' WHERE id = 'provision-1'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'created',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = 'provision-operation',
                    pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'head-old'
                WHERE id = 'provision-ticket'
                """);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'head-old',
                    result_head_sha = 'head-old',
                    result_code_fingerprint = 'fingerprint-old',
                    completed_at_ms = 3
                WHERE id = 'provision-1'
                """);
        jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES ('task-1', 'provision-1', 'acme/widget', 'acme/widget',
                    'dev/task-1', '/tmp/task-1', 'head-old', 'head-old',
                    'fingerprint-old', 3, 3)
                """);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = 'provision-ticket'");
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

    private static void seedBrainReview(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES ('implemented-turn', 'local-stage', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'SUCCEEDED', 'implemented-operation',
                    1, 1, 'fingerprint-old', 'head-old', 'head-old', 'API', '{}',
                    4, 4, 5)
                """);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns,
                    context_refs, source_code_fingerprint, source_head_sha,
                    source_base_sha)
                VALUES ('report-brain', 'task-1', 'implemented', 5, 'V2',
                    'local-stage', 1, 1, 'implemented-turn', 1,
                    'fingerprint-old', 'head-old', 'head-old', 'intent', '', '',
                    '', '', '', '[]', 'fingerprint-old', 'head-old', 'head-old')
                """);
        jdbc.update("""
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('validation-1', 'local-stage', 'task-1', 1, 1,
                    'report-brain', 'validation-operation', 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 6)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-ticket', 'validation-operation',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                    'local-stage', 'STAGE_VALIDATION_RESULT', 4, 1, 0,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 6)
                """);
        jdbc.update("""
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = 'validation-1'
                """);
        jdbc.update("""
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, fix_rounds,
                    failures_json, workflow_version, task_epoch, stage_id,
                    stage_generation, operation_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha)
                VALUES ('task-1', 6, 7, 1, 0, '[]', 'V2', 1,
                    'local-stage', 1, 'validation-operation', 1,
                    'fingerprint-old', 'head-old', 'head-old')
                """);
        Long passId = jdbc.queryForObject(
                "SELECT id FROM validation_pass WHERE operation_id = "
                        + "'validation-operation'", Long.class);
        jdbc.update("""
                INSERT INTO validation_evidence(
                    id, validation_operation_id, validation_pass_id, task_id,
                    task_epoch, stage_id, stage_generation, code_fingerprint,
                    head_sha, base_sha, passed, failures_digest, evidence,
                    completed_at_ms)
                VALUES ('validation-evidence', 'validation-1', ?, 'task-1',
                    1, 'local-stage', 1, 'fingerprint-old', 'head-old',
                    'head-old', 1, NULL, '{}', 7)
                """, passId);
        jdbc.update("""
                UPDATE validation_operation
                SET status = 'COMPLETED', completed_at_ms = 7
                WHERE id = 'validation-1'
                """);
        jdbc.update("""
                UPDATE stage
                SET checkpoint = 'BRAIN_REVIEW', version = version + 1
                WHERE id = 'local-stage'
                """);
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('brain-turn', 'task-1', 'DEVELOPMENT_BRAIN_REVIEW',
                    'QUEUED', 'brain-operation', 1, 1, 'local-stage', 1,
                    'fingerprint-old', 'head-old', 'head-old', 'API', '{}', 8)
                """);
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('brain-episode', 'brain-1', 'task-1', 1,
                    'local-stage', 1, 'report-brain', 'validation-evidence',
                    'brain-turn', 1, 'fingerprint-old', 'head-old', 'head-old',
                    'REQUESTED', 8)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('brain-ticket', 'brain-operation', 'EXECUTE_TASK_TURN',
                    'AGENT_TURN', 'TASK_TURN', 'brain-turn', 'TASK_TURN_RESULT',
                    2, 1, 0, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage', 1, 1, 'fingerprint-old', 'head-old',
                    'head-old', 'REQUESTED', 8)
                """);
    }

    private static void markResultPending(
            JdbcTemplate jdbc, String ticketId, ResultFence fence)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'FAILED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_error = 'BRAIN_BUDGET_EXHAUSTED',
                    pending_result_task_epoch = ?,
                    pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha(), ticketId);
    }

    private static void markSucceededResultPending(
            JdbcTemplate jdbc, String ticketId, ResultFence fence)
    {
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = '{}',
                    pending_result_evidence = '{}',
                    pending_result_error = NULL,
                    pending_result_task_epoch = ?,
                    pending_result_stage_id = ?,
                    pending_result_stage_generation = ?,
                    pending_result_operation_id = ?,
                    pending_result_attempt = ?,
                    pending_result_expected_code_fingerprint = ?,
                    pending_result_expected_head_sha = ?,
                    pending_result_expected_base_sha = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha(), ticketId);
    }

    private static void seedImplementationRequest(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('implementation-turn', 'local-stage', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED',
                    'implementation-operation', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'API', '{}', 4)
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES ('implementation-request', 'request-implementation',
                    'implementation-turn', 'task-1', 'local-stage', 1, 1,
                    'IMPLEMENTATION', 'IMMEDIATE',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'runtime', 4)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('implementation-ticket', 'implementation-operation',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'implementation-turn', 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'local-stage', 1, 1,
                    'fingerprint-old', 'head-old', 'head-old', 'REQUESTED', 4)
                """);
    }
}
