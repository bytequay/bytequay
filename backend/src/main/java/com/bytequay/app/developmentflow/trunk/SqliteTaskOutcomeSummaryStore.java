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

import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOwnerResultCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Read-only candidate projection for Trunk-owned TaskOutcome commands. */
@Repository
public class SqliteTaskOutcomeSummaryStore
{
    private final JdbcTemplate jdbc;

    public SqliteTaskOutcomeSummaryStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public List<Outcome> pendingOutcomes(int limit)
    {
        return outcomes("inbox.status = 'PENDING'", limit);
    }

    public List<Outcome> summaryCandidates(int limit)
    {
        return outcomes("""
                inbox.status = 'DELIVERED'
                AND outcome.summary_state = 'FALLBACK'
                AND trunk.lifecycle_state <> 'ARCHIVED'
                AND NOT EXISTS (
                  SELECT 1 FROM task_outcome_summary_operation summary
                  WHERE summary.task_outcome_id = outcome.id
                    AND summary.status IN ('REQUESTED', 'SUCCEEDED'))
                """, limit);
    }

    private List<Outcome> outcomes(String condition, int limit)
    {
        positive(limit);
        return jdbc.query("""
                SELECT outcome.id AS task_outcome_id, outcome.task_id,
                       outcome.trunk_id, outcome.task_epoch,
                       outcome.terminal_reason, outcome.observed_head_sha,
                       outcome.cleanup_summary_digest,
                       inbox.delivery_key, inbox.fallback_summary_text,
                       inbox.created_at_ms, task.seq AS task_seq,
                       task.name AS task_name, task.branch_name,
                       trunk.workspace_id, binding.remote_pr_number
                FROM task_outcome outcome
                JOIN trunk_outcome_inbox inbox
                  ON inbox.task_outcome_id = outcome.id
                JOIN tasks task ON task.id = outcome.task_id
                JOIN threads trunk ON trunk.id = outcome.trunk_id
                LEFT JOIN remote_pr_binding binding
                  ON binding.id = outcome.remote_pr_binding_id
                WHERE %s
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state IN (
                    'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
                  AND task.epoch = outcome.task_epoch
                  AND trunk.turn_version = 'V2'
                ORDER BY inbox.created_at_ms, outcome.id
                LIMIT ?
                """.formatted(condition),
                (rs, row) -> new Outcome(
                        rs.getString("task_outcome_id"),
                        rs.getString("delivery_key"),
                        rs.getString("trunk_id"),
                        rs.getString("workspace_id"),
                        rs.getString("task_id"),
                        rs.getLong("task_epoch"),
                        rs.getLong("task_seq"),
                        rs.getString("task_name"),
                        rs.getString("branch_name"),
                        rs.getString("terminal_reason"),
                        integer(rs.getObject("remote_pr_number")),
                        rs.getString("observed_head_sha"),
                        rs.getString("cleanup_summary_digest"),
                        rs.getString("fallback_summary_text"),
                        Instant.ofEpochMilli(rs.getLong("created_at_ms"))),
                limit);
    }

    public List<Enrichment> successfulEnrichments(int limit)
    {
        positive(limit);
        return jdbc.query("""
                SELECT outcome.id AS task_outcome_id,
                       summary.task_turn_id AS turn_id,
                       summary.id AS operation_id, summary.summary_text,
                       summary.summary_digest, summary.completed_at_ms
                FROM task_outcome outcome
                JOIN task_outcome_summary_operation summary
                  ON summary.task_outcome_id = outcome.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = summary.dispatch_ticket_id
                WHERE outcome.summary_state = 'FALLBACK'
                  AND summary.status = 'SUCCEEDED'
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                ORDER BY summary.completed_at_ms, outcome.id
                LIMIT ?
                """, (rs, row) -> new Enrichment(
                        rs.getString("task_outcome_id"),
                        rs.getString("turn_id"),
                        rs.getString("operation_id"),
                        rs.getString("summary_text"),
                        rs.getString("summary_digest"),
                        Instant.ofEpochMilli(rs.getLong("completed_at_ms"))),
                limit);
    }

    @Transactional
    public SummaryRequest requestSummary(
            Outcome outcome, LaunchInputFactory launchInputs, String deliveryLane,
            int laneMask, Instant requestedAt)
    {
        requireNonNull(outcome, "outcome is null");
        requireNonNull(launchInputs, "launchInputs is null");
        requireText(deliveryLane, "deliveryLane");
        requireNonNull(requestedAt, "requestedAt is null");
        List<SummaryRequest> existing = jdbc.query("""
                SELECT operation.task_turn_id, operation.operation_id,
                       operation.dispatch_ticket_id, operation.semantic_attempt
                FROM task_outcome_summary_operation operation
                WHERE operation.task_outcome_id = ?
                  AND operation.status IN ('REQUESTED', 'SUCCEEDED')
                ORDER BY operation.semantic_attempt DESC LIMIT 1
                """, (rs, row) -> new SummaryRequest(
                        rs.getString("task_turn_id"),
                        rs.getString("operation_id"),
                        rs.getString("dispatch_ticket_id"),
                        rs.getInt("semantic_attempt")), outcome.taskOutcomeId());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(semantic_attempt), 0) + 1
                FROM task_outcome_summary_operation
                WHERE task_outcome_id = ?
                """, Integer.class, outcome.taskOutcomeId());
        int attempt = requireNonNull(next, "summary attempt is null");
        String turnId = id("turn", outcome.taskOutcomeId(), attempt);
        String operationId = id("operation", outcome.taskOutcomeId(), attempt);
        String ticketId = id("ticket", outcome.taskOutcomeId(), attempt);
        String launchInput = launchInputs.create(turnId, operationId);
        requireText(launchInput, "launchInput");
        long at = requestedAt.toEpochMilli();
        jdbc.update("""
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, 'TASK_COMPLETION_SUMMARY', 'REQUESTED', ?, ?, ?,
                    NULL, NULL, NULL, NULL, NULL, ?, ?, ?)
                """, turnId, outcome.taskId(), operationId, attempt,
                outcome.taskEpoch(), deliveryLane, launchInput, at);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'GENERATE_TASK_OUTCOME_SUMMARY', 'AGENT_TURN',
                    'TASK_TURN', ?, 'TASK_OUTCOME_SUMMARY_RESULT', ?, 0, 0, 0,
                    ?, ?, ?, ?, NULL, NULL, ?, NULL, NULL, NULL,
                    'REQUESTED', ?)
                """, ticketId, operationId, turnId, laneMask,
                outcome.workspaceId(), outcome.trunkId(), outcome.taskId(),
                outcome.taskEpoch(), attempt, at);
        jdbc.update("""
                INSERT INTO task_outcome_summary_operation(
                    id, task_outcome_id, task_id, task_epoch, task_turn_id,
                    dispatch_ticket_id, operation_id, semantic_attempt,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """, id("summary", outcome.taskOutcomeId(), attempt),
                outcome.taskOutcomeId(), outcome.taskId(), outcome.taskEpoch(),
                turnId, ticketId, operationId, attempt, at);
        return new SummaryRequest(turnId, operationId, ticketId, attempt);
    }

    @Transactional
    public Completion complete(
            AgentTurnOwnerResultCodec.OwnerResult result,
            String rawDigest, Instant completedAt)
    {
        requireNonNull(result, "result is null");
        requireText(rawDigest, "rawDigest");
        requireNonNull(completedAt, "completedAt is null");
        List<CompletionContext> rows = jdbc.query("""
                SELECT summary.id, summary.task_outcome_id, summary.status,
                       summary.summary_digest, turn.status AS turn_status
                FROM task_outcome_summary_operation summary
                JOIN task_turn turn ON turn.id = summary.task_turn_id
                WHERE summary.task_turn_id = ? AND summary.operation_id = ?
                  AND summary.task_epoch = ?
                  AND summary.semantic_attempt = ?
                """, (rs, row) -> new CompletionContext(
                        rs.getString("id"), rs.getString("task_outcome_id"),
                        rs.getString("status"), rs.getString("summary_digest"),
                        rs.getString("turn_status")), result.owner().id(),
                result.fence().operationId(), result.fence().taskEpoch(),
                result.fence().attempt());
        if (rows.size() != 1) {
            throw new IllegalStateException("Task outcome summary fence is stale");
        }
        CompletionContext context = rows.getFirst();
        String status = switch (result.outcome()) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELED -> "CANCELED";
            case FAILED, INDETERMINATE -> "FAILED";
        };
        if (!"REQUESTED".equals(context.status())) {
            if (!status.equals(context.status())
                    || "SUCCEEDED".equals(status)
                    && !rawDigest.equals(context.summaryDigest())) {
                throw new IllegalStateException(
                        "Task outcome summary was redelivered differently");
            }
            return new Completion(context.taskOutcomeId(), status, true);
        }
        String text = result.payload().finalText();
        if ("SUCCEEDED".equals(status) && (text == null || text.isBlank())) {
            throw new IllegalArgumentException(
                    "successful Task outcome summary is blank");
        }
        String error = result.payload().error();
        if ("FAILED".equals(status) && (error == null || error.isBlank())) {
            error = "Task outcome summary provider failed";
        }
        long at = completedAt.toEpochMilli();
        int turnChanged = jdbc.update("""
                UPDATE task_turn
                SET status = ?, finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ?
                  AND status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, status, at, "SUCCEEDED".equals(status) ? null : error,
                result.owner().id(), result.fence().operationId());
        if (turnChanged != 1) {
            throw new IllegalStateException("Task outcome summary Turn changed");
        }
        int operationChanged = jdbc.update("""
                UPDATE task_outcome_summary_operation
                SET status = ?, summary_text = ?, summary_digest = ?,
                    error_message = ?, completed_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, status, "SUCCEEDED".equals(status) ? text : null,
                "SUCCEEDED".equals(status) ? rawDigest : null,
                "SUCCEEDED".equals(status) ? null : error,
                at, context.id());
        if (operationChanged != 1) {
            throw new IllegalStateException(
                    "Task outcome summary Operation changed");
        }
        return new Completion(context.taskOutcomeId(), status, false);
    }

    private static String id(String kind, String outcomeId, int attempt)
    {
        return UUID.nameUUIDFromBytes(("v2-task-outcome-summary:" + kind + ":"
                + outcomeId + ":" + attempt).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static void positive(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static Integer integer(Object value)
    {
        return value == null ? null : ((Number) value).intValue();
    }

    public record Outcome(
            String taskOutcomeId,
            String deliveryKey,
            String trunkId,
            String workspaceId,
            String taskId,
            long taskEpoch,
            long taskSeq,
            String taskName,
            String branchName,
            String terminalReason,
            Integer remotePrNumber,
            String observedHeadSha,
            String cleanupSummaryDigest,
            String fallbackSummaryText,
            Instant createdAt)
    {
        public Outcome
        {
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(deliveryKey, "deliveryKey");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(taskId, "taskId");
            requireText(terminalReason, "terminalReason");
            requireText(cleanupSummaryDigest, "cleanupSummaryDigest");
            requireText(fallbackSummaryText, "fallbackSummaryText");
            requireNonNull(createdAt, "createdAt is null");
            if (taskEpoch < 1 || taskSeq < 1
                    || remotePrNumber != null && remotePrNumber < 1) {
                throw new IllegalArgumentException(
                        "TaskOutcome sequence/identity is invalid");
            }
        }

        public String displayName()
        {
            if (taskName != null && !taskName.isBlank()) {
                return taskName;
            }
            if (branchName != null && !branchName.isBlank()) {
                return branchName;
            }
            return "Task " + taskSeq;
        }
    }

    public record Enrichment(
            String taskOutcomeId,
            String turnId,
            String operationId,
            String summaryText,
            String summaryDigest,
            Instant finishedAt)
    {
        public Enrichment
        {
            requireText(taskOutcomeId, "taskOutcomeId");
            requireText(turnId, "turnId");
            requireText(operationId, "operationId");
            requireText(summaryText, "summaryText");
            requireText(summaryDigest, "summaryDigest");
            requireNonNull(finishedAt, "finishedAt is null");
        }
    }

    public record SummaryRequest(
            String turnId, String operationId, String ticketId, int attempt) {}

    public record Completion(String taskOutcomeId, String status, boolean duplicate) {}

    @FunctionalInterface
    public interface LaunchInputFactory
    {
        String create(String turnId, String operationId);
    }

    private record CompletionContext(
            String id, String taskOutcomeId, String status,
            String summaryDigest, String turnStatus) {}
}
