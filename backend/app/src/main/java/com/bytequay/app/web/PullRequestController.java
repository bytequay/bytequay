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

import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final PullRequestRefreshService refreshService;

    public PullRequestController(PullRequestService pullRequests, PullRequestRefreshService refreshService)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.refreshService = requireNonNull(refreshService, "refreshService is null");
    }

    @GetMapping("/prs")
    public PullRequestPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search)
    {
        return pullRequests.listPullRequests(page, size, search);
    }

    @GetMapping("/prs/users")
    public List<RepoUser> users(@RequestParam("repo") String repo)
    {
        return pullRequests.listRepositoryUsers(repo);
    }

    @GetMapping("/prs/labels")
    public List<RepositoryLabel> labels(@RequestParam("repo") String repo)
    {
        return pullRequests.listRepositoryLabels(repo);
    }

    @PostMapping("/api/settings/sync/trigger")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void triggerSync()
    {
        refreshService.refreshDashboard();
    }

    @GetMapping("/prs/detail")
    public PullRequestDetail detail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestDetail(repo, number);
    }

    @GetMapping("/prs/{prId}/detail")
    public PullRequestDetail detail(@PathVariable long prId)
    {
        return pullRequests.getPullRequestDetail(prId);
    }

    @PostMapping("/prs/detail/refresh")
    public PullRequestDetailRefresh refreshDetail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return refreshService.refreshDetail(repo, number);
    }

    @PostMapping("/prs/{prId}/detail/refresh")
    public PullRequestDetailRefresh refreshDetail(@PathVariable long prId)
    {
        return refreshService.refreshDetail(prId);
    }

    @PostMapping("/prs/detail/refresh-all")
    public PullRequestDetail refreshAllDetail(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        return refreshService.refreshAllDetail(repo, number);
    }

    @PostMapping("/prs/{prId}/detail/refresh-all")
    public PullRequestDetail refreshAllDetail(@PathVariable long prId)
    {
        return refreshService.refreshAllDetail(prId);
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

    @PostMapping("/prs/diffFiles/refresh")
    public List<DiffFile> refreshDiffFiles(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha)
    {
        return refreshService.refreshDiffFiles(repo, number, baseSha, headSha);
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

    @PostMapping("/prs/diffContent/refresh")
    public DiffFileContent refreshDiffContent(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("path") String path,
            @RequestParam(value = "base", required = false) String baseSha,
            @RequestParam(value = "head", required = false) String headSha)
    {
        return refreshService.refreshDiffFileContent(
                repo, number, path, baseSha, headSha);
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

    public record MetadataRequest(List<String> assignees, List<String> labels) {}

    public record ReviewerRequest(String login, boolean requested) {}

    @PatchMapping("/prs/metadata")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateMetadata(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody MetadataRequest request)
    {
        requireNonNull(request, "body is required");
        pullRequests.updateMetadata(repo, number, request.assignees(), request.labels());
    }

    @PatchMapping("/prs/reviewers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateReviewer(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody ReviewerRequest request)
    {
        requireNonNull(request, "body is required");
        pullRequests.updateReviewerRequest(repo, number, request.login(), request.requested());
    }

    @PostMapping("/prs/comment")
    public Map<String, String> comment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody CommentRequest request)
    {
        pullRequests.commentOnPullRequest(repo, number, request.body());
        return ImmutableMap.of("result", "commented");
    }

    @PostMapping("/prs/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        pullRequests.closePullRequest(repo, number);
    }

    @PostMapping("/prs/checks/rerun-failed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rerunFailedChecks(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        pullRequests.rerunFailedChecks(repo, number);
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
