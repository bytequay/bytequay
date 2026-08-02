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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishRawResult;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.RemoteReference;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Accepts one durable publish result and performs the atomic Local-to-Remote handoff. */
public final class PublishResultDeliveryPort
        implements ExecutionPorts.ResultDeliveryPort
{
    private static final String ACTOR = "v2-publish-delivery";

    private final TaskCommandExecutor commands;
    private final LocalDevelopmentStageManager local;
    private final LocalToRemoteHandoff handoff;
    private final RemoteObservationRuntimeCoordinator observations;
    private final PRService prs;
    private final Store store;
    private final BaseMovedAcceptance baseMoves;
    private final ObjectMapper json;
    private final ObjectReader resultReader;
    private final Clock clock;

    public PublishResultDeliveryPort(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            LocalToRemoteHandoff handoff,
            RemoteObservationRuntimeCoordinator observations,
            PRService prs,
            Store store,
            ObjectMapper json,
            Clock clock)
    {
        this(commands, local, handoff, observations, prs, store,
                BaseMovedAcceptance.noOp(), json, clock);
    }

    public PublishResultDeliveryPort(
            TaskCommandExecutor commands,
            LocalDevelopmentStageManager local,
            LocalToRemoteHandoff handoff,
            RemoteObservationRuntimeCoordinator observations,
            PRService prs,
            Store store,
            BaseMovedAcceptance baseMoves,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.local = requireNonNull(local, "local is null");
        this.handoff = requireNonNull(handoff, "handoff is null");
        this.observations = requireNonNull(observations, "observations is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.store = requireNonNull(store, "store is null");
        this.baseMoves = requireNonNull(baseMoves, "baseMoves is null");
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(PublishRawResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !PublishOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            throw new IllegalArgumentException(
                    "Publish delivery owner or raw result fence is invalid");
        }
        String taskId = store.requireTaskId(expectedFence.operationId());
        return commands.execute(taskId, () -> deliverInCommand(
                owner, expectedFence, rawResult));
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        String operationId = expectedFence.operationId();
        String rawDigest = digest(write(rawResult));
        Optional<Receipt> duplicate = store.findReceipt(operationId);
        if (duplicate.isPresent()) {
            Receipt persisted = duplicate.orElseThrow();
            if (!persisted.rawResultDigest().equals(rawDigest)
                    || persisted.outcome() != rawResult.outcome()) {
                throw new IllegalStateException(
                        "Publish result was redelivered with different evidence");
            }
            return receipt(persisted.acceptance(), persisted);
        }

        Context context = store.requireContext(operationId);
        requireExact(context, owner, expectedFence, rawResult);
        Instant now = clock.instant();
        Receipt recorded = switch (rawResult.outcome()) {
            case SUCCEEDED -> acceptPublished(context, expectedFence, rawResult,
                    rawDigest, now);
            case FAILED, CANCELED -> acceptFailure(context, expectedFence,
                    rawResult, rawDigest, now);
            case INDETERMINATE -> throw new IllegalArgumentException(
                    "Indeterminate publish work must reconcile before delivery");
        };
        store.insertReceipt(recorded);
        return receipt(recorded.acceptance(), recorded);
    }

    private Receipt acceptPublished(
            Context context,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            Instant now)
    {
        PublishRawResult result = decode(rawResult.payloadJson());
        if (result.disposition() != PublishOperationHandler.Disposition.PUBLISHED
                || result.remote() == null || result.error() != null
                || result.observedBaseSha() != null
                || rawResult.evidenceJson() == null
                || rawResult.evidenceJson().isBlank()) {
            throw new IllegalArgumentException(
                    "Successful publish result has the wrong typed payload");
        }
        requirePayloadIdentity(context, result);
        String bindingId = id("remote-pr-binding", context.operationId());
        store.completePublished(
                context, bindingId, result.remote(), rawResult.evidenceJson(), now);
        prs.recordPublishedInCommand(
                context.prId(), result.remote().repositoryId(),
                result.remote().number(), result.remote().url());

        String remoteStageId = id("remote-stage", context.operationId());
        String commandId = id("accept-published", context.operationId());
        ResultFence resultFence = toResultFence(fence);
        LocalToRemoteHandoff.Result promotion = handoff.acceptInCommand(
                new LocalToRemoteHandoff.Command(
                        new StageManager.ResultCommand(
                                commandId, ACTOR, context.taskId(), resultFence),
                        new TaskManager.StageAdvanceCommand(
                                commandId, ACTOR, context.taskId(),
                                context.taskEpoch(), context.handoffTaskVersion(),
                                remoteStageId, context.nextRemoteGeneration())));
        DispatchTicket.Acceptance acceptance;
        String acceptedRemoteStage = null;
        if (promotion.remote().isPresent()) {
            StageManager.State remote = promotion.remote().orElseThrow().state();
            store.initializeRemote(
                    context, bindingId, remote,
                    id("remote-ci-policy", context.operationId()),
                    id("remote-automation-policy", context.operationId()), now);
            observations.requestObservationInCommand(
                    context.taskId(), remote.id());
            acceptance = ACCEPTED;
            acceptedRemoteStage = remote.id();
        }
        else {
            acceptance = SUPERSEDED;
        }
        return new Receipt(
                context.operationId(), rawDigest, SUCCEEDED, acceptance,
                acceptedRemoteStage, now);
    }

    private Receipt acceptFailure(
            Context context,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult,
            String rawDigest,
            Instant now)
    {
        PublishRawResult result = decode(rawResult.payloadJson());
        boolean baseMoved = result.disposition()
                == PublishOperationHandler.Disposition.BASE_MOVED;
        boolean exactDisposition = rawResult.outcome() == FAILED
                ? result.disposition() == PublishOperationHandler.Disposition.FAILED
                        || baseMoved
                : result.disposition()
                        == PublishOperationHandler.Disposition.CANCELED;
        boolean exactObservedBase = baseMoved
                ? result.observedBaseSha() != null
                        && !result.observedBaseSha().isBlank()
                        && !context.expectedBaseSha().equals(
                                result.observedBaseSha())
                : result.observedBaseSha() == null;
        if (!exactDisposition || !exactObservedBase
                || result.remote() != null
                || result.error() == null || result.error().isBlank()
                || !result.error().equals(rawResult.error())) {
            throw new IllegalArgumentException(
                    "Terminal publish result has the wrong typed payload");
        }
        requirePayloadIdentity(context, result);
        store.completeFailure(context, rawResult.outcome(), result.error(), now);
        LocalDevelopmentStageManager.PublishFailureResult stage =
                local.acceptPublishFailureInCommand(
                        new StageManager.ResultCommand(
                                id("accept-publish-failure", context.operationId()),
                                ACTOR, context.taskId(), toResultFence(fence)));
        if (baseMoved && stage.accepted()) {
            baseMoves.afterAccepted(new AcceptedBaseMove(
                    context.publishOperationId(), context.operationId(),
                    context.authorizationId(), context.manifestId(),
                    context.policyRevisionId(), context.autoApprove(),
                    context.taskId(), context.stageId(), context.taskEpoch(),
                    context.stageGeneration(), context.attempt(),
                    context.codeFingerprint(), context.expectedHeadSha(),
                    context.expectedBaseSha(), result.observedBaseSha(), now));
        }
        return new Receipt(
                context.operationId(), rawDigest, rawResult.outcome(),
                stage.accepted() ? ACCEPTED : SUPERSEDED, null, now);
    }

    private PublishRawResult decode(String payload)
    {
        try {
            return resultReader.readValue(payload);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Publish result is not valid typed JSON", e);
        }
    }

    private static void requireExact(
            Context context,
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult)
    {
        boolean exact = owner.id().equals(context.stageId())
                && fence.taskEpoch() == context.taskEpoch()
                && context.stageId().equals(fence.stageId())
                && fence.stageGeneration() != null
                && fence.stageGeneration() == context.stageGeneration()
                && fence.operationId().equals(context.operationId())
                && fence.attempt() == context.attempt()
                && context.codeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                && context.expectedHeadSha().equals(fence.expectedHeadSha())
                && context.expectedBaseSha().equals(fence.expectedBaseSha())
                && "RESULT_PENDING".equals(context.ticketStatus())
                && rawResult.outcome() == context.pendingOutcome()
                && ("DISPATCHED".equals(context.operationStatus())
                    || context.operationStatus().equals(
                            rawResult.outcome().name()));
        if (!exact) {
            throw new IllegalArgumentException(
                    "Publish result differs from its persisted owner fence");
        }
    }

    private static void requirePayloadIdentity(
            Context context, PublishRawResult result)
    {
        if (result.version() != 1
                || !context.publishOperationId().equals(result.publishOperationId())
                || !context.operationId().equals(result.operationId())
                || !context.taskId().equals(result.taskId())
                || !context.stageId().equals(result.stageId())) {
            throw new IllegalArgumentException(
                    "Publish payload identity differs from its Operation");
        }
    }

    private static ResultFence toResultFence(DispatchTicket.OperationFence fence)
    {
        return new ResultFence(
                fence.taskEpoch(), fence.stageId(), fence.stageGeneration(),
                fence.operationId(), fence.attempt(),
                fence.expectedCodeFingerprint(), fence.expectedHeadSha(),
                fence.expectedBaseSha());
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, Receipt persisted)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, write(new DeliveryEvidence(
                        1, persisted.operationId(), persisted.outcome(),
                        acceptance, persisted.remoteStageId())));
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Serializing publish delivery failed", e);
        }
    }

    public interface Store
    {
        String requireTaskId(String operationId);

        Context requireContext(String operationId);

        Optional<Receipt> findReceipt(String operationId);

        void completePublished(
                Context context,
                String bindingId,
                RemoteReference remote,
                String resultEvidence,
                Instant completedAt);

        void completeFailure(
                Context context,
                DispatchTicket.Outcome outcome,
                String error,
                Instant completedAt);

        void initializeRemote(
                Context context,
                String bindingId,
                StageManager.State remoteStage,
                String ciPolicyId,
                String automationPolicyId,
                Instant openedAt);

        void insertReceipt(Receipt receipt);
    }

    @FunctionalInterface
    public interface BaseMovedAcceptance
    {
        void afterAccepted(AcceptedBaseMove baseMove);

        static BaseMovedAcceptance noOp()
        {
            return ignored -> {};
        }
    }

    public record AcceptedBaseMove(
            String publishOperationId,
            String operationId,
            String authorizationId,
            String manifestId,
            String policyRevisionId,
            boolean autoApprove,
            String taskId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            int attempt,
            String codeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String observedBaseSha,
            Instant acceptedAt)
    {
        public AcceptedBaseMove
        {
            requireText(publishOperationId, "publishOperationId");
            requireText(operationId, "operationId");
            requireText(authorizationId, "authorizationId");
            requireText(manifestId, "manifestId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(codeFingerprint, "codeFingerprint");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireText(observedBaseSha, "observedBaseSha");
            requireNonNull(acceptedAt, "acceptedAt is null");
            if (taskEpoch < 1 || stageGeneration < 1 || attempt < 1
                    || expectedBaseSha.equals(observedBaseSha)) {
                throw new IllegalArgumentException(
                        "Accepted base move has an invalid fence");
            }
        }
    }

    public record Context(
            String publishOperationId,
            String operationId,
            String authorizationId,
            String manifestId,
            String policyRevisionId,
            String prId,
            String taskId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            int attempt,
            String codeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String operationStatus,
            String ticketStatus,
            DispatchTicket.Outcome pendingOutcome,
            long handoffTaskVersion,
            long nextRemoteGeneration,
            boolean autoApprove,
            boolean autoMerge,
            int minimumApprovals,
            PublishOperationHandler.Route route,
            String baseRepositoryId,
            String headRepositoryId)
    {
        public Context
        {
            requireText(publishOperationId, "publishOperationId");
            requireText(operationId, "operationId");
            requireText(authorizationId, "authorizationId");
            requireText(manifestId, "manifestId");
            requireText(policyRevisionId, "policyRevisionId");
            requireText(prId, "prId");
            requireText(taskId, "taskId");
            requireText(stageId, "stageId");
            requireText(codeFingerprint, "codeFingerprint");
            requireText(expectedHeadSha, "expectedHeadSha");
            requireText(expectedBaseSha, "expectedBaseSha");
            requireText(operationStatus, "operationStatus");
            requireText(ticketStatus, "ticketStatus");
            requireNonNull(pendingOutcome, "pendingOutcome is null");
            requireNonNull(route, "route is null");
            requireText(baseRepositoryId, "baseRepositoryId");
            requireText(headRepositoryId, "headRepositoryId");
            if (taskEpoch < 1 || stageGeneration < 1 || attempt < 1
                    || handoffTaskVersion < 0 || nextRemoteGeneration < 1
                    || minimumApprovals < 0 || (autoMerge && !autoApprove)) {
                throw new IllegalArgumentException(
                        "Publish delivery context has invalid counters or policy");
            }
        }
    }

    public record Receipt(
            String operationId,
            String rawResultDigest,
            DispatchTicket.Outcome outcome,
            DispatchTicket.Acceptance acceptance,
            String remoteStageId,
            Instant deliveredAt)
    {
        public Receipt
        {
            requireText(operationId, "operationId");
            requireText(rawResultDigest, "rawResultDigest");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(acceptance, "acceptance is null");
            requireNonNull(deliveredAt, "deliveredAt is null");
            if (acceptance == DispatchTicket.Acceptance.REJECTED
                    || ((outcome == SUCCEEDED && acceptance == ACCEPTED)
                        != (remoteStageId != null))) {
                throw new IllegalArgumentException(
                        "Publish delivery receipt has the wrong shape");
            }
            if (remoteStageId != null) {
                requireText(remoteStageId, "remoteStageId");
            }
        }
    }

    private record DeliveryEvidence(
            int version,
            String operationId,
            DispatchTicket.Outcome outcome,
            DispatchTicket.Acceptance acceptance,
            String remoteStageId) {}

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
