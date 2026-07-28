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

import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectDraft;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.PayloadKind;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ReviewRoundState;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Product adapter for listing and explicitly authorizing V2 Remote feedback. */
@Component
public final class V2RemoteFeedbackControlService
{
    private static final int EFFECT_ATTEMPT_LIMIT = 3;
    private static final String USER = "user";

    private final JdbcTemplate jdbc;
    private final TaskCommandExecutor commands;
    private final SqliteRemoteDevelopmentRuntimeStore remote;
    private final Clock clock;

    @Autowired
    public V2RemoteFeedbackControlService(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteRemoteDevelopmentRuntimeStore remote)
    {
        this(jdbc, commands, remote, Clock.systemUTC());
    }

    V2RemoteFeedbackControlService(
            JdbcTemplate jdbc,
            TaskCommandExecutor commands,
            SqliteRemoteDevelopmentRuntimeStore remote,
            Clock clock)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<String> findTaskId(String batchId)
    {
        return jdbc.query("""
                SELECT batch.task_id
                FROM remote_feedback_batch batch
                JOIN tasks task ON task.id = batch.task_id
                WHERE batch.id = ? AND task.workflow_version = 'V2'
                """, (rs, row) -> rs.getString(1), batchId).stream().findFirst();
    }

    /** A Task's immutable Remote feedback batches, newest first. */
    public List<ReviewRound> findByTask(String taskId)
    {
        return rows("WHERE batch.task_id = ?", taskId).stream()
                .map(this::project)
                .toList();
    }

    /** Explicit user command; duplicate approval reuses the frozen authorization. */
    public ReviewRound approve(String batchId)
    {
        String taskId = findTaskId(batchId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no V2 Remote feedback batch " + batchId));
        try {
            return commands.execute(taskId, () -> approveInCommand(taskId, batchId));
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Remote feedback approval is stale or incomplete", failure);
        }
    }

    private ReviewRound approveInCommand(String taskId, String batchId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        if (hasAuthorization(batchId)) {
            return requireRound(batchId);
        }

        ApprovalGate gate = requireApprovalGate(batchId, taskId);
        String authorizationId = id("remote-feedback-user-authorization", batchId);
        List<EffectDraft> effects = gate.drafts().stream()
                .map(draft -> effect(authorizationId, batchId, draft))
                .collect(Collectors.toCollection(ArrayList::new));
        if (!gate.proposedHeadSha().equals(gate.batchHeadSha())) {
            int ordinal = effects.size() + 1;
            effects.add(new EffectDraft(
                    id("remote-feedback-effect", authorizationId + ":" + ordinal),
                    EffectKind.PUSH_COMMITS, null, null, null, PayloadKind.PUSH,
                    gate.proposedHeadSha(),
                    "remote-feedback:" + batchId + ":" + ordinal + ":PUSH_COMMITS",
                    EFFECT_ATTEMPT_LIMIT));
        }
        remote.authorizeFeedback(
                authorizationId, batchId, USER, "approve Remote feedback batch",
                List.copyOf(effects), clock.instant());
        return requireRound(batchId);
    }

    private ApprovalGate requireApprovalGate(String batchId, String taskId)
    {
        List<ApprovalSubject> subjects = jdbc.query("""
                SELECT batch.head_sha, validation.proposed_head_sha,
                       repair.id AS repair_id
                FROM remote_feedback_batch batch
                JOIN tasks task ON task.id = batch.task_id
                JOIN remote_feedback_validation_evidence validation
                  ON validation.remote_feedback_batch_id = batch.id
                 AND validation.passed = 1
                JOIN remote_feedback_repair_result repair
                  ON repair.remote_feedback_batch_id = batch.id
                 AND repair.repair_stage_turn_id = validation.repair_stage_turn_id
                 AND repair.proposed_head_sha = validation.proposed_head_sha
                WHERE batch.id = ? AND batch.task_id = ?
                  AND task.workflow_version = 'V2'
                  AND batch.status = 'AWAITING_APPROVAL'
                """, (rs, row) -> new ApprovalSubject(
                        rs.getString("head_sha"),
                        rs.getString("proposed_head_sha"),
                        rs.getString("repair_id")), batchId, taskId);
        if (subjects.size() != 1) {
            throw new IllegalStateException(
                    "Remote feedback is not at one accepted repair gate");
        }
        ApprovalSubject subject = subjects.getFirst();
        List<ReplyDraft> drafts = jdbc.query("""
                SELECT draft.id, draft.ordinal, draft.kind, draft.body,
                       COALESCE(draft.external_target, item.external_target)
                           AS external_target,
                       item.remote_inbox_item_id
                FROM remote_feedback_reply_draft draft
                JOIN remote_feedback_batch_item item
                  ON item.remote_feedback_batch_id = draft.remote_feedback_batch_id
                 AND item.ordinal = draft.batch_item_ordinal
                JOIN remote_inbox_item inbox
                  ON inbox.id = item.remote_inbox_item_id
                 AND inbox.task_id = ?
                 AND inbox.remote_development_stage_id = (
                     SELECT remote_development_stage_id
                     FROM remote_feedback_batch WHERE id = ?)
                WHERE draft.remote_feedback_batch_id = ?
                  AND draft.repair_result_id = ?
                ORDER BY draft.ordinal
                """, (rs, row) -> new ReplyDraft(
                        rs.getString("id"), rs.getInt("ordinal"),
                        EffectKind.valueOf(rs.getString("kind")),
                        rs.getString("body"), rs.getString("external_target"),
                        rs.getString("remote_inbox_item_id")),
                taskId, batchId, batchId, subject.repairId());
        return new ApprovalGate(
                subject.batchHeadSha(), subject.proposedHeadSha(), drafts);
    }

    private static EffectDraft effect(
            String authorizationId, String batchId, ReplyDraft draft)
    {
        PayloadKind payloadKind;
        String payload;
        switch (draft.kind()) {
            case POST_INLINE_REPLY, POST_TOP_LEVEL_REPLY -> {
                payloadKind = PayloadKind.TEXT;
                payload = requireText(draft.body(), "Remote reply body");
            }
            case RESOLVE_THREAD -> {
                payloadKind = PayloadKind.RESOLUTION;
                payload = requireText(
                        draft.externalTarget(), "Remote thread target");
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported Remote reply effect " + draft.kind());
        }
        return new EffectDraft(
                id("remote-feedback-effect", authorizationId + ":" + draft.id()),
                draft.kind(), draft.remoteInboxItemId(), draft.externalTarget(),
                null, payloadKind, payload,
                "remote-feedback:" + batchId + ":" + draft.ordinal() + ":"
                        + draft.kind(),
                EFFECT_ATTEMPT_LIMIT);
    }

    private boolean hasAuthorization(String batchId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM remote_feedback_authorization
                WHERE remote_feedback_batch_id = ?
                """, Integer.class, batchId);
        return count != null && count == 1;
    }

    private ReviewRound requireRound(String batchId)
    {
        List<RoundRow> rows = rows("WHERE batch.id = ?", batchId);
        if (rows.size() != 1) {
            throw new IllegalStateException("V2 Remote feedback batch disappeared");
        }
        return project(rows.getFirst());
    }

    private List<RoundRow> rows(String predicate, Object argument)
    {
        return jdbc.query("""
                SELECT batch.id, batch.task_id, batch.sequence, batch.status,
                       batch.item_count, batch.created_at_ms,
                       batch.completed_at_ms,
                       validation.completed_at_ms AS gated_at_ms,
                       validation.repair_stage_turn_id,
                       authorization.id AS authorization_id,
                       authorization.authorized_at_ms,
                       brain.verdict AS brain_verdict,
                       COALESCE((
                           SELECT MAX(request.semantic_attempt)
                           FROM remote_feedback_stage_turn_request request
                           WHERE request.remote_feedback_batch_id = batch.id), 0)
                           AS iteration,
                       COALESCE((
                           SELECT COUNT(DISTINCT draft.batch_item_ordinal)
                           FROM remote_feedback_reply_draft draft
                           WHERE draft.repair_result_id = repair.id
                             AND draft.kind IN (
                                 'POST_INLINE_REPLY', 'POST_TOP_LEVEL_REPLY')), 0)
                           AS replied_count,
                       COALESCE((
                           SELECT COUNT(DISTINCT resolved.batch_item_ordinal)
                           FROM remote_feedback_reply_draft resolved
                           WHERE resolved.repair_result_id = repair.id
                             AND resolved.kind = 'RESOLVE_THREAD'
                             AND NOT EXISTS (
                                 SELECT 1 FROM remote_feedback_reply_draft reply
                                 WHERE reply.repair_result_id = repair.id
                                   AND reply.batch_item_ordinal =
                                       resolved.batch_item_ordinal
                                   AND reply.kind IN (
                                       'POST_INLINE_REPLY',
                                       'POST_TOP_LEVEL_REPLY'))), 0)
                           AS fixed_count,
                       MAX(0, batch.item_count - COALESCE((
                           SELECT COUNT(DISTINCT draft.batch_item_ordinal)
                           FROM remote_feedback_reply_draft draft
                           WHERE draft.repair_result_id = repair.id), 0))
                           AS open_count
                FROM remote_feedback_batch batch
                JOIN tasks task ON task.id = batch.task_id
                             AND task.workflow_version = 'V2'
                LEFT JOIN remote_feedback_validation_evidence validation
                  ON validation.remote_feedback_batch_id = batch.id
                LEFT JOIN remote_feedback_repair_result repair
                  ON repair.remote_feedback_batch_id = batch.id
                 AND repair.repair_stage_turn_id = validation.repair_stage_turn_id
                LEFT JOIN remote_feedback_authorization authorization
                  ON authorization.remote_feedback_batch_id = batch.id
                LEFT JOIN remote_feedback_brain_review_evidence brain
                  ON brain.remote_feedback_batch_id = batch.id
                """ + predicate + " ORDER BY batch.sequence DESC",
                (rs, row) -> roundRow(rs), argument);
    }

    private ReviewRound project(RoundRow row)
    {
        return new ReviewRound(
                row.id(), row.taskId(), row.sequence(), reviewers(row.id()),
                state(row.status()),
                new ReviewRound.ReviewRoundStats(
                        row.fixed(), row.replied(), 0, row.open()),
                null, row.openedAt(), row.gatedAt(), row.postedAt(),
                ReviewRound.ORIGIN_EXTERNAL, brainVerdict(row.brainVerdict()),
                row.iteration(), ReviewRound.DEFAULT_BRAIN_BUDGET, null,
                null, 0, row.iteration(), row.authorizationId() == null ? 0 : 1,
                row.authorizationId(),
                "SUPERSEDED".equals(row.status()) ? row.completedAt() : null);
    }

    private List<String> reviewers(String batchId)
    {
        return jdbc.query("""
                SELECT actor_login FROM (
                    SELECT DISTINCT inbox.actor_login
                    FROM remote_feedback_batch_item item
                    JOIN remote_inbox_item inbox
                      ON inbox.id = item.remote_inbox_item_id
                    WHERE item.remote_feedback_batch_id = ?
                      AND inbox.actor_login IS NOT NULL
                      AND TRIM(inbox.actor_login) <> ''
                    ORDER BY inbox.actor_login)
                """, (rs, row) -> "@" + rs.getString(1), batchId);
    }

    private static RoundRow roundRow(ResultSet rs)
            throws SQLException
    {
        return new RoundRow(
                rs.getString("id"), rs.getString("task_id"),
                rs.getInt("sequence"), rs.getString("status"),
                rs.getInt("fixed_count"), rs.getInt("replied_count"),
                rs.getInt("open_count"), rs.getInt("iteration"),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                instant(rs, "gated_at_ms"), instant(rs, "authorized_at_ms"),
                instant(rs, "completed_at_ms"), rs.getString("brain_verdict"),
                rs.getString("authorization_id"));
    }

    private static Instant instant(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static ReviewRoundState state(String status)
    {
        return switch (status) {
            case "BUILDING", "FROZEN" -> ReviewRoundState.TRIAGING;
            case "ADDRESSING" -> ReviewRoundState.ADDRESSING;
            case "AWAITING_APPROVAL" -> ReviewRoundState.AWAITING_GATE;
            case "AUTHORIZED", "APPLYING", "COMPLETED" -> ReviewRoundState.POSTED;
            case "SUPERSEDED" -> ReviewRoundState.CLOSED;
            default -> throw new IllegalStateException(
                    "Unknown Remote feedback batch status " + status);
        };
    }

    private static String brainVerdict(String verdict)
    {
        if (verdict == null) {
            return null;
        }
        return switch (verdict) {
            case "APPROVED" -> ReviewRound.VERDICT_APPROVED;
            case "CHANGES_REQUESTED" -> ReviewRound.VERDICT_CHANGES_REQUESTED;
            default -> null;
        };
    }

    private static String requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    private record ApprovalSubject(
            String batchHeadSha, String proposedHeadSha, String repairId) {}

    private record ReplyDraft(
            String id,
            int ordinal,
            EffectKind kind,
            String body,
            String externalTarget,
            String remoteInboxItemId) {}

    private record ApprovalGate(
            String batchHeadSha, String proposedHeadSha, List<ReplyDraft> drafts) {}

    private record RoundRow(
            String id,
            String taskId,
            int sequence,
            String status,
            int fixed,
            int replied,
            int open,
            int iteration,
            Instant openedAt,
            Instant gatedAt,
            Instant postedAt,
            Instant completedAt,
            String brainVerdict,
            String authorizationId) {}
}
