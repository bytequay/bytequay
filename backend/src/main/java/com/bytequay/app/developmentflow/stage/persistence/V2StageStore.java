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
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.LocalDevelopmentStageManager;
import com.bytequay.app.developmentflow.stage.PlanStageManager;
import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageEndReason;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.stage.StageManager;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static java.util.Objects.requireNonNull;

/** Transaction-bound persistence for all Stage kinds and their typed evidence. */
@Component
final class V2StageStore
        implements StageManager.Store,
        PlanStageManager.ApprovalStore,
        PlanStageManager.RevisionStore,
        LocalDevelopmentStageManager.EvidenceStore
{
    private static final String RECEIPT_SELECT = "SELECT * FROM stage_command_receipt";

    private static final String RECEIPT_INSERT = """
            INSERT INTO stage_command_receipt(
                id, stage_id, task_id, command_id, cause, actor, disposition,
                expected_task_epoch, expected_stage_generation,
                expected_stage_version, source_checkpoint,
                subject_task_epoch, subject_stage_id, subject_stage_generation,
                subject_operation_id, subject_attempt,
                subject_expected_code_fingerprint, subject_expected_head_sha,
                subject_expected_base_sha, proof_id, returned_kind,
                returned_generation, returned_version, returned_checkpoint,
                returned_end_reason, returned_pending_task_epoch,
                returned_pending_stage_id, returned_pending_stage_generation,
                returned_pending_operation_id, returned_pending_attempt,
                returned_pending_code_fingerprint, returned_pending_head_sha,
                returned_pending_base_sha, recorded_at_ms)
            VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    V2StageStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<StageManager.OwnerState> findOwner(String taskId, String stageId)
    {
        Optional<BaseOwner> base = jdbc.query("""
                SELECT task.id AS task_id, task.lifecycle_state, task.epoch,
                       current.stage_id AS current_stage_id,
                       stage.id AS stage_id, stage.kind, stage.generation,
                       stage.version, stage.checkpoint, stage.end_reason
                FROM stage
                JOIN tasks task ON task.id = stage.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                WHERE task.id = ? AND stage.id = ? AND task.workflow_version = 'V2'
                """,
                (rs, row) -> new BaseOwner(
                        rs.getString("task_id"),
                        TaskLifecycle.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("epoch"),
                        rs.getString("current_stage_id"),
                        rs.getString("stage_id"),
                        StageKind.valueOf(rs.getString("kind")),
                        rs.getLong("generation"),
                        rs.getLong("version"),
                        StageCheckpoint.valueOf(rs.getString("checkpoint")),
                        endReason(rs.getString("end_reason"))),
                taskId, stageId).stream().findFirst();
        if (base.isEmpty()) {
            return Optional.empty();
        }

        BaseOwner persisted = base.orElseThrow();
        Optional<StageManager.CommandReceipt> projection = queryReceipt(
                RECEIPT_SELECT
                        + " WHERE stage_id = ? AND disposition = 'APPLIED'"
                        + " AND returned_version = ?",
                stageId, persisted.version());
        StageManager.State stage = projection
                .map(StageManager.CommandReceipt::state)
                .orElseGet(() -> persisted.withPending(null));
        if (!persisted.matchesCore(stage)) {
            throw new DataIntegrityViolationException(
                    "Stage receipt projection disagrees with Stage row: " + stageId);
        }
        return Optional.of(new StageManager.OwnerState(
                persisted.taskId(), persisted.taskLifecycle(), persisted.taskEpoch(),
                persisted.currentStageId(), stage));
    }

    @Override
    public Optional<StageManager.CommandReceipt> findCommandResult(
            String taskId, String stageId, String commandId)
    {
        return queryReceipt(
                RECEIPT_SELECT
                        + " WHERE task_id = ? AND stage_id = ? AND command_id = ?",
                taskId, stageId, commandId);
    }

    @Override
    public StageManager.State commit(
            String commandId,
            String cause,
            String actor,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            ResultFence subjectFence,
            String proofId,
            StageManager.State expected,
            StageManager.State updated)
    {
        requireTransaction();
        OwnerFence ownerFence = validateCommit(
                expectedTaskEpoch, expectedStageGeneration,
                expectedStageVersion, subjectFence, expected, updated);
        long now = System.currentTimeMillis();
        int changed = jdbc.update("""
                UPDATE stage
                SET version = ?, checkpoint = ?, completed_at_ms = ?, end_reason = ?
                WHERE id = ? AND task_id = ? AND kind = ? AND generation = ?
                  AND version = ? AND checkpoint = ?
                  AND completed_at_ms IS NULL AND end_reason IS NULL
                  AND EXISTS (
                      SELECT 1
                      FROM tasks task
                      JOIN task_current_stage current ON current.task_id = task.id
                      WHERE task.id = stage.task_id
                        AND task.workflow_version = 'V2'
                        AND task.epoch = ?
                        AND current.stage_id = stage.id
                        AND current.stage_generation = ?)
                """,
                updated.version(), updated.checkpoint().name(),
                updated.endReason() == null ? null : now,
                name(updated.endReason()), expected.id(), expected.taskId(),
                expected.kind().name(), expected.generation(), expected.version(),
                expected.checkpoint().name(), ownerFence.taskEpoch(),
                ownerFence.stageGeneration());
        if (changed != 1) {
            throw concurrent("Stage changed before commit: " + expected.id());
        }
        recordTransition(commandId, cause, actor, expected, updated, now);
        insertReceipt(
                commandId, cause, actor, CommandResult.Disposition.APPLIED,
                expectedTaskEpoch, expectedStageGeneration, expectedStageVersion,
                sourceCheckpoint, subjectFence, proofId, updated, now);
        return updated;
    }

    @Override
    public StageManager.State create(
            String commandId,
            String cause,
            String actor,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            ResultFence subjectFence,
            String proofId,
            StageManager.State state)
    {
        requireTransaction();
        if (state.version() != 0 || state.endReason() != null
                || state.pendingResult() != null
                || expectedTaskEpoch != null || expectedStageGeneration != null
                || expectedStageVersion != null || sourceCheckpoint != null) {
            throw new IllegalArgumentException("New Stage identity is inconsistent");
        }
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """,
                state.id(), state.taskId(), state.kind().name(), state.generation(),
                state.checkpoint().name(), now);
        insertSubtype(state);
        jdbc.update("""
                INSERT INTO stage_transition(
                    id, stage_id, command_id, generation, from_checkpoint,
                    to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, NULL, ?, 0, ?, ?, ?)
                """,
                id(), state.id(), commandId, state.generation(),
                state.checkpoint().name(), cause, actor, now);
        insertReceipt(
                commandId, cause, actor, CommandResult.Disposition.APPLIED,
                null, null, null, null, subjectFence, proofId, state, now);
        return state;
    }

    @Override
    public StageManager.State recordSuperseded(
            String commandId,
            String cause,
            String actor,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            ResultFence subjectFence,
            String proofId,
            StageManager.State current)
    {
        requireTransaction();
        insertReceipt(
                commandId, cause, actor, CommandResult.Disposition.SUPERSEDED,
                expectedTaskEpoch, expectedStageGeneration, expectedStageVersion,
                sourceCheckpoint, subjectFence, proofId, current,
                System.currentTimeMillis());
        return current;
    }

    @Override
    public Optional<PlanStageManager.ApprovalEvidence> findLatestApproval(
            String taskId, String stageId, long stageGeneration, String approvalId)
    {
        return jdbc.query("""
                SELECT plan.task_id, plan.stage_id, plan.generation,
                       approval.id AS approval_id, revision.id AS revision_id,
                       review.id AS review_id, review.reviewed_digest
                FROM plan_approval approval
                JOIN plan_revision revision ON revision.id = approval.plan_revision_id
                JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                JOIN plan_self_review review ON review.id = approval.self_review_id
                WHERE plan.task_id = ? AND plan.stage_id = ? AND plan.generation = ?
                  AND approval.id = ?
                  AND review.plan_revision_id = revision.id
                  AND review.status = 'SUCCEEDED' AND review.verdict = 'APPROVED'
                  AND review.reviewed_digest = revision.content_digest
                  AND NOT EXISTS (
                      SELECT 1 FROM plan_revision newer
                      WHERE newer.plan_stage_id = revision.plan_stage_id
                        AND newer.revision > revision.revision)
                """,
                (rs, row) -> new PlanStageManager.ApprovalEvidence(
                        rs.getString("task_id"),
                        rs.getString("stage_id"),
                        rs.getLong("generation"),
                        rs.getString("approval_id"),
                        rs.getString("revision_id"),
                        rs.getString("review_id"),
                        rs.getString("reviewed_digest")),
                taskId, stageId, stageGeneration, approvalId).stream().findFirst();
    }

    @Override
    public Optional<PlanStageManager.RevisionEvidence> findRevision(
            String taskId, String stageId, long stageGeneration, String revisionId)
    {
        return jdbc.query("""
                SELECT plan.task_id, plan.stage_id, plan.generation,
                       revision.id AS revision_id, previous.id AS previous_revision_id,
                       revision.content_digest
                FROM plan_revision revision
                JOIN plan_stage plan ON plan.stage_id = revision.plan_stage_id
                JOIN plan_revision previous
                  ON previous.plan_stage_id = revision.plan_stage_id
                 AND previous.revision = revision.revision - 1
                WHERE plan.task_id = ? AND plan.stage_id = ? AND plan.generation = ?
                  AND revision.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM plan_revision newer
                      WHERE newer.plan_stage_id = revision.plan_stage_id
                        AND newer.revision > revision.revision)
                """,
                (rs, row) -> new PlanStageManager.RevisionEvidence(
                        rs.getString("task_id"),
                        rs.getString("stage_id"),
                        rs.getLong("generation"),
                        rs.getString("revision_id"),
                        rs.getString("previous_revision_id"),
                        rs.getString("content_digest")),
                taskId, stageId, stageGeneration, revisionId).stream().findFirst();
    }

    @Override
    public Optional<LocalDevelopmentStageManager.FeedbackEvidence> findLocalFeedback(
            String taskId, String stageId, long stageGeneration, String batchId)
    {
        // V228 has no immutable batch-level content digest required by the command.
        return Optional.empty();
    }

    @Override
    public Optional<LocalDevelopmentStageManager.PublishAuthorizationEvidence>
            findPublishAuthorization(
                    String taskId,
                    String stageId,
                    long stageGeneration,
                    String authorizationId)
    {
        return jdbc.query("""
                SELECT task_id, task_epoch, local_development_stage_id,
                       stage_generation, id, policy_revision_id, consent_id,
                       authorized_operation_id, authorized_attempt,
                       code_fingerprint, head_sha, base_sha
                FROM publish_authorization
                WHERE task_id = ? AND local_development_stage_id = ?
                  AND stage_generation = ? AND id = ?
                  AND revoked_at_ms IS NULL AND consumed_at_ms IS NULL
                """,
                (rs, row) -> new LocalDevelopmentStageManager.PublishAuthorizationEvidence(
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getString("local_development_stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("id"),
                        rs.getString("policy_revision_id"),
                        rs.getString("consent_id"),
                        new ResultFence(
                                rs.getLong("task_epoch"),
                                rs.getString("local_development_stage_id"),
                                rs.getLong("stage_generation"),
                                rs.getString("authorized_operation_id"),
                                rs.getInt("authorized_attempt"),
                                rs.getString("code_fingerprint"),
                                rs.getString("head_sha"),
                                rs.getString("base_sha"))),
                taskId, stageId, stageGeneration, authorizationId)
                .stream().findFirst();
    }

    private void insertSubtype(StageManager.State state)
    {
        String table = switch (state.kind()) {
            case PLAN -> "plan_stage";
            case LOCAL_DEVELOPMENT -> "local_development_stage";
            case REMOTE_DEVELOPMENT, CLEANUP -> null;
        };
        if (table == null) {
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO %s(stage_id, task_id, generation, opened_for_epoch)
                SELECT ?, ?, ?, task.epoch
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND current.stage_id = ? AND current.stage_generation = ?
                """.formatted(table),
                state.id(), state.taskId(), state.generation(), state.taskId(),
                state.id(), state.generation());
        if (inserted != 1) {
            throw concurrent("Stage subtype owner changed before create: " + state.id());
        }
    }

    private void recordTransition(
            String commandId,
            String cause,
            String actor,
            StageManager.State expected,
            StageManager.State updated,
            long now)
    {
        jdbc.update("""
                INSERT INTO stage_transition(
                    id, stage_id, command_id, generation, from_checkpoint,
                    to_checkpoint, stage_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), updated.id(), commandId, updated.generation(),
                expected.checkpoint().name(), updated.checkpoint().name(),
                updated.version(), cause, actor, now);
    }

    private void insertReceipt(
            String commandId,
            String cause,
            String actor,
            CommandResult.Disposition disposition,
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            StageCheckpoint sourceCheckpoint,
            ResultFence subjectFence,
            String proofId,
            StageManager.State state,
            long now)
    {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(RECEIPT_INSERT);
            int index = 1;
            statement.setString(index++, id());
            statement.setString(index++, state.id());
            statement.setString(index++, state.taskId());
            statement.setString(index++, commandId);
            statement.setString(index++, cause);
            statement.setString(index++, actor);
            statement.setString(index++, disposition.name());
            setLong(statement, index++, expectedTaskEpoch);
            setLong(statement, index++, expectedStageGeneration);
            setLong(statement, index++, expectedStageVersion);
            statement.setString(index++, name(sourceCheckpoint));
            index = bindFence(statement, index, subjectFence);
            statement.setString(index++, proofId);
            statement.setString(index++, state.kind().name());
            statement.setLong(index++, state.generation());
            statement.setLong(index++, state.version());
            statement.setString(index++, state.checkpoint().name());
            statement.setString(index++, name(state.endReason()));
            index = bindFence(statement, index, state.pendingResult());
            statement.setLong(index, now);
            return statement;
        });
    }

    private Optional<StageManager.CommandReceipt> queryReceipt(
            String sql, Object... arguments)
    {
        return jdbc.query(sql, (rs, row) -> receipt(rs), arguments)
                .stream().findFirst();
    }

    private static StageManager.CommandReceipt receipt(ResultSet rs)
            throws SQLException
    {
        StageManager.State state = new StageManager.State(
                rs.getString("stage_id"),
                rs.getString("task_id"),
                StageKind.valueOf(rs.getString("returned_kind")),
                rs.getLong("returned_generation"),
                rs.getLong("returned_version"),
                StageCheckpoint.valueOf(rs.getString("returned_checkpoint")),
                endReason(rs.getString("returned_end_reason")),
                readFence(rs, "returned_pending_", false));
        return new StageManager.CommandReceipt(
                rs.getString("task_id"),
                state,
                rs.getString("cause"),
                rs.getString("actor"),
                nullableLong(rs, "expected_task_epoch"),
                nullableLong(rs, "expected_stage_generation"),
                nullableLong(rs, "expected_stage_version"),
                checkpoint(rs.getString("source_checkpoint")),
                readFence(rs, "subject_", true),
                rs.getString("proof_id"),
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
        return new ResultFence(
                rs.getLong(prefix + "task_epoch"),
                rs.getString(prefix + "stage_id"),
                rs.getLong(prefix + "stage_generation"),
                operation,
                rs.getInt(prefix + "attempt"),
                rs.getString(subject
                        ? prefix + "expected_code_fingerprint"
                        : prefix + "code_fingerprint"),
                rs.getString(subject ? prefix + "expected_head_sha" : prefix + "head_sha"),
                rs.getString(subject ? prefix + "expected_base_sha" : prefix + "base_sha"));
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

    private static OwnerFence validateCommit(
            Long expectedTaskEpoch,
            Long expectedStageGeneration,
            Long expectedStageVersion,
            ResultFence subjectFence,
            StageManager.State expected,
            StageManager.State updated)
    {
        if (!expected.id().equals(updated.id())
                || !expected.taskId().equals(updated.taskId())
                || expected.kind() != updated.kind()
                || expected.generation() != updated.generation()
                || expected.endReason() != null
                || updated.version() != expected.version() + 1
                || (expectedTaskEpoch == null) != (expectedStageGeneration == null)
                || (expectedTaskEpoch == null) != (expectedStageVersion == null)
                || (expectedStageGeneration != null
                    && expected.generation() != expectedStageGeneration)
                || (expectedStageVersion != null
                    && expected.version() != expectedStageVersion)) {
            throw new IllegalArgumentException("Stage commit fence is inconsistent");
        }

        if (subjectFence != null && subjectFence.stageId() != null
                && (!expected.id().equals(subjectFence.stageId())
                || expected.generation() != subjectFence.stageGeneration())) {
            throw new IllegalArgumentException("Stage result fence targets another owner");
        }
        if (expectedTaskEpoch != null) {
            return new OwnerFence(expectedTaskEpoch, expectedStageGeneration);
        }
        if (subjectFence == null || subjectFence.stageId() == null) {
            throw new IllegalArgumentException("Stage commit lacks an owner fence");
        }
        return new OwnerFence(subjectFence.taskEpoch(), subjectFence.stageGeneration());
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

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Stage writes require the command transaction");
        }
    }

    private static StageCheckpoint checkpoint(String value)
    {
        return value == null ? null : StageCheckpoint.valueOf(value);
    }

    private static StageEndReason endReason(String value)
    {
        return value == null ? null : StageEndReason.valueOf(value);
    }

    private static String name(Enum<?> value)
    {
        return value == null ? null : value.name();
    }

    private static CommandRejectedException concurrent(String message)
    {
        return new CommandRejectedException(CONCURRENT_UPDATE, message);
    }

    private static String id()
    {
        return UUID.randomUUID().toString();
    }

    private record BaseOwner(
            String taskId,
            TaskLifecycle taskLifecycle,
            long taskEpoch,
            String currentStageId,
            String stageId,
            StageKind kind,
            long generation,
            long version,
            StageCheckpoint checkpoint,
            StageEndReason endReason)
    {
        private StageManager.State withPending(ResultFence pending)
        {
            return new StageManager.State(
                    stageId, taskId, kind, generation, version,
                    checkpoint, endReason, pending);
        }

        private boolean matchesCore(StageManager.State state)
        {
            return stageId.equals(state.id())
                    && taskId.equals(state.taskId())
                    && kind == state.kind()
                    && generation == state.generation()
                    && version == state.version()
                    && checkpoint == state.checkpoint()
                    && endReason == state.endReason();
        }
    }

    private record OwnerFence(long taskEpoch, long stageGeneration) {}
}
