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

import com.bytequay.app.config.AsyncConfig;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.threads.TaskExternalEffectGate;
import com.bytequay.app.service.threads.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Publishes local PRs through the durable push saga and owns the remaining
 * explicit remote PR actions (review, merge, dequeue, and branch deletion). */
@Service
public class PRPublishService
{
    private static final Logger log = LoggerFactory.getLogger(PRPublishService.class);

    private final PRService prService;
    private final TaskStore taskStore;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final BrainReviewService brainReview;
    private final PullRequestService pullRequestDetails;
    private final ReadyToMergeService readyToMerge;
    private final TaskService taskService;
    private final TaskPushSaga pushSaga;
    private final Executor executor;

    public PRPublishService(
            PRService prService,
            TaskStore taskStore,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            BrainReviewService brainReview,
            PullRequestService pullRequestDetails,
            ReadyToMergeService readyToMerge,
            TaskService taskService,
            TaskPushSaga pushSaga,
            @Qualifier(AsyncConfig.APPLICATION_EXECUTOR) Executor executor)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.pullRequestDetails = requireNonNull(pullRequestDetails, "pullRequestDetails is null");
        this.readyToMerge = requireNonNull(readyToMerge, "readyToMerge is null");
        this.taskService = requireNonNull(taskService, "taskService is null");
        this.pushSaga = requireNonNull(pushSaga, "pushSaga is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /**
     * Keep the PR row in step with a push/open-PR that just happened
     * through some other path (a push/open_pr gate, auto-approved or not; the
     * ship/next tool flow) instead of this service's own {@link #push}. That
     * row otherwise only advances when the user clicks the local-PR panel's
     * own Push button, so a push resolved elsewhere would leave it stuck
     * offering "ready to push" for a push that already happened. Runs after
     * the publishing transaction commits; best-effort — never fails the
     * caller over a sync miss.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPushedElsewhere(PrPushedEvent event)
    {
        submitAfterCommit("syncing local PR push state for task " + event.taskId(),
                () -> reconcilePushedElsewhere(event));
    }

    /** Synchronous reconciliation entry point for callers that are already
     * outside a publishing transaction, such as the periodic PR sync. */
    void reconcilePushedElsewhere(PrPushedEvent event)
    {
        try {
            if (pushSaga.adoptRemotePullRequest(
                    event.taskId(), event.repo(), event.remotePrNumber(), event.remotePrUrl())) {
                return;
            }
            prService.findByTask(event.taskId()).ifPresent(pr -> {
                PR current = pr;
                if (PR.STATUS_LOCAL_DRAFTED.equals(current.status())) {
                    current = brainReview.reviewBeforeLocalOpen(current.id(), PRTimelineEntry.ACTOR_AGENT);
                }
                if (PR.STATUS_LOCAL_OPEN.equals(current.status())) {
                    prService.recordPush(current.id(), event.repo(), event.remotePrNumber(), event.remotePrUrl());
                }
            });
        }
        catch (RuntimeException e) {
            log.warn("syncing local PR push state for task {} failed: {}", event.taskId(), e.getMessage());
        }
    }

    /**
     * Auto-merge's answer to the Local Review page's manual Push button: once
     * the dev-end brain review clears (the PR just reached {@code
     * local-open}), push straight to remote instead of waiting on that click
     * — but only for a clean approval (not a budget-exhaustion escalation,
     * R23) on a task opted into {@code auto_merge}. Best-effort and silent on
     * {@link #push}'s ordinary preconditions (an open comment thread, a
     * failing local check) — those just mean the manual button stays
     * available, same as for any other task.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLocalReviewCleared(LocalReviewClearedEvent event)
    {
        submitAfterCommit("auto-pushing local PR " + event.prId(),
                () -> autoPushAfterLocalReview(event));
    }

    private void autoPushAfterLocalReview(LocalReviewClearedEvent event)
    {
        if (!event.approved() || !taskStore.isAutoMerge(event.taskId())) {
            return;
        }
        try {
            push(event.prId());
            // The toggle is the user's standing approval for this gate. Keep
            // the decision on the PR even though no manual button was clicked.
            prService.recordGateApproval(event.prId(), "push", "auto-merge");
            log.info("auto-merge: approved and pushed local PR {} for task {} without waiting on the manual button",
                    event.prId(), event.taskId());
        }
        catch (RuntimeException e) {
            log.warn("auto-merge: push of local PR {} for task {} failed: {}",
                    event.prId(), event.taskId(), e.getMessage());
        }
    }

    private void submitAfterCommit(String action, Runnable work)
    {
        try {
            executor.execute(work);
        }
        catch (RuntimeException e) {
            log.warn("submitting {} failed: {}", action, e.getMessage());
        }
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
        return pushSaga.push(prId, userOverride);
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
        PR identified = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (identified.taskId() == null || identified.taskId().isBlank()) {
            return mergeLocked(prId, method);
        }
        return TaskExternalEffectGate.withEffectGate(
                identified.taskId(), () -> mergeLocked(prId, method));
    }

    private PR mergeLocked(String prId, String method)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (!PR.STATUS_REMOTE_OPEN.equals(pr.status()) || pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "local PR " + prId + " is not an open, review-ready remote PR");
        }
        RemoteTarget target = resolveRemoteTarget(pr);
        String pat = target.pat();
        PullRequestRef ref = target.ref();

        PullRequestDetail detail = pullRequestDetails.fetchFreshPullRequestDetail(
                ref.owner() + "/" + ref.repo(), ref.number());
        if (!readyToMerge.isReadyForMerge(pr.taskId(), detail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "pull request is not ready to merge: CI, approvals, comments, review rounds, and mergeability must be clear");
        }

        Optional<PullRequestRepository.MergeQueueProbe> probe;
        try {
            probe = pullRequests.probeMergeQueue(pat, ref);
        }
        catch (RuntimeException e) {
            log.debug("merge queue probe failed for local PR {}, falling back to direct merge: {}", prId, e.getMessage());
            probe = Optional.empty();
        }
        if (probe.isPresent()) {
            MergeResult queued = pullRequests.enqueuePullRequest(pat, probe.get().pullRequestNodeId());
            if (!queued.queued()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "GitHub did not queue PR #" + pr.remotePrNumber() + ": " + queued.message());
            }
            authorizeQueuedTaskMerge(pr);
            return prService.findById(prId).orElse(pr);
        }

        MergeResult result;
        try {
            result = pullRequests.mergePullRequest(pat, ref, mergeCommand(method));
        }
        catch (ResponseStatusException e) {
            if (requiresMergeQueue(e)) {
                Optional<String> nodeId = pullRequests.pullRequestNodeId(pat, ref);
                if (nodeId.isPresent()) {
                    MergeResult queued = pullRequests.enqueuePullRequest(pat, nodeId.get());
                    if (queued.queued()) {
                        authorizeQueuedTaskMerge(pr);
                        return prService.findById(prId).orElse(pr);
                    }
                }
            }
            throw e;
        }
        if (!result.merged()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "GitHub did not merge PR #" + pr.remotePrNumber() + ": " + result.message());
        }
        PR merged = prService.recordMerged(prId);
        if (pr.taskId() != null && !pr.taskId().isBlank()) {
            taskService.completeTasksForMergedPr(
                    ref.owner() + "/" + ref.repo(), ref.number());
        }
        return merged;
    }

    private void authorizeQueuedTaskMerge(PR pr)
    {
        if (pr.taskId() != null && !pr.taskId().isBlank()) {
            taskService.authorizeMergeForPr(pr.repo(), pr.remotePrNumber());
        }
    }

    /** True when a direct-merge rejection is GitHub requiring the change to
     *  go through the merge queue (HTTP 405 with a queue message) — mirrors
     *  {@link com.bytequay.app.service.pr.PullRequestService}'s own check. */
    private static boolean requiresMergeQueue(ResponseStatusException e)
    {
        return e.getStatusCode().value() == 405
                && e.getReason() != null
                && e.getReason().toLowerCase(Locale.ROOT).contains("merge queue");
    }

    /** User-gated removal of a pushed PR from its repo's merge queue —
     *  mirrors github.com's "Remove from queue" button. No-op on GitHub's
     *  side when the PR isn't queued. */
    public PR dequeue(String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "local PR " + prId + " has not been pushed");
        }
        RemoteTarget target = resolveRemoteTarget(pr);
        pullRequests.dequeuePullRequest(target.pat(), target.ref());
        return prService.findById(prId).orElse(pr);
    }

    /** User-gated deletion of a merged PR's head branch on GitHub — mirrors
     *  github.com's post-merge "Delete branch" button. Stamps {@code
     *  branchDeletedAt} so the button disappears afterward. */
    public PR deleteBranch(String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (!PR.STATUS_MERGED.equals(pr.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "local PR " + prId + " is not merged");
        }
        RemoteTarget target = resolveRemoteTarget(pr);
        pullRequests.deleteBranch(target.pat(), target.ref(), pr.branchName());
        return prService.recordBranchDeleted(prId);
    }

    /** Explicit user action from the GitHub-style PR composer. Posts a
     * top-level issue comment to the pushed PR for either origin. */
    public PR postComment(String prId, String body)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR " + prId + " has no remote identity");
        }
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "comment body is empty");
        }
        RemoteTarget target = resolveRemoteTarget(pr);
        pullRequests.createIssueComment(target.pat(), target.ref(), body.trim());
        markReviewRequestHandled(pr, HandledAction.COMMENTED);
        return pr;
    }

    /** Resolves the (PAT, REST ref) pair for a pushed PR of either origin. A
     *  pushed PR carries {@code repo}/{@code remotePrNumber} directly on its
     *  row (a task row is stamped at push time and repaired on startup), so
     *  the remote no longer needs re-deriving from the task's working dir —
     *  which may be gone once the PR is merged. */
    private RemoteTarget resolveRemoteTarget(PR pr)
    {
        if (pr.repo() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR " + pr.id() + " has no repo");
        }
        String[] ownerRepo = pr.repo().split("/", 2);
        return new RemoteTarget(
                patResolver.resolve(pr.repo()),
                new PullRequestRef(ownerRepo[0], ownerRepo[1], pr.remotePrNumber()));
    }

    private record RemoteTarget(String pat, PullRequestRef ref) {}

    /**
     * Batch every unpublished, unresolved-and-not-dismissed local draft on an
     * remote PR into one GitHub review, then mark each published. File-line drafts
     * become the review's inline comments (against the RIGHT/added side —
     * ByteQuay doesn't track diff side per draft); pr-scoped drafts join into
     * the review's summary body.
     */
    public PR publishReview(String prId)
    {
        return publishReview(prId, "COMMENT", null, null);
    }

    /** Publish only the explicitly included investigation findings/comments
     * with the user's chosen GitHub review verdict. Null id lists preserve
     * the legacy request-without-a-selection behavior; present empty lists
     * explicitly select no comments. */
    public PR publishReview(
            String prId, String verdict, List<String> findingIds, List<String> commentIds)
    {
        return publishReview(prId, verdict, findingIds, commentIds, null);
    }

    /** Publishes the selected draft comments plus an optional overall review
     * body. Any PR with a remote identity may publish: dashboard-discovered
     * external PRs throughout their lifecycle, and task-owned PRs once they've
     * reached the remote stage. Drafts stamped {@code strippedOnPushAt} (the
     * pre-push private review on a task PR) never reach GitHub. */
    public PR publishReview(
            String prId, String verdict, List<String> findingIds, List<String> commentIds, String reviewBody)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.repo() == null || pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR " + prId + " has no remote identity to review");
        }
        Set<String> selectedFindings = findingIds == null ? Set.of() : Set.copyOf(findingIds);
        Set<String> selectedComments = commentIds == null ? Set.of() : Set.copyOf(commentIds);
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

        String[] ownerRepo = pr.repo().split("/", 2);
        PullRequestRef ref = new PullRequestRef(ownerRepo[0], ownerRepo[1], pr.remotePrNumber());
        String pat = patResolver.resolve(pr.repo());
        String draftBody = drafts.stream()
                .filter(c -> PRComment.SCOPE_PR.equals(c.scope()))
                .map(PRComment::body)
                .collect(Collectors.joining("\n\n"));
        String body = requestedBody.isEmpty() ? draftBody
                : draftBody.isEmpty() ? requestedBody : requestedBody + "\n\n" + draftBody;
        List<ReviewLineComment> lineComments = drafts.stream()
                .filter(c -> PRComment.SCOPE_FILE_LINE.equals(c.scope()))
                .map(c -> new ReviewLineComment(
                        c.filePath(), Optional.empty(), Optional.of(c.lineNumber()),
                        c.side() == null ? "RIGHT" : c.side(), c.body(),
                        Optional.ofNullable(c.startLine()), Optional.ofNullable(c.startSide())))
                .toList();
        pullRequests.createReview(pat, ref, new CreateReviewCommand(
                Optional.empty(), body.isBlank() ? Optional.empty() : Optional.of(body), event, lineComments));
        markReviewRequestHandled(pr, switch (event) {
            case "APPROVE" -> HandledAction.APPROVED;
            case "REQUEST_CHANGES" -> HandledAction.CHANGES_REQUESTED;
            default -> HandledAction.COMMENTED;
        });

        Instant when = Instant.now();
        for (PRComment draft : drafts) {
            prService.markPublished(draft.id(), when);
        }
        return prService.findById(prId).orElse(pr);
    }

    private void markReviewRequestHandled(PR pr, HandledAction action)
    {
        if (!pr.isTerminal()
                && pr.githubSync() != null
                && pr.githubSync().watchReason() == PullRequest.Origin.REVIEW_REQUESTED) {
            prService.markHandled(pr.id(), action);
        }
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
