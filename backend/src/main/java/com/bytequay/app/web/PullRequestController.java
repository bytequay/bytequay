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

import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.ActionPayload;
import com.bytequay.app.developmentflow.execution.remote.UserRemoteActionOperationHandler.SemanticAction;
import com.bytequay.app.developmentflow.execution.remote.V2UserRemoteActionRuntime;
import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.MyActivitySummary;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PrAnalyticsSummary;
import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.pr.MyActivityService;
import com.bytequay.app.service.pr.PrAnalyticsService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.filters.PullRequestFilters;
import com.bytequay.app.service.threads.PrTaskLinkService;
import com.bytequay.app.service.threads.PublishService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
public class PullRequestController
{
    private final PullRequestService pullRequestService;
    private final PrAnalyticsService prAnalyticsService;
    private final MyActivityService myActivityService;
    private final PullRequestFilters prFilters;
    private final PrTaskLinkService prTaskLink;
    private final PRPublishService prPublishService;
    private final V2TaskControlService v2TaskControls;
    private final V2UserRemoteActionRuntime v2UserRemoteActions;

    public PullRequestController(
            PullRequestService pullRequestService,
            PrAnalyticsService prAnalyticsService,
            MyActivityService myActivityService,
            PullRequestFilters prFilters,
            PrTaskLinkService prTaskLink,
            PRPublishService prPublishService,
            V2TaskControlService v2TaskControls,
            V2UserRemoteActionRuntime v2UserRemoteActions)
    {
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.prAnalyticsService = requireNonNull(prAnalyticsService, "prAnalyticsService is null");
        this.myActivityService = requireNonNull(myActivityService, "myActivityService is null");
        this.prFilters = requireNonNull(prFilters, "prFilters is null");
        this.prTaskLink = requireNonNull(prTaskLink, "prTaskLink is null");
        this.prPublishService = requireNonNull(prPublishService, "prPublishService is null");
        this.v2TaskControls = requireNonNull(v2TaskControls, "v2TaskControls is null");
        this.v2UserRemoteActions = requireNonNull(
                v2UserRemoteActions, "v2UserRemoteActions is null");
    }

    /**
     * Returns pull requests from the local database, sorted by the user's configured order.
     * No PAT required — data is populated by the background sync job.
     * GET /prs
     */
    @GetMapping("/prs")
    public List<PullRequest> list()
    {
        return pullRequestService.listPullRequests();
    }

    /**
     * Returns pull requests matching a named filter. {@code name}
     * resolves through the same
     * {@link com.bytequay.app.service.pr.filters.PullRequestFilters}
     * the {@code list_prs} agent tool uses — one definition, no
     * parallel logic for the dashboard to keep in sync.
     * GET /prs/filter/{name}
     */
    @GetMapping("/prs/filter/{name}")
    public List<PullRequest> filter(@PathVariable String name)
    {
        List<PullRequest> all = pullRequestService.listPullRequests();
        try {
            return prFilters.apply(name, all, Instant.now());
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), e.getMessage());
        }
    }

    /**
     * Fetches a single PR straight from GitHub by repo + number,
     * bypassing the cached dashboard list. Backs the assign-review
     * dialog's on-demand lookup. GET /prs/lookup?repo=owner/repo&number=123
     */
    @GetMapping("/prs/lookup")
    public PullRequest lookup(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.lookupPullRequest(repo, number);
    }

    /**
     * Aggregated KPIs for the PR review Analytics page. Local-only,
     * no PAT — reads the cached PR rows and detail blobs.
     * GET /prs/analytics?scope=7d|30d|90d|all&tz=America/Los_Angeles
     *
     * <p>{@code tz} is an IANA zone id; the renderer passes its own
     * {@code Intl.DateTimeFormat().resolvedOptions().timeZone} so the
     * daily-bars and heatmap bucket in the user's local time rather
     * than the JVM default. Falls back to the JVM default if missing
     * or unparseable.
     */
    @GetMapping("/prs/analytics")
    public PrAnalyticsSummary analytics(
            @RequestParam(value = "scope", defaultValue = "30d") String scope,
            @RequestParam(value = "tz", required = false) String timezone)
    {
        return prAnalyticsService.summarize(scope, timezone);
    }

    /**
     * Aggregated KPIs for the "My activity" companion page — what the
     * current user has authored (PRs opened / merged). Same query
     * shape as {@code /prs/analytics}; lives behind a separate
     * endpoint so the renderers stay decoupled.
     * GET /prs/my-activity?scope=7d|30d|90d|all&tz=America/Los_Angeles
     */
    @GetMapping("/prs/my-activity")
    public MyActivitySummary myActivity(
            @RequestParam(value = "scope", defaultValue = "30d") String scope,
            @RequestParam(value = "tz", required = false) String timezone)
    {
        return myActivityService.summarize(scope, timezone);
    }

    /**
     * Fetches full PR detail live from GitHub. Requires a Bearer PAT.
     * GET /prs/detail
     */
    @GetMapping("/prs/detail")
    public PullRequestDetail detail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.getPullRequestDetail(repo, number);
    }

    /**
     * GET /prs/linked-tasks — the dev tasks linked to a PR: the single
     * active task (if any) + the completed/cancelled audit log. The PR
     * detail page renders the linked-task chip + history from this.
     * Served as a sibling of {@code /prs/detail} (not folded into the
     * cached detail payload) so the task links are always read fresh.
     */
    @GetMapping("/prs/linked-tasks")
    public PrTaskLinkService.LinkedTasks linkedTasks(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return prTaskLink.linkedTasksFor(repo, number);
    }

    /**
     * Force-refresh one PR's detail. Drops the cached snapshot and
     * re-fetches live from GitHub. Wired to the manual refresh button
     * on the detail page so users can pull in changes they made on
     * github.com without waiting for the next periodic sync.
     * POST /prs/detail/refresh
     */
    @PostMapping("/prs/detail/refresh")
    public PullRequestDetail refreshDetail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "maxAgeSeconds", required = false, defaultValue = "0") int maxAgeSeconds)
    {
        return pullRequestService.refreshPullRequestDetail(repo, number, maxAgeSeconds);
    }

    /**
     * Conversation (issue) comments created after {@code sinceEpochMs},
     * as activity items ready to merge into the detail timeline. Backs the
     * detail page's lightweight comments-delta poll, which runs on a
     * tighter cadence than the full refresh so a reviewer's new comment
     * shows up promptly without the heavier multi-call refetch.
     * GET /prs/comments/new
     */
    @GetMapping("/prs/comments/new")
    public List<PullRequestDetail.ActivityItem> newComments(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("sinceEpochMs") long sinceEpochMs)
    {
        return pullRequestService.fetchNewComments(repo, number, Instant.ofEpochMilli(sinceEpochMs));
    }

    /**
     * Re-runs the PR's failed CI jobs (GitHub "re-run failed jobs") — the
     * one-click flaky-failure fix. {@code rerunCount} is how many workflow
     * runs were re-triggered; 0 means nothing on the head had failed.
     * POST /prs/rerun-checks?repo=&number=
     */
    @PostMapping("/prs/rerun-checks")
    public Map<String, Integer> rerunChecks(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.rerunFailedChecks(
                    requireV2CommandId(commandId), pr.taskId(), pr.id());
            return ImmutableMap.of("rerunCount", 1);
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.RERUN_FAILED_CHECKS, ActionPayload.empty());
        return ImmutableMap.of("rerunCount", 1);
    }

    /**
     * Pushes an empty commit to the PR's branch to re-trigger a
     * push-driven CI run — the fallback to re-run-failed-jobs. Only works
     * when the PR has an active task worktree to commit on; otherwise
     * {@code triggered} is false with a {@code reason}.
     * POST /prs/trigger-ci?repo=&number=
     */
    @PostMapping("/prs/trigger-ci")
    public PublishService.EmptyCommitResult triggerCi(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.triggerCiViaEmptyCommit(
                    requireV2CommandId(commandId), pr.taskId(), pr.id());
            return new PublishService.EmptyCommitResult(
                    true, "durable empty-commit CI trigger accepted");
        }
        requireExternalPullRequest(repo, number);
        return new PublishService.EmptyCommitResult(
                false, "taskless PRs have no owned worktree for an empty CI commit");
    }

    /**
     * Returns just the CI status, per-check breakdown, and the viewer's
     * write permission for the PR. Polled by the detail page while the
     * window is focused so a CI flip and the merge button's enable/disable
     * state refresh without re-running the full detail orchestration.
     * GET /prs/ci?repo=&number=
     */
    @GetMapping("/prs/ci")
    public PrCiSnapshot ciSnapshot(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.getPullRequestCiSnapshot(repo, number);
    }

    /**
     * Returns the raw log text for a single Actions check-run job so the
     * merge bar's failure cards can show the actual log inline without
     * sending the user to github.com. Empty body when GitHub doesn't
     * expose a log (external CI, expired log, missing PAT scope).
     * GET /prs/checkLog?repo=&checkRunId=
     */
    @GetMapping("/prs/checkLog")
    public Map<String, String> checkLog(
            @RequestParam("repo") String repo,
            @RequestParam("checkRunId") long checkRunId)
    {
        return ImmutableMap.of("log", pullRequestService.getCheckRunLog(repo, checkRunId));
    }

    /**
     * Returns the best failure text for a single check-run so a failing row in
     * the checks card can unfold it: the annotated assertion with its file and
     * line when GitHub published one, otherwise an excerpt of the job log.
     * GET /prs/checkFailure?repo=&checkRunId=
     */
    @GetMapping("/prs/checkFailure")
    public PullRequestService.CheckRunFailure checkFailure(
            @RequestParam("repo") String repo,
            @RequestParam("checkRunId") long checkRunId)
    {
        return pullRequestService.getCheckRunFailure(repo, checkRunId);
    }

    /**
     * Toggles a PR between draft and ready-for-review. Body
     * {@code {"draft": true}} converts the PR to a draft; false marks
     * it as ready for review. POST /prs/draft?repo=&number=
     */
    public record SetDraftRequest(boolean draft) {}

    @PostMapping("/prs/draft")
    public Map<String, Object> setDraft(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody SetDraftRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.setDraft(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), req.draft());
            return ImmutableMap.of("result", req.draft() ? "draft" : "ready");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.SET_DRAFT_STATE,
                ActionPayload.selected(null, req.draft()));
        return ImmutableMap.of("result", req.draft() ? "draft" : "ready");
    }

    public record UpdateTitleRequest(String title) {}

    /**
     * Renames a PR on GitHub. Requires a PAT with push access to the repo.
     * PATCH /prs/title?repo={owner/repo}&number={n}
     */
    @PatchMapping("/prs/title")
    public PullRequestService.PrTitleUpdate updateTitle(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody UpdateTitleRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            String title = req.title() == null ? "" : req.title().strip();
            if (title.isEmpty() || title.length() > 256) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(400),
                        "Title must be between 1 and 256 characters");
            }
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.updateTitle(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), title);
            return new PullRequestService.PrTitleUpdate(
                    number, title, Instant.now());
        }
        String title = req.title() == null ? "" : req.title().strip();
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.UPDATE_TITLE, ActionPayload.value(title));
        return new PullRequestService.PrTitleUpdate(number, title, Instant.now());
    }

    /**
     * Records that the user viewed this PR. No PAT required — purely local
     * state. Identified by {@code id} (the search-derived id the local store
     * keys by) or by {@code repo}+{@code number} for callers whose rows come
     * from the REST pulls endpoints, whose ids live in a different GitHub id
     * namespace. POST /prs/viewed
     */
    @PostMapping("/prs/viewed")
    public Map<String, String> markViewed(
            @RequestParam(value = "id", required = false) Long prId,
            @RequestParam(value = "repo", required = false) String repo,
            @RequestParam(value = "number", required = false) Integer number)
    {
        if (prId != null) {
            pullRequestService.markViewed(prId);
        }
        else if (repo != null && number != null) {
            pullRequestService.markViewed(repo, number);
        }
        else {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400), "id or repo+number is required");
        }
        return ImmutableMap.of("result", "ok");
    }

    /**
     * Live GitHub search for the user's full closed-PR history (merged +
     * closed-without-merge). Powers the "View full merge history" page.
     * GET /prs/history?page=N&perPage=30
     */
    @GetMapping("/prs/history")
    public PullRequestHistoryPage history(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "perPage", defaultValue = "30") int perPage)
    {
        return pullRequestService.searchAuthoredHistory(page, perPage);
    }

    /**
     * Returns the PR's changed files with unified-diff patches. Always fresh
     * from GitHub. Requires a Bearer PAT.
     * GET /prs/diffFiles
     */
    @GetMapping("/prs/diffFiles")
    public List<DiffFile> diffFiles(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.getPullRequestDiffFiles(repo, number);
    }

    /**
     * Returns the PR's commits, oldest first. Always fresh from GitHub.
     * Requires a Bearer PAT.
     * GET /prs/commits
     */
    @GetMapping("/prs/commits")
    public List<PullRequestCommit> commits(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.getPullRequestCommits(repo, number);
    }

    /**
     * Returns the diff for a single commit (same DiffFile shape as /prs/diffFiles
     * but scoped to one sha). Powers the per-commit drill-down in the diff
     * viewer.
     * GET /prs/commitDiff?repo=&number=&sha=
     */
    @GetMapping("/prs/commitDiff")
    public List<DiffFile> commitDiff(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("sha") String sha)
    {
        return pullRequestService.getCommitDiffFiles(repo, number, sha);
    }

    /**
     * Returns a file's full content at a given ref, as a list of lines.
     * Powers the "expand collapsed code" buttons in the diff viewer —
     * the renderer slices the requested line window from this list.
     * GET /prs/fileBlob?repo=&path=&sha=
     */
    @GetMapping("/prs/fileBlob")
    public Map<String, Object> fileBlob(
            @RequestParam("repo") String repo,
            @RequestParam("path") String path,
            @RequestParam("sha") String sha)
    {
        List<String> lines = pullRequestService.getFileBlobLines(repo, path, sha);
        return ImmutableMap.of("lines", lines);
    }

    /**
     * Updates the PR body/description on GitHub. Only the PR author is
     * permitted by GitHub; non-authors will see a 422 from this endpoint.
     * POST /prs/body
     */
    public record UpdateBodyRequest(String body) {}

    @PostMapping("/prs/body")
    public Map<String, String> updateBody(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody UpdateBodyRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.updateBody(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), req.body());
            return ImmutableMap.of("result", "ok");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.UPDATE_BODY, ActionPayload.body(req.body()));
        return ImmutableMap.of("result", "ok");
    }

    /**
     * Posts an issue comment on a PR, optionally closing the PR afterwards.
     * Requires a Bearer PAT.
     * POST /prs/comment
     */
    public record CommentRequest(String body, boolean close) {}

    @PostMapping("/prs/comment")
    public Map<String, String> comment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody CommentRequest req)
    {
        // "Close pull request" with an empty composer is a bare close, not a
        // comment-and-close: COMMENT_AND_CLOSE requires a body to post.
        boolean bareClose = req.close()
                && (req.body() == null || req.body().isBlank());
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            if (bareClose) {
                v2UserRemoteActions.closePullRequest(
                        requireV2CommandId(commandId), pr.taskId(), pr.id());
                return ImmutableMap.of("result", "closed");
            }
            if (req.close()) {
                v2UserRemoteActions.commentAndClose(
                        requireV2CommandId(commandId), pr.taskId(), pr.id(), req.body());
                return ImmutableMap.of("result", "closed");
            }
            prPublishService.postComment(
                    requireV2CommandId(commandId), pr.id(), req.body());
            return ImmutableMap.of("result", "commented");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                bareClose ? SemanticAction.CLOSE_PULL_REQUEST
                        : req.close() ? SemanticAction.COMMENT_AND_CLOSE
                                : SemanticAction.POST_TOP_LEVEL_COMMENT,
                bareClose ? ActionPayload.empty() : ActionPayload.body(req.body()));
        return ImmutableMap.of("result", req.close() ? "closed" : "commented");
    }

    public record ReplyRequest(String body) {}

    /**
     * Replies inline to a review-thread comment. {@code rootCommentId} is
     * the GitHub id of the root comment in the thread.
     * POST /prs/review-threads/{rootCommentId}/reply?repo=&number=
     */
    @PostMapping("/prs/review-threads/{rootCommentId}/reply")
    public Map<String, String> replyToThread(
            @PathVariable long rootCommentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody ReplyRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.replyToReviewThread(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    rootCommentId, req.body());
            return ImmutableMap.of("result", "replied");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.REPLY_REVIEW_THREAD,
                ActionPayload.targetBody(
                        Long.toString(rootCommentId), req.body()));
        return ImmutableMap.of("result", "replied");
    }

    /**
     * Edits a top-level issue / PR comment authored by the authenticated
     * user. POST /prs/issue-comments/{commentId}/body?repo=&number=
     * Body: {"body": "..."}
     */
    @PostMapping("/prs/issue-comments/{commentId}/body")
    public Map<String, String> editIssueComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody UpdateBodyRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.editIssueComment(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    commentId, req.body());
            return ImmutableMap.of("result", "edited");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.EDIT_ISSUE_COMMENT,
                ActionPayload.targetBody(Long.toString(commentId), req.body()));
        return ImmutableMap.of("result", "edited");
    }

    /**
     * Edits a per-line review comment authored by the authenticated user.
     * POST /prs/review-comments/{commentId}/body?repo=&number=
     * Body: {"body": "..."}
     */
    @PostMapping("/prs/review-comments/{commentId}/body")
    public Map<String, String> editReviewComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody UpdateBodyRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.editReviewComment(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    commentId, req.body());
            return ImmutableMap.of("result", "edited");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.EDIT_REVIEW_COMMENT,
                ActionPayload.targetBody(Long.toString(commentId), req.body()));
        return ImmutableMap.of("result", "edited");
    }

    /**
     * Deletes a top-level issue / PR comment the authenticated user owns
     * (or can delete via repo write access).
     * DELETE /prs/issue-comments/{commentId}?repo=&number=
     */
    @DeleteMapping("/prs/issue-comments/{commentId}")
    public Map<String, String> deleteIssueComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.deleteIssueComment(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), commentId);
            return ImmutableMap.of("result", "deleted");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.DELETE_ISSUE_COMMENT,
                ActionPayload.target(Long.toString(commentId)));
        return ImmutableMap.of("result", "deleted");
    }

    /**
     * Deletes a per-line review comment the authenticated user owns (or
     * can delete via repo write access).
     * DELETE /prs/review-comments/{commentId}?repo=&number=
     */
    @DeleteMapping("/prs/review-comments/{commentId}")
    public Map<String, String> deleteReviewComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.deleteReviewComment(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), commentId);
            return ImmutableMap.of("result", "deleted");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.DELETE_REVIEW_COMMENT,
                ActionPayload.target(Long.toString(commentId)));
        return ImmutableMap.of("result", "deleted");
    }

    /**
     * Adds a requested reviewer to the PR.
     * POST /prs/reviewers?repo=&number=&reviewer=
     */
    @PostMapping("/prs/reviewers")
    public Map<String, String> addReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("reviewer") String reviewer,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.addReviewer(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), reviewer);
            return ImmutableMap.of("result", "added");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.ADD_REVIEWER, ActionPayload.value(reviewer));
        return ImmutableMap.of("result", "added");
    }

    /**
     * Removes a requested reviewer from the PR.
     * DELETE /prs/reviewers?repo=&number=&reviewer=
     */
    @DeleteMapping("/prs/reviewers")
    public Map<String, String> removeReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("reviewer") String reviewer,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.removeReviewer(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), reviewer);
            return ImmutableMap.of("result", "removed");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.REMOVE_REVIEWER, ActionPayload.value(reviewer));
        return ImmutableMap.of("result", "removed");
    }

    /**
     * GitHub's suggested reviewers for one PR — drives the one-click
     * chips above the typeahead in the Add-reviewer UI. GraphQL-only;
     * returns an empty list on failure rather than 5xxing.
     * GET /prs/reviewers/suggested?repo=&number=
     */
    @GetMapping("/prs/reviewers/suggested")
    public List<SuggestedReviewer> suggestedReviewers(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.getSuggestedReviewers(repo, number);
    }

    /** Current assignees/labels plus the repository choices for the metadata pickers. */
    @GetMapping("/prs/metadata")
    public PullRequestService.MetadataChoices metadataChoices(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequestService.getMetadataChoices(repo, number);
    }

    public record MetadataSelectionRequest(String value, boolean selected) {}

    @PostMapping("/prs/assignees")
    public Map<String, String> setAssignee(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody MetadataSelectionRequest request)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.setAssignee(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    request.value(), request.selected());
            return ImmutableMap.of("result", "updated");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.SET_ASSIGNEE,
                ActionPayload.selected(request.value(), request.selected()));
        return ImmutableMap.of("result", "updated");
    }

    @PostMapping("/prs/labels")
    public Map<String, String> setLabel(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody MetadataSelectionRequest request)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.setLabel(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    request.value(), request.selected());
            return ImmutableMap.of("result", "updated");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(), SemanticAction.SET_LABEL,
                ActionPayload.selected(request.value(), request.selected()));
        return ImmutableMap.of("result", "updated");
    }

    /**
     * Inline comment request body.
     *
     * @param startLine optional first line of a multi-line range.
     */
    public record InlineCommentRequest(
            String body,
            String path,
            int line,
            String side,
            String commitId,
            Integer startLine,
            String startSide) {}

    /**
     * Posts a single per-line review comment on a diff line. The service
     * resolves the authoritative PR head immediately before posting;
     * {@code commitId} remains in the request for transport compatibility.
     * POST /prs/review-comments?repo=&number=
     */
    @PostMapping("/prs/review-comments")
    public Map<String, String> createInlineReviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody InlineCommentRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.createInlineComment(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    req.body(), req.path(), req.line(), req.side(),
                    req.startLine(), req.startSide());
            return ImmutableMap.of("result", "commented");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.CREATE_INLINE_COMMENT,
                ActionPayload.inlineComment(
                        req.body(), req.path(), req.line(), req.side(),
                        req.startLine(), req.startSide()));
        return ImmutableMap.of("result", "commented");
    }

    public record ApplySuggestionRequest(
            String suggestion, String path, int line, Integer startLine) {}

    /**
     * Commits a review suggestion over the lines it was written against —
     * the "Apply suggestion" affordance on a review thread. The commit
     * lands on the pull request's head branch, which for a fork PR is the
     * contributor's branch (GitHub rejects the write when the fork hasn't
     * allowed maintainer edits).
     * POST /prs/suggestions/apply?repo=&number=
     */
    @PostMapping("/prs/suggestions/apply")
    public Map<String, String> applySuggestion(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody ApplySuggestionRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.applySuggestion(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    req.suggestion(), req.path(), req.line(), req.startLine());
            return ImmutableMap.of("result", "applied");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.APPLY_SUGGESTION,
                V2UserRemoteActionRuntime.suggestionPayload(
                        req.suggestion(), req.path(), req.line(), req.startLine()));
        return ImmutableMap.of("result", "applied");
    }

    public record AddReactionRequest(String content) {}

    /**
     * Adds an emoji reaction to the pull request description.
     * POST /prs/{number}/reactions?repo=
     */
    @PostMapping("/prs/{number}/reactions")
    public Map<String, String> addPullRequestReaction(
            @RequestParam("repo") String repo,
            @PathVariable int number,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody AddReactionRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.addPullRequestReaction(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(), req.content());
            return ImmutableMap.of("result", "reacted");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.REACT_PULL_REQUEST,
                ActionPayload.value(req.content()));
        return ImmutableMap.of("result", "reacted");
    }

    /**
     * Adds an emoji reaction to a per-line review comment. Idempotent on
     * the GitHub side — re-adding returns 200 with the same reaction id.
     * POST /prs/review-comments/{commentId}/reactions?repo=&number=
     */
    @PostMapping("/prs/review-comments/{commentId}/reactions")
    public Map<String, String> addReviewCommentReaction(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable long commentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody AddReactionRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.addReviewCommentReaction(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    commentId, req.content());
            return ImmutableMap.of("result", "reacted");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.REACT_REVIEW_COMMENT,
                ActionPayload.targetValue(
                        Long.toString(commentId), req.content()));
        return ImmutableMap.of("result", "reacted");
    }

    /**
     * Adds an emoji reaction to a top-level issue / PR comment.
     * POST /prs/issue-comments/{commentId}/reactions?repo=&number=
     */
    @PostMapping("/prs/issue-comments/{commentId}/reactions")
    public Map<String, String> addIssueCommentReaction(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable long commentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody AddReactionRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.addIssueCommentReaction(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    commentId, req.content());
            return ImmutableMap.of("result", "reacted");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.REACT_ISSUE_COMMENT,
                ActionPayload.targetValue(
                        Long.toString(commentId), req.content()));
        return ImmutableMap.of("result", "reacted");
    }

    public record SetThreadResolvedRequest(boolean resolved) {}

    /**
     * Marks a review thread resolved / unresolved via GraphQL. The
     * thread is identified by the REST root comment id (the same id
     * the frontend already uses elsewhere); the service translates to
     * the GraphQL node id internally.
     * POST /prs/review-threads/{rootId}/resolved?repo=&number=&prId=
     */
    @PostMapping("/prs/review-threads/{rootId}/resolved")
    public Map<String, String> setReviewThreadResolved(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("prId") long prId,
            @PathVariable long rootId,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId,
            @RequestBody SetThreadResolvedRequest req)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            PR pr = v2Pr.orElseThrow();
            v2UserRemoteActions.setThreadResolved(
                    requireV2CommandId(commandId), pr.taskId(), pr.id(),
                    rootId, req.resolved());
            return ImmutableMap.of(
                    "result", req.resolved() ? "resolved" : "unresolved");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.SET_THREAD_RESOLUTION,
                ActionPayload.targetSelected(
                        Long.toString(rootId), req.resolved()));
        return ImmutableMap.of("result", req.resolved() ? "resolved" : "unresolved");
    }

    /**
     * Approves a pull request on GitHub and records a local reviewed state. Requires a Bearer PAT.
     * POST /prs/approve
     */
    @PostMapping("/prs/approve")
    public Map<String, String> approve(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            prPublishService.publishReview(
                    requireV2CommandId(commandId), v2Pr.orElseThrow().id(),
                    "APPROVE", List.of(), List.of(), "");
            return ImmutableMap.of("result", "approved");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.SUBMIT_REVIEW,
                new ActionPayload(
                        1, "", "APPROVE", null, List.of()));
        return ImmutableMap.of("result", "approved");
    }

    /**
     * Merges a pull request on GitHub and records a local reviewed state. Requires a Bearer PAT.
     * POST /prs/merge
     */
    @PostMapping("/prs/merge")
    public MergeResult merge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            // Optional so older clients (no dropdown) keep getting rebase.
            @RequestParam(value = "strategy", required = false) String strategy,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            prPublishService.merge(
                    requireV2CommandId(commandId), v2Pr.orElseThrow().id(), strategy);
            return new MergeResult(null, false, "merge accepted by Task workflow", false);
        }
        PR pr = requireExternalPullRequest(repo, number);
        String method = strategy == null ? "REBASE" : strategy;
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(), SemanticAction.MERGE,
                ActionPayload.value(method));
        return new MergeResult(
                null, false, "merge accepted by REVIEW Trunk", false);
    }

    /**
     * Enables GitHub's auto-merge on the PR. Mirrors github.com's "Merge
     * when ready" button — GitHub merges automatically once required
     * checks pass and approvals are in place. Goes through GraphQL.
     * POST /prs/auto-merge
     */
    @PostMapping("/prs/auto-merge")
    public Map<String, String> enableAutoMerge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestParam(value = "strategy", required = false) String strategy,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            v2TaskControls.setAutoMerge(v2Pr.orElseThrow().taskId(), true);
            return ImmutableMap.of("result", "auto-merge-enabled");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.ENABLE_AUTO_MERGE,
                ActionPayload.value(strategy == null ? "REBASE" : strategy));
        return ImmutableMap.of("result", "auto-merge-enabled");
    }

    /**
     * Cancels a previously-enabled auto-merge. Idempotent on GitHub's side
     * (no-op when auto-merge wasn't enabled), so the route returns "ok"
     * either way.
     * DELETE /prs/auto-merge
     */
    @DeleteMapping("/prs/auto-merge")
    public Map<String, String> disableAutoMerge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            v2TaskControls.setAutoMerge(v2Pr.orElseThrow().taskId(), false);
            return ImmutableMap.of("result", "auto-merge-disabled");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(),
                SemanticAction.DISABLE_AUTO_MERGE, ActionPayload.empty());
        return ImmutableMap.of("result", "auto-merge-disabled");
    }

    /**
     * Removes a PR from its repo's merge queue. Mirrors the "Remove from
     * queue" button on github.com's merge bar. No-op on GitHub's side
     * when the PR isn't queued.
     * DELETE /prs/merge-queue
     */
    @DeleteMapping("/prs/merge-queue")
    public Map<String, String> dequeue(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestHeader(value = "Idempotency-Key", required = false) String commandId)
    {
        Optional<PR> v2Pr = v2TaskPullRequest(repo, number);
        if (v2Pr.isPresent()) {
            prPublishService.dequeue(
                    requireV2CommandId(commandId), v2Pr.orElseThrow().id());
            return ImmutableMap.of("result", "dequeued");
        }
        PR pr = requireExternalPullRequest(repo, number);
        v2UserRemoteActions.authorizeExternal(
                requireV2CommandId(commandId), pr.id(), SemanticAction.DEQUEUE,
                ActionPayload.empty());
        return ImmutableMap.of("result", "dequeued");
    }

    /**
     * Marks a PR as handled with the given action. Purely local state — no GitHub call.
     * Used when the user clicks the "Handled" button on a card without reviewing.
     * POST /prs/handle?id={prId}&action=MANUAL
     */
    @PostMapping("/prs/handle")
    public Map<String, String> markHandled(
            @RequestParam("id") long prId,
            @RequestParam(value = "action", defaultValue = "MANUAL") HandledAction action)
    {
        pullRequestService.markHandled(prId, action);
        return ImmutableMap.of("result", "handled");
    }

    /**
     * Clears the reviewed timestamp on a PR, returning it to the Inbox.
     * POST /prs/reopen?id={prId}
     */
    @PostMapping("/prs/reopen")
    public Map<String, String> reopen(@RequestParam("id") long prId)
    {
        pullRequestService.reopen(prId);
        return ImmutableMap.of("result", "reopened");
    }

    /**
     * Snooze a PR until the given ISO-8601 timestamp. The PR is hidden
     * from Inbox / kanban / sidebar lists until the timer fires or an
     * urgent condition trips the auto-wake (CI failing, changes
     * requested, merge conflict).
     * POST /prs/snooze?id={prId}&until={ISO-8601}
     */
    @PostMapping("/prs/snooze")
    public Map<String, String> snooze(
            @RequestParam("id") long prId,
            @RequestParam("until") String untilIso)
    {
        Instant until;
        try {
            until = Instant.parse(untilIso);
        }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Bad 'until' value, expected ISO-8601: " + untilIso);
        }
        pullRequestService.snooze(prId, until);
        return ImmutableMap.of("result", "snoozed");
    }

    /**
     * User-initiated wake. POST /prs/unsnooze?id={prId}
     */
    @PostMapping("/prs/unsnooze")
    public Map<String, String> unsnooze(@RequestParam("id") long prId)
    {
        pullRequestService.unsnooze(prId);
        return ImmutableMap.of("result", "unsnoozed");
    }

    /**
     * Drop a stored auto-wake reason once the user has seen the
     * "PR woke up" alert. POST /prs/snooze/wake-reason/clear?id={prId}
     */
    @PostMapping("/prs/snooze/wake-reason/clear")
    public Map<String, String> clearSnoozeWakeReason(@RequestParam("id") long prId)
    {
        pullRequestService.clearSnoozeWakeReason(prId);
        return ImmutableMap.of("result", "cleared");
    }

    private Optional<PR> v2TaskPullRequest(String repo, int number)
    {
        return prPublishService.findV2TaskPullRequest(repo, number);
    }

    private PR requireExternalPullRequest(String repo, int number)
    {
        return prPublishService.findExternalPullRequest(repo, number)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(409),
                        "taskless PR requires a synchronized external PR aggregate"));
    }

    private static String requireV2CommandId(String commandId)
    {
        if (commandId == null || commandId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(400),
                    "Idempotency-Key is required for a V2 remote action");
        }
        return commandId;
    }
}
