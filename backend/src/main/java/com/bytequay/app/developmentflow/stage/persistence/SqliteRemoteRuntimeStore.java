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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
    private static final String LEGACY_PUBLISH_CI_POLICY =
            "PUBLISH_HANDOFF_FAIL_CLOSED";
    private static final String CI_POLICY_UPGRADE_ACTOR =
            "remote-observation-owner";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Autowired
    public SqliteRemoteRuntimeStore(JdbcTemplate jdbc)
    {
        this(jdbc, new ObjectMapper());
    }

    SqliteRemoteRuntimeStore(JdbcTemplate jdbc, ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
    }

    /** Appends the current default after the obsolete publish policy. */
    public boolean adoptDefaultRepositoryCiPolicy(
            String taskId, String stageId, Instant at)
    {
        requireTransaction();
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        requireNonNull(at, "at is null");
        List<CiPolicyUpgradeSubject> rows = jdbc.query("""
                SELECT policy.id, policy.source,
                       policy.remote_pr_binding_id,
                       (SELECT COALESCE(MAX(candidate.revision), 0) + 1
                        FROM remote_ci_policy_revision candidate
                        WHERE candidate.task_id = task.id) AS next_revision
                FROM remote_development_stage remote
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = remote.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN remote_ci_policy_revision policy
                 ON policy.task_id = task.id
                 AND policy.remote_pr_binding_id = remote.remote_pr_binding_id
                 AND policy.revision = (
                    SELECT MAX(candidate.revision)
                    FROM remote_ci_policy_revision candidate
                    WHERE candidate.task_id = task.id
                      AND candidate.remote_pr_binding_id =
                            remote.remote_pr_binding_id)
                WHERE task.id = ? AND remote.stage_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                  AND current.stage_id = remote.stage_id
                  AND current.stage_generation = remote.generation
                """, (rs, row) -> new CiPolicyUpgradeSubject(
                        rs.getString("id"), rs.getString("source"),
                        rs.getString("remote_pr_binding_id"),
                        rs.getInt("next_revision")), taskId, stageId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one current Remote CI policy, found " + rows.size());
        }
        CiPolicyUpgradeSubject subject = rows.getFirst();
        if (!LEGACY_PUBLISH_CI_POLICY.equals(subject.source())) {
            return false;
        }

        int revision = subject.nextRevision();
        String policyId = id(
                "remote-ci-policy-upgrade",
                taskId + ":" + subject.bindingId() + ":" + revision + ":"
                        + RemoteCiPolicy.DEFAULT_REPOSITORY_CI_POLICY_V1_SOURCE);
        RemoteCiPolicy.Policy policy =
                RemoteCiPolicy.DEFAULT_REPOSITORY_CI_POLICY_V1;
        int inserted = jdbc.update("""
                INSERT INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome,
                    pending_outcome, neutral_outcome, skipped_outcome,
                    canceled_outcome, created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, policyId, taskId, subject.bindingId(), revision,
                RemoteCiPolicy.DEFAULT_REPOSITORY_CI_POLICY_V1_SOURCE,
                policy.outcome(RemoteCiPolicy.CheckState.NONE).name(),
                policy.outcome(RemoteCiPolicy.CheckState.MISSING).name(),
                policy.outcome(RemoteCiPolicy.CheckState.QUEUED).name(),
                policy.outcome(RemoteCiPolicy.CheckState.PENDING).name(),
                policy.outcome(RemoteCiPolicy.CheckState.NEUTRAL).name(),
                policy.outcome(RemoteCiPolicy.CheckState.SKIPPED).name(),
                policy.outcome(RemoteCiPolicy.CheckState.CANCELED).name(),
                CI_POLICY_UPGRADE_ACTOR, at.toEpochMilli());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Default repository CI policy was not appended");
        }
        jdbc.update("""
                INSERT INTO remote_ci_required_check(
                    ci_policy_revision_id, check_name)
                SELECT ?, check_name
                FROM remote_ci_required_check
                WHERE ci_policy_revision_id = ?
                """, policyId, subject.policyId());
        return true;
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

    /** Local worktree subject used to fence an observation-driven rebase. */
    public CodeSubject requireCodeSubject(String taskId)
    {
        List<CodeSubject> rows = jdbc.query("""
                SELECT code_fingerprint, head_sha, base_sha
                FROM task_current_code_subject_v230
                WHERE task_id = ?
                """, (rs, row) -> new CodeSubject(
                        rs.getString("code_fingerprint"),
                        rs.getString("head_sha"), rs.getString("base_sha")),
                taskId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one current V2 code subject, found " + rows.size());
        }
        return rows.getFirst();
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

    /** Exact request identity retained for idempotent recovery-command replay. */
    public ObservationRequest requireObservationRequest(String operationId)
    {
        requireText(operationId, "operationId");
        List<ObservationRequest> rows = jdbc.query("""
                SELECT id, operation_id, task_id,
                       remote_development_stage_id AS stage_id,
                       semantic_attempt, requested_at_ms
                FROM remote_observation_operation
                WHERE operation_id = ?
                """, (rs, row) -> new ObservationRequest(
                        rs.getString("id"), rs.getString("operation_id"),
                        rs.getString("task_id"), rs.getString("stage_id"),
                        rs.getInt("semantic_attempt"),
                        instant(rs, "requested_at_ms")), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one retained Remote observation request, found "
                            + rows.size());
        }
        return rows.getFirst();
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

    /** Exact current read-only observations parked after bounded reconciliation. */
    public List<ParkedObservation> findParkedObservations(
            Instant attemptedNotAfter, int limit)
    {
        requireNonNull(attemptedNotAfter, "attemptedNotAfter is null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT ticket.id AS ticket_id, operation.task_id,
                       operation.remote_development_stage_id AS stage_id,
                       COALESCE(ticket.started_at_ms, ticket.created_at_ms)
                           AS last_attempted_at_ms
                FROM remote_observation_operation operation
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.remote_development_stage_id
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                WHERE operation.status = 'DISPATCHED'
                  AND ticket.operation_kind = 'OBSERVE_REMOTE_PR'
                  AND ticket.async_family = 'REMOTE_OBSERVATION'
                  AND ticket.owner_kind = 'STAGE'
                  AND ticket.owner_id = operation.remote_development_stage_id
                  AND ticket.callback_route = 'REMOTE_OBSERVATION_RESULT'
                  AND ticket.status = 'RECONCILE_WAIT'
                  AND ticket.next_attempt_at_ms IS NULL
                  AND ticket.cancel_requested_at_ms IS NULL
                  AND ticket.task_id = operation.task_id
                  AND ticket.task_epoch = operation.task_epoch
                  AND ticket.stage_id = operation.remote_development_stage_id
                  AND ticket.stage_generation = operation.stage_generation
                  AND ticket.attempt = operation.semantic_attempt
                  AND ticket.expected_head_sha = operation.expected_head_sha
                  AND ticket.expected_base_sha = operation.expected_base_sha
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = operation.task_epoch
                  AND owner.completed_at_ms IS NULL
                  AND current.stage_id = operation.remote_development_stage_id
                  AND current.stage_generation = operation.stage_generation
                  AND remote.generation = operation.stage_generation
                  AND COALESCE(ticket.started_at_ms, ticket.created_at_ms) <= ?
                ORDER BY last_attempted_at_ms, operation.task_id,
                         operation.remote_development_stage_id
                LIMIT ?
                """, (rs, row) -> new ParkedObservation(
                        rs.getString("ticket_id"), rs.getString("task_id"),
                        rs.getString("stage_id"),
                        instant(rs, "last_attempted_at_ms")),
                attemptedNotAfter.toEpochMilli(), limit);
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
        List<ObservationOperationRow> rows = jdbc.query("""
                SELECT operation.operation_id, operation.remote_development_stage_id,
                       operation.task_epoch, operation.stage_generation,
                       operation.semantic_attempt, operation.expected_head_sha,
                       operation.expected_base_sha,
                       binding.remote_repository_id, binding.remote_pr_number,
                       operation.ci_policy_revision_id
                FROM remote_observation_operation operation
                JOIN remote_pr_binding binding
                  ON binding.id = operation.remote_pr_binding_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'DISPATCHED'
                """, (rs, row) -> new ObservationOperationRow(
                        rs.getString("operation_id"),
                        rs.getString("remote_development_stage_id"),
                        rs.getLong("task_epoch"),
                        rs.getLong("stage_generation"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("expected_head_sha"),
                        rs.getString("expected_base_sha"),
                        rs.getString("remote_repository_id"),
                        rs.getInt("remote_pr_number"),
                        rs.getString("ci_policy_revision_id")), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one dispatched Remote observation, found "
                            + rows.size());
        }
        ObservationOperationRow row = rows.getFirst();
        return new RemoteObservationOperationHandler.OperationContext(
                row.operationId(), row.stageId(), row.taskEpoch(),
                row.stageGeneration(), row.semanticAttempt(),
                row.expectedHeadSha(), row.expectedBaseSha(),
                new RemoteObservationOperationHandler.Request(
                        row.repositoryId(), row.pullRequestNumber(),
                        row.expectedHeadSha(), row.expectedBaseSha(),
                        requiredChecks(row.ciPolicyRevisionId())));
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
                       policy.id = (
                         SELECT candidate.id
                         FROM remote_ci_policy_revision candidate
                         WHERE candidate.task_id = operation.task_id
                         ORDER BY candidate.revision DESC
                         LIMIT 1) AS current_ci_policy,
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
        insertSnapshot(context, observation, revision, snapshotId);
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

    private void insertSnapshot(
            ObservationDelivery context,
            RemoteObservationOperationHandler.Observation observation,
            int revision,
            String snapshotId)
    {
        String columns = observation.schemaVersion() == 1 ? "" :
                ", viewer_login, viewer_can_merge";
        String values = observation.schemaVersion() == 1 ? "" : ", ?, ?";
        if (observation.schemaVersion() >= 3) {
            columns += ", ci_provenance_json";
            values += ", ?";
        }
        List<Object> arguments = new ArrayList<>(List.of(
                snapshotId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), revision,
                observation.observationKey(), context.repositoryId(),
                context.pullRequestNumber(), observation.headSha(),
                observation.baseSha(), observation.prState().name(),
                observation.mergeability().name(),
                observation.mergeQueueState().name(),
                observation.mergeQueueCapability().name(),
                observation.effectiveApprovalCount(),
                observation.writeApprovalCount(),
                observation.changesRequestedCount(),
                observation.requestedReviewerCount(),
                observation.unresolvedThreadCount(),
                observation.unresolvedCommentCount(), observation.observedAtMs(),
                observation.rawEvidence()));
        if (observation.schemaVersion() >= 2) {
            arguments.add(observation.viewerLogin());
            arguments.add(observation.viewerCanMerge() ? 1 : 0);
        }
        if (observation.schemaVersion() >= 3) {
            arguments.add(writeJson(observation.ciProvenance()));
        }
        jdbc.update("""
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    observation_revision, observation_key,
                    remote_repository_id, remote_pr_number, head_sha, base_sha,
                    pr_state, mergeability, merge_queue_state,
                    merge_queue_capability,
                    effective_approval_count, write_approval_count,
                    changes_requested_count, requested_reviewer_count,
                    unresolved_thread_count, unresolved_comment_count,
                    observed_at_ms, raw_evidence%s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?%s)
                """.formatted(columns, values), arguments.toArray());
    }

    private String writeJson(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Could not serialize typed Remote CI provenance", e);
        }
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

    public String requireEffectTaskId(String operationId)
    {
        return requireSingleString("""
                SELECT task_id FROM branch_sync_dispatch_operation
                WHERE operation_id = ?
                """, operationId,
                "Remote effect owner is missing");
    }

    public Optional<BranchEpisode> findLiveBranchEpisode(String stageId)
    {
        return jdbc.query("""
                SELECT id, remote_development_stage_id, task_id, task_epoch,
                       stage_generation, remote_pr_binding_id,
                       source_snapshot_id, old_head_sha, observed_base_sha,
                       target_base_sha, branch_sync_policy_revision_id,
                       policy_source, purpose, authority_kind, authority_id,
                       ci_repair_episode_id, ci_turn_intent_kind,
                       ci_turn_intent_id, source_code_fingerprint,
                       source_code_head_sha, source_code_base_sha,
                       status, attempt_count,
                       attempt_limit, result_code_fingerprint,
                       result_head_sha, result_snapshot_id,
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
                       target_base_sha, branch_sync_policy_revision_id,
                       policy_source, purpose, authority_kind, authority_id,
                       ci_repair_episode_id, ci_turn_intent_kind,
                       ci_turn_intent_id, source_code_fingerprint,
                       source_code_head_sha, source_code_base_sha,
                       status, attempt_count,
                       attempt_limit, result_code_fingerprint,
                       result_head_sha, result_snapshot_id,
                       opened_at_ms, completed_at_ms, error_message
                FROM branch_sync_episode WHERE id = ?
                """, (rs, row) -> branchEpisode(rs), episodeId)
                .stream().findFirst();
    }

    public boolean hasExactBranchSyncExhaustion(
            RemoteContext context, CodeSubject code)
    {
        requireNonNull(context, "context is null");
        requireNonNull(code, "code is null");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM branch_sync_exhaustion_v319 exhaustion
                WHERE exhaustion.task_id = ? AND exhaustion.stage_id = ?
                  AND exhaustion.remote_head_sha = ?
                  AND exhaustion.remote_base_sha = ?
                  AND exhaustion.code_fingerprint = ?
                  AND exhaustion.code_head_sha = ?
                  AND exhaustion.code_base_sha = ?
                """, Integer.class, context.taskId(), context.stageId(),
                context.headSha(), context.baseSha(), code.codeFingerprint(),
                code.headSha(), code.baseSha());
        return count != null && count > 0;
    }

    public BranchControlReceipt controlBranchExhaustion(
            String taskId,
            String episodeId,
            String blockerId,
            String commandId,
            String kind,
            String actor,
            String reason,
            Instant at)
    {
        requireTransaction();
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireText(blockerId, "blockerId");
        requireText(commandId, "commandId");
        requireText(kind, "kind");
        requireText(actor, "actor");
        requireText(reason, "reason");
        requireNonNull(at, "at is null");
        BranchControlReceipt replay = findBranchControl(commandId).orElse(null);
        if (replay != null) {
            if (!taskId.equals(replay.taskId())
                    || !episodeId.equals(replay.episodeId())
                    || !blockerId.equals(replay.blockerId())
                    || !kind.equals(replay.kind())
                    || !actor.equals(replay.actor())
                    || !reason.equals(replay.reason())) {
                throw new IllegalArgumentException(
                        "Branch sync control command was already used with other values");
            }
            return replay;
        }
        String controlId = id("branch-sync-control-command", commandId);
        jdbc.update("""
                INSERT INTO branch_sync_control_command_v319(
                    id, branch_sync_episode_id, blocker_id, task_id, stage_id,
                    command_id, kind, actor, reason, created_at_ms,
                    consumed_at_ms)
                SELECT ?, exhaustion.branch_sync_episode_id,
                    exhaustion.blocker_id, exhaustion.task_id,
                    exhaustion.stage_id, ?, ?, ?, ?, ?, ?
                FROM branch_sync_exhaustion_v319 exhaustion
                WHERE exhaustion.branch_sync_episode_id = ?
                  AND exhaustion.blocker_id = ?
                  AND exhaustion.task_id = ?
                """, controlId, commandId, kind, actor, reason,
                at.toEpochMilli(), at.toEpochMilli(), episodeId, blockerId,
                taskId);
        updateOne("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = ? AND task_id = ? AND stage_id IS NOT NULL
                  AND owner_kind = 'EPISODE' AND owner_id = ?
                  AND blocker_type = 'BRANCH_SYNC_EXHAUSTED'
                  AND status = 'OPEN'
                """, "Branch sync exhaustion changed before control",
                at.toEpochMilli(), kind + ": " + reason, blockerId, taskId,
                episodeId);
        return findBranchControl(commandId).orElseThrow(() ->
                new IllegalStateException(
                        "Branch sync control receipt is missing"));
    }

    private Optional<BranchControlReceipt> findBranchControl(String commandId)
    {
        return jdbc.query("""
                SELECT branch_sync_episode_id, blocker_id, task_id, stage_id,
                       command_id, kind, actor, reason, consumed_at_ms
                FROM branch_sync_control_command_v319
                WHERE command_id = ?
                """, (rs, row) -> new BranchControlReceipt(
                        rs.getString("task_id"),
                        rs.getString("branch_sync_episode_id"),
                        rs.getString("blocker_id"), rs.getString("stage_id"),
                        rs.getString("command_id"), rs.getString("kind"),
                        rs.getString("actor"), rs.getString("reason"),
                        instant(rs, "consumed_at_ms")), commandId)
                .stream().findFirst();
    }

    public void resolveStaleBranchSyncExhaustions(
            RemoteContext context, CodeSubject code, Instant at)
    {
        requireTransaction();
        requireNonNull(context, "context is null");
        requireNonNull(code, "code is null");
        requireNonNull(at, "at is null");
        jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = 'BranchSync subject changed'
                WHERE id IN (
                    SELECT exhaustion.blocker_id
                    FROM branch_sync_exhaustion_v319 exhaustion
                    WHERE exhaustion.task_id = ? AND exhaustion.stage_id = ?
                      AND (exhaustion.remote_head_sha <> ?
                        OR exhaustion.remote_base_sha <> ?
                        OR exhaustion.code_fingerprint <> ?
                        OR exhaustion.code_head_sha <> ?
                        OR exhaustion.code_base_sha <> ?))
                  AND status = 'OPEN'
                """, at.toEpochMilli(), context.taskId(), context.stageId(),
                context.headSha(), context.baseSha(), code.codeFingerprint(),
                code.headSha(), code.baseSha());
    }

    public BranchEpisode insertBranchEpisode(
            RemoteContext context,
            String commandId,
            String targetBaseSha,
            String policyRevisionId,
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
        CodeSubject sourceCode = requireCodeSubject(context.taskId());
        return insertBranchEpisode(
                context, commandId, sourceSnapshotId, context.headSha(),
                context.baseSha(), targetBaseSha, policyRevisionId,
                policySource, "SCHEDULED", "BRANCH_SYNC_POLICY",
                policyRevisionId, sourceCode, null, null, null,
                attemptLimit, at);
    }

    /**
     * Starts from a just-accepted pure base advance. The accepted snapshot is
     * the exact Remote subject; the first effect is separately fenced by the
     * still-local code subject supplied by the coordinator.
     */
    public BranchEpisode insertObservedBranchEpisode(
            RemoteContext context,
            String commandId,
            String policyRevisionId,
            String policySource,
            int attemptLimit,
            Instant at)
    {
        requireTransaction();
        requireNonNull(context, "context is null");
        requireNonNull(commandId, "commandId is null");
        requireNonNull(policyRevisionId, "policyRevisionId is null");
        requireNonNull(policySource, "policySource is null");
        if (attemptLimit < 1) {
            throw new IllegalArgumentException("attemptLimit must be positive");
        }
        String sourceSnapshotId = requireSingleString("""
                SELECT accepted_snapshot_id FROM remote_development_stage
                WHERE stage_id = ? AND accepted_snapshot_id IS NOT NULL
                """, context.stageId(),
                "Branch sync requires an accepted Remote snapshot");
        CodeSubject sourceCode = requireCodeSubject(context.taskId());
        return insertBranchEpisode(
                context, commandId, sourceSnapshotId, context.headSha(),
                context.baseSha(), context.baseSha(), policyRevisionId, policySource,
                "SCHEDULED", "BRANCH_SYNC_POLICY", policyRevisionId,
                sourceCode, null, null, null, attemptLimit, at);
    }

    public BranchEpisode insertCiPreconditionBranchEpisode(
            RemoteContext context,
            String commandId,
            String policyRevisionId,
            int attemptLimit,
            String authorityKind,
            String authorityId,
            Instant at)
    {
        requireTransaction();
        requireNonNull(context, "context is null");
        requireText(commandId, "commandId");
        requireText(policyRevisionId, "policyRevisionId");
        requireText(authorityKind, "authorityKind");
        requireText(authorityId, "authorityId");
        String sourceSnapshotId = requireSingleString("""
                SELECT accepted_snapshot_id FROM remote_development_stage
                WHERE stage_id = ? AND accepted_snapshot_id IS NOT NULL
                """, context.stageId(),
                "CI BranchSync requires an accepted Remote snapshot");
        CodeSubject sourceCode = requireCodeSubject(context.taskId());
        return insertBranchEpisode(
                context, commandId, sourceSnapshotId, context.headSha(),
                context.baseSha(), context.baseSha(), policyRevisionId,
                "CI_PRECONDITION", "CI_PRECONDITION", authorityKind,
                authorityId, sourceCode, null, null, null, attemptLimit, at);
    }

    public BranchEpisode insertManualCiPreconditionBranchEpisode(
            RemoteContext context,
            CiEpisode ciEpisode,
            String commandId,
            String policyRevisionId,
            int attemptLimit,
            String authorizationId,
            Instant at)
    {
        requireTransaction();
        requireNonNull(context, "context is null");
        requireNonNull(ciEpisode, "ciEpisode is null");
        requireText(commandId, "commandId");
        requireText(policyRevisionId, "policyRevisionId");
        requireText(authorizationId, "authorizationId");
        String sourceSnapshotId = requireSingleString("""
                SELECT accepted_snapshot_id FROM remote_development_stage
                WHERE stage_id = ? AND accepted_snapshot_id IS NOT NULL
                """, context.stageId(),
                "Manual CI precondition requires an accepted Remote snapshot");
        CodeSubject sourceCode = requireCodeSubject(context.taskId());
        return insertBranchEpisode(
                context, commandId, sourceSnapshotId, context.headSha(),
                context.baseSha(), context.baseSha(), policyRevisionId,
                "CI_PRECONDITION", "CI_PRECONDITION", "MANUAL",
                authorizationId, sourceCode, ciEpisode.id(), null, null,
                attemptLimit, at);
    }

    public BranchEpisode insertCiLocalPreconditionBranchEpisode(
            RemoteContext context,
            CiLocalPrecondition precondition,
            String commandId,
            String policyRevisionId,
            int attemptLimit,
            String authorityKind,
            String authorityId,
            Instant at)
    {
        requireTransaction();
        requireNonNull(context, "context is null");
        requireNonNull(precondition, "precondition is null");
        requireText(commandId, "commandId");
        requireText(policyRevisionId, "policyRevisionId");
        requireText(authorityKind, "authorityKind");
        requireText(authorityId, "authorityId");
        String sourceSnapshotId = requireSingleString("""
                SELECT accepted_snapshot_id FROM remote_development_stage
                WHERE stage_id = ? AND accepted_snapshot_id IS NOT NULL
                """, context.stageId(),
                "Local CI precondition requires an accepted Remote snapshot");
        return insertBranchEpisode(
                context, commandId, sourceSnapshotId, context.headSha(),
                context.baseSha(), context.baseSha(), policyRevisionId,
                "CI_PRECONDITION", "CI_PRECONDITION_LOCAL", authorityKind,
                authorityId, precondition.codeSubject(),
                precondition.episode().id(), precondition.intentKind(),
                precondition.intentId(), attemptLimit, at);
    }

    private BranchEpisode insertBranchEpisode(
            RemoteContext context,
            String commandId,
            String sourceSnapshotId,
            String oldHeadSha,
            String observedBaseSha,
            String targetBaseSha,
            String policyRevisionId,
            String policySource,
            String purpose,
            String authorityKind,
            String authorityId,
            CodeSubject sourceCode,
            String ciRepairEpisodeId,
            String ciTurnIntentKind,
            String ciTurnIntentId,
            int attemptLimit,
            Instant at)
    {
        String episodeId = id("branch-sync-episode", commandId);
        jdbc.update("""
                INSERT INTO branch_sync_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, source_snapshot_id,
                    old_head_sha, observed_base_sha, target_base_sha,
                    branch_sync_policy_revision_id, policy_source, purpose,
                    authority_kind, authority_id, ci_repair_episode_id,
                    ci_turn_intent_kind, ci_turn_intent_id,
                    source_code_fingerprint, source_code_head_sha,
                    source_code_base_sha, status,
                    attempt_limit, opened_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, 'OPEN', ?, ?)
                """, episodeId, context.stageId(), context.taskId(),
                context.taskEpoch(), context.stageGeneration(),
                context.remotePrBindingId(), sourceSnapshotId, oldHeadSha,
                observedBaseSha, targetBaseSha, policyRevisionId, policySource,
                purpose, authorityKind, authorityId, ciRepairEpisodeId,
                ciTurnIntentKind, ciTurnIntentId, sourceCode.codeFingerprint(),
                sourceCode.headSha(), sourceCode.baseSha(),
                attemptLimit, at.toEpochMilli());
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
            if (context.ordinal() != 1) {
                insertWorktreeSubject(
                        "BRANCH_EFFECT", context.operationId(), context.taskId(),
                        context.taskEpoch(), context.stageId(),
                        context.stageGeneration(), result.codeFingerprint(),
                        result.headSha(), context.targetBaseSha(), at);
            }
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

    public Optional<BranchStep> rearmFailedBranchEffect(
            BranchEffectDelivery context)
    {
        requireTransaction();
        requireNonNull(context, "context is null");
        BranchStep step = requireBranchStep(context.episodeId(), context.ordinal());
        if (!"FAILED".equals(step.status())
                || step.attemptCount() >= step.attemptLimit()) {
            return Optional.empty();
        }
        updateOne("""
                UPDATE branch_sync_effect_step
                SET status = 'REQUESTED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, evidence = NULL,
                    last_error = NULL, completed_at_ms = NULL
                WHERE id = ? AND status = 'FAILED'
                  AND attempt_count = ? AND attempt_count < attempt_limit
                """, "Branch sync step changed before retry", step.id(),
                step.attemptCount());
        return Optional.of(requireBranchStep(
                context.episodeId(), context.ordinal()));
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
        String reason = error == null || error.isBlank()
                ? "BranchSync exhausted without error evidence" : error;
        RemoteContext remote = requireRemoteContext(
                episode.taskId(), episode.stageId());
        CodeSubject code = requireCodeSubject(episode.taskId());
        updateOne("""
                UPDATE branch_sync_episode
                SET status = 'FAILED', completed_at_ms = ?, error_message = ?
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'STOPPED')
                """, "Branch sync changed before failure",
                at.toEpochMilli(), reason, episode.id());
        String blockerId = id("branch-sync-exhausted-blocker", episode.id());
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'EPISODE', ?, ?, 'BRANCH_SYNC_EXHAUSTED',
                    'OPEN', '{"choices":["MANUAL_TAKEOVER",'
                        || '"STOP_AUTOMATION"]}', ?)
                """, blockerId, episode.taskId(), episode.stageId(),
                episode.id(), episode.id(), at.toEpochMilli());
        jdbc.update("""
                INSERT INTO branch_sync_exhaustion_v319(
                    branch_sync_episode_id, blocker_id, task_id, stage_id,
                    remote_head_sha, remote_base_sha, code_fingerprint,
                    code_head_sha, code_base_sha, reason, exhausted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, episode.id(), blockerId, episode.taskId(),
                episode.stageId(), remote.headSha(), remote.baseSha(),
                code.codeFingerprint(), code.headSha(), code.baseSha(),
                reason, at.toEpochMilli());
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

    public void succeedLocalCiPrecondition(
            BranchEpisode episode, CodeSubject result, Instant at)
    {
        requireTransaction();
        requireNonNull(episode, "episode is null");
        requireNonNull(result, "result is null");
        requireNonNull(at, "at is null");
        updateOne("""
                UPDATE branch_sync_episode
                SET status = 'SUCCEEDED', result_code_fingerprint = ?,
                    result_head_sha = ?, result_snapshot_id = source_snapshot_id,
                    completed_at_ms = ?
                WHERE id = ? AND purpose = 'CI_PRECONDITION_LOCAL'
                  AND status IN ('REBASING', 'CONFLICT_REPAIR')
                """, "Local CI precondition changed before completion",
                result.codeFingerprint(), result.headSha(), at.toEpochMilli(),
                episode.id());
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
                rs.getString("ci_policy_revision_id"), policy(rs), ImmutableSet.of());
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
                && rs.getInt("current_ci_policy") == 1
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
                rs.getInt("semantic_attempt"), current, policy(rs), ImmutableSet.of());
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

    private static BaseRepairAuthorization baseRepairAuthorization(ResultSet rs)
            throws SQLException
    {
        return new BaseRepairAuthorization(
                rs.getString("id"), rs.getString("ci_repair_episode_id"),
                rs.getString("manifest_id"), rs.getInt("semantic_attempt"),
                rs.getString("authority_kind"),
                rs.getString("automation_policy_id"),
                rs.getString("blocker_id"), rs.getString("command_id"),
                rs.getString("actor"), rs.getString("reason"),
                rs.getString("failed_ci_evaluation_id"),
                rs.getString("remote_pr_snapshot_id"),
                rs.getString("expected_worktree_head_sha"),
                rs.getString("subject_head_sha"),
                rs.getString("subject_base_sha"),
                rs.getString("manifest_digest"), rs.getString("status"),
                instant(rs, "claimed_at_ms"),
                nullableInstant(rs, "terminal_at_ms"),
                rs.getString("terminal_evidence"));
    }

    private static CiTurnIntent ciTurnIntent(ResultSet rs) throws SQLException
    {
        int predecessor = rs.getInt("predecessor_revision");
        Integer predecessorRevision = rs.wasNull() ? null : predecessor;
        return new CiTurnIntent(
                rs.getString("kind"), rs.getString("id"),
                rs.getInt("semantic_attempt"),
                rs.getInt("execution_attempt"),
                rs.getString("predecessor_snapshot_id"),
                predecessorRevision);
    }

    private static CiTurnFreshness ciTurnFreshness(ResultSet rs)
            throws SQLException
    {
        int predecessor = rs.getInt("predecessor_observation_revision");
        Integer predecessorRevision = rs.wasNull() ? null : predecessor;
        return new CiTurnFreshness(
                rs.getString("id"), rs.getString("ci_repair_episode_id"),
                rs.getString("intent_kind"), rs.getString("intent_id"),
                rs.getInt("semantic_attempt"),
                rs.getInt("execution_attempt"),
                rs.getString("predecessor_snapshot_id"), predecessorRevision,
                rs.getString("accepted_snapshot_id"),
                rs.getInt("accepted_observation_revision"),
                rs.getString("accepted_ci_evaluation_id"),
                rs.getString("remote_head_sha"),
                rs.getString("authoritative_base_sha"),
                rs.getString("code_fingerprint"),
                rs.getString("code_head_sha"),
                rs.getString("code_base_sha"),
                rs.getString("prepublish_branch_sync_episode_id"),
                instant(rs, "authorized_at_ms"));
    }

    private static CiEffectDelivery ciEffectDelivery(ResultSet rs)
            throws SQLException
    {
        String kind = rs.getString("kind");
        String currentHead = rs.getString("current_head_sha");
        String episodeHead = rs.getString("last_pushed_head_sha") == null
                ? rs.getString("subject_head_sha")
                : rs.getString("last_pushed_head_sha");
        boolean remoteCurrent = (currentHead.equals(episodeHead)
                    || "PUSH_HEAD".equals(kind)
                        && currentHead.equals(rs.getString("expected_head_sha")))
                && rs.getString("subject_base_sha").equals(
                        rs.getString("current_base_sha"));
        boolean codeCurrent = "RERUN".equals(kind)
                ? rs.getString("expected_head_sha").equals(
                        rs.getString("current_head_sha"))
                    && rs.getString("expected_base_sha").equals(
                        rs.getString("current_base_sha"))
                : Objects.equals(
                        rs.getString("expected_code_fingerprint"),
                        rs.getString("current_code_fingerprint"))
                    && rs.getString("expected_head_sha").equals(
                        rs.getString("current_code_head_sha"))
                    && rs.getString("expected_base_sha").equals(
                        rs.getString("current_code_base_sha"));
        boolean current = "ACTIVE".equals(rs.getString("lifecycle_state"))
                && rs.getLong("task_epoch") == rs.getLong("current_task_epoch")
                && rs.getString("stage_id").equals(
                        rs.getString("current_stage_id"))
                && rs.getLong("stage_generation")
                        == rs.getLong("current_stage_generation")
                && remoteCurrent && codeCurrent;
        return new CiEffectDelivery(
                rs.getString("row_id"), rs.getString("operation_id"),
                rs.getString("ci_repair_episode_id"), rs.getString("kind"),
                rs.getString("operation_kind"),
                rs.getString("base_repair_authorization_id"),
                rs.getString("lease_expected_sha"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"),
                rs.getString("expected_code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("episode_status"),
                rs.getString("subject_head_sha"),
                rs.getString("subject_base_sha"),
                rs.getInt("rerun_count"), rs.getInt("rerun_limit"),
                rs.getInt("fix_attempt_count"),
                rs.getInt("fix_attempt_limit"),
                rs.getInt("push_count"), rs.getInt("push_limit"),
                rs.getString("last_pushed_head_sha"),
                rs.getString("last_push_result_evaluation_id"), current);
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
                rs.getString("branch_sync_policy_revision_id"),
                rs.getString("policy_source"), rs.getString("purpose"),
                rs.getString("authority_kind"),
                rs.getString("authority_id"),
                rs.getString("ci_repair_episode_id"),
                rs.getString("ci_turn_intent_kind"),
                rs.getString("ci_turn_intent_id"),
                rs.getString("source_code_fingerprint"),
                rs.getString("source_code_head_sha"),
                rs.getString("source_code_base_sha"),
                rs.getString("status"),
                rs.getInt("attempt_count"), rs.getInt("attempt_limit"),
                rs.getString("result_code_fingerprint"),
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

    private void insertWorktreeSubject(
            String sourceKind,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String codeFingerprint,
            String headSha,
            String baseSha,
            Instant at)
    {
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1
                FROM remote_worktree_subject
                WHERE task_id = ? AND task_epoch = ?
                """, Integer.class, taskId, taskEpoch);
        jdbc.update("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha,
                    base_sha, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id("remote-worktree-subject", operationId), taskId,
                taskEpoch, stageId, stageGeneration, revision, sourceKind,
                operationId, codeFingerprint, headSha, baseSha,
                at.toEpochMilli());
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

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
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
            requiredChecks = ImmutableSet.copyOf(requiredChecks);
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

    public record CodeSubject(
            String codeFingerprint, String headSha, String baseSha)
    {
        public CodeSubject
        {
            requireNonNull(codeFingerprint, "codeFingerprint is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(baseSha, "baseSha is null");
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
            requiredChecks = ImmutableSet.copyOf(requiredChecks);
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

    public record ParkedObservation(
            String ticketId,
            String taskId,
            String stageId,
            Instant lastAttemptedAt)
    {
        public ParkedObservation
        {
            requireNonNull(ticketId, "ticketId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(stageId, "stageId is null");
            requireNonNull(lastAttemptedAt, "lastAttemptedAt is null");
            if (ticketId.isBlank() || taskId.isBlank() || stageId.isBlank()) {
                throw new IllegalArgumentException(
                        "Parked observation identity must not be blank");
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

    public record CiPushSubject(String headSha, String baseSha) {}

    public record CiTurnFreshness(
            String id,
            String episodeId,
            String intentKind,
            String intentId,
            int semanticAttempt,
            int executionAttempt,
            String predecessorSnapshotId,
            Integer predecessorObservationRevision,
            String acceptedSnapshotId,
            int acceptedObservationRevision,
            String acceptedCiEvaluationId,
            String remoteHeadSha,
            String authoritativeBaseSha,
            String codeFingerprint,
            String codeHeadSha,
            String codeBaseSha,
            String prepublishBranchSyncEpisodeId,
            Instant authorizedAt) {}

    public record ManualCiTurnIntent(
            String id,
            String episodeId,
            String baseRepairAuthorizationId,
            String predecessorSnapshotId,
            int predecessorObservationRevision,
            int semanticAttempt,
            Instant requestedAt) {}

    public record ManualCiBranchSyncAuthorization(
            String id,
            String blockerId,
            String episodeId,
            String taskId,
            String stageId,
            String predecessorSnapshotId,
            int predecessorObservationRevision,
            String commandId,
            String observationOperationId,
            String actor,
            String reason,
            String status,
            String branchSyncEpisodeId,
            Instant claimedAt,
            Instant consumedAt) {}

    private record CiTurnIntent(
            String kind,
            String id,
            int semanticAttempt,
            int executionAttempt,
            String predecessorSnapshotId,
            Integer predecessorRevision) {}

    private record CiNoChangeRetrySource(
            String treeResultId,
            String operationId,
            String stageTurnId,
            int semanticAttempt,
            int executionAttempt,
            String acceptedSnapshotId,
            int acceptedObservationRevision) {}

    private record RemoteAcceptedSubject(String snapshotId, int revision) {}

    public record BaseRepairAuthorization(
            String id,
            String episodeId,
            String manifestId,
            int semanticAttempt,
            String authorityKind,
            String automationPolicyId,
            String blockerId,
            String commandId,
            String actor,
            String reason,
            String failedCiEvaluationId,
            String remotePrSnapshotId,
            String expectedWorktreeHeadSha,
            String subjectHeadSha,
            String subjectBaseSha,
            String manifestDigest,
            String status,
            Instant claimedAt,
            Instant terminalAt,
            String terminalEvidence) {}

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
            String operationKind,
            String baseRepairAuthorizationId,
            String leaseExpectedSha,
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
            int fixAttemptCount,
            int fixAttemptLimit,
            int pushCount,
            int pushLimit,
            String lastPushedHeadSha,
            String lastPushResultEvaluationId,
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
            String policyRevisionId,
            String policySource,
            String purpose,
            String authorityKind,
            String authorityId,
            String ciRepairEpisodeId,
            String ciTurnIntentKind,
            String ciTurnIntentId,
            String sourceCodeFingerprint,
            String sourceCodeHeadSha,
            String sourceCodeBaseSha,
            String status,
            int attemptCount,
            int attemptLimit,
            String resultCodeFingerprint,
            String resultHeadSha,
            String resultSnapshotId,
            Instant openedAt,
            Instant completedAt,
            String errorMessage) {}

    public record BranchControlReceipt(
            String taskId,
            String episodeId,
            String blockerId,
            String stageId,
            String commandId,
            String kind,
            String actor,
            String reason,
            Instant consumedAt) {}

    public record CiLocalPrecondition(
            CiEpisode episode,
            String intentKind,
            String intentId,
            CodeSubject codeSubject)
    {
        public CiLocalPrecondition
        {
            requireNonNull(episode, "episode is null");
            requireText(intentKind, "intentKind");
            requireText(intentId, "intentId");
            requireNonNull(codeSubject, "codeSubject is null");
        }
    }

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

    private record CiPolicyUpgradeSubject(
            String policyId,
            String source,
            String bindingId,
            int nextRevision) {}

    private record ObservationOperationRow(
            String operationId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            int semanticAttempt,
            String expectedHeadSha,
            String expectedBaseSha,
            String repositoryId,
            int pullRequestNumber,
            String ciPolicyRevisionId) {}

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
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one dispatched branch effect, found " + rows.size());
        }
        return rows.getFirst();
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
