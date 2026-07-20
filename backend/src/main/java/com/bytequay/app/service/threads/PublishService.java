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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.IssueOriginService;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.UncheckedGitException;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PrPushedEvent;
import com.bytequay.app.service.review.ReviewPassResolver;
import com.bytequay.app.service.tools.ParkedProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

/**
 * Resolves an AWAITING_REVIEW notification: either approve (claim the
 * notification once, run its deferred action, then atomically finalize
 * local state) or discard (claim it without running the side effect).
 * Side effects only fire here — McpController just parks; this is where
 * the publish-gate contract actually lands.
 *
 * <p>The parked notification body is a typed {@link ParkedProposal} —
 * Jackson polymorphism on the {@code "action"} discriminator deserialises
 * the stored JSON straight into the matching variant, and the dispatcher
 * routes each sealed subtype through its own action handler. {@code do*}
 * branches read fields via record accessors rather than {@code
 * JsonNode.path("...")} lookups, so missing-field validation lives in
 * one place per field (constructor + preflight).
 *
 * <p>Audit rows are AUTO_FIX_DONE notifications carrying the
 * resolution (approved / discarded / discarded_after_interrupt /
 * interrupted / recovered), the action, the parked notification's id,
 * and a human-readable summary. Structural validation runs before a
 * proposal is claimed. Once an external attempt starts, any incomplete
 * resolution stays RESOLVING so the next user action can close local
 * state without publishing twice.
 */
@Service
public class PublishService
{
    private static final Logger log = LoggerFactory.getLogger(PublishService.class);
    private static final String ACTION_REQUEST_REVIEW = "request_review";
    private static final String ACTION_NEXT_TASK = "next_task";
    private static final String ACTION_SHIP_TASK = "ship_task";
    private static final String RESOLUTION_APPROVED = "approved";
    private static final String RESOLUTION_APPROVED_CONCURRENT = "approved_concurrent";
    private static final String RESOLUTION_DISCARDED = "discarded";
    private static final String RESOLUTION_DISCARDED_AFTER_INTERRUPT = "discarded_after_interrupt";
    private static final String RESOLUTION_INTERRUPTED = "interrupted";
    private static final String RESOLUTION_INTERRUPTED_CONFIRMED = "interrupted_confirmed";
    private static final String RESOLUTION_INTERRUPTED_UNCONFIRMED = "interrupted_unconfirmed";
    private static final String RESOLUTION_RECOVERED = "recovered";
    private static final String BASE_MODE_MAIN = "main";
    private static final String BASE_MODE_STACKED = "stacked";
    private static final String ISSUE_STATE_OPEN = "open";
    private static final String ISSUE_STATE_CLOSED = "closed";
    private static final String MERGE_STRATEGY_MERGE = "merge";
    private static final String MERGE_STRATEGY_REBASE = "rebase";
    private static final String MERGE_STRATEGY_SQUASH = "squash";
    private static final String REVIEW_EVENT_APPROVE = "APPROVE";
    private static final String REVIEW_EVENT_REQUEST_CHANGES = "REQUEST_CHANGES";
    private static final String REVIEW_EVENT_COMMENT = "COMMENT";
    private static final String REVIEW_SIDE_RIGHT = "RIGHT";
    private static final String LINKED_PULL_REQUEST_STATUS_OPEN = "open";
    private static final String LINKED_PULL_REQUEST_STATUS_DRAFT = "draft";
    private static final String JSON_FIELD_ACTION = "action";
    private static final String JSON_FIELD_SOURCE = "source";
    private static final String JSON_FIELD_SUMMARY = "summary";
    private static final String LEGACY_REQUEST_REVIEW_SOURCE = "mcp:request_review";

    private final NotificationService notifications;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final IssueOriginService issueOrigins;
    private final ObjectMapper mapper;
    private final ParkedProposalService parkedProposals;
    private final TaskPhaseMachine phaseMachine;
    /** Lazy because TaskService transitively depends on services that
     *  may in turn need PublishService — using {@code @Lazy} keeps the
     *  bean graph acyclic by deferring the actual lookup to first
     *  use. The only call sites are approved task-advance proposals,
     *  which fire at most once per parked notification. */
    private final TaskService taskService;
    private final ReviewPassResolver reviewPassResolver;
    private final StageStore stageStore;
    private final PRService prService;
    private final ApplicationEventPublisher eventPublisher;

    public PublishService(
            NotificationService notifications,
            TaskStore taskStore,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            IssueOriginService issueOrigins,
            ObjectMapper mapper,
            ParkedProposalService parkedProposals,
            @Lazy TaskService taskService,
            ReviewPassResolver reviewPassResolver,
            TaskPhaseMachine phaseMachine,
            StageStore stageStore,
            PRService prService,
            ApplicationEventPublisher eventPublisher)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.issueOrigins = requireNonNull(issueOrigins, "issueOrigins is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.parkedProposals = requireNonNull(parkedProposals, "parkedProposals is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.reviewPassResolver = requireNonNull(reviewPassResolver, "reviewPassResolver is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher is null");
    }

    /**
     * Run the parked publish action. {@code editedBody} only applies
     * to {@code post_comment} — push has no editable surface, so any
     * editedBody value is ignored for that action. Returns the
     * resolution shape the controller hands back to the frontend.
     */
    public PublishResult approve(String notificationId, String editedBody, String expectedAction)
    {
        Notification original = requireParked(notificationId);
        ParkedProposal proposal = parseProposal(original);
        String action = proposal.action();
        // Recovering an interrupted (RESOLVING) row runs no remote action
        // — it only finalizes local state — so it must reach the recovery
        // branch even when the caller can't supply the expectedAction
        // discriminator the fresh-approve path uses to guard a button
        // rendered against a since-changed payload.
        if (original.status() == NotificationStatus.RESOLVING) {
            return finishInterruptedApproval(original, proposal);
        }
        requireExpectedAction(expectedAction, action);
        ApprovedAction approvedAction = approvedActionFor(proposal, original);
        approvedAction.preflight(editedBody);
        claimResolution(original);

        PublishResult result;
        try {
            result = approvedAction.run(editedBody);
        }
        catch (ResponseStatusException e) {
            // A 4xx means the action was rejected before it changed any
            // remote state — either a local payload/state validation that
            // slipped past preflight or a GitHub client rejection (not
            // found, already merged, conflict). For single-remote-call
            // actions nothing ran, so release the claim and surface the
            // clean status; the row returns to UNREAD for a retry rather
            // than pinning in RESOLVING with a misleading "outcome
            // unknown" audit.
            //
            // The advance actions are the exception: next_task / ship_task
            // push the branch BEFORE the PR-create call that can 4xx, so a
            // 4xx there does NOT mean "nothing ran" — the branch may
            // already be on the remote. Treat those conservatively like
            // any ambiguous failure so a retry can't double-push. 5xx /
            // unknown errors are always ambiguous and fall through too.
            if (e.getStatusCode().is4xxClientError() && !isAdvanceAction(action)) {
                notifications.releaseResolution(notificationId);
                throw e;
            }
            log.warn("publish approve {} ({}) interrupted before remote returned: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, RESOLUTION_INTERRUPTED_UNCONFIRMED, action,
                    "publish outcome unknown — the remote action may or may not have run: "
                            + e.getMessage());
            return interruptedResult(action);
        }
        catch (PublishPushFailedException e) {
            // The push step failed, so nothing reached the remote. Unlike the
            // ambiguous advance failures below, this is safe to retry — release
            // the claim back to UNREAD so the (now-fixed) push can be re-run on
            // the next approve, instead of pinning RESOLVING and forcing the
            // user to discard.
            notifications.releaseResolution(notificationId);
            log.info("publish approve {} ({}) push failed pre-remote, released for retry: {}",
                    notificationId, action, e.getMessage());
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "push failed — nothing was published. Fix the cause and approve again: "
                            + e.getMessage());
        }
        catch (RuntimeException e) {
            // Any error after the claim may follow an ambiguous remote
            // outcome (timeouts are especially unsafe to retry). Keep
            // RESOLVING so the next user action performs local recovery
            // only, never repeats the publish.
            log.warn("publish approve {} ({}) interrupted before remote returned: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, RESOLUTION_INTERRUPTED_UNCONFIRMED, action,
                    "publish outcome unknown — the remote action may or may not have run: "
                            + e.getMessage());
            return interruptedResult(action);
        }

        try {
            boolean taskAlreadyAdvanced = isAdvanceAction(action);
            parkedProposals.finishApproved(original, taskAlreadyAdvanced);
        }
        catch (ResponseStatusException e) {
            // 409 from finishClaim means another concurrent caller
            // (typically a Discard fired from a second tab while this
            // approve was mid-remote-call) already resolved the row.
            // The remote action ran, but the local close was performed
            // by that other caller — surface the conflict cleanly as
            // "succeeded but concurrently resolved" instead of leaving
            // the row stuck-in-RESOLVING with a confusing interrupted
            // audit. The other caller's audit row already records the
            // discard / resolution chain.
            if (e.getStatusCode().value() == 409) {
                log.warn("publish approve {} ({}) raced a concurrent resolver: {}",
                        notificationId, action, e.getMessage());
                writeAuditRow(original, RESOLUTION_APPROVED_CONCURRENT, action,
                        "remote action completed; another resolver finalized this row first: "
                                + e.getMessage());
                return result;
            }
            log.warn("local finalization of publish {} ({}) failed: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, RESOLUTION_INTERRUPTED_CONFIRMED, action,
                    "remote action completed; local finalization failed: " + e.getMessage());
            return interruptedResult(action);
        }
        catch (RuntimeException e) {
            log.warn("local finalization of publish {} ({}) failed: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, RESOLUTION_INTERRUPTED_CONFIRMED, action,
                    "remote action completed; local finalization failed: " + e.getMessage());
            return interruptedResult(action);
        }
        writeAuditRow(original, RESOLUTION_APPROVED, action, result.message());
        // Close the review→build loop: if this thread was spawned from a
        // review pass, resolve any AGREED finding its just-published work
        // references. Best-effort — it never affects the publish outcome.
        reviewPassResolver.onPublishApproved(original.threadId(), action, editedBody);
        // The approved publish is the real action, so drive the task's
        // dev-lifecycle phase here (not when the tool merely parked): a
        // push / opened PR puts the task on the remote spine; a review
        // request opens the internal review. A merge completes the task
        // through the PR-merged event, so it isn't repeated here.
        driveApprovedPhase(proposal, original.taskId());
        return result;
    }

    /** Advance the task's phase to reflect a just-approved publish. Uses
     *  the observe path (authoritative action, robust to whatever phase
     *  the task was in — the agent may have skipped the local steps).
     *  Best-effort: a bookkeeping failure never fails the publish. */
    private void driveApprovedPhase(ParkedProposal proposal, String taskId)
    {
        if (taskId == null) {
            return;
        }
        if (proposal instanceof ParkedProposal.CreateIssue) {
            completePlanningProposal(taskId, "quality_issue_published");
            return;
        }
        TaskPhase target = switch (proposal) {
            case ParkedProposal.Push ignored -> TaskPhase.PUSHED_AWAITING_CI;
            case ParkedProposal.OpenPr ignored -> TaskPhase.PUSHED_AWAITING_CI;
            case ParkedProposal.RequestReview ignored -> TaskPhase.INTERNAL_REVIEW;
            // Marking ready puts the PR out for review.
            case ParkedProposal.MarkReady ignored -> TaskPhase.AWAITING_REMOTE_REVIEW;
            default -> null;
        };
        if (target == null) {
            return;
        }
        try {
            phaseMachine.observe(taskId, target, "publish_approved");
        }
        catch (RuntimeException e) {
            log.warn("phase drive after approving {} for task {} failed: {}",
                    proposal.getClass().getSimpleName(), taskId, e.getMessage());
        }
    }

    /** Advance actions push the branch and open a PR as a multi-step
     *  remote sequence, so a failure partway through can leave the push
     *  applied. They must never auto-release a claim for retry. */
    private static boolean isAdvanceAction(String action)
    {
        return ACTION_NEXT_TASK.equals(action) || ACTION_SHIP_TASK.equals(action);
    }

    private static void requireExpectedAction(String expectedAction, String actualAction)
    {
        if (expectedAction == null || expectedAction.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "expectedAction is required for approval");
        }
        if (!expectedAction.equals(actualAction)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "notification action changed from " + expectedAction + " to " + actualAction);
        }
    }

    /**
     * Build the paired preflight/run behavior for one approved proposal.
     * This is the only subtype dispatch point; Java keeps it exhaustive
     * because {@link ParkedProposal} is sealed.
     */
    private ApprovedAction approvedActionFor(ParkedProposal proposal, Notification original)
    {
        return switch (proposal) {
            case ParkedProposal.Push push -> action(
                    editedBody -> preflightPush(push),
                    editedBody -> doPush(push, original));
            case ParkedProposal.PostComment postComment -> action(
                    editedBody -> preflightPostComment(postComment, editedBody),
                    editedBody -> doPostComment(postComment, editedBody));
            case ParkedProposal.ReplyReviewThread replyReviewThread -> action(
                    editedBody -> preflightReplyReviewThread(replyReviewThread, editedBody),
                    editedBody -> doReplyReviewThread(replyReviewThread, editedBody));
            case ParkedProposal.ResolveReviewThread resolveReviewThread -> action(
                    editedBody -> preflightResolveReviewThread(resolveReviewThread),
                    editedBody -> doResolveReviewThread(resolveReviewThread));
            case ParkedProposal.ApprovePr approvePullRequest -> action(
                    editedBody -> preflightApprovePr(approvePullRequest),
                    editedBody -> doApprovePr(approvePullRequest, editedBody));
            case ParkedProposal.MergePr mergePullRequest -> action(
                    editedBody -> preflightMergePr(mergePullRequest),
                    editedBody -> doMergePr(mergePullRequest));
            case ParkedProposal.CreateReviewComment createReviewComment -> action(
                    editedBody -> preflightCreateReviewComment(createReviewComment, editedBody),
                    editedBody -> doCreateReviewComment(createReviewComment, editedBody));
            case ParkedProposal.UpdatePrBody updatePullRequestBody -> action(
                    editedBody -> preflightUpdatePrBody(updatePullRequestBody, editedBody),
                    editedBody -> doUpdatePrBody(updatePullRequestBody, editedBody));
            case ParkedProposal.RequestReviewer requestReviewer -> action(
                    editedBody -> preflightRequestReviewer(requestReviewer),
                    editedBody -> doRequestReviewer(requestReviewer));
            case ParkedProposal.CreateIssue createIssue -> action(
                    editedBody -> preflightCreateIssue(createIssue, editedBody),
                    editedBody -> doCreateIssue(createIssue, editedBody));
            case ParkedProposal.CommentOnIssue commentOnIssue -> action(
                    editedBody -> preflightCommentOnIssue(commentOnIssue, editedBody),
                    editedBody -> doCommentOnIssue(commentOnIssue, editedBody));
            case ParkedProposal.SetIssueState setIssueState -> action(
                    editedBody -> preflightSetIssueState(setIssueState),
                    editedBody -> doSetIssueState(setIssueState));
            case ParkedProposal.OpenPr openPullRequest -> action(
                    editedBody -> preflightOpenPr(openPullRequest),
                    editedBody -> doOpenPr(openPullRequest, editedBody, original));
            case ParkedProposal.PublishReview publishReview -> action(
                    editedBody -> preflightPublishReview(publishReview),
                    editedBody -> doPublishReview(publishReview, editedBody));
            case ParkedProposal.RequestReview requestReview -> action(
                    editedBody -> preflightRequestReview(requestReview),
                    editedBody -> doRequestReview());
            case ParkedProposal.NextTask nextTask -> action(
                    editedBody -> preflightAdvance(nextTask.baseMode(), original, nextTask.action()),
                    editedBody -> doNextTask(nextTask, original));
            case ParkedProposal.ShipTask shipTask -> action(
                    editedBody -> preflightAdvance(shipTask.baseMode(), original, shipTask.action()),
                    editedBody -> doShipTask(shipTask, original));
            case ParkedProposal.MarkReady markReady -> action(
                    editedBody -> preflightMarkReady(markReady),
                    editedBody -> doMarkReady(markReady, editedBody, original));
        };
    }

    /** The parked push's worktree path, or 400 when it's missing. Shared
     *  by the preflight check and the approved push so the guard and its
     *  message live in one place. */
    private static String requireWorktreePath(ParkedProposal.Push push)
    {
        String worktreePath = nullToEmpty(push.worktreePath());
        if (worktreePath.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked push notification has no worktreePath");
        }
        return worktreePath;
    }

    private static void preflightPush(ParkedProposal.Push push)
    {
        String worktreePath = requireWorktreePath(push);
        try {
            if (!Path.of(worktreePath).isAbsolute()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                        "parked push notification has a non-absolute worktreePath");
            }
        }
        catch (InvalidPathException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked push notification has an invalid worktreePath");
        }
    }

    private static void preflightPostComment(ParkedProposal.PostComment postComment, String editedBody)
    {
        requirePrRef(postComment.pr(), postComment.action());
        requireEditableBody(postComment.body(), editedBody, "comment body is blank — nothing to post");
    }

    private static void preflightReplyReviewThread(
            ParkedProposal.ReplyReviewThread replyReviewThread, String editedBody)
    {
        requirePrRef(replyReviewThread.pr(), replyReviewThread.action());
        if (replyReviewThread.rootCommentId() <= 0L) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked reply_review_thread notification has no rootCommentId");
        }
        requireEditableBody(replyReviewThread.body(), editedBody, "reply body is blank — nothing to post");
    }

    private static void preflightResolveReviewThread(ParkedProposal.ResolveReviewThread resolve)
    {
        requirePrRef(resolve.pr(), resolve.action());
        if (resolve.rootCommentId() <= 0L) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked resolve_review_thread notification has no rootCommentId");
        }
    }

    /**
     * Resolve/unresolve happens via GraphQL, which keys off the thread's opaque
     * node id — not the REST root comment id the agent sees. Map it live (the
     * PR may not be cached), then call the mutation.
     */
    private PublishResult doResolveReviewThread(ParkedProposal.ResolveReviewThread resolve)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(resolve.pr(), resolve.action());
        if (resolve.rootCommentId() <= 0L) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked resolve_review_thread notification has no rootCommentId");
        }
        PullRequestRef ref = toPullRequestRef(pullRequest);
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        String nodeId = pullRequests.fetchReviewThreadResolution(pat, ref).stream()
                .filter(m -> m.rootCommentDatabaseId() == resolve.rootCommentId())
                .map(PullRequestRepository.ReviewThreadMeta::graphqlNodeId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "no review thread with root comment id " + resolve.rootCommentId() + " on "
                                + pullRequest.owner() + "/" + pullRequest.repo() + "#" + pullRequest.number()));
        if (resolve.resolved()) {
            pullRequests.resolveReviewThread(pat, nodeId);
        }
        else {
            pullRequests.unresolveReviewThread(pat, nodeId);
        }
        return new PublishResult(true, RESOLUTION_APPROVED,
                (resolve.resolved() ? "Resolved" : "Unresolved") + " review thread on "
                        + pullRequest.owner() + "/" + pullRequest.repo() + "#" + pullRequest.number() + ".",
                resolve.action());
    }

    private static void preflightApprovePr(ParkedProposal.ApprovePr approvePullRequest)
    {
        requirePrRef(approvePullRequest.pr(), approvePullRequest.action());
    }

    private static void preflightMergePr(ParkedProposal.MergePr mergePullRequest)
    {
        requirePrRef(mergePullRequest.pr(), mergePullRequest.action());
    }

    private static void preflightCreateReviewComment(
            ParkedProposal.CreateReviewComment createReviewComment, String editedBody)
    {
        requirePrRef(createReviewComment.pr(), createReviewComment.action());
        requireEditableBody(createReviewComment.body(), editedBody, "review comment body is blank — nothing to post");
        if (nullToEmpty(createReviewComment.filePath()).isBlank()
                || createReviewComment.line() <= 0
                || nullToEmpty(createReviewComment.commitId()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked create_review_comment notification is missing filePath / line / commitId");
        }
    }

    private static void preflightUpdatePrBody(ParkedProposal.UpdatePrBody updatePullRequestBody, String editedBody)
    {
        requirePrRef(updatePullRequestBody.pr(), updatePullRequestBody.action());
        requireEditableBody(updatePullRequestBody.body(), editedBody, "PR body is blank — nothing to update");
    }

    private static void preflightRequestReviewer(ParkedProposal.RequestReviewer requestReviewer)
    {
        requirePrRef(requestReviewer.pr(), requestReviewer.action());
        if (nullToEmpty(requestReviewer.reviewer()).trim().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked request_reviewer notification has no reviewer login");
        }
    }

    private static void preflightCommentOnIssue(ParkedProposal.CommentOnIssue commentOnIssue, String editedBody)
    {
        requireIssueRef(commentOnIssue.issue(), commentOnIssue.action());
        requireEditableBody(commentOnIssue.body(), editedBody, "comment body is blank — nothing to post");
    }

    private static void preflightCreateIssue(ParkedProposal.CreateIssue createIssue, String editedBody)
    {
        ParkedProposal.RepoRef repo = createIssue.repo();
        if (repo == null
                || nullToEmpty(repo.owner()).isBlank()
                || nullToEmpty(repo.repo()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked create_issue notification has incomplete repo ref");
        }
        if (nullToEmpty(createIssue.title()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked create_issue notification has no title");
        }
        requireEditableBody(createIssue.body(), editedBody, "issue body is blank — nothing to post");
    }

    private static void preflightSetIssueState(ParkedProposal.SetIssueState setIssueState)
    {
        requireIssueRef(setIssueState.issue(), setIssueState.action());
        if (!ISSUE_STATE_OPEN.equals(setIssueState.state()) && !ISSUE_STATE_CLOSED.equals(setIssueState.state())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked set_issue_state notification has invalid state: " + setIssueState.state());
        }
    }

    private static void preflightRequestReview(ParkedProposal.RequestReview ignored)
    {
        // The MCP park path has already verified it has a diff.
    }

    private static void requireEditableBody(String parkedBody, String editedBody, String message)
    {
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? nullToEmpty(parkedBody)
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), message);
        }
    }

    private static void preflightOpenPr(ParkedProposal.OpenPr openPullRequest)
    {
        ParkedProposal.RepoRef repo = openPullRequest.repo();
        if (repo == null
                || nullToEmpty(repo.owner()).isBlank()
                || nullToEmpty(repo.repo()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has incomplete repo ref");
        }
        if (nullToEmpty(openPullRequest.title()).isBlank()
                || nullToEmpty(openPullRequest.head()).isBlank()
                || nullToEmpty(openPullRequest.base()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification is missing title / head / base");
        }
    }

    private static void preflightPublishReview(ParkedProposal.PublishReview publishReview)
    {
        requirePrRef(publishReview.pr(), publishReview.action());
        List<ParkedProposal.PublishReview.InlineComment> comments = publishReview.comments();
        if (comments == null) {
            return;
        }
        for (ParkedProposal.PublishReview.InlineComment comment : comments) {
            if (nullToEmpty(comment.filePath()).isBlank()
                    || comment.line() <= 0
                    || nullToEmpty(comment.body()).isBlank()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                        "publish_review comment is missing file_path / line / body");
            }
        }
    }

    /**
     * Resolves and validates the parked task an advance (next / ship)
     * targets: the notification must carry both ids, the task must still
     * exist, and it must still be sitting at AWAITING_REVIEW. Returns the
     * parked task so {@link #preflightAdvance} and {@link #runApprovedAdvance}
     * share one guard instead of repeating it.
     */
    private Task resolveParkedAdvanceTarget(Notification original, String action)
    {
        String threadId = original.threadId();
        String taskId = original.taskId();
        if (threadId == null || taskId == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no thread / task id");
        }
        Task parked = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(400),
                        "parked " + action + " target " + taskId + " not found"));
        if (parked.status() != TaskStatus.AWAITING_REVIEW) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "parked " + action + " target " + taskId + " is no longer awaiting approval");
        }
        return parked;
    }

    private void preflightAdvance(String baseMode, Notification original, String action)
    {
        Task parked = resolveParkedAdvanceTarget(original, action);
        // Hard ship gate: a ship/advance must not push or open a PR while
        // the user still has open local review comments on the task. This
        // runs before the claim and any remote side effect, so a reject
        // leaves the proposal parked and re-approvable once they're resolved.
        int openComments = openReviewCommentCount(parked.id());
        if (openComments > 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "resolve the " + openComments + " open review comment(s) before shipping");
        }
        TaskPreconditions.requireShippable(parked);
        // The original nextTitle JsonNode type check (textual-or-absent)
        // is now enforced by Jackson at deserialisation — a non-string
        // value lands the same 400 via parseProposal.
        String resolvedBaseMode = baseMode == null ? BASE_MODE_MAIN : baseMode;
        if (!BASE_MODE_MAIN.equals(resolvedBaseMode) && !BASE_MODE_STACKED.equals(resolvedBaseMode)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid baseMode");
        }
    }

    private int openReviewCommentCount(String taskId)
    {
        int legacyCount = stageStore.findUnresolvedComments(taskId).size();
        Optional<PR> prOpt = prService.findByTask(taskId);
        int prCount = (prOpt == null ? Optional.<PR>empty() : prOpt)
                .map(pr -> (int) prService.comments(pr.id()).stream()
                        .filter(c -> PRComment.ORIGIN_LOCAL.equals(c.origin()))
                        .filter(c -> c.parentCommentId() == null)
                        .filter(c -> c.resolvedAt() == null && c.dismissedAt() == null)
                        .count())
                .orElse(0);
        return legacyCount + prCount;
    }

    private PublishResult finishInterruptedApproval(
            Notification original,
            ParkedProposal proposal)
    {
        String action = proposal.action();
        parkedProposals.finishInterruptedApproval(original, action);
        if (proposal instanceof ParkedProposal.CreateIssue) {
            completePlanningProposal(original.taskId(), "quality_issue_publish_recovered");
        }
        String message = "Closed the interrupted approval locally without repeating "
                + "its publish action. Check the remote state before proposing it again.";
        writeAuditRow(original, RESOLUTION_RECOVERED, action, message);
        return new PublishResult(true, RESOLUTION_RECOVERED, message, action);
    }

    private static PublishResult interruptedResult(String action)
    {
        return new PublishResult(false, RESOLUTION_INTERRUPTED,
                "The approval attempt did not finish cleanly. Check remote state, then "
                        + "choose Finish locally or Discard; publishing will not be repeated automatically.",
                action);
    }

    /**
     * Drop the parked publish without running its side effect. A
     * declined task advance that was never attempted returns to local
     * idle work. Once a publish outcome is uncertain, only next_task
     * may return to editing; other actions close potentially published
     * work rather than allowing silent divergence.
     */
    public PublishResult discard(String notificationId, String expectedAction)
    {
        Notification original = requireParked(notificationId);
        ParkedProposal proposal = parseProposal(original);
        String action = proposal.action();
        boolean interrupted = original.status() == NotificationStatus.RESOLVING;
        if (!interrupted) {
            // A fresh discard guards against a payload that changed under
            // the rendered button. An interrupted-row discard runs no
            // remote action — it only finalizes local state — so it must
            // recover even when the caller can't supply the discriminator.
            requireExpectedAction(expectedAction, action);
            claimResolution(original);
        }
        // Discard semantics:
        //  UNINTERRUPTED → always resume. The remote side effect never
        //  ran (Approve was never clicked), so closing the task on
        //  Discard would silently throw away in-progress work. User
        //  said "not this proposal" — let the agent keep iterating.
        //
        //  INTERRUPTED → branch on whether the remote may have run:
        //    • request_review: a local-only handoff that never touches
        //      the remote, so resume is safe even mid-interrupt.
        //    • everything else, including next_task / ship_task: the
        //      remote may have already happened — next_task and ship_task
        //      both push the branch and open a PR inside the approved
        //      advance — so completing locally is the safe close.
        //      Resuming could let the agent re-edit work that already
        //      shipped, and (when the advance produced a successor)
        //      revive the prior task into a second active sibling.
        boolean resumeTask = (!interrupted
                && !(proposal instanceof ParkedProposal.CreateIssue))
                || (interrupted && ACTION_REQUEST_REVIEW.equals(action));
        parkedProposals.finishDiscarded(original, resumeTask);
        if (proposal instanceof ParkedProposal.CreateIssue) {
            completePlanningProposal(original.taskId(), "quality_issue_discarded");
        }
        String auditResolution = interrupted ? RESOLUTION_DISCARDED_AFTER_INTERRUPT : RESOLUTION_DISCARDED;
        // For an interrupted discard we deliberately don't reassert
        // the remote outcome here — the prior `interrupted_unconfirmed`
        // or `interrupted_confirmed` audit row records what we know,
        // and overwriting that here with our own claim would
        // contradict itself in the confirmed case (where the remote
        // definitely ran). Point readers at the chain instead.
        String auditMessage = interrupted
                ? "user discarded an interrupted " + action
                        + " approval; see the prior interrupted audit row for the remote outcome"
                : "user discarded the proposed " + action;
        writeAuditRow(original, auditResolution, action, auditMessage);
        return new PublishResult(true, RESOLUTION_DISCARDED,
                "Discarded.", action);
    }

    /** Close the read-only planning lifecycle behind a quality-scan issue
     * proposal. Publish proposals normally arise during development; this
     * one deliberately arises from PlanStage, so its approval/discard must
     * also close that stage instead of leaving a completed task in PLANNING. */
    private void completePlanningProposal(String taskId, String reason)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            phaseMachine.transition(taskId, TaskPhase.COMPLETED, reason, Actor.HUMAN);
        }
        catch (RuntimeException e) {
            log.warn("completing quality proposal phase for task {} failed: {}",
                    taskId, e.getMessage());
        }
    }

    /**
     * Rewrite a parked ship proposal's PR title/body before the user
     * approves it. Loads the AWAITING_REVIEW notification, requires it to
     * be a {@code ship_task} proposal, replaces the proposed prTitle /
     * prBody, and re-persists the payload. Blank values are stored as
     * null so the approve path falls back to the thread title / no body.
     * Rejects anything that isn't an open ship proposal.
     */
    public Notification updateShipDescription(String notificationId, String prTitle, String prBody)
    {
        Notification original = requireParked(notificationId);
        ParkedProposal proposal = parseProposal(original);
        if (!(proposal instanceof ParkedProposal.ShipTask shipTask)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "notification " + notificationId + " is not a ship proposal");
        }
        String title = nullToEmpty(prTitle).trim();
        String body = nullToEmpty(prBody);
        ParkedProposal.ShipTask updated = new ParkedProposal.ShipTask(
                shipTask.threadId(),
                shipTask.taskId(),
                shipTask.branch(),
                shipTask.baseBranch(),
                shipTask.worktreePath(),
                shipTask.nextTitle(),
                shipTask.baseMode(),
                shipTask.diffBase(),
                shipTask.diff(),
                shipTask.diffError(),
                title.isEmpty() ? null : title,
                body.isEmpty() ? null : body);
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(updated);
        }
        catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(500),
                    "failed to serialise updated ship proposal: " + e.getMessage());
        }
        return notifications.updatePayload(notificationId, payloadJson);
    }

    private Notification requireParked(String notificationId)
    {
        Notification original = notifications.find(notificationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "no notification: " + notificationId));
        if (original.kind() != NotificationKind.AWAITING_REVIEW) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "only AWAITING_REVIEW notifications can be approved or discarded");
        }
        if (original.status() == NotificationStatus.RESOLVED
                || original.status() == NotificationStatus.DISMISSED) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), "notification already resolved: " + notificationId);
        }
        if (original.status() != NotificationStatus.UNREAD
                && original.status() != NotificationStatus.READ
                && original.status() != NotificationStatus.RESOLVING) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), "notification cannot be resolved: " + notificationId);
        }
        return original;
    }

    private void claimResolution(Notification original)
    {
        if (!notifications.claimResolution(original.id())) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409), "notification already resolved: " + original.id());
        }
    }

    /** Parse the parked JSON into a typed {@link ParkedProposal}.
     *
     *  <p>Legacy back-compat: rows written before {@code "action"} was a
     *  required field carry only {@code summary} + {@code
     *  source="mcp:request_review"}. Inject the discriminator before
     *  Jackson runs so polymorphism can resolve the variant. */
    private ParkedProposal parseProposal(Notification original)
    {
        String json = original.payloadJson() == null ? "{}" : original.payloadJson();
        try {
            JsonNode tree = mapper.readTree(json);
            if (tree instanceof ObjectNode obj
                    && obj.path(JSON_FIELD_ACTION).asText("").isBlank()
                    && LEGACY_REQUEST_REVIEW_SOURCE.equals(obj.path(JSON_FIELD_SOURCE).asText(""))
                    && obj.path(JSON_FIELD_SUMMARY).isTextual()) {
                obj.put(JSON_FIELD_ACTION, ACTION_REQUEST_REVIEW);
            }
            return mapper.treeToValue(tree, ParkedProposal.class);
        }
        catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "notification payload is not a known parked proposal: " + e.getMessage());
        }
    }

    private PublishResult doPush(ParkedProposal.Push push, Notification original)
    {
        String worktreePath = requireWorktreePath(push);
        Path worktree = Path.of(worktreePath);
        String branch = orElse(push.branch(), "the branch");
        try {
            // Force-with-lease: a post-ship branch may have been rebased onto
            // its base (diverging from its remote), so a plain push would be
            // rejected as a non-fast-forward. The lease still refuses if the
            // remote moved unexpectedly, so a teammate's push is never clobbered.
            git.pushForceWithLease(worktree);
        }
        catch (IOException e) {
            // Push failed → nothing on the remote → release for a clean retry.
            throw new PublishPushFailedException("git push failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishPushFailedException("git push interrupted", e);
        }
        // The branch is now on the remote — record it so the task UI can
        // show "on remote" instead of looking stuck. Best-effort: a
        // bookkeeping miss must not fail an already-applied push.
        markTaskPushed(original);
        syncPrIfAlreadyOpen(original);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Pushed " + branch + " from " + worktree + ".", push.action());
    }

    /** A plain push doesn't open a PR itself — but if the task already has
     *  one (e.g. pushing more commits after addressing comments), sync the
     *  PR row so it doesn't keep offering "ready to push" for a push
     *  that just landed on an already-open PR. */
    private void syncPrIfAlreadyOpen(Notification original)
    {
        String taskId = original == null ? null : original.taskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskStore.findTaskById(taskId).ifPresent(task -> PullRequestRef.parse(task.linkedPrRef()).ifPresent(ref ->
                eventPublisher.publishEvent(PrPushedEvent.of(task.id(), ref))));
    }

    /** Stamp the proposal's task as pushed-to-remote. Resolved by the
     *  notification's taskId; silently skipped for a thread-level row. */
    private void markTaskPushed(Notification original)
    {
        String taskId = original == null ? null : original.taskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            taskStore.markPushed(taskId, Instant.now());
        }
        catch (RuntimeException e) {
            log.warn("recording pushed state for task {} failed: {}", taskId, e.getMessage());
        }
    }

    private PublishResult doPostComment(ParkedProposal.PostComment postComment, String editedBody)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(postComment.pr(), postComment.action());
        String effectiveBody = effectiveBody(postComment.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment body is blank — nothing to post");
        }
        PullRequestRef ref = toPullRequestRef(pullRequest);
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        pullRequests.createIssueComment(pat, ref, effectiveBody);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Posted comment on " + pullRequest.owner() + "/" + pullRequest.repo()
                        + "#" + pullRequest.number() + ".",
                postComment.action());
    }

    private PublishResult doReplyReviewThread(ParkedProposal.ReplyReviewThread replyReviewThread, String editedBody)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(replyReviewThread.pr(), replyReviewThread.action());
        if (replyReviewThread.rootCommentId() <= 0L) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked reply_review_thread notification has no rootCommentId");
        }
        String effectiveBody = effectiveBody(replyReviewThread.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "reply body is blank — nothing to post");
        }
        PullRequestRef ref = toPullRequestRef(pullRequest);
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        pullRequests.replyToReviewComment(pat, ref, replyReviewThread.rootCommentId(), effectiveBody);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Replied in review thread on " + pullRequest.owner() + "/" + pullRequest.repo()
                        + "#" + pullRequest.number() + ".",
                replyReviewThread.action());
    }

    private PublishResult doApprovePr(ParkedProposal.ApprovePr approvePullRequest, String editedBody)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(approvePullRequest.pr(), approvePullRequest.action());
        // approve_pr's body is optional. Distinguish "user never
        // overrode the textarea" (editedBody == null — for callers
        // that don't render an editor at all) from "user explicitly
        // cleared the textarea" (editedBody == ""). The latter should
        // honour the user's blank — the gate's editable textarea
        // makes clearing a real intent, not an indication to fall
        // back to the agent's suggestion.
        String effectiveBody = editedBody == null ? nullToEmpty(approvePullRequest.body()) : editedBody;
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        // GitHub's review-create endpoint accepts an empty body for
        // an APPROVE; the SDK uses Optional<String> so pass empty
        // when the user didn't type anything.
        CreateReviewCommand command = new CreateReviewCommand(
                Optional.empty(),
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                REVIEW_EVENT_APPROVE,
                ImmutableList.of());
        pullRequests.createReview(pat, toPullRequestRef(pullRequest), command);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Approved " + pullRequest.owner() + "/" + pullRequest.repo() + "#" + pullRequest.number() + ".",
                approvePullRequest.action());
    }

    private PublishResult doMergePr(ParkedProposal.MergePr mergePullRequest)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(mergePullRequest.pr(), mergePullRequest.action());
        String strategy = orElse(mergePullRequest.strategy(), MERGE_STRATEGY_SQUASH);
        MergePullRequestCommand command = switch (strategy) {
            case MERGE_STRATEGY_MERGE -> MergePullRequestCommand.mergeCommit();
            case MERGE_STRATEGY_REBASE -> MergePullRequestCommand.rebase();
            default -> MergePullRequestCommand.squash();
        };
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        PullRequestRef ref = toPullRequestRef(pullRequest);
        String who = pullRequest.owner() + "/" + pullRequest.repo() + "#" + pullRequest.number();

        // Repos that require a merge queue reject a direct merge. Detect the
        // queue up front when GitHub exposes it (classic branch protection);
        // otherwise attempt the direct merge and, if it's rejected for a
        // queue rule (rulesets — where the queue isn't visible via GraphQL),
        // retry as an enqueue.
        Optional<PullRequestRepository.MergeQueueProbe> probe = safeProbeMergeQueue(pat, ref);
        if (probe.isPresent()) {
            pullRequests.enqueuePullRequest(pat, probe.get().pullRequestNodeId());
            return onEnqueued(pullRequest, who, mergePullRequest.action());
        }
        try {
            pullRequests.mergePullRequest(pat, ref, command);
        }
        catch (ResponseStatusException e) {
            if (requiresMergeQueue(e)) {
                Optional<String> nodeId = pullRequests.pullRequestNodeId(pat, ref);
                if (nodeId.isPresent()) {
                    pullRequests.enqueuePullRequest(pat, nodeId.get());
                    return onEnqueued(pullRequest, who, mergePullRequest.action());
                }
            }
            throw e;
        }
        // Advance a shipped task that owns this PR to COMPLETED — the
        // dashboard merge does the same via PullRequestMergedEvent.
        taskService.completeTasksForMergedPr(pullRequest.owner() + "/" + pullRequest.repo(), pullRequest.number());
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Merged " + who + " (" + strategy + ").",
                mergePullRequest.action());
    }

    /** Probe the PR's base branch for a (classic branch-protection) merge
     *  queue, swallowing probe failures so we can still attempt a direct
     *  merge. */
    private Optional<PullRequestRepository.MergeQueueProbe> safeProbeMergeQueue(String pat, PullRequestRef ref)
    {
        try {
            return pullRequests.probeMergeQueue(pat, ref);
        }
        catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static PublishResult mergeQueuedResult(String who, String action)
    {
        return new PublishResult(true, RESOLUTION_APPROVED, "Added " + who + " to the merge queue.", action);
    }

    /** Record standing merge consent (so a queue bounce re-enqueues without
     *  re-prompting) and report the PR as queued. */
    private PublishResult onEnqueued(ParkedProposal.PrRef pr, String who, String action)
    {
        taskService.authorizeMergeForPr(pr.owner() + "/" + pr.repo(), pr.number());
        return mergeQueuedResult(who, action);
    }

    /** True when a direct-merge rejection is GitHub telling us the change
     *  must go through the merge queue (HTTP 405 with a queue message). */
    private static boolean requiresMergeQueue(ResponseStatusException e)
    {
        return e.getStatusCode().value() == 405
                && e.getReason() != null
                && e.getReason().toLowerCase(Locale.ROOT).contains("merge queue");
    }

    private PublishResult doCreateReviewComment(
            ParkedProposal.CreateReviewComment createReviewComment, String editedBody)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(createReviewComment.pr(), createReviewComment.action());
        String effectiveBody = effectiveBody(createReviewComment.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "review comment body is blank — nothing to post");
        }
        String filePath = nullToEmpty(createReviewComment.filePath());
        int line = createReviewComment.line();
        String commitId = nullToEmpty(createReviewComment.commitId());
        if (filePath.isBlank() || line <= 0 || commitId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked create_review_comment notification is missing filePath / line / commitId");
        }
        String side = orElse(createReviewComment.side(), REVIEW_SIDE_RIGHT);
        String startSide = nullToEmpty(createReviewComment.startSide());
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        pullRequests.createInlineReviewComment(
                pat, toPullRequestRef(pullRequest),
                effectiveBody, filePath, line, side, commitId,
                createReviewComment.startLine(),
                startSide.isBlank() ? null : startSide);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Posted review comment on " + pullRequest.owner() + "/" + pullRequest.repo()
                        + "#" + pullRequest.number() + " · " + filePath + ":" + line + ".",
                createReviewComment.action());
    }

    private PublishResult doUpdatePrBody(ParkedProposal.UpdatePrBody updatePullRequestBody, String editedBody)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(updatePullRequestBody.pr(), updatePullRequestBody.action());
        String effectiveBody = effectiveBody(updatePullRequestBody.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "PR body is blank — nothing to update");
        }
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        UpdatePullRequestCommand command = new UpdatePullRequestCommand(
                Optional.empty(),
                Optional.of(effectiveBody),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        pullRequests.updatePullRequest(pat, toPullRequestRef(pullRequest), command);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Updated PR body on " + pullRequest.owner() + "/" + pullRequest.repo()
                        + "#" + pullRequest.number() + ".",
                updatePullRequestBody.action());
    }

    private PublishResult doRequestReviewer(ParkedProposal.RequestReviewer requestReviewer)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(requestReviewer.pr(), requestReviewer.action());
        String reviewer = nullToEmpty(requestReviewer.reviewer()).trim();
        if (reviewer.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked request_reviewer notification has no reviewer login");
        }
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        RequestReviewersCommand command = new RequestReviewersCommand(
                ImmutableList.of(reviewer),
                ImmutableList.of());
        pullRequests.requestReviewers(pat, toPullRequestRef(pullRequest), command);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Requested " + reviewer + " on " + pullRequest.owner() + "/"
                        + pullRequest.repo() + "#" + pullRequest.number() + ".",
                requestReviewer.action());
    }

    private static void preflightMarkReady(ParkedProposal.MarkReady markReady)
    {
        requirePrRef(markReady.pr(), markReady.action());
    }

    /**
     * Approve the mark-ready gate: flip the PR out of draft and, when the user
     * supplied reviewers in {@code editedBody} (comma/space/newline-separated
     * GitHub logins), request them. An empty body means "just mark ready".
     */
    private PublishResult doMarkReady(ParkedProposal.MarkReady markReady, String editedBody, Notification original)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(markReady.pr(), markReady.action());
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        PullRequestRef ref = toPullRequestRef(pullRequest);
        pullRequests.setPullRequestDraft(pat, ref, false);

        List<String> reviewers = Arrays.stream(nullToEmpty(editedBody).split("[,\\s]+"))
                .map(String::trim)
                .map(login -> login.startsWith("@") ? login.substring(1) : login)
                .filter(login -> !login.isBlank())
                .distinct()
                .toList();
        if (!reviewers.isEmpty()) {
            pullRequests.requestReviewers(pat, ref,
                    new RequestReviewersCommand(reviewers, ImmutableList.of()));
        }
        syncPrMarkedReady(original == null ? null : original.taskId());
        String who = pullRequest.owner() + "/" + pullRequest.repo() + "#" + pullRequest.number();
        return new PublishResult(true, RESOLUTION_APPROVED,
                reviewers.isEmpty()
                        ? "Marked " + who + " ready for review."
                        : "Marked " + who + " ready and requested " + String.join(", ", reviewers) + ".",
                markReady.action());
    }

    /** Best-effort: flip a still-{@code remote-drafted} PR row to
     *  {@code remote-open} once mark-ready resolves through this gate — same
     *  rationale as {@link PrPushedEvent}, but this one has no other
     *  path that creates it so it's kept local rather than centralized. */
    private void syncPrMarkedReady(String taskId)
    {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            prService.findByTask(taskId)
                    .filter(pr -> PR.STATUS_REMOTE_DRAFTED.equals(pr.status()))
                    .ifPresent(pr -> prService.transition(
                            pr.id(), PR.STATUS_REMOTE_OPEN, PRTimelineEntry.ACTOR_AGENT));
        }
        catch (RuntimeException e) {
            log.warn("syncing local PR mark-ready state for task {} failed: {}", taskId, e.getMessage());
        }
    }

    private PublishResult doCommentOnIssue(ParkedProposal.CommentOnIssue commentOnIssue, String editedBody)
    {
        ParkedProposal.IssueRef issue = requireIssueRef(commentOnIssue.issue(), commentOnIssue.action());
        String effectiveBody = effectiveBody(commentOnIssue.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment body is blank — nothing to post");
        }
        // GitHub's issue-comment endpoint is the same for issues and
        // PRs — PullRequestRef carries the (owner, repo, number)
        // triple either way.
        PullRequestRef forApi = new PullRequestRef(issue.owner(), issue.repo(), issue.number());
        String pat = patResolver.resolve(issue.owner() + "/" + issue.repo());
        pullRequests.createIssueComment(pat, forApi, effectiveBody);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Posted comment on " + issue.owner() + "/" + issue.repo() + "#" + issue.number() + ".",
                commentOnIssue.action());
    }

    private PublishResult doCreateIssue(ParkedProposal.CreateIssue createIssue, String editedBody)
    {
        ParkedProposal.RepoRef target = createIssue.repo();
        RepoRef repo = RepoRef.of(target.owner(), target.repo());
        String body = IssueOrigin.markQualityScan(effectiveBody(createIssue.body(), editedBody));
        String pat = patResolver.resolve(repo.fullName());
        RepoIssue created = pullRequests.createIssue(pat, repo, createIssue.title(), body);
        issueOrigins.recordCreated(created, IssueOrigin.QUALITY_SCAN);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Created " + repo.fullName() + "#" + created.number() + ".",
                createIssue.action());
    }

    private PublishResult doSetIssueState(ParkedProposal.SetIssueState setIssueState)
    {
        ParkedProposal.IssueRef issue = requireIssueRef(setIssueState.issue(), setIssueState.action());
        String state = nullToEmpty(setIssueState.state());
        if (!ISSUE_STATE_OPEN.equals(state) && !ISSUE_STATE_CLOSED.equals(state)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked set_issue_state notification has invalid state: " + state);
        }
        String pat = patResolver.resolve(issue.owner() + "/" + issue.repo());
        pullRequests.setIssueState(pat, new RepoRef(issue.owner(), issue.repo()), issue.number(), state);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Set " + issue.owner() + "/" + issue.repo() + "#" + issue.number()
                        + " to " + state + ".",
                setIssueState.action());
    }

    private PublishResult doOpenPr(ParkedProposal.OpenPr openPullRequest, String editedBody, Notification original)
    {
        ParkedProposal.RepoRef repo = openPullRequest.repo();
        if (repo == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has no repo ref");
        }
        String owner = nullToEmpty(repo.owner());
        String repoName = nullToEmpty(repo.repo());
        if (owner.isBlank() || repoName.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has incomplete repo ref");
        }
        String title = nullToEmpty(openPullRequest.title());
        String head = nullToEmpty(openPullRequest.head());
        String base = nullToEmpty(openPullRequest.base());
        if (title.isBlank() || head.isBlank() || base.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification is missing title / head / base");
        }
        // One-step publish: push the branch first if it isn't on the
        // remote yet, so approving the draft-PR proposal both lands the
        // branch and opens its PR in a single action. A no-op when the
        // branch was already pushed (e.g. a prior push approval).
        Task task = original == null || original.taskId() == null
                ? null
                : taskStore.findTaskById(original.taskId()).orElse(null);
        ensureBranchPushed(task, head);
        // Record the push the moment the branch is on the remote — before
        // the PR-create call below, which can time out. Otherwise a failed
        // open_pr leaves a pushed branch with pushedAt still null, so the
        // task UI can't show the "Branch pushed" state.
        if (task != null) {
            try {
                taskStore.markPushed(task.id(), Instant.now());
            }
            catch (RuntimeException e) {
                log.warn("recording pushed state for task {} failed: {}", task.id(), e.getMessage());
            }
        }
        // open_pr's body is optional. null = no override (use the
        // agent's parked body); "" = user explicitly cleared the
        // textarea and wants a blank PR description.
        String effectiveBody = editedBody == null ? nullToEmpty(openPullRequest.body()) : editedBody;
        boolean draft = openPullRequest.draft();
        // The branch is pushed to the clone's origin (the fork), but the PR
        // opens against the target repo. When origin is a fork of the
        // target, GitHub needs an owner-qualified head (<fork-owner>:branch);
        // the bare head above is still what ensureBranchPushed pushed.
        String apiHead = crossForkHead(task, owner, head);
        CreatePullRequestCommand command = new CreatePullRequestCommand(
                apiHead,
                base,
                title,
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                draft ? Optional.of(true) : Optional.empty(),
                Optional.empty());
        String pat = patResolver.resolve(owner + "/" + repoName);
        PullRequest opened = pullRequests.createPullRequest(pat, new RepoRef(owner, repoName), command);
        // Persist the opened PR onto the task so the UI can show "PR #n"
        // and deep-link into the in-app PR page — closing the gap where
        // the returned number used to be discarded. Best-effort.
        if (task != null && opened != null) {
            try {
                // Push was already recorded right after ensureBranchPushed.
                taskStore.linkPullRequest(
                        task.id(), opened.number(),
                        opened.draft() ? LINKED_PULL_REQUEST_STATUS_DRAFT : LINKED_PULL_REQUEST_STATUS_OPEN);
                // Also record the canonical "owner/repo#n" ref so the
                // lifecycle driver can fetch this PR directly by number.
                taskStore.linkTaskToPr(task.id(), opened.repo() + "#" + opened.number());
            }
            catch (RuntimeException e) {
                log.warn("linking PR #{} to task {} failed: {}",
                        opened.number(), task.id(), e.getMessage());
            }
            eventPublisher.publishEvent(new PrPushedEvent(task.id(), owner + "/" + repoName, opened.number(), opened.htmlUrl()));
        }
        String prRef = opened == null ? "" : " #" + opened.number();
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Opened PR" + prRef + " " + owner + "/" + repoName + " · " + apiHead + " → " + base
                        + (draft ? " (draft)" : "") + ".",
                openPullRequest.action());
    }

    /** Push {@code head} from the task's worktree when it isn't already
     *  on {@code origin}. Skipped when the worktree was reaped (the
     *  branch is necessarily already pushed) or the ref already exists. */
    private void ensureBranchPushed(Task task, String head)
    {
        if (task == null) {
            return;
        }
        String worktreePath = task.worktreePath();
        if (worktreePath == null || worktreePath.isBlank()) {
            return;
        }
        Path worktree = Path.of(worktreePath);
        try {
            if (git.refExists(worktree, "origin/" + head)) {
                return;
            }
            git.push(worktree);
        }
        catch (IOException e) {
            throw new UncheckedGitException("git push before open_pr failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git push before open_pr interrupted", e);
        }
    }

    /** PR head ref for {@code head} in {@code task}'s clone against a PR
     *  target owned by {@code targetOwner}: {@code <fork-owner>:<branch>}
     *  when the clone's {@code origin} is a fork of the target (its owner
     *  differs), else the bare branch. Falls back to the bare branch when
     *  there is no task worktree or the origin owner can't be read. */
    private String crossForkHead(Task task, String targetOwner, String head)
    {
        if (task == null || task.workingDir() == null || task.workingDir().isBlank()) {
            return head;
        }
        try {
            Optional<String> forkOwner = git.remoteOwner(Path.of(task.workingDir()), "origin");
            if (forkOwner.isPresent() && !forkOwner.get().equalsIgnoreCase(targetOwner)) {
                return forkOwner.get() + ":" + head;
            }
        }
        catch (IOException e) {
            log.warn("Resolving origin owner for cross-fork head of task {} failed: {}",
                    task.id(), e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return head;
    }

    private PublishResult doPublishReview(ParkedProposal.PublishReview review, String editedBody)
    {
        ParkedProposal.PrRef pullRequest = requirePrRef(review.pr(), review.action());
        String event = nullToEmpty(review.event());
        if (!REVIEW_EVENT_APPROVE.equals(event)
                && !REVIEW_EVENT_REQUEST_CHANGES.equals(event)
                && !REVIEW_EVENT_COMMENT.equals(event)) {
            event = REVIEW_EVENT_COMMENT;
        }
        // publish_review's review-level body is optional. null = no
        // override; "" = user explicitly cleared (APPROVE/REQUEST_CHANGES
        // can land without any review-level text).
        String effectiveBody = editedBody == null ? nullToEmpty(review.body()) : editedBody;
        List<ParkedProposal.PublishReview.InlineComment> reviewComments = review.comments();
        ImmutableList.Builder<CreateReviewCommand.ReviewLineComment> commentsBuilder = ImmutableList.builder();
        if (reviewComments != null) {
            for (ParkedProposal.PublishReview.InlineComment comment : reviewComments) {
                String filePath = nullToEmpty(comment.filePath());
                int line = comment.line();
                String body = nullToEmpty(comment.body());
                if (filePath.isBlank() || line <= 0 || body.isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "publish_review comment is missing file_path / line / body");
                }
                String side = orElse(comment.side(), REVIEW_SIDE_RIGHT);
                Optional<Integer> startLine = Optional.ofNullable(comment.startLine());
                String startSideRaw = nullToEmpty(comment.startSide());
                Optional<String> startSide = startSideRaw.isBlank()
                        ? Optional.empty()
                        : Optional.of(startSideRaw);
                commentsBuilder.add(new CreateReviewCommand.ReviewLineComment(
                        filePath, Optional.empty(), Optional.of(line), side, body, startLine, startSide));
            }
        }
        CreateReviewCommand command = new CreateReviewCommand(
                Optional.empty(),
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                event,
                commentsBuilder.build());
        String pat = patResolver.resolve(pullRequest.owner() + "/" + pullRequest.repo());
        pullRequests.createReview(pat, toPullRequestRef(pullRequest), command);
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Published review on " + pullRequest.owner() + "/" + pullRequest.repo()
                        + "#" + pullRequest.number() + " (" + event + ").",
                review.action());
    }

    /** Accepts a review-ready marker without running a remote side
     *  effect. The user has acknowledged the locally parked result. */
    private PublishResult doRequestReview()
    {
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Accepted review-ready work. No remote changes were published.",
                ACTION_REQUEST_REVIEW);
    }

    private PublishResult doNextTask(ParkedProposal.NextTask nextTask, Notification original)
    {
        Task next = runApprovedAdvance(
                nextTask.nextTitle(), nextTask.baseMode(), null, null, original, nextTask.action());
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Advanced from task " + original.taskId() + " to " + next.id()
                        + " on " + next.branchName() + ".",
                nextTask.action());
    }

    private PublishResult doShipTask(ParkedProposal.ShipTask shipTask, Notification original)
    {
        Task next = runApprovedAdvance(
                shipTask.nextTitle(), shipTask.baseMode(),
                shipTask.prTitle(), shipTask.prBody(), original, shipTask.action());
        return new PublishResult(true, RESOLUTION_APPROVED,
                "Shipped task " + original.taskId() + " → created " + next.id()
                        + " on " + next.branchName() + ".",
                shipTask.action());
    }

    /**
     * Approves a parked task advance without reopening it as live
     * agent work. The user's Approve click is the publish
     * authorisation gate. {@code prTitle} / {@code prBody} carry the
     * proposed (or edited) draft-PR description for a ship; they are
     * null for a next_task advance.
     */
    private Task runApprovedAdvance(
            String nextTitleRaw, String baseModeRaw,
            String prTitle, String prBody, Notification original, String action)
    {
        Task parked = resolveParkedAdvanceTarget(original, action);
        String threadId = parked.threadId();
        String taskId = parked.id();
        String nextTitle = nullToEmpty(nextTitleRaw);
        String baseMode = baseModeRaw == null ? BASE_MODE_MAIN : baseModeRaw;
        if (!BASE_MODE_MAIN.equals(baseMode) && !BASE_MODE_STACKED.equals(baseMode)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid baseMode");
        }
        TaskService.BaseMode mode = BASE_MODE_STACKED.equals(baseMode)
                ? TaskService.BaseMode.STACKED
                : TaskService.BaseMode.MAIN;
        TaskService.ShipRequest request = new TaskService.ShipRequest(
                nextTitle.isBlank() ? null : nextTitle, mode, prTitle, prBody);
        return ACTION_NEXT_TASK.equals(action)
                ? taskService.startNextFromApprovedParkedTask(threadId, taskId, request)
                : taskService.shipApprovedParkedTask(threadId, taskId, request);
    }

    /** Validate a {@link ParkedProposal.PrRef} is fully populated and
     *  hand it back for ergonomic destructuring at the call site. */
    private static ParkedProposal.PrRef requirePrRef(ParkedProposal.PrRef pullRequest, String action)
    {
        if (pullRequest == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no pr ref");
        }
        if (nullToEmpty(pullRequest.owner()).isBlank()
                || nullToEmpty(pullRequest.repo()).isBlank()
                || pullRequest.number() <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has incomplete pr ref");
        }
        return pullRequest;
    }

    private static ParkedProposal.IssueRef requireIssueRef(ParkedProposal.IssueRef issue, String action)
    {
        if (issue == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no issue ref");
        }
        if (nullToEmpty(issue.owner()).isBlank() || nullToEmpty(issue.repo()).isBlank() || issue.number() <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has incomplete issue ref");
        }
        return issue;
    }

    private static PullRequestRef toPullRequestRef(ParkedProposal.PrRef pullRequest)
    {
        return new PullRequestRef(pullRequest.owner(), pullRequest.repo(), pullRequest.number());
    }

    /** Resolves the effective body for an action with the standard
     *  "edited overrides parked unless blank" precedence — the rule used
     *  by every comment-style publisher. */
    private static String effectiveBody(String parkedBody, String editedBody)
    {
        return (editedBody == null || editedBody.isBlank())
                ? nullToEmpty(parkedBody)
                : editedBody;
    }

    private static String orElse(String s, String fallback)
    {
        return s == null || s.isBlank() ? fallback : s;
    }

    private void writeAuditRow(
            Notification original, String resolution, String action, String message)
    {
        try {
            String json = mapper.writeValueAsString(
                    new AuditRowPayload(resolution, action, original.id(), message));
            notifications.notifyAutoFixDone(original.threadId(), original.taskId(), json);
        }
        catch (JsonProcessingException | RuntimeException e) {
            // Audit failures shouldn't surface to the user — the
            // resolution already happened, and we'd rather lose the
            // audit row than the side effect's success acknowledgement.
            log.warn("audit row write failed for notification {} (resolution={}): {}",
                    original.id(), resolution, e.getMessage());
        }
    }

    /** Wire shape for the AUTO_FIX_DONE audit row written after a
     *  publish resolves. {@code publishResolution} is one of "approved",
     *  "discarded", "discarded_after_interrupt", "interrupted_confirmed",
     *  "interrupted_unconfirmed", "approved_concurrent", or "recovered". */
    private record AuditRowPayload(
            String publishResolution,
            String action,
            String originalNotificationId,
            String message) {}

    /**
     * Resolution shape the controller hands back to the frontend.
     * {@code ok} is true on success (approved, discarded, or recovered),
     * false while an interrupted attempt still needs a local resolution.
     * {@code resolution} is one of "approved", "discarded",
     * "interrupted", or "recovered".
     */
    public record PublishResult(boolean ok, String resolution, String message, String action) {}

    /**
     * Re-triggers a push-driven CI run by pushing an empty commit to the
     * PR's branch — the fallback to GitHub's "re-run failed jobs" for
     * repos whose CI only fires on push. Runs in the PR's active task
     * worktree (the one place we have the branch checked out with a push
     * path); with no such worktree it returns a reason instead of throwing
     * so the UI can explain why. The push is user-initiated (a button), so
     * — like the {@code push} tool — it's server-side and not subject to
     * the agent's deny-git gate.
     */
    public EmptyCommitResult triggerCiViaEmptyCommit(String repoFullName, int number)
    {
        String prRef = repoFullName + "#" + number;
        Task task = taskStore.findActiveTaskByPrRef(prRef).orElse(null);
        if (task == null || task.worktreePath() == null || task.worktreePath().isBlank()) {
            return new EmptyCommitResult(false, "No active task worktree for this PR to commit on.");
        }
        Path worktree = Path.of(task.worktreePath());
        if (!Files.isDirectory(worktree)) {
            return new EmptyCommitResult(false, "This task's worktree is no longer on disk.");
        }
        try {
            git.commitEmpty(worktree, "Re-trigger CI");
            git.push(worktree);
            return new EmptyCommitResult(true, null);
        }
        catch (IOException e) {
            return new EmptyCommitResult(false, "git push failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new EmptyCommitResult(false, "Interrupted while pushing the empty commit.");
        }
    }

    /** Outcome of {@link #triggerCiViaEmptyCommit}: {@code triggered} is
     *  true when the empty commit was pushed; {@code reason} explains a
     *  no-op so the UI can surface it. */
    public record EmptyCommitResult(boolean triggered, String reason) {}

    private static ApprovedAction action(ApprovedActionPreflight preflight, ApprovedActionRunner runner)
    {
        return new ApprovedAction(preflight, runner);
    }

    private record ApprovedAction(ApprovedActionPreflight preflight, ApprovedActionRunner runner)
    {
        void preflight(String editedBody)
        {
            preflight.preflight(editedBody);
        }

        PublishResult run(String editedBody)
        {
            return runner.run(editedBody);
        }
    }

    @FunctionalInterface
    private interface ApprovedActionPreflight
    {
        void preflight(String editedBody);
    }

    @FunctionalInterface
    private interface ApprovedActionRunner
    {
        PublishResult run(String editedBody);
    }
}
