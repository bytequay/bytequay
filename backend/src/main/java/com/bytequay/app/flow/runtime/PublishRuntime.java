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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.gate.UserGates.PublishDisposition;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionHandle;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionReservation;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleClaimException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleOwnerRevisionException;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Owns publication dispatch, execution authority, settlement, and recovery. */
final class PublishRuntime
{
    private static final int PUBLISH_PRIORITY = 850;

    private final FlowRuntime runtime;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    PublishRuntime(FlowRuntime runtime, JdbcTemplate jdbc, Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Locks the exact local PR shape required by a typed publication plan. */
    private PullRequestSubject lockPullRequestForPublish(
            String prId, String kind)
    {
        requireText(prId, "prId");
        requireText(kind, "kind");
        int locked = jdbc.update(
                "UPDATE flow_runtime_pr SET pr_id = pr_id "
                        + "WHERE pr_id = ? AND ((? = 'CI_UPDATE' "
                        + "AND remote_identity_id IS NOT NULL) OR "
                        + "(? = 'INITIAL_PUBLISH' "
                        + "AND remote_identity_id IS NULL))",
                prId, kind, kind);
        if (locked != 1) {
            throw new StaleOwnerRevisionException(
                    "publication PR shape is unavailable");
        }
        return runtime.requirePullRequest(prId);
    }

    /** Reserves one exact GitHub-owned publication operation and barrier. */
    Operation reservePublishOperation(
            String operationId,
            String planId,
            String taskId,
            String expectedChangeSetRevisionId,
            String planDigest,
            Instant createdAt)
    {
        requireText(operationId, "operationId");
        requireText(planId, "planId");
        requireText(taskId, "taskId");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireText(planDigest, "planDigest");
        requireNonNull(createdAt, "createdAt is null");
        return runtime.inTransaction(() -> {
            int taskLocked = jdbc.update(
                    """
                    UPDATE flow_runtime_task SET task_id = task_id
                    WHERE task_id = ? AND status = 'ACTIVE'
                      AND current_change_set_revision_id = ?
                      AND selected_writer_operation_id IS NULL
                      AND waiting_mutation_state_ref IS NULL
                    """,
                    taskId,
                    expectedChangeSetRevisionId);
            Task task = runtime.requireTask(taskId);
            Integer liveLease = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flow_runtime_writer_lease "
                            + "WHERE task_id = ?",
                    Integer.class,
                    taskId);
            Integer liveReviewer = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_operation
                    WHERE task_id = ? AND kind = 'RUN_REVIEWER'
                      AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                    """,
                    Integer.class,
                    taskId);
            if (taskLocked != 1
                    || task.status() != TaskStatus.ACTIVE
                    || task.selectedWriterOperationId() != null
                    || task.waitingMutationStateRef() != null
                    || !expectedChangeSetRevisionId.equals(
                            task.currentChangeSetRevisionId())
                    || requireNonNull(liveLease,
                            "writer lease count is null") != 0
                    || requireNonNull(liveReviewer,
                            "reviewer count is null") != 0
                    || hasNonterminalPublish(taskId)) {
                throw new StaleOwnerRevisionException(
                        "Task cannot reserve publication authority");
            }
            runtime.insertOperationAndTicket(
                    operationId,
                    "GITHUB_EFFECT_PLAN",
                    planId,
                    taskId,
                    OperationKind.PUBLISH,
                    planDigest,
                    planId,
                    null,
                    PUBLISH_PRIORITY,
                    createdAt);
            return runtime.requireOperation(operationId);
        });
    }


    /** Claims the oldest exact authorized publication plan for one PR. */
    Optional<Claim> claimNextPublish(
            String workerId, Duration claimTtl)
    {
        return claimNextPublish(workerId, claimTtl, null, Integer.MAX_VALUE);
    }

    /** Claims only an INITIAL plan for its bounded owner-specific lane. */
    Optional<Claim> claimNextInitialPublish(
            String workerId, Duration claimTtl, int capacity)
    {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return claimNextPublish(
                workerId, claimTtl, "INITIAL_PUBLISH", capacity);
    }

    /** Claims only a CI_UPDATE plan for its disjoint owner-specific lane. */
    Optional<Claim> claimNextCiUpdatePublish(
            String workerId, Duration claimTtl, int capacity)
    {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return claimNextPublish(
                workerId, claimTtl, "CI_UPDATE", capacity);
    }

    private Optional<Claim> claimNextPublish(
            String workerId, Duration claimTtl, String requiredKind,
            int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        return runtime.inTransaction(() -> {
            if (runtime.activeDispatchCount() >= capacity) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<PublishClaimCandidate> candidates = jdbc.query(
                    """
                    SELECT o.operation_id, o.task_id, p.pr_id, p.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_github_effect_plan_envelope p
                      ON p.operation_id = o.operation_id
                     AND p.plan_id = o.owner_id
                     AND p.plan_id = o.input_ref
                     AND p.plan_digest = o.subject_digest
                    JOIN flow_user_gate_authorization a
                      ON a.authorization_id = p.authorization_id
                     AND a.effect_plan_ref = p.plan_id
                     AND a.operation_id = p.operation_id
                     AND a.pr_id = p.pr_id
                     AND a.action_digest = p.action_digest
                    WHERE o.kind = 'PUBLISH'
                      AND (? IS NULL OR p.kind = ?)
                      AND o.owner_kind = 'GITHUB_EFFECT_PLAN'
                      AND o.state IN ('READY', 'RETRYABLE')
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND EXISTS (
                          SELECT 1
                          FROM flow_user_gate_transition t
                          WHERE t.gate_id = a.gate_id
                            AND t.gate_revision = a.gate_revision
                            AND (t.to_state IN (
                                    'AUTHORIZED', 'NEEDS_ATTENTION'
                                ) OR (t.to_state = 'EXECUTING' AND EXISTS (
                                    SELECT 1
                                    FROM flow_github_external_effect_attempt x
                                    WHERE x.operation_id = o.operation_id
                                      AND x.plan_id = p.plan_id
                                    UNION ALL
                                    SELECT 1
                                    FROM flow_github_initial_publish_attempt x
                                    WHERE x.operation_id = o.operation_id
                                      AND x.plan_id = p.plan_id
                                )))
                            AND t.sequence = (
                                SELECT MAX(t2.sequence)
                                FROM flow_user_gate_transition t2
                                WHERE t2.gate_id = t.gate_id
                                  AND t2.gate_revision = t.gate_revision
                            )
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM flow_github_effect_plan_envelope earlier
                          JOIN flow_runtime_operation earlier_operation
                            ON earlier_operation.operation_id =
                                earlier.operation_id
                          WHERE earlier.pr_id = p.pr_id
                            AND earlier.pr_sequence < p.pr_sequence
                            AND earlier_operation.state IN (
                                'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                            )
                      )
                    ORDER BY d.priority DESC, d.not_before, o.operation_id
                    LIMIT 32
                    """,
                    (result, row) -> new PublishClaimCandidate(
                            result.getString("operation_id"),
                            result.getString("task_id"),
                            result.getString("pr_id"),
                            result.getString("kind"),
                            result.getLong("claim_generation")),
                    requiredKind, requiredKind, now.toEpochMilli());
            for (PublishClaimCandidate candidate : candidates) {
                lockPullRequestForPublish(candidate.prId(), candidate.kind());
                if (!publishCandidateStillEligible(candidate, now)) {
                    continue;
                }
                long generation = candidate.generation() + 1;
                String token = UUID.randomUUID().toString();
                Instant expiresAt = now.plus(claimTtl);
                int ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET claim_owner = ?, claim_expires_at = ?,
                            claim_generation = ?, claim_token = ?,
                            delivery_state = 'CLAIMED'
                        WHERE operation_id = ?
                          AND delivery_state = 'AVAILABLE'
                          AND claim_generation = ?
                        """,
                        workerId,
                        expiresAt.toEpochMilli(),
                        generation,
                        token,
                        candidate.operationId(),
                        candidate.generation());
                if (ticketUpdated == 0) {
                    continue;
                }
                int operationUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_operation
                        SET state = 'CLAIMED', attempt = attempt + 1
                        WHERE operation_id = ?
                          AND state IN ('READY', 'RETRYABLE')
                        """,
                        candidate.operationId());
                if (operationUpdated != 1) {
                    throw new IllegalStateException(
                            "publication ticket claimed without READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(),
                        candidate.taskId(),
                        OperationKind.PUBLISH,
                        generation,
                        token,
                        workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }


    /** Requires one current exact PUBLISH claim. */
    Operation assertPublishClaim(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return runtime.inTransaction(() -> {
            if (claim.kind() != OperationKind.PUBLISH) {
                throw new IllegalArgumentException(
                        "claim is not a publication claim");
            }
            runtime.assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation operation = runtime.requireOperation(claim.operationId());
            if (operation.kind() != OperationKind.PUBLISH
                    || !operation.ownerKind().equals("GITHUB_EFFECT_PLAN")
                    || !operation.ownerId().equals(operation.inputRef())) {
                throw new IllegalStateException(
                        "publication operation graph is invalid");
            }
            return operation;
        });
    }

    PublishExecutionHandle mintPublishExecutionHandle(
            Claim claim, String attemptId)
    {
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        PublishExecutionReservation reservation =
                reservePublishExecutionHandle(claim, attemptId);
        return mintPublishExecutionHandle(
                claim, attemptId, reservation, reservation.tokenDigest());
    }

    PublishExecutionReservation reservePublishExecutionHandle(
            Claim claim, String attemptId)
    {
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        assertPublishClaim(claim);
        return new PublishExecutionReservation(
                claim.operationId(), claim.generation(), attemptId,
                FlowRuntime.stableId("publish-execution-token", UUID.randomUUID().toString()));
    }

    /** Mints only from the original one-use precursor after owner persistence. */
    PublishExecutionHandle mintPublishExecutionHandle(
            Claim claim, String attemptId,
            PublishExecutionReservation reservation,
            String expectedTokenDigest)
    {
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        requireNonNull(reservation, "reservation is null");
        requireText(expectedTokenDigest, "expectedTokenDigest");
        assertPublishClaim(claim);
        if (!reservation.operationId.equals(claim.operationId())
                || reservation.generation != claim.generation()
                || !reservation.attemptId.equals(attemptId)
                || !reservation.tokenDigest.equals(expectedTokenDigest)
                || !reservation.minted.compareAndSet(false, true)) {
            throw new StaleClaimException(
                    "publication execution reservation is invalid or consumed");
        }
        return new PublishExecutionHandle(claim.operationId(),
                claim.generation(), attemptId, expectedTokenDigest);
    }

    void consumePublishExecutionHandle(
            PublishExecutionHandle handle,
            Claim claim,
            String attemptId,
            String expectedTokenDigest)
    {
        requireNonNull(handle, "handle is null");
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        requireText(expectedTokenDigest, "expectedTokenDigest");
        assertPublishClaim(claim);
        if (!handle.operationId.equals(claim.operationId())
                || handle.generation != claim.generation()
                || !handle.attemptId.equals(attemptId)
                || !handle.tokenDigest.equals(expectedTokenDigest)
                || !handle.consumed.compareAndSet(false, true)) {
            throw new StaleClaimException(
                    "publication execution permission is invalid or consumed");
        }
    }

    Operation assertPublishAttemptResult(
            PublishExecutionHandle handle,
            Claim claim,
            String attemptId,
            String expectedTokenDigest)
    {
        requireNonNull(handle, "handle is null");
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        requireText(expectedTokenDigest, "expectedTokenDigest");
        if (!handle.operationId.equals(claim.operationId())
                || handle.generation != claim.generation()
                || !handle.attemptId.equals(attemptId)
                || !handle.tokenDigest.equals(expectedTokenDigest)
                || !handle.consumed.get()) {
            throw new StaleClaimException(
                    "publication result permission is invalid");
        }
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PUBLISH' AND o.state = 'CLAIMED'
                  AND d.delivery_state = 'CLAIMED'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                Integer.class,
                claim.operationId(),
                claim.taskId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId());
        if (requireNonNull(exact,
                "publication result authority count is null") != 1) {
            throw new StaleClaimException(
                    "publication result generation was already settled");
        }
        return runtime.requireOperation(claim.operationId());
    }

    void assertPublishTerminalReplay(
            Claim claim, String receiptId)
    {
        requireNonNull(claim, "claim is null");
        requireText(receiptId, "receiptId");
        if (claim.kind() != OperationKind.PUBLISH) {
            throw new StaleClaimException(
                    "publication terminal replay claim kind is invalid");
        }
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PUBLISH' AND o.state = 'SUCCEEDED'
                  AND o.result_ref = ? AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                Integer.class,
                claim.operationId(),
                claim.taskId(),
                receiptId,
                claim.generation(),
                claim.claimToken(),
                claim.workerId());
        if (requireNonNull(exact,
                "publication replay authority count is null") != 1) {
            throw new StaleClaimException(
                    "publication terminal replay authority is invalid");
        }
    }

    void assertPublishAttemptTerminalReplay(
            PublishExecutionHandle handle,
            Claim claim,
            String attemptId,
            String tokenDigest,
            String receiptId)
    {
        requireNonNull(handle, "handle is null");
        requireText(attemptId, "attemptId");
        requireText(tokenDigest, "tokenDigest");
        if (!handle.operationId.equals(claim.operationId())
                || handle.generation != claim.generation()
                || !handle.attemptId.equals(attemptId)
                || !handle.tokenDigest.equals(tokenDigest)
                || !handle.consumed.get()) {
            throw new StaleClaimException(
                    "publication terminal result handle is invalid");
        }
        assertPublishTerminalReplay(claim, receiptId);
    }

    /** Validates response-loss replay of one nonterminal initial step receipt. */
    void assertInitialStepReceiptReplay(
            Claim claim, String receiptId, String attemptId,
            String expectedClaimTokenDigest)
    {
        requireNonNull(claim, "claim is null");
        requireText(receiptId, "receiptId");
        requireText(attemptId, "attemptId");
        requireText(expectedClaimTokenDigest, "expectedClaimTokenDigest");
        if (claim.kind() != OperationKind.PUBLISH) {
            throw new StaleClaimException(
                    "initial receipt replay claim kind is invalid");
        }
        List<Object[]> matches = jdbc.query(
                """
                SELECT q.claim_generation AS probe_generation,
                       q.claim_token_digest, r.step_ordinal,
                       o.state, o.result_ref,
                       d.claim_generation AS ticket_generation,
                       d.delivery_state, d.claim_owner, d.claim_token
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_github_initial_publish_step_receipt r
                  ON r.operation_id = o.operation_id
                 AND r.receipt_id = ?
                JOIN flow_github_initial_publish_plan p
                  ON p.plan_id = r.plan_id
                 AND p.operation_id = r.operation_id
                 AND o.owner_kind = 'GITHUB_EFFECT_PLAN'
                 AND o.owner_id = p.plan_id AND o.input_ref = p.plan_id
                 AND o.subject_digest = p.plan_digest
                JOIN flow_github_initial_publish_attempt a
                  ON a.attempt_id = ?
                 AND a.attempt_id = r.attempt_id
                 AND a.operation_id = r.operation_id
                 AND a.plan_id = r.plan_id
                 AND a.step_id = r.step_id
                JOIN flow_github_initial_publish_probe q
                  ON q.probe_id = r.probe_id
                 AND q.operation_id = r.operation_id
                 AND q.plan_id = r.plan_id
                 AND q.step_id = r.step_id
                 AND q.attempt_id = r.attempt_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PUBLISH'
                """,
                (result, row) -> new Object[] {
                        result.getLong("probe_generation"),
                        result.getString("claim_token_digest"),
                        result.getInt("step_ordinal"),
                        result.getString("state"),
                        result.getString("result_ref"),
                        result.getLong("ticket_generation"),
                        result.getString("delivery_state"),
                        result.getString("claim_owner"),
                        result.getString("claim_token")},
                receiptId, attemptId, claim.operationId(), claim.taskId());
        if (matches.size() != 1) {
            throw new StaleClaimException(
                    "initial receipt replay authority is invalid");
        }
        Object[] exact = matches.getFirst();
        long probeGeneration = (long) exact[0];
        String probeTokenDigest = (String) exact[1];
        int ordinal = (int) exact[2];
        OperationState operationState = OperationState.valueOf((String) exact[3]);
        String resultRef = (String) exact[4];
        long ticketGeneration = (long) exact[5];
        String delivery = (String) exact[6];
        String owner = (String) exact[7];
        String token = (String) exact[8];
        boolean currentBranchReplay = ordinal == 1
                && ticketGeneration == claim.generation()
                && operationState == OperationState.RETRYABLE
                && Objects.equals(resultRef,
                        "INITIAL_STEP_RECEIPT:" + receiptId)
                && delivery.equals("AVAILABLE")
                && owner == null && token == null;
        boolean laterBranchReplay = ordinal == 1
                && ticketGeneration > claim.generation();
        boolean currentPrReplay = ordinal == 2
                && ticketGeneration == claim.generation()
                && operationState == OperationState.CLAIMED
                && delivery.equals("CLAIMED")
                && Objects.equals(owner, claim.workerId())
                && Objects.equals(token, claim.claimToken());
        if (probeGeneration != claim.generation()
                || !probeTokenDigest.equals(expectedClaimTokenDigest)
                || ticketGeneration < claim.generation()
                || !(currentBranchReplay
                        || laterBranchReplay || currentPrReplay)) {
            throw new StaleClaimException(
                    "initial receipt replay authority is invalid: probe="
                            + probeGeneration + ", claim=" + claim.generation()
                            + ", ordinal=" + ordinal + ", ticket="
                            + ticketGeneration + ", operation="
                            + operationState + ", delivery=" + delivery
                            + ", result=" + resultRef);
        }
    }

    /** Applies only a disposition minted by the owning UserGates transaction. */
    void settleClaimedPublish(
            Claim claim,
            PublishExecutionHandle handle,
            String attemptId,
            String tokenDigest,
            PublishDisposition disposition)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(disposition, "disposition is null");
        if (!disposition.operationId().equals(claim.operationId())) {
            throw new StaleClaimException(
                    "publication disposition owns another operation");
        }
        runtime.inTransaction(() -> {
            if (handle == null) {
                assertPublishClaim(claim);
            }
            else {
                assertPublishAttemptResult(
                        handle, claim, attemptId, tokenDigest);
            }
            switch (disposition.kind()) {
                case SUCCEEDED -> {
                    runtime.settleDispatch(
                            claim.operationId(),
                            OperationState.SUCCEEDED,
                            disposition.resultRef());
                    runtime.resumeWaitingReconciliation(claim.taskId());
                }
                case CANCELED -> {
                    runtime.settleDispatch(
                            claim.operationId(),
                            OperationState.CANCELED,
                            disposition.resultRef());
                    runtime.resumeWaitingReconciliation(claim.taskId());
                }
                case RETRY -> retryPublishDispatch(
                        claim,
                        disposition.resultRef(),
                        disposition.retryAt());
            }
            return Boolean.TRUE;
        });
    }

    private void retryPublishDispatch(
            Claim claim, String resultRef, Instant notBefore)
    {
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = 'RETRYABLE', result_ref = ?
                WHERE operation_id = ? AND state = 'CLAIMED'
                """,
                resultRef,
                claim.operationId());
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'AVAILABLE', not_before = ?,
                    claim_owner = NULL, claim_expires_at = NULL,
                    claim_token = NULL
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                  AND claim_generation = ? AND claim_token = ?
                """,
                notBefore.toEpochMilli(),
                claim.operationId(),
                claim.generation(),
                claim.claimToken());
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "publication changed during retry scheduling");
        }
    }

    /** Releases only the current claim so the same PUBLISH can run its next step. */
    void rearmClaimedPublishStep(
            Claim claim, String stepReceiptId, Instant notBefore)
    {
        requireNonNull(claim, "claim is null");
        requireText(stepReceiptId, "stepReceiptId");
        requireNonNull(notBefore, "notBefore is null");
        runtime.inTransaction(() -> {
            assertPublishClaim(claim);
            retryPublishDispatch(claim,
                    "INITIAL_STEP_RECEIPT:" + stepReceiptId, notBefore);
            return Boolean.TRUE;
        });
    }

    /** Reads one exact canceled disposition for response-loss replay. */
    public Optional<String> canceledPublishResult(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        if (claim.kind() != OperationKind.PUBLISH) {
            throw new IllegalArgumentException(
                    "claim is not a publication claim");
        }
        return jdbc.query(
                """
                SELECT o.result_ref
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PUBLISH' AND o.state = 'CANCELED'
                  AND o.owner_kind = 'GITHUB_EFFECT_PLAN'
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                (result, row) -> result.getString("result_ref"),
                claim.operationId(),
                claim.taskId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId()).stream().findFirst();
    }

    /** Redrives the same never-provider-started PUBLISH operation. */
    void redriveExpiredPublish(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(operationId);
            if (operation.kind() != OperationKind.PUBLISH) {
                throw new IllegalArgumentException(
                        "operation is not PUBLISH");
            }
            Integer replay = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_dispatch_ticket
                    WHERE operation_id = ? AND claim_generation = ?
                      AND delivery_state = 'AVAILABLE'
                      AND claim_owner IS NULL AND claim_expires_at IS NULL
                      AND claim_token IS NULL
                    """,
                    Integer.class,
                    operationId,
                    generation);
            if (operation.state() == OperationState.READY
                    && requireNonNull(replay,
                            "publication replay count is null") == 1) {
                return Boolean.TRUE;
            }
            ExpiredClaim expired = runtime.requireExpiredClaim(
                    operationId, generation);
            if (expired.processAttemptId() != null) {
                throw new IllegalStateException(
                        "PUBLISH cannot have an agent process attempt");
            }
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'READY'
                    WHERE operation_id = ? AND state = 'CLAIMED'
                    """,
                    operationId);
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                        claim_expires_at = NULL, claim_token = NULL
                    WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                      AND claim_generation = ?
                    """,
                    operationId,
                    generation);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new StaleClaimException(
                        "expired PUBLISH changed during redrive");
            }
            return Boolean.TRUE;
        });
    }

    /** Redrives an activated publication only for remote probing. */
    void redriveExpiredPublishProbeOnly(
            String operationId, long generation, Instant notBefore)
    {
        requireText(operationId, "operationId");
        requireNonNull(notBefore, "notBefore is null");
        runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(operationId);
            Integer attempts = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT attempt_id
                        FROM flow_github_external_effect_attempt
                        WHERE operation_id = ?
                        UNION ALL
                        SELECT a.attempt_id
                        FROM flow_github_initial_publish_attempt a
                        WHERE a.operation_id = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM flow_github_initial_publish_step_receipt r
                              WHERE r.step_id = a.step_id
                          )
                    ) current_attempt
                    """,
                    Integer.class,
                    operationId,
                    operationId);
            if (operation.kind() != OperationKind.PUBLISH
                    || requireNonNull(attempts,
                            "publication attempt count is null") == 0) {
                throw new IllegalStateException(
                        "probe-only recovery requires a provider attempt");
            }
            Integer replay = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_dispatch_ticket
                    WHERE operation_id = ? AND claim_generation = ?
                      AND delivery_state = 'AVAILABLE'
                      AND claim_owner IS NULL AND claim_expires_at IS NULL
                      AND claim_token IS NULL
                    """,
                    Integer.class,
                    operationId,
                    generation);
            if (operation.state() == OperationState.RETRYABLE
                    && operation.resultRef().equals("PROBE_REQUIRED")
                    && requireNonNull(replay,
                            "probe recovery replay count is null") == 1) {
                return Boolean.TRUE;
            }
            ExpiredClaim expired = runtime.requireExpiredClaim(
                    operationId, generation);
            if (expired.processAttemptId() != null) {
                throw new IllegalStateException(
                        "PUBLISH cannot have an agent process attempt");
            }
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'RETRYABLE', result_ref = 'PROBE_REQUIRED'
                    WHERE operation_id = ? AND state = 'CLAIMED'
                    """,
                    operationId);
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', not_before = ?,
                        claim_owner = NULL, claim_expires_at = NULL,
                        claim_token = NULL
                    WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                      AND claim_generation = ?
                    """,
                    notBefore.toEpochMilli(),
                    operationId,
                    generation);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new StaleClaimException(
                        "expired PUBLISH changed during probe-only redrive");
            }
            return Boolean.TRUE;
        });
    }


    boolean hasNonterminalPublish(String taskId)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'PUBLISH'
                  AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                """,
                Integer.class,
                taskId);
        return requireNonNull(count,
                "publication barrier count is null") != 0;
    }

    private boolean publishCandidateStillEligible(
            PublishClaimCandidate candidate, Instant now)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_github_effect_plan_envelope p
                  ON p.operation_id = o.operation_id
                 AND p.plan_id = o.owner_id
                 AND p.plan_id = o.input_ref
                 AND p.plan_digest = o.subject_digest
                JOIN flow_user_gate_authorization a
                  ON a.authorization_id = p.authorization_id
                 AND a.effect_plan_ref = p.plan_id
                 AND a.operation_id = p.operation_id
                 AND a.pr_id = p.pr_id
                 AND a.action_digest = p.action_digest
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND p.kind = ?
                  AND o.kind = 'PUBLISH'
                  AND o.owner_kind = 'GITHUB_EFFECT_PLAN'
                  AND o.state IN ('READY', 'RETRYABLE')
                  AND d.delivery_state = 'AVAILABLE'
                  AND d.claim_generation = ? AND d.not_before <= ?
                  AND EXISTS (
                      SELECT 1 FROM flow_user_gate_transition t
                      WHERE t.gate_id = a.gate_id
                        AND t.gate_revision = a.gate_revision
                        AND (t.to_state IN (
                                'AUTHORIZED', 'NEEDS_ATTENTION'
                            ) OR (t.to_state = 'EXECUTING' AND EXISTS (
                                SELECT 1
                                FROM flow_github_external_effect_attempt x
                                WHERE x.operation_id = o.operation_id
                                  AND x.plan_id = p.plan_id
                                UNION ALL
                                SELECT 1
                                FROM flow_github_initial_publish_attempt x
                                WHERE x.operation_id = o.operation_id
                                  AND x.plan_id = p.plan_id
                            )))
                        AND t.sequence = (
                            SELECT MAX(t2.sequence)
                            FROM flow_user_gate_transition t2
                            WHERE t2.gate_id = t.gate_id
                              AND t2.gate_revision = t.gate_revision
                        )
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM flow_github_effect_plan_envelope earlier
                      JOIN flow_runtime_operation earlier_operation
                        ON earlier_operation.operation_id = earlier.operation_id
                      WHERE earlier.pr_id = p.pr_id
                        AND earlier.pr_sequence < p.pr_sequence
                        AND earlier_operation.state IN (
                            'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                        )
                  )
                """,
                Integer.class,
                candidate.operationId(),
                candidate.taskId(),
                candidate.kind(),
                candidate.generation(),
                now.toEpochMilli());
        return requireNonNull(count,
                "publish candidate count is null") == 1;
    }


    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void requirePositive(Duration value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record PublishClaimCandidate(
            String operationId,
            String taskId,
            String prId,
            String kind,
            long generation) {}
}
