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
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
        Optional<TaskManager.CommandReceipt> projection = queryReceipt(
                RECEIPT_SELECT + """
                        WHERE task_id = ? AND disposition = 'APPLIED'
                          AND returned_version = ?
                        """,
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
    public Optional<TaskManager.CommandReceipt> findCommandResult(
            String taskId, String commandId)
    {
        return queryReceipt(
                RECEIPT_SELECT + " WHERE task_id = ? AND command_id = ?",
                taskId, commandId);
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
        // V225 stores only opaque barrier evidence, not the typed Stage/checkpoint fence.
        return Optional.empty();
    }

    @Override
    public Optional<TaskManager.ResumeEvidence> findResumeEvidence(
            String taskId, String reconciliationId)
    {
        // The typed resume-reconciliation protocol is intentionally deferred.
        return Optional.empty();
    }

    @Override
    public Optional<TaskManager.ArchiveEvidence> findArchiveEvidence(
            String taskId, String archiveEvidenceId)
    {
        // The typed archive-liveness protocol is intentionally deferred.
        return Optional.empty();
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
        recordTerminalIntent(cause, proofId, resultFence, expected, updated);
        recordTransition(commandId, cause, actor, expected, updated);
        insertReceipt(
                commandId, cause, actor, CommandResult.Disposition.APPLIED,
                expectedEpoch, expectedVersion, resultFence, brainVerdict, proofId,
                nextStageId, nextStageKind, nextStageGeneration, updated);
        return updated;
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
                id(), updated.id(), updated.terminalIntent().name(), cause, proofId,
                resultFence == null ? null : resultFence.expectedHeadSha(),
                System.currentTimeMillis());
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
            PreparedStatement statement = connection.prepareStatement(RECEIPT_INSERT);
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

    private static String id()
    {
        return UUID.randomUUID().toString();
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
