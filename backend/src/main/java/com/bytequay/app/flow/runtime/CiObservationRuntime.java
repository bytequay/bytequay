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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Owns the receipt-bound CI observation state machine.
 *
 * <p>{@link FlowRuntime} remains the synchronized facade while the runtime is
 * extracted vertically. Generic task and dispatch primitives still live there
 * until their owners are extracted; this component keeps each observation
 * command and all of its operation/ticket updates together.
 */
final class CiObservationRuntime
{
    private static final int PRIORITY = 825;

    private final FlowRuntime runtime;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    CiObservationRuntime(FlowRuntime runtime, JdbcTemplate jdbc, Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    Operation ensureWatch(String receiptId)
    {
        requireText(receiptId, "receiptId");
        return runtime.inTransaction(() -> {
            Owner owner = owner(receiptId);
            PullRequestSubject pr = runtime.requirePullRequest(owner.prId());
            if (!pr.taskId().equals(owner.taskId())
                    || !Objects.equals(
                            pr.currentRemoteHead(), owner.proposedHead())) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "CI observation receipt is not the current PR head");
            }
            String operationId = FlowRuntime.stableId(
                    "github-ci-observation", receiptId);
            String subjectDigest = subjectDigest(owner);
            String supersededRef = "CI_HEAD_SUPERSEDED:" + receiptId;
            jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'CANCELED', result_ref = ?
                    WHERE kind = 'OBSERVE_CI'
                      AND owner_kind = 'GITHUB_EFFECT_RECEIPT'
                      AND operation_id <> ?
                      AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                      AND owner_id IN (
                          SELECT old_receipt.receipt_id
                          FROM flow_github_effect_receipt_envelope old_receipt
                          JOIN flow_github_effect_plan_envelope old_plan
                            ON old_plan.plan_id = old_receipt.plan_id
                           AND old_plan.operation_id = old_receipt.operation_id
                           AND old_plan.kind = old_receipt.kind
                          WHERE old_plan.pr_id = ?
                      )
                    """,
                    supersededRef,
                    operationId,
                    owner.prId());
            jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'DONE', claim_owner = NULL,
                        claim_expires_at = NULL, claim_token = NULL
                    WHERE operation_id IN (
                        SELECT operation_id FROM flow_runtime_operation
                        WHERE kind = 'OBSERVE_CI' AND state = 'CANCELED'
                          AND result_ref = ?
                    )
                    """,
                    supersededRef);
            runtime.insertOperationAndTicket(
                    operationId,
                    "GITHUB_EFFECT_RECEIPT",
                    receiptId,
                    owner.taskId(),
                    OperationKind.OBSERVE_CI,
                    subjectDigest,
                    receiptId,
                    null,
                    PRIORITY,
                    clock.instant());
            return runtime.requireOperation(operationId);
        });
    }

    Optional<Claim> claimNext(
            String workerId, Duration claimTtl, int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return runtime.inTransaction(() -> {
            if (runtime.activeDispatchCount() >= capacity) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<ClaimCandidate> candidates = jdbc.query(
                    """
                    SELECT o.operation_id, o.task_id, o.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    JOIN flow_github_effect_receipt_envelope r
                      ON r.receipt_id = o.owner_id
                     AND r.receipt_id = o.input_ref
                    WHERE o.kind = 'OBSERVE_CI'
                      AND o.owner_kind = 'GITHUB_EFFECT_RECEIPT'
                      AND o.state IN ('READY', 'RETRYABLE')
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                    ORDER BY d.priority DESC, d.not_before, o.operation_id
                    LIMIT 32
                    """,
                    (result, row) -> new ClaimCandidate(
                            result.getString("operation_id"),
                            result.getString("task_id"),
                            OperationKind.valueOf(result.getString("kind")),
                            result.getLong("claim_generation")),
                    now.toEpochMilli());
            for (ClaimCandidate candidate : candidates) {
                Operation operation = runtime.requireOperation(
                        candidate.operationId());
                Owner owner = owner(operation.ownerId());
                if (!operation.subjectDigest().equals(subjectDigest(owner))) {
                    throw new IllegalStateException(
                            "CI observation operation graph is invalid");
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
                            "CI observation ticket claimed without its operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(), candidate.taskId(),
                        OperationKind.OBSERVE_CI, generation, token,
                        workerId, expiresAt));
            }
            return Optional.empty();
        });
    }

    Operation assertClaim(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return runtime.inTransaction(() -> {
            if (claim.kind() != OperationKind.OBSERVE_CI) {
                throw new IllegalArgumentException(
                        "claim is not a CI observation claim");
            }
            runtime.assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation operation = runtime.requireOperation(claim.operationId());
            Owner owner = owner(operation.ownerId());
            if (operation.kind() != OperationKind.OBSERVE_CI
                    || !operation.ownerKind().equals("GITHUB_EFFECT_RECEIPT")
                    || !operation.ownerId().equals(operation.inputRef())
                    || !operation.taskId().equals(owner.taskId())
                    || !operation.subjectDigest().equals(
                            subjectDigest(owner))) {
                throw new IllegalStateException(
                        "CI observation operation graph is invalid");
            }
            return operation;
        });
    }

    FlowRuntime.CiObservationSubject subject(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return runtime.inTransaction(() -> {
            Operation operation = assertClaim(claim);
            Owner owner = owner(operation.ownerId());
            return new FlowRuntime.CiObservationSubject(
                    owner.receiptId(), owner.receiptOperationId(),
                    owner.planId(), owner.stepId(), owner.probeId(),
                    owner.receiptDigest(), owner.prId(), owner.taskId(),
                    owner.repositoryId(), owner.targetBaseRef(),
                    owner.scopeKey(), owner.provider(),
                    owner.repositoryExternalId(), owner.repositoryOwner(),
                    owner.repositoryName(),
                    owner.headRepositoryExternalId(),
                    owner.headRepositoryOwner(), owner.headRepositoryName(),
                    owner.prNumber(), owner.prNodeId(), owner.branchName(),
                    owner.branchRef(), owner.expectedRemoteHead(),
                    owner.proposedHead());
        });
    }

    void rearm(Claim claim, String resultRef, Instant notBefore)
    {
        requireNonNull(claim, "claim is null");
        requireText(resultRef, "resultRef");
        requireNonNull(notBefore, "notBefore is null");
        runtime.inTransaction(() -> {
            assertClaim(claim);
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'READY', result_ref = ?
                    WHERE operation_id = ? AND state = 'CLAIMED'
                    """,
                    resultRef,
                    claim.operationId());
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', not_before = ?
                    WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                      AND claim_generation = ? AND claim_token = ?
                      AND claim_owner = ?
                    """,
                    notBefore.toEpochMilli(),
                    claim.operationId(),
                    claim.generation(),
                    claim.claimToken(),
                    claim.workerId());
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new FlowRuntime.StaleClaimException(
                        "CI observation changed during watch rearm");
            }
            return Boolean.TRUE;
        });
    }

    void cancel(Claim claim, String resultRef)
    {
        requireNonNull(claim, "claim is null");
        requireText(resultRef, "resultRef");
        runtime.inTransaction(() -> {
            if (canceledReplay(claim, resultRef)) {
                return Boolean.TRUE;
            }
            assertClaim(claim);
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'CANCELED', result_ref = ?
                    WHERE operation_id = ? AND state = 'CLAIMED'
                    """,
                    resultRef, claim.operationId());
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'DONE'
                    WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                      AND claim_generation = ? AND claim_token = ?
                      AND claim_owner = ?
                    """,
                    claim.operationId(), claim.generation(),
                    claim.claimToken(), claim.workerId());
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new FlowRuntime.StaleClaimException(
                        "CI observation changed during cancellation");
            }
            return Boolean.TRUE;
        });
    }

    Optional<String> canceledResult(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(claim.operationId());
            if (operation.state() != OperationState.CANCELED) {
                return Optional.empty();
            }
            String resultRef = operation.resultRef();
            if (resultRef == null || !canceledReplay(claim, resultRef)) {
                throw new FlowRuntime.StaleClaimException(
                        "CI observation cancellation claim is not exact");
            }
            return Optional.of(resultRef);
        });
    }

    void assertRearmReplay(Claim claim, String resultRef)
    {
        requireNonNull(claim, "claim is null");
        requireText(resultRef, "resultRef");
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'OBSERVE_CI' AND o.state = 'READY'
                  AND o.owner_kind = 'GITHUB_EFFECT_RECEIPT'
                  AND o.result_ref = ?
                  AND d.delivery_state = 'AVAILABLE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                Integer.class,
                claim.operationId(), claim.taskId(), resultRef,
                claim.generation(), claim.claimToken(), claim.workerId());
        if (claim.kind() != OperationKind.OBSERVE_CI
                || requireNonNull(exact,
                        "CI observation replay count is null") != 1) {
            throw new FlowRuntime.StaleClaimException(
                    "CI observation poll replay authority is invalid");
        }
    }

    void recoverExpired(String operationId, long generation)
    {
        requireText(operationId, "operationId");
        runtime.inTransaction(() -> {
            Operation operation = runtime.requireOperation(operationId);
            if (operation.kind() != OperationKind.OBSERVE_CI) {
                throw new IllegalArgumentException(
                        "operation is not OBSERVE_CI");
            }
            if (operation.state() == OperationState.CANCELED
                    && runtime.ticketMatches(
                            operationId, generation, "DONE")) {
                Owner owner = owner(operation.ownerId());
                PullRequestSubject pr = runtime.requirePullRequest(
                        owner.prId());
                Task task = runtime.requireTask(owner.taskId());
                if (Objects.equals(
                        operation.resultRef(), "CI_OBSERVATION_STALE")) {
                    if (FlowRuntime.isTerminal(task.status())
                            || !Objects.equals(
                                    pr.currentRemoteHead(),
                                    owner.proposedHead())) {
                        return Boolean.TRUE;
                    }
                    throw new IllegalStateException(
                            "CI observation recovery disposition is inconsistent");
                }
                String prefix = "CI_HEAD_SUPERSEDED:";
                if (operation.resultRef() != null
                        && operation.resultRef().startsWith(prefix)) {
                    String successorReceipt = operation.resultRef()
                            .substring(prefix.length());
                    Owner successor = owner(successorReceipt);
                    Operation successorOperation = runtime.requireOperation(
                            FlowRuntime.stableId(
                                    "github-ci-observation",
                                    successorReceipt));
                    if (!successor.prId().equals(owner.prId())
                            || !successor.taskId().equals(owner.taskId())
                            || Objects.equals(
                                    pr.currentRemoteHead(),
                                    owner.proposedHead())
                            || Objects.equals(
                                    successor.proposedHead(),
                                    owner.proposedHead())
                            || !successorOperation.ownerId().equals(
                                    successorReceipt)
                            || successorOperation.kind()
                                    != OperationKind.OBSERVE_CI) {
                        throw new IllegalStateException(
                                "CI observation supersession is inconsistent");
                    }
                    return Boolean.TRUE;
                }
                throw new IllegalStateException(
                        "CI observation cancellation is not recoverable");
            }
            if (operation.state() == OperationState.READY
                    && runtime.ticketMatches(
                            operationId, generation, "AVAILABLE")) {
                return Boolean.TRUE;
            }
            ExpiredClaim expired = runtime.requireExpiredClaim(
                    operationId, generation);
            if (expired.processAttemptId() != null) {
                throw new IllegalStateException(
                        "OBSERVE_CI cannot own a process attempt");
            }
            Owner owner = owner(operation.ownerId());
            PullRequestSubject pr = runtime.requirePullRequest(owner.prId());
            Task task = runtime.requireTask(owner.taskId());
            if (FlowRuntime.isTerminal(task.status())
                    || !Objects.equals(
                            pr.currentRemoteHead(), owner.proposedHead())) {
                int operationUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_operation
                        SET state = 'CANCELED', result_ref = ?
                        WHERE operation_id = ? AND state = 'CLAIMED'
                        """,
                        "CI_OBSERVATION_STALE", operationId);
                int ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET delivery_state = 'DONE', claim_owner = NULL,
                            claim_expires_at = NULL, claim_token = NULL
                        WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                          AND claim_generation = ?
                        """,
                        operationId, generation);
                if (operationUpdated != 1 || ticketUpdated != 1) {
                    throw new FlowRuntime.StaleClaimException(
                            "expired stale CI observation changed");
                }
                return Boolean.TRUE;
            }
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation SET state = 'READY'
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
                    operationId, generation);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new FlowRuntime.StaleClaimException(
                        "expired CI observation changed during recovery");
            }
            return Boolean.TRUE;
        });
    }

    void assertWatchReplay(String receiptId, String receiptDigest)
    {
        Owner owner = owner(receiptId);
        if (!owner.receiptDigest().equals(receiptDigest)) {
            throw new IllegalStateException(
                    "initial ready policy owns another receipt digest");
        }
        String operationId = FlowRuntime.stableId(
                "github-ci-observation", receiptId);
        Operation watch = runtime.requireOperation(operationId);
        if (!watch.ownerKind().equals("GITHUB_EFFECT_RECEIPT")
                || !watch.ownerId().equals(receiptId)
                || !watch.taskId().equals(owner.taskId())
                || watch.kind() != OperationKind.OBSERVE_CI
                || !watch.inputRef().equals(receiptId)
                || !watch.subjectDigest().equals(subjectDigest(owner))) {
            throw new IllegalStateException(
                    "initial CI watch replay identity is corrupt");
        }
        Integer coherent = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_dispatch_ticket d
                WHERE d.operation_id = ? AND (
                    (? IN ('READY', 'RETRYABLE')
                        AND d.delivery_state = 'AVAILABLE')
                    OR (? = 'CLAIMED' AND d.delivery_state = 'CLAIMED')
                    OR (? IN ('WAITING', 'SUCCEEDED', 'FAILED', 'CANCELED')
                        AND d.delivery_state = 'DONE'))
                """,
                Integer.class, operationId, watch.state().name(),
                watch.state().name(), watch.state().name());
        if (requireNonNull(coherent,
                "initial CI watch replay count is null") != 1) {
            throw new IllegalStateException(
                    "initial CI watch replay lifecycle is corrupt");
        }
    }

    private boolean canceledReplay(Claim claim, String resultRef)
    {
        if (claim.kind() != OperationKind.OBSERVE_CI) {
            return false;
        }
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'OBSERVE_CI' AND o.state = 'CANCELED'
                  AND o.result_ref = ? AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                Integer.class,
                claim.operationId(), claim.taskId(), resultRef,
                claim.generation(), claim.claimToken(), claim.workerId());
        return requireNonNull(
                exact, "CI observation cancel replay count is null") == 1;
    }

    private Owner owner(String receiptId)
    {
        Owner owner = jdbc.query(
                """
                WITH receipt_owner AS (
                    SELECT r.receipt_id,
                           r.operation_id AS receipt_operation_id,
                           r.plan_id, r.step_id, r.probe_id,
                           r.branch_ref, r.expected_remote_head,
                           r.proposed_head, r.receipt_digest,
                           r.head_repository_external_id AS receipt_head_external_id,
                           r.head_repository_owner AS receipt_head_owner,
                           r.head_repository_name AS receipt_head_name,
                           p.pr_id, p.required_ci_policy_revision_id
                    FROM flow_github_external_effect_receipt r
                    JOIN flow_github_external_effect_plan p
                      ON p.plan_id = r.plan_id
                     AND p.operation_id = r.operation_id
                    WHERE r.receipt_id = ?
                    UNION ALL
                    SELECT r.receipt_id,
                           r.operation_id AS receipt_operation_id,
                           r.plan_id, sr.step_id, d.probe_id,
                           p.branch_ref, p.proposed_head AS expected_remote_head,
                           r.proposed_head, r.receipt_digest,
                           p.head_repository_external_id AS receipt_head_external_id,
                           p.head_repository_owner AS receipt_head_owner,
                           p.head_repository_name AS receipt_head_name,
                           p.pr_id, p.required_ci_policy_revision_id
                    FROM flow_github_initial_publish_receipt r
                    JOIN flow_github_initial_publish_plan p
                      ON p.plan_id = r.plan_id
                     AND p.operation_id = r.operation_id
                    JOIN flow_github_initial_publish_step_receipt sr
                      ON sr.receipt_id = r.pr_step_receipt_id
                     AND sr.operation_id = r.operation_id
                     AND sr.plan_id = r.plan_id
                    JOIN flow_github_initial_pr_receipt_detail d
                      ON d.receipt_id = sr.receipt_id
                     AND d.operation_id = sr.operation_id
                     AND d.plan_id = sr.plan_id
                    JOIN flow_runtime_pr_ready_policy_revision rp
                      ON rp.publication_receipt_id = r.receipt_id
                     AND rp.operation_id = r.operation_id
                     AND rp.effect_plan_id = r.plan_id
                     AND rp.authorization_id = p.authorization_id
                     AND rp.action_digest = p.action_digest
                     AND rp.proposed_head = p.proposed_head
                     AND rp.required_ci_policy_revision_id =
                            p.required_ci_policy_revision_id
                     AND rp.policy = p.ready_policy
                    JOIN flow_runtime_pr initial_pr
                      ON initial_pr.pr_id = p.pr_id
                    JOIN flow_runtime_remote_identity initial_identity
                      ON initial_identity.remote_identity_id =
                            initial_pr.remote_identity_id
                     AND initial_identity.publication_receipt_id = r.receipt_id
                     AND initial_identity.repository_external_id =
                            p.base_repository_external_id
                     AND initial_identity.repository_owner =
                            p.base_repository_owner
                     AND initial_identity.repository_name =
                            p.base_repository_name
                     AND initial_identity.head_repository_external_id =
                            p.head_repository_external_id
                     AND initial_identity.head_repository_owner =
                            p.head_repository_owner
                     AND initial_identity.head_repository_name =
                            p.head_repository_name
                     AND initial_identity.pr_number = r.pr_number
                     AND initial_identity.pr_node_id = r.pr_node_id
                     AND initial_identity.html_url = r.html_url
                    WHERE r.receipt_id = ?
                )
                SELECT r.receipt_id, r.receipt_operation_id,
                       r.plan_id, r.step_id, r.probe_id,
                       r.branch_ref, r.expected_remote_head, r.proposed_head,
                       r.receipt_digest, r.receipt_head_external_id,
                       r.receipt_head_owner, r.receipt_head_name,
                       r.pr_id, r.required_ci_policy_revision_id,
                       pr.task_id, pr.repository_id, pr.target_base_ref,
                       pr.scope_key, pr.branch_name,
                       i.provider, i.repository_external_id,
                       i.repository_owner, i.repository_name,
                       i.head_repository_external_id,
                       i.head_repository_owner, i.head_repository_name,
                       i.pr_number, i.pr_node_id
                FROM receipt_owner r
                JOIN flow_runtime_pr pr ON pr.pr_id = r.pr_id
                JOIN flow_runtime_remote_identity i
                  ON i.remote_identity_id = pr.remote_identity_id
                WHERE r.receipt_id = ?
                """,
                (result, row) -> new Owner(
                        result.getString("receipt_id"),
                        result.getString("receipt_operation_id"),
                        result.getString("plan_id"),
                        result.getString("step_id"),
                        result.getString("probe_id"),
                        result.getString("pr_id"),
                        result.getString("task_id"),
                        result.getString("repository_id"),
                        result.getString("target_base_ref"),
                        result.getString("scope_key"),
                        result.getString("provider"),
                        result.getString("repository_external_id"),
                        result.getString("repository_owner"),
                        result.getString("repository_name"),
                        result.getString("proposed_head"),
                        result.getString("receipt_head_external_id"),
                        result.getString("receipt_head_owner"),
                        result.getString("receipt_head_name"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getLong("pr_number"),
                        result.getString("pr_node_id"),
                        result.getString("branch_name"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("receipt_digest"),
                        result.getString("required_ci_policy_revision_id")),
                receiptId, receiptId, receiptId).stream().findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown publication receipt: " + receiptId));
        if (!owner.provider().equals("GITHUB")
                || !owner.branchRef().equals(
                        "refs/heads/" + owner.branchName())
                || !owner.headRepositoryExternalId().equals(
                        owner.receiptHeadRepositoryExternalId())
                || !owner.headRepositoryOwner().equals(
                        owner.receiptHeadRepositoryOwner())
                || !owner.headRepositoryName().equals(
                        owner.receiptHeadRepositoryName())) {
            throw new IllegalStateException(
                    "CI observation receipt/PR identity is inconsistent");
        }
        return owner;
    }

    private static String subjectDigest(Owner owner)
    {
        return FlowRuntime.stableId(
                "github-ci-observation-subject:v1",
                owner.receiptId(), owner.planId(), owner.prId(),
                owner.receiptOperationId(), owner.stepId(), owner.probeId(),
                owner.taskId(), owner.repositoryId(), owner.targetBaseRef(),
                owner.scopeKey(), owner.provider(),
                owner.repositoryExternalId(), owner.repositoryOwner(),
                owner.repositoryName(), owner.prNumber().toString(),
                owner.prNodeId(), owner.branchName(), owner.branchRef(),
                owner.expectedRemoteHead(), owner.proposedHead(),
                owner.receiptDigest(),
                owner.headRepositoryExternalId(),
                owner.headRepositoryOwner(), owner.headRepositoryName(),
                owner.requiredCiPolicyRevisionId());
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

    private record ClaimCandidate(
            String operationId,
            String taskId,
            OperationKind kind,
            long generation) {}

    private record Owner(
            String receiptId,
            String receiptOperationId,
            String planId,
            String stepId,
            String probeId,
            String prId,
            String taskId,
            String repositoryId,
            String targetBaseRef,
            String scopeKey,
            String provider,
            String repositoryExternalId,
            String repositoryOwner,
            String repositoryName,
            String proposedHead,
            String receiptHeadRepositoryExternalId,
            String receiptHeadRepositoryOwner,
            String receiptHeadRepositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            Long prNumber,
            String prNodeId,
            String branchName,
            String branchRef,
            String expectedRemoteHead,
            String receiptDigest,
            String requiredCiPolicyRevisionId) {}
}
