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
                AND outcome.summary_thread_turn_id IS NULL
                AND trunk.lifecycle_state <> 'ARCHIVED'
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
                       outcome.summary_thread_turn_id AS turn_id,
                       turn.operation_id, message.body AS summary_text,
                       result.raw_result_digest AS summary_digest,
                       turn.finished_at_ms
                FROM task_outcome outcome
                JOIN thread_turn turn
                  ON turn.id = outcome.summary_thread_turn_id
                JOIN thread_message message
                  ON message.turn_id = turn.id AND message.seq = 2
                JOIN trunk_thread_turn_result_receipt result
                  ON result.turn_id = turn.id
                 AND result.operation_id = turn.operation_id
                JOIN trunk_thread_turn_request_receipt request
                  ON request.turn_id = turn.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = request.dispatch_ticket_id
                WHERE outcome.summary_state = 'FALLBACK'
                  AND turn.purpose = 'TASK_COMPLETION_SUMMARY'
                  AND turn.status = 'SUCCEEDED'
                  AND result.acceptance = 'ACCEPTED'
                  AND result.terminal_status = 'SUCCEEDED'
                  AND result.assistant_message_id = message.id
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                ORDER BY turn.finished_at_ms, outcome.id
                LIMIT ?
                """, (rs, row) -> new Enrichment(
                        rs.getString("task_outcome_id"),
                        rs.getString("turn_id"),
                        rs.getString("operation_id"),
                        rs.getString("summary_text"),
                        rs.getString("summary_digest"),
                        Instant.ofEpochMilli(rs.getLong("finished_at_ms"))),
                limit);
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
}
