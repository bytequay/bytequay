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

import com.bytequay.app.scheduler.PullRequestSyncJob;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoUser;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

@RestController
public class PullRequestController
{
    private final PullRequestService pullRequests;
    private final PullRequestSyncJob syncJob;

    public PullRequestController(PullRequestService pullRequests, PullRequestSyncJob syncJob)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.syncJob = requireNonNull(syncJob, "syncJob is null");
    }

    @GetMapping("/prs")
    public List<PullRequest> list()
    {
        return pullRequests.listPullRequests();
    }

    @GetMapping("/prs/users")
    public List<RepoUser> users(@RequestParam("repo") String repo)
    {
        return pullRequests.listRepositoryUsers(repo);
    }

    @PostMapping("/api/settings/sync/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void triggerSync()
    {
        syncJob.requestImmediateSync();
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
            @RequestParam("number") int number)
    {
        return pullRequests.refreshPullRequestDetail(repo, number);
    }

    @GetMapping("/prs/diffFiles")
    public List<DiffFile> diffFiles(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha)
    {
        return baseSha == null && headSha == null
                ? pullRequests.getPullRequestDiffFiles(repo, number)
                : pullRequests.getPullRequestDiffFiles(repo, number, baseSha, headSha);
    }

    @GetMapping("/prs/diffCommits")
    public List<DiffCommit> diffCommits(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestDiffCommits(repo, number);
    }

    @GetMapping("/prs/diffContent")
    public DiffFileContent diffContent(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("path") String path,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha)
    {
        return baseSha == null && headSha == null
                ? pullRequests.getPullRequestDiffFileContent(repo, number, path)
                : pullRequests.getPullRequestDiffFileContent(repo, number, path, baseSha, headSha);
    }

    @GetMapping("/prs/merge-mode")
    public Map<String, Boolean> mergeMode(
            @RequestParam("repo") String repo,
            @RequestParam("branch") String branch)
    {
        return ImmutableMap.of("mergeQueue", pullRequests.hasMergeQueue(repo, branch));
    }

    public record CommentRequest(String body) {}

    public record ReviewRequest(String event, String body, List<InlineReviewComment> comments) {}

    public record ResolutionRequest(boolean resolved) {}

    @PostMapping("/prs/comment")
    public Map<String, String> comment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody CommentRequest request)
    {
        pullRequests.commentOnPullRequest(repo, number, request.body());
        return ImmutableMap.of("result", "commented");
    }

    @PostMapping("/prs/review-comments/{commentId}/replies")
    public Map<String, String> replyToReviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable("commentId") long commentId,
            @RequestBody CommentRequest request)
    {
        pullRequests.replyToReviewComment(repo, number, commentId, request.body());
        return ImmutableMap.of("result", "replied");
    }

    @PostMapping("/prs/review-comments/{commentId}/resolution")
    public Map<String, String> setReviewThreadResolved(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable("commentId") long commentId,
            @RequestBody ResolutionRequest request)
    {
        pullRequests.setReviewThreadResolved(repo, number, commentId, request.resolved());
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

    @PostMapping("/prs/review")
    public Map<String, String> review(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId,
            @RequestBody ReviewRequest request)
    {
        if (request.comments() == null) {
            pullRequests.submitReview(repo, number, prId, request.event(), request.body());
        }
        else {
            pullRequests.submitReview(repo, number, prId, request.event(), request.body(), request.comments());
        }
        return ImmutableMap.of("result", "reviewed");
    }

    @PostMapping("/prs/review-comment")
    public Map<String, String> reviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody InlineReviewComment comment)
    {
        pullRequests.createReviewComment(repo, number, comment);
        return ImmutableMap.of("result", "commented");
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
}
