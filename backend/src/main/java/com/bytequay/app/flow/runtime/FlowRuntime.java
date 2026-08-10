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
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetSource;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.InProcessStopType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessQuarantineReason;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskBaseRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskLifecycleRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.TerminatedThreadWitness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * <p>The local sidecar has one runtime instance, so short synchronized command
 * sections serialize SQLite transactions. Slow worktree inspection runs
 * outside that monitor. Database uniqueness and CAS predicates remain the
 * final guards. A multi-process deployment would need a database owner lock
 * instead.
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
    private final FlowWorktreeInspector worktreeInspector = new FlowWorktreeInspector();

    /** Unforgeable result of mechanical Git inspection awaiting owner CAS. */
    public static final class PreparedChangeSet
    {
        private final String expectedChangeSetRevisionId;
        private final AdoptionSnapshot snapshot;
        private final Inspection inspection;

        private PreparedChangeSet(
                String expectedChangeSetRevisionId,
                AdoptionSnapshot snapshot,
                Inspection inspection)
        {
            this.expectedChangeSetRevisionId = expectedChangeSetRevisionId;
            this.snapshot = snapshot;
            this.inspection = inspection;
        }
    }

    /** Private-construction proof of one exact stopped non-clean observation. */
    public static final class PreparedNonCleanState
    {
        private final String expectedChangeSetRevisionId;
        private final AdoptionSnapshot snapshot;
        private final NonCleanInspection inspection;
        private final Claim claim;
        private final WriterFence fence;
        private final String processAttemptId;
        private final String stopProofRef;

        private PreparedNonCleanState(
                String expectedChangeSetRevisionId,
                AdoptionSnapshot snapshot,
                NonCleanInspection inspection,
                Claim claim,
                WriterFence fence,
                String processAttemptId,
                String stopProofRef)
        {
            this.expectedChangeSetRevisionId = expectedChangeSetRevisionId;
            this.snapshot = snapshot;
            this.inspection = inspection;
            this.claim = claim;
            this.fence = fence;
            this.processAttemptId = processAttemptId;
            this.stopProofRef = stopProofRef;
        }
    }

    private static final class PreparedCiCleanupInspectionBlock
    {
        private final String expectedChangeSetRevisionId;
        private final AdoptionSnapshot snapshot;
        private final Claim claim;
        private final WriterFence fence;
        private final String processAttemptId;
        private final String stopProofRef;
        private final FailureCode failureCode;

        private PreparedCiCleanupInspectionBlock(
                String expectedChangeSetRevisionId,
                AdoptionSnapshot snapshot,
                Claim claim,
                WriterFence fence,
                AgentProcessAttempt stopped,
                FailureCode failureCode)
        {
            this.expectedChangeSetRevisionId = expectedChangeSetRevisionId;
            this.snapshot = snapshot;
            this.claim = claim;
            this.fence = fence;
            this.processAttemptId = stopped.processAttemptId();
            this.stopProofRef = stopped.stopProofRef();
            this.failureCode = failureCode;
        }
    }

    /** Runtime-minted mechanical result for one stopped cleanup inspection. */
    public static final class PreparedCiCleanupFinalState
    {
        private final PreparedChangeSet clean;
        private final PreparedNonCleanState nonClean;
        private final PreparedCiCleanupInspectionBlock blocked;
        private final PreparedCiCleanupInspectionBlock authority;

        private PreparedCiCleanupFinalState(
                PreparedChangeSet clean,
                PreparedNonCleanState nonClean,
                PreparedCiCleanupInspectionBlock blocked,
                PreparedCiCleanupInspectionBlock authority)
        {
            this.clean = clean;
            this.nonClean = nonClean;
            this.blocked = blocked;
            this.authority = authority;
        }

        public boolean isClean()
        {
            return clean != null;
        }

        public Optional<PreparedNonCleanState> nonClean()
        {
            return Optional.ofNullable(nonClean);
        }

        public Optional<FailureCode> failureCode()
        {
            return blocked == null
                    ? Optional.empty()
                    : Optional.of(blocked.failureCode);
        }
    }

    /** Private-construction receipt for one committed runtime cleanup handoff. */
    public static final class CleanupHandoff
    {
        private final String cleanupId;
        private final AgentResult predecessorResult;
        private final Operation successorOperation;
        private final NonCleanInspection sealedState;

        private CleanupHandoff(
                String cleanupId,
                AgentResult predecessorResult,
                Operation successorOperation,
                NonCleanInspection sealedState)
        {
            this.cleanupId = requireNonNull(cleanupId, "cleanupId is null");
            this.predecessorResult = requireNonNull(predecessorResult,
                    "predecessorResult is null");
            this.successorOperation = requireNonNull(successorOperation,
                    "successorOperation is null");
            this.sealedState = requireNonNull(sealedState,
                    "sealedState is null");
        }

        public String cleanupId()
        {
            return cleanupId;
        }

        public AgentResult predecessorResult()
        {
            return predecessorResult;
        }

        public Operation successorOperation()
        {
            return successorOperation;
        }

        public NonCleanInspection sealedState()
        {
            return sealedState;
        }
    }

    /** Private-construction proof for first cleanup writer admission. */
    public static final class PreparedCiCleanupAdmission
    {
        private final String cleanupId;
        private final String repairAttemptId;
        private final String predecessorOperationId;
        private final String predecessorRunId;
        private final String predecessorResultId;
        private final String inputChangeSetRevisionId;
        private final String inputHead;
        private final NonCleanInspection sealedState;
        private final Claim claim;
        private final long taskEpoch;
        private final String sessionId;
        private final WriterFence existingFence;
        private final AgentRun existingRun;

        private PreparedCiCleanupAdmission(
                String cleanupId,
                String repairAttemptId,
                String predecessorOperationId,
                String predecessorRunId,
                String predecessorResultId,
                String inputChangeSetRevisionId,
                String inputHead,
                NonCleanInspection sealedState,
                Claim claim,
                long taskEpoch,
                String sessionId,
                WriterFence existingFence,
                AgentRun existingRun)
        {
            this.cleanupId = cleanupId;
            this.repairAttemptId = repairAttemptId;
            this.predecessorOperationId = predecessorOperationId;
            this.predecessorRunId = predecessorRunId;
            this.predecessorResultId = predecessorResultId;
            this.inputChangeSetRevisionId = inputChangeSetRevisionId;
            this.inputHead = inputHead;
            this.sealedState = sealedState;
            this.claim = claim;
            this.taskEpoch = taskEpoch;
            this.sessionId = sessionId;
            this.existingFence = existingFence;
            this.existingRun = existingRun;
        }
    }

    /** Runtime-minted proof that cleanup admission stably cannot proceed. */
    public static final class PreparedCiCleanupBlock
    {
        private final PreparedCiCleanupAdmission subject;
        private final FailureCode failureCode;
        private final NonCleanInspection observedState;

        private PreparedCiCleanupBlock(
                PreparedCiCleanupAdmission subject,
                FailureCode failureCode,
                NonCleanInspection observedState)
        {
            this.subject = requireNonNull(subject, "subject is null");
            this.failureCode = failureCode;
            this.observedState = observedState;
        }

        public Optional<FailureCode> failureCode()
        {
            return Optional.ofNullable(failureCode);
        }

        public Optional<NonCleanInspection> observedState()
        {
            return Optional.ofNullable(observedState);
        }
    }

    /** Typed stable admission stop carrying only a runtime-minted token. */
    public static final class CiCleanupAdmissionBlockedException
            extends IllegalStateException
    {
        private final PreparedCiCleanupBlock prepared;

        private CiCleanupAdmissionBlockedException(
                String message, PreparedCiCleanupBlock prepared)
        {
            super(message);
            this.prepared = requireNonNull(prepared, "prepared is null");
        }

        public PreparedCiCleanupBlock prepared()
        {
            return prepared;
        }
    }

    /** Private-construction receipt for one committed cleanup outcome. */
    public static final class CiCleanupFinalizationReceipt
    {
        private final String cleanupId;
        private final String operationId;
        private final AgentResult result;
        private final CiFixOutcome cleanOutcome;
        private final String outputHead;
        private final String outputChangeSetRevisionId;
        private final NonCleanInspection finalState;
        private final FailureCode failureCode;
        private final boolean admissionBlocked;
        private final Instant completedAt;

        private CiCleanupFinalizationReceipt(
                String cleanupId,
                String operationId,
                AgentResult result,
                CiFixOutcome cleanOutcome,
                String outputHead,
                String outputChangeSetRevisionId,
                NonCleanInspection finalState,
                FailureCode failureCode,
                boolean admissionBlocked,
                Instant completedAt)
        {
            this.cleanupId = requireNonNull(cleanupId, "cleanupId is null");
            this.operationId = requireNonNull(operationId,
                    "operationId is null");
            this.result = result;
            this.cleanOutcome = cleanOutcome;
            this.outputHead = outputHead;
            this.outputChangeSetRevisionId = outputChangeSetRevisionId;
            this.finalState = finalState;
            this.failureCode = failureCode;
            this.admissionBlocked = admissionBlocked;
            this.completedAt = requireNonNull(completedAt,
                    "completedAt is null");
        }

        public String cleanupId()
        {
            return cleanupId;
        }

        public String operationId()
        {
            return operationId;
        }

        public Optional<AgentResult> result()
        {
            return Optional.ofNullable(result);
        }

        public Optional<CiFixOutcome> cleanOutcome()
        {
            return Optional.ofNullable(cleanOutcome);
        }

        public Optional<String> outputHead()
        {
            return Optional.ofNullable(outputHead);
        }

        public Optional<String> outputChangeSetRevisionId()
        {
            return Optional.ofNullable(outputChangeSetRevisionId);
        }

        public Optional<NonCleanInspection> finalState()
        {
            return Optional.ofNullable(finalState);
        }

        public Optional<FailureCode> failureCode()
        {
            return Optional.ofNullable(failureCode);
        }

        public boolean admissionBlocked()
        {
            return admissionBlocked;
        }

        public Instant completedAt()
        {
            return completedAt;
        }
    }

    /** Runtime-owned fence and same-session run for one cleanup operation. */
    public record CiCleanupWriterStart(WriterFence fence, AgentRun run)
    {
        public CiCleanupWriterStart
        {
            requireNonNull(fence, "fence is null");
            requireNonNull(run, "run is null");
        }
    }

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
        requireObjectId(baseSha, "baseSha");
        requireObjectId(headSha, "headSha");
        return inTransaction(() -> {
            Operation operation = requireOperation(claim.operationId());
            if (operation.kind() != OperationKind.PROVISION_TASK) {
                throw new IllegalArgumentException(
                        "claim does not own Task provisioning");
            }
            Task task = requireTask(operation.taskId());
            if (operation.state() == OperationState.SUCCEEDED) {
                assertFinalizedClaim(
                        claim, "provisioned:" + task.taskId());
                if (!Objects.equals(task.launchBaseSha(), baseSha)
                        || !Objects.equals(initialTaskHead(task.taskId()), headSha)) {
                    throw new IllegalStateException(
                            "provisioning redelivery changed the frozen subject");
                }
                TaskBaseRevision base = baseRevisionForSource(
                        operation.operationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "provisioned Task has no initial base revision"));
                if (!base.baseSha().equals(baseSha)
                        || !base.sourceOperationId().equals(operation.operationId())) {
                    throw new IllegalStateException(
                            "provisioned base revision changed after completion");
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
            String baseRevisionId = stableId(
                    "task-base-revision", task.taskId(), "1");
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_task_base_revision (
                        base_revision_id, task_id, sequence, previous_base_sha,
                        base_sha, reason_code, evidence_ref,
                        source_operation_id, recorded_at
                    ) VALUES (?, ?, 1, NULL, ?, 'INITIAL', ?, ?, ?)
                    """,
                    baseRevisionId,
                    task.taskId(),
                    baseSha,
                    "provision-operation:" + operation.operationId(),
                    operation.operationId(),
                    now.toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET launch_base_sha = ?, current_base_sha = ?,
                        current_base_revision_id = ?, current_head_sha = ?,
                        task_session_id = ?,
                        status = 'ACTIVE', current_lifecycle_revision_id = ?
                    WHERE task_id = ? AND status = 'CREATED'
                        AND current_lifecycle_revision_id = ?
                    """,
                    baseSha,
                    baseSha,
                    baseRevisionId,
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
            if (task.waitingMutationStateRef() != null) {
                throw new MutationRejectedException(
                        "Task has unresolved local mutation state");
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
            String expectedChangeSetRevisionId,
            String baseRef,
            String targetBaseRef,
            String scopeKey)
    {
        requireText(taskId, "taskId");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireText(baseRef, "baseRef");
        requireText(targetBaseRef, "targetBaseRef");
        requireText(scopeKey, "scopeKey");
        return inTransaction(() -> {
            Task task = requireTask(taskId);
            if (task.prId() != null) {
                PullRequestSubject current = requirePullRequest(task.prId());
                if (!current.createdFromChangeSetRevisionId().equals(
                        expectedChangeSetRevisionId)
                        || !current.baseRef().equals(baseRef)
                        || !current.targetBaseRef().equals(targetBaseRef)
                        || !current.scopeKey().equals(scopeKey)) {
                    throw new IllegalStateException(
                            "Task already owns a different PR subject");
                }
                return current;
            }
            ChangeSetRevision changeSet = requireChangeSetRevision(
                    expectedChangeSetRevisionId);
            if (task.status() != TaskStatus.ACTIVE
                    || !expectedChangeSetRevisionId.equals(
                            changeSet.changeSetRevisionId())
                    || !changeSet.changeSetRevisionId().equals(
                            task.currentChangeSetRevisionId())
                    || !changeSet.baseRevisionId().equals(
                            task.currentBaseRevisionId())
                    || !changeSet.headSha().equals(task.currentHeadSha())
                    || !changeSet.baseSha().equals(task.currentBaseSha())) {
                throw new IllegalStateException(
                        "PR subject is not the current active Task head");
            }
            if (!changeSet.differsFromBase()) {
                throw new IllegalStateException(
                        "An empty Task cannot materialize a PR");
            }
            String prId = stableId("pr", taskId);
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_pr (
                        pr_id, task_id, repository_id, base_ref, base_sha,
                        target_base_ref, scope_key, branch_name,
                        created_from_change_set_revision_id,
                        created_from_head_sha, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    prId,
                    taskId,
                    task.repositoryId(),
                    baseRef,
                    changeSet.baseSha(),
                    targetBaseRef,
                    scopeKey,
                    task.branchName(),
                    changeSet.changeSetRevisionId(),
                    changeSet.headSha(),
                    clock.instant().toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET pr_id = ?
                    WHERE task_id = ? AND pr_id IS NULL
                        AND current_change_set_revision_id = ?
                    """,
                    prId,
                    taskId,
                    changeSet.changeSetRevisionId());
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

    /**
     * Lets a concrete owner validate the next inbox subject before the generic
     * selector grants a writer. This is read-only and claim-generation bound.
     */
    public synchronized Optional<PendingWork> nextPendingForReconciliation(
            Claim claim)
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
            return jdbc.query(
                    """
                    SELECT * FROM flow_runtime_inbox
                    WHERE task_id = ?
                      AND work_watermark <= ?
                      AND handled_by_operation_id IS NULL
                      AND selected_by_operation_id IS NULL
                      AND terminal_reason IS NULL
                    ORDER BY CASE kind
                        WHEN 'CI_FIX_READY' THEN 1
                        WHEN 'AGENT_RESULT_READY' THEN 1
                        WHEN 'FINAL_RED' THEN 2
                        WHEN 'INITIAL_TASK' THEN 3
                    END, work_watermark
                    LIMIT 1
                    """,
                    (result, row) -> readInbox(result),
                    reconciliation.taskId(),
                    reconciliation.workWatermark()).stream().findFirst();
        });
    }

    /**
     * Marks one stale CI owner fact consumed by this exact reconciliation. It
     * cannot select, terminate, or mutate any writer operation.
     */
    public synchronized void discardPendingFinalRed(
            Claim claim, String pendingId)
    {
        requireNonNull(claim, "claim is null");
        requireText(pendingId, "pendingId");
        inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation reconciliation = requireOperation(claim.operationId());
            if (reconciliation.kind() != OperationKind.RECONCILE_TASK
                    || reconciliation.workWatermark() == null) {
                throw new IllegalArgumentException(
                        "claim does not own reconciliation");
            }
            PendingWork pending = inbox(pendingId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown pending work: " + pendingId));
            if (!pending.taskId().equals(reconciliation.taskId())
                    || pending.kind() != PendingKind.FINAL_RED
                    || pending.workWatermark() > reconciliation.workWatermark()
                    || pending.selectedByOperationId() != null
                    || pending.terminalReason() != null) {
                throw new IllegalStateException(
                        "pending FINAL_RED is outside this reconciliation");
            }
            if (reconciliation.operationId().equals(
                    pending.handledByOperationId())) {
                return Boolean.TRUE;
            }
            if (pending.handledByOperationId() != null) {
                throw new StaleOwnerRevisionException(
                        "pending FINAL_RED was handled by another operation");
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_inbox
                    SET handled_by_operation_id = ?
                    WHERE inbox_id = ?
                      AND selected_by_operation_id IS NULL
                      AND handled_by_operation_id IS NULL
                      AND terminal_reason IS NULL
                    """,
                    reconciliation.operationId(),
                    pendingId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "pending FINAL_RED changed during disposition");
            }
            return Boolean.TRUE;
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
                              AND t.waiting_mutation_state_ref IS NULL
                              AND t.selected_writer_operation_id IS NULL)
                          OR (o.kind IN ('RUN_TASK_TURN', 'RUN_CI_FIXER')
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
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
                        WHEN 'CI_FIX_READY' THEN 1
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
                    case CI_FIX_READY -> "CI_ATTEMPT";
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
            if (operation.ownerKind().equals("CI_CLEANUP")) {
                throw new MutationRejectedException(
                        "CI cleanup cannot use caller-authored worktree admission");
            }
            AgentRole requiredRole = roleForWriter(operation.kind());
            if (holderKind != requiredRole) {
                throw new IllegalArgumentException(
                        "writer holder does not match operation kind");
            }
            Task task = requireTask(operation.taskId());
            if (task.status() != TaskStatus.ACTIVE
                    || task.waitingMutationStateRef() != null
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())) {
                throw new MutationRejectedException(
                        "operation is not the selected active Task writer");
            }
            if (!snapshot.headSha().equals(task.currentHeadSha())) {
                throw new MutationRejectedException(
                        "worktree snapshot is not the current Task head");
            }
            assertWriterSubjectCurrent(operation, snapshot.headSha());

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
     * Mechanically adopts the current clean committed Task worktree head.
     * Git inspection runs outside the database transaction; the append then
     * revalidates every owner pointer and the original claim/fence.
     */
    public ChangeSetRevision adoptChangeSet(
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");

        PreparedChangeSet prepared = prepareChangeSet(
                claim,
                fence,
                programOwnedRepositoryRoot,
                expectedChangeSetRevisionId);
        return adoptPreparedChangeSet(claim, fence, prepared);
    }

    /** Inspects Git outside the final owner transaction. */
    public PreparedChangeSet prepareChangeSet(
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        AdoptionSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() -> adoptionSnapshot(
                    claim, fence, expectedChangeSetRevisionId));
            if (operation(snapshot.operationId()).orElseThrow()
                    .ownerKind().equals("CI_CLEANUP")) {
                throw new MutationRejectedException(
                        "CI cleanup requires its stopped final-state boundary");
            }
        }
        Inspection inspection = worktreeInspector.inspect(
                programOwnedRepositoryRoot,
                snapshot.worktree(),
                snapshot.branchName(),
                snapshot.baseSha(),
                snapshot.predecessorHeadSha());
        return new PreparedChangeSet(
                expectedChangeSetRevisionId, snapshot, inspection);
    }

    /** Inspects a stopped non-clean writer outside the final owner transaction. */
    public PreparedNonCleanState prepareNonCleanState(
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        AdoptionSnapshot snapshot;
        AgentProcessAttempt stopped;
        synchronized (this) {
            PreparedNonCleanSnapshot prepared = inTransaction(() -> {
                AdoptionSnapshot current = adoptionSnapshot(
                        claim, fence, expectedChangeSetRevisionId);
                if (!requireOperation(current.operationId())
                        .ownerKind().equals("CI_ROUND")) {
                    throw new MutationRejectedException(
                            "only a CI round may hand off non-clean work");
                }
                if (current.redelivery() != null) {
                    throw new StaleOwnerRevisionException(
                            "adopted change set cannot become cleanup input");
                }
                AgentProcessAttempt process = requireStoppedProcessAttempt(
                        current.sourceRunId(), claim.generation());
                return new PreparedNonCleanSnapshot(current, process);
            });
            snapshot = prepared.snapshot();
            stopped = prepared.stopped();
        }
        NonCleanInspection inspection = worktreeInspector.inspectNonClean(
                programOwnedRepositoryRoot,
                snapshot.worktree(),
                snapshot.branchName(),
                snapshot.baseSha(),
                snapshot.predecessorHeadSha());
        return new PreparedNonCleanState(
                expectedChangeSetRevisionId,
                snapshot,
                inspection,
                claim,
                fence,
                stopped.processAttemptId(),
                stopped.stopProofRef());
    }

    /** Mechanically classifies one stopped cleanup final worktree state. */
    public PreparedCiCleanupFinalState prepareCiCleanupFinalState(
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId)
    {
        PreparedCiCleanupInspectionBlock authority =
                prepareCiCleanupInspectionBlock(
                        claim,
                        fence,
                        expectedChangeSetRevisionId,
                        null);
        AdoptionSnapshot snapshot = authority.snapshot;
        try {
            Inspection inspection = worktreeInspector.inspect(
                    programOwnedRepositoryRoot,
                    snapshot.worktree(),
                    snapshot.branchName(),
                    snapshot.baseSha(),
                    snapshot.predecessorHeadSha());
            return new PreparedCiCleanupFinalState(
                    new PreparedChangeSet(
                            expectedChangeSetRevisionId,
                            snapshot,
                            inspection),
                    null,
                    null,
                    authority);
        }
        catch (InspectionFailure failure) {
            if (transientInspectionFailure(failure.code())) {
                throw failure;
            }
            if (failure.code() == FailureCode.DIRTY
                    || failure.code()
                        == FailureCode.GIT_OPERATION_IN_PROGRESS) {
                try {
                    NonCleanInspection inspection =
                            worktreeInspector.inspectNonClean(
                                    programOwnedRepositoryRoot,
                                    snapshot.worktree(),
                                    snapshot.branchName(),
                                    snapshot.baseSha(),
                                    snapshot.predecessorHeadSha());
                    return new PreparedCiCleanupFinalState(
                            null,
                            new PreparedNonCleanState(
                                    expectedChangeSetRevisionId,
                                    snapshot,
                                    inspection,
                                    claim,
                                    fence,
                                    authority.processAttemptId,
                                    authority.stopProofRef),
                            null,
                            authority);
                }
                catch (InspectionFailure nonCleanFailure) {
                    if (transientInspectionFailure(nonCleanFailure.code())) {
                        throw nonCleanFailure;
                    }
                    failure = nonCleanFailure;
                }
            }
            PreparedCiCleanupInspectionBlock blocked =
                    new PreparedCiCleanupInspectionBlock(
                            expectedChangeSetRevisionId,
                            snapshot,
                            claim,
                            fence,
                            requireStoppedProcessAttemptOutsideTransaction(
                                    authority),
                            failure.code());
            return new PreparedCiCleanupFinalState(
                    null,
                    null,
                    blocked,
                    authority);
        }
    }

    private AgentProcessAttempt requireStoppedProcessAttemptOutsideTransaction(
            PreparedCiCleanupInspectionBlock authority)
    {
        synchronized (this) {
            return inTransaction(() -> {
                AgentProcessAttempt stopped = requireStoppedProcessAttempt(
                        authority.snapshot.sourceRunId(),
                        authority.claim.generation());
                if (!stopped.processAttemptId().equals(
                                authority.processAttemptId)
                        || !stopped.stopProofRef().equals(
                                authority.stopProofRef)) {
                    throw new StaleOwnerRevisionException(
                            "cleanup stop proof changed during inspection");
                }
                return stopped;
            });
        }
    }

    private PreparedCiCleanupInspectionBlock prepareCiCleanupInspectionBlock(
            Claim claim,
            WriterFence fence,
            String expectedChangeSetRevisionId,
            FailureCode failureCode)
    {
        synchronized (this) {
            return inTransaction(() -> {
                AdoptionSnapshot snapshot = adoptionSnapshot(
                        claim, fence, expectedChangeSetRevisionId);
                if (snapshot.redelivery() != null) {
                    throw new StaleOwnerRevisionException(
                            "cleanup inspection blocker follows adoption");
                }
                AgentProcessAttempt stopped = requireStoppedProcessAttempt(
                        snapshot.sourceRunId(), claim.generation());
                return new PreparedCiCleanupInspectionBlock(
                        expectedChangeSetRevisionId,
                        snapshot,
                        claim,
                        fence,
                        stopped,
                        failureCode);
            });
        }
    }

    /** Re-inspects the exact sealed state before the first cleanup body. */
    public PreparedCiCleanupAdmission prepareCiCleanupAdmission(
            Claim claim,
            Path programOwnedRepositoryRoot,
            String cleanupId,
            String repairAttemptId,
            String predecessorOperationId,
            String predecessorRunId,
            String predecessorResultId,
            String inputChangeSetRevisionId,
            String inputHead,
            NonCleanInspection sealedState)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireText(cleanupId, "cleanupId");
        requireText(repairAttemptId, "repairAttemptId");
        requireText(predecessorOperationId, "predecessorOperationId");
        requireText(predecessorRunId, "predecessorRunId");
        requireText(predecessorResultId, "predecessorResultId");
        requireText(inputChangeSetRevisionId,
                "inputChangeSetRevisionId");
        requireText(inputHead, "inputHead");
        requireNonNull(sealedState, "sealedState is null");
        CiCleanupAdmissionSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() -> cleanupAdmissionSnapshot(
                    claim,
                    cleanupId,
                    repairAttemptId,
                    predecessorOperationId,
                    predecessorRunId,
                    predecessorResultId,
                    inputChangeSetRevisionId,
                    inputHead,
                    sealedState));
        }
        if (snapshot.run() != null && snapshot.fence() != null) {
            return new PreparedCiCleanupAdmission(
                    cleanupId,
                    repairAttemptId,
                    predecessorOperationId,
                    predecessorRunId,
                    predecessorResultId,
                    inputChangeSetRevisionId,
                    inputHead,
                    sealedState,
                    claim,
                    snapshot.task().epoch(),
                    snapshot.session().sessionId(),
                    snapshot.fence(),
                    snapshot.run());
        }
        PreparedCiCleanupAdmission subject = new PreparedCiCleanupAdmission(
                cleanupId,
                repairAttemptId,
                predecessorOperationId,
                predecessorRunId,
                predecessorResultId,
                inputChangeSetRevisionId,
                inputHead,
                sealedState,
                claim,
                snapshot.task().epoch(),
                snapshot.session().sessionId(),
                snapshot.fence(),
                snapshot.run());
        NonCleanInspection inspected;
        try {
            inspected = worktreeInspector.inspectNonClean(
                    programOwnedRepositoryRoot,
                    Path.of(snapshot.task().worktreePath()),
                    snapshot.task().branchName(),
                    snapshot.base().baseSha(),
                    inputHead);
        }
        catch (InspectionFailure failure) {
            if (transientInspectionFailure(failure.code())) {
                throw failure;
            }
            throw new CiCleanupAdmissionBlockedException(
                    "cleanup worktree cannot be safely admitted",
                    new PreparedCiCleanupBlock(
                            subject, failure.code(), null));
        }
        if (!inspected.equals(sealedState)) {
            throw new CiCleanupAdmissionBlockedException(
                    "cleanup worktree no longer matches its immutable seal",
                    new PreparedCiCleanupBlock(subject, null, inspected));
        }
        return new PreparedCiCleanupAdmission(
                cleanupId,
                repairAttemptId,
                predecessorOperationId,
                predecessorRunId,
                predecessorResultId,
                inputChangeSetRevisionId,
                inputHead,
                inspected,
                claim,
                snapshot.task().epoch(),
                snapshot.session().sessionId(),
                snapshot.fence(),
                snapshot.run());
    }

    private static boolean transientInspectionFailure(FailureCode code)
    {
        return code == FailureCode.MOVED_DURING_INSPECTION
                || code == FailureCode.TIMEOUT
                || code == FailureCode.INTERRUPTED;
    }

    /** Appends only an inspection token minted by this runtime. */
    public synchronized ChangeSetRevision adoptPreparedChangeSet(
            Claim claim, WriterFence fence, PreparedChangeSet prepared)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(prepared, "prepared is null");
        if (requireOperation(prepared.snapshot.operationId())
                .ownerKind().equals("CI_CLEANUP")) {
            throw new MutationRejectedException(
                    "CI cleanup cannot use generic prepared adoption");
        }
        return inTransaction(() -> appendInspectedChangeSet(
                claim,
                fence,
                prepared.expectedChangeSetRevisionId,
                prepared.snapshot,
                prepared.inspection));
    }

    private ChangeSetRevision adoptPreparedCiCleanupChangeSet(
            Claim claim,
            WriterFence fence,
            PreparedCiCleanupFinalState prepared)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(prepared, "prepared is null");
        if (prepared.clean == null || prepared.authority == null) {
            throw new IllegalArgumentException(
                    "cleanup final state is not clean");
        }
        return inTransaction(() -> {
            PreparedCiCleanupInspectionBlock authority = prepared.authority;
            AgentProcessAttempt stopped = requireStoppedProcessAttempt(
                    authority.snapshot.sourceRunId(), claim.generation());
            if (!sameClaimAuthority(authority.claim, claim)
                    || !sameFenceAuthority(authority.fence, fence)
                    || !stopped.processAttemptId().equals(
                            authority.processAttemptId)
                    || !stopped.stopProofRef().equals(authority.stopProofRef)) {
                throw new StaleOwnerRevisionException(
                        "cleanup stopped inspection authority changed");
            }
            return appendInspectedChangeSet(
                    claim,
                    fence,
                    prepared.clean.expectedChangeSetRevisionId,
                    prepared.clean.snapshot,
                    prepared.clean.inspection);
        });
    }

    private ChangeSetRevision appendInspectedChangeSet(
            Claim claim,
            WriterFence fence,
            String expectedChangeSetRevisionId,
            AdoptionSnapshot snapshot,
            Inspection inspection)
    {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(snapshot.taskId());
            AgentRun run = requireRun(snapshot.sourceRunId());
            if (!operation.operationId().equals(snapshot.operationId())
                    || !operation.operationId().equals(run.operationId())
                    || operation.kind() != snapshot.operationKind()
                    || run.state() != RunState.RUNNING
                    || run.role() != (snapshot.source() == ChangeSetSource.TASK_AGENT
                            ? AgentRole.TASK_AGENT
                            : AgentRole.CI_FIXER)
                    || task.epoch() != snapshot.taskEpoch()
                    || task.status() != TaskStatus.ACTIVE
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())
                    || !Objects.equals(task.currentBaseRevisionId(),
                            snapshot.baseRevisionId())
                    || !Objects.equals(task.currentBaseSha(), snapshot.baseSha())) {
                throw new StaleOwnerRevisionException(
                        "Task adoption subject changed during Git inspection");
            }
            TaskBaseRevision base = currentBaseRevision(task.taskId())
                    .orElseThrow(() -> new StaleOwnerRevisionException(
                            "Task lost its current base revision"));
            if (!base.baseRevisionId().equals(snapshot.baseRevisionId())
                    || !base.baseSha().equals(snapshot.baseSha())) {
                throw new StaleOwnerRevisionException(
                        "Task base revision changed during Git inspection");
            }

            Optional<ChangeSetRevision> duplicate = changeSetForSource(
                    task.taskId(), operation.operationId(), inspection.headSha());
            if (duplicate.isPresent()) {
                ChangeSetRevision existing = duplicate.get();
                if (exactAdoptionRedelivery(
                        existing,
                        task,
                        snapshot,
                        inspection,
                        expectedChangeSetRevisionId)) {
                    return existing;
                }
                throw new StaleOwnerRevisionException(
                        "change-set adoption was already superseded");
            }
            if (snapshot.redelivery() != null
                    || !Objects.equals(task.currentHeadSha(),
                            snapshot.predecessorHeadSha())
                    || !Objects.equals(task.currentChangeSetRevisionId(),
                            expectedChangeSetRevisionId)) {
                throw new StaleOwnerRevisionException(
                        "Task adoption subject changed during Git inspection");
            }

            ChangeSetRevision previous = expectedChangeSetRevisionId == null
                    ? null
                    : requireChangeSetRevision(expectedChangeSetRevisionId);
            long sequence = previous == null ? 1 : previous.sequence() + 1;
            String revisionId = stableId(
                    "change-set-revision",
                    task.taskId(),
                    Long.toString(sequence),
                    operation.operationId(),
                    inspection.headSha());
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_change_set_revision (
                        change_set_revision_id, task_id, sequence,
                        previous_change_set_revision_id, previous_head_sha,
                        head_sha, base_revision_id, base_sha,
                        head_tree_digest, diff_digest, differs_from_base,
                        source, source_run_id, source_operation_id, adopted_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    revisionId,
                    task.taskId(),
                    sequence,
                    expectedChangeSetRevisionId,
                    snapshot.predecessorHeadSha(),
                    inspection.headSha(),
                    base.baseRevisionId(),
                    base.baseSha(),
                    inspection.headTreeDigest(),
                    inspection.baseToHeadDiffDigest(),
                    inspection.differsFromBase() ? 1 : 0,
                    snapshot.source().name(),
                    run.runId(),
                    operation.operationId(),
                    now.toEpochMilli());
            int advanced = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET current_head_sha = ?,
                        current_change_set_revision_id = ?
                    WHERE task_id = ? AND epoch = ? AND status = 'ACTIVE'
                      AND selected_writer_operation_id = ?
                      AND current_base_revision_id = ? AND current_base_sha = ?
                      AND current_head_sha = ?
                      AND ((? IS NULL AND current_change_set_revision_id IS NULL)
                        OR current_change_set_revision_id = ?)
                    """,
                    inspection.headSha(),
                    revisionId,
                    task.taskId(),
                    snapshot.taskEpoch(),
                    operation.operationId(),
                    base.baseRevisionId(),
                    base.baseSha(),
                    snapshot.predecessorHeadSha(),
                    expectedChangeSetRevisionId,
                    expectedChangeSetRevisionId);
            if (advanced != 1) {
                throw new StaleOwnerRevisionException(
                        "Task changed while adopting inspected Git state");
            }
            return requireChangeSetRevision(revisionId);
    }

    /**
     * Lazily creates the Task's persistent CI session (or resumes its existing
     * session) and creates one operation-bound run.
     */
    public synchronized CiCleanupWriterStart startCiCleanupWriter(
            Claim claim,
            PreparedCiCleanupAdmission prepared,
            Duration leaseTtl,
            String promptManifestRef,
            String capabilitySetRef)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(prepared, "prepared is null");
        requirePositive(leaseTtl, "leaseTtl");
        requireText(promptManifestRef, "promptManifestRef");
        requireText(capabilitySetRef, "capabilitySetRef");
        return inTransaction(() -> {
            if (!sameClaimAuthority(prepared.claim, claim)) {
                throw new StaleClaimException(
                        "cleanup claim changed after sealed-state inspection");
            }
            CiCleanupAdmissionSnapshot current = cleanupAdmissionSnapshot(
                    claim,
                    prepared.cleanupId,
                    prepared.repairAttemptId,
                    prepared.predecessorOperationId,
                    prepared.predecessorRunId,
                    prepared.predecessorResultId,
                    prepared.inputChangeSetRevisionId,
                    prepared.inputHead,
                    prepared.sealedState);
            if (current.task().epoch() != prepared.taskEpoch
                    || !current.session().sessionId().equals(
                            prepared.sessionId)) {
                throw new StaleOwnerRevisionException(
                        "cleanup Task/session changed after inspection");
            }
            if (current.run() != null && current.fence() != null) {
                AgentRun run = current.run();
                WriterFence fence = current.fence();
                if ((prepared.existingRun != null
                            && !prepared.existingRun.runId().equals(run.runId()))
                        || (prepared.existingFence != null
                            && !sameFenceAuthority(
                                    prepared.existingFence, fence))
                        || !run.promptManifestRef().equals(promptManifestRef)
                        || !run.capabilitySetRef().equals(capabilitySetRef)) {
                    throw new StaleOwnerRevisionException(
                            "cleanup writer redelivery changed identity");
                }
                return new CiCleanupWriterStart(fence, run);
            }
            if (current.run() == null
                    && (prepared.existingRun != null
                            || prepared.existingFence != null)) {
                throw new StaleOwnerRevisionException(
                        "cleanup writer disappeared after redelivery");
            }
            if (current.run() != null
                    && (prepared.existingRun == null
                            || !prepared.existingRun.runId().equals(
                                    current.run().runId())
                            || prepared.existingFence != null
                            || !current.run().promptManifestRef().equals(
                                    promptManifestRef)
                            || !current.run().capabilitySetRef().equals(
                                    capabilitySetRef))) {
                throw new StaleOwnerRevisionException(
                        "recovered cleanup run changed identity");
            }
            Task task = current.task();
            Operation operation = requireOperation(claim.operationId());
            long token = task.writerFenceSequence() + 1;
            int advanced = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET writer_fence_sequence = ?
                    WHERE task_id = ? AND writer_fence_sequence = ?
                      AND epoch = ? AND status = 'ACTIVE'
                      AND selected_writer_operation_id = ?
                      AND current_head_sha = ?
                      AND current_change_set_revision_id = ?
                    """,
                    token,
                    task.taskId(),
                    task.writerFenceSequence(),
                    task.epoch(),
                    operation.operationId(),
                    prepared.inputHead,
                    prepared.inputChangeSetRevisionId);
            if (advanced != 1) {
                throw new StaleOwnerRevisionException(
                        "cleanup Task changed during writer admission");
            }
            Instant expiresAt = earlier(
                    clock.instant().plus(leaseTtl),
                    currentClaimExpiry(claim));
            String evidenceRef = ciCleanupEvidenceRef(
                    prepared.cleanupId,
                    prepared.inputChangeSetRevisionId);
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_writer_lease (
                        task_id, operation_id, task_epoch, holder_kind,
                        fencing_token, claim_generation, claim_token_digest,
                        head_sha, tree_digest, snapshot_evidence_ref, expires_at
                    ) VALUES (?, ?, ?, 'CI_FIXER', ?, ?, ?, ?, ?, ?, ?)
                    """,
                    task.taskId(),
                    operation.operationId(),
                    task.epoch(),
                    token,
                    claim.generation(),
                    claimTokenDigest(claim),
                    prepared.inputHead,
                    prepared.sealedState.stateDigest(),
                    evidenceRef,
                    expiresAt.toEpochMilli());
            WriterFence fence = new WriterFence(
                    task.taskId(),
                    operation.operationId(),
                    task.epoch(),
                    AgentRole.CI_FIXER,
                    token,
                    claim.generation(),
                    claimTokenDigest(claim),
                    prepared.inputHead,
                    prepared.sealedState.stateDigest(),
                    evidenceRef,
                    expiresAt);
            if (current.run() != null) {
                return new CiCleanupWriterStart(fence, current.run());
            }
            String runId = stableId("agent-run", operation.operationId());
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_run (
                        run_id, operation_id, session_id, role, head_sha,
                        prompt_manifest_ref, capability_set_ref, input_ref,
                        state, created_at
                    ) VALUES (?, ?, ?, 'CI_FIXER', ?, ?, ?, ?, 'QUEUED', ?)
                    """,
                    runId,
                    operation.operationId(),
                    current.session().sessionId(),
                    prepared.inputHead,
                    promptManifestRef,
                    capabilitySetRef,
                    operation.inputRef(),
                    now.toEpochMilli());
            int sessionReserved = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = 'RUNNING', last_run_id = ?, updated_at = ?
                    WHERE session_id = ? AND state = 'IDLE'
                    """,
                    runId,
                    now.toEpochMilli(),
                    current.session().sessionId());
            if (sessionReserved != 1) {
                throw new MutationRejectedException(
                        "persistent CI session changed during cleanup start");
            }
            return new CiCleanupWriterStart(fence, requireRun(runId));
        });
    }

    /** Settles a stable pre-body cleanup admission blocker fail closed. */
    public synchronized CiCleanupFinalizationReceipt blockCiCleanupAdmission(
            Claim claim, PreparedCiCleanupBlock prepared)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            PreparedCiCleanupAdmission subject = prepared.subject;
            if (!sameClaimAuthority(subject.claim, claim)) {
                throw new StaleClaimException(
                        "cleanup admission claim changed before blocking");
            }
            CiCleanupAdmissionSnapshot current = cleanupAdmissionSnapshot(
                    claim,
                    subject.cleanupId,
                    subject.repairAttemptId,
                    subject.predecessorOperationId,
                    subject.predecessorRunId,
                    subject.predecessorResultId,
                    subject.inputChangeSetRevisionId,
                    subject.inputHead,
                    subject.sealedState);
            if (current.task().epoch() != subject.taskEpoch
                    || !current.session().sessionId().equals(
                            subject.sessionId)
                    || current.fence() != null) {
                throw new StaleOwnerRevisionException(
                        "cleanup admission authority changed before blocking");
            }
            Instant now = clock.instant();
            String blockedRef = "CI_CLEANUP_ADMISSION_BLOCKED:"
                    + subject.cleanupId;
            AgentResult canceledResult = null;
            if (current.run() != null) {
                AgentRun queued = current.run();
                if (queued.state() != RunState.QUEUED
                        || hasProcessAttemptForRun(queued.runId())) {
                    throw new StaleOwnerRevisionException(
                            "admission-blocked cleanup run was launched");
                }
                String resultId = stableId("agent-result", queued.runId());
                String stopProofRef = stableId(
                        "never-launched-stop",
                        subject.cleanupId,
                        queued.runId());
                int resultStored = jdbc.update(
                        """
                        INSERT INTO flow_runtime_agent_result (
                            result_id, run_id, terminal_outcome, final_content,
                            error_ref, stop_proof_ref, stored_at
                        ) VALUES (?, ?, 'CANCELED', NULL, ?, ?, ?)
                        """,
                        resultId,
                        queued.runId(),
                        blockedRef,
                        stopProofRef,
                        now.toEpochMilli());
                int runCanceled = jdbc.update(
                        """
                        UPDATE flow_runtime_agent_run
                        SET state = 'CANCELED', failure_reason_code = ?,
                            completed_at = ?
                        WHERE run_id = ? AND state = 'QUEUED'
                        """,
                        blockedRef,
                        now.toEpochMilli(),
                        queued.runId());
                int sessionReleased = jdbc.update(
                        """
                        UPDATE flow_runtime_agent_session
                        SET state = 'IDLE', updated_at = ?
                        WHERE session_id = ? AND state = 'RUNNING'
                          AND last_run_id = ?
                        """,
                        now.toEpochMilli(),
                        current.session().sessionId(),
                        queued.runId());
                if (resultStored != 1 || runCanceled != 1
                        || sessionReleased != 1) {
                    throw new StaleOwnerRevisionException(
                            "never-launched cleanup run changed before blocking");
                }
                canceledResult = requireResult(resultId);
            }
            String evidenceRef = cleanupAttentionRef(subject.cleanupId);
            TaskLifecycleRevision attention = appendLifecycle(
                    current.task(),
                    TaskStatus.NEEDS_ATTENTION,
                    "CI_CLEANUP_ADMISSION_BLOCKED",
                    evidenceRef,
                    claim.operationId(),
                    now);
            int taskUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET status = 'NEEDS_ATTENTION',
                        current_lifecycle_revision_id = ?,
                        selected_writer_operation_id = NULL,
                        waiting_mutation_state_ref = ?
                    WHERE task_id = ? AND epoch = ? AND status = 'ACTIVE'
                      AND selected_writer_operation_id = ?
                      AND waiting_mutation_state_ref IS NULL
                      AND current_head_sha = ?
                      AND current_change_set_revision_id = ?
                    """,
                    attention.lifecycleRevisionId(),
                    evidenceRef,
                    current.task().taskId(),
                    current.task().epoch(),
                    claim.operationId(),
                    subject.inputHead,
                    subject.inputChangeSetRevisionId);
            if (taskUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "cleanup admission lost its Task owner");
            }
            settleDispatch(
                    claim.operationId(),
                    OperationState.FAILED,
                    canceledResult == null
                            ? blockedRef
                            : canceledResult.resultId());
            return new CiCleanupFinalizationReceipt(
                    subject.cleanupId,
                    claim.operationId(),
                    canceledResult,
                    null,
                    null,
                    null,
                    prepared.observedState,
                    prepared.failureCode,
                    true,
                    Instant.ofEpochMilli(now.toEpochMilli()));
        });
    }

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
            if (operation.ownerKind().equals("CI_CLEANUP")) {
                throw new MutationRejectedException(
                        "CI cleanup requires specialized sealed-state admission");
            }
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

    /** Reserves one dormant in-process capability for this claim generation. */
    synchronized AgentProcessAttempt reserveInProcessWriterAttempt(
            String runId, Claim claim, WriterFence fence)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Operation operation = requireOperation(claim.operationId());
            if (!Objects.equals(claim.taskId(), operation.taskId())
                    || claim.kind() != operation.kind()
                    || !Objects.equals(claim.taskId(), fence.taskId())) {
                throw new StaleClaimException(
                        "writer claim metadata does not match its operation");
            }
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
                        || attempt.claimGeneration() != claim.generation()
                        || !attempt.claimTokenDigest()
                                .equals(claimTokenDigest(claim))) {
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
                        claim_generation, claim_token_digest, execution_id,
                        capability_id, state, reserved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?)
                    """,
                    attemptId,
                    runId,
                    claim.operationId(),
                    claim.generation(),
                    claimTokenDigest(claim),
                    stableId("execution", runId,
                            Long.toString(claim.generation())),
                    stableId("capability", runId,
                            Long.toString(claim.generation())),
                    now.toEpochMilli());
            return requireProcessAttempt(attemptId);
        });
    }

    /**
     * Records a dormant Java thread before the logical run can become RUNNING.
     * The supervisor starts the thread only after this transaction commits.
     */
    synchronized AgentRun activateInProcessWriterAttempt(
            String processAttemptId,
            Claim claim,
            WriterFence fence,
            long jvmPid,
            Instant jvmStartedAt,
            long threadId,
            String threadName)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        if (jvmPid <= 0) {
            throw new IllegalArgumentException("jvmPid must be positive");
        }
        requireNonNull(jvmStartedAt, "jvmStartedAt is null");
        if (threadId <= 0) {
            throw new IllegalArgumentException("threadId must be positive");
        }
        requireText(threadName, "threadName");
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
                if (!Long.valueOf(jvmPid).equals(attempt.jvmPid())
                        || !jvmStartedAt.equals(attempt.jvmStartedAt())
                        || !Long.valueOf(threadId).equals(attempt.threadId())
                        || !threadName.equals(attempt.threadName())
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
                    SET state = 'ACTIVATED', jvm_pid = ?, jvm_started_at = ?,
                        thread_id = ?, thread_name = ?, activated_at = ?
                    WHERE process_attempt_id = ? AND state = 'RESERVED'
                    """,
                    jvmPid,
                    jvmStartedAt.toEpochMilli(),
                    threadId,
                    threadName,
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

    /** Authorizes one tool call for the exact live in-process capability. */
    synchronized void assertInProcessWriterToolCapability(
            String runId,
            Claim claim,
            WriterFence fence,
            String capabilityId)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(capabilityId, "capabilityId");
        inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Integer matches = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_agent_process_attempt a
                    JOIN flow_runtime_agent_run r ON r.run_id = a.run_id
                    WHERE a.run_id = ? AND a.operation_id = ?
                      AND a.claim_generation = ?
                      AND a.claim_token_digest = ?
                      AND a.capability_id = ? AND a.state = 'ACTIVATED'
                      AND a.capability_revoked_at IS NULL
                      AND a.quarantine_reason IS NULL
                      AND r.state = 'RUNNING'
                    """,
                    Integer.class,
                    runId,
                    claim.operationId(),
                    claim.generation(),
                    claimTokenDigest(claim),
                    capabilityId);
            if (requireNonNull(matches, "capability count is null") != 1) {
                throw new StaleCapabilityException(
                        "in-process tool capability is stale or revoked");
            }
            return Boolean.TRUE;
        });
    }

    /** Revokes tools without claiming that the Java execution has stopped. */
    synchronized AgentProcessAttempt revokeInProcessWriterCapability(
            String processAttemptId, Claim claim, WriterFence fence)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        return inTransaction(() -> {
            assertTerminationAuthority(processAttemptId, claim, fence);
            AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
            if (attempt.capabilityRevokedAt() != null) {
                return attempt;
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET capability_revoked_at = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NULL
                    """,
                    clock.instant().toEpochMilli(),
                    processAttemptId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "in-process capability changed during revocation");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    /**
     * Stores program-owned proof after the concrete supervisor joined the
     * exact activated Java thread. This method never stops an execution.
     */
    synchronized AgentProcessAttempt recordInProcessWriterStopped(
            String processAttemptId,
            Claim claim,
            WriterFence fence,
            TerminatedThreadWitness witness,
            InProcessStopType stopType)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(witness, "witness is null");
        requireNonNull(stopType, "stopType is null");
        return inTransaction(() -> {
            assertTerminationAuthority(processAttemptId, claim, fence);
            AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
            assertInProcessIdentity(attempt, witness);
            if (attempt.state() == ProcessAttemptState.STOPPED) {
                if (attempt.stopType() != stopType) {
                    throw new IllegalStateException(
                            "in-process stop redelivery changed type");
                }
                return attempt;
            }
            if (attempt.state() != ProcessAttemptState.ACTIVATED
                    || attempt.capabilityRevokedAt() == null
                    || attempt.quarantineReason() != null) {
                throw new IllegalStateException(
                        "in-process execution is not safely stoppable");
            }
            Instant now = clock.instant();
            String proofRef = stableId(
                    "in-process-stop",
                    attempt.executionId(),
                    stopType.name(),
                    Long.toString(witness.jvmPid()),
                    Long.toString(witness.jvmStartedAt().toEpochMilli()),
                    Long.toString(witness.thread().threadId()),
                    Long.toString(attempt.capabilityRevokedAt()
                            .toEpochMilli()));
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET state = 'STOPPED', stop_type = ?,
                        stop_proof_ref = ?, stopped_at = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NOT NULL
                      AND quarantine_reason IS NULL
                    """,
                    stopType.name(),
                    proofRef,
                    now.toEpochMilli(),
                    processAttemptId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "in-process stop proof changed concurrently");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    /** Quarantines an activated execution that ignored bounded cancellation. */
    synchronized AgentProcessAttempt quarantineInProcessWriterAttempt(
            String processAttemptId,
            Claim claim,
            WriterFence fence,
            ProcessQuarantineReason reason)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(reason, "reason is null");
        return inTransaction(() -> {
            assertTerminationAuthority(processAttemptId, claim, fence);
            AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
            if (attempt.quarantineReason() != null) {
                if (attempt.quarantineReason() != reason) {
                    throw new IllegalStateException(
                            "in-process quarantine redelivery changed reason");
                }
                return attempt;
            }
            if (attempt.state() != ProcessAttemptState.ACTIVATED
                    || attempt.capabilityRevokedAt() == null) {
                throw new IllegalStateException(
                        "only a revoked active execution can be quarantined");
            }
            Instant now = clock.instant();
            int quarantined = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET quarantine_reason = ?, quarantined_at = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NOT NULL
                      AND quarantine_reason IS NULL
                    """,
                    reason.name(),
                    now.toEpochMilli(),
                    processAttemptId);
            if (quarantined != 1) {
                throw new StaleOwnerRevisionException(
                        "in-process quarantine changed concurrently");
            }
            Task task = requireTask(fence.taskId());
            if (task.status() == TaskStatus.NEEDS_ATTENTION) {
                return requireProcessAttempt(processAttemptId);
            }
            if (task.status() != TaskStatus.ACTIVE) {
                throw new StaleOwnerRevisionException(
                        "writer quarantine cannot change this Task state");
            }
            TaskLifecycleRevision attention = appendLifecycle(
                    task,
                    TaskStatus.NEEDS_ATTENTION,
                    reason.name(),
                    "process-attempt:" + processAttemptId,
                    claim.operationId(),
                    now);
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
                    claim.operationId(),
                    task.currentLifecycleRevisionId());
            if (taskUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "in-process quarantine lost its Task owner revision");
            }
            return requireProcessAttempt(processAttemptId);
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
            String errorRef)
    {
        return finishWriterAgentRun(
                runId,
                claim,
                fence,
                terminalOutcome,
                finalContent,
                errorRef,
                null,
                null,
                null,
                null,
                null);
    }

    /** Proves the exact claimed writer thread stopped before domain inspection. */
    public synchronized void assertWriterRunStopped(String runId, Claim claim)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            AgentRun run = requireRun(runId);
            if (!run.operationId().equals(claim.operationId())
                    || run.state() != RunState.RUNNING) {
                throw new StaleClaimException(
                        "writer run is not active under the claim");
            }
            requireStoppedProcessAttempt(runId, claim.generation());
            return Boolean.TRUE;
        });
    }

    /**
     * Atomically settles one stopped dirty CI run and reserves its sole direct
     * cleanup successor. The successor is not claimed and owns no run or lease.
     */
    public synchronized CleanupHandoff handoffStoppedCiRunToCleanup(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String repairAttemptId,
            PreparedNonCleanState prepared)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        requireText(repairAttemptId, "repairAttemptId");
        requireNonNull(prepared, "prepared is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        return inTransaction(() -> {
            if (resultForRun(runId).isPresent()) {
                throw new IllegalStateException(
                        "cleanup handoff is already finalized");
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            AdoptionSnapshot snapshot = prepared.snapshot;
            AgentRun run = requireRun(runId);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(fence.taskId());
            if (!operation.ownerKind().equals("CI_ROUND")) {
                throw new MutationRejectedException(
                        "only a CI round may reserve a cleanup successor");
            }
            TaskBaseRevision base = currentBaseRevision(task.taskId())
                    .orElseThrow(() -> new StaleOwnerRevisionException(
                            "cleanup Task lost its base revision"));
            if (!sameClaimAuthority(prepared.claim, claim)
                    || !sameFenceAuthority(prepared.fence, fence)
                    || !snapshot.taskId().equals(task.taskId())
                    || snapshot.taskEpoch() != task.epoch()
                    || !snapshot.operationId().equals(operation.operationId())
                    || snapshot.operationKind() != OperationKind.RUN_CI_FIXER
                    || snapshot.source() != ChangeSetSource.CI_FIXER
                    || !snapshot.sourceRunId().equals(runId)
                    || snapshot.redelivery() != null
                    || !snapshot.worktree().equals(Path.of(task.worktreePath()))
                    || !snapshot.branchName().equals(task.branchName())
                    || !snapshot.baseRevisionId().equals(
                            task.currentBaseRevisionId())
                    || !snapshot.baseRevisionId().equals(base.baseRevisionId())
                    || !snapshot.baseSha().equals(task.currentBaseSha())
                    || !snapshot.baseSha().equals(base.baseSha())
                    || !snapshot.predecessorHeadSha().equals(
                            task.currentHeadSha())
                    || !Objects.equals(prepared.expectedChangeSetRevisionId,
                            task.currentChangeSetRevisionId())
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())
                    || run.role() != AgentRole.CI_FIXER
                    || run.state() != RunState.RUNNING
                    || !run.operationId().equals(operation.operationId())
                    || !run.sessionId().equals(task.ciSessionId())) {
                throw new StaleOwnerRevisionException(
                        "cleanup subject changed after non-clean inspection");
            }
            assertFenceOwnsOperation(fence, operation, AgentRole.CI_FIXER);
            AgentProcessAttempt processAttempt = requireStoppedProcessAttempt(
                    runId, claim.generation());
            if (!processAttempt.processAttemptId().equals(
                            prepared.processAttemptId)
                    || !processAttempt.stopProofRef().equals(
                            prepared.stopProofRef)) {
                throw new StaleOwnerRevisionException(
                        "cleanup stop proof changed after inspection");
            }
            Instant now = clock.instant();
            String resultId = stableId("agent-result", runId);
            int resultStored = jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_result (
                        result_id, run_id, terminal_outcome, final_content,
                        error_ref, stop_proof_ref, stored_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    resultId,
                    runId,
                    terminalOutcome.name(),
                    finalContent,
                    errorRef,
                    processAttempt.stopProofRef(),
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
                        "cleanup predecessor changed during finalization");
            }
            OperationState terminalState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            settleDispatch(operation.operationId(), terminalState, resultId);
            int inboxHandled = jdbc.update(
                    """
                    UPDATE flow_runtime_inbox
                    SET handled_by_operation_id = ?
                    WHERE selected_by_operation_id = ?
                      AND handled_by_operation_id IS NULL
                    """,
                    operation.operationId(),
                    operation.operationId());
            if (inboxHandled != 1) {
                throw new StaleOwnerRevisionException(
                        "cleanup predecessor lost its selected input");
            }

            String cleanupId = stableId(
                    "ci-cleanup-seal", repairAttemptId);
            String subjectDigest = cleanupSubjectDigest(
                    task.taskId(),
                    snapshot.taskEpoch(),
                    repairAttemptId,
                    cleanupId,
                    prepared.expectedChangeSetRevisionId,
                    snapshot.predecessorHeadSha(),
                    operation.operationId(),
                    runId,
                    resultId,
                    prepared.inspection);
            String successorId = stableId(
                    "operation",
                    "CI_CLEANUP",
                    cleanupId,
                    OperationKind.RUN_CI_FIXER.name(),
                    subjectDigest);
            String inputRef = "ci-cleanup:" + cleanupId;
            if (operation(successorId).isPresent()) {
                throw new StaleOwnerRevisionException(
                        "cleanup successor already exists before reservation");
            }
            insertOperationAndTicket(
                    successorId,
                    "CI_CLEANUP",
                    cleanupId,
                    task.taskId(),
                    OperationKind.RUN_CI_FIXER,
                    subjectDigest,
                    inputRef,
                    null,
                    CI_FIX_PRIORITY,
                    now);
            assertFreshCleanupSuccessor(successorId);
            int pointerTransferred = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET selected_writer_operation_id = ?
                    WHERE task_id = ? AND epoch = ? AND status = 'ACTIVE'
                      AND selected_writer_operation_id = ?
                      AND current_head_sha = ?
                      AND current_change_set_revision_id = ?
                    """,
                    successorId,
                    task.taskId(),
                    snapshot.taskEpoch(),
                    operation.operationId(),
                    snapshot.predecessorHeadSha(),
                    prepared.expectedChangeSetRevisionId);
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
            if (pointerTransferred != 1 || leaseReleased != 1) {
                throw new StaleOwnerRevisionException(
                        "cleanup successor lost the predecessor fence");
            }
            return new CleanupHandoff(
                    cleanupId,
                    requireResult(resultId),
                    requireOperation(successorId),
                    prepared.inspection);
        });
    }

    /** Verifies exact replay without reinspecting or rerunning the writer. */
    public synchronized AgentResult replayStoppedCiCleanupHandoff(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String repairAttemptId,
            String cleanupId,
            String inputChangeSetRevisionId,
            String inputHead,
            NonCleanInspection sealedState,
            String stateDigest,
            String successorOperationId)
    {
        requireText(repairAttemptId, "repairAttemptId");
        requireText(cleanupId, "cleanupId");
        requireText(inputChangeSetRevisionId,
                "inputChangeSetRevisionId");
        requireText(inputHead, "inputHead");
        requireNonNull(sealedState, "sealedState is null");
        requireText(stateDigest, "stateDigest");
        requireText(successorOperationId, "successorOperationId");
        AgentResult result = resultForRun(runId).orElseThrow(() ->
                new IllegalStateException(
                        "cleanup handoff has no durable predecessor result"));
        if (result.terminalOutcome() != terminalOutcome
                || !Objects.equals(result.finalContent(), finalContent)
                || !Objects.equals(result.errorRef(), errorRef)) {
            throw new IllegalStateException(
                    "cleanup handoff replay changed terminal content");
        }
        AgentResult verified = verifyFinalizedAgentResult(
                runId, claim, fence, result);
        return inTransaction(() -> {
            AgentRun run = requireRun(runId);
            Operation old = requireOperation(run.operationId());
            Operation successor = requireOperation(successorOperationId);
            if (!sealedState.stateDigest().equals(stateDigest)) {
                throw new IllegalStateException(
                        "cleanup replay seal digest is inconsistent");
            }
            String expectedSubject = cleanupSubjectDigest(
                    old.taskId(),
                    fence.taskEpoch(),
                    repairAttemptId,
                    cleanupId,
                    inputChangeSetRevisionId,
                    inputHead,
                    old.operationId(),
                    runId,
                    verified.resultId(),
                    sealedState);
            String expectedId = stableId(
                    "operation",
                    "CI_CLEANUP",
                    cleanupId,
                    OperationKind.RUN_CI_FIXER.name(),
                    expectedSubject);
            if (!old.operationId().equals(claim.operationId())
                    || !successor.operationId().equals(expectedId)
                    || !successor.ownerKind().equals("CI_CLEANUP")
                    || !successor.ownerId().equals(cleanupId)
                    || !Objects.equals(successor.taskId(), old.taskId())
                    || successor.kind() != OperationKind.RUN_CI_FIXER
                    || !successor.subjectDigest().equals(expectedSubject)
                    || !successor.inputRef().equals(
                            "ci-cleanup:" + cleanupId)) {
                throw new IllegalStateException(
                        "cleanup handoff replay changed successor identity");
            }
            return verified;
        });
    }

    /** Finishes the CI writer with its exact inspected Task continuation. */
    public synchronized AgentResult finishCiAgentRun(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String attemptId,
            CiFixOutcome outcome,
            String outputHead,
            String outputChangeSetRevisionId)
    {
        requireText(attemptId, "attemptId");
        requireNonNull(outcome, "outcome is null");
        requireText(outputHead, "outputHead");
        requireText(outputChangeSetRevisionId,
                "outputChangeSetRevisionId");
        return finishWriterAgentRun(
                runId,
                claim,
                fence,
                terminalOutcome,
                finalContent,
                errorRef,
                attemptId,
                "CI_ROUND",
                outcome,
                outputHead,
                outputChangeSetRevisionId);
    }

    /**
     * Consumes one exact STOPPED cleanup final-state token. Adoption or
     * attention settlement is one runtime transaction; the CI owner joins it
     * to its immutable completion insert in the surrounding transaction.
     */
    public synchronized CiCleanupFinalizationReceipt finalizeCiCleanup(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String cleanupId,
            PreparedCiCleanupFinalState prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            if (prepared.clean != null) {
                ChangeSetRevision output = adoptPreparedCiCleanupChangeSet(
                        claim, fence, prepared);
                return finishCiCleanupRun(
                        runId,
                        claim,
                        fence,
                        terminalOutcome,
                        finalContent,
                        errorRef,
                        cleanupId,
                        output.headSha(),
                        output.changeSetRevisionId());
            }
            if (prepared.nonClean != null) {
                return finishCiCleanupNonClean(
                        runId,
                        claim,
                        fence,
                        terminalOutcome,
                        finalContent,
                        errorRef,
                        cleanupId,
                        prepared.nonClean);
            }
            return finishCiCleanupInspectionBlocked(
                    runId,
                    claim,
                    fence,
                    terminalOutcome,
                    finalContent,
                    errorRef,
                    cleanupId,
                    prepared);
        });
    }

    /** Verifies an exact committed clean-cleanup replay. */
    public synchronized CiCleanupFinalizationReceipt replayCiCleanupRun(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String cleanupId,
            String outputHead,
            String outputChangeSetRevisionId)
    {
        if (resultForRun(runId).isEmpty()) {
            throw new IllegalStateException(
                    "cleanup replay has no durable AgentResult");
        }
        return finishCiCleanupRun(
                runId,
                claim,
                fence,
                terminalOutcome,
                finalContent,
                errorRef,
                cleanupId,
                outputHead,
                outputChangeSetRevisionId);
    }

    private CiCleanupFinalizationReceipt finishCiCleanupRun(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String cleanupId,
            String outputHead,
            String outputChangeSetRevisionId)
    {
        requireText(cleanupId, "cleanupId");
        requireText(outputHead, "outputHead");
        requireText(outputChangeSetRevisionId,
                "outputChangeSetRevisionId");
        CiFixOutcome outcome = outputHead.equals(fence.headSha())
                ? CiFixOutcome.NO_HEAD_CHANGE
                : CiFixOutcome.FIX_PREPARED;
        AgentResult result = finishWriterAgentRun(
                runId,
                claim,
                fence,
                terminalOutcome,
                finalContent,
                errorRef,
                cleanupId,
                "CI_CLEANUP",
                outcome,
                outputHead,
                outputChangeSetRevisionId);
        return new CiCleanupFinalizationReceipt(
                cleanupId,
                claim.operationId(),
                result,
                outcome,
                outputHead,
                outputChangeSetRevisionId,
                null,
                null,
                false,
                result.storedAt());
    }

    /** Finalizes a stopped cleanup whose exact worktree is still non-clean. */
    private CiCleanupFinalizationReceipt finishCiCleanupNonClean(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String cleanupId,
            PreparedNonCleanState prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return finishCiCleanupAttention(
                runId,
                claim,
                fence,
                terminalOutcome,
                finalContent,
                errorRef,
                cleanupId,
                prepared,
                null);
    }

    /** Finalizes a stopped cleanup after a stable mechanical inspection stop. */
    private CiCleanupFinalizationReceipt
            finishCiCleanupInspectionBlocked(
                    String runId,
                    Claim claim,
                    WriterFence fence,
                    TerminalOutcome terminalOutcome,
                    String finalContent,
                    String errorRef,
                    String cleanupId,
                    PreparedCiCleanupFinalState prepared)
    {
        requireNonNull(prepared, "prepared is null");
        PreparedCiCleanupInspectionBlock blocked = prepared.blocked;
        if (blocked == null) {
            throw new IllegalArgumentException(
                    "cleanup final state is not inspection-blocked");
        }
        return finishCiCleanupAttention(
                runId,
                claim,
                fence,
                terminalOutcome,
                finalContent,
                errorRef,
                cleanupId,
                null,
                blocked);
    }

    private CiCleanupFinalizationReceipt finishCiCleanupAttention(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String cleanupId,
            PreparedNonCleanState prepared,
            PreparedCiCleanupInspectionBlock blocked)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        requireText(cleanupId, "cleanupId");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            AgentRun run = requireRun(runId);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(fence.taskId());
            if (!run.operationId().equals(operation.operationId())
                    || run.state() != RunState.RUNNING
                    || run.role() != AgentRole.CI_FIXER
                    || !operation.ownerKind().equals("CI_CLEANUP")
                    || !operation.ownerId().equals(cleanupId)
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())) {
                throw new StaleOwnerRevisionException(
                        "cleanup attention subject is no longer current");
            }
            AgentProcessAttempt stopped = requireStoppedProcessAttempt(
                    runId, claim.generation());
            NonCleanInspection finalState = null;
            FailureCode failureCode = null;
            if (prepared != null) {
                AdoptionSnapshot snapshot = prepared.snapshot;
                if (!sameClaimAuthority(prepared.claim, claim)
                        || !sameFenceAuthority(prepared.fence, fence)
                        || !prepared.processAttemptId.equals(
                                stopped.processAttemptId())
                        || !prepared.stopProofRef.equals(stopped.stopProofRef())
                        || !snapshot.taskId().equals(task.taskId())
                        || snapshot.taskEpoch() != task.epoch()
                        || !snapshot.operationId().equals(
                                operation.operationId())
                        || !snapshot.sourceRunId().equals(runId)
                        || snapshot.source() != ChangeSetSource.CI_FIXER
                        || snapshot.redelivery() != null
                        || !Objects.equals(
                                prepared.expectedChangeSetRevisionId,
                                task.currentChangeSetRevisionId())
                        || !snapshot.predecessorHeadSha().equals(
                                task.currentHeadSha())) {
                    throw new StaleOwnerRevisionException(
                            "cleanup final state changed after inspection");
                }
                finalState = prepared.inspection;
            }
            else if (blocked != null) {
                AdoptionSnapshot snapshot = blocked.snapshot;
                if (!sameClaimAuthority(blocked.claim, claim)
                        || !sameFenceAuthority(blocked.fence, fence)
                        || !blocked.processAttemptId.equals(
                                stopped.processAttemptId())
                        || !blocked.stopProofRef.equals(stopped.stopProofRef())
                        || !snapshot.taskId().equals(task.taskId())
                        || snapshot.taskEpoch() != task.epoch()
                        || !snapshot.operationId().equals(
                                operation.operationId())
                        || !snapshot.sourceRunId().equals(runId)
                        || !Objects.equals(
                                blocked.expectedChangeSetRevisionId,
                                task.currentChangeSetRevisionId())
                        || !snapshot.predecessorHeadSha().equals(
                                task.currentHeadSha())) {
                    throw new StaleOwnerRevisionException(
                            "cleanup inspection blocker changed identity");
                }
                failureCode = blocked.failureCode;
            }
            else {
                throw new IllegalArgumentException(
                        "cleanup attention has no mechanical evidence");
            }
            Instant now = clock.instant();
            String resultId = stableId("agent-result", runId);
            int resultStored = jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_result (
                        result_id, run_id, terminal_outcome, final_content,
                        error_ref, stop_proof_ref, stored_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    resultId,
                    runId,
                    terminalOutcome.name(),
                    finalContent,
                    errorRef,
                    stopped.stopProofRef(),
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
                        "cleanup result owner changed during attention finalization");
            }
            OperationState terminalState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            settleDispatch(operation.operationId(), terminalState, resultId);
            String attentionRef = cleanupAttentionRef(cleanupId);
            TaskLifecycleRevision attention = appendLifecycle(
                    task,
                    TaskStatus.NEEDS_ATTENTION,
                    "CI_CLEANUP_UNRESOLVED",
                    attentionRef,
                    operation.operationId(),
                    now);
            int taskUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET status = 'NEEDS_ATTENTION',
                        current_lifecycle_revision_id = ?,
                        selected_writer_operation_id = NULL,
                        waiting_mutation_state_ref = ?
                    WHERE task_id = ? AND epoch = ? AND status = 'ACTIVE'
                      AND selected_writer_operation_id = ?
                      AND waiting_mutation_state_ref IS NULL
                    """,
                    attention.lifecycleRevisionId(),
                    attentionRef,
                    task.taskId(),
                    task.epoch(),
                    operation.operationId());
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
            if (taskUpdated != 1 || leaseReleased != 1) {
                throw new StaleOwnerRevisionException(
                        "cleanup attention lost its writer authority");
            }
            AgentResult result = requireResult(resultId);
            return new CiCleanupFinalizationReceipt(
                    cleanupId,
                    operation.operationId(),
                    result,
                    null,
                    null,
                    null,
                    finalState,
                    failureCode,
                    false,
                    result.storedAt());
        });
    }

    /** Replays an exact committed cleanup attention result. */
    public synchronized AgentResult replayCiCleanupAttention(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String cleanupId)
    {
        AgentResult result = resultForRun(runId).orElseThrow(() ->
                new IllegalStateException(
                        "cleanup attention has no durable AgentResult"));
        if (result.terminalOutcome() != terminalOutcome
                || !Objects.equals(result.finalContent(), finalContent)
                || !Objects.equals(result.errorRef(), errorRef)) {
            throw new IllegalStateException(
                    "cleanup attention replay changed terminal content");
        }
        AgentResult verified = verifyFinalizedAgentResult(
                runId, claim, fence, result);
        return inTransaction(() -> {
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(fence.taskId());
            if (!operation.ownerKind().equals("CI_CLEANUP")
                    || !operation.ownerId().equals(cleanupId)
                    || task.status() != TaskStatus.NEEDS_ATTENTION
                    || !cleanupAttentionRef(cleanupId).equals(
                            task.waitingMutationStateRef())) {
                throw new IllegalStateException(
                        "cleanup attention replay changed durable identity");
            }
            return verified;
        });
    }

    private AgentResult finishWriterAgentRun(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String ciAttemptId,
            String ciOwnerKind,
            CiFixOutcome ciOutcome,
            String outputHead,
            String outputChangeSetRevisionId)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        return inTransaction(() -> {
            AgentRun run = requireRun(runId);
            boolean ciFinalization = ciAttemptId != null;
            if ((run.role() == AgentRole.CI_FIXER) != ciFinalization) {
                throw new MutationRejectedException(ciFinalization
                        ? "CI finalization requires a CI Fixer run"
                        : "CI Fixer requires its domain finalizer");
            }
            if (ciFinalization) {
                Operation operation = requireOperation(run.operationId());
                if (!operation.ownerKind().equals(ciOwnerKind)
                        || (ciOwnerKind.equals("CI_CLEANUP")
                            && !operation.ownerId().equals(ciAttemptId))) {
                    throw new MutationRejectedException(
                            "CI finalizer does not own this operation");
                }
            }
            Optional<AgentResult> existing = resultForRun(runId);
            if (existing.isPresent()) {
                AgentResult result = existing.get();
                assertFinalizedClaim(claim, result.resultId());
                if (result.terminalOutcome() != terminalOutcome
                        || !Objects.equals(result.finalContent(), finalContent)
                        || !Objects.equals(result.errorRef(), errorRef)) {
                    throw new IllegalStateException(
                            "AgentResult redelivery changed terminal content");
                }
                AgentProcessAttempt stopped = requireStoppedProcessAttempt(
                        runId, claim.generation());
                if (!result.stopProofRef().equals(stopped.stopProofRef())) {
                    throw new IllegalStateException(
                            "AgentResult stop proof changed after finalization");
                }
                if (ciFinalization) {
                    requireCiFixReady(
                            run,
                            result,
                            ciAttemptId,
                            ciOwnerKind,
                            ciOutcome,
                            outputHead,
                            outputChangeSetRevisionId);
                }
                return result;
            }

            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            if (!run.operationId().equals(claim.operationId())
                    || run.state() != RunState.RUNNING) {
                throw new StaleClaimException(
                        "run is not active under this dispatch claim");
            }
            assertFenceOwnsOperation(
                    fence, requireOperation(run.operationId()), run.role());
            if (ciFinalization) {
                Task task = requireTask(fence.taskId());
                ChangeSetRevision output = requireChangeSetRevision(
                        outputChangeSetRevisionId);
                if (!Objects.equals(task.currentHeadSha(), outputHead)
                        || !Objects.equals(task.currentChangeSetRevisionId(),
                                outputChangeSetRevisionId)
                        || !output.taskId().equals(task.taskId())
                        || !output.headSha().equals(outputHead)
                        || output.source() != ChangeSetSource.CI_FIXER
                        || !Objects.equals(output.sourceRunId(), runId)
                        || !output.sourceOperationId().equals(
                                run.operationId())) {
                    throw new MutationRejectedException(
                            "CI output is not the current inspected change set");
                }
            }

            Instant now = clock.instant();
            AgentProcessAttempt processAttempt = requireStoppedProcessAttempt(
                    runId, claim.generation());
            String resultId = stableId("agent-result", runId);
            int resultStored = jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_result (
                        result_id, run_id, terminal_outcome, final_content,
                        error_ref, stop_proof_ref, stored_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    resultId,
                    runId,
                    terminalOutcome.name(),
                    finalContent,
                    errorRef,
                    processAttempt.stopProofRef(),
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
            if (ciFinalization) {
                appendCiFixReady(
                        run,
                        requireResult(resultId),
                        ciAttemptId,
                        ciOwnerKind,
                        ciOutcome,
                        outputHead,
                        outputChangeSetRevisionId);
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

    private PendingWork appendCiFixReady(
            AgentRun run,
            AgentResult result,
            String attemptId,
            String ownerKind,
            CiFixOutcome outcome,
            String outputHead,
            String outputChangeSetRevisionId)
    {
        Operation operation = requireOperation(run.operationId());
        Task task = requireTask(operation.taskId());
        return appendPendingWork(
                stableId("inbox", "CI_FIX_READY", attemptId, "1"),
                task,
                task.prId(),
                "CI",
                attemptId,
                "1",
                PendingKind.CI_FIX_READY,
                outputHead,
                ciFixReadyPayload(
                        attemptId,
                        ownerKind,
                        outcome,
                        outputChangeSetRevisionId),
                result.resultId(),
                clock.instant());
    }

    private PendingWork requireCiFixReady(
            AgentRun run,
            AgentResult result,
            String attemptId,
            String ownerKind,
            CiFixOutcome outcome,
            String outputHead,
            String outputChangeSetRevisionId)
    {
        Operation operation = requireOperation(run.operationId());
        Task task = requireTask(operation.taskId());
        PendingWork ready = jdbc.query(
                "SELECT * FROM flow_runtime_inbox WHERE agent_result_id = ?",
                (row, number) -> readInbox(row),
                result.resultId()).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "CI finalization has no durable continuation"));
        if (!ready.taskId().equals(task.taskId())
                || ready.kind() != PendingKind.CI_FIX_READY
                || !ready.externalKey().equals(attemptId)
                || !ready.subjectHead().equals(outputHead)
                || !ready.payloadRef().equals(ciFixReadyPayload(
                        attemptId,
                        ownerKind,
                        outcome,
                        outputChangeSetRevisionId))) {
            throw new IllegalStateException(
                    "CI finalization redelivery changed continuation identity");
        }
        return ready;
    }

    private static String ciFixReadyPayload(
            String attemptId,
            String ownerKind,
            CiFixOutcome outcome,
            String outputChangeSetRevisionId)
    {
        String subject = ownerKind.equals("CI_CLEANUP")
                ? "ci-cleanup:"
                : "ci-attempt:";
        return subject + attemptId + ":outcome:" + outcome.name()
                + ":change-set:" + outputChangeSetRevisionId;
    }

    private static String cleanupSubjectDigest(
            String taskId,
            long taskEpoch,
            String repairAttemptId,
            String cleanupId,
            String inputChangeSetRevisionId,
            String inputHead,
            String predecessorOperationId,
            String predecessorRunId,
            String predecessorResultId,
            NonCleanInspection state)
    {
        return stableId(
                "ci-cleanup-subject",
                taskId,
                Long.toString(taskEpoch),
                repairAttemptId,
                cleanupId,
                inputChangeSetRevisionId,
                inputHead,
                predecessorOperationId,
                predecessorRunId,
                predecessorResultId,
                state.actualHeadSha(),
                state.branchHeadSha(),
                state.attachmentState().name(),
                state.kind().name(),
                String.join(",", state.operations().stream()
                        .map(Enum::name)
                        .toList()),
                state.stateDigest());
    }

    private static String ciCleanupEvidenceRef(
            String cleanupId, String inputChangeSetRevisionId)
    {
        return "ci-cleanup-seal:" + cleanupId
                + ":input-change-set:" + inputChangeSetRevisionId;
    }

    private static String cleanupAttentionRef(String cleanupId)
    {
        return "ci-cleanup-attention:" + cleanupId;
    }

    private void assertFreshCleanupSuccessor(String operationId)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                LEFT JOIN flow_runtime_agent_run r
                  ON r.operation_id = o.operation_id
                LEFT JOIN flow_runtime_writer_lease l
                  ON l.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.state = 'READY'
                  AND o.attempt = 0 AND o.result_ref IS NULL
                  AND d.delivery_state = 'AVAILABLE'
                  AND d.claim_owner IS NULL AND d.claim_expires_at IS NULL
                  AND d.claim_generation = 0 AND d.claim_token IS NULL
                  AND r.run_id IS NULL AND l.operation_id IS NULL
                """,
                Integer.class,
                operationId);
        if (requireNonNull(matches, "cleanup successor count is null") != 1) {
            throw new StaleOwnerRevisionException(
                    "cleanup successor was not freshly reserved");
        }
    }

    private static boolean sameClaimAuthority(Claim first, Claim second)
    {
        return first.operationId().equals(second.operationId())
                && Objects.equals(first.taskId(), second.taskId())
                && first.kind() == second.kind()
                && first.generation() == second.generation()
                && first.claimToken().equals(second.claimToken())
                && first.workerId().equals(second.workerId());
    }

    private static boolean sameFenceAuthority(
            WriterFence first, WriterFence second)
    {
        return first.taskId().equals(second.taskId())
                && first.operationId().equals(second.operationId())
                && first.taskEpoch() == second.taskEpoch()
                && first.holderKind() == second.holderKind()
                && first.fencingToken() == second.fencingToken()
                && first.claimGeneration() == second.claimGeneration()
                && first.claimTokenDigest().equals(
                        second.claimTokenDigest())
                && first.headSha().equals(second.headSha())
                && first.treeDigest().equals(second.treeDigest())
                && first.snapshotEvidenceRef().equals(
                        second.snapshotEvidenceRef());
    }

    /**
     * Verifies that a stopped program finalizer durably settled this exact
     * writer authority. A callback return value is not itself a receipt.
     */
    synchronized AgentResult verifyFinalizedAgentResult(
            String runId,
            Claim claim,
            WriterFence fence,
            AgentResult returnedResult)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(returnedResult, "returnedResult is null");
        return inTransaction(() -> {
            AgentResult stored = resultForRun(runId).orElseThrow(() ->
                    new IllegalStateException(
                            "stopped finalizer did not store AgentResult"));
            if (!stored.equals(returnedResult)) {
                throw new IllegalStateException(
                        "stopped finalizer returned a noncanonical AgentResult");
            }
            assertFinalizedClaim(claim, stored.resultId());

            AgentRun run = requireRun(runId);
            Operation operation = requireOperation(claim.operationId());
            RunState expectedRunState = switch (stored.terminalOutcome()) {
                case COMPLETED -> RunState.COMPLETED;
                case FAILED -> RunState.FAILED;
                case CANCELED -> RunState.CANCELED;
            };
            OperationState expectedOperationState = switch (
                    stored.terminalOutcome()) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            if (!run.operationId().equals(claim.operationId())
                    || run.state() != expectedRunState
                    || operation.state() != expectedOperationState
                    || !stored.resultId().equals(operation.resultRef())) {
                throw new IllegalStateException(
                        "agent run or operation is not durably finalized");
            }
            assertFenceOwnsOperation(fence, operation, run.role());

            AgentSession session = requireSession(run.sessionId());
            if (runId.equals(session.lastRunId())) {
                if (session.state() == SessionState.NEW
                        || session.state() == SessionState.RUNNING) {
                    throw new IllegalStateException(
                            "agent session still owns the finalized run");
                }
            }
            else {
                AgentRun successor = requireRun(session.lastRunId());
                if (!successor.sessionId().equals(session.sessionId())) {
                    throw new IllegalStateException(
                            "agent session advanced without a durable run");
                }
            }

            Task task = requireTask(fence.taskId());
            if (claim.operationId().equals(
                    task.selectedWriterOperationId())) {
                throw new IllegalStateException(
                        "finalized operation still owns the Task pointer");
            }
            Integer oldLease = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_writer_lease
                    WHERE task_id = ? AND operation_id = ?
                      AND task_epoch = ? AND fencing_token = ?
                    """,
                    Integer.class,
                    fence.taskId(),
                    fence.operationId(),
                    fence.taskEpoch(),
                    fence.fencingToken());
            if (requireNonNull(oldLease, "writer lease count is null") != 0) {
                throw new IllegalStateException(
                        "finalized operation still owns its writer lease");
            }
            return stored;
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

    public Optional<TaskBaseRevision> currentBaseRevision(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                """
                SELECT b.* FROM flow_runtime_task t
                JOIN flow_runtime_task_base_revision b
                  ON b.base_revision_id = t.current_base_revision_id
                WHERE t.task_id = ?
                """,
                (result, row) -> readBaseRevision(result),
                taskId).stream().findFirst();
    }

    private Optional<TaskBaseRevision> baseRevisionForSource(
            String sourceOperationId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_task_base_revision
                WHERE source_operation_id = ?
                """,
                (result, row) -> readBaseRevision(result),
                sourceOperationId).stream().findFirst();
    }

    public Optional<ChangeSetRevision> currentChangeSet(String taskId)
    {
        requireText(taskId, "taskId");
        return jdbc.query(
                """
                SELECT c.* FROM flow_runtime_task t
                JOIN flow_runtime_change_set_revision c
                  ON c.change_set_revision_id = t.current_change_set_revision_id
                WHERE t.task_id = ?
                """,
                (result, row) -> readChangeSetRevision(result),
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
            case CI_FIX_READY, AGENT_RESULT_READY -> {
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

    private AgentProcessAttempt requireStoppedProcessAttempt(
            String runId, long generation)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_process_attempt
                WHERE run_id = ? AND claim_generation = ?
                  AND state = 'STOPPED'
                  AND capability_revoked_at IS NOT NULL
                  AND stop_type IS NOT NULL
                  AND stop_proof_ref IS NOT NULL
                  AND stopped_at IS NOT NULL
                """,
                (result, row) -> readProcessAttempt(result),
                runId,
                generation).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "run has no stopped in-process proof for this claim"));
    }

    private void assertTerminationAuthority(
            String processAttemptId, Claim claim, WriterFence fence)
    {
        if (!fence.operationId().equals(claim.operationId())
                || fence.claimGeneration() != claim.generation()
                || !fence.claimTokenDigest().equals(claimTokenDigest(claim))) {
            throw new StaleWriterFenceException(
                    "writer fence is not bound to the process claim");
        }
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_agent_process_attempt a
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = a.operation_id
                JOIN flow_runtime_operation o
                  ON o.operation_id = a.operation_id
                JOIN flow_runtime_writer_lease l
                  ON l.operation_id = a.operation_id
                JOIN flow_runtime_task t ON t.task_id = l.task_id
                WHERE a.process_attempt_id = ?
                  AND a.operation_id = ?
                  AND a.claim_generation = ?
                  AND a.claim_token_digest = ?
                  AND d.claim_generation = a.claim_generation
                  AND d.claim_token = ? AND d.claim_owner = ?
                  AND l.task_id = ? AND l.operation_id = ?
                  AND l.task_epoch = ? AND l.fencing_token = ?
                  AND l.claim_generation = a.claim_generation
                  AND l.claim_token_digest = a.claim_token_digest
                  AND l.holder_kind = ? AND l.head_sha = ?
                  AND l.tree_digest = ? AND l.snapshot_evidence_ref = ?
                  AND l.expires_at = ?
                  AND t.epoch = l.task_epoch
                  AND t.selected_writer_operation_id = l.operation_id
                  AND (
                      (d.delivery_state = 'CLAIMED'
                          AND o.state = 'CLAIMED')
                      OR (d.delivery_state = 'DONE'
                          AND o.state = 'FAILED'
                          AND o.result_ref = ?
                          AND t.status = 'NEEDS_ATTENTION')
                  )
                """,
                Integer.class,
                processAttemptId,
                claim.operationId(),
                claim.generation(),
                claimTokenDigest(claim),
                claim.claimToken(),
                claim.workerId(),
                fence.taskId(),
                fence.operationId(),
                fence.taskEpoch(),
                fence.fencingToken(),
                fence.holderKind().name(),
                fence.headSha(),
                fence.treeDigest(),
                fence.snapshotEvidenceRef(),
                fence.expiresAt().toEpochMilli(),
                "PROCESS_ATTEMPT_RECOVERY_REQUIRED:" + processAttemptId);
        if (requireNonNull(matches, "process owner count is null") != 1) {
            throw new StaleCapabilityException(
                    "writer termination authority is stale");
        }
    }

    private static void assertInProcessIdentity(
            AgentProcessAttempt attempt,
            TerminatedThreadWitness witness)
    {
        Thread thread = witness.thread();
        if (thread.getState() != Thread.State.TERMINATED
                || !Long.valueOf(witness.jvmPid()).equals(attempt.jvmPid())
                || !witness.jvmStartedAt().equals(attempt.jvmStartedAt())
                || !Long.valueOf(thread.threadId())
                        .equals(attempt.threadId())) {
            throw new StaleCapabilityException(
                    "terminated writer execution identity is stale");
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
        if (task.status() == TaskStatus.NEEDS_ATTENTION) {
            return;
        }
        if (task.status() != TaskStatus.ACTIVE) {
            throw new StaleOwnerRevisionException(
                    "expired writer cannot quarantine this Task state");
        }
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
                  AND t.waiting_mutation_state_ref IS NULL
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
        assertWriterSubjectCurrent(
                requireOperation(fence.operationId()), fence);
    }

    private void assertWriterSubjectCurrent(
            Operation operation, WriterFence fence)
    {
        if (!operation.ownerKind().equals("CI_CLEANUP")) {
            assertWriterSubjectCurrent(operation, fence.headSha());
            return;
        }
        String prefix = "ci-cleanup-seal:" + operation.ownerId()
                + ":input-change-set:";
        if (!operation.inputRef().equals(
                    "ci-cleanup:" + operation.ownerId())
                || !fence.snapshotEvidenceRef().startsWith(prefix)) {
            throw new MutationRejectedException(
                    "cleanup fence has no exact sealed-state subject");
        }
        String inputChangeSetRevisionId = fence.snapshotEvidenceRef()
                .substring(prefix.length());
        requireText(inputChangeSetRevisionId,
                "cleanup inputChangeSetRevisionId");
        Task task = requireTask(operation.taskId());
        boolean inputIsCurrent = task.currentHeadSha().equals(fence.headSha())
                && Objects.equals(
                        task.currentChangeSetRevisionId(),
                        inputChangeSetRevisionId);
        Optional<ChangeSetRevision> current = currentChangeSet(task.taskId());
        Optional<AgentRun> run = runForOperation(operation.operationId());
        boolean cleanupOutputIsCurrent = current.isPresent()
                && run.isPresent()
                && current.get().changeSetRevisionId().equals(
                        task.currentChangeSetRevisionId())
                && current.get().headSha().equals(task.currentHeadSha())
                && Objects.equals(
                        current.get().previousChangeSetRevisionId(),
                        inputChangeSetRevisionId)
                && current.get().previousHeadSha().equals(fence.headSha())
                && current.get().source() == ChangeSetSource.CI_FIXER
                && current.get().sourceOperationId().equals(
                        operation.operationId())
                && Objects.equals(
                        current.get().sourceRunId(), run.get().runId());
        if (!inputIsCurrent && !cleanupOutputIsCurrent) {
            throw new MutationRejectedException(
                    "cleanup logical input/change set is no longer current");
        }
    }

    private void assertWriterSubjectCurrent(
            Operation operation, String admissionHead)
    {
        String condition = operation.kind() == OperationKind.RUN_CI_FIXER
                ? """
                  i.kind = 'FINAL_RED'
                  AND i.pr_id = p.pr_id
                  AND i.subject_head = p.current_remote_head
                  AND i.subject_head = ?
                  """
                : """
                  i.kind IN (
                      'INITIAL_TASK', 'CI_FIX_READY', 'AGENT_RESULT_READY'
                  )
                  AND i.subject_head = ?
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
                operation.taskId(),
                admissionHead);
        if (requireNonNull(matches, "writer subject count is null") != 1) {
            throw new MutationRejectedException(
                    "writer input is no longer the exact admission subject");
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

    private AdoptionSnapshot adoptionSnapshot(
            Claim claim,
            WriterFence fence,
            String expectedChangeSetRevisionId)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        assertFenceRow(claim, fence);
        Operation operation = requireOperation(claim.operationId());
        ChangeSetSource source = switch (operation.kind()) {
            case RUN_TASK_TURN -> ChangeSetSource.TASK_AGENT;
            case RUN_CI_FIXER -> ChangeSetSource.CI_FIXER;
            default -> throw new IllegalArgumentException(
                    "operation cannot adopt an agent change set");
        };
        AgentRole role = source == ChangeSetSource.TASK_AGENT
                ? AgentRole.TASK_AGENT
                : AgentRole.CI_FIXER;
        assertFenceOwnsOperation(fence, operation, role);
        AgentRun run = runForOperation(operation.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "writer operation has no exact AgentRun"));
        if (run.role() != role || run.state() != RunState.RUNNING) {
            throw new IllegalStateException(
                    "writer operation has no running source AgentRun");
        }
        Task task = requireTask(operation.taskId());
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "Task has no current base revision"));
        Optional<ChangeSetRevision> current = currentChangeSet(task.taskId());
        if (current.isPresent()) {
            ChangeSetRevision revision = current.get();
            if (revision.sourceOperationId().equals(operation.operationId())
                    && Objects.equals(revision.sourceRunId(), run.runId())
                    && Objects.equals(revision.previousChangeSetRevisionId(),
                            expectedChangeSetRevisionId)
                    && Objects.equals(task.currentChangeSetRevisionId(),
                            revision.changeSetRevisionId())
                    && revision.headSha().equals(task.currentHeadSha())
                    && revision.baseRevisionId().equals(base.baseRevisionId())) {
                return new AdoptionSnapshot(
                        task.taskId(),
                        task.epoch(),
                        operation.operationId(),
                        operation.kind(),
                        source,
                        run.runId(),
                        Path.of(task.worktreePath()),
                        task.branchName(),
                        base.baseRevisionId(),
                        base.baseSha(),
                        revision.previousHeadSha(),
                        revision);
            }
        }
        if (!Objects.equals(task.currentChangeSetRevisionId(),
                expectedChangeSetRevisionId)) {
            throw new StaleOwnerRevisionException(
                    "expected change-set revision is stale");
        }
        return new AdoptionSnapshot(
                task.taskId(),
                task.epoch(),
                operation.operationId(),
                operation.kind(),
                source,
                run.runId(),
                Path.of(task.worktreePath()),
                task.branchName(),
                base.baseRevisionId(),
                base.baseSha(),
                task.currentHeadSha(),
                null);
    }

    private CiCleanupAdmissionSnapshot cleanupAdmissionSnapshot(
            Claim claim,
            String cleanupId,
            String repairAttemptId,
            String predecessorOperationId,
            String predecessorRunId,
            String predecessorResultId,
            String inputChangeSetRevisionId,
            String inputHead,
            NonCleanInspection sealedState)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        Operation operation = requireOperation(claim.operationId());
        if (operation.kind() != OperationKind.RUN_CI_FIXER
                || !operation.ownerKind().equals("CI_CLEANUP")
                || !operation.ownerId().equals(cleanupId)
                || !operation.inputRef().equals("ci-cleanup:" + cleanupId)) {
            throw new MutationRejectedException(
                    "claim does not own the exact CI cleanup operation");
        }
        Task task = requireTask(operation.taskId());
        String expectedSubject = cleanupSubjectDigest(
                task.taskId(),
                task.epoch(),
                repairAttemptId,
                cleanupId,
                inputChangeSetRevisionId,
                inputHead,
                predecessorOperationId,
                predecessorRunId,
                predecessorResultId,
                sealedState);
        if (task.status() != TaskStatus.ACTIVE
                || task.waitingMutationStateRef() != null
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())
                || !task.currentHeadSha().equals(inputHead)
                || !Objects.equals(
                        task.currentChangeSetRevisionId(),
                        inputChangeSetRevisionId)
                || !operation.subjectDigest().equals(expectedSubject)) {
            throw new MutationRejectedException(
                    "cleanup logical Task input is no longer current");
        }
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new MutationRejectedException(
                        "cleanup Task has no current base revision"));
        AgentSession session = sessionForRole(task, AgentRole.CI_FIXER)
                .orElseThrow(() -> new MutationRejectedException(
                        "cleanup has no persistent CI session"));
        Optional<AgentRun> run = runForOperation(operation.operationId());
        Optional<WriterFence> fence = writerFence(task.taskId());
        if (run.isEmpty() && fence.isPresent()) {
            throw new MutationRejectedException(
                    "cleanup writer has only one half of its durable authority");
        }
        if (run.isEmpty()) {
            if (session.state() != SessionState.IDLE) {
                throw new MutationRejectedException(
                        "persistent CI session is not idle for cleanup");
            }
            return new CiCleanupAdmissionSnapshot(
                    task, base, session, null, null);
        }
        AgentRun existingRun = run.orElseThrow();
        if (existingRun.role() != AgentRole.CI_FIXER
                || (existingRun.state() != RunState.QUEUED
                        && existingRun.state() != RunState.RUNNING)
                || !existingRun.sessionId().equals(session.sessionId())
                || !existingRun.headSha().equals(inputHead)
                || session.state() != SessionState.RUNNING
                || !Objects.equals(session.lastRunId(), existingRun.runId())) {
            throw new MutationRejectedException(
                    "cleanup writer redelivery changed durable identity");
        }
        if (fence.isEmpty()) {
            if (existingRun.state() != RunState.QUEUED
                    || hasProcessAttemptForRun(existingRun.runId())) {
                throw new MutationRejectedException(
                        "cleanup writer lost a non-recoverable fence");
            }
            return new CiCleanupAdmissionSnapshot(
                    task, base, session, null, existingRun);
        }
        WriterFence existingFence = fence.orElseThrow();
        if (!existingFence.headSha().equals(inputHead)
                || !existingFence.treeDigest().equals(
                        sealedState.stateDigest())
                || !existingFence.snapshotEvidenceRef().equals(
                        ciCleanupEvidenceRef(
                                cleanupId,
                                inputChangeSetRevisionId))) {
            throw new MutationRejectedException(
                    "cleanup writer redelivery changed durable identity");
        }
        assertFenceRow(claim, existingFence);
        return new CiCleanupAdmissionSnapshot(
                task, base, session, existingFence, existingRun);
    }

    private boolean hasProcessAttemptForRun(String runId)
    {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_agent_process_attempt WHERE run_id = ?",
                Integer.class,
                runId);
        return requireNonNull(count, "process attempt count is null") != 0;
    }

    private Optional<ChangeSetRevision> changeSetForSource(
            String taskId, String operationId, String headSha)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_change_set_revision
                WHERE task_id = ? AND source_operation_id = ? AND head_sha = ?
                """,
                (result, row) -> readChangeSetRevision(result),
                taskId,
                operationId,
                headSha).stream().findFirst();
    }

    private static boolean exactAdoptionRedelivery(
            ChangeSetRevision revision,
            Task task,
            AdoptionSnapshot snapshot,
            Inspection inspection,
            String expectedChangeSetRevisionId)
    {
        return Objects.equals(
                        task.currentChangeSetRevisionId(),
                        revision.changeSetRevisionId())
                && task.currentHeadSha().equals(revision.headSha())
                && Objects.equals(
                        revision.previousChangeSetRevisionId(),
                        expectedChangeSetRevisionId)
                && revision.previousHeadSha().equals(
                        snapshot.predecessorHeadSha())
                && revision.headSha().equals(inspection.headSha())
                && revision.baseRevisionId().equals(snapshot.baseRevisionId())
                && revision.baseSha().equals(snapshot.baseSha())
                && revision.headTreeDigest().equals(
                        inspection.headTreeDigest())
                && revision.diffDigest().equals(
                        inspection.baseToHeadDiffDigest())
                && revision.differsFromBase() == inspection.differsFromBase()
                && revision.source() == snapshot.source()
                && Objects.equals(revision.sourceRunId(), snapshot.sourceRunId())
                && revision.sourceOperationId().equals(snapshot.operationId());
    }

    private ChangeSetRevision requireChangeSetRevision(String revisionId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_change_set_revision
                WHERE change_set_revision_id = ?
                """,
                (result, row) -> readChangeSetRevision(result),
                revisionId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown ChangeSetRevision: " + revisionId));
    }

    private Optional<PendingWork> inbox(String inboxId)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_inbox WHERE inbox_id = ?",
                (result, row) -> readInbox(result),
                inboxId).stream().findFirst();
    }

    private String initialTaskHead(String taskId)
    {
        return jdbc.queryForObject(
                """
                SELECT subject_head FROM flow_runtime_inbox
                WHERE task_id = ? AND kind = 'INITIAL_TASK'
                """,
                String.class,
                taskId);
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
                result.getString("current_base_revision_id"),
                result.getString("branch_name"),
                result.getString("worktree_path"),
                result.getString("current_head_sha"),
                result.getString("current_change_set_revision_id"),
                result.getString("task_session_id"),
                result.getString("ci_session_id"),
                result.getString("pr_id"),
                result.getString("current_lifecycle_revision_id"),
                result.getLong("pending_work_watermark"),
                result.getLong("last_reconciled_work_watermark"),
                result.getLong("reconciliation_sequence"),
                result.getString("selected_writer_operation_id"),
                result.getString("waiting_mutation_state_ref"),
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

    private static TaskBaseRevision readBaseRevision(ResultSet result)
            throws SQLException
    {
        return new TaskBaseRevision(
                result.getString("base_revision_id"),
                result.getString("task_id"),
                result.getLong("sequence"),
                result.getString("previous_base_sha"),
                result.getString("base_sha"),
                result.getString("reason_code"),
                result.getString("evidence_ref"),
                result.getString("source_operation_id"),
                instant(result, "recorded_at"));
    }

    private static ChangeSetRevision readChangeSetRevision(ResultSet result)
            throws SQLException
    {
        return new ChangeSetRevision(
                result.getString("change_set_revision_id"),
                result.getString("task_id"),
                result.getLong("sequence"),
                result.getString("previous_change_set_revision_id"),
                result.getString("previous_head_sha"),
                result.getString("head_sha"),
                result.getString("base_revision_id"),
                result.getString("base_sha"),
                result.getString("head_tree_digest"),
                result.getString("diff_digest"),
                result.getInt("differs_from_base") == 1,
                ChangeSetSource.valueOf(result.getString("source")),
                result.getString("source_run_id"),
                result.getString("source_operation_id"),
                instant(result, "adopted_at"));
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
                result.getString("created_from_change_set_revision_id"),
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
                result.getString("claim_token_digest"),
                result.getString("execution_id"),
                result.getString("capability_id"),
                ProcessAttemptState.valueOf(result.getString("state")),
                nullableLong(result, "jvm_pid"),
                nullableInstant(result, "jvm_started_at"),
                nullableLong(result, "thread_id"),
                result.getString("thread_name"),
                instant(result, "reserved_at"),
                nullableInstant(result, "activated_at"),
                nullableInstant(result, "capability_revoked_at"),
                nullableEnum(result, "stop_type", InProcessStopType.class),
                result.getString("stop_proof_ref"),
                nullableInstant(result, "stopped_at"),
                nullableEnum(
                        result,
                        "quarantine_reason",
                        ProcessQuarantineReason.class),
                nullableInstant(result, "quarantined_at"));
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
                result.getString("stop_proof_ref"),
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

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static <E extends Enum<E>> E nullableEnum(
            ResultSet result, String column, Class<E> type)
            throws SQLException
    {
        String value = result.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
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

    private static void requireObjectId(String value, String name)
    {
        requireText(value, name);
        if (value.length() != 40 && value.length() != 64) {
            throw new IllegalArgumentException(
                    name + " must be a full lowercase object ID");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException(
                        name + " must be a full lowercase object ID");
            }
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

    private record AdoptionSnapshot(
            String taskId,
            long taskEpoch,
            String operationId,
            OperationKind operationKind,
            ChangeSetSource source,
            String sourceRunId,
            Path worktree,
            String branchName,
            String baseRevisionId,
            String baseSha,
            String predecessorHeadSha,
            ChangeSetRevision redelivery) {}

    private record PreparedNonCleanSnapshot(
            AdoptionSnapshot snapshot, AgentProcessAttempt stopped) {}

    private record CiCleanupAdmissionSnapshot(
            Task task,
            TaskBaseRevision base,
            AgentSession session,
            WriterFence fence,
            AgentRun run) {}

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

    public static class StaleCapabilityException
            extends IllegalStateException
    {
        public StaleCapabilityException(String message)
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
