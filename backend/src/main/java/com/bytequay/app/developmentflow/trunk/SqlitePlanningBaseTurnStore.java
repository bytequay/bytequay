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
package com.bytequay.app.developmentflow.trunk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact persistence view shared by planning execution and launch recovery. */
@Repository
public class SqlitePlanningBaseTurnStore
        implements PlanningBaseRefreshOperationHandler.Store
{
    private final JdbcTemplate jdbc;

    public SqlitePlanningBaseTurnStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Optional<String> currentBaseSha(String trunkId, String repositoryRoot)
    {
        requireText(trunkId, "trunkId");
        requireText(repositoryRoot, "repositoryRoot");
        return jdbc.query("""
                SELECT planning_base_sha
                FROM threads
                WHERE id = ? AND turn_version = 'V2'
                  AND planning_repo_root = ?
                  AND planning_base_sha IS NOT NULL
                """, (rs, row) -> rs.getString("planning_base_sha"),
                trunkId, repositoryRoot).stream().findFirst();
    }

    public RequestBase requestedBase(String planningOperationId)
    {
        requireText(planningOperationId, "planningOperationId");
        List<RequestBase> rows = jdbc.query("""
                SELECT repository_root, previous_base_sha
                FROM planning_base_refresh_operation
                WHERE id = ?
                """, (rs, row) -> new RequestBase(
                        rs.getString("repository_root"),
                        rs.getString("previous_base_sha")),
                planningOperationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Planning-base Operation is missing: " + planningOperationId);
        }
        return rows.getFirst();
    }

    @Override
    public PlanningBaseRefreshOperationHandler.OperationContext require(
            String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation.id, operation.operation_id,
                       operation.trunk_id, operation.workspace_id,
                       operation.semantic_attempt, operation.repository_root,
                       operation.previous_base_sha,
                       EXISTS (
                           SELECT 1 FROM thread_turn turn
                           WHERE turn.trunk_id = operation.trunk_id
                             AND turn.status IN (
                                'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
                           AS live_thread_turn,
                       EXISTS (
                           SELECT 1
                           FROM planning_base_refresh_operation preceding
                           WHERE preceding.trunk_id = operation.trunk_id
                             AND preceding.id <> operation.id
                             AND preceding.status IN ('REQUESTED', 'SUCCEEDED')
                             AND preceding.launched_thread_turn_id IS NULL
                             AND (preceding.requested_at_ms < operation.requested_at_ms
                               OR (preceding.requested_at_ms = operation.requested_at_ms
                                 AND preceding.id < operation.id)))
                           AS unlaunched_predecessor
                FROM planning_base_refresh_operation operation
                WHERE operation.operation_id = ?
                  AND operation.status = 'REQUESTED'
                """, (rs, row) ->
                        new PlanningBaseRefreshOperationHandler.OperationContext(
                                rs.getString("id"), rs.getString("operation_id"),
                                rs.getString("trunk_id"),
                                rs.getString("workspace_id"),
                                rs.getInt("semantic_attempt"),
                                rs.getString("repository_root"),
                                rs.getString("previous_base_sha"),
                                rs.getBoolean("live_thread_turn"),
                                rs.getBoolean("unlaunched_predecessor")),
                operationId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Planning-base Operation is not requested: " + operationId));
    }

    public Optional<LaunchCandidate> findLaunchCandidate(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation.id, operation.operation_id,
                       operation.trunk_id, operation.workspace_id,
                       operation.actor, operation.launch_command_id,
                       operation.reserved_thread_turn_id,
                       operation.launch_intent, operation.launch_intent_digest,
                       operation.previous_base_sha,
                       operation.result_worktree_path,
                       operation.result_base_sha, operation.completed_at_ms
                FROM planning_base_refresh_operation operation
                JOIN dispatch_ticket ticket
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'SUCCEEDED'
                  AND operation.launched_thread_turn_id IS NULL
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                """, (rs, row) -> new LaunchCandidate(
                        rs.getString("id"), rs.getString("operation_id"),
                        rs.getString("trunk_id"), rs.getString("workspace_id"),
                        rs.getString("actor"),
                        rs.getString("launch_command_id"),
                        rs.getString("reserved_thread_turn_id"),
                        rs.getString("launch_intent"),
                        rs.getString("launch_intent_digest"),
                        rs.getString("previous_base_sha"),
                        rs.getString("result_worktree_path"),
                        rs.getString("result_base_sha"),
                        Instant.ofEpochMilli(rs.getLong("completed_at_ms"))),
                operationId).stream().findFirst();
    }

    public void markLaunched(
            LaunchCandidate candidate, String turnId, Instant launchedAt)
    {
        requireNonNull(candidate, "candidate is null");
        requireText(turnId, "turnId");
        requireNonNull(launchedAt, "launchedAt is null");
        int changed = jdbc.update("""
                UPDATE planning_base_refresh_operation
                SET launched_thread_turn_id = ?, launched_at_ms = ?
                WHERE id = ? AND operation_id = ? AND status = 'SUCCEEDED'
                  AND launched_thread_turn_id IS NULL
                  AND reserved_thread_turn_id = ?
                  AND EXISTS (
                      SELECT 1 FROM thread_turn turn
                      WHERE turn.id = ?
                        AND turn.trunk_id = planning_base_refresh_operation.trunk_id
                        AND turn.planning_operation_id = planning_base_refresh_operation.id
                        AND turn.expected_base_sha = planning_base_refresh_operation.result_base_sha)
                """, turnId, launchedAt.toEpochMilli(),
                candidate.planningOperationId(), candidate.operationId(), turnId,
                turnId);
        if (changed == 0) {
            String existing = jdbc.queryForObject("""
                    SELECT launched_thread_turn_id
                    FROM planning_base_refresh_operation
                    WHERE id = ?
                    """, String.class, candidate.planningOperationId());
            if (!turnId.equals(existing)) {
                throw new IllegalStateException(
                        "Planning-base Operation launched another ThreadTurn");
            }
        }
    }

    public List<String> readyOperationIds(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT operation.operation_id
                FROM planning_base_refresh_operation operation
                JOIN dispatch_ticket ticket
                  ON ticket.id = operation.dispatch_ticket_id
                WHERE operation.status = 'SUCCEEDED'
                  AND operation.launched_thread_turn_id IS NULL
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                ORDER BY operation.requested_at_ms, operation.id
                LIMIT ?
                """, (rs, row) -> rs.getString("operation_id"), limit);
    }

    public record LaunchCandidate(
            String planningOperationId,
            String operationId,
            String trunkId,
            String workspaceId,
            String actor,
            String launchCommandId,
            String reservedThreadTurnId,
            String launchIntent,
            String launchIntentDigest,
            String previousBaseSha,
            String worktreePath,
            String baseSha,
            Instant completedAt)
    {
        public LaunchCandidate
        {
            requireText(planningOperationId, "planningOperationId");
            requireText(operationId, "operationId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(actor, "actor");
            requireText(launchCommandId, "launchCommandId");
            requireText(reservedThreadTurnId, "reservedThreadTurnId");
            requireText(launchIntent, "launchIntent");
            requireText(launchIntentDigest, "launchIntentDigest");
            requireText(worktreePath, "worktreePath");
            requireText(baseSha, "baseSha");
            requireNonNull(completedAt, "completedAt is null");
        }
    }

    public record RequestBase(String repositoryRoot, String previousBaseSha)
    {
        public RequestBase
        {
            requireText(repositoryRoot, "repositoryRoot");
            if (previousBaseSha != null && previousBaseSha.isBlank()) {
                throw new IllegalArgumentException(
                        "previousBaseSha must not be blank");
            }
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
