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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.RepairResult;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.ResultReceipt;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.RESULT_SCHEMA_VERSION;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable command, live repair receipt, and delivery boundary for quarantine. */
@Repository
public class SqliteWorktreeQuarantineRepairStore
        implements WorktreeQuarantineRepairOperationHandler.Store
{
    private final JdbcTemplate jdbc;

    public SqliteWorktreeQuarantineRepairStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Admission request(RepairRequest request)
    {
        requireNonNull(request, "request is null");
        TaskCommandExecutor.requireCurrent(request.taskId());
        Optional<Operation> replay = findByCommandId(request.commandId());
        if (replay.isPresent()) {
            Operation operation = replay.orElseThrow();
            if (!operation.taskId().equals(request.taskId())
                    || !operation.quarantineId().equals(request.quarantineId())
                    || !operation.blockerId().equals(request.blockerId())
                    || operation.taskEpoch() != request.taskEpoch()
                    || !operation.stageId().equals(request.stageId())
                    || operation.stageGeneration() != request.stageGeneration()
                    || !operation.worktreePath().equals(request.worktreePath())
                    || !operation.expectedBranchName().equals(
                            request.expectedBranchName())
                    || !operation.expectedCodeFingerprint().equals(
                            request.expectedCodeFingerprint())
                    || !operation.expectedHeadSha().equals(
                            request.expectedHeadSha())
                    || !operation.expectedBaseSha().equals(
                            request.expectedBaseSha())
                    || !operation.actor().equals(request.actor())
                    || !operation.reason().equals(request.reason())) {
                throw new IllegalArgumentException(
                        "Quarantine repair command was reused for another subject");
            }
            return new Admission(operation, false);
        }

        Source source = requireSource(request);
        int attempt = jdbc.queryForObject("""
                SELECT COUNT(*) + 1
                  FROM worktree_quarantine_repair_operation_v318
                 WHERE quarantine_id = ?
                """, Integer.class, source.quarantineId());
        String rowId = id("worktree-quarantine-repair-row", request.commandId());
        String operationId = id(
                "worktree-quarantine-repair-operation", request.commandId());
        String ticketId = id(
                "worktree-quarantine-repair-ticket", request.commandId());
        jdbc.update("""
                INSERT INTO worktree_quarantine_repair_operation_v318(
                    id, quarantine_id, blocker_id, task_id, task_epoch,
                    stage_id, stage_generation, source_operation_id,
                    operation_id, dispatch_ticket_id, attempt, command_id,
                    actor, reason, worktree_path,
                    expected_branch_name,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, source.quarantineId(), source.blockerId(),
                source.taskId(), source.taskEpoch(), source.stageId(),
                source.stageGeneration(), source.sourceOperationId(),
                operationId, ticketId, attempt, request.commandId(),
                request.actor(), request.reason(), source.worktreePath(),
                source.expectedBranchName(),
                source.expectedCodeFingerprint(), source.expectedHeadSha(),
                source.expectedBaseSha(), request.requestedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'REPAIR_QUARANTINED_WORKTREE', 'LOCAL_GIT',
                    'TASK', ?, 'WORKTREE_QUARANTINE_REPAIR_RESULT', 16,
                    0, 1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, source.taskId(),
                source.workspaceId(), source.trunkId(), source.taskId(),
                source.taskEpoch(), source.stageId(), source.stageGeneration(),
                attempt, source.expectedCodeFingerprint(),
                source.expectedHeadSha(), source.expectedBaseSha(),
                request.requestedAt().toEpochMilli());
        updateOne("""
                UPDATE worktree_quarantine_repair_operation_v318
                   SET status = 'DISPATCHED'
                 WHERE id = ? AND status = 'REQUESTED'
                """, "Quarantine repair was not dispatched", rowId);
        return new Admission(requireByOperationId(operationId), true);
    }

    @Override
    public Operation requireByOperationId(String operationId)
    {
        requireText(operationId, "operationId");
        List<Operation> rows = jdbc.query(operationSelect() + """
                 WHERE operation.operation_id = ?
                """, (rs, row) -> operation(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException(
                    "Expected one quarantine repair Operation, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public String requireTaskId(String operationId)
    {
        return requireByOperationId(operationId).taskId();
    }

    @Override
    public ResultReceipt recordRestored(
            Operation operation,
            MutationFence fence,
            String resultBranchName,
            String resultCodeFingerprint,
            String resultHeadSha,
            String evidence,
            Instant recordedAt)
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(fence, "fence is null");
        requireText(resultBranchName, "resultBranchName");
        requireText(resultCodeFingerprint, "resultCodeFingerprint");
        requireText(resultHeadSha, "resultHeadSha");
        requireText(evidence, "evidence");
        requireNonNull(recordedAt, "recordedAt is null");
        Optional<ResultReceipt> replay = findResultByOperationId(
                operation.operationId());
        if (replay.isPresent()) {
            ResultReceipt result = replay.orElseThrow();
            if (!result.repairOperationId().equals(operation.id())
                    || !result.quarantineId().equals(operation.quarantineId())
                    || !result.resultBranchName().equals(resultBranchName)
                    || !result.resultCodeFingerprint().equals(
                            resultCodeFingerprint)
                    || !result.resultHeadSha().equals(resultHeadSha)
                    || !result.resultClean()
                    || !result.gitOperationStateClear()) {
                throw new IllegalStateException(
                        "Quarantine repair result changed on replay");
            }
            // A dispatcher crash after this immutable receipt was inserted but
            // before its DispatchResult was persisted is reconciled under a
            // fresh Capacity/Writer fence. The handler has just re-proved the
            // same HEAD, clean state, and fingerprint under that new fence;
            // the receipt itself remains the original durable proof.
            requireLiveReplayFence(operation, fence, recordedAt);
            return result;
        }
        String resultId = id(
                "worktree-quarantine-repair-result", operation.operationId());
        jdbc.update("""
                INSERT INTO worktree_quarantine_repair_result_v318(
                    id, repair_operation_id, quarantine_id, operation_id,
                    task_id, task_epoch, stage_id, stage_generation,
                    worktree_path, expected_branch_name,
                    expected_code_fingerprint,
                    expected_head_sha, result_code_fingerprint,
                    result_head_sha, result_branch_name, result_clean,
                    git_operation_state_clear, writer_fencing_token,
                    evidence, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1,
                    ?, ?, ?)
                """, resultId, operation.id(), operation.quarantineId(),
                operation.operationId(), operation.taskId(),
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.worktreePath(),
                operation.expectedBranchName(),
                operation.expectedCodeFingerprint(),
                operation.expectedHeadSha(), resultCodeFingerprint,
                resultHeadSha, resultBranchName, fence.fencingToken(), evidence,
                recordedAt.toEpochMilli());
        return findResultByOperationId(operation.operationId()).orElseThrow();
    }

    @Override
    public Optional<ResultReceipt> findResultByOperationId(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT id, repair_operation_id, operation_id, quarantine_id,
                       result_code_fingerprint, result_head_sha, result_clean,
                       result_branch_name, git_operation_state_clear,
                       writer_fencing_token, evidence, recorded_at_ms
                  FROM worktree_quarantine_repair_result_v318
                 WHERE operation_id = ?
                """, (rs, row) -> result(rs), operationId)
                .stream().findFirst();
    }

    /** Atomically proves the fresh replay fence against all live owners. */
    private void requireLiveReplayFence(
            Operation operation, MutationFence fence, Instant recordedAt)
    {
        if (!operation.worktreePath().equals(fence.worktreePath())
                || !operation.taskId().equals(fence.taskId())
                || !operation.operationId().equals(fence.operationId())
                || operation.taskEpoch() != fence.taskEpoch()) {
            throw new IllegalStateException(
                    "Quarantine repair receipt replay has the wrong mutation fence");
        }
        Integer live = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM worktree_quarantine_repair_operation_v318 operation
                  JOIN agent_turn_worktree_quarantine_v318 quarantine
                    ON quarantine.id = operation.quarantine_id
                  JOIN task_blocker blocker
                    ON blocker.id = operation.blocker_id
                  JOIN dispatch_ticket ticket
                    ON ticket.id = operation.dispatch_ticket_id
                   AND ticket.operation_id = operation.operation_id
                  JOIN capacity_lease capacity
                    ON capacity.id = ticket.capacity_lease_id
                   AND capacity.ticket_id = ticket.id
                   AND capacity.operation_id = operation.operation_id
                  JOIN worktree_leases lease
                    ON lease.operation_id = operation.operation_id
                   AND lease.task_id = operation.task_id
                   AND lease.task_epoch = operation.task_epoch
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN threads trunk ON trunk.id = task.thread_id
                  JOIN task_current_stage current
                    ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                  JOIN task_code_identity identity
                    ON identity.task_id = task.id
                 WHERE operation.id = ?
                   AND operation.operation_id = ?
                   AND operation.quarantine_id = ?
                   AND operation.task_id = ?
                   AND operation.task_epoch = ?
                   AND operation.stage_id = ?
                   AND operation.stage_generation = ?
                   AND operation.worktree_path = ?
                   AND operation.expected_branch_name = ?
                   AND operation.expected_code_fingerprint = ?
                   AND operation.expected_head_sha = ?
                   AND operation.expected_base_sha = ?
                   AND operation.status = 'DISPATCHED'
                   AND quarantine.status = 'OPEN'
                   AND quarantine.task_id = operation.task_id
                   AND quarantine.worktree_path = operation.worktree_path
                   AND quarantine.expected_branch_name =
                       operation.expected_branch_name
                   AND quarantine.expected_code_fingerprint =
                       operation.expected_code_fingerprint
                   AND quarantine.expected_head_sha =
                       operation.expected_head_sha
                   AND blocker.task_id = operation.task_id
                   AND blocker.stage_id = quarantine.stage_id
                   AND blocker.owner_kind = 'OPERATION'
                   AND blocker.owner_id = operation.source_operation_id
                   AND blocker.subject_revision = quarantine.id
                   AND blocker.blocker_type = 'WORKTREE_RESTORE_QUARANTINED'
                   AND blocker.status = 'OPEN'
                   AND ticket.operation_kind =
                       'REPAIR_QUARANTINED_WORKTREE'
                   AND ticket.async_family = 'LOCAL_GIT'
                   AND ticket.owner_kind = 'TASK'
                   AND ticket.owner_id = operation.task_id
                   AND ticket.callback_route =
                       'WORKTREE_QUARANTINE_REPAIR_RESULT'
                   AND ticket.lane_mask = 16
                   AND ticket.trunk_control = 0
                   AND ticket.exclusive_task = 1
                   AND ticket.writer_required = 1
                   AND ticket.task_id = operation.task_id
                   AND ticket.task_epoch = operation.task_epoch
                   AND ticket.stage_id = operation.stage_id
                   AND ticket.stage_generation = operation.stage_generation
                   AND ticket.attempt = operation.attempt
                   AND ticket.expected_code_fingerprint =
                       operation.expected_code_fingerprint
                   AND ticket.expected_head_sha = operation.expected_head_sha
                   AND ticket.expected_base_sha = operation.expected_base_sha
                   AND ticket.status = 'RUNNING'
                   AND capacity.workflow_source = 'V2'
                   AND capacity.workspace_id = ?
                   AND capacity.trunk_id = ?
                   AND capacity.task_id = operation.task_id
                   AND capacity.task_epoch = operation.task_epoch
                   AND capacity.lane_mask = 16
                   AND capacity.trunk_control = 0
                   AND capacity.exclusive_task = 1
                   AND capacity.writer_required = 1
                   AND capacity.fencing_token = ?
                   AND capacity.released_at_ms IS NULL
                   AND capacity.expires_at_ms > ?
                   AND lease.workflow_version = 'V2'
                   AND lease.worktree_path = operation.worktree_path
                   AND lease.fencing_token = ?
                   AND lease.lease_owner = capacity.holder
                   AND lease.expires_at_ms > ?
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND task.epoch = operation.task_epoch
                   AND task.thread_id = ?
                   AND trunk.workspace_id = ?
                   AND current.stage_id = operation.stage_id
                   AND current.stage_generation = operation.stage_generation
                   AND owner.task_id = task.id
                   AND owner.generation = operation.stage_generation
                   AND owner.completed_at_ms IS NULL
                   AND code.code_fingerprint =
                       operation.expected_code_fingerprint
                   AND code.head_sha = operation.expected_head_sha
                   AND code.base_sha = operation.expected_base_sha
                   AND identity.worktree_path = operation.worktree_path
                   AND identity.branch_name = operation.expected_branch_name
                """, Integer.class, operation.id(), operation.operationId(),
                operation.quarantineId(), operation.taskId(),
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.worktreePath(),
                operation.expectedBranchName(),
                operation.expectedCodeFingerprint(),
                operation.expectedHeadSha(), operation.expectedBaseSha(),
                operation.workspaceId(), operation.trunkId(),
                fence.fencingToken(), recordedAt.toEpochMilli(),
                fence.fencingToken(), recordedAt.toEpochMilli(),
                operation.trunkId(), operation.workspaceId());
        if (!Integer.valueOf(1).equals(live)) {
            throw new IllegalStateException(
                    "Quarantine repair receipt replay lacks exact live proof");
        }
    }

    public DeliveryReceipt deliver(DeliveryRequest request)
    {
        requireNonNull(request, "request is null");
        Operation operation = requireByOperationId(request.operationId());
        TaskCommandExecutor.requireCurrent(operation.taskId());
        Optional<DeliveryReceipt> replay = findDelivery(operation.id());
        if (replay.isPresent()) {
            DeliveryReceipt receipt = replay.orElseThrow();
            if (!receipt.rawResultDigest().equals(request.rawResultDigest())
                    || receipt.rawOutcome() != request.rawOutcome()) {
                throw new IllegalStateException(
                        "Quarantine repair delivery changed on replay");
            }
            return receipt;
        }
        if (!"DISPATCHED".equals(operation.status())) {
            throw new IllegalStateException(
                    "Quarantine repair Operation is not deliverable");
        }
        validateResultShape(operation, request);

        boolean current = operation.currentOwner();
        String terminalStatus;
        DispatchTicket.Acceptance acceptance;
        String resultId = null;
        if (!current) {
            terminalStatus = "SUPERSEDED";
            acceptance = DispatchTicket.Acceptance.SUPERSEDED;
            resultId = request.rawOutcome() == DispatchTicket.Outcome.SUCCEEDED
                    ? requireExactRestored(operation, request.result()).id()
                    : null;
        }
        else {
            acceptance = DispatchTicket.Acceptance.ACCEPTED;
            terminalStatus = switch (request.rawOutcome()) {
                case SUCCEEDED -> {
                    resultId = requireExactRestored(
                            operation, request.result()).id();
                    yield "SUCCEEDED";
                }
                case FAILED -> "FAILED";
                case CANCELED -> "CANCELED";
                case INDETERMINATE -> throw new IllegalArgumentException(
                        "Indeterminate quarantine repair must reconcile first");
            };
        }
        updateOne("""
                UPDATE worktree_quarantine_repair_operation_v318
                   SET status = ?, completed_at_ms = ?, error_message = ?
                 WHERE id = ? AND status = 'DISPATCHED'
                """, "Quarantine repair terminalization changed",
                terminalStatus, request.recordedAt().toEpochMilli(),
                request.error(), operation.id());
        String evidence = "worktree quarantine repair "
                + acceptance.name().toLowerCase(Locale.ROOT) + ": "
                + terminalStatus;
        jdbc.update("""
                INSERT INTO worktree_quarantine_repair_delivery_v318(
                    repair_operation_id, operation_id, result_id,
                    raw_outcome, raw_result_digest, acceptance, evidence,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, operation.id(), operation.operationId(), resultId,
                request.rawOutcome().name(), request.rawResultDigest(),
                acceptance.name(), evidence, request.recordedAt().toEpochMilli());
        if ("SUCCEEDED".equals(terminalStatus)) {
            updateOne("""
                    UPDATE agent_turn_worktree_quarantine_v318
                       SET status = 'CLEARED',
                           cleared_by_repair_operation_id = ?,
                           cleared_at_ms = ?, clear_evidence = ?
                     WHERE id = ? AND status = 'OPEN'
                    """, "Accepted quarantine repair did not clear quarantine",
                    operation.id(), request.recordedAt().toEpochMilli(),
                    evidence, operation.quarantineId());
        }
        return findDelivery(operation.id()).orElseThrow();
    }

    public Optional<DeliveryReceipt> findDelivery(String repairOperationId)
    {
        requireText(repairOperationId, "repairOperationId");
        return jdbc.query("""
                SELECT repair_operation_id, operation_id, result_id,
                       raw_outcome, raw_result_digest, acceptance, evidence,
                       recorded_at_ms
                  FROM worktree_quarantine_repair_delivery_v318
                 WHERE repair_operation_id = ?
                """, (rs, row) -> delivery(rs), repairOperationId)
                .stream().findFirst();
    }

    private ResultReceipt requireExactRestored(
            Operation operation, RepairResult raw)
    {
        requireNonNull(raw, "successful repair result is null");
        ResultReceipt result = findResultByOperationId(operation.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Successful repair lacks its live writer receipt"));
        boolean exact = raw.schemaVersion() == RESULT_SCHEMA_VERSION
                && raw.disposition()
                == WorktreeQuarantineRepairOperationHandler.Disposition.RESTORED
                && operation.id().equals(raw.repairOperationId())
                && operation.operationId().equals(raw.operationId())
                && operation.quarantineId().equals(raw.quarantineId())
                && result.id().equals(raw.resultReceiptId())
                && operation.expectedBranchName().equals(
                        raw.expectedBranchName())
                && operation.expectedCodeFingerprint().equals(
                        raw.expectedCodeFingerprint())
                && operation.expectedHeadSha().equals(raw.expectedHeadSha())
                && operation.expectedBaseSha().equals(raw.expectedBaseSha())
                && result.resultCodeFingerprint().equals(
                        raw.resultCodeFingerprint())
                && result.resultBranchName().equals(raw.resultBranchName())
                && result.resultHeadSha().equals(raw.resultHeadSha())
                && Boolean.TRUE.equals(raw.resultClean())
                && Boolean.TRUE.equals(raw.gitOperationStateClear())
                && Long.valueOf(result.writerFencingToken()).equals(
                        raw.writerFencingToken())
                && result.evidence().equals(raw.evidence());
        if (!exact) {
            throw new IllegalArgumentException(
                    "Successful quarantine repair proof is not exact");
        }
        return result;
    }

    private static void validateResultShape(
            Operation operation, DeliveryRequest request)
    {
        RepairResult result = requireNonNull(
                request.result(), "repair result is null");
        boolean exactSubject = result.schemaVersion() == RESULT_SCHEMA_VERSION
                && operation.id().equals(result.repairOperationId())
                && operation.operationId().equals(result.operationId())
                && operation.quarantineId().equals(result.quarantineId())
                && operation.expectedBranchName().equals(
                        result.expectedBranchName())
                && operation.expectedCodeFingerprint().equals(
                        result.expectedCodeFingerprint())
                && operation.expectedHeadSha().equals(
                        result.expectedHeadSha())
                && operation.expectedBaseSha().equals(
                        result.expectedBaseSha());
        if (!exactSubject) {
            throw new IllegalArgumentException(
                    "Quarantine repair result has the wrong schema or subject");
        }

        boolean exactOutcome = switch (request.rawOutcome()) {
            case SUCCEEDED -> result.disposition()
                    == WorktreeQuarantineRepairOperationHandler.Disposition.RESTORED
                    && result.resultReceiptId() != null
                    && result.resultBranchName() != null
                    && result.resultCodeFingerprint() != null
                    && result.resultHeadSha() != null
                    && Boolean.TRUE.equals(result.resultClean())
                    && Boolean.TRUE.equals(result.gitOperationStateClear())
                    && result.writerFencingToken() != null
                    && result.evidence() != null
                    && result.error() == null
                    && request.error() == null;
            case FAILED -> (result.disposition()
                    == WorktreeQuarantineRepairOperationHandler.Disposition.FAILED
                    || result.disposition()
                    == WorktreeQuarantineRepairOperationHandler.Disposition.STALE)
                    && noReceiptProof(result)
                    && sameNonBlankError(result.error(), request.error());
            case CANCELED -> result.disposition()
                    == WorktreeQuarantineRepairOperationHandler.Disposition.CANCELED
                    && noReceiptProof(result)
                    && sameNonBlankError(result.error(), request.error());
            case INDETERMINATE -> false;
        };
        if (!exactOutcome) {
            throw new IllegalArgumentException(
                    "Quarantine repair result does not match its raw outcome");
        }
    }

    private static boolean noReceiptProof(RepairResult result)
    {
        return result.resultReceiptId() == null
                && result.writerFencingToken() == null
                && result.evidence() == null;
    }

    private static boolean sameNonBlankError(String result, String raw)
    {
        return result != null && !result.isBlank() && result.equals(raw);
    }

    private Optional<Operation> findByCommandId(String commandId)
    {
        requireText(commandId, "commandId");
        return jdbc.query(operationSelect() + """
                 WHERE operation.command_id = ?
                """, (rs, row) -> operation(rs), commandId)
                .stream().findFirst();
    }

    private Source requireSource(RepairRequest request)
    {
        List<Source> rows = jdbc.query("""
                SELECT quarantine.id AS quarantine_id,
                       quarantine.source_operation_id,
                       quarantine.worktree_path,
                       quarantine.expected_branch_name,
                       quarantine.expected_code_fingerprint,
                       quarantine.expected_head_sha,
                       blocker.id AS blocker_id, task.id AS task_id,
                       task.epoch AS task_epoch, task.thread_id AS trunk_id,
                       trunk.workspace_id, owner.id AS stage_id,
                       owner.generation AS stage_generation,
                       code.base_sha AS expected_base_sha
                  FROM agent_turn_worktree_quarantine_v318 quarantine
                  JOIN task_blocker blocker ON blocker.id = ?
                  JOIN tasks task ON task.id = quarantine.task_id
                  JOIN threads trunk ON trunk.id = task.thread_id
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                  JOIN task_code_identity identity ON identity.task_id = task.id
                 WHERE quarantine.id = ? AND quarantine.task_id = ?
                   AND quarantine.status = 'OPEN'
                   AND blocker.task_id = task.id
                   AND blocker.stage_id = quarantine.stage_id
                   AND blocker.owner_kind = 'OPERATION'
                   AND blocker.owner_id = quarantine.source_operation_id
                   AND blocker.subject_revision = quarantine.id
                   AND blocker.blocker_type =
                       'WORKTREE_RESTORE_QUARANTINED'
                   AND blocker.status = 'OPEN'
                   AND task.workflow_version = 'V2'
                   AND task.lifecycle_state = 'ACTIVE'
                   AND task.epoch = ?
                   AND owner.id = ?
                   AND owner.generation = ?
                   AND quarantine.worktree_path = ?
                   AND quarantine.expected_branch_name = ?
                   AND quarantine.expected_code_fingerprint = ?
                   AND quarantine.expected_head_sha = ?
                   AND code.base_sha = ?
                   AND current.stage_generation = owner.generation
                   AND owner.completed_at_ms IS NULL
                   AND code.code_fingerprint =
                       quarantine.expected_code_fingerprint
                   AND code.head_sha = quarantine.expected_head_sha
                   AND identity.worktree_path = quarantine.worktree_path
                   AND identity.branch_name = quarantine.expected_branch_name
                   AND NOT EXISTS (
                       SELECT 1
                         FROM worktree_quarantine_repair_operation_v318 live
                        WHERE live.quarantine_id = quarantine.id
                          AND live.status IN ('REQUESTED', 'DISPATCHED'))
                """, (rs, row) -> source(rs), request.blockerId(),
                request.quarantineId(), request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageGeneration(),
                request.worktreePath(), request.expectedBranchName(),
                request.expectedCodeFingerprint(), request.expectedHeadSha(),
                request.expectedBaseSha());
        if (rows.size() != 1) {
            throw new IllegalArgumentException(
                    "Quarantine repair does not own the exact open blocker");
        }
        return rows.getFirst();
    }

    private static String operationSelect()
    {
        return """
                SELECT operation.id, operation.quarantine_id,
                       operation.blocker_id, operation.task_id,
                       operation.task_epoch, operation.stage_id,
                       operation.stage_generation,
                       operation.source_operation_id,
                       operation.operation_id, operation.dispatch_ticket_id,
                       operation.attempt, operation.command_id,
                       operation.actor, operation.reason,
                       operation.worktree_path,
                       operation.expected_branch_name,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha, operation.status,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       code.code_fingerprint AS current_code_fingerprint,
                       code.head_sha AS current_head_sha,
                       code.base_sha AS current_base_sha,
                       identity.branch_name AS current_branch_name,
                       quarantine.status AS quarantine_status,
                       blocker.status AS blocker_status
                  FROM worktree_quarantine_repair_operation_v318 operation
                  JOIN tasks task ON task.id = operation.task_id
                  JOIN threads trunk ON trunk.id = task.thread_id
                  LEFT JOIN task_current_stage current
                    ON current.task_id = task.id
                  LEFT JOIN task_current_code_subject_v230 code
                    ON code.task_id = task.id
                  LEFT JOIN task_code_identity identity
                    ON identity.task_id = task.id
                  JOIN agent_turn_worktree_quarantine_v318 quarantine
                    ON quarantine.id = operation.quarantine_id
                  JOIN task_blocker blocker ON blocker.id = operation.blocker_id
                """;
    }

    private static Operation operation(ResultSet rs)
            throws SQLException
    {
        long generation = rs.getLong("current_stage_generation");
        Long currentGeneration = rs.wasNull() ? null : generation;
        return new Operation(
                rs.getString("id"), rs.getString("quarantine_id"),
                rs.getString("blocker_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("source_operation_id"),
                rs.getString("operation_id"),
                rs.getString("dispatch_ticket_id"), rs.getInt("attempt"),
                rs.getString("command_id"), rs.getString("actor"),
                rs.getString("reason"),
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("worktree_path"),
                rs.getString("expected_branch_name"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"), rs.getString("status"),
                rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"),
                rs.getString("current_stage_id"), currentGeneration,
                rs.getString("current_code_fingerprint"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"),
                rs.getString("current_branch_name"),
                rs.getString("quarantine_status"),
                rs.getString("blocker_status"));
    }

    private static Source source(ResultSet rs)
            throws SQLException
    {
        return new Source(
                rs.getString("quarantine_id"),
                rs.getString("source_operation_id"),
                rs.getString("blocker_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("worktree_path"),
                rs.getString("expected_branch_name"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"));
    }

    private static ResultReceipt result(ResultSet rs)
            throws SQLException
    {
        return new ResultReceipt(
                rs.getString("id"), rs.getString("repair_operation_id"),
                rs.getString("operation_id"), rs.getString("quarantine_id"),
                rs.getString("result_code_fingerprint"),
                rs.getString("result_head_sha"),
                rs.getString("result_branch_name"),
                rs.getInt("result_clean") != 0,
                rs.getInt("git_operation_state_clear") != 0,
                rs.getLong("writer_fencing_token"), rs.getString("evidence"),
                Instant.ofEpochMilli(rs.getLong("recorded_at_ms")));
    }

    private static DeliveryReceipt delivery(ResultSet rs)
            throws SQLException
    {
        return new DeliveryReceipt(
                rs.getString("repair_operation_id"),
                rs.getString("operation_id"), rs.getString("result_id"),
                DispatchTicket.Outcome.valueOf(rs.getString("raw_outcome")),
                rs.getString("raw_result_digest"),
                DispatchTicket.Acceptance.valueOf(rs.getString("acceptance")),
                rs.getString("evidence"),
                Instant.ofEpochMilli(rs.getLong("recorded_at_ms")));
    }

    private void updateOne(String sql, String message, Object... args)
    {
        if (jdbc.update(sql, args) != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    public record RepairRequest(
            String taskId,
            String quarantineId,
            String blockerId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String worktreePath,
            String expectedBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String commandId,
            String actor,
            String reason,
            Instant requestedAt)
    {
        public RepairRequest
        {
            requireNonNull(requestedAt, "requestedAt is null");
        }
    }

    public record Admission(Operation operation, boolean created)
    {
    }

    public record DeliveryRequest(
            String operationId,
            DispatchTicket.Outcome rawOutcome,
            String rawResultDigest,
            RepairResult result,
            String error,
            Instant recordedAt)
    {
    }

    public record DeliveryReceipt(
            String repairOperationId,
            String operationId,
            String resultId,
            DispatchTicket.Outcome rawOutcome,
            String rawResultDigest,
            DispatchTicket.Acceptance acceptance,
            String evidence,
            Instant recordedAt)
    {
    }

    private record Source(
            String quarantineId,
            String sourceOperationId,
            String blockerId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String workspaceId,
            String trunkId,
            String worktreePath,
            String expectedBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha)
    {
    }
}
