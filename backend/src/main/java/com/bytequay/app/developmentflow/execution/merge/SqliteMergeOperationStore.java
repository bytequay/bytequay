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
package com.bytequay.app.developmentflow.execution.merge;

import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.AttemptStatus;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.BlockReason;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimSpec;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectAttempt;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeRequest;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationSnapshot;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.QueueEntry;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.QueueEntryStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** SQLite transaction boundary for V233 merge claims and observed truth. */
@Repository
public class SqliteMergeOperationStore
        implements MergeOperationHandler.OperationStore
{
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteMergeOperationStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = new TransactionTemplate(
                requireNonNull(transactionManager, "transactionManager is null"));
    }

    @Override
    public OperationSnapshot requireByOperationId(String operationId)
    {
        requireText(operationId, "operationId");
        List<MergeRequest> requests = jdbc.query("""
                SELECT operation.id AS merge_operation_id,
                    operation.merge_authorization_id,
                    authorization.readiness_evidence_id,
                    operation.operation_id,
                    operation.remote_development_stage_id AS stage_id,
                    operation.task_id, task.thread_id AS trunk_id,
                    trunk.workspace_id, operation.task_epoch,
                    operation.stage_generation, operation.semantic_attempt,
                    operation.mode, operation.status, operation.attempt_count,
                    operation.attempt_limit, operation.queue_bounce_count,
                    operation.max_queue_reenqueues, operation.head_sha,
                    operation.base_sha, binding.remote_repository_id,
                    binding.remote_pr_number,
                    current_readiness.id AS current_readiness_id,
                    task.workflow_version, task.lifecycle_state,
                    task.epoch AS current_task_epoch,
                    current.stage_id AS current_stage_id,
                    COALESCE(current.stage_generation, 0) AS current_stage_generation,
                    owner.checkpoint AS stage_checkpoint, operation.last_error
                FROM remote_merge_operation operation
                JOIN remote_merge_authorization authorization
                  ON authorization.id = operation.merge_authorization_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN remote_pr_binding binding
                  ON binding.id = remote.remote_pr_binding_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN stage owner ON owner.id = operation.remote_development_stage_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN remote_readiness_evidence current_readiness
                  ON current_readiness.remote_pr_snapshot_id = remote.accepted_snapshot_id
                 AND current_readiness.automation_policy_id = authorization.automation_policy_id
                 AND current_readiness.head_sha = operation.head_sha
                 AND current_readiness.base_sha = operation.base_sha
                 AND current_readiness.ready = 1
                WHERE operation.operation_id = ?
                """, (rs, row) -> mapRequest(rs), operationId);
        if (requests.size() != 1) {
            throw new IllegalStateException(
                    "expected one exact MergeOperation for " + operationId
                            + ", found " + requests.size());
        }
        MergeRequest request = requests.getFirst();
        Optional<EffectAttempt> attempt = jdbc.query("""
                SELECT id, ordinal, effect_kind, effect_ordinal,
                    readiness_evidence_id, idempotency_key, claim_mode,
                    status, claimed_at_ms, lease_until_ms
                FROM remote_merge_effect_attempt
                WHERE merge_operation_id = ?
                ORDER BY ordinal DESC LIMIT 1
                """, (rs, row) -> mapAttempt(rs), request.mergeOperationId())
                .stream().findFirst();
        Optional<QueueEntry> entry = jdbc.query("""
                SELECT ordinal, status FROM remote_merge_queue_entry
                WHERE merge_operation_id = ?
                ORDER BY ordinal DESC LIMIT 1
                """, (rs, row) -> new QueueEntry(
                        rs.getInt("ordinal"),
                        QueueEntryStatus.valueOf(rs.getString("status"))),
                request.mergeOperationId()).stream().findFirst();
        return new OperationSnapshot(request, attempt, entry);
    }

    @Override
    public Optional<EffectClaim> tryClaim(
            String operationId,
            ClaimSpec spec,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        requireText(operationId, "operationId");
        requireNonNull(spec, "spec is null");
        requireText(claimOwner, "claimOwner");
        requireNonNull(claimedAt, "claimedAt is null");
        requireNonNull(leaseUntil, "leaseUntil is null");
        try {
            return inTransaction(() -> {
                OperationSnapshot snapshot = requireByOperationId(operationId);
                MergeRequest request = snapshot.request();
                if (request.status().isTerminal()
                        || request.attemptCount() >= request.attemptLimit()) {
                    return Optional.empty();
                }
                int ordinal = request.attemptCount() + 1;
                String id = id("merge-attempt", request.mergeOperationId(), ordinal);
                jdbc.update("""
                        INSERT INTO remote_merge_effect_attempt(
                            id, merge_operation_id, ordinal, effect_kind,
                            effect_ordinal, readiness_evidence_id,
                            idempotency_key, attempt_key, claim_mode, status,
                            claim_owner, claimed_at_ms, lease_until_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, ?)
                        """, id, request.mergeOperationId(), ordinal,
                        spec.kind().name(), spec.effectOrdinal(),
                        spec.readinessEvidenceId(), spec.idempotencyKey(),
                        spec.idempotencyKey() + ":attempt:" + ordinal,
                        spec.mode().name(), claimOwner,
                        claimedAt.toEpochMilli(), leaseUntil.toEpochMilli());
                int changed = jdbc.update("""
                        UPDATE remote_merge_operation
                        SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                            last_error = NULL
                        WHERE id = ? AND operation_id = ?
                          AND attempt_count = ?
                          AND status IN ('REQUESTED', 'CLAIMED',
                              'AWAITING_OBSERVATION')
                        """, request.mergeOperationId(), operationId,
                        request.attemptCount());
                if (changed != 1) {
                    throw new ConcurrentClaimException();
                }
                return Optional.of(new EffectClaim(
                        id, request.mergeOperationId(), ordinal, spec,
                        claimOwner, claimedAt, leaseUntil));
            });
        }
        catch (ConcurrentClaimException | DataIntegrityViolationException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public boolean markAwaiting(
            EffectClaim claim, EffectEvidence evidence, Instant at)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(evidence, "evidence is null");
        requireNonNull(at, "at is null");
        try {
            return inTransaction(() -> {
                int attempt = jdbc.update("""
                    UPDATE remote_merge_effect_attempt
                    SET status = 'AWAITING_OBSERVATION',
                        external_effect_id = ?, evidence = ?, last_error = NULL
                    WHERE id = ? AND merge_operation_id = ?
                      AND ordinal = ? AND status = 'CLAIMED'
                      AND claim_owner = ? AND claimed_at_ms = ?
                      AND lease_until_ms = ?
                    """, evidence.externalEffectId(), evidence.detail(),
                    claim.id(), claim.mergeOperationId(), claim.ordinal(),
                    claim.claimOwner(), claim.claimedAt().toEpochMilli(),
                    claim.leaseUntil().toEpochMilli());
                int operation = jdbc.update("""
                    UPDATE remote_merge_operation
                    SET status = 'AWAITING_OBSERVATION', last_error = NULL
                    WHERE id = ? AND status = 'CLAIMED'
                      AND attempt_count = ?
                    """, claim.mergeOperationId(), claim.ordinal());
                if (attempt != 1 || operation != 1) {
                    throw new ConcurrentClaimException();
                }
                return true;
            });
        }
        catch (ConcurrentClaimException ignored) {
            return false;
        }
    }

    @Override
    public boolean markIndeterminate(EffectClaim claim, String detail, Instant at)
    {
        requireNonNull(claim, "claim is null");
        requireText(detail, "detail");
        requireNonNull(at, "at is null");
        return jdbc.update("""
                UPDATE remote_merge_effect_attempt
                SET status = 'INDETERMINATE', evidence = ?, last_error = ?,
                    completed_at_ms = ?
                WHERE id = ? AND merge_operation_id = ?
                  AND ordinal = ? AND status = 'CLAIMED'
                  AND claim_owner = ? AND claimed_at_ms = ?
                  AND lease_until_ms = ?
                """, detail, detail, at.toEpochMilli(), claim.id(),
                claim.mergeOperationId(), claim.ordinal(), claim.claimOwner(),
                claim.claimedAt().toEpochMilli(), claim.leaseUntil().toEpochMilli()) == 1;
    }

    @Override
    public void reconcileAcceptedObservation(String operationId, Instant at)
    {
        requireText(operationId, "operationId");
        requireNonNull(at, "at is null");
        inTransaction(() -> {
            ReconcileContext context = findReconcileContext(operationId).orElse(null);
            if (context == null || context.status().isTerminal()) {
                return null;
            }
            if (!context.headSha().equals(context.snapshotHeadSha())
                    || !context.baseSha().equals(context.snapshotBaseSha())) {
                cancelInTransaction(context, "accepted remote head moved", at);
                return null;
            }
            switch (context.prState()) {
                case "MERGED" -> reconcileMerged(context, at);
                case "CLOSED" -> {
                    if (context.terminalObservationId() != null) {
                        cancelInTransaction(context, "remote PR closed without merge", at);
                    }
                }
                case "OPEN" -> {
                    if (cancelStaleRequestedAuthorization(context, at)) {
                        return null;
                    }
                    if (reconcileCapabilityDrift(context, at)) {
                        return null;
                    }
                    reconcileOpen(context, at);
                }
                case "DRAFT" -> {
                    if (!cancelStaleRequestedAuthorization(context, at)) {
                        reconcileReadinessRegression(
                                context, "remote pull request became Draft", at);
                    }
                }
                default -> throw new IllegalStateException(
                        "unknown persisted PR state: " + context.prState());
            }
            return null;
        });
    }

    private boolean cancelStaleRequestedAuthorization(
            ReconcileContext context, Instant at)
    {
        if (context.status() != OperationStatus.REQUESTED) {
            return false;
        }
        if (!context.authorizationPolicyCurrent()) {
            cancelInTransaction(
                    context,
                    "automation policy superseded pre-effect merge authorization",
                    at);
            return true;
        }
        if (context.authorizationSnapshotId().equals(context.snapshotId())) {
            return false;
        }
        cancelInTransaction(
                context,
                "accepted remote revision superseded pre-effect merge authorization",
                at);
        return true;
    }

    private boolean reconcileCapabilityDrift(
            ReconcileContext context, Instant at)
    {
        if (context.operationQueueCapability().equals(
                context.snapshotQueueCapability())) {
            return false;
        }
        reconcileReadinessRegression(
                context, "accepted merge queue capability changed", at);
        return true;
    }

    private void reconcileReadinessRegression(
            ReconcileContext context, String detail, Instant at)
    {
        if (context.status() == OperationStatus.REQUESTED) {
            cancelInTransaction(context, detail, at);
            return;
        }
        finishLiveAttempt(context, "FAILED", detail, at);
        insertBlocker(context, "MERGEABILITY_REGRESSED", detail, at);
        updateBlocked(context, "MERGEABILITY_REGRESSED", detail, at);
    }

    @Override
    public void block(
            String operationId, BlockReason reason, String detail, Instant at)
    {
        requireText(operationId, "operationId");
        requireNonNull(reason, "reason is null");
        requireText(detail, "detail");
        requireNonNull(at, "at is null");
        inTransaction(() -> {
            ReconcileContext context = requireReconcileContext(operationId);
            if (context.status().isTerminal()) {
                return null;
            }
            finishLiveAttempt(context, "FAILED", detail, at);
            String blockerType = switch (reason) {
                case PERMISSION_DENIED -> "MERGE_PERMISSION_DENIED";
                case MERGEABILITY_REGRESSED -> "MERGEABILITY_REGRESSED";
                case MANUAL_INTERVENTION -> "MERGE_MANUAL_INTERVENTION";
            };
            insertBlocker(context, blockerType, detail, at);
            if (context.status() == OperationStatus.REQUESTED) {
                cancelInTransaction(context, detail, at);
                return null;
            }
            updateBlocked(context, reason.name(), detail, at);
            return null;
        });
    }

    @Override
    public void cancel(String operationId, String detail, Instant at)
    {
        terminalize(operationId, detail, at, "CANCELED");
    }

    private void terminalize(
            String operationId, String detail, Instant at, String status)
    {
        requireText(operationId, "operationId");
        requireText(detail, "detail");
        requireNonNull(at, "at is null");
        inTransaction(() -> {
            ReconcileContext context = requireReconcileContext(operationId);
            if (context.status().isTerminal()) {
                return null;
            }
            finishLiveAttempt(context, "FAILED", detail, at);
            int changed = jdbc.update("""
                    UPDATE remote_merge_operation
                    SET status = ?, completed_at_ms = ?, last_error = ?
                    WHERE id = ? AND status NOT IN (
                        'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
                    """, status, at.toEpochMilli(), detail,
                    context.mergeOperationId());
            if (changed != 1) {
                throw new IllegalStateException("MergeOperation changed before terminalization");
            }
            return null;
        });
    }

    private void reconcileOpen(ReconcileContext context, Instant at)
    {
        if (context.mode() != MergeMode.MERGE_QUEUE) {
            return;
        }
        switch (context.queueState()) {
            case "QUEUED" -> observeQueueEntered(context, at);
            case "DEQUEUED" -> observeQueueBounce(context, at);
            case "NONE" -> observeQueueRemoval(context, at);
            case "MERGED" -> { /* PR state remains authoritative */ }
            default -> throw new IllegalStateException(
                    "unknown persisted merge queue state: " + context.queueState());
        }
    }

    private void observeQueueEntered(ReconcileContext context, Instant at)
    {
        if (context.attemptId() == null
                || !"AWAITING_OBSERVATION".equals(context.attemptStatus())
                || !"ENTER_QUEUE".equals(context.effectKind())) {
            return;
        }
        int ordinal = requireNonNull(
                context.effectOrdinal(), "queue effect ordinal is null");
        if (context.queueEntryOrdinal() != null
                && context.queueEntryOrdinal() >= ordinal) {
            return;
        }
        String queueEntryId = context.externalEffectId() == null
                ? context.idempotencyKey() : context.externalEffectId();
        jdbc.update("""
                INSERT INTO remote_merge_queue_entry(
                    id, merge_operation_id, merge_effect_attempt_id,
                    ordinal, queue_entry_id, head_sha, status,
                    entered_snapshot_id, readiness_evidence_id,
                    entered_at_ms, evidence)
                VALUES (?, ?, ?, ?, ?, ?, 'ENTERED', ?, ?, ?, ?)
                """, id("merge-queue-entry", context.mergeOperationId(), ordinal),
                context.mergeOperationId(), context.attemptId(), ordinal,
                queueEntryId, context.headSha(), context.snapshotId(),
                context.readinessEvidenceId(), context.snapshotObservedAtMs(),
                "RemoteObserver reports queue entry");
        finishAttemptSucceeded(context, at, "RemoteObserver reports queued");
        jdbc.update("""
                UPDATE remote_merge_operation SET status = 'QUEUE_ENTERED'
                WHERE id = ? AND status = 'AWAITING_OBSERVATION'
                """, context.mergeOperationId());
        jdbc.update("""
                UPDATE remote_merge_operation SET status = 'AWAITING_OBSERVATION'
                WHERE id = ? AND status = 'QUEUE_ENTERED'
                """, context.mergeOperationId());
    }

    private void observeQueueBounce(ReconcileContext context, Instant at)
    {
        if (context.queueEntryId() == null
                || !"ENTERED".equals(context.queueEntryStatus())) {
            return;
        }
        jdbc.update("""
                UPDATE remote_merge_queue_entry
                SET status = 'BOUNCED', observed_snapshot_id = ?, observed_at_ms = ?
                WHERE id = ? AND status = 'ENTERED'
                """, context.snapshotId(), context.snapshotObservedAtMs(),
                context.queueEntryId());
        int ordinal = requireNonNull(
                context.queueEntryOrdinal(), "queue entry ordinal is null");
        if (ordinal <= context.maxQueueReenqueues()) {
            jdbc.update("""
                    UPDATE remote_merge_operation
                    SET queue_bounce_count = ?
                    WHERE id = ? AND queue_bounce_count = ?
                      AND status = 'AWAITING_OBSERVATION'
                    """, ordinal, context.mergeOperationId(), ordinal - 1);
            if (!context.authorizationPolicyCurrent()) {
                insertBlocker(context, "MERGE_POLICY_CHANGED",
                        "automation policy changed; fresh merge authorization required", at);
                updateBlocked(context, "MANUAL_INTERVENTION",
                        "automation policy changed; fresh merge authorization required", at);
            }
            else if (context.readinessProjected() && !context.readinessReady()) {
                insertBlocker(context, "MERGEABILITY_REGRESSED",
                        "fresh queue-bounce readiness is not satisfied", at);
                updateBlocked(context, "MERGEABILITY_REGRESSED",
                        "fresh queue-bounce readiness is not satisfied", at);
            }
            return;
        }
        insertBlocker(context, "MERGE_QUEUE_REENQUEUE_EXHAUSTED",
                "merge queue re-enqueue budget exhausted", at);
        updateBlocked(context, "QUEUE_REENQUEUE_EXHAUSTED",
                "merge queue re-enqueue budget exhausted", at);
    }

    private void observeQueueRemoval(ReconcileContext context, Instant at)
    {
        if (context.queueEntryId() == null
                || !"ENTERED".equals(context.queueEntryStatus())) {
            return;
        }
        jdbc.update("""
                UPDATE remote_merge_queue_entry
                SET status = 'REMOVED', observed_snapshot_id = ?, observed_at_ms = ?
                WHERE id = ? AND status = 'ENTERED'
                """, context.snapshotId(), context.snapshotObservedAtMs(),
                context.queueEntryId());
        insertBlocker(context, "MERGE_QUEUE_REMOVED",
                "merge queue entry was removed", at);
        updateBlocked(context, "MANUAL_INTERVENTION",
                "merge queue entry was removed", at);
    }

    private void reconcileMerged(ReconcileContext context, Instant at)
    {
        if (context.terminalObservationId() == null) {
            return;
        }
        if (context.mode() == MergeMode.DIRECT) {
            if (context.attemptId() == null) {
                cancelInTransaction(context, "PR merged outside this operation", at);
                return;
            }
            if ("AWAITING_OBSERVATION".equals(context.attemptStatus())) {
                finishAttemptSucceeded(context, at, "RemoteObserver reports merged");
            }
            String currentStatus = jdbc.queryForObject("""
                    SELECT status FROM remote_merge_effect_attempt WHERE id = ?
                    """, String.class, context.attemptId());
            if (!"SUCCEEDED".equals(currentStatus)) {
                cancelInTransaction(
                        context,
                        "merged truth cannot be attributed to this effect attempt",
                        at);
                return;
            }
        }
        else {
            if (context.queueEntryId() == null) {
                cancelInTransaction(context, "PR merged without observed queue entry", at);
                return;
            }
            if ("ENTERED".equals(context.queueEntryStatus())) {
                jdbc.update("""
                        UPDATE remote_merge_queue_entry
                        SET status = 'MERGED', observed_snapshot_id = ?, observed_at_ms = ?
                        WHERE id = ? AND status = 'ENTERED'
                        """, context.snapshotId(), context.snapshotObservedAtMs(),
                        context.queueEntryId());
            }
            else if (!"MERGED".equals(context.queueEntryStatus())) {
                cancelInTransaction(
                        context,
                        "PR merged without a live observed queue entry",
                        at);
                return;
            }
        }
        int changed = jdbc.update("""
                UPDATE remote_merge_operation
                SET status = 'SUCCEEDED', terminal_observation_id = ?,
                    completed_at_ms = ?, last_error = NULL
                WHERE id = ? AND status = 'AWAITING_OBSERVATION'
                """, context.terminalObservationId(), at.toEpochMilli(),
                context.mergeOperationId());
        if (changed != 1) {
            throw new IllegalStateException(
                    "observed merged truth could not complete MergeOperation");
        }
    }

    private void finishAttemptSucceeded(
            ReconcileContext context, Instant at, String evidence)
    {
        int changed = jdbc.update("""
                UPDATE remote_merge_effect_attempt
                SET status = 'SUCCEEDED', observed_snapshot_id = ?,
                    evidence = ?, last_error = NULL, completed_at_ms = ?
                WHERE id = ? AND status = 'AWAITING_OBSERVATION'
                """, context.snapshotId(), evidence, at.toEpochMilli(),
                context.attemptId());
        if (changed != 1) {
            throw new IllegalStateException(
                    "observed truth could not complete merge effect attempt");
        }
    }

    private void finishLiveAttempt(
            ReconcileContext context, String status, String detail, Instant at)
    {
        if (context.attemptId() == null
                || !("CLAIMED".equals(context.attemptStatus())
                    || "AWAITING_OBSERVATION".equals(context.attemptStatus()))) {
            return;
        }
        jdbc.update("""
                UPDATE remote_merge_effect_attempt
                SET status = ?, evidence = ?, last_error = ?, completed_at_ms = ?
                WHERE id = ? AND status IN ('CLAIMED', 'AWAITING_OBSERVATION')
                """, status, detail, detail, at.toEpochMilli(), context.attemptId());
    }

    private void cancelInTransaction(
            ReconcileContext context, String detail, Instant at)
    {
        finishLiveAttempt(context, "FAILED", detail, at);
        jdbc.update("""
                UPDATE remote_merge_operation
                SET status = 'CANCELED', completed_at_ms = ?, last_error = ?
                WHERE id = ? AND status NOT IN (
                    'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
                """, at.toEpochMilli(), detail, context.mergeOperationId());
    }

    private void insertBlocker(
            ReconcileContext context, String type, String detail, Instant at)
    {
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'OPERATION', ?, ?, ?, 'OPEN', ?, ?)
                ON CONFLICT(id) DO NOTHING
                """, id("merge-blocker", context.mergeOperationId(), type),
                context.taskId(), context.stageId(), context.mergeOperationId(),
                context.headSha(), type, detail, at.toEpochMilli());
    }

    private void updateBlocked(
            ReconcileContext context, String reason, String detail, Instant at)
    {
        int changed = jdbc.update("""
                UPDATE remote_merge_operation
                SET status = 'BLOCKED', block_reason = ?, completed_at_ms = ?,
                    last_error = ?
                WHERE id = ? AND status NOT IN (
                    'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
                """, reason, at.toEpochMilli(), detail,
                context.mergeOperationId());
        if (changed != 1) {
            throw new IllegalStateException("MergeOperation changed before blocking");
        }
    }

    private Optional<ReconcileContext> findReconcileContext(String operationId)
    {
        return jdbc.query("""
                SELECT operation.id AS merge_operation_id,
                    operation.operation_id, operation.task_id,
                    operation.remote_development_stage_id AS stage_id,
                    operation.mode, operation.status, operation.head_sha,
                    operation.base_sha, operation.max_queue_reenqueues,
                    operation.merge_queue_capability AS operation_queue_capability,
                    authorization_readiness.remote_pr_snapshot_id
                        AS authorization_snapshot_id,
                    snapshot.id AS snapshot_id,
                    snapshot.head_sha AS snapshot_head_sha,
                    snapshot.base_sha AS snapshot_base_sha,
                    snapshot.pr_state, snapshot.merge_queue_state,
                    snapshot.merge_queue_capability AS snapshot_queue_capability,
                    snapshot.observed_at_ms AS snapshot_observed_at_ms,
                    CASE WHEN authorization.automation_policy_id = (
                        SELECT current.id FROM task_automation_policy current
                        WHERE current.task_id = operation.task_id
                        ORDER BY current.revision DESC LIMIT 1)
                      THEN 1 ELSE 0 END AS authorization_policy_current,
                    CASE WHEN observed_readiness.id IS NULL
                      THEN 0 ELSE 1 END AS readiness_projected,
                    COALESCE(observed_readiness.ready, 0) AS readiness_ready,
                    terminal.id AS terminal_observation_id,
                    attempt.id AS attempt_id,
                    attempt.status AS attempt_status,
                    attempt.effect_kind, attempt.effect_ordinal,
                    attempt.readiness_evidence_id,
                    attempt.idempotency_key, attempt.external_effect_id,
                    entry.id AS queue_entry_id,
                    entry.ordinal AS queue_entry_ordinal,
                    entry.status AS queue_entry_status
                FROM remote_merge_operation operation
                JOIN remote_merge_authorization authorization
                  ON authorization.id = operation.merge_authorization_id
                JOIN remote_readiness_evidence authorization_readiness
                  ON authorization_readiness.id = authorization.readiness_evidence_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                LEFT JOIN remote_readiness_evidence observed_readiness
                  ON observed_readiness.remote_pr_snapshot_id = snapshot.id
                 AND observed_readiness.automation_policy_id =
                        authorization.automation_policy_id
                LEFT JOIN remote_terminal_observation terminal
                  ON terminal.remote_pr_snapshot_id = snapshot.id
                LEFT JOIN remote_merge_effect_attempt attempt
                  ON attempt.merge_operation_id = operation.id
                 AND attempt.ordinal = operation.attempt_count
                LEFT JOIN remote_merge_queue_entry entry
                  ON entry.merge_operation_id = operation.id
                 AND entry.ordinal = (
                     SELECT MAX(latest.ordinal)
                     FROM remote_merge_queue_entry latest
                     WHERE latest.merge_operation_id = operation.id)
                WHERE operation.operation_id = ?
                """, (rs, row) -> mapReconcile(rs), operationId)
                .stream().findFirst();
    }

    private ReconcileContext requireReconcileContext(String operationId)
    {
        return findReconcileContext(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "MergeOperation disappeared: " + operationId));
    }

    private static MergeRequest mapRequest(ResultSet rs)
            throws SQLException
    {
        return new MergeRequest(
                rs.getString("merge_operation_id"),
                rs.getString("merge_authorization_id"),
                rs.getString("readiness_evidence_id"),
                rs.getString("operation_id"), rs.getString("stage_id"),
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getLong("stage_generation"), rs.getInt("semantic_attempt"),
                MergeMode.valueOf(rs.getString("mode")),
                OperationStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"), rs.getInt("attempt_limit"),
                rs.getInt("queue_bounce_count"),
                rs.getInt("max_queue_reenqueues"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("remote_repository_id"),
                rs.getInt("remote_pr_number"), rs.getString("current_readiness_id"),
                rs.getString("workflow_version"), rs.getString("lifecycle_state"),
                rs.getLong("current_task_epoch"), rs.getString("current_stage_id"),
                rs.getLong("current_stage_generation"),
                rs.getString("stage_checkpoint"), rs.getString("last_error"));
    }

    private static EffectAttempt mapAttempt(ResultSet rs)
            throws SQLException
    {
        Integer effectOrdinal = nullableInt(rs, "effect_ordinal");
        return new EffectAttempt(
                rs.getString("id"), rs.getInt("ordinal"),
                EffectKind.valueOf(rs.getString("effect_kind")), effectOrdinal,
                rs.getString("readiness_evidence_id"),
                rs.getString("idempotency_key"),
                ClaimMode.valueOf(rs.getString("claim_mode")),
                AttemptStatus.valueOf(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("claimed_at_ms")),
                Instant.ofEpochMilli(rs.getLong("lease_until_ms")));
    }

    private static ReconcileContext mapReconcile(ResultSet rs)
            throws SQLException
    {
        return new ReconcileContext(
                rs.getString("merge_operation_id"), rs.getString("operation_id"),
                rs.getString("task_id"), rs.getString("stage_id"),
                MergeMode.valueOf(rs.getString("mode")),
                OperationStatus.valueOf(rs.getString("status")),
                rs.getString("head_sha"), rs.getString("base_sha"),
                rs.getInt("max_queue_reenqueues"),
                rs.getString("operation_queue_capability"),
                rs.getString("authorization_snapshot_id"),
                rs.getString("snapshot_id"),
                rs.getString("snapshot_head_sha"),
                rs.getString("snapshot_base_sha"), rs.getString("pr_state"),
                rs.getString("merge_queue_state"),
                rs.getString("snapshot_queue_capability"),
                rs.getLong("snapshot_observed_at_ms"),
                rs.getInt("authorization_policy_current") == 1,
                rs.getInt("readiness_projected") == 1,
                rs.getInt("readiness_ready") == 1,
                rs.getString("terminal_observation_id"),
                rs.getString("attempt_id"), rs.getString("attempt_status"),
                rs.getString("effect_kind"), nullableInt(rs, "effect_ordinal"),
                rs.getString("readiness_evidence_id"),
                rs.getString("idempotency_key"), rs.getString("external_effect_id"),
                rs.getString("queue_entry_id"),
                nullableInt(rs, "queue_entry_ordinal"),
                rs.getString("queue_entry_status"));
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return work.get();
        }
        return transactions.execute(status -> work.get());
    }

    private static Integer nullableInt(ResultSet rs, String column)
            throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static String id(String kind, Object... parts)
    {
        StringBuilder source = new StringBuilder(kind);
        for (Object part : parts) {
            source.append('\u001f').append(part);
        }
        return UUID.nameUUIDFromBytes(
                source.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record ReconcileContext(
            String mergeOperationId,
            String operationId,
            String taskId,
            String stageId,
            MergeMode mode,
            OperationStatus status,
            String headSha,
            String baseSha,
            int maxQueueReenqueues,
            String operationQueueCapability,
            String authorizationSnapshotId,
            String snapshotId,
            String snapshotHeadSha,
            String snapshotBaseSha,
            String prState,
            String queueState,
            String snapshotQueueCapability,
            long snapshotObservedAtMs,
            boolean authorizationPolicyCurrent,
            boolean readinessProjected,
            boolean readinessReady,
            String terminalObservationId,
            String attemptId,
            String attemptStatus,
            String effectKind,
            Integer effectOrdinal,
            String readinessEvidenceId,
            String idempotencyKey,
            String externalEffectId,
            String queueEntryId,
            Integer queueEntryOrdinal,
            String queueEntryStatus) {}

    private static final class ConcurrentClaimException
            extends RuntimeException {}
}
