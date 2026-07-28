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

import static java.util.Objects.requireNonNull;

/** Exact persistence boundary for the V2 Plan TaskTurn protocol. */
@Repository
public class SqlitePlanRuntimeStore
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
                  AND task_turn_id = ? AND reviewed_digest = ?
                  AND status = 'REQUESTED'
                """,
                submission.verdict(), concernSummary(submission.concernsJson()),
                completedAt.toEpochMilli(), submission.selfReviewId(),
                submission.revisionId(), submission.turnId(),
                submission.reviewedDigest());
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
                WHERE task_turn_id = ? AND status = 'REQUESTED'
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
                    review.reviewed_digest
                FROM plan_self_review review
                JOIN task_turn turn ON turn.id = review.task_turn_id
                WHERE review.task_turn_id = ?
                  AND review.status = 'REQUESTED'
                  AND turn.purpose = 'PLAN_SELF_REVIEW'
                """, (rs, row) -> new ReviewOwner(
                        rs.getString("self_review_id"),
                        rs.getString("revision_id"),
                        rs.getString("reviewed_digest")), turnId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Plan self-review, found " + rows.size());
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
                    blocker_type, status, payload_json, opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?, 'OPEN', ?, ?)
                """,
                id("plan-turn-blocker", context.turnId()), context.taskId(),
                context.stageId(), context.stageId(),
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

    public boolean hasOpenStewardship(String revisionId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM plan_followup
                WHERE plan_revision_id = ? AND kind = 'STEWARDSHIP'
                  AND status <> 'RESOLVED'
                """, Integer.class, revisionId);
        return count != null && count > 0;
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
            String selfReviewId, String revisionId, String reviewedDigest) {}

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
}
