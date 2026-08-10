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
package com.bytequay.app.flow.gate;

import com.bytequay.app.flow.ci.CiAutofix;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiUpdateGateEvidence;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateAction;
import com.bytequay.app.flow.gate.UserGateRecords.CodePublicationReviewBinding;
import com.bytequay.app.flow.gate.UserGateRecords.GateKind;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGateRecords.GateSubject;
import com.bytequay.app.flow.gate.UserGateRecords.GateTransition;
import com.bytequay.app.flow.gate.UserGateRecords.LocalCheckBinding;
import com.bytequay.app.flow.gate.UserGateRecords.ReadyForReviewAcceptance;
import com.bytequay.app.flow.gate.UserGateRecords.UserGate;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixSourceKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckEvidence;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReadyForReviewRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskBaseRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WakeKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.Inspection;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.LocalChecks;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Concrete owner for the implemented local CI_UPDATE review gate. */
public final class UserGates
{
    private static final String READY_ACTOR = "READY_FOR_REVIEW";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;
    private final LocalChecks localChecks;
    private final CiAutofix autofix;
    private final Clock clock;
    private final FlowWorktreeInspector worktreeInspector =
            new FlowWorktreeInspector();

    public UserGates(
            DataSource dataSource,
            FlowRuntime runtime,
            LocalChecks localChecks,
            CiAutofix autofix,
            Clock clock)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.localChecks = requireNonNull(localChecks, "localChecks is null");
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public static final class ReadyRejectedException
            extends IllegalStateException
    {
        private final List<String> blockerCodes;

        private ReadyRejectedException(List<String> blockerCodes)
        {
            super(String.join(",", blockerCodes));
            this.blockerCodes = List.copyOf(blockerCodes);
        }

        public List<String> blockerCodes()
        {
            return blockerCodes;
        }
    }

    public static ReadyRejectedException missingExactReview()
    {
        return rejected("EXACT_HEAD_REVIEW_MISSING");
    }

    public static final class PreparedReadyRequest
    {
        private final Claim claim;
        private final WriterFence fence;
        private final GateSubject subject;
        private final CiUpdateAction action;
        private final Inspection inspection;

        private PreparedReadyRequest(
                Claim claim,
                WriterFence fence,
                GateSubject subject,
                CiUpdateAction action,
                Inspection inspection)
        {
            this.claim = claim;
            this.fence = fence;
            this.subject = subject;
            this.action = action;
            this.inspection = inspection;
        }
    }

    public static final class PreparedReadyFinalization
    {
        private final ReadyForReviewRequest request;
        private final Claim claim;
        private final WriterFence fence;
        private final GateSubject subject;
        private final CiUpdateAction action;
        private final Inspection inspection;
        private final String blockerCode;

        private PreparedReadyFinalization(
                ReadyForReviewRequest request,
                Claim claim,
                WriterFence fence,
                GateSubject subject,
                CiUpdateAction action,
                Inspection inspection,
                String blockerCode)
        {
            this.request = request;
            this.claim = claim;
            this.fence = fence;
            this.subject = subject;
            this.action = action;
            this.inspection = inspection;
            this.blockerCode = blockerCode;
        }
    }

    public PreparedReadyRequest prepareReadyRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            Path repositoryRoot,
            ReviewerRequest reviewerRequest,
            AgentResult reviewerResult,
            CiFixReviewOrigin origin)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireNonNull(reviewerRequest, "reviewerRequest is null");
        requireNonNull(reviewerResult, "reviewerResult is null");
        requireNonNull(origin, "origin is null");
        Instant createdAt = clock.instant();
        GateBundle bundle = deriveCurrent(
                runId,
                claim,
                fence,
                reviewerRequest,
                reviewerResult,
                origin,
                createdAt);
        Inspection inspection = inspect(repositoryRoot, bundle.subject());
        return new PreparedReadyRequest(
                claim, fence, bundle.subject(), bundle.action(), inspection);
    }

    public ReadyForReviewAcceptance reserveReadyRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            String processAttemptId,
            String capabilityId,
            PreparedReadyRequest prepared)
    {
        requireText(runId, "runId");
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            if (!sameClaim(prepared.claim, claim)
                    || !sameFence(prepared.fence, fence)) {
                throw new FlowRuntime.StaleCapabilityException(
                        "ready authority changed after inspection");
            }
            GateBundle current = deriveCurrent(
                    runId,
                    claim,
                    fence,
                    runtime.reviewerRequest(
                            prepared.subject.reviewerRequestId())
                            .orElseThrow(),
                    runtime.resultForRun(prepared.subject.reviewerRunId())
                            .orElseThrow(),
                    new CiFixReviewOrigin(
                            prepared.subject.originCiFixPendingId(),
                            CiFixSourceKind.valueOf(prepared.subject
                                            .originCiFixSourceKind()),
                            prepared.subject.originCiFixSourceId()),
                    prepared.subject.createdAt());
            if (!current.subject().equals(prepared.subject)
                    || !current.action().equals(prepared.action)
                    || !inspectionMatches(
                            prepared.inspection, prepared.subject)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "ready subject changed after inspection");
            }
            insertSubject(prepared.subject);
            insertAction(prepared.action);
            String requestId = stableId(
                    "ready-request", runId, prepared.subject.subjectId());
            runtime.reserveReadyForReviewRequest(
                    runId,
                    claim,
                    fence,
                    processAttemptId,
                    capabilityId,
                    requestId,
                    prepared.subject.prId(),
                    prepared.subject.subjectId(),
                    prepared.subject.subjectDigest(),
                    prepared.action.actionRef(),
                    prepared.action.actionDigest(),
                    prepared.subject.createdAt());
            return new ReadyForReviewAcceptance("ACCEPTED_SEALED");
        });
    }

    public ReadyForReviewAcceptance replayReadyRequest(String runId)
    {
        ReadyForReviewRequest request = runtime.readyForReviewRequestForRun(
                runId).orElseThrow(() -> new IllegalStateException(
                        "ready request is not durable"));
        requireSubject(request.subjectRef());
        requireAction(request.actionRef());
        return new ReadyForReviewAcceptance("ACCEPTED_SEALED");
    }

    public PreparedReadyFinalization prepareFinalization(
            String runId,
            Claim claim,
            WriterFence fence)
    {
        ReadyForReviewRequest request = runtime.readyForReviewRequestForRun(
                runId).orElseThrow(() -> new IllegalArgumentException(
                        "unknown ready request"));
        GateSubject subject = requireSubject(request.subjectRef());
        CiUpdateAction action = requireAction(request.actionRef());
        Optional<AgentResult> existing = runtime.resultForRun(runId);
        if (existing.isPresent()) {
            Optional<GateRevision> revision = revisionForRun(runId);
            String durableFailure = runtime.readyAttentionReasonForRun(runId)
                    .orElse(null);
            if (revision.isPresent() == (durableFailure != null)) {
                throw new IllegalStateException(
                        "ready replay has no single durable disposition");
            }
            return new PreparedReadyFinalization(
                    request,
                    claim,
                    fence,
                    subject,
                    action,
                    null,
                    durableFailure);
        }
        try {
            runtime.assertWriterRunStopped(runId, claim);
            assertSubjectCurrent(subject, action, claim, fence);
            ReviewerRequest reviewer = runtime.reviewerRequest(
                    subject.reviewerRequestId()).orElseThrow();
            Inspection inspection = inspect(
                    Path.of(reviewer.repositoryRoot()),
                    subject);
            return new PreparedReadyFinalization(
                    request,
                    claim,
                    fence,
                    subject,
                    action,
                    inspection,
                    null);
        }
        catch (InspectionFailure failure) {
            if (transientFailure(failure.code())) {
                throw failure;
            }
            return new PreparedReadyFinalization(
                    request,
                    claim,
                    fence,
                    subject,
                    action,
                    null,
                    "REVIEW_READINESS_" + failure.code().name());
        }
        catch (ReadyRejectedException | FlowRuntime.StaleOwnerRevisionException
                failure) {
            return new PreparedReadyFinalization(
                    request,
                    claim,
                    fence,
                    subject,
                    action,
                    null,
                    "REVIEW_READINESS_STALE");
        }
    }

    public AgentResult finalizeReady(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            PreparedReadyFinalization prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            ReadyForReviewRequest currentRequest =
                    runtime.readyForReviewRequestForRun(runId).orElseThrow();
            if (!currentRequest.equals(prepared.request)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "ready finalization request changed");
            }
            if (!sameClaim(prepared.claim, claim)
                    || !sameFence(prepared.fence, fence)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "ready finalization authority changed");
            }
            Optional<GateRevision> replay = revisionForRun(runId);
            if (replay.isPresent()) {
                GateRevision revision = replay.orElseThrow();
                return runtime.finishTaskAgentReadyTurn(
                        runId,
                        claim,
                        fence,
                        terminalOutcome,
                        finalContent,
                        errorRef,
                        gateRevisionRef(revision),
                        null);
            }
            if (prepared.blockerCode != null) {
                return runtime.finishTaskAgentReadyTurn(
                        runId,
                        claim,
                        fence,
                        terminalOutcome,
                        finalContent,
                        errorRef,
                        null,
                        prepared.blockerCode);
            }
            assertPreparedFinalization(prepared, claim, fence);
            GateRevision revision = openOrRevise(
                    prepared.request, prepared.subject, prepared.action);
            return runtime.finishTaskAgentReadyTurn(
                    runId,
                    claim,
                    fence,
                    terminalOutcome,
                    finalContent,
                    errorRef,
                    gateRevisionRef(revision),
                    null);
        });
    }

    public Optional<UserGate> gate(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query(
                "SELECT * FROM flow_user_gate WHERE pr_id = ? AND kind = 'CI_UPDATE'",
                (result, row) -> readGate(result),
                prId).stream().findFirst();
    }

    public Optional<GateRevision> revisionForRun(String runId)
    {
        requireText(runId, "runId");
        return jdbc.query(
                "SELECT * FROM flow_user_gate_revision WHERE created_by_run_id = ?",
                (result, row) -> readRevision(result),
                runId).stream().findFirst();
    }

    public Optional<GateSubject> subject(String subjectId)
    {
        requireText(subjectId, "subjectId");
        return jdbc.query(
                "SELECT * FROM flow_user_gate_subject WHERE subject_id = ?",
                (result, row) -> readSubject(result),
                subjectId).stream().findFirst();
    }

    public List<GateTransition> transitions(String gateId)
    {
        requireText(gateId, "gateId");
        return jdbc.query(
                """
                SELECT * FROM flow_user_gate_transition
                WHERE gate_id = ? ORDER BY sequence
                """,
                (result, row) -> readTransition(result),
                gateId);
    }

    private GateBundle deriveCurrent(
            String runId,
            Claim claim,
            WriterFence fence,
            ReviewerRequest reviewerRequest,
            AgentResult reviewerResult,
            CiFixReviewOrigin origin,
            Instant createdAt)
    {
        runtime.assertWriterFence(claim, fence);
        AgentRun run = runtime.run(runId).orElseThrow();
        Task task = runtime.task(fence.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(task.prId()).orElseThrow();
        runtime.assertAndLockCiUpdatePr(pr);
        ChangeSetRevision changeSet = runtime.currentChangeSet(task.taskId())
                .orElseThrow(() -> rejected("CHANGE_SET_MISSING"));
        TaskBaseRevision base = runtime.currentBaseRevision(task.taskId())
                .orElseThrow(() -> rejected("BASE_REVISION_MISSING"));
        PendingWork input = runtime.pendingWork(task.taskId()).stream()
                .filter(item -> Objects.equals(
                        item.selectedByOperationId(), claim.operationId()))
                .findFirst()
                .orElseThrow(() -> rejected("REVIEW_RESULT_INPUT_MISSING"));
        AgentRun reviewerRun = runtime.run(reviewerResult.runId())
                .orElseThrow(() -> rejected("REVIEWER_RUN_MISSING"));
        List<String> blockers = new ArrayList<>();
        if (task.status() != TaskStatus.ACTIVE
                || run.state() != RunState.RUNNING
                || run.wakeKind() != WakeKind.AGENT_RESULT_READY
                || input.kind() != PendingKind.AGENT_RESULT_READY
                || !input.externalKey().equals(reviewerRun.runId())
                || !Objects.equals(
                        input.agentResultId(), reviewerResult.resultId())
                || !input.payloadRef().equals(reviewerResultPayload(
                        reviewerRequest.requestId(),
                        reviewerResult.resultId()))
                || input.intendedGateKind()
                        != reviewerRequest.intendedGateKind()
                || !reviewerRequest.taskId().equals(task.taskId())
                || !reviewerRun.operationId().equals(
                        reviewerRequest.reviewerOperationId())
                || !runtime.reviewerRequestForReviewerRun(
                        reviewerRun.runId()).orElseThrow()
                        .equals(reviewerRequest)
                || !reviewerRequest.reviewedHeadSha().equals(
                        changeSet.headSha())
                || !reviewerRequest.changeSetRevisionId().equals(
                        changeSet.changeSetRevisionId())
                || !reviewerRequest.baseHeadSha().equals(base.baseSha())
                || !reviewerRequest.headTreeDigest().equals(
                        changeSet.headTreeDigest())
                || !reviewerRequest.diffDigest().equals(
                        changeSet.diffDigest())
                || !base.baseRevisionId().equals(
                        changeSet.baseRevisionId())
                || !base.baseSha().equals(changeSet.baseSha())
                || !reviewerRequest.originCiFixPendingId().equals(
                        origin.pendingId())
                || !reviewerRequest.originCiFixSourceKind().equals(
                        origin.sourceKind().name())
                || !reviewerRequest.originCiFixSourceId().equals(
                        origin.sourceId())
                || reviewerRun.state() != RunState.COMPLETED
                || reviewerResult.terminalOutcome()
                        != TerminalOutcome.COMPLETED) {
            blockers.add("EXACT_HEAD_REVIEW_MISSING");
        }
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || !pr.repositoryId().equals(task.repositoryId())
                || pr.currentRemoteHead() == null
                || !pr.currentRemoteHead().equals(
                        reviewerRequest.remoteHeadSha())
                || !task.branchName().equals(pr.branchName())) {
            blockers.add("REMOTE_SUBJECT_STALE");
        }
        if (changeSet.headSha().equals(pr.currentRemoteHead())) {
            blockers.add("CI_UPDATE_HAS_NO_REMOTE_CHANGE");
        }
        LocalCheckEvidence checks;
        try {
            LocalChecks.ReviewerEvidence lockedChecks =
                    localChecks.reviewerEvidence(
                            task.taskId(),
                            changeSet.changeSetRevisionId(),
                            run.intendedGateKind());
            lockedChecks.assertCurrentForReservation();
            checks = lockedChecks.evidence();
        }
        catch (IllegalStateException staleChecks) {
            throw rejected("LOCAL_CHECK_EVIDENCE_STALE");
        }
        if (!Objects.equals(
                    checks.policyRevisionId(),
                    reviewerRequest.localCheckPolicyRevisionId())
                || !checks.checkRunRefs().equals(
                    reviewerRequest.checkRunRefs())) {
            blockers.add("REVIEW_CHECK_EVIDENCE_CHANGED");
        }
        List<String> warnings = new ArrayList<>();
        for (LocalCheckRun check : checks.runs()) {
            if (check.conclusion() == LocalCheckConclusion.FAILED) {
                blockers.add("LOCAL_CHECK_FAILED:" + check.profileId());
            }
            else if (check.conclusion() == LocalCheckConclusion.UNAVAILABLE) {
                warnings.add("LOCAL_CHECK_UNAVAILABLE:" + check.profileId());
            }
        }
        CiUpdateGateEvidence ci;
        try {
            ci = autofix.ciUpdateGateEvidence(
                    origin.sourceKind().name(), origin.sourceId());
        }
        catch (IllegalStateException staleCiEvidence) {
            throw rejected("CI_FIX_SOURCE_STALE");
        }
        if (!ci.taskId().equals(task.taskId())
                || !ci.prId().equals(pr.prId())
                || !ci.remoteHead().equals(pr.currentRemoteHead())
                || !origin.pendingId().equals(
                        reviewerRequest.originCiFixPendingId())) {
            blockers.add("CI_FIX_SOURCE_STALE");
        }
        if (!blockers.isEmpty()) {
            throw new ReadyRejectedException(
                    blockers.stream().distinct().toList());
        }
        CodePublicationReviewBinding localReview =
                new CodePublicationReviewBinding(
                        changeSet.changeSetRevisionId(),
                        false,
                        List.of(),
                        List.of(),
                        stableId(
                                "local-review-none",
                                pr.prId(),
                                changeSet.changeSetRevisionId()));
        String branchRef = "refs/heads/" + task.branchName();
        List<LocalCheckBinding> checkBindings = checks.runs().stream()
                .map(check -> new LocalCheckBinding(
                        check.checkRunId(),
                        check.profileId(),
                        check.conclusion()))
                .toList();
        String subjectDigest = subjectDigest(
                task,
                pr,
                changeSet,
                base,
                checks,
                reviewerRequest,
                reviewerRun,
                reviewerResult,
                origin,
                ci,
                localReview,
                warnings);
        String subjectId = stableId("gate-subject", runId, subjectDigest);
        GateSubject subject = new GateSubject(
                subjectId,
                task.taskId(),
                pr.prId(),
                task.repositoryId(),
                branchRef,
                pr.currentRemoteHead(),
                changeSet.changeSetRevisionId(),
                base.baseRevisionId(),
                base.baseSha(),
                changeSet.headSha(),
                changeSet.headTreeDigest(),
                changeSet.diffDigest(),
                checks.policyRevisionId(),
                checkBindings,
                reviewerRequest.requestId(),
                reviewerRun.runId(),
                reviewerResult.resultId(),
                origin.pendingId(),
                origin.sourceKind().name(),
                origin.sourceId(),
                ci.roundId(),
                ci.requiredCiPolicyRevisionId(),
                ci.evidenceRevision(),
                ci.checkObservationIds(),
                ci.failedLogRefs(),
                ci.repairAttemptId(),
                ci.repairResultId(),
                ci.cleanupId(),
                ci.cleanupResultId(),
                List.of(),
                localReview,
                !warnings.isEmpty(),
                warnings,
                subjectDigest,
                runId,
                createdAt);
        String actionDigest = stableId(
                "ci-update-action",
                branchRef,
                pr.currentRemoteHead(),
                changeSet.headSha(),
                "force:false");
        return new GateBundle(
                subject,
                new CiUpdateAction(
                        stableId("gate-action", runId, actionDigest),
                        branchRef,
                        pr.currentRemoteHead(),
                        changeSet.headSha(),
                        false,
                        actionDigest,
                        createdAt));
    }

    private void assertSubjectCurrent(
            GateSubject subject,
            CiUpdateAction action,
            Claim claim,
            WriterFence fence)
    {
        AgentRun run = runtime.run(subject.createdByRunId()).orElseThrow();
        PendingWork input = runtime.pendingWork(subject.taskId()).stream()
                .filter(item -> Objects.equals(
                        item.selectedByOperationId(), run.operationId()))
                .findFirst()
                .orElseThrow(() -> rejected("REVIEW_RESULT_INPUT_MISSING"));
        ReviewerRequest request = runtime.reviewerRequest(
                subject.reviewerRequestId()).orElseThrow();
        AgentResult result = runtime.resultForRun(input.externalKey())
                .orElseThrow();
        GateBundle current = deriveCurrent(
                run.runId(),
                claim,
                fence,
                request,
                result,
                new CiFixReviewOrigin(
                        subject.originCiFixPendingId(),
                        CiFixSourceKind.valueOf(
                                        subject.originCiFixSourceKind()),
                        subject.originCiFixSourceId()),
                subject.createdAt());
        if (!current.subject().equals(subject)
                || !current.action().equals(action)) {
            throw new FlowRuntime.StaleOwnerRevisionException(
                    "ready subject is no longer current");
        }
    }

    private void assertPreparedFinalization(
            PreparedReadyFinalization prepared,
            Claim claim,
            WriterFence fence)
    {
        assertSubjectCurrent(prepared.subject, prepared.action, claim, fence);
        if (prepared.inspection == null
                || !inspectionMatches(
                        prepared.inspection, prepared.subject)) {
            throw new FlowRuntime.StaleOwnerRevisionException(
                    "ready final inspection changed");
        }
    }

    private Inspection inspect(Path repositoryRoot, GateSubject subject)
    {
        Task task = runtime.task(subject.taskId()).orElseThrow();
        return worktreeInspector.inspect(
                repositoryRoot,
                Path.of(task.worktreePath()),
                task.branchName(),
                subject.baseSha(),
                subject.proposedHead());
    }

    private static boolean inspectionMatches(
            Inspection inspection, GateSubject subject)
    {
        return inspection.headSha().equals(subject.proposedHead())
                && inspection.headTreeDigest().equals(
                        subject.headTreeDigest())
                && inspection.baseToHeadDiffDigest().equals(
                        subject.diffDigest());
    }

    private GateRevision openOrRevise(
            ReadyForReviewRequest request,
            GateSubject subject,
            CiUpdateAction action)
    {
        Optional<GateRevision> replay = revisionForRun(
                subject.createdByRunId());
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Optional<UserGate> existing = gate(subject.prId());
        String gateId = existing.map(UserGate::gateId).orElseGet(() ->
                stableId("user-gate", subject.prId(), GateKind.CI_UPDATE.name()));
        long revision = existing.map(UserGate::currentRevision).orElse(0L) + 1;
        Instant now = clock.instant();
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO flow_user_gate (
                        gate_id, task_id, pr_id, kind,
                        current_revision, created_at
                    ) VALUES (?, ?, ?, 'CI_UPDATE', 1, ?)
                    """,
                    gateId,
                    subject.taskId(),
                    subject.prId(),
                    now.toEpochMilli());
        }
        else {
            UserGate gate = existing.orElseThrow();
            GateState currentState = currentState(
                    gate.gateId(), gate.currentRevision());
            if (currentState != GateState.OPEN) {
                throw new IllegalStateException(
                        "only the current OPEN CI_UPDATE gate may be revised");
            }
            appendTransition(
                    gateId,
                    gate.currentRevision(),
                    nextTransitionSequence(gateId),
                    GateState.OPEN,
                    GateState.STALE,
                    "SUPERSEDED_BY_READY",
                    "ready-subject:" + subject.subjectId(),
                    now);
            int advanced = jdbc.update(
                    """
                    UPDATE flow_user_gate SET current_revision = ?
                    WHERE gate_id = ? AND current_revision = ?
                    """,
                    revision,
                    gateId,
                    gate.currentRevision());
            if (advanced != 1) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "CI_UPDATE gate changed during revision");
            }
        }
        jdbc.update(
                """
                INSERT INTO flow_user_gate_revision (
                    gate_id, revision, subject_manifest_ref, subject_digest,
                    action_manifest_ref, action_digest,
                    readiness_evidence_ref, created_by_run_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                gateId,
                revision,
                subject.subjectId(),
                subject.subjectDigest(),
                action.actionRef(),
                action.actionDigest(),
                request.requestId(),
                subject.createdByRunId(),
                now.toEpochMilli());
        appendTransition(
                gateId,
                revision,
                nextTransitionSequence(gateId),
                null,
                GateState.OPEN,
                "READY",
                null,
                now);
        return revisionForRun(subject.createdByRunId()).orElseThrow();
    }

    private void insertSubject(GateSubject subject)
    {
        if (this.subject(subject.subjectId()).isPresent()) {
            if (!this.subject(subject.subjectId()).orElseThrow()
                    .equals(subject)) {
                throw new IllegalStateException(
                        "ready subject replay changed identity");
            }
            return;
        }
        jdbc.update(
                """
                INSERT INTO flow_user_gate_subject (
                    subject_id, subject_digest, task_id, pr_id,
                    repository_id, branch_ref,
                    expected_remote_head, change_set_revision_id,
                    base_revision_id, base_sha, proposed_head,
                    head_tree_digest, diff_digest,
                    local_check_policy_revision_id, reviewer_request_id,
                    reviewer_run_id, reviewer_result_id,
                    origin_ci_fix_pending_id, origin_ci_fix_source_kind,
                    origin_ci_fix_source_id, ci_round_id,
                    required_ci_policy_revision_id, ci_evidence_revision,
                    repair_attempt_id, repair_result_id, cleanup_id,
                    cleanup_result_id, local_review_owner_present,
                    local_review_batch_refs_json,
                    local_review_revision_refs_json, local_review_digest,
                    ci_memory_refs_json, manual_only, created_by_run_id,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, '[]', '[]', ?,
                          '[]', ?, ?, ?)
                """,
                subject.subjectId(),
                subject.subjectDigest(),
                subject.taskId(),
                subject.prId(),
                subject.repositoryId(),
                subject.branchRef(),
                subject.expectedRemoteHead(),
                subject.changeSetRevisionId(),
                subject.baseRevisionId(),
                subject.baseSha(),
                subject.proposedHead(),
                subject.headTreeDigest(),
                subject.diffDigest(),
                subject.localCheckPolicyRevisionId(),
                subject.reviewerRequestId(),
                subject.reviewerRunId(),
                subject.reviewerResultId(),
                subject.originCiFixPendingId(),
                subject.originCiFixSourceKind(),
                subject.originCiFixSourceId(),
                subject.ciRoundId(),
                subject.requiredCiPolicyRevisionId(),
                subject.ciEvidenceRevision(),
                subject.repairAttemptId(),
                subject.repairResultId(),
                subject.cleanupId(),
                subject.cleanupResultId(),
                subject.localReview().digest(),
                subject.manualOnly() ? 1 : 0,
                subject.createdByRunId(),
                subject.createdAt().toEpochMilli());
        for (int index = 0; index < subject.localChecks().size(); index++) {
            LocalCheckBinding check = subject.localChecks().get(index);
            jdbc.update(
                        """
                        INSERT INTO flow_user_gate_subject_local_check (
                            subject_id, ordinal, check_run_id,
                            profile_id, conclusion
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                        subject.subjectId(),
                        index,
                        check.checkRunId(),
                        check.profileId(),
                        check.conclusion().name());
        }
        insertStrings(
                "flow_user_gate_subject_ci_observation",
                "observation_id",
                subject.subjectId(),
                subject.ciObservationIds());
        insertStrings(
                "flow_user_gate_subject_failed_log",
                "log_ref",
                subject.subjectId(),
                subject.failedLogRefs());
        insertStrings(
                "flow_user_gate_subject_warning",
                "warning_code",
                subject.subjectId(),
                subject.warningCodes());
    }

    private void insertAction(CiUpdateAction action)
    {
        Optional<CiUpdateAction> existing = action(action.actionRef());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(action)) {
                throw new IllegalStateException(
                        "ready action replay changed identity");
            }
            return;
        }
        jdbc.update(
                """
                INSERT INTO flow_user_gate_ci_update_action (
                    action_ref, branch_ref, expected_remote_head,
                    proposed_head, force_push, action_digest, created_at
                ) VALUES (?, ?, ?, ?, 0, ?, ?)
                """,
                action.actionRef(),
                action.branchRef(),
                action.expectedRemoteHead(),
                action.proposedHead(),
                action.actionDigest(),
                action.createdAt().toEpochMilli());
    }

    private Optional<CiUpdateAction> action(String actionRef)
    {
        return jdbc.query(
                "SELECT * FROM flow_user_gate_ci_update_action WHERE action_ref = ?",
                (result, row) -> new CiUpdateAction(
                        result.getString("action_ref"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("proposed_head"),
                        result.getInt("force_push") != 0,
                        result.getString("action_digest"),
                        instant(result, "created_at")),
                actionRef).stream().findFirst();
    }

    private GateSubject requireSubject(String subjectId)
    {
        return subject(subjectId).orElseThrow(() ->
                new IllegalStateException("unknown gate subject"));
    }

    private CiUpdateAction requireAction(String actionRef)
    {
        return action(actionRef).orElseThrow(() ->
                new IllegalStateException("unknown gate action"));
    }

    private GateSubject readSubject(ResultSet result) throws SQLException
    {
        String subjectId = result.getString("subject_id");
        List<LocalCheckBinding> checks = jdbc.query(
                """
                SELECT * FROM flow_user_gate_subject_local_check
                WHERE subject_id = ? ORDER BY ordinal
                """,
                (row, number) -> new LocalCheckBinding(
                        row.getString("check_run_id"),
                        row.getString("profile_id"),
                        LocalCheckConclusion.valueOf(
                                row.getString("conclusion"))),
                subjectId);
        return new GateSubject(
                subjectId,
                result.getString("task_id"),
                result.getString("pr_id"),
                result.getString("repository_id"),
                result.getString("branch_ref"),
                result.getString("expected_remote_head"),
                result.getString("change_set_revision_id"),
                result.getString("base_revision_id"),
                result.getString("base_sha"),
                result.getString("proposed_head"),
                result.getString("head_tree_digest"),
                result.getString("diff_digest"),
                result.getString("local_check_policy_revision_id"),
                checks,
                result.getString("reviewer_request_id"),
                result.getString("reviewer_run_id"),
                result.getString("reviewer_result_id"),
                result.getString("origin_ci_fix_pending_id"),
                result.getString("origin_ci_fix_source_kind"),
                result.getString("origin_ci_fix_source_id"),
                result.getString("ci_round_id"),
                result.getString("required_ci_policy_revision_id"),
                result.getLong("ci_evidence_revision"),
                readStrings(
                        "flow_user_gate_subject_ci_observation",
                        "observation_id",
                        subjectId),
                readStrings(
                        "flow_user_gate_subject_failed_log",
                        "log_ref",
                        subjectId),
                result.getString("repair_attempt_id"),
                result.getString("repair_result_id"),
                result.getString("cleanup_id"),
                result.getString("cleanup_result_id"),
                List.of(),
                new CodePublicationReviewBinding(
                        result.getString("change_set_revision_id"),
                        false,
                        List.of(),
                        List.of(),
                        result.getString("local_review_digest")),
                result.getInt("manual_only") != 0,
                readStrings(
                        "flow_user_gate_subject_warning",
                        "warning_code",
                        subjectId),
                result.getString("subject_digest"),
                result.getString("created_by_run_id"),
                instant(result, "created_at"));
    }

    private UserGate readGate(ResultSet result) throws SQLException
    {
        return new UserGate(
                result.getString("gate_id"),
                result.getString("task_id"),
                result.getString("pr_id"),
                GateKind.valueOf(result.getString("kind")),
                result.getLong("current_revision"),
                instant(result, "created_at"));
    }

    private GateRevision readRevision(ResultSet result) throws SQLException
    {
        return new GateRevision(
                result.getString("gate_id"),
                result.getLong("revision"),
                result.getString("subject_manifest_ref"),
                result.getString("subject_digest"),
                result.getString("action_manifest_ref"),
                result.getString("action_digest"),
                result.getString("readiness_evidence_ref"),
                result.getString("created_by_run_id"),
                instant(result, "created_at"));
    }

    private GateTransition readTransition(ResultSet result)
            throws SQLException
    {
        String from = result.getString("from_state");
        return new GateTransition(
                result.getString("gate_id"),
                result.getLong("gate_revision"),
                result.getLong("sequence"),
                from == null ? null : GateState.valueOf(from),
                GateState.valueOf(result.getString("to_state")),
                result.getString("reason_code"),
                result.getString("detail_ref"),
                instant(result, "recorded_at"));
    }

    private GateState currentState(String gateId, long revision)
    {
        return GateState.valueOf(jdbc.queryForObject(
                """
                SELECT to_state FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                ORDER BY sequence DESC LIMIT 1
                """,
                String.class,
                gateId,
                revision));
    }

    private long nextTransitionSequence(String gateId)
    {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(sequence), 0) + 1
                FROM flow_user_gate_transition WHERE gate_id = ?
                """,
                Long.class,
                gateId);
    }

    private void appendTransition(
            String gateId,
            long revision,
            long sequence,
            GateState from,
            GateState to,
            String reason,
            String detail,
            Instant now)
    {
        jdbc.update(
                """
                INSERT INTO flow_user_gate_transition (
                    gate_id, gate_revision, sequence, from_state, to_state,
                    actor_type, actor_id, reason_code, detail_ref, recorded_at
                ) VALUES (?, ?, ?, ?, ?, 'PROGRAM', ?, ?, ?, ?)
                """,
                gateId,
                revision,
                sequence,
                from == null ? null : from.name(),
                to.name(),
                READY_ACTOR,
                reason,
                detail,
                now.toEpochMilli());
    }

    private void insertStrings(
            String table,
            String column,
            String subjectId,
            List<String> values)
    {
        for (int index = 0; index < values.size(); index++) {
            jdbc.update(
                    "INSERT INTO " + table
                            + " (subject_id, ordinal, " + column
                            + ") VALUES (?, ?, ?)",
                    subjectId,
                    index,
                    values.get(index));
        }
    }

    private List<String> readStrings(
            String table, String column, String subjectId)
    {
        return jdbc.queryForList(
                "SELECT " + column + " FROM " + table
                        + " WHERE subject_id = ? ORDER BY ordinal",
                String.class,
                subjectId);
    }

    private static String subjectDigest(
            Task task,
            PullRequestSubject pr,
            ChangeSetRevision changeSet,
            TaskBaseRevision base,
            LocalCheckEvidence checks,
            ReviewerRequest reviewer,
            AgentRun reviewerRun,
            AgentResult reviewerResult,
            CiFixReviewOrigin origin,
            CiUpdateGateEvidence ci,
            CodePublicationReviewBinding localReview,
            List<String> warnings)
    {
        List<String> fields = new ArrayList<>(List.of(
                "ci-update-subject:v1",
                task.taskId(),
                pr.prId(),
                task.repositoryId(),
                task.branchName(),
                pr.currentRemoteHead(),
                changeSet.changeSetRevisionId(),
                base.baseRevisionId(),
                base.baseSha(),
                changeSet.headSha(),
                changeSet.headTreeDigest(),
                changeSet.diffDigest(),
                checks.policyRevisionId(),
                reviewer.requestId(),
                reviewerRun.runId(),
                reviewerResult.resultId(),
                origin.pendingId(),
                origin.sourceKind().name(),
                origin.sourceId(),
                ci.roundId(),
                ci.requiredCiPolicyRevisionId(),
                Long.toString(ci.evidenceRevision()),
                ci.repairAttemptId(),
                ci.repairResultId(),
                nullable(ci.cleanupId()),
                nullable(ci.cleanupResultId()),
                localReview.digest(),
                warnings.isEmpty() ? "manual:false" : "manual:true"));
        checks.runs().forEach(check -> fields.add(
                "check:" + check.checkRunId() + ":"
                        + check.profileId() + ":" + check.conclusion()));
        ci.checkObservationIds().forEach(value ->
                fields.add("ci-observation:" + value));
        ci.failedLogRefs().forEach(value ->
                fields.add("failed-log:" + value));
        warnings.forEach(value -> fields.add("warning:" + value));
        fields.add("local-review-batches:0");
        fields.add("local-review-revisions:0");
        fields.add("ci-memory:0");
        return stableId(fields.toArray(String[]::new));
    }

    private static String gateRevisionRef(GateRevision revision)
    {
        return revision.gateId() + ":" + revision.revision();
    }

    private static String reviewerResultPayload(
            String requestId, String resultId)
    {
        return "reviewer-request:" + requestId + ":result:" + resultId;
    }

    private static ReadyRejectedException rejected(String code)
    {
        return new ReadyRejectedException(List.of(code));
    }

    private static boolean transientFailure(FailureCode code)
    {
        return code == FailureCode.TIMEOUT
                || code == FailureCode.INTERRUPTED;
    }

    private static boolean sameClaim(Claim left, Claim right)
    {
        return left.operationId().equals(right.operationId())
                && left.generation() == right.generation()
                && left.claimToken().equals(right.claimToken())
                && left.workerId().equals(right.workerId());
    }

    private static boolean sameFence(WriterFence left, WriterFence right)
    {
        return left.taskId().equals(right.taskId())
                && left.operationId().equals(right.operationId())
                && left.taskEpoch() == right.taskEpoch()
                && left.fencingToken() == right.fencingToken()
                && left.claimGeneration() == right.claimGeneration()
                && left.claimTokenDigest().equals(right.claimTokenDigest())
                && left.headSha().equals(right.headSha())
                && left.treeDigest().equals(right.treeDigest())
                && left.snapshotEvidenceRef().equals(
                        right.snapshotEvidenceRef());
    }

    private static String nullable(String value)
    {
        return value == null ? "<none>" : value;
    }

    private static String stableId(String... fields)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = requireNonNull(field, "digest field is null")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length)
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Instant instant(ResultSet result, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(result.getLong(column));
    }

    private <T> T inTransaction(Supplier<T> work)
    {
        return requireNonNull(
                transactions.execute(ignored -> work.get()),
                "User Gates transaction returned null");
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private record GateBundle(GateSubject subject, CiUpdateAction action) {}

}
