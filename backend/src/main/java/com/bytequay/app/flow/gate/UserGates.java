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
import com.bytequay.app.flow.gate.UserGateRecords.AuthorizedCiUpdate;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateAction;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGateRecords.CodePublicationReviewBinding;
import com.bytequay.app.flow.gate.UserGateRecords.GateAuthorization;
import com.bytequay.app.flow.gate.UserGateRecords.GateKind;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGateRecords.GateState;
import com.bytequay.app.flow.gate.UserGateRecords.GateSubject;
import com.bytequay.app.flow.gate.UserGateRecords.GateTransition;
import com.bytequay.app.flow.gate.UserGateRecords.LocalCheckBinding;
import com.bytequay.app.flow.gate.UserGateRecords.LocalReviewBinding;
import com.bytequay.app.flow.gate.UserGateRecords.ReadyForReviewAcceptance;
import com.bytequay.app.flow.gate.UserGateRecords.UserGate;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.github.GitHubEffects.PreparedCiUpdatePlan;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
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
    private static final String AUTHORIZATION_ACTOR =
            "USER_GATES_AUTHORIZATION";
    private static final String MANUAL_ACTOR = "LOCAL_DESKTOP_USER";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FlowRuntime runtime;
    private final LocalChecks localChecks;
    private final CiAutofix autofix;
    private final GitHubEffects githubEffects;
    private final Clock clock;
    private final FlowWorktreeInspector worktreeInspector =
            new FlowWorktreeInspector();

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

    public static final class DurableStaleEffectException
            extends IllegalStateException
    {
        private DurableStaleEffectException(String reasonCode)
        {
            super(reasonCode);
        }
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

    private record AuthorityInspection(
            Inspection inspection, String blockerCode) {}

    private record AuthorizationOutcome(
            AuthorizedCiUpdate authorized, String blockerCode) {}

    private record BeginOutcome(
            CiUpdateEffectActivation activation, String blockerCode) {}

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
            return assertAuthorizationGraph(
                    replay.orElseThrow(), gateId, gateRevision,
                    expectedSubjectDigest, expectedActionDigest,
                    idempotencyKey);
        }
        Optional<GateAuthorization> revisionReplay = authorizationForRevision(
                gateId, gateRevision);
        if (revisionReplay.isPresent()) {
            GateAuthorization existing = revisionReplay.orElseThrow();
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
            Instant now = clock.instant();
            String authorizationId = stableId(
                    "gate-authorization:v1",
                    gateId,
                    Long.toString(gateRevision),
                    expectedSubjectDigest,
                    expectedActionDigest);
            String operationId = stableId(
                    "publish-operation:v1", authorizationId);
            PreparedCiUpdatePlan prepared = githubEffects.prepareCiUpdatePlan(
                    authorizationId,
                    operationId,
                    subject.prId(),
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
            GateAuthorization authorization = new GateAuthorization(
                    authorizationId,
                    gateId,
                    gateRevision,
                    subject.prId(),
                    subject.subjectDigest(),
                    action.actionDigest(),
                    "USER",
                    MANUAL_ACTOR,
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
                    "USER",
                    MANUAL_ACTOR,
                    "MANUAL_AUTHORIZATION",
                    authorizationId,
                    now);
            return new AuthorizationOutcome(
                    assertAuthorizationGraph(
                            authorization, gateId, gateRevision,
                            expectedSubjectDigest, expectedActionDigest,
                            idempotencyKey),
                    null);
        });
        if (outcome.blockerCode() != null) {
            throw new AuthorizationRejectedException(outcome.blockerCode());
        }
        return requireNonNull(outcome.authorized(),
                "authorization result is null");
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
        if (state == GateState.EXECUTING) {
            return activation(authorization, plan);
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
            if (current == GateState.EXECUTING) {
                return new BeginOutcome(
                        activation(authorization, plan), null);
            }
            if (current != GateState.AUTHORIZED) {
                throw new IllegalStateException(
                        "authorization changed before effect begin");
            }
            String blocker = inspected.blockerCode();
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
                appendTransition(
                        authorization.gateId(),
                        authorization.gateRevision(),
                        nextTransitionSequence(authorization.gateId()),
                        GateState.AUTHORIZED,
                        GateState.STALE,
                        "PROGRAM",
                        claim.operationId(),
                        "EFFECT_STALE",
                        blocker,
                        clock.instant());
                runtime.cancelClaimedPublish(
                        claim, "PUBLISH_STALE:" + blocker);
                return new BeginOutcome(null, blocker);
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
                    activation(authorization, plan), null);
        });
        if (outcome.blockerCode() != null) {
            throw new DurableStaleEffectException(outcome.blockerCode());
        }
        return requireNonNull(outcome.activation(),
                "effect activation is null");
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
                localReviewBinding,
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
            if (currentState != GateState.OPEN
                    && currentState != GateState.STALE) {
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
                    local_review_binding_id,
                    local_review_batch_refs_json,
                    local_review_revision_refs_json, local_review_digest,
                    ci_memory_refs_json, manual_only, created_by_run_id,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, '[]', '[]', ?,
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
        CodePublicationReviewBinding review = subject.localReview();
        if (!review.ownerPresent()) {
            return;
        }
        LocalReviewBinding binding = localReviewBinding(review.bindingId())
                .orElseThrow(() -> new IllegalStateException(
                        "local-review binding is missing"));
        String expectedId = stableId(
                "local-review-binding-id:v1",
                subject.prId(),
                subject.changeSetRevisionId());
        String expectedDigest = stableId(
                "local-review-binding:v1",
                subject.prId(),
                subject.changeSetRevisionId(),
                "batch-count:0",
                "revision-count:0");
        if (!binding.bindingId().equals(expectedId)
                || !binding.prId().equals(subject.prId())
                || !binding.candidateChangeSetRevisionId().equals(
                        subject.changeSetRevisionId())
                || !binding.digest().equals(expectedDigest)
                || !review.digest().equals(expectedDigest)) {
            throw new IllegalStateException(
                    "local-review binding does not match gate subject");
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
                    idempotency_key, operation_id, effect_plan_ref,
                    authorized_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?)
                """,
                authorization.authorizationId(),
                authorization.gateId(),
                authorization.gateRevision(),
                authorization.prId(),
                authorization.subjectDigest(),
                authorization.actionDigest(),
                authorization.actorId(),
                authorization.idempotencyKey(),
                authorization.operationId(),
                authorization.effectPlanRef(),
                authorization.authorizedAt().toEpochMilli());
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
                || !authorization.authority().equals("USER")
                || !authorization.actorId().equals(MANUAL_ACTOR)
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
                  AND actor_type = 'USER' AND actor_id = ?
                  AND reason_code = 'MANUAL_AUTHORIZATION'
                  AND detail_ref = ?
                """,
                Integer.class,
                authorization.gateId(),
                authorization.gateRevision(),
                MANUAL_ACTOR,
                authorization.authorizationId());
        boolean dispatchState = switch (operation.state()) {
            case READY -> dispatchTicketInState(
                    operation.operationId(), "AVAILABLE");
            case CLAIMED -> dispatchTicketInState(
                    operation.operationId(), "CLAIMED");
            case CANCELED -> dispatchTicketInState(
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
        String prefix = "PUBLISH_STALE:";
        if (!resultRef.startsWith(prefix)) {
            throw new IllegalStateException(
                    "publication cancellation is not a stable disposition");
        }
        GateAuthorization authorization = authorizationForOperation(
                claim.operationId()).orElseThrow(() ->
                        new IllegalStateException(
                                "publication authorization is missing"));
        assertStoredAuthorizationGraph(authorization);
        String blocker = resultRef.substring(prefix.length());
        Integer transition = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_user_gate_transition
                WHERE gate_id = ? AND gate_revision = ?
                  AND from_state = 'AUTHORIZED' AND to_state = 'STALE'
                  AND actor_type = 'PROGRAM' AND actor_id = ?
                  AND reason_code = 'EFFECT_STALE' AND detail_ref = ?
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
                blocker,
                authorization.gateId(),
                authorization.gateRevision());
        if (blocker.isBlank()
                || requireNonNull(transition,
                        "stale transition count is null") != 1) {
            throw new IllegalStateException(
                    "publication stale disposition is inconsistent");
        }
        return blocker;
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
            GateAuthorization authorization, ExternalEffectPlan plan)
    {
        List<ExternalEffectStep> steps = githubEffects.steps(plan.planId());
        githubEffects.assertExactPlan(plan, steps);
        ExternalEffectStep step = steps.getFirst();
        return new CiUpdateEffectActivation(
                authorization.authorizationId(),
                plan.planId(),
                plan.operationId(),
                plan.prId(),
                plan.prSequence(),
                step.branchRef(),
                step.expectedRemoteHead(),
                step.proposedHead(),
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
