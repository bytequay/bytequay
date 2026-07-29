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
import com.bytequay.app.developmentflow.execution.remote.ReviewBuildCommentOperationHandler.CommentAction;
import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore.Projection;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewBuildCommentStore.ProposalView;
import com.bytequay.app.developmentflow.execution.remote.SqliteReviewPassPublicationStore.PublicationView;
import com.bytequay.app.developmentflow.execution.remote.SqliteUserRemoteActionStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.Action;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionKind;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionStatus;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.FrozenDraft;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final SqliteExternalPrActionStore externalActions;
    private final SqliteReviewBuildCommentStore reviewBuildComments;
    private final SqliteReviewPassPublicationStore reviewPassPublications;
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
        this(store, null, null, prs, json, investigationReviews,
                Clock.systemUTC(), null);
    }

    public V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            SqliteReviewBuildCommentStore reviewBuildComments,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews)
    {
        this(store, reviewBuildComments, null, prs, json,
                investigationReviews, Clock.systemUTC(), null);
    }

    public V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            SqliteReviewBuildCommentStore reviewBuildComments,
            SqliteReviewPassPublicationStore reviewPassPublications,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews)
    {
        this(store, reviewBuildComments, reviewPassPublications, prs, json,
                investigationReviews, Clock.systemUTC(), null);
    }

    public V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            SqliteExternalPrActionStore externalActions,
            SqliteReviewBuildCommentStore reviewBuildComments,
            SqliteReviewPassPublicationStore reviewPassPublications,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews)
    {
        this(store, reviewBuildComments, reviewPassPublications, prs, json,
                investigationReviews, Clock.systemUTC(), externalActions);
    }

    V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews,
            Clock clock)
    {
        this(store, null, null, prs, json, investigationReviews, clock, null);
    }

    V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            SqliteReviewBuildCommentStore reviewBuildComments,
            SqliteReviewPassPublicationStore reviewPassPublications,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews,
            Clock clock)
    {
        this(store, reviewBuildComments, reviewPassPublications, prs, json,
                investigationReviews, clock, null);
    }

    private V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            SqliteReviewBuildCommentStore reviewBuildComments,
            SqliteReviewPassPublicationStore reviewPassPublications,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews,
            Clock clock,
            SqliteExternalPrActionStore externalActions)
    {
        this.store = requireNonNull(store, "store is null");
        this.externalActions = externalActions;
        this.reviewBuildComments = reviewBuildComments;
        this.reviewPassPublications = reviewPassPublications;
        this.prs = requireNonNull(prs, "prs is null");
        this.resultReader = requireNonNull(json, "json is null")
                .readerFor(EffectResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.investigationReviews = requireNonNull(
                investigationReviews, "investigationReviews is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    V2UserRemoteActionRuntime(
            SqliteUserRemoteActionStore store,
            SqliteReviewBuildCommentStore reviewBuildComments,
            PRService prs,
            ObjectMapper json,
            InvestigationReviewService investigationReviews,
            Clock clock)
    {
        this(store, reviewBuildComments, null, prs, json,
                investigationReviews, clock, null);
    }

    public Optional<ProposalView> findReviewBuildCommentProposal(
            String reviewPassId)
    {
        return requireReviewBuildComments().findProposal(reviewPassId);
    }

    public ProposalView approveReviewBuildComments(
            String reviewPassId, String commandId)
    {
        try {
            return requireReviewBuildComments().approve(
                    reviewPassId, commandId, clock.instant());
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "review build comments no longer match the frozen proposal",
                    failure);
        }
    }

    public ProposalView discardReviewBuildComments(
            String reviewPassId, String commandId)
    {
        try {
            return requireReviewBuildComments().discard(
                    reviewPassId, commandId, clock.instant());
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "review build comment proposal already has a decision",
                    failure);
        }
    }

    public PublicationView publishReviewPass(
            String reviewPassId,
            String commandId,
            String reviewAction,
            List<String> findingIds)
    {
        try {
            return requireReviewPassPublications().authorize(
                    reviewPassId, commandId, reviewAction, findingIds,
                    clock.instant());
        }
        catch (DataAccessException | IllegalArgumentException |
                IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, failure.getMessage(), failure);
        }
    }

    /** Durable read projection used after UI or process restart. */
    public Optional<PublicationView> findReviewPassPublication(
            String reviewPassId)
    {
        return requireReviewPassPublications().findPublication(reviewPassId);
    }

    public Projection publishExternalReview(
            String commandId,
            String prId,
            String reviewId,
            String reviewAction,
            String body,
            List<PRComment> drafts,
            HandledAction handledAction)
    {
        List<FrozenDraft> frozen = drafts.stream()
                .map(V2UserRemoteActionRuntime::freeze)
                .toList();
        authorizeExternal(
                commandId, prId, reviewId, SemanticAction.SUBMIT_REVIEW,
                new ActionPayload(1, body, reviewAction, null, frozen),
                handledAction == null ? null : handledAction.name());
        return findExternalReviewPublication(prId).orElseThrow();
    }

    public Optional<Projection> findExternalReviewPublication(String prId)
    {
        return requireExternalActions().findLatestProjection(prId);
    }

    public Action authorizeExternal(
            String commandId,
            String prId,
            SemanticAction action,
            ActionPayload payload)
    {
        return authorizeExternal(
                commandId, prId, null, action, payload, null);
    }

    public Action authorizeExternal(
            String commandId,
            String prId,
            String reviewId,
            SemanticAction action,
            ActionPayload payload,
            String handledAction)
    {
        try {
            return requireExternalActions().authorize(
                    new SqliteExternalPrActionStore.AuthorizationRequest(
                            commandId, prId, reviewId, action, payload,
                            handledAction),
                    clock.instant());
        }
        catch (DataAccessException | IllegalArgumentException |
                IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "external remote action no longer matches the exact cached PR/head",
                    failure);
        }
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

    public Action rerunFailedChecks(
            String commandId, String taskId, String prId)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.RERUN_FAILED_CHECKS, ActionPayload.empty(), null);
    }

    public Action triggerCiViaEmptyCommit(
            String commandId, String taskId, String prId)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.TRIGGER_CI_EMPTY_COMMIT,
                ActionPayload.empty(), null);
    }

    public Action setDraft(
            String commandId, String taskId, String prId, boolean draft)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.SET_DRAFT_STATE,
                ActionPayload.selected(null, draft), null);
    }

    public Action updateTitle(
            String commandId, String taskId, String prId, String title)
    {
        return authorize(commandId, taskId, prId, SemanticAction.UPDATE_TITLE,
                ActionPayload.value(title == null ? null : title.strip()), null);
    }

    public Action updateBody(
            String commandId, String taskId, String prId, String body)
    {
        return authorize(commandId, taskId, prId, SemanticAction.UPDATE_BODY,
                ActionPayload.body(body), null);
    }

    public Action closePullRequest(
            String commandId, String taskId, String prId)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.CLOSE_PULL_REQUEST, ActionPayload.empty(), null);
    }

    public Action commentAndClose(
            String commandId,
            String taskId,
            String prId,
            String body)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.COMMENT_AND_CLOSE,
                ActionPayload.body(body), null);
    }

    public Action replyToReviewThread(
            String commandId,
            String taskId,
            String prId,
            long rootCommentId,
            String body)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.REPLY_REVIEW_THREAD,
                ActionPayload.targetBody(Long.toString(rootCommentId), body),
                null);
    }

    public Action editIssueComment(
            String commandId,
            String taskId,
            String prId,
            long commentId,
            String body)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.EDIT_ISSUE_COMMENT,
                ActionPayload.targetBody(Long.toString(commentId), body), null);
    }

    public Action editReviewComment(
            String commandId,
            String taskId,
            String prId,
            long commentId,
            String body)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.EDIT_REVIEW_COMMENT,
                ActionPayload.targetBody(Long.toString(commentId), body), null);
    }

    public Action deleteIssueComment(
            String commandId, String taskId, String prId, long commentId)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.DELETE_ISSUE_COMMENT,
                ActionPayload.target(Long.toString(commentId)), null);
    }

    public Action deleteReviewComment(
            String commandId, String taskId, String prId, long commentId)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.DELETE_REVIEW_COMMENT,
                ActionPayload.target(Long.toString(commentId)), null);
    }

    public Action addReviewer(
            String commandId, String taskId, String prId, String reviewer)
    {
        return authorize(commandId, taskId, prId, SemanticAction.ADD_REVIEWER,
                ActionPayload.value(strip(reviewer)), null);
    }

    public Action removeReviewer(
            String commandId, String taskId, String prId, String reviewer)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.REMOVE_REVIEWER,
                ActionPayload.value(strip(reviewer)), null);
    }

    public Action setAssignee(
            String commandId,
            String taskId,
            String prId,
            String login,
            boolean selected)
    {
        return authorize(commandId, taskId, prId, SemanticAction.SET_ASSIGNEE,
                ActionPayload.selected(strip(login), selected), null);
    }

    public Action setLabel(
            String commandId,
            String taskId,
            String prId,
            String label,
            boolean selected)
    {
        return authorize(commandId, taskId, prId, SemanticAction.SET_LABEL,
                ActionPayload.selected(strip(label), selected), null);
    }

    public Action createInlineComment(
            String commandId,
            String taskId,
            String prId,
            String body,
            String filePath,
            int lineNumber,
            String side,
            Integer startLine,
            String startSide)
    {
        String resolvedSide = upper(side);
        Integer resolvedStartLine = startLine != null && startLine != lineNumber
                ? startLine : null;
        String resolvedStartSide = resolvedStartLine == null ? null
                : startSide == null || startSide.isBlank()
                ? resolvedSide : upper(startSide);
        return authorize(commandId, taskId, prId,
                SemanticAction.CREATE_INLINE_COMMENT,
                ActionPayload.inlineComment(
                        body, filePath, lineNumber, resolvedSide,
                        resolvedStartLine, resolvedStartSide), null);
    }

    public Action addPullRequestReaction(
            String commandId, String taskId, String prId, String content)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.REACT_PULL_REQUEST,
                ActionPayload.value(content), null);
    }

    public Action addReviewCommentReaction(
            String commandId,
            String taskId,
            String prId,
            long commentId,
            String content)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.REACT_REVIEW_COMMENT,
                ActionPayload.targetValue(Long.toString(commentId), content),
                null);
    }

    public Action addIssueCommentReaction(
            String commandId,
            String taskId,
            String prId,
            long commentId,
            String content)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.REACT_ISSUE_COMMENT,
                ActionPayload.targetValue(Long.toString(commentId), content),
                null);
    }

    public Action setThreadResolved(
            String commandId,
            String taskId,
            String prId,
            long rootCommentId,
            boolean resolved)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.SET_THREAD_RESOLUTION,
                ActionPayload.targetSelected(
                        Long.toString(rootCommentId), resolved), null);
    }

    private Action authorize(
            String commandId,
            String taskId,
            String prId,
            ActionKind kind,
            ActionPayload payload,
            String handledAction)
    {
        return authorize(commandId, taskId, prId,
                SemanticAction.legacy(kind), payload, handledAction);
    }

    private Action authorize(
            String commandId,
            String taskId,
            String prId,
            SemanticAction semanticAction,
            ActionPayload payload,
            String handledAction)
    {
        try {
            return store.authorize(new AuthorizationRequest(
                    taskId, commandId, prId, semanticAction, payload,
                    handledAction),
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
        if (ReviewBuildCommentOperationHandler.CALLBACK_ROUTE.equals(
                owner.callbackRoute())) {
            return deliverReviewBuildComments(owner, fence, rawResult);
        }
        if (ReviewBuildCommentOperationHandler.REVIEW_PASS_CALLBACK_ROUTE.equals(
                owner.callbackRoute())) {
            return deliverReviewPassPublication(owner, fence, rawResult);
        }
        if (UserRemoteActionOperationHandler.EXTERNAL_CALLBACK_ROUTE.equals(
                owner.callbackRoute())) {
            return deliverExternalAction(owner, fence, rawResult);
        }
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
            if (ReviewBuildCommentOperationHandler.CALLBACK_ROUTE.equals(
                    owner.callbackRoute())) {
                CommentAction action = requireReviewBuildComments().require(
                        fence.operationId());
                requireReviewBuildComments().finalizeAction(
                        action.id(), action.status(), clock.instant());
            }
            else if (ReviewBuildCommentOperationHandler.REVIEW_PASS_CALLBACK_ROUTE
                    .equals(owner.callbackRoute())) {
                CommentAction action = requireReviewPassPublications().require(
                        fence.operationId());
                requireReviewPassPublications().finalizeAction(
                        action.id(), action.status(), clock.instant());
            }
            else if (UserRemoteActionOperationHandler.EXTERNAL_CALLBACK_ROUTE
                    .equals(owner.callbackRoute())) {
                finalizeExternalAction(
                        requireExternalActions().require(fence.operationId()));
            }
            else {
                finalizeAction(store.require(fence.operationId()));
            }
        }
    }

    @Override
    public void recoverCommittedDeliveries(int limit)
    {
        for (Action action : store.findCommittedUnfinalized(
                Math.min(RECOVERY_LIMIT, Math.max(1, limit)))) {
            finalizeAction(action);
        }
        if (reviewBuildComments != null) {
            for (CommentAction action : reviewBuildComments
                    .findCommittedUnfinalized(
                            Math.min(RECOVERY_LIMIT, Math.max(1, limit)))) {
                reviewBuildComments.finalizeAction(
                        action.id(), action.status(), clock.instant());
            }
        }
        if (reviewPassPublications != null) {
            for (CommentAction action : reviewPassPublications
                    .findCommittedUnfinalized(
                            Math.min(RECOVERY_LIMIT, Math.max(1, limit)))) {
                reviewPassPublications.finalizeAction(
                        action.id(), action.status(), clock.instant());
            }
        }
        if (externalActions != null) {
            for (Action action : externalActions.findCommittedUnfinalized(
                    Math.min(RECOVERY_LIMIT, Math.max(1, limit)))) {
                finalizeExternalAction(action);
            }
        }
    }

    private DispatchTicket.DeliveryReceipt deliverExternalAction(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult)
    {
        if (owner.kind() != DispatchTicket.OwnerKind.TRUNK
                || !fence.equals(rawResult.fence())) {
            return receipt(SUPERSEDED,
                    "external PR action delivery fence is stale");
        }
        SqliteExternalPrActionStore actions = requireExternalActions();
        Action action = actions.require(fence.operationId());
        if (!matchesExternal(owner, fence, action)) {
            return receipt(REJECTED,
                    "external PR action differs from durable authorization");
        }
        if (isGenericCanceled(rawResult)) {
            if (action.status() != ActionStatus.CANCELED) {
                action = actions.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.CANCELED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.CANCELED
                    ? receipt(ACCEPTED, "external PR action cancellation accepted")
                    : receipt(REJECTED, "external PR action cancellation is stale");
        }
        if (isGenericFailure(rawResult)) {
            if (action.status() != ActionStatus.ABANDONED) {
                action = actions.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.ABANDONED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.ABANDONED
                    ? receipt(ACCEPTED, "external PR action failure accepted")
                    : receipt(REJECTED, "external PR action failure is stale");
        }
        if (rawResult.outcome() != DispatchTicket.Outcome.SUCCEEDED
                || action.status() != ActionStatus.SUCCEEDED) {
            return receipt(REJECTED,
                    "external PR action lacks exact success evidence");
        }
        EffectResult result = decode(rawResult.payloadJson());
        if (!result.proven()
                || !Objects.equals(
                        action.externalEffectId(), result.externalEffectId())
                || !Objects.equals(action.evidence(), result.evidence())) {
            return receipt(REJECTED,
                    "external PR action result differs from durable evidence");
        }
        return receipt(ACCEPTED, "external PR action success accepted");
    }

    private DispatchTicket.DeliveryReceipt deliverReviewBuildComments(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult)
    {
        if (owner.kind() != DispatchTicket.OwnerKind.TRUNK
                || !fence.equals(rawResult.fence())) {
            return reviewBuildReceipt(SUPERSEDED,
                    "review build comment delivery fence is stale");
        }
        SqliteReviewBuildCommentStore comments = requireReviewBuildComments();
        CommentAction action = comments.require(fence.operationId());
        if (!matchesReviewBuildComments(owner, fence, action)) {
            return reviewBuildReceipt(REJECTED,
                    "review build comment delivery differs from authorization");
        }
        if (isGenericCanceled(rawResult)) {
            if (action.status() != ActionStatus.CANCELED) {
                action = comments.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.CANCELED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.CANCELED
                    ? reviewBuildReceipt(ACCEPTED,
                            "review build comment cancellation accepted")
                    : reviewBuildReceipt(REJECTED,
                            "review build comment cancellation is stale");
        }
        if (isGenericFailure(rawResult)) {
            if (action.status() != ActionStatus.ABANDONED) {
                action = comments.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.ABANDONED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.ABANDONED
                    ? reviewBuildReceipt(ACCEPTED,
                            "review build comment failure accepted")
                    : reviewBuildReceipt(REJECTED,
                            "review build comment failure is stale");
        }
        if (rawResult.outcome() != DispatchTicket.Outcome.SUCCEEDED
                || action.status() != ActionStatus.SUCCEEDED) {
            return reviewBuildReceipt(REJECTED,
                    "review build comment delivery lacks exact success evidence");
        }
        EffectResult result = decode(rawResult.payloadJson());
        if (!result.proven()
                || !Objects.equals(
                        action.externalEffectId(), result.externalEffectId())
                || !Objects.equals(action.evidence(), result.evidence())) {
            return reviewBuildReceipt(REJECTED,
                    "review build comment result differs from durable evidence");
        }
        return reviewBuildReceipt(ACCEPTED,
                "review build comment success accepted");
    }

    private DispatchTicket.DeliveryReceipt deliverReviewPassPublication(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult rawResult)
    {
        if (owner.kind() != DispatchTicket.OwnerKind.TRUNK
                || !fence.equals(rawResult.fence())) {
            return reviewPassReceipt(SUPERSEDED,
                    "standalone review publication delivery fence is stale");
        }
        SqliteReviewPassPublicationStore publications =
                requireReviewPassPublications();
        CommentAction action = publications.require(fence.operationId());
        if (!matchesReviewBuildComments(owner, fence, action)) {
            return reviewPassReceipt(REJECTED,
                    "standalone review publication differs from authorization");
        }
        if (isGenericCanceled(rawResult)) {
            if (action.status() != ActionStatus.CANCELED) {
                action = publications.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.CANCELED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.CANCELED
                    ? reviewPassReceipt(ACCEPTED,
                            "standalone review publication cancellation accepted")
                    : reviewPassReceipt(REJECTED,
                            "standalone review publication cancellation is stale");
        }
        if (isGenericFailure(rawResult)) {
            if (action.status() != ActionStatus.ABANDONED) {
                action = publications.terminalizeDeliveryFailure(
                        action.operationId(), ActionStatus.ABANDONED,
                        rawResult.error(), clock.instant());
            }
            return action.status() == ActionStatus.ABANDONED
                    ? reviewPassReceipt(ACCEPTED,
                            "standalone review publication failure accepted")
                    : reviewPassReceipt(REJECTED,
                            "standalone review publication failure is stale");
        }
        if (rawResult.outcome() != DispatchTicket.Outcome.SUCCEEDED
                || action.status() != ActionStatus.SUCCEEDED) {
            return reviewPassReceipt(REJECTED,
                    "standalone review publication lacks exact success evidence");
        }
        EffectResult result = decode(rawResult.payloadJson());
        if (!result.proven()
                || !Objects.equals(
                        action.externalEffectId(), result.externalEffectId())
                || !Objects.equals(action.evidence(), result.evidence())) {
            return reviewPassReceipt(REJECTED,
                    "standalone review publication result differs from evidence");
        }
        return reviewPassReceipt(ACCEPTED,
                "standalone review publication success accepted");
    }

    private void finalizeAction(Action action)
    {
        applyFinalization(action);
        store.markFinalized(action.id(), action.status(), clock.instant());
    }

    private void finalizeExternalAction(Action action)
    {
        applyFinalization(action);
        requireExternalActions().markFinalized(
                action.id(), action.status(), clock.instant());
    }

    private void applyFinalization(Action action)
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
                && Objects.equals(
                        action.expectedCodeFingerprint(),
                        fence.expectedCodeFingerprint())
                && action.headSha().equals(fence.expectedHeadSha())
                && action.baseSha().equals(fence.expectedBaseSha());
    }

    private static boolean matchesExternal(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            Action action)
    {
        // For zero-Task actions the existing Action carrier's binding slot is
        // deliberately the exact V2 REVIEW Trunk owner id.
        return action.taskId() == null
                && owner.id().equals(action.remotePrBindingId())
                && fence.taskEpoch() == null
                && fence.stageId() == null
                && fence.stageGeneration() == null
                && action.operationId().equals(fence.operationId())
                && fence.attempt() == action.semanticAttempt()
                && fence.expectedCodeFingerprint() == null
                && action.headSha().equals(fence.expectedHeadSha())
                && action.baseSha().equals(fence.expectedBaseSha());
    }

    private static boolean matchesReviewBuildComments(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            CommentAction action)
    {
        return owner.id().equals(action.threadId())
                && fence.taskEpoch() == null
                && fence.stageId() == null
                && fence.stageGeneration() == null
                && action.operationId().equals(fence.operationId())
                && fence.attempt() == action.semanticAttempt()
                && fence.expectedCodeFingerprint() == null
                && action.expectedHeadSha().equals(fence.expectedHeadSha())
                && fence.expectedBaseSha() == null;
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

    private static DispatchTicket.DeliveryReceipt reviewBuildReceipt(
            DispatchTicket.Acceptance acceptance, String detail)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                """
                {"schema":"REVIEW_BUILD_COMMENT_DELIVERY_V1","detail":"%s"}
                """.formatted(detail).strip());
    }

    private static DispatchTicket.DeliveryReceipt reviewPassReceipt(
            DispatchTicket.Acceptance acceptance, String detail)
    {
        return new DispatchTicket.DeliveryReceipt(
                acceptance,
                """
                {"schema":"STANDALONE_REVIEW_PASS_PUBLICATION_DELIVERY_V1","detail":"%s"}
                """.formatted(detail).strip());
    }

    private SqliteReviewBuildCommentStore requireReviewBuildComments()
    {
        return requireNonNull(
                reviewBuildComments,
                "review build comment runtime is not configured");
    }

    private SqliteReviewPassPublicationStore requireReviewPassPublications()
    {
        return requireNonNull(
                reviewPassPublications,
                "review pass publication runtime is not configured");
    }

    private SqliteExternalPrActionStore requireExternalActions()
    {
        return requireNonNull(
                externalActions, "external PR action runtime is not configured");
    }

    private static String strip(String value)
    {
        return value == null ? null : value.strip();
    }

    private static String upper(String value)
    {
        return value == null ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }
}
