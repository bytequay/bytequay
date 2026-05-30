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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MergeResult;
import com.bytequay.app.domain.MyActivitySummary;
import com.bytequay.app.domain.PrAnalyticsSummary;
import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.service.pr.MyActivityService;
import com.bytequay.app.service.pr.PrAnalyticsService;
import com.bytequay.app.service.pr.PullRequestService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@RestController
public class PullRequestController
{
    private final PullRequestService pullRequestService;
    private final PrAnalyticsService prAnalyticsService;
    private final MyActivityService myActivityService;
    private final PatResolver patResolver;

    public PullRequestController(
            PullRequestService pullRequestService,
            PrAnalyticsService prAnalyticsService,
            MyActivityService myActivityService,
            PatResolver patResolver)
    {
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.prAnalyticsService = requireNonNull(prAnalyticsService, "prAnalyticsService is null");
        this.myActivityService = requireNonNull(myActivityService, "myActivityService is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.getPullRequestDetail(pat, repo, number);
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.refreshPullRequestDetail(pat, repo, number, maxAgeSeconds);
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.getPullRequestCiSnapshot(pat, repo, number);
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
        String pat = patResolver.resolve(repo);
        return ImmutableMap.of("log", pullRequestService.getCheckRunLog(pat, repo, checkRunId));
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
            @RequestBody SetDraftRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.setPullRequestDraft(pat, repo, number, req.draft());
        return ImmutableMap.of("result", req.draft() ? "draft" : "ready");
    }

    /**
     * Records that the user viewed this PR. No PAT required — purely local state.
     * POST /prs/viewed
     */
    @PostMapping("/prs/viewed")
    public Map<String, String> markViewed(@RequestParam("id") long prId)
    {
        pullRequestService.markViewed(prId);
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
        String pat = patResolver.resolve();
        return pullRequestService.searchAuthoredHistory(pat, page, perPage);
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.getPullRequestDiffFiles(pat, repo, number);
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.getPullRequestCommits(pat, repo, number);
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.getCommitDiffFiles(pat, repo, number, sha);
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
        String pat = patResolver.resolve(repo);
        List<String> lines = pullRequestService.getFileBlobLines(pat, repo, path, sha);
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
            @RequestBody UpdateBodyRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.updatePullRequestBody(pat, repo, number, req.body());
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
            @RequestBody CommentRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.commentOnPullRequest(pat, repo, number, prId, req.body(), req.close());
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
            @RequestBody ReplyRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.replyToReviewThread(pat, repo, number, rootCommentId, req.body());
        return ImmutableMap.of("result", "replied");
    }

    /**
     * Edits a top-level issue / PR comment authored by the authenticated
     * user. POST /prs/issue-comments/{commentId}/body?repo=
     * Body: {"body": "..."}
     */
    @PostMapping("/prs/issue-comments/{commentId}/body")
    public Map<String, String> editIssueComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestBody UpdateBodyRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.editIssueComment(pat, repo, commentId, req.body());
        return ImmutableMap.of("result", "edited");
    }

    /**
     * Edits a per-line review comment authored by the authenticated user.
     * POST /prs/review-comments/{commentId}/body?repo=
     * Body: {"body": "..."}
     */
    @PostMapping("/prs/review-comments/{commentId}/body")
    public Map<String, String> editReviewComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestBody UpdateBodyRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.editReviewComment(pat, repo, commentId, req.body());
        return ImmutableMap.of("result", "edited");
    }

    /**
     * Adds a requested reviewer to the PR.
     * POST /prs/reviewers?repo=&number=&reviewer=
     */
    @PostMapping("/prs/reviewers")
    public Map<String, String> addReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("reviewer") String reviewer)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.addRequestedReviewer(pat, repo, number, reviewer);
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
            @RequestParam("reviewer") String reviewer)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.removeRequestedReviewer(pat, repo, number, reviewer);
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
        String pat = patResolver.resolve(repo);
        return pullRequestService.getSuggestedReviewers(pat, repo, number);
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
     * Posts a single per-line review comment on a diff line. The frontend
     * resolves {@code commitId} from the PR head SHA before calling.
     * POST /prs/review-comments?repo=&number=
     */
    @PostMapping("/prs/review-comments")
    public Map<String, String> createInlineReviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody InlineCommentRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.createInlineReviewComment(
                pat, repo, number, req.body(), req.path(), req.line(), req.side(), req.commitId(),
                req.startLine(), req.startSide());
        return ImmutableMap.of("result", "commented");
    }

    public record AddReactionRequest(String content) {}

    /**
     * Adds an emoji reaction to a per-line review comment. Idempotent on
     * the GitHub side — re-adding returns 200 with the same reaction id.
     * POST /prs/review-comments/{commentId}/reactions?repo=
     */
    @PostMapping("/prs/review-comments/{commentId}/reactions")
    public Map<String, String> addReviewCommentReaction(
            @RequestParam("repo") String repo,
            @PathVariable long commentId,
            @RequestBody AddReactionRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.addReviewCommentReaction(pat, repo, commentId, req.content());
        return ImmutableMap.of("result", "reacted");
    }

    /**
     * Adds an emoji reaction to a top-level issue / PR comment.
     * POST /prs/issue-comments/{commentId}/reactions?repo=
     */
    @PostMapping("/prs/issue-comments/{commentId}/reactions")
    public Map<String, String> addIssueCommentReaction(
            @RequestParam("repo") String repo,
            @PathVariable long commentId,
            @RequestBody AddReactionRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.addIssueCommentReaction(pat, repo, commentId, req.content());
        return ImmutableMap.of("result", "reacted");
    }

    public record SetThreadResolvedRequest(boolean resolved) {}

    /**
     * Marks a review thread resolved / unresolved via GraphQL. The
     * thread is identified by the REST root comment id (the same id
     * the frontend already uses elsewhere); the service translates to
     * the GraphQL node id internally.
     * POST /prs/review-threads/{rootId}/resolved?repo=&prId=
     */
    @PostMapping("/prs/review-threads/{rootId}/resolved")
    public Map<String, String> setReviewThreadResolved(
            @RequestParam("repo") String repo,
            @RequestParam("prId") long prId,
            @PathVariable long rootId,
            @RequestBody SetThreadResolvedRequest req)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.setReviewThreadResolved(pat, prId, rootId, req.resolved());
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
            @RequestParam("id") long prId)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.approvePullRequest(pat, repo, number, prId);
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
            @RequestParam(value = "strategy", required = false) String strategy)
    {
        String pat = patResolver.resolve(repo);
        return pullRequestService.mergePullRequest(pat, repo, number, prId, strategy);
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
            @RequestParam(value = "strategy", required = false) String strategy)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.enableAutoMerge(pat, repo, number, prId, strategy);
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
            @RequestParam("id") long prId)
    {
        String pat = patResolver.resolve(repo);
        pullRequestService.disableAutoMerge(pat, repo, number, prId);
        return ImmutableMap.of("result", "auto-merge-disabled");
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
}
