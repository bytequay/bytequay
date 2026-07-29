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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler.CommentAction;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable proposal, authorization, recovery, and finalization ledger. */
@Repository
public class SqliteReviewBuildCommentStore
        implements ReviewBuildCommentOperationHandler.OperationStore
{
    private static final int ATTEMPT_LIMIT = 3;
    private static final int OBSERVATION_LIMIT = 60;
    private static final Duration OBSERVATION_WINDOW = Duration.ofMinutes(5);
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqliteDispatchWakeStore wakes;
    private final ObjectMapper json;
    private final ObjectReader payloadReader;

    public SqliteReviewBuildCommentStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            SqliteDispatchWakeStore wakes,
            ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
        this.wakes = requireNonNull(wakes, "wakes is null");
        this.json = requireNonNull(json, "json is null");
        this.payloadReader = json.readerFor(ActionPayload.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public Optional<ProposalView> findProposal(String reviewPassId)
    {
        requireText(reviewPassId, "reviewPassId");
        return jdbc.query("""
                SELECT proposal.thread_id, proposal.review_pass_id,
                       proposal.selection_digest, proposal.decision,
                       proposal.decision_command_id,
                       selection.repo_full_name, selection.pr_number,
                       selection.reviewed_head_sha,
                       action.status AS action_status,
                       action.external_effect_id, action.evidence,
                       action.last_error, action.finalized_at_ms
                FROM review_build_comment_proposal_v287 proposal
                JOIN review_build_selection selection
                  ON selection.thread_id = proposal.thread_id
                LEFT JOIN review_build_comment_action_v287 action
                  ON action.thread_id = proposal.thread_id
                WHERE proposal.review_pass_id = ?
                """, (rs, row) -> proposal(rs), reviewPassId)
                .stream().findFirst();
    }

    public ProposalView approve(
            String reviewPassId, String commandId, Instant now)
    {
        requireText(reviewPassId, "reviewPassId");
        requireText(commandId, "commandId");
        requireNonNull(now, "now is null");
        return requireNonNull(transactions.execute(ignored -> {
            ProposalView proposal = requireProposal(reviewPassId);
            if (proposal.decision() != null) {
                if ("APPROVE".equals(proposal.decision())
                        && commandId.equals(proposal.commandId())) {
                    requireActionByThread(proposal.threadId());
                    return requireProposal(reviewPassId);
                }
                throw new IllegalStateException(
                        "review build comment proposal already has a different decision");
            }

            Subject subject = requireSubject(proposal.threadId());
            ActionPayload payload = payload(proposal.items());
            String payloadJson = encode(payload);
            String actionId = "review-build-comment-action-" + id(
                    "review-build-comment-action",
                    proposal.threadId() + ":" + commandId);
            String operationId = id(
                    "review-build-comment-operation", actionId);
            String ticketId = id("review-build-comment-ticket", actionId);
            jdbc.update("""
                    INSERT INTO review_build_comment_action_v287(
                        id, operation_id, thread_id, review_pass_id,
                        command_id, workspace_id, remote_repository_id,
                        head_repository_id, remote_pr_number, branch_name,
                        expected_head_sha, payload_json, payload_digest,
                        semantic_attempt, status, attempt_count, attempt_limit,
                        observation_count, observation_limit, authorized_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1,
                        'REQUESTED', 0, ?, 0, ?, ?)
                    """, actionId, operationId, subject.threadId(),
                    subject.reviewPassId(), commandId, subject.workspaceId(),
                    subject.remoteRepositoryId(), subject.headRepositoryId(),
                    subject.pullRequestNumber(), subject.branchName(),
                    subject.expectedHeadSha(), payloadJson,
                    digest(payloadJson), ATTEMPT_LIMIT, OBSERVATION_LIMIT,
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
                    VALUES (?, ?, 'APPLY_REVIEW_BUILD_COMMENTS',
                        'GITHUB_EFFECT', 'TRUNK', ?,
                        'REVIEW_BUILD_COMMENT_RESULT', 32,
                        1, 0, 0, ?, ?, NULL, NULL, NULL, NULL, 1,
                        NULL, ?, NULL, 'REQUESTED', ?)
                    """, ticketId, operationId, subject.threadId(),
                    subject.workspaceId(), subject.threadId(),
                    subject.expectedHeadSha(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO review_build_comment_dispatch_v287(
                        action_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES (?, ?, ?, ?)
                    """, actionId, ticketId, operationId, now.toEpochMilli());
            jdbc.update("""
                    UPDATE review_build_comment_proposal_v287
                    SET decision = 'APPROVE', decision_command_id = ?,
                        decided_at_ms = ?
                    WHERE thread_id = ? AND decision IS NULL
                    """, commandId, now.toEpochMilli(), subject.threadId());
            wakes.enqueue(ticketId, now);
            return requireProposal(reviewPassId);
        }), "review build comment approval returned null");
    }

    public ProposalView discard(
            String reviewPassId, String commandId, Instant now)
    {
        requireText(reviewPassId, "reviewPassId");
        requireText(commandId, "commandId");
        requireNonNull(now, "now is null");
        return requireNonNull(transactions.execute(ignored -> {
            ProposalView proposal = requireProposal(reviewPassId);
            if (proposal.decision() != null) {
                if ("DISCARD".equals(proposal.decision())
                        && commandId.equals(proposal.commandId())) {
                    return proposal;
                }
                throw new IllegalStateException(
                        "review build comment proposal already has a different decision");
            }
            int changed = jdbc.update("""
                    UPDATE review_build_comment_proposal_v287
                    SET decision = 'DISCARD', decision_command_id = ?,
                        decided_at_ms = ?
                    WHERE thread_id = ? AND decision IS NULL
                    """, commandId, now.toEpochMilli(), proposal.threadId());
            if (changed != 1) {
                throw new IllegalStateException(
                        "review build comment discard lost its decision fence");
            }
            return requireProposal(reviewPassId);
        }), "review build comment discard returned null");
    }

    @Override
    public CommentAction require(String operationId)
    {
        return findByOperationId(operationId).orElseThrow(() ->
                new IllegalStateException(
                        "exact review build comment action is missing"));
    }

    public Optional<CommentAction> findByOperationId(String operationId)
    {
        return jdbc.query("""
                SELECT action.*
                FROM review_build_comment_action_v287 action
                JOIN review_build_comment_dispatch_v287 dispatch
                  ON dispatch.action_id = action.id
                WHERE action.operation_id = ?
                  AND dispatch.operation_id = action.operation_id
                """, (rs, row) -> action(rs), operationId)
                .stream().findFirst();
    }

    public CommentAction requireActionByThread(String threadId)
    {
        List<CommentAction> rows = jdbc.query("""
                SELECT * FROM review_build_comment_action_v287
                WHERE thread_id = ?
                """, (rs, row) -> action(rs), threadId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "exact review build comment action is missing");
        }
        return rows.getFirst();
    }

    @Override
    public CommentAction claim(
            String actionId,
            int expectedAttemptCount,
            ClaimMode mode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        return requireNonNull(transactions.execute(ignored -> {
            CommentAction current = requireActionById(actionId);
            boolean reclaimForProbe = current.status() == ActionStatus.CLAIMED
                    && mode == ClaimMode.PROBE;
            if (current.attemptCount() != expectedAttemptCount
                    || (!reclaimForProbe && !claimable(current.status()))) {
                throw new IllegalStateException(
                        "review build comment action claim lost or exhausted");
            }
            if ((!reclaimForProbe
                    && current.attemptCount() >= current.attemptLimit())
                    || !sameSubject(current)) {
                abandon(current, claimedAt,
                        "review build comment authorization is stale or exhausted");
                return requireActionById(actionId);
            }
            int changed = reclaimForProbe
                    ? jdbc.update("""
                            UPDATE review_build_comment_action_v287
                            SET claim_mode = 'PROBE', claim_owner = ?,
                                claimed_at_ms = ?, lease_until_ms = ?
                            WHERE id = ? AND status = 'CLAIMED'
                              AND attempt_count = ? AND lease_until_ms <= ?
                            """, claimOwner, claimedAt.toEpochMilli(),
                            leaseUntil.toEpochMilli(), actionId,
                            expectedAttemptCount, claimedAt.toEpochMilli())
                    : jdbc.update("""
                            UPDATE review_build_comment_action_v287
                            SET status = 'CLAIMED',
                                attempt_count = attempt_count + 1,
                                claim_mode = ?, claim_owner = ?,
                                claimed_at_ms = ?, lease_until_ms = ?,
                                external_effect_id = NULL, evidence = NULL,
                                last_error = NULL, completed_at_ms = NULL
                            WHERE id = ? AND attempt_count = ?
                              AND status IN (
                                  'REQUESTED', 'FAILED', 'INDETERMINATE')
                              AND attempt_count < attempt_limit
                            """, mode.name(), claimOwner,
                            claimedAt.toEpochMilli(), leaseUntil.toEpochMilli(),
                            actionId, expectedAttemptCount);
            if (changed != 1) {
                throw new IllegalStateException(
                        "review build comment action claim lost or exhausted");
            }
            return requireActionById(actionId);
        }), "review build comment claim returned null");
    }

    @Override
    public void recordRecoveryBaseline(
            String actionId, int attempt, List<String> remoteEffectIds)
    {
        requireNonNull(remoteEffectIds, "remoteEffectIds is null");
        int changed = jdbc.update("""
                UPDATE review_build_comment_action_v287
                SET recovery_baseline_json = ?
                WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                  AND recovery_baseline_json IS NULL
                """, encode(remoteEffectIds), actionId, attempt);
        if (changed != 1) {
            CommentAction current = requireActionById(actionId);
            if (current.attemptCount() != attempt
                    || current.recoveryBaseline() == null
                    || !current.recoveryBaseline().equals(remoteEffectIds)) {
                throw new IllegalStateException(
                        "review build comment baseline persistence lost");
            }
        }
    }

    @Override
    public boolean deferProbe(
            String actionId, int attempt, Instant observedAt,
            Instant retryAt, String evidence)
    {
        requireNonNull(observedAt, "observedAt is null");
        requireNonNull(retryAt, "retryAt is null");
        requireNonNull(evidence, "evidence is null");
        return requireNonNull(transactions.execute(ignored -> {
            jdbc.update("""
                    UPDATE review_build_comment_action_v287
                    SET observation_started_at_ms = ?,
                        observation_deadline_ms = ?
                    WHERE id = ? AND status = 'CLAIMED'
                      AND attempt_count = ?
                      AND observation_started_at_ms IS NULL
                      AND observation_deadline_ms IS NULL
                    """, observedAt.toEpochMilli(),
                    observedAt.plus(OBSERVATION_WINDOW).toEpochMilli(),
                    actionId, attempt);
            int changed = jdbc.update("""
                    UPDATE review_build_comment_action_v287
                    SET observation_count = observation_count + 1,
                        status = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN 'ABANDONED' ELSE status END,
                        claim_mode = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN NULL ELSE 'PROBE' END,
                        claim_owner = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN NULL ELSE claim_owner END,
                        claimed_at_ms = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN NULL ELSE claimed_at_ms END,
                        lease_until_ms = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN NULL ELSE ? END,
                        evidence = ?,
                        last_error = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN ? ELSE NULL END,
                        completed_at_ms = CASE
                            WHEN observation_count + 1 >= observation_limit
                              OR ? >= observation_deadline_ms
                            THEN ? ELSE NULL END
                    WHERE id = ? AND status = 'CLAIMED'
                      AND attempt_count = ?
                    """, observedAt.toEpochMilli(), observedAt.toEpochMilli(),
                    observedAt.toEpochMilli(), observedAt.toEpochMilli(),
                    observedAt.toEpochMilli(), retryAt.toEpochMilli(), evidence,
                    observedAt.toEpochMilli(),
                    "suggested-change review observation budget exhausted",
                    observedAt.toEpochMilli(), observedAt.toEpochMilli(),
                    actionId, attempt);
            if (changed != 1) {
                throw new IllegalStateException(
                        "suggested-change review propagation wait lost its exact claim");
            }
            return requireActionById(actionId).status()
                    != ActionStatus.ABANDONED;
        }), "review build comment propagation deferral returned null");
    }

    @Override
    public void finishSucceeded(
            String actionId, int attempt, String externalEffectId,
            String evidence, Instant completedAt)
    {
        finish(actionId, attempt, "SUCCEEDED", externalEffectId, evidence,
                null, completedAt);
    }

    @Override
    public void finishFailed(
            String actionId, int attempt, String error, Instant completedAt)
    {
        finish(actionId, attempt, "FAILED", null, null, error, completedAt);
    }

    @Override
    public void finishIndeterminate(
            String actionId, int attempt, String evidence, Instant completedAt)
    {
        finish(actionId, attempt, "INDETERMINATE", null, evidence,
                evidence, completedAt);
    }

    @Override
    public void finishCanceled(
            String actionId, int attempt, String error, Instant completedAt)
    {
        finish(actionId, attempt, "CANCELED", null, null, error, completedAt);
    }

    public CommentAction terminalizeDeliveryFailure(
            String operationId,
            ActionStatus terminalStatus,
            String detail,
            Instant completedAt)
    {
        if (terminalStatus != ActionStatus.CANCELED
                && terminalStatus != ActionStatus.ABANDONED) {
            throw new IllegalArgumentException(
                    "delivery failure must be canceled or abandoned");
        }
        return requireNonNull(transactions.execute(ignored -> {
            CommentAction action = require(operationId);
            if (action.status() == terminalStatus) {
                return action;
            }
            int changed = jdbc.update("""
                    UPDATE review_build_comment_action_v287
                    SET status = ?, claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        external_effect_id = NULL, evidence = NULL,
                        last_error = ?, completed_at_ms = ?
                    WHERE id = ? AND status IN (
                        'REQUESTED', 'FAILED', 'INDETERMINATE')
                    """, terminalStatus.name(), detail,
                    completedAt.toEpochMilli(), action.id());
            if (changed != 1) {
                throw new IllegalStateException(
                        "review build comment failure delivery is stale");
            }
            return require(operationId);
        }), "review build comment terminalization returned null");
    }

    public List<CommentAction> findCommittedUnfinalized(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT action.*
                FROM review_build_comment_action_v287 action
                JOIN review_build_comment_dispatch_v287 dispatch
                  ON dispatch.action_id = action.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = dispatch.dispatch_ticket_id
                WHERE action.status IN ('SUCCEEDED', 'CANCELED', 'ABANDONED')
                  AND action.finalized_at_ms IS NULL
                  AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                ORDER BY ticket.completed_at_ms, action.id
                LIMIT ?
                """, (rs, row) -> action(rs), limit);
    }

    public void finalizeAction(
            String actionId, ActionStatus status, Instant finalizedAt)
    {
        transactions.executeWithoutResult(ignored -> {
            CommentAction action = requireActionById(actionId);
            if (isFinalized(actionId)) {
                if (action.status() != status) {
                    throw new IllegalStateException(
                            "review build comment finalization status changed");
                }
                return;
            }
            if (action.status() != status
                    || (status != ActionStatus.SUCCEEDED
                    && status != ActionStatus.CANCELED
                    && status != ActionStatus.ABANDONED)) {
                throw new IllegalStateException(
                        "review build comment action is not finalizable");
            }
            int resolved = 0;
            if (status == ActionStatus.SUCCEEDED) {
                resolved = jdbc.update("""
                        UPDATE review_findings
                        SET status = 'resolved', resolution = ?
                        WHERE status IN ('agreed', 'arbitrated')
                          AND EXISTS (
                              SELECT 1
                              FROM review_build_comment_proposal_item_v287 item
                              WHERE item.thread_id = ?
                                AND item.finding_id = review_findings.id
                                AND item.finding_revision = review_findings.revision)
                        """, "review_build_comment:" + action.id(),
                        action.threadId());
            }
            int changed = jdbc.update("""
                    UPDATE review_build_comment_action_v287
                    SET finalized_at_ms = ?, resolved_count = ?
                    WHERE id = ? AND status = ? AND finalized_at_ms IS NULL
                      AND completed_at_ms IS NOT NULL
                    """, finalizedAt.toEpochMilli(), resolved, actionId,
                    status.name());
            if (changed != 1) {
                throw new IllegalStateException(
                        "review build comment finalization is stale");
            }
        });
    }

    private ProposalView requireProposal(String reviewPassId)
    {
        return findProposal(reviewPassId).orElseThrow(() ->
                new IllegalStateException(
                        "review pass has no suggested-change proposal"));
    }

    private ProposalView proposal(ResultSet rs)
            throws SQLException
    {
        String threadId = rs.getString("thread_id");
        String decision = rs.getString("decision");
        String actionStatus = rs.getString("action_status");
        String status;
        if (decision == null) {
            status = "PENDING";
        }
        else if ("DISCARD".equals(decision)) {
            status = "DISCARDED";
        }
        else if (ActionStatus.SUCCEEDED.name().equals(actionStatus)
                && rs.getObject("finalized_at_ms") != null) {
            status = "PUBLISHED";
        }
        else if (ActionStatus.CANCELED.name().equals(actionStatus)
                || ActionStatus.ABANDONED.name().equals(actionStatus)) {
            status = "FAILED";
        }
        else {
            status = "APPROVED";
        }
        return new ProposalView(
                threadId, rs.getString("review_pass_id"),
                rs.getString("repo_full_name"), rs.getInt("pr_number"),
                rs.getString("reviewed_head_sha"),
                rs.getString("selection_digest"), status, decision,
                rs.getString("decision_command_id"),
                actionStatus == null ? null : ActionStatus.valueOf(actionStatus),
                rs.getString("external_effect_id"), rs.getString("evidence"),
                rs.getString("last_error"),
                items(threadId));
    }

    private List<ProposalItem> items(String threadId)
    {
        return jdbc.query("""
                SELECT position, finding_id, kind, path, line, body
                FROM review_build_comment_proposal_item_v287
                WHERE thread_id = ? ORDER BY position
                """, (rs, row) -> new ProposalItem(
                rs.getInt("position"), rs.getString("finding_id"),
                rs.getString("kind"), rs.getString("path"),
                (Integer) rs.getObject("line"), rs.getString("body")),
                threadId);
    }

    private Subject requireSubject(String threadId)
    {
        return jdbc.query("""
                SELECT selection.thread_id, selection.review_pass_id,
                       selection.workspace_id,
                       selection.base_repository_id,
                       selection.head_repository_id, selection.pr_number,
                       selection.head_ref, selection.reviewed_head_sha
                FROM review_build_selection selection
                JOIN review_build_comment_proposal_v287 proposal
                  ON proposal.thread_id = selection.thread_id
                JOIN threads trunk ON trunk.id = selection.thread_id
                WHERE selection.thread_id = ?
                  AND selection.spawn_mode = 'suggested_change'
                  AND proposal.decision IS NULL
                  AND trunk.turn_version = 'V2' AND trunk.flow = 'build'
                  AND trunk.lifecycle_state IN ('ACTIVE', 'IDLE')
                  AND NOT EXISTS (
                      SELECT 1 FROM tasks task
                      WHERE task.thread_id = selection.thread_id)
                """, (rs, row) -> new Subject(
                rs.getString("thread_id"), rs.getString("review_pass_id"),
                rs.getString("workspace_id"),
                rs.getString("base_repository_id"),
                rs.getString("head_repository_id"), rs.getInt("pr_number"),
                rs.getString("head_ref"), rs.getString("reviewed_head_sha")),
                threadId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "suggested-change proposal lost its zero-Task Trunk"));
    }

    private boolean sameSubject(CommentAction action)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM review_build_selection selection
                JOIN review_build_comment_proposal_v287 proposal
                  ON proposal.thread_id = selection.thread_id
                JOIN threads trunk ON trunk.id = selection.thread_id
                WHERE selection.thread_id = ?
                  AND selection.review_pass_id = ?
                  AND proposal.decision = 'APPROVE'
                  AND proposal.decision_command_id = ?
                  AND selection.workspace_id = ?
                  AND lower(selection.base_repository_id) = lower(?)
                  AND lower(selection.head_repository_id) = lower(?)
                  AND selection.pr_number = ?
                  AND selection.head_ref = ?
                  AND selection.reviewed_head_sha = ?
                  AND trunk.turn_version = 'V2' AND trunk.flow = 'build'
                  AND NOT EXISTS (
                      SELECT 1 FROM tasks task
                      WHERE task.thread_id = selection.thread_id)
                """, Integer.class, action.threadId(), action.reviewPassId(),
                action.commandId(), action.workspaceId(),
                action.remoteRepositoryId(), action.headRepositoryId(),
                action.pullRequestNumber(), action.branchName(),
                action.expectedHeadSha());
        return count != null && count == 1;
    }

    private CommentAction requireActionById(String actionId)
    {
        List<CommentAction> rows = jdbc.query("""
                SELECT * FROM review_build_comment_action_v287 WHERE id = ?
                """, (rs, row) -> action(rs), actionId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "exact review build comment action is missing");
        }
        return rows.getFirst();
    }

    private CommentAction action(ResultSet rs)
            throws SQLException
    {
        String payloadJson = rs.getString("payload_json");
        return new CommentAction(
                rs.getString("id"), rs.getString("operation_id"),
                ActionStatus.valueOf(rs.getString("status")),
                rs.getInt("semantic_attempt"), rs.getInt("attempt_count"),
                rs.getInt("attempt_limit"), rs.getString("thread_id"),
                rs.getString("review_pass_id"), rs.getString("command_id"),
                rs.getString("workspace_id"),
                rs.getString("remote_repository_id"),
                rs.getString("head_repository_id"),
                rs.getInt("remote_pr_number"), rs.getString("branch_name"),
                rs.getString("expected_head_sha"), payloadJson,
                rs.getString("payload_digest"), decodePayload(payloadJson),
                Instant.ofEpochMilli(rs.getLong("authorized_at_ms")),
                decodeBaseline(rs.getString("recovery_baseline_json")),
                rs.getString("external_effect_id"), rs.getString("evidence"));
    }

    private void abandon(CommentAction action, Instant now, String detail)
    {
        int changed = jdbc.update("""
                UPDATE review_build_comment_action_v287
                SET status = 'ABANDONED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, external_effect_id = NULL,
                    evidence = NULL, last_error = ?, completed_at_ms = ?
                WHERE id = ? AND status IN (
                    'REQUESTED', 'FAILED', 'INDETERMINATE', 'CLAIMED')
                """, detail, now.toEpochMilli(), action.id());
        if (changed != 1) {
            throw new IllegalStateException(
                    "review build comment abandonment lost");
        }
    }

    private void finish(
            String actionId, int attempt, String status,
            String externalEffectId, String evidence, String error,
            Instant completedAt)
    {
        int changed = jdbc.update("""
                UPDATE review_build_comment_action_v287
                SET status = CASE
                        WHEN ? IN ('FAILED', 'INDETERMINATE')
                          AND attempt_count >= attempt_limit
                        THEN 'ABANDONED' ELSE ? END,
                    claim_mode = NULL, claim_owner = NULL,
                    claimed_at_ms = NULL, lease_until_ms = NULL,
                    external_effect_id = ?, evidence = ?, last_error = ?,
                    completed_at_ms = ?
                WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                """, status, status, externalEffectId, evidence, error,
                completedAt.toEpochMilli(), actionId, attempt);
        if (changed != 1) {
            throw new IllegalStateException(
                    "review build comment result lost");
        }
    }

    private boolean isFinalized(String actionId)
    {
        Boolean value = jdbc.queryForObject("""
                SELECT finalized_at_ms IS NOT NULL
                FROM review_build_comment_action_v287 WHERE id = ?
                """, Boolean.class, actionId);
        return Boolean.TRUE.equals(value);
    }

    private ActionPayload payload(List<ProposalItem> items)
    {
        List<FrozenDraft> drafts = items.stream()
                .filter(item -> "INLINE".equals(item.kind()))
                .map(item -> new FrozenDraft(
                        "review-build-proposal:" + item.findingId(),
                        "file-line", item.path(), item.line(), "RIGHT",
                        null, null, item.body(), item.findingId()))
                .toList();
        List<String> topLevel = new ArrayList<>();
        for (ProposalItem item : items) {
            if ("TOP_LEVEL".equals(item.kind())) {
                String location = item.path() == null || item.path().isBlank()
                        ? "Pull request" : item.path();
                topLevel.add("### " + location + "\n\n" + item.body());
            }
        }
        return new ActionPayload(
                1, topLevel.isEmpty() ? "" : String.join("\n\n---\n\n", topLevel),
                "COMMENT", null, drafts);
    }

    private String encode(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (Exception failure) {
            throw new IllegalStateException(
                    "could not encode review build comment action", failure);
        }
    }

    private ActionPayload decodePayload(String value)
    {
        try {
            return payloadReader.readValue(value);
        }
        catch (Exception failure) {
            throw new IllegalStateException(
                    "could not decode review build comment payload", failure);
        }
    }

    private List<String> decodeBaseline(String value)
    {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, STRING_LIST);
        }
        catch (Exception failure) {
            throw new IllegalStateException(
                    "could not decode review build comment baseline", failure);
        }
    }

    private static boolean claimable(ActionStatus status)
    {
        return status == ActionStatus.REQUESTED
                || status == ActionStatus.FAILED
                || status == ActionStatus.INDETERMINATE;
    }

    public record ProposalView(
            String threadId,
            String reviewPassId,
            String repoFullName,
            int pullRequestNumber,
            String expectedHeadSha,
            String selectionDigest,
            String status,
            String decision,
            String commandId,
            ActionStatus actionStatus,
            String externalEffectId,
            String evidence,
            String lastError,
            List<ProposalItem> items)
    {
        public ProposalView
        {
            items = List.copyOf(requireNonNull(items, "items is null"));
        }
    }

    public record ProposalItem(
            int position,
            String findingId,
            String kind,
            String path,
            Integer line,
            String body)
    {}

    private record Subject(
            String threadId,
            String reviewPassId,
            String workspaceId,
            String remoteRepositoryId,
            String headRepositoryId,
            int pullRequestNumber,
            String branchName,
            String expectedHeadSha)
    {}

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
