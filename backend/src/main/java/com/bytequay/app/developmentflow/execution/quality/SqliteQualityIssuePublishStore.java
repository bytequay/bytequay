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
package com.bytequay.app.developmentflow.execution.quality;

import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.OperationStore;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Status;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.service.tools.ParkedProposal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

/** SQLite owner ledger for V2 quality-issue approval and result delivery. */
@Repository
public class SqliteQualityIssuePublishStore
        implements OperationStore
{
    private static final String MARKER_PREFIX =
            "<!-- bytequay-quality-issue-operation:v1 id=";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqliteDispatchWakeStore wakes;

    public SqliteQualityIssuePublishStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SqliteDispatchWakeStore wakes)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
        this.wakes = requireNonNull(wakes, "wakes is null");
    }

    public Operation authorize(
            Notification notification,
            ParkedProposal.CreateIssue proposal,
            String effectiveBody,
            Instant now)
    {
        requireNonNull(notification, "notification is null");
        requireNonNull(proposal, "proposal is null");
        requireNonNull(now, "now is null");
        validate(proposal, effectiveBody);
        return requireNonNull(transactions.execute(ignored -> {
            Subject subject = requireSubject(notification.id());
            requireSameNotification(notification, subject);
            String operationId = id(
                    "quality-issue-publish-operation", notification.id());
            String marker = MARKER_PREFIX + operationId + " -->";
            String body = withMarker(
                    IssueOrigin.markQualityScan(effectiveBody), marker);
            String payloadDigest = digest(
                    proposal.repo().owner() + "\n" + proposal.repo().repo()
                            + "\n" + proposal.title() + "\n" + body);
            Optional<Operation> replay = findByNotification(notification.id());
            if (replay.isPresent()) {
                return requireReplay(replay.orElseThrow(), proposal, body,
                        payloadDigest, subject);
            }
            if (subject.status() != NotificationStatus.UNREAD
                    && subject.status() != NotificationStatus.READ) {
                throw new ResponseStatusException(CONFLICT,
                        "quality issue notification is already being resolved");
            }
            int claimed = jdbc.update("""
                    UPDATE notifications
                    SET status = 'RESOLVING',
                        read_at_ms = COALESCE(read_at_ms, ?)
                    WHERE id = ? AND status IN ('UNREAD', 'READ')
                    """, now.toEpochMilli(), notification.id());
            if (claimed != 1) {
                throw new ResponseStatusException(CONFLICT,
                        "quality issue notification was resolved concurrently");
            }
            String rowId = id("quality-issue-publish", notification.id());
            String ticketId = id("quality-issue-publish-ticket", operationId);
            jdbc.update("""
                    INSERT INTO v2_quality_issue_publish_v285(
                        id, operation_id, notification_id, task_id, task_epoch,
                        workspace_id, trunk_id, repo_owner, repo_name, issue_title,
                        issue_body, idempotency_marker, payload_digest, status,
                        authorized_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'REQUESTED', ?)
                    """, rowId, operationId, notification.id(), subject.taskId(),
                    subject.taskEpoch(), subject.workspaceId(), subject.trunkId(),
                    proposal.repo().owner().strip(), proposal.repo().repo().strip(),
                    proposal.title().strip(), body, marker, payloadDigest,
                    now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch,
                        stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES (?, ?, 'PUBLISH_V2_QUALITY_ISSUE', 'GITHUB_EFFECT',
                        'TASK', ?, 'V2_QUALITY_ISSUE_RESULT', 32,
                        0, 1, 0, ?, ?, ?, ?, NULL, NULL, 1,
                        NULL, NULL, NULL, 'REQUESTED', ?)
                    """, ticketId, operationId, subject.taskId(),
                    subject.workspaceId(), subject.trunkId(), subject.taskId(),
                    subject.taskEpoch(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO v2_quality_issue_publish_dispatch_v285(
                        publish_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES (?, ?, ?, ?)
                    """, rowId, ticketId, operationId, now.toEpochMilli());
            wakes.enqueue(ticketId, now);
            return require(operationId);
        }), "quality issue authorization returned null");
    }

    public void discard(Notification notification, Instant now)
    {
        requireNonNull(notification, "notification is null");
        requireNonNull(now, "now is null");
        transactions.executeWithoutResult(ignored -> {
            Subject subject = requireSubject(notification.id());
            requireSameNotification(notification, subject);
            if (findByNotification(notification.id()).isPresent()) {
                throw new ResponseStatusException(CONFLICT,
                        "quality issue approval was already dispatched");
            }
            int resolved = jdbc.update("""
                    UPDATE notifications
                    SET status = 'RESOLVED',
                        read_at_ms = COALESCE(read_at_ms, ?)
                    WHERE id = ? AND status IN ('UNREAD', 'READ')
                    """, now.toEpochMilli(), notification.id());
            if (resolved != 1 && subject.status() != NotificationStatus.RESOLVED) {
                throw new ResponseStatusException(CONFLICT,
                        "quality issue notification cannot be discarded");
            }
        });
    }

    public Optional<Operation> findByNotification(String notificationId)
    {
        return jdbc.query("""
                SELECT publish.*, dispatch.dispatch_ticket_id
                FROM v2_quality_issue_publish_v285 publish
                LEFT JOIN v2_quality_issue_publish_dispatch_v285 dispatch
                  ON dispatch.publish_id = publish.id
                WHERE publish.notification_id = ?
                """, (rs, row) -> operation(rs), notificationId)
                .stream().findFirst();
    }

    @Override
    public Operation require(String operationId)
    {
        return jdbc.query("""
                SELECT publish.*, dispatch.dispatch_ticket_id
                FROM v2_quality_issue_publish_v285 publish
                JOIN v2_quality_issue_publish_dispatch_v285 dispatch
                  ON dispatch.publish_id = publish.id
                 AND dispatch.operation_id = publish.operation_id
                WHERE publish.operation_id = ?
                """, (rs, row) -> operation(rs), operationId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No quality issue operation " + operationId));
    }

    @Override
    public Operation markExecuting(String operationId, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE v2_quality_issue_publish_v285
                SET status = 'EXECUTING', effect_started_at_ms = ?
                WHERE operation_id = ? AND status = 'REQUESTED'
                """, now.toEpochMilli(), operationId);
        if (changed != 1) {
            throw new IllegalStateException(
                    "Quality issue execution claim was lost");
        }
        return require(operationId);
    }

    @Override
    public Operation markSucceeded(
            String operationId, RepoIssue issue, Instant now)
    {
        requireNonNull(issue, "issue is null");
        int changed = jdbc.update("""
                UPDATE v2_quality_issue_publish_v285
                SET status = 'SUCCEEDED', external_issue_id = ?,
                    external_issue_number = ?, external_issue_url = ?,
                    last_error = NULL, effect_completed_at_ms = ?
                WHERE operation_id = ?
                  AND status IN ('REQUESTED', 'EXECUTING', 'INDETERMINATE')
                """, issue.id(), issue.number(), issue.htmlUrl(),
                now.toEpochMilli(), operationId);
        Operation current = require(operationId);
        if (changed == 0 && (current.status() != Status.SUCCEEDED
                && current.status() != Status.DELIVERED)) {
            throw new IllegalStateException(
                    "Quality issue success raced another outcome");
        }
        if (current.issue() == null
                || current.issue().id() != issue.id()
                || current.issue().number() != issue.number()) {
            throw new IllegalStateException(
                    "Quality issue operation completed with different evidence");
        }
        return current;
    }

    @Override
    public Operation markIndeterminate(
            String operationId, String error, Instant now)
    {
        jdbc.update("""
                UPDATE v2_quality_issue_publish_v285
                SET status = 'INDETERMINATE', last_error = ?,
                    effect_completed_at_ms = ?
                WHERE operation_id = ? AND status = 'EXECUTING'
                """, error, now.toEpochMilli(), operationId);
        return require(operationId);
    }

    @Override
    public Operation markFailed(String operationId, String error, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE v2_quality_issue_publish_v285
                SET status = 'FAILED', last_error = ?,
                    effect_completed_at_ms = ?
                WHERE operation_id = ? AND status = 'EXECUTING'
                """, error, now.toEpochMilli(), operationId);
        if (changed != 1) {
            throw new IllegalStateException("Quality issue failure claim was lost");
        }
        return require(operationId);
    }

    public Operation finishDelivery(
            String operationId, Status outcome, String resultJson,
            String error, Instant now)
    {
        requireNonNull(outcome, "outcome is null");
        requireNonNull(resultJson, "resultJson is null");
        if (outcome != Status.DELIVERED
                && outcome != Status.FAILED
                && outcome != Status.CANCELED) {
            throw new IllegalArgumentException("invalid delivery outcome " + outcome);
        }
        Operation before = require(operationId);
        int notification = jdbc.update("""
                UPDATE notifications SET status = 'RESOLVED'
                WHERE id = ? AND task_id = ? AND status = 'RESOLVING'
                """, before.notificationId(), before.taskId());
        if (notification == 0) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM notifications WHERE id = ? AND task_id = ?",
                    String.class, before.notificationId(), before.taskId());
            if (!"RESOLVED".equals(status)) {
                throw new IllegalStateException(
                        "Exact quality issue notification is no longer resolving");
            }
        }
        int changed = jdbc.update("""
                UPDATE v2_quality_issue_publish_v285
                SET status = ?, result_json = ?, last_error = ?,
                    effect_completed_at_ms = COALESCE(effect_completed_at_ms, ?),
                    delivered_at_ms = ?
                WHERE operation_id = ?
                  AND delivered_at_ms IS NULL
                  AND status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE', 'REQUESTED')
                """, outcome.name(), resultJson, error, now.toEpochMilli(),
                now.toEpochMilli(), operationId);
        Operation current = require(operationId);
        if (changed == 0 && (current.status() != outcome
                || current.deliveredAt() == null
                || !Objects.equals(current.resultJson(), resultJson)
                || !Objects.equals(current.lastError(), error))) {
            throw new IllegalStateException(
                    "Quality issue delivery was already completed differently");
        }
        return current;
    }

    private Subject requireSubject(String notificationId)
    {
        return jdbc.query("""
                SELECT notification.id, notification.kind, notification.status,
                       notification.thread_id, notification.task_id,
                       notification.payload_json,
                       task.epoch AS task_epoch,
                       task.lifecycle_state AS task_lifecycle,
                       trunk.workspace_id
                FROM notifications notification
                JOIN tasks task ON task.id = notification.task_id
                JOIN threads trunk ON trunk.id = task.thread_id
                WHERE notification.id = ?
                  AND notification.kind = 'AWAITING_REVIEW'
                  AND notification.thread_id = task.thread_id
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state IN ('CANCELING', 'CLEANING', 'CANCELED')
                """, (rs, row) -> new Subject(
                rs.getString("id"), NotificationStatus.valueOf(
                        rs.getString("status")), rs.getString("thread_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("workspace_id"), rs.getString("payload_json")),
                notificationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT,
                        "notification is not owned by a canceled V2 quality task"));
    }

    private static void requireSameNotification(
            Notification notification, Subject subject)
    {
        if (!notification.id().equals(subject.notificationId())
                || !notification.threadId().equals(subject.trunkId())
                || !notification.taskId().equals(subject.taskId())
                || !requireNonNull(notification.payloadJson(), "payloadJson is null")
                        .equals(subject.payloadJson())) {
            throw new ResponseStatusException(CONFLICT,
                    "quality issue notification changed during approval");
        }
    }

    private static Operation requireReplay(
            Operation operation,
            ParkedProposal.CreateIssue proposal,
            String body,
            String payloadDigest,
            Subject subject)
    {
        if (!operation.taskId().equals(subject.taskId())
                || operation.taskEpoch() != subject.taskEpoch()
                || !operation.trunkId().equals(subject.trunkId())
                || !operation.workspaceId().equals(subject.workspaceId())
                || !operation.repoOwner().equals(proposal.repo().owner().strip())
                || !operation.repoName().equals(proposal.repo().repo().strip())
                || !operation.title().equals(proposal.title().strip())
                || !operation.body().equals(body)
                || !operation.payloadDigest().equals(payloadDigest)) {
            throw new ResponseStatusException(CONFLICT,
                    "quality issue approval was already authorized differently");
        }
        return operation;
    }

    private static void validate(
            ParkedProposal.CreateIssue proposal, String body)
    {
        if (proposal.repo() == null
                || blank(proposal.repo().owner()) || blank(proposal.repo().repo())
                || blank(proposal.title()) || blank(body)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "quality issue proposal is incomplete");
        }
    }

    private static String withMarker(String body, String marker)
    {
        String value = body.stripTrailing();
        return value.contains(marker) ? value : value + "\n\n" + marker;
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }

    private static Operation operation(ResultSet rs)
            throws SQLException
    {
        Long issueId = nullableLong(rs, "external_issue_id");
        Integer issueNumber = nullableInt(rs, "external_issue_number");
        RepoIssue issue = issueId == null ? null : new RepoIssue(
                issueId, issueNumber, rs.getString("issue_title"), null,
                "open", rs.getString("external_issue_url"), null,
                List.of(), 0, IssueOrigin.QUALITY_SCAN);
        return new Operation(
                rs.getString("id"), rs.getString("operation_id"),
                rs.getString("notification_id"), rs.getString("task_id"),
                rs.getLong("task_epoch"), rs.getString("workspace_id"),
                rs.getString("trunk_id"), rs.getString("repo_owner"),
                rs.getString("repo_name"), rs.getString("issue_title"),
                rs.getString("issue_body"), rs.getString("idempotency_marker"),
                rs.getString("payload_digest"), Status.valueOf(rs.getString("status")),
                issue, rs.getString("last_error"), rs.getString("result_json"),
                nullableInstant(rs, "delivered_at_ms"),
                rs.getString("dispatch_ticket_id"));
    }

    private static Long nullableLong(ResultSet rs, String name)
            throws SQLException
    {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String name)
            throws SQLException
    {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private static Instant nullableInstant(ResultSet rs, String name)
            throws SQLException
    {
        Long value = nullableLong(rs, name);
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private record Subject(
            String notificationId,
            NotificationStatus status,
            String trunkId,
            String taskId,
            long taskEpoch,
            String workspaceId,
            String payloadJson) {}
}
