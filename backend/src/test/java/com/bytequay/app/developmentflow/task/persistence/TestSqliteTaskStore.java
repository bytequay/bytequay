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
package com.bytequay.app.developmentflow.task.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.STALE_VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteTaskStore
{
    @TempDir
    private Path tempDir;

    @Test
    void duplicateStaleRollbackRestartAndDeferredEvidenceFailClosed()
    {
        SQLiteDataSource dataSource = database(tempDir.resolve("task.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTask(jdbc, "task-1", "plan-1", 1);
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2TaskStore store = new V2TaskStore(jdbc);
        TaskManager manager = new TaskManager(commands, store);
        TaskManager.Command pause = new TaskManager.Command(
                "pause-1", "user", "task-1", 1, 1);

        assertThat(manager.requestPause(pause).state())
                .returns(TaskLifecycle.PAUSING, TaskManager.State::lifecycle)
                .returns(2L, TaskManager.State::version);
        assertThat(manager.requestPause(pause).disposition().name()).isEqualTo("DUPLICATE");

        TaskManager restarted = new TaskManager(
                commands, new V2TaskStore(new JdbcTemplate(dataSource)));
        assertThat(restarted.requestPause(pause).disposition().name()).isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "task_transition", "task-1")).isEqualTo(1);
        assertThat(count(jdbc, "task_command_receipt", "task-1")).isEqualTo(1);
        assertThatThrownBy(() -> restarted.requestArchive(new TaskManager.Command(
                "stale-1", "user", "task-1", 1, 1)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));

        seedActiveTask(jdbc, "task-cancel", "plan-cancel", 2);
        TaskManager cancelManager = new TaskManager(commands, store);
        TaskManager.Command cancel = new TaskManager.Command(
                "cancel-1", "user", "task-cancel", 1, 1);
        TaskManager.State canceled = cancelManager.requestCancel(cancel).state();
        assertThat(canceled)
                .returns(TaskLifecycle.CANCELING, TaskManager.State::lifecycle)
                .returns(TaskManager.TerminalOutcome.CANCELED,
                        TaskManager.State::terminalIntent);
        assertThat(new V2TaskStore(new JdbcTemplate(dataSource))
                .findById("task-cancel")).contains(canceled);
        assertThat(jdbc.queryForObject("""
                SELECT kind FROM task_terminal_intent
                WHERE task_id = 'task-cancel' AND accepted = 1
                """, String.class)).isEqualTo("CANCELED");

        seedActiveTask(jdbc, "task-rollback", "plan-rollback", 3);
        TaskManager.State expected = store.findById("task-rollback").orElseThrow();
        TaskManager.State updated = new TaskManager.State(
                expected.id(), expected.trunkId(), TaskLifecycle.PAUSING,
                expected.epoch(), expected.version() + 1, expected.currentStageId(),
                null, null, null, null);
        assertThatThrownBy(() -> commands.execute(
                "task-rollback",
                () -> store.commit(
                        "bad-command", "NOT_A_CAUSE", "user", 1L, 1L,
                        null, null, null, null, null, null, expected, updated)))
                .isInstanceOf(DataAccessException.class);
        assertThat(store.findById("task-rollback")).contains(expected);
        assertThat(count(jdbc, "task_transition", "task-rollback")).isZero();

        seedSatisfiedPauseBarrier(jdbc, "task-rollback", "barrier-1");
        assertThat(store.findSatisfiedQuiescence("task-rollback", "barrier-1"))
                .isPresent();
        assertThatThrownBy(() -> store.recordSuperseded(
                "outside", "ACCEPT_BRAIN_VERDICT", "brain", null, null,
                null, null, null, null, null, null, expected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command transaction");
    }

    @Test
    void typedControlEvidenceDrivesRealHandoffAndUsesLeaseExpiryAtEvidenceTime()
    {
        Path file = tempDir.resolve("typed-control.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTaskWithCode(jdbc, "task-control", "plan-control", 1);
        seedActiveTaskWithCode(jdbc, "task-live-lease", "plan-live-lease", 2);
        long now = System.currentTimeMillis();
        seedRetainedWorktreeLease(jdbc, "task-control", "plan-control", 11, 20);
        seedRetainedWorktreeLease(
                jdbc, "task-live-lease", "plan-live-lease", 12, now + 60_000);
        migrate(file, "230");

        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2TaskStore store = new V2TaskStore(jdbc);
        TaskManager manager = new TaskManager(commands, store);
        TaskControlHandoff handoff = new TaskControlHandoff(commands, manager);

        assertThat(manager.requestPause(new TaskManager.Command(
                "pause-live", "user", "task-live-lease", 1, 1)).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSING);
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES ('barrier-live', 'task-live-lease', 1, 'PAUSE', 'REQUESTED', ?)
                """, now);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = ?, evidence = 'legacy'
                WHERE id = 'barrier-live'
                """, now + 1)).isInstanceOf(DataAccessException.class);

        TaskManager.Command requestPause = new TaskManager.Command(
                "pause-control", "user", "task-control", 1, 1);
        assertThat(manager.requestPause(requestPause).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSING);
        long pauseAt = now + 2;
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES ('barrier-control', 'task-control', 1,
                    'PAUSE', 'REQUESTED', ?)
                """, pauseAt - 1);
        jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = ?, evidence = 'legacy'
                WHERE id = 'barrier-control'
                """, pauseAt);
        String pauseDigest = pauseDigest(
                "task-control", "barrier-control", "plan-control", pauseAt, pauseAt);
        assertThatThrownBy(() -> insertPauseEvidence(
                jdbc, "task-control", "barrier-control", "plan-control",
                pauseAt, pauseAt, "tampered"))
                .isInstanceOf(DataAccessException.class);
        insertPauseEvidence(
                jdbc, "task-control", "barrier-control", "plan-control",
                pauseAt, pauseAt, pauseDigest);

        TaskManager.PauseCompletionCommand pause = new TaskManager.PauseCompletionCommand(
                new TaskManager.Command("complete-pause", "system", "task-control", 1, 2),
                "barrier-control", "plan-control", 1,
                StageCheckpoint.DRAFTING, pauseDigest);
        assertThat(handoff.completePause(pause).state().lifecycle())
                .isEqualTo(TaskLifecycle.PAUSED);
        assertThat(handoff.completePause(pause).disposition().name()).isEqualTo("DUPLICATE");

        assertThat(manager.requestResume(new TaskManager.Command(
                "request-resume", "user", "task-control", 1, 3)).state().lifecycle())
                .isEqualTo(TaskLifecycle.RESUMING);
        long resumeAt = pauseAt + 1;
        String resumeDigest = resumeDigest(
                "task-control", "resume-control", "barrier-control",
                "plan-control", resumeAt);
        insertResumeEvidence(jdbc, resumeAt, resumeDigest);
        TaskManager.ResumeCompletionCommand resume = new TaskManager.ResumeCompletionCommand(
                new TaskManager.Command("complete-resume", "system", "task-control", 1, 4),
                "resume-control", "plan-control", 1,
                StageCheckpoint.DRAFTING, resumeDigest);
        assertThat(handoff.completeResume(resume).state().lifecycle())
                .isEqualTo(TaskLifecycle.ACTIVE);

        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('archive-live-turn', 'task-control', 'BRAIN_REVIEW',
                    'REQUESTED', 'archive-live-operation', 1, 1,
                    'API', 'review', ?)
                """, resumeAt + 1);
        assertThat(manager.requestArchive(new TaskManager.Command(
                "request-archive", "user", "task-control", 1, 5)).state().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVING);
        long archiveAt = resumeAt + 2;
        String archiveDigest = archiveDigest(
                "task-control", "archive-control", "plan-control", archiveAt);
        assertThatThrownBy(() -> insertArchiveEvidence(jdbc, archiveAt, archiveDigest))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("""
                UPDATE task_turn
                SET status = 'CANCELED', finished_at_ms = ?
                WHERE id = 'archive-live-turn'
                """, archiveAt);
        insertArchiveEvidence(jdbc, archiveAt, archiveDigest);

        TaskManager.ArchiveCompletionCommand archive = new TaskManager.ArchiveCompletionCommand(
                new TaskManager.Command("complete-archive", "system", "task-control", 1, 6),
                "archive-control", "plan-control", 1, archiveDigest);
        assertThat(handoff.completeArchive(archive).state().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVED);
        assertThat(new V2TaskStore(new JdbcTemplate(dataSource))
                .findById("task-control").orElseThrow().lifecycle())
                .isEqualTo(TaskLifecycle.ARCHIVED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class)).isZero();
    }

    @Test
    void policyCommandsAppendAndSelectRevisionsWithoutInvalidatingFrozenAutomation()
    {
        Path file = tempDir.resolve("task-policy.db");
        SQLiteDataSource dataSource = database(file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedTrunk(jdbc);
        seedActiveTask(jdbc, "task-policy", "plan-policy", 1);
        migrate(file, "249");
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TaskManager manager = new TaskManager(commands, new V2TaskStore(jdbc));
        TaskManager.PolicyCommand first = new TaskManager.PolicyCommand(
                new TaskManager.Command(
                        "revise-policy-1", "user", "task-policy", 1, 1),
                "policy-2", true, true, 2, 4, 5, true, "permission-2");

        TaskManager.PolicyRevision selected = manager.revisePolicy(first).state();

        assertThat(selected)
                .returns("policy-2", TaskManager.PolicyRevision::id)
                .returns(2, TaskManager.PolicyRevision::revision)
                .returns(true, TaskManager.PolicyRevision::autoMerge)
                .returns(2, TaskManager.PolicyRevision::minApprovals);
        assertThat(new V2TaskStore(new JdbcTemplate(dataSource))
                .findById("task-policy").orElseThrow().version()).isEqualTo(2);
        assertThat(manager.revisePolicy(first).disposition().name())
                .isEqualTo("DUPLICATE");
        assertThat(count(jdbc, "task_policy_command_receipt", "task-policy"))
                .isEqualTo(1);
        assertThatThrownBy(() -> manager.requestPause(new TaskManager.Command(
                "revise-policy-1", "user", "task-policy", 1, 2)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason().name())
                                .isEqualTo("COMMAND_ID_CONFLICT"));
        assertThatThrownBy(() -> manager.revisePolicy(new TaskManager.PolicyCommand(
                new TaskManager.Command(
                        "stale-policy", "user", "task-policy", 1, 1),
                "policy-stale", true, false, 1, 4, 5, true, null)))
                .isInstanceOfSatisfying(CommandRejectedException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(STALE_VERSION));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE task_policy_revision SET min_approvals = 0
                WHERE id = 'policy-2'
                """)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE tasks
                SET policy_revision_id = 'policy-1', aggregate_version = 3
                WHERE id = 'task-policy'
                """)).isInstanceOf(DataAccessException.class);

        jdbc.update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES ('automation-1', 'task-policy', 1, 'PUBLISH', 1, 1,
                    0, 2, 2, 1, 1, 0, 'system', 10)
                """);
        TaskManager.PolicyRevision changed = manager.revisePolicy(
                new TaskManager.PolicyCommand(
                        new TaskManager.Command(
                                "revise-policy-2", "user", "task-policy", 1, 2),
                        "policy-3", false, false, 1, 6, 7, false, null))
                .state();

        assertThat(changed.id()).isEqualTo("policy-3");
        assertThat(jdbc.queryForMap("""
                SELECT revision, auto_approve, auto_merge,
                       minimum_write_approvals
                FROM task_automation_policy
                WHERE task_id = 'task-policy'
                ORDER BY revision DESC LIMIT 1
                """))
                .containsEntry("revision", 2)
                .containsEntry("auto_approve", 0)
                .containsEntry("auto_merge", 0)
                .containsEntry("minimum_write_approvals", 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class)).isZero();
    }

    private static SQLiteDataSource database(Path file)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private static void migrate(Path file, String target)
    {
        String url = "jdbc:sqlite:" + file + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static void seedTrunk(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'workspace-1', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'trunk-1', 'IDLE', 'test',
                    0, 0, 0, 1, 1, 'workspace-1', 'build', 2, 'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'test', 1)
                """);
    }

    private static void seedActiveTask(
            JdbcTemplate jdbc, String taskId, String stageId, int sequence)
    {
        String assignment = "assignment-" + taskId;
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', 'base', 'seed', 'prompt',
                    'test', 1)
                """, assignment);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', 1,
                    'V2', 'PROVISIONING', ?, 'policy-1')
                """, taskId, sequence, assignment);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'PLAN', 1, 0, 'DRAFTING', 2)
                """, stageId, taskId);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, stageId);
        jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
    }

    private static void seedSatisfiedPauseBarrier(
            JdbcTemplate jdbc, String taskId, String barrierId)
    {
        jdbc.update("""
                INSERT INTO task_quiescence_barrier(
                    id, task_id, task_epoch, reason, status, requested_at_ms)
                VALUES (?, ?, 1, 'PAUSE', 'REQUESTED', 1)
                """, barrierId, taskId);
        jdbc.update("""
                UPDATE task_quiescence_barrier
                SET status = 'SATISFIED', completed_at_ms = 2, evidence = 'opaque'
                WHERE id = ?
                """, barrierId);
    }

    private static void seedActiveTaskWithCode(
            JdbcTemplate jdbc, String taskId, String stageId, int sequence)
    {
        String assignment = "assignment-" + taskId;
        String provision = "provision-" + taskId;
        String operation = "provision-operation-" + taskId;
        String ticket = "provision-ticket-" + taskId;
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', 'base-code',
                    'seed', 'prompt', 'test', 1)
                """, assignment);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', 1,
                    'V2', 'PROVISIONING', ?, 'policy-1')
                """, taskId, sequence, assignment);
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES (?, ?, 'policy-1', 'DIRECT_USER', 'acme/widget',
                    'acme/widget', 'base-code', 'engine', 'work-model', 2)
                """, taskId, assignment);
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES (?, ?, 1, ?, ?, 1, 'acme/widget', 'base-code', ?, ?,
                    'REQUESTED', 3)
                """, provision, taskId, assignment, operation,
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
                    ?, 1, 1, 'base-code', 'REQUESTED', 3)
                """, ticket, operation, taskId, taskId);
        jdbc.update("UPDATE provision_task_operation SET status = 'DISPATCHED' WHERE id = ?",
                provision);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'worktree-created',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = ?, pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'base-code'
                WHERE id = ?
                """, operation, ticket);
        jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-code',
                    result_head_sha = 'base-code',
                    result_code_fingerprint = 'fp-code', completed_at_ms = 4
                WHERE id = ?
                """, provision);
        jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, 'acme/widget', 'acme/widget', ?, ?,
                    'base-code', 'base-code', 'fp-code', 4, 4)
                """, taskId, provision, "dev/" + taskId, "/tmp/" + taskId);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = ?", ticket);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'PLAN', 1, 0, 'DRAFTING', 5)
                """, stageId, taskId);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, stageId);
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
        jdbc.update("""
                INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                VALUES (?, ?, 1, 1)
                """, stageId, taskId);
    }

    private static void seedRetainedWorktreeLease(
            JdbcTemplate jdbc, String taskId, String stageId, int fence, long expiresAt)
    {
        String ticket = "lease-ticket-" + taskId;
        String operation = "lease-operation-" + taskId;
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'LOCAL_WORKTREE_WRITE', 'LOCAL_GIT', 'STAGE', ?,
                    'STAGE_LOCAL_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, ?, 1, 1, 'fp-code', 'base-code', 'base-code',
                    'REQUESTED', 10)
                """, ticket, operation, stageId, taskId, stageId);
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, holder,
                    fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES (?, ?, ?, 'V2', 16, 0, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, ?, ?, 10, 10, ?)
                """, "lease-capacity-" + taskId, ticket, operation, taskId,
                "holder-" + taskId, fence, expiresAt);
        jdbc.update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES (?, ?, 'CLI_AGENT', 10, ?, 'V2', ?, 1, ?, ?)
                """, "/tmp/" + taskId, taskId, expiresAt, operation, fence,
                "holder-" + taskId);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = ?", ticket);
    }

    private static void insertPauseEvidence(
            JdbcTemplate jdbc, String taskId, String barrierId, String stageId,
            long barrierAt, long recordedAt, String digest)
    {
        jdbc.update("""
                INSERT INTO task_pause_evidence(
                    barrier_id, task_id, task_epoch, stage_id, stage_generation,
                    restore_checkpoint, code_fingerprint, head_sha, base_sha,
                    barrier_completed_at_ms, stop_evidence_digest, status,
                    recorded_at_ms)
                VALUES (?, ?, 1, ?, 1, 'DRAFTING', 'fp-code', 'base-code',
                    'base-code', ?, ?, 'SATISFIED', ?)
                """, barrierId, taskId, stageId, barrierAt, digest, recordedAt);
    }

    private static void insertResumeEvidence(
            JdbcTemplate jdbc, long recordedAt, String digest)
    {
        jdbc.update("""
                INSERT INTO task_resume_reconciliation(
                    id, task_id, task_epoch, pause_barrier_id, stage_id,
                    stage_generation, restore_checkpoint,
                    paused_code_fingerprint, paused_head_sha, paused_base_sha,
                    active_task_turn_count, active_stage_turn_count,
                    active_plan_review_count, active_validation_count,
                    active_brain_episode_count, active_provision_operation_count,
                    active_writer_dispatch_count, active_agent_execution_count,
                    unreconciled_execution_count, live_writer_capacity_count,
                    live_worktree_lease_count, active_publish_operation_count,
                    unreconciled_publish_operation_count,
                    active_publish_effect_count, reconciliation_digest,
                    status, recorded_at_ms)
                VALUES ('resume-control', 'task-control', 1, 'barrier-control',
                    'plan-control', 1, 'DRAFTING', 'fp-code', 'base-code',
                    'base-code', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    ?, 'SATISFIED', ?)
                """, digest, recordedAt);
    }

    private static void insertArchiveEvidence(
            JdbcTemplate jdbc, long recordedAt, String digest)
    {
        jdbc.update("""
                INSERT INTO task_archive_liveness(
                    id, task_id, task_epoch, stage_id, stage_generation,
                    active_task_turn_count, active_stage_turn_count,
                    active_review_turn_count, active_plan_review_count,
                    active_validation_count, active_brain_episode_count,
                    active_provision_operation_count, active_dispatch_count,
                    active_agent_execution_count, unreconciled_execution_count,
                    live_capacity_lease_count, live_worktree_lease_count,
                    active_quiescence_count, active_replan_count,
                    active_feedback_batch_count, active_publish_operation_count,
                    unreconciled_publish_operation_count,
                    active_publish_effect_count,
                    active_publish_authorization_count, open_permission_count,
                    accepted_terminal_intent_count, open_cleanup_stage_count,
                    liveness_digest, status, recorded_at_ms)
                VALUES ('archive-control', 'task-control', 1, 'plan-control', 1,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, ?, 'SATISFIED', ?)
                """, digest, recordedAt);
    }

    private static String pauseDigest(
            String taskId, String barrierId, String stageId,
            long barrierAt, long recordedAt)
    {
        return "pause:v1:" + hex(taskId) + ":1:" + hex(barrierId) + ":"
                + hex(stageId) + ":1:" + hex("DRAFTING") + ":"
                + hex("fp-code") + ":" + hex("base-code") + ":"
                + hex("base-code") + ":" + barrierAt + ":" + recordedAt;
    }

    private static String resumeDigest(
            String taskId, String reconciliationId, String barrierId,
            String stageId, long recordedAt)
    {
        return "resume:v1:" + hex(taskId) + ":1:" + hex(reconciliationId) + ":"
                + hex(barrierId) + ":" + hex(stageId) + ":1:"
                + hex("DRAFTING") + ":" + hex("fp-code") + ":"
                + hex("base-code") + ":" + hex("base-code")
                + ":0".repeat(14) + ":" + recordedAt;
    }

    private static String archiveDigest(
            String taskId, String evidenceId, String stageId, long recordedAt)
    {
        return "archive:v1:" + hex(taskId) + ":1:" + hex(evidenceId) + ":"
                + hex(stageId) + ":1" + ":0".repeat(22) + ":" + recordedAt;
    }

    private static String hex(String value)
    {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int count(JdbcTemplate jdbc, String table, String taskId)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id = ?",
                Integer.class,
                taskId);
    }
}
