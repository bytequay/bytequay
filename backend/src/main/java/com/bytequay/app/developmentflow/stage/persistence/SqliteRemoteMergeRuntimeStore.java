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

import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.SqliteMergeOperationStore;
import com.bytequay.app.developmentflow.stage.RemoteDevelopmentStageManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Creates exact merge authority/ticket bundles and reads terminal handoff input. */
@Repository
public class SqliteRemoteMergeRuntimeStore
{
    private final JdbcTemplate jdbc;

    public SqliteRemoteMergeRuntimeStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public StartContext requireStartContext(
            String taskId, String stageId, String readinessEvidenceId)
    {
        List<StartContext> found = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                    trunk.workspace_id, task.epoch AS task_epoch,
                    task.aggregate_version AS task_version,
                    stage.id AS stage_id, stage.generation AS stage_generation,
                    stage.version AS stage_version, stage.checkpoint,
                    readiness.id AS readiness_evidence_id,
                    snapshot.observation_revision,
                    readiness.automation_policy_id,
                    readiness.head_sha, readiness.base_sha,
                    readiness.merge_queue_capability,
                    policy.max_merge_queue_reenqueues
                FROM remote_readiness_evidence readiness
                JOIN remote_development_stage remote
                  ON remote.stage_id = readiness.remote_development_stage_id
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = readiness.remote_pr_snapshot_id
                JOIN task_automation_policy policy
                  ON policy.id = readiness.automation_policy_id
                JOIN stage stage ON stage.id = remote.stage_id
                JOIN tasks task ON task.id = remote.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                WHERE task.id = ? AND stage.id = ? AND readiness.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = readiness.task_epoch
                  AND current.stage_id = stage.id
                  AND current.stage_generation = stage.generation
                  AND stage.kind = 'REMOTE_DEVELOPMENT'
                  AND stage.checkpoint = 'READY_TO_MERGE'
                  AND stage.completed_at_ms IS NULL
                  AND readiness.task_id = task.id
                  AND remote.generation = readiness.stage_generation
                  AND remote.accepted_snapshot_id = readiness.remote_pr_snapshot_id
                  AND remote.current_head_sha = readiness.head_sha
                  AND remote.current_base_sha = readiness.base_sha
                  AND policy.task_id = task.id
                  AND readiness.ready = 1
                  AND readiness.merge_queue_capability <> 'UNKNOWN'
                  AND policy.revision = (
                      SELECT MAX(current_policy.revision)
                      FROM task_automation_policy current_policy
                      WHERE current_policy.task_id = task.id)
                """, (rs, row) -> mapStartContext(rs),
                taskId, stageId, readinessEvidenceId);
        if (found.size() != 1) {
            throw new IllegalStateException(
                    "fresh exact-head readiness is not current");
        }
        return found.getFirst();
    }

    public Optional<StartReceipt> findStart(String authorizationId)
    {
        requireText(authorizationId, "authorizationId");
        return jdbc.query("""
                SELECT authorization.id AS authorization_id,
                    authorization.readiness_evidence_id,
                    authorization.authority_kind, authorization.actor_id,
                    operation.id AS merge_operation_id,
                    operation.operation_id, ticket.id AS ticket_id,
                    operation.task_id, operation.remote_development_stage_id AS stage_id,
                    operation.task_epoch, operation.stage_generation,
                    operation.semantic_attempt, operation.attempt_limit,
                    operation.head_sha, operation.merge_method,
                    operation.base_sha, operation.mode
                FROM remote_merge_authorization authorization
                JOIN remote_merge_operation operation
                  ON operation.merge_authorization_id = authorization.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE authorization.id = ?
                """, (rs, row) -> mapStartReceipt(rs), authorizationId)
                .stream().findFirst();
    }

    public StartReceipt insertStart(StartRequest request, StartContext context)
    {
        requireNonNull(request, "request is null");
        requireNonNull(context, "context is null");
        requireTransaction();
        MergeMode mode = context.mode();
        String mergeOperationId = SqliteMergeOperationStore.id(
                "merge-operation", request.operationId());
        String actor = request.authorityKind()
                == AuthorityKind.MANUAL ? request.actor() : null;
        long at = request.requestedAt().toEpochMilli();
        jdbc.update("""
                INSERT INTO remote_merge_authorization(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, readiness_evidence_id,
                    automation_policy_id, head_sha, base_sha, authority_kind,
                    actor_id, status, authorized_at_ms, merge_method)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, request.authorizationId(), context.stageId(),
                context.taskId(), context.taskEpoch(), context.stageGeneration(),
                context.readinessEvidenceId(), context.automationPolicyId(),
                context.headSha(), context.baseSha(), request.authorityKind().name(),
                actor, at, request.mergeMethod());
        jdbc.update("""
                INSERT INTO remote_merge_operation(
                    id, merge_authorization_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, head_sha, base_sha, mode,
                    merge_queue_capability, status, attempt_limit,
                    max_queue_reenqueues, requested_at_ms, merge_method)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?)
                """, mergeOperationId, request.authorizationId(), context.stageId(),
                context.taskId(), context.taskEpoch(), context.stageGeneration(),
                request.operationId(), context.headSha(), context.baseSha(),
                mode.name(), context.mergeQueueCapability(), request.attemptLimit(),
                mode == MergeMode.MERGE_QUEUE
                        ? context.maxMergeQueueReenqueues() : 0,
                at, request.mergeMethod());
        jdbc.update("""
                UPDATE remote_merge_authorization
                SET status = 'CONSUMED', terminal_at_ms = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, at, request.authorizationId());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'MERGE_REMOTE_PULL_REQUEST', 'MERGE',
                    'STAGE', ?, 'REMOTE_MERGE_RESULT', 128,
                    0, 1, 0, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'REQUESTED', ?)
                """, request.ticketId(), request.operationId(), context.stageId(),
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.headSha(), context.baseSha(), at);
        return new StartReceipt(
                request.authorizationId(), context.readinessEvidenceId(),
                request.authorityKind(), actor,
                mergeOperationId, request.operationId(), request.ticketId(),
                context.taskId(), context.stageId(), context.taskEpoch(),
                context.stageGeneration(), 1, request.attemptLimit(), context.headSha(),
                context.baseSha(), mode, request.mergeMethod());
    }

    public Optional<TerminalContext> findTerminalContext(String snapshotId)
    {
        requireText(snapshotId, "snapshotId");
        return jdbc.query("""
                SELECT snapshot.id AS snapshot_id, snapshot.task_id,
                    snapshot.task_epoch,
                    snapshot.remote_development_stage_id AS stage_id,
                    snapshot.stage_generation, snapshot.observation_revision,
                    snapshot.head_sha, snapshot.base_sha, snapshot.pr_state,
                    task.aggregate_version AS task_version,
                    stage.version AS stage_version,
                    COALESCE((SELECT MAX(cleanup.generation) + 1
                        FROM stage cleanup
                        WHERE cleanup.task_id = snapshot.task_id
                          AND cleanup.kind = 'CLEANUP'), 1) AS cleanup_generation,
                    terminal.id AS terminal_observation_id
                FROM remote_pr_snapshot snapshot
                JOIN remote_development_stage remote
                  ON remote.stage_id = snapshot.remote_development_stage_id
                JOIN tasks task ON task.id = snapshot.task_id
                JOIN stage stage ON stage.id = snapshot.remote_development_stage_id
                LEFT JOIN remote_terminal_observation terminal
                  ON terminal.remote_pr_snapshot_id = snapshot.id
                WHERE snapshot.id = ?
                  AND snapshot.pr_state IN ('MERGED', 'CLOSED')
                  AND remote.accepted_snapshot_id = snapshot.id
                  AND remote.current_head_sha = snapshot.head_sha
                  AND remote.current_base_sha = snapshot.base_sha
                """, (rs, row) -> new TerminalContext(
                        rs.getString("snapshot_id"), rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getString("stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getLong("observation_revision"),
                        rs.getString("head_sha"), rs.getString("base_sha"),
                        RemoteDevelopmentStageManager.TerminalOutcome.valueOf(
                                rs.getString("pr_state")),
                        rs.getLong("task_version"), rs.getLong("stage_version"),
                        rs.getLong("cleanup_generation"),
                        rs.getString("terminal_observation_id")), snapshotId)
                .stream().findFirst();
    }

    public Optional<String> findLiveMergeOperationId(
            String stageId, String headSha, String baseSha)
    {
        return jdbc.query("""
                SELECT operation_id FROM remote_merge_operation
                WHERE remote_development_stage_id = ?
                  AND head_sha = ? AND base_sha = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
                """, (rs, row) -> rs.getString("operation_id"),
                stageId, headSha, baseSha).stream().findFirst();
    }

    public List<String> findLiveMergeOperationIds(String stageId)
    {
        requireText(stageId, "stageId");
        return jdbc.query("""
                SELECT operation_id FROM remote_merge_operation
                WHERE remote_development_stage_id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELED')
                ORDER BY requested_at_ms, id
                """, (rs, row) -> rs.getString("operation_id"), stageId);
    }

    private static StartContext mapStartContext(ResultSet rs)
            throws SQLException
    {
        return new StartContext(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getLong("task_version"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("readiness_evidence_id"),
                rs.getLong("observation_revision"),
                rs.getString("automation_policy_id"), rs.getString("head_sha"),
                rs.getString("base_sha"),
                rs.getString("merge_queue_capability"),
                rs.getInt("max_merge_queue_reenqueues"));
    }

    private static StartReceipt mapStartReceipt(ResultSet rs)
            throws SQLException
    {
        return new StartReceipt(
                rs.getString("authorization_id"),
                rs.getString("readiness_evidence_id"),
                AuthorityKind.valueOf(rs.getString("authority_kind")),
                rs.getString("actor_id"),
                rs.getString("merge_operation_id"),
                rs.getString("operation_id"), rs.getString("ticket_id"),
                rs.getString("task_id"), rs.getString("stage_id"),
                rs.getLong("task_epoch"), rs.getLong("stage_generation"),
                rs.getInt("semantic_attempt"), rs.getInt("attempt_limit"),
                rs.getString("head_sha"),
                rs.getString("base_sha"),
                MergeMode.valueOf(rs.getString("mode")),
                rs.getString("merge_method"));
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Remote merge write requires a Task command");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum AuthorityKind
    {
        MANUAL,
        AUTO_MERGE_POLICY
    }

    public record StartContext(
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            long taskVersion,
            String stageId,
            long stageGeneration,
            long stageVersion,
            String readinessEvidenceId,
            long observationRevision,
            String automationPolicyId,
            String headSha,
            String baseSha,
            String mergeQueueCapability,
            int maxMergeQueueReenqueues)
    {
        public MergeMode mode()
        {
            return switch (mergeQueueCapability) {
                case "SUPPORTED" -> MergeMode.MERGE_QUEUE;
                case "UNSUPPORTED" -> MergeMode.DIRECT;
                default -> throw new IllegalStateException(
                        "unknown queue capability cannot authorize merge");
            };
        }
    }

    public record StartRequest(
            String authorizationId,
            String operationId,
            String ticketId,
            AuthorityKind authorityKind,
            String actor,
            String mergeMethod,
            int attemptLimit,
            Instant requestedAt)
    {
        public StartRequest
        {
            requireText(authorizationId, "authorizationId");
            requireText(operationId, "operationId");
            requireText(ticketId, "ticketId");
            requireNonNull(authorityKind, "authorityKind is null");
            requireText(actor, "actor");
            requireText(mergeMethod, "mergeMethod");
            if (!List.of("merge", "squash", "rebase").contains(mergeMethod)) {
                throw new IllegalArgumentException("unsupported mergeMethod");
            }
            requireNonNull(requestedAt, "requestedAt is null");
            if (attemptLimit < 1) {
                throw new IllegalArgumentException("attemptLimit must be positive");
            }
        }
    }

    public record StartReceipt(
            String authorizationId,
            String readinessEvidenceId,
            AuthorityKind authorityKind,
            String actor,
            String mergeOperationId,
            String operationId,
            String ticketId,
            String taskId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            int semanticAttempt,
            int attemptLimit,
            String headSha,
            String baseSha,
            MergeMode mode,
            String mergeMethod) {}

    public record TerminalContext(
            String snapshotId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            long observationRevision,
            String headSha,
            String baseSha,
            RemoteDevelopmentStageManager.TerminalOutcome outcome,
            long taskVersion,
            long stageVersion,
            long cleanupGeneration,
            String terminalObservationId)
    {
        public boolean accepted()
        {
            return terminalObservationId != null;
        }
    }
}
