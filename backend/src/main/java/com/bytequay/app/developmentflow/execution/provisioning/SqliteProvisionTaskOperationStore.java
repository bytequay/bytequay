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
package com.bytequay.app.developmentflow.execution.provisioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact SQLite loader for the immutable V2 Task provisioning graph. */
@Repository
public class SqliteProvisionTaskOperationStore
        implements ProvisionTaskOperationHandler.OperationStore
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SqliteProvisionTaskOperationStore(JdbcTemplate jdbc, ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public ProvisionTaskOperationHandler.ProvisionRequest requireByOperationId(
            String operationId)
    {
        requireText(operationId, "operationId");
        List<ProvisionTaskOperationHandler.ProvisionRequest> rows = jdbc.query("""
                SELECT
                    operation.id AS operation_row_id,
                    task.id AS task_id,
                    task.thread_id AS trunk_id,
                    trunk.workspace_id AS workspace_id,
                    task.workflow_version,
                    task.lifecycle_state AS task_lifecycle,
                    task.epoch AS current_task_epoch,
                    task.assignment_id AS task_assignment_id,
                    operation.assignment_id AS operation_assignment_id,
                    operation.operation_id,
                    operation.semantic_attempt,
                    operation.repository_id,
                    operation.base_source,
                    operation.base_repository_id,
                    operation.base_ref,
                    operation.expected_base_sha,
                    operation.expected_remote_head_sha,
                    operation.requested_branch_name,
                    operation.requested_worktree_path,
                    operation.status AS operation_status,
                    context.assignment_id AS context_assignment_id,
                    context.repository_id AS context_repository_id,
                    context.upstream_repository_id AS context_upstream_repository_id,
                    context.publish_repository_id AS context_publish_repository_id,
                    context.base_source AS context_base_source,
                    context.base_repository_id AS context_base_repository_id,
                    context.base_ref AS context_base_ref,
                    context.planning_base_sha AS context_planning_base_sha,
                    context.assignment_base_sha AS context_assignment_base_sha,
                    context.assignment_head_sha AS context_assignment_head_sha,
                    target.repository_id AS target_repository_id,
                    target.publish_repository_id AS target_publish_repository_id,
                    target.branch_name AS target_branch_name,
                    target.worktree_path AS target_worktree_path,
                    assignment.kind AS assignment_kind,
                    assignment.repository_id AS assignment_repository_id,
                    assignment.planning_base_sha AS assignment_planning_base_sha,
                    assignment.base_repository_id AS assignment_base_repository_id,
                    assignment.head_repository_id AS assignment_head_repository_id,
                    assignment.base_ref AS assignment_base_ref,
                    assignment.head_ref AS assignment_head_ref,
                    assignment.remote_base_sha AS assignment_remote_base_sha,
                    assignment.remote_head_sha AS assignment_remote_head_sha,
                    watched.local_clone_path
                FROM provision_task_operation operation
                JOIN tasks task ON task.id = operation.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN workspace_repos workspace_repository
                  ON workspace_repository.workspace_id = trunk.workspace_id
                 AND lower(workspace_repository.repo_full_name)
                    = lower(context.repository_id)
                JOIN task_provision_target target ON target.task_id = task.id
                JOIN task_assignment assignment ON assignment.id = context.assignment_id
                LEFT JOIN watched_repos watched
                  ON lower(watched.owner || '/' || watched.repo)
                    = lower(context.repository_id)
                WHERE operation.operation_id = ?
                """, (result, row) -> new ProvisionTaskOperationHandler.ProvisionRequest(
                        result.getString("operation_row_id"),
                        result.getString("task_id"),
                        result.getString("trunk_id"),
                        result.getString("workspace_id"),
                        result.getString("workflow_version"),
                        result.getString("task_lifecycle"),
                        result.getLong("current_task_epoch"),
                        result.getString("task_assignment_id"),
                        result.getString("operation_assignment_id"),
                        result.getString("operation_id"),
                        result.getInt("semantic_attempt"),
                        result.getString("repository_id"),
                        ProvisionTaskOperationHandler.BaseSource.valueOf(
                                result.getString("base_source")),
                        result.getString("base_repository_id"),
                        result.getString("base_ref"),
                        result.getString("expected_base_sha"),
                        result.getString("expected_remote_head_sha"),
                        result.getString("requested_branch_name"),
                        result.getString("requested_worktree_path"),
                        result.getString("operation_status"),
                        result.getString("context_assignment_id"),
                        result.getString("context_repository_id"),
                        result.getString("context_upstream_repository_id"),
                        result.getString("context_publish_repository_id"),
                        ProvisionTaskOperationHandler.BaseSource.valueOf(
                                result.getString("context_base_source")),
                        result.getString("context_base_repository_id"),
                        result.getString("context_base_ref"),
                        result.getString("context_planning_base_sha"),
                        result.getString("context_assignment_base_sha"),
                        result.getString("context_assignment_head_sha"),
                        result.getString("target_repository_id"),
                        result.getString("target_publish_repository_id"),
                        result.getString("target_branch_name"),
                        result.getString("target_worktree_path"),
                        result.getString("assignment_kind"),
                        result.getString("assignment_repository_id"),
                        result.getString("assignment_planning_base_sha"),
                        result.getString("assignment_base_repository_id"),
                        result.getString("assignment_head_repository_id"),
                        result.getString("assignment_base_ref"),
                        result.getString("assignment_head_ref"),
                        result.getString("assignment_remote_base_sha"),
                        result.getString("assignment_remote_head_sha"),
                        result.getString("local_clone_path")),
                operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "expected one exact ProvisionTaskOperation graph for "
                            + operationId + ", found " + rows.size());
        }
        return rows.getFirst();
    }

    @Override
    public Optional<ProvisionTaskOperationHandler.ProvisionSourceProof>
            findPriorSourceProof(String operationId)
    {
        requireText(operationId, "operationId");
        List<SourceLog> rows = jdbc.query("""
                SELECT execution.id AS execution_id,
                    execution.infrastructure_attempt,
                    log.payload
                FROM dispatch_ticket ticket
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                JOIN agent_execution_log log
                  ON log.execution_id = execution.id
                WHERE ticket.operation_id = ?
                  AND ticket.operation_kind = ?
                  AND execution.infrastructure_attempt
                    < ticket.infrastructure_attempts
                  AND log.seq = ?
                ORDER BY execution.infrastructure_attempt DESC
                """, (result, row) -> new SourceLog(
                        result.getString("execution_id"),
                        result.getInt("infrastructure_attempt"),
                        result.getString("payload")),
                operationId,
                ProvisionTaskOperationHandler.OPERATION_KIND,
                ProvisionTaskOperationHandler.SOURCE_PROOF_LOG_SEQUENCE);

        ProvisionTaskOperationHandler.ProvisionSourceProof accepted = null;
        for (SourceLog row : rows) {
            ProvisionTaskOperationHandler.ProvisionSourceEvidence evidence;
            try {
                evidence = json.readValue(
                        row.payload(),
                        ProvisionTaskOperationHandler.ProvisionSourceEvidence.class);
            }
            catch (JsonProcessingException | RuntimeException ignored) {
                // This log sequence predates or does not belong to the typed
                // provisioning proof contract. It cannot authorize adoption.
                continue;
            }
            if (!ProvisionTaskOperationHandler.SOURCE_PROOF_SCHEMA.equals(
                        evidence.schema())
                    || !row.executionId().equals(evidence.executionId())
                    || !operationId.equals(evidence.operationId())) {
                continue;
            }
            ProvisionTaskOperationHandler.ProvisionSourceProof candidate =
                    new ProvisionTaskOperationHandler.ProvisionSourceProof(
                            row.executionId(), row.infrastructureAttempt(), evidence);
            if (accepted != null
                    && !accepted.evidence().sameSourceAs(candidate.evidence())) {
                throw new IllegalStateException(
                        "conflicting prior provisioning source proofs for "
                                + operationId);
            }
            if (accepted == null) {
                accepted = candidate;
            }
        }
        return Optional.ofNullable(accepted);
    }

    private record SourceLog(
            String executionId,
            int infrastructureAttempt,
            String payload) {}

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
