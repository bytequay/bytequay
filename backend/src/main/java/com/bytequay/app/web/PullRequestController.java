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
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.service.pr.PullRequestService;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@RestController
public class PullRequestController
{
    private final PullRequestService pullRequestService;
    private final PatResolver patResolver;

    public PullRequestController(PullRequestService pullRequestService, PatResolver patResolver)
    {
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
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

    public record InlineCommentRequest(
            String body,
            String path,
            int line,
            String side,
            String commitId,
            /** Optional first line of a multi-line range. Null/omitted
             *  for single-line comments. */
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
            @RequestParam("id") long prId)
    {
        String pat = patResolver.resolve(repo);
        return pullRequestService.mergePullRequest(pat, repo, number, prId);
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
}
