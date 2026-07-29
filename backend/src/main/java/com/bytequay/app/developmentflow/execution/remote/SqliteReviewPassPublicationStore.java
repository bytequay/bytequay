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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Durable authorization and finalization ledger for a standalone ReviewPass. */
@Repository
public class SqliteReviewPassPublicationStore
        implements ReviewBuildCommentOperationHandler.OperationStore
{
    public static final String ACTION_PREFIX = "review-pass-publication-action-";
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

    public SqliteReviewPassPublicationStore(
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

    public PublicationView authorize(
            String reviewPassId,
            String commandId,
            String reviewAction,
            List<String> findingIds,
            Instant now)
    {
        requireText(reviewPassId, "reviewPassId");
        requireText(commandId, "commandId");
        String action = normalizeReviewAction(reviewAction);
        requireNonNull(findingIds, "findingIds is null");
        requireNonNull(now, "now is null");

        // This read deliberately precedes the transaction's first write. A
        // historical TASK_PHASE pass is rejected without promoting its thread,
        // creating an action, or emitting a dispatch wake.
        Subject initial = requirePass(reviewPassId);
        requireStandalone(initial);

        return requireNonNull(transactions.execute(ignored -> {
            Optional<PublicationView> existing = findPublication(reviewPassId);
            List<String> requestedIds = List.copyOf(
                    new LinkedHashSet<>(findingIds));
            if (existing.isPresent()) {
                PublicationView replay = existing.orElseThrow();
                if (commandId.equals(replay.commandId())
                        && action.equals(replay.reviewAction())
                        && requestedIds.equals(replay.findingIds())) {
                    return replay;
                }
                throw new IllegalStateException(
                        "review pass already has a different publication authorization");
            }

            Subject subject = requirePass(reviewPassId);
            requireStandalone(subject);
            requireAuthorizable(subject);

            List<Finding> findings = requestedIds.stream()
                    .map(findingId -> requireFinding(reviewPassId, findingId))
                    .toList();
            String findingIdsJson = encode(requestedIds);
            String requestJson = encode(new PublicationRequest(
                    reviewPassId, action, subject.expectedHeadSha(), findings));
            String requestDigest = digest(requestJson);
            ActionPayload payload = payload(
                    subject, findings, action, requestDigest);
            String payloadJson = encode(payload);
            String actionId = ACTION_PREFIX + id(
                    "review-pass-publication-action",
                    reviewPassId + ":" + commandId);
            String operationId = id(
                    "review-pass-publication-operation", actionId);
            String ticketId = id(
                    "review-pass-publication-ticket", actionId);

            jdbc.update("""
                    INSERT INTO review_pass_publication_v288(
                        id, operation_id, review_pass_id, thread_id,
                        command_id, workspace_id, remote_repository_id,
                        head_repository_id, remote_pr_number, branch_name,
                        expected_head_sha, review_action, finding_ids_json,
                        request_digest, payload_json, payload_digest,
                        semantic_attempt, status, attempt_count, attempt_limit,
                        observation_count, observation_limit, authorized_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        1, 'REQUESTED', 0, ?, 0, ?, ?)
                    """, actionId, operationId, reviewPassId,
                    subject.threadId(), commandId, subject.workspaceId(),
                    subject.remoteRepositoryId(), subject.headRepositoryId(),
                    subject.pullRequestNumber(), subject.branchName(),
                    subject.expectedHeadSha(), action, findingIdsJson,
                    requestDigest, payloadJson, digest(payloadJson),
                    ATTEMPT_LIMIT, OBSERVATION_LIMIT, now.toEpochMilli());

            int position = 0;
            for (Finding finding : findings) {
                position++;
                jdbc.update("""
                        INSERT INTO review_pass_publication_item_v288(
                            publication_id, position, finding_id,
                            finding_revision, content_digest, kind, path,
                            line, severity, body)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, actionId, position, finding.id(),
                        finding.revision(), digest(encode(finding)),
                        finding.inline() ? "INLINE" : "TOP_LEVEL",
                        finding.path(), finding.inline() ? finding.line() : null,
                        finding.severity(), finding.body());
            }

            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch,
                        stage_id, stage_generation, attempt,
                        expected_code_fingerprint, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES (?, ?, 'PUBLISH_STANDALONE_REVIEW_PASS',
                        'GITHUB_EFFECT', 'TRUNK', ?,
                        'STANDALONE_REVIEW_PASS_PUBLICATION_RESULT', 32,
                        1, 0, 0, ?, ?, NULL, NULL, NULL, NULL, 1,
                        NULL, ?, NULL, 'REQUESTED', ?)
                    """, ticketId, operationId, subject.threadId(),
                    subject.workspaceId(), subject.threadId(),
                    subject.expectedHeadSha(), now.toEpochMilli());
            jdbc.update("""
                    INSERT INTO review_pass_publication_dispatch_v288(
                        publication_id, dispatch_ticket_id, operation_id,
                        dispatched_at_ms)
                    VALUES (?, ?, ?, ?)
                    """, actionId, ticketId, operationId, now.toEpochMilli());
            wakes.enqueue(ticketId, now);
            return findPublication(reviewPassId).orElseThrow();
        }), "review pass publication authorization returned null");
    }

    public Optional<PublicationView> findPublication(String reviewPassId)
    {
        requireText(reviewPassId, "reviewPassId");
        return jdbc.query("""
                SELECT review_pass_id, command_id, review_action,
                       finding_ids_json, status, external_effect_id,
                       evidence, last_error, finalized_at_ms
                FROM review_pass_publication_v288
                WHERE review_pass_id = ?
                """, (rs, row) -> publication(rs), reviewPassId)
                .stream().findFirst();
    }

    public Optional<CommentAction> findByOperationId(String operationId)
    {
        return jdbc.query("""
                SELECT publication.*
                FROM review_pass_publication_v288 publication
                JOIN review_pass_publication_dispatch_v288 dispatch
                  ON dispatch.publication_id = publication.id
                WHERE publication.operation_id = ?
                  AND dispatch.operation_id = publication.operation_id
                """, (rs, row) -> action(rs), operationId)
                .stream().findFirst();
    }

    @Override
    public CommentAction require(String operationId)
    {
        return findByOperationId(operationId).orElseThrow(() ->
                new IllegalStateException(
                        "exact review pass publication is missing"));
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
            CommentAction current = requireAction(actionId);
            boolean reclaimForProbe = current.status() == ActionStatus.CLAIMED
                    && mode == ClaimMode.PROBE;
            if (current.attemptCount() != expectedAttemptCount
                    || (!reclaimForProbe && !claimable(current.status()))) {
                throw new IllegalStateException(
                        "review pass publication claim lost or exhausted");
            }
            if ((!reclaimForProbe
                    && current.attemptCount() >= current.attemptLimit())
                    || !sameSubject(current)) {
                abandon(current, claimedAt,
                        "review pass publication is stale or exhausted");
                return requireAction(actionId);
            }
            int changed = reclaimForProbe
                    ? jdbc.update("""
                            UPDATE review_pass_publication_v288
                            SET claim_mode = 'PROBE', claim_owner = ?,
                                claimed_at_ms = ?, lease_until_ms = ?
                            WHERE id = ? AND status = 'CLAIMED'
                              AND attempt_count = ? AND lease_until_ms <= ?
                            """, claimOwner, claimedAt.toEpochMilli(),
                            leaseUntil.toEpochMilli(), actionId,
                            expectedAttemptCount, claimedAt.toEpochMilli())
                    : jdbc.update("""
                            UPDATE review_pass_publication_v288
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
                        "review pass publication claim lost or exhausted");
            }
            return requireAction(actionId);
        }), "review pass publication claim returned null");
    }

    @Override
    public void recordRecoveryBaseline(
            String actionId, int attempt, List<String> remoteEffectIds)
    {
        requireNonNull(remoteEffectIds, "remoteEffectIds is null");
        int changed = jdbc.update("""
                UPDATE review_pass_publication_v288
                SET recovery_baseline_json = ?
                WHERE id = ? AND status = 'CLAIMED' AND attempt_count = ?
                  AND recovery_baseline_json IS NULL
                """, encode(remoteEffectIds), actionId, attempt);
        if (changed != 1) {
            CommentAction current = requireAction(actionId);
            if (current.attemptCount() != attempt
                    || current.recoveryBaseline() == null
                    || !current.recoveryBaseline().equals(remoteEffectIds)) {
                throw new IllegalStateException(
                        "review pass publication baseline persistence lost");
            }
        }
    }

    @Override
    public boolean deferProbe(
            String actionId,
            int attempt,
            Instant observedAt,
            Instant retryAt,
            String evidence)
    {
        requireNonNull(observedAt, "observedAt is null");
        requireNonNull(retryAt, "retryAt is null");
        requireNonNull(evidence, "evidence is null");
        return requireNonNull(transactions.execute(ignored -> {
            jdbc.update("""
                    UPDATE review_pass_publication_v288
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
                    UPDATE review_pass_publication_v288
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
                    "review pass publication observation budget exhausted",
                    observedAt.toEpochMilli(), observedAt.toEpochMilli(),
                    actionId, attempt);
            if (changed != 1) {
                throw new IllegalStateException(
                        "review pass publication propagation wait lost its claim");
            }
            return requireAction(actionId).status() != ActionStatus.ABANDONED;
        }), "review pass publication propagation deferral returned null");
    }

    @Override
    public void finishSucceeded(
            String actionId,
            int attempt,
            String externalEffectId,
            String evidence,
            Instant completedAt)
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
                    UPDATE review_pass_publication_v288
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
                        "review pass publication failure delivery is stale");
            }
            return require(operationId);
        }), "review pass publication terminalization returned null");
    }

    public List<CommentAction> findCommittedUnfinalized(int limit)
    {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query("""
                SELECT publication.*
                FROM review_pass_publication_v288 publication
                JOIN review_pass_publication_dispatch_v288 dispatch
                  ON dispatch.publication_id = publication.id
                JOIN dispatch_ticket ticket
                  ON ticket.id = dispatch.dispatch_ticket_id
                WHERE publication.status IN (
                    'SUCCEEDED', 'CANCELED', 'ABANDONED')
                  AND publication.finalized_at_ms IS NULL
                  AND ticket.status IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                ORDER BY ticket.completed_at_ms, publication.id
                LIMIT ?
                """, (rs, row) -> action(rs), limit);
    }

    public void finalizeAction(
            String actionId, ActionStatus status, Instant finalizedAt)
    {
        transactions.executeWithoutResult(ignored -> {
            CommentAction action = requireAction(actionId);
            if (isFinalized(actionId)) {
                if (action.status() != status) {
                    throw new IllegalStateException(
                            "review pass publication finalization status changed");
                }
                return;
            }
            if (action.status() != status
                    || (status != ActionStatus.SUCCEEDED
                    && status != ActionStatus.CANCELED
                    && status != ActionStatus.ABANDONED)) {
                throw new IllegalStateException(
                        "review pass publication is not finalizable");
            }
            int expected = status == ActionStatus.SUCCEEDED
                    ? itemCount(actionId) : 0;
            int finalized = jdbc.update("""
                    UPDATE review_pass_publication_v288
                    SET finalized_at_ms = ?, posted_count = ?
                    WHERE id = ? AND status = ? AND finalized_at_ms IS NULL
                      AND completed_at_ms IS NOT NULL
                    """, finalizedAt.toEpochMilli(), expected, actionId,
                    status.name());
            if (finalized != 1) {
                throw new IllegalStateException(
                        "review pass publication finalization is stale");
            }
            if (status != ActionStatus.SUCCEEDED) {
                return;
            }
            int posted = jdbc.update("""
                    UPDATE review_findings
                    SET status = 'posted', posted_comment_id = NULL
                    WHERE status IN ('agreed', 'resolved', 'arbitrated')
                      AND EXISTS (
                          SELECT 1
                          FROM review_pass_publication_item_v288 item
                          WHERE item.publication_id = ?
                            AND item.finding_id = review_findings.id
                            AND item.finding_revision = review_findings.revision)
                    """, actionId);
            if (posted != expected) {
                throw new IllegalStateException(
                        "review pass publication finding revisions changed");
            }
            int published = jdbc.update("""
                    UPDATE review_passes
                    SET phase = 'published', verdict = ?, ended_at_ms = ?
                    WHERE id = ? AND host_kind = 'THREAD'
                      AND phase = 'terminate'
                    """, action.payload().reviewAction()
                    .toLowerCase(Locale.ROOT), finalizedAt.toEpochMilli(),
                    action.reviewPassId());
            if (published != 1) {
                throw new IllegalStateException(
                        "review pass publication lost its pass fence");
            }
        });
    }

    private Subject requirePass(String reviewPassId)
    {
        return jdbc.query("""
                SELECT pass.id, pass.thread_id, pass.host_kind, pass.host_id,
                       pass.phase, pass.repo_full_name, pass.pr_number,
                       pass.head_sha, pass.base_repository_id,
                       pass.head_repository_id, pass.head_ref,
                       trunk.workspace_id, trunk.flow, trunk.turn_version,
                       trunk.lifecycle_state
                FROM review_passes pass
                JOIN threads trunk ON trunk.id = pass.thread_id
                WHERE pass.id = ?
                """, (rs, row) -> new Subject(
                rs.getString("id"), rs.getString("thread_id"),
                rs.getString("host_kind"), rs.getString("host_id"),
                rs.getString("phase"), rs.getString("repo_full_name"),
                rs.getInt("pr_number"), rs.getString("head_sha"),
                rs.getString("base_repository_id"),
                rs.getString("head_repository_id"), rs.getString("head_ref"),
                rs.getString("workspace_id"), rs.getString("flow"),
                rs.getString("turn_version"), rs.getString("lifecycle_state")),
                reviewPassId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("no review pass: " + reviewPassId));
    }

    private static void requireStandalone(Subject subject)
    {
        if (!"THREAD".equals(subject.hostKind())) {
            throw new IllegalStateException(
                    "TASK_PHASE-hosted review passes are historical and cannot publish");
        }
        if (!subject.threadId().equals(subject.hostId())
                || !"review".equals(subject.flow())) {
            throw new IllegalStateException(
                    "review pass is not hosted by its standalone review thread");
        }
        if (!"V2".equals(subject.turnVersion())) {
            throw new IllegalStateException(
                    "Historical review thread is read-only and cannot publish");
        }
    }

    private static void requireAuthorizable(Subject subject)
    {
        if (!"terminate".equals(subject.phase())) {
            throw new IllegalStateException(
                    "review pass must be at TERMINATE before publication");
        }
        if (!"ACTIVE".equals(subject.lifecycleState())
                && !"IDLE".equals(subject.lifecycleState())) {
            throw new IllegalStateException(
                    "review thread is not active for publication");
        }
        requireText(subject.workspaceId(), "review workspace");
        requireText(subject.remoteRepositoryId(), "review base repository");
        requireText(subject.headRepositoryId(), "review head repository");
        requireText(subject.branchName(), "review head branch");
        requireText(subject.expectedHeadSha(), "reviewed head SHA");
        if (!subject.repoFullName().equalsIgnoreCase(
                subject.remoteRepositoryId())) {
            throw new IllegalStateException(
                    "review pass base repository differs from its PR");
        }
    }

    private Finding requireFinding(String reviewPassId, String findingId)
    {
        requireText(findingId, "findingId");
        return jdbc.query("""
                SELECT id, review_pass_id, revision, path, line, severity,
                       status, body
                FROM review_findings
                WHERE id = ? AND review_pass_id = ?
                """, (rs, row) -> new Finding(
                rs.getString("id"), rs.getString("review_pass_id"),
                rs.getInt("revision"), rs.getString("path"),
                (Integer) rs.getObject("line"), rs.getString("severity"),
                rs.getString("status"), rs.getString("body")),
                findingId, reviewPassId).stream().findFirst()
                .filter(Finding::eligible)
                .orElseThrow(() -> new IllegalStateException(
                        "finding is missing or not eligible: " + findingId));
    }

    private ActionPayload payload(
            Subject subject,
            List<Finding> findings,
            String reviewAction,
            String requestDigest)
    {
        List<FrozenDraft> drafts = findings.stream()
                .filter(Finding::inline)
                .map(finding -> new FrozenDraft(
                        "review-pass-finding:" + finding.id(), "file-line",
                        finding.path(), finding.line(), "RIGHT", null, null,
                        renderFinding(finding), finding.id()))
                .toList();
        List<String> wholePr = new ArrayList<>();
        for (Finding finding : findings) {
            if (!finding.inline()) {
                wholePr.add("- " + renderFinding(finding));
            }
        }
        StringBuilder body = new StringBuilder(reviewSummary(subject.passId()));
        if (!wholePr.isEmpty()) {
            body.append("\n\n**Whole-PR notes**\n")
                    .append(String.join("\n", wholePr));
        }
        body.append("\n\n<!-- bytequay-review-pass:")
                .append(subject.passId()).append(':')
                .append(requestDigest).append(" -->");
        return new ActionPayload(
                1, body.toString(), reviewAction, null, drafts);
    }

    private String reviewSummary(String reviewPassId)
    {
        return jdbc.query("""
                SELECT body FROM review_messages
                WHERE review_pass_id = ? AND phase = 'independent'
                  AND length(trim(body)) > 0
                ORDER BY created_at_ms DESC, id DESC
                LIMIT 1
                """, (rs, row) -> rs.getString("body").strip(), reviewPassId)
                .stream().findFirst().orElse("Review by ByteQuay panel.");
    }

    private boolean sameSubject(CommentAction action)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM review_pass_publication_v288 publication
                JOIN review_passes pass ON pass.id = publication.review_pass_id
                JOIN threads trunk ON trunk.id = publication.thread_id
                WHERE publication.id = ?
                  AND publication.operation_id = ?
                  AND publication.review_pass_id = ?
                  AND publication.thread_id = ?
                  AND publication.command_id = ?
                  AND publication.workspace_id = ?
                  AND publication.remote_repository_id = ? COLLATE NOCASE
                  AND publication.head_repository_id = ? COLLATE NOCASE
                  AND publication.remote_pr_number = ?
                  AND publication.branch_name = ?
                  AND publication.expected_head_sha = ?
                  AND pass.host_kind = 'THREAD'
                  AND pass.host_id = pass.thread_id
                  AND pass.phase = 'terminate'
                  AND pass.head_sha = publication.expected_head_sha
                  AND trunk.turn_version = 'V2' AND trunk.flow = 'review'
                  AND (SELECT COUNT(*)
                       FROM review_pass_publication_item_v288 item
                       WHERE item.publication_id = publication.id)
                      = json_array_length(publication.finding_ids_json)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM review_pass_publication_item_v288 item
                      LEFT JOIN review_findings finding
                        ON finding.id = item.finding_id
                       AND finding.review_pass_id = pass.id
                       AND finding.revision = item.finding_revision
                       AND finding.status IN (
                           'agreed', 'resolved', 'arbitrated')
                      WHERE item.publication_id = publication.id
                        AND finding.id IS NULL)
                """, Integer.class, action.id(), action.operationId(),
                action.reviewPassId(), action.threadId(), action.commandId(),
                action.workspaceId(), action.remoteRepositoryId(),
                action.headRepositoryId(), action.pullRequestNumber(),
                action.branchName(), action.expectedHeadSha());
        return count != null && count == 1;
    }

    private CommentAction requireAction(String actionId)
    {
        return jdbc.query("""
                SELECT * FROM review_pass_publication_v288 WHERE id = ?
                """, (rs, row) -> action(rs), actionId)
                .stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "exact review pass publication is missing"));
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

    private PublicationView publication(ResultSet rs)
            throws SQLException
    {
        ActionStatus actionStatus = ActionStatus.valueOf(rs.getString("status"));
        String status;
        boolean terminal = false;
        if (actionStatus == ActionStatus.SUCCEEDED
                && rs.getObject("finalized_at_ms") != null) {
            status = "PUBLISHED";
            terminal = true;
        }
        else {
            status = switch (actionStatus) {
                case REQUESTED -> "QUEUED";
                case CLAIMED, SUCCEEDED -> "PUBLISHING";
                case INDETERMINATE -> "INDETERMINATE";
                case FAILED, CANCELED, ABANDONED -> "FAILED";
            };
            terminal = actionStatus == ActionStatus.CANCELED
                    || actionStatus == ActionStatus.ABANDONED;
        }
        return new PublicationView(
                rs.getString("review_pass_id"), rs.getString("command_id"),
                status, terminal, rs.getString("review_action"),
                decodeStringList(rs.getString("finding_ids_json")),
                rs.getString("external_effect_id"), rs.getString("evidence"),
                rs.getString("last_error"));
    }

    private void abandon(CommentAction action, Instant now, String detail)
    {
        int changed = jdbc.update("""
                UPDATE review_pass_publication_v288
                SET status = 'ABANDONED', claim_mode = NULL,
                    claim_owner = NULL, claimed_at_ms = NULL,
                    lease_until_ms = NULL, external_effect_id = NULL,
                    evidence = NULL, last_error = ?, completed_at_ms = ?
                WHERE id = ? AND status IN (
                    'REQUESTED', 'FAILED', 'INDETERMINATE', 'CLAIMED')
                """, detail, now.toEpochMilli(), action.id());
        if (changed != 1) {
            throw new IllegalStateException(
                    "review pass publication abandonment lost");
        }
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
        int changed = jdbc.update("""
                UPDATE review_pass_publication_v288
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
                    "review pass publication result lost");
        }
    }

    private boolean isFinalized(String actionId)
    {
        Boolean value = jdbc.queryForObject("""
                SELECT finalized_at_ms IS NOT NULL
                FROM review_pass_publication_v288 WHERE id = ?
                """, Boolean.class, actionId);
        return Boolean.TRUE.equals(value);
    }

    private int itemCount(String actionId)
    {
        Integer value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM review_pass_publication_item_v288
                WHERE publication_id = ?
                """, Integer.class, actionId);
        return value == null ? 0 : value;
    }

    private String encode(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (Exception failure) {
            throw new IllegalStateException(
                    "could not encode review pass publication", failure);
        }
    }

    private ActionPayload decodePayload(String value)
    {
        try {
            return payloadReader.readValue(value);
        }
        catch (Exception failure) {
            throw new IllegalStateException(
                    "could not decode review pass publication payload", failure);
        }
    }

    private List<String> decodeBaseline(String value)
    {
        return value == null ? null : decodeStringList(value);
    }

    private List<String> decodeStringList(String value)
    {
        try {
            return json.readValue(value, STRING_LIST);
        }
        catch (Exception failure) {
            throw new IllegalStateException(
                    "could not decode review pass publication list", failure);
        }
    }

    private static boolean claimable(ActionStatus status)
    {
        return status == ActionStatus.REQUESTED
                || status == ActionStatus.FAILED
                || status == ActionStatus.INDETERMINATE;
    }

    private static String renderFinding(Finding finding)
    {
        return "[" + finding.severity() + "] " + finding.body();
    }

    private static String normalizeReviewAction(String value)
    {
        String action = requireText(value, "reviewAction")
                .trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "COMMENT", "APPROVE", "REQUEST_CHANGES" -> action;
            default -> throw new IllegalArgumentException(
                    "unsupported review action " + value);
        };
    }

    public record PublicationView(
            String reviewPassId,
            String commandId,
            String status,
            boolean terminal,
            String reviewAction,
            List<String> findingIds,
            String externalEffectId,
            String evidence,
            String lastError)
    {
        public PublicationView
        {
            findingIds = List.copyOf(requireNonNull(
                    findingIds, "findingIds is null"));
        }
    }

    private record PublicationRequest(
            String reviewPassId,
            String reviewAction,
            String expectedHeadSha,
            List<Finding> findings)
    {}

    private record Finding(
            String id,
            String reviewPassId,
            int revision,
            String path,
            Integer line,
            String severity,
            String status,
            String body)
    {
        private boolean inline()
        {
            return path != null && !path.isBlank() && line != null && line > 0;
        }

        private boolean eligible()
        {
            return "agreed".equals(status)
                    || "resolved".equals(status)
                    || "arbitrated".equals(status);
        }
    }

    private record Subject(
            String passId,
            String threadId,
            String hostKind,
            String hostId,
            String phase,
            String repoFullName,
            int pullRequestNumber,
            String expectedHeadSha,
            String remoteRepositoryId,
            String headRepositoryId,
            String branchName,
            String workspaceId,
            String flow,
            String turnVersion,
            String lifecycleState)
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
