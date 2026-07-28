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

import com.bytequay.app.developmentflow.stage.StageCheckpoint;
import com.bytequay.app.developmentflow.stage.StageKind;
import com.bytequay.app.developmentflow.task.TaskLifecycle;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Durable support facts and fixed Cleanup graph for Task-owned controls. */
@Component
public final class SqliteTaskControlRuntimeStore
{
    private static final List<String> STEP_KINDS = List.of(
            "PROVE_NO_NEW_ADMISSIONS",
            "RECONCILE_OPEN_WORK",
            "STOP_PROVIDER_SESSIONS",
            "RECONCILE_VALIDATION",
            "SEAL_REVIEW_STATE",
            "DISMISS_TASK_INTERACTIONS",
            "RELEASE_RUNTIME_LEASES",
            "REMOVE_WORKTREE",
            "DELETE_LOCAL_BRANCH",
            "DELETE_REMOTE_BRANCH",
            "RECORD_FINAL_EVIDENCE");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SqliteTaskControlRuntimeStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = new TransactionTemplate(requireNonNull(
                transactionManager, "transactionManager is null"));
    }

    public List<ControlContext> pending()
    {
        return jdbc.query("""
                SELECT task.id AS task_id, task.lifecycle_state, task.epoch,
                       task.aggregate_version AS task_version,
                       current.stage_id, current.stage_generation,
                       owner.kind AS stage_kind, owner.version AS stage_version,
                       owner.checkpoint,
                       intent.id AS terminal_intent_id,
                       intent.kind AS terminal_intent_kind,
                       intent.source AS terminal_source,
                       intent.source_id AS terminal_source_id,
                       (SELECT transition.command_id
                          FROM task_transition transition
                         WHERE transition.task_id = task.id
                           AND transition.aggregate_version = task.aggregate_version
                         ORDER BY transition.occurred_at_ms DESC, transition.id DESC
                         LIMIT 1) AS control_command_id,
                       (SELECT transition.from_state
                          FROM task_transition transition
                         WHERE transition.task_id = task.id
                           AND transition.aggregate_version = task.aggregate_version
                         ORDER BY transition.occurred_at_ms DESC, transition.id DESC
                         LIMIT 1) AS control_source_state
                  FROM tasks task
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  LEFT JOIN task_terminal_intent intent
                    ON intent.task_id = task.id AND intent.accepted = 1
                 WHERE task.workflow_version = 'V2'
                   AND task.lifecycle_state IN (
                       'PAUSING', 'RESUMING', 'ARCHIVING', 'CANCELING', 'CLEANING')
                   AND (task.lifecycle_state <> 'CLEANING' OR NOT EXISTS (
                       SELECT 1 FROM cleanup_operation operation
                        WHERE operation.cleanup_stage_id = current.stage_id))
                 ORDER BY task.created_at_ms, task.id
                """, (result, ignored) -> context(result));
    }

    public List<String> liveTicketIds(String taskId, boolean includeCleanup)
    {
        return jdbc.query("""
                SELECT id FROM dispatch_ticket
                 WHERE task_id = ?
                   AND (? = 1 OR async_family <> 'CLEANUP')
                   AND status IN (
                       'REQUESTED', 'RETRY_WAIT', 'RECONCILE_WAIT',
                       'RESULT_PENDING', 'CLAIMED', 'RUNNING', 'DELIVERING')
                 ORDER BY created_at_ms, id
                """, (result, ignored) -> result.getString("id"),
                taskId, includeCleanup ? 1 : 0);
    }

    public String ensureBarrier(
            ControlContext context,
            TaskManager.QuiescenceReason reason,
            Instant now)
    {
        requireNonNull(context, "context is null");
        requireNonNull(reason, "reason is null");
        requireNonNull(now, "now is null");
        String barrierId = id("task-control-barrier", context.taskId(),
                context.taskEpoch(), reason, context.controlCommandId());
        Optional<String> existing = barrier(barrierId, context, reason);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        try {
            jdbc.update("""
                    INSERT INTO task_quiescence_barrier(
                        id, task_id, task_epoch, reason, status, requested_at_ms)
                    VALUES (?, ?, ?, ?, 'REQUESTED', ?)
                    """, barrierId, context.taskId(), context.taskEpoch(),
                    reason.name(), now.toEpochMilli());
        }
        catch (DataAccessException concurrent) {
            return barrier(barrierId, context, reason)
                    .orElseThrow(() -> concurrent);
        }
        return barrierId;
    }

    public Optional<String> cleanupBarrier(ControlContext context)
    {
        return barrier(context.taskId(), context.taskEpoch(), List.of(
                TaskManager.QuiescenceReason.CLEANUP,
                TaskManager.QuiescenceReason.CANCEL));
    }

    public boolean satisfyBarrier(String taskId, String barrierId, Instant now)
    {
        requireText(taskId, "taskId");
        requireText(barrierId, "barrierId");
        requireNonNull(now, "now is null");
        String status = jdbc.queryForObject("""
                SELECT status FROM task_quiescence_barrier
                 WHERE id = ? AND task_id = ?
                """, String.class, barrierId, taskId);
        if ("SATISFIED".equals(status)) {
            return true;
        }
        try {
            return jdbc.update("""
                    UPDATE task_quiescence_barrier
                       SET status = 'SATISFIED', completed_at_ms = ?,
                           evidence = ?
                     WHERE id = ? AND task_id = ? AND status = 'REQUESTED'
                    """, now.toEpochMilli(),
                    "task-control:v256:" + taskId + ":" + barrierId,
                    barrierId, taskId) == 1;
        }
        catch (DataAccessException liveWork) {
            if (rootMessage(liveWork).contains(
                    "quiescence requires all Task work across epochs to stop")) {
                return false;
            }
            throw liveWork;
        }
    }

    public TaskManager.PauseEvidence ensurePauseEvidence(
            ControlContext context, String barrierId, Instant now)
    {
        Optional<TaskManager.PauseEvidence> existing = pauseEvidence(
                context.taskId(), barrierId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Subject subject = subject(context.taskId());
        long barrierAt = jdbc.queryForObject("""
                SELECT completed_at_ms FROM task_quiescence_barrier
                 WHERE id = ? AND task_id = ? AND reason = 'PAUSE'
                   AND status = 'SATISFIED'
                """, Long.class, barrierId, context.taskId());
        long recordedAt = now.toEpochMilli();
        String digest = "pause:v1:" + hex(context.taskId()) + ":"
                + context.taskEpoch() + ":" + hex(barrierId) + ":"
                + hex(context.stageId()) + ":" + context.stageGeneration() + ":"
                + hex(context.checkpoint().name()) + ":"
                + hex(subject.codeFingerprint()) + ":" + hex(subject.headSha()) + ":"
                + hex(subject.baseSha()) + ":" + barrierAt + ":" + recordedAt;
        jdbc.update("""
                INSERT INTO task_pause_evidence(
                    barrier_id, task_id, task_epoch, stage_id, stage_generation,
                    restore_checkpoint, code_fingerprint, head_sha, base_sha,
                    barrier_completed_at_ms, stop_evidence_digest, status,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SATISFIED', ?)
                """, barrierId, context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(),
                context.checkpoint().name(), subject.codeFingerprint(),
                subject.headSha(), subject.baseSha(), barrierAt, digest, recordedAt);
        return pauseEvidence(context.taskId(), barrierId).orElseThrow();
    }

    public TaskManager.ResumeEvidence ensureResumeEvidence(
            ControlContext context, Instant now)
    {
        String sourceKind = switch (context.controlSourceState()) {
            case "PAUSED" -> "PAUSE";
            case "ARCHIVED" -> "ARCHIVE";
            default -> throw new IllegalStateException(
                    "Resume lacks its exact PAUSED/ARCHIVED predecessor");
        };
        String sourceId = jdbc.queryForObject(sourceKind.equals("PAUSE") ? """
                SELECT evidence.barrier_id
                  FROM task_command_receipt receipt
                  JOIN task_pause_evidence evidence
                    ON evidence.barrier_id = receipt.proof_id
                 WHERE receipt.task_id = ? AND receipt.cause = 'COMPLETE_PAUSE'
                   AND receipt.returned_version = ?
                   AND receipt.returned_epoch = ?
                   AND receipt.returned_current_stage_id = ?
                   AND evidence.stage_generation = ?
                   AND evidence.status = 'SATISFIED'
                """ : """
                SELECT evidence.id
                  FROM task_command_receipt receipt
                  JOIN task_archive_liveness evidence
                    ON evidence.id = receipt.proof_id
                 WHERE receipt.task_id = ? AND receipt.cause = 'COMPLETE_ARCHIVE'
                   AND receipt.returned_version = ?
                   AND receipt.returned_epoch = ?
                   AND receipt.returned_current_stage_id = ?
                   AND evidence.stage_generation = ?
                   AND evidence.status = 'SATISFIED'
                """, String.class, context.taskId(), context.taskVersion() - 1,
                context.taskEpoch(), context.stageId(), context.stageGeneration());
        String reconciliationId = id(
                "task-resume", context.taskId(), context.taskVersion(), sourceId);
        Optional<TaskManager.ResumeEvidence> existing = resumeEvidence(
                context.taskId(), reconciliationId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Subject subject = subject(context.taskId());
        long recordedAt = now.toEpochMilli();
        String digest = "resume:v2:" + hex(context.taskId()) + ":"
                + context.taskEpoch() + ":" + hex(reconciliationId) + ":"
                + hex(sourceKind) + ":" + hex(sourceId) + ":"
                + hex(context.stageId()) + ":" + context.stageGeneration() + ":"
                + hex(context.checkpoint().name()) + ":"
                + hex(subject.codeFingerprint()) + ":" + hex(subject.headSha()) + ":"
                + hex(subject.baseSha()) + ":" + recordedAt;
        jdbc.update("""
                INSERT INTO task_resume_reconciliation_v256(
                    id, task_id, task_epoch, source_kind, source_id,
                    stage_id, stage_generation, restore_checkpoint,
                    code_fingerprint, head_sha, base_sha,
                    reconciliation_digest, status, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SATISFIED', ?)
                """, reconciliationId, context.taskId(), context.taskEpoch(),
                sourceKind, sourceId, context.stageId(), context.stageGeneration(),
                context.checkpoint().name(), subject.codeFingerprint(),
                subject.headSha(), subject.baseSha(), digest, recordedAt);
        return resumeEvidence(context.taskId(), reconciliationId).orElseThrow();
    }

    public ResumeHandoff ensureResumeHandoff(
            ControlContext context,
            TaskManager.ResumeEvidence evidence,
            Instant now)
    {
        requireNonNull(context, "context is null");
        requireNonNull(evidence, "evidence is null");
        requireNonNull(now, "now is null");
        if (!evidence.taskId().equals(context.taskId())
                || evidence.taskEpoch() != context.taskEpoch()
                || !evidence.stageId().equals(context.stageId())
                || evidence.stageGeneration() != context.stageGeneration()
                || evidence.restoreCheckpoint() != context.checkpoint()) {
            throw new IllegalArgumentException(
                    "Resume evidence does not match its current Stage owner");
        }
        Optional<ResumeHandoff> existing = resumeHandoff(
                context.taskId(), evidence.reconciliationId());
        if (existing.isPresent()) {
            return requireResumeHandoff(
                    existing.orElseThrow(), context, evidence);
        }

        Subject subject = subject(context.taskId());
        String handoffId = id(
                "task-resume-handoff", context.taskId(), context.taskVersion(),
                evidence.reconciliationId());
        long createdAt = now.toEpochMilli();
        String digest = "resume-handoff:v1:" + hex(handoffId) + ":"
                + hex(context.taskId()) + ":" + context.taskEpoch() + ":"
                + context.taskVersion() + ":" + hex(evidence.reconciliationId()) + ":"
                + hex(context.stageId()) + ":" + hex(context.stageKind().name()) + ":"
                + context.stageGeneration() + ":" + context.stageVersion() + ":"
                + hex(context.checkpoint().name()) + ":"
                + hex(subject.codeFingerprint()) + ":" + hex(subject.headSha()) + ":"
                + hex(subject.baseSha()) + ":" + createdAt;
        try {
            jdbc.update("""
                    INSERT INTO task_resume_handoff_v256(
                        id, task_id, task_epoch, task_version, reconciliation_id,
                        stage_id, stage_kind, stage_generation, stage_version,
                        restore_checkpoint, code_fingerprint, head_sha, base_sha,
                        request_digest, status, created_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, handoffId, context.taskId(), context.taskEpoch(),
                    context.taskVersion(), evidence.reconciliationId(),
                    context.stageId(), context.stageKind().name(),
                    context.stageGeneration(), context.stageVersion(),
                    context.checkpoint().name(), subject.codeFingerprint(),
                    subject.headSha(), subject.baseSha(), digest, createdAt);
        }
        catch (DataAccessException concurrent) {
            ResumeHandoff replay = resumeHandoff(
                    context.taskId(), evidence.reconciliationId())
                    .orElseThrow(() -> concurrent);
            return requireResumeHandoff(replay, context, evidence);
        }
        return requireResumeHandoff(
                resumeHandoff(context.taskId(), evidence.reconciliationId())
                        .orElseThrow(),
                context,
                evidence);
    }

    public ResumeHandoff acceptResumeHandoff(
            ResumeHandoff handoff,
            TaskResumeOwner.Acceptance acceptance,
            Instant now)
    {
        requireNonNull(handoff, "handoff is null");
        requireNonNull(acceptance, "acceptance is null");
        requireNonNull(now, "now is null");
        if (!handoff.id().equals(acceptance.handoffId())) {
            throw new IllegalArgumentException(
                    "Resume owner accepted another handoff");
        }
        if (handoff.accepted()) {
            return requireAcceptanceReplay(handoff, acceptance);
        }
        jdbc.update("""
                UPDATE task_resume_handoff_v256
                   SET status = 'ACCEPTED', owner_proof_id = ?, accepted_by = ?,
                       accepted_at_ms = ?
                 WHERE id = ? AND status = 'PENDING' AND request_digest = ?
                """, acceptance.ownerProofId(), acceptance.acceptedBy(),
                now.toEpochMilli(), handoff.id(), handoff.requestDigest());
        ResumeHandoff persisted = resumeHandoff(
                handoff.taskId(), handoff.reconciliationId()).orElseThrow();
        return requireAcceptanceReplay(persisted, acceptance);
    }

    public TaskManager.ArchiveEvidence ensureArchiveEvidence(
            ControlContext context, Instant now)
    {
        String evidenceId = id(
                "task-archive", context.taskId(), context.taskVersion());
        Optional<TaskManager.ArchiveEvidence> existing = archiveEvidence(
                context.taskId(), evidenceId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        long recordedAt = now.toEpochMilli();
        String digest = "archive:v1:" + hex(context.taskId()) + ":"
                + context.taskEpoch() + ":" + hex(evidenceId) + ":"
                + hex(context.stageId()) + ":" + context.stageGeneration()
                + ":0".repeat(22) + ":" + recordedAt;
        jdbc.update("""
                INSERT INTO task_archive_liveness(
                    id, task_id, task_epoch, stage_id, stage_generation,
                    active_task_turn_count, active_stage_turn_count,
                    active_review_turn_count, active_plan_review_count,
                    active_validation_count, active_brain_episode_count,
                    active_provision_operation_count, active_dispatch_count,
                    active_agent_execution_count, unreconciled_execution_count,
                    live_capacity_lease_count, live_worktree_lease_count,
                    active_quiescence_count, active_replan_count,
                    active_feedback_batch_count, active_publish_operation_count,
                    unreconciled_publish_operation_count,
                    active_publish_effect_count,
                    active_publish_authorization_count, open_permission_count,
                    accepted_terminal_intent_count, open_cleanup_stage_count,
                    liveness_digest, status, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, ?, 'SATISFIED', ?)
                """, evidenceId, context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(), digest, recordedAt);
        return archiveEvidence(context.taskId(), evidenceId).orElseThrow();
    }

    public TerminalAcceptance ensureTerminalAcceptance(
            ControlContext context, Instant now)
    {
        requireText(context.terminalIntentId(), "terminalIntentId");
        requireText(context.terminalSource(), "terminalSource");
        requireText(context.terminalSourceId(), "terminalSourceId");
        Optional<TerminalAcceptance> existing = terminalAcceptance(context.taskId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        String acceptanceId = id("task-terminal-acceptance", context.terminalIntentId());
        jdbc.update("""
                INSERT INTO task_terminal_acceptance(
                    id, task_terminal_intent_id, task_id, task_epoch, kind,
                    source_kind, source_id, remote_terminal_observation_id,
                    observed_head_sha, accepted_by, accepted_at_ms, evidence)
                SELECT ?, intent.id, intent.task_id, task.epoch, intent.kind,
                       intent.source, intent.source_id, remote.id,
                       intent.observed_head_sha, 'TaskManager', ?,
                       'accepted exact Task terminal intent'
                  FROM task_terminal_intent intent
                  JOIN tasks task ON task.id = intent.task_id
                  LEFT JOIN remote_terminal_observation remote
                    ON remote.task_terminal_intent_id = intent.id
                 WHERE intent.id = ? AND intent.task_id = ? AND intent.accepted = 1
                """, acceptanceId, now.toEpochMilli(),
                context.terminalIntentId(), context.taskId());
        return terminalAcceptance(context.taskId()).orElseThrow();
    }

    public CancellationTarget cancellationTarget(
            ControlContext context, TerminalAcceptance acceptance)
    {
        long generation = jdbc.queryForObject("""
                SELECT COALESCE(MAX(generation), 0) + 1
                  FROM stage WHERE task_id = ? AND kind = 'CLEANUP'
                """, Long.class, context.taskId());
        return new CancellationTarget(
                id("cancel-cleanup-stage", acceptance.id()), generation);
    }

    public void ensureCleanupStage(
            ControlContext context,
            TerminalAcceptance acceptance,
            Instant now)
    {
        int existing = count("""
                SELECT COUNT(*) FROM cleanup_stage
                 WHERE stage_id = ? AND task_id = ? AND task_epoch = ?
                   AND generation = ? AND terminal_acceptance_id = ?
                """, context.stageId(), context.taskId(), context.taskEpoch(),
                context.stageGeneration(), acceptance.id());
        if (existing == 1) {
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO cleanup_stage(
                    stage_id, task_id, task_epoch, generation,
                    terminal_acceptance_id, task_terminal_intent_id,
                    terminal_reason, task_policy_revision_id,
                    remote_pr_binding_id, remote_pr_disposition,
                    local_branch_requirement, remote_branch_requirement,
                    opened_at_ms)
                SELECT owner.id, task.id, task.epoch, owner.generation,
                       acceptance.id, intent.id, acceptance.kind,
                       policy.id, binding.id,
                       CASE WHEN binding.id IS NULL THEN 'NO_REMOTE_PR'
                            WHEN acceptance.kind = 'CANCELED' THEN 'PRESERVE_OPEN'
                            ELSE 'REMOTE_ALREADY_TERMINAL' END,
                       CASE WHEN policy.delete_local_branch_on_cleanup = 1
                            THEN 'REQUIRED' ELSE 'NOT_APPLICABLE' END,
                       CASE WHEN binding.id IS NULL OR acceptance.kind = 'CANCELED'
                            THEN 'NOT_APPLICABLE'
                            WHEN policy.require_remote_branch_cleanup = 1
                            THEN 'REQUIRED' ELSE 'OPTIONAL' END,
                       ?
                  FROM tasks task
                  JOIN task_current_stage current ON current.task_id = task.id
                  JOIN stage owner ON owner.id = current.stage_id
                  JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                  JOIN task_terminal_intent intent ON intent.task_id = task.id
                     AND intent.accepted = 1
                  JOIN task_terminal_acceptance acceptance
                    ON acceptance.task_terminal_intent_id = intent.id
                  LEFT JOIN remote_pr_binding binding ON binding.task_id = task.id
                 WHERE task.id = ? AND owner.id = ? AND owner.kind = 'CLEANUP'
                   AND owner.generation = ? AND owner.checkpoint = 'WAITING_QUIESCENCE'
                   AND task.lifecycle_state = 'CLEANING' AND task.epoch = ?
                   AND acceptance.id = ?
                """, now.toEpochMilli(), context.taskId(), context.stageId(),
                context.stageGeneration(), context.taskEpoch(), acceptance.id());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Cleanup Stage subtype no longer owns the current Task");
        }
    }

    public CleanupGraph ensureCleanupGraph(
            ControlContext context,
            TerminalAcceptance acceptance,
            String barrierId,
            Instant now)
    {
        Optional<CleanupGraph> existing = cleanupGraph(context.stageId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        return inTransaction(() -> {
            Optional<CleanupGraph> replay = cleanupGraph(context.stageId());
            if (replay.isPresent()) {
                return replay.orElseThrow();
            }
            int barrier = count("""
                    SELECT COUNT(*) FROM task_quiescence_barrier
                     WHERE id = ? AND task_id = ? AND task_epoch = ?
                       AND reason IN ('CANCEL', 'CLEANUP') AND status = 'SATISFIED'
                    """, barrierId, context.taskId(), context.taskEpoch());
            if (barrier != 1) {
                throw new IllegalStateException("Cleanup barrier is not satisfied");
            }
            String operationId = id("cleanup-operation-fence", context.stageId());
            String ticketId = id("cleanup-ticket", context.stageId());
            String ledgerId = id("cleanup-operation", context.stageId());
            long at = now.toEpochMilli();
            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch,
                        stage_id, stage_generation, attempt, status, created_at_ms)
                    SELECT ?, ?, 'RUN_CLEANUP_OPERATION', 'CLEANUP',
                           'STAGE', owner.id, 'CLEANUP_OPERATION_RESULT', 256,
                           0, 1, 1, trunk.workspace_id, task.thread_id,
                           task.id, task.epoch, owner.id, owner.generation,
                           1, 'REQUESTED', ?
                      FROM tasks task
                      JOIN threads trunk ON trunk.id = task.thread_id
                      JOIN task_current_stage current ON current.task_id = task.id
                      JOIN stage owner ON owner.id = current.stage_id
                      JOIN cleanup_stage cleanup ON cleanup.stage_id = owner.id
                     WHERE task.id = ? AND task.lifecycle_state = 'CLEANING'
                       AND task.epoch = ? AND owner.id = ?
                       AND owner.generation = ?
                       AND owner.checkpoint = 'WAITING_QUIESCENCE'
                    """, ticketId, operationId, at, context.taskId(),
                    context.taskEpoch(), context.stageId(), context.stageGeneration());
            jdbc.update("""
                    INSERT INTO cleanup_operation(
                        id, cleanup_stage_id, task_id, task_epoch,
                        stage_generation, terminal_acceptance_id,
                        dispatch_ticket_id, operation_id, semantic_attempt,
                        status, step_count, requested_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'REQUESTED', 11, ?)
                    """, ledgerId, context.stageId(), context.taskId(),
                    context.taskEpoch(), context.stageGeneration(), acceptance.id(),
                    ticketId, operationId, at);
            Requirements requirements = requirements(context.stageId());
            for (int index = 0; index < STEP_KINDS.size(); index++) {
                int ordinal = index + 1;
                String requirement = switch (ordinal) {
                    case 9 -> requirements.localBranch();
                    case 10 -> requirements.remoteBranch();
                    default -> "REQUIRED";
                };
                jdbc.update("""
                        INSERT INTO cleanup_step(
                            id, cleanup_operation_id, task_id, task_epoch,
                            cleanup_stage_id, stage_generation, ordinal, kind,
                            requirement, idempotency_key, status,
                            attempt_count, execute_attempt_count, attempt_limit)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', 0, 0, 3)
                        """, id("cleanup-step", context.stageId(), ordinal),
                        ledgerId, context.taskId(), context.taskEpoch(),
                        context.stageId(), context.stageGeneration(), ordinal,
                        STEP_KINDS.get(index), requirement,
                        "cleanup:" + context.stageId() + ":" + ordinal);
            }
            return cleanupGraph(context.stageId()).orElseThrow();
        });
    }

    private Optional<String> barrier(
            String taskId,
            long taskEpoch,
            List<TaskManager.QuiescenceReason> reasons)
    {
        String placeholders = String.join(",", reasons.stream().map(ignored -> "?").toList());
        Object[] arguments = new Object[2 + reasons.size()];
        arguments[0] = taskId;
        arguments[1] = taskEpoch;
        for (int index = 0; index < reasons.size(); index++) {
            arguments[index + 2] = reasons.get(index).name();
        }
        return jdbc.query("""
                SELECT id FROM task_quiescence_barrier
                 WHERE task_id = ? AND task_epoch = ?
                   AND reason IN (%s) AND status IN ('REQUESTED', 'SATISFIED')
                 ORDER BY CASE status WHEN 'SATISFIED' THEN 0 ELSE 1 END,
                          requested_at_ms DESC, id DESC LIMIT 1
                """.formatted(placeholders),
                (result, ignored) -> result.getString("id"), arguments)
                .stream().findFirst();
    }

    private Optional<String> barrier(
            String barrierId,
            ControlContext context,
            TaskManager.QuiescenceReason reason)
    {
        return jdbc.query("""
                SELECT id FROM task_quiescence_barrier
                 WHERE id = ? AND task_id = ? AND task_epoch = ? AND reason = ?
                   AND status IN ('REQUESTED', 'SATISFIED')
                """, (result, ignored) -> result.getString("id"),
                barrierId, context.taskId(), context.taskEpoch(), reason.name())
                .stream().findFirst();
    }

    private Optional<TaskManager.PauseEvidence> pauseEvidence(
            String taskId, String barrierId)
    {
        return jdbc.query("""
                SELECT task_id, task_epoch, barrier_id, stage_id,
                       stage_generation, restore_checkpoint, stop_evidence_digest
                  FROM task_pause_evidence
                 WHERE task_id = ? AND barrier_id = ? AND status = 'SATISFIED'
                """, (result, ignored) -> new TaskManager.PauseEvidence(
                        result.getString("task_id"), result.getLong("task_epoch"),
                        result.getString("barrier_id"), result.getString("stage_id"),
                        result.getLong("stage_generation"),
                        StageCheckpoint.valueOf(result.getString("restore_checkpoint")),
                        result.getString("stop_evidence_digest")),
                taskId, barrierId).stream().findFirst();
    }

    private Optional<TaskManager.ResumeEvidence> resumeEvidence(
            String taskId, String reconciliationId)
    {
        return jdbc.query("""
                SELECT task_id, task_epoch, id, stage_id, stage_generation,
                       restore_checkpoint, reconciliation_digest
                  FROM task_resume_reconciliation_v256
                 WHERE task_id = ? AND id = ? AND status = 'SATISFIED'
                """, (result, ignored) -> new TaskManager.ResumeEvidence(
                        result.getString("task_id"), result.getLong("task_epoch"),
                        result.getString("id"), result.getString("stage_id"),
                        result.getLong("stage_generation"),
                        StageCheckpoint.valueOf(result.getString("restore_checkpoint")),
                        result.getString("reconciliation_digest")),
                taskId, reconciliationId).stream().findFirst();
    }

    private Optional<ResumeHandoff> resumeHandoff(
            String taskId, String reconciliationId)
    {
        return jdbc.query("""
                SELECT id, task_id, task_epoch, task_version, reconciliation_id,
                       stage_id, stage_kind, stage_generation, stage_version,
                       restore_checkpoint, code_fingerprint, head_sha, base_sha,
                       request_digest, status, owner_proof_id, accepted_by,
                       accepted_at_ms, created_at_ms
                  FROM task_resume_handoff_v256
                 WHERE task_id = ? AND reconciliation_id = ?
                """, (result, ignored) -> new ResumeHandoff(
                        result.getString("id"), result.getString("task_id"),
                        result.getLong("task_epoch"), result.getLong("task_version"),
                        result.getString("reconciliation_id"),
                        result.getString("stage_id"),
                        StageKind.valueOf(result.getString("stage_kind")),
                        result.getLong("stage_generation"),
                        result.getLong("stage_version"),
                        StageCheckpoint.valueOf(result.getString("restore_checkpoint")),
                        result.getString("code_fingerprint"),
                        result.getString("head_sha"), result.getString("base_sha"),
                        result.getString("request_digest"),
                        ResumeHandoffStatus.valueOf(result.getString("status")),
                        result.getString("owner_proof_id"),
                        result.getString("accepted_by"),
                        nullableLong(result, "accepted_at_ms"),
                        result.getLong("created_at_ms")),
                taskId, reconciliationId).stream().findFirst();
    }

    private Optional<TaskManager.ArchiveEvidence> archiveEvidence(
            String taskId, String evidenceId)
    {
        return jdbc.query("""
                SELECT task_id, task_epoch, id, stage_id, stage_generation,
                       liveness_digest
                  FROM task_archive_liveness
                 WHERE task_id = ? AND id = ? AND status = 'SATISFIED'
                """, (result, ignored) -> new TaskManager.ArchiveEvidence(
                        result.getString("task_id"), result.getLong("task_epoch"),
                        result.getString("id"), result.getString("stage_id"),
                        result.getLong("stage_generation"),
                        result.getString("liveness_digest")),
                taskId, evidenceId).stream().findFirst();
    }

    private Optional<TerminalAcceptance> terminalAcceptance(String taskId)
    {
        return jdbc.query("""
                SELECT acceptance.id, acceptance.task_terminal_intent_id,
                       acceptance.kind
                  FROM task_terminal_acceptance acceptance
                 WHERE acceptance.task_id = ?
                """, (result, ignored) -> new TerminalAcceptance(
                        result.getString("id"),
                        result.getString("task_terminal_intent_id"),
                        TaskManager.TerminalOutcome.valueOf(result.getString("kind"))),
                taskId).stream().findFirst();
    }

    private Optional<CleanupGraph> cleanupGraph(String stageId)
    {
        return jdbc.query("""
                SELECT operation.id, operation.operation_id,
                       operation.dispatch_ticket_id,
                       (SELECT COUNT(*) FROM cleanup_step step
                         WHERE step.cleanup_operation_id = operation.id) AS steps
                  FROM cleanup_operation operation
                 WHERE operation.cleanup_stage_id = ?
                """, (result, ignored) -> new CleanupGraph(
                        result.getString("id"), result.getString("operation_id"),
                        result.getString("dispatch_ticket_id"),
                        result.getInt("steps")), stageId).stream().findFirst();
    }

    private Requirements requirements(String stageId)
    {
        return jdbc.queryForObject("""
                SELECT local_branch_requirement, remote_branch_requirement
                  FROM cleanup_stage WHERE stage_id = ?
                """, (result, ignored) -> new Requirements(
                        result.getString("local_branch_requirement"),
                        result.getString("remote_branch_requirement")), stageId);
    }

    private Subject subject(String taskId)
    {
        return jdbc.queryForObject("""
                SELECT code_fingerprint, head_sha, base_sha
                  FROM task_current_code_subject_v230 WHERE task_id = ?
                """, (result, ignored) -> new Subject(
                        result.getString("code_fingerprint"),
                        result.getString("head_sha"),
                        result.getString("base_sha")), taskId);
    }

    private static ResumeHandoff requireResumeHandoff(
            ResumeHandoff handoff,
            ControlContext context,
            TaskManager.ResumeEvidence evidence)
    {
        if (!handoff.taskId().equals(context.taskId())
                || handoff.taskEpoch() != context.taskEpoch()
                || handoff.taskVersion() != context.taskVersion()
                || !handoff.reconciliationId().equals(evidence.reconciliationId())
                || !handoff.stageId().equals(context.stageId())
                || handoff.stageKind() != context.stageKind()
                || handoff.stageGeneration() != context.stageGeneration()
                || handoff.stageVersion() != context.stageVersion()
                || handoff.restoreCheckpoint() != context.checkpoint()) {
            throw new IllegalStateException(
                    "Persisted resume handoff does not match its current owner fence");
        }
        return handoff;
    }

    private static ResumeHandoff requireAcceptanceReplay(
            ResumeHandoff handoff, TaskResumeOwner.Acceptance acceptance)
    {
        if (!handoff.accepted()
                || !acceptance.ownerProofId().equals(handoff.ownerProofId())
                || !acceptance.acceptedBy().equals(handoff.acceptedBy())) {
            throw new IllegalStateException(
                    "Resume owner replayed a different acceptance");
        }
        return handoff;
    }

    private int count(String sql, Object... arguments)
    {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        return count == null ? 0 : count;
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        return transactions.execute(ignored -> work.get());
    }

    private static ControlContext context(ResultSet result)
            throws SQLException
    {
        String intent = result.getString("terminal_intent_kind");
        return new ControlContext(
                result.getString("task_id"),
                TaskLifecycle.valueOf(result.getString("lifecycle_state")),
                result.getLong("epoch"), result.getLong("task_version"),
                result.getString("stage_id"),
                StageKind.valueOf(result.getString("stage_kind")),
                result.getLong("stage_generation"),
                result.getLong("stage_version"),
                StageCheckpoint.valueOf(result.getString("checkpoint")),
                result.getString("terminal_intent_id"),
                intent == null ? null : TaskManager.TerminalOutcome.valueOf(intent),
                result.getString("terminal_source"),
                result.getString("terminal_source_id"),
                result.getString("control_command_id"),
                result.getString("control_source_state"));
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String id(String namespace, Object... parts)
    {
        StringBuilder value = new StringBuilder(namespace);
        for (Object part : parts) {
            value.append('\u001f').append(part);
        }
        return UUID.nameUUIDFromBytes(
                value.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String hex(String value)
    {
        return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static String rootMessage(Throwable failure)
    {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    public record ControlContext(
            String taskId,
            TaskLifecycle lifecycle,
            long taskEpoch,
            long taskVersion,
            String stageId,
            StageKind stageKind,
            long stageGeneration,
            long stageVersion,
            StageCheckpoint checkpoint,
            String terminalIntentId,
            TaskManager.TerminalOutcome terminalIntent,
            String terminalSource,
            String terminalSourceId,
            String controlCommandId,
            String controlSourceState)
    {
        public ControlContext
        {
            requireText(taskId, "taskId");
            requireNonNull(lifecycle, "lifecycle is null");
            requireText(stageId, "stageId");
            requireNonNull(stageKind, "stageKind is null");
            requireNonNull(checkpoint, "checkpoint is null");
            requireText(controlCommandId, "controlCommandId");
            requireText(controlSourceState, "controlSourceState");
            if (taskEpoch < 1 || taskVersion < 0
                    || stageGeneration < 1 || stageVersion < 0) {
                throw new IllegalArgumentException("control owner fence is invalid");
            }
        }
    }

    public record TerminalAcceptance(
            String id,
            String terminalIntentId,
            TaskManager.TerminalOutcome kind)
    {
        public TerminalAcceptance
        {
            requireText(id, "id");
            requireText(terminalIntentId, "terminalIntentId");
            requireNonNull(kind, "kind is null");
        }
    }

    public record CleanupGraph(
            String ledgerId, String operationId, String ticketId, int stepCount)
    {
        public CleanupGraph
        {
            requireText(ledgerId, "ledgerId");
            requireText(operationId, "operationId");
            requireText(ticketId, "ticketId");
            if (stepCount != 11) {
                throw new IllegalArgumentException("Cleanup graph requires eleven steps");
            }
        }
    }

    public record CancellationTarget(String stageId, long generation)
    {
        public CancellationTarget
        {
            requireText(stageId, "stageId");
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    public record ResumeHandoff(
            String id,
            String taskId,
            long taskEpoch,
            long taskVersion,
            String reconciliationId,
            String stageId,
            StageKind stageKind,
            long stageGeneration,
            long stageVersion,
            StageCheckpoint restoreCheckpoint,
            String codeFingerprint,
            String headSha,
            String baseSha,
            String requestDigest,
            ResumeHandoffStatus status,
            String ownerProofId,
            String acceptedBy,
            Long acceptedAtMs,
            long createdAtMs)
    {
        public ResumeHandoff
        {
            requireText(id, "id");
            requireText(taskId, "taskId");
            requireText(reconciliationId, "reconciliationId");
            requireText(stageId, "stageId");
            requireNonNull(stageKind, "stageKind is null");
            requireNonNull(restoreCheckpoint, "restoreCheckpoint is null");
            requireText(codeFingerprint, "codeFingerprint");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            requireText(requestDigest, "requestDigest");
            requireNonNull(status, "status is null");
            if (taskEpoch < 1 || taskVersion < 0
                    || stageGeneration < 1 || stageVersion < 0
                    || createdAtMs < 1) {
                throw new IllegalArgumentException("resume handoff fence is invalid");
            }
            if (stageKind == StageKind.CLEANUP) {
                throw new IllegalArgumentException(
                        "Cleanup Stage cannot own a Task resume");
            }
            if (status == ResumeHandoffStatus.PENDING
                    && (ownerProofId != null || acceptedBy != null
                    || acceptedAtMs != null)) {
                throw new IllegalArgumentException(
                        "pending resume handoff has acceptance fields");
            }
            if (status == ResumeHandoffStatus.ACCEPTED) {
                requireText(ownerProofId, "ownerProofId");
                requireText(acceptedBy, "acceptedBy");
                requireNonNull(acceptedAtMs, "acceptedAtMs is null");
                if (acceptedAtMs < createdAtMs) {
                    throw new IllegalArgumentException(
                            "resume acceptance predates its request");
                }
            }
        }

        public boolean accepted()
        {
            return status == ResumeHandoffStatus.ACCEPTED;
        }

        public TaskResumeOwner.Request request()
        {
            return new TaskResumeOwner.Request(
                    id, taskId, taskEpoch, taskVersion, stageId, stageKind,
                    stageGeneration, stageVersion, restoreCheckpoint,
                    reconciliationId, codeFingerprint, headSha, baseSha);
        }
    }

    public enum ResumeHandoffStatus
    {
        PENDING,
        ACCEPTED
    }

    private record Subject(String codeFingerprint, String headSha, String baseSha) {}

    private record Requirements(String localBranch, String remoteBranch) {}
}
