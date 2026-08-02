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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.RemoteCiRepairRuntimeCoordinator.ObservationDisposition;
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchControlReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEffectDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiLocalPrecondition;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CodeSubject;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.ManualCiBranchSyncAuthorization;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager.Policy;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static java.util.Objects.requireNonNull;

/** Ordered fetch, mechanical rebase, validation, force-with-lease, and observe. */
public final class BranchSyncRuntimeCoordinator
{
    private static final Map<String, String> CALLBACK_BY_KIND = Map.of(
            "FETCH_COMPARE", "BRANCH_SYNC_FETCH_RESULT",
            "MECHANICAL_REBASE", "BRANCH_SYNC_REBASE_RESULT",
            "VALIDATE", "BRANCH_SYNC_VALIDATION_RESULT",
            "FORCE_WITH_LEASE_PUSH", "BRANCH_SYNC_PUSH_RESULT");

    private final TaskCommandExecutor commands;
    private final SqliteRemoteRuntimeStore store;
    private final V2BranchSyncPolicyManager policies;
    private final ConflictRepairPort conflicts;
    private final BrainReviewPort brainReview;
    private final boolean requireBrainReview;
    private final ObjectMapper json;
    private final ObjectReader resultReader;
    private final Clock clock;

    public BranchSyncRuntimeCoordinator(
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            V2BranchSyncPolicyManager policies,
            ConflictRepairPort conflicts,
            BrainReviewPort brainReview,
            boolean requireBrainReview,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
        this.policies = requireNonNull(policies, "policies is null");
        this.conflicts = requireNonNull(conflicts, "conflicts is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.requireBrainReview = requireBrainReview;
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(RemoteEffectOperationHandler.Result.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    public BranchEpisode start(
            String taskId,
            String stageId,
            String commandId,
            String targetBaseSha)
    {
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        requireText(commandId, "commandId");
        requireText(targetBaseSha, "targetBaseSha");
        return commands.execute(taskId, () -> startInCommand(
                taskId, stageId, commandId, targetBaseSha));
    }

    /** Starts branch sync while the caller owns the Task command. */
    public BranchEpisode startInCommand(
            String taskId,
            String stageId,
            String commandId,
            String targetBaseSha)
    {
        requireText(taskId, "taskId");
        requireText(stageId, "stageId");
        requireText(commandId, "commandId");
        requireText(targetBaseSha, "targetBaseSha");
        TaskCommandExecutor.requireCurrent(taskId);
        String episodeId = PlanRuntimeCoordinator.id(
                "branch-sync-episode", commandId);
        BranchEpisode duplicate = store.findBranchEpisode(episodeId)
                .orElse(null);
        if (duplicate != null) {
            if (!taskId.equals(duplicate.taskId())
                    || !stageId.equals(duplicate.stageId())
                    || !targetBaseSha.equals(duplicate.targetBaseSha())) {
                throw new IllegalArgumentException(
                        "Branch sync command was already used with other values");
            }
            return duplicate;
        }
        Policy policy = policies.armOnFirstPushInCommand(taskId);
        if (!policy.enabled()) {
            throw new IllegalStateException("Branch sync policy is disabled");
        }
        if (store.findLiveBranchEpisode(stageId).isPresent()) {
            throw new IllegalStateException(
                    "Remote Stage already has a live branch sync");
        }
        RemoteContext context = store.requireRemoteContext(taskId, stageId);
        if (context.baseSha().equals(targetBaseSha)) {
            throw new IllegalArgumentException(
                    "Branch sync target is already the observed base");
        }
        Instant now = clock.instant();
        BranchEpisode episode = store.insertBranchEpisode(
                context, commandId, targetBaseSha, policy.id(), policy.source(),
                policy.attemptLimit(), now);
        schedule(context, episode, 1, null,
                episode.oldHeadSha(), episode.observedBaseSha(), now);
        return store.findBranchEpisode(episode.id()).orElseThrow();
    }

    /** Consumes one exact user blocker to admit a CI base-freshness precondition. */
    public CiPreconditionStart startCiPrecondition(
            String taskId,
            String episodeId,
            String blockerId,
            String commandId,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireText(blockerId, "blockerId");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        return commands.execute(taskId, () -> {
            SqliteRemoteRuntimeStore.CiEpisode episode =
                    store.requireCiEpisode(taskId, episodeId);
            ManualCiBranchSyncAuthorization replay = store
                    .findCiBranchSyncManualAuthorization(commandId)
                    .orElse(null);
            if (replay != null) {
                requireSameCiPrecondition(
                        replay, taskId, episode.stageId(), episodeId,
                        blockerId, actor, reason);
                return new CiPreconditionStart(
                        episode, replay, store.requireObservationRequest(
                                replay.observationOperationId()));
            }
            SqliteRemoteRuntimeStore.ObservationRequest observation =
                    store.findLiveObservation(episode.stageId())
                            .orElseGet(() -> store.insertObservation(
                                    store.requireRemoteContext(
                                            taskId, episode.stageId()),
                                    clock.instant()));
            ManualCiBranchSyncAuthorization authorization =
                    store.claimCiBranchSyncManualAuthorization(
                            taskId, episode.stageId(), episodeId,
                            blockerId, commandId, observation, actor, reason,
                            clock.instant());
            return new CiPreconditionStart(
                    episode, authorization, observation);
        });
    }

    /** Records one terminal exact-subject decision for exhausted BranchSync. */
    public BranchControlReceipt controlExhausted(
            String taskId,
            String episodeId,
            String blockerId,
            String commandId,
            BranchControlAction action,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireText(blockerId, "blockerId");
        requireText(commandId, "commandId");
        requireNonNull(action, "action is null");
        requireText(actor, "actor");
        requireText(reason, "reason");
        return commands.execute(taskId, () -> store.controlBranchExhaustion(
                taskId, episodeId, blockerId, commandId, action.name(), actor,
                reason, clock.instant()));
    }

    private static void requireSameCiPrecondition(
            ManualCiBranchSyncAuthorization authorization,
            String taskId,
            String stageId,
            String episodeId,
            String blockerId,
            String actor,
            String reason)
    {
        if (!taskId.equals(authorization.taskId())
                || !stageId.equals(authorization.stageId())
                || !episodeId.equals(authorization.episodeId())
                || !blockerId.equals(authorization.blockerId())
                || !actor.equals(authorization.actor())
                || !reason.equals(authorization.reason())) {
            throw new IllegalArgumentException(
                    "CI branch-sync command was already used with other values");
        }
    }

    /** Completes only after the pushed head is independently observed. */
    public ObservationDisposition acceptObservationInCommand(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        TaskCommandExecutor.requireCurrent(candidate.context().taskId());
        BranchEpisode episode = store.findLiveBranchEpisode(
                        candidate.context().stageId())
                .orElse(null);
        if (candidate.observation().prState()
                    == RemoteObservationOperationHandler.PrState.MERGED
                || candidate.observation().prState()
                    == RemoteObservationOperationHandler.PrState.CLOSED) {
            if (episode != null) {
                store.stopBranchEpisode(
                        episode,
                        "Remote pull request became terminal before branch sync completed",
                        clock.instant());
            }
            return ObservationDisposition.CONTINUE;
        }
        if (episode != null) {
            if ("AWAITING_HEAD".equals(episode.status())
                    && Objects.equals(
                            episode.resultHeadSha(), candidate.evidence().headSha())
                    && episode.targetBaseSha().equals(
                            candidate.evidence().baseSha())) {
                store.succeedBranchEpisode(
                        episode, candidate.evidence().snapshotId(), clock.instant());
                return ObservationDisposition.CONTINUE;
            }
            return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
        }

        // A normal Remote observation is also the branch freshness probe.
        // Only a pure target-base advance may be repaired automatically; an
        // independently moved PR head remains external truth.
        if (candidate.observation().prState()
                    != RemoteObservationOperationHandler.PrState.OPEN
                && candidate.observation().prState()
                    != RemoteObservationOperationHandler.PrState.DRAFT) {
            return ObservationDisposition.CONTINUE;
        }
        CodeSubject code = store.requireCodeSubject(
                candidate.context().taskId());
        RemoteContext current = store.requireRemoteContext(
                candidate.context().taskId(), candidate.context().stageId());
        store.resolveStaleBranchSyncExhaustions(current, code, clock.instant());
        if (Objects.equals(code.baseSha(), candidate.evidence().baseSha())) {
            store.resolveOpenCiBranchSyncBlockers(
                    candidate.context().stageId(),
                    "Task code already uses the accepted Remote base",
                    clock.instant());
            return ObservationDisposition.CONTINUE;
        }

        Policy policy = policies.armOnFirstPushInCommand(
                candidate.context().taskId());
        String autoApprovePolicyId = store.findCurrentAutoApprovePolicyId(
                candidate.context().taskId()).orElse(null);
        ManualCiBranchSyncAuthorization manual = store
                .findClaimedCiBranchSyncManualAuthorization(
                        candidate.context().stageId())
                .orElse(null);
        SqliteRemoteRuntimeStore.CiEpisode liveCi = store.findLiveCiEpisode(
                candidate.context().stageId()).orElse(null);
        if (candidate.ciEvaluation().outcome()
                    == RemoteCiPolicy.PolicyOutcome.ACCEPTED
                && (manual != null
                    || (liveCi != null
                        && store.hasPendingCiTurnIntent(liveCi.id())))) {
            // CI owns green closure and cancellation of every pending repair
            // or manual precondition intent. Do not launch a base writer first.
            return ObservationDisposition.CONTINUE;
        }
        if (store.hasExactBranchSyncExhaustion(current, code)) {
            return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
        }
        if (manual != null) {
            if (candidate.evidence().revision()
                        <= manual.predecessorObservationRevision()
                    || candidate.evidence().snapshotId().equals(
                            manual.predecessorSnapshotId())) {
                return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
            }
            SqliteRemoteRuntimeStore.CiEpisode ciEpisode =
                    store.requireCiEpisode(
                            candidate.context().taskId(), manual.episodeId());
            if (isTerminal(ciEpisode)) {
                store.resolveOpenCiBranchSyncBlockers(
                        candidate.context().stageId(),
                        "CI repair completed before branch sync started",
                        clock.instant());
                return ObservationDisposition.CONTINUE;
            }
            store.requestCancelLiveCiWork(
                    candidate.context().stageId(),
                    "manual CI base precondition requires the Task writer",
                    clock.instant());
            if (store.hasUnsettledCiWork(candidate.context().stageId())) {
                return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
            }
            Policy manualPolicy = policies.armOnFirstPushInCommand(
                    candidate.context().taskId());
            BranchEpisode started;
            if (!Objects.equals(code.headSha(), candidate.evidence().headSha())) {
                CiLocalPrecondition local = store
                        .findPendingCiLocalPrecondition(
                                candidate.context().stageId())
                        .orElse(null);
                if (local == null
                        || !local.episode().id().equals(manual.episodeId())
                        || !local.episode().subjectHeadSha().equals(
                                candidate.evidence().headSha())) {
                    return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
                }
                started = store.insertCiLocalPreconditionBranchEpisode(
                        current, local, manual.commandId(), manualPolicy.id(),
                        manualPolicy.attemptLimit(), "MANUAL", manual.id(),
                        clock.instant());
            }
            else {
                started = store.insertManualCiPreconditionBranchEpisode(
                        current, ciEpisode, manual.commandId(),
                        manualPolicy.id(), manualPolicy.attemptLimit(),
                        manual.id(), clock.instant());
            }
            store.consumeCiBranchSyncManualAuthorization(
                    manual, started, clock.instant());
            schedule(current, started, 1, code.codeFingerprint(),
                    code.headSha(), code.baseSha(), clock.instant());
            return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
        }

        if (!Objects.equals(code.headSha(), candidate.evidence().headSha())) {
            CiLocalPrecondition precondition = store
                    .findPendingCiLocalPrecondition(candidate.context().stageId())
                    .orElse(null);
            if (precondition == null) {
                return ObservationDisposition.CONTINUE;
            }
            if (!precondition.episode().subjectHeadSha().equals(
                        candidate.evidence().headSha())) {
                store.openCiBranchSyncBlocker(candidate, clock.instant());
                return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
            }
            if (autoApprovePolicyId == null) {
                store.openCiBranchSyncBlocker(candidate, clock.instant());
                return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
            }
            store.requestCancelLiveCiWork(
                    candidate.context().stageId(),
                    "local CI base precondition requires the Task writer",
                    clock.instant());
            if (store.hasUnsettledCiWork(candidate.context().stageId())) {
                return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
            }
            String commandId = PlanRuntimeCoordinator.id(
                    "ci-local-base-precondition-command",
                    candidate.evidence().snapshotId() + ":"
                            + precondition.intentKind() + ":"
                            + precondition.intentId());
            BranchEpisode started = store
                    .insertCiLocalPreconditionBranchEpisode(
                            current, precondition, commandId, policy.id(),
                            policy.attemptLimit(), "AUTO_APPROVE_POLICY",
                            autoApprovePolicyId, clock.instant());
            schedule(current, started, 1, code.codeFingerprint(),
                    code.headSha(), code.baseSha(), clock.instant());
            return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
        }

        if (!policy.enabled() && autoApprovePolicyId == null) {
            if (candidate.ciEvaluation().outcome()
                        == RemoteCiPolicy.PolicyOutcome.FAILED
                    || candidate.observation().mergeability()
                        == RemoteObservationOperationHandler.Mergeability
                                .CONFLICTING) {
                store.openCiBranchSyncBlocker(candidate, clock.instant());
            }
            return ObservationDisposition.CONTINUE;
        }
        String commandId = PlanRuntimeCoordinator.id(
                "branch-sync-observation-command",
                candidate.evidence().snapshotId());
        BranchEpisode started = policy.enabled()
                ? store.insertObservedBranchEpisode(
                        current, commandId, policy.id(), policy.source(),
                        policy.attemptLimit(), clock.instant())
                : store.insertCiPreconditionBranchEpisode(
                        current, commandId, policy.id(), policy.attemptLimit(),
                        "AUTO_APPROVE_POLICY", autoApprovePolicyId,
                        clock.instant());
        schedule(current, started, 1, null, code.headSha(), code.baseSha(),
                clock.instant());
        return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
    }

    private static boolean isTerminal(
            SqliteRemoteRuntimeStore.CiEpisode episode)
    {
        return switch (episode.status()) {
            case "SUCCEEDED", "EXHAUSTED", "STOPPED" -> true;
            default -> false;
        };
    }

    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "Branch sync owner/fence is stale");
        }
        String taskId = store.requireEffectTaskId(expectedFence.operationId());
        return commands.execute(taskId, () -> deliverInCommand(
                owner, expectedFence, rawResult));
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        String rawDigest = digest(write(rawResult));
        EffectDeliveryReceipt duplicate = store.findBranchEffectReceipt(
                        expectedFence.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawDigest())) {
                throw new IllegalStateException(
                        "Branch effect was redelivered with different evidence");
            }
            return receipt(duplicate.acceptance(), duplicate.rawOutcome());
        }
        BranchEffectDelivery context = store.requireBranchEffectDelivery(
                expectedFence.operationId());
        boolean exact = owner.id().equals(context.stageId())
                && Objects.equals(
                        CALLBACK_BY_KIND.get(context.kind()), owner.callbackRoute())
                && matches(expectedFence, context);
        DispatchTicket.Acceptance acceptance = exact && context.current()
                ? ACCEPTED : SUPERSEDED;
        RemoteEffectOperationHandler.Result result = rawResult.outcome() == SUCCEEDED
                ? decode(rawResult.payloadJson()) : null;
        if (result != null
                && !context.operationId().equals(result.operationId())) {
            throw new IllegalArgumentException(
                    "Branch result belongs to another Operation");
        }
        store.finishBranchEffect(
                context, rawResult.outcome().name(), rawDigest,
                acceptance.name(), result, clock.instant());
        if (acceptance == SUPERSEDED) {
            store.stopBranchEpisode(
                    store.findBranchEpisode(context.episodeId()).orElseThrow(),
                    "Remote subject changed before branch effect delivery",
                    clock.instant());
            return receipt(SUPERSEDED, "stale branch subject");
        }
        if (acceptance != ACCEPTED || rawResult.outcome() != SUCCEEDED
                || result == null
                || result.disposition()
                    == RemoteEffectOperationHandler.Disposition.FAILED) {
            if (acceptance == ACCEPTED) {
                boolean retryable = rawResult.outcome()
                            == DispatchTicket.Outcome.FAILED
                        || (rawResult.outcome() == SUCCEEDED
                            && result != null
                            && result.disposition()
                                == RemoteEffectOperationHandler.Disposition
                                        .FAILED);
                BranchStep retry = retryable
                        ? store.rearmFailedBranchEffect(context).orElse(null)
                        : null;
                if (retry != null) {
                    BranchEpisode episode = store.findBranchEpisode(
                            context.episodeId()).orElseThrow();
                    RemoteContext remote = store.requireRemoteContext(
                            context.taskId(), context.stageId());
                    schedule(
                            remote, episode, retry.ordinal(),
                            context.expectedCodeFingerprint(),
                            context.expectedHeadSha(),
                            context.expectedBaseSha(), clock.instant());
                    return receipt(ACCEPTED, "branch effect retry scheduled");
                }
                store.failBranchEpisode(
                        store.findBranchEpisode(context.episodeId()).orElseThrow(),
                        result == null ? rawResult.error() : result.error(),
                        clock.instant());
            }
            return receipt(acceptance, "branch effect failed");
        }
        continueAfter(context, result);
        return receipt(ACCEPTED, "branch step " + context.ordinal());
    }

    private void continueAfter(
            BranchEffectDelivery delivered,
            RemoteEffectOperationHandler.Result result)
    {
        Instant now = clock.instant();
        BranchEpisode episode = store.findBranchEpisode(delivered.episodeId())
                .orElseThrow();
        RemoteContext context = store.requireRemoteContext(
                episode.taskId(), episode.stageId());
        switch (delivered.ordinal()) {
            case 1 -> schedule(
                    context, episode, 2, null, episode.oldHeadSha(),
                    episode.observedBaseSha(), now);
            case 2 -> {
                requireText(result.headSha(), "rebase result headSha");
                if (result.disposition()
                        == RemoteEffectOperationHandler.Disposition.CONFLICT) {
                    store.markBranchConflictRepair(episode);
                    conflicts.startInCommand(episode, result);
                    return;
                }
                if (!episode.targetBaseSha().equals(result.baseSha())) {
                    throw new IllegalArgumentException(
                            "Rebase result does not use the target base");
                }
                store.skipBranchStep(
                        store.requireBranchStep(episode.id(), 3),
                        "mechanical rebase had no conflict", now);
                if ("CI_PRECONDITION_LOCAL".equals(episode.purpose())) {
                    completeLocalCiPrecondition(episode, result, now);
                    return;
                }
                schedule(
                        context, store.findBranchEpisode(episode.id()).orElseThrow(),
                        4, result.codeFingerprint(), result.headSha(),
                        result.baseSha(), now);
            }
            case 4 -> {
                requireText(result.headSha(), "validation result headSha");
                if (!episode.targetBaseSha().equals(result.baseSha())) {
                    throw new IllegalArgumentException(
                            "Validation result does not use the target base");
                }
                BranchStep brain = store.requireBranchStep(episode.id(), 5);
                if (requireBrainReview) {
                    brainReview.startInCommand(episode, brain, result);
                    return;
                }
                store.skipBranchStep(brain, "Brain review is optional", now);
                schedule(
                        context, store.findBranchEpisode(episode.id()).orElseThrow(),
                        6, result.codeFingerprint(), result.headSha(),
                        result.baseSha(), now);
            }
            case 6 -> {
                requireText(result.headSha(), "push result headSha");
                if (episode.oldHeadSha().equals(result.headSha())) {
                    throw new IllegalArgumentException(
                            "Branch sync push did not create a new head");
                }
                store.awaitBranchHead(episode, result.headSha());
            }
            default -> throw new IllegalStateException(
                    "Unsupported branch effect ordinal " + delivered.ordinal());
        }
    }

    private void completeLocalCiPrecondition(
            BranchEpisode episode,
            RemoteEffectOperationHandler.Result result,
            Instant at)
    {
        for (int ordinal = 4; ordinal <= 6; ordinal++) {
            store.skipBranchStep(
                    store.requireBranchStep(episode.id(), ordinal),
                    "local CI base precondition publishes only through its pending repair",
                    at);
        }
        store.succeedLocalCiPrecondition(
                episode,
                new CodeSubject(
                        requireNonNull(result.codeFingerprint(),
                                "local precondition codeFingerprint is null"),
                        requireNonNull(result.headSha(),
                                "local precondition headSha is null"),
                        requireNonNull(result.baseSha(),
                                "local precondition baseSha is null")),
                at);
        RemoteContext context = store.requireRemoteContext(
                episode.taskId(), episode.stageId());
        if (store.findLiveObservation(episode.stageId()).isEmpty()) {
            store.insertObservation(context, at);
        }
    }

    private void schedule(
            RemoteContext context,
            BranchEpisode episode,
            int ordinal,
            String codeFingerprint,
            String headSha,
            String baseSha,
            Instant at)
    {
        store.insertBranchEffect(
                context, episode, store.requireBranchStep(episode.id(), ordinal),
                codeFingerprint, headSha, baseSha, at);
    }

    private static boolean matches(
            DispatchTicket.OperationFence fence, BranchEffectDelivery context)
    {
        return Objects.equals(fence.taskEpoch(), context.taskEpoch())
                && context.stageId().equals(fence.stageId())
                && Objects.equals(
                        fence.stageGeneration(), context.stageGeneration())
                && context.operationId().equals(fence.operationId())
                && fence.attempt() == context.semanticAttempt()
                && Objects.equals(fence.expectedCodeFingerprint(),
                        context.expectedCodeFingerprint())
                && Objects.equals(
                        fence.expectedHeadSha(), context.expectedHeadSha())
                && Objects.equals(
                        fence.expectedBaseSha(), context.expectedBaseSha());
    }

    private RemoteEffectOperationHandler.Result decode(String payload)
    {
        try {
            return resultReader.readValue(payload);
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Branch effect result is invalid", e);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize branch effect evidence", e);
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String result)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                "{\"schema\":\"BRANCH_SYNC_DELIVERY_V1\","
                        + "\"result\":\"" + escape(result) + "\"}");
    }

    private static String escape(String value)
    {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static void requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @FunctionalInterface
    public interface ConflictRepairPort
    {
        /** Starts the exact conflict-repair StageTurn in the current command. */
        void startInCommand(
                BranchEpisode episode,
                RemoteEffectOperationHandler.Result rebaseResult);
    }

    @FunctionalInterface
    public interface BrainReviewPort
    {
        /** Starts the optional exact Task Brain Turn in the current command. */
        void startInCommand(
                BranchEpisode episode,
                BranchStep step,
                RemoteEffectOperationHandler.Result validationResult);
    }

    public record CiPreconditionStart(
            SqliteRemoteRuntimeStore.CiEpisode episode,
            ManualCiBranchSyncAuthorization authorization,
            SqliteRemoteRuntimeStore.ObservationRequest observation) {}

    public enum BranchControlAction
    {
        MANUAL_TAKEOVER,
        STOP_AUTOMATION
    }
}
