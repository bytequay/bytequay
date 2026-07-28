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
import com.bytequay.app.developmentflow.stage.RemoteObservationConsumer.Candidate;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEffectDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BranchStep;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
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

    /** Completes only after the pushed head is independently observed. */
    public void acceptObservationInCommand(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        TaskCommandExecutor.requireCurrent(candidate.context().taskId());
        BranchEpisode episode = store.findLiveBranchEpisode(
                        candidate.context().stageId())
                .orElse(null);
        if (episode != null) {
            if ("AWAITING_HEAD".equals(episode.status())
                    && Objects.equals(
                            episode.resultHeadSha(), candidate.evidence().headSha())
                    && episode.targetBaseSha().equals(
                            candidate.evidence().baseSha())) {
                store.succeedBranchEpisode(
                        episode, candidate.evidence().snapshotId(), clock.instant());
            }
            return;
        }

        // A normal Remote observation is also the scheduled branch-guard
        // probe. Start only for a pure base advance: an independently moved
        // PR head must first be reconciled as external truth, never
        // force-overwritten by an automatic rebase.
        if (candidate.observation().prState()
                    != RemoteObservationOperationHandler.PrState.OPEN
                && candidate.observation().prState()
                    != RemoteObservationOperationHandler.PrState.DRAFT) {
            return;
        }
        Policy policy = policies.armOnFirstPushInCommand(
                candidate.context().taskId());
        if (!policy.enabled()) {
            return;
        }
        if (!Objects.equals(candidate.context().currentHeadSha(),
                    candidate.evidence().headSha())
                || Objects.equals(candidate.context().currentBaseSha(),
                    candidate.evidence().baseSha())) {
            return;
        }
        SqliteRemoteRuntimeStore.CodeSubject code = store.requireCodeSubject(
                candidate.context().taskId());
        if (!Objects.equals(code.headSha(), candidate.evidence().headSha())
                || Objects.equals(code.baseSha(), candidate.evidence().baseSha())) {
            return;
        }
        RemoteContext current = store.requireRemoteContext(
                candidate.context().taskId(), candidate.context().stageId());
        String commandId = PlanRuntimeCoordinator.id(
                "branch-sync-observation-command",
                candidate.evidence().snapshotId());
        BranchEpisode started = store.insertObservedBranchEpisode(
                current, commandId, policy.id(), policy.source(),
                policy.attemptLimit(), clock.instant());
        schedule(current, started, 1, null, code.headSha(), code.baseSha(),
                clock.instant());
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
}
