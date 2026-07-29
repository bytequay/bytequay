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
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.review.InvestigationReviewService.StartOptions;
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
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable entry point for freezing one exact V2 Task review subject. */
@Component
public class TaskReviewSnapshotRuntime
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectReader optionsReader;
    private final Clock clock;

    @Autowired
    public TaskReviewSnapshotRuntime(JdbcTemplate jdbc, ObjectMapper json)
    {
        this(jdbc, json, Clock.systemUTC());
    }

    TaskReviewSnapshotRuntime(JdbcTemplate jdbc, ObjectMapper json, Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
        this.optionsReader = json.readerFor(StartOptions.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Persists the review owner, immutable snapshot request, and ticket atomically. */
    @Transactional
    public AgentReviewRow request(PR pr, StartOptions options)
    {
        requireNonNull(pr, "pr is null");
        Optional<AgentReviewRow> existing = findActiveReview(pr.id());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        RequestContext context = requireRequestContext(pr.id());
        String reviewId = UUID.randomUUID().toString();
        String operationId = id("task-review-snapshot-operation", reviewId);
        String ticketId = id("task-review-snapshot-ticket", operationId);
        Instant now = clock.instant();
        String optionsJson = write(options == null
                ? new StartOptions(null, null, null, null) : options);

        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, workspace_id, owner_thread_id, owner_task_id,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """, reviewId, context.repoId(), context.prId(),
                context.baseSha(), context.headSha(), context.workspaceId(),
                context.trunkId(), context.taskId(), now.toEpochMilli(),
                now.toEpochMilli());
        jdbc.update("""
                INSERT INTO task_review_snapshot_operation_v286(
                    id, review_id, pr_id, repository, remote_pr_number,
                    base_branch, pr_title, pr_description,
                    task_id, task_epoch, worktree_path,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    start_options_json, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, operationId, reviewId, context.prId(),
                context.repository(), context.remotePrNumber(),
                context.baseBranch(), context.prTitle(), context.prDescription(),
                context.taskId(), context.taskEpoch(), context.worktreePath(),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                optionsJson, now.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'CAPTURE_TASK_REVIEW_SNAPSHOT', 'LOCAL_GIT',
                    'TASK', ?, 'TASK_REVIEW_SNAPSHOT_RESULT', 16,
                    0, 1, 1, ?, ?, ?, ?, NULL, NULL, 1, ?, ?, ?,
                    'REQUESTED', ?)
                """, ticketId, operationId, context.taskId(),
                context.workspaceId(), context.trunkId(), context.taskId(),
                context.taskEpoch(), context.codeFingerprint(), context.headSha(),
                context.baseSha(), now.toEpochMilli());
        return new AgentReviewRow(
                reviewId, context.repoId(), context.prId(), context.baseSha(),
                context.headSha(), "ACTIVE", context.workspaceId(),
                context.trunkId(), context.taskId());
    }

    public RequestContext requireRequestContext(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query("""
                SELECT pr.id AS pr_id, COALESCE(NULLIF(pr.repo, ''), 'local') AS repo_id,
                       pr.repo, pr.remote_pr_number, pr.base_branch,
                       pr.title, pr.description,
                       task.id AS task_id, task.epoch AS task_epoch,
                       task.thread_id AS trunk_id, thread.workspace_id,
                       identity.worktree_path, code.code_fingerprint,
                       code.head_sha, code.base_sha
                FROM pr
                JOIN tasks task ON task.id = pr.task_id
                JOIN threads thread ON thread.id = task.thread_id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE pr.id = ? AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM pr_commit commit_row
                      WHERE commit_row.pr_id = pr.id
                        AND commit_row.sha = code.head_sha)
                """, (rs, row) -> new RequestContext(
                rs.getString("pr_id"), rs.getString("repo_id"),
                rs.getString("repo"), (Integer) rs.getObject("remote_pr_number"),
                rs.getString("base_branch"), rs.getString("title"),
                rs.getString("description"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("workspace_id"), rs.getString("trunk_id"),
                rs.getString("worktree_path"), rs.getString("code_fingerprint"),
                rs.getString("head_sha"), rs.getString("base_sha")), prId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "PR " + prId + " has no exact active V2 Task code subject"));
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
                           JOIN task_code_identity identity ON identity.task_id = task.id
                           JOIN task_current_code_subject_v230 code
                             ON code.task_id = task.id
                           WHERE review.id = operation.review_id
                             AND review.status = 'ACTIVE'
                             AND review.pr_id = operation.pr_id
                             AND review.owner_task_id = operation.task_id
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
                FROM task_review_snapshot_operation_v286 operation
                WHERE operation.id = ?
                """, (rs, row) -> new ExecutionSubject(
                rs.getString("id"), rs.getString("review_id"),
                rs.getString("pr_id"), rs.getString("task_id"),
                rs.getString("repository"),
                (Integer) rs.getObject("remote_pr_number"),
                rs.getString("base_branch"), rs.getString("pr_title"),
                rs.getString("pr_description"),
                rs.getLong("task_epoch"), rs.getString("worktree_path"),
                rs.getString("code_fingerprint"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"),
                rs.getString("start_options_json"),
                Status.valueOf(rs.getString("status")),
                rs.getString("result_json"), rs.getString("error_message"),
                rs.getInt("owner_current") != 0), operationId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException(
                        "No Task review snapshot operation " + operationId));
    }

    @Transactional
    public void finishCompleted(String operationId, String resultJson)
    {
        finish(operationId, Status.COMPLETED, resultJson, null);
    }

    @Transactional
    public void finishTerminal(
            String operationId, Status status, String resultJson, String error)
    {
        if (status == Status.REQUESTED || status == Status.COMPLETED) {
            throw new IllegalArgumentException("terminal failure status is invalid");
        }
        ExecutionSubject subject = requireExecutionSubject(operationId);
        finish(operationId, status, resultJson, error);
        jdbc.update("""
                UPDATE review_session
                SET status = 'FAILED', updated_at_ms = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, clock.instant().toEpochMilli(), subject.reviewId());
    }

    private void finish(
            String operationId, Status status, String resultJson, String error)
    {
        requireNonNull(resultJson, "resultJson is null");
        int changed = jdbc.update("""
                UPDATE task_review_snapshot_operation_v286
                SET status = ?, result_json = ?, error_message = ?,
                    completed_at_ms = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, status.name(), resultJson, error,
                clock.instant().toEpochMilli(), operationId);
        ExecutionSubject current = requireExecutionSubject(operationId);
        if (changed == 0 && (current.status() != status
                || !resultJson.equals(current.resultJson()))) {
            throw new IllegalStateException(
                    "Task review snapshot was already completed differently");
        }
    }

    public StartOptions startOptions(ExecutionSubject subject)
    {
        try {
            return optionsReader.readValue(subject.startOptionsJson());
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Task review snapshot options are invalid", e);
        }
    }

    /** Unified preparation projection for initial and later Task rounds. */
    @Transactional(readOnly = true)
    public Optional<SnapshotPreparation> latestPreparation(String reviewId)
    {
        requireText(reviewId, "reviewId");
        return jdbc.query("""
                SELECT status, error_message
                FROM (
                    SELECT status, error_message, requested_at_ms, id
                    FROM task_review_snapshot_operation_v286
                    WHERE review_id = ?
                    UNION ALL
                    SELECT status, error_message, requested_at_ms, id
                    FROM task_review_round_snapshot_operation_v293
                    WHERE review_id = ?)
                ORDER BY requested_at_ms DESC, id DESC
                LIMIT 1
                """, (rs, row) -> new SnapshotPreparation(
                rs.getString("status"), rs.getString("error_message"), "full"),
                reviewId, reviewId).stream().findFirst();
    }

    private Optional<AgentReviewRow> findActiveReview(String prId)
    {
        return jdbc.query("""
                SELECT id, repo_id, pr_id, base_commit, reviewed_head_commit,
                       status, workspace_id, owner_thread_id, owner_task_id
                FROM review_session
                WHERE pr_id = ? AND status IN ('ACTIVE', 'STALE')
                ORDER BY created_at_ms DESC LIMIT 1
                """, (rs, row) -> new AgentReviewRow(
                rs.getString("id"), rs.getString("repo_id"),
                rs.getString("pr_id"), rs.getString("base_commit"),
                rs.getString("reviewed_head_commit"), rs.getString("status"),
                rs.getString("workspace_id"), rs.getString("owner_thread_id"),
                rs.getString("owner_task_id")), prId).stream().findFirst();
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode review snapshot options", e);
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record RequestContext(
            String prId, String repoId, String repository,
            Integer remotePrNumber, String baseBranch, String prTitle,
            String prDescription, String taskId, long taskEpoch,
            String workspaceId, String trunkId, String worktreePath,
            String codeFingerprint, String headSha, String baseSha) {}

    public record ExecutionSubject(
            String operationId, String reviewId, String prId, String taskId,
            String repository, Integer remotePrNumber, String baseBranch,
            String prTitle, String prDescription,
            long taskEpoch, String worktreePath, String codeFingerprint,
            String headSha, String baseSha, String startOptionsJson,
            Status status, String resultJson, String error, boolean current)
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
