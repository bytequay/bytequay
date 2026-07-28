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

import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Durable manual authority for one exact ready-but-unmergeable PR action. */
@Repository
public class SqliteReadinessAssistanceStore
{
    private static final int ATTEMPT_LIMIT = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqliteDispatchWakeStore wakes;

    public SqliteReadinessAssistanceStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SqliteDispatchWakeStore wakes)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
        this.wakes = requireNonNull(wakes, "wakes is null");
    }

    public Optional<Availability> availability(String taskId, String stageId)
    {
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        return jdbc.query("""
                SELECT task.epoch AS task_epoch, owner.id AS stage_id,
                       owner.generation AS stage_generation,
                       snapshot.id AS snapshot_id,
                       readiness.id AS readiness_id,
                       policy.id AS policy_id, remote.current_head_sha,
                       remote.current_base_sha, snapshot.viewer_login
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN remote_development_stage remote ON remote.stage_id = owner.id
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                JOIN remote_readiness_evidence readiness
                  ON readiness.remote_pr_snapshot_id = snapshot.id
                 AND readiness.remote_development_stage_id = owner.id
                JOIN task_automation_policy policy
                  ON policy.id = readiness.automation_policy_id
                WHERE task.id = ? AND owner.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = readiness.task_epoch
                  AND current.stage_generation = owner.generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.checkpoint = 'READY_TO_MERGE'
                  AND owner.completed_at_ms IS NULL
                  AND remote.task_id = task.id
                  AND remote.generation = owner.generation
                  AND snapshot.remote_pr_binding_id = remote.remote_pr_binding_id
                  AND snapshot.head_sha = remote.current_head_sha
                  AND snapshot.base_sha = remote.current_base_sha
                  AND remote.current_head_sha = readiness.head_sha
                  AND remote.current_base_sha = readiness.base_sha
                  AND snapshot.task_id = task.id
                  AND snapshot.task_epoch = task.epoch
                  AND snapshot.remote_development_stage_id = owner.id
                  AND snapshot.stage_generation = owner.generation
                  AND snapshot.pr_state = 'OPEN'
                  AND snapshot.viewer_login IS NOT NULL
                  AND length(trim(snapshot.viewer_login)) > 0
                  AND snapshot.viewer_can_merge = 0
                  AND readiness.task_id = task.id
                  AND readiness.stage_generation = owner.generation
                  AND readiness.ready = 1
                  AND policy.task_id = task.id
                  AND policy.revision = (
                      SELECT MAX(latest.revision)
                      FROM task_automation_policy latest
                      WHERE latest.task_id = task.id)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM remote_readiness_assistance_v273 assistance
                      WHERE assistance.task_id = task.id
                        AND assistance.task_epoch = task.epoch
                        AND assistance.remote_stage_id = owner.id
                        AND assistance.stage_generation = owner.generation
                        AND assistance.remote_pr_snapshot_id = snapshot.id
                        AND assistance.automation_policy_id = policy.id
                        AND assistance.status IN (
                            'REQUESTED', 'CLAIMED', 'SUCCEEDED', 'FAILED',
                            'INDETERMINATE'))
                ORDER BY readiness.observed_at_ms DESC, readiness.id DESC
                LIMIT 1
                """, (rs, row) -> availability(rs), taskId, stageId)
                .stream().findFirst();
    }

    public Action authorize(AuthorizationRequest request, Instant now)
    {
        requireNonNull(request, "request is null");
        requireNonNull(now, "now is null");
        return requireNonNull(transactions.execute(ignored -> {
            Action duplicate = findByCommand(
                    request.taskId(), request.commandId()).orElse(null);
            if (duplicate != null) {
                requireSameCommand(duplicate, request);
                return duplicate;
            }
            Subject subject = requireSubject(request);
            String target = request.kind() == AssistanceKind.REQUEST_REVIEWER
                    ? reviewer(request.externalTarget()) : "MAINTAINER";
            String payload = request.kind() == AssistanceKind.REQUEST_REVIEWER
                    ? target : message(request.payload());
            String payloadDigest = digest(payload);
            String idempotencyKey = digest(String.join(":",
                    request.commandId(),
                    request.taskId(), Long.toString(request.taskEpoch()),
                    request.stageId(), Long.toString(request.stageGeneration()),
                    request.snapshotId(), request.policyId(),
                    request.kind().name(), target.toLowerCase(Locale.ROOT),
                    payloadDigest));
            Action semanticDuplicate = findByIdempotency(idempotencyKey)
                    .orElse(null);
            if (semanticDuplicate != null) {
                return semanticDuplicate;
            }

            String actionId = "readiness-assistance-" + UUID.randomUUID();
            String operationId = "readiness-assistance-operation-"
                    + digest(actionId);
            String ticketId = "readiness-assistance-ticket-"
                    + digest(actionId);
            jdbc.update("""
                    INSERT INTO remote_readiness_assistance_v273(
                        id, command_id, operation_id, task_id, task_epoch,
                        remote_stage_id, stage_generation,
                        remote_pr_binding_id, remote_pr_snapshot_id,
                        readiness_evidence_id, automation_policy_id, kind,
                        external_target, payload, payload_digest,
                        idempotency_key, remote_repository_id,
                        head_repository_id, remote_pr_number,
                        expected_head_sha, expected_base_sha,
                        semantic_attempt, status, attempt_count, attempt_limit,
                        authorized_by, authorized_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, 1, 'REQUESTED', 0, ?, ?, ?)
                    """, actionId, request.commandId(), operationId,
                    request.taskId(), request.taskEpoch(), request.stageId(),
                    request.stageGeneration(), subject.bindingId(),
                    request.snapshotId(), request.readinessId(),
                    request.policyId(), request.kind().name(), target, payload,
                    payloadDigest, idempotencyKey, subject.repositoryId(),
                    subject.headRepositoryId(), subject.pullRequestNumber(),
                    request.headSha(), request.baseSha(), ATTEMPT_LIMIT,
                    subject.viewerLogin(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch,
                        stage_id, stage_generation, attempt,
                        expected_head_sha, expected_base_sha,
                        status, created_at_ms)
                    VALUES (?, ?, 'APPLY_READINESS_ASSISTANCE',
                        'GITHUB_EFFECT', 'STAGE', ?,
                        'READINESS_ASSISTANCE_RESULT', 32, 0, 1, 0,
                        ?, ?, ?, ?, ?, ?, 1, ?, ?, 'REQUESTED', ?)
                    """, ticketId, operationId, request.stageId(),
                    subject.workspaceId(), subject.trunkId(), request.taskId(),
                    request.taskEpoch(), request.stageId(),
                    request.stageGeneration(), request.headSha(),
                    request.baseSha(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO remote_readiness_assistance_dispatch_v273(
                        assistance_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES (?, ?, ?, ?)
                    """, actionId, ticketId, operationId, now.toEpochMilli());
            wakes.enqueue(ticketId, now);
            return require(operationId);
        }), "Readiness assistance authorization returned null");
    }

    public Action require(String operationId)
    {
        requireText(operationId, "operationId");
        List<Action> rows = jdbc.query("""
                SELECT assistance.*
                FROM remote_readiness_assistance_v273 assistance
                JOIN remote_readiness_assistance_dispatch_v273 dispatch
                  ON dispatch.assistance_id = assistance.id
                WHERE assistance.operation_id = ?
                  AND dispatch.operation_id = assistance.operation_id
                """, (rs, row) -> action(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact readiness assistance operation is missing");
        }
        return rows.getFirst();
    }

    public Optional<DeliveryReceipt> findReceipt(String operationId)
    {
        return jdbc.query("""
                SELECT operation_id, raw_result_digest, acceptance,
                       evidence, recorded_at_ms
                FROM remote_readiness_assistance_receipt_v273
                WHERE operation_id = ?
                """, (rs, row) -> new DeliveryReceipt(
                        rs.getString("operation_id"),
                        rs.getString("raw_result_digest"),
                        rs.getString("acceptance"), rs.getString("evidence"),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                operationId).stream().findFirst();
    }

    public Action abandon(String operationId, String reason, Instant at)
    {
        requireText(operationId, "operationId");
        requireText(reason, "reason");
        requireNonNull(at, "at is null");
        int changed = jdbc.update("""
                UPDATE remote_readiness_assistance_v273
                SET status = 'ABANDONED', last_error = ?, completed_at_ms = ?
                WHERE operation_id = ?
                  AND status IN ('REQUESTED', 'FAILED', 'INDETERMINATE')
                """, reason, at.toEpochMilli(), operationId);
        Action action = require(operationId);
        if (changed != 1 && !"ABANDONED".equals(action.status())) {
            throw new IllegalStateException(
                    "Readiness assistance cannot accept a terminal result");
        }
        return action;
    }

    public void insertReceipt(DeliveryReceipt receipt)
    {
        jdbc.update("""
                INSERT INTO remote_readiness_assistance_receipt_v273(
                    operation_id, raw_result_digest, acceptance, evidence,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?)
                """, receipt.operationId(), receipt.rawResultDigest(),
                receipt.acceptance(), receipt.evidence(),
                receipt.recordedAt().toEpochMilli());
    }

    private Optional<Action> findByCommand(String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT * FROM remote_readiness_assistance_v273
                WHERE task_id = ? AND command_id = ?
                """, (rs, row) -> action(rs), taskId, commandId)
                .stream().findFirst();
    }

    private Optional<Action> findByIdempotency(String idempotencyKey)
    {
        return jdbc.query("""
                SELECT * FROM remote_readiness_assistance_v273
                WHERE idempotency_key = ?
                """, (rs, row) -> action(rs), idempotencyKey)
                .stream().findFirst();
    }

    private Subject requireSubject(AuthorizationRequest request)
    {
        List<Subject> rows = jdbc.query("""
                SELECT remote.remote_pr_binding_id AS binding_id,
                       binding.remote_repository_id AS repository_id,
                       binding.head_repository_id,
                       binding.remote_pr_number, trunk.workspace_id,
                       task.thread_id AS trunk_id, snapshot.viewer_login
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN remote_development_stage remote ON remote.stage_id = owner.id
                JOIN remote_pr_binding binding
                  ON binding.id = remote.remote_pr_binding_id
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                JOIN remote_readiness_evidence readiness
                  ON readiness.id = ?
                JOIN task_automation_policy policy ON policy.id = ?
                WHERE task.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE' AND task.epoch = ?
                  AND current.stage_id = ? AND current.stage_generation = ?
                  AND owner.id = ? AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.generation = ?
                  AND owner.checkpoint = 'READY_TO_MERGE'
                  AND owner.completed_at_ms IS NULL
                  AND remote.task_id = task.id
                  AND remote.generation = owner.generation
                  AND remote.accepted_snapshot_id = ?
                  AND remote.current_head_sha = ?
                  AND remote.current_base_sha = ?
                  AND binding.task_id = task.id
                  AND snapshot.task_id = task.id
                  AND snapshot.task_epoch = task.epoch
                  AND snapshot.remote_development_stage_id = owner.id
                  AND snapshot.stage_generation = owner.generation
                  AND snapshot.remote_pr_binding_id = binding.id
                  AND snapshot.head_sha = ? AND snapshot.base_sha = ?
                  AND snapshot.pr_state = 'OPEN'
                  AND snapshot.viewer_login IS NOT NULL
                  AND snapshot.viewer_can_merge = 0
                  AND readiness.task_id = task.id
                  AND readiness.task_epoch = task.epoch
                  AND readiness.remote_development_stage_id = owner.id
                  AND readiness.stage_generation = owner.generation
                  AND readiness.remote_pr_snapshot_id = snapshot.id
                  AND readiness.automation_policy_id = policy.id
                  AND readiness.head_sha = ? AND readiness.base_sha = ?
                  AND readiness.ready = 1
                  AND policy.task_id = task.id
                  AND policy.revision = (
                      SELECT MAX(latest.revision)
                      FROM task_automation_policy latest
                      WHERE latest.task_id = task.id)
                """, (rs, row) -> subject(rs), request.readinessId(),
                request.policyId(), request.taskId(), request.taskEpoch(),
                request.stageId(), request.stageGeneration(), request.stageId(),
                request.stageGeneration(), request.snapshotId(),
                request.headSha(), request.baseSha(), request.headSha(),
                request.baseSha(), request.headSha(), request.baseSha());
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Readiness assistance no longer matches current exact truth");
        }
        return rows.getFirst();
    }

    private static void requireSameCommand(
            Action action, AuthorizationRequest request)
    {
        String expectedTarget = request.kind() == AssistanceKind.REQUEST_REVIEWER
                ? reviewer(request.externalTarget()) : "MAINTAINER";
        String expectedPayload = request.kind() == AssistanceKind.REQUEST_REVIEWER
                ? expectedTarget : message(request.payload());
        if (!action.taskId().equals(request.taskId())
                || action.taskEpoch() != request.taskEpoch()
                || !action.stageId().equals(request.stageId())
                || action.stageGeneration() != request.stageGeneration()
                || !action.snapshotId().equals(request.snapshotId())
                || !action.readinessId().equals(request.readinessId())
                || !action.policyId().equals(request.policyId())
                || action.kind() != request.kind()
                || !action.externalTarget().equals(expectedTarget)
                || !action.payload().equals(expectedPayload)
                || !action.headSha().equals(request.headSha())
                || !action.baseSha().equals(request.baseSha())) {
            throw new IllegalStateException(
                    "Readiness assistance command id was reused with new input");
        }
    }

    private static Availability availability(ResultSet rs)
            throws SQLException
    {
        return new Availability(
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getString("snapshot_id"),
                rs.getString("readiness_id"), rs.getString("policy_id"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"),
                rs.getString("viewer_login"),
                List.of(AssistanceKind.POST_MAINTAINER_NUDGE,
                        AssistanceKind.REQUEST_REVIEWER));
    }

    private static Subject subject(ResultSet rs)
            throws SQLException
    {
        return new Subject(
                rs.getString("binding_id"), rs.getString("repository_id"),
                rs.getString("head_repository_id"),
                rs.getInt("remote_pr_number"), rs.getString("workspace_id"),
                rs.getString("trunk_id"), rs.getString("viewer_login"));
    }

    private static Action action(ResultSet rs)
            throws SQLException
    {
        return new Action(
                rs.getString("id"), rs.getString("command_id"),
                rs.getString("operation_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("remote_stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("remote_pr_snapshot_id"),
                rs.getString("readiness_evidence_id"),
                rs.getString("automation_policy_id"),
                AssistanceKind.valueOf(rs.getString("kind")),
                rs.getString("external_target"), rs.getString("payload"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getString("authorized_by"),
                Instant.ofEpochMilli(rs.getLong("authorized_at_ms")),
                rs.getString("external_effect_id"), rs.getString("evidence"));
    }

    private static String reviewer(String value)
    {
        String reviewer = requireText(value, "externalTarget").trim();
        if (!reviewer.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) {
            throw new IllegalArgumentException("reviewer is not a GitHub login");
        }
        return reviewer;
    }

    private static String message(String value)
    {
        String message = requireText(value, "payload").trim();
        if (message.length() > 10_000) {
            throw new IllegalArgumentException("maintainer nudge is too long");
        }
        return message;
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    public enum AssistanceKind
    {
        REQUEST_REVIEWER,
        POST_MAINTAINER_NUDGE
    }

    public record Availability(
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String snapshotId,
            String readinessId,
            String policyId,
            String headSha,
            String baseSha,
            String viewerLogin,
            List<AssistanceKind> actions)
    {
    }

    public record AuthorizationRequest(
            String commandId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String snapshotId,
            String readinessId,
            String policyId,
            String headSha,
            String baseSha,
            AssistanceKind kind,
            String externalTarget,
            String payload)
    {
        public AuthorizationRequest
        {
            requireText(commandId, "commandId");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(snapshotId, "snapshotId");
            requireText(readinessId, "readinessId");
            requireText(policyId, "policyId");
            requireText(headSha, "headSha");
            requireText(baseSha, "baseSha");
            requireNonNull(kind, "kind is null");
            if (taskEpoch < 1 || stageGeneration < 1) {
                throw new IllegalArgumentException(
                        "Readiness assistance owner fence is invalid");
            }
        }
    }

    public record Action(
            String id,
            String commandId,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String snapshotId,
            String readinessId,
            String policyId,
            AssistanceKind kind,
            String externalTarget,
            String payload,
            String headSha,
            String baseSha,
            String status,
            int attemptCount,
            String authorizedBy,
            Instant authorizedAt,
            String externalEffectId,
            String evidence)
    {
    }

    public record DeliveryReceipt(
            String operationId,
            String rawResultDigest,
            String acceptance,
            String evidence,
            Instant recordedAt)
    {
    }

    private record Subject(
            String bindingId,
            String repositoryId,
            String headRepositoryId,
            int pullRequestNumber,
            String workspaceId,
            String trunkId,
            String viewerLogin)
    {
    }
}
