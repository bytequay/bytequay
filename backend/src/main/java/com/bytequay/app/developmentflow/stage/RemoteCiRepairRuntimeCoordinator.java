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
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiBudgets;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEffectDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiPushSubject;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiTurnFreshness;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.EffectDeliveryReceipt;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.RemoteContext;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static java.util.Objects.requireNonNull;

/** Exact-head CI decision and the rerun-only repair path. */
public final class RemoteCiRepairRuntimeCoordinator
{
    private final TaskCommandExecutor commands;
    private final SqliteRemoteRuntimeStore store;
    private final FailureClassifier classifier;
    private final CiBudgets budgets;
    private final DeterministicRepairPort deterministicRepairs;
    private final ObjectMapper json;
    private final ObjectReader resultReader;
    private final Clock clock;

    public RemoteCiRepairRuntimeCoordinator(
            TaskCommandExecutor commands,
            SqliteRemoteRuntimeStore store,
            FailureClassifier classifier,
            CiBudgets budgets,
            DeterministicRepairPort deterministicRepairs,
            ObjectMapper json,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.store = requireNonNull(store, "store is null");
        this.classifier = requireNonNull(classifier, "classifier is null");
        this.budgets = requireNonNull(budgets, "budgets is null");
        this.deterministicRepairs = requireNonNull(
                deterministicRepairs, "deterministicRepairs is null");
        this.json = requireNonNull(json, "json is null");
        this.resultReader = json.readerFor(RemoteEffectOperationHandler.Result.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Called after the Remote owner accepted the snapshot in the same command. */
    public ObservationDisposition acceptObservationInCommand(Candidate candidate)
    {
        requireNonNull(candidate, "candidate is null");
        TaskCommandExecutor.requireCurrent(candidate.context().taskId());
        Instant now = clock.instant();
        CiEpisode episode = store.findLiveCiEpisode(
                        candidate.context().stageId())
                .orElse(null);
        if (candidate.observation().prState()
                    == RemoteObservationOperationHandler.PrState.MERGED
                || candidate.observation().prState()
                    == RemoteObservationOperationHandler.PrState.CLOSED) {
            if (episode != null) {
                store.stopCiEpisode(
                        episode,
                        "Remote pull request became terminal before CI repair completed",
                        now);
            }
            return ObservationDisposition.CONTINUE;
        }
        boolean pushedHead = episode != null
                && Objects.equals(episode.lastPushedHeadSha(),
                        candidate.evidence().headSha())
                && episode.subjectBaseSha().equals(
                        candidate.evidence().baseSha());
        CiPushSubject livePush = episode == null ? null
                : store.findLiveCiPushSubject(episode.id()).orElse(null);
        boolean provisionalPushedHead = livePush != null
                && livePush.headSha().equals(candidate.evidence().headSha())
                && livePush.baseSha().equals(candidate.evidence().baseSha());
        if (provisionalPushedHead && !pushedHead) {
            // The remote observer can win the Task stripe after the push has
            // reached GitHub but before its delivery is folded. The push
            // callback owns this transition; re-observe after it records the
            // pushed head rather than stopping the still-live Episode.
            return ObservationDisposition.DEFER_UNTIL_PUSH_DELIVERY;
        }
        if (episode != null && !pushedHead
                && (!episode.subjectHeadSha().equals(
                        candidate.evidence().headSha())
                    || !episode.subjectBaseSha().equals(
                        candidate.evidence().baseSha()))) {
            store.stopCiEpisode(
                    episode, "Remote subject changed before repair completed", now);
            episode = null;
        }
        switch (candidate.ciEvaluation().outcome()) {
            case WAITING -> {
                return ObservationDisposition.CONTINUE;
            }
            case ACCEPTED -> {
                if (episode != null) {
                    store.cancelPendingCiTurnIntents(
                            episode.id(), "fresh Remote CI accepted", now);
                    if (pushedHead
                            && episode.lastPushResultEvaluationId() == null) {
                        store.recordCiPushResult(
                                episode, candidate.evidence().ciEvaluationId());
                    }
                    store.succeedCiEpisode(
                            episode, candidate.evidence().ciEvaluationId(), now);
                }
                return ObservationDisposition.CONTINUE;
            }
            case FAILED -> {
                if (episode != null
                        && store.hasPendingCiTurnIntent(episode.id())) {
                    return continueRepair(candidate, episode, now);
                }
                Classification observedClassification = null;
                if (episode == null) {
                    if (store.hasSuppressedCiSubject(
                            candidate.context().stageId(),
                            candidate.evidence().headSha(),
                            candidate.evidence().baseSha())) {
                        return ObservationDisposition.CONTINUE;
                    }
                    observedClassification = requireNonNull(
                            classifier.classify(candidate),
                            "CI failure classification is null");
                    episode = store.openCiEpisode(
                            candidate.context(), candidate.evidence(),
                            observedClassification.name(), budgets, now);
                }
                else if (pushedHead
                        && episode.lastPushResultEvaluationId() == null) {
                    store.recordCiPushResult(
                            episode, candidate.evidence().ciEvaluationId());
                }
                else if (Classification.UNKNOWN.name().equals(
                        episode.classification())) {
                    observedClassification = requireNonNull(
                            classifier.classify(candidate),
                            "CI failure classification is null");
                    if (observedClassification != Classification.UNKNOWN) {
                        if (store.hasLiveCiOperation(episode.id())) {
                            return ObservationDisposition.CONTINUE;
                        }
                        episode = store.supersedeUnknownCiEpisode(
                                episode, candidate.context(),
                                candidate.evidence(),
                                observedClassification.name(),
                                digest(write(candidate.observation()
                                        .ciProvenance())),
                                now);
                    }
                }
                return continueRepair(candidate, episode, now);
            }
        }
        return ObservationDisposition.CONTINUE;
    }

    private ObservationDisposition continueRepair(
            Candidate candidate, CiEpisode episode, Instant now)
    {
        if (store.hasLiveCiOperation(episode.id())) {
            return ObservationDisposition.CONTINUE;
        }
        Classification classification = Classification.valueOf(
                episode.classification());
        if (classification == Classification.BASE_DETERMINISTIC) {
            if (episode.fixAttemptCount() >= episode.fixAttemptLimit()
                    || episode.pushCount() >= episode.pushLimit()) {
                store.exhaustCiEpisode(
                        episode, candidate.evidence().ciEvaluationId(), now);
                return ObservationDisposition.CONTINUE;
            }
            return startFreshDeterministicRepair(candidate, episode, now);
        }
        if (store.hasOpenCiBlocker(episode.id())) {
            return ObservationDisposition.CONTINUE;
        }
        if (classification == Classification.FLAKY
                || classification == Classification.INFRASTRUCTURE) {
            if (episode.rerunCount() < episode.rerunLimit()) {
                RemoteContext context = store.requireRemoteContext(
                        episode.taskId(), episode.stageId());
                store.insertCiRerun(context, episode, now);
                return ObservationDisposition.CONTINUE;
            }
            store.exhaustCiEpisode(
                    episode, candidate.evidence().ciEvaluationId(), now);
            return ObservationDisposition.CONTINUE;
        }
        if (episode.fixAttemptCount() >= episode.fixAttemptLimit()
                || episode.pushCount() >= episode.pushLimit()) {
            store.exhaustCiEpisode(
                    episode, candidate.evidence().ciEvaluationId(), now);
            return ObservationDisposition.CONTINUE;
        }
        return startFreshDeterministicRepair(candidate, episode, now);
    }

    private ObservationDisposition startFreshDeterministicRepair(
            Candidate candidate, CiEpisode episode, Instant now)
    {
        CiTurnFreshness proof = store.authorizeCiTurn(candidate, episode, now)
                .orElse(null);
        if (proof == null) {
            return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
        }
        switch (proof.intentKind()) {
            case "NO_CHANGE_CONTINUATION" -> deterministicRepairs
                    .startPendingCiNoChangeContinuationInCommand(episode);
            case "NEXT_FIX" -> deterministicRepairs
                    .startPendingCiNextFixInCommand(episode);
            case "MANUAL_BASE_REPAIR" -> deterministicRepairs
                    .startPendingManualCiFixInCommand(episode);
            case "STEERING" -> {
                return ObservationDisposition.DEFER_UNTIL_BRANCH_FRESH;
            }
            case "OBSERVED_FAILURE" ->
                    deterministicRepairs.startInCommand(candidate, episode);
            default -> throw new IllegalStateException(
                    "Unsupported CI freshness intent " + proof.intentKind());
        }
        return ObservationDisposition.CONTINUE;
    }

    public DispatchTicket.DeliveryReceipt deliverRerun(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !"REMOTE_CI_RERUN_RESULT".equals(owner.callbackRoute())
                || !expectedFence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED, "CI rerun owner/fence is stale");
        }
        String taskId = store.requireEffectTaskId(expectedFence.operationId());
        return commands.execute(taskId, () -> deliverRerunInCommand(
                owner, expectedFence, rawResult));
    }

    public DispatchTicket.DeliveryReceipt deliverEffect(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !expectedFence.equals(rawResult.fence())
                || !"REMOTE_CI_VALIDATION_RESULT".equals(
                        owner.callbackRoute())
                    && !"REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT".equals(
                        owner.callbackRoute())
                    && !"REMOTE_CI_PUSH_RESULT".equals(
                        owner.callbackRoute())) {
            return receipt(SUPERSEDED, "CI effect owner/fence is stale");
        }
        String taskId = store.requireEffectTaskId(expectedFence.operationId());
        return commands.execute(taskId, () -> deliverEffectInCommand(
                owner, expectedFence, rawResult));
    }

    public CiEpisode extendBudget(
            String taskId,
            String episodeId,
            String commandId,
            int rerunDelta,
            int fixDelta,
            int pushDelta,
            String actor,
            String reason)
    {
        return changeBudget(
                taskId, episodeId, commandId, "EXTEND",
                rerunDelta, fixDelta, pushDelta, actor, reason);
    }

    public CiEpisode continueWithPerPushApproval(
            String taskId,
            String episodeId,
            String commandId,
            String actor,
            String reason)
    {
        return changeBudget(
                taskId, episodeId, commandId, "PER_PUSH_APPROVAL",
                0, 1, 1, actor, reason);
    }

    public CiEpisode manualTakeover(
            String taskId,
            String episodeId,
            String commandId,
            String actor,
            String reason)
    {
        return control(
                taskId, episodeId, commandId, "MANUAL_TAKEOVER",
                actor, reason);
    }

    public CiEpisode stopAutomation(
            String taskId,
            String episodeId,
            String commandId,
            String actor,
            String reason)
    {
        return control(
                taskId, episodeId, commandId, "STOP_AUTOMATION", actor, reason);
    }

    public CiEpisode startBaseRepair(
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
            CiEpisode episode = store.requireCiEpisode(taskId, episodeId);
            if (!Classification.BASE_DETERMINISTIC.name().equals(
                    episode.classification())) {
                throw new IllegalStateException(
                        "Only a proven base-owned CI Episode accepts base repair");
            }
            deterministicRepairs.startBaseRepairInCommand(
                    episode, blockerId, commandId, actor, reason);
            return store.requireCiEpisode(taskId, episodeId);
        });
    }

    public CiEpisode retryNoChange(
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
        return commands.execute(taskId, () -> store.authorizeCiNoChangeRetry(
                taskId, episodeId, blockerId, commandId, actor, reason,
                clock.instant()));
    }

    private CiEpisode changeBudget(
            String taskId,
            String episodeId,
            String commandId,
            String kind,
            int rerunDelta,
            int fixDelta,
            int pushDelta,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        if (rerunDelta < 0 || fixDelta < 0 || pushDelta < 0
                || rerunDelta + fixDelta + pushDelta == 0) {
            throw new IllegalArgumentException(
                    "CI budget extension must add a positive budget");
        }
        return commands.execute(taskId, () -> store.changeCiBudget(
                taskId, episodeId, commandId, kind, rerunDelta, fixDelta,
                pushDelta, actor, reason, clock.instant()));
    }

    private CiEpisode control(
            String taskId,
            String episodeId,
            String commandId,
            String kind,
            String actor,
            String reason)
    {
        requireText(taskId, "taskId");
        requireText(episodeId, "episodeId");
        requireText(commandId, "commandId");
        requireText(actor, "actor");
        requireText(reason, "reason");
        return commands.execute(taskId, () -> store.controlCiEpisode(
                taskId, episodeId, commandId, kind, actor, reason,
                clock.instant()));
    }

    private DispatchTicket.DeliveryReceipt deliverRerunInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        String rawDigest = digest(write(rawResult));
        EffectDeliveryReceipt duplicate = store.findCiEffectReceipt(
                        expectedFence.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawDigest())) {
                throw new IllegalStateException(
                        "CI rerun was redelivered with different evidence");
            }
            return receipt(duplicate.acceptance(), duplicate.rawOutcome());
        }

        CiEffectDelivery context = store.requireCiEffectDelivery(
                expectedFence.operationId());
        boolean exact = owner.id().equals(context.stageId())
                && "RERUN".equals(context.kind())
                && matches(expectedFence, context);
        DispatchTicket.Acceptance acceptance = exact && context.current()
                ? ACCEPTED : SUPERSEDED;
        RemoteEffectOperationHandler.Result effect = rawResult.outcome() == SUCCEEDED
                ? decode(rawResult.payloadJson()) : null;
        boolean succeeded = acceptance == ACCEPTED
                && effect != null
                && effect.disposition()
                    == RemoteEffectOperationHandler.Disposition.SUCCEEDED;
        store.finishCiRerun(
                context, rawResult.outcome().name(), rawDigest,
                acceptance.name(), succeeded,
                effect == null ? rawResult.evidenceJson() : effect.evidence(),
                effect == null ? rawResult.error() : effect.error(),
                clock.instant());
        return receipt(acceptance, succeeded ? "awaiting CI" : "rerun failed");
    }

    private DispatchTicket.DeliveryReceipt deliverEffectInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
    {
        String rawDigest = digest(write(rawResult));
        EffectDeliveryReceipt duplicate = store.findCiEffectReceipt(
                        expectedFence.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!rawDigest.equals(duplicate.rawDigest())) {
                throw new IllegalStateException(
                        "CI effect was redelivered with different evidence");
            }
            return receipt(duplicate.acceptance(), duplicate.rawOutcome());
        }
        CiEffectDelivery context = store.requireCiEffectDelivery(
                expectedFence.operationId());
        String expectedRoute = switch (context.operationKind()) {
            case RemoteEffectOperationHandler.VALIDATE_CI_REPAIR ->
                    "REMOTE_CI_VALIDATION_RESULT";
            case RemoteEffectOperationHandler
                    .REWRITE_VALIDATE_BASE_CI_REPAIR ->
                    "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT";
            case RemoteEffectOperationHandler.PUSH_CI_REPAIR ->
                    "REMOTE_CI_PUSH_RESULT";
            default -> null;
        };
        boolean exact = owner.id().equals(context.stageId())
                && Objects.equals(expectedRoute, owner.callbackRoute())
                && matches(expectedFence, context);
        DispatchTicket.Acceptance acceptance = exact && context.current()
                ? ACCEPTED : SUPERSEDED;
        RemoteEffectOperationHandler.Result result = rawResult.outcome() == SUCCEEDED
                ? decode(rawResult.payloadJson()) : null;
        if (result != null
                && !context.operationId().equals(result.operationId())) {
            throw new IllegalArgumentException(
                    "CI result belongs to another Operation");
        }
        store.finishCiEffect(
                context, rawResult.outcome().name(), rawDigest,
                acceptance.name(), result, clock.instant());
        CiEpisode episode = store.requireCiEpisode(
                context.taskId(), context.episodeId());
        if (acceptance == SUPERSEDED) {
            store.stopCiEpisode(
                    episode, "Remote subject changed before CI effect delivery",
                    clock.instant());
            return receipt(SUPERSEDED, "stale CI subject");
        }
        if (rawResult.outcome() != SUCCEEDED || result == null) {
            store.blockCiEpisode(
                    episode, "CI_REPAIR_EFFECT_FAILED",
                    "CI repair effect failed",
                    "{\"operationId\":\"" + escape(context.operationId())
                            + "\"}", clock.instant());
            return receipt(ACCEPTED, "CI effect failed");
        }
        if ("VALIDATE".equals(context.kind())) {
            deterministicRepairs.acceptValidationInCommand(episode, result);
            return receipt(ACCEPTED,
                    result.disposition()
                            == RemoteEffectOperationHandler.Disposition.SUCCEEDED
                            ? "CI validation passed" : "CI validation failed");
        }
        if (result.disposition()
                != RemoteEffectOperationHandler.Disposition.SUCCEEDED) {
            store.blockCiEpisode(
                    episode, "CI_REPAIR_PUSH_FAILED", "CI repair push failed",
                    "{\"operationId\":\"" + escape(context.operationId())
                            + "\"}", clock.instant());
            return receipt(ACCEPTED, "CI push failed");
        }
        requestObservationAfterPush(context);
        return receipt(ACCEPTED, "awaiting pushed-head CI observation");
    }

    private void requestObservationAfterPush(CiEffectDelivery context)
    {
        if (store.findLiveObservation(context.stageId()).isPresent()) {
            return;
        }
        store.insertObservation(
                store.requireRemoteContext(context.taskId(), context.stageId()),
                clock.instant());
    }

    private static boolean matches(
            DispatchTicket.OperationFence fence, CiEffectDelivery context)
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
            throw new IllegalArgumentException("CI rerun result is invalid", e);
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize CI evidence", e);
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String result)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                "{\"schema\":\"REMOTE_CI_DELIVERY_V1\","
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
    public interface FailureClassifier
    {
        Classification classify(Candidate candidate);
    }

    @FunctionalInterface
    public interface DeterministicRepairPort
    {
        /** Starts the exact StageTurn -> validation -> push arm. */
        void startInCommand(Candidate candidate, CiEpisode episode);

        /** Starts one explicitly authorized repair of a proven base failure. */
        default void startBaseRepairInCommand(
                CiEpisode episode,
                String blockerId,
                String commandId,
                String actor,
                String reason)
        {
            throw new IllegalStateException(
                    "Explicit CI base repair is not configured");
        }

        /** Continues the same finite arm after canonical validation. */
        default void acceptValidationInCommand(
                CiEpisode episode, RemoteEffectOperationHandler.Result result)
        {
            throw new IllegalStateException(
                    "Deterministic CI validation continuation is not configured");
        }

        default boolean startPendingCiNoChangeContinuationInCommand(
                CiEpisode episode)
        {
            return false;
        }

        default boolean startPendingCiNextFixInCommand(CiEpisode episode)
        {
            return false;
        }

        default boolean startPendingManualCiFixInCommand(CiEpisode episode)
        {
            return false;
        }
    }

    public enum Classification
    {
        FLAKY,
        INFRASTRUCTURE,
        TASK_DETERMINISTIC,
        TASK_BRANCH_REPAIRABLE,
        BASE_DETERMINISTIC,
        UNKNOWN
    }

    public enum ObservationDisposition
    {
        CONTINUE,
        DEFER_UNTIL_PUSH_DELIVERY,
        DEFER_UNTIL_BRANCH_FRESH
    }
}
