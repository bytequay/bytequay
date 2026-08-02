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

import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.V2TaskSeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowPersistenceSpineMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void upgradesHistoricalRowsAndPreservesLegacyInsertContracts()
            throws Exception
    {
        String url = seedAt222("historical.db");
        try (Connection connection = connect(url)) {
            seedLegacyProtocolRows(connection, "before");
        }

        migrateTo223(url);

        try (Connection connection = connect(url)) {
            assertThat(text(connection,
                    "SELECT workflow_version FROM tasks WHERE id = 'task-legacy'"))
                    .isEqualTo("LEGACY");
            assertThat(number(connection,
                    "SELECT epoch FROM tasks WHERE id = 'task-legacy'"))
                    .isEqualTo(1);
            assertThat(text(connection,
                    "SELECT workflow_version FROM validation_pass WHERE claim_key = 'validation-before'"))
                    .isEqualTo("LEGACY");
            assertThat(text(connection,
                    "SELECT operation_id FROM validation_pass WHERE claim_key = 'validation-before'"))
                    .isNull();
            assertThat(text(connection,
                    "SELECT workflow_version FROM task_push_authorization WHERE token = 'push-before'"))
                    .isEqualTo("LEGACY");
            assertThat(text(connection,
                    "SELECT workflow_version FROM response_round WHERE id = 'round-before'"))
                    .isEqualTo("LEGACY");
            assertThat(text(connection,
                    "SELECT workflow_version FROM round_gate_authorization WHERE token = 'gate-before'"))
                    .isEqualTo("LEGACY");
            assertThat(text(connection,
                    "SELECT workflow_version FROM worktree_leases WHERE worktree_path = '/tmp/worktree-before'"))
                    .isEqualTo("LEGACY");

            execute(connection, """
                    UPDATE task_push_authorization
                    SET consumed_at_ms = 3
                    WHERE token = 'push-before'
                    """);
            seedLegacyProtocolRows(connection, "after");
            assertThat(number(connection, "SELECT COUNT(*) FROM validation_pass")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM task_push_authorization")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM response_round")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM round_gate_authorization")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM worktree_leases")).isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void restartIsIdempotent()
            throws Exception
    {
        String url = seedAt222("restart.db");
        migrateTo223(url);
        try (Connection connection = connect(url)) {
            seedV2Graph(connection);
        }

        migrateTo223(url);
        migrateTo223(url);

        try (Connection connection = connect(url)) {
            assertThat(text(connection,
                    "SELECT lifecycle_state FROM tasks WHERE id = 'task-v2'"))
                    .isEqualTo("ACTIVE");
            assertThat(text(connection,
                    "SELECT stage_id FROM task_current_stage WHERE task_id = 'task-v2'"))
                    .isEqualTo("stage-v2");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM sqlite_schema WHERE name = 'stage_turn_owner_fence_insert'"))
                    .isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void typedTurnsRejectAmbiguousOrStaleOwnership()
            throws Exception
    {
        String url = migratedV2("turns.db");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO thread_turn(
                        id, trunk_id, purpose, status, operation_id, attempt,
                        delivery_lane, launch_input, requested_at_ms)
                    VALUES ('thread-turn', 'thread-1', 'CONVERSATION', 'QUEUED',
                        'op-thread', 1, 'CLI', 'hello', 10)
                    """);
            execute(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt, task_epoch,
                        trigger_stage_id, trigger_stage_generation, delivery_lane,
                        launch_input, requested_at_ms)
                    VALUES ('task-turn', 'task-v2', 'BRAIN_REVIEW', 'QUEUED',
                        'op-task', 1, 1, 'stage-v2', 1, 'API', 'review', 10)
                    """);
            execute(connection, """
                    INSERT INTO stage_turn(
                        id, stage_id, stage_generation, purpose, status, operation_id,
                        attempt, task_epoch, expected_code_fingerprint, delivery_lane,
                        launch_input, requested_at_ms)
                    VALUES ('stage-turn', 'stage-v2', 1, 'IMPLEMENT', 'QUEUED',
                        'op-stage', 1, 1, 'fp-1', 'CLI', 'implement', 10)
                    """);
            execute(connection, """
                    INSERT INTO review_assignment_turn(
                        id, assignment_id, purpose, status, operation_id, attempt,
                        start_commit, delivery_lane, launch_input, requested_at_ms,
                        cost_cap_usd_milli)
                    VALUES ('review-turn', 'review-assignment-1', 'ADVISORY', 'QUEUED',
                        'op-review', 1, 'head-1', 'API', 'inspect', 10, 100)
                    """);

            assertFails(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt, task_epoch,
                        trigger_stage_id, trigger_stage_generation, delivery_lane,
                        launch_input, requested_at_ms)
                    VALUES ('bad-task-turn', 'task-v2', 'BRAIN_REVIEW', 'QUEUED',
                        'op-bad-task', 1, 1, 'stage-other', 1, 'API', 'review', 10)
                    """);
            assertFails(connection, """
                    INSERT INTO stage_turn(
                        id, stage_id, stage_generation, purpose, status, operation_id,
                        attempt, task_epoch, delivery_lane, launch_input, requested_at_ms)
                    VALUES ('bad-stage-turn', 'stage-v2', 2, 'IMPLEMENT', 'QUEUED',
                        'op-bad-stage', 1, 1, 'CLI', 'implement', 10)
                    """);
            assertFails(connection, """
                    INSERT INTO review_assignment_turn(
                        id, assignment_id, purpose, status, operation_id, attempt,
                        start_commit, delivery_lane, launch_input, requested_at_ms,
                        cost_cap_usd_milli)
                    VALUES ('bad-review-turn', 'review-assignment-1', 'ADVISORY', 'QUEUED',
                        'op-bad-review', 1, 'stale-head', 'API', 'inspect', 10, 100)
                    """);
            assertFails(connection, """
                    INSERT INTO stage_message(id, turn_id, seq, role, body, created_at_ms)
                    VALUES ('message-bad', 'task-turn', 1, 'assistant', 'wrong table', 10)
                    """);

            execute(connection, """
                    INSERT INTO permission_request(
                        id, call_id, turn_kind, turn_id, operation_id, capability,
                        parameters_json, policy_snapshot, state, requested_at_ms)
                    VALUES ('permission-1', 'call-1', 'STAGE', 'stage-turn', 'op-stage',
                        'shell', '{}', '{}', 'OPEN', 10)
                    """);
            assertFails(connection, """
                    INSERT INTO permission_request(
                        id, call_id, turn_kind, turn_id, operation_id, capability,
                        parameters_json, policy_snapshot, state, requested_at_ms)
                    VALUES ('permission-bad', 'call-bad', 'TASK', 'stage-turn', 'op-stage',
                        'shell', '{}', '{}', 'OPEN', 10)
                    """);
            assertFails(connection, """
                    UPDATE stage_turn SET operation_id = 'op-reassigned'
                    WHERE id = 'stage-turn'
                    """);
            assertFails(connection, """
                    UPDATE threads
                    SET turn_version = 'LEGACY', aggregate_version = 1
                    WHERE id = 'thread-1'
                    """);
        }
    }

    @Test
    void v2AggregateAndOwnerConstraintsFailClosed()
            throws Exception
    {
        String url = migratedV2("aggregate-fences.db");
        try (Connection connection = connect(url)) {
            assertFails(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, planning_base_sha, prompt,
                        created_by, created_at_ms,
                        creation_authorization_id)
                    VALUES ('assignment-no-seed', 'thread-1', 'NEW_FROM_TRUNK',
                        'base-1', 'build', 'user', 10,
                        'authorization-assignment-no-seed')
                    """);
            assertFails(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, created_by, created_at_ms,
                        creation_authorization_id)
                    VALUES ('assignment-no-issue', 'thread-1', 'ISSUE', 'user', 10,
                        'authorization-assignment-no-issue')
                    """);
            assertFails(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, producer, created_by, created_at_ms,
                        creation_authorization_id)
                    VALUES ('assignment-no-reason', 'thread-1', 'AUTOMATION',
                        'monitor', 'system', 10,
                        'authorization-assignment-no-reason')
                    """);
            assertFails(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, created_by, created_at_ms,
                        creation_authorization_id)
                    VALUES ('assignment-no-evidence', 'thread-1', 'QUALITY_SCAN',
                        'system', 10,
                        'authorization-assignment-no-evidence')
                    """);

            assertFails(connection, """
                    UPDATE tasks SET lifecycle_state = 'PAUSING' WHERE id = 'task-v2'
                    """);
            assertFails(connection, """
                    UPDATE tasks SET aggregate_version = 3 WHERE id = 'task-v2'
                    """);
            assertFails(connection, """
                    UPDATE tasks SET epoch = 3, aggregate_version = 2 WHERE id = 'task-v2'
                    """);
            assertFails(connection, """
                    UPDATE tasks
                    SET assignment_id = 'assignment-v2-other', aggregate_version = 2
                    WHERE id = 'task-v2'
                    """);
            assertFails(connection, """
                    UPDATE threads
                    SET lifecycle_state = NULL, aggregate_version = 1
                    WHERE id = 'thread-1'
                    """);

            assertFails(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                    VALUES ('stage-gap', 'task-v2-other', 'CLEANUP', 2, 0,
                        'WAITING_QUIESCENCE', 10)
                    """);
            assertFails(connection, """
                    UPDATE stage SET checkpoint = 'VALIDATING' WHERE id = 'stage-v2'
                    """);
            assertFails(connection, """
                    UPDATE stage
                    SET checkpoint = 'VALIDATING', version = 2
                    WHERE id = 'stage-v2'
                    """);
            assertFails(connection, """
                    UPDATE stage
                    SET checkpoint = 'COMPLETED', version = 1,
                        completed_at_ms = 20, end_reason = 'NORMAL'
                    WHERE id = 'stage-v2'
                    """);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM stage
                    WHERE id = 'stage-v2' AND completed_at_ms IS NULL
                    """)).isOne();

            assertFails(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, owner_kind, owner_id, blocker_type,
                        status, opened_at_ms)
                    VALUES ('blocker-wrong-task', 'task-v2', 'TASK', 'task-v2-other',
                        'review', 'OPEN', 10)
                    """);
            assertFails(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, owner_kind, owner_id, blocker_type,
                        status, opened_at_ms)
                    VALUES ('blocker-no-stage', 'task-v2', 'STAGE', 'stage-v2',
                        'review', 'OPEN', 10)
                    """);
            assertFails(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, owner_kind, owner_id, blocker_type,
                        status, opened_at_ms)
                    VALUES ('blocker-unknown-episode', 'task-v2', 'EPISODE', 'episode-1',
                        'review', 'OPEN', 10)
                    """);

            assertFails(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family, owner_kind, owner_id,
                        callback_route, lane_mask, workspace_id, trunk_id,
                        task_id, task_epoch, attempt, status, next_attempt_at_ms, created_at_ms)
                    VALUES ('ticket-forged-task', 'op-forged-task', 'VALIDATE', 'VALIDATION',
                        'TASK', 'task-v2', 'task-result', 4, 'ws-default', 'thread-1',
                        'task-v2-other', 1, 1, 'REQUESTED', 10, 10)
                    """);
            assertFails(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family, owner_kind, owner_id,
                        callback_route, lane_mask, attempt, status,
                        next_attempt_at_ms, created_at_ms)
                    VALUES ('ticket-forged-trunk', 'op-trunk', 'TALK', 'AGENT_TURN',
                        'TRUNK', 'thread-1', 'trunk-result', 1, 1,
                        'REQUESTED', 10, 10)
                    """);
            assertFails(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family, owner_kind, owner_id,
                        callback_route, lane_mask, workspace_id, trunk_id,
                        task_id, task_epoch, attempt, status, next_attempt_at_ms, created_at_ms)
                    VALUES ('ticket-no-lane', 'op-no-lane', 'VALIDATE', 'VALIDATION',
                        'TASK', 'task-v2', 'task-result', 0, 'ws-default', 'thread-1',
                        'task-v2', 1, 1, 'REQUESTED', 10, 10)
                    """);
        }
    }

    @Test
    void currentStageHandoffMustCommitAsOneExactOpenLink()
            throws Exception
    {
        String url = migratedV2("stage-handoff.db");
        try (Connection connection = connect(url)) {
            connection.setAutoCommit(false);
            execute(connection, """
                    UPDATE stage
                    SET checkpoint = 'COMPLETED', version = 1,
                        completed_at_ms = 20, end_reason = 'NORMAL'
                    WHERE id = 'stage-v2'
                    """);
            execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                    VALUES ('stage-remote', 'task-v2', 'REMOTE_DEVELOPMENT', 1, 0,
                        'WAITING_CI', 20)
                    """);
            execute(connection, """
                    UPDATE task_current_stage
                    SET stage_id = 'stage-remote', stage_generation = 1
                    WHERE task_id = 'task-v2'
                    """);
            execute(connection, """
                    UPDATE tasks SET aggregate_version = 2 WHERE id = 'task-v2'
                    """);
            connection.commit();

            assertThat(text(connection, """
                    SELECT stage_id FROM task_current_stage WHERE task_id = 'task-v2'
                    """)).isEqualTo("stage-remote");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM stage
                    WHERE task_id = 'task-v2' AND completed_at_ms IS NULL
                    """)).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void v2OwnerDeletionRequiresPurgeAuthorization()
            throws Exception
    {
        String url = migratedV2("owner-delete.db");
        try (Connection connection = connect(url)) {
            assertFails(connection, "DELETE FROM threads WHERE id = 'thread-1'");

            assertThat(number(connection, "SELECT COUNT(*) FROM tasks")).isEqualTo(3);
            assertThat(number(connection, "SELECT COUNT(*) FROM stage")).isEqualTo(3);
            assertThat(number(connection, "SELECT COUNT(*) FROM task_current_stage")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void v2ProtocolRecordsRequireCompleteImmutableFences()
            throws Exception
    {
        String url = migratedV2("fences.db");
        try (Connection connection = connect(url)) {
            assertFails(connection, """
                    INSERT INTO validation_pass(
                        task_id, started_at_ms, workflow_version, code_fingerprint)
                    VALUES ('task-v2', 10, 'V2', 'fp-1')
                    """);
            assertFails(connection, """
                    INSERT INTO validation_pass(
                        task_id, started_at_ms, workflow_version, claim_key,
                        code_fingerprint, task_epoch, stage_id, stage_generation,
                        operation_id, semantic_attempt)
                    VALUES ('task-v2', 10, 'V2', 'v2-validation-zero', 'fp-1',
                        1, 'stage-v2', 1, 'op-validation-zero', 0)
                    """);
            execute(connection, """
                    INSERT INTO validation_pass(
                        task_id, started_at_ms, workflow_version, claim_key,
                        code_fingerprint, task_epoch, stage_id, stage_generation,
                        operation_id, semantic_attempt)
                    VALUES ('task-v2', 10, 'V2', 'v2-validation', 'fp-1',
                        1, 'stage-v2', 1, 'op-validation', 1)
                    """);

            assertFails(connection, """
                    INSERT INTO task_push_authorization(
                        token, task_id, pr_id, head_sha, code_fingerprint, actor,
                        basis_kind, payload_json, payload_digest, created_at_ms,
                        workflow_version)
                    VALUES ('push-v2-bad', 'task-v2', 'pr-local', 'head-1', 'fp-1',
                        'user', 'LOCAL_REVIEW', '{}', 'digest', 10, 'V2')
                    """);
            assertFails(connection, """
                    INSERT INTO task_push_authorization(
                        token, task_id, pr_id, head_sha, code_fingerprint, actor,
                        basis_kind, payload_json, payload_digest, created_at_ms,
                        workflow_version, task_epoch, stage_id, stage_generation,
                        operation_id, semantic_attempt, expected_base_sha)
                    VALUES ('push-v2', 'task-v2', 'pr-local', 'head-1', 'fp-1',
                        'user', 'LOCAL_REVIEW', '{}', 'digest', 10, 'V2',
                        1, 'stage-v2', 1, 'op-push', 1, 'base-1')
                    """);

            assertFails(connection, """
                    INSERT INTO response_round(
                        id, task_id, idx, status, opened_at_ms, workflow_version,
                        code_fingerprint)
                    VALUES ('round-v2-bad', 'task-v2', 1, 'triaging', 10, 'V2', 'fp-1')
                    """);
            execute(connection, """
                    INSERT INTO response_round(
                        id, task_id, idx, status, opened_at_ms, workflow_version,
                        code_fingerprint, task_epoch, stage_id, stage_generation,
                        operation_id, semantic_attempt, expected_head_sha, expected_base_sha)
                    VALUES ('round-v2', 'task-v2', 1, 'triaging', 10, 'V2', 'fp-1',
                        1, 'stage-v2', 1, 'op-round', 1, 'head-1', 'base-1')
                    """);
            assertFails(connection, """
                    INSERT INTO round_gate_authorization(
                        token, task_id, round_id, gate_revision, attempt, actor,
                        code_fingerprint, payload_json, payload_digest, effect_keys_json,
                        approved_at_ms, workflow_version, task_epoch, stage_id,
                        stage_generation, operation_id, semantic_attempt,
                        expected_head_sha, expected_base_sha)
                    VALUES ('gate-v2-stale', 'task-v2', 'round-v2', 1, 1, 'user',
                        'fp-1', '{}', 'digest', '[]', 10, 'V2', 1, 'stage-v2',
                        1, 'op-round', 1, 'stale-head', 'base-1')
                    """);
            assertFails(connection, """
                    INSERT INTO round_gate_authorization(
                        token, task_id, round_id, gate_revision, attempt, actor,
                        code_fingerprint, payload_json, payload_digest, effect_keys_json,
                        approved_at_ms, workflow_version, task_epoch, stage_id,
                        stage_generation, operation_id, semantic_attempt,
                        expected_head_sha, expected_base_sha)
                    VALUES ('gate-v2', 'task-v2', 'round-v2', 1, 1, 'user',
                        'fp-1', '{}', 'digest', '[]', 10, 'V2', 1, 'stage-v2',
                        1, 'op-round', 1, 'head-1', 'base-1')
                    """);

            assertFails(connection, """
                    INSERT INTO worktree_leases(
                        worktree_path, task_id, agent_kind, acquired_at_ms,
                        workflow_version)
                    VALUES ('/tmp/v2-bad', 'task-v2', 'CLI_AGENT', 10, 'V2')
                    """);
            assertFails(connection, """
                    INSERT INTO worktree_leases(
                        worktree_path, task_id, agent_kind, acquired_at_ms, expires_at_ms,
                        workflow_version, operation_id, task_epoch, fencing_token, lease_owner)
                    VALUES ('/tmp/v2-no-capacity', 'task-v2', 'CLI_AGENT', 10, 20,
                        'V2', 'op-without-capacity', 1, 1, 'dispatcher-1')
                    """);
            execute(connection, """
                    INSERT INTO stage_turn(
                        id, stage_id, stage_generation, purpose, status, operation_id,
                        attempt, task_epoch, delivery_lane, launch_input, requested_at_ms)
                    VALUES ('stage-turn-dispatch', 'stage-v2', 1, 'IMPLEMENT', 'QUEUED',
                        'op-dispatch', 1, 1, 'CLI', 'implement', 10)
                    """);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family, owner_kind, owner_id,
                        callback_route, lane_mask, exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, stage_id, stage_generation, attempt,
                        status, next_attempt_at_ms, created_at_ms)
                    VALUES ('ticket-1', 'op-dispatch', 'AGENT_TURN', 'AGENT_TURN',
                        'STAGE_TURN', 'stage-turn-dispatch', 'stage-result', 1, 1, 1, 'ws-default',
                        'thread-1', 'task-v2', 1, 'stage-v2', 1, 1,
                        'REQUESTED', 10, 10)
                    """);
            assertFails(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask, trunk_control,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, holder, fencing_token,
                        acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-wrong-lane', 'ticket-1', 'op-dispatch', 'V2', 2, 0, 1, 1,
                        'ws-default', 'thread-1', 'task-v2', 1,
                        'dispatcher-1', 1, 10, 10, 20)
                    """);
            execute(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask, trunk_control,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, holder, fencing_token,
                        acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-1', 'ticket-1', 'op-dispatch', 'V2', 1, 0, 1, 1,
                        'ws-default', 'thread-1', 'task-v2', 1,
                        'dispatcher-1', 1, 10, 10, 20)
                    """);
            execute(connection, """
                    INSERT INTO worktree_leases(
                        worktree_path, task_id, agent_kind, acquired_at_ms, expires_at_ms,
                        workflow_version, operation_id, task_epoch, fencing_token, lease_owner)
                    VALUES ('/tmp/task-v2', 'task-v2', 'CLI_AGENT', 10, 20, 'V2',
                        'op-dispatch', 1, 1, 'dispatcher-1')
                    """);
            assertFails(connection, """
                    UPDATE worktree_leases
                    SET task_id = 'task-v2-other'
                    WHERE worktree_path = '/tmp/task-v2'
                    """);
            assertFails(connection, """
                    UPDATE worktree_leases
                    SET expires_at_ms = 21
                    WHERE worktree_path = '/tmp/task-v2'
                    """);
            assertFails(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'CLAIMED', claim_purpose = 'EXECUTE',
                        claim_owner = 'dispatcher-1', capacity_lease_id = 'not-the-lease',
                        claim_expires_at_ms = 20
                    WHERE id = 'ticket-1'
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'CLAIMED', claim_purpose = 'EXECUTE',
                        claim_owner = 'dispatcher-1', capacity_lease_id = 'capacity-1',
                        claim_expires_at_ms = 20
                    WHERE id = 'ticket-1'
                    """);
            assertFails(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask, trunk_control,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, holder, fencing_token,
                        acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-duplicate', 'ticket-1', 'op-dispatch', 'V2', 1, 0, 1, 1,
                        'ws-default', 'thread-1', 'task-v2', 1,
                        'dispatcher-2', 2, 10, 10, 20)
                    """);
        }
    }

    private String migratedV2(String database)
            throws Exception
    {
        String url = seedAt222(database);
        migrateTo223(url);
        try (Connection connection = connect(url)) {
            seedV2Graph(connection);
        }
        return url;
    }

    private String seedAt222(String database)
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(database) + "?foreign_keys=ON";
        MigratedSqliteDatabase.migrate(url);
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT OR IGNORE INTO workspaces(
                        id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                    VALUES ('ws-default', 'Default', '', 0, 1, 1)
                    """);
            execute(connection, """
                    INSERT INTO threads(
                        id, kind, provider, title, status, model,
                        cost_usd_milli, tokens_in, tokens_out,
                        created_at_ms, updated_at_ms, workspace_id, flow, parallel_slots)
                    VALUES ('thread-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                        'claude-sonnet-4.6', 0, 0, 0, 1, 1,
                        'ws-default', 'build', 2)
                    """);
            execute(connection, """
                    INSERT INTO tasks(id, thread_id, seq, status, phase, created_at_ms)
                    VALUES ('task-legacy', 'thread-1', 1, 'IDLE', 'PLANNING', 1)
                    """);
            execute(connection, """
                    INSERT INTO task_stage(id, task_id, stage_type, state, opened_at_ms)
                    VALUES ('legacy-stage', 'task-legacy', 'PLAN', 'ACTIVE', 1)
                    """);
        }
        return url;
    }

    private static void seedLegacyProtocolRows(Connection connection, String suffix)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO validation_pass(task_id, started_at_ms, claim_key, code_fingerprint)
                VALUES ('task-legacy', 2, 'validation-%s', 'legacy-fp')
                """.formatted(suffix));
        execute(connection, """
                INSERT INTO task_push_authorization(
                    token, task_id, pr_id, head_sha, code_fingerprint, actor,
                    basis_kind, payload_json, payload_digest, created_at_ms)
                VALUES ('push-%s', 'task-legacy', 'legacy-pr', 'legacy-head',
                    'legacy-fp', 'user', 'LEGACY', '{}', 'digest', 2)
                """.formatted(suffix));
        execute(connection, """
                INSERT INTO response_round(id, task_id, idx, status, opened_at_ms, code_fingerprint)
                VALUES ('round-%s', 'task-legacy', %d, 'posted', 2, 'legacy-fp')
                """.formatted(suffix, suffix.equals("before") ? 1 : 2));
        execute(connection, """
                INSERT INTO round_gate_authorization(
                    token, task_id, round_id, gate_revision, attempt, actor,
                    code_fingerprint, payload_json, payload_digest,
                    effect_keys_json, approved_at_ms)
                VALUES ('gate-%s', 'task-legacy', 'round-%s', 1, 1, 'user',
                    'legacy-fp', '{}', 'digest', '[]', 2)
                """.formatted(suffix, suffix));
        execute(connection, """
                INSERT INTO worktree_leases(
                    worktree_path, task_id, agent_kind, acquired_at_ms)
                VALUES ('/tmp/worktree-%s', 'task-legacy', 'CLI_AGENT', 2)
                """.formatted(suffix));
    }

    private static void seedV2Graph(Connection connection)
            throws SQLException
    {
        execute(connection, """
                UPDATE threads
                SET turn_version = 'V2', lifecycle_state = 'ACTIVE'
                WHERE id = 'thread-1'
                """);
        execute(connection, """
                UPDATE workspaces
                SET detached_at_ms = NULL
                WHERE id = 'ws-default'
                """);
        execute(connection, """
                DELETE FROM workspace_creation
                WHERE workspace_id = 'ws-default'
                  AND operation_kind = 'reclone'
                  AND state IN ('queued', 'forking', 'cloning', 'syncing')
                """);
        V2TaskSeed.prepareWorkspaces(connection);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES ('policy-v2', 'thread-1', 1, 'TRUNK', 'user', 2)
                """);
        JdbcTemplate jdbc = new JdbcTemplate(
                new SingleConnectionDataSource(connection, true));
        V2TaskSeed.insertAuthorized(jdbc, "assignment-v2", seed -> seed.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES ('assignment-v2', 'thread-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build it', 'user', 2,
                    'authorization-assignment-v2')
                """));
        V2TaskSeed.insertCreated(jdbc, "task-v2", seed -> seed.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id, creation_receipt_id, name, task_type,
                    opening_prompt, origin)
                VALUES ('task-v2', 'thread-1', 2, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-v2', 'policy-v2',
                    'creation-receipt-task-v2', 'Test task assignment-v2',
                    'DEVELOP', 'build it', 'user')
                """));
        V2TaskSeed.completeProvisioning(
                jdbc, "task-v2", "base-1", "base-1", "fp-1",
                "exact local Git result", 3);
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('stage-v2', 'task-v2', 'LOCAL_DEVELOPMENT', 1, 0,
                    'IMPLEMENTING', 3)
                """);
        execute(connection, """
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-v2', 'stage-v2', 1)
                """);
        execute(connection, """
                UPDATE tasks
                SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-v2'
                """);
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms, completed_at_ms, end_reason)
                VALUES ('stage-plan-history', 'task-v2', 'PLAN', 1, 1,
                    'COMPLETED', 2, 3, 'NORMAL')
                """);
        V2TaskSeed.insertAuthorized(jdbc, "assignment-v2-other", seed -> seed.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms,
                    creation_authorization_id)
                VALUES ('assignment-v2-other', 'thread-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'other work', 'user', 2,
                    'authorization-assignment-v2-other')
                """));
        V2TaskSeed.insertCreated(jdbc, "task-v2-other", seed -> seed.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id, creation_receipt_id, name, task_type,
                    opening_prompt, origin)
                VALUES ('task-v2-other', 'thread-1', 3, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-v2-other', 'policy-v2',
                    'creation-receipt-task-v2-other',
                    'Test task assignment-v2-other', 'DEVELOP', 'other work',
                    'user')
                """));
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint,
                    opened_at_ms, completed_at_ms, end_reason)
                VALUES ('stage-other', 'task-v2-other', 'PLAN', 1, 1,
                    'COMPLETED', 2, 3, 'NORMAL')
                """);

        execute(connection, """
                INSERT INTO pr(
                    id, branch_name, base_branch, title, description, status,
                    created_at_ms, remote_pr_number, origin, repo)
                VALUES ('review-pr', 'feature', 'main', 'Review', '', 'remote-open',
                    2, 7, 'external', 'acme/widget')
                """);
        execute(connection, """
                INSERT INTO agent_run(
                    id, kind, source, review_round_id, status,
                    started_at_ms, finished_at_ms, outcome)
                VALUES ('review-run', 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', 'review-round-1',
                    'succeeded', 2, 2, 'completed')
                """);
        execute(connection, """
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms)
                VALUES ('review-session', 'acme/widget', 'review-pr', 'base-1',
                    'head-1', 'ACTIVE', 2, 2)
                """);
        execute(connection, """
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope, start_commit,
                    status, budget_json, created_at_ms)
                VALUES ('review-round-1', 'review-session', 'review-run', 'USER',
                    'FULL', 'head-1', 'RUNNING', '{"cost_cap_cents":100}', 2)
                """);
        execute(connection, """
                INSERT INTO reviewer_def(
                    id, name, description, runner, runner_json, eligible_kinds)
                VALUES ('reviewer-1', 'Reviewer', 'Checks code', 'API', '{}', 'FULL')
                """);
        execute(connection, """
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json, budget_json)
                VALUES ('review-assignment-1', 'review-round-1', 'reviewer-1', 'API',
                    'RUNNING', '', '[]', '[]', '{}')
                """);
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        connection.createStatement().execute("PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void migrateTo223(String url)
    {
        MigratedSqliteDatabase.migrate(url);
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        connection.createStatement().executeUpdate(sql);
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class);
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
