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

import com.bytequay.app.developmentflow.AgentBrainResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The rows a Turn's result tool writes, and the only place that reads them.
 *
 * <p>A reviewing or repairing Turn used to report by formatting JSON into its
 * final assistant message; five runtimes each hand-parsed that message with
 * their own required-field list. The payload is a tool call now, and the row it
 * writes is the result. One store for the rows because four deliveries read
 * them — {@code task_turn_brain_verdict} alone is read by the Local, remote
 * repair and remote feedback Brain deliveries, and three copies of the same
 * SELECT is how the four hand-rolled decoders started.
 */
@Repository
public class SqliteAgentResultSubmissionStore
{
    /** Small fixed-shape JSON in one column each; no configuration to share. */
    private static final ObjectMapper LIST_JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;

    public SqliteAgentResultSubmissionStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    /**
     * The task owning a Brain review Turn, for any Brain purpose.
     *
     * <p>Deliberately keyed on {@code task_turn.task_id} rather than on the
     * owning episode. The lookup used to join {@code brain_review_episode},
     * which only the Local Development review writes, so a remote review that
     * called the verdict tool was told its owner did not exist. The purpose
     * list is what proves this is a review; the task is already on the Turn.
     */
    public String requireBrainTurnTaskId(String turnId, String operationId)
    {
        List<String> rows = jdbc.query("""
                SELECT turn.task_id
                FROM task_turn turn
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN (
                      'DEVELOPMENT_BRAIN_REVIEW',
                      'DEVELOPMENT_BRAIN_RESULT_REPAIR',
                      'REMOTE_CI_BRAIN_REVIEW',
                      'BRANCH_SYNC_BRAIN_REVIEW',
                      'REMOTE_FEEDBACK_BRAIN_REVIEW')
                """, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Brain review owner is missing");
        }
        return rows.getFirst();
    }

    /** The verdict a Brain review reported through its result tool. */
    public Optional<AgentBrainResult> findBrainVerdict(String turnId)
    {
        return jdbc.query("""
                SELECT verdict, summary, findings_json
                FROM task_turn_brain_verdict
                WHERE task_turn_id = ?
                """, (rs, row) -> new AgentBrainResult(
                        1, rs.getString("verdict"), rs.getString("summary"),
                        readStrings(rs.getString("findings_json"))), turnId)
                .stream().findFirst();
    }

    public void insertBrainVerdict(
            String turnId,
            String operationId,
            String taskId,
            AgentBrainResult verdict,
            Instant submittedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO task_turn_brain_verdict(
                    task_turn_id, operation_id, task_id, verdict, summary,
                    findings_json, submitted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                turnId, operationId, taskId, verdict.verdict(), verdict.summary(),
                writeList(verdict.findings()), submittedAt.toEpochMilli());
    }

    /**
     * The task owning a CI or branch-conflict repair StageTurn, including a
     * steering successor, which carries its predecessor's purpose. The stage
     * carries the task; the purpose is what says this Turn reports a summary.
     *
     * <p>Separate from {@link #requireFeedbackRepairTurnTaskId} on purpose. Both
     * shapes share a table, so a single lookup would let a CI repair record
     * reply drafts that its delivery never reads — accepted and silently
     * discarded, which is the failure this whole change exists to remove.
     */
    public String requireStageRepairTurnTaskId(String turnId, String operationId)
    {
        return requireRepairTurnTaskId("""
                SELECT stage.task_id
                FROM stage_turn turn
                JOIN stage ON stage.id = turn.stage_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose IN ('REMOTE_CI_REPAIR', 'BRANCH_CONFLICT_REPAIR')
                """, turnId, operationId);
    }

    /** The task owning a Remote feedback repair StageTurn or its steering
     *  successor — the one repair that also reports reply drafts. */
    public String requireFeedbackRepairTurnTaskId(String turnId, String operationId)
    {
        return requireRepairTurnTaskId("""
                SELECT stage.task_id
                FROM stage_turn turn
                JOIN stage ON stage.id = turn.stage_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND turn.purpose = 'ADDRESS_REMOTE_FEEDBACK'
                """, turnId, operationId);
    }

    private String requireRepairTurnTaskId(
            String sql, String turnId, String operationId)
    {
        List<String> rows = jdbc.query(
                sql, (rs, row) -> rs.getString(1), turnId, operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException("Remote repair Turn owner is missing");
        }
        return rows.getFirst();
    }

    /** The result a Remote repair Turn reported through its result tool. */
    public Optional<RepairSubmission> findRepairSubmission(String turnId)
    {
        return jdbc.query("""
                SELECT summary, replies_json
                FROM stage_turn_repair_submission
                WHERE stage_turn_id = ?
                """, (rs, row) -> new RepairSubmission(
                        rs.getString("summary"),
                        readStrings(rs.getString("replies_json"))), turnId)
                .stream().findFirst();
    }

    public void insertRepairSubmission(
            String turnId,
            String operationId,
            String taskId,
            RepairSubmission submission,
            Instant submittedAt)
    {
        requireTransaction();
        jdbc.update("""
                INSERT INTO stage_turn_repair_submission(
                    stage_turn_id, operation_id, task_id, summary,
                    replies_json, submitted_at_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                turnId, operationId, taskId, submission.summary(),
                writeList(submission.replies()), submittedAt.toEpochMilli());
    }

    /**
     * A repair Turn's result: the summary every repair reports, plus the reply
     * drafts only a Remote feedback repair prepares, each already serialized by
     * its own tool. Kept as raw JSON strings so this store stays free of the
     * reply shape, which belongs to the feedback runtime.
     */
    public record RepairSubmission(String summary, List<String> replies)
    {
        public RepairSubmission
        {
            replies = List.copyOf(requireNonNull(replies, "replies is null"));
        }
    }

    private static List<String> readStrings(String json)
    {
        try {
            return List.copyOf(LIST_JSON.readValue(json, STRINGS));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored result list is unreadable", e);
        }
    }

    private static String writeList(List<String> values)
    {
        try {
            return LIST_JSON.writeValueAsString(values);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("a result list is unwritable", e);
        }
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "result submissions require a command transaction");
        }
    }
}
