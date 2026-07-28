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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowPlanProtocolMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void provisioningAcceptsOnlyItsFrozenCreationContext()
            throws Exception
    {
        String url = migrated("provisioning.db");
        try (Connection connection = connect(url)) {
            seedProvisioningTask(connection);
            assertFails(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_base_sha,
                        requested_branch_name, requested_worktree_path,
                        status, result_base_sha, result_head_sha,
                        result_code_fingerprint, created_at_ms, completed_at_ms)
                    VALUES ('born-terminal', 'task-1', 1, 'assignment-1',
                        'born-terminal-operation', 1, 'acme/widget', 'base-1',
                        'dev/task-1', '/tmp/task-1', 'ACCEPTED',
                        'base-1', 'base-1', 'fp-1', 4, 5)
                    """);
            seedAcceptedProvisioning(connection);

            execute(connection, """
                    INSERT INTO task_code_identity(
                        task_id, provision_operation_id, repository_id,
                        publish_repository_id, branch_name, worktree_path,
                        base_sha, local_head_sha, code_fingerprint,
                        created_at_ms, updated_at_ms)
                    VALUES ('task-1', 'provision-1', 'acme/widget', 'acme/widget',
                        'dev/task-1', '/tmp/task-1', 'base-1', 'base-1', 'fp-1', 5, 5)
                    """);

            assertFails(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_base_sha,
                        requested_branch_name, requested_worktree_path,
                        status, created_at_ms)
                    VALUES ('provision-stale', 'task-1', 2, 'assignment-1',
                        'operation-stale', 2, 'acme/widget', 'base-1',
                        'dev/task-1', '/tmp/task-1', 'REQUESTED', 6)
                    """);
            assertFails(connection, """
                    INSERT INTO task_code_identity(
                        task_id, provision_operation_id, repository_id,
                        publish_repository_id, branch_name, worktree_path,
                        base_sha, local_head_sha, code_fingerprint,
                        created_at_ms, updated_at_ms)
                    VALUES ('task-other', 'provision-1', 'acme/widget', 'acme/widget',
                        'dev/other', '/tmp/other', 'base-1', 'base-1', 'fp-1', 5, 5)
                    """);
            assertFails(connection, """
                    UPDATE task_code_identity
                    SET branch_name = 'dev/reassigned', version = 1, updated_at_ms = 6
                    WHERE task_id = 'task-1'
                    """);
            assertFails(connection, """
                    UPDATE task_code_identity
                    SET local_head_sha = 'head-2', version = 1, updated_at_ms = 6
                    WHERE task_id = 'task-1'
                    """);
            assertFails(connection, """
                    UPDATE task_code_identity
                    SET base_sha = 'base-2', version = 1, updated_at_ms = 6
                    WHERE task_id = 'task-1'
                    """);
            assertFails(connection, """
                    UPDATE task_code_identity
                    SET base_sha = 'base-2', local_head_sha = 'head-2',
                        code_fingerprint = 'fp-1',
                        version = 1, updated_at_ms = 6
                    WHERE task_id = 'task-1'
                    """);
            assertThat(text(connection, """
                    SELECT local_head_sha FROM task_code_identity WHERE task_id = 'task-1'
                    """)).isEqualTo("base-1");

            execute(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_base_sha,
                        requested_branch_name, requested_worktree_path,
                        status, created_at_ms)
                    VALUES ('provision-stale-result', 'task-1', 1, 'assignment-1',
                        'operation-stale-result', 2, 'acme/widget', 'base-1',
                        'dev/task-1', '/tmp/task-1', 'REQUESTED', 7)
                    """);
            seedProvisioningTicket(connection, "ticket-stale-result", "task-1",
                    "operation-stale-result", 2, "base-1", null);
            execute(connection, """
                    UPDATE provision_task_operation SET status = 'DISPATCHED'
                    WHERE id = 'provision-stale-result'
                    """);
            completeProvisioningTicket(connection, "ticket-stale-result");
            execute(connection, """
                    UPDATE tasks SET epoch = 2, aggregate_version = 1 WHERE id = 'task-1'
                    """);
            assertFails(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'base-1',
                        result_head_sha = 'base-1',
                        result_code_fingerprint = 'fp-stale', completed_at_ms = 8
                    WHERE id = 'provision-stale-result'
                    """);
        }
    }

    @Test
    void planRevisionHasExactlyOneReviewAndExactApproval()
            throws Exception
    {
        String url = migrated("plan-review.db");
        try (Connection connection = connect(url)) {
            seedProvisioningTask(connection);
            seedPlanStage(connection);
            seedPlanRevisionAndTurn(connection);

            assertFails(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, verdict,
                        requested_at_ms, completed_at_ms)
                    VALUES ('review-born-terminal', 'revision-1', 'task-turn-1', 1,
                        'digest-1', 'SUCCEEDED', 'APPROVED', 8, 9)
                    """);
            execute(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, requested_at_ms)
                    VALUES ('review-1', 'revision-1', 'task-turn-1', 1,
                        'digest-1', 'REQUESTED', 8)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, requested_at_ms)
                    VALUES ('review-duplicate', 'revision-1', 'task-turn-1', 1,
                        'digest-1', 'REQUESTED', 8)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_approval(
                        id, plan_revision_id, self_review_id, approval_kind,
                        policy_revision_id, actor, approved_at_ms)
                    VALUES ('approval-early', 'revision-1', 'review-1', 'HUMAN',
                        'policy-1', 'user', 9)
                    """);

            execute(connection, """
                    UPDATE task_turn
                    SET status = 'SUCCEEDED', started_at_ms = 8, finished_at_ms = 9
                    WHERE id = 'task-turn-1'
                    """);
            execute(connection, """
                    UPDATE plan_self_review
                    SET status = 'SUCCEEDED', verdict = 'APPROVED', completed_at_ms = 9
                    WHERE id = 'review-1'
                    """);
            execute(connection, """
                    INSERT INTO plan_followup(
                        id, plan_revision_id, kind, description, status,
                        created_by, created_at_ms)
                    VALUES ('ordinary-followup', 'revision-1', 'FOLLOW_UP',
                        'track a non-blocking cleanup', 'OPEN', 'brain', 9)
                    """);
            execute(connection, """
                    INSERT INTO plan_approval(
                        id, plan_revision_id, self_review_id, approval_kind,
                        policy_revision_id, actor, approved_at_ms)
                    VALUES ('approval-1', 'revision-1', 'review-1', 'HUMAN',
                        'policy-1', 'user', 10)
                    """);
            execute(connection, """
                    INSERT INTO plan_revision(
                        id, plan_stage_id, revision, content, content_digest,
                        source, created_by, created_at_ms)
                    VALUES ('revision-2', 'stage-plan-1', 2, 'edited plan', 'digest-2',
                        'USER_EDIT', 'user', 11)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_approval(
                        id, plan_revision_id, self_review_id, approval_kind,
                        policy_revision_id, actor, approved_at_ms)
                    VALUES ('approval-stale', 'revision-2', 'review-1', 'HUMAN',
                        'policy-1', 'user', 12)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_revision(
                        id, plan_stage_id, revision, content, content_digest,
                        source, created_by, created_at_ms)
                    VALUES ('revision-gap', 'stage-plan-1', 4, 'gap', 'digest-gap',
                        'AGENT', 'agent', 12)
                    """);
            execute(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt, task_epoch,
                        trigger_stage_id, trigger_stage_generation, delivery_lane,
                        launch_input, requested_at_ms, started_at_ms, finished_at_ms)
                    VALUES ('task-turn-retroactive', 'task-1', 'PLAN_SELF_REVIEW',
                        'SUCCEEDED', 'review-operation-retroactive', 1, 1,
                        'stage-plan-1', 1, 'API', 'review old content', 11, 11, 12)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, requested_at_ms)
                    VALUES ('review-retroactive', 'revision-2', 'task-turn-retroactive', 1,
                        'digest-2', 'REQUESTED', 12)
                    """);
            execute(connection, """
                    INSERT INTO task_turn(
                        id, task_id, purpose, status, operation_id, attempt, task_epoch,
                        trigger_stage_id, trigger_stage_generation, delivery_lane,
                        launch_input, requested_at_ms)
                    VALUES ('task-turn-stale', 'task-1', 'PLAN_SELF_REVIEW', 'QUEUED',
                        'review-operation-stale', 1, 1, 'stage-plan-1', 1,
                        'API', 'review revision-2', 12)
                    """);
            execute(connection, """
                    UPDATE tasks SET epoch = 2, aggregate_version = 2 WHERE id = 'task-1'
                    """);
            assertFails(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, requested_at_ms)
                    VALUES ('review-stale', 'revision-2', 'task-turn-stale', 1,
                        'digest-2', 'REQUESTED', 13)
                    """);
        }

        migrate(url);
        migrate(url);
        try (Connection connection = connect(url)) {
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM plan_revision WHERE plan_stage_id = 'stage-plan-1'"))
                    .isEqualTo(2);
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM plan_approval WHERE id = 'approval-1'"))
                    .isOne();
            execute(connection, "DELETE FROM tasks WHERE id = 'task-1'");
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM tasks WHERE id = 'task-1'"))
                    .isZero();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
        }
    }

    @Test
    void policyApprovalRequiresResolvedStewardshipAndFrozenEvidence()
            throws Exception
    {
        String url = migrated("plan-stewardship.db");
        try (Connection connection = connect(url)) {
            seedProvisioningTask(connection);
            seedPlanStage(connection);
            seedPlanRevisionAndTurn(connection);
            execute(connection, """
                    INSERT INTO plan_followup(
                        id, plan_revision_id, kind, description, status,
                        created_by, created_at_ms)
                    VALUES ('stewardship-1', 'revision-1', 'STEWARDSHIP',
                        'human must confirm deployment risk', 'OPEN', 'brain', 7)
                    """);
            execute(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, requested_at_ms)
                    VALUES ('review-1', 'revision-1', 'task-turn-1', 1,
                        'digest-1', 'REQUESTED', 8)
                    """);
            execute(connection, """
                    UPDATE task_turn
                    SET status = 'SUCCEEDED', started_at_ms = 8, finished_at_ms = 9
                    WHERE id = 'task-turn-1'
                    """);
            execute(connection, """
                    UPDATE plan_self_review
                    SET status = 'SUCCEEDED', verdict = 'APPROVED', completed_at_ms = 9
                    WHERE id = 'review-1'
                    """);
            assertFails(connection, """
                    INSERT INTO plan_approval(
                        id, plan_revision_id, self_review_id, approval_kind,
                        policy_revision_id, actor, approved_at_ms)
                    VALUES ('policy-approval-early', 'revision-1', 'review-1', 'POLICY',
                        'policy-1', 'policy', 10)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_followup(
                        id, plan_revision_id, kind, description, status,
                        created_by, created_at_ms)
                    VALUES ('late-concern', 'revision-1', 'CONCERN',
                        'changed after review', 'OPEN', 'user', 10)
                    """);
            execute(connection, """
                    UPDATE plan_followup
                    SET status = 'RESOLVED', resolved_at_ms = 10,
                        resolution = 'human confirmed the risk'
                    WHERE id = 'stewardship-1'
                    """);
            execute(connection, """
                    INSERT INTO plan_approval(
                        id, plan_revision_id, self_review_id, approval_kind,
                        policy_revision_id, actor, approved_at_ms)
                    VALUES ('policy-approval-1', 'revision-1', 'review-1', 'POLICY',
                        'policy-1', 'policy', 11)
                    """);
        }
    }

    @Test
    void failedPlanReviewLinksItsCanonicalTaskBlocker()
            throws Exception
    {
        String url = migrated("plan-blocker.db");
        try (Connection connection = connect(url)) {
            seedProvisioningTask(connection);
            seedPlanStage(connection);
            seedPlanRevisionAndTurn(connection);
            execute(connection, """
                    INSERT INTO plan_self_review(
                        id, plan_revision_id, task_turn_id, task_epoch,
                        reviewed_digest, status, requested_at_ms)
                    VALUES ('review-failed', 'revision-1', 'task-turn-1', 1,
                        'digest-1', 'REQUESTED', 8)
                    """);
            execute(connection, """
                    UPDATE task_turn
                    SET status = 'FAILED', started_at_ms = 8, finished_at_ms = 9,
                        error_message = 'provider returned no verdict'
                    WHERE id = 'task-turn-1'
                    """);
            execute(connection, """
                    UPDATE plan_self_review
                    SET status = 'FAILED', completed_at_ms = 9,
                        error_message = 'provider returned no verdict'
                    WHERE id = 'review-failed'
                    """);
            execute(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, stage_id, owner_kind, owner_id,
                        subject_revision, blocker_type, status, opened_at_ms)
                    VALUES ('plan-blocker-1', 'task-1', 'stage-plan-1', 'STAGE',
                        'stage-plan-1', 'digest-1', 'PLAN_REVIEW_FAILURE', 'OPEN', 9)
                    """);
            execute(connection, """
                    INSERT INTO task_blocker(
                        id, task_id, stage_id, owner_kind, owner_id,
                        subject_revision, blocker_type, status, opened_at_ms)
                    VALUES ('plan-blocker-wrong', 'task-1', 'stage-plan-1', 'STAGE',
                        'stage-plan-1', 'other-digest', 'PLAN_REVIEW_FAILURE', 'OPEN', 9)
                    """);
            assertFails(connection, """
                    INSERT INTO plan_followup(
                        id, plan_revision_id, kind, description, status,
                        self_review_id, task_blocker_id, created_by, created_at_ms)
                    VALUES ('wrong-blocker-link', 'revision-1', 'FAILURE_BLOCKER',
                        'wrong subject', 'OPEN', 'review-failed', 'plan-blocker-wrong',
                        'system', 9)
                    """);
            execute(connection, """
                    INSERT INTO plan_followup(
                        id, plan_revision_id, kind, description, status,
                        self_review_id, task_blocker_id, created_by, created_at_ms)
                    VALUES ('failure-followup-1', 'revision-1', 'FAILURE_BLOCKER',
                        'self-review failed without a verdict', 'OPEN',
                        'review-failed', 'plan-blocker-1', 'system', 9)
                    """);
            assertThat(text(connection, """
                    SELECT task_blocker_id FROM plan_followup
                    WHERE id = 'failure-followup-1'
                    """)).isEqualTo("plan-blocker-1");
        }
    }

    @Test
    void existingPrProvisioningStartsFromTheAssignedRemoteHead()
            throws Exception
    {
        String url = migrated("existing-pr.db");
        try (Connection connection = connect(url)) {
            seedProvisioningTask(connection);
            execute(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, repository_id, pr_number,
                        remote_head_sha, created_by, created_at_ms)
                    VALUES ('assignment-pr', 'trunk-1', 'EXISTING_OWN_PR',
                        'acme/widget', 42, 'remote-head-1', 'user', 3)
                    """);
            execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms,
                        workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                    VALUES ('task-pr', 'trunk-1', 2, 'IDLE', 'PLANNING', 3,
                        'V2', 'PROVISIONING', 'assignment-pr', 'policy-1')
                    """);
            assertFails(connection, """
                    INSERT INTO task_creation_context(
                        task_id, assignment_id, policy_revision_id, provenance,
                        repository_id, publish_repository_id, assignment_head_sha, engine_snapshot,
                        work_model_snapshot, created_at_ms)
                    VALUES ('task-pr', 'assignment-pr', 'policy-1', 'DIRECT_USER',
                        'acme/widget', 'acme/widget', 'wrong-head',
                        'engine-v1', 'model-v1', 4)
                    """);
            assertFails(connection, """
                    INSERT INTO task_creation_context(
                        task_id, assignment_id, policy_revision_id, provenance,
                        repository_id, publish_repository_id, planning_base_sha,
                        assignment_head_sha, engine_snapshot,
                        work_model_snapshot, created_at_ms)
                    VALUES ('task-pr', 'assignment-pr', 'policy-1', 'DIRECT_USER',
                        'acme/widget', 'acme/widget', 'remote-base-1',
                        'remote-head-1', 'engine-v1', 'model-v1', 4)
                    """);
            execute(connection, """
                    INSERT INTO task_creation_context(
                        task_id, assignment_id, policy_revision_id, provenance,
                        repository_id, publish_repository_id, assignment_head_sha, engine_snapshot,
                        work_model_snapshot, created_at_ms)
                    VALUES ('task-pr', 'assignment-pr', 'policy-1', 'DIRECT_USER',
                        'acme/widget', 'acme/widget', 'remote-head-1',
                        'engine-v1', 'model-v1', 4)
                    """);
            execute(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_remote_head_sha,
                        requested_branch_name, requested_worktree_path, status, created_at_ms)
                    VALUES ('provision-pr-stale', 'task-pr', 1, 'assignment-pr',
                        'operation-pr-stale', 1, 'acme/widget', 'remote-head-1',
                        'dev/task-pr', '/tmp/task-pr', 'REQUESTED', 5)
                    """);
            seedProvisioningTicket(connection, "ticket-pr-stale", "task-pr",
                    "operation-pr-stale", 1, null, "remote-head-1");
            execute(connection, """
                    UPDATE provision_task_operation SET status = 'DISPATCHED'
                    WHERE id = 'provision-pr-stale'
                    """);
            completeProvisioningTicket(connection, "ticket-pr-stale");
            assertFails(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'remote-base-1',
                        result_head_sha = 'different-head',
                        result_code_fingerprint = 'fp-pr', completed_at_ms = 6
                    WHERE id = 'provision-pr-stale'
                    """);
            execute(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_remote_head_sha,
                        requested_branch_name, requested_worktree_path, status, created_at_ms)
                    VALUES ('provision-pr', 'task-pr', 1, 'assignment-pr',
                        'operation-pr', 2, 'acme/widget', 'remote-head-1',
                        'dev/task-pr', '/tmp/task-pr', 'REQUESTED', 6)
                    """);
            seedProvisioningTicket(connection, "ticket-pr", "task-pr",
                    "operation-pr", 2, null, "remote-head-1");
            execute(connection, """
                    UPDATE provision_task_operation SET status = 'DISPATCHED'
                    WHERE id = 'provision-pr'
                    """);
            completeProvisioningTicket(connection, "ticket-pr");
            execute(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'remote-base-1',
                        result_head_sha = 'remote-head-1',
                        result_code_fingerprint = 'fp-pr', completed_at_ms = 7
                    WHERE id = 'provision-pr'
                    """);
            assertThat(text(connection, """
                    SELECT result_head_sha FROM provision_task_operation
                    WHERE id = 'provision-pr'
                    """)).isEqualTo("remote-head-1");
            assertFails(connection, """
                    INSERT INTO task_code_identity(
                        task_id, provision_operation_id, repository_id,
                        publish_repository_id, branch_name, worktree_path,
                        base_sha, local_head_sha, code_fingerprint,
                        created_at_ms, updated_at_ms)
                    VALUES ('task-pr', 'provision-pr', 'acme/widget', 'someone/else',
                        'dev/task-pr', '/tmp/task-pr', 'remote-base-1',
                        'remote-head-1', 'fp-pr', 7, 7)
                    """);
            execute(connection, """
                    INSERT INTO task_code_identity(
                        task_id, provision_operation_id, repository_id,
                        publish_repository_id, branch_name, worktree_path,
                        base_sha, local_head_sha, code_fingerprint,
                        created_at_ms, updated_at_ms)
                    VALUES ('task-pr', 'provision-pr', 'acme/widget', 'acme/widget',
                        'dev/task-pr', '/tmp/task-pr', 'remote-base-1',
                        'remote-head-1', 'fp-pr', 7, 7)
                    """);
        }
    }

    @Test
    void replanAdvancesEpochAndRepointsPlanAtomically()
            throws Exception
    {
        String url = migrated("replan.db");
        try (Connection connection = connect(url)) {
            seedProvisioningTask(connection);
            seedPlanStage(connection);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, attempt, status, created_at_ms)
                    VALUES ('ticket-live', 'operation-live', 'LOCAL_MUTATION', 'LOCAL_GIT',
                        'TASK', 'task-1', 'TASK_LOCAL_RESULT', 16, 1, 1,
                        'workspace-1', 'trunk-1', 'task-1', 1, 1, 'REQUESTED', 18)
                    """);
            execute(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch, holder,
                        fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-live', 'ticket-live', 'operation-live', 'V2', 16,
                        0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1, 'worker-1',
                        1, 18, 18, 100)
                    """);
            execute(connection, """
                    INSERT INTO worktree_leases(
                        worktree_path, task_id, agent_kind, acquired_at_ms, expires_at_ms,
                        workflow_version, operation_id, task_epoch, fencing_token, lease_owner)
                    VALUES ('/tmp/live-task-1', 'task-1', 'CLI_AGENT', 18, 100,
                        'V2', 'operation-live', 1, 1, 'worker-1')
                    """);
            execute(connection, """
                    INSERT INTO task_quiescence_barrier(
                        id, task_id, task_epoch, reason, status, requested_at_ms)
                    VALUES ('quiescence-1', 'task-1', 1, 'REPLAN', 'REQUESTED', 19)
                    """);
            execute(connection, """
                    INSERT INTO task_replan_request(
                        id, task_id, source_stage_id, source_generation,
                        source_task_epoch, target_task_epoch, quiescence_barrier_id,
                        command_id, reason,
                        requested_by, status, requested_at_ms)
                    VALUES ('replan-1', 'task-1', 'stage-plan-1', 1, 1, 2,
                        'quiescence-1', 'command-replan-1', 'scope changed',
                        'user', 'REQUESTED', 20)
                    """);
            execute(connection, """
                    UPDATE task_replan_request SET status = 'QUIESCING'
                    WHERE id = 'replan-1'
                    """);
            assertFails(connection, """
                    UPDATE task_quiescence_barrier
                    SET status = 'SATISFIED', completed_at_ms = 20,
                        evidence = 'must not trust this assertion'
                    WHERE id = 'quiescence-1'
                    """);
            execute(connection, "DELETE FROM worktree_leases WHERE worktree_path = '/tmp/live-task-1'");
            execute(connection, """
                    UPDATE capacity_lease
                    SET released_at_ms = 20, release_reason = 'QUIESCED'
                    WHERE id = 'capacity-live'
                    """);
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, attempt, status, created_at_ms)
                    VALUES ('ticket-blocked', 'operation-blocked', 'LOCAL_MUTATION', 'LOCAL_GIT',
                        'TASK', 'task-1', 'TASK_LOCAL_RESULT', 16, 1, 1,
                        'workspace-1', 'trunk-1', 'task-1', 1, 1, 'REQUESTED', 20)
                    """);
            assertFails(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch, holder,
                        fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-blocked', 'ticket-blocked', 'operation-blocked', 'V2', 16,
                        0, 1, 1, 'workspace-1', 'trunk-1', 'task-1', 1, 'worker-2',
                        2, 20, 20, 100)
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 1, status = 'RECONCILE_WAIT'
                    WHERE id = 'ticket-live'
                    """);
            execute(connection, """
                    INSERT INTO agent_execution(
                        id, ticket_id, infrastructure_attempt, status, started_at_ms)
                    VALUES ('execution-unknown', 'ticket-live', 1, 'UNKNOWN', 18)
                    """);
            assertFails(connection, """
                    UPDATE task_quiescence_barrier
                    SET status = 'SATISFIED', completed_at_ms = 20,
                        evidence = 'unknown effect still exists'
                    WHERE id = 'quiescence-1'
                    """);
            execute(connection, """
                    UPDATE agent_execution SET status = 'FAILED', finished_at_ms = 20
                    WHERE id = 'execution-unknown'
                    """);
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = 2, status = 'RETRY_WAIT', next_attempt_at_ms = 30
                    WHERE id = 'ticket-live'
                    """);
            execute(connection, """
                    UPDATE task_quiescence_barrier
                    SET status = 'SATISFIED', completed_at_ms = 21,
                        evidence = 'no live mutating claims'
                    WHERE id = 'quiescence-1'
                    """);
            assertFails(connection, """
                    INSERT INTO capacity_lease(
                        id, ticket_id, operation_id, workflow_source, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch, holder,
                        fencing_token, acquired_at_ms, heartbeat_at_ms, expires_at_ms)
                    VALUES ('capacity-blocked-after', 'ticket-blocked',
                        'operation-blocked', 'V2', 16, 0, 1, 1,
                        'workspace-1', 'trunk-1', 'task-1', 1, 'worker-3',
                        3, 21, 21, 100)
                    """);

            connection.setAutoCommit(false);
            execute(connection, """
                    UPDATE stage
                    SET version = 1, checkpoint = 'COMPLETED', completed_at_ms = 21,
                        end_reason = 'NORMAL'
                    WHERE id = 'stage-plan-1'
                    """);
            execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                    VALUES ('stage-plan-broken', 'task-1', 'PLAN', 2, 0, 'DRAFTING', 21)
                    """);
            execute(connection, """
                    UPDATE task_current_stage
                    SET stage_id = 'stage-plan-broken', stage_generation = 2
                    WHERE task_id = 'task-1'
                    """);
            execute(connection, """
                    UPDATE tasks SET epoch = 2, aggregate_version = 2
                    WHERE id = 'task-1'
                    """);
            execute(connection, """
                    INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                    VALUES ('stage-plan-broken', 'task-1', 2, 2)
                    """);
            assertFails(connection, """
                    UPDATE task_replan_request
                    SET status = 'APPLIED', new_plan_stage_id = 'stage-plan-broken',
                        new_plan_generation = 2, completed_at_ms = 21
                    WHERE id = 'replan-1'
                    """);
            connection.rollback();
            connection.setAutoCommit(true);
            assertThat(text(connection,
                    "SELECT completed_at_ms FROM stage WHERE id = 'stage-plan-1'"))
                    .isNull();
            assertThat(text(connection,
                    "SELECT stage_id FROM task_current_stage WHERE task_id = 'task-1'"))
                    .isEqualTo("stage-plan-1");
            assertThat(number(connection, "SELECT epoch FROM tasks WHERE id = 'task-1'"))
                    .isOne();
            assertThat(text(connection,
                    "SELECT status FROM task_replan_request WHERE id = 'replan-1'"))
                    .isEqualTo("QUIESCING");

            connection.setAutoCommit(false);
            execute(connection, """
                    UPDATE stage
                    SET version = 1, completed_at_ms = 21,
                        end_reason = 'SUPERSEDED_BY_REPLAN'
                    WHERE id = 'stage-plan-1'
                    """);
            execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                    VALUES ('stage-plan-2', 'task-1', 'PLAN', 2, 0, 'DRAFTING', 21)
                    """);
            execute(connection, """
                    UPDATE task_current_stage
                    SET stage_id = 'stage-plan-2', stage_generation = 2
                    WHERE task_id = 'task-1'
                    """);
            execute(connection, """
                    UPDATE tasks SET epoch = 2, aggregate_version = 2
                    WHERE id = 'task-1'
                    """);
            execute(connection, """
                    INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                    VALUES ('stage-plan-2', 'task-1', 2, 2)
                    """);
            execute(connection, """
                    UPDATE task_replan_request
                    SET status = 'APPLIED', new_plan_stage_id = 'stage-plan-2',
                        new_plan_generation = 2, completed_at_ms = 21
                    WHERE id = 'replan-1'
                    """);
            connection.commit();

            assertThat(number(connection,
                    "SELECT epoch FROM tasks WHERE id = 'task-1'"))
                    .isEqualTo(2);
            assertThat(text(connection,
                    "SELECT stage_id FROM task_current_stage WHERE task_id = 'task-1'"))
                    .isEqualTo("stage-plan-2");
            assertThat(text(connection,
                    "SELECT status FROM task_replan_request WHERE id = 'replan-1'"))
                    .isEqualTo("APPLIED");
        }
    }

    @Test
    void upgradesPopulatedVersion224WithoutRewritingExistingRows()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("upgrade-224.db") + "?foreign_keys=ON";
        migrate(url, "224");
        try (Connection connection = connect(url)) {
            execute(connection, """
                    INSERT INTO workspaces(
                        id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                    VALUES ('upgrade-workspace', 'Upgrade', '', 0, 1, 1)
                    """);
            execute(connection, """
                    INSERT INTO threads(
                        id, kind, provider, title, status, model, cost_usd_milli,
                        tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                        flow, parallel_slots, turn_version, lifecycle_state)
                    VALUES ('upgrade-trunk', 'CLI_AGENT', 'claude-code', 'Upgrade trunk',
                        'IDLE', 'claude-sonnet-4.6', 0, 0, 0, 1, 1,
                        'upgrade-workspace', 'build', 2, 'V2', 'ACTIVE')
                    """);
            execute(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                        created_by, created_at_ms)
                    VALUES ('upgrade-assignment', 'upgrade-trunk', 'NEW_FROM_TRUNK',
                        'upgrade-base', 'seed', 'build', 'user', 2)
                    """);
            execute(connection, """
                    INSERT INTO task_policy_revision(
                        id, trunk_id, revision, source, created_by, created_at_ms)
                    VALUES ('upgrade-policy', 'upgrade-trunk', 1, 'TRUNK', 'user', 2)
                    """);
            execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms,
                        workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                    VALUES ('upgrade-task', 'upgrade-trunk', 1, 'IDLE', 'PLANNING', 2,
                        'V2', 'PROVISIONING', 'upgrade-assignment', 'upgrade-policy')
                    """);
            execute(connection, """
                    INSERT INTO threads(
                        id, kind, provider, title, status, model, cost_usd_milli,
                        tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                        flow, parallel_slots, turn_version)
                    VALUES ('legacy-trunk', 'CLI_AGENT', 'claude-code', 'Legacy trunk',
                        'IDLE', 'claude-sonnet-4.6', 0, 0, 0, 1, 1,
                        'upgrade-workspace', 'build', 1, 'LEGACY')
                    """);
            execute(connection, """
                    INSERT INTO tasks(
                        id, thread_id, seq, status, phase, created_at_ms, workflow_version)
                    VALUES ('legacy-task', 'legacy-trunk', 1, 'IDLE', 'PLANNING', 2, 'LEGACY')
                    """);
        }

        migrate(url, "225");
        migrate(url, "225");
        try (Connection connection = connect(url)) {
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM tasks WHERE id = 'upgrade-task' AND epoch = 1"))
                    .isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM tasks WHERE id = 'legacy-task' AND workflow_version = 'LEGACY'"))
                    .isOne();
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM task_creation_context"))
                    .isZero();
            execute(connection, """
                    INSERT INTO task_creation_context(
                        task_id, assignment_id, policy_revision_id, provenance,
                        repository_id, publish_repository_id, planning_base_sha,
                        engine_snapshot, work_model_snapshot, created_at_ms)
                    VALUES ('upgrade-task', 'upgrade-assignment', 'upgrade-policy',
                        'DIRECT_USER', 'acme/widget', 'acme/widget', 'upgrade-base',
                        'engine-v1', 'model-v1', 3)
                    """);
            assertThat(number(connection,
                    "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    private String migrated(String file)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
        migrate(url);
        return url;
    }

    private static void migrate(String url)
    {
        migrate(url, "225");
    }

    private static void migrate(String url, String target)
    {
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void seedProvisioningTask(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1', 'build', 2,
                    'V2', 'ACTIVE')
                """);
        execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-1', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-1', 'seed', 'build', 'user', 2)
                """);
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    created_by, created_at_ms)
                VALUES ('policy-1', 'trunk-1', 1, 'TRUNK', 1, 'user', 2)
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id, policy_revision_id)
                VALUES ('task-1', 'trunk-1', 1, 'IDLE', 'PLANNING', 2,
                    'V2', 'PROVISIONING', 'assignment-1', 'policy-1')
                """);
        execute(connection, """
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha, engine_snapshot,
                    work_model_snapshot, created_at_ms)
                VALUES ('task-1', 'assignment-1', 'policy-1', 'DIRECT_USER',
                    'acme/widget', 'acme/widget', 'base-1', 'engine-v1', 'model-v1', 3)
                """);
        assertFails(connection, """
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-wrong', 'task-1', 'openai', 'review-model',
                    'different-engine', 3)
                """);
        execute(connection, """
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-1', 'task-1', 'openai', 'review-model', 'engine-v1', 3)
                """);
    }

    private static void seedAcceptedProvisioning(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-1', 'task-1', 1, 'assignment-1', 'operation-1',
                    1, 'acme/widget', 'base-1', 'dev/task-1', '/tmp/task-1',
                    'REQUESTED', 4)
                """);
        seedProvisioningTicket(connection, "ticket-1", "task-1", "operation-1", 1,
                "base-1", null);
        execute(connection, """
                UPDATE provision_task_operation SET status = 'DISPATCHED'
                WHERE id = 'provision-1'
                """);
        completeProvisioningTicket(connection, "ticket-1");
        assertFails(connection, """
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-1',
                    result_head_sha = 'wrong-new-task-head',
                    result_code_fingerprint = 'fp-1', completed_at_ms = 5
                WHERE id = 'provision-1'
                """);
        execute(connection, """
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-1',
                    result_head_sha = 'base-1', result_code_fingerprint = 'fp-1',
                    completed_at_ms = 5
                WHERE id = 'provision-1'
                """);
    }

    private static void seedProvisioningTicket(
            Connection connection,
            String ticketId,
            String taskId,
            String operationId,
            int attempt,
            String expectedBaseSha,
            String expectedHeadSha)
            throws SQLException
    {
        String base = expectedBaseSha == null ? "NULL" : "'" + expectedBaseSha + "'";
        String head = expectedHeadSha == null ? "NULL" : "'" + expectedHeadSha + "'";
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    expected_head_sha, status, created_at_ms)
                VALUES ('%s', '%s', 'PROVISION_TASK', 'LOCAL_GIT',
                    'TASK', '%s', 'TASK_PROVISION_RESULT', 16,
                    1, 1, 'workspace-1', 'trunk-1', '%s', 1, %s, %s, %s,
                    'REQUESTED', 4)
                """.formatted(ticketId, operationId, taskId, taskId, attempt, base, head));
    }

    private static void completeProvisioningTicket(Connection connection, String ticketId)
            throws SQLException
    {
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'exact local Git result'
                WHERE id = '%s'
                """.formatted(ticketId));
    }

    private static void seedPlanStage(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('stage-plan-1', 'task-1', 'PLAN', 1, 0, 'DRAFTING', 5)
                """);
        execute(connection, """
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-1', 'stage-plan-1', 1)
                """);
        execute(connection, """
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-1'
                """);
        execute(connection, """
                INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                VALUES ('stage-plan-1', 'task-1', 1, 1)
                """);
    }

    private static void seedPlanRevisionAndTurn(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO plan_revision(
                    id, plan_stage_id, revision, content, content_digest,
                    source, created_by, created_at_ms)
                VALUES ('revision-1', 'stage-plan-1', 1, 'the plan', 'digest-1',
                    'AGENT', 'agent', 6)
                """);
        execute(connection, """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt, task_epoch,
                    trigger_stage_id, trigger_stage_generation, delivery_lane,
                    launch_input, requested_at_ms)
                VALUES ('task-turn-1', 'task-1', 'PLAN_SELF_REVIEW', 'QUEUED',
                    'review-operation-1', 1, 1, 'stage-plan-1', 1,
                    'API', 'review revision-1', 7)
                """);
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class);
    }
}
