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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static java.util.Objects.requireNonNull;

/** Human command boundary and restart-safe finalizer for V2 PR actions. */
public final class V2UserRemoteActionRuntime
        implements ExecutionPorts.ResultDeliveryPort
{
    private static final int RECOVERY_LIMIT = 100;

    private final SqliteUserRemoteActionStore store;
    private final PRService prs;
    private final InvestigationReviewService investigationReviews;
    private final ObjectReader resultReader;
    private final Clock clock;

    public V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews)
    {
        this(store, prs, json, investigationReviews, Clock.systemUTC());
    }

    V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.resultReader = requireNonNull(json, "json is null")
                .readerFor(EffectResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.investigationReviews = requireNonNull(
                investigationReviews, "investigationReviews is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Action dequeue(String commandId, String taskId, String prId)
    {
        return authorize(commandId, taskId, prId, ActionKind.DEQUEUE,
                new ActionPayload(1, null, null, null, List.of()), null);
    }

    public Action deleteRemoteBranch(
            String commandId, String taskId, String prId, String branchName)
    {
        return authorize(commandId, taskId, prId,
                ActionKind.DELETE_REMOTE_BRANCH,
                new ActionPayload(1, null, null, branchName, List.of()), null);
    }

    public Action postTopLevelComment(
            String commandId,
            String taskId,
            String prId,
            String body,
            HandledAction handledAction)
    {
        return authorize(commandId, taskId, prId,
                ActionKind.POST_TOP_LEVEL_COMMENT,
                new ActionPayload(1, body, null, null, List.of()),
                handledAction == null ? null : handledAction.name());
    }

    public Action submitReview(
            String commandId,
            String taskId,
            String prId,
            String reviewAction,
            String body,
            List<PRComment> drafts,
            HandledAction handledAction)
    {
        List<FrozenDraft> frozen = drafts.stream()
                .map(V2UserRemoteActionRuntime::freeze)
                .toList();
        return authorize(commandId, taskId, prId, ActionKind.SUBMIT_REVIEW,
                new ActionPayload(1, body, reviewAction, null, frozen),
                handledAction == null ? null : handledAction.name());
    }

    private Action authorize(
            String commandId,
            String taskId,
            String prId,
            ActionKind kind,
            ActionPayload payload,
            String handledAction)
    {
        try {
            return store.authorize(new AuthorizationRequest(
                    taskId, commandId, prId, kind, payload, handledAction),
                    clock.instant());
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 remote action no longer matches the exact PR/head",
                    failure);
        }
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TASK
                || !UserRemoteActionOperationHandler.CALLBACK_ROUTE.equals(
                        owner.callbackRoute())
                || !fence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED,
                    "V2 user remote action delivery fence is stale");
        }

        Action action = store.require(fence.operationId());
        if (!matches(owner, fence, action)) {
            return receipt(REJECTED,
                    "V2 user remote action differs from durable authorization");
        }
        if (isGenericCanceled(rawResult)) {
            if (action.status() != ActionStatus.CANCELED) {
                action = store.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.CANCELED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.CANCELED
                    ? receipt(ACCEPTED, "V2 user remote action cancellation accepted")
                    : receipt(REJECTED, "V2 user remote action cancellation is stale");
        }
        if (isGenericFailure(rawResult)) {
            if (action.status() != ActionStatus.ABANDONED) {
                action = store.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.ABANDONED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.ABANDONED
                    ? receipt(ACCEPTED, "V2 user remote action failure accepted")
                    : receipt(REJECTED, "V2 user remote action failure is stale");
        }
        if (rawResult.outcome() != DispatchTicket.Outcome.SUCCEEDED
                || action.status() != ActionStatus.SUCCEEDED) {
            return receipt(REJECTED,
                    "V2 user remote action lacks exact success evidence");
        }
        EffectResult result = decode(rawResult.payloadJson());
        if (!result.proven()
                || !Objects.equals(
                        action.externalEffectId(), result.externalEffectId())
                || !Objects.equals(action.evidence(), result.evidence())) {
            return receipt(REJECTED,
                    "V2 user remote action result differs from durable evidence");
        }
        return receipt(ACCEPTED, "V2 user remote action success accepted");
    }

    @Override
    public void afterDeliveryCommitted(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult,
            DispatchTicket.DeliveryReceipt receipt)
    {
        if (receipt.acceptance() == ACCEPTED) {
            finalizeAction(store.require(fence.operationId()));
        }
    }

    @Override
    public void recoverCommittedDeliveries(int limit)
    {
        for (Action action : store.findCommittedUnfinalized(
                Math.min(RECOVERY_LIMIT, Math.max(1, limit)))) {
            finalizeAction(action);
        }
    }

    private void finalizeAction(Action action)
    {
        if (action.status() == ActionStatus.SUCCEEDED) {
            if (action.kind() == ActionKind.SUBMIT_REVIEW) {
                Map<String, PRComment> current = prs.comments(action.prId())
                        .stream()
                        .collect(Collectors.toMap(
                                PRComment::id, Function.identity()));
                List<FrozenDraft> exact = new ArrayList<>();
                for (FrozenDraft frozen : action.payload().drafts()) {
                    PRComment draft = current.get(frozen.id());
                    if (draft != null && exactDraft(action, draft, frozen)) {
                        exact.add(frozen);
                        if (draft.publishedAt() == null) {
                            prs.markPublished(frozen.id(), clock.instant());
                        }
                    }
                }
                if (exact.size() == action.payload().drafts().size()) {
                    investigationReviews.recordPublished(
                            action.prId(), action.payload().reviewAction(),
                            exact.stream().map(FrozenDraft::findingId)
                                    .filter(Objects::nonNull).distinct().toList(),
                            exact.stream().map(FrozenDraft::id).toList());
                }
            }
            if (action.kind() == ActionKind.DELETE_REMOTE_BRANCH) {
                PR pr = prs.findById(action.prId())
                        .orElseThrow(() -> new IllegalStateException(
                                "V2 action PR is missing: " + action.prId()));
                if (pr.branchDeletedAt() == null) {
                    prs.recordBranchDeleted(action.prId());
                }
            }
            markHandled(action);
        }
        store.markFinalized(action.id(), action.status(), clock.instant());
    }

    private void markHandled(Action action)
    {
        if (action.handledAction() == null) {
            return;
        }
        PR pr = prs.findById(action.prId()).orElse(null);
        if (pr != null && !pr.isTerminal()
                && pr.githubSync() != null
                && pr.githubSync().watchReason()
                        == PullRequest.Origin.REVIEW_REQUESTED) {
            prs.markHandled(
                    pr.id(), HandledAction.valueOf(action.handledAction()));
        }
    }

    private static boolean matches(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            Action action)
    {
        return owner.id().equals(action.taskId())
                && fence.taskEpoch() != null
                && fence.taskEpoch() == action.taskEpoch()
                && action.stageId().equals(fence.stageId())
                && fence.stageGeneration() != null
                && fence.stageGeneration() == action.stageGeneration()
                && action.operationId().equals(fence.operationId())
                && fence.attempt() == action.semanticAttempt()
                && fence.expectedCodeFingerprint() == null
                && action.headSha().equals(fence.expectedHeadSha())
                && action.baseSha().equals(fence.expectedBaseSha());
    }

    private EffectResult decode(String payload)
    {
        try {
            return resultReader.readValue(requireNonNull(payload, "payload is null"));
        }
        catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid V2 user remote action result", failure);
        }
    }

    private static boolean isGenericCanceled(DispatchTicket.DispatchResult result)
    {
        return result.outcome() == DispatchTicket.Outcome.CANCELED
                && result.payloadJson() == null
                && "{}".equals(result.evidenceJson())
                && result.error() != null && !result.error().isBlank();
    }

    private static boolean isGenericFailure(DispatchTicket.DispatchResult result)
    {
        return result.outcome() == DispatchTicket.Outcome.FAILED
                && result.payloadJson() == null
                && "{}".equals(result.evidenceJson())
                && result.error() != null && !result.error().isBlank();
    }

    private static FrozenDraft freeze(PRComment draft)
    {
        String side = PRComment.SCOPE_FILE_LINE.equals(draft.scope())
                && draft.side() == null ? "RIGHT" : draft.side();
        return new FrozenDraft(
                draft.id(), draft.scope(), draft.filePath(), draft.lineNumber(),
                side, draft.startLine(), draft.startSide(), draft.body(),
                draft.findingId());
    }

    private static boolean exactDraft(
            Action action, PRComment draft, FrozenDraft frozen)
    {
        String side = PRComment.SCOPE_FILE_LINE.equals(draft.scope())
                && draft.side() == null ? "RIGHT" : draft.side();
        return action.prId().equals(draft.prId())
                && PRComment.ORIGIN_LOCAL.equals(draft.origin())
                && draft.parentCommentId() == null
                && draft.strippedOnPushAt() == null
                && draft.resolvedAt() == null
                && draft.dismissedAt() == null
                && Objects.equals(frozen.scope(), draft.scope())
                && Objects.equals(frozen.filePath(), draft.filePath())
                && Objects.equals(frozen.lineNumber(), draft.lineNumber())
                && Objects.equals(frozen.side(), side)
                && Objects.equals(frozen.startLine(), draft.startLine())
                && Objects.equals(frozen.startSide(), draft.startSide())
                && Objects.equals(frozen.body(), draft.body())
                && Objects.equals(frozen.findingId(), draft.findingId());
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String detail)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                """
                {"schema":"V2_USER_REMOTE_ACTION_DELIVERY_V1","detail":"%s"}
                """.formatted(detail).strip());
    }
}
