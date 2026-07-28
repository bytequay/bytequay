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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.ProvisionToPlanHandoff;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowSqliteHandoffRollback
{
    @TempDir
    private Path tempDir;

    @Test
    void failedPlanCreationRollsBackProvisioningAcrossBothOwners()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("handoff.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").target("225").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedAcceptedProvisioning(jdbc);

        TaskCommandExecutor executor = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TaskManager tasks = new TaskManager(executor, new SqliteTaskStore(jdbc));
        PlanStageManager plan = new PlanStageManager(
                executor, new FailingSqliteStageStore(jdbc));
        ProvisionToPlanHandoff handoff = new ProvisionToPlanHandoff(
                executor, tasks, plan);
        ResultFence result = new ResultFence(
                1, null, 0, "operation-1", 1, "fp-1", "base-1", "base-1");

        assertThatThrownBy(() -> handoff.accept(new TaskManager.ProvisioningCommand(
                "activate-task-1", "dispatcher", "task-1", 0,
                result, "stage-plan-1", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated Plan subtype failure");

        assertThat(jdbc.queryForObject(
                "SELECT lifecycle_state FROM tasks WHERE id = 'task-1'", String.class))
                .isEqualTo("PROVISIONING");
        assertThat(jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = 'task-1'", Long.class))
                .isZero();
        assertThat(count(jdbc, "task_current_stage")).isZero();
        assertThat(count(jdbc, "task_transition")).isZero();
        assertThat(count(jdbc, "stage")).isZero();
        assertThat(count(jdbc, "stage_transition")).isZero();
        assertThat(count(jdbc, "plan_stage")).isZero();
    }

    private static long count(JdbcTemplate jdbc, String table)
    {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static void seedAcceptedProvisioning(JdbcTemplate jdbc)
    {
        jdbc.execute("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.execute("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.execute("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build', 'user', 2)
                """);
        jdbc.execute("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 1, 'user', 2)
                """);
        jdbc.execute("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        jdbc.execute("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha, engine_snapshot,
                    work_model_snapshot, created_at_ms)
                VALUES ('task-1', 'assignment-1', 'policy-1', 'DIRECT_USER',
                    'acme/widget', 'acme/widget', 'base-1', 'engine-v1', 'model-v1', 3)
                """);
        jdbc.execute("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-1', 'task-1', 1, 'assignment-1', 'operation-1',
                    1, 'acme/widget', 'base-1', 'dev/task-1', '/tmp/task-1',
                    'REQUESTED', 4)
                """);
        jdbc.execute("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES ('ticket-1', 'operation-1', 'PROVISION_TASK', 'LOCAL_GIT',
                    'TASK', 'task-1', 'TASK_PROVISION_RESULT', 16,
                    1, 1, 'workspace-1', 'trunk-1', 'task-1', 1, 1, 'base-1',
                    'REQUESTED', 4)
                """);
        jdbc.execute("""
                UPDATE provision_task_operation SET status = 'DISPATCHED'
                WHERE id = 'provision-1'
                """);
        jdbc.execute("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'exact local Git result'
                WHERE id = 'ticket-1'
                """);
        jdbc.execute("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-1',
                    result_head_sha = 'base-1', result_code_fingerprint = 'fp-1',
                    completed_at_ms = 5
                WHERE id = 'provision-1'
                """);
    }

    private static final class SqliteTaskStore
            implements TaskManager.Store
    {
        private final JdbcTemplate jdbc;

        private SqliteTaskStore(JdbcTemplate jdbc)
        {
            this.jdbc = jdbc;
        }

        @Override
        public Optional<TaskManager.State> findById(String taskId)
        {
            List<TaskManager.State> states = jdbc.query("""
                    SELECT t.id, t.thread_id, t.lifecycle_state, t.epoch,
                        t.aggregate_version, c.stage_id
                    FROM tasks t
                    LEFT JOIN task_current_stage c ON c.task_id = t.id
                    WHERE t.id = ?
                    """, (row, ignored) -> new TaskManager.State(
                            row.getString("id"), row.getString("thread_id"),
                            TaskLifecycle.valueOf(row.getString("lifecycle_state")),
                            row.getLong("epoch"), row.getLong("aggregate_version"),
                            row.getString("stage_id"), null, null, null, null), taskId);
            return states.stream().findFirst();
        }

        @Override
        public Optional<TaskManager.CommandReceipt> findCommandResult(
                String taskId, String commandId)
        {
            return Optional.empty();
        }

        @Override
        public Optional<TaskManager.ProvisioningResult> findAcceptedProvisioningResult(
                String taskId, String operationId)
        {
            List<TaskManager.ProvisioningResult> results = jdbc.query("""
                    SELECT task_id, task_epoch, operation_id, semantic_attempt,
                        result_base_sha, result_head_sha, result_code_fingerprint
                    FROM provision_task_operation
                    WHERE task_id = ? AND operation_id = ? AND status = 'ACCEPTED'
                    """, (row, ignored) -> new TaskManager.ProvisioningResult(
                            row.getString("task_id"), row.getLong("task_epoch"),
                            row.getString("operation_id"), row.getInt("semantic_attempt"),
                            row.getString("result_base_sha"), row.getString("result_head_sha"),
                            row.getString("result_code_fingerprint")), taskId, operationId);
            return results.stream().findFirst();
        }

        @Override
        public Optional<TaskManager.ReplanEvidence> findReplanEvidence(
                String taskId, String replanRequestId)
        {
            return Optional.empty();
        }

        @Override
        public Optional<TaskManager.QuiescenceEvidence> findSatisfiedQuiescence(
                String taskId, String barrierId)
        {
            return Optional.empty();
        }

        @Override
        public Optional<TaskManager.PauseEvidence> findPauseEvidence(
                String taskId, String barrierId)
        {
            return Optional.empty();
        }

        @Override
        public Optional<TaskManager.ResumeEvidence> findResumeEvidence(
                String taskId, String reconciliationId)
        {
            return Optional.empty();
        }

        @Override
        public Optional<TaskManager.ArchiveEvidence> findArchiveEvidence(
                String taskId, String archiveEvidenceId)
        {
            return Optional.empty();
        }

        @Override
        public TaskManager.State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedEpoch,
                Long expectedVersion,
                ResultFence resultFence,
                TaskManager.BrainVerdict brainVerdict,
                String proofId,
                String nextStageId,
                StageKind nextStageKind,
                Long nextStageGeneration,
                TaskManager.State expected,
                TaskManager.State updated)
        {
            jdbc.update("""
                    INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                    VALUES (?, ?, ?)
                    """, updated.id(), updated.currentStageId(), nextStageGeneration);
            int changed = jdbc.update("""
                    UPDATE tasks
                    SET lifecycle_state = ?, epoch = ?, aggregate_version = ?
                    WHERE id = ? AND epoch = ? AND aggregate_version = ?
                    """, updated.lifecycle().name(), updated.epoch(), updated.version(),
                    expected.id(), expected.epoch(), expected.version());
            if (changed != 1) {
                throw new CommandRejectedException(
                        CommandRejectedException.Reason.CONCURRENT_UPDATE,
                        "Task changed before commit");
            }
            jdbc.update("""
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 10)
                    """, "transition-" + commandId, updated.id(), commandId,
                    updated.epoch(), expected.lifecycle().name(), updated.lifecycle().name(),
                    updated.version(), cause, actor);
            return findById(updated.id()).orElseThrow();
        }

        @Override
        public TaskManager.State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedEpoch,
                Long expectedVersion,
                ResultFence resultFence,
                TaskManager.BrainVerdict brainVerdict,
                String proofId,
                String nextStageId,
                StageKind nextStageKind,
                Long nextStageGeneration,
                TaskManager.State current)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markReplanApplied(
                TaskManager.ReplanEvidence evidence,
                String newPlanStageId,
                long newPlanGeneration)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FailingSqliteStageStore
            implements StageManager.Store
    {
        private final JdbcTemplate jdbc;

        private FailingSqliteStageStore(JdbcTemplate jdbc)
        {
            this.jdbc = jdbc;
        }

        @Override
        public Optional<StageManager.OwnerState> findOwner(String taskId, String stageId)
        {
            return Optional.empty();
        }

        @Override
        public Optional<StageManager.CommandReceipt> findCommandResult(
                String taskId, String stageId, String commandId)
        {
            return Optional.empty();
        }

        @Override
        public StageManager.State commit(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State expected,
                StageManager.State updated)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public StageManager.State create(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State state)
        {
            jdbc.update("""
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, 10)
                    """, state.id(), state.taskId(), state.kind().name(), state.generation(),
                    state.version(), state.checkpoint().name());
            jdbc.update("""
                    INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                    SELECT ?, ?, ?, epoch FROM tasks WHERE id = ?
                    """, state.id(), state.taskId(), state.generation(), state.taskId());
            jdbc.update("""
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, 10)
                    """, "transition-" + state.id(), state.id(), commandId,
                    state.generation(), state.checkpoint().name(), state.version(), cause, actor);
            throw new IllegalStateException("simulated Plan subtype failure");
        }

        @Override
        public StageManager.State recordSuperseded(
                String commandId,
                String cause,
                String actor,
                Long expectedTaskEpoch,
                Long expectedStageGeneration,
                Long expectedStageVersion,
                StageCheckpoint sourceCheckpoint,
                ResultFence subjectFence,
                String proofId,
                StageManager.State current)
        {
            throw new UnsupportedOperationException();
        }
    }
}
