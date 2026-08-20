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
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoUser;
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
    public List<DiffFile> diffFiles(@RequestParam("repo") String repo, @RequestParam("number") int number)
    {
        return pullRequests.getPullRequestDiffFiles(repo, number);
    }

    @GetMapping("/prs/merge-mode")
    public Map<String, Boolean> mergeMode(
            @RequestParam("repo") String repo,
            @RequestParam("branch") String branch)
    {
        return Map.of("mergeQueue", pullRequests.hasMergeQueue(repo, branch));
    }

    public record CommentRequest(String body) {}

    @PostMapping("/prs/comment")
    public Map<String, String> comment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestBody CommentRequest request)
    {
        pullRequests.commentOnPullRequest(repo, number, request.body());
        return Map.of("result", "commented");
    }

    @PostMapping("/prs/review-comments/{commentId}/replies")
    public Map<String, String> replyToReviewComment(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @PathVariable("commentId") long commentId,
            @RequestBody CommentRequest request)
    {
        pullRequests.replyToReviewComment(repo, number, commentId, request.body());
        return Map.of("result", "replied");
    }

    @PostMapping("/prs/approve")
    public Map<String, String> approve(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number,
            @RequestParam("id") long prId)
    {
        pullRequests.approvePullRequest(repo, number, prId);
        return Map.of("result", "approved");
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
