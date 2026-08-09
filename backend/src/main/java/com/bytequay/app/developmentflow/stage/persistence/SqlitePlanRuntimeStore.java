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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.INVALID_STATE;
import static java.util.Objects.requireNonNull;

/** Exact persistence boundary for the V2 Plan TaskTurn protocol. */
@Repository
public class SqlitePlanRuntimeStore
        implements PlanStageManager.FollowupStore
{
    private final JdbcTemplate jdbc;

    public SqlitePlanRuntimeStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public ProvisionContext requireProvisionContext(String taskId, String operationId)
    {
        List<ProvisionContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                    trunk.workspace_id, task.epoch AS task_epoch,
                    task.aggregate_version AS task_version,
                    task.lifecycle_state, operation.operation_id,
                    operation.semantic_attempt, operation.status AS operation_status,
                    context.repository_id, context.upstream_repository_id,
                    context.publish_repository_id, context.work_model_snapshot,
                    brain.provider, brain.model, brain.role_skill,
                    target.branch_name, target.worktree_path,
                    assignment.kind AS assignment_kind, assignment.source_id,
                    assignment.repository_id AS assignment_repository_id,
                    assignment.pr_number, assignment.plan_seed, assignment.prompt,
                    assignment.producer, assignment.reason,
                    assignment.selected_findings_json,
                    policy.id AS policy_revision_id, policy.auto_approve,
                    policy.auto_merge
                FROM provision_task_operation operation
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_provision_target target ON target.task_id = task.id
                JOIN task_assignment assignment ON assignment.id = task.assignment_id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                WHERE task.id = ? AND operation.operation_id = ?
                  AND task.workflow_version = 'V2'
                """, (rs, row) -> provisionContext(rs), taskId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact provisioning context, found " + rows.size());
        }
        return rows.getFirst();
    }

    public Optional<ProvisionReceipt> findProvisionReceipt(
            String taskId, String provisionOperationId)
    {
        return jdbc.query("""
                SELECT task_id, provision_operation_id, evidence_digest,
                    plan_stage_id, plan_stage_generation, draft_turn_id,
                    draft_operation_id, draft_ticket_id, recorded_at_ms
                FROM task_provision_plan_receipt
                WHERE task_id = ? AND provision_operation_id = ?
                """, (rs, row) -> new ProvisionReceipt(
                        rs.getString("task_id"),
                        rs.getString("provision_operation_id"),
                        rs.getString("evidence_digest"),
                        rs.getString("plan_stage_id"),
                        rs.getLong("plan_stage_generation"),
                        rs.getString("draft_turn_id"),
                        rs.getString("draft_operation_id"),
                        rs.getString("draft_ticket_id"),
                        instant(rs, "recorded_at_ms")),
                taskId, provisionOperationId).stream().findFirst();
    }

    public void insertProvisionReceipt(ProvisionReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_provision_plan_receipt(
                    id, task_id, provision_operation_id, evidence_digest,
                    plan_stage_id, plan_stage_generation, draft_turn_id,
                    draft_operation_id, draft_ticket_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id("provision-plan-receipt", receipt.provisionOperationId()),
                receipt.taskId(), receipt.provisionOperationId(),
                receipt.evidenceDigest(), receipt.planStageId(),
                receipt.planStageGeneration(), receipt.draftTurnId(),
                receipt.draftOperationId(), receipt.draftTicketId(),
                receipt.recordedAt().toEpochMilli());
    }

    public Optional<ReplanDraftReceipt> findReplanDraftReceipt(
            String replanRequestId)
    {
        return jdbc.query("""
                SELECT replan_request_id, task_id, task_epoch, plan_stage_id,
                    plan_stage_generation, draft_turn_id, draft_operation_id,
                    draft_ticket_id, recorded_at_ms
                FROM task_replan_plan_receipt
                WHERE replan_request_id = ?
                """, (rs, row) -> new ReplanDraftReceipt(
                        rs.getString("replan_request_id"), rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getString("plan_stage_id"),
                        rs.getLong("plan_stage_generation"),
                        rs.getString("draft_turn_id"),
                        rs.getString("draft_operation_id"),
                        rs.getString("draft_ticket_id"),
                        instant(rs, "recorded_at_ms")), replanRequestId)
                .stream().findFirst();
    }

    public ReplanDraftContext requireReplanDraftContext(
            String taskId,
            String replanRequestId,
            String planStageId,
            long planStageGeneration)
    {
        List<ReplanDraftContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                    task.thread_id AS trunk_id, trunk.workspace_id,
                    stage.id AS stage_id, stage.generation,
                    stage.version AS stage_version, replan.reason,
                    context.work_model_snapshot, brain.provider, brain.model,
                    brain.role_skill, code.worktree_path,
                    subject.code_fingerprint, subject.head_sha, subject.base_sha
                FROM task_replan_request replan
                JOIN tasks task ON task.id = replan.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_stage plan ON plan.stage_id = stage.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_code_identity code ON code.task_id = task.id
                JOIN task_current_code_subject_v230 subject
                  ON subject.task_id = task.id
                WHERE task.id = ? AND replan.id = ? AND stage.id = ?
                  AND stage.generation = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = replan.target_task_epoch
                  AND replan.status = 'APPLIED'
                  AND replan.new_plan_stage_id = stage.id
                  AND replan.new_plan_generation = stage.generation
                  AND current.stage_generation = stage.generation
                  AND stage.kind = 'PLAN' AND stage.checkpoint = 'DRAFTING'
                  AND stage.version = 0 AND stage.completed_at_ms IS NULL
                  AND plan.task_id = task.id
                  AND plan.opened_for_epoch = task.epoch
                """, (rs, row) -> new ReplanDraftContext(
                        new BrainLaunchContext(
                                rs.getString("task_id"), rs.getString("trunk_id"),
                                rs.getString("workspace_id"),
                                rs.getString("work_model_snapshot"),
                                rs.getString("provider"), rs.getString("model"),
                                rs.getString("role_skill"),
                                rs.getString("worktree_path")),
                        rs.getLong("task_epoch"), rs.getString("stage_id"),
                        rs.getLong("generation"), rs.getLong("stage_version"),
                        rs.getString("reason"), rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha")),
                taskId, replanRequestId, planStageId, planStageGeneration);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Applied replan does not own one unarmed Plan Stage");
        }
        return rows.getFirst();
    }

    public void insertReplanDraftReceipt(ReplanDraftReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_replan_plan_receipt(
                    replan_request_id, task_id, task_epoch, plan_stage_id,
                    plan_stage_generation, draft_turn_id, draft_operation_id,
                    draft_ticket_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.replanRequestId(), receipt.taskId(),
                receipt.taskEpoch(), receipt.planStageId(),
                receipt.planStageGeneration(), receipt.draftTurnId(),
                receipt.draftOperationId(), receipt.draftTicketId(),
                receipt.recordedAt().toEpochMilli());
    }

    public void insertTurn(TurnRequest turn)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'REQUESTED', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                turn.turnId(), turn.taskId(), turn.purpose(), turn.operationId(),
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.codeFingerprint(), turn.headSha(), turn.baseSha(),
                turn.deliveryLane(), turn.launchInput(),
                turn.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', ?, 'TASK_TURN_RESULT', ?, 0, 1, 0,
                    ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """,
                turn.ticketId(), turn.operationId(), turn.turnId(),
                turn.laneMask(), turn.workspaceId(), turn.trunkId(),
                turn.taskId(), turn.taskEpoch(), turn.stageId(),
                turn.stageGeneration(), turn.codeFingerprint(), turn.headSha(),
                turn.baseSha(), turn.requestedAt().toEpochMilli());
    }

    /** Revalidates the operation-scoped endpoint and marks this exact Turn running. */
    public Optional<String> findMcpTaskId(String turnId, String operationId)
    {
        return jdbc.query("""
                SELECT turn.task_id
                FROM task_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                  AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.status = 'RUNNING'
                """, (rs, row) -> rs.getString("task_id"), turnId, operationId)
                .stream().findFirst();
    }

    /** Revalidates the operation-scoped endpoint and marks this exact Turn running. */
    public Optional<McpContext> authorizeMcp(String turnId, String operationId)
    {
        requireTransaction();
        List<McpContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, turn.operation_id, turn.purpose,
                    turn.task_id, turn.task_epoch, turn.trigger_stage_id,
                    turn.trigger_stage_generation, turn.status AS turn_status,
                    turn.expected_code_fingerprint, turn.expected_head_sha,
                    turn.expected_base_sha, stage.checkpoint, stage.version,
                    code.worktree_path, task.aggregate_version AS task_version,
                    policy.id AS policy_revision_id, policy.auto_approve,
                    policy.auto_merge, ticket.started_at_ms
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_stage plan ON plan.stage_id = stage.id
                JOIN task_code_identity code ON code.task_id = task.id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                 AND execution.infrastructure_attempt
                    = ticket.infrastructure_attempts
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                  AND turn.status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = turn.task_epoch
                  AND current.stage_id = turn.trigger_stage_id
                  AND current.stage_generation = turn.trigger_stage_generation
                  AND plan.task_id = task.id
                  AND plan.opened_for_epoch = task.epoch
                  AND stage.kind = 'PLAN' AND stage.completed_at_ms IS NULL
                  AND code.code_fingerprint = turn.expected_code_fingerprint
                  AND code.local_head_sha = turn.expected_head_sha
                  AND code.base_sha = turn.expected_base_sha
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.task_id = turn.task_id
                  AND ticket.task_epoch = turn.task_epoch
                  AND ticket.stage_id = turn.trigger_stage_id
                  AND ticket.stage_generation = turn.trigger_stage_generation
                  AND ticket.status = 'RUNNING'
                  AND execution.status = 'RUNNING'
                """, (rs, row) -> mcpContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            return Optional.empty();
        }
        McpContext context = rows.getFirst();
        if (!"RUNNING".equals(context.turnStatus())) {
            int updated = jdbc.update("""
                    UPDATE task_turn
                    SET status = 'RUNNING',
                        started_at_ms = COALESCE(started_at_ms, ?)
                    WHERE id = ? AND operation_id = ?
                      AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED')
                      AND finished_at_ms IS NULL
                    """,
                    context.startedAt().toEpochMilli(), turnId, operationId);
            if (updated != 1) {
                return Optional.empty();
            }
            context = context.withTurnStatus("RUNNING");
        }
        return Optional.of(context);
    }

    public Optional<PlanSubmission> findPlanSubmission(String turnId)
    {
        return jdbc.query("""
                SELECT submission.task_turn_id, submission.operation_id,
                    revision.id AS revision_id, revision.revision,
                    revision.content, revision.content_digest, revision.source,
                    submission.submitted_at_ms
                FROM plan_turn_submission submission
                JOIN plan_revision revision
                  ON revision.id = submission.plan_revision_id
                WHERE submission.task_turn_id = ?
                """, (rs, row) -> new PlanSubmission(
                        rs.getString("task_turn_id"),
                        rs.getString("operation_id"),
                        rs.getString("revision_id"),
                        rs.getInt("revision"),
                        rs.getString("content"),
                        rs.getString("content_digest"),
                        rs.getString("source"),
                        instant(rs, "submitted_at_ms")), turnId)
                .stream().findFirst();
    }

    public PlanSubmission insertPlanSubmission(
            McpContext context,
            String revisionId,
            String content,
            String contentDigest,
            String source,
            Instant submittedAt)
    {
        requireTransaction();
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM plan_revision WHERE plan_stage_id = ?
                """, Integer.class, context.stageId());
        jdbc.update("""
                INSERT INTO plan_revision(
                    id, plan_stage_id, revision, content, content_digest,
                    source, created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                revisionId, context.stageId(), revision, content, contentDigest,
                source, context.turnId(), submittedAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO plan_turn_submission(
                    task_turn_id, operation_id, task_id, task_epoch,
                    plan_stage_id, stage_generation, plan_revision_id,
                    submitted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                context.turnId(), context.operationId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                revisionId, submittedAt.toEpochMilli());
        return new PlanSubmission(
                context.turnId(), context.operationId(), revisionId, revision,
                content, contentDigest, source, submittedAt);
    }

    public BrainLaunchContext requireBrainLaunchContext(String taskId)
    {
        List<BrainLaunchContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                    trunk.workspace_id, context.work_model_snapshot,
                    brain.provider, brain.model, brain.role_skill,
                    code.worktree_path
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_code_identity code ON code.task_id = task.id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                """, (rs, row) -> new BrainLaunchContext(
                        rs.getString("task_id"), rs.getString("trunk_id"),
                        rs.getString("workspace_id"),
                        rs.getString("work_model_snapshot"),
                        rs.getString("provider"), rs.getString("model"),
                        rs.getString("role_skill"), rs.getString("worktree_path")),
                taskId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Task Brain launch context, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public Optional<PlanDraftRetryReceipt> findPlanDraftRetryReceipt(
            String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT retry.command_id, retry.actor, retry.reason,
                    retry.task_id, retry.expected_task_epoch, retry.stage_id,
                    retry.expected_stage_generation, retry.expected_stage_version,
                    retry.returned_stage_version, retry.blocker_id,
                    retry.predecessor_turn_id, retry.subject_operation_id,
                    retry.replacement_turn_id, retry.pending_operation_id,
                    retry.replacement_ticket_id, retry.requested_at_ms
                FROM stage_plan_draft_retry_request_v297 retry
                WHERE retry.task_id = ? AND retry.command_id = ?
                """, (rs, row) -> planDraftRetryReceipt(rs), taskId, commandId)
                .stream().findFirst();
    }

    public PlanDraftRetryContext requirePlanDraftRetryContext(
            String taskId, String failedTurnId, String blockerId)
    {
        List<PlanDraftRetryContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                    task.thread_id AS trunk_id, trunk.workspace_id,
                    stage.id AS stage_id, stage.generation AS stage_generation,
                    stage.version AS stage_version, blocker.id AS blocker_id,
                    blocker.subject_revision AS blocker_subject_revision,
                    failed.id AS failed_turn_id,
                    failed.operation_id AS failed_operation_id,
                    failed.attempt AS failed_attempt,
                    failed.expected_code_fingerprint AS code_fingerprint,
                    failed.expected_head_sha AS head_sha,
                    failed.expected_base_sha AS base_sha,
                    COALESCE(
                        json_extract(failed.launch_input, '$.fallbackPrompt'),
                        json_extract(failed.launch_input, '$.prompt'))
                        AS frozen_prompt,
                    context.work_model_snapshot, brain.provider, brain.model,
                    brain.role_skill, code.worktree_path
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_stage plan ON plan.stage_id = stage.id
                JOIN task_blocker blocker
                  ON blocker.task_id = task.id AND blocker.stage_id = stage.id
                JOIN task_turn failed ON failed.id = ?
                JOIN stage_plan_terminal_result terminal
                  ON terminal.stage_id = stage.id
                 AND terminal.proof_id = failed.id
                 AND terminal.subject_operation_id = failed.operation_id
                JOIN plan_task_turn_delivery_receipt delivery
                  ON delivery.task_turn_id = failed.id
                 AND delivery.operation_id = failed.operation_id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_code_identity code ON code.task_id = task.id
                JOIN task_current_code_subject_v230 subject
                  ON subject.task_id = task.id
                WHERE task.id = ? AND blocker.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND current.stage_generation = stage.generation
                  AND stage.kind = 'PLAN'
                  AND stage.checkpoint = 'DRAFTING'
                  AND stage.completed_at_ms IS NULL
                  AND stage.end_reason IS NULL
                  AND plan.task_id = task.id
                  AND plan.generation = stage.generation
                  AND plan.opened_for_epoch = task.epoch
                  AND blocker.owner_kind = 'STAGE'
                  AND blocker.owner_id = stage.id
                  AND blocker.blocker_type = 'OPERATION_FAILED'
                  AND blocker.status = 'OPEN'
                  AND (blocker.subject_revision = failed.id
                    OR blocker.subject_revision IS NULL)
                  AND 1 = (
                      SELECT COUNT(*)
                      FROM task_blocker open_failure
                      WHERE open_failure.task_id = task.id
                        AND open_failure.stage_id = stage.id
                        AND open_failure.owner_kind = 'STAGE'
                        AND open_failure.owner_id = stage.id
                        AND open_failure.blocker_type = 'OPERATION_FAILED'
                        AND open_failure.status = 'OPEN')
                  AND failed.task_id = task.id
                  AND failed.task_epoch = task.epoch
                  AND failed.trigger_stage_id = stage.id
                  AND failed.trigger_stage_generation = stage.generation
                  AND failed.purpose = 'PLAN_DRAFT'
                  AND failed.status = 'FAILED'
                  AND failed.expected_code_fingerprint =
                      subject.code_fingerprint
                  AND failed.expected_head_sha = subject.head_sha
                  AND failed.expected_base_sha = subject.base_sha
                  AND terminal.cause = 'PLAN_DRAFT_FAILED'
                  AND terminal.checkpoint = 'DRAFTING'
                  AND terminal.returned_stage_version = stage.version
                  AND delivery.acceptance = 'ACCEPTED'
                  AND delivery.domain_result IN (
                      'PROTOCOL_BLOCKED', 'TURN_FAILED')
                """, (rs, row) -> new PlanDraftRetryContext(
                        new BrainLaunchContext(
                                rs.getString("task_id"),
                                rs.getString("trunk_id"),
                                rs.getString("workspace_id"),
                                rs.getString("work_model_snapshot"),
                                rs.getString("provider"),
                                rs.getString("model"),
                                rs.getString("role_skill"),
                                rs.getString("worktree_path")),
                        rs.getLong("task_epoch"), rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getLong("stage_version"),
                        rs.getString("blocker_id"),
                        rs.getString("blocker_subject_revision"),
                        rs.getString("failed_turn_id"),
                        rs.getString("failed_operation_id"),
                        rs.getInt("failed_attempt"),
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha"),
                        rs.getString("frozen_prompt")),
                failedTurnId, taskId, blockerId);
        if (rows.size() != 1) {
            throw new CommandRejectedException(
                    INVALID_STATE,
                    "Plan draft recovery target is stale, unrelated, or ambiguous");
        }
        PlanDraftRetryContext context = rows.getFirst();
        if (context.blockerSubjectRevision() == null
                && !blockerId.equals(id("plan-turn-blocker", failedTurnId))) {
            throw new CommandRejectedException(
                    INVALID_STATE,
                    "Legacy Plan blocker does not match its failed TaskTurn");
        }
        return context;
    }

    public Optional<ReviewSubmission> findReviewSubmission(String turnId)
    {
        return jdbc.query("""
                SELECT submission.task_turn_id, submission.operation_id,
                    submission.self_review_id, submission.plan_revision_id,
                    submission.reviewed_digest, submission.verdict,
                    submission.concerns_json, submission.follow_ups_json,
                    submission.stewardship_json, submission.submitted_at_ms
                FROM plan_review_submission submission
                WHERE submission.task_turn_id = ?
                """, (rs, row) -> reviewSubmission(rs), turnId)
                .stream().findFirst();
    }

    public ReviewSubmission insertReviewSubmission(
            McpContext context,
            String verdict,
            String concernsJson,
            String followUpsJson,
            String stewardshipJson,
            Instant submittedAt)
    {
        requireTransaction();
        ReviewOwner owner = requireReviewOwner(context.turnId());
        jdbc.update("""
                INSERT INTO plan_review_submission(
                    task_turn_id, operation_id, task_id, task_epoch,
                    plan_stage_id, stage_generation, self_review_id,
                    plan_revision_id, reviewed_digest, verdict,
                    concerns_json, follow_ups_json, stewardship_json,
                    submitted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                context.turnId(), context.operationId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                owner.selfReviewId(), owner.revisionId(), owner.reviewedDigest(),
                verdict, concernsJson, followUpsJson, stewardshipJson,
                submittedAt.toEpochMilli());
        return new ReviewSubmission(
                context.turnId(), context.operationId(), owner.selfReviewId(),
                owner.revisionId(), owner.reviewedDigest(), verdict,
                concernsJson, followUpsJson, stewardshipJson, submittedAt);
    }

    public TurnDeliveryContext requireTurnDelivery(
            String turnId, String operationId)
    {
        List<TurnDeliveryContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, turn.operation_id, turn.purpose,
                    turn.status AS turn_status, turn.task_id, turn.task_epoch,
                    turn.trigger_stage_id, turn.trigger_stage_generation,
                    turn.expected_code_fingerprint, turn.expected_head_sha,
                    turn.expected_base_sha, turn.requested_at_ms,
                    task.lifecycle_state, task.epoch AS current_task_epoch,
                    task.aggregate_version AS task_version,
                    current.stage_id AS current_stage_id,
                    current.stage_generation AS current_stage_generation,
                    stage.checkpoint, stage.version AS stage_version,
                    ticket.id AS ticket_id, ticket.status AS ticket_status,
                    ticket.pending_result_outcome,
                    ticket.pending_result_evidence,
                    policy.id AS policy_revision_id, policy.auto_approve,
                    policy.auto_merge
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage ON stage.id = turn.trigger_stage_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.operation_kind = 'EXECUTE_TASK_TURN'
                  AND ticket.async_family = 'AGENT_TURN'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> turnDeliveryContext(rs), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Plan TaskTurn delivery, found " + rows.size());
        }
        return rows.getFirst();
    }

    public Optional<PlanUserWaitContext> findUserWaitContext(
            String turnId,
            String operationId,
            String waitKind,
            String waitId)
    {
        List<PlanUserWaitContext> rows = jdbc.query("""
                SELECT turn.id AS turn_id, turn.operation_id, turn.purpose,
                    turn.status AS turn_status, turn.task_id, turn.task_epoch,
                    turn.trigger_stage_id, turn.trigger_stage_generation,
                    turn.expected_code_fingerprint, turn.expected_head_sha,
                    turn.expected_base_sha, turn.requested_at_ms,
                    turn.launch_input, task.lifecycle_state,
                    task.epoch AS current_task_epoch,
                    task.aggregate_version AS task_version,
                    current.stage_id AS current_stage_id,
                    current.stage_generation AS current_stage_generation,
                    stage.checkpoint, stage.version AS stage_version,
                    ticket.id AS ticket_id, ticket.status AS ticket_status,
                    ticket.pending_result_outcome,
                    ticket.pending_result_evidence,
                    policy.id AS policy_revision_id, policy.auto_approve,
                    policy.auto_merge,
                    review_attempt.self_review_id AS self_review_id,
                    review_attempt.semantic_attempt AS review_attempt,
                    execution.id AS execution_id,
                    CASE
                      WHEN json_extract(turn.launch_input, '$.transport') = 'CLI'
                        AND json_extract(
                            turn.launch_input, '$.toolEndpoint.profile') =
                            'TASK_BRAIN_READ_ONLY'
                        AND execution.provider = json_extract(
                            turn.launch_input, '$.provider')
                        AND length(trim(execution.provider_session_id)) > 0
                        AND NOT EXISTS (
                          SELECT 1 FROM task_turn live
                          WHERE live.task_id = turn.task_id
                            AND live.trigger_stage_id = turn.trigger_stage_id
                            AND live.trigger_stage_generation =
                                turn.trigger_stage_generation
                            AND live.purpose IN (
                              'PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                            AND live.status IN (
                              'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
                        AND NOT EXISTS (
                          SELECT 1 FROM task_turn later
                          WHERE later.task_id = turn.task_id
                            AND later.trigger_stage_id = turn.trigger_stage_id
                            AND later.trigger_stage_generation =
                                turn.trigger_stage_generation
                            AND later.purpose IN (
                              'PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                            AND later.rowid > turn.rowid)
                      THEN execution.provider_session_id
                    END AS provider_session_id,
                    CASE WHEN json_type(turn.launch_input,
                        '$.resumeSessionId') IS NULL THEN 0
                      ELSE json_extract(turn.launch_input,
                        '$.priorCumulativeInputTokens') END
                        AS cumulative_input_tokens,
                    CASE WHEN json_type(turn.launch_input,
                        '$.resumeSessionId') IS NULL THEN 0
                      ELSE json_extract(turn.launch_input,
                        '$.priorCumulativeOutputTokens') END
                        AS cumulative_output_tokens
                FROM task_turn turn
                JOIN tasks task ON task.id = turn.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = turn.trigger_stage_id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                JOIN task_policy_revision policy
                  ON policy.id = task.policy_revision_id
                JOIN typed_user_wait_result result
                  ON result.operation_id = turn.operation_id
                LEFT JOIN agent_execution execution
                  ON execution.id = (
                    SELECT candidate.id
                    FROM agent_execution candidate
                    WHERE candidate.ticket_id = ticket.id
                      AND candidate.status = 'SUCCEEDED'
                    ORDER BY candidate.infrastructure_attempt DESC
                    LIMIT 1)
                LEFT JOIN plan_self_review_all_attempt_v265 review_attempt
                  ON review_attempt.task_turn_id = turn.id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                  AND turn.status = 'SUCCEEDED'
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                  AND ticket.status = 'SUCCEEDED'
                  AND result.owner_kind = 'TASK_TURN'
                  AND result.turn_id = turn.id
                  AND result.wait_kind = ? AND result.wait_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = turn.task_epoch
                  AND current.stage_id = turn.trigger_stage_id
                  AND current.stage_generation = turn.trigger_stage_generation
                  AND stage.kind = 'PLAN' AND stage.completed_at_ms IS NULL
                  AND code.code_fingerprint IS turn.expected_code_fingerprint
                  AND code.head_sha IS turn.expected_head_sha
                  AND code.base_sha IS turn.expected_base_sha
                  AND ((? = 'QUESTION' AND EXISTS (
                        SELECT 1 FROM task_question question
                        WHERE question.id = ? AND question.turn_id = turn.id
                          AND question.state = 'ANSWERED'
                          AND question.continuation_state = 'READY'))
                    OR (? = 'PERMISSION' AND EXISTS (
                        SELECT 1 FROM permission_request permission
                        WHERE permission.id = ? AND permission.turn_kind = 'TASK'
                          AND permission.turn_id = turn.id
                          AND permission.operation_id = turn.operation_id
                          AND permission.state <> 'OPEN'
                          AND permission.continuation_state = 'READY')))
                """, (rs, row) -> new PlanUserWaitContext(
                        turnDeliveryContext(rs), rs.getString("launch_input"),
                        rs.getString("self_review_id"),
                        nullableInt(rs, "review_attempt"),
                        rs.getString("execution_id"),
                        rs.getString("provider_session_id"),
                        nullableLong(rs, "cumulative_input_tokens"),
                        nullableLong(rs, "cumulative_output_tokens")),
                turnId, operationId, waitKind, waitId,
                waitKind, waitId, waitKind, waitId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Plan user-wait owner is ambiguous");
        }
        return rows.stream().findFirst();
    }

    public Optional<String> findUserWaitSuccessor(String waitKind, String waitId)
    {
        return jdbc.query("""
                SELECT successor_turn_id
                FROM plan_turn_user_wait_continuation_v265
                WHERE wait_kind = ? AND wait_id = ?
                """, (rs, row) -> rs.getString("successor_turn_id"),
                waitKind, waitId).stream().findFirst();
    }

    public List<String> executionLog(String executionId)
    {
        required(executionId, "executionId");
        return jdbc.query("""
                SELECT payload FROM agent_execution_log
                WHERE execution_id = ? ORDER BY seq
                """, (rs, row) -> rs.getString("payload"), executionId);
    }

    public void insertSelfReviewUserWaitAttempt(
            String selfReviewId,
            int semanticAttempt,
            String turnId,
            String operationId,
            String predecessorTurnId,
            String waitKind,
            String waitId,
            Instant requestedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_self_review_user_wait_attempt_v265(
                    self_review_id, semantic_attempt, task_turn_id,
                    operation_id, predecessor_turn_id, wait_kind, wait_id,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, selfReviewId, semanticAttempt, turnId, operationId,
                predecessorTurnId, waitKind, waitId,
                requestedAt.toEpochMilli());
    }

    public Optional<String> findTurnDeliveryTaskId(
            String turnId, String operationId)
    {
        return jdbc.query("""
                SELECT turn.task_id
                FROM task_turn turn
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = turn.operation_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN ('PLAN_DRAFT', 'PLAN_SELF_REVIEW')
                  AND ticket.owner_kind = 'TASK_TURN'
                  AND ticket.owner_id = turn.id
                  AND ticket.callback_route = 'TASK_TURN_RESULT'
                """, (rs, row) -> rs.getString("task_id"), turnId, operationId)
                .stream().findFirst();
    }

    public Optional<TurnDeliveryReceipt> findTurnDeliveryReceipt(String turnId)
    {
        return jdbc.query("""
                SELECT task_turn_id, operation_id, raw_outcome,
                    raw_evidence_digest, acceptance, domain_result,
                    recorded_at_ms
                FROM plan_task_turn_delivery_receipt
                WHERE task_turn_id = ?
                """, (rs, row) -> new TurnDeliveryReceipt(
                        rs.getString("task_turn_id"),
                        rs.getString("operation_id"),
                        rs.getString("raw_outcome"),
                        rs.getString("raw_evidence_digest"),
                        rs.getString("acceptance"),
                        rs.getString("domain_result"),
                        instant(rs, "recorded_at_ms")), turnId)
                .stream().findFirst();
    }

    public void finishTurn(
            TurnDeliveryContext context, String terminalStatus, String error, Instant at)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE task_turn
                SET status = ?,
                    started_at_ms = CASE
                        WHEN ? IN ('SUCCEEDED', 'FAILED')
                            THEN COALESCE(started_at_ms, requested_at_ms)
                        ELSE started_at_ms END,
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                  AND finished_at_ms IS NULL
                """,
                terminalStatus, terminalStatus, at.toEpochMilli(), error,
                context.turnId(), context.operationId());
        if (changed != 1) {
            throw new IllegalStateException("Plan TaskTurn changed before completion");
        }
    }

    public void insertTurnDeliveryReceipt(TurnDeliveryReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_task_turn_delivery_receipt(
                    task_turn_id, operation_id, raw_outcome,
                    raw_evidence_digest, acceptance, domain_result,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                receipt.turnId(), receipt.operationId(), receipt.rawOutcome(),
                receipt.rawEvidenceDigest(), receipt.acceptance(),
                receipt.domainResult(), receipt.recordedAt().toEpochMilli());
    }

    public void insertSelfReview(
            String selfReviewId,
            String revisionId,
            String turnId,
            long taskEpoch,
            String reviewedDigest,
            Instant requestedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_self_review(
                    id, plan_revision_id, task_turn_id, task_epoch,
                    reviewed_digest, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?)
                """,
                selfReviewId, revisionId, turnId, taskEpoch, reviewedDigest,
                requestedAt.toEpochMilli());
        String operationId = jdbc.queryForObject(
                "SELECT operation_id FROM task_turn WHERE id = ?",
                String.class, turnId);
        jdbc.update("""
                INSERT INTO plan_self_review_attempt(
                    self_review_id, attempt, task_turn_id, operation_id,
                    predecessor_turn_id, requested_at_ms)
                VALUES (?, 1, ?, ?, NULL, ?)
                """, selfReviewId, turnId, operationId,
                requestedAt.toEpochMilli());
    }

    public void insertReviewRetryAttempt(
            String selfReviewId,
            String turnId,
            String operationId,
            String predecessorTurnId,
            Instant requestedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_self_review_attempt(
                    self_review_id, attempt, task_turn_id, operation_id,
                    predecessor_turn_id, requested_at_ms)
                VALUES (?, 2, ?, ?, ?, ?)
                """, selfReviewId, turnId, operationId, predecessorTurnId,
                requestedAt.toEpochMilli());
    }

    public void completeSelfReview(
            ReviewSubmission submission, Instant completedAt)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE plan_self_review
                SET status = 'SUCCEEDED', verdict = ?,
                    concern_summary = ?, completed_at_ms = ?, error_message = NULL
                WHERE id = ? AND plan_revision_id = ?
                  AND reviewed_digest = ?
                  AND status = 'REQUESTED'
                  AND EXISTS (
                      SELECT 1 FROM plan_self_review_all_attempt_v265 attempt
                      WHERE attempt.self_review_id = plan_self_review.id
                        AND attempt.task_turn_id = ?)
                """,
                submission.verdict(), concernSummary(submission.concernsJson()),
                completedAt.toEpochMilli(), submission.selfReviewId(),
                submission.revisionId(), submission.reviewedDigest(),
                submission.turnId());
        if (changed != 1) {
            throw new IllegalStateException("Plan self-review changed before completion");
        }
    }

    public void failSelfReview(
            TurnDeliveryContext context, String error, Instant completedAt)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE plan_self_review
                SET status = 'FAILED', completed_at_ms = ?, error_message = ?
                WHERE status = 'REQUESTED'
                  AND EXISTS (
                      SELECT 1 FROM plan_self_review_all_attempt_v265 attempt
                      WHERE attempt.self_review_id = plan_self_review.id
                        AND attempt.task_turn_id = ?)
                """,
                completedAt.toEpochMilli(), required(error, "error"),
                context.turnId());
        if (changed != 1) {
            throw new IllegalStateException("Plan self-review changed before failure");
        }
    }

    public ReviewOwner requireReviewOwner(String turnId)
    {
        List<ReviewOwner> rows = jdbc.query("""
                SELECT review.id AS self_review_id,
                    review.plan_revision_id AS revision_id,
                    review.reviewed_digest,
                    attempt.semantic_attempt AS execution_attempt,
                    (SELECT MAX(infrastructure.attempt)
                     FROM plan_self_review_attempt infrastructure
                     WHERE infrastructure.self_review_id = review.id)
                        AS failure_attempt,
                    attempt.predecessor_turn_id
                FROM plan_self_review review
                JOIN plan_self_review_all_attempt_v265 attempt
                  ON attempt.self_review_id = review.id
                JOIN task_turn turn ON turn.id = attempt.task_turn_id
                WHERE attempt.task_turn_id = ?
                  AND review.status = 'REQUESTED'
                  AND turn.purpose = 'PLAN_SELF_REVIEW'
                """, (rs, row) -> new ReviewOwner(
                        rs.getString("self_review_id"),
                        rs.getString("revision_id"),
                        rs.getString("reviewed_digest"),
                        rs.getInt("execution_attempt"),
                        rs.getInt("failure_attempt"),
                        rs.getString("predecessor_turn_id")), turnId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Plan self-review, found " + rows.size());
        }
        return rows.getFirst();
    }

    public PlanCandidate requirePlanCandidate(String revisionId)
    {
        List<PlanCandidate> rows = jdbc.query("""
                SELECT id, revision, content, content_digest, source
                FROM plan_revision WHERE id = ?
                """, (rs, row) -> new PlanCandidate(
                        rs.getString("id"), rs.getInt("revision"),
                        rs.getString("content"), rs.getString("content_digest"),
                        rs.getString("source")), revisionId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Plan review candidate is missing");
        }
        return rows.getFirst();
    }

    public void insertFollowup(
            String id,
            String revisionId,
            String kind,
            String description,
            String createdBy,
            Instant createdAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_followup(
                    id, plan_revision_id, kind, description, status,
                    created_by, created_at_ms)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?)
                """,
                id, revisionId, kind, required(description, "description"),
                createdBy, createdAt.toEpochMilli());
    }

    public void openReviewFailure(
            TurnDeliveryContext context,
            String selfReviewId,
            String revisionId,
            String reviewedDigest,
            String description,
            Instant openedAt)
    {
        requireTransaction();
        String blockerId = id("plan-review-blocker", selfReviewId);
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?, 'PLAN_REVIEW_FAILURE',
                    'OPEN', ?, ?)
                """,
                blockerId, context.taskId(), context.stageId(), context.stageId(),
                reviewedDigest, "{\"message\":" + quote(description) + "}",
                openedAt.toEpochMilli());
        jdbc.update("""
                INSERT INTO plan_followup(
                    id, plan_revision_id, kind, description, status,
                    self_review_id, task_blocker_id, created_by, created_at_ms)
                VALUES (?, ?, 'FAILURE_BLOCKER', ?, 'OPEN', ?, ?, ?, ?)
                """,
                id("plan-failure-followup", selfReviewId), revisionId,
                description, selfReviewId, blockerId, context.turnId(),
                openedAt.toEpochMilli());
    }

    public void openTurnFailure(
            TurnDeliveryContext context,
            String blockerType,
            String description,
            Instant openedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?, ?, 'OPEN', ?, ?)
                """,
                id("plan-turn-blocker", context.turnId()), context.taskId(),
                context.stageId(), context.stageId(), context.turnId(),
                required(blockerType, "blockerType"),
                "{\"message\":" + quote(description) + "}",
                openedAt.toEpochMilli());
    }

    public ApprovalContext requireApprovalContext(
            String taskId,
            String stageId,
            long stageGeneration,
            String revisionId,
            String selfReviewId)
    {
        List<ApprovalContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                    task.aggregate_version AS task_version,
                    task.thread_id AS trunk_id, trunk.workspace_id,
                    stage.id AS stage_id, stage.generation,
                    stage.version AS stage_version, stage.checkpoint,
                    revision.id AS revision_id, revision.content_digest,
                    review.id AS self_review_id,
                    task.policy_revision_id, policy.auto_approve,
                    policy.auto_merge
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                WHERE task.id = ? AND stage.id = ? AND stage.generation = ?
                  AND revision.id = ? AND review.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND stage.checkpoint = 'AWAITING_APPROVAL'
                  AND stage.completed_at_ms IS NULL
                  AND review.status = 'SUCCEEDED'
                  AND review.verdict = 'APPROVED'
                  AND review.reviewed_digest = revision.content_digest
                  AND NOT EXISTS (
                      SELECT 1 FROM plan_revision newer
                      WHERE newer.plan_stage_id = revision.plan_stage_id
                        AND newer.revision > revision.revision)
                """, (rs, row) -> approvalContext(rs), taskId, stageId,
                stageGeneration, revisionId, selfReviewId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Latest reviewed Plan is not eligible for approval");
        }
        return rows.getFirst();
    }

    public Optional<ApprovalContext> findLatestApprovalContext(String stageId)
    {
        return jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                    task.aggregate_version AS task_version,
                    task.thread_id AS trunk_id, trunk.workspace_id,
                    stage.id AS stage_id, stage.generation,
                    stage.version AS stage_version, stage.checkpoint,
                    revision.id AS revision_id, revision.content_digest,
                    review.id AS self_review_id,
                    task.policy_revision_id, policy.auto_approve,
                    policy.auto_merge
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                WHERE stage.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND stage.task_id = task.id
                  AND stage.generation = current.stage_generation
                  AND stage.kind = 'PLAN'
                  AND stage.checkpoint = 'AWAITING_APPROVAL'
                  AND stage.completed_at_ms IS NULL
                  AND review.status = 'SUCCEEDED'
                  AND review.verdict = 'APPROVED'
                  AND review.reviewed_digest = revision.content_digest
                  AND NOT EXISTS (
                      SELECT 1 FROM plan_revision newer
                      WHERE newer.plan_stage_id = revision.plan_stage_id
                        AND newer.revision > revision.revision)
                """, (rs, row) -> approvalContext(rs), stageId)
                .stream().findFirst();
    }

    public Optional<ApprovalContext> findLatestApprovalContextForTask(
            String taskId)
    {
        List<String> stages = jdbc.queryForList("""
                SELECT stage.id
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND stage.task_id = task.id
                  AND stage.generation = current.stage_generation
                  AND stage.kind = 'PLAN'
                  AND stage.checkpoint = 'AWAITING_APPROVAL'
                  AND stage.completed_at_ms IS NULL
                """, String.class, taskId);
        if (stages.isEmpty()) {
            return Optional.empty();
        }
        if (stages.size() != 1) {
            throw new IllegalStateException(
                    "Task has more than one current Plan approval owner");
        }
        return findLatestApprovalContext(stages.getFirst());
    }

    @Override
    public Optional<PlanStageManager.FollowupEvidence> find(
            String taskId, String stageId, String followupId)
    {
        return jdbc.query("""
                SELECT followup.id, plan.task_id, plan.stage_id,
                       plan.generation, revision.id AS revision_id,
                       followup.status, followup.resolution,
                       followup.resolved_at_ms
                FROM plan_followup followup
                JOIN plan_revision revision
                  ON revision.id = followup.plan_revision_id
                JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                WHERE followup.id = ? AND followup.kind = 'FOLLOW_UP'
                  AND plan.task_id = ? AND plan.stage_id = ?
                """, (rs, row) -> new PlanStageManager.FollowupEvidence(
                        rs.getString("id"), rs.getString("task_id"),
                        rs.getString("stage_id"), rs.getLong("generation"),
                        rs.getString("revision_id"),
                        PlanStageManager.FollowupStatus.valueOf(
                                rs.getString("status")),
                        rs.getString("resolution"),
                        instant(rs, "resolved_at_ms")),
                followupId, taskId, stageId).stream().findFirst();
    }

    @Override
    public PlanStageManager.FollowupEvidence update(
            PlanStageManager.FollowupEvidence current,
            PlanStageManager.FollowupCommand command)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE plan_followup
                   SET status = ?, resolved_at_ms = ?, resolution = ?
                 WHERE id = ? AND plan_revision_id = ? AND status = ?
                """, command.status().name(), command.resolvedAt().toEpochMilli(),
                command.actor() + ": " + command.resolution(), current.id(),
                current.revisionId(), current.status().name());
        if (changed != 1) {
            throw new IllegalStateException(
                    "Plan follow-up changed before resolution: " + current.id());
        }
        return find(command.taskId(), command.stageId(), command.followupId())
                .orElseThrow(() -> new IllegalStateException(
                        "Resolved Plan follow-up disappeared"));
    }

    public PlanEditContext requirePlanEditContext(
            String taskId,
            String stageId,
            long stageGeneration,
            String previousRevisionId,
            String previousSelfReviewId)
    {
        List<PlanEditContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                    task.aggregate_version AS task_version,
                    task.thread_id AS trunk_id, trunk.workspace_id,
                    stage.id AS stage_id, stage.generation,
                    stage.version AS stage_version,
                    revision.id AS previous_revision_id,
                    revision.revision AS previous_revision,
                    revision.content_digest AS previous_digest,
                    review.id AS previous_self_review_id,
                    context.work_model_snapshot, brain.provider, brain.model,
                    brain.role_skill, code.worktree_path,
                    code.code_fingerprint, code.local_head_sha,
                    code.base_sha
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage ON stage.id = current.stage_id
                JOIN plan_revision revision ON revision.plan_stage_id = stage.id
                JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_code_identity code ON code.task_id = task.id
                WHERE task.id = ? AND stage.id = ? AND stage.generation = ?
                  AND revision.id = ? AND review.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND stage.kind = 'PLAN'
                  AND stage.checkpoint = 'AWAITING_APPROVAL'
                  AND stage.completed_at_ms IS NULL
                  AND review.status = 'SUCCEEDED'
                  AND review.verdict = 'APPROVED'
                  AND review.reviewed_digest = revision.content_digest
                  AND NOT EXISTS (
                      SELECT 1 FROM plan_revision newer
                      WHERE newer.plan_stage_id = revision.plan_stage_id
                        AND newer.revision > revision.revision)
                """, (rs, row) -> planEditContext(rs), taskId, stageId,
                stageGeneration, previousRevisionId, previousSelfReviewId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Latest reviewed Plan is not eligible for editing");
        }
        return rows.getFirst();
    }

    public EditedRevision insertUserRevision(
            PlanEditContext context,
            String revisionId,
            String content,
            String contentDigest,
            String actor,
            Instant createdAt)
    {
        requireTransaction();
        int revision = context.previousRevision() + 1;
        jdbc.update("""
                INSERT INTO plan_revision(
                    id, plan_stage_id, revision, content, content_digest,
                    source, created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, 'USER_EDIT', ?, ?)
                """, revisionId, context.stageId(), revision, content,
                contentDigest, actor, createdAt.toEpochMilli());
        return new EditedRevision(
                revisionId, revision, content, contentDigest, createdAt);
    }

    public Optional<PlanEditReceipt> findPlanEditReceipt(String requestId)
    {
        return jdbc.query("""
                SELECT receipt.request_id, receipt.task_id, receipt.task_epoch,
                    receipt.plan_stage_id, receipt.stage_generation,
                    receipt.expected_stage_version, receipt.actor,
                    receipt.previous_revision_id, receipt.plan_revision_id,
                    revision.revision, revision.content,
                    receipt.content_digest, receipt.self_review_id,
                    receipt.review_turn_id, receipt.review_operation_id,
                    receipt.review_ticket_id, receipt.review_command_id,
                    receipt.recorded_at_ms
                FROM plan_user_edit_receipt receipt
                JOIN plan_revision revision
                  ON revision.id = receipt.plan_revision_id
                WHERE receipt.request_id = ?
                """, (rs, row) -> planEditReceipt(rs), requestId)
                .stream().findFirst();
    }

    public void insertPlanEditReceipt(PlanEditReceipt receipt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_user_edit_receipt(
                    request_id, task_id, task_epoch, plan_stage_id,
                    stage_generation, expected_stage_version, actor,
                    previous_revision_id, plan_revision_id, content_digest,
                    self_review_id, review_turn_id, review_operation_id,
                    review_ticket_id, review_command_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.requestId(), receipt.taskId(), receipt.taskEpoch(),
                receipt.stageId(), receipt.stageGeneration(),
                receipt.expectedStageVersion(), receipt.actor(),
                receipt.previousRevisionId(), receipt.revisionId(),
                receipt.contentDigest(), receipt.selfReviewId(),
                receipt.reviewTurnId(), receipt.reviewOperationId(),
                receipt.reviewTicketId(), receipt.reviewCommandId(),
                receipt.recordedAt().toEpochMilli());
    }

    public boolean hasOpenStewardship(String revisionId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM plan_followup
                WHERE plan_revision_id = ? AND kind = 'STEWARDSHIP'
                  AND status <> 'RESOLVED'
                """, Integer.class, revisionId);
        return count != null && count > 0;
    }

    /**
     * Current V2 Plan facts for one workspace automation family. Tasks that
     * already advanced beyond Plan are deliberately absent; LEGACY rows can
     * never enter this projection.
     */
    public List<AutomationPlan> listAutomationPlans(
            String workspaceId, String taskOrigin, String taskType)
    {
        return jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                    trunk.workspace_id, task.origin AS task_origin,
                    task.task_type, task.linked_issue_number,
                    task.created_at_ms, task.epoch AS task_epoch,
                    task.aggregate_version AS task_version,
                    stage.id AS stage_id, stage.generation AS stage_generation,
                    stage.version AS stage_version, stage.checkpoint,
                    revision.id AS revision_id, revision.content,
                    review.id AS self_review_id,
                    CASE
                      WHEN stage.kind = 'PLAN' AND EXISTS (
                        SELECT 1 FROM task_blocker blocker
                        WHERE blocker.task_id = task.id
                          AND blocker.stage_id = stage.id
                          AND blocker.owner_kind = 'STAGE'
                          AND blocker.owner_id = stage.id
                          AND blocker.status = 'OPEN') THEN 'FAILED'
                      WHEN stage.kind = 'PLAN'
                        AND stage.checkpoint = 'AWAITING_APPROVAL'
                        AND stage.completed_at_ms IS NULL
                        AND review.status = 'SUCCEEDED'
                        AND review.verdict = 'APPROVED'
                        AND review.reviewed_digest = revision.content_digest
                        THEN 'REVIEWED'
                      ELSE 'PENDING'
                    END AS plan_state,
                    (SELECT blocker.blocker_type
                       FROM task_blocker blocker
                      WHERE blocker.task_id = task.id
                        AND blocker.stage_id = stage.id
                        AND blocker.owner_kind = 'STAGE'
                        AND blocker.owner_id = stage.id
                        AND blocker.status = 'OPEN'
                      ORDER BY blocker.opened_at_ms DESC, blocker.id DESC
                      LIMIT 1) AS failure_reason
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                LEFT JOIN task_current_stage current
                  ON current.task_id = task.id
                LEFT JOIN stage
                  ON stage.id = current.stage_id
                 AND stage.generation = current.stage_generation
                 AND stage.task_id = task.id
                LEFT JOIN plan_revision revision
                  ON stage.kind = 'PLAN'
                 AND revision.plan_stage_id = stage.id
                 AND NOT EXISTS (
                    SELECT 1 FROM plan_revision newer
                    WHERE newer.plan_stage_id = revision.plan_stage_id
                      AND newer.revision > revision.revision)
                LEFT JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                WHERE trunk.workspace_id = ?
                  AND task.origin = ? AND task.task_type = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state IN ('PROVISIONING', 'ACTIVE')
                  AND (stage.id IS NULL OR stage.kind = 'PLAN')
                ORDER BY task.created_at_ms, task.id
                """, (rs, row) -> new AutomationPlan(
                        rs.getString("task_id"), rs.getString("trunk_id"),
                        rs.getString("workspace_id"), rs.getString("task_origin"),
                        rs.getString("task_type"),
                        nullableInt(rs, "linked_issue_number"),
                        instant(rs, "created_at_ms"), rs.getLong("task_epoch"),
                        rs.getLong("task_version"), rs.getString("stage_id"),
                        nullableLong(rs, "stage_generation"),
                        nullableLong(rs, "stage_version"),
                        rs.getString("checkpoint"), rs.getString("revision_id"),
                        rs.getString("content"), rs.getString("self_review_id"),
                        rs.getString("plan_state"),
                        rs.getString("failure_reason")),
                required(workspaceId, "workspaceId"),
                required(taskOrigin, "taskOrigin"),
                required(taskType, "taskType"));
    }

    public void insertApproval(
            String approvalId,
            ApprovalContext context,
            String kind,
            String actor,
            Instant approvedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO plan_approval(
                    id, plan_revision_id, self_review_id, approval_kind,
                    policy_revision_id, actor, approved_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                approvalId, context.revisionId(), context.selfReviewId(), kind,
                context.policyRevisionId(), actor, approvedAt.toEpochMilli());
    }

    public Optional<AcceptedApproval> findAcceptedApproval(String approvalId)
    {
        return jdbc.query("""
                SELECT approval.id AS approval_id, approval.approval_kind,
                    approval.actor, approval.policy_revision_id,
                    approval.approved_at_ms, plan.task_id,
                    task_receipt.returned_epoch AS task_epoch,
                    task_receipt.returned_version AS task_version, plan.stage_id,
                    plan.generation,
                    plan_receipt.returned_version AS plan_stage_version,
                    revision.id AS revision_id,
                    revision.content_digest, review.id AS self_review_id,
                    task_receipt.next_stage_id,
                    task_receipt.next_stage_generation
                FROM plan_approval approval
                JOIN plan_revision revision
                  ON revision.id = approval.plan_revision_id
                JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                JOIN plan_self_review review ON review.id = approval.self_review_id
                JOIN task_command_receipt task_receipt
                  ON task_receipt.task_id = plan.task_id
                 AND task_receipt.proof_id = approval.id
                 AND task_receipt.cause = 'OPEN_LOCAL_DEVELOPMENT'
                 AND task_receipt.disposition = 'APPLIED'
                JOIN stage_command_receipt plan_receipt
                  ON plan_receipt.stage_id = plan.stage_id
                 AND plan_receipt.task_id = plan.task_id
                 AND plan_receipt.command_id = task_receipt.command_id
                 AND plan_receipt.proof_id = approval.id
                 AND plan_receipt.cause = 'APPROVE_PLAN'
                 AND plan_receipt.disposition = 'APPLIED'
                WHERE approval.id = ?
                """, (rs, row) -> new AcceptedApproval(
                        rs.getString("approval_id"),
                        rs.getString("approval_kind"), rs.getString("actor"),
                        rs.getString("policy_revision_id"),
                        instant(rs, "approved_at_ms"), rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getLong("task_version"),
                        rs.getString("stage_id"), rs.getLong("generation"),
                        rs.getLong("plan_stage_version"),
                        rs.getString("revision_id"),
                        rs.getString("content_digest"),
                        rs.getString("self_review_id"),
                        rs.getString("next_stage_id"),
                        rs.getLong("next_stage_generation")), approvalId)
                .stream().findFirst();
    }

    private static ProvisionContext provisionContext(ResultSet rs)
            throws SQLException
    {
        return new ProvisionContext(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("lifecycle_state"),
                rs.getString("operation_id"), rs.getInt("semantic_attempt"),
                rs.getString("operation_status"), rs.getString("repository_id"),
                rs.getString("upstream_repository_id"),
                rs.getString("publish_repository_id"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("branch_name"), rs.getString("worktree_path"),
                rs.getString("assignment_kind"), rs.getString("source_id"),
                rs.getString("assignment_repository_id"),
                nullableInt(rs, "pr_number"), rs.getString("plan_seed"),
                rs.getString("prompt"), rs.getString("producer"),
                rs.getString("reason"), rs.getString("selected_findings_json"),
                rs.getString("policy_revision_id"), rs.getInt("auto_approve") == 1,
                rs.getInt("auto_merge") == 1);
    }

    private static McpContext mcpContext(ResultSet rs)
            throws SQLException
    {
        return new McpContext(
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getString("purpose"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("trigger_stage_id"),
                rs.getLong("trigger_stage_generation"),
                rs.getString("turn_status"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getLong("version"), rs.getString("worktree_path"),
                rs.getLong("task_version"), rs.getString("policy_revision_id"),
                rs.getInt("auto_approve") == 1, rs.getInt("auto_merge") == 1,
                instant(rs, "started_at_ms"));
    }

    private static ReviewSubmission reviewSubmission(ResultSet rs)
            throws SQLException
    {
        return new ReviewSubmission(
                rs.getString("task_turn_id"), rs.getString("operation_id"),
                rs.getString("self_review_id"), rs.getString("plan_revision_id"),
                rs.getString("reviewed_digest"), rs.getString("verdict"),
                rs.getString("concerns_json"), rs.getString("follow_ups_json"),
                rs.getString("stewardship_json"),
                instant(rs, "submitted_at_ms"));
    }

    private static TurnDeliveryContext turnDeliveryContext(ResultSet rs)
            throws SQLException
    {
        return new TurnDeliveryContext(
                rs.getString("turn_id"), rs.getString("operation_id"),
                rs.getString("purpose"), rs.getString("turn_status"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("trigger_stage_id"),
                rs.getLong("trigger_stage_generation"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                instant(rs, "requested_at_ms"), rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"), rs.getLong("task_version"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                checkpoint(rs.getString("checkpoint")),
                nullableLong(rs, "stage_version"), rs.getString("ticket_id"),
                rs.getString("ticket_status"),
                rs.getString("pending_result_outcome"),
                rs.getString("pending_result_evidence"),
                rs.getString("policy_revision_id"),
                rs.getInt("auto_approve") == 1, rs.getInt("auto_merge") == 1);
    }

    private static ApprovalContext approvalContext(ResultSet rs)
            throws SQLException
    {
        return new ApprovalContext(
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getString("stage_id"),
                rs.getLong("generation"), rs.getLong("stage_version"),
                StageCheckpoint.valueOf(rs.getString("checkpoint")),
                rs.getString("revision_id"), rs.getString("content_digest"),
                rs.getString("self_review_id"),
                rs.getString("policy_revision_id"),
                rs.getInt("auto_approve") == 1, rs.getInt("auto_merge") == 1);
    }

    private static PlanEditContext planEditContext(ResultSet rs)
            throws SQLException
    {
        return new PlanEditContext(
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getString("stage_id"),
                rs.getLong("generation"), rs.getLong("stage_version"),
                rs.getString("previous_revision_id"),
                rs.getInt("previous_revision"), rs.getString("previous_digest"),
                rs.getString("previous_self_review_id"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("worktree_path"),
                rs.getString("code_fingerprint"),
                rs.getString("local_head_sha"), rs.getString("base_sha"));
    }

    private static PlanEditReceipt planEditReceipt(ResultSet rs)
            throws SQLException
    {
        return new PlanEditReceipt(
                rs.getString("request_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("plan_stage_id"),
                rs.getLong("stage_generation"),
                rs.getLong("expected_stage_version"), rs.getString("actor"),
                rs.getString("previous_revision_id"),
                rs.getString("plan_revision_id"), rs.getInt("revision"),
                rs.getString("content"), rs.getString("content_digest"),
                rs.getString("self_review_id"), rs.getString("review_turn_id"),
                rs.getString("review_operation_id"),
                rs.getString("review_ticket_id"),
                rs.getString("review_command_id"),
                instant(rs, "recorded_at_ms"));
    }

    private static PlanDraftRetryReceipt planDraftRetryReceipt(ResultSet rs)
            throws SQLException
    {
        return new PlanDraftRetryReceipt(
                rs.getString("command_id"), rs.getString("actor"),
                rs.getString("reason"), rs.getString("task_id"),
                rs.getLong("expected_task_epoch"), rs.getString("stage_id"),
                rs.getLong("expected_stage_generation"),
                rs.getLong("expected_stage_version"),
                rs.getLong("returned_stage_version"),
                rs.getString("blocker_id"),
                rs.getString("predecessor_turn_id"),
                rs.getString("subject_operation_id"),
                rs.getString("replacement_turn_id"),
                rs.getString("pending_operation_id"),
                rs.getString("replacement_ticket_id"),
                instant(rs, "requested_at_ms"));
    }

    private static String concernSummary(String concernsJson)
    {
        return "[]".equals(concernsJson) ? null : concernsJson;
    }

    private static String quote(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String id(String namespace, String value)
    {
        return UUID.nameUUIDFromBytes(
                ("bytequay-v2:" + namespace + ":" + value)
                        .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Integer nullableInt(ResultSet rs, String column)
            throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static StageCheckpoint checkpoint(String value)
    {
        return value == null ? null : StageCheckpoint.valueOf(value);
    }

    private static String required(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Plan runtime writes require a command transaction");
        }
    }

    public record ProvisionContext(
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            long taskVersion,
            String lifecycle,
            String provisionOperationId,
            int provisionAttempt,
            String provisionStatus,
            String repositoryId,
            String upstreamRepositoryId,
            String publishRepositoryId,
            String workModelSnapshot,
            String provider,
            String model,
            String roleSkill,
            String branchName,
            String worktreePath,
            String assignmentKind,
            String sourceId,
            String assignmentRepositoryId,
            Integer pullRequestNumber,
            String planSeed,
            String prompt,
            String producer,
            String reason,
            String selectedFindingsJson,
            String policyRevisionId,
            boolean autoApprove,
            boolean autoMerge) {}

    public record ProvisionReceipt(
            String taskId,
            String provisionOperationId,
            String evidenceDigest,
            String planStageId,
            long planStageGeneration,
            String draftTurnId,
            String draftOperationId,
            String draftTicketId,
            Instant recordedAt) {}

    public record ReplanDraftContext(
            BrainLaunchContext brain,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String reason,
            String codeFingerprint,
            String headSha,
            String baseSha) {}

    public record ReplanDraftReceipt(
            String replanRequestId,
            String taskId,
            long taskEpoch,
            String planStageId,
            long planStageGeneration,
            String draftTurnId,
            String draftOperationId,
            String draftTicketId,
            Instant recordedAt) {}

    public record PlanDraftRetryContext(
            BrainLaunchContext brain,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String blockerId,
            String blockerSubjectRevision,
            String failedTurnId,
            String failedOperationId,
            int failedAttempt,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String frozenPrompt)
    {
        public ResultFence failedFence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, failedOperationId,
                    failedAttempt, codeFingerprint, headSha, baseSha);
        }
    }

    public record PlanDraftRetryReceipt(
            String commandId,
            String actor,
            String reason,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long expectedStageVersion,
            long returnedStageVersion,
            String blockerId,
            String failedTurnId,
            String failedOperationId,
            String replacementTurnId,
            String replacementOperationId,
            String replacementTicketId,
            Instant requestedAt) {}

    public record TurnRequest(
            String turnId,
            String operationId,
            String ticketId,
            String purpose,
            String workspaceId,
            String trunkId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String deliveryLane,
            int laneMask,
            String launchInput,
            Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }

        public DispatchTicket.OperationFence operationFence()
        {
            return new DispatchTicket.OperationFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record McpContext(
            String turnId,
            String operationId,
            String purpose,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String turnStatus,
            String codeFingerprint,
            String headSha,
            String baseSha,
            StageCheckpoint checkpoint,
            long stageVersion,
            String worktreePath,
            long taskVersion,
            String policyRevisionId,
            boolean autoApprove,
            boolean autoMerge,
            Instant startedAt)
    {
        private McpContext withTurnStatus(String status)
        {
            return new McpContext(
                    turnId, operationId, purpose, taskId, taskEpoch, stageId,
                    stageGeneration, status, codeFingerprint, headSha, baseSha,
                    checkpoint, stageVersion, worktreePath, taskVersion,
                    policyRevisionId, autoApprove, autoMerge, startedAt);
        }

        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }

        public DispatchTicket.OperationFence operationFence()
        {
            return new DispatchTicket.OperationFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record PlanSubmission(
            String turnId,
            String operationId,
            String revisionId,
            int revision,
            String content,
            String contentDigest,
            String source,
            Instant submittedAt) {}

    public record BrainLaunchContext(
            String taskId,
            String trunkId,
            String workspaceId,
            String workModelSnapshot,
            String provider,
            String model,
            String roleSkill,
            String worktreePath) {}

    public record ReviewOwner(
            String selfReviewId,
            String revisionId,
            String reviewedDigest,
            int executionAttempt,
            int failureAttempt,
            String predecessorTurnId) {}

    public record PlanCandidate(
            String revisionId,
            int revision,
            String content,
            String contentDigest,
            String source) {}

    public record ReviewSubmission(
            String turnId,
            String operationId,
            String selfReviewId,
            String revisionId,
            String reviewedDigest,
            String verdict,
            String concernsJson,
            String followUpsJson,
            String stewardshipJson,
            Instant submittedAt) {}

    public record TurnDeliveryContext(
            String turnId,
            String operationId,
            String purpose,
            String turnStatus,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            Instant requestedAt,
            String taskLifecycle,
            long currentTaskEpoch,
            long taskVersion,
            String currentStageId,
            Long currentStageGeneration,
            StageCheckpoint checkpoint,
            Long stageVersion,
            String ticketId,
            String ticketStatus,
            String pendingResultOutcome,
            String pendingResultEvidence,
            String policyRevisionId,
            boolean autoApprove,
            boolean autoMerge)
    {
        public boolean isCurrentPlan()
        {
            return "ACTIVE".equals(taskLifecycle)
                    && currentTaskEpoch == taskEpoch
                    && stageId.equals(currentStageId)
                    && currentStageGeneration != null
                    && currentStageGeneration == stageGeneration
                    && checkpoint != null
                    && stageVersion != null;
        }

        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }

        public DispatchTicket.OperationFence operationFence()
        {
            return new DispatchTicket.OperationFence(
                    taskEpoch, stageId, stageGeneration, operationId, 1,
                    codeFingerprint, headSha, baseSha);
        }
    }

    public record TurnDeliveryReceipt(
            String turnId,
            String operationId,
            String rawOutcome,
            String rawEvidenceDigest,
            String acceptance,
            String domainResult,
            Instant recordedAt) {}

    public record PlanUserWaitContext(
            TurnDeliveryContext turn,
            String launchInput,
            String selfReviewId,
            Integer selfReviewAttempt,
            String executionId,
            String providerSessionId,
            Long cumulativeInputTokens,
            Long cumulativeOutputTokens)
    {
        public PlanUserWaitContext
        {
            requireNonNull(turn, "turn is null");
            required(launchInput, "launchInput");
            if (executionId != null && executionId.isBlank()) {
                throw new IllegalArgumentException(
                        "Plan continuation execution must not be blank");
            }
            if (providerSessionId != null && providerSessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "Plan continuation session must not be blank");
            }
            if (executionId == null && providerSessionId != null) {
                throw new IllegalArgumentException(
                        "Plan continuation session requires its exact execution");
            }
            if ((cumulativeInputTokens == null)
                    != (cumulativeOutputTokens == null)
                    || (cumulativeInputTokens != null
                    && (cumulativeInputTokens < 0 || cumulativeOutputTokens < 0))) {
                throw new IllegalArgumentException(
                        "Plan cumulative usage baseline is invalid");
            }
            if (turn.purpose().equals("PLAN_SELF_REVIEW")
                    && (selfReviewId == null || selfReviewAttempt == null)) {
                throw new IllegalArgumentException(
                        "Plan self-review continuation owner is incomplete");
            }
        }

        public PlanUserWaitContext(
                TurnDeliveryContext turn,
                String launchInput,
                String selfReviewId,
                Integer selfReviewAttempt)
        {
            this(turn, launchInput, selfReviewId, selfReviewAttempt, null, null,
                    0L, 0L);
        }
    }

    public record ApprovalContext(
            String taskId,
            long taskEpoch,
            long taskVersion,
            String trunkId,
            String workspaceId,
            String stageId,
            long stageGeneration,
            long stageVersion,
            StageCheckpoint checkpoint,
            String revisionId,
            String reviewedDigest,
            String selfReviewId,
            String policyRevisionId,
            boolean autoApprove,
            boolean autoMerge) {}

    public record AcceptedApproval(
            String approvalId,
            String kind,
            String actor,
            String policyRevisionId,
            Instant approvedAt,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String planStageId,
            long planStageGeneration,
            long planStageVersion,
            String revisionId,
            String reviewedDigest,
            String selfReviewId,
            String localStageId,
            long localStageGeneration) {}

    public record AutomationPlan(
            String taskId,
            String trunkId,
            String workspaceId,
            String taskOrigin,
            String taskType,
            Integer linkedIssueNumber,
            Instant taskCreatedAt,
            long taskEpoch,
            long taskVersion,
            String stageId,
            Long stageGeneration,
            Long stageVersion,
            String checkpoint,
            String revisionId,
            String content,
            String selfReviewId,
            String state,
            String failureReason) {}

    public record PlanEditContext(
            String taskId,
            long taskEpoch,
            long taskVersion,
            String trunkId,
            String workspaceId,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String previousRevisionId,
            int previousRevision,
            String previousDigest,
            String previousSelfReviewId,
            String workModelSnapshot,
            String provider,
            String model,
            String roleSkill,
            String worktreePath,
            String codeFingerprint,
            String headSha,
            String baseSha)
    {
        public BrainLaunchContext brain()
        {
            return new BrainLaunchContext(
                    taskId, trunkId, workspaceId, workModelSnapshot, provider,
                    model, roleSkill, worktreePath);
        }
    }

    public record EditedRevision(
            String revisionId,
            int revision,
            String content,
            String contentDigest,
            Instant createdAt) {}

    public record PlanEditReceipt(
            String requestId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long expectedStageVersion,
            String actor,
            String previousRevisionId,
            String revisionId,
            int revision,
            String content,
            String contentDigest,
            String selfReviewId,
            String reviewTurnId,
            String reviewOperationId,
            String reviewTicketId,
            String reviewCommandId,
            Instant recordedAt) {}
}
