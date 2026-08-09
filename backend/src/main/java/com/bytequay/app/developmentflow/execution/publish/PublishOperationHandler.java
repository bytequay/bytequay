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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.GITHUB;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.GITHUB_EFFECT;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Runs the fixed six-step V2 publish saga without changing Stage or Task state. */
public final class PublishOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "PUBLISH_LOCAL_DEVELOPMENT";
    public static final String CALLBACK_ROUTE = "STAGE_PUBLISH_RESULT";

    private static final int PAYLOAD_VERSION = 1;

    private final OperationStore operations;
    private final PublishEffects effects;
    private final WorktreeWriterLeaseManager writers;
    private final ObjectMapper json;
    private final Clock clock;

    public PublishOperationHandler(
            OperationStore operations,
            PublishEffects effects,
            WorktreeWriterLeaseManager writers,
            ObjectMapper json,
            Clock clock)
    {
        this.operations = requireNonNull(operations, "operations is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
            throws Exception
    {
        return publish(context);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
            throws Exception
    {
        // Every external effect has its own committed claim. An expired
        // claim is admitted below only in PROBE mode.
        return publish(context);
    }

    private DispatchTicket.DispatchResult publish(ExecutionContext context)
            throws Exception
    {
        requireNonNull(context, "context is null");
        OperationSnapshot snapshot = operations.requireByOperationId(
                context.envelope().fence().operationId());
        validate(context.envelope(), snapshot.request());
        WorktreeWriterLeaseManager.Lease writer = writers.acquire(
                context, snapshot.request().worktreePath());

        while (true) {
            snapshot = operations.requireByOperationId(
                    context.envelope().fence().operationId());
            Optional<EffectStep> pending = snapshot.firstIncompleteStep();
            if (pending.isEmpty()) {
                return success(context.envelope(), snapshot);
            }
            if (context.isCancellationRequested()) {
                return canceled(context.envelope(), snapshot,
                        "publish canceled before the next effect claim");
            }

            EffectStep step = pending.orElseThrow();
            Instant now = clock.instant();
            ClaimMode mode = claimMode(step, now);
            if (mode == ClaimMode.EXECUTE
                    && step.attemptCount() >= step.attemptLimit()) {
                return failed(context.envelope(), snapshot, step,
                        "publish effect execution budget exhausted");
            }
            Instant leaseUntil = context.capacityLease().expiresAt();
            if (!leaseUntil.isAfter(now)) {
                throw new ExecutionPorts.RetryableExecutionException(
                        "publish capacity lease expired before effect claim");
            }
            EffectClaim claim = operations.tryClaim(
                            step, mode, context.executionId(), now, leaseUntil)
                    .orElseThrow(() -> new ExecutionPorts.RetryableExecutionException(
                            "publish effect claim changed concurrently"));
            try {
                EffectEvidence evidence = perform(
                        context, writer, snapshot, claim);
                finish(claim, StepStatus.SUCCEEDED, evidence, null);
            }
            catch (MissingEffectException missing) {
                finish(claim, StepStatus.FAILED, null, missing.getMessage());
                if (claim.attempt() < claim.attemptLimit()) {
                    throw new ExecutionPorts.RetryableExecutionException(
                            missing.getMessage(), missing);
                }
                return failed(context.envelope(),
                        operations.requireByOperationId(
                                context.envelope().fence().operationId()),
                        claim.step(), "publish effect was not observed and its execution budget is exhausted");
            }
            catch (BaseMovedException moved) {
                finish(claim, StepStatus.FAILED, null, moved.getMessage());
                return baseMoved(context.envelope(),
                        operations.requireByOperationId(
                                context.envelope().fence().operationId()),
                        claim.step(), moved);
            }
            catch (SubjectRejectedException rejected) {
                finish(claim, StepStatus.FAILED, null, rejected.getMessage());
                return failed(context.envelope(),
                        operations.requireByOperationId(
                                context.envelope().fence().operationId()),
                        claim.step(), rejected.getMessage());
            }
            catch (RetryableEffectException retryable) {
                finish(claim, StepStatus.FAILED, null, retryable.getMessage());
                if (context.isCancellationRequested()) {
                    return canceled(context.envelope(),
                            operations.requireByOperationId(
                                    context.envelope().fence().operationId()),
                            retryable.getMessage());
                }
                if (claim.attempt() >= claim.attemptLimit()) {
                    return failed(context.envelope(),
                            operations.requireByOperationId(
                                    context.envelope().fence().operationId()),
                            claim.step(), "publish effect execution budget exhausted: "
                                    + retryable.getMessage());
                }
                throw new ExecutionPorts.RetryableExecutionException(
                        retryable.getMessage(), retryable);
            }
            catch (AmbiguousEffectException ambiguous) {
                finish(claim, StepStatus.INDETERMINATE, null, ambiguous.getMessage());
                throw new ExecutionPorts.IndeterminateExecutionException(
                        ambiguous.getMessage(), ambiguous);
            }
            catch (RuntimeException unexpected) {
                StepStatus status = claim.step().kind().mayMutateExternally()
                        ? StepStatus.INDETERMINATE : StepStatus.FAILED;
                finish(claim, status, null,
                        "unexpected publish effect failure: " + unexpected.getMessage());
                if (status == StepStatus.INDETERMINATE) {
                    throw new ExecutionPorts.IndeterminateExecutionException(
                            "publish effect outcome is unknown", unexpected);
                }
                throw new ExecutionPorts.RetryableExecutionException(
                        "publish effect failed before an external mutation", unexpected);
            }
        }
    }

    private EffectEvidence perform(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease writer,
            OperationSnapshot snapshot,
            EffectClaim claim)
    {
        PublishRequest request = snapshot.request();
        return switch (claim.step().kind()) {
            case VERIFY_SUBJECT -> effects.verifySubject(request);
            case RECONCILE_BRANCH_BASE -> effects.verifyBranchBase(request);
            case PUSH_BRANCH -> claim.mode() == ClaimMode.PROBE
                    ? effects.probePushedBranch(request)
                    : mutate(context, writer,
                            fence -> effects.pushOrAdoptBranch(request, fence));
            case CREATE_OR_ADOPT_DRAFT_PR -> claim.mode() == ClaimMode.PROBE
                    ? effects.probeDraftPullRequest(request)
                    : mutate(context, writer,
                            fence -> effects.createOrAdoptDraftPullRequest(
                                    request, fence));
            case FETCH_REMOTE_DETAIL -> effects.fetchRemoteDetail(
                    request, requireRemoteReference(snapshot));
            case PROVE_REMOTE_HEAD -> effects.proveRemoteHead(
                    request, requireRemoteReference(snapshot));
        };
    }

    private <T> T mutate(
            ExecutionContext context,
            WorktreeWriterLeaseManager.Lease writer,
            Function<WorktreeWriterLeaseManager.MutationFence, T> mutation)
    {
        return writers.authorizeMutation(context, writer).run(mutation);
    }

    private RemoteReference requireRemoteReference(OperationSnapshot snapshot)
    {
        EffectStep creation = snapshot.steps().stream()
                .filter(step -> step.kind() == EffectKind.CREATE_OR_ADOPT_DRAFT_PR)
                .findFirst()
                .orElseThrow();
        if (creation.status() != StepStatus.SUCCEEDED
                || creation.evidenceJson() == null) {
            throw new IllegalStateException(
                    "remote detail requires successful PR identity evidence");
        }
        try {
            EffectEvidence evidence = json.readValue(
                    creation.evidenceJson(), EffectEvidence.class);
            if (evidence.kind() != EffectKind.CREATE_OR_ADOPT_DRAFT_PR
                    || evidence.remote() == null) {
                throw new IllegalStateException(
                        "PR creation evidence has the wrong typed subject");
            }
            return evidence.remote();
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "PR creation evidence is not valid typed JSON", e);
        }
    }

    private void finish(
            EffectClaim claim,
            StepStatus status,
            EffectEvidence evidence,
            String error)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        String evidenceJson = evidence == null ? null : json(evidence);
        if (!operations.finish(
                claim, status, evidenceJson, error, clock.instant())) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "publish effect claim changed before its result was persisted");
        }
    }

    private DispatchTicket.DispatchResult success(
            DispatchTicket.DispatchEnvelope envelope,
            OperationSnapshot snapshot)
    {
        EffectStep proofStep = snapshot.steps().stream()
                .filter(step -> step.kind() == EffectKind.PROVE_REMOTE_HEAD)
                .findFirst()
                .orElseThrow();
        EffectEvidence proof;
        try {
            proof = json.readValue(proofStep.evidenceJson(), EffectEvidence.class);
        }
        catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalStateException(
                    "successful publish has invalid remote-head evidence", failure);
        }
        RemoteReference remote = requireNonNull(
                proof.remote(), "successful publish remote proof is null");
        if (remote.headSha() == null || remote.baseSha() == null) {
            throw new IllegalStateException(
                    "successful publish lacks exact head/base proof");
        }
        PublishRawResult payload = new PublishRawResult(
                PAYLOAD_VERSION,
                snapshot.request().publishOperationId(),
                snapshot.request().operationId(),
                snapshot.request().taskId(),
                snapshot.request().stageId(),
                Disposition.PUBLISHED,
                remote,
                null,
                null);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), SUCCEEDED, json(payload),
                json(resultEvidence(snapshot, Disposition.PUBLISHED, null)), null);
    }

    private DispatchTicket.DispatchResult failed(
            DispatchTicket.DispatchEnvelope envelope,
            OperationSnapshot snapshot,
            EffectStep step,
            String error)
    {
        PublishRawResult payload = new PublishRawResult(
                PAYLOAD_VERSION,
                snapshot.request().publishOperationId(),
                snapshot.request().operationId(),
                snapshot.request().taskId(),
                snapshot.request().stageId(),
                Disposition.FAILED,
                null,
                error,
                null);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), FAILED, json(payload),
                json(resultEvidence(snapshot, Disposition.FAILED,
                        step == null ? null : step.kind())), error);
    }

    private DispatchTicket.DispatchResult baseMoved(
            DispatchTicket.DispatchEnvelope envelope,
            OperationSnapshot snapshot,
            EffectStep step,
            BaseMovedException moved)
    {
        PublishRawResult payload = new PublishRawResult(
                PAYLOAD_VERSION,
                snapshot.request().publishOperationId(),
                snapshot.request().operationId(),
                snapshot.request().taskId(),
                snapshot.request().stageId(),
                Disposition.BASE_MOVED,
                null,
                moved.getMessage(),
                moved.observedBaseSha());
        return new DispatchTicket.DispatchResult(
                envelope.fence(), FAILED, json(payload),
                json(resultEvidence(snapshot, Disposition.BASE_MOVED,
                        step.kind())), moved.getMessage());
    }

    private DispatchTicket.DispatchResult canceled(
            DispatchTicket.DispatchEnvelope envelope,
            OperationSnapshot snapshot,
            String error)
    {
        PublishRawResult payload = new PublishRawResult(
                PAYLOAD_VERSION,
                snapshot.request().publishOperationId(),
                snapshot.request().operationId(),
                snapshot.request().taskId(),
                snapshot.request().stageId(),
                Disposition.CANCELED,
                null,
                error,
                null);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), CANCELED, json(payload),
                json(resultEvidence(snapshot, Disposition.CANCELED, null)), error);
    }

    private PublishResultEvidence resultEvidence(
            OperationSnapshot snapshot,
            Disposition disposition,
            EffectKind failedStep)
    {
        return new PublishResultEvidence(
                PAYLOAD_VERSION,
                snapshot.request().publishOperationId(),
                snapshot.request().operationId(),
                snapshot.request().codeFingerprint(),
                snapshot.request().expectedHeadSha(),
                snapshot.request().expectedBaseSha(),
                disposition,
                failedStep,
                snapshot.steps().stream()
                        .map(step -> new StepProof(
                                step.kind(), step.status(), step.attemptCount(),
                                step.evidenceJson()))
                        .toList());
    }

    private static ClaimMode claimMode(EffectStep step, Instant now)
            throws ExecutionPorts.RetryableExecutionException
    {
        return switch (step.status()) {
            case REQUESTED, FAILED -> ClaimMode.EXECUTE;
            case INDETERMINATE -> ClaimMode.PROBE;
            case CLAIMED -> {
                if (step.leaseUntil() != null && step.leaseUntil().isAfter(now)) {
                    throw new ExecutionPorts.RetryableExecutionException(
                            "publish effect is held by a live claim");
                }
                yield ClaimMode.PROBE;
            }
            case SUCCEEDED -> throw new IllegalStateException(
                    "successful publish step cannot be claimed");
        };
    }

    private static void validate(
            DispatchTicket.DispatchEnvelope envelope,
            PublishRequest request)
            throws ExecutionPorts.IndeterminateExecutionException
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        CapacityManager.CapacityScope scope = capacity.scope();
        boolean exact = OPERATION_KIND.equals(envelope.operationKind())
                && envelope.family() == GITHUB_EFFECT
                && envelope.owner().kind() == STAGE
                && envelope.owner().id().equals(request.stageId())
                && CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                && fence.taskEpoch() != null
                && fence.taskEpoch() == request.taskEpoch()
                && request.stageId().equals(fence.stageId())
                && fence.stageGeneration() != null
                && fence.stageGeneration() == request.stageGeneration()
                && fence.operationId().equals(request.operationId())
                && fence.attempt() == request.attempt()
                && request.codeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                && request.expectedHeadSha().equals(fence.expectedHeadSha())
                && request.expectedBaseSha().equals(fence.expectedBaseSha())
                && capacity.source() == V2
                && capacity.lanes().equals(ImmutableSet.of(LOCAL_GIT, GITHUB))
                && !capacity.trunkControl()
                && capacity.exclusiveTask()
                && capacity.writerRequired()
                && request.workspaceId().equals(scope.workspaceId())
                && request.trunkId().equals(scope.trunkId())
                && request.taskId().equals(scope.taskId())
                && scope.taskEpoch() != null
                && scope.taskEpoch() == request.taskEpoch()
                && "V2".equals(request.workflowVersion())
                && "ACTIVE".equals(request.taskLifecycle())
                && request.currentTaskEpoch() == request.taskEpoch()
                && request.stageId().equals(request.currentStageId())
                && request.currentStageGeneration() == request.stageGeneration()
                && "PUBLISHING".equals(request.stageCheckpoint())
                && "DISPATCHED".equals(request.operationStatus())
                && request.authorizationActive();
        if (!exact) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "publish envelope or persisted owner fence is stale");
        }
    }

    private String json(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serializing typed publish evidence failed", e);
        }
    }

    public interface OperationStore
    {
        OperationSnapshot requireByOperationId(String operationId);

        Optional<EffectClaim> tryClaim(
                EffectStep step,
                ClaimMode mode,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil);

        boolean finish(
                EffectClaim claim,
                StepStatus status,
                String evidenceJson,
                String error,
                Instant completedAt);
    }

    public interface PublishEffects
    {
        EffectEvidence verifySubject(PublishRequest request);

        EffectEvidence verifyBranchBase(PublishRequest request);

        EffectEvidence probePushedBranch(PublishRequest request);

        EffectEvidence pushOrAdoptBranch(
                PublishRequest request,
                WorktreeWriterLeaseManager.MutationFence fence);

        EffectEvidence probeDraftPullRequest(PublishRequest request);

        EffectEvidence createOrAdoptDraftPullRequest(
                PublishRequest request,
                WorktreeWriterLeaseManager.MutationFence fence);

        EffectEvidence fetchRemoteDetail(
                PublishRequest request, RemoteReference remote);

        EffectEvidence proveRemoteHead(
                PublishRequest request, RemoteReference remote);
    }

    public enum EffectKind
    {
        VERIFY_SUBJECT,
        RECONCILE_BRANCH_BASE,
        PUSH_BRANCH,
        CREATE_OR_ADOPT_DRAFT_PR,
        FETCH_REMOTE_DETAIL,
        PROVE_REMOTE_HEAD;

        boolean mayMutateExternally()
        {
            return this == PUSH_BRANCH || this == CREATE_OR_ADOPT_DRAFT_PR;
        }
    }

    public enum StepStatus
    {
        REQUESTED,
        CLAIMED,
        SUCCEEDED,
        FAILED,
        INDETERMINATE
    }

    public enum ClaimMode
    {
        EXECUTE,
        PROBE
    }

    public enum Route
    {
        DIRECT,
        FORK
    }

    public enum Disposition
    {
        PUBLISHED,
        BASE_MOVED,
        FAILED,
        CANCELED
    }

    public record PublishRequest(
            String publishOperationId,
            String operationId,
            String stageId,
            String taskId,
            String trunkId,
            String workspaceId,
            long taskEpoch,
            long stageGeneration,
            int attempt,
            String operationStatus,
            String workflowVersion,
            String taskLifecycle,
            long currentTaskEpoch,
            String currentStageId,
            long currentStageGeneration,
            String stageCheckpoint,
            boolean authorizationActive,
            String manifestId,
            String prId,
            String codeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            Route route,
            String baseRepositoryId,
            String headRepositoryId,
            String publishRepositoryId,
            String branchName,
            String headRef,
            String baseBranch,
            String prTitle,
            String prBody,
            int prContentRevision,
            String prContentDigest,
            String worktreePath)
    {
        public PublishRequest
        {
            requireText(publishOperationId, "publishOperationId");
            requireText(operationId, "operationId");
            requireText(stageId, "stageId");
            requireText(taskId, "taskId");
            requireText(trunkId, "trunkId");
            requireText(workspaceId, "workspaceId");
            requireText(operationStatus, "operationStatus");
            requireText(workflowVersion, "workflowVersion");
            requireText(taskLifecycle, "taskLifecycle");
            requireText(currentStageId, "currentStageId");
            requireText(stageCheckpoint, "stageCheckpoint");
            requireText(manifestId, "manifestId");
            requireText(prId, "prId");
            requireText(codeFingerprint, "codeFingerprint");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireNonNull(route, "route is null");
            requireText(baseRepositoryId, "baseRepositoryId");
            requireText(headRepositoryId, "headRepositoryId");
            requireText(publishRepositoryId, "publishRepositoryId");
            requireText(branchName, "branchName");
            requireText(headRef, "headRef");
            requireText(baseBranch, "baseBranch");
            requireText(prTitle, "prTitle");
            requireNonNull(prBody, "prBody is null");
            requireText(prContentDigest, "prContentDigest");
            requireText(worktreePath, "worktreePath");
            if (taskEpoch < 1 || stageGeneration < 1 || attempt < 1
                    || currentTaskEpoch < 1 || currentStageGeneration < 1
                    || prContentRevision < 1) {
                throw new IllegalArgumentException(
                        "publish versions and attempts must be positive");
            }
        }
    }

    public record EffectStep(
            String id,
            String publishOperationId,
            int ordinal,
            EffectKind kind,
            StepStatus status,
            int attemptCount,
            int attemptLimit,
            ClaimMode claimMode,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil,
            String evidenceJson,
            String lastError,
            Instant completedAt)
    {
        public EffectStep
        {
            requireText(id, "id");
            requireText(publishOperationId, "publishOperationId");
            requireNonNull(kind, "kind is null");
            requireNonNull(status, "status is null");
            if (ordinal < 1 || ordinal > 6 || attemptCount < 0
                    || attemptLimit < 1) {
                throw new IllegalArgumentException("publish effect counters are invalid");
            }
        }
    }

    public record OperationSnapshot(PublishRequest request, List<EffectStep> steps)
    {
        public OperationSnapshot
        {
            requireNonNull(request, "request is null");
            requireNonNull(steps, "steps is null");
            steps = List.copyOf(steps);
            if (steps.size() != EffectKind.values().length) {
                throw new IllegalStateException(
                        "publish operation must have exactly six effect steps");
            }
            for (int index = 0; index < steps.size(); index++) {
                EffectStep step = steps.get(index);
                if (step.ordinal() != index + 1
                        || step.kind() != EffectKind.values()[index]
                        || !step.publishOperationId().equals(
                                request.publishOperationId())) {
                    throw new IllegalStateException(
                            "publish effect order or owner is invalid");
                }
            }
        }

        Optional<EffectStep> firstIncompleteStep()
        {
            return steps.stream()
                    .filter(step -> step.status() != StepStatus.SUCCEEDED)
                    .findFirst();
        }
    }

    public record EffectClaim(
            EffectStep step,
            ClaimMode mode,
            int attempt,
            int attemptLimit,
            String claimOwner,
            Instant claimedAt,
            Instant leaseUntil)
    {
        public EffectClaim
        {
            requireNonNull(step, "step is null");
            requireNonNull(mode, "mode is null");
            requireText(claimOwner, "claimOwner");
            requireNonNull(claimedAt, "claimedAt is null");
            requireNonNull(leaseUntil, "leaseUntil is null");
            if (attempt < 1 || attemptLimit < 1 || !leaseUntil.isAfter(claimedAt)) {
                throw new IllegalArgumentException("publish effect claim is invalid");
            }
        }
    }

    public record RemoteReference(
            String repositoryId,
            int number,
            String url,
            String headRef,
            String headSha,
            String baseSha)
    {
        public RemoteReference
        {
            requireText(repositoryId, "repositoryId");
            requireText(url, "url");
            requireText(headRef, "headRef");
            if (number < 1) {
                throw new IllegalArgumentException("remote PR number must be positive");
            }
            if (headSha != null && headSha.isBlank()
                    || baseSha != null && baseSha.isBlank()) {
                throw new IllegalArgumentException("remote SHA must not be blank");
            }
        }
    }

    public record EffectEvidence(
            int version,
            EffectKind kind,
            String detail,
            RemoteReference remote)
    {
        public EffectEvidence
        {
            if (version != PAYLOAD_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported publish evidence version");
            }
            requireNonNull(kind, "kind is null");
            requireText(detail, "detail");
            boolean remoteKind = kind == EffectKind.CREATE_OR_ADOPT_DRAFT_PR
                    || kind == EffectKind.FETCH_REMOTE_DETAIL
                    || kind == EffectKind.PROVE_REMOTE_HEAD;
            if (remoteKind != (remote != null)) {
                throw new IllegalArgumentException(
                        "publish evidence remote identity has the wrong shape");
            }
            if ((kind == EffectKind.FETCH_REMOTE_DETAIL
                    || kind == EffectKind.PROVE_REMOTE_HEAD)
                    && (remote.headSha() == null || remote.baseSha() == null)) {
                throw new IllegalArgumentException(
                        "remote detail evidence requires exact SHAs");
            }
        }

        public static EffectEvidence local(EffectKind kind, String detail)
        {
            return new EffectEvidence(PAYLOAD_VERSION, kind, detail, null);
        }

        public static EffectEvidence remote(
                EffectKind kind, String detail, RemoteReference remote)
        {
            return new EffectEvidence(PAYLOAD_VERSION, kind, detail, remote);
        }
    }

    public record PublishRawResult(
            int version,
            String publishOperationId,
            String operationId,
            String taskId,
            String stageId,
            Disposition disposition,
            RemoteReference remote,
            String error,
            String observedBaseSha)
    {
        public PublishRawResult(
                int version,
                String publishOperationId,
                String operationId,
                String taskId,
                String stageId,
                Disposition disposition,
                RemoteReference remote,
                String error)
        {
            this(version, publishOperationId, operationId, taskId, stageId,
                    disposition, remote, error, null);
        }
    }

    public record StepProof(
            EffectKind kind,
            StepStatus status,
            int attemptCount,
            String evidenceJson) {}

    public record PublishResultEvidence(
            int version,
            String publishOperationId,
            String operationId,
            String codeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            Disposition disposition,
            EffectKind failedStep,
            List<StepProof> steps) {}

    public static final class MissingEffectException
            extends RuntimeException
    {
        public MissingEffectException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }
    }

    public static final class BaseMovedException
            extends RuntimeException
    {
        private final String observedBaseSha;

        public BaseMovedException(String observedBaseSha)
        {
            super("remote base moved after publish authorization");
            requireText(observedBaseSha, "observedBaseSha");
            this.observedBaseSha = observedBaseSha;
        }

        public String observedBaseSha()
        {
            return observedBaseSha;
        }
    }

    public static final class SubjectRejectedException
            extends RuntimeException
    {
        public SubjectRejectedException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }

        public SubjectRejectedException(String message, Throwable cause)
        {
            super(requireNonNull(message, "message is null"), cause);
        }
    }

    public static final class RetryableEffectException
            extends RuntimeException
    {
        public RetryableEffectException(String message, Throwable cause)
        {
            super(requireNonNull(message, "message is null"), cause);
        }

        public RetryableEffectException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }
    }

    public static final class AmbiguousEffectException
            extends RuntimeException
    {
        public AmbiguousEffectException(String message, Throwable cause)
        {
            super(requireNonNull(message, "message is null"), cause);
        }

        public AmbiguousEffectException(String message)
        {
            super(requireNonNull(message, "message is null"));
        }
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
