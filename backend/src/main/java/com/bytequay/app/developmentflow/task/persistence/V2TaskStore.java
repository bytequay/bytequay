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
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.creation.ProvisionTarget;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static java.util.Objects.requireNonNull;

/** Spring-transaction-bound persistence for Task state, proofs and receipts. */
@Component
final class V2TaskStore
        implements TaskManager.Store
{
    private static final String IDLE_ARCHIVE_ELIGIBILITY = """
            WITH task_activity(task_id, occurred_at_ms) AS (
                SELECT id, created_at_ms
                  FROM tasks WHERE workflow_version = 'V2'
                UNION ALL
                SELECT task_id, occurred_at_ms FROM task_transition
                UNION ALL
                SELECT stage.task_id, stage.opened_at_ms FROM stage
                UNION ALL
                SELECT stage.task_id, transition.occurred_at_ms
                  FROM stage_transition transition
                  JOIN stage ON stage.id = transition.stage_id
                UNION ALL
                SELECT task_id, COALESCE(finished_at_ms, started_at_ms, requested_at_ms)
                  FROM task_turn
                UNION ALL
                SELECT stage.task_id,
                       COALESCE(turn.finished_at_ms, turn.started_at_ms,
                                turn.requested_at_ms)
                  FROM stage_turn turn
                  JOIN stage ON stage.id = turn.stage_id
                UNION ALL
                SELECT pull_request.task_id,
                       COALESCE(turn.finished_at_ms, turn.started_at_ms,
                                turn.requested_at_ms)
                  FROM review_assignment_turn turn
                  JOIN review_assignment assignment ON assignment.id = turn.assignment_id
                  JOIN review_round round ON round.id = assignment.round_id
                  JOIN review_session session ON session.id = round.session_id
                  JOIN pr pull_request ON pull_request.id = session.pr_id
                 WHERE pull_request.origin = 'task'
                UNION ALL
                SELECT task_id, COALESCE(completed_at_ms, started_at_ms, created_at_ms)
                  FROM dispatch_ticket WHERE task_id IS NOT NULL
                UNION ALL
                SELECT task_id, COALESCE(resolved_at_ms, opened_at_ms)
                  FROM task_blocker
                UNION ALL
                SELECT turn.task_id,
                       COALESCE(question.answered_at_ms, question.created_at_ms)
                  FROM task_question question
                  JOIN task_turn turn ON turn.id = question.turn_id
                UNION ALL
                SELECT stage.task_id,
                       COALESCE(question.answered_at_ms, question.created_at_ms)
                  FROM stage_question question
                  JOIN stage_turn turn ON turn.id = question.turn_id
                  JOIN stage ON stage.id = turn.stage_id
                UNION ALL
                SELECT task_id, state_changed_at_ms
                  FROM local_review_comment_revision
                UNION ALL
                SELECT plan.task_id, revision.created_at_ms
                  FROM plan_revision revision
                  JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                UNION ALL
                SELECT plan.task_id,
                       COALESCE(followup.resolved_at_ms, followup.created_at_ms)
                  FROM plan_followup followup
                  JOIN plan_revision revision ON revision.id = followup.plan_revision_id
                  JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                UNION ALL
                SELECT task_id, requested_at_ms
                  FROM stage_steering_request_v257
                UNION ALL
                SELECT task_id,
                       COALESCE(materialized_at_ms, diagnosed_at_ms, accepted_at_ms)
                  FROM stage_resume_rearm_intent_v257
            ),
            latest_activity AS (
                SELECT task_id, MAX(occurred_at_ms) AS occurred_at_ms
                  FROM task_activity
                 GROUP BY task_id
            )
            SELECT task.id, latest_activity.occurred_at_ms
              FROM tasks task
              JOIN task_current_stage current ON current.task_id = task.id
              JOIN stage owner ON owner.id = current.stage_id
              JOIN task_live_work_counts_v230 live ON live.task_id = task.id
              JOIN task_control_live_work_v256 all_epoch ON all_epoch.task_id = task.id
              JOIN latest_activity ON latest_activity.task_id = task.id
             WHERE task.workflow_version = 'V2'
               AND task.lifecycle_state = 'ACTIVE'
               AND owner.task_id = task.id
               AND owner.generation = current.stage_generation
               AND owner.completed_at_ms IS NULL
               AND owner.kind <> 'REMOTE_DEVELOPMENT'
               AND owner.checkpoint NOT IN ('LOCAL_REVIEW', 'PUBLISHING')
               AND NOT EXISTS (
                   SELECT 1 FROM task_blocker blocker
                    WHERE blocker.task_id = task.id AND blocker.status = 'OPEN')
               AND NOT EXISTS (
                   SELECT 1 FROM stage_steering_request_v257 steering
                    WHERE steering.task_id = task.id AND steering.status = 'PENDING')
               AND NOT EXISTS (
                   SELECT 1 FROM stage_resume_rearm_intent_v257 resume
                    WHERE resume.task_id = task.id AND resume.status = 'PENDING')
               AND (live.active_task_turn_count
                  + live.active_stage_turn_count
                  + live.active_review_turn_count
                  + live.active_plan_review_count
                  + live.active_validation_count
                  + live.active_brain_episode_count
                  + live.active_provision_operation_count
                  + live.active_dispatch_count
                  + live.active_agent_execution_count
                  + live.unreconciled_execution_count
                  + live.active_quiescence_count
                  + live.active_replan_count
                  + live.active_feedback_batch_count
                  + live.active_publish_operation_count
                  + live.unreconciled_publish_operation_count
                  + live.active_publish_effect_count
                  + live.active_publish_authorization_count
                  + live.open_permission_count
                  + live.accepted_terminal_intent_count
                  + live.open_cleanup_stage_count) = 0
               AND (all_epoch.active_task_turn_count
                  + all_epoch.active_stage_turn_count
                  + all_epoch.active_review_turn_count
                  + all_epoch.active_dispatch_count
                  + all_epoch.active_agent_execution_count) = 0
               AND NOT EXISTS (
                   SELECT 1 FROM capacity_lease lease
                    WHERE lease.workflow_source = 'V2'
                      AND lease.task_id = task.id
                      AND lease.released_at_ms IS NULL
                      AND lease.expires_at_ms > ?)
               AND NOT EXISTS (
                   SELECT 1 FROM worktree_leases lease
                    WHERE lease.workflow_version = 'V2'
                      AND lease.task_id = task.id
                      AND lease.expires_at_ms > ?)
               AND latest_activity.occurred_at_ms < ?
            """;

    private static final String RECEIPT_SELECT = """
            SELECT * FROM task_command_receipt
            """;

    private static final String RECEIPT_INSERT = """
            INSERT INTO task_command_receipt(
                id, task_id, command_id, cause, actor, disposition,
                expected_task_epoch, expected_task_version,
                subject_task_epoch, subject_stage_id, subject_stage_generation,
                subject_operation_id, subject_attempt,
                subject_expected_code_fingerprint, subject_expected_head_sha,
                subject_expected_base_sha, brain_verdict, proof_id,
                next_stage_id, next_stage_kind, next_stage_generation,
                returned_trunk_id, returned_lifecycle, returned_epoch,
                returned_version, returned_current_stage_id,
                returned_pending_task_epoch, returned_pending_stage_id,
                returned_pending_stage_generation, returned_pending_operation_id,
                returned_pending_attempt, returned_pending_code_fingerprint,
                returned_pending_head_sha, returned_pending_base_sha,
                returned_last_brain_verdict, returned_last_brain_task_epoch,
                returned_last_brain_stage_id, returned_last_brain_stage_generation,
                returned_last_brain_operation_id, returned_last_brain_attempt,
                returned_last_brain_code_fingerprint, returned_last_brain_head_sha,
                returned_last_brain_base_sha, returned_terminal_intent, recorded_at_ms)
            VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String BRAIN_RECEIPT_INSERT = RECEIPT_INSERT.replace(
            "INSERT INTO task_command_receipt(",
            "INSERT INTO task_brain_request_receipt(");

    private static final String BRAIN_BUDGET_RECEIPT_INSERT = RECEIPT_INSERT.replace(
            "INSERT INTO task_command_receipt(",
            "INSERT INTO task_brain_budget_receipt(");

    private static final String REMOTE_BRAIN_RECEIPT_INSERT = RECEIPT_INSERT.replace(
            "INSERT INTO task_command_receipt(",
            "INSERT INTO remote_task_brain_receipt(");

    private final JdbcTemplate jdbc;

    V2TaskStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<TaskManager.State> findById(String taskId)
    {
        Optional<BaseState> base = jdbc.query("""
                SELECT task.id, task.thread_id, task.lifecycle_state, task.epoch,
                       task.aggregate_version, current.stage_id,
                       terminal.kind AS terminal_intent
                FROM tasks task
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN task_terminal_intent terminal
                  ON terminal.task_id = task.id AND terminal.accepted = 1
                WHERE task.id = ? AND task.workflow_version = 'V2'
                """,
                (rs, row) -> new BaseState(
                        rs.getString("id"),
                        rs.getString("thread_id"),
                        TaskLifecycle.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("epoch"),
                        rs.getLong("aggregate_version"),
                        rs.getString("stage_id"),
                        terminal(rs.getString("terminal_intent"))),
                taskId).stream().findFirst();
        if (base.isEmpty()) {
            return Optional.empty();
        }

        BaseState persisted = base.orElseThrow();
        Optional<TaskManager.CommandReceipt> projection = findProjection(
                taskId, persisted.version());
        if (projection.isEmpty()) {
            return Optional.of(persisted.withProtocolState(null, null, null));
        }

        TaskManager.State snapshot = projection.orElseThrow().state();
        if (!persisted.matchesCore(snapshot)) {
            throw new DataIntegrityViolationException(
                    "Task receipt projection disagrees with Task row: " + taskId);
        }
        return Optional.of(snapshot);
    }

    @Override
    public List<String> findIdleArchiveCandidates(
            Instant cutoff, Instant observedAt, int limit)
    {
        requireNonNull(cutoff, "cutoff is null");
        requireNonNull(observedAt, "observedAt is null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query(
                IDLE_ARCHIVE_ELIGIBILITY
                        + " ORDER BY latest_activity.occurred_at_ms, task.id LIMIT ?",
                (rs, row) -> rs.getString("id"),
                observedAt.toEpochMilli(), observedAt.toEpochMilli(),
                cutoff.toEpochMilli(), limit);
    }

    @Override
    public boolean isIdleForArchive(
            String taskId, Instant cutoff, Instant observedAt)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(cutoff, "cutoff is null");
        requireNonNull(observedAt, "observedAt is null");
        return !jdbc.query(
                IDLE_ARCHIVE_ELIGIBILITY + " AND task.id = ?",
                (rs, row) -> rs.getString("id"),
                observedAt.toEpochMilli(), observedAt.toEpochMilli(),
                cutoff.toEpochMilli(), taskId).isEmpty();
    }

    @Override
    public Optional<TaskManager.PolicyRevision> findPolicy(String taskId)
    {
        return jdbc.query("""
                SELECT policy.id, policy.trunk_id, policy.revision,
                       policy.auto_approve, policy.auto_merge,
                       policy.min_approvals, policy.max_brain_rounds,
                       policy.max_ci_fix_pushes,
                       policy.require_remote_branch_cleanup,
                       policy.permission_policy_ref
                FROM tasks task
                JOIN task_policy_revision policy
                  ON policy.id = task.policy_revision_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                """, (rs, row) -> policy(rs, taskId), taskId)
                .stream().findFirst();
    }

    @Override
    public Optional<TaskManager.PolicyCommandReceipt> findPolicyCommand(
            String taskId, String commandId)
    {
        if (!tableAvailable("task_policy_command_receipt")) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT receipt.command_id, receipt.actor,
                       receipt.expected_task_epoch,
                       receipt.expected_task_version,
                       receipt.previous_policy_revision_id,
                       policy.id, policy.trunk_id, policy.revision,
                       policy.auto_approve, policy.auto_merge,
                       policy.min_approvals, policy.max_brain_rounds,
                       policy.max_ci_fix_pushes,
                       policy.require_remote_branch_cleanup,
                       policy.permission_policy_ref
                FROM task_policy_command_receipt receipt
                JOIN task_policy_revision policy
                  ON policy.id = receipt.selected_policy_revision_id
                WHERE receipt.task_id = ? AND receipt.command_id = ?
                """, (rs, row) -> new TaskManager.PolicyCommandReceipt(
                        rs.getString("command_id"), rs.getString("actor"),
                        rs.getLong("expected_task_epoch"),
                        rs.getLong("expected_task_version"),
                        rs.getString("previous_policy_revision_id"),
                        policy(rs, taskId)), taskId, commandId)
                .stream().findFirst();
    }

    @Override
    public boolean hasPolicyCommandReceipt(String taskId, String commandId)
    {
        if (!tableAvailable("task_policy_command_receipt")) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_policy_command_receipt
                WHERE task_id = ? AND command_id = ?
                """, Integer.class, taskId, commandId);
        return count != null && count == 1;
    }

    @Override
    public TaskManager.PolicyRevision revisePolicy(
            TaskManager.PolicyCommand command,
            TaskManager.PolicyRevision previous,
            TaskManager.State expected,
            TaskManager.State updated)
    {
        requireTransaction();
        validateCommit(
                command.task().expectedEpoch(), command.task().expectedVersion(),
                expected, updated);
        if (!previous.id().equals(findPolicy(expected.id())
                .map(TaskManager.PolicyRevision::id).orElse(null))) {
            throw concurrent("Task policy changed before revision: " + expected.id());
        }
        long now = System.currentTimeMillis();
        int inserted = jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve, auto_merge,
                    min_approvals, max_brain_rounds, max_ci_fix_pushes,
                    require_remote_branch_cleanup, permission_policy_ref,
                    created_by, created_at_ms)
                SELECT ?, task.thread_id,
                       COALESCE((SELECT MAX(policy.revision) + 1
                           FROM task_policy_revision policy
                           WHERE policy.trunk_id = task.thread_id), 1),
                       'TASK_POLICY_COMMAND', ?, ?, ?, ?, ?, ?, ?, ?, ?
                FROM tasks task
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.thread_id = ? AND task.policy_revision_id = ?
                  AND task.lifecycle_state = ? AND task.epoch = ?
                  AND task.aggregate_version = ?
                """,
                command.policyId(), command.autoApprove() ? 1 : 0,
                command.autoMerge() ? 1 : 0, command.minApprovals(),
                command.maxBrainRounds(), command.maxCiFixPushes(),
                command.requireRemoteBranchCleanup() ? 1 : 0,
                command.permissionPolicyRef(), command.task().actor(), now,
                expected.id(), expected.trunkId(), previous.id(),
                expected.lifecycle().name(), expected.epoch(), expected.version());
        if (inserted != 1) {
            throw concurrent("Task changed before policy revision: " + expected.id());
        }

        String intentId = preparePolicySelection(
                command, previous, expected, now);
        int changed = jdbc.update("""
                UPDATE tasks
                SET policy_revision_id = ?, aggregate_version = ?
                WHERE id = ? AND workflow_version = 'V2' AND thread_id = ?
                  AND policy_revision_id = ? AND lifecycle_state = ?
                  AND epoch = ? AND aggregate_version = ?
                """,
                command.policyId(), updated.version(), expected.id(),
                expected.trunkId(), previous.id(), expected.lifecycle().name(),
                expected.epoch(), expected.version());
        if (changed != 1) {
            throw concurrent("Task changed before policy selection: " + expected.id());
        }

        appendAutomationPolicy(command, now);
        recordTransition(
                command.task().commandId(), "REVISE_POLICY",
                command.task().actor(), expected, updated);
        jdbc.update("""
                INSERT INTO task_policy_command_receipt(
                    id, intent_id, task_id, command_id, actor,
                    expected_task_epoch, expected_task_version,
                    previous_policy_revision_id, selected_policy_revision_id,
                    returned_task_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), intentId, expected.id(), command.task().commandId(),
                command.task().actor(), command.task().expectedEpoch(),
                command.task().expectedVersion(), previous.id(),
                command.policyId(), updated.version(), now);
        return findPolicy(expected.id())
                .orElseThrow(() -> new DataIntegrityViolationException(
                        "Selected Task policy is missing: " + command.policyId()));
    }

    private String preparePolicySelection(
            TaskManager.PolicyCommand command,
            TaskManager.PolicyRevision previous,
            TaskManager.State expected,
            long now)
    {
        String intentId = id();
        jdbc.update("""
                INSERT INTO task_policy_command_intent(
                    id, task_id, command_id, actor,
                    expected_task_epoch, expected_task_version,
                    previous_policy_revision_id, selected_policy_revision_id,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                intentId, expected.id(), command.task().commandId(),
                command.task().actor(), command.task().expectedEpoch(),
                command.task().expectedVersion(), previous.id(),
                command.policyId(), now);
        return intentId;
    }

    private void appendAutomationPolicy(
            TaskManager.PolicyCommand command, long now)
    {
        jdbc.update("""
                INSERT INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                SELECT ?, current.task_id, current.revision + 1,
                       'TASK_POLICY_COMMAND', ?, ?, current.keep_draft, ?,
                       current.max_merge_queue_reenqueues,
                       current.require_low_risk, current.require_small_effort,
                       CASE WHEN ? = 1 OR ? = 1
                           THEN 0 ELSE current.stewardship_exception END, ?, ?
                FROM task_automation_policy current
                WHERE current.id = (
                    SELECT latest.id FROM task_automation_policy latest
                    WHERE latest.task_id = ?
                    ORDER BY latest.revision DESC LIMIT 1)
                """,
                id(), command.autoApprove() ? 1 : 0,
                command.autoMerge() ? 1 : 0, command.minApprovals(),
                command.autoApprove() ? 1 : 0,
                command.autoMerge() ? 1 : 0,
                command.task().actor(), now, command.task().taskId());
    }

    private static TaskManager.PolicyRevision policy(
            ResultSet rs, String taskId)
            throws SQLException
    {
        return new TaskManager.PolicyRevision(
                rs.getString("id"), taskId, rs.getString("trunk_id"),
                rs.getInt("revision"), rs.getBoolean("auto_approve"),
                rs.getBoolean("auto_merge"), rs.getInt("min_approvals"),
                rs.getInt("max_brain_rounds"),
                rs.getInt("max_ci_fix_pushes"),
                rs.getBoolean("require_remote_branch_cleanup"),
                rs.getString("permission_policy_ref"));
    }

    @Override
    public Optional<TaskManager.CommandReceipt> findCommandResult(
            String taskId, String commandId)
    {
        Optional<TaskManager.CommandReceipt> shared = queryReceipt(
                RECEIPT_SELECT + " WHERE task_id = ? AND command_id = ?",
                taskId, commandId);
        if (shared.isPresent()) {
            return shared;
        }
        if (tableAvailable("task_brain_request_receipt")) {
            Optional<TaskManager.CommandReceipt> request = queryReceipt(
                    "SELECT * FROM task_brain_request_receipt"
                            + " WHERE task_id = ? AND command_id = ?",
                    taskId, commandId);
            if (request.isPresent()) {
                return request;
            }
        }
        if (tableAvailable("task_brain_budget_receipt")) {
            Optional<TaskManager.CommandReceipt> budget = queryReceipt(
                    "SELECT * FROM task_brain_budget_receipt"
                            + " WHERE task_id = ? AND command_id = ?",
                    taskId, commandId);
            if (budget.isPresent()) {
                return budget;
            }
        }
        if (tableAvailable("remote_task_brain_receipt")) {
            return queryReceipt(
                    "SELECT * FROM remote_task_brain_receipt"
                            + " WHERE task_id = ? AND command_id = ?",
                    taskId, commandId);
        }
        return Optional.empty();
    }

    @Override
    public boolean matchesRepositoryRoot(
            TaskCreationInput input, Path repositoryRoot)
    {
        List<String> roots = jdbc.query("""
                SELECT watched.local_clone_path
                FROM workspace_repos repository
                JOIN watched_repos watched
                  ON lower(watched.owner || '/' || watched.repo)
                    = lower(repository.repo_full_name)
                WHERE repository.workspace_id = ?
                  AND lower(repository.repo_full_name) = lower(?)
                  AND watched.local_clone_path IS NOT NULL
                  AND length(trim(watched.local_clone_path)) > 0
                """,
                (rs, row) -> rs.getString("local_clone_path"),
                input.workspaceId(), input.base().repositories().repositoryId());
        if (roots.size() != 1) {
            return false;
        }
        Path persisted = Path.of(roots.getFirst());
        return persisted.isAbsolute()
                && persisted.normalize().equals(repositoryRoot);
    }

    @Override
    public long nextTaskSequence(String trunkId)
    {
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(seq), 0) + 1 FROM tasks WHERE thread_id = ?
                """, Long.class, trunkId);
        return requireNonNull(sequence, "next Task sequence is null");
    }

    @Override
    public Optional<TaskManager.TaskCreationReceipt> findTaskCreation(
            String trunkId, String commandId)
    {
        return jdbc.query("""
                SELECT receipt.task_id, receipt.task_seq,
                       receipt.authorization_id, receipt.assignment_id,
                       receipt.policy_revision_id, receipt.task_brain_id,
                       receipt.provision_operation_id,
                       receipt.dispatch_ticket_id, receipt.operation_id,
                       receipt.requested_branch_name,
                       receipt.requested_worktree_path,
                       receipt.returned_lifecycle,
                       receipt.task_epoch, receipt.task_version
                FROM task_creation_receipt receipt
                JOIN tasks task ON task.id = receipt.task_id
                WHERE receipt.trunk_id = ? AND receipt.command_id = ?
                """,
                (rs, row) -> new TaskManager.TaskCreationReceipt(
                        new TaskManager.State(
                                rs.getString("task_id"), trunkId,
                                TaskLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("task_epoch"),
                                rs.getLong("task_version"),
                                null, null, null, null, null),
                        rs.getLong("task_seq"),
                        rs.getString("authorization_id"),
                        rs.getString("assignment_id"),
                        rs.getString("policy_revision_id"),
                        rs.getString("task_brain_id"),
                        rs.getString("provision_operation_id"),
                        rs.getString("dispatch_ticket_id"),
                        rs.getString("operation_id"),
                        rs.getString("requested_branch_name"),
                        rs.getString("requested_worktree_path")),
                trunkId, commandId).stream().findFirst();
    }

    @Override
    public boolean hasTaskCreationReceipt(String taskId, String commandId)
    {
        if (!taskCreationProtocolExists()) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_creation_receipt
                WHERE task_id = ? AND command_id = ?
                """, Integer.class, taskId, commandId);
        return count != null && count == 1;
    }

    private boolean taskCreationProtocolExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'task_creation_receipt'
                """, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public boolean matchesTaskCreation(
            TrunkManager.TaskCreationCommand command,
            TrunkManager.AuthorizedTaskCreation authorization,
            TaskManager.TaskCreationReceipt receipt,
            ProvisionTarget target)
    {
        TaskCreationInput input = command.input();
        String taskId = receipt.state().id();
        long createdAt = input.createdAt().toEpochMilli();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT task.thread_id AS task_trunk_id,
                       task.seq AS task_seq,
                       task.workflow_version AS workflow_version,
                       task.assignment_id AS task_assignment_id,
                       task.policy_revision_id AS task_policy_id,
                       task.creation_receipt_id AS task_receipt_id,
                       task.created_at_ms AS task_created_at_ms,
                       task.name AS task_name,
                       task.task_type AS task_type,
                       task.linked_pr_number AS task_linked_pr_number,
                       task.linked_issue_number AS task_linked_issue_number,
                       task.opening_prompt AS task_opening_prompt,
                       task.origin AS task_origin,
                       context.authorization_id AS context_authorization_id,
                       context.provenance AS context_provenance,
                       context.repository_id AS context_repository_id,
                       context.upstream_repository_id AS context_upstream_repository_id,
                       context.publish_repository_id AS context_publish_repository_id,
                       context.base_source AS context_base_source,
                       context.base_repository_id AS context_base_repository_id,
                       context.base_ref AS context_base_ref,
                       context.planning_base_sha AS context_planning_base_sha,
                       context.assignment_base_sha AS context_assignment_base_sha,
                       context.assignment_head_sha AS context_assignment_head_sha,
                       context.engine_snapshot AS context_engine_snapshot,
                       context.work_model_snapshot AS context_work_model_snapshot,
                       context.task_name AS context_task_name,
                       context.task_type AS context_task_type,
                       context.linked_issue_number AS context_linked_issue_number,
                       context.opening_prompt AS context_opening_prompt,
                       context.task_origin AS context_task_origin,
                       context.created_at_ms AS context_created_at_ms,
                       brain.provider AS brain_provider,
                       brain.model AS brain_model,
                       brain.role_skill AS brain_role_skill,
                       brain.engine_snapshot AS brain_engine_snapshot,
                       brain.created_at_ms AS brain_created_at_ms,
                       target.repository_id AS target_repository_id,
                       target.publish_repository_id AS target_publish_repository_id,
                       target.branch_name AS target_branch_name,
                       target.worktree_path AS target_worktree_path,
                       target.created_at_ms AS target_created_at_ms,
                       operation.task_epoch AS operation_task_epoch,
                       operation.assignment_id AS operation_assignment_id,
                       operation.operation_id AS operation_id,
                       operation.semantic_attempt AS semantic_attempt,
                       operation.repository_id AS operation_repository_id,
                       operation.expected_base_sha AS expected_base_sha,
                       operation.expected_remote_head_sha AS expected_head_sha,
                       operation.requested_branch_name AS operation_branch_name,
                       operation.requested_worktree_path AS operation_worktree_path,
                       operation.created_at_ms AS operation_created_at_ms,
                       operation.base_source AS operation_base_source,
                       operation.base_repository_id AS operation_base_repository_id,
                       operation.base_ref AS operation_base_ref,
                       ticket.operation_kind AS operation_kind,
                       ticket.async_family AS async_family,
                       ticket.owner_kind AS owner_kind,
                       ticket.owner_id AS owner_id,
                       ticket.callback_route AS callback_route,
                       ticket.lane_mask AS lane_mask,
                       ticket.exclusive_task AS exclusive_task,
                       ticket.writer_required AS writer_required,
                       ticket.workspace_id AS ticket_workspace_id,
                       ticket.trunk_id AS ticket_trunk_id,
                       ticket.task_id AS ticket_task_id,
                       ticket.task_epoch AS ticket_task_epoch,
                       ticket.stage_id AS ticket_stage_id,
                       ticket.stage_generation AS ticket_stage_generation,
                       ticket.attempt AS ticket_attempt,
                       ticket.expected_code_fingerprint AS ticket_fingerprint,
                       ticket.expected_head_sha AS ticket_expected_head_sha,
                       ticket.expected_base_sha AS ticket_expected_base_sha,
                       ticket.created_at_ms AS ticket_created_at_ms,
                       wake.id AS wake_id, wake.dedup_key AS wake_dedup_key,
                       wake.aggregate_kind AS wake_aggregate_kind,
                       wake.aggregate_id AS wake_aggregate_id,
                       wake.topic AS wake_topic, wake.payload AS wake_payload,
                       wake.created_at_ms AS wake_created_at_ms,
                       transition.command_id AS transition_command_id,
                       transition.epoch AS transition_epoch,
                       transition.from_state AS transition_from_state,
                       transition.to_state AS transition_to_state,
                       transition.aggregate_version AS transition_version,
                       transition.cause AS transition_cause,
                       transition.actor AS transition_actor,
                       transition.occurred_at_ms AS transition_occurred_at_ms,
                       receipt.id AS receipt_id,
                       receipt.actor AS receipt_actor,
                       receipt.recorded_at_ms AS receipt_recorded_at_ms
                FROM tasks task
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_provision_target target ON target.task_id = task.id
                JOIN task_creation_receipt receipt ON receipt.task_id = task.id
                JOIN provision_task_operation operation
                  ON operation.id = receipt.provision_operation_id
                JOIN dispatch_ticket ticket
                  ON ticket.id = receipt.dispatch_ticket_id
                JOIN outbox wake ON wake.aggregate_id = ticket.id
                  AND wake.aggregate_kind = 'DISPATCH_TICKET'
                JOIN task_transition transition ON transition.task_id = task.id
                  AND transition.command_id = receipt.command_id
                WHERE task.id = ? AND receipt.trunk_id = ?
                  AND receipt.command_id = ?
                """, taskId, input.assignment().identity().trunkId(),
                command.commandId());
        if (rows.size() != 1) {
            return false;
        }
        Map<String, Object> row = rows.getFirst();
        String wakeId = "V2_DISPATCH_TICKET_REQUESTED:" + receipt.dispatchTicketId();
        return authorization.disposition() == CommandResult.Disposition.DUPLICATE
                && receipt.state().lifecycle() == TaskLifecycle.PROVISIONING
                && receipt.state().epoch() == 1
                && receipt.state().version() == 0
                && receipt.taskSequence() > 0
                && receipt.authorizationId().equals(
                        input.assignment().identity().creationAuthorizationId())
                && receipt.assignmentId().equals(input.assignment().identity().id())
                && receipt.policyRevisionId().equals(input.policy().id())
                && receipt.branchName().equals(target.branchName())
                && receipt.worktreePath().equals(target.worktreePath().toString())
                && same(row, "task_trunk_id",
                        input.assignment().identity().trunkId())
                && number(row, "task_seq") == receipt.taskSequence()
                && same(row, "workflow_version", "V2")
                && same(row, "task_assignment_id", receipt.assignmentId())
                && same(row, "task_policy_id", receipt.policyRevisionId())
                && Objects.equals(row.get("task_receipt_id"), row.get("receipt_id"))
                && number(row, "task_created_at_ms") == createdAt
                && same(row, "task_name", input.presentation().name())
                && same(row, "task_type", input.presentation().taskType())
                && Objects.equals(
                        nullableNumber(row, "task_linked_pr_number"),
                        linkedPrNumber(input.assignment()))
                && Objects.equals(
                        nullableNumber(row, "task_linked_issue_number"),
                        nullableLong(input.presentation().linkedIssueNumber()))
                && same(row, "task_opening_prompt", input.presentation().openingPrompt())
                && same(row, "task_origin", input.presentation().origin())
                && same(row, "context_authorization_id", receipt.authorizationId())
                && same(row, "context_provenance", input.provenance().name())
                && same(row, "context_repository_id", target.repositoryId())
                && same(row, "context_upstream_repository_id",
                        input.base().repositories().upstreamRepositoryId().orElse(null))
                && same(row, "context_publish_repository_id",
                        target.publishRepositoryId())
                && same(row, "context_base_source", input.base().source().name())
                && same(row, "context_base_repository_id",
                        input.base().repositories().baseRepositoryId())
                && same(row, "context_base_ref", input.base().baseRef())
                && same(row, "context_planning_base_sha", planningBase(input))
                && same(row, "context_assignment_base_sha", assignmentBase(input))
                && same(row, "context_assignment_head_sha", assignmentHead(input))
                && same(row, "context_engine_snapshot",
                        input.engine().canonicalValue())
                && same(row, "context_work_model_snapshot", input.workModel().value())
                && same(row, "context_task_name", input.presentation().name())
                && same(row, "context_task_type", input.presentation().taskType())
                && Objects.equals(
                        nullableNumber(row, "context_linked_issue_number"),
                        nullableLong(input.presentation().linkedIssueNumber()))
                && same(row, "context_opening_prompt", input.presentation().openingPrompt())
                && same(row, "context_task_origin", input.presentation().origin())
                && number(row, "context_created_at_ms") == createdAt
                && same(row, "brain_provider", input.engine().provider())
                && same(row, "brain_model", input.engine().model())
                && row.get("brain_role_skill") == null
                && same(row, "brain_engine_snapshot", input.engine().canonicalValue())
                && number(row, "brain_created_at_ms") == createdAt
                && same(row, "target_repository_id", target.repositoryId())
                && same(row, "target_publish_repository_id",
                        target.publishRepositoryId())
                && same(row, "target_branch_name", target.branchName())
                && same(row, "target_worktree_path", target.worktreePath().toString())
                && number(row, "target_created_at_ms") == createdAt
                && number(row, "operation_task_epoch") == 1
                && same(row, "operation_assignment_id", receipt.assignmentId())
                && same(row, "operation_id", receipt.operationId())
                && number(row, "semantic_attempt") == 1
                && same(row, "operation_repository_id", target.repositoryId())
                && same(row, "expected_base_sha", expectedBase(input))
                && same(row, "expected_head_sha", expectedHead(input))
                && same(row, "operation_branch_name", target.branchName())
                && same(row, "operation_worktree_path",
                        target.worktreePath().toString())
                && number(row, "operation_created_at_ms") == createdAt
                && same(row, "operation_base_source", input.base().source().name())
                && same(row, "operation_base_repository_id",
                        input.base().repositories().baseRepositoryId())
                && same(row, "operation_base_ref", input.base().baseRef())
                && same(row, "operation_kind", "PROVISION_TASK")
                && same(row, "async_family", "LOCAL_GIT")
                && same(row, "owner_kind", "TASK")
                && same(row, "owner_id", taskId)
                && same(row, "callback_route", "TASK_PROVISION_RESULT")
                && number(row, "lane_mask") == 16
                && number(row, "exclusive_task") == 1
                && number(row, "writer_required") == 1
                && same(row, "ticket_workspace_id", input.workspaceId())
                && same(row, "ticket_trunk_id",
                        input.assignment().identity().trunkId())
                && same(row, "ticket_task_id", taskId)
                && number(row, "ticket_task_epoch") == 1
                && row.get("ticket_stage_id") == null
                && row.get("ticket_stage_generation") == null
                && number(row, "ticket_attempt") == 1
                && row.get("ticket_fingerprint") == null
                && same(row, "ticket_expected_head_sha", expectedHead(input))
                && same(row, "ticket_expected_base_sha", expectedBase(input))
                && number(row, "ticket_created_at_ms") == createdAt
                && same(row, "wake_id", wakeId)
                && same(row, "wake_dedup_key", wakeId)
                && same(row, "wake_aggregate_kind", "DISPATCH_TICKET")
                && same(row, "wake_aggregate_id", receipt.dispatchTicketId())
                && same(row, "wake_topic", "V2_DISPATCH_TICKET_REQUESTED")
                && same(row, "wake_payload", receipt.dispatchTicketId())
                && number(row, "wake_created_at_ms") == createdAt
                && same(row, "transition_command_id", command.commandId())
                && number(row, "transition_epoch") == 1
                && row.get("transition_from_state") == null
                && same(row, "transition_to_state", "PROVISIONING")
                && number(row, "transition_version") == 0
                && same(row, "transition_cause", "CREATE_TASK")
                && same(row, "transition_actor", command.actor())
                && number(row, "transition_occurred_at_ms") == createdAt
                && same(row, "receipt_actor", command.actor())
                && number(row, "receipt_recorded_at_ms") == createdAt;
    }

    @Override
    public TaskManager.TaskCreationReceipt createTask(
            TrunkManager.TaskCreationCommand command,
            TrunkManager.AuthorizedTaskCreation authorization,
            TaskManager.State state,
            long taskSequence,
            ProvisionTarget target)
    {
        requireTransaction();
        TaskCreationInput input = command.input();
        if (!state.trunkId().equals(
                        input.assignment().identity().trunkId())
                || state.lifecycle() != TaskLifecycle.PROVISIONING
                || state.epoch() != 1 || state.version() != 0
                || state.currentStageId() != null
                || taskSequence < 1
                || !state.id().equals(target.taskId())) {
            throw new IllegalArgumentException("Initial Task creation fence is invalid");
        }

        String receiptId = id();
        String brainId = id();
        String provisionId = id();
        String operationId = id();
        String ticketId = id();
        long createdAt = input.createdAt().toEpochMilli();
        String assignmentId = input.assignment().identity().id();
        String authorizationId = input.assignment().identity()
                .creationAuthorizationId();

        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id, creation_receipt_id,
                    name, task_type, linked_pr_number, linked_pr_ref,
                    linked_issue_number, opening_prompt, origin)
                VALUES (?, ?, ?, 'PENDING', 'PLANNING', ?,
                    'V2', 1, 0, 'PROVISIONING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                state.id(), state.trunkId(), taskSequence, createdAt,
                assignmentId, input.policy().id(), receiptId,
                input.presentation().name(), input.presentation().taskType(),
                linkedPrNumber(input.assignment()),
                linkedPrRef(input.assignment()),
                input.presentation().linkedIssueNumber(),
                input.presentation().openingPrompt(), input.presentation().origin());
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, authorization_id,
                    provenance, repository_id, upstream_repository_id,
                    publish_repository_id, base_source, base_repository_id,
                    base_ref, planning_base_sha, assignment_base_sha,
                    assignment_head_sha, engine_snapshot, work_model_snapshot,
                    task_name, task_type, linked_issue_number, opening_prompt,
                    task_origin,
                    created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                state.id(), assignmentId, input.policy().id(), authorizationId,
                input.provenance().name(), target.repositoryId(),
                input.base().repositories().upstreamRepositoryId().orElse(null),
                target.publishRepositoryId(), input.base().source().name(),
                input.base().repositories().baseRepositoryId(), input.base().baseRef(),
                planningBase(input), assignmentBase(input), assignmentHead(input),
                input.engine().canonicalValue(), input.workModel().value(),
                input.presentation().name(), input.presentation().taskType(),
                input.presentation().linkedIssueNumber(),
                input.presentation().openingPrompt(), input.presentation().origin(),
                createdAt);
        jdbc.update("""
                INSERT INTO task_brain(
                    id, task_id, provider, model, role_skill,
                    engine_snapshot, created_at_ms)
                VALUES (?, ?, ?, ?, NULL, ?, ?)
                """,
                brainId, state.id(), input.engine().provider(), input.engine().model(),
                input.engine().canonicalValue(), createdAt);
        jdbc.update("""
                INSERT INTO task_provision_target(
                    task_id, repository_id, publish_repository_id,
                    branch_name, worktree_path, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                state.id(), target.repositoryId(), target.publishRepositoryId(),
                target.branchName(), target.worktreePath().toString(), createdAt);
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    expected_remote_head_sha, requested_branch_name,
                    requested_worktree_path, status, created_at_ms,
                    base_source, base_repository_id, base_ref)
                VALUES (?, ?, 1, ?, ?, 1, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?)
                """,
                provisionId, state.id(), assignmentId, operationId,
                target.repositoryId(), expectedBase(input), expectedHead(input),
                target.branchName(), target.worktreePath().toString(), createdAt,
                input.base().source().name(),
                input.base().repositories().baseRepositoryId(), input.base().baseRef());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'PROVISION_TASK', 'LOCAL_GIT', 'TASK', ?,
                    'TASK_PROVISION_RESULT', 16, 1, 1, ?, ?, ?, 1, 1, ?, ?,
                    'REQUESTED', ?)
                """,
                ticketId, operationId, state.id(), input.workspaceId(),
                state.trunkId(), state.id(), expectedHead(input), expectedBase(input),
                createdAt);
        jdbc.update("""
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, 1, NULL, 'PROVISIONING', 0,
                    'CREATE_TASK', ?, ?)
                """,
                id(), state.id(), command.commandId(), command.actor(), createdAt);
        jdbc.update("""
                INSERT INTO task_creation_receipt(
                    id, trunk_id, workspace_id, command_id, actor,
                    authorization_id, task_id, task_seq, task_epoch,
                    task_version, returned_lifecycle, assignment_id,
                    policy_revision_id, task_brain_id, provision_operation_id,
                    dispatch_ticket_id, operation_id, semantic_attempt,
                    requested_branch_name, requested_worktree_path, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 'PROVISIONING', ?, ?, ?,
                    ?, ?, ?, 1, ?, ?, ?)
                """,
                receiptId, state.trunkId(), input.workspaceId(), command.commandId(),
                command.actor(), authorizationId, state.id(), taskSequence,
                assignmentId, input.policy().id(), brainId, provisionId, ticketId,
                operationId, target.branchName(), target.worktreePath().toString(),
                createdAt);
        int dispatched = jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'DISPATCHED'
                WHERE id = ? AND task_id = ? AND status = 'REQUESTED'
                """, provisionId, state.id());
        if (dispatched != 1) {
            throw concurrent("Provision operation changed during Task creation");
        }
        return new TaskManager.TaskCreationReceipt(
                state, taskSequence, authorizationId, assignmentId,
                input.policy().id(), brainId, provisionId, ticketId, operationId,
                target.branchName(), target.worktreePath().toString());
    }

    @Override
    public Optional<TaskManager.ProvisioningResult> findAcceptedProvisioningResult(
            String taskId, String operationId)
    {
        return jdbc.query("""
                SELECT task_id, task_epoch, operation_id, semantic_attempt,
                       result_base_sha, result_head_sha, result_code_fingerprint
                FROM provision_task_operation
                WHERE task_id = ? AND operation_id = ? AND status = 'ACCEPTED'
                """,
                (rs, row) -> new TaskManager.ProvisioningResult(
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("result_base_sha"),
                        rs.getString("result_head_sha"),
                        rs.getString("result_code_fingerprint")),
                taskId, operationId).stream().findFirst();
    }

    @Override
    public TaskManager.ProvisioningResult acceptProvisioningResult(
            TaskManager.ProvisionedCode code)
    {
        requireTransaction();
        long now = System.currentTimeMillis();
        int accepted = jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = ?, result_head_sha = ?,
                    result_code_fingerprint = ?, result_evidence = ?,
                    completed_at_ms = ?, error_message = NULL
                WHERE task_id = ? AND task_epoch = ? AND operation_id = ?
                  AND semantic_attempt = ? AND repository_id = ?
                  AND requested_branch_name = ? AND requested_worktree_path = ?
                  AND status = 'DISPATCHED'
                """,
                code.baseSha(), code.headSha(), code.codeFingerprint(),
                code.evidenceJson(), now, code.taskId(), code.taskEpoch(),
                code.operationId(), code.attempt(), code.repositoryId(),
                code.branchName(), code.worktreePath());
        if (accepted != 1) {
            throw concurrent("Provisioning operation changed before acceptance");
        }
        int inserted = jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    upstream_repository_id, publish_repository_id, branch_name,
                    worktree_path, base_sha, local_head_sha, code_fingerprint,
                    version, created_at_ms, updated_at_ms)
                SELECT operation.task_id, operation.id, context.repository_id,
                    context.upstream_repository_id, context.publish_repository_id,
                    operation.requested_branch_name,
                    operation.requested_worktree_path,
                    operation.result_base_sha, operation.result_head_sha,
                    operation.result_code_fingerprint, 0, ?, ?
                FROM provision_task_operation operation
                JOIN task_creation_context context
                  ON context.task_id = operation.task_id
                WHERE operation.task_id = ? AND operation.task_epoch = ?
                  AND operation.operation_id = ?
                  AND operation.semantic_attempt = ?
                  AND operation.status = 'ACCEPTED'
                  AND operation.result_evidence = ?
                """,
                now, now, code.taskId(), code.taskEpoch(), code.operationId(),
                code.attempt(), code.evidenceJson());
        if (inserted != 1) {
            throw concurrent("Task code identity changed before acceptance");
        }
        return findAcceptedProvisioningResult(code.taskId(), code.operationId())
                .orElseThrow(() -> concurrent(
                        "Accepted provisioning result disappeared"));
    }

    @Override
    public Optional<TaskManager.ProvisioningFailureResult> findProvisioningFailure(
            String taskId, String operationId)
    {
        return jdbc.query("""
                SELECT receipt.task_id, receipt.task_epoch,
                    receipt.operation_id, operation.semantic_attempt,
                    receipt.raw_outcome, receipt.raw_digest,
                    receipt.error_message, receipt.blocker_id,
                    receipt.recorded_at_ms
                FROM task_provision_failure_receipt receipt
                JOIN provision_task_operation operation
                  ON operation.operation_id = receipt.operation_id
                WHERE receipt.task_id = ? AND receipt.operation_id = ?
                """, (rs, row) -> new TaskManager.ProvisioningFailureResult(
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("operation_id"), rs.getInt("semantic_attempt"),
                        rs.getString("raw_outcome"), rs.getString("raw_digest"),
                        rs.getString("error_message"), rs.getString("blocker_id"),
                        rs.getLong("recorded_at_ms")), taskId, operationId)
                .stream().findFirst();
    }

    @Override
    public TaskManager.ProvisioningFailureResult acceptProvisioningFailure(
            TaskManager.ProvisioningFailure failure)
    {
        requireTransaction();
        String operationStatus = "CANCELED".equals(failure.rawOutcome())
                ? "CANCELED" : "FAILED";
        int changed = jdbc.update("""
                UPDATE provision_task_operation
                SET status = ?, completed_at_ms = ?, error_message = ?
                WHERE task_id = ? AND task_epoch = ? AND operation_id = ?
                  AND semantic_attempt = ? AND status = 'DISPATCHED'
                """, operationStatus, failure.recordedAtMillis(),
                failure.errorMessage(), failure.taskId(), failure.taskEpoch(),
                failure.operationId(), failure.attempt());
        if (changed != 1) {
            throw concurrent("Provisioning operation changed before failure acceptance");
        }
        String blockerId = UUID.nameUUIDFromBytes(
                ("bytequay-v2:provision-blocker:" + failure.operationId())
                        .getBytes(StandardCharsets.UTF_8)).toString();
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, owner_kind, owner_id, blocker_type,
                    status, payload_json, opened_at_ms)
                VALUES (?, ?, 'TASK', ?, 'PROVISIONING_FAILED', 'OPEN', ?, ?)
                """, blockerId, failure.taskId(), failure.taskId(),
                "{\"message\":\"" + escape(failure.errorMessage()) + "\"}",
                failure.recordedAtMillis());
        jdbc.update("""
                INSERT INTO task_provision_failure_receipt(
                    operation_id, task_id, task_epoch, raw_outcome, raw_digest,
                    error_message, blocker_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, failure.operationId(), failure.taskId(), failure.taskEpoch(),
                failure.rawOutcome(), failure.rawDigest(), failure.errorMessage(),
                blockerId, failure.recordedAtMillis());
        return findProvisioningFailure(failure.taskId(), failure.operationId())
                .orElseThrow(() -> concurrent(
                        "Accepted provisioning failure disappeared"));
    }

    @Override
    public Optional<TaskManager.ReplanEvidence> findReplanEvidence(
            String taskId, String replanRequestId)
    {
        return jdbc.query("""
                SELECT request.task_id, request.id, request.quiescence_barrier_id,
                       request.source_stage_id, request.source_generation,
                       request.source_task_epoch, request.target_task_epoch,
                       request.command_id, request.requested_by
                FROM task_replan_request request
                JOIN task_quiescence_barrier barrier
                  ON barrier.id = request.quiescence_barrier_id
                WHERE request.task_id = ? AND request.id = ?
                  AND request.status IN ('QUIESCING', 'APPLIED')
                  AND barrier.task_id = request.task_id
                  AND barrier.task_epoch = request.source_task_epoch
                  AND barrier.reason = 'REPLAN' AND barrier.status = 'SATISFIED'
                """,
                (rs, row) -> new TaskManager.ReplanEvidence(
                        rs.getString("task_id"),
                        rs.getString("id"),
                        rs.getString("quiescence_barrier_id"),
                        rs.getString("source_stage_id"),
                        rs.getLong("source_generation"),
                        rs.getLong("source_task_epoch"),
                        rs.getLong("target_task_epoch"),
                        rs.getString("command_id"),
                        rs.getString("requested_by")),
                taskId, replanRequestId).stream().findFirst();
    }

    @Override
    public Optional<TaskManager.QuiescenceEvidence> findSatisfiedQuiescence(
            String taskId, String barrierId)
    {
        return jdbc.query("""
                SELECT task_id, task_epoch, id, reason
                FROM task_quiescence_barrier
                WHERE task_id = ? AND id = ? AND status = 'SATISFIED'
                """,
                (rs, row) -> new TaskManager.QuiescenceEvidence(
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("id"),
                        TaskManager.QuiescenceReason.valueOf(rs.getString("reason"))),
                taskId, barrierId).stream().findFirst();
    }

    @Override
    public Optional<TaskManager.PauseEvidence> findPauseEvidence(
            String taskId, String barrierId)
    {
        return jdbc.query("""
                SELECT evidence.task_id, evidence.task_epoch, evidence.barrier_id,
                       evidence.stage_id, evidence.stage_generation,
                       evidence.restore_checkpoint, evidence.stop_evidence_digest
                FROM task_pause_evidence evidence
                JOIN task_pause_evidence_digest_v230 digest
                  ON digest.barrier_id = evidence.barrier_id
                JOIN task_quiescence_barrier barrier
                  ON barrier.id = evidence.barrier_id
                JOIN tasks task ON task.id = evidence.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                JOIN task_live_work_counts_v230 live ON live.task_id = task.id
                WHERE evidence.task_id = ? AND evidence.barrier_id = ?
                  AND evidence.status = 'SATISFIED'
                  AND barrier.task_id = evidence.task_id
                  AND barrier.task_epoch = evidence.task_epoch
                  AND barrier.reason = 'PAUSE' AND barrier.status = 'SATISFIED'
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'PAUSING'
                  AND task.epoch = evidence.task_epoch
                  AND current.stage_id = evidence.stage_id
                  AND current.stage_generation = evidence.stage_generation
                  AND owner.generation = evidence.stage_generation
                  AND owner.checkpoint = evidence.restore_checkpoint
                  AND owner.completed_at_ms IS NULL
                  AND code.code_fingerprint = evidence.code_fingerprint
                  AND code.head_sha = evidence.head_sha
                  AND code.base_sha = evidence.base_sha
                  AND evidence.stop_evidence_digest = digest.content_digest
                  AND live.task_epoch = evidence.task_epoch
                  AND live.active_task_turn_count = 0
                  AND live.active_stage_turn_count = 0
                  AND live.active_review_turn_count = 0
                  AND live.active_plan_review_count = 0
                  AND live.active_brain_episode_count = 0
                  AND live.active_validation_count = 0
                  AND live.active_provision_operation_count = 0
                  AND live.active_dispatch_count = 0
                  AND live.active_agent_execution_count = 0
                  AND live.unreconciled_execution_count = 0
                  AND live.active_quiescence_count = 0
                  AND live.active_replan_count = 0
                  AND live.active_publish_operation_count = 0
                  AND live.unreconciled_publish_operation_count = 0
                  AND live.active_publish_effect_count = 0
                  AND NOT EXISTS (
                      SELECT 1 FROM capacity_lease lease
                      WHERE lease.workflow_source = 'V2'
                        AND lease.task_id = evidence.task_id
                        AND lease.task_epoch = evidence.task_epoch
                        AND lease.released_at_ms IS NULL
                        AND lease.expires_at_ms > evidence.recorded_at_ms)
                  AND NOT EXISTS (
                      SELECT 1 FROM worktree_leases lease
                      WHERE lease.workflow_version = 'V2'
                        AND lease.task_id = evidence.task_id
                        AND lease.task_epoch = evidence.task_epoch
                        AND lease.expires_at_ms > evidence.recorded_at_ms)
                """,
                (rs, row) -> new TaskManager.PauseEvidence(
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("barrier_id"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        StageCheckpoint.valueOf(rs.getString("restore_checkpoint")),
                        rs.getString("stop_evidence_digest")),
                taskId, barrierId).stream().findFirst();
    }

    @Override
    public Optional<TaskManager.ResumeEvidence> findResumeEvidence(
            String taskId, String reconciliationId)
    {
        if (tableAvailable("task_resume_reconciliation_v256")) {
            Optional<TaskManager.ResumeEvidence> current = jdbc.query("""
                    SELECT evidence.task_id, evidence.task_epoch, evidence.id,
                           evidence.stage_id, evidence.stage_generation,
                           evidence.restore_checkpoint,
                           evidence.reconciliation_digest
                    FROM task_resume_reconciliation_v256 evidence
                    JOIN task_resume_evidence_digest_v256 digest
                      ON digest.id = evidence.id
                    JOIN tasks task ON task.id = evidence.task_id
                    JOIN task_current_stage current ON current.task_id = task.id
                    JOIN stage owner ON owner.id = current.stage_id
                    JOIN task_current_code_subject_v230 code
                      ON code.task_id = task.id
                    JOIN task_control_live_work_v256 live
                      ON live.task_id = task.id
                    WHERE evidence.task_id = ? AND evidence.id = ?
                      AND evidence.status = 'SATISFIED'
                      AND evidence.reconciliation_digest = digest.content_digest
                      AND task.workflow_version = 'V2'
                      AND task.lifecycle_state = 'RESUMING'
                      AND task.epoch = evidence.task_epoch
                      AND current.stage_id = evidence.stage_id
                      AND current.stage_generation = evidence.stage_generation
                      AND owner.generation = evidence.stage_generation
                      AND owner.checkpoint = evidence.restore_checkpoint
                      AND owner.completed_at_ms IS NULL
                      AND code.code_fingerprint = evidence.code_fingerprint
                      AND code.head_sha = evidence.head_sha
                      AND code.base_sha = evidence.base_sha
                      AND live.active_task_turn_count = 0
                      AND live.active_stage_turn_count = 0
                      AND live.active_review_turn_count = 0
                      AND live.active_dispatch_count = 0
                      AND live.active_agent_execution_count = 0
                    """,
                    (rs, row) -> new TaskManager.ResumeEvidence(
                            rs.getString("task_id"),
                            rs.getLong("task_epoch"),
                            rs.getString("id"),
                            rs.getString("stage_id"),
                            rs.getLong("stage_generation"),
                            StageCheckpoint.valueOf(rs.getString("restore_checkpoint")),
                            rs.getString("reconciliation_digest")),
                    taskId, reconciliationId).stream().findFirst();
            if (current.isPresent()) {
                return current;
            }
        }
        return jdbc.query("""
                SELECT evidence.task_id, evidence.task_epoch, evidence.id,
                       evidence.stage_id, evidence.stage_generation,
                       evidence.restore_checkpoint, evidence.reconciliation_digest
                FROM task_resume_reconciliation evidence
                JOIN task_resume_evidence_digest_v230 digest
                  ON digest.id = evidence.id
                JOIN task_pause_evidence paused
                  ON paused.barrier_id = evidence.pause_barrier_id
                JOIN tasks task ON task.id = evidence.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                JOIN task_live_work_counts_v230 live ON live.task_id = task.id
                WHERE evidence.task_id = ? AND evidence.id = ?
                  AND evidence.status = 'SATISFIED'
                  AND paused.status = 'SATISFIED'
                  AND paused.task_id = evidence.task_id
                  AND paused.task_epoch = evidence.task_epoch
                  AND paused.stage_id = evidence.stage_id
                  AND paused.stage_generation = evidence.stage_generation
                  AND paused.restore_checkpoint = evidence.restore_checkpoint
                  AND paused.code_fingerprint = evidence.paused_code_fingerprint
                  AND paused.head_sha = evidence.paused_head_sha
                  AND paused.base_sha = evidence.paused_base_sha
                  AND code.code_fingerprint = evidence.paused_code_fingerprint
                  AND code.head_sha = evidence.paused_head_sha
                  AND code.base_sha = evidence.paused_base_sha
                  AND evidence.reconciliation_digest = digest.content_digest
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'RESUMING'
                  AND task.epoch = evidence.task_epoch
                  AND current.stage_id = evidence.stage_id
                  AND current.stage_generation = evidence.stage_generation
                  AND owner.generation = evidence.stage_generation
                  AND owner.checkpoint = evidence.restore_checkpoint
                  AND owner.completed_at_ms IS NULL
                  AND live.task_epoch = evidence.task_epoch
                  AND live.active_task_turn_count = 0
                  AND live.active_stage_turn_count = 0
                  AND live.active_plan_review_count = 0
                  AND live.active_validation_count = 0
                  AND live.active_brain_episode_count = 0
                  AND live.active_provision_operation_count = 0
                  AND live.active_writer_dispatch_count = 0
                  AND live.active_agent_execution_count = 0
                  AND live.unreconciled_execution_count = 0
                  AND live.active_publish_operation_count = 0
                  AND live.unreconciled_publish_operation_count = 0
                  AND live.active_publish_effect_count = 0
                  AND NOT EXISTS (
                      SELECT 1 FROM capacity_lease lease
                      WHERE lease.workflow_source = 'V2'
                        AND lease.task_id = evidence.task_id
                        AND lease.task_epoch = evidence.task_epoch
                        AND lease.writer_required = 1
                        AND lease.released_at_ms IS NULL
                        AND lease.expires_at_ms > evidence.recorded_at_ms)
                  AND NOT EXISTS (
                      SELECT 1 FROM worktree_leases lease
                      WHERE lease.workflow_version = 'V2'
                        AND lease.task_id = evidence.task_id
                        AND lease.task_epoch = evidence.task_epoch
                        AND lease.expires_at_ms > evidence.recorded_at_ms)
                """,
                (rs, row) -> new TaskManager.ResumeEvidence(
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("id"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        StageCheckpoint.valueOf(rs.getString("restore_checkpoint")),
                        rs.getString("reconciliation_digest")),
                taskId, reconciliationId).stream().findFirst();
    }

    @Override
    public Optional<TaskManager.ArchiveEvidence> findArchiveEvidence(
            String taskId, String archiveEvidenceId)
    {
        return jdbc.query("""
                SELECT evidence.task_id, evidence.task_epoch, evidence.id,
                       evidence.stage_id, evidence.stage_generation,
                       evidence.liveness_digest
                FROM task_archive_liveness evidence
                JOIN task_archive_evidence_digest_v230 digest
                  ON digest.id = evidence.id
                JOIN tasks task ON task.id = evidence.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_live_work_counts_v230 live ON live.task_id = task.id
                WHERE evidence.task_id = ? AND evidence.id = ?
                  AND evidence.status = 'SATISFIED'
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ARCHIVING'
                  AND task.epoch = evidence.task_epoch
                  AND current.stage_id = evidence.stage_id
                  AND current.stage_generation = evidence.stage_generation
                  AND owner.generation = evidence.stage_generation
                  AND owner.completed_at_ms IS NULL
                  AND evidence.liveness_digest = digest.content_digest
                  AND live.task_epoch = evidence.task_epoch
                  AND live.active_task_turn_count = evidence.active_task_turn_count
                  AND live.active_stage_turn_count = evidence.active_stage_turn_count
                  AND live.active_review_turn_count = evidence.active_review_turn_count
                  AND live.active_plan_review_count = evidence.active_plan_review_count
                  AND live.active_brain_episode_count
                        = evidence.active_brain_episode_count
                  AND live.active_validation_count = evidence.active_validation_count
                  AND live.active_provision_operation_count
                        = evidence.active_provision_operation_count
                  AND live.active_dispatch_count = evidence.active_dispatch_count
                  AND live.active_agent_execution_count
                        = evidence.active_agent_execution_count
                  AND live.unreconciled_execution_count
                        = evidence.unreconciled_execution_count
                  AND live.active_quiescence_count = evidence.active_quiescence_count
                  AND live.active_replan_count = evidence.active_replan_count
                  AND live.active_feedback_batch_count
                        = evidence.active_feedback_batch_count
                  AND live.active_publish_operation_count
                        = evidence.active_publish_operation_count
                  AND live.unreconciled_publish_operation_count
                        = evidence.unreconciled_publish_operation_count
                  AND live.active_publish_effect_count
                        = evidence.active_publish_effect_count
                  AND live.active_publish_authorization_count
                        = evidence.active_publish_authorization_count
                  AND live.open_permission_count = evidence.open_permission_count
                  AND live.accepted_terminal_intent_count
                        = evidence.accepted_terminal_intent_count
                  AND live.open_cleanup_stage_count = evidence.open_cleanup_stage_count
                  AND (SELECT COUNT(*) FROM capacity_lease lease
                      WHERE lease.workflow_source = 'V2'
                        AND lease.task_id = evidence.task_id
                        AND lease.task_epoch = evidence.task_epoch
                        AND lease.released_at_ms IS NULL
                        AND lease.expires_at_ms > evidence.recorded_at_ms)
                        = evidence.live_capacity_lease_count
                  AND (SELECT COUNT(*) FROM worktree_leases lease
                      WHERE lease.workflow_version = 'V2'
                        AND lease.task_id = evidence.task_id
                        AND lease.task_epoch = evidence.task_epoch
                        AND lease.expires_at_ms > evidence.recorded_at_ms)
                        = evidence.live_worktree_lease_count
                """,
                (rs, row) -> new TaskManager.ArchiveEvidence(
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("id"),
                        rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("liveness_digest")),
                taskId, archiveEvidenceId).stream().findFirst();
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
        requireTransaction();
        validateCommit(expectedEpoch, expectedVersion, expected, updated);
        boolean stageChanged = !Objects.equals(
                expected.currentStageId(), updated.currentStageId());
        if (stageChanged && updated.currentStageId() != null) {
            requireNextStage(updated, nextStageId, nextStageKind, nextStageGeneration);
            repointCurrentStage(expected, updated, nextStageGeneration);
        }
        else if (!stageChanged && nextStageId != null) {
            throw new IllegalArgumentException("Unchanged Task cannot advertise a next Stage");
        }

        String linkedStageAtCas = stageChanged && updated.currentStageId() != null
                ? updated.currentStageId()
                : expected.currentStageId();
        boolean cleanupCompletion = "ACCEPT_CLEANUP_COMPLETION".equals(cause);
        if (cleanupCompletion) {
            insertTaskOutcome(resultFence, expected, updated);
            recordTransition(commandId, cause, actor, expected, updated);
            insertReceipt(
                    commandId, cause, actor, CommandResult.Disposition.APPLIED,
                    expectedEpoch, expectedVersion, resultFence, brainVerdict, proofId,
                    nextStageId, nextStageKind, nextStageGeneration, updated);
        }
        int changed = jdbc.update("""
                UPDATE tasks
                SET lifecycle_state = ?, epoch = ?, aggregate_version = ?
                WHERE id = ? AND workflow_version = 'V2' AND thread_id = ?
                  AND lifecycle_state = ? AND epoch = ? AND aggregate_version = ?
                  AND ((? IS NULL AND NOT EXISTS (
                        SELECT 1 FROM task_current_stage current
                        WHERE current.task_id = tasks.id))
                    OR (? IS NOT NULL AND EXISTS (
                        SELECT 1 FROM task_current_stage current
                        WHERE current.task_id = tasks.id AND current.stage_id = ?)))
                """,
                updated.lifecycle().name(), updated.epoch(), updated.version(),
                expected.id(), expected.trunkId(), expected.lifecycle().name(),
                expected.epoch(), expected.version(), linkedStageAtCas,
                linkedStageAtCas, linkedStageAtCas);
        if (changed != 1) {
            throw concurrent("Task changed before commit: " + expected.id());
        }

        if (updated.currentStageId() == null && expected.currentStageId() != null) {
            int deleted = jdbc.update("""
                    DELETE FROM task_current_stage
                    WHERE task_id = ? AND stage_id = ?
                    """, expected.id(), expected.currentStageId());
            if (deleted != 1) {
                throw concurrent("Task current Stage changed before terminal commit");
            }
        }
        recordTerminalIntent(
                commandId, cause, proofId, resultFence, expected, updated);
        if (!cleanupCompletion) {
            recordTransition(commandId, cause, actor, expected, updated);
            insertReceipt(
                    commandId, cause, actor, CommandResult.Disposition.APPLIED,
                    expectedEpoch, expectedVersion, resultFence, brainVerdict, proofId,
                    nextStageId, nextStageKind, nextStageGeneration, updated);
        }
        return updated;
    }

    private void insertTaskOutcome(
            ResultFence resultFence,
            TaskManager.State expected,
            TaskManager.State updated)
    {
        requireNonNull(resultFence, "Cleanup result fence is null");
        if (expected.lifecycle() != TaskLifecycle.CLEANING
                || !updated.lifecycle().isTerminal()
                || updated.currentStageId() != null
                || expected.terminalIntent() == null
                || !updated.lifecycle().name().equals(expected.terminalIntent().name())) {
            throw new IllegalArgumentException("Cleanup Task outcome is inconsistent");
        }
        long now = System.currentTimeMillis();
        int inserted = jdbc.update("""
                INSERT INTO task_outcome(
                    id, task_id, trunk_id, task_epoch,
                    terminal_acceptance_id, cleanup_operation_id,
                    cleanup_stage_id, terminal_reason, pr_id,
                    remote_pr_binding_id, remote_terminal_observation_id,
                    observed_head_sha, cleanup_summary_digest,
                    summary_state, summary_text, summary_digest,
                    summary_operation_id, follow_up_proposals_json,
                    backlog_items_json, recorded_at_ms, summary_updated_at_ms)
                SELECT 'TASK_OUTCOME:' || task.id, task.id, task.thread_id,
                       task.epoch, acceptance.id, operation.id,
                       cleanup.stage_id, acceptance.kind, binding.pr_id,
                       cleanup.remote_pr_binding_id,
                       acceptance.remote_terminal_observation_id,
                       acceptance.observed_head_sha, operation.summary_digest,
                       'FALLBACK',
                       'TaskOutcome:' || task.id || ':' || acceptance.kind
                           || ':' || operation.summary_digest,
                       operation.summary_digest, NULL, '[]', '[]', ?, NULL
                  FROM tasks task
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN cleanup_stage cleanup ON cleanup.stage_id = current.stage_id
                  JOIN stage owner ON owner.id = cleanup.stage_id
                  JOIN task_terminal_acceptance acceptance
                    ON acceptance.id = cleanup.terminal_acceptance_id
                  JOIN cleanup_operation operation
                    ON operation.cleanup_stage_id = cleanup.stage_id
                  JOIN dispatch_ticket ticket
                    ON ticket.id = operation.dispatch_ticket_id
                  LEFT JOIN remote_pr_binding binding
                    ON binding.id = cleanup.remote_pr_binding_id
                 WHERE task.id = ? AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'CLEANING' AND task.epoch = ?
                   AND task.aggregate_version = ?
                   AND current.stage_id = ? AND current.stage_generation = ?
                   AND owner.generation = current.stage_generation
                   AND owner.checkpoint = 'COMPLETED'
                   AND owner.completed_at_ms IS NOT NULL
                   AND acceptance.task_id = task.id
                   AND acceptance.task_epoch = task.epoch
                   AND acceptance.kind = ?
                   AND operation.operation_id = ?
                   AND operation.semantic_attempt = ?
                   AND operation.status = 'COMPLETED'
                   AND operation.summary_digest IS NOT NULL
                   AND ticket.status = 'SUCCEEDED'
                   AND ticket.delivery_acceptance = 'ACCEPTED'
                   AND ticket.delivery_evidence IS NOT NULL
                """, now, expected.id(), resultFence.taskEpoch(), expected.version(),
                resultFence.stageId(), resultFence.stageGeneration(),
                updated.lifecycle().name(), resultFence.operationId(), resultFence.attempt());
        if (inserted != 1) {
            throw concurrent("Cleanup outcome proof changed before Task terminalization");
        }
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
        requireTransaction();
        insertReceipt(
                commandId, cause, actor, CommandResult.Disposition.SUPERSEDED,
                expectedEpoch, expectedVersion, resultFence, brainVerdict, proofId,
                nextStageId, nextStageKind, nextStageGeneration, current);
        return current;
    }

    @Override
    public void markReplanApplied(
            TaskManager.ReplanEvidence evidence,
            String newPlanStageId,
            long newPlanGeneration)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE task_replan_request
                SET status = 'APPLIED', new_plan_stage_id = ?,
                    new_plan_generation = ?, completed_at_ms = ?
                WHERE id = ? AND task_id = ? AND status = 'QUIESCING'
                  AND source_stage_id = ? AND source_generation = ?
                  AND source_task_epoch = ? AND target_task_epoch = ?
                  AND quiescence_barrier_id = ? AND command_id = ?
                  AND requested_by = ?
                """,
                newPlanStageId, newPlanGeneration, System.currentTimeMillis(),
                evidence.replanRequestId(), evidence.taskId(), evidence.sourceStageId(),
                evidence.sourceStageGeneration(), evidence.sourceTaskEpoch(),
                evidence.targetTaskEpoch(), evidence.quiescenceBarrierId(),
                evidence.commandId(), evidence.requestedBy());
        if (changed != 1) {
            throw concurrent("Replan request changed before completion: "
                    + evidence.replanRequestId());
        }
    }

    private void repointCurrentStage(
            TaskManager.State expected,
            TaskManager.State updated,
            Long nextStageGeneration)
    {
        if (nextStageGeneration == null) {
            throw new IllegalArgumentException("Next Stage generation is missing");
        }
        int changed;
        if (expected.currentStageId() == null) {
            changed = jdbc.update("""
                    INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                    VALUES (?, ?, ?)
                    """, updated.id(), updated.currentStageId(), nextStageGeneration);
        }
        else {
            changed = jdbc.update("""
                    UPDATE task_current_stage
                    SET stage_id = ?, stage_generation = ?
                    WHERE task_id = ? AND stage_id = ?
                    """,
                    updated.currentStageId(), nextStageGeneration,
                    expected.id(), expected.currentStageId());
        }
        if (changed != 1) {
            throw concurrent("Task current Stage changed before repoint");
        }
    }

    private void recordTerminalIntent(
            String commandId,
            String cause,
            String proofId,
            ResultFence resultFence,
            TaskManager.State expected,
            TaskManager.State updated)
    {
        if (expected.terminalIntent() == updated.terminalIntent()) {
            return;
        }
        if (expected.terminalIntent() != null || updated.terminalIntent() == null) {
            throw new IllegalArgumentException("Task terminal intent cannot be replaced");
        }
        jdbc.update("""
                INSERT INTO task_terminal_intent(
                    id, task_id, kind, source, source_id, observed_head_sha,
                    evidence_json, accepted, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 1, ?)
                """,
                id(), updated.id(), updated.terminalIntent().name(),
                terminalSource(cause), terminalSourceId(
                        commandId, cause, proofId),
                resultFence == null ? null : resultFence.expectedHeadSha(),
                System.currentTimeMillis());
    }

    private static String terminalSource(String cause)
    {
        return switch (cause) {
            case "OPEN_MERGED_CLEANUP", "OPEN_REMOTE_CLOSED_CLEANUP" ->
                    "REMOTE_OBSERVATION";
            case "REQUEST_CANCEL" -> "USER_CANCEL";
            default -> cause;
        };
    }

    private static String terminalSourceId(
            String commandId, String cause, String proofId)
    {
        return "REQUEST_CANCEL".equals(cause) ? commandId : proofId;
    }

    private void recordTransition(
            String commandId,
            String cause,
            String actor,
            TaskManager.State expected,
            TaskManager.State updated)
    {
        jdbc.update("""
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), updated.id(), commandId, updated.epoch(),
                expected.lifecycle().name(), updated.lifecycle().name(),
                updated.version(), cause, actor, System.currentTimeMillis());
    }

    private void insertReceipt(
            String commandId,
            String cause,
            String actor,
            CommandResult.Disposition disposition,
            Long expectedEpoch,
            Long expectedVersion,
            ResultFence resultFence,
            TaskManager.BrainVerdict brainVerdict,
            String proofId,
            String nextStageId,
            StageKind nextStageKind,
            Long nextStageGeneration,
            TaskManager.State state)
    {
        jdbc.update(connection -> {
            String insert = switch (cause) {
                case "REQUEST_BRAIN_REVIEW" -> isRemoteBrainResult(
                        resultFence, proofId)
                        ? REMOTE_BRAIN_RECEIPT_INSERT : BRAIN_RECEIPT_INSERT;
                case "ACCEPT_BRAIN_VERDICT" -> isRemoteBrainResult(
                        resultFence, null)
                        ? REMOTE_BRAIN_RECEIPT_INSERT : RECEIPT_INSERT;
                case "ACCEPT_BRAIN_BUDGET_EXHAUSTION" ->
                        BRAIN_BUDGET_RECEIPT_INSERT;
                default -> RECEIPT_INSERT;
            };
            PreparedStatement statement = connection.prepareStatement(insert);
            int index = 1;
            statement.setString(index++, id());
            statement.setString(index++, state.id());
            statement.setString(index++, commandId);
            statement.setString(index++, cause);
            statement.setString(index++, actor);
            statement.setString(index++, disposition.name());
            setLong(statement, index++, expectedEpoch);
            setLong(statement, index++, expectedVersion);
            index = bindFence(statement, index, resultFence);
            statement.setString(index++, name(brainVerdict));
            statement.setString(index++, proofId);
            statement.setString(index++, nextStageId);
            statement.setString(index++, name(nextStageKind));
            setLong(statement, index++, nextStageGeneration);
            statement.setString(index++, state.trunkId());
            statement.setString(index++, state.lifecycle().name());
            statement.setLong(index++, state.epoch());
            statement.setLong(index++, state.version());
            statement.setString(index++, state.currentStageId());
            index = bindFence(statement, index, state.pendingBrainResult());
            statement.setString(index++, name(state.lastBrainVerdict()));
            index = bindFence(statement, index, state.lastBrainResult());
            statement.setString(index++, name(state.terminalIntent()));
            statement.setLong(index, System.currentTimeMillis());
            return statement;
        });
    }

    private Optional<TaskManager.CommandReceipt> queryReceipt(String sql, Object... arguments)
    {
        return jdbc.query(sql, (rs, row) -> receipt(rs), arguments)
                .stream().findFirst();
    }

    private Optional<TaskManager.CommandReceipt> findProjection(
            String taskId, long version)
    {
        Optional<TaskManager.CommandReceipt> shared = queryReceipt(
                RECEIPT_SELECT + """
                        WHERE task_id = ? AND disposition = 'APPLIED'
                          AND returned_version = ?
                        """,
                taskId, version);
        if (shared.isPresent()) {
            return shared;
        }
        if (tableAvailable("task_brain_request_receipt")) {
            Optional<TaskManager.CommandReceipt> request = queryReceipt(
                    "SELECT * FROM task_brain_request_receipt"
                            + " WHERE task_id = ? AND disposition = 'APPLIED'"
                            + " AND returned_version = ?",
                    taskId, version);
            if (request.isPresent()) {
                return request;
            }
        }
        if (tableAvailable("task_brain_budget_receipt")) {
            Optional<TaskManager.CommandReceipt> budget = queryReceipt(
                    "SELECT * FROM task_brain_budget_receipt"
                            + " WHERE task_id = ? AND disposition = 'APPLIED'"
                            + " AND returned_version = ?",
                    taskId, version);
            if (budget.isPresent()) {
                return budget;
            }
        }
        if (tableAvailable("remote_task_brain_receipt")) {
            return queryReceipt(
                    "SELECT * FROM remote_task_brain_receipt"
                            + " WHERE task_id = ? AND disposition = 'APPLIED'"
                            + " AND returned_version = ?",
                    taskId, version);
        }
        return Optional.empty();
    }

    private boolean isRemoteBrainResult(ResultFence fence, String proofId)
    {
        if (fence == null || !tableAvailable("remote_feedback_brain_episode")) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM remote_feedback_brain_episode episode
                JOIN task_turn turn ON turn.id = episode.task_turn_id
                WHERE turn.operation_id = ?
                  AND (? IS NULL OR episode.id = ?)
                """, Integer.class, fence.operationId(), proofId, proofId);
        if (count != null && count == 1) {
            return true;
        }
        if (!tableAvailable("ci_repair_operation")
                || !tableAvailable("branch_sync_dispatch_operation")) {
            return false;
        }
        count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT operation.id, operation.operation_id
                    FROM ci_repair_operation operation
                    WHERE operation.kind = 'BRAIN_REVIEW'
                    UNION ALL
                    SELECT operation.id, operation.operation_id
                    FROM branch_sync_dispatch_operation operation
                    WHERE operation.kind = 'BRAIN_REVIEW') operation
                WHERE operation.operation_id = ?
                  AND (? IS NULL OR operation.id = ?)
                """, Integer.class, fence.operationId(), proofId, proofId);
        return count != null && count == 1;
    }

    private boolean tableAvailable(String table)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = ?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private static TaskManager.CommandReceipt receipt(ResultSet rs)
            throws SQLException
    {
        TaskManager.State state = new TaskManager.State(
                rs.getString("task_id"),
                rs.getString("returned_trunk_id"),
                TaskLifecycle.valueOf(rs.getString("returned_lifecycle")),
                rs.getLong("returned_epoch"),
                rs.getLong("returned_version"),
                rs.getString("returned_current_stage_id"),
                readFence(rs, "returned_pending_", false),
                brainVerdict(rs.getString("returned_last_brain_verdict")),
                readFence(rs, "returned_last_brain_", false),
                terminal(rs.getString("returned_terminal_intent")));
        return new TaskManager.CommandReceipt(
                state,
                rs.getString("cause"),
                rs.getString("actor"),
                nullableLong(rs, "expected_task_epoch"),
                nullableLong(rs, "expected_task_version"),
                readFence(rs, "subject_", true),
                brainVerdict(rs.getString("brain_verdict")),
                rs.getString("proof_id"),
                rs.getString("next_stage_id"),
                stageKind(rs.getString("next_stage_kind")),
                nullableLong(rs, "next_stage_generation"),
                CommandResult.Disposition.valueOf(rs.getString("disposition")));
    }

    private static ResultFence readFence(
            ResultSet rs, String prefix, boolean subject)
            throws SQLException
    {
        String operation = rs.getString(prefix + "operation_id");
        if (operation == null) {
            return null;
        }
        String codeColumn = subject
                ? prefix + "expected_code_fingerprint"
                : prefix + "code_fingerprint";
        String headColumn = subject ? prefix + "expected_head_sha" : prefix + "head_sha";
        String baseColumn = subject ? prefix + "expected_base_sha" : prefix + "base_sha";
        return new ResultFence(
                rs.getLong(prefix + "task_epoch"),
                rs.getString(prefix + "stage_id"),
                rs.getLong(prefix + "stage_generation"),
                operation,
                rs.getInt(prefix + "attempt"),
                rs.getString(codeColumn),
                rs.getString(headColumn),
                rs.getString(baseColumn));
    }

    private static int bindFence(
            PreparedStatement statement, int index, ResultFence fence)
            throws SQLException
    {
        if (fence == null) {
            for (int count = 0; count < 8; count++) {
                statement.setNull(index++, Types.NULL);
            }
            return index;
        }
        statement.setLong(index++, fence.taskEpoch());
        statement.setString(index++, fence.stageId());
        statement.setLong(index++, fence.stageGeneration());
        statement.setString(index++, fence.operationId());
        statement.setInt(index++, fence.attempt());
        statement.setString(index++, fence.expectedCodeFingerprint());
        statement.setString(index++, fence.expectedHeadSha());
        statement.setString(index++, fence.expectedBaseSha());
        return index;
    }

    private static void setLong(PreparedStatement statement, int index, Long value)
            throws SQLException
    {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        }
        else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void validateCommit(
            Long expectedEpoch,
            Long expectedVersion,
            TaskManager.State expected,
            TaskManager.State updated)
    {
        if (!expected.id().equals(updated.id())
                || !expected.trunkId().equals(updated.trunkId())
                || (expectedEpoch == null) != (expectedVersion == null)
                || (expectedEpoch != null && expected.epoch() != expectedEpoch)
                || (expectedVersion != null && expected.version() != expectedVersion)
                || updated.version() != expected.version() + 1
                || updated.epoch() < expected.epoch()
                || updated.epoch() > expected.epoch() + 1) {
            throw new IllegalArgumentException("Task commit fence is inconsistent");
        }
    }

    private static void requireNextStage(
            TaskManager.State updated,
            String nextStageId,
            StageKind nextStageKind,
            Long nextStageGeneration)
    {
        if (!updated.currentStageId().equals(nextStageId)
                || nextStageKind == null
                || nextStageGeneration == null) {
            throw new IllegalArgumentException("Task next Stage identity is inconsistent");
        }
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Task writes require the command transaction");
        }
    }

    private static CommandRejectedException concurrent(String message)
    {
        return new CommandRejectedException(CONCURRENT_UPDATE, message);
    }

    private static TaskManager.BrainVerdict brainVerdict(String value)
    {
        return value == null ? null : TaskManager.BrainVerdict.valueOf(value);
    }

    private static TaskManager.TerminalOutcome terminal(String value)
    {
        return value == null ? null : TaskManager.TerminalOutcome.valueOf(value);
    }

    private static StageKind stageKind(String value)
    {
        return value == null ? null : StageKind.valueOf(value);
    }

    private static String name(Enum<?> value)
    {
        return value == null ? null : value.name();
    }

    private static String planningBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.PlanningSnapshot planning
                ? planning.baseSha()
                : null;
    }

    private static String assignmentBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.ExistingPrHead existing
                ? existing.pullRequest().remoteBaseSha()
                : null;
    }

    private static String assignmentHead(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.ExistingPrHead existing
                ? existing.pullRequest().remoteHeadSha()
                : null;
    }

    private static String expectedBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.FreshRemoteBase
                ? null
                : input.base() instanceof TaskCreationInput.PlanningSnapshot planning
                        ? planning.baseSha()
                        : assignmentBase(input);
    }

    private static String expectedHead(TaskCreationInput input)
    {
        return assignmentHead(input);
    }

    private static Long linkedPrNumber(TaskAssignment assignment)
    {
        return switch (assignment) {
            case TaskAssignment.ExistingOwnPr value ->
                    (long) value.pullRequest().number();
            case TaskAssignment.ReviewFindings value ->
                    (long) value.pullRequest().number();
            default -> null;
        };
    }

    private static String linkedPrRef(TaskAssignment assignment)
    {
        TaskAssignment.PullRequestRef pullRequest = switch (assignment) {
            case TaskAssignment.ExistingOwnPr value -> value.pullRequest();
            case TaskAssignment.ReviewFindings value -> value.pullRequest();
            default -> null;
        };
        return pullRequest == null
                ? null
                : (pullRequest.repositories().baseRepositoryId()
                + "#" + pullRequest.number()).toLowerCase(Locale.ROOT);
    }

    private static Long nullableLong(Integer value)
    {
        return value == null ? null : value.longValue();
    }

    private static boolean same(Map<String, Object> row, String name, Object expected)
    {
        return Objects.equals(row.get(name), expected);
    }

    private static long number(Map<String, Object> row, String name)
    {
        return ((Number) row.get(name)).longValue();
    }

    private static Long nullableNumber(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String id()
    {
        return UUID.randomUUID().toString();
    }

    private static String escape(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record BaseState(
            String id,
            String trunkId,
            TaskLifecycle lifecycle,
            long epoch,
            long version,
            String currentStageId,
            TaskManager.TerminalOutcome terminalIntent)
    {
        private TaskManager.State withProtocolState(
                ResultFence pending,
                TaskManager.BrainVerdict verdict,
                ResultFence brainResult)
        {
            return new TaskManager.State(
                    id, trunkId, lifecycle, epoch, version, currentStageId,
                    pending, verdict, brainResult, terminalIntent);
        }

        private boolean matchesCore(TaskManager.State state)
        {
            return id.equals(state.id())
                    && trunkId.equals(state.trunkId())
                    && lifecycle == state.lifecycle()
                    && epoch == state.epoch()
                    && version == state.version()
                    && Objects.equals(currentStageId, state.currentStageId())
                    && terminalIntent == state.terminalIntent();
        }
    }
}
