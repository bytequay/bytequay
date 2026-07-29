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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable ReviewSession-owned boundary for standalone snapshot preparation. */
@Component
public class ReviewSessionSnapshotRuntime
{
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectReader commandReader;
    private final Clock clock;

    @Autowired
    public ReviewSessionSnapshotRuntime(JdbcTemplate jdbc, ObjectMapper json)
    {
        this(jdbc, json, Clock.systemUTC());
    }

    ReviewSessionSnapshotRuntime(JdbcTemplate jdbc, ObjectMapper json, Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
        this.commandReader = json.readerFor(SnapshotCommand.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Transactional
    public AgentReviewRow requestNew(
            PR pr, String workspaceId, Scope scope, SnapshotCommand command)
    {
        requireNonNull(pr, "pr is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(command, "command is null");
        Optional<AgentReviewRow> existing = findActiveReview(pr.id());
        if (existing.isPresent()) {
            request(existing.orElseThrow(), scope, command);
            return existing.orElseThrow();
        }

        Subject subject = subject(pr.id(), workspaceId, scope);
        String reviewId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        AgentReviewRow review = new AgentReviewRow(
                reviewId, pr.repo() == null ? "local" : pr.repo(), pr.id(),
                subject.baseSha(), subject.headSha(), "ACTIVE", workspaceId,
                null, null);
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, workspace_id, owner_thread_id, owner_task_id,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, NULL, NULL, ?, ?)
                """, review.id(), review.repoId(), review.prId(),
                review.baseCommit(), review.reviewedHeadCommit(),
                review.workspaceId(), now.toEpochMilli(), now.toEpochMilli());
        insert(review, subject, scope, command, now);
        return review;
    }

    @Transactional
    public ExecutionSubject request(
            AgentReviewRow review, Scope scope, SnapshotCommand command)
    {
        requireNonNull(review, "review is null");
        requireNonNull(scope, "scope is null");
        requireNonNull(command, "command is null");
        if (review.ownerTaskId() != null || review.ownerThreadId() != null) {
            throw new IllegalArgumentException(
                    "ReviewSession snapshot preparation requires a standalone owner");
        }
        Subject subject = subject(review.prId(), review.workspaceId(), scope);
        String encoded = write(command);
        Optional<ExecutionSubject> pending = findRequested(review.id());
        if (pending.isPresent()) {
            ExecutionSubject current = pending.orElseThrow();
            if (current.scope() == scope
                    && current.requestJson().equals(encoded)
                    && current.repository().equals(subject.repository())
                    && current.remotePrNumber() == subject.remotePrNumber()
                    && current.baseBranch().equals(subject.baseBranch())
                    && current.prTitle().equals(subject.prTitle())
                    && current.prDescription().equals(subject.prDescription())
                    && current.baseSha().equals(subject.baseSha())
                    && current.headSha().equals(subject.headSha())
                    && Objects.equals(
                        current.repositoryRoot(), subject.repositoryRoot())) {
                return current;
            }
            throw new IllegalStateException(
                    "ReviewSession already has a different snapshot preparation");
        }
        return insert(review, subject, scope, command, clock.instant());
    }

    private ExecutionSubject insert(
            AgentReviewRow review, Subject subject, Scope scope,
            SnapshotCommand command, Instant now)
    {
        String operationId = id(
                "review-session-snapshot-operation",
                review.id() + ":" + command.commandId());
        String ticketId = id("review-session-snapshot-ticket", operationId);
        String requestJson = write(command);
        jdbc.update("""
                INSERT INTO review_session_snapshot_operation_v293(
                    id, dispatch_ticket_id, review_id, command_id, pr_id, repository,
                    remote_pr_number, base_branch, pr_title, pr_description, workspace_id,
                    repository_root, scope, request_json, expected_base_sha,
                    expected_head_sha, status, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', ?)
                """, operationId, ticketId, review.id(), command.commandId(), review.prId(),
                subject.repository(), subject.remotePrNumber(),
                subject.baseBranch(), subject.prTitle(), subject.prDescription(),
                subject.workspaceId(),
                subject.repositoryRoot(), scope.wire(),
                requestJson, subject.baseSha(), subject.headSha(), now.toEpochMilli());
        boolean quick = scope == Scope.QUICK;
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'CAPTURE_REVIEW_SESSION_SNAPSHOT', ?,
                    'REVIEW_SESSION', ?, 'REVIEW_SESSION_SNAPSHOT_RESULT', ?,
                    0, 0, 0, ?, NULL, NULL, NULL, NULL, NULL, 1, NULL, ?, ?,
                    'REQUESTED', ?)
                """, ticketId, operationId,
                quick ? "REMOTE_OBSERVATION" : "LOCAL_GIT", review.id(),
                quick ? 64 : 48, subject.workspaceId(), subject.headSha(),
                subject.baseSha(), now.toEpochMilli());
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
                           WHERE review.id = operation.review_id
                             AND review.pr_id = operation.pr_id
                             AND review.owner_task_id IS NULL
                             AND review.owner_thread_id IS NULL
                             AND pr.task_id IS NULL
                             AND review.status IN ('ACTIVE', 'STALE')
                             AND review.workspace_id IS operation.workspace_id
                             AND pr.repo = operation.repository
                             AND pr.remote_pr_number = operation.remote_pr_number
                             AND pr.base_branch = operation.base_branch
                             AND pr.title = operation.pr_title
                             AND pr.description = operation.pr_description
                             AND operation.expected_base_sha = COALESCE((
                                 SELECT commit_row.sha
                                 FROM pr_commit commit_row
                                 WHERE commit_row.pr_id = operation.pr_id
                                 ORDER BY commit_row.authored_at_ms, commit_row.id
                                 LIMIT 1), 'unknown-base')
                             AND operation.expected_head_sha = COALESCE((
                                 SELECT commit_row.sha
                                 FROM pr_commit commit_row
                                 WHERE commit_row.pr_id = operation.pr_id
                                 ORDER BY commit_row.authored_at_ms DESC,
                                          commit_row.id DESC
                                 LIMIT 1), 'unknown-head')
                             AND (operation.scope = 'quick' OR EXISTS (
                                 SELECT 1
                                 FROM workspace_repos binding
                                 JOIN watched_repos watched
                                   ON lower(binding.repo_full_name) =
                                      lower(watched.owner || '/' || watched.repo)
                                 JOIN pr ON pr.id = operation.pr_id
                                 WHERE binding.workspace_id = operation.workspace_id
                                   AND lower(binding.repo_full_name) = lower(pr.repo)
                                   AND watched.local_clone_path =
                                       operation.repository_root))) AS owner_current
                FROM review_session_snapshot_operation_v293 operation
                WHERE operation.id = ?
                """, (rs, row) -> new ExecutionSubject(
                rs.getString("id"), rs.getString("review_id"),
                rs.getString("command_id"), rs.getString("pr_id"),
                rs.getString("repository"), rs.getInt("remote_pr_number"),
                rs.getString("base_branch"), rs.getString("pr_title"),
                rs.getString("pr_description"),
                rs.getString("workspace_id"), rs.getString("repository_root"),
                Scope.fromWire(rs.getString("scope")),
                rs.getString("request_json"),
                rs.getString("expected_base_sha"),
                rs.getString("expected_head_sha"),
                Status.valueOf(rs.getString("status")),
                rs.getString("result_json"), rs.getString("error_message"),
                rs.getString("round_id"), rs.getInt("owner_current") != 0),
                operationId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "No ReviewSession snapshot operation " + operationId));
    }

    public SnapshotCommand command(ExecutionSubject subject)
    {
        try {
            return commandReader.readValue(subject.requestJson());
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "ReviewSession snapshot command is invalid", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<SnapshotPreparation> latestPreparation(String reviewId)
    {
        requireText(reviewId, "reviewId");
        return jdbc.query("""
                SELECT status, error_message, scope
                FROM review_session_snapshot_operation_v293
                WHERE review_id = ?
                ORDER BY requested_at_ms DESC, id DESC
                LIMIT 1
                """, (rs, row) -> new SnapshotPreparation(
                rs.getString("status"), rs.getString("error_message"),
                rs.getString("scope")), reviewId).stream().findFirst();
    }

    @Transactional
    public void finishCompleted(
            String operationId, String roundId, String resultJson)
    {
        requireText(roundId, "roundId");
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
                UPDATE review_session_snapshot_operation_v293
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
                    "ReviewSession snapshot was already completed differently");
        }
    }

    private Subject subject(String prId, String workspaceId, Scope scope)
    {
        requireText(prId, "prId");
        if (scope == Scope.QUICK && workspaceId != null) {
            throw new IllegalArgumentException("quick snapshot must be detached");
        }
        if (scope == Scope.FULL && (workspaceId == null || workspaceId.isBlank())) {
            throw new IllegalArgumentException("full snapshot requires a workspace");
        }
        return jdbc.query("""
                SELECT pr.id AS pr_id, pr.repo, pr.remote_pr_number,
                       pr.base_branch, pr.title, pr.description,
                       COALESCE((
                           SELECT commit_row.sha FROM pr_commit commit_row
                           WHERE commit_row.pr_id = pr.id
                           ORDER BY commit_row.authored_at_ms, commit_row.id
                           LIMIT 1), 'unknown-base') AS base_sha,
                       COALESCE((
                           SELECT commit_row.sha FROM pr_commit commit_row
                           WHERE commit_row.pr_id = pr.id
                           ORDER BY commit_row.authored_at_ms DESC,
                                    commit_row.id DESC
                           LIMIT 1), 'unknown-head') AS head_sha,
                       CASE WHEN ? = 'quick' THEN NULL ELSE (
                           SELECT watched.local_clone_path
                           FROM workspace_repos binding
                           JOIN watched_repos watched
                             ON lower(binding.repo_full_name) =
                                lower(watched.owner || '/' || watched.repo)
                           WHERE binding.workspace_id = ?
                             AND lower(binding.repo_full_name) = lower(pr.repo)
                           LIMIT 1) END AS repository_root
                FROM pr
                WHERE pr.id = ?
                  AND pr.task_id IS NULL
                  AND pr.repo IS NOT NULL
                  AND pr.remote_pr_number IS NOT NULL
                  AND pr.base_branch IS NOT NULL
                  AND trim(pr.base_branch) <> ''
                """, (rs, row) -> new Subject(
                prId, rs.getString("repo"), rs.getInt("remote_pr_number"),
                rs.getString("base_branch"), rs.getString("title"),
                rs.getString("description"), workspaceId,
                rs.getString("repository_root"),
                rs.getString("base_sha"), rs.getString("head_sha")),
                scope.wire(), workspaceId, prId).stream().findFirst()
                .filter(value -> scope == Scope.QUICK
                        || value.repositoryRoot() != null
                                && !value.repositoryRoot().isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "workspace has no configured local clone for review PR"));
    }

    private Optional<ExecutionSubject> findRequested(String reviewId)
    {
        return jdbc.query("""
                SELECT id
                FROM review_session_snapshot_operation_v293
                WHERE review_id = ? AND status = 'REQUESTED'
                """, (rs, row) -> rs.getString("id"), reviewId).stream()
                .findFirst().map(this::requireExecutionSubject);
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
            throw new IllegalStateException(
                    "Could not encode ReviewSession snapshot command", e);
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record SnapshotCommand(
            String commandId, String kind, List<String> findingIds,
            StartOptions options, String seed, Integer costCapCents,
            String answerFindingId, String answerText)
    {
        public SnapshotCommand
        {
            requireText(commandId, "commandId");
            requireText(kind, "kind");
            findingIds = findingIds == null ? List.of() : List.copyOf(findingIds);
        }
    }

    public record ExecutionSubject(
            String operationId, String reviewId, String commandId, String prId,
            String repository, int remotePrNumber, String baseBranch,
            String prTitle, String prDescription,
            String workspaceId, String repositoryRoot, Scope scope,
            String requestJson, String baseSha, String headSha, Status status,
            String resultJson, String error, String roundId, boolean current)
    {
        public boolean terminal()
        {
            return status != Status.REQUESTED;
        }
    }

    private record Subject(
            String prId, String repository, int remotePrNumber,
            String baseBranch, String prTitle, String prDescription,
            String workspaceId, String repositoryRoot,
            String baseSha, String headSha) {}

    public enum Scope
    {
        QUICK("quick"),
        FULL("full");

        private final String wire;

        Scope(String wire)
        {
            this.wire = wire;
        }

        public String wire()
        {
            return wire;
        }

        public static Scope fromWire(String value)
        {
            return "quick".equals(value) ? QUICK : FULL;
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
