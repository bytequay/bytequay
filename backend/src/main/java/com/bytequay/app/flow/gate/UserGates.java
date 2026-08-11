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
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedCiUpdate;
import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedInitialPublish;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateAction;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateConsentRevision;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGateRecords.CodePublicationReviewBinding;
import com.bytequay.app.flow.gate.UserGateRecords.GateAuthorization;
import com.bytequay.app.flow.gate.UserGateRecords.GateKind;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGateRecords.GateSubject;
import com.bytequay.app.flow.gate.UserGateRecords.GateTransition;
import com.bytequay.app.flow.gate.UserGateRecords.InitialPublishAction;
import com.bytequay.app.flow.gate.UserGateRecords.InitialPublishSubject;
import com.bytequay.app.flow.gate.UserGateRecords.LocalCheckBinding;
import com.bytequay.app.flow.gate.UserGateRecords.LocalReviewBinding;
import com.bytequay.app.flow.gate.UserGateRecords.ReadyForReviewAcceptance;
import com.bytequay.app.flow.gate.UserGateRecords.UserGate;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectProbe;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderFailure;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderFailureKind;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderObservation;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.github.GitHubEffects.ActivatedInitialAttempt;
import com.bytequay.app.flow.github.GitHubEffects.InitialRecoveryKind;
import com.bytequay.app.flow.github.GitHubEffects.PreparedCiUpdatePlan;
import com.bytequay.app.flow.github.GitHubEffects.PreparedInitialPublishPlan;
import com.bytequay.app.flow.github.GitHubInitialPublishExecutor.SettlementRequired;
import com.bytequay.app.flow.github.InitialPublishRecords;
import com.bytequay.app.flow.github.InitialPublishRecords.Plan;
import com.bytequay.app.flow.github.InitialPublishRecords.Settlement;
import com.bytequay.app.flow.github.InitialPublishRecords.TargetSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionHandle;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixSourceKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckEvidence;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PrDraftRevision;
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
import java.time.Duration;
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
    private static final String AUTHORIZATION_ACTOR =
            "USER_GATES_AUTHORIZATION";
    private static final String MANUAL_ACTOR = "LOCAL_DESKTOP_USER";
    private static final String CONSENT_ACTOR = "USER_GATES_CI_CONSENT";
    private static final Duration MAX_CONSENT_DURATION = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;
    private final LocalChecks localChecks;
    private final CiAutofix autofix;
    private final GitHubEffects githubEffects;
    private final Clock clock;
    private final FlowWorktreeInspector worktreeInspector =
            new FlowWorktreeInspector();

    /** Exact consumed publication graph eligible for optional learning. */
    public record CiUpdateLearningPublication(
            ExternalEffectReceipt receipt,
            ExternalEffectPlan plan,
            GateAuthorization authorization,
            GateRevision revision,
            GateSubject subject,
            CiUpdateAction action)
    {
        public CiUpdateLearningPublication
        {
            requireNonNull(receipt, "receipt is null");
            requireNonNull(plan, "plan is null");
            requireNonNull(authorization, "authorization is null");
            requireNonNull(revision, "revision is null");
            requireNonNull(subject, "subject is null");
            requireNonNull(action, "action is null");
        }
    }

    public UserGates(
            DataSource dataSource,
            FlowRuntime runtime,
            LocalChecks localChecks,
            CiAutofix autofix,
            GitHubEffects githubEffects,
            Clock clock)
    {
        requireNonNull(dataSource, "dataSource is null");
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.localChecks = requireNonNull(localChecks, "localChecks is null");
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.githubEffects = requireNonNull(
                githubEffects, "githubEffects is null");
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

    public static final class AuthorizationRejectedException
            extends IllegalStateException
    {
        private final String reasonCode;

        private AuthorizationRejectedException(String reasonCode)
        {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }

        public String reasonCode()
        {
            return reasonCode;
        }
    }

    public static final class ConsentRejectedException
            extends IllegalStateException
    {
        private ConsentRejectedException(String reasonCode)
        {
            super(reasonCode);
        }
    }

    public static final class DurableStaleEffectException
            extends IllegalStateException
    {
        private DurableStaleEffectException(String reasonCode)
        {
            super(reasonCode);
        }
    }

    public static final class DurableProbeRequiredException
            extends IllegalStateException
    {
        private DurableProbeRequiredException(String reasonCode)
        {
            super(reasonCode);
        }
    }

    public enum PublishDispositionKind
    {
        SUCCEEDED,
        CANCELED,
        RETRY
    }

    public enum InitialPublishDispositionKind
    {
        DIVERGED,
        UNKNOWN,
        ATTEMPT_LIMIT,
        ABSENT_RETRY,
        DIVERGENCE_LOCKED
    }

    public record InitialPublishEffectActivation(
            String planId, boolean mutationAllowed)
    {
        public InitialPublishEffectActivation
        {
            requireNonNull(planId, "planId is null");
        }
    }

    /** Nonconstructible proof that User Gates locked a post-branch stop. */
    public static final class InitialBranchStaleDisposition
    {
        private final Claim claim;
        private final String planId;
        private final String branchReceiptId;
        private final String reasonCode;
        private final String detail;

        private InitialBranchStaleDisposition(
                Claim claim, String planId, String branchReceiptId,
                String reasonCode, String detail)
        {
            this.claim = requireNonNull(claim, "claim is null");
            requireText(planId, "planId");
            requireText(branchReceiptId, "branchReceiptId");
            requireText(reasonCode, "reasonCode");
            requireText(detail, "detail");
            this.planId = planId;
            this.branchReceiptId = branchReceiptId;
            this.reasonCode = reasonCode;
            this.detail = detail;
        }

        public Claim claim() { return claim; }
        public String planId() { return planId; }
        public String branchReceiptId() { return branchReceiptId; }
        public String reasonCode() { return reasonCode; }
        public String detail() { return detail; }
    }

    /** Owner-minted runtime settlement after its durable graph mutation. */
    public static final class PublishDisposition
    {
        private final String operationId;
        private final PublishDispositionKind kind;
        private final String resultRef;
        private final Instant retryAt;

        private PublishDisposition(
                String operationId,
                PublishDispositionKind kind,
                String resultRef,
                Instant retryAt)
        {
            this.operationId = requireNonNull(
                    operationId, "operationId is null");
            this.kind = requireNonNull(kind, "kind is null");
            this.resultRef = requireNonNull(
                    resultRef, "resultRef is null");
            this.retryAt = retryAt;
            if (kind == PublishDispositionKind.RETRY != (retryAt != null)) {
                throw new IllegalArgumentException(
                        "only retry disposition has a retry time");
            }
        }

        public String operationId() { return operationId; }
        public PublishDispositionKind kind() { return kind; }
        public String resultRef() { return resultRef; }
        public Instant retryAt() { return retryAt; }
    }

    public static final class PreparedReadyRequest
    {
        private final Claim claim;
        private final WriterFence fence;
        private final LocalReviewBinding localReviewBinding;
        private final GateSubject subject;
        private final CiUpdateAction action;
        private final Inspection inspection;

        private PreparedReadyRequest(
                Claim claim,
                WriterFence fence,
                LocalReviewBinding localReviewBinding,
                GateSubject subject,
                CiUpdateAction action,
                Inspection inspection)
        {
            this.claim = claim;
            this.fence = fence;
            this.localReviewBinding = localReviewBinding;
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

    public static final class PreparedInitialReadyRequest
    {
        private final Claim claim;
        private final WriterFence fence;
        private final InitialGateBundle bundle;
        private final Inspection inspection;

        private PreparedInitialReadyRequest(
                Claim claim,
                WriterFence fence,
                InitialGateBundle bundle,
                Inspection inspection)
        {
            this.claim = claim;
            this.fence = fence;
            this.bundle = bundle;
            this.inspection = inspection;
        }
    }

    public static final class PreparedInitialFinalization
    {
        private final ReadyForReviewRequest request;
        private final Claim claim;
        private final WriterFence fence;
        private final InitialPublishSubject subject;
        private final InitialPublishAction action;
        private final Inspection inspection;
        private final String blockerCode;

        private PreparedInitialFinalization(
                ReadyForReviewRequest request,
                Claim claim,
                WriterFence fence,
                InitialPublishSubject subject,
                InitialPublishAction action,
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

    private record AuthorityInspection(
            Inspection inspection, String blockerCode) {}

    private record InitialAuthorityInspection(
            Inspection inspection, String blockerCode, boolean required) {}

    private record AuthorizationOutcome(
            AuthorizedCiUpdate authorized, String blockerCode) {}

    private record AuthorizationOutcomeInitial(
            AuthorizedInitialPublish authorized, String blockerCode) {}

    private record InitialGateBundle(
            LocalReviewBinding localReviewBinding,
            InitialPublishSubject subject,
            InitialPublishAction action,
            TargetSnapshot target) {}

    private record BeginOutcome(
            CiUpdateEffectActivation activation,
            String blockerCode,
            boolean probeRequired) {}

    private record InitialBeginOutcome(
            InitialPublishEffectActivation activation,
            String blockerCode,
            boolean probeRequired) {}

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
                claim,
                fence,
                bundle.localReviewBinding(),
                bundle.subject(),
                bundle.action(),
                inspection);
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
                    || !sameLocalReviewBinding(
                            current.localReviewBinding,
                            prepared.localReviewBinding)
                    || !inspectionMatches(
                            prepared.inspection, prepared.subject)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "ready subject changed after inspection");
            }
            insertLocalReviewBinding(prepared.localReviewBinding);
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

    /**
     * Consumes the fresh repository observation while the terminal Task tool
     * is live and derives the complete immutable INITIAL publication bundle.
     * A durable replay never calls the provider again.
     */
    public PreparedInitialReadyRequest prepareInitialReadyRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            Path repositoryRoot,
            Supplier<InitialPublishRecords.RepositoryObservation> observation)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(repositoryRoot, "repositoryRoot is null");
        requireNonNull(observation, "observation is null");
        Optional<ReadyForReviewRequest> replay =
                runtime.readyForReviewRequestForRun(runId);
        if (replay.isPresent()) {
            return new PreparedInitialReadyRequest(
                    claim,
                    fence,
                    requireInitialReadyBundle(replay.orElseThrow()),
                    null);
        }
        runtime.assertWriterFence(claim, fence);
        InitialGateBundle bundle = deriveInitialPublish(
                runId,
                requireNonNull(observation.get(),
                        "repository observation is null"));
        Inspection inspection = inspect(repositoryRoot, bundle.subject());
        return new PreparedInitialReadyRequest(
                claim, fence, bundle, inspection);
    }

    /** Stores the complete INITIAL bundle and terminal request in one tx. */
    public ReadyForReviewAcceptance reserveInitialReadyRequest(
            String runId,
            Claim claim,
            WriterFence fence,
            String processAttemptId,
            String capabilityId,
            PreparedInitialReadyRequest prepared)
    {
        requireText(runId, "runId");
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            Optional<ReadyForReviewRequest> replay =
                    runtime.readyForReviewRequestForRun(runId);
            if (replay.isPresent()) {
                InitialGateBundle stored = requireInitialReadyBundle(
                        replay.orElseThrow());
                if (!stored.equals(prepared.bundle)) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "INITIAL ready replay changed its sealed bundle");
                }
                return new ReadyForReviewAcceptance("ACCEPTED_SEALED");
            }
            if (!sameClaim(prepared.claim, claim)
                    || !sameFence(prepared.fence, fence)) {
                throw new FlowRuntime.StaleCapabilityException(
                        "INITIAL ready authority changed after observation");
            }
            runtime.assertWriterFence(claim, fence);
            InitialPublishSubject subject = prepared.bundle.subject();
            InitialGateBundle current = deriveInitialPublish(
                    runId,
                    subject.baseRepositoryExternalId(),
                    subject.baseRepositoryOwner(),
                    subject.baseRepositoryName(),
                    subject.createdAt());
            if (!current.equals(prepared.bundle)
                    || prepared.inspection == null
                    || !inspectionMatches(
                            prepared.inspection, subject)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "INITIAL ready subject changed after observation");
            }
            githubEffects.storeInitialTargetSnapshot(
                    prepared.bundle.target());
            insertLocalReviewBinding(prepared.bundle.localReviewBinding());
            insertInitialSubject(subject);
            insertInitialAction(prepared.bundle.action());
            String requestId = stableId(
                    "ready-request", runId, subject.subjectId());
            runtime.reserveReadyForReviewRequest(
                    runId,
                    claim,
                    fence,
                    processAttemptId,
                    capabilityId,
                    requestId,
                    subject.prId(),
                    subject.subjectId(),
                    subject.subjectDigest(),
                    prepared.bundle.action().actionRef(),
                    prepared.bundle.action().actionDigest(),
                    subject.createdAt());
            return new ReadyForReviewAcceptance("ACCEPTED_SEALED");
        });
    }

    public ReadyForReviewAcceptance replayInitialReadyRequest(String runId)
    {
        ReadyForReviewRequest request = runtime.readyForReviewRequestForRun(
                runId).orElseThrow(() -> new IllegalStateException(
                        "INITIAL ready request is not durable"));
        requireInitialReadyBundle(request);
        return new ReadyForReviewAcceptance("ACCEPTED_SEALED");
    }

    /** Provider-free preparation for the durable STOPPED finalizer. */
    public PreparedInitialFinalization prepareInitialFinalization(
            String runId,
            Claim claim,
            WriterFence fence)
    {
        ReadyForReviewRequest request = runtime.readyForReviewRequestForRun(
                runId).orElseThrow(() -> new IllegalArgumentException(
                        "unknown INITIAL ready request"));
        InitialGateBundle bundle = requireInitialReadyBundle(request);
        Optional<AgentResult> existing = runtime.resultForRun(runId);
        if (existing.isPresent()) {
            Optional<GateRevision> revision = revisionForRun(runId);
            String durableFailure = runtime.readyAttentionReasonForRun(runId)
                    .orElse(null);
            if (revision.isPresent() == (durableFailure != null)) {
                throw new IllegalStateException(
                        "INITIAL ready replay has no single disposition");
            }
            return new PreparedInitialFinalization(
                    request, claim, fence, bundle.subject(), bundle.action(),
                    null, durableFailure);
        }
        try {
            runtime.assertWriterRunStopped(runId, claim);
            assertInitialSubjectCurrent(
                    bundle.subject(), bundle.action(), claim, fence);
            ReviewerRequest reviewer = runtime.reviewerRequest(
                    bundle.subject().reviewerRequestId()).orElseThrow();
            Inspection inspection = inspect(
                    Path.of(reviewer.repositoryRoot()), bundle.subject());
            return new PreparedInitialFinalization(
                    request, claim, fence, bundle.subject(), bundle.action(),
                    inspection, null);
        }
        catch (InspectionFailure failure) {
            if (transientFailure(failure.code())) {
                throw failure;
            }
            return new PreparedInitialFinalization(
                    request, claim, fence, bundle.subject(), bundle.action(),
                    null, "INITIAL_READINESS_" + failure.code().name());
        }
        catch (ReadyRejectedException | FlowRuntime.StaleOwnerRevisionException
                failure) {
            return new PreparedInitialFinalization(
                    request, claim, fence, bundle.subject(), bundle.action(),
                    null, "INITIAL_READINESS_STALE");
        }
    }

    /** Opens the INITIAL gate and settles the Task owner in one outer tx. */
    public AgentResult finalizeInitialReady(
            String runId,
            Claim claim,
            WriterFence fence,
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef,
            PreparedInitialFinalization prepared)
    {
        requireNonNull(prepared, "prepared is null");
        return inTransaction(() -> {
            ReadyForReviewRequest current =
                    runtime.readyForReviewRequestForRun(runId).orElseThrow();
            if (!current.equals(prepared.request)
                    || !sameClaim(prepared.claim, claim)
                    || !sameFence(prepared.fence, fence)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "INITIAL ready finalization authority changed");
            }
            InitialGateBundle stored = requireInitialReadyBundle(current);
            if (!stored.subject().equals(prepared.subject)
                    || !stored.action().equals(prepared.action)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "INITIAL ready finalization bundle changed");
            }
            Optional<GateRevision> replay = revisionForRun(runId);
            if (replay.isPresent()) {
                return runtime.finishTaskAgentReadyTurn(
                        runId, claim, fence, terminalOutcome, finalContent,
                        errorRef, gateRevisionRef(replay.orElseThrow()), null);
            }
            if (prepared.blockerCode != null) {
                return runtime.finishTaskAgentReadyTurn(
                        runId, claim, fence, terminalOutcome, finalContent,
                        errorRef, null, prepared.blockerCode);
            }
            assertInitialSubjectCurrent(
                    prepared.subject, prepared.action, claim, fence);
            if (prepared.inspection == null
                    || !inspectionMatches(
                            prepared.inspection, prepared.subject)) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "INITIAL ready final inspection changed");
            }
            GateRevision revision = openInitialGate(
                    runId, stored.subject(), stored.action());
            return runtime.finishTaskAgentReadyTurn(
                    runId, claim, fence, terminalOutcome, finalContent,
                    errorRef, gateRevisionRef(revision), null);
        });
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
            AgentResult result = runtime.finishTaskAgentReadyTurn(
                    runId,
                    claim,
                    fence,
                    terminalOutcome,
                    finalContent,
                    errorRef,
                    gateRevisionRef(revision),
                    null);
            maybeAuthorizeNewCiUpdate(
                    revision,
                    prepared.subject,
                    prepared.action,
                    prepared.inspection);
            return result;
        });
    }

    /** Grants one future automatic CI_UPDATE use for at most 24 hours. */
    public synchronized CiUpdateConsentRevision grantCiUpdateConsent(
            String taskId,
            Instant expiresAt,
            String idempotencyKey)
    {
        requireText(taskId, "taskId");
        requireText(idempotencyKey, "idempotencyKey");
        requireNonNull(expiresAt, "expiresAt is null");
        Instant now = clock.instant();
        if (!expiresAt.equals(Instant.ofEpochMilli(expiresAt.toEpochMilli()))
                || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plus(MAX_CONSENT_DURATION))
                || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException(
                    "consent expiry/idempotency key is invalid");
        }
        CiUpdateConsentRevision replay = grantReplay(
                consentByKey(idempotencyKey), taskId, expiresAt);
        if (replay != null) {
            return replay;
        }
        return inTransaction(() -> {
            if (!expiresAt.isAfter(clock.instant())) {
                throw new ConsentRejectedException("CONSENT_EXPIRED");
            }
            Task task = runtime.task(taskId).orElseThrow(() ->
                    new ConsentRejectedException("CONSENT_TASK_MISSING"));
            if (task.status() != TaskStatus.ACTIVE || task.prId() == null) {
                throw new ConsentRejectedException(
                        "CONSENT_TASK_INELIGIBLE");
            }
            String consentId = stableId("ci-update-consent:v1", taskId);
            Optional<CiUpdateConsentRevision> current =
                    currentConsentForTask(taskId);
            if (current.isPresent()) {
                lockCurrentConsent(
                        consentId, current.orElseThrow().revision());
            }
            PullRequestSubject pr = runtime.pullRequest(task.prId())
                    .orElseThrow(() -> new ConsentRejectedException(
                            "CONSENT_PR_MISSING"));
            runtime.assertAndLockCiUpdatePr(pr);
            CiUpdateConsentRevision lockedReplay = grantReplay(
                    consentByKey(idempotencyKey), taskId, expiresAt);
            if (lockedReplay != null) {
                return lockedReplay;
            }
            if (!pr.published()
                    || !pr.taskId().equals(task.taskId())
                    || !pr.repositoryId().equals(task.repositoryId())
                    || !pr.branchName().equals(task.branchName())) {
                throw new ConsentRejectedException(
                        "CONSENT_SCOPE_STALE");
            }
            current = currentConsentForTask(taskId);
            if (hasNonterminalConsentEffect(taskId)) {
                throw new ConsentRejectedException(
                        "CONSENT_EFFECT_ACTIVE");
            }
            if (current.isPresent()) {
                CiUpdateConsentRevision existing = current.orElseThrow();
                if (existing.enabled()
                        && existing.expiresAt().isAfter(now)
                        && !consentUsed(existing)) {
                    throw new ConsentRejectedException(
                            "CONSENT_ALREADY_ACTIVE");
                }
            }
            long revision = current.map(
                    CiUpdateConsentRevision::revision).orElse(0L) + 1;
            CiUpdateConsentRevision consent = consentRevision(
                    consentId, revision, task, pr, true, expiresAt,
                    idempotencyKey, now);
            insertConsentRevision(consent);
            if (current.isEmpty()) {
                jdbc.update(
                        "INSERT INTO flow_user_gate_ci_consent_current "
                                + "(consent_id, task_id, current_revision) "
                                + "VALUES (?, ?, ?)",
                        consentId, taskId, revision);
            }
            else {
                int advanced = jdbc.update(
                        "UPDATE flow_user_gate_ci_consent_current "
                                + "SET current_revision = ? "
                                + "WHERE consent_id = ? "
                                + "AND current_revision = ?",
                        revision,
                        consentId,
                        current.orElseThrow().revision());
                if (advanced != 1) {
                    throw new ConsentRejectedException(
                            "CONSENT_REVISION_CHANGED");
                }
            }
            return assertConsentRevision(consent);
        });
    }

    /** Revokes future use; an already executing exact effect remains frozen. */
    public synchronized CiUpdateConsentRevision revokeCiUpdateConsent(
            String consentId,
            long expectedRevision,
            String idempotencyKey)
    {
        requireText(consentId, "consentId");
        requireText(idempotencyKey, "idempotencyKey");
        if (expectedRevision < 1 || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException(
                    "consent revision/idempotency key is invalid");
        }
        CiUpdateConsentRevision replay = revokeReplay(
                consentByKey(idempotencyKey), consentId, expectedRevision);
        if (replay != null) {
            return replay;
        }
        return inTransaction(() -> {
            lockCurrentConsent(consentId, expectedRevision);
            CiUpdateConsentRevision lockedReplay = revokeReplay(
                    consentByKey(idempotencyKey), consentId, expectedRevision);
            if (lockedReplay != null) {
                return lockedReplay;
            }
            CiUpdateConsentRevision current = currentConsent(consentId)
                    .orElseThrow();
            if (current.revision() != expectedRevision
                    || !current.enabled()) {
                throw new ConsentRejectedException(
                        "CONSENT_REVISION_CHANGED");
            }
            Instant now = clock.instant();
            CiUpdateConsentRevision revoked = consentRevision(
                    consentId,
                    expectedRevision + 1,
                    current.taskId(),
                    current.prId(),
                    current.repositoryId(),
                    current.remoteIdentityId(),
                    current.headRepositoryExternalId(),
                    current.headRepositoryOwner(),
                    current.headRepositoryName(),
                    current.branchName(),
                    current.branchRef(),
                    false,
                    current.expiresAt(),
                    idempotencyKey,
                    now);
            insertConsentRevision(revoked);
            int advanced = jdbc.update(
                    "UPDATE flow_user_gate_ci_consent_current "
                            + "SET current_revision = ? "
                            + "WHERE consent_id = ? AND current_revision = ?",
                    revoked.revision(), consentId, expectedRevision);
            if (advanced != 1) {
                throw new ConsentRejectedException(
                        "CONSENT_REVISION_CHANGED");
            }
            return assertConsentRevision(revoked);
        });
    }

    public Optional<CiUpdateConsentRevision> currentCiUpdateConsent(
            String taskId)
    {
        requireText(taskId, "taskId");
        return currentConsentForTask(taskId).map(this::assertConsentRevision);
    }

    /** Authorizes exactly one displayed local CI_UPDATE gate revision. */
    public AuthorizedCiUpdate authorizeCiUpdate(
            String gateId,
            long gateRevision,
            String expectedSubjectDigest,
            String expectedActionDigest,
            String idempotencyKey)
    {
        requireText(gateId, "gateId");
        requireText(expectedSubjectDigest, "expectedSubjectDigest");
        requireText(expectedActionDigest, "expectedActionDigest");
        requireText(idempotencyKey, "idempotencyKey");
        if (gateRevision < 1 || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException(
                    "gate revision/idempotency key is invalid");
        }
        Optional<GateAuthorization> replay = authorizationByKey(
                MANUAL_ACTOR, idempotencyKey);
        if (replay.isPresent()) {
            requireManualAuthorization(replay.orElseThrow());
            return assertAuthorizationGraph(
                    replay.orElseThrow(), gateId, gateRevision,
                    expectedSubjectDigest, expectedActionDigest,
                    idempotencyKey);
        }
        Optional<GateAuthorization> revisionReplay = authorizationForRevision(
                gateId, gateRevision);
        if (revisionReplay.isPresent()) {
            GateAuthorization existing = revisionReplay.orElseThrow();
            requireManualAuthorization(existing);
            if (!existing.idempotencyKey().equals(idempotencyKey)) {
                throw new AuthorizationRejectedException(
                        "IDEMPOTENCY_KEY_CONFLICT");
            }
            return assertAuthorizationGraph(
                    existing, gateId, gateRevision,
                    expectedSubjectDigest, expectedActionDigest,
                    idempotencyKey);
        }
        GateRevision revision = requireRevision(gateId, gateRevision);
        if (currentState(gateId, gateRevision) != GateState.OPEN) {
            throw new AuthorizationRejectedException(
                    terminalAuthorizationBlocker(
                            gateId, gateRevision));
        }
        if (!revision.subjectDigest().equals(expectedSubjectDigest)
                || !revision.actionDigest().equals(expectedActionDigest)) {
            throw new AuthorizationRejectedException(
                    "DISPLAYED_GATE_DIGEST_CHANGED");
        }
        GateSubject subject = requireSubject(revision.subjectManifestRef());
        CiUpdateAction action = requireAction(revision.actionManifestRef());
        ReviewerRequest reviewerRequest = runtime.reviewerRequest(
                subject.reviewerRequestId()).orElseThrow(() ->
                        new IllegalStateException(
                                "reviewer request is missing"));
        AuthorityInspection inspected = inspectAuthority(
                Path.of(reviewerRequest.repositoryRoot()), subject);
        AuthorizationOutcome outcome = inTransaction(() -> {
            lockCurrentGateRevision(gateId, gateRevision);
            Optional<GateAuthorization> transactionalReplay =
                    authorizationByKey(MANUAL_ACTOR, idempotencyKey);
            if (transactionalReplay.isPresent()) {
                requireManualAuthorization(
                        transactionalReplay.orElseThrow());
                return new AuthorizationOutcome(
                        assertAuthorizationGraph(
                                transactionalReplay.orElseThrow(), gateId,
                                gateRevision, expectedSubjectDigest,
                                expectedActionDigest, idempotencyKey),
                        null);
            }
            Optional<GateAuthorization> transactionalRevisionReplay =
                    authorizationForRevision(gateId, gateRevision);
            if (transactionalRevisionReplay.isPresent()) {
                GateAuthorization existing =
                        transactionalRevisionReplay.orElseThrow();
                requireManualAuthorization(existing);
                if (!existing.idempotencyKey().equals(idempotencyKey)) {
                    throw new AuthorizationRejectedException(
                            "IDEMPOTENCY_KEY_CONFLICT");
                }
                return new AuthorizationOutcome(
                        assertAuthorizationGraph(
                                existing, gateId, gateRevision,
                                expectedSubjectDigest,
                                expectedActionDigest, idempotencyKey),
                        null);
            }
            if (currentState(gateId, gateRevision) != GateState.OPEN) {
                return new AuthorizationOutcome(
                        null,
                        terminalAuthorizationBlocker(
                                gateId, gateRevision));
            }
            String blocker = inspected.blockerCode();
            if (blocker == null) {
                blocker = authorizationBlocker(
                        gateId, gateRevision, revision, subject, action,
                        inspected.inspection());
            }
            if (blocker != null) {
                staleOpenAuthorization(gateId, gateRevision, blocker);
                return new AuthorizationOutcome(null, blocker);
            }
            return new AuthorizationOutcome(
                    createAuthorizationLocked(
                            gateId,
                            gateRevision,
                            subject,
                            action,
                            idempotencyKey,
                            null),
                    null);
        });
        if (outcome.blockerCode() != null) {
            throw new AuthorizationRejectedException(outcome.blockerCode());
        }
        return requireNonNull(outcome.authorized(),
                "authorization result is null");
    }

    private GateRevision openInitialGate(
            String runId,
            InitialPublishSubject subject,
            InitialPublishAction action)
    {
        Optional<GateRevision> replay = initialPublishReplay(runId);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        Optional<UserGate> existing = initialGate(subject.prId());
        String gateId = existing.map(UserGate::gateId).orElseGet(() ->
                stableId("user-gate", subject.prId(),
                        GateKind.INITIAL_PUBLISH.name()));
        long revision = existing.map(UserGate::currentRevision).orElse(0L) + 1;
        Instant now = clock.instant();
        if (existing.isEmpty()) {
            jdbc.update(
                        """
                        INSERT INTO flow_user_gate (
                            gate_id, task_id, pr_id, kind,
                            current_revision, created_at
                        ) VALUES (?, ?, ?, 'INITIAL_PUBLISH', 1, ?)
                        """,
                    gateId, subject.taskId(), subject.prId(),
                    now.toEpochMilli());
        }
        else {
            UserGate gate = existing.orElseThrow();
            GateState state = currentState(gate.gateId(),
                    gate.currentRevision());
            if (state != GateState.OPEN && state != GateState.STALE
                    && state != GateState.CONSUMED) {
                throw new IllegalStateException(
                        "authorized initial gate may not be revised");
            }
            if (state == GateState.OPEN) {
                appendTransition(gateId, gate.currentRevision(),
                        nextTransitionSequence(gateId), GateState.OPEN,
                        GateState.STALE, "SUPERSEDED_BY_READY",
                        "initial-subject:" + subject.subjectId(),
                        now);
            }
            int advanced = jdbc.update(
                    "UPDATE flow_user_gate SET current_revision = ? "
                            + "WHERE gate_id = ? AND current_revision = ?",
                    revision, gateId, gate.currentRevision());
            if (advanced != 1) {
                throw new FlowRuntime.StaleOwnerRevisionException(
                        "initial gate changed during revision");
            }
        }
        jdbc.update(
                    """
                    INSERT INTO flow_user_gate_revision (
                        gate_id, kind, revision, subject_manifest_ref,
                        subject_digest, action_manifest_ref, action_digest,
                        readiness_evidence_ref, created_by_run_id, created_at
                    ) VALUES (?, 'INITIAL_PUBLISH', ?, ?, ?, ?, ?, NULL, ?, ?)
                    """,
                gateId, revision, subject.subjectId(),
                subject.subjectDigest(), action.actionRef(),
                action.actionDigest(), runId, now.toEpochMilli());
        appendTransition(gateId, revision, nextTransitionSequence(gateId),
                null, GateState.OPEN, "READY", null, now);
        GateRevision opened = requireRevision(gateId, revision);
        assertInitialRevisionGraph(opened);
        return opened;
    }

    private Optional<GateRevision> initialPublishReplay(String runId)
    {
        Optional<GateRevision> replay = revisionForRun(runId);
        if (replay.isEmpty()) {
            return replay;
        }
        GateRevision stored = replay.orElseThrow();
        UserGate gate = requireGate(stored.gateId());
        if (gate.kind() != GateKind.INITIAL_PUBLISH
                || stored.readinessEvidenceRef() != null) {
            throw new IllegalStateException(
                    "initial publication replay is corrupt");
        }
        assertInitialRevisionGraph(stored);
        return replay;
    }

    /** Authorizes exactly one displayed INITIAL_PUBLISH revision, manually. */
    public AuthorizedInitialPublish authorizeInitialPublish(
            String gateId,
            long gateRevision,
            String expectedSubjectDigest,
            String expectedActionDigest,
            String idempotencyKey)
    {
        requireText(gateId, "gateId");
        requireText(expectedSubjectDigest, "expectedSubjectDigest");
        requireText(expectedActionDigest, "expectedActionDigest");
        requireText(idempotencyKey, "idempotencyKey");
        if (gateRevision < 1 || idempotencyKey.length() > 256) {
            throw new IllegalArgumentException(
                    "gate revision/idempotency key is invalid");
        }
        Optional<GateAuthorization> replay = authorizationByKey(
                MANUAL_ACTOR, idempotencyKey);
        if (replay.isPresent()) {
            requireManualAuthorization(replay.orElseThrow());
            return assertInitialAuthorizationGraph(replay.orElseThrow(),
                    gateId, gateRevision, expectedSubjectDigest,
                    expectedActionDigest, idempotencyKey);
        }
        Optional<GateAuthorization> revisionReplay = authorizationForRevision(
                gateId, gateRevision);
        if (revisionReplay.isPresent()) {
            GateAuthorization existing = revisionReplay.orElseThrow();
            requireManualAuthorization(existing);
            if (!existing.idempotencyKey().equals(idempotencyKey)) {
                throw new AuthorizationRejectedException(
                        "IDEMPOTENCY_KEY_CONFLICT");
            }
            return assertInitialAuthorizationGraph(existing, gateId,
                    gateRevision, expectedSubjectDigest,
                    expectedActionDigest, idempotencyKey);
        }
        GateRevision revision = requireRevision(gateId, gateRevision);
        if (currentState(gateId, gateRevision) != GateState.OPEN) {
            throw new AuthorizationRejectedException(
                    terminalAuthorizationBlocker(gateId, gateRevision));
        }
        if (!revision.subjectDigest().equals(expectedSubjectDigest)
                || !revision.actionDigest().equals(expectedActionDigest)) {
            throw new AuthorizationRejectedException(
                    "DISPLAYED_GATE_DIGEST_CHANGED");
        }
        InitialPublishSubject inspectedSubject = requireInitialSubject(
                revision.subjectManifestRef());
        InitialAuthorityInspection inspected = inspectInitialAuthority(
                runtime.task(inspectedSubject.taskId()).orElseThrow(),
                inspectedSubject);
        AuthorizationOutcomeInitial outcome = inTransaction(() -> {
            lockCurrentGateRevision(gateId, gateRevision);
            Optional<GateAuthorization> keyed = authorizationByKey(
                    MANUAL_ACTOR, idempotencyKey);
            if (keyed.isPresent()) {
                requireManualAuthorization(keyed.orElseThrow());
                return new AuthorizationOutcomeInitial(
                        assertInitialAuthorizationGraph(keyed.orElseThrow(),
                                gateId, gateRevision, expectedSubjectDigest,
                                expectedActionDigest, idempotencyKey), null);
            }
            Optional<GateAuthorization> revisionMatch = authorizationForRevision(
                    gateId, gateRevision);
            if (revisionMatch.isPresent()) {
                GateAuthorization existing = revisionMatch.orElseThrow();
                requireManualAuthorization(existing);
                if (!existing.idempotencyKey().equals(idempotencyKey)) {
                    throw new AuthorizationRejectedException(
                            "IDEMPOTENCY_KEY_CONFLICT");
                }
                return new AuthorizationOutcomeInitial(
                        assertInitialAuthorizationGraph(existing, gateId,
                                gateRevision, expectedSubjectDigest,
                                expectedActionDigest, idempotencyKey), null);
            }
            if (currentState(gateId, gateRevision) != GateState.OPEN) {
                return new AuthorizationOutcomeInitial(null,
                        terminalAuthorizationBlocker(gateId, gateRevision));
            }
            GateRevision lockedRevision = requireRevision(gateId, gateRevision);
            String blocker = inspected.blockerCode();
            if (blocker == null) {
                blocker = initialAuthorizationBlocker(
                        gateId, gateRevision, lockedRevision,
                        inspected.inspection());
            }
            if (blocker != null) {
                staleOpenAuthorization(gateId, gateRevision, blocker);
                return new AuthorizationOutcomeInitial(null, blocker);
            }
            InitialPublishSubject subject = requireInitialSubject(
                    lockedRevision.subjectManifestRef());
            InitialPublishAction action = requireInitialAction(
                    lockedRevision.actionManifestRef());
            return new AuthorizationOutcomeInitial(
                    createInitialAuthorizationLocked(gateId, gateRevision,
                            subject, action, idempotencyKey), null);
        });
        if (outcome.blockerCode() != null) {
            throw new AuthorizationRejectedException(outcome.blockerCode());
        }
        return requireNonNull(outcome.authorized(),
                "initial authorization result is null");
    }

    /** Replays a committed publication without recreating live attempt authority. */
    public Optional<ExternalEffectReceipt> terminalCiUpdateReceipt(
            Claim claim)
    {
        requireNonNull(claim, "claim is null");
        Optional<GateAuthorization> storedAuthorization =
                authorizationForOperation(claim.operationId());
        if (storedAuthorization.isEmpty()) {
            return Optional.empty();
        }
        GateAuthorization authorization = storedAuthorization.orElseThrow();
        ExternalEffectPlan plan = githubEffects.plan(
                authorization.effectPlanRef()).orElseThrow(() ->
                        new IllegalStateException(
                                "publication plan is missing"));
        Optional<ExternalEffectReceipt> storedReceipt =
                githubEffects.exactReceipt(plan.planId());
        if (storedReceipt.isEmpty()) {
            return Optional.empty();
        }
        ExternalEffectReceipt receipt = storedReceipt.orElseThrow();
        assertStoredAuthorizationGraph(authorization);
        runtime.assertPublishTerminalReplay(claim, receipt.receiptId());
        Integer consumed = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                  AND to_state = 'CONSUMED'
                  AND actor_type = 'PROGRAM' AND actor_id = ?
                  AND reason_code = 'EFFECT_APPLIED' AND detail_ref = ?
                  AND sequence = (
                      SELECT MAX(sequence)
                      FROM flow_user_gate_transition
                      WHERE gate_id = ? AND gate_revision = ?
                  )
                """,
                Integer.class,
                authorization.gateId(),
                authorization.gateRevision(),
                claim.operationId(),
                receipt.receiptId(),
                authorization.gateId(),
                authorization.gateRevision());
        if (requireNonNull(consumed,
                "consumed transition count is null") != 1) {
            throw new IllegalStateException(
                    "publication receipt is not the terminal gate result");
        }
        return Optional.of(receipt);
    }

    /** Returns only an exact historical INITIAL terminal graph. */
    public Optional<Settlement> terminalInitialPublishSettlement(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        Optional<Settlement> stored = githubEffects.initialSettlement(
                claim.operationId());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Settlement settlement = stored.orElseThrow();
        GateAuthorization authorization = authorizationForOperation(
                claim.operationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "initial authorization is missing"));
        assertInitialAuthorizationGraph(
                authorization, authorization.gateId(),
                authorization.gateRevision(), authorization.subjectDigest(),
                authorization.actionDigest(), authorization.idempotencyKey());
        runtime.assertInitialSettlementReplay(claim, settlement);
        GateState expected = settlement.succeeded()
                ? GateState.CONSUMED : GateState.STALE;
        Integer exactTransition = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition t
                WHERE t.gate_id = ? AND t.gate_revision = ?
                  AND t.to_state = ? AND t.actor_type = 'PROGRAM'
                  AND t.actor_id = ? AND t.detail_ref = ?
                  AND t.reason_code = ?
                  AND t.sequence = (
                      SELECT MAX(x.sequence) FROM flow_user_gate_transition x
                      WHERE x.gate_id = t.gate_id
                        AND x.gate_revision = t.gate_revision)
                """,
                Integer.class, authorization.gateId(),
                authorization.gateRevision(), expected.name(),
                claim.operationId(), settlement.resultId(),
                settlement.succeeded()
                        ? "EFFECT_APPLIED" : "EFFECT_STALE");
        if (requireNonNull(exactTransition,
                "initial terminal transition count is null") != 1) {
            throw new IllegalStateException(
                    "initial terminal gate replay is inconsistent");
        }
        return Optional.of(settlement);
    }

    /** One outer transaction settles all INITIAL owners from sealed evidence. */
    public Settlement settleInitialPublish(
            Claim claim, SettlementRequired handoff)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(handoff, "handoff is null");
        return inTransaction(() -> {
            if (githubEffects.initialSettlement(
                    claim.operationId()).isPresent()) {
                Settlement replay = githubEffects.storeInitialSettlement(
                        claim, handoff, clock.instant());
                Settlement terminal = terminalInitialPublishSettlement(claim)
                        .orElseThrow();
                if (replay != terminal
                        && !replay.resultId().equals(terminal.resultId())) {
                    throw new IllegalStateException(
                            "initial settlement replay changed result");
                }
                return terminal;
            }
            GateAuthorization authorization = authorizationForOperation(
                    claim.operationId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "initial authorization is missing"));
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            assertInitialAuthorizationGraph(
                    authorization, authorization.gateId(),
                    authorization.gateRevision(),
                    authorization.subjectDigest(),
                    authorization.actionDigest(),
                    authorization.idempotencyKey());
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state != GateState.EXECUTING
                    && state != GateState.NEEDS_ATTENTION) {
                throw new IllegalStateException(
                        "initial publication is not awaiting settlement");
            }
            Settlement settlement = githubEffects.storeInitialSettlement(
                    claim, handoff, clock.instant());
            GateState next = settlement.succeeded()
                    ? GateState.CONSUMED : GateState.STALE;
            appendTransition(
                    authorization.gateId(), authorization.gateRevision(),
                    nextTransitionSequence(authorization.gateId()),
                    state, next, "PROGRAM", claim.operationId(),
                    settlement.succeeded()
                            ? "EFFECT_APPLIED" : "EFFECT_STALE",
                    settlement.resultId(), clock.instant());
            runtime.applyInitialSettlement(claim, settlement);
            return settlement;
        });
    }

    /** Revalidates and activates INITIAL_PUBLISH before any provider read. */
    public InitialPublishEffectActivation beginInitialPublishEffect(
            Claim claim)
    {
        requireNonNull(claim, "claim is null");
        Optional<String> canceled = runtime.canceledPublishResult(claim);
        if (canceled.isPresent()) {
            throw new DurableStaleEffectException(canceled.orElseThrow());
        }
        InitialAuthorityInspection inspected =
                inspectInitialAuthorityIfRequired(claim);
        InitialBeginOutcome outcome = inTransaction(() -> {
            var operation = runtime.assertPublishClaim(claim);
            Plan plan = githubEffects.initialPublishPlan(operation.inputRef())
                    .orElseThrow(() -> new IllegalStateException(
                            "initial publication plan is missing"));
            GateAuthorization authorization = authorization(
                    plan.authorizationId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "initial authorization is missing"));
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            assertInitialAuthorizationGraph(
                    authorization, authorization.gateId(),
                    authorization.gateRevision(),
                    authorization.subjectDigest(),
                    authorization.actionDigest(),
                    authorization.idempotencyKey());
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state != GateState.AUTHORIZED
                    && state != GateState.EXECUTING
                    && state != GateState.NEEDS_ATTENTION) {
                throw new IllegalStateException(
                        "initial authorization is not claim-activatable");
            }
            List<InitialPublishRecords.Step> steps =
                    githubEffects.initialPublishSteps(plan.planId());
            int currentOrdinal = githubEffects
                    .initialPublishStepReceipts(plan.planId()).size();
            if (currentOrdinal == steps.size()) {
                if (state != GateState.EXECUTING
                        && state != GateState.NEEDS_ATTENTION) {
                    throw new IllegalStateException(
                            "complete initial publication is not awaiting settlement");
                }
                return new InitialBeginOutcome(
                        new InitialPublishEffectActivation(
                                plan.planId(), false), null, false);
            }
            boolean attemptBacked = currentOrdinal < steps.size()
                    && githubEffects.initialPublishAttempts(
                            plan.planId()).stream()
                            .anyMatch(value -> value.stepId().equals(
                                    steps.get(currentOrdinal).stepId()));
            if (attemptBacked) {
                if (state != GateState.AUTHORIZED) {
                    return new InitialBeginOutcome(
                            new InitialPublishEffectActivation(
                                    plan.planId(), false), null, false);
                }
            }
            GateRevision revision = requireRevision(
                    authorization.gateId(), authorization.gateRevision());
            String blocker = inspected.required()
                    ? inspected.blockerCode()
                    : "INITIAL_AUTHORITY_INSPECTION_MISSING";
            if (blocker == null) {
                blocker = initialAuthorizationBlocker(
                        authorization.gateId(),
                        authorization.gateRevision(), revision,
                        inspected.inspection());
            }
            if (blocker != null) {
                if (currentOrdinal == 1 && !attemptBacked) {
                    Settlement settlement = settleInitialBranchOnlyStale(
                            claim, authorization, state, blocker, false);
                    return new InitialBeginOutcome(
                            null, settlement.resultId(), false);
                }
                GateState next = attemptBacked
                        ? GateState.NEEDS_ATTENTION : GateState.STALE;
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state, next,
                        "PROGRAM", claim.operationId(),
                        attemptBacked
                                ? "EFFECT_AUTHORITY_UNPROVEN"
                                : "EFFECT_STALE",
                        blocker, clock.instant());
                runtime.settleClaimedPublish(
                        claim, null, null, null,
                        disposition(
                                claim,
                                attemptBacked
                                        ? PublishDispositionKind.RETRY
                                        : PublishDispositionKind.CANCELED,
                                attemptBacked
                                        ? "INITIAL_PUBLISH_PROBE_REQUIRED:"
                                                + blocker
                                        : "INITIAL_PUBLISH_STALE:" + blocker,
                                attemptBacked
                                        ? clock.instant().plus(
                                                Duration.ofSeconds(5))
                                        : null));
                return new InitialBeginOutcome(
                        null, blocker, attemptBacked);
            }
            if (state == GateState.NEEDS_ATTENTION) {
                appendTransition(
                        authorization.gateId(), authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        GateState.NEEDS_ATTENTION, GateState.AUTHORIZED,
                        "PROGRAM", claim.operationId(), "EFFECT_RETRY",
                        claim.operationId(), clock.instant());
                state = GateState.AUTHORIZED;
            }
            if (state == GateState.AUTHORIZED) {
                appendTransition(
                        authorization.gateId(), authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        GateState.AUTHORIZED, GateState.EXECUTING,
                        "PROGRAM", claim.operationId(), "EFFECT_BEGIN",
                        claim.operationId(), clock.instant());
            }
            return new InitialBeginOutcome(
                    new InitialPublishEffectActivation(
                            plan.planId(), true), null, false);
        });
        if (outcome.blockerCode() != null) {
            if (outcome.probeRequired()) {
                throw new DurableProbeRequiredException(
                        outcome.blockerCode());
            }
            throw new DurableStaleEffectException(outcome.blockerCode());
        }
        return requireNonNull(
                outcome.activation(), "initial activation is null");
    }

    /** Persists a non-success INITIAL disposition without adopting a remote. */
    @SuppressWarnings("StringConcatToTextBlock")
    public void applyInitialPublishDisposition(
            Claim claim,
            ActivatedInitialAttempt activated,
            InitialPublishRecords.Probe suppliedProbe,
            InitialPublishDispositionKind kind)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(kind, "kind is null");
        applyInitialPublishDisposition(
                claim, activated, suppliedProbe, kind, null);
    }

    /** Consumes provider-minted evidence for one exact INITIAL failure. */
    public Optional<Settlement> applyInitialPublishFailure(
            Claim claim,
            ActivatedInitialAttempt activated,
            InitialPublishRecords.ProviderFailure failure)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(failure, "failure is null");
        return applyInitialPublishDisposition(
                claim, activated, null, null, failure);
    }

    @SuppressWarnings("StringConcatToTextBlock")
    private Optional<Settlement> applyInitialPublishDisposition(
            Claim claim,
            ActivatedInitialAttempt activated,
            InitialPublishRecords.Probe suppliedProbe,
            InitialPublishDispositionKind kind,
            InitialPublishRecords.ProviderFailure failure)
    {
        return inTransaction(() -> {
            GateAuthorization authorization = authorizationForOperation(
                    claim.operationId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "initial authorization is missing"));
            Plan plan = githubEffects.initialPublishPlan(
                    authorization.effectPlanRef()).orElseThrow(() ->
                            new IllegalStateException(
                                    "initial publication plan is missing"));
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            assertInitialAuthorizationGraph(
                    authorization, authorization.gateId(),
                    authorization.gateRevision(),
                    authorization.subjectDigest(),
                    authorization.actionDigest(),
                    authorization.idempotencyKey());
            if (activated == null) {
                runtime.assertPublishClaim(claim);
            }
            else {
                runtime.assertPublishAttemptResult(
                        activated.executionHandle(), claim,
                        activated.attempt().attemptId(),
                        activated.attempt().executionTokenDigest());
            }
            List<InitialPublishRecords.Step> steps =
                    githubEffects.initialPublishSteps(plan.planId());
            List<InitialPublishRecords.StepReceipt> receipts =
                    githubEffects.initialPublishStepReceipts(plan.planId());
            if (receipts.size() >= steps.size()) {
                throw new IllegalStateException(
                        "fully receipted initial publication cannot defer");
            }
            InitialPublishRecords.Step step = steps.get(receipts.size());
            List<InitialPublishRecords.Attempt> currentAttempts =
                    githubEffects.initialPublishAttempts(plan.planId()).stream()
                            .filter(value -> value.stepId().equals(step.stepId()))
                            .toList();
            String latestAttemptId = currentAttempts.isEmpty()
                    ? null : currentAttempts.getLast().attemptId();
            if (failure != null && !failure.matches(
                    claim, plan.planId(), step.stepId(), latestAttemptId)) {
                throw new IllegalStateException(
                        "initial provider failure is stale or substituted");
            }
            if (failure != null && failure.baseDrift()) {
                if (!currentAttempts.isEmpty() || receipts.size() != 1) {
                    throw new IllegalStateException(
                            "base drift is not branch-only");
                }
                GateState state = currentState(
                        authorization.gateId(), authorization.gateRevision());
                if (state != GateState.EXECUTING
                        && state != GateState.NEEDS_ATTENTION) {
                    throw new IllegalStateException(
                            "initial publication gate is not executing");
                }
                Settlement settlement =
                        githubEffects.storeInitialBranchOnlyBaseDrift(
                                claim, failure, clock.instant());
                appendTransition(
                        authorization.gateId(), authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state, GateState.STALE, "PROGRAM", claim.operationId(),
                        "EFFECT_STALE", settlement.resultId(),
                        clock.instant());
                runtime.applyInitialSettlement(claim, settlement);
                return Optional.of(settlement);
            }
            String evidenceRef = plan.planId();
            if (suppliedProbe != null) {
                InitialPublishRecords.Probe stored = githubEffects
                        .initialPublishProbes(plan.planId()).stream()
                        .filter(value -> value.probeId().equals(
                                suppliedProbe.probeId()))
                        .findFirst().orElseThrow(() ->
                                new IllegalStateException(
                                        "initial disposition probe is missing"));
                if (!stored.equals(suppliedProbe)
                        || !stored.operationId().equals(claim.operationId())
                        || !stored.stepId().equals(step.stepId())
                        || stored.claimGeneration() != claim.generation()
                        || kind == InitialPublishDispositionKind.DIVERGED
                                && stored.outcome()
                                    != InitialPublishRecords.Outcome.DIVERGED
                        || kind == InitialPublishDispositionKind.UNKNOWN
                                && stored.outcome()
                                    != InitialPublishRecords.Outcome.UNKNOWN
                        || kind == InitialPublishDispositionKind.ATTEMPT_LIMIT
                                && stored.outcome()
                                    != InitialPublishRecords.Outcome.ABSENT
                        || kind == InitialPublishDispositionKind.ABSENT_RETRY
                                && stored.outcome()
                                    != InitialPublishRecords.Outcome.ABSENT
                        || kind
                                == InitialPublishDispositionKind.DIVERGENCE_LOCKED
                                && stored.outcome()
                                    != InitialPublishRecords.Outcome.ABSENT) {
                    throw new IllegalStateException(
                            "initial disposition probe is inconsistent");
                }
                evidenceRef = stored.probeId();
            }
            else if (failure == null) {
                throw new IllegalArgumentException(
                        "initial disposition requires a provider probe");
            }
            if (kind == InitialPublishDispositionKind.DIVERGENCE_LOCKED
                    && githubEffects.initialPublishProbes(plan.planId())
                            .stream().noneMatch(value ->
                                    value.stepId().equals(step.stepId())
                                    && value.outcome()
                                        == InitialPublishRecords.Outcome.DIVERGED)
                    || kind == InitialPublishDispositionKind.ATTEMPT_LIMIT
                            && currentAttempts.size()
                                < GitHubEffects.MAX_MUTATION_ATTEMPTS
                    || kind == InitialPublishDispositionKind.ABSENT_RETRY
                            && currentAttempts.isEmpty()) {
                throw new IllegalStateException(
                        "initial disposition predicate is not proven");
            }
            boolean stable = kind == InitialPublishDispositionKind.DIVERGED
                    || failure != null && failure.invalid()
                    || kind
                        == InitialPublishDispositionKind.DIVERGENCE_LOCKED;
            boolean cancel = stable && currentAttempts.isEmpty();
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state != GateState.EXECUTING
                    && state != GateState.NEEDS_ATTENTION) {
                throw new IllegalStateException(
                        "initial publication gate is not executing");
            }
            if (cancel && receipts.size() == 1) {
                String detail = failure != null
                        ? "PROVIDER_INVALID:" + evidenceRef
                        : kind.name() + ":" + evidenceRef;
                return Optional.of(settleInitialBranchOnlyStale(
                        claim, authorization, state, detail, true));
            }
            GateState next = cancel
                    ? GateState.STALE
                    : kind == InitialPublishDispositionKind.ABSENT_RETRY
                            ? GateState.AUTHORIZED
                            : GateState.NEEDS_ATTENTION;
            if (state != next) {
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state, next, "PROGRAM", claim.operationId(),
                        failure != null
                                ? failure.invalid()
                                        ? "EFFECT_PREPARATION_INVALID"
                                        : currentAttempts.isEmpty()
                                                ? "EFFECT_PREPARATION_UNAVAILABLE"
                                                : "EFFECT_PROBE_UNAVAILABLE"
                                : switch (kind) {
                                    case DIVERGED -> "EFFECT_DIVERGED";
                                    case UNKNOWN -> "EFFECT_UNKNOWN";
                                    case ATTEMPT_LIMIT ->
                                            "EFFECT_ATTEMPT_LIMIT";
                                    case ABSENT_RETRY -> "EFFECT_RETRY";
                                    case DIVERGENCE_LOCKED ->
                                            "EFFECT_DIVERGED";
                                },
                        evidenceRef, clock.instant());
            }
            String resultKind = failure == null
                    ? kind.name()
                    : failure.invalid() ? "INVALID" : "UNAVAILABLE";
            String resultRef = "INITIAL_PUBLISH_" + resultKind
                    + ":" + evidenceRef;
            runtime.settleClaimedPublish(
                    claim,
                    activated == null ? null : activated.executionHandle(),
                    activated == null ? null
                            : activated.attempt().attemptId(),
                    activated == null ? null
                            : activated.attempt().executionTokenDigest(),
                    disposition(
                            claim,
                            cancel ? PublishDispositionKind.CANCELED
                                    : PublishDispositionKind.RETRY,
                            resultRef,
                            cancel ? null : clock.instant().plus(
                                    Duration.ofSeconds(5))));
            return Optional.<Settlement>empty();
        });
    }

    private Settlement settleInitialBranchOnlyStale(
            Claim claim, GateAuthorization authorization, GateState state,
            String detail, boolean providerInvalid)
    {
        List<InitialPublishRecords.StepReceipt> receipts = githubEffects
                .initialPublishStepReceipts(authorization.effectPlanRef());
        if (receipts.size() != 1) {
            throw new IllegalStateException(
                    "branch-only stale disposition is not exact");
        }
        InitialBranchStaleDisposition disposition =
                new InitialBranchStaleDisposition(
                        claim, authorization.effectPlanRef(),
                        receipts.getFirst().receiptId(),
                        providerInvalid ? "REMOTE_STEP_INVALID"
                                : "LOCAL_AUTHORITY_DRIFT",
                        detail);
        Settlement settlement = githubEffects.storeInitialBranchOnlyStale(
                disposition, clock.instant());
        appendTransition(
                authorization.gateId(), authorization.gateRevision(),
                nextTransitionSequence(authorization.gateId()),
                state, GateState.STALE, "PROGRAM", claim.operationId(),
                "EFFECT_STALE", settlement.resultId(), clock.instant());
        runtime.applyInitialSettlement(claim, settlement);
        return settlement;
    }

    /** Revalidates and activates one claimed CI_UPDATE without an external call. */
    public CiUpdateEffectActivation beginCiUpdateEffect(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        Optional<String> canceled = runtime.canceledPublishResult(claim);
        if (canceled.isPresent()) {
            String blocker = durableStaleBlocker(
                    claim, canceled.orElseThrow());
            throw new DurableStaleEffectException(blocker);
        }
        var operation = runtime.assertPublishClaim(claim);
        ExternalEffectPlan plan = githubEffects.plan(operation.inputRef())
                .orElseThrow(() -> new IllegalStateException(
                        "publication plan is missing"));
        GateAuthorization authorization = authorization(
                plan.authorizationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "authorization is missing"));
        assertStoredAuthorizationGraph(authorization);
        GateState state = currentState(
                authorization.gateId(), authorization.gateRevision());
        if (state == GateState.EXECUTING
                || state == GateState.NEEDS_ATTENTION) {
            return activation(
                    authorization,
                    plan,
                    state == GateState.EXECUTING
                            && githubEffects.attempts(plan.planId()).isEmpty());
        }
        if (state != GateState.AUTHORIZED) {
            throw new IllegalStateException(
                    "authorization is not claim-activatable");
        }
        GateRevision revision = requireRevision(
                authorization.gateId(), authorization.gateRevision());
        GateSubject subject = requireSubject(revision.subjectManifestRef());
        CiUpdateAction action = requireAction(revision.actionManifestRef());
        ReviewerRequest reviewerRequest = runtime.reviewerRequest(
                subject.reviewerRequestId()).orElseThrow();
        AuthorityInspection inspected = inspectAuthority(
                Path.of(reviewerRequest.repositoryRoot()), subject);
        BeginOutcome outcome = inTransaction(() -> {
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            runtime.assertPublishClaim(claim);
            GateState current = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (current == GateState.EXECUTING
                    || current == GateState.NEEDS_ATTENTION) {
                return new BeginOutcome(
                        activation(
                                authorization,
                                plan,
                                current == GateState.EXECUTING
                                        && githubEffects.attempts(
                                                plan.planId()).isEmpty()),
                        null,
                        false);
            }
            if (current != GateState.AUTHORIZED) {
                throw new IllegalStateException(
                        "authorization changed before effect begin");
            }
            String blocker = consentBeginBlocker(
                    authorization, clock.instant());
            if (blocker == null) {
                blocker = inspected.blockerCode();
            }
            if (blocker == null) {
                blocker = authorizationBlocker(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        revision,
                        subject,
                        action,
                        inspected.inspection());
            }
            if (blocker != null) {
                boolean probeRequired = !githubEffects.attempts(
                        plan.planId()).isEmpty();
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        GateState.AUTHORIZED,
                        probeRequired
                                ? GateState.NEEDS_ATTENTION : GateState.STALE,
                        "PROGRAM",
                        claim.operationId(),
                        probeRequired
                                ? "EFFECT_AUTHORITY_UNPROVEN"
                                : "EFFECT_STALE",
                        blocker,
                        clock.instant());
                runtime.settleClaimedPublish(
                        claim,
                        null,
                        null,
                        null,
                        disposition(
                                claim,
                                probeRequired
                                        ? PublishDispositionKind.RETRY
                                        : PublishDispositionKind.CANCELED,
                                probeRequired
                                        ? "PUBLISH_PROBE_REQUIRED:" + blocker
                                        : "PUBLISH_STALE:" + blocker,
                                probeRequired
                                        ? clock.instant().plus(
                                                Duration.ofSeconds(5))
                                        : null));
                return new BeginOutcome(null, blocker, probeRequired);
            }
            assertAuthorizationGraph(
                    authorization,
                    authorization.gateId(),
                    authorization.gateRevision(),
                    authorization.subjectDigest(),
                    authorization.actionDigest(),
                    authorization.idempotencyKey());
            appendTransition(
                    authorization.gateId(),
                    authorization.gateRevision(),
                    nextTransitionSequence(authorization.gateId()),
                    GateState.AUTHORIZED,
                    GateState.EXECUTING,
                    "PROGRAM",
                    claim.operationId(),
                    "EFFECT_BEGIN",
                    claim.operationId(),
                    clock.instant());
            return new BeginOutcome(
                    activation(authorization, plan, true), null, false);
        });
        if (outcome.blockerCode() != null) {
            if (outcome.probeRequired()) {
                throw new DurableProbeRequiredException(
                        outcome.blockerCode());
            }
            throw new DurableStaleEffectException(outcome.blockerCode());
        }
        return requireNonNull(outcome.activation(),
                "effect activation is null");
    }

    private String consentBeginBlocker(
            GateAuthorization authorization,
            Instant now)
    {
        if (!authorization.authority().equals("CI_UPDATE_CONSENT")) {
            return null;
        }
        Integer priorBegin = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                  AND from_state = 'AUTHORIZED'
                  AND to_state = 'EXECUTING'
                  AND actor_type = 'PROGRAM' AND actor_id = ?
                  AND reason_code = 'EFFECT_BEGIN'
                  AND detail_ref = ?
                """,
                Integer.class,
                authorization.gateId(),
                authorization.gateRevision(),
                authorization.operationId(),
                authorization.operationId());
        int beginCount = requireNonNull(
                priorBegin, "consent effect-begin count is null");
        if (beginCount > 0) {
            return null;
        }
        CiUpdateConsentRevision authorized = jdbc.query(
                """
                SELECT * FROM flow_user_gate_ci_consent_revision
                WHERE consent_id = ? AND revision = ?
                """,
                (result, row) -> readConsentRevision(result),
                authorization.consentId(),
                authorization.consentRevision()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "authorization consent is missing"));
        assertConsentRevision(authorized);
        CiUpdateConsentRevision current = currentConsent(
                authorized.consentId()).orElseThrow(() ->
                        new IllegalStateException(
                                "current authorization consent is missing"));
        lockCurrentConsent(current.consentId(), current.revision());
        current = currentConsent(current.consentId()).orElseThrow();
        if (current.revision() != authorized.revision()
                || !current.revisionDigest().equals(
                        authorized.revisionDigest())
                || !current.enabled()) {
            return "CI_UPDATE_CONSENT_REVOKED";
        }
        if (!current.expiresAt().isAfter(now)) {
            return "CI_UPDATE_CONSENT_EXPIRED";
        }
        return null;
    }

    /** Applies only a remote probe; provider return values are never proof. */
    @SuppressWarnings("StringConcatToTextBlock")
    public Optional<ExternalEffectReceipt> applyCiUpdateObservation(
            Claim claim,
            PublishExecutionHandle executionHandle,
            ExternalEffectAttempt attempt,
            ProviderObservation observation)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(observation, "observation is null");
        ProbeOutcome outcome = observation.outcome();
        GateAuthorization authorization = authorizationForOperation(
                claim.operationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "publication authorization is missing"));
        ExternalEffectPlan plan = githubEffects.plan(
                authorization.effectPlanRef()).orElseThrow();
        Optional<ExternalEffectReceipt> terminal =
                githubEffects.exactReceipt(plan.planId());
        if (terminal.isPresent()) {
            return validateTerminalProbeReplay(
                    claim, executionHandle, attempt,
                    observation, terminal.orElseThrow());
        }
        return inTransaction(() -> {
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            if (executionHandle == null) {
                if (attempt != null && !githubEffects.attempt(
                        attempt.attemptId()).filter(attempt::equals)
                        .isPresent()) {
                    throw new IllegalStateException(
                            "historical probe attempt differs from evidence");
                }
                runtime.assertPublishClaim(claim);
            }
            else {
                requireNonNull(attempt, "attempt is null");
                runtime.assertPublishAttemptResult(
                        executionHandle,
                        claim,
                        attempt.attemptId(),
                        attempt.executionTokenDigest());
            }
            Optional<ExternalEffectReceipt> replay =
                    githubEffects.exactReceipt(plan.planId());
            if (replay.isPresent()) {
                return validateTerminalProbeReplay(
                        claim, executionHandle, attempt,
                        observation, replay.orElseThrow());
            }
            assertStoredAuthorizationGraph(authorization);
            List<ExternalEffectStep> steps = githubEffects.steps(plan.planId());
            githubEffects.assertExactPlan(plan, steps);
            ExternalEffectStep step = steps.getFirst();
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state != GateState.EXECUTING
                    && state != GateState.NEEDS_ATTENTION) {
                throw new IllegalStateException(
                        "publication probe gate is not active");
            }
            ExternalEffectProbe probe = executionHandle == null
                    ? githubEffects.recordObservation(
                            claim, observation, clock.instant())
                    : githubEffects.recordAttemptObservation(
                            claim, executionHandle, attempt, observation,
                            clock.instant());
            Instant now = clock.instant();
            if (outcome == ProbeOutcome.APPLIED) {
                ExternalEffectReceipt receipt = githubEffects.insertReceipt(
                        plan, step, probe, now);
                runtime.acceptAppliedRemoteHead(
                        plan.prId(),
                        step.expectedRemoteHead(),
                        step.proposedHead());
                runtime.ensureCiObservationWatch(receipt.receiptId());
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state,
                        GateState.CONSUMED,
                        "PROGRAM",
                        claim.operationId(),
                        "EFFECT_APPLIED",
                        receipt.receiptId(),
                        now);
                runtime.settleClaimedPublish(
                        claim,
                        executionHandle,
                        attempt == null ? null : attempt.attemptId(),
                        attempt == null
                                ? null : attempt.executionTokenDigest(),
                        disposition(
                                claim,
                                PublishDispositionKind.SUCCEEDED,
                                receipt.receiptId(),
                                null));
                return Optional.of(receipt);
            }
            if (outcome == ProbeOutcome.DIVERGED) {
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state,
                        GateState.STALE,
                        "PROGRAM",
                        claim.operationId(),
                        "EFFECT_DIVERGED",
                        probe.probeId(),
                        now);
                runtime.settleClaimedPublish(
                        claim,
                        executionHandle,
                        attempt == null ? null : attempt.attemptId(),
                        attempt == null
                                ? null : attempt.executionTokenDigest(),
                        disposition(
                                claim,
                                PublishDispositionKind.CANCELED,
                                "PUBLISH_DIVERGED:" + probe.probeId(),
                                null));
                return Optional.empty();
            }
            boolean limitReached = outcome == ProbeOutcome.ABSENT
                    && githubEffects.attempts(plan.planId()).size()
                            >= GitHubEffects.MAX_MUTATION_ATTEMPTS;
            GateTransition latestTransition = transitions(
                    authorization.gateId()).getLast();
            boolean authorityUnproven = outcome == ProbeOutcome.ABSENT
                    && state == GateState.NEEDS_ATTENTION
                    && latestTransition.gateRevision()
                            == authorization.gateRevision()
                    && latestTransition.reasonCode().equals(
                                    "EFFECT_AUTHORITY_UNPROVEN");
            GateState nextState = outcome == ProbeOutcome.UNKNOWN
                    || limitReached || authorityUnproven
                    ? GateState.NEEDS_ATTENTION
                    : GateState.AUTHORIZED;
            if (state != nextState) {
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state,
                        nextState,
                        "PROGRAM",
                        claim.operationId(),
                        nextState == GateState.AUTHORIZED
                                ? "EFFECT_RETRY" : "EFFECT_UNKNOWN",
                        probe.probeId(),
                        now);
            }
            Instant retryAt = now.plus(Duration.ofSeconds(5));
            String retryRef = nextState == GateState.AUTHORIZED
                    ? "PUBLISH_ABSENT:" + probe.probeId()
                    : "PUBLISH_PROBE_REQUIRED:" + probe.probeId();
            runtime.settleClaimedPublish(
                    claim,
                    executionHandle,
                    attempt == null ? null : attempt.attemptId(),
                    attempt == null
                            ? null : attempt.executionTokenDigest(),
                    disposition(
                            claim,
                            PublishDispositionKind.RETRY,
                            retryRef,
                            retryAt));
            return Optional.empty();
        });
    }

    /** Settles a local provider prerequisite failure without fabricating a probe. */
    public void applyCiUpdateProviderFailure(
            Claim claim,
            PublishExecutionHandle executionHandle,
            ExternalEffectAttempt attempt,
            ProviderFailure failure)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(failure, "failure is null");
        if (!failure.matchesClaim(claim)
                || !failure.operationId().equals(claim.operationId())
                || !Objects.equals(
                        failure.attemptId(),
                        attempt == null ? null : attempt.attemptId())) {
            throw new IllegalStateException(
                    "provider failure does not match its claim");
        }
        inTransaction(() -> {
            GateAuthorization authorization = authorizationForOperation(
                    claim.operationId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "publication authorization is missing"));
            ExternalEffectPlan plan = githubEffects.plan(
                    authorization.effectPlanRef()).orElseThrow();
            List<ExternalEffectStep> steps = githubEffects.steps(plan.planId());
            githubEffects.assertExactPlan(plan, steps);
            ExternalEffectStep step = steps.getFirst();
            if (!failure.planId().equals(plan.planId())
                    || !failure.headRepositoryExternalId().equals(
                            step.headRepositoryExternalId())
                    || !failure.headRepositoryOwner().equals(
                            step.headRepositoryOwner())
                    || !failure.headRepositoryName().equals(
                            step.headRepositoryName())
                    || !failure.branchRef().equals(step.branchRef())
                    || !failure.expectedRemoteHead().equals(
                            step.expectedRemoteHead())
                    || !failure.proposedHead().equals(
                            step.proposedHead())) {
                throw new IllegalStateException(
                        "provider failure does not match its plan");
            }
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            if (executionHandle == null) {
                if (attempt != null && !githubEffects.attempt(
                        attempt.attemptId()).filter(attempt::equals)
                        .isPresent()) {
                    throw new IllegalStateException(
                            "historical failure attempt differs from evidence");
                }
                runtime.assertPublishClaim(claim);
            }
            else {
                requireNonNull(attempt, "attempt is null");
                runtime.assertPublishAttemptResult(
                        executionHandle,
                        claim,
                        attempt.attemptId(),
                        attempt.executionTokenDigest());
            }
            assertStoredAuthorizationGraph(authorization);
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state != GateState.EXECUTING
                    && state != GateState.NEEDS_ATTENTION) {
                throw new IllegalStateException(
                        "provider failure gate is not active");
            }
            Instant now = clock.instant();
            boolean invalid = githubEffects.attempts(plan.planId()).isEmpty()
                    && failure.kind() == ProviderFailureKind.INVALID;
            GateState next = invalid
                    ? GateState.STALE : GateState.NEEDS_ATTENTION;
            if (state != next) {
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state,
                        next,
                        "PROGRAM",
                        claim.operationId(),
                        invalid
                                ? "EFFECT_PREPARATION_INVALID"
                                : attempt == null
                                        ? "EFFECT_PREPARATION_UNAVAILABLE"
                                        : "EFFECT_PROBE_UNAVAILABLE",
                        plan.planId(),
                        now);
            }
            String resultRef = invalid
                    ? "PUBLISH_PREPARATION_INVALID:" + plan.planId()
                    : attempt == null
                            ? "PUBLISH_PREPARATION_REQUIRED:" + plan.planId()
                            : "PUBLISH_PROBE_REQUIRED:" + plan.planId();
            runtime.settleClaimedPublish(
                    claim,
                    executionHandle,
                    attempt == null ? null : attempt.attemptId(),
                    attempt == null
                            ? null : attempt.executionTokenDigest(),
                    disposition(
                            claim,
                            invalid
                                    ? PublishDispositionKind.CANCELED
                                    : PublishDispositionKind.RETRY,
                            resultRef,
                            invalid ? null : now.plus(Duration.ofSeconds(5))));
            return Boolean.TRUE;
        });
    }

    private Optional<ExternalEffectReceipt> validateTerminalProbeReplay(
            Claim claim,
            PublishExecutionHandle executionHandle,
            ExternalEffectAttempt attempt,
            ProviderObservation observation,
            ExternalEffectReceipt receipt)
    {
        ProbeOutcome outcome = observation.outcome();
        String observedHead = observation.observedHead();
        if (!receipt.operationId().equals(claim.operationId())
                || !observation.matchesClaim(claim)
                || !observation.operationId().equals(claim.operationId())
                || !observation.planId().equals(receipt.planId())
                || !Objects.equals(
                        observation.attemptId(), receipt.attemptId())
                || !observation.headRepositoryExternalId().equals(
                        receipt.headRepositoryExternalId())
                || !observation.headRepositoryOwner().equals(
                        receipt.headRepositoryOwner())
                || !observation.headRepositoryName().equals(
                        receipt.headRepositoryName())
                || !observation.branchRef().equals(receipt.branchRef())
                || !observation.expectedRemoteHead().equals(
                        receipt.expectedRemoteHead())
                || !observation.proposedHead().equals(
                        receipt.proposedHead())
                || outcome != ProbeOutcome.APPLIED
                || !receipt.proposedHead().equals(observedHead)) {
            throw new IllegalStateException(
                    "terminal publication replay conflicts with receipt");
        }
        if (receipt.attemptId() == null) {
            if (executionHandle != null || attempt != null) {
                throw new IllegalStateException(
                        "terminal publication replay attempt changed");
            }
            runtime.assertPublishTerminalReplay(
                    claim, receipt.receiptId());
        }
        else {
            requireNonNull(executionHandle,
                    "terminal replay executionHandle is null");
            requireNonNull(attempt,
                    "terminal replay attempt is null");
            ExternalEffectAttempt stored = githubEffects.attempt(
                    receipt.attemptId()).orElseThrow();
            if (!stored.equals(attempt)) {
                throw new IllegalStateException(
                        "terminal publication replay attempt changed");
            }
            runtime.assertPublishAttemptTerminalReplay(
                    executionHandle,
                    claim,
                    attempt.attemptId(),
                    attempt.executionTokenDigest(),
                    receipt.receiptId());
        }
        return Optional.of(receipt);
    }

    private static PublishDisposition disposition(
            Claim claim,
            PublishDispositionKind kind,
            String resultRef,
            Instant retryAt)
    {
        return new PublishDisposition(
                claim.operationId(), kind, resultRef, retryAt);
    }

    /** Recovers only an expired PUBLISH generation that could not call GitHub. */
    public void recoverExpiredCiUpdateEffect(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        inTransaction(() -> {
            ExternalEffectPlan plan = githubEffects.planForAuthorization(
                    authorizationForOperation(operationId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "publication authorization is missing"))
                            .authorizationId()).orElseThrow();
            if (!plan.operationId().equals(operationId)) {
                throw new IllegalStateException(
                        "publication recovery graph is invalid");
            }
            GateAuthorization authorization = authorization(
                    plan.authorizationId()).orElseThrow();
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            assertStoredAuthorizationGraph(authorization);
            if (!githubEffects.attempts(plan.planId()).isEmpty()) {
                throw new IllegalStateException(
                        "activated publication requires probe-only recovery");
            }
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state == GateState.EXECUTING) {
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        GateState.EXECUTING,
                        GateState.AUTHORIZED,
                        "PROGRAM",
                        operationId,
                        "NEVER_STARTED_REDRIVE",
                        operationId,
                        clock.instant());
            }
            else if (state != GateState.AUTHORIZED) {
                throw new IllegalStateException(
                        "expired publication is not redrivable");
            }
            runtime.redriveExpiredPublish(operationId, generation);
            return Boolean.TRUE;
        });
    }

    /** Recovers an activated expired publication without mutation authority. */
    public void recoverExpiredCiUpdateProbe(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        inTransaction(() -> {
            GateAuthorization authorization = authorizationForOperation(
                    operationId).orElseThrow(() ->
                            new IllegalStateException(
                                    "publication authorization is missing"));
            ExternalEffectPlan plan = githubEffects.plan(
                    authorization.effectPlanRef()).orElseThrow();
            if (githubEffects.attempts(plan.planId()).isEmpty()) {
                throw new IllegalStateException(
                        "probe-only recovery requires an activated attempt");
            }
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            assertStoredAuthorizationGraph(authorization);
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            if (state == GateState.EXECUTING) {
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        GateState.EXECUTING,
                        GateState.NEEDS_ATTENTION,
                        "PROGRAM",
                        operationId,
                        "EFFECT_UNKNOWN",
                        operationId,
                        clock.instant());
            }
            else if (state != GateState.NEEDS_ATTENTION) {
                throw new IllegalStateException(
                        "activated publication is not probe-recoverable");
            }
            runtime.redriveExpiredPublishProbeOnly(
                    operationId,
                    generation,
                    clock.instant().plus(Duration.ofSeconds(5)));
            return Boolean.TRUE;
        });
    }

    /** Recovers an expired INITIAL_PUBLISH from its exact current-step evidence. */
    public void recoverExpiredInitialPublish(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        inTransaction(() -> {
            GateAuthorization authorization = authorizationForOperation(
                    operationId).orElseThrow(() ->
                            new IllegalStateException(
                                    "publication authorization is missing"));
            Plan plan = githubEffects.initialPublishPlan(
                    authorization.effectPlanRef()).orElseThrow(() ->
                            new IllegalStateException(
                                    "initial publication plan is missing"));
            if (!plan.operationId().equals(operationId)) {
                throw new IllegalStateException(
                        "initial publication recovery graph is invalid");
            }
            lockCurrentGateRevision(
                    authorization.gateId(), authorization.gateRevision());
            assertInitialAuthorizationGraph(authorization,
                    authorization.gateId(), authorization.gateRevision(),
                    authorization.subjectDigest(), authorization.actionDigest(),
                    authorization.idempotencyKey());
            InitialRecoveryKind recovery =
                    githubEffects.initialPublishRecoveryKind(
                            plan.planId(), operationId);
            if (recovery == InitialRecoveryKind.COMPLETE) {
                GateState completeState = currentState(
                        authorization.gateId(), authorization.gateRevision());
                if (completeState != GateState.EXECUTING
                        && completeState != GateState.NEEDS_ATTENTION) {
                    throw new IllegalStateException(
                            "fully receipted publication is not settleable");
                }
                runtime.redriveExpiredPublish(operationId, generation);
                return Boolean.TRUE;
            }
            GateState state = currentState(
                    authorization.gateId(), authorization.gateRevision());
            GateState expected = recovery == InitialRecoveryKind.PROBE_ONLY
                    ? GateState.NEEDS_ATTENTION : GateState.AUTHORIZED;
            boolean mayTransition = state == GateState.EXECUTING
                    || recovery == InitialRecoveryKind.PROBE_ONLY
                            && state == GateState.AUTHORIZED;
            if (mayTransition && state != expected) {
                appendTransition(
                        authorization.gateId(), authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        state, expected, "PROGRAM", operationId,
                        recovery == InitialRecoveryKind.PROBE_ONLY
                                ? state == GateState.AUTHORIZED
                                        ? "EFFECT_AUTHORITY_UNPROVEN"
                                        : "EFFECT_UNKNOWN"
                                : "NEVER_STARTED_REDRIVE",
                        operationId, clock.instant());
            }
            else if (state != expected) {
                throw new IllegalStateException(
                        "expired initial publication gate is not recoverable");
            }
            if (recovery == InitialRecoveryKind.PROBE_ONLY) {
                runtime.redriveExpiredPublishProbeOnly(
                        operationId, generation,
                        clock.instant().plus(Duration.ofSeconds(5)));
            }
            else {
                runtime.redriveExpiredPublish(operationId, generation);
            }
            return Boolean.TRUE;
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

    public Optional<UserGate> initialGate(String prId)
    {
        requireText(prId, "prId");
        return jdbc.query("SELECT * FROM flow_user_gate WHERE pr_id = ? "
                        + "AND kind = 'INITIAL_PUBLISH'", (result, row) ->
                        readGate(result), prId).stream().findFirst();
    }

    private UserGate requireGate(String gateId)
    {
        return jdbc.query("SELECT * FROM flow_user_gate WHERE gate_id = ?",
                (result, row) -> readGate(result), gateId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("gate is missing"));
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

    public Optional<LocalReviewBinding> localReviewBinding(String bindingId)
    {
        requireText(bindingId, "bindingId");
        return jdbc.query(
                "SELECT * FROM flow_user_gate_local_review_binding WHERE binding_id = ?",
                (result, row) -> readLocalReviewBinding(result),
                bindingId).stream().findFirst();
    }

    public Optional<GateAuthorization> authorization(String authorizationId)
    {
        requireText(authorizationId, "authorizationId");
        return jdbc.query(
                "SELECT * FROM flow_user_gate_authorization "
                        + "WHERE authorization_id = ?",
                (result, row) -> readAuthorization(result),
                authorizationId).stream().findFirst();
    }

    /** Recomputes the complete immutable owner graph for a consumed receipt. */
    public Optional<CiUpdateLearningPublication> learningPublication(
            String receiptId)
    {
        requireText(receiptId, "receiptId");
        Optional<ExternalEffectReceipt> stored =
                githubEffects.exactReceiptById(receiptId);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ExternalEffectReceipt receipt = stored.orElseThrow();
        ExternalEffectPlan plan = githubEffects.plan(receipt.planId())
                .orElseThrow(() -> new IllegalStateException(
                        "learning receipt plan is missing"));
        GateAuthorization authorization = authorization(
                plan.authorizationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "learning authorization is missing"));
        AuthorizedCiUpdate exact = assertStoredAuthorizationGraph(
                authorization);
        GateRevision revision = requireRevision(
                authorization.gateId(), authorization.gateRevision());
        GateSubject subject = requireSubject(revision.subjectManifestRef());
        CiUpdateAction action = requireAction(revision.actionManifestRef());
        Integer consumed = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition t
                WHERE t.gate_id = ? AND t.gate_revision = ?
                  AND t.to_state = 'CONSUMED'
                  AND t.reason_code = 'EFFECT_APPLIED'
                  AND t.actor_type = 'PROGRAM' AND t.actor_id = ?
                  AND t.detail_ref = ?
                  AND t.sequence = (
                      SELECT MAX(t2.sequence)
                      FROM flow_user_gate_transition t2
                      WHERE t2.gate_id = ? AND t2.gate_revision = ?
                  )
                """,
                Integer.class,
                authorization.gateId(), authorization.gateRevision(),
                receipt.operationId(), receipt.receiptId(),
                authorization.gateId(), authorization.gateRevision());
        if (requireNonNull(consumed,
                "learning consumed count is null") != 1
                || !exact.planId().equals(plan.planId())
                || !exact.operationId().equals(receipt.operationId())
                || !plan.operationId().equals(receipt.operationId())
                || !plan.prId().equals(subject.prId())
                || !plan.actionRef().equals(action.actionRef())
                || !plan.actionDigest().equals(action.actionDigest())
                || !receipt.expectedRemoteHead().equals(
                        action.expectedRemoteHead())
                || !receipt.proposedHead().equals(action.proposedHead())) {
            throw new IllegalStateException(
                    "learning publication graph is inconsistent");
        }
        return Optional.of(new CiUpdateLearningPublication(
                receipt, plan, authorization, revision, subject, action));
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
        String localReviewDigest = stableId(
                "local-review-binding:v1",
                pr.prId(),
                changeSet.changeSetRevisionId(),
                "batch-count:0",
                "revision-count:0");
        LocalReviewBinding localReviewBinding = new LocalReviewBinding(
                stableId(
                        "local-review-binding-id:v1",
                        pr.prId(),
                        changeSet.changeSetRevisionId()),
                pr.prId(),
                changeSet.changeSetRevisionId(),
                localReviewDigest,
                createdAt);
        CodePublicationReviewBinding localReview =
                new CodePublicationReviewBinding(
                        changeSet.changeSetRevisionId(),
                        localReviewBinding.bindingId(),
                        true,
                        List.of(),
                        List.of(),
                        localReviewDigest);
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
                pr.headRepositoryExternalId(),
                pr.headRepositoryOwner(),
                pr.headRepositoryName(),
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
                pr.headRepositoryExternalId(),
                pr.headRepositoryOwner(),
                pr.headRepositoryName(),
                branchRef,
                pr.currentRemoteHead(),
                changeSet.headSha(),
                "force:false");
        return new GateBundle(
                localReviewBinding,
                subject,
                new CiUpdateAction(
                        stableId("gate-action", runId, actionDigest),
                        pr.headRepositoryExternalId(),
                        pr.headRepositoryOwner(),
                        pr.headRepositoryName(),
                        branchRef,
                        pr.currentRemoteHead(),
                        changeSet.headSha(),
                        false,
                        actionDigest,
                        createdAt));
    }

    private InitialGateBundle deriveInitialPublish(
            String runId,
            InitialPublishRecords.RepositoryObservation observation)
    {
        AgentRun run = runtime.run(runId).orElseThrow(() ->
                new ReadyRejectedException(List.of("INITIAL_RUN_MISSING")));
        Operation operation = runtime.operation(run.operationId()).orElseThrow();
        Task task = runtime.task(operation.taskId()).orElseThrow();
        if (!observation.consumeMatches(
                runId, task.taskId(), task.repositoryId(),
                task.launchDigest(), task.repositoryOwner(),
                task.repositoryName())
                || !observation.repositoryExternalId().matches(
                        "[1-9][0-9]*")) {
            throw rejected("INITIAL_REPOSITORY_IDENTITY_STALE");
        }
        return deriveInitialPublish(
                runId,
                observation.repositoryExternalId(),
                observation.owner(),
                observation.name(),
                clock.instant());
    }

    private InitialGateBundle deriveInitialPublish(
            String runId,
            String repositoryExternalId,
            String repositoryOwner,
            String repositoryName,
            Instant createdAt)
    {
        AgentRun run = runtime.run(runId).orElseThrow(() ->
                new ReadyRejectedException(List.of("INITIAL_RUN_MISSING")));
        Operation operation = runtime.operation(run.operationId()).orElseThrow();
        Task task = runtime.task(operation.taskId()).orElseThrow();
        PullRequestSubject pr = task.prId() == null ? null
                : runtime.pullRequest(task.prId()).orElse(null);
        ChangeSetRevision changeSet = runtime.currentChangeSet(task.taskId())
                .orElse(null);
        TaskBaseRevision base = runtime.currentBaseRevision(task.taskId())
                .orElse(null);
        PendingWork input = runtime.pendingWork(task.taskId()).stream()
                .filter(item -> Objects.equals(
                        item.selectedByOperationId(), operation.operationId()))
                .findFirst().orElse(null);
        AgentResult reviewerResult = input == null ? null
                : runtime.resultForRun(input.externalKey()).orElse(null);
        ReviewerRequest review = input == null ? null
                : runtime.reviewerRequestForReviewerRun(input.externalKey())
                        .orElse(null);
        AgentRun parent = review == null ? null
                : runtime.run(review.parentRunId()).orElse(null);
        if (pr == null || changeSet == null || base == null || review == null
                || input == null || reviewerResult == null || parent == null
                || task.status() != TaskStatus.ACTIVE || pr.published()
                || run.role() != AgentRole.TASK_AGENT
                || run.state() != RunState.RUNNING
                || run.wakeKind() != WakeKind.AGENT_RESULT_READY
                || run.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || !Objects.equals(run.inputChangeSetRevisionId(),
                        changeSet.changeSetRevisionId())
                || input.kind() != PendingKind.AGENT_RESULT_READY
                || input.intendedGateKind()
                        != GateIntent.INITIAL_PUBLISH
                || !input.externalKey().equals(reviewerResult.runId())
                || !Objects.equals(
                        input.agentResultId(), reviewerResult.resultId())
                || !input.payloadRef().equals(reviewerResultPayload(
                        review.requestId(), reviewerResult.resultId()))
                || !review.intendedGateKind().equals(GateIntent.INITIAL_PUBLISH)
                || !review.taskId().equals(task.taskId())
                || parent.role() != AgentRole.TASK_AGENT
                || parent.state() == RunState.QUEUED
                || parent.state() == RunState.RUNNING
                || !parent.operationId().equals(review.parentOperationId())
                || !review.changeSetRevisionId().equals(
                        changeSet.changeSetRevisionId())
                || !review.reviewedHeadSha().equals(changeSet.headSha())
                || !review.baseHeadSha().equals(base.baseSha())
                || !review.headTreeDigest().equals(changeSet.headTreeDigest())
                || !review.diffDigest().equals(changeSet.diffDigest())
                || !changeSet.differsFromBase()) {
            throw rejected("INITIAL_SUBJECT_STALE");
        }
        AgentRun reviewerRun = runtime.runForOperation(review.reviewerOperationId())
                .orElseThrow(() -> rejected("EXACT_HEAD_REVIEW_MISSING"));
        if (reviewerRun.state() != RunState.COMPLETED
                || !reviewerRun.runId().equals(input.externalKey())
                || !reviewerResult.runId().equals(reviewerRun.runId())
                || reviewerResult.terminalOutcome() != TerminalOutcome.COMPLETED) {
            throw rejected("EXACT_HEAD_REVIEW_MISSING");
        }
        PrDraftRevision draft = runtime.currentPrDraft(pr.prId())
                .orElseThrow(() -> rejected("PR_DRAFT_MISSING"));
        if (!draft.changeSetRevisionId().equals(changeSet.changeSetRevisionId())
                || !draft.headSha().equals(changeSet.headSha())) {
            throw rejected("PR_DRAFT_STALE");
        }
        LocalChecks.ReviewerEvidence reservation = localChecks.reviewerEvidence(
                task.taskId(), changeSet.changeSetRevisionId(),
                GateIntent.INITIAL_PUBLISH);
        reservation.assertCurrentForReservation();
        LocalCheckEvidence checks = reservation.evidence();
        if (!checks.policyRevisionId().equals(review.localCheckPolicyRevisionId())
                || !checks.checkRunRefs().equals(review.checkRunRefs())) {
            throw rejected("LOCAL_CHECK_EVIDENCE_STALE");
        }
        List<String> blockers = new ArrayList<>();
        for (LocalCheckRun check : checks.runs()) {
            if (check.conclusion() == LocalCheckConclusion.FAILED) {
                blockers.add("LOCAL_CHECK_FAILED:" + check.profileId());
            }
        }
        if (!blockers.isEmpty()) {
            throw new ReadyRejectedException(blockers);
        }
        RequiredCiPolicyRevision policy = autofix.currentPolicy(
                task.repositoryId(), pr.scopeKey()).orElseThrow(() ->
                        rejected("REQUIRED_CI_POLICY_MISSING"));
        if (!policy.targetBaseRef().equals(pr.targetBaseRef())) {
            throw rejected("REQUIRED_CI_POLICY_STALE");
        }
        String localDigest = stableId("local-review-binding:v1", pr.prId(),
                changeSet.changeSetRevisionId(), "batch-count:0",
                "revision-count:0");
        LocalReviewBinding local = new LocalReviewBinding(stableId(
                "local-review-binding-id:v1", pr.prId(),
                changeSet.changeSetRevisionId()), pr.prId(),
                changeSet.changeSetRevisionId(), localDigest, createdAt);
        CodePublicationReviewBinding reviewBinding =
                new CodePublicationReviewBinding(changeSet.changeSetRevisionId(),
                        local.bindingId(), true, List.of(), List.of(), localDigest);
        String branchRef = "refs/heads/" + task.branchName();
        String targetDigest = GitHubEffects.initialTargetSnapshotDigest(task.taskId(),
                pr.prId(), task.repositoryId(), task.launchDigest(),
                repositoryExternalId, repositoryOwner,
                repositoryName, repositoryExternalId,
                repositoryOwner, repositoryName,
                task.branchName(), branchRef, pr.targetBaseRef(), base.baseSha(),
                changeSet.headSha(), policy.policyRevisionId());
        TargetSnapshot target = new TargetSnapshot(
                GitHubEffects.initialTargetSnapshotId(targetDigest),
                task.taskId(), pr.prId(), task.repositoryId(),
                task.launchDigest(), repositoryExternalId,
                repositoryOwner, repositoryName,
                repositoryExternalId, repositoryOwner,
                repositoryName, task.branchName(), branchRef,
                pr.targetBaseRef(), base.baseSha(), changeSet.headSha(),
                policy.policyRevisionId(), targetDigest, createdAt);
        List<LocalCheckBinding> bindings = checks.runs().stream().map(check ->
                new LocalCheckBinding(check.checkRunId(), check.profileId(),
                        check.conclusion())).toList();
        String subjectDigest = initialSubjectDigest(task, pr, changeSet, base,
                draft, checks, review, reviewerRun, reviewerResult, reviewBinding,
                target);
        InitialPublishSubject subject = new InitialPublishSubject(stableId(
                "initial-publish-subject:v1", runId, subjectDigest), task.taskId(),
                pr.prId(), task.repositoryId(), task.launchDigest(),
                changeSet.changeSetRevisionId(), base.baseRevisionId(), base.baseSha(),
                changeSet.headSha(), changeSet.headTreeDigest(), changeSet.diffDigest(),
                draft.draftRevisionId(), draft.draftDigest(), policy.policyRevisionId(),
                checks.policyRevisionId(), bindings, review.requestId(),
                reviewerRun.runId(), reviewerResult.resultId(), reviewBinding,
                target.baseRepositoryExternalId(), target.baseRepositoryOwner(),
                target.baseRepositoryName(), target.headRepositoryExternalId(),
                target.headRepositoryOwner(), target.headRepositoryName(), branchRef,
                target.targetBaseRef(), target.targetSnapshotId(),
                target.targetSnapshotDigest(), subjectDigest, runId, createdAt);
        String actionDigest = initialActionDigest(subject, "KEEP_DRAFT");
        InitialPublishAction action = new InitialPublishAction(stableId(
                "initial-publish-action:v1", subject.subjectId(), actionDigest),
                actionDigest, subject.prId(), subject.changeSetRevisionId(),
                subject.baseRepositoryExternalId(), subject.baseRepositoryOwner(),
                subject.baseRepositoryName(), subject.headRepositoryExternalId(),
                subject.headRepositoryOwner(), subject.headRepositoryName(),
                subject.branchRef(), subject.targetBaseRef(), subject.expectedBaseSha(),
                subject.proposedHead(), subject.draftRevisionId(), subject.draftDigest(),
                subject.requiredCiPolicyRevisionId(), "KEEP_DRAFT",
                subject.targetSnapshotId(), subject.targetSnapshotDigest(), createdAt);
        return new InitialGateBundle(local, subject, action, target);
    }

    private InitialGateBundle requireInitialReadyBundle(
            ReadyForReviewRequest request)
    {
        InitialPublishSubject subject = requireInitialSubject(
                request.subjectRef());
        InitialPublishAction action = requireInitialAction(
                request.actionRef());
        if (!request.runId().equals(subject.createdByRunId())
                || !request.taskId().equals(subject.taskId())
                || !request.prId().equals(subject.prId())
                || !request.subjectDigest().equals(subject.subjectDigest())
                || !request.actionDigest().equals(action.actionDigest())
                || !actionMatchesInitialSubject(action, subject)) {
            throw new IllegalStateException(
                    "INITIAL ready request changed its sealed bundle");
        }
        assertStoredInitialSubject(subject);
        TargetSnapshot target = githubEffects.initialTargetSnapshot(
                subject.targetSnapshotId()).orElseThrow(() ->
                        new IllegalStateException(
                                "INITIAL ready target is missing"));
        LocalReviewBinding local = localReviewBinding(
                subject.localReview().bindingId()).orElseThrow(() ->
                        new IllegalStateException(
                                "INITIAL ready local review is missing"));
        return new InitialGateBundle(local, subject, action, target);
    }

    private void assertInitialSubjectCurrent(
            InitialPublishSubject subject,
            InitialPublishAction action,
            Claim claim,
            WriterFence fence)
    {
        runtime.assertWriterFence(claim, fence);
        InitialGateBundle current = deriveInitialPublish(
                subject.createdByRunId(),
                subject.baseRepositoryExternalId(),
                subject.baseRepositoryOwner(),
                subject.baseRepositoryName(),
                subject.createdAt());
        if (!current.subject().equals(subject)
                || !current.action().equals(action)
                || !current.target().targetSnapshotId().equals(
                        subject.targetSnapshotId())) {
            throw new FlowRuntime.StaleOwnerRevisionException(
                    "INITIAL ready subject is no longer current");
        }
    }

    private Inspection inspect(
            Path repositoryRoot, InitialPublishSubject subject)
    {
        Task task = runtime.task(subject.taskId()).orElseThrow();
        return worktreeInspector.inspect(
                repositoryRoot,
                Path.of(task.worktreePath()),
                task.branchName(),
                subject.expectedBaseSha(),
                subject.proposedHead());
    }

    private static boolean inspectionMatches(
            Inspection inspection, InitialPublishSubject subject)
    {
        return inspection.headSha().equals(subject.proposedHead())
                && inspection.headTreeDigest().equals(
                        subject.headTreeDigest())
                && inspection.baseToHeadDiffDigest().equals(
                        subject.diffDigest());
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
            if (currentState != GateState.OPEN
                    && currentState != GateState.STALE
                    && currentState != GateState.CONSUMED) {
                throw new IllegalStateException(
                        "authorized CI_UPDATE gate may not be revised");
            }
            if (currentState == GateState.OPEN) {
                appendTransition(
                        gateId,
                        gate.currentRevision(),
                        nextTransitionSequence(gateId),
                        GateState.OPEN,
                        GateState.STALE,
                        "SUPERSEDED_BY_READY",
                        "ready-subject:" + subject.subjectId(),
                        now);
            }
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
                    gate_id, kind, revision, subject_manifest_ref, subject_digest,
                    action_manifest_ref, action_digest,
                    readiness_evidence_ref, created_by_run_id, created_at
                ) VALUES (?, 'CI_UPDATE', ?, ?, ?, ?, ?, ?, ?, ?)
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
                INSERT INTO flow_user_gate_subject_manifest (
                    subject_id, kind, subject_digest
                ) VALUES (?, 'CI_UPDATE', ?)
                """,
                subject.subjectId(), subject.subjectDigest());
        jdbc.update(
                """
                INSERT INTO flow_user_gate_subject (
                    subject_id, subject_digest, task_id, pr_id,
                    repository_id, head_repository_external_id,
                    head_repository_owner, head_repository_name, branch_ref,
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
                    local_review_binding_id,
                    local_review_batch_refs_json,
                    local_review_revision_refs_json, local_review_digest,
                    ci_memory_refs_json, manual_only, created_by_run_id,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, '[]', '[]', ?,
                          '[]', ?, ?, ?)
                """,
                subject.subjectId(),
                subject.subjectDigest(),
                subject.taskId(),
                subject.prId(),
                subject.repositoryId(),
                subject.headRepositoryExternalId(),
                subject.headRepositoryOwner(),
                subject.headRepositoryName(),
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
                subject.localReview().bindingId(),
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

    private void insertLocalReviewBinding(LocalReviewBinding binding)
    {
        Optional<LocalReviewBinding> existing = localReviewBinding(
                binding.bindingId());
        if (existing.isPresent()) {
            if (!sameLocalReviewBinding(existing.orElseThrow(), binding)) {
                throw new IllegalStateException(
                        "local-review binding replay changed identity");
            }
            return;
        }
        jdbc.update(
                """
                INSERT INTO flow_user_gate_local_review_binding (
                    binding_id, pr_id, candidate_change_set_revision_id,
                    binding_digest, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                binding.bindingId(),
                binding.prId(),
                binding.candidateChangeSetRevisionId(),
                binding.digest(),
                binding.createdAt().toEpochMilli());
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
                INSERT INTO flow_user_gate_action_manifest (
                    action_ref, kind, action_digest
                ) VALUES (?, 'CI_UPDATE', ?)
                """,
                action.actionRef(), action.actionDigest());
        jdbc.update(
                """
                INSERT INTO flow_user_gate_ci_update_action (
                    action_ref, head_repository_external_id,
                    head_repository_owner, head_repository_name,
                    branch_ref, expected_remote_head,
                    proposed_head, force_push, action_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                action.actionRef(),
                action.headRepositoryExternalId(),
                action.headRepositoryOwner(),
                action.headRepositoryName(),
                action.branchRef(),
                action.expectedRemoteHead(),
                action.proposedHead(),
                action.actionDigest(),
                action.createdAt().toEpochMilli());
    }

    private void insertInitialSubject(InitialPublishSubject subject)
    {
        Optional<InitialPublishSubject> existing = initialSubject(subject.subjectId());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(subject)) {
                throw new IllegalStateException("initial subject replay changed identity");
            }
            return;
        }
        jdbc.update("INSERT INTO flow_user_gate_subject_manifest "
                        + "(subject_id, kind, subject_digest) VALUES (?, 'INITIAL_PUBLISH', ?)",
                subject.subjectId(), subject.subjectDigest());
        jdbc.update(
                """
                INSERT INTO flow_user_gate_initial_publish_subject (
                    subject_id, subject_digest, task_id, pr_id, repository_id,
                    launch_digest, change_set_revision_id, base_revision_id,
                    expected_base_sha, proposed_head, head_tree_digest, diff_digest,
                    draft_revision_id, draft_digest, required_ci_policy_revision_id,
                    local_check_policy_revision_id, reviewer_request_id, reviewer_run_id,
                    reviewer_result_id, local_review_binding_id, local_review_digest,
                    base_repository_external_id, base_repository_owner,
                    base_repository_name, head_repository_external_id,
                    head_repository_owner, head_repository_name, branch_ref,
                    target_base_ref, target_snapshot_id, target_snapshot_digest,
                    created_by_run_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                subject.subjectId(), subject.subjectDigest(), subject.taskId(),
                subject.prId(), subject.repositoryId(), subject.launchDigest(),
                subject.changeSetRevisionId(), subject.baseRevisionId(),
                subject.expectedBaseSha(), subject.proposedHead(),
                subject.headTreeDigest(), subject.diffDigest(),
                subject.draftRevisionId(), subject.draftDigest(),
                subject.requiredCiPolicyRevisionId(),
                subject.localCheckPolicyRevisionId(), subject.reviewerRequestId(),
                subject.reviewerRunId(), subject.reviewerResultId(),
                subject.localReview().bindingId(), subject.localReview().digest(),
                subject.baseRepositoryExternalId(), subject.baseRepositoryOwner(),
                subject.baseRepositoryName(), subject.headRepositoryExternalId(),
                subject.headRepositoryOwner(), subject.headRepositoryName(),
                subject.branchRef(), subject.targetBaseRef(),
                subject.targetSnapshotId(), subject.targetSnapshotDigest(),
                subject.createdByRunId(), subject.createdAt().toEpochMilli());
        for (int ordinal = 0; ordinal < subject.localChecks().size(); ordinal++) {
            LocalCheckBinding check = subject.localChecks().get(ordinal);
            jdbc.update("INSERT INTO flow_user_gate_initial_publish_subject_local_check "
                            + "(subject_id, ordinal, check_run_id, profile_id, conclusion) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    subject.subjectId(), ordinal, check.checkRunId(),
                    check.profileId(), check.conclusion().name());
        }
    }

    private void insertInitialAction(InitialPublishAction action)
    {
        Optional<InitialPublishAction> existing = initialAction(action.actionRef());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(action)) {
                throw new IllegalStateException("initial action replay changed identity");
            }
            return;
        }
        jdbc.update("INSERT INTO flow_user_gate_action_manifest "
                        + "(action_ref, kind, action_digest) VALUES (?, 'INITIAL_PUBLISH', ?)",
                action.actionRef(), action.actionDigest());
        jdbc.update(
                """
                INSERT INTO flow_user_gate_initial_publish_action (
                    action_ref, action_digest, pr_id, change_set_revision_id,
                    base_repository_external_id, base_repository_owner,
                    base_repository_name, head_repository_external_id,
                    head_repository_owner, head_repository_name, branch_ref,
                    target_base_ref, expected_base_sha, proposed_head,
                    draft_revision_id, draft_digest, required_ci_policy_revision_id,
                    ready_policy, target_snapshot_id, target_snapshot_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                action.actionRef(), action.actionDigest(), action.prId(),
                action.changeSetRevisionId(), action.baseRepositoryExternalId(),
                action.baseRepositoryOwner(), action.baseRepositoryName(),
                action.headRepositoryExternalId(), action.headRepositoryOwner(),
                action.headRepositoryName(), action.branchRef(),
                action.targetBaseRef(), action.expectedBaseSha(),
                action.proposedHead(), action.draftRevisionId(), action.draftDigest(),
                action.requiredCiPolicyRevisionId(), action.readyPolicy(),
                action.targetSnapshotId(), action.targetSnapshotDigest(),
                action.createdAt().toEpochMilli());
    }

    private Optional<CiUpdateAction> action(String actionRef)
    {
        return jdbc.query(
                "SELECT * FROM flow_user_gate_ci_update_action WHERE action_ref = ?",
                (result, row) -> new CiUpdateAction(
                        result.getString("action_ref"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("proposed_head"),
                        result.getInt("force_push") != 0,
                        result.getString("action_digest"),
                        instant(result, "created_at")),
                actionRef).stream().findFirst();
    }

    public Optional<InitialPublishSubject> initialSubject(String subjectId)
    {
        requireText(subjectId, "subjectId");
        return jdbc.query("SELECT * FROM flow_user_gate_initial_publish_subject WHERE subject_id = ?",
                (result, row) -> readInitialSubject(result), subjectId).stream().findFirst();
    }

    public Optional<InitialPublishAction> initialAction(String actionRef)
    {
        requireText(actionRef, "actionRef");
        return jdbc.query("SELECT * FROM flow_user_gate_initial_publish_action WHERE action_ref = ?",
                (result, row) -> readInitialAction(result), actionRef).stream().findFirst();
    }

    private InitialPublishSubject requireInitialSubject(String subjectId)
    {
        return initialSubject(subjectId).orElseThrow(() ->
                new IllegalStateException("initial gate subject is missing"));
    }

    private InitialPublishAction requireInitialAction(String actionRef)
    {
        return initialAction(actionRef).orElseThrow(() ->
                new IllegalStateException("initial gate action is missing"));
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
        boolean localReviewOwnerPresent =
                result.getInt("local_review_owner_present") != 0;
        String localReviewBindingId =
                result.getString("local_review_binding_id");
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
        GateSubject subject = new GateSubject(
                subjectId,
                result.getString("task_id"),
                result.getString("pr_id"),
                result.getString("repository_id"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
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
                        localReviewBindingId,
                        localReviewOwnerPresent,
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
        assertStoredLocalReviewBinding(subject);
        return subject;
    }

    private InitialPublishSubject readInitialSubject(ResultSet result)
            throws SQLException
    {
        String subjectId = result.getString("subject_id");
        List<LocalCheckBinding> checks = jdbc.query(
                "SELECT * FROM flow_user_gate_initial_publish_subject_local_check "
                        + "WHERE subject_id = ? ORDER BY ordinal",
                (row, number) -> new LocalCheckBinding(
                        row.getString("check_run_id"), row.getString("profile_id"),
                        LocalCheckConclusion.valueOf(row.getString("conclusion"))),
                subjectId);
        InitialPublishSubject subject = new InitialPublishSubject(subjectId,
                result.getString("task_id"), result.getString("pr_id"),
                result.getString("repository_id"), result.getString("launch_digest"),
                result.getString("change_set_revision_id"), result.getString("base_revision_id"),
                result.getString("expected_base_sha"), result.getString("proposed_head"),
                result.getString("head_tree_digest"), result.getString("diff_digest"),
                result.getString("draft_revision_id"), result.getString("draft_digest"),
                result.getString("required_ci_policy_revision_id"),
                result.getString("local_check_policy_revision_id"), checks,
                result.getString("reviewer_request_id"), result.getString("reviewer_run_id"),
                result.getString("reviewer_result_id"), new CodePublicationReviewBinding(
                        result.getString("change_set_revision_id"),
                        result.getString("local_review_binding_id"), true, List.of(), List.of(),
                        result.getString("local_review_digest")),
                result.getString("base_repository_external_id"),
                result.getString("base_repository_owner"), result.getString("base_repository_name"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"), result.getString("head_repository_name"),
                result.getString("branch_ref"), result.getString("target_base_ref"),
                result.getString("target_snapshot_id"), result.getString("target_snapshot_digest"),
                result.getString("subject_digest"), result.getString("created_by_run_id"),
                instant(result, "created_at"));
        assertStoredInitialSubject(subject);
        return subject;
    }

    private static InitialPublishAction readInitialAction(ResultSet result)
            throws SQLException
    {
        return new InitialPublishAction(result.getString("action_ref"),
                result.getString("action_digest"), result.getString("pr_id"),
                result.getString("change_set_revision_id"),
                result.getString("base_repository_external_id"),
                result.getString("base_repository_owner"), result.getString("base_repository_name"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"), result.getString("head_repository_name"),
                result.getString("branch_ref"), result.getString("target_base_ref"),
                result.getString("expected_base_sha"), result.getString("proposed_head"),
                result.getString("draft_revision_id"), result.getString("draft_digest"),
                result.getString("required_ci_policy_revision_id"), result.getString("ready_policy"),
                result.getString("target_snapshot_id"), result.getString("target_snapshot_digest"),
                instant(result, "created_at"));
    }

    private static LocalReviewBinding readLocalReviewBinding(ResultSet result)
            throws SQLException
    {
        return new LocalReviewBinding(
                result.getString("binding_id"),
                result.getString("pr_id"),
                result.getString("candidate_change_set_revision_id"),
                result.getString("binding_digest"),
                instant(result, "created_at"));
    }

    private void assertStoredLocalReviewBinding(GateSubject subject)
    {
        assertStoredLocalReviewBinding(subject.prId(),
                subject.changeSetRevisionId(), subject.localReview());
    }

    private void assertStoredLocalReviewBinding(
            String prId, String changeSetRevisionId,
            CodePublicationReviewBinding review)
    {
        if (!review.ownerPresent()) {
            return;
        }
        LocalReviewBinding binding = localReviewBinding(review.bindingId())
                .orElseThrow(() -> new IllegalStateException(
                        "local-review binding is missing"));
        String expectedId = stableId(
                "local-review-binding-id:v1",
                prId, changeSetRevisionId);
        String expectedDigest = stableId(
                "local-review-binding:v1",
                prId, changeSetRevisionId,
                "batch-count:0",
                "revision-count:0");
        if (!binding.bindingId().equals(expectedId)
                || !binding.prId().equals(prId)
                || !binding.candidateChangeSetRevisionId().equals(
                        changeSetRevisionId)
                || !binding.digest().equals(expectedDigest)
                || !review.digest().equals(expectedDigest)) {
            throw new IllegalStateException(
                    "local-review binding does not match gate subject");
        }
    }

    private void assertStoredInitialSubject(InitialPublishSubject subject)
    {
        assertStoredLocalReviewBinding(subject.prId(),
                subject.changeSetRevisionId(), subject.localReview());
        TargetSnapshot target = githubEffects.initialTargetSnapshot(subject.targetSnapshotId())
                .orElseThrow(() -> new IllegalStateException("initial target is missing"));
        githubEffects.assertExactInitialTargetSnapshot(target);
        if (!target.taskId().equals(subject.taskId()) || !target.prId().equals(subject.prId())
                || !target.repositoryId().equals(subject.repositoryId())
                || !target.launchDigest().equals(subject.launchDigest())
                || !target.proposedHead().equals(subject.proposedHead())
                || !target.requiredCiPolicyRevisionId().equals(subject.requiredCiPolicyRevisionId())
                || !target.targetSnapshotDigest().equals(subject.targetSnapshotDigest())
                || !target.branchRef().equals(subject.branchRef())
                || !target.headBranchName().equals(subject.branchRef()
                        .substring("refs/heads/".length()))
                || !target.targetBaseRef().equals(subject.targetBaseRef())
                || !target.expectedBaseSha().equals(subject.expectedBaseSha())
                || !target.baseRepositoryExternalId().equals(subject.baseRepositoryExternalId())
                || !target.baseRepositoryOwner().equals(subject.baseRepositoryOwner())
                || !target.baseRepositoryName().equals(subject.baseRepositoryName())
                || !target.headRepositoryExternalId().equals(subject.headRepositoryExternalId())
                || !target.headRepositoryOwner().equals(subject.headRepositoryOwner())
                || !target.headRepositoryName().equals(subject.headRepositoryName())) {
            throw new IllegalStateException("initial target does not match subject");
        }
    }

    private GateRevision requireRevision(String gateId, long revision)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_user_gate_revision
                WHERE gate_id = ? AND revision = ?
                """,
                (result, row) -> readRevision(result),
                gateId,
                revision).stream().findFirst().orElseThrow(() ->
                        new IllegalStateException(
                                "gate revision is missing"));
    }

    private void lockCurrentGateRevision(String gateId, long revision)
    {
        int locked = jdbc.update(
                """
                UPDATE flow_user_gate SET gate_id = gate_id
                WHERE gate_id = ? AND current_revision = ?
                """,
                gateId,
                revision);
        if (locked != 1) {
            throw new AuthorizationRejectedException(
                    "GATE_REVISION_STALE");
        }
    }

    private Optional<GateAuthorization> authorizationByKey(
            String actorId, String idempotencyKey)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_user_gate_authorization
                WHERE actor_id = ? AND idempotency_key = ?
                """,
                (result, row) -> readAuthorization(result),
                actorId,
                idempotencyKey).stream().findFirst();
    }

    private Optional<GateAuthorization> authorizationForRevision(
            String gateId, long revision)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_user_gate_authorization
                WHERE gate_id = ? AND gate_revision = ?
                """,
                (result, row) -> readAuthorization(result),
                gateId,
                revision).stream().findFirst();
    }

    private Optional<GateAuthorization> authorizationForOperation(
            String operationId)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_user_gate_authorization
                WHERE operation_id = ?
                """,
                (result, row) -> readAuthorization(result),
                operationId).stream().findFirst();
    }

    private static GateAuthorization readAuthorization(ResultSet result)
            throws SQLException
    {
        return new GateAuthorization(
                result.getString("authorization_id"),
                result.getString("gate_id"),
                result.getLong("gate_revision"),
                result.getString("pr_id"),
                result.getString("subject_digest"),
                result.getString("action_digest"),
                result.getString("authority"),
                result.getString("actor_id"),
                result.getString("consent_id"),
                nullableLong(result, "consent_revision"),
                result.getString("consent_digest"),
                result.getString("idempotency_key"),
                result.getString("operation_id"),
                result.getString("effect_plan_ref"),
                instant(result, "authorized_at"));
    }

    private void insertAuthorization(GateAuthorization authorization)
    {
        jdbc.update(
                """
                INSERT INTO flow_user_gate_authorization (
                    authorization_id, gate_id, gate_revision, pr_id,
                    subject_digest, action_digest, authority, actor_id,
                    consent_id, consent_revision, consent_digest,
                    idempotency_key, operation_id, effect_plan_ref,
                    authorized_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                authorization.authorizationId(),
                authorization.gateId(),
                authorization.gateRevision(),
                authorization.prId(),
                authorization.subjectDigest(),
                authorization.actionDigest(),
                authorization.authority(),
                authorization.actorId(),
                authorization.consentId(),
                authorization.consentRevision(),
                authorization.consentDigest(),
                authorization.idempotencyKey(),
                authorization.operationId(),
                authorization.effectPlanRef(),
                authorization.authorizedAt().toEpochMilli());
    }

    private void maybeAuthorizeNewCiUpdate(
            GateRevision revision,
            GateSubject subject,
            CiUpdateAction action,
            Inspection inspection)
    {
        Optional<CiUpdateConsentRevision> found = currentConsentForTask(
                subject.taskId());
        if (found.isEmpty()) {
            return;
        }
        CiUpdateConsentRevision consent = found.orElseThrow();
        lockCurrentConsent(consent.consentId(), consent.revision());
        consent = currentConsentForTask(subject.taskId()).orElseThrow();
        if (!consentEligible(consent, subject, action, clock.instant())
                || authorizationForRevision(
                        revision.gateId(), revision.revision()).isPresent()
                || currentState(revision.gateId(), revision.revision())
                        != GateState.OPEN
                || authorizationBlocker(
                        revision.gateId(),
                        revision.revision(),
                        revision,
                        subject,
                        action,
                        inspection) != null) {
            return;
        }
        String idempotencyKey = stableId(
                "ci-update-consent-authorization:v1",
                consent.consentId(),
                Long.toString(consent.revision()),
                revision.gateId(),
                Long.toString(revision.revision()),
                revision.subjectDigest(),
                revision.actionDigest());
        createAuthorizationLocked(
                revision.gateId(),
                revision.revision(),
                subject,
                action,
                idempotencyKey,
                consent);
    }

    private boolean consentEligible(
            CiUpdateConsentRevision consent,
            GateSubject subject,
            CiUpdateAction action,
            Instant now)
    {
        return consent.enabled()
                && consent.expiresAt().isAfter(now)
                && !consentUsed(consent)
                && consent.taskId().equals(subject.taskId())
                && consent.prId().equals(subject.prId())
                && consent.repositoryId().equals(subject.repositoryId())
                && consent.headRepositoryExternalId().equals(
                        subject.headRepositoryExternalId())
                && consent.headRepositoryOwner().equals(
                        subject.headRepositoryOwner())
                && consent.headRepositoryName().equals(
                        subject.headRepositoryName())
                && consent.branchRef().equals(subject.branchRef())
                && !subject.manualOnly()
                && subject.localChecks().stream().allMatch(check ->
                        check.conclusion() == LocalCheckConclusion.PASSED)
                && subject.localReview().ownerPresent()
                && subject.localReview().bindingId() != null
                && action.headRepositoryExternalId().equals(
                        consent.headRepositoryExternalId())
                && action.headRepositoryOwner().equals(
                        consent.headRepositoryOwner())
                && action.headRepositoryName().equals(
                        consent.headRepositoryName())
                && action.branchRef().equals(consent.branchRef())
                && !action.forcePush();
    }

    private Optional<CiUpdateConsentRevision> consentByKey(String key)
    {
        return jdbc.query(
                "SELECT * FROM flow_user_gate_ci_consent_revision "
                        + "WHERE actor_id = ? AND idempotency_key = ?",
                (result, row) -> readConsentRevision(result),
                MANUAL_ACTOR,
                key).stream().findFirst().map(this::assertConsentRevision);
    }

    private CiUpdateConsentRevision grantReplay(
            Optional<CiUpdateConsentRevision> replay,
            String taskId,
            Instant expiresAt)
    {
        if (replay.isEmpty()) {
            return null;
        }
        CiUpdateConsentRevision stored = replay.orElseThrow();
        if (!stored.taskId().equals(taskId)
                || !stored.expiresAt().equals(expiresAt)
                || !stored.enabled()) {
            throw new ConsentRejectedException("CONSENT_REPLAY_CONFLICT");
        }
        return assertConsentRevision(stored);
    }

    private CiUpdateConsentRevision revokeReplay(
            Optional<CiUpdateConsentRevision> replay,
            String consentId,
            long expectedRevision)
    {
        if (replay.isEmpty()) {
            return null;
        }
        CiUpdateConsentRevision stored = replay.orElseThrow();
        if (!stored.consentId().equals(consentId)
                || stored.enabled()
                || stored.revision() != expectedRevision + 1) {
            throw new ConsentRejectedException("CONSENT_REPLAY_CONFLICT");
        }
        return assertConsentRevision(stored);
    }

    private Optional<CiUpdateConsentRevision> currentConsentForTask(
            String taskId)
    {
        return jdbc.query(
                """
                SELECT r.* FROM flow_user_gate_ci_consent_current c
                JOIN flow_user_gate_ci_consent_revision r
                  ON r.consent_id = c.consent_id
                 AND r.revision = c.current_revision
                WHERE c.task_id = ?
                """,
                (result, row) -> readConsentRevision(result),
                taskId).stream().findFirst().map(this::assertConsentRevision);
    }

    private Optional<CiUpdateConsentRevision> currentConsent(
            String consentId)
    {
        return jdbc.query(
                """
                SELECT r.* FROM flow_user_gate_ci_consent_current c
                JOIN flow_user_gate_ci_consent_revision r
                  ON r.consent_id = c.consent_id
                 AND r.revision = c.current_revision
                WHERE c.consent_id = ?
                """,
                (result, row) -> readConsentRevision(result),
                consentId).stream().findFirst().map(this::assertConsentRevision);
    }

    private void lockCurrentConsent(String consentId, long revision)
    {
        int locked = jdbc.update(
                "UPDATE flow_user_gate_ci_consent_current "
                        + "SET consent_id = consent_id "
                        + "WHERE consent_id = ? AND current_revision = ?",
                consentId,
                revision);
        if (locked != 1) {
            throw new ConsentRejectedException(
                    "CONSENT_REVISION_CHANGED");
        }
    }

    private boolean consentUsed(CiUpdateConsentRevision consent)
    {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_user_gate_authorization "
                        + "WHERE consent_id = ? AND consent_revision = ?",
                Integer.class,
                consent.consentId(),
                consent.revision());
        return requireNonNull(count, "consent use count is null") != 0;
    }

    private boolean hasNonterminalConsentEffect(String taskId)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM flow_user_gate_authorization a
                JOIN flow_runtime_operation o
                  ON o.operation_id = a.operation_id
                JOIN flow_user_gate_revision r
                  ON r.gate_id = a.gate_id
                 AND r.revision = a.gate_revision
                JOIN flow_user_gate_subject s
                  ON s.subject_id = r.subject_manifest_ref
                WHERE a.authority = 'CI_UPDATE_CONSENT'
                  AND s.task_id = ?
                  AND o.state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                """,
                Integer.class,
                taskId);
        return requireNonNull(count,
                "nonterminal consent effect count is null") != 0;
    }

    private CiUpdateConsentRevision consentRevision(
            String consentId,
            long revision,
            Task task,
            PullRequestSubject pr,
            boolean enabled,
            Instant expiresAt,
            String idempotencyKey,
            Instant recordedAt)
    {
        return consentRevision(
                consentId,
                revision,
                task.taskId(),
                pr.prId(),
                task.repositoryId(),
                pr.remoteIdentityId(),
                pr.headRepositoryExternalId(),
                pr.headRepositoryOwner(),
                pr.headRepositoryName(),
                task.branchName(),
                "refs/heads/" + task.branchName(),
                enabled,
                expiresAt,
                idempotencyKey,
                recordedAt);
    }

    private CiUpdateConsentRevision consentRevision(
            String consentId,
            long revision,
            String taskId,
            String prId,
            String repositoryId,
            String remoteIdentityId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchName,
            String branchRef,
            boolean enabled,
            Instant expiresAt,
            String idempotencyKey,
            Instant recordedAt)
    {
        String digest = stableId(
                "ci-update-consent-revision:v1",
                consentId,
                Long.toString(revision),
                taskId,
                prId,
                repositoryId,
                remoteIdentityId,
                headRepositoryExternalId,
                headRepositoryOwner,
                headRepositoryName,
                branchName,
                branchRef,
                Boolean.toString(enabled),
                expiresAt.toString(),
                MANUAL_ACTOR);
        return new CiUpdateConsentRevision(
                consentId,
                revision,
                taskId,
                prId,
                repositoryId,
                remoteIdentityId,
                headRepositoryExternalId,
                headRepositoryOwner,
                headRepositoryName,
                branchName,
                branchRef,
                enabled,
                expiresAt,
                MANUAL_ACTOR,
                idempotencyKey,
                digest,
                recordedAt);
    }

    private void insertConsentRevision(CiUpdateConsentRevision consent)
    {
        jdbc.update(
                """
                INSERT INTO flow_user_gate_ci_consent_revision (
                    consent_id, revision, task_id, pr_id, repository_id,
                    remote_identity_id, provider,
                    head_repository_external_id, head_repository_owner,
                    head_repository_name, branch_name, branch_ref, enabled,
                    expires_at, actor_id, idempotency_key, revision_digest,
                    recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'GITHUB', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                consent.consentId(),
                consent.revision(),
                consent.taskId(),
                consent.prId(),
                consent.repositoryId(),
                consent.remoteIdentityId(),
                consent.headRepositoryExternalId(),
                consent.headRepositoryOwner(),
                consent.headRepositoryName(),
                consent.branchName(),
                consent.branchRef(),
                consent.enabled() ? 1 : 0,
                consent.expiresAt().toEpochMilli(),
                consent.actorId(),
                consent.idempotencyKey(),
                consent.revisionDigest(),
                consent.recordedAt().toEpochMilli());
    }

    private static CiUpdateConsentRevision readConsentRevision(
            ResultSet result)
            throws SQLException
    {
        return new CiUpdateConsentRevision(
                result.getString("consent_id"),
                result.getLong("revision"),
                result.getString("task_id"),
                result.getString("pr_id"),
                result.getString("repository_id"),
                result.getString("remote_identity_id"),
                result.getString("head_repository_external_id"),
                result.getString("head_repository_owner"),
                result.getString("head_repository_name"),
                result.getString("branch_name"),
                result.getString("branch_ref"),
                result.getInt("enabled") == 1,
                instant(result, "expires_at"),
                result.getString("actor_id"),
                result.getString("idempotency_key"),
                result.getString("revision_digest"),
                instant(result, "recorded_at"));
    }

    private CiUpdateConsentRevision assertConsentRevision(
            CiUpdateConsentRevision consent)
    {
        CiUpdateConsentRevision expected = consentRevision(
                consent.consentId(),
                consent.revision(),
                consent.taskId(),
                consent.prId(),
                consent.repositoryId(),
                consent.remoteIdentityId(),
                consent.headRepositoryExternalId(),
                consent.headRepositoryOwner(),
                consent.headRepositoryName(),
                consent.branchName(),
                consent.branchRef(),
                consent.enabled(),
                consent.expiresAt(),
                consent.idempotencyKey(),
                consent.recordedAt());
        if (!expected.revisionDigest().equals(consent.revisionDigest())) {
            throw new IllegalStateException(
                    "CI_UPDATE consent digest is inconsistent");
        }
        return consent;
    }

    private AuthorizedCiUpdate createAuthorizationLocked(
            String gateId,
            long gateRevision,
            GateSubject subject,
            CiUpdateAction action,
            String idempotencyKey,
            CiUpdateConsentRevision consent)
    {
        Instant now = clock.instant();
        String authorizationId = stableId(
                "gate-authorization:v1",
                gateId,
                Long.toString(gateRevision),
                subject.subjectDigest(),
                action.actionDigest());
        String operationId = stableId(
                "publish-operation:v1", authorizationId);
        PreparedCiUpdatePlan prepared = githubEffects.prepareCiUpdatePlan(
                authorizationId,
                operationId,
                subject.prId(),
                action.headRepositoryExternalId(),
                action.headRepositoryOwner(),
                action.headRepositoryName(),
                action.branchRef(),
                action.expectedRemoteHead(),
                action.proposedHead(),
                action.actionRef(),
                action.actionDigest(),
                subject.requiredCiPolicyRevisionId(),
                now);
        String planId = prepared.plan().planId();
        runtime.reservePublishOperation(
                operationId,
                planId,
                subject.taskId(),
                subject.changeSetRevisionId(),
                prepared.plan().planDigest(),
                now);
        boolean automatic = consent != null;
        GateAuthorization authorization = new GateAuthorization(
                authorizationId,
                gateId,
                gateRevision,
                subject.prId(),
                subject.subjectDigest(),
                action.actionDigest(),
                automatic ? "CI_UPDATE_CONSENT" : "USER",
                automatic ? CONSENT_ACTOR : MANUAL_ACTOR,
                automatic ? consent.consentId() : null,
                automatic ? consent.revision() : null,
                automatic ? consent.revisionDigest() : null,
                idempotencyKey,
                operationId,
                planId,
                now);
        insertAuthorization(authorization);
        githubEffects.insert(prepared);
        appendTransition(
                gateId,
                gateRevision,
                nextTransitionSequence(gateId),
                GateState.OPEN,
                GateState.AUTHORIZED,
                automatic ? "PROGRAM" : "USER",
                automatic ? CONSENT_ACTOR : MANUAL_ACTOR,
                automatic
                        ? "CI_UPDATE_CONSENT_AUTHORIZATION"
                        : "MANUAL_AUTHORIZATION",
                authorizationId,
                now);
        return assertAuthorizationGraph(
                authorization,
                gateId,
                gateRevision,
                subject.subjectDigest(),
                action.actionDigest(),
                idempotencyKey);
    }

    private AuthorizedInitialPublish createInitialAuthorizationLocked(
            String gateId, long gateRevision, InitialPublishSubject subject,
            InitialPublishAction action, String idempotencyKey)
    {
        Instant now = clock.instant();
        String authorizationId = stableId("gate-authorization:v1", gateId,
                Long.toString(gateRevision), subject.subjectDigest(),
                action.actionDigest());
        String operationId = stableId("publish-operation:v1", authorizationId);
        PreparedInitialPublishPlan prepared = githubEffects.prepareInitialPublishPlan(
                authorizationId, operationId, subject.prId(),
                subject.changeSetRevisionId(), action.draftRevisionId(),
                action.draftDigest(), action.readyPolicy(), action.actionRef(),
                action.actionDigest(), action.targetSnapshotId(), now);
        runtime.reservePublishOperation(operationId, prepared.plan().planId(),
                subject.taskId(), subject.changeSetRevisionId(),
                prepared.plan().planDigest(), now);
        GateAuthorization authorization = new GateAuthorization(authorizationId,
                gateId, gateRevision, subject.prId(), subject.subjectDigest(),
                action.actionDigest(), "USER", MANUAL_ACTOR, null, null, null,
                idempotencyKey, operationId, prepared.plan().planId(), now);
        insertAuthorization(authorization);
        githubEffects.insertInitialPublishPlan(prepared);
        appendTransition(gateId, gateRevision, nextTransitionSequence(gateId),
                GateState.OPEN, GateState.AUTHORIZED, "USER", MANUAL_ACTOR,
                "MANUAL_AUTHORIZATION", authorizationId, now);
        return assertInitialAuthorizationGraph(authorization, gateId,
                gateRevision, subject.subjectDigest(), action.actionDigest(),
                idempotencyKey);
    }

    private InitialAuthorityInspection inspectInitialAuthority(
            Task task, InitialPublishSubject subject)
    {
        try {
            return new InitialAuthorityInspection(
                    worktreeInspector.inspect(
                            Path.of(task.repositoryRoot()),
                            Path.of(task.worktreePath()), task.branchName(),
                            subject.expectedBaseSha(), subject.proposedHead()),
                    null, true);
        }
        catch (InspectionFailure failure) {
            if (transientFailure(failure.code())) {
                throw failure;
            }
            return new InitialAuthorityInspection(
                    null, "WORKTREE_" + failure.code().name(), true);
        }
    }

    private InitialAuthorityInspection inspectInitialAuthorityIfRequired(
            Claim claim)
    {
        var operation = runtime.assertPublishClaim(claim);
        Plan plan = githubEffects.initialPublishPlan(operation.inputRef())
                .orElseThrow(() -> new IllegalStateException(
                        "initial publication plan is missing"));
        GateAuthorization authorization = authorization(
                plan.authorizationId()).orElseThrow();
        List<InitialPublishRecords.Step> steps =
                githubEffects.initialPublishSteps(plan.planId());
        int currentOrdinal = githubEffects
                .initialPublishStepReceipts(plan.planId()).size();
        if (currentOrdinal == steps.size()) {
            return new InitialAuthorityInspection(null, null, false);
        }
        boolean attemptBacked = githubEffects.initialPublishAttempts(
                plan.planId()).stream().anyMatch(value ->
                        value.stepId().equals(
                                steps.get(currentOrdinal).stepId()));
        GateState state = currentState(
                authorization.gateId(), authorization.gateRevision());
        if (attemptBacked && state != GateState.AUTHORIZED) {
            return new InitialAuthorityInspection(null, null, false);
        }
        GateRevision revision = requireRevision(
                authorization.gateId(), authorization.gateRevision());
        InitialPublishSubject subject = requireInitialSubject(
                revision.subjectManifestRef());
        return inspectInitialAuthority(
                runtime.task(subject.taskId()).orElseThrow(), subject);
    }

    private String initialAuthorizationBlocker(
            String gateId, long gateRevision, GateRevision revision,
            Inspection inspection)
    {
        try {
            assertInitialRevisionGraph(revision);
            InitialPublishSubject subject = requireInitialSubject(
                    revision.subjectManifestRef());
            InitialPublishAction action = requireInitialAction(
                    revision.actionManifestRef());
            UserGate gate = requireGate(gateId);
            if (gate.kind() != GateKind.INITIAL_PUBLISH
                    || !gate.prId().equals(subject.prId())
                    || gate.currentRevision() != gateRevision) {
                return "GATE_REVISION_STALE";
            }
            Task task = runtime.task(subject.taskId()).orElseThrow();
            PullRequestSubject pr = runtime.pullRequest(subject.prId()).orElseThrow();
            ChangeSetRevision changeSet = runtime.currentChangeSet(subject.taskId())
                    .orElseThrow();
            TaskBaseRevision base = runtime.currentBaseRevision(subject.taskId())
                    .orElseThrow();
            PrDraftRevision draft = runtime.currentPrDraft(subject.prId())
                    .orElseThrow();
            if (task.status() != TaskStatus.ACTIVE
                    || task.selectedWriterOperationId() != null
                    || task.waitingMutationStateRef() != null
                    || !task.launchDigest().equals(subject.launchDigest())
                    || !task.repositoryId().equals(subject.repositoryId())
                    || !Objects.equals(task.prId(), subject.prId())
                    || !Objects.equals(task.currentChangeSetRevisionId(),
                            subject.changeSetRevisionId())
                    || !Objects.equals(task.currentHeadSha(), subject.proposedHead())
                    || !Objects.equals(task.currentBaseRevisionId(), subject.baseRevisionId())
                    || !Objects.equals(task.currentBaseSha(), subject.expectedBaseSha())
                    || pr.published() || !pr.taskId().equals(task.taskId())
                    || !pr.repositoryId().equals(task.repositoryId())
                    || !pr.branchName().equals(task.branchName())
                    || !pr.targetBaseRef().equals(subject.targetBaseRef())
                    || !changeSet.changeSetRevisionId().equals(subject.changeSetRevisionId())
                    || !changeSet.baseRevisionId().equals(subject.baseRevisionId())
                    || !changeSet.baseSha().equals(subject.expectedBaseSha())
                    || !changeSet.headSha().equals(subject.proposedHead())
                    || !changeSet.headTreeDigest().equals(subject.headTreeDigest())
                    || !changeSet.diffDigest().equals(subject.diffDigest())
                    || !changeSet.differsFromBase()
                    || !base.baseRevisionId().equals(subject.baseRevisionId())
                    || !base.baseSha().equals(subject.expectedBaseSha())
                    || !draft.draftRevisionId().equals(subject.draftRevisionId())
                    || !draft.draftDigest().equals(subject.draftDigest())
                    || !draft.changeSetRevisionId().equals(subject.changeSetRevisionId())
                    || !draft.headSha().equals(subject.proposedHead())) {
                return "INITIAL_SUBJECT_STALE";
            }
            if (inspection == null
                    || !inspection.headSha().equals(subject.proposedHead())
                    || !inspection.headTreeDigest().equals(
                            subject.headTreeDigest())
                    || !inspection.baseToHeadDiffDigest().equals(
                            subject.diffDigest())) {
                return "WORKTREE_SUBJECT_STALE";
            }
            LocalChecks.ReviewerEvidence checks;
            try {
                checks = localChecks.reviewerEvidence(subject.taskId(),
                        subject.changeSetRevisionId(), GateIntent.INITIAL_PUBLISH);
                checks.assertCurrentForReservation();
            }
            catch (IllegalStateException staleChecks) {
                return "LOCAL_CHECK_EVIDENCE_STALE";
            }
            List<LocalCheckBinding> bindings = checks.evidence().runs().stream()
                    .map(check -> new LocalCheckBinding(check.checkRunId(),
                            check.profileId(), check.conclusion())).toList();
            if (!checks.evidence().policyRevisionId().equals(
                    subject.localCheckPolicyRevisionId())
                    || !bindings.equals(subject.localChecks())
                    || bindings.stream().anyMatch(check -> check.conclusion()
                            == LocalCheckConclusion.FAILED)) {
                return "LOCAL_CHECK_EVIDENCE_STALE";
            }
            Optional<RequiredCiPolicyRevision> currentPolicy = autofix.currentPolicy(
                    subject.repositoryId(), pr.scopeKey());
            if (currentPolicy.isEmpty()) {
                return "REQUIRED_CI_POLICY_STALE";
            }
            RequiredCiPolicyRevision policy = currentPolicy.orElseThrow();
            if (!policy.policyRevisionId().equals(subject.requiredCiPolicyRevisionId())
                    || !policy.targetBaseRef().equals(subject.targetBaseRef())) {
                return "REQUIRED_CI_POLICY_STALE";
            }
            if (!autofix.lockCurrentPolicy(policy)) {
                return "REQUIRED_CI_POLICY_STALE";
            }
            if (!actionMatchesInitialSubject(action, subject)) {
                throw new IllegalStateException("initial action does not match subject");
            }
            return null;
        }
        catch (ReadyRejectedException | FlowRuntime.StaleOwnerRevisionException stale) {
            return "INITIAL_SUBJECT_STALE";
        }
        catch (IllegalStateException corrupt) {
            throw corrupt;
        }
    }

    private AuthorizedInitialPublish assertInitialAuthorizationGraph(
            GateAuthorization authorization, String gateId, long gateRevision,
            String expectedSubjectDigest, String expectedActionDigest,
            String expectedIdempotencyKey)
    {
        if (!authorization.gateId().equals(gateId)
                || authorization.gateRevision() != gateRevision
                || !authorization.subjectDigest().equals(expectedSubjectDigest)
                || !authorization.actionDigest().equals(expectedActionDigest)
                || !authorization.idempotencyKey().equals(expectedIdempotencyKey)) {
            throw new AuthorizationRejectedException("AUTHORIZATION_REPLAY_CONFLICT");
        }
        requireManualAuthorization(authorization);
        String expectedAuthorizationId = stableId("gate-authorization:v1", gateId,
                Long.toString(gateRevision), expectedSubjectDigest,
                expectedActionDigest);
        String expectedOperationId = stableId("publish-operation:v1",
                expectedAuthorizationId);
        if (!authorization.authorizationId().equals(expectedAuthorizationId)
                || !authorization.operationId().equals(expectedOperationId)) {
            throw new IllegalStateException("initial authorization identity is inconsistent");
        }
        GateRevision revision = requireRevision(gateId, gateRevision);
        assertInitialRevisionGraph(revision);
        InitialPublishSubject subject = requireInitialSubject(revision.subjectManifestRef());
        InitialPublishAction action = requireInitialAction(revision.actionManifestRef());
        Plan plan = githubEffects.initialPublishPlan(authorization.effectPlanRef())
                .orElseThrow(() -> new IllegalStateException("initial plan is missing"));
        List<InitialPublishRecords.Step> steps = githubEffects.initialPublishSteps(plan.planId());
        githubEffects.assertExactInitialPublishPlan(plan, steps);
        Operation operation = runtime.operation(authorization.operationId()).orElseThrow();
        Integer tickets = jdbc.queryForObject("SELECT COUNT(*) FROM flow_runtime_dispatch_ticket "
                + "WHERE operation_id = ?", Integer.class, operation.operationId());
        Integer transitions = jdbc.queryForObject("SELECT COUNT(*) FROM flow_user_gate_transition "
                + "WHERE gate_id = ? AND gate_revision = ? AND from_state = 'OPEN' "
                + "AND to_state = 'AUTHORIZED' AND actor_type = 'USER' "
                + "AND actor_id = ? AND reason_code = 'MANUAL_AUTHORIZATION' AND detail_ref = ?",
                Integer.class, gateId, gateRevision, MANUAL_ACTOR,
                authorization.authorizationId());
        boolean dispatchState = switch (operation.state()) {
            case READY, RETRYABLE -> dispatchTicketInState(operation.operationId(), "AVAILABLE");
            case CLAIMED -> dispatchTicketInState(operation.operationId(), "CLAIMED");
            case CANCELED, SUCCEEDED -> dispatchTicketInState(operation.operationId(), "DONE");
            default -> false;
        };
        if (!plan.authorizationId().equals(authorization.authorizationId())
                || !plan.operationId().equals(authorization.operationId())
                || !plan.prId().equals(subject.prId())
                || !plan.baseRepositoryExternalId().equals(action.baseRepositoryExternalId())
                || !plan.baseRepositoryOwner().equals(action.baseRepositoryOwner())
                || !plan.baseRepositoryName().equals(action.baseRepositoryName())
                || !plan.headRepositoryExternalId().equals(action.headRepositoryExternalId())
                || !plan.headRepositoryOwner().equals(action.headRepositoryOwner())
                || !plan.headRepositoryName().equals(action.headRepositoryName())
                || !plan.branchRef().equals(action.branchRef())
                || !plan.targetBaseRef().equals(action.targetBaseRef())
                || !plan.expectedBaseSha().equals(action.expectedBaseSha())
                || !plan.proposedHead().equals(action.proposedHead())
                || !plan.changeSetRevisionId().equals(subject.changeSetRevisionId())
                || !plan.draftRevisionId().equals(action.draftRevisionId())
                || !plan.draftDigest().equals(action.draftDigest())
                || !plan.actionRef().equals(action.actionRef())
                || !plan.actionDigest().equals(action.actionDigest())
                || !plan.requiredCiPolicyRevisionId().equals(action.requiredCiPolicyRevisionId())
                || !plan.readyPolicy().equals(action.readyPolicy())
                || !plan.targetSnapshotId().equals(subject.targetSnapshotId())
                || !plan.targetSnapshotDigest().equals(subject.targetSnapshotDigest())
                || !operation.ownerKind().equals("GITHUB_EFFECT_PLAN")
                || !operation.ownerId().equals(plan.planId())
                || !operation.inputRef().equals(plan.planId())
                || operation.kind() != OperationKind.PUBLISH
                || !operation.taskId().equals(subject.taskId())
                || !operation.subjectDigest().equals(plan.planDigest())
                || requireNonNull(tickets, "ticket count is null") != 1
                || requireNonNull(transitions, "transition count is null") != 1
                || !dispatchState) {
            throw new IllegalStateException("initial authorization graph is inconsistent");
        }
        return new AuthorizedInitialPublish(authorization, plan.planId(),
                operation.operationId(), plan.prSequence());
    }

    private void assertInitialRevisionGraph(GateRevision revision)
    {
        UserGate gate = requireGate(revision.gateId());
        InitialPublishSubject subject = requireInitialSubject(
                revision.subjectManifestRef());
        InitialPublishAction action = requireInitialAction(
                revision.actionManifestRef());
        if (gate.kind() != GateKind.INITIAL_PUBLISH
                || !gate.gateId().equals(stableId("user-gate", subject.prId(),
                        GateKind.INITIAL_PUBLISH.name()))
                || revision.readinessEvidenceRef() != null
                || !revision.subjectDigest().equals(subject.subjectDigest())
                || !revision.actionDigest().equals(action.actionDigest())
                || !revision.createdByRunId().equals(subject.createdByRunId())
                || !subject.subjectDigest().equals(initialSubjectDigest(subject))
                || !subject.subjectId().equals(stableId("initial-publish-subject:v1",
                        subject.createdByRunId(), subject.subjectDigest()))
                || !action.actionDigest().equals(initialActionDigest(subject,
                        action.readyPolicy()))
                || !action.actionRef().equals(stableId("initial-publish-action:v1",
                        subject.subjectId(), action.actionDigest()))
                || !gate.taskId().equals(subject.taskId())
                || !gate.prId().equals(subject.prId())
                || !actionMatchesInitialSubject(action, subject)) {
            throw new IllegalStateException("initial gate revision is inconsistent");
        }
        assertStoredInitialSubject(subject);
        ReadyForReviewRequest ready = runtime.readyForReviewRequestForRun(
                subject.createdByRunId()).orElseThrow(() ->
                        new IllegalStateException(
                                "initial ready request is missing"));
        AgentRun readyRun = runtime.run(subject.createdByRunId()).orElseThrow(() ->
                new IllegalStateException("initial ready run is missing"));
        Operation readyOperation = runtime.operation(readyRun.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "initial ready operation is missing"));
        PendingWork readyInput = runtime.pendingWork(subject.taskId()).stream()
                .filter(input -> Objects.equals(
                        input.selectedByOperationId(), readyOperation.operationId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "initial ready input is missing"));
        ReviewerRequest request = runtime.reviewerRequest(subject.reviewerRequestId())
                .orElseThrow(() -> new IllegalStateException("initial reviewer request is missing"));
        AgentRun parent = runtime.run(request.parentRunId()).orElseThrow(() ->
                new IllegalStateException("initial reviewer parent is missing"));
        AgentRun reviewer = runtime.run(subject.reviewerRunId()).orElseThrow(() ->
                new IllegalStateException("initial reviewer run is missing"));
        AgentResult result = runtime.resultForRun(subject.reviewerRunId()).orElseThrow(() ->
                new IllegalStateException("initial reviewer result is missing"));
        AgentResult parentResult = runtime.resultForRun(parent.runId()).orElseThrow(() ->
                new IllegalStateException("initial reviewer parent result is missing"));
        Operation parentOperation = runtime.operation(parent.operationId()).orElseThrow(() ->
                new IllegalStateException("initial parent operation is missing"));
        Operation reviewerOperation = runtime.operation(reviewer.operationId()).orElseThrow(() ->
                new IllegalStateException("initial reviewer operation is missing"));
        Integer readyTransition = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                  AND from_state IS NULL AND to_state = 'OPEN'
                  AND actor_type = 'PROGRAM'
                  AND actor_id = ? AND reason_code = 'READY'
                """,
                Integer.class,
                revision.gateId(), revision.revision(), READY_ACTOR);
        if (!ready.requestId().equals(stableId(
                        "ready-request", readyRun.runId(), subject.subjectId()))
                || !ready.subjectRef().equals(subject.subjectId())
                || !ready.subjectDigest().equals(subject.subjectDigest())
                || !ready.actionRef().equals(action.actionRef())
                || !ready.actionDigest().equals(action.actionDigest())
                || !ready.operationId().equals(readyOperation.operationId())
                || !ready.taskId().equals(subject.taskId())
                || !ready.prId().equals(subject.prId())
                || readyRun.role() != AgentRole.TASK_AGENT
                || readyRun.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || readyRun.wakeKind() != WakeKind.AGENT_RESULT_READY
                || readyRun.state() == RunState.QUEUED
                || !readyRun.sessionId().equals(parent.sessionId())
                || !readyRun.inputRef().equals(
                        "inbox:" + readyInput.pendingId())
                || !Objects.equals(readyRun.inputChangeSetRevisionId(),
                        subject.changeSetRevisionId())
                || readyRun.inputRemoteHeadSha() != null
                || !readyRun.headSha().equals(subject.proposedHead())
                || readyOperation.kind() != OperationKind.RUN_TASK_TURN
                || !readyOperation.taskId().equals(subject.taskId())
                || !readyOperation.ownerKind().equals("AGENT_RUN")
                || !readyOperation.ownerId().equals(reviewer.runId())
                || !readyOperation.inputRef().equals(
                        "inbox:" + readyInput.pendingId())
                || readyInput.kind() != PendingKind.AGENT_RESULT_READY
                || readyInput.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || !readyInput.externalKey().equals(reviewer.runId())
                || !readyInput.subjectHead().equals(subject.proposedHead())
                || !Objects.equals(
                        readyInput.agentResultId(), result.resultId())
                || !readyInput.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), result.resultId()))
                || parent.role() != AgentRole.TASK_AGENT
                || parent.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || parentOperation.kind() != OperationKind.RUN_TASK_TURN
                || !parentOperation.taskId().equals(subject.taskId())
                || !parent.operationId().equals(request.parentOperationId())
                || !request.parentRunId().equals(parent.runId())
                || request.intendedGateKind() != GateIntent.INITIAL_PUBLISH
                || !request.taskId().equals(subject.taskId())
                || !request.reviewerOperationId().equals(reviewer.operationId())
                || reviewer.role() != AgentRole.ADVERSARIAL_REVIEWER
                || reviewerOperation.kind() != OperationKind.RUN_REVIEWER
                || !reviewerOperation.taskId().equals(subject.taskId())
                || !reviewerOperation.ownerKind().equals("REVIEW_REQUEST")
                || !reviewerOperation.ownerId().equals(request.requestId())
                || !reviewerOperation.inputRef().equals("review-request:" + request.requestId())
                || reviewer.state() != RunState.COMPLETED
                || result.terminalOutcome() != TerminalOutcome.COMPLETED
                || parent.state() == RunState.QUEUED
                || parent.state() == RunState.RUNNING
                || parentOperation.state() == OperationState.READY
                || parentOperation.state() == OperationState.CLAIMED
                || parentOperation.state() == OperationState.WAITING
                || parentOperation.state() == OperationState.RETRYABLE
                || !Objects.equals(parentOperation.resultRef(),
                        parentResult.resultId())
                || !result.runId().equals(reviewer.runId())
                || !result.resultId().equals(subject.reviewerResultId())
                || !request.changeSetRevisionId().equals(subject.changeSetRevisionId())
                || !request.reviewedHeadSha().equals(subject.proposedHead())
                || !request.baseHeadSha().equals(subject.expectedBaseSha())
                || !request.headTreeDigest().equals(subject.headTreeDigest())
                || !request.diffDigest().equals(subject.diffDigest())
                || !request.localCheckPolicyRevisionId().equals(
                        subject.localCheckPolicyRevisionId())
                || !request.checkRunRefs().equals(subject.localChecks().stream()
                        .map(LocalCheckBinding::checkRunId).toList())
                || requireNonNull(readyTransition,
                        "initial ready transition count is null") != 1) {
            throw new IllegalStateException("initial reviewer graph is inconsistent");
        }
    }

    private static boolean actionMatchesInitialSubject(InitialPublishAction action,
            InitialPublishSubject subject)
    {
        return action.prId().equals(subject.prId())
                && action.changeSetRevisionId().equals(subject.changeSetRevisionId())
                && action.baseRepositoryExternalId().equals(subject.baseRepositoryExternalId())
                && action.baseRepositoryOwner().equals(subject.baseRepositoryOwner())
                && action.baseRepositoryName().equals(subject.baseRepositoryName())
                && action.headRepositoryExternalId().equals(subject.headRepositoryExternalId())
                && action.headRepositoryOwner().equals(subject.headRepositoryOwner())
                && action.headRepositoryName().equals(subject.headRepositoryName())
                && action.branchRef().equals(subject.branchRef())
                && action.targetBaseRef().equals(subject.targetBaseRef())
                && action.expectedBaseSha().equals(subject.expectedBaseSha())
                && action.proposedHead().equals(subject.proposedHead())
                && action.draftRevisionId().equals(subject.draftRevisionId())
                && action.draftDigest().equals(subject.draftDigest())
                && action.requiredCiPolicyRevisionId().equals(
                        subject.requiredCiPolicyRevisionId())
                && action.targetSnapshotId().equals(subject.targetSnapshotId())
                && action.targetSnapshotDigest().equals(subject.targetSnapshotDigest());
    }

    private AuthorityInspection inspectAuthority(
            Path repositoryRoot, GateSubject subject)
    {
        try {
            return new AuthorityInspection(
                    inspect(repositoryRoot, subject), null);
        }
        catch (InspectionFailure failure) {
            if (transientFailure(failure.code())) {
                throw failure;
            }
            return new AuthorityInspection(
                    null, "WORKTREE_" + failure.code().name());
        }
    }

    private AuthorizedCiUpdate assertAuthorizationGraph(
            GateAuthorization authorization,
            String expectedGateId,
            long expectedGateRevision,
            String expectedSubjectDigest,
            String expectedActionDigest,
            String expectedIdempotencyKey)
    {
        if (!authorization.gateId().equals(expectedGateId)
                || authorization.gateRevision() != expectedGateRevision
                || !authorization.subjectDigest().equals(
                        expectedSubjectDigest)
                || !authorization.actionDigest().equals(
                        expectedActionDigest)
                || !authorization.idempotencyKey().equals(
                        expectedIdempotencyKey)) {
            throw new AuthorizationRejectedException(
                    "AUTHORIZATION_REPLAY_CONFLICT");
        }
        GateRevision revision = requireRevision(
                authorization.gateId(), authorization.gateRevision());
        GateSubject subject = requireSubject(revision.subjectManifestRef());
        CiUpdateAction action = requireAction(revision.actionManifestRef());
        ExternalEffectPlan plan = githubEffects.plan(
                authorization.effectPlanRef()).orElseThrow(() ->
                        new IllegalStateException(
                                "GitHub effect plan is missing"));
        List<ExternalEffectStep> steps = githubEffects.steps(plan.planId());
        githubEffects.assertExactPlan(plan, steps);
        ExternalEffectStep step = steps.getFirst();
        Operation operation = runtime.operation(
                authorization.operationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "publication operation is missing"));
        Integer ticketCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_dispatch_ticket "
                        + "WHERE operation_id = ?",
                Integer.class,
                authorization.operationId());
        Integer transitionCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                  AND from_state = 'OPEN' AND to_state = 'AUTHORIZED'
                  AND actor_type = ? AND actor_id = ?
                  AND reason_code = ?
                  AND detail_ref = ?
                """,
                Integer.class,
                authorization.gateId(),
                authorization.gateRevision(),
                authorization.authority().equals("USER")
                        ? "USER" : "PROGRAM",
                authorization.actorId(),
                authorization.authority().equals("USER")
                        ? "MANUAL_AUTHORIZATION"
                        : "CI_UPDATE_CONSENT_AUTHORIZATION",
                authorization.authorizationId());
        assertAuthorizationAuthority(authorization, subject, action);
        boolean dispatchState = switch (operation.state()) {
            case READY, RETRYABLE -> dispatchTicketInState(
                    operation.operationId(), "AVAILABLE");
            case CLAIMED -> dispatchTicketInState(
                    operation.operationId(), "CLAIMED");
            case CANCELED, SUCCEEDED -> dispatchTicketInState(
                    operation.operationId(), "DONE");
            default -> false;
        };
        if (!revision.subjectDigest().equals(subject.subjectDigest())
                || !revision.actionDigest().equals(action.actionDigest())
                || !authorization.prId().equals(subject.prId())
                || !plan.planId().equals(authorization.effectPlanRef())
                || !plan.operationId().equals(authorization.operationId())
                || !plan.authorizationId().equals(
                        authorization.authorizationId())
                || !plan.prId().equals(authorization.prId())
                || !plan.actionRef().equals(action.actionRef())
                || !plan.actionDigest().equals(action.actionDigest())
                || !plan.requiredCiPolicyRevisionId().equals(
                        subject.requiredCiPolicyRevisionId())
                || !plan.headRepositoryExternalId().equals(
                        action.headRepositoryExternalId())
                || !plan.headRepositoryOwner().equals(
                        action.headRepositoryOwner())
                || !plan.headRepositoryName().equals(
                        action.headRepositoryName())
                || !step.headRepositoryExternalId().equals(
                        action.headRepositoryExternalId())
                || !step.headRepositoryOwner().equals(
                        action.headRepositoryOwner())
                || !step.headRepositoryName().equals(
                        action.headRepositoryName())
                || !step.branchRef().equals(action.branchRef())
                || !step.expectedRemoteHead().equals(
                        action.expectedRemoteHead())
                || !step.proposedHead().equals(action.proposedHead())
                || step.forcePush()
                || !operation.ownerKind().equals("GITHUB_EFFECT_PLAN")
                || !operation.ownerId().equals(plan.planId())
                || !operation.taskId().equals(subject.taskId())
                || operation.kind() != OperationKind.PUBLISH
                || !operation.subjectDigest().equals(plan.planDigest())
                || !operation.inputRef().equals(plan.planId())
                || requireNonNull(ticketCount,
                        "publication ticket count is null") != 1
                || requireNonNull(transitionCount,
                        "authorization transition count is null") != 1
                || !dispatchState) {
            throw new IllegalStateException(
                    "authorization graph is internally inconsistent");
        }
        return new AuthorizedCiUpdate(
                authorization,
                plan.planId(),
                operation.operationId(),
                plan.prSequence());
    }

    private static void requireManualAuthorization(
            GateAuthorization authorization)
    {
        if (!authorization.authority().equals("USER")
                || !authorization.actorId().equals(MANUAL_ACTOR)
                || authorization.consentId() != null
                || authorization.consentRevision() != null
                || authorization.consentDigest() != null) {
            throw new AuthorizationRejectedException(
                    "AUTHORIZATION_AUTHORITY_CONFLICT");
        }
    }

    private void assertAuthorizationAuthority(
            GateAuthorization authorization,
            GateSubject subject,
            CiUpdateAction action)
    {
        if (authorization.authority().equals("USER")) {
            requireManualAuthorization(authorization);
            return;
        }
        if (!authorization.authority().equals("CI_UPDATE_CONSENT")
                || !authorization.actorId().equals(CONSENT_ACTOR)
                || authorization.consentId() == null
                || authorization.consentRevision() == null
                || authorization.consentDigest() == null) {
            throw new IllegalStateException(
                    "authorization authority is inconsistent");
        }
        CiUpdateConsentRevision consent = jdbc.query(
                """
                SELECT * FROM flow_user_gate_ci_consent_revision
                WHERE consent_id = ? AND revision = ?
                """,
                (result, row) -> readConsentRevision(result),
                authorization.consentId(),
                authorization.consentRevision()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "authorization consent is missing"));
        assertConsentRevision(consent);
        if (!authorization.consentDigest().equals(
                    consent.revisionDigest())
                || !consent.taskId().equals(subject.taskId())
                || !consent.prId().equals(subject.prId())
                || !consent.repositoryId().equals(subject.repositoryId())
                || !consent.headRepositoryExternalId().equals(
                        subject.headRepositoryExternalId())
                || !consent.headRepositoryOwner().equals(
                        subject.headRepositoryOwner())
                || !consent.headRepositoryName().equals(
                        subject.headRepositoryName())
                || !consent.branchRef().equals(subject.branchRef())
                || !action.headRepositoryExternalId().equals(
                        consent.headRepositoryExternalId())
                || !action.headRepositoryOwner().equals(
                        consent.headRepositoryOwner())
                || !action.headRepositoryName().equals(
                        consent.headRepositoryName())
                || !action.branchRef().equals(consent.branchRef())) {
            throw new IllegalStateException(
                    "authorization consent use is inconsistent");
        }
    }

    private AuthorizedCiUpdate assertStoredAuthorizationGraph(
            GateAuthorization authorization)
    {
        return assertAuthorizationGraph(
                authorization,
                authorization.gateId(),
                authorization.gateRevision(),
                authorization.subjectDigest(),
                authorization.actionDigest(),
                authorization.idempotencyKey());
    }

    private String durableStaleBlocker(
            Claim claim, String resultRef)
    {
        String reasonCode;
        String prefix;
        if (resultRef.startsWith("PUBLISH_STALE:")) {
            prefix = "PUBLISH_STALE:";
            reasonCode = "EFFECT_STALE";
        }
        else if (resultRef.startsWith(
                "PUBLISH_PREPARATION_INVALID:")) {
            prefix = "PUBLISH_PREPARATION_INVALID:";
            reasonCode = "EFFECT_PREPARATION_INVALID";
        }
        else if (resultRef.startsWith("PUBLISH_DIVERGED:")) {
            prefix = "PUBLISH_DIVERGED:";
            reasonCode = "EFFECT_DIVERGED";
        }
        else {
            throw new IllegalStateException(
                    "publication cancellation is not a stable disposition");
        }
        GateAuthorization authorization = authorizationForOperation(
                claim.operationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "publication authorization is missing"));
        assertStoredAuthorizationGraph(authorization);
        String detail = resultRef.substring(prefix.length());
        Integer transition = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                  AND to_state = 'STALE'
                  AND actor_type = 'PROGRAM' AND actor_id = ?
                  AND reason_code = ? AND detail_ref = ?
                  AND sequence = (
                      SELECT MAX(sequence)
                      FROM flow_user_gate_transition
                      WHERE gate_id = ? AND gate_revision = ?
                  )
                """,
                Integer.class,
                authorization.gateId(),
                authorization.gateRevision(),
                claim.operationId(),
                reasonCode,
                detail,
                authorization.gateId(),
                authorization.gateRevision());
        if (detail.isBlank()
                || requireNonNull(transition,
                        "stale transition count is null") != 1) {
            throw new IllegalStateException(
                    "publication stale disposition is inconsistent");
        }
        if (reasonCode.equals("EFFECT_PREPARATION_INVALID")
                && !detail.equals(authorization.effectPlanRef())
                || reasonCode.equals("EFFECT_DIVERGED")
                && githubEffects.probes(authorization.effectPlanRef()).stream()
                        .noneMatch(probe -> probe.probeId().equals(detail)
                                && probe.outcome()
                                        == ProbeOutcome.DIVERGED)) {
            throw new IllegalStateException(
                    "publication stale evidence is inconsistent");
        }
        return detail;
    }

    private boolean dispatchTicketInState(
            String operationId, String deliveryState)
    {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_dispatch_ticket
                WHERE operation_id = ? AND delivery_state = ?
                """,
                Integer.class,
                operationId,
                deliveryState);
        return requireNonNull(count,
                "publication ticket state count is null") == 1;
    }

    private CiUpdateEffectActivation activation(
            GateAuthorization authorization,
            ExternalEffectPlan plan,
            boolean mutationAllowed)
    {
        List<ExternalEffectStep> steps = githubEffects.steps(plan.planId());
        githubEffects.assertExactPlan(plan, steps);
        ExternalEffectStep step = steps.getFirst();
        GateRevision revision = requireRevision(
                authorization.gateId(), authorization.gateRevision());
        GateSubject subject = requireSubject(revision.subjectManifestRef());
        ReviewerRequest reviewer = runtime.reviewerRequest(
                subject.reviewerRequestId()).orElseThrow();
        return new CiUpdateEffectActivation(
                authorization.authorizationId(),
                plan.planId(),
                plan.operationId(),
                plan.prId(),
                plan.prSequence(),
                reviewer.repositoryRoot(),
                step.headRepositoryExternalId(),
                step.headRepositoryOwner(),
                step.headRepositoryName(),
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead(),
                mutationAllowed,
                plan.planDigest());
    }

    private void staleOpenAuthorization(
            String gateId, long revision, String blocker)
    {
        GateState state = currentState(gateId, revision);
        if (state == GateState.STALE) {
            return;
        }
        if (state != GateState.OPEN) {
            throw new AuthorizationRejectedException(
                    "GATE_NOT_OPEN");
        }
        appendTransition(
                gateId,
                revision,
                nextTransitionSequence(gateId),
                GateState.OPEN,
                GateState.STALE,
                "PROGRAM",
                AUTHORIZATION_ACTOR,
                "AUTHORIZATION_STALE",
                blocker,
                clock.instant());
    }

    private String terminalAuthorizationBlocker(
            String gateId, long revision)
    {
        return jdbc.queryForObject(
                """
                SELECT CASE
                    WHEN to_state = 'STALE'
                         AND reason_code = 'AUTHORIZATION_STALE'
                    THEN detail_ref
                    ELSE 'GATE_NOT_OPEN'
                END
                FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                ORDER BY sequence DESC LIMIT 1
                """,
                String.class,
                gateId,
                revision);
    }

    private String authorizationBlocker(
            String gateId,
            long gateRevision,
            GateRevision revision,
            GateSubject subject,
            CiUpdateAction action,
            Inspection inspection)
    {
        UserGate gate = gate(subject.prId()).orElseThrow(() ->
                new IllegalStateException("CI_UPDATE gate is missing"));
        if (!gate.gateId().equals(gateId)
                || gate.currentRevision() != gateRevision
                || !revision.equals(requireRevision(gateId, gateRevision))) {
            return "GATE_REVISION_STALE";
        }
        if (!revision.subjectDigest().equals(subject.subjectDigest())
                || !revision.actionDigest().equals(action.actionDigest())
                || !revision.subjectManifestRef().equals(subject.subjectId())
                || !revision.actionManifestRef().equals(action.actionRef())) {
            throw new IllegalStateException(
                    "gate revision manifests are internally inconsistent");
        }
        if (!subject.localReview().ownerPresent()
                || subject.localReview().bindingId() == null) {
            return "LOCAL_REVIEW_INCOMPLETE";
        }
        assertStoredLocalReviewBinding(subject);
        if (inspection == null || !inspectionMatches(inspection, subject)) {
            return "WORKTREE_SUBJECT_STALE";
        }
        Task task = runtime.task(subject.taskId()).orElseThrow();
        ChangeSetRevision changeSet = runtime.currentChangeSet(
                subject.taskId()).orElseThrow();
        TaskBaseRevision base = runtime.currentBaseRevision(
                subject.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(
                subject.prId()).orElseThrow();
        if (task.status() != TaskStatus.ACTIVE
                || task.selectedWriterOperationId() != null
                || task.waitingMutationStateRef() != null
                || !task.repositoryId().equals(subject.repositoryId())
                || !Objects.equals(task.prId(), subject.prId())
                || !Objects.equals(task.currentChangeSetRevisionId(),
                        subject.changeSetRevisionId())
                || !Objects.equals(task.currentHeadSha(),
                        subject.proposedHead())
                || !Objects.equals(task.currentBaseRevisionId(),
                        subject.baseRevisionId())
                || !Objects.equals(task.currentBaseSha(), subject.baseSha())) {
            return "TASK_SUBJECT_STALE";
        }
        Integer leases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_writer_lease "
                        + "WHERE task_id = ?",
                Integer.class,
                task.taskId());
        Integer reviewers = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_operation
                WHERE task_id = ? AND kind = 'RUN_REVIEWER'
                  AND state IN ('READY', 'CLAIMED', 'WAITING', 'RETRYABLE')
                """,
                Integer.class,
                task.taskId());
        if (requireNonNull(leases, "lease count is null") != 0
                || requireNonNull(reviewers,
                        "reviewer count is null") != 0) {
            return "TASK_AUTHORITY_BUSY";
        }
        if (!changeSet.changeSetRevisionId().equals(
                    subject.changeSetRevisionId())
                || !changeSet.baseRevisionId().equals(subject.baseRevisionId())
                || !changeSet.baseSha().equals(subject.baseSha())
                || !changeSet.headSha().equals(subject.proposedHead())
                || !changeSet.headTreeDigest().equals(
                        subject.headTreeDigest())
                || !changeSet.diffDigest().equals(subject.diffDigest())
                || !base.baseRevisionId().equals(subject.baseRevisionId())
                || !base.baseSha().equals(subject.baseSha())) {
            return "CHANGE_SET_STALE";
        }
        if (!pr.prId().equals(subject.prId())
                || !pr.taskId().equals(subject.taskId())
                || !pr.repositoryId().equals(subject.repositoryId())
                || !pr.branchName().equals(task.branchName())
                || !subject.branchRef().equals(
                        "refs/heads/" + task.branchName())
                || !Objects.equals(pr.currentRemoteHead(),
                        subject.expectedRemoteHead())
                || subject.expectedRemoteHead().equals(subject.proposedHead())) {
            return "REMOTE_SUBJECT_STALE";
        }
        try {
            runtime.assertAndLockCiUpdatePr(pr);
        }
        catch (FlowRuntime.StaleOwnerRevisionException stale) {
            return "REMOTE_SUBJECT_STALE";
        }
        if (!action.branchRef().equals(subject.branchRef())
                || !action.headRepositoryExternalId().equals(
                        subject.headRepositoryExternalId())
                || !action.headRepositoryOwner().equals(
                        subject.headRepositoryOwner())
                || !action.headRepositoryName().equals(
                        subject.headRepositoryName())
                || !action.expectedRemoteHead().equals(
                        subject.expectedRemoteHead())
                || !action.proposedHead().equals(subject.proposedHead())
                || action.forcePush()) {
            throw new IllegalStateException(
                    "CI_UPDATE action does not match its subject");
        }
        ReviewerRequest request = runtime.reviewerRequest(
                subject.reviewerRequestId()).orElseThrow();
        AgentRun reviewerRun = runtime.run(
                subject.reviewerRunId()).orElseThrow();
        AgentResult reviewerResult = runtime.resultForRun(
                reviewerRun.runId()).orElseThrow();
        AgentRun readyRun = runtime.run(
                subject.createdByRunId()).orElseThrow();
        PendingWork readyInput = runtime.pendingWork(subject.taskId()).stream()
                .filter(item -> Objects.equals(
                        item.selectedByOperationId(), readyRun.operationId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ready input is missing"));
        List<String> subjectCheckRefs = subject.localChecks().stream()
                .map(LocalCheckBinding::checkRunId)
                .toList();
        if (!request.taskId().equals(subject.taskId())
                || !request.changeSetRevisionId().equals(
                        subject.changeSetRevisionId())
                || !request.reviewedHeadSha().equals(subject.proposedHead())
                || !request.baseHeadSha().equals(subject.baseSha())
                || !request.headTreeDigest().equals(subject.headTreeDigest())
                || !request.diffDigest().equals(subject.diffDigest())
                || !request.remoteHeadSha().equals(
                        subject.expectedRemoteHead())
                || !request.localCheckPolicyRevisionId().equals(
                        subject.localCheckPolicyRevisionId())
                || !request.checkRunRefs().equals(subjectCheckRefs)
                || !request.originCiFixPendingId().equals(
                        subject.originCiFixPendingId())
                || !request.originCiFixSourceKind().equals(
                        subject.originCiFixSourceKind())
                || !request.originCiFixSourceId().equals(
                        subject.originCiFixSourceId())
                || !request.reviewerOperationId().equals(
                        reviewerRun.operationId())
                || !runtime.reviewerRequestForReviewerRun(
                        reviewerRun.runId()).orElseThrow().equals(request)
                || reviewerRun.state() != RunState.COMPLETED
                || !reviewerResult.resultId().equals(
                        subject.reviewerResultId())
                || reviewerResult.terminalOutcome()
                        != TerminalOutcome.COMPLETED
                || readyRun.state() != RunState.COMPLETED
                || readyRun.wakeKind() != WakeKind.AGENT_RESULT_READY
                || readyInput.kind() != PendingKind.AGENT_RESULT_READY
                || !readyInput.externalKey().equals(reviewerRun.runId())
                || !Objects.equals(
                        readyInput.agentResultId(), reviewerResult.resultId())
                || !readyInput.payloadRef().equals(reviewerResultPayload(
                        request.requestId(), reviewerResult.resultId()))) {
            return "EXACT_HEAD_REVIEW_STALE";
        }
        try {
            LocalChecks.ReviewerEvidence checks =
                    localChecks.reviewerEvidence(
                            subject.taskId(),
                            subject.changeSetRevisionId(),
                            GateIntent.CI_UPDATE);
            checks.assertCurrentForReservation();
            LocalCheckEvidence evidence = checks.evidence();
            List<LocalCheckBinding> bindings = evidence.runs().stream()
                    .map(check -> new LocalCheckBinding(
                            check.checkRunId(),
                            check.profileId(),
                            check.conclusion()))
                    .toList();
            if (!evidence.policyRevisionId().equals(
                        subject.localCheckPolicyRevisionId())
                    || !bindings.equals(subject.localChecks())) {
                return "LOCAL_CHECK_EVIDENCE_STALE";
            }
        }
        catch (IllegalStateException stale) {
            return "LOCAL_CHECK_EVIDENCE_STALE";
        }
        try {
            CiUpdateGateEvidence ci = autofix.ciUpdateGateEvidence(
                    subject.originCiFixSourceKind(),
                    subject.originCiFixSourceId());
            if (!ci.taskId().equals(subject.taskId())
                    || !ci.prId().equals(subject.prId())
                    || !ci.roundId().equals(subject.ciRoundId())
                    || !ci.remoteHead().equals(subject.expectedRemoteHead())
                    || !ci.requiredCiPolicyRevisionId().equals(
                            subject.requiredCiPolicyRevisionId())
                    || ci.evidenceRevision() != subject.ciEvidenceRevision()
                    || !ci.checkObservationIds().equals(
                            subject.ciObservationIds())
                    || !ci.failedLogRefs().equals(subject.failedLogRefs())
                    || !ci.outputChangeSetRevisionId().equals(
                            subject.changeSetRevisionId())
                    || !ci.outputHead().equals(subject.proposedHead())
                    || !ci.repairAttemptId().equals(
                            subject.repairAttemptId())
                    || !ci.repairResultId().equals(subject.repairResultId())
                    || !Objects.equals(ci.cleanupId(), subject.cleanupId())
                    || !Objects.equals(
                            ci.cleanupResultId(), subject.cleanupResultId())) {
                return "CI_FIX_SOURCE_STALE";
            }
        }
        catch (IllegalStateException stale) {
            return "CI_FIX_SOURCE_STALE";
        }
        return null;
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
        appendTransition(
                gateId,
                revision,
                sequence,
                from,
                to,
                "PROGRAM",
                READY_ACTOR,
                reason,
                detail,
                now);
    }

    private void appendTransition(
            String gateId,
            long revision,
            long sequence,
            GateState from,
            GateState to,
            String actorType,
            String actorId,
            String reason,
            String detail,
            Instant now)
    {
        jdbc.update(
                """
                INSERT INTO flow_user_gate_transition (
                    gate_id, gate_revision, sequence, from_state, to_state,
                    actor_type, actor_id, reason_code, detail_ref, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                gateId,
                revision,
                sequence,
                from == null ? null : from.name(),
                to.name(),
                actorType,
                actorId,
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
                pr.headRepositoryExternalId(),
                pr.headRepositoryOwner(),
                pr.headRepositoryName(),
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
                "local-review-owner:true",
                localReview.bindingId(),
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

    private static String initialSubjectDigest(
            Task task, PullRequestSubject pr, ChangeSetRevision changeSet,
            TaskBaseRevision base, PrDraftRevision draft,
            LocalCheckEvidence checks, ReviewerRequest reviewer,
            AgentRun reviewerRun, AgentResult reviewerResult,
            CodePublicationReviewBinding localReview, TargetSnapshot target)
    {
        List<String> fields = new ArrayList<>(List.of("initial-publish-subject:v1",
                task.taskId(), pr.prId(), task.repositoryId(), task.launchDigest(),
                changeSet.changeSetRevisionId(), base.baseRevisionId(), base.baseSha(),
                changeSet.headSha(), changeSet.headTreeDigest(), changeSet.diffDigest(),
                draft.draftRevisionId(), draft.draftDigest(), checks.policyRevisionId(),
                reviewer.requestId(), reviewerRun.runId(), reviewerResult.resultId(),
                localReview.bindingId(), localReview.digest(),
                target.targetSnapshotId(), target.targetSnapshotDigest(),
                target.requiredCiPolicyRevisionId()));
        checks.runs().forEach(check -> fields.add("check:" + check.checkRunId()
                + ":" + check.profileId() + ":" + check.conclusion()));
        return stableId(fields.toArray(String[]::new));
    }

    private static String initialSubjectDigest(InitialPublishSubject subject)
    {
        List<String> fields = new ArrayList<>(List.of("initial-publish-subject:v1",
                subject.taskId(), subject.prId(), subject.repositoryId(),
                subject.launchDigest(), subject.changeSetRevisionId(),
                subject.baseRevisionId(), subject.expectedBaseSha(),
                subject.proposedHead(), subject.headTreeDigest(), subject.diffDigest(),
                subject.draftRevisionId(), subject.draftDigest(),
                subject.localCheckPolicyRevisionId(), subject.reviewerRequestId(),
                subject.reviewerRunId(), subject.reviewerResultId(),
                subject.localReview().bindingId(), subject.localReview().digest(),
                subject.targetSnapshotId(), subject.targetSnapshotDigest(),
                subject.requiredCiPolicyRevisionId()));
        subject.localChecks().forEach(check -> fields.add("check:"
                + check.checkRunId() + ":" + check.profileId() + ":"
                + check.conclusion()));
        return stableId(fields.toArray(String[]::new));
    }

    private static String initialActionDigest(InitialPublishSubject subject,
            String readyPolicy)
    {
        return stableId("initial-publish-action:v1", subject.prId(),
                subject.changeSetRevisionId(), subject.baseRepositoryExternalId(),
                subject.baseRepositoryOwner(), subject.baseRepositoryName(),
                subject.headRepositoryExternalId(), subject.headRepositoryOwner(),
                subject.headRepositoryName(), subject.branchRef(),
                subject.targetBaseRef(), subject.expectedBaseSha(),
                subject.proposedHead(), subject.draftRevisionId(),
                subject.draftDigest(), subject.requiredCiPolicyRevisionId(),
                readyPolicy, subject.targetSnapshotId(),
                subject.targetSnapshotDigest());
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

    private static boolean sameLocalReviewBinding(
            LocalReviewBinding left, LocalReviewBinding right)
    {
        return left.bindingId().equals(right.bindingId())
                && left.prId().equals(right.prId())
                && left.candidateChangeSetRevisionId().equals(
                        right.candidateChangeSetRevisionId())
                && left.digest().equals(right.digest());
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

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
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

    private record GateBundle(
            LocalReviewBinding localReviewBinding,
            GateSubject subject,
            CiUpdateAction action) {}

}
