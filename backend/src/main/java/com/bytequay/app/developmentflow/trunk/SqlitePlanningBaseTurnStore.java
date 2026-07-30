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

import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
                           JOIN trunk_planning_base_request_receipt preceding_request
                             ON preceding_request.planning_operation_id = preceding.id
                           JOIN trunk_planning_base_request_receipt request
                             ON request.planning_operation_id = operation.id
                           WHERE preceding.trunk_id = operation.trunk_id
                             AND preceding.id <> operation.id
                             AND preceding.status IN ('REQUESTED', 'SUCCEEDED')
                             AND preceding.launch_disposition = 'PENDING'
                             AND preceding_request.returned_trunk_version
                                   < request.returned_trunk_version)
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
                  AND operation.launch_disposition = 'PENDING'
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

    public List<ConversationMessage> conversation(String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT message.role, message.body
                FROM thread_message message
                JOIN thread_turn turn ON turn.id = message.turn_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                WHERE turn.trunk_id = ?
                  AND turn.purpose = 'TRUNK_CONVERSATION'
                ORDER BY request.returned_trunk_version, message.seq
                """, (rs, row) -> new ConversationMessage(
                rs.getString("role"), rs.getString("body")), trunkId);
    }

    public List<AgentTurnProviderSession.ImageAttachment> conversationAttachments(
            String trunkId)
    {
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT attachment.content_ref, attachment.media_type,
                       attachment.digest
                FROM thread_attachment attachment
                JOIN thread_turn turn ON turn.id = attachment.turn_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                WHERE turn.trunk_id = ?
                  AND turn.purpose = 'TRUNK_CONVERSATION'
                  AND attachment.kind = 'image'
                ORDER BY request.returned_trunk_version, attachment.id
                """, (rs, row) -> new AgentTurnProviderSession.ImageAttachment(
                rs.getString("content_ref"), rs.getString("media_type"),
                rs.getString("digest")), trunkId);
    }

    /** The latest Turn must be the successful predecessor and have no live consumer. */
    public Optional<CliSession> latestSuccessfulCliSession(
            String trunkId, String provider, String model, String workingDirectory)
    {
        requireText(trunkId, "trunkId");
        requireText(provider, "provider");
        requireText(model, "model");
        requireText(workingDirectory, "workingDirectory");
        return jdbc.query("""
                SELECT execution.provider_session_id,
                       json_extract(json_extract(execution.raw_result,
                           '$.payloadJson'),
                           '$.providerCumulativeInputTokens')
                           AS cumulative_input_tokens,
                       json_extract(json_extract(execution.raw_result,
                           '$.payloadJson'),
                           '$.providerCumulativeOutputTokens')
                           AS cumulative_output_tokens
                FROM thread_turn turn
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN trunk_thread_turn_result_receipt result
                  ON result.turn_id = turn.id
                 AND result.acceptance = 'ACCEPTED'
                 AND result.terminal_status = 'SUCCEEDED'
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'THREAD_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                 AND ticket.status = 'SUCCEEDED'
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                 AND execution.status = 'SUCCEEDED'
                 AND execution.provider_session_id IS NOT NULL
                 AND execution.provider = json_extract(
                     turn.launch_input, '$.provider')
                WHERE turn.trunk_id = ? AND turn.status = 'SUCCEEDED'
                  AND turn.purpose = 'TRUNK_CONVERSATION'
                  AND json_extract(turn.launch_input, '$.transport') = 'CLI'
                  AND json_extract(turn.launch_input, '$.provider') = ?
                  AND json_extract(turn.launch_input, '$.model') = ?
                  AND json_extract(turn.launch_input, '$.workingDirectory') = ?
                  AND json_extract(
                      turn.launch_input, '$.toolEndpoint.profile')
                        = 'TRUNK_CONTROL_READ_ONLY'
                  AND (? <> 'codex' OR (
                      json_type(json_extract(execution.raw_result,
                          '$.payloadJson'),
                          '$.providerCumulativeInputTokens') = 'integer'
                      AND json_type(json_extract(execution.raw_result,
                          '$.payloadJson'),
                          '$.providerCumulativeOutputTokens') = 'integer'))
                  AND NOT EXISTS (
                      SELECT 1
                      FROM trunk_thread_turn_request_receipt later
                      JOIN thread_turn later_turn
                        ON later_turn.id = later.turn_id
                      WHERE later.trunk_id = request.trunk_id
                        AND later_turn.purpose = 'TRUNK_CONVERSATION'
                        AND later.returned_trunk_version
                            > request.returned_trunk_version)
                ORDER BY execution.infrastructure_attempt DESC
                LIMIT 1
                """, (rs, row) -> new CliSession(
                rs.getString("provider_session_id"),
                rs.getLong("cumulative_input_tokens"),
                rs.getLong("cumulative_output_tokens")),
                trunkId, provider, model, workingDirectory, provider)
                .stream().findFirst();
    }

    /** Exact USER_WAIT source; unlike normal completion it has no result receipt. */
    public Optional<UserWaitSource> userWaitSource(
            String turnId, String operationId, String trunkId)
    {
        requireText(turnId, "turnId");
        requireText(operationId, "operationId");
        requireText(trunkId, "trunkId");
        return jdbc.query("""
                SELECT turn.launch_input, execution.id AS execution_id,
                       CASE WHEN execution.provider = json_extract(
                           turn.launch_input, '$.provider')
                         THEN execution.provider_session_id END
                         AS provider_session_id,
                       CASE WHEN json_type(turn.launch_input,
                           '$.resumeSessionId') IS NULL THEN 0
                         ELSE json_extract(turn.launch_input,
                           '$.priorCumulativeInputTokens') END
                           AS cumulative_input_tokens,
                       CASE WHEN json_type(turn.launch_input,
                           '$.resumeSessionId') IS NULL THEN 0
                         ELSE json_extract(turn.launch_input,
                           '$.priorCumulativeOutputTokens') END
                           AS cumulative_output_tokens,
                       NOT EXISTS (
                           SELECT 1
                           FROM trunk_thread_turn_request_receipt later
                           JOIN thread_turn later_turn
                             ON later_turn.id = later.turn_id
                           WHERE later.trunk_id = request.trunk_id
                             AND later_turn.purpose = 'TRUNK_CONVERSATION'
                             AND later.returned_trunk_version
                                 > request.returned_trunk_version)
                           AS latest_turn
                FROM thread_turn turn
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN typed_user_wait_result result
                  ON result.operation_id = turn.operation_id
                 AND result.owner_kind = 'THREAD_TURN'
                 AND result.turn_id = turn.id
                JOIN dispatch_ticket ticket
                  ON ticket.owner_kind = 'THREAD_TURN'
                 AND ticket.owner_id = turn.id
                 AND ticket.operation_id = turn.operation_id
                 AND ticket.status = 'SUCCEEDED'
                JOIN agent_execution execution
                  ON execution.ticket_id = ticket.id
                 AND execution.status = 'SUCCEEDED'
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.trunk_id = ? AND turn.status = 'SUCCEEDED'
                  AND turn.purpose = 'TRUNK_CONVERSATION'
                  AND ((result.wait_kind = 'QUESTION' AND EXISTS (
                        SELECT 1 FROM thread_question question
                        WHERE question.id = result.wait_id
                          AND question.turn_id = turn.id
                          AND question.state = 'ANSWERED'
                          AND question.continuation_state = 'READY'))
                    OR (result.wait_kind = 'PERMISSION' AND EXISTS (
                        SELECT 1 FROM permission_request permission
                        WHERE permission.id = result.wait_id
                          AND permission.turn_kind = 'THREAD'
                          AND permission.turn_id = turn.id
                          AND permission.operation_id = turn.operation_id
                          AND permission.state <> 'OPEN'
                          AND permission.continuation_state = 'READY')))
                ORDER BY execution.infrastructure_attempt DESC
                LIMIT 1
                """, (rs, row) -> new UserWaitSource(
                rs.getString("launch_input"), rs.getString("execution_id"),
                rs.getString("provider_session_id"),
                nullableLong(rs, "cumulative_input_tokens"),
                nullableLong(rs, "cumulative_output_tokens"),
                rs.getBoolean("latest_turn")),
                turnId, operationId, trunkId).stream().findFirst();
    }

    public List<String> executionLog(String executionId)
    {
        requireText(executionId, "executionId");
        return jdbc.query("""
                SELECT payload FROM agent_execution_log
                WHERE execution_id = ? ORDER BY seq
                """, (rs, row) -> rs.getString("payload"), executionId);
    }

    public void markLaunched(
            LaunchCandidate candidate, String turnId, Instant launchedAt)
    {
        requireNonNull(candidate, "candidate is null");
        requireText(turnId, "turnId");
        requireNonNull(launchedAt, "launchedAt is null");
        int changed = jdbc.update("""
                UPDATE planning_base_refresh_operation
                SET launch_disposition = 'LAUNCHED',
                    launched_thread_turn_id = ?, launched_at_ms = ?
                WHERE id = ? AND operation_id = ? AND status = 'SUCCEEDED'
                  AND launch_disposition = 'PENDING'
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
                JOIN trunk_planning_base_request_receipt request
                  ON request.planning_operation_id = operation.id
                WHERE operation.status = 'SUCCEEDED'
                  AND operation.launch_disposition = 'PENDING'
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                ORDER BY request.returned_trunk_version
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

    public record ConversationMessage(String role, String body)
    {
        public ConversationMessage
        {
            requireText(role, "role");
            requireText(body, "body");
        }
    }

    public record UserWaitSource(
            String launchInput,
            String executionId,
            String providerSessionId,
            Long cumulativeInputTokens,
            Long cumulativeOutputTokens,
            boolean latestTurn)
    {
        public UserWaitSource
        {
            requireText(launchInput, "launchInput");
            requireText(executionId, "executionId");
            if (providerSessionId != null && providerSessionId.isBlank()) {
                throw new IllegalArgumentException(
                        "providerSessionId must not be blank");
            }
            if ((cumulativeInputTokens == null)
                    != (cumulativeOutputTokens == null)
                    || (cumulativeInputTokens != null
                    && (cumulativeInputTokens < 0 || cumulativeOutputTokens < 0))) {
                throw new IllegalArgumentException(
                        "cumulative usage baseline is invalid");
            }
        }
    }

    public record CliSession(
            String providerSessionId,
            long cumulativeInputTokens,
            long cumulativeOutputTokens)
    {
        public CliSession
        {
            requireText(providerSessionId, "providerSessionId");
            if (cumulativeInputTokens < 0 || cumulativeOutputTokens < 0) {
                throw new IllegalArgumentException(
                        "cumulative usage must be non-negative");
            }
        }
    }

    private static Long nullableLong(ResultSet rs, String name)
            throws SQLException
    {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
