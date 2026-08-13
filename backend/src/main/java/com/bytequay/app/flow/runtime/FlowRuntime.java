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
import com.bytequay.app.flow.github.InitialPublishRecords.Settlement;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentSession;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetSource;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GitHubRepositoryLocator;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.InProcessStopType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PrDraftRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessQuarantineReason;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReadyForReviewRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskBaseRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskLifecycleRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskTerminalRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskTerminalRequestKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WakeKind;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int PUBLISH_PRIORITY = 850;
    private static final int CI_FIX_PRIORITY = 800;
    private static final int TASK_TURN_PRIORITY = 700;
    private static final int CI_LEARNING_PRIORITY = 600;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final FlowWorktreeInspector worktreeInspector = new FlowWorktreeInspector();
    private final CiObservationRuntime ciObservations;

    /** One live-JVM permission for exactly one provider mutation call. */
    public static final class PublishExecutionHandle
    {
        private final String operationId;
        private final long generation;
        private final String attemptId;
        private final String tokenDigest;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private PublishExecutionHandle(
                String operationId,
                long generation,
                String attemptId,
                String tokenDigest)
        {
            this.operationId = operationId;
            this.generation = generation;
            this.attemptId = attemptId;
            this.tokenDigest = tokenDigest;
        }

        public String tokenDigest()
        {
            return tokenDigest;
        }
    }

    /** One-use precursor whose digest may be persisted before a handle exists. */
    public static final class PublishExecutionReservation
    {
        private final String operationId;
        private final long generation;
        private final String attemptId;
        private final String tokenDigest;
        private final AtomicBoolean minted = new AtomicBoolean();

        private PublishExecutionReservation(
                String operationId, long generation, String attemptId,
                String tokenDigest)
        {
            this.operationId = operationId;
            this.generation = generation;
            this.attemptId = attemptId;
            this.tokenDigest = tokenDigest;
        }

        public String tokenDigest() { return tokenDigest; }
    }

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

    /** Private-construction proof of one exact inspected Task turn input. */
    public static final class PreparedTaskWriterAdmission
    {
        private final Claim claim;
        private final TaskAdmissionSnapshot snapshot;
        private final Inspection inspection;

        private PreparedTaskWriterAdmission(
                Claim claim,
                TaskAdmissionSnapshot snapshot,
                Inspection inspection)
        {
            this.claim = claim;
            this.snapshot = snapshot;
            this.inspection = inspection;
        }
    }

    /** Private-construction proof of one exact INITIAL Task input. */
    public static final class PreparedInitialTaskAdmission
    {
        private final Claim claim;
        private final InitialTaskAdmissionSnapshot snapshot;
        private final Inspection inspection;

        private PreparedInitialTaskAdmission(
                Claim claim,
                InitialTaskAdmissionSnapshot snapshot,
                Inspection inspection)
        {
            this.claim = claim;
            this.snapshot = snapshot;
            this.inspection = inspection;
        }
    }

    /** Private-construction subject for the terminal reviewer request tool. */
    public static final class PreparedReviewerRequest
    {
        private final Claim claim;
        private final WriterFence fence;
        private final ReviewRequestSnapshot snapshot;
        private final Inspection inspection;
        private final String repositoryRoot;
        private final CiFixReviewOrigin origin;
        private final LocalChecks.ReviewerEvidence reviewerEvidence;
        private final String localCheckPolicyRevisionId;
        private final List<String> checkRunRefs;

        private PreparedReviewerRequest(
                Claim claim,
                WriterFence fence,
                ReviewRequestSnapshot snapshot,
                Inspection inspection,
                String repositoryRoot,
                CiFixReviewOrigin origin,
                LocalChecks.ReviewerEvidence reviewerEvidence,
                String localCheckPolicyRevisionId,
                List<String> checkRunRefs)
        {
            this.claim = claim;
            this.fence = fence;
            this.snapshot = snapshot;
            this.inspection = inspection;
            this.repositoryRoot = repositoryRoot;
            this.origin = origin;
            this.reviewerEvidence = reviewerEvidence;
            this.localCheckPolicyRevisionId =
                    localCheckPolicyRevisionId;
            this.checkRunRefs = List.copyOf(checkRunRefs);
        }
    }

    /** Private proof for a stopped, unchanged reviewer-result Task turn. */
    private static final class PreparedTaskResultConsumption
    {
        private final Claim claim;
        private final WriterFence fence;
        private final TaskResultConsumptionSnapshot snapshot;
        private final Inspection inspection;
        private final String blockerCode;

        private PreparedTaskResultConsumption(
                Claim claim,
                WriterFence fence,
                TaskResultConsumptionSnapshot snapshot,
                Inspection inspection,
                String blockerCode)
        {
            this.claim = claim;
            this.fence = fence;
            this.snapshot = snapshot;
            this.inspection = inspection;
            this.blockerCode = blockerCode;
        }

        private boolean accepted()
        {
            return inspection != null && blockerCode == null;
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

    /** Runtime-owned fence and persistent-session run for an inspected turn. */
    public record TaskWriterStart(WriterFence fence, AgentRun run)
    {
        public TaskWriterStart
        {
            requireNonNull(fence, "fence is null");
            requireNonNull(run, "run is null");
        }
    }

    /** Fresh request-bound read-only reviewer session and run. */
    public record ReviewerStart(
            ReviewerRequest request, AgentSession session, AgentRun run)
    {
        public ReviewerStart
        {
            requireNonNull(request, "request is null");
            requireNonNull(session, "session is null");
            requireNonNull(run, "run is null");
        }
    }

    /** Fresh one-shot read-only learner session and run. */
    public record CiLearningStart(AgentSession session, AgentRun run)
    {
        public CiLearningStart
        {
            requireNonNull(session, "session is null");
            requireNonNull(run, "run is null");
        }
    }

    /** Process-only result used by the CI owner during STOPPED recovery. */
    public record StoppedCiLearningResult(
            AgentResult result, String runId, String subjectId)
    {
        public StoppedCiLearningResult
        {
            requireNonNull(result, "result is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(subjectId, "subjectId is null");
        }
    }

    /** Exact program completion sealed with one durable STOPPED proof. */
    public record StoppedAgentCompletion(
            String processAttemptId,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String completionDigest)
    {
        public StoppedAgentCompletion
        {
            requireText(processAttemptId, "processAttemptId");
            assertCompletion(terminalOutcome, errorRef);
            requireText(completionDigest, "completionDigest");
        }
    }

    /** Renewed same-generation authority for a proven STOPPED writer only. */
    public record StoppedWriterRecovery(
            AgentRun run,
            Claim claim,
            WriterFence fence,
            StoppedAgentCompletion completion)
    {
        public StoppedWriterRecovery
        {
            requireNonNull(run, "run is null");
            requireNonNull(claim, "claim is null");
            requireNonNull(fence, "fence is null");
            requireNonNull(completion, "completion is null");
        }
    }

    /** Renewed same-generation authority for a proven STOPPED reviewer. */
    public record StoppedReviewerRecovery(
            AgentRun run,
            Claim claim,
            StoppedAgentCompletion completion)
    {
        public StoppedReviewerRecovery
        {
            requireNonNull(run, "run is null");
            requireNonNull(claim, "claim is null");
            requireNonNull(completion, "completion is null");
        }
    }

    public FlowRuntime(DataSource dataSource, Clock clock)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.clock = requireNonNull(clock, "clock is null");
        this.ciObservations = new CiObservationRuntime(this, jdbc, clock);
    }

    /**
     * Persists one Task plus its provisioning operation/ticket. It performs no
     * Git, process, or model work.
     */
    synchronized Task startTask(TaskProvisioning.FrozenLaunch launch)
    {
        requireNonNull(launch, "launch is null");
        return inTransaction(() -> {
            Instant now = clock.instant();
            String taskId = launch.taskId();
            String lifecycleId = stableId("task-lifecycle", taskId, "1");
            int accepted = jdbc.update(
                    """
                    INSERT OR IGNORE INTO flow_runtime_task (
                        task_id, request_key, repository_id,
                        repository_owner, repository_name, goal_text, status,
                        repository_root, git_common_dir, remote_name,
                        base_ref,
                        launch_digest,
                        epoch, branch_name, worktree_path,
                        current_lifecycle_revision_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?,
                        1, ?, ?, ?)
                    """,
                    taskId,
                    launch.requestKey(),
                    launch.repositoryId(),
                    launch.repositoryOwner(),
                    launch.repositoryName(),
                    launch.goalText(),
                    launch.repositoryRoot(),
                    launch.gitCommonDir(),
                    launch.remoteName(),
                    launch.baseRef(),
                    launch.launchDigest(),
                    launch.branchName(),
                    launch.worktreePath(),
                    lifecycleId);
            if (accepted == 0) {
                Task task = taskByRequestKey(launch.requestKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Task launch conflicts with another subject"));
                TaskProvisioning.assertStoredLaunch(task);
                if (!task.repositoryId().equals(launch.repositoryId())
                        || !task.goalText().equals(launch.goalText())) {
                    throw new IllegalStateException(
                            "requestKey already owns a different Task command");
                }
                return task;
            }
            if (accepted != 1) {
                throw new IllegalStateException(
                        "Task acceptance changed an unexpected row count");
            }
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

            String subjectDigest = TaskProvisioning.provisionSubjectDigest(
                    taskId, launch.launchDigest());
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
    synchronized AgentSession provisionTask(
            Claim claim, TaskProvisioning.ProvisionedWorktree proof)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(proof, "proof is null");
        return inTransaction(() -> {
            Operation operation = requireOperation(claim.operationId());
            if (operation.kind() != OperationKind.PROVISION_TASK) {
                throw new IllegalArgumentException(
                        "claim does not own Task provisioning");
            }
            Task task = requireTask(operation.taskId());
            proof.assertMatches(claim, task, operation);
            String baseSha = proof.baseSha();
            String headSha = proof.headSha();
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
                    GateIntent.INITIAL_PUBLISH,
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
            int taskLocked = jdbc.update(
                    """
                    UPDATE flow_runtime_task SET task_id = task_id
                    WHERE task_id = ? AND current_lifecycle_revision_id = ?
                    """,
                    taskId,
                    expectedLifecycleRevisionId);
            Task task = requireTask(taskId);
            if (taskLocked != 1
                    || !task.currentLifecycleRevisionId()
                    .equals(expectedLifecycleRevisionId)) {
                throw new StaleOwnerRevisionException(
                        "Task lifecycle revision is stale");
            }
            if (!allowedTransition(task.status(), nextStatus)) {
                throw new IllegalStateException(
                        "Invalid Task transition: " + task.status()
                                + " -> " + nextStatus);
            }
            if (task.status() == TaskStatus.NEEDS_ATTENTION
                    && nextStatus == TaskStatus.ACTIVE) {
                throw new MutationRejectedException(
                        "Task attention requires a typed recovery cause");
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
            if (hasNonterminalReviewer(taskId)) {
                throw new MutationRejectedException(
                        "Task lifecycle cannot change while review is live");
            }
            if (hasNonterminalPublish(taskId)) {
                throw new MutationRejectedException(
                        "Task lifecycle cannot change while publication is live");
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

    /** Appends one exact-head local draft; it never performs a remote call. */
    public synchronized PrDraftRevision savePrDraft(
            Claim claim,
            WriterFence fence,
            String capabilityId,
            String prId,
            String expectedChangeSetRevisionId,
            String expectedHeadSha,
            String createdByRunId,
            String title,
            String body)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(capabilityId, "capabilityId");
        requireText(prId, "prId");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireText(expectedHeadSha, "expectedHeadSha");
        requireText(createdByRunId, "createdByRunId");
        requireText(title, "title");
        requireNonNull(body, "body is null");
        if (title.length() > 256 || body.length() > 65_536) {
            throw new IllegalArgumentException("PR draft is too large");
        }
        return inTransaction(() -> {
            assertInProcessWriterToolCapability(
                    createdByRunId, claim, fence, capabilityId);
            PullRequestSubject pr = requirePullRequest(prId);
            Task task = requireTask(pr.taskId());
            ChangeSetRevision changeSet = requireChangeSetRevision(
                    expectedChangeSetRevisionId);
            AgentRun run = requireRun(createdByRunId);
            Optional<PrDraftRevision> replay = jdbc.query(
                    "SELECT * FROM flow_runtime_pr_draft_revision "
                            + "WHERE created_by_run_id = ?",
                    (result, row) -> readPrDraftRevision(result),
                    createdByRunId).stream().findFirst();
            if (replay.isPresent()) {
                PrDraftRevision existing = replay.orElseThrow();
                if (!existing.prId().equals(prId)
                        || !existing.changeSetRevisionId().equals(
                                expectedChangeSetRevisionId)
                        || !existing.headSha().equals(expectedHeadSha)
                        || !existing.title().equals(title)
                        || !existing.body().equals(body)
                        || !Objects.equals(pr.currentDraftRevisionId(),
                                existing.draftRevisionId())) {
                    throw new IllegalStateException(
                            "Task run already owns a different PR draft");
                }
                return existing;
            }
            if (pr.published()) {
                throw new StaleOwnerRevisionException(
                        "published PR metadata is immutable in this flow");
            }
            if (task.status() != TaskStatus.ACTIVE
                    || !Objects.equals(task.currentChangeSetRevisionId(),
                            expectedChangeSetRevisionId)
                    || !Objects.equals(task.currentHeadSha(), expectedHeadSha)
                    || !changeSet.taskId().equals(task.taskId())
                    || !changeSet.headSha().equals(expectedHeadSha)
                    || !changeSet.differsFromBase()
                    || !run.operationId().equals(claim.operationId())
                    || run.role() != AgentRole.TASK_AGENT
                    || run.state() != RunState.RUNNING
                    || !fence.taskId().equals(task.taskId())
                    || !Objects.equals(task.selectedWriterOperationId(),
                            claim.operationId())) {
                throw new StaleOwnerRevisionException(
                        "PR draft is not bound to the current authored head");
            }
            long sequence = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sequence), 0) + 1 "
                            + "FROM flow_runtime_pr_draft_revision WHERE pr_id = ?",
                    Long.class,
                    prId);
            String digest = stableId(
                    "pr-draft:v1",
                    prId,
                    Long.toString(sequence),
                    expectedChangeSetRevisionId,
                    expectedHeadSha,
                    title,
                    body);
            String revisionId = stableId("pr-draft-revision:v1", digest);
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_pr_draft_revision (
                        draft_revision_id, pr_id, sequence,
                        change_set_revision_id, head_sha, title, body,
                        draft_digest, created_by_run_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    revisionId, prId, sequence, expectedChangeSetRevisionId,
                    expectedHeadSha, title, body, digest, createdByRunId,
                    now.toEpochMilli());
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_pr SET current_draft_revision_id = ?
                    WHERE pr_id = ? AND remote_identity_id IS NULL
                      AND (current_draft_revision_id IS NULL
                           OR current_draft_revision_id = ?)
                    """,
                    revisionId,
                    prId,
                    pr.currentDraftRevisionId());
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "PR draft changed concurrently");
            }
            return requirePrDraftRevision(revisionId);
        });
    }

    public Optional<PrDraftRevision> currentPrDraft(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query(
                """
                SELECT d.* FROM flow_runtime_pr p
                JOIN flow_runtime_pr_draft_revision d
                  ON d.draft_revision_id = p.current_draft_revision_id
                WHERE p.pr_id = ?
                """,
                (result, row) -> readPrDraftRevision(result),
                prId).stream().findFirst();
    }

    public PrDraftRevision requirePrDraftRevision(String revisionId)
    {
        requireText(revisionId, "revisionId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_pr_draft_revision "
                        + "WHERE draft_revision_id = ?",
                (result, row) -> readPrDraftRevision(result),
                revisionId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown PR draft revision: " + revisionId));
    }

    private synchronized PullRequestSubject bindGitHubRemoteIdentity(
            String prId,
            String expectedLocalHead,
            GitHubRepositoryLocator baseRepository,
            GitHubRepositoryLocator headRepository,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String publicationReceiptId)
    {
        requireText(prId, "prId");
        requireText(expectedLocalHead, "expectedLocalHead");
        requireNonNull(baseRepository, "baseRepository is null");
        requireNonNull(headRepository, "headRepository is null");
        requireText(baseRepository.repositoryExternalId(),
                "baseRepository.repositoryExternalId");
        requireText(headRepository.repositoryExternalId(),
                "headRepository.repositoryExternalId");
        requireGitHubRepositoryLocator(
                baseRepository.owner(), baseRepository.name());
        requireGitHubRepositoryLocator(
                headRepository.owner(), headRepository.name());
        requireText(prNodeId, "prNodeId");
        requireText(htmlUrl, "htmlUrl");
        requireText(publicationReceiptId, "publicationReceiptId");
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber must be positive");
        }
        return inTransaction(() -> {
            return bindGitHubRemoteIdentity0(
                    prId, expectedLocalHead, baseRepository, headRepository,
                    prNumber, prNodeId, htmlUrl, publicationReceiptId, true);
        });
    }

    private PullRequestSubject bindGitHubRemoteIdentity0(
            String prId,
            String expectedLocalHead,
            GitHubRepositoryLocator baseRepository,
            GitHubRepositoryLocator headRepository,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String publicationReceiptId,
            boolean requireCurrentLocalHead)
    {
            PullRequestSubject pr = requirePullRequest(prId);
            if (pr.published()) {
                if (!"GITHUB".equals(pr.provider())
                        || !baseRepository.repositoryExternalId().equals(
                                pr.repositoryExternalId())
                        || !baseRepository.owner().equals(pr.repositoryOwner())
                        || !baseRepository.name().equals(pr.repositoryName())
                        || !headRepository.repositoryExternalId().equals(
                                pr.headRepositoryExternalId())
                        || !headRepository.owner().equals(
                                pr.headRepositoryOwner())
                        || !headRepository.name().equals(
                                pr.headRepositoryName())
                        || !Long.valueOf(prNumber).equals(pr.prNumber())
                        || !expectedLocalHead.equals(pr.currentRemoteHead())) {
                    throw new IllegalStateException(
                            "PR already owns a different remote identity");
                }
                assertRemoteIdentityMatches(
                        pr.remoteIdentityId(),
                        "GITHUB",
                        baseRepository.repositoryExternalId(),
                        baseRepository.owner(),
                        baseRepository.name(),
                        headRepository.repositoryExternalId(),
                        headRepository.owner(),
                        headRepository.name(),
                        prNumber,
                        prNodeId,
                        htmlUrl,
                        publicationReceiptId);
                return pr;
            }
            Task task = requireTask(pr.taskId());
            if (requireCurrentLocalHead
                    && !expectedLocalHead.equals(task.currentHeadSha())) {
                throw new StaleOwnerRevisionException(
                        "publication head is not current");
            }

            String identityId = stableId(
                    "remote-pr", "GITHUB",
                    baseRepository.repositoryExternalId(),
                    Long.toString(prNumber));
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_remote_identity (
                        remote_identity_id, provider, repository_external_id,
                        repository_owner, repository_name,
                        head_repository_external_id,
                        head_repository_owner, head_repository_name,
                        pr_number, pr_node_id, html_url,
                        publication_receipt_id, bound_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    identityId,
                    "GITHUB",
                    baseRepository.repositoryExternalId(),
                    baseRepository.owner(),
                    baseRepository.name(),
                    headRepository.repositoryExternalId(),
                    headRepository.owner(),
                    headRepository.name(),
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
    }

    /** Consumes only a GitHub-owner settlement capability in the outer tx. */
    public synchronized void applyInitialSettlement(
            Claim claim, Settlement settlement)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(settlement, "settlement is null");
        inTransaction(() -> {
            assertPublishClaim(claim);
            List<Object[]> owners = jdbc.query(
                    """
                    SELECT p.pr_id, pr.task_id, p.authorization_id,
                           p.action_digest, p.required_ci_policy_revision_id,
                           p.ready_policy, p.proposed_head,
                           p.base_repository_external_id,
                           p.base_repository_owner, p.base_repository_name,
                           p.head_repository_external_id,
                           p.head_repository_owner, p.head_repository_name,
                           d.pr_number, d.pr_node_id, d.html_url,
                           COALESCE(fr.receipt_id, xr.partial_receipt_id),
                           COALESCE(fr.pr_step_receipt_id,
                                    xr.pr_step_receipt_id),
                           xr.reason_code
                    FROM flow_github_initial_publish_plan p
                    JOIN flow_runtime_pr pr ON pr.pr_id = p.pr_id
                    LEFT JOIN flow_github_initial_publish_receipt fr
                      ON fr.operation_id = p.operation_id
                     AND fr.plan_id = p.plan_id
                    LEFT JOIN flow_github_initial_publish_partial_receipt xr
                      ON xr.operation_id = p.operation_id
                     AND xr.plan_id = p.plan_id
                    LEFT JOIN flow_github_initial_publish_step_receipt sr
                      ON sr.receipt_id = COALESCE(
                            fr.pr_step_receipt_id, xr.pr_step_receipt_id)
                     AND sr.operation_id = p.operation_id
                     AND sr.plan_id = p.plan_id
                    LEFT JOIN flow_github_initial_pr_receipt_detail d
                      ON d.receipt_id = sr.receipt_id
                     AND d.operation_id = sr.operation_id
                     AND d.plan_id = sr.plan_id
                    WHERE p.operation_id = ? AND p.plan_id = ?
                    """,
                    (result, row) -> new Object[] {
                            result.getString(1), result.getString(2),
                            result.getString(3), result.getString(4),
                            result.getString(5), result.getString(6),
                            result.getString(7), result.getString(8),
                            result.getString(9), result.getString(10),
                            result.getString(11), result.getString(12),
                            result.getString(13), result.getLong(14),
                            result.getString(15), result.getString(16),
                            result.getString(17), result.getString(18),
                            result.getString(19)},
                    settlement.operationId(), settlement.planId());
            if (owners.size() != 1) {
                throw new IllegalStateException(
                        "initial settlement owner graph is invalid");
            }
            Object[] owner = owners.getFirst();
            String resultId = (String) owner[16];
            String prStepReceiptId = (String) owner[17];
            if (!settlement.operationId().equals(claim.operationId())
                    || !settlement.resultId().equals(resultId)
                    || !settlement.proposedHead().equals(owner[6])
                    || settlement.bindsIdentity()
                            != (prStepReceiptId != null)) {
                throw new StaleClaimException(
                        "initial settlement capability is inconsistent");
            }
            String prId = (String) owner[0];
            String taskId = (String) owner[1];
            if (settlement.bindsIdentity()) {
                String publicationProof = settlement.succeeded()
                        ? settlement.resultId() : prStepReceiptId;
                bindGitHubRemoteIdentity0(
                        prId, settlement.proposedHead(),
                        new GitHubRepositoryLocator(
                                (String) owner[7], (String) owner[8],
                                (String) owner[9]),
                        new GitHubRepositoryLocator(
                                (String) owner[10], (String) owner[11],
                                (String) owner[12]),
                        (Long) owner[13], (String) owner[14],
                        (String) owner[15], publicationProof, false);
            }
            if (settlement.succeeded()) {
                Integer existingPolicies = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + "flow_runtime_pr_ready_policy_revision "
                                + "WHERE pr_id = ?",
                        Integer.class, prId);
                if (requireNonNull(existingPolicies,
                        "ready policy count is null") != 0) {
                    throw new IllegalStateException(
                            "initial ready policy is not set-once");
                }
                long sequence = 1;
                String policyDigest = stableId(
                        "initial-ready-policy:v1", prId,
                        Long.toString(sequence), (String) owner[5],
                        (String) owner[4], (String) owner[2],
                        settlement.operationId(), settlement.planId(),
                        (String) owner[3], settlement.proposedHead(),
                        settlement.resultId());
                jdbc.update(
                        """
                        INSERT INTO flow_runtime_pr_ready_policy_revision (
                            ready_policy_revision_id, pr_id, sequence, policy,
                            required_ci_policy_revision_id, authorization_id,
                            operation_id, effect_plan_id, action_digest,
                            proposed_head, publication_receipt_id,
                            publication_receipt_digest, policy_digest,
                            created_at
                        ) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, r.receipt_id,
                                 r.receipt_digest, ?, ?
                          FROM flow_github_initial_publish_receipt r
                          WHERE r.receipt_id = ? AND r.operation_id = ?
                            AND r.plan_id = ?
                        """,
                        stableId("initial-ready-policy-id:v1", policyDigest),
                        prId, sequence, owner[5], owner[4], owner[2],
                        settlement.operationId(), settlement.planId(), owner[3],
                        settlement.proposedHead(), policyDigest,
                        clock.instant().toEpochMilli(), settlement.resultId(),
                        settlement.operationId(), settlement.planId());
                ensureCiObservationWatch(settlement.resultId());
                settleDispatch(claim.operationId(), OperationState.SUCCEEDED,
                        settlement.resultId());
            }
            else {
                Task task = requireTask(taskId);
                String reason = "INITIAL_PUBLISH_"
                        + settlement.attentionReason();
                TaskLifecycleRevision lifecycle = appendLifecycle(
                        task, TaskStatus.NEEDS_ATTENTION, reason,
                        settlement.resultId(), claim.operationId(),
                        clock.instant());
                int updated = jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET status = 'NEEDS_ATTENTION',
                            current_lifecycle_revision_id = ?
                        WHERE task_id = ? AND status = 'ACTIVE'
                          AND current_lifecycle_revision_id = ?
                        """,
                        lifecycle.lifecycleRevisionId(), taskId,
                        task.currentLifecycleRevisionId());
                if (updated != 1) {
                    throw new StaleOwnerRevisionException(
                            "initial partial settlement lost Task ownership");
                }
                settleDispatch(claim.operationId(), OperationState.CANCELED,
                        settlement.resultId());
            }
            ensureReconciliation(taskId);
            resumeWaitingReconciliation(taskId);
            return Boolean.TRUE;
        });
    }

    /** Validates response-loss replay against every terminal runtime owner. */
    public synchronized void assertInitialSettlementReplay(
            Claim claim, Settlement settlement)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(settlement, "settlement is null");
        String state = settlement.succeeded() ? "SUCCEEDED" : "CANCELED";
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'PUBLISH' AND o.state = ?
                  AND o.result_ref = ? AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                """,
                Integer.class, claim.operationId(), claim.taskId(), state,
                settlement.resultId(), claim.generation(), claim.claimToken(),
                claim.workerId());
        if (requireNonNull(exact,
                "initial settlement replay count is null") != 1) {
            throw new StaleClaimException(
                    "initial settlement replay authority is invalid");
        }
        if (!settlement.succeeded() && !settlement.bindsIdentity()) {
            Integer preflight = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_github_initial_publish_partial_receipt r
                    LEFT JOIN flow_github_initial_base_preflight b
                      ON b.preflight_id = r.base_preflight_id
                     AND b.operation_id = r.operation_id
                     AND b.plan_id = r.plan_id
                     AND b.preflight_digest = r.base_preflight_digest
                    WHERE r.partial_receipt_id = ?
                      AND r.operation_id = ? AND r.plan_id = ?
                      AND (r.kind = 'BRANCH_ONLY_STALE'
                           OR (r.kind = 'BRANCH_ONLY_BASE_DRIFT'
                               AND b.claim_generation = ?
                               AND b.claim_token_digest = ?))
                    """,
                    Integer.class, settlement.resultId(),
                    settlement.operationId(), settlement.planId(),
                    claim.generation(), stableId(
                            "publish-claim-token:v1", claim.claimToken()));
            if (requireNonNull(preflight,
                    "initial preflight replay count is null") != 1) {
                throw new StaleClaimException(
                        "initial base preflight replay is invalid");
            }
        }
        Integer graph = jdbc.queryForObject(
                settlement.succeeded()
                        ? """
                          SELECT COUNT(*)
                          FROM flow_github_initial_publish_receipt r
                          JOIN flow_github_initial_publish_plan p
                            ON p.plan_id = r.plan_id
                           AND p.operation_id = r.operation_id
                          JOIN flow_runtime_pr pr ON pr.pr_id = p.pr_id
                          JOIN flow_runtime_remote_identity i
                            ON i.remote_identity_id = pr.remote_identity_id
                           AND i.publication_receipt_id = r.receipt_id
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
                          JOIN flow_runtime_operation w
                            ON w.owner_kind = 'GITHUB_EFFECT_RECEIPT'
                           AND w.owner_id = r.receipt_id
                           AND w.kind = 'OBSERVE_CI'
                          JOIN flow_runtime_dispatch_ticket wd
                            ON wd.operation_id = w.operation_id
                          WHERE r.receipt_id = ? AND r.operation_id = ?
                            AND r.plan_id = ?
                          """
                        : """
                          SELECT COUNT(*)
                          FROM flow_github_initial_publish_partial_receipt r
                          JOIN flow_github_initial_publish_plan p
                            ON p.plan_id = r.plan_id
                           AND p.operation_id = r.operation_id
                          JOIN flow_runtime_pr pr ON pr.pr_id = p.pr_id
                          JOIN flow_runtime_task_lifecycle_revision l
                            ON l.task_id = pr.task_id
                           AND l.operation_id = r.operation_id
                           AND l.to_status = 'NEEDS_ATTENTION'
                           AND l.from_status = 'ACTIVE'
                           AND l.evidence_ref = r.partial_receipt_id
                           AND l.reason_code =
                                'INITIAL_PUBLISH_' || r.attention_detail
                          WHERE r.partial_receipt_id = ?
                            AND r.operation_id = ? AND r.plan_id = ?
                            AND NOT EXISTS (
                                SELECT 1 FROM flow_github_initial_publish_receipt f
                                WHERE f.operation_id = r.operation_id)
                            AND NOT EXISTS (
                                SELECT 1 FROM flow_runtime_pr_ready_policy_revision rp
                                WHERE rp.operation_id = r.operation_id)
                            AND (r.pr_step_receipt_id IS NULL OR EXISTS (
                                SELECT 1
                                FROM flow_runtime_remote_identity i
                                JOIN flow_github_initial_pr_receipt_detail pd
                                  ON pd.receipt_id = r.pr_step_receipt_id
                                 AND pd.operation_id = r.operation_id
                                 AND pd.plan_id = r.plan_id
                                 AND pd.pr_number = i.pr_number
                                 AND pd.pr_node_id = i.pr_node_id
                                 AND pd.html_url = i.html_url
                                WHERE i.remote_identity_id = pr.remote_identity_id
                                  AND i.publication_receipt_id =
                                        r.pr_step_receipt_id
                                  AND i.repository_external_id =
                                        p.base_repository_external_id
                                  AND i.repository_owner =
                                        p.base_repository_owner
                                  AND i.repository_name =
                                        p.base_repository_name
                                  AND i.head_repository_external_id =
                                        p.head_repository_external_id
                                  AND i.head_repository_owner =
                                        p.head_repository_owner
                                  AND i.head_repository_name =
                                        p.head_repository_name))
                          """,
                Integer.class, settlement.resultId(),
                settlement.operationId(), settlement.planId());
        if (requireNonNull(graph,
                "initial settlement replay graph count is null") != 1) {
            throw new IllegalStateException(
                    "initial settlement terminal graph is inconsistent");
        }
        if (settlement.succeeded()) {
            assertInitialSuccessReplayDetails(settlement);
        }
    }

    private void assertInitialSuccessReplayDetails(Settlement settlement)
    {
        List<Object[]> policies = jdbc.query(
                """
                SELECT rp.pr_id, rp.sequence, rp.policy,
                       rp.required_ci_policy_revision_id,
                       rp.authorization_id, rp.action_digest,
                       rp.publication_receipt_digest, rp.policy_digest,
                       rp.ready_policy_revision_id
                FROM flow_runtime_pr_ready_policy_revision rp
                JOIN flow_github_initial_publish_receipt r
                  ON r.receipt_id = rp.publication_receipt_id
                 AND r.operation_id = rp.operation_id
                 AND r.plan_id = rp.effect_plan_id
                WHERE rp.publication_receipt_id = ?
                """,
                (result, row) -> new Object[] {
                        result.getString(1), result.getLong(2),
                        result.getString(3), result.getString(4),
                        result.getString(5), result.getString(6),
                        result.getString(7), result.getString(8),
                        result.getString(9)},
                settlement.resultId());
        if (policies.size() != 1) {
            throw new IllegalStateException(
                    "initial ready-policy replay is ambiguous");
        }
        Object[] policy = policies.getFirst();
        String digest = stableId("initial-ready-policy:v1",
                (String) policy[0], Long.toString((long) policy[1]),
                (String) policy[2], (String) policy[3], (String) policy[4],
                settlement.operationId(), settlement.planId(),
                (String) policy[5], settlement.proposedHead(),
                settlement.resultId());
        if ((long) policy[1] != 1
                || !digest.equals(policy[7])
                || !stableId("initial-ready-policy-id:v1", digest)
                        .equals(policy[8])) {
            throw new IllegalStateException(
                    "initial ready-policy replay identity is corrupt");
        }
        ciObservations.assertWatchReplay(
                settlement.resultId(), (String) policy[6]);
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

    /** Accepts one exact provider receipt despite an equal prior observation. */
    public synchronized PullRequestSubject acceptAppliedRemoteHead(
            String prId, String expectedRemoteHead, String appliedRemoteHead)
    {
        requireText(prId, "prId");
        requireText(expectedRemoteHead, "expectedRemoteHead");
        requireText(appliedRemoteHead, "appliedRemoteHead");
        return inTransaction(() -> {
            PullRequestSubject current = requirePullRequest(prId);
            if (Objects.equals(current.currentRemoteHead(), appliedRemoteHead)) {
                assertAndLockCiUpdatePr(current);
                return current;
            }
            if (!Objects.equals(
                    current.currentRemoteHead(), expectedRemoteHead)) {
                throw new StaleOwnerRevisionException(
                        "remote PR head differs from receipt precondition");
            }
            return advanceRemoteHead(
                    prId, expectedRemoteHead, appliedRemoteHead);
        });
    }

    /** Locks and revalidates one exact published PR for gate construction. */
    public synchronized void assertAndLockCiUpdatePr(
            PullRequestSubject expected)
    {
        requireNonNull(expected, "expected is null");
        inTransaction(() -> {
            int locked = jdbc.update(
                    """
                    UPDATE flow_runtime_pr
                    SET current_remote_head = current_remote_head
                    WHERE pr_id = ? AND task_id = ? AND repository_id = ?
                      AND branch_name = ? AND target_base_ref = ?
                      AND current_remote_head = ?
                      AND remote_identity_id IS NOT NULL
                    """,
                    expected.prId(),
                    expected.taskId(),
                    expected.repositoryId(),
                    expected.branchName(),
                    expected.targetBaseRef(),
                    expected.currentRemoteHead());
            if (locked != 1
                    || !requirePullRequest(expected.prId()).equals(expected)) {
                throw new StaleOwnerRevisionException(
                        "CI_UPDATE PR subject changed during gate construction");
            }
            return Boolean.TRUE;
        });
    }

    /** Takes the stable PR ordering lock without asserting mutable remote state. */
    public synchronized PullRequestSubject lockPublishedPullRequest(String prId)
    {
        requireText(prId, "prId");
        return inTransaction(() -> {
            int locked = jdbc.update(
                    """
                    UPDATE flow_runtime_pr SET pr_id = pr_id
                    WHERE pr_id = ? AND remote_identity_id IS NOT NULL
                    """,
                    prId);
            if (locked != 1) {
                throw new StaleOwnerRevisionException(
                        "published PR is unavailable");
            }
            return requirePullRequest(prId);
        });
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
        return requirePullRequest(prId);
    }

    /** Reserves one exact GitHub-owned publication operation and barrier. */
    public synchronized Operation reservePublishOperation(
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
        return inTransaction(() -> {
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
            Task task = requireTask(taskId);
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
            insertOperationAndTicket(
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
            return requireOperation(operationId);
        });
    }

    /**
     * Replaces older PR watches and installs the one read-only watch owned by
     * an exact applied receipt. This must join the receipt settlement tx.
     */
    public synchronized Operation ensureCiObservationWatch(String receiptId)
    {
        return ciObservations.ensureWatch(receiptId);
    }

    /** Reserves the one optional learner owned by an applied CI update. */
    public synchronized Operation ensureCiLearningOperation(
            String operationId,
            String receiptId,
            String taskId,
            String subjectId,
            String subjectDigest,
            Instant createdAt)
    {
        requireText(operationId, "operationId");
        requireText(receiptId, "receiptId");
        requireText(taskId, "taskId");
        requireText(subjectId, "subjectId");
        requireText(subjectDigest, "subjectDigest");
        requireNonNull(createdAt, "createdAt is null");
        return inTransaction(() -> {
            insertOperationAndTicket(
                    operationId,
                    "CI_UPDATE_RECEIPT",
                    receiptId,
                    taskId,
                    OperationKind.RUN_CI_LEARNING,
                    subjectDigest,
                    subjectId,
                    null,
                    CI_LEARNING_PRIORITY,
                    createdAt);
            Operation operation = requireOperation(operationId);
            if (!operation.ownerKind().equals("CI_UPDATE_RECEIPT")
                    || !operation.ownerId().equals(receiptId)
                    || !Objects.equals(operation.taskId(), taskId)
                    || operation.kind() != OperationKind.RUN_CI_LEARNING
                    || !operation.subjectDigest().equals(subjectDigest)
                    || !operation.inputRef().equals(subjectId)) {
                throw new IllegalStateException(
                        "CI learning operation replay changed identity");
            }
            return operation;
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
                        WHEN 'AGENT_RESULT_READY' THEN 1
                        WHEN 'CI_FIX_READY' THEN 2
                        WHEN 'FINAL_RED' THEN 3
                        WHEN 'INITIAL_TASK' THEN 4
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
        return claimNext(workerId, claimTtl, null, Integer.MAX_VALUE);
    }

    /**
     * Claims one exact wired local-runtime kind under the durable capacity
     * bound. The production data source begins this transaction IMMEDIATE.
     */
    public synchronized Optional<Claim> claimNextForDispatch(
            Set<OperationKind> kinds,
            String workerId,
            Duration claimTtl,
            int capacity)
    {
        requireNonNull(kinds, "kinds is null");
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        Set<OperationKind> exactKinds = Set.copyOf(kinds);
        if (exactKinds.contains(OperationKind.PUBLISH)
                || exactKinds.contains(OperationKind.OBSERVE_CI)
                || exactKinds.contains(OperationKind.RUN_CI_LEARNING)) {
            throw new IllegalArgumentException(
                    "owner-specific kinds require their own claim path");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return claimNext(workerId, claimTtl, exactKinds, capacity);
    }

    /**
     * Claims only the CI-autofix owner graph. In particular this cannot claim
     * an INITIAL Task turn or a non-CI reviewer continuation merely because it
     * shares an operation kind.
     */
    public synchronized Optional<Claim> claimNextCiAutofix(
            String workerId, Duration claimTtl, int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
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
                    JOIN flow_runtime_task t ON t.task_id = o.task_id
                    JOIN flow_runtime_agent_session ts
                      ON ts.session_id = t.task_session_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND t.status = 'ACTIVE'
                      AND t.waiting_mutation_state_ref IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM flow_runtime_operation po
                          WHERE po.task_id = t.task_id
                            AND po.kind = 'PUBLISH'
                            AND po.state IN (
                                'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                            )
                      )
                      AND (
                          (o.kind = 'RECONCILE_TASK'
                           AND t.selected_writer_operation_id IS NULL
                           AND ts.state <> 'PARKED_CHILD'
                           AND EXISTS (
                               SELECT 1 FROM flow_runtime_inbox i
                               WHERE i.task_id = t.task_id
                                 AND i.selected_by_operation_id IS NULL
                                 AND i.handled_by_operation_id IS NULL
                                 AND i.terminal_reason IS NULL
                                 AND i.kind IN (
                                     'FINAL_RED', 'CI_FIX_READY',
                                     'AGENT_RESULT_READY'
                                 )
                                 AND (i.kind <> 'AGENT_RESULT_READY'
                                      OR EXISTS (
                                          SELECT 1
                                          FROM flow_runtime_agent_run rr
                                          JOIN flow_runtime_reviewer_request q
                                            ON q.reviewer_operation_id =
                                                rr.operation_id
                                          WHERE rr.run_id = i.external_key
                                            AND q.intended_gate_kind =
                                                'CI_UPDATE'
                                            AND q.origin_ci_fix_pending_id
                                                IS NOT NULL
                                      ))
                           ))
                          OR (o.kind = 'RUN_CI_FIXER'
                              AND o.owner_kind IN ('CI_ROUND', 'CI_CLEANUP')
                              AND t.selected_writer_operation_id =
                                  o.operation_id)
                          OR (o.kind = 'RUN_TASK_TURN'
                              AND t.selected_writer_operation_id =
                                  o.operation_id
                              AND (
                                  o.owner_kind = 'CI_ATTEMPT'
                                  OR (o.owner_kind = 'AGENT_RUN' AND EXISTS (
                                      SELECT 1
                                      FROM flow_runtime_agent_run rr
                                      JOIN flow_runtime_reviewer_request q
                                        ON q.reviewer_operation_id =
                                            rr.operation_id
                                      WHERE rr.run_id = o.owner_id
                                        AND q.intended_gate_kind = 'CI_UPDATE'
                                        AND q.origin_ci_fix_pending_id
                                            IS NOT NULL
                                  ))
                              ))
                          OR (o.kind = 'RUN_REVIEWER'
                              AND o.owner_kind = 'REVIEW_REQUEST'
                              AND t.selected_writer_operation_id IS NULL
                              AND ts.state = 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_writer_lease l
                                  WHERE l.task_id = t.task_id
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_reviewer_request q
                                  JOIN flow_runtime_operation p
                                    ON p.operation_id = q.parent_operation_id
                                  WHERE q.reviewer_operation_id =
                                            o.operation_id
                                    AND q.request_id = o.owner_id
                                    AND q.intended_gate_kind = 'CI_UPDATE'
                                    AND q.origin_ci_fix_pending_id IS NOT NULL
                                    AND p.state IN (
                                        'SUCCEEDED', 'FAILED', 'CANCELED'
                                    )
                                    AND p.result_ref IS NOT NULL
                              ))
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
                        workerId, expiresAt.toEpochMilli(), generation, token,
                        candidate.operationId(), candidate.generation());
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
                            "CI ticket claimed without its READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(), candidate.taskId(),
                        candidate.kind(), generation, token, workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }

    /** Claims only ordinary INITIAL Task/reviewer/reconciliation owners. */
    public synchronized Optional<Claim> claimNextInitialTask(
            String workerId, Duration claimTtl, int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
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
                    JOIN flow_runtime_task t ON t.task_id = o.task_id
                    JOIN flow_runtime_agent_session ts
                      ON ts.session_id = t.task_session_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND t.status = 'ACTIVE'
                      AND t.waiting_mutation_state_ref IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM flow_runtime_operation po
                          WHERE po.task_id = t.task_id
                            AND po.kind = 'PUBLISH'
                            AND po.state IN (
                                'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                            )
                      )
                      AND (
                          (o.kind = 'RECONCILE_TASK'
                           AND t.selected_writer_operation_id IS NULL
                           AND ts.state <> 'PARKED_CHILD'
                           AND EXISTS (
                               SELECT 1 FROM flow_runtime_inbox i
                               WHERE i.task_id = t.task_id
                                 AND i.selected_by_operation_id IS NULL
                                 AND i.handled_by_operation_id IS NULL
                                 AND i.terminal_reason IS NULL
                                 AND (
                                     (i.kind = 'INITIAL_TASK'
                                      AND i.intended_gate_kind =
                                            'INITIAL_PUBLISH'
                                      AND i.external_key = t.task_id
                                      AND i.pr_id IS NULL
                                      AND i.agent_result_id IS NULL
                                      AND i.payload_ref =
                                            'task-goal:' || t.task_id
                                      AND i.subject_head = t.current_head_sha)
                                     OR (i.kind = 'AGENT_RESULT_READY'
                                         AND i.intended_gate_kind =
                                            'INITIAL_PUBLISH'
                                         AND EXISTS (
                                             SELECT 1
                                             FROM flow_runtime_agent_run rr
                                             JOIN flow_runtime_reviewer_request q
                                               ON q.reviewer_operation_id =
                                                    rr.operation_id
                                             JOIN flow_runtime_agent_result ar
                                               ON ar.run_id = rr.run_id
                                             WHERE rr.run_id = i.external_key
                                               AND q.intended_gate_kind =
                                                    'INITIAL_PUBLISH'
                                               AND q.task_id = t.task_id
                                               AND ar.result_id =
                                                    i.agent_result_id
                                               AND i.payload_ref =
                                                    'reviewer-request:'
                                                    || q.request_id
                                                    || ':result:'
                                                    || ar.result_id
                                               AND i.subject_head =
                                                    q.reviewed_head_sha
                                         ))
                                 )
                           ))
                          OR (o.kind = 'RUN_TASK_TURN'
                              AND t.selected_writer_operation_id =
                                    o.operation_id
                              AND EXISTS (
                                  SELECT 1 FROM flow_runtime_inbox i
                                  WHERE i.selected_by_operation_id =
                                            o.operation_id
                                    AND i.handled_by_operation_id IS NULL
                                    AND i.terminal_reason IS NULL
                                    AND o.input_ref = 'inbox:' || i.inbox_id
                                    AND i.task_id = t.task_id
                                    AND i.intended_gate_kind =
                                        'INITIAL_PUBLISH'
                                    AND i.subject_head = t.current_head_sha
                                    AND (
                                        (o.owner_kind = 'TASK'
                                         AND o.owner_id = t.task_id
                                         AND i.kind = 'INITIAL_TASK'
                                         AND i.external_key = t.task_id
                                         AND i.pr_id IS NULL
                                         AND i.agent_result_id IS NULL
                                         AND i.payload_ref =
                                            'task-goal:' || t.task_id)
                                        OR (o.owner_kind = 'AGENT_RUN'
                                            AND i.kind =
                                                'AGENT_RESULT_READY'
                                            AND i.external_key = o.owner_id
                                            AND EXISTS (
                                                SELECT 1
                                                FROM flow_runtime_agent_run rr
                                                JOIN flow_runtime_reviewer_request q
                                                  ON q.reviewer_operation_id =
                                                       rr.operation_id
                                                JOIN flow_runtime_agent_result ar
                                                  ON ar.run_id = rr.run_id
                                                WHERE rr.run_id = o.owner_id
                                                  AND q.task_id = t.task_id
                                                  AND q.intended_gate_kind =
                                                    'INITIAL_PUBLISH'
                                                  AND ar.result_id =
                                                    i.agent_result_id
                                                  AND i.payload_ref =
                                                    'reviewer-request:'
                                                    || q.request_id
                                                    || ':result:'
                                                    || ar.result_id
                                                  AND i.subject_head =
                                                    q.reviewed_head_sha
                                            ))
                                    )
                              ))
                          OR (o.kind = 'RUN_REVIEWER'
                              AND o.owner_kind = 'REVIEW_REQUEST'
                              AND t.selected_writer_operation_id IS NULL
                              AND ts.state = 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_writer_lease l
                                  WHERE l.task_id = t.task_id
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_reviewer_request q
                                  JOIN flow_runtime_operation p
                                    ON p.operation_id = q.parent_operation_id
                                  WHERE q.reviewer_operation_id =
                                        o.operation_id
                                    AND q.request_id = o.owner_id
                                    AND q.intended_gate_kind =
                                        'INITIAL_PUBLISH'
                                    AND q.origin_ci_fix_pending_id IS NULL
                                    AND q.origin_ci_fix_source_kind IS NULL
                                    AND q.origin_ci_fix_source_id IS NULL
                                    AND p.state IN (
                                        'SUCCEEDED', 'FAILED', 'CANCELED'
                                    )
                                    AND p.result_ref IS NOT NULL
                              ))
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
                        workerId, expiresAt.toEpochMilli(), generation, token,
                        candidate.operationId(), candidate.generation());
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
                            "INITIAL ticket claimed without READY operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(), candidate.taskId(),
                        candidate.kind(), generation, token, workerId,
                        expiresAt));
            }
            return Optional.empty();
        });
    }

    private Optional<Claim> claimNext(
            String workerId,
            Duration claimTtl,
            Set<OperationKind> exactKinds,
            int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        return inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            List<String> kindNames = exactKinds == null
                    ? List.of()
                    : exactKinds.stream()
                            .map(Enum::name)
                            .sorted()
                            .toList();
            String kindFilter = kindNames.isEmpty()
                    ? ""
                    : "AND o.kind IN ("
                            + String.join(", ", Collections.nCopies(
                                    kindNames.size(), "?"))
                            + ")";
            String claimSql = """
                    SELECT o.operation_id, o.task_id, o.kind,
                           d.claim_generation
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    LEFT JOIN flow_runtime_task t ON t.task_id = o.task_id
                    LEFT JOIN flow_runtime_agent_session ts
                      ON ts.session_id = t.task_session_id
                    WHERE o.state = 'READY'
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      %s
                      AND (
                          o.kind = 'PROVISION_TASK'
                          OR (o.kind = 'RECONCILE_TASK'
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
                              AND t.selected_writer_operation_id IS NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_operation po
                                  WHERE po.task_id = t.task_id
                                    AND po.kind = 'PUBLISH'
                                    AND po.state IN (
                                        'READY', 'CLAIMED', 'WAITING',
                                        'RETRYABLE'
                                    )
                              )
                              AND ts.state <> 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_operation ro
                                  WHERE ro.task_id = t.task_id
                                    AND ro.kind = 'RUN_REVIEWER'
                                    AND ro.state IN (
                                        'READY', 'CLAIMED', 'WAITING',
                                        'RETRYABLE'
                                    )
                              ))
                          OR (o.kind IN ('RUN_TASK_TURN', 'RUN_CI_FIXER')
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_operation po
                                  WHERE po.task_id = t.task_id
                                    AND po.kind = 'PUBLISH'
                                    AND po.state IN (
                                        'READY', 'CLAIMED', 'WAITING',
                                        'RETRYABLE'
                                    )
                              )
                              AND t.selected_writer_operation_id = o.operation_id)
                          OR (o.kind = 'RUN_REVIEWER'
                              AND o.owner_kind = 'REVIEW_REQUEST'
                              AND t.status = 'ACTIVE'
                              AND t.waiting_mutation_state_ref IS NULL
                              AND t.selected_writer_operation_id IS NULL
                              AND ts.state = 'PARKED_CHILD'
                              AND NOT EXISTS (
                                  SELECT 1 FROM flow_runtime_writer_lease l
                                  WHERE l.task_id = t.task_id
                              )
                              AND EXISTS (
                                  SELECT 1
                                  FROM flow_runtime_reviewer_request q
                                  JOIN flow_runtime_operation p
                                    ON p.operation_id = q.parent_operation_id
                                  WHERE q.reviewer_operation_id = o.operation_id
                                    AND q.request_id = o.owner_id
                                    AND p.state IN (
                                        'SUCCEEDED', 'FAILED', 'CANCELED'
                                    )
                                    AND p.result_ref IS NOT NULL
                              ))
                      )
                    ORDER BY d.priority DESC, d.not_before, o.operation_id
                    LIMIT 32
                    """.formatted(kindFilter);
            List<Object> parameters = new ArrayList<>();
            parameters.add(now.toEpochMilli());
            parameters.addAll(kindNames);
            List<ClaimCandidate> candidates = jdbc.query(
                    claimSql,
                    (result, row) -> new ClaimCandidate(
                            result.getString("operation_id"),
                            result.getString("task_id"),
                            OperationKind.valueOf(result.getString("kind")),
                            result.getLong("claim_generation")),
                    parameters.toArray());
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

    /** Counts shared-capacity work; optional read-only learners are excluded. */
    int activeDispatchCount()
    {
        Integer active = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT operation_id)
                FROM (
                    SELECT o.operation_id
                    FROM flow_runtime_operation o
                    JOIN flow_runtime_dispatch_ticket d
                      ON d.operation_id = o.operation_id
                    WHERE o.state = 'CLAIMED'
                      AND d.delivery_state = 'CLAIMED'
                      AND o.kind <> 'RUN_CI_LEARNING'
                    UNION ALL
                    SELECT p.operation_id
                    FROM flow_runtime_agent_process_attempt p
                    JOIN flow_runtime_agent_run r ON r.run_id = p.run_id
                    WHERE p.state = 'ACTIVATED'
                      AND r.role <> 'CI_LEARNER'
                ) active
                """,
                Integer.class);
        return requireNonNull(active, "active dispatch count is null");
    }

    /** Claims the oldest exact authorized publication plan for one PR. */
    public synchronized Optional<Claim> claimNextPublish(
            String workerId, Duration claimTtl)
    {
        return claimNextPublish(workerId, claimTtl, null, Integer.MAX_VALUE);
    }

    /** Claims only an INITIAL plan for its bounded owner-specific lane. */
    public synchronized Optional<Claim> claimNextInitialPublish(
            String workerId, Duration claimTtl, int capacity)
    {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return claimNextPublish(
                workerId, claimTtl, "INITIAL_PUBLISH", capacity);
    }

    /** Claims only a CI_UPDATE plan for its disjoint owner-specific lane. */
    public synchronized Optional<Claim> claimNextCiUpdatePublish(
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
        return inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
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

    /** Claims one receipt-owned read-only CI observation watch. */
    public synchronized Optional<Claim> claimNextCiObservation(
            String workerId, Duration claimTtl)
    {
        return claimNextCiObservation(workerId, claimTtl, Integer.MAX_VALUE);
    }

    public synchronized Optional<Claim> claimNextCiObservation(
            String workerId, Duration claimTtl, int capacity)
    {
        return ciObservations.claimNext(workerId, claimTtl, capacity);
    }

    /** Claims one isolated optional learner after all current repair work. */
    public synchronized Optional<Claim> claimNextCiLearning(
            String workerId, Duration claimTtl)
    {
        return claimNextCiLearning(
                workerId, claimTtl, Integer.MAX_VALUE);
    }

    public synchronized Optional<Claim> claimNextCiLearning(
            String workerId, Duration claimTtl, int capacity)
    {
        requireText(workerId, "workerId");
        requirePositive(claimTtl, "claimTtl");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        return inTransaction(() -> {
            if (activeDispatchCount() >= capacity) {
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
                    JOIN flow_ci_learning_subject s
                      ON s.operation_id = o.operation_id
                     AND s.subject_id = o.input_ref
                     AND s.subject_digest = o.subject_digest
                    WHERE o.kind = 'RUN_CI_LEARNING'
                      AND o.owner_kind = 'CI_UPDATE_RECEIPT'
                      AND o.owner_id = s.receipt_id
                      AND o.state IN ('READY', 'RETRYABLE')
                      AND d.delivery_state = 'AVAILABLE'
                      AND d.not_before <= ?
                      AND NOT EXISTS (
                          SELECT 1 FROM flow_ci_round r
                          WHERE r.task_id = o.task_id
                            AND r.state IN ('FINAL_RED', 'QUEUED', 'ACTIVE')
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
                        workerId, expiresAt.toEpochMilli(), generation, token,
                        candidate.operationId(), candidate.generation());
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
                            "CI learning ticket claimed without its operation");
                }
                return Optional.of(new Claim(
                        candidate.operationId(), candidate.taskId(),
                        OperationKind.RUN_CI_LEARNING, generation, token,
                        workerId, expiresAt));
            }
            return Optional.empty();
        });
    }

    public synchronized Operation assertCiLearningClaim(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return inTransaction(() -> {
            if (claim.kind() != OperationKind.RUN_CI_LEARNING) {
                throw new IllegalArgumentException(
                        "claim is not a CI learning claim");
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation operation = requireOperation(claim.operationId());
            Integer exact = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_ci_learning_subject s
                    WHERE s.operation_id = ? AND s.subject_id = ?
                      AND s.receipt_id = ? AND s.task_id = ?
                      AND s.subject_digest = ?
                    """,
                    Integer.class,
                    operation.operationId(), operation.inputRef(),
                    operation.ownerId(), operation.taskId(),
                    operation.subjectDigest());
            if (operation.kind() != OperationKind.RUN_CI_LEARNING
                    || !operation.ownerKind().equals("CI_UPDATE_RECEIPT")
                    || requireNonNull(exact,
                            "CI learning subject count is null") != 1) {
                throw new IllegalStateException(
                        "CI learning operation graph is invalid");
            }
            return operation;
        });
    }

    public synchronized void cancelClaimedCiLearning(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        String resultRef = "CI_LEARNING_GREEN_SUPERSEDED";
        inTransaction(() -> {
            assertCiLearningClaim(claim);
            Optional<AgentRun> existing = runForOperation(
                    claim.operationId());
            if (existing.isPresent()) {
                AgentRun run = existing.orElseThrow();
                Integer activated = jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM flow_runtime_agent_process_attempt
                        WHERE run_id = ? AND state <> 'RESERVED'
                        """,
                        Integer.class, run.runId());
                if (run.state() != RunState.QUEUED
                        || requireNonNull(activated,
                            "activated learner count is null") != 0) {
                    throw new IllegalStateException(
                            "started CI learning requires stopped finalization");
                }
                AgentSession session = requireSession(run.sessionId());
                Instant now = clock.instant();
                String resultId = stableId(
                        "agent-result", run.runId());
                String stopProof = stableId(
                        "never-launched-ci-learning-stop", run.runId());
                int resultStored = jdbc.update(
                        """
                        INSERT INTO flow_runtime_agent_result (
                            result_id, run_id, terminal_outcome,
                            final_content, error_ref, stop_proof_ref, stored_at
                        ) VALUES (?, ?, 'CANCELED', NULL, ?, ?, ?)
                        """,
                        resultId, run.runId(), resultRef, stopProof,
                        now.toEpochMilli());
                int runClosed = jdbc.update(
                        "UPDATE flow_runtime_agent_run SET state = 'CANCELED', "
                                + "failure_reason_code = ?, completed_at = ? "
                                + "WHERE run_id = ? AND state = 'QUEUED'",
                        resultRef, now.toEpochMilli(), run.runId());
                int sessionClosed = jdbc.update(
                        "UPDATE flow_runtime_agent_session "
                                + "SET state = 'CLOSED', close_reason = ?, "
                                + "updated_at = ? WHERE session_id = ? "
                                + "AND state = 'RUNNING' AND last_run_id = ?",
                        resultRef, now.toEpochMilli(), session.sessionId(),
                        run.runId());
                int completionStored = jdbc.update(
                        """
                        INSERT INTO flow_ci_learning_completion (
                            operation_id, run_id, result_id, state, lesson_id,
                            reason_code, completed_at
                        ) VALUES (?, ?, ?, 'MISSED', NULL, ?, ?)
                        """,
                        claim.operationId(), run.runId(), resultId, resultRef,
                        now.toEpochMilli());
                if (resultStored != 1 || runClosed != 1
                        || sessionClosed != 1 || completionStored != 1) {
                    throw new StaleOwnerRevisionException(
                            "never-launched CI learner changed during cancel");
                }
                settleDispatch(
                        claim.operationId(), OperationState.CANCELED,
                        resultId);
                return Boolean.TRUE;
            }
            settleDispatch(
                    claim.operationId(), OperationState.CANCELED, resultRef);
            return Boolean.TRUE;
        });
    }

    public synchronized boolean ciLearningCanceledReplay(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        if (claim.kind() != OperationKind.RUN_CI_LEARNING) {
            return false;
        }
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.operation_id = ? AND o.task_id = ?
                  AND o.kind = 'RUN_CI_LEARNING'
                  AND o.owner_kind = 'CI_UPDATE_RECEIPT'
                  AND o.state = 'CANCELED'
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ? AND d.claim_token = ?
                  AND d.claim_owner = ?
                  AND (
                    (o.result_ref = 'CI_LEARNING_GREEN_SUPERSEDED'
                     AND NOT EXISTS (
                        SELECT 1 FROM flow_runtime_agent_run r0
                        WHERE r0.operation_id = o.operation_id
                     ))
                    OR EXISTS (
                        SELECT 1
                        FROM flow_runtime_agent_run r
                        JOIN flow_runtime_agent_session s
                          ON s.session_id = r.session_id
                         AND s.last_run_id = r.run_id
                        JOIN flow_runtime_agent_result x
                          ON x.run_id = r.run_id
                         AND x.result_id = o.result_ref
                        JOIN flow_ci_learning_completion c
                          ON c.operation_id = o.operation_id
                         AND c.run_id = r.run_id
                         AND c.result_id = x.result_id
                        WHERE r.operation_id = o.operation_id
                          AND r.role = 'CI_LEARNER'
                          AND r.state = 'CANCELED'
                          AND r.failure_reason_code =
                                'CI_LEARNING_GREEN_SUPERSEDED'
                          AND s.role = 'CI_LEARNER'
                          AND s.state = 'CLOSED'
                          AND s.close_reason =
                                'CI_LEARNING_GREEN_SUPERSEDED'
                          AND x.terminal_outcome = 'CANCELED'
                          AND x.error_ref =
                                'CI_LEARNING_GREEN_SUPERSEDED'
                          AND x.stop_proof_ref IS NOT NULL
                          AND c.state = 'MISSED' AND c.lesson_id IS NULL
                          AND c.reason_code =
                                'CI_LEARNING_GREEN_SUPERSEDED'
                    )
                  )
                """,
                Integer.class, claim.operationId(), claim.taskId(),
                claim.generation(), claim.claimToken(), claim.workerId());
        return requireNonNull(exact,
                "CI learning cancellation count is null") == 1;
    }

    public synchronized Operation assertCiObservationClaim(Claim claim)
    {
        return ciObservations.assertClaim(claim);
    }

    /** Exact immutable receipt/PR identity behind a current poll claim. */
    public synchronized CiObservationSubject ciObservationSubject(Claim claim)
    {
        return ciObservations.subject(claim);
    }

    /** Rearms the same read-only watch after one complete poll. */
    public synchronized void rearmCiObservation(
            Claim claim, String resultRef, Instant notBefore)
    {
        ciObservations.rearm(claim, resultRef, notBefore);
    }

    /** Settles one objectively stale/terminal read-only watch. */
    public synchronized void cancelCiObservation(Claim claim, String resultRef)
    {
        ciObservations.cancel(claim, resultRef);
    }

    /** Replays one exact durable owner cancellation before claim renewal. */
    public synchronized Optional<String> canceledCiObservationResult(
            Claim claim)
    {
        return ciObservations.canceledResult(claim);
    }

    /** Exact response-loss replay for a completed poll generation. */
    public synchronized void assertCiObservationRearmReplay(
            Claim claim, String resultRef)
    {
        ciObservations.assertRearmReplay(claim, resultRef);
    }

    /** Redrives an expired read-only observation; it has no process/effect. */
    public synchronized void recoverExpiredCiObservation(
            String operationId, long generation)
    {
        ciObservations.recoverExpired(operationId, generation);
    }

    /** Requires one current exact PUBLISH claim. */
    public synchronized Operation assertPublishClaim(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return inTransaction(() -> {
            if (claim.kind() != OperationKind.PUBLISH) {
                throw new IllegalArgumentException(
                        "claim is not a publication claim");
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation operation = requireOperation(claim.operationId());
            if (operation.kind() != OperationKind.PUBLISH
                    || !operation.ownerKind().equals("GITHUB_EFFECT_PLAN")
                    || !operation.ownerId().equals(operation.inputRef())) {
                throw new IllegalStateException(
                        "publication operation graph is invalid");
            }
            return operation;
        });
    }

    public synchronized PublishExecutionHandle mintPublishExecutionHandle(
            Claim claim, String attemptId)
    {
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        PublishExecutionReservation reservation =
                reservePublishExecutionHandle(claim, attemptId);
        return mintPublishExecutionHandle(
                claim, attemptId, reservation, reservation.tokenDigest());
    }

    public synchronized PublishExecutionReservation reservePublishExecutionHandle(
            Claim claim, String attemptId)
    {
        requireNonNull(claim, "claim is null");
        requireText(attemptId, "attemptId");
        assertPublishClaim(claim);
        return new PublishExecutionReservation(
                claim.operationId(), claim.generation(), attemptId,
                stableId("publish-execution-token", UUID.randomUUID().toString()));
    }

    /** Mints only from the original one-use precursor after owner persistence. */
    public synchronized PublishExecutionHandle mintPublishExecutionHandle(
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

    public synchronized void consumePublishExecutionHandle(
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

    public synchronized Operation assertPublishAttemptResult(
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
        return requireOperation(claim.operationId());
    }

    public synchronized void assertPublishTerminalReplay(
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

    public synchronized void assertPublishAttemptTerminalReplay(
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
    public synchronized void assertInitialStepReceiptReplay(
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
    public synchronized void settleClaimedPublish(
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
        inTransaction(() -> {
            if (handle == null) {
                assertPublishClaim(claim);
            }
            else {
                assertPublishAttemptResult(
                        handle, claim, attemptId, tokenDigest);
            }
            switch (disposition.kind()) {
                case SUCCEEDED -> {
                    settleDispatch(
                            claim.operationId(),
                            OperationState.SUCCEEDED,
                            disposition.resultRef());
                    resumeWaitingReconciliation(claim.taskId());
                }
                case CANCELED -> {
                    settleDispatch(
                            claim.operationId(),
                            OperationState.CANCELED,
                            disposition.resultRef());
                    resumeWaitingReconciliation(claim.taskId());
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
    public synchronized void rearmClaimedPublishStep(
            Claim claim, String stepReceiptId, Instant notBefore)
    {
        requireNonNull(claim, "claim is null");
        requireText(stepReceiptId, "stepReceiptId");
        requireNonNull(notBefore, "notBefore is null");
        inTransaction(() -> {
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
    public synchronized void redriveExpiredPublish(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        inTransaction(() -> {
            Operation operation = requireOperation(operationId);
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
            ExpiredClaim expired = requireExpiredClaim(
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
    public synchronized void redriveExpiredPublishProbeOnly(
            String operationId, long generation, Instant notBefore)
    {
        requireText(operationId, "operationId");
        requireNonNull(notBefore, "notBefore is null");
        inTransaction(() -> {
            Operation operation = requireOperation(operationId);
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
            ExpiredClaim expired = requireExpiredClaim(
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
            if (operation.kind() == OperationKind.PUBLISH
                    || operation.kind() == OperationKind.OBSERVE_CI
                    || operation.kind() == OperationKind.PROVISION_TASK
                    || operation.kind()
                        == OperationKind.RUN_CI_LEARNING) {
                throw new IllegalArgumentException(
                        operation.kind() + " requires owner-specific recovery");
            }
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
                if (expired.kind() == OperationKind.RUN_REVIEWER
                        && expired.processAttemptState()
                                == ProcessAttemptState.RESERVED) {
                    recoverNeverLaunchedClaim(expired);
                    return true;
                }
                if (expired.kind() == OperationKind.RUN_REVIEWER) {
                    quarantineExpiredReviewerProcessAttempt(expired);
                }
                else {
                    quarantineExpiredProcessAttempt(expired);
                }
                return false;
            }
            recoverNeverLaunchedClaim(expired);
            return true;
        });
    }

    /**
     * Renews only the same expired generation after its exact Java thread and
     * completion were durably sealed. No body or provider call is redriven.
     */
    public synchronized StoppedWriterRecovery reviveExpiredStoppedWriter(
            String operationId, long generation, Duration ttl)
    {
        requireText(operationId, "operationId");
        requirePositive(ttl, "ttl");
        return inTransaction(() -> {
            ExpiredClaim expired = requireExpiredClaim(
                    operationId, generation);
            Operation operation = requireOperation(operationId);
            if (expired.processAttemptState() != ProcessAttemptState.STOPPED
                    || (operation.kind() != OperationKind.RUN_CI_FIXER
                        && operation.kind()
                            != OperationKind.RUN_TASK_TURN)) {
                throw new IllegalArgumentException(
                        "claim is not a stopped writer");
            }
            AgentProcessAttempt attempt = requireProcessAttempt(
                    expired.processAttemptId());
            AgentRun run = requireRun(expired.runId());
            if (!attempt.operationId().equals(operationId)
                    || attempt.claimGeneration() != generation
                    || !run.operationId().equals(operationId)
                    || (run.role() != AgentRole.CI_FIXER
                        && run.role() != AgentRole.TASK_AGENT)) {
                throw new IllegalStateException(
                        "stopped writer recovery graph is invalid");
            }
            StoppedAgentCompletion completion = requireStoppedCompletion(
                    attempt.processAttemptId());
            Claim claim = renewExpiredStoppedClaim(
                    operation, generation, ttl);
            WriterFence current = writerFence(operation.taskId())
                    .orElseThrow(() -> new IllegalStateException(
                            "stopped writer lost its fence"));
            if (!current.operationId().equals(operationId)
                    || current.claimGeneration() != generation
                    || !current.claimTokenDigest().equals(
                            claimTokenDigest(claim))) {
                throw new IllegalStateException(
                        "stopped writer fence changed before recovery");
            }
            Instant expiry = claim.expiresAt();
            int renewed = jdbc.update(
                    "UPDATE flow_runtime_writer_lease SET expires_at = ? "
                            + "WHERE task_id = ? AND operation_id = ? "
                            + "AND claim_generation = ? "
                            + "AND claim_token_digest = ?",
                    expiry.toEpochMilli(), current.taskId(), operationId,
                    generation, claimTokenDigest(claim));
            if (renewed != 1) {
                throw new StaleWriterFenceException(
                        "stopped writer fence changed during recovery");
            }
            WriterFence fence = writerFence(operation.taskId())
                    .orElseThrow();
            return new StoppedWriterRecovery(
                    run, claim, fence, completion);
        });
    }

    public synchronized StoppedReviewerRecovery reviveExpiredStoppedReviewer(
            String operationId, long generation, Duration ttl)
    {
        requireText(operationId, "operationId");
        requirePositive(ttl, "ttl");
        return inTransaction(() -> {
            ExpiredClaim expired = requireExpiredClaim(
                    operationId, generation);
            Operation operation = requireOperation(operationId);
            if (expired.processAttemptState() != ProcessAttemptState.STOPPED
                    || operation.kind() != OperationKind.RUN_REVIEWER) {
                throw new IllegalArgumentException(
                        "claim is not a stopped reviewer");
            }
            AgentProcessAttempt attempt = requireProcessAttempt(
                    expired.processAttemptId());
            AgentRun run = requireRun(expired.runId());
            if (!attempt.operationId().equals(operationId)
                    || attempt.claimGeneration() != generation
                    || !run.operationId().equals(operationId)
                    || run.role() != AgentRole.ADVERSARIAL_REVIEWER) {
                throw new IllegalStateException(
                        "stopped reviewer recovery graph is invalid");
            }
            return new StoppedReviewerRecovery(
                    run,
                    renewExpiredStoppedClaim(operation, generation, ttl),
                    requireStoppedCompletion(attempt.processAttemptId()));
        });
    }

    private Claim renewExpiredStoppedClaim(
            Operation operation, long generation, Duration ttl)
    {
        Instant expiresAt = clock.instant().plus(ttl);
        Claim claim = jdbc.query(
                """
                SELECT d.claim_token, d.claim_owner
                FROM flow_runtime_dispatch_ticket d
                WHERE d.operation_id = ? AND d.delivery_state = 'CLAIMED'
                  AND d.claim_generation = ? AND d.claim_expires_at <= ?
                """,
                (result, row) -> new Claim(
                        operation.operationId(), operation.taskId(),
                        operation.kind(), generation,
                        result.getString("claim_token"),
                        result.getString("claim_owner"), expiresAt),
                operation.operationId(), generation,
                clock.instant().toEpochMilli()).stream().findFirst()
                .orElseThrow(() -> new StaleClaimException(
                        "stopped claim is no longer expired"));
        int renewed = jdbc.update(
                "UPDATE flow_runtime_dispatch_ticket "
                        + "SET claim_expires_at = ? "
                        + "WHERE operation_id = ? "
                        + "AND delivery_state = 'CLAIMED' "
                        + "AND claim_generation = ? AND claim_token = ? "
                        + "AND claim_owner = ? AND claim_expires_at <= ?",
                expiresAt.toEpochMilli(), operation.operationId(),
                generation, claim.claimToken(), claim.workerId(),
                clock.instant().toEpochMilli());
        if (renewed != 1) {
            throw new StaleClaimException(
                    "stopped claim changed during recovery");
        }
        return claim;
    }

    /** Owner-specific recovery for an isolated optional learner. */
    public synchronized boolean recoverExpiredCiLearning(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        return inTransaction(() -> {
            Operation operation = requireOperation(operationId);
            if (operation.kind() != OperationKind.RUN_CI_LEARNING) {
                throw new IllegalArgumentException(
                        "operation is not CI learning");
            }
            if (operation.state() == OperationState.RETRYABLE
                    && ticketMatches(operationId, generation, "AVAILABLE")) {
                return true;
            }
            if (operation.state() == OperationState.FAILED
                    && ticketMatches(operationId, generation, "DONE")) {
                assertCiLearningQuarantineReplay(operationId, generation);
                return false;
            }
            ExpiredClaim expired = requireExpiredClaim(
                    operationId, generation);
            if (expired.processAttemptId() == null
                    || expired.processAttemptState()
                        == ProcessAttemptState.RESERVED) {
                recoverNeverLaunchedClaim(expired);
                return true;
            }
            if (expired.processAttemptState()
                    == ProcessAttemptState.STOPPED) {
                throw new IllegalStateException(
                        "STOPPED CI learning requires CI-owner finalization");
            }
            else {
                quarantineExpiredCiLearningAttempt(expired);
            }
            return false;
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

    private void assertCiLearningQuarantineReplay(
            String operationId, long generation)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_runtime_agent_run r
                  ON r.operation_id = o.operation_id
                JOIN flow_runtime_agent_session s
                  ON s.session_id = r.session_id
                 AND s.last_run_id = r.run_id
                JOIN flow_runtime_agent_process_attempt a
                  ON a.run_id = r.run_id
                 AND a.claim_generation = d.claim_generation
                WHERE o.operation_id = ? AND o.kind = 'RUN_CI_LEARNING'
                  AND o.owner_kind = 'CI_UPDATE_RECEIPT'
                  AND o.state = 'FAILED'
                  AND o.result_ref =
                        'CI_LEARNING_PROCESS_QUARANTINED:'
                        || a.process_attempt_id
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ?
                  AND r.role = 'CI_LEARNER' AND r.state = 'FAILED'
                  AND r.failure_reason_code =
                        'CI_LEARNING_PROCESS_QUARANTINED'
                  AND s.role = 'CI_LEARNER' AND s.state = 'CLOSED'
                  AND s.close_reason =
                        'CI_LEARNING_PROCESS_QUARANTINED'
                  AND a.state = 'ACTIVATED'
                  AND a.capability_revoked_at IS NOT NULL
                  AND a.quarantine_reason =
                        'IN_PROCESS_OWNER_UNAVAILABLE'
                  AND a.quarantined_at IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM flow_runtime_agent_result x
                    WHERE x.run_id = r.run_id
                  )
                """,
                Integer.class, operationId, generation);
        if (requireNonNull(exact,
                "CI learning quarantine replay count is null") != 1) {
            throw new IllegalStateException(
                    "CI learning quarantine replay is inconsistent");
        }
    }

    /**
     * Runs the bounded WorkSelector for a claimed reconciliation generation.
     * It materializes at most one current FINAL_RED writer operation.
     */
    public synchronized Optional<Operation> selectNext(Claim claim)
    {
        return selectNext(claim, null);
    }

    /** Selects only INITIAL_TASK and INITIAL reviewer-result continuations. */
    public synchronized Optional<Operation> selectNextInitial(Claim claim)
    {
        return selectNext(claim, GateIntent.INITIAL_PUBLISH);
    }

    private Optional<Operation> selectNext(
            Claim claim, GateIntent exactIntent)
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
            int taskLocked = jdbc.update(
                    "UPDATE flow_runtime_task SET task_id = task_id "
                            + "WHERE task_id = ?",
                    reconciliation.taskId());
            if (taskLocked != 1) {
                throw new StaleOwnerRevisionException(
                        "reconciliation Task is unavailable");
            }
            Task task = requireTask(reconciliation.taskId());
            if (hasNonterminalPublish(task.taskId())) {
                parkReconciliation(reconciliation.operationId());
                return Optional.empty();
            }
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
            AgentSession taskSession = requireSession(task.taskSessionId());
            Integer liveReviewer = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM flow_runtime_operation
                    WHERE task_id = ? AND kind = 'RUN_REVIEWER'
                      AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                    """,
                    Integer.class,
                    task.taskId());
            if (taskSession.state() == SessionState.PARKED_CHILD
                    || requireNonNull(
                            liveReviewer, "live reviewer count is null") > 0) {
                parkReconciliation(reconciliation.operationId());
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
                        WHEN 'CI_FIX_READY' THEN 2
                        WHEN 'FINAL_RED' THEN 3
                        WHEN 'INITIAL_TASK' THEN 4
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
                if (exactIntent == GateIntent.INITIAL_PUBLISH
                        && !isInitialPending(item)) {
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
                          AND NOT EXISTS (
                              SELECT 1 FROM flow_runtime_operation po
                              WHERE po.task_id = flow_runtime_task.task_id
                                AND po.kind = 'PUBLISH'
                                AND po.state IN (
                                    'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                                )
                          )
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

    private boolean isInitialPending(PendingWork item)
    {
        if (item.kind() == PendingKind.INITIAL_TASK) {
            return item.intendedGateKind() == GateIntent.INITIAL_PUBLISH;
        }
        if (item.kind() != PendingKind.AGENT_RESULT_READY
                || item.intendedGateKind() != GateIntent.INITIAL_PUBLISH) {
            return false;
        }
        ReviewerRequest request = reviewerRequestForReviewerRun(
                item.externalKey()).orElse(null);
        AgentResult result = resultForRun(item.externalKey()).orElse(null);
        return request != null && result != null
                && request.intendedGateKind() == GateIntent.INITIAL_PUBLISH
                && Objects.equals(item.agentResultId(), result.resultId())
                && item.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), result.resultId()))
                && item.subjectHead().equals(request.reviewedHeadSha());
    }

    /** Exact owner discriminator used before INITIAL-specific recovery. */
    synchronized boolean ownsCurrentInitialDispatch(Operation operation)
    {
        requireNonNull(operation, "operation is null");
        Task task = requireTask(operation.taskId());
        return switch (operation.kind()) {
            case RECONCILE_TASK -> pendingWork(task.taskId()).stream()
                    .filter(input -> input.selectedByOperationId() == null
                            && input.handledByOperationId() == null
                            && input.terminalReason() == null)
                    .anyMatch(input -> pendingSubjectIsCurrent(task, input)
                            && isInitialPending(input));
            case RUN_TASK_TURN -> pendingWork(task.taskId()).stream()
                    .filter(input -> Objects.equals(
                            input.selectedByOperationId(),
                            operation.operationId()))
                    .filter(input -> input.handledByOperationId() == null
                            && input.terminalReason() == null
                            && operation.inputRef().equals(
                                "inbox:" + input.pendingId()))
                    .anyMatch(input -> pendingSubjectIsCurrent(task, input)
                            && isInitialPending(input)
                            && (operation.ownerKind().equals("TASK")
                                && operation.ownerId().equals(task.taskId())
                                && input.kind() == PendingKind.INITIAL_TASK
                                || operation.ownerKind().equals("AGENT_RUN")
                                && operation.ownerId().equals(
                                    input.externalKey())
                                && input.kind()
                                    == PendingKind.AGENT_RESULT_READY));
            case RUN_REVIEWER -> operation.ownerKind()
                        .equals("REVIEW_REQUEST")
                    && reviewerRequest(operation.ownerId())
                            .filter(request -> request.reviewerOperationId()
                                    .equals(operation.operationId()))
                            .filter(request -> request.intendedGateKind()
                                    == GateIntent.INITIAL_PUBLISH)
                            .isPresent();
            default -> false;
        };
    }

    /**
     * Mechanically inspects one selected Task-turn input outside the owner
     * transaction. The returned token is private-construction authority; the
     * public {@link WorktreeSnapshot} is deliberately not accepted here.
     */
    public PreparedTaskWriterAdmission prepareTaskWriterAdmission(
            Claim claim,
            Path programOwnedRepositoryRoot,
            String expectedPendingId,
            String expectedChangeSetRevisionId,
            String expectedRemoteHead)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireText(expectedPendingId, "expectedPendingId");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireObjectId(expectedRemoteHead, "expectedRemoteHead");
        TaskAdmissionSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() -> taskAdmissionSnapshot(
                    claim,
                    expectedPendingId,
                    expectedChangeSetRevisionId,
                    expectedRemoteHead));
        }
        Inspection inspection = worktreeInspector.inspect(
                programOwnedRepositoryRoot,
                snapshot.worktree(),
                snapshot.branchName(),
                snapshot.baseSha(),
                snapshot.headSha());
        if (!inspection.headSha().equals(snapshot.headSha())
                || !inspection.headTreeDigest().equals(
                        snapshot.headTreeDigest())
                || !inspection.baseToHeadDiffDigest().equals(
                        snapshot.diffDigest())) {
            throw new MutationRejectedException(
                    "inspected Task input differs from its immutable change set");
        }
        return new PreparedTaskWriterAdmission(claim, snapshot, inspection);
    }

    /** Mechanically inspects one selected INITIAL Task input. */
    public PreparedInitialTaskAdmission prepareInitialTaskAdmission(
            Claim claim, Path programOwnedRepositoryRoot)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        InitialTaskAdmissionSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() ->
                    initialTaskAdmissionSnapshot(claim));
        }
        Inspection inspection = worktreeInspector.inspect(
                programOwnedRepositoryRoot,
                snapshot.worktree(),
                snapshot.branchName(),
                snapshot.baseSha(),
                snapshot.headSha());
        if (!inspection.headSha().equals(snapshot.headSha())
                || (snapshot.headTreeDigest() != null
                    && !inspection.headTreeDigest().equals(
                            snapshot.headTreeDigest()))
                || (snapshot.diffDigest() != null
                    && !inspection.baseToHeadDiffDigest().equals(
                            snapshot.diffDigest()))
                || inspection.differsFromBase()
                        != snapshot.differsFromBase()) {
            throw new MutationRejectedException(
                    "inspected INITIAL Task input changed");
        }
        return new PreparedInitialTaskAdmission(
                claim, snapshot, inspection);
    }

    /** Atomically consumes one inspected INITIAL input into a fence and run. */
    public synchronized TaskWriterStart startInspectedInitialTaskWriter(
            Claim claim,
            PreparedInitialTaskAdmission prepared,
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
            if (!sameClaimAuthority(prepared.claim, claim)
                    || !prepared.snapshot.equals(
                            initialTaskAdmissionSnapshot(claim))) {
                throw new StaleOwnerRevisionException(
                        "INITIAL Task admission changed after inspection");
            }
            WorktreeSnapshot snapshot = new WorktreeSnapshot(
                    prepared.inspection.headSha(),
                    prepared.inspection.headTreeDigest(),
                    "initial-task:" + prepared.snapshot.pendingId());
            WriterFence fence = acquireWriterLease(
                    claim, AgentRole.TASK_AGENT, snapshot, leaseTtl, true);
            AgentRun run = startWriterAgent(
                    claim, fence, promptManifestRef, capabilitySetRef);
            return new TaskWriterStart(fence, run);
        });
    }

    /** Atomically consumes one inspected admission into a fence and run. */
    public synchronized TaskWriterStart startInspectedTaskWriter(
            Claim claim,
            PreparedTaskWriterAdmission prepared,
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
                        "Task admission claim authority changed");
            }
            TaskAdmissionSnapshot current = taskAdmissionSnapshot(
                    claim,
                    prepared.snapshot.pendingId(),
                    prepared.snapshot.changeSetRevisionId(),
                    prepared.snapshot.remoteHead());
            if (!current.equals(prepared.snapshot)) {
                throw new StaleOwnerRevisionException(
                        "Task admission subject changed after inspection");
            }
            WorktreeSnapshot snapshot = new WorktreeSnapshot(
                    prepared.inspection.headSha(),
                    prepared.inspection.headTreeDigest(),
                    "task-change-set:" + current.changeSetRevisionId());
            WriterFence fence = acquireWriterLease(
                    claim,
                    AgentRole.TASK_AGENT,
                    snapshot,
                    leaseTtl,
                    true);
            AgentRun run = startWriterAgent(
                    claim, fence, promptManifestRef, capabilitySetRef);
            return new TaskWriterStart(fence, run);
        });
    }

    /**
     * Re-inspects the exact current immutable Task change set for the terminal
     * reviewer request. This slow Git read occurs before the request CAS.
     */
    public PreparedReviewerRequest prepareReviewerRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId,
            CiFixReviewOrigin origin,
            LocalChecks.ReviewerEvidence reviewerEvidence)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireNonNull(origin, "origin is null");
        requireNonNull(reviewerEvidence, "reviewerEvidence is null");
        List<String> boundedCheckRunRefs = validateReviewerCheckRunRefs(
                reviewerEvidence.checkRunRefs());
        ReviewRequestSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() -> reviewRequestSnapshot(
                    runId,
                    claim,
                    fence,
                    expectedChangeSetRevisionId,
                    origin));
            inTransaction(() -> {
                if (!reviewerEvidence.taskId().equals(snapshot.taskId())
                        || !reviewerEvidence.changeSetRevisionId().equals(
                                snapshot.changeSetRevisionId())
                        || reviewerEvidence.gateKind()
                                != snapshot.intendedGateKind()) {
                    throw new StaleOwnerRevisionException(
                            "review local-check evidence subject changed");
                }
                reviewerEvidence.assertCurrentForReservation();
                return Boolean.TRUE;
            });
        }
        Inspection inspection = worktreeInspector.inspect(
                programOwnedRepositoryRoot,
                snapshot.worktree(),
                snapshot.branchName(),
                snapshot.baseHeadSha(),
                snapshot.reviewedHeadSha());
        if (!inspection.headSha().equals(snapshot.reviewedHeadSha())
                || !inspection.headTreeDigest().equals(
                        snapshot.headTreeDigest())
                || !inspection.baseToHeadDiffDigest().equals(
                        snapshot.diffDigest())) {
            throw new MutationRejectedException(
                    "review subject differs from its immutable change set");
        }
        return new PreparedReviewerRequest(
                claim,
                fence,
                snapshot,
                inspection,
                programOwnedRepositoryRoot.toAbsolutePath()
                        .normalize().toString(),
                origin,
                reviewerEvidence,
                reviewerEvidence.policyRevisionId(),
                boundedCheckRunRefs);
    }

    /** Re-inspects an unpublished INITIAL change set for exact review. */
    public PreparedReviewerRequest prepareInitialReviewerRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId,
            LocalChecks.ReviewerEvidence reviewerEvidence)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireNonNull(reviewerEvidence, "reviewerEvidence is null");
        List<String> boundedCheckRunRefs = validateReviewerCheckRunRefs(
                reviewerEvidence.checkRunRefs());
        ReviewRequestSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() -> initialReviewRequestSnapshot(
                    runId, claim, fence, expectedChangeSetRevisionId));
            inTransaction(() -> {
                if (!reviewerEvidence.taskId().equals(snapshot.taskId())
                        || !reviewerEvidence.changeSetRevisionId().equals(
                                snapshot.changeSetRevisionId())
                        || reviewerEvidence.gateKind()
                                != GateIntent.INITIAL_PUBLISH) {
                    throw new StaleOwnerRevisionException(
                            "INITIAL review checks changed subject");
                }
                reviewerEvidence.assertCurrentForReservation();
                return Boolean.TRUE;
            });
        }
        Inspection inspection = worktreeInspector.inspect(
                programOwnedRepositoryRoot,
                snapshot.worktree(),
                snapshot.branchName(),
                snapshot.baseHeadSha(),
                snapshot.reviewedHeadSha());
        if (!inspection.headSha().equals(snapshot.reviewedHeadSha())
                || !inspection.headTreeDigest().equals(
                        snapshot.headTreeDigest())
                || !inspection.baseToHeadDiffDigest().equals(
                        snapshot.diffDigest())) {
            throw new MutationRejectedException(
                    "INITIAL review subject differs from its change set");
        }
        return new PreparedReviewerRequest(
                claim, fence, snapshot, inspection,
                programOwnedRepositoryRoot.toAbsolutePath()
                        .normalize().toString(),
                null, reviewerEvidence,
                reviewerEvidence.policyRevisionId(),
                boundedCheckRunRefs);
    }

    /**
     * Atomically persists the immutable request and makes it the durable
     * terminal seal for the parent process capability. The reviewer operation
     * remains WAITING until the exact parent thread is durably stopped.
     */
    synchronized ReviewerRequest reserveReviewerRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            String processAttemptId,
            String capabilityId,
            PreparedReviewerRequest prepared)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(processAttemptId, "processAttemptId");
        requireText(capabilityId, "capabilityId");
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            if (!sameClaimAuthority(prepared.claim, claim)
                    || !sameFenceAuthority(prepared.fence, fence)) {
                throw new StaleCapabilityException(
                        "review request authority changed after inspection");
            }
            assertInProcessWriterToolCapability(
                    runId, claim, fence, capabilityId);
            AgentProcessAttempt process = requireProcessAttempt(
                    processAttemptId);
            if (!process.runId().equals(runId)
                    || !process.capabilityId().equals(capabilityId)) {
                throw new StaleCapabilityException(
                        "review request used another process capability");
            }
            ReviewRequestSnapshot current = prepared.snapshot
                    .intendedGateKind() == GateIntent.INITIAL_PUBLISH
                    ? initialReviewRequestSnapshot(
                            runId, claim, fence,
                            prepared.snapshot.changeSetRevisionId())
                    : reviewRequestSnapshot(
                            runId,
                            claim,
                            fence,
                            requireRun(runId).inputChangeSetRevisionId(),
                            prepared.origin);
            if (!current.equals(prepared.snapshot)
                    || !prepared.inspection.headSha().equals(
                            current.reviewedHeadSha())
                    || !prepared.inspection.headTreeDigest().equals(
                            current.headTreeDigest())
                    || !prepared.inspection.baseToHeadDiffDigest().equals(
                            current.diffDigest())) {
                throw new StaleOwnerRevisionException(
                        "review request subject changed after inspection");
            }
            prepared.reviewerEvidence.assertCurrentForReservation();
            Optional<ReviewerRequest> existing = reviewerRequestForParentRun(
                    runId);
            String requestId = stableId(
                    "reviewer-request",
                    current.taskId(),
                    runId,
                    current.changeSetRevisionId());
            String reviewerSubject = stableId(
                    "reviewer-subject",
                    requestId,
                    current.taskId(),
                    Long.toString(current.taskEpoch()),
                    current.baseHeadSha(),
                    current.reviewedHeadSha(),
                    nullableId(current.remoteHeadSha()),
                    nullableId(current.originCiFixPendingId()),
                    nullableId(current.originCiFixSourceKind()),
                    nullableId(current.originCiFixSourceId()),
                    current.changeSetRevisionId(),
                    prepared.localCheckPolicyRevisionId,
                    current.headTreeDigest(),
                    current.diffDigest(),
                    prepared.repositoryRoot,
                    String.join("\u0000", prepared.checkRunRefs));
            String reviewerOperationId = stableId(
                    "operation",
                    "REVIEW_REQUEST",
                    requestId,
                    OperationKind.RUN_REVIEWER.name(),
                    reviewerSubject);
            ReviewerRequest requested = new ReviewerRequest(
                    requestId,
                    current.taskId(),
                    current.operationId(),
                    runId,
                    reviewerOperationId,
                    prepared.repositoryRoot,
                    current.baseHeadSha(),
                    current.reviewedHeadSha(),
                    current.remoteHeadSha(),
                    current.originCiFixPendingId(),
                    current.originCiFixSourceKind(),
                    current.originCiFixSourceId(),
                    current.changeSetRevisionId(),
                    prepared.localCheckPolicyRevisionId,
                    current.headTreeDigest(),
                    current.diffDigest(),
                    prepared.checkRunRefs,
                    current.intendedGateKind(),
                    clock.instant());
            if (existing.isPresent()) {
                assertExactReviewerRequest(existing.get(), requested);
                assertTaskTerminalRequest(
                        runId,
                        TaskTerminalRequestKind.REVIEWER,
                        requestId);
                return existing.get();
            }
            insertOperationAndTicket(
                    reviewerOperationId,
                    "REVIEW_REQUEST",
                    requestId,
                    current.taskId(),
                    OperationKind.RUN_REVIEWER,
                    reviewerSubject,
                    "review-request:" + requestId,
                    null,
                    TASK_TURN_PRIORITY,
                    requested.createdAt());
            int waiting = jdbc.update(
                    """
                    UPDATE flow_runtime_operation SET state = 'WAITING'
                    WHERE operation_id = ? AND state = 'READY'
                    """,
                    reviewerOperationId);
            int inserted = jdbc.update(
                    """
                    INSERT INTO flow_runtime_reviewer_request (
                        request_id, task_id, parent_operation_id,
                        parent_run_id, reviewer_operation_id, repository_root,
                        base_head_sha, reviewed_head_sha, remote_head_sha,
                        origin_ci_fix_pending_id, origin_ci_fix_source_kind,
                        origin_ci_fix_source_id,
                        change_set_revision_id, local_check_policy_revision_id,
                        head_tree_digest, diff_digest, intended_gate_kind,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    requestId,
                    current.taskId(),
                    current.operationId(),
                    runId,
                    reviewerOperationId,
                    prepared.repositoryRoot,
                    current.baseHeadSha(),
                    current.reviewedHeadSha(),
                    current.remoteHeadSha(),
                    current.originCiFixPendingId(),
                    current.originCiFixSourceKind(),
                    current.originCiFixSourceId(),
                    current.changeSetRevisionId(),
                    prepared.localCheckPolicyRevisionId,
                    current.headTreeDigest(),
                    current.diffDigest(),
                    current.intendedGateKind().name(),
                    requested.createdAt().toEpochMilli());
            reserveTaskTerminalRequest(
                    runId,
                    TaskTerminalRequestKind.REVIEWER,
                    requestId,
                    requested.createdAt());
            for (int index = 0; index < prepared.checkRunRefs.size(); index++) {
                jdbc.update(
                        """
                        INSERT INTO flow_runtime_reviewer_check_ref (
                            request_id, ordinal, check_run_ref
                        ) VALUES (?, ?, ?)
                        """,
                        requestId,
                        index,
                        prepared.checkRunRefs.get(index));
            }
            if (waiting != 1 || inserted != 1) {
                throw new StaleOwnerRevisionException(
                        "reviewer request changed during reservation");
            }
            return requireReviewerRequest(requestId);
        });
    }

    synchronized ReviewerRequest replayReviewerRequest(
            String runId,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId,
            CiFixReviewOrigin origin,
            String localCheckPolicyRevisionId,
            List<String> checkRunRefs)
    {
        requireText(runId, "runId");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireNonNull(origin, "origin is null");
        requireText(localCheckPolicyRevisionId,
                "localCheckPolicyRevisionId");
        List<String> boundedCheckRunRefs = validateReviewerCheckRunRefs(
                checkRunRefs);
        ReviewerRequest request = reviewerRequestForParentRun(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "reviewer request is not durable"));
        String normalizedRoot = programOwnedRepositoryRoot.toAbsolutePath()
                .normalize().toString();
        AgentRun run = requireRun(runId);
        if (!request.repositoryRoot().equals(normalizedRoot)
                || !Objects.equals(run.inputChangeSetRevisionId(),
                        expectedChangeSetRevisionId)
                || !request.originCiFixPendingId().equals(
                        origin.pendingId())
                || !request.originCiFixSourceKind().equals(
                        origin.sourceKind().name())
                || !request.originCiFixSourceId().equals(origin.sourceId())
                || !request.localCheckPolicyRevisionId().equals(
                        localCheckPolicyRevisionId)
                || !request.checkRunRefs().equals(boundedCheckRunRefs)) {
            throw new IllegalStateException(
                    "reviewer request replay changed terminal arguments");
        }
        return request;
    }

    synchronized ReviewerRequest replayInitialReviewerRequest(
            String runId,
            Path programOwnedRepositoryRoot,
            String expectedChangeSetRevisionId,
            String localCheckPolicyRevisionId,
            List<String> checkRunRefs)
    {
        requireText(runId, "runId");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireText(expectedChangeSetRevisionId,
                "expectedChangeSetRevisionId");
        requireText(localCheckPolicyRevisionId,
                "localCheckPolicyRevisionId");
        List<String> boundedCheckRunRefs = validateReviewerCheckRunRefs(
                checkRunRefs);
        ReviewerRequest request = reviewerRequestForParentRun(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "INITIAL reviewer request is not durable"));
        if (!request.repositoryRoot().equals(
                    programOwnedRepositoryRoot.toAbsolutePath()
                            .normalize().toString())
                || request.intendedGateKind()
                        != GateIntent.INITIAL_PUBLISH
                || request.remoteHeadSha() != null
                || request.originCiFixPendingId() != null
                || request.originCiFixSourceKind() != null
                || request.originCiFixSourceId() != null
                || !request.changeSetRevisionId().equals(
                        expectedChangeSetRevisionId)
                || !request.localCheckPolicyRevisionId().equals(
                        localCheckPolicyRevisionId)
                || !request.checkRunRefs().equals(boundedCheckRunRefs)) {
            throw new IllegalStateException(
                    "INITIAL reviewer replay changed terminal arguments");
        }
        return request;
    }

    private static List<String> validateReviewerCheckRunRefs(
            List<String> checkRunRefs)
    {
        requireNonNull(checkRunRefs, "checkRunRefs is null");
        if (checkRunRefs.size() > 64
                || checkRunRefs.stream().anyMatch(
                        value -> value == null || value.isBlank()
                                || value.length() > 1024)
                || checkRunRefs.stream().distinct().count()
                        != checkRunRefs.size()) {
            throw new IllegalArgumentException(
                    "review check refs must be unique bounded text");
        }
        return List.copyOf(checkRunRefs);
    }

    /** Acquires or idempotently returns the Task's sole live writer fence. */
    public synchronized WriterFence acquireWriterLease(
            Claim claim,
            AgentRole holderKind,
            WorktreeSnapshot snapshot,
            Duration leaseTtl)
    {
        return acquireWriterLease(
                claim, holderKind, snapshot, leaseTtl, false);
    }

    private WriterFence acquireWriterLease(
            Claim claim,
            AgentRole holderKind,
            WorktreeSnapshot snapshot,
            Duration leaseTtl,
            boolean inspectedTaskAdmission)
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
            if (operation.kind() == OperationKind.RUN_TASK_TURN
                    && !inspectedTaskAdmission) {
                throw new MutationRejectedException(
                        "Task turns require inspected admission");
            }
            Task task = requireTask(operation.taskId());
            if (task.status() != TaskStatus.ACTIVE
                    || task.waitingMutationStateRef() != null
                    || hasNonterminalPublish(task.taskId())
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
                    SET claim_expires_at = CASE
                        WHEN claim_expires_at > ? THEN claim_expires_at
                        ELSE ? END
                    WHERE operation_id = ? AND claim_generation = ?
                      AND claim_token = ? AND claim_owner = ?
                      AND delivery_state = 'CLAIMED'
                      AND claim_expires_at > ?
                    """,
                    requestedExpiry.toEpochMilli(),
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
                    SET expires_at = CASE
                        WHEN expires_at > ? THEN expires_at ELSE ? END
                    WHERE task_id = ? AND operation_id = ?
                      AND task_epoch = ? AND fencing_token = ?
                      AND claim_generation = ? AND claim_token_digest = ?
                      AND expires_at > ?
                    """,
                    expiresAt.toEpochMilli(),
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
            Instant storedExpiry = writerFence(fence.taskId())
                    .orElseThrow().expiresAt();
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
                    storedExpiry);
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

    /** Adopts only the first nonempty INITIAL Task change set. */
    public ChangeSetRevision adoptInitialChangeSet(
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        AgentRun run = runForOperation(claim.operationId()).orElseThrow();
        if (run.role() != AgentRole.TASK_AGENT
                || run.wakeKind() != WakeKind.INITIAL_TASK
                || run.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || run.inputChangeSetRevisionId() != null) {
            throw new MutationRejectedException(
                    "first adoption requires the exact INITIAL Task run");
        }
        return adoptChangeSet(
                claim, fence, programOwnedRepositoryRoot, null);
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
            PendingWork taskInput = role == AgentRole.TASK_AGENT
                    ? selectedInput(operation)
                    : null;
            String inputRemoteHead = taskInput != null
                            && taskInput.intendedGateKind()
                                == GateIntent.CI_UPDATE
                    ? requirePullRequest(task.prId()).currentRemoteHead()
                    : null;
            if (taskInput != null
                    && taskInput.intendedGateKind() == GateIntent.CI_UPDATE
                    && inputRemoteHead == null) {
                throw new MutationRejectedException(
                        "CI-update Task run has no current remote head");
            }
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_run (
                        run_id, operation_id, session_id, role, head_sha,
                        prompt_manifest_ref, capability_set_ref, input_ref,
                        input_change_set_revision_id, input_remote_head_sha,
                        wake_kind,
                        intended_gate_kind, state, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                    """,
                    runId,
                    operation.operationId(),
                    session.sessionId(),
                    role.name(),
                    task.currentHeadSha(),
                    promptManifestRef,
                    capabilitySetRef,
                    operation.inputRef(),
                    role == AgentRole.TASK_AGENT
                            ? task.currentChangeSetRevisionId() : null,
                    inputRemoteHead,
                    taskInput == null ? null : taskInput.kind().name(),
                    taskInput == null ? null
                            : taskInput.intendedGateKind().name(),
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

    /** Starts or idempotently returns the isolated one-shot CI learner. */
    public synchronized CiLearningStart startCiLearningAgent(
            Claim claim,
            String promptManifestRef,
            String capabilitySetRef)
    {
        requireNonNull(claim, "claim is null");
        requireText(promptManifestRef, "promptManifestRef");
        requireText(capabilitySetRef, "capabilitySetRef");
        return inTransaction(() -> {
            Operation operation = assertCiLearningClaim(claim);
            Optional<AgentRun> existing = runForOperation(
                    operation.operationId());
            if (existing.isPresent()) {
                AgentRun run = existing.orElseThrow();
                AgentSession session = requireSession(run.sessionId());
                if (run.role() != AgentRole.CI_LEARNER
                        || session.role() != AgentRole.CI_LEARNER
                        || !run.promptManifestRef().equals(promptManifestRef)
                        || !run.capabilitySetRef().equals(capabilitySetRef)
                        || !run.inputRef().equals(operation.inputRef())
                        || !run.operationId().equals(operation.operationId())
                        || !run.runId().equals(session.lastRunId())
                        || (run.state() != RunState.QUEUED
                            && run.state() != RunState.RUNNING)
                        || session.state() != SessionState.RUNNING) {
                    throw new IllegalStateException(
                            "CI learner start replay changed identity");
                }
                return new CiLearningStart(session, run);
            }
            String head = jdbc.queryForObject(
                    "SELECT published_head FROM flow_ci_learning_subject "
                            + "WHERE subject_id = ? AND operation_id = ?",
                    String.class, operation.inputRef(),
                    operation.operationId());
            Instant now = clock.instant();
            String sessionId = stableId(
                    "ci-learning-session", operation.operationId());
            String runId = stableId("agent-run", operation.operationId());
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_session (
                        session_id, task_id, role, state, last_run_id,
                        created_at, updated_at
                    ) VALUES (?, ?, 'CI_LEARNER', 'RUNNING', ?, ?, ?)
                    """,
                    sessionId, operation.taskId(), runId,
                    now.toEpochMilli(), now.toEpochMilli());
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_run (
                        run_id, operation_id, session_id, role, head_sha,
                        prompt_manifest_ref, capability_set_ref, input_ref,
                        state, created_at
                    ) VALUES (?, ?, ?, 'CI_LEARNER', ?, ?, ?, ?, 'QUEUED', ?)
                    """,
                    runId, operation.operationId(), sessionId, head,
                    promptManifestRef, capabilitySetRef,
                    operation.inputRef(), now.toEpochMilli());
            return new CiLearningStart(
                    requireSession(sessionId), requireRun(runId));
        });
    }

    public synchronized AgentProcessAttempt reserveInProcessCiLearningAttempt(
            String runId, Claim claim)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        return inTransaction(() -> {
            Operation operation = assertCiLearningClaim(claim);
            AgentRun run = requireRun(runId);
            if (!run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.CI_LEARNER
                    || run.state() != RunState.QUEUED) {
                throw new StaleClaimException(
                        "CI learner run is not reservable");
            }
            String attemptId = stableId(
                    "process-attempt", runId,
                    Long.toString(claim.generation()));
            Optional<AgentProcessAttempt> existing = processAttempt(attemptId);
            if (existing.isPresent()) {
                AgentProcessAttempt attempt = existing.orElseThrow();
                if (!attempt.runId().equals(runId)
                        || !attempt.operationId().equals(claim.operationId())
                        || attempt.claimGeneration() != claim.generation()
                        || !attempt.claimTokenDigest().equals(
                                claimTokenDigest(claim))) {
                    throw new IllegalStateException(
                            "CI learner reservation replay changed identity");
                }
                return attempt;
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
                    attemptId, runId, claim.operationId(), claim.generation(),
                    claimTokenDigest(claim),
                    stableId("ci-learning-execution", runId,
                            Long.toString(claim.generation())),
                    stableId("ci-learning-capability", runId,
                            Long.toString(claim.generation())),
                    now.toEpochMilli());
            return requireProcessAttempt(attemptId);
        });
    }

    public synchronized AgentRun activateInProcessCiLearningAttempt(
            String processAttemptId,
            Claim claim,
            long jvmPid,
            Instant jvmStartedAt,
            long threadId,
            String threadName)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        requireNonNull(jvmStartedAt, "jvmStartedAt is null");
        requireText(threadName, "threadName");
        if (jvmPid <= 0 || threadId <= 0) {
            throw new IllegalArgumentException(
                    "CI learner process identity must be positive");
        }
        return inTransaction(() -> {
            assertCiLearningClaim(claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
            AgentRun run = requireRun(attempt.runId());
            if (!attempt.operationId().equals(claim.operationId())
                    || attempt.claimGeneration() != claim.generation()
                    || !attempt.claimTokenDigest().equals(
                            claimTokenDigest(claim))
                    || run.role() != AgentRole.CI_LEARNER) {
                throw new StaleClaimException(
                        "CI learner process belongs to another claim");
            }
            if (attempt.state() == ProcessAttemptState.ACTIVATED) {
                if (!Long.valueOf(jvmPid).equals(attempt.jvmPid())
                        || !jvmStartedAt.equals(attempt.jvmStartedAt())
                        || !Long.valueOf(threadId).equals(attempt.threadId())
                        || !threadName.equals(attempt.threadName())
                        || run.state() != RunState.RUNNING) {
                    throw new IllegalStateException(
                            "CI learner activation replay changed identity");
                }
                return run;
            }
            if (attempt.state() != ProcessAttemptState.RESERVED
                    || run.state() != RunState.QUEUED) {
                throw new IllegalStateException(
                        "CI learner process cannot be activated");
            }
            Instant now = clock.instant();
            int attemptUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET state = 'ACTIVATED', jvm_pid = ?, jvm_started_at = ?,
                        thread_id = ?, thread_name = ?, activated_at = ?
                    WHERE process_attempt_id = ? AND state = 'RESERVED'
                    """,
                    jvmPid, jvmStartedAt.toEpochMilli(), threadId, threadName,
                    now.toEpochMilli(), processAttemptId);
            int runUpdated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_run
                    SET state = 'RUNNING', started_at = COALESCE(started_at, ?)
                    WHERE run_id = ? AND state = 'QUEUED'
                    """,
                    now.toEpochMilli(), run.runId());
            if (attemptUpdated != 1 || runUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "CI learner changed during activation");
            }
            return requireRun(run.runId());
        });
    }

    public synchronized void assertInProcessCiLearningToolCapability(
            String runId, Claim claim, String capabilityId)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireText(capabilityId, "capabilityId");
        inTransaction(() -> {
            assertCiLearningClaim(claim);
            Integer exact = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_agent_process_attempt a
                    JOIN flow_runtime_agent_run r ON r.run_id = a.run_id
                    JOIN flow_runtime_operation o
                      ON o.operation_id = r.operation_id
                    WHERE a.run_id = ? AND a.operation_id = ?
                      AND a.claim_generation = ?
                      AND a.claim_token_digest = ?
                      AND a.capability_id = ? AND a.state = 'ACTIVATED'
                      AND a.capability_revoked_at IS NULL
                      AND a.quarantine_reason IS NULL
                      AND r.role = 'CI_LEARNER' AND r.state = 'RUNNING'
                      AND o.kind = 'RUN_CI_LEARNING'
                      AND o.owner_kind = 'CI_UPDATE_RECEIPT'
                    """,
                    Integer.class, runId, claim.operationId(),
                    claim.generation(), claimTokenDigest(claim), capabilityId);
            if (requireNonNull(exact,
                    "CI learner capability count is null") != 1) {
                throw new StaleCapabilityException(
                        "CI learner capability is stale or revoked");
            }
            return Boolean.TRUE;
        });
    }

    public synchronized AgentProcessAttempt revokeInProcessCiLearningCapability(
            String processAttemptId, Claim claim)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        return inTransaction(() -> {
            assertCiLearningTerminationAuthority(processAttemptId, claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
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
                    clock.instant().toEpochMilli(), processAttemptId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "CI learner capability changed during revocation");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    /** Atomically seals the sole model-authored lesson request and tools. */
    public synchronized String sealCiLearningLesson(
            String processAttemptId,
            Claim claim,
            String title,
            String markdown)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        requireText(title, "title");
        requireText(markdown, "markdown");
        if (title.length() > 160 || markdown.length() > 16_384) {
            throw new IllegalArgumentException(
                    "CI lesson exceeds its bounded size");
        }
        return inTransaction(() -> {
            String contentDigest = stableId(
                    "ci-lesson-content", title, markdown);
            List<String> existing = jdbc.queryForList(
                    "SELECT content_digest FROM "
                            + "flow_ci_learning_lesson_request "
                            + "WHERE operation_id = ? AND run_id = ("
                            + "SELECT run_id FROM "
                            + "flow_runtime_agent_process_attempt "
                            + "WHERE process_attempt_id = ?)",
                    String.class, claim.operationId(), processAttemptId);
            if (!existing.isEmpty()) {
                Integer exact = jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM flow_ci_learning_lesson_request q
                        JOIN flow_runtime_agent_process_attempt a
                          ON a.process_attempt_id = q.process_attempt_id
                        WHERE q.operation_id = ?
                          AND q.process_attempt_id = ?
                          AND q.title = ? AND q.markdown = ?
                          AND q.content_digest = ?
                          AND a.claim_generation = ?
                          AND a.claim_token_digest = ?
                        """,
                        Integer.class, claim.operationId(), processAttemptId,
                        title, markdown, contentDigest, claim.generation(),
                        claimTokenDigest(claim));
                if (requireNonNull(exact,
                        "CI lesson replay count is null") != 1) {
                    throw new IllegalStateException(
                            "CI lesson request replay changed content");
                }
                return existing.getFirst();
            }
            assertCiLearningTerminationAuthority(processAttemptId, claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
            AgentRun run = requireRun(attempt.runId());
            Operation operation = requireOperation(claim.operationId());
            Instant now = clock.instant();
            int requestStored = jdbc.update(
                    """
                    INSERT INTO flow_ci_learning_lesson_request (
                        operation_id, run_id, subject_id,
                        process_attempt_id, title, markdown,
                        content_digest, sealed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    operation.operationId(), run.runId(),
                    operation.inputRef(), processAttemptId,
                    title, markdown, contentDigest, now.toEpochMilli());
            int revoked = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET capability_revoked_at = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NULL
                      AND quarantine_reason IS NULL
                    """,
                    now.toEpochMilli(), processAttemptId);
            if (requestStored != 1 || revoked != 1) {
                throw new StaleOwnerRevisionException(
                        "CI lesson seal lost its active capability");
            }
            return contentDigest;
        });
    }

    public synchronized AgentProcessAttempt recordInProcessCiLearningStopped(
            String processAttemptId,
            Claim claim,
            InProcessCiLearningAgentSupervisor.TerminatedThreadWitness witness,
            InProcessStopType stopType,
            TerminalOutcome completionOutcome,
            String completionContent,
            String completionErrorRef)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(witness, "witness is null");
        requireNonNull(stopType, "stopType is null");
        assertCompletion(completionOutcome, completionErrorRef);
        return inTransaction(() -> {
            assertCiLearningTerminationAuthority(processAttemptId, claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
            assertCiLearningProcessIdentity(attempt, witness);
            if (attempt.state() == ProcessAttemptState.STOPPED) {
                if (attempt.stopType() != stopType) {
                    throw new IllegalStateException(
                            "CI learner stop replay changed type");
                }
                assertStoppedCompletion(
                        processAttemptId, completionOutcome,
                        completionContent, completionErrorRef);
                return attempt;
            }
            if (attempt.state() != ProcessAttemptState.ACTIVATED
                    || attempt.capabilityRevokedAt() == null
                    || attempt.quarantineReason() != null) {
                throw new IllegalStateException(
                        "CI learner is not safely stoppable");
            }
            Instant now = clock.instant();
            String proof = stableId(
                    "in-process-ci-learning-stop", attempt.executionId(),
                    stopType.name(), Long.toString(witness.jvmPid()),
                    Long.toString(witness.jvmStartedAt().toEpochMilli()),
                    Long.toString(witness.thread().threadId()),
                    Long.toString(attempt.capabilityRevokedAt()
                            .toEpochMilli()));
            String completionDigest = stoppedCompletionDigest(
                    processAttemptId, proof, completionOutcome,
                    completionContent, completionErrorRef);
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET state = 'STOPPED', stop_type = ?, stop_proof_ref = ?,
                        stopped_at = ?, completion_outcome = ?,
                        completion_content = ?, completion_error_ref = ?,
                        completion_digest = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NOT NULL
                      AND quarantine_reason IS NULL
                    """,
                    stopType.name(), proof, now.toEpochMilli(),
                    completionOutcome.name(), completionContent,
                    completionErrorRef, completionDigest,
                    processAttemptId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "CI learner stop proof changed concurrently");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    /** Stores the opaque result; CI owner facts join this outer transaction. */
    public synchronized AgentResult finishCiLearningAgentRun(
            String runId,
            Claim claim,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        return inTransaction(() -> {
            Optional<AgentResult> existing = resultForRun(runId);
            if (existing.isPresent()) {
                AgentResult result = existing.orElseThrow();
                assertFinalizedClaim(claim, result.resultId());
                AgentProcessAttempt stopped = requireExactStoppedCompletion(
                        runId, claim.generation(), terminalOutcome,
                        finalContent, errorRef);
                if (result.terminalOutcome() != terminalOutcome
                        || !Objects.equals(result.finalContent(), finalContent)
                        || !Objects.equals(result.errorRef(), errorRef)
                        || !result.stopProofRef().equals(
                                stopped.stopProofRef())) {
                    throw new IllegalStateException(
                            "CI learner result replay changed content");
                }
                return result;
            }
            Operation operation = assertCiLearningClaim(claim);
            AgentRun run = requireRun(runId);
            AgentSession session = requireSession(run.sessionId());
            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), terminalOutcome,
                    finalContent, errorRef);
            if (!run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.CI_LEARNER
                    || run.state() != RunState.RUNNING
                    || session.role() != AgentRole.CI_LEARNER
                    || session.state() != SessionState.RUNNING
                    || !runId.equals(session.lastRunId())) {
                throw new StaleOwnerRevisionException(
                        "CI learner finalization identity changed");
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
                    resultId, runId, terminalOutcome.name(), finalContent,
                    errorRef, stopped.stopProofRef(), now.toEpochMilli());
            RunState runState = switch (terminalOutcome) {
                case COMPLETED -> RunState.COMPLETED;
                case FAILED -> RunState.FAILED;
                case CANCELED -> RunState.CANCELED;
            };
            int runClosed = jdbc.update(
                    "UPDATE flow_runtime_agent_run SET state = ?, "
                            + "failure_reason_code = ?, completed_at = ? "
                            + "WHERE run_id = ? AND state = 'RUNNING'",
                    runState.name(), errorRef, now.toEpochMilli(), runId);
            int sessionClosed = jdbc.update(
                    "UPDATE flow_runtime_agent_session SET state = 'CLOSED', "
                            + "close_reason = ?, updated_at = ? "
                            + "WHERE session_id = ? AND state = 'RUNNING' "
                            + "AND last_run_id = ?",
                    "CI_LEARNING_" + terminalOutcome.name(),
                    now.toEpochMilli(), session.sessionId(), runId);
            if (resultStored != 1 || runClosed != 1
                    || sessionClosed != 1) {
                throw new StaleOwnerRevisionException(
                        "CI learner changed during finalization");
            }
            OperationState operationState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            settleDispatch(operation.operationId(), operationState, resultId);
            return requireResult(resultId);
        });
    }

    /** Starts or idempotently returns the fresh session for one reviewer. */
    public synchronized ReviewerStart startReviewerAgent(
            String requestId,
            Claim claim,
            String promptManifestRef,
            String capabilitySetRef)
    {
        requireText(requestId, "requestId");
        requireNonNull(claim, "claim is null");
        requireText(promptManifestRef, "promptManifestRef");
        requireText(capabilitySetRef, "capabilitySetRef");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            ReviewerRequest request = requireReviewerRequest(requestId);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(request.taskId());
            AgentSession taskSession = requireSession(task.taskSessionId());
            if (operation.kind() != OperationKind.RUN_REVIEWER
                    || !operation.ownerKind().equals("REVIEW_REQUEST")
                    || !operation.ownerId().equals(requestId)
                    || !operation.operationId().equals(
                            request.reviewerOperationId())
                    || !Objects.equals(claim.taskId(), task.taskId())
                    || task.status() != TaskStatus.ACTIVE
                    || task.waitingMutationStateRef() != null
                    || task.selectedWriterOperationId() != null
                    || taskSession.state() != SessionState.PARKED_CHILD
                    || !task.currentHeadSha().equals(
                            request.reviewedHeadSha())
                    || !task.currentChangeSetRevisionId().equals(
                            request.changeSetRevisionId())
                    || writerFence(task.taskId()).isPresent()) {
                throw new MutationRejectedException(
                        "reviewer request is not the current parked Task child");
            }
            Optional<AgentRun> existing = runForOperation(
                    operation.operationId());
            if (existing.isPresent()) {
                AgentRun run = existing.get();
                AgentSession session = requireSession(run.sessionId());
                if (run.role() != AgentRole.ADVERSARIAL_REVIEWER
                        || !run.headSha().equals(request.reviewedHeadSha())
                        || !run.promptManifestRef().equals(promptManifestRef)
                        || !run.capabilitySetRef().equals(capabilitySetRef)
                        || !run.inputRef().equals(
                                "review-request:" + requestId)
                        || (run.state() != RunState.QUEUED
                            && run.state() != RunState.RUNNING)
                        || session.role()
                                != AgentRole.ADVERSARIAL_REVIEWER
                        || session.state() != SessionState.RUNNING
                        || !run.runId().equals(session.lastRunId())) {
                    throw new IllegalStateException(
                            "reviewer start redelivery changed identity");
                }
                return new ReviewerStart(request, session, run);
            }
            Instant now = clock.instant();
            String sessionId = stableId(
                    "reviewer-session", requestId);
            String runId = stableId(
                    "agent-run", operation.operationId());
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_session (
                        session_id, task_id, role, state, last_run_id,
                        created_at, updated_at
                    ) VALUES (?, ?, 'ADVERSARIAL_REVIEWER', 'RUNNING', ?, ?, ?)
                    """,
                    sessionId,
                    task.taskId(),
                    runId,
                    now.toEpochMilli(),
                    now.toEpochMilli());
            jdbc.update(
                    """
                    INSERT INTO flow_runtime_agent_run (
                        run_id, operation_id, session_id, role, head_sha,
                        prompt_manifest_ref, capability_set_ref, input_ref,
                        state, created_at
                    ) VALUES (?, ?, ?, 'ADVERSARIAL_REVIEWER', ?, ?, ?, ?,
                        'QUEUED', ?)
                    """,
                    runId,
                    operation.operationId(),
                    sessionId,
                    request.reviewedHeadSha(),
                    promptManifestRef,
                    capabilitySetRef,
                    "review-request:" + requestId,
                    now.toEpochMilli());
            return new ReviewerStart(
                    request, requireSession(sessionId), requireRun(runId));
        });
    }

    synchronized AgentProcessAttempt reserveInProcessReviewerAttempt(
            String runId, Claim claim, String requestId)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireText(requestId, "requestId");
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            ReviewerRequest request = requireReviewerRequest(requestId);
            Operation operation = requireOperation(claim.operationId());
            AgentRun run = requireRun(runId);
            if (operation.kind() != OperationKind.RUN_REVIEWER
                    || !operation.ownerKind().equals("REVIEW_REQUEST")
                    || !operation.ownerId().equals(requestId)
                    || !request.reviewerOperationId().equals(
                            operation.operationId())
                    || !run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.ADVERSARIAL_REVIEWER) {
                throw new StaleClaimException(
                        "review process claim changed identity");
            }
            String attemptId = stableId(
                    "process-attempt",
                    runId,
                    Long.toString(claim.generation()));
            Optional<AgentProcessAttempt> existing = processAttempt(attemptId);
            if (existing.isPresent()) {
                AgentProcessAttempt attempt = existing.get();
                if (!attempt.runId().equals(runId)
                        || !attempt.operationId().equals(
                                claim.operationId())
                        || attempt.claimGeneration() != claim.generation()
                        || !attempt.claimTokenDigest().equals(
                                claimTokenDigest(claim))) {
                    throw new IllegalStateException(
                            "review process redelivery changed identity");
                }
                return attempt;
            }
            if (run.state() != RunState.QUEUED) {
                throw new StaleClaimException(
                        "reviewer run is not queued");
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
                    stableId("reviewer-execution", runId,
                            Long.toString(claim.generation())),
                    stableId("reviewer-capability", runId,
                            Long.toString(claim.generation())),
                    now.toEpochMilli());
            return requireProcessAttempt(attemptId);
        });
    }

    synchronized AgentRun activateInProcessReviewerAttempt(
            String processAttemptId,
            Claim claim,
            long jvmPid,
            Instant jvmStartedAt,
            long threadId,
            String threadName)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        requireNonNull(jvmStartedAt, "jvmStartedAt is null");
        requireText(threadName, "threadName");
        if (jvmPid <= 0 || threadId <= 0) {
            throw new IllegalArgumentException(
                    "review process identity must be positive");
        }
        return inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
            AgentRun run = requireRun(attempt.runId());
            if (!attempt.operationId().equals(claim.operationId())
                    || attempt.claimGeneration() != claim.generation()
                    || run.role() != AgentRole.ADVERSARIAL_REVIEWER) {
                throw new StaleClaimException(
                        "review process belongs to another claim");
            }
            if (attempt.state() == ProcessAttemptState.ACTIVATED) {
                if (!Long.valueOf(jvmPid).equals(attempt.jvmPid())
                        || !jvmStartedAt.equals(attempt.jvmStartedAt())
                        || !Long.valueOf(threadId).equals(attempt.threadId())
                        || !threadName.equals(attempt.threadName())
                        || run.state() != RunState.RUNNING) {
                    throw new IllegalStateException(
                            "review activation redelivery changed identity");
                }
                return run;
            }
            if (attempt.state() != ProcessAttemptState.RESERVED
                    || run.state() != RunState.QUEUED) {
                throw new IllegalStateException(
                        "review process cannot be activated");
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
                    SET state = 'RUNNING', started_at = COALESCE(started_at, ?)
                    WHERE run_id = ? AND state = 'QUEUED'
                    """,
                    now.toEpochMilli(),
                    run.runId());
            if (processUpdated != 1 || runUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "review process changed during activation");
            }
            return requireRun(run.runId());
        });
    }

    synchronized void assertInProcessReviewerToolCapability(
            String runId,
            Claim claim,
            String capabilityId,
            String requestId)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireText(capabilityId, "capabilityId");
        requireText(requestId, "requestId");
        inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Integer matches = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_agent_process_attempt a
                    JOIN flow_runtime_agent_run r ON r.run_id = a.run_id
                    JOIN flow_runtime_reviewer_request q
                      ON q.reviewer_operation_id = r.operation_id
                    WHERE a.run_id = ? AND a.operation_id = ?
                      AND a.claim_generation = ?
                      AND a.claim_token_digest = ?
                      AND a.capability_id = ? AND a.state = 'ACTIVATED'
                      AND a.capability_revoked_at IS NULL
                      AND a.quarantine_reason IS NULL
                      AND r.role = 'ADVERSARIAL_REVIEWER'
                      AND r.state = 'RUNNING'
                      AND q.request_id = ?
                    """,
                    Integer.class,
                    runId,
                    claim.operationId(),
                    claim.generation(),
                    claimTokenDigest(claim),
                    capabilityId,
                    requestId);
            if (requireNonNull(
                    matches, "review capability count is null") != 1) {
                throw new StaleCapabilityException(
                        "reviewer capability is stale or revoked");
            }
            return Boolean.TRUE;
        });
    }

    synchronized AgentProcessAttempt revokeInProcessReviewerCapability(
            String processAttemptId, Claim claim)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        return inTransaction(() -> {
            assertReviewerTerminationAuthority(processAttemptId, claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
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
                        "review capability changed during revocation");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    synchronized AgentProcessAttempt recordInProcessReviewerStopped(
            String processAttemptId,
            Claim claim,
            InProcessReviewerAgentSupervisor.TerminatedThreadWitness witness,
            InProcessStopType stopType,
            TerminalOutcome completionOutcome,
            String completionContent,
            String completionErrorRef)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(witness, "witness is null");
        requireNonNull(stopType, "stopType is null");
        assertCompletion(completionOutcome, completionErrorRef);
        return inTransaction(() -> {
            assertReviewerTerminationAuthority(processAttemptId, claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
            assertReviewerProcessIdentity(attempt, witness);
            if (attempt.state() == ProcessAttemptState.STOPPED) {
                if (attempt.stopType() != stopType) {
                    throw new IllegalStateException(
                            "review stop redelivery changed type");
                }
                assertStoppedCompletion(
                        processAttemptId, completionOutcome,
                        completionContent, completionErrorRef);
                return attempt;
            }
            if (attempt.state() != ProcessAttemptState.ACTIVATED
                    || attempt.capabilityRevokedAt() == null
                    || attempt.quarantineReason() != null) {
                throw new IllegalStateException(
                        "review execution is not safely stoppable");
            }
            Instant now = clock.instant();
            String proofRef = stableId(
                    "in-process-reviewer-stop",
                    attempt.executionId(),
                    stopType.name(),
                    Long.toString(witness.jvmPid()),
                    Long.toString(witness.jvmStartedAt().toEpochMilli()),
                    Long.toString(witness.thread().threadId()),
                    Long.toString(attempt.capabilityRevokedAt()
                            .toEpochMilli()));
            String completionDigest = stoppedCompletionDigest(
                    processAttemptId, proofRef, completionOutcome,
                    completionContent, completionErrorRef);
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET state = 'STOPPED', stop_type = ?,
                        stop_proof_ref = ?, stopped_at = ?,
                        completion_outcome = ?, completion_content = ?,
                        completion_error_ref = ?, completion_digest = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NOT NULL
                      AND quarantine_reason IS NULL
                    """,
                    stopType.name(),
                    proofRef,
                    now.toEpochMilli(),
                    completionOutcome.name(), completionContent,
                    completionErrorRef, completionDigest,
                    processAttemptId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "review stop proof changed concurrently");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    synchronized AgentProcessAttempt quarantineInProcessReviewerAttempt(
            String processAttemptId,
            Claim claim,
            ProcessQuarantineReason reason)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(reason, "reason is null");
        return inTransaction(() -> {
            assertReviewerTerminationAuthority(processAttemptId, claim);
            AgentProcessAttempt attempt = requireProcessAttempt(
                    processAttemptId);
            if (attempt.quarantineReason() != null) {
                if (attempt.quarantineReason() != reason) {
                    throw new IllegalStateException(
                            "review quarantine redelivery changed reason");
                }
                return attempt;
            }
            if (attempt.state() != ProcessAttemptState.ACTIVATED
                    || attempt.capabilityRevokedAt() == null) {
                throw new IllegalStateException(
                        "only a revoked active reviewer can be quarantined");
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
            Task task = requireTask(claim.taskId());
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
                        current_lifecycle_revision_id = ?,
                        waiting_mutation_state_ref = ?
                    WHERE task_id = ? AND status = 'ACTIVE'
                      AND selected_writer_operation_id IS NULL
                      AND waiting_mutation_state_ref IS NULL
                      AND current_lifecycle_revision_id = ?
                    """,
                    attention.lifecycleRevisionId(),
                    reviewerRecoveryRef(processAttemptId),
                    task.taskId(),
                    task.currentLifecycleRevisionId());
            if (quarantined != 1 || taskUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "review quarantine lost its Task owner");
            }
            return requireProcessAttempt(processAttemptId);
        });
    }

    /** Atomically stores one opaque reviewer result and resumes its parent. */
    public synchronized AgentResult finishReviewerAgentRun(
            String runId,
            Claim claim,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        return inTransaction(() -> {
            AgentRun run = requireRun(runId);
            ReviewerRequest request = reviewerRequestForOperation(
                    run.operationId());
            Optional<AgentResult> existing = resultForRun(runId);
            if (existing.isPresent()) {
                AgentResult result = existing.get();
                assertFinalizedClaim(claim, result.resultId());
                AgentProcessAttempt stopped = requireExactStoppedCompletion(
                        runId, claim.generation(), terminalOutcome,
                        finalContent, errorRef);
                if (result.terminalOutcome() != terminalOutcome
                        || !Objects.equals(
                                result.finalContent(), finalContent)
                        || !Objects.equals(result.errorRef(), errorRef)
                        || !result.stopProofRef().equals(
                                stopped.stopProofRef())) {
                    throw new IllegalStateException(
                            "reviewer result replay changed terminal content");
                }
                requireReviewerResultReady(request, result);
                return result;
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(request.taskId());
            AgentSession reviewerSession = requireSession(run.sessionId());
            AgentSession parentSession = requireSession(task.taskSessionId());
            if (!run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.ADVERSARIAL_REVIEWER
                    || run.state() != RunState.RUNNING
                    || !run.headSha().equals(request.reviewedHeadSha())
                    || reviewerSession.role()
                            != AgentRole.ADVERSARIAL_REVIEWER
                    || reviewerSession.state() != SessionState.RUNNING
                    || !runId.equals(reviewerSession.lastRunId())
                    || parentSession.state() != SessionState.PARKED_CHILD
                    || task.status() != TaskStatus.ACTIVE
                    || task.waitingMutationStateRef() != null
                    || task.selectedWriterOperationId() != null
                    || !task.currentHeadSha().equals(
                            request.reviewedHeadSha())
                    || !task.currentChangeSetRevisionId().equals(
                            request.changeSetRevisionId())) {
                throw new StaleOwnerRevisionException(
                        "reviewer finalization subject is no longer current");
            }
            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), terminalOutcome,
                    finalContent, errorRef);
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
            int reviewerClosed = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = 'CLOSED', close_reason = ?, updated_at = ?
                    WHERE session_id = ? AND state = 'RUNNING'
                      AND last_run_id = ?
                    """,
                    "REVIEWER_" + terminalOutcome.name(),
                    now.toEpochMilli(),
                    reviewerSession.sessionId(),
                    runId);
            OperationState operationState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            if (resultStored != 1 || runFinished != 1
                    || reviewerClosed != 1) {
                throw new StaleOwnerRevisionException(
                        "reviewer run changed during finalization");
            }
            settleDispatch(operation.operationId(), operationState, resultId);
            AgentResult result = requireResult(resultId);
            appendPendingWork(
                    stableId("inbox", "AGENT_RESULT_READY", runId, "1"),
                    task,
                    task.prId(),
                    "REVIEWER",
                    runId,
                    "1",
                    PendingKind.AGENT_RESULT_READY,
                    request.reviewedHeadSha(),
                    reviewerResultPayload(request.requestId(), resultId),
                    resultId,
                    request.intendedGateKind(),
                    now);
            int parentResumed = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = 'IDLE', updated_at = ?
                    WHERE session_id = ? AND state = 'PARKED_CHILD'
                      AND last_run_id = ?
                    """,
                    now.toEpochMilli(),
                    parentSession.sessionId(),
                    request.parentRunId());
            if (parentResumed != 1) {
                throw new StaleOwnerRevisionException(
                        "reviewer lost its parked parent session");
            }
            cancelReviewerBlockedReconciliation(task.taskId());
            ensureReconciliationIfPending(task.taskId());
            return result;
        });
    }

    synchronized AgentResult verifyFinalizedReviewerResult(
            String runId, Claim claim, AgentResult returnedResult)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(returnedResult, "returnedResult is null");
        return inTransaction(() -> {
            AgentResult stored = resultForRun(runId).orElseThrow(() ->
                    new IllegalStateException(
                            "reviewer finalizer stored no AgentResult"));
            if (!stored.equals(returnedResult)) {
                throw new IllegalStateException(
                        "reviewer finalizer returned a noncanonical result");
            }
            assertFinalizedClaim(claim, stored.resultId());
            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), stored.terminalOutcome(),
                    stored.finalContent(), stored.errorRef());
            if (!stored.stopProofRef().equals(stopped.stopProofRef())) {
                throw new IllegalStateException(
                        "finalized reviewer stop proof changed");
            }
            AgentRun run = requireRun(runId);
            ReviewerRequest request = reviewerRequestForOperation(
                    run.operationId());
            AgentSession session = requireSession(run.sessionId());
            if (run.role() != AgentRole.ADVERSARIAL_REVIEWER
                    || session.state() != SessionState.CLOSED
                    || !runId.equals(session.lastRunId())) {
                throw new IllegalStateException(
                        "reviewer session did not close durably");
            }
            requireReviewerResultReady(request, stored);
            return stored;
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
                assertExistingAttemptRedeliveryAuthority(
                        claim, fence, operation);
                return attempt;
            }
            assertFenceRow(claim, fence);
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

    private void assertExistingAttemptRedeliveryAuthority(
            Claim suppliedClaim,
            WriterFence suppliedFence,
            Operation operation)
    {
        Instant storedClaimExpiry = currentClaimExpiry(suppliedClaim);
        Claim storedClaim = new Claim(
                suppliedClaim.operationId(),
                operation.taskId(),
                operation.kind(),
                suppliedClaim.generation(),
                suppliedClaim.claimToken(),
                suppliedClaim.workerId(),
                storedClaimExpiry);
        WriterFence storedFence = writerFence(operation.taskId())
                .orElseThrow(() -> new StaleWriterFenceException(
                        "writer fence is no longer current"));
        assertFenceRow(storedClaim, storedFence);
        if (!sameClaimAuthority(suppliedClaim, storedClaim)
                || suppliedClaim.expiresAt().isAfter(storedClaimExpiry)
                || !sameFenceAuthority(suppliedFence, storedFence)
                || suppliedFence.expiresAt().isAfter(
                        storedFence.expiresAt())) {
            throw new StaleWriterFenceException(
                    "writer redelivery authority changed after renewal");
        }
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

    /**
     * Records the process group a CLI body launched, before its prompt is sent.
     *
     * <p>Ordering is the whole point: an id learned only when the turn ends is
     * lost by exactly the crash that makes it matter, leaving a live agent group
     * that no later JVM can find, let alone bury. Written while the group is
     * still young and unused, it is what a restart reads before deciding whether
     * a successor writer may be admitted.
     *
     * <p>Set-once. A redelivery naming the same group is the no-op it looks
     * like; one naming a different group means two agents ran under a single
     * attempt, which is not a state to overwrite quietly.
     *
     * @param agentStartedAt when the group leader began, or null when the OS
     *         would not say — recorded as absent rather than guessed, because
     *         recovery treats an unidentifiable group as a stop, and a
     *         fabricated start time would turn that stop into a false bury
     */
    synchronized void recordInProcessWriterAgentGroup(
            String processAttemptId,
            Claim claim,
            WriterFence fence,
            long agentPid,
            long agentPgid,
            Instant agentStartedAt)
    {
        requireNonNull(fence, "fence is null");
        recordAgentProcessGroup(
                processAttemptId, claim, fence, agentPid, agentPgid,
                agentStartedAt);
    }

    /**
     * The same record for a role that holds no writer lease.
     *
     * <p>A reviewer or learner runs read-only and never takes the fence, so
     * there is none to assert. What still has to hold is everything that makes
     * the row meaningful: the claim is current, the attempt belongs to that
     * claim generation, and it is activated. Those are what a later JVM trusts
     * when it decides whether to bury this group.
     */
    synchronized void recordInProcessReadOnlyAgentGroup(
            String processAttemptId,
            Claim claim,
            long agentPid,
            long agentPgid,
            Instant agentStartedAt)
    {
        recordAgentProcessGroup(
                processAttemptId, claim, null, agentPid, agentPgid,
                agentStartedAt);
    }

    private void recordAgentProcessGroup(
            String processAttemptId,
            Claim claim,
            WriterFence fence,
            long agentPid,
            long agentPgid,
            Instant agentStartedAt)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(claim, "claim is null");
        if (agentPid <= 0) {
            throw new IllegalArgumentException("agentPid must be positive");
        }
        if (agentPgid <= 1) {
            // Group 0 is the caller's own and 1 is init; persisting either would
            // arm a later restart to signal this JVM, or the system.
            throw new IllegalArgumentException("agentPgid must be above 1");
        }
        inTransaction(() -> {
            assertCurrentClaim(claim, OperationState.CLAIMED);
            if (fence != null) {
                assertFenceRow(claim, fence);
            }
            AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
            if (!attempt.operationId().equals(claim.operationId())
                    || attempt.claimGeneration() != claim.generation()) {
                throw new StaleClaimException(
                        "process attempt belongs to another claim generation");
            }
            if (attempt.state() != ProcessAttemptState.ACTIVATED) {
                throw new IllegalStateException(
                        "only an activated attempt owns an agent process group");
            }
            if (attempt.agentPgid() != null) {
                if (!Long.valueOf(agentPid).equals(attempt.agentPid())
                        || !Long.valueOf(agentPgid).equals(attempt.agentPgid())
                        || !Objects.equals(
                                agentStartedAt, attempt.agentStartedAt())) {
                    throw new IllegalStateException(
                            "agent process group redelivery changed identity");
                }
                return Boolean.TRUE;
            }
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET agent_pid = ?, agent_pgid = ?, agent_started_at = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                        AND agent_pgid IS NULL
                    """,
                    agentPid,
                    agentPgid,
                    agentStartedAt == null ? null : agentStartedAt.toEpochMilli(),
                    processAttemptId);
            if (updated != 1) {
                throw new StaleOwnerRevisionException(
                        "process attempt changed while recording its group");
            }
            return Boolean.TRUE;
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
                      AND NOT EXISTS (
                          SELECT 1 FROM flow_runtime_reviewer_request q
                          WHERE q.parent_run_id = r.run_id
                      )
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
            InProcessStopType stopType,
            TerminalOutcome completionOutcome,
            String completionContent,
            String completionErrorRef)
    {
        requireText(processAttemptId, "processAttemptId");
        requireNonNull(witness, "witness is null");
        requireNonNull(stopType, "stopType is null");
        assertCompletion(completionOutcome, completionErrorRef);
        return inTransaction(() -> {
            assertTerminationAuthority(processAttemptId, claim, fence);
            AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
            assertInProcessIdentity(attempt, witness);
            if (attempt.state() == ProcessAttemptState.STOPPED) {
                if (attempt.stopType() != stopType) {
                    throw new IllegalStateException(
                            "in-process stop redelivery changed type");
                }
                assertStoppedCompletion(
                        processAttemptId, completionOutcome,
                        completionContent, completionErrorRef);
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
            String completionDigest = stoppedCompletionDigest(
                    processAttemptId, proofRef, completionOutcome,
                    completionContent, completionErrorRef);
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_process_attempt
                    SET state = 'STOPPED', stop_type = ?,
                        stop_proof_ref = ?, stopped_at = ?,
                        completion_outcome = ?, completion_content = ?,
                        completion_error_ref = ?, completion_digest = ?
                    WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                      AND capability_revoked_at IS NOT NULL
                      AND quarantine_reason IS NULL
                    """,
                    stopType.name(),
                    proofRef,
                    now.toEpochMilli(),
                    completionOutcome.name(), completionContent,
                    completionErrorRef, completionDigest,
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
        AgentRun run = requireRun(runId);
        if (run.role() == AgentRole.TASK_AGENT) {
            throw new MutationRejectedException(
                    "Task turns require their intent-bound finalizer");
        }
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

    private PreparedTaskResultConsumption prepareTaskResultConsumption(
            String runId, Claim claim, WriterFence fence)
    {
        TaskResultConsumptionSnapshot snapshot;
        synchronized (this) {
            snapshot = inTransaction(() ->
                    taskResultConsumptionSnapshot(runId, claim, fence));
        }
        if (!snapshot.currentChangeSet().changeSetRevisionId().equals(
                    snapshot.request().changeSetRevisionId())
                || !snapshot.currentChangeSet().headSha().equals(
                    snapshot.request().reviewedHeadSha())) {
            return new PreparedTaskResultConsumption(
                    claim,
                    fence,
                    snapshot,
                    null,
                    "CHANGED_WITHOUT_FRESH_REVIEW");
        }
        try {
            Inspection inspection = worktreeInspector.inspect(
                    Path.of(snapshot.request().repositoryRoot()),
                    snapshot.worktree(),
                    snapshot.branchName(),
                    snapshot.base().baseSha(),
                    snapshot.currentChangeSet().headSha());
            if (!inspection.headSha().equals(
                        snapshot.currentChangeSet().headSha())
                    || !inspection.headTreeDigest().equals(
                        snapshot.currentChangeSet().headTreeDigest())
                    || !inspection.baseToHeadDiffDigest().equals(
                        snapshot.currentChangeSet().diffDigest())) {
                return new PreparedTaskResultConsumption(
                        claim,
                        fence,
                        snapshot,
                        null,
                        "WORKTREE_CHANGED_WITHOUT_FRESH_REVIEW");
            }
            return new PreparedTaskResultConsumption(
                    claim, fence, snapshot, inspection, null);
        }
        catch (InspectionFailure failure) {
            if (transientInspectionFailure(failure.code())) {
                throw failure;
            }
            return new PreparedTaskResultConsumption(
                    claim,
                    fence,
                    snapshot,
                    null,
                    "RESULT_CONSUMPTION_" + failure.code().name());
        }
    }

    private TaskResultConsumptionSnapshot taskResultConsumptionSnapshot(
            String runId, Claim claim, WriterFence fence)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        assertFenceRow(claim, fence);
        AgentRun run = requireRun(runId);
        Operation operation = requireOperation(claim.operationId());
        Task task = requireTask(operation.taskId());
        PendingWork input = selectedInput(operation);
        if (!run.operationId().equals(operation.operationId())
                || run.role() != AgentRole.TASK_AGENT
                || run.state() != RunState.RUNNING
                || run.wakeKind() != WakeKind.AGENT_RESULT_READY
                || input.kind() != PendingKind.AGENT_RESULT_READY
                || !input.pendingId().equals(
                        requireTextResult(run.inputRef(), "inbox:"))
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())) {
            throw new MutationRejectedException(
                    "result consumption requires the exact active Task turn");
        }
        ReviewerRequest request = reviewerRequestForReviewerRun(
                input.externalKey()).orElseThrow(() ->
                        new MutationRejectedException(
                                "result consumption has no reviewer request"));
        AgentResult reviewerResult = resultForRun(input.externalKey())
                .orElseThrow(() -> new MutationRejectedException(
                        "result consumption has no reviewer result"));
        if (!Objects.equals(input.agentResultId(), reviewerResult.resultId())
                || !input.subjectHead().equals(request.reviewedHeadSha())
                || input.intendedGateKind() != request.intendedGateKind()
                || !input.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), reviewerResult.resultId()))
                || !request.taskId().equals(task.taskId())) {
            throw new MutationRejectedException(
                    "result consumption input changed identity");
        }
        requireStoppedProcessAttempt(runId, claim.generation());
        ChangeSetRevision current = requireChangeSetRevision(
                task.currentChangeSetRevisionId());
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new MutationRejectedException(
                        "result consumption has no immutable base"));
        return new TaskResultConsumptionSnapshot(
                task.taskId(),
                task.epoch(),
                operation.operationId(),
                operation.subjectDigest(),
                runId,
                input.pendingId(),
                request,
                reviewerResult.resultId(),
                Path.of(task.worktreePath()),
                task.branchName(),
                base,
                current);
    }

    private static String requireTextResult(String inputRef, String prefix)
    {
        if (!inputRef.startsWith(prefix)) {
            throw new MutationRejectedException(
                    "Task run input reference is malformed");
        }
        return inputRef.substring(prefix.length());
    }

    private static String missingReviewerReason(
            PreparedTaskResultConsumption prepared,
            TerminalOutcome terminalOutcome)
    {
        if (prepared == null) {
            return "MISSING_TERMINAL_REVIEW_REQUEST";
        }
        if (prepared.blockerCode != null) {
            return prepared.blockerCode;
        }
        return "RESULT_TURN_" + terminalOutcome.name();
    }

    /**
     * Finalizes one stopped CI-update Task turn. A durable reviewer request
     * parks the parent. An unchanged result-delivery turn may consume its input
     * only after fresh mechanical inspection; all other missing-request turns
     * release authority into typed attention.
     */
    public AgentResult finishTaskAgentReviewTurn(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        boolean prepareResultConsumption;
        synchronized (this) {
            AgentRun candidate = requireRun(runId);
            prepareResultConsumption = resultForRun(runId).isEmpty()
                    && candidate.wakeKind() == WakeKind.AGENT_RESULT_READY
                    && candidate.intendedGateKind() == GateIntent.CI_UPDATE
                    && reviewerRequestForParentRun(runId).isEmpty();
        }
        PreparedTaskResultConsumption resultConsumption =
                prepareResultConsumption
                        ? prepareTaskResultConsumption(runId, claim, fence)
                        : null;
        PreparedTaskResultConsumption preparedResultConsumption =
                resultConsumption;
        synchronized (this) {
            return inTransaction(() -> {
            AgentRun run = requireRun(runId);
            if (taskTerminalRequest(runId)
                    .filter(request -> request.kind()
                            == TaskTerminalRequestKind.READY_FOR_REVIEW)
                    .isPresent()) {
                throw new MutationRejectedException(
                        "ready-for-review run requires its gate finalizer");
            }
            Optional<ReviewerRequest> request =
                    reviewerRequestForParentRun(runId);
            Optional<AgentResult> existing = resultForRun(runId);
            if (existing.isPresent()) {
                AgentResult result = existing.get();
                assertFinalizedClaim(claim, result.resultId());
                AgentProcessAttempt stopped = requireExactStoppedCompletion(
                        runId, claim.generation(), terminalOutcome,
                        finalContent, errorRef);
                if (result.terminalOutcome() != terminalOutcome
                        || !Objects.equals(
                                result.finalContent(), finalContent)
                        || !Objects.equals(result.errorRef(), errorRef)
                        || !result.stopProofRef().equals(
                                stopped.stopProofRef())) {
                    throw new IllegalStateException(
                            "Task review finalization changed terminal content");
                }
                assertTaskReviewFinalState(run, request);
                return result;
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(fence.taskId());
            if (!run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.TASK_AGENT
                    || run.state() != RunState.RUNNING
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())) {
                throw new StaleOwnerRevisionException(
                        "Task review parent is no longer current");
            }
            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), terminalOutcome,
                    finalContent, errorRef);
            boolean consumeReviewedResult = request.isEmpty()
                    && run.wakeKind() == WakeKind.AGENT_RESULT_READY
                    && run.intendedGateKind() == GateIntent.CI_UPDATE
                    && terminalOutcome == TerminalOutcome.COMPLETED
                    && preparedResultConsumption != null
                    && preparedResultConsumption.accepted();
            if (preparedResultConsumption != null) {
                if (!sameClaimAuthority(
                            preparedResultConsumption.claim, claim)
                        || !sameFenceAuthority(
                            preparedResultConsumption.fence, fence)
                        || !preparedResultConsumption.snapshot.equals(
                            taskResultConsumptionSnapshot(
                                    runId, claim, fence))) {
                    throw new StaleOwnerRevisionException(
                            "result consumption changed after inspection");
                }
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
            SessionState sessionTarget = request.isPresent()
                    ? SessionState.PARKED_CHILD
                    : SessionState.IDLE;
            int sessionFinished = jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = ?, updated_at = ?
                    WHERE session_id = ? AND state = 'RUNNING'
                      AND last_run_id = ?
                    """,
                    sessionTarget.name(),
                    now.toEpochMilli(),
                    run.sessionId(),
                    runId);
            if (resultStored != 1 || runFinished != 1
                    || sessionFinished != 1) {
                throw new StaleOwnerRevisionException(
                        "Task review run changed during finalization");
            }
            OperationState terminalState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            settleDispatch(operation.operationId(), terminalState, resultId);
            if (request.isPresent()) {
                ReviewerRequest reviewer = request.orElseThrow();
                if (!reviewer.parentOperationId().equals(
                            operation.operationId())
                        || !reviewer.taskId().equals(task.taskId())
                        || !reviewer.reviewedHeadSha().equals(
                                task.currentHeadSha())
                        || !reviewer.changeSetRevisionId().equals(
                                task.currentChangeSetRevisionId())
                        || reviewer.intendedGateKind()
                                != run.intendedGateKind()) {
                    throw new StaleOwnerRevisionException(
                            "reviewer request changed before parent stop");
                }
                int inputHandled = jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET handled_by_operation_id = ?
                        WHERE selected_by_operation_id = ?
                          AND handled_by_operation_id IS NULL
                        """,
                        operation.operationId(),
                        operation.operationId());
                int reviewerReady = jdbc.update(
                        """
                        UPDATE flow_runtime_operation SET state = 'READY'
                        WHERE operation_id = ? AND state = 'WAITING'
                        """,
                        reviewer.reviewerOperationId());
                if (inputHandled != 1 || reviewerReady != 1) {
                    throw new StaleOwnerRevisionException(
                            "reviewer eligibility changed before parent stop");
                }
            }
            else if (consumeReviewedResult) {
                int inputHandled = jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET handled_by_operation_id = ?
                        WHERE selected_by_operation_id = ?
                          AND handled_by_operation_id IS NULL
                        """,
                        operation.operationId(),
                        operation.operationId());
                if (inputHandled != 1) {
                    throw new StaleOwnerRevisionException(
                            "reviewer result consumption lost its input");
                }
            }
            else {
                int inputHandled = jdbc.update(
                        """
                        UPDATE flow_runtime_inbox
                        SET handled_by_operation_id = ?
                        WHERE selected_by_operation_id = ?
                          AND handled_by_operation_id IS NULL
                        """,
                        operation.operationId(),
                        operation.operationId());
                if (inputHandled != 1) {
                    throw new StaleOwnerRevisionException(
                            "failed Task review did not consume its input");
                }
                String attentionReason;
                if (run.wakeKind() == WakeKind.AGENT_RESULT_READY
                        && run.intendedGateKind()
                                == GateIntent.INITIAL_PUBLISH) {
                    attentionReason = "MISSING_INITIAL_TERMINAL_REQUEST";
                }
                else {
                    attentionReason = missingReviewerReason(
                            preparedResultConsumption, terminalOutcome);
                }
                TaskLifecycleRevision attention = appendLifecycle(
                        task,
                        TaskStatus.NEEDS_ATTENTION,
                        attentionReason,
                        "agent-result:" + resultId,
                        operation.operationId(),
                        now);
                int attentionSet = jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET status = 'NEEDS_ATTENTION',
                            current_lifecycle_revision_id = ?
                        WHERE task_id = ? AND status = 'ACTIVE'
                          AND current_lifecycle_revision_id = ?
                        """,
                        attention.lifecycleRevisionId(),
                        task.taskId(),
                        task.currentLifecycleRevisionId());
                if (attentionSet != 1) {
                    throw new StaleOwnerRevisionException(
                            "failed Task review lost its lifecycle owner");
                }
            }
            int pointerCleared = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET selected_writer_operation_id = NULL
                    WHERE task_id = ? AND selected_writer_operation_id = ?
                    """,
                    task.taskId(),
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
            if (pointerCleared != 1 || leaseReleased != 1) {
                throw new StaleOwnerRevisionException(
                        "Task review finalization lost its writer authority");
            }
            rearmReconciliationAfterWriter(task.taskId());
            ensureReconciliationIfPending(task.taskId());
            return requireResult(resultId);
            });
        }
    }

    /**
     * Settles one stopped terminal ready declaration. The gate owner inserts
     * the exact local revision in the same outer transaction before calling
     * this method, or supplies one typed stable post-seal blocker.
     */
    public synchronized AgentResult finishTaskAgentReadyTurn(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            String gateRevisionRef,
            String readinessFailureCode)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
        if ((gateRevisionRef == null) == (readinessFailureCode == null)) {
            throw new IllegalArgumentException(
                    "ready finalization needs a gate or blocker");
        }
        return inTransaction(() -> {
            ReadyForReviewRequest ready = readyForReviewRequestForRun(runId)
                    .orElseThrow(() -> new MutationRejectedException(
                            "ready finalization has no durable request"));
            assertTaskTerminalRequest(
                    runId,
                    TaskTerminalRequestKind.READY_FOR_REVIEW,
                    ready.requestId());
            AgentRun run = requireRun(runId);
            Optional<AgentResult> existing = resultForRun(runId);
            if (existing.isPresent()) {
                AgentResult result = existing.orElseThrow();
                assertFinalizedClaim(claim, result.resultId());
                AgentProcessAttempt stopped = requireExactStoppedCompletion(
                        runId, claim.generation(), terminalOutcome,
                        finalContent, errorRef);
                if (result.terminalOutcome() != terminalOutcome
                        || !Objects.equals(result.finalContent(), finalContent)
                        || !Objects.equals(result.errorRef(), errorRef)
                        || !result.stopProofRef().equals(
                                stopped.stopProofRef())) {
                    throw new IllegalStateException(
                            "ready finalization changed terminal content");
                }
                assertReadyFinalState(
                        run, ready, gateRevisionRef, readinessFailureCode);
                return result;
            }
            assertCurrentClaim(claim, OperationState.CLAIMED);
            assertFenceRow(claim, fence);
            Operation operation = requireOperation(claim.operationId());
            Task task = requireTask(fence.taskId());
            PendingWork input = selectedInput(operation);
            ReviewerRequest reviewer = reviewerRequestForReviewerRun(
                    input.externalKey()).orElseThrow(() ->
                            new StaleOwnerRevisionException(
                                    "ready Task input has no reviewer"));
            if (!run.operationId().equals(operation.operationId())
                    || !ready.operationId().equals(operation.operationId())
                    || !ready.taskId().equals(task.taskId())
                    || run.role() != AgentRole.TASK_AGENT
                    || run.state() != RunState.RUNNING
                    || run.wakeKind() != WakeKind.AGENT_RESULT_READY
                    || input.kind() != PendingKind.AGENT_RESULT_READY
                    || run.intendedGateKind() != input.intendedGateKind()
                    || reviewer.intendedGateKind()
                            != run.intendedGateKind()
                    || !operation.operationId().equals(
                            task.selectedWriterOperationId())) {
                throw new StaleOwnerRevisionException(
                        "ready Task turn is no longer current");
            }
            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), terminalOutcome,
                    finalContent, errorRef);
            if (gateRevisionRef != null) {
                assertReadyGateRevision(runId, ready, gateRevisionRef);
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
            int sessionFinished = jdbc.update(
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
                    || sessionFinished != 1) {
                throw new StaleOwnerRevisionException(
                        "ready Task run changed during finalization");
            }
            OperationState terminalState = switch (terminalOutcome) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            settleDispatch(operation.operationId(), terminalState, resultId);
            int inputHandled = jdbc.update(
                    """
                    UPDATE flow_runtime_inbox
                    SET handled_by_operation_id = ?
                    WHERE selected_by_operation_id = ?
                      AND handled_by_operation_id IS NULL
                    """,
                    operation.operationId(),
                    operation.operationId());
            if (inputHandled != 1) {
                throw new StaleOwnerRevisionException(
                        "ready Task run lost its selected input");
            }
            if (readinessFailureCode != null) {
                TaskLifecycleRevision attention = appendLifecycle(
                        task,
                        TaskStatus.NEEDS_ATTENTION,
                        readinessFailureCode,
                        "ready-request:" + ready.requestId(),
                        operation.operationId(),
                        now);
                int attentionSet = jdbc.update(
                        """
                        UPDATE flow_runtime_task
                        SET status = 'NEEDS_ATTENTION',
                            current_lifecycle_revision_id = ?
                        WHERE task_id = ? AND status = 'ACTIVE'
                          AND current_lifecycle_revision_id = ?
                        """,
                        attention.lifecycleRevisionId(),
                        task.taskId(),
                        task.currentLifecycleRevisionId());
                if (attentionSet != 1) {
                    throw new StaleOwnerRevisionException(
                            "ready attention lost its Task lifecycle");
                }
            }
            int pointerCleared = jdbc.update(
                    """
                    UPDATE flow_runtime_task
                    SET selected_writer_operation_id = NULL
                    WHERE task_id = ? AND selected_writer_operation_id = ?
                    """,
                    task.taskId(),
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
            if (pointerCleared != 1 || leaseReleased != 1) {
                throw new StaleOwnerRevisionException(
                        "ready finalization lost its writer authority");
            }
            rearmReconciliationAfterWriter(task.taskId());
            ensureReconciliationIfPending(task.taskId());
            return requireResult(resultId);
        });
    }

    private void assertReadyGateRevision(
            String runId,
            ReadyForReviewRequest ready,
            String gateRevisionRef)
    {
        AgentRun run = requireRun(runId);
        if (run.intendedGateKind() == GateIntent.INITIAL_PUBLISH) {
            Integer initial = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_user_gate_revision r
                    JOIN flow_user_gate g ON g.gate_id = r.gate_id
                    JOIN flow_user_gate_initial_publish_subject s
                      ON s.subject_id = r.subject_manifest_ref
                    JOIN flow_user_gate_initial_publish_action a
                      ON a.action_ref = r.action_manifest_ref
                    JOIN flow_runtime_inbox i
                      ON i.selected_by_operation_id = ?
                    JOIN flow_runtime_reviewer_request q
                      ON q.request_id = s.reviewer_request_id
                    JOIN flow_runtime_agent_run rr
                      ON rr.run_id = s.reviewer_run_id
                    JOIN flow_user_gate_transition t
                      ON t.gate_id = r.gate_id
                     AND t.gate_revision = r.revision
                    WHERE r.created_by_run_id = ?
                      AND r.subject_manifest_ref = ?
                      AND r.subject_digest = s.subject_digest
                      AND r.subject_digest = ?
                      AND r.action_manifest_ref = ?
                      AND r.action_digest = a.action_digest
                      AND r.action_digest = ?
                      AND r.readiness_evidence_ref IS NULL
                      AND g.task_id = ? AND s.task_id = g.task_id
                      AND g.pr_id = ? AND s.pr_id = g.pr_id
                      AND g.kind = 'INITIAL_PUBLISH'
                      AND i.kind = 'AGENT_RESULT_READY'
                      AND i.agent_result_id = s.reviewer_result_id
                      AND i.external_key = s.reviewer_run_id
                      AND q.reviewer_operation_id = rr.operation_id
                      AND q.intended_gate_kind = 'INITIAL_PUBLISH'
                      AND t.from_state IS NULL AND t.to_state = 'OPEN'
                      AND t.actor_type = 'PROGRAM'
                      AND t.actor_id = 'READY_FOR_REVIEW'
                      AND t.reason_code = 'READY'
                      AND r.gate_id || ':' || r.revision = ?
                    """,
                    Integer.class,
                    ready.operationId(), runId,
                    ready.subjectRef(), ready.subjectDigest(),
                    ready.actionRef(), ready.actionDigest(),
                    ready.taskId(), ready.prId(), gateRevisionRef);
            if (requireNonNull(initial,
                    "INITIAL gate revision count is null") != 1) {
                throw new StaleOwnerRevisionException(
                        "INITIAL ready gate does not match its reviewer input");
            }
            return;
        }
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_user_gate_revision r
                JOIN flow_user_gate g ON g.gate_id = r.gate_id
                JOIN flow_user_gate_subject s
                  ON s.subject_id = r.subject_manifest_ref
                JOIN flow_user_gate_ci_update_action a
                  ON a.action_ref = r.action_manifest_ref
                JOIN flow_user_gate_transition t
                  ON t.gate_id = r.gate_id
                 AND t.gate_revision = r.revision
                WHERE r.created_by_run_id = ?
                  AND r.subject_manifest_ref = ?
                  AND r.subject_digest = ?
                  AND r.action_manifest_ref = ?
                  AND r.action_digest = ?
                  AND r.readiness_evidence_ref = ?
                  AND a.action_digest = r.action_digest
                  AND a.branch_ref = s.branch_ref
                  AND a.expected_remote_head = s.expected_remote_head
                  AND a.proposed_head = s.proposed_head
                  AND a.force_push = 0
                  AND g.task_id = ? AND s.task_id = g.task_id
                  AND g.pr_id = ? AND s.pr_id = g.pr_id
                  AND g.kind = 'CI_UPDATE'
                  AND t.from_state IS NULL AND t.to_state = 'OPEN'
                  AND t.actor_type = 'PROGRAM'
                  AND t.actor_id = 'READY_FOR_REVIEW'
                  AND t.reason_code = 'READY'
                  AND r.gate_id || ':' || r.revision = ?
                """,
                Integer.class,
                runId,
                ready.subjectRef(),
                ready.subjectDigest(),
                ready.actionRef(),
                ready.actionDigest(),
                ready.requestId(),
                ready.taskId(),
                ready.prId(),
                gateRevisionRef);
        if (requireNonNull(count, "gate revision count is null") != 1) {
            throw new StaleOwnerRevisionException(
                    "ready gate revision does not match its sealed subject");
        }
    }

    private void assertReadyFinalState(
            AgentRun run,
            ReadyForReviewRequest ready,
            String gateRevisionRef,
            String readinessFailureCode)
    {
        if (gateRevisionRef != null) {
            assertReadyGateRevision(run.runId(), ready, gateRevisionRef);
            return;
        }
        AgentResult result = resultForRun(run.runId()).orElseThrow();
        Integer attention = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_task_lifecycle_revision
                WHERE task_id = ? AND to_status = 'NEEDS_ATTENTION'
                  AND operation_id = ? AND reason_code = ?
                  AND evidence_ref = ?
                """,
                Integer.class,
                ready.taskId(),
                ready.operationId(),
                readinessFailureCode,
                "ready-request:" + ready.requestId());
        if (requireNonNull(attention, "ready attention count is null") != 1
                || result.resultId() == null) {
            throw new IllegalStateException(
                    "ready failure did not settle typed attention");
        }
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
            AgentProcessAttempt processAttempt =
                    requireExactStoppedCompletion(
                            runId, claim.generation(), terminalOutcome,
                            finalContent, errorRef);
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
            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), terminalOutcome,
                    finalContent, errorRef);
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
                AgentProcessAttempt stopped = requireExactStoppedCompletion(
                        runId, claim.generation(), terminalOutcome,
                        finalContent, errorRef);
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
            AgentProcessAttempt processAttempt =
                    requireExactStoppedCompletion(
                            runId, claim.generation(), terminalOutcome,
                            finalContent, errorRef);
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
                GateIntent.CI_UPDATE,
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

            AgentProcessAttempt stopped = requireExactStoppedCompletion(
                    runId, claim.generation(), stored.terminalOutcome(),
                    stored.finalContent(), stored.errorRef());
            if (!stored.stopProofRef().equals(stopped.stopProofRef())) {
                throw new IllegalStateException(
                        "finalized writer stop proof changed");
            }

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

    public Optional<Task> taskForRequestKey(String requestKey)
    {
        requireText(requestKey, "requestKey");
        return taskByRequestKey(requestKey);
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

    public Optional<AgentRun> run(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                "SELECT * FROM flow_runtime_agent_run WHERE run_id = ?",
                (result, row) -> readRun(result),
                runId).stream().findFirst();
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

    public Optional<ReviewerRequest> reviewerRequest(String requestId)
    {
        requireText(requestId, "requestId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_reviewer_request
                WHERE request_id = ?
                """,
                (result, row) -> readReviewerRequest(result),
                requestId).stream().findFirst();
    }

    public Optional<ReviewerRequest> reviewerRequestForParentRun(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_reviewer_request
                WHERE parent_run_id = ?
                """,
                (result, row) -> readReviewerRequest(result),
                runId).stream().findFirst();
    }

    public Optional<ReviewerRequest> reviewerRequestForReviewerRun(
            String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                """
                SELECT q.* FROM flow_runtime_reviewer_request q
                JOIN flow_runtime_agent_run r
                  ON r.operation_id = q.reviewer_operation_id
                WHERE r.run_id = ?
                """,
                (result, row) -> readReviewerRequest(result),
                runId).stream().findFirst();
    }

    private TaskTerminalRequest reserveTaskTerminalRequest(
            String runId,
            TaskTerminalRequestKind kind,
            String requestId,
            Instant createdAt)
    {
        requireText(runId, "runId");
        requireNonNull(kind, "kind is null");
        requireText(requestId, "requestId");
        requireNonNull(createdAt, "createdAt is null");
        {
            Optional<TaskTerminalRequest> existing = taskTerminalRequest(runId);
            if (existing.isPresent()) {
                TaskTerminalRequest request = existing.orElseThrow();
                if (request.kind() != kind
                        || !request.requestId().equals(requestId)) {
                    throw new StaleCapabilityException(
                            "Task run already owns another terminal request");
                }
                return request;
            }
            AgentRun run = requireRun(runId);
            if (run.role() != AgentRole.TASK_AGENT
                    || run.state() != RunState.RUNNING) {
                throw new StaleCapabilityException(
                        "terminal request requires an active Task run");
            }
            int inserted = jdbc.update(
                    """
                    INSERT INTO flow_runtime_task_terminal_request (
                        run_id, kind, request_id, created_at
                    ) VALUES (?, ?, ?, ?)
                    """,
                    runId,
                    kind.name(),
                    requestId,
                    createdAt.toEpochMilli());
            if (inserted != 1) {
                throw new StaleCapabilityException(
                        "terminal request changed during reservation");
            }
            return taskTerminalRequest(runId).orElseThrow();
        }
    }

    /** Narrow capability-validated reservation for the terminal ready tool. */
    public synchronized ReadyForReviewRequest reserveReadyForReviewRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            String processAttemptId,
            String capabilityId,
            String requestId,
            String prId,
            String subjectRef,
            String subjectDigest,
            String actionRef,
            String actionDigest,
            Instant createdAt)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(processAttemptId, "processAttemptId");
        requireText(capabilityId, "capabilityId");
        requireText(requestId, "requestId");
        requireText(prId, "prId");
        requireText(subjectRef, "subjectRef");
        requireText(subjectDigest, "subjectDigest");
        requireText(actionRef, "actionRef");
        requireText(actionDigest, "actionDigest");
        requireNonNull(createdAt, "createdAt is null");
        return inTransaction(() -> {
            Optional<ReadyForReviewRequest> existing =
                    readyForReviewRequestForRun(runId);
            if (existing.isPresent()) {
                ReadyForReviewRequest request = existing.orElseThrow();
                if (!request.requestId().equals(requestId)
                        || !request.prId().equals(prId)
                        || !request.subjectRef().equals(subjectRef)
                        || !request.subjectDigest().equals(subjectDigest)
                        || !request.actionRef().equals(actionRef)
                        || !request.actionDigest().equals(actionDigest)) {
                    throw new StaleCapabilityException(
                            "ready request replay changed identity");
                }
                assertTaskTerminalRequest(
                        runId,
                        TaskTerminalRequestKind.READY_FOR_REVIEW,
                        requestId);
                return request;
            }
            assertInProcessWriterToolCapability(
                    runId, claim, fence, capabilityId);
            AgentProcessAttempt process = requireProcessAttempt(
                    processAttemptId);
            AgentRun run = requireRun(runId);
            Operation operation = requireOperation(claim.operationId());
            PendingWork input = selectedInput(operation);
            ReviewerRequest reviewer = reviewerRequestForReviewerRun(
                    input.externalKey()).orElseThrow(() ->
                            new StaleCapabilityException(
                                    "ready request has no reviewer subject"));
            AgentResult reviewerResult = resultForRun(input.externalKey())
                    .orElseThrow(() -> new StaleCapabilityException(
                            "ready request has no durable reviewer result"));
            if (!process.runId().equals(runId)
                    || !process.capabilityId().equals(capabilityId)
                    || !run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.TASK_AGENT
                    || run.state() != RunState.RUNNING
                    || run.wakeKind() != WakeKind.AGENT_RESULT_READY
                    || input.kind() != PendingKind.AGENT_RESULT_READY
                    || run.intendedGateKind() != input.intendedGateKind()
                    || reviewer.intendedGateKind()
                            != run.intendedGateKind()
                    || !input.pendingId().equals(
                            requireTextResult(run.inputRef(), "inbox:"))
                    || !input.agentResultId().equals(
                            reviewerResult.resultId())
                    || !input.subjectHead().equals(
                            reviewer.reviewedHeadSha())
                    || !reviewer.taskId().equals(operation.taskId())) {
                throw new StaleCapabilityException(
                        "ready request requires the exact live Task capability");
            }
            int inserted = jdbc.update(
                    """
                    INSERT INTO flow_runtime_ready_for_review_request (
                        request_id, run_id, operation_id, task_id, pr_id,
                        subject_ref, subject_digest, action_ref, action_digest,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    requestId,
                    runId,
                    operation.operationId(),
                    operation.taskId(),
                    prId,
                    subjectRef,
                    subjectDigest,
                    actionRef,
                    actionDigest,
                    createdAt.toEpochMilli());
            reserveTaskTerminalRequest(
                    runId,
                    TaskTerminalRequestKind.READY_FOR_REVIEW,
                    requestId,
                    createdAt);
            if (inserted != 1) {
                throw new StaleCapabilityException(
                        "ready request changed during reservation");
            }
            return readyForReviewRequest(requestId).orElseThrow();
        });
    }

    public Optional<ReadyForReviewRequest> readyForReviewRequest(
            String requestId)
    {
        requireText(requestId, "requestId");
        return readyForReviewRequests(
                "WHERE request_id = ?", requestId).stream().findFirst();
    }

    public Optional<ReadyForReviewRequest> readyForReviewRequestForRun(
            String runId)
    {
        requireText(runId, "runId");
        return readyForReviewRequests(
                "WHERE run_id = ?", runId).stream().findFirst();
    }

    /** Reads the immutable attention disposition of one settled ready run. */
    public Optional<String> readyAttentionReasonForRun(String runId)
    {
        ReadyForReviewRequest ready = readyForReviewRequestForRun(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown ready request"));
        return jdbc.queryForList(
                """
                SELECT reason_code
                FROM flow_runtime_task_lifecycle_revision
                WHERE task_id = ? AND operation_id = ?
                  AND to_status = 'NEEDS_ATTENTION'
                  AND evidence_ref = ?
                """,
                String.class,
                ready.taskId(),
                ready.operationId(),
                "ready-request:" + ready.requestId()).stream().findFirst();
    }

    private List<ReadyForReviewRequest> readyForReviewRequests(
            String predicate, String value)
    {
        return jdbc.query(
                "SELECT * FROM flow_runtime_ready_for_review_request "
                        + predicate,
                (result, row) -> new ReadyForReviewRequest(
                        result.getString("request_id"),
                        result.getString("run_id"),
                        result.getString("operation_id"),
                        result.getString("task_id"),
                        result.getString("pr_id"),
                        result.getString("subject_ref"),
                        result.getString("subject_digest"),
                        result.getString("action_ref"),
                        result.getString("action_digest"),
                        instant(result, "created_at")),
                value);
    }

    public Optional<TaskTerminalRequest> taskTerminalRequest(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_task_terminal_request
                WHERE run_id = ?
                """,
                (result, row) -> new TaskTerminalRequest(
                        result.getString("run_id"),
                        TaskTerminalRequestKind.valueOf(
                                result.getString("kind")),
                        result.getString("request_id"),
                        instant(result, "created_at")),
                runId).stream().findFirst();
    }

    private void assertTaskTerminalRequest(
            String runId,
            TaskTerminalRequestKind kind,
            String requestId)
    {
        TaskTerminalRequest request = taskTerminalRequest(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "terminal request seal is missing"));
        if (request.kind() != kind
                || !request.requestId().equals(requestId)) {
            throw new IllegalStateException(
                    "terminal request seal changed identity");
        }
    }

    private ReviewerRequest reviewerRequestForOperation(String operationId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_reviewer_request
                WHERE reviewer_operation_id = ?
                """,
                (result, row) -> readReviewerRequest(result),
                operationId).stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "operation has no ReviewerRequest"));
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
            GateIntent intendedGateKind,
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
                    || !Objects.equals(pending.agentResultId(), agentResultId)
                    || pending.intendedGateKind() != intendedGateKind) {
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
                    agent_result_id, intended_gate_kind,
                    work_watermark, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                intendedGateKind == null ? null : intendedGateKind.name(),
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
            case INITIAL_TASK -> pending.intendedGateKind()
                        == GateIntent.INITIAL_PUBLISH
                    && pending.externalKey().equals(task.taskId())
                    && pending.prId() == null
                    && pending.agentResultId() == null
                    && pending.payloadRef().equals(
                            "task-goal:" + task.taskId())
                    && pending.subjectHead().equals(task.currentHeadSha())
                    && task.prId() == null
                    && task.currentChangeSetRevisionId() == null;
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
            case CI_FIX_READY -> {
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
            case AGENT_RESULT_READY -> {
                AgentRun reviewer = run(pending.externalKey()).orElse(null);
                AgentResult result = resultForRun(
                        pending.externalKey()).orElse(null);
                ReviewerRequest request = reviewerRequestForReviewerRun(
                        pending.externalKey()).orElse(null);
                PullRequestSubject pr = task.prId() == null
                        ? null : pullRequest(task.prId()).orElse(null);
                yield reviewer != null && result != null && request != null
                        && pr != null
                        && reviewer.role()
                            == AgentRole.ADVERSARIAL_REVIEWER
                        && reviewer.operationId().equals(
                                request.reviewerOperationId())
                        && result.runId().equals(reviewer.runId())
                        && Objects.equals(
                                pending.agentResultId(), result.resultId())
                        && pending.payloadRef().equals(reviewerResultPayload(
                                request.requestId(), result.resultId()))
                        && pending.intendedGateKind()
                            == request.intendedGateKind()
                        && pending.taskId().equals(request.taskId())
                        && Objects.equals(pending.prId(), pr.prId())
                        && pr.taskId().equals(task.taskId())
                        && request.changeSetRevisionId().equals(
                                task.currentChangeSetRevisionId())
                        && request.reviewedHeadSha().equals(
                                task.currentHeadSha())
                        && pending.subjectHead().equals(
                                request.reviewedHeadSha());
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

    private void cancelReviewerBlockedReconciliation(String taskId)
    {
        List<BlockedReconciliation> blocked = jdbc.query(
                """
                SELECT o.operation_id, o.state, d.delivery_state
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                WHERE o.task_id = ? AND o.kind = 'RECONCILE_TASK'
                  AND o.state IN ('READY', 'WAITING')
                """,
                (result, row) -> new BlockedReconciliation(
                        result.getString("operation_id"),
                        OperationState.valueOf(result.getString("state")),
                        result.getString("delivery_state")),
                taskId);
        for (BlockedReconciliation reconciliation : blocked) {
            int updated = jdbc.update(
                    """
                    UPDATE flow_runtime_operation
                    SET state = 'CANCELED',
                        result_ref = 'REVIEWER_RESULT_ADVANCED'
                    WHERE operation_id = ? AND state = ?
                    """,
                    reconciliation.operationId(),
                    reconciliation.state().name());
            int ticketUpdated;
            if (reconciliation.state() == OperationState.READY
                    && reconciliation.deliveryState().equals("AVAILABLE")) {
                ticketUpdated = jdbc.update(
                        """
                        UPDATE flow_runtime_dispatch_ticket
                        SET delivery_state = 'DONE'
                        WHERE operation_id = ?
                          AND delivery_state = 'AVAILABLE'
                        """,
                        reconciliation.operationId());
            }
            else if (reconciliation.state() == OperationState.WAITING
                    && reconciliation.deliveryState().equals("DONE")) {
                ticketUpdated = 1;
            }
            else {
                ticketUpdated = 0;
            }
            if (updated != 1 || ticketUpdated != 1) {
                throw new StaleOwnerRevisionException(
                        "reviewer-blocked reconciliation changed concurrently");
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

    void insertOperationAndTicket(
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

    private boolean hasNonterminalPublish(String taskId)
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

    void assertCurrentClaim(
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

    ExpiredClaim requireExpiredClaim(
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

    private AgentProcessAttempt requireExactStoppedCompletion(
            String runId,
            long generation,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        AgentProcessAttempt stopped = requireStoppedProcessAttempt(
                runId, generation);
        assertStoppedCompletion(
                stopped.processAttemptId(), terminalOutcome,
                finalContent, errorRef);
        return stopped;
    }

    private StoppedAgentCompletion requireStoppedCompletion(
            String processAttemptId)
    {
        AgentProcessAttempt attempt = requireProcessAttempt(processAttemptId);
        if (attempt.state() != ProcessAttemptState.STOPPED
                || attempt.stopProofRef() == null) {
            throw new IllegalStateException(
                    "process attempt has no stopped completion");
        }
        StoppedAgentCompletion completion = jdbc.query(
                """
                SELECT process_attempt_id, completion_outcome,
                       completion_content, completion_error_ref,
                       completion_digest
                FROM flow_runtime_agent_process_attempt
                WHERE process_attempt_id = ? AND state = 'STOPPED'
                """,
                (result, row) -> new StoppedAgentCompletion(
                        result.getString("process_attempt_id"),
                        TerminalOutcome.valueOf(result.getString(
                                "completion_outcome")),
                        result.getString("completion_content"),
                        result.getString("completion_error_ref"),
                        result.getString("completion_digest")),
                processAttemptId).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "stopped completion is missing"));
        String expected = stoppedCompletionDigest(
                processAttemptId, attempt.stopProofRef(),
                completion.terminalOutcome(), completion.finalContent(),
                completion.errorRef());
        if (!completion.completionDigest().equals(expected)) {
            throw new IllegalStateException(
                    "stopped completion digest changed");
        }
        return completion;
    }

    private void assertStoppedCompletion(
            String processAttemptId,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        StoppedAgentCompletion stored = requireStoppedCompletion(
                processAttemptId);
        if (stored.terminalOutcome() != terminalOutcome
                || !Objects.equals(stored.finalContent(), finalContent)
                || !Objects.equals(stored.errorRef(), errorRef)) {
            throw new IllegalStateException(
                    "stopped completion replay changed content");
        }
    }

    private static String stoppedCompletionDigest(
            String processAttemptId,
            String stopProofRef,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        return stableId(
                "in-process-stopped-completion",
                processAttemptId,
                stopProofRef,
                terminalOutcome.name(),
                finalContent == null ? "<null>" : finalContent,
                errorRef == null ? "<null>" : errorRef);
    }

    private static void assertCompletion(
            TerminalOutcome terminalOutcome, String errorRef)
    {
        requireNonNull(terminalOutcome, "terminalOutcome is null");
        if (terminalOutcome != TerminalOutcome.COMPLETED) {
            requireText(errorRef, "errorRef");
        }
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

    private void assertReviewerTerminationAuthority(
            String processAttemptId, Claim claim)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_agent_process_attempt a
                JOIN flow_runtime_agent_run r ON r.run_id = a.run_id
                JOIN flow_runtime_operation o
                  ON o.operation_id = a.operation_id
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = a.operation_id
                JOIN flow_runtime_reviewer_request q
                  ON q.reviewer_operation_id = a.operation_id
                JOIN flow_runtime_agent_session s
                  ON s.session_id = r.session_id
                WHERE a.process_attempt_id = ?
                  AND a.operation_id = ?
                  AND a.claim_generation = ?
                  AND a.claim_token_digest = ?
                  AND d.claim_generation = a.claim_generation
                  AND d.claim_token = ? AND d.claim_owner = ?
                  AND d.delivery_state = 'CLAIMED'
                  AND o.state = 'CLAIMED'
                  AND o.kind = 'RUN_REVIEWER'
                  AND o.owner_kind = 'REVIEW_REQUEST'
                  AND o.owner_id = q.request_id
                  AND r.role = 'ADVERSARIAL_REVIEWER'
                  AND r.state = 'RUNNING'
                  AND s.role = 'ADVERSARIAL_REVIEWER'
                  AND s.state = 'RUNNING'
                  AND s.last_run_id = r.run_id
                """,
                Integer.class,
                processAttemptId,
                claim.operationId(),
                claim.generation(),
                claimTokenDigest(claim),
                claim.claimToken(),
                claim.workerId());
        if (requireNonNull(
                matches, "review process owner count is null") != 1) {
            throw new StaleCapabilityException(
                    "review termination authority is stale");
        }
    }

    private void assertCiLearningTerminationAuthority(
            String processAttemptId, Claim claim)
    {
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_agent_process_attempt a
                JOIN flow_runtime_agent_run r ON r.run_id = a.run_id
                JOIN flow_runtime_agent_session s
                  ON s.session_id = r.session_id
                JOIN flow_runtime_operation o
                  ON o.operation_id = a.operation_id
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = a.operation_id
                JOIN flow_ci_learning_subject l
                  ON l.operation_id = o.operation_id
                 AND l.subject_id = o.input_ref
                 AND l.subject_digest = o.subject_digest
                WHERE a.process_attempt_id = ? AND a.operation_id = ?
                  AND a.claim_generation = ?
                  AND a.claim_token_digest = ?
                  AND d.claim_generation = a.claim_generation
                  AND d.claim_token = ? AND d.claim_owner = ?
                  AND d.delivery_state = 'CLAIMED'
                  AND o.state = 'CLAIMED'
                  AND o.kind = 'RUN_CI_LEARNING'
                  AND o.owner_kind = 'CI_UPDATE_RECEIPT'
                  AND o.owner_id = l.receipt_id
                  AND r.role = 'CI_LEARNER' AND r.state = 'RUNNING'
                  AND s.role = 'CI_LEARNER' AND s.state = 'RUNNING'
                  AND s.last_run_id = r.run_id
                """,
                Integer.class, processAttemptId, claim.operationId(),
                claim.generation(), claimTokenDigest(claim),
                claim.claimToken(), claim.workerId());
        if (requireNonNull(exact,
                "CI learner termination count is null") != 1) {
            throw new StaleCapabilityException(
                    "CI learner termination authority is stale");
        }
    }

    private static void assertCiLearningProcessIdentity(
            AgentProcessAttempt attempt,
            InProcessCiLearningAgentSupervisor.TerminatedThreadWitness witness)
    {
        Thread thread = witness.thread();
        if (thread.getState() != Thread.State.TERMINATED
                || !Long.valueOf(witness.jvmPid()).equals(attempt.jvmPid())
                || !witness.jvmStartedAt().equals(attempt.jvmStartedAt())
                || !Long.valueOf(thread.threadId()).equals(
                        attempt.threadId())) {
            throw new StaleCapabilityException(
                    "terminated CI learner identity is stale");
        }
    }

    private static void assertReviewerProcessIdentity(
            AgentProcessAttempt attempt,
            InProcessReviewerAgentSupervisor.TerminatedThreadWitness witness)
    {
        Thread thread = witness.thread();
        if (thread.getState() != Thread.State.TERMINATED
                || !Long.valueOf(witness.jvmPid()).equals(attempt.jvmPid())
                || !witness.jvmStartedAt().equals(attempt.jvmStartedAt())
                || !Long.valueOf(thread.threadId())
                        .equals(attempt.threadId())) {
            throw new StaleCapabilityException(
                    "terminated reviewer execution identity is stale");
        }
    }

    boolean ticketMatches(
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

    private void quarantineExpiredReviewerProcessAttempt(ExpiredClaim expired)
    {
        String reason = "REVIEWER_PROCESS_RECOVERY_REQUIRED";
        settleDispatch(
                expired.operationId(),
                OperationState.FAILED,
                reason + ":" + expired.processAttemptId());
        Task task = requireTask(expired.taskId());
        if (task.status() == TaskStatus.NEEDS_ATTENTION
                && reviewerRecoveryRef(expired.processAttemptId()).equals(
                        task.waitingMutationStateRef())) {
            return;
        }
        if (task.status() != TaskStatus.ACTIVE
                || task.selectedWriterOperationId() != null
                || writerFence(task.taskId()).isPresent()) {
            throw new StaleOwnerRevisionException(
                    "expired reviewer cannot quarantine this Task state");
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
                    current_lifecycle_revision_id = ?,
                    waiting_mutation_state_ref = ?
                WHERE task_id = ? AND status = 'ACTIVE'
                  AND selected_writer_operation_id IS NULL
                  AND waiting_mutation_state_ref IS NULL
                  AND current_lifecycle_revision_id = ?
                """,
                attention.lifecycleRevisionId(),
                reviewerRecoveryRef(expired.processAttemptId()),
                task.taskId(),
                task.currentLifecycleRevisionId());
        if (taskUpdated != 1) {
            throw new StaleOwnerRevisionException(
                    "expired reviewer lost its Task owner revision");
        }
    }

    private void quarantineExpiredCiLearningAttempt(ExpiredClaim expired)
    {
        if (expired.processAttemptState() != ProcessAttemptState.ACTIVATED) {
            throw new IllegalStateException(
                    "only an activated CI learner needs quarantine");
        }
        AgentProcessAttempt attempt = requireProcessAttempt(
                expired.processAttemptId());
        AgentRun run = requireRun(expired.runId());
        AgentSession session = requireSession(run.sessionId());
        if (run.role() != AgentRole.CI_LEARNER
                || session.role() != AgentRole.CI_LEARNER) {
            throw new IllegalStateException(
                    "expired learner has a conflicting role");
        }
        Instant now = clock.instant();
        int quarantined = jdbc.update(
                """
                UPDATE flow_runtime_agent_process_attempt
                SET capability_revoked_at = COALESCE(
                        capability_revoked_at, ?),
                    quarantine_reason = 'IN_PROCESS_OWNER_UNAVAILABLE',
                    quarantined_at = ?
                WHERE process_attempt_id = ? AND state = 'ACTIVATED'
                  AND quarantine_reason IS NULL
                """,
                now.toEpochMilli(), now.toEpochMilli(),
                attempt.processAttemptId());
        int runClosed = jdbc.update(
                """
                UPDATE flow_runtime_agent_run
                SET state = 'FAILED',
                    failure_reason_code = 'CI_LEARNING_PROCESS_QUARANTINED',
                    completed_at = ?
                WHERE run_id = ? AND state = 'RUNNING'
                """,
                now.toEpochMilli(), run.runId());
        int sessionClosed = jdbc.update(
                """
                UPDATE flow_runtime_agent_session
                SET state = 'CLOSED',
                    close_reason = 'CI_LEARNING_PROCESS_QUARANTINED',
                    updated_at = ?
                WHERE session_id = ? AND state = 'RUNNING'
                  AND last_run_id = ?
                """,
                now.toEpochMilli(), session.sessionId(), run.runId());
        if (quarantined != 1 || runClosed != 1 || sessionClosed != 1) {
            throw new StaleOwnerRevisionException(
                    "expired CI learner changed during quarantine");
        }
        settleDispatch(
                expired.operationId(), OperationState.FAILED,
                "CI_LEARNING_PROCESS_QUARANTINED:"
                        + attempt.processAttemptId());
    }

    /**
     * Stores only runtime-owned STOPPED recovery facts. The CI owner must add
     * its lesson/completion fact in the same outer transaction.
     */
    public synchronized StoppedCiLearningResult
            finishExpiredStoppedCiLearningProcess(
                    String operationId, long generation)
    {
        requireText(operationId, "operationId");
        return inTransaction(() -> finishExpiredStoppedCiLearningProcess0(
                operationId, generation));
    }

    public synchronized void assertStoppedCiLearningRecoveryReplay(
            String operationId, long generation, String resultId)
    {
        requireText(operationId, "operationId");
        requireText(resultId, "resultId");
        Integer exact = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_runtime_operation o
                JOIN flow_runtime_dispatch_ticket d
                  ON d.operation_id = o.operation_id
                JOIN flow_runtime_agent_run r
                  ON r.operation_id = o.operation_id
                JOIN flow_runtime_agent_session s
                  ON s.session_id = r.session_id
                 AND s.last_run_id = r.run_id
                JOIN flow_runtime_agent_process_attempt a
                  ON a.run_id = r.run_id
                 AND a.claim_generation = d.claim_generation
                JOIN flow_runtime_agent_result x
                  ON x.run_id = r.run_id
                 AND x.result_id = o.result_ref
                WHERE o.operation_id = ? AND o.kind = 'RUN_CI_LEARNING'
                  AND o.owner_kind = 'CI_UPDATE_RECEIPT'
                  AND o.state IN ('SUCCEEDED', 'FAILED', 'CANCELED')
                  AND o.result_ref = ?
                  AND d.delivery_state = 'DONE'
                  AND d.claim_generation = ?
                  AND r.role = 'CI_LEARNER'
                  AND r.state IN ('COMPLETED', 'FAILED', 'CANCELED')
                  AND s.role = 'CI_LEARNER' AND s.state = 'CLOSED'
                  AND a.state = 'STOPPED'
                  AND a.stop_proof_ref IS NOT NULL
                  AND a.completion_outcome = x.terminal_outcome
                  AND a.completion_content IS x.final_content
                  AND a.completion_error_ref IS x.error_ref
                  AND x.stop_proof_ref = a.stop_proof_ref
                  AND r.state = CASE x.terminal_outcome
                        WHEN 'COMPLETED' THEN 'COMPLETED'
                        WHEN 'FAILED' THEN 'FAILED'
                        ELSE 'CANCELED' END
                  AND o.state = CASE x.terminal_outcome
                        WHEN 'COMPLETED' THEN 'SUCCEEDED'
                        WHEN 'FAILED' THEN 'FAILED'
                        ELSE 'CANCELED' END
                  AND s.close_reason =
                        'CI_LEARNING_' || x.terminal_outcome
                """,
                Integer.class, operationId, resultId, generation);
        if (requireNonNull(exact,
                "stopped learner replay count is null") != 1) {
            throw new IllegalStateException(
                    "stopped CI learner recovery replay is inconsistent");
        }
    }

    private StoppedCiLearningResult finishExpiredStoppedCiLearningProcess0(
            String operationId, long generation)
    {
        ExpiredClaim expired = requireExpiredClaim(operationId, generation);
        if (expired.kind() != OperationKind.RUN_CI_LEARNING
                || expired.processAttemptState()
                        != ProcessAttemptState.STOPPED) {
            throw new IllegalArgumentException(
                    "claim is not a stopped CI learner");
        }
        AgentProcessAttempt attempt = requireProcessAttempt(
                expired.processAttemptId());
        AgentRun run = requireRun(expired.runId());
        AgentSession session = requireSession(run.sessionId());
        StoppedAgentCompletion completion = requireStoppedCompletion(
                attempt.processAttemptId());
        if (attempt.state() != ProcessAttemptState.STOPPED
                || attempt.stopProofRef() == null
                || run.role() != AgentRole.CI_LEARNER
                || run.state() != RunState.RUNNING
                || session.role() != AgentRole.CI_LEARNER
                || session.state() != SessionState.RUNNING) {
            throw new IllegalStateException(
                    "stopped CI learner recovery graph is invalid");
        }
        Instant now = clock.instant();
        String resultId = stableId("agent-result", run.runId());
        int resultStored = jdbc.update(
                """
                INSERT INTO flow_runtime_agent_result (
                    result_id, run_id, terminal_outcome, final_content,
                    error_ref, stop_proof_ref, stored_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                resultId, run.runId(),
                completion.terminalOutcome().name(),
                completion.finalContent(), completion.errorRef(),
                attempt.stopProofRef(),
                now.toEpochMilli());
        RunState runState = switch (completion.terminalOutcome()) {
            case COMPLETED -> RunState.COMPLETED;
            case FAILED -> RunState.FAILED;
            case CANCELED -> RunState.CANCELED;
        };
        int runClosed = jdbc.update(
                "UPDATE flow_runtime_agent_run SET state = ?, "
                        + "failure_reason_code = ?, completed_at = ? "
                        + "WHERE run_id = ? AND state = 'RUNNING'",
                runState.name(), completion.errorRef(),
                now.toEpochMilli(), run.runId());
        int sessionClosed = jdbc.update(
                "UPDATE flow_runtime_agent_session SET state = 'CLOSED', "
                        + "close_reason = ?, updated_at = ? "
                        + "WHERE session_id = ? AND state = 'RUNNING' "
                        + "AND last_run_id = ?",
                "CI_LEARNING_" + completion.terminalOutcome().name(),
                now.toEpochMilli(), session.sessionId(), run.runId());
        if (resultStored != 1 || runClosed != 1
                || sessionClosed != 1) {
            throw new StaleOwnerRevisionException(
                    "stopped CI learner changed during recovery");
        }
        OperationState operationState = switch (
                completion.terminalOutcome()) {
            case COMPLETED -> OperationState.SUCCEEDED;
            case FAILED -> OperationState.FAILED;
            case CANCELED -> OperationState.CANCELED;
        };
        settleDispatch(expired.operationId(), operationState, resultId);
        return new StoppedCiLearningResult(
                requireResult(resultId), run.runId(), run.inputRef());
    }

    private static String reviewerRecoveryRef(String processAttemptId)
    {
        return "REVIEWER_PROCESS_RECOVERY_REQUIRED:" + processAttemptId;
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
                  AND NOT EXISTS (
                      SELECT 1 FROM flow_runtime_operation po
                      WHERE po.task_id = t.task_id AND po.kind = 'PUBLISH'
                        AND po.state IN (
                            'READY', 'CLAIMED', 'WAITING', 'RETRYABLE'
                        )
                  )
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
            case ADVERSARIAL_REVIEWER -> throw new IllegalArgumentException(
                    "reviewers require a fresh request-bound session");
            case CI_LEARNER -> throw new IllegalArgumentException(
                    "learners require a fresh receipt-bound session");
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

    private boolean hasNonterminalReviewer(String taskId)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RUN_REVIEWER'
                  AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                """,
                Integer.class,
                taskId);
        return requireNonNull(count, "reviewer count is null") > 0;
    }

    private boolean hasClaimedOperation(String taskId)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation
                WHERE task_id = ? AND state = 'CLAIMED'
                  AND kind <> 'RUN_CI_LEARNING'
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
                  AND kind <> 'PUBLISH'
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
                      AND kind <> 'PUBLISH'
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

    static boolean isTerminal(TaskStatus status)
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
            case CREATED -> to == TaskStatus.CANCELED;
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

    Task requireTask(String taskId)
    {
        return task(taskId).orElseThrow(() ->
                new IllegalArgumentException("Unknown Task: " + taskId));
    }

    Operation requireOperation(String operationId)
    {
        return operation(operationId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Unknown operation: " + operationId));
    }

    PullRequestSubject requirePullRequest(String prId)
    {
        return pullRequest(prId).orElseThrow(() ->
                        new IllegalArgumentException("Unknown PR: " + prId));
    }

    private void assertRemoteIdentityMatches(
            String identityId,
            String provider,
            String repositoryExternalId,
            String repositoryOwner,
            String repositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String publicationReceiptId)
    {
        Integer matches = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_remote_identity
                WHERE remote_identity_id = ? AND provider = ?
                  AND repository_external_id = ?
                  AND repository_owner = ? AND repository_name = ?
                  AND head_repository_external_id = ?
                  AND head_repository_owner = ?
                  AND head_repository_name = ? AND pr_number = ?
                  AND pr_node_id = ? AND html_url = ?
                  AND publication_receipt_id = ?
                """,
                Integer.class,
                identityId,
                provider,
                repositoryExternalId,
                repositoryOwner,
                repositoryName,
                headRepositoryExternalId,
                headRepositoryOwner,
                headRepositoryName,
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

    private PendingWork selectedInput(Operation operation)
    {
        List<PendingWork> selected = jdbc.query(
                """
                SELECT * FROM flow_runtime_inbox
                WHERE selected_by_operation_id = ?
                  AND handled_by_operation_id IS NULL
                  AND terminal_reason IS NULL
                """,
                (result, row) -> readInbox(result),
                operation.operationId());
        if (selected.size() != 1) {
            throw new MutationRejectedException(
                    "Task Agent operation must own one current input");
        }
        PendingWork pending = selected.get(0);
        if (!operation.inputRef().equals("inbox:" + pending.pendingId())
                || pending.kind() == PendingKind.FINAL_RED) {
            throw new MutationRejectedException(
                    "Task Agent input does not match its selected operation");
        }
        return pending;
    }

    private ReviewRequestSnapshot initialReviewRequestSnapshot(
            String runId,
            Claim claim,
            WriterFence fence,
            String expectedChangeSetRevisionId)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        assertFenceRow(claim, fence);
        AgentRun run = requireRun(runId);
        Operation operation = requireOperation(claim.operationId());
        Task task = requireTask(operation.taskId());
        PendingWork input = selectedInput(operation);
        if (!run.operationId().equals(operation.operationId())
                || run.role() != AgentRole.TASK_AGENT
                || run.state() != RunState.RUNNING
                || run.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || (run.wakeKind() != WakeKind.INITIAL_TASK
                    && run.wakeKind() != WakeKind.AGENT_RESULT_READY)
                || input.intendedGateKind()
                        != GateIntent.INITIAL_PUBLISH
                || input.kind() != PendingKind.valueOf(
                        run.wakeKind().name())
                || task.status() != TaskStatus.ACTIVE
                || task.waitingMutationStateRef() != null
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())) {
            throw new MutationRejectedException(
                    "review request requires the exact INITIAL Task turn");
        }
        ChangeSetRevision current = requireChangeSetRevision(
                expectedChangeSetRevisionId);
        if (!current.taskId().equals(task.taskId())
                || !current.changeSetRevisionId().equals(
                        task.currentChangeSetRevisionId())
                || !current.headSha().equals(task.currentHeadSha())
                || !current.differsFromBase()) {
            throw new MutationRejectedException(
                    "INITIAL review change set is not current and nonempty");
        }
        if (run.wakeKind() == WakeKind.INITIAL_TASK) {
            if (run.inputChangeSetRevisionId() != null
                    || !isExactInitialTaskChain(
                            current, operation, run)) {
                throw new MutationRejectedException(
                        "INITIAL first change set has the wrong lineage");
            }
        }
        else {
            ChangeSetRevision initial = requireChangeSetRevision(
                    run.inputChangeSetRevisionId());
            if (!initial.taskId().equals(task.taskId())
                    || !initial.headSha().equals(run.headSha())) {
                throw new MutationRejectedException(
                        "INITIAL review continuation input changed");
            }
            requireTaskTurnDescendant(
                    initial, current, operation, run);
        }
        PullRequestSubject pr = requirePullRequest(task.prId());
        if (pr.published()
                || !pr.taskId().equals(task.taskId())
                || pr.currentRemoteHead() != null) {
            throw new MutationRejectedException(
                    "INITIAL review requires the unpublished local PR");
        }
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new MutationRejectedException(
                        "INITIAL review has no immutable base"));
        return new ReviewRequestSnapshot(
                task.taskId(), task.epoch(), operation.operationId(),
                operation.subjectDigest(), runId, input.pendingId(),
                input.externalKey(), Path.of(task.worktreePath()),
                task.branchName(), base.baseSha(), current.headSha(), null,
                null, null, null, current.changeSetRevisionId(),
                current.headTreeDigest(), current.diffDigest(),
                GateIntent.INITIAL_PUBLISH);
    }

    private boolean isExactInitialTaskChain(
            ChangeSetRevision current, Operation operation, AgentRun run)
    {
        ChangeSetRevision cursor = current;
        int traversed = 0;
        while (true) {
            if (++traversed > 256
                    || cursor.source() != ChangeSetSource.TASK_AGENT
                    || !cursor.sourceOperationId().equals(
                            operation.operationId())
                    || !Objects.equals(cursor.sourceRunId(), run.runId())) {
                return false;
            }
            if (cursor.previousChangeSetRevisionId() == null) {
                return true;
            }
            cursor = requireChangeSetRevision(
                    cursor.previousChangeSetRevisionId());
        }
    }

    /** Reads only the current revision proven to belong to this INITIAL run. */
    synchronized Optional<ChangeSetRevision> currentInitialTaskChangeSet(
            String runId)
    {
        AgentRun run = requireRun(runId);
        Operation operation = requireOperation(run.operationId());
        Task task = requireTask(operation.taskId());
        if (run.role() != AgentRole.TASK_AGENT
                || run.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || run.state() != RunState.RUNNING
                || operation.kind() != OperationKind.RUN_TASK_TURN) {
            throw new MutationRejectedException(
                    "run is not an active INITIAL Task turn");
        }
        Optional<ChangeSetRevision> current = currentChangeSet(task.taskId());
        if (current.isEmpty()) {
            if (run.inputChangeSetRevisionId() != null) {
                throw new MutationRejectedException(
                        "INITIAL continuation lost its change set");
            }
            return Optional.empty();
        }
        ChangeSetRevision revision = current.orElseThrow();
        if (run.inputChangeSetRevisionId() == null) {
            if (!isExactInitialTaskChain(revision, operation, run)) {
                throw new MutationRejectedException(
                        "current change set belongs to another INITIAL run");
            }
        }
        else {
            ChangeSetRevision initial = requireChangeSetRevision(
                    run.inputChangeSetRevisionId());
            requireTaskTurnDescendant(initial, revision, operation, run);
        }
        return current;
    }

    private ReviewRequestSnapshot reviewRequestSnapshot(
            String runId,
            Claim claim,
            WriterFence fence,
            String expectedInputChangeSetRevisionId,
            CiFixReviewOrigin origin)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        assertFenceRow(claim, fence);
        AgentRun run = requireRun(runId);
        Operation operation = requireOperation(claim.operationId());
        Task task = requireTask(operation.taskId());
        PendingWork input = selectedInput(operation);
        assertReviewOrigin(input, origin);
        if (!run.operationId().equals(operation.operationId())
                || run.role() != AgentRole.TASK_AGENT
                || run.state() != RunState.RUNNING
                || run.intendedGateKind() != GateIntent.CI_UPDATE
                || (run.wakeKind() != WakeKind.CI_FIX_READY
                    && run.wakeKind() != WakeKind.AGENT_RESULT_READY)
                || input.intendedGateKind() != GateIntent.CI_UPDATE
                || task.status() != TaskStatus.ACTIVE
                || task.waitingMutationStateRef() != null
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())) {
            throw new MutationRejectedException(
                    "review request requires the exact active CI-update turn");
        }
        if (!Objects.equals(run.inputChangeSetRevisionId(),
                expectedInputChangeSetRevisionId)) {
            throw new MutationRejectedException(
                    "review request changed the program-owned input change set");
        }
        ChangeSetRevision initial = requireChangeSetRevision(
                run.inputChangeSetRevisionId());
        ChangeSetRevision changeSet = requireChangeSetRevision(
                task.currentChangeSetRevisionId());
        if (!initial.taskId().equals(task.taskId())
                || !initial.headSha().equals(run.headSha())
                || !changeSet.taskId().equals(task.taskId())
                || !changeSet.headSha().equals(task.currentHeadSha())) {
            throw new MutationRejectedException(
                    "review request change set is no longer current");
        }
        requireTaskTurnDescendant(initial, changeSet, operation, run);
        PullRequestSubject pr = requirePullRequest(task.prId());
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || run.inputRemoteHeadSha() == null) {
            throw new MutationRejectedException(
                    "review request requires the exact published PR head");
        }
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new MutationRejectedException(
                        "review request has no immutable base"));
        return new ReviewRequestSnapshot(
                task.taskId(),
                task.epoch(),
                operation.operationId(),
                operation.subjectDigest(),
                runId,
                input.pendingId(),
                input.externalKey(),
                Path.of(task.worktreePath()),
                task.branchName(),
                base.baseSha(),
                changeSet.headSha(),
                run.inputRemoteHeadSha(),
                origin.pendingId(),
                origin.sourceKind().name(),
                origin.sourceId(),
                changeSet.changeSetRevisionId(),
                changeSet.headTreeDigest(),
                changeSet.diffDigest(),
                run.intendedGateKind());
    }

    private void assertReviewOrigin(
            PendingWork input, CiFixReviewOrigin origin)
    {
        requireNonNull(origin, "origin is null");
        if (input.kind() == PendingKind.CI_FIX_READY) {
            if (!origin.pendingId().equals(input.pendingId())
                    || !origin.sourceId().equals(input.externalKey())) {
                throw new MutationRejectedException(
                        "CI review origin does not match its selected input");
            }
            return;
        }
        ReviewerRequest predecessor = reviewerRequestForReviewerRun(
                input.externalKey()).orElseThrow(() ->
                        new MutationRejectedException(
                                "review continuation has no origin request"));
        if (!origin.pendingId().equals(
                    predecessor.originCiFixPendingId())
                || !origin.sourceKind().name().equals(
                    predecessor.originCiFixSourceKind())
                || !origin.sourceId().equals(
                    predecessor.originCiFixSourceId())) {
            throw new MutationRejectedException(
                    "review continuation changed its CI origin");
        }
    }

    private void requireTaskTurnDescendant(
            ChangeSetRevision initial,
            ChangeSetRevision current,
            Operation operation,
            AgentRun run)
    {
        ChangeSetRevision cursor = current;
        int traversed = 0;
        while (!cursor.changeSetRevisionId().equals(
                initial.changeSetRevisionId())) {
            if (++traversed > 256
                    || cursor.sequence() <= initial.sequence()
                    || cursor.source() != ChangeSetSource.TASK_AGENT
                    || !cursor.sourceOperationId().equals(
                            operation.operationId())
                    || !Objects.equals(cursor.sourceRunId(), run.runId())
                    || cursor.previousChangeSetRevisionId() == null) {
                throw new MutationRejectedException(
                        "review request current revision is not an exact Task-turn descendant");
            }
            cursor = requireChangeSetRevision(
                    cursor.previousChangeSetRevisionId());
        }
    }

    private void assertExactReviewerRequest(
            ReviewerRequest existing, ReviewerRequest requested)
    {
        if (!existing.requestId().equals(requested.requestId())
                || !existing.taskId().equals(requested.taskId())
                || !existing.parentOperationId().equals(
                        requested.parentOperationId())
                || !existing.parentRunId().equals(requested.parentRunId())
                || !existing.reviewerOperationId().equals(
                        requested.reviewerOperationId())
                || !existing.repositoryRoot().equals(
                        requested.repositoryRoot())
                || !existing.baseHeadSha().equals(requested.baseHeadSha())
                || !existing.reviewedHeadSha().equals(
                        requested.reviewedHeadSha())
                || !Objects.equals(existing.remoteHeadSha(),
                        requested.remoteHeadSha())
                || !Objects.equals(existing.originCiFixPendingId(),
                        requested.originCiFixPendingId())
                || !Objects.equals(existing.originCiFixSourceKind(),
                        requested.originCiFixSourceKind())
                || !Objects.equals(existing.originCiFixSourceId(),
                        requested.originCiFixSourceId())
                || !existing.changeSetRevisionId().equals(
                        requested.changeSetRevisionId())
                || !existing.localCheckPolicyRevisionId().equals(
                        requested.localCheckPolicyRevisionId())
                || !existing.headTreeDigest().equals(
                        requested.headTreeDigest())
                || !existing.diffDigest().equals(requested.diffDigest())
                || !existing.checkRunRefs().equals(requested.checkRunRefs())
                || existing.intendedGateKind()
                        != requested.intendedGateKind()) {
            throw new IllegalStateException(
                    "reviewer request redelivery changed identity");
        }
    }

    private void assertTaskReviewFinalState(
            AgentRun run, Optional<ReviewerRequest> request)
    {
        Task task = requireTask(
                requireOperation(run.operationId()).taskId());
        if (request.isPresent()) {
            ReviewerRequest reviewer = request.orElseThrow();
            Operation reviewerOperation = requireOperation(
                    reviewer.reviewerOperationId());
            if (!reviewer.parentRunId().equals(run.runId())
                    || reviewerOperation.kind()
                            != OperationKind.RUN_REVIEWER
                    || reviewerOperation.state() == OperationState.WAITING) {
                throw new IllegalStateException(
                        "completed parent did not make its reviewer eligible");
            }
        }
        else {
            PendingWork input = inbox(requireTextResult(
                    run.inputRef(), "inbox:")).orElseThrow();
            AgentResult result = resultForRun(run.runId()).orElseThrow();
            boolean consumedReviewerResult =
                    run.wakeKind() == WakeKind.AGENT_RESULT_READY
                    && run.intendedGateKind() == GateIntent.CI_UPDATE
                    && result.terminalOutcome() == TerminalOutcome.COMPLETED
                    && run.operationId().equals(
                            input.handledByOperationId());
            String requiredAttentionReason =
                    run.wakeKind() == WakeKind.AGENT_RESULT_READY
                            && run.intendedGateKind()
                                    == GateIntent.INITIAL_PUBLISH
                            ? "MISSING_INITIAL_TERMINAL_REQUEST"
                            : null;
            Integer attention = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM flow_runtime_task_lifecycle_revision
                    WHERE task_id = ? AND to_status = 'NEEDS_ATTENTION'
                      AND operation_id = ? AND evidence_ref = ?
                      AND (? IS NULL OR reason_code = ?)
                    """,
                    Integer.class,
                    task.taskId(),
                    run.operationId(),
                    "agent-result:" + result.resultId(),
                    requiredAttentionReason,
                    requiredAttentionReason);
            if (!run.operationId().equals(input.handledByOperationId())
                    || (!consumedReviewerResult
                        && requireNonNull(
                                attention, "attention count is null") != 1)) {
                throw new IllegalStateException(
                        "missing reviewer command did not settle its Task");
            }
        }
    }

    private PendingWork requireReviewerResultReady(
            ReviewerRequest request, AgentResult result)
    {
        PendingWork ready = jdbc.query(
                """
                SELECT * FROM flow_runtime_inbox
                WHERE agent_result_id = ? AND kind = 'AGENT_RESULT_READY'
                """,
                (row, number) -> readInbox(row),
                result.resultId()).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "reviewer result has no durable continuation"));
        if (!ready.taskId().equals(request.taskId())
                || !ready.externalKey().equals(result.runId())
                || !ready.subjectHead().equals(request.reviewedHeadSha())
                || !ready.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), result.resultId()))
                || ready.intendedGateKind()
                        != request.intendedGateKind()) {
            throw new IllegalStateException(
                    "reviewer continuation changed identity");
        }
        return ready;
    }

    private static String reviewerResultPayload(
            String requestId, String resultId)
    {
        return "reviewer-request:" + requestId + ":result:" + resultId;
    }

    private static String nullableId(String value)
    {
        return value == null ? "<none>" : value;
    }

    private ReviewerRequest requireReviewerRequest(String requestId)
    {
        return reviewerRequest(requestId).orElseThrow(() ->
                new IllegalArgumentException(
                        "Unknown ReviewerRequest: " + requestId));
    }

    private TaskAdmissionSnapshot taskAdmissionSnapshot(
            Claim claim,
            String expectedPendingId,
            String expectedChangeSetRevisionId,
            String expectedRemoteHead)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        Operation operation = requireOperation(claim.operationId());
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || !Objects.equals(operation.taskId(), claim.taskId())) {
            throw new MutationRejectedException(
                    "claim does not own a Task Agent turn");
        }
        Task task = requireTask(operation.taskId());
        if (task.status() != TaskStatus.ACTIVE
                || task.waitingMutationStateRef() != null
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())) {
            throw new MutationRejectedException(
                    "Task turn is not the selected active writer");
        }
        PendingWork pending = selectedInput(operation);
        if (!pending.pendingId().equals(expectedPendingId)
                || !pending.subjectHead().equals(task.currentHeadSha())
                || pending.intendedGateKind() == null
                || !pendingSubjectIsCurrent(task, pending)) {
            throw new MutationRejectedException(
                    "Task turn input is no longer current");
        }
        PullRequestSubject pr = requirePullRequest(task.prId());
        if (!Objects.equals(pending.prId(), pr.prId())
                || !pr.taskId().equals(task.taskId())
                || !pr.published()
                || !pr.currentRemoteHead().equals(expectedRemoteHead)) {
            throw new MutationRejectedException(
                    "Task turn remote PR subject is no longer current");
        }
        ChangeSetRevision changeSet = requireChangeSetRevision(
                expectedChangeSetRevisionId);
        if (!changeSet.taskId().equals(task.taskId())
                || !changeSet.changeSetRevisionId().equals(
                        task.currentChangeSetRevisionId())
                || !changeSet.headSha().equals(task.currentHeadSha())) {
            throw new MutationRejectedException(
                    "Task turn change set is no longer current");
        }
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new MutationRejectedException(
                        "Task turn has no immutable base revision"));
        return new TaskAdmissionSnapshot(
                task.taskId(),
                task.epoch(),
                operation.operationId(),
                operation.subjectDigest(),
                pending.pendingId(),
                pending.kind(),
                pending.externalKey(),
                pending.agentResultId(),
                pending.intendedGateKind(),
                pr.prId(),
                pr.currentRemoteHead(),
                Path.of(task.worktreePath()),
                task.branchName(),
                base.baseRevisionId(),
                base.baseSha(),
                changeSet.changeSetRevisionId(),
                changeSet.headSha(),
                changeSet.headTreeDigest(),
                changeSet.diffDigest());
    }

    private InitialTaskAdmissionSnapshot initialTaskAdmissionSnapshot(
            Claim claim)
    {
        assertCurrentClaim(claim, OperationState.CLAIMED);
        Operation operation = requireOperation(claim.operationId());
        if (operation.kind() != OperationKind.RUN_TASK_TURN
                || !Objects.equals(operation.taskId(), claim.taskId())) {
            throw new MutationRejectedException(
                    "claim does not own an INITIAL Task turn");
        }
        Task task = requireTask(operation.taskId());
        PendingWork input = selectedInput(operation);
        AgentSession session = requireSession(task.taskSessionId());
        if (task.status() != TaskStatus.ACTIVE
                || task.waitingMutationStateRef() != null
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())
                || input.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || (input.kind() != PendingKind.INITIAL_TASK
                    && input.kind() != PendingKind.AGENT_RESULT_READY)
                || !input.subjectHead().equals(task.currentHeadSha())
                || session.role() != AgentRole.TASK_AGENT) {
            throw new MutationRejectedException(
                    "INITIAL Task input is no longer current");
        }
        Optional<AgentRun> existingRun = runForOperation(
                operation.operationId());
        Optional<WriterFence> existingFence = writerFence(task.taskId());
        if (existingRun.isEmpty() && existingFence.isPresent()) {
            throw new MutationRejectedException(
                    "INITIAL Task admission has half a writer owner");
        }
        if (existingRun.isEmpty()) {
            if (session.state() != SessionState.IDLE) {
                throw new MutationRejectedException(
                        "INITIAL Task session is not idle");
            }
        }
        else {
            AgentRun run = existingRun.orElseThrow();
            if (session.state() != SessionState.RUNNING
                    || !Objects.equals(session.lastRunId(), run.runId())
                    || !run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.TASK_AGENT
                    || (run.state() != RunState.QUEUED
                        && run.state() != RunState.RUNNING)
                    || run.intendedGateKind()
                        != GateIntent.INITIAL_PUBLISH
                    || run.wakeKind() != WakeKind.valueOf(
                            input.kind().name())
                    || !run.inputRef().equals(operation.inputRef())) {
                throw new MutationRejectedException(
                        "INITIAL Task replay changed writer identity");
            }
            if (existingFence.isPresent()) {
                WriterFence ownedFence = existingFence.orElseThrow();
                assertFenceRow(claim, ownedFence);
                if (!ownedFence.operationId().equals(
                            operation.operationId())
                        || !ownedFence.headSha().equals(
                            task.currentHeadSha())) {
                    throw new MutationRejectedException(
                            "INITIAL Task replay changed writer fence");
                }
            }
            else {
                Integer attempts = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + "flow_runtime_agent_process_attempt "
                                + "WHERE run_id = ?",
                        Integer.class, run.runId());
                if (run.state() != RunState.QUEUED
                        || requireNonNull(attempts,
                                "process attempt count is null") != 0) {
                    throw new MutationRejectedException(
                            "INITIAL Task lost attempted writer authority");
                }
            }
        }
        TaskBaseRevision base = currentBaseRevision(task.taskId())
                .orElseThrow(() -> new MutationRejectedException(
                        "INITIAL Task has no immutable base"));
        ChangeSetRevision changeSet = task.currentChangeSetRevisionId() == null
                ? null : requireChangeSetRevision(
                        task.currentChangeSetRevisionId());
        PullRequestSubject pr = task.prId() == null
                ? null : requirePullRequest(task.prId());
        ReviewerRequest reviewer = null;
        String reviewerResultId = null;
        if (input.kind() == PendingKind.INITIAL_TASK) {
            if (!operation.ownerKind().equals("TASK")
                    || !operation.ownerId().equals(task.taskId())
                    || !input.externalKey().equals(task.taskId())
                    || changeSet != null || pr != null
                    || !task.currentHeadSha().equals(base.baseSha())) {
                throw new MutationRejectedException(
                        "first INITIAL Task input changed identity");
            }
        }
        else {
            if (!operation.ownerKind().equals("AGENT_RUN")
                    || !operation.ownerId().equals(input.externalKey())
                    || changeSet == null || pr == null || pr.published()
                    || !Objects.equals(input.prId(), pr.prId())
                    || !pr.taskId().equals(task.taskId())
                    || !pr.repositoryId().equals(task.repositoryId())
                    || !pr.branchName().equals(task.branchName())
                    || pr.currentRemoteHead() != null) {
                throw new MutationRejectedException(
                        "INITIAL review continuation changed identity");
            }
            AgentResult reviewerResult = resultForRun(input.externalKey())
                    .orElseThrow(() -> new MutationRejectedException(
                            "INITIAL review result is missing"));
            reviewer = reviewerRequestForReviewerRun(input.externalKey())
                    .orElseThrow(() -> new MutationRejectedException(
                            "INITIAL reviewer request is missing"));
            reviewerResultId = reviewerResult.resultId();
            AgentRun reviewerRun = requireRun(input.externalKey());
            Operation reviewerOperation = requireOperation(
                    reviewerRun.operationId());
            RunState expectedReviewerState = switch (
                    reviewerResult.terminalOutcome()) {
                case COMPLETED -> RunState.COMPLETED;
                case FAILED -> RunState.FAILED;
                case CANCELED -> RunState.CANCELED;
            };
            OperationState expectedReviewerOperationState = switch (
                    reviewerResult.terminalOutcome()) {
                case COMPLETED -> OperationState.SUCCEEDED;
                case FAILED -> OperationState.FAILED;
                case CANCELED -> OperationState.CANCELED;
            };
            ChangeSetRevision created = requireChangeSetRevision(
                    pr.createdFromChangeSetRevisionId());
            if (reviewerRun.role()
                        != AgentRole.ADVERSARIAL_REVIEWER
                    || reviewerRun.state() != expectedReviewerState
                    || !reviewerRun.operationId().equals(
                            reviewer.reviewerOperationId())
                    || reviewerOperation.state()
                            != expectedReviewerOperationState
                    || !Objects.equals(reviewerOperation.resultRef(),
                            reviewerResult.resultId())
                    || !Objects.equals(
                            input.agentResultId(), reviewerResult.resultId())
                    || reviewer.intendedGateKind()
                        != GateIntent.INITIAL_PUBLISH
                    || reviewer.remoteHeadSha() != null
                    || reviewer.originCiFixPendingId() != null
                    || reviewer.originCiFixSourceKind() != null
                    || reviewer.originCiFixSourceId() != null
                    || !reviewer.taskId().equals(task.taskId())
                    || !reviewer.changeSetRevisionId().equals(
                            changeSet.changeSetRevisionId())
                    || !reviewer.reviewedHeadSha().equals(
                            changeSet.headSha())
                    || !reviewer.baseHeadSha().equals(base.baseSha())
                    || !created.taskId().equals(task.taskId())
                    || !created.baseRevisionId().equals(
                            base.baseRevisionId())
                    || !input.payloadRef().equals(reviewerResultPayload(
                            reviewer.requestId(), reviewerResult.resultId()))) {
                throw new MutationRejectedException(
                        "INITIAL reviewer result is not exact and complete");
            }
        }
        return new InitialTaskAdmissionSnapshot(
                task.taskId(), task.epoch(), operation.operationId(),
                operation.subjectDigest(), input.pendingId(), input.kind(),
                input.externalKey(), reviewerResultId,
                Path.of(task.worktreePath()), task.branchName(),
                base.baseRevisionId(), base.baseSha(),
                changeSet == null ? null : changeSet.changeSetRevisionId(),
                task.currentHeadSha(),
                changeSet == null ? null : changeSet.headTreeDigest(),
                changeSet == null ? null : changeSet.diffDigest(),
                changeSet != null && changeSet.differsFromBase(),
                pr == null ? null : pr.prId(),
                reviewer == null ? null : reviewer.requestId());
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

    <T> T inTransaction(Supplier<T> work)
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
                result.getString("repository_owner"),
                result.getString("repository_name"),
                result.getString("goal_text"),
                result.getString("repository_root"),
                result.getString("git_common_dir"),
                result.getString("remote_name"),
                result.getString("base_ref"),
                result.getString("launch_digest"),
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
                result.getString("current_draft_revision_id"),
                result.getString("remote_identity_id"),
                result.getString("provider"),
                result.getString("repository_external_id"),
                result.getString("repository_owner"),
                result.getString("repository_name"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                number,
                result.getString("current_remote_head"));
    }

    private static PrDraftRevision readPrDraftRevision(ResultSet result)
            throws SQLException
    {
        return new PrDraftRevision(
                result.getString("draft_revision_id"),
                result.getString("pr_id"),
                result.getLong("sequence"),
                result.getString("change_set_revision_id"),
                result.getString("head_sha"),
                result.getString("title"),
                result.getString("body"),
                result.getString("draft_digest"),
                result.getString("created_by_run_id"),
                instant(result, "created_at"));
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
                result.getString("input_change_set_revision_id"),
                result.getString("input_remote_head_sha"),
                nullableEnum(result, "wake_kind", WakeKind.class),
                nullableEnum(result, "intended_gate_kind", GateIntent.class),
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
                nullableLong(result, "agent_pid"),
                nullableLong(result, "agent_pgid"),
                nullableInstant(result, "agent_started_at"),
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
                nullableEnum(
                        result, "intended_gate_kind", GateIntent.class),
                result.getLong("work_watermark"),
                result.getString("selected_by_operation_id"),
                result.getString("handled_by_operation_id"),
                result.getString("terminal_reason"));
    }

    private ReviewerRequest readReviewerRequest(ResultSet result)
            throws SQLException
    {
        String requestId = result.getString("request_id");
        List<String> checkRefs = jdbc.queryForList(
                """
                SELECT check_run_ref FROM flow_runtime_reviewer_check_ref
                WHERE request_id = ? ORDER BY ordinal
                """,
                String.class,
                requestId);
        return new ReviewerRequest(
                requestId,
                result.getString("task_id"),
                result.getString("parent_operation_id"),
                result.getString("parent_run_id"),
                result.getString("reviewer_operation_id"),
                result.getString("repository_root"),
                result.getString("base_head_sha"),
                result.getString("reviewed_head_sha"),
                result.getString("remote_head_sha"),
                result.getString("origin_ci_fix_pending_id"),
                result.getString("origin_ci_fix_source_kind"),
                result.getString("origin_ci_fix_source_id"),
                result.getString("change_set_revision_id"),
                result.getString("local_check_policy_revision_id"),
                result.getString("head_tree_digest"),
                result.getString("diff_digest"),
                checkRefs,
                GateIntent.valueOf(result.getString("intended_gate_kind")),
                instant(result, "created_at"));
    }

    private static String prSubjectSql()
    {
        return """
                SELECT p.*, r.provider, r.repository_external_id,
                       r.repository_owner, r.repository_name,
                       r.head_repository_external_id,
                       r.head_repository_owner, r.head_repository_name,
                       r.pr_number
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

    static String stableId(String namespace, String... components)
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

    private static void requireGitHubRepositoryLocator(
            String owner, String name)
    {
        if (!owner.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
                || !name.matches("[A-Za-z0-9_.-]{1,100}")) {
            throw new IllegalArgumentException(
                    "GitHub repository owner/name is invalid");
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

    public record CiObservationSubject(
            String receiptId,
            String receiptOperationId,
            String planId,
            String stepId,
            String probeId,
            String receiptDigest,
            String prId,
            String taskId,
            String repositoryId,
            String targetBaseRef,
            String scopeKey,
            String provider,
            String repositoryExternalId,
            String repositoryOwner,
            String repositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            Long prNumber,
            String prNodeId,
            String branchName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead) {}

    private record PublishClaimCandidate(
            String operationId,
            String taskId,
            String prId,
            String kind,
            long generation) {}

    private record InitialPublicationIdentity(
            String prId,
            String proposedHead,
            String baseExternalId,
            String baseOwner,
            String baseName,
            String headExternalId,
            String headOwner,
            String headName,
            long prNumber,
            String prNodeId,
            String htmlUrl) {}

    private record TaskAdmissionSnapshot(
            String taskId,
            long taskEpoch,
            String operationId,
            String operationSubjectDigest,
            String pendingId,
            PendingKind pendingKind,
            String pendingExternalKey,
            String agentResultId,
            GateIntent intendedGateKind,
            String prId,
            String remoteHead,
            Path worktree,
            String branchName,
            String baseRevisionId,
            String baseSha,
            String changeSetRevisionId,
            String headSha,
            String headTreeDigest,
            String diffDigest) {}

    private record InitialTaskAdmissionSnapshot(
            String taskId,
            long taskEpoch,
            String operationId,
            String operationSubjectDigest,
            String pendingId,
            PendingKind pendingKind,
            String pendingExternalKey,
            String reviewerResultId,
            Path worktree,
            String branchName,
            String baseRevisionId,
            String baseSha,
            String changeSetRevisionId,
            String headSha,
            String headTreeDigest,
            String diffDigest,
            boolean differsFromBase,
            String prId,
            String reviewerRequestId) {}

    private record ReviewRequestSnapshot(
            String taskId,
            long taskEpoch,
            String operationId,
            String operationSubjectDigest,
            String runId,
            String pendingId,
            String pendingExternalKey,
            Path worktree,
            String branchName,
            String baseHeadSha,
            String reviewedHeadSha,
            String remoteHeadSha,
            String originCiFixPendingId,
            String originCiFixSourceKind,
            String originCiFixSourceId,
            String changeSetRevisionId,
            String headTreeDigest,
            String diffDigest,
            GateIntent intendedGateKind) {}


    private record TaskResultConsumptionSnapshot(
            String taskId,
            long taskEpoch,
            String operationId,
            String operationSubjectDigest,
            String runId,
            String pendingId,
            ReviewerRequest request,
            String reviewerResultId,
            Path worktree,
            String branchName,
            TaskBaseRevision base,
            ChangeSetRevision currentChangeSet) {}

    private record BlockedReconciliation(
            String operationId,
            OperationState state,
            String deliveryState) {}

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
