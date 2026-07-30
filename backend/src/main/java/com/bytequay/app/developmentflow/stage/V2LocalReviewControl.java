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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteStageSteeringStore;
import com.bytequay.app.domain.DiffSide;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.localpr.PrUpdatedEvent;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/**
 * Synchronous owner of private V2 Local Review state. It freezes feedback in
 * the caller's exact Task command; only the resulting typed StageTurn is
 * asynchronous and competes for capacity through its DispatchTicket.
 */
@Component
public final class V2LocalReviewControl
{
    private static final String ACTOR = "v2-local-review";

    private final JdbcTemplate jdbc;
    private final TaskCommandExecutor commands;
    private final LocalDevelopmentStageManager local;
    private final ObjectMapper json;
    private final ObjectReader workModelReader;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int serverPort;
    private SqliteStageSteeringStore steering;

    @Autowired
    public V2LocalReviewControl(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            ObjectMapper json,
            ApplicationEventPublisher events,
            @Value("${server.port:53123}") int serverPort)
    {
        this(jdbc, commands, local, json, events, Clock.systemUTC(), serverPort);
    }

    V2LocalReviewControl(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            ObjectMapper json,
            ApplicationEventPublisher events,
            Clock clock,
            int serverPort)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.local = requireNonNull(local, "local is null");
        this.json = requireNonNull(json, "json is null");
        this.workModelReader = json.readerFor(WorkModel.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.events = requireNonNull(events, "events is null");
        this.clock = requireNonNull(clock, "clock is null");
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("serverPort is invalid");
        }
        this.serverPort = serverPort;
    }

    @Autowired
    void setSteeringStore(SqliteStageSteeringStore steering)
    {
        this.steering = requireNonNull(steering, "steering is null");
    }

    public boolean handles(PR pr)
    {
        requireNonNull(pr, "pr is null");
        if (pr.taskId() == null || !PR.ORIGIN_TASK.equals(pr.origin())
                || !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tasks
                WHERE id = ? AND workflow_version = 'V2'
                """, Integer.class, pr.taskId());
        return count != null && count == 1;
    }

    public boolean ownsComment(String commentId)
    {
        String idValue = required(commentId, "commentId");
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM local_review_thread thread
                WHERE thread.id = ?
                   OR EXISTS (
                       SELECT 1 FROM local_review_comment_revision revision
                       WHERE revision.thread_id = thread.id AND revision.id = ?)
                """, Integer.class, idValue, idValue);
        return count != null && count > 0;
    }

    /** Edit the PR content only while this exact Local Development subject
     * owns Local Review. The synchronous command is fenced by the current
     * Task epoch, Stage generation, DevReport, and code subject. */
    public void updateDetails(PR pr, String title, String description)
    {
        requireNonNull(pr, "pr is null");
        if (!handles(pr)) {
            throw conflict("PR is not owned by V2 Local Review");
        }
        inCommand(pr.taskId(), () -> {
            Subject subject = requireSubject(pr.taskId(), pr.id());
            if (!"LOCAL_REVIEW".equals(subject.checkpoint())) {
                throw conflict("PR content can be edited only during exact Local Review");
            }
            int changed = jdbc.update("""
                    UPDATE pr
                    SET title = COALESCE(?, title),
                        description = COALESCE(?, description)
                    WHERE id = ? AND task_id = ? AND origin = 'task'
                      AND status = 'local-open'
                    """, title, description, subject.prId(), subject.taskId());
            if (changed != 1) {
                throw conflict("Local Review PR content changed before edit");
            }
            updated(subject.prId());
            return null;
        });
    }

    public PRComment addComment(
            PR pr,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String author,
            String body,
            String parentCommentId)
    {
        requireNonNull(pr, "pr is null");
        if (!handles(pr)) {
            throw conflict("PR is not owned by V2 Local Review");
        }
        return inCommand(pr.taskId(), () -> addCommentInCommand(
                requireSubject(pr.taskId(), pr.id()), origin, scope, filePath,
                lineNumber, side, startLine, startSide, author, body,
                parentCommentId, null));
    }

    private PRComment addCommentInCommand(
            Subject subject,
            String origin,
            String scope,
            String filePath,
            Integer lineNumber,
            String side,
            Integer startLine,
            String startSide,
            String author,
            String body,
            String parentCommentId,
            String findingId)
    {
        TaskCommandExecutor.requireCurrent(subject.taskId());
        if (!PRComment.ORIGIN_LOCAL.equals(required(origin, "origin"))) {
            throw conflict("V2 Local Review accepts private local comments only");
        }
        String authorValue = required(author, "author");
        String bodyValue = required(body, "body");
        PRComment parent = parentCommentId == null || parentCommentId.isBlank()
                ? null : requireComment(parentCommentId.strip());
        if (parent != null && !subject.prId().equals(parent.prId())) {
            throw conflict("parent comment belongs to another PR");
        }
        if (parent != null) {
            scope = parent.scope();
            filePath = parent.filePath();
            lineNumber = parent.lineNumber();
            side = parent.side();
            startLine = parent.startLine();
            startSide = parent.startSide();
            parentCommentId = parent.id();
        }
        String scopeValue = required(scope, "scope");
        if (PRComment.SCOPE_FILE_LINE.equals(scopeValue)) {
            if (filePath == null || filePath.isBlank()
                    || lineNumber == null || lineNumber <= 0) {
                throw badRequest("file-line comment requires filePath and a positive lineNumber");
            }
            filePath = filePath.strip();
        }
        else if (PRComment.SCOPE_PR.equals(scopeValue)) {
            filePath = null;
            lineNumber = null;
            startLine = null;
            startSide = null;
        }
        else {
            throw badRequest("scope must be pr or file-line");
        }
        String resolvedSide = DiffSide.normalize(side);
        Integer resolvedStartLine = startLine != null && !startLine.equals(lineNumber)
                ? startLine : null;
        String resolvedStartSide = resolvedStartLine == null
                ? null : DiffSide.normalizeOptional(startSide, resolvedSide);
        Instant now = clock.instant();
        String commentId = UUID.randomUUID().toString();
        PRComment comment = new PRComment(
                commentId, subject.prId(), PRComment.ORIGIN_LOCAL, scopeValue,
                filePath, lineNumber, authorValue, bodyValue, now, null, null,
                null, parentCommentId, null, resolvedSide, resolvedStartLine,
                resolvedStartSide, findingId, null);
        insertComment(comment);

        String threadId = parent == null ? comment.id() : rootId(parent);
        boolean userAuthored = PRTimelineEntry.ACTOR_USER.equals(authorValue);
        boolean typedRoot = parent == null && (userAuthored
                || PRTimelineEntry.ACTOR_AGENT.equals(authorValue)
                || PRTimelineEntry.ACTOR_BRAIN.equals(authorValue));
        boolean typedReply = parent != null && userAuthored;
        if (typedRoot) {
            String source = sourceFor(subject, authorValue);
            insertThread(subject, comment, source, now);
            appendRevision(subject, threadId, source, bodyValue,
                    userAuthored, comment.id(), now);
        }
        else if (typedReply) {
            Revision previous = requireLatestRevision(threadId);
            supersedePending(previous, "new user reply", now);
            appendRevision(subject, threadId, "USER", bodyValue,
                    true, comment.id(), now);
            if (parent.resolvedAt() != null || parent.dismissedAt() != null) {
                jdbc.update("""
                        UPDATE pr_comment SET resolved_at_ms = NULL,
                            dismissed_at_ms = NULL, resolved_by = NULL
                        WHERE id = ?
                        """, threadId);
            }
        }
        if (PRComment.SCOPE_PR.equals(scopeValue)) {
            insertTimelineComment(subject.prId(), comment.id(), authorValue, now);
        }
        updated(subject.prId());
        return comment;
    }

    public PRComment resolveComment(String commentId)
    {
        return closeComment(commentId, false);
    }

    public PRComment dismissComment(String commentId)
    {
        return closeComment(commentId, true);
    }

    private PRComment closeComment(String commentId, boolean dismissed)
    {
        String taskId = requireCommentTask(commentId);
        return inCommand(taskId, () -> {
            PRComment comment = requireComment(commentId);
            String threadId = requireThreadId(commentId);
            Revision latest = requireLatestRevision(threadId);
            if (Set.of("PENDING", "SUBMITTED", "DRAFT").contains(latest.state())) {
                terminalizeRevision(latest,
                        dismissed ? "dismissed by user" : "resolved by user",
                        clock.instant());
            }
            Instant now = clock.instant();
            jdbc.update(dismissed ? """
                    UPDATE pr_comment SET dismissed_at_ms = ? WHERE id = ?
                    """ : """
                    UPDATE pr_comment SET resolved_at_ms = ?, resolved_by = 'user'
                    WHERE id = ?
                    """, now.toEpochMilli(), threadId);
            updated(comment.prId());
            return requireComment(comment.id());
        });
    }

    public PRComment reopenComment(String commentId)
    {
        String taskId = requireCommentTask(commentId);
        return inCommand(taskId, () -> {
            PRComment comment = requireComment(commentId);
            String threadId = requireThreadId(commentId);
            Revision latest = requireLatestRevision(threadId);
            if (!Set.of("ADDRESSED", "DISMISSED", "SUPERSEDED").contains(latest.state())) {
                return comment;
            }
            Subject subject = requireSubject(taskId, comment.prId());
            Instant now = clock.instant();
            appendRevision(subject, threadId, "USER", comment.body(),
                    true, UUID.randomUUID().toString(), now);
            jdbc.update("""
                    UPDATE pr_comment SET resolved_at_ms = NULL,
                        dismissed_at_ms = NULL, resolved_by = NULL
                    WHERE id = ?
                    """, threadId);
            updated(comment.prId());
            return requireComment(comment.id());
        });
    }

    public PRComment editComment(String commentId, String body)
    {
        String bodyValue = required(body, "body");
        String taskId = requireCommentTask(commentId);
        return inCommand(taskId, () -> {
            PRComment comment = requireComment(commentId);
            if (!PRComment.ORIGIN_LOCAL.equals(comment.origin())
                    || comment.publishedAt() != null) {
                throw conflict("only pending private comments can be edited");
            }
            String threadId = requireThreadId(commentId);
            Revision previous = requireLatestRevision(threadId);
            supersedePending(previous, "edited by user", clock.instant());
            Subject subject = requireSubject(taskId, comment.prId());
            Instant now = clock.instant();
            appendRevision(subject, threadId, "USER", bodyValue, true,
                    UUID.randomUUID().toString(), now);
            jdbc.update("UPDATE pr_comment SET body = ? WHERE id = ?",
                    bodyValue, comment.id());
            updated(comment.prId());
            return requireComment(comment.id());
        });
    }

    public void deleteDraftComment(String commentId)
    {
        String taskId = requireCommentTask(commentId);
        inCommand(taskId, () -> {
            PRComment comment = requireComment(commentId);
            Revision latest = requireLatestRevision(requireThreadId(commentId));
            if (!Set.of("DRAFT", "PENDING").contains(latest.state())) {
                throw conflict("submitted Local Review history cannot be deleted");
            }
            terminalizeRevision(latest, "deleted by user", clock.instant());
            jdbc.update("DELETE FROM pr_comment WHERE parent_comment_id = ?", comment.id());
            jdbc.update("DELETE FROM pr_comment WHERE id = ?", comment.id());
            updated(comment.prId());
            return null;
        });
    }

    public Submission submit(
            String taskId, String body, String verdict, List<String> commentIds)
    {
        String taskIdValue = required(taskId, "taskId");
        String bodyValue = body == null ? "" : body.strip();
        return inCommand(taskIdValue, () -> submitInCommand(
                taskIdValue, bodyValue, verdict, commentIds));
    }

    private Submission submitInCommand(
            String taskId, String body, String verdict, List<String> commentIds)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Subject subject = requireSubject(taskId, null);
        List<String> requested = normalizeIds(commentIds);
        if (body.isEmpty() && !requested.isEmpty()) {
            Submission duplicate = duplicateSubmission(subject, requested).orElse(null);
            if (duplicate != null) {
                return duplicate;
            }
        }
        if (!Set.of("LOCAL_REVIEW", "BRAIN_REVIEW").contains(subject.checkpoint())) {
            throw conflict("Task is not accepting a Local Review submission");
        }
        if (!body.isEmpty()) {
            PRComment summary = addCommentInCommand(
                    subject, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                    null, null, null, null, null,
                    PRTimelineEntry.ACTOR_USER, body, null, null);
            requested = new ArrayList<>(requested);
            requested.add(summary.id());
        }
        List<Revision> selected = requested.isEmpty() && commentIds == null
                ? pendingUserRoots(subject)
                : requestedRevisions(subject, requested);
        if (selected.isEmpty()) {
            String event = verdict == null ? "COMMENT" : verdict.strip();
            if (!body.isEmpty() || "REQUEST_CHANGES".equals(event)) {
                throw conflict("Local Review submission has no current actionable comments");
            }
            return new Submission(0, null);
        }
        FrozenBatch batch = freeze(subject, selected, verdict, clock.instant());
        String turnId;
        if ("LOCAL_REVIEW".equals(subject.checkpoint())) {
            turnId = admitBatchInCommand(batch.id());
        }
        else {
            jdbc.update("""
                    UPDATE local_feedback_batch
                    SET status = 'QUEUED'
                    WHERE id = ? AND status = 'FROZEN'
                    """, batch.id());
            turnId = null;
        }
        updated(subject.prId());
        return new Submission(selected.size(), turnId);
    }

    public Optional<String> admitQueuedInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        List<String> batches = jdbc.query("""
                SELECT batch.id
                FROM local_feedback_batch batch
                JOIN tasks task ON task.id = batch.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                WHERE batch.task_id = ?
                  AND batch.status IN ('FROZEN', 'QUEUED')
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = batch.task_epoch
                  AND current.stage_id = batch.local_development_stage_id
                  AND current.stage_generation = batch.stage_generation
                  AND owner.checkpoint = 'LOCAL_REVIEW'
                  AND owner.completed_at_ms IS NULL
                  AND code.code_fingerprint = batch.code_fingerprint
                  AND code.head_sha = batch.head_sha
                  AND code.base_sha = batch.base_sha
                ORDER BY batch.sequence
                LIMIT 1
                """, (rs, row) -> rs.getString(1), taskId);
        return batches.stream().findFirst().map(this::admitBatchInCommand);
    }

    public void acceptFeedbackResultInCommand(
            String taskId, String batchId, String stageTurnId)
    {
        if (batchId == null) {
            return;
        }
        TaskCommandExecutor.requireCurrent(taskId);
        Instant now = clock.instant();
        List<String> revisions = jdbc.query("""
                SELECT item.comment_revision_id
                FROM local_feedback_batch batch
                JOIN stage_turn turn ON turn.id = batch.stage_turn_id
                JOIN local_feedback_batch_item item ON item.batch_id = batch.id
                WHERE batch.id = ? AND batch.task_id = ?
                  AND batch.stage_turn_id = ? AND batch.status = 'DISPATCHED'
                  AND turn.status = 'SUCCEEDED'
                ORDER BY item.position
                """, (rs, row) -> rs.getString(1), batchId, taskId, stageTurnId);
        if (revisions.isEmpty()) {
            throw new IllegalStateException("successful Local feedback Turn lacks its exact batch");
        }
        for (String revisionId : revisions) {
            int changed = jdbc.update("""
                    UPDATE local_review_comment_revision
                    SET state = 'ADDRESSED', state_version = state_version + 1,
                        state_changed_at_ms = ?, terminal_at_ms = ?
                    WHERE id = ? AND state = 'SUBMITTED'
                    """, now.toEpochMilli(), now.toEpochMilli(), revisionId);
            if (changed != 1 && !"DISMISSED".equals(jdbc.queryForObject("""
                    SELECT state FROM local_review_comment_revision WHERE id = ?
                    """, String.class, revisionId))) {
                throw new IllegalStateException("Local feedback revision changed before acceptance");
            }
            resolveBlocker(revisionId, "addressed by StageTurn " + stageTurnId, now);
        }
        jdbc.update("""
                UPDATE pr_comment
                SET resolved_at_ms = ?, resolved_by = 'agent'
                WHERE id IN (
                    SELECT DISTINCT item.thread_id
                    FROM local_feedback_batch_item item
                    WHERE item.batch_id = ?)
                  AND EXISTS (
                    SELECT 1
                    FROM local_feedback_batch_item addressed_item
                    JOIN local_review_comment_revision addressed
                      ON addressed.id = addressed_item.comment_revision_id
                    WHERE addressed_item.batch_id = ?
                      AND addressed_item.thread_id = pr_comment.id
                      AND addressed.state = 'ADDRESSED')
                  AND NOT EXISTS (
                    SELECT 1 FROM local_review_comment_revision pending
                    WHERE pending.thread_id = pr_comment.id
                      AND pending.state IN ('DRAFT', 'PENDING', 'SUBMITTED'))
                """, now.toEpochMilli(), batchId, batchId);
        if (jdbc.update("""
                UPDATE local_feedback_batch
                SET status = 'ADDRESSED', completed_at_ms = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, now.toEpochMilli(), batchId) != 1) {
            throw new IllegalStateException("Local feedback batch changed before acceptance");
        }
        String prId = jdbc.queryForObject(
                "SELECT pr_id FROM local_feedback_batch WHERE id = ?",
                String.class, batchId);
        jdbc.update("UPDATE pr SET local_addressed_through_ms = ? WHERE id = ?",
                now.toEpochMilli(), prId);
        updated(prId);
    }

    /** Terminalizes a failed/stale batch and reopens only the exact revisions
     * that are still current and retryable. */
    public void rejectFeedbackResultInCommand(
            String taskId,
            String batchId,
            String stageTurnId,
            String terminalStatus,
            String reason,
            boolean retryable)
    {
        if (batchId == null) {
            return;
        }
        TaskCommandExecutor.requireCurrent(taskId);
        String status = required(terminalStatus, "terminalStatus");
        if (!Set.of("FAILED", "CANCELED", "SUPERSEDED").contains(status)) {
            throw new IllegalArgumentException("unsupported feedback terminal status " + status);
        }
        Batch batch = requireBatch(batchId);
        if (!batch.taskId().equals(taskId)
                || !Objects.equals(batch.stageTurnId(), stageTurnId)
                || !"DISPATCHED".equals(batch.status())) {
            throw new IllegalStateException("terminal Local feedback result lacks its exact batch");
        }
        String turnStatus = jdbc.queryForObject("""
                SELECT status FROM stage_turn WHERE id = ?
                """, String.class, stageTurnId);
        if (!status.equals(turnStatus)) {
            throw new IllegalStateException("Local feedback batch and Turn terminal states differ");
        }
        String evidence = reason == null || reason.isBlank()
                ? "Local feedback StageTurn ended " + status
                : reason.strip();
        Instant now = clock.instant();
        List<Revision> selected = jdbc.query("""
                SELECT revision.id, revision.thread_id, revision.revision,
                       revision.author_kind, revision.body,
                       revision.body_digest, revision.state,
                       revision.dev_report_id, revision.task_id,
                       revision.task_epoch,
                       revision.local_development_stage_id,
                       revision.stage_generation,
                       revision.code_fingerprint, revision.head_sha,
                       revision.base_sha
                FROM local_feedback_batch_item item
                JOIN local_review_comment_revision revision
                  ON revision.id = item.comment_revision_id
                WHERE item.batch_id = ?
                ORDER BY item.position
                """, (rs, row) -> revision(rs), batchId);
        Map<String, Revision> reopen = new LinkedHashMap<>();
        for (Revision revision : selected) {
            if (!"SUBMITTED".equals(revision.state())) {
                continue;
            }
            terminalizeRevision(revision, evidence, now);
            reopen.merge(
                    revision.threadId(), revision,
                    (left, right) -> left.number() >= right.number() ? left : right);
        }
        if (jdbc.update("""
                UPDATE local_feedback_batch
                SET status = ?, completed_at_ms = ?, error_message = ?
                WHERE id = ? AND status = 'DISPATCHED'
                """, status, now.toEpochMilli(), evidence, batchId) != 1) {
            throw new IllegalStateException("Local feedback batch changed before failure");
        }

        Subject current = retryable ? requireSubject(taskId, batch.prId()) : null;
        if (current != null && !batch.matches(current)) {
            current = null;
        }
        if (current != null) {
            for (Revision source : reopen.values()) {
                Revision latest = requireLatestRevision(source.threadId());
                if (latest.number() != source.number()) {
                    continue;
                }
                appendRevision(
                        current, source.threadId(), source.authorKind(),
                        source.body(), true, UUID.randomUUID().toString(), now);
                jdbc.update("""
                        UPDATE pr_comment
                        SET resolved_at_ms = NULL, dismissed_at_ms = NULL,
                            resolved_by = NULL
                        WHERE id = ?
                        """, source.threadId());
            }
        }
        updated(batch.prId());
    }

    /**
     * Carries user-authored feedback across an accepted writer result without
     * changing the immutable old-head revision. A frozen batch for the old
     * subject is explicitly superseded first; its selected revisions are
     * reopened as new pending revisions so they cannot become stranded or be
     * applied to a head they were never reviewed against.
     */
    public void carryFeedbackToCurrentSubjectInCommand(
            String taskId, String causeTurnId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Integer owned = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM local_review_thread WHERE task_id = ?)
                     + (SELECT COUNT(*) FROM local_review_agent_request
                        WHERE task_id = ? AND status = 'REQUESTED')
                """, Integer.class, taskId, taskId);
        if (owned == null || owned == 0) {
            return;
        }
        Subject current = requireSubject(taskId, null);
        Instant now = clock.instant();
        String reason = "superseded by code subject from StageTurn "
                + required(causeTurnId, "causeTurnId");
        List<AgentReviewRequest> staleRequests = jdbc.query("""
                SELECT id, review_id, review_round_id, task_id, mode, status
                FROM local_review_agent_request
                WHERE task_id = ?
                  AND task_epoch = ?
                  AND local_development_stage_id = ?
                  AND stage_generation = ?
                  AND status = 'REQUESTED'
                  AND (dev_report_id <> ? OR code_fingerprint <> ?
                       OR head_sha <> ? OR base_sha <> ?)
                ORDER BY requested_at_ms, id
                """, (rs, row) -> new AgentReviewRequest(
                        rs.getString("id"), rs.getString("review_id"),
                        rs.getString("review_round_id"), rs.getString("task_id"),
                        "BLOCKING".equals(rs.getString("mode")),
                        rs.getString("status")), taskId, current.taskEpoch(),
                current.stageId(), current.stageGeneration(),
                current.devReportId(), current.codeFingerprint(),
                current.headSha(), current.baseSha());
        staleRequests.forEach(request ->
                staleAgentRequestInCommand(request, reason, now));
        List<Revision> pendingUser = jdbc.query("""
                SELECT revision.id, revision.thread_id, revision.revision,
                       revision.author_kind, revision.body, revision.body_digest,
                       revision.state, revision.dev_report_id,
                       revision.task_id, revision.task_epoch,
                       revision.local_development_stage_id,
                       revision.stage_generation, revision.code_fingerprint,
                       revision.head_sha, revision.base_sha
                FROM local_review_comment_revision revision
                WHERE revision.task_id = ?
                  AND revision.local_development_stage_id = ?
                  AND revision.task_epoch = ?
                  AND revision.stage_generation = ?
                  AND revision.author_kind = 'USER'
                  AND revision.state = 'PENDING'
                  AND NOT EXISTS (
                      SELECT 1 FROM local_review_comment_revision newer
                      WHERE newer.thread_id = revision.thread_id
                        AND newer.revision > revision.revision)
                """, (rs, row) -> revision(rs), taskId, current.stageId(),
                current.taskEpoch(), current.stageGeneration()).stream()
                .filter(revision -> !revision.matches(current))
                .toList();
        List<Batch> staleBatches = jdbc.query("""
                SELECT id, task_id, local_development_stage_id, task_epoch,
                       stage_generation, pr_id, dev_report_id,
                       source_submission_id, content_digest,
                       code_fingerprint, head_sha, base_sha, status,
                       stage_turn_id
                FROM local_feedback_batch
                WHERE task_id = ?
                  AND local_development_stage_id = ?
                  AND task_epoch = ?
                  AND stage_generation = ?
                  AND status IN ('FROZEN', 'QUEUED')
                ORDER BY sequence
                """, (rs, row) -> batch(rs), taskId, current.stageId(),
                current.taskEpoch(), current.stageGeneration()).stream()
                .filter(batch -> !batch.matches(current))
                .toList();
        if (pendingUser.isEmpty() && staleBatches.isEmpty()) {
            if (!staleRequests.isEmpty()) {
                updated(current.prId());
            }
            return;
        }

        Map<String, Revision> carryByThread = new LinkedHashMap<>();
        pendingUser.forEach(revision -> carryByThread.merge(
                revision.threadId(), revision,
                (left, right) -> left.number() >= right.number() ? left : right));
        for (Batch batch : staleBatches) {
            List<Revision> selected = jdbc.query("""
                    SELECT revision.id, revision.thread_id, revision.revision,
                           revision.author_kind, revision.body,
                           revision.body_digest, revision.state,
                           revision.dev_report_id, revision.task_id,
                           revision.task_epoch,
                           revision.local_development_stage_id,
                           revision.stage_generation,
                           revision.code_fingerprint, revision.head_sha,
                           revision.base_sha
                    FROM local_feedback_batch_item item
                    JOIN local_review_comment_revision revision
                      ON revision.id = item.comment_revision_id
                    WHERE item.batch_id = ?
                    ORDER BY item.position
                    """, (rs, row) -> revision(rs), batch.id());
            for (Revision revision : selected) {
                if (!"SUBMITTED".equals(revision.state())) {
                    continue;
                }
                carryByThread.merge(
                        revision.threadId(), revision,
                        (left, right) -> left.number() >= right.number() ? left : right);
                Revision latestState = requireLatestRevision(revision.threadId());
                if (latestState.id().equals(revision.id())
                        && "SUBMITTED".equals(latestState.state())) {
                    terminalizeRevision(latestState, reason, now);
                }
                else {
                    terminalizeRevision(revision, reason, now);
                }
            }
            if (jdbc.update("""
                    UPDATE local_feedback_batch
                    SET status = 'SUPERSEDED', completed_at_ms = ?,
                        error_message = ?
                    WHERE id = ? AND status IN ('FROZEN', 'QUEUED')
                    """, now.toEpochMilli(), reason, batch.id()) != 1) {
                throw conflict("queued Local feedback changed before carry-forward");
            }
        }

        for (Revision source : carryByThread.values()) {
            Revision latest = requireLatestRevision(source.threadId());
            if (latest.matches(current)) {
                continue;
            }
            if (Set.of("DRAFT", "PENDING").contains(latest.state())) {
                supersedePending(latest, reason, now);
            }
            appendRevision(
                    current, source.threadId(), source.authorKind(),
                    source.body(), true, UUID.randomUUID().toString(), now);
            jdbc.update("""
                    UPDATE pr_comment
                    SET resolved_at_ms = NULL, dismissed_at_ms = NULL,
                        resolved_by = NULL
                    WHERE id = ?
                    """, source.threadId());
        }
        updated(current.prId());
    }

    public record Submission(int submitted, String turnId) {}

    public AgentReviewRequest requestAgentReview(
            String taskId, String reviewId, String reviewRoundId, boolean blocking)
    {
        String taskIdValue = required(taskId, "taskId");
        String reviewIdValue = required(reviewId, "reviewId");
        String roundIdValue = required(reviewRoundId, "reviewRoundId");
        return inCommand(taskIdValue, () -> requestAgentReviewInCommand(
                taskIdValue, reviewIdValue, roundIdValue, blocking));
    }

    /** Continues from the exact preceding request and preserves its mode. */
    public AgentReviewRequest continueAgentReview(
            String taskId, String reviewId, String reviewRoundId)
    {
        String taskIdValue = required(taskId, "taskId");
        String reviewIdValue = required(reviewId, "reviewId");
        String roundIdValue = required(reviewRoundId, "reviewRoundId");
        return inCommand(taskIdValue, () -> {
            AgentReviewRequest exact = findAgentRequestForRound(roundIdValue)
                    .orElse(null);
            if (exact != null) {
                if (!reviewIdValue.equals(exact.reviewId())
                        || !taskIdValue.equals(exact.taskId())) {
                    throw conflict(
                            "Agent review round already has another Local Review owner");
                }
                return exact;
            }
            AgentReviewRequest previous = findPriorAgentRequest(
                    taskIdValue, reviewIdValue, roundIdValue)
                    .orElseThrow(() -> conflict(
                            "Agent review continuation has no exact prior Local Review request"));
            return requestAgentReviewInCommand(
                    taskIdValue, reviewIdValue, roundIdValue,
                    previous.blocking());
        });
    }

    private AgentReviewRequest requestAgentReviewInCommand(
            String taskId, String reviewId, String reviewRoundId, boolean blocking)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        AgentReviewRequest exact = findAgentRequestForRound(reviewRoundId).orElse(null);
        if (exact != null) {
            if (!exact.reviewId().equals(reviewId) || !exact.taskId().equals(taskId)
                    || exact.blocking() != blocking) {
                throw conflict("Agent review round already has another Local Review owner");
            }
            return exact;
        }
        String prId = jdbc.query("""
                SELECT review.pr_id
                FROM review_session review
                JOIN review_round round ON round.session_id = review.id
                WHERE review.id = ? AND review.owner_task_id = ?
                  AND round.id = ?
                """, (rs, row) -> rs.getString(1), reviewId, taskId, reviewRoundId)
                .stream().findFirst()
                .orElseThrow(() -> conflict(
                        "Agent review round does not belong to this Task"));
        Subject subject = requireSubject(taskId, prId);
        if (!"LOCAL_REVIEW".equals(subject.checkpoint())) {
            throw conflict("Agent review can start only from Local Review");
        }
        requireFreshBrain(subject);
        Instant now = clock.instant();
        AgentReviewRequest previous = findAgentRequest(reviewId).orElse(null);
        if (previous != null && "REQUESTED".equals(previous.status())) {
            staleAgentRequestInCommand(
                    previous,
                    "superseded by AgentReview round " + reviewRoundId,
                    now);
        }
        String requestId = id("local-agent-review-request", reviewRoundId);
        String blockerId = null;
        if (blocking) {
            blockerId = id("local-agent-review-blocker", reviewRoundId);
            insertBlocker(
                    blockerId, subject, requestId,
                    "LOCAL_AGENT_REVIEW_BLOCKING",
                    "blocking AgentReview round " + reviewRoundId, now);
        }
        jdbc.update("""
                INSERT INTO local_review_agent_request(
                    id, review_id, review_round_id, pr_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation,
                    dev_report_id, code_fingerprint, head_sha, base_sha,
                    mode, task_blocker_id, status, requested_by,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'REQUESTED', 'user', ?)
                """, requestId, reviewId, reviewRoundId, subject.prId(),
                subject.taskId(), subject.taskEpoch(), subject.stageId(),
                subject.stageGeneration(), subject.devReportId(),
                subject.codeFingerprint(), subject.headSha(), subject.baseSha(),
                blocking ? "BLOCKING" : "ADVISORY", blockerId,
                now.toEpochMilli());
        updated(subject.prId());
        return new AgentReviewRequest(
                requestId, reviewId, reviewRoundId, subject.taskId(),
                blocking, "REQUESTED");
    }

    public void cancelAgentReview(String reviewId, String reason)
    {
        String reviewIdValue = required(reviewId, "reviewId");
        AgentReviewRequest request = findAgentRequest(reviewIdValue).orElse(null);
        cancelAgentReviewRequest(request, reason);
    }

    public void cancelAgentReviewRound(
            String reviewId, String reviewRoundId, String reason)
    {
        String reviewIdValue = required(reviewId, "reviewId");
        AgentReviewRequest request = findAgentRequestForRound(
                required(reviewRoundId, "reviewRoundId")).orElse(null);
        if (request != null && !reviewIdValue.equals(request.reviewId())) {
            throw conflict("Agent review round belongs to another ReviewSession");
        }
        cancelAgentReviewRequest(request, reason);
    }

    private void cancelAgentReviewRequest(
            AgentReviewRequest request, String reason)
    {
        if (request == null || !"REQUESTED".equals(request.status())) {
            return;
        }
        inCommand(request.taskId(), () -> {
            Instant now = clock.instant();
            int changed = jdbc.update("""
                    UPDATE local_review_agent_request
                    SET status = 'CANCELED', completed_at_ms = ?,
                        completion_evidence = ?
                    WHERE id = ? AND status = 'REQUESTED'
                    """, now.toEpochMilli(), required(reason, "reason"), request.id());
            if (changed == 1) {
                resolveAgentRequestBlocker(
                        request.id(), "AgentReview canceled: " + reason, now);
                String prId = jdbc.queryForObject("""
                        SELECT pr_id FROM local_review_agent_request WHERE id = ?
                        """, String.class, request.id());
                updated(prId);
            }
            return null;
        });
    }

    public Submission importSelectedFindings(
            String reviewId, String reviewRoundId, List<String> findingIds)
    {
        String reviewIdValue = required(reviewId, "reviewId");
        String roundIdValue = required(reviewRoundId, "reviewRoundId");
        List<String> selectedIds = normalizeIds(findingIds);
        if (selectedIds.isEmpty()) {
            throw badRequest("at least one findingId is required");
        }
        AgentReviewRequest observed = findAgentRequestForRound(roundIdValue)
                .orElseThrow(() -> conflict(
                        "Agent review round is not attached to a V2 Local Review"));
        if (!reviewIdValue.equals(observed.reviewId())) {
            throw conflict("Agent review round belongs to another ReviewSession");
        }
        return inCommand(observed.taskId(), () -> {
            AgentReviewRequest request = findAgentRequestForRound(roundIdValue)
                    .orElseThrow();
            if ("IMPORTED".equals(request.status())) {
                return importedSubmission(request.id(), selectedIds);
            }
            if (!"REQUESTED".equals(request.status())) {
                throw conflict("Agent review request is no longer importable");
            }
            RequestSubject frozen = requireAgentRequestSubject(request.id());
            Subject current = requireSubject(request.taskId(), frozen.prId());
            if (!frozen.matches(current)) {
                throw conflict("Agent review is stale for the current code head");
            }
            Integer liveRounds = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM review_round
                    WHERE session_id = ? AND status IN ('QUEUED', 'RUNNING')
                    """, Integer.class, reviewIdValue);
            if (liveRounds != null && liveRounds > 0) {
                throw conflict("Agent review is still running");
            }
            List<Revision> revisions = new ArrayList<>();
            for (String findingId : selectedIds) {
                Finding finding = requireFinding(
                        reviewIdValue, roundIdValue, findingId, frozen.headSha());
                Revision existing = importedRevision(request.id(), finding.id())
                        .orElse(null);
                if (existing != null) {
                    revisions.add(existing);
                    continue;
                }
                String authorKind = frozen.blocking()
                        ? "BLOCKING_REVIEW" : "ADVISORY_REVIEW";
                String rendered = renderFinding(finding);
                PRComment comment = addCommentInCommand(
                        current, PRComment.ORIGIN_LOCAL,
                        finding.path() == null ? PRComment.SCOPE_PR
                                : PRComment.SCOPE_FILE_LINE,
                        finding.path(), finding.endLine(), "RIGHT",
                        finding.startLine(), "RIGHT", PRTimelineEntry.ACTOR_AGENT,
                        rendered, null, finding.id());
                Revision revision = requireLatestRevision(comment.id());
                if (!authorKind.equals(revision.authorKind())) {
                    throw new IllegalStateException(
                            "Agent finding source differs from its review request");
                }
                Instant importedAt = clock.instant();
                openRevisionBlocker(current, revision.id(), importedAt);
                jdbc.update("""
                        INSERT INTO local_review_imported_finding(
                            request_id, finding_id, thread_id,
                            comment_revision_id, imported_by, imported_at_ms)
                        VALUES (?, ?, ?, ?, 'user', ?)
                        """, request.id(), finding.id(), comment.id(),
                        revision.id(), importedAt.toEpochMilli());
                revisions.add(revision);
            }
            Instant frozenAt = clock.instant();
            FrozenBatch batch = freeze(
                    current, revisions, "REQUEST_CHANGES", frozenAt);
            if (jdbc.update("""
                    UPDATE local_review_agent_request
                    SET status = 'IMPORTED', completed_at_ms = ?,
                        completion_evidence = ?
                    WHERE id = ? AND status = 'REQUESTED'
                    """, frozenAt.toEpochMilli(),
                    "LocalFeedbackBatch:" + batch.id(),
                    request.id()) != 1) {
                throw new IllegalStateException("Agent review request changed before import");
            }
            resolveAgentRequestBlocker(
                    request.id(), "selected findings imported into " + batch.id(),
                    frozenAt);
            String turnId = admitBatchInCommand(batch.id());
            updated(current.prId());
            return new Submission(revisions.size(), turnId);
        });
    }

    private FrozenBatch freeze(
            Subject subject, List<Revision> selected, String verdict, Instant now)
    {
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("selected revisions are empty");
        }
        for (Revision revision : selected) {
            if (!revision.matches(subject) || !"PENDING".equals(revision.state())) {
                throw conflict("Local Review comment is stale or already submitted");
            }
        }
        List<String> rootIds = selected.stream().map(Revision::threadId).distinct().toList();
        String snapshots = write(selected.stream().map(revision -> Map.of(
                "threadId", revision.threadId(),
                "revisionId", revision.id(),
                "body", revision.body(),
                "bodyDigest", revision.bodyDigest())).toList());
        String eventId = UUID.randomUUID().toString();
        String submissionId = UUID.randomUUID().toString();
        int submissionSequence = nextInt("""
                SELECT COALESCE(MAX(submission_seq), 0) + 1
                FROM local_review_submission WHERE task_id = ?
                """, subject.taskId());
        jdbc.update("""
                INSERT INTO pr_timeline_event(
                    id, pr_id, event_type, actor, is_local_only,
                    created_at_ms, payload_json)
                VALUES (?, ?, 'review', 'user', 1, ?, ?)
                """, eventId, subject.prId(), now.toEpochMilli(), write(Map.of(
                        "reviewEvent", "submitted",
                        "verdict", verdict == null ? "COMMENT" : verdict,
                        "commentIds", rootIds,
                        "findingCount", rootIds.size())));
        jdbc.update("""
                INSERT INTO local_review_submission(
                    id, timeline_event_id, task_id, pr_id, agent_run_id,
                    submission_seq, root_ids_json, root_snapshot_json,
                    submitted_through_ms, attempt, failures, created_at_ms)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, 0, 0, ?)
                """, submissionId, eventId, subject.taskId(), subject.prId(),
                submissionSequence, write(rootIds), snapshots, now.toEpochMilli(),
                now.toEpochMilli());
        String batchId = UUID.randomUUID().toString();
        int batchSequence = nextInt("""
                SELECT COALESCE(MAX(sequence), 0) + 1
                FROM local_feedback_batch
                WHERE local_development_stage_id = ?
                """, subject.stageId());
        jdbc.update("""
                INSERT INTO local_feedback_batch(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, pr_id, dev_report_id,
                    source_submission_id, sequence, code_fingerprint,
                    head_sha, base_sha, status, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', ?)
                """, batchId, subject.stageId(), subject.taskId(),
                subject.taskEpoch(), subject.stageGeneration(), subject.prId(),
                subject.devReportId(), submissionId, batchSequence,
                subject.codeFingerprint(), subject.headSha(), subject.baseSha(),
                now.toEpochMilli());
        int position = 0;
        for (Revision revision : selected) {
            position++;
            String threadContent = frozenThread(revision.threadId());
            jdbc.update("""
                    INSERT INTO local_feedback_batch_item(
                        batch_id, position, thread_id, comment_revision_id,
                        body_digest, frozen_body, frozen_thread_content,
                        selected_by, selected_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'user', ?)
                    """, batchId, position, revision.threadId(), revision.id(),
                    revision.bodyDigest(), revision.body(), threadContent,
                    now.toEpochMilli());
            if (jdbc.update("""
                    UPDATE local_review_comment_revision
                    SET state = 'SUBMITTED', state_version = state_version + 1,
                        state_changed_at_ms = ?
                    WHERE id = ? AND state = 'PENDING'
                    """, now.toEpochMilli(), revision.id()) != 1) {
                throw conflict("Local Review comment changed while freezing feedback");
            }
        }
        String contentDigest = jdbc.queryForObject("""
                SELECT content_digest FROM local_feedback_batch_digest_v230
                WHERE batch_id = ?
                """, String.class, batchId);
        if (jdbc.update("""
                UPDATE local_feedback_batch
                SET status = 'FROZEN', frozen_at_ms = ?, content_digest = ?
                WHERE id = ? AND status = 'BUILDING'
                """, now.toEpochMilli(), contentDigest, batchId) != 1) {
            throw new IllegalStateException("Local feedback batch could not freeze");
        }
        jdbc.update("UPDATE pr SET local_review_epoch = local_review_epoch + 1 WHERE id = ?",
                subject.prId());
        return new FrozenBatch(batchId, submissionId, contentDigest);
    }

    private String admitBatchInCommand(String batchId)
    {
        Batch batch = jdbc.query("""
                SELECT id, task_id, local_development_stage_id, task_epoch,
                       stage_generation, pr_id, dev_report_id,
                       source_submission_id, content_digest,
                       code_fingerprint, head_sha, base_sha, status,
                       stage_turn_id
                FROM local_feedback_batch WHERE id = ?
                """, (rs, row) -> batch(rs), batchId).stream().findFirst()
                .orElseThrow(() -> conflict("unknown Local feedback batch"));
        TaskCommandExecutor.requireCurrent(batch.taskId());
        if (batch.stageTurnId() != null) {
            return batch.stageTurnId();
        }
        if (!Set.of("FROZEN", "QUEUED").contains(batch.status())) {
            throw conflict("Local feedback batch is no longer dispatchable");
        }
        Subject subject = requireSubject(batch.taskId(), batch.prId());
        if (!batch.matches(subject) || !"LOCAL_REVIEW".equals(subject.checkpoint())) {
            throw conflict("Local feedback batch is stale for the current Local Review");
        }
        requireFreshBrain(subject);
        String requestId = id("local-feedback-request", batch.id());
        String commandId = id("submit-local-feedback", batch.id());
        String turnId = id("local-feedback-turn", batch.id());
        String operationId = id("local-feedback-operation", batch.id());
        String ticketId = id("local-feedback-ticket", batch.id());
        Instant now = clock.instant();
        String prompt = feedbackPrompt(batch.id());
        WorkModel model = decodeWorkModel(subject.workModelSnapshot());
        if (!subject.provider().equals(model.agentOrProvider())
                || model.model() != null && !model.model().isBlank()
                    && !subject.model().equals(model.model())) {
            throw new IllegalStateException(
                    "Frozen Task Brain and work model do not identify one engine");
        }
        int laneMask = model.kind() == WorkModelKind.CLI ? 1 : 2;
        ObjectNode launch = writerLaunch(subject, model, turnId, operationId, prompt);
        if (steering != null) {
            StageCliContinuity.applyExact(
                    json, launch, subject.sourceStageTurnId(), model.kind(),
                    prompt, steering, new StageCliContinuity.Fence(
                            subject.stageId(), subject.stageGeneration(),
                            subject.codeFingerprint(), subject.headSha(),
                            subject.baseSha(), subject.provider(), subject.model(),
                            subject.worktreePath()));
        }
        jdbc.update("""
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES (?, ?, ?, 'ADDRESS_LOCAL_FEEDBACK', 'QUEUED', ?, 1, ?,
                    ?, ?, ?, ?, ?, ?)
                """, turnId, subject.stageId(), subject.stageGeneration(),
                operationId, subject.taskEpoch(), subject.codeFingerprint(),
                subject.headSha(), subject.baseSha(), model.kind().name(),
                write(launch), now.toEpochMilli());
        jdbc.update("""
                UPDATE local_feedback_batch SET stage_turn_id = ?
                WHERE id = ? AND stage_turn_id IS NULL
                  AND status IN ('FROZEN', 'QUEUED')
                """, turnId, batch.id());
        jdbc.update("""
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, predecessor_turn_id,
                    brain_review_episode_id, local_feedback_batch_id,
                    prompt_digest, requested_by, requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'LOCAL_FEEDBACK', 'IMMEDIATE',
                    NULL, NULL, ?, ?, 'user', ?)
                """, requestId, commandId, turnId, subject.taskId(),
                subject.stageId(), subject.taskEpoch(), subject.stageGeneration(),
                batch.id(), digest(prompt), now.toEpochMilli());
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch, stage_id,
                    stage_generation, attempt, expected_code_fingerprint,
                    expected_head_sha, expected_base_sha, status, created_at_ms)
                VALUES (?, ?, 'EXECUTE_STAGE_TURN', 'AGENT_TURN',
                    'STAGE_TURN', ?, 'STAGE_TURN_RESULT', ?, 0, 1, 1,
                    ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 'REQUESTED', ?)
                """, ticketId, operationId, turnId, laneMask,
                subject.workspaceId(), subject.trunkId(), subject.taskId(),
                subject.taskEpoch(), subject.stageId(), subject.stageGeneration(),
                subject.codeFingerprint(), subject.headSha(), subject.baseSha(),
                now.toEpochMilli());
        if (jdbc.update("""
                UPDATE local_feedback_batch SET status = 'DISPATCHED'
                WHERE id = ? AND status IN ('FROZEN', 'QUEUED')
                """, batch.id()) != 1) {
            throw new IllegalStateException("Local feedback batch changed before dispatch");
        }
        CommandResult<StageManager.State> moved = local.submitLocalFeedbackInCommand(
                new LocalDevelopmentStageManager.FeedbackCommand(
                        new StageManager.Command(
                                commandId, ACTOR, subject.taskId(), subject.taskEpoch(),
                                subject.stageId(), subject.stageGeneration(),
                                subject.stageVersion()),
                        batch.id(), batch.submissionId(), batch.contentDigest()));
        if (moved.disposition() != CommandResult.Disposition.APPLIED) {
            throw conflict("Local feedback transition was superseded");
        }
        ResultFence fence = new ResultFence(
                subject.taskEpoch(), subject.stageId(), subject.stageGeneration(),
                operationId, 1, subject.codeFingerprint(), subject.headSha(),
                subject.baseSha());
        CommandResult<StageManager.State> requested = local.requestLocalFeedbackFixInCommand(
                new StageManager.Command(
                        id("request-local-feedback-turn", batch.id()), ACTOR,
                        subject.taskId(), subject.taskEpoch(), subject.stageId(),
                        subject.stageGeneration(), moved.state().version()),
                fence, requestId);
        if (requested.disposition() != CommandResult.Disposition.APPLIED) {
            throw conflict("Local feedback Turn request was superseded");
        }
        return turnId;
    }

    public record AgentReviewRequest(
            String id, String reviewId, String reviewRoundId, String taskId,
            boolean blocking, String status) {}

    private Subject requireSubject(String taskId, String prId)
    {
        List<Subject> rows = jdbc.query("""
                SELECT task.id AS task_id, pull_request.id AS pr_id,
                       task.epoch AS task_epoch, owner.id AS stage_id,
                       owner.generation AS stage_generation,
                       owner.version AS stage_version, owner.checkpoint,
                       report.id AS dev_report_id,
                       report.stage_turn_id AS source_stage_turn_id,
                       report.code_fingerprint, report.head_sha, report.base_sha,
                       identity.worktree_path, creation.work_model_snapshot,
                       brain.provider, brain.model, brain.role_skill,
                       task.thread_id AS trunk_id, trunk.workspace_id
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN local_development_stage local ON local.stage_id = owner.id
                JOIN pr pull_request ON pull_request.task_id = task.id
                JOIN task_current_code_subject_v230 code ON code.task_id = task.id
                JOIN task_code_identity identity ON identity.task_id = task.id
                JOIN task_creation_context creation ON creation.task_id = task.id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN dev_report report ON report.id = (
                    SELECT candidate.id FROM dev_report candidate
                    WHERE candidate.workflow_version = 'V2'
                      AND candidate.local_development_stage_id = owner.id
                    ORDER BY candidate.revision DESC LIMIT 1)
                WHERE task.id = ?
                  AND (? IS NULL OR pull_request.id = ?)
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND pull_request.origin = 'task'
                  AND pull_request.status = 'local-open'
                  AND current.stage_generation = owner.generation
                  AND owner.kind = 'LOCAL_DEVELOPMENT'
                  AND owner.completed_at_ms IS NULL
                  AND local.task_id = task.id
                  AND local.generation = owner.generation
                  AND local.opened_for_epoch = task.epoch
                  AND report.task_epoch = task.epoch
                  AND report.stage_generation = owner.generation
                  AND report.code_fingerprint = code.code_fingerprint
                  AND report.head_sha = code.head_sha
                  AND report.base_sha = code.base_sha
                """, (rs, row) -> subject(rs), taskId, prId, prId);
        if (rows.size() != 1) {
            throw conflict("Task has no exact current V2 Local Review subject");
        }
        return rows.getFirst();
    }

    private void requireFreshBrain(Subject subject)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM brain_review_episode episode
                WHERE episode.task_id = ?
                  AND episode.task_epoch = ?
                  AND episode.local_development_stage_id = ?
                  AND episode.stage_generation = ?
                  AND episode.dev_report_id = ?
                  AND episode.code_fingerprint = ?
                  AND episode.expected_head_sha = ?
                  AND episode.expected_base_sha = ?
                  AND ((episode.status = 'SUCCEEDED'
                        AND episode.verdict = 'APPROVED'
                        AND episode.unresolved_finding_count = 0)
                    OR episode.status = 'BUDGET_EXHAUSTED')
                """, Integer.class, subject.taskId(), subject.taskEpoch(),
                subject.stageId(), subject.stageGeneration(), subject.devReportId(),
                subject.codeFingerprint(), subject.headSha(), subject.baseSha());
        if (count == null || count != 1) {
            throw conflict("Local Review requires a fresh exact-head Brain verdict");
        }
    }

    private List<Revision> pendingUserRoots(Subject subject)
    {
        return jdbc.query("""
                SELECT revision.id, revision.thread_id, revision.revision,
                       revision.author_kind, revision.body, revision.body_digest,
                       revision.state, revision.dev_report_id,
                       revision.task_id, revision.task_epoch,
                       revision.local_development_stage_id,
                       revision.stage_generation, revision.code_fingerprint,
                       revision.head_sha, revision.base_sha
                FROM local_review_comment_revision revision
                WHERE revision.task_id = ?
                  AND revision.local_development_stage_id = ?
                  AND revision.task_epoch = ?
                  AND revision.stage_generation = ?
                  AND revision.dev_report_id = ?
                  AND revision.code_fingerprint = ?
                  AND revision.head_sha = ? AND revision.base_sha = ?
                  AND revision.author_kind = 'USER'
                  AND revision.state = 'PENDING'
                  AND NOT EXISTS (
                      SELECT 1 FROM local_review_comment_revision newer
                      WHERE newer.thread_id = revision.thread_id
                        AND newer.revision > revision.revision)
                ORDER BY revision.created_at_ms, revision.id
                """, (rs, row) -> revision(rs), subject.taskId(),
                subject.stageId(), subject.taskEpoch(), subject.stageGeneration(),
                subject.devReportId(), subject.codeFingerprint(),
                subject.headSha(), subject.baseSha());
    }

    private List<Revision> requestedRevisions(
            Subject subject, List<String> commentIds)
    {
        LinkedHashSet<String> threads = new LinkedHashSet<>();
        for (String commentId : commentIds) {
            String threadId = requireThreadId(commentId);
            Revision revision = requireLatestRevision(threadId);
            if (!revision.matches(subject) || !"PENDING".equals(revision.state())) {
                throw conflict("comment " + commentId
                        + " is stale or not current actionable Local Review feedback");
            }
            threads.add(threadId);
        }
        return threads.stream().map(this::requireLatestRevision).toList();
    }

    private Optional<Submission> duplicateSubmission(
            Subject subject, List<String> commentIds)
    {
        LinkedHashSet<String> batchIds = new LinkedHashSet<>();
        LinkedHashSet<String> requestedThreads = new LinkedHashSet<>();
        for (String commentId : commentIds) {
            String threadId;
            try {
                threadId = requireThreadId(commentId);
            }
            catch (RuntimeException missing) {
                return Optional.empty();
            }
            Revision latest = requireLatestRevision(threadId);
            if (!latest.matches(subject) || !"SUBMITTED".equals(latest.state())) {
                return Optional.empty();
            }
            requestedThreads.add(threadId);
            List<String> ids = jdbc.query("""
                    SELECT batch.id
                    FROM local_feedback_batch_item item
                    JOIN local_feedback_batch batch ON batch.id = item.batch_id
                    WHERE item.comment_revision_id = ?
                      AND batch.status IN ('FROZEN', 'QUEUED', 'DISPATCHED')
                    """, (rs, row) -> rs.getString(1), latest.id());
            if (ids.size() != 1) {
                return Optional.empty();
            }
            batchIds.add(ids.getFirst());
        }
        if (batchIds.size() != 1) {
            return Optional.empty();
        }
        String batchId = batchIds.getFirst();
        Set<String> frozenThreads = Set.copyOf(jdbc.query("""
                SELECT thread_id FROM local_feedback_batch_item WHERE batch_id = ?
                """, (rs, row) -> rs.getString(1), batchId));
        if (!frozenThreads.equals(Set.copyOf(requestedThreads))) {
            return Optional.empty();
        }
        Batch batch = requireBatch(batchId);
        return Optional.of(new Submission(requestedThreads.size(), batch.stageTurnId()));
    }

    private Submission importedSubmission(String requestId, List<String> findingIds)
    {
        List<String> imported = jdbc.query("""
                SELECT finding_id FROM local_review_imported_finding
                WHERE request_id = ? ORDER BY finding_id
                """, (rs, row) -> rs.getString(1), requestId);
        if (!Set.copyOf(imported).equals(Set.copyOf(findingIds))) {
            throw conflict("Agent review was already imported with another selection");
        }
        List<String> batches = jdbc.query("""
                SELECT DISTINCT item.batch_id
                FROM local_review_imported_finding imported
                JOIN local_feedback_batch_item item
                  ON item.comment_revision_id = imported.comment_revision_id
                WHERE imported.request_id = ?
                """, (rs, row) -> rs.getString(1), requestId);
        if (batches.size() != 1) {
            throw new IllegalStateException("imported findings lack one frozen batch");
        }
        Batch batch = requireBatch(batches.getFirst());
        return new Submission(imported.size(), batch.stageTurnId());
    }

    private void insertThread(
            Subject subject, PRComment comment, String source, Instant now)
    {
        String scope = PRComment.SCOPE_PR.equals(comment.scope()) ? "PR" : "FILE_LINE";
        Integer start = comment.startLine() == null
                ? comment.lineNumber() : comment.startLine();
        jdbc.update("""
                INSERT INTO local_review_thread(
                    id, pr_id, task_id, local_development_stage_id,
                    task_epoch, stage_generation, scope, file_path,
                    start_line, end_line, source, created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, comment.id(), subject.prId(), subject.taskId(),
                subject.stageId(), subject.taskEpoch(), subject.stageGeneration(),
                scope, comment.filePath(),
                "PR".equals(scope) ? null : start,
                "PR".equals(scope) ? null : comment.lineNumber(),
                source, comment.author(), now.toEpochMilli());
    }

    private Revision appendRevision(
            Subject subject,
            String threadId,
            String authorKind,
            String body,
            boolean actionable,
            String revisionId,
            Instant now)
    {
        Revision previous = latestRevision(threadId).orElse(null);
        int number = previous == null ? 1 : previous.number() + 1;
        jdbc.update("""
                INSERT INTO local_review_comment_revision(
                    id, thread_id, task_id, local_development_stage_id,
                    task_epoch, stage_generation, dev_report_id, revision,
                    previous_revision_id, author_kind, body, body_digest,
                    code_fingerprint, head_sha, base_sha, state,
                    state_version, created_at_ms, state_changed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING', 0, ?, ?)
                """, revisionId, threadId, subject.taskId(), subject.stageId(),
                subject.taskEpoch(), subject.stageGeneration(), subject.devReportId(),
                number, previous == null ? null : previous.id(), authorKind,
                body, digest(body), subject.codeFingerprint(), subject.headSha(),
                subject.baseSha(), now.toEpochMilli(), now.toEpochMilli());
        Revision inserted = requireLatestRevision(threadId);
        if (actionable) {
            openRevisionBlocker(subject, inserted.id(), now);
        }
        return inserted;
    }

    private void supersedePending(Revision revision, String reason, Instant now)
    {
        if (!Set.of("DRAFT", "PENDING").contains(revision.state())) {
            return;
        }
        if (jdbc.update("""
                UPDATE local_review_comment_revision
                SET state = 'SUPERSEDED', state_version = state_version + 1,
                    state_changed_at_ms = ?, resolution_reason = ?,
                    terminal_at_ms = ?
                WHERE id = ? AND state IN ('DRAFT', 'PENDING')
                """, now.toEpochMilli(), reason, now.toEpochMilli(),
                revision.id()) != 1) {
            throw conflict("Local Review comment changed before revision");
        }
        resolveBlocker(revision.id(), reason, now);
    }

    private void terminalizeRevision(Revision revision, String reason, Instant now)
    {
        if (jdbc.update("""
                UPDATE local_review_comment_revision
                SET state = 'DISMISSED', state_version = state_version + 1,
                    state_changed_at_ms = ?, resolution_reason = ?,
                    terminal_at_ms = ?
                WHERE id = ? AND state IN ('DRAFT', 'PENDING', 'SUBMITTED')
                """, now.toEpochMilli(), reason, now.toEpochMilli(),
                revision.id()) != 1) {
            throw conflict("Local Review comment is already terminal");
        }
        resolveBlocker(revision.id(), reason, now);
    }

    private void openRevisionBlocker(
            Subject subject, String revisionId, Instant now)
    {
        String blockerId = id("local-feedback-blocker", revisionId);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_blocker WHERE id = ?",
                Integer.class, blockerId);
        if (count != null && count > 0) {
            return;
        }
        insertBlocker(blockerId, subject, revisionId, "LOCAL_FEEDBACK_OPEN",
                "open Local Review revision " + revisionId, now);
    }

    private void insertBlocker(
            String blockerId,
            Subject subject,
            String subjectRevision,
            String type,
            String payload,
            Instant now)
    {
        jdbc.update("""
                INSERT INTO task_blocker(
                    id, task_id, stage_id, owner_kind, owner_id,
                    subject_revision, blocker_type, status, payload_json,
                    opened_at_ms)
                VALUES (?, ?, ?, 'STAGE', ?, ?, ?, 'OPEN', ?, ?)
                """, blockerId, subject.taskId(), subject.stageId(),
                subject.stageId(), subjectRevision, type, payload,
                now.toEpochMilli());
    }

    private void resolveBlocker(String revisionId, String evidence, Instant now)
    {
        jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE subject_revision = ?
                  AND blocker_type = 'LOCAL_FEEDBACK_OPEN'
                  AND status = 'OPEN'
                """, now.toEpochMilli(), evidence, revisionId);
    }

    private void staleAgentRequestInCommand(
            AgentReviewRequest request, String evidence, Instant now)
    {
        if (jdbc.update("""
                UPDATE local_review_agent_request
                SET status = 'STALE', completed_at_ms = ?,
                    completion_evidence = ?
                WHERE id = ? AND status = 'REQUESTED'
                """, now.toEpochMilli(), evidence, request.id()) != 1) {
            throw conflict("Agent review request changed before invalidation");
        }
        resolveAgentRequestBlocker(request.id(), evidence, now);
    }

    private void resolveAgentRequestBlocker(
            String requestId, String evidence, Instant now)
    {
        jdbc.update("""
                UPDATE task_blocker
                SET status = 'RESOLVED', resolved_at_ms = ?,
                    resolution_evidence = ?
                WHERE id = (
                    SELECT task_blocker_id FROM local_review_agent_request
                    WHERE id = ?)
                  AND status = 'OPEN'
                """, now.toEpochMilli(), evidence, requestId);
    }

    private String sourceFor(Subject subject, String author)
    {
        if (PRTimelineEntry.ACTOR_USER.equals(author)) {
            return "USER";
        }
        if (PRTimelineEntry.ACTOR_BRAIN.equals(author)) {
            return "BRAIN";
        }
        if (PRTimelineEntry.ACTOR_AGENT.equals(author)) {
            List<String> sources = jdbc.query("""
                    SELECT CASE mode WHEN 'BLOCKING' THEN 'BLOCKING_REVIEW'
                           ELSE 'ADVISORY_REVIEW' END
                    FROM local_review_agent_request
                    WHERE task_id = ? AND pr_id = ? AND status = 'REQUESTED'
                      AND task_epoch = ?
                      AND local_development_stage_id = ?
                      AND stage_generation = ?
                      AND dev_report_id = ?
                      AND code_fingerprint = ?
                      AND head_sha = ? AND base_sha = ?
                    """, (rs, row) -> rs.getString(1), subject.taskId(),
                    subject.prId(), subject.taskEpoch(), subject.stageId(),
                    subject.stageGeneration(), subject.devReportId(),
                    subject.codeFingerprint(), subject.headSha(), subject.baseSha());
            if (sources.size() != 1) {
                throw conflict(
                        "Agent local comment requires one exact active AgentReview request");
            }
            return sources.getFirst();
        }
        return "DEVELOPMENT";
    }

    private Optional<AgentReviewRequest> findPriorAgentRequest(
            String taskId, String reviewId, String reviewRoundId)
    {
        return jdbc.query("""
                SELECT request.id, request.review_id, request.review_round_id,
                       request.task_id, request.mode, request.status
                FROM review_round target
                JOIN review_session review ON review.id = target.session_id
                JOIN local_review_agent_request request
                  ON request.review_id = review.id
                 AND request.task_id = review.owner_task_id
                JOIN review_round prior ON prior.id = request.review_round_id
                WHERE target.id = ? AND target.session_id = ?
                  AND review.owner_task_id = ?
                  AND prior.session_id = target.session_id
                  AND prior.rowid < target.rowid
                  AND NOT EXISTS (
                      SELECT 1
                      FROM local_review_agent_request later
                      JOIN review_round later_round
                        ON later_round.id = later.review_round_id
                      WHERE later.review_id = review.id
                        AND later_round.rowid >= target.rowid)
                ORDER BY prior.rowid DESC
                LIMIT 1
                """, (rs, row) -> new AgentReviewRequest(
                        rs.getString("id"), rs.getString("review_id"),
                        rs.getString("review_round_id"),
                        rs.getString("task_id"),
                        "BLOCKING".equals(rs.getString("mode")),
                        rs.getString("status")),
                reviewRoundId, reviewId, taskId).stream().findFirst();
    }

    private Optional<AgentReviewRequest> findAgentRequest(String reviewId)
    {
        return jdbc.query("""
                SELECT request.id, request.review_id, request.review_round_id,
                       request.task_id, request.mode, request.status
                FROM local_review_agent_request request
                JOIN review_round round ON round.id = request.review_round_id
                WHERE request.review_id = ?
                ORDER BY round.created_at_ms DESC,
                         request.requested_at_ms DESC, request.id DESC
                LIMIT 1
                """, (rs, row) -> new AgentReviewRequest(
                        rs.getString("id"), rs.getString("review_id"),
                        rs.getString("review_round_id"),
                        rs.getString("task_id"),
                        "BLOCKING".equals(rs.getString("mode")),
                        rs.getString("status")), reviewId).stream().findFirst();
    }

    private Optional<AgentReviewRequest> findAgentRequestForRound(String roundId)
    {
        return jdbc.query("""
                SELECT id, review_id, review_round_id, task_id, mode, status
                FROM local_review_agent_request WHERE review_round_id = ?
                """, (rs, row) -> new AgentReviewRequest(
                        rs.getString("id"), rs.getString("review_id"),
                        rs.getString("review_round_id"), rs.getString("task_id"),
                        "BLOCKING".equals(rs.getString("mode")),
                        rs.getString("status")), roundId).stream().findFirst();
    }

    private RequestSubject requireAgentRequestSubject(String requestId)
    {
        return jdbc.query("""
                SELECT id, pr_id, task_id, task_epoch,
                       local_development_stage_id, stage_generation,
                       dev_report_id, code_fingerprint, head_sha, base_sha, mode
                FROM local_review_agent_request WHERE id = ?
                """, (rs, row) -> new RequestSubject(
                        rs.getString("id"), rs.getString("pr_id"),
                        rs.getString("task_id"), rs.getLong("task_epoch"),
                        rs.getString("local_development_stage_id"),
                        rs.getLong("stage_generation"),
                        rs.getString("dev_report_id"),
                        rs.getString("code_fingerprint"), rs.getString("head_sha"),
                        rs.getString("base_sha"),
                        "BLOCKING".equals(rs.getString("mode"))), requestId)
                .stream().findFirst().orElseThrow();
    }

    private Finding requireFinding(
            String reviewId, String reviewRoundId, String findingId, String headSha)
    {
        List<Finding> rows = jdbc.query("""
                SELECT finding.id, finding.path, finding.start_line,
                       finding.end_line, finding.claim, finding.severity,
                       finding.requested_action
                FROM finding
                JOIN review_round round ON round.id = finding.round_id
                WHERE finding.id = ? AND finding.session_id = ?
                  AND finding.round_id = ?
                  AND lower(finding.lifecycle_status) <> 'dropped'
                  AND finding.last_checked_commit = ?
                  AND round.session_id = ?
                  AND round.status LIKE 'COMPLETED%'
                  AND round.end_commit = ?
                """, (rs, row) -> new Finding(
                        rs.getString("id"), rs.getString("path"),
                        integer(rs, "start_line"), integer(rs, "end_line"),
                        rs.getString("claim"), rs.getInt("severity"),
                        rs.getString("requested_action")),
                findingId, reviewId, reviewRoundId, headSha, reviewId, headSha);
        if (rows.size() != 1) {
            throw conflict("finding " + findingId
                    + " is stale, dropped, or not from this exact completed review round");
        }
        Finding finding = rows.getFirst();
        if (finding.path() != null && (finding.startLine() == null
                || finding.endLine() == null || finding.startLine() <= 0
                || finding.endLine() < finding.startLine())) {
            throw conflict("finding " + findingId + " has no valid local anchor");
        }
        return finding;
    }

    private Optional<Revision> importedRevision(String requestId, String findingId)
    {
        return jdbc.query("""
                SELECT revision.id, revision.thread_id, revision.revision,
                       revision.author_kind, revision.body, revision.body_digest,
                       revision.state, revision.dev_report_id,
                       revision.task_id, revision.task_epoch,
                       revision.local_development_stage_id,
                       revision.stage_generation, revision.code_fingerprint,
                       revision.head_sha, revision.base_sha
                FROM local_review_imported_finding imported
                JOIN local_review_comment_revision revision
                  ON revision.id = imported.comment_revision_id
                WHERE imported.request_id = ? AND imported.finding_id = ?
                """, (rs, row) -> revision(rs), requestId, findingId)
                .stream().findFirst();
    }

    private static String renderFinding(Finding finding)
    {
        return "[Agent review severity " + finding.severity() + "] "
                + finding.claim() + "\n\nRequested action: "
                + finding.requestedAction();
    }

    private Batch requireBatch(String batchId)
    {
        return jdbc.query("""
                SELECT id, task_id, local_development_stage_id, task_epoch,
                       stage_generation, pr_id, dev_report_id,
                       source_submission_id, content_digest,
                       code_fingerprint, head_sha, base_sha, status,
                       stage_turn_id
                FROM local_feedback_batch WHERE id = ?
                """, (rs, row) -> batch(rs), batchId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "missing Local feedback batch " + batchId));
    }

    private String feedbackPrompt(String batchId)
    {
        List<String> items = jdbc.query("""
                SELECT position, frozen_thread_content
                FROM local_feedback_batch_item
                WHERE batch_id = ? ORDER BY position
                """, (rs, row) -> rs.getInt("position") + ". "
                        + rs.getString("frozen_thread_content"), batchId);
        if (items.isEmpty()) {
            throw new IllegalStateException("Local feedback batch is empty");
        }
        return "Address every item in this immutable Local Review batch against "
                + "the supplied exact code subject:\n\n"
                + String.join("\n\n", items)
                + "\n\nDo not push, publish, or modify another Task. Return only "
                + "strict JSON with schemaVersion=1 and string fields "
                + "implementedIntent, commitSummary, fileSummary, "
                + "validationSummary, knownRisks, unresolvedConcerns, contextRefs.";
    }

    private String frozenThread(String threadId)
    {
        List<Map<String, Object>> comments = jdbc.query("""
                SELECT id, author, body, file_path, line_number,
                       parent_comment_id, created_at_ms
                FROM pr_comment
                WHERE id = ? OR parent_comment_id = ?
                ORDER BY created_at_ms, id
                """, (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", rs.getString("id"));
                    value.put("author", rs.getString("author"));
                    value.put("body", rs.getString("body"));
                    value.put("filePath", rs.getString("file_path"));
                    value.put("lineNumber", integer(rs, "line_number"));
                    value.put("parentCommentId", rs.getString("parent_comment_id"));
                    value.put("createdAtMs", rs.getLong("created_at_ms"));
                    return value;
                }, threadId, threadId);
        if (comments.isEmpty()) {
            Revision revision = requireLatestRevision(threadId);
            return write(Map.of(
                    "threadId", threadId,
                    "body", revision.body(),
                    "bodyDigest", revision.bodyDigest()));
        }
        return write(comments);
    }

    private ObjectNode writerLaunch(
            Subject subject,
            WorkModel model,
            String turnId,
            String operationId,
            String prompt)
    {
        ObjectNode launch = json.createObjectNode();
        launch.put("schemaVersion", 1);
        launch.put("transport", model.kind().name());
        launch.put("provider", subject.provider());
        putNullable(launch, "credentialAccount", model.account());
        launch.put("model", subject.model());
        putNullable(launch, "reasoningEffort", model.reasoningEffort());
        launch.put("workingDirectory", subject.worktreePath());
        String system = "You are the code-writing Stage owner for V2 Local "
                + "Development. Work only in the supplied Task worktree and "
                + "address the frozen review batch. Do not push, publish, merge, "
                + "or mutate another Task.";
        if (subject.roleSkill() != null && !subject.roleSkill().isBlank()) {
            system += "\n\nRole skill:\n" + subject.roleSkill();
        }
        launch.put("systemPrompt", system);
        launch.put("prompt", prompt);
        ObjectNode endpoint = launch.putObject("toolEndpoint");
        endpoint.put("serverName", "bytequay");
        endpoint.put("url", "http://127.0.0.1:" + serverPort
                + "/api/v2/stage-turns/" + turnId
                + "/operations/" + operationId + "/mcp");
        endpoint.put("ownerKind", "STAGE_TURN");
        endpoint.put("ownerId", turnId);
        endpoint.put("operationId", operationId);
        endpoint.put("profile", "STAGE_DEVELOPMENT");
        endpoint.put("approvalPromptTool", "mcp__bytequay__approval_prompt");
        return launch;
    }

    private WorkModel decodeWorkModel(String value)
    {
        try {
            return workModelReader.readValue(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Frozen work model is invalid", e);
        }
    }

    private void insertComment(PRComment comment)
    {
        jdbc.update("""
                INSERT INTO pr_comment(
                    id, pr_id, origin, scope, file_path, line_number,
                    author, body, created_at_ms, resolved_at_ms,
                    dismissed_at_ms, stripped_on_push_at_ms,
                    parent_comment_id, published_at_ms, side,
                    start_line, start_side, finding_id, resolved_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL,
                    ?, NULL, ?, ?, ?, ?, NULL)
                """, comment.id(), comment.prId(), comment.origin(),
                comment.scope(), comment.filePath(), comment.lineNumber(),
                comment.author(), comment.body(), comment.createdAt().toEpochMilli(),
                comment.parentCommentId(), comment.side(), comment.startLine(),
                comment.startSide(), comment.findingId());
    }

    private void insertTimelineComment(
            String prId, String commentId, String author, Instant now)
    {
        jdbc.update("""
                INSERT INTO pr_timeline_event(
                    id, pr_id, event_type, actor, is_local_only,
                    created_at_ms, payload_json)
                VALUES (?, ?, 'comment', ?, 1, ?, ?)
                """, UUID.randomUUID().toString(), prId, author,
                now.toEpochMilli(), write(Map.of("commentId", commentId)));
    }

    private PRComment requireComment(String commentId)
    {
        return jdbc.query("""
                SELECT id, pr_id, origin, scope, file_path, line_number,
                       author, body, created_at_ms, resolved_at_ms,
                       dismissed_at_ms, stripped_on_push_at_ms,
                       parent_comment_id, published_at_ms, side,
                       start_line, start_side, finding_id, resolved_by
                FROM pr_comment WHERE id = ?
                """, (rs, row) -> comment(rs), commentId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown comment: " + commentId));
    }

    private String requireCommentTask(String commentId)
    {
        List<String> tasks = jdbc.query("""
                SELECT DISTINCT thread.task_id
                FROM local_review_thread thread
                LEFT JOIN local_review_comment_revision revision
                  ON revision.thread_id = thread.id
                LEFT JOIN pr_comment comment ON comment.id = ?
                WHERE thread.id = ? OR revision.id = ?
                   OR comment.parent_comment_id = thread.id
                """, (rs, row) -> rs.getString(1), commentId, commentId, commentId);
        if (tasks.size() != 1) {
            throw new IllegalArgumentException("comment is not V2 Local Review feedback");
        }
        return tasks.getFirst();
    }

    private String requireThreadId(String commentId)
    {
        List<String> threads = jdbc.query("""
                SELECT DISTINCT thread.id
                FROM local_review_thread thread
                LEFT JOIN local_review_comment_revision revision
                  ON revision.thread_id = thread.id
                LEFT JOIN pr_comment comment ON comment.id = ?
                WHERE thread.id = ? OR revision.id = ?
                   OR comment.parent_comment_id = thread.id
                """, (rs, row) -> rs.getString(1), commentId, commentId, commentId);
        if (threads.size() != 1) {
            throw new IllegalArgumentException(
                    "comment is not an exact V2 Local Review thread");
        }
        return threads.getFirst();
    }

    private static String rootId(PRComment comment)
    {
        return comment.parentCommentId() == null
                ? comment.id() : comment.parentCommentId();
    }

    private Optional<Revision> latestRevision(String threadId)
    {
        return jdbc.query("""
                SELECT id, thread_id, revision, author_kind, body, body_digest,
                       state, dev_report_id, task_id, task_epoch,
                       local_development_stage_id, stage_generation,
                       code_fingerprint, head_sha, base_sha
                FROM local_review_comment_revision
                WHERE thread_id = ? ORDER BY revision DESC LIMIT 1
                """, (rs, row) -> revision(rs), threadId).stream().findFirst();
    }

    private Revision requireLatestRevision(String threadId)
    {
        return latestRevision(threadId).orElseThrow(() ->
                new IllegalStateException("Local Review thread has no revision"));
    }

    private <T> T inCommand(String taskId, Supplier<T> work)
    {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TaskCommandExecutor.requireCurrent(taskId);
            return work.get();
        }
        return commands.execute(taskId, work);
    }

    private void updated(String prId)
    {
        if (prId == null) {
            return;
        }
        Runnable publish = () -> events.publishEvent(new PrUpdatedEvent(prId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization()
                    {
                        @Override
                        public void afterCommit()
                        {
                            publish.run();
                        }
                    });
            return;
        }
        publish.run();
    }

    private int nextInt(String sql, Object parameter)
    {
        Integer value = jdbc.queryForObject(sql, Integer.class, parameter);
        return requireNonNull(value, "sequence is null");
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize Local Review value", e);
        }
    }

    private static void putNullable(ObjectNode node, String name, String value)
    {
        if (value == null || value.isBlank()) {
            node.putNull(name);
        }
        else {
            node.put(name, value);
        }
    }

    private static List<String> normalizeIds(List<String> ids)
    {
        if (ids == null) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        ids.stream().filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isEmpty()).forEach(values::add);
        return List.copyOf(values);
    }

    private static String required(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw badRequest(name + " is required");
        }
        return value.strip();
    }

    private static ResponseStatusException badRequest(String message)
    {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static Subject subject(ResultSet rs) throws SQLException
    {
        return new Subject(
                rs.getString("task_id"), rs.getString("pr_id"),
                rs.getLong("task_epoch"), rs.getString("stage_id"),
                rs.getLong("stage_generation"), rs.getLong("stage_version"),
                rs.getString("checkpoint"), rs.getString("dev_report_id"),
                rs.getString("source_stage_turn_id"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("worktree_path"),
                rs.getString("work_model_snapshot"), rs.getString("provider"),
                rs.getString("model"), rs.getString("role_skill"),
                rs.getString("trunk_id"), rs.getString("workspace_id"));
    }

    private static Revision revision(ResultSet rs) throws SQLException
    {
        return new Revision(
                rs.getString("id"), rs.getString("thread_id"),
                rs.getInt("revision"), rs.getString("author_kind"),
                rs.getString("body"), rs.getString("body_digest"),
                rs.getString("state"), rs.getString("dev_report_id"),
                rs.getString("task_id"), rs.getLong("task_epoch"),
                rs.getString("local_development_stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"));
    }

    private static Batch batch(ResultSet rs) throws SQLException
    {
        return new Batch(
                rs.getString("id"), rs.getString("task_id"),
                rs.getString("local_development_stage_id"),
                rs.getLong("task_epoch"), rs.getLong("stage_generation"),
                rs.getString("pr_id"), rs.getString("dev_report_id"),
                rs.getString("source_submission_id"),
                rs.getString("content_digest"),
                rs.getString("code_fingerprint"), rs.getString("head_sha"),
                rs.getString("base_sha"), rs.getString("status"),
                rs.getString("stage_turn_id"));
    }

    private static PRComment comment(ResultSet rs) throws SQLException
    {
        return new PRComment(
                rs.getString("id"), rs.getString("pr_id"),
                rs.getString("origin"), rs.getString("scope"),
                rs.getString("file_path"), integer(rs, "line_number"),
                rs.getString("author"), rs.getString("body"),
                instant(rs, "created_at_ms"), instant(rs, "resolved_at_ms"),
                instant(rs, "dismissed_at_ms"),
                instant(rs, "stripped_on_push_at_ms"),
                rs.getString("parent_comment_id"),
                instant(rs, "published_at_ms"), rs.getString("side"),
                integer(rs, "start_line"), rs.getString("start_side"),
                rs.getString("finding_id"), rs.getString("resolved_by"));
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private record Subject(
            String taskId, String prId, long taskEpoch, String stageId,
            long stageGeneration, long stageVersion, String checkpoint,
            String devReportId, String sourceStageTurnId,
            String codeFingerprint, String headSha,
            String baseSha, String worktreePath, String workModelSnapshot,
            String provider, String model, String roleSkill,
            String trunkId, String workspaceId) {}

    private record Revision(
            String id, String threadId, int number, String authorKind,
            String body, String bodyDigest, String state, String devReportId,
            String taskId, long taskEpoch, String stageId,
            long stageGeneration, String codeFingerprint,
            String headSha, String baseSha)
    {
        private boolean matches(Subject subject)
        {
            return taskId.equals(subject.taskId())
                    && taskEpoch == subject.taskEpoch()
                    && stageId.equals(subject.stageId())
                    && stageGeneration == subject.stageGeneration()
                    && devReportId.equals(subject.devReportId())
                    && codeFingerprint.equals(subject.codeFingerprint())
                    && headSha.equals(subject.headSha())
                    && baseSha.equals(subject.baseSha());
        }
    }

    private record FrozenBatch(String id, String submissionId, String contentDigest) {}

    private record Batch(
            String id, String taskId, String stageId, long taskEpoch,
            long stageGeneration, String prId, String devReportId,
            String submissionId, String contentDigest, String codeFingerprint,
            String headSha, String baseSha, String status, String stageTurnId)
    {
        private boolean matches(Subject subject)
        {
            return taskId.equals(subject.taskId())
                    && taskEpoch == subject.taskEpoch()
                    && stageId.equals(subject.stageId())
                    && stageGeneration == subject.stageGeneration()
                    && prId.equals(subject.prId())
                    && devReportId.equals(subject.devReportId())
                    && codeFingerprint.equals(subject.codeFingerprint())
                    && headSha.equals(subject.headSha())
                    && baseSha.equals(subject.baseSha());
        }
    }

    private record RequestSubject(
            String id, String prId, String taskId, long taskEpoch,
            String stageId, long stageGeneration, String devReportId,
            String codeFingerprint, String headSha, String baseSha,
            boolean blocking)
    {
        private boolean matches(Subject subject)
        {
            return taskId.equals(subject.taskId())
                    && prId.equals(subject.prId())
                    && taskEpoch == subject.taskEpoch()
                    && stageId.equals(subject.stageId())
                    && stageGeneration == subject.stageGeneration()
                    && devReportId.equals(subject.devReportId())
                    && codeFingerprint.equals(subject.codeFingerprint())
                    && headSha.equals(subject.headSha())
                    && baseSha.equals(subject.baseSha());
        }
    }

    private record Finding(
            String id, String path, Integer startLine, Integer endLine,
            String claim, int severity, String requestedAction) {}
}
