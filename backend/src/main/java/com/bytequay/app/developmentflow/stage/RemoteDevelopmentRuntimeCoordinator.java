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
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.AuthorityKind;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectCompletion;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.EffectDeliveryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.MarkReadyDeliveryContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.MarkReadyDispatch;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ReadinessEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RuntimeDeliveryReceipt;
import com.bytequay.app.domain.PR;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.digest;
import static com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator.id;
import static java.util.Objects.requireNonNull;

/** Synchronous owner use cases for durable Remote effect delivery and gates. */
public final class RemoteDevelopmentRuntimeCoordinator
{
    public static final String EFFECT_CALLBACK = "REMOTE_FEEDBACK_EFFECT_RESULT";
    public static final String MARK_READY_CALLBACK = "REMOTE_MARK_READY_RESULT";

    private static final String ACTOR = "v2-remote-runtime";

    private final TaskCommandExecutor commands;
    private final RemoteDevelopmentStageManager remote;
    private final SqliteRemoteDevelopmentRuntimeStore store;
    private final ObjectMapper json;
    private final PRService prs;
    private final Clock clock;

    public RemoteDevelopmentRuntimeCoordinator(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteDevelopmentRuntimeStore store,
            ObjectMapper json,
            PRService prs,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.store = requireNonNull(store, "store is null");
        this.json = requireNonNull(json, "json is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public DispatchTicket.DeliveryReceipt deliverEffect(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(result, "result is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !EFFECT_CALLBACK.equals(owner.callbackRoute())
                || !fence.equals(result.fence())) {
            return receipt(SUPERSEDED, "Remote feedback effect owner is stale");
        }
        EffectDeliveryContext context = store.requireEffectDelivery(
                fence.operationId());
        return commands.execute(context.taskId(), () ->
                deliverEffectInCommand(owner, fence, result));
    }

    public DispatchTicket.DeliveryReceipt deliverMarkReady(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(result, "result is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !MARK_READY_CALLBACK.equals(owner.callbackRoute())
                || !fence.equals(result.fence())) {
            return receipt(SUPERSEDED, "Mark-ready owner is stale");
        }
        MarkReadyDeliveryContext context = store.requireMarkReadyDelivery(
                fence.operationId());
        return commands.execute(context.taskId(), () ->
                deliverMarkReadyInCommand(owner, fence, result));
    }

    public Optional<EffectCompletion> resumeFeedbackCompletionInCommand(
            String taskId, String batchId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        Optional<EffectCompletion> completion =
                store.finishBatchIfEffectsProven(batchId, clock.instant());
        completion.ifPresent(this::completeFeedbackStage);
        return completion;
    }

    /** Explicit human/policy entry point for one exact Draft-to-ready effect. */
    public Optional<MarkReadyDispatch> startMarkReady(
            String taskId,
            String stageId,
            MarkReadyAuthority authority,
            String actorId,
            int attemptLimit)
    {
        return commands.execute(taskId, () -> startMarkReadyInCommand(
                taskId, stageId, authority, actorId, attemptLimit));
    }

    public Optional<MarkReadyDispatch> startMarkReadyInCommand(
            String taskId,
            String stageId,
            MarkReadyAuthority authority,
            String actorId,
            int attemptLimit)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        requireNonNull(authority, "authority is null");
        if (attemptLimit < 1) {
            throw new IllegalArgumentException("attemptLimit must be positive");
        }
        if (authority == MarkReadyAuthority.HUMAN
                && (actorId == null || actorId.isBlank())) {
            throw new IllegalArgumentException(
                    "Human mark-ready requires an actor");
        }
        if (authority == MarkReadyAuthority.POLICY && actorId != null) {
            throw new IllegalArgumentException(
                    "Policy mark-ready cannot impersonate an actor");
        }
        RemoteContext context = store.requireContext(taskId, stageId);
        Optional<MarkReadyDispatch> existing = store.findMarkReadyDispatch(
                taskId, stageId, context.headSha(), context.baseSha());
        if (existing.isPresent()) {
            return existing;
        }
        if (!"AWAITING_READY".equals(context.checkpoint())) {
            return Optional.empty();
        }
        String subject = context.snapshotId();
        return Optional.of(store.authorizeMarkReady(
                id("mark-ready-authorization", subject),
                id("mark-ready-operation", subject), taskId, stageId,
                authority == MarkReadyAuthority.HUMAN
                        ? AuthorityKind.MANUAL
                        : AuthorityKind.AUTO_APPROVE_POLICY,
                actorId, attemptLimit, clock.instant()));
    }

    /** Proves readiness and advances the Stage only when every gate is true. */
    public ReadinessEvidence proveReadiness(
            String evidenceId,
            String taskId,
            String stageId,
            String automationEligibilityEvidenceId,
            String evidence)
    {
        return commands.execute(taskId, () -> proveReadinessInCommand(
                evidenceId, taskId, stageId, automationEligibilityEvidenceId,
                evidence));
    }

    public ReadinessEvidence proveReadinessInCommand(
            String evidenceId,
            String taskId,
            String stageId,
            String automationEligibilityEvidenceId,
            String evidence)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReadinessEvidence readiness = store.proveReadiness(
                evidenceId, taskId, stageId, automationEligibilityEvidenceId,
                evidence, clock.instant());
        if (readiness.ready()) {
            RemoteContext context = store.requireContext(taskId, stageId);
            CommandResult<StageManager.State> accepted =
                    remote.acceptReadinessEvidenceInCommand(
                            new RemoteDevelopmentStageManager.RemoteGateCommand(
                                    stageCommand(context,
                                            id("accept-remote-readiness", evidenceId)),
                                    evidenceId, readiness.headSha(),
                                    readiness.baseSha()));
            if (accepted.disposition()
                    == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException(
                        "Fresh readiness became stale inside its Task command");
            }
        }
        return readiness;
    }

    private DispatchTicket.DeliveryReceipt deliverEffectInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result)
    {
        String rawDigest = digest(write(result));
        RuntimeDeliveryReceipt duplicate = store.findRuntimeDeliveryReceipt(
                        fence.operationId())
                .orElse(null);
        if (duplicate != null) {
            requireSameDelivery(duplicate, EFFECT_CALLBACK, rawDigest);
            return receipt(
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance()),
                    duplicate.evidence());
        }
        EffectDeliveryContext context = store.requireEffectDelivery(
                fence.operationId());
        requireEffectFence(owner, fence, context);
        Instant now = clock.instant();
        DispatchTicket.Acceptance acceptance;
        String evidence;
        if (!context.current()) {
            acceptance = SUPERSEDED;
            evidence = "Remote feedback effect no longer owns the exact head";
        }
        else if (result.outcome() != DispatchTicket.Outcome.SUCCEEDED) {
            acceptance = ACCEPTED;
            evidence = "Remote feedback effect ended " + result.outcome();
        }
        else if (!"SUCCEEDED".equals(context.effectStatus())) {
            throw new IllegalStateException(
                    "Successful effect delivery lacks durable effect proof");
        }
        else {
            acceptance = ACCEPTED;
            store.dispatchNextEffect(context.batchId(), now);
            Optional<EffectCompletion> completion =
                    store.finishBatchIfEffectsProven(context.batchId(), now);
            completion.ifPresent(this::completeFeedbackStage);
            evidence = completion.map(value -> "feedback-complete:" + value.batchId())
                    .orElse("effect-complete:" + context.effectId());
        }
        RuntimeDeliveryReceipt recorded = new RuntimeDeliveryReceipt(
                fence.operationId(), EFFECT_CALLBACK, rawDigest,
                acceptance.name(), evidence, now);
        store.insertRuntimeDeliveryReceipt(recorded);
        return receipt(acceptance, evidence);
    }

    public enum MarkReadyAuthority
    {
        HUMAN,
        POLICY
    }

    private DispatchTicket.DeliveryReceipt deliverMarkReadyInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result)
    {
        String rawDigest = digest(write(result));
        RuntimeDeliveryReceipt duplicate = store.findRuntimeDeliveryReceipt(
                        fence.operationId())
                .orElse(null);
        if (duplicate != null) {
            requireSameDelivery(duplicate, MARK_READY_CALLBACK, rawDigest);
            return receipt(
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance()),
                    duplicate.evidence());
        }
        MarkReadyDeliveryContext context = store.requireMarkReadyDelivery(
                fence.operationId());
        requireMarkReadyFence(owner, fence, context);
        Instant now = clock.instant();
        DispatchTicket.Acceptance acceptance;
        String evidence;
        if (!context.current()) {
            acceptance = SUPERSEDED;
            evidence = "Mark-ready result no longer owns the exact head";
        }
        else if (result.outcome() != DispatchTicket.Outcome.SUCCEEDED) {
            acceptance = ACCEPTED;
            evidence = "Mark-ready ended " + result.outcome();
        }
        else if (!"SUCCEEDED".equals(context.status())) {
            throw new IllegalStateException(
                    "Successful mark-ready delivery lacks accepted observation");
        }
        else {
            RemoteContext current = store.requireContext(
                    context.taskId(), context.stageId());
            CommandResult<StageManager.State> completed =
                    remote.completeMarkReadyInCommand(
                            new RemoteDevelopmentStageManager.RemoteGateCommand(
                                    stageCommand(current,
                                            id("complete-mark-ready", context.id())),
                                    context.id(), context.headSha(), context.baseSha()));
            acceptance = completed.disposition()
                    == CommandResult.Disposition.SUPERSEDED
                    ? SUPERSEDED : ACCEPTED;
            if (acceptance == ACCEPTED) {
                projectRemoteOpen(context.taskId());
            }
            evidence = "mark-ready-complete:" + context.id();
        }
        RuntimeDeliveryReceipt recorded = new RuntimeDeliveryReceipt(
                fence.operationId(), MARK_READY_CALLBACK, rawDigest,
                acceptance.name(), evidence, now);
        store.insertRuntimeDeliveryReceipt(recorded);
        return receipt(acceptance, evidence);
    }

    /** The Remote Stage owns acceptance; PRService owns the stable PR edge. */
    private void projectRemoteOpen(String taskId)
    {
        PR pr = prs.findByTask(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "Accepted mark-ready has no stable Task PR"));
        if (PR.STATUS_REMOTE_OPEN.equals(pr.status())) {
            return;
        }
        if (!PR.STATUS_REMOTE_DRAFTED.equals(pr.status())) {
            throw new IllegalStateException(
                    "Accepted mark-ready cannot project PR status "
                            + pr.status());
        }
        prs.transition(pr.id(), PR.STATUS_REMOTE_OPEN, ACTOR);
    }

    private void completeFeedbackStage(EffectCompletion completion)
    {
        RemoteContext context = store.requireContext(
                completion.taskId(), completion.stageId());
        CommandResult<StageManager.State> stage = remote.completeRemoteFeedbackInCommand(
                new RemoteDevelopmentStageManager.FeedbackCompletionCommand(
                        stageCommand(context,
                                id("complete-remote-feedback", completion.batchId())),
                        completion.batchId(), completion.pushed(),
                        completion.resultHeadSha(), completion.resultSnapshotId()));
        if (stage.disposition() == CommandResult.Disposition.SUPERSEDED) {
            throw new IllegalStateException(
                    "Completed Remote feedback became stale inside its Task command");
        }
    }

    private static StageManager.Command stageCommand(
            RemoteContext context, String commandId)
    {
        return new StageManager.Command(
                commandId, ACTOR, context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(), context.stageVersion());
    }

    private static void requireEffectFence(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            EffectDeliveryContext context)
    {
        if (!context.stageId().equals(owner.id())
                || !context.operationId().equals(fence.operationId())
                || !Objects.equals(context.taskEpoch(), fence.taskEpoch())
                || !context.stageId().equals(fence.stageId())
                || !Objects.equals(context.stageGeneration(), fence.stageGeneration())
                || !context.headSha().equals(fence.expectedHeadSha())
                || !context.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Remote feedback delivery differs from its persisted fence");
        }
    }

    private static void requireMarkReadyFence(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            MarkReadyDeliveryContext context)
    {
        if (!context.stageId().equals(owner.id())
                || !context.operationId().equals(fence.operationId())
                || !Objects.equals(context.taskEpoch(), fence.taskEpoch())
                || !context.stageId().equals(fence.stageId())
                || !Objects.equals(context.stageGeneration(), fence.stageGeneration())
                || !context.headSha().equals(fence.expectedHeadSha())
                || !context.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Mark-ready delivery differs from its persisted fence");
        }
    }

    private static void requireSameDelivery(
            RuntimeDeliveryReceipt receipt, String route, String rawDigest)
    {
        if (!route.equals(receipt.callbackRoute())
                || !rawDigest.equals(receipt.rawResultDigest())) {
            throw new IllegalStateException(
                    "Remote result operation was reused with different evidence");
        }
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize Remote result", e);
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance, "{\"schema\":\"REMOTE_RUNTIME_V1\",\"result\":\""
                        + evidence.replace("\\", "\\\\").replace("\"", "\\\"")
                        + "\"}");
    }
}
