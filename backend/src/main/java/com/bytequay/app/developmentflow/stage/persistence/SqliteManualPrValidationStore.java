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

import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable owner store for an exact user-requested V2 local test run. */
@Component
public final class SqliteManualPrValidationStore
{
    private final JdbcTemplate jdbc;

    public SqliteManualPrValidationStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public RequestContext requireRequestContext(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query("""
                SELECT pr.id AS pr_id, task.id AS task_id, task.epoch AS task_epoch,
                       task.thread_id AS trunk_id, thread.workspace_id AS workspace_id,
                       identity.worktree_path, code.code_fingerprint,
                       code.head_sha, code.base_sha
                FROM pr
                JOIN tasks task ON task.id = pr.task_id
                JOIN threads thread ON thread.id = task.thread_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE pr.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                """, (rs, row) -> new RequestContext(
                rs.getString("pr_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("workspace_id"),
                rs.getString("trunk_id"), rs.getString("worktree_path"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha")), prId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "PR " + prId + " is not owned by an active V2 Task"));
    }

    public Operation request(
            String commandId, RequestContext context, Instant now)
    {
        requireText(commandId, "commandId");
        requireNonNull(context, "context is null");
        requireNonNull(now, "now is null");
        TaskCommandExecutor.requireCurrent(context.taskId());

        Optional<Operation> replay = findByCommand(context.taskId(), commandId);
        if (replay.isPresent()) {
            Operation operation = replay.orElseThrow();
            if (!operation.prId().equals(context.prId())) {
                throw new IllegalStateException(
                        "Idempotency key was already used for another PR");
            }
            return operation;
        }

        RequestContext current = requireRequestContext(context.prId());
        if (!current.equals(context)) {
            throw new IllegalStateException(
                    "Manual validation owner changed while the command was admitted");
        }
        String operationId = id(
                "manual-pr-validation-operation", context.taskId() + ":" + commandId);
        String ticketId = id("manual-pr-validation-ticket", operationId);
        jdbc.update("""
                INSERT INTO manual_pr_validation_operation(
                    id, command_id, pr_id, task_id, task_epoch, worktree_path,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, operationId, commandId, context.prId(), context.taskId(),
                context.taskEpoch(), context.worktreePath(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                now.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'VALIDATE_PR_MANUALLY', 'VALIDATION',
                    'TASK', ?, 'MANUAL_PR_VALIDATION_RESULT', 4,
                    0, 1, 0, ?, ?, ?, ?, NULL, NULL, 1, ?, ?, ?,
                    'REQUESTED', ?)
                """, ticketId, operationId, context.taskId(),
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.codeFingerprint(), context.headSha(),
                context.baseSha(), now.toEpochMilli());
        return requireOperation(operationId);
    }

    public ExecutionContext requireExecutionContext(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation.*,
                       EXISTS (
                           SELECT 1 FROM pr
                           JOIN tasks task ON task.id = pr.task_id
                           JOIN task_code_identity identity ON identity.task_id = task.id
                           JOIN task_current_code_subject_v230 code
                             ON code.task_id = task.id
                           WHERE pr.id = operation.pr_id
                             AND task.id = operation.task_id
                             AND task.workflow_version = 'V2'
                             AND task.lifecycle_state = 'ACTIVE'
                             AND task.epoch = operation.task_epoch
                             AND identity.worktree_path = operation.worktree_path
                             AND code.code_fingerprint = operation.code_fingerprint
                             AND code.head_sha = operation.expected_head_sha
                             AND code.base_sha = operation.expected_base_sha
                       ) AS owner_current
                FROM manual_pr_validation_operation operation
                WHERE operation.id = ?
                """, (rs, row) -> new ExecutionContext(
                rs.getString("id"), rs.getString("pr_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("worktree_path"), rs.getString("code_fingerprint"),
                rs.getString("expected_head_sha"), rs.getString("expected_base_sha"),
                rs.getInt("owner_current") != 0), operationId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No Manual PR validation operation " + operationId));
    }

    public Optional<Operation> findByCommand(String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT * FROM manual_pr_validation_operation
                WHERE task_id = ? AND command_id = ?
                """, (rs, row) -> operation(
                rs.getString("id"), rs.getString("command_id"),
                rs.getString("pr_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("status"),
                rs.getString("result_json"), rs.getString("error_message")),
                taskId, commandId).stream().findFirst();
    }

    public Operation requireOperation(String operationId)
    {
        return jdbc.query("""
                SELECT * FROM manual_pr_validation_operation WHERE id = ?
                """, (rs, row) -> operation(
                rs.getString("id"), rs.getString("command_id"),
                rs.getString("pr_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("status"),
                rs.getString("result_json"), rs.getString("error_message")),
                operationId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "No Manual PR validation operation " + operationId));
    }

    public Operation finish(
            String operationId, String status, String resultJson,
            String error, Instant now)
    {
        requireText(status, "status");
        requireNonNull(resultJson, "resultJson is null");
        int changed = jdbc.update("""
                UPDATE manual_pr_validation_operation
                SET status = ?, result_json = ?, error_message = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, status, resultJson, error, now.toEpochMilli(), operationId);
        Operation operation = requireOperation(operationId);
        if (changed == 0 && (!operation.status().name().equals(status)
                || !resultJson.equals(operation.resultJson()))) {
            throw new IllegalStateException(
                    "Manual PR validation was already completed differently");
        }
        return operation;
    }

    private static Operation operation(
            String id, String commandId, String prId, String taskId,
            long taskEpoch, String status, String resultJson, String error)
    {
        return new Operation(
                id, commandId, prId, taskId, taskEpoch,
                Status.valueOf(status), resultJson, error);
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record RequestContext(
            String prId, String taskId, long taskEpoch, String workspaceId,
            String trunkId, String worktreePath, String codeFingerprint,
            String headSha, String baseSha) {}

    public record ExecutionContext(
            String operationId, String prId, String taskId, long taskEpoch,
            String worktreePath, String codeFingerprint, String headSha,
            String baseSha, boolean current) {}

    public record Operation(
            String id, String commandId, String prId, String taskId,
            long taskEpoch, Status status, String resultJson, String error)
    {
        public boolean terminal()
        {
            return status != Status.REQUESTED;
        }
    }

    public enum Status
    {
        REQUESTED,
        COMPLETED,
        FAILED,
        CANCELED,
        SUPERSEDED
    }
}
