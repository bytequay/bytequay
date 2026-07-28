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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.acceptSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.assertFails;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertSnapshot;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.number;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedLegacyTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.text;
import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowCleanupOutcomeProtocolMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void upgradesLegacyRowsAndRestartsWithoutInventingCleanupWork()
            throws Exception
    {
        String url = url("legacy.db");
        migrate(url, "233");
        try (Connection connection = connect(url)) {
            seedLegacyTask(connection);
        }

        migrate(url, "234");
        migrate(url, "234");
        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT workflow_version || '|' || status
                    FROM tasks WHERE id = 'legacy-task'
                    """)).isEqualTo("LEGACY|IDLE");
            assertThat(number(connection, "SELECT COUNT(*) FROM cleanup_operation"))
                    .isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM task_outcome"))
                    .isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void recoversCleanupWithoutRepeatingEffectsAndEmitsOneOutcome()
            throws Exception
    {
        String url = remoteUrl("cleanup.db", true);
        try (Connection connection = connect(url)) {
            assertFails(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt,
                        task_epoch, delivery_lane, launch_input, requested_at_ms)
                    VALUES ('late-turn', 'task-1', 'UNRELATED_WORK', 'REQUESTED',
                        'late-turn-operation', 1, 1, 'API', 'late work', 99)
                    """);
            assertFails(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        workspace_id, trunk_id, task_id, task_epoch, attempt,
                        status, created_at_ms)
                    VALUES ('late-ticket', 'late-operation', 'UNRELATED_WORK',
                        'VALIDATION', 'TASK', 'task-1', 'UNRELATED_RESULT', 4,
                        'workspace-1', 'trunk-1', 'task-1', 1, 1,
                        'REQUESTED', 99)
                    """);
            assertFails(connection, """
                    UPDATE tasks SET lifecycle_state = 'COMPLETED',
                        aggregate_version = 3 WHERE id = 'task-1'
                    """);
            claimStep(connection, 1, "EXECUTE", 100);
        }

        try (Connection connection = connect(url)) {
            assertFails(connection, claimStepSql(1, "PROBE", 199));
            assertFails(connection, claimStepSql(1, "EXECUTE", 201));
            claimStep(connection, 1, "PROBE", 201);
            assertThat(text(connection, """
                    SELECT attempt_count || '|' || execute_attempt_count
                    FROM cleanup_step WHERE id = 'cleanup-step-1'
                    """)).isEqualTo("2|1");
            assertFails(connection, """
                    UPDATE cleanup_step SET claim_owner = 'forged-worker'
                    WHERE id = 'cleanup-step-1'
                    """);
            assertFails(connection, """
                    UPDATE cleanup_step SET last_error = 'forged result'
                    WHERE id = 'cleanup-step-1'
                    """);
            recordResult(connection, 1, 2, "PROBE", "SUCCEEDED",
                    null, "admission guard installed", "step-1", null, 202);
            assertFails(connection, resultSql(1, 2, "PROBE", "SUCCEEDED",
                    null, "duplicate", "duplicate", null, 203));
            finishSuccess(connection, 1, 203);

            for (int ordinal = 2; ordinal <= 5; ordinal++) {
                claimStep(connection, ordinal, "EXECUTE", 210 + ordinal);
                recordResult(connection, ordinal, 1, "EXECUTE", "SUCCEEDED",
                        null, "step complete", "step-" + ordinal, null,
                        220 + ordinal);
                finishSuccess(connection, ordinal, 230 + ordinal);
            }
            claimStep(connection, 6, "EXECUTE", 216);
            assertFails(connection, resultSql(6, 1, "EXECUTE", "SUCCEEDED",
                    null, "interactions dismissed", "step-6", null, 226));
            execute(connection, """
                    UPDATE notifications
                    SET status = 'DISMISSED', read_at_ms = 224
                    WHERE id = 'cleanup-notification-1'
                    """);
            execute(connection, """
                    UPDATE permission_request
                    SET state = 'CANCELED', answer = 'cleanup canceled request',
                        answer_revision = 1, answered_at_ms = 224
                    WHERE id = 'cleanup-permission-1'
                    """);
            execute(connection, """
                    INSERT INTO cleanup_interaction_dismissal_evidence(
                        id, cleanup_step_id, cleanup_operation_id, task_id,
                        task_epoch, dismissed_notification_count,
                        canceled_permission_count, notification_scope_evidence,
                        permission_scope_evidence, recorded_at_ms)
                    VALUES ('interaction-evidence-1', 'cleanup-step-6',
                        'cleanup-operation-1', 'task-1', 1, 1, 1,
                        'all task notifications dismissed',
                        'all task permissions canceled', 225)
                    """);
            recordResult(connection, 6, 1, "EXECUTE", "SUCCEEDED", null,
                    "interactions dismissed", "step-6", null, 226);
            finishSuccess(connection, 6, 236);

            claimStep(connection, 7, "EXECUTE", 237);
            recordResult(connection, 7, 1, "EXECUTE", "SUCCEEDED", null,
                    "runtime leases released", "step-7", null, 238);
            finishSuccess(connection, 7, 239);

            claimStep(connection, 8, "EXECUTE", 240);
            recordResult(connection, 8, 1, "EXECUTE", "FAILED", null,
                    "worktree removal failed", "step-8-failed", "busy", 241);
            openBlocker(connection, 8, 241);
            finishFailure(connection, 8, "DETERMINATE", "busy", 242);
            assertFails(connection, """
                    INSERT INTO cleanup_step_waiver(
                        id, cleanup_step_id, actor_id, reason, evidence,
                        waived_at_ms)
                    VALUES ('required-waiver', 'cleanup-step-8', 'user',
                        'skip required work', 'none', 243)
                    """);
            claimStep(connection, 8, "EXECUTE", 244);
            recordResult(connection, 8, 2, "EXECUTE", "SUCCEEDED",
                    "removed:/tmp/task-1", "worktree absent", "step-8", null,
                    245);
            finishSuccess(connection, 8, 246);
            settleBlocker(connection, 8, "RESOLVED", 247);

            claimStep(connection, 9, "EXECUTE", 250);
            recordResult(connection, 9, 1, "EXECUTE", "SUCCEEDED",
                    "branch:dev/task-1", "local branch absent", "step-9", null,
                    251);
            finishSuccess(connection, 9, 252);

            claimStep(connection, 10, "EXECUTE", 260);
            recordResult(connection, 10, 1, "EXECUTE", "FAILED", null,
                    "remote branch deletion denied", "step-10-failed",
                    "permission denied", 261);
            openBlocker(connection, 10, 261);
            finishFailure(connection, 10, "DETERMINATE", "permission denied", 262);
            execute(connection, """
                    INSERT INTO cleanup_step_waiver(
                        id, cleanup_step_id, actor_id, reason, evidence,
                        waived_at_ms)
                    VALUES ('cleanup-waiver-10', 'cleanup-step-10', 'user',
                        'remote cleanup is optional', 'explicit user waiver', 263)
                    """);
            execute(connection, """
                    UPDATE cleanup_step
                    SET status = 'WAIVED', failure_kind = NULL
                    WHERE id = 'cleanup-step-10'
                    """);
            settleBlocker(connection, 10, "WAIVED", 264);

            assertFails(connection, completeCleanupSql());
            claimStep(connection, 11, "EXECUTE", 270);
            recordResult(connection, 11, 1, "EXECUTE", "SUCCEEDED", null,
                    "final cleanup evidence", "cleanup-summary", null, 271);
            finishSuccess(connection, 11, 272);
            completeCleanup(connection);
            terminalizeTask(connection);

            assertThat(text(connection, """
                    SELECT lifecycle_state FROM tasks WHERE id = 'task-1'
                    """)).isEqualTo("COMPLETED");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_outcome WHERE task_id = 'task-1'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM trunk_outcome_inbox
                    WHERE task_id = 'task-1'
                    """)).isOne();
            assertThat(text(connection, """
                    SELECT lifecycle_state || '|' || current.stage_id
                    FROM tasks task
                    JOIN task_current_stage current ON current.task_id = task.id
                    WHERE task.id = 'task-2'
                    """)).isEqualTo("ACTIVE|remote-stage-2");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM pr WHERE id = 'pr-1'
                    """)).isOne();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_pr_snapshot
                    WHERE task_id = 'task-1'
                    """)).isOne();
            assertFails(connection, """
                    UPDATE tasks SET lifecycle_state = 'ACTIVE',
                        aggregate_version = 4 WHERE id = 'task-1'
                    """);
            assertFails(connection, """
                    DELETE FROM trunk_outcome_inbox WHERE task_id = 'task-1'
                    """);
            assertFails(connection, """
                    DELETE FROM task_outcome WHERE task_id = 'task-1'
                    """);

            assertFails(connection, taskOutcomeSql("duplicate-outcome"));
            assertFails(connection, """
                    INSERT INTO trunk_outcome_inbox(
                        id, trunk_id, task_id, task_outcome_id, delivery_key,
                        fallback_summary_marker, status, created_at_ms)
                    VALUES ('other', 'trunk-1', 'task-1', 'outcome-1', 'other',
                        'FALLBACK:cleanup-summary', 'PENDING', 500)
                    """);
            execute(connection, """
                    UPDATE trunk_outcome_inbox
                    SET status = 'DELIVERED', delivered_at_ms = 510,
                        delivery_evidence = 'Trunk accepted outcome-1'
                    WHERE task_id = 'task-1'
                    """);
            assertFails(connection, """
                    UPDATE trunk_outcome_inbox
                    SET delivered_at_ms = 511
                    WHERE task_id = 'task-1'
                    """);

            assertThat(text(connection, """
                    SELECT summary_state || '|' || summary_text
                    FROM task_outcome WHERE id = 'outcome-1'
                    """)).isEqualTo("FALLBACK|TaskOutcome:task-1:COMPLETED:cleanup-summary");
            enrichSummary(connection);
            assertThat(text(connection, """
                    SELECT summary_state || '|' || summary_digest
                    FROM task_outcome WHERE id = 'outcome-1'
                    """)).isEqualTo("BRAIN_GENERATED|brain-summary-digest");
            assertThat(text(connection, """
                    SELECT fallback_summary_marker
                    FROM trunk_outcome_inbox WHERE task_id = 'task-1'
                    """)).isEqualTo("FALLBACK:cleanup-summary");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM trunk_outcome_inbox
                    WHERE task_id = 'task-1'
                    """)).isOne();
            assertFails(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt,
                        task_epoch, delivery_lane, launch_input, requested_at_ms)
                    VALUES ('duplicate-summary-turn', 'task-1',
                        'TASK_COMPLETION_SUMMARY', 'REQUESTED',
                        'duplicate-summary-operation', 2, 1, 'API',
                        'summarize again', 525)
                    """);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void userCancellationPreservesTheOpenRemotePullRequest()
            throws Exception
    {
        String url = url("cancel.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url, "234");

        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertSnapshot(connection, 1, 1, "head-1", "base-1", "OPEN",
                    "MERGEABLE");
            acceptSnapshot(connection, 1, 1, "head-1", "base-1");
            settleHistoricalTickets(connection);
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('cancel-transition-1', 'task-1', 'cancel-task-1', 2,
                        'ACTIVE', 'CANCELING', 2, 'REQUEST_CANCEL', 'user', 70)
                    """);
            execute(connection, """
                    UPDATE tasks SET lifecycle_state = 'CANCELING', epoch = 2,
                        aggregate_version = 2 WHERE id = 'task-1'
                    """);
            execute(connection, """
                    INSERT INTO task_terminal_intent(
                        id, task_id, kind, source, source_id, evidence_json,
                        accepted, recorded_at_ms)
                    VALUES ('cancel-intent-1', 'task-1', 'CANCELED',
                        'USER_CANCEL', 'cancel-task-1', '{"actor":"user"}', 1, 71)
                    """);
            execute(connection, """
                    INSERT INTO task_terminal_acceptance(
                        id, task_terminal_intent_id, task_id, task_epoch, kind,
                        source_kind, source_id, accepted_by, accepted_at_ms,
                        evidence)
                    VALUES ('cancel-acceptance-1', 'cancel-intent-1', 'task-1',
                        2, 'CANCELED', 'USER_CANCEL', 'cancel-task-1',
                        'TaskManager', 72, 'accepted explicit user cancel')
                    """);
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('cancel-cleaning-transition-1', 'task-1',
                        'open-cancel-cleanup-1', 2, 'CANCELING', 'CLEANING', 3,
                        'OPEN_CANCELED_CLEANUP', 'TaskManager', 73)
                    """);
            execute(connection, """
                    UPDATE tasks SET lifecycle_state = 'CLEANING',
                        aggregate_version = 3 WHERE id = 'task-1'
                    """);

            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE stage
                        SET version = 1, checkpoint = 'COMPLETED',
                            completed_at_ms = 73, end_reason = 'TASK_CANCELED'
                        WHERE id = 'remote-stage-1'
                        """);
                execute(connection, """
                        INSERT INTO stage(
                            id, task_id, kind, generation, checkpoint,
                            opened_at_ms)
                        VALUES ('cancel-cleanup-stage-1', 'task-1', 'CLEANUP', 1,
                            'WAITING_QUIESCENCE', 73)
                        """);
                execute(connection, """
                        UPDATE task_current_stage
                        SET stage_id = 'cancel-cleanup-stage-1',
                            stage_generation = 1
                        WHERE task_id = 'task-1'
                        """);
                connection.commit();
            }
            catch (Throwable failure) {
                connection.rollback();
                throw failure;
            }
            finally {
                connection.setAutoCommit(true);
            }

            assertFails(connection, """
                    INSERT INTO cleanup_stage(
                        stage_id, task_id, task_epoch, generation,
                        terminal_acceptance_id, task_terminal_intent_id,
                        terminal_reason, task_policy_revision_id,
                        remote_pr_binding_id, remote_pr_disposition,
                        local_branch_requirement, remote_branch_requirement,
                        opened_at_ms)
                    VALUES ('cancel-cleanup-stage-1', 'task-1', 2, 1,
                        'cancel-acceptance-1', 'cancel-intent-1', 'CANCELED',
                        'policy-1', 'binding-1', 'REMOTE_ALREADY_TERMINAL',
                        'REQUIRED', 'OPTIONAL', 73)
                    """);
            execute(connection, """
                    INSERT INTO cleanup_stage(
                        stage_id, task_id, task_epoch, generation,
                        terminal_acceptance_id, task_terminal_intent_id,
                        terminal_reason, task_policy_revision_id,
                        remote_pr_binding_id, remote_pr_disposition,
                        local_branch_requirement, remote_branch_requirement,
                        opened_at_ms)
                    VALUES ('cancel-cleanup-stage-1', 'task-1', 2, 1,
                        'cancel-acceptance-1', 'cancel-intent-1', 'CANCELED',
                        'policy-1', 'binding-1', 'PRESERVE_OPEN',
                        'REQUIRED', 'NOT_APPLICABLE', 73)
                    """);

            assertThat(text(connection, """
                    SELECT cleanup.remote_pr_disposition || '|' ||
                        cleanup.remote_branch_requirement || '|' || task.epoch
                    FROM cleanup_stage cleanup
                    JOIN tasks task ON task.id = cleanup.task_id
                    WHERE cleanup.task_id = 'task-1'
                    """)).isEqualTo("PRESERVE_OPEN|NOT_APPLICABLE|2");
            assertThat(text(connection, """
                    SELECT snapshot.pr_state
                    FROM remote_pr_snapshot snapshot
                    JOIN remote_pr_binding binding
                      ON binding.id = snapshot.remote_pr_binding_id
                    WHERE binding.task_id = 'task-1'
                    """)).isEqualTo("OPEN");
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM remote_pr_binding
                    WHERE task_id = 'task-1'
                    """)).isOne();
        }
    }

    private String remoteUrl(String file, boolean sibling)
            throws Exception
    {
        String url = url(file);
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            if (sibling) {
                seedPublishedRemoteTask(connection, 2);
            }
        }
        migrate(url, "234");
        try (Connection connection = connect(url)) {
            prepareMergedCleanup(connection);
        }
        return url;
    }

    private static void prepareMergedCleanup(Connection connection)
            throws Exception
    {
        insertRemoteOwner(connection, 1);
        insertSnapshot(connection, 1, 1, "head-1", "base-1", "MERGED",
                "MERGEABLE");
        acceptSnapshot(connection, 1, 1, "head-1", "base-1");
        execute(connection, """
                INSERT INTO task_terminal_intent(
                    id, task_id, kind, source, source_id, observed_head_sha,
                    evidence_json, accepted, recorded_at_ms)
                VALUES ('terminal-intent-1', 'task-1', 'COMPLETED',
                    'REMOTE_OBSERVATION', 'snapshot-1-1', 'head-1',
                    '{"remote":"merged"}', 1, 70)
                """);
        execute(connection, """
                INSERT INTO remote_terminal_observation(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    remote_pr_snapshot_id, task_terminal_intent_id, kind,
                    head_sha, base_sha, observed_at_ms, evidence)
                VALUES ('terminal-observation-1', 'remote-stage-1', 'task-1', 1,
                    1, 'binding-1', 'snapshot-1-1', 'terminal-intent-1',
                    'MERGED', 'head-1', 'base-1', 61, 'remote reports merged')
                """);
        execute(connection, """
                INSERT INTO task_terminal_acceptance(
                    id, task_terminal_intent_id, task_id, task_epoch, kind,
                    source_kind, source_id, remote_terminal_observation_id,
                    observed_head_sha, accepted_by, accepted_at_ms, evidence)
                VALUES ('terminal-acceptance-1', 'terminal-intent-1', 'task-1',
                    1, 'COMPLETED', 'REMOTE_OBSERVATION', 'snapshot-1-1',
                    'terminal-observation-1', 'head-1', 'TaskManager', 71,
                    'accepted exact merged observation')
                """);
        execute(connection, """
                INSERT INTO notifications(
                    id, kind, thread_id, task_id, status, payload_json,
                    created_at_ms, workspace_id, public_type, title, dedup_key)
                VALUES ('cleanup-notification-1', 'NEEDS_ATTENTION', 'trunk-1',
                    'task-1', 'UNREAD', '{}', 71, 'workspace-1',
                    'agent-question', 'Needs attention',
                    'cleanup-notification-1')
                """);
        execute(connection, """
                INSERT INTO permission_request(
                    id, call_id, turn_kind, turn_id, operation_id, capability,
                    tool_name, parameters_json, policy_snapshot, state,
                    requested_at_ms)
                VALUES ('cleanup-permission-1', 'cleanup-call-1', 'TASK',
                    'brain-turn-1', 'brain-turn-operation-1', 'WRITE', 'shell',
                    '{}', '{}', 'OPEN', 71)
                """);
        settleHistoricalTickets(connection);
        execute(connection, """
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES ('task-cleaning-transition-1', 'task-1',
                    'open-cleanup-1', 1, 'ACTIVE', 'CLEANING', 2,
                    'OPEN_MERGED_CLEANUP', 'TaskManager', 72)
                """);
        execute(connection, """
                UPDATE tasks SET lifecycle_state = 'CLEANING',
                    aggregate_version = 2 WHERE id = 'task-1'
                """);

        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('remote-complete-transition-1', 'remote-stage-1',
                        'open-cleanup-1', 1, 'WAITING_CI', 'COMPLETED', 1,
                        'OBSERVE_REMOTE_MERGED', 'RemoteDevelopmentManager', 72)
                    """);
            execute(connection, """
                    UPDATE stage SET version = 1, checkpoint = 'COMPLETED',
                        completed_at_ms = 72, end_reason = 'REMOTE_MERGED'
                    WHERE id = 'remote-stage-1'
                    """);
            execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint,
                        opened_at_ms)
                    VALUES ('cleanup-stage-1', 'task-1', 'CLEANUP', 1, 0,
                        'WAITING_QUIESCENCE', 72)
                    """);
            execute(connection, """
                    UPDATE task_current_stage
                    SET stage_id = 'cleanup-stage-1', stage_generation = 1
                    WHERE task_id = 'task-1'
                    """);
            connection.commit();
        }
        catch (Throwable failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }

        execute(connection, """
                INSERT INTO cleanup_stage(
                    stage_id, task_id, task_epoch, generation,
                    terminal_acceptance_id, task_terminal_intent_id,
                    terminal_reason, task_policy_revision_id,
                    remote_pr_binding_id, remote_pr_disposition,
                    local_branch_requirement, remote_branch_requirement,
                    opened_at_ms)
                VALUES ('cleanup-stage-1', 'task-1', 1, 1,
                    'terminal-acceptance-1', 'terminal-intent-1', 'COMPLETED',
                    'policy-1', 'binding-1', 'REMOTE_ALREADY_TERMINAL',
                    'REQUIRED', 'OPTIONAL', 72)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    status, created_at_ms)
                VALUES ('cleanup-ticket-1', 'cleanup-operation-id-1',
                    'RUN_CLEANUP_OPERATION', 'CLEANUP', 'STAGE',
                    'cleanup-stage-1', 'CLEANUP_OPERATION_RESULT', 256, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1,
                    'cleanup-stage-1', 1, 1, 'REQUESTED', 80)
                """);
        execute(connection, """
                INSERT INTO cleanup_operation(
                    id, cleanup_stage_id, task_id, task_epoch,
                    stage_generation, terminal_acceptance_id,
                    dispatch_ticket_id, operation_id, semantic_attempt,
                    status, step_count, requested_at_ms)
                VALUES ('cleanup-operation-1', 'cleanup-stage-1', 'task-1', 1,
                    1, 'terminal-acceptance-1', 'cleanup-ticket-1',
                    'cleanup-operation-id-1', 1, 'REQUESTED', 11, 80)
                """);
        insertSteps(connection);
        execute(connection, """
                INSERT INTO capacity_lease(
                    id, ticket_id, operation_id, workflow_source, lane_mask,
                    trunk_control, exclusive_task, writer_required, workspace_id,
                    trunk_id, task_id, task_epoch, holder, fencing_token,
                    acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                VALUES ('cleanup-lease-1', 'cleanup-ticket-1',
                    'cleanup-operation-id-1', 'V2', 256, 0, 1, 1,
                    'workspace-1', 'trunk-1', 'task-1', 1, 'cleanup-worker', 1,
                    90, 90, 1000)
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'CLAIMED', claim_purpose = 'EXECUTE',
                    claim_owner = 'cleanup-worker',
                    capacity_lease_id = 'cleanup-lease-1',
                    claim_expires_at_ms = 900
                WHERE id = 'cleanup-ticket-1'
                """);
        execute(connection, """
                UPDATE cleanup_operation
                SET status = 'ACTIVE', started_at_ms = 91
                WHERE id = 'cleanup-operation-1'
                """);
        execute(connection, """
                INSERT INTO stage_transition(
                    id, stage_id, command_id, generation, from_checkpoint,
                    to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                VALUES ('cleanup-start-transition-1', 'cleanup-stage-1',
                    'start-cleanup-1', 1, 'WAITING_QUIESCENCE', 'CLEANING', 1,
                    'START_CLEANUP', 'CleanupStageManager', 91)
                """);
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'CLEANING'
                WHERE id = 'cleanup-stage-1'
                """);
    }

    private static void settleHistoricalTickets(Connection connection)
            throws Exception
    {
        execute(connection, terminalTicketSql("provision-ticket-1", 2, 73));
        execute(connection, terminalTicketSql("publish-ticket-1", 2, 74));
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'CANCELED',
                    delivery_acceptance = 'SUPERSEDED',
                    delivery_evidence = 'cleanup reconciled request',
                    completed_at_ms = 75
                WHERE id = 'validation-ticket-1'
                """);
    }

    private static String terminalTicketSql(String id, int version, int completedAt)
    {
        return """
                UPDATE dispatch_ticket
                SET version = %2$s, status = 'SUCCEEDED',
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
                    delivery_evidence = 'result already accepted',
                    completed_at_ms = %3$s
                WHERE id = '%1$s'
                """.formatted(id, version, completedAt);
    }

    private static void insertSteps(Connection connection)
            throws Exception
    {
        String[] kinds = {
                "PROVE_NO_NEW_ADMISSIONS", "RECONCILE_OPEN_WORK",
                "STOP_PROVIDER_SESSIONS", "RECONCILE_VALIDATION",
                "SEAL_REVIEW_STATE", "DISMISS_TASK_INTERACTIONS",
                "RELEASE_RUNTIME_LEASES", "REMOVE_WORKTREE",
                "DELETE_LOCAL_BRANCH", "DELETE_REMOTE_BRANCH",
                "RECORD_FINAL_EVIDENCE"};
        for (int ordinal = 1; ordinal <= kinds.length; ordinal++) {
            execute(connection, """
                    INSERT INTO cleanup_step(
                        id, cleanup_operation_id, task_id, task_epoch,
                        cleanup_stage_id, stage_generation, ordinal, kind,
                        requirement, idempotency_key, status, attempt_limit)
                    VALUES ('cleanup-step-%1$s', 'cleanup-operation-1', 'task-1',
                        1, 'cleanup-stage-1', 1, %1$s, '%2$s', '%3$s',
                        'cleanup-operation-1:%2$s', 'REQUESTED', %4$s)
                    """.formatted(ordinal, kinds[ordinal - 1],
                    ordinal == 10 ? "OPTIONAL" : "REQUIRED",
                    ordinal == 1 ? 1 : 3));
        }
    }

    private static void claimStep(
            Connection connection, int ordinal, String mode, int claimedAt)
            throws Exception
    {
        execute(connection, claimStepSql(ordinal, mode, claimedAt));
    }

    private static String claimStepSql(int ordinal, String mode, int claimedAt)
    {
        return """
                UPDATE cleanup_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    execute_attempt_count = execute_attempt_count
                        + CASE '%2$s' WHEN 'EXECUTE' THEN 1 ELSE 0 END,
                    claim_mode = '%2$s', claim_owner = 'cleanup-worker',
                    claimed_at_ms = %3$s, lease_until_ms = %3$s + 100,
                    failure_kind = NULL, last_error = NULL,
                    completed_at_ms = NULL
                WHERE id = 'cleanup-step-%1$s'
                """.formatted(ordinal, mode, claimedAt);
    }

    private static void recordResult(
            Connection connection,
            int ordinal,
            int attempt,
            String claimMode,
            String outcome,
            String externalEffectId,
            String evidence,
            String evidenceDigest,
            String error,
            int recordedAt)
            throws Exception
    {
        execute(connection, resultSql(ordinal, attempt, claimMode, outcome,
                externalEffectId, evidence, evidenceDigest, error, recordedAt));
    }

    private static String resultSql(
            int ordinal,
            int attempt,
            String claimMode,
            String outcome,
            String externalEffectId,
            String evidence,
            String evidenceDigest,
            String error,
            int recordedAt)
    {
        return """
                INSERT INTO cleanup_step_attempt_result(
                    id, cleanup_step_id, cleanup_operation_id, task_id,
                    task_epoch, ordinal, attempt, claim_mode, outcome,
                    external_effect_id, evidence, evidence_digest,
                    error_message, recorded_at_ms)
                VALUES ('cleanup-result-%1$s-%2$s', 'cleanup-step-%1$s',
                    'cleanup-operation-1', 'task-1', 1, %1$s, %2$s, '%3$s',
                    '%4$s', %5$s, '%6$s', '%7$s', %8$s, %9$s)
                """.formatted(
                ordinal,
                attempt,
                claimMode,
                outcome,
                sqlLiteral(externalEffectId),
                evidence,
                evidenceDigest,
                sqlLiteral(error),
                recordedAt);
    }

    private static String sqlLiteral(String value)
    {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    private static void finishSuccess(
            Connection connection, int ordinal, int completedAt)
            throws Exception
    {
        execute(connection, """
                UPDATE cleanup_step
                SET status = 'SUCCEEDED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, failure_kind = NULL,
                    last_error = NULL, completed_at_ms = %2$s
                WHERE id = 'cleanup-step-%1$s'
                """.formatted(ordinal, completedAt));
    }

    private static void finishFailure(
            Connection connection,
            int ordinal,
            String failureKind,
            String error,
            int completedAt)
            throws Exception
    {
        execute(connection, """
                UPDATE cleanup_step
                SET status = 'FAILED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, failure_kind = '%2$s',
                    last_error = '%3$s', completed_at_ms = %4$s
                WHERE id = 'cleanup-step-%1$s'
                """.formatted(ordinal, failureKind, error, completedAt));
    }

    private static void openBlocker(
            Connection connection, int ordinal, int openedAt)
            throws Exception
    {
        execute(connection, """
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES ('cleanup-blocker-%1$s', 'task-1', 'cleanup-stage-1',
                    'OPERATION', 'cleanup-operation-1', '%1$s',
                    'CLEANUP_STEP_FAILED', 'OPEN', '{"step":%1$s}', %2$s)
                """.formatted(ordinal, openedAt));
    }

    private static void settleBlocker(
            Connection connection, int ordinal, String status, int resolvedAt)
            throws Exception
    {
        execute(connection, """
                UPDATE task_blocker
                SET status = '%2$s', resolved_at_ms = %3$s,
                    resolution_evidence = 'cleanup decision recorded'
                WHERE id = 'cleanup-blocker-%1$s'
                """.formatted(ordinal, status, resolvedAt));
    }

    private static String completeCleanupSql()
    {
        return """
                UPDATE cleanup_operation
                SET status = 'COMPLETED', completed_at_ms = 500,
                    summary_digest = 'cleanup-summary'
                WHERE id = 'cleanup-operation-1'
                """;
    }

    private static void completeCleanup(Connection connection)
            throws Exception
    {
        execute(connection, """
                UPDATE capacity_lease
                SET released_at_ms = 490, release_reason = 'cleanup complete'
                WHERE id = 'cleanup-lease-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 2, status = 'RESULT_PENDING',
                    claim_purpose = NULL, claim_owner = NULL,
                    capacity_lease_id = NULL, claim_expires_at_ms = NULL,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'cleanup-summary',
                    pending_result_evidence = 'all cleanup steps settled',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'cleanup-stage-1',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'cleanup-operation-id-1',
                    pending_result_attempt = 1
                WHERE id = 'cleanup-ticket-1'
                """);
        execute(connection, completeCleanupSql());
    }

    private static void terminalizeTask(Connection connection)
            throws Exception
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    INSERT INTO stage_transition(
                        id, stage_id, command_id, generation, from_checkpoint,
                        to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                    VALUES ('cleanup-complete-transition-1', 'cleanup-stage-1',
                        'accept-cleanup-1', 1, 'CLEANING', 'COMPLETED', 2,
                        'ACCEPT_CLEANUP_COMPLETION', 'CleanupStageManager', 501)
                    """);
            execute(connection, """
                    UPDATE stage
                    SET version = 2, checkpoint = 'COMPLETED',
                        completed_at_ms = 501, end_reason = 'NORMAL'
                    WHERE id = 'cleanup-stage-1'
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 3, status = 'SUCCEEDED',
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
                        delivery_evidence = 'CleanupStageManager accepted result',
                        completed_at_ms = 502
                    WHERE id = 'cleanup-ticket-1'
                    """);
            execute(connection, taskOutcomeSql("outcome-1"));
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('task-complete-transition-1', 'task-1',
                        'accept-cleanup-1', 1, 'CLEANING', 'COMPLETED', 3,
                        'ACCEPT_CLEANUP_COMPLETION', 'TaskManager', 503)
                    """);
            execute(connection, """
                    INSERT INTO task_command_receipt(
                        id, task_id, command_id, cause, actor, disposition,
                        expected_task_epoch, expected_task_version,
                        subject_task_epoch, subject_stage_id,
                        subject_stage_generation, subject_operation_id,
                        subject_attempt, returned_trunk_id, returned_lifecycle,
                        returned_epoch, returned_version,
                        returned_current_stage_id, returned_terminal_intent,
                        recorded_at_ms)
                    VALUES ('task-complete-receipt-1', 'task-1',
                        'accept-cleanup-1', 'ACCEPT_CLEANUP_COMPLETION',
                        'TaskManager', 'APPLIED', 1, 2, 1, 'cleanup-stage-1', 1,
                        'cleanup-operation-id-1', 1, 'trunk-1', 'COMPLETED',
                        1, 3, NULL, 'COMPLETED', 503)
                    """);
            execute(connection, """
                    UPDATE tasks
                    SET lifecycle_state = 'COMPLETED', aggregate_version = 3
                    WHERE id = 'task-1'
                    """);
            execute(connection, """
                    DELETE FROM task_current_stage WHERE task_id = 'task-1'
                    """);
            connection.commit();
        }
        catch (Throwable failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    private static String taskOutcomeSql(String id)
    {
        return """
                INSERT INTO task_outcome(
                    id, task_id, trunk_id, task_epoch, terminal_acceptance_id,
                    cleanup_operation_id, cleanup_stage_id, terminal_reason,
                    pr_id, remote_pr_binding_id,
                    remote_terminal_observation_id, observed_head_sha,
                    cleanup_summary_digest, summary_state, summary_text,
                    summary_digest, follow_up_proposals_json,
                    backlog_items_json, recorded_at_ms)
                VALUES ('%1$s', 'task-1', 'trunk-1', 1,
                    'terminal-acceptance-1', 'cleanup-operation-1',
                    'cleanup-stage-1', 'COMPLETED', 'pr-1', 'binding-1',
                    'terminal-observation-1', 'head-1', 'cleanup-summary',
                    'FALLBACK',
                    'TaskOutcome:task-1:COMPLETED:cleanup-summary',
                    'cleanup-summary', '[]', '[]', 503)
                """.formatted(id);
    }

    private static void enrichSummary(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, delivery_lane, launch_input, requested_at_ms)
                VALUES ('summary-turn-1', 'task-1', 'TASK_COMPLETION_SUMMARY',
                    'REQUESTED', 'summary-operation-id-1', 1, 1, 'API',
                    'summarize outcome-1', 520)
                """);
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    workspace_id, trunk_id, task_id, task_epoch, attempt,
                    status, created_at_ms)
                VALUES ('summary-ticket-1', 'summary-operation-id-1',
                    'GENERATE_TASK_OUTCOME_SUMMARY', 'AGENT_TURN', 'TASK_TURN',
                    'summary-turn-1', 'TASK_OUTCOME_SUMMARY_RESULT', 2,
                    'workspace-1', 'trunk-1', 'task-1', 1, 1, 'REQUESTED', 520)
                """);
        execute(connection, """
                INSERT INTO task_outcome_summary_operation(
                    id, task_outcome_id, task_id, task_epoch, task_turn_id,
                    dispatch_ticket_id, operation_id, semantic_attempt,
                    status, requested_at_ms)
                VALUES ('summary-operation-1', 'outcome-1', 'task-1', 1,
                    'summary-turn-1', 'summary-ticket-1',
                    'summary-operation-id-1', 1, 'REQUESTED', 520)
                """);
        execute(connection, """
                UPDATE task_turn
                SET status = 'SUCCEEDED', started_at_ms = 521,
                    finished_at_ms = 522
                WHERE id = 'summary-turn-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'brain-summary-digest',
                    pending_result_evidence = 'Brain returned summary',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = 'summary-operation-id-1',
                    pending_result_attempt = 1
                WHERE id = 'summary-ticket-1'
                """);
        execute(connection, """
                UPDATE task_outcome_summary_operation
                SET status = 'SUCCEEDED', summary_text = 'Brain summary',
                    summary_digest = 'brain-summary-digest',
                    completed_at_ms = 522
                WHERE id = 'summary-operation-1'
                """);
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 2, status = 'SUCCEEDED',
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
                    delivery_evidence = 'TaskManager accepted Brain summary',
                    completed_at_ms = 523
                WHERE id = 'summary-ticket-1'
                """);
        execute(connection, """
                UPDATE task_outcome
                SET summary_state = 'BRAIN_GENERATED',
                    summary_text = 'Brain summary',
                    summary_digest = 'brain-summary-digest',
                    summary_operation_id = 'summary-operation-1',
                    follow_up_proposals_json = '["follow up"]',
                    backlog_items_json = '["backlog"]',
                    summary_updated_at_ms = 524
                WHERE id = 'outcome-1'
                """);
    }

    private String url(String file)
    {
        return "jdbc:sqlite:" + tempDir.resolve(file);
    }
}
