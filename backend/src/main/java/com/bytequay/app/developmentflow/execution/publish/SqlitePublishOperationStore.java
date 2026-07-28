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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectStep;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.OperationSnapshot;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishRequest;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.Route;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.StepStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact SQLite store for one V2 publish operation and its fixed effect ledger. */
@Repository
public class SqlitePublishOperationStore
        implements PublishOperationHandler.OperationStore
{
    private final JdbcTemplate jdbc;

    public SqlitePublishOperationStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public OperationSnapshot requireByOperationId(String operationId)
    {
        requireText(operationId, "operationId");
        List<PublishRequest> requests = jdbc.query("""
                SELECT operation.id AS publish_operation_id,
                    operation.operation_id,
                    operation.local_development_stage_id AS stage_id,
                    operation.task_id,
                    task.thread_id AS trunk_id,
                    trunk.workspace_id,
                    operation.task_epoch,
                    operation.stage_generation,
                    operation.semantic_attempt,
                    operation.status AS operation_status,
                    task.workflow_version,
                    task.lifecycle_state AS task_lifecycle,
                    task.epoch AS current_task_epoch,
                    current.stage_id AS current_stage_id,
                    current.stage_generation AS current_stage_generation,
                    stage.checkpoint AS stage_checkpoint,
                    CASE WHEN authorization.revoked_at_ms IS NULL
                              AND authorization.consumed_at_ms IS NULL
                         THEN 1 ELSE 0 END AS authorization_active,
                    manifest.id AS manifest_id,
                    manifest.pr_id,
                    operation.code_fingerprint,
                    operation.expected_head_sha,
                    operation.expected_base_sha,
                    manifest.route,
                    manifest.base_repository_id,
                    manifest.head_repository_id,
                    manifest.publish_repository_id,
                    manifest.branch_name,
                    manifest.head_ref,
                    manifest.base_branch,
                    manifest.pr_title,
                    manifest.pr_body,
                    manifest.pr_content_revision,
                    manifest.pr_content_digest,
                    code.worktree_path
                FROM publish_operation operation
                JOIN publish_authorization authorization
                  ON authorization.id = operation.publish_authorization_id
                JOIN promotion_manifest manifest
                  ON manifest.id = authorization.manifest_id
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage stage ON stage.id = operation.local_development_stage_id
                JOIN task_code_identity code ON code.task_id = task.id
                WHERE operation.operation_id = ?
                """, (result, row) -> mapRequest(result), operationId);
        if (requests.size() != 1) {
            throw new IllegalStateException(
                    "expected one exact PublishOperation graph for "
                            + operationId + ", found " + requests.size());
        }
        PublishRequest request = requests.getFirst();
        List<EffectStep> steps = jdbc.query("""
                SELECT id, publish_operation_id, ordinal, kind, status,
                    attempt_count, attempt_limit, claim_mode, claim_owner,
                    claimed_at_ms, lease_until_ms, evidence, last_error,
                    completed_at_ms
                FROM publish_effect_step
                WHERE publish_operation_id = ?
                ORDER BY ordinal
                """, (result, row) -> mapStep(result), request.publishOperationId());
        return new OperationSnapshot(request, steps);
    }

    @Override
    public Optional<EffectClaim> tryClaim(
            EffectStep step,
            ClaimMode mode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        requireNonNull(step, "step is null");
        requireNonNull(mode, "mode is null");
        requireText(claimOwner, "claimOwner");
        requireNonNull(claimedAt, "claimedAt is null");
        requireNonNull(leaseUntil, "leaseUntil is null");
        if (!leaseUntil.isAfter(claimedAt)) {
            throw new IllegalArgumentException("effect lease must follow its claim");
        }
        int updated = jdbc.update("""
                UPDATE publish_effect_step
                SET status = 'CLAIMED',
                    attempt_count = attempt_count + 1,
                    claim_mode = ?, claim_owner = ?,
                    claimed_at_ms = ?, lease_until_ms = ?,
                    evidence = NULL, last_error = NULL,
                    completed_at_ms = NULL
                WHERE id = ? AND publish_operation_id = ?
                  AND status = ? AND attempt_count = ?
                  AND (status <> 'CLAIMED' OR lease_until_ms <= ?)
                """,
                mode.name(), claimOwner,
                claimedAt.toEpochMilli(), leaseUntil.toEpochMilli(),
                step.id(), step.publishOperationId(), step.status().name(),
                step.attemptCount(), claimedAt.toEpochMilli());
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(new EffectClaim(
                step, mode, step.attemptCount() + 1, step.attemptLimit(),
                claimOwner, claimedAt, leaseUntil));
    }

    @Override
    public boolean finish(
            EffectClaim claim,
            StepStatus status,
            String evidenceJson,
            String error,
            Instant completedAt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(status, "status is null");
        requireNonNull(completedAt, "completedAt is null");
        if (status != StepStatus.SUCCEEDED
                && status != StepStatus.FAILED
                && status != StepStatus.INDETERMINATE) {
            throw new IllegalArgumentException("effect result must be terminal for its attempt");
        }
        if ((status == StepStatus.SUCCEEDED) != (evidenceJson != null)
                || (status == StepStatus.SUCCEEDED) == (error != null)) {
            throw new IllegalArgumentException("effect result evidence has the wrong shape");
        }
        return jdbc.update("""
                UPDATE publish_effect_step
                SET status = ?, claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    evidence = ?, last_error = ?, completed_at_ms = ?
                WHERE id = ? AND publish_operation_id = ?
                  AND status = 'CLAIMED'
                  AND attempt_count = ? AND claim_mode = ?
                  AND claim_owner = ? AND claimed_at_ms = ?
                  AND lease_until_ms = ?
                """,
                status.name(), evidenceJson, error, completedAt.toEpochMilli(),
                claim.step().id(), claim.step().publishOperationId(),
                claim.attempt(), claim.mode().name(), claim.claimOwner(),
                claim.claimedAt().toEpochMilli(), claim.leaseUntil().toEpochMilli()) == 1;
    }

    private static PublishRequest mapRequest(ResultSet result)
            throws SQLException
    {
        return new PublishRequest(
                result.getString("publish_operation_id"),
                result.getString("operation_id"),
                result.getString("stage_id"),
                result.getString("task_id"),
                result.getString("trunk_id"),
                result.getString("workspace_id"),
                result.getLong("task_epoch"),
                result.getLong("stage_generation"),
                result.getInt("semantic_attempt"),
                result.getString("operation_status"),
                result.getString("workflow_version"),
                result.getString("task_lifecycle"),
                result.getLong("current_task_epoch"),
                result.getString("current_stage_id"),
                result.getLong("current_stage_generation"),
                result.getString("stage_checkpoint"),
                result.getInt("authorization_active") == 1,
                result.getString("manifest_id"),
                result.getString("pr_id"),
                result.getString("code_fingerprint"),
                result.getString("expected_head_sha"),
                result.getString("expected_base_sha"),
                Route.valueOf(result.getString("route")),
                result.getString("base_repository_id"),
                result.getString("head_repository_id"),
                result.getString("publish_repository_id"),
                result.getString("branch_name"),
                result.getString("head_ref"),
                result.getString("base_branch"),
                result.getString("pr_title"),
                result.getString("pr_body"),
                result.getInt("pr_content_revision"),
                result.getString("pr_content_digest"),
                result.getString("worktree_path"));
    }

    private static EffectStep mapStep(ResultSet result)
            throws SQLException
    {
        String mode = result.getString("claim_mode");
        return new EffectStep(
                result.getString("id"),
                result.getString("publish_operation_id"),
                result.getInt("ordinal"),
                EffectKind.valueOf(result.getString("kind")),
                StepStatus.valueOf(result.getString("status")),
                result.getInt("attempt_count"),
                result.getInt("attempt_limit"),
                mode == null ? null : ClaimMode.valueOf(mode),
                result.getString("claim_owner"),
                instant(result, "claimed_at_ms"),
                instant(result, "lease_until_ms"),
                result.getString("evidence"),
                result.getString("last_error"),
                instant(result, "completed_at_ms"));
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
