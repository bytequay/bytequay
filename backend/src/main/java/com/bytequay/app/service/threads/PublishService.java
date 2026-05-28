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
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.RequestReviewersCommand;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.web.PatResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Resolves an AWAITING_REVIEW notification: either approve (claim the
 * notification once, run its deferred action, then atomically finalize
 * local state) or discard (claim it without running the side effect).
 * Side effects only fire here — McpController just parks; this is where
 * the publish-gate contract actually lands.
 *
 * <p>Audit rows are AUTO_FIX_DONE notifications carrying the
 * resolution (approved / discarded / discarded_after_interrupt /
 * interrupted / recovered), the action, the parked notification's id,
 * and a human-readable summary. Structural
 * validation runs before a proposal is claimed. Once an external
 * attempt starts, any incomplete resolution stays RESOLVING so the
 * next user action can close local state without publishing twice.
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

    public PublishService(
            NotificationService notifications,
            TaskStore taskStore,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            ObjectMapper mapper,
            ParkedProposalService parkedProposals,
            @Lazy TaskService taskService)
    {
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.parkedProposals = requireNonNull(parkedProposals, "parkedProposals is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
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
        JsonNode payload = parsePayload(original);
        String action = resolveAction(payload);
        // Recovering an interrupted (RESOLVING) row runs no remote action
        // — it only finalizes local state — so it must reach the recovery
        // branch even when the caller can't supply the expectedAction
        // discriminator the fresh-approve path uses to guard a button
        // rendered against a since-changed payload.
        if (original.status() == NotificationStatus.RESOLVING) {
            return finishInterruptedApproval(original, action);
        }
        requireExpectedAction(expectedAction, action);
        preflightApprovedAction(action, payload, editedBody, original);
        claimResolution(original);

        PublishResult result;
        try {
            result = runApprovedAction(action, payload, editedBody, original);
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
            String action, JsonNode payload, String editedBody, Notification original)
    {
        return switch (action) {
            case "push" -> doPush(payload);
            case "post_comment" -> doPostComment(payload, editedBody);
            case "reply_review_thread" -> doReplyReviewThread(payload, editedBody);
            case "approve_pr" -> doApprovePr(payload, editedBody);
            case "merge_pr" -> doMergePr(payload);
            case "create_review_comment" -> doCreateReviewComment(payload, editedBody);
            case "update_pr_body" -> doUpdatePrBody(payload, editedBody);
            case "request_reviewer" -> doRequestReviewer(payload);
            case "comment_on_issue" -> doCommentOnIssue(payload, editedBody);
            case "set_issue_state" -> doSetIssueState(payload);
            case "open_pr" -> doOpenPr(payload, editedBody);
            case "publish_review" -> doPublishReview(payload, editedBody);
            case "request_review" -> doRequestReview();
            case "next_task" -> doNextTask(payload, original);
            case "ship_task" -> doShipTask(payload, original);
            default -> throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "unsupported action: " + action);
        };
    }

    /**
     * Verify payload fields and the parked task target while the
     * notification is still open. Once the claim is written we never
     * infer that an exception happened before the remote call.
     */
    private void preflightApprovedAction(
            String action, JsonNode payload, String editedBody, Notification original)
    {
        switch (action) {
            case "push" -> {
                String worktreePath = payload.path("worktreePath").asText("");
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
            case "post_comment" -> {
                readPrRef(payload, action);
                requireEditableBody(payload, editedBody, "comment body is blank — nothing to post");
            }
            case "reply_review_thread" -> {
                readPrRef(payload, action);
                if (payload.path("rootCommentId").asLong(0L) <= 0L) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked reply_review_thread notification has no rootCommentId");
                }
                requireEditableBody(payload, editedBody, "reply body is blank — nothing to post");
            }
            case "approve_pr", "merge_pr" -> readPrRef(payload, action);
            case "create_review_comment" -> {
                readPrRef(payload, action);
                requireEditableBody(payload, editedBody, "review comment body is blank — nothing to post");
                String filePath = payload.path("filePath").asText("");
                int line = payload.path("line").asInt(0);
                String commitId = payload.path("commitId").asText("");
                if (filePath.isBlank() || line <= 0 || commitId.isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked create_review_comment notification is missing filePath / line / commitId");
                }
            }
            case "update_pr_body" -> {
                readPrRef(payload, action);
                requireEditableBody(payload, editedBody, "PR body is blank — nothing to update");
            }
            case "request_reviewer" -> {
                readPrRef(payload, action);
                if (payload.path("reviewer").asText("").trim().isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked request_reviewer notification has no reviewer login");
                }
            }
            case "comment_on_issue" -> {
                readIssueRef(payload, action);
                requireEditableBody(payload, editedBody, "comment body is blank — nothing to post");
            }
            case "set_issue_state" -> {
                readIssueRef(payload, action);
                String state = payload.path("state").asText("");
                if (!"open".equals(state) && !"closed".equals(state)) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "parked set_issue_state notification has invalid state: " + state);
                }
            }
            case "open_pr" -> preflightOpenPr(payload);
            case "publish_review" -> preflightPublishReview(payload);
            case "request_review" -> {
                // The MCP park path has already verified it has a diff.
            }
            case "next_task", "ship_task" -> preflightAdvance(payload, original, action);
            default -> throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "unsupported action: " + action);
        }
    }

    private static void requireEditableBody(JsonNode payload, String editedBody, String message)
    {
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? payload.path("body").asText("")
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), message);
        }
    }

    private static void preflightOpenPr(JsonNode payload)
    {
        JsonNode repo = payload.path("repo");
        if (repo.isMissingNode() || repo.isNull()
                || repo.path("owner").asText("").isBlank()
                || repo.path("repo").asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has incomplete repo ref");
        }
        if (payload.path("title").asText("").isBlank()
                || payload.path("head").asText("").isBlank()
                || payload.path("base").asText("").isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification is missing title / head / base");
        }
    }

    private static void preflightPublishReview(JsonNode payload)
    {
        readPrRef(payload, "publish_review");
        JsonNode commentsNode = payload.path("comments");
        if (!commentsNode.isArray()) {
            return;
        }
        for (JsonNode comment : commentsNode) {
            if (comment.path("file_path").asText("").isBlank()
                    || comment.path("line").asInt(0) <= 0
                    || comment.path("body").asText("").isBlank()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                        "publish_review comment is missing file_path / line / body");
            }
        }
    }

    private void preflightAdvance(JsonNode payload, Notification original, String action)
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
        JsonNode nextTitleNode = payload.path("nextTitle");
        if (!nextTitleNode.isMissingNode() && !nextTitleNode.isNull() && !nextTitleNode.isTextual()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid nextTitle");
        }
        String baseModeRaw = payload.path("baseMode").asText("main");
        if (!"main".equals(baseModeRaw) && !"stacked".equals(baseModeRaw)) {
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
        JsonNode payload = parsePayload(original);
        String action = resolveAction(payload);
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

    private JsonNode parsePayload(Notification original)
    {
        try {
            String json = original.payloadJson() == null ? "{}" : original.payloadJson();
            return mapper.readTree(json);
        }
        catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "notification payload is not valid JSON: " + e.getMessage());
        }
    }

    private static String resolveAction(JsonNode payload)
    {
        String action = payload.path("action").asText("");
        if (action.isBlank()
                && "mcp:request_review".equals(payload.path("source").asText(""))
                && payload.path("summary").isTextual()) {
            return "request_review";
        }
        return action;
    }

    private PublishResult doPush(JsonNode payload)
    {
        String worktreePath = payload.path("worktreePath").asText("");
        if (worktreePath.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked push notification has no worktreePath");
        }
        Path worktree = Path.of(worktreePath);
        String branch = payload.path("branch").asText("the branch");
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
        return new PublishResult(true, "approved",
                "Pushed " + branch + " from " + worktree + ".", "push");
    }

    private PublishResult doPostComment(JsonNode payload, String editedBody)
    {
        JsonNode pr = payload.path("pr");
        if (pr.isMissingNode() || pr.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked post_comment notification has no pr ref");
        }
        String owner = pr.path("owner").asText("");
        String repo = pr.path("repo").asText("");
        int number = pr.path("number").asInt(0);
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked post_comment notification has incomplete pr ref");
        }
        String parkedBody = payload.path("body").asText("");
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? parkedBody
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment body is blank — nothing to post");
        }
        PullRequestRef ref = new PullRequestRef(owner, repo, number);
        String pat = patResolver.resolve(owner + "/" + repo);
        pullRequests.createIssueComment(pat, ref, effectiveBody);
        return new PublishResult(true, "approved",
                "Posted comment on " + owner + "/" + repo + "#" + number + ".",
                "post_comment");
    }

    private PublishResult doReplyReviewThread(JsonNode payload, String editedBody)
    {
        JsonNode pr = payload.path("pr");
        if (pr.isMissingNode() || pr.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked reply_review_thread notification has no pr ref");
        }
        String owner = pr.path("owner").asText("");
        String repo = pr.path("repo").asText("");
        int number = pr.path("number").asInt(0);
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked reply_review_thread notification has incomplete pr ref");
        }
        long rootCommentId = payload.path("rootCommentId").asLong(0L);
        if (rootCommentId <= 0L) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked reply_review_thread notification has no rootCommentId");
        }
        String parkedBody = payload.path("body").asText("");
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? parkedBody
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "reply body is blank — nothing to post");
        }
        PullRequestRef ref = new PullRequestRef(owner, repo, number);
        String pat = patResolver.resolve(owner + "/" + repo);
        pullRequests.replyToReviewComment(pat, ref, rootCommentId, effectiveBody);
        return new PublishResult(true, "approved",
                "Replied in review thread on " + owner + "/" + repo + "#" + number + ".",
                "reply_review_thread");
    }

    private PublishResult doApprovePr(JsonNode payload, String editedBody)
    {
        PrRefFromPayload ref = readPrRef(payload, "approve_pr");
        String parkedBody = payload.path("body").asText("");
        // approve_pr's body is optional. Distinguish "user never
        // overrode the textarea" (editedBody == null — for callers
        // that don't render an editor at all) from "user explicitly
        // cleared the textarea" (editedBody == ""). The latter should
        // honour the user's blank — the gate's editable textarea
        // makes clearing a real intent, not an indication to fall
        // back to the agent's suggestion.
        String effectiveBody = editedBody == null ? parkedBody : editedBody;
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        // GitHub's review-create endpoint accepts an empty body for
        // an APPROVE; the SDK uses Optional<String> so pass empty
        // when the user didn't type anything.
        CreateReviewCommand command = new CreateReviewCommand(
                Optional.empty(),
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                "APPROVE",
                ImmutableList.of());
        pullRequests.createReview(pat, ref.toRef(), command);
        return new PublishResult(true, "approved",
                "Approved " + ref.owner() + "/" + ref.repo() + "#" + ref.number() + ".",
                "approve_pr");
    }

    private PublishResult doMergePr(JsonNode payload)
    {
        PrRefFromPayload ref = readPrRef(payload, "merge_pr");
        String strategy = payload.path("strategy").asText("squash");
        MergePullRequestCommand command = switch (strategy) {
            case "merge" -> MergePullRequestCommand.mergeCommit();
            case "rebase" -> MergePullRequestCommand.rebase();
            default -> MergePullRequestCommand.squash();
        };
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        pullRequests.mergePullRequest(pat, ref.toRef(), command);
        return new PublishResult(true, "approved",
                "Merged " + ref.owner() + "/" + ref.repo() + "#" + ref.number()
                        + " (" + strategy + ").",
                "merge_pr");
    }

    private PublishResult doCreateReviewComment(JsonNode payload, String editedBody)
    {
        PrRefFromPayload ref = readPrRef(payload, "create_review_comment");
        String parkedBody = payload.path("body").asText("");
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? parkedBody
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "review comment body is blank — nothing to post");
        }
        String filePath = payload.path("filePath").asText("");
        int line = payload.path("line").asInt(0);
        String commitId = payload.path("commitId").asText("");
        if (filePath.isBlank() || line <= 0 || commitId.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked create_review_comment notification is missing filePath / line / commitId");
        }
        String side = payload.path("side").asText("RIGHT");
        Integer startLine = payload.path("startLine").isNumber()
                ? payload.path("startLine").asInt()
                : null;
        String startSide = payload.path("startSide").asText("");
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        pullRequests.createInlineReviewComment(
                pat, ref.toRef(),
                effectiveBody, filePath, line, side, commitId,
                startLine,
                startSide.isBlank() ? null : startSide);
        return new PublishResult(true, "approved",
                "Posted review comment on " + ref.owner() + "/" + ref.repo()
                        + "#" + ref.number() + " · " + filePath + ":" + line + ".",
                "create_review_comment");
    }

    private PublishResult doUpdatePrBody(JsonNode payload, String editedBody)
    {
        PrRefFromPayload ref = readPrRef(payload, "update_pr_body");
        String parkedBody = payload.path("body").asText("");
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? parkedBody
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "PR body is blank — nothing to update");
        }
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        UpdatePullRequestCommand command = new UpdatePullRequestCommand(
                Optional.empty(),
                Optional.of(effectiveBody),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        pullRequests.updatePullRequest(pat, ref.toRef(), command);
        return new PublishResult(true, "approved",
                "Updated PR body on " + ref.owner() + "/" + ref.repo() + "#" + ref.number() + ".",
                "update_pr_body");
    }

    private PublishResult doRequestReviewer(JsonNode payload)
    {
        PrRefFromPayload ref = readPrRef(payload, "request_reviewer");
        String reviewer = payload.path("reviewer").asText("").trim();
        if (reviewer.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked request_reviewer notification has no reviewer login");
        }
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        RequestReviewersCommand command = new RequestReviewersCommand(
                ImmutableList.of(reviewer),
                ImmutableList.of());
        pullRequests.requestReviewers(pat, ref.toRef(), command);
        return new PublishResult(true, "approved",
                "Requested " + reviewer + " on " + ref.owner() + "/"
                        + ref.repo() + "#" + ref.number() + ".",
                "request_reviewer");
    }

    private PublishResult doCommentOnIssue(JsonNode payload, String editedBody)
    {
        IssueRefFromPayload ref = readIssueRef(payload, "comment_on_issue");
        String parkedBody = payload.path("body").asText("");
        String effectiveBody = (editedBody == null || editedBody.isBlank())
                ? parkedBody
                : editedBody;
        if (effectiveBody.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "comment body is blank — nothing to post");
        }
        // GitHub's issue-comment endpoint is the same for issues and
        // PRs — PullRequestRef carries the (owner, repo, number)
        // triple either way.
        PullRequestRef forApi = new PullRequestRef(ref.owner(), ref.repo(), ref.number());
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        pullRequests.createIssueComment(pat, forApi, effectiveBody);
        return new PublishResult(true, "approved",
                "Posted comment on " + ref.owner() + "/" + ref.repo() + "#" + ref.number() + ".",
                "comment_on_issue");
    }

    private PublishResult doSetIssueState(JsonNode payload)
    {
        IssueRefFromPayload ref = readIssueRef(payload, "set_issue_state");
        String state = payload.path("state").asText("");
        if (!"open".equals(state) && !"closed".equals(state)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked set_issue_state notification has invalid state: " + state);
        }
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        pullRequests.setIssueState(pat, new RepoRef(ref.owner(), ref.repo()), ref.number(), state);
        return new PublishResult(true, "approved",
                "Set " + ref.owner() + "/" + ref.repo() + "#" + ref.number()
                        + " to " + state + ".",
                "set_issue_state");
    }

    private PublishResult doOpenPr(JsonNode payload, String editedBody)
    {
        JsonNode repo = payload.path("repo");
        if (repo.isMissingNode() || repo.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has no repo ref");
        }
        String owner = repo.path("owner").asText("");
        String repoName = repo.path("repo").asText("");
        if (owner.isBlank() || repoName.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification has incomplete repo ref");
        }
        String title = payload.path("title").asText("");
        String head = payload.path("head").asText("");
        String base = payload.path("base").asText("");
        if (title.isBlank() || head.isBlank() || base.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked open_pr notification is missing title / head / base");
        }
        String parkedBody = payload.path("body").asText("");
        // open_pr's body is optional. null = no override (use the
        // agent's parked body); "" = user explicitly cleared the
        // textarea and wants a blank PR description.
        String effectiveBody = editedBody == null ? parkedBody : editedBody;
        boolean draft = payload.path("draft").asBoolean(false);
        CreatePullRequestCommand command = new CreatePullRequestCommand(
                head,
                base,
                title,
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                draft ? Optional.of(true) : Optional.empty(),
                Optional.empty());
        String pat = patResolver.resolve(owner + "/" + repoName);
        pullRequests.createPullRequest(pat, new RepoRef(owner, repoName), command);
        return new PublishResult(true, "approved",
                "Opened PR " + owner + "/" + repoName + " · " + head + " → " + base
                        + (draft ? " (draft)" : "") + ".",
                "open_pr");
    }

    private PublishResult doPublishReview(JsonNode payload, String editedBody)
    {
        PrRefFromPayload ref = readPrRef(payload, "publish_review");
        String event = payload.path("event").asText("COMMENT");
        if (!"APPROVE".equals(event) && !"REQUEST_CHANGES".equals(event) && !"COMMENT".equals(event)) {
            event = "COMMENT";
        }
        String parkedBody = payload.path("body").asText("");
        // publish_review's review-level body is optional. null = no
        // override; "" = user explicitly cleared (APPROVE/REQUEST_CHANGES
        // can land without any review-level text).
        String effectiveBody = editedBody == null ? parkedBody : editedBody;
        JsonNode commentsNode = payload.path("comments");
        ImmutableList.Builder<CreateReviewCommand.ReviewLineComment> commentsBuilder = ImmutableList.builder();
        if (commentsNode.isArray()) {
            for (JsonNode c : commentsNode) {
                String filePath = c.path("file_path").asText("");
                int line = c.path("line").asInt(0);
                String body = c.path("body").asText("");
                if (filePath.isBlank() || line <= 0 || body.isBlank()) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                            "publish_review comment is missing file_path / line / body");
                }
                String side = c.path("side").asText("RIGHT");
                Optional<Integer> startLine = c.path("start_line").isNumber()
                        ? Optional.of(c.path("start_line").asInt())
                        : Optional.empty();
                Optional<String> startSide = c.path("start_side").isTextual() && !c.path("start_side").asText().isBlank()
                        ? Optional.of(c.path("start_side").asText())
                        : Optional.empty();
                commentsBuilder.add(new CreateReviewCommand.ReviewLineComment(
                        filePath, Optional.empty(), Optional.of(line), side, body, startLine, startSide));
            }
        }
        CreateReviewCommand command = new CreateReviewCommand(
                Optional.empty(),
                effectiveBody.isBlank() ? Optional.empty() : Optional.of(effectiveBody),
                event,
                commentsBuilder.build());
        String pat = patResolver.resolve(ref.owner() + "/" + ref.repo());
        pullRequests.createReview(pat, ref.toRef(), command);
        return new PublishResult(true, "approved",
                "Published review on " + ref.owner() + "/" + ref.repo()
                        + "#" + ref.number() + " (" + event + ").",
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

    private PublishResult doNextTask(JsonNode payload, Notification original)
    {
        Task next = runApprovedAdvance(payload, original, "next_task");
        return new PublishResult(true, "approved",
                "Advanced from task " + original.taskId() + " to " + next.id()
                        + " on " + next.branchName() + ".",
                "next_task");
    }

    private PublishResult doShipTask(JsonNode payload, Notification original)
    {
        Task next = runApprovedAdvance(payload, original, "ship_task");
        return new PublishResult(true, "approved",
                "Shipped task " + original.taskId() + " \u2192 created " + next.id()
                        + " on " + next.branchName() + ".",
                "ship_task");
    }

    /**
     * Approves a parked task advance without reopening it as live
     * agent work. The user's Approve click is the publish
     * authorisation gate.
     */
    private Task runApprovedAdvance(JsonNode payload, Notification original, String action)
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
        JsonNode nextTitleNode = payload.path("nextTitle");
        if (!nextTitleNode.isMissingNode() && !nextTitleNode.isNull() && !nextTitleNode.isTextual()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid nextTitle");
        }
        String nextTitle = nextTitleNode.asText("");
        String baseModeRaw = payload.path("baseMode").asText("main");
        if (!"main".equals(baseModeRaw) && !"stacked".equals(baseModeRaw)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has an invalid baseMode");
        }
        TaskService.BaseMode mode = "stacked".equals(baseModeRaw)
                ? TaskService.BaseMode.STACKED
                : TaskService.BaseMode.MAIN;
        TaskService.ShipRequest request = new TaskService.ShipRequest(
                nextTitle.isBlank() ? null : nextTitle, mode);
        return "next_task".equals(action)
                ? taskService.startNextFromApprovedParkedTask(threadId, taskId, request)
                : taskService.shipApprovedParkedTask(threadId, taskId, request);
    }

    private static IssueRefFromPayload readIssueRef(JsonNode payload, String action)
    {
        JsonNode issue = payload.path("issue");
        if (issue.isMissingNode() || issue.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no issue ref");
        }
        String owner = issue.path("owner").asText("");
        String repo = issue.path("repo").asText("");
        int number = issue.path("number").asInt(0);
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has incomplete issue ref");
        }
        return new IssueRefFromPayload(owner, repo, number);
    }

    private record IssueRefFromPayload(String owner, String repo, int number) {}

    /** Reads (owner, repo, number) out of a parked payload's "pr"
     *  block, validating that all three are present. Centralises the
     *  shape so the publisher branches stay short. */
    private static PrRefFromPayload readPrRef(JsonNode payload, String action)
    {
        JsonNode pr = payload.path("pr");
        if (pr.isMissingNode() || pr.isNull()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has no pr ref");
        }
        String owner = pr.path("owner").asText("");
        String repo = pr.path("repo").asText("");
        int number = pr.path("number").asInt(0);
        if (owner.isBlank() || repo.isBlank() || number <= 0) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "parked " + action + " notification has incomplete pr ref");
        }
        return new PrRefFromPayload(owner, repo, number);
    }

    private record PrRefFromPayload(String owner, String repo, int number)
    {
        PullRequestRef toRef()
        {
            return new PullRequestRef(owner, repo, number);
        }
    }

    private void writeAuditRow(
            Notification original, String resolution, String action, String message)
    {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("publishResolution", resolution);
            payload.put("action", action);
            payload.put("originalNotificationId", original.id());
            payload.put("message", message);
            String json = mapper.writeValueAsString(payload);
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

    /**
     * Resolution shape the controller hands back to the frontend.
     * {@code ok} is true on success (approved, discarded, or recovered),
     * false while an interrupted attempt still needs a local resolution.
     * {@code resolution} is one of "approved", "discarded",
     * "interrupted", or "recovered".
     */
    public record PublishResult(boolean ok, String resolution, String message, String action) {}
}
