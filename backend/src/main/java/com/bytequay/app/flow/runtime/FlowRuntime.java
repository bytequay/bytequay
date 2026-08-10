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

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentSession;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskLifecycleRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Small durable transaction boundary for the greenfield workflow runtime.
 *
 * <p>The local sidecar has one runtime instance, so synchronized command
 * methods serialize SQLite's write transactions. Database uniqueness and CAS
 * predicates remain the final guards. A multi-process deployment would need a
 * database owner lock instead.
 */
public final class FlowRuntime
{
    private static final int PROVISION_PRIORITY = 1_000;
    private static final int RECONCILIATION_PRIORITY = 900;
    private static final int CI_FIX_PRIORITY = 800;
    private static final int TASK_TURN_PRIORITY = 700;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public FlowRuntime(DataSource dataSource, Clock clock)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Persists one Task plus its provisioning operation/ticket. It performs no
     * Git, process, or model work.
     */
    public synchronized Task startTask(
            String requestKey,
            String repositoryId,
            String goalText,
            String branchName,
            String worktreePath)
    {
        requireText(requestKey, "requestKey");
        requireText(repositoryId, "repositoryId");
        requireText(goalText, "goalText");
        requireText(branchName, "branchName");
        requireText(worktreePath, "worktreePath");
        return inTransaction(() -> {
            Optional<Task> existing = taskByRequestKey(requestKey);
            if (existing.isPresent()) {
                Task task = existing.get();
                if (!task.repositoryId().equals(repositoryId)
                        || !task.goalText().equals(goalText)
                        || !task.branchName().equals(branchName)
                        || !task.worktreePath().equals(worktreePath)) {
                    throw new IllegalStateException(
                            "requestKey already owns a different Task subject");
                }
                return task;
            }

            Instant now = clock.instant();
            String taskId = stableId("task", requestKey);
            String lifecycleId = stableId("task-lifecycle", taskId, "1");
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_task (
                        task_id, request_key, repository_id, goal_text, status,
                        epoch, branch_name, worktree_path,
                        current_lifecycle_revision_id
                    ) VALUES (?, ?, ?, ?, 'CREATED', 1, ?, ?, ?)
                    """,
                    taskId,
                    requestKey,
                    repositoryId,
                    goalText,
                    branchName,
                    worktreePath,
                    lifecycleId);
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_task_lifecycle_revision (
                        lifecycle_revision_id, task_id, sequence, from_status,
                        to_status, reason_code, recorded_at
                    ) VALUES (?, ?, 1, NULL, 'CREATED', 'TASK_STARTED', ?)
                    """,
                    lifecycleId,
                    taskId,
                    now.toEpochMilli());

            String subjectDigest = stableId(
                    "provision-subject",
                    taskId,
                    repositoryId,
                    branchName,
                    worktreePath);
            String operationId = stableId(
                    "operation", taskId, "PROVISION_TASK", subjectDigest);
            insertOperationAndTicket(
                    operationId,
                    "TASK",
                    taskId,
                    taskId,
                    OperationKind.PROVISION_TASK,
                    subjectDigest,
                    "task:" + taskId,
                    null,
                    PROVISION_PRIORITY,
                    now);
            return requireTask(taskId);
        });
    }

    /** Completes objective provisioning and creates the one idle Task session. */
    public synchronized AgentSession provisionTask(
            Claim claim, String baseSha, String headSha)
    {
        requireNonNull(claim, "claim is null");
        requireText(baseSha, "baseSha");
        requireText(headSha, "headSha");
        return inTransaction(() -> {
            Operation operation = requireOperation(claim.operationId());
            if (operation.kind() != OperationKind.PROVISION_TASK) {
                throw new IllegalArgumentException(
                        "claim does not own Task provisioning");
            }
            Task task = requireTask(operation.taskId());
            if (task.status() == TaskStatus.ACTIVE
                    && operation.state() == OperationState.SUCCEEDED) {
                if (!Objects.equals(task.launchBaseSha(), baseSha)
                        || !Objects.equals(task.currentHeadSha(), headSha)) {
                    throw new IllegalStateException(
                            "provisioning redelivery changed the frozen subject");
                }
                return requireSession(task.taskSessionId());
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            if (task.status() != TaskStatus.CREATED) {
                throw new IllegalStateException(
                        "Task is not awaiting provisioning");
            }

            Instant now = clock.instant();
            String sessionId = stableId(
                    "agent-session", task.taskId(), AgentRole.TASK_AGENT.name());
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_session (
                        session_id, task_id, role, state, created_at, updated_at
                    ) VALUES (?, ?, 'TASK_AGENT', 'IDLE', ?, ?)
                    """,
                    sessionId,
                    task.taskId(),
                    now.toEpochMilli(),
                    now.toEpochMilli());
            TaskLifecycleRevision active = appendLifecycle(
                    task,
                    TaskStatus.ACTIVE,
                    "PROVISIONED",
                    "base:" + baseSha,
                    operation.operationId(),
                    now);
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET launch_base_sha = ?, current_base_sha = ?,
                        current_head_sha = ?, task_session_id = ?,
                        status = 'ACTIVE', current_lifecycle_revision_id = ?
                    WHERE task_id = ? AND status = 'CREATED'
                        AND current_lifecycle_revision_id = ?
                    """,
                    baseSha,
                    baseSha,
                    headSha,
                    sessionId,
                    active.lifecycleRevisionId(),
                    task.taskId(),
                    task.currentLifecycleRevisionId());
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "Task changed during provisioning");
            }
            Task activeTask = requireTask(task.taskId());
            appendPendingWork(
                    stableId("inbox", "TASK", task.taskId(), "INITIAL"),
                    activeTask,
                    null,
                    "TASK",
                    task.taskId(),
                    "1",
                    PendingKind.INITIAL_TASK,
                    headSha,
                    "task-goal:" + task.taskId(),
                    null,
                    now);
            ensureReconciliation(task.taskId());
            settleDispatch(operation.operationId(), OperationState.SUCCEEDED,
                    "provisioned:" + task.taskId());
            return requireSession(sessionId);
        });
    }

    /** Appends one immutable Task lifecycle revision using exact-owner CAS. */
    public synchronized TaskLifecycleRevision transitionTask(
            String taskId,
            String expectedLifecycleRevisionId,
            TaskStatus nextStatus,
            String reasonCode,
            String evidenceRef)
    {
        requireText(taskId, "taskId");
        requireText(expectedLifecycleRevisionId,
                "expectedLifecycleRevisionId");
        requireNonNull(nextStatus, "nextStatus is null");
        requireText(reasonCode, "reasonCode");
        return inTransaction(() -> {
            Task task = requireTask(taskId);
            if (!task.currentLifecycleRevisionId()
                    .equals(expectedLifecycleRevisionId)) {
                throw new StaleOwnerRevisionException(
                        "Task lifecycle revision is stale");
            }
            if (!allowedTransition(task.status(), nextStatus)) {
                throw new IllegalStateException(
                        "Invalid Task transition: " + task.status()
                                + " -> " + nextStatus);
            }
            if (task.selectedWriterOperationId() != null
                    || writerFence(taskId).isPresent()) {
                throw new MutationRejectedException(
                        "Task lifecycle cannot change while a writer is live");
            }
            if (isTerminal(nextStatus) && hasClaimedOperation(taskId)) {
                throw new MutationRejectedException(
                        "Task cannot terminate while an operation is claimed");
            }
            TaskLifecycleRevision revision = appendLifecycle(
                    task,
                    nextStatus,
                    reasonCode,
                    evidenceRef,
                    null,
                    clock.instant());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET status = ?, current_lifecycle_revision_id = ?
                    WHERE task_id = ? AND current_lifecycle_revision_id = ?
                    """,
                    nextStatus.name(),
                    revision.lifecycleRevisionId(),
                    taskId,
                    expectedLifecycleRevisionId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "Task lifecycle revision is stale");
            }
            if (nextStatus == TaskStatus.ACTIVE) {
                resumeWaitingReconciliation(taskId);
            }
            else if (isTerminal(nextStatus)) {
                settleTaskOperationsAtTerminal(taskId, nextStatus);
            }
            return revision;
        });
    }

    /** Materializes the Task's one stable local PR at an exact adopted head. */
    public synchronized PullRequestSubject materializePullRequest(
            String taskId,
            String expectedHead,
            String baseRef,
            String targetBaseRef,
            String scopeKey)
    {
        requireText(taskId, "taskId");
        requireText(expectedHead, "expectedHead");
        requireText(baseRef, "baseRef");
        requireText(targetBaseRef, "targetBaseRef");
        requireText(scopeKey, "scopeKey");
        return inTransaction(() -> {
            Task task = requireTask(taskId);
            if (task.status() != TaskStatus.ACTIVE
                    || !expectedHead.equals(task.currentHeadSha())) {
                throw new IllegalStateException(
                        "PR subject is not the current active Task head");
            }
            if (expectedHead.equals(task.currentBaseSha())) {
                throw new IllegalStateException(
                        "An empty Task cannot materialize a PR");
            }
            if (task.prId() != null) {
                PullRequestSubject current = requirePullRequest(task.prId());
                if (!current.createdFromHeadSha().equals(expectedHead)
                        || !current.baseRef().equals(baseRef)
                        || !current.targetBaseRef().equals(targetBaseRef)
                        || !current.scopeKey().equals(scopeKey)) {
                    throw new IllegalStateException(
                            "Task already owns a different PR subject");
                }
                return current;
            }

            String prId = stableId("pr", taskId);
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_pr (
                        pr_id, task_id, repository_id, base_ref, base_sha,
                        target_base_ref, scope_key, branch_name,
                        created_from_head_sha, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    prId,
                    taskId,
                    task.repositoryId(),
                    baseRef,
                    task.currentBaseSha(),
                    targetBaseRef,
                    scopeKey,
                    task.branchName(),
                    expectedHead,
                    clock.instant().toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET pr_id = ?
                    WHERE task_id = ? AND pr_id IS NULL
                        AND current_head_sha = ?
                    """,
                    prId,
                    taskId,
                    expectedHead);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "Task head changed while materializing its PR");
            }
            return requirePullRequest(prId);
        });
    }

    /** Adds the set-once provider identity to the same local PR record. */
    public synchronized PullRequestSubject bindRemoteIdentity(
            String prId,
            String expectedLocalHead,
            String provider,
            String repositoryExternalId,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String publicationReceiptId)
    {
        requireText(prId, "prId");
        requireText(expectedLocalHead, "expectedLocalHead");
        requireText(provider, "provider");
        requireText(repositoryExternalId, "repositoryExternalId");
        requireText(prNodeId, "prNodeId");
        requireText(htmlUrl, "htmlUrl");
        requireText(publicationReceiptId, "publicationReceiptId");
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive");
        }
        return inTransaction(() -> {
            PullRequestSubject pr = requirePullRequest(prId);
            if (pr.published()) {
                if (!provider.equals(pr.provider())
                        || !repositoryExternalId.equals(
                                pr.repositoryExternalId())
                        || !Long.valueOf(prNumber).equals(pr.prNumber())
                        || !expectedLocalHead.equals(pr.currentRemoteHead())) {
                    throw new IllegalStateException(
                            "PR already owns a different remote identity");
                }
                assertRemoteIdentityMatches(
                        pr.remoteIdentityId(),
                        provider,
                        repositoryExternalId,
                        prNumber,
                        prNodeId,
                        htmlUrl,
                        publicationReceiptId);
                return pr;
            }
            Task task = requireTask(pr.taskId());
            if (!expectedLocalHead.equals(task.currentHeadSha())) {
                throw new StaleOwnerRevisionException(
                        "publication head is not current");
            }

            String identityId = stableId(
                    "remote-pr", provider, repositoryExternalId,
                    Long.toString(prNumber));
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_remote_identity (
                        remote_identity_id, provider, repository_external_id,
                        pr_number, pr_node_id, html_url,
                        publication_receipt_id, bound_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    identityId,
                    provider,
                    repositoryExternalId,
                    prNumber,
                    prNodeId,
                    htmlUrl,
                    publicationReceiptId,
                    clock.instant().toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_pr
                    SET remote_identity_id = ?, current_remote_head = ?
                    WHERE pr_id = ? AND remote_identity_id IS NULL
                    """,
                    identityId,
                    expectedLocalHead,
                    prId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "PR remote identity changed while binding");
            }
            return requirePullRequest(prId);
        });
    }

    /** Records a program-observed remote-head compare-and-set. */
    public synchronized PullRequestSubject advanceRemoteHead(
            String prId, String expectedRemoteHead, String newRemoteHead)
    {
        requireText(prId, "prId");
        requireText(expectedRemoteHead, "expectedRemoteHead");
        requireText(newRemoteHead, "newRemoteHead");
        return inTransaction(() -> {
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_pr
                    SET current_remote_head = ?
                    WHERE pr_id = ? AND remote_identity_id IS NOT NULL
                        AND current_remote_head = ?
                    """,
                    newRemoteHead,
                    prId,
                    expectedRemoteHead);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "remote PR head changed concurrently");
            }
            return requirePullRequest(prId);
        });
    }

    /**
     * Persists a typed FINAL_RED owner reference and only ensures
     * reconciliation; it never starts a CI process directly.
     */
    public synchronized FinalRedRegistration registerFinalRed(
            String roundId,
            String taskId,
            String prId,
            String remoteHead,
            String payloadRef)
    {
        requireText(roundId, "roundId");
        requireText(taskId, "taskId");
        requireText(prId, "prId");
        requireText(remoteHead, "remoteHead");
        requireText(payloadRef, "payloadRef");
        return inTransaction(() -> {
            PullRequestSubject pr = requirePullRequest(prId);
            if (!pr.taskId().equals(taskId) || !pr.published()) {
                throw new IllegalStateException(
                        "FINAL_RED does not belong to a published Task PR");
            }
            Task task = requireTask(taskId);
            String inboxId = stableId("inbox", "CI", roundId, "1");
            PendingWork pending = appendPendingWork(
                    inboxId,
                    task,
                    prId,
                    "CI",
                    roundId,
                    "1",
                    PendingKind.FINAL_RED,
                    remoteHead,
                    payloadRef,
                    null,
                    clock.instant());
            if (isTerminal(task.status())
                    && pending.selectedByOperationId() == null
                    && pending.handledByOperationId() == null) {
                String terminalReason = "TASK_" + task.status().name();
                int marked = jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET terminal_reason = ?
                        WHERE inbox_id = ?
                          AND selected_by_operation_id IS NULL
                          AND handled_by_operation_id IS NULL
                          AND (terminal_reason IS NULL OR terminal_reason = ?)
                        """,
                        terminalReason,
                        inboxId,
                        terminalReason);
                if (marked != 1) {
                    throw new StaleOwnerRevisionException(
                            "terminal FINAL_RED disposition changed");
                }
                return new FinalRedRegistration(
                        inboxId,
                        null,
                        pending.workWatermark(),
                        terminalReason);
            }
            Operation reconciliation = pending.selectedByOperationId() == null
                    && pending.handledByOperationId() == null
                    ? ensureReconciliation(taskId)
                    : reconciliationCovering(
                            taskId, pending.workWatermark());
            return new FinalRedRegistration(
                    inboxId,
                    reconciliation.operationId(),
                    pending.workWatermark(),
                    null);
        });
    }

    /** Claims one eligible durable ticket and increments its claim generation. */
    public synchronized Optional<Claim> claimNext(
            String workerId, Duration claimTtl)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        return inTransaction(() -> {
            Instant now = clock.instant();
            List<ClaimCandidate> candidates = jdbc.query(
                    """
                    SELECT o.operation_id, o.task_id, o.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    LEFT JOIN flow_runtime_task t ON t.task_id = o.task_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND (
                          o.kind = 'PROVISION_TASK'
                          OR (o.kind = 'RECONCILE_TASK'
                              AND t.status = 'ACTIVE'
                              AND t.selected_writer_operation_id IS NULL)
                          OR (o.kind IN ('RUN_TASK_TURN', 'RUN_CI_FIXER')
                              AND t.status = 'ACTIVE'
                              AND t.selected_writer_operation_id = o.operation_id)
                      )
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
                        WHERE operation_id = ? AND state = 'READY'
                        """,
                        candidate.operationId());
                if (operationUpdated != 1) {
                    throw new IllegalStateException(
                            "ticket claimed without its READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(),
                        candidate.taskId(),
                        candidate.kind(),
                        generation,
                        token,
                        workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }

    /** Finds expired generations entirely from durable state after restart. */
    public List<ExpiredClaim> expiredClaims()
    {
        return jdbc.query(
                """
                SELECT o.operation_id, o.task_id, o.kind,
                       d.claim_generation, d.claim_expires_at,
                       r.run_id, p.process_attempt_id, p.state process_state
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                LEFT JOIN flow_runtime_agent_run r
                  ON r.operation_id = o.operation_id
                LEFT JOIN flow_runtime_agent_process_attempt p
                  ON p.run_id = r.run_id
                 AND p.claim_generation = d.claim_generation
                WHERE o.state = 'CLAIMED'
                  AND d.delivery_state = 'CLAIMED'
                  AND d.claim_expires_at <= ?
                ORDER BY d.claim_expires_at, o.operation_id
                """,
                (result, row) -> new ExpiredClaim(
                        result.getString("operation_id"),
                        result.getString("task_id"),
                        OperationKind.valueOf(result.getString("kind")),
                        result.getLong("claim_generation"),
                        instant(result, "claim_expires_at"),
                        result.getString("run_id"),
                        result.getString("process_attempt_id"),
                        nullableProcessState(result.getString("process_state"))),
                clock.instant().toEpochMilli());
    }

    /**
     * Retries only an objectively never-launched expired generation. Any
     * process-attempt row requires a future supervisor-owned recovery receipt.
     */
    public synchronized boolean recoverExpiredClaim(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        return inTransaction(() -> {
            Operation operation = requireOperation(operationId);
            if (operation.state() == OperationState.RETRYABLE
                    && ticketMatches(
                            operationId, generation, "AVAILABLE")) {
                return true;
            }
            if (operation.state() == OperationState.FAILED
                    && ticketMatches(operationId, generation, "DONE")) {
                return false;
            }
            ExpiredClaim expired = requireExpiredClaim(operationId, generation);
            if (expired.processAttemptId() != null) {
                quarantineExpiredProcessAttempt(expired);
                return false;
            }
            recoverNeverLaunchedClaim(expired);
            return true;
        });
    }

    /** Makes one proven-dead retry generation eligible for normal claiming. */
    public synchronized void redriveRetryable(String operationId)
    {
        requireText(operationId, "operationId");
        inTransaction(() -> {
            Operation operation = requireOperation(operationId);
            if (operation.state() == OperationState.READY) {
                return Boolean.TRUE;
            }
            Integer available = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_dispatch_ticket
                    WHERE operation_id = ? AND delivery_state = 'AVAILABLE'
                    """,
                    Integer.class,
                    operationId);
            if (operation.state() != OperationState.RETRYABLE
                    || requireNonNull(
                            available, "available ticket count is null") != 1) {
                throw new IllegalStateException(
                        "operation is not a never-launched retry generation");
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'READY'
                    WHERE operation_id = ? AND state = 'RETRYABLE'
                    """,
                    operationId);
            if (updated != 1) {
                throw new StaleClaimException(
                        "retry operation changed during redrive");
            }
            return Boolean.TRUE;
        });
    }

    /**
     * Runs the bounded WorkSelector for a claimed reconciliation generation.
     * It materializes at most one current FINAL_RED writer operation.
     */
    public synchronized Optional<Operation> selectNext(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation reconciliation = requireOperation(claim.operationId());
            if (reconciliation.kind() != OperationKind.RECONCILE_TASK
                    || reconciliation.workWatermark() == null) {
                throw new IllegalArgumentException(
                        "claim does not own reconciliation");
            }
            Task task = requireTask(reconciliation.taskId());
            if (task.selectedWriterOperationId() != null) {
                throw new IllegalStateException(
                        "Task already has a selected writer");
            }
            if (task.status() != TaskStatus.ACTIVE) {
                if (task.status() == TaskStatus.WAITING_USER
                        || task.status() == TaskStatus.NEEDS_ATTENTION
                        || task.status() == TaskStatus.CREATED) {
                    parkReconciliation(reconciliation.operationId());
                    return Optional.empty();
                }
                jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET handled_by_operation_id = ?
                        WHERE task_id = ? AND handled_by_operation_id IS NULL
                          AND work_watermark <= ?
                        """,
                        reconciliation.operationId(),
                        task.taskId(),
                        reconciliation.workWatermark());
                settleDispatch(
                        reconciliation.operationId(),
                        OperationState.CANCELED,
                        "TASK_NOT_ACTIVE");
                jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET last_reconciled_work_watermark = MAX(
                            last_reconciled_work_watermark, ?)
                        WHERE task_id = ?
                        """,
                        reconciliation.workWatermark(),
                        task.taskId());
                return Optional.empty();
            }

            Optional<Operation> selected = Optional.empty();
            List<PendingWork> pending = jdbc.query(
                    """
                    SELECT * FROM flow_runtime_inbox
                    WHERE task_id = ?
                      AND work_watermark <= ?
                      AND handled_by_operation_id IS NULL
                      AND selected_by_operation_id IS NULL
                      AND terminal_reason IS NULL
                    ORDER BY CASE kind
                        WHEN 'AGENT_RESULT_READY' THEN 1
                        WHEN 'FINAL_RED' THEN 2
                        WHEN 'INITIAL_TASK' THEN 3
                    END, work_watermark
                    """,
                    (result, row) -> readInbox(result),
                    task.taskId(),
                    reconciliation.workWatermark());
            for (PendingWork item : pending) {
                if (!pendingSubjectIsCurrent(task, item)) {
                    jdbc.update(
                            """
                            UPDATE flow_runtime_inbox
                            SET handled_by_operation_id = ?
                            WHERE inbox_id = ?
                              AND handled_by_operation_id IS NULL
                            """,
                            reconciliation.operationId(),
                            item.pendingId());
                    continue;
                }

                OperationKind writerKind = item.kind() == PendingKind.FINAL_RED
                        ? OperationKind.RUN_CI_FIXER
                        : OperationKind.RUN_TASK_TURN;
                String ownerKind = switch (item.kind()) {
                    case FINAL_RED -> "CI_ROUND";
                    case AGENT_RESULT_READY -> "AGENT_RUN";
                    case INITIAL_TASK -> "TASK";
                };
                String subjectDigest = stableId(
                        "writer-subject",
                        task.taskId(),
                        Long.toString(task.epoch()),
                        item.kind().name(),
                        item.externalKey(),
                        item.subjectHead());
                String operationId = stableId(
                        "operation",
                        ownerKind,
                        item.externalKey(),
                        writerKind.name(),
                        subjectDigest);
                insertOperationAndTicket(
                        operationId,
                        ownerKind,
                        item.externalKey(),
                        task.taskId(),
                        writerKind,
                        subjectDigest,
                        "inbox:" + item.pendingId(),
                        null,
                        writerKind == OperationKind.RUN_CI_FIXER
                                ? CI_FIX_PRIORITY
                                : TASK_TURN_PRIORITY,
                        clock.instant());
                int pointerUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET selected_writer_operation_id = ?
                        WHERE task_id = ?
                          AND selected_writer_operation_id IS NULL
                          AND epoch = ?
                        """,
                        operationId,
                        task.taskId(),
                        task.epoch());
                if (pointerUpdated != 1) {
                    throw new StaleOwnerRevisionException(
                            "Task writer selection changed concurrently");
                }
                jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET selected_by_operation_id = ?
                        WHERE inbox_id = ?
                          AND selected_by_operation_id IS NULL
                        """,
                        operationId,
                        item.pendingId());
                selected = Optional.of(requireOperation(operationId));
                break;
            }

            settleDispatch(
                    reconciliation.operationId(),
                    OperationState.SUCCEEDED,
                    selected.map(Operation::operationId).orElse("NO_CURRENT_WORK"));
            jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET last_reconciled_work_watermark = MAX(
                        last_reconciled_work_watermark, ?)
                    WHERE task_id = ?
                    """,
                    reconciliation.workWatermark(),
                    task.taskId());
            ensureReconciliationIfPending(task.taskId());
            return selected;
        });
    }

    /** Acquires or idempotently returns the Task's sole live writer fence. */
    public synchronized WriterFence acquireWriterLease(
            Claim claim,
            AgentRole holderKind,
            WorktreeSnapshot snapshot,
            Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(holderKind, "holderKind is null");
        requireNonNull(snapshot, "snapshot is null");
        requirePositive(leaseTtl, "leaseTtl");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Instant claimExpiresAt = currentClaimExpiry(claim);
            String claimDigest = claimTokenDigest(claim);
            Operation operation = requireOperation(claim.operationId());
            AgentRole requiredRole = roleForWriter(operation.kind());
            if (holderKind != requiredRole) {
                throw new IllegalArgumentException(
                        "writer holder does not match operation kind");
            }
            Task task = requireTask(operation.taskId());
            if (task.status() != TaskStatus.ACTIVE
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())) {
                throw new MutationRejectedException(
                        "operation is not the selected active Task writer");
            }
            if (!snapshot.headSha().equals(task.currentHeadSha())) {
                throw new MutationRejectedException(
                        "worktree snapshot is not the current Task head");
            }
            assertWriterSubjectCurrent(operation);

            Optional<WriterFence> existing = writerFence(task.taskId());
            if (existing.isPresent()) {
                WriterFence fence = existing.get();
                if (fence.operationId().equals(operation.operationId())
                        && fence.taskEpoch() == task.epoch()
                        && fence.claimGeneration() == claim.generation()
                        && fence.claimTokenDigest().equals(claimDigest)
                        && fence.headSha().equals(snapshot.headSha())
                        && fence.treeDigest().equals(snapshot.treeDigest())
                        && fence.snapshotEvidenceRef().equals(
                                snapshot.evidenceRef())
                        && fence.expiresAt().isAfter(clock.instant())) {
                    return fence;
                }
                throw new MutationRejectedException(
                        "Task already has a writer lease; recovery must resolve it");
            }

            long token = task.writerFenceSequence() + 1;
            int advanced = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET writer_fence_sequence = ?
                    WHERE task_id = ? AND writer_fence_sequence = ?
                      AND epoch = ? AND selected_writer_operation_id = ?
                    """,
                    token,
                    task.taskId(),
                    task.writerFenceSequence(),
                    task.epoch(),
                    operation.operationId());
            if (advanced != 1) {
                throw new StaleOwnerRevisionException(
                        "Task changed during writer admission");
            }
            Instant expiresAt = earlier(
                    clock.instant().plus(leaseTtl), claimExpiresAt);
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_writer_lease (
                        task_id, operation_id, task_epoch, holder_kind,
                        fencing_token, claim_generation, claim_token_digest,
                        head_sha, tree_digest, snapshot_evidence_ref, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    task.taskId(),
                    operation.operationId(),
                    task.epoch(),
                    holderKind.name(),
                    token,
                    claim.generation(),
                    claimDigest,
                    snapshot.headSha(),
                    snapshot.treeDigest(),
                    snapshot.evidenceRef(),
                    expiresAt.toEpochMilli());
            return new WriterFence(
                    task.taskId(),
                    operation.operationId(),
                    task.epoch(),
                    holderKind,
                    token,
                    claim.generation(),
                    claimDigest,
                    snapshot.headSha(),
                    snapshot.treeDigest(),
                    snapshot.evidenceRef(),
                    expiresAt);
        });
    }

    /** Renews only the live current claim generation. */
    public synchronized Claim renewClaim(Claim claim, Duration claimTtl)
    {
        requireNonNull(claim, "claim is null");
        requirePositive(claimTtl, "claimTtl");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Instant requestedExpiry = clock.instant().plus(claimTtl);
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET claim_expires_at = MAX(claim_expires_at, ?)
                    WHERE operation_id = ? AND claim_generation = ?
                      AND claim_token = ? AND claim_owner = ?
                      AND delivery_state = 'CLAIMED'
                      AND claim_expires_at > ?
                    """,
                    requestedExpiry.toEpochMilli(),
                    claim.operationId(),
                    claim.generation(),
                    claim.claimToken(),
                    claim.workerId(),
                    clock.instant().toEpochMilli());
            if (updated != 1) {
                throw new StaleClaimException(
                        "claim generation changed during renewal");
            }
            Instant expiresAt = currentClaimExpiry(claim);
            return new Claim(
                    claim.operationId(),
                    claim.taskId(),
                    claim.kind(),
                    claim.generation(),
                    claim.claimToken(),
                    claim.workerId(),
                    expiresAt);
        });
    }

    /** Renews only the same live fencing token under its current claim. */
    public synchronized WriterFence renewWriterLease(
            Claim claim, WriterFence fence, Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requirePositive(leaseTtl, "leaseTtl");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Operation operation = requireOperation(claim.operationId());
            assertFenceOwnsOperation(
                    fence, operation, roleForWriter(operation.kind()));
            Instant expiresAt = earlier(
                    clock.instant().plus(leaseTtl),
                    currentClaimExpiry(claim));
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_writer_lease
                    SET expires_at = ?
                    WHERE task_id = ? AND operation_id = ?
                      AND task_epoch = ? AND fencing_token = ?
                      AND claim_generation = ? AND claim_token_digest = ?
                      AND expires_at > ?
                    """,
                    expiresAt.toEpochMilli(),
                    fence.taskId(),
                    fence.operationId(),
                    fence.taskEpoch(),
                    fence.fencingToken(),
                    fence.claimGeneration(),
                    fence.claimTokenDigest(),
                    clock.instant().toEpochMilli());
            if (updated != 1) {
                throw new StaleWriterFenceException(
                        "writer fence changed during renewal");
            }
            return new WriterFence(
                    fence.taskId(),
                    fence.operationId(),
                    fence.taskEpoch(),
                    fence.holderKind(),
                    fence.fencingToken(),
                    fence.claimGeneration(),
                    fence.claimTokenDigest(),
                    fence.headSha(),
                    fence.treeDigest(),
                    fence.snapshotEvidenceRef(),
                    expiresAt);
        });
    }

    /** Fails before an adapter mutation when any fence component is stale. */
    public synchronized void assertWriterFence(
            Claim claim, WriterFence fence)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        inTransaction(() -> {
            assertFenceRow(claim, fence);
            return Boolean.TRUE;
        });
    }

    /**
     * Lazily creates the Task's persistent CI session (or resumes its existing
     * session) and creates one operation-bound run.
     */
    public synchronized AgentRun startWriterAgent(
            Claim claim,
            WriterFence fence,
            String promptManifestRef,
            String capabilitySetRef)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(promptManifestRef, "promptManifestRef");
        requireText(capabilitySetRef, "capabilitySetRef");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Operation operation = requireOperation(claim.operationId());
            AgentRole role = roleForWriter(operation.kind());
            assertFenceOwnsOperation(fence, operation, role);
            Optional<AgentRun> existingRun = runForOperation(
                    operation.operationId());
            if (existingRun.isPresent()) {
                AgentRun run = existingRun.get();
                if (run.role() != role
                        || !run.promptManifestRef().equals(promptManifestRef)
                        || !run.capabilitySetRef().equals(capabilitySetRef)) {
                    throw new IllegalStateException(
                            "operation already owns a different AgentRun subject");
                }
                return run;
            }

            Task task = requireTask(operation.taskId());
            AgentSession session = sessionForRole(task, role)
                    .orElseGet(() -> createCiSession(task, role));
            if (session.state() != SessionState.IDLE) {
                throw new MutationRejectedException(
                        "persistent agent session is not idle");
            }
            String runId = stableId(
                    "agent-run", operation.operationId());
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_run (
                        run_id, operation_id, session_id, role, head_sha,
                        prompt_manifest_ref, capability_set_ref, input_ref,
                        state, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                    """,
                    runId,
                    operation.operationId(),
                    session.sessionId(),
                    role.name(),
                    task.currentHeadSha(),
                    promptManifestRef,
                    capabilitySetRef,
                    operation.inputRef(),
                    now.toEpochMilli());
            int reserved = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = 'RUNNING', last_run_id = ?, updated_at = ?
                    WHERE session_id = ? AND state = 'IDLE'
                    """,
                    runId,
                    now.toEpochMilli(),
                    session.sessionId());
            if (reserved != 1) {
                throw new MutationRejectedException(
                        "persistent agent session changed concurrently");
            }
            return requireRun(runId);
        });
    }

    /** Reserves one dormant process/capability identity for this claim generation. */
    public synchronized AgentProcessAttempt reserveProcessAttempt(
            String runId, Claim claim, WriterFence fence)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            AgentRun run = requireRun(runId);
            if (!run.operationId().equals(claim.operationId())) {
                throw new StaleClaimException(
                        "run belongs to another dispatch operation");
            }
            String attemptId = stableId(
                    "process-attempt", runId,
                    Long.toString(claim.generation()));
            Optional<AgentProcessAttempt> existing = processAttempt(attemptId);
            if (existing.isPresent()) {
                AgentProcessAttempt attempt = existing.get();
                if (!attempt.runId().equals(runId)
                        || !attempt.operationId().equals(claim.operationId())
                        || attempt.claimGeneration() != claim.generation()) {
                    throw new IllegalStateException(
                            "process-attempt identity has conflicting content");
                }
                return attempt;
            }
            if (run.state() != RunState.QUEUED) {
                throw new StaleClaimException(
                        "run is not queued for a new process attempt");
            }
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_process_attempt (
                        process_attempt_id, run_id, operation_id,
                        claim_generation, execution_id, capability_id,
                        state, reserved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'RESERVED', ?)
                    """,
                    attemptId,
                    runId,
                    claim.operationId(),
                    claim.generation(),
                    stableId("execution", runId,
                            Long.toString(claim.generation())),
                    stableId("capability", runId,
                            Long.toString(claim.generation())),
                    now.toEpochMilli());
            return requireProcessAttempt(attemptId);
        });
    }

    /**
     * Records the concrete dormant process identity before the logical run can
     * become RUNNING. The caller activates model/tools only after this commits.
     */
    public synchronized AgentRun activateProcessAttempt(
            String processAttemptId,
            Claim claim,
            WriterFence fence,
            String processIdentity)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(processIdentity, "processIdentity");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
            AgentRun run = requireRun(attempt.runId());
            if (!attempt.operationId().equals(claim.operationId())
                    || attempt.claimGeneration() != claim.generation()) {
                throw new StaleClaimException(
                        "process attempt belongs to another claim generation");
            }
            if (attempt.state() == ProcessAttemptState.ACTIVATED) {
                if (!processIdentity.equals(attempt.processIdentity())
                        || run.state() != RunState.RUNNING) {
                    throw new IllegalStateException(
                            "process activation redelivery changed identity");
                }
                return run;
            }
            if (attempt.state() != ProcessAttemptState.RESERVED
                    || run.state() != RunState.QUEUED) {
                throw new IllegalStateException(
                        "process attempt cannot be activated");
            }
            Instant now = clock.instant();
            int processUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET state = 'ACTIVATED', process_identity = ?,
                        activated_at = ?
                    WHERE process_attempt_id = ? AND state = 'RESERVED'
                    """,
                    processIdentity,
                    now.toEpochMilli(),
                    processAttemptId);
            int runUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_run
                    SET state = 'RUNNING',
                        started_at = COALESCE(started_at, ?)
                    WHERE run_id = ? AND state = 'QUEUED'
                    """,
                    now.toEpochMilli(),
                    run.runId());
            if (processUpdated != 1 || runUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "process/run changed during activation");
            }
            return requireRun(run.runId());
        });
    }

    /**
     * Stores the tagged terminal result before releasing the lease/session.
     * The final content is never inspected or normalized.
     */
    public synchronized AgentResult finishAgentRun(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String processMetadataRef)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        requireText(processMetadataRef, "processMetadataRef");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        return inTransaction(() -> {
            Optional<AgentResult> existing = resultForRun(runId);
            if (existing.isPresent()) {
                AgentResult result = existing.get();
                assertFinalizedClaim(claim, result.resultId());
                if (result.terminalOutcome() != terminalOutcome
                        || !Objects.equals(result.finalContent(), finalContent)
                        || !Objects.equals(result.errorRef(), errorRef)
                        || !result.processMetadataRef()
                                .equals(processMetadataRef)) {
                    throw new IllegalStateException(
                            "AgentResult redelivery changed terminal content");
                }
                assertStoppedProcessMetadata(
                        runId, claim.generation(), processMetadataRef);
                return result;
            }

            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            AgentRun run = requireRun(runId);
            if (!run.operationId().equals(claim.operationId())
                    || run.state() != RunState.RUNNING) {
                throw new StaleClaimException(
                        "run is not active under this dispatch claim");
            }
            assertFenceOwnsOperation(
                    fence, requireOperation(run.operationId()), run.role());

            Instant now = clock.instant();
            AgentProcessAttempt processAttempt = requireActivatedProcessAttempt(
                    runId, claim.generation());
            completeCurrentProcessAttempt(
                    processAttempt, processMetadataRef, now);
            String resultId = stableId("agent-result", runId);
            int resultStored = jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_result (
                        result_id, run_id, terminal_outcome, final_content,
                        error_ref, process_metadata_ref, stored_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    resultId,
                    runId,
                    terminalOutcome.name(),
                    finalContent,
                    errorRef,
                    processMetadataRef,
                    now.toEpochMilli());
            RunState runState = switch (terminalOutcome) {
                case COMPLETED -> RunState.COMPLETED;
                case FAILED -> RunState.FAILED;
                case CANCELED -> RunState.CANCELED;
            };
            int runFinished = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_run
                    SET state = ?, failure_reason_code = ?, completed_at = ?
                    WHERE run_id = ? AND state = 'RUNNING'
                    """,
                    runState.name(),
                    errorRef,
                    now.toEpochMilli(),
                    runId);
            int sessionReleased = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = 'IDLE', updated_at = ?
                    WHERE session_id = ? AND state = 'RUNNING'
                      AND last_run_id = ?
                    """,
                    now.toEpochMilli(),
                    run.sessionId(),
                    runId);
            if (resultStored != 1 || runFinished != 1
                    || sessionReleased != 1) {
                throw new StaleOwnerRevisionException(
                        "agent run/session changed during finalization");
            }
            OperationState operationState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            settleDispatch(claim.operationId(), operationState, resultId);
            jdbc.update(
                    """
                    UPDATE flow_runtime_inbox
                    SET handled_by_operation_id = ?
                    WHERE selected_by_operation_id = ?
                      AND handled_by_operation_id IS NULL
                    """,
                    claim.operationId(),
                    claim.operationId());
            if (run.role() == AgentRole.CI_FIXER) {
                Task task = requireTask(fence.taskId());
                appendPendingWork(
                        stableId("inbox", "AGENT_RESULT", runId, "1"),
                        task,
                        task.prId(),
                        "AGENT_RESULT",
                        runId,
                        "1",
                        PendingKind.AGENT_RESULT_READY,
                        run.headSha(),
                        "agent-result:" + resultId,
                        resultId,
                        now);
            }
            int pointerCleared = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET selected_writer_operation_id = NULL
                    WHERE task_id = ?
                      AND selected_writer_operation_id = ?
                    """,
                    fence.taskId(),
                    claim.operationId());
            int leaseReleased = jdbc.update(
                    """
                    DELETE FROM flow_runtime_writer_lease
                    WHERE task_id = ? AND operation_id = ?
                      AND task_epoch = ? AND fencing_token = ?
                    """,
                    fence.taskId(),
                    fence.operationId(),
                    fence.taskEpoch(),
                    fence.fencingToken());
            if (pointerCleared != 1 || leaseReleased != 1) {
                throw new StaleOwnerRevisionException(
                        "writer finalization lost its Task fence");
            }
            if (run.role() == AgentRole.TASK_AGENT
                    && terminalOutcome != TerminalOutcome.COMPLETED) {
                moveTaskToAttentionAfterAgentStop(
                        fence.taskId(),
                        claim.operationId(),
                        resultId,
                        terminalOutcome,
                        now);
            }
            rearmReconciliationAfterWriter(fence.taskId());
            ensureReconciliationIfPending(fence.taskId());
            return requireResult(resultId);
        });
    }

    public Optional<Task> task(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_task WHERE task_id = ?",
                (result, row) -> readTask(result),
                taskId).stream().findFirst();
    }

    public Optional<PullRequestSubject> pullRequest(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query(
                prSubjectSql() + " WHERE p.pr_id = ?",
                (result, row) -> readPullRequest(result),
                prId).stream().findFirst();
    }

    public Optional<Operation> operation(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_operation WHERE operation_id = ?",
                (result, row) -> readOperation(result),
                operationId).stream().findFirst();
    }

    public Optional<AgentSession> session(String taskId, AgentRole role)
    {
        requireText(taskId, "taskId");
        requireNonNull(role, "role is null");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_session
                WHERE task_id = ? AND role = ?
                """,
                (result, row) -> readSession(result),
                taskId,
                role.name()).stream().findFirst();
    }

    public Optional<AgentRun> runForOperation(String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_run
                WHERE operation_id = ?
                """,
                (result, row) -> readRun(result),
                operationId).stream().findFirst();
    }

    public Optional<AgentResult> resultForRun(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_agent_result WHERE run_id = ?",
                (result, row) -> readResult(result),
                runId).stream().findFirst();
    }

    public List<PendingWork> pendingWork(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_inbox
                WHERE task_id = ?
                ORDER BY work_watermark
                """,
                (result, row) -> readInbox(result),
                taskId);
    }

    private PendingWork appendPendingWork(
            String pendingId,
            Task task,
            String prId,
            String source,
            String externalKey,
            String revision,
            PendingKind kind,
            String subjectHead,
            String payloadRef,
            String agentResultId,
            Instant now)
    {
        Optional<PendingWork> existing = inbox(pendingId);
        if (existing.isPresent()) {
            PendingWork pending = existing.get();
            if (!pending.taskId().equals(task.taskId())
                    || !Objects.equals(pending.prId(), prId)
                    || pending.kind() != kind
                    || !pending.externalKey().equals(externalKey)
                    || !pending.subjectHead().equals(subjectHead)
                    || !pending.payloadRef().equals(payloadRef)
                    || !Objects.equals(pending.agentResultId(), agentResultId)) {
                throw new IllegalStateException(
                        "pending-work identity has conflicting content");
            }
            return pending;
        }
        long watermark = task.pendingWorkWatermark() + 1;
        jdbc.update(
                """
                INSERT INTO flow_runtime_inbox (
                    inbox_id, task_id, pr_id, source, external_key,
                    revision, kind, subject_head, payload_ref,
                    agent_result_id, work_watermark, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                pendingId,
                task.taskId(),
                prId,
                source,
                externalKey,
                revision,
                kind.name(),
                subjectHead,
                payloadRef,
                agentResultId,
                watermark,
                now.toEpochMilli());
        int advanced = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET pending_work_watermark = ?
                WHERE task_id = ? AND pending_work_watermark = ?
                """,
                watermark,
                task.taskId(),
                task.pendingWorkWatermark());
        if (advanced != 1) {
            throw new StaleOwnerRevisionException(
                    "Task work watermark changed concurrently");
        }
        return inbox(pendingId).orElseThrow();
    }

    private boolean pendingSubjectIsCurrent(Task task, PendingWork pending)
    {
        if (!pending.taskId().equals(task.taskId())) {
            return false;
        }
        return switch (pending.kind()) {
            case INITIAL_TASK -> pending.subjectHead().equals(task.currentHeadSha());
            case FINAL_RED -> {
                if (pending.prId() == null) {
                    yield false;
                }
                PullRequestSubject pr = requirePullRequest(pending.prId());
                yield pr.published()
                        && pr.taskId().equals(task.taskId())
                        && pending.subjectHead().equals(pr.currentRemoteHead())
                        && pending.subjectHead().equals(task.currentHeadSha());
            }
            case AGENT_RESULT_READY -> {
                Integer resultExists = jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM flow_runtime_agent_result
                        WHERE result_id = ?
                        """,
                        Integer.class,
                        pending.agentResultId());
                yield requireNonNull(resultExists, "result count is null") == 1
                        && pending.subjectHead().equals(task.currentHeadSha());
            }
        };
    }

    private Operation ensureReconciliation(String taskId)
    {
        Optional<Operation> live = jdbc.query(
                """
                SELECT * FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                LIMIT 1
                """,
                (result, row) -> readOperation(result),
                taskId).stream().findFirst();
        if (live.isPresent()) {
            return live.get();
        }
        Task task = requireTask(taskId);
        long sequence = task.reconciliationSequence() + 1;
        long throughWatermark = task.pendingWorkWatermark();
        int advanced = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET reconciliation_sequence = ?
                WHERE task_id = ? AND reconciliation_sequence = ?
                """,
                sequence,
                taskId,
                task.reconciliationSequence());
        if (advanced != 1) {
            throw new StaleOwnerRevisionException(
                    "reconciliation sequence changed concurrently");
        }
        String subjectDigest = stableId(
                "reconciliation-subject",
                taskId,
                Long.toString(task.epoch()),
                Long.toString(sequence),
                Long.toString(throughWatermark));
        String operationId = stableId(
                "operation",
                taskId,
                OperationKind.RECONCILE_TASK.name(),
                subjectDigest);
        insertOperationAndTicket(
                operationId,
                "TASK",
                taskId,
                taskId,
                OperationKind.RECONCILE_TASK,
                subjectDigest,
                "work-watermark:" + throughWatermark,
                throughWatermark,
                RECONCILIATION_PRIORITY,
                clock.instant());
        return requireOperation(operationId);
    }

    private void parkReconciliation(String operationId)
    {
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = 'WAITING', result_ref = 'TASK_PAUSED'
                WHERE operation_id = ? AND kind = 'RECONCILE_TASK'
                  AND state = 'CLAIMED'
                """,
                operationId);
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'DONE'
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                """,
                operationId);
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "reconciliation changed while parking");
        }
    }

    private void resumeWaitingReconciliation(String taskId)
    {
        List<String> waiting = jdbc.query(
                """
                SELECT operation_id FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND state = 'WAITING'
                """,
                (result, row) -> result.getString("operation_id"),
                taskId);
        for (String operationId : waiting) {
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'READY', result_ref = NULL
                    WHERE operation_id = ? AND state = 'WAITING'
                    """,
                    operationId);
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                        claim_expires_at = NULL, claim_token = NULL
                    WHERE operation_id = ? AND delivery_state = 'DONE'
                    """,
                    operationId);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "waiting reconciliation changed during Task resume");
            }
        }
    }

    private Operation reconciliationCovering(String taskId, long watermark)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND work_watermark >= ?
                ORDER BY work_watermark, created_at, operation_id
                LIMIT 1
                """,
                (result, row) -> readOperation(result),
                taskId,
                watermark).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "selected pending work has no reconciliation owner"));
    }

    private void ensureReconciliationIfPending(String taskId)
    {
        Integer pending = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_inbox
                WHERE task_id = ? AND handled_by_operation_id IS NULL
                  AND selected_by_operation_id IS NULL
                  AND terminal_reason IS NULL
                """,
                Integer.class,
                taskId);
        if (requireNonNull(pending, "pending count is null") > 0) {
            ensureReconciliation(taskId);
        }
    }

    /**
     * A pass frozen while a writer ran cannot omit a newer continuation.
     * Cancel that immutable generation and create a fresh one after the
     * caller has released the writer pointer.
     */
    private void rearmReconciliationAfterWriter(String taskId)
    {
        Task task = requireTask(taskId);
        List<String> stale = jdbc.query(
                """
                SELECT operation_id FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RECONCILE_TASK'
                  AND state = 'READY' AND work_watermark < ?
                """,
                (result, row) -> result.getString("operation_id"),
                taskId,
                task.pendingWorkWatermark());
        for (String operationId : stale) {
            int operationUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'CANCELED', result_ref = 'WRITER_ADVANCED'
                    WHERE operation_id = ? AND state = 'READY'
                    """,
                    operationId);
            int ticketUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_dispatch_ticket
                    SET delivery_state = 'DONE'
                    WHERE operation_id = ? AND delivery_state = 'AVAILABLE'
                    """,
                    operationId);
            if (operationUpdated != 1 || ticketUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "reconciliation changed during writer release");
            }
        }
    }

    private void insertOperationAndTicket(
            String operationId,
            String ownerKind,
            String ownerId,
            String taskId,
            OperationKind kind,
            String subjectDigest,
            String inputRef,
            Long workWatermark,
            int priority,
            Instant now)
    {
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_runtime_operation (
                    operation_id, owner_kind, owner_id, task_id, kind,
                    subject_digest, input_ref, work_watermark, state, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?)
                """,
                operationId,
                ownerKind,
                ownerId,
                taskId,
                kind.name(),
                subjectDigest,
                inputRef,
                workWatermark,
                now.toEpochMilli());
        Operation stored = requireOperation(operationId);
        if (!stored.ownerKind().equals(ownerKind)
                || !stored.ownerId().equals(ownerId)
                || !Objects.equals(stored.taskId(), taskId)
                || stored.kind() != kind
                || !stored.subjectDigest().equals(subjectDigest)
                || !stored.inputRef().equals(inputRef)
                || !Objects.equals(stored.workWatermark(), workWatermark)) {
            throw new IllegalStateException(
                    "operation identity has conflicting content");
        }
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_runtime_dispatch_ticket (
                    operation_id, not_before, priority, delivery_state
                ) VALUES (?, ?, ?, 'AVAILABLE')
                """,
                operationId,
                now.toEpochMilli(),
                priority);
    }

    private void settleDispatch(
            String operationId, OperationState state, String resultRef)
    {
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = ?, result_ref = ?
                WHERE operation_id = ? AND state = 'CLAIMED'
                """,
                state.name(),
                resultRef,
                operationId);
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'DONE'
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                """,
                operationId);
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "dispatch operation is no longer claimed");
        }
    }

    private void assertCurrentClaim(
            Claim claim, OperationState expectedOperationState)
    {
        assertClaimGeneration(claim, expectedOperationState, true);
    }

    private Instant currentClaimExpiry(Claim claim)
    {
        return jdbc.query(
                """
                SELECT claim_expires_at
                FROM flow_runtime_dispatch_ticket
                WHERE operation_id = ? AND claim_generation = ?
                  AND claim_token = ? AND claim_owner = ?
                  AND delivery_state = 'CLAIMED'
                  AND claim_expires_at > ?
                """,
                (result, row) -> instant(result, "claim_expires_at"),
                claim.operationId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                clock.instant().toEpochMilli()).stream().findFirst()
                .orElseThrow(() -> new StaleClaimException(
                        "dispatch claim generation is stale"));
    }

    private static String claimTokenDigest(Claim claim)
    {
        return stableId(
                "claim-token", claim.operationId(), claim.claimToken());
    }

    private static Instant earlier(Instant first, Instant second)
    {
        return first.isBefore(second) ? first : second;
    }

    private ExpiredClaim requireExpiredClaim(
            String operationId, long generation)
    {
        return expiredClaims().stream()
                .filter(claim -> claim.operationId().equals(operationId)
                        && claim.generation() == generation)
                .findFirst()
                .orElseThrow(() -> new StaleClaimException(
                        "claim generation is not durably expired"));
    }

    private Optional<AgentProcessAttempt> processAttempt(String attemptId)
    {
        if (attemptId == null) {
            return Optional.empty();
        }
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_process_attempt
                WHERE process_attempt_id = ?
                """,
                (result, row) -> readProcessAttempt(result),
                attemptId).stream().findFirst();
    }

    private AgentProcessAttempt requireProcessAttempt(String attemptId)
    {
        return processAttempt(attemptId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Unknown process attempt: " + attemptId));
    }

    private AgentProcessAttempt requireActivatedProcessAttempt(
            String runId, long generation)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_process_attempt
                WHERE run_id = ? AND claim_generation = ?
                  AND state = 'ACTIVATED'
                """,
                (result, row) -> readProcessAttempt(result),
                runId,
                generation).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "run has no activated process for this claim"));
    }

    private void completeCurrentProcessAttempt(
            AgentProcessAttempt attempt,
            String processMetadataRef,
            Instant stoppedAt)
    {
        if (attempt.state() == ProcessAttemptState.STOPPED) {
            if (!Objects.equals(
                    attempt.processMetadataRef(), processMetadataRef)) {
                throw new IllegalStateException(
                        "process completion changed opaque metadata");
            }
            return;
        }
        if (attempt.state() != ProcessAttemptState.ACTIVATED) {
            throw new IllegalStateException(
                    "only the current activated process can complete a run");
        }
        int updated = jdbc.update(
                """
                UPDATE flow_runtime_agent_process_attempt
                SET state = 'STOPPED', process_metadata_ref = ?, stopped_at = ?
                WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                  AND process_identity IS NOT NULL
                """,
                processMetadataRef,
                stoppedAt.toEpochMilli(),
                attempt.processAttemptId());
        if (updated != 1) {
            throw new StaleOwnerRevisionException(
                    "process attempt changed during current completion");
        }
    }

    private void assertStoppedProcessMetadata(
            String runId,
            long generation,
            String processMetadataRef)
    {
        AgentProcessAttempt attempt = jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_process_attempt
                WHERE run_id = ? AND claim_generation = ?
                  AND state = 'STOPPED'
                """,
                (result, row) -> readProcessAttempt(result),
                runId,
                generation).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "finalized run has no stopped process attempt"));
        if (!Objects.equals(
                attempt.processMetadataRef(), processMetadataRef)) {
            throw new IllegalStateException(
                    "process completion changed opaque metadata");
        }
    }

    private boolean ticketMatches(
            String operationId, long generation, String deliveryState)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_dispatch_ticket
                WHERE operation_id = ? AND claim_generation = ?
                  AND delivery_state = ?
                """,
                Integer.class,
                operationId,
                generation,
                deliveryState);
        return requireNonNull(count, "ticket count is null") == 1;
    }

    private void recoverNeverLaunchedClaim(ExpiredClaim expired)
    {
        AgentRun run = expired.runId() == null
                ? null
                : requireRun(expired.runId());
        if (run != null && run.state() != RunState.QUEUED) {
            throw new IllegalStateException(
                    "missing process attempt conflicts with running AgentRun");
        }
        jdbc.update(
                """
                DELETE FROM flow_runtime_writer_lease
                WHERE task_id = ? AND operation_id = ?
                """,
                expired.taskId(),
                expired.operationId());
        int operationUpdated = jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = 'RETRYABLE'
                WHERE operation_id = ? AND state = 'CLAIMED'
                """,
                expired.operationId());
        int ticketUpdated = jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'AVAILABLE', claim_owner = NULL,
                    claim_expires_at = NULL, claim_token = NULL
                WHERE operation_id = ? AND delivery_state = 'CLAIMED'
                  AND claim_generation = ?
                """,
                expired.operationId(),
                expired.generation());
        if (operationUpdated != 1 || ticketUpdated != 1) {
            throw new StaleClaimException(
                    "never-launched claim changed during recovery");
        }
    }

    private void quarantineExpiredProcessAttempt(ExpiredClaim expired)
    {
        String reason = "PROCESS_ATTEMPT_RECOVERY_REQUIRED";
        settleDispatch(expired.operationId(), OperationState.FAILED,
                reason + ":" + expired.processAttemptId());
        Task task = requireTask(expired.taskId());
        TaskLifecycleRevision attention = appendLifecycle(
                task,
                TaskStatus.NEEDS_ATTENTION,
                reason,
                "process-attempt:" + expired.processAttemptId(),
                expired.operationId(),
                clock.instant());
        int taskUpdated = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET status = 'NEEDS_ATTENTION',
                    current_lifecycle_revision_id = ?
                WHERE task_id = ? AND status = 'ACTIVE'
                  AND selected_writer_operation_id = ?
                  AND current_lifecycle_revision_id = ?
                """,
                attention.lifecycleRevisionId(),
                task.taskId(),
                expired.operationId(),
                task.currentLifecycleRevisionId());
        if (taskUpdated != 1) {
            throw new StaleOwnerRevisionException(
                    "process-attempt quarantine lost its Task owner revision");
        }
    }

    private void assertClaimGeneration(
            Claim claim,
            OperationState expectedOperationState,
            boolean requireUnexpired)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_dispatch_ticket d
                JOIN flow_runtime_operation o
                  ON o.operation_id = d.operation_id
                WHERE d.operation_id = ?
                  AND d.claim_generation = ?
                  AND d.claim_token = ?
                  AND d.claim_owner = ?
                  AND d.delivery_state = 'CLAIMED'
                  AND o.state = ?
                  AND (? = 0 OR d.claim_expires_at > ?)
                """,
                Integer.class,
                claim.operationId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                expectedOperationState.name(),
                requireUnexpired ? 1 : 0,
                clock.instant().toEpochMilli());
        if (requireNonNull(matches, "claim count is null") != 1) {
            throw new StaleClaimException(
                    "dispatch claim generation is stale");
        }
    }

    private void assertFinalizedClaim(Claim claim, String resultId)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_dispatch_ticket d
                JOIN flow_runtime_operation o
                  ON o.operation_id = d.operation_id
                WHERE d.operation_id = ?
                  AND d.claim_generation = ?
                  AND d.claim_token = ?
                  AND d.claim_owner = ?
                  AND d.delivery_state = 'DONE'
                  AND o.result_ref = ?
                """,
                Integer.class,
                claim.operationId(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                resultId);
        if (requireNonNull(matches, "final claim count is null") != 1) {
            throw new StaleClaimException(
                    "terminal result does not belong to this claim generation");
        }
    }

    private void assertFenceRow(Claim claim, WriterFence fence)
    {
        if (!fence.operationId().equals(claim.operationId())
                || fence.claimGeneration() != claim.generation()
                || !fence.claimTokenDigest().equals(claimTokenDigest(claim))) {
            throw new StaleWriterFenceException(
                    "writer fence is not bound to the current claim");
        }
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_writer_lease l
                JOIN flow_runtime_task t ON t.task_id = l.task_id
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = l.operation_id
                JOIN flow_runtime_operation o
                  ON o.operation_id = l.operation_id
                WHERE l.task_id = ? AND l.operation_id = ?
                  AND l.task_epoch = ? AND l.holder_kind = ?
                  AND l.fencing_token = ?
                  AND l.claim_generation = ?
                  AND l.claim_token_digest = ?
                  AND l.head_sha = ?
                  AND l.tree_digest = ? AND l.snapshot_evidence_ref = ?
                  AND l.expires_at = ? AND l.expires_at > ?
                  AND l.expires_at <= d.claim_expires_at
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ? AND d.delivery_state = 'CLAIMED'
                  AND d.claim_expires_at > ? AND o.state = 'CLAIMED'
                  AND t.epoch = l.task_epoch
                  AND t.status = 'ACTIVE'
                  AND t.selected_writer_operation_id = l.operation_id
                """,
                Integer.class,
                fence.taskId(),
                fence.operationId(),
                fence.taskEpoch(),
                fence.holderKind().name(),
                fence.fencingToken(),
                fence.claimGeneration(),
                fence.claimTokenDigest(),
                fence.headSha(),
                fence.treeDigest(),
                fence.snapshotEvidenceRef(),
                fence.expiresAt().toEpochMilli(),
                clock.instant().toEpochMilli(),
                claim.generation(),
                claim.claimToken(),
                claim.workerId(),
                clock.instant().toEpochMilli());
        if (requireNonNull(matches, "writer authority count is null") != 1) {
            throw new StaleWriterFenceException("writer fence is stale");
        }
        assertWriterSubjectCurrent(requireOperation(fence.operationId()));
    }

    private void assertWriterSubjectCurrent(Operation operation)
    {
        String condition = operation.kind() == OperationKind.RUN_CI_FIXER
                ? """
                  i.kind = 'FINAL_RED'
                  AND i.pr_id = p.pr_id
                  AND i.subject_head = p.current_remote_head
                  AND i.subject_head = t.current_head_sha
                  """
                : """
                  i.kind IN ('INITIAL_TASK', 'AGENT_RESULT_READY')
                  AND i.subject_head = t.current_head_sha
                  """;
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_inbox i
                JOIN flow_runtime_task t ON t.task_id = i.task_id
                LEFT JOIN flow_runtime_pr p ON p.pr_id = i.pr_id
                WHERE i.selected_by_operation_id = ?
                  AND i.handled_by_operation_id IS NULL
                  AND i.task_id = ?
                  AND t.status = 'ACTIVE'
                  AND
                """ + condition,
                Integer.class,
                operation.operationId(),
                operation.taskId());
        if (requireNonNull(matches, "writer subject count is null") != 1) {
            throw new MutationRejectedException(
                    "FINAL_RED is no longer the current remote PR subject");
        }
    }

    private static void assertFenceOwnsOperation(
            WriterFence fence, Operation operation, AgentRole role)
    {
        if (!fence.operationId().equals(operation.operationId())
                || !fence.taskId().equals(operation.taskId())
                || fence.holderKind() != role) {
            throw new MutationRejectedException(
                    "writer fence does not own the agent operation");
        }
    }

    private Optional<WriterFence> writerFence(String taskId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_writer_lease WHERE task_id = ?
                """,
                (result, row) -> new WriterFence(
                        result.getString("task_id"),
                        result.getString("operation_id"),
                        result.getLong("task_epoch"),
                        AgentRole.valueOf(result.getString("holder_kind")),
                        result.getLong("fencing_token"),
                        result.getLong("claim_generation"),
                        result.getString("claim_token_digest"),
                        result.getString("head_sha"),
                        result.getString("tree_digest"),
                        result.getString("snapshot_evidence_ref"),
                        instant(result, "expires_at")),
                taskId).stream().findFirst();
    }

    private Optional<AgentSession> sessionForRole(Task task, AgentRole role)
    {
        String sessionId = switch (role) {
            case TASK_AGENT -> task.taskSessionId();
            case CI_FIXER -> task.ciSessionId();
        };
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.of(requireSession(sessionId));
    }

    private AgentSession createCiSession(Task task, AgentRole role)
    {
        if (role != AgentRole.CI_FIXER || task.ciSessionId() != null) {
            throw new IllegalStateException(
                    "only the missing persistent CI session is lazy");
        }
        Instant now = clock.instant();
        String sessionId = stableId(
                "agent-session", task.taskId(), role.name());
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, created_at, updated_at
                ) VALUES (?, ?, 'CI_FIXER', 'IDLE', ?, ?)
                """,
                sessionId,
                task.taskId(),
                now.toEpochMilli(),
                now.toEpochMilli());
        int bound = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET ci_session_id = ?
                WHERE task_id = ? AND ci_session_id IS NULL
                """,
                sessionId,
                task.taskId());
        if (bound != 1) {
            throw new StaleOwnerRevisionException(
                    "CI session changed concurrently");
        }
        return requireSession(sessionId);
    }

    private TaskLifecycleRevision appendLifecycle(
            Task task,
            TaskStatus nextStatus,
            String reasonCode,
            String evidenceRef,
            String operationId,
            Instant now)
    {
        Long currentSequence = jdbc.queryForObject(
                """
                SELECT sequence FROM flow_runtime_task_lifecycle_revision
                WHERE lifecycle_revision_id = ?
                """,
                Long.class,
                task.currentLifecycleRevisionId());
        long sequence = requireNonNull(
                currentSequence, "lifecycle sequence is null") + 1;
        String revisionId = stableId(
                "task-lifecycle", task.taskId(), Long.toString(sequence));
        jdbc.update(
                """
                INSERT INTO flow_runtime_task_lifecycle_revision (
                    lifecycle_revision_id, task_id, sequence, from_status,
                    to_status, reason_code, evidence_ref, operation_id,
                    recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                revisionId,
                task.taskId(),
                sequence,
                task.status().name(),
                nextStatus.name(),
                reasonCode,
                evidenceRef,
                operationId,
                now.toEpochMilli());
        return new TaskLifecycleRevision(
                revisionId,
                task.taskId(),
                sequence,
                task.status(),
                nextStatus,
                reasonCode,
                evidenceRef,
                operationId,
                now);
    }

    private void moveTaskToAttentionAfterAgentStop(
            String taskId,
            String operationId,
            String resultId,
            TerminalOutcome outcome,
            Instant now)
    {
        Task task = requireTask(taskId);
        if (task.status() != TaskStatus.ACTIVE
                || task.selectedWriterOperationId() != null
                || writerFence(taskId).isPresent()) {
            throw new StaleOwnerRevisionException(
                    "Task Agent stopped without releasing its writer owner");
        }
        String reason = outcome == TerminalOutcome.FAILED
                ? "TASK_AGENT_FAILED"
                : "TASK_AGENT_CANCELED";
        TaskLifecycleRevision attention = appendLifecycle(
                task,
                TaskStatus.NEEDS_ATTENTION,
                reason,
                "agent-result:" + resultId,
                operationId,
                now);
        int updated = jdbc.update(
                """
                UPDATE flow_runtime_task
                SET status = 'NEEDS_ATTENTION',
                    current_lifecycle_revision_id = ?
                WHERE task_id = ? AND status = 'ACTIVE'
                  AND selected_writer_operation_id IS NULL
                  AND current_lifecycle_revision_id = ?
                """,
                attention.lifecycleRevisionId(),
                taskId,
                task.currentLifecycleRevisionId());
        if (updated != 1) {
            throw new StaleOwnerRevisionException(
                    "Task changed during Agent failure finalization");
        }
    }

    private static AgentRole roleForWriter(OperationKind kind)
    {
        return switch (kind) {
            case RUN_TASK_TURN -> AgentRole.TASK_AGENT;
            case RUN_CI_FIXER -> AgentRole.CI_FIXER;
            default -> throw new IllegalArgumentException(
                    "operation is not an agent writer: " + kind);
        };
    }

    private boolean hasClaimedOperation(String taskId)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation
                WHERE task_id = ? AND state = 'CLAIMED'
                """,
                Integer.class,
                taskId);
        return requireNonNull(count, "claimed operation count is null") > 0;
    }

    private void settleTaskOperationsAtTerminal(
            String taskId, TaskStatus terminalStatus)
    {
        String resultRef = "TASK_" + terminalStatus.name();
        jdbc.update(
                """
                UPDATE flow_runtime_operation
                SET state = 'CANCELED', result_ref = ?
                WHERE task_id = ?
                  AND state IN ('READY', 'WAITING', 'RETRYABLE')
                """,
                resultRef,
                taskId);
        jdbc.update(
                """
                UPDATE flow_runtime_dispatch_ticket
                SET delivery_state = 'DONE', claim_owner = NULL,
                    claim_expires_at = NULL, claim_token = NULL
                WHERE operation_id IN (
                    SELECT operation_id FROM flow_runtime_operation
                    WHERE task_id = ? AND state = 'CANCELED'
                      AND result_ref = ?
                )
                """,
                taskId,
                resultRef);
        jdbc.update(
                """
                UPDATE flow_runtime_inbox
                SET terminal_reason = ?
                WHERE task_id = ? AND selected_by_operation_id IS NULL
                  AND handled_by_operation_id IS NULL
                  AND terminal_reason IS NULL
                """,
                resultRef,
                taskId);
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELED;
    }

    private static boolean allowedTransition(TaskStatus from, TaskStatus to)
    {
        if (to == TaskStatus.CANCELED) {
            return from != TaskStatus.COMPLETED
                    && from != TaskStatus.CANCELED;
        }
        return switch (from) {
            case CREATED -> to == TaskStatus.ACTIVE;
            case ACTIVE -> to == TaskStatus.WAITING_USER
                    || to == TaskStatus.NEEDS_ATTENTION
                    || to == TaskStatus.COMPLETED;
            case WAITING_USER -> to == TaskStatus.ACTIVE
                    || to == TaskStatus.NEEDS_ATTENTION
                    || to == TaskStatus.COMPLETED;
            case NEEDS_ATTENTION -> to == TaskStatus.ACTIVE
                    || to == TaskStatus.COMPLETED;
            case COMPLETED, CANCELED -> false;
        };
    }

    private Optional<Task> taskByRequestKey(String requestKey)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_task WHERE request_key = ?",
                (result, row) -> readTask(result),
                requestKey).stream().findFirst();
    }

    private Task requireTask(String taskId)
    {
        return task(taskId).orElseThrow(() ->
                new IllegalArgumentException("Unknown Task: " + taskId));
    }

    private Operation requireOperation(String operationId)
    {
        return operation(operationId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Unknown operation: " + operationId));
    }

    private PullRequestSubject requirePullRequest(String prId)
    {
        return pullRequest(prId).orElseThrow(() ->
                        new IllegalArgumentException("Unknown PR: " + prId));
    }

    private void assertRemoteIdentityMatches(
            String identityId,
            String provider,
            String repositoryExternalId,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String publicationReceiptId)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_remote_identity
                WHERE remote_identity_id = ? AND provider = ?
                  AND repository_external_id = ? AND pr_number = ?
                  AND pr_node_id = ? AND html_url = ?
                  AND publication_receipt_id = ?
                """,
                Integer.class,
                identityId,
                provider,
                repositoryExternalId,
                prNumber,
                prNodeId,
                htmlUrl,
                publicationReceiptId);
        if (requireNonNull(matches, "remote identity count is null") != 1) {
            throw new IllegalStateException(
                    "PR already owns different remote identity evidence");
        }
    }

    private AgentSession requireSession(String sessionId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_session WHERE session_id = ?
                """,
                (result, row) -> readSession(result),
                sessionId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown agent session: " + sessionId));
    }

    private AgentRun requireRun(String runId)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_agent_run WHERE run_id = ?",
                (result, row) -> readRun(result),
                runId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown AgentRun: " + runId));
    }

    private AgentResult requireResult(String resultId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_result WHERE result_id = ?
                """,
                (result, row) -> readResult(result),
                resultId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown AgentResult: " + resultId));
    }

    private Optional<PendingWork> inbox(String inboxId)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_inbox WHERE inbox_id = ?",
                (result, row) -> readInbox(result),
                inboxId).stream().findFirst();
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        return requireNonNull(
                transactions.execute(ignored -> work.get()),
                "runtime transaction returned null");
    }

    private static Task readTask(ResultSet result)
            throws SQLException
    {
        return new Task(
                result.getString("task_id"),
                result.getString("request_key"),
                result.getString("repository_id"),
                result.getString("goal_text"),
                TaskStatus.valueOf(result.getString("status")),
                result.getLong("epoch"),
                result.getString("launch_base_sha"),
                result.getString("current_base_sha"),
                result.getString("branch_name"),
                result.getString("worktree_path"),
                result.getString("current_head_sha"),
                result.getString("task_session_id"),
                result.getString("ci_session_id"),
                result.getString("pr_id"),
                result.getString("current_lifecycle_revision_id"),
                result.getLong("pending_work_watermark"),
                result.getLong("last_reconciled_work_watermark"),
                result.getLong("reconciliation_sequence"),
                result.getString("selected_writer_operation_id"),
                result.getLong("writer_fence_sequence"));
    }

    private static Operation readOperation(ResultSet result)
            throws SQLException
    {
        long rawWatermark = result.getLong("work_watermark");
        Long watermark = result.wasNull() ? null : rawWatermark;
        return new Operation(
                result.getString("operation_id"),
                result.getString("owner_kind"),
                result.getString("owner_id"),
                result.getString("task_id"),
                OperationKind.valueOf(result.getString("kind")),
                result.getString("subject_digest"),
                result.getString("input_ref"),
                watermark,
                OperationState.valueOf(result.getString("state")),
                result.getInt("attempt"),
                result.getString("result_ref"),
                instant(result, "created_at"));
    }

    private static PullRequestSubject readPullRequest(ResultSet result)
            throws SQLException
    {
        long rawNumber = result.getLong("pr_number");
        Long number = result.wasNull() ? null : rawNumber;
        return new PullRequestSubject(
                result.getString("pr_id"),
                result.getString("task_id"),
                result.getString("repository_id"),
                result.getString("base_ref"),
                result.getString("base_sha"),
                result.getString("target_base_ref"),
                result.getString("scope_key"),
                result.getString("branch_name"),
                result.getString("created_from_head_sha"),
                result.getString("remote_identity_id"),
                result.getString("provider"),
                result.getString("repository_external_id"),
                number,
                result.getString("current_remote_head"));
    }

    private static AgentSession readSession(ResultSet result)
            throws SQLException
    {
        return new AgentSession(
                result.getString("session_id"),
                result.getString("task_id"),
                AgentRole.valueOf(result.getString("role")),
                SessionState.valueOf(result.getString("state")),
                result.getString("last_run_id"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private static AgentRun readRun(ResultSet result)
            throws SQLException
    {
        return new AgentRun(
                result.getString("run_id"),
                result.getString("operation_id"),
                result.getString("session_id"),
                AgentRole.valueOf(result.getString("role")),
                result.getString("head_sha"),
                result.getString("prompt_manifest_ref"),
                result.getString("capability_set_ref"),
                result.getString("input_ref"),
                RunState.valueOf(result.getString("state")),
                result.getString("failure_reason_code"),
                instant(result, "created_at"),
                nullableInstant(result, "started_at"),
                nullableInstant(result, "completed_at"));
    }

    private static AgentProcessAttempt readProcessAttempt(ResultSet result)
            throws SQLException
    {
        return new AgentProcessAttempt(
                result.getString("process_attempt_id"),
                result.getString("run_id"),
                result.getString("operation_id"),
                result.getLong("claim_generation"),
                result.getString("execution_id"),
                result.getString("capability_id"),
                ProcessAttemptState.valueOf(result.getString("state")),
                result.getString("process_identity"),
                instant(result, "reserved_at"),
                nullableInstant(result, "activated_at"),
                result.getString("process_metadata_ref"),
                nullableInstant(result, "stopped_at"));
    }

    private static AgentResult readResult(ResultSet result)
            throws SQLException
    {
        return new AgentResult(
                result.getString("result_id"),
                result.getString("run_id"),
                TerminalOutcome.valueOf(
                        result.getString("terminal_outcome")),
                result.getString("final_content"),
                result.getString("error_ref"),
                result.getString("process_metadata_ref"),
                instant(result, "stored_at"));
    }

    private static PendingWork readInbox(ResultSet result)
            throws SQLException
    {
        return new PendingWork(
                result.getString("inbox_id"),
                result.getString("task_id"),
                result.getString("pr_id"),
                PendingKind.valueOf(result.getString("kind")),
                result.getString("external_key"),
                result.getString("subject_head"),
                result.getString("payload_ref"),
                result.getString("agent_result_id"),
                result.getLong("work_watermark"),
                result.getString("selected_by_operation_id"),
                result.getString("handled_by_operation_id"),
                result.getString("terminal_reason"));
    }

    private static String prSubjectSql()
    {
        return """
                SELECT p.*, r.provider, r.repository_external_id, r.pr_number
                FROM flow_runtime_pr p
                LEFT JOIN flow_runtime_remote_identity r
                  ON r.remote_identity_id = p.remote_identity_id
                """;
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(result.getLong(column));
    }

    private static Instant nullableInstant(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static ProcessAttemptState nullableProcessState(String state)
    {
        return state == null ? null : ProcessAttemptState.valueOf(state);
    }

    private static String stableId(String namespace, String... components)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(namespace.getBytes(StandardCharsets.UTF_8));
            for (String component : components) {
                digest.update((byte) 0);
                digest.update(requireNonNull(component, "id component is null")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return namespace + "-"
                    + HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    public static class StaleOwnerRevisionException
            extends IllegalStateException
    {
        public StaleOwnerRevisionException(String message)
        {
            super(message);
        }
    }

    public static class StaleClaimException
            extends IllegalStateException
    {
        public StaleClaimException(String message)
        {
            super(message);
        }
    }

    public static class StaleWriterFenceException
            extends IllegalStateException
    {
        public StaleWriterFenceException(String message)
        {
            super(message);
        }
    }

    public static class MutationRejectedException
            extends IllegalStateException
    {
        public MutationRejectedException(String message)
        {
            super(message);
        }
    }
}
