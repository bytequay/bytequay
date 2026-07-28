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
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.RemoteReference;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.Context;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.Receipt;
import com.bytequay.app.developmentflow.stage.StageManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** SQLite owner store for exact publish acceptance and Remote bootstrap. */
@Repository
public class SqlitePublishResultStore
        implements PublishResultDeliveryPort.Store
{
    private final JdbcTemplate jdbc;

    public SqlitePublishResultStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public String requireTaskId(String operationId)
    {
        requireText(operationId, "operationId");
        List<String> rows = jdbc.query(
                "SELECT task_id FROM publish_operation WHERE operation_id = ?",
                (result, row) -> result.getString(1), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one PublishOperation for " + operationId);
        }
        return rows.getFirst();
    }

    @Override
    public Context requireContext(String operationId)
    {
        requireText(operationId, "operationId");
        String handoffCommandId = id("accept-published", operationId);
        List<Context> rows = jdbc.query("""
                SELECT operation.id AS publish_operation_id,
                    operation.operation_id,
                    operation.publish_authorization_id AS authorization_id,
                    authorization.manifest_id,
                    authorization.pr_id,
                    operation.task_id,
                    operation.local_development_stage_id AS stage_id,
                    operation.task_epoch,
                    operation.stage_generation,
                    operation.semantic_attempt,
                    operation.code_fingerprint,
                    operation.expected_head_sha,
                    operation.expected_base_sha,
                    operation.status AS operation_status,
                    ticket.status AS ticket_status,
                    ticket.pending_result_outcome,
                    COALESCE(receipt.expected_task_version,
                        task.aggregate_version) AS handoff_task_version,
                    COALESCE(receipt.next_stage_generation, (
                        SELECT COALESCE(MAX(remote_stage.generation), 0) + 1
                        FROM stage remote_stage
                        WHERE remote_stage.task_id = operation.task_id
                          AND remote_stage.kind = 'REMOTE_DEVELOPMENT'
                    )) AS next_remote_generation,
                    policy.auto_approve,
                    policy.auto_merge,
                    policy.min_approvals,
                    manifest.route,
                    manifest.base_repository_id,
                    manifest.head_repository_id
                FROM publish_operation operation
                JOIN publish_authorization authorization
                  ON authorization.id = operation.publish_authorization_id
                JOIN promotion_manifest manifest
                  ON manifest.id = authorization.manifest_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN task_policy_revision policy ON policy.id = task.policy_revision_id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                LEFT JOIN task_command_receipt receipt
                  ON receipt.task_id = operation.task_id
                 AND receipt.command_id = ?
                 AND receipt.cause = 'OPEN_REMOTE_DEVELOPMENT'
                WHERE operation.operation_id = ?
                """, (result, row) -> mapContext(result),
                handoffCommandId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact publish delivery graph for " + operationId);
        }
        return rows.getFirst();
    }

    @Override
    public Optional<Receipt> findReceipt(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation_id, raw_result_digest, outcome, acceptance,
                    remote_stage_id, delivered_at_ms
                FROM publish_delivery_receipt
                WHERE operation_id = ?
                """, (result, row) -> mapReceipt(result), operationId)
                .stream().findFirst();
    }

    @Override
    public void completePublished(
            Context context,
            String bindingId,
            RemoteReference remote,
            String resultEvidence,
            Instant completedAt)
    {
        requireNonNull(context, "context is null");
        requireText(bindingId, "bindingId");
        requireNonNull(remote, "remote is null");
        requireText(resultEvidence, "resultEvidence");
        requireNonNull(completedAt, "completedAt is null");
        if (!context.baseRepositoryId().equalsIgnoreCase(remote.repositoryId())
                || !context.expectedHeadSha().equals(remote.headSha())
                || !context.expectedBaseSha().equals(remote.baseSha())) {
            throw new IllegalArgumentException(
                    "Published remote identity differs from the frozen subject");
        }
        int changed = jdbc.update("""
                UPDATE publish_operation
                SET status = 'SUCCEEDED', remote_repository_id = ?,
                    remote_pr_number = ?, remote_pr_url = ?,
                    remote_head_ref = ?, remote_head_sha = ?, remote_base_sha = ?,
                    result_evidence = ?, completed_at_ms = ?, error_message = NULL
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                  AND task_id = ? AND task_epoch = ?
                  AND local_development_stage_id = ? AND stage_generation = ?
                  AND semantic_attempt = ? AND code_fingerprint = ?
                  AND expected_head_sha = ? AND expected_base_sha = ?
                """,
                remote.repositoryId(), remote.number(), remote.url(),
                remote.headRef(), remote.headSha(), remote.baseSha(),
                resultEvidence, completedAt.toEpochMilli(),
                context.publishOperationId(), context.operationId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.attempt(),
                context.codeFingerprint(), context.expectedHeadSha(),
                context.expectedBaseSha());
        if (changed != 1 && !publishedMatches(context, remote, resultEvidence)) {
            throw new IllegalStateException(
                    "PublishOperation could not accept its exact successful result");
        }

        jdbc.update("""
                INSERT OR IGNORE INTO remote_pr_binding(
                    id, task_id, pr_id, publish_operation_id,
                    publish_authorization_id, manifest_id, route,
                    base_repository_id, head_repository_id,
                    remote_repository_id, remote_pr_number, remote_pr_url,
                    remote_head_ref, remote_head_sha, remote_base_sha,
                    evidence, bound_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                bindingId, context.taskId(), context.prId(),
                context.publishOperationId(), context.authorizationId(),
                context.manifestId(), context.route().name(),
                context.baseRepositoryId(), context.headRepositoryId(),
                remote.repositoryId(), remote.number(), remote.url(),
                remote.headRef(), remote.headSha(), remote.baseSha(),
                resultEvidence, completedAt.toEpochMilli());
        requireCount("""
                SELECT COUNT(*) FROM remote_pr_binding
                WHERE id = ? AND task_id = ? AND pr_id = ?
                  AND publish_operation_id = ?
                  AND publish_authorization_id = ? AND manifest_id = ?
                  AND remote_repository_id = ? AND remote_pr_number = ?
                  AND remote_head_ref = ? AND remote_head_sha = ?
                  AND remote_base_sha = ?
                """, bindingId, context.taskId(), context.prId(),
                context.publishOperationId(), context.authorizationId(),
                context.manifestId(), remote.repositoryId(), remote.number(),
                remote.headRef(), remote.headSha(), remote.baseSha());

        int authorization = jdbc.update("""
                UPDATE publish_authorization
                SET consumed_at_ms = ?, outcome = 'PUBLISHED'
                WHERE id = ? AND revoked_at_ms IS NULL
                  AND consumed_at_ms IS NULL AND outcome IS NULL
                """, completedAt.toEpochMilli(), context.authorizationId());
        if (authorization != 1) {
            requireCount("""
                    SELECT COUNT(*) FROM publish_authorization
                    WHERE id = ? AND revoked_at_ms IS NULL
                      AND consumed_at_ms IS NOT NULL AND outcome = 'PUBLISHED'
                    """, context.authorizationId());
        }
    }

    @Override
    public void completeFailure(
            Context context,
            DispatchTicket.Outcome outcome,
            String error,
            Instant completedAt)
    {
        requireNonNull(context, "context is null");
        requireNonNull(outcome, "outcome is null");
        requireText(error, "error");
        requireNonNull(completedAt, "completedAt is null");
        if (outcome != DispatchTicket.Outcome.FAILED
                && outcome != DispatchTicket.Outcome.CANCELED) {
            throw new IllegalArgumentException("Publish failure outcome is invalid");
        }
        int changed = jdbc.update("""
                UPDATE publish_operation
                SET status = ?, completed_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ? AND status = 'DISPATCHED'
                  AND task_id = ? AND task_epoch = ?
                  AND local_development_stage_id = ? AND stage_generation = ?
                  AND semantic_attempt = ?
                """, outcome.name(), completedAt.toEpochMilli(), error,
                context.publishOperationId(), context.operationId(),
                context.taskId(), context.taskEpoch(), context.stageId(),
                context.stageGeneration(), context.attempt());
        if (changed != 1) {
            requireCount("""
                    SELECT COUNT(*) FROM publish_operation
                    WHERE id = ? AND operation_id = ? AND status = ?
                      AND error_message = ?
                    """, context.publishOperationId(), context.operationId(),
                    outcome.name(), error);
        }
        if (outcome == DispatchTicket.Outcome.CANCELED) {
            int consumed = jdbc.update("""
                    UPDATE publish_authorization
                    SET consumed_at_ms = ?, outcome = 'CANCELED'
                    WHERE id = ? AND revoked_at_ms IS NULL
                      AND consumed_at_ms IS NULL AND outcome IS NULL
                    """, completedAt.toEpochMilli(), context.authorizationId());
            if (consumed != 1) {
                requireCount("""
                        SELECT COUNT(*) FROM publish_authorization
                        WHERE id = ? AND consumed_at_ms IS NOT NULL
                          AND outcome = 'CANCELED'
                        """, context.authorizationId());
            }
        }
        else {
            int revoked = jdbc.update("""
                    UPDATE publish_authorization SET revoked_at_ms = ?
                    WHERE id = ? AND revoked_at_ms IS NULL
                      AND consumed_at_ms IS NULL AND outcome IS NULL
                    """, completedAt.toEpochMilli(), context.authorizationId());
            if (revoked != 1) {
                requireCount("""
                        SELECT COUNT(*) FROM publish_authorization
                        WHERE id = ? AND revoked_at_ms IS NOT NULL
                          AND consumed_at_ms IS NULL AND outcome IS NULL
                        """, context.authorizationId());
            }
        }
    }

    @Override
    public void initializeRemote(
            Context context,
            String bindingId,
            StageManager.State remoteStage,
            String ciPolicyId,
            String automationPolicyId,
            Instant openedAt)
    {
        requireNonNull(context, "context is null");
        requireText(bindingId, "bindingId");
        requireNonNull(remoteStage, "remoteStage is null");
        requireText(ciPolicyId, "ciPolicyId");
        requireText(automationPolicyId, "automationPolicyId");
        requireNonNull(openedAt, "openedAt is null");
        jdbc.update("""
                INSERT OR IGNORE INTO remote_development_stage(
                    stage_id, task_id, generation, remote_pr_binding_id,
                    accepted_snapshot_id, accepted_observation_revision,
                    current_head_sha, current_base_sha, subject_changed_at_ms)
                VALUES (?, ?, ?, ?, NULL, 0, ?, ?, ?)
                """, remoteStage.id(), context.taskId(), remoteStage.generation(),
                bindingId, context.expectedHeadSha(), context.expectedBaseSha(),
                openedAt.toEpochMilli());
        requireCount("""
                SELECT COUNT(*) FROM remote_development_stage
                WHERE stage_id = ? AND task_id = ? AND generation = ?
                  AND remote_pr_binding_id = ?
                  AND accepted_snapshot_id IS NULL
                  AND accepted_observation_revision = 0
                  AND current_head_sha = ? AND current_base_sha = ?
                """, remoteStage.id(), context.taskId(), remoteStage.generation(),
                bindingId, context.expectedHeadSha(), context.expectedBaseSha());

        jdbc.update("""
                INSERT OR IGNORE INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome,
                    pending_outcome, neutral_outcome, skipped_outcome,
                    canceled_outcome, created_by, created_at_ms)
                VALUES (?, ?, ?, 1, 'PUBLISH_HANDOFF_FAIL_CLOSED',
                    'WAITING', 'WAITING', 'WAITING', 'WAITING',
                    'FAILED', 'FAILED', 'FAILED', ?, ?)
                """, ciPolicyId, context.taskId(), bindingId, ACTOR,
                openedAt.toEpochMilli());
        requireCount("""
                SELECT COUNT(*) FROM remote_ci_policy_revision
                WHERE id = ? AND task_id = ? AND remote_pr_binding_id = ?
                  AND revision = 1
                """, ciPolicyId, context.taskId(), bindingId);

        jdbc.update("""
                INSERT OR IGNORE INTO task_automation_policy(
                    id, task_id, revision, source, auto_approve, auto_merge,
                    keep_draft, minimum_write_approvals,
                    max_merge_queue_reenqueues, require_low_risk,
                    require_small_effort, stewardship_exception,
                    created_by, created_at_ms)
                VALUES (?, ?, 1, 'TASK_POLICY_AT_PUBLISH', ?, ?,
                    0, ?, 1, 0, 0, 0, ?, ?)
                """, automationPolicyId, context.taskId(),
                context.autoApprove() ? 1 : 0, context.autoMerge() ? 1 : 0,
                context.minimumApprovals(), ACTOR, openedAt.toEpochMilli());
        requireCount("""
                SELECT COUNT(*) FROM task_automation_policy
                WHERE id = ? AND task_id = ? AND revision = 1
                  AND auto_approve = ? AND auto_merge = ?
                  AND minimum_write_approvals = ?
                """, automationPolicyId, context.taskId(),
                context.autoApprove() ? 1 : 0, context.autoMerge() ? 1 : 0,
                context.minimumApprovals());
    }

    @Override
    public void insertReceipt(Receipt receipt)
    {
        requireNonNull(receipt, "receipt is null");
        if (jdbc.update("""
                INSERT INTO publish_delivery_receipt(
                    operation_id, raw_result_digest, outcome, acceptance,
                    remote_stage_id, delivered_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """, receipt.operationId(), receipt.rawResultDigest(),
                receipt.outcome().name(), receipt.acceptance().name(),
                receipt.remoteStageId(), receipt.deliveredAt().toEpochMilli()) != 1) {
            throw new IllegalStateException("Publish delivery receipt was not inserted");
        }
    }

    private boolean publishedMatches(
            Context context, RemoteReference remote, String resultEvidence)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM publish_operation
                WHERE id = ? AND operation_id = ? AND status = 'SUCCEEDED'
                  AND remote_repository_id = ? AND remote_pr_number = ?
                  AND remote_pr_url = ? AND remote_head_ref = ?
                  AND remote_head_sha = ? AND remote_base_sha = ?
                  AND result_evidence = ?
                """, Integer.class,
                context.publishOperationId(), context.operationId(),
                remote.repositoryId(), remote.number(), remote.url(),
                remote.headRef(), remote.headSha(), remote.baseSha(),
                resultEvidence);
        return count != null && count == 1;
    }

    private void requireCount(String sql, Object... arguments)
    {
        Integer count = jdbc.queryForObject(sql, Integer.class, arguments);
        if (count == null || count != 1) {
            throw new IllegalStateException("Exact publish delivery evidence is missing");
        }
    }

    private static Context mapContext(ResultSet result)
            throws SQLException
    {
        String pending = result.getString("pending_result_outcome");
        return new Context(
                result.getString("publish_operation_id"),
                result.getString("operation_id"),
                result.getString("authorization_id"),
                result.getString("manifest_id"),
                result.getString("pr_id"),
                result.getString("task_id"),
                result.getString("stage_id"),
                result.getLong("task_epoch"),
                result.getLong("stage_generation"),
                result.getInt("semantic_attempt"),
                result.getString("code_fingerprint"),
                result.getString("expected_head_sha"),
                result.getString("expected_base_sha"),
                result.getString("operation_status"),
                result.getString("ticket_status"),
                pending == null ? null : DispatchTicket.Outcome.valueOf(pending),
                result.getLong("handoff_task_version"),
                result.getLong("next_remote_generation"),
                result.getInt("auto_approve") == 1,
                result.getInt("auto_merge") == 1,
                result.getInt("min_approvals"),
                PublishOperationHandler.Route.valueOf(result.getString("route")),
                result.getString("base_repository_id"),
                result.getString("head_repository_id"));
    }

    private static Receipt mapReceipt(ResultSet result)
            throws SQLException
    {
        return new Receipt(
                result.getString("operation_id"),
                result.getString("raw_result_digest"),
                DispatchTicket.Outcome.valueOf(result.getString("outcome")),
                DispatchTicket.Acceptance.valueOf(result.getString("acceptance")),
                result.getString("remote_stage_id"),
                Instant.ofEpochMilli(result.getLong("delivered_at_ms")));
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static final String ACTOR = "v2-publish-delivery";
}
