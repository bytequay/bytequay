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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.RemoteCiPolicy;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteObservationOperationHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Exact persistence boundary for Remote observation, CI, and branch sync. */
@Repository
public class SqliteRemoteRuntimeStore
        implements RemoteObservationOperationHandler.Store,
        RemoteEffectOperationHandler.Store
{
    private final JdbcTemplate jdbc;

    public SqliteRemoteRuntimeStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public RemoteContext requireRemoteContext(String taskId, String stageId)
    {
        List<RemoteContext> rows = jdbc.query("""
                SELECT task.id AS task_id, task.epoch AS task_epoch,
                       task.thread_id AS trunk_id, trunk.workspace_id,
                       remote.stage_id, remote.generation AS stage_generation,
                       owner.version AS stage_version,
                       remote.remote_pr_binding_id,
                       binding.remote_repository_id,
                       binding.remote_pr_number, binding.remote_head_ref,
                       remote.current_head_sha, remote.current_base_sha,
                       identity.worktree_path,
                       policy.id AS ci_policy_revision_id,
                       policy.none_outcome, policy.missing_outcome,
                       policy.queued_outcome, policy.pending_outcome,
                       policy.neutral_outcome, policy.skipped_outcome,
                       policy.canceled_outcome
                FROM remote_development_stage remote
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = remote.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN remote_pr_binding binding
                  ON binding.id = remote.remote_pr_binding_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN remote_ci_policy_revision policy ON policy.id = (
                    SELECT candidate.id
                    FROM remote_ci_policy_revision candidate
                    WHERE candidate.task_id = task.id
                      AND candidate.remote_pr_binding_id = binding.id
                    ORDER BY candidate.revision DESC
                    LIMIT 1)
                WHERE task.id = ? AND remote.stage_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                  AND current.stage_id = remote.stage_id
                  AND current.stage_generation = remote.generation
                """, (rs, row) -> remoteContext(rs), taskId, stageId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one current Remote Development context, found "
                            + rows.size());
        }
        RemoteContext context = rows.getFirst();
        Set<String> required = new LinkedHashSet<>(jdbc.query("""
                SELECT check_name
                FROM remote_ci_required_check
                WHERE ci_policy_revision_id = ?
                ORDER BY check_name
                """, (rs, row) -> rs.getString(1), context.ciPolicyRevisionId()));
        return context.withRequiredChecks(required);
    }

    public Optional<ObservationRequest> findLiveObservation(String stageId)
    {
        return jdbc.query("""
                SELECT id, operation_id, task_id,
                       remote_development_stage_id AS stage_id,
                       semantic_attempt, requested_at_ms
                FROM remote_observation_operation
                WHERE remote_development_stage_id = ?
                  AND status IN ('REQUESTED', 'DISPATCHED')
                """, (rs, row) -> new ObservationRequest(
                        rs.getString("id"), rs.getString("operation_id"),
                        rs.getString("task_id"), rs.getString("stage_id"),
                        rs.getInt("semantic_attempt"),
                        instant(rs, "requested_at_ms")), stageId)
                .stream().findFirst();
    }

    /** Current V2 Remote owners whose last observation is old and has no live ticket. */
    public List<ObservationTarget> findDueObservations(
            Instant requestedNotAfter, int limit)
    {
        requireNonNull(requestedNotAfter, "requestedNotAfter is null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT remote.task_id, remote.stage_id,
                       COALESCE(MAX(operation.requested_at_ms), 0) AS last_requested_at_ms
                FROM remote_development_stage remote
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = remote.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN remote_observation_operation operation
                  ON operation.remote_development_stage_id = remote.stage_id
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch > 0
                  AND owner.completed_at_ms IS NULL
                  AND current.stage_id = remote.stage_id
                  AND current.stage_generation = remote.generation
                  AND NOT EXISTS (
                      SELECT 1 FROM remote_observation_operation live
                      WHERE live.remote_development_stage_id = remote.stage_id
                        AND live.status IN ('REQUESTED', 'DISPATCHED'))
                GROUP BY remote.task_id, remote.stage_id
                HAVING COALESCE(MAX(operation.requested_at_ms), 0) <= ?
                ORDER BY last_requested_at_ms, remote.task_id, remote.stage_id
                LIMIT ?
                """, (rs, row) -> new ObservationTarget(
                        rs.getString("task_id"),
                        rs.getString("stage_id"),
                        Instant.ofEpochMilli(rs.getLong("last_requested_at_ms"))),
                requestedNotAfter.toEpochMilli(), limit);
    }

    public ObservationRequest insertObservation(RemoteContext context, Instant at)
    {
        requireTransaction();
        int attempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(semantic_attempt), 0) + 1
                FROM remote_observation_operation
                WHERE remote_development_stage_id = ?
                """, Integer.class, context.stageId());
        String operationRowId = id(
                "remote-observation-row", context.stageId() + ":" + attempt);
        String operationId = id(
                "remote-observation-operation", context.stageId() + ":" + attempt);
        String ticketId = id(
                "remote-observation-ticket", context.stageId() + ":" + attempt);
        jdbc.update("""
                INSERT INTO remote_observation_operation(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    ci_policy_revision_id, operation_id, semantic_attempt,
                    expected_head_sha, expected_base_sha, status,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, operationRowId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), context.ciPolicyRevisionId(),
                operationId, attempt, context.headSha(), context.baseSha(),
                at.toEpochMilli());
        insertTicket(
                ticketId, operationId,
                RemoteObservationOperationHandler.OPERATION_KIND,
                "REMOTE_OBSERVATION", "STAGE", context.stageId(),
                RemoteObservationOperationHandler.CALLBACK_ROUTE, 64, false, false,
                context, attempt, null, context.headSha(), context.baseSha(), at);
        updateOne("""
                UPDATE remote_observation_operation
                SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Remote observation was not dispatched", operationRowId);
        return new ObservationRequest(
                operationRowId, operationId, context.taskId(),
                context.stageId(), attempt, at);
    }

    @Override
    public RemoteObservationOperationHandler.OperationContext requireObservation(
            String operationId)
    {
        List<RemoteObservationOperationHandler.OperationContext> rows = jdbc.query("""
                SELECT operation.operation_id, operation.remote_development_stage_id,
                       operation.task_epoch, operation.stage_generation,
                       operation.semantic_attempt, operation.expected_head_sha,
                       operation.expected_base_sha,
                       binding.remote_repository_id, binding.remote_pr_number,
                       binding.remote_head_ref,
                       operation.ci_policy_revision_id
                FROM remote_observation_operation operation
                JOIN remote_pr_binding binding
                  ON binding.id = operation.remote_pr_binding_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                """, (rs, row) -> new RemoteObservationOperationHandler.OperationContext(
                        rs.getString("operation_id"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("task_epoch"),
                        rs.getLong("stage_generation"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        new RemoteObservationOperationHandler.Request(
                                rs.getString("remote_repository_id"),
                                rs.getInt("remote_pr_number"),
                                rs.getString("expected_head_sha"),
                                rs.getString("expected_base_sha"),
                                requiredChecks(rs.getString(
                                        "ci_policy_revision_id")))), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one dispatched Remote observation, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public String requireObservationTaskId(String operationId)
    {
        return requireSingleString("""
                SELECT task_id FROM remote_observation_operation
                WHERE operation_id = ?
                """, operationId, "Remote observation owner is missing");
    }

    public Optional<DeliveryReceipt> findObservationReceipt(String operationId)
    {
        return jdbc.query("""
                SELECT receipt.remote_observation_operation_id AS row_id,
                       receipt.operation_id, operation.task_id,
                       receipt.raw_outcome, receipt.raw_result_digest,
                       receipt.acceptance, receipt.snapshot_id,
                       receipt.ci_evaluation_id, receipt.recorded_at_ms
                FROM remote_observation_delivery_receipt receipt
                JOIN remote_observation_operation operation
                  ON operation.id = receipt.remote_observation_operation_id
                WHERE receipt.operation_id = ?
                """, (rs, row) -> deliveryReceipt(rs), operationId)
                .stream().findFirst();
    }

    public ObservationDelivery requireObservationDelivery(String operationId)
    {
        List<ObservationDelivery> rows = jdbc.query("""
                SELECT operation.id AS row_id, operation.operation_id,
                       operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation,
                       operation.remote_pr_binding_id,
                       operation.ci_policy_revision_id,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       operation.semantic_attempt,
                       binding.remote_repository_id,
                       binding.remote_pr_number,
                       remote.current_head_sha, remote.current_base_sha,
                       remote.accepted_observation_revision,
                       task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.completed_at_ms,
                       policy.none_outcome, policy.missing_outcome,
                       policy.queued_outcome, policy.pending_outcome,
                       policy.neutral_outcome, policy.skipped_outcome,
                       policy.canceled_outcome
                FROM remote_observation_operation operation
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = operation.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN remote_pr_binding binding
                  ON binding.id = operation.remote_pr_binding_id
                JOIN remote_ci_policy_revision policy
                  ON policy.id = operation.ci_policy_revision_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> observationDelivery(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact Remote observation delivery, found "
                            + rows.size());
        }
        ObservationDelivery delivery = rows.getFirst();
        return delivery.withRequiredChecks(
                requiredChecks(delivery.ciPolicyRevisionId()));
    }

    public ObservationEvidence insertObservationEvidence(
            ObservationDelivery context,
            RemoteObservationOperationHandler.Observation observation,
            RemoteCiPolicy.Evaluation evaluation)
    {
        requireTransaction();
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(observation_revision), 0) + 1
                FROM remote_pr_snapshot
                WHERE remote_development_stage_id = ?
                """, Integer.class, context.stageId());
        String snapshotId = id("remote-snapshot", context.operationId());
        String evaluationId = id("remote-ci-evaluation", context.operationId());
        jdbc.update("""
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state,
                    effective_approval_count, write_approval_count,
                    changes_requested_count, requested_reviewer_count,
                    unresolved_thread_count, unresolved_comment_count,
                    observed_at_ms, raw_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), revision,
                observation.observationKey(), context.repositoryId(),
                context.pullRequestNumber(), observation.headSha(),
                observation.baseSha(), observation.prState().name(),
                observation.mergeability().name(),
                observation.mergeQueueState().name(),
                observation.effectiveApprovalCount(),
                observation.writeApprovalCount(),
                observation.changesRequestedCount(),
                observation.requestedReviewerCount(),
                observation.unresolvedThreadCount(),
                observation.unresolvedCommentCount(), observation.observedAtMs(),
                observation.rawEvidence());
        int index = 0;
        for (RemoteCiPolicy.Check check : evaluation.checks()) {
            jdbc.update("""
                    INSERT INTO remote_ci_check_snapshot(
                        id, remote_pr_snapshot_id, check_kind,
                        external_check_id, check_name, normalized_state,
                        provider_status, provider_conclusion, started_at_ms,
                        completed_at_ms, observed_at_ms, raw_evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id("remote-check", context.operationId() + ":" + index++),
                    snapshotId, check.kind(), check.externalId(), check.name(),
                    check.state().name(), check.providerStatus(),
                    check.providerConclusion(), check.startedAtMs(),
                    check.completedAtMs(), observation.observedAtMs(),
                    check.rawEvidence());
        }
        jdbc.update("""
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch,
                    stage_generation, head_sha, base_sha, normalized_status,
                    policy_outcome, check_count, missing_required_count,
                    evidence, evaluated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, evaluationId, context.stageId(), snapshotId,
                context.ciPolicyRevisionId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                observation.headSha(), observation.baseSha(),
                evaluation.normalizedStatus().name(), evaluation.outcome().name(),
                evaluation.checkCount(), evaluation.missingRequiredCount(),
                observation.rawEvidence() == null ? "{}" : observation.rawEvidence(),
                observation.observedAtMs());
        return new ObservationEvidence(
                snapshotId, evaluationId, revision, observation.headSha(),
                observation.baseSha(), evaluation.outcome(),
                observation.observedAtMs());
    }

    public void acceptObservation(
            ObservationDelivery context,
            ObservationEvidence evidence,
            Instant at)
    {
        requireTransaction();
        boolean changed = !context.currentHeadSha().equals(evidence.headSha())
                || !context.currentBaseSha().equals(evidence.baseSha());
        if (changed) {
            for (String kind : List.of(
                    "CI", "BRAIN", "REMOTE_REVIEW", "READINESS", "MERGE")) {
                jdbc.update("""
                        INSERT INTO remote_head_evidence_invalidation(
                            id, remote_development_stage_id,
                            accepted_snapshot_id, old_head_sha, new_head_sha,
                            old_base_sha, new_base_sha, evidence_kind,
                            invalidated_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, id("remote-invalidation",
                                evidence.snapshotId() + ":" + kind),
                        context.stageId(), evidence.snapshotId(),
                        context.currentHeadSha(), evidence.headSha(),
                        context.currentBaseSha(), evidence.baseSha(), kind,
                        at.toEpochMilli());
            }
        }
        updateOne("""
                UPDATE remote_development_stage
                SET accepted_snapshot_id = ?,
                    accepted_observation_revision = ?,
                    current_head_sha = ?, current_base_sha = ?,
                    subject_changed_at_ms = ?
                WHERE stage_id = ?
                  AND accepted_observation_revision = ?
                  AND current_head_sha = ? AND current_base_sha = ?
                """, "Remote subject changed before observation acceptance",
                evidence.snapshotId(), evidence.revision(), evidence.headSha(),
                evidence.baseSha(), evidence.observedAtMs(), context.stageId(),
                context.acceptedObservationRevision(), context.currentHeadSha(),
                context.currentBaseSha());
    }

    public void finishObservation(
            ObservationDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            ObservationEvidence evidence,
            String error,
            Instant at)
    {
        requireTransaction();
        String status = switch (rawOutcome) {
            case "SUCCEEDED" -> acceptance;
            case "CANCELED" -> "CANCELED";
            default -> "FAILED";
        };
        updateOne("""
                UPDATE remote_observation_operation
                SET status = ?, snapshot_id = ?, ci_evaluation_id = ?,
                    completed_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, "Remote observation changed before delivery", status,
                evidence == null ? null : evidence.snapshotId(),
                evidence == null ? null : evidence.ciEvaluationId(),
                at.toEpochMilli(), error, context.rowId());
        jdbc.update("""
                INSERT INTO remote_observation_delivery_receipt(
                    remote_observation_operation_id, operation_id,
                    raw_outcome, raw_result_digest, acceptance,
                    snapshot_id, ci_evaluation_id, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, context.rowId(), context.operationId(), rawOutcome,
                rawDigest, acceptance,
                evidence == null ? null : evidence.snapshotId(),
                evidence == null ? null : evidence.ciEvaluationId(),
                at.toEpochMilli());
    }

    public Optional<CiEpisode> findLiveCiEpisode(String stageId)
    {
        return jdbc.query("""
                SELECT id, remote_development_stage_id, task_id, task_epoch,
                       stage_generation, remote_pr_binding_id,
                       failed_ci_evaluation_id, subject_head_sha,
                       subject_base_sha, classification, status,
                       rerun_count, rerun_limit, fix_attempt_count,
                       fix_attempt_limit, delivery_retry_count,
                       delivery_retry_limit, push_count, push_limit,
                       last_pushed_head_sha, last_push_result_evaluation_id,
                       terminal_ci_evaluation_id, opened_at_ms,
                       completed_at_ms, stop_reason
                FROM ci_repair_episode
                WHERE remote_development_stage_id = ?
                  AND status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
                """, (rs, row) -> ciEpisode(rs), stageId)
                .stream().findFirst();
    }

    public CiEpisode openCiEpisode(
            ObservationDelivery context,
            ObservationEvidence evidence,
            String classification,
            CiBudgets budgets,
            Instant at)
    {
        requireTransaction();
        String episodeId = id("ci-repair-episode", evidence.ciEvaluationId());
        jdbc.update("""
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha,
                    subject_base_sha, classification, status,
                    rerun_limit, fix_attempt_limit, delivery_retry_limit,
                    push_limit, opened_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)
                """, episodeId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), evidence.ciEvaluationId(),
                evidence.headSha(), evidence.baseSha(), classification,
                budgets.rerunLimit(), budgets.fixAttemptLimit(),
                budgets.deliveryRetryLimit(), budgets.pushLimit(),
                at.toEpochMilli());
        return findLiveCiEpisode(context.stageId()).orElseThrow();
    }

    public void stopCiEpisode(CiEpisode episode, String reason, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE ci_repair_episode
                SET status = 'STOPPED', completed_at_ms = ?, stop_reason = ?
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
                """, "CI repair Episode changed before stop",
                at.toEpochMilli(), reason, episode.id());
    }

    public boolean hasLiveCiOperation(String episodeId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ci_repair_operation
                WHERE ci_repair_episode_id = ?
                  AND status IN ('REQUESTED', 'DISPATCHED')
                """, Integer.class, episodeId);
        return count != null && count > 0;
    }

    public EffectRequest insertCiRerun(
            RemoteContext context, CiEpisode episode, Instant at)
    {
        requireTransaction();
        int attempt = episode.rerunCount() + 1;
        String suffix = episode.id() + ":rerun:" + attempt;
        String rowId = id("ci-repair-operation-row", suffix);
        String operationId = id("ci-repair-operation", suffix);
        String ticketId = id("ci-repair-ticket", suffix);
        jdbc.update("""
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'RERUN', ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, episode.id(), context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(), operationId,
                attempt, episode.subjectHeadSha(), episode.subjectBaseSha(),
                at.toEpochMilli());
        insertTicket(
                ticketId, operationId, RemoteEffectOperationHandler.RERUN_CI,
                "GITHUB_EFFECT", "STAGE", context.stageId(),
                "REMOTE_CI_RERUN_RESULT", 32, false, false, context, attempt,
                null, episode.subjectHeadSha(), episode.subjectBaseSha(), at);
        updateOne("""
                UPDATE ci_repair_operation SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "CI rerun was not dispatched", rowId);
        updateOne("""
                UPDATE ci_repair_episode SET status = 'AWAITING_RERUN'
                WHERE id = ? AND status IN ('OPEN', 'AWAITING_RERUN')
                """, "CI repair Episode changed before rerun", episode.id());
        return new EffectRequest(
                rowId, operationId, episode.id(), null, context.taskId(),
                context.stageId(), "RERUN", attempt, at);
    }

    public Optional<EffectDeliveryReceipt> findCiEffectReceipt(String operationId)
    {
        return jdbc.query("""
                SELECT receipt.ci_repair_operation_id AS row_id,
                       receipt.operation_id, operation.task_id,
                       receipt.raw_outcome, receipt.raw_result_digest,
                       receipt.acceptance, receipt.recorded_at_ms
                FROM ci_repair_delivery_receipt receipt
                JOIN ci_repair_operation operation
                  ON operation.id = receipt.ci_repair_operation_id
                WHERE receipt.operation_id = ?
                """, (rs, row) -> effectReceipt(rs), operationId)
                .stream().findFirst();
    }

    public String requireEffectTaskId(String operationId)
    {
        return requireSingleString("""
                SELECT task_id FROM ci_repair_operation WHERE operation_id = ?
                UNION ALL
                SELECT task_id FROM branch_sync_dispatch_operation
                WHERE operation_id = ?
                """, List.of(operationId, operationId),
                "Remote effect owner is missing");
    }

    public CiEffectDelivery requireCiEffectDelivery(String operationId)
    {
        List<CiEffectDelivery> rows = jdbc.query("""
                SELECT operation.id AS row_id, operation.operation_id,
                       operation.ci_repair_episode_id, operation.kind,
                       operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation,
                       operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       episode.status AS episode_status,
                       episode.subject_head_sha, episode.subject_base_sha,
                       episode.rerun_count, episode.rerun_limit,
                       remote.current_head_sha, remote.current_base_sha,
                       task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation
                FROM ci_repair_operation operation
                JOIN ci_repair_episode episode
                  ON episode.id = operation.ci_repair_episode_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN tasks task ON task.id = operation.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> ciEffectDelivery(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact CI effect delivery, found " + rows.size());
        }
        return rows.getFirst();
    }

    public void finishCiRerun(
            CiEffectDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            boolean succeeded,
            String evidence,
            String error,
            Instant at)
    {
        requireTransaction();
        String status = "SUPERSEDED".equals(acceptance) ? "SUPERSEDED"
                : succeeded ? "SUCCEEDED"
                : "CANCELED".equals(rawOutcome) ? "CANCELED" : "FAILED";
        updateOne("""
                UPDATE ci_repair_operation
                SET status = ?, result_evidence = ?, completed_at_ms = ?,
                    error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, "CI rerun changed before delivery", status, evidence,
                at.toEpochMilli(), error, context.rowId());
        if (succeeded && "ACCEPTED".equals(acceptance)) {
            updateOne("""
                    UPDATE ci_repair_episode
                    SET rerun_count = rerun_count + 1,
                        status = 'AWAITING_RERUN'
                    WHERE id = ? AND rerun_count = ?
                      AND status = 'AWAITING_RERUN'
                    """, "CI rerun counter changed before delivery",
                    context.episodeId(), context.rerunCount());
        }
        jdbc.update("""
                INSERT INTO ci_repair_delivery_receipt(
                    ci_repair_operation_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, context.rowId(), context.operationId(), rawOutcome,
                rawDigest, acceptance, at.toEpochMilli());
    }

    public void succeedCiEpisode(
            CiEpisode episode, String evaluationId, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE ci_repair_episode
                SET status = 'SUCCEEDED', terminal_ci_evaluation_id = ?,
                    completed_at_ms = ?, stop_reason = NULL
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
                """, "CI repair Episode changed before success",
                evaluationId, at.toEpochMilli(), episode.id());
    }

    public void exhaustCiEpisode(
            CiEpisode episode, String evaluationId, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE ci_repair_episode
                SET status = 'EXHAUSTED', terminal_ci_evaluation_id = ?,
                    completed_at_ms = ?, stop_reason = 'budget exhausted'
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'EXHAUSTED', 'STOPPED')
                """, "CI repair Episode changed before exhaustion",
                evaluationId, at.toEpochMilli(), episode.id());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'EPISODE', ?, ?, 'CI_BUDGET_EXHAUSTED',
                    'OPEN', ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    status = 'OPEN', payload_json = excluded.payload_json,
                    opened_at_ms = excluded.opened_at_ms,
                    resolved_at_ms = NULL, resolution_evidence = NULL
                """, id("ci-repair-blocker", episode.id()), episode.taskId(),
                episode.stageId(), episode.id(), episode.subjectHeadSha(),
                "{\"choices\":[\"EXTEND\",\"PER_PUSH_APPROVAL\","
                        + "\"MANUAL_TAKEOVER\",\"STOP_AUTOMATION\"]}",
                at.toEpochMilli());
    }

    public CiEpisode changeCiBudget(
            String taskId,
            String episodeId,
            String commandId,
            String kind,
            int rerunDelta,
            int fixDelta,
            int pushDelta,
            String actor,
            String reason,
            Instant at)
    {
        requireTransaction();
        List<Boolean> duplicate = jdbc.query("""
                SELECT ci_repair_episode_id = ? AND kind = ?
                    AND rerun_delta = ? AND fix_delta = ? AND push_delta = ?
                    AND approved_by = ? AND reason = ? AS exact
                FROM ci_repair_budget_extension
                WHERE command_id = ?
                """, (rs, row) -> rs.getBoolean("exact"), episodeId, kind,
                rerunDelta, fixDelta, pushDelta, actor, reason, commandId);
        if (!duplicate.isEmpty()) {
            if (duplicate.size() != 1 || !duplicate.getFirst()) {
                throw new IllegalArgumentException(
                        "CI budget command was already used with other values");
            }
            return requireCiEpisode(taskId, episodeId);
        }
        String extensionId = id("ci-budget-extension", commandId);
        jdbc.update("""
                INSERT INTO ci_repair_budget_extension(
                    id, ci_repair_episode_id, command_id, kind,
                    rerun_delta, fix_delta, push_delta, approved_by,
                    reason, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, extensionId, episodeId, commandId, kind, rerunDelta,
                fixDelta, pushDelta, actor, reason, at.toEpochMilli());
        updateOne("""
                UPDATE ci_repair_episode
                SET rerun_limit = rerun_limit + ?,
                    fix_attempt_limit = fix_attempt_limit + ?,
                    push_limit = push_limit + ?,
                    status = CASE WHEN status = 'EXHAUSTED'
                        THEN 'OPEN' ELSE status END,
                    completed_at_ms = CASE WHEN status = 'EXHAUSTED'
                        THEN NULL ELSE completed_at_ms END,
                    terminal_ci_evaluation_id = CASE WHEN status = 'EXHAUSTED'
                        THEN NULL ELSE terminal_ci_evaluation_id END,
                    stop_reason = CASE WHEN status = 'EXHAUSTED'
                        THEN NULL ELSE stop_reason END
                WHERE id = ? AND task_id = ?
                  AND status NOT IN ('SUCCEEDED', 'STOPPED')
                """, "CI repair Episode cannot accept this budget",
                rerunDelta, fixDelta, pushDelta, episodeId, taskId);
        updateOne("""
                UPDATE ci_repair_budget_extension SET consumed_at_ms = ?
                WHERE id = ? AND consumed_at_ms IS NULL
                """, "CI budget authorization was not consumed",
                at.toEpochMilli(), extensionId);
        resolveCiBlocker(episodeId, "budget extended", at);
        return requireCiEpisode(taskId, episodeId);
    }

    public CiEpisode controlCiEpisode(
            String taskId,
            String episodeId,
            String commandId,
            String kind,
            String actor,
            String reason,
            Instant at)
    {
        requireTransaction();
        List<Boolean> duplicate = jdbc.query("""
                SELECT ci_repair_episode_id = ? AND kind = ?
                    AND actor = ? AND reason = ? AS exact
                FROM ci_repair_control_command
                WHERE command_id = ?
                """, (rs, row) -> rs.getBoolean("exact"), episodeId, kind,
                actor, reason, commandId);
        if (!duplicate.isEmpty()) {
            if (duplicate.size() != 1 || !duplicate.getFirst()) {
                throw new IllegalArgumentException(
                        "CI control command was already used with other values");
            }
            return requireCiEpisode(taskId, episodeId);
        }
        String controlId = id("ci-control-command", commandId);
        jdbc.update("""
                INSERT INTO ci_repair_control_command(
                    id, ci_repair_episode_id, command_id, kind, actor,
                    reason, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, controlId, episodeId, commandId, kind, actor, reason,
                at.toEpochMilli());
        updateOne("""
                UPDATE ci_repair_episode
                SET status = 'STOPPED', completed_at_ms = COALESCE(
                        completed_at_ms, ?), stop_reason = ?
                WHERE id = ? AND task_id = ?
                  AND status NOT IN ('SUCCEEDED', 'STOPPED')
                """, "CI repair Episode cannot be stopped",
                at.toEpochMilli(), kind + ": " + reason, episodeId, taskId);
        updateOne("""
                UPDATE ci_repair_control_command SET consumed_at_ms = ?
                WHERE id = ? AND consumed_at_ms IS NULL
                """, "CI control authorization was not consumed",
                at.toEpochMilli(), controlId);
        resolveCiBlocker(episodeId, kind, at);
        return requireCiEpisode(taskId, episodeId);
    }

    public CiEpisode requireCiEpisode(String taskId, String episodeId)
    {
        List<CiEpisode> rows = jdbc.query("""
                SELECT id, remote_development_stage_id, task_id, task_epoch,
                       stage_generation, remote_pr_binding_id,
                       failed_ci_evaluation_id, subject_head_sha,
                       subject_base_sha, classification, status,
                       rerun_count, rerun_limit, fix_attempt_count,
                       fix_attempt_limit, delivery_retry_count,
                       delivery_retry_limit, push_count, push_limit,
                       last_pushed_head_sha, last_push_result_evaluation_id,
                       terminal_ci_evaluation_id, opened_at_ms,
                       completed_at_ms, stop_reason
                FROM ci_repair_episode WHERE id = ? AND task_id = ?
                """, (rs, row) -> ciEpisode(rs), episodeId, taskId);
        if (rows.size() != 1) {
            throw new IllegalStateException("CI repair Episode is missing");
        }
        return rows.getFirst();
    }

    private void resolveCiBlocker(
            String episodeId, String evidence, Instant at)
    {
        jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE owner_kind = 'EPISODE' AND owner_id = ?
                  AND status = 'OPEN'
                """, at.toEpochMilli(), evidence, episodeId);
    }

    public Optional<BranchEpisode> findLiveBranchEpisode(String stageId)
    {
        return jdbc.query("""
                SELECT id, remote_development_stage_id, task_id, task_epoch,
                       stage_generation, remote_pr_binding_id,
                       source_snapshot_id, old_head_sha, observed_base_sha,
                       target_base_sha, policy_source, status, attempt_count,
                       attempt_limit, result_head_sha, result_snapshot_id,
                       opened_at_ms, completed_at_ms, error_message
                FROM branch_sync_episode
                WHERE remote_development_stage_id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
                """, (rs, row) -> branchEpisode(rs), stageId)
                .stream().findFirst();
    }

    public Optional<BranchEpisode> findBranchEpisode(String episodeId)
    {
        return jdbc.query("""
                SELECT id, remote_development_stage_id, task_id, task_epoch,
                       stage_generation, remote_pr_binding_id,
                       source_snapshot_id, old_head_sha, observed_base_sha,
                       target_base_sha, policy_source, status, attempt_count,
                       attempt_limit, result_head_sha, result_snapshot_id,
                       opened_at_ms, completed_at_ms, error_message
                FROM branch_sync_episode WHERE id = ?
                """, (rs, row) -> branchEpisode(rs), episodeId)
                .stream().findFirst();
    }

    public BranchEpisode insertBranchEpisode(
            RemoteContext context,
            String commandId,
            String targetBaseSha,
            String policySource,
            int attemptLimit,
            Instant at)
    {
        requireTransaction();
        requireNonNull(commandId, "commandId is null");
        requireNonNull(targetBaseSha, "targetBaseSha is null");
        String sourceSnapshotId = requireSingleString("""
                SELECT accepted_snapshot_id FROM remote_development_stage
                WHERE stage_id = ? AND accepted_snapshot_id IS NOT NULL
                """, context.stageId(),
                "Branch sync requires an accepted Remote snapshot");
        String episodeId = id("branch-sync-episode", commandId);
        jdbc.update("""
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    old_head_sha, observed_base_sha, target_base_sha,
                    policy_source, status, attempt_limit, opened_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)
                """, episodeId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), sourceSnapshotId, context.headSha(),
                context.baseSha(), targetBaseSha, policySource, attemptLimit,
                at.toEpochMilli());
        List<String> kinds = List.of(
                "FETCH_COMPARE", "MECHANICAL_REBASE", "CONFLICT_REPAIR",
                "VALIDATE", "BRAIN_REVIEW", "FORCE_WITH_LEASE_PUSH");
        for (int ordinal = 1; ordinal <= kinds.size(); ordinal++) {
            String kind = kinds.get(ordinal - 1);
            jdbc.update("""
                    INSERT INTO branch_sync_effect_step(
                        id, branch_sync_episode_id, ordinal, kind,
                        idempotency_key, status, attempt_limit)
                    VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?)
                    """, id("branch-sync-step", episodeId + ":" + ordinal),
                    episodeId, ordinal, kind,
                    id("branch-sync-effect", episodeId + ":" + ordinal),
                    attemptLimit);
        }
        return findBranchEpisode(episodeId).orElseThrow();
    }

    public BranchStep requireBranchStep(String episodeId, int ordinal)
    {
        List<BranchStep> rows = jdbc.query("""
                SELECT id, branch_sync_episode_id, ordinal, kind,
                       idempotency_key, status, attempt_count, attempt_limit
                FROM branch_sync_effect_step
                WHERE branch_sync_episode_id = ? AND ordinal = ?
                """, (rs, row) -> new BranchStep(
                        rs.getString("id"),
                        rs.getString("branch_sync_episode_id"),
                        rs.getInt("ordinal"), rs.getString("kind"),
                        rs.getString("idempotency_key"), rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getInt("attempt_limit")), episodeId, ordinal);
        if (rows.size() != 1) {
            throw new IllegalStateException("Branch sync step is missing");
        }
        return rows.getFirst();
    }

    public EffectRequest insertBranchEffect(
            RemoteContext context,
            BranchEpisode episode,
            BranchStep step,
            String codeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            Instant at)
    {
        requireTransaction();
        BranchEffectShape shape = branchShape(step.kind());
        int attempt = step.attemptCount() + 1;
        String suffix = step.id() + ":" + attempt;
        String rowId = id("branch-sync-operation-row", suffix);
        String operationId = id("branch-sync-operation", suffix);
        String ticketId = id("branch-sync-ticket", suffix);
        jdbc.update("""
                INSERT INTO branch_sync_dispatch_operation(
                    id, branch_sync_episode_id, branch_sync_effect_step_id,
                    remote_development_stage_id, task_id, task_epoch,
                    stage_generation, kind, operation_id, semantic_attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, target_base_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, rowId, episode.id(), step.id(), context.stageId(),
                context.taskId(), context.taskEpoch(), context.stageGeneration(),
                step.kind(), operationId, attempt, codeFingerprint,
                expectedHeadSha, expectedBaseSha, episode.targetBaseSha(),
                at.toEpochMilli());
        insertTicket(
                ticketId, operationId, shape.operationKind(), shape.family(),
                "STAGE", context.stageId(), shape.callbackRoute(),
                shape.laneMask(), true, shape.writer(), context, attempt,
                codeFingerprint, expectedHeadSha, expectedBaseSha, at);
        updateOne("""
                UPDATE branch_sync_dispatch_operation SET status = 'DISPATCHED'
                WHERE id = ? AND status = 'REQUESTED'
                """, "Branch sync effect was not dispatched", rowId);
        updateOne("""
                UPDATE branch_sync_effect_step
                SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                    claim_mode = 'EXECUTE', claim_owner = ?,
                    claimed_at_ms = ?, lease_until_ms = ?
                WHERE id = ? AND status = 'REQUESTED' AND attempt_count = ?
                """, "Branch sync step changed before dispatch",
                operationId, at.toEpochMilli(), at.plusSeconds(60).toEpochMilli(),
                step.id(), step.attemptCount());
        if (step.ordinal() == 2) {
            updateOne("""
                    UPDATE branch_sync_episode
                    SET status = 'REBASING', attempt_count = attempt_count + 1
                    WHERE id = ? AND attempt_count = ?
                      AND status IN ('OPEN', 'REBASING')
                    """, "Branch sync attempt changed before rebase",
                    episode.id(), episode.attemptCount());
        }
        else if (step.ordinal() == 4) {
            updateOne("""
                    UPDATE branch_sync_episode SET status = 'VALIDATING'
                    WHERE id = ? AND status IN ('REBASING', 'CONFLICT_REPAIR')
                    """, "Branch sync changed before validation", episode.id());
        }
        else if (step.ordinal() == 6) {
            updateOne("""
                    UPDATE branch_sync_episode SET status = 'PUSHING'
                    WHERE id = ? AND status IN ('VALIDATING', 'BRAIN_REVIEW')
                    """, "Branch sync changed before push", episode.id());
        }
        return new EffectRequest(
                rowId, operationId, episode.id(), step.id(), context.taskId(),
                context.stageId(), step.kind(), attempt, at);
    }

    public void skipBranchStep(
            BranchStep step, String evidence, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE branch_sync_effect_step
                SET status = 'SKIPPED', evidence = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, "Branch sync step changed before skip", evidence,
                at.toEpochMilli(), step.id());
    }

    public Optional<EffectDeliveryReceipt> findBranchEffectReceipt(
            String operationId)
    {
        return jdbc.query("""
                SELECT receipt.branch_sync_dispatch_operation_id AS row_id,
                       receipt.operation_id, operation.task_id,
                       receipt.raw_outcome, receipt.raw_result_digest,
                       receipt.acceptance, receipt.recorded_at_ms
                FROM branch_sync_delivery_receipt receipt
                JOIN branch_sync_dispatch_operation operation
                  ON operation.id = receipt.branch_sync_dispatch_operation_id
                WHERE receipt.operation_id = ?
                """, (rs, row) -> effectReceipt(rs), operationId)
                .stream().findFirst();
    }

    public BranchEffectDelivery requireBranchEffectDelivery(String operationId)
    {
        List<BranchEffectDelivery> rows = jdbc.query("""
                SELECT operation.id AS row_id, operation.operation_id,
                       operation.branch_sync_episode_id,
                       operation.branch_sync_effect_step_id,
                       operation.kind, operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation,
                       operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha, operation.target_base_sha,
                       episode.status AS episode_status,
                       episode.old_head_sha, episode.observed_base_sha,
                       episode.attempt_count, episode.attempt_limit,
                       step.ordinal, step.idempotency_key,
                       remote.current_head_sha, remote.current_base_sha,
                       task.lifecycle_state,
                       task.epoch AS current_task_epoch,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation
                FROM branch_sync_dispatch_operation operation
                JOIN branch_sync_episode episode
                  ON episode.id = operation.branch_sync_episode_id
                JOIN branch_sync_effect_step step
                  ON step.id = operation.branch_sync_effect_step_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN tasks task ON task.id = operation.task_id
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND step.status = 'CLAIMED'
                  AND ticket.status = 'RESULT_PENDING'
                """, (rs, row) -> branchEffectDelivery(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact branch effect delivery, found "
                            + rows.size());
        }
        return rows.getFirst();
    }

    public void finishBranchEffect(
            BranchEffectDelivery context,
            String rawOutcome,
            String rawDigest,
            String acceptance,
            RemoteEffectOperationHandler.Result result,
            Instant at)
    {
        requireTransaction();
        boolean succeeded = "ACCEPTED".equals(acceptance)
                && "SUCCEEDED".equals(rawOutcome)
                && result != null
                && result.disposition()
                    != RemoteEffectOperationHandler.Disposition.FAILED;
        String status = "SUPERSEDED".equals(acceptance) ? "SUPERSEDED"
                : succeeded ? "SUCCEEDED"
                : "INDETERMINATE".equals(rawOutcome) ? "INDETERMINATE"
                : "CANCELED".equals(rawOutcome) ? "CANCELED" : "FAILED";
        updateOne("""
                UPDATE branch_sync_dispatch_operation
                SET status = ?, result_code_fingerprint = ?,
                    result_head_sha = ?, result_evidence = ?,
                    completed_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, "Branch sync effect changed before delivery", status,
                result == null ? null : result.codeFingerprint(),
                result == null ? null : result.headSha(),
                result == null ? null : result.evidence(), at.toEpochMilli(),
                result == null ? null : result.error(), context.rowId());
        if (succeeded) {
            if (context.ordinal() == 6) {
                jdbc.update("""
                        INSERT INTO branch_sync_push_proof(
                            branch_sync_effect_step_id, branch_sync_episode_id,
                            old_head_sha, observed_base_sha, target_base_sha,
                            force_with_lease_expected_sha, pushed_head_sha,
                            force_with_lease_used, remote_probe_identity,
                            evidence, recorded_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                        """, context.stepId(), context.episodeId(),
                        context.oldHeadSha(), context.observedBaseSha(),
                        context.targetBaseSha(), context.oldHeadSha(),
                        result.headSha(), context.operationId(), result.evidence(),
                        at.toEpochMilli());
            }
            updateOne("""
                    UPDATE branch_sync_effect_step
                    SET status = 'SUCCEEDED', claim_mode = NULL,
                        claim_owner = NULL, claimed_at_ms = NULL,
                        lease_until_ms = NULL, evidence = ?,
                        completed_at_ms = ?
                    WHERE id = ? AND status = 'CLAIMED'
                    """, "Branch sync step changed before completion",
                    result.evidence(), at.toEpochMilli(), context.stepId());
        }
        else {
            updateOne("""
                    UPDATE branch_sync_effect_step
                    SET status = ?, claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        last_error = ?, completed_at_ms = ?
                    WHERE id = ? AND status = 'CLAIMED'
                    """, "Branch sync step changed before failure",
                    "INDETERMINATE".equals(rawOutcome)
                            ? "INDETERMINATE" : "FAILED",
                    result == null ? rawOutcome : result.error(),
                    at.toEpochMilli(), context.stepId());
        }
        jdbc.update("""
                INSERT INTO branch_sync_delivery_receipt(
                    branch_sync_dispatch_operation_id, operation_id,
                    raw_outcome, raw_result_digest, acceptance, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, context.rowId(), context.operationId(), rawOutcome,
                rawDigest, acceptance, at.toEpochMilli());
    }

    public void awaitBranchHead(
            BranchEpisode episode, String resultHeadSha)
    {
        requireTransaction();
        updateOne("""
                UPDATE branch_sync_episode
                SET status = 'AWAITING_HEAD', result_head_sha = ?
                WHERE id = ? AND status = 'PUSHING'
                """, "Branch sync changed before awaiting head",
                resultHeadSha, episode.id());
    }

    public void markBranchConflictRepair(BranchEpisode episode)
    {
        requireTransaction();
        updateOne("""
                UPDATE branch_sync_episode SET status = 'CONFLICT_REPAIR'
                WHERE id = ? AND status = 'REBASING'
                """, "Branch sync changed before conflict repair", episode.id());
    }

    public void failBranchEpisode(
            BranchEpisode episode, String error, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE branch_sync_episode
                SET status = 'FAILED', completed_at_ms = ?, error_message = ?
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
                """, "Branch sync changed before failure",
                at.toEpochMilli(), error, episode.id());
    }

    public void stopBranchEpisode(
            BranchEpisode episode, String reason, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE branch_sync_episode
                SET status = 'STOPPED', completed_at_ms = ?, error_message = ?
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
                """, "Branch sync changed before stop",
                at.toEpochMilli(), reason, episode.id());
    }

    public void succeedBranchEpisode(
            BranchEpisode episode, String snapshotId, Instant at)
    {
        requireTransaction();
        updateOne("""
                UPDATE branch_sync_episode
                SET status = 'SUCCEEDED', result_snapshot_id = ?,
                    completed_at_ms = ?
                WHERE id = ? AND status = 'AWAITING_HEAD'
                """, "Branch sync changed before observed success",
                snapshotId, at.toEpochMilli(), episode.id());
    }

    private RemoteContext remoteContext(ResultSet rs)
            throws SQLException
    {
        return new RemoteContext(
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("trunk_id"), rs.getString("workspace_id"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getLong("stage_version"),
                rs.getString("remote_pr_binding_id"),
                rs.getString("remote_repository_id"),
                rs.getInt("remote_pr_number"),
                rs.getString("remote_head_ref"), rs.getString("worktree_path"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"),
                rs.getString("ci_policy_revision_id"), policy(rs), Set.of());
    }

    private ObservationDelivery observationDelivery(ResultSet rs)
            throws SQLException
    {
        boolean current = "ACTIVE".equals(rs.getString("lifecycle_state"))
                && rs.getLong("task_epoch") == rs.getLong("current_task_epoch")
                && rs.getString("stage_id").equals(
                        rs.getString("current_stage_id"))
                && rs.getLong("stage_generation")
                        == rs.getLong("current_stage_generation")
                && rs.getObject("completed_at_ms") == null
                && rs.getString("expected_head_sha").equals(
                        rs.getString("current_head_sha"))
                && rs.getString("expected_base_sha").equals(
                        rs.getString("current_base_sha"));
        return new ObservationDelivery(
                rs.getString("row_id"), rs.getString("operation_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getString("remote_pr_binding_id"),
                rs.getString("ci_policy_revision_id"),
                rs.getString("remote_repository_id"),
                rs.getInt("remote_pr_number"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"),
                rs.getInt("accepted_observation_revision"),
                rs.getInt("semantic_attempt"), current, policy(rs), Set.of());
    }

    private static RemoteCiPolicy.Policy policy(ResultSet rs)
            throws SQLException
    {
        EnumMap<RemoteCiPolicy.CheckState, RemoteCiPolicy.PolicyOutcome> values =
                new EnumMap<>(RemoteCiPolicy.CheckState.class);
        values.put(RemoteCiPolicy.CheckState.NONE, outcome(rs, "none_outcome"));
        values.put(RemoteCiPolicy.CheckState.MISSING,
                outcome(rs, "missing_outcome"));
        values.put(RemoteCiPolicy.CheckState.QUEUED,
                outcome(rs, "queued_outcome"));
        values.put(RemoteCiPolicy.CheckState.PENDING,
                outcome(rs, "pending_outcome"));
        values.put(RemoteCiPolicy.CheckState.NEUTRAL,
                outcome(rs, "neutral_outcome"));
        values.put(RemoteCiPolicy.CheckState.SKIPPED,
                outcome(rs, "skipped_outcome"));
        values.put(RemoteCiPolicy.CheckState.CANCELED,
                outcome(rs, "canceled_outcome"));
        return new RemoteCiPolicy.Policy(values);
    }

    private static RemoteCiPolicy.PolicyOutcome outcome(
            ResultSet rs, String column)
            throws SQLException
    {
        return RemoteCiPolicy.PolicyOutcome.valueOf(rs.getString(column));
    }

    private List<String> requiredChecks(String policyId)
    {
        return jdbc.query("""
                SELECT check_name FROM remote_ci_required_check
                WHERE ci_policy_revision_id = ? ORDER BY check_name
                """, (rs, row) -> rs.getString(1), policyId);
    }

    private static DeliveryReceipt deliveryReceipt(ResultSet rs)
            throws SQLException
    {
        return new DeliveryReceipt(
                rs.getString("row_id"), rs.getString("operation_id"),
                rs.getString("task_id"), rs.getString("raw_outcome"),
                rs.getString("raw_result_digest"),
                DispatchTicket.Acceptance.valueOf(rs.getString("acceptance")),
                rs.getString("snapshot_id"), rs.getString("ci_evaluation_id"),
                instant(rs, "recorded_at_ms"));
    }

    private static EffectDeliveryReceipt effectReceipt(ResultSet rs)
            throws SQLException
    {
        return new EffectDeliveryReceipt(
                rs.getString("row_id"), rs.getString("operation_id"),
                rs.getString("task_id"), rs.getString("raw_outcome"),
                rs.getString("raw_result_digest"),
                DispatchTicket.Acceptance.valueOf(rs.getString("acceptance")),
                instant(rs, "recorded_at_ms"));
    }

    private static CiEpisode ciEpisode(ResultSet rs)
            throws SQLException
    {
        return new CiEpisode(
                rs.getString("id"),
                rs.getString("remote_development_stage_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("stage_generation"),
                rs.getString("remote_pr_binding_id"),
                rs.getString("failed_ci_evaluation_id"),
                rs.getString("subject_head_sha"),
                rs.getString("subject_base_sha"),
                rs.getString("classification"), rs.getString("status"),
                rs.getInt("rerun_count"), rs.getInt("rerun_limit"),
                rs.getInt("fix_attempt_count"),
                rs.getInt("fix_attempt_limit"),
                rs.getInt("delivery_retry_count"),
                rs.getInt("delivery_retry_limit"),
                rs.getInt("push_count"), rs.getInt("push_limit"),
                rs.getString("last_pushed_head_sha"),
                rs.getString("last_push_result_evaluation_id"),
                rs.getString("terminal_ci_evaluation_id"),
                instant(rs, "opened_at_ms"), nullableInstant(rs, "completed_at_ms"),
                rs.getString("stop_reason"));
    }

    private static CiEffectDelivery ciEffectDelivery(ResultSet rs)
            throws SQLException
    {
        boolean current = "ACTIVE".equals(rs.getString("lifecycle_state"))
                && rs.getLong("task_epoch") == rs.getLong("current_task_epoch")
                && rs.getString("stage_id").equals(
                        rs.getString("current_stage_id"))
                && rs.getLong("stage_generation")
                        == rs.getLong("current_stage_generation")
                && rs.getString("expected_head_sha").equals(
                        rs.getString("current_head_sha"))
                && rs.getString("expected_base_sha").equals(
                        rs.getString("current_base_sha"));
        return new CiEffectDelivery(
                rs.getString("row_id"), rs.getString("operation_id"),
                rs.getString("ci_repair_episode_id"), rs.getString("kind"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("episode_status"),
                rs.getString("subject_head_sha"),
                rs.getString("subject_base_sha"),
                rs.getInt("rerun_count"), rs.getInt("rerun_limit"), current);
    }

    private static BranchEpisode branchEpisode(ResultSet rs)
            throws SQLException
    {
        return new BranchEpisode(
                rs.getString("id"),
                rs.getString("remote_development_stage_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getLong("stage_generation"),
                rs.getString("remote_pr_binding_id"),
                rs.getString("source_snapshot_id"),
                rs.getString("old_head_sha"),
                rs.getString("observed_base_sha"),
                rs.getString("target_base_sha"),
                rs.getString("policy_source"), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getInt("attempt_limit"),
                rs.getString("result_head_sha"),
                rs.getString("result_snapshot_id"),
                instant(rs, "opened_at_ms"), nullableInstant(rs, "completed_at_ms"),
                rs.getString("error_message"));
    }

    private static BranchEffectDelivery branchEffectDelivery(ResultSet rs)
            throws SQLException
    {
        boolean current = "ACTIVE".equals(rs.getString("lifecycle_state"))
                && rs.getLong("task_epoch") == rs.getLong("current_task_epoch")
                && rs.getString("stage_id").equals(
                        rs.getString("current_stage_id"))
                && rs.getLong("stage_generation")
                        == rs.getLong("current_stage_generation")
                && rs.getString("old_head_sha").equals(
                        rs.getString("current_head_sha"))
                && rs.getString("observed_base_sha").equals(
                        rs.getString("current_base_sha"));
        return new BranchEffectDelivery(
                rs.getString("row_id"), rs.getString("operation_id"),
                rs.getString("branch_sync_episode_id"),
                rs.getString("branch_sync_effect_step_id"),
                rs.getString("kind"), rs.getInt("ordinal"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("target_base_sha"),
                rs.getString("episode_status"),
                rs.getString("old_head_sha"),
                rs.getString("observed_base_sha"),
                rs.getInt("attempt_count"), rs.getInt("attempt_limit"),
                rs.getString("idempotency_key"), current);
    }

    private static BranchEffectShape branchShape(String kind)
    {
        return switch (kind) {
            case "FETCH_COMPARE" -> new BranchEffectShape(
                    RemoteEffectOperationHandler.FETCH_BRANCH,
                    "LOCAL_GIT", "BRANCH_SYNC_FETCH_RESULT", 16, true);
            case "MECHANICAL_REBASE" -> new BranchEffectShape(
                    RemoteEffectOperationHandler.REBASE_BRANCH,
                    "LOCAL_GIT", "BRANCH_SYNC_REBASE_RESULT", 16, true);
            case "VALIDATE" -> new BranchEffectShape(
                    RemoteEffectOperationHandler.VALIDATE_BRANCH,
                    "VALIDATION", "BRANCH_SYNC_VALIDATION_RESULT", 4, false);
            case "FORCE_WITH_LEASE_PUSH" -> new BranchEffectShape(
                    RemoteEffectOperationHandler.PUSH_BRANCH,
                    "LOCAL_GIT", "BRANCH_SYNC_PUSH_RESULT", 16, true);
            default -> throw new IllegalArgumentException(
                    "Branch effect requires an Agent Turn: " + kind);
        };
    }

    private void insertTicket(
            String ticketId,
            String operationId,
            String operationKind,
            String family,
            String ownerKind,
            String ownerId,
            String callback,
            int laneMask,
            boolean exclusive,
            boolean writer,
            RemoteContext context,
            int attempt,
            String codeFingerprint,
            String headSha,
            String baseSha,
            Instant at)
    {
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, operationKind, family,
                ownerKind, ownerId, callback, laneMask,
                exclusive ? 1 : 0, writer ? 1 : 0,
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                attempt, codeFingerprint, headSha, baseSha, at.toEpochMilli());
    }

    private String requireSingleString(String sql, String value, String message)
    {
        List<String> rows = jdbc.query(
                sql, (rs, row) -> rs.getString(1), value);
        if (rows.size() != 1) {
            throw new IllegalStateException(message);
        }
        return rows.getFirst();
    }

    private String requireSingleString(
            String sql, List<Object> values, String message)
    {
        List<String> rows = jdbc.query(
                sql, (rs, row) -> rs.getString(1), values.toArray());
        if (rows.size() != 1) {
            throw new IllegalStateException(message);
        }
        return rows.getFirst();
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    private static Instant nullableInstant(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Remote runtime mutation requires a Task command transaction");
        }
    }

    private void updateOne(String sql, String failure, Object... arguments)
    {
        if (jdbc.update(sql, arguments) != 1) {
            throw new IllegalStateException(failure);
        }
    }

    public record RemoteContext(
            String taskId,
            long taskEpoch,
            String trunkId,
            String workspaceId,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String remotePrBindingId,
            String repositoryId,
            int pullRequestNumber,
            String headRef,
            String worktreePath,
            String headSha,
            String baseSha,
            String ciPolicyRevisionId,
            RemoteCiPolicy.Policy ciPolicy,
            Set<String> requiredChecks)
    {
        public RemoteContext
        {
            requiredChecks = Set.copyOf(requiredChecks);
        }

        private RemoteContext withRequiredChecks(Set<String> required)
        {
            return new RemoteContext(
                    taskId, taskEpoch, trunkId, workspaceId, stageId,
                    stageGeneration, stageVersion, remotePrBindingId,
                    repositoryId, pullRequestNumber, headRef, worktreePath,
                    headSha, baseSha, ciPolicyRevisionId, ciPolicy, required);
        }
    }

    public record ObservationRequest(
            String rowId,
            String operationId,
            String taskId,
            String stageId,
            int semanticAttempt,
            Instant requestedAt) {}

    public record ObservationDelivery(
            String rowId,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String remotePrBindingId,
            String ciPolicyRevisionId,
            String repositoryId,
            int pullRequestNumber,
            String expectedHeadSha,
            String expectedBaseSha,
            String currentHeadSha,
            String currentBaseSha,
            int acceptedObservationRevision,
            int semanticAttempt,
            boolean current,
            RemoteCiPolicy.Policy ciPolicy,
            Set<String> requiredChecks)
    {
        public ObservationDelivery
        {
            requiredChecks = Set.copyOf(requiredChecks);
        }

        private ObservationDelivery withRequiredChecks(List<String> required)
        {
            return new ObservationDelivery(
                    rowId, operationId, taskId, taskEpoch, stageId,
                    stageGeneration, remotePrBindingId, ciPolicyRevisionId,
                    repositoryId, pullRequestNumber, expectedHeadSha,
                    expectedBaseSha, currentHeadSha, currentBaseSha,
                    acceptedObservationRevision, semanticAttempt, current,
                    ciPolicy, new LinkedHashSet<>(required));
        }
    }

    public record ObservationTarget(
            String taskId, String stageId, Instant lastRequestedAt)
    {
        public ObservationTarget
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(stageId, "stageId is null");
            requireNonNull(lastRequestedAt, "lastRequestedAt is null");
            if (taskId.isBlank() || stageId.isBlank()) {
                throw new IllegalArgumentException(
                        "Observation target identity must not be blank");
            }
        }
    }

    public record ObservationEvidence(
            String snapshotId,
            String ciEvaluationId,
            int revision,
            String headSha,
            String baseSha,
            RemoteCiPolicy.PolicyOutcome ciOutcome,
            long observedAtMs) {}

    public record DeliveryReceipt(
            String rowId,
            String operationId,
            String taskId,
            String rawOutcome,
            String rawDigest,
            DispatchTicket.Acceptance acceptance,
            String snapshotId,
            String ciEvaluationId,
            Instant recordedAt) {}

    public record CiBudgets(
            int rerunLimit,
            int fixAttemptLimit,
            int deliveryRetryLimit,
            int pushLimit)
    {
        public CiBudgets
        {
            if (rerunLimit < 0 || fixAttemptLimit < 0
                    || deliveryRetryLimit < 0 || pushLimit < 0) {
                throw new IllegalArgumentException(
                        "CI repair budgets must be non-negative");
            }
        }
    }

    public record CiEpisode(
            String id,
            String stageId,
            String taskId,
            long taskEpoch,
            long stageGeneration,
            String remotePrBindingId,
            String failedCiEvaluationId,
            String subjectHeadSha,
            String subjectBaseSha,
            String classification,
            String status,
            int rerunCount,
            int rerunLimit,
            int fixAttemptCount,
            int fixAttemptLimit,
            int deliveryRetryCount,
            int deliveryRetryLimit,
            int pushCount,
            int pushLimit,
            String lastPushedHeadSha,
            String lastPushResultEvaluationId,
            String terminalCiEvaluationId,
            Instant openedAt,
            Instant completedAt,
            String stopReason) {}

    public record EffectRequest(
            String rowId,
            String operationId,
            String episodeId,
            String stepId,
            String taskId,
            String stageId,
            String kind,
            int semanticAttempt,
            Instant requestedAt) {}

    public record EffectDeliveryReceipt(
            String rowId,
            String operationId,
            String taskId,
            String rawOutcome,
            String rawDigest,
            DispatchTicket.Acceptance acceptance,
            Instant recordedAt) {}

    public record CiEffectDelivery(
            String rowId,
            String operationId,
            String episodeId,
            String kind,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String episodeStatus,
            String subjectHeadSha,
            String subjectBaseSha,
            int rerunCount,
            int rerunLimit,
            boolean current) {}

    public record BranchEpisode(
            String id,
            String stageId,
            String taskId,
            long taskEpoch,
            long stageGeneration,
            String remotePrBindingId,
            String sourceSnapshotId,
            String oldHeadSha,
            String observedBaseSha,
            String targetBaseSha,
            String policySource,
            String status,
            int attemptCount,
            int attemptLimit,
            String resultHeadSha,
            String resultSnapshotId,
            Instant openedAt,
            Instant completedAt,
            String errorMessage) {}

    public record BranchStep(
            String id,
            String episodeId,
            int ordinal,
            String kind,
            String idempotencyKey,
            String status,
            int attemptCount,
            int attemptLimit) {}

    public record BranchEffectDelivery(
            String rowId,
            String operationId,
            String episodeId,
            String stepId,
            String kind,
            int ordinal,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String targetBaseSha,
            String episodeStatus,
            String oldHeadSha,
            String observedBaseSha,
            int attemptCount,
            int attemptLimit,
            String idempotencyKey,
            boolean current) {}

    private record BranchEffectShape(
            String operationKind,
            String family,
            String callbackRoute,
            int laneMask,
            boolean writer) {}

    @Override
    public RemoteEffectOperationHandler.OperationContext requireEffect(
            String operationId)
    {
        List<RemoteEffectOperationHandler.OperationContext> rows = jdbc.query("""
                SELECT operation.operation_id, operation.kind,
                       operation.task_id, operation.task_epoch,
                       operation.remote_development_stage_id AS stage_id,
                       operation.stage_generation,
                       operation.semantic_attempt,
                       operation.expected_code_fingerprint,
                       operation.expected_head_sha,
                       operation.expected_base_sha,
                       episode.subject_head_sha AS force_with_lease_expected_sha,
                       binding.remote_repository_id,
                       binding.remote_pr_number, binding.remote_head_ref,
                       identity.worktree_path
                FROM ci_repair_operation operation
                JOIN ci_repair_episode episode
                  ON episode.id = operation.ci_repair_episode_id
                JOIN remote_pr_binding binding
                  ON binding.id = episode.remote_pr_binding_id
                JOIN task_code_identity identity
                  ON identity.task_id = operation.task_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                  AND operation.kind IN ('RERUN', 'VALIDATE', 'PUSH_HEAD')
                """, (rs, row) -> effectOperationContext(rs, null), operationId);
        if (rows.isEmpty()) {
            rows = jdbc.query("""
                    SELECT operation.operation_id, operation.kind,
                           operation.task_id, operation.task_epoch,
                           operation.remote_development_stage_id AS stage_id,
                           operation.stage_generation,
                           operation.semantic_attempt,
                           operation.expected_code_fingerprint,
                           operation.expected_head_sha,
                           operation.expected_base_sha,
                           operation.target_base_sha,
                           episode.old_head_sha AS force_with_lease_expected_sha,
                           step.idempotency_key,
                           binding.remote_repository_id,
                           binding.remote_pr_number, binding.remote_head_ref,
                           identity.worktree_path
                    FROM branch_sync_dispatch_operation operation
                    JOIN branch_sync_episode episode
                      ON episode.id = operation.branch_sync_episode_id
                    JOIN branch_sync_effect_step step
                      ON step.id = operation.branch_sync_effect_step_id
                    JOIN remote_pr_binding binding
                      ON binding.id = episode.remote_pr_binding_id
                    JOIN task_code_identity identity
                      ON identity.task_id = operation.task_id
                    WHERE operation.operation_id = ?
                      AND operation.status = 'DISPATCHED'
                      AND operation.kind IN (
                          'FETCH_COMPARE', 'MECHANICAL_REBASE', 'VALIDATE',
                          'FORCE_WITH_LEASE_PUSH')
                    """, (rs, row) -> branchEffectOperationContext(rs),
                    operationId);
        }
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one dispatched Remote effect, found " + rows.size());
        }
        return rows.getFirst();
    }

    private static RemoteEffectOperationHandler.OperationContext
            effectOperationContext(ResultSet rs, String targetBaseSha)
            throws SQLException
    {
        String kind = rs.getString("kind");
        String operationKind = switch (kind) {
            case "RERUN" -> RemoteEffectOperationHandler.RERUN_CI;
            case "VALIDATE" -> RemoteEffectOperationHandler.VALIDATE_CI_REPAIR;
            case "PUSH_HEAD" -> RemoteEffectOperationHandler.PUSH_CI_REPAIR;
            default -> throw new IllegalStateException(
                    "Unsupported CI effect kind " + kind);
        };
        String operationId = rs.getString("operation_id");
        String stageId = rs.getString("stage_id");
        String expectedHead = rs.getString("expected_head_sha");
        String expectedBase = rs.getString("expected_base_sha");
        return new RemoteEffectOperationHandler.OperationContext(
                operationId, operationKind, stageId,
                rs.getLong("task_epoch"), rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"), expectedHead, expectedBase,
                new RemoteEffectOperationHandler.Request(
                        operationId, operationKind, rs.getString("task_id"),
                        stageId, rs.getString("remote_repository_id"),
                        rs.getInt("remote_pr_number"),
                        rs.getString("worktree_path"),
                        rs.getString("remote_head_ref"),
                        rs.getString("expected_code_fingerprint"), expectedHead,
                        expectedBase, targetBaseSha,
                        rs.getString("force_with_lease_expected_sha"),
                        operationId));
    }

    private static RemoteEffectOperationHandler.OperationContext
            branchEffectOperationContext(ResultSet rs)
            throws SQLException
    {
        String kind = rs.getString("kind");
        String operationKind = switch (kind) {
            case "FETCH_COMPARE" -> RemoteEffectOperationHandler.FETCH_BRANCH;
            case "MECHANICAL_REBASE" -> RemoteEffectOperationHandler.REBASE_BRANCH;
            case "VALIDATE" -> RemoteEffectOperationHandler.VALIDATE_BRANCH;
            case "FORCE_WITH_LEASE_PUSH" -> RemoteEffectOperationHandler.PUSH_BRANCH;
            default -> throw new IllegalStateException(
                    "Unsupported branch effect kind " + kind);
        };
        String operationId = rs.getString("operation_id");
        String stageId = rs.getString("stage_id");
        String expectedHead = rs.getString("expected_head_sha");
        String expectedBase = rs.getString("expected_base_sha");
        return new RemoteEffectOperationHandler.OperationContext(
                operationId, operationKind, stageId,
                rs.getLong("task_epoch"), rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"), expectedHead, expectedBase,
                new RemoteEffectOperationHandler.Request(
                        operationId, operationKind, rs.getString("task_id"),
                        stageId, rs.getString("remote_repository_id"),
                        rs.getInt("remote_pr_number"),
                        rs.getString("worktree_path"),
                        rs.getString("remote_head_ref"),
                        rs.getString("expected_code_fingerprint"), expectedHead,
                        expectedBase, rs.getString("target_base_sha"),
                        rs.getString("force_with_lease_expected_sha"),
                        rs.getString("idempotency_key")));
    }
}
