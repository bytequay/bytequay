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
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.LocalTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Predecessor;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore.Request;
import com.bytequay.app.developmentflow.task.TaskControlHandoff;
import com.bytequay.app.developmentflow.task.TaskControlMaintainer;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import com.bytequay.app.developmentflow.task.persistence.SqliteTaskControlRuntimeStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestV2StageSteeringStore
{
    @TempDir
    private Path tempDir;

    @Test
    void appendPersistsBeforeSuccessorAndSurvivesRestart()
    {
        Fixture fixture = fixture("append.db", false);
        Request request = request(
                fixture, "steer-1", "command-1",
                V2StageSteeringControl.Mode.APPEND);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));

        assertThat(fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM stage_turn WHERE stage_id = 'local-stage-1'",
                Integer.class)).isEqualTo(1);
        assertThat(fixture.steering().predecessorQuiesced(request)).isFalse();

        SQLiteDataSource restarted = dataSource(fixture.url());
        SqliteStageSteeringStore recovered = new SqliteStageSteeringStore(
                new JdbcTemplate(restarted));
        assertThat(recovered.find("steer-1")).contains(request);
        assertThat(recovered.findPending(10)).extracting(Request::id)
                .containsExactly("steer-1");
    }

    @Test
    void cancelAndReplaceWaitsForWriterQuiescenceThenAdmitsExactlyOneTurn()
    {
        Fixture fixture = fixture("replace.db", false);
        Request request = request(
                fixture, "steer-2", "command-2",
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));
        assertThat(fixture.steering().cancellationRequestedFor("operation-1"))
                .isTrue();

        insertWriterLeases(fixture.jdbc());
        terminalizePredecessor(fixture.jdbc());
        assertThat(fixture.steering().predecessorQuiesced(request)).isFalse();
        fixture.jdbc().update("DELETE FROM worktree_leases WHERE operation_id = 'operation-1'");
        fixture.jdbc().update("""
                UPDATE capacity_lease SET released_at_ms = 9, release_reason = 'done'
                WHERE operation_id = 'operation-1'
                """);
        assertThat(fixture.steering().predecessorQuiesced(request)).isTrue();

        fixture.commands().executeVoid("task-1", () -> {
            fixture.local().clearImplementationTurnInCommand(
                    new StageManager.ResultCommand(
                            "clear-old", "test", "task-1", fence("1")),
                    "request-1");
            LocalTurn turn = replacementTurn();
            fixture.steering().insertLocalTurn(turn);
            fixture.local().requestImplementationInCommand(
                    new StageManager.Command(
                            "arm-replacement", "test", "task-1", 1,
                            "local-stage-1", 1, 2),
                    turn.fence(), turn.localRequestId());
            fixture.steering().markAdmitted(
                    request.id(), "STAGE_TURN", turn.turnId(),
                    turn.operationId(), Instant.ofEpochMilli(10));
        });

        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_steering_request_v257
                WHERE id = 'steer-2' AND status = 'ADMITTED'
                  AND successor_owner_id = 'replacement-turn'
                """, Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_turn
                WHERE stage_id = 'local-stage-1' AND purpose = 'USER_STEERING'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void staleFenceAndDuplicateCommandCannotAffectSiblingTask()
    {
        Fixture fixture = fixture("sibling.db", true);
        Request request = request(
                fixture, "steer-3", "same-command",
                V2StageSteeringControl.Mode.APPEND);
        fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(request, List.of()));

        Request duplicateCommand = new Request(
                "steer-other", "same-command", request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageKind(), request.stageGeneration(),
                request.acceptedStageVersion(), request.acceptedCheckpoint(),
                request.mode(), "different", "b".repeat(64),
                request.predecessor(), "PENDING", null, null, null,
                "test", Instant.ofEpochMilli(7));
        assertThatThrownBy(() -> fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(duplicateCommand, List.of())))
                .isInstanceOf(DataAccessException.class);

        Request stale = new Request(
                "stale", "stale-command", request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageKind(), 2,
                request.acceptedStageVersion(), request.acceptedCheckpoint(),
                request.mode(), request.body(), "c".repeat(64), null,
                "PENDING", null, null, null, "test", Instant.ofEpochMilli(8));
        assertThatThrownBy(() -> fixture.commands().executeVoid("task-1", () ->
                fixture.steering().insert(stale, List.of())))
                .isInstanceOf(DataAccessException.class);

        assertThat(fixture.jdbc().queryForObject("""
                SELECT status FROM dispatch_ticket WHERE id = 'ticket-2'
                """, String.class)).isEqualTo("REQUESTED");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_steering_request_v257
                WHERE task_id = 'task-2'
                """, Integer.class)).isZero();
    }

    @Test
    void localResumeRearmsOnlyTheUniqueHighestCanceledSemanticAttempt()
    {
        Fixture fixture = fixture("local-resume.db", false);
        terminalizePredecessor(fixture.jdbc());
        fixture.commands().executeVoid("task-1", () ->
                fixture.local().clearImplementationTurnInCommand(
                        new StageManager.ResultCommand(
                                "clear-attempt-1", "test", "task-1", fence("1")),
                        "request-1"));
        seedSecondImplementation(fixture.jdbc());
        long stageVersion = fixture.jdbc().queryForObject(
                "SELECT version FROM stage WHERE id = 'local-stage-1'",
                Long.class);
        fixture.commands().executeVoid("task-1", () ->
                fixture.local().requestImplementationInCommand(
                        new StageManager.Command(
                                "arm-attempt-2", "test", "task-1", 1,
                                "local-stage-1", 1, stageVersion),
                        historyFence(), "request-history"));
        terminalizeHistory(fixture.jdbc());
        fixture.commands().executeVoid("task-1", () ->
                fixture.local().clearImplementationTurnInCommand(
                        new StageManager.ResultCommand(
                                "clear-attempt-2", "test", "task-1",
                                historyFence()),
                        "request-history"));

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
                return StageKind.LOCAL_DEVELOPMENT;
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
        Instant now = Instant.ofEpochMilli(100);

        tasks.requestPause(new TaskManager.Command(
                "pause-local", "user", "task-1", 1,
                taskVersion(fixture.jdbc())));
        withoutOwner.maintain(now);
        tasks.requestResume(new TaskManager.Command(
                "resume-local", "user", "task-1", 1,
                taskVersion(fixture.jdbc())));
        withoutOwner.maintain(now.plusMillis(1));
        withOwner.maintain(now.plusMillis(2));
        SqliteStageResumeRearmStore.Intent intent = rearms
                .pending(StageKind.LOCAL_DEVELOPMENT, 10).getFirst();
        commands.executeVoid("task-1", () ->
                rearms.materializeLocalTurn(intent, now.plusMillis(3)));

        assertThat(fixture.jdbc().queryForMap("""
                SELECT intent.status, successor.status AS successor_status,
                       successor.semantic_attempt, successor.owner_kind
                FROM stage_resume_rearm_intent_v257 intent
                JOIN stage_resume_rearm_successor_v257 successor
                  ON successor.handoff_id = intent.handoff_id
                WHERE intent.task_id = 'task-1'
                """))
                .containsEntry("status", "MATERIALIZED")
                .containsEntry("successor_status", "ARMED")
                .containsEntry("semantic_attempt", 3)
                .containsEntry("owner_kind", "STAGE_TURN");
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM stage_turn
                WHERE stage_id = 'local-stage-1'
                  AND purpose = 'IMPLEMENT_LOCAL_PLAN'
                  AND attempt = 3 AND status = 'QUEUED'
                """, Integer.class)).isOne();
    }

    private Fixture fixture(String name, boolean sibling)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        SQLiteDataSource dataSource = dataSource(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedOwner(jdbc, "1", 1);
        if (sibling) {
            seedOwner(jdbc, "2", 2);
        }
        Flyway.configure().dataSource(dataSource).target("265").load().migrate();
        seedImplementation(jdbc, "1");
        if (sibling) {
            seedImplementation(jdbc, "2");
        }
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        V2StageStore stages = new V2StageStore(jdbc);
        LocalDevelopmentStageManager local = new LocalDevelopmentStageManager(
                commands, stages, stages);
        arm(commands, local, "1");
        if (sibling) {
            arm(commands, local, "2");
        }
        return new Fixture(
                url, dataSource, jdbc, commands, local,
                new SqliteStageSteeringStore(jdbc));
    }

    private static Request request(
            Fixture fixture, String id, String commandId,
            V2StageSteeringControl.Mode mode)
    {
        Predecessor predecessor = fixture.steering()
                .findPredecessor(fence("1")).orElseThrow();
        return new Request(
                id, commandId, "task-1", 1, "local-stage-1",
                StageKind.LOCAL_DEVELOPMENT, 1, 1,
                StageCheckpoint.IMPLEMENTING, mode, "Please adjust this",
                "a".repeat(64), predecessor, "PENDING", null, null, null,
                "test", Instant.ofEpochMilli(6));
    }

    private static void arm(
            TaskCommandExecutor commands, LocalDevelopmentStageManager local,
            String suffix)
    {
        commands.executeVoid("task-" + suffix, () ->
                local.requestImplementationInCommand(
                        new StageManager.Command(
                                "arm-" + suffix, "test", "task-" + suffix,
                                1, "local-stage-" + suffix, 1, 0),
                        fence(suffix), "request-" + suffix));
    }

    private static ResultFence fence(String suffix)
    {
        return new ResultFence(
                1, "local-stage-" + suffix, 1, "operation-" + suffix, 1,
                "fingerprint-" + suffix, "base-" + suffix, "base-" + suffix);
    }

    private static ResultFence historyFence()
    {
        return new ResultFence(
                1, "local-stage-1", 1, "operation-history", 2,
                "fingerprint-1", "base-1", "base-1");
    }

    private static LocalTurn replacementTurn()
    {
        return new LocalTurn(
                "replacement-request", "persist-replacement", "replacement-turn",
                "replacement-operation", "replacement-ticket", "workspace-1",
                "trunk-1", "task-1", 1, "local-stage-1", 1,
                "fingerprint-1", "base-1", "base-1", "API", 2, "{}",
                "d".repeat(64), "test", Instant.ofEpochMilli(10));
    }

    private static void terminalizePredecessor(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE stage_turn SET status = 'CANCELED', started_at_ms = 8,
                    finished_at_ms = 8, error_message = 'replaced'
                WHERE id = 'turn-1'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'CANCELED', cancel_requested_at_ms = 8,
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'replaced', completed_at_ms = 8
                WHERE id = 'ticket-1'
                """);
    }

    private static void terminalizeHistory(JdbcTemplate jdbc)
    {
        jdbc.update("""
                UPDATE stage_turn SET status = 'CANCELED', started_at_ms = 18,
                    finished_at_ms = 18, error_message = 'paused'
                WHERE id = 'turn-history'
                """);
        jdbc.update("""
                UPDATE dispatch_ticket SET version = version + 1,
                    status = 'CANCELED', cancel_requested_at_ms = 18,
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'paused', completed_at_ms = 18
                WHERE id = 'ticket-history'
                """);
    }

    private static void seedSecondImplementation(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES ('turn-history', 'local-stage-1', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED', 'operation-history',
                    2, 1, 'fingerprint-1', 'base-1', 'base-1', 'API', '{}', 17)
                """);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by, requested_at_ms)
                VALUES ('request-history', 'persist-history', 'turn-history',
                    'task-1', 'local-stage-1', 1, 1, 'IMPLEMENTATION',
                    'IMMEDIATE', ?, 'test', 17)
                """, "f".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, exclusive_task,
                    writer_required, workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('ticket-history', 'operation-history',
                    'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    'turn-history', 'STAGE_TURN_RESULT', 2, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'local-stage-1', 1,
                    2, 'fingerprint-1', 'base-1', 'base-1', 'REQUESTED', 17)
                """);
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

    private static long taskVersion(JdbcTemplate jdbc)
    {
        return jdbc.queryForObject(
                "SELECT aggregate_version FROM tasks WHERE id = 'task-1'",
                Long.class);
    }

    private static void insertWriterLeases(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required, workspace_id,
                    trunk_id, task_id, task_epoch, holder, fencing_token,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('capacity-1', 'ticket-1', 'operation-1', 'V2', 2,
                    0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1,
                    'holder', 1, 7, 7, 100)
                """);
        jdbc.update("""
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms,
                    expires_at_ms, workflow_version, operation_id, task_epoch,
                    fencing_token, lease_owner)
                VALUES ('/tmp/task-1', 'task-1', 'CLI_AGENT', 7, 100,
                    'V2', 'operation-1', 1, 1, 'holder')
                """);
    }

    private static void seedOwner(JdbcTemplate jdbc, String suffix, int sequence)
    {
        jdbc.update("""
                INSERT OR IGNORE INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT OR IGNORE INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'codex', 'Trunk', 'IDLE',
                    'test', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT OR IGNORE INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 'test', 1)
                """);
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES (?, 'trunk-1', 'NEW_FROM_TRUNK', ?, 'seed', 'build',
                    'test', 2)
                """, "assignment-" + suffix, "base-" + suffix);
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id)
                VALUES (?, 'trunk-1', ?, 'IDLE', 'PLANNING', 2,
                    'V2', 1, 0, 'PROVISIONING', ?, 'policy-1')
                """, "task-" + suffix, sequence, "assignment-" + suffix);
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES (?, ?, 'policy-1', 'DIRECT_USER', 'acme/widget',
                    'acme/widget', ?, 'engine',
                    '{"kind":"API","agentOrProvider":"openai",'
                    || '"model":"model","account":null,'
                    || '"reasoningEffort":null}', 2)
                """, "task-" + suffix, "assignment-" + suffix, "base-" + suffix);
        jdbc.update("""
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES (?, ?, 'openai', 'model', 'engine', 2)
                """, "brain-" + suffix, "task-" + suffix);
        seedCode(jdbc, suffix);
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'LOCAL_DEVELOPMENT', 1, 0, 'IMPLEMENTING', 3)
                """, "local-stage-" + suffix, "task-" + suffix);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, "task-" + suffix, "local-stage-" + suffix);
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, "task-" + suffix);
        jdbc.update("""
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES (?, ?, 1, 1)
                """, "local-stage-" + suffix, "task-" + suffix);
    }

    private static void seedCode(JdbcTemplate jdbc, String suffix)
    {
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES (?, ?, 1, ?, ?, 1, 'acme/widget', ?, ?, ?,
                    'REQUESTED', 2)
                """, "provision-" + suffix, "task-" + suffix,
                "assignment-" + suffix, "provision-operation-" + suffix,
                "base-" + suffix, "dev/task-" + suffix, "/tmp/task-" + suffix);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, exclusive_task,
                    writer_required, workspace_id, trunk_id, task_id, task_epoch,
                    attempt, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'PROVISION_TASK', 'LOCAL_GIT', 'TASK', ?,
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, 1, ?, 'REQUESTED', 2)
                """, "provision-ticket-" + suffix,
                "provision-operation-" + suffix, "task-" + suffix,
                "task-" + suffix, "base-" + suffix);
        jdbc.update("UPDATE provision_task_operation SET status = 'DISPATCHED' WHERE id = ?",
                "provision-" + suffix);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'created',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = ?, pending_result_attempt = 1,
                    pending_result_expected_base_sha = ?
                WHERE id = ?
                """, "provision-operation-" + suffix, "base-" + suffix,
                "provision-ticket-" + suffix);
        jdbc.update("""
                UPDATE provision_task_operation SET status = 'ACCEPTED',
                    result_base_sha = ?, result_head_sha = ?,
                    result_code_fingerprint = ?, completed_at_ms = 3
                WHERE id = ?
                """, "base-" + suffix, "base-" + suffix,
                "fingerprint-" + suffix, "provision-" + suffix);
        jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, 'acme/widget', 'acme/widget', ?, ?, ?, ?, ?, 3, 3)
                """, "task-" + suffix, "provision-" + suffix,
                "dev/task-" + suffix, "/tmp/task-" + suffix,
                "base-" + suffix, "base-" + suffix, "fingerprint-" + suffix);
        jdbc.update("DELETE FROM dispatch_ticket WHERE id = ?",
                "provision-ticket-" + suffix);
    }

    private static void seedImplementation(JdbcTemplate jdbc, String suffix)
    {
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status, operation_id,
                    attempt, task_epoch, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES (?, ?, 1, 'IMPLEMENT_LOCAL_PLAN', 'QUEUED', ?, 1, 1,
                    ?, ?, ?, 'API', '{}', 4)
                """, "turn-" + suffix, "local-stage-" + suffix,
                "operation-" + suffix, "fingerprint-" + suffix,
                "base-" + suffix, "base-" + suffix);
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, 1, 1, 'IMPLEMENTATION', 'IMMEDIATE',
                    ?, 'test', 4)
                """, "request-" + suffix, "persist-" + suffix,
                "turn-" + suffix, "task-" + suffix, "local-stage-" + suffix,
                "e".repeat(64));
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family, owner_kind,
                    owner_id, callback_route, lane_mask, exclusive_task,
                    writer_required, workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN', 'STAGE_TURN',
                    ?, 'STAGE_TURN_RESULT', 2, 1, 1, 'workspace-1', 'trunk-1',
                    ?, 1, ?, 1, 1, ?, ?, ?, 'REQUESTED', 4)
                """, "ticket-" + suffix, "operation-" + suffix,
                "turn-" + suffix, "task-" + suffix, "local-stage-" + suffix,
                "fingerprint-" + suffix, "base-" + suffix, "base-" + suffix);
    }

    private static SQLiteDataSource dataSource(String url)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    private record Fixture(
            String url, SQLiteDataSource dataSource, JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            SqliteStageSteeringStore steering) {}
}
