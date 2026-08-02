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
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.OutputCodeSubject;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.AdoptionResult;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Operation;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.ResultReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.TurnDelivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable one-shot syntax normalization and commit-adoption protocol. */
@Repository
public class SqliteRemoteRepairNormalizationStore
        implements RemoteRepairCommitAdoptionOperationHandler.Store
{
    private static final String DUE_SELECT = """
            SELECT due.*, source.launch_input AS source_launch_input,
                   source.delivery_lane, ticket.lane_mask,
                   identity.worktree_path, identity.branch_name,
                   task.thread_id AS trunk_id, trunk.workspace_id,
                   CASE WHEN task.lifecycle_state = 'ACTIVE'
                          AND task.epoch = due.task_epoch
                          AND current.stage_id = due.remote_development_stage_id
                          AND current.stage_generation = due.stage_generation
                          AND code.source_code_subject_revision =
                              due.source_code_subject_revision
                          AND code.source_code_subject_kind =
                              due.source_code_subject_kind
                          AND code.source_code_subject_id =
                              due.source_code_subject_id
                          AND code.code_fingerprint =
                              due.expected_code_fingerprint
                          AND code.head_sha = due.expected_head_sha
                          AND code.base_sha = due.expected_base_sha
                          AND episode.status = 'FIXING'
                          AND episode.fix_attempt_count + 1 =
                              due.semantic_attempt
                          AND blocker.status = 'OPEN'
                        THEN 1 ELSE 0 END AS is_current
            FROM remote_repair_result_normalization_due_v322 due
            JOIN stage_turn source ON source.id = due.source_stage_turn_id
            JOIN dispatch_ticket ticket
              ON ticket.id = due.source_dispatch_ticket_id
            JOIN tasks task ON task.id = due.task_id
            JOIN threads trunk ON trunk.id = task.thread_id
            LEFT JOIN task_code_identity identity ON identity.task_id = task.id
            LEFT JOIN task_current_stage current ON current.task_id = task.id
            LEFT JOIN task_current_code_subject_fence_v322 code
              ON code.task_id = task.id
            JOIN ci_repair_episode episode
              ON episode.id = due.ci_repair_episode_id
            JOIN task_blocker blocker ON blocker.id = due.blocker_id
            """;

    private final JdbcTemplate jdbc;

    public SqliteRemoteRepairNormalizationStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public List<NormalizationDue> findPending(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query(DUE_SELECT + """
                WHERE due.status = 'PENDING'
                ORDER BY due.recorded_at_ms, due.id
                LIMIT ?
                """, (rs, row) -> normalizationDue(rs), limit);
    }

    public Optional<NormalizationDue> findPending(String dueId)
    {
        requireText(dueId, "dueId");
        return jdbc.query(DUE_SELECT + """
                WHERE due.id = ? AND due.status = 'PENDING'
                """, (rs, row) -> normalizationDue(rs), dueId)
                .stream().findFirst();
    }

    @Transactional
    public void cancelPending(
            NormalizationDue due, String reason, Instant at)
    {
        requireNonNull(due, "due is null");
        requireText(reason, "reason");
        requireNonNull(at, "at is null");
        updateOne("""
                UPDATE remote_repair_result_normalization_due_v322
                SET status = 'CANCELED', consumed_at_ms = ?
                WHERE id = ? AND status = 'PENDING'
                """, "Remote repair normalization due changed before cancel",
                at.toEpochMilli(), due.id());
        closeAuthorization(
                due.sourceBaseRepairAuthorizationId(), reason, at);
    }

    @Transactional
    public NormalizationOperation insertNormalization(
            NormalizationDue due, String launchInput, Instant at)
    {
        requireNonNull(due, "due is null");
        requireText(launchInput, "launchInput");
        requireNonNull(at, "at is null");
        int attempt = due.executionAttempt() + 1;
        String rowId = id("remote-repair-normalization-row", due.id());
        String turnId = id("remote-repair-normalization-turn", due.id());
        String operationId = id("remote-repair-normalization-operation", due.id());
        String ticketId = id("remote-repair-normalization-ticket", due.id());
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'REMOTE_REPAIR_RESULT_NORMALIZATION',
                    'REQUESTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turnId, due.taskId(), operationId, attempt,
                due.taskEpoch(), due.stageId(), due.stageGeneration(),
                due.expectedCodeFingerprint(), due.expectedHeadSha(),
                due.expectedBaseSha(), due.deliveryLane(), launchInput,
                at.toEpochMilli());
        jdbc.update("""
                INSERT INTO remote_repair_result_normalization_operation_v322(
                    id, normalization_due_id, source_operation_row_id,
                    source_operation_id, source_stage_turn_id,
                    ci_repair_episode_id, task_id, task_epoch,
                    remote_development_stage_id, stage_generation,
                    normalization_task_turn_id, operation_id,
                    dispatch_ticket_id, semantic_attempt,
                    source_execution_attempt, normalization_attempt,
                    source_code_subject_revision, source_code_subject_kind,
                    source_code_subject_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, 'REQUESTED', ?)
                """, rowId, due.id(), due.sourceOperationRowId(),
                due.sourceOperationId(), due.sourceStageTurnId(),
                due.episodeId(), due.taskId(), due.taskEpoch(), due.stageId(),
                due.stageGeneration(), turnId, operationId, ticketId,
                due.semanticAttempt(), due.executionAttempt(), attempt,
                due.sourceCodeSubjectRevision(),
                due.sourceCodeSubjectKind(), due.sourceCodeSubjectId(),
                due.expectedCodeFingerprint(), due.expectedHeadSha(),
                due.expectedBaseSha(), at.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_TASK_TURN', 'AGENT_TURN',
                    'TASK_TURN', ?, 'REMOTE_REPAIR_RESULT_NORMALIZATION_RESULT',
                    ?, 0, 1, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, turnId, due.laneMask(),
                due.workspaceId(), due.trunkId(), due.taskId(), due.taskEpoch(),
                due.stageId(), due.stageGeneration(), attempt,
                due.expectedCodeFingerprint(), due.expectedHeadSha(),
                due.expectedBaseSha(), at.toEpochMilli());
        updateOne("""
                UPDATE remote_repair_result_normalization_operation_v322
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Remote repair normalization did not dispatch", rowId);
        updateOne("""
                UPDATE remote_repair_result_normalization_due_v322
                SET status = 'DISPATCHED', normalization_operation_row_id = ?,
                    consumed_at_ms = ?
                WHERE id = ? AND status = 'PENDING'
                """, "Remote repair normalization due changed", rowId,
                at.toEpochMilli(), due.id());
        return new NormalizationOperation(
                rowId, due.id(), due.sourceOperationRowId(),
                due.sourceOperationId(), due.sourceStageTurnId(),
                due.episodeId(), due.taskId(), due.taskEpoch(), due.stageId(),
                due.stageGeneration(), turnId, operationId, ticketId,
                due.semanticAttempt(), due.executionAttempt(), attempt,
                due.sourceCodeSubjectRevision(),
                due.sourceCodeSubjectKind(), due.sourceCodeSubjectId(),
                due.expectedCodeFingerprint(), due.expectedHeadSha(),
                due.expectedBaseSha(), "DISPATCHED", due, at);
    }

    public String requireNormalizationTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT task_id
                FROM remote_repair_result_normalization_operation_v322
                WHERE normalization_task_turn_id = ? AND operation_id = ?
                  AND status IN (
                      'DISPATCHED', 'SUCCEEDED', 'FAILED',
                      'CANCELED', 'SUPERSEDED')
                """, (rs, row) -> rs.getString("task_id"), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one Remote repair normalization owner");
        }
        return rows.getFirst();
    }

    public NormalizationOperation requireNormalizationDelivery(
            String turnId, String operationId)
    {
        List<String> dueIds = jdbc.query("""
                SELECT normalization_due_id
                FROM remote_repair_result_normalization_operation_v322
                WHERE normalization_task_turn_id = ? AND operation_id = ?
                  AND status = 'DISPATCHED'
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (dueIds.size() != 1) {
            throw new IllegalStateException(
                    "Expected one Remote repair normalization delivery");
        }
        NormalizationDue due = requireDue(dueIds.getFirst());
        List<NormalizationOperation> rows = jdbc.query("""
                SELECT operation.*
                FROM remote_repair_result_normalization_operation_v322 operation
                WHERE operation.normalization_task_turn_id = ?
                  AND operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                """, (rs, row) -> normalizationOperation(rs, due),
                turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one Remote repair normalization delivery");
        }
        return rows.getFirst();
    }

    public Optional<ReplayReceipt> findNormalizationReceipt(
            String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation.status, operation.raw_outcome,
                       operation.normalization_raw_result_digest,
                       operation.acceptance
                FROM remote_repair_result_normalization_operation_v322 operation
                WHERE operation.operation_id = ?
                  AND operation.status IN (
                      'SUCCEEDED', 'FAILED', 'CANCELED', 'SUPERSEDED')
                """, (rs, row) -> new ReplayReceipt(
                        rs.getString("normalization_raw_result_digest"),
                        normalizationDeliveryReceipt(
                                rs.getString("status"),
                                rs.getString("raw_outcome"),
                                DispatchTicket.Acceptance.valueOf(
                                        rs.getString("acceptance")))),
                        operationId)
                .stream().findFirst();
    }

    @Transactional
    public void finishNormalization(
            NormalizationOperation operation,
            String rawOutcome,
            String rawDigest,
            String status,
            DispatchTicket.Acceptance acceptance,
            String normalizedPayload,
            String normalizedPayloadDigest,
            String terminalEvidence,
            String error,
            Instant at)
    {
        requireNonNull(operation, "operation is null");
        requireText(rawOutcome, "rawOutcome");
        requireText(rawDigest, "rawDigest");
        requireText(status, "status");
        requireNonNull(acceptance, "acceptance is null");
        requireText(terminalEvidence, "terminalEvidence");
        requireNonNull(at, "at is null");
        updateOne("""
                UPDATE task_turn
                SET status = ?,
                    started_at_ms = COALESCE(started_at_ms, requested_at_ms),
                    finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, "Remote repair normalization Turn changed",
                status, at.toEpochMilli(), error,
                operation.turnId(), operation.operationId());
        updateOne("""
                UPDATE remote_repair_result_normalization_operation_v322
                SET status = ?, raw_outcome = ?,
                    normalization_raw_result_digest = ?,
                    normalized_payload = ?, normalized_payload_digest = ?,
                    acceptance = ?, terminal_evidence = ?,
                    completed_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                """, "Remote repair normalization changed before delivery",
                status, rawOutcome, rawDigest, normalizedPayload,
                normalizedPayloadDigest, acceptance.name(), terminalEvidence,
                at.toEpochMilli(), error, operation.id(),
                operation.operationId());
        if ("SUCCEEDED".equals(status)) {
            insertAdoption(operation, at);
        }
        else {
            closeAuthorization(
                    operation.due().sourceBaseRepairAuthorizationId(),
                    error == null ? terminalEvidence : error, at);
        }
    }

    @Transactional
    public void insertMalformedDue(
            TurnDelivery context,
            String malformedOutput,
            OutputCodeSubject candidate,
            String rawDigest,
            String blockerId,
            Instant at)
    {
        requireNonNull(context, "context is null");
        requireText(malformedOutput, "malformedOutput");
        requireText(rawDigest, "rawDigest");
        requireText(blockerId, "blockerId");
        requireNonNull(at, "at is null");
        if (!recoverableCandidate(context, candidate)) {
            closeAuthorization(
                    context.baseRepairAuthorizationId(),
                    "Malformed Remote CI repair lacked one changed-tree candidate",
                    at);
            return;
        }
        String dueId = id("remote-repair-normalization-due", context.rowId());
        updateOne("""
                INSERT INTO remote_repair_result_normalization_due_v322(
                    id, ci_repair_episode_id, source_operation_row_id,
                    source_operation_id, source_stage_turn_id,
                    source_dispatch_ticket_id, source_agent_execution_id,
                    source_base_repair_authorization_id, blocker_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, semantic_attempt, execution_attempt,
                    source_code_subject_revision, source_code_subject_kind,
                    source_code_subject_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, source_malformed_output,
                    source_raw_result_digest, required_result_shape,
                    candidate_capture_kind, candidate_code_fingerprint,
                    candidate_head_sha, candidate_parent_sha,
                    candidate_base_sha, candidate_clean,
                    candidate_merge_base_sha, candidate_source_tree_sha,
                    candidate_result_tree_sha,
                    candidate_source_head_merge_base_sha,
                    candidate_branch_name, source_execution_started_at_ms,
                    source_execution_finished_at_ms, status, recorded_at_ms)
                SELECT ?, source.ci_repair_episode_id, source.id,
                       source.operation_id, source.stage_turn_id, ticket.id,
                       execution.id, source.base_repair_authorization_id, ?,
                       source.task_id, source.task_epoch,
                       source.remote_development_stage_id,
                       source.stage_generation, source.semantic_attempt,
                       turn.attempt, code.source_code_subject_revision,
                       code.source_code_subject_kind,
                       code.source_code_subject_id,
                       source.expected_code_fingerprint,
                       source.expected_head_sha, source.expected_base_sha,
                       ?, ?, '{"schemaVersion":1,"summary":"string"}',
                       'FROZEN_WRITER_PROOF_V1', ?, ?, ?, ?, 1, ?, ?, ?, ?, ?,
                       execution.started_at_ms, execution.finished_at_ms,
                       'PENDING', source.completed_at_ms
                FROM ci_repair_operation source
                JOIN stage_turn turn ON turn.id = source.stage_turn_id
                JOIN task_current_code_subject_fence_v322 code
                  ON code.task_id = source.task_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = source.operation_id
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                 AND execution.infrastructure_attempt =
                     ticket.infrastructure_attempts
                WHERE source.id = ? AND source.operation_id = ?
                """, "Malformed Remote CI repair due was not recorded",
                dueId, blockerId, malformedOutput, rawDigest,
                candidate.codeFingerprint(), candidate.headSha(),
                candidate.candidateParentSha(), candidate.baseSha(),
                candidate.mergeBaseSha(),
                candidate.sourceTreeSha(), candidate.resultTreeSha(),
                candidate.sourceHeadMergeBaseSha(), candidate.branchName(),
                context.rowId(), context.operationId());
    }

    private static boolean recoverableCandidate(
            TurnDelivery context, OutputCodeSubject candidate)
    {
        return candidate != null
                && candidate.clean()
                && !context.headSha().equals(candidate.headSha())
                && context.headSha().equals(candidate.candidateParentSha())
                && context.baseSha().equals(candidate.baseSha())
                && context.baseSha().equals(candidate.mergeBaseSha())
                && candidate.sourceTreeSha() != null
                && candidate.resultTreeSha() != null
                && !candidate.sourceTreeSha().equals(candidate.resultTreeSha())
                && context.headSha().equals(
                        candidate.sourceHeadMergeBaseSha())
                && candidate.branchName() != null;
    }

    private void insertAdoption(
            NormalizationOperation normalization, Instant at)
    {
        NormalizationDue due = normalization.due();
        String commandId = id("remote-repair-adoption-command", due.id());
        String reauthorizationId = null;
        if (due.sourceBaseRepairAuthorizationId() != null
                && "LEGACY_REFLOG_WINDOW_V1".equals(
                        due.candidateCaptureKind())) {
            reauthorizationId = id(
                    "ci-base-repair-reauthorization", due.id());
            jdbc.update("""
                    INSERT INTO ci_base_repair_reauthorization_v322(
                        id, source_authorization_id, normalization_due_id,
                        normalization_operation_row_id, ci_repair_episode_id,
                        source_operation_row_id, source_operation_id,
                        blocker_id, adoption_command_id, task_id, task_epoch,
                        remote_development_stage_id, stage_generation,
                        semantic_attempt, source_code_subject_revision,
                        source_code_subject_kind, source_code_subject_id,
                        expected_code_fingerprint,
                        expected_head_sha, expected_base_sha, status,
                        claimed_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, 'CLAIMED', ?)
                    """, reauthorizationId,
                    due.sourceBaseRepairAuthorizationId(), due.id(),
                    normalization.id(), due.episodeId(),
                    due.sourceOperationRowId(), due.sourceOperationId(),
                    due.blockerId(), commandId, due.taskId(), due.taskEpoch(),
                    due.stageId(), due.stageGeneration(), due.semanticAttempt(),
                    due.sourceCodeSubjectRevision(),
                    due.sourceCodeSubjectKind(), due.sourceCodeSubjectId(),
                    due.expectedCodeFingerprint(), due.expectedHeadSha(),
                    due.expectedBaseSha(), at.toEpochMilli());
        }

        String rowId = id("remote-repair-adoption-row", due.id());
        String operationId = id("remote-repair-adoption-operation", due.id());
        String ticketId = id("remote-repair-adoption-ticket", due.id());
        jdbc.update("""
                INSERT INTO remote_repair_commit_adoption_operation_v322(
                    id, normalization_due_id,
                    normalization_operation_row_id, ci_repair_episode_id,
                    source_operation_row_id, source_operation_id,
                    source_stage_turn_id,
                    source_base_repair_authorization_id,
                    compatibility_reauthorization_id, blocker_id, command_id,
                    task_id, task_epoch, remote_development_stage_id,
                    stage_generation, operation_id, dispatch_ticket_id,
                    adoption_attempt, worktree_path, expected_branch_name,
                    source_code_subject_revision, source_code_subject_kind,
                    source_code_subject_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, candidate_capture_kind,
                    candidate_code_fingerprint, candidate_head_sha,
                    candidate_source_tree_sha, candidate_result_tree_sha,
                    source_execution_started_at_ms,
                    source_execution_finished_at_ms, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, rowId, due.id(), normalization.id(), due.episodeId(),
                due.sourceOperationRowId(), due.sourceOperationId(),
                due.sourceStageTurnId(),
                due.sourceBaseRepairAuthorizationId(), reauthorizationId,
                due.blockerId(), commandId, due.taskId(), due.taskEpoch(),
                due.stageId(), due.stageGeneration(), operationId, ticketId,
                due.worktreePath(), due.branchName(),
                due.sourceCodeSubjectRevision(),
                due.sourceCodeSubjectKind(), due.sourceCodeSubjectId(),
                due.expectedCodeFingerprint(), due.expectedHeadSha(),
                due.expectedBaseSha(), due.candidateCaptureKind(),
                due.candidateCodeFingerprint(), due.candidateHeadSha(),
                due.candidateSourceTreeSha(), due.candidateResultTreeSha(),
                due.sourceExecutionStartedAt().toEpochMilli(),
                due.sourceExecutionFinishedAt().toEpochMilli(),
                at.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'ADOPT_NORMALIZED_REMOTE_REPAIR', 'LOCAL_GIT',
                    'TASK', ?, 'REMOTE_REPAIR_COMMIT_ADOPTION_RESULT',
                    16, 0, 1, 1, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, due.taskId(), due.workspaceId(),
                due.trunkId(), due.taskId(), due.taskEpoch(), due.stageId(),
                due.stageGeneration(), due.expectedCodeFingerprint(),
                due.expectedHeadSha(), due.expectedBaseSha(),
                at.toEpochMilli());
        updateOne("""
                UPDATE remote_repair_commit_adoption_operation_v322
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Remote repair adoption did not dispatch", rowId);
    }

    public String requireAdoptionTaskId(String ownerTaskId, String operationId)
    {
        requireText(ownerTaskId, "ownerTaskId");
        requireText(operationId, "operationId");
        List<String> rows = jdbc.query("""
                SELECT task_id
                FROM remote_repair_commit_adoption_operation_v322
                WHERE task_id = ? AND operation_id = ?
                  AND status IN (
                      'DISPATCHED', 'SUCCEEDED', 'FAILED',
                      'CANCELED', 'SUPERSEDED')
                """, (rs, row) -> rs.getString("task_id"),
                ownerTaskId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one Remote repair adoption owner");
        }
        return rows.getFirst();
    }

    public Optional<ReplayReceipt> findAdoptionReceipt(
            String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation.status, delivery.raw_result_digest,
                       delivery.acceptance
                FROM remote_repair_commit_adoption_delivery_v322 delivery
                JOIN remote_repair_commit_adoption_operation_v322 operation
                  ON operation.id = delivery.adoption_operation_row_id
                WHERE delivery.operation_id = ?
                """, (rs, row) -> new ReplayReceipt(
                        rs.getString("raw_result_digest"),
                        adoptionDeliveryReceipt(
                                rs.getString("status"),
                                DispatchTicket.Acceptance.valueOf(
                                        rs.getString("acceptance")))),
                        operationId)
                .stream().findFirst();
    }

    public Operation requireAdoptionDelivery(
            String ownerTaskId, String operationId)
    {
        requireText(ownerTaskId, "ownerTaskId");
        Operation operation = requireAdoptionOperation(operationId);
        if (!ownerTaskId.equals(operation.taskId())
                || !"DISPATCHED".equals(operation.status())) {
            throw new IllegalStateException(
                    "Remote repair adoption delivery owner is stale");
        }
        return operation;
    }

    @Override
    public Operation requireByOperationId(String operationId)
    {
        return requireAdoptionOperation(operationId);
    }

    private Operation requireAdoptionOperation(String operationId)
    {
        requireText(operationId, "operationId");
        List<Operation> rows = jdbc.query("""
                SELECT operation.*, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       CASE WHEN episode.status = 'FIXING'
                              AND episode.fix_attempt_count + 1 =
                                  due.semantic_attempt
                              AND episode.task_id = operation.task_id
                              AND episode.task_epoch = operation.task_epoch
                              AND episode.remote_development_stage_id =
                                  operation.remote_development_stage_id
                              AND episode.stage_generation =
                                  operation.stage_generation
                              AND episode.subject_head_sha =
                                  operation.expected_head_sha
                              AND episode.subject_base_sha =
                                  operation.expected_base_sha
                            THEN 1 ELSE 0
                       END AS current_ci_episode_fixing,
                       CASE WHEN blocker.task_id = operation.task_id
                              AND blocker.stage_id =
                                  operation.remote_development_stage_id
                              AND blocker.owner_kind = 'EPISODE'
                              AND blocker.owner_id =
                                  operation.ci_repair_episode_id
                              AND blocker.subject_revision =
                                  operation.expected_head_sha
                              AND blocker.blocker_type =
                                  'CI_REPAIR_OUTPUT_MALFORMED'
                              AND blocker.status = 'OPEN'
                            THEN 1 ELSE 0
                       END AS current_malformed_blocker_open,
                       CASE WHEN code.source_code_subject_revision =
                                      operation.source_code_subject_revision
                              AND code.source_code_subject_kind =
                                      operation.source_code_subject_kind
                              AND code.source_code_subject_id =
                                      operation.source_code_subject_id
                            THEN 1 ELSE 0
                       END AS current_code_source
                FROM remote_repair_commit_adoption_operation_v322 operation
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN ci_repair_episode episode
                  ON episode.id = operation.ci_repair_episode_id
                JOIN remote_repair_result_normalization_due_v322 due
                  ON due.id = operation.normalization_due_id
                LEFT JOIN task_blocker blocker
                  ON blocker.id = operation.blocker_id
                LEFT JOIN task_current_stage current
                  ON current.task_id = task.id
                LEFT JOIN task_current_code_subject_fence_v322 code
                  ON code.task_id = task.id
                WHERE operation.operation_id = ?
                """, (rs, row) -> adoptionOperation(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one Remote repair adoption operation");
        }
        return rows.getFirst();
    }

    @Override
    public Optional<ResultReceipt> findResultByOperationId(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT *
                FROM remote_repair_commit_adoption_result_v322
                WHERE operation_id = ?
                """, (rs, row) -> resultReceipt(rs), operationId)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public ResultReceipt recordAdopted(
            Operation operation,
            MutationFence fence,
            Candidate candidate,
            String resultCodeFingerprint,
            String evidence,
            Instant recordedAt)
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(candidate, "candidate is null");
        requireText(resultCodeFingerprint, "resultCodeFingerprint");
        requireText(evidence, "evidence");
        requireNonNull(recordedAt, "recordedAt is null");
        ResultReceipt existing = findResultByOperationId(
                operation.operationId()).orElse(null);
        if (existing != null) {
            if (!existing.candidateHeadSha().equals(candidate.headSha())
                    || !existing.resultCodeFingerprint().equals(
                            resultCodeFingerprint)
                    || !existing.resultTreeSha().equals(
                            candidate.resultTreeSha())) {
                throw new IllegalStateException(
                        "Remote repair adoption result replay differs");
            }
            return existing;
        }
        String resultId = id("remote-repair-adoption-result", operation.id());
        jdbc.update("""
                INSERT INTO remote_repair_commit_adoption_result_v322(
                    id, adoption_operation_row_id, operation_id, task_id,
                    task_epoch, remote_development_stage_id, stage_generation,
                    worktree_path, expected_branch_name,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, candidate_capture_kind,
                    candidate_code_fingerprint, candidate_head_sha,
                    candidate_parent_sha, candidate_source_tree_sha,
                    candidate_result_tree_sha, candidate_branch_name,
                    candidate_base_merge_base_sha,
                    candidate_source_head_merge_base_sha, result_clean,
                    git_operation_state_clear, writer_fencing_token,
                    evidence, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, 1, 1, ?, ?, ?)
                """, resultId, operation.id(), operation.operationId(),
                operation.taskId(), operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.worktreePath(),
                operation.expectedBranchName(),
                operation.sourceCodeFingerprint(), operation.sourceHeadSha(),
                operation.expectedBaseSha(), captureKind(operation),
                resultCodeFingerprint, candidate.headSha(),
                operation.sourceHeadSha(), candidate.sourceTreeSha(),
                candidate.resultTreeSha(), operation.expectedBranchName(),
                operation.expectedBaseSha(), operation.sourceHeadSha(),
                fence.fencingToken(), evidence, recordedAt.toEpochMilli());
        return findResultByOperationId(operation.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Remote repair adoption result is missing"));
    }

    @Transactional
    public AdoptionCompletion finishAdoption(
            Operation operation,
            AdoptionResult result,
            String rawOutcome,
            String rawDigest,
            String status,
            DispatchTicket.Acceptance acceptance,
            String evidence,
            String error,
            Instant at)
    {
        requireNonNull(operation, "operation is null");
        requireText(rawOutcome, "rawOutcome");
        requireText(rawDigest, "rawDigest");
        requireText(status, "status");
        requireNonNull(acceptance, "acceptance is null");
        requireText(evidence, "evidence");
        requireNonNull(at, "at is null");
        ResultReceipt adopted = "SUCCEEDED".equals(status)
                ? findResultByOperationId(operation.operationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Successful Remote repair adoption lacks proof"))
                : null;
        if (adopted != null
                && (result == null
                    || !adopted.id().equals(result.resultReceiptId())
                    || !adopted.candidateHeadSha().equals(
                            result.candidateHeadSha())
                    || !adopted.resultCodeFingerprint().equals(
                            result.resultCodeFingerprint()))) {
            throw new IllegalArgumentException(
                    "Remote repair adoption delivery differs from its proof");
        }
        updateOne("""
                UPDATE remote_repair_commit_adoption_operation_v322
                SET status = ?, result_id = ?, completed_at_ms = ?,
                    error_message = ?
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                """, "Remote repair adoption changed before delivery",
                status, adopted == null ? null : adopted.id(),
                at.toEpochMilli(), error, operation.id(),
                operation.operationId());
        jdbc.update("""
                INSERT INTO remote_repair_commit_adoption_delivery_v322(
                    adoption_operation_row_id, operation_id, result_id,
                    raw_outcome, raw_result_digest, acceptance, evidence,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, operation.id(), operation.operationId(),
                adopted == null ? null : adopted.id(), rawOutcome, rawDigest,
                acceptance.name(), evidence, at.toEpochMilli());
        if (adopted == null) {
            closeAuthorization(
                    sourceAuthorizationId(operation.id()),
                    error == null ? evidence : error, at);
            return new AdoptionCompletion(
                    episodeId(operation.id()), false, null);
        }

        insertAdoptedSubjects(operation, adopted, at);
        updateOne("""
                UPDATE ci_repair_episode
                SET fix_attempt_count = fix_attempt_count + 1
                WHERE id = ? AND status = 'FIXING'
                  AND fix_attempt_count = (
                      SELECT due.semantic_attempt - 1
                      FROM remote_repair_commit_adoption_operation_v322 adoption
                      JOIN remote_repair_result_normalization_due_v322 due
                        ON due.id = adoption.normalization_due_id
                      WHERE adoption.id = ?)
                """, "CI fix budget changed before normalized adoption",
                episodeId(operation.id()), operation.id());
        updateOne("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = ? AND task_id = ? AND stage_id = ?
                  AND owner_kind = 'EPISODE' AND owner_id = ?
                  AND blocker_type = 'CI_REPAIR_OUTPUT_MALFORMED'
                  AND status = 'OPEN'
                """, "Malformed CI repair blocker changed before adoption",
                at.toEpochMilli(), evidence, blockerId(operation.id()),
                operation.taskId(), operation.stageId(),
                episodeId(operation.id()));
        return new AdoptionCompletion(
                episodeId(operation.id()), true,
                sourceAuthorizationId(operation.id()));
    }

    private void insertAdoptedSubjects(
            Operation operation, ResultReceipt result, Instant at)
    {
        updateOne("""
                INSERT INTO remote_code_subject(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, stage_turn_id,
                    source_code_fingerprint, source_head_sha, source_base_sha,
                    code_fingerprint, head_sha, base_sha, created_at_ms)
                SELECT ?, adoption.remote_development_stage_id,
                       adoption.task_id, adoption.task_epoch,
                       adoption.stage_generation, adoption.source_stage_turn_id,
                       adoption.expected_code_fingerprint,
                       adoption.expected_head_sha, adoption.expected_base_sha,
                       result.candidate_code_fingerprint,
                       result.candidate_head_sha, adoption.expected_base_sha, ?
                FROM remote_repair_commit_adoption_operation_v322 adoption
                JOIN remote_repair_commit_adoption_result_v322 result
                  ON result.id = adoption.result_id
                WHERE adoption.id = ? AND adoption.operation_id = ?
                """, "Adopted Remote repair code subject was not recorded",
                id("remote-code-subject", operation.operationId()),
                at.toEpochMilli(), operation.id(), operation.operationId());
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM remote_worktree_subject
                WHERE task_id = ? AND task_epoch = ?
                """, Integer.class, operation.taskId(), operation.taskEpoch());
        updateOne("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha,
                    base_sha, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'CI_STAGE_TURN', ?, ?, ?, ?, ?)
                """, "Adopted Remote repair worktree subject was not recorded",
                id("remote-worktree-subject", operation.operationId()),
                operation.taskId(), operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), revision,
                operation.operationId(), result.resultCodeFingerprint(),
                result.candidateHeadSha(), operation.expectedBaseSha(),
                at.toEpochMilli());
    }

    private String episodeId(String adoptionRowId)
    {
        List<String> rows = jdbc.query("""
                SELECT ci_repair_episode_id
                FROM remote_repair_commit_adoption_operation_v322
                WHERE id = ?
                """, (rs, row) -> rs.getString(1), adoptionRowId);
        return requireSingleLineageValue(rows);
    }

    private String blockerId(String adoptionRowId)
    {
        List<String> rows = jdbc.query("""
                SELECT blocker_id
                FROM remote_repair_commit_adoption_operation_v322
                WHERE id = ?
                """, (rs, row) -> rs.getString(1), adoptionRowId);
        return requireSingleLineageValue(rows);
    }

    private String sourceAuthorizationId(String adoptionRowId)
    {
        List<String> rows = jdbc.query("""
                SELECT source_base_repair_authorization_id
                FROM remote_repair_commit_adoption_operation_v322
                WHERE id = ?
                """, (rs, row) -> rs.getString(1), adoptionRowId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Remote repair adoption authorization lineage is missing");
        }
        return rows.getFirst();
    }

    private static String requireSingleLineageValue(List<String> rows)
    {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Remote repair adoption lineage is missing");
        }
        return rows.getFirst();
    }

    private void closeAuthorization(
            String authorizationId, String evidence, Instant at)
    {
        if (authorizationId == null) {
            return;
        }
        requireText(evidence, "authorization evidence");
        int original = jdbc.update("""
                UPDATE ci_base_repair_authorization_v303
                SET status = 'CLOSED', terminal_at_ms = ?,
                    terminal_evidence = ?
                WHERE id = ? AND status = 'CLAIMED'
                """, at.toEpochMilli(), evidence, authorizationId);
        if (original == 0) {
            jdbc.update("""
                    UPDATE ci_base_repair_reauthorization_v322
                    SET status = 'CLOSED', terminal_at_ms = ?,
                        terminal_evidence = ?
                    WHERE source_authorization_id = ? AND status = 'CLAIMED'
                    """, at.toEpochMilli(), evidence, authorizationId);
        }
    }

    private static String captureKind(Operation operation)
    {
        return operation.candidateHeadSha() == null
                ? "LEGACY_REFLOG_WINDOW_V1"
                : "FROZEN_WRITER_PROOF_V1";
    }

    private NormalizationDue requireDue(String dueId)
    {
        List<NormalizationDue> rows = jdbc.query(DUE_SELECT + """
                WHERE due.id = ?
                """, (rs, row) -> normalizationDue(rs), dueId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one Remote repair normalization due");
        }
        return rows.getFirst();
    }

    private void updateOne(
            String sql, String failure, Object... arguments)
    {
        if (jdbc.update(sql, arguments) != 1) {
            throw new IllegalStateException(failure);
        }
    }

    private static NormalizationDue normalizationDue(ResultSet rs)
            throws SQLException
    {
        return new NormalizationDue(
                rs.getString("id"), rs.getString("ci_repair_episode_id"),
                rs.getString("source_operation_row_id"),
                rs.getString("source_operation_id"),
                rs.getString("source_stage_turn_id"),
                rs.getString("source_dispatch_ticket_id"),
                rs.getString("source_agent_execution_id"),
                rs.getString("source_base_repair_authorization_id"),
                rs.getString("blocker_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"),
                rs.getInt("execution_attempt"),
                rs.getLong("source_code_subject_revision"),
                rs.getString("source_code_subject_kind"),
                rs.getString("source_code_subject_id"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("source_malformed_output"),
                rs.getString("source_raw_result_digest"),
                rs.getString("required_result_shape"),
                rs.getString("candidate_capture_kind"),
                rs.getString("candidate_code_fingerprint"),
                rs.getString("candidate_head_sha"),
                rs.getString("candidate_parent_sha"),
                rs.getString("candidate_base_sha"),
                nullableBoolean(rs, "candidate_clean"),
                rs.getString("candidate_merge_base_sha"),
                rs.getString("candidate_source_tree_sha"),
                rs.getString("candidate_result_tree_sha"),
                rs.getString("candidate_source_head_merge_base_sha"),
                rs.getString("candidate_branch_name"),
                instant(rs, "source_execution_started_at_ms"),
                instant(rs, "source_execution_finished_at_ms"),
                rs.getString("source_launch_input"),
                rs.getString("delivery_lane"), rs.getInt("lane_mask"),
                rs.getString("worktree_path"), rs.getString("branch_name"),
                rs.getString("trunk_id"), rs.getString("workspace_id"),
                instant(rs, "recorded_at_ms"),
                rs.getInt("is_current") == 1);
    }

    private static NormalizationOperation normalizationOperation(
            ResultSet rs, NormalizationDue due)
            throws SQLException
    {
        return new NormalizationOperation(
                rs.getString("id"), rs.getString("normalization_due_id"),
                rs.getString("source_operation_row_id"),
                rs.getString("source_operation_id"),
                rs.getString("source_stage_turn_id"),
                rs.getString("ci_repair_episode_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("normalization_task_turn_id"),
                rs.getString("operation_id"),
                rs.getString("dispatch_ticket_id"),
                rs.getInt("semantic_attempt"),
                rs.getInt("source_execution_attempt"),
                rs.getInt("normalization_attempt"),
                rs.getLong("source_code_subject_revision"),
                rs.getString("source_code_subject_kind"),
                rs.getString("source_code_subject_id"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"), rs.getString("status"),
                due, instant(rs, "requested_at_ms"));
    }

    private static Operation adoptionOperation(ResultSet rs)
            throws SQLException
    {
        return new Operation(
                rs.getString("id"),
                rs.getString("normalization_operation_row_id"),
                rs.getString("source_operation_id"),
                rs.getString("operation_id"),
                rs.getString("dispatch_ticket_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("remote_development_stage_id"),
                rs.getLong("stage_generation"),
                rs.getInt("adoption_attempt"),
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("worktree_path"),
                rs.getString("expected_branch_name"),
                rs.getLong("source_code_subject_revision"),
                rs.getString("source_code_subject_kind"),
                rs.getString("source_code_subject_id"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("candidate_head_sha"),
                rs.getString("candidate_code_fingerprint"),
                rs.getString("candidate_source_tree_sha"),
                rs.getString("candidate_result_tree_sha"),
                instant(rs, "source_execution_started_at_ms"),
                instant(rs, "source_execution_finished_at_ms"),
                rs.getString("status"),
                rs.getInt("current_ci_episode_fixing") == 1,
                rs.getInt("current_malformed_blocker_open") == 1,
                rs.getInt("current_code_source") == 1,
                rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"),
                rs.getString("current_stage_id"),
                nullableLong(rs, "current_stage_generation"),
                rs.getString("current_code_fingerprint"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"));
    }

    private static ResultReceipt resultReceipt(ResultSet rs)
            throws SQLException
    {
        return new ResultReceipt(
                rs.getString("id"),
                rs.getString("adoption_operation_row_id"),
                rs.getString("operation_id"),
                rs.getString("candidate_head_sha"),
                rs.getString("candidate_code_fingerprint"),
                rs.getString("candidate_result_tree_sha"),
                rs.getLong("writer_fencing_token"),
                rs.getString("evidence"), instant(rs, "recorded_at_ms"));
    }

    private static Boolean nullableBoolean(ResultSet rs, String column)
            throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    private static DispatchTicket.DeliveryReceipt normalizationDeliveryReceipt(
            String status,
            String rawOutcome,
            DispatchTicket.Acceptance acceptance)
    {
        String evidence = switch (status) {
            case "SUCCEEDED" -> "Remote repair commit adoption requested";
            case "FAILED" -> "SUCCEEDED".equals(rawOutcome)
                    ? "Remote repair result normalization failed closed"
                    : "Remote repair result normalization failed";
            case "CANCELED" ->
                    "Remote repair result normalization canceled";
            case "SUPERSEDED" ->
                    "Remote repair normalization subject is stale";
            default -> throw new IllegalStateException(
                    "Unsupported Remote repair normalization receipt status: "
                            + status);
        };
        return new DispatchTicket.DeliveryReceipt(
                acceptance, acceptance.name() + ":" + evidence);
    }

    private static DispatchTicket.DeliveryReceipt adoptionDeliveryReceipt(
            String status,
            DispatchTicket.Acceptance acceptance)
    {
        String evidence = switch (status) {
            case "SUCCEEDED" ->
                    "CI validation requested after normalized repair adoption";
            case "FAILED" -> "Remote repair adoption finished failed";
            case "CANCELED" -> "Remote repair adoption finished canceled";
            case "SUPERSEDED" ->
                    "Remote repair adoption finished superseded";
            default -> throw new IllegalStateException(
                    "Unsupported Remote repair adoption receipt status: "
                            + status);
        };
        return new DispatchTicket.DeliveryReceipt(
                acceptance, acceptance.name() + ":" + evidence);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record ReplayReceipt(
            String rawDigest,
            DispatchTicket.DeliveryReceipt deliveryReceipt)
    {
        public ReplayReceipt
        {
            requireText(rawDigest, "rawDigest");
            requireNonNull(deliveryReceipt, "deliveryReceipt is null");
        }
    }

    public record NormalizationDue(
            String id,
            String episodeId,
            String sourceOperationRowId,
            String sourceOperationId,
            String sourceStageTurnId,
            String sourceTicketId,
            String sourceExecutionId,
            String sourceBaseRepairAuthorizationId,
            String blockerId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            int executionAttempt,
            long sourceCodeSubjectRevision,
            String sourceCodeSubjectKind,
            String sourceCodeSubjectId,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String malformedOutput,
            String sourceRawResultDigest,
            String requiredResultShape,
            String candidateCaptureKind,
            String candidateCodeFingerprint,
            String candidateHeadSha,
            String candidateParentSha,
            String candidateBaseSha,
            Boolean candidateClean,
            String candidateMergeBaseSha,
            String candidateSourceTreeSha,
            String candidateResultTreeSha,
            String candidateSourceHeadMergeBaseSha,
            String candidateBranchName,
            Instant sourceExecutionStartedAt,
            Instant sourceExecutionFinishedAt,
            String sourceLaunchInput,
            String deliveryLane,
            int laneMask,
            String worktreePath,
            String branchName,
            String trunkId,
            String workspaceId,
            Instant recordedAt,
            boolean current) {}

    public record NormalizationOperation(
            String id,
            String dueId,
            String sourceOperationRowId,
            String sourceOperationId,
            String sourceStageTurnId,
            String episodeId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String turnId,
            String operationId,
            String ticketId,
            int semanticAttempt,
            int sourceExecutionAttempt,
            int normalizationAttempt,
            long sourceCodeSubjectRevision,
            String sourceCodeSubjectKind,
            String sourceCodeSubjectId,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String status,
            NormalizationDue due,
            Instant requestedAt)
    {
        public ResultFence fence()
        {
            return new ResultFence(
                    taskEpoch, stageId, stageGeneration, operationId,
                    normalizationAttempt, expectedCodeFingerprint,
                    expectedHeadSha, expectedBaseSha);
        }

        public boolean current()
        {
            return due.current();
        }
    }

    public record AdoptionCompletion(
            String episodeId,
            boolean shouldValidate,
            String authorizationId) {}
}
