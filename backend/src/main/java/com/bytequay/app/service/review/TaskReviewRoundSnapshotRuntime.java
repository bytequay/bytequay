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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.SnapshotPreparation;
import com.bytequay.app.service.review.ReviewSessionSnapshotRuntime.SnapshotCommand;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Per-command durable snapshot preparation for later Task review rounds. */
@Component
public class TaskReviewRoundSnapshotRuntime
{
    private final JdbcTemplate jdbc;
    private final TaskReviewSnapshotRuntime initialSnapshots;
    private final ObjectMapper json;
    private final ObjectReader commandReader;
    private final Clock clock;

    @Autowired
    public TaskReviewRoundSnapshotRuntime(
            JdbcTemplate jdbc, TaskReviewSnapshotRuntime initialSnapshots,
            ObjectMapper json)
    {
        this(jdbc, initialSnapshots, json, Clock.systemUTC());
    }

    TaskReviewRoundSnapshotRuntime(
            JdbcTemplate jdbc, TaskReviewSnapshotRuntime initialSnapshots,
            ObjectMapper json, Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.initialSnapshots = requireNonNull(
                initialSnapshots, "initialSnapshots is null");
        this.json = requireNonNull(json, "json is null");
        this.commandReader = json.readerFor(SnapshotCommand.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Transactional
    public ExecutionSubject request(
            AgentReviewRow review, SnapshotCommand command)
    {
        requireNonNull(review, "review is null");
        requireNonNull(command, "command is null");
        if (review.ownerTaskId() == null) {
            throw new IllegalArgumentException(
                    "Task review round snapshot requires a Task owner");
        }
        String encoded = write(command);
        Optional<ExecutionSubject> pending = findRequested(review.id());
        if (pending.isPresent()) {
            ExecutionSubject current = pending.orElseThrow();
            if (current.requestJson().equals(encoded)) {
                return current;
            }
            throw new IllegalStateException(
                    "Task review already has a different snapshot preparation");
        }
        RequestContext context = initialSnapshots.requireRequestContext(review.prId());
        if (!review.ownerTaskId().equals(context.taskId())) {
            throw new IllegalStateException(
                    "Task review owner differs from current PR subject");
        }
        String operationId = id(
                "task-review-round-snapshot-operation",
                review.id() + ":" + command.commandId());
        String ticketId = id("task-review-round-snapshot-ticket", operationId);
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO task_review_round_snapshot_operation_v293(
                    id, dispatch_ticket_id, review_id, command_id, pr_id,
                    repository, remote_pr_number, base_branch,
                    pr_title, pr_description,
                    task_id, task_epoch, worktree_path, code_fingerprint,
                    expected_head_sha, expected_base_sha, request_json,
                    status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, operationId, ticketId, review.id(), command.commandId(),
                review.prId(), context.repository(), context.remotePrNumber(),
                context.baseBranch(), context.prTitle(), context.prDescription(),
                context.taskId(), context.taskEpoch(),
                context.worktreePath(), context.codeFingerprint(),
                context.headSha(), context.baseSha(), encoded, now.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'CAPTURE_TASK_REVIEW_ROUND_SNAPSHOT', 'LOCAL_GIT',
                    'TASK', ?, 'TASK_REVIEW_ROUND_SNAPSHOT_RESULT', 16,
                    0, 1, 1, ?, ?, ?, ?, NULL, NULL, 1, ?, ?, ?,
                    'REQUESTED', ?)
                """, ticketId, operationId, context.taskId(),
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.codeFingerprint(), context.headSha(),
                context.baseSha(), now.toEpochMilli());
        return requireExecutionSubject(operationId);
    }

    public ExecutionSubject requireExecutionSubject(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query("""
                SELECT operation.*,
                       EXISTS (
                           SELECT 1
                           FROM review_session review
                           JOIN pr ON pr.id = review.pr_id
                           JOIN tasks task ON task.id = pr.task_id
                           JOIN threads trunk ON trunk.id = task.thread_id
                           JOIN task_code_identity identity
                             ON identity.task_id = task.id
                           JOIN task_current_code_subject_v230 code
                             ON code.task_id = task.id
                           WHERE review.id = operation.review_id
                             AND review.status IN ('ACTIVE', 'STALE')
                             AND review.owner_task_id = operation.task_id
                             AND review.pr_id = operation.pr_id
                             AND review.owner_thread_id = task.thread_id
                             AND review.workspace_id = trunk.workspace_id
                             AND pr.repo IS operation.repository
                             AND pr.remote_pr_number IS operation.remote_pr_number
                             AND pr.base_branch = operation.base_branch
                             AND pr.title = operation.pr_title
                             AND pr.description = operation.pr_description
                             AND task.id = operation.task_id
                             AND task.workflow_version = 'V2'
                             AND task.lifecycle_state = 'ACTIVE'
                             AND task.epoch = operation.task_epoch
                             AND identity.worktree_path = operation.worktree_path
                             AND code.code_fingerprint = operation.code_fingerprint
                             AND code.head_sha = operation.expected_head_sha
                             AND code.base_sha = operation.expected_base_sha
                       ) AS owner_current
                FROM task_review_round_snapshot_operation_v293 operation
                WHERE operation.id = ?
                """, (rs, row) -> new ExecutionSubject(
                rs.getString("id"), rs.getString("review_id"),
                rs.getString("command_id"), rs.getString("pr_id"),
                rs.getString("repository"),
                (Integer) rs.getObject("remote_pr_number"),
                rs.getString("base_branch"), rs.getString("pr_title"),
                rs.getString("pr_description"), rs.getString("task_id"),
                rs.getLong("task_epoch"),
                rs.getString("worktree_path"),
                rs.getString("code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("request_json"),
                Status.valueOf(rs.getString("status")),
                rs.getString("result_json"), rs.getString("error_message"),
                rs.getString("round_id"), rs.getInt("owner_current") != 0),
                operationId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "No Task review round snapshot operation " + operationId));
    }

    public SnapshotCommand command(ExecutionSubject subject)
    {
        try {
            return commandReader.readValue(subject.requestJson());
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Task review round snapshot command is invalid", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<SnapshotPreparation> latestPreparation(String reviewId)
    {
        return jdbc.query("""
                SELECT status, error_message
                FROM task_review_round_snapshot_operation_v293
                WHERE review_id = ?
                ORDER BY requested_at_ms DESC, id DESC LIMIT 1
                """, (rs, row) -> new SnapshotPreparation(
                rs.getString("status"), rs.getString("error_message"), "full"),
                reviewId).stream().findFirst();
    }

    @Transactional
    public void finishCompleted(
            String operationId, String roundId, String resultJson)
    {
        finish(operationId, Status.COMPLETED, roundId, resultJson, null);
    }

    @Transactional
    public void finishTerminal(
            String operationId, Status status, String resultJson, String error)
    {
        if (status == Status.REQUESTED || status == Status.COMPLETED) {
            throw new IllegalArgumentException("terminal failure status is invalid");
        }
        finish(operationId, status, null, resultJson, error);
    }

    private void finish(
            String operationId, Status status, String roundId,
            String resultJson, String error)
    {
        requireNonNull(resultJson, "resultJson is null");
        int changed = jdbc.update("""
                UPDATE task_review_round_snapshot_operation_v293
                SET status = ?, result_json = ?, error_message = ?, round_id = ?,
                    completed_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, status.name(), resultJson, error, roundId,
                clock.instant().toEpochMilli(), operationId);
        ExecutionSubject current = requireExecutionSubject(operationId);
        if (changed == 0 && (current.status() != status
                || !resultJson.equals(current.resultJson())
                || !Objects.equals(roundId, current.roundId()))) {
            throw new IllegalStateException(
                    "Task review round snapshot was already completed differently");
        }
    }

    private Optional<ExecutionSubject> findRequested(String reviewId)
    {
        return jdbc.query("""
                SELECT id FROM task_review_round_snapshot_operation_v293
                WHERE review_id = ? AND status = 'REQUESTED'
                """, (rs, row) -> rs.getString("id"), reviewId).stream()
                .findFirst().map(this::requireExecutionSubject);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not encode Task review round snapshot command", e);
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record ExecutionSubject(
            String operationId, String reviewId, String commandId, String prId,
            String repository, Integer remotePrNumber, String baseBranch,
            String prTitle, String prDescription,
            String taskId, long taskEpoch, String worktreePath,
            String codeFingerprint, String headSha, String baseSha,
            String requestJson, Status status, String resultJson, String error,
            String roundId, boolean current)
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
