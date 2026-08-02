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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.ResultFence;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Evidence;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Kind;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.OperationContext;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Result;
import com.bytequay.app.developmentflow.stage.PublishResultDeliveryPort.AcceptedBaseMove;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Admission;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.AuthorityKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.BaseSyncTurn;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.Delivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryAcceptance;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.DeliveryState;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ManualBlocker;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.OpenRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ResultDisposition;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ResumeDisposition;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.ResumeSettlement;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.StartReceiptEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteLocalPublishBaseSyncStore.TurnContext;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager.Policy;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.INDETERMINATE;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Owns exact first-publish base repair from BASE_MOVED through a BASE_SYNC Turn. */
public final class LocalPublishBaseSyncRuntimeCoordinator
        implements PublishResultDeliveryPort.BaseMovedAcceptance,
        ExecutionPorts.ResultDeliveryPort,
        ExecutionPorts.MaintenanceWork
{
    private final TaskCommandExecutor commands;
    private final V2BranchSyncPolicyManager policies;
    private final SqliteLocalPublishBaseSyncStore store;
    private final LocalDevelopmentRuntimeCoordinator localRuntime;
    private final LocalDevelopmentStageManager local;
    private final ObjectMapper json;
    private final ObjectReader resultReader;
    private final ObjectReader evidenceReader;
    private final Clock clock;

    public LocalPublishBaseSyncRuntimeCoordinator(
            TaskCommandExecutor commands,
            V2BranchSyncPolicyManager policies,
            SqliteLocalPublishBaseSyncStore store,
            LocalDevelopmentRuntimeCoordinator localRuntime,
            LocalDevelopmentStageManager local,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.policies = requireNonNull(policies, "policies is null");
        this.store = requireNonNull(store, "store is null");
        this.localRuntime = requireNonNull(localRuntime, "localRuntime is null");
        this.local = requireNonNull(local, "local is null");
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(Result.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.evidenceReader = json.readerFor(Evidence.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Called synchronously by accepted Publish delivery inside its Task command. */
    @Override
    public void afterAccepted(AcceptedBaseMove baseMove)
    {
        requireNonNull(baseMove, "baseMove is null");
        TaskCommandExecutor.requireCurrent(baseMove.taskId());
        if (!baseMove.autoApprove()) {
            store.openManualBlocker(
                    baseMove.publishOperationId(), baseMove.observedBaseSha(),
                    baseMove.acceptedAt());
            return;
        }
        Policy policy = policies.armOnFirstPushInCommand(baseMove.taskId());
        store.open(new OpenRequest(
                commandId(baseMove.publishOperationId()),
                baseMove.publishOperationId(), baseMove.observedBaseSha(),
                policy.id(), AuthorityKind.STANDING_TASK_POLICY,
                baseMove.policyRevisionId(), null, null, baseMove.acceptedAt()));
    }

    /** Explicit user authority for the exact blocker opened by BASE_MOVED delivery. */
    public Admission approveManual(String taskId, String blockerId, String actor)
    {
        requireText(taskId, "taskId");
        return commands.execute(
                taskId, () -> approveManualInCommand(taskId, blockerId, actor));
    }

    public Admission approveManualInCommand(
            String taskId, String blockerId, String actor)
    {
        requireText(taskId, "taskId");
        requireText(blockerId, "blockerId");
        requireText(actor, "actor");
        TaskCommandExecutor.requireCurrent(taskId);
        ManualBlocker blocker = store.findManualBlocker(blockerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Local publish base-sync blocker: " + blockerId));
        if (!taskId.equals(blocker.taskId())
                || !("OPEN".equals(blocker.status())
                    || "RESOLVED".equals(blocker.status()))) {
            throw new IllegalArgumentException(
                    "Local publish base-sync blocker is not approvable");
        }
        if (!"LOCAL_PUBLISH_BASE_SYNC_REQUIRED".equals(
                blocker.blockerType())) {
            return store.approveFailureBlocker(
                    blocker,
                    id("approve-local-publish-base-sync-retry", blocker.id()),
                    actor, clock.instant());
        }
        Policy policy = policies.armOnFirstPushInCommand(taskId);
        return store.open(new OpenRequest(
                commandId(blocker.sourcePublishOperationId()),
                blocker.sourcePublishOperationId(), blocker.targetBaseSha(),
                policy.id(), AuthorityKind.MANUAL, null, blocker.id(), actor,
                clock.instant()));
    }

    /** One explicit action grants exactly one attempt beyond exhaustion. */
    public Admission extendExhausted(
            String taskId,
            String episodeId,
            String blockerId,
            String commandId,
            String actor)
    {
        requireText(taskId, "taskId");
        return commands.execute(taskId, () -> {
            TaskCommandExecutor.requireCurrent(taskId);
            ManualBlocker blocker = store.findManualBlocker(blockerId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No Local publish base-sync blocker: " + blockerId));
            if (!taskId.equals(blocker.taskId())
                    || !episodeId.equals(blocker.subjectRevision())
                    || !"LOCAL_PUBLISH_BASE_SYNC_EXHAUSTED".equals(
                            blocker.blockerType())
                    || !("OPEN".equals(blocker.status())
                        || "RESOLVED".equals(blocker.status()))) {
                throw new IllegalArgumentException(
                        "Local publish base-sync exhaustion is not extendable");
            }
            return store.approveFailureBlocker(
                    blocker, commandId, actor, clock.instant());
        });
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
                || !isCallback(owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            throw new IllegalArgumentException(
                    "Local publish base-sync delivery owner or fence is invalid");
        }
        if (rawResult.outcome() == INDETERMINATE) {
            throw new IllegalArgumentException(
                    "Indeterminate Local publish base-sync must reconcile first");
        }
        String taskId = store.requireTaskId(expectedFence.operationId());
        return commands.execute(taskId, () -> deliverInCommand(
                owner, expectedFence, rawResult));
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult)
    {
        String rawDigest = digest(write(rawResult));
        DeliveryReceipt duplicate = store.findReceipt(fence.operationId())
                .orElse(null);
        if (duplicate != null) {
            OperationContext operation = store.requireByOperationId(
                    fence.operationId());
            requireExactSubject(owner, fence, operation);
            if (duplicate.rawOutcome() != rawResult.outcome()
                    || !duplicate.rawResultDigest().equals(rawDigest)) {
                throw new IllegalStateException(
                        "Local publish base-sync result changed on redelivery");
            }
            return receipt(
                    duplicate.acceptance(),
                    duplicate.operationId(),
                    "replayed " + duplicate.rawOutcome());
        }

        Delivery delivery = store.requireDelivery(fence.operationId());
        requireExactSubject(owner, fence, delivery);
        DeliveryAcceptance acceptance = switch (delivery.state()) {
            case ACTIVE -> DeliveryAcceptance.ACCEPTED;
            case PAUSING -> DeliveryAcceptance.PARKED;
            case CANCELING, STALE -> DeliveryAcceptance.SUPERSEDED;
        };
        Result decoded = acceptance != DeliveryAcceptance.SUPERSEDED
                ? decodeResult(rawResult) : null;
        requireExactResult(delivery, rawResult, decoded, acceptance);
        SqliteLocalPublishBaseSyncStore.Result stored = toStored(decoded);
        DeliveryReceipt persisted = acceptance == DeliveryAcceptance.PARKED
                ? store.finishClassified(
                        delivery, rawResult.outcome(), rawDigest, acceptance,
                        stored, clock.instant())
                : store.finish(
                        delivery, rawResult.outcome(), rawDigest,
                        dispatcherAcceptance(acceptance), stored,
                        clock.instant());
        if (persisted.localAcceptance() != acceptance) {
            throw new IllegalStateException(
                    "Local publish base-sync stored another acceptance");
        }
        if (acceptance == DeliveryAcceptance.PARKED) {
            return receipt(SUPERSEDED, delivery.operationId(),
                    "parked base-sync result for Task resume");
        }
        if (acceptance == DeliveryAcceptance.SUPERSEDED) {
            return receipt(SUPERSEDED, delivery.operationId(),
                    delivery.state() == DeliveryState.CANCELING
                            ? "Task cancellation discarded base-sync result"
                            : "stale base-sync subject");
        }
        if (rawResult.outcome() != SUCCEEDED) {
            if (rawResult.outcome() == FAILED) {
                store.settleFailure(delivery.episodeId(), clock.instant());
            }
            return receipt(ACCEPTED, delivery.operationId(),
                    "base-sync operation did not succeed");
        }

        if (delivery.kind() == SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE) {
            store.requestRebase(delivery.episodeId(), clock.instant());
        }
        else {
            startBaseSyncTurn(delivery);
        }
        return receipt(ACCEPTED, delivery.operationId(),
                "accepted " + delivery.kind());
    }

    private void startBaseSyncTurn(Delivery delivery)
    {
        startBaseSyncTurn(delivery.episodeId(), delivery.operationId());
    }

    private void startBaseSyncTurn(String episodeId, String operationId)
    {
        Instant now = clock.instant();
        TurnContext context = store.requireTurnContext(episodeId);
        Result result = durableRebaseResult(operationId, context);
        BaseSyncTurn turn = localRuntime.createPublishBaseSyncTurnInCommand(
                context, result, now);
        store.insertBaseSyncTurn(turn);
        ResultFence turnFence = fence(turn);
        CommandResult<StageManager.State> started =
                local.startPublishBaseSyncInCommand(
                        new StageManager.Command(
                                turn.commandId(), turn.requestedBy(), turn.taskId(),
                                turn.taskEpoch(), turn.stageId(),
                                turn.stageGeneration(), context.stageVersion()),
                        turnFence, context.episodeId());
        if (started.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Current BASE_SYNC StageTurn was superseded");
        }
        StartReceiptEvidence receipt = store.completeHandoff(
                context.episodeId(), now);
        requireStartReceipt(receipt, context, turn, started.state());
    }

    private Result durableRebaseResult(String operationId, TurnContext context)
    {
        if (context.resultDisposition() == null
                || context.resultEvidenceJson() == null) {
            throw new IllegalStateException(
                    "Durable mechanical rebase proof is incomplete");
        }
        Evidence evidence = decodeEvidence(context.resultEvidenceJson());
        return new Result(
                1, operationId, Kind.MECHANICAL_REBASE,
                Disposition.valueOf(context.resultDisposition().name()),
                context.codeFingerprint(), context.headSha(), context.baseSha(),
                context.targetBaseSha(), evidence, null);
    }

    private void requireExactResult(
            Delivery delivery,
            DispatchTicket.DispatchResult rawResult,
            Result result,
            DeliveryAcceptance acceptance)
    {
        if (acceptance == DeliveryAcceptance.SUPERSEDED) {
            return;
        }
        if (rawResult.outcome() == CANCELED) {
            if (rawResult.error() == null || rawResult.error().isBlank()) {
                throw new IllegalArgumentException(
                        "Canceled Local publish base-sync lacks an error");
            }
            return;
        }
        if (result == null
                || !Objects.equals(
                        rawResult.payloadJson(), rawResult.evidenceJson())
                || !delivery.operationId().equals(result.operationId())
                || toHandlerKind(delivery.kind()) != result.kind()
                || !delivery.targetBaseSha().equals(result.targetBaseSha())) {
            throw new IllegalArgumentException(
                    "Local publish base-sync result has another subject");
        }
        if (rawResult.outcome() == SUCCEEDED) {
            if (result.disposition() == Disposition.FAILED
                    || rawResult.error() != null
                    || !matchesSuccessEvidence(delivery, result)) {
                throw new IllegalArgumentException(
                        "Successful Local publish base-sync proof is not exact");
            }
            return;
        }
        if (rawResult.outcome() != FAILED
                || result.disposition() != Disposition.FAILED
                || result.error() == null
                || !result.error().equals(rawResult.error())) {
            throw new IllegalArgumentException(
                    "Failed Local publish base-sync proof is not exact");
        }
    }

    /** Continues a parked cursor only after the Task resume owner is ACTIVE. */
    public Optional<ResumeSettlement> resumePausedInCommand(
            String handoffId,
            String taskId,
            String stageId,
            long taskEpoch,
            long stageGeneration,
            Instant now)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Optional<ResumeSettlement> resumed = store.resumePaused(
                handoffId, taskId, stageId, taskEpoch, stageGeneration, now);
        if (resumed.isPresent()
                && resumed.orElseThrow().disposition()
                        == ResumeDisposition.HANDOFF) {
            var rebase = store.findOperation(
                    resumed.orElseThrow().episode().id(),
                    SqliteLocalPublishBaseSyncStore.Kind.MECHANICAL_REBASE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Parked base-sync handoff lacks its rebase proof"));
            startBaseSyncTurn(
                    resumed.orElseThrow().episode().id(), rebase.operationId());
        }
        return resumed;
    }

    @Override
    public void maintain(Instant now)
    {
        for (var settlement : store.pendingControlSettlements(32)) {
            commands.executeVoid(settlement.taskId(), () ->
                    store.settleControl(settlement, now));
        }
    }

    private static boolean matchesSuccessEvidence(Delivery delivery, Result result)
    {
        Evidence evidence = result.evidence();
        return evidence != null
                && evidence.kind() == result.kind()
                && delivery.expectedCodeFingerprint().equals(
                        evidence.sourceCodeFingerprint())
                && delivery.expectedHeadSha().equals(evidence.sourceHeadSha())
                && delivery.expectedBaseSha().equals(evidence.sourceBaseSha())
                && delivery.targetBaseSha().equals(evidence.targetBaseSha())
                && result.codeFingerprint().equals(
                        evidence.resultCodeFingerprint())
                && result.headSha().equals(evidence.resultHeadSha())
                && result.baseSha().equals(evidence.resultBaseSha());
    }

    private static void requireStartReceipt(
            StartReceiptEvidence receipt,
            TurnContext context,
            BaseSyncTurn turn,
            StageManager.State state)
    {
        boolean exact = receipt.episodeId().equals(context.episodeId())
                && receipt.requestId().equals(turn.requestId())
                && receipt.commandId().equals(turn.commandId())
                && receipt.actor().equals(turn.requestedBy())
                && receipt.taskId().equals(turn.taskId())
                && receipt.stageId().equals(turn.stageId())
                && receipt.taskEpoch() == turn.taskEpoch()
                && receipt.stageGeneration() == turn.stageGeneration()
                && receipt.expectedStageVersion() == context.stageVersion()
                && receipt.returnedStageVersion() == state.version()
                && receipt.operationId().equals(turn.operationId())
                && receipt.attempt() == turn.attempt()
                && receipt.codeFingerprint().equals(turn.codeFingerprint())
                && receipt.headSha().equals(turn.headSha())
                && receipt.baseSha().equals(turn.baseSha())
                && receipt.targetBaseSha().equals(turn.targetBaseSha())
                && state.checkpoint() == StageCheckpoint.IMPLEMENTING
                && fence(turn).equals(state.pendingResult());
        if (!exact) {
            throw new IllegalStateException(
                    "Local publish base-sync start receipt is not exact");
        }
    }

    private Result decodeResult(DispatchTicket.DispatchResult rawResult)
    {
        if (rawResult.outcome() == CANCELED) {
            return null;
        }
        try {
            return resultReader.readValue(rawResult.payloadJson());
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Local publish base-sync result is not strict typed JSON",
                    failure);
        }
    }

    private Evidence decodeEvidence(String value)
    {
        try {
            return evidenceReader.readValue(value);
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Durable Local publish base-sync evidence is invalid",
                    failure);
        }
    }

    private SqliteLocalPublishBaseSyncStore.Result toStored(Result result)
    {
        if (result == null) {
            return null;
        }
        return new SqliteLocalPublishBaseSyncStore.Result(
                ResultDisposition.valueOf(result.disposition().name()),
                result.codeFingerprint(), result.headSha(), result.baseSha(),
                result.evidence() == null ? null : write(result.evidence()),
                result.error());
    }

    private static boolean matches(
            DispatchTicket.OperationFence fence, Delivery delivery)
    {
        return Objects.equals(fence.taskEpoch(), delivery.taskEpoch())
                && delivery.stageId().equals(fence.stageId())
                && Objects.equals(
                        fence.stageGeneration(), delivery.stageGeneration())
                && delivery.operationId().equals(fence.operationId())
                && fence.attempt() == delivery.semanticAttempt()
                && delivery.expectedCodeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                && delivery.expectedHeadSha().equals(fence.expectedHeadSha())
                && delivery.expectedBaseSha().equals(fence.expectedBaseSha());
    }

    private static DispatchTicket.Acceptance dispatcherAcceptance(
            DeliveryAcceptance acceptance)
    {
        return acceptance == DeliveryAcceptance.ACCEPTED
                ? ACCEPTED : SUPERSEDED;
    }

    private static void requireExactSubject(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            Delivery delivery)
    {
        if (!owner.id().equals(delivery.stageId())
                || !callback(delivery.kind()).equals(owner.callbackRoute())
                || !matches(fence, delivery)) {
            throw new IllegalArgumentException(
                    "Local publish base-sync delivery has another subject");
        }
    }

    private static void requireExactSubject(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            OperationContext operation)
    {
        if (!owner.id().equals(operation.stageId())
                || !callback(toStoreKind(operation.kind())).equals(
                        owner.callbackRoute())
                || !Objects.equals(fence.taskEpoch(), operation.taskEpoch())
                || !operation.stageId().equals(fence.stageId())
                || !Objects.equals(
                        fence.stageGeneration(), operation.stageGeneration())
                || !operation.operationId().equals(fence.operationId())
                || fence.attempt() != operation.semanticAttempt()
                || !operation.expectedCodeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                || !operation.expectedHeadSha().equals(
                        fence.expectedHeadSha())
                || !operation.expectedBaseSha().equals(
                        fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Local publish base-sync delivery has another subject");
        }
    }

    private static ResultFence fence(BaseSyncTurn turn)
    {
        return new ResultFence(
                turn.taskEpoch(), turn.stageId(), turn.stageGeneration(),
                turn.operationId(), turn.attempt(), turn.codeFingerprint(),
                turn.headSha(), turn.baseSha());
    }

    private static Kind toHandlerKind(SqliteLocalPublishBaseSyncStore.Kind kind)
    {
        return Kind.valueOf(kind.name());
    }

    private static SqliteLocalPublishBaseSyncStore.Kind toStoreKind(Kind kind)
    {
        return SqliteLocalPublishBaseSyncStore.Kind.valueOf(kind.name());
    }

    private static String callback(SqliteLocalPublishBaseSyncStore.Kind kind)
    {
        return kind == SqliteLocalPublishBaseSyncStore.Kind.FETCH_COMPARE
                ? LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK
                : LocalPublishBaseSyncOperationHandler.REBASE_CALLBACK;
    }

    private static boolean isCallback(String callback)
    {
        return LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK.equals(callback)
                || LocalPublishBaseSyncOperationHandler.REBASE_CALLBACK.equals(
                        callback);
    }

    private DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance,
            String operationId,
            String result)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                write(new DeliveryEvidence(1, operationId, acceptance, result)));
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Could not serialize Local publish base-sync evidence",
                    failure);
        }
    }

    private static String commandId(String publishOperationId)
    {
        return id("open-local-publish-base-sync", publishOperationId);
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private record DeliveryEvidence(
            int version,
            String operationId,
            DispatchTicket.Acceptance acceptance,
            String result) {}
}
