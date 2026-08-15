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
import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestHistoryPage;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.service.pr.PullRequestService;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final PullRequestService pullRequests;
    public PullRequestController(PullRequestService pullRequests)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    @GetMapping("/prs")
    public List<PullRequest> list()
    {
        return pullRequests.listPullRequests();
    }

    @GetMapping("/prs/lookup")
    public PullRequest lookup(@RequestParam("repo") String repo, @RequestParam("number") int number)
    {
        return pullRequests.lookupPullRequest(repo, number);
    }

    @GetMapping("/prs/detail")
    public PullRequestDetail detail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestDetail(repo, number);
    }

    @PostMapping("/prs/detail/refresh")
    public PullRequestDetail refreshDetail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "maxAgeSeconds", defaultValue = "0") int maxAgeSeconds)
    {
        return pullRequests.refreshPullRequestDetail(repo, number, maxAgeSeconds);
    }

    @GetMapping("/prs/comments/new")
    public List<PullRequestDetail.ActivityItem> newComments(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("sinceEpochMs") long sinceEpochMs)
    {
        return pullRequests.fetchNewComments(repo, number, Instant.ofEpochMilli(sinceEpochMs));
    }

    @PostMapping("/prs/rerun-checks")
    public Map<String, Integer> rerunChecks(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return ImmutableMap.of("rerunCount", pullRequests.rerunFailedChecks(repo, number));
    }

    @GetMapping("/prs/ci")
    public PrCiSnapshot ciSnapshot(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestCiSnapshot(repo, number);
    }

    @GetMapping("/prs/checkLog")
    public Map<String, String> checkLog(
            @RequestParam("repo") String repo,
            @RequestParam("checkRunId") long checkRunId)
    {
        return ImmutableMap.of("log", pullRequests.getCheckRunLog(repo, checkRunId));
    }

    @GetMapping("/prs/checkFailure")
    public PullRequestService.CheckRunFailure checkFailure(
            @RequestParam("repo") String repo,
            @RequestParam("checkRunId") long checkRunId)
    {
        return pullRequests.getCheckRunFailure(repo, checkRunId);
    }

    public record SetDraftRequest(boolean draft) {}

    @PostMapping("/prs/draft")
    public Map<String, Object> setDraft(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody SetDraftRequest request)
    {
        pullRequests.setPullRequestDraft(repo, number, request.draft());
        return ImmutableMap.of("result", request.draft() ? "draft" : "ready");
    }

    public record UpdateTitleRequest(String title) {}

    @PatchMapping("/prs/title")
    public PullRequestService.PrTitleUpdate updateTitle(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody UpdateTitleRequest request)
    {
        return pullRequests.updatePullRequestTitle(repo, number, request.title());
    }

    @PostMapping("/prs/viewed")
    public Map<String, String> markViewed(
            @RequestParam(value = "id", required = false) Long prId,
            @RequestParam(value = "repo", required = false) String repo,
            @RequestParam(value = "number", required = false) Integer number)
    {
        if (prId != null) {
            pullRequests.markViewed(prId);
        }
        else if (repo != null && number != null) {
            pullRequests.markViewed(repo, number);
        }
        else {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "id or repo + number is required");
        }
        return ImmutableMap.of("result", "viewed");
    }

    @GetMapping("/prs/history")
    public PullRequestHistoryPage history(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "perPage", defaultValue = "30") int perPage)
    {
        return pullRequests.searchAuthoredHistory(page, perPage);
    }

    @GetMapping("/prs/diffFiles")
    public List<DiffFile> diffFiles(@RequestParam("repo") String repo, @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestDiffFiles(repo, number);
    }

    @GetMapping("/prs/commits")
    public List<PullRequestCommit> commits(@RequestParam("repo") String repo, @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestCommits(repo, number);
    }

    @GetMapping("/prs/commitDiff")
    public List<DiffFile> commitDiff(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("sha") String sha)
    {
        return pullRequests.getCommitDiffFiles(repo, number, sha);
    }

    @GetMapping("/prs/fileBlob")
    public Map<String, Object> fileBlob(
            @RequestParam("repo") String repo,
            @RequestParam("path") String path,
            @RequestParam("sha") String sha)
    {
        return ImmutableMap.of("lines", pullRequests.getFileBlobLines(repo, path, sha));
    }

    public record UpdateBodyRequest(String body) {}

    @PostMapping("/prs/body")
    public Map<String, String> updateBody(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody UpdateBodyRequest request)
    {
        pullRequests.updatePullRequestBody(repo, number, request.body());
        return ImmutableMap.of("result", "ok");
    }

    public record CommentRequest(String body, boolean close) {}

    @PostMapping("/prs/comment")
    public Map<String, String> comment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestBody CommentRequest request)
    {
        pullRequests.commentOnPullRequest(repo, number, prId, request.body(), request.close());
        return ImmutableMap.of("result", request.close() ? "closed" : "commented");
    }

    public record ReplyRequest(String body) {}

    @PostMapping("/prs/review-threads/{rootCommentId}/reply")
    public Map<String, String> replyToThread(
            @PathVariable long rootCommentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody ReplyRequest request)
    {
        pullRequests.replyToReviewThread(repo, number, rootCommentId, request.body());
        return ImmutableMap.of("result", "replied");
    }

    @PostMapping("/prs/issue-comments/{commentId}/body")
    public Map<String, String> editIssueComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestBody UpdateBodyRequest request)
    {
        pullRequests.editIssueComment(repo, commentId, request.body());
        return ImmutableMap.of("result", "edited");
    }

    @PostMapping("/prs/review-comments/{commentId}/body")
    public Map<String, String> editReviewComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo,
            @RequestBody UpdateBodyRequest request)
    {
        pullRequests.editReviewComment(repo, commentId, request.body());
        return ImmutableMap.of("result", "edited");
    }

    @DeleteMapping("/prs/issue-comments/{commentId}")
    public Map<String, String> deleteIssueComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo)
    {
        pullRequests.deleteIssueComment(repo, commentId);
        return ImmutableMap.of("result", "deleted");
    }

    @DeleteMapping("/prs/review-comments/{commentId}")
    public Map<String, String> deleteReviewComment(
            @PathVariable long commentId,
            @RequestParam("repo") String repo)
    {
        pullRequests.deleteReviewComment(repo, commentId);
        return ImmutableMap.of("result", "deleted");
    }

    @PostMapping("/prs/reviewers")
    public Map<String, String> addReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("reviewer") String reviewer)
    {
        pullRequests.addRequestedReviewer(repo, number, reviewer);
        return ImmutableMap.of("result", "added");
    }

    @DeleteMapping("/prs/reviewers")
    public Map<String, String> removeReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("reviewer") String reviewer)
    {
        pullRequests.removeRequestedReviewer(repo, number, reviewer);
        return ImmutableMap.of("result", "removed");
    }

    @GetMapping("/prs/reviewers/suggested")
    public List<SuggestedReviewer> suggestedReviewers(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequests.getSuggestedReviewers(repo, number);
    }

    @GetMapping("/prs/metadata")
    public PullRequestService.MetadataChoices metadataChoices(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequests.getMetadataChoices(repo, number);
    }

    public record MetadataSelectionRequest(String value, boolean selected) {}

    @PostMapping("/prs/assignees")
    public Map<String, String> setAssignee(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody MetadataSelectionRequest request)
    {
        pullRequests.setPullRequestAssignee(repo, number, request.value(), request.selected());
        return ImmutableMap.of("result", "updated");
    }

    @PostMapping("/prs/labels")
    public Map<String, String> setLabel(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody MetadataSelectionRequest request)
    {
        pullRequests.setPullRequestLabel(repo, number, request.value(), request.selected());
        return ImmutableMap.of("result", "updated");
    }

    public record InlineCommentRequest(
            String body,
            String path,
            int line,
            String side,
            String commitId,
            Integer startLine,
            String startSide) {}

    @PostMapping("/prs/review-comments")
    public Map<String, String> createInlineReviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody InlineCommentRequest request)
    {
        pullRequests.createInlineReviewComment(
                repo, number, request.body(), request.path(), request.line(),
                request.side(), request.commitId(), request.startLine(), request.startSide());
        return ImmutableMap.of("result", "commented");
    }

    public record AddReactionRequest(String content) {}

    @PostMapping("/prs/{number}/reactions")
    public Map<String, String> addPullRequestReaction(
            @RequestParam("repo") String repo,
            @PathVariable int number,
            @RequestBody AddReactionRequest request)
    {
        pullRequests.addPullRequestReaction(repo, number, request.content());
        return ImmutableMap.of("result", "reacted");
    }

    @PostMapping("/prs/review-comments/{commentId}/reactions")
    public Map<String, String> addReviewCommentReaction(
            @RequestParam("repo") String repo,
            @PathVariable long commentId,
            @RequestBody AddReactionRequest request)
    {
        pullRequests.addReviewCommentReaction(repo, commentId, request.content());
        return ImmutableMap.of("result", "reacted");
    }

    @PostMapping("/prs/issue-comments/{commentId}/reactions")
    public Map<String, String> addIssueCommentReaction(
            @RequestParam("repo") String repo,
            @PathVariable long commentId,
            @RequestBody AddReactionRequest request)
    {
        pullRequests.addIssueCommentReaction(repo, commentId, request.content());
        return ImmutableMap.of("result", "reacted");
    }

    public record SetThreadResolvedRequest(boolean resolved) {}

    @PostMapping("/prs/review-threads/{rootCommentId}/resolved")
    public Map<String, String> setReviewThreadResolved(
            @PathVariable long rootCommentId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestBody SetThreadResolvedRequest request)
    {
        pullRequests.setReviewThreadResolved(repo, number, prId, rootCommentId, request.resolved());
        return ImmutableMap.of("result", request.resolved() ? "resolved" : "unresolved");
    }

    @PostMapping("/prs/approve")
    public Map<String, String> approve(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId)
    {
        pullRequests.approvePullRequest(repo, number, prId);
        return ImmutableMap.of("result", "approved");
    }

    @PostMapping("/prs/merge")
    public MergeResult merge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestParam(value = "strategy", required = false) String strategy)
    {
        return pullRequests.mergePullRequest(repo, number, prId, strategy);
    }

    @PostMapping("/prs/auto-merge")
    public Map<String, String> enableAutoMerge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestParam(value = "strategy", required = false) String strategy)
    {
        pullRequests.enableAutoMerge(repo, number, prId, strategy);
        return ImmutableMap.of("result", "auto-merge-enabled");
    }

    @DeleteMapping("/prs/auto-merge")
    public Map<String, String> disableAutoMerge(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId)
    {
        pullRequests.disableAutoMerge(repo, number, prId);
        return ImmutableMap.of("result", "auto-merge-disabled");
    }

    @DeleteMapping("/prs/merge-queue")
    public Map<String, String> dequeue(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId)
    {
        pullRequests.dequeuePullRequest(repo, number, prId);
        return ImmutableMap.of("result", "dequeued");
    }

    @PostMapping("/prs/handle")
    public Map<String, String> markHandled(
            @RequestParam("id") long prId,
            @RequestParam(value = "action", defaultValue = "MANUAL") HandledAction action)
    {
        pullRequests.markHandled(prId, action);
        return ImmutableMap.of("result", "handled");
    }

    @PostMapping("/prs/reopen")
    public Map<String, String> reopen(@RequestParam("id") long prId)
    {
        pullRequests.reopen(prId);
        return ImmutableMap.of("result", "reopened");
    }

    @PostMapping("/prs/snooze")
    public Map<String, String> snooze(
            @RequestParam("id") long prId,
            @RequestParam("until") String untilIso)
    {
        try {
            pullRequests.snooze(prId, Instant.parse(untilIso));
        }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "Bad 'until' value, expected ISO-8601: " + untilIso);
        }
        return ImmutableMap.of("result", "snoozed");
    }

    @PostMapping("/prs/unsnooze")
    public Map<String, String> unsnooze(@RequestParam("id") long prId)
    {
        pullRequests.unsnooze(prId);
        return ImmutableMap.of("result", "unsnoozed");
    }

    @PostMapping("/prs/snooze/wake-reason/clear")
    public Map<String, String> clearSnoozeWakeReason(@RequestParam("id") long prId)
    {
        pullRequests.clearSnoozeWakeReason(prId);
        return ImmutableMap.of("result", "cleared");
    }
}
