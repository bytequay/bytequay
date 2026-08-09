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
package com.bytequay.app.service.localpr;

import com.bytequay.app.developmentflow.execution.remote.SqliteExternalPrActionStore.Projection;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.developmentflow.stage.V2PrRemoteControlService;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewServiceImpl;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.threads.TaskService;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Owns explicit typed remote PR actions. */
@Service
public class PRPublishService
{
    private final PRService prService;
    private final TaskStore taskStore;
    private final V2PrRemoteControlService v2Controls;
    private final V2UserRemoteActionRuntime v2UserRemoteActions;

    public PRPublishService(
            PRService prService,
            TaskStore taskStore,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            BrainReviewServiceImpl brainReview,
            PullRequestService pullRequestDetails,
            ReadyToMergeService readyToMerge,
            TaskService taskService,
            V2PrRemoteControlService v2Controls,
            V2UserRemoteActionRuntime v2UserRemoteActions)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(patResolver, "patResolver is null");
        requireNonNull(brainReview, "brainReview is null");
        requireNonNull(pullRequestDetails, "pullRequestDetails is null");
        requireNonNull(readyToMerge, "readyToMerge is null");
        requireNonNull(taskService, "taskService is null");
        this.v2Controls = requireNonNull(v2Controls, "v2Controls is null");
        this.v2UserRemoteActions = requireNonNull(
                v2UserRemoteActions, "v2UserRemoteActions is null");
    }

    /**
     * Keep the PR row in step with a push/open-PR that just happened
     * through some other path (a push/open_pr gate, auto-approved or not; the
     * ship/next tool flow) instead of this service's own {@link #push}. That
     * row otherwise only advances when the user clicks the local-PR panel's
     * own Push button. V2 owns that projection in its publish result command;
     * this retained compatibility entry point therefore accepts V2 as a no-op
     * and fails closed for historical LEGACY Tasks.
     */
    public void onPushedElsewhere(PrPushedEvent event)
    {
        reconcilePushedElsewhere(event);
    }

    /** Compatibility entry point for callers such as the periodic PR sync. */
    void reconcilePushedElsewhere(PrPushedEvent event)
    {
        if (isV2Task(event.taskId())) {
            return;
        }
        throw legacyTaskPrRetired(event.taskId());
    }

    /**
     * Auto-merge's answer to the Local Review page's manual Push button: once
     * the dev-end brain review clears (the PR just reached {@code
     * local-open}), the V2 maintenance path may authorize publish. This old
     * event callback performs no scheduling: V2 is already handled by its
     * typed owner and historical LEGACY Tasks fail closed.
     */
    public void onLocalReviewCleared(LocalReviewClearedEvent event)
    {
        if (!event.approved() || isV2Task(event.taskId())) {
            return;
        }
        throw legacyTaskPrRetired(event.taskId());
    }

    /** Push {@code prId}'s branch and open a Draft PR, then strip locals + flip
     *  {@code local-open → remote-drafted}. The automation path keeps the
     *  review-quality gate (open comments, failing local checks). */
    public PR push(String prId)
    {
        return push(prId, false);
    }

    /**
     * As {@link #push(String)}, but {@code userOverride} lets the human's
     * explicit Approve &amp; ship proceed past the review-quality gate (open
     * comment threads, a failing local check) — the user is the final
     * authority on whether their own PR ships. Structural preconditions
     * (status, phase, working dir) still apply. Only the user-gated push
     * endpoint passes {@code true}; auto-merge keeps the gate.
     */
    public PR push(String prId, boolean userOverride)
    {
        return push(null, prId, userOverride);
    }

    /** V2 user action variant with a stable client command identity. */
    public PR push(String commandId, String prId, boolean userOverride)
    {
        Optional<PR> candidate = prService.findById(prId);
        if (candidate.isPresent() && isV2(candidate.orElseThrow())) {
            PR pr = candidate.orElseThrow();
            v2Controls.approveAndShip(
                    requireV2CommandId(commandId), pr.taskId(), prId, userOverride);
            return prService.findById(prId).orElse(pr);
        }
        candidate.ifPresent(PRPublishService::rejectLegacyTaskPr);
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Legacy PR publish is retired; use the typed V2 remote owner");
    }

    /**
     * User-gated merge of a pushed PR, then flip the local PR to {@code
     * merged}. {@code method} is merge / squash / rebase (defaults to
     * squash) — ignored when the target branch has merge queue enabled,
     * since the queue's own configured method wins there. Mirrors {@link
     * com.bytequay.app.service.pr.PullRequestService#mergePullRequest}'s
     * probe-then-dispatch: a queue-enabled branch enqueues via GraphQL
     * instead of attempting a direct REST merge; a 405 mid-merge (a
     * ruleset-driven queue the probe couldn't see) falls back to enqueueing
     * too. A successful enqueue is not a merge — the PR isn't flipped to
     * {@code merged} here; the next sync picks up the fresh {@code
     * mergeQueueState} and, once the queue actually lands the merge, the
     * fresh {@code status=merged} from GitHub.
     */
    public PR merge(String prId, String method)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    /** V2 user action variant with a stable client command identity. */
    public PR merge(String commandId, String prId, String method)
    {
        PR identified = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (isV2(identified)) {
            v2Controls.merge(
                    requireV2CommandId(commandId), identified.taskId(), method);
            return prService.findById(prId).orElse(identified);
        }
        rejectLegacyTaskPr(identified);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), prId, SemanticAction.MERGE,
                ActionPayload.value(method == null ? "REBASE" : method));
        return identified;
    }

    /** User-gated removal of a pushed PR from its repo's merge queue —
     *  mirrors github.com's "Remove from queue" button. No-op on GitHub's
     *  side when the PR isn't queued. */
    public PR dequeue(String prId)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    public PR dequeue(String commandId, String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "local PR " + prId + " has not been pushed");
        }
        if (isV2(pr)) {
            v2UserRemoteActions.dequeue(
                    requireV2CommandId(commandId), pr.taskId(), prId);
            return prService.findById(prId).orElse(pr);
        }
        rejectLegacyTaskPr(pr);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), prId, SemanticAction.DEQUEUE,
                ActionPayload.empty());
        return prService.findById(prId).orElse(pr);
    }

    /** User-gated deletion of a merged PR's head branch on GitHub — mirrors
     *  github.com's post-merge "Delete branch" button. Stamps {@code
     *  branchDeletedAt} so the button disappears afterward. */
    public PR deleteBranch(String prId)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    public PR deleteBranch(String commandId, String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (!PR.STATUS_MERGED.equals(pr.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "local PR " + prId + " is not merged");
        }
        if (isV2(pr)) {
            v2UserRemoteActions.deleteRemoteBranch(
                    requireV2CommandId(commandId), pr.taskId(), prId,
                    pr.branchName());
            return prService.findById(prId).orElse(pr);
        }
        rejectLegacyTaskPr(pr);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), prId,
                SemanticAction.DELETE_REMOTE_BRANCH,
                new ActionPayload(
                        1, null, null, pr.branchName(), List.of()));
        return pr;
    }

    /** Explicit user action from the GitHub-style PR composer. Posts a
     * top-level issue comment to the pushed PR for either origin. */
    public PR postComment(String prId, String body)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    public PR postComment(String commandId, String prId, String body)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR " + prId + " has no remote identity");
        }
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment body is empty");
        }
        if (isV2(pr)) {
            v2UserRemoteActions.postTopLevelComment(
                    requireV2CommandId(commandId), pr.taskId(), prId, body.trim(),
                    HandledAction.COMMENTED);
            return prService.findById(prId).orElse(pr);
        }
        rejectLegacyTaskPr(pr);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), prId,
                null, SemanticAction.POST_TOP_LEVEL_COMMENT,
                ActionPayload.body(body.trim()),
                HandledAction.COMMENTED.name());
        return pr;
    }

    /**
     * Batch every unpublished, unresolved-and-not-dismissed local draft on an
     * remote PR into one GitHub review, then mark each published. File-line drafts
     * become the review's inline comments (against the RIGHT/added side —
     * ByteQuay doesn't track diff side per draft); pr-scoped drafts join into
     * the review's summary body.
     */
    public PR publishReview(String prId)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    /** Publish only the explicitly included investigation findings/comments
     * with the user's chosen GitHub review verdict. Null id lists preserve
     * the legacy request-without-a-selection behavior; present empty lists
     * explicitly select no comments. */
    public PR publishReview(
            String prId, String verdict, List<String> findingIds, List<String> commentIds)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    /** Publishes the selected draft comments plus an optional overall review
     * body. Any PR with a remote identity may publish: dashboard-discovered
     * external PRs throughout their lifecycle, and task-owned PRs once they've
     * reached the remote stage. Drafts stamped {@code strippedOnPushAt} (the
     * pre-push private review on a task PR) never reach GitHub. */
    public PR publishReview(
            String prId, String verdict, List<String> findingIds, List<String> commentIds, String reviewBody)
    {
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is required for a V2 remote action");
    }

    public PR publishReview(
            String commandId,
            String prId,
            String verdict,
            List<String> findingIds,
            List<String> commentIds,
            String reviewBody)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.repo() == null || pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR " + prId + " has no remote identity to review");
        }
        Set<String> selectedFindings = findingIds == null ? ImmutableSet.of() : ImmutableSet.copyOf(findingIds);
        Set<String> selectedComments = commentIds == null ? ImmutableSet.of() : ImmutableSet.copyOf(commentIds);
        boolean selectAll = findingIds == null && commentIds == null;
        List<PRComment> drafts = prService.comments(prId).stream()
                .filter(c -> PRComment.ORIGIN_LOCAL.equals(c.origin()))
                .filter(c -> c.parentCommentId() == null)
                .filter(c -> c.publishedAt() == null && c.strippedOnPushAt() == null
                        && c.resolvedAt() == null && c.dismissedAt() == null)
                .filter(c -> selectAll || selectedComments.contains(c.id())
                        || c.findingId() != null && selectedFindings.contains(c.findingId()))
                .toList();
        String event = switch (verdict == null ? "COMMENT" : verdict.toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> "APPROVE";
            case "REQUEST_CHANGES" -> "REQUEST_CHANGES";
            default -> "COMMENT";
        };
        boolean explicitlySelectedNothing = !selectAll
                && selectedFindings.isEmpty() && selectedComments.isEmpty();
        String requestedBody = reviewBody == null ? "" : reviewBody.strip();
        if (drafts.isEmpty() && requestedBody.isEmpty()
                && !(explicitlySelectedNothing && "APPROVE".equals(event))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no draft comments to publish for PR " + prId);
        }

        String draftBody = drafts.stream()
                .filter(c -> PRComment.SCOPE_PR.equals(c.scope()))
                .map(PRComment::body)
                .collect(Collectors.joining("\n\n"));
        String body = requestedBody.isEmpty() ? draftBody
                : draftBody.isEmpty() ? requestedBody : requestedBody + "\n\n" + draftBody;
        HandledAction handledAction = switch (event) {
            case "APPROVE" -> HandledAction.APPROVED;
            case "REQUEST_CHANGES" -> HandledAction.CHANGES_REQUESTED;
            default -> HandledAction.COMMENTED;
        };
        if (isV2(pr)) {
            v2UserRemoteActions.submitReview(
                    requireV2CommandId(commandId), pr.taskId(), prId, event,
                    body, drafts,
                    handledAction);
            return prService.findById(prId).orElse(pr);
        }
        rejectLegacyTaskPr(pr);
        v2UserRemoteActions.publishExternalReview(
                requireV2CommandId(commandId), prId, null, event, body, drafts,
                handledAction);
        return pr;
    }

    public Optional<Projection> findExternalReviewPublication(String prId)
    {
        return v2UserRemoteActions.findExternalReviewPublication(prId);
    }

    private void markReviewRequestHandled(PR pr, HandledAction action)
    {
        if (!pr.isTerminal()
                && pr.githubSync() != null
                && pr.githubSync().watchReason() == PullRequest.Origin.REVIEW_REQUESTED) {
            prService.markHandled(pr.id(), action);
        }
    }

    /**
     * Resolve the immutable workflow owner for a GitHub PR before a generic
     * dashboard endpoint performs a remote write. An empty result is a proven
     * non-V2 route; inconsistent V2 ownership fails closed instead of falling
     * through to a direct GitHub mutation.
     */
    public Optional<PR> findV2TaskPullRequest(String repo, int remotePrNumber)
    {
        Optional<PR> taskPr = prService.findTaskByRepoAndNumber(repo, remotePrNumber);
        if (taskPr.isPresent()) {
            PR pr = taskPr.orElseThrow();
            if (pr.taskId() == null || pr.taskId().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Task-owned PR " + repo + "#" + remotePrNumber
                                + " has no Task identity");
            }
            if (workflowVersion(pr.taskId())) {
                return taskPr;
            }
            throw legacyTaskPrRetired(pr.taskId());
        }

        String ref = repo + "#" + remotePrNumber;
        List<Task> linked = taskStore.findTasksByPrRef(ref);
        if (linked.isEmpty()) {
            linked = taskStore.findActiveTaskByPrRef(ref).stream().toList();
        }
        if (!linked.isEmpty()) {
            Task owner = linked.getLast();
            if (workflowVersion(owner.id())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "V2 Task " + owner.id() + " owns " + ref
                                + " but its local PR identity is unavailable");
            }
            throw legacyTaskPrRetired(owner.id());
        }
        return Optional.empty();
    }

    /** Resolve the taskless aggregate used by the V289 REVIEW-Trunk route. */
    public Optional<PR> findExternalPullRequest(String repo, int remotePrNumber)
    {
        return prService.findByRepoAndNumber(repo, remotePrNumber)
                .filter(pr -> pr.taskId() == null);
    }

    private boolean isV2(PR pr)
    {
        return pr.taskId() != null && !pr.taskId().isBlank()
                && workflowVersion(pr.taskId());
    }

    private static void rejectLegacyTaskPr(PR pr)
    {
        if (pr.taskId() != null && !pr.taskId().isBlank()) {
            throw legacyTaskPrRetired(pr.taskId());
        }
    }

    private static ResponseStatusException legacyTaskPrRetired(String taskId)
    {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Historical LEGACY Task-owned PR for " + taskId
                        + " is read-only; use the typed V2 remote owner");
    }

    private static String requireV2CommandId(String commandId)
    {
        if (commandId == null || commandId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required for a V2 remote action");
        }
        return commandId;
    }

    private boolean isV2Task(String taskId)
    {
        return taskId != null && !taskId.isBlank() && workflowVersion(taskId);
    }

    private boolean workflowVersion(String taskId)
    {
        return taskStore.findWorkflowVersion(taskId)
                .map(version -> {
                    if (!"V2".equals(version) && !"LEGACY".equals(version)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "unsupported Task workflow version " + version);
                    }
                    return "V2".equals(version);
                })
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Task " + taskId + " has no immutable workflow route"));
    }

    private static MergePullRequestCommand mergeCommand(String method)
    {
        return switch (method == null ? "squash" : method) {
            case "merge" -> MergePullRequestCommand.mergeCommit();
            case "rebase" -> MergePullRequestCommand.rebase();
            default -> MergePullRequestCommand.squash();
        };
    }
}
