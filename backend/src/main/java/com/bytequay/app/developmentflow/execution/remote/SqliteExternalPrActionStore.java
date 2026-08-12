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

import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable zero-Task GitHub-effect ledger for a cached external PR. */
@Repository
public class SqliteExternalPrActionStore
        implements UserRemoteActionOperationHandler.OperationStore
{
    private static final int ATTEMPT_LIMIT = 3;
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqliteDispatchWakeStore wakes;
    private final ObjectMapper json;
    private final ObjectReader payloadReader;

    public SqliteExternalPrActionStore(
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

    public Action authorize(AuthorizationRequest request, Instant now)
    {
        requireNonNull(request, "request is null");
        requireNonNull(now, "now is null");
        if (request.semanticAction() == SemanticAction.TRIGGER_CI_EMPTY_COMMIT) {
            throw new IllegalArgumentException(
                    "a taskless PR has no worktree for an empty CI commit");
        }
        SqliteUserRemoteActionStore.validatePayload(
                request.semanticAction(), request.payload());
        return requireNonNull(transactions.execute(ignored -> {
            Subject cached = requireCachedSubject(request.prId());
            String threadId = requireReviewTrunk(cached, now);
            String payloadJson = encode(request.payload());
            String payloadDigest = digest(payloadJson);
            Optional<Action> replay = findByCommand(threadId, request.commandId());
            if (replay.isPresent()) {
                return requireSameCommand(
                        replay.orElseThrow(), request, payloadJson,
                        payloadDigest);
            }
            String actionId = "v2-external-pr-action-" + id(
                    "external-pr-action",
                    threadId + ":" + request.commandId());
            String operationId = id("external-pr-action-operation", actionId);
            String ticketId = id("external-pr-action-ticket", actionId);
            String reviewId = request.reviewId() == null
                    ? findExactReview(cached, threadId).orElse(null)
                    : request.reviewId();
            jdbc.update("""
                    INSERT INTO external_pr_action_v289(
                        id, operation_id, thread_id, workspace_id, command_id,
                        pr_id, review_id, kind, semantic_action,
                        remote_repository_id, head_repository_id,
                        remote_pr_number, branch_name, expected_head_sha,
                        expected_base_sha, payload_json, payload_digest,
                        handled_action, semantic_attempt, status,
                        attempt_count, attempt_limit, authorized_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, 1, 'REQUESTED', 0, ?, ?)
                    """, actionId, operationId, threadId, cached.workspaceId(),
                    request.commandId(), cached.prId(), reviewId,
                    request.semanticAction().wireKind().name(),
                    request.semanticAction().name(), cached.repositoryId(),
                    cached.headRepositoryId(), cached.prNumber(),
                    cached.branchName(), cached.headSha(), cached.baseSha(),
                    payloadJson, payloadDigest, request.handledAction(),
                    ATTEMPT_LIMIT, now.toEpochMilli());
            insertDrafts(actionId, request.payload().drafts());
            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch,
                        stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES (?, ?, 'APPLY_V2_EXTERNAL_PR_ACTION',
                        'GITHUB_EFFECT', 'TRUNK', ?,
                        'V2_EXTERNAL_PR_ACTION_RESULT', 32,
                        1, 0, 0, ?, ?, NULL, NULL, NULL, NULL, 1,
                        NULL, ?, ?, 'REQUESTED', ?)
                    """, ticketId, operationId, threadId,
                    cached.workspaceId(), threadId, cached.headSha(),
                    cached.baseSha(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO external_pr_action_dispatch_v289(
                        action_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES (?, ?, ?, ?)
                    """, actionId, ticketId, operationId, now.toEpochMilli());
            wakes.enqueue(ticketId, now);
            return require(operationId);
        }), "external PR action authorization returned null");
    }

    @Override
    public Action require(String operationId)
    {
        return find(operationId).orElseThrow(() -> new IllegalStateException(
                "exact external PR action is missing"));
    }

    /** Empty when the operation belongs to the Task ledger instead. */
    @Override
    public Optional<Action> find(String operationId)
    {
        List<Action> rows = jdbc.query("""
                SELECT action.* FROM external_pr_action_v289 action
                JOIN external_pr_action_dispatch_v289 dispatch
                  ON dispatch.action_id = action.id
                WHERE dispatch.operation_id = ?
                  AND action.operation_id = dispatch.operation_id
                """, (rs, row) -> action(rs), operationId);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    @Override
    public Action claim(
            String actionId,
            int expectedAttemptCount,
            ClaimMode mode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        return requireNonNull(transactions.execute(ignored -> {
            Action current = requireById(actionId);
            boolean reclaim = current.status() == ActionStatus.CLAIMED
                    && mode == ClaimMode.PROBE;
            if (current.attemptCount() != expectedAttemptCount
                    || (!reclaim && !claimable(current.status()))) {
                throw new IllegalStateException("external PR action claim lost");
            }
            if ((!reclaim
                    && current.attemptCount() >= current.attemptLimit())
                    || !sameSubject(current, findCurrentSubject(current))) {
                abandon(current, expectedAttemptCount,
                        "external PR action authorization is stale or exhausted",
                        claimedAt);
                return requireById(actionId);
            }
            int changed = reclaim
                    ? jdbc.update("""
                            UPDATE external_pr_action_v289
                            SET claim_mode = 'PROBE', claim_owner = ?,
                                claimed_at_ms = ?, lease_until_ms = ?
                            WHERE id = ? AND attempt_count = ?
                              AND status = 'CLAIMED' AND lease_until_ms <= ?
                            """, claimOwner, claimedAt.toEpochMilli(),
                            leaseUntil.toEpochMilli(), actionId,
                            expectedAttemptCount, claimedAt.toEpochMilli())
                    : jdbc.update("""
                            UPDATE external_pr_action_v289
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
                throw new IllegalStateException("external PR action claim lost");
            }
            return requireById(actionId);
        }), "external PR action claim returned null");
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
        finish(actionId, attempt, "INDETERMINATE", null, evidence, evidence,
                completedAt);
    }

    @Override
    public void finishCanceled(
            String actionId, int attempt, String error, Instant completedAt)
    {
        finish(actionId, attempt, "CANCELED", null, null, error, completedAt);
    }

    @Override
    public void recordRecoveryBaseline(
            String actionId, int attempt, List<String> remoteEffectIds)
    {
        requireNonNull(remoteEffectIds, "remoteEffectIds is null");
        int changed = jdbc.update("""
                UPDATE external_pr_action_v289
                SET recovery_baseline_json = ?
                WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                  AND recovery_baseline_json IS NULL
                """, encode(remoteEffectIds), actionId, attempt);
        if (changed != 1) {
            Action current = requireById(actionId);
            if (current.attemptCount() != attempt
                    || !Objects.equals(
                            current.recoveryBaseline(), remoteEffectIds)) {
                throw new IllegalStateException(
                        "external PR action baseline persistence lost");
            }
        }
    }

    @Override
    public void deferProbe(
            String actionId, int attempt, Instant retryAt, String evidence)
    {
        throw new IllegalStateException(
                "taskless PR actions do not own deferred worktree probes");
    }

    public Action terminalizeDeliveryFailure(
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
            Action action = require(operationId);
            if (action.status() == terminalStatus) {
                return action;
            }
            int changed = jdbc.update("""
                    UPDATE external_pr_action_v289
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
                        "external PR action failure delivery is stale");
            }
            return require(operationId);
        }), "external PR action terminal delivery returned null");
    }

    public List<Action> findCommittedUnfinalized(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT action.* FROM external_pr_action_v289 action
                JOIN external_pr_action_dispatch_v289 dispatch
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

    public void markFinalized(
            String actionId, ActionStatus status, Instant finalizedAt)
    {
        int changed = jdbc.update("""
                UPDATE external_pr_action_v289
                SET finalized_at_ms = ?
                WHERE id = ? AND status = ? AND finalized_at_ms IS NULL
                  AND completed_at_ms IS NOT NULL
                """, finalizedAt.toEpochMilli(), actionId, status.name());
        if (changed == 0 && !isFinalized(actionId, status)) {
            throw new IllegalStateException(
                    "external PR action finalization is stale");
        }
    }

    public Optional<Projection> findLatestProjection(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query("""
                SELECT action.command_id, action.review_id, action.status,
                       action.finalized_at_ms, action.external_effect_id,
                       action.last_error, action.semantic_action
                FROM external_pr_action_v289 action
                WHERE action.pr_id = ?
                  AND action.semantic_action = 'SUBMIT_REVIEW'
                ORDER BY action.authorized_at_ms DESC, action.id DESC
                LIMIT 1
                """, (rs, row) -> projection(rs), prId).stream().findFirst();
    }

    public Optional<String> reviewId(String operationId)
    {
        return jdbc.query("""
                SELECT review_id FROM external_pr_action_v289
                WHERE operation_id = ?
                """, (rs, row) -> rs.getString(1), operationId).stream()
                .filter(Objects::nonNull).findFirst();
    }

    private String requireReviewTrunk(Subject subject, Instant now)
    {
        String prRef = subject.repositoryId().toLowerCase(Locale.ROOT)
                + "#" + subject.prNumber();
        String threadId = id("external-pr-review-trunk",
                subject.workspaceId() + ":" + prRef);
        jdbc.update("""
                INSERT OR IGNORE INTO threads(
                    id, kind, provider, agent_session_id, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, ended_at_ms, error_message,
                    workspace_id, flow, parallel_slots, pr_ref,
                    turn_version, lifecycle_state, aggregate_version)
                VALUES (?, 'LOGIC_LOOP', 'external-pr', NULL, ?, 'IDLE',
                    'external-pr', 0, 0, 0, ?, ?, NULL, NULL, ?, 'review', 1,
                    ?, 'V2', 'ACTIVE', 0)
                """, threadId,
                "Review " + subject.repositoryId() + "#" + subject.prNumber(),
                now.toEpochMilli(), now.toEpochMilli(), subject.workspaceId(),
                prRef);
        List<String> exact = jdbc.queryForList("""
                SELECT id FROM threads
                WHERE workspace_id = ? AND pr_ref = ? AND flow = 'review'
                  AND turn_version = 'V2'
                  AND lifecycle_state IN ('ACTIVE', 'IDLE')
                  AND NOT EXISTS (
                      SELECT 1 FROM tasks task WHERE task.thread_id = threads.id)
                """, String.class, subject.workspaceId(), prRef);
        if (exact.size() != 1) {
            throw new IllegalStateException(
                    "external PR review Trunk is absent, LEGACY, or ambiguous");
        }
        return exact.getFirst();
    }

    private Subject requireCachedSubject(String prId)
    {
        List<Subject> rows = jdbc.query("""
                SELECT local_pr.id AS pr_id, binding.workspace_id,
                       local_pr.repo AS repository_id,
                       detail.head_repo AS head_repository_id,
                       local_pr.remote_pr_number AS pr_number,
                       detail.head_ref AS branch_name,
                       detail.head_sha AS head_sha,
                       detail.base_sha AS base_sha
                FROM pr local_pr
                JOIN workspace_repos binding
                  ON lower(binding.repo_full_name) = lower(local_pr.repo)
                JOIN pull_requests cached
                  ON lower(cached.repo) = lower(local_pr.repo)
                 AND cached.number = local_pr.remote_pr_number
                JOIN pr_detail detail ON detail.pr_id = cached.id
                WHERE local_pr.id = ? AND local_pr.task_id IS NULL
                  AND local_pr.origin = 'external'
                  AND local_pr.remote_pr_number > 0
                  AND length(trim(detail.head_repo)) > 0
                  AND length(trim(detail.head_ref)) > 0
                  AND length(trim(detail.head_sha)) > 0
                  AND length(trim(detail.base_sha)) > 0
                  AND lower(detail.base_repo) = lower(local_pr.repo)
                """, (rs, row) -> subject(rs), prId);
        if (rows.size() != 1) {
            requireWatchedRepository(prId);
            throw new IllegalStateException(
                    "external PR action requires one complete cached subject");
        }
        return rows.getFirst();
    }

    /**
     * A PR's GitHub effects hang off the V2 REVIEW Trunk in the workspace
     * bound to its repository, so a repository the user never watched has
     * nowhere to own them and {@link #requireCachedSubject} finds nothing.
     * That is a setup gap the user closes by watching the repository — not a
     * moved head or a stale cache — so report it as itself. Only runs on the
     * failure path.
     */
    private void requireWatchedRepository(String prId)
    {
        List<String> unwatched = jdbc.queryForList("""
                SELECT local_pr.repo FROM pr local_pr
                WHERE local_pr.id = ?
                  AND local_pr.repo IS NOT NULL
                  AND length(trim(local_pr.repo)) > 0
                  AND NOT EXISTS (
                      SELECT 1 FROM workspace_repos binding
                      WHERE lower(binding.repo_full_name) = lower(local_pr.repo))
                """, String.class, prId);
        if (!unwatched.isEmpty()) {
            throw new UnwatchedRepositoryException(unwatched.getFirst());
        }
    }

    private Optional<Subject> findCurrentSubject(Action action)
    {
        try {
            Subject subject = requireCachedSubject(action.prId());
            return findExactReviewTrunk(subject)
                    .filter(action.remotePrBindingId()::equals)
                    .isPresent()
                    ? Optional.of(subject) : Optional.empty();
        }
        catch (IllegalStateException failure) {
            return Optional.empty();
        }
    }

    private Optional<String> findExactReviewTrunk(Subject subject)
    {
        String prRef = subject.repositoryId().toLowerCase(Locale.ROOT)
                + "#" + subject.prNumber();
        List<String> rows = jdbc.queryForList("""
                SELECT id FROM threads
                WHERE workspace_id = ? AND pr_ref = ? AND flow = 'review'
                  AND turn_version = 'V2'
                  AND lifecycle_state IN ('ACTIVE', 'IDLE')
                  AND NOT EXISTS (
                      SELECT 1 FROM tasks task WHERE task.thread_id = threads.id)
                """, String.class, subject.workspaceId(), prRef);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    private Optional<String> findExactReview(Subject subject, String threadId)
    {
        return jdbc.query("""
                SELECT id FROM review_session
                WHERE pr_id = ? AND workspace_id = ? AND owner_thread_id = ?
                  AND base_commit = ? AND reviewed_head_commit = ?
                ORDER BY updated_at_ms DESC, id DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), subject.prId(),
                subject.workspaceId(), threadId, subject.baseSha(),
                subject.headSha()).stream().findFirst();
    }

    private void finish(
            String actionId, int attempt, String status,
            String externalEffectId, String evidence, String error,
            Instant completedAt)
    {
        int changed = jdbc.update("""
                UPDATE external_pr_action_v289
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
            throw new IllegalStateException("external PR action result lost");
        }
    }

    private void abandon(
            Action action, int expectedAttemptCount, String reason, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE external_pr_action_v289
                SET status = 'ABANDONED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, external_effect_id = NULL,
                    evidence = NULL, last_error = ?, completed_at_ms = ?
                WHERE id = ? AND attempt_count = ?
                  AND status IN (
                      'REQUESTED', 'CLAIMED', 'FAILED', 'INDETERMINATE')
                """, reason, now.toEpochMilli(), action.id(),
                expectedAttemptCount);
        if (changed != 1) {
            throw new IllegalStateException("external PR action abandonment lost");
        }
    }

    private Action requireById(String actionId)
    {
        List<Action> rows = jdbc.query("""
                SELECT * FROM external_pr_action_v289 WHERE id = ?
                """, (rs, row) -> action(rs), actionId);
        if (rows.size() != 1) {
            throw new IllegalStateException("exact external PR action is missing");
        }
        return rows.getFirst();
    }

    private Optional<Action> findByCommand(String threadId, String commandId)
    {
        return jdbc.query("""
                SELECT * FROM external_pr_action_v289
                WHERE thread_id = ? AND command_id = ?
                """, (rs, row) -> action(rs), threadId, commandId)
                .stream().findFirst();
    }

    private static Action requireSameCommand(
            Action action, AuthorizationRequest request,
            String payloadJson, String payloadDigest)
    {
        if (!action.commandId().equals(request.commandId())
                || !action.prId().equals(request.prId())
                || action.semanticAction() != request.semanticAction()
                || !action.payloadJson().equals(payloadJson)
                || !action.payloadDigest().equals(payloadDigest)
                || !Objects.equals(
                        action.handledAction(), request.handledAction())) {
            throw new IllegalStateException(
                    "external PR commandId was reused for another request");
        }
        return action;
    }

    private static boolean sameSubject(
            Action action, Optional<Subject> candidate)
    {
        if (candidate.isEmpty()) {
            return false;
        }
        Subject subject = candidate.orElseThrow();
        return action.prId().equals(subject.prId())
                && action.remoteRepositoryId().equalsIgnoreCase(
                        subject.repositoryId())
                && action.headRepositoryId().equalsIgnoreCase(
                        subject.headRepositoryId())
                && action.pullRequestNumber() == subject.prNumber()
                && action.branchName().equals(subject.branchName())
                && action.headSha().equals(subject.headSha())
                && action.baseSha().equals(subject.baseSha());
    }

    private void insertDrafts(String actionId, List<FrozenDraft> drafts)
    {
        for (int index = 0; index < drafts.size(); index++) {
            FrozenDraft draft = drafts.get(index);
            jdbc.update("""
                    INSERT INTO external_pr_action_draft_v289(
                        action_id, position, comment_id, scope, file_path,
                        line_number, side, start_line, start_side, body,
                        finding_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, actionId, index + 1, draft.id(), draft.scope(),
                    draft.filePath(), draft.lineNumber(), draft.side(),
                    draft.startLine(), draft.startSide(), draft.body(),
                    draft.findingId());
        }
    }

    private Action action(ResultSet rs)
            throws SQLException
    {
        String payloadJson = rs.getString("payload_json");
        return new Action(
                rs.getString("id"), rs.getString("operation_id"),
                ActionKind.valueOf(rs.getString("kind")),
                SemanticAction.valueOf(rs.getString("semantic_action")),
                ActionStatus.valueOf(rs.getString("status")),
                rs.getInt("semantic_attempt"), rs.getInt("attempt_count"),
                rs.getInt("attempt_limit"), null, rs.getString("command_id"),
                0, null, 0, rs.getString("thread_id"),
                rs.getString("pr_id"), rs.getString("remote_repository_id"),
                rs.getString("head_repository_id"),
                rs.getInt("remote_pr_number"), rs.getString("branch_name"),
                null, null, rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"), payloadJson,
                rs.getString("payload_digest"), decode(payloadJson),
                rs.getString("handled_action"),
                Instant.ofEpochMilli(rs.getLong("authorized_at_ms")),
                decodeBaseline(rs.getString("recovery_baseline_json")),
                rs.getString("external_effect_id"), rs.getString("evidence"));
    }

    private static Subject subject(ResultSet rs)
            throws SQLException
    {
        return new Subject(
                rs.getString("pr_id"), rs.getString("workspace_id"),
                rs.getString("repository_id"),
                rs.getString("head_repository_id"), rs.getInt("pr_number"),
                rs.getString("branch_name"), rs.getString("head_sha"),
                rs.getString("base_sha"));
    }

    private Projection projection(ResultSet rs)
            throws SQLException
    {
        ActionStatus status = ActionStatus.valueOf(rs.getString("status"));
        boolean finalized = rs.getObject("finalized_at_ms") != null;
        String wireStatus = switch (status) {
            case REQUESTED -> "QUEUED";
            case CLAIMED -> "PUBLISHING";
            case FAILED -> "FAILED";
            case INDETERMINATE -> "INDETERMINATE";
            case SUCCEEDED -> finalized ? "PUBLISHED" : "PUBLISHING";
            case CANCELED, ABANDONED -> "FAILED";
        };
        boolean terminal = finalized && (status == ActionStatus.SUCCEEDED
                || status == ActionStatus.CANCELED
                || status == ActionStatus.ABANDONED);
        boolean blocks = !(status == ActionStatus.SUCCEEDED && finalized);
        return new Projection(
                rs.getString("command_id"), rs.getString("review_id"),
                wireStatus, terminal, finalized,
                blocks,
                rs.getString("external_effect_id"),
                rs.getString("last_error"));
    }

    private boolean isFinalized(String actionId, ActionStatus status)
    {
        Boolean value = jdbc.queryForObject("""
                SELECT status = ? AND finalized_at_ms IS NOT NULL
                FROM external_pr_action_v289 WHERE id = ?
                """, Boolean.class, status.name(), actionId);
        return Boolean.TRUE.equals(value);
    }

    private String encode(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "cannot encode external PR action payload", failure);
        }
    }

    private ActionPayload decode(String value)
    {
        try {
            return payloadReader.readValue(value);
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid external PR action payload", failure);
        }
    }

    private List<String> decodeBaseline(String value)
    {
        if (value == null) {
            return null;
        }
        try {
            return List.copyOf(json.readValue(value, STRING_LIST));
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid external PR action baseline", failure);
        }
    }

    private static boolean claimable(ActionStatus status)
    {
        return status == ActionStatus.REQUESTED
                || status == ActionStatus.FAILED
                || status == ActionStatus.INDETERMINATE;
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }

    /**
     * The one authorization failure the user can fix from the app: the PR's
     * repository isn't watched, so it has no workspace and no REVIEW Trunk to
     * own a GitHub effect. Stays an {@link IllegalStateException} so claim and
     * authorization handling treat it exactly like any other stale subject;
     * the message is user-facing and the UI turns it into a watch action, so
     * keep its wording in step with the frontend's submit-review drawer.
     */
    public static final class UnwatchedRepositoryException
            extends IllegalStateException
    {
        public UnwatchedRepositoryException(String repository)
        {
            super("ByteQuay must watch " + requireText(repository, "repository")
                    + " before publishing to its pull requests");
        }
    }

    public record AuthorizationRequest(
            String commandId,
            String prId,
            String reviewId,
            SemanticAction semanticAction,
            ActionPayload payload,
            String handledAction)
    {
        public AuthorizationRequest
        {
            requireText(commandId, "commandId");
            requireText(prId, "prId");
            requireNonNull(semanticAction, "semanticAction is null");
            requireNonNull(payload, "payload is null");
        }
    }

    public record Projection(
            String commandId,
            String reviewId,
            String status,
            boolean terminal,
            boolean finalized,
            boolean blocksNewPublication,
            String externalEffectId,
            String lastError) {}

    private record Subject(
            String prId,
            String workspaceId,
            String repositoryId,
            String headRepositoryId,
            int prNumber,
            String branchName,
            String headSha,
            String baseSha) {}
}
