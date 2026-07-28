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

/** SQLite ledger for one human-authorized V2 remote action. */
@Repository
public class SqliteUserRemoteActionStore
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

    public SqliteUserRemoteActionStore(
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
        validatePayload(request);
        return requireNonNull(transactions.execute(ignored -> {
            String payloadJson = encode(request.payload());
            String payloadDigest = digest(payloadJson);
            Optional<Action> replay = findByCommand(
                    request.taskId(), request.commandId());
            if (replay.isPresent()) {
                return requireSameCommand(
                        replay.orElseThrow(), request, payloadJson,
                        payloadDigest);
            }
            Subject subject = requireSubject(
                    request.taskId(), request.prId(), request.kind());
            if (request.kind() == ActionKind.DELETE_REMOTE_BRANCH
                    && !subject.branchName().equals(
                            request.payload().branchName())) {
                throw new IllegalStateException(
                        "V2 remote branch deletion differs from the bound branch");
            }
            String actionId = "v2-user-remote-action-" + id(
                    "user-remote-action",
                    request.taskId() + ":" + request.commandId());
            String operationId = id("user-remote-action-operation", actionId);
            String ticketId = id("user-remote-action-ticket", actionId);
            jdbc.update("""
                    INSERT INTO v2_user_remote_action_v270(
                        id, operation_id, task_id, command_id, task_epoch,
                        remote_stage_id, stage_generation,
                        remote_pr_binding_id, pr_id, kind,
                        remote_repository_id, head_repository_id,
                        remote_pr_number, branch_name, expected_head_sha,
                        expected_base_sha, payload_json, payload_digest,
                        handled_action, semantic_attempt, status,
                        attempt_count, attempt_limit, authorized_by,
                        authorized_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, 1, 'REQUESTED', 0, ?, 'user', ?)
                    """, actionId, operationId, subject.taskId(),
                    request.commandId(), subject.taskEpoch(), subject.stageId(),
                    subject.stageGeneration(), subject.bindingId(),
                    subject.prId(), request.kind().name(),
                    subject.remoteRepositoryId(), subject.headRepositoryId(),
                    subject.pullRequestNumber(), subject.branchName(),
                    subject.headSha(), subject.baseSha(), payloadJson,
                    payloadDigest, request.handledAction(), ATTEMPT_LIMIT,
                    now.toEpochMilli());
            insertDrafts(actionId, request.payload().drafts());
            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch,
                        stage_id, stage_generation, attempt,
                        expected_head_sha, expected_base_sha,
                        status, created_at_ms)
                    VALUES (?, ?, 'APPLY_V2_USER_REMOTE_ACTION',
                        'GITHUB_EFFECT', 'TASK', ?,
                        'V2_USER_REMOTE_ACTION_RESULT', 32,
                        0, 1, 0, ?, ?, ?, ?, ?, ?, 1, ?, ?, 'REQUESTED', ?)
                    """, ticketId, operationId, subject.taskId(),
                    subject.workspaceId(), subject.trunkId(), subject.taskId(),
                    subject.taskEpoch(), subject.stageId(),
                    subject.stageGeneration(), subject.headSha(),
                    subject.baseSha(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO v2_user_remote_action_dispatch_v270(
                        action_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES (?, ?, ?, ?)
                    """, actionId, ticketId, operationId, now.toEpochMilli());
            wakes.enqueue(ticketId, now);
            return require(operationId);
        }), "V2 user remote action authorization returned null");
    }

    @Override
    public Action require(String operationId)
    {
        List<Action> rows = jdbc.query("""
                SELECT action.*
                FROM v2_user_remote_action_v270 action
                JOIN v2_user_remote_action_dispatch_v270 dispatch
                  ON dispatch.action_id = action.id
                WHERE dispatch.operation_id = ?
                  AND action.operation_id = dispatch.operation_id
                """, (rs, row) -> action(rs), operationId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact V2 user remote action is missing");
        }
        return rows.getFirst();
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
            boolean reclaimForProbe = current.status() == ActionStatus.CLAIMED
                    && mode == ClaimMode.PROBE;
            if (current.attemptCount() != expectedAttemptCount
                    || (!reclaimForProbe && !claimable(current.status()))) {
                throw new IllegalStateException(
                        "V2 user remote action claim lost or exhausted");
            }
            if ((!reclaimForProbe
                    && current.attemptCount() >= current.attemptLimit())
                    || !sameSubject(current, findSubject(
                            current.taskId(), current.prId(), current.kind()))) {
                abandon(current, expectedAttemptCount,
                        "V2 user remote action authorization is stale or exhausted",
                        claimedAt);
                return requireById(actionId);
            }
            int changed = reclaimForProbe
                    ? jdbc.update("""
                            UPDATE v2_user_remote_action_v270
                            SET claim_mode = 'PROBE', claim_owner = ?,
                                claimed_at_ms = ?, lease_until_ms = ?
                            WHERE id = ? AND attempt_count = ?
                              AND status = 'CLAIMED'
                              AND lease_until_ms <= ?
                            """, claimOwner, claimedAt.toEpochMilli(),
                            leaseUntil.toEpochMilli(), actionId,
                            expectedAttemptCount, claimedAt.toEpochMilli())
                    : jdbc.update("""
                            UPDATE v2_user_remote_action_v270
                            SET status = 'CLAIMED',
                                attempt_count = attempt_count + 1,
                                claim_mode = ?, claim_owner = ?, claimed_at_ms = ?,
                                lease_until_ms = ?, external_effect_id = NULL,
                                evidence = NULL, last_error = NULL,
                                completed_at_ms = NULL
                            WHERE id = ? AND attempt_count = ?
                              AND status IN (
                                  'REQUESTED', 'FAILED', 'INDETERMINATE')
                              AND attempt_count < attempt_limit
                            """, mode.name(), claimOwner,
                            claimedAt.toEpochMilli(), leaseUntil.toEpochMilli(),
                            actionId, expectedAttemptCount);
            if (changed != 1) {
                throw new IllegalStateException(
                        "V2 user remote action claim lost or exhausted");
            }
            return requireById(actionId);
        }), "V2 user remote action claim returned null");
    }

    @Override
    public void finishSucceeded(
            String actionId,
            int attempt,
            String externalEffectId,
            String evidence,
            Instant completedAt)
    {
        finish(actionId, attempt, "SUCCEEDED", externalEffectId,
                evidence, null, completedAt);
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

    @Override
    public void recordRecoveryBaseline(
            String actionId, int attempt, List<String> remoteEffectIds)
    {
        requireNonNull(remoteEffectIds, "remoteEffectIds is null");
        int changed = jdbc.update("""
                UPDATE v2_user_remote_action_v270
                SET recovery_baseline_json = ?
                WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                  AND recovery_baseline_json IS NULL
                """, encode(remoteEffectIds), actionId, attempt);
        if (changed != 1) {
            Action current = requireById(actionId);
            if (current.attemptCount() != attempt
                    || current.recoveryBaseline() == null
                    || !current.recoveryBaseline().equals(remoteEffectIds)) {
                throw new IllegalStateException(
                        "V2 user remote action baseline persistence lost");
            }
        }
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
                    UPDATE v2_user_remote_action_v270
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
                        "V2 user remote action failure delivery is stale");
            }
            return require(operationId);
        }), "V2 user remote action terminal delivery returned null");
    }

    public Action requireById(String actionId)
    {
        List<Action> rows = jdbc.query("""
                SELECT * FROM v2_user_remote_action_v270 WHERE id = ?
                """, (rs, row) -> action(rs), actionId);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Exact V2 user remote action is missing");
        }
        return rows.getFirst();
    }

    public List<Action> findCommittedUnfinalized(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT action.*
                FROM v2_user_remote_action_v270 action
                JOIN v2_user_remote_action_dispatch_v270 dispatch
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
                UPDATE v2_user_remote_action_v270
                SET finalized_at_ms = ?
                WHERE id = ? AND status = ? AND finalized_at_ms IS NULL
                  AND completed_at_ms IS NOT NULL
                """, finalizedAt.toEpochMilli(), actionId, status.name());
        if (changed == 0) {
            Action current = requireById(actionId);
            if (current.status() != status
                    || !isFinalized(actionId)) {
                throw new IllegalStateException(
                        "V2 user remote action finalization is stale");
            }
        }
    }

    private boolean isFinalized(String actionId)
    {
        Boolean finalized = jdbc.queryForObject("""
                SELECT finalized_at_ms IS NOT NULL
                FROM v2_user_remote_action_v270 WHERE id = ?
                """, Boolean.class, actionId);
        return Boolean.TRUE.equals(finalized);
    }

    private void finish(
            String actionId,
            int attempt,
            String status,
            String externalEffectId,
            String evidence,
            String error,
            Instant completedAt)
    {
        transactions.executeWithoutResult(ignored -> {
            int changed = jdbc.update("""
                    UPDATE v2_user_remote_action_v270
                    SET status = CASE
                            WHEN ? IN ('FAILED', 'INDETERMINATE')
                              AND attempt_count >= attempt_limit
                            THEN 'ABANDONED'
                            ELSE ? END,
                        claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        external_effect_id = ?, evidence = ?, last_error = ?,
                        completed_at_ms = ?
                    WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                    """, status, status, externalEffectId, evidence, error,
                    completedAt.toEpochMilli(), actionId, attempt);
            if (changed != 1) {
                throw new IllegalStateException(
                        "V2 user remote action result lost");
            }
        });
    }

    private Optional<Action> findByCommand(String taskId, String commandId)
    {
        return jdbc.query("""
                SELECT * FROM v2_user_remote_action_v270
                WHERE task_id = ? AND command_id = ?
                """, (rs, row) -> action(rs), taskId, commandId)
                .stream().findFirst();
    }

    private static Action requireSameCommand(
            Action action,
            AuthorizationRequest request,
            String payloadJson,
            String payloadDigest)
    {
        if (!action.commandId().equals(request.commandId())
                || !action.prId().equals(request.prId())
                || action.kind() != request.kind()
                || !action.payloadJson().equals(payloadJson)
                || !action.payloadDigest().equals(payloadDigest)
                || !Objects.equals(
                        action.handledAction(), request.handledAction())) {
            throw new IllegalStateException(
                    "V2 remote action commandId was reused for a different request");
        }
        return action;
    }

    private Subject requireSubject(
            String taskId, String prId, ActionKind kind)
    {
        return findSubject(taskId, prId, kind)
                .orElseThrow(() -> new IllegalStateException(
                        "V2 user remote action requires one exact Remote subject"));
    }

    private Optional<Subject> findSubject(
            String taskId, String prId, ActionKind kind)
    {
        String liveClause = switch (kind) {
            case DELETE_REMOTE_BRANCH -> """
                    AND pull_request.status = 'merged'
                    AND snapshot.pr_state = 'MERGED'
                    AND NOT EXISTS (
                        SELECT 1 FROM remote_development_stage newer
                        WHERE newer.task_id = task.id
                          AND newer.generation > remote.generation)
                    """;
            case POST_TOP_LEVEL_COMMENT -> """
                    AND (
                        (task.lifecycle_state = 'ACTIVE'
                          AND current.stage_id = remote.stage_id
                          AND current.stage_generation = remote.generation
                          AND owner.completed_at_ms IS NULL
                          AND snapshot.pr_state = 'OPEN')
                        OR
                        (pull_request.status IN ('merged', 'closed')
                          AND snapshot.pr_state = CASE pull_request.status
                              WHEN 'merged' THEN 'MERGED' ELSE 'CLOSED' END
                          AND NOT EXISTS (
                              SELECT 1
                              FROM remote_development_stage newer
                              WHERE newer.task_id = task.id
                                AND newer.generation > remote.generation)))
                    """;
            case DEQUEUE, SUBMIT_REVIEW -> """
                    AND task.lifecycle_state = 'ACTIVE'
                    AND current.stage_id = remote.stage_id
                    AND current.stage_generation = remote.generation
                    AND owner.completed_at_ms IS NULL
                    AND snapshot.pr_state = 'OPEN'
                    """;
        };
        List<Subject> rows = jdbc.query("""
                SELECT task.id AS task_id, task.thread_id AS trunk_id,
                       trunk.workspace_id, task.epoch AS task_epoch,
                       remote.stage_id, remote.generation AS stage_generation,
                       binding.id AS binding_id, pull_request.id AS pr_id,
                       binding.remote_repository_id,
                       binding.head_repository_id,
                       binding.remote_pr_number, pull_request.branch_name,
                       remote.current_head_sha, remote.current_base_sha
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN pr pull_request ON pull_request.task_id = task.id
                JOIN remote_pr_binding binding
                  ON binding.task_id = task.id
                 AND binding.pr_id = pull_request.id
                JOIN remote_development_stage remote
                  ON remote.task_id = task.id
                JOIN stage owner ON owner.id = remote.stage_id
                JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                LEFT JOIN task_current_stage current
                  ON current.task_id = task.id
                WHERE task.id = ? AND pull_request.id = ?
                  AND task.workflow_version = 'V2'
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.generation = remote.generation
                  AND snapshot.task_id = task.id
                  AND snapshot.remote_development_stage_id = remote.stage_id
                  AND snapshot.stage_generation = remote.generation
                  AND snapshot.remote_pr_binding_id = binding.id
                  AND snapshot.head_sha = remote.current_head_sha
                  AND snapshot.base_sha = remote.current_base_sha
                  AND binding.remote_repository_id = pull_request.repo
                  AND binding.remote_pr_number = pull_request.remote_pr_number
                """ + liveClause + """
                ORDER BY remote.generation DESC
                """, (rs, row) -> subject(rs), taskId, prId);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    private void abandon(
            Action action, int expectedAttemptCount, String reason, Instant now)
    {
        int changed = jdbc.update("""
                UPDATE v2_user_remote_action_v270
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
            throw new IllegalStateException(
                    "V2 user remote action abandonment lost");
        }
    }

    private static boolean sameSubject(
            Action action, Optional<Subject> candidate)
    {
        if (candidate.isEmpty()) {
            return false;
        }
        Subject subject = candidate.orElseThrow();
        return action.taskId().equals(subject.taskId())
                && action.taskEpoch() == subject.taskEpoch()
                && action.stageId().equals(subject.stageId())
                && action.stageGeneration() == subject.stageGeneration()
                && action.remotePrBindingId().equals(subject.bindingId())
                && action.prId().equals(subject.prId())
                && action.remoteRepositoryId().equals(subject.remoteRepositoryId())
                && action.headRepositoryId().equals(subject.headRepositoryId())
                && action.pullRequestNumber() == subject.pullRequestNumber()
                && action.branchName().equals(subject.branchName())
                && action.headSha().equals(subject.headSha())
                && action.baseSha().equals(subject.baseSha());
    }

    private static boolean claimable(ActionStatus status)
    {
        return status == ActionStatus.REQUESTED
                || status == ActionStatus.FAILED
                || status == ActionStatus.INDETERMINATE;
    }

    private static void validatePayload(AuthorizationRequest request)
    {
        ActionPayload payload = request.payload();
        switch (request.kind()) {
            case DEQUEUE -> requireEmptyPayload(payload, false);
            case DELETE_REMOTE_BRANCH -> {
                requireEmptyPayload(payload, true);
                requireText(payload.branchName(), "branchName");
            }
            case POST_TOP_LEVEL_COMMENT -> {
                requireText(payload.body(), "comment body");
                if (payload.reviewAction() != null
                        || payload.branchName() != null
                        || !payload.drafts().isEmpty()) {
                    throw new IllegalArgumentException(
                            "comment action payload is invalid");
                }
            }
            case SUBMIT_REVIEW -> {
                String review = requireText(
                        payload.reviewAction(), "reviewAction")
                        .toUpperCase(Locale.ROOT);
                if (!List.of("COMMENT", "APPROVE", "REQUEST_CHANGES")
                        .contains(review)
                        || payload.branchName() != null) {
                    throw new IllegalArgumentException(
                            "review action payload is invalid");
                }
            }
        }
    }

    private static void requireEmptyPayload(
            ActionPayload payload, boolean allowBranch)
    {
        if (payload.body() != null || payload.reviewAction() != null
                || (!allowBranch && payload.branchName() != null)
                || !payload.drafts().isEmpty()) {
            throw new IllegalArgumentException(
                    "remote action payload is invalid");
        }
    }

    private void insertDrafts(String actionId, List<FrozenDraft> drafts)
    {
        for (int index = 0; index < drafts.size(); index++) {
            FrozenDraft draft = drafts.get(index);
            jdbc.update("""
                    INSERT INTO v2_user_remote_action_draft_v270(
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
                ActionStatus.valueOf(rs.getString("status")),
                rs.getInt("semantic_attempt"), rs.getInt("attempt_count"),
                rs.getInt("attempt_limit"), rs.getString("task_id"),
                rs.getString("command_id"), rs.getLong("task_epoch"),
                rs.getString("remote_stage_id"),
                rs.getLong("stage_generation"),
                rs.getString("remote_pr_binding_id"), rs.getString("pr_id"),
                rs.getString("remote_repository_id"),
                rs.getString("head_repository_id"),
                rs.getInt("remote_pr_number"), rs.getString("branch_name"),
                rs.getString("expected_head_sha"),
                rs.getString("expected_base_sha"), payloadJson,
                rs.getString("payload_digest"), decode(payloadJson),
                rs.getString("handled_action"),
                Instant.ofEpochMilli(rs.getLong("authorized_at_ms")),
                decodeBaseline(rs.getString("recovery_baseline_json")),
                rs.getString("external_effect_id"), rs.getString("evidence"));
    }

    private String encode(ActionPayload payload)
    {
        try {
            return json.writeValueAsString(payload);
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Cannot encode V2 user remote action payload", failure);
        }
    }

    private String encode(List<String> values)
    {
        try {
            return json.writeValueAsString(values);
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Cannot encode V2 user remote action baseline", failure);
        }
    }

    private ActionPayload decode(String payload)
    {
        try {
            return payloadReader.readValue(payload);
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Invalid V2 user remote action payload", failure);
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
                    "Invalid V2 user remote action baseline", failure);
        }
    }

    private static Subject subject(ResultSet rs)
            throws SQLException
    {
        return new Subject(
                rs.getString("task_id"), rs.getString("trunk_id"),
                rs.getString("workspace_id"), rs.getLong("task_epoch"),
                rs.getString("stage_id"), rs.getLong("stage_generation"),
                rs.getString("binding_id"), rs.getString("pr_id"),
                rs.getString("remote_repository_id"),
                rs.getString("head_repository_id"),
                rs.getInt("remote_pr_number"), rs.getString("branch_name"),
                rs.getString("current_head_sha"),
                rs.getString("current_base_sha"));
    }

    public record AuthorizationRequest(
            String taskId,
            String commandId,
            String prId,
            ActionKind kind,
            ActionPayload payload,
            String handledAction)
    {
        public AuthorizationRequest
        {
            requireText(taskId, "taskId");
            requireText(commandId, "commandId");
            requireText(prId, "prId");
            requireNonNull(kind, "kind is null");
            requireNonNull(payload, "payload is null");
        }
    }

    private record Subject(
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String bindingId,
            String prId,
            String remoteRepositoryId,
            String headRepositoryId,
            int pullRequestNumber,
            String branchName,
            String headSha,
            String baseSha) {}

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
