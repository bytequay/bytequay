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

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.ReviewPassResolver;
import com.bytequay.app.service.tools.ParkedProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 * is a pattern-switch over the sealed type. {@code do*} branches read
 * fields via record accessors rather than {@code JsonNode.path("...")}
 * lookups, so missing-field validation lives in one place per field
 * (constructor + preflight) and the action surface is exhaustively
 * checked at compile time.
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

    private final NotificationService notifications;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final ObjectMapper mapper;
    private final ParkedProposalService parkedProposals;
    /** Lazy because TaskService transitively depends on services that
     *  may in turn need PublishService — using {@code @Lazy} keeps the
     *  bean graph acyclic by deferring the actual lookup to first
     *  use. The only call sites are approved task-advance proposals,
     *  which fire at most once per parked notification. */
    private final TaskService taskService;
    private final ReviewPassResolver reviewPassResolver;

    public PublishService(
            NotificationService notifications,
            TaskStore taskStore,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            ObjectMapper mapper,
            ParkedProposalService parkedProposals,
            @Lazy TaskService taskService,
            ReviewPassResolver reviewPassResolver)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.parkedProposals = requireNonNull(parkedProposals, "parkedProposals is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.reviewPassResolver = requireNonNull(reviewPassResolver, "reviewPassResolver is null");
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
            return finishInterruptedApproval(original, action);
        }
        requireExpectedAction(expectedAction, action);
        preflightApprovedAction(proposal, editedBody, original);
        claimResolution(original);

        PublishResult result;
        try {
            result = runApprovedAction(proposal, editedBody, original);
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
            writeAuditRow(original, "interrupted_unconfirmed", action,
                    "publish outcome unknown — the remote action may or may not have run: "
                            + e.getMessage());
            return interruptedResult(action);
        }
        catch (RuntimeException e) {
            // Any error after the claim may follow an ambiguous remote
            // outcome (timeouts are especially unsafe to retry). Keep
            // RESOLVING so the next user action performs local recovery
            // only, never repeats the publish.
            log.warn("publish approve {} ({}) interrupted before remote returned: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, "interrupted_unconfirmed", action,
                    "publish outcome unknown — the remote action may or may not have run: "
                            + e.getMessage());
            return interruptedResult(action);
        }

        try {
            boolean taskAlreadyAdvanced = "next_task".equals(action) || "ship_task".equals(action);
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
                writeAuditRow(original, "approved_concurrent", action,
                        "remote action completed; another resolver finalized this row first: "
                                + e.getMessage());
                return result;
            }
            log.warn("local finalization of publish {} ({}) failed: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, "interrupted_confirmed", action,
                    "remote action completed; local finalization failed: " + e.getMessage());
            return interruptedResult(action);
        }
        catch (RuntimeException e) {
            log.warn("local finalization of publish {} ({}) failed: {}",
                    notificationId, action, e.getMessage());
            writeAuditRow(original, "interrupted_confirmed", action,
                    "remote action completed; local finalization failed: " + e.getMessage());
            return interruptedResult(action);
        }
        writeAuditRow(original, "approved", action, result.message());
        // Close the review→build loop: if this thread was spawned from a
        // review pass, resolve any AGREED finding its just-published work
        // references. Best-effort — it never affects the publish outcome.
        reviewPassResolver.onPublishApproved(original.threadId(), action, editedBody);
        return result;
    }

    /** Advance actions push the branch and open a PR as a multi-step
     *  remote sequence, so a failure partway through can leave the push
     *  applied. They must never auto-release a claim for retry. */
    private static boolean isAdvanceAction(String action)
    {
        return "next_task".equals(action) || "ship_task".equals(action);
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

    private PublishResult runApprovedAction(
            ParkedProposal proposal, String editedBody, Notification original)
    {
        return switch (proposal) {
            case ParkedProposal.Push p -> doPush(p, original);
            case ParkedProposal.PostComment pc -> doPostComment(pc, editedBody);
            case ParkedProposal.ReplyReviewThread rrt -> doReplyReviewThread(rrt, editedBody);
            case ParkedProposal.ApprovePr a -> doApprovePr(a, editedBody);
            case ParkedProposal.MergePr m -> doMergePr(m);
            case ParkedProposal.CreateReviewComment c -> doCreateReviewComment(c, editedBody);
            case ParkedProposal.UpdatePrBody u -> doUpdatePrBody(u, editedBody);
            case ParkedProposal.RequestReviewer r -> doRequestReviewer(r);
            case ParkedProposal.CommentOnIssue c -> doCommentOnIssue(c, editedBody);
            case ParkedProposal.SetIssueState s -> doSetIssueState(s);
            case ParkedProposal.OpenPr o -> doOpenPr(o, editedBody, original);
            case ParkedProposal.PublishReview pr -> doPublishReview(pr, editedBody);
            case ParkedProposal.RequestReview ignored -> doRequestReview();
            case ParkedProposal.NextTask n -> doNextTask(n, original);
            case ParkedProposal.ShipTask s -> doShipTask(s, original);
        };
    }

    /**
     * Verify payload fields and the parked task target while the
     * notification is still open. Once the claim is written we never
     * infer that an exception happened before the remote call.
     */
    private void preflightApprovedAction(
            ParkedProposal proposal, String editedBody, Notification original)
    {
        switch (proposal) {
            case ParkedProposal.Push p -> preflightPush(p);
            case ParkedProposal.PostComment pc -> {
                requirePrRef(pc.pr(), "post_comment");
                requireEditableBody(pc.body(), editedBody, "comment body is blank — nothing to post");
            }
            case ParkedProposal.ReplyReviewThread rrt -> {
                requirePrRef(rrt.pr(), "reply_review_thread");
                if (rrt.rootCommentId() <= 0L) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked reply_review_thread notification has no rootCommentId");
                }
                requireEditableBody(rrt.body(), editedBody, "reply body is blank — nothing to post");
            }
            case ParkedProposal.ApprovePr a -> requirePrRef(a.pr(), "approve_pr");
            case ParkedProposal.MergePr m -> requirePrRef(m.pr(), "merge_pr");
            case ParkedProposal.CreateReviewComment c -> {
                requirePrRef(c.pr(), "create_review_comment");
                requireEditableBody(c.body(), editedBody, "review comment body is blank — nothing to post");
                if (orEmpty(c.filePath()).isBlank() || c.line() <= 0 || orEmpty(c.commitId()).isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked create_review_comment notification is missing filePath / line / commitId");
                }
            }
            case ParkedProposal.UpdatePrBody u -> {
                requirePrRef(u.pr(), "update_pr_body");
                requireEditableBody(u.body(), editedBody, "PR body is blank — nothing to update");
            }
            case ParkedProposal.RequestReviewer r -> {
                requirePrRef(r.pr(), "request_reviewer");
                if (orEmpty(r.reviewer()).trim().isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked request_reviewer notification has no reviewer login");
                }
            }
            case ParkedProposal.CommentOnIssue c -> {
                requireIssueRef(c.issue(), "comment_on_issue");
                requireEditableBody(c.body(), editedBody, "comment body is blank — nothing to post");
            }
            case ParkedProposal.SetIssueState s -> {
                requireIssueRef(s.issue(), "set_issue_state");
                if (!"open".equals(s.state()) && !"closed".equals(s.state())) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked set_issue_state notification has invalid state: " + s.state());
                }
            }
            case ParkedProposal.OpenPr o -> preflightOpenPr(o);
            case ParkedProposal.PublishReview pr -> preflightPublishReview(pr);
            case ParkedProposal.RequestReview ignored -> {
                // The MCP park path has already verified it has a diff.
            }
            case ParkedProposal.NextTask n -> preflightAdvance(n.baseMode(), original, "next_task");
            case ParkedProposal.ShipTask s -> preflightAdvance(s.baseMode(), original, "ship_task");
        }
    }

    private static void preflightPush(ParkedProposal.Push p)
    {
        String worktreePath = orEmpty(p.worktreePath());
        if (worktreePath.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked push notification has no worktreePath");
        }
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

    private static void requireEditableBody(String parkedBody, String editedBody, String message)
    {
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? orEmpty(parkedBody)
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), message);
        }
    }

    private static void preflightOpenPr(ParkedProposal.OpenPr o)
    {
        ParkedProposal.RepoRef repo = o.repo();
        if (repo == null
                || orEmpty(repo.owner()).isBlank()
                || orEmpty(repo.repo()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has incomplete repo ref");
        }
        if (orEmpty(o.title()).isBlank()
                || orEmpty(o.head()).isBlank()
                || orEmpty(o.base()).isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification is missing title / head / base");
        }
    }

    private static void preflightPublishReview(ParkedProposal.PublishReview pr)
    {
        requirePrRef(pr.pr(), "publish_review");
        List<ParkedProposal.PublishReview.InlineComment> comments = pr.comments();
        if (comments == null) {
            return;
        }
        for (ParkedProposal.PublishReview.InlineComment comment : comments) {
            if (orEmpty(comment.filePath()).isBlank()
                    || comment.line() <= 0
                    || orEmpty(comment.body()).isBlank()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                        "publish_review comment is missing file_path / line / body");
            }
        }
    }

    private void preflightAdvance(String baseMode, Notification original, String action)
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
        if (taskStore.findActiveTaskForThread(threadId).isPresent()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "thread " + threadId + " already has an active successor");
        }
        if (parked.workingDir() == null || parked.workingDir().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no working dir; nothing to ship");
        }
        if (parked.worktreePath() == null || parked.worktreePath().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no worktree; nothing to ship");
        }
        if (parked.branchName() == null || parked.branchName().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no branch name; nothing to ship");
        }
        // The original nextTitle JsonNode type check (textual-or-absent)
        // is now enforced by Jackson at deserialisation — a non-string
        // value lands the same 400 via parseProposal.
        String resolvedBaseMode = baseMode == null ? "main" : baseMode;
        if (!"main".equals(resolvedBaseMode) && !"stacked".equals(resolvedBaseMode)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid baseMode");
        }
    }

    private PublishResult finishInterruptedApproval(Notification original, String action)
    {
        parkedProposals.finishInterruptedApproval(original, action);
        String message = "Closed the interrupted approval locally without repeating "
                + "its publish action. Check the remote state before proposing it again.";
        writeAuditRow(original, "recovered", action, message);
        return new PublishResult(true, "recovered", message, action);
    }

    private static PublishResult interruptedResult(String action)
    {
        return new PublishResult(false, "interrupted",
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
        boolean resumeTask = !interrupted
                || "request_review".equals(action);
        parkedProposals.finishDiscarded(original, resumeTask);
        String auditResolution = interrupted ? "discarded_after_interrupt" : "discarded";
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
        return new PublishResult(true, "discarded",
                "Discarded.", action);
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
                    && obj.path("action").asText("").isBlank()
                    && "mcp:request_review".equals(obj.path("source").asText(""))
                    && obj.path("summary").isTextual()) {
                obj.put("action", "request_review");
            }
            return mapper.treeToValue(tree, ParkedProposal.class);
        }
        catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "notification payload is not a known parked proposal: " + e.getMessage());
        }
    }

    private PublishResult doPush(ParkedProposal.Push p, Notification original)
    {
        String worktreePath = orEmpty(p.worktreePath());
        if (worktreePath.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked push notification has no worktreePath");
        }
        Path worktree = Path.of(worktreePath);
        String branch = orElse(p.branch(), "the branch");
        try {
            git.push(worktree);
        }
        catch (IOException e) {
            throw new RuntimeException("git push failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git push interrupted", e);
        }
        // The branch is now on the remote — record it so the task UI can
        // show "on remote" instead of looking stuck. Best-effort: a
        // bookkeeping miss must not fail an already-applied push.
        markTaskPushed(original);
        return new PublishResult(true, "approved",
                "Pushed " + branch + " from " + worktree + ".", "push");
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

    private PublishResult doPostComment(ParkedProposal.PostComment pc, String editedBody)
    {
        ParkedProposal.PrRef pr = requirePrRef(pc.pr(), "post_comment");
        String effectiveBody = effectiveBody(pc.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment body is blank — nothing to post");
        }
        PullRequestRef ref = toPullRequestRef(pr);
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        pullRequests.createIssueComment(pat, ref, effectiveBody);
        return new PublishResult(true, "approved",
                "Posted comment on " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + ".",
                "post_comment");
    }

    private PublishResult doReplyReviewThread(ParkedProposal.ReplyReviewThread rrt, String editedBody)
    {
        ParkedProposal.PrRef pr = requirePrRef(rrt.pr(), "reply_review_thread");
        if (rrt.rootCommentId() <= 0L) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked reply_review_thread notification has no rootCommentId");
        }
        String effectiveBody = effectiveBody(rrt.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "reply body is blank — nothing to post");
        }
        PullRequestRef ref = toPullRequestRef(pr);
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        pullRequests.replyToReviewComment(pat, ref, rrt.rootCommentId(), effectiveBody);
        return new PublishResult(true, "approved",
                "Replied in review thread on " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + ".",
                "reply_review_thread");
    }

    private PublishResult doApprovePr(ParkedProposal.ApprovePr a, String editedBody)
    {
        ParkedProposal.PrRef pr = requirePrRef(a.pr(), "approve_pr");
        // approve_pr's body is optional. Distinguish "user never
        // overrode the textarea" (editedBody == null — for callers
        // that don't render an editor at all) from "user explicitly
        // cleared the textarea" (editedBody == ""). The latter should
        // honour the user's blank — the gate's editable textarea
        // makes clearing a real intent, not an indication to fall
        // back to the agent's suggestion.
        String effectiveBody = editedBody == null ? orEmpty(a.body()) : editedBody;
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        // GitHub's review-create endpoint accepts an empty body for
        // an APPROVE; the SDK uses Optional<String> so pass empty
        // when the user didn't type anything.
        CreateReviewCommand command = new CreateReviewCommand(
                Optional.empty(),
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                "APPROVE",
                ImmutableList.of());
        pullRequests.createReview(pat, toPullRequestRef(pr), command);
        return new PublishResult(true, "approved",
                "Approved " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + ".",
                "approve_pr");
    }

    private PublishResult doMergePr(ParkedProposal.MergePr m)
    {
        ParkedProposal.PrRef pr = requirePrRef(m.pr(), "merge_pr");
        String strategy = orElse(m.strategy(), "squash");
        MergePullRequestCommand command = switch (strategy) {
            case "merge" -> MergePullRequestCommand.mergeCommit();
            case "rebase" -> MergePullRequestCommand.rebase();
            default -> MergePullRequestCommand.squash();
        };
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        pullRequests.mergePullRequest(pat, toPullRequestRef(pr), command);
        return new PublishResult(true, "approved",
                "Merged " + pr.owner() + "/" + pr.repo() + "#" + pr.number()
                        + " (" + strategy + ").",
                "merge_pr");
    }

    private PublishResult doCreateReviewComment(ParkedProposal.CreateReviewComment c, String editedBody)
    {
        ParkedProposal.PrRef pr = requirePrRef(c.pr(), "create_review_comment");
        String effectiveBody = effectiveBody(c.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "review comment body is blank — nothing to post");
        }
        String filePath = orEmpty(c.filePath());
        int line = c.line();
        String commitId = orEmpty(c.commitId());
        if (filePath.isBlank() || line <= 0 || commitId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked create_review_comment notification is missing filePath / line / commitId");
        }
        String side = orElse(c.side(), "RIGHT");
        String startSide = orEmpty(c.startSide());
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        pullRequests.createInlineReviewComment(
                pat, toPullRequestRef(pr),
                effectiveBody, filePath, line, side, commitId,
                c.startLine(),
                startSide.isBlank() ? null : startSide);
        return new PublishResult(true, "approved",
                "Posted review comment on " + pr.owner() + "/" + pr.repo()
                        + "#" + pr.number() + " · " + filePath + ":" + line + ".",
                "create_review_comment");
    }

    private PublishResult doUpdatePrBody(ParkedProposal.UpdatePrBody u, String editedBody)
    {
        ParkedProposal.PrRef pr = requirePrRef(u.pr(), "update_pr_body");
        String effectiveBody = effectiveBody(u.body(), editedBody);
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "PR body is blank — nothing to update");
        }
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        UpdatePullRequestCommand command = new UpdatePullRequestCommand(
                Optional.empty(),
                Optional.of(effectiveBody),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        pullRequests.updatePullRequest(pat, toPullRequestRef(pr), command);
        return new PublishResult(true, "approved",
                "Updated PR body on " + pr.owner() + "/" + pr.repo() + "#" + pr.number() + ".",
                "update_pr_body");
    }

    private PublishResult doRequestReviewer(ParkedProposal.RequestReviewer r)
    {
        ParkedProposal.PrRef pr = requirePrRef(r.pr(), "request_reviewer");
        String reviewer = orEmpty(r.reviewer()).trim();
        if (reviewer.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked request_reviewer notification has no reviewer login");
        }
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        RequestReviewersCommand command = new RequestReviewersCommand(
                ImmutableList.of(reviewer),
                ImmutableList.of());
        pullRequests.requestReviewers(pat, toPullRequestRef(pr), command);
        return new PublishResult(true, "approved",
                "Requested " + reviewer + " on " + pr.owner() + "/"
                        + pr.repo() + "#" + pr.number() + ".",
                "request_reviewer");
    }

    private PublishResult doCommentOnIssue(ParkedProposal.CommentOnIssue c, String editedBody)
    {
        ParkedProposal.IssueRef issue = requireIssueRef(c.issue(), "comment_on_issue");
        String effectiveBody = effectiveBody(c.body(), editedBody);
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
        return new PublishResult(true, "approved",
                "Posted comment on " + issue.owner() + "/" + issue.repo() + "#" + issue.number() + ".",
                "comment_on_issue");
    }

    private PublishResult doSetIssueState(ParkedProposal.SetIssueState s)
    {
        ParkedProposal.IssueRef issue = requireIssueRef(s.issue(), "set_issue_state");
        String state = orEmpty(s.state());
        if (!"open".equals(state) && !"closed".equals(state)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked set_issue_state notification has invalid state: " + state);
        }
        String pat = patResolver.resolve(issue.owner() + "/" + issue.repo());
        pullRequests.setIssueState(pat, new RepoRef(issue.owner(), issue.repo()), issue.number(), state);
        return new PublishResult(true, "approved",
                "Set " + issue.owner() + "/" + issue.repo() + "#" + issue.number()
                        + " to " + state + ".",
                "set_issue_state");
    }

    private PublishResult doOpenPr(ParkedProposal.OpenPr o, String editedBody, Notification original)
    {
        ParkedProposal.RepoRef repo = o.repo();
        if (repo == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has no repo ref");
        }
        String owner = orEmpty(repo.owner());
        String repoName = orEmpty(repo.repo());
        if (owner.isBlank() || repoName.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has incomplete repo ref");
        }
        String title = orEmpty(o.title());
        String head = orEmpty(o.head());
        String base = orEmpty(o.base());
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
        // open_pr's body is optional. null = no override (use the
        // agent's parked body); "" = user explicitly cleared the
        // textarea and wants a blank PR description.
        String effectiveBody = editedBody == null ? orEmpty(o.body()) : editedBody;
        boolean draft = o.draft();
        CreatePullRequestCommand command = new CreatePullRequestCommand(
                head,
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
                taskStore.linkPullRequest(task.id(), opened.number(), opened.draft() ? "draft" : "open");
                taskStore.markPushed(task.id(), Instant.now());
            }
            catch (RuntimeException e) {
                log.warn("linking PR #{} to task {} failed: {}",
                        opened.number(), task.id(), e.getMessage());
            }
        }
        String prRef = opened == null ? "" : " #" + opened.number();
        return new PublishResult(true, "approved",
                "Opened PR" + prRef + " " + owner + "/" + repoName + " · " + head + " → " + base
                        + (draft ? " (draft)" : "") + ".",
                "open_pr");
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
            throw new RuntimeException("git push before open_pr failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git push before open_pr interrupted", e);
        }
    }

    private PublishResult doPublishReview(ParkedProposal.PublishReview review, String editedBody)
    {
        ParkedProposal.PrRef pr = requirePrRef(review.pr(), "publish_review");
        String event = orEmpty(review.event());
        if (!"APPROVE".equals(event) && !"REQUEST_CHANGES".equals(event) && !"COMMENT".equals(event)) {
            event = "COMMENT";
        }
        // publish_review's review-level body is optional. null = no
        // override; "" = user explicitly cleared (APPROVE/REQUEST_CHANGES
        // can land without any review-level text).
        String effectiveBody = editedBody == null ? orEmpty(review.body()) : editedBody;
        List<ParkedProposal.PublishReview.InlineComment> reviewComments = review.comments();
        ImmutableList.Builder<CreateReviewCommand.ReviewLineComment> commentsBuilder = ImmutableList.builder();
        if (reviewComments != null) {
            for (ParkedProposal.PublishReview.InlineComment c : reviewComments) {
                String filePath = orEmpty(c.filePath());
                int line = c.line();
                String body = orEmpty(c.body());
                if (filePath.isBlank() || line <= 0 || body.isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "publish_review comment is missing file_path / line / body");
                }
                String side = orElse(c.side(), "RIGHT");
                Optional<Integer> startLine = Optional.ofNullable(c.startLine());
                String startSideRaw = orEmpty(c.startSide());
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
        String pat = patResolver.resolve(pr.owner() + "/" + pr.repo());
        pullRequests.createReview(pat, toPullRequestRef(pr), command);
        return new PublishResult(true, "approved",
                "Published review on " + pr.owner() + "/" + pr.repo()
                        + "#" + pr.number() + " (" + event + ").",
                "publish_review");
    }

    /** Accepts a review-ready marker without running a remote side
     *  effect. The user has acknowledged the locally parked result. */
    private PublishResult doRequestReview()
    {
        return new PublishResult(true, "approved",
                "Accepted review-ready work. No remote changes were published.",
                "request_review");
    }

    private PublishResult doNextTask(ParkedProposal.NextTask n, Notification original)
    {
        Task next = runApprovedAdvance(n.nextTitle(), n.baseMode(), original, "next_task");
        return new PublishResult(true, "approved",
                "Advanced from task " + original.taskId() + " to " + next.id()
                        + " on " + next.branchName() + ".",
                "next_task");
    }

    private PublishResult doShipTask(ParkedProposal.ShipTask s, Notification original)
    {
        Task next = runApprovedAdvance(s.nextTitle(), s.baseMode(), original, "ship_task");
        return new PublishResult(true, "approved",
                "Shipped task " + original.taskId() + " → created " + next.id()
                        + " on " + next.branchName() + ".",
                "ship_task");
    }

    /**
     * Approves a parked task advance without reopening it as live
     * agent work. The user's Approve click is the publish
     * authorisation gate.
     */
    private Task runApprovedAdvance(String nextTitleRaw, String baseModeRaw, Notification original, String action)
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
        String nextTitle = orEmpty(nextTitleRaw);
        String baseMode = baseModeRaw == null ? "main" : baseModeRaw;
        if (!"main".equals(baseMode) && !"stacked".equals(baseMode)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid baseMode");
        }
        TaskService.BaseMode mode = "stacked".equals(baseMode)
                ? TaskService.BaseMode.STACKED
                : TaskService.BaseMode.MAIN;
        TaskService.ShipRequest request = new TaskService.ShipRequest(
                nextTitle.isBlank() ? null : nextTitle, mode);
        return "next_task".equals(action)
                ? taskService.startNextFromApprovedParkedTask(threadId, taskId, request)
                : taskService.shipApprovedParkedTask(threadId, taskId, request);
    }

    /** Validate a {@link ParkedProposal.PrRef} is fully populated and
     *  hand it back for ergonomic destructuring at the call site. */
    private static ParkedProposal.PrRef requirePrRef(ParkedProposal.PrRef pr, String action)
    {
        if (pr == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no pr ref");
        }
        if (orEmpty(pr.owner()).isBlank() || orEmpty(pr.repo()).isBlank() || pr.number() <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has incomplete pr ref");
        }
        return pr;
    }

    private static ParkedProposal.IssueRef requireIssueRef(ParkedProposal.IssueRef issue, String action)
    {
        if (issue == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no issue ref");
        }
        if (orEmpty(issue.owner()).isBlank() || orEmpty(issue.repo()).isBlank() || issue.number() <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has incomplete issue ref");
        }
        return issue;
    }

    private static PullRequestRef toPullRequestRef(ParkedProposal.PrRef pr)
    {
        return new PullRequestRef(pr.owner(), pr.repo(), pr.number());
    }

    /** Resolves the effective body for an action with the standard
     *  "edited overrides parked unless blank" precedence — the rule used
     *  by every comment-style publisher. */
    private static String effectiveBody(String parkedBody, String editedBody)
    {
        return (editedBody == null || editedBody.isBlank())
                ? orEmpty(parkedBody)
                : editedBody;
    }

    private static String orEmpty(String s)
    {
        return s == null ? "" : s;
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
}
