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

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.CreateReviewCommand;
import com.bytequay.app.domain.CreateReviewCommand.ReviewLineComment;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * The user-gated push transition (local → remote). This is the one place the
 * local PR touches GitHub: it pushes the branch to origin and opens a Draft
 * PR, then hands off to {@link PRService#recordPush} which strips the
 * private local record (design #47) and flips the status. Agents never reach
 * here — push is user-initiated through {@code PRController}.
 *
 * <p>A local PR pushes to its own origin and opens against origin's base, so
 * (unlike the fork-aware {@code PublishService}) there is no cross-fork head:
 * the head is the bare branch name.
 *
 * <p>Promotion gate (design doc slice 5): {@link #push} refuses while any
 * comment thread is still open, or the most recently recorded local test run
 * failed. A repo with no recognised test runner (so no {@code pr_check}
 * row ever gets written) is treated as having nothing to gate on — "don't
 * ship red" means don't ship a known failure, not "require a runner exist."
 */
@Service
public class PRPublishService
{
    private static final Logger log = LoggerFactory.getLogger(PRPublishService.class);

    private static final String LINKED_STATUS_DRAFT = "draft";

    private final PRService prService;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final BrainReviewService brainReview;
    private final TaskPhaseMachine phaseMachine;

    public PRPublishService(
            PRService prService,
            TaskStore taskStore,
            GitRunner git,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            BrainReviewService brainReview,
            TaskPhaseMachine phaseMachine)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
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
        try {
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

    /** Push {@code prId}'s branch and open a Draft PR, then strip locals + flip
     *  {@code local-open → remote-drafted}. */
    public PR push(String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (!PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "local PR " + prId + " is not ready to push (status=" + pr.status() + ")");
        }
        long openComments = openCommentCount(prId);
        if (openComments > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "local PR " + prId + " has " + openComments + " open comment thread(s) — "
                            + "resolve or dismiss them before promoting");
        }
        if (latestLocalCheckFailed(prId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "local PR " + prId + " has a failing local test run — fix it before promoting");
        }
        Task task = taskStore.findTaskById(pr.taskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no task for local PR " + prId));
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task " + task.id() + " has no working dir");
        }
        RepoRef repo = remoteSlug(task);
        pushBranch(task, pr.branchName());
        String pat = patResolver.resolve(repo.owner() + "/" + repo.repo());
        PullRequest opened = pullRequests.createPullRequest(
                pat, repo, CreatePullRequestCommand.draft(pr.branchName(), pr.baseBranch(), pr.title(), pr.description()));
        if (opened == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub did not return the opened PR");
        }
        // Mirror the push onto the task row so the rest of the app sees the
        // pushed/linked state, then strip locals + flip the local PR status.
        taskStore.markPushed(task.id(), Instant.now());
        taskStore.linkPullRequest(task.id(), opened.number(), LINKED_STATUS_DRAFT);
        taskStore.linkTaskToPr(task.id(), opened.repo() + "#" + opened.number());
        // This push bypasses the gated Push/OpenPr proposal flow (the local
        // PR already survived its own review, so nothing pauses for
        // approval here) — advance the phase directly, or the task stays on
        // AWAITING_PUSH's local-only polling forever and TaskLifecycleDriver
        // never picks up CI state for it.
        phaseMachine.observe(task.id(), TaskPhase.PUSHED_AWAITING_CI, "local_pr_pushed");
        PR pushed = prService.recordPush(prId, repo.owner() + "/" + repo.repo(), opened.number(), opened.htmlUrl());
        return prService.updateAuthor(pushed.id(), actorLabel(opened.author()));
    }

    private static String actorLabel(String githubLogin)
    {
        return githubLogin == null || githubLogin.isBlank() ? null : "@" + githubLogin;
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
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no local PR " + prId));
        if (pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "local PR " + prId + " has not been pushed");
        }
        if (pr.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "local PR " + prId + " is already " + pr.status());
        }
        RemoteTarget target = resolveRemoteTarget(pr);
        String pat = target.pat();
        PullRequestRef ref = target.ref();

        // A still-draft PR can't merge or queue on GitHub — merging one
        // means "mark it ready for review, then merge". Mirrors the
        // mark-ready gate's own flip in PublishService.
        if (PR.STATUS_REMOTE_DRAFTED.equals(pr.status())) {
            pullRequests.setPullRequestDraft(pat, ref, false);
            pr = prService.transition(prId, PR.STATUS_REMOTE_OPEN, PRTimelineEntry.ACTOR_USER);
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
        return prService.recordMerged(prId);
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
     * body. Task-owned PRs are valid once they have a remote identity. */
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
                .filter(c -> c.publishedAt() == null
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
                        c.filePath(), Optional.empty(), Optional.of(c.lineNumber()), "RIGHT", c.body()))
                .toList();
        pullRequests.createReview(pat, ref, new CreateReviewCommand(
                Optional.empty(), body.isBlank() ? Optional.empty() : Optional.of(body), event, lineComments));

        Instant when = Instant.now();
        for (PRComment draft : drafts) {
            prService.markPublished(draft.id(), when);
        }
        return prService.findById(prId).orElse(pr);
    }

    private long openCommentCount(String prId)
    {
        return prService.comments(prId).stream()
                .filter(c -> c.parentCommentId() == null)
                .filter(c -> c.resolvedAt() == null && c.dismissedAt() == null)
                .count();
    }

    /** The most recently started local check, if any, failed. No local checks
     *  recorded at all (no recognised test runner) does not count as a
     *  failure — there is nothing to gate on. */
    private boolean latestLocalCheckFailed(String prId)
    {
        List<PRCheck> checks = prService.checks(prId);
        return checks.stream()
                .filter(c -> PRCheck.KIND_LOCAL.equals(c.kind()))
                .max(Comparator.comparing(PRCheck::startedAt))
                .map(c -> PRCheck.STATUS_FAILED.equals(c.status()))
                .orElse(false);
    }

    private static MergePullRequestCommand mergeCommand(String method)
    {
        return switch (method == null ? "squash" : method) {
            case "merge" -> MergePullRequestCommand.mergeCommit();
            case "rebase" -> MergePullRequestCommand.rebase();
            default -> MergePullRequestCommand.squash();
        };
    }

    private RepoRef remoteSlug(Task task)
    {
        try {
            return git.remoteSlug(Path.of(task.workingDir()), "origin")
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "could not resolve origin repo for task " + task.id()));
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "reading origin remote failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted resolving origin remote");
        }
    }

    /** Push the branch from the task worktree unless it is already on origin. */
    private void pushBranch(Task task, String branch)
    {
        String worktreePath = task.worktreePath() == null || task.worktreePath().isBlank()
                ? task.workingDir() : task.worktreePath();
        Path worktree = Path.of(worktreePath);
        try {
            if (git.refExists(worktree, "origin/" + branch)) {
                return;
            }
            git.push(worktree);
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "git push failed: " + e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "git push interrupted");
        }
    }
}
