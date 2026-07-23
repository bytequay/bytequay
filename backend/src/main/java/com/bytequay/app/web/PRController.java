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
package com.bytequay.app.web;

import com.bytequay.app.beans.localpr.AddPRCommentRequest;
import com.bytequay.app.beans.localpr.CreatePRRequest;
import com.bytequay.app.beans.localpr.MarkHandledRequest;
import com.bytequay.app.beans.localpr.MergePRRequest;
import com.bytequay.app.beans.localpr.PRBundleDto;
import com.bytequay.app.beans.localpr.PRCheckDto;
import com.bytequay.app.beans.localpr.PRCommentDto;
import com.bytequay.app.beans.localpr.PRCommitDto;
import com.bytequay.app.beans.localpr.PRDashboardEntryDto;
import com.bytequay.app.beans.localpr.PRDto;
import com.bytequay.app.beans.localpr.PRTimelineEntryDto;
import com.bytequay.app.beans.localpr.SnoozePRRequest;
import com.bytequay.app.beans.localpr.UpdatePRRequest;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the local PR — the PR artifact that lives in ByteQuay
 * before it reaches GitHub. Reads and the create / update / comment mutations
 * are here; the user-gated {@code push} and {@code merge} transitions land in
 * a later phase (they perform GitHub I/O). Agents mutate the local PR through
 * the {@code record_pr_*} MCP tools inside a scheduler-dispatched turn, not
 * through this controller.
 */
@RestController
public class PRController
{
    private static final String USER_AUTHOR = PRTimelineEntry.ACTOR_USER;
    private static final String DEFAULT_BASE_BRANCH = "main";

    private final PRService prService;
    private final PRPublishService publish;
    private final PRSyncService sync;
    private final TaskStore taskStore;
    private final ObjectMapper mapper;
    private final RepoTestValidationCheck testRunner;
    private final PullRequestService pullRequests;
    private final InvestigationReviewService investigationReviews;

    public PRController(
            PRService prService,
            PRPublishService publish,
            PRSyncService sync,
            TaskStore taskStore,
            ObjectMapper mapper,
            RepoTestValidationCheck testRunner,
            PullRequestService pullRequests,
            InvestigationReviewService investigationReviews)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.publish = requireNonNull(publish, "publish is null");
        this.sync = requireNonNull(sync, "sync is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.testRunner = requireNonNull(testRunner, "testRunner is null");
        this.investigationReviews = requireNonNull(investigationReviews, "investigationReviews is null");
    }

    /** Resolver — the task's PR id, so the frontend's PR-scoped hook has
     *  something to key off of. Materialises/refreshes the row from the
     *  task's branch on read (same as the old task-scoped bundle fetch did),
     *  so the view has something to show even before an agent records its
     *  first commit via {@code record_pr_*}. */
    @GetMapping("/api/tasks/{taskId}/pr")
    public PRDto getForTask(@PathVariable String taskId)
    {
        return PRDto.from(sync.syncFromTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR for task " + taskId)));
    }

    @PostMapping("/api/tasks/{taskId}/pr")
    public PRDto create(@PathVariable String taskId, @RequestBody(required = false) CreatePRRequest body)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + taskId));
        if (task.branchName() == null || task.branchName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task " + taskId + " has no branch yet");
        }
        String baseBranch = task.baseBranch() == null || task.baseBranch().isBlank()
                ? DEFAULT_BASE_BRANCH : task.baseBranch();
        String title = body != null && body.title() != null && !body.title().isBlank()
                ? body.title()
                : task.name() != null && !task.name().isBlank() ? task.name() : task.branchName();
        String description = body == null ? "" : body.description();
        return PRDto.from(
                prService.createForTask(taskId, task.branchName(), baseBranch, title, description));
    }

    @GetMapping("/api/prs/{prId}")
    public PRDto getById(@PathVariable String prId)
    {
        return PRDto.from(prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /** Resolver for an external PR — the dashboard/details-page entry point
     *  for a GitHub PR that isn't tied to a ByteQuay task. Creates the row
     *  (origin=external) on first sight, syncing on every call after. */
    @GetMapping("/api/repos/{owner}/{repo}/prs/{number}")
    public PRDto getExternalPr(@PathVariable String owner, @PathVariable String repo, @PathVariable int number)
    {
        String slug = owner + "/" + repo;
        return PRDto.from(sync.syncExternalPR(slug, number)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + slug + "#" + number)));
    }

    /** The dashboard list — every PR the last {@code syncList} pass watched
     *  (authored or review-requested), flattened with its triage state. */
    @GetMapping("/api/prs")
    public List<PRDashboardEntryDto> dashboard()
    {
        return dashboardEntries();
    }

    /** Explicit user-triggered dashboard refresh (the list's manual-refresh
     *  button) — always sweeps GitHub, unlike the scheduled tick. */
    @PostMapping("/api/prs/sync-list")
    public List<PRDashboardEntryDto> syncDashboard()
    {
        sync.syncList();
        return dashboardEntries();
    }

    private List<PRDashboardEntryDto> dashboardEntries()
    {
        Map<String, String> reviewStates = investigationReviews.dashboardStates();
        return prService.dashboardEntries().stream()
                .map(entry -> PRDashboardEntryDto.from(
                        entry, reviewStates.getOrDefault(entry.pr().id(), "none")))
                .toList();
    }

    /** Records that the user opened this PR in the app. Idempotent. */
    @PostMapping("/api/prs/{prId}/viewed")
    public void markViewed(@PathVariable String prId)
    {
        prService.markViewed(prId);
    }

    /** Marks a PR handled with the given action, without any GitHub call —
     *  the dashboard's hover "Handled" affordance. */
    @PostMapping("/api/prs/{prId}/handle")
    public void markHandled(@PathVariable String prId, @RequestBody MarkHandledRequest body)
    {
        prService.markHandled(prId, HandledAction.valueOf(body.action()));
    }

    /** Clears the local reviewed timestamp so the PR returns to the Inbox. */
    @PostMapping("/api/prs/{prId}/reopen")
    public void reopen(@PathVariable String prId)
    {
        prService.reopen(prId);
    }

    /** Parks a PR until the given instant. */
    @PostMapping("/api/prs/{prId}/snooze")
    public void snooze(@PathVariable String prId, @RequestBody SnoozePRRequest body)
    {
        Instant until = Instant.parse(body.until());
        if (!until.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Snooze target must be in the future.");
        }
        prService.snooze(prId, until);
    }

    /** User-initiated wake — no wake-reason banner. */
    @PostMapping("/api/prs/{prId}/unsnooze")
    public void unsnooze(@PathVariable String prId)
    {
        prService.unsnooze(prId);
    }

    /** Drops the wake-reason flag once the user has seen the "PR woke up" banner. */
    @PostMapping("/api/prs/{prId}/clear-snooze-wake-reason")
    public void clearSnoozeWakeReason(@PathVariable String prId)
    {
        prService.clearSnoozeWakeReason(prId);
    }

    /** Submits a GitHub approval review, then records it as handled locally
     *  — the dashboard/inbox's "Approve" affordance for an external PR. */
    @PostMapping("/api/prs/{prId}/approve")
    public void approve(@PathVariable String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        if (pr.repo() == null || pr.remotePrNumber() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR " + prId + " has no remote identity yet");
        }
        pullRequests.submitApproval(pr.repo(), pr.remotePrNumber());
        prService.markHandled(prId, HandledAction.APPROVED);
    }

    /** The whole PR in one payload — {@code usePR} fetches this rather than
     *  five separate reads. Materialises/refreshes on read (task-origin picks
     *  up branch commits; either origin picks up the remote timeline once
     *  pushed), so the view shows real state even before an agent or a GitHub
     *  sync has caught up. */
    @GetMapping("/api/prs/{prId}/bundle")
    public PRBundleDto bundle(@PathVariable String prId)
    {
        PR pr = sync.syncPR(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        return new PRBundleDto(
                PRDto.from(pr),
                prService.commits(pr.id()).stream().map(PRCommitDto::from).toList(),
                prService.timeline(pr.id()).stream().map(e -> PRTimelineEntryDto.from(e, mapper)).toList(),
                prService.checks(pr.id()).stream().map(PRCheckDto::from).toList(),
                prService.comments(pr.id()).stream().map(PRCommentDto::from).toList(),
                prService.pendingStripCount(pr.id()));
    }

    /** Explicit user-triggered refresh — always probes GitHub (no maxAge
     *  short-circuit), unlike the passive sync a PR-bundle fetch performs. */
    @PostMapping("/api/prs/{prId}/sync")
    public PRDto syncPr(@PathVariable String prId)
    {
        return PRDto.from(sync.syncPR(prId, 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /**
     * On-demand local test run (design doc slice 4 — "runs at VALIDATING and
     * on demand"): the same {@link RepoTestValidationCheck} the VALIDATING
     * phase runs automatically, triggered manually from the Tests card.
     * Synchronous — a local desktop sidecar with one user, so a "run tests,
     * wait for it" click is the same shape as running it in a terminal; the
     * frontend shows a busy state for the call's duration.
     */
    @PostMapping("/api/prs/{prId}/run-tests")
    public List<PRCheckDto> runTests(@PathVariable String prId)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId));
        Task task = taskStore.findTaskById(pr.taskId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no task " + pr.taskId()));
        if (task.worktreePath() == null || task.worktreePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "task " + pr.taskId() + " has no worktree");
        }
        testRunner.run(task.id(), Path.of(task.worktreePath()));
        return prService.checks(prId).stream().map(PRCheckDto::from).toList();
    }

    /**
     * User-gated push: pushes the branch, opens a Draft PR on GitHub, strips
     * the private local record, and flips the PR to {@code remote-drafted}.
     * This is not an agent path — only the user's Approve &amp; push triggers it.
     */
    @PostMapping("/api/prs/{prId}/push")
    public PRDto push(@PathVariable String prId)
    {
        return PRDto.from(publish.push(prId));
    }

    /** User-gated merge of a pushed task-origin PR, then flip it to {@code merged}. */
    @PostMapping("/api/prs/{prId}/merge")
    public PRDto merge(@PathVariable String prId, @RequestBody(required = false) MergePRRequest body)
    {
        return PRDto.from(publish.merge(prId, body == null ? null : body.method()));
    }

    /** User-gated removal of a pushed PR from its repo's merge queue. */
    @DeleteMapping("/api/prs/{prId}/merge-queue")
    public PRDto dequeue(@PathVariable String prId)
    {
        return PRDto.from(publish.dequeue(prId));
    }

    /** User-gated deletion of a merged PR's head branch on GitHub. */
    @DeleteMapping("/api/prs/{prId}/branch")
    public PRDto deleteBranch(@PathVariable String prId)
    {
        return PRDto.from(publish.deleteBranch(prId));
    }

    public record PostRemoteCommentRequest(String body) {}

    /** Explicitly posts a top-level comment to GitHub, including after the
     * PR has merged, then refreshes the conversation timeline. */
    @PostMapping("/api/prs/{prId}/remote-comments")
    public PRDto postRemoteComment(
            @PathVariable String prId, @RequestBody PostRemoteCommentRequest body)
    {
        publish.postComment(prId, body.body());
        return PRDto.from(sync.syncPR(prId, 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    /** Batch every draft comment on an {@code origin=external} PR into one
     *  GitHub review, then re-sync so the review appears on the timeline. */
    @PostMapping("/api/prs/{prId}/publish-review")
    public PRDto publishReview(
            @PathVariable String prId, @RequestBody(required = false) PublishReviewRequest body)
    {
        String verdict = body == null ? "COMMENT" : body.verdict();
        List<String> findingIds = body == null ? null : body.findingIds();
        List<String> commentIds = body == null ? null : body.comments();
        String reviewBody = body == null ? null : body.body();
        PR published = publish.publishReview(prId, verdict, findingIds, commentIds, reviewBody);
        investigationReviews.recordPublished(prId, verdict, findingIds, commentIds);
        if (!PR.ORIGIN_EXTERNAL.equals(published.origin())) {
            return PRDto.from(published);
        }
        return PRDto.from(sync.syncPR(prId, 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no PR " + prId)));
    }

    public record PublishReviewRequest(
            String verdict, List<String> findingIds, List<String> comments, String body) {}

    @PatchMapping("/api/prs/{prId}")
    public PRDto update(@PathVariable String prId, @RequestBody(required = false) UpdatePRRequest body)
    {
        String title = body == null ? null : body.title();
        String description = body == null ? null : body.description();
        return PRDto.from(prService.updateDetails(prId, title, description));
    }

    @GetMapping("/api/prs/{prId}/timeline")
    public List<PRTimelineEntryDto> timeline(@PathVariable String prId)
    {
        return prService.timeline(prId).stream().map(e -> PRTimelineEntryDto.from(e, mapper)).toList();
    }

    @GetMapping("/api/prs/{prId}/commits")
    public List<PRCommitDto> commits(@PathVariable String prId)
    {
        return prService.commits(prId).stream().map(PRCommitDto::from).toList();
    }

    @GetMapping("/api/prs/{prId}/checks")
    public List<PRCheckDto> checks(@PathVariable String prId)
    {
        return prService.checks(prId).stream().map(PRCheckDto::from).toList();
    }

    @GetMapping("/api/prs/{prId}/comments")
    public List<PRCommentDto> comments(@PathVariable String prId)
    {
        return prService.comments(prId).stream().map(PRCommentDto::from).toList();
    }

    @PostMapping("/api/prs/{prId}/comments")
    public PRCommentDto addComment(@PathVariable String prId, @RequestBody AddPRCommentRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        // A comment posted through this controller is the user's own — always
        // local origin; the "remote" origin is populated only by the review
        // ingestor, never by a user request.
        return PRCommentDto.from(prService.addComment(
                prId,
                PRComment.ORIGIN_LOCAL,
                body.scope(),
                body.filePath(),
                body.lineNumber(),
                body.side(),
                body.startLine(),
                body.startSide(),
                USER_AUTHOR,
                body.body(),
                body.parentCommentId()));
    }

    @PatchMapping("/api/prs/comments/{commentId}")
    public PRCommentDto resolveComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.resolveComment(commentId));
    }

    @DeleteMapping("/api/prs/comments/{commentId}")
    public void deleteComment(@PathVariable String commentId)
    {
        prService.deleteDraftComment(commentId);
    }

    @PatchMapping("/api/prs/comments/{commentId}/dismiss")
    public PRCommentDto dismissComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.dismissComment(commentId));
    }

    @PatchMapping("/api/prs/comments/{commentId}/reopen")
    public PRCommentDto reopenComment(@PathVariable String commentId)
    {
        return PRCommentDto.from(prService.reopenComment(commentId));
    }
}
